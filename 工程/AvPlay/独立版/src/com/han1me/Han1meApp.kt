package com.han1me

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.javrocket.hanime.HanimeClient
import com.javrocket.hanime.HanimeDetail
import com.javrocket.hanime.HanimeNavItem
import com.javrocket.hanime.HanimeSection
import com.javrocket.hanime.HanimeVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ---------- 设计 token 见 Tokens.kt ----------

private enum class ListMode { HOME, LIST }

/** 列表数据源种类。 */
private enum class ListKind { SEARCH, CATEGORY, LIKES, SAVES, HISTORY, SUBSCRIPTIONS }

/** 应用内网页（播放列表 / 站点播放页等）：用宿主 jar 的 AppWebView 打开的浮层。 */
private data class WebPage(val url: String, val title: String, val play: Boolean = false)

/**
 * Han1me 应用根界面：网站风格首页（导航条 + hero 大横幅 + 分区卡片流）+
 * 搜索/分区/我的清单列表 + 详情浮层（内嵌播放）+「我的」面板（登录/收藏/历史/网络设置）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Han1meApp() {
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ListMode.HOME) }
    var kind by remember { mutableStateOf(ListKind.SEARCH) }
    var page by remember { mutableStateOf(1) }
    var listUrl by remember { mutableStateOf("") }
    var listTitle by remember { mutableStateOf("") }
    var tabs by remember { mutableStateOf<List<HanimeNavItem>>(emptyList()) }
    var activeTab by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<HanimeSection>>(emptyList()) }
    var cards by remember { mutableStateOf<List<HanimeVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var heroVideo by remember { mutableStateOf<HanimeVideo?>(null) }
    var heroDetail by remember { mutableStateOf<HanimeDetail?>(null) }
    var detail by remember { mutableStateOf<HanimeDetail?>(null) }
    // 当前这页详情来自「站点合集」还是官方源：决定「相关影片」再点下去走哪条路（官方源那条一行不动）
    var detailFromBoard by remember { mutableStateOf(false) }
    // 底栏五项（用户 2026-10-01 指定：主页 / 最新 / 检索 / 收藏 / 用户）。
    // 参照图是 NoyAcg 的「主页/最新/社区/收藏/用户」，但 Hplay 没有社区、积分、徽章、本地下载这些
    // 后端能力，经用户确认**不做社区格**，把「检索」提到独立一格。每一格都落在已有能力上，不放空壳。
    var tab by remember { mutableStateOf(X99.setting("ui.tab", "home")) }
    var detailLoading by remember { mutableStateOf(false) }
    /**
     * 详情页请求的代号。用户 2026-10-02 报「板块切换后视频封面和**源**会滞留」，源这一半就出在这里：
     * `openDetail` 把取详情丢进 `scope.launch(Dispatchers.IO)` 后无条件写回 `detail`，
     * 于是先点的那条只要回得晚，就会把后点的那条**整页覆盖掉**——界面显示 B 的封面，`detail.sources` 却是 A 的地址，
     * 按下播放起播的就是上一个板块那部片。每次发起把代号 +1，回写时代号不符就整包丢弃。
     */
    var detailSeq by remember { mutableStateOf(0) }
    var user by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    var showMine by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var webPage by remember { mutableStateOf<WebPage?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    // ---- 优选（x99dh.cc）：本命板块线路 + 站点合集板块 ----
    var board by remember { mutableStateOf(if (tab == "latest") "sites" else "hanime") }   // hanime | sites
    // 本命板块站源：启动固定为官方源（可登录、DoH/代理生效）；只有用户在「优」里手动点才切，且不跨启动保留
    var hanimeLine by remember { mutableStateOf("") }                   // 空 = 官方 hanime1.me
    var x99Focus by remember { mutableStateOf<String?>(null) }          // 优选弹窗（值 = 当前站点名）
    var refreshKey by remember { mutableStateOf(0) }                    // 切换站点/线路后强制刷新
    val mirror = hanimeLine.isNotBlank()
    /**
     * 账号资料。图3 那句「已登录：已登录」就是因为这里以前只放 `whoami()`——
     * 它返回的是字面量"已登录"，于是"已登录："后面又拼了一遍。改成读 `/user/<uid>` 的真实字段。
     */
    var profile by remember { mutableStateOf<com.javrocket.hanime.HanimeProfile?>(null) }
    var showNotices by remember { mutableStateOf(false) }               // 用户页「信息」
    var showDownloads by remember { mutableStateOf(false) }             // 用户页「本地下载」
    var downloading by remember { mutableStateOf<String?>(null) }       // 非空 = 正在下的标题
    var downloadTick by remember { mutableStateOf(0) }                  // 下载/删除后让列表重读一次磁盘
    var favFilter by remember { mutableStateOf("全部") }                // 收藏页顶部的板块筛选 chip
    // 本地收藏的增删要立刻反映到卡片与收藏页，用一个代号把这两处串起来
    var favTick by remember { mutableStateOf(0) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    /**
     * 播放清单页。上一版这里打开 `BASE/user/playlists`，实测服务端回 **500**（那是个不存在的路由），
     * 站点自己挂的是 `/user/<uid>/playlists`（实测 200，标题「播放清單」）。
     */
    fun openPlaylistsWeb() {
        val p = profile
        webPage = if (p != null) WebPage(p.playlists, "Hanime 播放列表")
        else WebPage("${HanimeClient.BASE}/user/playlists", "Hanime 播放列表").also { toast = "未登录，播放列表可能打不开" }
    }

    /** 复制「我的主页链接」——站点自己在个人页上就有这个按钮（`id="copy-text"` + copyToClipboard）。 */
    fun copyProfileHome() {
        val p = profile
        if (p == null) { toast = "未登录：没有个人主页链接可复制"; return }
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(p.home))
        toast = "已复制个人主页链接 ${p.home}"
    }

    fun refreshUser() {
        if (mirror) return
        scope.launch(Dispatchers.IO) {
            user = runCatching { HanimeClient.whoami() }.getOrNull()
            // 昵称/头像/订阅数这些只有 `/user/<uid>` 上有，whoami() 给的是"已登录"这个字面量
            profile = runCatching { HanimeClient.profile() }.getOrNull()
        }
    }

    fun loadHome(mirrorUrl: String = hanimeLine) {
        loading = true
        error = null
        mode = ListMode.HOME
        activeTab = null
        scope.launch(Dispatchers.IO) {
            try {
                if (mirrorUrl.isNotBlank()) {
                    // 优选线路（hanime1 镜像站）：直连抓取站点自身前端
                    val (nav, secs) = X99.mirrorHome(mirrorUrl)
                    tabs = nav
                    rows = secs
                    user = null
                    val hv = secs.firstOrNull { it.cards.isNotEmpty() }?.cards?.firstOrNull()
                    heroVideo = hv
                    heroDetail = null
                } else {
                    val (nav, secs) = HanimeClient.homeWithNav()
                    tabs = nav
                    rows = secs
                    user = runCatching { HanimeClient.whoami() }.getOrNull()
                    // hero = 第一个有卡片的分区的第一部影片（网站行为：banner 展示最新片）
                    val hv = secs.firstOrNull { it.cards.isNotEmpty() }?.cards?.firstOrNull()
                    heroVideo = hv
                    heroDetail = null
                    if (hv != null) {
                        runCatching { heroDetail = HanimeClient.detail(hv) }
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                loading = false
            }
        }
    }

    fun fetch(kind0: ListKind, p: Int): List<HanimeVideo> = when (kind0) {
        ListKind.SEARCH -> {
            if (mirror) X99.mirrorListing(X99.mirrorSearchUrl(hanimeLine, query.trim(), p))
            else {
                val sep = if (listUrl.contains("?")) "&" else "?"
                HanimeClient.listing("$listUrl${sep}page=$p")
            }
        }
        ListKind.CATEGORY -> {
            if (mirror) {
                val sep = if (listUrl.contains("?")) "&" else "?"
                X99.mirrorListing("$listUrl${sep}page=$p")
            } else {
                val sep = if (listUrl.contains("?")) "&" else "?"
                HanimeClient.listing("$listUrl${sep}page=$p")
            }
        }
        ListKind.LIKES -> HanimeClient.myList("likes", p)
        ListKind.SAVES -> HanimeClient.myList("saves", p)
        ListKind.HISTORY -> HanimeClient.history(p)
        ListKind.SUBSCRIPTIONS -> HanimeClient.subscriptions(p)
    }

    fun loadList(kind0: ListKind, url: String, title: String, p: Int) {
        kind = kind0
        listUrl = url
        listTitle = title
        page = p
        mode = ListMode.LIST
        activeTab = if (kind0 == ListKind.CATEGORY) title else null
        loading = true
        error = null
        scope.launch(Dispatchers.IO) {
            try {
                cards = fetch(kind0, p)
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                loading = false
            }
        }
    }

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        val u = if (mirror) X99.mirrorSearchUrl(hanimeLine, q, 1)
        else "${HanimeClient.BASE}/search?query=" + java.net.URLEncoder.encode(q, "UTF-8")
        loadList(ListKind.SEARCH, u, "搜索：$q", 1)
    }

    fun openDetail(v: HanimeVideo, fromBoard: Boolean = false) {
        val seq = ++detailSeq
        if (fromBoard) {
            // 站点合集（MissAV 区 / xchina 区）的卡片：**全部**先进这一页介绍页（用户 2026-10-01 点名，
            // 与图1 官方站那一套同版式）。有原生播址的板块在页内直接给画质按钮，没有的给「网页播放」。
            detailLoading = true
            detail = null
            detailFromBoard = true
            scope.launch(Dispatchers.IO) {
                val d = runCatching { X99.boardPage(v) }.getOrNull()
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    // 迟到的上一张卡片：既不能写 detail，也不能关 loading（那正在显示新一条的转圈）
                    if (seq != detailSeq) return@withContext
                    detailLoading = false
                    // 连介绍页都拿不到（站点改版 / 被频控）就回落网页版：卡在空白页比退回网页版更糟。
                    if (d != null) { X99.adoptDetail(d); detail = d } else webPage = WebPage(v.url, v.title, play = true)
                }
            }
            return
        }
        if (mirror) {
            // 优选线路：媒体链路只放行真实浏览器（内置播放器会被 CDN 403）→ 点击视频直接跳转播放
            webPage = WebPage(v.url, v.title, play = true)
            return
        }
        detailLoading = true
        detail = null
        detailFromBoard = false
        scope.launch(Dispatchers.IO) {
            try {
                detail = HanimeClient.detail(v)
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                detailLoading = false
            }
        }
    }

    /**
     * 切底栏页。`board` / `mode` 是既有状态，这里只做映射，官方源与优选镜像那条加载路径一行未动。
     * 本地函数不能前向引用后面才声明的 backToHome()，所以主页那一支把它那三行原样展开。
     */
    fun selectTab(t: String) {
        tab = t
        X99.putSetting("ui.tab", t)
        detail = null
        webPage = null
        x99Focus = null
        when (t) {
            "latest" -> board = "sites"
            "home" -> {
                board = "hanime"; mode = ListMode.HOME; activeTab = null
                if (rows.isEmpty()) loadHome()
            }
            else -> board = "hanime"
        }
    }

    fun backToHome() {
        mode = ListMode.HOME
        activeTab = null
        if (rows.isEmpty()) loadHome()
    }

    LaunchedEffect(Unit) {
        // 启动即清理旧键（站源不再跨启动保留），并固定用官方源
        runCatching {
            X99.putSetting("hanime.line", "")
            X99.putSetting("hanime.line.name", "")
        }
        // 每次打开程序：先为 hanime 官方源筛选最快的「内置 Hosts 边缘节点」，再加载首页
        // （走代理时域名由代理解析，节点不参与——跳过测速省时间）
        scope.launch {
            val picked: Map<String, Long> = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    if (HanimeClient.edgeHostsEnabled && !HanimeClient.proxyInUse()) HanimeClient.pickFastestNode()
                    else emptyMap()
                }.getOrDefault(emptyMap())
            }
            val n = HanimeClient.edgeNode
            if (picked.isNotEmpty() && n.isNotBlank() && (picked[n] ?: -1L) >= 0) {
                toast = "边缘节点已选最快：$n（${picked[n]} ms）"
            }
            loadHome()
        }
        // 每次启动联网刷新一次优选目录（站点/线路跟随 x99dh 更新）
        scope.launch(Dispatchers.IO) {
            runCatching { X99.loadCatalog(true) }
            kotlinx.coroutines.withContext(Dispatchers.Main) { refreshKey += 1 }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgColor)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    when {
                        webPage != null -> { webPage = null; true }
                        detail != null -> { detail = null; true }
                        showMine -> { showMine = false; true }
                        showSearch -> { showSearch = false; true }
                        else -> false
                    }
                } else false
            },
    ) {
        // 底栏是盖在最下层的浮层，内容区统一让出它的高度（否则最后一行卡片会被压住）
        // 让内容不被底栏盖住：这个数必须与 BottomBar 的高度一致（改一处就要改另一处）
        Column(Modifier.fillMaxSize().padding(bottom = 74.dp)) {
            // ---------- 顶部导航条（网站风格） ----------
            TopNavBar(
                tabs = if (board == "hanime") tabs else emptyList(),
                activeTab = if (board == "hanime") activeTab else null,
                user = user,
                onTab = { tab -> loadList(ListKind.CATEGORY, tab.url, tab.label, 1) },
                onLogo = { board = "hanime"; backToHome() },
                onSearchToggle = null,
                // 站点 / 优 / 搜索 / 头像四个控件与底栏重复，用户 2026-10-01 要求删掉顶栏这几个。
                // TopNavBar 那四个参数都是 `?.let`，传 null 就是整块不渲染（不改 TopNavBar 本体：
                // 它被 tools\patches.py 的补丁锚点覆盖着）。
                onMine = null,
                onFav = null,
                onSites = null,
            )
            if (showSearch && board == "hanime") {
                SearchRow(
                    query = query,
                    onQuery = { query = it },
                    onSearch = { showSearch = false; doSearch() },
                    onClose = { showSearch = false },
                )
            }

            if (board == "sites") {
                X99Board(
                    refreshKey = refreshKey,
                    onToast = { toast = it },
                    onOpenDetail = { v -> openDetail(v, fromBoard = true) },
                    onOpenDialog = { x99Focus = it },
                    onBack = { board = "hanime"; backToHome() },
                )
            } else when (tab) {
                "search" -> GlobalSearchTab(
                    query = query,
                    onQuery = { query = it },
                    onOfficial = { doSearch() },
                    onOpenBoardVideo = { v -> openDetail(v, fromBoard = true) },
                    officialResults = mode == ListMode.LIST && kind == ListKind.SEARCH,
                    listTitle = listTitle,
                    cards = cards,
                    page = page,
                    loading = loading,
                    error = error,
                    onResultsBack = { mode = ListMode.HOME },
                    onPrev = { if (page > 1) loadList(kind, listUrl, listTitle, page - 1) },
                    onNext = { loadList(kind, listUrl, listTitle, page + 1) },
                    onOpenVideo = { v -> openDetail(v) },
                )
                // 底栏各格里再点开的清单**必须在这一格内渲染**：上一版这里按 tab 直接进 FavTab/UserTab，
                // `loadList()` 只改了 mode 没改 tab，于是清单页被这一层的 tab 分支挡在外面——
                // 用户看到的就是「收藏页整页点不动」（2026-10-02 图3）。
                "fav" -> if (mode == ListMode.LIST) {
                    ListContent(
                        title = listTitle,
                        cards = cards,
                        page = page,
                        loading = loading,
                        error = error,
                        onBack = { backToHome() },
                        onPrev = { if (page > 1) loadList(kind, listUrl, listTitle, page - 1) },
                        onNext = { loadList(kind, listUrl, listTitle, page + 1) },
                        onOpenVideo = { v -> openDetail(v) },
                    )
                } else FavTab(
                    user = user,
                    mirror = mirror,
                    favTick = favTick,
                    favFilter = favFilter,
                    onFavFilter = { favFilter = it },
                    onLogin = { showLogin = true },
                    onOpen = { k, title -> loadList(k, "", title, 1) },
                    onOpenWebList = { openPlaylistsWeb() },
                    onOpenBoardVideo = { v -> openDetail(v, fromBoard = true) },
                    onUnfav = { url ->
                        X99.toggleFav(
                            HanimeVideo(id = url, url = url, title = "", thumb = "", duration = "",
                                likes = "", views = "", brand = ""),
                            X99.boardLabelFor(url),
                        )
                        favTick++
                        toast = "已从本站收藏移除"
                    },
                )
                "user" -> if (mode == ListMode.LIST) {
                    ListContent(
                        title = listTitle,
                        cards = cards,
                        page = page,
                        loading = loading,
                        error = error,
                        onBack = { backToHome() },
                        onPrev = { if (page > 1) loadList(kind, listUrl, listTitle, page - 1) },
                        onNext = { loadList(kind, listUrl, listTitle, page + 1) },
                        onOpenVideo = { v -> openDetail(v) },
                    )
                } else UserTab(
                    user = user,
                    mirror = mirror,
                    profile = profile,
                    // 键挂在 user 上：登录成功后 user 会变，不挂键的话 remember 会一直留着登录前那次读到的空值，
                    // 表现就是"刚登录完用户页还是不显示邮箱"。
                    email = remember(user) { X99.setting("account.email", "") },
                    onLogin = { showLogin = true },
                    onOpenSites = { x99Focus = X99.HANIME_SITE },
                    onOpen = { k, title -> loadList(k, "", title, 1) },
                    onNotices = { showNotices = true },
                    onDownloads = { showDownloads = true },
                    onCopyHome = { copyProfileHome() },
                    onOpenWeb = { u, t -> webPage = WebPage(u, t) },
                )
                else -> when (mode) {
                ListMode.HOME -> HomeContent(
                    rows = rows,
                    tabs = tabs,
                    heroVideo = heroVideo,
                    heroDetail = heroDetail,
                    loading = loading,
                    error = error,
                    onRetry = { loadHome() },
                    onOpenCategory = { sec -> loadList(ListKind.CATEGORY, sec.url, sec.title, 1) },
                    onOpenTab = { t -> loadList(ListKind.CATEGORY, t.url, t.label, 1) },
                    onOpenVideo = { v -> openDetail(v) },
                )
                ListMode.LIST -> ListContent(
                    title = listTitle,
                    cards = cards,
                    page = page,
                    loading = loading,
                    error = error,
                    onBack = { backToHome() },
                    onPrev = { if (page > 1) loadList(kind, listUrl, listTitle, page - 1) },
                    onNext = { loadList(kind, listUrl, listTitle, page + 1) },
                    onOpenVideo = { v -> openDetail(v) },
                )
                }
            }
        }

        // ---------- 我的面板 ----------
        if (showMine) {
            MinePanel(
                user = user,
                mirror = mirror,
                onClose = { showMine = false },
                onLogin = { showMine = false; showLogin = true },
                onOpen = { k, title ->
                    showMine = false
                    loadList(k, "", title, 1)
                },
                onOpenWebList = {
                    showMine = false
                    openPlaylistsWeb()
                },
            )
        }

        // ---------- 用户页的两个子页（信息 / 本地下载）：整屏覆盖，各自带回退 ----------
        if (showNotices) {
            Box(Modifier.fillMaxSize().background(BgColor)) {
                NoticesPanel(onClose = { showNotices = false })
            }
        }
        if (showDownloads) {
            Box(Modifier.fillMaxSize().background(BgColor)) {
                DownloadsPanel(
                    tick = downloadTick,
                    downloading = downloading,
                    onClose = { showDownloads = false },
                    onPlay = { path ->
                        // 合成一条"只有本机源"的详情，走详情面板里那条已被补丁翻译过的播放路径
                        showDownloads = false
                        detailFromBoard = false
                        detail = localFileDetail(path)
                    },
                    onDelete = { path ->
                        runCatching {
                            val f = java.io.File(path)
                            if (f.isDirectory) f.deleteRecursively() else f.delete()
                        }
                        downloadTick++
                        toast = "已删除本机文件"
                    },
                )
            }
        }

        // ---------- 详情浮层 ----------
        if (detailLoading) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentRed)
            }
        }
        detail?.let { d ->
            HanimeDetailPanel(
                d = d,
                favTick = favTick,
                fromBoard = detailFromBoard,
                onOpenVideo = { v -> openDetail(v, fromBoard = detailFromBoard) },
                onClose = { detail = null },
                onToast = { msg -> toast = msg },
                onWebPlay = { u, t ->
                    // 没有原生播址的板块：介绍页看完，播放仍交给内置浏览器（去广告链路一行未动）
                    detail = null
                    webPage = WebPage(u, t, play = true)
                },
                onFavToggled = { now ->
                    detail = d.copy(isFav = now)
                    refreshUser()
                },
                onSavedToggled = { now -> detail = d.copy(isSaved = now) },
                onToggleLocalFav = {
                    val now = X99.toggleFav(d.video, X99.boardLabelFor(d.video.url))
                    favTick++
                    toast = if (now) "已收藏到本站（本机保存）" else "已从本站收藏移除"
                },
                onDownload = {
                    val src = d.sources.firstOrNull()?.second
                    if (src == null) { toast = "没有可下载的直链"; return@HanimeDetailPanel }
                    if (downloading != null) { toast = "已经有一个下载在跑了，先等它完成"; return@HanimeDetailPanel }
                    downloading = d.video.title.ifBlank { "媒体" }
                    showDownloads = true
                    scope.launch(Dispatchers.IO) {
                        val r = runCatching {
                            X99.downloadMedia(src, d.video.title.ifBlank { "media" }) { msg ->
                                scope.launch(Dispatchers.Main) { downloading = msg }
                            }
                        }
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            downloading = null
                            downloadTick++
                            toast = r.fold(
                                { "已下载到本机：${it.absolutePath}" },
                                { "下载失败：${it.message ?: it}" },
                            )
                        }
                    }
                },
            )
        }

        // ---------- 应用内网页（含站点播放页：直接跳转播放） ----------
        webPage?.let { wp ->
            Han1meWebScreen(
                url = wp.url,
                title = wp.title,
                onClose = { webPage = null },
                autoPlay = wp.play,
            )
        }

        // ---------- 优选访问弹窗（站点 + 线路） ----------
        x99Focus?.let { focus ->
            X99Dialog(
                focusSite = focus,
                hanimeLineUrl = hanimeLine,
                onClose = { x99Focus = null },
                onSwitchSite = { name ->
                    if (name == X99.HANIME_SITE) {
                        board = "hanime"
                        x99Focus = X99.HANIME_SITE
                    } else {
                        if (name != "__sites__") X99.putSetting("x99.site", name)
                        board = "sites"
                        x99Focus = null
                        refreshKey += 1
                    }
                },
                onPickLine = { site, line ->
                    if (site.name.equals(X99.HANIME_SITE, true)) {
                        if (line.url == X99.OFFICIAL_HANIME) {
                            // 官方源：可登录，DoH/代理等网络特殊功能只对它生效
                            hanimeLine = ""
                            board = "hanime"
                            x99Focus = null
                            toast = "已切回官方源 hanime1.me（可登录；DoH/代理等网络功能生效）"
                            loadHome("")
                        } else {
                            hanimeLine = line.url
                            board = "hanime"
                            x99Focus = null
                            toast = "已切换站点源：${line.host}（直连，不走 DoH）"
                            loadHome(line.url)
                        }
                    } else {
                        X99.saveLine(site, line)
                        X99.putSetting("x99.site", site.name)
                        board = "sites"
                        x99Focus = null
                        refreshKey += 1
                        toast = "已切换：${site.name} · ${line.host}"
                    }
                },
                onToast = { toast = it },
            )
        }

        // ---------- 底栏（主页 / 最新 / 检索 / 收藏 / 用户） ----------
        // 详情浮层与内置浏览器是全屏覆盖，那时不该再留一条底栏挡在下面
        if (detail == null && webPage == null) {
            BottomBar(tab = tab, mirror = mirror, modifier = Modifier.align(Alignment.BottomCenter),
                onTab = { selectTab(it) })
        }

        // ---------- 轻提示 ----------
        toast?.let { msg ->
            Box(
                Modifier.fillMaxSize().padding(bottom = 48.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    msg, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xE61F1F23))
                        .clickable { toast = null }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(3200)
                    toast = null
                }
            }
        }

        // ---------- 登录对话框 ----------
        if (showLogin) {
            HanimeLoginDialog(
                onDismiss = { showLogin = false },
                onLoggedIn = { name -> user = name; showLogin = false },
                onWebLogin = {
                    showLogin = false
                    // 默认调取系统外部浏览器（内置 WebView 无 DoH，登录页在污染网络下不可用）
                    runCatching {
                        java.awt.Desktop.getDesktop().browse(java.net.URI("${HanimeClient.BASE}/login"))
                        toast = "已用系统浏览器打开登录页；登录后回到这里用邮箱密码登录即可"
                    }.onFailure {
                        toast = "无法调起系统浏览器：${it.message}"
                    }
                },
            )
        }
    }
}

