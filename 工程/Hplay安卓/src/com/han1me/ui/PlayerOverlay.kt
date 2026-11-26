package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **播放器覆盖层组件**（2026-11-25 第 281 轮，照 Han1meViewer 的 PlayerControls）。
 *
 * Viewer 的播放器控件是一套「玻璃拟态」语言：
 *  · `FrostedGlassBar` —— 半透明黑底 + 1dp 白边（6% 不透明）+ 20dp 圆角；
 *  · `CompactChip`    —— 小胶囊按钮（关键帧/倍速/超分），选中态用主题色 35% 叠加。
 *
 * 我们沿用这套视觉，但**不接管播放控制**：
 * 底层仍是 ExoPlayer 的 PlayerView（拖动进度/全屏/倍速都是它自带的），
 * 这里只做「叠在画面上的那层 UI」——返回键、状态条、失败提示。
 *
 * 为什么不全盘重写播放器控件：
 * Viewer 的 PlayerControls 依赖它自己的 HanimePlayerController（682 行）+
 * 双内核抽象（ExoPlayerKernel 461 行 + MpvKernel 737 行）+ 手势层 252 行。
 * 我们的播放器已经能正常播放，重写的风险远大于收益；
 * 先把**观感**对齐（玻璃拟态 + 胶囊），功能保持稳定。
 */

/**
 * 玻璃拟态容器（Viewer 的 FrostedGlassBar 同款视觉）。
 *
 * 半透明黑底让画面透出来一点，1dp 白色低透明边框勾出边缘，
 * 在深色视频上既看得清又不刺眼。
 */
@Composable
fun FrostedGlassBar(
    modifier: Modifier = Modifier,
    rounded: Int = 20,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(rounded.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(rounded.dp)),
        content = content,
    )
}

/**
 * 播放器小胶囊按钮（Viewer 的 CompactChip 同款）。
 *
 * @param selected 选中态：用主题色 35% 叠加，与未选中明显区分
 */
@Composable
fun CompactChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                else Color.White.copy(alpha = 0.12f),
            )
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White.copy(alpha = 0.92f), fontSize = 11.sp)
    }
}

/**
 * 播放器左上角的返回按钮（玻璃拟态圆形）。
 */
@Composable
fun PlayerBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.48f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("‹", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 播放器底部状态条（时长/缓冲/轨道数）。
 *
 * 与 Viewer 的底部信息条同位置：贴在画面下沿，半透明不挡内容。
 */
@Composable
fun PlayerStatusBar(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.52f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp)
    }
}
