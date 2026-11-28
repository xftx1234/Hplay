package com.han1me.lab

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **关注 / 取关作者**（第 345 轮建立；第 347/348 轮改页面内 fetch；**第 355 轮加 OkHttp 快路**）。
 *
 * ## 第 355 轮为什么改（用户原话）
 *
 * 「关注功能会随内置节点网络变化而出现无法关注问题，优化机制，让 cookie 可实现包含
 *   全部云端账户请求，或者在最初 cf 验证时就一次解决所有带 cf 验证的相关问题」。
 *
 * 上一版**只有一条路**：起一个不可见 WebView、在页面上下文里 fetch。它的失败面很大：
 *
 *  · 页面被 CF 挑战（换节点后常发生）⇒ 页面里没有 `#video-subscribe-form`，fetch 无从发起；
 *  · WebView 冷启动 + 十几个第三方资源 ⇒ 2~4 秒，用户以为没点上；
 *  · 会话被回收 / 主线程忙 ⇒ 轮询拿不到回包，报「页面内 fetch 无回包」。
 *
 * 而同一批日志里 **`POST hanime1.me/like -> 200`** 是通的 —— 说明在这个节点上，
 * 带 clearance 的 OkHttp POST 本来就能过 CF。所以正确做法是**两条路都留着，先走快的那条**：
 *
 * ```
 *  ① OkHttp 直提（<300ms）   —— 成了就结束；403/挑战才继续
 *  ② WebView 页面内 fetch     —— 真正的兜底（浏览器的 TLS 指纹 + 页面上下文）
 *  ③ 两条都不行               —— 如实报错，并提示去首页过一次盾
 * ```
 *
 * ## 一秒卡顿（用户第 2 点）
 *
 * 上一版在**成功后仍把那个全屏 WebView 挂着 90 秒**（LINGER_MS，为"用户可能再点一次"设计）。
 * 它的 alpha 只有 0.01，但 **alpha 不影响合成开销** —— 详情页上永远压着一层全屏渲染层，
 * 主线程每次重组都要多算一层，于是"关注成功后卡一下"。
 *
 * 现在：**成功/失败都已定论就立刻降为短回收（600ms）**，下次点关注重新预热即可
 *（预热本身在打开详情页时就跑了，用户感受不到）。
 *
 * ## WebView 的四项配置（缺一项都过不去 CF 挑战页）
 *
 * 照本项目唯一能过盾的 HplayGateActivity：
 *  ① CfUa.USER_AGENT（clearance 与 UA 强绑定）
 *  ② setAcceptThirdPartyCookies(wv, true)
 *  ③ ProxyController + CfForward（WebView 不读系统 http_proxy）
 *  ④ MIXED_CONTENT_ALWAYS_ALLOW（挑战页 https 里嵌 http 资源）
 *
 * ## 纪律
 *
 * · WebView 在主线程创建（Android 硬要求）；调用线程用 latch 等，有硬超时；
 * · 1x1 不行（挑战脚本不跑），要有真实 viewport；alpha 用 0.01 不用 0f；
 * · 任何异常都返回 false/抛异常，由调用方如实提示，不假装成功。
 */
object HplaySubscribe {

    /**
     * 单次提交的整体超时（秒）。
     *
     * ★ 第 355 轮真机实测校准：冷启动（代理 → 建 WebView → 加载详情页 → 探针就绪）实测 7.2 秒，
     * 再加一次页面内 fetch 1.2 秒 ≈ 8.5 秒。上一版给 10 秒 —— 只差 1.5 秒就踩线，
     * 真机上「第二次点关注」直接报「超时 10s」（20:50:09 日志实锤）。
     * 现在给 20 秒：预热命中时仍然是 0.3~1.3 秒结束，只有冷启动才会用到这个上限。
     */
    private const val TIMEOUT_S = 20L

    /** OkHttp 快路的超时（秒）：超了就转 WebView，不再等。 */
    private const val QUICK_TIMEOUT_S = 8L

    /** 轮询粒度（毫秒）。 */
    private const val POLL_MS = 70L

    /** 轮询上限次数：70ms x 110 约 7.7 秒。 */
    private const val POLL_MAX = 110

