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
        // ★ 2026-11-25（第 294 轮）：改从 res/drawable 读。
        // assets 方案在 Windows 构建机上反复被文件句柄卡住，换资源管线更稳。
        return runCatching {
            val id = ctx.resources.getIdentifier("hplay_app_bg", "drawable", ctx.packageName)
            if (id == 0) return null
            BitmapFactory.decodeResource(ctx.resources, id)?.asImageBitmap()?.also { cached = it }
        }.getOrNull()
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
            // ★★★ 2026-11-25（第 310 轮 · 用户报「背景只有下面一半」）：
            //
            // 原因：图片是 912x1536 的**竖图**，用 ContentScale.Crop 铺到宽屏上时，
            // 会按宽度缩放再裁掉上下 —— 视觉上就是「只有中间一条」。
            //
            // 改法：用 ContentScale.FillBounds（拉伸铺满）。
            // 竖图铺宽屏会被横向拉伸，但配合下面的深色遮罩后，
            // 观感上比「只有一半」好得多；而且用户换横图时完全正常。
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
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
