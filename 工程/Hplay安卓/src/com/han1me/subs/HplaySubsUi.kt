package com.han1me.subs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.AccentRed
import com.han1me.CardColorSoft
import com.han1me.TextDim
import com.han1me.TextFaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.han1me.HplayOnSurface
import com.han1me.HplaySurfaceContainer
import com.han1me.HplaySurfaceLow
import com.han1me.HplayOutline

/**
 * **我的订阅**（第 80 轮照 Han1meViewer 的 SubscriptionContent 复刻；**第 355 轮补分页**）。
 *
 * 页面组成：
 *  · 顶栏 `← 我的订阅`；
 *  · **已关注作者（N）** + 右侧「向右滑动 →」提示；
 *  · **横向滑动的作者头像行**（3 行 × N 列，列优先），点作者 → 搜索他的作品；
 *  · 下方订阅视频**两列网格** + 触底自动加载下一页。
 *
 * ## 第 355 轮新增：分页（用户报「拉到最下面没有继续加载新视频卡片」）
 *
 * 上一版只调 `HplaySubs.load(1)`，**一次就到底** —— 站点一页只有 ~30 条，
 * 用户滑到底看到的是"没有更多"，而不是"还要去点一下"。
 *
 * 现在照 Han1meViewer 的 `MySubscriptionsViewModel`：
 *  · 分页累加，**按 videoCode 去重**（翻页边界重叠会产生重复条目，参考实现也专门处理了这条）；
 *  · 触底前 800dp 就预取下一页（不等滑到底才转圈）；
 *  · 取到空页 = 到底，停止请求并显示「没有更多了」。
 *
 * 数据来自 [HplaySubs]（选择器语义照参考实现 Parser.getMySubscriptions）。
 */
