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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **内容库入口**（2026-11-25 第 281 轮，照 Han1meViewer 的 ContentLibraryEntry）。
 *
 * Viewer 的定义：`ui/screen/library/ContentLibraryEntry.kt`
 * 五个入口，每个都带「是否需要登录」的标记：
 *   Favorite（喜欢）/ WatchLater（稍后看）/ Playlist（播放列表）需登录；
 *   History（历史）/ Download（下载）不需要。
 *
 * 我们照这个模型重构收藏页，但保留自己的「本站收藏」（Viewer 没有 ——
 * 它的收藏全在 hanime1.me 账号里，而我们支持各板块的本地收藏）。
 */
data class HplayLibraryEntry(
    val icon: String,
    val title: String,
    val desc: String,
    /** 需要登录才能用（未登录时置灰并提示）。 */
    val requiresLogin: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 内容库入口卡（2 列网格）。
 *
 * 未登录但需要登录的入口：置灰 + 副标题变成「需登录」，点了也不进（由调用方决定是否提示）。
 */
@Composable
fun LibraryEntryCard(
    entry: HplayLibraryEntry,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    HplayCard(
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .clickable(enabled = enabled) { entry.onClick() },
    ) {
        Row(
            Modifier.fillMaxSize().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (enabled) cs.primaryContainer else cs.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(entry.icon, fontSize = 17.sp)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    color = if (enabled) cs.onSurface else cs.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (!enabled && entry.requiresLogin) "需登录" else entry.desc,
                    color = cs.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 内容库入口网格（2 列）。
 *
 * 与收藏页的「本站收藏」列表并存：
 * 上方是入口网格（点进去看清单），下方是本地收藏的实际内容。
 */
@Composable
fun LibraryEntryGrid(
    entries: List<HplayLibraryEntry>,
    loggedIn: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { e ->
                    LibraryEntryCard(
                        entry = e,
                        enabled = !e.requiresLogin || loggedIn,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}
