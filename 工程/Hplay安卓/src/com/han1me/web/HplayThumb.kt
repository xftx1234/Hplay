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
import java.util.LinkedHashMap
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

// ★ 第 84 轮（用户报「头像加载太慢、有的加载不出来」）：上限从 72 张 / 24MB 提到现在的量。
private const val MAX_ENTRIES = 220
private const val MAX_BYTES = 48L * 1024 * 1024
/** 无论多大都至少留这么多张 —— 否则首屏自己就把自己踢空。 */
private const val MIN_KEEP = 12

// ★★★ 第 337 轮（复核第 336 轮）：**336 轮那次缓存修复本身是错的，本轮重做。**
//
// 336 轮把 removeEldestEntry 里的 `values.sumOf { it.bytes }`（O(n)）换成了自维护的
// totalBytes 累加器，以为这样就修好了。实测不成立，原因在 LinkedHashMap 的语义：
//
//   removeEldestEntry 返回 true 时，Map **自己**把 eldest 摘掉，
//   走的是**不经过** `remove(key)` 覆写的内部路径。
//   （想靠覆写 afterNodeRemoval 兜住也不行：它是包级私有，Kotlin 在别的包里连编译都过不去。）
//
// 于是每淘汰一张，totalBytes 只加不减。同参数实测（JDK 21）：
//   首屏 20 张 1280×720（每张 3.7MB）→ totalBytes = 73,728,000，
//   而 48MB 上限 = 50,331,648。第一次超限之后计数永久大于上限，
//   之后**每次 put 都立刻触发淘汰**，缓存被自己的错误计数锁死成「只留 1~2 张」。
//   人话：加了缓存反倒每张都重下，正好就是用户报的「加了缓存后变慢」。
//
// 正确做法（本轮）：**不再依赖 removeEldestEntry 的副作用**，改成显式淘汰循环 ——
//   自己取迭代器、自己减 totalBytes、自己 it.remove()。计数与容器永远同步，
//   也顺带绕开「removeEldestEntry 一次只能淘汰 1 条」（一次插入可能超限很多）。
//
// 另一条同源结论：只按字节收也不够。一张 1000px 封面解码后 2.25MB，
// 48MB 只装得下 21 张 —— 首屏就填满，等于没有缓存。所以卡片封面改用
// [CARD_DECODE_W] 解码（见 SiteCard 的调用点），单张降到约 0.52MB，同样 48MB 能装 90 多张。
private val thumbLock = Any()

/** accessOrder = true ⇒ 迭代器从「最久未用」开始，淘汰顺序天然就是 LRU。 */
private val thumbCache = LinkedHashMap<String, ThumbEntry>(64, 0.75f, true)

/** 缓存内所有条目解码后的总字节。**只允许在 thumbLock 内读写。** */
private var totalBytes: Long = 0L

/** 显式淘汰：逐条摘掉最久未用的，直到回到水位以下。必须在 thumbLock 内调用。 */
private fun evictLocked() {
    // ★★★ 第 344 轮（全量核查 · P1 · 可致 OOM）：**字节上限必须能压过张数下限**。
    //
    // 原来第一行就是 `if (thumbCache.size <= MIN_KEEP) return`（MIN_KEEP = 12），
    // 而 MAX_BYTES = 48MB 是字节上限。两者冲突时**张数赢**：
    //   · cos 板块用 decodeMaxW = 1600 解码（CosBoard.kt），一张 1600x2400 的图
    //     = 1600*2400*4 ≈ 15.4 MB；
    //   · 滑 12 张 = 154MB 位图常驻，totalBytes 早已 3 倍超限，
    //     但每次淘汰都在第一行返回 —— 低内存机型直接 OutOfMemoryError；
    //   · 更糟的是早退时连 thumbStateDrop 都不跑，位图被 state 表钉住无法回收。
    //
    // 现在：只保「不能把缓存清空」这一条（留 1 张），字节上限永远生效。
    // 代价是极端情况下缓存只剩 1 张 —— 那也比 OOM 崩掉强。
    if (thumbCache.size <= 1) return
    val it = thumbCache.entries.iterator()
    while (it.hasNext()) {
        if (thumbCache.size <= 1) break
        if (thumbCache.size <= MAX_ENTRIES && totalBytes <= MAX_BYTES) break
        val e = it.next()
        totalBytes -= e.value.bytes
        // ★ 第 340 轮自查修：**淘汰必须连 state 缓存一起清**。
        // state 缓存只存引用，但它会阻止位图被 GC —— 如果只管加不管删，
        // 一个 400 条的 state 表能把已经被 thumbCache 淘汰的位图全部钉在内存里
        // （400 × 0.5MB ≈ 200MB），比不做缓存糟得多。
        thumbStateDrop(e.key)
        it.remove()
    }
}

