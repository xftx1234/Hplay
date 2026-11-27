package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.HplayEnv
import com.han1me.X99
import com.han1me.log.AppLog
import kotlinx.coroutines.delay

/**
 * **运行日志面板**（第 334 轮，挂在「设置 → 数据」）。
 *
 * ## 为什么必须做这一块
 *
 * 用户原话：「日志功能少了……数据板块没有日志，只有备份恢复」。
 *
 * 上一版 `AppLog` 的开关是 `X99.setting("x99.log.enabled")`，**界面上没有任何入口**
 * 能改这个键 —— 也就是说日志系统写好了但用户永远打不开，等于不存在。
 * 这个面板就是那个缺失的入口。
 *
 * ## 面板内容
 *
 * ① 总开关（持久化到 `x99.log.enabled`，重启后保持）；
 * ② 状态行：内存条数 / 累计写入 / 磁盘占用；
 * ③ 过滤器（全部 / 网络 / 操作 / 失败 / 播放 / 下载）—— 日志一多没过滤等于没有；
 * ④ 实时日志窗口（限高可滚动，每 800ms 刷新）；
 * ⑤ 操作：刷新 / 导出（分享面板）/ 清空内存 / 清空磁盘。
 *
 * ## 刷新策略
 *
 * 不用 StateFlow 而用 800ms 轮询的原因：AppLog 是**非 Compose 的进程级 object**，
 * 后台线程（下载、播放器、OkHttp 拦截器）都在写它。把它改成 SnapshotStateList
 * 会让每次写入都触发快照提交，而写入方常在非主线程 —— 那是第 331 轮踩过的坑
 *（UpdateService.State 就是因为这个才用 @Volatile 而不是 mutableStateOf）。
 * 轮询 800ms 对日志展示完全够用，且对业务零侵入。
 */
@Composable
fun HplayLogSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var enabled by remember { mutableStateOf(AppLog.isOn) }
    var filter by remember { mutableStateOf("all") }
    var lines by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(AppLog.size()) }
    var total by remember { mutableStateOf(AppLog.total()) }
    var disk by remember { mutableStateOf(AppLog.diskBytes()) }
    var tick by remember { mutableStateOf(0) }

    // 800ms 轮询（面板可见时才跑，离开组合自动停）
    LaunchedEffect(enabled, filter, tick) {
        while (true) {
            if (enabled) {
                val raw = AppLog.dump()
                lines = when (filter) {
                    "net" -> raw.lineSequence().filter { it.contains("[HTTP]") || it.contains("[NET]") || it.contains("[FAIL]") }.joinToString("\n")
                    "act" -> raw.lineSequence().filter { it.contains("[act]") }.joinToString("\n")
                    "fail" -> raw.lineSequence().filter { it.contains("[FAIL]") || it.contains("!!") }.joinToString("\n")
                    "player" -> raw.lineSequence().filter { it.contains("[player]") }.joinToString("\n")
                    "dl" -> raw.lineSequence().filter { it.contains("[dl]") }.joinToString("\n")
                    else -> raw
                }
                count = AppLog.size()
                total = AppLog.total()
                disk = AppLog.diskBytes()
            }
            delay(800)
        }
    }

    HplaySectionGroup("运行日志") {
        Column(Modifier.padding(14.dp)) {
            // ① 总开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("记录运行日志", color = cs.onSurface, fontSize = 14.sp)
                    Text(
                        "网络请求、页面操作、播放与下载事件。关闭时零开销。",
                        color = cs.onSurfaceVariant, fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        X99.putSetting("x99.log.enabled", if (on) "1" else "0")
                        AppLog.switch(on)
                        if (!on) { lines = ""; count = 0; total = 0 }
                    },
                )
            }

            if (enabled) {
                Spacer(Modifier.height(10.dp))
                // ② 状态行
                Text(
                    "内存 " + count + " 条 · 累计 " + total + " 条 · 磁盘 " + humanBytes(disk),
                    color = cs.onSurfaceVariant, fontSize = 10.5.sp,
                )
                Spacer(Modifier.height(8.dp))

                // ③ 过滤器
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((id, label) in listOf(
                        "all" to "全部", "net" to "网络", "act" to "操作",
                        "fail" to "失败", "player" to "播放", "dl" to "下载",
                    )) {
                        val on = filter == id
                        Text(
                            label,
                            color = if (on) cs.onPrimary else cs.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                .background(if (on) cs.primary else cs.surfaceContainerHigh)
                                .clickable { filter = id }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                // ④ 日志窗口
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.surfaceContainerLowest)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        if (lines.isBlank()) "（暂无日志，操作一下应用再看）" else lines,
                        color = if (lines.isBlank()) cs.onSurfaceVariant else cs.onSurface,
                        fontSize = 9.5.sp,
                        lineHeight = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(10.dp))

                // ⑤ 操作按钮
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LogBtn("刷新") { tick++ }
                    LogBtn("导出") {
                        val path = AppLog.exportToFile()
                        if (path != null) {
                            HplayEnv.shareFile(path, "text/plain")
                        } else {
                            HplayEnv.toast("导出失败（没有日志或存储不可写）")
                        }
                    }
                    LogBtn("清空内存") {
                        AppLog.clear()
                        lines = ""; count = 0; total = 0
                    }
                    LogBtn("清空磁盘") {
                        AppLog.clearDisk()
                        disk = 0L
                        HplayEnv.toast("磁盘日志已清空")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "日志同时写入应用外部私有目录的 logs/ 下（adb pull 可直接取，release 包也读得到）。",
                    color = cs.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun LogBtn(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        label,
        color = cs.onSurface,
        fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainerHigh)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private fun humanBytes(n: Long): String = when {
    n >= 1048576L -> "%.1f MB".format(n / 1048576.0)
    n >= 1024L -> "%.0f KB".format(n / 1024.0)
    else -> n.toString() + " B"
}