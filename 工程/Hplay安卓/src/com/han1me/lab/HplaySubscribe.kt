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
 * **关注 / 取关作者**（第 345 轮建立，第 347 轮改为页面内 fetch，第 348 轮改为**预热 + 即时提交**）。
 *
 * ## 真机证据链（按轮次累积，别删）
 *
 * @@
 * [11:27:22.448] GET  hanime1.me/          -> 200  Server=cloudflare
 * [11:27:22.937] POST hanime1.me/subscribe -> 403  Cf-Mitigated=challenge
 * @@
 *
 * 第 347 轮排除掉「请求头」方向：同一台机器试了**四种**头部形态
 * （文档导航式 / Plus 式带 XHR / Viewer 式最精简 / 桌面 UA），**全部 403**；
 * CloudflareInterceptor 每次都重放过一次，重放仍被挑战。同时段 GET 全是 200。
 * 结论：cf_clearance 对**非导航的 POST** 不生效，CF 的 WAF 只放行页面上下文里发起的请求。
 *
 * ## 第 348 轮：为什么之前"点了要卡几秒"
 *
 * 真机日志（2026-11-27 16:28:38 - 16:28:45）把 7 秒拆得清清楚楚：
 *
 * @@
 * 16:28:38.645  POST /subscribe -> 403      <- OkHttp 形态 0
 * 16:28:39.366  GET  hanime1.me/ -> 200                                          (0.7s)
 * 16:28:40.589  POST /subscribe -> 403      <- 形态 1                             (1.2s)
 * 16:28:41.312  GET  hanime1.me/ -> 200
 * 16:28:42.329  POST /subscribe -> 403      <- 形态 2                             (1.1s)
 * 16:28:42.950  GET  hanime1.me/ -> 200
 * 16:28:44.072  POST /subscribe -> 403      <- 形态 3                             (1.1s)
 * 16:28:45.200  GET  watch?... -> 200      <- WebView 这时才开始加载详情页
 * ... 页面挂 hCaptcha / jsdelivr / gstatic / 广告等十几个第三方资源 ...
 * @@
 *
 * 三个真凶，全部在本轮修掉：
 *
 * ① **四次注定失败的 OkHttp 重试白烧 6.3 秒**。
 *    它们从来不会成功（WAF 语义决定），每轮还要先 GET 一次首页刷 CSRF 令牌。
 *    本轮**整段删除**，直接走页面内提交。
 *
 * ② **WebView 在点击那一刻才冷启动**。加载详情页 + 十几个第三方资源本身要 2-4 秒。
 *    本轮改成**打开详情页时预热**（[prewarm]）：用户读简介、决定要不要关注的这几秒里，
 *    页面已经在后台加载好了；真点下去时只差一次 fetch。
 *
 * ③ **轮询间隔 1200ms**（第 347 轮的 onPageReady）。
 *    fetch 本身只要 200-400ms，却被 1.2 秒的轮询粒度拖成"至少等一档"。
 *    本轮改成 **70ms** 轮询、首轮立即执行。
 *
 * 顺带修掉一个用户能直接感觉到的 bug：
 * ④ 不可见 WebView 之前用 addView(fl, params) 加在 **decorView 最上层**，
 *    全屏尺寸 + alpha 0.01 —— **alpha 不影响触摸派发**，于是那 4 秒里用户在详情页
 *    的任何点击都被这个看不见的 WebView 吃掉。本轮改为 addView(fl, 0, params)
 *    压到 z 序最底，并且 isClickable/isFocusable 全关。
 *
 * ## 期望时间线（预热命中时）
 *
 * @@
 * 点击 -> evaluateJavascript(填表 + fetch)  ~20ms
 *      -> 页面 fetch 往返                    ~250ms
 *      -> 轮询读到状态码（70ms 粒度）         ~70ms
 * 合计 ~0.35s，且按钮在按下瞬间就已翻转（乐观更新，见 patch_subscribe_instant）
 * @@
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
 * · 1x1 不行（挑战脚本不跑），要有真实 viewport；alpha 用 0.01 不用 0f
 *   （0f 会被 JS 判定为不可见）；
 * · 任何异常都返回 false/抛异常，由调用方如实提示，不假装成功。
 */
object HplaySubscribe {

    /** 单次提交的整体超时（秒）。预热命中时 0.4 秒就结束，这里是兜底。 */
    private const val TIMEOUT_S = 8L

