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
 * **关注 / 取关作者**（第 345 轮）。
 *
 * ## 403 的真根因（真机日志实证）
 *
 * ```
 * [11:27:22.448] GET  hanime1.me/          -> 200  Server=cloudflare
 * [11:27:22.937] POST hanime1.me/subscribe -> 403  Cf-Mitigated=challenge
 * ```
 *
 * 同一条客户端、同一份 Cookie、相隔 489 毫秒：GET 200、POST 被挑战。
 * 不是 Laravel 的 CSRF（那会是 419，或 403 但不带 cf-mitigated），
 * 而是 Cloudflare 对 **POST /subscribe** 单独下发的 managed challenge。
 *
 * 托管挑战按 TLS 指纹（JA3）判定；OkHttp 走 Conscrypt，指纹与 Chromium 不同，
 * Cookie 带得再全也过不了。本项目 CloudflareInterceptor 里那段
 * 「cf_clearance 绑定 TLS 指纹」的注释，说的就是这件事。
 *
 * ## 两个参考源码核对
 *
 * · Han1meViewer `HanimeSubscriptionService`：Retrofit `@FormUrlEncoded @POST("subscribe")`，
 *   `@Field("_token")` + 三件套 + `@Header("X-CSRF-TOKEN")`；**没带 Origin**。
 * · Han1mePlus `han1me_api._submitForm`：显式带 X-CSRF-TOKEN / Referer / **Origin** /
 *   Accept / X-Requested-With，并在 419 时刷新令牌重试一次。
 *
 * 两者都证明「头要全」（`HanimeClient.subscribeArtist` 已按 Plus 补齐），
 * 但都解决不了「WAF 按路径挑战 POST」—— 那是服务端策略。
 *
 * ## 所以两级
 *
 * ```
 * ① OkHttp 直发 —— 快、无 UI。只有 403/419 才往下走。
 * ② 不可见 WebView 打开**详情页本身**，用它自己的 #video-subscribe-form
 *    把三个 input 改成目标值，然后 form.submit()。
 * ```
 *
 * ② 之所以不是「fetch 一个 /subscribe」而是「让页面自己提交表单」：
 * 第 345 轮第一次尝试用的是 fetch，真机 25 秒超时（WebView 里 fetch 走的是
 * 页面的 XHR，仍会被同一套 WAF 规则拦；而且页面本身要过盾，识别不到结果）。
 * 而真实用户点击页面上的「关注」按钮，走的就是这条原生表单提交路径 ——
 * 浏览器自己发起的顶层导航，CF 不会拦。这也是两个参考源码在 WebView 里能生效的
 * 同一个原因：它们都依赖「页面已经能正常用」。
 *
 * ## 纪律
 *
 * · WebView 在主线程创建（Android 硬要求）；调用线程用 latch 等，有硬超时；
 * · 1×1 + alpha=0，挂到 decorView 上让它参与布局（不布局的 WebView 不跑 JS），用完移除；
 * · 任何异常都返回 false，由调用方如实提示，不假装成功。
 */
object HplaySubscribe {

    /** 整体超时（秒）。页面加载 + CF 过盾 + 表单提交 + 结果回读，四段合计。 */
    private const val TIMEOUT_S = 30L

    /**
     * 关注 / 取关。
     *
     * @param videoUrl 详情页地址（WebView 要打开它才能拿到站方自己的表单）
     * @param userId   `#video-subscribe-form` 的 subscribe-user-id
     * @param artistId 同上，subscribe-artist-id
     * @param subscribe true = 关注，false = 取关
     * @return 站点侧确认后的状态；无法确认时抛异常（不假装成功）
     */
    fun toggle(
        videoUrl: String,
        userId: String,
        artistId: String,
        subscribe: Boolean,
    ): Boolean {
        // ① OkHttp 直发
        val r = runCatching {
            com.javrocket.hanime.HanimeClient.subscribeArtist(userId, artistId, subscribe)
        }
        r.getOrNull()?.let { return it }
        val t = r.exceptionOrNull()
        val msg = t?.message.orEmpty()
        // 只有 WAF 挑战（403 / 419）才值得起 WebView —— 未登录之类起也没用
        val waf = msg.contains("403") || msg.contains("419")
        if (!waf) throw (t ?: IllegalStateException("关注失败"))

        // ② 不可见 WebView：让详情页自己提交表单
        val ctx = com.han1me.HplayEnv.applicationOrNull()
            ?: throw IllegalStateException(msg + "（无 Context，无法回落）")
        if (videoUrl.isBlank()) throw IllegalStateException(msg + "（缺详情页地址，无法回落）")
        // subscribe-status：已关注发空串、未关注发 "1"（照两个参考源码的目标状态语义）
        val status = if (subscribe) "" else "1"
        val ok = submitInPage(ctx, videoUrl, userId, artistId, status)
        if (!ok) throw IllegalStateException(msg + "（Cloudflare 挑战，页面内提交未确认）")

        // ③ 清缓存：让详情页与「我的订阅」下次重抓，反映新的关注状态。
        //    这里**不假装能回读确认** —— 站方这个接口没有可用回包字段（第 101 轮已核过），
        //    而表单提交的落点是一整页 HTML。所以返回值按提交意图给，
        //    真伪由用户重进详情页时站点自己渲染的状态决定。
        runCatching { com.javrocket.hanime.HanimeClient.invalidateAllHtml() }
        runCatching { com.javrocket.hanime.HanimeClient.invalidateDetailCache() }
        return subscribe
    }

