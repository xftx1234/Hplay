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
 * **Cloudflare 可见验证页**（2026-11-24 第 277 轮新增；第 322 轮改成兜底通道）。
 *
 * 只有当 [SilentCfVerifier] 静默挑战失败时才走到这里 ——
 * 也就是真的需要人点一下的挑战（Turnstile 交互式那种）。
 *
 * ## 自动检测的三条判据（照搬 Han1meViewer，逐条都有理由）
 *
 * 1. **进度 >= 90%** —— 挑战页本身是慢慢加载的，太早检测会把"还在挑战中"误判成完成；
 * 2. **DOM 里没有挑战标记**：`#challenge-form` / `#challenge-success-text` / `#challenge-error-text`
 *    —— 这三个 id 是 CF 挑战页的固定结构，任何一个还在就说明没过；
 * 3. **CookieManager 里确实出现了 `cf_clearance`** —— 这是唯一权威证据。
 *    只满足前两条而没有 cookie，重放请求照样 403。
 *
 * ## 为什么用原生 View 而不是 Compose
 *
 * 这个页面生命周期极短、只有一个控件，用 Activity + 原生 View 最省事；
 * 也避免为了一个弹层去引入 Compose 的 Activity 依赖（主线目前是单 Activity 架构）。
 */
class CfVerifyActivity : Activity() {

    companion object {
        const val EXTRA_URL = "cf_url"

        /** 验证结束回调（由拦截器设置，通过后 invoke）。 */
        @Volatile
        var onFinished: (() -> Unit)? = null
    }

    private var web: WebView? = null
    private val done = AtomicBoolean(false)

    /**
     * 把 WebView 的流量指到本地转发代理。
     *
     * ## 为什么必须用 ProxyController
     *
     * 实测结论：设了系统 `http_proxy` 后 curl 走代理正常，
     * 但 WebView **完全没连 18080**（转发器一条 cf.fwd 日志都没有）。
     * 也就是说：**Android WebView 不读系统 http_proxy**。
     * 唯一能给 WebView 单独设代理的官方接口就是 `androidx.webkit.ProxyController`。
     */
    private fun applyWebViewProxy(port: Int) {
        runCatching {
            val cfg = androidx.webkit.ProxyConfig.Builder()
                .addProxyRule("http://127.0.0.1:" + port)
                .addDirect()   // 代理失败时允许直连，避免整页全断
                .build()
            androidx.webkit.ProxyController.getInstance().setProxyOverride(
                cfg,
                { it.run() },
            ) {
                LabLog.i("cf", "ProxyController 已生效 -> 127.0.0.1:" + port)
            }
        }.onFailure {
            LabLog.i("cf", "ProxyController 失败：" + it.javaClass.simpleName)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finishAndNotify(false); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF17171C.toInt())
        }

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, 6))

        val title = TextView(this).apply {
            text = "Cloudflare 验证 · 完成后自动返回"
            setTextColor(0xFFE6E6EA.toInt())
            textSize = 13f
            setPadding(28, 22, 28, 14)
        }
        root.addView(title)

        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        val tip = TextView(this).apply {
            text = "自动验证没通过，需要你点一下。\n" +
                "如果一直停在这里：点下面「手动完成」或返回重试。"
            setTextColor(0xFF9A9AA2.toInt())
            textSize = 11f
            setPadding(28, 14, 28, 8)
        }
        root.addView(tip)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 8, 24, 24)
        }
        row.addView(Button(this).apply {
            text = "手动完成"
            setOnClickListener { finishAndNotify(true) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { finishAndNotify(false) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        setContentView(root)

        val proxyPort = CfForward.ensureStarted()
        if (proxyPort > 0) applyWebViewProxy(proxyPort)

        val cm = CookieManager.getInstance().apply { setAcceptCookie(true) }

        val wv = WebView(this)
        web = wv
        cm.setAcceptThirdPartyCookies(wv, true)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            // 验证页与 API 请求共用 CfUa.USER_AGENT，CF 看到的是同一个客户端
            userAgentString = CfUa.USER_AGENT
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean = false
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) {
                bar.progress = p
                if (p >= 90) checkPassed(wv, url)
            }
        }
        frame.addView(wv, FrameLayout.LayoutParams(-1, -1))
        wv.loadUrl(url)
    }

    /** 三条判据全满足才算过（见类注释）。 */
    private fun checkPassed(wv: WebView, url: String) {
        if (done.get()) return
        wv.postDelayed({
            if (done.get()) return@postDelayed
            wv.evaluateJavascript("document.head ? document.head.innerHTML : \"\"") { html ->
                val h = html ?: ""
                val stillChallenging = h.contains("#challenge-form") ||
                    h.contains("#challenge-success-text") ||
                    h.contains("#challenge-error-text")
                if (stillChallenging) {
                    wv.postDelayed({ checkPassed(wv, url) }, 1200)
                    return@evaluateJavascript
                }
                val cookies = runCatching {
                    CookieManager.getInstance().getCookie(url)
                }.getOrNull().orEmpty()
                if (cookies.contains("cf_clearance")) {
                    runCatching { CookieManager.getInstance().flush() }
                    LabLog.i("cf", "可见验证拿到 cf_clearance，长度 " + cookies.length)
                    finishAndNotify(true)
                } else {
                    wv.postDelayed({ checkPassed(wv, url) }, 1200)
                }
            }
        }, 1000)
    }

    private fun finishAndNotify(ok: Boolean) {
        if (!done.compareAndSet(false, true)) return
        if (ok) {
            val u = intent.getStringExtra(EXTRA_URL).orEmpty()
            HplayCfHolder.markCleared(u)
            // 读整串而不是只取 cf_clearance：CF 还会下发 __cf_bm、cf_chl_* 等配套 cookie
            val cookies = runCatching { CookieManager.getInstance().getCookie(u) }.getOrNull().orEmpty()
            if (cookies.isNotBlank()) {
                CfCookieBridge.persist(cookies)
                LabLog.i("cf", "已落盘 WebView cookie（" + cookies.length + " 字节）")
            }
        }
        val cb = onFinished
        onFinished = null
        runCatching { cb?.invoke() }
        runCatching { finish() }
    }

    override fun onDestroy() {
        done.set(true)
        runCatching { web?.destroy() }
        web = null
        super.onDestroy()
    }
}
