package com.han1me.checkin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.han1me.HplayWarning
import com.han1me.HplayOnPrimary
import com.han1me.HplayPrimary
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.AccentRed
import com.han1me.CardColor
import com.han1me.CardColorSoft
import com.han1me.TextDim
import com.han1me.TextFaint
import com.han1me.checkin.HplayCheckIn.Kind
import com.han1me.HplayOnSurface
import com.han1me.HplaySurfaceContainer
import com.han1me.HplaySurfaceLow

/**
 * **打卡报表**（照 Han1meViewer 的 DailyCheckInReport 复刻，对齐截图 5~8）。
 *
 * 参考源码：ui/screen/home/dailycheckin/DailyCheckInReport.kt。
 * 组成：
 *  · 顶栏：`← 打卡报表` + **年度 / 月度** 切换 + 导出图标（占位，不假装能导出）；
 *  · 时期切换：`« 2026年10月 »` / `« 2026年 »`；
 *  · **月度**：日历网格（有记录的按次数深浅着色）+ 类型统计卡 + 热力图例（少/多）；
 *  · **年度**：12 个月份格（每月打卡数）+ 三格统计（总次数 / 打卡天数 / 单日最高）+ 类型统计；
 *  · 类型统计：每个类型一行（图标 + 次数 + 名称）。
 *
 * 全部分口径来自 [HplayCheckIn.statsOf] / [HplayCheckIn.statsOfMonth] / [HplayCheckIn.statsOfYear]。
 */
