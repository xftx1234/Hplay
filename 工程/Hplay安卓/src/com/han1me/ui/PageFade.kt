package com.han1me.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * 页面淡入 + 位移动画（第 56 轮起；第 339 轮重做，用户要求「优化全局应用流畅转场动画」）。
 *
 * ## 为什么不用 Crossfade / AnimatedContent
 *
 * 那类容器需要在源码里成对插入大括号；我们的补丁机制只做「原文替换」，
 * 只加开不加闭会把后面整块吞进新作用域（实测 26 条编译错误；第 335 轮又踩了一次，
 * 那次把整个 tab 树吞进了 sites 分支）。
 * 所以做成**单行 Modifier 扩展**：调用点只改一行，不产生新的大括号。
 *
 * ## 第 339 轮改了什么（用户报「转场动画要更流畅」）
 *
 * 旧版只有 alpha 0.55 -> 1，160ms。三个问题：
 *  · 纯淡入**没有方向感**，切换看起来是平的，用户感觉不到层级变化；
 *  · 160ms 在高刷屏上只有 9~19 帧，加速曲线来不及铺开就被截断；
 *  · 每帧重建 alpha 图层（旧版用 Modifier.alpha）。
 *
 * 现在：
 *  · **alpha + 轻微上位移**（8dp -> 0）：有方向感但仍克制，符合 Material 3 的
 *    emphasized decelerate 语义，不会像滑动转场那样抢注意力；
 *  · 220ms + LinearOutSlowIn（前快后慢）：到位更快，尾巴更柔，
 *    比 FastOutSlowIn 更适合内容出现这类一次性动画；
 *  · 全程 graphicsLayer（GPU 合成，不重建图层）；
 *  · 动画期把窗口帧率提到面板上限（见 HplayFrameRate），结束后交还系统。
 *
 * @param key 视图标识（board/tab/mode 拼起来）。它一变就重放一次淡入。
 */
@Composable
fun Modifier.pageFade(key: Any): Modifier {
    val ctx = LocalContext.current
    val act = remember(ctx) { com.han1me.player.HplayOrientation.activityOf(ctx) }
    val a = remember { Animatable(1f) }
    val ty = remember { Animatable(0f) }
    LaunchedEffect(key) {
        // 动画期间用面板上限帧率；结束立刻交还（省电，不常驻高刷）
        HplayFrameRate.beginAnimation(act)
        a.snapTo(0.62f)      // 不从全透明起：视觉上更快到位，也不闪白
        ty.snapTo(8f)
        try {
            // 两条同时跑：alpha 稍快收尾，位移稍慢铺开，观感更自然
            kotlinx.coroutines.coroutineScope {
                launch { a.animateTo(1f, tween(200, easing = LinearOutSlowInEasing)) }
                launch { ty.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
            }
        } finally {
            HplayFrameRate.endAnimation(act)
        }
    }
    val v = a.value
    val offset = ty.value
    return this.graphicsLayer {
        alpha = v
        translationY = offset
    }
}