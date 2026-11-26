package com.han1me.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * **Hplay 主题配色体系**（2026-11-24 第 277 轮重构新增）。
 *
 * ## 为什么要重建

 * v1.1.69 及之前，全部界面颜色都是**硬编码**的（`Color(0xFF17171C)` 之类散落在几十个文件里），
 * 后果：
 *  · 想做「换肤」要改动每一处；
 *  · 明暗模式无法整体切换（有一半硬编码深色、一半没写）；
 *  · 语义混乱 —— 同一个灰色在三处是三个值，视觉上不统一。

 * 这次参照 Han1mePlus（Material Design 3 设计语言）与 Han1meViewer 的 ThemePresets，
 * 把颜色收敛成**种子色 + 生成整套 Material 3 ColorScheme** 的模型：
 *  · 每个预设只声明一个种子色，其余 30+ 语义色由 [md3Dark] / [md3Light] 统一派生；
 *  · 界面只引用语义色（`MaterialTheme.colorScheme.surface` 等），不再写死十六进制；
 *  · 加一个预设 = 加一行种子色。
 *
 * ## 预设清单

 * 种子色取自已验证的 MD3 配色（与 Han1mePlus 的 9 色一一对应），
 * 默认用「玫红」—— 与本站原有的红黑视觉基调一致，不至于换主题换到认不出。
 */
enum class HplaySeed(val label: String, val seed: Long) {
    ROSE("玫红", 0xFFB3265A),
    BLUE("深蓝", 0xFF00639B),
    TEAL("青碧", 0xFF006B5F),
    AMBER("琥珀", 0xFF875400),
    GREEN("苔绿", 0xFF386A20),
    ORANGE("橘橙", 0xFF9B4400),
    INDIGO("靛蓝", 0xFF4A5F9E),
    PINK("樱粉", 0xFF9C3C66),
    PURPLE("紫罗兰", 0xFF6D3F90);

    companion object {
        fun fromName(n: String?): HplaySeed =
            entries.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: ROSE
    }
}

/**
 * 由种子色派生一套**深色** Material 3 配色。
 *
 * 算法说明：没有引入 `dynamicColor` / `material-color-utilities`（那需要额外依赖），
 * 而是用**固定比例混色**从种子色派生出各个语义层。这在视觉上足够接近 MD3，
 * 且完全离线、零依赖 —— 对我们是更稳的选择（主线构建是纯 kotlinc，不跑 Gradle）。
 *
 * @param amoled true 时把所有 surface 层级压到纯黑（OLED 省电，Han1mePlus 也有这个开关）
 */
fun md3Dark(seed: Long, amoled: Boolean = false): androidx.compose.material3.ColorScheme {
    val s = Color(seed)
    val primary = mix(s, Color.White, 0.34f)
    val onPrimary = mix(s, Color.Black, 0.72f)
    val primaryContainer = mix(s, Color.Black, 0.22f)
    val onPrimaryContainer = mix(s, Color.White, 0.72f)
    val secondary = mix(s, Color.White, 0.20f)
    val onSecondary = mix(s, Color.Black, 0.70f)
    val secondaryContainer = mix(s, Color.Black, 0.34f)
    val onSecondaryContainer = mix(s, Color.White, 0.66f)
    val tertiary = mix(s, Color(0xFFFFC46B), 0.42f)
    val onTertiary = mix(s, Color.Black, 0.70f)
    val tertiaryContainer = mix(s, Color(0xFF875400), 0.36f)
    val onTertiaryContainer = mix(s, Color(0xFFFFDDB0), 0.70f)

    // 底色：深灰带一点点种子色的倾向（纯灰会显得脏，纯种子色又太艳）
    val bg = if (amoled) Color.Black else mix(s, Color(0xFF101014), 0.06f)
    val surface = if (amoled) Color.Black else mix(s, Color(0xFF141418), 0.05f)
    val c = { f: Float -> if (amoled) Color.Black else mix(s, Color(0xFF1C1C22), f) }

    return darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = secondary, onSecondary = onSecondary,
        secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary, onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
        error = Color(0xFFFF8A8A), onError = Color(0xFF5C0000),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        background = bg, onBackground = Color(0xFFE7E7EC),
        surface = surface, onSurface = Color(0xFFE7E7EC),
        surfaceVariant = mix(s, Color(0xFF2A2A32), 0.16f),
        onSurfaceVariant = Color(0xFFB8B8C2),
        outline = Color(0xFF6A6A76), outlineVariant = Color(0xFF3A3A44),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE7E7EC), inverseOnSurface = Color(0xFF2B2B32),
        inversePrimary = mix(s, Color.Black, 0.30f),
        surfaceDim = if (amoled) Color.Black else mix(s, Color(0xFF0E0E12), 0.04f),
        surfaceBright = if (amoled) Color.Black else mix(s, Color(0xFF26262E), 0.08f),
        surfaceContainerLowest = if (amoled) Color.Black else mix(s, Color(0xFF0A0A0E), 0.05f),
        surfaceContainerLow = c(0.03f),
        surfaceContainer = c(0.05f),
        surfaceContainerHigh = c(0.08f),
        surfaceContainerHighest = c(0.11f),
    )
}

