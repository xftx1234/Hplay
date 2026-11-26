package com.han1me.tablet

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.AccentRed
import com.han1me.CardColorSoft
import com.han1me.HplayEnv
import com.han1me.TextDim
import com.han1me.TextFaint
import com.han1me.X99
import com.han1me.HplayOnSurface
import com.han1me.HplayOnSurfaceVariant
import com.han1me.HplayWarning

/**
 * **平板模式**（第 76 轮，照 Han1meViewer 的 `tabletMode` 复刻，用户要求）。
 *
 * 参考源码：
 *  · `Preferences.kt` 的 `val tabletMode`（默认 **false**）
 *  · 设置项 `tablet_mode` / `tablet_mode_summary`（「开启后影片页面与首页横幅将以平板布局显示」）
 *  · 生效点：`ui/screen/Utils.kt` 的网格列数、`VideoRouteHostScreen` 的播放器高度、
 *    `SearchScreen` 的列数 —— 都是**按平板模式换布局**。
 *
 * ## 用户的四条明确要求（逐条落实）
 *  ① **放在设置里** —— 见 `HplayTabletSection()`；
 *  ② **实验性功能** —— 标题旁标注「实验性」，说明里写清；
 *  ③ **只有点击才会切换**（不自动开） —— 默认 false，只由用户拨动开关；
 *  ④ **只对平板生效** —— [isTabletDevice] 用官方口径（最小宽度 >= 600dp）判定；
 *     手机上即使打开了开关，[effective] 也是 false（不会影响手机排版）。
 */
object HplayTablet {

    const val KEY = "ui.tablet"

    private const val TABLET_MIN_WIDTH_DP = 600

    /** 用户在设置里拨的开关值（默认 false = 用户要求的「平常保持默认」）。 */
    fun enabled(): Boolean = X99.setting(KEY, "") == "1"

    fun setEnabled(v: Boolean) = X99.putSetting(KEY, if (v) "1" else "")

    /**
     * 这台设备**是不是平板**。
     *
     * 口径用 Android 官方的「最小宽度 dp」：>= 600dp 视为平板（手机通常 360~430dp）。
     * 这样「只对平板生效」是**设备事实**，与用户开关无关。
     */
    fun isTabletDevice(): Boolean = runCatching {
        val c = HplayEnv.requireContext()
        val cfg = c.resources.configuration
        cfg.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP
    }.getOrDefault(false)

    /**
     * **真正生效**的平板模式 = 开关打开 **且** 设备是平板。
     *
     * 布局取值一律用这个（而不是 [enabled]），这样手机上开了开关也不会打乱排版。
     */
    fun effective(): Boolean = enabled() && isTabletDevice()

    /** 给界面显示的状态说明（如实说明为何没生效）。 */
    fun statusText(): String = when {
        !isTabletDevice() -> "当前设备不是平板（最小宽度 < 600dp），开关不会改变排版"
        enabled() -> "已开启：影片页与网格按平板布局（更大观看区域）"
        else -> "未开启：保持手机布局"
    }
}

/**
 * 设置里的「平板模式」区块（照 Han1meViewer 的 SettingSwitchItem）。
 *
 * 放在设置面板内（用户要求「平板模式按钮也是放在设置里」），紧接「备份/恢复」之后。
 */
@Composable
fun HplayTabletSection() {
    var on by remember { mutableStateOf(HplayTablet.enabled()) }
    val isTablet = remember { HplayTablet.isTabletDevice() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📱 平板模式", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "实验性", color = HplayWarning, fontSize = 9.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF3A2E16)).padding(horizontal = 6.dp, vertical = 1.5.dp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "开启后影片页面与首页横幅将以平板布局显示，提供更大观看区域。" +
                        "默认关闭；只有平板设备才会生效。",
                    color = TextFaint, fontSize = 10.sp, lineHeight = 14.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = on,
                onCheckedChange = { v ->
                    on = v
                    HplayTablet.setEnabled(v)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentRed,
                    uncheckedThumbColor = HplayOnSurfaceVariant,
                    uncheckedTrackColor = CardColorSoft,
                ),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(HplayTablet.statusText(), color = TextDim, fontSize = 10.sp, lineHeight = 14.sp)
    }
}
