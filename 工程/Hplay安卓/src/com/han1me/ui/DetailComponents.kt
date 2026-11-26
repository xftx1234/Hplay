package com.han1me.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **详情页视觉组件库**（2026-11-25 第 281 轮，照 Han1meViewer 的 video 屏重构）。
 *
 * 参照 `Han1meViewer/ui/screen/video/`：
 *  · `VideoCollapsingHeader.kt` —— 折叠头（我们做简化版：渐变 + 标题浮层）
 *  · `VideoIntroductionScreen.kt` —— 简介卡、操作按钮组、标签片
 *
 * 为什么不全盘照搬它的折叠头：
 * 那是一个 245 行的纯 Compose 折叠系统（自定义 NestedScrollConnection +
 * 指数摩擦惯性 + 帧级几何下沉），完整移植风险高、收益主要在「拖拽手感」。
 * 我们的详情页是「滚动容器 + 卡片」结构，用**视差渐变头 + 卡片化信息区**就能达到
 * 同样的视觉层次，且不引入滚动协议的复杂性。
 */

/**
 * 详情页顶部 Hero 区：封面大图 + 渐变遮罩 + 标题浮层。
 *
 * 对应 Viewer 的 `VideoCollapsingHeader` 的静止态视觉。
 */
@Composable
fun DetailHero(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 200.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)),
    ) {
        content()
        // 底部渐变：保证标题在任何封面上都可读
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color(0x33000000),
                        1f to Color(0xE6000000),
                    ),
                ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(16.dp),
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Color(0xFFD0D0D8), fontSize = 11.sp)
            }
        }
    }
}

/**
 * 信息条：时长 / 好评 / 播放量 三个小胶囊横排。
 *
 * 对应 Viewer 简介页顶部的 stats 行。
 */
@Composable
fun DetailStatRow(
    duration: String,
    likes: String,
    views: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            "⏱" to duration,
            "👍" to likes,
            "👁" to views,
        ).forEach { (icon, v) ->
            if (v.isBlank()) return@forEach
            Row(
                Modifier
                    .clip(RoundedCornerShape(HplayDimens.radiusChip))
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(icon, fontSize = 10.sp)
                Spacer(Modifier.width(4.dp))
                Text(v, color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

/**
 * 操作按钮组：收藏 / 稍后看 / 外部播放 / 复制直链 / 下载。
 *
 * 对应 Viewer 的 `VideoRouteActions.kt`。我们的按钮多一个「下载到本机」
 * （本地下载是我们的特色功能，Viewer 是站内下载体系）。
 */
@Composable
fun DetailActionRow(
    actions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // 每行 3 个，超出换行
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, cb) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(cs.surfaceContainerHigh)
                            .androidxClickable(cb)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = cs.onSurface, fontSize = 12.sp, maxLines = 1)
                    }
                }
                // 补齐空位，避免最后一行拉伸
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 小工具：Modifier.clickable 的简写（避免这个文件再 import 一堆）。 */
private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/**
 * 画质选择条：1080P / 720P / 480P 胶囊横排。
 *
 * 点即起播 —— 与 Viewer 的「点画质直接播放」一致。
 */
@Composable
fun DetailQualityRow(
    qualities: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Text(
            "选择画质 · 点击即播",
            color = cs.onSurfaceVariant, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            qualities.forEachIndexed { i, q ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(HplayDimens.radiusChip))
                        .background(if (i == 0) cs.primary else cs.surfaceContainerHigh)
                        .androidxClickable { onPick(q) }
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                ) {
                    Text(
                        q,
                        color = if (i == 0) cs.onPrimary else cs.onSurface,
                        fontSize = 12.sp,
                        fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 标签片行（可换行）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DetailTagRow(
    tags: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { t ->
            HplayChip(text = t, onClick = { onPick(t) })
        }
    }
}
