package com.han1me.update

import com.javrocket.hanime.HanimeClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 「检查更新」。
 *
 * ## 第 332 轮：139 直连整条移除，改为 **GitHub 默认 + 自建网盘垫底**
 *
 * 用户原话：「彻底去除 139 直连相关更新渠道，代码层也去掉，改成 github 默认 + 自建垫底」。
 *
 * 所以这个文件现在只管两条路：
 *  · **GitHub**（[GitHubReleases]）：默认。匿名可读，不需要任何凭据。
 *  · **自建网盘 OpenList**（本文件）：垫底。只读 API，也不需要凭据。
 *
 * 139 为什么必须删：它要一条 Basic 凭据才能取清单与直链，而凭据等价于
 * 移动云盘账号的访问权。第 331 轮实测发现它会被原样编进 classes*.dex ——
 * 公开分发版只能靠 `--no-cred` 剥掉，剥掉后这条渠道**本来就用不了**。
 * 留一条默认失效的渠道只会让用户看到「未配置凭据」的失败页。
 *
 * ## 网盘通道的限制（如实标注，别在界面上说成「已是最新」）
 * 网盘给出的 raw_url 是**带签名的临时直链**（约 15 分钟有效）。所以：
 *  - 「检查更新」每次点都**重新取一次**，不复用旧值；
 *  - 拿到直链就立刻交给 [UpdateService] 去下；
 *  - 不把直链存进任何持久化位置（存了也会过期，反而制造「点了没反应」）。
 *
 * ## 诚实性
 * 取不到就如实说取不到并给出原因；**绝不显示「已是最新」** ——
 * 那会把「没查到」谎报成「查过了」。
 */
object UpdateChecker {

    /** 网盘上的构建版本目录（与 `tools/push_to_mea.py` 的 REMOTE_DIR 必须一致）。 */
    /**
     * **构建版本目录（多通道）**。
     *
     * 第 113 轮（用户要求「默认走移动网盘134，其他通道故障时自动切换，自建网盘最后兜底」）：
     * 每次发版都把 APK 与 version.json 推到**下面每一个目录**；读取时按顺序试，
     * **任一条通就拿到清单** ⇒ 单条挂载故障不会让「检查更新」整体失效。
     *
     * 顺序 = 第 113 轮定的优先级：移动云盘134 → 移动云盘195 → 天翼云盘134 → 123云盘134。
     *
     * 第 332 轮：这些目录是**网盘（OpenList）的挂载点**，读它们只需要网盘的只读 API，
     * 不需要任何账号凭据 —— 与已删除的 139 直连是两件事，别混。
     */
    private val REMOTE_DIRS = listOf(
        "/移动云盘134/hplay",       // ①
        "/移动云盘195/hplay",       // ②
        "/天翼云盘134-个人云/hplay",   // ③
        "/123云盘134/hplay",      // ④
    )
    /** 兼容旧引用：默认第一条。 */
    private val REMOTE_DIR get() = REMOTE_DIRS.first()
    private const val MANIFEST = "version.json"

