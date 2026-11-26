package com.han1me

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * **MD3 语义 token 扩展**（2026-11-25 第 325 轮）。
 *
 * ## 为什么要这一层
 *
 * 全项目原来有 40+ 种硬编码色（0xFFE8E8EA / 0xFF9A9AA2 / 0xFF23232A ...），
 * 它们是从上游桌面版一路搬过来的固定配色。表现就是「换主题色只变了几个地方」，
 * 其余界面纹丝不动 —— 用户换主题后观感割裂。
 *
 * 这里按**语义**把那些颜色归成 MD3 token。全部是 @Composable getter，
 * 调用点只需把 Color(0xFF...) 换成 token 名，零结构改动、零生命周期影响。
 *
 * ## 语义映射依据（从调用点上下文归纳）
 *
 * | 原硬编码色 | 语义 | token |
 * |---|---|---|
 * | 0xFFE8E8EA / 0xFFCFCFD4 / 0xFFD8D8DC | 正文主文字 | [HplayOnSurface] |
 * | 0xFF9A9AA2 / 0xFFB8B8C0 / 0xFF6A6A72 | 次要说明文字 | [HplayOnSurfaceVariant] |
 * | 0xFF23232A / 0xFF2E2E34 / 0xFF1C1C20 | 卡片/输入框底 | [HplaySurfaceContainer] |
 * | 0xFF141417 / 0xFF141416 / 0xFF1A1A1E | 页面底 | [HplaySurfaceLow] |
 * | 0xFFB3262C | 强调红（主色） | [HplayPrimary] |
 * | 0xFFFF8A8A | 浅红警示文字 | [HplayErrorSoft] |
 * | 0xFF7ED97E / 0xFF4CAF50 | 成功绿 | [HplaySuccess] |
 * | 0xFFFFC46B / 0xFFE5A05A | 警告橙 | [HplayWarning] |
 * | 0xFF4A4A52 | 分割线/描边 | [HplayOutline] |
 * | 0x99000000 / 0xE6000000 | 纯黑遮罩 | **保留**（遮罩就该是黑） |
 *
 * ## 为什么单独一个文件
 *
 * 上游的 `Tokens.kt` 是 CRLF、且被 patches.py 的锚点覆盖着；
 * 往里加东西要动补丁锚点（本轮因转义层级踩了两次坑）。
 * 独立文件零侵入，构建时直接编进去。
 */

internal val HplayOnSurface: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface

internal val HplayOnSurfaceVariant: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

internal val HplaySurfaceContainer: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainer

internal val HplaySurfaceLow: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerLowest

internal val HplayPrimary: Color
    @Composable get() = MaterialTheme.colorScheme.primary

internal val HplayOnPrimary: Color
    @Composable get() = MaterialTheme.colorScheme.onPrimary

internal val HplayErrorSoft: Color
    @Composable get() = MaterialTheme.colorScheme.error

internal val HplayOutline: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant

/** 成功态（下载完成 / 连接正常）。MD3 没有 success 语义，用固定绿但跟随明暗调亮度。 */
internal val HplaySuccess: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        Color(0xFF2E7D32)
    } else {
        Color(0xFF7ED97E)
    }

/** 警告态（测速中 / 需注意）。 */
internal val HplayWarning: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        Color(0xFFB26A00)
    } else {
        Color(0xFFFFC46B)
    }
