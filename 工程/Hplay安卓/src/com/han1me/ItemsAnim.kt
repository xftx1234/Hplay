package com.han1me

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.runtime.Composable

/**
 * **带入场动画的 items 包装**（第 348 轮）。
 *
 * ## 为什么需要这一层
 *
 * LazyRow / LazyVerticalGrid 的便捷重载 `items(list, key = {...}) { v -> }` **不给 index**，
 * 而入场动画的阶梯延迟（Viewer AnimatedLazy: min(index,6) x 12ms）必须知道下标。
 * 想拿下标只能改用 `itemsIndexed`，但它的 key 形参是 `(index, item) -> Any`，
 * 与便捷版的 `(item) -> Any` 不同 —— 每个调用点都要改签名太脏。
 *
 * 把这些差异收在这一个文件里，调用点只把 `items` 换成 `itemsA`、lambda 多收一个 `i`。
 *
 * ## 规格来源（Han1meViewer AnimatedLazy.kt:362-402，逐行核对）
 *
 *   alpha 0 -> 1（100ms）、scale 0.985 -> 1（180ms）、阶梯 12ms x min(index, 6)
 *
 * 只动合成层属性（graphicsLayer），不触发重排；滚动复用已有项时不会重放。
 */

/** LazyRow / LazyColumn 用。 */
inline fun <T> LazyListScope.itemsA(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    crossinline itemContent: @Composable (item: T, index: Int) -> Unit,
) {
    listItemsIndexed(
        items = items,
        key = if (key == null) null else { _, v -> key(v) },
    ) { i, v -> itemContent(v, i) }
}

/** LazyVerticalGrid 用。 */
inline fun <T> LazyGridScope.itemsA(
    items: List<T>,
    noinline key: ((item: T) -> Any)? = null,
    crossinline itemContent: @Composable (item: T, index: Int) -> Unit,
) {
    gridItemsIndexed(
        items = items,
        key = if (key == null) null else { _, v -> key(v) },
    ) { i, v -> itemContent(v, i) }
}