@Composable
fun CheckInReport(onBack: () -> Unit, onOpenVideo: (String) -> Unit = {}) {
    var tick by remember { mutableStateOf(0) }
    var yearly by remember { mutableStateOf(false) }
    var monthCode by remember { mutableStateOf(HplayCheckIn.currentMonthCode()) }
    var year by remember { mutableStateOf(HplayCheckIn.today().take(4)) }
    val stats = remember(tick, yearly, monthCode, year) {
        if (yearly) HplayCheckIn.statsOfYear(year) else HplayCheckIn.statsOfMonth(monthCode)
    }
    var selectedDate by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(HplaySurfaceLow).verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
    ) {
        // 顶栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        ) {
            Text(
                "←", color = Color.White, fontSize = 19.sp, textAlign = TextAlign.Center,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp))
                    .background(HplaySurfaceContainer).clickable { onBack() }.padding(top = 3.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("打卡报表", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // 年度 / 月度（照截图的右上角两枚切换）
            Text(
                "年度", color = if (yearly) AccentRed else TextDim, fontSize = 13.sp,
                modifier = Modifier.clickable { yearly = true }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                "月度", color = if (!yearly) AccentRed else TextDim, fontSize = 13.sp,
                modifier = Modifier.clickable { yearly = false }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // 时期切换
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Text(
                "«", color = Color.White, fontSize = 18.sp,
                modifier = Modifier.clickable {
                    if (yearly) year = (year.toIntOrNull()?.minus(1) ?: 2026).toString()
                    else monthCode = HplayCheckIn.shiftMonth(monthCode, -1)
                }.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Text(
                if (yearly) "${year}年" else {
                    val y = monthCode.take(4); val m = monthCode.drop(5).trimStart('0')
                    "${y}年${m}月"
                },
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Text(
                "»", color = Color.White, fontSize = 18.sp,
                modifier = Modifier.clickable {
                    if (yearly) year = (year.toIntOrNull()?.plus(1) ?: 2026).toString()
                    else monthCode = HplayCheckIn.shiftMonth(monthCode, 1)
                }.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // 年度：12 个月格
        if (yearly) {
            Spacer(Modifier.height(6.dp))
            val yStats = remember(tick, year) { HplayCheckIn.yearRecords(year) }
            val byMonth = yStats.groupingBy { it.date.drop(5).take(2) }.eachCount()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..12).chunked(3).forEach { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        group.forEach { m ->
                            val mm = String.format("%02d", m)
                            val cnt = byMonth[mm] ?: 0
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                                    .background(if (cnt > 0) HplayPrimary.copy(alpha = 0.55f) else CardColor)
                                    .clickable { yearly = false; monthCode = "$year-$mm" }
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("${m}月", color = Color.White, fontSize = 12.5.sp)
                                Text(
                                    if (cnt > 0) "$cnt 次" else "—",
                                    color = if (cnt > 0) HplayOnPrimary else TextFaint, fontSize = 10.sp,
                                )
                            }
                        }
                        // 补齐最后一行
                        repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            // 月度：日历网格（按次数深浅）
            Spacer(Modifier.height(4.dp))
            val counts = remember(tick, monthCode) { HplayCheckIn.monthCounts(monthCode) }
            val maxCnt = (counts.values.maxOrNull() ?: 1).coerceAtLeast(1)
            val days = HplayCheckIn.daysInMonth(monthCode)
            val firstWd = HplayCheckIn.firstWeekdayOfMonth(monthCode)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Text(it, color = TextFaint, fontSize = 11.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
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
                        val isSel = d != null && d == selectedDate
                        Box(
                            Modifier.width(36.dp).height(36.dp).padding(1.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        cnt <= 0 -> Color.Transparent
                                        cnt == 1 -> HplayPrimary.copy(alpha = 0.18f)
                                        cnt <= maxCnt / 2 + 1 -> HplayPrimary.copy(alpha = 0.36f)
                                        else -> HplayPrimary.copy(alpha = 0.62f)
                                    },
                                )
                                .then(
                                    if (isSel) Modifier.border(1.5.dp, HplayWarning, RoundedCornerShape(6.dp))
                                    else Modifier,
                                )
                                .then(if (d != null) Modifier.clickable { selectedDate = d } else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (d != null) {
                                Text(
                                    d.takeLast(2).trimStart('0'),
                                    color = if (cnt > 0) Color.White else HplayOnSurface, fontSize = 11.5.sp,
                                )
                            }
                        }
                    }
                }
            }
            selectedDate?.let { d ->
                val rs = remember(tick, d) { HplayCheckIn.on(d) }
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardColor).padding(12.dp),
                ) {
                    Text(HplayCheckIn.fullLabelOf(d) + "（${rs.size} 次）", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (rs.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("这天没有记录", color = TextFaint, fontSize = 11.sp)
                    } else rs.forEach { r ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            r.time + "  " + r.kind.label +
                                (if (r.dishes.isEmpty()) "" else "　配菜：" + r.dishes.joinToString("、") { it.title }),
                            color = HplayOnSurface, fontSize = 11.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 热力图例（照截图的「少 ▢▢▢▢ 多」）
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Text("少", color = TextFaint, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                listOf(HplayPrimary.copy(alpha = 0.12f), HplayPrimary.copy(alpha = 0.28f), HplayPrimary.copy(alpha = 0.46f), HplayPrimary.copy(alpha = 0.68f)).forEach {
                    Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(it))
                    Spacer(Modifier.width(4.dp))
                }
                Text("多", color = TextFaint, fontSize = 10.sp)
            }
        }

        // ---- 统计格 ----
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ReportStat("📅", stats.totalCount, if (yearly) "总次数" else "总次数", Modifier.weight(1f))
            ReportStat("★", stats.daysChecked, "打卡天数", Modifier.weight(1f))
            ReportStat("♥", stats.maxDailyTypes, "单日最高", Modifier.weight(1f))
        }

        // ---- 类型统计（照截图的「28 自慰」卡） ----
        Spacer(Modifier.height(12.dp))
        if (stats.typeCounts.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("暂无打卡记录", color = TextFaint, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        } else {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardColor).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val top = stats.topType
                Text("🍆", fontSize = 18.sp)
                Text("${stats.totalCount}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(top?.label ?: "", color = TextDim, fontSize = 11.sp)
            }
        }

        // ---- 时段与配菜 ----
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ReportMini("🍽", "${stats.uniqueDishes}", "配菜种类", Modifier.weight(1f))
            ReportMini("🎯", "${stats.topDishCount}", "最爱配菜", Modifier.weight(1f))
            ReportMini("🦉", stats.dominantPeriod.ifBlank { "—" }, "主导时段", Modifier.weight(1f))
        }
        if (stats.topDish.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardColor).padding(12.dp),
            ) {
                Text("最爱配菜", color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stats.topDish + "　×${stats.topDishCount}",
                    color = Color.White, fontSize = 12.5.sp,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReportStat(glyph: String, value: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(CardColor).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, fontSize = 14.sp)
        Spacer(Modifier.height(3.dp))
        Text("$value", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextFaint, fontSize = 10.5.sp)
    }
}

@Composable
private fun ReportMini(glyph: String, value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(CardColor).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, fontSize = 15.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = TextFaint, fontSize = 9.5.sp, textAlign = TextAlign.Center)
    }
}
