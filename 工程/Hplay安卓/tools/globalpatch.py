# -*- coding: utf-8 -*-
"""**全局替换规则**（2026-11-25 第 278 轮新增）。

与 patches.py / postpatch.py 的分工：

| 文件 | 匹配要求 | 适用场景 |
|---|---|---|
| patches.py | old **恰好命中 1 次** | 单点植入（新增函数、改一段逻辑） |
| postpatch.py | old **恰好命中 1 次** | 单点修正 |
| **本文件** | 命中 **N 次全部替换** | **批量替换**（同一种颜色出现在几十处） |

为什么要单独一个文件：UI 重构时 `Han1meApp.kt` 里有 34 处 `Color(0xFFxxxxxx)`
写死的文本/背景色，每一处都要换成主题语义色。用 patches.py 得为每处写不同上下文，
既脆弱（上游一改就失配）又难维护；而它们的**替换语义完全相同**，本质是批量替换。

规矩：
  · 每条 (文件, old, new) 的 old **至少要命中 1 次**，否则报错（防止规则失效无人察觉）；
  · 替换是**全量**的（text.replace，替换所有出现）；
  · 执行时机：在 patches.py 重放 + postpatch.py 之后、硬检查之前。
"""
import io
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GEN = ROOT / "build" / "gen"


# ---------------------------------------------------------------- 颜色 -> 主题语义色
#
# 这些是 UI 重构时统一的主题化规则。语义对应关系都有理由：
#   · 浅灰文字 0xFFD8D8DC / 0xFFE0E0E0 -> onSurfaceVariant（次要文本）
#   · 亮白文字 0xFFE8E8EA / 0xFFE4E4E8 -> onSurface（主要文本）
#   · 红 0xFFFF8A8A -> error
#   · 各层深灰背景 -> surfaceContainer 系列
#
# **刻意不替换**的语义色（它们不该跟主题变）：
#   · 0xFFB8E0B8 好评绿、0xFF7ED97E 可用绿
#   · 0xFF3A2318 / 0xFF141416 轮播遮罩渐变
#   · 纯黑纯白（Color.Black / Color.White，视频播放器与遮罩需要）
COLOR_RULES = [
    ("Han1meApp.kt", "Color(0xFFD8D8DC)", "MaterialTheme.colorScheme.onSurfaceVariant"),
    ("Han1meApp.kt", "Color(0xFFE8E8EA)", "MaterialTheme.colorScheme.onSurface"),
    ("Han1meApp.kt", "Color(0xFFE4E4E8)", "MaterialTheme.colorScheme.onSurface"),
    ("Han1meApp.kt", "Color(0xFFE0E0E0)", "MaterialTheme.colorScheme.onSurfaceVariant"),
    ("Han1meApp.kt", "Color(0xFFFF8A8A)", "MaterialTheme.colorScheme.error"),
    ("Han1meApp.kt", "Color(0xFF2E2E34)", "MaterialTheme.colorScheme.surfaceContainerHigh"),
    ("Han1meApp.kt", "Color(0xFF2A1518)", "MaterialTheme.colorScheme.primaryContainer"),
    ("Han1meApp.kt", "Color(0xFF1C1C1F)", "MaterialTheme.colorScheme.surfaceContainer"),
    ("Han1meApp.kt", "Color(0xFF111114)", "MaterialTheme.colorScheme.surface"),
    ("Han1meApp.kt", "Color(0xFF161619)", "MaterialTheme.colorScheme.surface"),
    ("Han1meApp.kt", "Color(0xFF4A4A52)", "MaterialTheme.colorScheme.outline"),
    ("Han1meApp.kt", "Color(0xFF3A3A40)", "MaterialTheme.colorScheme.outlineVariant"),
    # 引入 MaterialTheme（上面所有规则都依赖它）
    ("Han1meApp.kt",
     "import androidx.compose.foundation.background",
     "import androidx.compose.material3.MaterialTheme\nimport androidx.compose.foundation.background"),
]

