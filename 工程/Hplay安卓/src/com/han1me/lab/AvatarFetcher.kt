package com.han1me.lab

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **用 WebView 抓云端账户与头像**（2026-11-25 第 322 轮新增）。
 *
 * ## 为什么不能继续用 OkHttp
 *
 * 真机日志（第 322 轮，补了 jar 级 cookie 注入之后）：
 * ```
 * [cf] 静默过盾成功，cookie 3532 字节        <- WebView 里真的拿到了 cf_clearance
 * [cf] 静默过盾拿到 cookie 但重放仍被挑战     <- OkHttp 带着同一串 cookie 仍旧 403
 * ```
 *
 * 根因是 **cf_clearance 绑定的不只是 UA，还有 TLS 指纹（JA3）**。
 * WebView 走的是 Chromium 的 BoringSSL，OkHttp 走的是 Java/Conscrypt ——
 * 两者的 ClientHello 完全不一样，CF 的一致性校验直接判「环境不一致」。
 * 这不是 cookie 传递的问题（已经在 jar 层确认带上去了），是协议层不可绕过的。
 *
 * ## 参考项目为什么没这个问题
 *
 * Han1meViewer / Han1mePlus 的账号页**也走 WebView**（`LoginScreen` / `login_page.dart`），
 * 它们并没有依赖 OkHttp 去读 `/user/<id>/edit`。
 * 我们之前照 Parser 抄了取法，却用 OkHttp 去发 —— 这一步才是错的。
 *
 * ## 现在的做法
 *
 * 起一个 1×1 透明 WebView（带 ProxyController 指向节点，与验证页同一条链路），
 * 加载 `/user/<uid>/edit`，等页面里出现 `img#playlist-avatar` 后用 JS 读出 src。
 * 抓一次就落盘（[CloudAccount] 的缓存），之后冷启动直接用缓存，不再抓。
 *
 * 为什么这条路不会弹 CF：它用的就是**已经过一次就不再挑战**的那个 WebView 会话。
 */
object AvatarFetcher {

    /** 整体上限。页面本身很小，超时只可能卡在网络。 */
    private const val TIMEOUT_MS = 15_000L

    @SuppressLint("SetJavaScriptEnabled")
    fun fetch(userId: String): CloudAccount.Info? {
        if (userId.isBlank()) return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<CloudAccount.Info?>(null)
        val main = Handler(Looper.getMainLooper())
        val url = "https://hanime1.me/user/" + userId + "/edit"

        main.post {
            val act = CurrentActivity.find()
            if (act == null) {
                LabLog.i("account", "WebView 抓取：找不到前台 Activity")
                latch.countDown()
                return@post
            }
            val dialog = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.setCancelable(false)
            var finished = false
            var wv: WebView? = null

            fun done() {
                if (finished) return
                finished = true
                runCatching { wv?.stopLoading() }
                runCatching { wv?.destroy() }
                runCatching { dialog.dismiss() }
                latch.countDown()
            }

            val web = WebView(act)
            wv = web
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = CfUa.USER_AGENT
            }
            val cm = CookieManager.getInstance().apply { setAcceptCookie(true) }
            cm.setAcceptThirdPartyCookies(web, true)
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView?,
                    r: android.webkit.WebResourceRequest?,
                ): Boolean = false
            }
            web.webChromeClient = object : WebChromeClient() {}

            val wrap = FrameLayout(act)
            wrap.addView(web, 1, 1)
            dialog.setContentView(wrap, ViewGroup.LayoutParams(1, 1))
            runCatching { dialog.show() }

            val port = runCatching { CfForward.ensureStarted() }.getOrDefault(0)
            if (port > 0) {
                runCatching {
                    val cfg = androidx.webkit.ProxyConfig.Builder()
                        .addProxyRule("http://127.0.0.1:" + port)
                        .addDirect()
                        .build()
                    androidx.webkit.ProxyController.getInstance().setProxyOverride(
                        cfg, { it.run() },
                    ) {}
                }
            }

            // 页面里三个字段一次读完（头像 / 昵称 / 邮箱）
            val js = "(function(){" +
                "var a=document.querySelector('img#playlist-avatar');" +
                "var src=a?(a.getAttribute('src')||a.src||''):'';" +
                "var nm=document.querySelector('input[name=name]');" +
                "var name=nm?(nm.value||''):'';" +
                "var em=document.querySelector('input#email,input[name=email]');" +
                "var mail=em?(em.value||''):'';" +
                "if(!src&&!name)return '';" +
                "return src+String.fromCharCode(1)+name+String.fromCharCode(1)+mail;" +
                "})()"

            val startAt = System.currentTimeMillis()
            val tick = object : Runnable {
                override fun run() {
                    if (finished) return
                    if (System.currentTimeMillis() - startAt > TIMEOUT_MS) {
                        LabLog.i("account", "WebView 抓取超时")
                        done()
                        return
                    }
                    web.evaluateJavascript(js) { raw ->
                        val s = (raw ?: "").trim().removeSurrounding("\"")
                            .replace("\\u0001", "\u0001")
                        if (s.isNotEmpty() && s.contains("\u0001")) {
                            val parts = s.split("\u0001")
                            val src = parts.getOrElse(0) { "" }
                            val name = parts.getOrElse(1) { "" }
                            val mail = parts.getOrElse(2) { "" }
                            if (src.isNotBlank() || name.isNotBlank()) {
                                val info = CloudAccount.Info(
                                    userId = userId,
                                    userName = unescapeJs(name),
                                    avatarUrl = unescapeJs(src),
                                    email = unescapeJs(mail),
                                )
                                result.set(info)
                                LabLog.i(
                                    "account",
                                    "WebView 抓取成功 name=" + info.userName +
                                        " avatar=" + (if (info.avatarUrl.isBlank()) "无" else "有"),
                                )
                                done()
                                return@evaluateJavascript
                            }
                        }
                        main.postDelayed(this, 500)
                    }
                }
            }
            web.loadUrl(url)
            main.postDelayed(tick, 1200)
        }

        val ok = runCatching { latch.await(TIMEOUT_MS + 6_000L, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!ok) LabLog.i("account", "WebView 抓取未在时限内结束")
        return result.get()
    }

    /** JS 返回的字符串里 \\uXXXX 要还原（头像 URL 里有 &amp; 之类）。 */
    private fun unescapeJs(s: String): String {
        if (s.isBlank()) return ""
        var out = s.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        if (out.startsWith("//")) out = "https:" + out
        if (out.startsWith("/")) out = "https://hanime1.me" + out
        return out.trim()
    }
}