    /** 预热延迟：让详情页先画出来，别在首帧上建 WebView。 */
    private const val PREWARM_DELAY_MS = 900L

    /** 预热页**空闲保留**时长（用户还在读简介时别回收）。 */
    private const val LINGER_MS = 90_000L

    /** **离开详情页**后的回收延时。 */
    private const val RELEASE_MS = 6_000L

    /** ★ 第 355 轮：**操作已定论后的回收延时**（修"关注成功后卡一秒"）。 */
    /**
     * 操作已定论后的回收延时。
     *
     * 不能太短：用户「关注 → 发现点错了 → 立刻取关」是常见动作，页面还在就得复用；
     * 也不能像原来那样挂 90 秒（那层全屏 WebView 一直压着，用户报「关注成功后卡一秒」）。
     * 1.5 秒是权衡点：足够覆盖连点，又不让那层合成层长期存在。
     */
    private const val AFTER_DONE_MS = 1500L

    // ---------------- 会话（一个就够，同一时刻只可能看一个详情页） ----------------

    private val lock = Any()

    @Volatile
    private var sess: Session? = null

    /** 最近一次「本节点拒绝 POST」的 host（见 toggle 里的 skipQuick）。 */
    @Volatile private var quickBlockedHost: String = ""

    /** 最近一次「本节点拒绝 POST」的时间戳。 */
    @Volatile private var quickBlockedAt: Long = 0L

    private class Session {
        var web: WebView? = null
        var holder: android.widget.FrameLayout? = null
        var url: String = ""
        var busy = false
        var dropped = false
        /** ★ 第 355 轮：页面是否已 load 完成（未完成时提交必然"无回包"）。 */
        @Volatile var loaded = false
        /** ★ 第 355 轮：当前页面是不是 CF 挑战页（是的话 fetch 一定失败，直接报原因）。 */
        @Volatile var challenged = false
        var linger: Runnable? = null
    }

    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    // ---------------- 对外 API ----------------

    /**
     * 预热：打开详情页时调，后台把页面加载好。幂等。
     *
     * ★ 第 355 轮：**延迟 900ms 再建**。
     *
     * 上一版是立刻 `main.post { buildLocked() }` —— 而 createView 要在主线程 `new WebView`，
     * 那是 200~600ms 的同步开销，正好压在详情页首帧组合上（用户报的「卡一下」）。
     * 900ms 足够详情页画完两帧；用户读简介本来就要几秒，预热仍然赶得上。
     */
    fun prewarm(videoUrl: String) {
        if (videoUrl.isBlank()) return
        // 只预热**官方站**的详情页：关注表单只存在于 hanime1.me 的 watch 页。
        if (!videoUrl.contains("hanime1.me")) return
        main.postDelayed({
            val cur = sess
            if (cur != null && !cur.dropped && cur.url == videoUrl) {
                scheduleLingerLocked(cur, LINGER_MS)
                return@postDelayed
            }
            dropLocked()
            buildLocked(videoUrl)?.let { scheduleLingerLocked(it, LINGER_MS) }
        }, PREWARM_DELAY_MS)
    }

    /** 兜底回收计时（用户可能用返回手势退出详情页，走不到 releaseLater）。 */
    private fun scheduleLingerLocked(s: Session, delayMs: Long) {
        s.linger?.let { main.removeCallbacks(it) }
        val r = Runnable {
            if (!(sess?.busy ?: false)) dropLocked()
        }
        s.linger = r
        main.postDelayed(r, delayMs)
    }

    /** 离开详情页：延迟回收（用户可能马上又点回来）。 */
    fun releaseLater() {
        main.post {
            val cur = sess ?: return@post
            if (cur.busy) return@post
            scheduleLingerLocked(cur, RELEASE_MS)
        }
    }

