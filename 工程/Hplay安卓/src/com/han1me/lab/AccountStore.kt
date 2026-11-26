package com.han1me.lab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * **多账户管理**（2026-11-25 第 298 轮）。
 *
 * 用户要求：「保留切换账户和退出登录按钮，切换账户中可以切换多个 cookie，
 * 也就是切换多个账户态，做到完美切换他们的所有云端收藏历史资料等」。
 *
 * ## 模型

 * 每个账户 = 一份 cookie + 一份账号信息，整体存成一个 JSON 数组：
 * ```json
 * [
 *   { "email": "...", "uid": "...", "name": "...", "avatar": "...", "cookie": "...", "savedAt": 0 }
 * ]
 * ```
 * 另有一个 `active` 指针指向当前账户（按 email 索引）。
 *
 * ## 切换账户为什么能"完美切换"

 * 云端数据（收藏/历史/订阅）不在本地缓存，**每次进页面都按当前 cookie 现拉**。
 * 所以切 cookie = 立刻看到另一个账号的全部数据，不需要同步/清理任何缓存。
 * 唯一要清的是 UI 层状态（user/profile 变量），由切换处负责。
 */
object AccountStore {

    private const val FILE = "hplay_accounts"
    private const val K_LIST = "accounts"
    private const val K_ACTIVE = "activeEmail"

