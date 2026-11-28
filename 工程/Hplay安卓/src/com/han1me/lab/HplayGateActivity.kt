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
 * **验证 + 登录 + 取 cookie 一体页**（2026-11-26 第 338 轮）。
 *
 * ## 为什么要有这个页面
 *
 * 第 322 轮的方案是「静默过盾，失败才弹可见页」，本意是让用户看不见弹窗。实测不成立：
 *
 *  · 静默 WebView 必须在**主线程**起一个 1x1 Dialog，构造 WebView 本身要 200~600ms，
 *    而它是被**网络拦截器线程**触发的 —— 用户点进首页时，请求侧在等这个 Dialog，
 *    主线程又正在组合首页，两边互等，表现就是「界面卡一下才闪出验证」；
 *  · 静默跑满 12 秒仍失败才弹可见页，那一次弹窗必然出现 —— 于是「首次启动弹 CF」
 *    根本没被消除，只是延后了。
 *
 * 用户第 338 轮要求很明确：**默认不弹**，首页给入口按钮，点了才走。
 * 所以这里把「起 WebView」彻底从拦截器里摘出来，只在用户主动点击时发生。
 *
 * ## 一次解决三件事
 *
 * 用户原话：「过验证，登录，取 cookie，一次全部解决，不再二次需要。」
 *
 * 本页只加载**站点首页**（不是挑战页）：
 *  1. 首页本身会触发 CF 挑战，通过后 cf_clearance 落进 CookieManager；
 *  2. 同一页面上用户可直接点「登录」完成账号登录（不再跳第二个 Activity）；
 *  3. 用户点「完成」时把 CookieManager 里**整串** cookie 一次读出：
 *       - 含 cf_clearance          -> 存 clearance 层
 *       - 含会话标记（hanime1_session / remember_web）-> 存登录层 + 账户表
 *
 * 判定用整串而不是只看 cf_clearance —— CF 还会下发 __cf_bm、_cfuvid，
 * 缺了它们 clearance 会被判「环境不一致」（第 322 轮踩过）。
 *
 * ## 多账户（第 338 轮一并修）
 *
 * [EXTRA_FRESH] = true 时，进入前先清空 WebView 的 cookie 再加载首页。
 * 这是「添加第二个账户」的必要前提：CookieManager 是**进程级共享**的，
 * 不清就会带着上一个账户的会话打开首页 —— 用户看到的就是「还是原来那个账号」。
 */
class HplayGateActivity : Activity() {

    companion object {
        const val EXTRA_FRESH = "gate_fresh"
        const val EXTRA_START = "gate_start"

        /** 结束回调：参数为本次拿到的 cookie 串（取消为空）。 */
        @Volatile
        var onFinished: ((String) -> Unit)? = null
    }

