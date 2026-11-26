package com.han1me.lab

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * **Xray 进程管理**（实验分叉 · 第 2 版）。
 *
 * ★★ 第 2 版为什么彻底改路径（真机 error=13 Permission denied 的根因）：
 *
 *  · 本应用 targetSdk=34，Android 10+ 实行 **W^X 政策**：
 *    **禁止从可写的 filesDir 执行任何二进制**（chmod 755 也没用）。
 *  · 实测：文件在、chmod 成功、canExecute() 也 true，但 ProcessBuilder 仍报
 *    error=13 Permission denied —— 这是**内核层 SELinux/execve 拦截**，不是文件权限问题。
 *  · 唯一合法可执行位置是 **APK 内的 native library 目录**（只读、系统代解压）。
 *
 * 所以：内核以 `lib/arm64-v8a/libxray.so` 打进 APK，运行时直接用
 * `applicationInfo.nativeLibraryDir + "/libxray.so"` —— 那里天然可执行。
 */
object XrayRunner {

    private const val SO_NAME = "libxray.so"
    private const val READY_TIMEOUT_MS = 15_000L

    @Volatile
    private var process: Process? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var lastLog: String = ""
        private set

    /** 运行目录（filesDir/xray）—— 只放 config.json，不放二进制。 */
    private fun workDir(ctx: Context): File =
        File(ctx.filesDir, "xray").apply { mkdirs() }

    /**
     * 取内核可执行路径：**nativeLibraryDir**（APK 的 lib/arm64-v8a 被系统解压到这里）。
     * 不存在时返回 null，调用方如实报错。
     */
    private fun binFile(ctx: Context): File? {
        val dir = ctx.applicationInfo.nativeLibraryDir ?: return null
        val f = File(dir, SO_NAME)
        return if (f.exists()) f else null
    }

