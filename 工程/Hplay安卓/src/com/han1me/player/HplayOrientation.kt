package com.han1me.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * **横屏控制**（2026-11-25 第 305 轮，照 Han1meViewer 的 VideoRouteHostScreen）。
 *
 * ## Viewer 的做法（`ui/screen/video/VideoRouteHostScreen.kt` 第 284-301 行）
 *
 * ```kotlin
 * activity.requestedOrientation = if (controller.isPortraitVideo) {
 *     ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
 * } else {
 *     ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
 * }
 * // 退出时
 * activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
 * ```
 *
 * 要点：
 *  · 用 **SENSOR_** 系列而不是固定 LANDSCAPE —— 允许用户左右翻转
 *  · **竖屏视频**（宽<高）全屏时保持竖屏，不要硬转横屏（否则画面更小）
 *  · 退出时恢复 UNSPECIFIED，交还系统
 *
 * ## 为什么之前点了没反应
 *
 * `HplayEnv.toggleFullscreen()` 只改了 Compose 层的 isFullscreen 标记，
 * **没有调用 Activity.requestedOrientation** —— 系统根本不知道要转屏。
 */
object HplayOrientation {

    /** 从 Context 里挖出 Activity（Compose 的 LocalContext 常常是 ContextWrapper）。 */
    fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return c as? Activity
    }

    /** 当前是否已经是横屏。 */
    fun isLandscape(ctx: Context): Boolean =
        ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * 进入全屏：按视频朝向决定横屏还是竖屏。
     *
     * @param portraitVideo 视频是竖屏（宽<高）—— 竖屏视频全屏时保持竖屏
     */
    fun enterFullscreen(ctx: Context, portraitVideo: Boolean) {
        val act = activityOf(ctx) ?: return
        act.requestedOrientation = if (portraitVideo) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        com.han1me.lab.LabLog.i(
            "player.orient",
            "进入全屏 " + (if (portraitVideo) "竖屏" else "横屏"),
        )
    }

    /** 退出全屏：交还系统。 */
    fun exitFullscreen(ctx: Context) {
        val act = activityOf(ctx) ?: return
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        com.han1me.lab.LabLog.i("player.orient", "退出全屏，方向交还系统")
    }

    /** 切换。 */
    fun toggle(ctx: Context, portraitVideo: Boolean, currentlyFullscreen: Boolean) {
        if (currentlyFullscreen) exitFullscreen(ctx) else enterFullscreen(ctx, portraitVideo)
    }
}
