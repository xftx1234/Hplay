package com.han1me.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * **更新面板全局状态**（2026-11-25 第 304 轮）。
 *
 * 背景：检查更新入口从用户页移到了「设置 > 关于」，
 * 但设置页是独立覆盖层，拿不到 Han1meApp 里的 showUpdate 局部变量。
 *
 * 做法：与 HplaySettingsState 同款 —— 一个全局可变状态，
 * 设置页置 true，Han1meApp 用 LaunchedEffect 监听并打开更新面板。
 */
object HplayUpdateState {
    /**
     * 是否显示更新面板。
     *
     * ★ 2026-11-25（第 312 轮）：**改成直接控制面板可见性**。
     *
     * 之前是「requested 置位 -> Han1meApp 用 LaunchedEffect 监听 -> 转成局部 showUpdate」。
     * 实测 LaunchedEffect(requested) 在设置页这种**独立覆盖层**里点按钮时
     * 不一定会重跑 —— 两个 Compose 组合树的快照传播时序不可靠。
     *
     * 现在直接让 Han1meApp 读这个全局状态作为面板可见性，
     * 设置页点按钮改它 -> 全局快照变化 -> Han1meApp 重组 -> 面板出现。
     */
    var visible by mutableStateOf(false)
        private set

    /** 兼容旧调用点：等价于 visible。 */
    val requested: Boolean get() = visible

    /**
     * ★ 第 331 轮：**开屏自动检查是否已经跑过**（每次进程生命周期一次）。
     *
     * 为什么需要它：开屏检查挂在 MainActivity 最外层，那个 composable 会随重组
     * 反复进入 LaunchedEffect。不设标记就会反复发请求 —— 匿名 GitHub 只有
     * 60 次/小时，139 那边也有频率限制，几次重组就把额度耗光。
     *
     * 放这里而不是放 HplayStartupUpdate 内部的原因：Compose 的 remember 只在
     * 组合存活期间有效，而 MainActivity 的 Activity 重建（旋转、字体变化）会
     * 重新组合 —— 那时 remember 已清零，但仍属同一次进程启动。
     */
    var startupChecked: Boolean = false

    fun request() {
        visible = true
    }

    fun consume() {
        visible = false
    }
}
