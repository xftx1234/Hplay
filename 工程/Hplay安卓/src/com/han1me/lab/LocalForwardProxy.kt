package com.han1me.lab

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **本地 HTTP 转发代理**（2026-11-24 第 277 轮新增）。
 *
 * ## 为什么需要它

 * CF 挑战必须由 **WebView 自己**建立连接（浏览器指纹要一致）。
 * 但 WebView 不会走 JVM 的 ProxySelector，Android 也没有给 WebView 直接配 SOCKS 的 API。
 *
 * 所以桥接一层：
 *
 * ```
 * WebView --HTTP代理--> 本机 127.0.0.1:<port> --SOCKS5--> Xray 10808 --> 节点 --> hanime1.me
 * ```
 *
 * WebView 侧看到的仍然是「一条普通 HTTP 代理」，TLS 由 WebView 自己握手，
 * 指纹、JS 环境、challenge iframe 全部是原生浏览器的 —— CF 不会判定为异常配置。
 *
 * ## 实现范围（够用就好，不求完整 RFC）

 * 只做 CF 挑战需要的那点：
 *  · `CONNECT host:port` —— HTTPS 隧道，挑战页主路径；
 *  · `GET/POST http://...` —— 明文回退（少见但会有）。
 * 不做：认证、缓存、分块传输的边界情况、HTTP/2。
 *
 * ## 生命周期

 * 由 App 启动时起一次、进程存活期常驻（见 CfForward）。端口固定 18080，
 * 避免和别的进程抢固定端口。
 */
