package com.han1me.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.AccentRed
import com.han1me.TextDim
import com.javrocket.hanime.HanimeVideo
import kotlinx.coroutines.delay

/**
 * **首页大封面轮播**（第 80 轮，用户要求「首页大封面可滑动查看多个网页版的大封面视频」）。
 *
 * 参考：Han1meViewer 首页 banner 与 hanime1.me 网页版顶部大图（可左右切换多个最新片）。
 *
 * 行为：
 *  · 上方大图区**左右滑动**切换（HorizontalPager）；
 *  · 底部**圆点指示器**（当前页高亮）；
 *  · **4 秒自动轮播**（用户手动滑动后停 12 秒再恢复，避免「刚划走又被切回来」）；
 *  · 点图进详情（与原 HeroBanner 一致）。
 *
 * 只替换大图那一块，其余（标题/标签/分区流）保持原样 —— 遵循「不要乱动其他地方」。
 */
@Composable
fun HeroCarousel(
    items: List<HanimeVideo>,
    onOpenVideo: (HanimeVideo) -> Unit,
    onPageChanged: (HanimeVideo) -> Unit = {},
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState(pageCount = { items.size })
    // 点圆点跳页要用（第 88 轮）
    val scope = rememberCoroutineScope()

    // ★★ 第 88 轮修（用户报「大封面自动轮播会卡在两个封面之间」）：
    //
    // 根因：原来的自动轮播是
    //     LaunchedEffect(pager.currentPage, manualHold) { … animateScrollToPage(next) }
    // 而 **animateScrollToPage 动画进行中就会改变 currentPage** ——
    // 于是 effect 立刻被取消重启，正在跑的动画被打断，页面停在两张之间（正是用户看到的现象）。
    //
    // 修法两条：
    //  ① 用 **settledPage**（动画结束后的稳定页）而不是 currentPage 作触发依据；
    //  ② 用 while(true) 循环 + 内部 delay，**不把页码当 key** ——
    //     这样动画期间 effect 不会被取消，能一次滑到位。
    //  用户手动拖动时（isScrollInProgress）跳过这一轮，不抢用户的操作。
    LaunchedEffect(items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(4000L)
            if (pager.isScrollInProgress) continue          // 用户正在拖：这轮不自动翻
            val next = (pager.settledPage + 1) % items.size
            // 一次滑到位；用 runCatching 兜住「滑动中被取消」这种正常情况。
            runCatching { pager.animateScrollToPage(next) }
        }
    }

    // 当前稳定页变化 → 通知外层去取这一部的详情（标签/简介用）。
    LaunchedEffect(pager.settledPage) {
        items.getOrNull(pager.settledPage)?.let { onPageChanged(it) }
    }

    Box(Modifier.fillMaxWidth()) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
            val v = items[page]
            // ★ 第 88 轮修（用户报「图只能看到一部分，修一下可以看到全图」）：
            // 原来写死 height(400.dp) + ContentScale.Crop —— 16:9 的封面被裁掉近一半。
            // 现在**按 16:9 算高度**（全宽 / 16 × 9）并改用 ContentScale.Fit，整张图完整可见。
            Box(
                Modifier.fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clickable { onOpenVideo(v) },
            ) {
                // Fit 会留白，所以先铺一层纯黑底 —— 与站点深色背景一致（必须在图片之前画）。
                Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0F)))
                com.han1me.web.HplayThumb(
                    url = v.thumb,
                    contentDescription = v.title,
                    // Fit：不裁切，整图可见（配合下面的黑底，留白不突兀）。
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Color(0x26000000)))
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color(0x000D0D0F), Color(0x8C0D0D0F), Color(0xF20D0D0F)),
                        ),
                    ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                    Text(
                        v.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (v.brand.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(v.brand, color = TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
        // 圆点指示器（照网页版 banner 底部那几个点）
        if (items.size > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items.indices.forEach { i ->
                    // 用 settledPage 画高亮：动画途中不会乱跳，停下来才更新（与自动轮播同一口径）。
                    val on = i == pager.settledPage
                    Box(
                        Modifier.size(if (on) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (on) AccentRed else Color(0x66FFFFFF))
                            // 点圆点直接跳到那一页（第 88 轮：原来只是「暂停自动轮播」，点了不跳，
                            // 用户会觉得没反应；现在点哪张就滑到哪张，语义更直白）。
                            .clickable { scope.launch { runCatching { pager.animateScrollToPage(i) } } },
                    )
                }
            }
        }
    }
    // 当前页变化 → 通知外层去取这一部的详情（标签/简介用）
    LaunchedEffect(pager.currentPage) {
        items.getOrNull(pager.currentPage)?.let { onPageChanged(it) }
    }
}