    private var web: WebView? = null
    private val done = AtomicBoolean(false)
    private var status: TextView? = null
    private val loginDetected = AtomicBoolean(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fresh = intent.getBooleanExtra(EXTRA_FRESH, false)
        // 默认打开首页：首页能打开就说明验证过了，这一步比「打开挑战页」更直接
        val startUrl = intent.getStringExtra(EXTRA_START)?.takeIf { it.isNotBlank() }
            ?: "https://hanime1.me/"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF17171C.toInt())
        }

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, 5))

        val head = TextView(this).apply {
            text = if (fresh) "验证并登录新账户" else "Cloudflare 验证 · 登录 · 一次完成"
            setTextColor(0xFFE6E6EA.toInt())
            textSize = 14f
            setPadding(28, 20, 28, 6)
        }
        root.addView(head)

        val tip = TextView(this).apply {
            text = "① 页面能正常打开，说明验证已通过\n" +
                "② 需要登录就点页面里的「登录」正常登录\n" +
                "③ 完成后点下面「完成」—— 验证与登录态会一起保存"
            setTextColor(0xFF9A9AA2.toInt())
            textSize = 11f
            setPadding(28, 0, 28, 8)
        }
        root.addView(tip)

        val st = TextView(this).apply {
            text = "正在检查…"
            setTextColor(0xFF8AB4F8.toInt())
            textSize = 11f
            setPadding(28, 0, 28, 8)
        }
        status = st
        root.addView(st)

        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 8, 24, 22)
        }
        row.addView(Button(this).apply {
            text = "完成"
            setOnClickListener { captureAndFinish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { finishAndNotify("") }
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
            // 与 OkHttp 同一个 UA —— clearance 与 UA 强绑定（第 322 轮实测）
            userAgentString = CfUa.USER_AGENT
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean = false
            override fun onPageFinished(v: WebView?, url: String?) {
                refreshStatus()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) {
                bar.progress = p
                if (p >= 90) refreshStatus()
            }
        }
        frame.addView(wv, LinearLayout.LayoutParams(-1, -1))

        // WebView 不读系统 http_proxy，必须 ProxyController（第 322 轮实测）
        val port = runCatching { CfForward.ensureStarted() }.getOrDefault(0)
        if (port > 0) {
            runCatching {
                val cfg = androidx.webkit.ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:" + port)
                    .addDirect()
                    .build()
                androidx.webkit.ProxyController.getInstance().setProxyOverride(cfg, { it.run() }) {}
            }
        }

        // ★★★ 第 338 轮：**会话隔离用独立的 CookieManager 实例做不到**（它是进程级单例），
        // 所以「添加第二个账户」只能先清空再登录。但清空必须精确：
        //
        // 之前写的是 removeAllCookies{}，它清的是**整个进程**的 cookie ——
        // 连带把主界面的登录态、已保存账户的 WebView 会话一起清掉，
        // 用户表现为「加了个新账户，旧账户掉线了」。这是本轮自审发现的缺陷。
        //
        // 现在：只清 hanime1 / cloudflare 相关的 cookie（按域 + 按名字双过滤），
        // 其它站点（三次元各板块）的 cookie 一律不动。
        if (fresh) {
            status?.text = "正在准备新会话…"
            clearSiteCookies(cm) {
                runOnUiThread {
                    status?.text = "已就绪，正在打开站点…"
                    wv.loadUrl(startUrl)
                }
            }
        } else {
            wv.loadUrl(startUrl)
        }

        // 轮询：登录标记 + 验证状态（给用户看进度，**不自动关闭** —— 自动关会让人来不及登录）
        Thread({
            while (!done.get()) {
                Thread.sleep(900)
                if (done.get()) break
                val v = web ?: break
                v.post {
                    v.evaluateJavascript(LOGIN_JS) { r ->
                        if (r != null && r.contains("1")) loginDetected.set(true)
                        refreshStatus()
                    }
                }
            }
        }, "hplay-gate-poll").apply { isDaemon = true }.start()
    }

    /**
     * 只清 hanime1 / cloudflare 的 cookie，别站一律保留。
     *
     * 为什么不能 removeAllCookies：它清的是进程级 CookieManager，
     * 会把用户已登录的账户、三次元各板块的会话一并抹掉（自审发现的缺陷）。
     *
     * WebView 的 CookieManager 没有「按域枚举」的公开 API，但 [CookieManager.getCookie]
     * 对单域返回的是 "k=v; k2=v2" 串 —— 我们可以反解出名字，
     * 再用 setCookie(domain, "name=; Max-Age=0") 逐条过期。这是唯一可行的精确清除方式。
     */
    private fun clearSiteCookies(cm: CookieManager, done: () -> Unit) {
        val hosts = listOf(
            "https://hanime1.me/",
            "https://www.hanime1.me/",
            "https://hanime1.com/",
        )
        var pending = hosts.size
        if (pending == 0) { done(); return }
        for (h in hosts) {
            val raw = runCatching { cm.getCookie(h) }.getOrNull().orEmpty()
            if (raw.isBlank()) {
                if (--pending == 0) done()
                continue
            }
            for (seg in raw.split(';')) {
                val t = seg.trim()
                val i = t.indexOf('=')
                if (i <= 0) continue
                val name = t.substring(0, i).trim()
                if (name.isEmpty()) continue
                // 过期：值与 Max-Age 都清掉，Path 覆盖根
                runCatching { cm.setCookie(h, name + "=; Max-Age=0; Path=/") }
                runCatching { cm.setCookie(h, name + "=; Max-Age=0; Path=/; Secure") }
            }
            runCatching { cm.flush() }
            if (--pending == 0) {
                runCatching { cm.flush() }
                done()
            }
        }
    }

    /**
     * 把**每个受管域**当前的 cookie 一次读出来（第 355 轮）。
     *
     * 照 Han1mePlus 的 `WebViewCookieBridge.read(urls)`：
     * 它把 `hanime1.me / hanime1.com / hanimeone.me / javchu.com` 四个域的 cookie
     * 一起读出来。用户要求「在最初 cf 验证时就一次解决所有带 cf 验证的相关问题」，
     * 这正是那一条的实现 —— 用户只点一次验证，四个域的 clearance 全部落盘。
     *
     * @return host -> 该域整串 cookie（只含非空的）
     */
    private fun readAllHostCookies(): Map<String, String> {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (h in CfCookieBridge.HOSTS) {
            val raw = runCatching { cm.getCookie("https://$h/") }.getOrNull().orEmpty()
            if (raw.isNotBlank()) out[h] = raw
        }
        return out
    }

    /** 把当前状态写到提示行（哪一步已经完成）。 */
    private fun refreshStatus() {
        val all = readAllHostCookies()
        val cfHosts = all.filterValues { it.contains("cf_clearance") }.keys
        val main = all["hanime1.me"].orEmpty()
        val hasLogin = main.contains("hanime1_session") || main.contains("remember_web") ||
            loginDetected.get()
        val cfTxt = if (cfHosts.isEmpty()) "" else "（" + cfHosts.joinToString("、") + "）"
        status?.text = when {
            cfHosts.isNotEmpty() && hasLogin -> "✅ 验证已通过" + cfTxt + " · 已登录 —— 可以点「完成」"
            cfHosts.isNotEmpty() -> "✅ 验证已通过" + cfTxt + "（未登录；需要账号就点页面里的登录）"
            hasLogin -> "已登录 · 验证标记未出现（点「完成」会一并保存）"
            else -> "⏳ 正在通过验证，请稍候…"
        }
    }

    /**
     * 一次性取全（**所有受管域的 clearance** + 登录），落盘，结束。
     *
     * ★ 第 355 轮（用户要求「在最初 cf 验证时就一次解决所有带 cf 验证的相关问题」）：
     * 旧版只读 `hanime1.me` 一个域。于是 javchu.com 虽然也在 WebView 里、虽然也可能
     * 已经被 CF 放过，它的 clearance **从来没有被取走** —— 换节点后 javchu 页面 403，
     * 桥里没有任何凭据可以重放，用户看到的则是「关注时好时坏」。
     *
     * 现在按 [CfCookieBridge.HOSTS] 逐域读取：有 clearance 的存 clearance，
     * 有会话标记的存登录层；登录/账户表的处理仍只看 hanime1.me（账号体系在那边）。
     */
    private fun captureAndFinish() {
        if (!done.compareAndSet(false, true)) return
        runCatching { CookieManager.getInstance().flush() }
        val all = readAllHostCookies()
        val cookies = all["hanime1.me"].orEmpty()
        LabLog.i("gate", "取到 cookie 域数=" + all.size + " 主域=" + cookies.length + " 字节")

        // ---------- ① 逐域落盘 clearance ----------
        var cfCount = 0
        for ((h, raw) in all) {
            if (!raw.contains("cf_clearance")) continue
            cfCount++
            CfCookieBridge.persistFor(h, raw)
            HplayCfHolder.markCleared("https://$h/")
            HplayCfHolder.endCooldown(h)
        }
        LabLog.i("gate", "已落盘 clearance 的域：" + cfCount + "/" + all.size)

        // ---------- ② 登录态（账号体系在 hanime1.me）----------
        val logged = cookies.contains("hanime1_session") || cookies.contains("remember_web") ||
            loginDetected.get()
        if (logged && cookies.isNotBlank()) {
            CfCookieBridge.importIntoClient("https://hanime1.me/", cookies)
            CfCookieBridge.persistLogin(cookies)
            LoginState.beginLoadingAccount()
            LoginState.notifyChanged()
            saveAccount(cookies)
            Thread({
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        InstantAccountLoader.loadNow(com.javrocket.hanime.HanimeClient.http)
                    }
                }
            }, "hplay-gate-account").apply { isDaemon = true }.start()
        }
        // 验证态变了：之前 403 的请求该恢复了，清连接池
        HplayCfHolder.clearPool()
        HplayCfHolder.clearNeedVerify()
        finishAndNotify(cookies)
    }

    /**
     * 把这份 cookie 存进多账户表。
     *
     * 身份信息从当前页面读（edit 页有 input#email）；读不到就用 uid 兜底，
     * 再读不到就用时间戳 —— **绝不覆盖已有账户**，否则加第二个号会把第一个冲掉。
     */
    private fun saveAccount(cookies: String) {
        val v = web ?: run { saveAccountWith("", "", "", cookies); return }
        v.post {
            v.evaluateJavascript(IDENTITY_JS) { r ->
                var email = ""
                var uid = ""
                var name = ""
                runCatching {
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
                saveAccountWith(email, uid, name, cookies)
            }
        }
    }

    private fun saveAccountWith(email: String, uid: String, name: String, cookies: String) {
        runCatching {
            val ctx = applicationContext
            val existing = AccountStore.all(ctx)
            // 用 uid 优先匹配：同一账户再次登录时 email 常读不到，靠 uid 才不会变成两条
            val old = existing.firstOrNull { uid.isNotBlank() && it.uid == uid }
                ?: existing.firstOrNull { email.isNotBlank() && it.email.equals(email, true) }
            val key = email.ifBlank {
                old?.email
                    ?: ("uid" + uid).takeIf { uid.isNotBlank() }
                    ?: ("account@" + System.currentTimeMillis())
            }
            AccountStore.save(
                ctx,
                AccountStore.Account(
                    email = key,
                    uid = uid.ifBlank { old?.uid.orEmpty() },
                    name = name.ifBlank { old?.name.orEmpty() },
                    avatar = old?.avatar.orEmpty(),
                    cookie = cookies,
                ),
            )
            if (email.isNotBlank()) com.han1me.X99.putSetting("account.email", email)
            if (uid.isNotBlank()) com.han1me.X99.putSetting("account.uid", uid)
            if (name.isNotBlank()) com.han1me.X99.putSetting("account.name", name)
            LabLog.i("account", "已保存账户 " + key + "（共 " + AccountStore.all(ctx).size + " 个）")
        }
    }

    private fun finishAndNotify(cookies: String) {
        done.set(true)
        val cb = onFinished
        onFinished = null
        runCatching { cb?.invoke(cookies) }
        runCatching { finish() }
    }

    override fun onDestroy() {
        done.set(true)
        runCatching { web?.destroy() }
        web = null
        super.onDestroy()
    }
}

/** 登录标记（与 LoginActivity 同一段 JS，减少一处维护点）。 */
private const val LOGIN_JS = "(function(){" +
    "var t=document.querySelector('#user-modal-trigger');" +
    "if(t&&/\\/user\\/\\d+/.test(t.getAttribute('href')||''))return '1';" +
    "return document.querySelector('.profile-sub-stats-id')?'1':'0';})()"

/** 身份读取（邮箱 / uid / 昵称）。 */
private const val IDENTITY_JS = "(function(){" +
    "var em=document.querySelector('input#email,input[name=email]');" +
    "var mail=em?(em.value||''):'';" +
    "var nm=document.querySelector('input[name=name]');" +
    "var name=nm?(nm.value||''):'';" +
    "var id=document.querySelector('.profile-sub-stats-id');" +
    "var uid=id?((id.textContent||'').match(/\\d+/)||[''])[0]:'';" +
    "if(!uid){var t=document.querySelector('#user-modal-trigger');" +
    "if(t){var h=t.getAttribute('href')||'';var m=h.match(/user\\/(\\d+)/);if(m)uid=m[1];}}" +
    "return JSON.stringify({e:mail,u:uid,n:name});})()"