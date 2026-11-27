package com.han1me.search

/**
 * **搜索历史的本地存储**（第 350 轮）。
 *
 * 为什么自己存而不是抄参考实现的 Room：
 *  参考实现有整套 Room（SearchHistoryDao + HistoryDatabase），本工程没有 Room，
 *  为一份「最近搜过的词」引一个数据库不划算。这里用 X99 的设置文件存一行文本，
 *  分隔符用 U+0001（不可能出现在关键词里），顺序即时间序（最新的在前）。
 *
 * 容量：保留 30 条。参考实现列表页只显示 10 条（SearchScreen.kt:258），
 * 多留一些给「输入时联想」用。
 */
object HplaySearchHistory {

    private const val KEY = "search.recent"
    private const val SEP = "\u0001"
    private const val MAX = 30

    fun list(): List<String> =
        runCatching { com.han1me.X99.setting(KEY, "") }
            .getOrDefault("")
            .split(SEP)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX)

    /** 记一次搜索（去重后放最前）。 */
    fun add(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        val next = (listOf(t) + list().filter { it != t }).take(MAX)
        runCatching { com.han1me.X99.putSetting(KEY, next.joinToString(SEP)) }
    }

    fun remove(q: String) {
        val next = list().filter { it != q }
        runCatching { com.han1me.X99.putSetting(KEY, next.joinToString(SEP)) }
    }

    fun clear() {
        runCatching { com.han1me.X99.putSetting(KEY, "") }
    }

    /** 输入联想：包含关键词的前 N 条。 */
    fun suggest(keyword: String, limit: Int = 10): List<String> {
        val k = keyword.trim()
        val all = list()
        if (k.isEmpty()) return all.take(limit)
        return all.filter { it.contains(k, true) }.take(limit)
    }
}
