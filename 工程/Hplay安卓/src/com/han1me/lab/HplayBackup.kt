package com.han1me.lab

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
/**
 * **完整备份 / 恢复**（2026-11-25 第 297 轮；第 322 轮重写并接管全部备份职责）。
 *
 * ## 用户要求（第 322 轮原话）
 *
 * 「新构建的备份与恢复没有接线，备份要涵盖登录凭据，三次元收藏历史，下载和设定等所有操作，
 *   恢复要兼容主线旧的备份。」
 *
 * ## 覆盖范围（逐项对照）
 *
 * | 类别 | 内容 | 来源 |
 * |---|---|---|
 * | 登录凭据 | hanime1_session / remember_web 整串 | CfCookieBridge |
 * | CF 通行证 | cf_clearance / __cf_bm | CfCookieBridge |
 * | 多账户 | 每个账户的 email/uid/昵称/头像/cookie | AccountStore |
 * | han1 账号 | 邮箱 / uid / 昵称 | X99 settings |
 * | 三次元收藏 | URL/标题/封面/板块/时间 | X99.favs |
 * | cos 历史 | cos 板块观看记录 | hplay_cos_history.tsv |
 * | 下载任务 | 任务清单（不含视频文件本身） | hplay_tasks.json |
 * | 下载缓存 | 已下载条目索引 | hplay_dl_cache.json |
 * | 打卡记录 | 每日打卡明细 | hplay_checkin.tsv |
 * | 外观设置 | 主题种子/深浅/AMOLED | HplayThemeStore |
 * | 全部设置 | hanime_settings.properties（节点/线路/界面偏好…） | HanimeClient |
 * | 内置节点 | 节点链接与订阅源 | hpla_lab_node.properties |
 * | 站点目录缓存 | x99_catalog.json | X99 |
 *
 * ## 恢复兼容主线旧备份
 *
 * 主线（com.han1me.app）的备份格式是纯「文件快照」：
 *
 * ```json
 * { "format": 1, "app": "Hplay",
 *   "favorites": "<tsv 文本>", "settings": "<properties 文本>",
 *   "cookies": "<properties 文本>", "labNode": "...", "history": "...",
 *   "catalog": "...", "checkin": "...", "checkinRec": "...", "tasks": "..." }
 * ```
 *
 * 本实现**两种都认**：
 *  · 有 format + app=Hplay → 按主线的「文件快照」语义逐段回写；
 *  · 有 version + favs/account 等结构化键 → 按本版结构化语义恢复。
 * 两者可以同时存在于同一个文件里（本版导出会**同时写两套**，
 * 这样新备份拿到主线版本上也能恢复）。
 */
object HplayBackup {

    /** 本版结构版本（结构化部分）。 */
    private const val VERSION = 3

    /** 主线格式版本（兼容部分必须写 1）。 */
    private const val LEGACY_FORMAT = 1

    private fun dataDir(): File = runCatching { com.han1me.HplayEnv.dataDir() }.getOrElse { File("/") }

    // ================================================================
    // 导出
    // ================================================================

