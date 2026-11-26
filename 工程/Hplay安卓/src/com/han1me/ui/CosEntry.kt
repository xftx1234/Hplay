package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **cos 板块入口卡**（2026-11-25 第 290 轮）。
 *
 * 用户要求：「cos 板块你集成到三次元板块的最新里，底栏就删掉吧」。
 *
 * 底栏的 cos tab 已删（现在只剩 主页/最新/收藏/用户）；
 * 这里在「最新」（X99Board 站点合集）页顶部放一张醒目的入口卡，
 * 点它进原来的 CosBoardScreen —— 功能一行没改，只是换了个入口。
 *
 * 视觉上用主题色渐变，让它在一堆站点卡片里显眼但不突兀。
 */
@Composable
fun CosEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        cs.primary.copy(alpha = 0.30f),
                        cs.primary.copy(alpha = 0.10f),
                    ),
                ),
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.primary.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("🎭", fontSize = 20.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "cos 板块",
                    color = cs.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "cosplay 图集与短视频 · 独立内容源",
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Text("›", color = cs.onSurfaceVariant, fontSize = 20.sp)
        }
    }
}
