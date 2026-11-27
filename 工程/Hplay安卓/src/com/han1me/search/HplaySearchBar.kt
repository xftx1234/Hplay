package com.han1me.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.AccentRed
import com.han1me.CardColor
import com.han1me.CardColorSoft
import com.han1me.HplayOnSurface
import com.han1me.TextDim
import com.han1me.TextFaint
import com.han1me.BgColor

/**
 * 搜索页的统一底色（★ 第 351 轮）。
 *
 * **只在 GlobalSearchTab 根部画一次**，顶栏 / 条件条 / 聚合面板全部透明。
 * 为什么：搜索页本身是画在 app 根容器 `BgColor.copy(alpha = 0.72f)` 之上的**一层**，
 * 每一块再各叠一个 alpha 就会得到 1-(0.28×0.28)=0.92 这种「几乎实心」的结果，
 * 而各块的层数不一样（顶栏 1 层、面板 1 层、有的地方 2 层），
 * 于是同屏出现无法调平的色差。只画一层就没有「层数」这个变量。
 */
internal const val SEARCH_SCRIM_ALPHA = 0.72f

/** 搜索页底色（根容器同值）。 */
internal val SEARCH_SCRIM: androidx.compose.ui.graphics.Color
    @androidx.compose.runtime.Composable get() = BgColor.copy(alpha = SEARCH_SCRIM_ALPHA)

/**
 * **搜索顶栏 + 高级搜索入口 + 生效条件条**（第 350 轮）。
 *
 * 替换生成壳里原来的 `SearchRow(...)` —— 那一版只有「输入框 + 搜索 + 关闭」三个东西，
 * 没有高级搜索入口、没有生效条件回显，与参考实现的 SearchAppBar（SearchScreen.kt:478-568）
 * 差三样东西：**「進階」按钮**、**生效条件 chip 条**、**隐藏已观看开关**（最后这个本工程不适用）。
 *
 * 这里按参考实现补齐前两样，样式沿用本工程的胶囊语言。
 *
 * @param onSearch 点「搜索 / 回车」：外壳去发请求
 * @param onAdvancedSearch 高级条件改动后重搜（外壳同一条路）
 */
