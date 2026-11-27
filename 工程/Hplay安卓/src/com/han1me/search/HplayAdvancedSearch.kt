package com.han1me.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
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

/**
 * **高级搜索弹窗**（第 350 轮，照 Han1meViewer 的 AdvancedSearchSheet 结构与交互）。
 *
 * ## 与参考实现的对应关系
 *
 * | 参考实现 | 这里 |
 * |---|---|
 * | ModalBottomSheet + 5 个 Chip（类型/排序/标签/发布日期/时长） | 同一个弹窗里的 5 个胶囊 + 5 个子弹窗 |
 * | Chip 单击 = 打开对应选择器 | 同 |
 * | Chip **长按 = 清除该项**（AdvancedSearchChip.kt:43-51 的 combinedClickable） | 同 |
 * | 底部 60dp 圆形搜索按钮 | 同（红底） |
 * | 组合历史（AdvancedSearchHistoryCard） | 本工程用最近搜索词代替（见 HplaySearchHistory） |
 *
 * 为什么是自绘弹窗而不是 ModalBottomSheet：见 HplaySearchUi.kt 的 SearchDialog 注释。
 *
 * @param onSearch 点「搜索」：上层把 filter 拼成 URL 去请求
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HplayAdvancedSearchDialog(
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
) {
    val f = HplaySearchState.filter
    // 子弹窗的开关（'genre' / 'sort' / 'tag' / 'date' / 'duration'）
    var open by remember { mutableStateOf<String?>(null) }

    SearchDialog(onDismiss = onDismiss) {
        Text("高级搜索", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "长按任一单元可清除该项；搜索只作用于 hanime 官方源。",
            color = TextFaint, fontSize = 10.5.sp, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(12.dp))

        // ---- 五个过滤器（FlowRow 两列，与参考实现的 maxItemsInEachRow = 2 一致）----
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilterCell(
                label = "类型",
                value = f.genre ?: "全部",
                active = f.genre != null && f.genre != "全部",
                onClick = { open = "genre" },
                onLongClick = { HplaySearchState.edit { it.genre = null } },
                modifier = Modifier.weight(1f),
            )
            FilterCell(
                label = "排序方式",
                value = f.sort ?: "默认",
                active = f.sort != null,
                onClick = { open = "sort" },
                onLongClick = { HplaySearchState.edit { it.sort = null } },
                modifier = Modifier.weight(1f),
            )
            FilterCell(
                label = "标签",
                value = if (f.tags.isEmpty()) "全部" else f.tags.size.toString() + " 个",
                active = f.tags.isNotEmpty(),
                onClick = { open = "tag" },
                onLongClick = { HplaySearchState.edit { it.tags = emptySet() } },
                modifier = Modifier.weight(1f),
            )
            FilterCell(
                label = "发布日期",
                value = f.dateText() ?: "全部",
                active = f.dateText() != null,
                onClick = { open = "date" },
                onLongClick = { HplaySearchState.edit { it.year = null; it.month = null; it.approx = null } },
                modifier = Modifier.weight(1f),
            )
            FilterCell(
                label = "影片时长",
                value = f.duration ?: "全部",
                active = f.duration != null,
                onClick = { open = "duration" },
                onLongClick = { HplaySearchState.edit { it.duration = null } },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))
        // 模糊搜索开关（参考实现在标签弹窗里，这里放外面更顺手；参数仍是 broad=on）
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("宽泛匹配", color = HplayOnSurface, fontSize = 12.5.sp)
                Text(
                    "开启后站点会放宽匹配（broad=on）",
                    color = TextFaint, fontSize = 10.sp,
                )
            }
            Text(
                if (f.broad) "已开" else "关闭",
                color = if (f.broad) Color.White else TextDim,
                fontSize = 11.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    .background(if (f.broad) AccentRed else CardColorSoft)
                    .clickable { HplaySearchState.edit { it.broad = !it.broad } }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            GhostPill("重设", onClick = { HplaySearchState.clearFilters() }, modifier = Modifier.weight(1f))
            GhostPill("取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            SearchPill("搜索", onClick = { onSearch(); onDismiss() }, modifier = Modifier.weight(1f))
        }
    }

    // ---- 五个子选择器 ----
    when (open) {
        "genre" -> SingleChoicePicker(
            title = "类型",
            options = HplaySearchOptions.GENRE,
            selectedKey = HplaySearchState.filter.genre,
            onDismiss = { open = null },
            onPick = { k -> HplaySearchState.edit { it.genre = k }; open = null },
        )
        "sort" -> SingleChoicePicker(
            title = "排序方式",
            options = HplaySearchOptions.SORT,
            selectedKey = HplaySearchState.filter.sort,
            onDismiss = { open = null },
            onPick = { k -> HplaySearchState.edit { it.sort = k }; open = null },
        )
        "duration" -> SingleChoicePicker(
            title = "影片时长",
            options = HplaySearchOptions.DURATION,
            selectedKey = HplaySearchState.filter.duration,
            onDismiss = { open = null },
            onPick = { k -> HplaySearchState.edit { it.duration = k }; open = null },
        )
        "tag" -> TagPicker(onDismiss = { open = null })
        "date" -> DatePicker(onDismiss = { open = null })
    }
}

/** 一个过滤器单元：标题 + 当前值；单击开选择器，长按清除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterCell(
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) AccentRed.copy(alpha = 0.18f) else CardColorSoft)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, color = TextFaint, fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = if (active) AccentRed else HplayOnSurface,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 单选选择器（类型 / 排序 / 时长共用）。 */
@Composable
private fun SingleChoicePicker(
    title: String,
    options: List<HplaySearchOption>,
    selectedKey: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    SearchDialog(onDismiss = onDismiss) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
        ) {
            options.forEach { o ->
                ChoiceRow(
                    label = o.label,
                    selected = (o.key ?: "") == (selectedKey ?: ""),
                    onClick = { onPick(o.key) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            GhostPill("取消", onClick = onDismiss, modifier = Modifier.weight(1f))
        }
    }
}

/** 标签多选（7 个分组横向切换，与参考实现的 HorizontalPager + 每页网格等价）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagPicker(onDismiss: () -> Unit) {
    var scope by remember { mutableStateOf(0) }
    // 弹窗内的草稿：确认才写回，取消不污染全局条件（参考实现也是这个语义）
    var picked by remember { mutableStateOf(HplaySearchState.filter.tags) }
    val group = HplaySearchOptions.TAGS[scope]

    SearchDialog(onDismiss = onDismiss) {
        Text("标签", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        // 分组切换条
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HplaySearchOptions.TAGS.forEachIndexed { i, s ->
                Text(
                    s.title,
                    color = if (i == scope) Color.White else TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (i == scope) AccentRed else CardColorSoft)
                        .clickable { scope = i }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState()),
        ) {
            group.options.forEach { o ->
                val k = o.key ?: return@forEach
                TagChip(
                    label = o.label,
                    selected = picked.contains(k),
                    onClick = {
                        picked = if (picked.contains(k)) picked - k else picked + k
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            GhostPill("重设", onClick = { picked = emptySet() }, modifier = Modifier.weight(1f))
            GhostPill("取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            SearchPill(
                "保存 " + picked.size,
                onClick = { HplaySearchState.edit { it.tags = picked }; onDismiss() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 发布日期选择器（参考实现的 AdvancedSearchReleaseDateDialog）。
 *
 * 两个页签：具体年月（滚轮式列表）/ 大致范围。两者互斥 ——
 * 选具体就清 approx，选大致就清 year/month（SearchViewModel.kt:251-260 同款语义）。
 */
@Composable
private fun DatePicker(onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(if (HplaySearchState.filter.approx != null) 1 else 0) }
    var year by remember { mutableStateOf(HplaySearchState.filter.year ?: HplaySearchOptions.YEAR_END) }
    var month by remember { mutableStateOf(HplaySearchState.filter.month ?: 1) }
    var yearOnly by remember { mutableStateOf(HplaySearchState.filter.year != null && HplaySearchState.filter.month == null) }
    var approx by remember { mutableStateOf(HplaySearchState.filter.approx) }

    SearchDialog(onDismiss = onDismiss) {
        Text("发布日期", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                "具体年月",
                color = if (tab == 0) Color.White else TextDim,
                fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    .background(if (tab == 0) AccentRed else CardColorSoft)
                    .clickable { tab = 0 }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(
                "大致范围",
                color = if (tab == 1) Color.White else TextDim,
                fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    .background(if (tab == 1) AccentRed else CardColorSoft)
                    .clickable { tab = 1 }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            if (tab == 0) {
                Text(
                    if (yearOnly) "仅年份" else "年 + 月",
                    color = TextDim, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                        .clickable { yearOnly = !yearOnly }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (tab == 0) {
            Row(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                LazyColumn(Modifier.weight(1f)) {
                    items((HplaySearchOptions.YEAR_START..HplaySearchOptions.YEAR_END).toList().reversed()) { y ->
                        ChoiceRow(label = y.toString() + " 年", selected = y == year, onClick = { year = y })
                    }
                }
                if (!yearOnly) {
                    Spacer(Modifier.width(8.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items((1..12).toList()) { m ->
                            ChoiceRow(label = m.toString() + " 月", selected = m == month, onClick = { month = m })
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                HplaySearchOptions.RELEASE_APPROX.forEach { o ->
                    ChoiceRow(
                        label = o.label,
                        selected = (o.key ?: "") == (approx ?: ""),
                        onClick = { approx = o.key },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            GhostPill(
                "清除",
                onClick = {
                    HplaySearchState.edit { it.year = null; it.month = null; it.approx = null }
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
            GhostPill("取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            SearchPill(
                "保存",
                onClick = {
                    HplaySearchState.edit {
                        if (tab == 0) {
                            it.year = year
                            it.month = if (yearOnly) null else month
                            it.approx = null
                        } else {
                            it.approx = approx
                            it.year = null
                            it.month = null
                        }
                    }
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}