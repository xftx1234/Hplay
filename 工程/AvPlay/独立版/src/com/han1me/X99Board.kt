package com.han1me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javrocket.hanime.HanimeVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 站点是否使用 MissAV 专用解析（其余站点统一走通用卡片解析）。 */
fun x99IsMissav(name: String): Boolean = name.equals("MissAV", ignoreCase = true)

/**
 * 站点是否为内置 xchina 板块：走 xchina 专用解析 + **双排封面** + **原生详情**（不进网页版）。
 * 与 x99IsMissav 一样按站点名判定；xchina 不在 x99dh 目录里，由 X99.builtinSites 注入。
 */
fun x99IsXchina(name: String): Boolean = X99.isXchinaSite(name)

/**
 * 「站点合集」板块：x99dh.cc 收录的站点集中在此，**统一卡片 UI**（不再使用网页模式）。
 * 顶部站点按钮行（点击切换，首次进入自动选最快线路）；右侧「⇄ 站点/优选」弹窗；
 * 卡片点击进**介绍页**（与官方站 / xchina 同一套版式：封面 + 时长 + 简介 + 画质或「网页播放」+ 相关影片）；左上「← 返回」回到本命板块。
 * 列表解析：MissAV 走专用解析，其余站点走通用卡片解析（线路 URL → 缓存路径 → 候选路径）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun X99Board(
    refreshKey: Int,
    onToast: (String) -> Unit,
    onOpenDetail: (HanimeVideo) -> Unit,
    onOpenDialog: (String) -> Unit,
    onBack: () -> Unit,
) {
    var sites by remember { mutableStateOf(X99.otherSites()) }
    var catalogErr by remember { mutableStateOf("") }
    var siteName by remember { mutableStateOf(X99.setting("x99.site", X99.DEFAULT_SITE)) }
    var tab by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(1) }
    var cards by remember { mutableStateOf<List<HanimeVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var lineUrl by remember { mutableStateOf("") }
    var exhausted by remember { mutableStateOf(false) }        // 已到末页（或站点开始重复给同一页）
    var maxPage by remember { mutableStateOf(0) }              // 站点分页器给出的总页数；0 = 未知
    var loadingMore by remember { mutableStateOf(false) }
    /**
     * 列表请求的代号。用户 2026-10-02 报「板块切换后视频封面和源会滞留」，封面这一半就在这里：
     * `load()` 把请求丢进一个**不属于本次组合**的 `CoroutineScope(Dispatchers.IO)`，
     * `LaunchedEffect` 换键时只取消它自己，管不到已经飞出去的那一发；于是慢的上一站回包会把新站的
     * `cards` 整片覆盖回去（MissAV 慢站 4~9 s，够用户点两三个板块），界面看起来就是「还挂着上一个板块的封面」。
     * 换站/换页签/换线路时代号 +1，回包时代号不符就整包丢弃。
     */
    var loadGen by remember { mutableStateOf(0) }
    var loadedSite by remember { mutableStateOf("") }          // 当前 cards 实际属于哪个站（换站要立刻清空）
    var showAgg by remember { mutableStateOf(false) }           // 聚合搜索面板
    var webFallback by remember { mutableStateOf<String?>(null) }   // 动态站点兜底：内置浏览器打开

    // 目录（每次启动由外部刷新一次；此处只取内存/缓存）
    LaunchedEffect(refreshKey) {
        val list = withContext(Dispatchers.IO) { X99.loadCatalog(false) }
        sites = X99.otherSites()
        catalogErr = if (sites.isEmpty()) X99.catalogError else ""
        if (sites.isEmpty() && catalogErr.isBlank()) catalogErr = "未取得站点目录"
        val want = X99.setting("x99.site", siteName)
        if (sites.any { it.name.equals(want, true) }) siteName = want
        if (sites.isNotEmpty() && sites.none { it.name.equals(siteName, true) }) {
            siteName = sites.firstOrNull { it.name.equals(X99.DEFAULT_SITE, true) }?.name ?: sites.first().name
        }
        if (list.isEmpty() && sites.isEmpty()) error = catalogErr
    }

    val site: X99Site? = sites.firstOrNull { it.name.equals(siteName, true) } ?: sites.firstOrNull()
    val missav = site != null && x99IsMissav(site.name)
    val xchina = site != null && x99IsXchina(site.name)
    // 页签：有内置栏目表的站直接用（MissAV / xchina / qinav 各有一套）；
    // 其余站在用户点开「☰ 子选项」时才现抓分类链接（见 boardOptionsLive），不自动预取。
    val tabs: List<Pair<String, String>> = if (site != null) X99.boardOptions(site.name) else emptyList()
    // 从「子选项」弹窗里选中的栏目（内置栏目表之外的站靠它切分类）
    var optionPath by remember { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var optionBusy by remember { mutableStateOf(false) }
    val currentPath: String? = tabs.getOrNull(tab)?.second ?: optionPath

    // 站点切换 → 选线路（没存过就自动挑延迟最低的）
    LaunchedEffect(site?.name, refreshKey) {
        val s = site ?: return@LaunchedEffect
        var saved = X99.setting("x99.line.${s.name}", "")
        if (saved.isBlank()) {
            picking = true
            // 测速途中换板块会取消这个 effect，取消点落在 withContext 里面：不放进 finally 的话
            // picking 永远停在 true，界面就一直显示「正在测速并自动选择最快线路…」——那是另一种滞留。
            val fast = try {
                withContext(Dispatchers.IO) { X99.fastestLine(s.lines) }
            } finally {
                picking = false
            }
            if (fast != null) {
                X99.saveLine(s, fast)
                saved = fast.url
                onToast("已自动选择最快线路：${fast.host}")
            }
        }
        lineUrl = saved.ifBlank { s.lines.firstOrNull()?.url ?: s.url }
    }

    /**
     * 取一页。`append=true` 是「加载更多」：结果**追加**而不是替换，并按 URL 去重——
     * 有些站点越界后会把第 1 页再给一遍，不去重就会出现「加载更多」永远加得动、内容却全是重复。
     * `append=false` 是「从头重来」（换站点/换页签/换线路/点重试），所以先把页码归 1：
     * 不归零的话在页第 3 层切页签会去取「新页签的第 3 页」，重试也只会把已追加的卡片换成一页。
     */
    fun load(append: Boolean = false) {
        val s = site ?: return
        val base = lineUrl
        if (base.isBlank()) return
        val myGen = loadGen
        if (append) {
            if (loadingMore || loading) return
            loadingMore = true
        } else {
            loading = true
            error = null
            page = 1
            exhausted = false
        }
        val wantPage = if (append) page + 1 else page
        val path = currentPath
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { X99.boardListing(s.name, base, path, wantPage) }
            withContext(Dispatchers.Main) {
                // 迟到的上一站响应：既不能盖掉新站的卡片，也不能把新站那发的 loading 关掉
                if (myGen != loadGen) return@withContext
                result.onSuccess { got ->
                    maxPage = X99.boardMaxPage(s.name, path)
                    if (append) {
                        val seen = HashSet(cards.map { it.url })
                        val fresh = got.filter { seen.add(it.url) }
                        if (got.isEmpty() || fresh.isEmpty()) {
                            exhausted = true                    // 空页或全是重复 ⇒ 到此为止
                        } else {
                            cards = cards + fresh
                            page = wantPage
                        }
                    } else {
                        cards = got
                        exhausted = got.isEmpty()
                    }
                    // 分页器给了总页数就以它为准（xchina 实测：全部影片 13 页 / 国产 9 / 日本AV 4）
                    if (maxPage > 0 && page >= maxPage) exhausted = true
                }.onFailure {
                    val why = it.message ?: it.toString()
                    val dns = X99.lastBoardDns
                    // 手机抑制应用日志，所以「这一族域名到底连上了哪个地址」只能在失败时上屏：
                    // 只在连接类失败时附，内容/解析类失败带上去只会把真正的原因淹掉。
                    val connectish = why.contains("connect", true) || why.contains("timed out", true) ||
                        why.contains("Unable to resolve", true) || why.contains("Network", true)
                    error = (if (append) "第 $wantPage 页加载失败：" else "") + why +
                        (if (!append && connectish && dns.isNotBlank()) "｜解析：$dns" else "")
                }
                loading = false
                loadingMore = false
            }
        }
    }

    // **键里不能有 page**：追加成功后 page 会变，这个 effect 就会用 append=false 重跑一次，
    // 把刚追加进来的旧页全部换成单页——上一版「加载更多点了没累加」就是这么来的。
    LaunchedEffect(site?.name, tab, refreshKey, lineUrl, optionPath) {
        if (site == null || lineUrl.isBlank()) return@LaunchedEffect
        loadGen++                     // 作废所有还在路上的上一轮响应（见 loadGen 的说明）
        // 换站先把上一站的卡片撤掉：残留的封面比空态更骗人（它看着像新站已经加载完了）
        if (loadedSite != site.name) {
            cards = emptyList()
            maxPage = 0
            loadedSite = site.name
            webFallback = null        // 上一站留下的兜底浏览器页同样属于"源滞留"
        }
        load()
    }

    if (showAgg) {
        AggregateSearchPanel(
            sites = sites,
            onOpen = { v -> showAgg = false; onOpenDetail(v) },
            onClose = { showAgg = false },
        )
        return
    }

    if (showOptions && site != null) {
        BoardOptionDialog(
            siteName = site.name,
            base = lineUrl.ifBlank { site.url },
            current = currentPath,
            onPick = { path -> optionPath = path; tab = 0; page = 1; showOptions = false },
            onClose = { showOptions = false },
        )
        return
    }

    if (webFallback != null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(BarColor).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    "← 返回卡片列表", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp)).background(AccentRed)
                        .clickable { webFallback = null }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("该站点为动态站点，已用内置浏览器打开（去广告已注入）", color = TextFaint, fontSize = 11.sp)
            }
            androidx.compose.runtime.key(webFallback) {
                SiteWebPane(url = webFallback!!, title = site?.name ?: "", onToast = onToast, modifier = Modifier.fillMaxSize())
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 站点按钮行（本板块上方；左侧为返回键） ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(BarColor).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                "← 返回", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp)).background(AccentRed)
                    .clickable { onBack() }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            ) {
                sites.forEach { s ->
                    val active = s.name.equals(site?.name ?: "", true)
                    Text(
                        s.name,
                        color = if (active) Color.White else Color(0xFFB9B9C0),
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (active) AccentRed else CardColorSoft)
                            .clickable {
                                siteName = s.name
                                X99.putSetting("x99.site", s.name)
                                tab = 0
                                page = 1
                                lineUrl = ""
                                optionPath = null   // 换站必须清掉上一站选中的栏目，否则拿 A 站的分类路径去请求 B 站
                            }
                            .padding(horizontal = 11.dp, vertical = 5.dp),
                    )
                }
                if (sites.isEmpty()) {
                    Text(if (catalogErr.isBlank()) "正在获取站点…" else "站点目录获取失败：$catalogErr",
                        color = TextDim, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "⇄ 站点/优选",
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2E2E34))
                    .clickable { onOpenDialog(site?.name ?: X99.DEFAULT_SITE) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineColor))

        if (site == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (catalogErr.isBlank()) "正在获取站点目录…" else "站点目录获取失败：$catalogErr", color = TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { com.han1me.X99.loadCatalog(true); sites = X99.otherSites() }) {
                        Text("重试", color = AccentRed)
                    }
                }
            }
            return@Column
        }

        // ---------- 工具行（分类/翻页/搜索） ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            ) {
                if (tabs.isNotEmpty()) {
                    tabs.forEachIndexed { i, (label, _) ->
                        val active = i == tab
                        Text(
                            label,
                            color = if (active) Color.White else TextDim,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (active) AccentRed else CardColorSoft)
                                .clickable { tab = i; page = 1 }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                } else {
                    Text(
                        site.desc.ifBlank { "统一卡片浏览" }, color = TextFaint, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "⟳ 刷新", color = TextDim, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(13.dp)).background(CardColorSoft)
                            .clickable { load() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 「☰ 子选项」：站内所有分类/栏目收进一个弹窗（用户 2026-10-01 要求）。
            // 有内置栏目表的站（MissAV / xchina / qinav）零请求；其余站点开时才发**一发**列表页请求现抓。
            Text(
                "☰ 子选项", color = if (currentPath != null) Color.White else TextDim, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    .background(if (currentPath != null) AccentRed else CardColorSoft)
                    .clickable { showOptions = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(8.dp))
            // 聚合搜索：一次输入并发查所有板块（用户点名要 missav 区 + xchina 全板块）
            Text(
                "🔍", fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(15.dp)).clickable { showAgg = !showAgg }.padding(6.dp),
            )
            if (missav || xchina) {
                Text(
                    "🔎", fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(15.dp)).clickable { showSearch = !showSearch }.padding(6.dp),
                )
            }
            Text(
                "‹", color = if (page > 1) Color.White else TextFaint, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                    .clickable { if (page > 1) page -= 1 }.padding(horizontal = 10.dp),
            )
            Text(if (maxPage > 0) " $page/$maxPage " else " $page ", color = TextDim, fontSize = 12.sp)
            Text(
                "›", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                    .clickable { page += 1 }.padding(horizontal = 10.dp),
            )
            Spacer(Modifier.width(6.dp))
            // 「加载更多」＝按网页版继续往下翻页并**追加**（用户要求"展示所有视频可翻页更多"）
            Text(
                if (loadingMore) "加载中…" else if (exhausted) "已到末页" else "加载更多 ↓",
                color = if (exhausted || loadingMore) TextFaint else Color.White, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                    .clickable(enabled = !exhausted && !loadingMore && !loading) { load(append = true) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (showSearch && (missav || xchina)) {
            SearchRow(
                query = query,
                onQuery = { query = it },
                onSearch = {
                    val q = query.trim()
                    if (q.isNotEmpty()) {
                        showSearch = false
                        loading = true
                        error = null
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            val r = runCatching {
                                if (xchina) X99.xchinaSearch(X99.originOf(lineUrl), q)
                                else X99.missavSearch(X99.originOf(lineUrl), q, 1)
                            }
                            withContext(Dispatchers.Main) {
                                r.onSuccess { cards = it; page = 1 }.onFailure { error = it.message }
                                loading = false
                            }
                        }
                    }
                },
                onClose = { showSearch = false },
            )
        }
        error?.let { msg ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                Text("出错：$msg", color = Color(0xFFFF8A8A), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    "重试", color = AccentRed, fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp)).background(CardColorSoft)
                        .clickable { load() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        when {
            loading || picking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentRed)
                    if (picking) {
                        Spacer(Modifier.height(10.dp))
                        Text("正在测速并自动选择最快线路…", color = TextDim, fontSize = 12.sp)
                    }
                }
            }
            cards.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("该站点未解析到卡片（可能是动态站点或改版）", color = TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "在内置浏览器打开（去广告）", color = Color.White, fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp)).background(AccentRed)
                                .clickable { webFallback = lineUrl.ifBlank { site.url } }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        Text(
                            "换一条线路", color = Color.White, fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp)).background(Color(0xFF2E2E34))
                                .clickable { onOpenDialog(site.name) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        Text(
                            "重新解析", color = Color.White, fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp)).background(Color(0xFF2E2E34))
                                .clickable { X99.putSetting("x99.path.${site.name}", ""); load() }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            else -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                // 版式统一（用户要求「其他板块一律按新 xchina 的界面」）：**按可用宽度判而不是按板块名判**。
                // 手机上（APK 的全部使用场景）所有板块都是双排封面；桌面宽窗口才退回自适应，
                // 否则 1400px 的窗口里只有两列巨卡。xchina 无论宽窄都保持双排，与已经交付的形态一致。
                val twoCol = xchina || maxWidth < 720.dp
                LazyVerticalGrid(
                    columns = if (twoCol) GridCells.Fixed(2) else GridCells.Adaptive(200.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                items(cards, key = { it.url }) { v ->
                    // **所有板块的卡片都先进介绍页**（用户 2026-10-01 点名：图1 那套版式要铺满 MissAV 区）。
                    // 分流不等于「硬说这些站点能原生播」：`X99.boardPage` 取不到可验证直链时返回 sources 为空的
                    // 详情，介绍页上给的是「▶ 网页播放」，走的还是原来那条内置浏览器路径（去广告注入不变）。
                    SiteCard(v, width = null, onClick = { onOpenDetail(v) })
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    ) {
                        Text(
                            when {
                                loadingMore -> "正在加载第 ${page + 1} 页…"
                                exhausted -> if (maxPage > 0) "已到末页（$page/$maxPage）· 共 ${cards.size} 条"
                                else "已到末页 · 共 ${cards.size} 条"
                                else -> "还有更多：共已加载 ${cards.size} 条，点「加载更多 ↓」继续"
                            },
                            color = TextFaint, fontSize = 11.5.sp,
                        )
                    }
                }
                }
            }
        }
    }
}

