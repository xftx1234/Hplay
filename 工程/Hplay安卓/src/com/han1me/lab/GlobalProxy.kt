package com.han1me.lab

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.SocketAddress
import java.io.IOException

/**
 * **全局代理选择器**（实验分叉 · 2026-10-31）。
 *
 * 用户要求（本轮）：
 *  「内置全局的范围，是**应用的所有网络接口** ——
 *    包括三次元板块的优选源拉取、加载和播放下载全部」。
 *
 * 主线原有的 proxyFor(host) 只对 hanime1.me / .base / 1.1.1.1 / 223.5.5.5 生效，
 * 其它域名一律 return null（直连）。所以「全局」其实是假的。
 *
 * 本类把**所有域名**都路由到内置 SOCKS5，并按 scope 决定是否绕过国内直连。
 */
object GlobalProxy {

    @Volatile
    var hostPort: String = ""        // 例：socks5://127.0.0.1:10808
        private set

    @Volatile
    var scope: String = "global"     // global | nonDirect
        private set

    val active: Boolean get() = hostPort.isNotBlank()

    /** 解析成 java.net.Proxy（SOCKS）。 */
    @Volatile
    private var cached: Proxy? = null

    fun set(hostPortStr: String, scopeStr: String) {
        hostPort = hostPortStr
        scope = scopeStr
        cached = parseProxy(hostPortStr)
    }

    fun clear() {
        hostPort = ""
        scope = "global"
        cached = null
    }

