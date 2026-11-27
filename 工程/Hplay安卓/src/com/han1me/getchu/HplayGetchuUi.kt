package com.han1me.getchu

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
 * **Getchu 新作预告页**（第 76 轮，照 Han1meViewer 的 GetchuPreviewScreen 一比一复刻）。
 *
 * 参考源码：`ui/screen/home/preview/getchupreview/GetchuPreviewScreen.kt`。
 * 顶栏结构（用户截图与源码一致）：
 *  · 左：`←` 返回按钮；
 *  · 中：标题「Getchu 2026/11 ▾」—— **点标题弹出下拉菜单**选月（±12 个月）；
 *  · 右：`‹` 上一月 / `›` 下一月 两个圆形箭头按钮。
 *
 * 正文：按**发售日分组**（日期小标题 + 商品卡：封面 / 标题 / 品牌 / 价格），
 * 点商品卡跳**原站详情页**（用系统浏览器打开 —— 用户要求「get 出跳转按钮，全面复刻本页面」）。
 *
 * 取不到时**如实显示原因**，并给「重试」。
 */
@Composable
fun GetchuPreviewContent(
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
    /**
     * ★ 第 342 轮（用户报「getchu 预告的内板块详情没有接线，点击卡片无响应」）：
     *
     * 原来卡片点击只走 [onOpenUrl] → 宿主的 webPage = WebPage(...)，也就是打开**内置网页浏览器**。
     * 而宿主那边 showGetchu 覆盖层是**后绘制**的（z 序在上），于是刚打开的网页立刻被
     * Getchu 列表盖住 —— 用户看到的就是「点了完全没反应」。
     *
     * 现在卡片默认进**内置详情页**（GetchuDetailContent），与「新番预告页」的行为一致；
     * 详情页里那个「转到网页」按钮仍然可以把原站页面丢给浏览器。
     */
    onOpenDetail: (String, String) -> Unit = { _, _ -> },
) {
    var monthCode by remember { mutableStateOf(HplayGetchu.currentMonthCode()) }
    var page by remember { mutableStateOf<HplayGetchu.Page?>(null) }
    var loading by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load(code: String) {
        monthCode = code
        loading = true
        scope.launch {
            val p = withContext(Dispatchers.IO) { HplayGetchu.load(code) }
            page = p
            loading = false
        }
    }

    LaunchedEffect(Unit) { load(HplayGetchu.currentMonthCode()) }

    Column(Modifier.fillMaxSize().background(HplaySurfaceLow)) {
        // ---------- 顶栏（照参考实现：返回 / 标题+下拉 / 左右箭头） ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // 返回
            Text(
                "←", color = Color.White, fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(19.dp))
                    .background(HplaySurfaceContainer).clickable { onBack() }.padding(top = 4.dp),
            )
            Spacer(Modifier.width(10.dp))
            // 标题 + ▾（点开下拉选月）
            Box(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { menuOpen = !menuOpen }.padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        "Getchu ${HplayGetchu.monthLabel(monthCode)}",
                        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("▾", color = Color.White, fontSize = 13.sp)
                }
            }
            // 上一月 / 下一月（照参考实现是两个圆形箭头按钮）
            Text(
                "‹", color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(19.dp))
                    .background(AccentRed).clickable {
                        load(HplayGetchu.shiftMonthCode(monthCode, -1))
                    }.padding(top = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "›", color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(19.dp))
                    .background(AccentRed).clickable {
                        load(HplayGetchu.shiftMonthCode(monthCode, 1))
                    }.padding(top = 2.dp),
            )
        }

        // 下拉选月（±12 个月，照参考实现 getchuMonthOptions）
        if (menuOpen) {
            Column(
                Modifier.fillMaxWidth(0.5f).heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(10.dp)).background(HplaySurfaceContainer)
                    .verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            ) {
                (-12..12).map { d -> HplayGetchu.shiftMonthCode(monthCode, d) }.forEach { opt ->
                    val on = opt == monthCode
                    Text(
                        HplayGetchu.monthLabel(opt),
                        color = if (on) AccentRed else HplayOnSurface, fontSize = 12.5.sp,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { menuOpen = false; load(opt) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // ---------- 正文 ----------
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 74.dp),
        ) {
            when {
                loading -> {
                    Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        Text("正在获取 ${HplayGetchu.monthLabel(monthCode)} 的预告…", color = TextDim, fontSize = 12.sp)
                    }
                }
                page == null || page!!.groups.isEmpty() -> {
                    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp)) {
                        Text(
                            page?.error?.ifBlank { null } ?: "这一月没有取得预告",
                            color = TextDim, fontSize = 12.5.sp, lineHeight = 19.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "重试", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(AccentRed)
                                .clickable { load(monthCode) }.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
                else -> {
                    page!!.groups.forEach { g ->
                        Spacer(Modifier.height(14.dp))
                        // 发售日小标题（照参考实现：分组标题）
                        Text(
                            g.releaseDate,
                            color = HplayOnSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
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
                                        it0.title,
                                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    )
                                    if (it0.brand.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("品牌：${it0.brand}", color = TextDim, fontSize = 11.sp)
                                    }
                                    if (it0.price.isNotBlank()) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(it0.price, color = TextFaint, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "打开详情 ›", color = AccentRed, fontSize = 11.5.sp,
                                        modifier = Modifier.clickable { onOpenDetail(it0.id, it0.title) },
                                    )
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