    /** 轮询粒度（毫秒）。第 347 轮是 1200，这就是"卡几秒"的第三个原因。 */
    private const val POLL_MS = 70L

    /** 轮询上限次数：70ms x 110 约 7.7 秒。 */
    private const val POLL_MAX = 110

    /**
     * 预热页**空闲保留**时长。
     *
     * 第 348 轮第一版给的是 25 秒 —— 真机实测踩坑：用户读简介超过 25 秒
     * （这不是慢，这是正常阅读），页面正好在点下去那一刻被回收，
     * 于是又走一遍「冷启动 WebView + 加载详情页」，回到 3 秒。
     * 90 秒足够覆盖「看简介 - 想一下 - 点关注」的正常节奏。
     */
    private const val LINGER_MS = 90_000L

    /** **离开详情页**后的回收延时（比空闲保留短得多：用户已经走了）。 */
    private const val RELEASE_MS = 6_000L

    // ---------------- 会话（一个就够，同一时刻只可能看一个详情页） ----------------

    private val lock = Any()

    @Volatile
    private var sess: Session? = null

    private class Session {
        var web: WebView? = null
        var holder: android.widget.FrameLayout? = null
        var url: String = ""
        var busy = false
        var dropped = false
        var linger: Runnable? = null
    }

    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    // ---------------- 对外 API ----------------

    /**
     * **预热**：打开详情页时调，后台把页面加载好，等用户真点关注时只差一次 fetch。
     *
     * 幂等：同一 URL 已就绪就直接返回。切到另一个详情页会重建。
     */
    fun prewarm(videoUrl: String) {
        // 只预热**官方站**的详情页：关注表单只存在于 hanime1.me 的 watch 页。
        // 板块源（三次元那些站）的 url 里没有这个表单，预热它纯属白耗流量与内存。
        if (videoUrl.isBlank()) return
        if (!videoUrl.contains("hanime1.me")) return
        main.post {
            val cur = sess
            if (cur != null && !cur.dropped && cur.url == videoUrl) {
                // 同一页：重置空闲计时即可（用户可能从相关影片点回来）
                scheduleLingerLocked(cur, LINGER_MS)
                return@post
            }
            dropLocked()
            buildLocked(videoUrl)?.let { scheduleLingerLocked(it, LINGER_MS) }
        }
    }

