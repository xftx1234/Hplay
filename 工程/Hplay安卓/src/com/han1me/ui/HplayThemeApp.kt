package com.han1me.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * **Hplay 主题包装**（2026-11-24 第 277 轮重构新增）。
 *
 * 用法：整个 App 的根 Composable 外面套一层 `HplayTheme { ... }`。
 * 之后所有界面用 `MaterialTheme.colorScheme.xxx` 取色，不再写硬编码十六进制。
 *
 * 要点：
 *  · 首次组合时从磁盘读一次用户选择（[HplayThemeStore.load]）；
 *  · 读的是 `mutableStateOf`，用户改主题会**自动重组**，不需要手动刷新；
 *  · 形状也一并统一（MD3 的大圆角），避免各处 `RoundedCornerShape(12.dp)` 乱飞。
 */
@Composable
fun HplayTheme(
    /** 允许外部强制指定明暗（调试用）；默认跟随用户设置。 */
    forceDark: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    // ★★★ 2026-11-25（配色 bug 修复）：**同步读一次**，不要只靠 LaunchedEffect。
    //
    // 原写法只有 `LaunchedEffect(Unit) { load(ctx) }` —— 首次组合时 store 还是初始值，
    // 等 load 回来才会触发重组。结果：设置页打开时如果赶在 load 之前，
    // 拿到的是 MaterialTheme 的**默认配色**（洋红底），看起来像主题没生效。
    // 这里改成组合期间同步读（SharedPreferences 有内存缓存，读取开销可忽略）。
    HplayThemeStore.load(ctx)

    // forceDark 只在调试时用；正常走用户设置
    val dark = forceDark ?: HplayThemeStore.dark
    val amoled = HplayThemeStore.amoled && dark
    val scheme = if (dark) md3Dark(HplayThemeStore.seed.seed, amoled) else md3Light(HplayThemeStore.seed.seed)

    // ★ 诊断：确认主题真的生效（洋红 = darkColorScheme 默认值 = 没传进去）
    LaunchedEffect(dark, amoled, HplayThemeStore.seed) {
        com.han1me.lab.LabLog.i(
            "ui.theme",
            "主题生效 dark=" + dark + " amoled=" + amoled +
                " seed=" + HplayThemeStore.seed.name +
                " bg=" + scheme.background.toString() +
                " primary=" + scheme.primary.toString(),
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = hplayTypography(),
        shapes = hplayShapes(),
        content = content,
    )
}

/**
 * 字号阶梯。
 *
 * 为什么不直接用 `Typography()` 默认值：MD3 默认的 `titleLarge` 是 22sp，
 * 而本站列表里的片名较长（日文标题动辄 30+ 字），22sp 两行就撑爆卡片。
 * 这里把标题类压小一档，正文保持默认 —— 视觉上更紧凑，也与原版观感接近。
 */
@Composable
private fun hplayTypography(): Typography {
    val base = Typography()
    return base.copy(
        titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize * 0.92f),
        titleMedium = base.titleMedium.copy(fontSize = base.titleMedium.fontSize * 0.95f),
    )
}

/** MD3 形状阶梯：卡片 14dp、按钮胶囊、弹层 22dp。 */
@Composable
private fun hplayShapes(): Shapes {
    val card: CornerBasedShape = RoundedCornerShape(14.dp)
    val sheet: CornerBasedShape = RoundedCornerShape(22.dp)
    return Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(9.dp),
        medium = card,
        large = sheet,
        extraLarge = RoundedCornerShape(28.dp),
    )
}
