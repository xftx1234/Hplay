package com.han1me.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **用户页封面横幅 · MD3**（2026-11-26 第 337 轮）。
 *
 * ## 为什么要单独一个组件
 *
 * 第 335 / 336 轮这块是直接在 `build/gen/Han1meApp.kt` 里用补丁文本改的，
 * 结果连续两轮都改歪：
 *   · 335 轮：`alpha(0.38f)` 半透明 —— 用户要的是**不透明**封面，看着像蒙了层灰；
 *   · 336 轮：把"半屏"改成 150dp 横条，但透明度 0.38 原样留着，且没有 MD3 的圆角/层次；
 *   · 两轮都没解决"这块跟其余界面不是一个设计语言"。
 *
 * 现在整块搬到 `src/`（真正受版本控制、能改逻辑的地方），gen 那边只剩一行调用。
 * 以后调样式不必再动补丁锚点 —— 前两轮的返工就是因为"样式写在补丁里"。
 *
 * ## 设计（对齐 Material 3）
 *
 * | 项 | 取值 | 依据 |
 * |---|---|---|
 * | 容器 | `Surface` + `surfaceContainer` | MD3 elevated/card 容器色，不是纯黑 |
 * | 圆角 | `HplayDimens.radiusSheet` (22dp) | MD3 large card 量级；小圆角显得像图片没切好 |
 * | 图 | **不透明**，`ContentScale.Crop` | 用户明确要求不透明；Crop 等比裁边不拉伸 |
 * | 底部压暗 | 只有**下半 45%** 一层渐变 | 只为按钮可读，不覆盖整图（335 轮半透明是错的） |
 * | 换图按钮 | `Surface` 胶囊 + `onSurface` 文字，alpha 0.72 | MD3 filled-tonal 观感，避免 Material3.Button 的抬升阴影抢戏 |
 *
 * ## 没有封面时
 *
 * 画同尺寸的渐变占位 + "选择封面"，**不塌陷高度** —— 否则第一帧没有封面时
 * 下面的网格会先顶上来再跳下去（335 轮的真机现象）。
 */
@Composable
fun HplayCoverBanner(
    /** 本机封面文件绝对路径；空 = 用包内默认图。 */
    file: String,
    modifier: Modifier = Modifier,
    /** 点封面/按钮时的回调；null = 不可换（只展示）。 */
    onPick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    // 与 335 轮一致：走进程级解码缓存，切 tab 不重解。
    val picked = remember(file) {
        if (file.isBlank()) null else com.han1me.HplaySlots.decodeImageCached(file)
    }
    // 默认图只解一次（decodeRes 内部不解缓存，这里靠 remember 兜住）。
    val fallback = remember {
        com.han1me.HplaySlots.decodeRes(ctx, "hplay_cover_default")
    }
    val bmp = picked ?: fallback

    val shape = RoundedCornerShape(HplayDimens.radiusSheet)
    Surface(
        modifier = modifier.height(HplayDimens.coverHeight),
        shape = shape,
        color = cs.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "用户页封面",
                    // ★ 不透明：用户要求。alpha 一律留在 1，靠形状与压暗分隔层次。
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // 没有默认图（资源被裁掉）也不能白屏：给同色系渐变
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            listOf(cs.surfaceContainerHighest, cs.surfaceContainerLow),
                        ),
                    ),
                )
            }

            // 底部压暗：**只覆盖下半**，让按钮永远可读，同时不把整图蒙灰。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        ),
                    ),
            )

            // 点击整块也能换图（老用户已经习惯点封面）
            if (onPick != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .clickable { onPick() },
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "我的封面",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (picked != null) "本机图片 · 点按更换" else "内置默认图 · 点按更换",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (onPick != null) {
                    // MD3 filled-tonal 观感：胶囊 + 半透明表面 + onSurface 文字
                    Surface(
                        shape = RoundedCornerShape(HplayDimens.radiusChip),
                        color = cs.surface.copy(alpha = 0.82f),
                        contentColor = cs.onSurface,
                        tonalElevation = 0.dp,
                        modifier = Modifier.clickable { onPick() },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text("🖼", fontSize = 12.sp)
                            Spacer(Modifier.width(5.dp))
                            Text("更换", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
