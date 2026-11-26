package com.han1me.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.ui.PageStepper
import com.han1me.AccentRed
import com.han1me.CardColor
import com.han1me.CardColorSoft
import com.han1me.TextDim
import com.han1me.TextFaint
import java.io.File
import com.han1me.HplayOnSurface
import com.han1me.HplayPrimary
import com.han1me.HplayErrorSoft
import com.han1me.HplayOutline
import com.han1me.HplayWarning

/**
 * 「下载」页（第 46 轮，按用户参考图重做）。
 *
 * 参考图的结构（这是**要还原的规格**，不是可选项）：
 *
 *   [←]  下载        [搜索][筛选][多选][新建][下载]
 *        正在下载                    已下载
 *        +------------------------------------------+
 *        | [下载图标] 未分组            [ 收起 ]     |   <- 红色收起键
 *        |            18部影片                      |
 *        +------------------------------------------+
 *        | +--------+  [Monty] 泣いてもやめない！..  |   <- 大封面缩略图
 *        | |  封面  |  2026-09-28 18:46             |
 *        | +--------+  (408161) 720P | 257.0 MiB    |   <- 橄榄色胶囊 + 质量/体积
 *        |                                           |
 *        |  删除    外部播放器    本地观看            |   <- 底排三键
 *        +------------------------------------------+
 *
 * 与第 45 轮那版的差别（用户第 3 条反馈就是「没还原这个 UI」）：
 *  · 标题从「本地下载」改成「下载」，并补上五个图标键（搜索/筛选/多选/新建/下载）；
 *  · 页签顺序按图改成 **正在下载 / 已下载**（原来是反的）；
 *  · 新增 **分组头**（「未分组 · N部影片」+ 可折叠的红色「收起」）；
 *  · 卡片从「46x30 小方块 + 播放/叉」改成 **大封面 + 质量/体积胶囊 + 底排三键**
 *    （删除 / 外部播放器 / 本地观看）；
 *  · 新增**分片并发度**选择（用户第 4 条：可选内置多线程）。
 *
 * 只画界面，状态与磁盘都在 [HplayDownloads] 里（进程级，关掉这一页下载照跑）。
 * 配色沿用 App 自己的 token，不引入新的设计语言。
 */
/** 已下载每页条数（第 54 轮分页）。12 是一屏多一点，滚动一屏就能看完一页。 */
private const val PAGE_SIZE = 12

