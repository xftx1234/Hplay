package com.han1me.checkin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.AccentRed
import com.han1me.CardColor
import com.han1me.CardColorSoft
import com.han1me.TextDim
import com.han1me.TextFaint
import com.han1me.checkin.HplayCheckIn.Dish
import com.han1me.checkin.HplayCheckIn.Kind
import com.han1me.HplayOnSurface
import com.han1me.HplaySurfaceContainer
import com.han1me.HplaySurfaceLow

/**
 * **「您冲了吗」打卡页** —— 照 Han1meViewer 的 DailyCheckIn 一比一复刻（第 77 轮）。
 *
 * 对齐截图与参考源码的组成：
 *  · 顶部：日期卡（10月08日 星期四 / 今日尚未打卡）+ **打卡**按钮；
 *  · 三格统计：**本月打卡（天数）/ 共射（次数）/ 最高连续**；
 *  · **打卡日历**：`« 2026-07 »` 翻月 + 7 列日期格（有记录的显示 ×N，今天/选中高亮）；
 *  · **成就**（表现出色 / 连续达成 …，照 DailyCheckInAchievement）；
 *  · 四格小统计（配菜丰富 / 最爱配菜 / 时段 / 强撸灰飞烟灭，照参考实现的成就模型）；
 *  · 点日期 → **记录弹窗**（类型 chips + 自定义配菜 + 最近观看一键加配菜 + 感想）；
 *  · 长按日期 → 清除该日记录；
 *  · **打卡报表**（月视图 / 年视图，照 DailyCheckInReport）；
 *  · **漏卡提醒**（「忘记打卡了吗？」→ 不了谢谢 / 补上打卡）。
 *
 * 全部数据来自 [HplayCheckIn]（字段与参考实现 CheckInRecordEntity 对齐）。
 */
