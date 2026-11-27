package com.han1me.lab

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * ★★★ 第 160 轮（用户要求：「把网页端播放也走内置节点路线进行代理」）。
 *
 * ## 为什么必须单独做这一层
 *
 * **Android 的 WebView 不支持 SOCKS 代理**，也不吃 JVM 的 `http.proxyHost` 之类属性 ——
 * 项目里 `Han1meWeb.kt` 的注释原话就是「WebView 走系统代理（不吃 JVM 代理属性）」。
 * 而内置节点对外是 SOCKS5，于是：
 *
 *   · 原生解析（OkHttp + GlobalProxy.selector()） → **走节点** ✅
 *   · 网页版介绍页 / 播放 / 封面（WebView）     → **直连** ❌
 *
 * 这些站直连基本不通（实测 99热 / 青青草 直连返回 000），
 * 所以「网页播放打不开、封面一片空」的真正原因就在这里。
 *
 * `ProxyController` + `ProxyConfig` 本来能设 WebView 代理，但 **`ProxyConfig` 是隐藏 API
 * （@SystemApi）**，普通应用编译期就拿不到（实测 `unresolved reference: ProxyConfig`）。
 *
 * ## 这里的做法：**自己把请求取回来**
 *
 * 在 WebView 的 `shouldInterceptRequest` 里，用走 `GlobalProxy` 的 OkHttp 把请求拉回来，
 * 再包成 `WebResourceResponse` 还给 WebView。对 WebView 而言这就是一次普通响应 ——
 * 页面的 HTML / CSS / JS / 图片 / 视频分片**全部经节点**，无需任何系统权限，也不影响别的应用。
 *
 * ## 纪律
 *  · 只在内置节点开启时介入（`GlobalProxy.active`），并尊重 scope 的直连例外；
 *  · **任何异常都返回 null**，让 WebView 退回它自己的路径 —— 绝不把页面搞成白屏；
 *  · 只接管 http/https，其它 scheme 原样交回；
 *  · 透传 `Range`（视频拖动进度要它）与状态码 206。
 */
object HplayWebProxy {

