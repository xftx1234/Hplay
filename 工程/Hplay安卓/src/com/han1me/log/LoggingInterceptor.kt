package com.han1me.log

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * **OkHttp 全链路日志拦截器**（第 334 轮）。
 *
 * ## 为什么用拦截器而不是逐处埋点
 *
 * 全项目有 14 个 `OkHttpClient.Builder()`（`build/gen` 8 个 + `src` 6 个）：
 *  · gen：HanimeClient 主客户端 + DoH/边缘探测用的 4 个临时客户端 + X99 的 3 个；
 *  · src：CosBoard 两个、HplayWebProxy、GitHubReleases、UpdateChecker、UpdateService。
 *
 * 逐个加 `AppLog.net(...)` 有两个问题：① 14 处都要改，漏一处就是盲区；
 * ② 以后新增客户端还会漏。所以写一个拦截器，由 `tools/postpatch.py` 挂到**每一个**
 * builder 上 —— 只要新代码沿用既有写法，日志自动覆盖。
 *
 * ## 记什么
 *
 *  · 方法 + host + path（**不记 query**：里面可能有 token / 签名 / 手机号）；
 *  · 状态码 + 响应字节数 + 耗时；
 *  · 请求/响应头里几个有诊断价值的（Content-Type / Content-Length / Cf-Mitigated /
 *    Location / x-ratelimit-remaining）—— 这些正是排查 CF 与限额问题时要看的；
 *  · 失败时异常类名 + message。
 *
 * ## 不记什么（有意）
 *
 *  · **不读 body**。OkHttp 的 body 是流，读了就要 `peekBody` 或全量 buffer，
 *    对 20 MB 的 APK 下载与几百 MB 的视频分片是灾难。日志只要知道「多大、多久、
 *    什么状态码」就够了；真要内容用 getHtml 自己的打点。
 *  · **不记 Cookie 头**。里面是 `cf_clearance` / `user_session`，等于账号凭据。
 *  · **不记 Authorization**。139 / GitHub token 都在里面。
 */
object LoggingInterceptor : Interceptor {

    /** 这些 header 有诊断价值，抄进日志。 */
    private val INTERESTING = arrayOf(
        "Content-Type", "Content-Length", "Cf-Mitigated",
        // ★★★ 第 344 轮（全量核查 · P1）：**Location 移出白名单**。
        //
        // 它原来在这里，而 SECRET 只排除 cookie/authorization —— 于是媒体站 302 的
        // Location（普遍带一次性签名 ?token=…&expire=…）与网盘直链（sign=…）会被
        // 抄前 60 字符进 hplay.log，而日志面板有「导出并分享到微信/QQ」一键。
        // 这是把播放凭据主动交出去的路径。
        //
        // 需要看跳转去向时，走下面 LOCATION_SAFE 的写法：只记 origin + path，丢弃 query。
        "x-ratelimit-remaining", "Retry-After", "Server",
    )

    /** 这些 header 绝不能进日志（凭据）。 */
    private val SECRET = arrayOf("cookie", "set-cookie", "authorization", "proxy-authorization")

    override fun intercept(chain: Interceptor.Chain): Response {
        // 关闭时零开销：一次 volatile 读就返回，连计时都不做。
        if (!AppLog.isOn) return chain.proceed(chain.request())

        val req = chain.request()
        val t0 = System.currentTimeMillis()
        val method = req.method
        val host = req.url.host
        // path 不含 query（query 可能带 token / secure 签名 / 手机号）
        val path = req.url.encodedPath
        val qn = if (req.url.querySize > 0) "?…(" + req.url.querySize + ")" else ""

        return try {
            val resp = chain.proceed(req)
            val ms = System.currentTimeMillis() - t0
            val len = resp.body?.contentLength() ?: -1L
            val extra = buildString {
                for (h in INTERESTING) {
                    val v = resp.header(h) ?: continue
                    if (v.isBlank()) continue
                    if (SECRET.any { it.equals(h, true) }) continue
                    if (length > 0) append(" ")
                    append(h).append("=").append(v.take(60))
                }
            }

            // ★ 第 344 轮：Location 只记 origin+path（query 里是签名/token）
            resp.header("Location")?.let { loc ->
                val safe = runCatching {
                    val u = java.net.URI(loc)
                    (u.scheme ?: "") + "://" + (u.host ?: "") + (u.path ?: "")
                }.getOrDefault("(解析失败)")
                AppLog.w("HTTP", "  -> Location " + safe.take(80))
            }
            AppLog.w(
                "HTTP",
                method + " " + host + path + qn + " -> " + resp.code +
                    (if (len >= 0) " " + len + "B" else " ?B") + " " + ms + "ms" + extra,
            )
            resp
        } catch (t: Throwable) {
            val ms = System.currentTimeMillis() - t0
            AppLog.w(
                "HTTP",
                method + " " + host + path + qn + " !! " + t.javaClass.simpleName +
                    ": " + (t.message ?: "").take(120) + " " + ms + "ms",
            )
            throw t
        }
    }
}