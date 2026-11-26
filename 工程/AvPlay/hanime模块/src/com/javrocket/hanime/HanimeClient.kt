package com.javrocket.hanime

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/** hanime1.me 视频（列表卡片与相关推荐共用）。 */
data class HanimeVideo(
    val id: String,
    val url: String,
    val title: String,
    val thumb: String,
    val duration: String,
    val likes: String,
    val views: String,
    val brand: String,
)

/** hanime1.me 详情页。 */
data class HanimeDetail(
    val video: HanimeVideo,
    val description: String,
    val tags: List<String>,
    val sources: List<Pair<Int, String>>, // 清晰度 -> 直链（带 secure 参数，有时效）
    val related: List<HanimeVideo>,
    val isFav: Boolean = false,
    val isSaved: Boolean = false,
    val saveListId: String = "",
)

/** 首页分区入口（含该分区卡片，供分行展示）。 */
data class HanimeSection(val title: String, val url: String, val cards: List<HanimeVideo> = emptyList())

/**
 * 账号资料（用户页）。字段全部来自 `/user/<uid>` 的原文，没有的字段留空串而不是编一个：
 * 站点不公开邮箱/积分/徽章/私信，所以这里也没有这些字段。
 */
data class HanimeProfile(
    val uid: String,
    val name: String,
    val avatar: String,
    val avatarLarge: String,
    val stats: String,
    val home: String,
    val edit: String,
    val playlists: String,
)

/** 首页顶部导航分区（网站 navbar 上的 genre 链接）。 */
data class HanimeNavItem(val label: String, val url: String)

/**
 * hanime1.me 客户端：OkHttp + CookieJar（登录态持久化到 %LOCALAPPDATA%\AvPlay）。
 * hanime1.me 为 Laravel 应用：登录 = GET /login 取 _token -> POST /login；播放直链匿名可播。
 */