    /**
     * OpenList 端点。作为**兜底通道**保留。
     *
     * ★ 第 331 轮（进公开仓库前处理）：**内网端点从 App 里彻底移除**。
     *
     * 原来这里有两行：公网 + 一条软路由内网地址。内网那条的理由是「在家时更快」。
     * 但这一版开始 APK 要**公开发布**，而 dex 里的字符串是明文 ——
     * 实测（zipfile 扫 classes*.dex）确实能在 classes2.dex 里搜到那个内网地址。
     * 把一个家庭网络的地址打进公开分发的二进制里，收益是「家里省一跳」，
     * 代价是「向所有人公布本机网段」 —— 不划算，直接删掉。
     *
     * 行为影响：家里也走公网端点。公网端点是 Cloudflare 后面的公网域名，
     * 家里访问只是多一次公网往返，不影响可用性。
     *
     * 保留 List 结构：多端点轮询的代码路径（下面的 for 循环）与「以后加一条
     * 备用公网域名」是同一套逻辑，改成单字符串会让扩展变麻烦。
     */
    private val ENDPOINTS: List<String> = listOf(
        "https://mea.yuzusoft.ccwu.cc",     // 公网（实测匿名 API 可读）
    )

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    /** 一条「远端有什么版本」的事实。 */
    data class Remote(
        val versionName: String,
        val versionCode: Int,
        val size: Long,
        val md5: String,
        val notes: String,
        val apk: String,
        /**
         * 139 直连才有的 fileId。
         *
         * ★ 第 332 轮：139 渠道已删除，这个字段**源代码里已经没有写入点**，
         * 保留它只为不破坏旧备份/旧接口的形状（默认空串，无害）。
         * 新增代码不要再往它赋值 —— 那会让读者以为 139 还活着。
         */
        val fileId: String = "",
        /**
         * ★ 第 331 轮：GitHub 通道才有。
         *
         * 这几项与 139/网盘的语义不同：
         *  · [ghTag] / [ghHtmlUrl] 是给人看的（「v1.1.70」「在浏览器里打开」）；
         *  · [ghSizeKnown]=false 表示这份清单来自 Atom 兜底，
         *    **体积未知** —— 界面上不能显示「0 B」，要显示「体积未知」。
         *    （参考项目 Plus 的 Atom 兜底也是这个限制，它只是不给体积。）
         */
        val ghTag: String = "",
        val ghHtmlUrl: String = "",
        val ghApkName: String = "",
        val ghMd5: String = "",
        val ghSizeKnown: Boolean = true,
        /**
         * ★ 第 344 轮：GitHub asset 的 sha256（API 的 digest 字段），空 = 站点没给。
         * 有它就能在校验里做**内容级**比对，而不只是比体积。
         */
        val ghSha256: String = "",
    ) {
        /** 「v1.0.22 · 10.1 MB」；体积未知时如实说，不显示 0 B。 */
        val sizeText: String
            get() = when {
                !ghSizeKnown -> "体积未知"
                size >= 1048576 -> "%.1f MB".format(size / 1048576.0)
                size > 0 -> "${size} B"
                else -> "体积未知"
            }
    }

    /**
     * 从哪条通道拿到的（界面如实标注，便于判断「这次到底走的是哪条」）。
     *
     * ★ 第 332 轮：[YUN139] **已删除**。渠道 id 定义在 [HplayUpdateChannel]，
     * 这里的枚举只用于界面文案分支 —— 删掉枚举项后编译器会指出所有遗留分支，
     * 比留着「永远走不到的分支」安全。
     */
    enum class Via { NETDISK, GITHUB, CI }

    /** 一次检查的结果。**失败必须能被区分出来**，不能与「已是最新」混为一谈。 */
    sealed class Result {
        /** 取到清单了。 */
        data class Ok(val remote: Remote, val current: Int, val via: Via) : Result() {
            val hasUpdate: Boolean get() = remote.versionCode > current
        }

        /** 没取到 —— 带上人话原因（每条通道各一条，或用「；」串起来）。 */
        data class Failed(val why: String) : Result()
    }

    private val busy = AtomicBoolean(false)
    /** 防连点锁的置位时刻，用于「陈旧锁自愈」（第 152 轮）。 */
    @Volatile private var busyAt = 0L

    /** 当前安装版本的 versionCode。从 PackageManager 读（版本号不许在界面另存一份常量）。 */
    fun currentVersionCode(): Int = runCatching {
        val c = com.han1me.HplayEnv.requireContext()
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    }.getOrDefault(0)

