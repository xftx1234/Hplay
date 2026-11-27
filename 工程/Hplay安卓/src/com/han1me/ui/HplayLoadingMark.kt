package com.han1me.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **加载小图标**（第 349 轮引入，第 351 轮按用户要求改回原图）。
 *
 * ## 形态（第 351 轮，用户原话：「加载中的小动态图重构为原始图片，保留文字，图片不缩放，但保留圆角」）
 *
 * · **整图**：不再裁头部特写。上一版把 370×320 裁成脸部的 180×180 方窗，
 *   理由是「缩到 26-34dp 后竖排字只是一条色块」。用户明确要求用原始图片，
 *   那就整张显示 —— 裁切窗在 [CROP] 留着（空数组 = 不裁），要回退只改一行。
 * · **不缩放**：动画里**只有位移**（上下小幅跳动），`scaleX/scaleY` 恒为 1。
 *   上一版落地有一下挤压（scaleY 1→0.94、scaleX 1→1.05），用户要求去掉。
 * · **保留圆角**：`clip(RoundedCornerShape(size * 0.22))`，跟着整体一起跳。
 * · **保留文字**：整页加载态（[HplayLoadingMark]）在图标下方带「加载中…」，
 *   与参考实现 `LoadingContent.kt` 的「动画 + message」同构。
 *
 * ## 为什么这套动画不掉帧（三条硬约束，第 349 轮定的，仍然有效）
 *
 * 1. **动画值只在 draw 阶段读**：`animateFloat` 返回 `State<Float>`，这里刻意用 `val`
 *    拿 State 本身，只在 `graphicsLayer { }` 的 lambda 里读 `.value` ——
 *    那个 lambda 由绘制阶段调用，每帧只重绘一个图层，**不重组、不重新测量**。
 * 2. **只动合成层属性**（translationY）：RenderNode 直接消费，走 GPU 合成。
 *    对比 `Modifier.offset(dp)` 会走 layout 阶段每帧重新测量 —— 那才是掉帧源头。
 * 3. **一条动画**（只剩位移）：第 351 轮去掉挤压之后，每帧只更新一个 float。
 *
 * ## 曲线（为什么是这个形状）
 *
 * ```
 *   0ms   起跳
 * 230ms   到最高点（LinearOutSlowIn：起步快、到顶柔，像真的起跳）
 * 300ms   顶点悬停一瞬（滞空感，没有它就像弹簧）
 * 640ms   落回地面（FastOutLinearIn：越落越快，符合重力）
 * 980ms   一个循环结束，立刻下一跳
 * ```
 */
object HplayLoadArt {

    /** 原图宽高（370×320）。宽高比用于【不拉伸】地摆图标。 */
    const val SRC_W = 370f
    const val SRC_H = 320f

    /** 原图宽高比。**图片不缩放**指的是动画不缩放，显示仍按原比例（不拉伸变形）。 */
    const val ASPECT = SRC_W / SRC_H

    /**
     * **裁切窗**（原图像素坐标）：left, top, right, bottom。
     *
     * 第 351 轮按用户要求清空 —— 直接用原始整图。
     * 真要回退成「头部特写」，把它设成 `intArrayOf(165, 40, 345, 220)` 即可，
     * 下面的绘制分支会重新走 `drawImage(srcRect)` 那条路。
     */
    val CROP: IntArray = IntArray(0)

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
 * 加载图标（只画图，不带文字）。
 *
 * 小尺寸（列表页脚 12dp、卡片占位 14dp 之类）用它；整页加载态用 [HplayLoadingMark]。
 *
 * @param markSize 图标**高度**。宽度按原图比例 370:320 自动算 —— 不拉伸、不裁切。
 */
@Composable
fun HplayLoadMark(
    markSize: Dp = 26.dp,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val img = remember { HplayLoadArt.get(ctx) }
    val tr = rememberInfiniteTransition(label = "loadmark")

    // ★★ 第 351 轮：**只剩位移**。用户要求「图片不缩放」，所以挤压那条动画已删除，
    // 这里连 squash 的 State 都不再创建 —— 少一个每帧读取的 float，也少一次合成属性写。
    // 注意：**不要**用 by 解构，那只会在组合期读值 → 每帧重组。
    val hop = tr.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 980
                0f at 0
                -1f at 230 using LinearOutSlowInEasing
                -1f at 300
                0f at 640 using FastOutLinearInEasing
                0f at 980
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "hop",
    )

    // 跳的时候把窗口帧率抬到面板上限；离开组合（加载结束）立刻交还。
    DisposableEffect(Unit) {
        HplayFrameRate.pushLoop()
        onDispose { HplayFrameRate.popLoop() }
    }

    // 宽按原图比例算：370:320 ⇒ 宽 = 高 × 1.156。方框会把整图压扁或留黑边。
    val w = markSize * HplayLoadArt.ASPECT
    Box(
        modifier
            .width(w)
            .height(markSize)
            // clip 在 graphicsLayer 之前：圆角随整体一起跳，不是「跳出框外再被裁」
            .clip(RoundedCornerShape(markSize * 0.22f))
            .graphicsLayer {
                // ★ 全部 state 读在这里 —— 只在 draw 阶段发生，不触发重组
                translationY = hop.value * 5.dp.toPx()
                // ★ 第 351 轮：**不缩放**。显式写 1f 而不是删掉这两行 ——
                // graphicsLayer 的 scale 默认就是 1f，但写出来是要让下一个人一眼看到
                // 「这里刻意不动」，别再往里加挤压。
                scaleX = 1f
                scaleY = 1f
            },
    ) {
        if (img != null) {
            val crop = HplayLoadArt.CROP
            if (crop.size < 4) {
                // 第 351 轮默认走这里：**整图**，等比铺满（Fit 不留白、不裁切）。
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
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
                        dstSize = androidx.compose.ui.unit.IntSize(
                            this.size.width.toInt().coerceAtLeast(1),
                            this.size.height.toInt().coerceAtLeast(1),
                        ),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    )
                }
            }
        } else {
            // 兜底：一张同尺寸的圆角色块，仍然在跳（不至于「没有加载反馈」）
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.35f + 0.35f * (1f + hop.value) },
            )
        }
    }
}

/**
 * 整页加载态：**图标 + 文字**（第 351 轮，用户要求「保留文字」）。
 *
 * 与参考实现 `LoadingContent.kt` 同构 —— 那张图下面是 `Text(message)`，
 * 默认「加载中...」。这里把「…」写成中文全角省略号（与项目其它处统一）。
 *
 * @param markSize 图标高度；宽度自动按原图比例。
 * @param text 文案；传空串就只画图标（少数不要字的地方）。
 */
@Composable
fun HplayLoadingMark(
    modifier: Modifier = Modifier,
    markSize: Dp = 34.dp,
    text: String = "加载中…",
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HplayLoadMark(markSize = markSize)
            if (text.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                )
            }
        }
    }
}