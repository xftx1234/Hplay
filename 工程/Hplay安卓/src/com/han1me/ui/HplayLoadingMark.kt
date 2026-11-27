package com.han1me.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **加载小图标**（第 349 轮，用户要求「所有页面的加载图标从转圈改成如图」，图片做圆角）。
 *
 * ## 形态
 *
 * 用户给的那张角色图（370×320）当加载图标用，圆角裁切，**小幅度跳动**。
 * 原图放在 assets 里（`hplay_chibi.webp`）：本工程手搓管线不给 app 包生成 R 类
 * （`build.py` 的 `gen_r()` 只给被 jar 引用到的库命名空间生成，刻意排除 PACKAGE），
 * 所以 **不能用 R.drawable**，只能走 assets。
 *
 * ## 为什么这套动画不掉帧（三条硬约束）
 *
 * 1. **动画值只在 draw 阶段读**。
 *    `rememberInfiniteTransition` 返回的是 `State<Float>`。如果写成
 *    `val hop by transition.animateFloat(...)` 再在组合体里用，就是**每帧重组**整棵子树。
 *    这里刻意用 `val hop = ...` 拿 State 本身，只在 `graphicsLayer { }` 的 lambda 里读
 *    `hop.value` —— 那个 lambda 由绘制阶段调用（LayerDrawScope），
 *    所以每帧只**重绘一个图层**，不重组、不重新测量、不重新布局。
 *    这是 Compose 官方说的 "defer state reads to the draw phase"。
 *
 * 2. **只动合成层属性**（translationY / scaleX / scaleY）。这三个属性由 RenderNode
 *    直接消费，走 GPU 合成，连 draw 指令列表都不用重建。
 *    对比：改 `Modifier.offset(dp)` 会走 layout 阶段，每帧重新测量 —— 那才是掉帧源头。
 *
 * 3. **一次只跑两条动画**（位移 + 挤压），且都挂在同一个 `rememberInfiniteTransition` 上。
 *    参考 Han1meViewer 的 `SittingCatLoadingAnimation`（1 条）与 `WalkingCatAnimation`
 *    （2 条）—— 它的数量就是这个量级，再多就会和列表滚动抢帧。
 *
 * ## 省不省电
 *
 * 用户本轮明确「不考虑耗电，要不掉帧」。所以这里做了两件平时不会做的事：
 *  · 动画期间把窗口 preferredRefreshRate 抬到面板上限（`HplayFrameRate.pushLoop`），
 *    让 120Hz 屏真的跑 120 帧；
 *  · 跳动不停顿（`RepeatMode.Restart` 循环），落地后立刻起跳，视觉上是"活着"的。
 *
 * ## 曲线（为什么是这个形状）
 *
 * ```
 *   0ms   起跳
 * 230ms   到最高点（LinearOutSlowIn：起步快、到顶柔，像真的起跳）
 * 300ms   顶点悬停一瞬（滞空感，没有它就像弹簧）
 * 640ms   落回地面（FastOutLinearIn：越落越快，符合重力）
 * 660ms   落地挤压（scaleY 1→0.94、scaleX 1→1.05）
 * 800ms   挤压回弹
 * 980ms   一个循环结束，立刻下一跳
 * ```
 */
object HplayLoadArt {

    /** 原图宽高（370×320）。 */
    const val SRC_W = 370f
    const val SRC_H = 320f

    /**
     * **头部特写裁切窗**（原图像素坐标）。
     *
     * 为什么裁：原图左侧 1/3 是竖排大字「合法ストーキングです」，整张缩到 26-34dp 后
     * 那排字只是一条模糊色块，脸被挤到只剩十几像素 —— 认不出是谁，加载图标就白做了。
     * 取脸为中心的 180×180 方窗（x 165-345 / y 40-220）—— 这是把候选窗口做成带圆角的
     * 对照图逐个看过之后定的：两只眼睛都在框内、嘴落右下缘、头发占上三分之一，
     * 且完全不含左侧竖排字。试过的其它五组（115-315 / 150-348 / 160-340 / 170-350 / 178-352）
     * 分别是"残留字边"或"脸偏上/偏左"。
     * 又保持 1:1 的圆角方块形状（外面 `clip` 的圆角才不会把脸切掉）。
     *
     * 想换回整图就把 [CROP] 设成 null。
     */
    val CROP: IntArray = intArrayOf(165, 40, 345, 220)   // left, top, right, bottom

