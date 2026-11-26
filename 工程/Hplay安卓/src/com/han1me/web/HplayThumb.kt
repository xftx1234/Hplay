package com.han1me.web

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javrocket.hanime.HanimeClient
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.han1me.HplayOnSurface
import com.han1me.HplayOnSurfaceVariant
import com.han1me.HplayErrorSoft
import com.han1me.HplayOutline

/**
 * 封面加载（替代 coil AsyncImage）：网络口径与桌面同构，走 `HanimeClient.thumbHttp`
 * （由 `HanimeClient.http` newBuilder 派生 ⇒ DoH / 边缘节点 / 代理 / cookie 全部继承，不是另起一套策略）。
 *
 * 失败原因画在卡面上：本机 logcat 抑制应用日志，屏显是唯一可靠诊断通道。
 */

/** 缓存项：解码后的图 + 它实际占的字节（ARGB_8888 按 宽×高×4 估）。 */
private data class ThumbEntry(val img: ImageBitmap, val bytes: Long)

// ★ 第 84 轮（用户报「头像加载太慢、有的加载不出来」）：
// 原上限 72 张 / 24MB —— 而订阅页光作者就有 60 个，加上封面对 72 张的 LRU 是**边滑边淘汰**，
// 淘汰掉的再滚回来又要重下（用户感受就是「慢且有的出不来」）。
// 提到 220 张 / 48MB：按头像（200px ⇒ 约 160KB/张）算，60 个头像约 9.6MB，余量充足。
private const val MAX_ENTRIES = 220
private const val MAX_BYTES = 48L * 1024 * 1024

/**
 * 按**解码后字节数**收，不按张数。
 *
 * 旧版是 `size > 48`：一张 1280×720 的 ARGB_8888 就是 3.7 MB，48 张理论上限 176 MB，
 * 那是「多翻几屏就闪退」的形态而不是缓存。现在 24 MB / 72 张双上限，最少也留 6 张（不然首屏就会被自己踢掉）。
 */
private val thumbCache = Collections.synchronizedMap(
    object : LinkedHashMap<String, ThumbEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ThumbEntry>?): Boolean =
            size > 6 && (size > MAX_ENTRIES || values.sumOf { it.bytes } > MAX_BYTES)
    },
)

/** 解码宽度上限：站源常见 1280×720，而手机上没有一张封面真需要 1280 px（卡片只有 206 dp 宽）。 */

/** 缓存键：把解码尺寸也带进去（头像 200px 与封面 1000px 不能互相顶掉）。第 84 轮。 */
private fun ck(url: String, maxW: Int): String = if (maxW == DECODE_MAX_W) url else "$url@$maxW"
private const val DECODE_MAX_W = 1000

/** 两段式解码：先 `inJustDecodeBounds` 量原图，再按 2 的幂挑 `inSampleSize`，然后真解一次。 */
private fun decodeSampled(bytes: ByteArray, maxW: Int = DECODE_MAX_W): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
    val opt = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)?.asImageBitmap()
}

/**
 * 取一张封面。网络侧的策略（并发闸门、Referer 写法记忆、失败域 20 s 短路、8 s 连接超时）
 * 全在生产层 [HanimeClient.fetchThumbBytes] 里，这里只负责解码与缓存——
 * 验收宿具调的也是同一个函数，不存在「界面一套、测的另一套」。
 */
