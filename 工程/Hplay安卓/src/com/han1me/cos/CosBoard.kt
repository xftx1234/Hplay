package com.han1me.cos

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.han1me.ui.columnsFor
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.han1me.lab.GlobalProxy
import com.han1me.player.HplayPlayerView
import com.han1me.player.rememberHplayController
import com.han1me.web.HplayThumb
import com.han1me.HplayEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// ★★★ 2026-11-25（第 324 轮 · 用户要求「cos 板块内部没有重构」）：
//
// 这四色原来是硬编码的独立配色（品红 #E93A7B + 近黑 #0B0C10），
// 与全站 Material 3 主题完全不搭 —— 用户换了主题色，cos 板块纹丝不动。
//
// 改成 @Composable getter：**调用点一个字都不用改**，但每次读取都会跟着
// MaterialTheme.colorScheme 走。与 HplayTheme 里那 2400 行的做法完全一致。
private val CosAccent: Color
    @Composable get() = MaterialTheme.colorScheme.primary

private val CosBg: Color
    @Composable get() = Color.Transparent   // 让全局壁纸透出来（与其它板块一致）

private val CosCard: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

private val CosFaint: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

object CosRepo {
    const val BASE = "https://cosplaytele.com"
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .addInterceptor(com.han1me.log.LoggingInterceptor)
            .proxySelector(GlobalProxy.selector())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    data class Post(val url: String, val title: String, val thumb: String, val video: Boolean)
    data class Detail(val images: List<String>, val embed: String?)

    private fun unescape(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

    fun get(url: String, referer: String = ""): String {
        var last: Throwable? = null
        repeat(2) {
            try {
                val b = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                if (referer.isNotBlank()) b.header("Referer", referer)
                http.newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) throw IllegalStateException("empty")
                    return body
                }
            } catch (t: Throwable) { last = t; Thread.sleep(400) }
        }
        throw IllegalStateException(last?.message ?: "fail")
    }

    private fun parsePosts(html: String): List<Post> {
        val out = ArrayList<Post>()
        val seen = HashSet<String>()
        for (block in html.split("col post-item").drop(1)) {
            val u = Regex("href=\"(https://cosplaytele\\.com/[^\"]+)\"").find(block)?.groupValues?.get(1) ?: continue
            if (u.contains("/page/") || u.contains("wp-") || u.contains("#") || u.contains("/category")) continue
            if (!seen.add(u)) continue
            val raw = Regex("post-title[^>]*>\\s*<a[^>]*>([\\s\\S]{5,220}?)</a>").find(block)?.groupValues?.get(1) ?: continue
            val img = Regex("<img[^>]*src=\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: ""
            val title = unescape(raw)
            out.add(Post(u, title, img, title.contains("video", true)))
        }
        return out
    }

    fun listing(page: Int): List<Post> =
        parsePosts(get(if (page <= 1) BASE + "/" else BASE + "/page/" + page + "/"))

    fun detail(postUrl: String): Detail {
        val html = get(postUrl)
        val i = html.indexOf("entry-content")
        val seg = if (i >= 0) html.substring(i) else html
        val seen = LinkedHashSet<String>()
        Regex("<img[^>]*src=\"([^\"]+)\"").findAll(seg).forEach { m ->
            val u = m.groupValues[1]
            if (u.contains("/wp-content/uploads/")) seen.add(u)
        }
        val embed = Regex("<iframe[^>]*src=\"(https://cossora\\.stream/embed/[^\"]+)\"")
            .find(html)?.groupValues?.get(1)
        return Detail(seen.toList(), embed)
    }

    fun resolveCossora(embedUrl: String): String? {
        return try {
            val html = get(embedUrl, referer = "https://cosplaytele.com/")
            val b64 = Regex("const\\s+videoURL\\s*=\\s*'([^']+)'").find(html)?.groupValues?.get(1) ?: return null
            val key = Regex("decryptLink\\(\\s*videoURL\\s*,\\s*'([0-9a-fA-F]{32})'\\s*\\)").find(html)?.groupValues?.get(1) ?: return null
            val raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            if (raw.size < 32) return null
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                javax.crypto.spec.IvParameterSpec(raw.copyOfRange(0, 16)),
            )
            String(cipher.doFinal(raw.copyOfRange(16, raw.size)), Charsets.UTF_8).trim().takeIf { it.startsWith("http") }
        } catch (t: Throwable) { null }
    }

    fun toggleFav(post: Post): Boolean {
        val now = !com.han1me.X99.isFav(post.url)
        runCatching {
            val f = com.han1me.X99.downloadDir.parentFile
            val file = java.io.File(f, "hplay_favorites.tsv")
            val existing = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
            val esc = post.title.replace("\t", "\\t").replace("\n", "\\n")
            val entry = post.url + "\t" + esc + "\t" + post.thumb + "\t" + "cos" + "\t" + System.currentTimeMillis().toString()
            val next = if (now) listOf(entry) + existing
                else existing.filterNot { it.startsWith(post.url + "\t") }
            file.parentFile?.mkdirs()
            file.writeText(next.joinToString("\n") + "\n")
        }
        runCatching { com.han1me.X99.clearFavsCache() }
        return now
    }

    fun recordHistory(post: Post) {
        runCatching {
            val f = com.han1me.X99.downloadDir.parentFile
            val file = java.io.File(f, "hplay_cos_history.tsv")
            val esc = post.title.replace("\t", "\\t").replace("\n", "\\n")
            val entry = post.url + "\t" + esc + "\t" + post.thumb + "\t" + post.video.toString() + "\t" + System.currentTimeMillis().toString()
            val old = if (file.exists()) file.readLines().filter { it.isNotBlank() && !it.startsWith(post.url + "\t") } else emptyList()
            file.parentFile?.mkdirs()
            file.writeText((listOf(entry) + old).take(200).joinToString("\n") + "\n")
        }
    }

    var copyToast by mutableStateOf("")
    var lastDownloadMsg by mutableStateOf("")

    fun history(): List<Post> = runCatching {
        val f = com.han1me.X99.downloadDir.parentFile
        val file = java.io.File(f, "hplay_cos_history.tsv")
        if (!file.exists()) return emptyList()
        file.readLines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("\t")
            Post(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" }, parts.getOrElse(3) { "false" } == "true")
        }
    }.getOrDefault(emptyList())
}

