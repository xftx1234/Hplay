package com.han1me.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

/**
 * 搜索页里反复用到的小控件（第 350 轮）。
 *
 * 单独一个文件是因为 HplaySearchScreen.kt 已经很长，把「胶囊 / 圆角输入框 / 深色遮罩弹窗」
 * 这些原语抽出来，改配色时只动这里。
 */

/** 红底胶囊按钮（搜索页的主行动色，与全站 AccentRed 一致）。 */
@Composable
internal fun SearchPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Text(
        text,
        color = if (enabled) Color.White else TextDim,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) AccentRed else CardColorSoft)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 灰底胶囊（次要动作：取消 / 清除）。 */
@Composable
internal fun GhostPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        color = TextDim,
        fontSize = 12.5.sp,
        maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CardColorSoft)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * 深色遮罩 + 居中卡片（与下载页 DirChooser 同一套形态，不做第二套弹窗语言）。
 *
 * 为什么不用 material3 的 AlertDialog / ModalBottomSheet：
 * 那两者是这个工程里**唯一**还需要 @OptIn(ExperimentalMaterial3Api) 的 API，
 * 而无 Gradle 构建里多一个 opt-in 注解就多一处编译风险。自绘弹窗零风险且观感一致。
 */
@Composable
internal fun SearchDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // ★★★ 第 350 轮（真机实测修）：**必须用 Dialog，不能用 Box 叠在布局里**。
    //
    // 第一版写成 `Box(fillMaxSize)`，自认为"自绘弹窗零风险"。真机上是这样：
    //  · 高级搜索弹窗本身正常（它是调用点第一个兄弟，吃到了剩余高度）；
    //  · 但点「类型」打开的**二级选择器**排在它后面 ——
    //    在 Column 里只剩 132px 高（见 uiautomator 的 bounds 2001..2133），
    //    于是选择器被压成一条缝，用户看到的是"点了没反应"。
    //  · 而且它和一级弹窗共用同一份遮罩，两层 alpha 叠加，底色发灰。
    //
    // Dialog 是独立的窗口，几何完全不受调用点布局影响，二级弹窗还能正常盖在一级之上。
    // usePlatformDefaultWidth = false 是为了让遮罩铺满屏宽（默认会留 56dp 边）。
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth(0.92f).heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(18.dp)).background(CardColor)
                    .clickable { }.padding(16.dp),
                content = content,
            )
        }
    }
}

/** 单选列表项（照参考实现的 SettingChoiceItem：选中项用主色、右侧打勾）。 */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AccentRed.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) AccentRed else HplayOnSurface,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) Text("✓", color = AccentRed, fontSize = 13.sp)
    }
}

/** 可多选的标签小胶囊（选中填主色）。 */
@Composable
internal fun TagChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label,
        color = if (selected) Color.White else HplayOnSurface,
        fontSize = 11.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) AccentRed else CardColorSoft)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 7.dp),
    )
}