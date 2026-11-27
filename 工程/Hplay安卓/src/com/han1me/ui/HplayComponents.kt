package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **Hplay 通用组件库**（2026-11-25 第 278 轮 UI 重构）。
 *
 * 参照 Han1meViewer 的 `ui/component/` 与 Han1mePlus 的 `SettingsCardItem` 组织方式，
 * 把「每屏都要用」的控件统一到这里：
 *
 * | 组件 | 用途 |
 * |---|---|
 * | [HplaySectionTitle] | 区块标题 + 右侧「查看更多」 |
 * | [HplayChip]        | 标签 / 筛选项（胶囊） |
 * | [HplayCard]        | 通用卡片容器 |
 * | [HplaySettingRow]  | 设置项一行（图标 + 标题 + 副标题 + 右箭头） |
 * | [HplaySwitchRow]   | 设置项：开关 |
 * | [HplaySectionGroup]| 设置项分组容器（带小标题） |
 * | [HplayEmpty]       | 空态 / 错误态 |
 * | [HplayLoading]     | 转圈 |
 *
 * 全部走 `MaterialTheme.colorScheme.*`，自动跟随主题换肤。
 */

/** 区块标题：左侧大标题，右侧可选「查看更多」。 */
@Composable
fun HplaySectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                action,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(HplayDimens.radiusChip))
                    .clickable { onAction() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 胶囊标签 / 筛选项。 */
@Composable
fun HplayChip(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(HplayDimens.radiusChip))
            .background(if (selected) cs.primary else cs.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** 通用卡片容器。 */
@Composable
fun HplayCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(HplayDimens.radiusCard))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

/** 设置项一行：图标 + 标题 + 副标题 + 右侧箭头。 */
@Composable
fun HplaySettingRow(
    icon: String,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 17.sp, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = cs.onSurface, fontSize = 14.sp)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text("›", color = cs.outline, fontSize = 18.sp)
    }
}

/** 设置项：开关。 */
@Composable
fun HplaySwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    icon: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Text(icon, fontSize = 17.sp, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = cs.onSurface, fontSize = 14.sp)
            if (subtitle != null) {
                Text(subtitle, color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 设置项分组：小标题 + 一张卡片包住若干行。 */
@Composable
fun HplaySectionGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(bottom = HplayDimens.gap)) {
        Text(
            title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        HplayCard { content() }
    }
}

/** 空态 / 错误态。 */
@Composable
fun HplayEmpty(
    text: String,
    detail: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                color = MaterialTheme.colorScheme.outline,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/**
 * 加载中。
 *
 * ★ 第 349 轮（用户要求「所有页面的加载图标从转圈改成如图」）：
 * 转圈换成**小幅度跳动的角色图标**（见 ui/HplayLoadingMark.kt）。
 * 动画全部在 graphicsLayer 里读值，不触发重组 —— 换掉转圈的另一个原因是
 * CircularProgressIndicator 自己就是一条无限动画 + 每帧 Arc 重绘，两个一起跑反而更重。
 */
@Composable
fun HplayLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        HplayLoadMark(markSize = 34.dp)
    }
}