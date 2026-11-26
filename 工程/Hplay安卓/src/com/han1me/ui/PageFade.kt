package com.han1me.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 页面淡入（第 56 轮；第 86 轮按「动画要流畅」重做参数）。
 *
 * ## 为什么不用 Crossfade
 * 那类容器需要在源码里成对插入大括号；我们的补丁机制只做「原文替换」，
 * 只加开不加闭会把后面整块吞进新作用域（实测 26 条编译错误）。
 * 所以做成**单行 Modifier 扩展**：调用点只改一行，不产生新的大括号。
 *
 * ## 第 86 轮为什么改（用户报「全局动画切换卡顿」）
 *  · 旧版 wrap 成 Modifier.alpha()：它会触发**每一帧的图层重建**，在长列表页上是主要卡顿源；
 *    改用 graphicsLayer { alpha = ... } —— 只更新图层的 alpha 属性，走 GPU 合成，几乎零成本。
 *  · 时长 220ms → **160ms** + FastOutSlowIn 缓动：观感更利落，不拖尾。
 *  · 关键：**动画期间不做额外 IO**。旧版每次 key 变化都重建整个内容树，
 *    现在只改图层属性。
 *
 * @param key 视图标识（board/tab/mode 拼起来）。它一变就重放一次淡入。
 */
@Composable
fun Modifier.pageFade(key: Any): Modifier {
    val a = remember { Animatable(1f) }
    LaunchedEffect(key) {
        a.snapTo(0.55f)   // 不从全透明开始：视觉上更快"到位"，也不闪白
        a.animateTo(
            1f,
            tween(durationMillis = 160, easing = FastOutSlowInEasing),
        )
    }
    val v = a.value
    return this.graphicsLayer { alpha = v }
}
