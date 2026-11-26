package com.han1me.update

import com.han1me.lab.LabLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * **GitHub Releases 更新源**（第 331 轮）。
 *
 * 照 Han1meViewer 的 HUpdater / HGitHubService，以及 Han1mePlus 的 UpdateChecker 实现。
 * 两个参考项目里的可取之处，这里都照抄过来：
 *
 *  1. **API 优先、Atom 兜底**（Plus 的做法）。GitHub REST 匿名访问限额是
 *     60 次/小时/IP —— 一个公共出口 IP 下（家里、公司、手机上同一个运营商 NAT）
 *     很容易打满。打满时 REST 会返回 403 且 body 里带 `API rate limit exceeded`，
 *     此时**退回 releases.atom**（Atom feed 不受 API 限额约束），照样能拿到最新 tag。
 *  2. **错误分类**（Viewer 的做法）。429/403 限额、404 仓库不存在、5xx 服务端故障、
 *     网络异常，四类分开报，界面上说人话，而不是笼统一句「检查失败」。
 *  3. **下载走浏览器直链**（Viewer 的做法）。Release asset 的
 *     `browser_download_url` 是可以直接 GET 的（不是 API，不耗限额）。
 *  4. **CI 渠道读 `ci-*` 预发布**（Viewer 的 CI 频道思路的等价实现）。
 *     Viewer 读 Actions Artifact（要 token），这里改成读我们自己发布的
 *     `ci-<sha>` 预发布 Release —— 不需要任何 token，匿名可读。
 *     这是**有意简化**：Artifact 下载必须带 token，把 token 打进 APK 等于公开泄露。
 *
 * ## 与本项目既有两条通道的关系
 *
 * 139 与自建网盘是**主通道**（构建机直推，改动即时可见）；
 * GitHub 是**第二备用渠道**（用户原话「检查更新拿 github 作为备用渠道2」）。
 * 四条渠道在设置里可自由选，选哪条只查哪条，不做自动串联 ——
 * 串联会让「到底走的哪条」变得不可解释（参考项目里 Viewer 也刻意不串联，理由一样）。
 */
object GitHubReleases {

    /**
     * 独立裸客户端。
     *
     * 为什么不复用 HanimeClient.http：它带 LABGLOBAL 的每请求拦截器（全局挂节点），
     * 而 GitHub 在国内直连通常比经节点更稳（api.github.com 有 CDN，走代理反而多一跳）。
     * 与 [UpdateChecker] 里那两条通道的处理保持一致：彻底直连。
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** GitHub 要求带 UA，否则 403。 */
    private const val UA = "Hplay-Android-Updater"

    private const val API = "https://api.github.com/repos/"

    /** 失败分类（界面上按这个说人话）。 */
    enum class Reason { RATE_LIMIT, NOT_FOUND, SERVER, NETWORK, PARSE, NO_RELEASE, UNCONFIGURED }

    /** 一条 GitHub 发布事实。 */
    data class Release(
        /** tag 名，例如 `lab-v1.1.70` / `v1.1.70` / `ci-a1b2c3d`。 */
        val tag: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
        /** APK 的浏览器直链（Release 没有 APK asset 时为空串）。 */
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val prerelease: Boolean,
        val publishedAt: String,
        /** 从哪条路拿到的：api / atom。 */
        val via: String,
        /** atom 兜底时拿不到体积（Atom 不提供 asset 信息）。 */
        val sizeKnown: Boolean = true,
    )

    /** 检查结果：成功给 [Release]，失败给分类 + 人话原因。 */
    sealed class Result {
        data class Ok(val release: Release) : Result()
        data class Failed(val reason: Reason, val why: String) : Result()
    }

    /**
     * 只允许一个检查在跑（与 [UpdateChecker] 同一套锁语义，含陈旧锁自愈）。
     *
     * 两把锁分开：用户可能在设置里连点「GitHub」再点「139」，
     * 共用一把锁会让第二条被无谓地挡住。
     */
    private val busy = AtomicBoolean(false)
    @Volatile private var busyAt = 0L

    fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        if (busy.compareAndSet(false, true)) { busyAt = now; return true }
        if (now - busyAt > 120_000L) { busyAt = now; return true }
        return false
    }

    fun release() { busy.set(false); busyAt = 0L }

    /**
     * 取某条线的**最新**发布。
     *
     * @param line [HplayUpdateChannel.LINE_LAB] / [HplayUpdateChannel.LINE_STABLE]
     * @param ci   true = 读 `ci-*` 预发布（CI 渠道），false = 读该线的正式发布
     */
    suspend fun fetchLatest(line: String, ci: Boolean): Result = withContext(Dispatchers.IO) {
        if (!HplayUpdateChannel.repoConfigured) {
            return@withContext Result.Failed(
                Reason.UNCONFIGURED,
                "构建时没有注入 GitHub 仓库地址（build.py 的 GH_REPO / 环境变量 HPLAY_GH_REPO 为空）。",
            )
        }
        val repo = HplayBuildInfo.GH_REPO
        // ① API 优先
        val api = runCatching { fromApi(repo, line, ci) }.getOrElse { e -> failOf(e) }
        if (api is Result.Ok) return@withContext api
        val first = api as Result.Failed
        LabLog.i("gh", "API 失败(" + first.reason + ")：" + first.why + " → 试 Atom")

        // ② Atom 兜底：只有「限额 / 服务端 / 网络」才值得退；
        //    仓库不存在或没有 Release 时 Atom 也救不了，直接如实报。
        if (first.reason == Reason.NOT_FOUND || first.reason == Reason.NO_RELEASE) {
            return@withContext first
        }
        val atom = runCatching { fromAtom(repo, line, ci) }.getOrElse { e -> failOf(e) }
        if (atom is Result.Ok) return@withContext atom
        val second = atom as Result.Failed
        Result.Failed(second.reason, "API：" + first.why + "；Atom：" + second.why)
    }

    /**
     * 把异常翻成分类。
     *
     * ★ 必须**先看 [Classified]**：httpText 里已经把 HTTP 状态分好类了，
     * 如果这里统一压成 NETWORK，界面上就会把「限额用尽」误报成「网络不好」，
     * 用户会一直重试而不知道要等一小时。这是第 331 轮实现时自查发现的问题。
     */
    private fun failOf(e: Throwable): Result.Failed =
        if (e is Classified) Result.Failed(e.reason, e.message ?: "")
        else Result.Failed(Reason.NETWORK, e.message ?: e.javaClass.simpleName)

    private fun fromApi(repo: String, line: String, ci: Boolean): Result {
        // CI 渠道要的是「列表里的第一条 ci-*」，正式版要的是 releases/latest。
        // releases/latest 会跳过 prerelease 与 draft —— 正好是实验版需要的语义
        // （实验版 Release 标 prerelease，所以 latest 不会把它当正式版）。
        if (!ci) {
            val j = apiGet(API + repo + "/releases/latest")
            return parseOne(j, repo, line, ci = false, via = "api")
        }
        // CI：取最近 30 条，自己挑第一条 tag 以 ci- 开头且 prerelease 的
        val arr = apiGetArray(API + repo + "/releases?per_page=30")
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val tag = j.optString("tag_name")
            if (!tag.startsWith("ci-")) continue
            if (!j.optBoolean("prerelease", false)) continue
            return parseOne(j, repo, line, ci = true, via = "api")
        }
        return Result.Failed(Reason.NO_RELEASE, "仓库里没有 ci-* 预发布。CI 构建需要先由发布脚本 --ci 上传过一次。")
    }

    /** API GET，把 HTTP 状态翻成分类。 */
    private fun apiGet(url: String): JSONObject {
        val txt = httpText(url, accept = "application/vnd.github+json")
        return JSONObject(txt)
    }

    private fun apiGetArray(url: String): org.json.JSONArray {
        val txt = httpText(url, accept = "application/vnd.github+json")
        return org.json.JSONArray(txt)
    }

    private fun httpText(url: String, accept: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) return body
            val code = resp.code
            val remain = resp.header("x-ratelimit-remaining").orEmpty()
            val reset = resp.header("x-ratelimit-reset").orEmpty()
            // 限额：GitHub 对匿名请求回 403 且 x-ratelimit-remaining=0（也有 429 的情形）
            if (code == 429 || (code == 403 && remain.trim() == "0")) {
                val at = reset.toLongOrNull()?.let { r ->
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                        .format(java.util.Date(r * 1000L))
                }.orEmpty()
                throw Classified(Reason.RATE_LIMIT, "GitHub API 限额已用尽" + if (at.isNotBlank()) "（约 " + at + " 恢复）" else "")
            }
            if (code == 403) throw Classified(Reason.RATE_LIMIT, "GitHub 拒绝访问（HTTP 403）：" + body.take(160))
            if (code == 404) throw Classified(Reason.NOT_FOUND, "仓库或发布不存在（HTTP 404）")
            if (code >= 500) throw Classified(Reason.SERVER, "GitHub 服务端故障（HTTP " + code + "）")
            throw Classified(Reason.NETWORK, "HTTP " + code + "：" + body.take(160))
        }
    }

    /** 带分类的异常（内部用）。 */
    private class Classified(val reason: Reason, msg: String) : Exception(msg)

    private fun parseOne(j: JSONObject, repo: String, line: String, ci: Boolean, via: String): Result {
        val tag = j.optString("tag_name").trim()
        if (tag.isEmpty()) return Result.Failed(Reason.PARSE, "响应里没有 tag_name")
        // 线的过滤：正式版只认本线前缀，避免把实验版当主线版报给用户。
        // CI 不参与过滤（ci-* 是两条线共用的构建快照标签）。
        if (!ci) {
            val p = HplayUpdateChannel.tagPrefix(line)
            if (!tag.startsWith(p)) {
                return Result.Failed(
                    Reason.NO_RELEASE,
                    "仓库最新发布是 " + tag + "，不属于「" + HplayUpdateChannel.lineLabel(line) +
                        "」这条线（该线 tag 前缀 " + p + "）。可能这条线还没发过版。",
                )
            }
        }
        // 从 assets 里挑 APK：优先名字里带当前线的，其次任意 .apk
        val assets = j.optJSONArray("assets")
        var apkUrl = ""
        var apkName = ""
        var apkSize = 0L
        if (assets != null) {
            var best: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val nm = a.optString("name")
                if (!nm.endsWith(".apk", true)) continue
                val want = if (line == HplayUpdateChannel.LINE_STABLE) "stable" else "lab"
                if (nm.contains(want, true)) { best = a; break }
                if (best == null) best = a
            }
            if (best != null) {
                apkUrl = best.optString("browser_download_url")
                apkName = best.optString("name")
                apkSize = best.optLong("size", 0L)
            }
        }
        return Result.Ok(
            Release(
                tag = tag,
                name = j.optString("name").ifBlank { tag },
                body = j.optString("body"),
                htmlUrl = j.optString("html_url"),
                apkUrl = apkUrl,
                apkName = apkName,
                apkSize = apkSize,
                prerelease = j.optBoolean("prerelease", false),
                publishedAt = j.optString("published_at").ifBlank { j.optString("created_at") },
                via = via,
            ),
        )
    }

    /**
     * Atom 兜底（抄 Plus 的 `_fromAtom`）。
     *
     * releases.atom 是 GitHub 的公开 feed，**不计 API 限额**。
     * 缺点：只有 tag 名、标题、正文，**没有 asset 信息**。
     * 所以这条路只能给出「有新版本」+ 网页地址，下载还得靠
     * [apkUrlFromWeb] 去 releases/expanded_assets 找。
     */
    private fun fromAtom(repo: String, line: String, ci: Boolean): Result {
        val txt = httpText(
            "https://github.com/" + repo + "/releases.atom",
            accept = "application/atom+xml, application/xml;q=0.9, */*;q=0.8",
        )
        // 逐 <entry> 解析：tag 在 <id> 末尾（tag:github.com,2008:Repository/xxx/<tag>），
        // 正文在 <content>，网页地址在 <link href>。
        val entries = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(txt).map { it.groupValues[1] }.toList()
        if (entries.isEmpty()) return Result.Failed(Reason.PARSE, "Atom feed 里没有 entry")
        for (e in entries) {
            val id = Regex("<id>(.*?)</id>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1).orEmpty()
            val tag = id.substringAfterLast("/").trim()
            if (tag.isEmpty()) continue
            if (ci) {
                if (!tag.startsWith("ci-")) continue
            } else {
                val p = HplayUpdateChannel.tagPrefix(line)
                if (!tag.startsWith(p)) continue
            }
            val title = unescapeXml(Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1).orEmpty())
            val body = unescapeXml(Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1).orEmpty())
            val link = Regex("<link[^>]*href=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1).orEmpty()
            val updated = Regex("<updated>(.*?)</updated>", RegexOption.DOT_MATCHES_ALL).find(e)?.groupValues?.get(1).orEmpty()
            return Result.Ok(
                Release(
                    tag = tag,
                    name = title.ifBlank { tag },
                    body = stripHtml(body),
                    htmlUrl = link,
                    apkUrl = "",          // Atom 不给 asset；界面上退化成「去网页下载」
                    apkName = "",
                    apkSize = 0L,
                    prerelease = ci,
                    publishedAt = updated,
                    via = "atom",
                    sizeKnown = false,
                ),
            )
        }
        return Result.Failed(
            Reason.NO_RELEASE,
            if (ci) "Atom feed 里没有 ci-* 发布" else "Atom feed 里没有「" + HplayUpdateChannel.lineLabel(line) + "」线的发布",
        )
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&")

    /** Atom 的 content 是转义过的 HTML，剥成纯文本给界面用。 */
    private fun stripHtml(s: String): String =
        s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("\n\n\n", "\n\n")
            .trim()

    /**
     * **从网页找 APK 直链**（Atom 兜底时用）。
     *
     * GitHub 的 releases 页面把 asset 列表放在
     * `/OWNER/REPO/releases/expanded_assets/<tag>` 这个片段页里，
     * 里面每个 asset 是 `/OWNER/REPO/releases/download/<tag>/<文件名>`。
     * 直接请求那个片段页即可，不需要 API、不耗限额。
     */
    fun apkUrlFromWeb(tag: String): String? = runCatching {
        val repo = HplayBuildInfo.GH_REPO
        val url = "https://github.com/" + repo + "/releases/expanded_assets/" + java.net.URLEncoder.encode(tag, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string().orEmpty()
            val m = Regex("href=\"(/[^\"]+/releases/download/[^\"]+\\.apk)\"", RegexOption.IGNORE_CASE).find(html)
                ?: return null
            "https://github.com" + m.groupValues[1]
        }
    }.getOrNull()

    /** 当前安装版本的 versionName（界面显示用；与 [UpdateChecker] 同一份事实源）。 */
    fun currentVersionName(): String = UpdateChecker.currentVersionName()

    /** 当前安装版本的 versionCode。 */
    fun currentVersionCode(): Int = UpdateChecker.currentVersionCode()
}