    /**
     * 生成备份 JSON。
     *
     * @return JSON 字符串；失败返回 null
     */
    fun export(ctx: Context): String? = runCatching {
        val root = JSONObject()

        // ---------- 结构化部分（本版） ----------
        root.put("version", VERSION)
        root.put("createdAt", System.currentTimeMillis())
        root.put("createdAtText", nowText())

        // 登录 / clearance cookie（整串）
        root.put("loginCookie", CfCookieBridge.savedLogin())
        root.put("cfCookie", CfCookieBridge.savedClearance())
        root.put("extraCookie", CfCookieBridge.allCookieHeader())
        // ★ 第 355 轮：cookie 改按域存之后，**必须逐域导出** —— 否则换机后
        // javchu.com 的 clearance 丢失，用户又要在那个域上重新过一次盾。
        val cfByHost = JSONObject()
        for (h in CfCookieBridge.HOSTS) {
            val one = CfCookieBridge.allCookieHeader("https://$h/")
            if (one.isNotBlank()) cfByHost.put(h, one)
        }
        root.put("cfByHost", cfByHost)

        // 多账户（每个账户独立 cookie）
        root.put("accounts", AccountStore.exportJson(ctx))

        // 账号展示信息
        val account = JSONObject()
        account.put("email", com.han1me.X99.setting("account.email", ""))
        account.put("uid", com.han1me.X99.setting("account.uid", ""))
        account.put("name", com.han1me.X99.setting("account.name", ""))
        account.put("avatar", CachedAccount.avatarUrl)
        root.put("account", account)

        // 三次元收藏（含板块分类）
        val favs = JSONArray()
        com.han1me.X99.favs().forEach { f ->
            favs.put(
                JSONObject().apply {
                    put("url", f.url); put("title", f.title); put("thumb", f.thumb)
                    put("board", f.board); put("at", f.at)
                },
            )
        }
        root.put("favs", favs)
        root.put("favCount", favs.length())

        // 外观
        val theme = JSONObject()
        theme.put("seed", com.han1me.ui.HplayThemeStore.seed.name)
        theme.put("dark", com.han1me.ui.HplayThemeStore.dark)
        theme.put("amoled", com.han1me.ui.HplayThemeStore.amoled)
        root.put("theme", theme)

        // ---------- 主线兼容部分（文件快照，扁平字符串） ----------
        root.put("format", LEGACY_FORMAT)
        root.put("app", "Hplay")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("exportedAtText", nowText())
        for ((key, file) in legacyTargets()) {
            val txt = readText(file)
            if (txt.isNotEmpty()) root.put(key, txt)
        }

        LabLog.i("backup", "导出完成 " + root.toString().length + " 字节")
        root.toString(2)
    }.onFailure { LabLog.i("backup", "导出失败 " + it.javaClass.simpleName + " " + (it.message ?: "")) }
        .getOrNull()

    /**
     * 主线兼容的文件清单（键名与主线完全一致，恢复时按同名回写）。
     *
     * **必须与主线 HplayBackup.backupTargets() 的键名一字不差** ——
     * 否则「主线导出、实验版恢复」会读不到。
     */
    private fun legacyTargets(): List<Pair<String, File>> = listOf(
        "favorites" to File(dataDir(), "hplay_favorites.tsv"),
        "settings" to File(dataDir(), "hanime_settings.properties"),
        "cookies" to File(dataDir(), "hanime_cookies.properties"),
        "labNode" to File(dataDir(), "hpla_lab_node.properties"),
        "history" to File(dataDir(), "hplay_history.tsv"),
        "catalog" to File(dataDir(), "x99_catalog.json"),
        "checkin" to File(dataDir(), "hplay_checkin.json"),
        "checkinRec" to File(dataDir(), "hplay_checkin.tsv"),
        "tasks" to File(dataDir(), "hplay_tasks.json"),
        // 本版新增（主线没有，但导出时一并带上，将来主线加了也能对上）
        "dlCache" to File(dataDir(), "hplay_dl_cache.json"),
        "cosHistory" to File(dataDir(), "hplay_cos_history.tsv"),
    )
    // ================================================================
    // 恢复
    // ================================================================