private fun cacheGet(key: String): ImageBitmap? = synchronized(thumbLock) { thumbCache[key]?.img }

private fun cachePut(key: String, img: ImageBitmap) = synchronized(thumbLock) {
    val bytes = img.width.toLong() * img.height * 4L
    val old = thumbCache.put(key, ThumbEntry(img, bytes))
    if (old != null) totalBytes -= old.bytes
    totalBytes += bytes
    evictLocked()
}

/**
 * **磁盘持久化封面缓存**（第 336 轮，用户要求"既不掉速又不用重载"）。
 *
 * 内存缓存解决不了冷启动：进程一死就全丢，下次进来还得把整屏封面重下一遍。
 * 这里把**原始字节**按 URL 的 hash 落盘，下次启动先读盘（几 ms）再解码，
 * 网络只在盘里没有时才发。
 *
 * 为什么不存解码后的 Bitmap：Bitmap 落盘要重新编码 PNG，比存原始 JPEG 大 5~10 倍
 * 且编码本身耗时，反而更慢。存原字节 + 解码是最省的做法。
 *
 * 容量：500 个文件 / 40 MB，超了按最后修改时间删最旧的。放在 cacheDir，
 * 系统空间紧张时会自动清理，不会占用户存储配额。
 */
private object ThumbDisk {
    private const val MAX_FILES = 500
    private const val MAX_BYTES = 40L * 1024 * 1024

    /** ★ 第 340 轮：trim 节流 —— 每写这么多张才真清一次（见 put 的注释）。 */
    private const val TRIM_EVERY = 64
    private val putCounter = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var dir: java.io.File? = null

    private fun dir(): java.io.File? {
        dir?.let { if (it.isDirectory) return it }
        val ctx = com.han1me.HplayEnv.applicationOrNull() ?: return null
        val d = java.io.File(ctx.cacheDir, "thumb-v2")
        if (!d.isDirectory && !d.mkdirs()) return null
        dir = d
        return d
    }

    private fun fileFor(key: String): java.io.File? {
        val d = dir() ?: return null
        val h = Integer.toHexString(key.hashCode())
        return java.io.File(d, h + ".img")
    }

    /** 上次刷新 mtime 的节流窗口：1 小时内不重复写盘（写盘比读盘更贵，见下）。 */
    private const val TOUCH_WINDOW_MS = 3_600_000L

    fun get(key: String): ByteArray? = runCatching {
        val f = fileFor(key) ?: return null
        if (!f.isFile || f.length() <= 0L) return null
        // 读盘时顺手刷新 mtime，LRU 淘汰就按它排序（读盘命中的应该留得更久）。
        //
        // ★ 第 340 轮：**加节流**。原实现每读一次就 setLastModified 一次 ——
        // 那是一次真实的磁盘写（inode 更新），一屏 12 张 = 12 次额外写。
        // 现在同一文件一小时内只写一次，语义不变而开销归零（缓存命中本该是纯读）。
        val now = System.currentTimeMillis()
        if (now - f.lastModified() > TOUCH_WINDOW_MS) {
            runCatching { f.setLastModified(now) }
        }
        f.readBytes()
    }.getOrNull()

