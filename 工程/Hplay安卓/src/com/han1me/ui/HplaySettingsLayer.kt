package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/**
 * **设置入口的全局状态**（2026-11-25 第 278 轮）。
 *
 * 为什么用全局单例而不是在 `Han1meApp.kt` 里加状态：
 *
 * 那个文件是补丁从上游生成的 2400+ 行单文件，往里加东西要么改补丁锚点
 * （这次已经因为锚点误伤文件三次），要么改上游源码（不允许）。
 * 用 `mutableStateOf` 单例做跨 Composable 通信，是最低风险的做法 ——
 * 宿主只需在 `MainActivity` 里套一层 [HplaySettingsLayer]，
 * 任何地方（用户页的设置行）写 `HplaySettingsState.open = true` 就能打开。
 */
object HplaySettingsState {

    /** 是否正在显示设置页。 */
    var open by mutableStateOf(false)

    /** 直接打开某一页（内部导航起始页）。 */
    var startPage by mutableStateOf(SettingsPage.ROOT)

    /** 打开设置主页。 */
    fun show() {
        startPage = SettingsPage.ROOT
        open = true
    }
}

/**
 * 设置页的**覆盖层容器**：挂在 App 根部，`open` 为真时全屏盖住主界面。
 *
 * 这样做的额外好处：设置页不依赖主界面的导航状态，
 * 从任何屏幕（主页 / 详情 / 用户）打开都能正常返回原处。
 */
@Composable
fun HplaySettingsLayer(
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content()
        if (HplaySettingsState.open) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    // 吃掉点击，避免穿透到下层
                    .clickable(enabled = false) {},
            ) {
                HplaySettingsHost(
                    onClose = { HplaySettingsState.open = false },
                    networkExtra = {
                        com.han1me.lab.NodeSection(com.han1me.HplayEnv.requireContext())
                    },
                    // ★ 2026-11-25（第 322 轮）：旧的 BackupRestoreSection 已删除 ——
                    // 它只写应用私有目录，换设备拿不到，等于没接线。
                    // 现在备份恢复完全由 lab/HplayBackup + BackupAccountSettings 接管
                    // （系统文件选择器 + 兼容主线旧备份），所以这里不再注入额外面板。
                    dataExtra = null,
                    // ★ 2026-11-25（第 286 轮）：边缘节点面板（用户反馈缺失）
                    edgeExtra = {
                        com.han1me.lab.EdgeSection(com.han1me.HplayEnv.requireContext())
                    },
                )
            }
        }
    }
}