    /**
     * 关注 / 取关。**先走 OkHttp 快路，失败再回落到 WebView 页面内 fetch**。
     *
     * @param videoUrl 详情页地址（WebView 要打开它才能拿到站方自己的表单）
     * @param userId   #video-subscribe-form 的 subscribe-user-id
     * @param artistId 同上，subscribe-artist-id
     * @param subscribe true = 关注，false = 取关
     * @return 操作后的状态；失败抛异常（不假装成功）
     */
    fun toggle(
        videoUrl: String,
        userId: String,
        artistId: String,
        subscribe: Boolean,
    ): Boolean {
        if (videoUrl.isBlank()) throw IllegalStateException("缺少详情页地址")
        if (userId.isBlank() || artistId.isBlank()) throw IllegalStateException("缺少关注所需的 id")

        // ---------- ① OkHttp 快路 ----------
        //
        // ★ 第 355 轮真机实测（20:44:09 日志）：同一节点上
        //     GET  hanime1.me/          -> 200
        //     POST hanime1.me/subscribe -> 403 Cf-Mitigated=challenge
        // 也就是说这条路上**注定失败**，每点一次白烧 0.7 秒再回落到 WebView。
        // 所以记住「本机这个节点刚拒过 POST」——5 分钟内直接走 WebView，不再试。
        // 换节点 / 过盾后（HplayCfHolder.endCooldown）会清掉这条记忆。
        val h = runCatching { java.net.URI(videoUrl).host.orEmpty().lowercase() }.getOrDefault("")
        val skipQuick = h.isNotBlank() && h == quickBlockedHost &&
            System.currentTimeMillis() - quickBlockedAt < 5 * 60_000L
        if (!skipQuick) {
            val quick = runCatching { quickHttp(videoUrl, userId, artistId, subscribe) }.getOrNull()
            if (quick != null) {
                LabLog.i("webpost", "注意：本次由 OkHttp 快路完成（不经 WebView）")
                invalidateCaches()
                recycleSoon()
                return quick
            }
        } else {
            LabLog.i("webpost", "本节点 5 分钟内拒过 POST，直接走页面内提交")
        }

        // ---------- ② WebView 页面内 fetch ----------
        val status = if (subscribe) "" else "1"
        val latch = CountDownLatch(1)
        val out = booleanArrayOf(false)
        val note = arrayOf("")

        main.post {
            val cur = sess
            val s = if (cur != null && !cur.dropped && cur.url == videoUrl) cur else {
                dropLocked()
                buildLocked(videoUrl)
            }
            if (s == null) {
                note[0] = "WebView 创建失败"
                latch.countDown()
                return@post
            }
            s.busy = true
            s.linger?.let { main.removeCallbacks(it) }
            s.linger = null
            submitLocked(s, userId, artistId, status) { ok, why ->
                s.busy = false
                out[0] = ok
                note[0] = why
                latch.countDown()
                // ★ 第 355 轮：定论后立刻降为短回收（修"成功后卡一秒"）
                scheduleLingerLocked(s, AFTER_DONE_MS)
            }
        }

        val waited = runCatching { latch.await(TIMEOUT_S, TimeUnit.SECONDS) }.getOrDefault(false)
        val ok = waited && out[0]
        if (!ok) {
            val why = if (waited) note[0] else "超时 " + TIMEOUT_S + "s"
            LabLog.i("webpost", "页面内提交失败：" + why)
            throw IllegalStateException("关注没成功（" + why + "）。可在首页点「CF 验证」过一次盾再试")
        }
        invalidateCaches()
        recycleSoon()
        return subscribe
    }

    /** 站点侧状态已变：清掉短缓存，让「我的订阅」与详情页下次重新抓。 */
    private fun invalidateCaches() {
        runCatching { com.javrocket.hanime.HanimeClient.invalidateAllHtml() }
        runCatching { com.javrocket.hanime.HanimeClient.invalidateDetailCache() }
    }

    /** 立刻把预热页降为 600ms 后回收（幂等）。 */
    private fun recycleSoon() {
        main.post {
            val s = sess ?: return@post
            if (s.busy) return@post
            scheduleLingerLocked(s, AFTER_DONE_MS)
        }
    }

