package com.han1me.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

/**
 * **应用背景图**（2026-11-25 第 292 轮，用户提供图片）。
 *
 * ## 设计

 * 图片放在 `assets/app_bg.jpg`，运行时解码一次后缓存。
 * 上面盖一层半透明黑渐变，保证前景 UI 的可读性 ——
 * 直接用原图会太亮，文字在亮部完全看不清。
 *
 * ## 为什么不用 res/drawable

 * 换图只需替换 assets 里的文件重建，不用碰资源 ID（`R.drawable.*`）。
 * 用户可能还会换图，assets 方式对他更友好。
 */
object HplayBg {
    /** 解码后的位图缓存（进程内只解一次）。 */
    @Volatile
    private var cached: androidx.compose.ui.graphics.ImageBitmap? = null

    @Volatile
    private var tried = false

    fun load(ctx: android.content.Context): androidx.compose.ui.graphics.ImageBitmap? {
        cached?.let { return it }
        if (tried) return null
        tried = true
        return runCatching {
            // ★ 第 335 轮（用户要求外观页自定义背景）：设置键 ui.bg.file 优先；
            // 没有自定义图时回落包内 hplay_app_bg（第 335 轮已换成用户新图）。
            val custom = com.han1me.X99.setting("ui.bg.file", "")
            if (custom.isNotBlank()) {
                com.han1me.HplaySlots.decodeImageCached(custom)?.also { cached = it }
            } else {
                val id = ctx.resources.getIdentifier("hplay_app_bg", "drawable", ctx.packageName)
                if (id == 0) return null
                BitmapFactory.decodeResource(ctx.resources, id)?.asImageBitmap()?.also { cached = it }
            }
        }.getOrNull()
    }

    /** 用户换背景后调：丢缓存与 tried 标记，下一帧重新 load。 */
    fun invalidate() {
        cached = null
        tried = false
    }
}

/**
 * 背景层：图片（若有）+ 可读性遮罩。
 *
 * 用法：放在页面内容**之前**的 Box 里，内容叠在它上面。
 */
@Composable
fun HplayBackgroundLayer(
    modifier: Modifier = Modifier,
    /** 遮罩强度 0..1。0 = 完全透出图片，1 = 全黑。 */
    dim: Float = 0.42f,
) {
    val ctx = LocalContext.current
    val bmp = remember { HplayBg.load(ctx) }
    Box(modifier.fillMaxSize()) {
        if (bmp != null) {
            // ★★★ 第 335 轮修正（用户报「背景变成一半且拉伸」）：
            //
            // 上一版用 FillBounds（拉伸铺满），用户实测两处都不对：
            //   · 拉伸把人物压变形；
            //   · 旧默认图是 912x1536 竖图，铺宽屏后只看到中间一条。
            //
            // 现在回到 **ContentScale.Crop**：等比缩放后裁边，**绝不拉伸**，
            // 且 fillMaxSize() 保证**整屏覆盖**（顶栏到底栏，没有"一半"）。
            // 配套：默认背景已换成 1536x912 的横图，Crop 在竖屏上就是"填满宽度、
            // 上下各裁一点"，视觉最接近原图比例。
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 没图时用深色渐变兜底，不至于白屏
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF1A1A20), Color(0xFF0E0E12))),
                ),
            )
        }
        // 遮罩：上淡下浓，底部更深（底栏区域需要更实的底）
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    // 2026-11-25 调优：原来 0.62 太浓，图片几乎看不出。
                    // 现在 0.42：上半部几乎透出原图，底部稍浓保证底栏可读。
                    0f to Color.Black.copy(alpha = dim * 0.55f),
                    0.55f to Color.Black.copy(alpha = dim),
                    1f to Color.Black.copy(alpha = (dim + 0.28f).coerceAtMost(0.88f)),
                ),
            ),
        )
    }
}
