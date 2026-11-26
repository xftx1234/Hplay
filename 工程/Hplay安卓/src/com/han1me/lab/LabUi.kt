package com.han1me.lab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * UI 实时刷新信号（实验分叉 · 2026-10-31）。
 *
 * 用户要求：「按钮的开关以及自动切换，不要点了下个板块页面再回来才刷新，
 *   要页面实时刷新、实时切换。」
 *
 * 问题根源：各面板用的是
 *   var on by remember { mutableStateOf(HanimeClient.edgeHostsEnabled) }
 * remember 只在首次组合时求值一次；之后哪怕 HanimeClient 里的值变了，
 * 界面也不会重组 —— 所以看起来「要点进别处再回来才生效」。
 *
 * 修法：凡是有可能被别处改动的开关，都订阅这个版本号；
 * 改动方调 bump()，所有订阅者立刻重组并重读真实值。
 */
object LabUi {
    /** 全局版本号：任何「可能被别处改」的状态变更后 +1。 */
    var rev by mutableIntStateOf(0)
        private set

    fun bump() {
        rev += 1
    }
}
