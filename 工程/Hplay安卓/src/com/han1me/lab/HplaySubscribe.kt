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
 * **关注 / 取关作者**（第 345 轮建立，第 347 轮重写为「页面内 fetch」模型）。
 *
 * ## 真机证据链（按轮次累积，别删）
 *
 * ```
 * [11:27:22.448] GET  hanime1.me/          -> 200  Server=cloudflare
 * [11:27:22.937] POST hanime1.me/subscribe -> 403  Cf-Mitigated=challenge
 * ```
 *
 * 第 347 轮进一步排除掉「请求头」这个方向：同一台机器上试了**四种**头部形态
 * （文档导航式 / Plus 式带 XHR / Viewer 式最精简 / 桌面 UA），**全部 403**；
 * 而 CloudflareInterceptor 每次都重放过一次，重放仍被挑战。
 * 同时段的 GET 全是 200，clearance 也记录在位。
 *
 * 结论：`cf_clearance` 对**非导航的 POST** 不生效 —— CF 的 WAF 规则只放行
 * 页面上下文里发起的请求。这与两个参考源码的做法一致：
 * Viewer 的 `CloudflareInterceptor` 是「过盾 → latch.await → chain.proceed 重放」，
 * 但真正让关注生效的是**页面自己的表单提交**（WebView 承载）。
 *
 * ## 这里的做法
 *
 * ```
 * ① OkHttp 直发（快、无 UI）—— 四种形态都试，任一成功即返回
 * ② 全失败则起不可见 WebView 打开详情页，用**页面自己的表单**发 fetch
 * ```
 *
 * ② 为什么是 fetch 而不是 form.submit()：
 * form.submit() 是顶层导航，页面被换掉后读不到结果，只能靠「有没有第二次
 * onPageFinished」猜 —— 第 346 轮就是这么超时的。fetch 跑在同一上下文，
 * 能直接拿到 HTTP 状态码，也带着页面自己的 cookie 与浏览器指纹。
 *
 * ## WebView 的四项配置（缺一项都过不去 CF 挑战页）
 *
 * 照本项目唯一能过盾的 `HplayGateActivity`：
 *  ① `CfUa.USER_AGENT`（clearance 与 UA 强绑定）
 *  ② `setAcceptThirdPartyCookies(wv, true)`
 *  ③ `ProxyController` + `CfForward`（WebView 不读系统 http_proxy）
 *  ④ `MIXED_CONTENT_ALWAYS_ALLOW`（挑战页 https 里嵌 http 资源）
 *
 * ## 纪律
 *
 * · WebView 在主线程创建（Android 硬要求）；调用线程用 latch 等，有硬超时；
 * · 1×1 不行（挑战脚本不跑），要有真实 viewport；alpha 用 0.01 不用 0f
 *   （0f 会被 JS 判定为不可见）；
 * · 任何异常都返回 false，由调用方如实提示，不假装成功。
 */
object HplaySubscribe {

    /** 整体超时（秒）。页面加载 + CF 过盾 + fetch + 结果轮询，四段合计。 */
    private const val TIMEOUT_S = 35L

    /**
     * 关注 / 取关。
     *
     * @param videoUrl 详情页地址（WebView 要打开它才能拿到站方自己的表单）
     * @param userId   `#video-subscribe-form` 的 subscribe-user-id
     * @param artistId 同上，subscribe-artist-id
     * @param subscribe true = 关注，false = 取关
     * @return 操作后的状态；两条路都失败时抛异常（不假装成功）
     */
    fun toggle(
        videoUrl: String,
        userId: String,
        artistId: String,
        subscribe: Boolean,
    ): Boolean {
        // ① OkHttp 直发（内部已做四形态重试）
        val r = runCatching {
            com.javrocket.hanime.HanimeClient.subscribeArtist(userId, artistId, subscribe)
        }
        r.getOrNull()?.let { return it }
        val t = r.exceptionOrNull()
        val msg = t?.message.orEmpty()
        // 只有 WAF 挑战（403/419）才值得起 WebView；未登录之类起也没用
        val waf = msg.contains("403") || msg.contains("419")
        if (!waf) throw (t ?: IllegalStateException("关注失败"))

        // ② 不可见 WebView：用详情页自己的表单发 fetch
        val ctx = com.han1me.HplayEnv.applicationOrNull()
            ?: throw IllegalStateException(msg + "（无 Context，无法回落）")
        if (videoUrl.isBlank()) throw IllegalStateException(msg + "（缺详情页地址，无法回落）")
        // subscribe-status：已关注发空串、未关注发 "1"（照两个参考源码的目标状态语义）
        val status = if (subscribe) "" else "1"
        val ok = submitInPage(ctx, videoUrl, userId, artistId, status)
        if (!ok) throw IllegalStateException(msg + "（Cloudflare 挑战，页面内提交未通过）")

        // ③ 清缓存让详情页与订阅列表下次重抓（站点侧状态已变）
        runCatching { com.javrocket.hanime.HanimeClient.invalidateAllHtml() }
        runCatching { com.javrocket.hanime.HanimeClient.invalidateDetailCache() }
        return subscribe
    }