/** 由种子色派生一套**浅色** Material 3 配色（同上的比例混色思路）。 */
fun md3Light(seed: Long): androidx.compose.material3.ColorScheme {
    val s = Color(seed)
    return lightColorScheme(
        primary = mix(s, Color.Black, 0.10f),
        onPrimary = Color.White,
        primaryContainer = mix(s, Color.White, 0.78f),
        onPrimaryContainer = mix(s, Color.Black, 0.30f),
        secondary = mix(s, Color.Black, 0.22f),
        onSecondary = Color.White,
        secondaryContainer = mix(s, Color.White, 0.82f),
        onSecondaryContainer = mix(s, Color.Black, 0.34f),
        tertiary = mix(s, Color(0xFF875400), 0.55f),
        onTertiary = Color.White,
        tertiaryContainer = mix(s, Color(0xFFFFDDB0), 0.70f),
        onTertiaryContainer = mix(s, Color.Black, 0.30f),
        error = Color(0xFFBA1A1A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
        background = mix(s, Color(0xFFFFFBFE), 0.03f),
        onBackground = Color(0xFF1B1B1F),
        surface = mix(s, Color(0xFFFFFBFE), 0.02f),
        onSurface = Color(0xFF1B1B1F),
        surfaceVariant = mix(s, Color(0xFFE7E0EC), 0.24f),
        onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E), outlineVariant = Color(0xFFCAC4D0),
        scrim = Color.Black,
        inverseSurface = Color(0xFF313033), inverseOnSurface = Color(0xFFF4EFF4),
        inversePrimary = mix(s, Color.White, 0.30f),
        surfaceDim = mix(s, Color(0xFFDED8E1), 0.16f),
        surfaceBright = mix(s, Color(0xFFFFFBFE), 0.04f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = mix(s, Color(0xFFF7F2FA), 0.06f),
        surfaceContainer = mix(s, Color(0xFFF3EDF7), 0.10f),
        surfaceContainerHigh = mix(s, Color(0xFFECE6F0), 0.14f),
        surfaceContainerHighest = mix(s, Color(0xFFE6E0E9), 0.18f),
    )
}

/**
 * 两色按比例混合：`ratio=0` 返回 [a]，`ratio=1` 返回 [b]。
 *
 * 用于从种子色派生语义层。用逐通道线性插值即可 —— 不需要 gamma 修正，
 * 因为这些派生的用途是「同色系不同明度」，线性插值在视觉上足够平滑。
 */
fun mix(a: Color, b: Color, ratio: Float): Color {
    // ★★★ 2026-11-25（配色 bug 根因修复）：用官方 lerp，不自己拼通道。
    //
    // 踩过的坑（都在真机上验证过）：
    //  ① `Color(red = f, green = f, blue = f, alpha = 1f)` —— Compose 里这组
    //     Float 具名参数会匹配到带 ColorSpace 的那族构造器，实测得到**透明色**
    //     （日志 bg=#0），界面退回 MaterialTheme 默认洋红。
    //  ② 手工 `(0xFF shl 24) or (r shl 16) ...` 再 `Color(Int)` —— 在 Kotlin 里
    //     这个表达式类型是 Int，但 Compose 的 `Color(x: Int)` 是**扩展函数**，
    //     需要 `import androidx.compose.ui.graphics.Color` 才能解析；漏了就静默走
    //     到 `Color(Long)`，同样得到错色。
    //
    // 直接用官方 `androidx.compose.ui.graphics.lerp(start, stop, fraction)`：
    // 它就是「两色按比例插值」，行为定义清晰、无需自己算通道，也不依赖重载解析。
    // ★★★ 语义约定（重要，别再搞反）：
    //
    //   mix(种子色, 目标色, r) 的意思是「**以目标色为主**，掺 r 比例的种子色」。
    //
    // 也就是说 r 越大越接近 a（种子），越小越接近 b（目标）。
    // 这符合调用处的意图，例如：
    //     bg = mix(s, Color(0xFF101014), 0.06f)   → 94% 深灰 + 6% 种子倾向
    //
    // 实现上 lerp(start, stop, fraction) 是 start→stop，所以要反过来传：
    //     lerp(b, a, r)
    //
    // 之前写成 lerp(a, b, r) 的后果（真机实测）：bg 得到了 94% 的**亮粉色**，
    // 整个设置页是洋红底 —— 就是这个参数顺序反了。
    val r = ratio.coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.lerp(b, a, r)
}
