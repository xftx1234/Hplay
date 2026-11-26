package com.han1me.lab

import java.net.URL
import java.util.Base64

/**
 * **订阅链接拉取**（实验分叉 · 2026-10-31）。
 *
 * 用户要求：照 v2rayN 那样，填一条订阅链接就能拉回一堆节点。
 *
 * 支持两种常见格式：
 *  ① **base64 订阅**：整份内容是 base64，解出来是多行 vless/vmess/ss 链接（最常见）；
 *  ② **纯文本订阅**：直接就是多行链接。
 *
 * 解析出的节点**追加**到现有列表（同名/同链接自动去重），不覆盖用户已有节点。
 */
object SubFetcher {

    /** 一次拉取结果。 */
    data class Result(
        val ok: Boolean,
        val nodes: List<LabNode> = emptyList(),
        val message: String = "",
    )

    /**
     * 拉取订阅。
     *
     * @param url 订阅地址（http/https）
     * @return 解析出的节点列表；失败时 ok=false 且 message 说明原因
     */
    fun fetch(url: String, timeoutMs: Int = 20000): Result {
        val u = url.trim()
        if (u.isBlank()) return Result(false, message = "订阅链接为空")
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            return Result(false, message = "订阅链接需 http(s):// 开头")
        }
        val text = runCatching {
            // ★★ 关键：必须显式 NO_PROXY。
            // Hplay 启动时会把 http.proxyHost 指向 127.0.0.1:10808（内置节点），
            // 订阅请求若跟着系统属性走，就会「订阅→内置节点→订阅」形成自环。
            val conn = URL(u).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            // ★★ LABSUBUA（2026-10-31）：机场订阅普遍按 UA 返回不同格式，
            //    用 v2rayN 的 UA 最稳（有些服务端只对「认识的客户端」下发节点）。
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            // ★ 声明接受 gzip（很多订阅体积大，服务端默认 gzip；不声明会拿到未压缩或空）
            conn.setRequestProperty("Accept-Encoding", "gzip")
            val code = conn.responseCode
            if (code !in 200..299) {
                return Result(false, message = "服务器返回 HTTP " + code + "（订阅地址可能失效/欠费/被墙）")
            }
            val raw = conn.inputStream.use { it.readBytes() }
            // gzip 解压（服务端若直接给明文就跳过）
            val body = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                runCatching {
                    java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() }
                }.getOrDefault(raw)
            } else raw
            if (body.isEmpty()) {
                return Result(false, message = "订阅内容为空（服务器返回 200 但正文 0 字节 —— 通常是面板里没有节点/流量耗尽/token 失效）")
            }
            String(body, Charsets.UTF_8)
        }.getOrElse {
            return Result(false, message = "拉取失败: " + (it.message ?: it.javaClass.simpleName))
        }
        if (text.isBlank()) return Result(false, message = "订阅内容为空")

        val links = extractLinks(text)
        if (links.isEmpty()) {
            return Result(false, message = "没解析出节点（内容不是 base64 订阅也没找到 vless:// 链接）")
        }
        val nodes = links.mapNotNull { l ->
            val n = VlessLink.parse(l) ?: return@mapNotNull null
            LabNode(NodeStore.nameOf(l), l)
        }
        if (nodes.isEmpty()) return Result(false, message = "找到 " + links.size + " 条链接，但都不是可解析的 vless 节点")
        return Result(true, nodes, "成功解析 " + nodes.size + " 个节点")
    }

    /** 从订阅文本里抽出节点链接（兼容 base64 与纯文本）。 */
    private fun extractLinks(text: String): List<String> {
        val direct = Regex("(?m)^(vless|vmess|trojan|ss)://\\S+").findAll(text)
            .map { it.value.trim() }.toList()
        if (direct.isNotEmpty()) return direct
        // 尝试 base64 解码（去空白后再解，兼容换行）
        val cleaned = text.replace(Regex("\\s"), "")
        val decoded = runCatching {
            String(Base64.getMimeDecoder().decode(cleaned), Charsets.UTF_8)
        }.getOrNull()
        if (decoded.isNullOrBlank()) return emptyList()
        return Regex("(?m)^(vless|vmess|trojan|ss)://\\S+").findAll(decoded)
            .map { it.value.trim() }.toList()
    }

    /** 从订阅链接推导一个可读的源名（给「留空自动取名」用）。 */
    fun nameForUrl(url: String): String {
        val host = runCatching { java.net.URI(url.trim()).host.orEmpty() }.getOrDefault("")
        if (host.isNotBlank()) return host
        return url.trim().take(24)
    }

    private const val UA =
        // 机场订阅最认这个 UA（v2rayN 的标识）；纯浏览器 UA 有些服务端不给节点
        "v2rayN/6.31"
}