    /**
     * **OkHttp 快路**：直接 POST /subscribe（与两个参考实现的字段完全一致）。
     *
     * @return 成功时的目标状态；**null = 没成（403 挑战 / 419 令牌 / 网络问题），应回落到 WebView**
     */
    private fun quickHttp(
        videoUrl: String,
        userId: String,
        artistId: String,
        subscribe: Boolean,
    ): Boolean? {
        val cli = runCatching {
            com.javrocket.hanime.HanimeClient.http.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(QUICK_TIMEOUT_S, TimeUnit.SECONDS)
                .callTimeout(QUICK_TIMEOUT_S, TimeUnit.SECONDS)
                .build()
        }.getOrNull() ?: return null

        val token = runCatching { com.javrocket.hanime.HanimeClient.csrfTokenPublic() }
            .getOrElse { return null }
        if (token.isBlank()) return null
        // 语义照两个参考源码：已关注发空串、未关注发 "1"（目标状态）
        val status = if (subscribe) "" else "1"
        val base = runCatching { com.javrocket.hanime.HanimeClient.baseUrlPublic() }
            .getOrDefault("https://hanime1.me")
        val form = okhttp3.FormBody.Builder()
            .add("_token", token)
            .add("subscribe-user-id", userId)
            .add("subscribe-artist-id", artistId)
            .add("subscribe-status", status)
            .build()
        val req = okhttp3.Request.Builder()
            .url(base + "/subscribe")
            .post(form)
            .header("X-CSRF-TOKEN", token)
            .header("Referer", videoUrl)
            .header("Origin", base)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
            .build()
        val code = runCatching {
            cli.newCall(req).execute().use { r ->
                LabLog.i("webpost", "OkHttp 快路 -> HTTP " + r.code)
                if (r.code == 403 || r.code == 419) {
                    quickBlockedHost = runCatching { java.net.URI(videoUrl).host.orEmpty().lowercase() }
                        .getOrDefault("")
                    quickBlockedAt = System.currentTimeMillis()
                }
                if (r.code in 200..399) {
                    val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                    // 回包认得出就按它，认不出就按意图（与参考实现一致）
                    val marked = Regex(
                        """"?(?:subscribed|is_subscribed|subscribe_status)"?\s*[:=]\s*"?([01])""",
                        RegexOption.IGNORE_CASE,
                    ).find(body)?.groupValues?.get(1)
                    return@use when (marked) {
                        "1" -> true
                        "0" -> false
                        else -> subscribe
                    }
                }
                null
            }
        }.getOrNull()
        return code
    }

    // ---------------- 内部：建 / 拆 WebView ----------------

    /** 后台把代理准备好再回主线程建 WebView（CfForward.ensureStarted 可能阻塞）。 */
    private fun buildLocked(videoUrl: String): Session? {
        val ctx = com.han1me.HplayEnv.applicationOrNull() ?: return null
        val s = Session()
        s.url = videoUrl
        sess = s
        Thread({
            runCatching { CfForward.ensureStarted() }
            main.post { runCatching { createView(ctx, s) }.onFailure {
                LabLog.i("webpost", "创建失败：" + it.javaClass.simpleName + " " + (it.message ?: ""))
            } }
        }, "hpla-sub-prep").apply { isDaemon = true }.start()
        return s
    }