    /**
     * 在不可见 WebView 里打开 [pageUrl]，用页面自己的表单发一次同源 fetch。
     *
     * @return true = 站点回了 2xx/3xx
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun submitInPage(
        ctx: Context,
        pageUrl: String,
        userId: String,
        artistId: String,
        status: String,
    ): Boolean {
        val app = ctx.applicationContext
        val latch = CountDownLatch(1)
        val out = booleanArrayOf(false)
        val main = Handler(Looper.getMainLooper())
        var web: WebView? = null
        var holder: android.widget.FrameLayout? = null
        var finished = false
        var poller: Runnable? = null

        fun finish(v: Boolean) {
            if (finished) return
            finished = true
            out[0] = v
            latch.countDown()
        }

        // 填表单 + 同源 fetch。所有分支都回一个可判定的字符串，不抛异常出去。
        val jsFill = buildString {
            append("(function(){")
            append("try{")
            append("var f=document.getElementById('video-subscribe-form');")
            append("if(!f){return 'nof|'+location.href+'|'+document.title;}")
            append("if(window.__hpSubmitting)return 'dup';")
            append("window.__hpSubmitting=1;")
            append("var u=f.querySelector('input[name=\"subscribe-user-id\"]');")
            append("var a=f.querySelector('input[name=\"subscribe-artist-id\"]');")
            append("var s=f.querySelector('input[name=\"subscribe-status\"]');")
            append("if(u)u.value='" + escapeJs(userId) + "';")
            append("if(a)a.value='" + escapeJs(artistId) + "';")
            append("if(s)s.value='" + escapeJs(status) + "';")
            append("var fd=new URLSearchParams(new FormData(f));")
            append("fetch(f.action||location.pathname,{method:'POST',body:fd,credentials:'include',")
            append("headers:{'Content-Type':'application/x-www-form-urlencoded'}})")
            append(".then(function(r){window.__hpSubCode=r.status;})")
            append(".catch(function(e){window.__hpSubCode=-2;});")
            append("return 'ok';")
            append("}catch(e){return 'err:'+e;}")
            append("})()")
        }

        main.post {
            try {
                val cm = CookieManager.getInstance().apply { setAcceptCookie(true) }
                val w = WebView(app)
                web = w
                cm.setAcceptThirdPartyCookies(w, true)
                w.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    // ① 与 OkHttp 同一个 UA —— clearance 与 UA 强绑定
                    userAgentString = com.han1me.lab.CfUa.USER_AGENT
                }
                // ③ 代理走 ProxyController（WebView 不读系统 http_proxy）
                runCatching {
                    val port = CfForward.ensureStarted()
                    if (port > 0) {
                        val cfg = androidx.webkit.ProxyConfig.Builder()
                            .addProxyRule("http://127.0.0.1:" + port)
                            .addDirect()
                            .build()
                        androidx.webkit.ProxyController.getInstance()
                            .setProxyOverride(cfg, { it.run() }) {}
                        LabLog.i("webpost", "WebView 代理 -> 127.0.0.1:" + port)
                    }
                }
                // 把 OkHttp 侧的登录 cookie 灌进来（CookieManager 与 cookieJar 不自动同步）
                runCatching {
                    val raw = CfCookieBridge.allCookieHeader("https://hanime1.me/")
                    if (raw.isNotBlank()) {
                        for (kv in raw.split(";")) {
                            val s = kv.trim()
                            if (s.isEmpty() || !s.contains("=")) continue
                            cm.setCookie("https://hanime1.me/", s)
                            cm.setCookie("https://.hanime1.me/", s)
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
                        runCatching { com.han1me.lab.HplayWebProxy.intercept(request) }.getOrNull()

                    override fun onPageFinished(view: WebView, url: String) {
                        // ★ 第 347 轮：**onPageFinished 只用来加速，不再作为启动条件**。
                        // 真机实测：这个页面挂着 hCaptcha / jsdelivr / gstatic / 广告等十几个
                        // 第三方资源，全部经节点转发，onPageFinished 要等**所有**子资源结束才触发
                        // —— 35 秒整体超时都等不到。改为在 loadUrl 之后无条件启动轮询。
                        if (poller != null) return
                        onPageReady(view, main, { v -> finish(v) }, jsFill)?.let { poller = it }
                    }
                }

                // 真实尺寸（挑战脚本要 viewport）+ alpha=0.01（0f 会被判不可见）
                val fl = android.widget.FrameLayout(app)
                holder = fl
                val dw = com.han1me.HplayEnv.activity()?.window?.decorView
                val fw = if ((dw?.width ?: 0) > 0) dw!!.width else 720
                val fh = if ((dw?.height ?: 0) > 0) dw!!.height else 1280
                fl.addView(w, android.widget.FrameLayout.LayoutParams(fw, fh))
                fl.alpha = 0.01f
                val dv = dw as? android.view.ViewGroup
                if (dv == null) {
                    finish(false)
                } else {
                    dv.addView(fl, android.view.ViewGroup.LayoutParams(fw, fh))
                    w.loadUrl(pageUrl)
                    // ★ 轮询在这里就启动，不等 onPageFinished（原因见上）
                    val boot = object : Runnable {
                        override fun run() {
                            if (finished || poller != null) return
                            if (web?.url == null) { main.postDelayed(this, 500); return }
                            // 复用 onPageFinished 里的同一套轮询构造
                            onPageReady(w, main, { v -> finish(v) }, jsFill)?.let { poller = it }
                        }
                    }
                    main.postDelayed(boot, 2500)
                }
            } catch (e: Throwable) {
                LabLog.i("webpost", "启动失败：" + e.javaClass.simpleName + " " + (e.message ?: ""))
                finish(false)
            }
        }

        val waited = runCatching { latch.await(TIMEOUT_S, TimeUnit.SECONDS) }.getOrDefault(false)
        main.post {
            runCatching { poller?.let { main.removeCallbacks(it) } }
            runCatching { holder?.let { h -> (h.parent as? android.view.ViewGroup)?.removeView(h) } }
            runCatching { web?.destroy() }
        }
        val ok = waited && out[0]
        LabLog.i("webpost", "页面内提交 -> " + ok + (if (waited) "" else " timeout"))
        return ok
    }

    /**
     * 构造一轮「试填表单 + 读 fetch 结果」的轮询器。
     *
     * 抽成独立函数是因为它有两个启动点：
     *  · loadUrl 之后 2.5 秒（主路径，不等 onPageFinished）；
     *  · onPageFinished（加速路径，页面恰好加载快时提前跑）。
     * 两处都要判 `poller != null` 防重复。
     */
    private fun onPageReady(
        view: WebView,
        main: Handler,
        finish: (Boolean) -> Unit,
        jsFill: String,
    ): Runnable? {
        val p = object : Runnable {
            var tries = 0
            override fun run() {
                if (tries++ > 26) { finish(false); return }
                view.evaluateJavascript(
                    "(window.__hpSubCode===undefined?-1:window.__hpSubCode)",
                ) { code ->
                    val c = code?.replace("\u0022", "")?.trim()?.toIntOrNull() ?: -1
                    if (c > 0) {
                        LabLog.i("webpost", "页面内 fetch -> HTTP " + c)
                        finish(c in 200..399)
                        return@evaluateJavascript
                    }
                    view.evaluateJavascript(jsFill) { v ->
                        val flag = v?.replace("\u0022", "").orEmpty()
                        if (!flag.contains("ok") && !flag.contains("dup")) {
                            LabLog.i("webpost", "填表 " + flag.take(90))
                        }
                    }
                }
                main.postDelayed(this, 1200)
            }
        }
        main.postDelayed(p, 300)
        return p
    }

    /** JS 单引号字符串转义。 */
    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")
}
