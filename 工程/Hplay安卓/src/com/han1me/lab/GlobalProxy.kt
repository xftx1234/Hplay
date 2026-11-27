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
        "cdn2020.com",
        // 第 351 轮同类问题：javchu 的封面图床。设备日志里连续 4 条
        // 「SSLPeerUnverifiedException: Hostname tutu1.space not verified」——
        // 走节点时 CDN 回的是别人家的证书（节点侧回源错乱），直连则是正常证书。
        "tutu1.space",
    )

    /**
     * 第 351 轮新增：**按域名后缀的直连白名单**（与 [ALWAYS_DIRECT] 同一用途，只是可扩展）。
     *
     * 分离出来是因为这里是「**实测确认直连更快/唯一可达**」的 CDN 族，
     * 与「气泡/糖心那几个固定站」的维护节奏不同，混在一起改容易误伤。
     */
    private val ALWAYS_DIRECT_SUFFIX = listOf(
        "tangxin.xyz",   // 糖心 CDN 的别名族（与 5gcdn.xyz 同一家）
    )

    /**
     * 第 352 轮：**javchu 一族是否走直连**（设置页可切）。
     *
     * 默认 `direct` —— 实测（2026-11-27）：`t33.cdn2020.com` 直连 200 + 完整 m3u8 +
     * 分片全部 200；经内置节点（美西）一律 **451**（Server: Quantum，只对外国 IP 生效）。
     * 所以「走代理」在本节点下必然播不了，但**换个节点可能就行**，因此留开关而不是写死。
     */
    private fun javchuDirect(): Boolean = runCatching {
        com.han1me.X99.setting(JAVCHU_ROUTE_KEY, "direct") != "proxy"
    }.getOrDefault(true)

    /** javchu 一族的域（页面 + 媒体 + 封面图床）。 */
    private val JAVCHU_HOSTS = listOf("javchu.com", "cdn2020.com", "tutu1.space")

    private fun isJavchu(h: String): Boolean {
        for (d in JAVCHU_HOSTS) if (h == d || h.endsWith("." + d)) return true
        return false
    }

    private fun alwaysDirect(host: String): Boolean {
        val h = host.lowercase()
        // ★ 第 352 轮：javchu 一族受开关控制，先判它（否则下面的固定表会盖掉开关）
        if (isJavchu(h)) return javchuDirect()
        for (d in ALWAYS_DIRECT) if (h == d || h.endsWith("." + d)) return true
        for (d in ALWAYS_DIRECT_SUFFIX) if (h == d || h.endsWith("." + d)) return true
        return false
    }

    /** 设置键（设置页读写同一个）。 */
    const val JAVCHU_ROUTE_KEY = "net.javchu.route"

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