@Composable
fun CheckInContent(onBack: () -> Unit, onOpenVideo: (String) -> Unit = {}) {
    var tick by remember { mutableStateOf(0) }
    fun refresh() { tick++ }
    val today = HplayCheckIn.today()
    var monthCode by remember { mutableStateOf(HplayCheckIn.currentMonthCode()) }
    var selected by remember { mutableStateOf(today) }
    var showEditor by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var missing by remember(tick) { mutableStateOf(HplayCheckIn.firstMissingDay()) }

    val all = remember(tick) { HplayCheckIn.all() }
    val monthCounts = remember(tick, monthCode) { HplayCheckIn.monthCounts(monthCode) }
    val monthStats = remember(tick, monthCode) { HplayCheckIn.statsOfMonth(monthCode) }
    val todayCount = HplayCheckIn.countOn(today)

    if (showReport) {
        CheckInReport(onBack = { showReport = false }, onOpenVideo = onOpenVideo)
        return
    }

    Column(
        // ★ 第 79 轮修（用户要求「底栏设定为不要遮盖到任何页面」）：
        // 这个 Column 是 fillMaxSize，**外层 Box 的 padding 会被它撑满覆盖掉** ——
        // 让位必须加在内容自身（bottom = 74.dp 与底栏同高）。
        Modifier.fillMaxSize().background(HplaySurfaceLow).verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, bottom = 74.dp),
    ) {
        // ---------- 顶栏 ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        ) {
            // ★ 第 79 轮修（用户报「右上角加号偏移」）：
            // 原来用 `Text` + 固定 34dp 尺寸，再靠 `padding(top/bottom)` 手动挪字 ——
            // 不同字形的行高与基线不同，`＋`（全角）与 `←`/`📅` 的中轴对不齐，看起来就是「加号偏移」。
            // 改用 **Box 居中**：圆形背景 + 内容在正中，三种字形自动一致。
            CircleIcon("←", 19.sp, HplaySurfaceContainer) { onBack() }
            Spacer(Modifier.width(10.dp))
            Text("您冲了吗", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // 报表入口（照参考实现顶栏的日历图标）
            CircleIcon("📅", 15.sp, HplaySurfaceContainer) { showReport = true }
            Spacer(Modifier.width(8.dp))
            // 记录入口（照参考实现顶栏的「+」）
            CircleIcon("＋", 17.sp, AccentRed, Color.White) { selected = today; showEditor = true }
        }

        // ---------- 今日卡 ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(CardColor).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("📅", fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(HplayCheckIn.labelOf(today), color = Color.White, fontSize = 14.sp)
                Text(
                    if (todayCount > 0) "今日已打卡 $todayCount 次" else "今日尚未打卡",
                    color = if (todayCount > 0) Color(0xFF9AD8A0) else TextFaint,
                    fontSize = 11.sp,
                )
            }
            Text(
                if (todayCount > 0) "✓ 再记一次" else "✓ 打卡",
                color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(AccentRed)
                    // ★ 第 79 轮修（用户报的真 bug）：「点击打卡或者再记一次应该弹出图2的弹窗，',
                    //   现在是直接加数量了，没有弹窗」。
                    // 原来这里直接 addToday()（静默记一条），与右上角「＋」的行为不一致 ——
                    // 用户预期两个入口都进**记录弹窗**（那里才能选类型/配菜/最近观看/感想）。
                    .clickable {
                        selected = today
                        showEditor = true
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        // ---------- 三格统计（照截图的「本月打卡 / 共射 / 最高连续」） ----------
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("★", monthStats.daysChecked, "本月打卡", "天", Modifier.weight(1f))
            StatBox("♥", monthStats.totalCount, "共射", "次", Modifier.weight(1f))
            StatBox("♥", monthStats.bestStreak, "最高连续", "天", Modifier.weight(1f))
        }

        // ---------- 日历 ----------
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("打卡日历", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "«", color = Color.White, fontSize = 17.sp,
                modifier = Modifier.clickable {
                    monthCode = HplayCheckIn.shiftMonth(monthCode, -1)
                }.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text(monthCode, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "»", color = Color.White, fontSize = 17.sp,
                modifier = Modifier.clickable {
                    monthCode = HplayCheckIn.shiftMonth(monthCode, 1)
                }.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        CalendarGrid(
            monthCode = monthCode,
            counts = monthCounts,
            today = today,
            selected = selected,
            onPick = { d -> selected = d; showEditor = true },
            onLongPick = { d -> HplayCheckIn.removeAllOn(d); refresh() },
        )
        Spacer(Modifier.height(6.dp))
        Text("长按日期可清除打卡记录", color = TextFaint, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

        // ---------- 成就 ----------
        Spacer(Modifier.height(16.dp))
        Achievements(monthStats)

        // ---------- 类型分布条（照截图的「20 自慰」） ----------
        Spacer(Modifier.height(12.dp))
        monthStats.topType?.let { k ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardColor)
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🍆", fontSize = 18.sp)
                Text("${monthStats.totalCount}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(k.label, color = TextDim, fontSize = 11.sp)
            }
        }

        // ---------- 四格小统计 ----------
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MiniStat("🍽", "${monthStats.uniqueDishes}种", "配菜丰富", Modifier.weight(1f))
            MiniStat(
            "🎯", "${monthStats.topDishCount}次", "最爱配菜", Modifier.weight(1f))
            MiniStat("🦉", monthStats.dominantPeriod.ifBlank { "—" }, "夜猫子", Modifier.weight(1f))
            MiniStat("💥", "${monthStats.bestStreak}", "强撸灰飞烟灭", Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
    }

    // ---------- 漏卡提醒（照截图的「忘记打卡了吗？」） ----------
    missing?.let { d ->
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { missing = null }, contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth(0.84f).clip(RoundedCornerShape(16.dp)).background(HplaySurfaceLow)
                    .clickable(enabled = false) {}.padding(18.dp),
            ) {
                Text("忘记打卡了吗？", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    HplayCheckIn.labelOf(d) + " 的记录似乎是空的…\n你是忘记打卡了吗？😢",
                    color = HplayOnSurface, fontSize = 12.5.sp, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "不了谢谢", color = TextDim, fontSize = 12.5.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clickable { missing = null }.padding(vertical = 8.dp),
                    )
                    Text(
                        "补上打卡", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(AccentRed).clickable {
                                HplayCheckIn.add(d, HplayCheckIn.nowTime(), Kind.MASTURBATION)
                                missing = null
                                monthCode = HplayCheckIn.monthCodeOf(d)
                                refresh()
                            }.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }

    // ---------- 记录弹窗（点日期） ----------
    if (showEditor) {
        CheckInEditor(
            date = selected,
            onDismiss = { showEditor = false },
            onSaved = { refresh() },
            onOpenVideo = onOpenVideo,
        )
    }
}

// ---------------------------------------------------------------- 组件

@Composable
private fun StatBox(glyph: String, value: Int, label: String, unit: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(CardColor).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, color = AccentRed, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$value", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = TextDim, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp, start = 1.dp))
        }
        Text(label, color = TextFaint, fontSize = 10.5.sp)
    }
}

@Composable
private fun MiniStat(glyph: String, value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(CardColor).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = TextFaint, fontSize = 9.5.sp, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** 月历网格（照参考实现 CalendarGrid：7 列，周一起）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CalendarGrid(
    monthCode: String,
    counts: Map<String, Int>,
    today: String,
    selected: String,
    onPick: (String) -> Unit,
    onLongPick: (String) -> Unit,
) {
    val days = HplayCheckIn.daysInMonth(monthCode)
    val firstWd = HplayCheckIn.firstWeekdayOfMonth(monthCode) // 1=周一
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, color = TextFaint, fontSize = 11.sp, modifier = Modifier.width(38.dp), textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(6.dp))
        val cells = ArrayList<String?>()
        repeat(firstWd - 1) { cells.add(null) }
        for (d in 1..days) cells.add(String.format("%s-%02d", monthCode, d))
        while (cells.size % 7 != 0) cells.add(null)
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                week.forEach { d ->
                    val cnt = if (d == null) 0 else (counts[d] ?: 0)
                    val isToday = d != null && d == today
                    val isSel = d != null && d == selected
                    Box(
                        Modifier.width(38.dp).height(40.dp).padding(1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when {
                                    cnt > 0 -> Color(0xFF8B2E2E)
                                    isToday -> Color(0xFF2A2A30)
                                    else -> Color.Transparent
                                },
                            )
                            .then(
                                if (isSel || isToday) Modifier.border(1.5.dp, AccentRed, RoundedCornerShape(6.dp))
                                else Modifier,
                            )
                            // ★ 第 80 轮修（用户报：「冲了吗页长按日期不删除打卡记录」）：
                            // 这里原来只挂了 clickable —— **长按根本没实现**（onLongPick 传了却没人用），
                            // 所以「长按日期可清除打卡记录」那行提示是一句空话。
                            // 现在用 combinedClickable，真的把长按接上。
                            .then(
                                if (d != null) {
                                    Modifier.combinedClickable(
                                        onClick = { onPick(d) },
                                        onLongClick = { onLongPick(d) },
                                    )
                                } else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (d != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    d.takeLast(2).trimStart('0'),
                                    color = if (cnt > 0) Color.White else HplayOnSurface,
                                    fontSize = 12.sp,
                                )
                                if (cnt > 0) {
                                    Text("×$cnt", color = Color(0xFFFFC9C9), fontSize = 8.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 成就卡（照参考实现 DailyCheckInAchievement 的文案口径）。 */
@Composable
private fun Achievements(s: HplayCheckIn.Stats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.daysChecked >= 1) {
            AchievementCard("🚀", "表现出色", "本月已打卡 ${s.daysChecked} 天，太厉害了")
        }
        if (s.bestStreak >= 2) {
            AchievementCard("💪", "连续达成", "最长连续 ${s.bestStreak} 天！保持下去")
        }
        if (s.totalCount == 0) {
            AchievementCard("🌱", "开始记录", "本月还没有记录，从今天开始吧")
        }
    }
}

@Composable
private fun AchievementCard(glyph: String, title: String, desc: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A2E12)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, fontSize = 22.sp)
        Spacer(Modifier.height(4.dp))
        Text(title, color = Color(0xFFFFD98A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(desc, color = Color(0xFFD8C48F), fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

/**
 * 顶栏圆形图标钮（第 79 轮）。
 *
 * 为什么不用 `Text` + `padding(top/bottom)` 手动挪字：不同字形（←、📅、＋）的行高与基线不同，
 * 硬挪会让它们的中轴各偏一点（用户看到的就是「加号偏移」）。
 * 用 Box 做**几何居中**，任何字形都落在正中。
 */
@Composable
private fun CircleIcon(
    glyph: String,
    size: androidx.compose.ui.unit.TextUnit,
    bg: Color,
    fg: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = fg, fontSize = size)
    }
}