package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **用户页重构**（2026-11-25 第 278 轮，照 Han1meViewer ProfileScreen 复刻）。
 *
 * 参照 `Han1meViewer/ui/screen/main/ProfileScreen.kt`：
 *  · 顶部 ProfileHeaderCard：头像 + 昵称 + 当前站点徽章，整卡可点
 *  · 「快捷入口」小标题
 *  · **2 列网格卡片**放入口
 *
 * 原版是一长列 UserRow + 内联展开设置面板，越加越长。
 * 现在按 Viewer 改成网格卡片：一屏看全，点进去逐项。
 */

/** 用户页入口项。 */
data class HplayProfileEntry(
    val icon: String,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

/** 用户页（我的页）。 */
@Composable
fun HplayProfileScreen(
    loggedIn: Boolean,
    displayName: String,
    siteName: String,
    entries: List<HplayProfileEntry>,
    onAccountClick: () -> Unit,
    onSwitchSite: () -> Unit,
    modifier: Modifier = Modifier,
    /** 云端账户头像 URL（空 = emoji 兜底）。 */
    avatarUrl: String = "",
) {
    val cs = MaterialTheme.colorScheme
    // ★ 第 327 轮：快捷入口列数自适应
    BoxWithConstraints {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columnsFor(maxWidth, minItemWidth = 150.dp)),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = HplayDimens.page,
            end = HplayDimens.page,
            top = 12.dp,
            bottom = HplayDimens.bottomInset,
        ),
        horizontalArrangement = Arrangement.spacedBy(HplayDimens.gap),
        verticalArrangement = Arrangement.spacedBy(HplayDimens.gap),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ProfileHeaderCard(loggedIn, displayName, siteName, avatarUrl, onAccountClick)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "快捷入口",
                color = cs.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
        }
        items(entries, key = { it.title }) { e ->
            ProfileEntryCard(e)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            // ★ 2026-11-25（第 306 轮）：**优选访问 / 站点线路卡片已移除**（用户要求）。
            // 站点切换入口移到「设置 > 网络」，用户页不再重复一份。
        }
    }
    }   // ← 第 327 轮：BoxWithConstraints 收尾
}

/** 顶部资料卡：头像 + 昵称 + 站点徽章。 */
@Composable
private fun ProfileHeaderCard(
    loggedIn: Boolean,
    displayName: String,
    siteName: String,
    /** 云端账户头像 URL（空 = 用 emoji 兜底）。 */
    avatarUrl: String = "",
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    HplayCard(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ★★★ 2026-11-25（第 307 轮）：**头像三层优先**（用户要求恢复自定义头像）。
            //
            // 优先级：
            //   1. 本机自定义头像（user.avatar.file）—— 用户自己挑的图
            //   2. 云端账户头像（avatarUrl）—— 从 /user/<id>/edit 抓的
            //   3. emoji 兜底
            //
            // 点一下头像可更换（HplaySlots.pickImage）。
            var slotTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
            val customFile = androidx.compose.runtime.remember(slotTick) {
                com.han1me.X99.setting("user.avatar.file", "")
            }
            val customBmp = androidx.compose.runtime.remember(customFile) {
                if (customFile.isBlank()) null else com.han1me.HplaySlots.decodeImage(customFile)
            }
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(cs.surfaceContainerHigh)
                    .clickable {
                        // 点头像 -> 挑图换自定义头像
                        if (com.han1me.HplaySlots.pickSupported) {
                            com.han1me.HplaySlots.pickImage("user.avatar.file") { p ->
                                if (p != null) {
                                    com.han1me.X99.putSetting("user.avatar.file", p)
                                    slotTick++
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (customBmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = customBmp,
                        contentDescription = "自定义头像",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else if (avatarUrl.isNotBlank()) {
                    com.han1me.web.HplayThumb(
                        url = avatarUrl,
                        contentDescription = displayName,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else if (com.han1me.lab.LoginState.loadingAccount) {
                    // 加载中：转圈代替 emoji
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = cs.primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (loggedIn) "🙂" else "👤", fontSize = 24.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // ★ 2026-11-25（第 320 轮 · 用户要求）：
                // 登录后 2 秒内显示「正在加载账户…」，不允许一直停留。
                val loading = com.han1me.lab.LoginState.loadingAccount
                Text(
                    if (loading) "正在加载账户…" else displayName,
                    color = cs.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(HplayDimens.radiusChip))
                        .background(cs.primaryContainer)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text(siteName, color = cs.onPrimaryContainer, fontSize = 10.sp)
                }
            }
            Text("›", color = cs.outline, fontSize = 20.sp)
        }
    }
}

/** 快捷入口卡片（2 列网格）。 */
@Composable
private fun ProfileEntryCard(e: HplayProfileEntry) {
    val cs = MaterialTheme.colorScheme
    HplayCard(
        Modifier.fillMaxWidth().height(96.dp).clickable { e.onClick() },
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(e.icon, fontSize = 20.sp)
            Column {
                Text(
                    e.title,
                    color = cs.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    e.subtitle,
                    color = cs.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 底部「切换站点」卡（整行）。 */
@Composable
private fun SwitchSiteCard(siteName: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    HplayCard(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⇄", fontSize = 16.sp, modifier = Modifier.width(30.dp))
            Column(Modifier.weight(1f)) {
                Text("优选访问 / 站点线路", color = cs.onSurface, fontSize = 13.sp)
                Text(
                    "当前：" + siteName,
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Text("›", color = cs.outline, fontSize = 18.sp)
        }
    }
}

/**
 * **用户页网格的便捷入口**（宿主只调这个）。
 *
 * 与 [HplayProfileScreen] 的区别：这个直接接收「已经组装好的入口列表」，
 * 内部不再管站点名/登录态之外的任何东西。
 *
 * 为什么不直接在 Han1meApp.kt 里调 HplayProfileScreen：
 * 那个文件是补丁生成区，参数一多就难维护；这里包一层，宿主侧代码能短则短。
 */
@Composable
fun HplayProfileGrid(
    loggedIn: Boolean,
    displayName: String,
    siteName: String,
    entries: List<HplayProfileEntry>,
    onAccountClick: () -> Unit,
    onSwitchSite: () -> Unit,
    modifier: Modifier = Modifier,
    /** 云端账户头像 URL（空 = emoji 兜底）。 */
    avatarUrl: String = "",
) {
    HplayProfileScreen(
        loggedIn = loggedIn,
        displayName = displayName,
        siteName = siteName,
        entries = entries,
        onAccountClick = onAccountClick,
        onSwitchSite = onSwitchSite,
        modifier = modifier,
        avatarUrl = avatarUrl,
    )
}
