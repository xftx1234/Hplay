package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **设置路由容器**（2026-11-25 第 278 轮）。
 *
 * 只做一件事：管理「设置主页 → 各二级页」的栈内导航，
 * 让宿主那边只需要一行 `HplaySettingsHost(onClose = …)` 就能挂上整套设置。
 *
 * 为什么单独一个文件：
 *  主界面 `Han1meApp.kt` 是从上游 AvPlay 补丁生成的 2400+ 行单文件，
 *  往里面塞多个新屏幕会显著抬高维护成本与补丁脆弱度。
 *  这里用独立 Composable 承接，宿主只加一行。
 */
@Composable
fun HplaySettingsHost(
    onClose: () -> Unit,
    /** 注入「网络」页里的原有复杂面板（节点 / 边缘节点 / 代理设置）。 */
    networkExtra: (@Composable () -> Unit)? = null,
    /** 注入「数据」页里的原有面板（备份恢复）。 */
    dataExtra: (@Composable () -> Unit)? = null,
    /** 注入「网络」页的**边缘节点**面板（与内置节点并列，两个都在）。 */
    edgeExtra: (@Composable () -> Unit)? = null,
) {
    var page by remember { mutableStateOf(SettingsPage.ROOT) }

    fun back() {
        if (page == SettingsPage.ROOT) onClose() else page = SettingsPage.ROOT
    }

    // ★★★ 2026-11-25（第 306 轮 · 用户报「设置页面手势安卓导航用不了，只能用左上角返回」）：
    //
    // 根因：设置页是**独立覆盖层**，不在 NavHost 里，所以系统返回手势
    // （Android 10+ 的侧滑 / 返回键）落不到它身上 —— 直接退出了 App 或者被
    // 下层吃掉。用户只能点左上角那个 ← 按钮。
    //
    // 修法：挂 BackHandler。它在返回事件冒泡到系统之前拦截，
    // 按「子页 -> 根页 -> 关闭」三级顺序处理，与左上角按钮行为完全一致。
    androidx.activity.compose.BackHandler(enabled = true) {
        back()
    }

    // 子页切换加转场（照 Viewer 的 MainNavHost 规格）
    androidx.compose.animation.AnimatedContent(
        targetState = page,
        transitionSpec = {
            // 进子页用 enter，回根页用 pop
            if (targetState == SettingsPage.ROOT) {
                com.han1me.ui.HplayTransition.pop
            } else {
                com.han1me.ui.HplayTransition.enter
            }
        },
        label = "settings-page",
    ) { p ->
    when (p) {
        SettingsPage.ROOT -> SettingsHomeScreen(
            onOpen = { page = it },
            onClose = onClose,
        )
        SettingsPage.APPEARANCE -> AppearanceSettingsScreen(onBack = ::back)
        SettingsPage.NETWORK -> SettingsScaffold("网络", ::back) {
            // ★★★ 第 352 轮（用户要求「设置 网络里加一项 javchu 路由开关」）：**放在最上面**。
            //
            // 它是本页唯一一个「影响具体站点能否播放」的开关，比节点列表更需要先看到。
            // 默认关（= 直连）：实测 t33.cdn2020.com 走内置节点一律 451，只有直连能播。
            item {
                val javchuProxy = remember {
                    mutableStateOf(
                        com.han1me.X99.setting(com.han1me.lab.GlobalProxy.JAVCHU_ROUTE_KEY, "direct") == "proxy",
                    )
                }
                HplaySectionGroup("javchu 路由") {
                    HplaySwitchRow(
                        title = "javchu 走内置节点",
                        checked = javchuProxy.value,
                        subtitle = "关闭（默认）= 直连。当前节点下 javchu 的媒体 CDN 对外国 IP 回 451，",
                        icon = "🎬",
                        onCheckedChange = { on ->
                            javchuProxy.value = on
                            com.han1me.X99.putSetting(
                                com.han1me.lab.GlobalProxy.JAVCHU_ROUTE_KEY,
                                if (on) "proxy" else "direct",
                            )
                            com.han1me.lab.LabLog.i("proxy", "javchu 路由 -> " + if (on) "proxy" else "direct")
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "改完立即生效（每个请求都按当前开关实时选路），不需要重启或重连节点。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            // ★ 2026-11-25（第 286 轮）：**边缘节点板块补回**。
            // 用户反馈「设置-网络里没有边缘节点板块，只有内置节点」——
            // 重构设置页时只注入了 NodeSection，漏了 EdgeSection。
            // 两者是不同能力：内置节点是 SOCKS 隧道，边缘节点是直连优选 IP。
            item { networkExtra?.invoke() }
            item {
                if (edgeExtra != null) {
                    Spacer(Modifier.height(14.dp))
                    edgeExtra.invoke()
                }
            }
            item {
                HplaySectionGroup("说明") {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "内置节点走本地 SOCKS，仅本应用生效，不改系统设置。" +
                                "官方源建议开启；三次元板块可单独设为直连。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
        SettingsPage.PLAYBACK -> SettingsScaffold("播放", ::back) {
            item { PlaybackSettingsContent() }
            item {
                HplaySectionGroup("播放器") {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "内嵌播放器支持拖动进度、倍速、全屏。" +
                                "点画质即可切换；外部播放器在详情页「外部播放」按钮里选。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
        SettingsPage.DOWNLOAD -> SettingsScaffold("下载", ::back) {
            item { DownloadSettingsContent() }
            item {
                HplaySectionGroup("下载设置") {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "并发分片与保存目录在「本地下载」页里调整。" +
                                "支持断点续传；后台下载走前台服务，息屏也不会断。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
        SettingsPage.DATA -> SettingsScaffold("数据", ::back) {
            // ★ 2026-11-25（第 319 轮）：**备份与多账户 UI 挂到这里**。
            // 组件早就建好了（BackupAccountSettings / AccountStore / HplayBackup），
            // 一直没挂到设置页 —— 用户点「数据」只看到旧的备份恢复面板。
            item {
                BackupAccountSettings(onToast = { })
            }
            item { dataExtra?.invoke() }
            // ★ 2026-11-25（第 323 轮 · 审查修复）：**平板模式开关**。
            // HplayTabletSection 同为 0 处引用的死组件 —— 用户第 76 轮明确要求
            // 「平板模式按钮放在设置里」，重构设置页时丢了。
            item {
                com.han1me.tablet.HplayTabletSection()
            }
            // ★★★ 第 334 轮（用户要求「数据板块没有日志，只有备份恢复」）：
            // **运行日志面板**。上一版 AppLog 的开关是设置键 x99.log.enabled，
            // 但全项目没有任何 UI 能改它 —— 日志系统写好了却永远打不开。
            // 这一块就是那个缺失的入口：开关 / 过滤 / 实时查看 / 导出 / 清空。
            item {
                com.han1me.ui.HplayLogSection()
            }
            item {
                HplaySectionGroup("存储") {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "收藏与历史存在本机；备份会把它们打包成 zip，" +
                                "换设备可一键恢复。已下载的视频不在备份内。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
        SettingsPage.PRIVACY -> SettingsScaffold("隐私", ::back) {
            // ★★★ 2026-11-25（第 323 轮 · 审查修复）：
            // 这一页原来只有一段说明文字，**没有任何可操作的开关** ——
            // 隐私锁真正的开关组件（lock/PrivacyLock.kt 的 PrivacyLockRow）
            // 全项目 0 处引用，等于「隐私锁」这个功能在设置里根本打不开。
            // 现在把真正的开关挂上。
            item {
                HplaySectionGroup("隐私锁") {
                    Column(Modifier.padding(14.dp)) {
                        com.han1me.lock.PrivacyLockRow()
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "开启后每次冷启动都会先验证设备锁屏密码。" +
                                "设备没设密码时无法验证，会给出「直接进入」入口。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
        SettingsPage.ABOUT -> SettingsScaffold("关于", ::back) {
            // ★ 2026-11-25（第 306 轮）：改成 Viewer 的键值表样式（用户截图那张），
            // 并把「检查更新」放进来（从用户页移过来的）。
            item {
                AboutPageViewerStyle(
                    onCheckUpdate = { com.han1me.ui.HplayUpdateState.request() },
                )
            }
            // ★ 第 346 轮修（结构错误）：这里原本是
            //     if (false) { item { AboutSettingsContent() }
            //     item { ...版本信息... }
            // 少了一个右花括号 —— 于是「if (false)」把**下面整块版本信息**也吃进去了，
            // 大括号一路套到文件末尾（末尾那三行裸 `}` 就是欠账）。
            // 后果：设置页最下面的「版本」分区**从来不显示**，而编译器只会说大括号配平
            // （凑巧配上了），不会报错。
            // 现在：把死分支显式注释掉，版本信息正常渲染。
            // 删掉的只是一行 if：AboutSettingsContent 已被 AboutPageViewerStyle 取代。
            item {
                HplaySectionGroup("版本") {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            // ★ 第 346 轮：**版本号从构建注入读**，不再硬编码。
                            // 原来写死 "v1.1.69-refactor"，从 1.1.69 一路显示到 1.1.79 都没变过。
                            "Hplay 实验版 · v" + runCatching {
                                com.han1me.update.HplayBuildInfo.versionName()
                            }.getOrDefault("?"),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "基于上游 AvPlay 重构。UI 参照 Han1meViewer 与 Han1mePlus " +
                                "（Material Design 3）。CF 质询由 OkHttp 拦截器 + WebView 验证页处理。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
    }
}
