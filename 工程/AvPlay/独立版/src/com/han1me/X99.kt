package com.han1me

import com.javrocket.hanime.HanimeClient
import com.javrocket.hanime.HanimeDetail
import com.javrocket.hanime.HanimeNavItem
import com.javrocket.hanime.HanimeSection
import com.javrocket.hanime.HanimeVideo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit

/** x99dh.cc 的一条「线路」（优选访问）。 */
data class X99Line(val url: String, val testUrl: String) {
    val host: String get() = runCatching { URI(url).host ?: url }.getOrDefault(url)
}

/** x99dh.cc 的一个站点（板块）。 */
data class X99Site(val name: String, val desc: String, val url: String, val lines: List<X99Line>)

/**
 * x99dh.cc 优选目录 + 各站点解析。
 *
 * 全部请求**直连**（Proxy.NO_PROXY + 系统 DNS）：x99dh 收录的这些域名本身就是"国内直连可达"的优选线路，
 * 不走代理、不用 DoH；播放交给内置浏览器（WebView2，真实浏览器指纹才能通过 CDN 的人机校验）。
 */
object X99 {
    const val CATALOG_URL = "https://x99dh.cc/"
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 本命板块（hanime1）在 x99dh 目录里的站点名，与其它站点分开展示。 */
    const val HANIME_SITE = "Hanime1"

    /** 本命板块的官方源（可登录；DoH/代理等网络特殊功能仅对它生效）。 */
    const val OFFICIAL_HANIME = "https://hanime1.me"