# ---------------------------------------------------------------- 用户页旧设置面板退场
#
# 2026-11-25：设置已迁到独立页面（HplaySettingsHost），但 UserTab 里那段
# 「点设置就在本页展开 6 个面板」的旧代码还在 —— 用户点设置会**同时**打开新设置页
# 和展开旧面板（用户实测指出「用户页还残留着旧的网络设置」）。
#
# 这里把旧面板整段清空：保留 if 语句结构（避免括号不配平），只清掉内容。
LEGACY_PANEL_RULES = [
    (
        "Han1meApp.kt",
        """            if (settingsOpen) {
                Spacer(Modifier.height(6.dp))""",
        """            if (false) {
                Spacer(Modifier.height(0.dp))""",
    ),
    # 逐个摘掉旧面板的调用（保留注释，日后要回滚看得见原来是什么）
    (
        "Han1meApp.kt",
        """                CatalogSourceSection()
                Spacer(Modifier.height(10.dp))
                NetSettingsSection()
                Spacer(Modifier.height(10.dp))""",
        """                // ★ 2026-11-25：旧内联面板已迁至独立设置页（SettingsHost）""",
    ),
]


# ---------------------------------------------------------------- 用户页按 Viewer 重构
#
# 用户要求：「用户页、设置页等所有板块进行完全的重构」。
#
# 原版用户页是一长列 UserRow（复制链接 / 信息 / 检查更新 / 历史 / 本地下载 / 打卡 /
# 订阅 / 设置），越加越长。参照 Han1meViewer 的 ProfileScreen 改成：
#   顶部资料卡 + 2 列网格快捷入口。
#
# 做法：把「从 UserRow("🔗"...) 开始到设置那行结束」的整段
# 替换成一次 HplayProfileGrid 调用（该组件在 ui/ProfileGrid.kt）。
USER_PAGE_OLD = open(r"D:\deepseek-harness\deepseek-harness工作区\hpla实验\_临时\userpage_old.txt",
                     encoding="utf-8").read()
USER_PAGE_NEW = open(r"D:\deepseek-harness\deepseek-harness工作区\hpla实验\_临时\userpage_new.txt",
                     encoding="utf-8").read()

USER_PAGE_RULES = [
    # ① 外层滚动容器：网格不能套在 verticalScroll 里（会得到无限高约束 → 崩溃）
    (
        "Han1meApp.kt",
        """    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        // —— 顶部资料区（图2 的封面 + 头像 + 昵称 + 邮箱 + 积分那一块）——""",
        """    Column(
        // ★★★ 2026-11-25（第 278 轮）：**去掉外层 verticalScroll**。
        //
        // 用户页改成 LazyVerticalGrid 之后，再套一层 verticalScroll 会让网格
        // 拿到「无限最大高度」约束 —— Compose 直接抛
        //   IllegalStateException: Vertically scrollable component was measured with
        //   an infinity maximum height constraints
        // 真机实测：进用户页必崩。
        //
        // 网格自己会滚，不需要外层。旧的 UserRow 列表已经不在这里渲染了。
        Modifier.fillMaxSize(),
    ) {
        // —— 顶部资料区（图2 的封面 + 头像 + 昵称 + 邮箱 + 积分那一块）——""",
    ),

    # ② 用户页入口列表 -> 网格
    ("Han1meApp.kt", USER_PAGE_OLD, USER_PAGE_NEW),
    # ③ 隐藏旧封面区（ProfileGrid 已经是完整用户页）
    (
        "Han1meApp.kt",
        """        // —— 顶部资料区（图2 的封面 + 头像 + 昵称 + 邮箱 + 积分那一块）——
        // ★★ LABUI（2026-10-31）：封面**直接可点**即更换（用户要求「点一下封面就能更换封面」）。
        Box(
            Modifier.fillMaxWidth().height(150.dp)""",
        """        // ★★★ 2026-11-25（第 278 轮）：旧封面区整段停用。
        // 用户页现在由 HplayProfileGrid（照 Han1meViewer ProfileScreen）承载。
        if (false) Box(
            Modifier.fillMaxWidth().height(150.dp)""",
    ),

]

