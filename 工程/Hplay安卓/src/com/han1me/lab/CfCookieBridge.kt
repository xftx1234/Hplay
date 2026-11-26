package com.han1me.lab

import android.content.Context
import android.content.SharedPreferences

/**
 * **cookie 总汇**（2026-11-25 第 322 轮重写）。
 *
 * ## 用户要求

 * 「让 cf 过一次全部都不用重新验证」「如果修不好就丢弃云端头像」。
 *
 * ## 两个参考项目是怎么做到的
 *
 * | 项目 | 做法 |
 * |---|---|
 * | Han1meViewer | `HCookieJar.loadForRequest` **每次请求**都把 `Preferences.loginCookie` 与 `Preferences.cloudFlareCookie` 合并进来。cookie 是"一直带着"的，不是"导入一次"。 |
 * | Han1mePlus | `saveCookies` 存整串到 account_store，`setCloudflareCookie` 灌进 Dio 的 CookieManager，且**每次启动**都回灌。 |
 *
 * 共同点：**cookie 必须是"每次请求都合并"的模型**，而不是"过盾时往 map 里塞一次"。
 *
 * ## 旧实现错在哪
 *
 * `importIntoClient` 把 cookie 塞进 `HanimeClient.cookieMap`，而那个 jar 有两条硬编码：
 *
 * ```kotlin
 * // 1) 导入时丢弃所有以 _ 开头的 cookie
 * if (k.isEmpty() || k.startsWith("_")) continue
 * // 2) 发请求时也丢弃所有以 _ 开头的
 * cookieMap.filterKeys { !it.startsWith("_") }...
 * ```
 *
 * 第一条是为了不把内部标记 `_uid` 当成 cookie 发出去 ——
 * 但它**连 `__cf_bm`、`_cfuvid` 一起丢了**，而这两个是 Cloudflare 的
 * bot-management cookie，缺了它们 clearance 会被判为"环境不一致"。
 *
 * 更关键的是 **cookie 的域/路径全是硬编码**（`domain("hanime1.me").path("/")`），
 * WebView 里那些带 `Path=/user` 的会话 cookie 语义就被抹平了。
 *
 * ## 现在的模型（三层，全部落盘）
 *
 * ```
 *  cf.cookie.raw      ← WebView 过盾后 CookieManager 的整串（含 cf_clearance / __cf_bm）
 *  login.cookie.raw   ← 登录成功后 CookieManager 的整串（含 hanime1_session / remember_web）
 *  extra.cookie.raw   ← OkHttp 响应里新下发的 Set-Cookie（服务端刷新 clearance 时更新）
 * ```
 *
 * 三者由 [allCookieHeader] **按 name 合并去重**（后写的胜），交给
 * [HplayCookieInterceptor] 在**每次请求**前补进 Cookie 头 —— 与 Viewer 的
 * `loadForRequest` 完全等价，但不用改上游 `HanimeClient`。
 */
object CfCookieBridge {

    private const val SP = "hplay_cf"

    /** WebView 过盾后拿到的整串（含 cf_clearance）。 */
    private const val K_CF_COOKIE = "cf.cookie.raw"

    /** 登录成功后的整串。 */
    private const val K_LOGIN_COOKIE = "login.cookie.raw"

    /** 网络响应里新下发/刷新的 cookie。 */
    private const val K_EXTRA_COOKIE = "extra.cookie.raw"

    private fun prefs(): SharedPreferences =
        com.han1me.HplayEnv.requireContext()
            .getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun read(k: String): String =
        runCatching { prefs().getString(k, "").orEmpty() }.getOrDefault("")

    private fun write(k: String, v: String) {
        runCatching {
            prefs().edit().putString(k, v).apply()
        }.onFailure { LabLog.i("cf", "cookie 落盘失败 " + it.javaClass.simpleName) }
    }

    // ================================================================
    // 写
    // ================================================================

    /** 保存 WebView 过盾拿到的整串（含 cf_clearance）。 */
    fun persist(rawCookies: String) {
        if (rawCookies.isBlank()) return
        // 与旧值合并：新串可能只有 clearance 而没有 session，不能把 session 冲掉
        val merged = mergeCookie(read(K_CF_COOKIE), rawCookies)
        write(K_CF_COOKIE, merged)
        bumpVersion()
        LabLog.i("cf", "clearance 已落盘 " + merged.length + " 字节")
    }