private suspend fun loadThumb(url: String, maxW: Int = DECODE_MAX_W): Any {
    // 本机封面（下载时一并缓存的 `file://…/hplay.cover.jpg`）：直接读盘。
    // 不能交给 fetchThumbBytes —— 它先解析 URL 的主机名，file:// 得到空主机就直接回
    // 「封面地址无主机」，于是离线介绍页的封面永远是一行红字。
    if (url.startsWith("file:")) {
        // 用 URI 反解，但**先兜一层**：老版本写进 sidecar 的可能是未转义的 "file://" + absolutePath
        // （保存目录含空格时那个串根本不是合法 URI，见第 48 轮工程记录）。解不出来就去掉 scheme 当路径用。
        val f = runCatching { java.io.File(java.net.URI(url)) }.getOrNull()
            ?: runCatching { java.io.File(url.removePrefix("file://")) }.getOrNull()
        if (f == null || !f.isFile) return BLANK   // 离线副本没留封面：按"没有封面"画占位，不当故障
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return BLANK
        val img = decodeSampled(bytes, maxW) ?: return "图载失败: 解码失败(${bytes.size}B)"
        thumbCache[ck(url, maxW)] = ThumbEntry(img, img.width.toLong() * img.height * 4L)
        return img
    }
    val (bytes, why) = HanimeClient.fetchThumbBytes(url)
    if (bytes != null) {
        val img = decodeSampled(bytes, maxW) ?: return "图载失败: 解码失败(${bytes.size}B)"
        thumbCache[ck(url, maxW)] = ThumbEntry(img, img.width.toLong() * img.height * 4L)
        return img
    }
    // ★★ 第 90 轮（用户报「有些订阅作者图载失败」）：
    //
    // 真机实测根因：部分头像托管在**境外图床**（i.imgur.com / pbs.twimg.com），
    // 而设备在无代理环境下这些域**整体不可达**（curl 直连 code=000；同机 baidu 0.1 秒 200）。
    // 页面本身能出来（hanime1 有 DoH/边缘节点通道），只有这些图床域没走那条通道。
    //
    // 修法：直连失败且判定为「连不上」时，**经公共图片代理 wsrv.nl 再取一次**。
    // 实测该代理在本网络下可达（200/2.6s），且能成功代理境外图片（GitHub 头像 283KB、image/png）。
    // 只对「连接层失败」启用，避免把正常的 403/404 也绕一圈。
    if (why.contains("连不上") || why.contains("暂停重试")) {
        // ★ 第 93 轮修正（用户报「头像没有完全修复」）：
        //
        // 上一版只经 wsrv.nl 试**一次**。实测该代理偶发 429（请求过多）与超时，
        // 单次失败就放弃 ⇒ 用户仍看到「图载失败」。
        // 现在**最多试 3 次、间隔递增**（600/1200ms），把偶发限流错开。
        for (tryIdx in 0 until 3) {
            val proxied = runCatching { HanimeClient.fetchThumbBytesViaProxy(url) }.getOrNull()
            if (proxied != null) {
                val img = decodeSampled(proxied, maxW)
                if (img != null) {
                    thumbCache[ck(url, maxW)] = ThumbEntry(img, img.width.toLong() * img.height * 4L)
                    return img
                }
            }
            if (tryIdx < 2) kotlinx.coroutines.delay(600L * (tryIdx + 1))
        }
    }
    // ★ 第 93 轮：**连不上的图床按「没有封面」处理，不再画红字**。
    //
    // 用户视角：这些头像托管在境外图床，在当前网络下**本来就不可能取到** ——
    // 画一行「图载失败: 图床 x 刚连不上」只会让人以为应用坏了（而且会一直重试、拖慢整页）。
    // 给一块中性占位（同"站点自己没给封面"的处理），标题照常显示，界面干净。
    // 真失败（解析/解码异常）仍如实返回原因。
    if (why.contains("连不上") || why.contains("暂停重试")) return BLANK
    return "图载失败: $why"
}

