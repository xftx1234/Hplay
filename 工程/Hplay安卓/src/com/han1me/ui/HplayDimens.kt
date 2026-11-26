package com.han1me.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **设计令牌**（2026-11-25 第 278 轮 UI 重构）。
 *
 * 把散落各处的魔法数字收敛成一套语义化的尺度与间距。
 *
 * ## 为什么要单独一层

 * 原版 UI 里同一个「卡片圆角」有 8dp / 10dp / 12dp 三种写法，
 * 同一个「页面边距」有 12 / 14 / 16 三种 —— 视觉上不统一，改起来也没法一处生效。
 *
 * Han1meViewer 有 `Dimens.kt`，Han1mePlus 把间距写在主题里；
 * 我们照这个思路做，但只保留**真正会被复用**的那些，不做过度抽象。
 */
object HplayDimens {

    // ---------- 间距 ----------
    /** 页面左右边距。列表、详情、设置页统一用它。 */
    val page = 16.dp

    /** 卡片之间的间距。 */
    val gap = 12.dp

    /** 小间距（同一行的控件之间）。 */
    val gapSmall = 8.dp

    /** 区块之间的间距（「最新上市」与「最新上传」之间）。 */
    val section = 22.dp

    // ---------- 圆角 ----------
    val radiusCard = 14.dp
    val radiusChip = 999.dp
    val radiusSheet = 22.dp

    // ---------- 尺寸 ----------
    /** 顶栏高度。 */
    val topBar = 54.dp

    /** 底栏高度。 */
    val bottomBar = 58.dp

    /** 列表卡片的最小宽度（双列网格用）。 */
    val cardMinWidth = 156.dp

    /** 详情页封面高度。 */
    val heroHeight = 200.dp

    // ---------- 常用内边距 ----------
    val pagePadding = PaddingValues(horizontal = page)
    val cardPadding = PaddingValues(10.dp)

    /** 底部内容留白：为底栏让位，避免最后一行被遮住。 */
    val bottomInset: Dp = bottomBar + 18.dp
}