// ---------- 顶部导航条 ----------

@Composable
private fun TopNavBar(
    tabs: List<HanimeNavItem>,
    activeTab: String?,
    user: String?,
    onTab: (HanimeNavItem) -> Unit,
    onLogo: () -> Unit,
    onSearchToggle: (() -> Unit)?,
    onMine: (() -> Unit)?,
    onFav: (() -> Unit)? = null,
    onSites: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(BarColor)
            .padding(horizontal = 16.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    HplayWindow.dragBy?.invoke(drag.x.toInt(), drag.y.toInt())
                }
            },
    ) {
        // logo：H 红色 + anime1.me 白色
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onLogo() },
        ) {
            Text("H", color = AccentRed, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("anime1.me", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(20.dp))
        // 分区导航（可横向滚动；激活项红色，与网站一致）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        ) {
            tabs.forEach { tab ->
                val active = activeTab == tab.label
                Text(
                    tab.label,
                    color = if (active) AccentRed else Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clickable { onTab(tab) },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // 站点合集（x99dh 优选站点）
        onSites?.let { cb ->
            Text(
                "站点", color = Color(0xFFD8D8DC), fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .background(CardColorSoft)
                    .clickable { cb() }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        // 优选访问（搜索图标旁的小按钮：弹窗切换站源/线路）
        onFav?.let { cb ->
            Text(
                "优", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .background(AccentRed)
                    .clickable { cb() }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        // 搜索
        onSearchToggle?.let { cb ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .clickable { cb() },
            ) { Text("🔍", fontSize = 15.sp) }
        }
        // 无边框窗口自绘的窗口键（右上角三键，从左到右：最小化 / 全屏 / 退出）
        Spacer(Modifier.width(8.dp))
        Text(
            "─", color = Color(0xFFD8D8DC), fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp)).background(CardColorSoft)
                .clickable { HplayWindow.minimize?.invoke() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(6.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (HplayWindow.isFullscreen) "⛶ 还原" else "⛶ 全屏",
            color = Color(0xFFD8D8DC), fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(13.dp))
                .background(CardColorSoft)
                .clickable { HplayWindow.toggle?.invoke() }
                .padding(horizontal = 9.dp, vertical = 5.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "✕", color = Color.White, fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp)).background(AccentRed)
                .clickable { HplayWindow.closeApp?.invoke() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        onMine?.let { cb ->
            Spacer(Modifier.width(10.dp))
            // 头像（我的）
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (user != null) AccentRed else CardColorSoft)
                    .clickable { cb() },
            ) {
                Text(
                    if (user != null) "👤" else "我",
                    color = Color.White, fontSize = if (user != null) 14.sp else 12.sp,
                )
            }
        }
    }
    // 底部分隔线
    Box(Modifier.fillMaxWidth().height(1.dp).background(LineColor))
}

@Composable
internal fun SearchRow(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(BgColor)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(CardColor)
                .padding(horizontal = 14.dp),
        ) {
            Text("🔍", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                cursorBrush = Brush.verticalGradient(listOf(AccentRed, AccentRed)),
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("想找什么…", color = TextFaint, fontSize = 13.sp)
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                Text("✕", color = TextDim, fontSize = 13.sp, modifier = Modifier.clickable { onQuery("") }.padding(6.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSearch,
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
            shape = RoundedCornerShape(21.dp),
            modifier = Modifier.height(42.dp),
        ) { Text("搜索", fontSize = 13.sp) }
        Spacer(Modifier.width(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(CardColor)
                .clickable { onClose() },
        ) { Text("✕", color = TextDim, fontSize = 13.sp) }
    }
}

// ---------- 首页（网站风格：hero 大横幅 + 分区卡片流） ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeContent(
    rows: List<HanimeSection>,
    tabs: List<HanimeNavItem>,
    heroVideo: HanimeVideo?,
    heroDetail: HanimeDetail?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpenCategory: (HanimeSection) -> Unit,
    onOpenTab: (HanimeNavItem) -> Unit,
    onOpenVideo: (HanimeVideo) -> Unit,
) {
    var allTags by remember { mutableStateOf(false) }
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentRed) }
        return
    }
    if (rows.isEmpty() && error == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有内容", color = TextDim) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        error?.let { e ->
            item {
                Column(Modifier.padding(14.dp)) {
                    Text("出错：$e", color = Color(0xFFFF8A8A), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onRetry) { Text("重试", color = AccentRed) }
                }
            }
        }
        heroVideo?.let { hv ->
            item(key = "hero") { HeroBanner(video = hv, detail = heroDetail, onOpenVideo = onOpenVideo) }
        }
        // 「标签 推荐」：数据就是站点首页导航上那一批 `search?genre=…`（实测 10 条：裏番 / 泡麵番 /
        // Motion Anime / 3DCG / 2.5D / 2D動畫 / AI生成 / MMD / Cosplay / 新番預告），
        // 点一个就走既有的分类清单通道——不另造一套"看起来像标签"的假数据。
        if (tabs.isNotEmpty()) {
            item(key = "tag-recommend") {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("标签", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text("推荐", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (allTags) "收起 ‸" else "更多 ›",
                            color = TextDim, fontSize = 11.sp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, LineColor, RoundedCornerShape(12.dp))
                                .clickable { allTags = !allTags }.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    val shown = if (allTags) tabs else tabs.take(6)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        shown.forEach { t ->
                            Text(
                                t.label, color = Color(0xFFE4E4E8), fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                                    .clickable { onOpenTab(t) }.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }
        items(rows, key = { it.title + it.url }) { sec ->
            Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(sec.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "查看更多 ›",
                        color = TextDim, fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, LineColor, RoundedCornerShape(12.dp))
                            .clickable { onOpenCategory(sec) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                ) {
                    items(sec.cards, key = { sec.title + it.id }) { v ->
                        SiteCard(v, width = 206.dp, onClick = { onOpenVideo(v) })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

/** 网站 hero 大横幅：背景大图 + 渐变遮罩 + 标题/作者/标签 + 播放/更多资讯。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroBanner(
    video: HanimeVideo,
    detail: HanimeDetail?,
    onOpenVideo: (HanimeVideo) -> Unit,
) {
    val shown = detail?.video ?: video
    val tags = detail?.tags.orEmpty()
    Box(
        Modifier
            .fillMaxWidth()
            .height(400.dp)
            .clickable { onOpenVideo(video) },
    ) {
        AsyncImage(
            model = shown.thumb,
            contentDescription = shown.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 压暗 + 底部渐变（网站同款：文字压在左下）
        Box(Modifier.fillMaxSize().background(Color(0x26000000)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x000D0D0F), Color(0x8C0D0D0F), Color(0xF20D0D0F))),
            ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.62f)
                .padding(start = 28.dp, bottom = 26.dp, end = 16.dp),
        ) {
            Text(
                shown.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                lineHeight = 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            val meta = buildList {
                if (shown.brand.isNotBlank()) add(shown.brand)
                if (shown.views.isNotBlank()) add(shown.views.replace(Regex("次$"), "") + "次觀看")
                if (shown.duration.isNotBlank()) add(shown.duration)
            }
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
            }
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.take(12).forEach { tag ->
                        Text(
                            tag, color = Color.White, fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            Row {
                Button(
                    onClick = { onOpenVideo(video) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF111114)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(44.dp),
                ) { Text("▶  播放", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { onOpenVideo(video) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E34), contentColor = Color.White),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(44.dp),
                ) { Text("ⓘ  更多资讯", fontSize = 14.sp) }
            }
        }
    }
}

// ---------- 列表页（搜索 / 分区 / 我的清单） ----------

@Composable
private fun ListContent(
    title: String,
    cards: List<HanimeVideo>,
    page: Int,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenVideo: (HanimeVideo) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(AccentRed).clickable { onBack() },
            ) { Text("←", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                "‹", color = if (page > 1) Color.White else TextFaint, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(CardColorSoft).clickable { onPrev() }.padding(horizontal = 12.dp, vertical = 2.dp),
            )
            Text("  $page  ", color = TextDim, fontSize = 12.sp)
            Text(
                "›", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(CardColorSoft).clickable { onNext() }.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        error?.let {
            Text("出错：$it", color = Color(0xFFFF8A8A), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentRed) }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(200.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(cards, key = { it.id }) { v ->
                    SiteCard(v, width = null, onClick = { onOpenVideo(v) })
                }
            }
        }
    }
}

// ---------- 卡片（网站风格：16:9 缩略图 + 时长角标 + 好评/观看数压底 + 两行标题） ----------

@Composable
internal fun SiteCard(v: HanimeVideo, width: androidx.compose.ui.unit.Dp?, onClick: () -> Unit) {
    Column(
        modifier = (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(CardColor),
        ) {
            AsyncImage(
                model = v.thumb,
                contentDescription = v.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (v.duration.isNotBlank()) {
                Text(
                    v.duration, color = Color.White, fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xB3000000))))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                if (v.likes.isNotBlank()) Text("👍${v.likes}", color = Color(0xFFB8E0B8), fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                if (v.views.isNotBlank()) Text(v.views, color = Color(0xFFE0E0E0), fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            v.title, color = Color(0xFFE8E8EA), fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
        )
        if (v.brand.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(v.brand, color = TextFaint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- 底栏与它的页（2026-10-01 实验版式） ----------

/**
 * 底栏五项。每一格都对应工程里已经存在的能力：
 * 主页=官方源首页，最新=站点合集，检索=官方源搜索＋全站聚合搜索，
 * 收藏=喜欢的影片/稍后看/历史/订阅，用户=登录态＋优选线路＋网络设置。
 */
@Composable
private fun BottomBar(tab: String, mirror: Boolean, modifier: Modifier = Modifier, onTab: (String) -> Unit) {
    // 图标与文字**分两层**排：上一版把 "⌂\n主页" 塞进一个 Text 里，行高由 \n 决定，
    // 结果整条只有 58 dp、图标挤成一行小字，与参照图（图4）差得远（用户 2026-10-02 点名"底栏大小都不够还原"）。
    val items = listOf(
        "home" to ("⌂" to "主页"), "latest" to ("☰" to "最新"), "search" to ("🔍" to "检索"),
        "fav" to ("♥" to "收藏"), "user" to ("◉" to "用户"),
    )
    Column(
        modifier.fillMaxWidth().background(BarColor).height(74.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineColor))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { (key, pair) ->
                val active = tab == key
                // 优选线路模式下账号相关清单是空的：只压暗提示"这格现在没数据"，
                // 不禁用——禁用会让用户以为按钮坏了。
                val dim = mirror && (key == "fav" || key == "user")
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) Color(0xFF2A1518) else Color.Transparent)
                        .clickable { onTab(key) },
                ) {
                    Text(
                        pair.first,
                        fontSize = 19.sp,
                        color = when {
                            active -> AccentRed
                            dim -> TextFaint
                            else -> TextDim
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        pair.second,
                        color = when {
                            active -> AccentRed
                            dim -> TextFaint
                            else -> TextDim
                        },
                        fontSize = 11.5.sp,
                        lineHeight = 13.sp,
                        maxLines = 1,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * 「检索」页。用户提的「现在的检索只对封面显示出来的视频」就是这一格的意义：
 *  · 回车＝只搜 hanime 官方源（沿用原有 loadList 通道，行为与顶栏搜索完全一致）；
 *  · 下方＝并发搜站点合集里每一个板块的**站内搜索**，能命中列表翻页翻不到的作品。
 * 没有可用站内搜索入口的站（实测 qinav 就没有）显示「无搜索入口」，不谎报条数。
 */
@Composable
private fun GlobalSearchTab(
    query: String,
    onQuery: (String) -> Unit,
    onOfficial: () -> Unit,
    onOpenBoardVideo: (HanimeVideo) -> Unit,
    officialResults: Boolean,
    listTitle: String,
    cards: List<HanimeVideo>,
    page: Int,
    loading: Boolean,
    error: String?,
    onResultsBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenVideo: (HanimeVideo) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SearchRow(query = query, onQuery = onQuery, onSearch = onOfficial, onClose = onResultsBack)
        Text(
            "↑ 回车＝只搜 hanime 官方源；下面＝同时搜站点合集里每一个板块的站内搜索",
            color = TextFaint, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (officialResults) {
            ListContent(
                title = listTitle,
                cards = cards,
                page = page,
                loading = loading,
                error = error,
                onBack = onResultsBack,
                onPrev = onPrev,
                onNext = onNext,
                onOpenVideo = onOpenVideo,
            )
        } else {
            AggregateSearchPanel(sites = X99.otherSites(), onOpen = onOpenBoardVideo, onClose = {})
        }
    }
}

/**
 * 「收藏」页。两区，互不混淆（用户 2026-10-02 点名）：
 *  · **本站收藏**：各板块（MissAV / xchina / qinav / ganxiaban / 肉视频 …）的卡片，存在本机文件里；
 *  · **hanime1.me 账号**：官方源账号里的清单，走站点接口，一行未改。
 * 上面那排 chip 是板块筛选（参照图1 的「全部 / 流媒体 / 未分类 / 漫画」，我们按板块分）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavTab(
    user: String?,
    mirror: Boolean,
    favTick: Int,
    favFilter: String,
    onFavFilter: (String) -> Unit,
    onLogin: () -> Unit,
    onOpen: (ListKind, String) -> Unit,
    onOpenWebList: () -> Unit,
    onOpenBoardVideo: (HanimeVideo) -> Unit,
    onUnfav: (String) -> Unit,
) {
    val local = remember(favTick) { X99.favs() }
    val boards = remember(favTick, favFilter) { listOf("全部") + X99.favBoards() }
    val shown = if (favFilter == "全部") local else local.filter { it.board == favFilter }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("收藏", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("本站收藏 · ${local.size} 条", color = Color(0xFFE8E8EA), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "这一区存在本机（各板块没有可写的账号体系），与下面 hanime1.me 账号里的清单互不影响。",
            color = TextFaint, fontSize = 11.sp, lineHeight = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            boards.forEach { b ->
                val on = b == favFilter
                Text(
                    b,
                    color = if (on) Color.White else TextDim, fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(15.dp)).background(if (on) AccentRed else CardColorSoft)
                        .clickable { onFavFilter(b) }.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (shown.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardColor).padding(14.dp),
            ) {
                Text("这里还是空的", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "在「最新」里选一个板块 → 点任意封面进介绍页 → 点 ♡ 收藏，就会出现在这一区。",
                    color = TextFaint, fontSize = 11.sp, lineHeight = 16.sp,
                )
            }
        }
        shown.forEach { item ->
            val v = HanimeVideo(
                id = item.url, url = item.url, title = item.title, thumb = item.thumb,
                duration = "", likes = "", views = "", brand = item.board,
            )
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(10.dp)).background(CardColor).padding(8.dp),
            ) {
                Column(Modifier.weight(1f).clickable { onOpenBoardVideo(v) }) {
                    // 封面按 16:9 走，与板块卡片一致；没有封面的站（图集类）留一块深色底而不是空白
                    Box(
                        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C1C1F)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.thumb.isNotBlank()) {
                            com.han1me.web.HplayThumb(item.thumb, item.title, Modifier.fillMaxSize())
                        } else {
                            Text("🎬", color = Color(0xFF4A4A52), fontSize = 24.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(item.title, color = Color(0xFFE8E8EA), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (item.board.isNotBlank()) InfoChip(item.board)
                        if (item.at > 0) InfoChip(
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                                .format(java.util.Date(item.at)),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "✕", color = TextFaint, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                        .clickable { onUnfav(item.url) }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineColor))
        Spacer(Modifier.height(14.dp))
        Text("hanime1.me 账号", color = Color(0xFFE8E8EA), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        if (mirror) {
            Text(
                "当前为优选线路模式：账号 / 收藏 / 历史是 hanime1.me 官方功能，此线路下不可用。" +
                    "需要在「用户」页的优选访问里切回 Hanime1（官方源）。",
                color = TextDim, fontSize = 12.sp, lineHeight = 17.sp,
            )
            return@Column
        }
        Text(
            if (user != null) "已登录：$user" else "未登录（登录后才能同步官方账号里的清单）",
            color = TextDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (user == null) {
            Button(
                onClick = onLogin,
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(40.dp),
            ) { Text("登录 / 注册", fontSize = 13.sp) }
            Spacer(Modifier.height(12.dp))
        }
        MineItem("❤ 喜欢的影片") { onOpen(ListKind.LIKES, "喜欢的影片") }
        MineItem("🕒 稍后观看") { onOpen(ListKind.SAVES, "稍后观看") }
        MineItem("📺 观看历史") { onOpen(ListKind.HISTORY, "观看历史") }
        MineItem("🔔 我的订阅") { onOpen(ListKind.SUBSCRIPTIONS, "我的订阅") }
        MineItem("🎬 播放列表（网页）") { onOpenWebList() }
    }
}

/**
 * 平台注入槽（第 43 轮：把用户页的封面与头像做成**可替换的本机素材**）。
 *
 * 上游这一份代码要同时编桌面与安卓，而「打开相册挑一张」与「解本机图片」都是平台能力，
 * 直接写在上游就编不过桌面。所以这里只放两个默认**什么都不做**的槽，由安卓壳在
 * [com.han1me.HplayEnv 的 init]（MainActivity.onCreate 已经在调它）填真实现；
 * 桌面不填 ⇒ `pickSupported` 保持 false ⇒ 那两行按钮根本不出现，不会出现「点了没反应」的空壳。
 */
object HplaySlots {
    /** 本机是否真的有图片选择器可调用（安卓壳置 true）。 */
    var pickSupported: Boolean = false

    /**
     * 唤起系统图片选择器。`settingKey` 是选完之后要写回的那格设置（安卓侧据此决定落盘文件名），
     * 成功把本机文件绝对路径交给 `done`，取消或失败给 null。
     */
    var pickImage: ((String, (String?) -> Unit) -> Unit) = { _, done -> done(null) }

    /** 把本机图片文件解成 Compose 位图（解不动就 null，界面回落到官方头像）。 */
    var decodeImage: ((String) -> androidx.compose.ui.graphics.ImageBitmap?) = { null }

    /**
     * 「信息」页那句版本号。上游这份代码要同时编桌面与安卓，而**包里的版本是构建时从
     * `version.properties` 注入 aapt2 的**，不是这里的常量：安卓壳把它换成 PackageManager 的
     * versionName（真在包里的数），桌面不填才用下面这个常量兜底。
     * 第 43 轮的 v1.0.19 就是踩着这个洞发出去的——包是 1.0.19，界面写的是常量 1.0.18。
     */
    var versionLabel: () -> String = { APP_VERSION }
}

/**
 * 「用户」页：照图2 的版式做，但**只放有真实功能支撑的行**。
 *
 * 图2 里的「积分 / 积分商店 / 积分记录 / 徽章 / 信息(私信)」在 hanime1.me 上**没有后端**——
 * 实测 `/points` 与 `/notifications` 都回 404，个人页里出现的 "point" 全是 CSS `cursor:pointer`，
 * 也没有徽章与邀请码体系。按用户 2026-10-02 的裁决：**这几行删掉，不做空壳**，
 * 并在页面上写一行说明，免得用户以为功能被藏起来了。
 * 保留下来的每一行都真的能用：头像/昵称/@uid/订阅数取自 `/user/<uid>`，
 * 「复制我的主页链接」就是站点个人页自己那个按钮，「历史记录」是官方清单，
 * 「本地下载」是本轮新增的下载器，「设置」是原有的网络设置。
 */
@Composable
private fun UserTab(
    user: String?,
    mirror: Boolean,
    profile: com.javrocket.hanime.HanimeProfile?,
    email: String,
    onLogin: () -> Unit,
    onOpenSites: () -> Unit,
    onOpen: (ListKind, String) -> Unit,
    onNotices: () -> Unit,
    onDownloads: () -> Unit,
    onCopyHome: () -> Unit,
    onOpenWeb: (String, String) -> Unit,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    // 本机素材槽：设置里存的是**文件绝对路径**；slotTick 只负责「挑完图之后让界面重读一次」。
    var slotTick by remember { mutableStateOf(0) }
    val coverFile = remember(slotTick) { X99.setting("user.cover.file", "") }
    val avatarFile = remember(slotTick) { X99.setting("user.avatar.file", "") }
    val coverImg = remember(coverFile) { if (coverFile.isBlank()) null else HplaySlots.decodeImage(coverFile) }
    val avatarImg = remember(avatarFile) { if (avatarFile.isBlank()) null else HplaySlots.decodeImage(avatarFile) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        // —— 顶部资料区（图2 的封面 + 头像 + 昵称 + 邮箱 + 积分那一块）——
        Box(
            Modifier.fillMaxWidth().height(150.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF3A2318), Color(0xFF141416)))),
        ) {
            if (coverImg != null) {
                androidx.compose.foundation.Image(
                    bitmap = coverImg,
                    contentDescription = "本机封面",
                    modifier = Modifier.fillMaxSize().alpha(0.62f),
                    contentScale = ContentScale.Crop,
                )
            } else if (profile != null && profile.avatarLarge.isNotBlank()) {
                com.han1me.web.HplayThumb(
                    profile.avatarLarge, profile.name,
                    Modifier.fillMaxSize().alpha(0.45f),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().offset(y = (-30).dp)) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(CardColor)
                        .border(2.dp, Color(0xFF3A3A40), RoundedCornerShape(36.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatarImg != null) {
                        androidx.compose.foundation.Image(
                            bitmap = avatarImg,
                            contentDescription = "本机头像",
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(34.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else if (profile != null && profile.avatar.isNotBlank()) {
                        com.han1me.web.HplayThumb(
                            profile.avatar, profile.name, Modifier.fillMaxSize().clip(RoundedCornerShape(34.dp)),
                        )
                    } else {
                        Text(if (profile == null) "◉" else profile.name.take(1), color = TextDim, fontSize = 26.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (profile != null) {
                    OutlinedButton(
                        onClick = { onOpenWeb(profile.edit, "帳戶設定") },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.height(34.dp),
                    ) { Text("编辑个人资料", fontSize = 12.sp, color = Color(0xFFE8E8EA)) }
                }
            }
            Text(
                profile?.name ?: if (user != null) "已登录" else "未登录",
                color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(y = (-14).dp),
            )
            if (email.isNotBlank()) {
                Text(email, color = TextDim, fontSize = 12.sp, modifier = Modifier.offset(y = (-10).dp))
            }
            if (profile != null) {
                Text(
                    "@ ${profile.uid}" + if (profile.stats.isNotBlank()) "　${profile.stats}" else "",
                    color = TextFaint, fontSize = 11.5.sp, modifier = Modifier.offset(y = (-8).dp),
                )
            } else if (mirror) {
                Text("当前站源：优选线路（账号功能不可用）", color = TextFaint, fontSize = 11.5.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (user == null) {
                Button(
                    onClick = onLogin,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                ) { Text("登录 / 注册", fontSize = 13.sp) }
                Spacer(Modifier.height(10.dp))
            }
            OutlinedButton(
                onClick = onOpenSites,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(36.dp),
            ) { Text("⇄ 优选访问 / 站点线路", fontSize = 12.sp, color = Color(0xFFE8E8EA)) }
            Spacer(Modifier.height(14.dp))
            UserRow("🔗", "复制我的主页链接", if (profile == null) "未登录时没有主页" else profile.home) { onCopyHome() }
            // 本机素材槽（第 43 轮）。`pickSupported` 只有安卓壳会置 true：
            // 桌面没有系统相册可唤，就把这两行**整块撤掉**，而不是留两个点了什么都不发生的行。
            if (HplaySlots.pickSupported) {
                UserRow(
                    "🖼", "本机封面",
                    if (coverFile.isBlank()) "挑一张本机图片当用户页封面"
                    else "已设置：${coverFile.substringAfterLast('/', coverFile).substringAfterLast('\\')}" +
                        "｜再点一次换一张",
                ) { HplaySlots.pickImage("user.cover.file") { p -> if (p != null) { X99.putSetting("user.cover.file", p); slotTick++ } } }
                UserRow(
                    "🙂", "本机头像",
                    if (avatarFile.isBlank()) "挑一张本机图片替代官方账号头像"
                    else "已设置：${avatarFile.substringAfterLast('/', avatarFile).substringAfterLast('\\')}" +
                        "｜再点一次换一张",
                ) { HplaySlots.pickImage("user.avatar.file") { p -> if (p != null) { X99.putSetting("user.avatar.file", p); slotTick++ } } }
                if (coverFile.isNotBlank() || avatarFile.isNotBlank()) {
                    UserRow("✕", "清除本机素材", "只清这两格，不动账号里的任何东西") {
                        X99.putSetting("user.cover.file", "")
                        X99.putSetting("user.avatar.file", "")
                        slotTick++
                    }
                }
            }
            UserRow("📩", "信息", "本机更新与交付通知") { onNotices() }
            UserRow("🕘", "历史记录", "hanime1.me 账号里的观看纪录") {
                onOpen(ListKind.HISTORY, "观看历史")
            }
            UserRow("⬇", "本地下载", "把已验证的直链下到本机（HLS 连分片一起下）") { onDownloads() }
            UserRow("⚙", "设置", if (settingsOpen) "收起" else "网络 / DoH / 代理 / 边缘节点") {
                settingsOpen = !settingsOpen
            }
            if (settingsOpen) {
                Spacer(Modifier.height(6.dp))
                NetSettingsSection()
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "官方源没有积分、徽章、私信与站内下载中心（实测 /points 与 /notifications 都回 404），" +
                    "所以这几格没有放——放了也是点不动的空壳。",
                color = TextFaint, fontSize = 11.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun UserRow(icon: String, title: String, sub: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardColor)
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(icon, fontSize = 16.sp, color = Color(0xFFE8E8EA))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (sub.isNotBlank()) {
                Text(sub, color = TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("›", color = TextFaint, fontSize = 16.sp)
    }
    Spacer(Modifier.height(8.dp))
}

/** 目录总字节数。`File.totalSize()` 是 JDK 9+ 的 API，安卓侧不可依赖，所以自己走一遍。 */
private fun dirBytes(f: java.io.File): Long {
    if (!f.isDirectory) return if (f.isFile) f.length() else 0L
    return (f.listFiles() ?: emptyArray()).sumOf { dirBytes(it) }
}

/** 「信息」页：本机真实内容（版本、构建、板块清单、交付通道、本轮变更），不是照抄截图里的空通知。 */
@Composable
private fun NoticesPanel(onClose: () -> Unit) {
    val notices = remember {
        listOf(
            "版本 v${HplaySlots.versionLabel()}",
            "已接入 ${X99.otherSites().size} 个站点板块，其中 ${X99.nativeBoardCount()} 个支持原生内嵌播放",
            "本地收藏与下载都存在应用自己的目录里，不上传、不同步到任何站点",
            "hanime1.me 官方源没有积分、徽章、私信与站内下载中心（实测 /points 与 /notifications 都回 404），" +
                "用户页因此不放这几格",
            "直链取不到时会保留「🌐 网页播放」入口，避免整片看不了",
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(AccentRed).clickable { onClose() },
            ) { Text("←", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Text("信息", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        notices.forEach { n ->
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(10.dp))
                .background(CardColor).padding(12.dp)) {
                Text(n, color = Color(0xFFD8D8DC), fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

/** 「本地下载」页：列出已下好的条目，可直接内嵌播放，也能删。 */
@Composable
private fun DownloadsPanel(
    tick: Int,
    downloading: String?,
    onClose: () -> Unit,
    onPlay: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val items = remember(tick) { X99.downloads() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(AccentRed).clickable { onClose() },
            ) { Text("←", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            Text("本地下载 · ${items.size}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        downloading?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = AccentRed, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("正在下载：$it", color = TextDim, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (items.isEmpty() && downloading == null) {
            Text(
                "还没有下载。在影片介绍页点「⬇ 下载到本机」即可；HLS 会把分片与密钥一起下到本地，" +
                    "断网也能播。",
                color = TextFaint, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }
        items.forEach { (path, at) ->
            val f = java.io.File(path)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp)).background(CardColor).padding(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.parentFile?.name ?: f.name, color = Color(0xFFE8E8EA), fontSize = 12.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "%,d B".format(dirBytes(f)) + "　" +
                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(at)),
                        color = TextFaint, fontSize = 11.sp,
                    )
                }
                Text(
                    "▶", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(AccentRed)
                        .clickable { onPlay(path) }.padding(horizontal = 11.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "✕", color = TextFaint, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(CardColorSoft)
                        .clickable { onDelete(path) }.padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ---------- 我的面板（登录 / 清单入口 / 网络设置，文案与注入版一致） ----------

// ---------- 我的面板（登录 / 清单入口 / 网络设置，文案与注入版一致） ----------

@Composable
private fun MinePanel(
    user: String?,
    mirror: Boolean = false,
    onClose: () -> Unit,
    onLogin: () -> Unit,
    onOpen: (ListKind, String) -> Unit,
    onOpenWebList: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xF20A0A0C)).clickable { onClose() }) {
        // 右上角圆形关闭按钮（与注入版一致）
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(18.dp)
                .size(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(CardColorSoft)
                .clickable { onClose() },
        ) { Text("✕", color = Color.White, fontSize = 14.sp) }
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth(0.52f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF161619))
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
        ) {
            Text("我的 Hanime", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (mirror) {
                Text(
                    "当前为优选线路模式：账号 / 收藏 / 历史为 hanime1.me 官方功能，此线路下不可用。" +
                        "需要账号功能请在「优」里切回 Hanime1（官方源）。",
                    color = TextDim, fontSize = 12.sp, lineHeight = 17.sp,
                )
            } else {
                Text(
                    if (user != null) "已登录：$user" else "未登录（点击下方按钮登录后可同步清单）",
                    color = TextDim, fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                if (user == null) {
                    Button(
                        onClick = onLogin,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                    ) { Text("登录 / 注册", fontSize = 13.sp) }
                    Spacer(Modifier.height(10.dp))
                }
                MineItem("❤ 喜欢的影片") { onOpen(ListKind.LIKES, "喜欢的影片") }
                MineItem("🕒 稍后观看") { onOpen(ListKind.SAVES, "稍后观看") }
                MineItem("📺 观看历史") { onOpen(ListKind.HISTORY, "观看历史") }
                MineItem("🔔 我的订阅") { onOpen(ListKind.SUBSCRIPTIONS, "我的订阅") }
                MineItem("🎬 播放列表（网页）") { onOpenWebList() }
            }
            Spacer(Modifier.height(12.dp))
            NetSettingsSection()
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
        }
    }
}

/** 网络设置：全应用走代理 + 播放中继 + DoH + 代理模式（自动/直连/手动）+ 状态与重新探测。 */
@Composable
private fun NetSettingsSection() {
    var doh by remember { mutableStateOf(HanimeClient.dohEnabled) }
    var mode by remember { mutableStateOf(HanimeClient.proxyMode) }
    var manual by remember { mutableStateOf(HanimeClient.manualProxy) }
    var relay by remember { mutableStateOf(HanimeClient.relayEnabled) }
    var globalProxy by remember { mutableStateOf(HanimeClient.globalProxyEnabled) }
    var diag by remember { mutableStateOf(HanimeClient.netDiag()) }
    var expanded by remember { mutableStateOf(false) }

    Text(
        "⚙ 网络设置 " + (if (expanded) "▾" else "▸"),
        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
    )
    if (!expanded) return

    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("全应用走代理（仅作用于应用自身网络请求；优选线路与站点合集始终直连）", color = Color(0xFFD8D8DC), fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = globalProxy,
            onCheckedChange = {
                globalProxy = it
                HanimeClient.updateSettings(doh = doh, mode = mode, manual = manual, relay = relay, globalProxy = it)
                diag = HanimeClient.netDiag()
            },
        )
    }
    Text(
        if (globalProxy) "提示：切换后重启应用生效（启动时自动检测并接管应用网络）" else "",
        color = TextFaint, fontSize = 10.sp,
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("播放中继（官方源播放走本地中继，推荐开启）", color = Color(0xFFD8D8DC), fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = relay,
            onCheckedChange = {
                relay = it
                HanimeClient.updateSettings(doh = doh, mode = mode, manual = manual, relay = it, globalProxy = globalProxy)
                diag = HanimeClient.netDiag()
            },
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("DoH 解析（默认开；仅作用于 hanime 官方源）", color = Color(0xFFD8D8DC), fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = doh,
            onCheckedChange = {
                doh = it
                HanimeClient.updateSettings(doh = it, mode = mode, manual = manual, relay = relay, globalProxy = globalProxy)
                diag = HanimeClient.netDiag()
            },
        )
    }
    Spacer(Modifier.height(6.dp))
    Text("代理模式", color = Color(0xFFD8D8DC), fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("auto" to "自动探测", "direct" to "直连", "manual" to "手动").forEach { (m, label) ->
            Text(
                label, fontSize = 12.sp,
                color = if (mode == m) Color.White else TextDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (mode == m) AccentRed else CardColorSoft)
                    .clickable {
                        mode = m
                        HanimeClient.updateSettings(doh = doh, mode = m, manual = manual, relay = relay, globalProxy = globalProxy)
                        diag = HanimeClient.netDiag()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    if (mode == "manual") {
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            placeholder = { Text("http://127.0.0.1:10809 或 socks5://127.0.0.1:10808", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = {
            HanimeClient.updateSettings(doh = doh, mode = mode, manual = manual, relay = relay, globalProxy = globalProxy)
            diag = HanimeClient.netDiag()
        }) { Text("应用") }
    }
    Spacer(Modifier.height(4.dp))
    Text(diag, color = TextFaint, fontSize = 10.sp, lineHeight = 14.sp)
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = {
        HanimeClient.reprobeProxy()
        diag = HanimeClient.netDiag()
    }) { Text("重新探测代理") }
    Spacer(Modifier.height(10.dp))
    EdgeNodeSection(hostsChanged = { diag = HanimeClient.netDiag() })
}

/**
 * 边缘节点（内置 Hosts）——参考 Han1meViewer：把 hanime 官方域名直接解析到 Cloudflare 边缘 IP。
 * **仅对 hanime1.me 官方源生效**；优选线路与站点合集一律直连、不用 hosts，不受影响。
 * 每次打开程序会自动测速并选最快（见 Han1meApp 启动逻辑）；此处也可手动测速/切换/填自定义 Hosts。
 */
@Composable
private fun EdgeNodeSection(hostsChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(HanimeClient.edgeHostsEnabled) }
    var custom by remember { mutableStateOf(HanimeClient.edgeCustomIps) }
    var node by remember { mutableStateOf(HanimeClient.edgeNode) }
    var delays by remember { mutableStateOf(HanimeClient.lastNodeTest) }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    Text(
        "🛰 边缘节点（内置 Hosts）" + (if (expanded) " ▾" else " ▸"),
        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
    )
    if (!expanded) return

    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "启用内置 Hosts 加速（仅 hanime 官方源；优选线路与其它板块不受影响）",
            color = Color(0xFFD8D8DC), fontSize = 12.sp, modifier = Modifier.weight(1f),
        )
        Switch(
            checked = on,
            onCheckedChange = {
                on = it
                HanimeClient.updateEdgeHosts(it, custom)
                hostsChanged()
            },
        )
    }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (busy) "测速中…" else "⚡ 测速并选最快",
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(13.dp))
                .background(if (busy) Color(0xFF2E2E34) else AccentRed)
                .clickable(enabled = !busy) {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        val map = runCatching { HanimeClient.pickFastestNode() }.getOrDefault(emptyMap())
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            delays = map
                            node = HanimeClient.edgeNode
                            busy = false
                            hostsChanged()
                        }
                    }
                }
                .padding(horizontal = 11.dp, vertical = 5.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (HanimeClient.proxyInUse()) "当前走代理：由代理解析，节点不参与" else "当前直连：节点生效",
            color = TextFaint, fontSize = 10.5.sp,
        )
    }
    Spacer(Modifier.height(6.dp))
    val nodes = HanimeClient.builtInNodes
    nodes.sortedBy { delays[it] ?: Long.MAX_VALUE / 2 }.forEach { ip ->
        val d = delays[ip]
        val sel = ip == node
        Text(
            (if (sel) "● " else "○ ") + ip + (if (d == null) "   …" else if (d < 0) "   不可用" else "   $d ms"),
            color = when {
                sel -> Color.White
                d != null && d >= 0 -> Color(0xFF7ED97E)
                d != null -> Color(0xFFFF8A8A)
                else -> TextDim
            },
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().clickable { HanimeClient.setEdgeNode(ip); node = ip; hostsChanged() }
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
    TextButton(onClick = {
        HanimeClient.updateEdgeHosts(on, custom)
        hostsChanged()
    }) { Text("应用自定义 Hosts") }
    Text(
        "说明：内置节点取自 Han1meViewer（Cloudflare 边缘 IP）；每次打开程序会自动测速并选最快。" +
            "走代理时由代理解析域名，节点仅在直连时生效。",
        color = TextFaint, fontSize = 10.sp, lineHeight = 14.sp,
    )
}

@Composable
private fun MineItem(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color(0xFFE8E8EA), fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardColorSoft)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    )
}

/**
 * 播一个本机下载好的文件。
 *
 * **不再另起一个播放器**：宿主 jar 里那套 player 包名在安卓侧由补丁换成 com.han1me.player，
 * 所以它的 API 只能出现在**已被补丁锚定**的那一处（详情面板里）。
 * 这里改成合成一条"只有本机源"的详情，走同一条已经翻译好的播放路径——
 * 顺带也就保证了本机文件与在线直链用的是同一个控制器、同一套全屏/返回键行为。
 */
private fun localFileDetail(path: String): HanimeDetail {
    val f = java.io.File(java.io.File(path).parentFile, "")
    val name = f.name.ifBlank { "本机媒体" }
    return HanimeDetail(
        video = HanimeVideo(
            id = "local:" + java.net.URI(path).path.orEmpty().hashCode(),
            url = "https://local-file.invalid/" + name.trimEnd('/'),
            title = name,
            thumb = "",
            duration = "",
            likes = "",
            views = "",
            brand = "本机",
        ),
        description = "这是已下载到本机的副本，断网也能播。",
        tags = listOf("本机"),
        sources = listOf(0 to "file://" + path),
        related = emptyList(),
        isFav = false,
        isSaved = false,
        saveListId = "",
    )
}

// ---------- 详情浮层（内嵌播放器复用宿主 jar 的 VideoPlayer 体系） ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HanimeDetailPanel(
    d: HanimeDetail,
    favTick: Int,
    /**
     * 这一条详情来自「站点板块」还是官方源/本机。
     * 收藏到本站、下载到本机、以及"直链旁边的网页播放"这三样都只在板块来的详情上出现——
     * 用 `!officialSite` 判会误伤本机文件（它的 URL 也不是官方域）。
     */
    fromBoard: Boolean,
    onOpenVideo: (HanimeVideo) -> Unit,
    onClose: () -> Unit,
    onToast: (String) -> Unit,
    onWebPlay: (String, String) -> Unit,
    onFavToggled: (Boolean) -> Unit,
    onSavedToggled: (Boolean) -> Unit,
    onToggleLocalFav: () -> Unit,
    onDownload: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var playingUrl by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    // 收藏 / 稍后看是 hanime1.me 官方账号功能，只有官方源详情能用；
    // 优选镜像与内置 xchina 的详情带上这两个键，点了是拿官方接口去改别的库，只会得到一条失败 toast。
    val officialSite = runCatching {
        HanimeClient.isOfficialHost(java.net.URI(d.video.url).host.orEmpty())
    }.getOrDefault(true)
    // 内嵌播放器：initIsFullscreen 默认 false（小窗），全屏按钮在播放器控制条上
    val controller = com.javrocket.playground.player.ui.rememberVideoPlayerController(
        ownerKey = "hanime-detail:${d.video.id}",
    )
    // 帧调度/播放生命周期（与宿主 VideoPlayerScreen 同款，缺它画面不显示）
    // 签名: VideoPlayerLifecycle(controller, ownerKey: String?, isPlaybackVisible: Boolean)
    com.javrocket.playground.player.ui.VideoPlayerLifecycle(
        controller, "hanime-detail:${d.video.id}", playingUrl != null,
    )

    androidx.compose.runtime.LaunchedEffect(playingUrl) {
        val u = playingUrl ?: return@LaunchedEffect
        runCatching {
            controller.prepare(
                com.javrocket.playground.player.PlaybackLine.fromUrl(
                    "hanime", u, "video/mp4", "",
                    com.javrocket.playground.player.PlaybackTransport(emptyMap()),
                ),
                null, false,
            )
            controller.play()
        }
    }

    // —— 播放即放大：点画质后视频直接放大成满窗浮层（弹窗扩张效果），详情其它元素一律不渲染 ——
    if (playingUrl != null) {
        val grow = androidx.compose.animation.core.animateFloatAsState(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.tween(240),
            label = "grow",
        )
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .graphicsLayer(scaleX = 0.94f + 0.06f * grow.value, scaleY = 0.94f + 0.06f * grow.value, alpha = grow.value),
        ) {
            com.javrocket.playground.player.ui.VideoPlayer(
                controller,
                Modifier.fillMaxSize(),
                onBackClick = { runCatching { controller.stop() }; playingUrl = null },
                onFullscreenClick = { controller.toggleFullscreen() },
            )
            Text(
                "⤡ 收起", color = Color.White, fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd).padding(14.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xB326262B))
                    .clickable { runCatching { controller.stop() }; playingUrl = null }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Color(0xF20A0A0C))) {
        Column(Modifier.fillMaxSize().padding(top = 52.dp).verticalScroll(scroll)) {
            if (false) {   // 播放改走顶部「放大浮层」，此处不再渲染
                androidx.compose.material3.Button(
                    onClick = { runCatching { controller.stop() }; playingUrl = null },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A30)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp).height(32.dp),
                ) { Text("✕ 关闭播放（回到封面）", fontSize = 11.sp) }
                com.javrocket.playground.player.ui.VideoPlayer(
                    controller,
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    onBackClick = { runCatching { controller.stop() }; playingUrl = null },
                    onFullscreenClick = { controller.toggleFullscreen() },
                )
            } else {
                Box(Modifier.fillMaxWidth().height(230.dp)) {
                    AsyncImage(model = d.video.thumb, contentDescription = d.video.title, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color(0x000A0A0C), Color(0x660A0A0C), Color(0xF20A0A0C))),
                        ),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(d.video.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.video.duration.isNotBlank()) InfoChip("🕒 ${d.video.duration}")
                    Spacer(Modifier.width(8.dp))
                    if (d.video.likes.isNotBlank()) InfoChip("👍 ${d.video.likes}")
                    Spacer(Modifier.width(8.dp))
                    if (d.video.views.isNotBlank()) InfoChip("👁 ${d.video.views}")
                }
                Spacer(Modifier.height(12.dp))
                // 操作行：收藏 / 稍后看 / 外部播放 / 复制直链
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (officialSite) DetailAction(if (d.isFav) "❤ 已收藏" else "♡ 收藏", busy) {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val now = HanimeClient.toggleFavorite(d.video.id, d.isFav)
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    onFavToggled(now)
                                    onToast(if (now) "已加入喜欢的影片" else "已取消收藏")
                                }
                            } catch (t: Throwable) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) { onToast("操作失败：${t.message}") }
                            } finally {
                                busy = false
                            }
                        }
                    }
                    if (officialSite) DetailAction(if (d.isSaved) "🕒 已稍后看" else "＋ 稍后看", busy) {
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val now = HanimeClient.toggleWatchLater(d.video.id, d.isSaved, d.saveListId)
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    onSavedToggled(now)
                                    onToast(if (now) "已加入稍后观看" else "已移出稍后观看")
                                }
                            } catch (t: Throwable) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) { onToast("操作失败：${t.message}") }
                            } finally {
                                busy = false
                            }
                        }
                    }
                    // 站点板块的收藏写在**本机**（这些站没有可写的账号体系），
                    // 与上面官方源那个「♡ 收藏」是两回事，所以标签也分开写清「本站」。
                    if (fromBoard && !officialSite) {
                        val faved = remember(favTick, d.video.url) { X99.isFav(d.video.url) }
                        DetailAction(if (faved) "❤ 已收藏（本站）" else "♡ 收藏到本站", busy) { onToggleLocalFav() }
                    }
                    val first = d.sources.firstOrNull()
                    if (first != null) {
                        DetailAction("📺 外部播放", busy) {
                            try {
                                val f = HanimeClient.writeStrm(d.video.id, HanimeClient.relayUrl(first.second))
                                java.awt.Desktop.getDesktop().open(f)
                                onToast("已交给系统默认播放器（${first.first}P，经本地中继走代理）")
                            } catch (t: Throwable) {
                                onToast("打开外部播放器失败：${t.message}")
                            }
                        }
                        DetailAction("🔗 复制直链", busy) {
                            try {
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                    .setContents(java.awt.datatransfer.StringSelection(first.second), null)
                                onToast("已复制 ${first.first}P 直链（原始裸链）")
                            } catch (t: Throwable) {
                                onToast("复制失败：${t.message}")
                            }
                        }
                    }
                    // 只有站点板块给下载按钮：官方源的媒体域带防盗链，桌面这边已有中继路径，
                    // 而这一轮的用户页要求是「本地下载」这一格必须真能用。
                    if (fromBoard && !officialSite) {
                        DetailAction("⬇ 下载到本机", busy) { onDownload() }
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (d.sources.isEmpty()) {
                    // 没有验证过的可播清单 ⇒ 如实说「交给内置浏览器」，而不是摆一个点了没反应的画质按钮。
                    Text(
                        "该站点未取得可验证直链，播放由内置浏览器完成（去广告已注入）",
                        color = TextDim, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onWebPlay(d.video.url, d.video.title) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(38.dp),
                    ) {
                        Text("▶ 网页播放", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                Text("选择画质（内嵌播放 · 默认小窗，播放器控制条可切全屏）", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    d.sources.forEach { (quality, url) ->
                        Button(
                            onClick = {
                                // 「这是不是原生板块的播放」必须在**交出处**写明，不能留给播放器按域名反推：
                                // 官方源的媒体域是 vdownload.hembed.com，它不在 officialHosts 那四个页面域里，
                                // 反推会把官方源也认成第三方板块（强制横屏 + 多塞 Referer）。
                                // 判据用详情页自己的 URL，与卡片分流共用同一个 isNativeBoardUrl。
                                X99.nativePlay = X99.isNativeBoardUrl(d.video.url)
                                playingUrl = HanimeClient.relayUrl(url)
                                scope.launch { scroll.animateScrollTo(0) }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(38.dp),
                        ) {
                            Text(if (quality > 0) "${quality}P" else "播放",
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    // 直链是**取的时候**验过的，但站点侧的签名/线路池会过期或整条撤（肉视频的线路池、
                    // ganxiaban 的 sign 24 h 都是实测会变的）。所以取到直链也不能把网页那条路堵死：
                    // 用户 2026-10-02 点名「同步 ui 直链时也要留出网页播放板块，避免直链失败无法观看」。
                    // 只给站点板块加，官方源那一支的界面一行不动。
                    if (fromBoard && !officialSite) {
                        OutlinedButton(
                            onClick = { onWebPlay(d.video.url, d.video.title) },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(38.dp),
                        ) {
                            Text("🌐 网页播放（直链失败时用）", fontSize = 12.sp, color = Color(0xFFE8E8EA))
                        }
                    }
                }
                }
                if (d.tags.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.tags.forEach { tag ->
                            Text(
                                tag, color = Color(0xFFD8D8DC), fontSize = 11.sp,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(CardColorSoft).padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                if (d.description.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardColor).padding(12.dp)) {
                        Text("简介", color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(d.description, color = Color(0xFFCFCFD4), fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            if (d.related.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("相关影片", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                ) {
                    // 键用 URL 不用 id：通用卡片的 id 是 `url.takeLast(60)`，长链接共后缀会撞键，
                    // LazyRow 撞键是硬崩（Key was already used）。related 已按 URL 去重，这里唯一性有保证。
                    items(d.related.take(16), key = { it.url }) { v ->
                        SiteCard(v, width = 158.dp, onClick = { onOpenVideo(v) })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        // 左上角返回键：最后绘制（层级最高），全屏播放时也始终可见可点
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(16.dp)
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AccentRed)
                .clickable { onClose() },
        ) { Text("←", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun DetailAction(label: String, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.height(36.dp),
    ) { Text(label, fontSize = 12.sp, color = Color(0xFFE8E8EA)) }
}

@Composable
private fun InfoChip(text: String) {
    Text(
        text, color = Color(0xFFD8D8DC), fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(CardColorSoft).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ---------- 登录对话框 ----------

@Composable
private fun HanimeLoginDialog(
    onDismiss: () -> Unit,
    onLoggedIn: (String) -> Unit,
    onWebLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录 Hanime1.me") },
        text = {
            Column {
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("邮箱") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") }, singleLine = true)
                err?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("登录后可看收藏/稍后观看/历史；也可用系统浏览器登录。", fontSize = 11.sp, color = TextDim)
            }
        },
        confirmButton = {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = AccentRed)
            else Button(
                onClick = {
                    busy = true
                    err = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            val name = HanimeClient.login(email.trim(), password)
                            // 站点不公开邮箱（个人页上没有这个字段），所以图2 那一行邮箱只能记我们自己经手的值：
                            // 用户在本机输入过一次，之后用户页就显示它。不存在"从站点读回来"这条路。
                            if (email.isNotBlank()) com.han1me.X99.putSetting("account.email", email.trim())
                            kotlinx.coroutines.withContext(Dispatchers.Main) { onLoggedIn(name) }
                        } catch (t: Throwable) {
                            err = t.message ?: t.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
            ) { Text("登录") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onWebLogin) { Text("网页登录") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
