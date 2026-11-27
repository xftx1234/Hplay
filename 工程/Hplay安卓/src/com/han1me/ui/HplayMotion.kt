package com.han1me.ui

import android.content.Context
import android.provider.Settings

/**
 * **全局动效参数（第 348 轮）** —— 两个参考源码的数值在这里集中一份。
 *
 * 为什么必须集中：两个参考源码都是**就地写死字面量**（Viewer 39 处 tween、Plus 14 处 Duration），
 * 想校准只能满仓库改。这里把它们抄成常量，改一处全站生效。
 *
 * ## 数值出处（逐条对着源码抄的，别凭感觉改）
 *
 * | 场景 | 数值 | 出处 |
 * |---|---|---|
 * | 页面转场 进入滑动 + 淡入 | 450ms FastOutSlowInEasing | Viewer MainNavHost.kt:422-425 |
 * | 页面转场 退出滑动（1/3 宽） | 450ms FastOutSlowInEasing | Viewer MainNavHost.kt:426-429 |
 * | 页面转场 退出缩放 | 1 - 0.9（默认 spring，不写 spec） | Viewer MainNavHost.kt:430 |
 * | 页面转场 退出淡出 | 300ms | Viewer MainNavHost.kt:430 |
 * | 返回 进入缩放 | 0.9 - 1（默认 spring） | Viewer MainNavHost.kt:486 |
 * | 列表项 淡入 | alpha 0-1，100ms | Viewer AnimatedLazy.kt:383-387 |
 * | 列表项 放大 | scale 0.985-1，180ms | Viewer AnimatedLazy.kt:388-392 |
 * | 列表项 阶梯延迟 | min(index,6) x 12ms（上限 72ms） | Viewer AnimatedLazy.kt:376-379 |
 * | 控件显隐淡入淡出 | 200ms | Viewer 默认 spring(400f) 约等于 200ms；Plus 是瞬切 |
 * | 轻提示进出 | 220ms + 从下方整高滑入 | Viewer GlobalToast.kt:296-303（默认 spring） |
 *
 * ## 省电的两条硬约束
 *
 * 1. **尊重系统的「动画程序时长缩放」**。开发人员选项里把动画调成 0（无障碍"移除动画"），
 *    这里就让所有时长变 0 —— 动画瞬间到位、一帧不多画。Viewer **没做**这件事；
 *    我们显式做，既守无障碍约定也真省电。
 * 2. **只动合成层属性**（alpha / translation / scale）。全程走 graphicsLayer，
 *    不触发重新测量与布局 —— 这是「流畅」与「省电」能同时成立的前提。
 *    反例就是 Plus 的 AnimatedSize（200/180ms 高度补间），每帧都要重排，它自己
 *    在 AnimatedLazy 里都写明「数据到达后每一帧重排 - 掉帧」。
 */
object HplayMotion {

    // ---------------- 页面转场（Viewer MainNavHost） ----------------

    /**
     * 进入：滑动 + 淡入的时长。
     *
     * ★★ 第 350 轮：**260ms → 回到 450ms**（第 349 轮那次缩短是错的）。
     *
     * 第 349 轮为了省帧把它压到 260ms，结果用户实测"比之前还卡顿"——
     * 因为那一轮同时砍掉了旧页退场与新页淡入，只剩一条纯滑动；
     * 260ms 的纯滑动在视觉上是"跳一下"，FastOutSlowIn 的曲线还没铺开就结束了。
     *
     * 现在整套回到 Viewer（MainNavHost.kt:422-430）的 450ms，参数一个不改。
     * 用户本轮明确「不考虑功耗，要跟 viewer 一模一样」。
     */
    const val NAV_IN = 450

    /** 退出滑动时长（与进入同长，Viewer 就是同一个 450）。 */
    const val NAV_SLIDE_OUT = 450

    /** 退出淡出时长。 */
    const val NAV_FADE_OUT = 300

    /** 旧页退出时缩到的比例。 */
    const val NAV_SCALE_OUT = 0.9f

    /** 返回时新页的起始缩放。 */
    const val NAV_SCALE_IN = 0.9f

    /** 旧页退出只走 1/3 宽（不是整屏），营造"压在下面"。 */
    const val NAV_EXIT_DIV = 3

    /** 返回时新页从 1/3 处滑入。 */
    const val NAV_ENTER_DIV = 3

    // ---------------- 列表项入场（Viewer AnimatedLazy） ----------------

    const val ITEM_ALPHA = 100
    const val ITEM_SCALE = 180
    const val ITEM_SCALE_FROM = 0.985f
    const val ITEM_STAGGER_MS = 12
    const val ITEM_STAGGER_MAX = 6

    // ---------------- 轻提示（Viewer GlobalToast） ----------------

    const val TOAST_IN = 220
    const val TOAST_OUT = 180
    const val TOAST_HOLD_MS = 3500L
    const val TOAST_CORNER_PCT = 50
    const val TOAST_BG_ALPHA = 0.8f

    // ---------------- 其它 ----------------

    /** 播放控件淡入淡出。 */
    const val CONTROL_FADE = 200

    /** 详情浮层（与页面转场同口径）。 */
    const val DETAIL_IN = 420
    const val DETAIL_OUT = 300

    // ---------------- 系统动画缩放 ----------------

    @Volatile
    private var scale = 1f

    @Volatile
    private var inited = false

    /** 读系统动画缩放（幂等，毫秒级；onCreate 调）。 */
    fun init(ctx: Context) {
        if (inited) return
        inited = true
        runCatching { refresh(ctx) }
        com.han1me.lab.LabLog.i("motion", "系统动画缩放 = " + scale + (if (scale <= 0f) "（已关闭动画）" else ""))
    }

    /** 从设置里重读（应用从后台回来时也可能被改）。 */
    fun refresh(ctx: Context) {
        val s = runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        scale = if (s.isNaN() || s < 0f) 1f else s
        inited = true
    }

    /** 是否应当播动画。系统关掉动画时为 false。 */
    fun enabled(): Boolean = scale > 0.01f

    /**
     * 按系统缩放折算时长。系统关闭动画时返回 0（tween(0) 即瞬到）。
     *
     * 注意：**不要**在这里对 0 做 coerceAtLeast(1) —— 那会让"移除动画"变成
     * "1 毫秒闪一下"，既违背无障碍设置又多画一帧。
     */
    fun ms(base: Int): Int = if (!enabled()) 0 else (base * scale).toInt()
}