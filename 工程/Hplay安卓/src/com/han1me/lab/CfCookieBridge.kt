package com.han1me.lab

import android.content.Context
import android.content.SharedPreferences

/**
 * **cookie 总汇（第 355 轮 · 按域模型重写，对齐 Han1meViewer 的 HCookieJar）**。
 *
 * ## 为什么这一轮要重写
 *
 * 用户实测：「关注功能会随内置节点网络变化而出现无法关注问题……让 cookie 可实现包含
 * 全部云端账户请求，或者在最初 cf 验证时就一次解决所有带 cf 验证的相关问题」。
 *
 * 上一版（第 322 轮）把 cookie 当成**一个域**的东西：三串 key 全部硬编码给
 * `hanime1.me`，`parsed()` 甚至不接收 url。后果：
 *
 *  · `javchu.com` 也是 Cloudflare 前置，但它的 clearance **根本无处存放** ——
 *    换节点后 javchu 页面 403，我们连"重放一次"的凭据都没有；
 *  · 用户过盾时只抓了 `hanime1.me` 一个域的 cookie，其它 CF 域等于没过盾；
 *  · `parsed()` 把 hanime1 的 `hanime1_session` 发给了所有域（越权且会被 CF 判异常）。
 *
 * ## 参考实现（逐行读过的结论）
 *
 * | 项目 | 做法 |
 * |---|---|
 * | Han1meViewer · `HCookieJar.loadForRequest(url)` | **每次请求**按 `url.host` 取 login + cloudflare 两串，合并后交给 OkHttp |
 * | Han1meViewer · `CloudflareActivity` | 过盾后 `cookieMgr.getCookie(url)` 取**当前域**整串 → `Preferences.cloudFlareCookie = CookieString(cookies)` |
 * | Han1mePlus · `WebViewCookieBridge.read(urls)` | 把 `hanime1.me / hanime1.com / hanimeone.me / javchu.com` **四个域**的 cookie 一起读出来合并 |
 * | Han1mePlus · `AccountStore` | 按 `host` 分键存 `cloudflare_cookie_v2:<host>` |
 *
 * 三者共同点：**cookie 是按域存的，且每次请求都按域合并**。
 * 本类就是这两条的 Kotlin 实现。
 *
 * ## 存储模型（全部落盘）
 *
 * ```
 *  cf.cookie.raw@<host>     ← 该域过盾后 CookieManager 的整串（含 cf_clearance / __cf_bm）
 *  login.cookie.raw@<host>  ← 该域登录后的整串（含 hanime1_session / remember_web）
 *  extra.cookie.raw@<host>  ← OkHttp 响应里新下发的 Set-Cookie（服务端续期）
 * ```
 *
 * 兼容：`hanime1.me` 读不到分域键时回落到**旧的无域键**（`cf.cookie.raw` 等），
 * 老用户升级后登录态不丢。
 */
object CfCookieBridge {

    private const val SP = "hplay_cf"

    /** 需要管理 cookie 的 Cloudflare 前置域（照 Han1mePlus 的 WebViewCookieBridge.urls）。 */
    val HOSTS: List<String> = listOf(
        "hanime1.me",
        "hanime1.com",
        "hanimeone.me",
        "javchu.com",
    )

    /** 默认域（旧接口的隐含目标）。 */
    private const val DEFAULT_HOST = "hanime1.me"

    private const val K_CF = "cf.cookie.raw"
    private const val K_LOGIN = "login.cookie.raw"
    private const val K_EXTRA = "extra.cookie.raw"

    private fun prefs(): SharedPreferences =
        com.han1me.HplayEnv.requireContext()
            .getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 从 URL 里取 host（大小写归一）；取不到就用默认域。 */
    fun hostOf(url: String): String {
        val h = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        return normalize(h)
    }

    /** 把 `www.` 之类去掉并小写，落到 [HOSTS] 里的某个域。 */
    private fun normalize(raw: String): String {
        var h = raw.trim().lowercase()
        if (h.startsWith("www.")) h = h.substring(4)
        if (h.isBlank()) return DEFAULT_HOST
        for (d in HOSTS) if (h == d || h.endsWith("." + d)) return d
        return h
    }

    private fun key(k: String, host: String): String = "$k@$host"

    private fun read(k: String, host: String): String {
        val p = runCatching { prefs() }.getOrNull() ?: return ""
        val scoped = runCatching { p.getString(key(k, host), "") }.getOrDefault("").orEmpty()
        if (scoped.isNotBlank()) return scoped
        // 兼容旧的无域键（只有默认域继承，避免把 hanime1 的会话发给别的站）
        if (host != DEFAULT_HOST) return ""
        return runCatching { p.getString(k, "") }.getOrDefault("").orEmpty()
    }

    private fun write(k: String, host: String, v: String) {
        runCatching {
            prefs().edit().putString(key(k, host), v).apply()
        }.onFailure { LabLog.i("cf", "cookie 落盘失败 " + it.javaClass.simpleName) }
    }

    // ================================================================
    // 写
    // ================================================================

