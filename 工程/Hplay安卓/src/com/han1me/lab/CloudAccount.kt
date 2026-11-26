package com.han1me.lab

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * **云端账户信息 / 头像**（2026-11-25 第 293 轮；第 322 轮重写）。
 *
 * 用户要求：「头像需要你获取云端 han1me 的账户头像，不是本地」，
 * 并补充「如果修不好就丢弃云端头像，不重要」。
 *
 * ## 两个参考项目的头像方案（逐条对照）
 *
 * | 项目 | 取法 |
 * |---|---|
 * | Han1meViewer · `Parser.userAccountPage` | `@GET("user/{userid}/edit")` → `parseBody.selectFirst("img#playlist-avatar")` → `absUrl("src")`；uid 来自首页里的 `/user/(\d+)` |
 * | Han1meViewer · `AvatarCropScreen` | 上传走 `updateUserAccountAvatar(userId, csrfToken, multipart)`，**必须带 CSRF token**（`meta[name=csrf-token]`） |
 * | Han1mePlus | 走同一路径，cookie 由 `account_store` 统一注入 |
 *
 * 两者都依赖**同一个前提**：请求必须带着登录 cookie 且能过 CF。
 * 我们旧版 403 的根因就是这一点（见 [CfCookieBridge] 类注释），
 * 现在 cookie 由 [HplayCookieInterceptor] 每次请求合并，这个前提成立了。
 *
 * ## 本轮做的三件事
 *
 * 1. **落盘缓存**：抓到一次就存进 SharedPreferences，冷启动先显示缓存，
 *    再后台刷新 —— 不再「每次启动都要等一次网络，失败就永远空白」。
 * 2. **两条路径**：`/user/<uid>/edit`（Viewer 用的）取不到时，退到
 *    `/user/<uid>` 的 og:image 兜底。
 * 3. **彻底失败就静默放弃**：拿不到 URL 就不显示头像，绝不摆一个破图。
 */
object CloudAccount {

    private const val BASE = "https://hanime1.me"
    private const val SP = "hplay_cloud_acc"

    private const val K_UID = "uid"
    private const val K_NAME = "name"
    private const val K_AVATAR = "avatar"
    private const val K_MAIL = "mail"
    private const val K_AT = "at"

    /** 一次抓取的结果。 */
    data class Info(
        val userId: String = "",
        val userName: String = "",
        val avatarUrl: String = "",
        val email: String = "",
    )

    private fun prefs(): android.content.SharedPreferences? = runCatching {
        com.han1me.HplayEnv.requireContext().getSharedPreferences(SP, Context.MODE_PRIVATE)
    }.getOrNull()

    /** 读磁盘缓存（冷启动先用它，避免空白头像闪一下）。 */
    fun cached(): Info? {
        val p = prefs() ?: return null
        val uid = runCatching { p.getString(K_UID, "").orEmpty() }.getOrDefault("")
        val av = runCatching { p.getString(K_AVATAR, "").orEmpty() }.getOrDefault("")
        if (uid.isBlank() && av.isBlank()) return null
        return Info(
            userId = uid,
            userName = runCatching { p.getString(K_NAME, "").orEmpty() }.getOrDefault(""),
            avatarUrl = av,
            email = runCatching { p.getString(K_MAIL, "").orEmpty() }.getOrDefault(""),
        )
    }

    private fun save(info: Info) {
        val p = prefs() ?: return
        runCatching {
            p.edit()
                .putString(K_UID, info.userId)
                .putString(K_NAME, info.userName)
                // 头像 URL 只在新值非空时覆盖：抓取偶发失败不能把已有头像清掉
                .apply {
                    if (info.avatarUrl.isNotBlank()) putString(K_AVATAR, info.avatarUrl)
                    if (info.email.isNotBlank()) putString(K_MAIL, info.email)
                }
                .putLong(K_AT, System.currentTimeMillis())
                .apply()
        }
    }

    fun clearCache() {
        runCatching { prefs()?.edit()?.clear()?.apply() }
    }

