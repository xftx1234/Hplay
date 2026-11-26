package com.han1me.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.javrocket.hanime.HanimeClient
import com.han1me.HplayOnSurface
import com.han1me.HplayOnSurfaceVariant
import com.han1me.HplaySurfaceContainer
import com.han1me.HplaySurfaceLow
import com.han1me.HplayPrimary
import com.han1me.HplayErrorSoft
import com.han1me.HplayOutline
import com.han1me.HplaySuccess
import com.han1me.HplayWarning

/**
 * 内置节点面板（v2rayN 风格）+ 订阅源管理（实验分叉）。
 *
 * 用户需求累积：
 *  · 节点链接保存后显示成简短节点名，长链接进子页编辑；
 *  · 多节点、点名字热切换、每节点延迟 + 官方源可用性；
 *  · 订阅源栏：点切换显示（不换生效节点），**长按编辑/重新拉取/删除**；
 *  · 删除节点与订阅源**都要确认弹窗**。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NodeSection(ctx: android.content.Context) {
    var tick by remember { mutableStateOf(0) }
    var enabled by remember { mutableStateOf(NodeStore.enabled()) }
    var editing by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var showSub by remember { mutableStateOf(false) }
    var subMenuIndex by remember { mutableStateOf(-1) }
    var subMenuName by remember { mutableStateOf("") }
    var subMenuUrl by remember { mutableStateOf("") }
    var delSubIndex by remember { mutableStateOf(-1) }
    var delSubName by remember { mutableStateOf("") }

    // ★ LABLIVE：别人改了开关这里立刻跟上
    val uiRev = LabUi.rev
    LaunchedEffect(uiRev) {
        val real = NodeStore.enabled()
        if (enabled != real) enabled = real
    }

    // 显示哪组节点：currentSub >= 0 → 该订阅源的；否则用户手建列表
    val curSubIdx = remember(tick, uiRev) { NodeStore.currentSub() }
    val subList = remember(tick, uiRev) { NodeStore.subs() }
    val nodes = remember(tick, uiRev, curSubIdx) {
        val fromSub = subList.getOrNull(curSubIdx)?.nodes
        if (!fromSub.isNullOrEmpty()) fromSub else NodeStore.nodes()
    }
    val curUrl = remember(tick, uiRev) { NodeStore.currentNode()?.url ?: "" }

    Column(Modifier.fillMaxWidth()) {
        // ---------- 标题行 ----------
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("🛰 内置节点", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (enabled) {
                var pt by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(3000)
                        pt += 1
                    }
                }
                val pulse = pt
                Text(
                    "● " + NodeProbe.text(),
                    color = if (NodeProbe.ok) HplaySuccess else HplayOnSurfaceVariant,
                    fontSize = 10.5.sp,
                    modifier = Modifier.clickable { NodeProbe.probeNow() },
                )
            } else {
                Text("未启用", color = HplayOnSurfaceVariant, fontSize = 10.5.sp)
            }
            Spacer(Modifier.width(8.dp))
            // ★ 边缘节点开着时内置开关不可点（用户要求：互斥改为禁用）
            val lockedByEdge = HanimeClient.edgeHostsEnabled
            Switch(
                checked = enabled,
                enabled = !lockedByEdge,
                onCheckedChange = { v ->
                    if (lockedByEdge) return@Switch
                    enabled = v
                    if (v) {
                        NodeStore.setEnabled(true)
                        NodeBridge.applyAsync(ctx)
                    } else {
                        NodeStore.setEnabled(false)
                        NodeBridge.revokeAsync()
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = HplayPrimary,
                    uncheckedThumbColor = HplayOnSurfaceVariant,
                    uncheckedTrackColor = HplaySurfaceContainer,
                ),
            )
        }
        Spacer(Modifier.height(2.dp))
        if (HanimeClient.edgeHostsEnabled)
            Text("⚠ 边缘节点已开启，内置节点被锁定。要切换请先关闭边缘节点。",
                color = HplayWarning, fontSize = 10.sp, lineHeight = 14.sp)
        else
            Text("点节点名即热切换。右侧是延迟；「官」是官方源检测（● 可用 / ✕ 被 CF 拦）。",
                color = HplayOnSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(6.dp))

        // ---------- 节点列表 ----------
        nodes.forEachIndexed { i, n ->
            val sel = n.url == curUrl
            val d = if (sel && NodeProbe.ok) NodeProbe.latencyMs else NodeProbe.delayOf(n.url)
            val off = NodeProbe.officialOf(n.url)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (sel) HplaySurfaceContainer else Color(0x00000000))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    (if (sel) "● " else "○ ") + n.label,
                    color = if (sel) Color.White else HplayOnSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f).clickable(enabled = !busy) {
                        if (n.url != curUrl) {
                            busy = true
                            val subNodes = subList.getOrNull(curSubIdx)?.nodes
                            if (!subNodes.isNullOrEmpty()) {
                                NodeStore.setNodes(subNodes)
                                val idx = subNodes.indexOfFirst { it.url == n.url }
                                if (idx >= 0) NodeStore.setCurrentIndex(idx)
                            } else {
                                NodeStore.setCurrentIndex(i)
                            }
                            tick += 1
                            NodeBridge.hotSwitchAsync(ctx) { busy = false; tick += 1 }
                        }
                    },
                )
                Text(
                    when {
                        d == -2L -> "…"
                        d < 0L -> "超时"
                        else -> d.toString() + "ms"
                    },
                    color = when {
                        d == -2L -> HplayOnSurfaceVariant
                        d < 0L -> HplayErrorSoft
                        else -> HplaySuccess
                    },
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        off == -2L -> "官…"
                        off < 0L -> "官✕"
                        else -> "官●"
                    },
                    color = when {
                        off == -2L -> HplayOnSurfaceVariant
                        off < 0L -> HplayErrorSoft
                        else -> HplaySuccess
                    },
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text("改", color = HplayOnSurfaceVariant, fontSize = 10.5.sp,
                    modifier = Modifier.clickable { editIndex = i; editing = true })
            }
        }

        // ---------- 操作行 ----------
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { editIndex = -1; editing = true }) {
                Text("＋ 添加节点", color = HplayPrimary, fontSize = 11.sp)
            }
            TextButton(onClick = {
                // ★ 只测**当前显示的**节点（用户要求）
                NodeProbe.pingAll(nodes)
                NodeProbe.probeOfficial(nodes)
                tick += 1
            }) {
                Text("⚡ 测速", color = HplayOnSurfaceVariant, fontSize = 11.sp)
            }
            TextButton(onClick = { showSub = true }) {
                Text("🔗 订阅", color = Color(0xFF6FA8DC), fontSize = 11.sp)
            }
        }

        // ---------- 订阅源栏 ----------
        if (subList.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("订阅源（点切换显示 · 长按编辑）", color = HplayOnSurfaceVariant, fontSize = 10.sp)
            Spacer(Modifier.height(3.dp))
            subList.forEachIndexed { si, sub ->
                val curSub = curSubIdx == si
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
                        .background(if (curSub) Color(0xFF1E3A52) else Color(0x00000000))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        (if (curSub) "● " else "○ ") + sub.name + "（" + sub.nodes.size + "）",
                        color = if (curSub) Color(0xFF9FD0FF) else HplayOnSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f).combinedClickable(
                            onClick = {
                                NodeStore.activateSub(si)
                                tick += 1
                            },
                            onLongClick = {
                                subMenuIndex = si
                                subMenuName = sub.name
                                subMenuUrl = sub.url
                            },
                        ),
                    )
                    Text("✕", color = HplayOnSurfaceVariant, fontSize = 10.sp,
                        modifier = Modifier.clickable {
                            delSubIndex = si
                            delSubName = sub.name
                        }.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        // ---------- 生效范围 ----------
        Text("生效范围", color = HplayOnSurfaceVariant, fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NodeChip2("全局（含官方源）", NodeStore.scope() == "global") {
                NodeStore.setScope("global")
                NodeBridge.hotSwitchAsync(ctx) {}
                tick += 1
            }
            NodeChip2("只对非直连", NodeStore.scope() == "nonDirect") {
                NodeStore.setScope("nonDirect")
                NodeBridge.hotSwitchAsync(ctx) {}
                tick += 1
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(NodeState.lastApplied, color = HplayWarning, fontSize = 9.5.sp, lineHeight = 13.sp)
    }

    // ---------- 弹窗 ----------
    if (showSub) {
        SubDialog(
            onClose = { showSub = false },
            onDone = { showSub = false; tick += 1; NodeBridge.hotSwitchAsync(ctx) { tick += 1 } },
        )
    }

    if (subMenuIndex >= 0) {
        SubActionDialog(
            name = subMenuName,
            url = subMenuUrl,
            onClose = { subMenuIndex = -1 },
            onSave = { newName, newUrl, refetch ->
                val idx = subMenuIndex
                if (refetch) {
                    busy = true
                    Thread({
                        val r = SubFetcher.fetch(newUrl)
                        if (r.ok) {
                            NodeStore.updateSub(idx, newName.ifBlank { SubFetcher.nameForUrl(newUrl) }, newUrl.trim(), r.nodes)
                        } else {
                            NodeStore.updateSubMeta(idx, newName.ifBlank { SubFetcher.nameForUrl(newUrl) }, newUrl.trim())
                        }
                        busy = false
                        tick += 1
                    }, "hpla-sub-refetch").apply { isDaemon = true }.start()
                } else {
                    NodeStore.updateSubMeta(idx, newName.ifBlank { SubFetcher.nameForUrl(newUrl) }, newUrl.trim())
                    tick += 1
                }
                subMenuIndex = -1
            },
            canDelete = NodeStore.subs().getOrNull(subMenuIndex)?.let { s0 -> !NodeStore.isFixedSub(s0) } ?: true,
            onDelete = {
                delSubIndex = subMenuIndex
                delSubName = subMenuName
                subMenuIndex = -1
            },
        )
    }

    if (delSubIndex >= 0) {
        ConfirmDialog(
            title = "删除订阅源？",
            message = "「" + delSubName + "」及其 " +
                (NodeStore.subs().getOrNull(delSubIndex)?.nodes?.size ?: 0) +
                " 个节点将从列表移除。\n\n已下载的文件和收藏不受影响。",
            confirmText = "确认删除",
            onCancel = { delSubIndex = -1 },
            onConfirm = {
                NodeStore.removeSub(delSubIndex)
                delSubIndex = -1
                tick += 1
            },
        )
    }

    if (editing) {
        NodeEditDialog(
            index = editIndex,
            subIdx = curSubIdx,
            onClose = { editing = false },
            onSaved = {
                editing = false
                tick += 1
                NodeBridge.hotSwitchAsync(ctx) { tick += 1 }
            },
        )
    }
}

@Composable
private fun NodeChip2(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (on) Color.White else HplayOnSurfaceVariant, fontSize = 10.sp,
        modifier = Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (on) HplayPrimary else HplaySurfaceContainer)
            .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
/** 节点编辑子页（链接藏在这里，主面板只显示节点名）。 */
@Composable
private fun NodeEditDialog(
    index: Int,
    // 第 151 轮：>=0 表示「改/删的是这个订阅源里的节点」；-1 表示操作全局手建列表。
    // 用户要求「可自由在订阅源栏里新增节点」——否则新增只会进全局列表，在订阅源视图里根本看不到。
    subIdx: Int = -1,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val existing = remember {
        if (subIdx >= 0) NodeStore.subs().getOrNull(subIdx)?.nodes?.getOrNull(index)
        else NodeStore.nodes().getOrNull(index)
    }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: NodeStore.DEFAULT_NODE_URL) }
    var msg by remember { mutableStateOf("") }
    var showDelConfirm by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { onClose() }) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.86f).heightIn(max = 480.dp)
                .clip(RoundedCornerShape(14.dp)).background(HplaySurfaceLow)
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(if (index < 0) "添加节点" else "编辑节点",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("名称（留空自动从链接取）", color = HplayOnSurfaceVariant, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(HplaySurfaceContainer).padding(10.dp)) {
                BasicTextField(value = name, onValueChange = { name = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = HplayOnSurface, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            Text("节点链接（vless://…）", color = HplayOnSurfaceVariant, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(RoundedCornerShape(8.dp))
                .background(HplaySurfaceContainer).padding(10.dp)) {
                BasicTextField(value = url, onValueChange = { url = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = HplayOnSurface, fontSize = 10.sp, lineHeight = 14.sp),
                    modifier = Modifier.fillMaxWidth())
            }
            if (msg.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(msg, color = HplayWarning, fontSize = 10.5.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onClose) {
                    Text("取消", color = HplayOnSurfaceVariant, fontSize = 12.sp)
                }
                if (index >= 0) {
                    // ★ 删除节点必须确认
                    TextButton(onClick = { showDelConfirm = true }) {
                        Text("删除", color = HplayErrorSoft, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "保存", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(HplayPrimary)
                        .clickable {
                            if (VlessLink.parse(url) == null) {
                                msg = "链接不合法：需 vless:// 开头（端口后带斜杠也支持）"
                            } else {
                                if (index < 0) {
                                    // 新增：进「当前查看的那组」——订阅源视图就进该订阅源
                                    if (subIdx >= 0) NodeStore.addNodeToSub(subIdx, url, name.trim())
                                    else NodeStore.addNode(url, name.trim())
                                } else {
                                    if (subIdx >= 0) NodeStore.updateNodeInSub(subIdx, index, url, name.trim())
                                    else NodeStore.updateNode(index, url, name.trim())
                                }
                                onSaved()
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }
        }
    }

    if (showDelConfirm) {
        ConfirmDialog(
            title = "删除这个节点？",
            message = "「" + (existing?.let { it.name.ifBlank { it.url.take(30) } } ?: "该节点") +
                "」将从列表移除。\n\n已下载的文件和收藏不受影响。",
            confirmText = "确认删除",
            onCancel = { showDelConfirm = false },
            onConfirm = {
                showDelConfirm = false
                if (subIdx >= 0) NodeStore.removeNodeInSub(subIdx, index) else NodeStore.removeAt(index)
                onSaved()
            },
        )
    }
}

/** 订阅链接弹窗（拉取新订阅）。 */
@Composable
private fun SubDialog(onClose: () -> Unit, onDone: () -> Unit) {
    var url by remember { mutableStateOf(NodeStore.subUrl()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { onClose() }) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.88f).heightIn(max = 420.dp)
                .clip(RoundedCornerShape(14.dp)).background(HplaySurfaceLow)
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text("订阅链接", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("粘贴机场订阅地址，拉回后自动追加到节点列表（不会覆盖已有节点）。",
                color = HplayOnSurfaceVariant, fontSize = 10.5.sp, lineHeight = 15.sp)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(8.dp))
                .background(HplaySurfaceContainer).padding(10.dp)) {
                BasicTextField(value = url, onValueChange = { url = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = HplayOnSurface, fontSize = 10.5.sp, lineHeight = 15.sp),
                    modifier = Modifier.fillMaxWidth())
            }
            if (msg.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(msg, color = HplayWarning, fontSize = 10.5.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onClose) {
                    Text("取消", color = HplayOnSurfaceVariant, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (busy) "拉取中…" else "拉取并添加",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (busy) HplayOutline else Color(0xFF2F5D8A))
                        .clickable(enabled = !busy) {
                            busy = true
                            msg = "正在拉取…"
                            Thread({
                                val r = SubFetcher.fetch(url)
                                if (r.ok) {
                                    NodeStore.setSubUrl(url.trim())
                                    NodeStore.upsertSub("", url.trim(), r.nodes)
                                    var added = 0
                                    for (nd in r.nodes) { if (NodeStore.addNode(nd.url, nd.name) >= 0) added += 1 }
                                    msg = "✅ " + r.message + "（已追加 " + added + " 个）"
                                } else {
                                    msg = "❌ " + r.message
                                }
                                busy = false
                                if (r.ok) Thread.sleep(600)
                            }, "hpla-sub-fetch").apply { isDaemon = true }.start()
                        }
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 订阅源操作菜单（长按订阅源弹出）。 */
@Composable
private fun SubActionDialog(
    name: String,
    url: String,
    onClose: () -> Unit,
    onSave: (newName: String, newUrl: String, refetch: Boolean) -> Unit,
    onDelete: () -> Unit,
    // 第 148 轮：固定的「内置默认节点」订阅源不可删除 -> 传 false 隐藏删除按钮
    canDelete: Boolean = true,
) {
    var n by remember { mutableStateOf(name) }
    var u by remember { mutableStateOf(url) }

    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { onClose() }) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.88f).heightIn(max = 460.dp)
                .clip(RoundedCornerShape(14.dp)).background(HplaySurfaceLow)
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text("编辑订阅源", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("名称", color = HplayOnSurfaceVariant, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(HplaySurfaceContainer).padding(10.dp)) {
                BasicTextField(value = n, onValueChange = { n = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = HplayOnSurface, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            Text("订阅链接", color = HplayOnSurfaceVariant, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 70.dp).clip(RoundedCornerShape(8.dp))
                .background(HplaySurfaceContainer).padding(10.dp)) {
                BasicTextField(value = u, onValueChange = { u = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = HplayOnSurface, fontSize = 10.sp, lineHeight = 14.sp),
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (canDelete) {
                    Text("删除", color = HplayErrorSoft, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF3A2020))
                            .clickable { onDelete() }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("只保存", color = HplayOnSurfaceVariant, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(HplaySurfaceContainer)
                        .clickable { onSave(n.trim(), u.trim(), false) }
                        .padding(horizontal = 12.dp, vertical = 7.dp))
                Text("保存并重新拉取", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF2F5D8A))
                        .clickable { onSave(n.trim(), u.trim(), true) }
                        .padding(horizontal = 12.dp, vertical = 7.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("「保存并重新拉取」会用新链接重新下载节点；拉取失败时保留原有节点，只改名/改链接。",
                color = HplayOnSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

/** 通用确认弹窗。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { onCancel() }) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.82f)
                .clip(RoundedCornerShape(14.dp)).background(HplaySurfaceLow)
                .clickable(enabled = false) {}.padding(18.dp),
        ) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(message, color = HplayOnSurfaceVariant, fontSize = 11.5.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel) {
                    Text("取消", color = HplayOnSurfaceVariant, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(confirmText, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(HplayPrimary)
                        .clickable { onConfirm() }
                        .padding(horizontal = 18.dp, vertical = 8.dp))
            }
        }
    }
}