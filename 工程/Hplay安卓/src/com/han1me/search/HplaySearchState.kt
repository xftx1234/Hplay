package com.han1me.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * **搜索页的状态容器**（第 350 轮）。
 *
 * 为什么要一个 object 而不是 remember：参考实现把这些参数放在 SearchViewModel 里，
 * 而 SearchViewModel 挂在 NavEntry 上 —— 用户从搜索页点进视频、返回后过滤条件还在
 * （SearchViewModel.kt:55-107 的 SavedStateHandle）。
 *
 * 本工程的搜索是**底栏一个 tab**，切走再切回来会重建组合树；只用 remember 会丢掉
 * 过滤条件，用户回来看到「条件全没了、结果还在」的错位状态。
 * 所以放进程级 object：切 tab 不丢，改条件即时生效。
 */
object HplaySearchState {

    /** 当前过滤条件（界面直接读它）。 */
    var filter by mutableStateOf(HplaySearchFilter())
        private set

    /** 上一次真正发出的 URL / 标题。 */
    var lastUrl by mutableStateOf("")
    var lastTitle by mutableStateOf("")

    /**
     * 改条件并触发重组。
     *
     * HplaySearchFilter 的字段都是 var，就地改完引用没变 —— Compose 察觉不到，
     * 必须 copy() 一次换引用（这也是它写成 data class 的原因）。
     */
    fun edit(block: (HplaySearchFilter) -> Unit) {
        val f = filter
        block(f)
        filter = f.copy()
    }

    fun setQuery(q: String) = edit { it.query = q }

    fun hasFilters(): Boolean = filter.hasFilters

    fun clearFilters() = edit { it.clearFilters() }

    /** 结果页标题（与列表页 ListContent 的 title 同口径）。 */
    fun title(): String {
        val f = filter
        val q = f.query.trim()
        val head = if (q.isEmpty()) "搜索" else "搜索:" + q
        return if (f.hasFilters) head + " (" + f.summary() + ")" else head
    }

    fun reset() {
        filter = HplaySearchFilter()
        lastUrl = ""
        lastTitle = ""
    }
}