    /**
     * 兜底回收计时。
     *
     * 为什么需要它而不只靠 releaseLater：用户可能用系统返回手势退出详情页
     * （BackHandler 直接把 detail 置 null，走不到 onClose），那样这个不可见 WebView
     * 会一直挂着 —— 它占着一个渲染进程与若干个连接。统一由这里兜住：
     * 无论怎么退出详情页，LINGER_MS 之后都回收。提交进行中（busy）时不回收。
     */
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
     * 关注 / 取关。**立即**返回结果（预热命中时 < 0.5 秒）。
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
        // subscribe-status：已关注发空串、未关注发 "1"（目标状态语义，照两个参考源码）
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
            }
        }

        val waited = runCatching { latch.await(TIMEOUT_S, TimeUnit.SECONDS) }.getOrDefault(false)
        val ok = waited && out[0]
        if (!ok) {
            LabLog.i("webpost", "页面内提交失败：" + (if (waited) note[0] else "超时 " + TIMEOUT_S + "s"))
            throw IllegalStateException("Cloudflare 挑战，页面内提交未通过" + if (note[0].isNotBlank()) "（" + note[0] + "）" else "")
        }
        // 站点侧状态已变：清掉短缓存，让「我的订阅」与详情页下次重新抓
        runCatching { com.javrocket.hanime.HanimeClient.invalidateAllHtml() }
        runCatching { com.javrocket.hanime.HanimeClient.invalidateDetailCache() }
        return subscribe
    }

    // ---------------- 内部：建 / 拆 WebView ----------------

    /**
     * 后台把代理准备好再回主线程建 WebView —— CfForward.ensureStarted() 可能阻塞，
     * 在主线程调会卡界面（这正是"点了卡一下"的观感来源之一）。
     */
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
        // 把 OkHttp 侧的登录 cookie 灌进来（CookieManager 与 cookieJar 不自动同步）
        runCatching {
            val raw = CfCookieBridge.allCookieHeader("https://hanime1.me/")
            if (raw.isNotBlank()) {
                for (kv in raw.split(";")) {
                    val one = kv.trim()
                    if (one.isEmpty() || !one.contains("=")) continue
                    cm.setCookie("https://hanime1.me/", one)
                    cm.setCookie("https://.hanime1.me/", one)
                }
                cm.flush()
                LabLog.i("webpost", "已灌入 cookie " + raw.length + " 字节")
            }
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): android.webkit.WebResourceResponse? =
                runCatching { HplayWebProxy.intercept(request) }.getOrNull()
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
        // ④ 压到 z 序最底：alpha 0.01 不挡绘制，但**不挡触摸**，
        //    加在最上层会让用户在详情页的那几秒点不动（第 347 轮真机可感知）。
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
        val w = s.web
        if (w == null) {
            // 视图还没建好（预热刚起）：等它就绪再提交，最多 4 秒
            var waited = 0L
            val t = object : Runnable {
                override fun run() {
                    val v = s.web
                    if (v != null) {
                        submitLocked(s, userId, artistId, status, done)
                        return
                    }
                    waited += 100
                    if (waited > 4000L || s.dropped) {
                        done(false, "WebView 未就绪")
                        return
                    }
                    main.postDelayed(this, 100)
                }
            }
            main.postDelayed(t, 100)
            return
        }
        // ★★★ 第 348 轮修（真机日志抓到的 bug）：**轮询器泄漏**。
        //
        // 上一版的 run() 末尾无条件 main.postDelayed(this, POLL_MS)，
        // 而 done() 只是 countDown latch —— 轮询器自己并不知道已经结束，
        // 于是**每 70 毫秒永久 evaluateJavascript 一次**。真机日志里同一个
        // "页面内 fetch -> HTTP 200" 被打印了几十次、时间戳一路涨到 7770ms，
        // 就是这个：主线程持续唤醒 + 持续和 WebView 通信，纯耗电。
        // 现在用 finished 标志在所有出口拦住。
        var finished = false
        var tries = 0
        var firstFill = true
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
                if (tries++ > POLL_MAX) {
                    stop(false, "页面内 fetch 无回包")
                    return
                }
                // ★★★ 第 348 轮修（真机抓到）：**必须先复位再读状态**。
                //
                // 预热会话是**复用**的，上一次提交留下的 window.__hpSubCode = 200
                // 会一直挂在页面里。第一轮就去读它，会立刻读到上一次的 200 ——
                // 于是「提交」根本没发生，界面却报成功。真机实测第二次点击
                // 只花 70ms 就"成功"，就是读到了这个陈旧值。
                //
                // 所以第一轮**只发复位 + 填表 + fetch**，不读结果；下一轮才开始读。
                if (firstFill) {
                    firstFill = false
                    w.evaluateJavascript(jsFill(userId, artistId, status, true)) { v ->
                        val flag = v?.replace("\u0022", "").orEmpty()
                        if (flag.contains("err")) LabLog.i("webpost", "填表 " + flag.take(90))
                    }
                    main.postDelayed(this, POLL_MS)
                    return
                }
                w.evaluateJavascript("(window.__hpSubCode===undefined?-1:window.__hpSubCode)") { raw ->
                    if (finished) return@evaluateJavascript
                    val code = raw?.replace("\u0022", "")?.trim()?.toIntOrNull() ?: -1
                    if (code > 0) {
                        if (!finished) {
                            LabLog.i("webpost", "页面内 fetch -> HTTP " + code + "（" + (tries * POLL_MS) + "ms）")
                        }
                        stop(code in 200..399, if (code in 200..399) "" else "HTTP " + code)
                        return@evaluateJavascript
                    }
                    if (code == -2) {
                        LabLog.i("webpost", "页面内 fetch 抛异常")
                        stop(false, "fetch 异常")
                        return@evaluateJavascript
                    }
                    // 状态码还是 -1：说明 fetch 还没回来，继续轮询即可（不重复提交）。
                }
                if (!finished) main.postDelayed(this, POLL_MS)
            }
        }
        main.post(r)
    }

    /**
     * 填表单 + 同源 fetch。所有分支都回一个可判定的字符串，不抛异常出去。
     *
     * @@begin == true@@ 时先清掉上一次的提交标记 —— 预热会话会被反复复用，
     * 不清就会一直返回 "dup"，看起来像"点了没反应"。
     */
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