    /**
     * 从备份 JSON 恢复。
     *
     * 自动识别两种格式（本版结构化 / 主线文件快照），可以同时恢复。
     *
     * @return 恢复成功的类别数；-1 表示 JSON 解析失败
     */
    fun restore(ctx: Context, json: String): Int {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return -1
        var n = 0
        val detail = StringBuilder()

        val isLegacy = root.optInt("format", 0) > 0 && root.optString("app") == "Hplay"
        val isStructured = root.optInt("version", 0) > 0

        if (!isLegacy && !isStructured) {
            LabLog.i("backup", "无法识别的备份格式")
            return -1
        }
        LabLog.i("backup", "恢复开始 legacy=" + isLegacy + " structured=" + isStructured)

        // ---------- ① 登录凭据（最关键，先恢复） ----------
        val login = root.optString("loginCookie", "")
        if (login.isNotBlank()) {
            CfCookieBridge.persistLogin(login)
            CfCookieBridge.importIntoClient("https://hanime1.me/", login)
            n++; detail.append("登录凭据、")
        }
        val cf = root.optString("cfCookie", "")
        if (cf.isNotBlank()) {
            CfCookieBridge.persist(cf)
            n++; detail.append("CF 通行证、")
        }
        val extra = root.optString("extraCookie", "")
        if (extra.isNotBlank()) CfCookieBridge.importIntoClient("https://hanime1.me/", extra)

        // ★ 第 355 轮：逐域恢复（新格式）。旧备份没有这个键，上面的三条老路径照旧生效。
        val cfByHost = root.optJSONObject("cfByHost")
        if (cfByHost != null) {
            var hosts = 0
            for (h in cfByHost.keys()) {
                val one = cfByHost.optString(h, "")
                if (one.isBlank()) continue
                CfCookieBridge.importIntoClient("https://$h/", one)
                hosts++
            }
            if (hosts > 0) { n++; detail.append("分域 cookie ").append(hosts).append(" 个、") }
        }

        // ---------- ② 多账户 ----------
        val accs = root.optString("accounts", "")
        if (accs.isNotBlank()) {
            val cnt = AccountStore.importJson(ctx, accs)
            if (cnt > 0) { n++; detail.append("账户 ").append(cnt).append(" 个、") }
        }

        // ---------- ③ 账号展示信息 ----------
        root.optJSONObject("account")?.let { a ->
            com.han1me.X99.putSetting("account.email", a.optString("email", ""))
            com.han1me.X99.putSetting("account.uid", a.optString("uid", ""))
            com.han1me.X99.putSetting("account.name", a.optString("name", ""))
            n++
        }

        // ---------- ④ 三次元收藏（按 url 去重合并） ----------
        root.optJSONArray("favs")?.let { arr ->
            val list = ArrayList<com.han1me.X99.FavItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url")
                if (u.isBlank()) continue
                list.add(
                    com.han1me.X99.FavItem(
                        url = u,
                        title = o.optString("title"),
                        thumb = o.optString("thumb"),
                        board = o.optString("board"),
                        at = o.optLong("at"),
                    ),
                )
            }
            if (list.isNotEmpty()) {
                val added = runCatching { com.han1me.X99.restoreFavs(list) }.getOrDefault(0)
                // 即便 0 条新增也算恢复成功（说明本来就有，不是失败）
                n++; detail.append("收藏 ").append(list.size).append(" 条（新增 ").append(added).append("）、")
            }
        }

        // ---------- ⑤ 外观 ----------
        root.optJSONObject("theme")?.let { t ->
            runCatching {
                val sn = t.optString("seed", "ROSE")
                val sd = com.han1me.ui.HplaySeed.entries.firstOrNull { it.name == sn }
                    ?: com.han1me.ui.HplaySeed.ROSE
                com.han1me.ui.HplayThemeStore.setSeed(ctx, sd)
                com.han1me.ui.HplayThemeStore.setDark(ctx, t.optBoolean("dark", true))
                com.han1me.ui.HplayThemeStore.setAmoled(ctx, t.optBoolean("amoled", false))
                n++; detail.append("外观、")
            }
        }

        // ---------- ⑥ 主线兼容的文件快照 ----------
        //
        // 顺序很重要：**先让内存里的旧 cookie 副本失效，再写文件**，
        // 否则 HanimeClient 带着"已加载"标记继续用旧 cookie（表现为「恢复了但仍是未登录」）。
        val legacyPresent = legacyTargets().filter { root.optString(it.first).isNotBlank() }
        if (legacyPresent.isNotEmpty()) {
            // 网络层与设置层的内存副本作废（能安全调的只有这一个）
            runCatching { com.han1me.download.HplayDownloads.invalidateSize() }
            for ((key, file) in legacyPresent) {
                val txt = root.optString(key)
                if (writeText(file, txt)) {
                    n++
                    when (key) {
                        "favorites" -> detail.append("收藏文件、")
                        "settings" -> detail.append("全部设置、")
                        "cookies" -> detail.append("cookie 文件、")
                        "labNode" -> detail.append("内置节点、")
                        "history" -> detail.append("观看历史、")
                        "catalog" -> detail.append("站点目录、")
                        "checkin" -> detail.append("打卡、")
                        "checkinRec" -> detail.append("打卡明细、")
                        "tasks" -> detail.append("下载任务、")
                        "dlCache" -> detail.append("下载索引、")
                        "cosHistory" -> detail.append("cos 历史、")
                    }
                }
            }
            // ★ 主线备份的 cookies 是 properties 文本（name=value 每行一条），
            //   要把它们灌进 CfCookieBridge 的新模型里，否则登录态不生效。
            val cookiesTxt = root.optString("cookies")
            if (cookiesTxt.isNotBlank()) {
                runCatching {
                    val p = java.util.Properties()
                    p.load(cookiesTxt.reader())
                    val sb = StringBuilder()
                    p.forEach { (k, v) ->
                        val name = k.toString()
                        if (name.startsWith("_") && !name.startsWith("__cf") && !name.startsWith("_cfuvid")) return@forEach
                        if (sb.isNotEmpty()) sb.append("; ")
                        sb.append(name).append('=').append(v.toString())
                    }
                    if (sb.isNotEmpty()) {
                        CfCookieBridge.importIntoClient("https://hanime1.me/", sb.toString())
                        LabLog.i("backup", "已把主线 cookie 迁入新模型 " + sb.length + " 字节")
                    }
                }
            }
            // ★ 主线的 settings 是 properties 文本；hanime_settings.properties 已经原样写回，
            //   但内存里的 HanimeClient 还拿着旧值 —— 标记需要重新加载。
            runCatching { com.javrocket.hanime.HanimeClient.clearCookies() }
        }

