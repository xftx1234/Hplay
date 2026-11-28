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

/** 网络设置页正文：javchu 路由 + 内置节点 + 说明。 */
@Composable
fun NetworkSettingsContent() {
    val cs = MaterialTheme.colorScheme
    // ★★ 第 352 轮（用户要求「设置 网络里加一项 javchu 路由开关，可切换走直连还是代理」）：
    //
    // 默认 **proxy**（走节点）。javchu.com 是 Cloudflare 前置，直连必被 RST ——
    // 设备日志实证：19 条 `Connection reset`，内核里一条 `accepted tcp:javchu.com:443` 都没有。
    // 媒体域（cdn2020.com / tutu1.space）是另一套判据、**永远直连**，不受本开关影响。
    //
    // ★ 第 356 轮：本函数当前是死代码（网络页渲染走 SettingsHost），
    // 但默认值必须与 GlobalProxy.javchuDirect() 保持一致，否则将来一旦被接回去就会再犯同一个错。
    var javchuProxy by remember {
        mutableStateOf(com.han1me.X99.setting(com.han1me.lab.GlobalProxy.JAVCHU_ROUTE_KEY, "proxy") != "direct")
    }
    Column {
        HplaySectionGroup("javchu 路由") {
            HplaySwitchRow(
                title = "javchu 走内置节点",
                checked = javchuProxy,
                subtitle = "开启（默认）= 走内置节点。javchu 页面站在 Cloudflare 后，直连必被 RST；" +
                    "媒体 CDN 不受本开关影响（永远直连）。",
                icon = "🌐",
                onCheckedChange = { on ->
                    javchuProxy = on
                    com.han1me.X99.putSetting(
                        com.han1me.lab.GlobalProxy.JAVCHU_ROUTE_KEY,
                        if (on) "proxy" else "direct",
                    )
                    // 让下一次请求立刻按新路由走（GlobalProxy 的选择是每请求实时判定的，无需重启）
                    com.han1me.lab.LabLog.i("proxy", "javchu 路由 -> " + (if (on) "proxy" else "direct"))
                },
            )
        }
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
                    "同时下载的分片数。默认 32。\n\n" +
                        "多线程 CDN 常见「每连接限速」（实测 t33 每连接约 0.11 MB/s），" +
                        "所以总速度 ≈ 连接数 × 单连接速度：16 路≈1.7、32 路≈3、64 路≈5~6 MB/s。\n\n" +
                        "调太高（96 以上）会被连接建立开销吃掉，且手机内存/线程压力大。",
                    color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(10.dp))
                // ★ 第 352 轮：档位按实测重排。旧档位 8/16/24 的「16」是当上限设计的，
                // 而 16 路在 t33 上只有 1.75 MB/s；实测 32→2.9~3.4、64→4.8~6.4、96→7.2。
                // 现在给 16/32/48/64 四档（默认 32），让用户能真的调到自己的带宽上限。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("16", "32", "48", "64").forEach { v ->
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