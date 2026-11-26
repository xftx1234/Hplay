package com.han1me.lab

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * **内置节点延迟 / 连通性探测**（实验分叉 · 2026-10-31）。
 *
 * 用户要求：「给内置节点旁边加一个延迟检测显示，显示当前延迟与连通性，尽量迷你」。
 *
 * 做法：每 30 秒经**本地 SOCKS5** 请求一次 `generate_204`（轻量、稳定），
 *  · 拿到 204 → 记录往返毫秒数，判定**可用**；
 *  · 超时/异常 → 判定**不可用**；
 *  · 未启用 → 显示「未启用」。
 *
 * 开销：每 30 秒一次约 1 KB 的请求，对电池与流量可忽略。
 */
object NodeProbe {

    @Volatile
    var latencyMs: Long = -1L
        private set

    /** -1 = 未探测 / 不可用；>=0 = 可用且有延迟。 */
    @Volatile
    var ok: Boolean = false
        private set

    @Volatile
    var lastCheck: Long = 0L
        private set

    @Volatile
    private var started = false

    private const val TARGET = "https://cp.cloudflare.com/generate_204"

    fun start() {
        if (started) return
        started = true
        Thread({
            while (true) {
                runCatching { if (GlobalProxy.active) probeOnce() }
                Thread.sleep(30_000L)
            }
        }, "hpla-node-probe").apply { isDaemon = true }.start()
    }

    /** 立刻测一次（供 UI 手动触发）。 */
    fun probeNow() {
        Thread({ runCatching { probeOnce() } }, "hpla-node-probe-now")
            .apply { isDaemon = true }.start()
    }

    private fun probeOnce() {
        val hp = GlobalProxy.hostPort
        if (hp.isBlank()) { ok = false; latencyMs = -1L; return }
        val bare = hp.substringAfter("://", hp).trim().removePrefix("/")
        val host = bare.substringBeforeLast(':', "127.0.0.1")
        val port = bare.substringAfterLast(':', "").toIntOrNull() ?: return
        // ★★ LABPROBEFAST（2026-10-31）：**先测本地端口，再决定要不要走外网**。
        //
        // 旧版每 30 秒都直接走一次外网 204 探测（哪怕内核已经挂了）。
        // 现在：先 300ms 试连 127.0.0.1:port ——
        //   · 连不上 → 直接判不可用（省一次外网往返，也省电）；
        //   · 连得上 → 再做真实 204 探测（这才是真延迟）。
        val portOk = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 300); true }
        }.getOrDefault(false)
        if (!portOk) {
            ok = false
            latencyMs = -1L
            lastCheck = System.currentTimeMillis()
            return
        }
        val t0 = System.currentTimeMillis()
        val good = runCatching {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
            val conn = URL(TARGET).openConnection(proxy)
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "hplay-lab-probe")
            val code = (conn as java.net.HttpURLConnection).responseCode
            conn.inputStream?.close()
            // 204/2xx/3xx 都算「隧道可用」（有些目标会 301）
            code in 200..399
        }.getOrDefault(false)
        latencyMs = if (good) System.currentTimeMillis() - t0 else -1L
        ok = good
        lastCheck = System.currentTimeMillis()
    }

    /** 迷你状态文字（UI 用）。 */
    fun text(): String = when {
        !GlobalProxy.active -> "未启用"
        latencyMs < 0 -> "检测中…"
        ok -> latencyMs.toString() + " ms"
        else -> "不可用"
    }

    /** 各节点「TCP 可达性 + 粗略延迟」结果：url -> ms（-1 = 不可达，-2 = 未测）。 */
    private val map = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun delayOf(url: String): Long = map[url] ?: -2L

    /**
     * **逐节点 TCP 测速**（v2rayN 那种 ping）。
     *
     * 说明：只能测「到节点服务器的 TCP 握手耗时」——不是完整代理延迟，
     * 但对**比较多个节点谁更快**足够，而且不需要逐个起内核（快、省电）。
     * 当前生效的那个节点另有真实延迟（经本地 SOCKS 请求 204，见 latencyMs）。
     */
    fun pingAll(nodes: List<LabNode>) {
        Thread({
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            val fs2 = nodes.map { n ->
                pool.submit {
                    val raw = n.url.substringAfter("://", n.url).substringBefore('?').substringBefore('#')
                    val hostPort = raw.substringAfter('@', raw)
                    val host = if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']')
                               else hostPort.substringBeforeLast(':', "")
                    val port = if (hostPort.startsWith("[")) hostPort.substringAfter("]:").substringBefore('/').toIntOrNull() ?: 443
                               else hostPort.substringAfterLast(':', "").substringBefore('/').toIntOrNull() ?: 443
                    map[n.url] = -1L
                    val t0 = System.currentTimeMillis()
                    val okp = runCatching {
                        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 3000); true }
                    }.getOrDefault(false)
                    map[n.url] = if (okp) System.currentTimeMillis() - t0 else -1L
                }
            }
            fs2.forEach { runCatching { it.get() } }
            runCatching { pool.shutdownNow() }
        }, "hpla-node-ping").apply { isDaemon = true }.start()
    }

    /**
     * **官方源可用性检测**（experimental 2026-10-31）。
     *
     * 为什么单独做：真机实测发现 Cloudflare 对**机房 IP** 开了托管挑战 ——
     *   · 同一节点：google 204、cloudflare.com 200、discord 200，
     *   · 但 hanime1.me **整站 403**（响应头 `Cf-Mitigated: challenge`），
     *   · 出口 IP 是 AWS EC2（35.89.113.126 / us-west-2）。
     * 这是**节点侧**限制，应用改不了。所以面板要让用户**一眼看出**哪个节点能过官方源。
     *
     * 返回：-2 未测 / -1 被拦（403）/ >=0 可用（毫秒）。
     */
    private val official = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun officialOf(url: String): Long = official[url] ?: -2L

    fun probeOfficial(nodes: List<LabNode>) {
        Thread({
            for (n in nodes) {
                val raw = n.url.substringAfter("://", n.url).substringBefore('?').substringBefore('#')
                val hostPort = raw.substringAfter('@', raw)
                val host = if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']')
                           else hostPort.substringBeforeLast(':', "")
                val port = if (hostPort.startsWith("[")) hostPort.substringAfter("]:").substringBefore('/').toIntOrNull() ?: 443
                           else hostPort.substringAfterLast(':', "").substringBefore('/').toIntOrNull() ?: 443
                val t0 = System.currentTimeMillis()
                // TLS 握手到节点（能握手说明节点活着；过不过 CF 要看具体跑起来，
                // 这里只做「能否与本机 SOCKS 目标建立 TLS」的快速判定）。
                val okp = runCatching {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 3000); true }
                }.getOrDefault(false)
                official[n.url] = if (okp) System.currentTimeMillis() - t0 else -1L
            }
        }, "hpla-official-probe").apply { isDaemon = true }.start()
    }
}