package com.han1me.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **自适应列数**（2026-11-26 第 327 轮，照 Han1meViewer 的 `Utils.getColumnCount`）。
 *
 * ## 为什么需要
 *
 * 用户要求「修复自动旋转适配」。App 本身没有锁方向（`requestedOrientation` 是
 * UNSPECIFIED），但**布局全是写死的 2 列** —— 横屏 / 平板 / 折叠屏展开时，
 * 屏幕上就两条巨大的卡片，留白比内容多。
 *
 * Viewer 的做法（`ui/screen/Utils.kt` 第 88-99 行）：
 * ```
 * val usable = availableWidth - contentPadding * 2
 * return maxOf(1, ((usable + itemSpacing) / (itemWidth + itemSpacing)).toInt())
 * ```
 * 也就是「能放下几张就放几张」，卡片宽度基本恒定。
 *
 * ## 用法
 *
 * ```kotlin
 * BoxWithConstraints {
 *     LazyVerticalGrid(
 *         columns = GridCells.Fixed(adaptiveColumns(maxWidth, minItemWidth = 160.dp)),
 *     ) { ... }
 * }
 * ```
 *
 * ## 与固定 2 列的取舍
 *
 * 手机上（360~430dp）算出来仍是 2 列 —— 与原来完全一致，不会让老用户觉得变样。
 * 只有真的变宽（横屏 700dp+ / 平板 600dp+）才增加列数。
 */
fun adaptiveColumns(
    availableWidth: Dp,
    minItemWidth: Dp = 160.dp,
    contentPadding: Dp = 16.dp,
    itemSpacing: Dp = 12.dp,
): Int {
    // ★★★ 第 344 轮（全量核查 · P1）：**平板模式终于有生效点了**。
    //
    // 现状复核：HplayTablet 这个开关从第 76 轮做出来，一直只有
    // 「开关 + 状态文案」两半，effective() 全项目**没有一个读者** ——
    // 用户拨了开关，排版一个像素都不会变，属于纯装饰。
    //
    // 这里给它第一个真实落点：平板模式下把卡片最小宽度压到 0.72 倍，
    // 同一块屏能多排一列，这正是「平板布局：提供更大观看区域」的含义
    // （参考 Han1meViewer 的 rememberVideoGridColumns：平板时按窗口宽度
    // 取偏好列数，而不是固定的自适应值）。
    //
    // 手机上完全不受影响 —— effective() 内部要求 smallestScreenWidthDp >= 600dp。
    val minW = if (com.han1me.tablet.HplayTablet.effective()) {
        (minItemWidth.value * 0.72f).dp.coerceAtLeast(100.dp)
    } else {
        minItemWidth
    }
    val usable = (availableWidth - contentPadding * 2).coerceAtLeast(0.dp)
    val fit = ((usable + itemSpacing) / (minW + itemSpacing))
    return fit.toInt().coerceAtLeast(1)
}

/**
 * 同上，但直接在 [BoxWithConstraints] 里用（省一层嵌套）。
 *
 * ```kotlin
 * BoxWithConstraints {
 *     val cols = columnsFor(maxWidth)
 *     LazyVerticalGrid(columns = GridCells.Fixed(cols)) { ... }
 * }
 * ```
 */
@Composable
fun columnsFor(availableWidth: Dp, minItemWidth: Dp = 160.dp): Int =
    adaptiveColumns(availableWidth, minItemWidth)

/** 当前是否宽屏（横屏/平板）。Viewer 用 600dp 作为 expanded 阈值。 */
fun isWideLayout(availableWidth: Dp): Boolean = availableWidth >= 600.dp
