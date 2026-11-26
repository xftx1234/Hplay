package com.han1me.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javrocket.hanime.HanimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.han1me.HplayOnSurface
import com.han1me.HplayOnSurfaceVariant
import com.han1me.HplaySurfaceContainer
import com.han1me.HplayPrimary
import com.han1me.HplayErrorSoft
import com.han1me.HplaySuccess
import com.han1me.HplayWarning
/**
 * 边缘节点独立板块（实验分叉 2026-10-31）。
 *
 * 用户要求：
 *  · 像平板模式一样独立成板块（从网络设置的折叠里拿出来）；
 *  · 文字样式、图标样式照平板模式（标题+徽章+说明+右侧开关）；
 *  · 内容保持上游原版（测速、节点列表、自定义 Hosts）；**备用源已按用户要求移除**。
 */
@Composable
fun EdgeSection(ctx: android.content.Context) {
    // ★★ LABLIVE2（2026-10-31 修）：**严格单向同步**。
    //
    // 上一版用 remember(uiRev) { mutableStateOf(真实值) } 是不行的 ——
    // Compose 在 key 变化时会**新建** State，但如果这个 State 已被 remember 保存过，
    // 旧值仍会胜出（这是 remember 的语义），所以「开内置后边缘开关不会自动关」。
    //
    // 正确做法：用 LaunchedEffect 监听「真实值 + 全局版本号」，
    // 一旦真实值变了就**主动写回本地状态**，界面立刻跟上。
    var on by remember { mutableStateOf(HanimeClient.edgeHostsEnabled) }
    val uiRev = LabUi.rev
    LaunchedEffect(uiRev) {
        val real = HanimeClient.edgeHostsEnabled
        if (on != real) on = real
    }
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🛰 边缘节点", color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "内置 Hosts", color = HplayWarning, fontSize = 9.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF3A2E16)).padding(horizontal = 6.dp, vertical = 1.5.dp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    if (NodeStore.enabled()) "⚠ 内置节点已开启，边缘节点被锁定。要切换请先关闭内置节点。"
                    else "把 hanime 官方域名解析到 Cloudflare 边缘 IP，仅对官方源生效。点标题展开测速 / 节点列表。",
                    color = HplayOnSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            // ★★ LABLOCK（2026-10-31 · 用户明确要求）：
            //
            // 「点开了一个开关，另一个开关就**不可点**；等我把一个关掉，另一个才可点。」
            //
            // 所以这里是**禁用**而不是自动切换 —— 用户要自己手动切，
            // 软件不替他做决定（也不会出现「刚开的被悄悄关掉」这种事）。
            val lockedByBuiltin = NodeStore.enabled()
            Switch(
                checked = on,
                enabled = !lockedByBuiltin,
                onCheckedChange = { v ->
                    if (lockedByBuiltin) return@Switch
                    on = v
                    HanimeClient.updateEdgeHosts(v, HanimeClient.edgeCustomIps)
                    LabUi.bump()
                    com.han1me.HplayEnv.toast(
                        if (v) "边缘节点已开启（要切回内置请先关它）"
                        else "边缘节点已关闭（现在可以开启内置节点了）",
                    )
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = HplayPrimary,
                    uncheckedThumbColor = HplayOnSurfaceVariant,
                    uncheckedTrackColor = HplaySurfaceContainer,
                ),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            NodeEdgeBody(on)
        }
    }
}

@Composable
private fun NodeEdgeBody(on: Boolean) {
    val scope = rememberCoroutineScope()
    var custom by remember { mutableStateOf(HanimeClient.edgeCustomIps) }
    var node by remember { mutableStateOf(HanimeClient.edgeNode) }
    var delays by remember { mutableStateOf(HanimeClient.lastNodeTest) }
    var busy by remember { mutableStateOf(false) }

    // ★★ LABNOSUB（2026-10-31，用户要求）：**备用边缘源已彻底移除**（含手动切换按钮）。



    val nodes = HanimeClient.nodeCandidates()
    nodes.sortedBy { delays[it] ?: Long.MAX_VALUE / 2 }.forEach { ip ->
        val d = delays[ip]
        val sel = ip == node
        Text(
            (if (sel) "● " else "○ ") + ip + (if (d == null) "   …" else if (d < 0) "   不可用" else "   " + d + " ms"),
            color = when {
                sel -> Color.White
                d != null && d >= 0 -> HplaySuccess
                d != null -> HplayErrorSoft
                else -> HplayOnSurfaceVariant
            },
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth()
                .clickable { HanimeClient.setEdgeNode(ip); node = ip }
                .padding(vertical = 2.dp),
        )
    }
    Spacer(Modifier.height(6.dp))

    OutlinedTextField(
        value = custom,
        onValueChange = { custom = it },
        placeholder = { Text("自定义 Hosts（逗号分隔 IP，留空用内置节点）", fontSize = 11.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { HanimeClient.updateEdgeHosts(on, custom) }) {
        Text("应用自定义 Hosts")
    }
    Text(
        "说明：节点来自订阅源 sub.xdu.qzz.io（与隔壁网盘同源，每次打开程序自动拉取最新并测速选最快）；失败时回退内置节点。走代理时由代理解析域名，节点仅在直连时生效。",
        color = HplayOnSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp,
    )
}
