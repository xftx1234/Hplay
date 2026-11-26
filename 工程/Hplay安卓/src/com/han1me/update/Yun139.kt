package com.han1me.update

import android.util.Base64
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 中国移动云盘（139）**直连**客户端。
 *
 * ## 为什么有这个文件
 * App 的「检查更新」要**直接问移动云盘**（`/移动云盘134/hplay`），不再绕自建网盘。
 * 自建网盘那条路的问题：它是家里的服务（公网域名 + Cloudflare），**移动流量下请求不到**，
 * 于是「检查更新」在户外直接不可用；而移动云盘本身是公网可达的。
 *
 * ## 这条链路是实测打通的（不是照文档猜的）
 *
 * ~~~
 * 1) POST https://user-njs.yun.139.com/user/route/qryRoutePolicy
 *      body: {"userInfo":{"userType":1,"accountType":1,"accountName":"<手机号>"},"modAddrType":1}
 *    -> data.routePolicyList[modName=personal].httpsUrl  （实测 https://personal-kd-njs.yun.139.com/hcy）
 * 2) POST <personalHost>/file/list  parentFileId="root"      -> 找到 hplay 目录的 fileId
 * 3) POST <personalHost>/file/list  parentFileId=<hplay id>  -> 列出 version.json 与各 APK
 * 4) POST <personalHost>/file/getDownloadUrl {fileId}       -> data.url / data.cdnUrl（139 自家 CDN 直链）
 *    -> 实测 GET 该直链 206 + 前四字节 PK（清单与 APK 都验过）
 * ~~~
 *
 * ## ★ 一个必须记住的坑（第 49 轮那份实现踩过）
 * 取 APK 的 fileId **必须按清单里的 `apk` 文件名精确匹配**，
 * **不能**取「遍历到的最后一个 .apk」。
 * 实测：目录里按 updated_at 倒序是 45/44/43/38/22，最后一个恰好是**永久保留的 v1.0.22**；
 * 那样「检查更新」会把**旧版**当成新版交出去。
 *
 * ## 另一个坑
 * accountName **必须是 Basic 里的手机号**（authorization 解出 `pc:<手机号>:<token>` 的第 2 段），
 * **不能**用挂载配置里的 username（139 那种挂载里它是空串）。
 * 传空串时路由接口回 success=false / code=05010001，看起来像「凭据失效」，其实是**参数缺失**。
 *
 * ## 签名
 * mcloud-sign = calSign(body, ts, rand)：URL-encode(body) -> 按字符排序 -> base64 -> MD5 组合，全程只需 MD5。
 * 与 `工程\mea网盘\edge-openlist-src\worker\src\drivers\139.ts` 的实现逐字对齐。
 */
object Yun139 {

    /**
     * 凭据。**不硬编码在源码里** —— 由构建期从本机配置文件生成 [Yun139Cred] 注入
     * （见 `tools/139_cred.txt`，该文件不入库），也可在「检查更新」面板里改。
     */
    @Volatile
    var authorization: String = ""

    /**
     * **全部账号**（第 114 轮），顺序即尝试顺序：主账号（移动云盘134）在前、备用在后。
     *
     * 为什么不只留一个：单个 token 过期时（实测 route 接口回 05050006），
     * 旧版会**整条 139 路失效**、被迫回落自建网盘 —— 用户看到的就是「自建网盘兜底」。
     * 多账号让「换过 token」「某个账号异常」不再让 139 直连整体不可用。
     * 为空时退回只看 [authorization]（兼容用户在设置里手填单个凭据的情形）。
     */
    @Volatile
    var authorizations: List<String> = emptyList()

    private const val ROUTE_URL = "https://user-njs.yun.139.com/user/route/qryRoutePolicy"

    /** 实测拿到的 host；留作兜底（路由接口偶发失败时直接用它，省一次往返）。 */
    private const val FALLBACK_HOST = "https://personal-kd-njs.yun.139.com/hcy"

