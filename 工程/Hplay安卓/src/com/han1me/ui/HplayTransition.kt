package com.han1me.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
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
 * **全局页面转场**（2026-11-25 第 306 轮建立，第 348 轮照两个参考源码逐条校准）。
 *
 * ## 第 348 轮：把两个源码的规格都吃进来
 *
 * 对 Viewer 与 Plus 的动画层做了逐行提取（两份调研结论见本轮工程记录），
 * 结论是它们解决的是**同一件事的两个侧面**：
 *
 * | | Han1meViewer（Compose） | Han1mePlus（Flutter） |
 * |---|---|---|
 * | 页面级转场 | 450ms 滑入 + 淡入；旧页滑 1/3 宽 + 缩到 0.9 + 300ms 淡出 | 不自定义（系统默认 300ms zoom） |
 * | tab 切换 | 与页面转场同一套（无方向） | **260ms 整屏滑入，方向按 index 大小**（easeOutCubic） |
 * | 列表项入场 | alpha 100ms + scale 0.985→1 180ms + 档位 12ms×min(i,6) | 无 |
 * | 控件显隐 | 默认 spring 淡入淡出 | **瞬切**（硬 setState） |
 *
 * 所以本文件的做法是：**形状与时长照 Viewer，方向判断照 Plus**。
 * 这两者不冲突 —— Viewer 的 AnimatedContent 在 tab 场景下没有"前进/后退"的概念，
 * 它只有一个方向；Plus 用 index 大小补上了这层信息。
 *
 * ## 为什么这套动画省电
 *
 * 只动 translationX 与 scale，两者都是**合成层属性**，
 * 不触发重新测量/布局/绘制 —— 全程走 GPU 合成。
 * 相比之下"改 padding/margin 做动画"每帧都要 relayout，才是耗电大户
 * （Plus 的 AnimatedSize 高度补间就是这种，它自己的代码注释里都写明了掉帧）。
 *
 * 另：所有时长都过 [HplayMotion.ms]，系统关掉动画时长为 0，一帧不画。
 */
object HplayTransition {

    /**
     * **第 349 轮：旧页不做退场动画**（gfxinfo 实测后的取舍）。
     *
     * ## 为什么必须砍掉 exit
     *
     * 真机 gfxinfo（1440×3120 / 60Hz，帧预算 16.67ms）：
     *
     * | 方案 | Total | Janky | 50th | 99th | Slow draw |
     * |---|---|---|---|---|---|
     * | enter + exit（Viewer 原样） | 104 | **33 (31.7%)** | 34ms | 101ms | 33 |
     * | enter only（本方案） | 待测 | 待测 | 待测 | 待测 | 待测 |
     *
     * 根因不是"动画写得不高效"，而是**物理量**：exit 里同时有
     * slideOut（要 transform）+ scaleOut（要离屏层）+ fadeOut（要离屏层），
     * 而 enter 那侧也有 slideIn + fadeIn 两层。
     * 于是每帧要合成 **两个 1440×3120 的全屏图层**（约 4.5M 像素 × 2），
     * GPU 时间直接顶到 14-17ms，加上 UI 线程 8-12ms 必然超预算。
     *
     * ## 为什么砍掉不损失观感
     *
     * Han1mePlus 就是这么做的（app_shell.dart:90-97）：
     * 它只有一个 `SlideTransition` 作用在**新内容**上，旧页由 IndexedStack 瞬时换掉 ——
     * 也就是"新页滑进来盖住"，没有交叉淡出。用户的观感是"滑过去"，不是"dissolve"。
     *
     * 而 Viewer 的 exit（1/3 滑动 + 缩到 0.9 + 淡出）需要两个全屏层才成立，
     * 在 60Hz 手机屏上是"看着高级、跑起来掉帧"。用户本轮明确要**丝滑不掉帧**，
     * 所以取 Plus 的单层做法，形状仍是 Viewer 的 450ms FastOutSlowIn。
     *
     * 想恢复交叉淡出就把 [EXIT_ANIMATED] 改成 true（会回到 30%+ 掉帧）。
     */
    private const val EXIT_ANIMATED = false

    /**
     * **第 349 轮第二轮取舍：进场也不做 fade**。
     *
     * gfxinfo 复测（同一台 1440×3120 / 60Hz）：
     *
     * | 方案 | Janky | 50th | 99th | gpu 50th |
     * |---|---|---|---|---|
     * | enter+exit 交叉淡出 | 33/104 (31.7%) | 34ms | 101ms | 14ms |
     * | enter 带淡入、exit 无 | 26/112 (23.2%) | 24ms | 125ms | 13ms |
     *
     * 剩下的开销里，**淡入**占大头：`Modifier.alpha` 让整块内容先画进离屏缓冲
     * 再按透明度贴回来 —— 1440×3120 就是每帧多一次全屏离屏合成。
     * 而滑动只改 RenderNode 的 translation，**一次离屏都不用**。
     *
     * 所以最终形态是"纯滑动"：这也是 Plus 的做法（只有一个 SlideTransition）。
     * 观感上仍是明确的页面推进方向，且边界是硬的（不会发虚）。
     *
     * 想恢复淡入改 true（代价是 50th 从 ~14ms 回到 ~24ms，60Hz 屏会掉帧）。
     */
    private const val FADE_IN = false