    /** 保存登录 cookie。 */
    fun persistLogin(rawCookies: String) {
        if (rawCookies.isBlank()) return
        val merged = mergeCookie(read(K_LOGIN_COOKIE), rawCookies)
        write(K_LOGIN_COOKIE, merged)
        bumpVersion()
        LabLog.i("login", "登录态已落盘 " + merged.length + " 字节")
    }

    /**
     * 收集响应里的 `Set-Cookie` 并落盘。
     *
     * 为什么要做：CF 会在每次校验通过后**刷新** `cf_clearance` 的有效期
     * （响应头里带新的 Set-Cookie）。旧实现只在过盾那一刻存一次，
     * 于是 clearance 到期后必然要重新弹窗 —— 用户看到的就是「隔几小时又要点一次」。
     * 现在只要 App 还在用，每次刷新都会被收下，有效期自然续上。
     */
    fun harvest(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val picked = StringBuilder()
        for (raw in setCookies) {
            // Set-Cookie: name=value; Path=/; Expires=...; Secure; HttpOnly
            val pair = raw.substringBefore(';').trim()
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val name = pair.substring(0, i).trim()
            val value = pair.substring(i + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            // 只收本站相关的（其它域的下发与我们无关）
            if (name.startsWith("cf_chl_")) continue   // 挑战过程用的临时票，留着反而污染
            if (picked.isNotEmpty()) picked.append("; ")
            picked.append(name).append('=').append(value)
        }
        if (picked.isEmpty()) return
        val merged = mergeCookie(read(K_EXTRA_COOKIE), picked.toString())
        write(K_EXTRA_COOKIE, merged)
        bumpVersion()
        LabLog.i("cf", "收到服务端 cookie 刷新 " + picked.length + " 字节")
    }

    // ================================================================
    // 读
    // ================================================================

    fun savedLogin(): String = read(K_LOGIN_COOKIE)

    fun savedClearance(): String = read(K_CF_COOKIE)

    /** 是否有任何可用 cookie（决定 CF 拦截器要不要先静默重放）。 */
    fun hasAnyCookie(): Boolean =
        read(K_CF_COOKIE).isNotBlank() || read(K_LOGIN_COOKIE).isNotBlank()

    /** 磁盘上是否已有 cf_clearance（重启后仍有效）。 */
    fun hasPersisted(): Boolean =
        read(K_CF_COOKIE).contains("cf_clearance") ||
            read(K_EXTRA_COOKIE).contains("cf_clearance")

    /**
     * **合并三层的 Cookie 头**（每次请求都调）。
     *
     * 顺序即优先级：后写的覆盖先写的。
     * `extra`（服务端最新下发）> `clearance`（过盾时拿到的）> `login`（登录时拿到的）。
     *
     * @param url 目标地址（目前只有一个域，保留参数便于将来分域）
     */
    fun allCookieHeader(url: String = "https://hanime1.me/"): String {
        // 缓存：cookieJar.loadForRequest 是**每条请求**都要调的，
        // 每次都读三次 SharedPreferences + 解析三次字符串会明显拖慢。
        // 三个写入点（persist / persistLogin / harvest）都会 bump 版本号。
        val v = version
        if (v == cacheVersion && cacheValue != null) return cacheValue!!
        var out = ""
        out = mergeCookie(out, read(K_LOGIN_COOKIE))
        out = mergeCookie(out, read(K_CF_COOKIE))
        out = mergeCookie(out, read(K_EXTRA_COOKIE))
        cacheValue = out
        cacheVersion = v
        return out
    }

    @Volatile
    private var version: Int = 0

    @Volatile
    private var cacheVersion: Int = -1

    @Volatile
    private var cacheValue: String? = null

    private fun bumpVersion() {
        version++
    }

    /**
     * **解析成 (name, value) 列表**，供 cookieJar 的 `loadForRequest` 逐条构造 Cookie。
     *
     * ## 为什么必须走 cookieJar，而不是只设 Cookie 头（第 322 轮真机定位）
     *
     * OkHttp 的 `BridgeInterceptor` 里有这么一段：
     *
     * ```kotlin
     * val cookies = cookieJar.loadForRequest(userRequest.url)
     * if (cookies.isNotEmpty()) {
     *     userRequestBuilder.header("Cookie", cookieHeader(cookies))   // ← 直接覆盖
     * }
     * ```
     *
     * 也就是说：**应用拦截器里设的 Cookie 头，只要 jar 返回非空就会被整条替换掉**。
     * 这正是 Viewer 把登录/clearance cookie 塞进 `HCookieJar.loadForRequest`
     * （而不是塞进请求头）的原因 —— 那里是唯一不会被覆盖的地方。
     *
     * 日志证据（改之前）：WebView 拿到 3532 字节 cookie、重放仍 403；
     * 因为重放那一刻 jar 只吐了 cookieMap 里那几个，clearance 根本不在里面。
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
     * **给一条请求挂上完整 Cookie 头**（CF 拦截器重放时必须调）。
     *
     * ## 为什么重放要单独调（第 322 轮真机定位）
     *
     * OkHttp 的拦截器链是单向的：CF 拦截器拿到的 `chain` 位置在
     * `HplayCookieInterceptor` **之后**。它调 `chain.proceed(request)` 时，
     * 前面的拦截器（含 cookie 补全）**不会重新执行** ——
     * 于是重放用的是那条**原始请求**（没有任何 Cookie 头）。
     *
     * 真机日志就是这个形态：
     * ```
     * [cf] 静默过盾成功，cookie 3532 字节     <- WebView 拿到了
     * [cf] 静默过盾拿到 cookie 但重放仍被挑战   <- 重放是裸的，当然还是 403
     * ```
     *
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
     *
     * 公开给 [HplayCookieInterceptor] 用（请求原有 Cookie 优先，我们只补缺）。
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
            prefs().edit().remove(K_LOGIN_COOKIE).apply()
            // 退出登录时也清掉服务端下发的会话类 cookie，避免下一个账号继承上一个的身份
            val extra = read(K_EXTRA_COOKIE)
            if (extra.contains("hanime1_session") || extra.contains("remember_web")) {
                prefs().edit().remove(K_EXTRA_COOKIE).apply()
            }
        }
        bumpVersion()
        LabLog.i("login", "已清除登录态")
    }

    /** 彻底清空（调试/重置用）。 */
    fun clearAll() {
        runCatching { prefs().edit().clear().apply() }
    }

    /**
     * 启动时把三段 cookie 回灌进两处：
     *  · OkHttp：由 [HplayCookieInterceptor] 每次请求自动带 —— 这里**不用**再灌；
     *  · CookieManager（WebView）：下次过盾/网页登录时能直接过。
     *
     * 旧实现还要 importIntoClient（往上游 cookieMap 塞），现在废弃了 ——
     * 那条路会丢掉 `_` 开头的 cookie，正是 clearance 失效的元凶之一。
     */
    fun restoreAtStartup() {
        val all = allCookieHeader()
        if (all.isBlank()) {
            LabLog.i("cf", "无历史 cookie，首次需过一次挑战")
            return
        }
        runCatching {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            for (seg in all.split(';')) {
                val s = seg.trim()
                if (s.isEmpty()) continue
                cm.setCookie("https://hanime1.me/", s)
                cm.setCookie("https://.hanime1.me/", s)
            }
            cm.flush()
        }.onFailure {
            LabLog.i("cf", "回灌 CookieManager 失败 " + it.javaClass.simpleName)
        }
        LabLog.i("cf", "已回灌历史 cookie " + all.length + " 字节（clearance=" + hasPersisted() + "）")
    }

    /** 兼容旧名字（LabInit / LoginActivity 里用过）。 */
    fun restoreLoginAtStartup() = restoreAtStartup()

    /**
     * 旧接口：往上游 `HanimeClient.cookieMap` 里塞。
     *
     * **现在只做落盘**，不再碰 cookieMap —— 详见类注释。
     * 保留同名同签名，是为了不让既有调用点全部改一遍（那些调用点分布在
     * patches.py 生成的 2400 行文件里，改一处就要动一个补丁锚点）。
     */
    fun importIntoClient(url: String, cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        // 判断是不是登录串：含 session / remember_web 就当登录态存
        if (cookieHeader.contains("hanime1_session") || cookieHeader.contains("remember_web")) {
            persistLogin(cookieHeader)
        }
        if (cookieHeader.contains("cf_clearance") || cookieHeader.contains("__cf_bm")) {
            persist(cookieHeader)
        }
        // 两样都不含（只有零散 cookie）也存进 extra，别丢
        if (!cookieHeader.contains("hanime1_session") &&
            !cookieHeader.contains("remember_web") &&
            !cookieHeader.contains("cf_clearance") &&
            !cookieHeader.contains("__cf_bm")
        ) {
            write(K_EXTRA_COOKIE, mergeCookie(read(K_EXTRA_COOKIE), cookieHeader))
        }
    }
}
