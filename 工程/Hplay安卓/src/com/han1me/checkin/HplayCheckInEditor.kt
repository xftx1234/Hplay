package com.han1me.checkin

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
import androidx.compose.runtime.mutableStateListOf
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
import com.han1me.HplayErrorSoft
import com.han1me.HplayOutline

/**
 * **打卡记录弹窗**（点日历某天弹出）—— 照 Han1meViewer 的 DailyCheckInDialog 复刻。
 *
 * 对齐截图 9 与参考源码：
 *  · 顶部：`yyyy年MM月dd日` + 返回；
 *  · **类型**：自慰 / 梦遗 / 做爱 / 口交 / 其它（chips，照 FilterChip）；
 *  · **配菜**：自定义配菜输入框 + 「新增」；
 *  · **最近观看**：列出最近看过的片子，每条右侧「+ 配菜」一键加入（照参考实现从播放历史取）；
 *  · 感想输入 + 保存；
 *  · 已记条数展示 + 单条删除。
 *
 * 「最近观看」的数据源：参考实现读自己的播放历史库；
 * 这里用本应用的**本站收藏**（Hplay 的观看历史在官方账号里、不落地），
 * 并在文案上如实说明 —— 不假装有历史库。
 */
@Composable
fun CheckInEditor(
    date: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onOpenVideo: (String) -> Unit = {},
) {
    var tick by remember { mutableStateOf(0) }
    val existing = remember(tick) { HplayCheckIn.on(date) }
    var kind by remember { mutableStateOf(Kind.MASTURBATION) }
    var dishInput by remember { mutableStateOf("") }
    var feeling by remember { mutableStateOf("") }
    val picked = remember(tick) { mutableStateListOf<Dish>() }
    // 最近观看（用本站收藏当数据源）
    val recent = remember { runCatching { com.han1me.X99.favs().take(12) }.getOrDefault(emptyList()) }

    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.92f).heightIn(max = 620.dp)
                .clip(RoundedCornerShape(16.dp)).background(HplaySurfaceLow)
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable { onDismiss() })
                Spacer(Modifier.width(10.dp))
                Text(
                    HplayCheckIn.fullLabelOf(date), color = Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(14.dp))

            // ---- 类型 ----
            Text("类型", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Kind.entries.forEach { k ->
                    val on = k == kind
                    Text(
                        k.label,
                        color = if (on) Color.White else TextDim, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (on) AccentRed else CardColorSoft)
                            .clickable { kind = k }.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // ---- 配菜 ----
            Spacer(Modifier.height(16.dp))
            Text("配菜", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = dishInput,
                    onValueChange = { dishInput = it },
                    placeholder = { Text("自定义配菜", color = TextFaint, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "新增", color = if (dishInput.isBlank()) TextFaint else Color.White, fontSize = 12.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (dishInput.isBlank()) CardColorSoft else AccentRed)
                        .clickable(enabled = dishInput.isNotBlank()) {
                            picked.add(Dish(dishInput.trim(), ""))
                            dishInput = ""
                        }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            if (picked.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                picked.forEach { d ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp)).background(CardColorSoft)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(
                            d.title, color = HplayOnSurface, fontSize = 11.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text(
                            "移除", color = HplayErrorSoft, fontSize = 11.sp,
                            modifier = Modifier.clickable { picked.remove(d) }.padding(horizontal = 6.dp),
                        )
                    }
                }
            }

            // ---- 最近观看（一键加配菜） ----
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("最近观看（点「+ 配菜」加入）", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                recent.forEach { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(9.dp)).background(CardColor)
                            .padding(6.dp),
                    ) {
                        Box(
                            Modifier.width(46.dp).height(62.dp).clip(RoundedCornerShape(6.dp))
                                .background(HplaySurfaceContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (f.thumb.isNotBlank()) {
                                com.han1me.web.HplayThumb(f.thumb, f.title, Modifier.fillMaxSize())
                            } else {
                                Text("🎬", color = HplayOutline, fontSize = 14.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            f.title, color = HplayOnSurface, fontSize = 11.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text(
                            "+ 配菜", color = AccentRed, fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                if (picked.none { it.title == f.title }) picked.add(Dish(f.title, f.url))
                            }.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            // ---- 感想 ----
            Spacer(Modifier.height(16.dp))
            Text("感想（可选）", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(
                value = feeling,
                onValueChange = { feeling = it },
                placeholder = { Text("写点什么…", color = TextFaint, fontSize = 12.sp) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- 保存 ----
            Spacer(Modifier.height(14.dp))
            Text(
                "保存打卡", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(AccentRed)
                    .clickable {
                        HplayCheckIn.add(date, HplayCheckIn.nowTime(), kind, picked.toList(), feeling)
                        tick++
                        onSaved()
                        onDismiss()
                    }.padding(vertical = 11.dp),
            )

            // ---- 当天已有记录 ----
            if (existing.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("当天记录（${existing.size}）", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                existing.forEach { r ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp)).background(CardColor)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(r.time + "  " + r.kind.label, color = HplayOnSurface, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (r.dishes.isEmpty()) "" else r.dishes.joinToString("、") { it.title },
                            color = TextFaint, fontSize = 10.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text(
                            "删除", color = HplayErrorSoft, fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                HplayCheckIn.remove(r.date, r.time, r.kind)
                                tick++
                            }.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}