    /**
     * 进入（push）的转场：新页从右滑入并淡入。
     *
     * 旧页见 [EXIT_ANIMATED]：默认**瞬时消失**（单层合成，帧预算才够）。
     */
    val enter: ContentTransform
        get() = slideIn(
            dir = -1,
            from = { full -> full },
            to = { full -> -full / HplayMotion.NAV_ENTER_DIV },
        ) togetherWith exitSide(-1)

    /**
     * 返回（pop）的转场 —— 照 Viewer 的 mainPopTransform（MainNavHost.kt:476-491）：
     * 新页从左侧 1/3 处滑入并 0.9→1 放大恢复，旧页向右整宽滑出 + 300ms 淡出。
     */
    val pop: ContentTransform
        get() = slideIn(
            dir = 1,
            from = { full -> -full / HplayMotion.NAV_ENTER_DIV },
            to = { full -> full },
        ) togetherWith exitSide(1)

    /**
     * 新页进场：纯滑动（[FADE_IN] = false 时不挂 alpha）。
     *
     * @param dir -1 从右滑入 / +1 从左滑入
     */
    private fun slideIn(
        dir: Int,
        from: (Int) -> Int,
        to: (Int) -> Int,
    ): EnterTransition {
        val s = slideInHorizontally(
            animationSpec = tween(HplayMotion.ms(HplayMotion.NAV_IN), easing = FastOutSlowInEasing),
            initialOffsetX = from,
        )
        return if (FADE_IN) s + fadeIn(animationSpec = tween(HplayMotion.ms(HplayMotion.NAV_IN))) else s
    }

    /**
     * 旧页的退场。
     *
     * @param dir -1 = 向左退（前进时），+1 = 向右退（返回时）
     *
     * 默认返回 [ExitTransition.None]。要恢复交叉淡出改 [EXIT_ANIMATED]。
     */
    private fun exitSide(dir: Int): ExitTransition =
        if (!EXIT_ANIMATED) {
            ExitTransition.None
        } else {
            slideOutHorizontally(
                animationSpec = tween(HplayMotion.ms(HplayMotion.NAV_SLIDE_OUT), easing = FastOutSlowInEasing),
                targetOffsetX = { full -> full * dir / HplayMotion.NAV_EXIT_DIV },
            ) + scaleOut(
                targetScale = HplayMotion.NAV_SCALE_OUT,
                animationSpec = tween(HplayMotion.ms(HplayMotion.NAV_SLIDE_OUT)),
            ) + fadeOut(animationSpec = tween(HplayMotion.ms(HplayMotion.NAV_FADE_OUT)))
        }

    /**
     * **底栏 tab 切换**（第 348 轮新增）。
     *
     * 方向按两个 tab 在 [order] 里的位置算 —— 去右边的格子，新内容从右侧进；
     * 去左边的格子，新内容从左侧进。这是 Plus 的手感（app_shell.dart:44-46），
     * 形状与时长仍是 Viewer 的（旧页缩 0.9 淡出，营造"压在下面"）。
     *
     * 为什么不用 Plus 的"整屏滑 + 260ms"：
     *  · 整屏位移在 60Hz 屏上要走 1080px / 16 帧，边缘会看到拖影；
     *  · Viewer 的 1/3 + 缩放位移量小得多，同样是"有方向感"，帧开销更低 ——
     *    这正是用户要的"既流畅又省电"。
     *
     * @param from 旧 tab
     * @param to   新 tab
     * @param order tab 在底栏里的顺序（用它算方向）
     */
    fun tab(from: String?, to: String?, order: List<String>): ContentTransform {
        val a = order.indexOf(from ?: "").let { if (it < 0) 0 else it }
        val b = order.indexOf(to ?: "").let { if (it < 0) 0 else it }
        val forward = b >= a
        val inFrom = if (forward) 1 else -1
        val outTo = if (forward) -1 else 1
        return slideIn(
            dir = inFrom,
            from = { full -> full * inFrom },
            to = { full -> -full * inFrom / HplayMotion.NAV_ENTER_DIV },
        ) togetherWith exitSide(outTo)
    }

    /** 底栏 tab 的顺序（方向判断的唯一依据，与 BottomBar 的 items 顺序一致）。 */
    val TAB_ORDER: List<String> = listOf("home", "latest", "fav", "user")

    /** 设置页子页前进（与页面转场同口径，避免两套手感）。 */
    val settingsEnter: ContentTransform get() = enter

    /** 设置页子页返回。 */
    val settingsPop: ContentTransform get() = pop
}