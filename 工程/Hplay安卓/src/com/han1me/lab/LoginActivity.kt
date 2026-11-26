package com.han1me.lab

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **WebView 登录页**（2026-11-25 第 285 轮；第 322 轮接多账户）。
 *
 * ## 为什么是 WebView 而不是表单 POST
 *
 * 两个参考项目**都用 WebView 登录**，理由一致：
 *  · `Han1mePlus/lib/src/features/account/login_page.dart` —— InAppWebView + 700ms 轮询 + DOM 标记
 *  · `Han1meViewer/ui/activity/LoginActivity.kt` —— WebView + 下拉刷新 + 扫码兜底
 *
 * 根本原因：hanime1.me 在 Cloudflare 后面，且登录页带 **hCaptcha**。
 * 表单 POST 需要先拿 CSRF token、再带头发 POST，任何一环在 CF 环境下都可能失败，
 * 而且失败原因不透明。WebView 里这些全部由浏览器自己处理。
 *
 * ## 登录成功判据（照搬 Plus / Viewer）
 *
 * 每 700ms 执行一段 JS 检查 DOM：
 * ```js
 * var t = document.querySelector("#user-modal-trigger");
 * if (t && /\/user\/\d+/.test(t.getAttribute("href") || "")) return true;
 * return document.querySelector(".profile-sub-stats-id") !== null;
 * ```
 *
 * ## 第 322 轮：登录成功后自动存进多账户表
 *
 * 用户要求「多账户体系，登录后显示已保存的账户」。
 * 所以登录成功的瞬间要顺便把 **邮箱 / uid / 昵称** 从页面里读出来，
 * 连同 cookie 一起交给 [AccountStore] —— 这样「账户」页立刻就有条目，
 * 切回这个账户时只要换 cookie 即可。
 */
class LoginActivity : Activity() {

    companion object {
        const val EXTRA_URL = "login_url"

        /** 登录结束回调：参数为 cookie 串（失败为空）。 */
        @Volatile
        var onFinished: ((String) -> Unit)? = null
    }

