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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **静默过盾**（2026-11-25 第 322 轮新增）。
 *
 * ## 用户要求

 * 「让 cf 过一次全部都不用重新验证。」
 *
 * 也就是说：**允许过一次**（真人点一下挑战），但之后所有地方
 * （首页 / 搜索 / 详情 / 登录 / 云端头像 / 收藏）都不许再弹。
 *
 * ## 弹窗是怎么来的
 *
 * 旧 `CloudflareInterceptor`：403 且已登录 → 重放 → 仍 403 → **弹 [CfVerifyActivity]**。
 * 问题在于「重放仍 403」是常态（clearance 过期 / UA 绑定失效 / 服务端需要重新挑战），
 * 于是每次都会弹，而 WebView 里因为已经带着登录 cookie，**一秒就自动通过并关闭** ——
 * 用户看到的就是「闪一下又没了」，而且每次点 han 相关都闪。
 *
 * ## 现在的做法
 *
 * 分三条路：
 *
 * | 场景 | 走哪条 |
 * |---|---|
 * | 本地已有 clearance 且重放成功 | 直接返回，**什么都不做** |
 * | 本地 clearance 缺失/过期 | **静默 WebView 跑一次挑战**（1×1 透明 Dialog，用户看不见） |
 * | 静默跑了 [MAX_SILENT_MS] 还没过 | 才弹可见验证页（真的需要人点，比如 Turnstile 要求交互） |
 *
 * ## 为什么静默 WebView 能过
 *
 * CF 的托管挑战（managed challenge）大多数情况下是**纯 JS 自动求解**，
 * 只要浏览器环境是真的（TLS 指纹、navigator、canvas 都是 WebView 自己的），
 * 不需要人点。HeadlessLogin 当初失败是因为它在**登录页**上要 hCaptcha，
 * 那才是必须人点的；clearance 挑战不是。
 *
 * 所以：静默跑 = 起一个真的 WebView（带 ProxyController 指向节点），
 * 加载一次首页，等 `cf_clearance` 出现在 CookieManager 里。
 */
object SilentCfVerifier {

    /** 静默尝试的上限。超过就交给可见验证页。 */
    private const val MAX_SILENT_MS = 12_000L

    /** 轮询节奏。 */
    private const val POLL_MS = 400L

    @Volatile
    private var running = false

    /**
     * 跑一次静默挑战（阻塞直到拿到 clearance 或超时）。
     *
     * @return true = 拿到了 cf_clearance
     */
    fun run(url: String): Boolean {
        if (running) {
            // 已经有一个在跑：等它结束，避免并发起多个 WebView
            val deadline = System.currentTimeMillis() + MAX_SILENT_MS + 2_000L
            while (running && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(200) }
            }
            return CfCookieBridge.hasPersisted()
        }
        running = true
        val latch = java.util.concurrent.CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())

        main.post {
            val act = CurrentActivity.find()
            if (act == null) {
                LabLog.i("cf", "静默过盾：找不到前台 Activity，跳过")
                latch.countDown()
                return@post
            }
            val dialog = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.setCancelable(false)
            val finished = AtomicBoolean(false)
            var wv: WebView? = null

            fun done(passed: Boolean) {
                if (!finished.compareAndSet(false, true)) return
                ok.set(passed)
                runCatching { wv?.stopLoading() }
                runCatching { wv?.destroy() }
                runCatching { dialog.dismiss() }
                latch.countDown()
            }

            @SuppressLint("SetJavaScriptEnabled")
            val web = WebView(act)
            wv = web
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                javaScriptCanOpenWindowsAutomatically = true
                // 与 API 请求同一个 UA —— clearance 与 UA 强绑定
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

            // WebView 不读系统代理，必须 ProxyController
            val port = runCatching { CfForward.ensureStarted() }.getOrDefault(0)
            if (port > 0) {
                runCatching {
                    val cfg = androidx.webkit.ProxyConfig.Builder()
                        .addProxyRule("http://127.0.0.1:" + port)
                        .addDirect()
                        .build()
                    androidx.webkit.ProxyController.getInstance().setProxyOverride(
                        cfg,
                        { it.run() },
                    ) {}
                }
            }

            val startAt = System.currentTimeMillis()
            val tick = object : Runnable {
                override fun run() {
                    if (finished.get()) return
                    if (System.currentTimeMillis() - startAt > MAX_SILENT_MS) {
                        LabLog.i("cf", "静默过盾超时（" + MAX_SILENT_MS + "ms）")
                        done(false)
                        return
                    }
                    val ck = runCatching {
                        CookieManager.getInstance().getCookie(url)
                    }.getOrNull().orEmpty()
                    if (ck.contains("cf_clearance")) {
                        runCatching { CookieManager.getInstance().flush() }
                        CfCookieBridge.persist(ck)
                        LabLog.i("cf", "静默过盾成功，cookie " + ck.length + " 字节")
                        done(true)
                        return
                    }
                    main.postDelayed(this, POLL_MS)
                }
            }
            web.loadUrl(url)
            main.postDelayed(tick, 900)
        }

        val got = runCatching {
            latch.await(MAX_SILENT_MS + 5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        running = false
        if (!got) LabLog.i("cf", "静默过盾未在时限内结束")
        return ok.get() || CfCookieBridge.hasPersisted()
    }
}
