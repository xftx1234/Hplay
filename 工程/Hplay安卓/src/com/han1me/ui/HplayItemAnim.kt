package com.han1me.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * **列表项入场动画**（第 348 轮，照 Han1meViewer 的 AnimatedLazy.kt）。
 *
 * ## 抄来的规格（Viewer AnimatedLazy.kt:362-402，逐行核对过）
 *
 *    alpha  : 0f - 1f，tween(100ms)
 *    scale  : 0.985f - 1f，tween(180ms)
 *    stagger: min(index, 6) x 12ms（上限 72ms）
 *    位移   : **没有**（它刻意不做 translation，避免和滚动抢视觉）
 *
 * ## 为什么用 Modifier 扩展而不是包一层容器
 *
 * 本工程的补丁机制只做"原文替换"，只加开不加闭会把后面整块吞进新作用域
 * （见 PageFade.kt 顶部那段血泪）。所以做成单行 Modifier 扩展：
 * 调用点只在 SiteCard(...) 外面挂 .itemEnter(i)，不产生新的大括号。
 *
 * ## 省电
 *
 * 全部走 graphicsLayer（alpha + scale 都是合成层属性），不触发重排重绘。
 * 首次组合后**只跑一次**（LaunchedEffect(Unit)），列表滚动不会重放。
 * 系统关闭动画时 HplayMotion.ms() 返回 0，动画瞬间到位、一帧不多画。
 *
 * @param index 在列表里的下标（用来算阶梯延迟）。传 0 就是不做阶梯。
 */
@Composable
fun Modifier.itemEnter(index: Int): Modifier {
    var visible by remember { mutableStateOf(false) }
    // settled：动画彻底结束后置 true，把这个 RenderNode 摘掉（详见下方注释）。
    // 它只翻转**一次**，所以只触发一次重组 —— 不构成"每帧重组"。
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val clamped = index.coerceIn(0, HplayMotion.ITEM_STAGGER_MAX)
        val d = clamped * HplayMotion.ITEM_STAGGER_MS
        if (d > 0) delay(d.toLong())
        visible = true
        // 等两条动画都走完（scale 是最长的那条）再多留一帧余量
        delay(HplayMotion.ms(HplayMotion.ITEM_SCALE) + 32L)
        settled = true
    }
    // ★★★ 第 349 轮：**不用 by 解构**。
    //
    // animateFloatAsState 返回 State<Float>。写成 val a by ... 时，a 在任何地方被读
    // 就是在那个阶段读 state —— 而这里原来是在组合体里 val a = ...（by 委托的取值
    // 发生在使用点，但 graphicsLayer { alpha = a } 的 a 是**闭包捕获的值**，
    // 捕获发生在组合期）。于是每帧一次重组，列表滚动时几十项一起重组 → 掉帧。
    //
    // 现在保留 State 本身，读取只在 graphicsLayer 的 lambda 里发生（绘制阶段）。
    val a = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = HplayMotion.ms(HplayMotion.ITEM_ALPHA)),
        label = "item-alpha",
    )
    val s = animateFloatAsState(
        targetValue = if (visible) 1f else HplayMotion.ITEM_SCALE_FROM,
        animationSpec = tween(durationMillis = HplayMotion.ms(HplayMotion.ITEM_SCALE)),
        label = "item-scale",
    )
    // ★★★ 第 349 轮（gfxinfo 实测）：**动画结束后把图层摘掉**。
    //
    // graphicsLayer 每挂一次，Compose 就给这一项建一个 RenderNode。一个双栏网格首屏
    // 12-20 项 → 就是 12-20 个独立图层，滚动时每帧都要逐个合成。
    // 真机 gfxinfo 的 50th gpu percentile 在纯滚动下已经 13ms（预算 16.67ms），
    // 就是这些"常驻但已无用"的图层吃掉的。
    //
    // 动画跑完后 alpha=1、scale=1，这个图层再没有任何作用 —— 直接不挂。
    // 只在动画结束那一刻触发**一次**重组（不是每帧），换来的是列表静止时零图层。
    if (settled) return this
    return this.graphicsLayer {
        alpha = a.value
        val sc = s.value
        scaleX = sc
        scaleY = sc
    }
}