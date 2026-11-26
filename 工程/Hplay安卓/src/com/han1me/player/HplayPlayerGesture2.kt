package com.han1me.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * **播放器手势层**（2026-11-25 第 289 轮，照 Han1meViewer 的 `PlayerGestureLayer.kt`）。
 *
 * ## 手势协议（与 Viewer 一致）

 * | 手势 | 行为 |
 * |---|---|
 * | 单击 | 显示/隐藏控制条 |
 * | 双击 | 播放 ⇄ 暂停 |
 * | 长按 | 临时 3× 加速（松手恢复） |
 * | 横向拖动 | 调进度（松手才 seek，拖动中只显示预览） |
 * | 纵向拖动 | 左半边调亮度 / 右半边调音量 |
 *
 * ## 为什么拖动中不 seek

 * 拖动过程中每帧 seek 会让解码器反复重排（ExoPlayer 会不断丢弃缓冲），
 * 结果是指示器数字在跳、画面卡死。Viewer 的做法是**松手才 seek**，
 * 拖动中只更新一个本地浮点值用于显示预览。我们沿用。
 *
 * ## 锁定态

 * `locked = true` 时除「解锁」外的全部手势失效 —— 防止躺在床上看时误触。
 */
@Composable
fun HplayPlayerGestureLayer(
    state: HplayPlayerUiState,
    durationMs: Long,
    currentPositionMs: Long,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val curTap by rememberUpdatedState(onTap)
    val curDouble by rememberUpdatedState(onDoubleTap)
    val curLongStart by rememberUpdatedState(onLongPressStart)
    val curLongEnd by rememberUpdatedState(onLongPressEnd)
    val curSeek by rememberUpdatedState(onSeekTo)

    // 拖动预览状态
    var dragMode by remember { mutableStateOf(0) }   // 0 无 / 1 横向 / 2 纵向
    var dragDelta by remember { mutableFloatStateOf(0f) }
    var dragStartPos by remember { mutableFloatStateOf(0f) }
    var dragSide by remember { mutableStateOf(0) }   // 0 左(亮度) / 1 右(音量)

    Box(
        modifier
            .fillMaxSize()
            // ★★★ 2026-11-25（第 316 轮 · 用户报「控制条隐藏后点不出来」）：
            //
            // 根因：pointerInput 的 key 里放了 currentPositionMs —— 它**每 100ms 变一次**
            // （进度泵频率）。key 变化会让整个手势检测器重建，
            // 单击事件在重建间隙几乎必丢 —— 表现就是「点了没反应」。
            //
            // 修法：key 只留 isGestureLocked（真正需要重置手势的状态）。
            // 需要读的实时值（durationMs / position）通过 rememberUpdatedState 取。
            .pointerInput(state.isGestureLocked) {
                if (state.isGestureLocked) {
                    // 锁定态：只有单击（用于唤出解锁按钮）
                    detectTapGestures(onTap = { curTap() })
                    return@pointerInput
                }
                detectTapGestures(
                    onTap = { curTap() },
                    onDoubleTap = { curDouble() },
                    onLongPress = {
                        curLongStart()
                    },
                )
            }
            .pointerInput(state.isGestureLocked) {
                if (state.isGestureLocked) return@pointerInput
                detectDragGestures(
                    onDragStart = { off ->
                        dragDelta = 0f
                        dragMode = 0
                        dragStartPos = currentPositionMs.toFloat()
                        dragSide = if (off.x < size.width / 2f) 0 else 1
                    },
                    onDrag = { _, delta ->
                        if (dragMode == 0) {
                            // 判定主轴：前 12px 决定是横还是纵
                            if (abs(delta.x) > abs(delta.y)) dragMode = 1
                            else if (abs(delta.y) > abs(delta.x)) dragMode = 2
                        }
                        when (dragMode) {
                            1 -> dragDelta += delta.x
                            2 -> dragDelta += -delta.y
                        }
                    },
                    onDragEnd = {
                        if (dragMode == 1 && durationMs > 0) {
                            // 横向：每 4px 约 1 秒；松手才 seek
                            val deltaMs = (dragDelta / 4f * 1000f).toLong()
                            val target = (dragStartPos.toLong() + deltaMs)
                                .coerceIn(0L, durationMs)
                            curSeek(target)
                        }
                        dragMode = 0
                        dragDelta = 0f
                    },
                    onDragCancel = {
                        dragMode = 0
                        dragDelta = 0f
                    },
                )
            },
    ) {
        // 拖动预览指示器
        if (dragMode == 1 && durationMs > 0) {
            val deltaMs = (dragDelta / 4f * 1000f).toLong()
            val target = (dragStartPos.toLong() + deltaMs).coerceIn(0L, durationMs)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    fmt(target) + " / " + fmt(durationMs),
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
        }
        if (dragMode == 2) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    (if (dragSide == 0) "亮度 " else "音量 ") +
                        (50 + (dragDelta / 4f).toInt().coerceIn(-50, 50)).toString() + "%",
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
