package com.han1me.update

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.AccentRed
import com.han1me.CardColor
import com.han1me.CardColorSoft
import com.han1me.HplayEnv
import com.han1me.TextDim
import com.han1me.TextFaint
import kotlinx.coroutines.launch
import com.han1me.HplayOnSurface
import com.han1me.HplayWarning

/**
 * 「检查更新」面板（第 47 轮；第 331 轮大改）。
 *
 * ## 四条渠道 · 两条发布线（第 331 轮，用户要求）
 *
 * 用户原话：「检查更新拿 github 作为备用渠道2，可在更新页选择 github 更新，
 * 实验版本和主线版分开更新，实验版单一个分支仓库，而且 app 检查更新内新增 cl(CI)
 * 更新渠道，可选实验版和主线版自由更新」。
 *
 * 于是这个面板顶部有两排选择器：
 *  · **发布线**：实验版（lab）/ 主线版（stable）
 *  · **渠道**：GitHub Releases（默认）/ GitHub CI 构建 / 自建网盘（垫底）
 *
 * 两者都是**用户选择持久化**的（存在 X99 设置里），重装前一直有效。
 * 用户可以先在实验版里把线切到主线版、直接下主线的新包覆盖上去 —— 因为两条线
 * 包名与签名相同（见 build.py 的 PACKAGE 注释）。
 *
 * ## 下载方式也改了
 *
 * 旧版：取一次网盘的签名直链，**交给系统浏览器**去下（签名 15 分钟有效）。
 * 新版：同一个按钮，但**在应用内后台下载**（[UpdateService] 前台服务 + 通知栏进度），
 * 下完自动拉起系统安装器（[ApkInstaller]）。
 *
 * 为什么改：用户明确要求「点击下载后后台通知栏下载并且显示进度」。
 * 交给浏览器时：① 用户切出去就看不到进度；② 部分机型的浏览器下载管理器
 * 会把 24 MB 的包下到私有目录，装的时候找不到。自己下能保证「下完就在手边」。
 *
 * 旧行为**保留为兜底**：应用内下载全部源都失败时，按钮会退化成「用浏览器打开直链」，
 * 不让用户卡死在一条路上。
 *
 * 诚实性约定（这一条是本面板的设计底线）：
 *  - 取不到就**如实说取不到**，并给出原因，**绝不显示「已是最新」**；
 *  - 网盘上的版本比本机**旧或相同**才说「已是最新」，且把两边的版本号都列出来；
 *  - 直链取不到时明说原因，不留下一个点了没反应的按钮。
 */
