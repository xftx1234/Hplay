package com.han1me

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.han1me.ui.pageFade

/**
 * **pageFade 的桥接**（2026-11-26 第 328 轮）。
 *
 * `com.han1me.ui.pageFade` 是 `Modifier` 的扩展函数。
 * X99Board 在 `com.han1me` 包、与它不同包，链式调用 `.pageFade(...)` 解析不到；
 * 而 `com.han1me.ui.pageFade(Modifier, key)` 这种「带包名的静态调用」在 Kotlin 里也不成立 ——
 * 扩展函数不能那样限定调用。
 *
 * 正解：在**同包**加一层 import + 薄转发，调用点就能写成
 * `Modifier.fillMaxSize().boardFade(key)`。
 */
@Composable
fun Modifier.boardFade(key: Any): Modifier = this.pageFade(key)
