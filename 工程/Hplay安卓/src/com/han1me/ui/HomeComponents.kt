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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **首页组件库**（2026-11-25 第 290 轮，照 Han1mePlus 的 explore_page.dart）。
 *
 * ## 从 Plus 抄的三件事

 * Plus 的首页（`features/explore/explore_page.dart`，423 行）与我们的差别：

 * | 方面 | Plus | 我们（旧） |
 * |---|---|---|
 * | 滚动容器 | `CustomScrollView` + Sliver，长列表懒加载 | `LazyColumn`（也懒加载，但区块内部是整体） |
 * | 区块头 | `titleLarge` 粗体 + 右侧 `TextButton.icon`「查看更多 ›」 | 手搓 Row，字号/间距各处不一 |
 * | 区块内容 | 每区块**横向滚动**的卡片流 | 固定纵向列表，一屏只能看 2 部 |
 * | Hero | 16:8 圆角 8dp，图上叠标题 | 轮播，样式自定 |
 * | 区块顺序 | **用户可自定义排序与显隐**（applyHomeSectionLayout） | 固定 |

 * 本轮先做视觉（区块头 / 横滑流 / Hero 圆角），排序显隐随后。
 */

/**
 * 区块头：标题 + 右侧「查看更多 ›」。
 *
 * 对应 Plus 的 `_SectionHeader`（它用 Padding(16,12,8,8) + Row）。
 */
@Composable
fun HomeSectionHeader(
    title: String,
    onMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = cs.onSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (onMore != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onMore() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("查看更多", color = cs.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.width(2.dp))
                Text("›", color = cs.onSurfaceVariant, fontSize = 14.sp)
            }
        }
    }
}

/**
 * Hero 区：封面 + 底部渐变 + 标题浮层。
 *
 * 与 Plus 的 `_FeaturedVideo` 一致：16:8 比例、8dp 圆角、外边距 16dp。
 */
@Composable
fun HomeHero(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
            .aspectRatio(16f / 8f)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
    ) {
        content()
        // 底部渐变，保证标题可读
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.52f to Color(0x22000000),
                        1f to Color(0xD9000000),
                    ),
                ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = Color(0xFFB9B9C2),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
            }
            Text(
                title,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 横滑卡片行（每区块内部的卡片流）。
 *
 * Plus 是每区块一个横向列表（一屏看 3~4 部，横滑看更多）；
 * 我们旧版是纵向网格（一屏 2 部）。横滑的信息密度高得多。
 */
@Composable
fun <T> HomeHorizontalRow(
    items: List<T>,
    modifier: Modifier = Modifier,
    cardWidth: androidx.compose.ui.unit.Dp = 168.dp,
    content: @Composable (T) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items.size) { i ->
            Box(Modifier.width(cardWidth)) { content(items[i]) }
        }
    }
}