/**
 * cos 视频下载器（第 249 轮）：解密 m3u8 → 并发下载分片 → 合并为 .ts 文件。
 * 分片走节点（hls-cdn.cossora.stream 需要节点才能达到最佳速度）。
 * 下载完成后可在「已下载」页看到（与 HplayDownloads 同一个目录）。
 */
object CosDownloader {
    /**
     * cos 视频下载（第 250 轮）：解密 m3u8 后构造 HanimeDetail，
     * 交给已有的 HplayDownloads 系统（分片并发下载 + 通知栏提醒 + 下载页可见）。
     */
    fun enqueue(title: String, m3u8Url: String, postUrl: String, thumb: String = ""): Long {
        val video = com.javrocket.hanime.HanimeVideo(
            id = postUrl,
            url = postUrl,
            title = title,
            thumb = thumb,
            duration = "",
            likes = "",
            views = "",
            brand = "cos",
        )
        val detail = com.javrocket.hanime.HanimeDetail(
            video = video,
            description = "",
            tags = emptyList(),
            sources = listOf(1080 to m3u8Url),
            related = emptyList(),
        )
        return com.han1me.download.HplayDownloads.enqueue(detail, 1080, m3u8Url, group = "cos")
    }
}
enum class CosTab(val label: String) { GALLERY("图集"), VIDEO("视频") }