object HanimeClient {
    const val BASE = "https://hanime1.me"
    const val HOST = "hanime1.me"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val cookieFile: File by lazy {
        val base = System.getenv("LOCALAPPDATA")
            ?: (System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local")
        File(File(base, "AvPlay").apply { mkdirs() }, "hanime_cookies.properties")
    }
    private val cookieMap = LinkedHashMap<String, String>()
    private var cookiesLoaded = false

    private fun loadCookies() {
        if (cookiesLoaded) return
        synchronized(cookieMap) {
            if (!cookiesLoaded) {
                runCatching {
                    if (cookieFile.exists()) {
                        val p = Properties()
                        cookieFile.inputStream().use { p.load(it) }
                        p.forEach { (k, v) -> cookieMap[k.toString()] = v.toString() }
                    }
                }
                cookiesLoaded = true
            }
        }
    }

    private fun persistCookies() {
        runCatching {
            val p = Properties()
            synchronized(cookieMap) { cookieMap.forEach { (k, v) -> p.setProperty(k, v) } }
            cookieFile.outputStream().use { p.store(it, "hanime1.me cookies") }
        }
    }

    fun clearCookies() {
        synchronized(cookieMap) { cookieMap.clear() }
        runCatching { cookieFile.delete() }
    }

    private val jar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            loadCookies()
            return synchronized(cookieMap) {
                cookieMap.filterKeys { !it.startsWith("_") }.map { (k, v) ->
                    Cookie.Builder().name(k).value(v).domain(HOST).path("/").build()
                }
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieMap) { cookies.forEach { c -> cookieMap[c.name] = c.value } }
            persistCookies()
        }
    }

    // ---------- 网络设置（DoH 开关 / 代理模式）与代理自动探测 ----------

    @Volatile
    var dohEnabled: Boolean = true // 默认开启（用户 2026-09-29 要求）；仅作用于 hanime1.me 官方源，优选/镜像线路一律直连
        private set

    @Volatile
    var proxyMode: String = "auto" // auto | direct | manual
        private set

    @Volatile
    var manualProxy: String = ""
        private set

    /** 播放中继：VLC/内嵌播放器改为播放 localhost，数据由客户端按代理设置拉取（解决"VLC 不走代理"）。 */
    @Volatile
    var relayEnabled: Boolean = true
        private set

    /** 全局代理：检测到本机 HTTP 代理时，让应用自身板块（小黄书 / JavDB 等）也走代理。 */
    @Volatile
    var globalProxyEnabled: Boolean = true
        private set

    @Volatile
    private var globalProxyDesc: String = "(未检测)"

    private val settingsFile: File by lazy {
        File(cookieFile.parentFile, "hanime_settings.properties")
    }

    @Volatile
    private var settingsLoaded = false

    fun loadSettings() {
        if (settingsLoaded) return
        synchronized(this) {
            if (settingsLoaded) return
            runCatching {
                if (settingsFile.exists()) {
                    val p = Properties()
                    settingsFile.inputStream().use { p.load(it) }
                    dohEnabled = (p.getProperty("doh.enabled") ?: "true").toBoolean()
                    proxyMode = p.getProperty("proxy.mode") ?: "auto"
                    manualProxy = p.getProperty("proxy.manual") ?: ""
                    relayEnabled = (p.getProperty("relay.enabled") ?: "true").toBoolean()
                    globalProxyEnabled = (p.getProperty("global.proxy") ?: "true").toBoolean()
                    edgeHostsEnabled = (p.getProperty("hosts.enabled") ?: "true").toBoolean()
                    edgeCustomIps = p.getProperty("hosts.custom") ?: ""
                    edgeNode = p.getProperty("hosts.node") ?: ""
                    // 一次性迁移（schema 4）：DoH 强制置为「开」（用户要求默认开启；仅作用于官方源）
                    if (p.getProperty("schema") != "4") {
                        dohEnabled = true
                        p.setProperty("schema", "4")
                        p.setProperty("doh.enabled", "true")
                        p.setProperty("proxy.mode", proxyMode)
                        p.setProperty("relay.enabled", relayEnabled.toString())
                        // 保留其余自定义键（如 cdn.relay.port、x99.site、hosts.* 等）
                        settingsFile.outputStream().use { p.store(it, "hanime1.me network settings") }
                    }
                }
            }
            settingsLoaded = true
        }
    }

    fun updateSettings(
        doh: Boolean = dohEnabled,
        mode: String = proxyMode,
        manual: String = manualProxy,
        relay: Boolean = relayEnabled,
        globalProxy: Boolean = globalProxyEnabled,
    ) {
        loadSettings()
        dohEnabled = doh
        proxyMode = mode
        manualProxy = manual.trim()
        relayEnabled = relay
        globalProxyEnabled = globalProxy
        runCatching {
            // 合并式写入：保留设置文件里的其它键（优选线路、站点选择等由 X99 维护）
            val p = Properties()
            if (settingsFile.exists()) settingsFile.inputStream().use { p.load(it) }
            p.setProperty("schema", "3")
            p.setProperty("doh.enabled", doh.toString())
            p.setProperty("proxy.mode", proxyMode)
            p.setProperty("proxy.manual", manualProxy)
            p.setProperty("relay.enabled", relay.toString())
            p.setProperty("global.proxy", globalProxy.toString())
            settingsFile.parentFile?.mkdirs()
            settingsFile.outputStream().use { p.store(it, "hanime1.me network settings") }
        }
        reprobeProxy()
    }

    @Volatile
    private var chosenProxy: java.net.Proxy? = null

    @Volatile
    private var chosenProxyDesc: String = "(未探测)"

    @Volatile
    private var probed = false

    fun reprobeProxy() {
        probed = false
        chosenProxy = null
        chosenProxyDesc = "(未探测)"
    }

    private val proxyCandidates = listOf(
        "http://127.0.0.1:10808", // v2rayN mixed 口（socks+http 双协议）
        "http://127.0.0.1:10809", // v2rayN http 口
        "socks5://127.0.0.1:10808",
        "http://127.0.0.1:7890",
        "http://127.0.0.1:10810",
        "http://127.0.0.1:8889",
    )

    private fun tcpAlive(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(host, port), 400); true }
    }.getOrDefault(false)

    private fun parseProxy(spec: String): java.net.Proxy? = runCatching {
        val raw = if (spec.contains("://")) spec else "http://$spec"
        val u = java.net.URI(raw)
        val host = u.host ?: return@runCatching null
        val scheme = (u.scheme ?: "http").lowercase()
        val port = if (u.port > 0) u.port else if (scheme.startsWith("socks")) 1080 else 8080
        val type = if (scheme.startsWith("socks")) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
        java.net.Proxy(type, java.net.InetSocketAddress(host, port))
    }.getOrNull()

    /** 自动模式：按候选顺序探测本机代理端口（TCP 连通即采用），HTTP 优先（让 DoH 生效）。 */
    private fun probeProxy(): java.net.Proxy? {
        synchronized(this) {
            if (probed) return chosenProxy
            val list = buildList {
                addAll(proxyCandidates)
                val explicit = System.getProperty("socksProxyHost")
                if (!explicit.isNullOrBlank()) {
                    val p = System.getProperty("socksProxyPort")?.toIntOrNull() ?: 1080
                    add("http://$explicit:$p")
                    add("socks5://$explicit:$p")
                }
            }.distinct()
            for (spec in list) {
                val proxy = parseProxy(spec) ?: continue
                val addr = proxy.address() as java.net.InetSocketAddress
                if (tcpAlive(addr.hostString, addr.port)) {
                    chosenProxy = proxy
                    chosenProxyDesc = spec
                    break
                }
            }
            if (chosenProxy == null) chosenProxyDesc = "直连（未发现本机代理）"
            probed = true
            return chosenProxy
        }
    }

    private fun proxyFor(host: String): java.net.Proxy? {
        loadSettings()
        if (host.isEmpty()) return null
        val isHanime = host.endsWith("hanime1.me") || host.endsWith(".base")
                || host == "1.1.1.1" || host == "223.5.5.5"
        if (!isHanime) return null
        return when (proxyMode) {
            "direct" -> null
            "manual" -> parseProxy(manualProxy)
            else -> probeProxy()
        }
    }

    private val proxySelector: java.net.ProxySelector = object : java.net.ProxySelector() {
        override fun select(uri: java.net.URI): List<java.net.Proxy> {
            val p = proxyFor(uri.host.orEmpty())
            return if (p != null) listOf(p) else listOf(java.net.Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: java.net.URI, sa: java.net.SocketAddress, ioe: java.io.IOException) {}
    }

    val http: OkHttpClient by lazy {
        loadSettings()
        OkHttpClient.Builder()
            .cookieJar(jar)
            .dns(hanimeDns)
            .proxySelector(proxySelector)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // 第 51 轮：板块与介绍页的加载速度。
            //
            // 这两样**不是**网络口径的改动，只是**连接复用**参数 ——
            // DoH / 边缘节点 / 代理选择 / cookie / 拦截器全部照旧，与官方源无关。
            //  · 连接池：OkHttp 默认只留 5 条空闲、5 分钟。
            //    而板块页 + 介绍页会连着打同一个域好几发（列表、详情、封面元数据），
            //    默认池一满就出现「连接刚建好又关掉、下一次重新握手」——
            //    TLS 握手比复用昂贵一个量级，这就是「进去要等一下」的主因。
            //  · Dispatcher：默认 maxRequestsPerHost = 5，板块页同时拉几条就会被自己排队。
            //    放到 8 与封面专用客户端口径一致（互不干扰：封面那个是 newBuilder 派生的独立实例）。
            // 第 54 轮：板块响应加速（用户要求「优化各个板块响应加载速度，减少转圈等待时间」）。
            // 仍然**只动连接复用**参数 —— 域名 / DoH / 代理 / cookie / 超时一律不变。
            //  · 池 16→24、空闲 5→10 分钟：切板块、回列表反复打同一个域时握手一次就能一直复用；
            //  · maxRequestsPerHost 8→12：板块页一屏要拉列表 + 多条数据，被自己的调度器排队
            //    就是「转圈等」的直接来源（OkHttp 默认只给同域 5 个并发）。
            .connectionPool(okhttp3.ConnectionPool(24, 10, TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 48; maxRequestsPerHost = 12 })
            .retryOnConnectionFailure(true)
            .eventListenerFactory { object : okhttp3.EventListener() {
                override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                    lastReq = "connect ${inetSocketAddress} via $proxy"
                }
                override fun connectFailed(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?, ioe: java.io.IOException) {
                    lastReq = "connectFailed ${inetSocketAddress} via $proxy url=${call.request().url} : ${ioe.message}"
                }
            } }
            .build()
    }

    /**
     * 封面专用客户端（2026-10-02 第 43 轮：用户要求「优化各板块封面加载速度」）。
     *
     * 从 [http] `newBuilder()` **派生**，所以 DoH / 边缘节点 / 代理选择 / cookie / 拦截器全部继承，
     * 不是另起一套网络策略，也不是给官方源换了口径。只改三处与「一屏几十张图」这件事相关的参数：
     *  · 连接超时 20 s → 8 s、读超时 30 s → 12 s：某张图的图床挂了，不该让那张卡转半分钟；
     *  · 连接池 12 条、空闲留 5 min：一屏同域封面十几张，握手一次就该被复用
     *    （默认只留 5 条空闲，并发一高就出现「新连接建了又马上关掉、下一张重新握手」）；
     *  · 自己一套 Dispatcher：与接口请求分开的排队口径，调它不会动官方源接口的并发。
     */
    val thumbHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(12, 5, TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 24; maxRequestsPerHost = 8 })
            .build()
    }

    /** 同时最多 8 发封面：闸门比"不限并发"更快——几十张图一起抢带宽时每张都出不来。 */
    private val thumbGate = Semaphore(8)

    /** 某图床两次都取不到之后，这 20 s 内同域封面**直接判失败**，不再让每张各烧一次完整超时。 */
    private val thumbDeadUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 图床的防盗链判据是「要 Referer」还是「不许带 Referer」，实测过两种相反的站，
     * 所以这里记住**这个域上次是哪一种写法成功的**，下次先发那一种。上一版每张图最坏两趟往返就是这么来的。
     */
    private val thumbRefererFirst = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 最近一次封面失败的人话原因（真机抑制日志，界面把这句画在卡面上）。 */
    @Volatile
    var lastThumbError: String = ""
        private set

    /** 单发取图。成功给字节，失败给一句原因；**不抛异常**，由调用方决定要不要换一种 Referer 写法。 */
    private fun thumbAttempt(url: String, host: String, withRef: Boolean): Pair<ByteArray?, String> {
        val rb = okhttp3.Request.Builder().url(url).header("User-Agent", UA)
        if (withRef) {
            val origin = runCatching { "${java.net.URI(url).scheme}://$host" }.getOrDefault("")
            if (origin.isNotEmpty()) rb.header("Referer", "$origin/")
        }
        return runCatching {
            thumbHttp.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    null to "HTTP ${resp.code}${if (withRef) "(带Referer)" else "(无Referer)"}"
                } else {
                    val bytes = resp.body.bytes()
                    if (bytes.size < 64) null to "正文太小(${bytes.size}B)" else bytes to ""
                }
            }
        }.getOrElse { null to "请求异常 ${it.message?.take(48)}" }
    }

    /**
     * 取一张封面的字节。返回 `(字节?, 失败原因)`，原因为空即成功。
     *
     * 顺序是「记忆里的写法 → 另一种」，不是一律两发；连续两次都失败的域先冷 20 s（见 [thumbDeadUntil]），
     * 免得整屏卡片每张都去烧满超时——那正是「封面迟迟出不来」的主体耗时。
     */
    fun fetchThumbBytesSync(url: String): Pair<ByteArray?, String> {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        if (host.isBlank()) return null to "封面地址无主机"
        if ((thumbDeadUntil[host] ?: 0L) > System.currentTimeMillis()) return null to "图床 $host 刚连不上，暂不发卡"
        val order = if (thumbRefererFirst[host] == false) listOf(false, true) else listOf(true, false)
        var why = "未取到"
        for (withRef in order) {
            val (bytes, err) = thumbAttempt(url, host, withRef)
            if (bytes != null) {
                thumbRefererFirst[host] = withRef
                lastThumbError = ""
                return bytes to ""
            }
            why = err
        }
        thumbDeadUntil[host] = System.currentTimeMillis() + 20_000L
        lastThumbError = "$host: $why"
        return null to lastThumbError
    }

    /** [fetchThumbBytesSync] 的挂起外壳：并发闸门在这里生效（同步入口留给宿具与冷路径直接用）。 */
    suspend fun fetchThumbBytes(url: String): Pair<ByteArray?, String> =
        thumbGate.withPermit {
            withContext(Dispatchers.IO) { fetchThumbBytesSync(url) }
        }

    /** 诊断：当前出网方式描述（展示在设置面板里）。 */
    fun netDiag(): String {
        loadSettings()
        val modeTxt = when (proxyMode) {
            "direct" -> "直连(手动)"
            "manual" -> "手动 ${manualProxy.ifBlank { "(未填)" }}"
            else -> chosenProxyDesc
        }
        return "代理:$modeTxt | DoH:${if (dohEnabled) "开" else "关"}(最近:$lastDoh) | " +
            "Hosts:${if (edgeHostsEnabled) (edgeNode.ifBlank { "内置全部" }) else "关"} | 末次请求:$lastReq"
    }

    @Volatile
    private var lastDoh: String = "(未查询)"

    // ---------- 内置 Hosts / 边缘节点（参考 Han1meViewer 的 HDns，仅作用于 hanime 官方源） ----------

    /** 覆盖的官方域名（与 Han1meViewer 的 HANIME_HOSTNAME 同集合）。 */
    private val officialHosts = listOf("hanime1.me", "hanime1.com", "hanimeone.me", "javchu.com")

    /** 内置边缘节点（Cloudflare 边缘 IP，取自 Han1meViewer `HDns.cloudFlareIps`）。 */
    val builtInNodes: List<String> = listOf(
        "172.64.229.154", "162.159.0.1", "108.162.192.1", "172.64.33.1", "104.19.0.1",
        "2606:4700:3035::ac43:bb8d", "2606:4700:3030::6815:746", "2606:4700:3030::6815:714",
    )

    @Volatile
    var edgeHostsEnabled: Boolean = true // 内置 Hosts 边缘节点加速（默认开；只影响 hanime 官方源）
        private set

    @Volatile
    var edgeCustomIps: String = "" // 自定义 Hosts（逗号分隔 IP；非空时优先于内置节点）
        private set

    @Volatile
    var edgeNode: String = "" // 每次启动自动测速选出的最快节点
        private set

    /** 最近一次节点测速结果（ip → ms，-1 不可用；供设置面板直接展示启动时的结果）。 */
    @Volatile
    var lastNodeTest: Map<String, Long> = emptyMap()
        private set

    /** 是否属于 hanime 官方源域名（内置 Hosts 只对它们生效；优选镜像/其它板块不受影响）。 */
    fun isOfficialHost(hostname: String): Boolean =
        officialHosts.any { hostname.equals(it, true) || hostname.endsWith(".$it", true) }

    /** 当前是否在用本机代理（走代理时由代理解析，边缘节点不参与）。 */
    fun proxyInUse(): Boolean {
        loadSettings()
        return proxyMode != "direct" && chosenProxy != null
    }

    fun updateEdgeHosts(enabled: Boolean, customIps: String) {
        loadSettings()
        edgeHostsEnabled = enabled
        edgeCustomIps = customIps.trim()
        runCatching {
            val p = Properties()
            if (settingsFile.exists()) settingsFile.inputStream().use { p.load(it) }
            p.setProperty("hosts.enabled", enabled.toString())
            p.setProperty("hosts.custom", edgeCustomIps)
            settingsFile.parentFile?.mkdirs()
            settingsFile.outputStream().use { p.store(it, "hanime1.me network settings") }
        }
    }

    /** 手动指定节点（点击节点列表时调用）。 */
    fun setEdgeNode(ip: String) = saveEdgeNode(ip.trim())

    private fun saveEdgeNode(ip: String) {
        edgeNode = ip
        runCatching {
            val p = Properties()
            if (settingsFile.exists()) settingsFile.inputStream().use { p.load(it) }
            p.setProperty("hosts.node", ip)
            settingsFile.parentFile?.mkdirs()
            settingsFile.outputStream().use { p.store(it, "hanime1.me network settings") }
        }
    }

    /** DNS 用的节点候选：自定义 Hosts 优先 → 自动选中的最快节点 → 空（表示用全部内置节点）。 */
    private fun edgeCandidates(): List<String> {
        val custom = edgeCustomIps.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (custom.isNotEmpty()) return custom
        val picked = edgeNode.trim()
        return if (picked.isNotEmpty()) listOf(picked) else emptyList()
    }

    private fun addrFor(hostname: String, ip: String): java.net.InetAddress? = runCatching {
        val a = java.net.InetAddress.getByName(ip)
        java.net.InetAddress.getByAddress(hostname, a.address)
    }.getOrNull()

    /** 单节点测速：DNS 钉死该 IP、直连请求官方源首页，返回毫秒；-1 = 不可用。 */
    fun testNode(ip: String, host: String = "hanime1.me"): Long {
        val t0 = System.currentTimeMillis()
        return try {
            val client = OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .dns { hostname ->
                    if (isOfficialHost(hostname)) listOfNotNull(addrFor(hostname, ip))
                    else okhttp3.Dns.SYSTEM.lookup(hostname)
                }
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url("https://$host/?_hplay=$t0")
                .header("User-Agent", UA).header("Accept-Language", "zh-CN,zh;q=0.9").build()
            client.newCall(req).execute().use { resp ->
                if (resp.code in 200..599) System.currentTimeMillis() - t0 else -1
            }
        } catch (t: Throwable) {
            -1
        }
    }

    /** 并发测速全部候选节点并选中最快者（返回 ip→ms；-1 表示不可用）。 */
    fun pickFastestNode(): Map<String, Long> {
        loadSettings()
        val custom = edgeCustomIps.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val nodes = if (custom.isNotEmpty()) custom else builtInNodes
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(8, nodes.size))
        return try {
            val futures = ArrayList<Pair<String, java.util.concurrent.Future<Long>>>(nodes.size)
            for (ip in nodes) {
                futures.add(ip to pool.submit(java.util.concurrent.Callable<Long> { testNode(ip) }))
            }
            val out = LinkedHashMap<String, Long>()
            futures.forEach { (ip, f) -> out[ip] = runCatching { f.get() }.getOrDefault(-1L) }
            lastNodeTest = out
            out.entries.filter { it.value >= 0 }.minByOrNull { it.value }?.let { saveEdgeNode(it.key) }
            out
        } finally {
            pool.shutdown()
        }
    }

    private val ipv4Re = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /**
     * hanime 域名走 DoH（1.1.1.1 / 223.5.5.5），不依赖本地 DNS，避免 DNS 污染把
     * hanime1.me 解析到无关 IP；DoH 不可用或开关关闭时回退系统解析。
     * DoH 请求本身也走代理（若探测到本机代理），保证代理环境下可用。
     */
    private val hanimeDns: okhttp3.Dns by lazy {
        object : okhttp3.Dns {
            private val bootstrap: OkHttpClient by lazy {
                OkHttpClient.Builder()
                    .proxySelector(proxySelector)
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .build()
            }

            // 解析结果缓存（10 分钟），避免每次连接都发 DoH 请求（此前 DoH 慢的主因）
            private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<java.net.InetAddress>, Long>>()

            override fun lookup(hostname: String): List<java.net.InetAddress> {
                loadSettings()
                // 内置 Hosts 边缘节点（参考 Han1meViewer）：只对 hanime 官方源生效，优先于 DoH
                if (edgeHostsEnabled && isOfficialHost(hostname)) {
                    val picked = edgeCandidates()
                    val list = if (picked.isEmpty()) builtInNodes else picked
                    val ips = list.mapNotNull { addrFor(hostname, it) }
                    if (ips.isNotEmpty()) {
                        lastDoh = "$hostname => 内置Hosts ${ips.joinToString(",") { it.hostAddress }}"
                        return ips
                    }
                }
                if (!dohEnabled) {
                    lastDoh = "$hostname => (DoH 已关闭)"
                    return okhttp3.Dns.SYSTEM.lookup(hostname)
                }
                cache[hostname]?.let { (ips, ts) ->
                    if (System.currentTimeMillis() - ts < 10 * 60_000L) {
                        lastDoh = "$hostname => 缓存 ${ips.joinToString(",") { it.hostAddress }}"
                        return ips
                    }
                }
                val viaDoh = runCatching { dohLookup(hostname) }
                val ips = viaDoh.getOrNull()
                if (!ips.isNullOrEmpty()) {
                    lastDoh = "$hostname => ${ips.joinToString(",") { it.hostAddress }}"
                    return ips
                }
                // DNS 劫持兜底：查原名失败时用 hanime 主域名结果（HTTPS 证书/SNI 仍按域名校验）
                if (!hostname.endsWith("hanime1.me")) {
                    val fallback = runCatching { dohLookup("hanime1.me") }.getOrNull()
                    if (!fallback.isNullOrEmpty()) {
                        lastDoh = "$hostname => 兜底hanime1.me ${fallback.joinToString(",") { it.hostAddress }}"
                        return fallback
                    }
                }
                lastDoh = "$hostname => 系统回退(${viaDoh.exceptionOrNull()?.message})"
                return okhttp3.Dns.SYSTEM.lookup(hostname)
            }

            /** 双端点并发查询，取最先成功者；带 10 分钟缓存。 */
            private fun dohLookup(hostname: String): List<java.net.InetAddress> {
                val endpoints = listOf(
                    "https://223.5.5.5/resolve?name=$hostname&type=A",
                    "https://1.1.1.1/dns-query?name=$hostname&type=A",
                )
                val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
                try {
                    val futures = endpoints.map { url ->
                        pool.submit<List<java.net.InetAddress>> { queryEndpoint(url, hostname) }
                    }
                    var lastError: String = "空结果"
                    for (f in futures) {
                        val r = runCatching { f.get(7, TimeUnit.SECONDS) }
                        val ips = r.getOrNull()
                        if (!ips.isNullOrEmpty()) {
                            cache[hostname] = ips to System.currentTimeMillis()
                            return ips
                        }
                        r.exceptionOrNull()?.let { lastError = it.message ?: it.toString() }
                    }
                    throw IllegalStateException("DoH 全部失败: $lastError")
                } finally {
                    pool.shutdownNow()
                }
            }

            private fun queryEndpoint(url: String, hostname: String): List<java.net.InetAddress> {
                val req = Request.Builder().url(url).header("accept", "application/dns-json").build()
                bootstrap.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val entries = Regex("\"type\"\\s*:\\s*(\\d+)[^}]*?\"data\"\\s*:\\s*\"([^\"]+)\"")
                        .findAll(body)
                        .map { it.groupValues[1] to it.groupValues[2] }
                        .toList()
                    val ips = entries.filter { it.first == "1" && ipv4Re.matches(it.second) }
                        .map { java.net.InetAddress.getByName(it.second) }
                    if (ips.isNotEmpty()) return ips
                    val cname = entries.firstOrNull { it.first == "5" }?.second?.trimEnd('.')
                    if (cname != null && cname != hostname) return queryEndpoint(url, cname)
                    return emptyList()
                }
            }
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
            .header("Referer", "$BASE/")
            .get()
            .build()
        val noFollow = http.newBuilder().build() // 手动处理重定向，避免被导流到黑洞域名
        noFollow.newCall(req).execute().use { resp ->
            if (resp.isRedirect) {
                val loc = resp.header("Location").orEmpty()
                val locHost = runCatching { java.net.URI(loc).host.orEmpty() }.getOrDefault("")
                return if (locHost.endsWith("hanime1.me")) {
                    get(java.net.URI(url).resolve(loc).toString())
                } else {
                    throw IllegalStateException("请求被中间层重定向到 $locHost（疑似代理/DNS 干扰），已中止")
                }
            }
            lastReq = "${req.url} → ${resp.code}"
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：$url")
            return resp.body?.string() ?: ""
        }
    }

    @Volatile
    private var lastReq: String = "(无)"

    // ---------- HTML 工具 ----------

    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    private val titleAttrRe = Regex("""<div title="([^"]*)" class="video-item-container">""")
    private val hrefRe = Regex("""<a href="(https?://[^"]*/watch\?v=\d+)" class="video-link">""")
    private val thumbRe = Regex("""<img class="main-thumb" src="([^"]+)"""")
    private val durationRe = Regex("""<div class="duration">\s*([^<]*?)\s*</div>""")
    private val likeRe = Regex("""thumb_up</i>\s*([0-9.]+%)""")
    private val viewsRe = Regex("""stat-item">\s*([0-9.,]+[萬万]?)次""")
    private val subtitleRe = Regex("""<div class="subtitle">\s*<a href="[^"]*">\s*([^<]*?)\s*</a>""")

    /** 从任意 hanime 页面切片解析视频卡片（首页/搜索/相关推荐通用）。 */
    fun parseCards(html: String): List<HanimeVideo> {
        val marker = """<div title=""""
        val out = ArrayList<HanimeVideo>()
        var i = html.indexOf(marker)
        while (i >= 0) {
            val next = html.indexOf(marker, i + marker.length)
            val block = html.substring(i, if (next > 0) next else html.length)
            val headEnd = block.indexOf("video-item-container")
            if (headEnd > 0 && block.length > headEnd) {
                val title = titleAttrRe.find(block.substring(0, headEnd + 40))
                    ?.groupValues?.get(1)?.let(::unescape) ?: ""
                val url = hrefRe.find(block)?.groupValues?.get(1)
                if (url != null) {
                    val id = Regex("""v=(\d+)""").find(url)?.groupValues?.get(1) ?: ""
                    val titleInBlock = Regex("""<div class="title">\s*([\s\S]*?)\s*</div>""").find(block)
                        ?.groupValues?.get(1)?.replace(Regex("""<[^>]+>"""), "")?.trim()
                    val finalTitle = (titleInBlock?.takeIf { it.isNotEmpty() } ?: title)
                    val thumb = thumbRe.find(block)?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""
                    val duration = durationRe.find(block)?.groupValues?.get(1) ?: ""
                    val likes = likeRe.find(block)?.groupValues?.get(1) ?: ""
                    val views = viewsRe.find(block)?.groupValues?.get(1) ?: ""
                    val brand = subtitleRe.find(block)?.groupValues?.get(1)?.let(::unescape) ?: ""
                    if (finalTitle.isNotEmpty() && id.isNotEmpty()) {
                        out.add(HanimeVideo(id, url, finalTitle, thumb, duration, likes, views, brand))
                    }
                }
            }
            i = next
        }
        if (out.isEmpty()) out.addAll(parseCardsByContainer(html))
        return out
    }

    /** 兼容收藏/稍后/历史等页面：容器选择器 div[class^=user-tab-item-wrapper] / horizontal-card。 */
    private fun parseCardsByContainer(html: String): List<HanimeVideo> {
        val out = ArrayList<HanimeVideo>()
        val blockRe = Regex("""<div[^>]*class="(?:user-tab-item-wrapper|horizontal-card)[^"]*"[^>]*>""")
        val marks = blockRe.findAll(html).toList()
        for ((idx, m) in marks.withIndex()) {
            val start = m.range.first
            val end = if (idx + 1 < marks.size) marks[idx + 1].range.first else html.length
            val block = html.substring(start, end)
            val url = Regex("""href="(https?://[^"]*/watch\?v=\d+)"""").find(block)?.groupValues?.get(1) ?: continue
            val id = Regex("""v=(\d+)""").find(url)?.groupValues?.get(1) ?: continue
            val titleInBlock = Regex("""<div class="title">\s*([\s\S]*?)\s*</div>""").find(block)
                ?.groupValues?.get(1)?.replace(Regex("""<[^>]+>"""), "")?.trim().orEmpty()
            val title = titleInBlock.ifEmpty {
                Regex("""title="([^"]*)"""").find(block)?.groupValues?.get(1)?.let(::unescape).orEmpty()
            }
            if (title.isEmpty()) continue
            val thumb = Regex("""<img[^>]*src="([^"]+)"""").find(block)?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""
            val duration = durationRe.find(block)?.groupValues?.get(1) ?: ""
            val likes = likeRe.find(block)?.groupValues?.get(1) ?: ""
            val views = viewsRe.find(block)?.groupValues?.get(1) ?: ""
            val brand = subtitleRe.find(block)?.groupValues?.get(1)?.let(::unescape) ?: ""
            out.add(HanimeVideo(id, url, title, thumb, duration, likes, views, brand))
        }
        return out
    }

    private val sectionRe = Regex("""class="horizontal-row-title"[^>]*href="([^"]+)"[^>]*>\s*<h3>([^<]+)<""")

    /** 首页：分区标题 + 全部卡片（扁平）。 */
    fun home(): Pair<List<HanimeSection>, List<HanimeVideo>> {
        val html = get("$BASE/")
        val sections = sectionRe.findAll(html).map { HanimeSection(unescape(it.groupValues[2]), it.groupValues[1]) }.toList()
        return sections to parseCards(html)
    }

    /** 首页：按分区切分，每区带自己的卡片（分行展示用）。 */
    fun homeRows(): List<HanimeSection> {
        val html = get("$BASE/")
        val marks = sectionRe.findAll(html).toList()
        val out = ArrayList<HanimeSection>()
        for ((i, m) in marks.withIndex()) {
            val start = m.range.last
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else html.length
            if (start >= end) continue
            val cards = parseCards(html.substring(start, end))
            if (cards.isNotEmpty()) {
                out.add(HanimeSection(unescape(m.groupValues[2]), unescape(m.groupValues[1]), cards))
            }
        }
        return out
    }

    private val genreTabRe = Regex("""href="(https://hanime1\.me/search\?genre=[^"]*)"[^>]*class="home-genre-tabs"[^>]*>\s*([^<]+?)\s*</a>""")

    /**
     * 首页（一次抓取）：顶部导航 tabs（网站 navbar 的 genre 链接）+ 分区行。
     * 独立版首页导航条用；注入版不受影响（不调用此函数）。
     */
    fun homeWithNav(): Pair<List<HanimeNavItem>, List<HanimeSection>> {
        val html = get("$BASE/")
        val tabs = genreTabRe.findAll(html)
            .map { HanimeNavItem(unescape(it.groupValues[2]).trim(), it.groupValues[1].replace("&amp;", "&")) }
            .filter { it.label.isNotEmpty() }
            .distinctBy { it.label }
            .toMutableList()
        if (tabs.none { it.label.contains("新番") }) {
            tabs.add(HanimeNavItem("新番預告", "$BASE/search?genre=新番預告"))
        }
        val marks = sectionRe.findAll(html).toList()
        val out = ArrayList<HanimeSection>()
        for ((i, m) in marks.withIndex()) {
            val start = m.range.last
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else html.length
            if (start >= end) continue
            val cards = parseCards(html.substring(start, end))
            if (cards.isNotEmpty()) {
                out.add(HanimeSection(unescape(m.groupValues[2]), unescape(m.groupValues[1]), cards))
            }
        }
        return tabs to out
    }

    /** 搜索/分区列表（url 形如 /search?query=xx&page=2 或分区链接）。 */
    fun listing(url: String): List<HanimeVideo> {
        val full = if (url.startsWith("http")) url else BASE + url
        return parseCards(get(full))
    }

    private val ogTitleRe = Regex("""<meta property="og:title" content="([^"]+)"""")
    private val ogDescRe = Regex("""<meta property="og:description" content="([^"]*)"""")
    private val ogImgRe = Regex("""<meta property="og:image" content="([^"]+)"""")
    private val sourceRe = Regex("""<source src="([^"]+)" type="video/mp4" size="(\d+)"""")
    private val tagRe = Regex("""href="https://hanime1\.me/tag/[^"]*"[^>]*>\s*([^<]+?)\s*<""")

    /** 详情页。 */
    fun detail(video: HanimeVideo): HanimeDetail {
        val html = get(video.url)
        val sources = sourceRe.findAll(html)
            .map { it.groupValues[2].toInt() to it.groupValues[1].replace("&amp;", "&") }
            .toList()
            .sortedByDescending { it.first }
        val tags = tagRe.findAll(html).map { unescape(it.groupValues[1]) }.distinct().toList()
        val desc = ogDescRe.find(html)?.groupValues?.get(1)?.let(::unescape) ?: ""
        val relatedStart = html.indexOf("related-tabcontent")
        val related = if (relatedStart >= 0) parseCards(html.substring(relatedStart)) else emptyList()
        val title = ogTitleRe.find(html)?.groupValues?.get(1)?.let(::unescape) ?: video.title
        val thumb = ogImgRe.find(html)?.groupValues?.get(1)?.replace("&amp;", "&") ?: video.thumb
        // 表单状态：收藏 / 稍后观看 / 用户 id
        val likeStatus = Regex("""name="like-status"[^>]*value="([^"]*)"""").find(html)?.groupValues?.get(1)
        val isFav = !likeStatus.isNullOrEmpty()
        val saveCheckbox = Regex("""<input[^>]*id="playlist-save-checkbox"[^>]*>""").find(html)?.value.orEmpty()
        val isSaved = saveCheckbox.contains("checked")
        val saveListId = Regex("""name="input_id"[^>]*value="([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
        val uid = Regex("""name="like-user-id"[^>]*value="(\d+)"""").find(html)?.groupValues?.get(1)
        if (uid != null && cachedUserId == null) {
            synchronized(cookieMap) { cookieMap["_uid"] = uid }
            cachedUserId = uid
        }
        return HanimeDetail(
            video.copy(title = title, thumb = thumb), desc, tags, sources, related,
            isFav = isFav, isSaved = isSaved, saveListId = saveListId,
        )
    }

    // ---------- 登录 ----------

    private val tokenRe = Regex("""name="_token" value="([^"]+)"""")

    /** 当前登录用户名；未登录返回 null。 */
    fun whoami(): String? {
        loadCookies()
        val home = get("$BASE/")
        if (!home.contains("/logout")) return null
        val name = Regex("""href="https://hanime1\.me/users/[^"]*"[^>]*>\s*(?:<[^>]+>\s*)*([^<]+)<""").find(home)
            ?.groupValues?.get(1)?.trim()
        return name?.takeIf { it.isNotEmpty() } ?: "已登录"
    }

    /**
     * 邮箱 + 密码登录。成功返回用户名，失败抛 [IllegalStateException]（消息可直接展示）。
     */
    fun login(email: String, password: String): String {
        val loginHtml = get("$BASE/login")
        val token = tokenRe.find(loginHtml)?.groupValues?.get(1)
            ?: throw IllegalStateException("无法获取登录令牌（页面结构可能已变化）")
        val form = FormBody.Builder()
            .add("_token", token)
            .add("email", email)
            .add("password", password)
            .add("submit", "")
            .build()
        val req = Request.Builder()
            .url("$BASE/login")
            .post(form)
            .header("User-Agent", UA)
            .header("Referer", "$BASE/login")
            .header("Origin", BASE)
            .build()
        val noRedirect = http.newBuilder().followRedirects(false).build()
        noRedirect.newCall(req).execute().use { resp ->
            if (resp.isRedirect) {
                val loc = resp.header("Location") ?: ""
                if (loc.contains("/login")) throw IllegalStateException("邮箱或密码错误")
            } else if (!resp.isSuccessful) {
                throw IllegalStateException("登录请求失败（HTTP ${resp.code}）")
            }
        }
        // 成功后回首页确认登录态
        val name = whoami() ?: throw IllegalStateException("登录未生效（站点可能要求邮箱验证）")
        persistCookies()
        return name
    }

    // ---------- 账号 / 我的清单 ----------

    /** 当前登录用户 id（登录后从页面解析并缓存到 cookie 文件）。 */
    @Volatile
    private var cachedUserId: String? = null

    fun userId(): String? {
        cachedUserId?.let { return it }
        synchronized(cookieMap) { cookieMap["_uid"] }?.let { cachedUserId = it; return it }
        val uid = runCatching {
            val home = get("$BASE/")
            Regex("""/user/(\d+)""").find(home)?.groupValues?.get(1)
                ?: Regex("""like-user-id"[^>]*value="(\d+)"""").find(home)?.groupValues?.get(1)
        }.getOrNull()
        if (uid != null) {
            synchronized(cookieMap) { cookieMap["_uid"] = uid }
            cachedUserId = uid
            persistCookies()
        }
        return uid
    }

    /** 我的清单：type = "likes"（喜欢的影片）/ "saves"（稍后观看）。 */
    fun myList(type: String, page: Int): List<HanimeVideo> {
        val uid = userId() ?: throw IllegalStateException("未登录或无法获取用户 ID")
        return parseCards(get("$BASE/user/$uid/$type?page=$page"))
    }

    /** 观看历史：sort = latest / popular / oldest。 */
    fun history(page: Int, sort: String = "latest"): List<HanimeVideo> {
        val uid = userId() ?: throw IllegalStateException("未登录或无法获取用户 ID")
        return parseCards(get("$BASE/user/$uid/histories?sort=$sort&page=$page"))
    }

    /** 我的订阅（作者列表以卡片行渲染）。 */
    fun subscriptions(page: Int): List<HanimeVideo> {
        return parseCards(get("$BASE/subscriptions?page=$page"))
    }

    private fun csrfToken(): String {
        val html = get("$BASE/")
        return Regex("""name="_token"[^>]*value="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("无法获取 CSRF 令牌")
    }

    private fun postForm(path: String, fields: Map<String, String>): String {
        val fb = FormBody.Builder()
        fields.forEach { (k, v) -> fb.add(k, v) }
        val req = Request.Builder()
            .url(if (path.startsWith("http")) path else "$BASE$path")
            .post(fb.build())
            .header("User-Agent", UA)
            .header("Referer", "$BASE/")
            .header("Origin", BASE)
            .build()
        http.newBuilder().build().newCall(req).execute().use { resp ->
            return resp.body?.string() ?: ""
        }
    }

    /** 收藏 / 取消收藏。assumeFaved 为当前状态（true=已收藏，操作将取消）。 */
    fun toggleFavorite(videoId: String, currentlyFaved: Boolean): Boolean {
        val uid = userId() ?: throw IllegalStateException("请先登录")
        val token = csrfToken()
        postForm("/like", mapOf(
            "like-foreign-id" to videoId,
            "like-status" to (if (currentlyFaved) "1" else ""),
            "unlike-status" to "",
            "likes-count" to "0",
            "unlikes-count" to "0",
            "_token" to token,
            "like-user-id" to uid,
            "like-is-positive" to "1",
        ))
        return !currentlyFaved
    }

    /** 稍后观看 / 取消。listId 为 saves 列表的 code（详情页 input[name=input_id]）。 */
    fun toggleWatchLater(videoId: String, currentlySaved: Boolean, listId: String): Boolean {
        val uid = userId() ?: throw IllegalStateException("请先登录")
        val token = csrfToken()
        postForm("/save", mapOf(
            "_token" to token,
            "input_id" to listId,
            "video_id" to videoId,
            "is_checked" to if (currentlySaved) "0" else "1",
            "user_id" to uid,
        ))
        return !currentlySaved
    }

    /** 写 .strm 供外部播放器打开；返回文件路径。 */
    fun writeStrm(id: String, url: String): java.io.File {
        val f = java.io.File(System.getProperty("java.io.tmpdir"), "hanime_$id.strm")
        f.writeText(url + "\n")
        return f
    }

    private val profileNameRe = Regex("""class="profile-display-name"[^>]*>\s*([^<\n]{1,60})<""")
    private val profileAvatarRe = Regex(
        """profile-avatar-wrapper[\s\S]{0,400}?<img[^>]{0,200}?src="(https?://[^"]+)"""",
    )
    private val profileStatsRe = Regex("""class="profile-sub-stats-new-line"[^>]*>([^<]{1,60})<""")

    /**
     * 账号资料（用户页要的那一块）。**只放站点真的给的东西**：
     * 实测 `/user/<uid>` 的原文是
     * `<h1 class="profile-display-name">kagla</h1>`、`profile-sub-stats-id` 里是 `@ 483028`、
     * `profile-sub-stats-new-line` 里是 `0 位訂閱者 • 0 部影片`，头像是
     * `profile-avatar-wrapper` 里那张 `<img src="…googleusercontent.com/…=s96-c" alt="kagla">`。
     * 站点**不公开**邮箱、积分、徽章、私信（`/points`、`/notifications` 实测 404，
     * 页面里出现的 "point" 全是 CSS `cursor:pointer`），所以这里一个都不编。
     */
    fun profile(): HanimeProfile? {
        val uid = userId() ?: return null
        val html = runCatching { get("$BASE/user/$uid") }.getOrDefault("")
        if (html.isBlank()) return null
        val name = profileNameRe.find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val avatar = profileAvatarRe.find(html)?.groupValues?.get(1).orEmpty()
        val stats = profileStatsRe.find(html)?.groupValues?.get(1)
            ?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
        return HanimeProfile(
            uid = uid,
            name = name.ifBlank { "用户 $uid" },
            avatar = avatar,
            // 头像原图是 `=s96-c` 的缩图，同一 URL 换成 `=s512-c` 就是大图（Google 图床的尺寸段），
            // 用户页顶部要一张不糊的头像，所以在这里换尺寸而不是另找接口。
            avatarLarge = avatar.replace("=s96-c", "=s512-c"),
            stats = stats,
            home = "$BASE/user/$uid",
            edit = "$BASE/user/$uid/edit",
            playlists = "$BASE/user/$uid/playlists",
        )
    }

    // ---------- 本地播放中继 ----------
    // 播放器（VLC/内嵌）不走 JVM 代理设置；把直链包成本地 localhost 地址，
    // 由本进程按代理探测结果去源站拉流并转发（含 Range/Seek 透传），
    // 从而保证"代理环境下播放也走代理"。

    @Volatile
    private var relayServer: com.sun.net.httpserver.HttpServer? = null

    @Volatile
    private var relayPort: Int = 0

    /** 把原始直链转换为本地中继地址（relay 关闭时原样返回）。 */
    fun relayUrl(original: String): String {
        loadSettings()
        if (!relayEnabled) return original
        if (!ensureRelay()) return original
        val enc = java.net.URLEncoder.encode(original, "UTF-8")
        return "http://127.0.0.1:$relayPort/stream?u=$enc"
    }

    private fun ensureRelay(): Boolean {
        if (relayServer != null) return true
        synchronized(this) {
            if (relayServer != null) return true
            return runCatching {
                val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/stream") { ex ->
                    try {
                        val q = ex.requestURI.rawQuery ?: ""
                        val u = java.net.URLDecoder.decode(q.substringAfter("u=", ""), "UTF-8")
                        if (u.isBlank()) {
                            ex.sendResponseHeaders(400, -1)
                        } else {
                            val range = ex.requestHeaders.getFirst("Range")
                            val reqB = Request.Builder().url(u).header("User-Agent", UA)
                            if (!range.isNullOrBlank()) reqB.header("Range", range)
                            http.newCall(reqB.get().build()).execute().use { resp ->
                                val body = resp.body
                                ex.responseHeaders.add("Content-Type", resp.header("Content-Type") ?: "application/octet-stream")
                                ex.responseHeaders.add("Accept-Ranges", "bytes")
                                resp.header("Content-Range")?.let { ex.responseHeaders.add("Content-Range", it) }
                                resp.header("Content-Length")?.let { ex.responseHeaders.add("Content-Length", it) }
                                if (body == null) {
                                    ex.sendResponseHeaders(502, -1)
                                } else {
                                    val len = body.contentLength()
                                    ex.sendResponseHeaders(resp.code, if (len >= 0) len else 0)
                                    ex.responseBody.use { out -> body.byteStream().use { it.copyTo(out, 256 * 1024) } }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        runCatching { ex.sendResponseHeaders(502, -1) }
                    } finally {
                        runCatching { ex.close() }
                    }
                }
                server.executor = java.util.concurrent.Executors.newFixedThreadPool(4)
                server.start()
                relayServer = server
                relayPort = server.address.port
                true
            }.getOrDefault(false)
        }
    }
}
