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
    LaunchedEffect(Unit) {
        val clamped = index.coerceIn(0, HplayMotion.ITEM_STAGGER_MAX)
        val d = clamped * HplayMotion.ITEM_STAGGER_MS
        if (d > 0) delay(d.toLong())
        visible = true
    }
    val a by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = HplayMotion.ms(HplayMotion.ITEM_ALPHA)),
        label = "item-alpha",
    )
    val s by animateFloatAsState(
        targetValue = if (visible) 1f else HplayMotion.ITEM_SCALE_FROM,
        animationSpec = tween(durationMillis = HplayMotion.ms(HplayMotion.ITEM_SCALE)),
        label = "item-scale",
    )
    return this.graphicsLayer {
        alpha = a
        scaleX = s
        scaleY = s
    }
}
