package com.han1me.lab

import java.net.URLDecoder

/**
 * **VLESS 链接解析**（实验分叉）。
 *
 * 用户要求「节点链接可自由填写，方便替换」—— 所以支持完整 vless:// 链接。
 *
 * 支持参数：
 *  · security = reality / tls / none
 *  · type     = tcp / ws / grpc
 *  · sni / pbk / sid / spx / fp（REALITY 必需）
 *  · flow     = xtls-rprx-vision（可选）
 */
data class VlessNode(
    val uuid: String,
    val host: String,
    val port: Int,
    val security: String = "",
    val type: String = "tcp",
    val sni: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val fingerprint: String = "chrome",
    val flow: String = "",
    val wsPath: String = "",
    val wsHost: String = "",
    val name: String = "",
) {
    val label: String get() = name.ifBlank { host + ":" + port }
}

object VlessLink {

    /**
     * 解析 vless:// 链接。返回 null 表示链接不合法（调用方据此如实报错）。
     */
    fun parse(link: String): VlessNode? {
        val s = link.trim()
        if (!s.startsWith("vless://", ignoreCase = true)) return null
        val body = s.substring("vless://".length)
        val at = body.indexOf('@')
        if (at < 0) return null
        val uuid = body.substring(0, at)
        val rest = body.substring(at + 1)

        // host:port（在 ? 或 # 之前）
        var hostPort = rest.substringBefore('?').substringBefore('#').trim()
        // ★★ 关键修复（真机「切换别的链接显示节点链接不合法」的根因）：
        //
        // 很多机场链接写成 `@1.2.3.4:443/?type=tcp...` —— **端口后面带一个斜杠**。
        // 旧版直接 toIntOrNull() 拿到 "443/" 就失败，整条链接被判非法。
        // 这里先把结尾的斜杠（以及可能的路径残留）剥掉。
        hostPort = hostPort.trimEnd('/').trim()
        // 兜底：万一还带路径（如 1.2.3.4:443/abc），只取第一段
        hostPort = hostPort.substringBefore('/')
        // ★ IPv6 字面量形如 [::1]:443 —— 用方括号判定，避免 substringAfterLast(':') 取错
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(1, close)
            port = hostPort.substring(close + 1).removePrefix(":").trim().toIntOrNull() ?: return null
        } else {
            if (!hostPort.contains(':')) return null
            host = hostPort.substringBeforeLast(':', "")
            port = hostPort.substringAfterLast(':', "").trim().toIntOrNull() ?: return null
        }
        if (host.isBlank() || port !in 1..65535) return null

        val q = query(rest)
        val name = runCatching {
            URLDecoder.decode(rest.substringAfter('#', ""), "UTF-8")
        }.getOrDefault("")

        return VlessNode(
            uuid = uuid,
            host = host,
            port = port,
            security = q["security"] ?: "",
            type = (q["type"] ?: "tcp").ifBlank { "tcp" },
            sni = q["sni"] ?: q["host"] ?: "",
            publicKey = q["pbk"] ?: "",
            shortId = q["sid"] ?: "",
            spiderX = q["spx"] ?: "",
            fingerprint = (q["fp"] ?: "chrome").ifBlank { "chrome" },
            flow = q["flow"] ?: "",
            wsPath = q["path"] ?: "",
            wsHost = q["host"] ?: "",
            name = name,
        )
    }

    private fun query(s: String): Map<String, String> {
        val q = s.substringAfter('?', "").substringBefore('#')
        if (q.isBlank()) return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (kv in q.split('&')) {
            if (kv.isBlank()) continue
            val k = kv.substringBefore('=')
            val v = runCatching {
                URLDecoder.decode(kv.substringAfter('=', "").replace("+", "%2B"), "UTF-8")
            }.getOrDefault(kv.substringAfter('=', ""))
            m[k] = v
        }
        return m
    }
}