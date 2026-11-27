package com.han1me

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * Hplay Android 版入口：与桌面版 Main.kt 的差别在于
 *  - 窗口/无边框/窗口键由系统承担（HplayWindow 一族不适用，相关代码已从上游补丁中移除）；
 *  - 无需宿主 jar 的 PlaybackSession（桌面内嵌播放器的要求），Android 版播放器自带会话。
 */
class MainActivity : ComponentActivity() {
    /**
     * ★★★ 2026-11-26（第 338 轮 · 用户报「全屏播放用返回手势后停在横屏」）：
     *
     * 兜底回竖屏。播放器的横屏是 Activity.requestedOrientation = SENSOR_LANDSCAPE 设的，
     * 系统不会因为"内容没了"就自己转回来 —— 安卓返回手势又常常绕过播放器的
     * DisposableEffect（Compose 树在销毁路径上被拆，onDispose 未必跑到）。
     *
     * 这里只做一件事：**只要不是播放器锁着方向，就强制恢复系统默认**。
     * 用 HplayOrientation.lockedBy() 判断，所以不会干扰：
     *   · 播放器正在全屏（owner = "player"）
     *   · 平板模式（走 HplayEnv 的另一条通道，不经过本对象）
     */
    private fun restoreOrientationIfNeeded() {
        runCatching {
            if (com.han1me.player.HplayOrientation.lockedBy() != null) return@runCatching
            // ★★★ 第 345 轮：**播放器给了自动朝向基线时不抢方向**。
            //
            // 上面那条 lockedBy 只覆盖「全屏锁定」这一种情况。自动朝向（非全屏下按
            // 视频朝向吸附）不设 owner —— 于是 onResume 会把刚吸好的方向打回
            // UNSPECIFIED：从系统对话框 / 通知栏回来、切后台再回来，画面又翻回竖屏。
            // 基线存在本身就说明「有活着的播放器在管方向」，这里直接让位。
            if (com.han1me.player.HplayOrientation.hasAuto()) return@runCatching
            if (requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return@runCatching
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            com.han1me.lab.LabLog.i("orient", "onResume 兜底：方向已交还系统")
        }
    }

    override fun onResume() {
        super.onResume()
        restoreOrientationIfNeeded()
        // ★ 2026-11-25（第 303 轮）：从 WebView 登录页返回时强制同步登录态。
        //
        // LoginActivity 与 MainActivity 各自持有独立 Compose 组合树，
        // 跨 Activity 的 mutableStateOf 传播依赖主线程消息队列时序，实测不可靠 ——
        // 用户登录成功返回后主界面仍显示「未登录」。
        // onResume 必然在返回时触发，这里主动读磁盘比对。
        runCatching { com.han1me.lab.LoginState.syncFromDisk() }
        // ★ 第 348 轮：用户可能去开发者选项把动画关掉了再回来 —— 重读一次。
        runCatching { com.han1me.ui.HplayMotion.refresh(this) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HplayEnv.init(this, this)
        // ★ 第 339 轮（用户要求「默认最高帧率可根据系统自动调节」）：探测面板上限，
        // 只在动画期间提升，静止时交还系统（见 ui/HplayFrameRate.kt 的三层说明）。
        com.han1me.ui.HplayFrameRate.init(this)
        // ★★ LABKEEP：恢复用户素材（封面/头像），防止换版本后丢失
        runCatching { HplayEnv.restoreSlots() }
        // ★★ 实验分叉（2026-10-30）：初始化内置节点（节点链接/开关/自动恢复）。
        // 这是实验版对主线文件的**唯一一处**运行时改动，便于日后合并。
        com.han1me.lab.LabInit.initOnce(this, HplayEnv.dataDir())
        // ★ 第 348 轮：动画缩放读取放在 LabInit 之后 —— 那里 LabLog 才 attach，
        // 否则这一行日志会丢（HplayFrameRate 的日志就一直是丢的）。
        com.han1me.ui.HplayMotion.init(this)
        // 第 46 轮「外部播放器」：本机下载条目点这个键时交给系统播放器。
        // 走 FileProvider 的 content://（见 HplayEnv.playExternalFile 的注释）；
        // 调不起来就明确提示，不让用户点了没反应。
        com.han1me.download.HplayDownloads.onPlayExternal = { path ->
            if (!HplayEnv.playExternalFile(path)) {
                HplayEnv.toast("没有可用的外部播放器，或文件已不存在")
            }
        }
        askNotificationPermission()
        window.setBackgroundDrawableResource(android.R.color.black)
        setContent {
            // ★★★ 2026-11-24（第 277 轮 · UI 重构）：套上 MD3 主题。
            //
            // 这层的意义：此前所有界面颜色都是硬编码十六进制（BgColor 之类），
            // 想换肤要改动几十处、明暗模式也无法整体切换。
            // 现在 HplayTheme 提供整套 Material 3 语义色，并支持
            // 9 种种子色 / 明暗 / AMOLED 三个开关（HplayThemeStore 持久化）。
            //
            // 注意：Han1meApp 内部仍有大量遗留硬编码色 —— 主题只是**开始生效**，
            // 逐屏改用 MaterialTheme.colorScheme.* 是后续每屏迭代的工作。
            // 这里先保证：新增界面一律走主题色，旧界面在改造到的屏幕上同步替换。
            com.han1me.ui.HplayTheme {
                // ★★★ 2026-11-25（第 311 轮 · 用户报「壁纸只在一半/有的页面缺」）：
                //
                // 原来背景层挂在 HplaySettingsLayer 内部的 Box 里，
                // 只覆盖 Han1meApp 那一层 —— 详情浮层、播放页、搜索页
                // 都是后来叠上去的，各自带不透明底，于是背景被盖住。
                //
                // 现在提到**最外层**：所有页面（含覆盖层）都叠在背景之上。
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize(),
                ) {
                    com.han1me.ui.HplayBackgroundLayer()
                    com.han1me.lock.PrivacyLockGate {
                    // ★★★ 2026-11-25（第 313 轮）：**更新面板提到这一层**。
                    //
                    // 之前它挂在 Han1meApp 内部，而设置页是这一层的独立覆盖层 ——
                    // 两个组合树之间的状态传播时序不可靠，点「检查更新」常常没反应。
                    // 现在更新面板与设置页是**兄弟节点**，直接共享 HplayUpdateState。
                    // 2026-11-25（第 313 轮）：轮询兜底。
                    // 设置页与 MainActivity 是两个独立 ComposeView，
                    // 各有自己的 Recomposer —— 跨视图的 mutableStateOf 传播不可靠。
                    // 这里每 200ms 主动检查一次，变化就触发本层重组。
                    // ★★★ 第 344 轮（全量核查 · P1）：**删掉 200ms 轮询**。
                    //
                    // 原来这里每 200ms 醒一次主线程探一个布尔量，理由是「跨 ComposeView 传播不可靠」。
                    // 但 HplayUpdatePanel 与这一层是**同一个 setContent 组合树**（见下面几行），
                    // 不存在跨 Recomposer 问题 —— 直接读 mutableState 就会正常重组。
                    // 而这条循环换来的是：应用在前台期间每秒 5 次主线程唤醒，
                    // 待机功耗与首屏帧率都受影响。
                    // ★★★ 2026-11-25（第 278 轮 UI 重构）：设置页覆盖层。
                    //
                    // 为什么挂在这里而不是塞进 Han1meApp.kt：
                    //  那是补丁从上游生成的 2400+ 行单文件，改它要动补丁锚点
                    //  （本轮已因锚点误伤三次）。挂在这一层零风险，
                    //  且从任何屏幕都能打开设置、返回后还在原处。
                    com.han1me.ui.HplaySettingsLayer {
                        // 背景层已提到最外层（第 311 轮），这里只放内容
                        Box(Modifier.fillMaxSize()) {
                            Han1meApp()
                        }
                    }
                    if (com.han1me.ui.HplayUpdateState.visible) {
                        // ★ 第 344 轮：**更新面板要有自己的返回键**。
                        // 原来整个 update 包都没注册 BackHandler —— 面板开着按返回会冒泡到
                        // Han1meApp 的根 handler，直接弹「退出确认」，面板反而不关。
                        androidx.activity.compose.BackHandler {
                            com.han1me.ui.HplayUpdateState.consume()
                        }
                        com.han1me.update.HplayUpdatePanel(
                            onClose = { com.han1me.ui.HplayUpdateState.consume() },
                        )
                    }
                    // ★ 第 332 轮：**引用与致谢全屏页**。
                    //
                    // 与更新面板同级（都在设置页之外这一层），所以它能盖住设置页，
                    // 系统返回也能正常退出（下面有 BackHandler）。
                    // 关于页里那一行「引用与致谢」只置 showCredits = true。
                    if (com.han1me.ui.HplayAboutState.showCredits) {
                        androidx.activity.compose.BackHandler {
                            com.han1me.ui.HplayAboutState.showCredits = false
                        }
                        com.han1me.ui.AboutCreditsPage(
                            onClose = { com.han1me.ui.HplayAboutState.showCredits = false },
                        )
                    }
                    // ★ 第 331 轮：**开屏自动检查更新弹窗**。
                    //
                    // 之前 HplayUpdatePopup 这个 composable 已经写好但**没有任何调用点**
                    // （全项目 grep 只有定义处），等于「开屏提示新版本」这个功能是死的。
                    // 用户这轮要求「设置应用内下载更新弹窗」，所以把它接上：
                    //   ① 冷启动后延迟 1.5s 查一次（别和首屏抢带宽）；
                    //   ② 只在「本线渠道」上查（用户选的渠道），
                    //      且尊重 update.autoCheck 开关；
                    //   ③ 查到有新版才弹，没新版/查询失败都**不弹** ——
                    //      开屏弹一个「检查失败」是最讨人厌的设计。
                    com.han1me.update.HplayStartupUpdate()

                }
                }
            }
        }
    }

    /**
     * 第 48 轮：Android 13+ 的通知权限。
     *
     * 不给也**不影响下载继续跑**（前台服务本身不依赖通知权限），只是用户看不到进度通知。
     * 所以这里异步要一次即可，被拒也不重复打扰、更不阻断任何功能。
     */
    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        runCatching {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) return
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { }.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        HplayEnv.applyFullscreen(false)
        // ★★★ 第 169 轮（真机实测：App 停止后系统代理残留 127.0.0.1:10809）：
        //
        // root 增强会用 `settings put global http_proxy` 让整个系统走节点，
        // 这能救「WebView 网页播放不走节点」；但**一旦残留就会影响其它 App 上网** ——
        // 那正是用户明确禁止的「损害系统」。
        //
        // 真机实测：force-stop 后 http_proxy 仍为 127.0.0.1:10809，说明
        // ShutdownHook 在 force-stop 时**不会执行**（SIGKILL 不给机会）。
        // 所以在 onDestroy 里也必须还原一次。
        runCatching { com.han1me.lab.HplayRootBoost.clearSystemProxy() }
        super.onDestroy()
    }
}