# ---------------------------------------------------------------- 登录修复（第 279 轮）
#
# 用户实测：同一节点下主页能加载，但登录一直失败。
#
# 对比 Han1meViewer 的 HanimeBaseService.login：
#   @FormUrlEncoded @POST("login")
#   suspend fun login(
#       @Field("_token") csrfToken: String?,
#       @Field("email") email: String,
#       @Field("password") password: String,
#       @Header("X-CSRF-TOKEN") csrfToken_1: String? = csrfToken,   // ← 请求头
#   )
#
# 我们的实现只把 _token 放进表单，**漏了 X-CSRF-TOKEN 请求头** ——
# Laravel 的 VerifyCsrfToken 在 AJAX 请求上优先读这个头，缺了会判 CSRF 失败。
LOGIN_RULES = [
    (
        "HanimeClient.kt",
        """            .header("Referer", "$BASE/login")
            .header("Origin", BASE)
            .build()""",
        """            .header("Referer", "$BASE/login")
            .header("Origin", BASE)
            // ★ 2026-11-25（第 279 轮 · 登录修复）：**必须带 X-CSRF-TOKEN 请求头**。
            // 对照 Han1meViewer 的 HanimeBaseService.login：
            //   @Header("X-CSRF-TOKEN") csrfToken_1: String? = csrfToken
            // Laravel 在 AJAX 请求上优先读这个头；只放表单不够。
            .header("X-CSRF-TOKEN", token)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .build()""",
    ),
    # 403 说清原因（CF 拦截 ≠ 密码错误）
    (
        "HanimeClient.kt",
        """            } else if (!resp.isSuccessful) {""",
        """            } else if (resp.code == 403) {
                // ★ 2026-11-25：403 几乎一定是 Cloudflare 质询，不是密码错误。
                // 提示说清楚，否则用户会反复试密码（实测踩过）。
                val mitigated = resp.header("cf-mitigated").orEmpty()
                throw IllegalStateException(
                    if (mitigated.equals("challenge", true))
                        "被 Cloudflare 拦截（需先过一次人机验证）。回主页刷新一下再登录。"
                    else
                        "登录被拒绝（HTTP 403）。可能是站点风控或节点 IP 被限。"
                )
            } else if (!resp.isSuccessful) {""",
    ),
]

# ---------------------------------------------------------------- 调试预填（第 279 轮）
#
# 目的：自动化验证登录链路。
#
# 背景：`adb shell input text` 在本机输入法下会把 ASCII 转成中文候选（实测 “%40” 变成 “买了”），
# 无法可靠地往输入框里打字，导致「登录能否成功」这件事没法用脚本验证。
#
# 做法：从 X99 设置读预填值（默认为空，不影响正常用户）。
# 自动化时先写：
#   X99.putSetting("debug.login.email", "xxx@yyy.com")
#   X99.putSetting("debug.login.password", "xxx")
# 再打开登录框，两个框会自动带上这些值，点登录即可。
PREFILL_RULES = [
    (
        "Han1meApp.kt",
        """    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }""",
        """    // ★ 2026-11-25（第 279 轮）：支持调试预填（自动化验证登录用；正常用户为空）
    var email by remember { mutableStateOf(com.han1me.X99.setting("debug.login.email", "")) }
    var password by remember { mutableStateOf(com.han1me.X99.setting("debug.login.password", "")) }""",
    ),
]

