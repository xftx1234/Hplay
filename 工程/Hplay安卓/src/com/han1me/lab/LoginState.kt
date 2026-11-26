package com.han1me.lab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * **登录态广播**（2026-11-25 第 303 轮）。
 *
 * 解决的问题：用户在 WebView 里登录成功后返回，主界面**仍显示「未登录」**。
 *
 * 原因：LoginActivity 是独立 Activity，它拿到 cookie 后只灌进了 OkHttp，
 * 但 Compose 侧完全不知道 —— `user` / `profile` 这两个 mutableStateOf
 * 还是旧值，界面不会重组。
 *
 * 做法：一个全局的 Compose 状态计数器。
 *   · LoginActivity 登录成功 -> [notifyChanged] 让它 +1
 *   · Han1meApp 用 LaunchedEffect(LoginState.tick) 监听，变化就重新拉用户信息
 *
 * 为什么不用广播/EventBus：这是单进程内的 UI 刷新，Compose 快照状态最直接，
 * 而且天然参与重组，不需要手动反注册。
 */
object LoginState {

    /** 登录态变更计数器。每次变更 +1。 */
    var tick by mutableStateOf(0)
        private set

    /** 最近一次登录的 cookie（便于调试与备份）。 */
    var lastCookie by mutableStateOf("")
        private set

    /**
     * **正在加载云端账户**（2026-11-25 第 320 轮）。
     *
     * 用户要求：「网页登录之后用户页和其他相关版面瞬间加载出云端内容，
     * 延迟不超过 2 秒。可以在这 2 秒中间加入正在加载态，但不允许它一直停留。」
     *
     * 语义：
     *   · true  = 已登录但云端信息还没到（UI 显示「正在加载账户…」）
     *   · false = 要么没登录，要么已经加载完（含失败）
     *
     * **必须有超时**：网络卡住时不能让加载态无限停留。
     */
    var loadingAccount by mutableStateOf(false)
        private set

    fun beginLoadingAccount() {
        loadingAccount = true
    }

    fun endLoadingAccount() {
        loadingAccount = false
    }

    /** 通知：登录态变了。 */
    fun notifyChanged(cookie: String = "") {
        lastCookie = cookie
        tick++
        LabLog.i("login.state", "登录态变更 tick=" + tick)
    }

    /** 是否有可用的登录态（从持久化里读）。 */
    fun hasLogin(): Boolean = CfCookieBridge.savedLogin().isNotBlank()

    /**
     * 从持久化里同步一次（跨 Activity 后强制读磁盘）。
     *
     * ★ 2026-11-25（第 303 轮）：LoginActivity 里 tick++ 之后，
     * 主 Activity 的 Compose 快照不一定会重组 —— 两个 Activity 各自持有一份
     * 组合树，跨 Activity 的状态传播依赖主线程消息队列的时序，实测不可靠。
     *
     * 所以这里再提供一个"强制同步"入口：主界面 onResume 时调用它，
     * 读磁盘判断登录态是否变了，变了就 tick++ 触发重组。
     */
    fun syncFromDisk() {
        val saved = CfCookieBridge.savedLogin()
        val nowLogged = saved.isNotBlank()
        val wasLogged = lastCookie.isNotBlank()
        if (nowLogged && !wasLogged) {
            lastCookie = saved
            tick++
            LabLog.i("login.state", "onResume 检测到登录态，tick=" + tick)
        }
    }
}