@Composable
fun CosBoardScreen(onBack: () -> Unit, cosFavOpen: CosRepo.Post? = null, onCosFavClose: () -> Unit = {}) {
    var tab by remember { mutableStateOf(CosTab.VIDEO) }
    var page by remember { mutableIntStateOf(1) }
    var posts by remember { mutableStateOf<List<CosRepo.Post>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<CosRepo.Post?>(null) }
    var gen by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun load(p: Int) {
        loading = true
        err = ""
        scope.launch {
            try {
                val got = withContext(Dispatchers.IO) { CosRepo.listing(p) }
                posts = if (p <= 1) got else {
                    val seen = posts.map { it.url }.toHashSet()
                    posts + got.filter { seen.add(it.url) }
                }
                page = p
                exhausted = got.isEmpty()
            } catch (t: Throwable) { err = t.message ?: "fail" }
            loading = false
        }
    }

    LaunchedEffect(tab, gen) { page = 1; exhausted = false; load(1) }
    val visible = posts.filter { it.video == (tab == CosTab.VIDEO) }

    Box(Modifier.fillMaxSize().background(CosBg).statusBarsPadding()) {
    Column(Modifier.fillMaxSize()) {
        // ★ 第 324 轮重构：顶栏照其它板块（← 返回 + 标题 + 右侧切换胶囊）。
        // 原来「←」没有背景框、标题用硬编码品红、tab 之间用 Spacer 硬撑 ——
        // 与设置页/板块页的观感完全不同。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "← 返回",
                color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onBack() }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "cos 板块", color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CosTab.entries.forEach { t ->
                    val active = tab == t
                    Text(
                        t.label,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable { tab = t }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (loading && posts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = CosAccent) }
        } else if (err.isNotBlank() && posts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(err, color = CosFaint, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("重试", color = Color.White, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(CosAccent).clickable { gen++ }.padding(horizontal = 18.dp, vertical = 8.dp))
                }
            }
        } else {
            // ★ 第 327 轮：列数自适应（横屏/平板不再只有两列巨卡）
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnsFor(maxWidth, minItemWidth = 150.dp)),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visible, key = { it.url }) { post ->
                    // ★ 第 324 轮：卡片改成与三次元板块同一套排版
                    //（圆角 10dp + 主题卡片底 + 左上类型角标 + 两行标题）。
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(CosCard)
                            .clickable { detail = post },
                    ) {
                        Box {
                            HplayThumb(
                                post.thumb, post.title,
                                Modifier.fillMaxWidth().aspectRatio(0.85f),
                                contentScale = ContentScale.Crop,
                            )
                            Text(
                                if (post.video) "视频" else "图集",
                                color = MaterialTheme.colorScheme.onPrimary, fontSize = 10.sp,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(
                                        if (post.video) MaterialTheme.colorScheme.primary
                                        else Color.Black.copy(alpha = 0.62f)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            post.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.5.sp, lineHeight = 15.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        )
                    }
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                    Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                        when {
                            loading -> CircularProgressIndicator(color = CosAccent, modifier = Modifier.size(22.dp))
                            exhausted -> Text("没有更多了", color = CosFaint, fontSize = 12.sp)
                            else -> Text("加载更多 ↓", color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(CosCard)
                                    .clickable { load(page + 1) }.padding(horizontal = 20.dp, vertical = 9.dp))
                        }
                    }
                }
            }
            }   // ← 第 327 轮：BoxWithConstraints 收尾
        }
    }
    detail?.let { post -> CosPostScreen(post, onBack = { detail = null }) }
    // 第 250 轮：从收藏页跳转过来的 cos 详情
    cosFavOpen?.let { post ->
        CosPostScreen(post, onBack = onCosFavClose)
    }
    }
}

