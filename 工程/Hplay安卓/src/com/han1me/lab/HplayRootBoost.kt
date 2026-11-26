package com.han1me.lab

/**
 * **已彻底移除（2026-11-03 第二次）**：本组件与 root 权限完全脱钩。
 *
 * 用户两次明确指示：
 *   第一次（2026-11-03 凌晨）：把 hplay 涉及 root 级别系统调用修改的组件都清除，
 *     网页代理也不要做了，hplay 内置老老实实走应用内就行
 *   第二次（同日早）：新版本仍然会请求 root 权限，没有清理干净，把 root 相关的所有组件全部清除
 *
 * 事故回顾：本组件曾用 root 写系统代理（settings put global http_proxy），
 * 残留导致两台手机全系统断网。第一次修复时错误地保留了清残留的 su 调用，
 * 这就是用户看到的「新版本仍请求 root」。现在连这个也移除：
 *
 *   - 不申请 root（不调用 su）
 *   - 不读不写任何 settings
 *   - 不执行任何外部命令
 *   - 不启动任何后台进程或看门狗
 *
 * 历史残留的系统代理由用户的设备通过 adb 清理
 * （adb shell settings delete global ...，shell uid 自带权限，与 App 无关）。
 * 本 App 从此对系统网络设置零接触。
 */
object HplayRootBoost {

    /** 恒 false：本组件不再具备任何 root 能力。 */
    fun isAvailable(): Boolean = false

    /** 空操作：不再设置系统代理。 */
    fun applySystemProxy(httpPort: Int) {
        appliedPort = -1
    }

    /** 空操作：残留清理改由用户经 adb 处理，本 App 不再触碰系统设置。 */
    fun clearSystemProxy() {
        appliedPort = -1
    }

    /** 恒 false：系统代理从未被本组件设置。 */
    fun active(): Boolean = false

    /** 保留字段形状，恒为 -1。 */
    private var appliedPort: Int = -1
}