    /** 保存某个域过盾拿到的整串（含 cf_clearance）。 */
    fun persistFor(host: String, rawCookies: String) {
        if (rawCookies.isBlank()) return
        val h = normalize(host)
        // 与旧值合并：新串可能只有 clearance 而没有 session，不能把 session 冲掉
        val merged = mergeCookie(read(K_CF, h), rawCookies)
        write(K_CF, h, merged)
        bumpVersion()
        LabLog.i("cf", "clearance 已落盘 " + h + " " + merged.length + " 字节")
    }

    /** 保存默认域（hanime1.me）的 clearance —— 旧签名，保留给既有调用点。 */
    fun persist(rawCookies: String) = persistFor(DEFAULT_HOST, rawCookies)

    /** 保存某个域的登录 cookie。 */
    fun persistLoginFor(host: String, rawCookies: String) {
        if (rawCookies.isBlank()) return
        val h = normalize(host)
        val merged = mergeCookie(read(K_LOGIN, h), rawCookies)
        write(K_LOGIN, h, merged)
        bumpVersion()
        LabLog.i("login", "登录态已落盘 " + h + " " + merged.length + " 字节")
    }

    fun persistLogin(rawCookies: String) = persistLoginFor(DEFAULT_HOST, rawCookies)

    /**
     * 收集某个域响应里的 `Set-Cookie` 并落盘。
     *
     * CF 每次校验通过都会**刷新** `cf_clearance` 的到期时间，
     * 只要 App 还在用，有效期自然续上 —— 除非换节点/换 IP。
     */
    fun harvestFor(host: String, setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val h = normalize(host)
        val picked = StringBuilder()
        for (raw in setCookies) {
            val pair = raw.substringBefore(';').trim()
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val name = pair.substring(0, i).trim()
            val value = pair.substring(i + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            if (name.startsWith("cf_chl_")) continue   // 挑战过程用的临时票
            if (picked.isNotEmpty()) picked.append("; ")
            picked.append(name).append('=').append(value)
        }
        if (picked.isEmpty()) return
        val merged = mergeCookie(read(K_EXTRA, h), picked.toString())
        write(K_EXTRA, h, merged)
        bumpVersion()
        LabLog.i("cf", "收到服务端 cookie 刷新 " + h + " " + picked.length + " 字节")
    }

    fun harvest(setCookies: List<String>) = harvestFor(DEFAULT_HOST, setCookies)

    // ================================================================
    // 读
    // ================================================================

    fun savedLoginFor(host: String): String = read(K_LOGIN, normalize(host))

    fun savedLogin(): String = savedLoginFor(DEFAULT_HOST)

    fun savedClearanceFor(host: String): String = read(K_CF, normalize(host))

    fun savedClearance(): String = savedClearanceFor(DEFAULT_HOST)

    /** 该域是否已有 cf_clearance（决定要不要先静默重放）。 */
    fun hasPersistedFor(host: String): Boolean {
        val h = normalize(host)
        return read(K_CF, h).contains("cf_clearance") ||
            read(K_EXTRA, h).contains("cf_clearance")
    }

    fun hasPersisted(): Boolean = hasPersistedFor(DEFAULT_HOST)

    /** 是否有任何可用 cookie（决定 CF 拦截器要不要先静默重放）。 */
    fun hasAnyCookie(): Boolean = HOSTS.any {
        read(K_CF, it).isNotBlank() || read(K_LOGIN, it).isNotBlank()
    }

    /**
     * **按域合并三层 Cookie 头**（每次请求都调）。
     *
     * 顺序即优先级：后写的覆盖先写的。
     * `extra`（服务端最新下发）> `clearance`（过盾时拿到的）> `login`（登录时拿到的）。
     */
    fun allCookieHeader(url: String = "https://hanime1.me/"): String {
        val h = hostOf(url)
        val v = version
        if (v == cacheVersion && cacheHost == h && cacheValue != null) return cacheValue!!
        var out = ""
        out = mergeCookie(out, read(K_LOGIN, h))
        out = mergeCookie(out, read(K_CF, h))
        out = mergeCookie(out, read(K_EXTRA, h))
        cacheValue = out
        cacheVersion = v
        cacheHost = h
        return out
    }

    @Volatile private var version: Int = 0
    @Volatile private var cacheVersion: Int = -1
    @Volatile private var cacheHost: String = ""
    @Volatile private var cacheValue: String? = null

    private fun bumpVersion() {
        version++
    }

    /**
     * **解析成 (name, value) 列表**，供 cookieJar 的 `loadForRequest` 逐条构造 Cookie。
     *
     * ## 为什么必须走 cookieJar，而不是只设 Cookie 头（第 322 轮真机定位）
     *
     * OkHttp 的 `BridgeInterceptor` 里：只要 jar 返回非空，就会**整条覆盖** Cookie 头。
     * 这正是 Viewer 把登录/clearance cookie 塞进 `HCookieJar.loadForRequest` 的原因 ——
     * 那里是唯一不会被覆盖的地方。
     */
    fun parsed(url: String = "https://hanime1.me/"): List<Pair<String, String>> {
        val all = allCookieHeader(url)
        if (all.isBlank()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (seg in all.split(';')) {
            val t = seg.trim()
            if (t.isEmpty()) continue
            val i = t.indexOf('=')
            if (i <= 0) continue
            out.add(t.substring(0, i).trim() to t.substring(i + 1).trim())
        }
        return out
    }

    /**
     * **给一条请求挂上该域的完整 Cookie 头**（CF 拦截器重放时必须调）。
     *
     * OkHttp 的拦截器链是单向的：CF 拦截器拿到的 `chain` 在 cookie 拦截器**之后**，
     * 它调 `chain.proceed(request)` 时前面的拦截器不会重跑 —— 重放用的是裸请求。
     * 所以重放前必须显式把 cookie 挂上去。
     */
    fun applyTo(request: okhttp3.Request): okhttp3.Request {
        val ours = allCookieHeader(request.url.toString())
        if (ours.isBlank()) return request
        val theirs = request.header("Cookie").orEmpty()
        val merged = mergeCookie(theirs, ours)
        if (merged == theirs) return request
        return request.newBuilder().header("Cookie", merged).build()
    }

    /**
     * 合并两个 `k=v; k2=v2` 串：右边的值覆盖左边的同名项，顺序保持左边在前。
     */
    fun mergeCookie(base: String, extra: String): String {
        if (extra.isBlank()) return base
        val map = LinkedHashMap<String, String>()
        fun eat(s: String) {
            for (seg in s.split(';')) {
                val t = seg.trim()
                if (t.isEmpty()) continue
                val i = t.indexOf('=')
                if (i <= 0) continue
                map[t.substring(0, i).trim()] = t.substring(i + 1).trim()
            }
        }
        eat(base)
        eat(extra)
        if (map.isEmpty()) return ""
        val sb = StringBuilder()
        for ((k, v) in map) {
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append(k).append('=').append(v)
        }
        return sb.toString()
    }

    // ================================================================
    // 生命周期
    // ================================================================

    /** 清掉登录态（退出登录用）；clearance 保留（它属于本机网络环境，不属于账号）。 */
    fun clearLogin() {
        runCatching {
            val e = prefs().edit()
            for (h in HOSTS) {
                e.remove(key(K_LOGIN, h))
                val extra = read(K_EXTRA, h)
                if (extra.contains("hanime1_session") || extra.contains("remember_web")) {
                    e.remove(key(K_EXTRA, h))
                }
            }
            e.remove(K_LOGIN)
            e.apply()
        }
        bumpVersion()
        LabLog.i("login", "已清除登录态")
    }

    /** 彻底清空（调试/重置用）。 */
    fun clearAll() {
        runCatching { prefs().edit().clear().apply() }
        bumpVersion()
    }

    /**
     * 启动时把**每个域**的三段 cookie 回灌进 CookieManager（WebView）。
     *
     * 这与参考实现一致：cookie 不是"用一次就完"，而是每次启动都回灌，
     * 这样下次过盾/网页登录时能直接过。
     */
    fun restoreAtStartup() {
        var total = 0
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            for (h in HOSTS) {
                val all = allCookieHeader("https://$h/")
                if (all.isBlank()) continue
                total += all.length
                for (seg in all.split(';')) {
                    val s = seg.trim()
                    if (s.isEmpty() || !s.contains('=')) continue
                    cm.setCookie("https://$h/", s)
                    cm.setCookie("https://.$h/", s)
                }
            }
            cm.flush()
        }.onFailure {
            LabLog.i("cf", "回灌 CookieManager 失败 " + it.javaClass.simpleName)
        }
        if (total == 0) LabLog.i("cf", "无历史 cookie，首次需过一次挑战")
        else LabLog.i("cf", "已回灌历史 cookie " + total + " 字节（clearance=" + hasAnyClearance() + "）")
    }

    private fun hasAnyClearance(): Boolean = HOSTS.any { hasPersistedFor(it) }

    /** 兼容旧名字。 */
    fun restoreLoginAtStartup() = restoreAtStartup()

    /**
     * 旧接口：判断是不是登录串/clearance 串并分别落盘。
     *
     * **现在按 url 的域存**（旧版一律存给 hanime1.me，导致 javchu 永远没有凭据）。
     */
    fun importIntoClient(url: String, cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        val h = hostOf(url)
        if (cookieHeader.contains("hanime1_session") || cookieHeader.contains("remember_web")) {
            persistLoginFor(h, cookieHeader)
        }
        if (cookieHeader.contains("cf_clearance") || cookieHeader.contains("__cf_bm")) {
            persistFor(h, cookieHeader)
        }
        if (!cookieHeader.contains("hanime1_session") &&
            !cookieHeader.contains("remember_web") &&
            !cookieHeader.contains("cf_clearance") &&
            !cookieHeader.contains("__cf_bm")
        ) {
            write(K_EXTRA, h, mergeCookie(read(K_EXTRA, h), cookieHeader))
            bumpVersion()
        }
    }
}
