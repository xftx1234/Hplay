package com.han1me.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.update.HplayUpdateChannel

/**
 * **各设置子页的正文内容**（2026-11-25 第 278 轮）。
 *
 * 拆成独立文件是为了让 `SettingsHost.kt` 只负责路由，不塞满界面代码。
 * 每个页面只做「展示 + 已有开关的接线」，不重复实现底层逻辑 ——
 * 底层能力（节点、备份、下载）都在各自的包里，这里只调用。
 */

/** 网络设置页正文：内置节点 + 说明。 */
@Composable
fun NetworkSettingsContent() {
    val cs = MaterialTheme.colorScheme
    Column {
        HplaySectionGroup("内置节点") {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "内置节点只在本应用内生效（本地 SOCKS），不改系统设置。" +
                        "官方源建议开启；三次元板块可单独设为直连。",
                    color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }
        }
        // 真正的节点面板由宿主注入（NodeSection 需要 Context，且体量大）
    }
}

/**
 * 播放设置页正文。
 *
 * 这里放的是**真正会影响播放行为**的开关。
 * 目前 Hplay 的播放策略存在 X99 设置里（键名 x99.*），这里读写同一份。
 */
@Composable
fun PlaybackSettingsContent() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var autoFull by remember { mutableStateOf(com.han1me.X99.setting("x99.play.autoFull", "false") == "true") };
    var keepScreen by remember { mutableStateOf(com.han1me.X99.setting("x99.play.keepScreen", "true") == "true") };

    Column {
        HplaySectionGroup("播放行为") {
            HplaySwitchRow(
                title = "起播即全屏",
                checked = autoFull,
                subtitle = "点画质后直接进入全屏播放",
                icon = "⛶",
                onCheckedChange = {
                    autoFull = it
                    com.han1me.X99.putSetting("x99.play.autoFull", if (it) "true" else "false")
                },
            )
            HplaySwitchRow(
                title = "播放时保持亮屏",
                checked = keepScreen,
                subtitle = "关闭后按系统息屏时间黑屏",
                icon = "💡",
                onCheckedChange = {
                    keepScreen = it
                    com.han1me.X99.putSetting("x99.play.keepScreen", if (it) "true" else "false")
                },
            )
        }
        HplaySectionGroup("说明") {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "内嵌播放器支持拖动进度、倍速、全屏。" +
                        "点画质即切换；外部播放器在详情页「外部播放」里选。",
                    color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }
        }
    }
}

/** 下载设置页正文：并发 + 说明。 */
@Composable
fun DownloadSettingsContent() {
    val cs = MaterialTheme.colorScheme
    var conc by remember { mutableStateOf(com.han1me.X99.setting("dl.concurrency", "16")) }

    Column {
        HplaySectionGroup("并发分片") {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "同时下载的分片数。默认 16 —— 实测这是本机带宽与设备解码的上限；" +
                        "再高会互相抢带宽，总速度反而下降。",
                    color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("8", "16", "24").forEach { v ->
                        HplayChip(
                            text = v + " 路",
                            selected = conc == v,
                            onClick = {
                                conc = v
                                com.han1me.X99.putSetting("dl.concurrency", v)
                            },
                        )
                    }
                }
            }
        }
        HplaySectionGroup("说明") {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "支持断点续传；后台下载走前台服务，息屏也不会断。" +
                        "保存目录在「本地下载」页里可改。",
                    color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }
        }
    }
}

/** 关于页正文。 */
@Composable
fun AboutSettingsContent() {
    val cs = MaterialTheme.colorScheme
    Column {
        HplaySectionGroup("版本") {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Hplay 实验版",
                    color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "基于上游 v1.1.69 重构 · MD3 界面",
                    color = cs.onSurfaceVariant, fontSize = 11.sp,
                )
            }
        }
        // ★ 2026-11-25（第 304 轮）：检查更新从用户页移到这里（用户要求）。
        HplaySectionGroup("更新") {
            HplaySettingRow(
                icon = "🔄",
                title = "检查更新",
                // ★ 第 331 轮：副标题反映**当前实际走的渠道**与发布线。
                // 写死「从网盘取最新版」已经不对了 —— 用户可能选的是 GitHub 或 CI。
                subtitle = HplayUpdateChannel.channelLabel(HplayUpdateChannel.channel) +
                    " · " + HplayUpdateChannel.lineLabel(HplayUpdateChannel.line),
                // UpdateChecker 的公开入口是 UI 层的 HplayUpdateLayer，
                // 这里只置标记，由外层弹出更新面板（与用户页原来的行为一致）
                onClick = { com.han1me.ui.HplayUpdateState.request() },
            )
        }
        HplaySectionGroup("技术说明") {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "· UI 参照 Han1meViewer（Kotlin/MVVM）与 Han1mePlus（Flutter/MD3）\n" +
                        "· Cloudflare 质询：OkHttp 拦截器 + WebView 验证页 + UA 统一\n" +
                        "· 内置节点：本机 SOCKS，仅本应用生效\n" +
                        "· 三次元板块：xChina / Qinav / ganxiaban / 36co / ininav",
                    color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 18.sp,
                )
            }
        }
    }
}
