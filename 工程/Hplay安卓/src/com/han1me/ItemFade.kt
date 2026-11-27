package com.han1me

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.han1me.ui.itemEnter

/**
 * **itemEnter 的桥接**（第 348 轮，与 BoardFade.kt 同一个理由）。
 *
 * com.han1me.ui.itemEnter 是 Modifier 的扩展函数。
 * Han1meApp / X99Board 在 com.han1me 包、与它不同包，链式调用 .itemEnter(...) 解析不到；
 * 而 com.han1me.ui.itemEnter(Modifier, key) 这种"带包名的静态调用"在 Kotlin 里也不成立 ——
 * 扩展函数不能那样限定调用。
 *
 * 正解：在**同包**加一层 import + 薄转发，调用点就能写成
 * Modifier.itemIn(i) {...} 这种形式。
 */
@Composable
fun Modifier.itemIn(index: Int): Modifier = this.itemEnter(index)