    fun put(key: String, bytes: ByteArray) {
        runCatching {
            if (bytes.size < 512) return   // 太小的是错误页，不值得落盘
            val f = fileFor(key) ?: return
            f.writeBytes(bytes)
            // ★★★ 第 340 轮（用户报「糖心板块封面加缓存后慢了不少」）：**trim 绝不能每次 put 都跑**。
            //
            // 原实现每写一张图就 trim() 一次，而 trim() 是
            //   d.listFiles() + files.sumOf { it.length() }
            // —— 500 个文件就是 500 次 stat，全在 IO 线程上，且**与写图串行**。
            // 一屏 12 张图 = 6000 次 stat，比下载本身还慢，这就是「加了缓存反而更慢」。
            //
            // 现在按次数节流：累计写满 TRIM_EVERY 张才清一次，平时一次 list/stat 都不做。
            // 上限由 trim 自己保证（超额才真删），差几十张不会失控。
            if (putCounter.incrementAndGet() >= TRIM_EVERY) {
                putCounter.set(0)
                trim()
            }
        }
    }

    /** 删掉某一条（盘里那份解不开时用）。 */
    fun remove(key: String) {
        runCatching { fileFor(key)?.delete() }
    }

    /** 超限时按 mtime 从旧到新删，直到回到水位以下。 */
    private fun trim() {
        val d = dir() ?: return
        val files = d.listFiles() ?: return
        if (files.size <= MAX_FILES && files.sumOf { it.length() } <= MAX_BYTES) return
        files.sortedBy { it.lastModified() }
            .take((files.size - MAX_FILES).coerceAtLeast(0) + 40)
            .forEach { runCatching { it.delete() } }
    }

    /** 清空磁盘缓存（设置里的"清空封面缓存"用）。 */
    fun clear() {
        runCatching { dir()?.listFiles()?.forEach { it.delete() } }
    }
}

/**
 * 解码宽度上限。
 *
 * [DECODE_MAX_W] 是介绍页大图（Hero 400dp 高、全宽）用的；
 * [CARD_DECODE_W] 是列表卡片用的 —— 卡片实际只有 ~180dp 宽（1080p 屏 ≈ 490px），
 * 按 1000px 解出来再缩下去是白烧 4 倍内存，也是 336 轮「缓存装不下首屏」的直接原因。
 * 480px 在 3x 屏上仍然不糊，单张 0.52MB，48MB 能装 90 多张。
 */
private const val DECODE_MAX_W = 1000
const val CARD_DECODE_W = 480