/**
 * 「子选项」弹窗：把站内所有分类/栏目收进一处可选（用户 2026-10-01 要求）。
 * 有内置栏目表的站（MissAV / xchina / qinav）直接列，零网络请求；
 * 其余站点在**弹窗打开时**发一发列表页请求现抓分类链接（那些站几乎都有频控，不预取、不重试）。
 * 抓不到就如实写「该站没解析出分类」，不给一个空壳列表让人以为功能坏了。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardOptionDialog(
    siteName: String,
    base: String,
    current: String?,
    onPick: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val preset = X99.boardOptions(siteName)
    var live by remember(siteName) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var busy by remember(siteName) { mutableStateOf(preset.isEmpty()) }
    var err by remember(siteName) { mutableStateOf("") }
    LaunchedEffect(siteName, preset.isEmpty()) {
        if (preset.isNotEmpty()) return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { X99.boardOptionsLive(siteName, base) } }
        live = r.getOrDefault(emptyList())
        err = if (live.isEmpty()) (r.exceptionOrNull()?.message ?: "该站没解析出分类") else ""
        busy = false
    }
    val opts = if (preset.isNotEmpty()) preset else live
    Box(Modifier.fillMaxSize().background(Color(0xE60A0A0C)).clickable { onClose() }) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141417))
                .clickable(enabled = false) {},
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("$siteName · 子选项", color = Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("✕", color = TextDim, fontSize = 14.sp, modifier = Modifier
                    .clip(RoundedCornerShape(12.dp)).clickable { onClose() }.padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(
                    "全部（不选栏目，用站点默认列表）", color = if (current == null) Color.White else TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (current == null) AccentRed else CardColorSoft)
                        .clickable { onPick(null) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = AccentRed, modifier = Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在读取该站分类…", color = TextDim, fontSize = 12.sp)
                    }
                }
                if (err.isNotEmpty()) Text(err, color = Color(0xFFFF8A8A), fontSize = 11.5.sp)
                opts.forEach { (label, path) ->
                    val active = current == path
                    Text(
                        label, color = if (active) Color.White else Color(0xFFCFCFD4), fontSize = 12.5.sp,
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) AccentRed else Color(0xFF1C1C21))
                            .clickable { onPick(path) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** 动态站点兜底用的内嵌浏览器（保留站点按钮行与返回键）。 */
