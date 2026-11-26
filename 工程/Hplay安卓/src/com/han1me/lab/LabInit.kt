package com.han1me.lab

import android.content.Context
import java.io.File

/**
 * **实验功能初始化**（实验分叉 · 第 2 版）。
 *
 * ★★ 第 2 版修「开启内置节点后退出再进 App 卡死」（用户报的严重问题）：
 *
 * 根因：第 1 版在 onCreate 里**同步调 NodeBridge.apply()**，而它会：
 *  1. 等 xray 端口就绪（最多 **15 秒**）；
 *  2. 期间占着**主线程**（onCreate 是主线程）；
 *  3. 于是 App 启动直接 ANR —— 用户看到的就是「卡死进不去」。
 *
 * 修法：
 *  · initOnce **只做毫秒级的事**（挂设置文件），绝不做网络/进程操作；
 *  · 若要恢复内置节点，**丢到后台线程**，主线程立刻放行；
 *  · 后台线程里再加**启动失败兜底**（失败就回落边缘节点，不阻塞界面）。
 */
object LabInit {

    @Volatile
    private var done = false

    @Volatile
    private var restoring = false

    /**
     * **只允许做毫秒级操作**（挂设置文件），不得阻塞主线程。
     *
     * 由 MainActivity.onCreate 调用 —— 那里是主线程，任何耗时操作都会 ANR。
     */
    fun initOnce(ctx: Context, dataDir: File) {
        if (done) return
        synchronized(this) {
            if (done) return
            runCatching { LabLog.attach(ctx) }
            LabLog.i("init", "LabInit.initOnce 进入")
            runCatching { MainWatchdog.start() }
            runCatching { NodeProbe.start() }
            runCatching { NodeStore.attach(dataDir) }
            // 第 148 轮：确保「内置默认节点」这条固定订阅源存在（不可删除）
            runCatching { NodeStore.ensureFixedSub() }
            LabLog.i("init", "dataDir=" + dataDir.absolutePath + " enabled=" + NodeStore.enabled())
            done = true
        }
        // 需要恢复内置节点时：**后台线程**做，主线程立即返回
        if (NodeStore.enabled() && !restoring) {
            LabLog.i("init", "内置节点已启用 -> 后台恢复")
            restoring = true
            Thread({
                NodeBridge.applyAsync(ctx)
                restoring = false
            }, "hpla-lab-node-restore").apply { isDaemon = true }.start()
        }
        // 第 232 轮（用户批准方案 B）：双向同步巡检 —— 引擎存活与 GlobalProxy 挂载状态对齐。
        // 纯应用内：只用 NodeStore/XrayRunner/GlobalProxy，不触碰任何系统网络设置。
        runCatching { NodeBridge.startSyncLoop(ctx) }
        // ★★★ 2026-11-24（CF 测试模式）：**启动时就起本地转发代理**（固定端口 18080）。
        //
        // 为什么必须在启动时起、而不是等验证页：系统代理里写的是具体端口，
        // 若端口在验证页才出现，那在它出现之前系统代理指向死端口 → 全 App 网页白屏。
        // 现在固定 18080、进程序列化启动，adb 设一次系统代理就长期有效：
        //   adb shell settings put global http_proxy 127.0.0.1:18080
        //   adb shell settings put global http_proxy :0    # 不用时清除
        runCatching { CfForward.ensureStarted() }
        // ★ 2026-11-25：回灌上次的 cf_clearance（否则每次启动都要重新过盾，
        //   而「首页能看、登录 403」正是 clearance 过期的表现）
        runCatching { CfCookieBridge.restoreAtStartup() }
        // ★ 2026-11-25（第 297 轮）：**冷启动恢复登录态**。
        // 用户要求「一次登陆成功后永久保存 cookie，之后不用再次网页登录」。
        // 落盘在 CfCookieBridge.persistLogin，这里灌回 OkHttp。
        runCatching { CfCookieBridge.restoreLoginAtStartup() }
        // ★★★ 2026-11-25（第 322 轮 · 用户要求「cf 过一次全部都不用重新验证」）：
        //
        // 这里不再需要"等代理就绪再抓头像"——头像 URL 上次抓过就落盘了，
        // 启动瞬间先用磁盘缓存显示（[CachedAccount.warmFromDisk]），
        // 网络刷新在下面那条线程里进行，失败也不影响界面。
        runCatching { CachedAccount.warmFromDisk() }
        // ★ 2026-11-25（第 308 轮）：**启动时抓一次云端账户**。
        // 放在 LabInit 而不是 Compose 的 LaunchedEffect ——
        // 后者依赖组合树与 LoginState 的时序，实测不稳定。
        // 这里在 App 初始化时无条件跑一次（有 cookie 才有意义）。
        Thread({
            runCatching {
                // ★★★ 2026-11-25（第 308 轮 · 真机抓到 DNS 污染）：
                // 首次失败日志是 "failed to connect to hanime1.me/31.13.85.169"
                // —— 31.13.85.169 是 Facebook 的 IP，说明 DNS 被污染了。
                // 原因是 LabInit 跑得比 GlobalProxy（SOCKS 隧道）早，
                // 那一刻 DoH/代理还没就绪，走了系统 DNS。
                // 这里等隧道起来再抓。
                var waited = 0
                while (waited < 30_000 && !com.han1me.lab.GlobalProxy.active) {
                    Thread.sleep(500)
                    waited += 500
                }
                LabLog.i("account", "代理就绪等待 " + waited + "ms")
                // ★★★ 2026-11-25（第 322 轮 · 真机实测后的启动优化）：
                //
                // 事实：cf_clearance 除了 UA 还绑定 **TLS 指纹（JA3）**。
                // WebView（Chromium/BoringSSL）能过；OkHttp（Java/Conscrypt）过不了 ——
                // 无论 cookie 怎么带都是 403。这是协议层差异，不是 cookie 传递问题。
                //
                // 所以启动时只要磁盘上有 clearance，就**直接进入冷却**：
                // 第一条请求立刻拿到 403，上层走 WebView 分支（头像 1 秒抓好），
                // 完全不会出现「静默挑战 2 秒 + 可见验证窗闪一下」。
                // 冷却 3 分钟后自动解除，那时若 CF 放宽了照样能过。
                if (CfCookieBridge.hasPersisted()) {
                    runCatching { HplayCfHolder.markCleared("https://hanime1.me/") }
                    runCatching { HplayCfHolder.startCooldown("hanime1.me") }
                }
                if (CfCookieBridge.savedLogin().isNotBlank()) {
                    // ★ 第 322 轮：磁盘上已经有头像就**不再联网**（启动零等待）。
                    //   只有第一次（或缓存被清）才走 OkHttp + WebView 那条链。
                    if (CachedAccount.avatarUrl.isNotBlank()) {
                        LabLog.i("account", "已有磁盘缓存的头像，跳过启动抓取")
                        return@runCatching
                    }
                    val info = CloudAccount.fetch(com.javrocket.hanime.HanimeClient.http)
                    if (info != null) {
                        CachedAccount.set(info)
                        LabLog.i(
                            "account",
                            "启动抓取成功 name=" + info.userName +
                                " avatar=" + (if (info.avatarUrl.isBlank()) "无" else "有"),
                        )
                    }
                }
            }
        }, "hplay-account-boot").apply { isDaemon = true }.start()
        // ★ 2026-11-25（第 304 轮）：**砍掉自动订阅拉取**（用户要求）。
        // 每次启动拉所有订阅源会把用户手动设的节点冲掉，且启动变慢。
        // 需要时在「设置 > 网络 > 订阅」里手动拉。
        // ★ 第 159 轮：启动后后台自检这 6 个新板块能不能解析出卡片（见 probeBoards 注释）。
        // 默认**关闭** —— 它每次启动会发 6~13 条请求，而内置节点是有流量套餐的，
        // 不该为了诊断白耗用户流量。需要排查时用 adb 打开开关：
        //   X99.putSetting("x99.probe", "true")
        // 第 159 轮起默认**开启**：这是排查板块问题的唯一可靠手段（不依赖 UI 点击）。
        // 节点流量无限（用户 2026-11-02 确认），每次启动多几条请求可以接受。
        // ★ 2026-11-24（CF 测试模式）：启动自检也关闭 —— 它每次启动发 6~13 条请求，
        //   会污染 CF 测试的观测（日志里混入大量无关请求，难以判断哪条是主页的）。
        //   CF 测试通过后改回 "true" 即恢复。
        // ★ 2026-11-25（第 304 轮）：**砍掉启动自检**（用户要求「砍掉自动刷新」）。
        if (false) {
            runCatching { probeBoards() }
        }
        // ★ 第 168 轮：**进程退出前兜底还原系统代理**。
        // root 增强会用 `settings put global http_proxy` 让整个系统走节点；
        // 若 App 被杀/退出时没还原，系统代理会残留 —— 那会**影响其它 App 上网**，
        // 属于「损害系统」的范畴，所以这里加一道 ShutdownHook 兜底。
        // 拿不到 root 时 clearSystemProxy() 内部直接 return，零副作用。
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread({
                runCatching { com.han1me.lab.HplayRootBoost.clearSystemProxy() }
            }, "hpla-root-proxy-restore"))
        }
    }

    /**
     * ★★★ 第 159 轮诊断：启动后后台探测内置新板块的**可解析性**，结论直接进日志。
     *
     * 为什么放启动时：排查「6 个新板块全都转圈」时最难受的是**必须靠 UI 导航**才能触发一次
     * 板块加载（adb 点击又经常错位）。放到启动时自动跑一次，重启 App 就能拿到
     * 「每个站 × 每条线路 => 解析出几张卡」的完整结论，**完全不依赖界面**。
     *
     * 探测完只打日志、不改任何状态；失败也只是记一行，不影响正常启动。
     */
    private fun probeBoards() {
        Thread({
            runCatching {
                // ★★★ 第 159 轮真机定位到的**真因**：
                //
                // 实测时序（重启日志）：
                //   00:41:19.440 [x99] 站点目录共 14 个…   ← 探测开始发请求
                //   00:41:19.785 [bridge] GlobalProxy 已挂  ← 代理 345ms 后才挂上
                //
                // 于是**第一个请求跑在代理挂载之前 → 走了直连**。而这些站直连根本不通，
                // 只能干等超时 —— 真机实测第一条探测结果比节点就绪晚了 **4 分 06 秒**
                // （后续每条约 0.5 秒）。用户看到的「打开板块一直转圈」就是这 4 分钟。
                //
                // 修法：发请求前先等 GlobalProxy 挂上（最多 30 秒）。
                // 这同时说明一件事：**任何在代理就绪前发出的 X99 请求都会白等**，
                // 所以这个等待对真实用户路径同样有意义。
                var waited = 0
                while (!GlobalProxy.active && waited < 30_000) {
                    Thread.sleep(200); waited += 200
                }
                LabLog.i("x99", "等待代理 " + waited + "ms，active=" + GlobalProxy.active)
                val sites = com.han1me.X99.otherSites()
                LabLog.i("x99", "站点目录共 " + sites.size + " 个；前 40 名=" +
                    sites.take(40).joinToString("/") { it.name })
                // ★ 第 172 轮（用户报「某些板块获取不了内容」）：
                // 原名单写死 6 个旧站名，**新加的 jptt / aplovesoon / x站精选 从没被探测过**——
                // 日志里根本看不到它们，自然也无从发现它们的问题。
                // 改成**全部内置板块都探**（不再维护一份会过期的名单）。
                val targets = setOf(
                    "气泡视频", "jptt", "麻豆传媒", "madaoav", "aplovesoon", "x站精选",
                    "青青草视频", "99热", "视色", "xChina 发布页",
                )
                for (s in sites) {
                    if (s.name !in targets) continue
                    val cards = runCatching {
                        com.han1me.X99.boardListing(s.name, s.lines.first().url, null, 1)
                    }.getOrDefault(emptyList())
                    LabLog.i("x99", "列表 " + s.name + " => " + cards.size + " 卡" +
                        (if (cards.isNotEmpty()) " 首=" + cards[0].title.take(26) else ""))
                    if (cards.isEmpty()) {
                        // ★ 第 172 轮：列表为空时**转储页面锚点形态**，一次定位「为什么解析不出卡片」。
                        // 99热 就是典型：HTTP 200 / 56KB 拿到了，但卡片 0 —— 说明它的锚点
                        // 不在通用正则的覆盖范围内，光看「0 卡」永远查不出原因。
                        runCatching {
                            val html = com.han1me.X99.getHtml(s.lines.first().url)
                            val hrefs = Regex("""href=["']([^'"]+)["']""")
                                .findAll(html).map { it.groupValues[1] }.toList()
                            val shapes = hrefs.map { Regex("""\d+""").replace(it, "N").take(30) }
                                .groupingBy { it }.eachCount().entries
                                .sortedByDescending { it.value }.take(8)
                                .joinToString(" | ") { it.key + "×" + it.value }
                            LabLog.i("x99", "空列表Html " + s.name + " len=" + html.length +
                                " href数=" + hrefs.size + " 形态: " + shapes.take(260))
                            // 第 175 轮：把**包含 /videos/ 的那段真实 HTML 原样打出来**。
                            // 只打形态看不到结构，无法判断是「标签内文字太长」还是「嵌套了别的 <a>」。
                            val at = html.indexOf("/videos/")
                            if (at > 0) {
                                val seg = html.substring(maxOf(0, at - 260), minOf(html.length, at + 420))
                                LabLog.i("x99", "锚点原文 " + s.name + " >>> " +
                                    seg.replace(Regex("""\s+"""), " ").take(640))
                            }
                        }
                        continue
                    }
                    runCatching {
                        val v = cards[0]
                        // ★ 第 186 轮：**直接调 boardDetail**，这才是 App 真实点击时走的那条路。
                        // 之前这里只 getHtml 抓页面看特征，测不出「原生解析器到底通不通」——
                        // 用户反馈「aplovesoon 明明页面里有 manifest.m3u8 却没取到」就是这个盲区。
                        val native = runCatching { com.han1me.X99.boardDetail(v) }.getOrNull()
                        LabLog.i("x99", "原生 " + s.name + " => " +
                            if (native == null) "null（回落网页版）"
                            else native.sources.size.toString() + " 条播址 " +
                                native.sources.take(2).joinToString(" ; ") { it.first.toString() + "p=" + it.second.take(90) })
                        val html = com.han1me.X99.getHtml(v.url)
                        LabLog.i("x99", "详情 " + s.name + " url=" + v.url.take(56) +
                            " len=" + html.length)
                        val pats = listOf(
                            "m3u8" to Regex("""https?://[^"\x27\s<>]+\.m3u8[^"\x27\s<>]*"""),
                            "mp4" to Regex("""https?://[^"\x27\s<>]+\.mp4[^"\x27\s<>]*"""),
                            "videoTag" to Regex("""<video[^>]*src=["\x27]([^"\x27]+)["\x27]"""),
                            "sourceTag" to Regex("""<source[^>]*src=["\x27]([^\x27]+)["\x27]"""),
                            "iframe" to Regex("""<iframe[^>]*src=["\x27]([^\x27]+)["\x27]"""),
                        )
                        for ((n0, re) in pats) {
                            val f0 = re.find(html)?.value?.take(120).orEmpty()
                            if (f0.isNotEmpty()) LabLog.i("x99", "播址 " + s.name + " [" + n0 + "] " + f0)
                        }
                        if (pats.none { it.second.containsMatchIn(html) }) {
                            val kw = Regex("""(?i)(player|playlist|hls|stream|video_?url)["\x27]?\s*[:=]""")
                            val hit = kw.find(html)
                            if (hit != null) {
                                val a0 = maxOf(0, hit.range.first - 60)
                                LabLog.i("x99", "播址 " + s.name + " [片段] " +
                                    html.substring(a0, minOf(html.length, a0 + 260))
                                        .replace(Regex("""\s+"""), " "))
                            } else {
                                LabLog.i("x99", "播址 " + s.name + " 未找到播放线索")
                            }
                        }
                    }.onFailure {
                        LabLog.i("x99", "详情 " + s.name + " => 失败 " + (it.message ?: "").take(90))
                    }
                }
                // 第 216 轮：搜索链路实测（8xx6 / madaoav / 青青草三站搜索入口均实测可用）
                runCatching {
                    for (sn in listOf("麻豆传媒", "madaoav", "青青草视频")) {
                        val st = sites.firstOrNull { it.name == sn } ?: continue
                        val cards = com.han1me.X99.boardSearchQuick(sn, st.lines.first().url, "按摩")
                        LabLog.i("x99", "搜索 " + sn + " => " + cards.size + " 卡" +
                            (if (cards.isNotEmpty()) " 首=" + cards[0].title.take(24) else ""))
                    }
                }.onFailure { LabLog.i("x99", "搜索探测失败 " + (it.message ?: it.javaClass.simpleName)) }
                LabLog.i("x99", "板块探测完成")
            }.onFailure { LabLog.i("x99", "板块探测整体失败 " + (it.message ?: "")) }
        }, "hpla-probe-boards").apply { isDaemon = true }.start()
    }
}