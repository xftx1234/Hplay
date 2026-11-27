package com.han1me.search

import java.net.URLEncoder

/**
 * **高级搜索的过滤条件 + 真实请求 URL 组装**（第 350 轮，照 Han1meViewer 的 SearchViewModel）。
 *
 * ## 参数表（逐条对着参考实现抄，别凭感觉改）
 *
 * 站点是 `https://hanime1.me/search?...`，参考实现 HanimeBaseService.kt:25-38 的 9 个 @Query：
 *
 * | 参数 | 取值 | 说明 |
 * |---|---|---|
 * | `page` | 1..N | 由列表加载器追加，这里不拼 |
 * | `query` | 任意 | 关键词 |
 * | `genre` | genre.json 的 search_key | 「全部」也是一个**真实值**，不是省略 |
 * | `sort` | sort_option.json 的 search_key | 不选就不拼 |
 * | `broad` | 魔法串 **`on`** | 不是 true/1 |
 * | `date` | `過去 1 週` 或 `2024 年` / `2024 年 5 月` | 中间是空格，不是「年5月」 |
 * | `duration` | duration.json 的 search_key | 全角/半角空格照抄 |
 * | `tags[]` | 重复键 | `tags[]=A&tags[]=B` |
 *
 * ★ 一个坑：**年份/月份不直传**，必须拼进 `date`（SearchViewModel.getSearchDate，
 * SearchViewModel.kt:234-243）。直传 year=/month= 站点不认，结果会退化成「无过滤」——
 * 用户看到的是「选了年份但结果没变」。
 *
 * ★ 另一个坑：`search_key = null` 才是「不筛」，空串会被拼成 `genre=`，
 * 站点会把它当成一个空值条件（返回空列表）。所以拼接时**空串一律跳过**。
 */
data class HplaySearchFilter(
    var query: String = "",
    var genre: String? = null,
    var sort: String? = null,
    var duration: String? = null,
    /** 具体年月（与 [approx] 互斥，谁非空用谁）。 */
    var year: Int? = null,
    var month: Int? = null,
    /** 大致范围，如「過去 1 週」。 */
    var approx: String? = null,
    /** 标签（存 search_key，来自 7 个分组里的任意一个）。 */
    var tags: Set<String> = emptySet(),
    /** 模糊搜索（宽泛匹配）。 */
    var broad: Boolean = false,
) {
    /** 有没有任何过滤（决定界面要不要显示「已生效条件」那一条）。 */
    val hasFilters: Boolean
        get() = genre != null || sort != null || duration != null ||
            year != null || approx != null || tags.isNotEmpty() || broad

    /** 人话摘要（给「高级搜索」按钮下方那行用）。 */
    fun summary(): String {
        val parts = ArrayList<String>()
        genre?.let { parts.add(it) }
        sort?.let { parts.add(it) }
        if (tags.isNotEmpty()) parts.add("标签 " + tags.size)
        dateText()?.let { parts.add(it) }
        duration?.let { parts.add(it) }
        if (broad) parts.add("模糊")
        return if (parts.isEmpty()) "未设置过滤" else parts.joinToString(" · ")
    }

    /** `date` 参数（参考实现的 getSearchDate）。 */
    fun dateText(): String? = when {
        approx != null -> approx
        year != null -> listOfNotNull(year?.let { "$it 年" }, month?.let { "$it 月" }).joinToString(" ")
        else -> null
    }

    /**
     * 拼成站点真实 URL。
     *
     * @param base 站点根（官方源 `https://hanime1.me`，或镜像 origin）
     */
    fun buildUrl(base: String): String {
        val sb = StringBuilder(base.trimEnd('/')).append("/search?")
        val q = ArrayList<String>()
        fun add(k: String, v: String?) {
            if (v.isNullOrBlank()) return          // 空串绝不能拼（见类注释的第二个坑）
            q.add(k + "=" + enc(v))
        }
        add("query", query.trim().ifBlank { null })
        add("genre", genre)
        add("sort", sort)
        add("broad", if (broad) "on" else null)   // 魔法串 on
        add("date", dateText())
        add("duration", duration)
        // tags[] 是重复键：tags[]=A&tags[]=B（参考实现用 Set<String> 的 @Query("tags[]")）
        for (t in tags) if (t.isNotBlank()) q.add("tags%5B%5D=" + enc(t))
        sb.append(q.joinToString("&"))
        return sb.toString()
    }

    /** 清掉全部过滤（保留关键词）——「重设」按钮用。 */
    fun clearFilters(): HplaySearchFilter {
        genre = null; sort = null; duration = null
        year = null; month = null; approx = null
        tags = emptySet(); broad = false
        return this
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