@Composable
private fun SiteWebPane(
    url: String,
    title: String,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = com.javrocket.playground.webview.rememberAppWebViewState(url) {
        enableWideViewport()
        enableDomStorage()
    }
    val navigator = com.javrocket.playground.webview.rememberAppWebViewNavigator()
    AdHideEffect(navigator, state)
    Column(modifier// ★ 2026-11-25（第 319 轮）：改半透明，让全局壁纸透出来
        .background(BgColor.copy(alpha = 0.82f))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(40.dp).background(BarColor).padding(horizontal = 10.dp),
        ) {
            Text(
                "‹", color = Color(0xFFD8D8DC), fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(13.dp)).background(CardColorSoft)
                    .clickable { runCatching { if (navigator.canGoBack) navigator.navigateBack() } }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                state.pageTitle?.takeIf { it.isNotBlank() } ?: title,
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "⟳", color = Color(0xFFD8D8DC), fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(13.dp)).background(CardColorSoft)
                    .clickable {
                        runCatching {
                            val u = (state.lastLoadedUrl ?: "").ifBlank { url }
                            navigator.loadUrl(u, emptyMap())
                        }
                    }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "↗", color = Color(0xFFD8D8DC), fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(13.dp)).background(CardColorSoft)
                    .clickable {
                        runCatching {
                            val u = (state.lastLoadedUrl ?: "").ifBlank { url }
                            java.awt.Desktop.getDesktop().browse(java.net.URI(u))
                        }
                    }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Box(Modifier.fillMaxSize()) {
            com.javrocket.playground.webview.AppWebView(
                state, navigator, Modifier.fillMaxSize(), null, false,
                { _ -> }, { _, _ -> }, { _ -> },
            )
        }
    }
}