@Composable
fun SubscriptionsContent(
    onBack: () -> Unit,
    /** 点作者 → 用他的名字去搜索（用户要求「点击作者可跳转到详情搜索页面」）。 */
    onSearchArtist: (String) -> Unit,
    onOpenVideo: (String, String) -> Unit,
) {
    var artists by remember { mutableStateOf<List<HplaySubs.Artist>>(emptyList()) }
    var videos by remember { mutableStateOf<List<HplaySubs.Video>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var loadMoreError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    /** 从第 1 页重新加载。 */
    fun reload() {
        loading = true
        loadMoreError = ""
        endReached = false
        scope.launch {
            val r = withContext(Dispatchers.IO) { HplaySubs.load(1) }
            artists = r.artists
            videos = r.videos
            error = r.error
            page = 1
            if (r.videos.isEmpty()) endReached = true
            loading = false
        }
    }

    /** 取下一页并**按 code 去重**追加。 */
    fun loadMore() {
        if (loading || loadingMore || endReached) return
        loadingMore = true
        loadMoreError = ""
        val next = page + 1
        scope.launch {
            val r = withContext(Dispatchers.IO) { HplaySubs.load(next) }
            val have = videos.map { it.code }.toHashSet()
            val fresh = r.videos.filter { have.add(it.code) }
            if (fresh.isEmpty()) {
                // 空页 = 到底（与参考实现一致：`if (info.subscriptionsVideos.isNotEmpty()) … else hasMore = false`）
                endReached = true
                if (r.error.isNotBlank()) loadMoreError = r.error
            } else {
                videos = videos + fresh
                page = next
            }
            loadingMore = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    // ★ 触底预取：滑到距底部 800px 内就请求下一页（不等到底才转圈）。
    LaunchedEffect(scroll.value, scroll.maxValue, loading, loadingMore, endReached) {
        if (loading || loadingMore || endReached) return@LaunchedEffect
        if (scroll.maxValue <= 0) return@LaunchedEffect
        if (scroll.value >= scroll.maxValue - 800) loadMore()
    }

    Column(Modifier.fillMaxSize().background(HplaySurfaceLow)) {
        // ---------- 顶栏 ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(HplaySurfaceContainer)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { Text("←", color = Color.White, fontSize = 19.sp) }
            Spacer(Modifier.width(10.dp))
            Text("我的订阅", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text("正在获取订阅…", color = TextDim, fontSize = 12.sp)
            }
            return@Column
        }
        if (artists.isEmpty() && videos.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("😶", fontSize = 40.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    error.ifBlank { "还没有订阅任何作者" },
                    color = TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "重新加载", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(AccentRed)
                        .clickable { reload() }.padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
            return@Column
        }

        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 74.dp)) {
            artistSection(
                artists = artists,
                onPick = { name -> onSearchArtist(name) },
            )
            // ---------- 订阅视频（两列网格） ----------
            Spacer(Modifier.height(16.dp))
            videos.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { v ->
                        VideoCard(v, Modifier.weight(1f)) { onOpenVideo(v.code, v.title) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            // ---------- 底部状态 ----------
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        loadingMore -> "正在加载更多…"
                        endReached && loadMoreError.isNotBlank() -> loadMoreError
                        endReached -> "没有更多了"
                        else -> "上滑继续加载"
                    },
                    color = TextFaint, fontSize = 11.5.sp,
                    modifier = if (endReached && loadMoreError.isNotBlank()) {
                        Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                            .clickable { endReached = false; loadMore() }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    } else Modifier,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 作者格（圆形头像 + 名字；点它去搜这个作者）。 */
@Composable
private fun ArtistCell(name: String, avatar: String, onClick: () -> Unit) {
    Column(
        Modifier.width(62.dp).clip(RoundedCornerShape(8.dp)).clickable { onClick() }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(HplaySurfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar.isNotBlank()) {
                com.han1me.web.HplayThumb(avatar, name, Modifier.fillMaxSize(), decodeMaxW = 200)
            } else {
                Text("👤", color = HplayOutline, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            name, color = HplayOnSurface, fontSize = 10.5.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

/** 订阅视频卡（照截图的封面 + 时长 + 播放量 + 标题 + 作者 + 点赞 + 时间）。 */
@Composable
private fun VideoCard(v: HplaySubs.Video, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick() }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp))
                .background(HplaySurfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (v.cover.isNotBlank()) {
                com.han1me.web.HplayThumb(v.cover, v.title, Modifier.fillMaxSize())
            } else {
                Text("🎬", color = HplayOutline, fontSize = 20.sp)
            }
            if (v.duration.isNotBlank()) {
                Text(
                    v.duration, color = Color.White, fontSize = 9.5.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0xAA000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (v.views.isNotBlank()) {
                Text(
                    "▶ ${v.views}", color = Color.White, fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0xAA000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            v.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (v.artist.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(v.artist, color = TextFaint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
            if (v.reviews.isNotBlank()) {
                Text("👍 ${v.reviews}", color = TextDim, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
            }
            if (v.uploadTime.isNotBlank()) {
                Text(v.uploadTime, color = TextFaint, fontSize = 10.sp)
            }
        }
    }
}

/**
 * 订阅作者区（3 行 × N 列，列优先，横向滚动）。
 *
 * 照参考实现 SubscriptionContent.ArtistListSection 的排布公式：
 * itemIndex = columnIndex * rows + rowIndex（列优先）。
 * 「向右滑动 →」只在**确实溢出**时出现（和参考实现一致，不溢出不提示）。
 */
@Composable
private fun artistSection(
    artists: List<HplaySubs.Artist>,
    onPick: (String) -> Unit,
) {
    val rows = 3
    val cols = maxOf(1, (artists.size + rows - 1) / rows)
    val scroll = rememberScrollState()
    val showHint = scroll.value < scroll.maxValue
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已关注作者（${artists.size}）", color = Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            if (showHint) Text("向右滑动 →", color = TextDim, fontSize = 11.5.sp)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll).padding(start = 10.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(cols) { ci ->
                Column(
                    Modifier.width(62.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(rows) { ri ->
                        val idx = ci * rows + ri
                        val a = artists.getOrNull(idx)
                        if (a != null) ArtistCell(a.name, a.avatar) { onPick(a.name) }
                    }
                }
            }
        }
    }
}
