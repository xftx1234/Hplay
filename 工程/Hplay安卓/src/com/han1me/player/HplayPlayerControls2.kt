package com.han1me.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **播放器控制层 UI**（2026-11-25 第 289 轮）。
 *
 * 视觉语言照 Han1meViewer 的 `PlayerControls.kt`：
 *  · 顶栏/底栏都是**玻璃拟态条**（半透明黑 + 细白边）；
 *  · 按钮是**圆形玻璃**，不是 Material 的方形按钮；
 *  · 进度条极细（2dp），拖动时才加粗 —— 不挡画面。
 *
 * 与 Viewer 的差异：它的控制条绑在自己那套 4605 行体系上；
 * 我们只取**视觉与布局**，行为接到 [HplayPlayerController2]。
 */

/** 玻璃拟态容器。 */
@Composable
private fun GlassBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 圆形玻璃按钮。 */
@Composable
private fun GlassButton(
    label: String,
    onClick: () -> Unit,
    size: Int = 36,
    active: Boolean = false,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                else Color.Black.copy(alpha = 0.46f),
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = (size / 2.4).sp)
    }
}

/**
 * 顶部控制条：返回 + 标题 + 倍速。
 */
@Composable
fun HplayPlayerTopBar(
    state: HplayPlayerUiState,
    visible: Boolean,
    onBack: () -> Unit,
    onSpeedClick: () -> Unit,
    /**
     * ★ 第 344 轮：**锁定手势**入口。
     *
     * 这个功能此前只有「读数」没有「写法」：HplayPlayerUiState.isGestureLocked
     * 存在、手势层也按它分支，但全项目**没有任何一处**把它置 true ——
     * 也就是说用户永远摸不到锁定，而那半套分支代码还是活的。
     * 现在顶栏给出按钮，锁定后只剩悬浮的解锁键（见 HplayPlayerView2）。
     */
    onLock: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassButton("←", onBack)
            Spacer(Modifier.width(10.dp))
            Text(
                state.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            GlassButton("🔒", onLock)
            Spacer(Modifier.width(8.dp))
            val speed = HplayPlayerDefaults.SPEEDS.getOrElse(state.speedIndex) { 1.0f }
            GlassBar {
                Text(
                    if (speed == 1.0f) "倍速" else speed.toString().removeSuffix(".0") + "x",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onSpeedClick() }.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/**
 * 底部控制条：进度 + 时间 + 播放/暂停 + 全屏。
 */
@Composable
fun HplayPlayerBottomBar(
    state: HplayPlayerUiState,
    progress: HplayPlayerProgress,
    visible: Boolean,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            val dur = if (progress.durationMs > 0) progress.durationMs else state.durationMs
            val frac = if (dur > 0) (progress.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
            // 极细进度条（2dp），拖动时加粗
            // ★★★ 2026-11-25（第 319 轮）：**进度条支持拖动**。
            //
            // 原来只有 clickable，点任意位置跳 50% —— 用户报「进度条不能拖」。
            // 现在用 pointerInput 实现真正的拖动：
            //   · 按下即定位（不用等松手，符合直觉）
            //   · 拖动中实时跟随，松手才真正 seek（拖动中每帧 seek 会让解码器反复重排）
            //   · 轨道高度 16dp（视觉 2dp，但触摸区足够好点）
            var dragFrac by remember { mutableStateOf<Float?>(null) }
            val shownFrac = dragFrac ?: frac
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .pointerInput(dur) {
                        if (dur <= 0) return@pointerInput
                        detectTapGestures { off ->
                            val f = (off.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeek((dur * f).toLong())
                        }
                    }
                    .pointerInput(dur) {
                        if (dur <= 0) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { off ->
                                dragFrac = (off.x / size.width.toFloat()).coerceIn(0f, 1f)
                            },
                            onHorizontalDrag = { change, _ ->
                                dragFrac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                dragFrac?.let { onSeek((dur * it).toLong()) }
                                dragFrac = null
                            },
                            onDragCancel = { dragFrac = null },
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.22f)))
                Box(
                    Modifier.fillMaxWidth(shownFrac).height(2.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Box(Modifier.fillMaxWidth(shownFrac).height(2.dp)) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(if (dragFrac != null) 14.dp else 10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                // 拖动时显示时间预览
                if (dragFrac != null) {
                    Text(
                        fmt((dur * (dragFrac ?: 0f)).toLong()),
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(bottom = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassButton(
                    // ★ 第 344 轮：**缓冲中不该显示 ▶**。
                    // 原来只要不是 PLAYING 就画 ▶，而缓冲时用户看到 ▶ 会以为已暂停，
                    // 点一下也没反应（还在缓冲），完全误导。缓冲用 ⋯ 表示在等。
                    when (state.state) {
                        HplayPlayerState.PLAYING -> "‖"
                        HplayPlayerState.BUFFERING, HplayPlayerState.PREPARING -> "⋯"
                        else -> "▶"
                    },
                    onPlayPause,
                    size = 40,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    fmt(progress.positionMs) + " / " + fmt(dur),
                    color = Color.White.copy(alpha = 0.86f),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                GlassBar {
                    Text(
                        (progress.bufferedPercent).toString() + "%",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                GlassButton("⛶", onFullscreen)
            }
        }
    }
}

/** 毫秒转 mm:ss / h:mm:ss。 */
internal fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%d:%02d", m, s)
    }
}