    @Volatile
    private var cached: ImageBitmap? = null

    @Volatile
    private var tried = false

    /** 读一次并缓存。解码失败返回 null，调用方给纯色兜底（不崩、不留白）。 */
    fun get(ctx: Context): ImageBitmap? {
        cached?.let { return it }
        if (tried) return null
        synchronized(this) {
            cached?.let { return it }
            if (tried) return null
            tried = true
            cached = runCatching {
                ctx.applicationContext.assets.open("hplay_chibi.webp").use { ins ->
                    BitmapFactory.decodeStream(ins)?.asImageBitmap()
                }
            }.getOrNull()
            com.han1me.lab.LabLog.i(
                "loadart",
                if (cached == null) "加载图标解码失败（用纯色兜底）" else "加载图标就绪 " + cached!!.width + "x" + cached!!.height,
            )
            return cached
        }
    }
}

/**
 * 加载图标。
 *
 * @param size 图标高度。宽度按原图比例自动算（370:320），不拉伸变形。
 */
@Composable
fun HplayLoadMark(
    markSize: Dp = 26.dp,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val img = remember { HplayLoadArt.get(ctx) }
    val tr = rememberInfiniteTransition(label = "loadmark")

    // ① 起跳 / 落地。注意：**不要**用 by 解构，那只会在组合期读值 → 每帧重组。
    val hop = tr.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 980
                0f at 0
                -1f at 230 using LinearOutSlowInEasing
                -1f at 300
                0f at 640 using androidx.compose.animation.core.FastOutLinearInEasing
                0f at 980
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "hop",
    )

    // ② 落地挤压。与 ① 同一个 transition，相位对齐（同一时长、同一 Restart）。
    val squash = tr.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 980
                0f at 0
                0f at 630
                1f at 690 using LinearEasing
                0f at 800 using LinearOutSlowInEasing
                0f at 980
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "squash",
    )

    // 跳的时候把窗口帧率抬到面板上限；离开组合（加载结束）立刻交还。
    DisposableEffect(Unit) {
        HplayFrameRate.pushLoop()
        onDispose { HplayFrameRate.popLoop() }
    }

    Box(
        modifier
            .width(markSize)
            .height(markSize)
            // clip 在 graphicsLayer 之前：圆角随整体一起跳，不是"跳出框外再被裁"
            .clip(RoundedCornerShape(markSize * 0.22f))
            .graphicsLayer {
                // ★ 全部 state 读在这里 —— 只在 draw 阶段发生，不触发重组
                translationY = hop.value * 5.dp.toPx()
                val s = squash.value
                scaleX = 1f + s * 0.05f
                scaleY = 1f - s * 0.06f
            },
    ) {
        if (img != null) {
            val crop = HplayLoadArt.CROP
            if (crop.isEmpty()) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 单次 drawImage 带 srcRect：源图只解码一次，裁切在 GPU 采样阶段完成，
                // 不额外分配位图（Bitmap.createBitmap 会多占一份像素内存）。
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        image = img,
                        srcOffset = androidx.compose.ui.unit.IntOffset(crop[0], crop[1]),
                        srcSize = androidx.compose.ui.unit.IntSize(crop[2] - crop[0], crop[3] - crop[1]),
                        // 这里的 size 是 DrawScope.size（composable 参数已改名 markSize，
                        // 否则会被同名局部变量遮蔽 —— 第一次编译就是栽在这个遮蔽上）
                        dstSize = androidx.compose.ui.unit.IntSize(
                            this.size.width.toInt().coerceAtLeast(1),
                            this.size.height.toInt().coerceAtLeast(1),
                        ),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    )
                }
            }
        } else {
            // 兜底：一张同尺寸的圆角色块，仍然在跳（不至于"没有加载反馈"）
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.35f + 0.35f * (1f + hop.value) },
            )
        }
    }
}

/** 居中大号版（整页加载态用，替换原来的居中转圈）。 */
@Composable
fun HplayLoadingMark(
    modifier: Modifier = Modifier,
    markSize: Dp = 34.dp,
) {
    Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        HplayLoadMark(markSize = markSize)
    }
}