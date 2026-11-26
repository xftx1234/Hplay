package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.AccentRed
import com.han1me.CardColorSoft
import com.han1me.TextDim
import com.han1me.TextFaint
import com.han1me.HplayOnSurface

/**
 * **通用翻页控件**（第 90 轮抽成公共组件）。
 *
 * 原来只有「本地下载」页有这个控件（private fun PageStepper 在 HplayDownloadsUi.kt 里），
 * 用户要求「收藏页的本地收藏板块也做一个翻页按钮，具体都和已下载视频页的一样就行」——
 * 与其复制一份，不如抽出来共用，保证两处**行为与外观天然一致**。
 *
 * 形态 `‹  1 / 6  ›`：到头时对应按钮置灰。
 */
@Composable
fun PageStepper(page: Int, pageCount: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(
            "‹",
            color = if (page > 0) HplayOnSurface else TextFaint,
            fontSize = 16.sp,
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (page > 0) CardColorSoft else Color.Transparent)
                .clickable(enabled = page > 0) { onPrev() }
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("${page + 1} / $pageCount", color = TextDim, fontSize = 11.5.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            "›",
            color = if (page < pageCount - 1) Color.White else TextFaint,
            fontSize = 16.sp,
            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (page < pageCount - 1) AccentRed else Color.Transparent)
                .clickable(enabled = page < pageCount - 1) { onNext() }
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}