    /**
     * 抓取账户信息。失败返回 null（调用方保留旧值）。
     *
     * 现在不做超时控制 —— 由 [InstantAccountLoader] 用 `withTimeoutOrNull` 包。
     */
    fun fetch(http: OkHttpClient): Info? {
        LabLog.i("account", "开始抓取云端账户")
        val uid = runCatching { com.javrocket.hanime.HanimeClient.userId() }.getOrNull().orEmpty()
            .ifBlank { null }
            ?: runCatching {
                // 退一步：自己从首页抓一次（userId() 内部有缓存，可能拿到空）
                val homeHtml = http.newCall(
                    Request.Builder().url("$BASE/").get().build(),
                ).execute().use { r ->
                    LabLog.i("account", "GET / -> " + r.code)
                    if (!r.isSuccessful) return null
                    r.body?.string().orEmpty()
                }
                Regex("""/user/(\d+)""").find(homeHtml)?.groupValues?.get(1)
            }.getOrNull()
        if (uid.isNullOrBlank()) {
            LabLog.i("account", "拿不到 uid（未登录或被拦）")
            return null
        }

        // ---- 主路径：/user/<uid>/edit（Viewer 用的那一条）----
        val editHtml = runCatching {
            http.newCall(Request.Builder().url("$BASE/user/$uid/edit").get().build()).execute().use { r ->
                LabLog.i("account", "GET /user/" + uid + "/edit -> " + r.code)
                if (!r.isSuccessful) "" else r.body?.string().orEmpty()
            }
        }.getOrDefault("")

        var avatar = extractAvatar(editHtml)
        val name = Regex(
            """<input[^>]*name=["']name["'][^>]*value=["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(editHtml)?.groupValues?.get(1).orEmpty()
        val mail = Regex(
            """<input[^>]*(?:id=["']email["']|name=["']email["'])[^>]*value=["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(editHtml)?.groupValues?.get(1).orEmpty()

        // ---- 兜底路径：/user/<uid> 的 og:image ----
        if (avatar.isBlank()) {
            val profHtml = runCatching {
                http.newCall(Request.Builder().url("$BASE/user/$uid").get().build()).execute().use { r ->
                    LabLog.i("account", "GET /user/" + uid + " -> " + r.code)
                    if (!r.isSuccessful) "" else r.body?.string().orEmpty()
                }
            }.getOrDefault("")
            avatar = Regex(
                """<meta[^>]*property=["']og:image["'][^>]*content=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            ).find(profHtml)?.groupValues?.get(1)
                ?: Regex(
                    """<img[^>]*id=["']playlist-avatar["'][^>]*src=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE,
                ).find(profHtml)?.groupValues?.get(1)
                ?: ""
        }

        // ★★★ 2026-11-25（第 322 轮 · 真机定位）：**OkHttp 拿不到就交给 WebView**。
        //
        // 日志：WebView 里已经拿到 cf_clearance（3532 字节），OkHttp 带着同一串 cookie
        // 仍然 403。根因是 clearance 还绑定 TLS 指纹（JA3）——WebView 是 Chromium 的
        // BoringSSL，OkHttp 是 Java/Conscrypt，ClientHello 不同，CF 判「环境不一致」。
        // 这不是 cookie 传递的问题（jar 层已确认带上去了），协议层绕不过。
        //
        // 两个参考项目的账号页**本来就都走 WebView**，我们之前照 Parser 抄了取法
        // 却用 OkHttp 发，那一步才是错的。
        var finalAvatar = avatar
        var finalName = name
        var finalMail = mail
        if (finalAvatar.isBlank()) {
            LabLog.i("account", "OkHttp 取不到头像，改用 WebView 抓")
            val wv = runCatching { AvatarFetcher.fetch(uid) }.getOrNull()
            if (wv != null) {
                if (wv.avatarUrl.isNotBlank()) finalAvatar = wv.avatarUrl
                if (wv.userName.isNotBlank()) finalName = wv.userName
                if (wv.email.isNotBlank()) finalMail = wv.email
            }
        }

        val info = Info(userId = uid, userName = finalName, avatarUrl = finalAvatar, email = finalMail)
        if (finalAvatar.isNotBlank() || finalName.isNotBlank()) save(info)
        LabLog.i(
            "account",
            "云端账户 uid=" + uid + " name=" + finalName + " avatar=" +
                (if (finalAvatar.isBlank()) "无" else finalAvatar.take(60)),
        )
        return info
    }

    /** 从 /edit 页面抽头像 URL（两种属性顺序都要兼容）。 */
    private fun extractAvatar(html: String): String {
        if (html.isBlank()) return ""
        val re = Regex(
            """<img[^>]*id=["']playlist-avatar["'][^>]*src=["']([^"']+)["']""" +
                """|<img[^>]*src=["']([^"']+)["'][^>]*id=["']playlist-avatar["']""",
            RegexOption.IGNORE_CASE,
        )
        val m = re.find(html) ?: return ""
        return m.groupValues[1].takeIf { it.isNotBlank() } ?: m.groupValues[2]
    }
}
