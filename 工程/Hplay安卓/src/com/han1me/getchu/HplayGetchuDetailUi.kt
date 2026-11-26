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
 * **Getchu 新番详情页**（第 77 轮，照 Han1meViewer 的 GetchuPreviewDetailScreen 复刻）。
 *
 * 参考源码：ui/screen/home/preview/getchupreview/GetchuPreviewDetailContent.kt。
 * 对齐用户截图 2/3 的组成：
 *  · 顶栏 `← Getchu 新番详情`；
 *  · **大封面**（16:9 裁切）+ 标题叠图；
 *  · **品牌 / 发布日期 / 价格** 三行信息；
 *  · **转到网页** 按钮（打开原站详情页）；
 *  · **商品介绍** / **故事** / **制作人员** 段落（照参考实现的标题映射与排序）；
 *  · 取不到时如实显示原因 + 重试。
 */
@Composable
fun GetchuDetailContent(
    id: String,
    onBack: () -> Unit,
    onOpenUrl: (String, String) -> Unit,
) {
    var detail by remember { mutableStateOf<HplayGetchu.Detail?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() {
        loading = true
        scope.launch {
            val d = withContext(Dispatchers.IO) { HplayGetchu.loadDetail(id) }
            detail = d
            loading = false
        }
    }
    LaunchedEffect(id) { load() }

    Column(Modifier.fillMaxSize().background(HplaySurfaceLow)) {
        // 顶栏
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
            Text("Getchu 新番详情", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 74.dp),
        ) {
            val d = detail
            when {
                loading -> {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text("正在获取详情…", color = TextDim, fontSize = 12.sp)
                    }
                }
                d == null -> Unit
                else -> {
                    // 大封面
                    Box(
                        Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(12.dp))
                            .background(HplaySurfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (d.cover.isNotBlank()) {
                            com.han1me.web.HplayThumb(d.cover, d.title, Modifier.fillMaxSize())
                        } else {
                            Text("🎬", color = HplayOutline, fontSize = 30.sp)
                        }
                    }
                    // 标题
                    if (d.title.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(d.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    // 信息三行（照截图：品牌 / 发布日期 / 价格）
                    Spacer(Modifier.height(10.dp))
                    if (d.brand.isNotBlank()) InfoLine("品牌", d.brand)
                    if (d.releaseDate.isNotBlank()) InfoLine("发布日期", d.releaseDate)
                    if (d.price.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(d.price, color = AccentRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    // 转到网页
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "↗ 转到网页", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(HplaySurfaceContainer)
                            .clickable { onOpenUrl(d.productUrl, d.title) }
                            .padding(vertical = 11.dp),
                    )
                    // 段落
                    if (d.sections.isNotEmpty()) {
                        d.sections.forEach { sec ->
                            Spacer(Modifier.height(18.dp))
                            Text(sec.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(7.dp))
                            Text(sec.body, color = HplayOnSurface, fontSize = 12.sp, lineHeight = 19.sp)
                        }
                    } else if (d.error.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(d.error, color = TextDim, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "重试", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(AccentRed)
                            .clickable { load() }.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label + "：", color = TextFaint, fontSize = 12.sp)
        Text(value, color = HplayOnSurface, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}