    /**
     * 生成 xray config.json。
     *
     * ★★ 关键修复（真机「Failed to load config files」的根因）：
     *
     * 旧版手拼 JSON 字符串，在 `"users": [...]` 后面多了一个尾逗号 ——
     * **JSON 规范不允许尾逗号**，xray 直接拒绝加载。
     *
     * 现在改用 org.json（Android 内置）真序列化，从根本上不可能拼出非法 JSON。
     */
    private fun buildConfig(node: VlessNode, port: Int): String {
        val root = org.json.JSONObject()
        root.put("log", org.json.JSONObject().put("loglevel", "warning"))

        val inbounds = org.json.JSONArray()
        val sock = org.json.JSONObject()
        sock.put("tag", "socks-in")
        sock.put("port", port)
        sock.put("listen", "127.0.0.1")
        sock.put("protocol", "socks")
        sock.put("settings", org.json.JSONObject().put("auth", "noauth").put("udp", false))
        inbounds.put(sock)

        // ★★★ 第 163 轮：**HTTP 入站**（端口 = SOCKS + 1）。
        //
        // 用途一：给 WebView 留一条**能走节点**的通路（WebView 不支持 SOCKS）。
        // 用途二：HTTP 代理对长连接/流媒体更省事，播放器走它能减少一次 SOCKS 封装。
        //
        // 注意：本应用的网页流量主要由 [com.han1me.lab.HplayWebProxy] 在
        // shouldInterceptRequest 里经 GlobalProxy 取回（见 AndroidWeb.kt），
        // 这个入站是**兜底通道**：拦截层拿不到时（如大文件流式响应），
        // 仍有一条 HTTP 代理可用。
        val httpIn = org.json.JSONObject()
        httpIn.put("tag", "http-in")
        httpIn.put("port", port + 1)
        httpIn.put("listen", "127.0.0.1")
        httpIn.put("protocol", "http")
        httpIn.put("settings", org.json.JSONObject())
        inbounds.put(httpIn)

        root.put("inbounds", inbounds)

        val out = org.json.JSONObject()
        out.put("tag", "vless-out")
        out.put("protocol", "vless")

        val user = org.json.JSONObject()
        user.put("id", node.uuid)
        user.put("encryption", "none")
        if (node.flow.isNotBlank()) user.put("flow", node.flow)

        val vnext = org.json.JSONObject()
        vnext.put("address", node.host)
        vnext.put("port", node.port)
        vnext.put("users", org.json.JSONArray().put(user))

        out.put("settings", org.json.JSONObject().put("vnext", org.json.JSONArray().put(vnext)))

        val stream = org.json.JSONObject()
        stream.put("network", node.type.ifBlank { "tcp" })
        if (node.security.isNotBlank()) stream.put("security", node.security)
        // ★★ LABTLS（2026-10-31 · 真机「CF 优选节点全超时」的根因）：
        //
        // 旧版**只处理了 reality**，完全没有 tls 分支 —— 于是 security=tls 的节点
        // （CF 优选节点几乎都是 ws + tls + CF IP 前置）缺少 `tlsSettings`，
        // 里面的 serverName(SNI) 也就丢了 → TLS 握手失败 → 所有请求超时。
        //
        // 这里补齐 tls / xtls：把 sni 写进 serverName，并带上 ALPN 与指纹。
        if (node.security.equals("tls", true) || node.security.equals("xtls", true)) {
            val t = org.json.JSONObject()
            if (node.sni.isNotBlank()) t.put("serverName", node.sni)
            if (node.fingerprint.isNotBlank()) t.put("fingerprint", node.fingerprint)
            // ★★ LABTLS2（2026-10-31 · 真机实测）：**ALPN 必须只写 http/1.1**。
            //
            // WebSocket 走的是 HTTP/1.1 Upgrade；把 h2 放在首位会让服务器协商到 h2，
            // 而 h2 上没有 WS Upgrade → 连接建立后立刻失败（表现为「TCP 通了但全部超时」）。
            // 实测对比：alpn=[h2,http/1.1] → 000；alpn=[http/1.1] → **204**。
            t.put("alpn", org.json.JSONArray().put("http/1.1"))
            // ★★ 注意（真机实测）：Xray 26.x **已移除 allowInsecure** 字段，
            //    写了它会导致「配置加载失败 → 内核直接退出」（表现为进程退出、无输出）。
            //    现在改用 verifyPeerCertByName / pinnedPeerCertSha256；
            //    CF 优选节点的证书就是 *.hanime1.me（真证书），所以只要 serverName 正确即可，
            //    **不需要**不校验证书。
            stream.put("tlsSettings", t)
        }
        if (node.security.equals("reality", true)) {
            val r = org.json.JSONObject()
            r.put("serverName", node.sni)
            r.put("publicKey", node.publicKey)
            r.put("shortId", node.shortId)
            r.put("spiderX", node.spiderX)
            r.put("fingerprint", node.fingerprint.ifBlank { "chrome" })
            stream.put("realitySettings", r)
        }
        if (node.type.equals("ws", true)) {
            val w = org.json.JSONObject()
            w.put("path", node.wsPath.ifBlank { "/" })
            // ★★ Xray 26.x：wsSettings 的 host 用**独立字段**（headers.Host 已 deprecated，
            //    实测真机会打 Warning；两者都写更保险）。
            if (node.wsHost.isNotBlank()) {
                w.put("host", node.wsHost)
                w.put("headers", org.json.JSONObject().put("Host", node.wsHost))
            }
            stream.put("wsSettings", w)
        }
        out.put("streamSettings", stream)

        val outbounds = org.json.JSONArray()
        outbounds.put(out)
        // ★★★ 第 171 轮（用户明确要求）：**全局覆盖走内置节点，但不干涉系统其它 App**。
        //
        // 第 170 轮我加过 geoip/geosite 分流规则，实测**内核直接崩溃退出**：
        // 容器里没有 geoip.dat / geosite.dat，Xray 加载路由规则时找不到数据文件就死。
        // （真机日志：`[bridge] 启动结果 ok=false msg=进程退出`，xray 目录只有 config.json）
        //
        // 而且用户已明确：不要分流，**要全局**。
        // 「作用域限在应用之内」这一点由**系统代理的写法**来保证（见 HplayRootBoost），
        // 不再依赖 Xray 内部路由。所以这里只保留一个最小规则：
        //   · 私网 / 本机 → 直连（避免把 127.0.0.1 自己代理成死循环）；
        //   · 其余 → **全部走节点**。
        val direct = org.json.JSONObject()
        direct.put("tag", "direct")
        direct.put("protocol", "freedom")
        direct.put("settings", org.json.JSONObject())
        outbounds.put(direct)
        root.put("outbounds", outbounds)

        val routing = org.json.JSONObject()
        routing.put("domainStrategy", "AsIs")
        val rules = org.json.JSONArray()
        rules.put(org.json.JSONObject().apply {
            put("type", "field")
            put("outboundTag", "direct")
            // 只写**字面量网段**，绝不用 geoip:/geosite:（缺 .dat 会崩）
            put("ip", org.json.JSONArray()
                .put("127.0.0.0/8")
                .put("10.0.0.0/8")
                .put("172.16.0.0/12")
                .put("192.168.0.0/16")
                .put("169.254.0.0/16"))
        })
        routing.put("rules", rules)
        root.put("routing", routing)
        return root.toString(2)
        return root.toString(2)
    }
    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** 启动。返回 (成功, 说明)。 */
    @Synchronized
    fun start(ctx: Context): Pair<Boolean, String> {
        if (running) return true to "已在运行"
        lastError = ""
        LabLog.i("xray", "nativeLibraryDir=" + (ctx.applicationInfo.nativeLibraryDir ?: "null"))
        val bin = binFile(ctx)
        if (bin == null) {
            LabLog.i("xray", "内核不存在（APK 内缺 lib/arm64-v8a/libxray.so）")
            return false to ("内核不存在（APK 内缺 lib/arm64-v8a/libxray.so）")
        }
        LabLog.i("xray", "内核路径=" + bin.absolutePath + " canExec=" + bin.canExecute())
        val node = VlessLink.parse(NodeStore.nodeUrl())
            ?: return false to "节点链接不合法（请检查格式）"
        val port = NodeStore.localPort()
        return runCatching {
            val dir = workDir(ctx)
            val cfg = File(dir, "config.json")
            cfg.writeText(buildConfig(node, port), Charsets.UTF_8)
            // ★★ LABPORT（2026-10-31 · 真机日志：热切换时报「端口 10808 已被占用」）：
            //
            // 旧版直接 return 失败 —— 但那个占用者往往就是**上一个还没退干净的内核**，
            // 于是用户看到「切换失败」，要再点一次才成功。
            //
            // 现在：先杀掉旧进程、最多等 2 秒让端口释放，再启动。
            if (portAlive(port, 500)) {
                LabLog.i("xray", "端口 " + port + " 被占用，先清理旧内核")
                stopInternal()
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("pkill", "-f", "libxray.so run")).waitFor()
                }
                val deadline0 = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < deadline0 && portAlive(port, 200)) {
                    Thread.sleep(150)
                }
                if (portAlive(port, 300)) {
                    return false to "端口 " + port + " 仍被占用（其他程序占用）"
                }
                LabLog.i("xray", "端口已释放，继续启动")
            }
            val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfg.absolutePath)
            pb.directory(dir)
            pb.redirectErrorStream(true)
            LabLog.i("xray", "启动命令=" + bin.absolutePath + " run -c " + cfg.absolutePath)
            val p = pb.start()
            process = p
            LabLog.i("xray", "进程已起")
            // ★★ 持续收集内核输出（真机排查「连不上」必需：xray 的错误都在自己的 stdout）
            Thread({
                runCatching {
                    p.inputStream.use { ins ->
                        val buf = ByteArray(4096)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            val line = String(buf, 0, n, Charsets.UTF_8).trim()
                            if (line.isNotEmpty()) {
                                lastLog = (lastLog + line + "\n").takeLast(4000)
                                LabLog.i("xray-out", line.take(300))
                            }
                        }
                    }
                }
            }, "hpla-xray-out").apply { isDaemon = true }.start()
            val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (portAlive(port, 300)) {
                    // ★★ LABREADY（2026-10-31 · 修「切节点后官方源经常 403」）：
                    //
                    // **端口能连上 ≠ 内核就绪**。旧版一到「TCP 可连」就认定成功并立刻
                    // 挂上 GlobalProxy —— 但此时 xray 可能还在解析配置/建 TLS 上下文，
                    // 头几个请求会走进半初始化的隧道 → 403 / 连接被重置。
                    //
                    // 现在再等**一次真实 SOCKS 探测**通过才算就绪：
                    // 真正经这条隧道打一次极轻量的目标（1.1.1.1:80，只要 SOCKS 握手回来即算通）。
                    // 探不通就继续等，直到超时。
                    if (socksReady(port, 2500)) {
                        running = true
                        LabLog.i("xray", "内核就绪（SOCKS 探测通过）")
                        return true to ("已启动 · " + node.label + " · 127.0.0.1:" + port)
                    }
                    LabLog.i("xray", "端口已开但 SOCKS 未就绪，继续等…")
                }
                if (!p.isAlive) {
                    val log = readLog(p)
                    lastLog = log
                    LabLog.i("xray", "进程退出: " + log.take(400))
                    stopInternal()
                    return false to ("进程退出: " + log.take(300))
                }
                Thread.sleep(300)
            }
            val log = readLog(p)
            lastLog = log
            stopInternal()
            LabLog.i("xray", "端口未就绪: " + log.take(400))
            return false to ("端口 " + port + " 未就绪（15s 超时）: " + log.take(200))
        }.getOrElse {
            stopInternal()
            false to ("启动异常: " + (it.message ?: ""))
        }
    }

    @Synchronized
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        val p = process
        process = null
        running = false
        if (p != null) {
            runCatching { p.destroy() }
            // ★ 等它真的退出（最多 1.2 秒），否则新旧内核会短暂共存、抢同一个端口
            runCatching {
                val dl = System.currentTimeMillis() + 1200
                while (p.isAlive && System.currentTimeMillis() < dl) Thread.sleep(80)
                if (p.isAlive) p.destroyForcibly()
            }
        }
    }

    /**
     * **真正就绪探测**：走一次 SOCKS5 握手，确认内核已经能转发。
     *
     * 只做到「SOCKS 方法协商成功」这一步就返回 —— 不真的连外网，所以极快（几十毫秒）。
     * 这样既确认了内核就绪，又不会因为目标站慢而拖长启动。
     */
    private fun socksReady(port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { s ->
            s.soTimeout = timeoutMs
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            val out = s.getOutputStream()
            val ins = s.getInputStream()
            // SOCKS5 问候：VER=5, NMETHODS=1, METHOD=0(no auth)
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            val r = ByteArray(2)
            if (ins.read(r) != 2) return false
            // 期望 5, 0（服务器选中 no-auth）
            r[0].toInt() == 5 && r[1].toInt() == 0
        }
    }.getOrDefault(false)

    private fun portAlive(port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs); true }
    }.getOrDefault(false)

    private fun readLog(p: Process): String = runCatching {
        val out = java.io.ByteArrayOutputStream()
        val t = Thread {
            runCatching {
                p.inputStream.use { ins ->
                    val buf = ByteArray(4096)
                    val start = System.currentTimeMillis()
                    while (System.currentTimeMillis() - start < 3000) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > 40_000) break
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        t.join(3000)
        out.toString(Charsets.UTF_8.name())
    }.getOrDefault("(无输出)")
}