    /** 当前安装版本的 versionName（界面显示用）。 */
    fun currentVersionName(): String = runCatching {
        val c = com.han1me.HplayEnv.requireContext()
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private val http: OkHttpClient by lazy {
        // 第 223 轮（用户指出）：检查更新走内置节点很慢 —— 网盘通道是直连最快的。
        // 不能复用 HanimeClient.http：它带 LABGLOBAL 每请求拦截器（全局挂节点），
        // builder 级 NO_PROXY 会被拦截器覆盖。这里用独立裸客户端，彻底直连。
        OkHttpClient.Builder()
            .addInterceptor(com.han1me.log.LoggingInterceptor)
            .proxy(java.net.Proxy.NO_PROXY)
            .build()
    }

    /**
     * **按用户选的渠道取清单**（第 331 轮建立，第 332 轮去掉 139）。
     *
     * 第 332 轮：139 那条已删除，现在只有 GitHub / CI / 网盘三条。
     * 一次检查只查**一条**渠道 ——「为什么没查到」永远只有一个原因，可解释。
     *
     * 参考依据：Han1meViewer 的 \`currentChannel()\` 也是「渠道由用户设置唯一决定」，
     * 并明确写了**不自动回落**（CI 出问题时回落到 Release 会让用户被降级到旧版）。
     * 这里沿用同一个口径，但保留一个例外：网盘是 4 个挂载目录的**内部**轮询，
     * 那不是跨渠道回落，是同一渠道内部的容错。
     */
    suspend fun fetchRemote(): Result = when (HplayUpdateChannel.channel) {
        HplayUpdateChannel.CH_GITHUB -> fetchGitHub(ci = false)
        HplayUpdateChannel.CH_CI -> fetchGitHub(ci = true)
        else -> fetchRemoteNetdisk()
    }

    /**
     * **GitHub / CI 渠道**。
     *
     * 与网盘通道的最大语义差别：GitHub 的版本标识是 **tag 名**（lab-v1.1.70），
     * 不是 versionCode。而 [Result.Ok.hasUpdate] 比的是 versionCode。
     *
     * 所以这里把 tag 里的版本号解析成 versionCode 的等价形式再比：
     *  · tag 形如 \`lab-v1.1.70\` / \`v1.1.70\` → 取 1.1.70；
     *  · 与 [currentVersionName] 做**点分段逐段比较**（抄 Plus 的 \`_newer\`）。
     * 这样「远端 1.1.70、本机 1.1.69」能正确判为新版，而不依赖构建日期的 versionCode。
     *
     * 为什么不直接用 versionCode：GitHub Release 里没有 versionCode 字段，
     * 只能从 tag 或 APK 文件名里拿；用 tag 更稳（tag 是发布的事实来源）。
     */
    private suspend fun fetchGitHub(ci: Boolean): Result {
        val line = HplayUpdateChannel.line
        val r = GitHubReleases.fetchLatest(line, ci)
        if (r is GitHubReleases.Result.Failed) {
            val hint = when (r.reason) {
                GitHubReleases.Reason.UNCONFIGURED -> "GitHub 渠道未配置仓库地址"
                GitHubReleases.Reason.RATE_LIMIT -> "GitHub API 限额用尽"
                GitHubReleases.Reason.NOT_FOUND -> "仓库或发布不存在"
                GitHubReleases.Reason.SERVER -> "GitHub 服务端故障"
                GitHubReleases.Reason.NO_RELEASE -> "这条发布线还没有发过版"
                GitHubReleases.Reason.PARSE -> "GitHub 返回的内容无法解析"
                GitHubReleases.Reason.NETWORK -> "网络不通"
            }
            return Result.Failed(hint + "：" + r.why)
        }
        val rel = (r as GitHubReleases.Result.Ok).release
        val remoteVer = versionOfTag(rel.tag)
        val cur = currentVersionName()
        val via = if (ci) Via.CI else Via.GITHUB
        // 把「是否更新」编码进 current/remote 这一对：hasUpdate 是 remote.versionCode > current。
        // 这里 current 恒为 0（表示「不比你新」），remote 用 1（表示「比你新」）或 0。
        val newer = isNewer(remoteVer, cur)
        val remote = Remote(
            versionName = remoteVer.ifBlank { rel.tag },
            versionCode = if (newer) 1 else 0,
            size = rel.apkSize,
            md5 = "",
            notes = rel.body,
            apk = rel.apkName,
            fileId = "",
            ghTag = rel.tag,
            ghHtmlUrl = rel.htmlUrl,
            ghApkName = rel.apkName,
            ghSizeKnown = rel.sizeKnown,
            ghSha256 = rel.apkSha256,
        )
        return Result.Ok(remote, 0, via)
    }

    /**
     * 从 tag 里取版本号。
     * \`lab-v1.1.70\` → \`1.1.70\`；\`v1.1.70\` → \`1.1.70\`；\`ci-a1b2c3d\` → 空串（CI 没有版本号）。
     */
    private fun versionOfTag(tag: String): String {
        var t = tag.trim()
        if (t.startsWith("lab-")) t = t.removePrefix("lab-")
        t = t.trimStart('v', 'V')
        return if (t.isNotEmpty() && t[0].isDigit()) t.substringBefore('-').substringBefore('+') else ""
    }

    /**
     * 点分段比较（照 Han1mePlus 的 \`_newer\`）。
     * 远端为空（CI 构建没有版本号）时按「有更新」处理 —— CI 本来就是给追新的用户用的。
     */
    private fun isNewer(remote: String, local: String): Boolean {
        if (remote.isBlank()) return true
        val a = parts(remote)
        val b = parts(local)
        // ★ 第 344 轮：**按较长的一边比，缺段补 0**。
        // 原来固定比前 3 段 —— 远端 1.1.70.1、本机 1.1.70 会被判成「已是最新」，
        // 界面隐藏下载按钮，用户永远拿不到那个 hotfix。
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int> {
        val seg = v.trim().trimStart('v', 'V').split('-').first().split('+').first().split('.')
        // ★ 第 344 轮：不再截断成 3 段（配合 isNewer 的变长比较）。
        return seg.map { it.toIntOrNull() ?: 0 }
    }

    /**
     * **自建网盘渠道**（第 332 轮：从「139 → 网盘」的串联里摘出来，只留网盘）。
     *
     * 这一条是**垫底通道**：GitHub 与 CI 都不通时才该用它。
     * 内部仍然是多挂载目录轮询（第 113 轮定的顺序），任一条能取到清单即用 ——
     * 单条挂载故障不会让整条渠道失效。
     */
    private suspend fun fetchRemoteNetdisk(): Result = withContext(Dispatchers.IO) {
        val reasons = ArrayList<String>(REMOTE_DIRS.size * ENDPOINTS.size)
        // 多通道链（第 113 轮）：按优先级逐个试，任一条通即用。
        //
        // 顺序：移动云盘134（默认）→ 移动云盘195 → 天翼云盘134 → 123云盘134。
        // 每个目录先在**公网端点**试（出门也能用），失败再试内网端点。
        // 这样单条挂载故障会**自动切换**到下一个，用户无感；全部不通才报失败。
        for (dir in REMOTE_DIRS) {
            for (base in ENDPOINTS) {
                val r = runCatching { fetchOne(base, dir) }
                r.getOrNull()?.let { return@withContext Result.Ok(it, currentVersionCode(), Via.NETDISK) }
                val short = base.substringAfter("//").substringBefore("/")
                reasons.add("$dir@$short：${r.exceptionOrNull()?.message ?: "未知原因"}")
            }
        }
        Result.Failed(reasons.joinToString("；"))
    }

    private fun fetchOne(base: String, dir: String = REMOTE_DIR): Remote {
        val body = JSONObject().put("path", dir + "/" + MANIFEST).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(base + "/api/fs/get")
            .header("User-Agent", UA)
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val root = JSONObject(text)
            if (root.optInt("code") != 200) {
                throw IllegalStateException(root.optString("message").ifBlank { "接口返回 code!=200" })
            }
            val d = root.optJSONObject("data") ?: throw IllegalStateException("响应里没有 data")
            // 清单本身要先经 raw_url 取回（/api/fs/get 只给文件元信息 + 签名直链，不给正文）
            val raw = d.optString("raw_url")
            if (raw.isBlank()) throw IllegalStateException("接口没给出 raw_url")
            val j = JSONObject(getText(raw))
            return Remote(
                versionName = j.optString("versionName"),
                versionCode = j.optInt("versionCode"),
                size = j.optLong("size"),
                md5 = j.optString("md5"),
                notes = j.optString("notes"),
                apk = j.optString("apk"),
            )
        }
    }

    private fun getText(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /**
     * 取 APK 的**签名直链**（有效期约 15 分钟）。
     * 界面拿到它就该立刻交给浏览器/下载器；不要缓存。
     *
     * 第 332 轮：139 那条已删除，现在只有两条路 ——
     *  · GitHub / CI：Release asset 的浏览器直链（不耗 API 限额）；
     *  · 网盘：raw_url 签名直链，**做大小校验**（对不上说明与清单不是同一次发布）。
     */
    suspend fun apkDirectUrl(remote: Remote): String? = withContext(Dispatchers.IO) {
        // ★ 第 331 轮：GitHub / CI 渠道的清单没有 fileId，也不是网盘上的文件 ——
        // 它给的是 Release asset 的浏览器直链，可以直接 GET（不耗 API 限额）。
        // Atom 兜底时 asset 未知，再去 releases/expanded_assets 片段页里捞一次。
        if (remote.ghTag.isNotBlank()) {
            if (remote.ghApkName.isNotBlank() && remote.ghHtmlUrl.isNotBlank()) {
                // 从 html_url（.../releases/tag/<tag>）拼 download 地址
                val baseUrl = "https://github.com/" + HplayBuildInfo.GH_REPO + "/releases/download/" +
                    java.net.URLEncoder.encode(remote.ghTag, "UTF-8") + "/" +
                    java.net.URLEncoder.encode(remote.ghApkName, "UTF-8")
                return@withContext baseUrl
            }
            val fromWeb = GitHubReleases.apkUrlFromWeb(remote.ghTag)
            if (fromWeb.isNullOrBlank()) {
                com.han1me.HplayEnv.toast("这个 Release 里没有找到 APK 资产；请在浏览器里打开发布页手动下载")
                return@withContext null
            }
            return@withContext fromWeb
        }
        val reasons = ArrayList<String>(REMOTE_DIRS.size * ENDPOINTS.size)
        // 网盘多通道（第 113 轮）：与 fetchRemoteNetdisk 同一优先级链
        for (dir in REMOTE_DIRS) {
        for (base in ENDPOINTS) {
            val r = runCatching {
                val body = JSONObject().put("path", dir + "/" + remote.apk).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url(base + "/api/fs/get")
                    .header("User-Agent", UA)
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val root = JSONObject(resp.body?.string().orEmpty())
                    if (root.optInt("code") != 200) throw IllegalStateException(root.optString("message"))
                    val d = root.optJSONObject("data") ?: throw IllegalStateException("没有 data")
                    val raw = d.optString("raw_url")
                    if (raw.isBlank()) throw IllegalStateException("没有 raw_url")
                    val sz = d.optLong("size", -1)
                    if (remote.size > 0 && sz > 0 && sz != remote.size) {
                        throw IllegalStateException("网盘上的大小(${sz})与清单(${remote.size})不符")
                    }
                    raw
                }
            }
            r.getOrNull()?.let { return@withContext it }
            val short = base.substringAfter("//").substringBefore("/")
            reasons.add("$dir@$short：${r.exceptionOrNull()?.message ?: "未知原因"}")
        }
        }
        com.han1me.HplayEnv.toast("取下载直链失败：${reasons.joinToString("；")}")
        null
    }

    /**
     * 防连点：一次只跑一个检查。
     *
     * ★ 第 152 轮：加**陈旧锁自愈**。原来只 compareAndSet，
     * 一旦占用方被取消而没来得及 release（协程还没开始跑就被取消这种窄缝），
     * 这把锁就永久卡住，表现为「点重新检查没反应」。
     * 现在超过 2 分钟仍被占着就强制放行。
     */
    fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        if (busy.compareAndSet(false, true)) { busyAt = now; return true }
        if (now - busyAt > 120_000L) { busyAt = now; return true }
        return false
    }

    fun release() {
        busy.set(false)
        busyAt = 0L
    }
}