class LocalForwardProxy(
    /** 上游 SOCKS 代理地址（Xray 的 socks-in），例如 127.0.0.1:10808。 */
    private val upstreamHost: String,
    private val upstreamPort: Int,
) {

    companion object {
        /**
         * 固定监听端口。
         *
         * 为什么必须固定：系统代理（settings global http_proxy）里写的是具体端口号，
         * 如果每次启动随机分配，重启后系统代理就指向了死端口 → 验证页变空白。
         * 固定后 adb 只需设置一次。
         */
        const val FIXED_PORT = 18080
    }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = AtomicBoolean(false)

    /** 实际监听端口；0 表示没起来。 */
    @Volatile
    var port: Int = 0
        private set

    /** 启动监听。成功返回端口号，失败返回 0。 */
    fun start(): Int {
        if (running.get()) return port
        // ★ 2026-11-24：**固定端口优先**（系统代理里写的就是它，重启后端口不能变）。
        //   只有被占用时才退回动态端口。这样 adb 设一次系统代理就长期有效。
        val ss = runCatching { ServerSocket(FIXED_PORT, 64, java.net.InetAddress.getByName("127.0.0.1")) }
            .recoverCatching { ServerSocket(0, 64, java.net.InetAddress.getByName("127.0.0.1")) }
            .getOrNull() ?: return 0
        server = ss
        port = ss.localPort
        running.set(true)
        Thread({
            while (running.get()) {
                val c = runCatching { ss.accept() }.getOrElse {
                    LabLog.i("cf.fwd", "accept 异常 " + it.javaClass.simpleName)
                    null
                } ?: break
                LabLog.i("cf.fwd", "收到连接 from " + runCatching { c.inetAddress?.hostAddress }.getOrNull())
                Thread({ handle(c) }, "hplay-fwd").apply { isDaemon = true }.start()
            }
        }, "hplay-fwd-accept").apply { isDaemon = true }.start()
        LabLog.i("cf", "本地转发代理已起 127.0.0.1:" + port + " -> socks " + upstreamHost + ":" + upstreamPort)
        return port
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        port = 0
    }

    /** 处理一条客户端连接。 */
    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val cin = BufferedInputStream(client.getInputStream())
            val cout = client.getOutputStream()

            // ---- 读请求行与头（限制大小，防被灌爆） ----
            val head = readHead(cin) ?: run { client.close(); return }
            val firstLine = head.lineSequence().firstOrNull().orEmpty()
            val parts = firstLine.split(" ")
            if (parts.size < 3) { client.close(); return }
            val method = parts[0].uppercase()
            val target = parts[1]

            if (method == "CONNECT") {
                // HTTPS 隧道：host:port
                val hp = target.substringBefore(" ").trim()
                val h = hp.substringBeforeLast(":", hp)
                val p = hp.substringAfterLast(":", "443").toIntOrNull() ?: 443
                val up = dialSocks(h, p) ?: run {
                    LabLog.i("cf.fwd", "CONNECT " + h + ":" + p + " -> SOCKS 拨号失败")
                    runCatching { cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()) }
                    client.close(); return
                }
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                cout.flush()
                LabLog.i("cf.fwd", "CONNECT " + h + ":" + p + " -> 隧道已建")
                client.soTimeout = 0
                // 双向泵
                val t1 = pump(cin, up.getOutputStream())
                val t2 = pump(up.getInputStream(), cout)
                runCatching { t1.join(120_000) }
                runCatching { t2.join(120_000) }
                runCatching { up.close() }
                runCatching { client.close() }
                return
            }

            // ---- 明文 http:// ----
            val url = java.net.URL(target)
            val h = url.host
            val p = if (url.port > 0) url.port else 80
            val up = dialSocks(h, p) ?: run {
                runCatching { cout.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()) }
                client.close(); return
            }
            // 重写请求行：绝对 URL -> 路径；Host 头补上
            val sb = StringBuilder()
            sb.append(method).append(" ").append(url.file.ifEmpty { "/" })
            if (url.query != null) sb.append("?").append(url.query)
            sb.append(" HTTP/1.1\r\n")
            var hasHost = false
            var bodyLen = 0L
            for (line in head.lineSequence().drop(1)) {
                if (line.isBlank()) break
                val k = line.substringBefore(":", "").trim()
                if (k.equals("Host", true)) hasHost = true
                if (k.equals("Content-Length", true)) bodyLen = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                // Proxy-Connection 是给代理的，别转给目标站
                if (k.equals("Proxy-Connection", true)) continue
                sb.append(line).append("\r\n")
            }
            if (!hasHost) sb.append("Host: ").append(h).append("\r\n")
            sb.append("\r\n")
            val uout = up.getOutputStream()
            uout.write(sb.toString().toByteArray())
            uout.flush()
            // 有 body 就转发
            if (bodyLen in 1..(4 * 1024 * 1024)) {
                val buf = ByteArray(16 * 1024)
                var left = bodyLen
                while (left > 0) {
                    val n = cin.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    uout.write(buf, 0, n)
                    left -= n
                }
                uout.flush()
            }
            client.soTimeout = 0
            val t1 = pump(up.getInputStream(), cout)
            runCatching { t1.join(60_000) }
            runCatching { up.close() }
            runCatching { client.close() }
        } catch (e: Exception) {
            LabLog.i("cf.fwd", "handle 异常 " + e.javaClass.simpleName + " " + (e.message ?: ""))
            runCatching { client.close() }
        }
    }

    /**
     * 读 HTTP 头，读到空行为止，上限 32KB。
     *
     * ★ 2026-11-24（真机 bug）：判据写错了。
     *   HTTP 头的结束标记是 `CRLF CRLF`，即字节序列 `13 10 13 10`。
     *   原来只检查「连续两个 LF」（prev==10 && b==10），而实际流里
     *   永远是 `13 10` 结尾 —— 于是**永远读不到头**，连接一直挂着，
     *   表现为 curl 走这个代理 100% 超时（实测 code=000 t=10s）。
     *
     *   正确判据：当前字节是 LF(10) 且前一个字节是 CR(13) 且再前一个是 LF 前的 CR
     *   —— 简化为「收到 LF，且缓冲区以 CRLF CRLF 结尾」。
     */
    private fun readHead(inp: InputStream): String? {
        val sb = StringBuilder()
        var n = 0
        while (n < 32 * 1024) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            n++
            val len = sb.length
            // 判据：末尾正好是 CRLFCRLF（或 LFLF，兼容无 CR 的畸形客户端）
            if (len >= 4 &&
                sb[len - 4] == '\r' && sb[len - 3] == '\n' &&
                sb[len - 2] == '\r' && sb[len - 1] == '\n'
            ) return sb.toString()
            if (len >= 2 && sb[len - 2] == '\n' && sb[len - 1] == '\n') return sb.toString()
        }
        return sb.toString()
    }

    /** 经 SOCKS5 连到目标（Xray 的 socks-in 是标准 SOCKS）。 */
    private fun dialSocks(host: String, port: Int): Socket? = runCatching {
        val s = Socket()
        s.connect(InetSocketAddress(upstreamHost, upstreamPort), 8000)
        s.soTimeout = 20_000
        val o = s.getOutputStream()
        val i = s.getInputStream()
        // 握手：无认证
        o.write(byteArrayOf(0x05, 0x01, 0x00)); o.flush()
        val gr = ByteArray(2)
        if (i.read(gr) != 2 || gr[0].toInt() != 5 || gr[1].toInt() != 0) {
            s.close(); return null
        }
        // CONNECT 请求：域名类型（0x03），让节点侧解析（避免本地 DNS 污染）
        val hb = host.toByteArray(Charsets.UTF_8)
        val req = ByteArray(4 + 1 + hb.size + 2)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = hb.size.toByte()
        System.arraycopy(hb, 0, req, 5, hb.size)
        req[5 + hb.size] = ((port shr 8) and 0xFF).toByte()
        req[6 + hb.size] = (port and 0xFF).toByte()
        o.write(req); o.flush()
        // 应答：VER REP RSV ATYP BND.ADDR BND.PORT
        val rb = ByteArray(4)
        if (i.read(rb) != 4 || rb[1].toInt() != 0) { s.close(); return null }
        when (rb[3].toInt()) {
            1 -> { val skip = ByteArray(4 + 2); if (i.read(skip) != skip.size) { s.close(); return null } }
            4 -> { val skip = ByteArray(16 + 2); if (i.read(skip) != skip.size) { s.close(); return null } }
            3 -> {
                val ln = i.read(); if (ln < 0) { s.close(); return null }
                val skip = ByteArray(ln + 2); if (i.read(skip) != skip.size) { s.close(); return null }
            }
            else -> { s.close(); return null }
        }
        s.soTimeout = 0
        s
    }.getOrNull()

    /** 单向泵，返回线程。 */
    private fun pump(a: InputStream, b: OutputStream): Thread {
        val t = Thread({
            val buf = ByteArray(16 * 1024)
            var total = 0L
            try {
                while (true) {
                    val n = a.read(buf)
                    if (n <= 0) break
                    b.write(buf, 0, n)
                    b.flush()
                    total += n
                }
                LabLog.i("cf.fwd", "pump 结束，共 " + total + " 字节")
            } catch (e: IOException) {
                LabLog.i("cf.fwd", "pump 异常 " + e.javaClass.simpleName + " 共 " + total + " 字节")
            } finally {
                runCatching { b.close() }
            }
        }, "hplay-fwd-pump")
        t.isDaemon = true
        t.start()
        return t
    }
}