@Composable
fun HplayThumb(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    /** 解码宽度上限（第 84 轮）：头像这类小图没必要按 1000px 解 —— 见 decodeSampled 的说明。 */
    decodeMaxW: Int = DECODE_MAX_W,
    contentScale: ContentScale = ContentScale.Crop,
    /**
     * 备用图地址（第 100 轮）。首选图**最终仍取不到**时改用它，而不是留空/红字。
     * 介绍页作者头像用它兜底（页面里的默认头像），缓解「有时加载不出来」。
     */
    fallbackUrl: String = "",
) {
    var state by remember(url) { mutableStateOf<Any?>(null) }
    // 第 64 轮：**失败后自动重试**（修「图载失败」红字一直挂着）。
    // 老写法是 LaunchedEffect(url) —— url 不变就只跑一次；
    // 而生产层的图床退避最多 8 秒（第 64 轮由 20 秒缩短），冷却过去后**界面不会自己恢复**，
    // 用户看到的就是「一直坏」。现在失败后等一会儿自动再试，最多 3 次（避免一直打网）。
    var attempt by remember(url) { mutableStateOf(0) }
    LaunchedEffect(url, attempt) {
        if (url.isBlank()) {
            // 站点自己就没给封面（情色小说、部分文字列表站）——这是**内容形态**，不是故障，
            // 上一版在这里画一行红字报错，用户读成「坏了」。给一块中性占位卡，标题照常显示在卡下方。
            state = BLANK
            return@LaunchedEffect
        }
        thumbCache[ck(url, decodeMaxW)]?.let {
            state = it.img
            return@LaunchedEffect
        }
        val r = withContext(Dispatchers.IO) { loadThumb(url, decodeMaxW) }
        // ★ 第 100 轮：首选图失败且给了备用图 ⇒ **改取备用图**（作者的默认头像）。
        // 只在「真的失败」时换（BLANK 是"站点没给图"的内容形态，换也没意义）。
        if (r is String && fallbackUrl.isNotBlank() && fallbackUrl != url) {
            val fb = withContext(Dispatchers.IO) { loadThumb(fallbackUrl, decodeMaxW) }
            if (fb is ImageBitmap) {
                state = fb
                return@LaunchedEffect
            }
        }
        state = r
        // 只有「真的失败」（不是空封面占位）才重试；次数上限 3，间隔递增。
        //
        // 第 68 轮**实机测出的 bug**：截图里出现「图载失败：picmeta2025.sbs: 正文太小(0B)」。
        // 复现：图床域名变成停放页后，某些图片路径回 **HTTP 200 + 0 字节**（Content-Type: text/html）。
        // 这类失败是「**那边根本没有这张图**」，**重试不会有任何变化** ——
        // 老实现照样重试 3 次（每次还带递增延迟），既白等又让用户以为「网络在挣扎」。
        // 现在：判为「不是图片正文」的一律**不重试**，并把话说清楚。
        if (r is String && !r.contains("不是图片正文") && !r.contains("没有这张图") && attempt < 3) {
            kotlinx.coroutines.delay(if (decodeMaxW <= 256) (400L * (attempt + 1)) else (1200L * (attempt + 1)))
            attempt++
        }
    }
    Box(modifier.background(Color(0xFF1C1C1F))) {
        when (val s = state) {
            null -> Box(Modifier.fillMaxSize())
            is ImageBitmap -> Image(
                bitmap = s,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
            BLANK -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("🎬", color = HplayOutline, fontSize = 26.sp)
            }
            else -> {
                // 第 68 轮：把两类失败分开说 ——
                //  · 「没有这张图」是**内容形态**（站点给的链接本身就是坏的），不是网络故障；
                //  · 其余（超时/HTTP 4xx/5xx）才是**这张暂时取不到**，值得等下一次。
                // 说清楚才能让用户判断该不该等，而不是一律看到「图载失败」四个字。
                val msg = s.toString()
                val noImage = msg.contains("不是图片正文") || msg.contains("没有这张图")
                Text(
                    if (noImage) "🖼 无封面\n(站点未提供有效图片)" else msg,
                    color = if (noImage) HplayOnSurfaceVariant else HplayErrorSoft,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                )
            }
        }
    }
}

/** 空封面占位的标记对象（与「加载失败」的字符串区分开：前者是内容形态，后者才是要回显的故障）。 */
private val BLANK = Any()