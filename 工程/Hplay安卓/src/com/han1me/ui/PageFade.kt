package com.han1me.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * 页面淡入 + 位移动画（第 56 轮起；第 339 轮重做；**第 348 轮按参考源码再校准**）。
 *
 * ## 为什么不用 Crossfade / AnimatedContent
 *
 * 那类容器需要在源码里成对插入大括号；我们的补丁机制只做「原文替换」，
 * 只加开不加闭会把后面整块吞进新作用域（实测 26 条编译错误；第 335 轮又踩了一次，
 * 那次把整个 tab 树吞进了 sites 分支）。
 * 所以做成**单行 Modifier 扩展**：调用点只改一行，不产生新的大括号。
 *
 * ## 第 348 轮改了什么
 *
 * 上一版是「alpha 0.62->1（200ms）+ 上移 8dp->0（220ms）」。
 * 对着两个参考源码核了一遍，发现两处不一致：
 *
 * 1. **有位移，但它们都没有**。
 *    Viewer 的 AnimatedLazy（列表项）明确只做 alpha + scale，不做 translation ——
 *    它注释里写的原因是对的：位移会和滚动方向抢视觉，用户分不清"是我在滑"还是"页面在动"。
 *    Plus 连 alpha 都没有，是瞬切。
 *    本轮把位移去掉，改成 Viewer 那套 **alpha + scale(0.985->1)**：
 *    位移为 0，观感是"内容在原位轻轻落定"，比"从下往上飘"干净得多，也少一个属性要合成。
 *
 * 2. **时长不对**。
 *    Viewer 是 alpha 100ms / scale 180ms。上一版 200/220ms，慢了近一倍，
 *    高刷屏上反而显得拖。本轮照抄 100/180。
 *
 * ## 省电
 *
 *  · 全程 graphicsLayer（alpha + scaleX/Y 都是合成层属性），不触发重排重绘；
 *  · 时长过 HplayMotion.ms()：系统关掉动画时长为 0，一帧不画；
 *  · 动画期把窗口帧率提到面板上限，结束立刻交还（见 HplayFrameRate）。
 *
 * @param key 视图标识（board/tab/mode 拼起来）。它一变就重放一次淡入。
 */
@Composable
fun Modifier.pageFade(key: Any): Modifier {
    val ctx = LocalContext.current
    val act = remember(ctx) { com.han1me.player.HplayOrientation.activityOf(ctx) }
    var settled by remember(key) { androidx.compose.runtime.mutableStateOf(false) }
    val a = remember { Animatable(1f) }
    val s = remember { Animatable(1f) }
    LaunchedEffect(key) {
        // ★★★ 第 349 轮（gfxinfo 实测 50th 29ms，掉帧）：**动画值为 1 时不要开图层**。
        //
        // 上一版无条件返回 this.graphicsLayer{...}，于是整块内容（含整个 LazyGrid）
        // 永远挂在一个 RenderNode 上 —— 即使动画早跑完、alpha 恒为 1。
        // 那一层会让列表的每张卡片都先合成到离屏层再贴回来，
        // 纯滚动时 GPU 时间就从 8ms 涨到 13ms（预算 16.67ms）。
        //
        // 现在用 done 标记：动画跑完把图层整个摘掉。done 只翻转一次 → 只重组一次。
        HplayFrameRate.beginAnimation(act)
        a.snapTo(0f)                       // 照 Viewer：从**全透明**起，不是 0.62
        s.snapTo(HplayMotion.ITEM_SCALE_FROM)
        try {
            // 两条同时跑：alpha 快、scale 慢（Viewer 就是这么配的，100 / 180）
            kotlinx.coroutines.coroutineScope {
                launch {
                    a.animateTo(
                        1f,
                        tween(HplayMotion.ms(HplayMotion.ITEM_ALPHA), easing = LinearOutSlowInEasing),
                    )
                }
                launch {
                    s.animateTo(
                        1f,
                        tween(HplayMotion.ms(HplayMotion.ITEM_SCALE), easing = FastOutSlowInEasing),
                    )
                }
            }
        } finally {
            HplayFrameRate.endAnimation(act)
            settled = true          // 图层摘掉：之后这块内容就是普通的直接绘制
        }
    }
    if (settled) return this
    // ★★★ 第 349 轮（用户要求「不掉帧」）：**不能在组合期读动画值**。
    //
    // 上一版写的是 val v = a.value; val sc = s.value; 然后 graphicsLayer { alpha = v } ——
    // 这句话看着无害，实际上 a.value 是在**组合阶段**读的：
    // 每帧一次 state 读取 → 本作用域每帧重组 → 整棵内容子树每帧重组。
    // 60fps 下等于每秒 60 次重组，120fps 下 120 次 —— 这就是"切页面卡一下"的来源。
    //
    // 正解：把读取留在 graphicsLayer 的 lambda 里。那个 lambda 由**绘制阶段**调用，
    // 每帧只重绘一个图层，跳过组合与布局（Compose 官方的 defer state reads）。
    return this.graphicsLayer {
        alpha = a.value
        val sc = s.value
        scaleX = sc
        scaleY = sc
    }
}