    /** 构建版本目录（用户在 139 上指定的落点，与 push_to_mea.py 的 REMOTE_DIR 末段一致）。 */
    /**
     * ★★ 实验版隔离（2026-10-30）：**改为 "hpla实验"**，与上游 "hplay" 分开。
     *
     * 用户铁律：不得与上游更新源污染交叉 —— 实验版绝不能去读上游的更新清单。
     */
    private const val BUILD_DIR = "hplay"   // 主线保持 hplay（实验区才是 hpla实验）

    private const val MANIFEST = "version.json"

    // 第 223 轮：显式 NO_PROXY —— 移动云盘 139 是国内直连可达的，走系统代理(内置节点)反而慢
    private val http: OkHttpClient by lazy { OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build() }

    /** 路由结果的短时缓存：一次「检查更新」里要调好几次 API，不必每次都问路由。 */
    @Volatile
    private var hostCache: String = ""

    private fun md5Hex(b: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    /** URL-encode（与 139.ts 的 encURI 对齐：这几类字符保持原样）。 */
    private fun encUri(s: String): String {
        val enc = java.net.URLEncoder.encode(s, "UTF-8")
        return enc.replace("+", "%20").replace("%21", "!").replace("%27", "'")
            .replace("%28", "(").replace("%29", ")").replace("%2A", "*")
    }

    private fun calSign(body: String, ts: String, rand: String): String {
        val sorted = encUri(body).toCharArray().sorted().joinToString("")
        val b64 = Base64.encodeToString(sorted.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val a = md5Hex(b64.toByteArray(Charsets.UTF_8))
        val b = md5Hex(md5Hex("${ts}:${rand}".toByteArray(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
        return md5Hex((a + b).toByteArray(Charsets.UTF_8)).uppercase()
    }

    private fun stamp(): Pair<String, String> {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val rand = (1..16).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        return ts to rand
    }

    /** Basic 里的手机号（pc:<手机号>:<token> 的第 2 段）。空串是「凭据不可用」的明确信号。 */
    fun accountFromAuth(auth: String = authorization): String = runCatching {
        val pad = "=".repeat((4 - auth.length % 4) % 4)
        val dec = String(Base64.decode(auth + pad, Base64.DEFAULT), Charsets.UTF_8)
        dec.split(":").getOrNull(1).orEmpty()
    }.getOrDefault("")

    private fun baseHeaders(ts: String, rand: String, sign: String, svcType: String): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "CMS-DEVICE" to "default",
        "Authorization" to "Basic ${authorization}",
        "mcloud-channel" to "1000101",
        "mcloud-client" to "10701",
        "mcloud-sign" to "${ts},${rand},${sign}",
        "mcloud-version" to "7.14.0",
        "Origin" to "https://yun.139.com",
        "Referer" to "https://yun.139.com/w/",
        "x-DeviceInfo" to "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||",
        "x-huawei-channelSrc" to "10000034",
        "x-inner-ntwk" to "2",
        "x-m4c-caller" to "PC",
        "x-m4c-src" to "10002",
        "x-SvcType" to svcType,
        "Inner-Hcy-Router-Https" to "1",
    )

    /** 个人云那套头（与路由那套不同，139 自己两套并存）。 */
    private fun personalHeaders(ts: String, rand: String, sign: String): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Authorization" to "Basic ${authorization}",
        "Caller" to "web",
        "Cms-Device" to "default",
        "Mcloud-Channel" to "1000101",
        "Mcloud-Client" to "10701",
        "Mcloud-Route" to "001",
        "Mcloud-Sign" to "${ts},${rand},${sign}",
        "Mcloud-Version" to "7.14.0",
        "x-DeviceInfo" to "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||",
        "x-huawei-channelSrc" to "10000034",
        "x-inner-ntwk" to "2",
        "x-m4c-caller" to "PC",
        "x-m4c-src" to "10002",
        "x-SvcType" to "1",
        "X-Yun-Api-Version" to "v1",
        "X-Yun-App-Channel" to "10000034",
        "X-Yun-Channel-Source" to "10000034",
        "X-Yun-Client-Info" to "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||dW5kZWZpbmVk||",
        "X-Yun-Module-Type" to "100",
        "X-Yun-Svc-Type" to "1",
    )

    private fun post(url: String, bodyJson: String, headers: Map<String, String>): JSONObject {
        val req = Request.Builder().url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}：${text.take(160)}")
            if (!text.trimStart().startsWith("{")) throw IllegalStateException("139 非 JSON 响应：${text.take(160)}")
            return JSONObject(text)
        }
    }

    /** 路由：拿个人云 host。失败时回落到实测值（省一次往返，也让弱网更稳）。 */
    private fun personalHost(): String {
        hostCache.takeIf { it.isNotBlank() }?.let { return it }
        val (ts, rand) = stamp()
        val acct = accountFromAuth()
        if (acct.isBlank()) throw IllegalStateException(
            "139 凭据不可用（需形如 pc:<手机号>:<token>）",
        )
        val body = JSONObject()
            .put("userInfo", JSONObject().put("userType", 1).put("accountType", 1).put("accountName", acct))
            .put("modAddrType", 1)
            .toString()
        val sign = calSign(body, ts, rand)
        val host = runCatching {
            val j = post(ROUTE_URL, body, baseHeaders(ts, rand, sign, "1"))
            val list = j.optJSONObject("data")?.optJSONArray("routePolicyList")
            var h: String? = null
            for (i in 0 until (list?.length() ?: 0)) {
                val it = list!!.optJSONObject(i)
                if (it?.optString("modName") == "personal") h = it.optString("httpsUrl")
            }
            h?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: FALLBACK_HOST
        hostCache = host
        return host
    }

    private fun list(host: String, parentId: String): JSONObject {
        val (ts, rand) = stamp()
        val body = JSONObject()
            .put("imageThumbnailStyleList", JSONArray().put("Small").put("Large"))
            .put("orderBy", "updated_at")
            .put("orderDirection", "DESC")
            .put("pageInfo", JSONObject().put("pageCursor", "").put("pageSize", 200))
            .put("parentFileId", parentId)
            .toString()
        return post("${host}/file/list", body, personalHeaders(ts, rand, calSign(body, ts, rand)))
    }

    /** 远端清单里的一个事实。 */
    data class Remote(
        val versionName: String,
        val versionCode: Int,
        val size: Long,
        val md5: String,
        val notes: String,
        val apk: String,
        val fileId: String,
    ) {
        val sizeText: String
            get() = if (size >= 1048576) "%.1f MB".format(size / 1048576.0) else "${size} B"
    }

    /**
     * 取 `hplay` 目录里的 `version.json`，并**按清单指名的 apk 文件名**定位 APK 的 fileId。
     *
     * 这里刻意**不**用「最后一个 .apk」：实测目录按 updated_at 倒序为 45/44/43/38/22，
     * 最后一个正是**永久保留的 v1.0.22** —— 那样会把旧版当新版。
     */
    fun fetchManifest(): Remote {
        // 第 114 轮：**逐个账号试**。第一个成功即返回，并把它留在 authorization 上
        //（后续 apkUrl() 还要用它取直链）。全部失败才抛出，且带上各自原因。
        val creds = (authorizations + authorization).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (creds.isEmpty()) throw IllegalStateException("未配置 139 凭据")
        val reasons = ArrayList<String>(creds.size)
        for ((idx, c) in creds.withIndex()) {
            authorization = c
            reset()
            val r = runCatching { fetchManifestOne() }
            r.getOrNull()?.let { return it }
            val who = accountFromAuth(c).takeIf { it.isNotBlank() } ?: ("#" + (idx + 1))
            reasons.add(who + "：" + (r.exceptionOrNull()?.message ?: "未知原因"))
        }
        throw IllegalStateException("139 全部账号都取不到清单（" + reasons.joinToString("；") + "）")
    }

    /** 单账号取清单（原 [fetchManifest] 的主体，第 114 轮拆分出来）。 */
    private fun fetchManifestOne(): Remote {
        if (authorization.isBlank()) throw IllegalStateException("未配置 139 凭据")
        val host = personalHost()
        val root = list(host, "root")
        var dirId: String? = null
        root.optJSONObject("data")?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i)
                if (it?.optString("name") == BUILD_DIR) dirId = it.optString("fileId")
            }
        }
        val dir = dirId ?: throw IllegalStateException("139 上找不到 ${BUILD_DIR} 目录")
        val files = list(host, dir)
        var manifestId: String? = null
        val apkByName = HashMap<String, Pair<String, Long>>()   // 名字 -> (fileId, size)
        files.optJSONObject("data")?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val nm = it.optString("name")
                if (nm == MANIFEST) manifestId = it.optString("fileId")
                else if (nm.endsWith(".apk", true)) {
                    apkByName[nm] = it.optString("fileId") to it.optLong("size", -1L)
                }
            }
        }
        val mid = manifestId ?: throw IllegalStateException("139 上找不到 ${MANIFEST}")
        val o = JSONObject(downloadText(host, mid))
        com.han1me.lab.LabLog.i("root", "Yun139 manifest v" + o.optString("versionName") + " code=" + o.optInt("versionCode") + " apk=" + o.optString("apk"))
        val apkName = o.optString("apk")
        if (apkName.isBlank()) throw IllegalStateException("清单里没写 apk 文件名")
        val hit = apkByName[apkName]
            ?: throw IllegalStateException("139 的 ${BUILD_DIR} 里没有清单指名的 ${apkName}")
        val manifestSize = o.optLong("size", -1L)
        // 大小对不上就说明网盘那份与清单不是同一次发布 —— 不把可疑的结果交出去
        if (manifestSize > 0 && hit.second > 0 && hit.second != manifestSize) {
            throw IllegalStateException("网盘上的 ${apkName} 大小(${hit.second})与清单(${manifestSize})不符")
        }
        return Remote(
            versionName = o.optString("versionName"),
            versionCode = o.optInt("versionCode"),
            size = if (manifestSize > 0) manifestSize else hit.second,
            md5 = o.optString("md5"),
            notes = o.optString("notes"),
            apk = apkName,
            fileId = hit.first,
        )
    }

    private fun downloadUrl(host: String, fileId: String): String {
        val (ts, rand) = stamp()
        val body = JSONObject().put("fileId", fileId).toString()
        val j = post("${host}/file/getDownloadUrl", body, personalHeaders(ts, rand, calSign(body, ts, rand)))
        val d = j.optJSONObject("data") ?: throw IllegalStateException("139 没有返回下载数据")
        val url = if (d.optBoolean("cdnSwitch")) d.optString("cdnUrl") else d.optString("url")
        if (url.isBlank()) throw IllegalStateException("139 没有返回下载地址")
        return url
    }

    private fun downloadText(host: String, fileId: String): String {
        val req = Request.Builder().url(downloadUrl(host, fileId))
            .header("User-Agent", "Mozilla/5.0").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("取清单 HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /** APK 的直链（139 自家 CDN 地址；每次现取，签名同样有时效）。 */
    fun apkUrl(remote: Remote): String = downloadUrl(personalHost(), remote.fileId)

    /**
     * 取 APK 直链前若干字节做**真伪校验**（zip 魔数 + 总长）。
     * 校验不过就不把地址交出去 —— 避免「点了下载却拿到一个错误页」。
     */
    fun verifyApkUrl(url: String, expectSize: Long): Boolean = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
            .header("Range", "bytes=0-63").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return false
            val b = r.body?.bytes() ?: return false
            if (b.size < 4 || b[0] != 'P'.code.toByte() || b[1] != 'K'.code.toByte()) return false
            val cr = r.header("Content-Range").orEmpty()
            if (expectSize > 0 && cr.contains("/")) {
                cr.substringAfterLast("/").trim().toLongOrNull()?.let { return it == expectSize }
            }
            true
        }
    }.getOrDefault(false)

    /** 清掉路由缓存（换凭据后必须清，否则会拿着旧 host 打）。 */
    fun reset() { hostCache = "" }
}