@Composable
fun CosPostScreen(post: CosRepo.Post, onBack: () -> Unit) {
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var resolving by remember { mutableStateOf(false) }
    var nativeM3u8 by remember { mutableStateOf<String?>(null) }
    var resolveFail by remember { mutableStateOf(false) }
    var openNative by remember { mutableStateOf(false) }
    var isFav by remember(post.url) { mutableStateOf(com.han1me.X99.isFav(post.url)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(post.url, reload) {
        CosRepo.recordHistory(post)
        loading = true; err = ""
        try {
            val d = withContext(Dispatchers.IO) { CosRepo.detail(post.url) }
            images = d.images
            if (d.images.isEmpty()) err = "未取得媒体内容"
        } catch (t: Throwable) { err = t.message ?: "fail" }
        loading = false
    }

    fun startNative() {
        resolving = true
        scope.launch {
            try {
                val d = withContext(Dispatchers.IO) { CosRepo.detail(post.url) }
                val embed = d.embed ?: throw IllegalStateException("novideo")
                val u = withContext(Dispatchers.IO) { CosRepo.resolveCossora(embed) }
                if (u != null) { nativeM3u8 = u; openNative = true }
                else { resolveFail = true; openNative = true }
            } catch (t: Throwable) { resolveFail = true; openNative = true }
            resolving = false
        }
    }

    // 全屏模式：隐藏顶栏，只显示播放器（与三次元一致）
    if (openNative) {
        // 第 252 轮：Dialog 全屏覆盖层（遮盖底栏/顶栏/一切，与 v1.1.51 相同）
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { openNative = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                CosNativePlayerScreen(nativeM3u8 ?: "", resolveFail, post.url, onBack = { openNative = false })
            }
        }
        return
    }

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(CosBg).statusBarsPadding()) {
        // ★★★ 2026-11-25（第 324 轮 · 详情页重构）：
        //
        // 真机实测（23:09）：顶栏的「←」是个裸字符、标题在 weight(1f) 里没设 maxLines，
        // 长标题（"Acos 接拍 Tiny Asa baby cosplay Castorice - ..."）把「←」直接挤没了，
        // 而状态栏又是透明的 —— 视觉上标题压在时间那一行上，看起来像排版错乱。
        //
        // 现在照板块页那套：返回键做成胶囊（有底有内边距）、标题强制单行省略、
        // 收藏键放进同样的胶囊里；整条顶栏加不透明底，杜绝与状态栏/图片混在一起。
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "← 返回",
                color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onBack() }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                post.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isFav) "❤" else "♡",
                color = if (isFav) CosAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { isFav = CosRepo.toggleFav(post) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = CosAccent) }
            err.isNotBlank() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(err, color = CosFaint, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("重试", color = Color.White, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(CosAccent).clickable { reload++ }.padding(horizontal = 18.dp, vertical = 8.dp))
                }
            }
            else -> {
                val pager = rememberPagerState(pageCount = { images.size })
                // ★ 第 324 轮：图片区保持 Fit（不裁切 cos 图），但加一层浅底 ——
                // 原来纯黑没有边界，看起来像"图糊满了整屏"，分不清图与按钮。
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { p ->
                        HplayThumb(images[p], post.title, Modifier.fillMaxSize(), decodeMaxW = 1600, contentScale = ContentScale.Fit)
                    }
                    Text((pager.currentPage + 1).toString() + " / " + images.size, color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp))
                }
                if (post.video) {
                    if (resolving) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = CosAccent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("正在解析视频直链…", color = CosFaint, fontSize = 13.sp)
                        }
                    } else {
                        // ★ 第 324 轮：按钮区加不透明底，与图片区清楚分层
                        Column(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                                .padding(bottom = 6.dp),
                        ) {
                        Button(
                            onClick = { startNative() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("▶ 原生播放", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(onClick = {
                            scope.launch {
                                try {
                                    val d = withContext(Dispatchers.IO) { CosRepo.detail(post.url) }
                                    val embed = d.embed ?: throw IllegalStateException("无视频源")
                                    val u = withContext(Dispatchers.IO) { CosRepo.resolveCossora(embed) }
                                    if (u == null) throw IllegalStateException("解析失败")
                                    CosDownloader.enqueue(post.title, u, post.url, thumb = post.thumb)
                                   CosRepo.lastDownloadMsg = "已加入下载队列"
                                } catch (t: Throwable) {
                                    CosRepo.lastDownloadMsg = "下载失败: " + (t.message ?: "")
                                }
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A9D8F)),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                            Text("⬇ 下载视频", color = Color.White, fontSize = 13.sp)
                        }
                        Text(CosRepo.lastDownloadMsg, color = CosFaint, fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center) {
                            Text("或在浏览器中观看 →", color = CosFaint, fontSize = 11.sp,
                                modifier = Modifier.clickable {
                                    runCatching { HplayEnv.activity()?.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(post.url))) }
                                }.padding(horizontal = 8.dp, vertical = 4.dp))
                            // 复制直链（第 250 轮）：把解析出的 m3u8 播放链接复制到剪贴板
                            if (nativeM3u8 != null) {
                                Text("复制直链", color = CosAccent, fontSize = 11.sp,
                                    modifier = Modifier.clickable {
                                        runCatching {
                                            val cb = HplayEnv.applicationOrNull()?.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                            cb?.setPrimaryClip(android.content.ClipData.newPlainText("m3u8", nativeM3u8!!))
                                            CosRepo.copyToast = "直链已复制"
                                        }
                                    }.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                        // 复制提示
                        if (CosRepo.copyToast.isNotBlank()) {
                            Text(CosRepo.copyToast, color = Color(0xFF2A9D8F), fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        }
                        }   // ← 第 324 轮：闭合「按钮区 Column」
                    }
                }
            }
        }
    }
}



@Composable
fun rememberCosPlayerController(key: String): com.han1me.player.HplayPlayerController {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember(key) {
        val cosHttp = okhttp3.OkHttpClient.Builder()
            .addInterceptor(com.han1me.log.LoggingInterceptor)
            .proxySelector(GlobalProxy.selector())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(24, 5, TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
            .retryOnConnectionFailure(true)
            .build()
        val factory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(cosHttp)
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/149.0.0.0 Mobile Safari/537.36")
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 600_000, 500, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(120_000, true)
            .build()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(
            context,
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(context, factory),
            ),
        ).setLoadControl(loadControl).build()
        com.han1me.player.HplayPlayerController(player, factory)
    }
    DisposableEffect(controller) { onDispose { runCatching { controller.player.release() } } }
    return controller
}
@Composable
fun CosNativePlayerScreen(m3u8: String, resolveFail: Boolean, postUrl: String, onBack: () -> Unit) {
    val controller = rememberCosPlayerController("cos-native:" + m3u8)
    DisposableEffect(Unit) { HplayEnv.applyFullscreen(true); onDispose { HplayEnv.applyFullscreen(false) } }
    LaunchedEffect(m3u8) { if (!resolveFail) runCatching { controller.prepare(m3u8); controller.play() } }
    BackHandler { onBack() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!resolveFail) {
            HplayPlayerView(controller, Modifier.fillMaxSize(), onBackClick = onBack)
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("解析失败", color = CosFaint, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text("在浏览器中打开", color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(CosAccent).clickable {
                        runCatching { HplayEnv.activity()?.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(postUrl))) }
                    }.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
        Text("←", color = Color.White, fontSize = 20.sp,
            modifier = Modifier.statusBarsPadding().padding(10.dp).clip(CircleShape).background(Color(0x99000000))
                .clickable { onBack() }.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