@Composable
fun HplaySearchBar(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    onAdvancedSearch: () -> Unit,
) {
    var showAdvanced by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            // ★★ 第 351 轮（用户报「搜索页面上下有透明度色差，修正同步为 app 原始半透明度」）：
            //
            // 原来这里是**不透明**的 CardColor。而搜索页的其余部分（最近搜索 / 结果 /
            // 聚合面板）都画在 app 根容器 `BgColor.copy(alpha = 0.72f)` 上，壁纸能透出来。
            // 于是同屏出现「顶部一块实心、下面半透明」的接缝 —— 就是用户看到的色差。
            //
            // 改成与根容器**同一个 alpha**（0.72），壁纸连续、没有接缝。
            // 数值集中放到 [SEARCH_SCRIM] 一个常量里，以后改一处全对。
            // ★★★ 第 351 轮（第二次修，用户报的色差**没有**因为改成 0.72 就消失）：
            //
            // 根因不是「顶栏 1.0、下面 0.72」这么简单：搜索页自身也是画在 app 根容器
            // `BgColor.copy(alpha = 0.72f)` 之上的**一层**，所以任何一块再叠 0.72，
            // 实际合成是 1-(0.28×0.28) = 0.92 —— 壁纸只剩 8% 透出，肉眼就是「一块实心」。
            // 而聚合面板叠 0.72、顶栏原来叠 1.0，三处各不相同，色差因此怎么调都对不上。
            //
            // 正确做法：**整页只在 GlobalSearchTab 根部画一层 0.72**，其余各层全透明。
            // 只有一层就没有「层数不同」这个变量，上下必然一致。
            Modifier.fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp))
                    .background(CardColorSoft).clickable { onBack() },
            ) { Text("←", color = HplayOnSurface, fontSize = 15.sp) }
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).height(42.dp)
                    .clip(RoundedCornerShape(21.dp)).background(CardColorSoft)
                    .padding(horizontal = 13.dp),
            ) {
                Text("🔍", fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    cursorBrush = SolidColor(AccentRed),
                    textStyle = TextStyle(color = HplayOnSurface, fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("搜索影片…", color = TextFaint, fontSize = 13.sp)
                        inner()
                    },
                )
                if (query.isNotEmpty()) {
                    Text(
                        "✕", color = TextDim, fontSize = 12.sp,
                        modifier = Modifier.clickable { onQuery("") }.padding(6.dp),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            SearchPill("搜索", onClick = { HplaySearchHistory.add(query); onSearch() })
            Spacer(Modifier.width(6.dp))
            // 「高级」：参考实现顶栏右侧那个文本按钮（SearchScreen.kt:557-565）
            Text(
                "高级",
                color = AccentRed,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    .background(AccentRed.copy(alpha = 0.14f))
                    .clickable { showAdvanced = true }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            )
        }

        // ---------- 生效条件 chip 条（参考实现 ActiveSearchCriteria）----------
        if (HplaySearchState.hasFilters()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val f = HplaySearchState.filter
                f.genre?.let { g -> ActiveChip("类型 " + g) { HplaySearchState.edit { it.genre = null }; onAdvancedSearch() } }
                f.sort?.let { s -> ActiveChip("排序 " + s) { HplaySearchState.edit { it.sort = null }; onAdvancedSearch() } }
                if (f.tags.isNotEmpty()) {
                    ActiveChip("标签 " + f.tags.size) { HplaySearchState.edit { it.tags = emptySet() }; onAdvancedSearch() }
                }
                f.dateText()?.let { d ->
                    ActiveChip("日期 " + d) {
                        HplaySearchState.edit { it.year = null; it.month = null; it.approx = null }
                        onAdvancedSearch()
                    }
                }
                f.duration?.let { du -> ActiveChip("时长 " + du) { HplaySearchState.edit { it.duration = null }; onAdvancedSearch() } }
                if (f.broad) ActiveChip("宽泛匹配") { HplaySearchState.edit { it.broad = false }; onAdvancedSearch() }
                ActiveChip("重设") { HplaySearchState.clearFilters(); onAdvancedSearch() }
            }
        }
    }

    if (showAdvanced) {
        HplayAdvancedSearchDialog(
            onDismiss = { showAdvanced = false },
            onSearch = { HplaySearchHistory.add(query); onAdvancedSearch() },
        )
    }
}

@Composable
private fun ActiveChip(text: String, onClick: () -> Unit) {
    Text(
        text + "  ✕",
        color = AccentRed,
        fontSize = 10.5.sp,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(11.dp))
            .background(AccentRed.copy(alpha = 0.14f))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/**
 * **最近搜索**（第 350 轮；参考实现 SearchScreen.kt:445-467 的未搜索态）。
 *
 * 外壳在「还没发起搜索」时渲染它，替代原来那个空空的聚合面板。
 */
@Composable
fun HplayRecentSearches(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var history by remember { mutableStateOf(HplaySearchHistory.list()) }
    // ★★★ 第 351 轮（用户圈图要求「去除搜索页面我标记处的文字，只保留下面的那个文字」）：
    //
    // 原来空历史时渲染 HplayEmpty（「还没有搜索记录」+ 一行说明），
    // 而它下面还有聚合面板自己的空态（「在上面的搜索框输入关键词，点「搜索」」）。
    // 两块空态上下叠在一起，同一件事说两遍，还各占半屏 —— 用户圈的就是上面那块。
    //
    // 现在：**没有历史就什么也不画**，把整块位置让给下面那块（它才是真正的指引）。
    // 有历史时仍然显示「最近搜索」列表（那是功能，不是空态）。
    if (history.isEmpty()) return
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text("最近搜索", color = HplayOnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "清空", color = TextDim, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(CardColorSoft)
                    .clickable { HplaySearchHistory.clear(); history = emptyList() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Column(Modifier.fillMaxWidth()) {
            history.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(q) }
                        .padding(horizontal = 6.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔍", fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        q, color = HplayOnSurface, fontSize = 12.5.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(
                        "✕", color = TextFaint, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(11.dp))
                            .clickable { HplaySearchHistory.remove(q); history = HplaySearchHistory.list() }
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}