    private var web: WebView? = null
    private val done = AtomicBoolean(false)
    private var pollThread: Thread? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startUrl = intent.getStringExtra(EXTRA_URL) ?: "https://hanime1.me/login"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF17171C.toInt())
        }

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, 5))

        val title = TextView(this).apply {
            text = "登录 Hanime1.me · 登录成功后自动返回"
            setTextColor(0xFFE6E6EA.toInt())
            textSize = 13f
            setPadding(28, 20, 28, 12)
        }
        root.addView(title)

        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        val tip = TextView(this).apply {
            text = "在这一页正常登录即可（需要过验证码）。\n" +
                "登录状态会写入本机并记住账户，之后打开 App 自动保持。"
            setTextColor(0xFF9A9AA2.toInt())
            textSize = 11f
            setPadding(28, 12, 28, 8)
        }
        root.addView(tip)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 6, 24, 22)
        }
        row.addView(Button(this).apply {
            text = "我已完成登录"
            setOnClickListener { captureAndFinish(true) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { captureAndFinish(false) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        setContentView(root)

        val cm = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val wv = WebView(this)
        web = wv
        cm.setAcceptThirdPartyCookies(wv, true)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            // 与 CfVerifyActivity 使用同一个 UA（clearance 与 UA 强绑定）
            userAgentString = CfUa.USER_AGENT
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean = false
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) {
                bar.progress = p
            }
        }
        frame.addView(wv, FrameLayout.LayoutParams(-1, -1))

        // Android WebView 不读系统 http_proxy，必须用 ProxyController 单独指过去
        CfForward.ensureStarted()
        runCatching {
            val cfg = androidx.webkit.ProxyConfig.Builder()
                .addProxyRule("http://127.0.0.1:" + CfForward.port)
                .addDirect()
                .build()
            androidx.webkit.ProxyController.getInstance().setProxyOverride(
                cfg,
                { it.run() },
            ) {
                LabLog.i("login", "WebView 已指向本地代理 " + CfForward.port)
            }
        }.onFailure {
            LabLog.i("login", "ProxyController 失败：" + it.javaClass.simpleName)
        }
        wv.loadUrl(startUrl)

        // 轮询检查登录是否完成（照搬 Plus 的 700ms 节奏）
        pollThread = Thread({
            val marker = "(function(){" +
                "var t=document.querySelector('#user-modal-trigger');" +
                "if(t&&/\\/user\\/\\d+/.test(t.getAttribute('href')||''))return true;" +
                "return document.querySelector('.profile-sub-stats-id')!==null;" +
                "})()"
            while (!done.get()) {
                Thread.sleep(700)
                if (done.get()) break
                val v = web ?: break
                v.post {
                    v.evaluateJavascript(marker) { r ->
                        if (!done.get() && r != null && r.contains("true")) {
                            LabLog.i("login", "检测到登录成功标记")
                            captureAndFinish(true)
                        }
                    }
                }
            }
        }, "hplay-login-poll").apply { isDaemon = true }
        pollThread?.start()
    }

    /**
     * 取 cookie 并结束。
     *
     * @param ok true = 用户说已登录（或检测到标记）；false = 取消
     */
    private fun captureAndFinish(ok: Boolean) {
        if (!done.compareAndSet(false, true)) return
        if (!ok) {
            val cb0 = onFinished
            onFinished = null
            runCatching { cb0?.invoke("") }
            runCatching { finish() }
            return
        }
        val cookies = runCatching {
            CookieManager.getInstance().getCookie("https://hanime1.me/")
        }.getOrNull().orEmpty()
        runCatching { CookieManager.getInstance().flush() }
        LabLog.i("login", "取到 cookie " + cookies.length + " 字节")

        if (cookies.isNotBlank()) {
            // 三件事：落盘 + 通知 UI + 立刻开始抓账户
            CfCookieBridge.importIntoClient("https://hanime1.me/", cookies)
            CfCookieBridge.persistLogin(cookies)
            LoginState.beginLoadingAccount()
            LoginState.notifyChanged()

            // ★ 多账户：把这一份登录态存进账户表（邮箱/uid/昵称从页面里读）
            readIdentity { email, uid, name ->
                runCatching {
                    val ctx = applicationContext
                    val old = AccountStore.all(ctx).firstOrNull { it.email.equals(email, true) }
                    AccountStore.save(
                        ctx,
                        AccountStore.Account(
                            email = email.ifBlank { "account@" + System.currentTimeMillis() },
                            uid = uid.ifBlank { old?.uid.orEmpty() },
                            name = name.ifBlank { old?.name.orEmpty() },
                            avatar = old?.avatar.orEmpty(),
                            cookie = cookies,
                        ),
                    )
                    // 邮箱写进 X99 设置，用户页要显示
                    if (email.isNotBlank()) {
                        com.han1me.X99.putSetting("account.email", email)
                    }
                    if (uid.isNotBlank()) com.han1me.X99.putSetting("account.uid", uid)
                    if (name.isNotBlank()) com.han1me.X99.putSetting("account.name", name)
                }
            }

            // 后台线程抓账户（2 秒硬超时，失败也会关掉加载态）
            Thread({
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        com.han1me.lab.InstantAccountLoader.loadNow(
                            com.javrocket.hanime.HanimeClient.http,
                        )
                    }
                }
            }, "hplay-instant-account").apply { isDaemon = true }.start()
        }

        val cb = onFinished
        onFinished = null
        runCatching { cb?.invoke(cookies) }
        runCatching { finish() }
    }

    /**
     * 从页面里读身份信息（邮箱 / uid / 昵称）。
     *
     * 三个来源，按可靠性排列：
     *  · `input#email`（/user/<id>/edit 页有，登录后跳过去的那个页面）；
     *  · `.profile-sub-stats-id` 文本里的数字（uid）；
     *  · 顶栏 `#user-modal-trigger` 的 href 里的 uid。
     *
     * 读不到就返回空串 —— 调用方会用兜底邮箱，不影响功能。
     */
    private fun readIdentity(cb: (email: String, uid: String, name: String) -> Unit) {
        val v = web
        if (v == null) {
            cb("", "", "")
            return
        }
        val js = "(function(){" +
            "var em=document.querySelector('input#email,input[name=email]');" +
            "var mail=em?(em.value||''):'';" +
            "var nm=document.querySelector('input[name=name]');" +
            "var name=nm?(nm.value||''):'';" +
            "var id=document.querySelector('.profile-sub-stats-id');" +
            "var uid=id?((id.textContent||'').match(/\\d+/)||[''])[0]:'';" +
            "if(!uid){var t=document.querySelector('#user-modal-trigger');" +
            "if(t){var h=t.getAttribute('href')||'';var m=h.match(/user\\/(\\d+)/);if(m)uid=m[1];}}" +
            "return JSON.stringify({e:mail,u:uid,n:name});" +
            "})()"
        val main = android.os.Handler(mainLooper)
        v.post {
            v.evaluateJavascript(js) { r ->
                var email = ""
                var uid = ""
                var name = ""
                runCatching {
                    // evaluateJavascript 回的是 JSON 字符串字面量："{\"e\":\"..\"}"
                    val raw = (r ?: "").trim().removeSurrounding("\"")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                    if (raw.startsWith("{")) {
                        val o = org.json.JSONObject(raw)
                        email = o.optString("e")
                        uid = o.optString("u")
                        name = o.optString("n")
                    }
                }
                LabLog.i("login", "身份解析 email=" + email + " uid=" + uid + " name=" + name)
                main.post { cb(email, uid, name) }
            }
        }
    }

    override fun onDestroy() {
        done.set(true)
        runCatching { web?.destroy() }
        web = null
        super.onDestroy()
    }
}