@Composable
fun HplayDownloadsPanel(
    /** 点「本地观看」：交给上层合成详情（走已补丁翻译过的那条播放路径）。 */
    onPlay: (String) -> Unit,
    onClose: () -> Unit,
) {
    val tasks by HplayDownloads.tasks.collectAsState()

    // ---------- 进场/退场动画（第 51 轮，用户要求「进本地下载的动画」）----------
    // 自包含实现：面板自己管可见性，所以**不必改生成壳** Han1meApp.kt。
    // 进场：从下方 24dp 滑入 + 淡入；退场：下滑 + 淡出后再真正回调 onClose。
    var visible by remember { mutableStateOf(false) }
    // 第 149 轮：进下载页时兜底补一次「未完成任务恢复」。
    // 万一 object 初始化那一刻 HplayEnv 还没备好（dataDir 抛异常），init 里那次会失败；
    // 这里再试一次，保证用户「暂停过的任务」不会因为初始化时序问题凭空消失。
    LaunchedEffect(Unit) { runCatching { HplayDownloads.ensureRestored() } }
    LaunchedEffect(Unit) { visible = true }
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = if (visible) 180 else 120),  // ★ LABANIM：更快
        label = "dl-panel-slide",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (visible) 260 else 180),
        label = "dl-panel-alpha",
    )
    // 用**显式标志**而不是 LaunchedEffect(visible)：后者在首次组合时 key 就是 false，
    // 会延迟一拍直接调 onClose()，面板刚进来就把自己关了。
    var closing by remember { mutableStateOf(false) }

    /** 先播退场，再真的关闭（否则瞬间消失，看不到动画）。 */
    val requestClose: () -> Unit = {
        if (!closing) {
            closing = true
            visible = false
        }
    }

    var tab by remember { mutableStateOf(1) }          // 0 = 正在下载，1 = 已下载（与参考图一致）
    var tick by remember { mutableStateOf(0) }
    // 第 258 轮：**秒开**。不再"开面板即全量重扫"（那是第 60 轮为修「下载完成却找不到」加的）：
    // 现在完成钩子已把成品直接写进快照（HplayDownloads.snapshotUpsertForArtifact），
    // 改为装载持久化快照（毫秒级）立即渲染，后台增量校准（每 root 一次 listFiles +
    // 每条目 4 次 O(1) stat），有变化才重建受影响条目并再刷新一次。
    // 判据是磁盘真实文件名集合 + 条目浅指纹，与全量扫描同一口径，准确度不打折。
    LaunchedEffect(Unit) {
        runCatching { HplayDownloads.ensureFreshAsync() }
        tick++
    }
    // 快照装载/校准/完成钩子之后由监听器推 tick，列表与总量就地刷新。
    DisposableEffect(Unit) {
        val l: () -> Unit = { tick++ }
        HplayDownloads.addItemsListener(l)
        onDispose { HplayDownloads.removeItemsListener(l) }
    }
    var showDir by remember { mutableStateOf(false) }
    var showConc by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectMode by remember { mutableStateOf(false) }
    // ---------- 第 53 轮：删除相关状态 ----------
    // ① 删除确认弹窗（用户要求"删除时加入确认删除弹窗"）
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    // ② 正在删除（后台线程跑；界面显示"删除中…"，主线程不再被递归删文件卡住）
    var deleting by remember { mutableStateOf(false) }
    // 第 56 轮：手动扫描进行中（按钮防重点；扫描结果通过 msg 如实回显）
    var scanning by remember { mutableStateOf(false) }
    // 第 71 轮：恢复收藏进行中（防连点）
    var restoring by remember { mutableStateOf(false) }
    var coverSweeping by remember { mutableStateOf(false) }   // 第 261 轮：补全封面按钮防连点
    val scope = rememberCoroutineScope()
    // 第 50 轮：这两个都走 HplayDownloads 的内部缓存（见 invalidateSize）。
    // tick 只在"下载完成/删除/改目录"时自增，所以滚动列表、打字筛选都不会触发扫盘 ——
    // 上一版是 remember(tick) + 每次重组现算，实测一个常见规模要 66 ms/次。
    val dir = remember(tick) { HplayDownloads.saveDir() }
    // ★★ 第 111 轮修（用户报「多项下载进行中时，点『本地下载』要卡约 5 秒才进得去；
    //    偶尔没有下载任务时也会停滞几秒」）：
    //
    // 这几行原来写的是 `val all = remember(tick) { HplayDownloads.items() }` ——
    // 它在**主线程**上跑 items()，而 items() 未命中缓存时会 scanItems()：
    // 递归遍历**全部候选下载根目录**，并对每个条目再递归算体积（dirBytes）。
    // 下载中的任务正在写几百上千个分片（实测 299 片 / 333 MB 那种），
    // 于是首次组合就要在主线程上 stat 上千个文件 ⇒ 界面冻结数秒才出得来。
    // 没有下载任务时也会慢，是因为它要扫**所有候选根**（含 /Download）而不是只看一个目录。
    //
    // 第 63 轮已经把 totalBytes() 挪到 IO（见下面那段注释），**但 items() 漏了**。
    // 现在同样挪到 IO：先用上一次的结果渲染（不冻结），扫完再刷新。
    var all by remember { mutableStateOf<List<DlItem>>(emptyList()) }
    // 第 258 轮：读**就地缓存**（itemsSnapshot 绝不触发扫盘）——扫描全部由后台负责，
    // 缓存就绪后监听器推 tick 刷新。原来的 items() 在缓存未命中时会同步全量扫盘，
    // 是「打开下载页要等很久」的另一半根源。
    LaunchedEffect(tick) {
        all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { HplayDownloads.itemsSnapshot() }.getOrDefault(emptyList())
        }
    }
    // 体积也只在 tick 变时才重算（它曾经直接写在 Text(...) 里，等于每帧扫盘一次）
    // 第 63 轮：**改为 IO 线程异步算**。原写法 remember(tick){ totalBytes() } 在**主线程**执行，
    // 而 totalBytes() 未命中缓存时会递归遍历整个下载目录（片数多时是几千次 stat）——
    // 刚下载完成/删除/改目录后缓存被作废，正好是用户在看界面的时刻，主线程就卡住。
    // 现在：先用可能过期的缓存值渲染（不卡），算完再刷新。
    var totalBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(tick) {
        totalBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { HplayDownloads.totalBytes() }.getOrDefault(0L)
        }
    }
    val filtered = remember(all, query) {
        if (query.isBlank()) all else all.filter { it.title.contains(query, true) }
    }
    // ---------- 第 54 轮：已下载分页（用户要求"新增一个下一页按钮"）----------
    // 已下载可能有很多条，一屏铺完既卡又难找。按 PAGE_SIZE 分页，页脚给「上一页/下一页」。
    // 筛选条件变了要回到第 1 页，否则会出现"筛完停在一个空页上"。
    var page by remember { mutableStateOf(0) }
    LaunchedEffect(filtered.size) {
        val last = ((filtered.size - 1).coerceAtLeast(0)) / PAGE_SIZE
        if (page > last) page = last
    }
    val pageCount = ((filtered.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
    val items = remember(filtered, page) {
        filtered.drop(page * PAGE_SIZE).take(PAGE_SIZE)
    }
    // 第 49 轮：PAUSED 也要留在「正在下载」页签里 —— 否则暂停之后任务消失，用户找不到续传按钮。
    val pending = tasks.filter { it.status != DlStatus.DONE }
    val doneCount = tasks.count { it.status == DlStatus.DONE }

    // 有活干就自动切到「正在下载」；刚下完则回到「已下载」并让列表重读磁盘。
    LaunchedEffect(pending.size) { if (pending.isNotEmpty()) tab = 0 }
    LaunchedEffect(doneCount) { if (doneCount > 0) { tick++; tab = 1 } }

    // 退场动画播完后真正关闭（只在用户确实请求关闭时才走这里）
    LaunchedEffect(closing) {
        if (closing) {
            kotlinx.coroutines.delay(190)
            onClose()
        }
    }

    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                translationY = slide * 48f
                this.alpha = alpha
            },
    ) {
    // 第 65 轮：底部给**固定翻页栏**留出高度（约 52dp），否则最后一行会被它压住。
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
            // 第 81 轮：翻页栏已移到页签行，这里只需给**底栏**让位（74dp）即可。
            .padding(bottom = 74.dp),
    ) {
        // ---------- 标题行 + 五个图标键 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(AccentRed).clickable { requestClose() },
            ) { Text("←", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Text("下载", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconChip("🔍") { searching = !searching; if (!searching) query = "" }
            Spacer(Modifier.width(6.dp))
            // 第 262 轮：☰/＋ 顶栏按钮移除——底部「并发 N」「目录」已有同功能（对话框共用），只去显示。            Spacer(Modifier.width(6.dp))
            IconChip(if (selectMode) "✓" else "≣") {
                selectMode = !selectMode; if (!selectMode) picked = emptySet()
            }
            Spacer(Modifier.width(6.dp))
            Spacer(Modifier.width(6.dp))
            // 第 56 轮：**手动扫描**（用户要求「新建个手动扫描下载视频功能」）。
            // 显式触发一次全范围重扫（含历史/候选目录），完成后**如实回显扫到了什么**。
            // 扫描在 IO 线程做，不阻塞界面；结果只读，不移动/不改名/不删任何文件。
            IconChip("⟳") {
                if (!scanning) {
                    scanning = true
                    msg = null
                    scope.launch {
                        val rep = withContext(Dispatchers.IO) { HplayDownloads.manualScan() }
                        scanning = false
                        tick++
                        msg = "扫描完成：在 " + rep.roots.size + " 个目录找到 " + rep.found + " 个视频" +
                            (if (rep.bytes > 0) "（共 " + HplayDownloads.humanOf(rep.bytes) + "）" else "")
                        tab = 1
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            // 第 71 轮：**从下载产物重建本地收藏**（事故恢复）。
            // 起因：应用私有目录被清后本站收藏一条不剩，而下载的视频与 detail.json 还在 ——
            // 后者足以把「下载过的那些」收藏重建。做成显式动作，并如实回显恢复了多少条。
            IconChip("♻") {
                if (!restoring) {
                    restoring = true
                    msg = null
                    scope.launch {
                        val n = withContext(Dispatchers.IO) {
                            runCatching {
                                com.han1me.X99.restoreFavsFrom(HplayDownloads.favCandidatesFromDownloads())
                            }.getOrDefault(0)
                        }
                        restoring = false
                        tick++
                        msg = if (n > 0) "已从下载记录恢复 $n 条本地收藏（只能恢复下载过的；只收藏没下载的找不回）"
                              else "没有可恢复的：下载记录里没有新条目（或它们本就在收藏里）"
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            // 第 261 轮：手动「补全封面」——自动机制（已下载页签点击触发）的兜底，
            // 忽略节流强制全量扫，结果如实回显（成功/无来源/失败）。
            IconChip("🖼") {
                if (!coverSweeping) {
                    coverSweeping = true
                    msg = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                HplayDownloads.sweepMissingCoversSync(true) { d, tot ->
                                    msg = "补全封面中 $d/$tot…"
                                }
                            }.getOrNull()
                        }
                        coverSweeping = false
                        tick++
                        msg = when {
                            r == null -> "补全封面：执行出错"
                            r.ok < 0 -> "补全封面：已有扫补在进行"
                            else -> "补全封面：成功 " + r.ok + " / 无来源 " + r.noSrc + " / 失败 " + r.fail + r.topReasons
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            IconChip("⬇") { tab = 0 }
        }

        // 搜索行（点 🔍 才出现）
        if (searching) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CardColorSoft)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (query.isEmpty()) Text("按片名筛选已下载", color = TextFaint, fontSize = 12.sp)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = HplayOnSurface, fontSize = 12.5.sp),
                    cursorBrush = SolidColor(AccentRed), modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---------- 两个页签（顺序按参考图：正在下载 在前） ----------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabPill("正在下载 ${pending.size}", tab == 0) { tab = 0 }
            // 第 65 轮实机 bug：这里原来写的是 items.size —— 而 items 是**当前页切片**
            // （受 PAGE_SIZE=12 限制），不是总数。实测扫描报告「找到 18 个视频」，
            // 页签却显示「已下载 12」，用户以为「刚下载的没进去」。
            // 现在显示 filtered.size（筛选后的**真实总数**）。
            // 注意**不再拼外层页码**：已下载的分页是**按分组各自翻页**（见下面的组内分页），
            // 外层那套 page/pageCount 已废弃，写上去只会显示一个不生效的「1/2」。
            // 第 261 轮：封面自动扫补的唯一自动触发点 = 用户点击「已下载」页签（用户明令约束）。
            // 防重入与 60 分钟/URL 节流在扫补内部；重复点击/无缺项都是零成本短路。
            TabPill("已下载 ${filtered.size}", tab == 1) {
                tab = 1
                runCatching { HplayDownloads.sweepMissingCoversAsync() }
            }
            // * 第 81 轮（用户要求）：「翻页按键位置偏移，把它移到我图上方标记地方，
            //   在已下载61的右边，调整美观」。
            // 原来翻页栏是**底部固定浮层**（放在滚动容器之外，还曾与底栏打架），
            // 既挡住列表又显得错位。现在移到**页签行右侧**，与「已下载 N」同一行。
            if (tab == 1 && pageCount > 1) {
                Spacer(Modifier.weight(1f))
                PageStepper(
                    page = page,
                    pageCount = pageCount,
                    onPrev = { if (page > 0) page-- },
                    onNext = { if (page < pageCount - 1) page++ },
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (tab == 1) {
            // ---------- 第 60 轮：**按板块分组**（用户要求「各个板块下载的视频单独分组，分组名取自板块名」） ----------
            // 老实现只有一个写死叫「未分组」的分组头，等于没有分组。
            // 现在按 DlItem.group（板块名，下载时写入）分成若干组，每组：
            //   自己的分组头（板块名 + N 部影片 + 可收起）+ 自己的分页页脚。
            // **每组独立分页**：否则一个板块的片子会把别的板块挤出这一页，用户以为丢了。
            msg?.let { Text(it, color = HplayErrorSoft, fontSize = 11.sp, lineHeight = 15.sp); Spacer(Modifier.height(6.dp)) }

            // 第 65 轮实机 bug（用户指出「视频数还是 12，只把显示改成 18」）：
            // 这里原来用的 **items** 是外层已切过的当前页（<= PAGE_SIZE=12），
            // 而组内又按 PAGE_SIZE 切一次 —— **双层分页**，于是 18 条永远只看到 12 条；
            // 外层那套 page/pageCount 还只写在页签文案里、**页脚从未渲染**。
            //
            // 现在改为**单一层级的分页**：对 filtered 整体分页（items 就是当前页），
            // 分组只是把**当前页这批**按板块归类显示；翻页由底部**固定翻页栏**负责。
            val byGroup = remember(items) {
                items.groupBy { it.group.ifBlank { "未分组" } }
                    .toList().sortedWith(
                        compareByDescending<Pair<String, List<DlItem>>> { it.second.size }.thenBy { it.first },
                    )
            }

            byGroup.forEach { (gName, gItems) ->
                // 第 65 轮：**组内不再单独分页** —— 分页统一由底部固定翻页栏管。
                // 这里只展示「当前页里属于该组」的那些条目。
                val gShown = gItems

                // ---------- 分组头（参考图的「未分组 · 18部影片」+ 红色收起） ----------
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(CardColor).padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(CardColorSoft),
                    ) { Text("⬇", color = HplayOnSurface, fontSize = 13.sp) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        // 分组名 = 板块名（用户要求）
                        Text(gName, color = HplayOnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${gItems.size}部影片", color = TextFaint, fontSize = 11.sp)
                    }
                    Text(
                        if (collapsed) "展开" else "收起", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(HplayPrimary)
                            .clickable { collapsed = !collapsed }.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (!collapsed) {
                DownloadedList(
                    items = gShown,
                    selectMode = selectMode,
                    picked = picked,
                    onTogglePick = { p -> picked = if (p in picked) picked - p else picked + p },
                    onPlayLocal = onPlay,
                    // 第 53 轮：**删除不再在主线程直接做**。
                    // 老实现 HplayDownloads.delete(p) 在点击回调里递归删文件 + Thread.sleep(180)
                    // + 多次重试；删一个几百片的分片目录要几秒，主线程被占住就是"卡死"。
                    // 现在：点删除 → 弹确认 → 确认后丢到 IO 线程，界面显示"删除中…"。
                    onDelete = { p -> confirmDelete = listOf(p) },
                    onDeletePicked = { confirmDelete = picked.toList() },
                )
                // 第 65 轮：组内页脚已移除 —— 翻页统一由**底部固定翻页栏**承担
                // （用户要求「做一个固定下载页的翻页按钮，可翻页查看其余视频」）。
                Spacer(Modifier.height(14.dp))
                }        // ← if (!collapsed)
            }            // ← byGroup.forEach（第 60 轮：分组循环）
        } else {
            // 已完成/失败/取消的**记录**（不是文件）在这里清掉；文件仍要回「已下载」页签逐条删。
            if (tasks.any { it.status == DlStatus.DONE || it.status == DlStatus.FAILED || it.status == DlStatus.CANCELLED }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text("已完成 " + tasks.count { it.status == DlStatus.DONE } + " 条记录", color = TextDim, fontSize = 11.5.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "清空记录", color = TextDim, fontSize = 11.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                            .clickable { HplayDownloads.clearFinished() }.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
            // ---------- 第 60 轮：**已完成记录也按板块分组**（用户要求） ----------
            // 已完成是 DlTask，板块名在 task.group 里（第 60 轮起下载时会写入）。
            val doneByGroup = remember(tasks) {
                tasks.filter { it.status != DlStatus.RUNNING && it.status != DlStatus.QUEUED }
                    .groupBy { it.group.ifBlank { "未分组" } }
                    .toList().sortedWith(
                        compareByDescending<Pair<String, List<DlTask>>> { it.second.size }.thenBy { it.first },
                    )
            }
            if (doneByGroup.isEmpty()) {
                DownloadingList(pending)
            } else {
                doneByGroup.forEach { (gName, gTasks) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(CardColorSoft),
                        ) { Text("✓", color = Color(0xFF8FD18F), fontSize = 11.sp) }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            gName, color = HplayOnSurface, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${gTasks.size} 条", color = TextFaint, fontSize = 11.sp)
                    }
                    DownloadingList(gTasks)
                }
                // 还在跑的（RUNNING/QUEUED）不参与分组，单独列在最后
                val runningNow = tasks.filter { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }
                if (runningNow.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                    ) { Text("进行中 ${runningNow.size} 条", color = TextFaint, fontSize = 11.5.sp) }
                    DownloadingList(runningNow)
                }
            }
        }

        // ---------- 第 71 轮：公共目录访问权限（真实事故修复） ----------
        //
        // 没有这条权限时，保存目录里的文件在应用看来**完全不可见** ——
        // 用户看到的是「已下载」全空、或每条都 0 B / 无封面（本次事故就是这个）。
        // 所以这里**摆在最显眼处**：没权限就明确说清 + 给一键去授予的按钮，不藏在设置深处。
        if (!HplayStorageAccess.hasFullAccess()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2A1E1E)).padding(12.dp),
            ) {
                Text(
                    "⚠ 缺少「所有文件访问」权限",
                    color = HplayWarning, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "没有它，保存目录里的视频在应用看来是空的（会显示 0 B、无封面）。" +
                        HplayStorageAccess.statusText(),
                    color = HplayOnSurface, fontSize = 11.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "去授予权限", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(AccentRed)
                        .clickable {
                            if (!HplayStorageAccess.request()) {
                                msg = "无法拉起授权页，请到「系统设置 → 应用 → Hplay → 权限」手动开启「所有文件访问」"
                            }
                        }.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        // 底部汇总 + 目录/并发入口
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dir.absolutePath + "　·　共 " + humanBytes(totalBytes),
                color = TextFaint, fontSize = 10.5.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                "并发 " + remember(tick) { HplayDownloads.concurrency() }, color = TextDim, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(CardColorSoft)
                    .clickable { showConc = true }.padding(horizontal = 10.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "目录", color = TextDim, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(CardColorSoft)
                    .clickable { showDir = true }.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
    }   // ← 关闭动画用的 Box（第 51 轮）

    // ---------- 第 53 轮：删除确认弹窗 ----------
    confirmDelete?.let { targets ->
        ConfirmDialog(
            title = if (targets.size == 1) "删除这个视频？" else "删除选中的 " + targets.size + " 个视频？",
            body = "会连同介绍页与封面缓存一起删掉，**不可恢复**。",
            busy = deleting,
            onCancel = { if (!deleting) confirmDelete = null },
            onConfirm = {
                if (deleting) return@ConfirmDialog
                deleting = true
                scope.launch {
                    // 丢到 IO 线程：主线程只管界面
                    val bad = withContext(Dispatchers.IO) {
                        val acc = ArrayList<String>()
                        targets.forEach { p -> HplayDownloads.delete(p)?.let { acc += it } }
                        acc
                    }
                    msg = if (bad.isEmpty()) "已删除 " + targets.size + " 个" else bad.first()
                    deleting = false
                    confirmDelete = null
                    picked = emptySet()
                    selectMode = false
                    tick++
                }
            },
        )
    }

    if (showDir) {
        DirChooser(current = dir, onDismiss = { showDir = false }, onApplied = { tick++; showDir = false })
    }
    if (showConc) {
        ConcChooser(onDismiss = { showConc = false }, onApplied = { tick++; showConc = false })
    }
}

@Composable
private fun IconChip(glyph: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(CardColorSoft).clickable { onClick() },
    ) { Text(glyph, color = HplayOnSurface, fontSize = 14.sp) }
}

@Composable
private fun TabPill(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color.White else TextDim,
        fontSize = 12.5.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) AccentRed else CardColorSoft)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * 已下载卡片：**大封面 + 标题 + 时间 + 质量/体积胶囊 + 底排三键**（参考图的形态）。
 */
@Composable
private fun DownloadedList(
    items: List<DlItem>,
    selectMode: Boolean,
    picked: Set<String>,
    onTogglePick: (String) -> Unit,
    onPlayLocal: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeletePicked: () -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            "还没有下载。在影片介绍页点「⬇ 下载到本机」即可：HLS 会把分片与密钥一起下到本地，" +
                "介绍页与封面也一并缓存，断网后在这一页点一下就能看。",
            color = TextFaint, fontSize = 12.sp, lineHeight = 18.sp,
        )
        return
    }
    if (selectMode && picked.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text("已选 ${picked.size} 部", color = TextDim, fontSize = 11.5.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "删除所选", color = Color.White, fontSize = 11.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(HplayPrimary)
                    .clickable { onDeletePicked() }.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
    // 注意：这里**不能**用 LazyColumn —— 外层 Column 是 verticalScroll，
    // 嵌套 Lazy 会在测量时抛「无限高度」异常（真机表现就是闪退）。
    // 卡顿的真正根因是每次重组都扫盘算体积（66 ms/次），已在 HplayDownloads 里用缓存解决。
    items.forEach { it0 ->
        Column(
            Modifier.fillMaxWidth().padding(vertical = 5.dp)
                .clip(RoundedCornerShape(10.dp)).background(CardColor).padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                // 大封面缩略图（参考图里封面是主体，不是小方块）
                Box(
                    Modifier.size(width = 96.dp, height = 134.dp).clip(RoundedCornerShape(6.dp)).background(CardColorSoft),
                ) {
                    if (it0.cover.isNotEmpty()) {
                        com.han1me.web.HplayThumb(
                            url = it0.cover, contentDescription = it0.title,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("🎬", color = HplayOutline, fontSize = 20.sp)
                        }
                    }
                    if (selectMode) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (it0.path in picked) AccentRed else Color(0xAA000000))
                                .clickable { onTogglePick(it0.path) },
                            contentAlignment = Alignment.Center,
                        ) { if (it0.path in picked) Text("✓", color = Color.White, fontSize = 11.sp) }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        it0.title, color = HplayOnSurface, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                            .format(java.util.Date(it0.at)),
                        color = TextFaint, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 质量 / 体积胶囊（参考图里那个橄榄色小块 + 「720P | 257.0 MiB」）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF8A6D1F))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                if (it0.quality > 0) it0.quality.toString() else "本机",
                                color = Color(0xFFFFE9A8), fontSize = 11.sp,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            (if (it0.quality > 0) it0.quality.toString() + "P | " else "") + humanBytes(it0.bytes),
                            color = HplayOnSurface, fontSize = 11.5.sp,
                        )
                    }
                    if (it0.hasDetail) {
                        Spacer(Modifier.height(4.dp))
                        Text("含介绍页缓存", color = TextFaint, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // ---------- 底排三键：删除 / 外部播放器 / 本地观看 ----------
            // 「（未完成）」的条目没有可播产物（HLS 只下到一半、没有 index.m3u8），
            // 那就**不给播放入口**（给了也是点下去报错）；删除仍然可用，且一次删掉整个条目。
            val playable = !it0.title.endsWith("（未完成）")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                BottomAction("🗑", "删除", HplayOnSurface) { onDelete(it0.path) }
                Spacer(Modifier.weight(1f))
                if (playable) {
                    BottomAction("⧉", "外部播放器", HplayOnSurface) {
                        val cb = HplayDownloads.onPlayExternal
                        if (cb != null) cb(it0.path)
                    }
                    Spacer(Modifier.weight(1f))
                    BottomAction("▶", "本地观看", AccentRed) { onPlayLocal(it0.path) }
                } else {
                    Text(
                        "这条没下完，只能删除", color = TextFaint, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomAction(glyph: String, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(glyph, color = tint, fontSize = 12.sp)
        Spacer(Modifier.width(5.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}

@Composable
private fun DownloadingList(pending: List<DlTask>) {
    if (pending.isEmpty()) {
        Text(
            "当前没有下载任务。在影片介绍页点「⬇ 下载到本机」就会出现在这里，并显示进度、速度与剩余时间。",
            color = TextFaint, fontSize = 12.sp, lineHeight = 18.sp,
        )
        return
    }
    pending.forEach { t ->
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp)).background(CardColor).padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.title, color = HplayOnSurface, fontSize = 12.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (t.quality > 0) t.quality.toString() + "P" else "", color = TextFaint, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            // 手绘进度条（不用 material3 的 LinearProgressIndicator：这一版的签名带 lambda 与
            // drawStopIndicator，跨版本容易踩空；两条 Box 行为完全可控）。
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(CardColorSoft)) {
                if (t.determinate) {
                    Box(Modifier.fillMaxWidth(t.fraction).fillMaxHeight().background(AccentRed))
                } else {
                    Box(Modifier.fillMaxWidth(0.32f).fillMaxHeight().background(AccentRed.copy(alpha = 0.62f)))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.phase + "　" + t.sizeText + if (t.segText.isNotEmpty()) "　" + t.segText else "",
                    color = TextDim, fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(t.speedText + "　" + t.etaText, color = TextDim, fontSize = 11.sp)
            }
            // 重试次数如实显示：用户能看到"它在重试"，而不是干等（第 49 轮）。
            if (t.retries > 0) {
                Spacer(Modifier.height(3.dp))
                Text("已重试 ${t.retries} 次", color = HplayWarning, fontSize = 10.5.sp)
            }
            if (t.error.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(t.error, color = HplayErrorSoft, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (t.status) {
                    // 正在下 / 排队中：可暂停
                    DlStatus.RUNNING, DlStatus.QUEUED -> {
                        Text(
                            "⏸ 暂停", color = TextDim, fontSize = 11.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                                .clickable { HplayDownloads.pause(t.id) }.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                        Text(
                            "取消", color = TextDim, fontSize = 11.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                                .clickable { HplayDownloads.cancel(t.id) }.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    // 已暂停：可续传（已下好的分片会跳过，是真续传）
                    DlStatus.PAUSED -> {
                        Text(
                            "▶ 继续", color = Color.White, fontSize = 11.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(AccentRed)
                                .clickable { HplayDownloads.resume(t.id) }.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                        Text(
                            "取消", color = TextDim, fontSize = 11.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                                .clickable { HplayDownloads.cancel(t.id) }.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    // 第 58 轮：**失败的任务给「重试」按钮**（用户要求）。
                    // 前提改动在引擎侧：失败时不再删掉 Job，而是留在 failedJobs 里，
                    // 所以重试能复用原始 URL / 详情 / 质量档，并跳过已下好的分片（真续传）。
                    DlStatus.FAILED -> {
                        if (HplayDownloads.canRetry(t.id)) {
                            Text(
                                "↻ 重试", color = Color.White, fontSize = 11.5.sp,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(AccentRed)
                                    .clickable { HplayDownloads.retry(t.id) }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            "移除记录", color = TextDim, fontSize = 11.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                                .clickable { HplayDownloads.remove(t.id) }.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    else -> Text(
                        "移除记录", color = TextDim, fontSize = 11.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                            .clickable { HplayDownloads.remove(t.id) }.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** 分片并发度选择（用户第 4 条：可选内置多线程）。 */
@Composable
private fun ConcChooser(onDismiss: () -> Unit, onApplied: () -> Unit) {
    var n by remember { mutableStateOf(HplayDownloads.concurrency()) }
    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp).clip(RoundedCornerShape(16.dp)).background(CardColor)
                .clickable { }.padding(14.dp),
        ) {
            Text("下载线程数", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "单个任务内部的分片并发数。1 = 串行最稳但慢；默认值经带宽实测标定，更高更快但遇到限流的站可能反而变慢（程序会按域自动降速）。" +
                    "同时下载的任务数最多 " + HplayDownloads.MAX_PARALLEL_TASKS + " 个。",
            )
            Spacer(Modifier.height(10.dp))
                // ★ 第 156 轮修（用户报「只显示到 9，后面的没有」）：
                // 16 个按钮横排会超出弹窗宽度被裁掉，改为两行各 8 个。
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..8).forEach { i ->
                            Text(
                                i.toString(),
                                color = if (i == n) Color.White else TextDim,
                                fontSize = 12.5.sp,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                    .background(if (i == n) AccentRed else CardColorSoft)
                                    .clickable { n = i }.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (9..16).forEach { i ->
                            Text(
                                i.toString(),
                                color = if (i == n) Color.White else TextDim,
                                fontSize = 12.5.sp,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                    .background(if (i == n) AccentRed else CardColorSoft)
                                    .clickable { n = i }.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "取消", color = TextDim, fontSize = 12.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(CardColorSoft)
                        .clickable { onDismiss() }.padding(horizontal = 16.dp, vertical = 7.dp),
                )
                Text(
                    "应用", color = Color.White, fontSize = 12.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(AccentRed)
                        .clickable { HplayDownloads.setConcurrency(n); onApplied() }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 保存目录选择：三个「真实可写候选」+ 自定义绝对路径，选中前一律过写探针。 */
@Composable
private fun DirChooser(current: File, onDismiss: () -> Unit, onApplied: () -> Unit) {
    var custom by remember { mutableStateOf(current.absolutePath) }
    var msg by remember { mutableStateOf<String?>(null) }
    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp).clip(RoundedCornerShape(16.dp)).background(CardColor)
                .clickable { }.padding(14.dp),
        ) {
            Text("保存目录", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            HplayDownloads.candidates().forEach { opt ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp)).background(CardColorSoft).padding(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(opt.label, color = HplayOnSurface, fontSize = 12.5.sp)
                        Text(
                            opt.dir.absolutePath, color = TextFaint, fontSize = 10.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(opt.note, color = TextFaint, fontSize = 10.5.sp, lineHeight = 14.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (opt.dir.absolutePath == current.absolutePath) "当前" else "选用",
                        color = Color.White, fontSize = 11.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (opt.dir.absolutePath == current.absolutePath) CardColorSoft else AccentRed)
                            .clickable {
                                val r = HplayDownloads.setSaveDir(opt.dir)
                                if (r == null) onApplied() else msg = r
                            }.padding(horizontal = 11.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("自定义路径（绝对路径）", color = TextDim, fontSize = 11.5.sp)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CardColorSoft)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = custom, onValueChange = { custom = it; msg = null }, singleLine = true,
                    textStyle = TextStyle(color = HplayOnSurface, fontSize = 12.5.sp),
                    cursorBrush = SolidColor(AccentRed), modifier = Modifier.fillMaxWidth(),
                )
            }
            msg?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = HplayErrorSoft, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "取消", color = TextDim, fontSize = 12.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(CardColorSoft)
                        .clickable { onDismiss() }.padding(horizontal = 16.dp, vertical = 7.dp),
                )
                Text(
                    "应用", color = Color.White, fontSize = 12.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(AccentRed)
                        .clickable {
                            val r = HplayDownloads.setSaveDir(File(custom.trim()))
                            if (r == null) onApplied() else msg = r
                        }.padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}

private fun humanBytes(n: Long): String = when {
    n >= 1073741824L -> "%.2f GB".format(n / 1073741824.0)
    n >= 1048576L -> "%.1f MB".format(n / 1048576.0)
    n >= 1024L -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

/**
 * 删除确认弹窗（第 53 轮，用户要求「删除时加入确认删除弹窗」）。
 *
 * 自带淡入 + 轻微缩放的进场动画（与页面转场同一套时长口径）。
 * 删除进行中时按钮变「删除中…」且不可重复点 —— 后台真在删，界面不假装已完成。
 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 130),  // ★ LABANIM
        label = "confirm-alpha",
    )
    val sc by animateFloatAsState(
        targetValue = if (shown) 1f else 0.94f,
        animationSpec = tween(durationMillis = 180),
        label = "confirm-scale",
    )
    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { if (!busy) onCancel() }
            .graphicsLayer { alpha = a },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.86f)
                .graphicsLayer { alpha = a; scaleX = sc; scaleY = sc }
                .clip(RoundedCornerShape(16.dp)).background(CardColor)
                .clickable { }
                .padding(16.dp),
        ) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(body.replace("**", ""), color = TextDim, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                Text(
                    "取消", color = TextDim, fontSize = 12.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                        .clickable { if (!busy) onCancel() }.padding(horizontal = 16.dp, vertical = 7.dp),
                )
                Text(
                    if (busy) "删除中…" else "删除",
                    color = Color.White, fontSize = 12.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (busy) Color(0xFF6B3538) else HplayPrimary)
                        .clickable { onConfirm() }.padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}