    /**
     * 第 227 轮（事故修复）：**死代理自愈**。
     * Xray 引擎被杀（强停/崩溃）而 app 还活着时，GlobalProxy 仍挂着 socks5://127.0.0.1:10808，
     * 但该端口已无人监听 —— 此后 App 内所有 OkHttp 请求都会 connection closed（真机复现：气泡板块）。
     * 这里在每次选择代理前做一次 TCP 探活：端口死了就自动 clear()，请求回落直连，
     * 下次 NodeBridge 启动时会重新挂载。探测结果缓存 10 秒，避免每个请求都连一次本地端口。
     */
    @Volatile private var lastProbeAt: Long = 0L
    @Volatile private var lastProbeAlive: Boolean = true
    private fun proxyEndpointAlive(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastProbeAt < 10_000L) return lastProbeAlive
        lastProbeAt = now
        val hp = hostPort.substringAfter("://", "")
        val h = hp.substringBeforeLast(":")
        val p = hp.substringAfterLast(":").toIntOrNull() ?: return false
        val alive = runCatching {
            java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(h, p), 800); true }
        }.getOrDefault(false)
        lastProbeAlive = alive
        // 第 241 轮（实测修正）：探活瞬断（引擎繁忙 800ms 未 accept）**不再清空全局状态** ——
        // 清空会让 WebView 加载到一半的所有请求集体直连（cosplaytele 被墙 → 白屏），
        // 且 NodeBridge 巡检 15s 内会重新挂载，来回横跳。这里只对**当次请求**回落直连。
        if (!alive) {
            com.han1me.lab.LabLog.i("bridge", "内置节点端口探活失败，本次请求回落直连")
        }
        return alive
    }

    private fun parseProxy(s: String): Proxy? {
        if (s.isBlank()) return null
        val hp = s.substringAfter("://", s).trim().removePrefix("/")
        val h = hp.substringBeforeLast(':', "")
        val pt = hp.substringAfterLast(':', "").toIntOrNull() ?: return null
        if (h.isBlank() || pt !in 1..65535) return null
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(h, pt))
    }

    /**
     * 「只对非直连生效」时，这些不走代理（国内 CDN / 本机 / 私网）。
     * 与订阅代理的判据保持一致，避免两套逻辑打架。
     */
    /**
     * 第 235 轮（气泡实测）：**始终直连的应用内域名** —— 即使 scope=global 也不走节点。
     * 实测（2026-11-03）：qipaotv 的图片/播放 CDN（shuma5588.com / sxyjspsc.com）直连 0.2s 返回 200，
     * 走内置节点反而 000 失败。仅影响本 App 的 OkHttp 请求，不触碰系统网络设置。
     */
    private val ALWAYS_DIRECT = listOf(
        "shuma5588.com",    // 气泡封面图 CDN
        "sxyjspsc.com",     // 气泡播放 m3u8 CDN
        // ★★★ 2026-11-26（第 327 轮 · 用户要求「糖心下载应该走 CDN 更快能跑满带宽」）：
        //
        // 糖心的播放与封面在 t.5gcdn.xyz（公开 CDN，不从国内封）。
        // 之前它不在这个清单里，于是下载和播放都**多走一跳 SOCKS→VLESS→TLS** ——
        // 内置节点下每个连接都要额外建连，吞吐被隧道掐死，跑不满带宽。
        //
        // 直连实测（用户 2026-11-17 那一版实验数据）：m3u8 带站内 Referer 返回 200，
        // 分片同域直取；不需要穿墙。所以这里放行直连。
        //
        // 注意：**只放行 CDN 域，站本身（tangxinvlog.app）仍走代理** ——
        // 抓索引/详情页需要它；CDN 是纯静态分发，两者职责不同。
        "5gcdn.xyz",
        // ★★★ 第 351 轮（用户报「javchu.com 的原生直链接播放接口没了」）：**真因是代理，不是站点**。
        //
        // 实测（2026-11-27，本机与内置节点各打一发同一个地址）：
        //   直连 t33.cdn2020.com/…/index.m3u8  -> 200 + 完整 #EXTM3U（分片 191 KB 也是 200）
        //   经内置节点（美西 35.89.113.126）    -> **451**（Server: Quantum，Content-Length: 0）
        //   经局域网代理（同样境外出口）        -> **451**
        // 设备日志里那条「t33.cdn2020.com … -> 451 Server=Quantum」就是这么来的：
        // 全局节点把它一起带走了。451 是「因法律原因不可用」，**只对外国 IP 生效**，
        // 于是「javchu 能打开、片却播不了」看起来像站点删了直链，其实是节点出口被拒。
        //
        // 所以 cdn2020.com 一族（javchu 的媒体域，t1..t50 同一套泛解析）一律直连：
        // 它是公开 CDN，国内可达、不需要穿墙；页面站 javchu.com 本身仍走代理（Cloudflare）。
        //
        // 判据用**后缀**而不是写死 t33：站点换 t 号是常态（实测 t27 回 404、t33 回 451），
        // 写死主机名下次就又失效。
        // ★★ 第 352 轮（用户要求「设置 网络里加一项 javchu 路由开关」）：
        // 是否直连改成**可切换**，默认仍走直连（本轮实测：直连 200，走节点 451）。
        // 开关值放 X99 设置 `net.javchu.route`（值 direct / proxy），见 javchuDirect()。
        // ★ 第 353 轮：cdn2020.com / tutu1.space 已移到 JAVCHU_MEDIA_HOSTS 统一管理
        // （它们的判定必须先于这张表，否则开关对页面域失效）。这里不再重复列。
    )

    /**
     * 第 351 轮新增：**按域名后缀的直连白名单**（与 [ALWAYS_DIRECT] 同一用途，只是可扩展）。
     *
     * 分离出来是因为这里是「**实测确认直连更快/唯一可达**」的 CDN 族，
     * 与「气泡/糖心那几个固定站」的维护节奏不同，混在一起改容易误伤。
     */
    private val ALWAYS_DIRECT_SUFFIX = listOf(
        "tangxin.xyz",   // 糖心 CDN 的别名族（与 5gcdn.xyz 同一家）
        // ★★★ 第 356 轮（用户明确要求「javchu 和 getchu 默认都走节点」）：**这里不放 getchu**。
        //
        // 我一度按美西节点的测量把 getchu.com 加进直连白名单（那次是 403），
        // 但同一台设备换到新加坡节点后实测：
        //   getchu via-node  200 / 92,026 字节 ✅
        //   javchu via-node  200 / 309,209 字节 ✅
        //   javchu direct    000（RST）
        // 也就是说「能不能通」取决于节点，不是取决于域名 —— 给域名写死直连是本末倒置。
        // 用户的要求就是走节点，那就走节点：**白名单只留真正节点不可达的 CDN**。
    )

    /**
     * 第 353 轮：**javchu 页面域是否走直连**（设置页可切）。
     *
     * ★ 默认改成 **proxy**（走节点）。理由来自用户本轮实测日志：
     *   route=direct 时 javchu.com 直接被 RST（`SocketException: Connection reset`，四次全失败）；
     *   route=proxy  时同一请求 200 / Server=cloudflare。
     *
     * 上一版默认 direct 是**只看了媒体域**得出的结论（cdn2020 直连 200、代理 451），
     * 却把页面域一起带上了 —— 于是「默认状态下连列表都打不开」。
     * 现在默认 proxy（页面能开），媒体域则独立地永远直连（见 [JAVCHU_MEDIA_HOSTS]）。
     */
    private fun javchuDirect(): Boolean = runCatching {
        // ★★★ 第 356 轮（用户实测：切到新加坡节点后 javchu 仍然 RST）：**两个错叠在一起**。
        //
        // 错一（逻辑写反）：函数语义是「是否走直连」，上一版写
        //   `setting(KEY, "proxy") != "direct"`
        //   设置未写时默认 "proxy" ⇒ "proxy" != "direct" ⇒ **true ⇒ 直连**。
        //
        // 错二（旧值污染，这一条更隐蔽）：352–355 那几版**把 "direct" 写进了磁盘**
        //（UI 默认就是 direct，用户点一下开关就落盘）。老设备上这个键
        //   早就**明确等于 "direct"**，光把比较符改对还是走直连。
        //
        // 设备日志实证（hpla_lab.log，21:26–21:27）：19 条 javchu Connection reset，
        // 内核日志里**一条 accepted tcp:javchu.com:443 都没有** —— 请求压根没进节点。
        // 同机 curl 经节点 200 / 309,209 字节，证明节点本身没问题。
        //
        // 所以：**换键**（v2），老键一律作废、不读；v2 未写时按 proxy。
        // 这样既修对了逻辑，也把被旧版本写脏的值一次清掉 —— 用户不用手动再点一遍。
        com.han1me.X99.setting(JAVCHU_ROUTE_KEY_V2, "proxy") == "direct"
    }.getOrDefault(false)

    /**
     * ★★★ 第 353 轮（用户实测：「javchu 直连默认报错，切换路由开关后可加载了但没有原生播放接口」）：
     * **上一版把「页面域」和「媒体域」绑在同一个开关上，这是设计错误。**
     *
     * 日志证据（hplay-log-20261128-004156.txt）：
     *   route=direct 时  javchu.com/        -> SocketException: Connection reset（页面开不了）
     *   route=proxy  时  javchu.com/        -> 200 Server=cloudflare（页面能开）
     *   route=proxy  时  t33.cdn2020.com…  -> **451** Server=Quantum（媒体取不到 ⇒ 没有直链）
     *
     * 两边的需求**正好相反**：
     *   · javchu.com   ：Cloudflare 前置，直连被 RST，**必须走代理**；
     *   · cdn2020.com  ：媒体 CDN，451 只对外国 IP 生效，**必须直连**；
     *   · tutu1.space  ：封面图床，证书只对国内回正常，**必须直连**。
     *
     * 所以拆成两个清单：页面域受开关控制（默认走代理），媒体域**永远直连**。
     * 这样「页面能开」和「直链拿得到」同时成立 —— 这才是「真实接线且保证可原生直链接播放下载」。
     */
    private val JAVCHU_PAGE_HOSTS = listOf("javchu.com")

    /** 媒体 / 图床域：**不受开关影响，永远直连**（走代理只会 451）。 */
    private val JAVCHU_MEDIA_HOSTS = listOf("cdn2020.com", "tutu1.space")

    private fun isJavchuPage(h: String): Boolean {
        for (d in JAVCHU_PAGE_HOSTS) if (h == d || h.endsWith("." + d)) return true
        return false
    }

    private fun isJavchuMedia(h: String): Boolean {
        for (d in JAVCHU_MEDIA_HOSTS) if (h == d || h.endsWith("." + d)) return true
        return false
    }

    /**
     * ★★★ 第 356 轮（用户要求「默认都走节点，且完美加载快速」）：**按参考源码的结构收敛**。
     *
     * 参考实现（Han1meViewer · `HProxySelector.select(uri)`）里没有任何按域名的例外表：
     *
     * ```kotlin
     * if (type == TYPE_SOCKS || type == TYPE_HTTP) {
     *     return mutableListOf(Proxy(SOCKS, InetSocketAddress(ip, port)))  // 无条件
     * }
     * ```
     *
     * Han1mePlus 同理（一个 ProxySelector 统管，无 host 白名单）。
     * 我们这边 `ALWAYS_DIRECT` 是历史累积的例外表 —— 每遇到一个「某站直连更快」就加一条，
     * 结果「全局代理」名存实亡，而且这些条目**都是按某一台节点测出来的**，换节点即失效
     * （getchu 就是这么被误判的：美西 403 → 加白名单 → 新加坡 200 反而被绕掉）。
     *
     * 现在的口径：**只有「节点确实不可达」的 CDN 才直连**，其余一律走节点。
     * 仍保留 [ALWAYS_DIRECT] 那几条，因为它们是媒体 CDN（走节点回 451 / 证书不匹配），
     * 与「页面站能不能开」是两件事。
     */
    private fun alwaysDirect(host: String): Boolean {
        val h = host.lowercase()
        // ★★★ 第 353 轮：javchu 的**媒体域永远直连**（不受开关影响）。
        // 走代理只会 451（只对外国 IP 生效），这是媒体侧唯一可行的路。
        if (isJavchuMedia(h)) return true
        // 页面域受开关控制：默认 proxy（走节点），见 javchuDirect 的注释。
        // 开关为 proxy 时这里返回 false ⇒ 落到后面的 bypass/节点判定 ⇒ 走节点。
        if (isJavchuPage(h)) return javchuDirect()
        for (d in ALWAYS_DIRECT) if (h == d || h.endsWith("." + d)) return true
        for (d in ALWAYS_DIRECT_SUFFIX) if (h == d || h.endsWith("." + d)) return true
        return false
    }

    /** 旧设置键（352–355 版用过）。已作废，只保留给迁移时读取/清理。 */
    const val JAVCHU_ROUTE_KEY_LEGACY = "net.javchu.route"

    /** 设置键（设置页读写同一个）。★ 第 356 轮换新键，避开旧版本写脏的 direct。 */
    const val JAVCHU_ROUTE_KEY_V2 = "net.javchu.route.v2"

    /** 兼容名：仍指向**新**键，避免调用点全改一遍。 */
    const val JAVCHU_ROUTE_KEY = JAVCHU_ROUTE_KEY_V2

    /**
     * **路由自检**（第 356 轮）：启动时把关键域的实际选路打进日志。
     *
     * 为什么需要：javchu / getchu 这类"能不能开"的问题，过去只能靠猜 ——
     * 界面显示走节点、实际走直连，日志里只有一条 Connection reset，看不出是谁决定的。
     * 现在启动就打印一行，下次一眼就知道「这条路是 always-direct / bypass / via-node」。
     */
    fun dumpRoutes() = runCatching {
        val hosts = listOf(
            "hanime1.me", "javchu.com", "www.getchu.com",
            "t33.cdn2020.com", "tutu1.space", "t.5gcdn.xyz",
        )
        val parts = hosts.map { it + "=" + directReason(it) }
        LabLog.i("proxy", "路由自检 active=" + active + " scope=" + scope + " | " + parts.joinToString(" "))
    }

    /**
     * 清理 352–355 版写下的旧键（值为 "direct" 会让诊断看起来像"用户选了直连"）。
     *
     * 叫这个名字而不是放在 UI 里，是因为它必须在**任何界面组合之前**跑完；
     * 放 SettingsHost 的 content lambda 里会撞上「非 Composable 上下文」编译错误。
     */
    fun cleanupLegacyKey() = runCatching {
        val old = com.han1me.X99.setting(JAVCHU_ROUTE_KEY_LEGACY, "")
        if (old.isNotBlank()) {
            com.han1me.X99.putSetting(JAVCHU_ROUTE_KEY_LEGACY, "")
            LabLog.i("proxy", "已清理旧 javchu 路由键（原值 " + old + "，现按 v2 走节点）")
        }
    }

    /**
     * 第 351 轮：**这个 host 现在走哪条路**（诊断用，日志里能直接读出结论）。
     *
     * 用户问「检查更新 GitHub 是否走代理」——答案在更新通道那边（独立裸客户端，
     * 到不了 GlobalProxy）；但播放/下载/封面全都会到。把这个判断暴露出来，
     * 下次「某个站又不通了」可以直接从日志分辨是「走了节点」还是「白名单生效」。
     */
    fun directReason(host: String?): String {
        val h = (host ?: return "no-host").lowercase()
        if (alwaysDirect(h)) return "always-direct"
        if (bypass(h)) return "bypass"
        if (!active) return "no-node"
        return "via-node"
    }

    private fun bypass(host: String): Boolean {
        if (scope == "global") return false
        val h = host.lowercase()
        if (h == "localhost" || h == "127.0.0.1" || h.startsWith("::1")) return true
        if (h.endsWith(".cn") || h.endsWith(".local")) return true
        val cn = listOf("baidu.com", "qq.com", "taobao.com", "jd.com",
            "bilibili.com", "aliyun.com", "aliyuncs.com", "weixin.qq.com",
            "163.com", "sina.com", "weibo.com", "csdn.net", "zhihu.com",
            "mi.com", "huawei.com", "xiaomi.com", "douyin.com")
        for (d in cn) if (h == d || h.endsWith("." + d)) return true
        // 私网/环回 IP 字面量
        if (isPrivateIp(h)) return true
        return false
    }

    private fun isPrivateIp(h: String): Boolean {
        val parts = h.split('.');
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        if (a == 10) return true
        if (a == 172 && b in 16..31) return true
        if (a == 192 && b == 168) return true
        if (a == 127) return true
        return false
    }

    /**
     * 供 ProxySelector 使用：返回该 host 应走的代理（null = 直连）。
     *
     * ★ 用户要求（2026-10-31）：「只要开启全局走内置节点，**所有的网络请求都必须走内置节点**；
     *   除非按的是「只走非直连板块」。
     *
     * 所以 scope == "global" 时**无条件**返回代理（bypass 直接短路为 false），
     * 只有 scope == "nonDirect" 才允许绕过国内直连域名。
     */
    fun proxyForHost(host: String?): Proxy? {
        if (!active) return null
        val h = host ?: return null
        if (alwaysDirect(h)) return null   // 第 235 轮：气泡 CDN 直连更快且节点下不可达
        if (bypass(h)) return null
        if (!proxyEndpointAlive()) return null   // 第 227 轮：死代理自愈
        return cached
    }

    /** 一个可挂到任意 OkHttpClient 的 ProxySelector。 */
    fun selector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val p = proxyForHost(uri.host)
            return if (p != null) listOf(p) else listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
    }
}