/** 缓存键：把解码尺寸也带进去（头像 480px 与封面 1000px 不能互相顶掉）。第 84 轮。 */
private fun ck(url: String, maxW: Int): String = if (maxW == DECODE_MAX_W) url else "$url@$maxW"

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
        cachePut(ck(url, maxW), img)
        return img
    }
    // ★ 第 336 轮：**先读磁盘缓存**。冷启动后内存是空的，
    // 但盘里通常已经有这张图 —— 命中就省掉整个网络往返。
    // ★ 第 337 轮：命中后统一走 cachePut（336 轮这里是直接 putTracked 往表里塞，
    // 既绕过计数也绕过淘汰 —— 那个 API 现在已删，改回唯一入口）。
    val diskKey = ck(url, maxW)
    val diskBytes = withContext(Dispatchers.IO) { ThumbDisk.get(diskKey) }
    if (diskBytes != null) {
        val img2 = decodeSampled(diskBytes, maxW)
        if (img2 != null) {
            cachePut(diskKey, img2)
            return img2
        }
        // 盘里那份解不开（源站换图/文件截断）→ 删掉，继续走网络
        withContext(Dispatchers.IO) { ThumbDisk.remove(diskKey) }
    }
    val (bytes, why) = HanimeClient.fetchThumbBytes(url)
    if (bytes != null) {
        val img = decodeSampled(bytes, maxW) ?: return "图载失败: 解码失败(${bytes.size}B)"
        cachePut(ck(url, maxW), img)
        withContext(Dispatchers.IO) { ThumbDisk.put(ck(url, maxW), bytes) }
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
                    cachePut(ck(url, maxW), img)
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
    // ★★★ 第 340 轮（用户报「收藏页切换页面就重载，浪费」）：
    //
    // 老写法 remember(url) { mutableStateOf(null) } —— 组件一旦离开组合（切底栏、翻页、
    // 换 tab），state 就没了；回来时先画一帧空白，等 LaunchedEffect 跑完才出图，
    // 用户看到的就是「每切一次都白闪一下、像重新加载」。
    //
    // 现在把**已解析的结果**放进程级表里，重组时直接拿它当初始值：
    // 内存缓存命中是同步的，第一帧就有图，彻底没有白帧。
    // 表本身有上限（避免常驻过多），且只存已经在内存缓存里的图，不额外占内存。
    val key0 = ck(url, decodeMaxW)
    val init0 = thumbStateGet(key0)
    var state by remember(url) { mutableStateOf<Any?>(init0) }
    // 第 64 轮：**失败后自动重试**（修「图载失败」红字一直挂着）。
    // 老写法是 LaunchedEffect(url) —— url 不变就只跑一次；
    // 而生产层的图床退避最多 8 秒（第 64 轮由 20 秒缩短），冷却过去后**界面不会自己恢复**，
    // 用户看到的就是「一直坏」。现在失败后等一会儿自动再试，最多 3 次（避免一直打网）。
    var attempt by remember(url) { mutableStateOf(0) }
    // 解析结果回写进程级表（下一帧/下次进页面直接用）
    androidx.compose.runtime.LaunchedEffect(state, url) {
        val v = state
        if (v is ImageBitmap) thumbStatePut(key0, v)
    }
    LaunchedEffect(url, attempt) {
        if (url.isBlank()) {
            // 站点自己就没给封面（情色小说、部分文字列表站）——这是**内容形态**，不是故障，
            // 上一版在这里画一行红字报错，用户读成「坏了」。给一块中性占位卡，标题照常显示在卡下方。
            state = BLANK
            return@LaunchedEffect
        }
        // ★ 第 337 轮：这里原来是无锁直读 thumbCache（主线程），改成唯一入口 cacheGet。
        cacheGet(ck(url, decodeMaxW))?.let {
            state = it
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

/**
 * ★★★ 第 340 轮（用户报「收藏页切换页面就重载，浪费」）：**已解析结果的进程级备忘**。
 *
 * 为什么要它：HplayThumb 内部用 `remember(url) { mutableStateOf(null) }` 持状态，
 * 组件一旦离开组合（切底栏、切 tab、翻页），那份 state 就被丢弃 —— 回来时先画**一帧空白**，
 * 等 LaunchedEffect 重新走一遍「查缓存 → 出图」才恢复。看上去就是「每切一次都重新加载」。
 *
 * 内存缓存 thumbCache 本身是跨组合的，问题只在于**首帧拿不到值**。
 * 这里存一份「已解析的 ImageBitmap」，让重组时能**同步**取到初值：
 *   · 只存 ImageBitmap（位图本身已由 thumbCache 持有，这里只多存一个引用，不额外占像素内存）；
 *   · 上限 STATE_MAX 条，超出按访问顺序删最旧的。
 */
private const val STATE_MAX = 160
private val thumbStateCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>?): Boolean =
        size > STATE_MAX
}

private fun thumbStatePut(key: String, img: ImageBitmap) {
    synchronized(thumbStateCache) { thumbStateCache[key] = img }
}

/**
 * 同步取已解析结果。
 *
 * **必须加同一把锁**：LinkedHashMap（accessOrder=true）在并发读写下会破坏内部链表，
 * 极端情况直接死循环。这里的竞争极低（写只在出图后一次），加锁成本可以忽略。
 */
private fun thumbStateGet(key: String): ImageBitmap? =
    synchronized(thumbStateCache) { thumbStateCache[key] }

/**
 * 从 state 缓存里删一条（thumbCache 淘汰时同步调用）。
 *
 * ★ 第 340 轮自查：state 缓存只存**引用**，但它足以让位图**无法被 GC**。
 * 如果再叠一层 trim 节流，很容易出现「thumbCache 已经淘汰、state 表还钉着 400 张」的内存黑洞。
 * 所以淘汰路径必须成对调用它，上限也压到与 thumbCache 同量级（[STATE_MAX]）。
 */
private fun thumbStateDrop(key: String) {
    synchronized(thumbStateCache) { thumbStateCache.remove(key) }
}