@Composable
fun HplayUpdatePanel(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var direct by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    // ★ 第 331 轮：渠道与发布线（用户选择，持久化）。
    var line by remember { mutableStateOf(HplayUpdateChannel.line) }
    var channel by remember { mutableStateOf(HplayUpdateChannel.channel) }
    // 换线 / 换渠道后旧结果作废 —— 否则界面上「当前查的是网盘」却显示 GitHub 的版本号。
    var pickerVersion by remember { mutableStateOf(0) }

    // 下载进度：每 400ms 轮询一次 UpdateService.State（它不在 Compose 快照里）。
    var dlTick by remember { mutableStateOf(0) }
    var dlPct by remember { mutableStateOf(0) }
    var dlRunning by remember { mutableStateOf(false) }
    var dlText by remember { mutableStateOf("") }
    var dlErr by remember { mutableStateOf("") }
    var dlOk by remember { mutableStateOf(false) }
    LaunchedEffect(dlTick) {
        // 只在面板可见期间轮询；关掉面板协程就停（LaunchedEffect 随组合销毁）。
        while (true) {
            dlRunning = UpdateService.State.running
            dlPct = UpdateService.State.pct
            dlOk = UpdateService.State.ok
            dlErr = UpdateService.State.error
            val d = UpdateService.State.done
            val t = UpdateService.State.total
            val sp = UpdateService.State.speed
            dlText = when {
                dlRunning -> {
                    val p = if (t > 0) " (" + (d * 100 / t) + "%)" else ""
                    "已下载 " + UpdateService.human(d) + (if (t > 0) " / " + UpdateService.human(t) else "") + p +
                        (if (sp > 0) " · " + "%.2f MB/s".format(sp / 1048576.0) else "")
                }
                dlOk -> "已下载完成，正在打开安装器（若没弹出来，点下面「立即安装」）"
                dlErr.isNotBlank() -> "下载中断：" + dlErr
                else -> ""
            }
            kotlinx.coroutines.delay(400)
        }
    }

    val currentName = remember { UpdateChecker.currentVersionName() }
    val currentCode = remember { UpdateChecker.currentVersionCode() }

    fun check() {
        // ★★★ 第 152 轮修（用户报「点重新检查更新 不检查」）：
        //
        // 老实现在 tryAcquire() 失败时**静默 return**，而 release() 只写在协程正常跑完的路径上。
        // 一旦这轮检查的协程被取消（关面板、或隐私锁重锁把 Han1meApp 移出组合），
        // release() 永不执行 —— busy 是**进程级** AtomicBoolean，于是永久卡在 true：
        // 之后每次进面板都在这一行悄悄返回，界面不显示「检查中…」也不发任何请求。
        //
        // 现在：① 失败时给出可见提示；② finally 保证任何路径都释放（协程取消也会走 finally）。
        if (!UpdateChecker.tryAcquire()) {
            note = "上一次检查还没结束（或刚被中断），请稍等再点。"
            checking = false
            return
        }
        checking = true
        note = null
        direct = null
        scope.launch {
            try {
                result = UpdateChecker.fetchRemote()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                result = UpdateChecker.Result.Failed("检查失败：" + (t.message ?: t.toString()))
            } finally {
                // 关面板 / 隐私锁上锁导致的协程取消也会走到这里（finally 里都是非挂起写）
                checking = false
                UpdateChecker.release()
            }
        }
    }

    // 进面板就自动查一次（用户点这一格的本意就是"看有没有新版"）。
    // ★ 第 331 轮：选择器变了也要重查 —— 否则切到主线版后界面还挂着实验版的版本号。
    LaunchedEffect(pickerVersion) {
        result = null
        direct = null
        check()
    }

    Column(
        // ★ 2026-11-25（第 313 轮）：**加不透明底**。
        // 原来没有 background，面板是透明的 —— 底下的「关于」页内容透上来，
        // 两层文字叠在一起完全没法看。
        Modifier.fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(AccentRed).clickable { onClose() },
            ) { Text("←", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Text("检查更新", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                if (checking) "检查中…" else "重新检查", color = TextDim, fontSize = 11.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(CardColorSoft)
                    .clickable { if (!checking) check() }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---------- 发布线 / 渠道选择器（第 331 轮）----------
        // 两排横向 chip。点一下立刻重查（LaunchedEffect(pickerVersion)）。
        Text("发布线", color = TextFaint, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for ((id, label) in HplayUpdateChannel.LINES) {
                val on = id == line
                Text(
                    label + (if (id == HplayUpdateChannel.buildLine) "（本包）" else ""),
                    color = if (on) Color.White else TextDim,
                    fontSize = 11.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (on) AccentRed else CardColorSoft)
                        .clickable {
                            if (id != line) {
                                line = id
                                HplayUpdateChannel.line = id
                                pickerVersion++
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("更新渠道", color = TextFaint, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        // 四条渠道分两行摆（一行塞不下四个中文 chip）。
        for (row in HplayUpdateChannel.CHANNELS.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                for ((id, label) in row) {
                    val on = id == channel
                    Text(
                        label,
                        color = if (on) Color.White else TextDim,
                        fontSize = 11.5.sp,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (on) AccentRed else CardColorSoft)
                            .clickable {
                                if (id != channel) {
                                    channel = id
                                    HplayUpdateChannel.channel = id
                                    pickerVersion++
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(
            when (channel) {
                HplayUpdateChannel.CH_GITHUB -> "GitHub Releases：本线的正式发布。不需要任何凭据，公网匿名可读。"
                HplayUpdateChannel.CH_CI ->
                    "GitHub CI 构建：最新一次 CI 打出来的预发布包，比正式版更超前，也更可能有问题。"
                else -> "自建网盘（垫底）：4 个挂载目录轮询。GitHub 与 CI 都不通时才该用它。"
            },
            color = TextFaint, fontSize = 10.5.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        // ---------- 本机 ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardColor).padding(12.dp),
        ) {
            Text("📦", fontSize = 15.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("当前版本", color = HplayOnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("v${currentName}（versionCode ${currentCode}）", color = TextFaint, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))

        when (val r = result) {
            null -> {
                Text(
                    if (checking) "正在取最新构建版本（走「" + HplayUpdateChannel.channelLabel(channel) + "」）…"
                    else "点右上角「重新检查」开始。",
                    color = TextFaint, fontSize = 12.sp, lineHeight = 18.sp,
                )
            }
            is UpdateChecker.Result.Failed -> {
                // 取不到 ≠ 已是最新。必须把原因说出来。
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardColor).padding(12.dp),
                ) {
                    Text("⚠", fontSize = 15.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("没取到更新信息", color = HplayWarning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(r.why, color = TextFaint, fontSize = 11.sp, lineHeight = 16.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            when (channel) {
                                HplayUpdateChannel.CH_NETDISK ->
                                    "这条路轮询 4 个挂载目录（移动云盘134→195→天翼→123）。" +
                                        "常见原因：服务没起、域名解析不了、或这一版还没推上去。"
                                HplayUpdateChannel.CH_GITHUB, HplayUpdateChannel.CH_CI ->
                                    "这条路走 GitHub。常见原因：API 限额用尽（匿名 60 次/小时/IP）、" +
                                        "构建时没注入仓库地址、或这条线还没发过版。"
                                else -> ""
                            } + "这不代表已经是最新版本 —— 只是这次没查到。",
                            color = TextFaint, fontSize = 10.5.sp, lineHeight = 15.sp,
                        )
                    }
                }
            }
            is UpdateChecker.Result.Ok -> {
                val rem = r.remote
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardColor).padding(12.dp),
                ) {
                    Text(if (r.hasUpdate) "🎉" else "✅", fontSize = 15.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (r.hasUpdate) "发现新版本 v${rem.versionName}" else "已是最新版本",
                            color = HplayOnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            when (r.via) {
                                UpdateChecker.Via.NETDISK -> "网盘：v${rem.versionName}"
                                UpdateChecker.Via.GITHUB -> "GitHub：${rem.ghTag.ifBlank { "v" + rem.versionName }}"
                                UpdateChecker.Via.CI -> "GitHub CI：${rem.ghTag}"
                            } + "（${rem.sizeText}）　·　本机：v${currentName}",
                            color = TextFaint, fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            when (r.via) {
                                UpdateChecker.Via.NETDISK -> "通道：自建网盘（垫底；GitHub 不通时才走这条）"
                                UpdateChecker.Via.GITHUB -> "通道：GitHub Releases · ${HplayUpdateChannel.lineLabel(line)}"
                                UpdateChecker.Via.CI -> "通道：GitHub CI 构建（预发布，追新用）"
                            },
                            color = TextFaint, fontSize = 10.5.sp,
                        )
                    }
                }
                if (rem.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    // ★★ LABUPD1（用户要求 2026-10-31）：**缩短更新说明**，只留最重要前 6 条，
                    //   不再让长说明把下面的「下载新版本 / 重新检查」挤出可视区。
                    val pn = rem.notes.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    Text(
                        "更新说明：\n" + pn.take(6).joinToString("\n") { "· " + it } +
                            (if (pn.size > 6) "\n……共 " + pn.size + " 条" else ""),
                        color = TextDim, fontSize = 11.5.sp, lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(CardColor).padding(12.dp),
                    )
                }
                if (r.hasUpdate) {
                    Spacer(Modifier.height(10.dp))

                    // ---------- ① 应用内后台下载（第 331 轮主路径）----------
                    // 有进度在跑时按钮变成「取消」，避免用户重复点出第二个下载。
                    // ★★★ 第 344 轮（用户报「点击按钮指向残留的上一版本安装包」）：
                    //   pendingApk 必须按**本次查到的版本号**去找，不能只判"文件存在"。
                    //   原来固定文件名 + 无版本判断 ⇒ 摸到旧包就直接拉安装器，
                    //   用户永远装不上新版，还以为更新成功了。
                    val pending = UpdateService.pendingApk(ctx, rem.versionName)
                    val btnText = when {
                        dlRunning -> "下载中 " + dlPct + "%　·　点此取消"
                        pending != null -> "⬇ 立即安装已下载的包（" + rem.versionName + "）"
                        else -> "⬇ 下载并在应用内安装"
                    }
                    Text(
                        btnText, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .background(if (dlRunning) HplayWarning else AccentRed)
                            .clickable {
                                if (dlRunning) {
                                    UpdateService.cancel(ctx)
                                    note = "已请求取消下载"
                                    return@clickable
                                }
                                // 已经下好**本版本**那一份 → 直接拉安装器，不重下 24 MB。
                                // 旧版本的包一律不认（见上面 pending 的注释）。
                                if (pending != null) {
                                    ApkInstaller.install(ctx, pending)
                                    return@clickable
                                }
                                note = "正在取下载地址…"
                                scope.launch {
                                    val u = UpdateChecker.apkDirectUrl(rem)
                                    direct = u
                                    if (u == null) {
                                        note = "没取到下载地址（见上方原因）。可以点下面的「用浏览器打开」。"
                                    } else {
                                        note = null
                                        // 开始后台下载。体积未知（Atom 兜底）时传 0，
                                        // 服务会把进度显示成不确定态而不是假的百分比。
                                        UpdateService.start(
                                            ctx, u, rem.ghApkName.ifBlank { rem.apk },
                                            if (rem.ghSizeKnown) rem.size else 0L, rem.md5,
                                            sha256 = rem.ghSha256,
                                            version = rem.versionName,
                                        )
                                        dlTick++
                                    }
                                }
                            }.padding(vertical = 11.dp),
                        textAlign = TextAlign.Center,
                    )

                    // ---------- 进度 / 结果 ----------
                    if (dlText.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            dlText,
                            color = if (dlErr.isNotBlank()) HplayWarning else TextDim,
                            fontSize = 11.sp, lineHeight = 16.sp,
                        )
                    }
                    if (dlRunning && dlPct > 0) {
                        Spacer(Modifier.height(6.dp))
                        // 进度条（不引 LinearProgressIndicator，避免依赖 material3 的额外 API 面）
                        Box(
                            Modifier.fillMaxWidth().height(4.dp)
                                .clip(RoundedCornerShape(2.dp)).background(CardColorSoft),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(dlPct / 100f).height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)).background(AccentRed),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    // ---------- ② 兜底：浏览器直链（旧行为，保留）----------
                    Text(
                        if (direct != null) "🌐 用浏览器打开直链" else "🌐 取直链并用浏览器下载",
                        color = TextDim, fontSize = 11.5.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(CardColorSoft)
                            .clickable {
                                if (direct != null) { HplayEnv.openExternal(direct!!); return@clickable }
                                note = "正在取下载直链…"
                                scope.launch {
                                    val u = UpdateChecker.apkDirectUrl(rem)
                                    direct = u
                                    note = if (u == null) "没取到下载直链（见上方原因）" else null
                                    if (u != null) HplayEnv.openExternal(u)
                                }
                            }.padding(vertical = 9.dp),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append("应用内下载走前台服务，退到后台/息屏也会继续，通知栏显示进度，下完自动拉起系统安装器；")
                            append("断网后重试只补缺的那一段（Range 续传）。")
                            if (rem.ghTag.isNotBlank()) {
                                append("\n安装前系统会问一次「是否允许 Hplay 安装应用」，允许一次即可。")
                            } else {
                                append("\n网盘的直链是签名临时地址（约 15 分钟有效），退回浏览器那条路时点一次取一次。")
                            }
                        },
                        color = TextFaint, fontSize = 10.5.sp, lineHeight = 15.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "两条发布线包名与签名相同，可互相覆盖安装（实验版 ↔ 主线版都行）。",
                        color = TextFaint, fontSize = 10.5.sp, lineHeight = 15.sp,
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "远端那份不比本机新。若刚发布还没同步，点右上角「重新检查」再试一次。",
                        color = TextFaint, fontSize = 10.5.sp, lineHeight = 15.sp,
                    )
                }
            }
        }

        note?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = TextDim, fontSize = 11.5.sp)
        }
        direct?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "直链：" + it.take(96) + if (it.length > 96) "…" else "",
                color = TextFaint, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * **开屏自动检查更新弹窗**（第 54 轮，用户要求「开启软件时显示弹窗，简略通俗明了地说明新版本
 * 更新内容，并在弹窗内设『立即更新』和『下次再说』」）。
 *
 * 与「检查更新」面板的分工：
 *  · 面板 = 用户主动点进去看，信息全（版本号、体积、md5、直链）；
 *  · 本弹窗 = 开屏自动弹一次，**只讲人话**（这一版改了什么），给两个选择。
 *
 * 「下次再说」只关掉本次，**不写任何『不再提醒』的持久标记** ——
 * 用户没说要永久忽略，替用户做这个决定是越权。
 */
@Composable
fun HplayUpdatePopup(
    remote: UpdateChecker.Remote,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "upd-alpha",
    )
    val sc by animateFloatAsState(
        targetValue = if (shown) 1f else 0.94f,
        animationSpec = tween(durationMillis = 200),
        label = "upd-scale",
    )
    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onLater() }
            .graphicsLayer { alpha = a },
        contentAlignment = Alignment.Center,
    ) {
        // ★★ LABUPD1（用户要求 2026-10-31）：**不再占满全屏**。
        //
        // 用户原话：「打开应用后的检查更新弹窗，不要占满全屏，把它缩减到适合的高度即可」。
        // 旧版没有任何高度约束，更新说明一多（十几条）就把弹窗撑到贴边、按钮被挤出屏幕。
        // 现在：整体限高 + 说明区独立限高可滚动，按钮永远固定在底部可见处。
        Column(
            Modifier.fillMaxWidth(0.88f)
                .heightIn(max = 460.dp)
                .graphicsLayer { alpha = a; scaleX = sc; scaleY = sc }
                .clip(RoundedCornerShape(16.dp)).background(CardColor)
                .clickable { }
                .padding(18.dp),
        ) {
            // ★★ 第 332 轮：**去掉顶部 emoji**。
            //
            // 原来这里有个 🔔。在采信它的 ROM 上是个铃铛，在字体缺这个码位的机器上
            // 就是一个方框 —— 弹窗第一眼看到方框，比没有图标更糟。标题本身就够醒目，
            // 不需要靠 emoji 撑场。
            Text(
                "发现新版本", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "v" + remote.versionName + "　·　" + remote.sizeText +
                    "　·　" + HplayUpdateChannel.lineLabel(HplayUpdateChannel.line),
                color = TextFaint, fontSize = 11.5.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(12.dp))
            Text("这一版改了什么", color = HplayOnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            // ★★ 第 332 轮（用户要求「每次更新都简略写明核心更新」）：
            //
            // 旧版直接把 GitHub Release 的 body 原样列出来。实测那个 body 往往是
            // 一堆 commit subject："Merge pull request #705 from ..."、
            // "↓ (@misaka10032w) #703 ..."、dependabot 的版本号……
            // 十几条里真正有用的一两条被淹掉，用户看完还是不知道更新了什么。
            //
            // 现在先**提炼**（[coreNotes]）：去掉 merge/依赖升级/纯哈希这类噪音，
            // 剥掉提交者前缀与 markdown 记号，最多留 5 条，每条截到 2 行长度。
            // 用户要完整清单时，面板里看得到（这里只讲核心）。
            val allNotes = coreNotes(remote.notes)
            val noteLines = allNotes.take(5)
            Column(
                Modifier.fillMaxWidth().heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(10.dp)).background(CardColorSoft)
                    .verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                if (noteLines.isEmpty()) {
                    Text(
                        "（这一版没有附更新说明）",
                        color = TextFaint, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                } else {
                    noteLines.forEach { ln ->
                        Text(
                            "· " + ln,
                            color = HplayOnSurface, fontSize = 12.sp, lineHeight = 18.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (allNotes.size > 5) {
                        Text(
                            "……另有 " + (allNotes.size - 5) + " 条，完整说明见「检查更新」面板",
                            color = TextFaint, fontSize = 11.sp, lineHeight = 16.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "下次再说", color = TextDim, fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .background(CardColorSoft).clickable { onLater() }
                        .padding(vertical = 10.dp),
                )
                Text(
                    "立即更新", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .background(AccentRed).clickable { onUpdate() }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * **把更新说明提炼成「核心更新」**（第 332 轮，用户要求「每次更新都简略写明核心更新」）。
 *
 * ## 为什么需要提炼
 *
 * GitHub Release 的 body 实测长这样（Han1meViewer 的真实发布）：
 * ```
 * ↓ (@misaka10032w)
 * Merge pull request #705 from misaka10032w/fix/minor-fixes
 *
 * [Improve] 增加APP级缩放限制上限，防止雷霆系统缩放导致APP布局错位，可在设置-外观与交互-界面与布局更改
 *
 * ↓ (@misaka10032w)
 * #703 增加APP级缩放限制上限，防止雷霆系统缩放导致APP布局错位
 * ```
 * 六条里只有一条是用户看得懂、用得上的（缩放限制）。其余是 merge 记录、
 * 提交者署名、版本号、dependabot 的依赖升级。
 *
 * ## 提炼规则（按顺序）
 *
 * 1. 按 `↓ (@xxx)` 这类署名行切成块，署名行本身丢掉；
 * 2. 丢掉：merge / bump / dependabot / 纯 `#123 标题` 的重复引用 / 
 *    纯哈希 / `Signed-off-by` / `Co-authored-by`；
 * 3. 剥掉 markdown 记号（`###`、`**`、`- [x]`、反引号）；
 * 4. 去重（同一句常因为 merge 出现两次）；
 * 5. 每条压成一行（换行变空格），超长由界面按 3 行截断。
 *
 * 提炼后如果**一条都不剩**（说明这条发布确实是纯 CI 噪音），
 * 返回空列表 —— 界面会显示「这一版没有附更新说明」，这比显示六条 merge 记录诚实。
 */
internal fun coreNotes(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val out = ArrayList<String>()
    val seen = HashSet<String>()
    for (rawLine in raw.split("\n")) {
        var ln = rawLine.trim()
        if (ln.isEmpty()) continue
        // 1) 提交者署名行（`↓ (@name)` / `(@name)`）
        if (ln.startsWith("↓") || Regex("^\\\\(@[^)]+\\\\)$").matches(ln)) continue
        // 2) 噪音判据（大小写不敏感）
        val low = ln.lowercase()
        if (low.startsWith("merge pull request") || low.startsWith("merge branch") ||
            low.startsWith("merge remote-tracking") || low.contains("dependabot") ||
            low.startsWith("bump ") || low.startsWith("signed-off-by") ||
            low.startsWith("co-authored-by") || low.startsWith("this reverts commit")
        ) continue
        // 3) 剥 markdown
        ln = ln.replace(Regex("^#{1,6}\\s*"), "")
            .replace("**", "")
            .replace("__", "")
            .replace(Regex("^[-*+]\\s+"), "")
            .replace(Regex("^\\[x\\]\\s*"), "")
            .replace("`", "")
            .trim()
        if (ln.isEmpty()) continue
        // 4) 纯哈希 / 纯版本号 / 过短
        if (Regex("^[0-9a-f]{7,40}$").matches(ln)) continue
        if (Regex("^v?\\d+(\\.\\d+)+(\\S*)?$").matches(ln)) continue
        if (ln.length < 6) continue
        // 5) 压成一行
        ln = ln.replace(Regex("\\s+"), " ").trim()
        // 6) 去重（按归一化后的文本）
        val key = ln.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]"), "")
        if (key.isEmpty() || !seen.add(key)) continue
        out.add(ln)
    }
    return out
}