    /** 一个已保存的账户。 */
    data class Account(
        val email: String,
        val uid: String = "",
        val name: String = "",
        val avatar: String = "",
        val cookie: String = "",
        val savedAt: Long = 0L,
    );

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 全部已保存账户（按保存时间倒序）。 */
    fun all(ctx: Context): List<Account> {
        val raw = runCatching { prefs(ctx).getString(K_LIST, "[]").orEmpty() }.getOrDefault("[]")
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<Account>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Account(
                        email = o.optString("email"),
                        uid = o.optString("uid"),
                        name = o.optString("name"),
                        avatar = o.optString("avatar"),
                        cookie = o.optString("cookie"),
                        savedAt = o.optLong("savedAt"),
                    ),
                )
            }
            out.sortedByDescending { it.savedAt }
        }.getOrDefault(emptyList())
    }

    /** 当前激活账户的 email；没有返回空串。 */
    fun activeEmail(ctx: Context): String =
        runCatching { prefs(ctx).getString(K_ACTIVE, "").orEmpty() }.getOrDefault("")

    /** 当前激活账户；没有返回 null。 */
    fun active(ctx: Context): Account? {
        val e = activeEmail(ctx)
        if (e.isBlank()) return null
        return all(ctx).firstOrNull { it.email == e }
    }

    /**
     * 保存/更新一个账户并设为激活。
     *
     * 同一 email 会被覆盖（cookie 刷新），不产生重复项。
     */
    fun save(ctx: Context, acc: Account) {
        val list = all(ctx).toMutableList()
        list.removeAll { it.email.equals(acc.email, true) }
        list.add(0, acc.copy(savedAt = System.currentTimeMillis()))
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("email", a.email)
                    put("uid", a.uid)
                    put("name", a.name)
                    put("avatar", a.avatar)
                    put("cookie", a.cookie)
                    put("savedAt", a.savedAt)
                },
            )
        }
        prefs(ctx).edit()
            .putString(K_LIST, arr.toString())
            .putString(K_ACTIVE, acc.email)
            .apply()
        // cookie 立刻灌进 OkHttp
        runCatching { CfCookieBridge.importIntoClient("https://hanime1.me/", acc.cookie) }
        runCatching { CfCookieBridge.persistLogin(acc.cookie) }
        LabLog.i("account", "已保存账户 " + acc.email + "，共 " + list.size + " 个")
    }

    /** 切换到指定账户（不改 cookie 内容，只换激活态 + 灌 cookie）。 */
    fun switchTo(ctx: Context, email: String): Account? {
        val acc = all(ctx).firstOrNull { it.email == email } ?: return null
        prefs(ctx).edit().putString(K_ACTIVE, acc.email).apply()
        runCatching { CfCookieBridge.importIntoClient("https://hanime1.me/", acc.cookie) }
        runCatching { CfCookieBridge.persistLogin(acc.cookie) }
        LabLog.i("account", "已切换到 " + acc.email)
        return acc
    }

    /** 更新一个账户的展示信息（头像/昵称抓到后回填）。 */
    fun updateMeta(ctx: Context, email: String, name: String = "", avatar: String = "", uid: String = "") {
        if (email.isBlank()) return
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.email.equals(email, true) }
        if (i < 0) return
        val old = list[i]
        list[i] = old.copy(
            name = name.ifBlank { old.name },
            avatar = avatar.ifBlank { old.avatar },
            uid = uid.ifBlank { old.uid },
        )
        writeList(ctx, list, activeEmail(ctx))
    }

    /**
     * **整体导出**（备份用）：返回 JSON 数组字符串。
     *
     * 含 cookie，属敏感数据 —— 备份文件本身已经标明这一点（界面上有提示）。
     */
    fun exportJson(ctx: Context): String = all(ctx).let { list ->
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("email", a.email); put("uid", a.uid); put("name", a.name)
                    put("avatar", a.avatar); put("cookie", a.cookie); put("savedAt", a.savedAt)
                },
            )
        }
        JSONObject().apply {
            put("active", activeEmail(ctx))
            put("list", arr)
        }.toString()
    }

    /**
     * **整体导入**（恢复用）。
     *
     * 合并语义：按 email 去重，备份里的覆盖同 email 的旧条目，
     * 其它账户保持不动 —— 换设备/恢复旧备份都不会把现有账户冲掉。
     *
     * @return 导入的账户数
     */
    fun importJson(ctx: Context, json: String): Int {
        if (json.isBlank()) return 0
        return runCatching {
            val root = JSONObject(json)
            val arr = root.optJSONArray("list") ?: return 0
            val cur = all(ctx).toMutableList()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val e = o.optString("email")
                if (e.isBlank()) continue
                cur.removeAll { it.email.equals(e, true) }
                cur.add(
                    Account(
                        email = e,
                        uid = o.optString("uid"),
                        name = o.optString("name"),
                        avatar = o.optString("avatar"),
                        cookie = o.optString("cookie"),
                        savedAt = o.optLong("savedAt", System.currentTimeMillis()),
                    ),
                )
                n++
            }
            val act = root.optString("active").ifBlank { activeEmail(ctx) }
            writeList(ctx, cur, act)
            LabLog.i("account", "导入账户 " + n + " 个")
            n
        }.getOrDefault(0)
    }

    /** 统一的列表写盘（save / remove / updateMeta / importJson 共用）。 */
    private fun writeList(ctx: Context, list: List<Account>, active: String) {
        val sorted = list.sortedByDescending { it.savedAt }
        val arr = JSONArray()
        sorted.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("email", a.email); put("uid", a.uid); put("name", a.name)
                    put("avatar", a.avatar); put("cookie", a.cookie); put("savedAt", a.savedAt)
                },
            )
        }
        prefs(ctx).edit()
            .putString(K_LIST, arr.toString())
            .putString(K_ACTIVE, active)
            .apply()
    }

    /** 删除一个账户（退出登录时用）。 */
    fun remove(ctx: Context, email: String) {
        val list = all(ctx).filterNot { it.email.equals(email, true) }
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("email", a.email)
                    put("uid", a.uid)
                    put("name", a.name)
                    put("avatar", a.avatar)
                    put("cookie", a.cookie)
                    put("savedAt", a.savedAt)
                },
            )
        }
        val next = if (activeEmail(ctx).equals(email, true)) {
            list.firstOrNull()?.email.orEmpty()
        } else {
            activeEmail(ctx)
        }
        prefs(ctx).edit()
            .putString(K_LIST, arr.toString())
            .putString(K_ACTIVE, next)
            .apply()
        if (next.isBlank()) CfCookieBridge.clearLogin()
        LabLog.i("account", "已移除 " + email + "，剩余 " + list.size + " 个")
    }
}
