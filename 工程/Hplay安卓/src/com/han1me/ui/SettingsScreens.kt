package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **设置界面**（2026-11-25 第 278 轮重构）。
 *
 * ## 设计依据

 * 对比了两家：
 *  · **Han1meViewer**：8 个一级分类（外观/播放/网络/下载/关键帧/隐私/数据/关于），
 *    每类一个独立页面，用 `SettingsCategoryItem` 做统一行样式。
 *  · **Han1mePlus**：6 个分区（外观/播放/网络/内容/应用/其他），
 *    用 `SettingsSection` + `SettingsCardItem` 组织，卡片式。

 * **结论：用 Plus 的分区卡片结构 + Viewer 的分类完整性**。
 * 理由：卡片分组在手机上更好扫读（Plus 的强项），
 * 而 Viewer 的分类更细、更贴近我们已有的功能面（网络/下载/数据 我们都有）。
 *
 * ## 与我们原有功能的对齐

 * 原版设置散在「用户」页的几个面板里（NetSettingsSection / EdgeNodeSection /
 * CatalogSourceSection / DownloadsPanel…）。这里全部收纳进统一设置页，
 * 「用户」页只留账号与快捷入口。
 */

/** 设置页二级分类。 */
enum class SettingsPage(val title: String, val icon: String, val desc: String) {
    ROOT("设置", "⚙", ""),
    APPEARANCE("外观", "🎨", "主题配色、明暗、纯黑模式"),
    NETWORK("网络", "🌐", "内置节点、代理范围、DoH、边缘节点"),
    PLAYBACK("播放", "▶", "播放器、画质、外部播放器"),
    DOWNLOAD("下载", "⬇", "并发数、保存目录、分片"),
    DATA("数据", "💾", "收藏、历史、备份恢复"),
    PRIVACY("隐私", "🔒", "隐私锁、启动验证"),
    ABOUT("关于", "ℹ", "版本、更新、开源说明");
}

/**
 * 设置主页面。
 *
 * @param onOpen 打开某个二级分类
 * @param onClose 关闭设置（返回上一屏）
 * @param extra 额外注入的区块（例如「节点设置」这种已有的复杂面板）
 */
@Composable
fun SettingsHomeScreen(
    onOpen: (SettingsPage) -> Unit,
    onClose: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background)) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().height(HplayDimens.topBar).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = cs.onSurface,
                fontSize = 26.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onClose() }
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            )
            Text(
                "设置",
                color = cs.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = HplayDimens.page,
                end = HplayDimens.page,
                bottom = 24.dp,
            ),
        ) {
            item {
                HplaySectionGroup("外观与播放") {
                    SettingEntry(SettingsPage.APPEARANCE, onOpen)
                    SettingEntry(SettingsPage.PLAYBACK, onOpen)
                }
            }
            item {
                HplaySectionGroup("网络与下载") {
                    SettingEntry(SettingsPage.NETWORK, onOpen)
                    SettingEntry(SettingsPage.DOWNLOAD, onOpen)
                }
            }
            item {
                HplaySectionGroup("数据与隐私") {
                    SettingEntry(SettingsPage.DATA, onOpen)
                    SettingEntry(SettingsPage.PRIVACY, onOpen)
                }
            }
            if (extra != null) {
                item { extra() }
            }
            item {
                HplaySectionGroup("关于") {
                    SettingEntry(SettingsPage.ABOUT, onOpen)
                }
            }
        }
    }
}

@Composable
private fun SettingEntry(page: SettingsPage, onOpen: (SettingsPage) -> Unit) {
    HplaySettingRow(
        icon = page.icon,
        title = page.title,
        subtitle = page.desc,
        onClick = { onOpen(page) },
    )
}

/**
 * **外观设置**：主题配色 / 明暗 / 纯黑。
 *
 * 这是 MD3 主题体系的入口 —— 用户在这里能真正换肤。
 */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var seed by remember { mutableStateOf(HplayThemeStore.seed) }
    var dark by remember { mutableStateOf(HplayThemeStore.dark) }
    var amoled by remember { mutableStateOf(HplayThemeStore.amoled) }
    val cs = MaterialTheme.colorScheme

    SettingsScaffold("外观", onBack) {
        item {
            HplaySectionGroup("主题配色") {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "选一个主色，整个界面会按它派生配色",
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(HplaySeed.entries.toList()) { s ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    seed = s
                                    HplayThemeStore.setSeed(ctx, s)
                                },
                            ) {
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(s.seed))
                                        .then(
                                            if (seed == s) Modifier.border(2.dp, cs.onSurface, CircleShape)
                                            else Modifier,
                                        ),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    s.label,
                                    color = if (seed == s) cs.onSurface else cs.onSurfaceVariant,
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            HplaySectionGroup("显示") {
                HplaySwitchRow(
                    title = "深色模式",
                    checked = dark,
                    subtitle = "关闭则使用浅色配色",
                    icon = "🌙",
                    onCheckedChange = { dark = it; HplayThemeStore.setDark(ctx, it) },
                )
                HplaySwitchRow(
                    title = "纯黑（AMOLED）",
                    checked = amoled,
                    subtitle = "深色模式下把背景压成纯黑，OLED 屏更省电",
                    icon = "⬛",
                    onCheckedChange = { amoled = it; HplayThemeStore.setAmoled(ctx, it) },
                )
            }
        }
    }
}

/** 设置子页面的统一外壳（顶栏 + 滚动容器）。 */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background)) {
        Row(
            Modifier.fillMaxWidth().height(HplayDimens.topBar).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = cs.onSurface,
                fontSize = 26.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onBack() }
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            )
            Text(title, color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = HplayDimens.page,
                end = HplayDimens.page,
                bottom = 24.dp,
            ),
            content = content,
        )
    }
}
