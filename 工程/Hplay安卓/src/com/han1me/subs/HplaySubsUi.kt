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
import com.han1me.CardColor
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
 * **我的订阅**（第 80 轮，照 Han1meViewer 的 SubscriptionContent 复刻，对齐用户截图 1）。
 *
 * 页面组成：
 *  · 顶栏 `← 我的订阅`；
 *  · **已关注作者（60）** + 右侧「向右滑动 →」提示；
 *  · **横向滑动的作者头像行**（每行 5 个，圆形头像 + 名字），**点作者 → 跳转搜索他的作品**；
 *  · 下方订阅视频**两列网格**（封面 + 时长 + 播放量 + 标题 + 作者 + 点赞 + 上传时间）。
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
    var page by remember { mutableStateOf<HplaySubs.Page?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() {
        loading = true
        scope.launch {
            val p = withContext(Dispatchers.IO) { HplaySubs.load(1) }
            page = p
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

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

        val p = page
        if (loading) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text("正在获取订阅…", color = TextDim, fontSize = 12.sp)
            }
            return@Column
        }
        if (p == null || (p.artists.isEmpty() && p.videos.isEmpty())) {
            Column(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("😶", fontSize = 40.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    p?.error?.ifBlank { "还没有订阅任何作者" } ?: "取不到订阅内容",
                    color = TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "重新加载", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(AccentRed)
                        .clickable { load() }.padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
            return@Column
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 74.dp)) {
            // ★ 第 82 轮（用户要求）：「我的订阅页面要有图3 这种订阅作者的近期更新视频推荐，
            //   一比一复刻」——并且和收藏页的「我的订阅」**合并成同一种样式**。
            //
            // 布局照参考实现 SubscriptionContent.ArtistListSection：
            //  · **3 行** × N 列（N = ⌈作者数 / 3⌉），**列优先**填充
            //    （itemIndex = columnIndex * 3 + rowIndex，与参考实现同一公式）；
            //  · 整块**横向滚动**；
            //  · 「向右滑动 →」**只在真的溢出时**才显示（照参考实现 showArtistOverflowHint）。
            artistSection(
                artists = p.artists,
                onPick = { name -> onSearchArtist(name) },
            )
            // ---------- 订阅视频（两列网格） ----------
            Spacer(Modifier.height(16.dp))
            p.videos.chunked(2).forEach { pair ->
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
            Modifier.size(54.dp).clip(CircleShape).background(HplaySurfaceContainer)
                .then(
                    // 头像用已封装的图片组件；空则留占位
                    if (avatar.isNotBlank()) {
                        Modifier
                    } else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar.isNotBlank()) {
                // 头像按 **200px** 解码（第 84 轮）：一个 54dp 的圆里放 1000px 的图纯属浪费 ——
                // 每张少解 96% 的像素，60 个头像的首屏时间差距很明显。
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
            // 时长（右下角）
            if (v.duration.isNotBlank()) {
                Text(
                    v.duration, color = Color.White, fontSize = 9.5.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0xAA000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            // 播放量（左下角）
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
 * itemIndex = columnIndex * rows + rowIndex（列优先，所以竖着读完一列再读下一列）。
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
    // 只在「内容宽度 > 可视宽度 且 还没滑到底」时提示右滑
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
                        val idx = ci * rows + ri   // 列优先（照参考实现）
                        val a = artists.getOrNull(idx)
                        if (a != null) ArtistCell(a.name, a.avatar) { onPick(a.name) }
                    }
                }
            }
        }
    }
}