/**
 * 「优选访问」弹窗：站点列表（切换站点）+ 该站点线路（自动测速、按延迟排序、点击切换）。
 * 顶部「⚡ 自动选最快线路」= 一键测速并切到延迟最低的那条。本命板块与站点合集共用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun X99Dialog(
    focusSite: String,
    hanimeLineUrl: String,
    onClose: () -> Unit,
    onSwitchSite: (String) -> Unit,
    onPickLine: (X99Site, X99Line) -> Unit,
    onToast: (String) -> Unit,
) {
    val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
    var sites by remember { mutableStateOf(X99.sites) }
    var err by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(focusSite) }
    val delay = remember { mutableStateMapOf<String, Long>() } // line.url -> ms（-1 失败，-2 测试中）
    var round by remember { mutableStateOf(0) }
    var autoBusy by remember { mutableStateOf(false) }

    LaunchedEffect(round) {
        if (sites.isEmpty()) {
            val list = withContext(Dispatchers.IO) { X99.loadCatalog(round > 0) }
            sites = list
            err = if (list.isEmpty()) X99.catalogError.ifBlank { "站点目录获取失败" } else ""
        }
        val s = sites.firstOrNull { it.name.equals(current, true) } ?: return@LaunchedEffect
        s.lines.forEach { ln ->
            delay[ln.url] = -2L
            scope.launch {
                val ms = withContext(Dispatchers.IO) { X99.testLine(ln) }
                delay[ln.url] = ms
            }
        }
    }

    val site = sites.firstOrNull { it.name.equals(current, true) }
    val isHanime = site?.name.equals(X99.HANIME_SITE, true) == true
    // 本命板块：第 1 条固定为「官方源」（可登录；DoH/代理等网络功能只对它生效），其后为镜像线路
    val officialLine = X99Line(X99.OFFICIAL_HANIME, "")
    val pickedUrl = when {
        isHanime -> hanimeLineUrl.ifBlank { X99.OFFICIAL_HANIME }
        site != null -> X99.selectedLine(site).url
        else -> ""
    }

    Box(Modifier.fillMaxSize().background(Color(0xE60A0A0C)).clickable { onClose() }) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.62f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141417))
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("优选访问", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text("选站点或线路；线路按延迟自动排序", color = TextFaint, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { round += 1 }) { Text("重新测速", color = AccentRed, fontSize = 12.sp) }
                TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(6.dp))

            Text("站点", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sites.forEach { s ->
                    val active = s.name.equals(current, true)
                    Text(
                        s.name,
                        color = if (active) Color.White else Color(0xFFCFCFD4), fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (active) AccentRed else CardColorSoft)
                            .clickable { current = s.name }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            if (sites.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    (if (site?.name.equals(X99.HANIME_SITE, true)) "本命板块（Hanime1）：第 1 条线路＝官方源（可登录，DoH 生效）；其余为镜像（直连）"
                    else "该板块：选线路后立即生效") +
                        (site?.desc?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    color = TextFaint, fontSize = 10.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text("切换到：", color = TextDim, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "本命 Hanime 板块",
                        color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF2E2E34))
                            .clickable { onSwitchSite(X99.HANIME_SITE) }.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Text(
                        "站点合集板块（含 MissAV）",
                        color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF2E2E34))
                            .clickable { onSwitchSite("__sites__") }.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            if (err.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(err, color = Color(0xFFFF8A8A), fontSize = 11.sp)
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("线路（优选访问）", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (autoBusy) "测速中…" else "⚡ 自动选最快线路",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (autoBusy) Color(0xFF2E2E34) else AccentRed)
                        .clickable(enabled = !autoBusy) {
                            val s = site ?: return@clickable
                            autoBusy = true
                            scope.launch {
                                val fast = withContext(Dispatchers.IO) { X99.fastestLine(s.lines) }
                                autoBusy = false
                                if (fast == null) onToast("测速失败：没有可用线路")
                                else onPickLine(s, fast)
                            }
                        }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            val mirror = site?.lines.orEmpty()
            if (mirror.isEmpty() && !isHanime) Text("（无线路）", color = TextFaint, fontSize = 11.sp)
            // 排序：官方源固定第一；其余已测好的按延迟升序，未测/测试中随后，失败最后
            val sorted = buildList {
                if (isHanime) add(officialLine)
                addAll(mirror.sortedBy { ln ->
                    val d = delay[ln.url]
                    when {
                        d == null || d == -2L -> 1_000_000L
                        d < 0 -> 2_000_000L
                        else -> d
                    }
                })
            }
            sorted.forEach { ln ->
                val d = delay[ln.url]
                val official = ln.url == X99.OFFICIAL_HANIME
                val selected = ln.url == pickedUrl
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color(0xFF23232A) else Color(0xFF1A1A1E))
                        .clickable { site?.let { onPickLine(it, ln) } }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(if (selected) "✔" else "○", color = if (selected) AccentRed else TextFaint, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (official) "hanime1.me（官方源 · 可登录 · DoH 生效）" else ln.host,
                        color = if (selected) Color.White else Color(0xFFCFCFD4), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(
                        when {
                            d == null -> "…"
                            d == -2L -> "测速中"
                            d < 0 -> if (official) "官方源" else "线路异常"
                            else -> "延迟 $d ms"
                        },
                        color = when {
                            d == null || d == -2L -> TextFaint
                            d < 0 -> if (official) TextFaint else Color(0xFFFF8A8A)
                            else -> Color(0xFF7ED97E)
                        },
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}


/**
 * 聚合搜索：一次输入，并发查**所有板块**（MissAV 各线路 + xchina + 目录里其它站点），按板块分组给结果。
 *
 * 三条硬约束：
 *  · **并发上限 3**：这些站点几乎都有频控（xchina 前置 Cloudflare，实测连续快取就翻挑战、持续高频会硬封），
 *    一次打三十站等于替用户把出口 IP 打进黑名单。
 *  · **每板块单独超时**（20 s）：一个慢站不能拖死整屏。
 *  · **状态如实报**：「N 条」「0 条」「无搜索入口」「失败：原因」四种分开写——
 *    把「没搜到」和「没搜成」混成一个空列表，用户就以为片库里没有。
 */
