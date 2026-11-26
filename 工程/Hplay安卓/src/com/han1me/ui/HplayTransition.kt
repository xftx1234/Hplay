package com.han1me.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * **全局页面转场**（2026-11-25 第 306 轮，照 Han1meViewer 的 MainNavHost）。
 *
 * ## Viewer 的规格（`ui/navigation/main/MainNavHost.kt` 第 413-435 行）
 *
 * ```
 * 进入：slideIntoContainer(Start) + fadeIn        450ms FastOutSlowInEasing
 * 退出：slideOutOfContainer(Start, offset=1/3) + scaleOut(0.9) + fadeOut  450/300ms
 * 返回：反向（新页面从左滑入并放大恢复，旧页面向右滑出）
 * ```
 *
 * 设计意图（它注释里写得很清楚）：
 *  · 新页从右侧滑入 + 淡入
 *  · 旧页向左轻微偏移（1/3 宽度）并缩小到 0.9、淡出
 *    —— 营造「压在下面」的层级感
 *
 * ## 为什么这套动画省电
 *
 * 只动 `translationX` 与 `scale`，两者都是**合成层属性**，
 * 不触发重新测量/布局/绘制 —— 全程走 GPU 合成，不烧 CPU。
 * 相比之下「改 padding/margin 做动画」每帧都要 relayout，才是耗电大户。
 *
 * 450ms 是 Viewer 实测的手感甜点：
 *  · 300ms 偏快，层级感出不来
 *  · 600ms 偏拖沓，用户会觉得卡
 */
object HplayTransition {

    private const val DUR_IN = 450
    private const val DUR_OUT = 300
    private const val DUR_EXIT_SLIDE = 450

    /** 进入（push）的转场。 */
    val enter: ContentTransform
        get() = (
            slideInHorizontally(
                animationSpec = tween(DUR_IN, easing = FastOutSlowInEasing),
                initialOffsetX = { full -> full },
            ) + fadeIn(animationSpec = tween(DUR_IN))
            ) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(DUR_EXIT_SLIDE, easing = FastOutSlowInEasing),
                targetOffsetX = { full -> -full / 3 },
            ) + scaleOut(targetScale = 0.9f, animationSpec = tween(DUR_EXIT_SLIDE))
                + fadeOut(animationSpec = tween(DUR_OUT))
            )

    /** 返回（pop）的转场 —— 方向与进入相反。 */
    val pop: ContentTransform
        get() = (
            slideInHorizontally(
                animationSpec = tween(DUR_IN, easing = FastOutSlowInEasing),
                initialOffsetX = { full -> -full / 3 },
            ) + fadeIn(animationSpec = tween(DUR_IN))
            ) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(DUR_EXIT_SLIDE, easing = FastOutSlowInEasing),
                targetOffsetX = { full -> full },
            ) + fadeOut(animationSpec = tween(DUR_OUT))
            )
}