        // ---------- ⑦ 刷新 UI ----------
        runCatching { CachedAccount.clear() }
        runCatching { CachedAccount.warmFromDisk() }
        runCatching { LoginState.notifyChanged() }
        runCatching {
            // 恢复完立刻重新抓一次账户（有新 cookie 的话 2 秒内就有头像）
            Thread({
                kotlinx.coroutines.runBlocking {
                    runCatching {
                        InstantAccountLoader.loadNow(com.javrocket.hanime.HanimeClient.http)
                    }
                }
            }, "hplay-restore-account").apply { isDaemon = true }.start()
        }

        LabLog.i("backup", "恢复完成，共 " + n + " 类：" + detail.toString())
        return n
    }
    // ================================================================
    // 文件读写
    // ================================================================

    /** 备份文件名（应用数据目录里一份，方便「一键恢复上次」）。 */
    private const val FILE_NAME = "hplay_backup.json"

    private fun backupFile(): File = File(dataDir(), FILE_NAME)

    fun saveToFile(ctx: Context, json: String): Boolean = runCatching {
        backupFile().writeText(json)
        LabLog.i("backup", "已保存到 " + backupFile().absolutePath)
        true
    }.getOrDefault(false)

    fun loadFromFile(ctx: Context): String? = runCatching {
        val f = backupFile()
        if (!f.exists()) return null
        f.readText()
    }.getOrNull()

    /** 把备份写到用户选的位置（系统文件选择器给的 Uri）。 */
    fun saveToUri(ctx: Context, uri: Uri, json: String): Boolean = runCatching {
        ctx.contentResolver.openOutputStream(uri)?.use {
            it.write(json.toByteArray(Charsets.UTF_8))
        } != null
    }.getOrDefault(false)

    /** 从用户选的文件读备份。 */
    fun loadFromUri(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use {
            String(it.readBytes(), Charsets.UTF_8)
        }
    }.getOrNull()

    /** 备份文件默认名（带时间戳）。 */
    fun suggestedName(): String =
        "hplay_backup_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json"

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun readText(f: File): String =
        runCatching { if (f.isFile) f.readText() else "" }.getOrDefault("")

    private fun writeText(f: File, text: String): Boolean = runCatching {
        f.parentFile?.mkdirs()
        f.writeText(text)
        true
    }.getOrDefault(false)

    // ================================================================
    // 摘要（界面显示「这次备份里到底有什么」，不吹牛）
    // ================================================================

    data class Summary(
        val favorites: Int,
        val accounts: Int,
        val settingsKeys: Int,
        val hasLogin: Boolean,
        val hasClearance: Boolean,
        val atText: String,
    )

    fun summarize(json: String): Summary? = runCatching {
        val o = JSONObject(json)
        val accCount = runCatching {
            JSONObject(o.optString("accounts", "{}")).optJSONArray("list")?.length() ?: 0
        }.getOrDefault(0)
        Summary(
            favorites = o.optJSONArray("favs")?.length()
                ?: o.optString("favorites").lines().count { it.isNotBlank() && !it.startsWith("#") },
            accounts = accCount,
            settingsKeys = o.optString("settings").lines().count { it.contains("=") && !it.trimStart().startsWith("#") },
            hasLogin = o.optString("loginCookie").isNotBlank() || o.optString("cookies").isNotBlank(),
            hasClearance = o.optString("cfCookie").contains("cf_clearance"),
            atText = o.optString("createdAtText").ifBlank { o.optString("exportedAtText") },
        )
    }.getOrNull()
}