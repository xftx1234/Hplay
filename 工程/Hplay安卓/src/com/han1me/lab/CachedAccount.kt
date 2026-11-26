package com.han1me.lab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * **云端账户缓存**（2026-11-25 第 308 轮；第 322 轮加磁盘预热）。
 *
 * 解决的问题：云端头像/昵称的抓取是**异步**的（在 LabInit 的后台线程里），
 * 而 Compose 的 LaunchedEffect 时序不稳定 —— 实测抓取根本没跑。
 *
 * 做法：抓取结果放进这个全局 Compose 状态，UI 直接读；
 * 抓取完成后写状态 -> 自动触发重组 -> 头像出现。
 *
 * ## 第 322 轮：磁盘预热
 *
 * 冷启动时网络还没通（要先等节点），如果这期间头像位是空的，
 * 用户看到的就是「每次启动都要等几秒才有头像」。
 * 现在 [warmFromDisk] 在启动瞬间就把上次抓到的 URL 塞进来 ——
 * 图片本身有 Coil/本地图片缓存，直接就能显示，网络刷新在后面悄悄进行。
 */
object CachedAccount {

    /** 抓到的账户信息；null = 还没抓到或未登录。 */
    var info by mutableStateOf<CloudAccount.Info?>(null)
        private set

    fun set(v: CloudAccount.Info) {
        info = v
    }

    fun clear() {
        info = null
    }

    /**
     * 启动时用磁盘缓存预热（毫秒级，可在主线程调）。
     *
     * 只在内存里还没有值时才覆盖 —— 后台线程可能已经抓到更新的了。
     */
    fun warmFromDisk() {
        if (info != null) return
        val c = runCatching { CloudAccount.cached() }.getOrNull() ?: return
        if (c.avatarUrl.isBlank() && c.userName.isBlank()) return
        info = c
        LabLog.i("account", "磁盘预热头像 " + (if (c.avatarUrl.isBlank()) "无" else "有"))
    }

    /** 头像 URL（空串表示没有）。 */
    val avatarUrl: String get() = info?.avatarUrl.orEmpty()

    /** 昵称（空串表示没有）。 */
    val userName: String get() = info?.userName.orEmpty()
}