    /** 走 GlobalProxy 的客户端。连接池放大 + 长超时（经节点的首包可能偏慢）。 */
    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .addInterceptor(com.han1me.log.LoggingInterceptor)
            .proxySelector(GlobalProxy.selector())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 把这条 WebView 请求经节点取回；返回 null 表示「不接管，交回 WebView 自己处理」。
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!GlobalProxy.active) return null
        val url = request.url ?: return null
        val scheme = url.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return null
        val host = url.host.orEmpty()
        // scope=nonDirect 时，国内直连域名仍走直连（与 OkHttp 侧同一判据，避免两套逻辑打架）
        if (GlobalProxy.proxyForHost(host) == null) return null
        return runCatching { fetch(request) }.getOrNull()
    }

    private fun fetch(request: WebResourceRequest): WebResourceResponse? {
        val method = request.method.uppercase()
        // 只接管读类请求；POST/PUT 等交回 WebView，避免表单语义被破坏
        if (method != "GET" && method != "HEAD") return null
        val b = okhttp3.Request.Builder().url(request.url.toString()).method(method, null)
        for ((k, v) in request.requestHeaders) {
            val key = k.lowercase()
            // 这几个由 OkHttp 自己管，手工带上会冲突/报错
            if (key == "host" || key == "connection" || key == "accept-encoding" ||
                key == "content-length" || key == "transfer-encoding"
            ) continue
            runCatching { b.header(k, v) }
        }
        // ★ 第 185 轮（用户报「网页版很多视频播不了」）：
        //
        // 视频能否在网页里播，取决于三件事，缺一个播放器就不干活：
        //  ① **Range 请求要透传**（<video> 拖动进度时发 Range，不透传就一直在开头缓冲）；
        //  ② **206 + Content-Range 要原样回去**（否则播放器认为不支持 seek）；
        //  ③ **Accept-Ranges: bytes** 要回（否则部分播放器直接判定「不可拖动」）。
        //
        // 下面把这三样都补齐；并给媒体请求一个更长的读超时（经节点首包偏慢）。
        val range = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value.orEmpty()
        if (range.isNotBlank()) runCatching { b.header("Range", range) }
        // 第 236 轮（cos 板块实测）：带反爬 Cookie 挑战的站（cosplaytele/cossora 一族），
        // 陷阱页 JS 种下 cookie 后重载 —— WebView 的 CookieManager 里有这份 cookie，
        // 但下面 OkHttp 是新请求，不带就会被再发一次陷阱 → 无限循环。
        // 这里把 WebView CookieManager 里该 URL 的 cookie 附加为 Cookie 头，闭环挑战。
        runCatching {
            val ck = android.webkit.CookieManager.getInstance().getCookie(request.url.toString())
            if (!ck.isNullOrBlank()) b.header("Cookie", ck)
        }
        // 第 237 轮（cos 板块实测）：**主框架导航没有 Referer**（WebView 顶层导航不带），
        // 而 cossora 一族对「无 Referer」一律 404 + Unknown Error xD（实测矩阵：
        // 无 Ref 403/404；带任意 Referer——包括自引——都返回完整播放页）。
        // 兜底：没有 Referer 时用 URL 自身作自引（实测自引即可过）。
        if (b.build().header("Referer") == null) {
            runCatching { b.header("Referer", request.url.toString()) }
        }
        // 第 185 轮：不少站点对「没有 Referer / UA 不像浏览器」的媒体请求直接 403。
        // WebView 原本请求头里就带这些，上面已逐个透传；这里兜底补 UA 与 Referer。
        if (b.build().header("User-Agent") == null) {
            runCatching {
                b.header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36")
            }
        }
        runCatching {
            if (request.url.toString().contains("cossora")) {
                val dbg = StringBuilder("REQ " + method + " " + request.url + "\n")
                for ((k, v) in b.build().headers) dbg.append("  H " + k + ": " + v + "\n")
                java.io.FileWriter("/sdcard/cos_req.txt", true).use { f -> f.append(dbg.toString()) }
            }
        }
        val resp = http.newCall(b.build()).execute()
        runCatching {
            if (request.url.toString().contains("cossora")) {
                val dbg = StringBuilder("RESP " + resp.code + " " + resp.header("Content-Type") + "\n")
                for ((k, v) in resp.headers) dbg.append("  H " + k + ": " + v + "\n")
                val bb = resp.peekBody(200)
                dbg.append("  B " + bb.string().replace("\n", " ").take(150) + "\n")
                java.io.FileWriter("/sdcard/cos_debug.txt", true).use { f -> f.append(dbg.toString()) }
            }
        }
        val body = resp.body ?: run { resp.close(); return null }
        val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
        val headers = HashMap<String, String>()
        for (name in listOf(
            "Content-Range", "Accept-Ranges", "Content-Length",
            "Cache-Control", "ETag", "Last-Modified", "Content-Encoding",
        )) {
            resp.header(name)?.let { headers[name] = it }
        }

        // 第 236 轮（cos 板块实测）：Set-Cookie 必须回种到 WebView 的 CookieManager。
        // 带反爬挑战的站先发 JS 陷阱页：浏览器执行它种下 cookie 再重载，服务器见到
        // cookie 才发真页面。若 Set-Cookie 不回种，WebView 每次重载都拿到新陷阱 → 永远白屏。
        val setCookies = resp.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            val cm = android.webkit.CookieManager.getInstance()
            val abs = request.url.toString()
            val main = Runnable {
                for (sc in setCookies) runCatching { cm.setCookie(abs, sc) }
                runCatching { cm.flush() }
            }
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).post(main)
                main.run()
            }
        }
        // 第 185 轮：媒体一定要有 Accept-Ranges，否则不少播放器判定「不可 seek」而不播
        if (mime.startsWith("video/") || mime.startsWith("audio/") || mime.contains("mpegurl")) {
            if (!headers.containsKey("Accept-Ranges")) headers["Accept-Ranges"] = "bytes"
        }
        val stream: InputStream = object : InputStream() {
            private val inner = body.byteStream()
            override fun read(): Int = inner.read()
            override fun read(b2: ByteArray, off: Int, len: Int): Int = inner.read(b2, off, len)
            override fun available(): Int = runCatching { inner.available() }.getOrDefault(0)
            override fun close() {
                runCatching { inner.close() }
                runCatching { resp.close() }
            }
        }
        val reason = if (resp.code == 206) "Partial Content" else "OK"
        return WebResourceResponse(mime, "utf-8", resp.code, reason, headers, stream)
    }
}