# ---------------------------------------------------------------- 用户页最终形态（第 280 轮）
#
# 用户要求：「旧的登录头像还在顶部残留，保留我们的背景图片。其他的删掉。」
#
# 旧封面区 + 大头像 + 邮箱 + 登录/退出按钮 + 站点按钮 全部删除（ProfileGrid 已自带），
# 只保留「背景图（我们的特色，Viewer 没有）」+「ProfileGrid（照 Viewer 重构）」。
FINAL_USER_RULES = [
# 删除 MinePanel 死代码（第 282 轮）
    #
    # showMine 状态在全文件**没有任何 true 触发点** —— 顶栏的 onMine 传的是 null，
    # 用户页改网格后也不再走它。这个面板的功能已全部迁到：
    #   · 账号/登录      -> ProfileGrid 的资料卡 + 登录入口
    #   · 收藏/历史/清单 -> 收藏页（LibraryEntryGrid）
    #   · 设置           -> 独立设置页
    # 属于彻底的死代码。
    (
        "Han1meApp.kt",
        open(r"D:/deepseek-harness/deepseek-harness工作区/hpla实验/_临时/minepanel.txt", encoding="utf-8").read(),
        """        // ★ 2026-11-25（第 282 轮）：**MinePanel 已删除**。
        // 它是旧版「我的」面板，功能已全部迁到：
        //   账号/登录 -> 用户页 ProfileGrid 资料卡
        //   收藏/历史 -> 收藏页 LibraryEntryGrid
        //   设置      -> 独立设置页
        // showMine 全文件无 true 触发点，是彻底死代码。""",
    ),
# 收藏页顶部加「内容库入口」网格（第 281 轮，照 Viewer ContentLibraryEntry）
    (
        "Han1meApp.kt",
        """        Text("收藏", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))""",
        """        Text("收藏", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        // ★ 2026-11-25（第 281 轮）：照 Han1meViewer 的 ContentLibraryEntry，
        // 在页面顶部放一排「内容库入口」卡片。
        // Viewer 的模型：Favorite/WatchLater/Playlist 需登录，History/Download 不需要；
        // 我们保留「本站收藏」（Viewer 没有 —— 它全在账号里，我们支持本地）。
        com.han1me.ui.LibraryEntryGrid(
            entries = listOf(
                com.han1me.ui.HplayLibraryEntry(
                    "❤", "喜欢的影片", "账号里的收藏清单",
                    requiresLogin = true,
                ) { onOpen(com.han1me.ListKind.LIKES, "喜欢的影片") },
                com.han1me.ui.HplayLibraryEntry(
                    "🕘", "稍后观看", "账号里稍后看的清单",
                    requiresLogin = true,
                ) { onOpen(com.han1me.ListKind.SAVES, "稍后观看") },
                com.han1me.ui.HplayLibraryEntry(
                    "📋", "播放列表", "网页版播放列表",
                    requiresLogin = true,
                ) { onOpenWebList() },
                com.han1me.ui.HplayLibraryEntry(
                    "⏱", "观看历史", "账号里的观看纪录",
                ) { onOpen(com.han1me.ListKind.HISTORY, "观看历史") },
                com.han1me.ui.HplayLibraryEntry(
                    "⬇", "本地下载", "已下到本机的视频",
                ) { onSubs() },
            ),
            loggedIn = user != null,
        )
        Spacer(Modifier.height(14.dp))""",
    ),
# 详情页 Hero 视觉升级（第 281 轮，照 Viewer 的折叠头静止态）
    (
        "Han1meApp.kt",
        """                Box(Modifier.fillMaxWidth().height(230.dp)) {
                    com.han1me.web.HplayThumb(url = d.video.thumb, contentDescription = d.video.title, modifier = Modifier.fillMaxSize())""",
        """                // ★ 2026-11-25（第 281 轮）：照 Han1meViewer 的 VideoCollapsingHeader 静止态，
                // 封面改为圆角底边 + 更强的渐变遮罩，让下方标题层次更清楚。
                Box(
                    Modifier.fillMaxWidth().height(230.dp)
                        .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)),
                ) {
                    com.han1me.web.HplayThumb(url = d.video.thumb, contentDescription = d.video.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)""",
    ),
    # 信息条胶囊化
    (
        "Han1meApp.kt",
        """                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.video.duration.isNotBlank()) InfoChip("🕒 ${d.video.duration}")
                    Spacer(Modifier.width(8.dp))
                    if (d.video.likes.isNotBlank()) InfoChip("👍 ${d.video.likes}")
                    Spacer(Modifier.width(8.dp))
                    if (d.video.views.isNotBlank()) InfoChip("👁 ${d.video.views}")
                }""",
        """                // ★ 2026-11-25（第 281 轮）：信息条改用统一的 DetailStatRow（MD3 胶囊）。
                com.han1me.ui.DetailStatRow(
                    duration = d.video.duration,
                    likes = d.video.likes,
                    views = d.video.views,
                )""",
    ),
# 详情页 Hero 视觉升级（第 281 轮，照 Viewer 的折叠头静止态）
    (
        "Han1meApp.kt",
        """                Box(Modifier.fillMaxWidth().height(230.dp)) {
                    com.han1me.web.HplayThumb(url = d.video.thumb, contentDescription = d.video.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)""",
        """                // ★ 2026-11-25（第 281 轮）：照 Han1meViewer 的 VideoCollapsingHeader 静止态，
                // 封面改为圆角底边 + 更强的渐变遮罩，让下方标题层次更清楚。
                Box(
                    Modifier.fillMaxWidth().height(230.dp)
                        .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)),
                ) {
                    com.han1me.web.HplayThumb(url = d.video.thumb, contentDescription = d.video.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)""",
    ),
    # 信息条胶囊化
    (
        "Han1meApp.kt",
        """                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.video.duration.isNotBlank()) InfoChip("🕒 ${d.video.duration}")
                    Spacer(Modifier.width(8.dp))
                    if (d.video.likes.isNotBlank()) InfoChip("👍 ${d.video.likes}")
                    Spacer(Modifier.width(8.dp))
                    if (d.video.views.isNotBlank()) InfoChip("👁 ${d.video.views}")
                }""",
        """                // ★ 2026-11-25（第 281 轮）：信息条改用统一的 DetailStatRow（MD3 胶囊）。
                com.han1me.ui.DetailStatRow(
                    duration = d.video.duration,
                    likes = d.video.likes,
                    views = d.video.views,
                )""",
    ),
# 删除聚合面板内部的重复搜索框（第 280 轮）
    (
        "X99Board.kt",
        """        SearchRow(
            query = query,
            onQuery = { query = it },
            onSearch = { if (query.trim().isNotEmpty()) submitted = query.trim() },
            onClose = { onClose() },
        )""",
        """        // ★ 2026-11-25（第 280 轮）：**这里原本有一个重复的 SearchRow**。
        // 外面 GlobalSearchTab 已经有一个搜索框，两个框并存让用户不知道该用哪个。
        // 现在只用外部那个：query 由 externalQuery 同步进来，提交由 externalTick 触发。
        // 保留一行提示，让用户知道结果来自哪个关键词。
        if (submitted.isNotBlank()) {
            Text(
                "搜索词：" + submitted,
                color = AccentRed, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }""",
    ),
# 搜索页合并为单输入框（第 280 轮）
    #
    # 现状：GlobalSearchTab 顶部有一个 SearchRow，AggregateSearchPanel 内部又有一个。
    # 用户看到两个「想找什么…」输入框，不知道用哪个。
    #
    # 改法：把外部的 query 传给聚合面板，删掉它内部的输入框与提交按钮，
    # 只用外部那一个框 + 两个动作按钮（搜官方 / 搜板块）。
    (
        "X99Board.kt",
        """    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }""",
        """    // ★ 2026-11-25（第 280 轮）：聚合搜索不再自带输入框。
    // 外面的 GlobalSearchTab 已经有一个统一的搜索框，两个框并存会让人不知道该用哪个。
    // 现在的约定：query 由外部传入，submitted 由外部按钮触发。
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    // externalQuery：非空时以它为准（外部输入框的内容）
    if (externalQuery.isNotEmpty() && externalQuery != query) query = externalQuery
    // externalTick：外部点「搜板块」时 +1，触发一次提交
    LaunchedEffect(externalTick) {
        if (externalTick > 0 && query.isNotBlank()) submitted = query
    }""",
    ),
    (
        "X99Board.kt",
        """fun AggregateSearchPanel(
    sites: List<X99Site>,
    onOpen: (HanimeVideo) -> Unit,
    onClose: () -> Unit,
) {""",
        """fun AggregateSearchPanel(
    sites: List<X99Site>,
    onOpen: (HanimeVideo) -> Unit,
    onClose: () -> Unit,
    /** 外部搜索框的当前内容（第 280 轮：合并成单输入框）。 */
    externalQuery: String = "",
    /** 外部「搜板块」按钮的计数器，变化即触发一次提交。 */
    externalTick: Int = 0,
) {""",
    ),
# 删除死代码：旧内联设置面板整段（第 280 轮）
    (
        "Han1meApp.kt",
        open(r"D:/deepseek-harness/deepseek-harness工作区/hpla实验/_临时/oldpanel.txt", encoding="utf-8").read(),
        open(r"D:/deepseek-harness/deepseek-harness工作区/hpla实验/_临时/newpanel.txt", encoding="utf-8").read(),
    ),

# 清掉顶部残留的旧说明文案（第 280 轮）
    (
        "Han1meApp.kt",
        """            Spacer(Modifier.height(12.dp))
            Text(
                "官方源没有积分、徽章、私信与站内下载中心（实测 /points 与 /notifications 都回 404），" +
                    "所以这几格没有放——放了也是点不动的空壳。",
                color = TextFaint, fontSize = 11.sp, lineHeight = 16.sp,
            )""",
        """            // ★ 2026-11-25（第 280 轮）：旧说明文案已移除。
            // 它是给旧版「积分/徽章/私信 空壳格」做解释的，那些格子已经不存在了。
            // 保留一段空 Spacer 维持底部留白。
            Spacer(Modifier.height(12.dp))""",
    ),
    (
        "Han1meApp.kt",
        open(r"D:/deepseek-harness/deepseek-harness工作区/hpla实验/_临时/olduserblock.txt", encoding="utf-8").read(),
        open(r"D:/deepseek-harness/deepseek-harness工作区/hpla实验/_临时/newuserblock.txt", encoding="utf-8").read(),
    ),

    # WebView 登录（第 285 轮，照 Han1mePlus / Han1meViewer）
    (
        "Han1meApp.kt",
        """                onWebLogin = {
                    showLogin = false
                    // 默认调取系统外部浏览器（内置 WebView 无 DoH，登录页在污染网络下不可用）
                    runCatching {
                        com.han1me.HplayEnv.openExternal("${HanimeClient.BASE}/login")
                        toast = "已用系统浏览器打开登录页；登录后回到这里用邮箱密码登录即可"
                    }.onFailure {
                        toast = "无法调起系统浏览器：${it.message}"
                    }
                },""",
        """                onWebLogin = {
                    showLogin = false
                    // ★ 2026-11-25（第 285 轮）：改用**内置 WebView 登录页**。
                    //
                    // 原来调系统外部浏览器 —— cookie 留在外部浏览器里，App 拿不到；
                    // 而且「邮箱或密码错误」这个提示来自表单 POST 的判定逻辑，
                    // 在 CF 环境下那条链路本身就不稳（CSRF token 那步可能被盾）。
                    //
                    // 现在照搬两个参考项目的方案（Han1mePlus login_page.dart /
                    // Han1meViewer LoginScreen.kt）：WebView 登录 → 检测 DOM 标记
                    // → 提取 CookieManager 整串 cookie → 灌进 OkHttp 层。
                    runCatching {
                        com.han1me.lab.LoginActivity.onFinished = { cookies ->
                            // ★ 2026-11-25（第 322 轮）：登录成功后**立刻**用新 cookie 拉一次账户，
                            // 不再等回到主界面再由 LaunchedEffect 去抓（那条路要等节点、
                            // 还要等 Compose 重组，实测 3~6 秒才出头像）。
                            if (cookies.isNotEmpty()) {
                                toast = "登录成功，正在同步云端资料…"
                                com.han1me.lab.LoginState.beginLoadingAccount()
                                Thread({
                                    kotlinx.coroutines.runBlocking {
                                        com.han1me.lab.InstantAccountLoader
                                            .loadNow(HanimeClient.http)
                                    }
                                }, "hplay-login-refresh").apply { isDaemon = true }.start()
                            } else {
                                toast = "未取到登录态，请重试"
                            }
                        }
                        val li = android.content.Intent(
                            com.han1me.HplayEnv.requireContext(),
                            com.han1me.lab.LoginActivity::class.java,
                        )
                        li.putExtra(
                            com.han1me.lab.LoginActivity.EXTRA_URL,
                            "https://hanime1.me/login",
                        )
                        li.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        com.han1me.HplayEnv.requireContext().startActivity(li)
                    }.onFailure {
                        toast = "无法打开登录页：" + it.message
                    }
                },""",
    ),

    # 原生登录失败后自动降级到后台 WebView（第 286 轮，用户方案）
    #
    # 用户原话：「网页登陆只是兜底。可以在原生登录接口点登录后，
    #   自动调用网页登录的源码，进行登录后取它的 cookie。」
    #
    # 实现：先走原来的表单 POST；失败且不是「密码错」时，
    # 自动起隐藏 WebView 代填代交。用户只输一次。
    (
        "Han1meApp.kt",
        """                        } catch (t: Throwable) {
                            err = t.message ?: t.toString()
                        } finally {
                            busy = false
                        }""",
        """                        } catch (t: Throwable) {
                            // ★ 2026-11-25（第 286 轮）：**表单 POST 失败 → 自动降级到后台 WebView**。
                            //
                            // 用户提出的方案：原生登录点下去后，自动用网页登录的源码去登录并取 cookie。
                            // 这样用户只输一次账号密码，而 CF 环境下的兼容性由 WebView 保证。
                            //
                            // 只在「非密码错误」时降级 —— 密码真错了再去网页试也是白试，
                            // 直接报错更快（用户不至于等 90 秒超时）。
                            val msg = t.message ?: t.toString()
                            val maybePasswordWrong = msg.contains("邮箱或密码错误");
                            if (!maybePasswordWrong) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    err = "正在用备用方式登录…"
                                    com.han1me.lab.HeadlessLogin.start(
                                        com.han1me.HplayEnv.requireContext(),
                                        email.trim(), password,
                                    ) { ck, m ->
                                        if (ck.isNotEmpty()) {
                                            if (email.isNotBlank()) com.han1me.X99.putSetting("account.email", email.trim())
                                            onLoggedIn("已登录")
                                        } else {
                                            err = m
                                        }
                                        busy = false
                                    }
                                }
                                return@launch
                            }
                            err = msg
                        }
                        busy = false
                        if (false) {
                        }""",
    ),
]

RULES = COLOR_RULES + LEGACY_PANEL_RULES + USER_PAGE_RULES + LOGIN_RULES + PREFILL_RULES + FINAL_USER_RULES


def main():
    if not GEN.exists():
        raise SystemExit("build/gen 不存在 —— 先跑 build.py 的第 1 步")

    total = 0
    touched = {}
    for fname, old, new in RULES:
        f = GEN / fname
        if not f.exists():
            raise SystemExit("规则指向的文件不存在：%s" % fname)
        text = io.open(f, encoding="utf-8").read()
        n = text.count(old)
        if n == 0:
            # 允许「已经替换过」的情况：new 已经在了也算通过（幂等）
            if new in text:
                continue
            raise SystemExit(
                "全局规则失配：%s 里找不到 %r（也没有 %r）" % (fname, old, new)
            )
        text = text.replace(old, new)
        io.open(f, "w", encoding="utf-8").write(text)
        touched[fname] = touched.get(fname, 0) + n
        total += n

    if total:
        for k, v in touched.items():
            print("  %s: 全局替换 %d 处" % (k, v))
    else:
        print("  无全局替换（规则未命中或已应用）")


if __name__ == "__main__":
    main()
