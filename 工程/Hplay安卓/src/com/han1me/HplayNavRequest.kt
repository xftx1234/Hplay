package com.han1me

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * **跨入口导航请求**（第 352 轮）。
 *
 * ## 为什么需要它
 *
 * 用户报「通知栏点击下载中的通知不跳转正在下载页」。根因不是 Compose 侧，而是**两侧都没接线**：
 * · MainActivity 没有 `onNewIntent` —— 点通知只能把 Activity 拉到前台，收不到「要开哪一页」；
 * · 通知的 PendingIntent 是一个光秃秃的 Intent，没带任何标识，就算有 onNewIntent 也无从判断。
 *
 * 现在用这个 object 做**单向信箱**：Activity 侧写入，Compose 侧读走并清空。
 *
 * ## 为什么不是 StateFlow
 *
 * 请求是**一次性**的（处理完就该消失），StateFlow 会保留最后一个值 —— 用户切个 tab 回来
 * 又会被弹一次下载页。用 `mutableStateOf<String?>(null)` + 消费即置空，语义正好。
 */
object HplayNavRequest {

    /** Intent extra 名。 */
    const val EXTRA_OPEN = "hplay.open"

    /** 打开「本地下载」面板。 */
    const val OPEN_DOWNLOADS = "downloads"

    /** 待处理的请求；null = 没有。**只在主线程读写**（Intent 回调与组合都在主线程）。 */
    var pending by mutableStateOf<String?>(null)
        private set

    fun request(what: String?) {
        pending = what?.takeIf { it.isNotBlank() }
    }

    /** 取走（消费后自动清空，避免重复触发）。 */
    fun consume(): String? {
        val v = pending
        pending = null
        return v
    }
}