@Composable
fun AggregateSearchPanel(
    sites: List<X99Site>,
    onOpen: (HanimeVideo) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val status = remember { mutableStateMapOf<String, String>() }
    val found = remember { mutableStateMapOf<String, List<HanimeVideo>>() }
    // 并发闸与取消域都**跟着面板记忆**：
    //  · 聚合搜索和「点板块名单发探测」共用同一个 Semaphore，探测不再另起一个无上限的 scope；
    //  · panelScope 随组合离开而取消，面板关掉之后不会再有回调往 mutableStateMapOf 里写。
    val gate = remember { Semaphore(3) }
    val panelScope = rememberCoroutineScope()

    LaunchedEffect(submitted) {
        val q = submitted.trim()
        if (q.isEmpty()) return@LaunchedEffect
        status.clear()
        found.clear()
        busy = true
        sites.forEach { status[it.name] = "查询中…" }   // 主线程预置，别在 IO 线程里写第一帧状态
        coroutineScope {
            sites.map { s ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val line = X99.selectedLine(s).url
                        val r = runCatching {
                            // allowProbe=false：不给未知板块现场猜搜索入口（每站最多 6 发 × 几十站＝自找封禁）
                            withTimeout(20_000) { X99.boardSearch(s.name, line, q, 1, allowProbe = false) }
                        }
                        withContext(Dispatchers.Main) {
                            r.onSuccess { list ->
                                when {
                                    list == null -> status[s.name] =
                                        if (X99.setting("x99.search.${s.name}", "") == "none") "无搜索入口"
                                        else "未探测 · 点板块名试一次"
                                    list.isEmpty() -> status[s.name] = "0 条"
                                    else -> {
                                        found[s.name] = list
                                        status[s.name] = "${list.size} 条"
                                    }
                                }
                            }.onFailure { t ->
                                status[s.name] = "失败：" + (t.message ?: t.toString()).take(40)
                            }
                        }
                    }
                }
            }.forEach { runCatching { it.await() } }
        }
        busy = false
    }

    Column(Modifier.fillMaxSize()// ★ 2026-11-25（第 319 轮）：改半透明，让全局壁纸透出来
        .background(BgColor.copy(alpha = 0.82f))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(BarColor).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                "← 返回板块", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(AccentRed)
                    .clickable { onClose() }.padding(horizontal = 11.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "聚合搜索 · ${sites.size} 个板块 · 并发 3 · 单板块 20s 超时",
                color = TextFaint, fontSize = 11.sp, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        SearchRow(
            query = query,
            onQuery = { query = it },
            onSearch = { if (query.trim().isNotEmpty()) submitted = query.trim() },
            onClose = { onClose() },
        )
        if (submitted.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("输入关键词，一次查全部板块", color = TextDim, fontSize = 13.sp)
            }
            return@Column
        }
        // 分组结果：板块名做整跨页脚，卡片两列（与 xchina 板块同一版式）
        val names = sites.map { it.name }.filter { status.containsKey(it) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            names.forEach { nm ->
                item(
                    key = "hdr-$nm",
                    span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text(
                            nm, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            // 点板块名＝只为这一个板块探测搜索入口（用户显式触发，请求数有上限，结果永久缓存）
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !busy && status[nm] == "未探测 · 点板块名试一次") {
                                    val target = sites.firstOrNull { it.name == nm } ?: return@clickable
                                    status[nm] = "探测中…"
                                    // 走面板的取消域 + 共用闸门：上一版这里另起 CoroutineScope(IO)，
                                    // 既不受 Semaphore(3) 约束（连点几个板块就是几路并发探测），
                                    // 也不会随面板关闭取消。探测最坏 6 发，必须排队。
                                    panelScope.launch(Dispatchers.IO) {
                                        gate.withPermit {
                                            val line = X99.selectedLine(target).url
                                            val r = runCatching { X99.boardSearch(nm, line, submitted.trim(), 1) }
                                            withContext(Dispatchers.Main) {
                                                r.onSuccess { list ->
                                                    when {
                                                        list == null -> status[nm] = "无搜索入口"
                                                        list.isEmpty() -> status[nm] = "0 条"
                                                        else -> {
                                                            found[nm] = list
                                                            status[nm] = "${list.size} 条"
                                                        }
                                                    }
                                                }.onFailure { t -> status[nm] = "失败：" + (t.message ?: t.toString()).take(40) }
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            status[nm] ?: "",
                            color = when {
                                (status[nm] ?: "").endsWith("条") && !(status[nm] ?: "").startsWith("0") -> Color(0xFF7ED97E)
                                (status[nm] ?: "").startsWith("失败") -> Color(0xFFFF8A8A)
                                else -> TextFaint
                            },
                            fontSize = 11.sp,
                        )
                        if (busy && status[nm] == "查询中…") {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(color = AccentRed, modifier = Modifier.height(12.dp).width(12.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                items(found[nm].orEmpty().take(24), key = { "$nm|" + it.url }) { v ->
                    SiteCard(v, width = null, onClick = { onOpen(v) })
                }
            }
        }
    }
}
