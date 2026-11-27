package com.han1me.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * **全局页面转场 —— 第 350 轮：逐字照搬 Han1meViewer**。
 *
 * ## 为什么推翻第 349 轮
 *
 * 第 349 轮按 gfxinfo 数据做了一轮"省帧"取舍：砍掉旧页退场（[ExitTransition.None]）、
 * 砍掉新页淡入，只留一条纯滑动 + 260ms。数字好看（janky 31.7% → 13.7%），
 * 但**观感更差** —— 用户实测原话："现在动画比之前还卡顿"。
 *
 * 原因不难理解：
 *  · 纯滑动没有交叉淡出，目标页在 `AnimatedContent` 换内容的那一刻是**硬切**的；
 *   页面内容还在异步加载（列表空/转圈），于是用户看到的是"一块空白被推着走"，
 *    观感上比淡入淡出更像卡顿。
 *  · 260ms 太短，`FastOutSlowIn` 的曲线还没铺开就结束，位移显得"跳"。
 *  · 旧页瞬时消失 ⇒ 背景（半透明 root）透出来一帧，闪。
 *
 * 所以本轮**完全回到 Viewer 的规格**（MainNavHost.kt:414-431 与 :476-491），
 * 一个参数都不改：
 *
 * | 动作 | 新页 | 旧页 |
 * |---|---|---|
 * | push | slideIn(Start, 整宽) 450ms FastOutSlowIn + fadeIn 450ms | slideOut(Start, 1/3 宽) 450ms + scaleOut(0.9) 默认 spring + fadeOut 300ms |
 * | pop  | slideIn(End, 从 1/3 宽处) 450ms + scaleIn(0.9) 默认 spring + fadeIn 450ms | slideOut(End, 整宽) 450ms + fadeOut 300ms |
 *
 * 用户本轮明确"不考虑功耗"，所以不再为帧预算做任何减法。
 *
 * ## tab 方向仍然保留（第 348 轮的需求，与 Viewer 不冲突）
 *
 * Viewer 的 `AnimatedContent` 没有"前进/后退"概念，只有一个方向；
 * 而底栏是四格并列，用户手指从左往右点，新页却从右边进来会觉得别扭。
 * 所以 [tab] 用 tab 在底栏里的次序决定滑动方向，**曲线/时长/缩放/淡出仍与 Viewer 完全一致**。
 */
object HplayTransition {

    /** 进入（push）：新页从右整宽滑入并淡入，旧页缩到 0.9 左移 1/3 淡出。 */
    val enter: ContentTransform
        get() = push(1)

    /** 返回（pop）—— 照 Viewer 的 mainPopTransform。 */
    val pop: ContentTransform
        get() = popTransform(1)

    /**
     * **底栏 tab 切换**（第 348 轮新增方向；形状/时长按第 350 轮回到 Viewer）。
     *
     * @param from 旧 tab
     * @param to   新 tab
     * @param order tab 在底栏里的顺序（用它算方向）
     */
    fun tab(from: String?, to: String?, order: List<String>): ContentTransform {
        val a = order.indexOf(from ?: "").let { if (it < 0) 0 else it }
        val b = order.indexOf(to ?: "").let { if (it < 0) 0 else it }
        // 去右边的格子：新页从右侧进来（dir=+1），旧页向左（-1/3）。反之亦然。
        return if (b >= a) push(1) else push(-1)
    }

    /**
     * push 转场。[dir] +1 = 新页从右侧来 / 旧页往左退；-1 = 反过来。
     *
     * Viewer 原样：
     *   slideIntoContainer(Start) 450 + fadeIn(450)
     *   togetherWith
     *   slideOutOfContainer(Start, targetOffset = it/3) 450 + scaleOut(0.9) + fadeOut(300)
     */
    private fun push(dir: Int): ContentTransform {
        val ms = HplayMotion.ms(HplayMotion.NAV_IN)
        val outMs = HplayMotion.ms(HplayMotion.NAV_SLIDE_OUT)
        return slideInHorizontally(
            animationSpec = tween(ms, easing = FastOutSlowInEasing),
            initialOffsetX = { full -> full * dir },
        ) + fadeIn(animationSpec = tween(ms)) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(outMs, easing = FastOutSlowInEasing),
                targetOffsetX = { full -> -full * dir / HplayMotion.NAV_EXIT_DIV },
            ) + scaleOut(targetScale = HplayMotion.NAV_SCALE_OUT) +
                fadeOut(animationSpec = tween(HplayMotion.ms(HplayMotion.NAV_FADE_OUT)))
            )
    }

    /**
     * pop 转场 —— 照 Viewer 的 `mainPopTransform`：
     *   slideIntoContainer(End, initialOffset = it/3) 450 + scaleIn(0.9) + fadeIn(450)
     *   togetherWith slideOutOfContainer(End) 450 + fadeOut(300)
     *
     * @param dir +1 = 旧页往右退、新页从左侧 1/3 处浮上来（Viewer 的原方向）
     */
    private fun popTransform(dir: Int): ContentTransform {
        val ms = HplayMotion.ms(HplayMotion.NAV_IN)
        val outMs = HplayMotion.ms(HplayMotion.NAV_SLIDE_OUT)
        return slideInHorizontally(
            animationSpec = tween(ms, easing = FastOutSlowInEasing),
            initialOffsetX = { full -> -full * dir / HplayMotion.NAV_ENTER_DIV },
        ) + scaleIn(initialScale = HplayMotion.NAV_SCALE_IN) + fadeIn(animationSpec = tween(ms)) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(outMs, easing = FastOutSlowInEasing),
                targetOffsetX = { full -> full * dir },
            ) + fadeOut(animationSpec = tween(HplayMotion.ms(HplayMotion.NAV_FADE_OUT)))
            )
    }

    /** 底栏 tab 的顺序（方向判断的唯一依据，与 BottomBar 的 items 顺序一致）。 */
    val TAB_ORDER: List<String> = listOf("home", "latest", "fav", "user")

    /** 设置页子页前进（与页面转场同口径，避免两套手感）。 */
    val settingsEnter: ContentTransform get() = enter

    /** 设置页子页返回。 */
    val settingsPop: ContentTransform get() = pop

    /** 详情/覆盖层用：无动画（由各自的 AnimatedVisibility 负责）。 */
    val none: ContentTransform get() = EnterTransition.None togetherWith ExitTransition.None
}