    /**
     * 在不可见 WebView 里打开 [pageUrl]，改表单值后原生提交。
     *
     * @return true = 提交动作确实发出去了（页面发生了导航）；false = 超时或找不到表单
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
        var submitted = false
        var poller: Runnable? = null
        fun finish(v: Boolean) {
            if (finished) return
            finished = true
            out[0] = v
            latch.countDown()
        }

        // 表单填充 + 原生提交的 JS。三处 try 是为了在某页结构变动时不抛异常，只回标记。
        val jsFill = buildString {
            append("(function(){")
            append("try{")
            append("var f=document.getElementById('video-subscribe-form');")
            append("if(!f){return 'nof|'+location.href+'|'+document.title+'|'+(document.body?document.body.innerHTML.length:0);}")
            append("var u=f.querySelector('input[name=\"subscribe-user-id\"]');")
            append("var a=f.querySelector('input[name=\"subscribe-artist-id\"]');")
            append("var s=f.querySelector('input[name=\"subscribe-status\"]');")
            append("if(u)u.value='" + escapeJs(userId) + "';")
            append("if(a)a.value='" + escapeJs(artistId) + "';")
            append("if(s)s.value='" + escapeJs(status) + "';")
            append("if(window.__hpSubmitting)return 'dup';")
            append("window.__hpSubmitting=1;")
            append("if(f.requestSubmit){f.requestSubmit();}else{f.submit();}")
            append("return 'ok';")
            append("}catch(e){return 'err:'+e;}")
            append("})()")
        }

        main.post {
            try {
                // ★★★ 第 345 轮：**WebView 必须照 HplayGateActivity 的配置来**。
                //
                // 真机诊断连续三次都是 `nof|...|Just a moment...|8239` —— WebView 自己
                // 卡在 CF 挑战页出不来。逐项对照本项目唯一能过盾的 WebView
                // （HplayGateActivity，就是「验证」按钮打开的那个），差在四处：
                //
                //  ① **UA**：验证页用 CfUa.USER_AGENT；我原来用系统默认 UA 去掉 "; wv"。
                //     cf_clearance 与 UA 强绑定（CloudflareInterceptor 的注释里写了），
                //     UA 不同 = clearance 无效 = 永远 403/挑战。
                //  ② **第三方 cookie**：验证页 setAcceptThirdPartyCookies(wv, true)。
                //  ③ **代理**：WebView 不读系统 http_proxy，也不能靠 shouldInterceptRequest
                //     （那只能拦到一部分请求，挑战页的分片资源照样直连）。
                //     验证页用 androidx.webkit.ProxyController + CfForward 本地转发端口。
                //  ④ mixedContentMode 用 ALWAYS_ALLOW（挑战页是 https 里嵌 http 资源）。
                //
                // 这四条缺任何一条，挑战页都过不去。现在全部照抄。
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
                    val raw = com.han1me.lab.CfCookieBridge.allCookieHeader("https://hanime1.me/")
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
                w.loadUrl(pageUrl)
            } catch (e: Throwable) {
                LabLog.i("webpost", "启动失败：" + e.javaClass.simpleName + " " + (e.message ?: ""))
                finish(false)
            }
        }

        val waited = runCatching { latch.await(TIMEOUT_S, TimeUnit.SECONDS) }.getOrDefault(false)
        main.post {
            runCatching { poller?.let { p -> main.removeCallbacks(p) } }
            runCatching { holder?.let { h -> (h.parent as? android.view.ViewGroup)?.removeView(h) } }
            runCatching { web?.destroy() }
        }
        val ok = waited && out[0]
        LabLog.i("webpost", "页面内提交 -> " + ok + (if (waited) "" else " timeout"))
        return ok
    }

    /** JS 单引号字符串转义（只用得到这几种）。 */
    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")
}