    /** 探一次「页面是否已能提交」（见 createView 里对进度的说明）。 */
    private fun probeReady(w: WebView, s: Session) {
        w.evaluateJavascript(
            "(function(){var t=(document.title||'');" +
                "var h=(document.body?document.body.innerHTML:'');" +
                "if(document.getElementById('video-subscribe-form'))return 'ok';" +
                "if(t.indexOf('Just a moment')>=0||t.indexOf('Attention Required')>=0)return 'cf';" +
                "if(h.indexOf('challenge-platform')>=0)return 'cf';" +
                "return 'noform';})()",
        ) { v ->
            val flag = (v ?: "").replace("\u0022", "").trim()
            // noform 只有在主文档确实加载过之后才算结论；进度 75 时它通常已经在了。
            s.challenged = flag == "cf"
            s.loaded = flag == "ok" || flag == "noform"
            LabLog.i("webpost", "页面探针 " + flag)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(ctx: Context, s: Session) {
        if (s.dropped) return
        val app = ctx.applicationContext
        val cm = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val w = WebView(app)
        cm.setAcceptThirdPartyCookies(w, true)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            // ① 与 OkHttp 同一个 UA —— clearance 与 UA 强绑定
            userAgentString = CfUa.USER_AGENT
        }
        // ③ 代理走 ProxyController（WebView 不读系统 http_proxy）
        runCatching {
            val port = CfForward.port
            if (port > 0) {
                val cfg = androidx.webkit.ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:" + port)
                    .addDirect()
                    .build()
                androidx.webkit.ProxyController.getInstance().setProxyOverride(cfg, { it.run() }) {}
                LabLog.i("webpost", "WebView 代理 -> 127.0.0.1:" + port)
            }
        }
        // 把 OkHttp 侧的 cookie 灌进来（CookieManager 与 cookieJar 不自动同步）
        runCatching {
            val raw = CfCookieBridge.allCookieHeader("https://hanime1.me/")
            if (raw.isNotBlank()) {
                for (kv in raw.split(';')) {
                    val one = kv.trim()
                    if (one.isEmpty() || !one.contains('=')) continue
                    cm.setCookie("https://hanime1.me/", one)
                    cm.setCookie("https://.hanime1.me/", one)
                }
                cm.flush()
                LabLog.i("webpost", "已灌入 cookie " + raw.length + " 字节")
            }
        }

        // ★ 第 355 轮（真机 20:43:16 → 20:45:09 实测）：
        // `onPageFinished` 要等**所有子资源**（hCaptcha / jsdelivr / 十几个广告）都结束，
        // 那次花了 **113 秒**；而关注表单在 HTML 到达后 ~1 秒就已存在。
        // 所以进度信号改用 `onProgressChanged >= 75`（主文档+关键资源已就绪），
        // `onPageFinished` 只作为兜底补充。
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 75 || s.loaded || s.challenged) return
                probeReady(view ?: return, s)
            }
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): android.webkit.WebResourceResponse? =
                runCatching { HplayWebProxy.intercept(request) }.getOrNull()

            override fun onPageFinished(view: WebView, url: String?) {
                if (!s.loaded && !s.challenged) probeReady(view, s)
            }
        }

        // 真实尺寸（挑战脚本要 viewport）+ alpha=0.01（0f 会被判不可见）
        val fl = android.widget.FrameLayout(app)
        fl.alpha = 0.01f
        fl.isClickable = false
        fl.isFocusable = false
        fl.isFocusableInTouchMode = false
        val dw = com.han1me.HplayEnv.activity()?.window?.decorView
        val fw = if ((dw?.width ?: 0) > 0) dw!!.width else 720
        val fh = if ((dw?.height ?: 0) > 0) dw!!.height else 1280
        fl.addView(w, android.widget.FrameLayout.LayoutParams(fw, fh))
        val dv = dw as? android.view.ViewGroup
        if (dv == null) {
            runCatching { w.destroy() }
            s.dropped = true
            return
        }
        // ④ 压到 z 序最底：alpha 0.01 不挡绘制，但**不挡触摸**
        dv.addView(fl, 0, android.view.ViewGroup.LayoutParams(fw, fh))
        s.web = w
        s.holder = fl
        w.loadUrl(s.url)
        LabLog.i("webpost", "预热开始 " + s.url.take(64))
    }

    private fun dropLocked() {
        val s = sess ?: return
        sess = null
        s.dropped = true
        s.linger?.let { main.removeCallbacks(it) }
        s.linger = null
        s.web?.let { w ->
            runCatching { (s.holder?.parent as? android.view.ViewGroup)?.removeView(s.holder) }
            runCatching { w.stopLoading() }
            runCatching { w.destroy() }
        }
        s.web = null
        s.holder = null
        LabLog.i("webpost", "预热页回收")
    }

    // ---------------- 内部：提交与轮询 ----------------

    private fun submitLocked(
        s: Session,
        userId: String,
        artistId: String,
        status: String,
        done: (Boolean, String) -> Unit,
    ) {
        var finished = false
        var tries = 0
        var firstFill = true
        var waited = 0L
        val stop = { ok: Boolean, why: String ->
            if (!finished) {
                finished = true
                done(ok, why)
            }
        }
        val r = object : Runnable {
            override fun run() {
                if (finished) return
                if (s.dropped) {
                    stop(false, "会话已回收")
                    return
                }
                val w = s.web
                if (w == null) {
                    // 视图还没建好（预热刚起）：等它就绪再提交，最多 4 秒
                    waited += 100
                    if (waited > 4000L) {
                        stop(false, "WebView 未就绪")
                        return
                    }
                    main.postDelayed(this, 100)
                    return
                }
                // ★ 第 355 轮：页面还没 load 完就提交，必然「无回包」—— 先等页面。
                if (!s.loaded && !s.challenged) {
                    waited += POLL_MS
                    if (waited > 12_000L) {
                        stop(false, "页面加载超时（预热未完成）")
                        return
                    }
                    main.postDelayed(this, POLL_MS)
                    return
                }
                if (s.challenged) {
                    stop(false, "页面被 Cloudflare 挑战")
                    return
                }
                if (tries++ > POLL_MAX) {
                    stop(false, "页面内 fetch 无回包")
                    return
                }
                // 第一轮**只发复位 + 填表 + fetch**，不读结果（复用会话里可能有陈旧值）。
                if (firstFill) {
                    firstFill = false
                    w.evaluateJavascript(jsFill(userId, artistId, status, true)) { v ->
                        val flag = v?.replace("\u0022", "").orEmpty()
                        if (flag.contains("err")) LabLog.i("webpost", "填表 " + flag.take(90))
                        if (flag.contains("nof")) LabLog.i("webpost", "页面里没有关注表单")
                    }
                    main.postDelayed(this, POLL_MS)
                    return
                }
                w.evaluateJavascript("(window.__hpSubCode===undefined?-1:window.__hpSubCode)") { raw ->
                    if (finished) return@evaluateJavascript
                    val code = raw?.replace("\u0022", "")?.trim()?.toIntOrNull() ?: -1
                    if (code > 0) {
                        LabLog.i("webpost", "页面内 fetch -> HTTP " + code + "（" + (tries * POLL_MS) + "ms）")
                        stop(code in 200..399, if (code in 200..399) "" else "HTTP " + code)
                        return@evaluateJavascript
                    }
                    if (code == -2) {
                        LabLog.i("webpost", "页面内 fetch 抛异常")
                        stop(false, "fetch 异常")
                        return@evaluateJavascript
                    }
                }
                if (!finished) main.postDelayed(this, POLL_MS)
            }
        }
        main.post(r)
    }

    /** 填表单 + 同源 fetch（所有分支都回一个可判定的字符串）。 */
    private fun jsFill(userId: String, artistId: String, status: String, begin: Boolean): String =
        "(function(begin){" +
            "try{" +
            "if(begin){window.__hpSubmitting=0;window.__hpSubCode=undefined;}" +
            "var f=document.getElementById('video-subscribe-form');" +
            "if(!f){return 'nof';}" +
            "if(window.__hpSubmitting){return 'dup';}" +
            "window.__hpSubmitting=1;" +
            "window.__hpSubCode=undefined;" +
            "var u=f.querySelector('input[name=\u0022subscribe-user-id\u0022]');" +
            "var a=f.querySelector('input[name=\u0022subscribe-artist-id\u0022]');" +
            "var st=f.querySelector('input[name=\u0022subscribe-status\u0022]');" +
            "if(u)u.value='" + escapeJs(userId) + "';" +
            "if(a)a.value='" + escapeJs(artistId) + "';" +
            "if(st)st.value='" + escapeJs(status) + "';" +
            "var fd=new URLSearchParams(new FormData(f));" +
            "fetch(f.action||location.pathname,{method:'POST',body:fd,credentials:'include'," +
            "headers:{'Content-Type':'application/x-www-form-urlencoded'}})" +
            ".then(function(r){window.__hpSubCode=r.status;})" +
            ".catch(function(e){window.__hpSubCode=-2;});" +
            "return 'ok';" +
            "}catch(e){return 'err:'+e;}" +
            "})(" + (if (begin) "true" else "false") + ")"

    /** JS 单引号字符串转义。 */
    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")
}