    /** 站点板块默认站点（用户指定：默认 MissAV）。 */
    const val DEFAULT_SITE = "MissAV"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(boardDns)
            .build()
    }

    /**
     * 名单内的板块域在本机网络下**解析是污染的**，而且第 43 轮把「污染」量清楚了
     * （`临时\r43_dns\dns_probe.log`）：同一个 `www.qinav.com` 一轮里三家解析给出
     * 199.16.156.75（系统）/ 74.86.142.55（DNSPod DoH）/ 199.59.149.206（阿里 DoH），
     * 全是 Facebook/Dropbox 段的黑洞地址，钉上去 8 s 全部超时——
     * **DNSPod 与阿里的 DoH 自己也会被污染**，所以「换一个 DNS 查」不是可靠救法
     * （上一轮就是这个判据写的 DoH，真机表现为「qinav 加载很慢甚至不通」）。
     *
     * 真正稳定的是**边缘节点**：qinav 前置 Cloudflare，任取一个 CF anycast 边缘 IP、
     * 带**真实 SNI**、并**保留完整证书校验**去连，实测 8 个里 7 个直接回 HTTP 200、耗时 0.8~0.9 s
     * （`临时\r43_edge\cf_edge.log`）。CF 是按 SNI 回源的，所以这些 IP 不是「qinav 的 IP」，
     * 而是「离我最近的那台 CF 边缘」——这也正是本工作区官方源那套「边缘节点」的同一个原理。
     *
     * 因此策略从「DoH 优先、系统垫底」改成**先验后给**：候选（本机验通过的 > 内置 CF 边缘 > DoH > 系统）
     * 逐个做 TCP+TLS 实测，只把**真连上的**交给 OkHttp；一个都没连上时退回原来的 DoH+系统并集，
     * 不比上一版更差。名单外的域名原样交给调用方的解析，异常照抛——
     * **官方源与既有板块的网络路径一行不变**。
     */
    // xchina.download 也在名单里：xchina 的**分片**域是 cdn.xchina.download，
    // 只给页面域做处理的话清单取到了、分片仍会被污染成 0.0.0.0。
    private val edgeHostSuffixes = setOf("qinav.com", "xchina.co", "xchina001.ink", "xchina.download")

    /**
     * 内置 Cloudflare anycast 边缘候选。来源是 2026-10-02 对本机网络的实测
     * （`临时\r43_edge\cf_edge.log`，qinav 8 个里 7 个回 200；`104.16.132.229` 回 403 所以没收进来），
     * 不是抄来的「公共 DNS 列表」。它们**必须逐个实测后才采用**（见 [probeTls]）：
     * 套到没在 CF 上的域，证书校验就过不去、这一发被丢弃，不会把站点带偏。
     */
    private val cfEdgeSeeds = listOf(
        "104.21.22.147", "172.67.205.85", "172.67.206.141", "104.21.53.2",
        "172.64.155.209", "141.101.120.7", "188.114.97.6",
    )

    /**
     * 「实测过：随便钉一台 CF 边缘 + 真实 SNI 就能拿到本站」的族。目前只有 qinav——
     * `临时\r43_edge\cf_edge.log` 里 8 个 anycast IP 有 7 个对 `www.qinav.com` 直接回 HTTP 200。
     * 名单外的族（xchina）仍把 DoH 排在边缘前面，因为 DoH 给的是**该站自己的** CF IP，
     * 比随机 anycast 更贴；随机 anycast 撞上别的 colo 时有可能 403（实测 `104.16.132.229` 就对 qinav 403 过）。
     */
    private val cfFrontedFamilies = setOf("qinav.com")

    /** DoH 端点一律写 IP 字面量：否则"要解析 DoH 域名先得有可用 DNS"就成了鸡生蛋。 */
    private val dohEndpoints = listOf(
        "https://1.12.12.12/dns-query?name=%s&type=A",
        "https://223.5.5.5/resolve?name=%s&type=A",
        "https://1.1.1.1/dns-query?name=%s&type=A",
    )

    private val dohCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<java.net.InetAddress>, Long>>()
    /** 实测通过的边缘地址（进程内），与 [dohCache] 同期失效。 */
    private val edgeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<java.net.InetAddress>, Long>>()
    private const val EDGE_TTL_MS = 10 * 60 * 1000

    /** 最近一次板块解析的人话结论：手机抑制日志，出问题时要能上屏（X99Board 的板块头部用它）。 */
    @Volatile
    var lastBoardDns: String = ""
        private set

    private val ipv4Re = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    private val bootstrapHttp: OkHttpClient by lazy {
        OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    }

    /** DoH 查询（端点依次试，第一个非空即返回）。端点写 IP 字面量，避免"解析 DoH 域名先得有 DNS"。 */
    private fun dohAddresses(hostname: String): List<java.net.InetAddress> {
        val out = ArrayList<java.net.InetAddress>()
        for (tpl in dohEndpoints) {
            val ips = runCatching {
                val url = String.format(tpl, hostname)
                val req = Request.Builder().url(url).header("accept", "application/dns-json").build()
                bootstrapHttp.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    Regex(""""type"\s*:\s*(\d+)[^}]*?"data"\s*:\s*"([^"]+)"""")
                        .findAll(body)
                        .filter { it.groupValues[1] == "1" && ipv4Re.matches(it.groupValues[2]) }
                        .map { java.net.InetAddress.getByName(it.groupValues[2]) }
                        .toList()
                }
            }.getOrDefault(emptyList())
            out += ips
            if (out.isNotEmpty()) break
        }
        return out.distinct()
    }

    /**
     * 名单内域名的解析：**先实测再交出去**。
     *
     * 候选顺序 = 本机上次验通过的 → 内置 CF 边缘 → DoH → 系统解析，逐个做 [probeTls]（真实 SNI + 完整
     * 证书校验），只有连上的才返回；拿到两个就停手（够 OkHttp 轮换，又不至于一开场就发十几发握手）。
     * 一个都没验通时**原样交回 DoH+系统的并集**，让失败形态和上一版一致（连接超时），
     * 而不是伪造一个「空地址列表」——那会被 OkHttp 打成 `/0.0.0.0:443`，把根因藏掉（上一轮就这么错过）。
     */
    private fun resolveBoardHost(hostname: String, systemLookup: () -> List<java.net.InetAddress>): List<java.net.InetAddress> {
        if (edgeHostSuffixes.none { hostname == it || hostname.endsWith(".$it") }) return systemLookup()
        val now = System.currentTimeMillis()
        val hit = edgeCache[hostname]
        if (hit != null && hit.first.isNotEmpty() && now - hit.second < EDGE_TTL_MS) return hit.first
        val system = runCatching(systemLookup).getOrDefault(emptyList())
        val d = dohCache[hostname]
        val viaDoh = if (d != null && now - d.second < EDGE_TTL_MS) d.first
        else dohAddresses(hostname).also { if (it.isNotEmpty()) dohCache[hostname] = it to now }
        // 候选顺序按「这一族是不是实测过的前置 CF」分两种：
        //  · qinav 一族：本机解析经常是错的，但 CF 边缘任一台都能按 SNI 找回源站（cf_edge.log 实测 8 中 7），
        //    所以内置边缘排在 DoH 前面——否则要先浪费 4 发在污染 IP 上。
        //  · xchina 一族：DoH 给的是它自己的 CF IP，比随机 anycast 更贴（随机 anycast 撞上别的 colo 有可能 403），
        //    所以 DoH 排前面、边缘垫后，**保持这一族今天的行为不变**。
        val seedsFirst = cfFrontedFamilies.any { hostname == it || hostname.endsWith(".$it") }
        val dohIps = viaDoh.map { it.hostAddress }
        val candidates = (
            if (seedsFirst) persistedEdges(hostname) + cfEdgeSeeds + dohIps + system.map { it.hostAddress }
            else persistedEdges(hostname) + dohIps + cfEdgeSeeds + system.map { it.hostAddress }
            ).filter { ipv4Re.matches(it) }.distinct()
        val verified = ArrayList<java.net.InetAddress>(2)
        val tried = ArrayList<String>(5)
        for (ip in candidates.take(5)) {
            tried += ip
            if (probeTls(hostname, ip)) verified += java.net.InetAddress.getByName(ip)
            if (verified.size >= 2) break
        }
        // 手机抑制应用日志，所以把「这一族到底怎么连上的」留成一句能上屏的话
        lastBoardDns = if (verified.isEmpty()) {
            "$hostname：${tried.size} 个候选逐个实测全不通（${tried.joinToString()}），已退回解析原样"
        } else {
            "$hostname：实测通过 ${verified.joinToString { it.hostAddress }}（从 ${tried.size} 个候选里选）"
        }
        if (verified.isNotEmpty()) {
            edgeCache[hostname] = verified to now
            putEdges(hostname, verified.map { it.hostAddress })
            return verified
        }
        val union = (viaDoh + system).distinct()
        return if (union.isEmpty()) systemLookup() else union
    }

    /**
     * 钉 IP + **真实 SNI** + **保留完整证书校验**的单发实测。
     *
     * `endpointIdentificationAlgorithm = "HTTPS"` 这一句是本函数的重点：Java 的裸 SSLSocket 默认
     * **不做主机名校验**，少设它「钉 IP」就偷偷降级成了「绕过证书」，中间人可以冒充任意站点。
     * 校验不过 → 这一发判废、换下一个候选；连不上与证书名不符都算判废，绝不因为连不上就坏交出去。
     */
    private fun probeTls(host: String, ip: String, timeoutMs: Int = 2500): Boolean {
        var raw: java.net.Socket? = null
        var ssl: javax.net.ssl.SSLSocket? = null
        return runCatching {
            raw = java.net.Socket()
            raw!!.connect(java.net.InetSocketAddress(ip, 443), timeoutMs)
            ssl = javax.net.ssl.SSLContext.getDefault().socketFactory
                .createSocket(raw!!, host, 443, false) as javax.net.ssl.SSLSocket
            ssl!!.sslParameters = ssl!!.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl!!.startHandshake()
            ssl!!.session.peerCertificates.isNotEmpty()
        }.getOrDefault(false).also {
            runCatching { ssl?.close() }
            runCatching { raw?.close() }
        }
    }

    /** 上次验通过的地址落进设置：冷启动第一发就省掉一次 DoH + 逐候选实测。 */
    private fun persistedEdges(hostname: String): List<String> =
        setting("x99.edge.$hostname", "").split(",").map { it.trim() }.filter { ipv4Re.matches(it) }

    private fun putEdges(hostname: String, ips: List<String>) {
        val key = "x99.edge.$hostname"
        val v = ips.joinToString(",")
        if (setting(key, "") != v) putSetting(key, v)
    }

    private val boardDns: okhttp3.Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            resolveBoardHost(hostname) { okhttp3.Dns.SYSTEM.lookup(hostname) }
    }

    /**
     * 把板块 DoH **链到**已有解析之后，供播放器的 OkHttp 客户端复用。
     *
     * 为什么不直接替换：播放器用的是 `HanimeClient.http`，它自带一套 Dns（官方源的边缘节点 + DoH）。
     * 直接 `.dns(板块那套)` 会把官方源的解析换掉 —— 那就是"干涉官方源"。
     * 这里只有名单内的板块域才前置 DoH 答案，其余域名原样走 fallback（含异常照抛）。
     * 上一轮 DoH 只挂在解析用的 `X99.http` 上，播放器仍走系统 DNS，
     * 于是 xchina 的清单被污染成 `0.0.0.0`（真机截图那条 `Failed to connect to /0.0.0.0:443`）。
     */
    fun boardDnsOver(fallback: okhttp3.Dns): okhttp3.Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            resolveBoardHost(hostname) { fallback.lookup(hostname) }
    }

    /**
     * 对 `Range` 撒谎的媒体地址——**按地址登记，不按域名猜**。
     *
     * 为什么不能一刀切、也不能按前缀切：同一个「肉视频线路族」里派发口与线路域的表现是不一样的（实测见下）。
     *  · 派发口 `rou888.net/api/hls/<cuid>`：不带 Range 回 200 + 真实全量，带 `Range: bytes=0-` 回
     *    **200 + 比真实全量更大的 Content-Length**（`2 584 796` vs 实收 `2 584 708`、`3 145 792` vs `3 145 700`、
     *    `1 807 915` vs `1 807 867`——每片多报 1 字节）。media3 信这个 CL，读到真尽头就抛
     *    `EOFException: source exhausted prematurely`（2026-10-02 真机回显那条）。
     *  · ganxiaban 的 `c3x.dycp444.com` 分片：206 的 `CL=414 690=实收`、200 的 `CL=1 650 594=实收`，**逐字节诚实**
     *    ⇒ 绝不给它剥 Range。qinav 的 206 上一轮实测同样诚实（118=118）。
     * 所以只有真的量到过撒谎的那几条才进这个集合，别的站点一行不受影响。
     */
    private val rangeHostileMedia =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun mediaRangeHostile(u: String): Boolean = rangeHostileMedia.contains(u)

    /**
     * 需要**自己看 302** 的场合（肉视频派发口选线路、ganxiaban 判闸门）。
     *
     * 这里的连接池是**故意设成不复用**的（`maxIdleConnections = 0`）：派发口那台 openresty 是
     * **按 TCP 连接分线**的——复用连接时同一个 cuid 连发四次拿到的还是同一条坏线（实测 12 条只通 6 条，
     * 且四次落点域名一模一样）；每次新建连接后落点开始轮换，同样 12 条通了 10 条
     * （对照实验 `临时\r41_gx_harness\RouDiag.kt`，两份输出留在 `临时\r41_rou/roudiag*.log`）。
     * "重取一条线路"这个动作只有在真的换了连接时才成立。
     */
    private val noRedirectHttp: OkHttpClient by lazy {
        http.newBuilder()
            .followRedirects(false).followSslRedirects(false)
            .connectionPool(OkHttpNoReusePool)
            .build()
    }

    /** 一次请求后即丢弃连接，让下一次重新经过负载均衡。 */
    private val OkHttpNoReusePool = okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS)

    // ---------- 设置（与 HanimeClient 共用 hanime_settings.properties） ----------

    private val settingsFile: File by lazy {
        val base = System.getenv("LOCALAPPDATA")
            ?: (System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local")
        File(File(base, "AvPlay").apply { mkdirs() }, "hanime_settings.properties")
    }

    fun setting(key: String, def: String = ""): String = runCatching {
        if (!settingsFile.exists()) return@runCatching def
        val p = Properties()
        settingsFile.inputStream().use { p.load(it) }
        p.getProperty(key) ?: def
    }.getOrDefault(def)

    fun putSetting(key: String, value: String) = runCatching {
        val p = Properties()
        if (settingsFile.exists()) settingsFile.inputStream().use { p.load(it) }
        p.setProperty(key, value)
        settingsFile.outputStream().use { p.store(it, "hanime settings") }
    }

    private val catalogFile: File by lazy { File(settingsFile.parentFile, "x99_catalog.json") }

    // ---------- 本地收藏（站点板块专用）与本地下载 ----------

    /**
     * 站点板块的一条本地收藏。
     *
     * 为什么必须单独存一份：官方源的「喜欢的影片」存在 hanime1.me 账号里（`/user/<uid>/likes`），
     * 而 MissAV / xchina / qinav / ganxiaban 这些板块**没有账号体系可写**，
     * 用户 2026-10-02 要求「其他板块的收藏单独在本页面显示，与 han1me 官方源分开」——
     * 所以这里存的是本机文件，不假装能同步到任何站点。
     */
    data class FavItem(
        val url: String,
        val title: String,
        val thumb: String,
        val board: String,
        val at: Long,
    )

    /** 一行一条，字段用 \t 分隔（标题里的制表符与换行写入时转义）。比 JSON 少一套转义代码，且能肉眼查。 */
    private val favFile: File by lazy { File(settingsFile.parentFile, "hplay_favorites.tsv") }

    @Volatile
    private var favsCache: List<FavItem>? = null

    fun favs(): List<FavItem> {
        favsCache?.let { return it }
        val out = ArrayList<FavItem>()
        runCatching {
            if (favFile.exists()) {
                favFile.readLines().forEach { line ->
                    val f = line.split('\t')
                    if (f.size >= 5 && f[0].startsWith("http")) {
                        out.add(
                            FavItem(
                                url = f[0],
                                title = f[1].replace("\\t", "\t").replace("\\n", "\n"),
                                thumb = f[2],
                                board = f[3],
                                at = f[4].toLongOrNull() ?: 0L,
                            ),
                        )
                    }
                }
            }
        }
        val sorted = out.sortedByDescending { it.at }
        favsCache = sorted
        return sorted
    }

    /**
     * **批量恢复收藏**（2026-11-25 第 309 轮）。
     *
     * 供 HplayBackup 用：备份里存的是 FavItem 列表，恢复时整批写回 TSV。
     *
     * 合并策略：**按 url 去重，保留已有的**（不覆盖用户当前收藏）。
     * 这样「恢复备份」不会把用户后来新增的收藏冲掉。
     *
     * @return 实际新增的条数
     */
    fun restoreFavs(items: List<FavItem>): Int {
        if (items.isEmpty()) return 0
        val existing = favs().toMutableList()
        val urls = existing.map { it.url }.toHashSet()
        var added = 0
        items.forEach { it2 ->
            if (it2.url.isNotBlank() && urls.add(it2.url)) {
                existing.add(it2)
                added++
            }
        }
        if (added == 0) return 0
        runCatching {
            val sb = StringBuilder()
            existing.sortedByDescending { it.at }.forEach { f ->
                sb.append(f.url).append('\t')
                    .append(f.title.replace("\t", "\\t").replace("\n", "\\n")).append('\t')
                    .append(f.thumb).append('\t')
                    .append(f.board).append('\t')
                    .append(f.at).append('\n')
            }
            favFile.writeText(sb.toString())
            favsCache = null
        }
        return added
    }

    fun isFav(url: String): Boolean = url.isNotEmpty() && favs().any { it.url == url }

    /** @return 操作后的状态（true = 现在已收藏）。 */
    fun toggleFav(v: HanimeVideo, board: String): Boolean {
        val now = !isFav(v.url)
        val next = if (now) {
            listOf(FavItem(v.url, v.title, v.thumb, board, System.currentTimeMillis())) + favs()
        } else {
            favs().filterNot { it.url == v.url }
        }
        runCatching {
            favFile.parentFile?.mkdirs()
            favFile.writeText(
                next.joinToString("\n") {
                    "${it.url}\t${it.title.replace("\t", "\\t").replace("\n", "\\n")}" +
                        "\t${it.thumb}\t${it.board}\t${it.at}"
                } + "\n",
            )
        }
        favsCache = next
        return now
    }

    /** 板块名去重（收藏页顶部的筛选 chip 用，图1 那种「全部 / 各板块」）。 */
    fun favBoards(): List<String> = favs().map { it.board }.filter { it.isNotBlank() }.distinct()

    /** 下载目录与设置文件同级：桌面是 %LOCALAPPDATA%/AvPlay，安卓侧由宿主注入到应用私有目录。 */
    val downloadDir: File by lazy { File(settingsFile.parentFile, "downloads").apply { mkdirs() } }

    /** 已完成的下载（按时间倒序）。只认我们自己写下的 .m3u8 / 媒体文件，不把目录里别的东西算进来。 */
    fun downloads(): List<Pair<String, Long>> = runCatching {
        downloadDir.listFiles()?.mapNotNull { f ->
            if (f.isDirectory) {
                val inner = f.listFiles()?.firstOrNull { it.name.endsWith(".m3u8") } ?: return@mapNotNull null
                inner.absolutePath to (inner.lastModified())
            } else if (f.name.contains(".m3u8") || Regex("""\.(mp4|mkv|webm|ts)$""", RegexOption.IGNORE_CASE).matches(f.name)) {
                f.absolutePath to f.lastModified()
            } else null
        }.orEmpty().sortedByDescending { it.second }
    }.getOrDefault(emptyList())

    /**
     * 把一条直链下到本地。**HLS 要连分片一起下**并把清单改写成相对路径，
     * 否则只存到一个几 KB 的 `index.m3u8` 文本，站点签名过期后就再也打不开——那不是"下载"，是"存了个链接"。
     * AES-128 的清单连 key 一起下（key 的 URI 也改写成相对路径），这样离线也能解。
     *
     * @return 落地后的清单/文件路径（可直接交给内嵌播放器播）。
     */
    fun downloadMedia(url: String, title: String, onProgress: (String) -> Unit = {}): File {
        val slug = title.ifBlank { "media" }.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(48)
        val text = runCatching { getHtml(url, referer = refererFor(url)) }.getOrDefault("")
        if (!text.contains("#EXTM3U")) {
            if (!Regex("""\.(mp4|mkv|webm|ts)""", RegexOption.IGNORE_CASE).matches(URI(url).rawPath.orEmpty()))
                throw IllegalStateException("这条地址既不是媒体清单也不是可直下的文件，不假装下载成功")
            val out = File(downloadDir, "${slug}_${System.currentTimeMillis()}.mp4")
            streamToFile(url, out, onProgress)
            return out
        }
        val dir = File(downloadDir, "${slug}_${System.currentTimeMillis()}").apply { mkdirs() }
        val lines = text.lines().toMutableList()
        for (i in lines.indices) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-KEY") && l.contains("URI=\"")) {
                val keyUrl = URI(url).resolve(l.substringAfter("URI=\"").substringBefore('"')).toString()
                val keyName = "key.bin"
                streamToFile(keyUrl, File(dir, keyName), onProgress)
                lines[i] = l.replaceFirst("URI=\"${l.substringAfter("URI=\"").substringBefore('"')}\"", "URI=\"$keyName\"")
            } else if (l.startsWith("#EXT-X-STREAM-INF")) {
                // 多档 master：挑最高的一档重入一次，下成单档
                val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                val variant = URI(url).resolve(next.trim()).toString()
                return downloadMedia(variant, title, onProgress)
            } else if (l.isNotBlank() && !l.startsWith("#")) {
                val segUrl = URI(url).resolve(l.trim()).toString()
                val name = "%05d.ts".format(i)
                streamToFile(segUrl, File(dir, name), onProgress)
                lines[i] = name
            }
        }
        val out = File(dir, "index.m3u8")
        out.writeText(lines.joinToString("\n"))
        return out
    }

    private fun streamToFile(url: String, out: File, onProgress: (String) -> Unit) {
        if (out.exists() && out.length() > 0) return
        val req = Request.Builder().url(url).header("User-Agent", UA)
            .header("Referer", refererFor(url)).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：$url")
            val tmp = File(out.absolutePath + ".part")
            resp.body?.byteStream()?.use { input ->
                tmp.outputStream().use { os -> input.copyTo(os) }
            }
            if (!tmp.renameTo(out)) throw IllegalStateException("落地失败：${out.name}")
            onProgress(out.name)
        }
    }

    // ---------- 目录（x99dh.cc 首页内嵌的 base64(URL 编码 JSON)） ----------

    @Volatile
    var sites: List<X99Site> = emptyList()
        private set

    @Volatile
    var catalogAt: Long = 0L
        private set

    @Volatile
    var catalogError: String = ""
        private set

    private fun decodeCatalog(html: String): List<X99Site> {
        val m = Regex("""const encodedData = '([^']+)'""").find(html)
            ?: throw IllegalStateException("x99dh 页面结构变化：未找到 encodedData")
        val raw = URLDecoder.decode(String(Base64.getDecoder().decode(m.groupValues[1]), Charsets.UTF_8), "UTF-8")
        val arr = Json.parseToJsonElement(raw).jsonArray
        val out = ArrayList<X99Site>(arr.size)
        fun fix(u: String) = Regex("^(https?://)/+").replace(u.trim(), "$1") // 目录里存在 "https:///host/..." 的坏写法
        for (el in arr) {
            val o = el.jsonObject
            fun str(k: String) = fix(o[k]?.jsonPrimitive?.content.orEmpty())
            val lines = (o["urls"]?.jsonArray ?: emptyList()).mapNotNull { le ->
                val lo = le.jsonObject
                val u = lo["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val t = lo["testUrl"]?.jsonPrimitive?.content ?: return@mapNotNull null
                X99Line(fix(u), fix(t))
            }
            val one = str("url")
            val site = X99Site(
                name = str("name"),
                desc = o["description"]?.jsonPrimitive?.content.orEmpty(),
                url = one,
                lines = if (lines.isNotEmpty()) lines else listOf(X99Line(one, "")),
            )
            if (site.name.isNotEmpty() && site.url.isNotEmpty()) out.add(site)
        }
        return out
    }

    /** 联网刷新目录；失败时回退到本地缓存。 */
    fun loadCatalog(force: Boolean = false): List<X99Site> {
        if (!force && sites.isNotEmpty()) return sites
        if (!force && catalogAt != 0L && System.currentTimeMillis() - catalogAt < 6 * 3600_000L) return sites
        try {
            val list = decodeCatalog(getHtmlLoose(CATALOG_URL))
            sites = list
            catalogAt = System.currentTimeMillis()
            catalogError = ""
            runCatching {
                catalogFile.writeText(
                    x99Json(list.map { s ->
                        mapOf(
                            "name" to s.name,
                            "description" to s.desc,
                            "url" to s.url,
                            "lines" to s.lines.map { listOf(it.url, it.testUrl) },
                        )
                    }),
                )
            }
            return list
        } catch (t: Throwable) {
            catalogError = t.message ?: t.toString()
            if (sites.isEmpty()) runCatching {
                val txt = catalogFile.readText()
                val arr = Json.parseToJsonElement(txt).jsonArray
                sites = arr.mapNotNull { el ->
                    val o = el.jsonObject
                    fun str(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
                    val lines = o["lines"]?.jsonArray?.map { le ->
                        val p = le.jsonArray
                        X99Line(p[0].jsonPrimitive.content, p.getOrNull(1)?.jsonPrimitive?.content.orEmpty())
                    }.orEmpty()
                    val nm = str("name")
                    if (nm.isEmpty()) null else X99Site(nm, str("description"), str("url"), lines)
                }
                catalogAt = System.currentTimeMillis()
            }
            return sites
        }
    }

    fun site(name: String): X99Site? = sites.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * x99dh 目录里没有、但用户点名要加的内置站点。放最前面方便找到；
     * **目录哪天收录了同名站就以目录为准**（内置项按名去重，不出现两个按钮、也不抢线路）。
     */
    private val builtinSites: List<X99Site> = listOf(
        X99Site(
            name = XCHINA_NAME,
            desc = "小黄书 xChina · 国产 / 日本AV（双排封面 · 原生详情 · 点画质即全屏起播）",
            url = XCHINA_HOME,
            lines = listOf(X99Line(XCHINA_HOME, ""), X99Line(XCHINA_FALLBACK, "")),
        ),
        X99Site(
            name = QINAV_NAME,
            desc = "Qinav · 16 个分类（原生播放 · 解析被污染，钉 Cloudflare 边缘节点直连）",
            url = QINAV_HOME,
            lines = listOf(X99Line(QINAV_HOME, "")),
        ),
        X99Site(
            name = GANXIE_NAME,
            desc = "ganxiaban · 48 栏目 / 22754 部（原生详情 · 需先过滑动闸门，本板块按实测轨迹自动代过）",
            url = GANXIE_HOME,
            lines = listOf(
                X99Line(GANXIE_HOME, ""), X99Line("https://www.mei78.com", ""),
                X99Line("https://www.avtt3581.com", ""),
            ),
        ),
        X99Site(
            name = CO36_NAME,
            desc = "麻豆网 36co · 18 家传媒分区（介绍页 + 网页播放 · 站内只有明文 80 可达，见下方实测说明）",
            url = CO36_HOME,
            lines = listOf(X99Line(CO36_HOME, "")),
        ),
        X99Site(
            name = ININAV_NAME,
            desc = "ininav · 导航站（本机网络按域名阻断，实测见工程记录：需不受阻断的网络才打得开）",
            url = ININAV_HOME,
            lines = listOf(X99Line(ININAV_HOME, "")),
        ),
    )

    /**
     * 36co（麻豆网）与 ininav 是用户 2026-10-02 第 43 轮点名的两家，实测结论直接决定了它们能做到哪一步：
     *
     *  · **36co**（`临时\r43_36co\`）：AppleCMS 形态。首页 / 播放页 / 搜索都取得到真实页面，
     *    但 `443` 带 `www.36co.cc` 这个 SNI 在**两个 Cloudflare 边缘 IP 上都被 RST**（0.24 s 内断），
     *    而明文 `80` 回 200 —— 所以站内地址只能用 `http://`（安卓壳已 `usesCleartextTraffic="true"`）。
     *    播放页的 `player_aaaa.url` 直接写在页面里（`https://t27.cdn2020.com/video/m3u8/<日期>/<哈希>/index.m3u8`），
     *    媒体域解析稳定、443 证书校验通过、回 404 是因为**我拿到的那部是 2026-05-12 的旧片**；
     *    在架新片的播放页本轮反复超时（一次 10 s 成功、其余 40~60 s 超时），
     *    所以**没过媒体门槛，不装原生**：卡片进介绍页、按钮给「网页播放」。
     *  · **ininav**：`临时\r43_edge\cf_edge.log` + `ininav_who_rsts.log` 的结论是
     *    「同一个 CF 边缘 IP，带 `www.qinav.com` 这个 SNI 回 200，带 `www.ininav.com` 就 0.2 s 内被 RST；
     *    不带 SNI 的连接能建起来并回 400；明文 80 带 `Host: www.ininav.com` 同样被 RST」
     *    —— 判据是**明文字符串里的域名**，不是 DNS、也不是 CF 拒绝。
     *    8.8.8.8 / 1.1.1.1 / 208.67.222.222 的 53 端口（UDP 与 TCP）在本机全不通，
     *    所以一个页面字节都没取到过：**没有结构可以解析，就没有任何原生能力可写**，
     *    这里只收进站按钮（选中即开内置浏览器），并在描述里如实写清是本机网络被域名阻断。
     */
    const val CO36_NAME = "36co"
    const val CO36_HOME = "http://www.36co.cc"

    const val ININAV_NAME = "ininav"
    const val ININAV_HOME = "https://www.ininav.com"

    /**
     * ganxiaban 族（干下吧 / mei78 / avtt3581 / semm1 / laona73 / avtt736）是**同一套库的多个前端**：
     * 三站的视频 id 完全同一命名空间（509924、713813 在 ganxiaban 与 mei78 首页同时出现），
     * 页面形态、闸门、媒体链一致，静态资源前缀只差一号（ganxiaban=MjYyNzM=26273，mei78=MjYyNzQ=26274）。
     * 上一轮把它当"只有入口页"的站，是因为误把 `tokccdn` 当成了跳转目标（正确拼写是 `tiokcdn`，
     * 那个域名根本未注册）；本轮按 `临时\r38_ganxiaban\REPORT.md` 与 `临时\r41_gx_walk.py` 的实测升为真板块。
     */
    const val GANXIE_NAME = "ganxiaban"
    const val GANXIE_HOME = "https://www.ganxiaban.com"

    /** 除本命板块（Hanime1）以外的站点，供「站点合集」板块使用（含内置站点）。 */
    fun otherSites(): List<X99Site> {
        val cat = sites.filterNot { it.name.contains("hanime", ignoreCase = true) }
        val extra = builtinSites.filterNot { b -> cat.any { it.name.equals(b.name, ignoreCase = true) } }
        return extra + cat
    }

    /** 站点名是否指向内置 xchina 板块（板块 UI 与解析路由都用它判断）。 */
    fun isXchinaSite(name: String): Boolean = name.equals(XCHINA_NAME, ignoreCase = true)

    /** 当前选中的线路（无记录时用第一条）。 */
    fun selectedLine(site: X99Site): X99Line {
        val saved = setting("x99.line.${site.name}", "")
        return site.lines.firstOrNull { it.url == saved } ?: site.lines.firstOrNull() ?: X99Line(site.url, "")
    }

    fun saveLine(site: X99Site, line: X99Line) = putSetting("x99.line.${site.name}", line.url)

    /** 线路测速：返回毫秒（与 x99dh 同口径：往返/2.5），-1 = 失败。 */
    fun testLine(line: X99Line): Long {
        if (line.testUrl.isBlank()) return -1
        val url = line.testUrl + (if (line.testUrl.contains("?")) "&" else "?") + "ts=" + System.currentTimeMillis()
        val t0 = System.currentTimeMillis()
        return try {
            val req = Request.Builder().url(url).header("User-Agent", UA).header("Cache-Control", "no-cache").build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty().trim()
                val el = System.currentTimeMillis() - t0
                if (body == "ok") (el / 2.5).toLong() else -1
            }
        } catch (t: Throwable) {
            -1
        }
    }

    // ---------- HTTP ----------

    /**
     * Cloudflare 拦下时给出的那几页壳。**它们是 HTTP 200 + 一段正文**，状态码判不出来：
     * 挑战页（`Just a moment`）与硬封页（`Sorry, you have been blocked`）都长这样。
     * 用来区分「站点确实没这个入口」和「这一发被拦了」，别让后者把设置写成永久的 `none`。
     */
    internal val cfBlockRe = Regex(
        """(Just a moment|cf-mitigated|Sorry, you have been blocked|Attention Required)""",
    )

    /**
     * 取页（站点正文）：带浏览器化请求头 + 一次自动重试。
     * 优选镜像站经 Cloudflare 中转，偶发 502/风控限流；重试一次通常即恢复。
     */
    fun getHtml(url: String, referer: String = ""): String {
        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                val b = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .header("Upgrade-Insecure-Requests", "1")
                val ref = referer.ifBlank { originOf(url) + "/" }
                b.header("Referer", ref)
                http.newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) throw IllegalStateException("HTTP ${resp.code}（空响应）")
                    return body
                }
            } catch (t: Throwable) {
                last = t
                if (attempt == 0) Thread.sleep(1200)
            }
        }
        throw IllegalStateException("${last?.message ?: "请求失败"}：$url（可换条线路重试）")
    }

    /** 容错取页：x99dh.cc 首页当前恒返回 500（响应体却是完整页面），故按内容判定而非状态码。 */
    private fun getHtmlLoose(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) throw IllegalStateException("HTTP ${resp.code}：$url（空响应）")
            return body
        }
    }

    // ---------- HTML 工具 ----------

    fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    /** 取站点根（scheme://host），线路 URL 自带路径（如 /cn、/enter）时用来拼子页面。 */
    fun originOf(url: String): String = runCatching {
        val u = URI(url)
        "${u.scheme}://${u.host}" + if (u.port > 0) ":${u.port}" else ""
    }.getOrDefault(url.trimEnd('/'))

    // ---------- hanime1 优选镜像（clone 前端：/watch?v= 卡片） ----------

    private val genreTabRe =
        Regex("""href="(https?://[^"]*/search\?genre=[^"]*)"[^>]*class="home-genre-tabs"[^>]*>\s*([^<]+?)\s*</a>""")

    private val rowTitleRe =
        Regex("""<a[^>]*class="horizontal-row-title"[^>]*href="([^"]*)"[^>]*>\s*<h3>\s*([^<]+)""")

    /** 镜像首页：顶部 genre tabs + **多分区**（每区一行，复刻官方源样式，内容取自优选镜像站）。 */
    fun mirrorHome(homeUrl: String): Pair<List<HanimeNavItem>, List<HanimeSection>> {
        val html = getHtml(homeUrl)
        val tabs = genreTabRe.findAll(html)
            .map { HanimeNavItem(unescape(it.groupValues[2]).trim(), it.groupValues[1].replace("&amp;", "&")) }
            .filter { it.label.isNotEmpty() }
            .distinctBy { it.label }
            .toMutableList()
        if (tabs.none { it.label.contains("新番") }) {
            tabs.add(HanimeNavItem("新番預告", originOf(homeUrl) + "/search?genre=新番預告"))
        }
        val marks = rowTitleRe.findAll(html).toList()
        val secs = ArrayList<HanimeSection>(marks.size)
        for ((i, m) in marks.withIndex()) {
            val title = unescape(m.groupValues[2]).trim().ifEmpty { "第 ${i + 1} 区" }
            val url = m.groupValues[1].replace("&amp;", "&")
            val start = m.range.last
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else html.length
            if (start >= end) continue
            val cards = HanimeClient.parseCards(html.substring(start, end))
            if (cards.isNotEmpty()) secs.add(HanimeSection(title, url, cards))
        }
        if (secs.isEmpty()) {
            val cards = HanimeClient.parseCards(html)
            if (cards.isNotEmpty()) secs.add(HanimeSection("最新上傳", homeUrl, cards))
        }
        return tabs to secs
    }

    /** 镜像列表/搜索页（/search?query=xx&page=N）卡片。 */
    fun mirrorListing(url: String): List<HanimeVideo> = HanimeClient.parseCards(getHtml(url))

    /** 镜像搜索 URL。 */
    fun mirrorSearchUrl(homeUrl: String, kw: String, page: Int): String {
        val u = originOf(homeUrl) + "/search?query=" + URLEncoder.encode(kw, "UTF-8")
        return if (page > 1) "$u&page=$page" else u
    }

    // ---------- MissAV（服务端渲染的 /cn 列表页） ----------

    /** MissAV 板块内的分类页签（路径来自列表页导航，实测可用）。 */
    val missavTabs: List<Pair<String, String>> = listOf(
        "最近更新" to "/cn/new",
        "中文字幕" to "/cn/chinese-subtitle",
        "新作上市" to "/cn/release",
        "無碼流出" to "/cn/uncensored-leak",
        "今日熱門" to "/cn/today-hot",
        "週榜" to "/cn/weekly-hot",
        "月榜" to "/cn/monthly-hot",
    )

    private val missavBlockMarker = """class="thumbnail group""""
    private val missavHrefRe = Regex("""<a href="(https?://[^"]+)"""")
    private val missavImgAltRe = Regex("""<img[^>]*?alt="([^"]*)"""")
    private val missavCoverRe = Regex("""data-src="(https?://[^"]*cover[^"]*)"""")
    private val missavDurRe = Regex("""<span[^>]*>\s*(\d{1,2}:\d{2}(?::\d{2})?)\s*</span>""")

    fun parseMissav(pageUrl: String, html: String): List<HanimeVideo> {
        val host = runCatching { URI(pageUrl).host.orEmpty() }.getOrDefault("")
        val marks = ArrayList<Int>()
        var i = html.indexOf(missavBlockMarker)
        while (i >= 0) {
            marks.add(i)
            i = html.indexOf(missavBlockMarker, i + missavBlockMarker.length)
        }
        val out = ArrayList<HanimeVideo>(marks.size)
        for ((idx, start) in marks.withIndex()) {
            val end = if (idx + 1 < marks.size) marks[idx + 1] else html.length
            val block = html.substring(start, end)
            val url = missavHrefRe.find(block)?.groupValues?.get(1) ?: continue
            // 只收本站详情页（站点会在列表里插「已迁移到 xxx.men」之类的外链公告卡）
            if (host.isNotEmpty() && runCatching { URI(url).host }.getOrNull() != host) continue
            val id = url.trimEnd('/').substringAfterLast('/')
            val title = missavImgAltRe.find(block)?.groupValues?.get(1)?.let(::unescape)
                ?.takeIf { it.isNotBlank() } ?: id
            val thumb = missavCoverRe.find(block)?.groupValues?.get(1).orEmpty()
            val dur = missavDurRe.find(block)?.groupValues?.get(1).orEmpty()
            out.add(HanimeVideo(id, url, title, thumb, dur, "", "", ""))
        }
        return out
    }

    /** MissAV 列表页：base = 线路 origin，path = missavTabs 里的路径。 */
    fun missavListing(base: String, path: String, page: Int): List<HanimeVideo> {
        val u = base.trimEnd('/') + path + (if (page > 1) "?page=$page" else "")
        return parseMissav(u, getHtml(u))
    }

    /** MissAV 搜索。 */
    fun missavSearch(base: String, kw: String, page: Int): List<HanimeVideo> {
        val u = base.trimEnd('/') + "/cn/search/" + URLEncoder.encode(kw, "UTF-8") +
            (if (page > 1) "?page=$page" else "")
        return parseMissav(u, getHtml(u))
    }

    // ---------- xchina（小黄书 xChina：内置站点，x99dh 目录里没有；SSR 页 + /hls/<id>/master.m3u8） ----------

    /**
     * 站点是自建程序（**不是 maccms**：`/api.php/provide/vod/?ac=list` 实测 404，页面里也没有任何 XHR 接口，
     * 全站服务端渲染）。以下每条都实测于 2026-09-30，取证样本在 `临时\xchina\`：
     *
     *  · 列表卡 `<div class="item video">`：详情 `/video/id-<12hex>.html`；封面写在
     *    `style="background-image:url('https://r2.xchina.download/cover/<id>.webp')"`；时长在 `.tags` 的
     *    `<i class="far fa-clock"></i>25:46`；演出者在 `.model-item`（**卡上没有播放量**，别编）。
     *  · 栏目 `/videos.html`（全部，实测 24 卡）、`/videos/cat-cn.html`（国产）、`/videos/cat-jav.html`
     *    （日本AV，各 11 卡）；翻页是**路径式**：`/videos/page-2.html`、`/videos/cat-cn/page-2.html`。
     *  · 详情页：`<h1 class="hero-title-item">` 是标题，`og:duration` 给秒数，JSON-LD `VideoObject` 给
     *    uploadDate / actor[].name / thumbnailUrl；分类链在 `/videos/cat-*.html`。**详情页不带推荐位**
     *    （实测只有一个自身面包屑链接），所以「相关影片」取同栏目列表剔除自己。
     *  · 播放地址直接写在页面里：`src: '/hls/<id>/master.m3u8'`。裸请求、Chrome UA、ExoPlayer UA 都 200，
     *    **不需要 Referer**。master 实测只有单一级别 `RESOLUTION=1280x720`，变体 URI 自带 `?n=<ts>.<hash>`
     *    签名；变体内 `#EXT-X-KEY:METHOD=AES-128,URI="/hls/key/<id>.<ts>.<hash>"`（站内取 16 字节 key），
     *    分片在 `https://cdn.xchina.download/v/<id>/seg_NNN.ts?md5=&expires=`，响应
     *    `access-control-allow-origin: *` ⇒ ExoPlayer 交出去就能自己解封装、取 key、拉分片。
     *  · **Cloudflare 按请求频率翻脸**：连发快取会拿 403「Just a moment…」（响应头 `cf-mitigated: challenge`），
     *    实测隔 4 秒一发即恢复；持续高频会升级为硬封（"Sorry, you have been blocked"，本端探测就被封过一次）。
     *    所以：一次列表只发一发，列表与详情都进进程内缓存，绝不做批量预热。
     */
    const val XCHINA_NAME = "xchina"
    /**
     * 主源 2026-10-02 按用户要求换成 `https://xchina.co/`；`xchina001.ink` 留作第二条线路。
     * **诚实标注**：本机对 xchina.co 的三个 Cloudflare IP × 两种 UA 全部被 WAF 硬拦
     * （5857 B 的 "Sorry, you have been blocked / You are unable to access xchina.co"，
     * 不是 JS 挑战），所以 xchina.co 的页面结构**没能从这台机器验证过**——
     * 解析器沿用 xchina001.ink 那一套（同族站），装到手机上若结构不同就会解析不出卡片，
     * 那时在「优选访问」里切到 xchina001.ink 这条线路即可（它实测可用，见下）。
     */
    const val XCHINA_HOME = "https://xchina.co"

    /** 旧域：实测（2026-10-02）系统 DNS 与阿里 DNS 都给出污染答案（阿里直接回 `0.0.0.0`），
     *  而 DNSPod 给 Cloudflare 172.67.205.25 时 `GET /videos/1.html` 回 200、11 张卡——
     *  所以它必须走 DoH 才连得上，这也正是真机那条 `xchina001.ink/0.0.0.0:443` 的根因。 */
    const val XCHINA_FALLBACK = "https://xchina001.ink"

    private val XCHINA_HOSTS = setOf("xchina.co", "xchina001.ink")

    /**
     * 板块页签。**路径必须是 canonical 形式**（实测 `临时\r34_xchina\`）：
     *  · `/videos.html` 是**板块首页混合流**（24 卡＝国产 12＋日本AV 12），不是「全部影片」第 1 页；
     *    拿它当第 1 页再往下翻会拼出跨序列的重复卡片（实测与第 2 页重叠 10 个 id）。全部影片从 `/videos/1.html` 起。
     *  · `page-N.html` 只是别名：canonical 与 title 是对的，但**它的分页器是坏的**——`pager-num` 链接 0 条、
     *    `next` 被拼成 `/videos/cat-cn/page-2/2.html`、`current` 恒为 1。所以翻页一律自己拼 `/videos/<seg>/N.html`。
     */
    val xchinaTabs: List<Pair<String, String>> = listOf(
        "全部影片" to "/videos/1.html",
        "国产" to "/videos/cat-cn.html",
        "日本AV" to "/videos/cat-jav.html",
    )

    /** 分页器里 `pager-num` 链接的最大页号；0 = 该页没有分页器（板块首页/别名页），即"未知"。 */
    private val xchinaPagerRe = Regex("""<a href="/videos/(?:[a-z0-9-]+/)?(\d+)\.html" class="pager-num""")
    private val xchinaPagerMax = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** canonical 翻页路径：`/videos/1.html`+3 → `/videos/3.html`；`/videos/cat-cn.html`+2 → `/videos/cat-cn/2.html`。 */
    fun xchinaPagePath(path: String, page: Int): String {
        if (page <= 1) return path
        val stem = path.removeSuffix(".html").replace(Regex("""/\d+$"""), "")
        return "$stem/$page.html"
    }

    /** 该页签已知最大页（来自上一次解析到的分页器）；0 = 还不知道。 */
    fun xchinaMaxPage(path: String): Int = xchinaPagerMax[path] ?: 0

    /** 是不是 xchina 的页面/资源地址（含多语言镜像子域；镜像侧只有 en 实测证书有效）。 */
    fun isXchinaUrl(u: String): Boolean {
        val h = runCatching { URI(u).host.orEmpty() }.getOrDefault("").lowercase()
        return XCHINA_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    private const val XCHINA_CARD_MARK = "<div class=\"item video\">"
    private val xchinaHrefRe = Regex("""href="/video/id-([a-z0-9]+)\.html"""")
    private val xchinaTitleRe = Regex("""title="([^"]*)"""")
    private val xchinaTitleTextRe = Regex("""class="title"><a[^>]*>([^<]*)<""")
    private val xchinaBgImgRe = Regex("""background-image:url\('([^']+)'\)""")
    private val xchinaImgRe = Regex("""<img[^>]*?src="([^"]+)"""")
    private val xchinaDurRe = Regex("""fa-clock"></i>(\d{1,2}:\d{2}(?::\d{2})?)""")
    private val xchinaModelRe = Regex("""class="model-item">([^<]*)<""")

    /** 列表卡解析（`base` 用来拼绝对详情链；卡片里的 href 是站内相对路径）。 */
    fun parseXchina(base: String, html: String): List<HanimeVideo> {
        val origin = originOf(base)
        val marks = ArrayList<Int>()
        var i = html.indexOf(XCHINA_CARD_MARK)
        while (i >= 0) {
            marks.add(i)
            i = html.indexOf(XCHINA_CARD_MARK, i + XCHINA_CARD_MARK.length)
        }
        val out = ArrayList<HanimeVideo>(marks.size)
        val seen = HashSet<String>()
        for ((idx, start) in marks.withIndex()) {
            val end = if (idx + 1 < marks.size) marks[idx + 1] else minOf(html.length, start + 3000)
            val block = html.substring(start, end)
            val id = xchinaHrefRe.find(block)?.groupValues?.get(1) ?: continue
            if (!seen.add(id)) continue
            val title = unescape(xchinaTitleRe.find(block)?.groupValues?.get(1).orEmpty())
                .ifBlank { unescape(xchinaTitleTextRe.find(block)?.groupValues?.get(1).orEmpty()) }
                .ifBlank { id }
            val cover = (xchinaBgImgRe.find(block)?.groupValues?.get(1)
                ?: xchinaImgRe.find(block)?.groupValues?.get(1)).orEmpty()
            out.add(
                HanimeVideo(
                    id = id,
                    url = "$origin/video/id-$id.html",
                    title = title,
                    thumb = cover,
                    duration = xchinaDurRe.find(block)?.groupValues?.get(1).orEmpty(),
                    likes = "",
                    views = "",
                    brand = unescape(xchinaModelRe.find(block)?.groupValues?.get(1).orEmpty()),
                ),
            )
        }
        return out
    }

    /**
     * 进程内缓存（**这是防 Cloudflare 的，不是性能优化**）：同一页签反复进出、翻回上一页、
     * 点开详情再返回，都不该再打站点一发请求——实测连续快取会翻出 403 挑战页，持续高频会被硬封。
     * TTL 到点就重取，不让界面整天停在旧数据上。
     */
    private const val XCHINA_TTL_MS = 10 * 60_000L
    private val xchinaListingCache = java.util.concurrent.ConcurrentHashMap<String, List<HanimeVideo>>()
    private val xchinaListingAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val xchinaDetailCache = java.util.concurrent.ConcurrentHashMap<String, HanimeDetail>()
    private val xchinaDetailAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun <V> fresh(store: java.util.concurrent.ConcurrentHashMap<String, V>,
                          at: java.util.concurrent.ConcurrentHashMap<String, Long>, key: String): V? {
        val t = at[key] ?: return null
        if (System.currentTimeMillis() - t > XCHINA_TTL_MS) return null
        return store[key]
    }

    private fun <V> remember(store: java.util.concurrent.ConcurrentHashMap<String, V>,
                             at: java.util.concurrent.ConcurrentHashMap<String, Long>,
                             key: String, value: V) {
        if (store.size > 60) { store.clear(); at.clear() }   // 只有超限才整表清，正常用不到
        store[key] = value
        at[key] = System.currentTimeMillis()
    }

    /** 列表：page>1 走 canonical 路径式翻页，一次一发；顺带把分页器的最大页记下来。 */
    fun xchinaListing(base: String, path: String, page: Int): List<HanimeVideo> {
        val p = xchinaPagePath(path, page)
        val u = originOf(base) + p
        fresh(xchinaListingCache, xchinaListingAt, u)?.let { return it }
        val html = getHtml(u, referer = originOf(base) + "/videos.html")
        xchinaPagerRe.findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()?.takeIf { it > 0 }?.let { xchinaPagerMax[path] = it }
        val cards = parseXchina(u, html)
        // 只缓存「解析到东西」的页：空结果多半是站点改版或被 CF 拦了，缓存住会让用户再也刷不出卡片
        if (cards.isNotEmpty()) remember(xchinaListingCache, xchinaListingAt, u, cards)
        return cards
    }

    /**
     * 搜索：走**视频域** `/videos/keyword-<enc>.html`（站点自己的搜索 JS 原文：选了 video 模式就跳这个地址，
     * 而 `/search.html?keyword=` 是"全部类型"，会把套图/模特/小说混进来）。
     * 关键词规则照抄站点：空格先换成 `+` 再 encodeURIComponent；**少于 2 字站点直接报错不发请求**，
     * 所以这里也直接抛，让界面如实显示原因，而不是给一个空列表让用户以为片库里没有。
     */
    fun xchinaSearch(base: String, kw: String): List<HanimeVideo> {
        val k = kw.trim()
        if (k.length < 2) throw IllegalStateException("关键词至少 2 个字（站点自身限制）")
        val enc = URLEncoder.encode(k.replace(" ", "+"), "UTF-8")
        val u = originOf(base) + "/videos/keyword-$enc.html"
        val cards = parseXchina(u, getHtml(u, referer = originOf(base) + "/videos.html"))
        if (cards.isNotEmpty()) remember(xchinaListingCache, xchinaListingAt, u, cards)
        return cards
    }

    private val xchinaSrcRe = Regex("""src:\s*'(/hls/[^']+)'\s*,""")
    private val xchinaPosterRe = Regex("""poster:\s*'([^']+)'\s*,""")
    private val xchinaOgDurRe = Regex("""property="og:duration" content="(\d+)"""")
    private val xchinaH1Re = Regex("""<h1 class="hero-title-item">([^<]*)</h1>""")
    private val xchinaCatRe = Regex("""href="(/videos/cat-[a-z]+\.html)">([^<]+)</a>""")
    private val xchinaPersonPathRe = Regex("""href="(/videos/person-\d+\.html)"""")
    private val xchinaSeriesRe = Regex("""href="(/videos/series-[a-z0-9]+\.html)"""")
    private val xchinaUploadRe = Regex(""""uploadDate":"([^"]+)"""")
    private val xchinaPersonRe = Regex(""""name":"([^"]+)","gender"""")
    private val xchinaRenditionRe = Regex("""RESOLUTION=\d+x(\d+)[^\n]*\n([^\n]+)""")

    /**
     * master.m3u8 → 「清晰度 → 直链」。实测该站只出一级 720，但按解析写：多级就逐个给按钮，
     * 解析不出变体时退回 master 本身（标签给 0，界面渲染成「播放」而不是「0P」），
     * ExoPlayer 自己会再读一级清单——**不能因为解析失败就没有播放项**。
     */
    fun xchinaQualities(masterUrl: String): List<Pair<Int, String>> {
        val text = runCatching {
            getHtml(masterUrl, referer = originOf(masterUrl) + "/videos.html")
        }.getOrDefault("")
        val base = masterUrl.substringBeforeLast('/') + "/"
        val out = ArrayList<Pair<Int, String>>()
        for (m in xchinaRenditionRe.findAll(text)) {
            val h = m.groupValues[1].toIntOrNull() ?: continue
            val uri = m.groupValues[2].trim()
            if (uri.isEmpty() || uri.startsWith("#")) continue
            val abs = runCatching { URI(base).resolve(uri).toString() }.getOrDefault(base + uri)
            out.add(h to abs)
        }
        // xchina 的清单是自己取的（不走 getPlaylist/renditionsOf），认到 #EXTM3U 就得同样登记，
        // 否则它哪天换成无扩展名的变体就会重演肉视频那个错。
        if (text.contains("#EXTM3U")) {
            hlsVerified.add(masterUrl)
            out.forEach { hlsVerified.add(it.second) }
        }
        if (out.isEmpty()) return listOf(0 to masterUrl)
        return out.distinctBy { it.first }.sortedByDescending { it.first }
    }

    /**
     * 详情页 → 官方 UI 那套数据（标题 / 时长 / 清晰度 / 标签 / 相关影片）。
     * 失败时不写缓存（下次可重试），成功才缓存 —— 缓存一半的详情会让用户反复看到同一个错误。
     */
    fun xchinaDetail(v: HanimeVideo): HanimeDetail {
        fresh(xchinaDetailCache, xchinaDetailAt, v.url)?.let { return it }
        val html = getHtml(v.url, referer = originOf(v.url) + "/videos.html")
        val origin = originOf(v.url)
        val id = Regex("""/video/id-([a-z0-9]+)\.html""").find(v.url)?.groupValues?.get(1) ?: v.id
        val rel = xchinaSrcRe.find(html)?.groupValues?.get(1)
        val masterUrl = if (rel.isNullOrBlank()) "" else origin + rel
        val secs = xchinaOgDurRe.find(html)?.groupValues?.get(1)?.toIntOrNull()
        val dur = v.duration.ifBlank { if (secs != null) "%d:%02d".format(secs / 60, secs % 60) else "" }
        val title = unescape(xchinaH1Re.find(html)?.groupValues?.get(1).orEmpty()).ifBlank { v.title }
        val poster = xchinaPosterRe.find(html)?.groupValues?.get(1).orEmpty().ifBlank { v.thumb }
        val cat = xchinaCatRe.find(html)
        val actors = xchinaPersonRe.findAll(html).map { unescape(it.groupValues[1]) }.distinct().take(4).toList()
        val date = xchinaUploadRe.find(html)?.groupValues?.get(1)?.substringBefore('T').orEmpty()
        val tags = buildList {
            cat?.groupValues?.get(2)?.let { add(unescape(it)) }
            addAll(actors)
        }
        // 简介：该站详情页没有正文梗概（实测只有面包屑与推广位），所以只写实测到的事实项，
        // 不编内容；推广位（cpc-text）与本棒的广告裁决同类，一律不进 App UI。
        val desc = listOfNotNull(
            if (date.isNotBlank()) "上传：$date" else null,
            if (cat != null) "分类：${unescape(cat.groupValues[2])}" else null,
            if (actors.isNotEmpty()) "演出：${actors.joinToString("、")}" else null,
        ).joinToString(" · ")
        val sources = if (masterUrl.isEmpty()) emptyList() else xchinaQualities(masterUrl)
        // 推荐位：详情页**确实没有**推荐区（独立复核：全页 /video/id- 去重后只有它自己），
        // 所以取站点在详情页里自己给出的聚合入口，按相关性排序，取第一个能出卡片的：
        //   同演员 /videos/person-N.html → 同系列 /videos/series-<id>.html → 同栏目 cat-*（兜底）。
        // 每次最多 3 发，且都走列表缓存（同一演员/栏目的第二次打开是 0 发）。
        val relPaths = listOfNotNull(
            xchinaPersonPathRe.find(html)?.groupValues?.get(1),
            xchinaSeriesRe.find(html)?.groupValues?.get(1),
            cat?.groupValues?.get(1),
        )
        var related: List<HanimeVideo> = emptyList()
        for (rp in relPaths) {
            val got = runCatching { xchinaListing(origin, rp, 1).filterNot { it.id == id } }.getOrDefault(emptyList())
            if (got.isNotEmpty()) {
                related = got
                break
            }
        }
        val d = HanimeDetail(
            video = v.copy(id = id, url = v.url, title = title, thumb = poster, duration = dur),
            description = desc,
            tags = tags,
            sources = sources,
            related = related,
            isFav = false,
            isSaved = false,
            saveListId = "",
        )
        // 只缓存「拿到播放源」的详情：源解析失败时缓存住，用户重试也永远看到同一份坏数据
        if (masterUrl.isNotEmpty() && sources.isNotEmpty()) {
            remember(xchinaDetailCache, xchinaDetailAt, v.url, d)
        }
        return d
    }

    // ---------- MissAV 原生详情（packed 脚本解包 → /jmpres/ 直链，实测取证在 临时\r34_play\） ----------

    /**
     * 最近一次打开的详情页 URL。**媒体请求的 Referer 用它**：MissAV 的 /jmpres/ 链没有同域 Referer 时
     * 不是报错，而是 **HTTP 200 + 一张诱饵 JPEG**（实测恒定 23 111 B、md5 b1fc24c2…、302 到 shimg.piccdn1.cfd），
     * 状态码判不出来，只能靠 Referer 正确 + 内容嗅探。
     */
    @Volatile
    var lastDetailUrl: String = ""
        private set

    /**
     * 本次播放是不是「原生板块」的播放（xchina / MissAV），由详情面板在把播址交给播放器的那一刻写入
     * （见 `HanimeApp` 画质按钮的 onClick），安卓播放器在 `prepare` 里读它来决定
     * 「自动横屏全屏」与「补同域 Referer」这两件事。桌面端不读，写它不改变桌面行为。
     * 每次起播都会重写，所以不存在残留；官方源走同一条语句，值恒为 false。
     */
    @Volatile
    var nativePlay: Boolean = false

    /**
     * MissAV 的视频页形状：`/cn/<作品号>`，号里必带数字（实测 `dldss-550`、`fc2-ppv-4968519`、`erk-113`）。
     * **按 URL 形状而不是按线路域名判定**：线路域是轮换的（`missav06.com` 只是其中一条），
     * 作品号形状才是站点自身的规则。`/cn/new`、`/cn/genres`、`/cn/actresses` 没有数字，
     * 多一级的路径也不是作品页，都自然落在门外。判错的代价是「先试原生再回落网页版」，不是坏链接。
     */
    // ---------- qinav（2026-10-01 新增板块；系统 DNS 被污染，靠上面的 DoH 直连） ----------

    const val QINAV_NAME = "qinav"
    const val QINAV_HOME = "https://www.qinav.com"

    fun isQinavSite(name: String): Boolean = name.equals(QINAV_NAME, ignoreCase = true)

    private val qinavVideoPathRe = Regex("""^/video/\d+\.html$""")

    fun isQinavUrl(u: String): Boolean {
        val h = runCatching { URI(u).host.orEmpty().lowercase() }.getOrDefault("")
        return (h == "qinav.com" || h.endsWith(".qinav.com")) && qinavVideoPathRe.matches(pathOnly(u))
    }

    /**
     * 站首页导航里的 16 个分类，**名称与路径都照抄原文**（`/site/<一级>/<二级>.html`）。
     * 这是「子选项」弹窗的数据源：用户要求「对网站内所有子选项制作一个弹窗以便选择」。
     */
    val qinavCats: List<Pair<String, String>> = listOf(
        "亚洲情色" to "/site/5/1.html", "国产主播" to "/site/5/2.html", "国产自拍" to "/site/5/3.html",
        "无码专区" to "/site/5/4.html", "欧美性爱" to "/site/5/5.html", "熟女人妻" to "/site/5/6.html",
        "强奸乱伦" to "/site/5/7.html", "巨乳美乳" to "/site/5/8.html", "中文字幕" to "/site/5/9.html",
        "制服诱惑" to "/site/5/10.html", "女同性恋" to "/site/5/11.html", "卡通动画" to "/site/5/12.html",
        "视频伦理" to "/site/5/13.html", "少女萝莉" to "/site/5/14.html", "重口色情" to "/site/5/15.html",
        "福利姬" to "/site/5/33.html",
    )

    /** 卡片：`<a href="/video/938547.html"> … <img src="/static/loading.gif" img="真图"> … <li class="title">片名</li> </a>` */
    private val qinavCardRe = Regex("""<a[^>]{0,160}?href="/video/(\d+)\.html"[^>]{0,160}?>([\s\S]{0,1400}?)</a>""")
    private val qinavImgRe = Regex("""\bimg="(https?://[^"]+)""")
    private val qinavTitleRe = Regex("""<li class="title">([\s\S]{0,300}?)</li>""")
    private val qinavTimeRe = Regex("""<span class="time">([^<]{1,20})</span>""")
    private val qinavViewRe = Regex("""icon-eye-open"></i>\s*([^<]{1,16})<""")
    private val qinavEmbedRe = Regex("""<iframe[^>]{0,240}?src="(/embed/\d+\.html)"""")
    /** 播放器套了一层 `https://www.155jx.com/?url=<真 m3u8>`；解码后才是给 ExoPlayer 的那条。 */
    private val qinavWrappedRe = Regex("""[?&]url=(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
    private val qinavBareM3u8Re = Regex("""(https?://[^"'\s<>]+\.m3u8)""")

    fun parseQinav(pageUrl: String, html: String): List<HanimeVideo> {
        val origin = originOf(pageUrl)
        val out = ArrayList<HanimeVideo>()
        val seen = HashSet<String>()
        for (m in qinavCardRe.findAll(html)) {
            val id = m.groupValues[1]
            val block = m.groupValues[2]
            val url = "$origin/video/$id.html"
            if (!seen.add(url)) continue
            val title = unescape(qinavTitleRe.find(block)?.groupValues?.get(1)
                ?.let { genTagRe.replace(it, " ") }.orEmpty())
                .replace(Regex("""\s+"""), " ").trim().take(160)
            if (isJunkTitle(title)) continue
            out.add(
                HanimeVideo(
                    id = id, url = url, title = title,
                    thumb = qinavImgRe.find(block)?.groupValues?.get(1).orEmpty(),
                    duration = qinavTimeRe.find(block)?.groupValues?.get(1)?.trim().orEmpty(),
                    likes = "", views = qinavViewRe.find(block)?.groupValues?.get(1)?.trim().orEmpty(),
                    brand = "",
                ),
            )
        }
        return out
    }

    /** qinav 的翻页形式实测是「把 `-N` 插在 `.html` 前」：`/new.html` → `/new-2.html`（两页卡片零重叠）。 */
    fun qinavPagePath(path: String, page: Int): String =
        if (page <= 1) path else path.removeSuffix(".html") + "-$page.html"

    /** 分页器给出的总页数（`尾页 → /new-97.html` 实测）；键 = 栏目路径，0 = 未知。 */
    private val qinavPagerMax = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun qinavMaxPage(path: String): Int = qinavPagerMax[path.removeSuffix(".html")] ?: 0

    fun qinavListing(base: String, path: String, page: Int): List<HanimeVideo> {
        val stem = path.removeSuffix(".html")
        val u = originOf(base) + qinavPagePath(path, page)
        val html = getHtml(u, referer = originOf(base) + "/")
        // 分页器只在第 1 页读（翻页页面上「尾页」仍指向同一个总数，读一次就够，少一次解析歧义）
        if (page == 1) Regex("""href="$stem-(\d+)\.html"[^>]*>\s*尾页""").find(html)
            ?.groupValues?.get(1)?.toIntOrNull()?.let { qinavPagerMax[stem] = it }
        val cards = parseQinav(u, html)
        // 重复页由 X99Board 的「加载更多」按 URL 去重兜住（去重后为空即判到末页），
        // 这里不再写 firstPageCache —— 那份自校验基线只有 genericListing 会读。
        return cards
    }

    /**
     * qinav 原生播址：详情页 iframe `/embed/<id>.html` → 里面 `155jx.com/?url=<真 m3u8>` → master → 变体。
     * 证据（2026-10-01 实测）：master 118 B 单变体 `RESOLUTION=1280x720`，变体清单 120 524 B / **2266 段真 TS**
     * / 6794.5 s，`#EXT-X-KEY:METHOD=NONE`，首片 200 + `video/mp2t` + 同步字节 0x47，**带不带 Referer 都通**。
     */
    fun qinavDetail(v: HanimeVideo): HanimeDetail? {
        val html = getHtml(v.url, referer = originOf(v.url) + "/")
        val embed = qinavEmbedRe.find(html)?.groupValues?.get(1) ?: return null
        val embedHtml = getHtml(originOf(v.url) + embed, referer = v.url)
        val wrapped = qinavWrappedRe.find(embedHtml)?.groupValues?.get(1).orEmpty()
        val master = runCatching { java.net.URLDecoder.decode(wrapped, "UTF-8") }
            .getOrDefault(wrapped)
            .ifBlank { qinavBareM3u8Re.find(embedHtml)?.groupValues?.get(1).orEmpty() }
        if (!master.startsWith("http")) return null
        val list = getPlaylist(master, v.url)
        if (list.isEmpty()) return null
        val sources = renditionsOf(master, list).ifEmpty { listOf(qualityFromUrl(master) to master) }
            .filter { it.second.startsWith("http") }
        if (sources.isEmpty()) return null
        openNativeDetail(v.url, listOf(master) + sources.map { it.second })
        return htmlDetail(v, html, sources)
    }

    // ---------- 36co（麻豆网，maccms 形态；2026-10-02 第 43 轮点名新增） ----------

    /**
     * 18 个分区的**名称与路径逐字取自站内导航**（取证样本 `临时\r43_36co\home.html`，2026-10-02）。
     * 这张表是「子选项」弹窗的数据源，零请求直接列（与 qinav / ganxiaban 同一做法）。
     */
    val co36Cats: List<Pair<String, String>> = listOf(
        "麻豆视频" to "/index.php/vod/type/id/1.html", "91制片厂" to "/index.php/vod/type/id/2.html",
        "天美传媒" to "/index.php/vod/type/id/3.html", "蜜桃传媒" to "/index.php/vod/type/id/4.html",
        "皇家華人" to "/index.php/vod/type/id/5.html", "星空传媒" to "/index.php/vod/type/id/6.html",
        "精东影业" to "/index.php/vod/type/id/7.html", "乐播传媒" to "/index.php/vod/type/id/8.html",
        "性视界传媒" to "/index.php/vod/type/id/9.html", "乌鸦传媒" to "/index.php/vod/type/id/10.html",
        "兔子先生" to "/index.php/vod/type/id/11.html", "杏吧原创" to "/index.php/vod/type/id/12.html",
        "糖心Vlog" to "/index.php/vod/type/id/13.html", "萝莉社" to "/index.php/vod/type/id/14.html",
        "玩偶姐姐" to "/index.php/vod/type/id/15.html", "mini传媒" to "/index.php/vod/type/id/16.html",
        "PsychoPorn" to "/index.php/vod/type/id/17.html", "其他传媒" to "/index.php/vod/type/id/18.html",
    )

    /** maccms 分类页的翻页是把 `/page/N` 插在 `.html` 之前——这个形式取自站内分页器自己写出的链接。 */
    fun co36PagePath(path: String, page: Int): String =
        if (page <= 1) path else path.removeSuffix(".html") + "/page/$page.html"

    /**
     * 封面主机改写**试过并且否掉了**（第 44 轮）：站内把封面写成 `https://23.225.11.173:8443/...`，
     * 看着像是该换成同一套库的域名形态 `https://t27.cdn2020.com/...`。实测四种写法
     * （无扩展名、补 `.jpg`、日期用 `/` 分段、以及 `video/m3u8/<日期>/<哈希>/vod.jpg`）**全部 404**
     * （`临时\r44_gate_final.log`），也就是说改完之后没有任何一条能证明比原样更好。
     * 只能变坏、验不出变好的改动不留：这里保持站内原样，取不到时由卡面把原因画出来。
     * 媒体侧不同——清单与首片是**亲眼取到 200 + 0x47** 的，所以播址照原生走。
     */

    /** 分页器读到的最大页（键 = 栏目路径，0 = 未知）。 */
    private val co36PagerMax = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun co36MaxPage(path: String): Int = co36PagerMax[path.removeSuffix(".html")] ?: 0

    /**
     * 36co 列表：一次调用只发一发（站内响应本身在 0.5~40 s 之间抖，多打只会更糟）。
     * 没选栏目时取首页——首页的「正在热播 / 最新影片」两区实测有 72 张作品卡。
     * 卡片解析**复用 [parseGeneric]**：不另抄一套正则，否则就成了「界面跑一套、验收器跑另一套」。
     */
    /**
     * 36co 的作品页形状：`/index.php/vod/play/id/<数字>/sid/<数字>/nid/<数字>.html`。
     * 只锚路径、不比查询串（maccms 的 `?m=vod-play` 之类的参数各站写法不一）。
     */
    fun isCo36Url(u: String): Boolean =
        rootDomain(u) == "36co" && Regex("""^/index\.php/vod/play/id/\d+/sid/\d+/nid/\d+\.html""")
            .containsMatchIn(pathOnly(u))

    fun co36Listing(base: String, tabPath: String?, page: Int): List<HanimeVideo> {
        val origin = originOf(base)
        val path = tabPath?.takeIf { it.isNotBlank() } ?: "/"
        val u = origin + co36PagePath(path, page)
        val html = getHtml(u, referer = "$origin/")
        if (page == 1 && path != "/") {
            Regex("""/index\.php/vod/type/id/\d+/page/(\d+)\.html""").findAll(html)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()?.let { co36PagerMax[path] = it }
        }
        // 封面的域名改写试过又被撤掉（理由见 co36ThumbNote）：改写后取不到图片，
        // 而站内原样写的主机本来就是这个形态，改错了只会让卡面更糟。
        return parseGeneric(u, html)
    }


    /** maccms 把播址写在播放页的 `player_aaaa = { … "url": "…/index.m3u8" … }` 里，没有 iframe 也没有混淆。 */
    private val co36PlayerRe = Regex("""player_aaaa\s*=\s*(\{[\s\S]*?\})\s*</script>""")

    /**
     * 36co 的原生播址。
     *
     * **每一部都要现场验清单**才给画质按钮：`getPlaylist` 只认以 `#EXTM3U` 开头、
     * 且（有 `#EXT-X-STREAM-INF` 或分片数 >2）的正文，并挡 `tip.ts` 那种「200 + mpegurl 也算诱饵」的假清单；
     * 验不过直接返回 null，界面回落「网页播放」。这一族的页面形状与黄色仓库族相同
     * （`maccmsPlayRe`），所以那边已按域名把 36co 排除掉，两条路不会互相抢。
     */
    /**
     * 从播放页正文里取出 maccms 写的播址。**独立成一个函数是为了能被离线验收器直调**：
     * 拿真页面判它取出什么、拿真 404 正文判它返回空，验的就是生产那三条正则本身。
     */
    /**
     * maccms 的这段 JSON 里 `/` 写成 `\/`、中文写成 `\uXXXX`，**正则直取会把反斜杠原样带进播址**
     * （第 44 轮宿具当场抓到 `https:\/\/t27…`）。所以先按 JSON 解；解不动才退回正则并手工还原 `\/`。
     */
    private val co36UrlRe = Regex(""""url"\s*:\s*"([^"]+)"""")
    private val co36NameRe = Regex(""""vod_name"\s*:\s*"([^"]+)"""")

    private fun co36JsonField(blob: String, key: String): String {
        val viaJson = runCatching {
            val o = Json.parseToJsonElement(blob).jsonObject
            // 片名藏在 vod_data 里，只查顶层会让这一格永远失败并退到兜底（兜底不还原 \uXXXX
            // ⇒ 用户会在详情页标题上看到 \u9ebb\u8c46…）。第 44 轮宿具就是这么抓到的。
            (o[key] ?: o["vod_data"]?.jsonObject?.get(key))?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
        if (viaJson.isNotBlank()) return viaJson
        val raw = when (key) {
            "url" -> co36UrlRe.find(blob)?.groupValues?.get(1).orEmpty()
            "vod_name" -> co36NameRe.find(blob)?.groupValues?.get(1).orEmpty()
            else -> ""
        }
        return jsonUnescape(raw)
    }

    /** 兜底路径专用的极小反转义：只还原 `\/` 与 `\uXXXX` 两种。JSON 主路自己会解好；兜底再多还原一个反斜杠引号就要第三层转义，上一版正是那样把 Kotlin 字面量写坏的。 */
    private fun jsonUnescape(s: String): String {
        val noSlash = s.replace("\\/", "/")
        return Regex("""\\u([0-9a-fA-F]{4})""").replace(noSlash) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
    }

    fun co36MasterFrom(html: String): String {
        val blob = co36PlayerRe.find(html)?.groupValues?.get(1) ?: return ""
        val url = co36JsonField(blob, "url")
        return if (url.startsWith("http")) url else ""
    }

    /** 同一段正文里的片名（`vod_data.vod_name`，比带站名后缀的 `<title>` 干净）。 */
    fun co36NameFrom(html: String): String {
        val blob = co36PlayerRe.find(html)?.groupValues?.get(1) ?: return ""
        return co36JsonField(blob, "vod_name").trim()
    }

    fun co36Detail(v: HanimeVideo): HanimeDetail? {
        val html = getHtml(v.url, referer = originOf(v.url) + "/")
        val master = co36MasterFrom(html)
        if (master.isEmpty()) return null
        val list = getPlaylist(master, v.url)
        if (list.isEmpty()) return null
        val sources = renditionsOf(master, list).ifEmpty { listOf(qualityFromUrl(master) to master) }
            .filter { it.second.startsWith("http") }
        if (sources.isEmpty()) return null
        openNativeDetail(v.url, listOf(master) + sources.map { it.second })
        val name = co36NameFrom(html)
        val d = htmlDetail(v, html, sources)
        return if (name.isBlank()) d else d.copy(video = d.video.copy(title = unescape(name)))
    }

    // ---------- ganxiaban 族（2026-10-02 升为真板块：临时\r38_ganxiaban\REPORT.md、临时\r41_gx_walk.py） ----------

    /** 这一族的根域（同一套库的多个前端，见 GANXIE_NAME 上方说明）。 */
    private val gxRoots = setOf("ganxiaban", "mei78", "avtt3581", "semm1", "laona73", "avtt736")

    /**
     * 卡片与介绍页都是 `/v/<数字 id>.php`（`/n/` 是图文、`/p/` 是播放页，都不在这里认）。
     * 查询串 `?v=<渲染秒>&<随机字母>id=26273<id>` 每次渲染都不同且**不参与定位**，所以只锚路径。
     */
    fun isGanxiabanUrl(u: String): Boolean =
        rootDomain(u) in gxRoots && Regex("""^/v/\d+\.php$""").matches(pathOnly(u))

    /**
     * 闸门：`POST /set_verified`，body 只有一串 `{"track":[{"x":…,"t":…}]}`。
     * 实测（`REPORT.md` §1.1、§1.2 + 本轮 `r41_gx_walk.py` 复放）：
     *  · **6 点、x 步长 65、相邻 140 ms 的线性轨迹在 ganxiaban / semm1 / mei78 / avtt3581 四站都回 `{"success":true}`**；
     *  · 顺序不是条件（不必先 GET /verify.html）、`y` 键不是条件、x 上限不是条件；
     *  · 点数是条件：24 点被拒时服务端会在 body 里报 `"expected":N` —— 所以被拒就照它报的数重交一次；
     *  · 成功才下发 `csrf_token`（Max-Age=7200、HttpOnly），此后列表/介绍/播放/map 全部 200。
     */
    private val gxVerifiedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val gxCookies = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val gxExpectedRe = Regex(""""expected"\s*:\s*(\d+)""")

    private fun gxTrackJson(n: Int): String {
        val t0 = System.currentTimeMillis()
        return """{"track":[""" + (0 until n).joinToString(",") { i -> """{"x":${i * 65},"t":${t0 + i * 140L}}""" } + "]}"
    }

    /** @return true = 这个 host 现在手上有 token。 */
    private fun gxPassGate(host: String): Boolean {
        if ((gxVerifiedUntil[host] ?: 0L) > System.currentTimeMillis()) return true
        var json = gxTrackJson(6)
        var sent = 0
        while (sent++ < 3) {
            val r = runCatching {
                val req = Request.Builder()
                    .url("https://$host/set_verified")
                    .header("User-Agent", GX_UA)
                    .header("Origin", "https://$host")
                    .header("Referer", "https://$host/verify.html?backurl=%2F")
                    .header("Content-Type", "application/json")
                    .post(json.toRequestBody(gxJsonType))
                    .build()
                gxHttp.newCall(req).execute().use { resp ->
                    Triple(resp.code, resp.body?.string().orEmpty(),
                        resp.headers["Set-Cookie"]?.substringAfter("csrf_token=")?.substringBefore(";").orEmpty())
                }
            }.getOrNull() ?: continue
            if (r.first == 200 && r.third.isNotEmpty()) {
                gxCookies[host] = r.third
                gxVerifiedUntil[host] = System.currentTimeMillis() + 7_000_000L   // Max-Age 7200，留 200 s 余量
                return true
            }
            val n = gxExpectedRe.find(r.second)?.groupValues?.get(1)?.toIntOrNull()
            if (n == null || n < 2 || n > 40 || n == 6) return false
            json = gxTrackJson(n)
        }
        return false
    }

    /**
     * 这一族**只给移动端 UA 发手机版正文**。用公用的桌面 UA 实测拿到的是另一套 PC 模板
     * （`<title>亚洲有码-《当前第1页》-干下吧</title>` + `Tpl/06/css/main.css`，24 123 B，
     * 里面既没有 `globalPicList` 也没有 `class="sTit"` ⇒ 解析出 0 张卡）；
     * 换成 Android UA 才是 `干下吧-手机版` 那份（52 513 B / 每页 20 张卡）。
     * 这条是用**生产代码本身**跑出来的（`临时\r41_gx_harness\GxDiag.kt`），不是拿 Python 复刻时发现的——
     * 复刻脚本用的是移动 UA，所以正则验收器全绿、真板块却是空的。
     */
    private const val GX_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** 只给这一族用：不自动跟 302（要跟它判「是不是被踢回闸门」），并按 host 手动带 `csrf_token`。 */
    private val gxHttp: OkHttpClient by lazy {
        http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
    private val gxJsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * 取这一族的正文。三种落点都要处理：
     *  302 → `/verify.html`（token 过期）＝补过一次闸门再来；302 → 别处＝跟着走；200＝packed JS 就还原。
     * 实测 `?v=<秒>&<随机字母>id=26273<cid>` 这些查询串**不参与定位**（裸路径同尺寸同标题），所以拼 URL 时不用管它。
     */
    internal fun gxGetPage(url: String, referer: String = ""): String {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        var target = url
        var next = referer.ifBlank { "https://$host/" }
        for (i in 0 until 4) {
            gxPassGate(host)
            val r = runCatching {
                val b = Request.Builder().url(target).header("User-Agent", GX_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                gxCookies[host]?.let { b.header("Cookie", "csrf_token=$it") }
                if (next.isNotEmpty()) b.header("Referer", next)
                gxHttp.newCall(b.get().build()).execute().use { resp ->
                    Pair(resp.code, Pair(resp.header("Location").orEmpty(), resp.body?.string().orEmpty()))
                }
            }.getOrNull() ?: continue
            val loc = r.second.first
            when {
                r.first in 300..399 && loc.isNotEmpty() -> {
                    if (loc.contains("verify.html")) { gxVerifiedUntil.remove(host); continue }
                    val abs = runCatching { URI(target).resolve(loc).toString() }.getOrDefault("")
                    if (abs.isEmpty() || abs == target) continue
                    next = target; target = abs
                }
                r.first == 200 && r.second.second.isNotBlank() -> return gxUnpack(r.second.second)
                else -> continue
            }
        }
        throw IllegalStateException("ganxiaban 正文未取得：$url（闸门或改版）")
    }

    /**
     * 整页正文是 `document.write(F(unescape(M("<HEX>","%"))))`：`M` 每两字符插一个 `%`，
     * `unescape`+`F`（手写 UTF-8 解码器）合起来就是「hex → 字节 → UTF-8」，反运算直接 unhexlify。
     * **正则必须要求第二个参数 `,"%")`**：省掉它就会匹配到页面里别的 `document.write(...)`，
     * 截出一段"看着像正文"的错位内容（2026-10-02 实测踩过，那次的 `var player` 解出来是半截 hex）。
     */
    private val gxPackedRe = Regex(
        """document\.write\(\s*[A-Za-z_$][\w$]*\(\s*unescape\(\s*[A-Za-z_$][\w$]*\(\s*"([0-9a-fA-F]{400,})"\s*,\s*"%"\s*\)""",
    )

    internal fun gxUnpack(html: String): String {
        val hex = gxPackedRe.find(html)?.groupValues?.get(1) ?: return html
        return runCatching { String(hexToBytes(hex), Charsets.UTF_8) }.getOrDefault(html)
    }

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte() }

    /** JS `unescape("%2f…")`：只解 `%xx`。`URLDecoder` 会把 `+` 当空格，这里不能用它。 */
    internal fun gxUnescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.append(((hi shl 4) + lo).toChar()); i += 3; continue
                }
            }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    /**
     * 这一族的媒体**没有**可播清单上的坑（实测清单与分片都 200 逐字节诚实），但 `c3x.dycp444.com`
     * 挂在 Cloudflare 上，同一地址连发 3 次里有 1 次读超时（`临时\r41_gx_range.py`），所以取清单要重试。
     */
    private fun playlistRetry(url: String, referer: String): String {
        repeat(3) { val t = getPlaylist(url, referer); if (t.isNotEmpty()) return t }
        return ""
    }

    private val gxPlayerRe = Regex("""var\s+player\s*=\s*unescape\("([^"]+)"\)""")
    /**
     * 站点自己的 `artplayer.js` 里那条 **顺序执行** 的 replace 链，必须照抄：
     * `c11→c21→c31`、`c12→c22→c32`，而 `c2x.cdn.dycp444.com`（67.21.72.203/205、107.167.8.155/156）
     * 实测 80/443 全端口 TCP 超时，只有重写终点 `c31/c32.dycp444.com`（Cloudflare）连得上。
     * 不重写就是「取到直链却连不上」——看着像我们的链错了。
     */
    private val gxRewrite = listOf(
        "play.172cat.com" to "c21.cdn.dycp444.com", "play3.172cat.com" to "c21.cdn.dycp444.com",
        "play5.172cat.com" to "c22.cdn.dycp444.com", "c11.cdn.dycp444.com" to "c21.cdn.dycp444.com",
        "c12.cdn.dycp444.com" to "c22.cdn.dycp444.com", "c21.cdn.dycp444.com" to "c31.dycp444.com",
        "c22.cdn.dycp444.com" to "c32.dycp444.com",
    )

    /** 播放页 → 给 ExoPlayer 的那条 m3u8：unescape → `url=<HEX>` → 解 hex → 照链重写 host。 */
    internal fun gxMediaUrl(playHtml: String): String {
        val arg = gxUnescape(gxPlayerRe.find(playHtml)?.groupValues?.get(1) ?: return "")
        val hex = arg.substringAfter("url=", "").trim()
        if (hex.length < 20 || !hex.all { it in "0123456789abcdefABCDEF" }) return ""
        var u = runCatching { String(hexToBytes(hex), Charsets.UTF_8) }.getOrDefault("")
        for ((from, to) in gxRewrite) u = u.replace(from, to)
        return if (u.startsWith("http")) u else ""
    }

    /**
     * 卡片封面写的是 `https://cache.sgvafw.com/base/<B64>.cache`，而这个反代**恒回 403 Cloudflare 挑战页**
     * （带 Referer＋桌面 UA、不带 Referer、移动 UA 三种发法实测都是 403／5509 B／magic `3c21444f`）。
     * `<B64>` 本身可逆，解出来就是源站直链 `https://imgcdn01.dycp444.com/…jpg`（实测 200 + `ffd8ffe0`），
     * 所以在解析阶段就换掉，别让图片加载器去撞那面墙。
     */
    internal fun gxCover(u: String): String {
        if (!u.contains("/base/") || !u.endsWith(".cache")) return u
        val b64 = u.substringAfterLast("/base/").removeSuffix(".cache")
        val padded = b64 + "==".take((4 - b64.length % 4) % 4)
        val real = runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }.getOrDefault("")
        return if (real.startsWith("https://")) real else u
    }

    private val gxPicRe = Regex(
        """<div class="pic"><a href="/v/(\d+)\.php[^"]*"><img src="([^"]+)"[^>]*>(?:<span class="sBottom">([^<]{1,12})</span>)?""",
    )
    private val gxTitRe = Regex("""<span class="sTit"><a href="/v/(\d+)\.php[^"]*">([^<]{2,400})</a>""")
    private val gxEpisodeRe = Regex("""href=['"](/p/\d+-\d+-\d+\.php)""")
    private val gxTotalRe = Regex("""共(\d{1,9})部""")
    /** 这一族的 `<h1>` 里裹着一个装饰 `<i>`（实测 `<h1><i class="ico-lx3"></i>片名</h1>`），公用的 h1Re 匹配不上。 */
    private val gxH1Re = Regex("""<h1>\s*(?:<i[^>]*>\s*</i>)?([^<]{2,300})""")
    private val gxPosterRe = Regex("""<img class="lazy" data-original="([^"]+)"""")
    private val gxIntroRe = Regex("""<div id="introshow">([\s\S]{0,4000}?)</div>""")
    private val gxFieldRe = Regex("""<em class="emTit">[^<]*</em>\s*(?:<em>)?([^<\n]{2,20})""")
    private val gxDateRe = Regex("""\d{4}-\d{2}-\d{2}""")

    internal fun parseGx(pageUrl: String, html: String): List<HanimeVideo> {
        val origin = originOf(pageUrl)
        val titles = HashMap<String, String>()
        for (m in gxTitRe.findAll(html)) {
            titles.putIfAbsent(m.groupValues[1], unescape(m.groupValues[2]).replace(Regex("""\s+"""), " ").trim())
        }
        val out = ArrayList<HanimeVideo>()
        val seen = HashSet<String>()
        for (m in gxPicRe.findAll(html)) {
            val id = m.groupValues[1]
            val url = "$origin/v/$id.php"
            if (!seen.add(url)) continue
            val title = titles[id].orEmpty()
            if (title.length < 2 || isJunkTitle(title)) continue
            out.add(
                HanimeVideo(
                    id = id, url = url, title = title.take(160),
                    thumb = gxCover(m.groupValues[2]),
                    duration = m.groupValues.getOrNull(3).orEmpty().trim(),
                    likes = "", views = "", brand = "",
                ),
            )
        }
        return out
    }

    private val gxPagerMax = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun gxMaxPage(path: String): Int = gxPagerMax[path.removeSuffix(".php")] ?: 0

    /** 列表页：`/vl/<cid>.php` 与 `/vl/<cid>-<N>.php` 两种形态实测并存，`-N` 插在 `.php` 前。 */
    fun gxListing(base: String, path: String, page: Int): List<HanimeVideo> {
        val stem = path.removeSuffix(".php")
        val url = originOf(base) + "$stem${if (page <= 1) "" else "-$page"}.php"
        val html = gxGetPage(url, originOf(base) + "/")
        if (page == 1) {
            val pager = Regex("${Regex.escape(stem)}-(\\d+)\\.php").findAll(html)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
            val byCount = gxTotalRe.find(html)?.groupValues?.get(1)?.toIntOrNull()?.let { (it + 19) / 20 } ?: 0
            val mx = maxOf(pager, byCount)
            if (mx > 1) gxPagerMax[stem] = mx
        }
        return parseGx(url, html)
    }

    /**
     * 介绍页 → 原生详情。选集锚点实测形如 `href='/p/509743-5-1.php?nm=<ts>'`（查询名的随机字母每页不同：
     * `nm=`/`gh=`/… 都见过，所以只锚路径），而 `/p/…` 裸路径 200，不必带那个数。
     * 每条选集解出的播址都要**亲眼验过是 `#EXTM3U`** 才交给播放器；验不出就整体返回 null 走网页版。
     */
    fun gxDetail(v: HanimeVideo): HanimeDetail? {
        val html = gxGetPage(v.url)
        val eps = gxEpisodeRe.findAll(html).map { it.groupValues[1] }.distinct().toList()
        if (eps.isEmpty()) return null
        val origin = originOf(v.url)
        val sources = ArrayList<Pair<Int, String>>()
        for (ep in eps.take(3)) {
            val playHtml = runCatching { gxGetPage(origin + ep, v.url) }.getOrDefault("")
            val media = gxMediaUrl(playHtml)
            if (media.isEmpty()) continue
            val list = playlistRetry(media, origin + ep)
            if (list.isEmpty()) continue
            sources += renditionsOf(media, list).ifEmpty { listOf(qualityFromUrl(media) to media) }
        }
        if (sources.isEmpty()) return null
        openNativeDetail(v.url, sources.map { it.second })
        return HanimeDetail(
            video = v.copy(
                title = unescape(gxH1Re.find(html)?.groupValues?.get(1).orEmpty())
                    .replace(Regex("""\s+"""), " ").trim().take(160).ifBlank { v.title },
                thumb = gxCover(gxPosterRe.find(html)?.groupValues?.get(1).orEmpty()).ifBlank { v.thumb },
            ),
            description = unescape(gxIntroRe.find(html)?.groupValues?.get(1).orEmpty())
                .let { genTagRe.replace(it, " ") }.replace(Regex("""\s+"""), " ").trim().take(600),
            // 「地区/来源/类型/更新时间」四档共用同一个 `<em class="emTit">`，更新时间那档的值还多套一层 `<em>`，
            // 会被一起抓到——按日期形状筛掉，日期不是标签（实测 [干下吧, 亚洲有码, 2026-10-02] 里第三个就是它）。
            tags = gxFieldRe.findAll(html).map { unescape(it.groupValues[1]).trim() }
                .filter { it.isNotEmpty() && !it.contains("欢迎在线观看") && !gxDateRe.matches(it) }
                .distinct().take(6).toList(),
            sources = sources,
            related = parseGx(v.url, html).filter { it.url != v.url }.distinctBy { it.url }.take(16),
            isFav = false,
            isSaved = false,
            saveListId = "",
        )
    }

    /**
     * `/map.html` 的 48 条栏目（实测 6 组 × 8）。弹窗只给 **32 条视频栏目**：
     * 图片专区与小说专区走的是 `/nl/<cid>.php`，那两族页面里是 `<li><a href="/n/<id>.php" title="…">` 的
     * **文字目录，没有封面卡、没有 `/v/` 介绍页、没有任何媒体链**（本轮实测 `/nl/38.php` 全文 sTit=0、/v/=0），
     * 塞进视频板块的弹窗只会得到一片空态，所以不收 —— 要看的作者在板块里点「在内置浏览器打开」。
     */
    val gxCats: List<Pair<String, String>> = listOf(
        "一区·亚洲有码" to "/vl/56.php", "一区·亚洲无码" to "/vl/57.php", "一区·亚洲主播" to "/vl/58.php",
        "一区·韩国高清" to "/vl/59.php", "一区·大陆高清" to "/vl/60.php", "一区·欧美高清" to "/vl/61.php",
        "一区·H动漫" to "/vl/62.php", "一区·三级伦理" to "/vl/63.php",
        "二区·日韩无码" to "/vl/101.php", "二区·中文字幕" to "/vl/102.php", "二区·国产自制" to "/vl/103.php",
        "二区·约炮偷拍" to "/vl/104.php", "二区·传媒剧情" to "/vl/105.php", "二区·强制乱伦" to "/vl/106.php",
        "二区·SM调教" to "/vl/107.php", "二区·剧情动漫" to "/vl/108.php",
        "三区·国产制片" to "/vl/83.php", "三区·日本无码" to "/vl/84.php", "三区·中文字幕" to "/vl/85.php",
        "三区·AV剧情" to "/vl/86.php", "三区·长腿丝袜" to "/vl/87.php", "三区·邻家人妻" to "/vl/88.php",
        "三区·网红主播" to "/vl/89.php", "三区·国模私拍" to "/vl/90.php",
        "四区·抖阴视频" to "/vl/92.php", "四区·网红头条" to "/vl/93.php", "四区·AV解说" to "/vl/94.php",
        "四区·制服诱惑" to "/vl/95.php", "四区·明星换脸" to "/vl/96.php", "四区·SM调教" to "/vl/97.php",
        "四区·女同性恋" to "/vl/98.php", "四区·VR视角" to "/vl/99.php",
    )

    // ---------- MissAV ----------

    private val missavVideoPathRe = Regex("""^/cn/[a-z0-9][a-z0-9._-]*[0-9][a-z0-9._-]*$""", RegexOption.IGNORE_CASE)
    fun isMissavVideoUrl(u: String): Boolean =
        missavVideoPathRe.matches(runCatching { URI(u).rawPath.orEmpty() }.getOrDefault(""))

    private val packedRe = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{[\s\S]{0,600}?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)""",
    )
    private val srcAssignRe = Regex("""(source\d*)\s*=\s*'([^']+\.m3u8)'""")
    private val renditionRe2 = Regex("""#EXT-X-STREAM-INF:([^\n]*)\n([^\n]+)""")
    private val resInAttrs = Regex("""RESOLUTION=\d+x(\d+)""")
    private val ogTitleRe = Regex("""property="og:title" content="([^"]*)"""")
    private val ogImageRe = Regex("""property="og:image" content="([^"]*)"""")
    private val h1Re = Regex("""<h1[^>]*>([^<]{2,200})</h1>""")
    private val genreLinkRe = Regex("""href="(https?://[^"]*/cn/genres/[^"?#]+)"""")

    /**
     * 标准 Dean-Edwards 六参解包：`c` 从大到小，把 payload 里的 base-`a` 记号换成字典项。
     * 站点用它把三条播放地址藏成 `f='8://7.6/5-4-3-2-1/e.0'`（实测解出
     * `source='https://surrit.com/<UUID>/playlist.m3u8'` 等三条）。UUID 是**永久媒体 ID**，不是时效 token。
     */
    internal fun unpackDeanEdwards(payload: String, base: Int, count: Int, dict: List<String>): String {
        var p = payload.replace("\\'", "'").replace("\\\\", "\\")
        for (c in count - 1 downTo 0) {
            val token = java.lang.Integer.toString(c, base)
            val rep = dict.getOrNull(c).orEmpty()
            if (rep.isEmpty()) continue
            p = Regex("\\b" + Regex.escape(token) + "\\b").replace(p) { rep }
        }
        return p
    }

    /**
     * 站点自己的重写规则：`https://<媒体域>/<路径>` → `<线路域>/jmpres/<媒体域>/<路径>`。
     * **必须走本站的 jmpres 前缀**：surrit.com 直连实测 403（Cloudflare 硬拦，带 Referer 与浏览器 UA 也没用）。
     */
    internal fun jmpres(origin: String, mediaUrl: String): String {
        val i = mediaUrl.indexOf("://")
        if (i < 0) return mediaUrl
        val rest = mediaUrl.substring(i + 3)
        return origin.trimEnd('/') + "/jmpres/" + rest
    }

    /**
     * 详情页 → 清晰度直链表（1080/720/480…，标签取变量名里的宽度档）。
     * 只有 playlist（master）时不直接返回它：**master 的变体是相对地址，播放器会解析到 302 之后的轮换域上，
     * 而轮换域不认 /jmpres/，实测返回诱饵图**；此时自己取一次 master、把变体重写成 /jmpres/ 再返回。
     */
    fun missavSources(detailHtml: String, pageUrl: String): List<Pair<Int, String>> {
        val origin = originOf(pageUrl)
        val m = packedRe.find(detailHtml) ?: return emptyList()
        val unpacked = unpackDeanEdwards(
            m.groupValues[1],
            m.groupValues[2].toIntOrNull() ?: 36,
            m.groupValues[3].toIntOrNull() ?: 0,
            m.groupValues[4].split('|'),
        )
        val out = ArrayList<Pair<Int, String>>()
        var playlist = ""
        for (a in srcAssignRe.findAll(unpacked)) {
            val u = a.groupValues[2]
            if (!u.startsWith("http")) continue
            val abs = jmpres(origin, u)
            when (a.groupValues[1]) {
                "source1280" -> out.add(1080 to abs)
                "source842" -> out.add(720 to abs)
                else -> playlist = abs        // source = playlist.m3u8（master）
            }
        }
        if (out.isEmpty() && playlist.isNotEmpty()) out.addAll(resolveMaster(playlist, pageUrl))
        return out.distinctBy { it.second }.sortedByDescending { it.first }
    }

    /** master → 各档变体（同样重写成 /jmpres/）。取不到 `#EXTM3U` 就认为被劫持，返回空表。 */
    private fun resolveMaster(masterUrl: String, referer: String): List<Pair<Int, String>> {
        val text = runCatching { getHtml(masterUrl, referer = referer) }.getOrDefault("")
        if (!text.contains("#EXTM3U")) return emptyList()
        val origin = originOf(referer)
        val base = masterUrl.substringBeforeLast('/') + "/"
        val out = ArrayList<Pair<Int, String>>()
        for (r in renditionRe2.findAll(text)) {
            val h = resInAttrs.find(r.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val uri = r.groupValues[2].trim()
            if (uri.isEmpty() || uri.startsWith("#")) continue
            val abs = runCatching { URI(base).resolve(uri).toString() }.getOrDefault(base + uri)
            out.add(h to jmpres(origin, abs))
        }
        return out
    }

    /**
     * MissAV 详情（原生）。拿不到可播直链时返回 **null**，调用方回落网页版——
     * 给一个播不动的直链比留在网页版更糟。
     */
    fun missavDetail(v: HanimeVideo): HanimeDetail? {
        val pageUrl = v.url
        val html = getHtml(pageUrl, referer = originOf(pageUrl) + "/")
        val sources = missavSources(html, pageUrl)
        if (sources.isEmpty()) return null
        openNativeDetail(pageUrl, sources.map { it.second })
        val title = unescape(ogTitleRe.find(html)?.groupValues?.get(1).orEmpty())
            .ifBlank { unescape(h1Re.find(html)?.groupValues?.get(1).orEmpty()) }
            .ifBlank { v.title }
        val cover = ogImageRe.find(html)?.groupValues?.get(1).orEmpty().ifBlank { v.thumb }
        // 站点没有服务端推荐位（真数据由 Recombee SDK 在客户端拉，需要 publicToken 与 user_uuid，
        // 离线不可复现）。所以「相关影片」取详情页自身给出的**类型链接**列表页——这是站点自己的内容，不是我编的。
        val genres = genreLinkRe.findAll(html).map { it.groupValues[1] }.distinct().take(2).toList()
        var related: List<HanimeVideo> = emptyList()
        for (g in genres) {
            val got = runCatching { parseMissav(g, getHtml(g, referer = originOf(g) + "/")) }
                .getOrDefault(emptyList()).filterNot { it.url == pageUrl }
            if (got.isNotEmpty()) {
                related = got
                break
            }
        }
        val tags = genres.map { it.substringAfterLast('/').replace("-", " ") } +
            listOfNotNull(title.substringBefore(' ').takeIf { it.isNotBlank() && it != title })
        return HanimeDetail(
            video = v.copy(title = title, thumb = cover),
            // 站点 og:description 实测为空；没有正文简介就不写，不拿标签凑一段话
            description = "",
            tags = tags.distinct(),
            sources = sources,
            related = related,
            isFav = false,
            isSaved = false,
            saveListId = "",
        )
    }

    /**
     * 该媒体地址应当带的 Referer：**同域**才有效（跨域 Referer 实测直接 TLS 断连）。
     * 详情页与媒体链同线路域时给详情页 URL，否则退回该域根路径。
     *
     * 例外：LissAV 直接把**线路域的 master** 交出来（`jre<hex>.<线路域>/13avm3/…`，与详情页不同注册域），
     * 而它要的 Referer 是详情页本身（实测：带 → 200 真 TS；不带 → 143 B 诱饵）。
     * 所以「能不能借用详情页」不看域名猜，看**这批主机是不是当前这个详情页自己给出的**——
     * 由 `openNativeDetail` 整体覆盖写入（不是累加、不是进程级永久白名单）。
     */
    fun refererFor(mediaUrl: String): String {
        val host = runCatching { URI(mediaUrl).host.orEmpty().lowercase() }.getOrDefault("")
        val d = lastDetailUrl
        if (host.isEmpty() || d.isEmpty()) return if (host.isEmpty()) "" else "https://$host/"
        val dh = runCatching { URI(d).host.orEmpty().lowercase() }.getOrDefault("")
        if (host == dh) return d
        if (host in nativeMediaHosts) return d
        return "https://$host/"
    }

    // ---------- Jable / 肉视频 / LissAV 的原生详情（2026-10-01 取证：临时\r35_familyB\） ----------

    /** 详情页给出的那批媒体主机（见 `refererFor` 的例外说明）。换详情页就整体覆盖。 */
    @Volatile
    private var nativeMediaHosts: Set<String> = emptySet()

    /** 打开原生详情时统一登记：Referer 用哪个页面、哪些媒体域可以借用它。 */
    /**
     * **亲眼验过是 HLS 清单**的媒体地址。
     *
     * 为什么要有这个集合：media3 的 DefaultMediaSourceFactory 靠 **URI 后缀**推断类型，
     * 没有 `.m3u8` 就当普通文件交给 Mp4Extractor/TsExtractor 那批，于是报
     * 「None of the available extractors … could read the stream」。
     * 肉视频正是这样：它的播址是 `https://rou01.cc/api/hls/< cuid >`——**无扩展名的媒体清单**
     * （真机回显把这条地址打出来才看得见，见 2026-10-02 记录）。
     * 之前只能靠"猜后缀"，现在不用猜：谁被 `getPlaylist`/`renditionsOf` 认出来过，谁就在这里。
     * 静态 mp4（avjoy / SuperPorn）走的是 `<source src=…mp4>`，从不经过这两个函数，
     * 所以不会被误标成 HLS。
     */
    private val hlsVerified = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 这条媒体地址是否被我们实际验证过是 HLS 清单（播放器据此显式声明 MIME，不再靠后缀猜）。 */
    fun mediaIsHls(u: String): Boolean = hlsVerified.contains(u)

    private fun openNativeDetail(pageUrl: String, mediaUrls: List<String>) {
        lastDetailUrl = pageUrl
        nativeMediaHosts = mediaUrls.mapNotNull {
            runCatching { URI(it).host.orEmpty().lowercase() }.getOrNull()?.takeIf { h -> h.isNotEmpty() }
        }.toSet()
    }

    /**
     * 界面**采纳**某条详情时，把 Referer/媒体主机登记重新对准这一条。
     *
     * 为什么要有这个方法：`lastDetailUrl` / `nativeMediaHosts` 是进程级的，由各板块的 `*Detail()` 在
     * 后台线程里写。用户连点两张卡片时，慢的那条（A）会在快的两条之间**后**跑完 `openNativeDetail`，
     * 于是界面上显示的是 B、`refererFor()` 借的却是 A 的详情页——B 的清单拿不到正确 Referer，
     * 就退成诱饵清单或 401，用户看到的还是「换个板块源不对」。
     * 现在由 `HanimeApp.openDetail` 在 seq 校验通过之后调用它，登记对象与屏幕上的那条必然一致。
     */
    fun adoptDetail(d: HanimeDetail) = openNativeDetail(d.video.url, d.sources.map { it.second })

    /**
     * 取**可注册域的首段**当站点族标识：`jable666.com`→`jable666`、`cn.avjoy.ws`→`avjoy`、`www.rou888.net`→`rou888`。
     * 这里必须按「最后两段」取，不能取第一个 label：这几家都挂在 `cn.` / `www.` 前缀下，
     * 取首段会得到 `cn`，于是 avjoy、SuperPorn 这类站点**永远匹配不上**（离线验收器实测抓到）。
     * 同时也正好排除掉线路域：`jre8dlgj4rx.vcdenet2.top`→`vcdenet2`，不会被认成 `rou*`。
     */
    private fun rootDomain(u: String): String {
        val parts = bareHost(runCatching { URI(u).host.orEmpty() }.getOrDefault("")).split('.')
        return if (parts.size >= 2) parts[parts.size - 2] else parts[0]
    }

    private fun pathOnly(u: String): String = "/" + u.substringAfter("://").substringAfter('/', "")
        .substringBefore('?').substringBefore('#')

    /** Jable 作品页：`/videos/<作品码>/`（实测 `/videos/smra-005/`），域族 `jable*`。 */
    fun isJableUrl(u: String): Boolean =
        rootDomain(u).startsWith("jable") && Regex("""^/videos/[^/?#]+/?${'$'}""").matches(pathOnly(u))

    /** 肉视频作品页：`/v/<cuid>` 与 `/s/<cuid>`（实测 `/v/cmu8ng3q60004s6x3byujkmig`），域族 `rou*`。 */
    fun isRouVideoUrl(u: String): Boolean =
        rootDomain(u).startsWith("rou") && Regex("""^/[vs]/[0-9a-z]{16,}${'$'}""").matches(pathOnly(u))

    /** LissAV 作品页：`/<板块>/<语言>/video/cid/<作品码>`（实测 `/asian/zh-CN/video/cid/simm-412`），域族 `huangguo*`/`lissav*`。 */
    fun isLissavUrl(u: String): Boolean {
        val r = rootDomain(u)
        return (r.startsWith("huangguo") || r.startsWith("lissav")) &&
            Regex("""^/(asian|domestic)/[A-Za-z-]{2,6}/video/[a-z]+/[^/?#]+${'$'}""").matches(pathOnly(u))
    }

    /**
     * master（或本身就是分档清单）→ 「清晰度 → 直链」。
     * 三种实测形态都要照顾：
     *  · LissAV 的 master 广播三档（360/480/720）⇒ 逐个给按钮；
     *  · Jable / 肉视频 **只有一档**，页面给的就是分档清单 ⇒ 退成一条，标签取路径里的宽度，取不到给 0；
     *  · 取回来的东西**不是** `#EXTM3U`（或被塞了诱饵）⇒ 返回空表，调用方回落网页版。
     */
    private fun renditionsOf(masterUrl: String, text: String): List<Pair<Int, String>> {
        if (!text.contains("#EXTM3U")) return emptyList()
        val base = masterUrl.substringBeforeLast('/') + "/"
        val out = ArrayList<Pair<Int, String>>()
        for (r in renditionRe2.findAll(text)) {
            val h = resInAttrs.find(r.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val uri = r.groupValues[2].trim()
            if (uri.isEmpty() || uri.startsWith("#")) continue
            out.add(h to runCatching { URI(base).resolve(uri).toString() }.getOrDefault(base + uri))
        }
        // 变体清单里的每一路，以及「没有 STREAM-INF、master 自己就是媒体清单」时的那条 master，
        // 都是我们**亲眼从 #EXTM3U 响应里拿到的**，一并记进 hlsVerified。
        out.forEach { hlsVerified.add(it.second) }
        if (out.isNotEmpty()) { hlsVerified.add(masterUrl); return out.sortedByDescending { it.first } }
        hlsVerified.add(masterUrl)
        return listOf(qualityFromUrl(masterUrl) to masterUrl)
    }

    /**
     * 取清单并**认诱饵**：Jable 与 LissAV 在没有正确 Referer 时都回 **HTTP 200 + 一份 143 字节的假清单**
     * （只列一段、指向 `player.centercdn.top/tip.ts`，取证文件 `jable666.com__DECOY_no_referer.m3u8`
     * 与 `huangguo01.net__SEGMENT0_no_referer_DECOY.m3u8` 逐字节相同），
     * 状态码和 content-type（也是 mpegurl）都判不出来，只能看内容。
     * 判假就返回空串 ⇒ 调用方给出 null ⇒ 回落网页版，比把假清单交给播放器好。
     * 真清单里没有 STREAM-INF 时**必须有 >2 段**才算数；有 STREAM-INF 的多档 master 本来就只有几行，
     * 不能拿段数去卡它（LissAV 的 13avm3 master 实测正好 3 档）。
     */
    private fun getPlaylist(url: String, referer: String): String {
        val text = runCatching { getHtml(url, referer = referer) }.getOrDefault("")
        if (!text.contains("#EXTM3U") || text.contains("tip.ts")) return ""
        if (text.contains("#EXT-X-STREAM-INF")) { hlsVerified.add(url); return text }
        val segs = text.lines().count { it.isNotBlank() && !it.startsWith("#") }
        if (segs > 2) { hlsVerified.add(url); return text }
        return ""
    }

    private val jableHlsRe = Regex("""var\s+hlsUrl\s*=\s*'([^']+\.m3u8)'\s*;""")
    private val jableCatsRe = Regex("""href="/categories/([^"/]+)/">([^<]+)<""")

    /**
     * 详情页 → 原生详情。**登记的媒体主机必须是「这一批要播的全部主机」**：
     * LissAV 实测 master 在一个线路域、它广播的三档变体在另一个线路域，只登记 master 的话
     * 变体与分片就借不到详情页这个 Referer（而 Referer 正是这两家的唯一闸门，实测不带回 143 B 诱饵）。
     */
    fun jableDetail(v: HanimeVideo): HanimeDetail? {
        val html = getHtml(v.url, referer = originOf(v.url) + "/")
        val m = jableHlsRe.find(html) ?: return null
        val master = m.groupValues[1]
        val list = getPlaylist(master, v.url)
        if (list.isEmpty()) return null
        val sources = renditionsOf(master, list)
        if (sources.isEmpty()) return null
        openNativeDetail(v.url, listOf(master) + sources.map { it.second })
        val title = unescape(ogTitleRe.find(html)?.groupValues?.get(1).orEmpty())
            .ifBlank { unescape(h1Re.find(html)?.groupValues?.get(1).orEmpty()) }
            .ifBlank { v.title }
        return HanimeDetail(
            video = v.copy(
                title = title,
                thumb = ogImageRe.find(html)?.groupValues?.get(1).orEmpty().ifBlank { v.thumb },
            ),
            description = "",
            tags = jableCatsRe.findAll(html).map { unescape(it.groupValues[2]).trim() }.distinct().take(8).toList(),
            sources = sources,
            related = sameBoardCards(v.url, html, ::isJableUrl),
            isFav = false,
            isSaved = false,
            saveListId = "",
        )
    }

    private val rouEvRe = Regex("""\{\s*d:"([A-Za-z0-9+/=]{20,})",k:(\d+)\s*\}""")
    private val rouUrlRe = Regex(""""videoUrl":"([^"]+)"""")

    /**
     * 派发口 302 的目标（**不跟随**，只要那条绝对地址）。派发口偶尔不跳、自己就回清单，那情形返回空串。
     */
    private fun rouLineLocation(dispatch: String, referer: String): String {
        val req = Request.Builder().url(dispatch).header("User-Agent", UA)
            .header("Accept", "*/*").header("Referer", referer).get().build()
        return runCatching {
            noRedirectHttp.newCall(req).execute().use { r ->
                if (r.code !in 300..399) return@use ""
                val loc = r.header("Location").orEmpty()
                if (loc.isEmpty()) return@use ""
                runCatching { URI(dispatch).resolve(loc).toString() }.getOrDefault(loc)
            }
        }.getOrDefault("")
    }

    /**
     * 肉视频的播址取法。`/api/hls/<cuid>` 是**派发口**，同一个 cuid 每次请求 302 到
     * 「同前缀（`jre8<cuid>短哈希>rx`）、基域轮换」的线路池；实测（2026-10-02，脚本 `临时\r41_rou_pool.py`）
     * 对 4 个 cuid 各连发 12 次：
     *  · 池子里出现过 `vcdenet1…13.top`、`vcriteria/vcmarrycdn/vcidhive/vcusarrycdn/vcsolaris/vcheetah…` 二十多个域；
     *  · **坏的是当次被选中的那条线，不是这条视频**——`cmuit1ab5…` 有 5 次落到 `vcidhive.sbs` 回
     *    `401 not authorized`、2 次落 `vcdenet11.sbs` 同样 401，另外 5 次落别的域全是 200 真清单；
     *    `cmumwqo8e…` 头两轮各 1 次都是 401（`vcdenet7.top`），连发 12 次就 12/12 全 200；
     *  · 还有 `certificate has expired`（`vcontract.mom`）与握手超时两种死法，各占 1/12。
     * 上一版把派发口原样交给 ExoPlayer，它自己只跟**一次** 302，撞上坏域就整片失败——
     * 这正是用户 2026-10-02 报的「肉视频有的能内嵌播、有的取不到直链只能网页」。
     * 现在：自己跟 302、自己验清单，不通就**换一条连接**重取一条线路（≤6 次），把**验过的那条绝对地址**交给播放器。
     * 上限取 6 也是量出来的：换成不复用的连接池后 12 条里 10 条在 4 次内通，剩下 2 条把 4 次用完仍不通，
     * 再多给两次并每次退避 120·n ms 后能再捞回一部分（`临时\r41_rou/roudiag2.log`）。
     *
     * @return (交给播放器的地址, 清单正文)；六条线路都不通就回 ("", "")，调用方回落网页版。
     */
    private fun rouMaster(dispatch: String, referer: String): Pair<String, String> {
        for (i in 0 until 6) {
            rouAttemptGap(i)
            val line = rouLineLocation(dispatch, referer)
            if (line.isNotEmpty()) {
                val text = getPlaylist(line, referer)
                if (text.isNotEmpty()) return rouAdopt(line, text)
            } else {
                // 派发口偶尔不跳转、自己就把清单给了（同一 cuid 连发实测出现过）
                val inline = getPlaylist(dispatch, referer)
                if (inline.isNotEmpty()) return rouAdopt(dispatch, inline)
            }
        }
        return "" to ""
    }

    private fun rouAdopt(media: String, text: String): Pair<String, String> {
        if (media.startsWith("https://rou") || media.contains("/api/hls/")) rangeHostileMedia.add(media)
        return media to text
    }

    /** 重取一条线路之间的间隔：实测坏线是**成串**出现的（同一时刻多条线一起拒），退一点再试命中率明显更高。 */
    private fun rouAttemptGap(attempt: Int) {
        if (attempt > 0) runCatching { Thread.sleep(120L * attempt) }
    }

    /**
     * 肉视频的内嵌状态：`{d:"<base64>",k:<整数>}`，站点自己的解码是
     * `JSON.parse(atob(d).split("").map(s => String.fromCharCode(s.charCodeAt(0) - k)).join(""))`
     * ——**先 base64 解码，再每个字节减 k**。实测解出 `{"videoUrl":"/api/hls/<cuid>",…}`（脚本 `临时\r35_rou_decode.py` 可复放）。
     */
    internal fun rouDecode(d: String, k: Int): String {
        val raw = java.util.Base64.getDecoder().decode(d)
        val out = ByteArray(raw.size) { i -> ((raw[i].toInt() - k) and 0xFF).toByte() }
        return String(out, Charsets.UTF_8)
    }

    fun rouVideoDetail(v: HanimeVideo): HanimeDetail? {
        val html = getHtml(v.url, referer = originOf(v.url) + "/")
        val m = rouEvRe.find(html) ?: return null
        val json = runCatching { rouDecode(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0) }.getOrDefault("")
        val rel = rouUrlRe.find(json)?.groupValues?.get(1) ?: return null
        val dispatch = if (rel.startsWith("http")) rel else originOf(v.url) + rel
        val (master, list) = rouMaster(dispatch, v.url)
        if (list.isEmpty()) return null
        val sources = renditionsOf(master, list)
        if (sources.isEmpty()) return null
        openNativeDetail(v.url, listOf(master) + sources.map { it.second })
        val title = unescape(ogTitleRe.find(html)?.groupValues?.get(1).orEmpty()).ifBlank { v.title }
        return HanimeDetail(
            video = v.copy(
                title = title,
                thumb = ogImageRe.find(html)?.groupValues?.get(1).orEmpty().ifBlank { v.thumb },
            ),
            // 站点 og:description 给的是「…在线观看 - 搜AV私|藏密私片AV影院」这类推广语，不是简介，宁缺不凑
            description = "",
            tags = emptyList(),
            sources = sources,
            related = sameBoardCards(v.url, html, ::isRouVideoUrl),
            isFav = false,
            isSaved = false,
            saveListId = "",
        )
    }

    private val lissavUidA = Regex("""data-streamit-page-video-uid="([0-9a-f]{8,32})"""")
    private val lissavUidB = Regex("""window\.__STREAMIT_ME_QUERY__\s*=\s*\{"videoUid":"([0-9a-f]{8,32})"\}""")
    private val lissavUrlRe = Regex(""""url":"([^"]+)"""")

    /**
     * LissAV 的两跳：详情页取 uid → `GET /<板块>/<语言>/api/video/stream?video_uid=<uid>` → JSON 里的 `playlist[]`。
     * **取没有 `urlSign` 的那条**（实测它是本站自己的 hls 目录线、广播三档、不需要签名）；
     * 带 urlSign 的两条要在 Accept 头里附上 sign 才认（站点自己的写法是通配 MIME 后面拼 sign），
     * 那是 MissAV 的桶，不在这里跟它较劲。
     * 板块与语言从页面 URL 自己带出来（实测 `asian|domestic` × `zh-CN|zh-HK|en`）。
     */
    fun lissavDetail(v: HanimeVideo): HanimeDetail? {
        val html = getHtml(v.url, referer = originOf(v.url) + "/")
        val uid = lissavUidA.find(html)?.groupValues?.get(1)
            ?: lissavUidB.find(html)?.groupValues?.get(1) ?: return null
        val p = pathOnly(v.url).split('/').dropWhile { it.isEmpty() }
        if (p.size < 2) return null
        val api = "${originOf(v.url)}/${p[0]}/${p[1]}/api/video/stream?video_uid=$uid"
        val json = runCatching { getHtml(api, referer = v.url) }.getOrDefault("")
        if (!json.contains("\"playlist\"")) return null
        val master = lissavUnsigned(json) ?: return null
        openNativeDetail(v.url, listOf(master))
        val list = getPlaylist(master, v.url)
        if (list.isEmpty()) return null
        val sources = renditionsOf(master, list)
        if (sources.isEmpty()) return null
        val title = unescape(ogTitleRe.find(html)?.groupValues?.get(1).orEmpty())
            .ifBlank { unescape(h1Re.find(html)?.groupValues?.get(1).orEmpty()) }
            .ifBlank { v.title }
        return HanimeDetail(
            video = v.copy(
                title = title,
                thumb = Regex(""""poster":"([^"]+)"""").find(json)?.groupValues?.get(1).orEmpty()
                    .ifBlank { ogImageRe.find(html)?.groupValues?.get(1).orEmpty().ifBlank { v.thumb } },
            ),
            description = "",
            tags = Regex(""""name":"([^"]+)"""").findAll(html).map { unescape(it.groupValues[1]).trim() }
                .filter { it.isNotEmpty() }.distinct().take(8).toList(),
            sources = sources,
            related = sameBoardCards(v.url, html, ::isLissavUrl),
            isFav = false,
            isSaved = false,
            saveListId = "",
        )
    }

    /** 从 JSON 里挑**不带 urlSign** 的那条播放线：按 `{` `}` 配对切对象，不能靠正则切数组（URL 里就有花括号转义）。 */
    internal fun lissavUnsigned(json: String): String? {
        val head = json.indexOf("\"playlist\"")
        val arr = if (head < 0) -1 else json.indexOf('[', head)
        if (arr < 0) return null
        var depth = 0
        var start = -1
        for (i in arr until json.length) {
            when (json[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val o = json.substring(start, i + 1)
                        if (!o.contains("urlSign")) {
                            lissavUrlRe.find(o)?.groupValues?.get(1)?.let { return it }
                        }
                        start = -1
                    }
                }
                ']' -> if (depth == 0) return null
            }
        }
        return null
    }

    /** 详情页里同板块的其它作品：卡片直接用通用解析拿，再按板块自己的 URL 形状筛（不额外发请求）。 */
    private fun sameBoardCards(pageUrl: String, html: String, belongs: (String) -> Boolean): List<HanimeVideo> =
        runCatching { parseGeneric(pageUrl, html) }.getOrDefault(emptyList())
            .filter { belongs(it.url) && it.url.trimEnd('/') != pageUrl.trimEnd('/') }
            .distinctBy { it.url }
            .take(18)

    // ---------- avjoy / SuperPorn / 黄色仓库族 / 亚洲素人精选 / OKXXX（2026-10-01 取证：临时\r35_familyA\） ----------

    /** avjoy `/video/<id>/<slug>`、SuperPorn `/video/<slug>`、OKXXX `/video/<id>/`：都是**静态 `<source>`**，零接口零解密。 */
    private val avjoyPathRe = Regex("""^/video/\d+/[^/?#]+/?${'$'}""")
    private val superpornPathRe = Regex("""^/video/[A-Za-z0-9-]+/?${'$'}""")
    private val okxxxPathRe = Regex("""^/video/\d+/?${'$'}""")
    /** 「播放器 iframe 里带 video_url」这一族（黄色仓库 69ck 的 maccms 播放页 + 好色TV pb365 的 video-N.htm）。 */
    private val maccmsPlayRe = Regex("""^/index\.php/vod/play/id/\d+""")
    private val pb365PlayRe = Regex("""^/video-\d+\.htm${'$'}""")
    /** 亚洲素人精选：`/v/<6 位码>/`（与肉视频的 `/v/<24 位 cuid>` 靠域名族分开，见 is591avUrl）。 */
    private val shortVPathRe = Regex("""^/v/[^/?#]{3,12}/?${'$'}""")

    fun isAvjoyUrl(u: String): Boolean = rootDomain(u).startsWith("avjoy") && avjoyPathRe.matches(pathOnly(u))
    fun isSuperpornUrl(u: String): Boolean = rootDomain(u).startsWith("superporn") && superpornPathRe.matches(pathOnly(u))
    fun isOkxxxUrl(u: String): Boolean = rootDomain(u).startsWith("okxxx") && okxxxPathRe.matches(pathOnly(u))
    fun is591avUrl(u: String): Boolean = rootDomain(u).startsWith("591av") && shortVPathRe.matches(pathOnly(u))
    fun isEmbedPlayerUrl(u: String): Boolean {
        // 36co 也是 maccms 形态、URL 形状一模一样，但它本轮**没过媒体门槛**（一个 200 的清单都没取到过）：
        // 让它落进这一族，界面就会给一个「点了没画面」的原生按钮，还会为每张卡白花一发播放页请求。
        // 所以这里明确排除，36co 的卡片走介绍页 + 「网页播放」。
        if (rootDomain(u) == "36co") return false
        val p = pathOnly(u)
        return maccmsPlayRe.containsMatchIn(p) || pb365PlayRe.matches(p)
    }

    private val staticSourceRe = Regex("""<source src="(https?://[^"]+\.mp4[^"]*)"[^>]*>""", RegexOption.IGNORE_CASE)
    private val embedVideoUrlRe = Regex("""player\.centercdn\.top/player\.html\?video_url=([^"'\s&]+)""")
    private val streamVarRe = Regex("""var\s+stream\s*=\s*'([^']+\.m3u8)'\s*;""")
    private val okxxxFileRe = Regex("""https?://[^"'()\s]+/get_file/\d+/[0-9a-f]{32}/\d+/\d+/\d+(?:_(\d{3,4})p)?\.mp4/""")

    /**
     * 从 URL 尾巴上认清晰度（`14614_720p.mp4`、`786703_1080p.mp4/`）；认不出给 0，界面渲染成「播放」而不是「0P」。
     * **只认得起来的档位**：SuperPorn 的播址里就有 `/videos/106/10650/mp4/…` 这种目录号，
     * 凡是不限定取值集合，`/(\d{3,4})/` 会把 106 当成清晰度写成「106P」。
     */
    private val knownHeights = setOf(240, 360, 480, 540, 720, 768, 1080, 1440, 2160)

    private fun qualityFromUrl(u: String): Int {
        val c = Regex("""[-_/.](\d{3,4})p?(?=[./])""").findAll(u).mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it in knownHeights }
        return c ?: 0
    }

    /**
     * 静态 `<source>` 族（avjoy / SuperPorn）：播址**原样写在详情页里**，取到就是 206。
     * SuperPorn 的 `?secure=` 里带 `%3D%3D%2C`，**必须原样携带**：解码或二次编码都会失效（实测）。
     */
    fun staticSourceDetail(v: HanimeVideo): HanimeDetail? {
        val html = getHtml(v.url, referer = originOf(v.url) + "/")
        val urls = staticSourceRe.findAll(html).map { it.groupValues[1] }
            .filterNot { it.contains("preview", true) }.toList().distinct()
        if (urls.isEmpty()) return null
        openNativeDetail(v.url, urls)
        val sources = urls.map { qualityFromUrl(it) to it }.sortedByDescending { it.first }
        return htmlDetail(v, html, sources)
    }

    /**
     * iframe 播放器族（黄色仓库实测 200；好色TV 的解析链同样打通，只是它那条线路当时回 522）：
     * 播放页里 `player.centercdn.top/player.html?video_url=<percent 编码的 m3u8>`，**解码就是播址**。
     */
    fun embedPlayerDetail(v: HanimeVideo): HanimeDetail? {
        val html = getHtml(v.url, referer = originOf(v.url) + "/")
        val enc = embedVideoUrlRe.find(html)?.groupValues?.get(1) ?: return null
        val master = runCatching { java.net.URLDecoder.decode(enc, "UTF-8") }.getOrDefault("")
        if (!master.startsWith("http")) return null
        val list = getPlaylist(master, v.url)
        if (list.isEmpty()) return null
        val sources = renditionsOf(master, list)
        if (sources.isEmpty()) return null
        openNativeDetail(v.url, listOf(master) + sources.map { it.second })
        return htmlDetail(v, html, sources)
    }

    /** 亚洲素人精选：详情页内联 `var stream = '...m3u8'`。实测**旧副本回 410**，所以清单绝不复用缓存。 */
    fun streamVarDetail(v: HanimeVideo): HanimeDetail? {
        val html = getHtml(v.url, referer = originOf(v.url) + "/")
        val master = streamVarRe.find(html)?.groupValues?.get(1) ?: return null
        val list = getPlaylist(master, v.url)
        if (list.isEmpty()) return null
        val sources = renditionsOf(master, list)
        if (sources.isEmpty()) return null
        openNativeDetail(v.url, listOf(master) + sources.map { it.second })
        return htmlDetail(v, html, sources)
    }

    /**
     * OKXXX：详情页直接内联三条正式片地址（`.mp4/` 结尾但**返回的是 HLS master**，末尾斜杠必须留着）。
     * 带 `preview` 的是预览，排除掉。
     */
    fun okxxxDetail(v: HanimeVideo): HanimeDetail? {
        val html = getHtml(v.url, referer = originOf(v.url) + "/")
        val cands = okxxxFileRe.findAll(html).map { it.value }.filterNot { it.contains("preview", true) }
            .toList().distinct()
        if (cands.isEmpty()) return null
        val master = cands.maxByOrNull { qualityFromUrl(it) }!!
        val list = getPlaylist(master, v.url)
        if (list.isEmpty()) return null
        val sources = renditionsOf(master, list)
        if (sources.isEmpty()) return null
        openNativeDetail(v.url, listOf(master) + sources.map { it.second })
        return htmlDetail(v, html, sources)
    }

    /** 详情页字段装进 HanimeDetail：这几家的正文/OG 结构相近，共用一份，别再复制五遍。 */
    private fun htmlDetail(v: HanimeVideo, html: String, sources: List<Pair<Int, String>>): HanimeDetail {
        val title = unescape(ogTitleRe.find(html)?.groupValues?.get(1).orEmpty())
            .ifBlank { unescape(h1Re.find(html)?.groupValues?.get(1).orEmpty()) }
            .ifBlank { v.title }
        return HanimeDetail(
            video = v.copy(
                title = title,
                thumb = ogImageRe.find(html)?.groupValues?.get(1).orEmpty().ifBlank { v.thumb },
            ),
            description = "",
            tags = Regex("""href="[^"]*/(?:categories|tags)/([^"/]+)/?">([^<]+)<""")
                .findAll(html).map { unescape(it.groupValues[2]).trim() }
                .filter { it.isNotEmpty() }.distinct().take(8).toList(),
            sources = sources,
            related = sameBoardCards(
                v.url, html,
                { u -> isJableUrl(u) || isRouVideoUrl(u) || isLissavUrl(u) || isAvjoyUrl(u) ||
                    isSuperpornUrl(u) || isOkxxxUrl(u) || is591avUrl(u) || isEmbedPlayerUrl(u) ||
                    isQinavUrl(u) || isCo36Url(u) },
            ),
            isFav = false,
            isSaved = false,
            saveListId = "",
        )
    }

    private val titleTagRe = Regex("""<title[^>]*>([^<]{3,200})</title>""", RegexOption.IGNORE_CASE)
    private val ogDescRe = Regex("""(?:property|name)="og:description" content="([^"]*)""")
    private val metaDescRe = Regex("""<meta[^>]+name="description"[^>]+content="([^"]*)""", RegexOption.IGNORE_CASE)
    private val keywordsRe = Regex("""<meta[^>]+name="keywords"[^>]+content="([^"]*)""", RegexOption.IGNORE_CASE)
    private val ogVideoTagRe = Regex("""(?:property|name)="og:video:tag"\s+content="([^"]+)"\s*/?>""")
    private val ogDurationRe = Regex("""(?:property|name)="(?:og:duration|video:duration)" content="(\d+)""")
    private val tagLinkRe = Regex("""href="[^"]*/(?:categories|tags|genres)/([^"/]+)/?">([^<]+)<""")

    /** 协议相对写法（CartoonPorno 的 og:image 实测就是 `//host/…jpg`）不补 scheme 就加载不出来。 */
    private fun absImg(u: String, pageUrl: String): String = when {
        u.isBlank() -> ""
        u.startsWith("//") -> "https:" + u
        u.startsWith("http") -> cleanThumb(u, pageUrl).ifBlank { u }
        else -> cleanThumb(u, pageUrl)
    }

    /** 路径拆段（丢掉空段与首尾斜杠），用来比较两个 URL 的「形状」。 */
    private fun pathSegs(u: String): List<String> =
        runCatching { URI(u).rawPath.orEmpty() }.getOrDefault("")
            .split('/').filter { it.isNotEmpty() }

    /** 段内的数字串折成 `#`：`video-1246807.htm` 与 `video-1247449.htm` 是同一个内容池。 */
    private fun segShape(s: String): String = s.replace(Regex("""\d+"""), "#")

    /**
     * 同 host + 同段数 + 首段相同（字面或形状）才算「同一个内容池」。
     * 实测挡掉：Hentai 的 `/g/685147/2/`（翻页，段数不同）、Pornhub 的 `/video?c=27`（分类菜单，
     * 首段就不是 view_video.php）；实测放行：好色TV 只有一段，首段本身就是片名 `video-1246807.htm`，
     * 只比字面会把整站推荐位判成 0。
     */
    private fun sameShape(a: String, b: String): Boolean {
        val ua = runCatching { URI(a) }.getOrNull() ?: return false
        val ub = runCatching { URI(b) }.getOrNull() ?: return false
        if (bareHost(ua.host.orEmpty()) != bareHost(ub.host.orEmpty())) return false
        val sa = pathSegs(a)
        val sb = pathSegs(b)
        if (sa.isEmpty() || sa.size != sb.size) return false
        // 首段字面相同（/videos/、/vodplay/、/g/），或**形状**相同（好色TV 的作品页只有一段，
        // 首段就是片名 `video-1246807.htm` —— 只比字面会把整站推荐位判成 0）
        return sa[0] == sb[0] || segShape(sa[0]) == segShape(sb[0])
    }

    /**
     * 「同一个作品的另一个入口」的标识段。
     * · 路径式（LissAV `/asian/zh-CN/video/cid/ddn-093`）：末段就是作品号，
     *   语言切换项 `/asian/en/video/cid/ddn-093` 与它相同 ⇒ 挡掉（实测段数与首段都一样，只有末段能挡）；
     * · 单段式（Pornhub `/view_video.php?viewkey=…`、好色TV `/video-1246807.htm`）：
     *   末段是**所有作品共用**的文件名，只比末段会把整站推荐位判成 0（实测 99 条 view_video 链接全被吞），
     *   所以这种形状改用 path+query 当标识。
     */
    private fun workKey(u: String): String {
        val s = pathSegs(u)
        if (s.size >= 2) return s.last()
        return runCatching {
            val x = URI(u)
            (x.rawPath.orEmpty()) + "?" + (x.rawQuery.orEmpty())
        }.getOrDefault(u)
    }

    private fun sameWork(a: String, b: String): Boolean = pathSegs(a).size == pathSegs(b).size &&
        workKey(a) == workKey(b)

    /**
     * 通用详情介绍页：**没有原生播址的板块也给出与官方站 / xchina 同一套的介绍页**（2026-10-01 用户点名）。
     * 字段取法全部来自 `临时\r36_detail\` 的 22 份真实详情页样本，不是凭印象写的：
     *  · 标题 og:title → h1 → title → 卡片标题；
     *  · 封面 og:image（协议相对的要补 https:）→ 卡片封面；
     *  · 简介 og:description → meta description；
     *  · 标签 og:video:tag → meta keywords → 分类链接；
     *  · 推荐位＝详情页里**同形状且自带封面**的其它卡片。
     * `sources` 一律留空：这些站点没有验证过的可播清单，界面据此显示「网页播放」而不是假装能原生播。
     */
    fun metaDetail(v: HanimeVideo): HanimeDetail? {
        val html = runCatching { getHtml(v.url, referer = originOf(v.url) + "/") }.getOrDefault("")
        if (html.length < 200) return null
        val title = unescape(ogTitleRe.find(html)?.groupValues?.get(1).orEmpty())
            .ifBlank { unescape(h1Re.find(html)?.groupValues?.get(1).orEmpty()) }
            .ifBlank { unescape(titleTagRe.find(html)?.groupValues?.get(1).orEmpty()) }
            .ifBlank { v.title }
            .replace(Regex("""\s+"""), " ").trim().take(160)
        val cover = absImg(ogImageRe.find(html)?.groupValues?.get(1).orEmpty(), v.url).ifBlank { v.thumb }
        val desc = unescape(ogDescRe.find(html)?.groupValues?.get(1).orEmpty())
            .ifBlank { unescape(metaDescRe.find(html)?.groupValues?.get(1).orEmpty()) }
            .trim().take(900)
        val tags = ogVideoTagRe.findAll(html).map { unescape(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }.distinct().take(8).toList()
            .ifEmpty {
                keywordsRe.find(html)?.groupValues?.get(1)?.split(',')
                    ?.map { unescape(it.trim()) }?.filter { it.length in 2..24 }?.distinct()?.take(8).orEmpty()
            }
            .ifEmpty {
                tagLinkRe.findAll(html).map { unescape(it.groupValues[2]).trim() }
                    .filter { it.isNotEmpty() }.distinct().take(8).toList()
            }
        val secs = ogDurationRe.find(html)?.groupValues?.get(1)?.toIntOrNull()
        val dur = v.duration.ifBlank { if (secs != null && secs > 0) "%d:%02d".format(secs / 60, secs % 60) else "" }
        val related = runCatching { parseGeneric(v.url, html) }.getOrDefault(emptyList())
            .filter { it.thumb.isNotBlank() && sameShape(it.url, v.url) && !sameWork(it.url, v.url) }
            .distinctBy { it.url }.take(18)
        return HanimeDetail(
            video = v.copy(title = title, thumb = cover, duration = dur),
            description = desc,
            tags = tags,
            sources = emptyList(),
            related = related,
            isFav = false,
            isSaved = false,
            saveListId = "",
        )
    }

    /**
     * 板块详情入口（**卡片点击只认这一个函数**）：有原生播址的板块沿用 `boardDetail`；
     * 拿不到原生清单的（含原生板块被频控挡下的场合）退回通用介绍页，界面再给「网页播放」。
     */
    fun boardPage(v: HanimeVideo): HanimeDetail? =
        (if (isNativeBoardUrl(v.url)) runCatching { boardDetail(v) }.getOrNull() else null)
            ?: runCatching { metaDetail(v) }.getOrNull()

    // ---------- 通用卡片解析（统一卡片 UI：非 MissAV 的站点都走这一套） ----------

    private val genHintRe = Regex(
        """(/v/|/video|/watch|/detail|/vod|/movies?/|/archives?/|/works?/|/post|/play|/mv/|/article|/albums?|/photos?/|/p/|/n/|/\d{4,}|[.\-]\d{3,}|-[a-z]{2,}\d{2,}|\.htm|viewkey=|/g/\d)""",
        RegexOption.IGNORE_CASE,
    )
    private val genBadRe = Regex(
        """(javascript:|^#|/login|/register|/signup|/search|/user|/tag|/categories|/genres?|/actress|/maker|/page/|/vip|/help|/contact|/dmca|/terms|/privacy|/enter$|/static|/assets|/css|/js/|\.(css|js|png|jpe?g|webp|svg|ico|gif)$)""",
        RegexOption.IGNORE_CASE,
    )
    // 内层上限从 2000 提到 20000：YesPorn 的卡片内层实测 2997 字符，2000 的上限让**整站 0 卡**。
    private val genAnchorRe = Regex("""<a\s[^>]*href=["']([^"']+)["']([^>]*)>([\s\S]{0,20000}?)</a>""", RegexOption.IGNORE_CASE)
    private val genImgTagRe = Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE)
    private val genDataAttrRe = Regex(
        """(?:data-src|data-original|data-echo|data-lazy-src|data-background|data-url|data-thumb_url)\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val genSrcAttrRe = Regex("""\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val genBgImgRe = Regex("""background-image\s*:\s*url\(\s*['"]?([^'")]+)""", RegexOption.IGNORE_CASE)
    /**
     * 封面不一定挂在 `img` 标签的 `src` 上：
     *  · Pornhub 的 GIF 卡写在 `<video data-poster="…jpg">`，同一标签的 `poster="data:image/gif;base64,…"`
     *    是占位图（必须跳过，否则等于没封面）；
     *  · qinav 更野：`<img class="lazy" src="/static/loading.gif" img="https://…/47.jpg">`——
     *    真图放在一个**名叫 `img` 的属性**里，`src` 是占位、全站没有 `data-src`（实测 52/52 张都是这个写法）。
     * 所以最后再对整个内层扫一遍**任意标签**的图片属性，且只收带图片扩展名的值。
     */
    private val genAnyImgAttrRe = Regex(
        """\s(?:data-src|data-original|data-poster|data-thumb|data-thumb_url|data-image|img|poster)\s*=\s*""" +
            """["']([^"']+\.(?:jpg|jpeg|png|webp|gif)[^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val genAltRe = Regex("""alt=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val genTitleAttrRe = Regex("""(?:title|aria-label)=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val genDurRe = Regex("""(\d{1,3}:\d{2}(?::\d{2})?|\d{1,3}\s*分\s*\d{1,2}\s*秒)""")
    private val genCommentRe = Regex("""<!--[\s\S]*?-->""")
    private val genTagRe = Regex("""<[^>]+>""")
    private val genNavWords = setOf(
        "首页", "登录", "注册", "搜索", "更多", "下一页", "上一页", "分类", "排行", "标签", "会员",
        "最新", "周榜", "月榜", "日榜", "热门", "推荐", "全部", "查看", "查看更多", "精选", "列表",
        "home", "login", "more", "next", "prev", "help", "about", "contact",
        "new", "hot", "top", "all", "view", "daily", "weekly", "monthly", "trending", "onlyfans",
    )

    /** 占位图/懒加载底图：当封面用等于「封面无法获取」，必须丢掉去取真图。 */
    private val genBadThumbRe = Regex(
        """(^data:|placeholder|/blank\.jpg|blank\.webp|zero\.png|/a/i/|loading\.(gif|png|webp)|spinner|no-image|nopic|default\.(jpg|png|webp)|1px)""",
        RegexOption.IGNORE_CASE,
    )

    /** 纯时长/纯数字的"标题"不是标题（Jable 实测把 `2:35:36` 当标题，因为图锚点内文只有时长）。 */
    private fun isJunkTitle(t: String): Boolean {
        val s = t.trim()
        if (s.length < 6) return true
        if (genDurRe.matches(s)) return true
        if (s.all { it.isDigit() || it in " :.-/_" }) return true
        if (genNavWords.contains(s.lowercase())) return true
        // 菜单项常是「中英对照」拼接（好色TV 实测 `最新/New`、`周榜/Weekly`、`月榜/Monthly`），
        // 整体不在词表里，但**每个分词都是导航词**——这也算垃圾标题。真片名里只要有一个非导航词就活着。
        val toks = s.split(Regex("""[\s/|·、，,（）()\[\]-]+""")).filter { it.isNotBlank() }
        return toks.isNotEmpty() && toks.all { genNavWords.contains(it.lowercase()) }
    }

    private fun cleanThumb(u: String, pageUrl: String): String {
        val t = u.trim().replace("&amp;", "&")
        if (t.isEmpty() || genBadThumbRe.containsMatchIn(t)) return ""
        val abs = runCatching { URI(pageUrl).resolve(t).toString() }.getOrNull().orEmpty()
        return if (abs.startsWith("http")) abs else ""
    }

    /** host 比较忽略 `www.` 前缀：18av 的线路是 `www.18av01.cc`、页内链接是 `18av01.cc`，实测因此整站 0 卡。 */
    private fun bareHost(h: String): String = h.removePrefix("www.").lowercase()

    /**
     * 通用列表解析（统一卡片 UI）。**2026-10-01 按 57 份实测样本重写**，修掉的缺陷与受害站点：
     *  · 懒加载属性顺序：原来 `(?:data-src|…|src)` 是"最左位置优先"，`src=` 写在 `data-src=` 前面就必取占位图
     *    → 改成先扫全部 `data-*`、再退 `src`，并丢弃 data:/placeholder/blank/zero.png
     *    （Jable、SuperPorn、OKXXX、亚洲素人精选、CartoonPorno、禁漫天堂，实测 266 张坏封面）；
     *  · 非 `<img>` 封面：补 `style="background-image:url(…)"` 与 `<a data-original|data-background>`
     *    （好色TV、黄色仓库、爱漫岛——这三家卡片里**根本没有 img 标签**）；
     *  · 标题来源：补锚点自身 `title`/`aria-label`（在内层之外，原来完全读不到）、剥 HTML 注释
     *    （爱漫岛 193 张标题全是 `更新至第1集 -->`）；
     *  · 同一 URL 多锚点：原来 `seen` 先到先得，图锚点先占位后因 title<6 被丢，带标题的锚点成了"重复"
     *    （18av、xhamster 46 个链接只剩 7 卡）→ 改成**合并取最优**；
     *  · 内层上限 2000 → 20000（YesPorn 整站 0 卡）；hint 补 `viewkey=`（Pornhub 只剩 GIF 菜单）；
     *  · 时长：原来硬编码空串，而这些站页面上都有（`<var class="duration">`、`.pic-text`、`N分N秒`）；
     *  · 门槛：必须有**封面或时长**才算卡片，否则导航菜单项（周榜/Weekly/OnlyFans/分类名）会凑满 6 张
     *    让 `genericListing` 的"≥6 张即命中"闸门误判成解析成功。
     */
    /** 同一 URL 的多个锚点各贡献自己那一份字段（图锚点有封面没标题、标题锚点反过来）。 */
    private class GenCard {
        var title = ""
        var thumb = ""
        var dur = ""
    }

    /**
     * 「像详情页」判据：用来放行**本来就没有封面与时长**的正文列表（情色小说 `/article/3229`），
     * 同时挡住导航/分类项（`/vodtype/4.html` 这类两位数以下的分类页）。
     * **只在 path+query 上判，不看 host**（见 `detailLike`）：整条 URL 一起判会让域名里的数字
     * 冒充「作品号」，实测 `pb365.nl` 的 5 个菜单项与 `rou123.ws` 的「OnlyFans」全被放成卡片。
     */
    private val genDetailRe = Regex(
        """(\d{3,}|/article/|/post/|/read/|/book/|/chapter|viewkey=|/g/\d+/|/videos/\w|[?&]id=\d)""",
        RegexOption.IGNORE_CASE,
    )

    /** 取 path+query（丢掉 scheme 与 host）；URI 解析不了的（带中文、带空格的脏 href）退回整串。 */
    private fun pathQuery(u: String): String {
        val noScheme = u.substringAfter("://", u)
        return if (noScheme == u) u else "/" + noScheme.substringAfter('/', "")
    }

    private fun detailLike(u: String): Boolean = genDetailRe.containsMatchIn(pathQuery(u))

    /**
     * 「这条链接本身又是一个列表」——它们**带封面**，所以过不了「没图没时长才要求像详情页」那道门，
     * 只能单独拦。两份实测样本：18comic 的「查看更多…」`/albums?o=mr` 与「English Manga」`/albums/meiman`
     * （作品页是**单数** `/album/1232836/…`），xhamster 的「顶级创作者视频」`/creators/videos/esea`。
     */
    private val genListPathRe = Regex("""(/albums([/?#]|$)|/creators/)""", RegexOption.IGNORE_CASE)

    /**
     * 导航项的两条硬判据，**字面量全部取自 2026-10-01 的列表样本原文**（不是解析器自己算的）：
     *  · 锚点自己的 class：`page-link`（Jable「最后 »」/avjoy 数字页）、`list-group-item`（avjoy 分类）、
     *    `sugg-pep`（Xanimu 搜索建议）、`menuLink`/`js-menuAnalytics`（Pornhub 顶部菜单）、
     *    `viewPlaylistLink`（Pornhub「浏览片单」）、`text-nowrap`（LissAV 页脚 18 U.S.C. 2257）、
     *    `hover:text-ink`（肉视频「2257 聲明」）、`outsidenotice`（禁漫天堂外链公告）、`nav-link`（三级片年份条）；
     *  · URL 形状：`user.htm?author=`（作者页）、`_list-N.htm`（时长筛选条）、`/playlist/`、`/uncensored_makersr/`（ maker 索引）。
     * 这些项**没有封面也没有时长**，上一版靠「像详情页」那道门放行（域名里的数字冒充作品号），
     * 结果在界面上就是一块写着「(无封面URL)」的空卡。
     */
    private val genNavClassRe = Regex(
        """class="[^"]*(page-link|pagination|list-group-item|sugg-pep|menuLink|js-menuAnalytics|""" +
            """viewPlaylistLink|text-nowrap|hover:text-ink|outsidenotice|nav-link|breadcrumb|dropdown-item|""" +
            """label-cat|all-cat|cat-name|mobile-menu-href)""",
        RegexOption.IGNORE_CASE,
    )
    private val genNavUrlRe = Regex(
        """(user\.htm|[?&]author=|_list-\d+\.htm|^/?playlist/|/uncensored_makersr/)""",
        RegexOption.IGNORE_CASE,
    )

    /** JSON 字符串值还原（`\/` → `/`）：xhamster 的 URL 全部写成 `https:\/\/host\/…`。 */
    private fun jsonUnesc(s: String): String =
        s.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")

    /**
     * 内嵌 JSON 状态里的封面回补。xhamster 实测：服务端 HTML 只渲染首屏 15 个 `img`，
     * 其余 35 张卡片在锚点里**根本没有图**，但页面那段 JSON 里 `"pageURL"` 与 `"thumbURL"` 是成对给的
     * （两份样本各 50 / 46 对，与锚点 href 一一对应）——不补就是整屏「无封面」。
     * **只按 URL 精确回填**，不做任何模糊匹配：填错等于把 A 片的封面挂到 B 片上。
     */
    private val jsonCoverRe = Regex(
        """"pageURL"\s*:\s*"((?:[^"\\]|\\.)+)"[^}]{0,600}?"thumbURL"\s*:\s*"((?:[^"\\]|\\.)+)"\s*"""
    )

    private fun fillCoversFromJson(pageUrl: String, html: String, cards: Map<String, GenCard>) {
        if (cards.none { it.value.thumb.isEmpty() }) return
        val byUrl = HashMap<String, String>()
        jsonCoverRe.findAll(html).forEach { m ->
            val page = jsonUnesc(m.groupValues[1])
            val abs = runCatching { URI(pageUrl).resolve(page).toString() }.getOrDefault(page)
            val thumb = cleanThumb(jsonUnesc(m.groupValues[2]), pageUrl)
            if (abs.startsWith("http") && thumb.isNotEmpty()) byUrl[abs.trimEnd('/')] = thumb
        }
        if (byUrl.isEmpty()) return
        for ((u, c) in cards) if (c.thumb.isEmpty()) byUrl[u.trimEnd('/')]?.let { c.thumb = it }
    }

    fun parseGeneric(pageUrl: String, html: String): List<HanimeVideo> {
        val pageHost = bareHost(runCatching { URI(pageUrl).host.orEmpty() }.getOrDefault(""))
        val best = LinkedHashMap<String, GenCard>()
        for (m in genAnchorRe.findAll(html)) {
            val href = m.groupValues[1]
            if (genBadRe.containsMatchIn(href) || !genHintRe.containsMatchIn(href)) continue
            val abs = runCatching { URI(pageUrl).resolve(unescape(href)).toString() }.getOrNull() ?: continue
            if (!abs.startsWith("http")) continue
            val h = bareHost(runCatching { URI(abs).host.orEmpty() }.getOrDefault(""))
            if (pageHost.isNotEmpty() && h.isNotEmpty() && h != pageHost) continue
            if (abs.trimEnd('/') == pageUrl.trimEnd('/')) continue
            if (genListPathRe.containsMatchIn(pathQuery(abs))) continue

            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]
            if (genNavClassRe.containsMatchIn("<a$attrs") || genNavUrlRe.containsMatchIn(pathQuery(abs))) continue
            val innerNoComment = genCommentRe.replace(inner, " ")

            // 封面：内层 img 的 data-* → img src → 内层 background-image → 锚点自身 data-*
            var thumb = ""
            for (img in genImgTagRe.findAll(innerNoComment)) {
                val tag = img.value
                thumb = genDataAttrRe.find(tag)?.groupValues?.get(1)?.let { cleanThumb(it, pageUrl) }.orEmpty()
                if (thumb.isEmpty()) thumb = genSrcAttrRe.find(tag)?.groupValues?.get(1)?.let { cleanThumb(it, pageUrl) }.orEmpty()
                if (thumb.isNotEmpty()) break
            }
            if (thumb.isEmpty()) {
                thumb = genBgImgRe.find(innerNoComment)?.groupValues?.get(1)?.let { cleanThumb(it, pageUrl) }.orEmpty()
            }
            if (thumb.isEmpty()) {
                thumb = genDataAttrRe.find(attrs)?.groupValues?.get(1)?.let { cleanThumb(it, pageUrl) }.orEmpty()
            }
            if (thumb.isEmpty()) {
                // 任意标签上的图片属性（Pornhub 的 `<video data-poster=…>` 就靠这一步）
                thumb = genAnyImgAttrRe.find(innerNoComment)?.groupValues?.get(1)?.let { cleanThumb(it, pageUrl) }
                    .orEmpty()
            }
            if (thumb.isEmpty()) {
                thumb = genAnyImgAttrRe.find(attrs)?.groupValues?.get(1)?.let { cleanThumb(it, pageUrl) }.orEmpty()
            }

            // 标题：内层 img alt → 锚点自身 title/aria-label → 内层任意 title → 内层纯文本
            var title = genAltRe.find(innerNoComment)?.groupValues?.get(1)?.let { unescape(it).trim() }.orEmpty()
            if (isJunkTitle(title)) title = genTitleAttrRe.find(attrs)?.groupValues?.get(1)?.let { unescape(it).trim() }.orEmpty()
            if (isJunkTitle(title)) {
                title = genTitleAttrRe.find(innerNoComment)?.groupValues?.get(1)?.let { unescape(it).trim() }.orEmpty()
            }
            if (isJunkTitle(title)) {
                title = unescape(genTagRe.replace(innerNoComment, " ")).replace(Regex("\\s+"), " ").trim()
            }

            // 时长：内层文本里的 MM:SS / H:MM:SS / N分N秒（标题本身就是时长时不算）
            val dur = genDurRe.find(genTagRe.replace(innerNoComment, " "))?.groupValues?.get(1)?.trim()
                ?.takeIf { it != title }.orEmpty()

            // **不在这里因"标题是垃圾"就丢掉整条**：Jable/18av/xhamster 的封面锚点内文只有时长，
            // 早期版本在这里 continue，等于把同一个 URL 唯一带封面的锚点扔掉，后面标题锚点再来也没图。
            val c = best.getOrPut(abs) { GenCard() }
            if (!isJunkTitle(title) && (c.title.isEmpty() || title.length > c.title.length)) c.title = title.take(160)
            if (c.thumb.isEmpty()) c.thumb = thumb
            if (c.dur.isEmpty()) c.dur = dur
        }
        fillCoversFromJson(pageUrl, html, best)
        return best.mapNotNull { (url, c) ->
            if (isJunkTitle(c.title)) return@mapNotNull null
            // 门槛：没封面又没时长 ⇒ 必须"像详情页"才收，否则导航/分类项会凑满 6 张
            // 让 genericListing 的命中闸门误判成功（好色TV/rouAV/Pornhub/Xanimu 实测都是菜单项凑数）
            // **必须走 detailLike（只看 path+query）**：这里原先直接对整条 URL 判，
            // 于是 `cn.pornhub163.net`、`pb365.nl` 域名里的数字冒充了「作品号」，
            // 菜单项被当成正片卡片显示出来——卡片没有封面，界面上就是一块「(无封面URL)」。
            if (c.thumb.isEmpty() && c.dur.isEmpty() && !detailLike(url)) return@mapNotNull null
            HanimeVideo(
                id = url.takeLast(60), url = url, title = c.title, thumb = c.thumb,
                duration = c.dur, likes = "", views = "", brand = "",
            )
        }
    }

    /** 各站点列表页的候选路径（线路 URL 打不出卡片时依次试，命中后缓存）。
     *  2026-10-01 按 57 份实测样本补齐：CartoonPorno 的真列表是 `/videos?hl=zh`、黄色仓库是
     *  `/index.php/vod/type/id/1.html`、Jable 是 `/latest-updates/`、好色TV 是 `list-1.htm`、
     *  18av 是 `/zh/`、禁漫天堂是 `/albums?o=mv`——原来那 15 条通用候选全都试不到它们。 */
    private val candidatePaths = listOf(
        "/new", "/latest", "/videos", "/hot", "/popular", "/recent", "/new-release", "/newest",
        "/trending", "/v", "/list", "/p/1", "/page/1", "/cn/new", "/index.php/vod/type/id/1",
        "/videos?hl=zh", "/index.php/vod/type/id/1.html", "/latest-updates/", "list-1.htm", "/zh/",
        "/albums?o=mv", "/vodtype/1.html", "/asian/zh-CN", "/enter", "/home", "/pp1/", "/video",
    )

    /**
     * 各站点**实测到的**翻页形式（`%N%` = 页码；`临时\r34_list\` 的逐站对比：第 2 页与第 1 页卡片集合不相交才算生效）。
     * 没列出来的站点退回 `?page=N`（MissAV / SuperPorn / avjoy / 三级片 / LissAV / Pornhub 实测有效）。
     */
    val genericPageForms: Map<String, String> = mapOf(
        "Jable" to "/latest-updates/%N%/",                 // 实测生效；`/enter?page=2` 无效（28 张里 23 张重复）
        "OKXXX" to "/%N%/",                                // 实测 /2/ 60 卡、32 张是新内容
        "xhamster" to "/%N%/",                             // 实测 /2 与 / 交集 0
        "好色TV" to "/list-%N%.htm",                        // 实测 list-1↔list-2 交集 4/25
        "99漫画" to "/albums-index-page-%N%.html",         // 实测交集 0
        "情色小说" to "/page/%N%",                          // 实测交集 0
        "321AV" to "/vodtype/1-%N%.html",                  // 页内「上一页/下一页」= page/1、page/3
        "CartoonPorno" to "/videos?hl=zh&p=%N%",           // 实测 p1=19 / p2=8 / 交集 1
        "肉视频" to "/v?order=createdAt&page=%N%",          // 页内分页器链；`/home?page=2` 无效（101 张里 100 张重复）
        "黄色仓库" to "/index.php/vod/type/id/1/page/%N%.html",  // 页内分页器给出的形式（未逐页验证）
        "rouAV" to "/vod/show/id/4/page/%N%.html",         // 同上
        "禁漫天堂" to "/albums?o=mv&page=%N%",              // markup + rel=next（未逐页验证）
    )

    /** 拼第 page 页：`%N%` 换成页码；表单里没有该站点时用查询式追加。 */
    private fun pageUrlFor(siteName: String, base: String, page: Int): String {
        if (page <= 1) return base
        val cached = setting("x99.pageform.$siteName", "")
        val form = cached.ifBlank { genericPageForms[siteName].orEmpty() }
        val origin = originOf(base)
        val rel = base.removePrefix(origin)
        return when {
            form.isBlank() -> base + (if (base.contains("?")) "&" else "?") + "page=$page"
            form.contains("%N%") && form.startsWith("/") ->
                if (form.startsWith("/?")) origin + rel + form.replace("%N%", page.toString()).substring(1)
                else origin + form.replace("%N%", page.toString())
            else -> base + (if (base.contains("?")) "&" else "?") + form.replace("%N%", page.toString())
        }
    }

    /**
     * 第 page 页有效吗：必须**与第 1 页不几乎相同**。
     * 这一条不是洁癖——站点忽略未知翻页参数时会原样回第 1 页，界面表现就是「加载更多」永远加得动、
     * 内容却全是重复（肉视频 `/home?page=2` 实测 101 张里 100 张重复）。
     */
    private fun pageDistinct(prev: List<HanimeVideo>, next: List<HanimeVideo>): Boolean {
        if (next.isEmpty()) return false
        if (prev.isEmpty()) return true
        val a = prev.map { it.url }.toHashSet()
        val fresh = next.count { it.url !in a }
        return fresh * 2 >= next.size     // 至少一半是新内容
    }

    /**
     * 站点列表（统一卡片 UI）：线路 URL → 已缓存路径 → 候选路径，取第一个能出 ≥6 张卡片的；
     * 命中路径写回设置 `x99.path.<站点>`。全部失败时抛错（界面给兜底入口）。
     */
    fun genericListing(siteName: String, lineUrl: String, page: Int): List<HanimeVideo> {
        val cached = setting("x99.path.$siteName", "")
        val tries = LinkedHashSet<String>()
        if (cached.isNotBlank()) tries.add(cached)
        tries.add(lineUrl)
        val origin = originOf(lineUrl)
        candidatePaths.forEach { tries.add(origin + it) }
        var lastErr: String? = null
        var emptyFallback: List<HanimeVideo>? = null
        for (base in tries) {
            try {
                val u = pageUrlFor(siteName, base, page)
                val cards = parseGeneric(u, getHtml(u, referer = origin + "/"))
                if (page > 1) {
                    // 翻页自校验：与第 1 页几乎相同 ⇒ 这个形式没生效，退回查询式再试一次，
                    // 并把真正生效的形式记进设置（避免每次都白打一发无效请求）
                    if (cards.isNotEmpty() && !pageDistinct(firstPageCache[base] ?: emptyList(), cards)) {
                        val alt = base + (if (base.contains("?")) "&" else "?") + "page=$page"
                        if (alt != u) {
                            val altCards = runCatching { parseGeneric(alt, getHtml(alt, referer = origin + "/")) }
                                .getOrDefault(emptyList())
                            if (altCards.isNotEmpty() && pageDistinct(firstPageCache[base] ?: emptyList(), altCards)) {
                                putSetting("x99.pageform.$siteName", "?page=%N%")
                                return altCards
                            }
                        }
                    }
                }
                if (cards.size >= 6) {
                    if (base != cached) putSetting("x99.path.$siteName", base)
                    // 只把**第 1 页**当作翻页自校验的基线：写成「最近一次取到的页」的话，
                    // 用户翻到第 2 页后基线就变成第 2 页，之后每页都和它比 ⇒ 判据悄悄失效。
                    if (page == 1) firstPageCache[base] = cards
                    return cards
                }
                if (cards.isNotEmpty() && emptyFallback == null && base == tries.first()) emptyFallback = cards
            } catch (t: Throwable) {
                lastErr = t.message ?: t.toString()
            }
        }
        emptyFallback?.let { return it }
        if (lastErr != null && tries.isNotEmpty()) throw IllegalStateException(lastErr)
        return emptyList()
    }

    // ---------- 板块分发（MissAV / xchina / 通用站点走同一组入口，新增原生板块只改这里） ----------

    /** 该站点的页签表；无页签的站点返回空表（界面就不画页签行）。 */
    fun boardTabs(siteName: String): List<Pair<String, String>> = when {
        isXchinaSite(siteName) -> xchinaTabs
        siteName.equals("MissAV", ignoreCase = true) -> missavTabs
        else -> emptyList()
    }

    /**
     * 没有内置栏目表的站，从**列表页现抓**子选项（分类/频道）链接。
     * 只在用户点开「子选项」弹窗时抓一次并缓存 —— 不做自动预取，那些站几乎都有频控。
     * 判据保守：链接必须是站内绝对路径、文字 2~10 字、且路径里带分类特征词，
     * 否则会把一堆无关锚点灌进弹窗（弹窗是给人点的，不是拿来凑数的）。
     */
    private val optionCache = java.util.concurrent.ConcurrentHashMap<String, List<Pair<String, String>>>()
    private val optionPathRe = Regex("""(type|list|cat|genre|tags?|/site/|channel|/v/\w+/)""", RegexOption.IGNORE_CASE)
    private val optionAnchorRe = Regex("""<a[^>]{0,140}?href="([^"]+)"[^>]{0,140}?>([^<]{2,14})</a>""")

    fun boardOptionsLive(siteName: String, base: String): List<Pair<String, String>> {
        boardOptions(siteName).let { if (it.isNotEmpty()) return it }
        optionCache[siteName]?.let { return it }
        val got = runCatching {
            val html = getHtml(base, referer = originOf(base) + "/")
            optionAnchorRe.findAll(html)
                .map { unescape(it.groupValues[2]).replace(Regex("""\s+"""), " ").trim() to it.groupValues[1] }
                .filter { (n, u) -> n.length in 2..10 && u.startsWith("/") && !u.startsWith("//") &&
                    optionPathRe.containsMatchIn(u) }
                .distinctBy { it.second }.take(40).toList()
        }.getOrDefault(emptyList())
        optionCache[siteName] = got
        return got
    }

    /** 该页签已知的最大页（0 = 未知）：供「加载更多」判断终点，别让用户无限点出空页。 */
    fun boardMaxPage(siteName: String, tabPath: String?): Int = when {
        isXchinaSite(siteName) -> xchinaMaxPage(tabPath ?: "/videos/1.html")
        isQinavSite(siteName) -> qinavMaxPage(tabPath ?: "/new.html")
        siteName.equals(GANXIE_NAME, ignoreCase = true) -> gxMaxPage(tabPath ?: "/vl/56.php")
        siteName.equals(CO36_NAME, ignoreCase = true) -> co36MaxPage(tabPath ?: "/")
        else -> 0
    }

    /**
     * 板块的「子选项」清单（用户 2026-10-01 要求：对站内的所有子选项做一个弹窗以便选择）。
     * 有专用栏目表的站直接给；只有通用解析的站从列表页现抓分类链接（见 boardOptionsLive）。
     * ganxiaban 只给 32 条视频栏目，理由写在 [gxCats] 上方。
     */
    fun boardOptions(siteName: String): List<Pair<String, String>> = when {
        isQinavSite(siteName) -> listOf("最新" to "/new.html", "热门" to "/hot.html") + qinavCats
        isXchinaSite(siteName) -> xchinaTabs
        siteName.equals(GANXIE_NAME, ignoreCase = true) ->
            listOf("最新·亚洲有码" to "/vl/56.php") + gxCats
        siteName.equals(CO36_NAME, ignoreCase = true) -> listOf("首页·最新" to "/") + co36Cats
        siteName.equals("MissAV", ignoreCase = true) -> missavTabs
        else -> emptyList()
    }

    /**
     * 这条 URL 属于哪个板块的**显示名**（本地收藏要按板块分组，图1 那种 chip 筛选）。
     * 认不出的一律回主机名，不硬编成"未知板块"——用户要能看出那是哪个站。
     */
    fun boardLabelFor(u: String): String {
        val host = runCatching { URI(u).host.orEmpty() }.getOrDefault("")
        val r = rootDomain(u)
        return when {
            r == "xchina" || isXchinaUrl(u) -> XCHINA_NAME
            isQinavUrl(u) || r == "qinav" -> QINAV_NAME
            r in gxRoots -> GANXIE_NAME
            r == "36co" -> CO36_NAME
            r == "ininav" -> ININAV_NAME
            isMissavVideoUrl(u) || r.startsWith("missav") || r.startsWith("javrocket") -> "MissAV"
            isJableUrl(u) -> "Jable"
            isRouVideoUrl(u) -> "肉视频"
            isLissavUrl(u) -> "LissAV"
            isAvjoyUrl(u) -> "avjoy"
            isSuperpornUrl(u) -> "SuperPorn"
            is591avUrl(u) -> "亚洲素人精选"
            isOkxxxUrl(u) -> "OKXXX"
            isEmbedPlayerUrl(u) -> "黄色仓库"
            host.isNotEmpty() -> bareHost(host)
            else -> ""
        }
    }

    /**
     * 该地址是否属于「有原生详情能力」的板块。**这是卡片点击与详情路由的唯一判据**：
     * 上一版这里按「当前板块名」判定，而板块名从未被赋值过，于是列表卡片那侧用站点名放行、
     * 路由这侧用 URL 拦下，MissAV 的卡片被喂给了官方解析器（详见 2026-10-01 审查记录）。
     * 判不准的代价是「先试原生、拿不到直链就回落网页版」，不会给出播不动的死链。
     *
     * 覆盖面（**每一家都有实测的媒体响应取证**，不是只看页面里出现过 .m3u8 字符串）：
     * xchina、qinav、ganxiaban、MissAV、Jable、肉视频、LissAV、avjoy、SuperPorn、亚洲素人精选、OKXXX、
     * 黄色仓库（iframe 族）、36co（maccms 直写 `player_aaaa`）——**家数只能由 [nativeFamilyProbe] 数出来**，
     * 这句注释里的数字不算数（第 44 轮就是因为它和表里的样本各写一遍而长期少报两家）。
     * 取证目录：`临时\r34_play\`、`临时\xchina\`、`临时\r35_familyA\`、`临时\r35_familyB\`。
     * 其余板块（321AV / 18av / 大哥视频 / VJAV / xhamster / Pornhub / 图集站 …）继续走网页版播放，**不假装能原生化**。
     */
    fun isNativeBoardUrl(u: String): Boolean = boardOf(u) != null

    /**
     * 有原生内嵌播放能力的板块家族数（「信息」页要说这句，所以判据只能有一个来源：
     * 就是 [boardOf] 那张表，不能另数一遍——另数就会和实际路由不一致）。
     */
    fun nativeBoardCount(): Int = NATIVE_BOARD_FAMILIES

    /** 家族表里每一条样本 URL 判到什么家族（数不对时先看这个，别猜）。 */
    fun nativeFamilyProbe(): List<Pair<String, String?>> = NATIVE_FAMILY_SAMPLES.map { it to boardOf(it) }

    private val NATIVE_FAMILY_SAMPLES: List<String> = listOf(
        "https://xchina.co/videos/1.html", "https://www.qinav.com/video/1.html",
        "https://www.ganxiaban.com/v/1.php", "https://missav.asia/cn/abc-1",
        "https://jable666.com/videos/abc-1/", "https://rou888.net/v/cmu8ng3q60004s6x3byujkmig",
        "https://huangguo01.net/asian/zh-CN/video/cid/simm-412",
        "https://cn.avjoy.ws/video/14614/mird-136",
        "https://cn.superporn.ws/video/10650", "https://591av.cc/v/abc123/",
        "https://okxxx.com/video/1/", "https://69ck.com/index.php/vod/play/id/1",
        "http://www.36co.cc/index.php/vod/play/id/38936/sid/1/nid/1.html",
    )

    /**
     * 「有几家支持原生」= 家族表 [NATIVE_FAMILY_SAMPLES] 判出来的家族去重数。
     * 样本必须用**取证里真实出现过的 URL**（`临时\r34_list\`、`临时\r35_familyA\`）：
     * 第 44 轮之前这里两条是照记忆写的（avjoy 少一段 `/video/`、superporn 写成 `/videos/` 和 .com），
     * 于是这两条一直判 null，那句话长期少报两家——而板块本身其实是通的，只有计数在说谎。
     */
    private val NATIVE_BOARD_FAMILIES: Int by lazy {
        setOfNotNull(*nativeFamilyProbe().map { it.second }.toTypedArray()).size
    }

    /** 该 URL 属于哪个原生板块；null = 没有原生详情能力（走网页版）。判据只写一遍，卡片门与详情路由共用。 */
    private fun boardOf(u: String): String? = when {
        isXchinaUrl(u) -> "xchina"
        isQinavUrl(u) -> "qinav"
        isGanxiabanUrl(u) -> "ganxiaban"
        isCo36Url(u) -> "co36"
        isMissavVideoUrl(u) -> "missav"
        isJableUrl(u) -> "jable"
        isRouVideoUrl(u) -> "rouvideo"
        isLissavUrl(u) -> "lissav"
        isAvjoyUrl(u) -> "avjoy"
        isSuperpornUrl(u) -> "superporn"
        is591avUrl(u) -> "591av"
        isOkxxxUrl(u) -> "okxxx"
        isEmbedPlayerUrl(u) -> "embed"
        else -> null
    }

    /**
     * 板块详情（原生）：十二家的播址取法各不相同（xchina 直接写在详情页 script 里，MissAV 藏在 Dean-Edwards
     * 打包脚本里，Jable 是内联 `hlsUrl`，肉视频是 base64+字节位移的内嵌状态**再经一次派发口选线**，
     * LissAV 要走一次 stream API，avjoy/SuperPorn/OKXXX 是静态 `<source>`，黄色仓库族是播放器 iframe 的参数，
     * 亚洲素人精选是 `var stream`，qinav 是 `/embed/` 里的 `?url=`，ganxiaban 是 `var player=unescape()` →
     * 百分号还原 → 十六进制还原 → 照站点自己的 replace 链重写 host）。
     * 任何一环拿不到**验证过的**可播清单就返回 null，让界面走既有网页版路径——没把握就不假装能给。
     */
    fun boardDetail(v: HanimeVideo): HanimeDetail? = when (boardOf(v.url)) {
        "xchina" -> runCatching { xchinaDetail(v) }.getOrNull()
        "qinav" -> runCatching { qinavDetail(v) }.getOrNull()
        "ganxiaban" -> runCatching { gxDetail(v) }.getOrNull()
        "co36" -> runCatching { co36Detail(v) }.getOrNull()
        "missav" -> runCatching { missavDetail(v) }.getOrNull()
        "jable" -> runCatching { jableDetail(v) }.getOrNull()
        "rouvideo" -> runCatching { rouVideoDetail(v) }.getOrNull()
        "lissav" -> runCatching { lissavDetail(v) }.getOrNull()
        "avjoy", "superporn" -> runCatching { staticSourceDetail(v) }.getOrNull()
        "591av" -> runCatching { streamVarDetail(v) }.getOrNull()
        "okxxx" -> runCatching { okxxxDetail(v) }.getOrNull()
        "embed" -> runCatching { embedPlayerDetail(v) }.getOrNull()
        else -> null
    }

    /** 板块列表：一次调用只发一发请求（各站都有频控，见 xchina 的 Cloudflare 记录）。 */
    fun boardListing(siteName: String, base: String, tabPath: String?, page: Int): List<HanimeVideo> = when {
        isXchinaSite(siteName) -> xchinaListing(base, tabPath ?: "/videos.html", page)
        isQinavSite(siteName) -> qinavListing(base, tabPath ?: "/new.html", page)
        siteName.equals(GANXIE_NAME, ignoreCase = true) -> gxListing(base, tabPath ?: "/vl/56.php", page)
        siteName.equals(CO36_NAME, ignoreCase = true) -> co36Listing(base, tabPath, page)
        siteName.equals("MissAV", ignoreCase = true) ->
            missavListing(originOf(base), tabPath ?: "/cn/new", page)
        else -> genericListing(siteName, base, page)
    }

    /** 常见搜索入口形式（按命中率排序；探测成功后写进设置，下次不再猜）。 */
    private val searchForms = listOf(
        { o: String, k: String -> "$o/search/$k" },
        { o: String, k: String -> "$o/?s=$k" },
        { o: String, k: String -> "$o/search?q=$k" },
        { o: String, k: String -> "$o/search?keyword=$k" },
        { o: String, k: String -> "$o/index.php/vod/search.html?wd=$k" },
        { o: String, k: String -> "$o/index.php/vod/search/page/1/wd/$k.html" },
        { o: String, k: String -> "$o/search.php?searchword=$k" },
    )

    /**
     * 板块搜索。MissAV / xchina 有实测过的入口；其它站点用**已缓存的形式**，没有缓存才探测
     * （每站最多 `searchForms.size` 发，命中即缓存 `x99.search.<站点>`，探不到就记 `none` 不再重试）。
     * 返回 null 表示「该板块没有可用搜索入口」，与「搜到 0 条」区分开——聚合搜索要如实报出来。
     */
    fun boardSearch(
        siteName: String,
        base: String,
        kw: String,
        page: Int,
        allowProbe: Boolean = true,
    ): List<HanimeVideo>? {
        val origin = originOf(base)
        val enc = URLEncoder.encode(kw, "UTF-8")
        return when {
            isXchinaSite(siteName) -> xchinaSearch(base, kw)
            siteName.equals("MissAV", ignoreCase = true) -> missavSearch(origin, kw, page)
            // qinav **没有可用的服务端搜索**，这里必须写死拒绝，不能让它去猜形式：
            // 实测首页表单指的 `/?module=video&action=keyword&keyword=…` 与 `/?module=tags&…`，
            // 用真关键词 SSIS-698、必然无结果的乱码、中文词三种查询**回的是同一批 40 张卡**、
            // 标题都是 `Error` —— 那是带热门列表的错误页。让聚合搜索去吃它，界面就会报「40 条」，
            // 把「没这个功能」谎报成「搜到了 40 条」。
            isQinavSite(siteName) -> null
            // ganxiaban 同样**没有可用的服务端搜索**，必须写死拒绝（`REPORT.md` §4「搜索入口」）：
            // 页面里的 `search()` 在站点下发的任何一份正文里都没有定义体（只外链 jquery），点了不跳；
            // `/search.php?wd=` 在 ganxiaban / mei78 / avtt3581 三站、用「字幕」「无码」两个不同关键词，
            // 回的都是**同一组与关键词无关的 9 个 id**（562,1547,…），而那 9 个 id 落到的介绍页没有媒体块。
            // 让它进探测队列，聚合搜索就会把「没这个功能」谎报成「搜到 9 条」。
            siteName.equals(GANXIE_NAME, ignoreCase = true) -> null
            else -> {
                val key = "x99.search.$siteName"
                when (val cached = setting(key, "")) {
                    "none" -> null
                    // 没缓存过搜索形式时**默认不探测**：探测最坏要发 searchForms.size 发请求，
                    // 聚合搜索一次覆盖几十个站点 ⇒ 首次搜索就是上百发，足够把出口 IP 打进各家频控黑名单。
                    // 改成由用户在结果里点该板块名，单发一次探测并永久缓存。
                    "" -> if (allowProbe) probeSearch(siteName, origin, enc, key) else null
                    else -> runCatching {
                        val u = withPage(cached.replace("%KW%", enc), page)
                        parseGeneric(u, getHtml(u))
                    }.getOrNull()
                }
            }
        }
    }

    /**
     * 第 page 页的 URL。
     *
     * maccms 一族（36co / 黄色仓库）的搜索分页是**把页码写在路径里**的：
     * 站内分页器自己给出的就是 `/index.php/vod/search/page/144/wd/<kw>.html`（取证见
     * `临时\r43_36co\pg_index_php_vod_search_html_wd_*.html`），追加 `?page=2` 它不认，
     * 于是「加载更多」会一遍遍拿回同一页。
     */
    private fun withPage(u: String, page: Int): String {
        if (page <= 1) return u
        val maccms = Regex("""^(.*/vod/search/)page/\d+(/.*)""").find(u)
        if (maccms != null) return maccms.groupValues[1] + "page/$page" + maccms.groupValues[2]
        return u + (if (u.contains("?")) "&" else "?") + "page=$page"
    }

    /**
     * 逐个试搜索入口，第一个能出卡片的形式记下来（`%KW%` 占位）。全部失败记 `none`，此后不再重试。
     * **只在用户显式点按某个板块时调用**（见 boardSearch 的 allowProbe）；请求数上限 = searchForms.size。
     */
    private fun probeSearch(siteName: String, origin: String, enc: String, key: String): List<HanimeVideo>? {
        // 「这个站没有搜索」和「这一发没拿到东西」是两件事，必须分开：写进设置的 `none` 会让
        // boardSearch 从此不再探测，等于一次抖动/一发 CF 挑战就把该板块的搜索永久判死。
        var answered = false
        for (form in searchForms) {
            val u = form(origin, enc)
            val cards = runCatching {
                val html = getHtml(u, referer = "$origin/")
                // CF 挑战页/硬封页也是 **HTTP 200 + 一段正文**，状态码判不出来（与 MissAV 的诱饵 JPEG 同一类坑）：
                // 它解析不出卡片，但不能因此认定该站没有搜索入口。
                if (cfBlockRe.containsMatchIn(html)) null else parseGeneric(u, html)
            }.getOrNull()
            if (cards == null) continue
            answered = true
            if (cards.isNotEmpty()) {
                putSetting(key, form(origin, "%KW%"))
                return cards
            }
        }
        if (answered) putSetting(key, "none")
        return null
    }

    /** 各列表基址的第 1 页结果（翻页自校验要拿它对比，不然无法判断第 2 页是不是复制品）。 */
    private val firstPageCache = java.util.concurrent.ConcurrentHashMap<String, List<HanimeVideo>>()

    /** 并发测速并返回最快线路（全部失败返回 null）。 */
    fun fastestLine(lines: List<X99Line>): X99Line? {
        if (lines.isEmpty()) return null
        if (lines.size == 1) return lines.first()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(8, lines.size))
        return try {
            val futures = lines.map { ln ->
                pool.submit(java.util.concurrent.Callable { (if (ln.testUrl.isBlank()) -1L else testLine(ln)) to ln })
            }
            futures.mapNotNull { runCatching { it.get() }.getOrNull() }
                .filter { it.first >= 0 }
                .minByOrNull { it.first }?.second
        } finally {
            pool.shutdown()
        }
    }
}

/** 供缓存文件序列化（避免引入额外依赖，手写最小 JSON）。 */
internal fun x99Json(list: List<Map<String, Any>>): String {
    val sb = StringBuilder("[")
    list.forEachIndexed { i, m ->
        if (i > 0) sb.append(',')
        sb.append("{\"name\":\"").append(esc(m["name"] as? String ?: "")).append("\",")
        sb.append("\"description\":\"").append(esc(m["description"] as? String ?: "")).append("\",")
        sb.append("\"url\":\"").append(esc(m["url"] as? String ?: "")).append("\",")
        sb.append("\"lines\":[")
        @Suppress("UNCHECKED_CAST")
        (m["lines"] as? List<List<String>>).orEmpty().forEachIndexed { j, l ->
            if (j > 0) sb.append(',')
            sb.append("[\"").append(esc(l.getOrElse(0) { "" })).append("\",\"")
                .append(esc(l.getOrElse(1) { "" })).append("\"]")
        }
        sb.append("]}")
    }
    return sb.append(']').toString()
}

private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
