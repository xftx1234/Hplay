package com.han1me.download

import com.han1me.HplayEnv
import com.han1me.X99
import com.javrocket.hanime.HanimeClient
import com.javrocket.hanime.HanimeDetail
import com.javrocket.hanime.HanimeVideo
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 安卓版本地下载器。
 *
 * ## 第 46 轮修的四个 bug（用户真机反馈）
 *
 * **① 下下来的视频只有几秒。** 根因不是「少下了分片」，而是**把诱饵清单当成了真清单**：
 * 站点在「不带 Referer」或「Referer 不对」时回的是一份**结构完全合法、只有 1 段 10 秒**
 * 的 m3u8（实测 Jable / LissAV 那一族：`#EXTM3U` + `#EXTINF:10.000000,` +
 * `player.centercdn.top/tip.ts` + `#EXT-X-ENDLIST`，143 字节）。它 `#EXTM3U` 有、
 * `#EXT-X-ENDLIST` 有、`#EXTINF` 也有，**拿「像不像清单」去判是判不出来的**；
 * 上一版只有 `X99.getPlaylist` 那条路挡了 `tip.ts`，而下载器自己直接吃 `fetchText`
 * 的正文，于是把这份 10 秒的诱饵**完整、成功地**下到了本地 —— 界面显示「已完成」，
 * 播出来只有几秒。
 * 修法：下载器与 `X99.getPlaylist` **用同一条判据**（见 playlistLooksReal）：
 * 含 `tip.ts` 直接判假；没有 `#EXT-X-STREAM-INF` 时**必须 >2 段**才算数。
 * 判假就抛错，**绝不写一个「看起来下好了」的短文件**。
 *
 * **② 删除要删好几次才干净。** 根因是 `File.deleteRecursively()` 遇到正在被写入/被占用
 * 的文件会**删一半就返回 false**，而上一版把返回值丢掉了，界面却照旧重扫磁盘 ——
 * 于是用户看到「点一次只少一点点」，得连点。
 * 修法：deleteTree 自己递归 + **逐项重试** + 删完**回验**（目录必须真的不存在），
 * 并把失败原因回给界面。删除前先把正在写这一条的任务停下来。
 *
 * **③ 本地下载页没还原参考 UI。** 参考图是「下载」标题 + 五个图标 +
 * **正在下载 / 已下载** 两个页签 + **「未分组 · N 部影片」分组头** + 卡片（封面缩略图 +
 * 标题 + 时间 + **质量/体积胶囊**）+ 底排三键 **删除 / 外部播放器 / 本地观看**。
 * 上一版只有两个 pill 与「播放 / ✕」，封面只有 46x30 的小方块。修法见 HplayDownloadsUi.kt。
 *
 * **④ 下载速度要能提上去，可选内置多线程。** 上一版 HLS 分片是**纯串行**一个个下，
 * 每个分片一次完整 RTT，速度被「延迟 x 段数」卡死。修法：分片并发（默认 4，界面可调 1-8），
 * 用固定线程池；进度改用原子计数，并保留**媒体秒数**这个真分母
 * （并发下按「已完成段的时长 + 在途段的已读比例」累计）。
 *
 * ## 与桌面版 `X99.downloadMedia` 的关系
 * **不是**同一个实现。这里另起一个自包含的下载器，只借三样已验过的东西：
 *  ① `HanimeClient.http` + `X99.boardDnsOver` —— 与内嵌播放器**同一条**网络栈
 *    （DoH / 边缘节点 / 代理 / 板块 DoH 全继承）。能下下来的，播放器就一定播得动；
 *  ② `X99.refererFor(url, pageUrl)` —— 板块媒体主机的 Referer 规则（唯一事实源，不重抄一张表）；
 *     **第 111 轮**：下载器把自己的详情页当 pageUrl 传进去 —— 原来只传 url，函数只能读进程级的
 *     lastDetailUrl（会被「最近打开的那条详情」改写），于是后台下载任务会被借走别人的 Referer，分片回 403。
 *  ③ `X99.setting/putSetting` —— 与其它设置同一个 properties 文件。
 *
 * 线程模型：任务对象是**进程级**的（关掉下载页进度照跑），一个守护线程按队列**串行执行任务**，
 * 但**单个任务内部的分片是并发的** —— 串行任务是刻意的：这些站全部前置频控，
 * 多个任务同时抢带宽既容易被风控，也让每个任务的剩余时间估不准。
 * 进度用 MutableStateFlow 发布（线程安全，Compose 侧 collectAsState() 直接收）。
 */

enum class DlStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

/** 一条任务在界面上的全部可见状态（不可变快照，UI 只读它）。 */
data class DlTask(
    val id: Long,
    val title: String,
    val quality: Int,
    val status: DlStatus,
    /** 阶段文字：排队中 / 取清单 / 下载中 / 已完成 等。 */
    val phase: String,
    val doneBytes: Long,
    /** -1 = 未知。HLS 的总字节要下完才知道，这里不编一个数出来。 */
    val totalBytes: Long,
    /** 已下/已落的**媒体秒数**（HLS 专用：给进度条一个真实分母，而不是假百分比）。 */
    val mediaDoneSec: Double,
    val mediaTotalSec: Double,
    /** 字节/秒；0 = 还没测出来。 */
    val speedBps: Double,
    /** 剩余秒数；-1 = 估不出来。 */
    val etaSec: Long,
    val dest: String,
    val error: String,
    /** 已完成的段数 / 总段数（并发下载时用户能看到「下到第几片」）。 */
    val segDone: Int = 0,
    val segTotal: Int = 0,
    /** 这条任务能不能续传（HLS 分片天然可以；单文件要服务端支持 Range 才行）。 */
    val resumable: Boolean = true,
    /** 重试了几次（界面如实显示，不藏"它已经在重试"这件事）。 */
    val retries: Int = 0,
    /**
     * **分组名 = 板块名**（第 60 轮）。
     * 用户要求「各个板块下载的视频单独分组，分组名取自板块名」。
     * 下载时由界面把 X99.boardLabelFor(url) 传进来，空串按「未分组」显示。
     */
    val group: String = "",
) {
    val fraction: Float
        get() = when {
            status == DlStatus.DONE -> 1f
            totalBytes > 0 -> (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            mediaTotalSec > 0 -> (mediaDoneSec / mediaTotalSec).toFloat().coerceIn(0f, 1f)
            else -> 0f
        }

    /** 进度条有没有真实分母；没有就画不确定态，不假装百分比。 */
    val determinate: Boolean
        get() = status == DlStatus.DONE || totalBytes > 0 || mediaTotalSec > 0

    /** 「12.40 MB/s」—— 用户点名要的单位就是 MB/s。 */
    val speedText: String get() = if (speedBps > 0) "%.2f MB/s".format(speedBps / 1048576.0) else "— MB/s"

    val etaText: String
        get() = when {
            status == DlStatus.DONE -> "已完成"
            status == DlStatus.QUEUED -> "等待中"
            status == DlStatus.PAUSED -> "已暂停"
            etaSec < 0 -> "剩余 --:--"
            else -> "剩余 %02d:%02d".format(etaSec / 60, etaSec % 60)
        }

    val sizeText: String
        get() = if (totalBytes > 0) "%s / %s".format(human(doneBytes), human(totalBytes)) else human(doneBytes)

    /** 「第 12/203 片」；没有分段信息（直下 mp4）就是空串。 */
    val segText: String get() = if (segTotal > 0) "第 $segDone/$segTotal 片" else ""
}

/** 已下载条目（磁盘扫描结果）。 */
data class DlItem(
    /** 可直接交给播放器的路径（`…/index.m3u8` 或 `….mp4`）。 */
    val path: String,
    val title: String,
    val bytes: Long,
    val at: Long,
    /** HLS 整包（清单 + 分片）在一个目录里；删除要连目录一起删。 */
    val isDir: Boolean,
    val hasDetail: Boolean,
    /** 下载时一并缓存的封面（`file://` 绝对地址）；没缓存到就是空串。 */
    val cover: String,
    /** 分组名（参考 UI 的「未分组」）。sidecar 里没写就是「未分组」。 */
    val group: String = "未分组",
    /** 下载时记录的质量档（720 / 1080 …）；0 = 未知。 */
    val quality: Int = 0,
    /** 时长文字（sidecar 里的 duration）；空串 = 没有。 */
    val duration: String = "",
    /**
     * **第 258 轮**：sidecar 里的源页面 URL（@@video.url@@），扫描时随条目一次读出。
     * @@isDownloaded@@ 用它判"已下载"，不必再对每个条目重读 sidecar JSON。
     * 没有介绍页缓存就是空串。
     */
    val srcUrl: String = "",
    /**
     * **第 258 轮**：这一条对应的**磁盘锚点**（扫描时的根子项）：
     * 目录条目 = 那个目录本身；散落文件条目 = 文件本身。
     * @@path@@ 指向的是 playable（可能是锚点内部的 index.m3u8），不一定是锚点。
     * 快照增量校准按锚点比对指纹。
     */
    val anchorPath: String = "",
    /**
     * **条目归并键**（第 54 轮修「同一部片子显示 4 条」）。
     *
     * 磁盘上同一部片子可能留下多份产物（多次下载、合并前后的残留、不同时间戳的同名文件）。
     * 以前列表**逐个文件/目录各列一条**，于是截图里「糖心Vlog」重复出现 4 次，每条都是 307.7 MB。
     * 这个键取「去掉时间戳后缀的标题」，同键只保留一条（见 [scanItems] 的归并）。
     */
    val key: String = "",
)

/** 保存目录候选。 */
data class DirOption(val label: String, val dir: File, val note: String)

private fun human(n: Long): String = when {
    n >= 1073741824L -> "%.2f GB".format(n / 1073741824.0)
    n >= 1048576L -> "%.1f MB".format(n / 1048576.0)
    n >= 1024L -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

private class Cancelled : RuntimeException("已取消")

/** 每个任务的可变运行计数。**分片并发**下会被多个线程写，所以计数一律原子。 */
private class Prog {
    val done = AtomicLong(0)
    @Volatile var total = -1L
    /** 已完成段的媒体毫秒（避免用 Double 做原子累加）。 */
    val segDoneMillis = AtomicLong(0)
    /** 在途段的「已读比例 x 段时长(ms)」，键 = 段序号。并发下用它把进度条推得连续。 */
    val inflight = ConcurrentHashMap<Int, Double>()
    @Volatile var mediaTotal = 0.0
    @Volatile var bps = 0.0
    @Volatile var eta = -1L
    @Volatile var startedAt = 0L
    /** 第 51 轮：分片计数改成原子（多个分片线程同时自增）。 */
    val segDone = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var segTotal = 0

    private var spdAt = -1L
    private var spdBytes = 0L

    /** 媒体秒数进度：已完成段 + 在途段的已读比例。并发安全。 */
    val mediaDone: Double
        get() = (segDoneMillis.get() + inflight.values.sum()) / 1000.0

    /** 瞬时速率（约 0.5 s 窗口）。第一次采样返回 0：不编一个数出来。 */
    @Synchronized
    fun speed(now: Long, bytes: Long): Double {
        if (spdAt < 0) { spdAt = now; spdBytes = bytes; return bps }
        val dt = now - spdAt
        if (dt < 500) return bps
        val v = (bytes - spdBytes).toDouble() / (dt / 1000.0)
        spdAt = now
        spdBytes = bytes
        if (v > 0) bps = v
        return bps
    }

    /** 总量已知时按字节估；否则 -1。 */
    fun bytesEta(): Long =
        if (total > 0 && bps > 0) ((total - done.get()) / bps).toLong().coerceAtLeast(0L) else -1L

    /**
     * 总量未知（HLS）时按**媒体秒数**推进度估剩余：
     * 已下 mediaDone 媒体秒用了 elapsed 秒，按同一速率推剩下的 mediaTotal - mediaDone 秒。
     */
    fun mediaEta(now: Long): Long {
        val md = mediaDone
        return if (mediaTotal > 0 && md > 0.4) {
            val elapsed = (now - startedAt) / 1000.0
            (elapsed * (mediaTotal - md) / md).toLong().coerceAtLeast(0L)
        } else -1L
    }
}

object HplayDownloads {

    private const val KEY_DIR = "download.dir"
    private const val KEY_CONC = "download.concurrency"
    private const val SIDE_DETAIL = "hplay.detail.json"
    private const val SIDE_COVER = "hplay.cover.jpg"
    private const val SUFFIX_DETAIL = ".detail.json"
    private const val SUFFIX_COVER = ".cover.jpg"

    /**
     * 分片并发默认值。
     *
     * 第 57 轮为了缓解 429 曾下调到 3，但用户随即要求「不妨碍下载的情况下最大速度」——
     * **永久降并发是在用速度换平安，代价一直付**。第 58 轮改成：
     * **默认回到 4（速度优先），把 429 处理做细**：
     *  · 单段遇 429 时先按秒级退避重试（第 53 轮就有）；
     *  · 新增 [throttledUntil]：某个域刚被限流过，就对该域**临时**降速一小段时间，
     *    而不是把全局并发永久调低。风控过去后自动恢复满速。
     *
     * 这样「平时满速、被限流时自动让一让」，比一刀切降并发更符合用户诉求。
     */
    // ★★ LABDL（2026-10-31）：默认分片并发 8 → 12。
    //
    // 依据：内置节点下每个分片都走同一条 SOCKS 隧道，连接复用已由 mediaHttp 放宽到 48 池，
    // 实测增加并发能继续吃满带宽（8 时常见「总进度卡在 90% 等最后几片」）。
    // 仍保留「连续失败自动降级」的保护（见下方注释），所以对慢站不会更糟。
    // ★★ LABPERF2：有了内置节点后带宽不再是瓶颈，默认并发 12 → 16（连接池已放大）
    private const val CONC_DEFAULT = 16

    /** 默认分片并发（UI 显示用；内部仍走 private 的 CONC_DEFAULT）。 */
    val concDefault: Int get() = CONC_DEFAULT

    /**
     * 分片并发上限（界面选项与设置校验共用）。
     *
     * ★★★ 2026-11-26（第 329 轮 · 用户要求「继续糖心提速」）：**保持 16，不要提**。
     *
     * 历史实测（工作报告 v1.2.5，糖心同一批分片）：
     * | 并发 | 1 | 4 | 8 | 12 | 16 | 20 | 24 | 32 |
     * |---|---|---|---|---|---|---|---|---|
     * | MB/s | 0.33 | 1.40 | 2.50 | 3.41 | **3.62~4.19** | 3.87~5.18 | 4.35~4.53 | **1.13（崩）** |
     *
     * 拐点在 16，32 会崩到 1.13 MB/s。所以上限就钉在 16 ——
     * 不给用户调到会崩的区间。真正的提速手段是**强制 HTTP/1.1**（见 mediaHttp）。
     */
    const val MAX_CONCURRENCY = 16

    /**
     * **按域记忆最佳分段数**（第 133 轮，照 1DM 的 domain_part_limit 表设计）。
     *
     * 1DM 在 SQLite 里专门有一张 domain_part_limit(domain, part_limit, use_for_subdomain)，
     * 把「这个域名该拆几段」当成**可学习的持久参数**，而不是全局常数。
     * 我们照做：首次用 CONC_DEFAULT，成功则沿用；连续失败就降一级（8->6->4），
     * 记住结果供该域后续任务复用。只存进程内（重启回到默认），避免把一次坏样本永久写死。
     */
    private val segLimitByHost = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val segFailByHost = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * ★★★ 第 120 轮（用户报「肉视频点一次要转好久 / 加载过慢」）：**实测驱动**的并发调整。
     *
     * 真机同一条视频（rou07.net，63 个分片、每片 1.2~1.6 MB、共约 74 MB）实测：
     *
     * | 方式 | 8 个分片 | 全片（63 片） |
     * | --- | --- | --- |
     * | 串行 | 26.90s | 约 3.5 分钟 |
     * | **8 路并发** | **3.33s** | **约 0.4 分钟** |
     *
     * ⇒ **并发是 8.1 倍提速**，这是这个板块「转好久」的**根本原因**。
     *
     * 为什么原来只给 4：第 57 轮为缓解 429 下调过，第 58 轮回调 4。
     * 但那时没有「域级限流冷却」（throttledUntil）这个机制 —— 现在有了：
     * **平时跑满 8 路，某个域回 429 时才对该域临时降速，冷却过去自动恢复**。
     * 所以提高默认并发**不会**换来更高的风控风险。
     *
     * 上限同步从 8 提到 16：给慢线路留出更多在途请求（单条分片 2.6~3.0s，
     * 8 路时通路利用率已经很高，16 路对超长视频收益更明显）。
     */

    /**
     * **域级限流冷却**（第 58 轮）：某个域名刚回过 429 就记下"在此之前降速"。
     * 目的是"被限流时自动让一让"，而不是把全局并发永久调低。
     */
    private val throttledUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 这个域现在是否处于限流冷却中。 */
    private fun hostThrottled(url: String): Boolean = runCatching {
        val h = java.net.URI(url).host ?: return@runCatching false
        (throttledUntil[h] ?: 0L) > System.currentTimeMillis()
    }.getOrDefault(false)

    /**
     * **同时下载的任务数上限**（第 49 轮，用户要求"最大可同时下载五个视频"）。
     * 与"单个任务内的分片并发"是两件事：这里是**任务级**并发。
     */
    const val MAX_PARALLEL_TASKS = 5

    /** [enqueue] 的返回值：这一部已经下载过了，没有新建任务（第 54 轮）。 */
    const val ALREADY_DONE = -1L

    /** [enqueue] 的返回值：已有同样的任务在队列里，返回的是它的 id（正数，不重复建）。 */

    /** 单个分片/文件的下载失败重试次数（第 49 轮修「偶发 unexpected end of stream」）。 */
    // ★★ LABDL：经内置节点时单段偶发超时比直连多（多一跳），重试 3 → 4。
    private const val SEG_TRIES = 4

    /**
     * **连接级失败**的重试次数（第 58 轮）。
     * 息屏恢复后网络栈需要时间热起来，头几次连不上是常态；给足预算而不是 3 次就认输。
     */
    private const val CONNECT_TRIES = 8

    /** 低速检测窗口（第 57 轮）：每这么久结算一次平均速率。 */
    private const val STALL_WINDOW_MS = 15_000L

    /**
     * 低于这个速率就判定"这条连接废了"，主动断开重连（第 57 轮）。
     * 30 KB/s 是个保守门槛：正常分片下载远高于它，
     * 而截图里那种 0.01 MB/s（≈10 KB/s）会稳稳落进来。
     */
    private const val MIN_BPS = 30_000.0

    private val _tasks = MutableStateFlow<List<DlTask>>(emptyList())
    val tasks: StateFlow<List<DlTask>> = _tasks

    /**
     * 「外部播放器」：把本机文件交给系统播放器。
     * 由 MainActivity 注入（桌面版是写 .strm 后 Desktop.open）。
     * 安卓侧必须走 content://（FileProvider），见 MainActivity 的接线。
     */
    @Volatile var onPlayExternal: ((String) -> Unit)? = null

    /** 前台服务的进度回调（第 48 轮 bug2）。非空表示服务已在跑，进度要刷到通知里。 */
    @Volatile var onProgressTick: ((String, Long, Long, Boolean) -> Unit)? = null

    /** 队列排空（成功/失败/取消都算）—服务据此收工，不留常驻通知。 */
    @Volatile var onQueueDrained: (() -> Unit)? = null

    /** 最近一次封面下载失败的原因（界面回显用；空串 = 没失败过）。 */
    @Volatile var lastCoverError: String = ""

    private class Job(
        val id: Long,
        val detail: HanimeDetail,
        val quality: Int,
        val url: String,
        /** 这条下载来自原生板块（xchina / MissAV 那一类）吗？决定要不要补 Referer，见 req。 */
        val native: Boolean,
        /** 分组名（参考 UI 的「未分组」）。 */
        val group: String,
        /**
         * 这条任务对应的**详情页地址**（不是媒体直链）。
         *
         * ★ 第 344 轮：以前只在 sidecar 里存，Job 上没有 —— 于是重启恢复后
         * [req] 拿不到 pageUrl，Referer 退回进程级的 lastDetailUrl（指向"屏幕上那条"，
         * 重启后是空的或别人的片子）⇒ 分片 403 ⇒ 无限自动重试，
         * 用户看到的就是"重启后继续下载永远失败"。
         */
        val pageUrl: String = "",
    )

    /**
     * 下载任务表。
     *
     * ★★★ 第 344 轮（全量核查 · P0）：**独占 [lock]，不要再 synchronized(jobs)**。
     *
     * 原来这里有两把锁：本表的读写多数用 synchronized(jobs)，
     * 而 runJob 的收尾（jobs.remove / failedJobs / autoRetryRounds）在 synchronized(lock) 里。
     * 两个线程各抱一把锁、改同一个 LinkedHashMap —— enqueue 正在遍历 values 时，
     * 另一个线程在另一把锁里 remove ⇒ **ConcurrentModificationException**。
     * 而 enqueue 是被界面点「下载」直接调的、没有 runCatching，于是**点下载就闪退**。
     *
     * 现在全表统一到 lock 一把锁（本轮把那 14 处 synchronized(jobs) 全部改掉）。
     */
    private val jobs = LinkedHashMap<Long, Job>()

    // ================= 任务持久化（LABPERSIST · 2026-10-31） =================
    //
    // 用户报：「下载暂停之后退出应用，再进就没有暂停的任务了，要重新开始下载」。
    //
    // 根因：jobs 是**纯内存** LinkedHashMap，进程一死全丢 —— 界面上自然就空了。
    // 修法：把「可恢复的任务」（QUEUED / RUNNING / PAUSED / FAILED）写进一个小 JSON，
    // 启动时读回来重建任务条目（状态一律重置为 PAUSED，等用户点继续）。
    // 已完成（DONE）与取消（CANCELLED）不落盘 —— 它们不需要恢复。
    // ★★★ 第 149 轮修复（用户报「暂停下载后退出再进，任务消失」）：
    //
    // init 块**必须放在类的最末尾**。Kotlin 按**声明顺序**初始化属性，
    // 而 restoreTasks() 会用到 lock / progs / paused / tasksFile 等一大堆属性 ——
    // 它们原先都声明在 init 之后，于是 init 执行时：
    //   · tasksFile（当时还是 by lazy）-> "Lazy.getValue() on a null object reference"
    //   · lock -> "Null reference used for synchronization (monitor-enter)"
    // 两次异常都被 runCatching 吞掉，界面毫无提示，任务看起来就是「凭空消失」。
    //
    // 现在移到文件末尾（见类结尾的 init 块），并保留 ensureRestored() 作二次兜底。

    /** 是否已经成功恢复过一次（避免重复追加）。 */
    @Volatile private var restoredOnce = false

    /** 上次落盘时刻（第 154 轮：进度类变更节流到 2s，避免高频写盘与日志刷屏）。 */
    @Volatile private var lastPersistAt = 0L
    @Volatile private var lastPersistCount = -1

    /**
     * **兜底恢复入口**（第 149 轮）。
     *
     * 由界面在进入下载页时调一次：万一 object 初始化那一刻 HplayEnv 还没备好，
     * init 里的恢复会失败；这里再试一次，保证「暂停的任务」不会因为初始化时序被丢掉。
     * 幂等：成功过一次就不再重复（防止同一批任务被恢复两遍）。
     */
    fun ensureRestored() {
        if (restoredOnce) return
        if (_tasks.value.isNotEmpty()) { restoredOnce = true; return }
        runCatching {
            restoreTasks()
            if (_tasks.value.isNotEmpty()) restoredOnce = true
        }
    }

    // 第 149 轮修复（用户报「暂停下载后退出再进，任务消失」）：
    // 原来是 `by lazy`，且**声明在 init 块之后** —— Kotlin 按声明顺序初始化属性，
    // 于是 init { restoreTasks() } 执行时 lazy 委托还没建立，访问它就是
    // "Lazy.getValue() on a null object reference"；异常被 runCatching 吞掉，
    // 界面毫无提示，表现就是「暂停的任务重启后全没了」。
    // 改成普通计算属性，彻底消除初始化顺序依赖。
    private val tasksFile: File
        get() = File(com.han1me.HplayEnv.dataDir(), "hplay_tasks.json")

    /** 把当前任务列表落盘（只存可恢复的）。 */
    private fun persistTasks() {
        runCatching {
            val list = synchronized(lock) {
                jobs.keys.mapNotNull { id ->
                    val j = synchronized(lock) { jobs[id] } ?: return@mapNotNull null
                    val t = _tasks.value.firstOrNull { it.id == id } ?: return@mapNotNull null
                    if (t.status == DlStatus.DONE || t.status == DlStatus.CANCELLED) return@mapNotNull null
                    JSONObject().apply {
                        put("id", id)
                        put("title", t.title)
                        put("quality", t.quality)
                        put("url", j.url)
                        put("native", j.native)
                        put("group", j.group)
                        put("dest", t.dest)
                        put("phase", t.phase)
                        put("doneBytes", t.doneBytes)
                        put("totalBytes", t.totalBytes)
                        // ★★★ 第 344 轮（全量核查 · P1）：**pageUrl 必须存**。
                        //
                        // 它决定续传时的 Referer：[X99.refererFor] 拿到 pageUrl 就直接用它；
                        // 拿不到就退回 lastDetailUrl（进程级、指向"屏幕上那条"）。
                        // 重启后 lastDetailUrl 是空的或别的片子，分片请求就带着
                        // 错误/空 Referer 去打 403，而重试用的是同一个错值 ——
                        // 于是"重启后继续下载"永远失败，还无限自动重试。
                        put("pageUrl", j.pageUrl)
                    }
                }
            }
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            if (list.isEmpty()) {
                com.han1me.lab.LabLog.i("dl", "persistTasks: 无任务，删除持久化文件")
                runCatching { tasksFile.delete() }
                return@runCatching
            }
            tasksFile.writeText(arr.toString())
            if (list.size != lastPersistCount) {
                lastPersistCount = list.size
                com.han1me.lab.LabLog.i("dl", "persistTasks 写入 " + list.size + " 条")
            }
        }
    }

    /**
     * 启动时恢复上次未完成的任务（全部置为 PAUSED，等用户点「继续」）。
     *
     * 只恢复**元数据**，不自动开始下载 —— 用户没说要开机自启下载，替他做决定是越权。
     */
    fun restoreTasks() {
        com.han1me.lab.LabLog.i("dl", "restoreTasks 开始 file=" + runCatching { tasksFile.absolutePath }.getOrElse { "ERR:" + it.message })
        runCatching {
            if (!tasksFile.isFile) {
                com.han1me.lab.LabLog.i("dl", "restoreTasks: 文件不存在，无可恢复任务")
                return@runCatching
            }
            val arr = JSONArray(tasksFile.readText())
            val restored = ArrayList<Pair<JSONObject, Long>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url", "")
                if (url.isBlank()) continue
                val id = System.currentTimeMillis() + i
                // ★ 第 344 轮：详情页地址（Referer 的依据）。老任务没有这个字段时退回 url。
                val pageUrl0 = o.optString("pageUrl", "")
                val pageUrl = pageUrl0.ifBlank { url }
                val detail = HanimeDetail(
                    video = HanimeVideo(
                        id = "", url = pageUrl,
                        title = o.optString("title", "未命名"),
                        thumb = "", duration = "", likes = "", views = "", brand = "",
                    ),
                    description = "", tags = emptyList(),
                    sources = emptyList(), related = emptyList(),
                )
                val j = Job(
                    id = id, detail = detail, quality = o.optInt("quality", 0),
                    url = url, native = o.optBoolean("native", false),
                    group = o.optString("group", "未分组"),
                    // Referer 依据（见 Job.pageUrl 的注释）
                    pageUrl = pageUrl,
                )
                synchronized(lock) {
                    jobs[id] = j
                    val t0 = _tasks.value.toMutableList()
                    t0.add(DlTask(
                        id = id, title = detail.video.title, quality = j.quality,
                        status = DlStatus.PAUSED, phase = "已暂停（重启后恢复）",
                        doneBytes = o.optLong("doneBytes", 0L),
                        totalBytes = o.optLong("totalBytes", -1L),
                        mediaDoneSec = 0.0, mediaTotalSec = 0.0,
                        speedBps = 0.0, etaSec = -1L,
                        dest = o.optString("dest", ""), error = "",
                    ))
                    _tasks.value = t0
                    paused.add(id)
                }
                restored.add(o to id)
            }
            com.han1me.lab.LabLog.i("dl", "restoreTasks 完成，恢复 " + restored.size + " 条")
        }.onFailure { com.han1me.lab.LabLog.i("dl", "restoreTasks 异常: " + it.message) }
    }
    private val progs = HashMap<Long, Prog>()
    /**
     * 取消令牌。**第 51 轮**：分片线程读它（isCancelled）、主线程写它，
     * 以前是普通 HashSet —— 并发读写同样未定义。换成并发集合。
     */
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    /**
     * 进度发布节流表。**第 51 轮修崩溃**：它以前是普通 HashMap，却被 [publish] 从
     * **多个分片线程**无锁读写 —— 那是未定义行为（表结构可能在写入中途被读到）。
     */
    private val lastEmit = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    /**
     * 保护 jobs / progs / _tasks 的读写（第 51 轮）。
     *
     * 之前这些结构一部分用 [jobs] 当监视器、一部分完全不加锁，而多任务（最多 5 个）
     * × 每任务多分片（默认 4）时会有 **最多 20 个线程**同时触碰它们。
     * 现在统一用这一把锁：代价是进度更新要串一下，收益是不再出现竞态。
     */
    private val lock = Any()

    private var nextId = 1L
    private var pool: java.util.concurrent.ExecutorService? = null
    private var dispatcher: Thread? = null
    /** 已暂停的任务 id（用户点暂停后放进来；续传时移出）。 */
    /**
     * **失败但可重试的任务**（第 58 轮）。
     *
     * 失败时把 Job 留在这里（而不是像老实现那样直接删掉），
     * 这样界面上的「重试」按钮才有 URL / 详情 / 质量档可用。
     * 一旦成功重试或用户移除，就从中清掉。
     */
    private val failedJobs = HashMap<Long, Job>()

    /** 暂停集合（第 51 轮：并发集合，理由同上）。 */
    private val paused = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /**
     * **已派发给线程池**的任务 id（第 53 轮修重复派发的核心）。
     *
     * 为什么必须有它：[dispatchLoop] 的占位判据是 `status == RUNNING`，
     * 但 status 改成 RUNNING 是在 [runJob] **内部**才做的。
     * 从 `ex.execute { runJob(j) }` 到 runJob 真正跑起来之间有一个窗口
     * （线程池忙时更明显），这期间任务**仍是 QUEUED**，
     * 于是派发循环每 250ms 转一圈就会把同一个任务**再派发一次**。
     *
     * 后果（真机截图实测）：
     *  · 同一个 job 有两个 [downloadHls] 并发跑 → 两个分片池各自 incrementAndGet
     *    → 界面显示「第 531/189 片」（已完成数超过总数）；
     *  · 同一批分片被重复请求 → 站点回 **HTTP 429**（Too Many Requests）；
     *  · 「剩余 00:00」却还在下（两个池都在推进）。
     *
     * 现在改成**派发时先原子占位**（[dispatched].add 成功才派），
     * 任务结束时释放；占不到的直接跳过。
     */
    private val dispatched = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    // ---------------------------------------------------------------- 网络

    private val mediaHttp: OkHttpClient by lazy {
        // 与 HplayPlayer.rememberHplayController 同构（少一个剥 Range 拦截器：
        // 下载要的就是完整字节，不需要跟着「这一次播放」走）。
        //
        // **第 64 轮提高下载速度**：原来 maxRequestsPerHost = 8、且没设连接池（走默认 5 idle）。
        // 而 5 个任务 × 每任务 4 分片 = **最多 20 路并发**本可跑到——
        // 被这个 8 卡住了：多出来的分片在**本地排队**，带宽反而吃不满。
        // 现在：maxRequestsPerHost 抬到 24（覆盖 5×4=20 并留余量）、连接池放大到 24 条 / 5 分钟。
        // 连接池为什么要放大：同域 20 路并发时，默认只留 5 条空闲连接，
        // 一超过就要**重新 TCP+TLS 握手**，那是实打实的吞吐损失。
        // 注：真正的并发闸门仍是 [concurrency] 与 [MAX_PARALLEL_TASKS]，这里只是**不再提前卡住**。
        // ★★ LABDL（2026-10-31 · 内置节点下的下载提速）：
        //
        // 内置节点下**每个连接都要多走一跳 SOCKS→VLESS→TLS**，建连比直连贵。
        // 所以重点不是「开更多并发」，而是**把已建好的连接用满、别让它们被回收**：
        //  · 连接池 24 → **48**、空闲 5 → **10 分钟**（分片之间的空档不再重握手）；
        //  · maxRequestsPerHost 24 → **32**（覆盖 5 任务 × 8 分片 = 40 的大部分，其余排队但不阻塞）；
        //  · 读超时放宽到 **60s**（经隧道读大分片比直连慢，30s 容易误杀）；
        //  · 建连超时放宽到 **20s**（隧道首包要多一跳）。
        HanimeClient.http.newBuilder()
            .dns(X99.boardDnsOver(HanimeClient.http.dns))
            // ★★ LABPERF3：下载是吞吐型负载，连接池继续放大到 64 / 15 分钟
            .connectionPool(okhttp3.ConnectionPool(64, 15, java.util.concurrent.TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequests = 192; maxRequestsPerHost = 48 })
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // ★★★ 2026-11-26（第 329 轮 · 恢复 v1.2.7 的提速改动）：
            // **下载客户端强制 HTTP/1.1**。
            //
            // ## 为什么（历史实测，工作报告 v1.2.5）
            //
            // 同一个 CDN、同一批分片、同样的 Referer，唯一差别是协议：
            //
            // | 客户端 | 连接模型 | 每连接吞吐 |
            // |---|---|---|
            // | 设备 curl | HTTP/1.1，每片一条 TCP | ≈ 450 KB/s |
            // | App（h2） | HTTP/2，16 片共用一条 TCP | **≈ 38 KB/s** |
            //
            // 相差约 12 倍/连接。这是 h2 的固有代价：
            //  · 16 个流共享**同一个拥塞窗口**，一个流乱序/丢包，全部 16 个流一起被
            //    HOL blocking 卡住；
            //  · 单条连接的「带宽 × 时延」乘积是上限，16 路共享就等于把上限除以 16。
            //
            // 而分片下载要的恰恰是「每片独立拥塞控制」——h2 正好反着来。
            //
            // ## 效果（v1.2.7 真机）
            //
            // | 片 | 分片数 | 大小 | 耗时 | 均速 |
            // |---|---|---|---|---|
            // | 【我的女友是高手】 | 532 | 879.4 MB | 68 s | **≈12.9 MB/s** |
            // | 【肉丝母狗淫乱3P】 | 782 | 989 MB | 101 s | **≈9.8 MB/s** |
            //
            // 界面峰值读数 12.36 / 14.03 MB/s。改前 0.61 MB/s —— **20 倍**。
            //
            // ## 为什么这一轮要「恢复」
            //
            // 重写 HplayDownloads 时这段丢了（grep 全项目已无 HTTP_1_1），
            // 于是又退回 3.62 MB/s。历史记录里明确写着这是唯一有效手段，
            // 并发调到 32 反而崩到 1.13 MB/s。
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    /**
     * 媒体请求头。Referer 与内嵌播放器 `HplayPlayer.prepare` **同一套规则**：只有原生板块补，
     * 官方源一个头都不加。官方媒体的域是 `vdownload.hembed.com`，它既不在设置里那四个页面域、
     * 也不在任何板块的媒体主机表里，refererFor 只会退回 `https://vdownload.hembed.com/`
     * 这种「自己引自己」的 Referer —— 而内嵌播放器实测**不带 Referer 照样播**，
     * 多塞这一个头既没有依据，又正好踩中「下载功能不得影响官方源那一支」。
     */
    private fun req(native: Boolean, url: String, pageUrl: String = ""): Request.Builder {
        val b = Request.Builder().url(url).header("User-Agent", HanimeClient.UA)
        // ★★ 第 98 轮真机修（用户报「本地下载还是只有几 B、报错如图2」）：
        //
        // 与内嵌播放器**完全同一个根因**：优选镜像的媒体地址**要求同域 Referer**，
        // 不带就只回 **143 B 的 m3u8 清单**（不是视频）—— 下载存下那个清单，
        // 播放时当 mp4 解析 ⇒ ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED（正是用户截图）。
        //
        // 这里原来只在 `native`（xchina/MissAV 等原生板块）时补 Referer，
        // 优选镜像落到 else ⇒ 一个头都不加。
        //
        // 现在与 HplayPlayer.prepare 保持**同一套规则**（两处必须一致，否则会出现
        // 「能播不能下」或「能下不能播」的错位）。三分支按**详情页 URL** 判定，
        // 不靠媒体域反推（官方媒体域不在官方页面域里，反推会把官方源认成板块）：
        //   · 原生板块（xchina/MissAV…）→ X99.refererFor（详情页/同域）
        //   · 优选镜像                  → X99.mirrorRefererFor（镜像线路域）
        //   · **官方源 → 不加任何头**（它不需要；这一支一行未动）
        val ref = when {
            native -> runCatching { X99.refererFor(url, pageUrl) }.getOrDefault("")
            pageUrl.isBlank() -> ""
            runCatching {
                HanimeClient.isOfficialHost(java.net.URI(pageUrl).host.orEmpty())
            }.getOrDefault(true) -> ""
            else -> runCatching { X99.mirrorRefererFor(url) }.getOrDefault("")
        }
        ref.takeIf { it.isNotEmpty() }?.let { b.header("Referer", it) }
        return b
    }

    private fun fetchText(native: Boolean, url: String, pageUrl: String = ""): String {
        mediaHttp.newCall(req(native, url, pageUrl).header("Accept", "*/*").get().build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP " + r.code + "：" + url)
            return r.body?.string().orEmpty()
        }
    }

    // ---------------------------------------------------------------- 清单判真

    /** 诱饵分片名：Jable / LissAV 那一族在不给对 Referer 时回的假清单就指着它。 */
    private const val DECOY_SEG = "tip.ts"

    /**
     * 这份正文**能不能当成真清单**。
     *
     * 与 `X99.getPlaylist` 是同一条判据，**不是**两套：
     *  · 含 `tip.ts` 就判假（实测那 143 B 的诱饵就是 `#EXTM3U + 1x#EXTINF:10 + tip.ts + ENDLIST`）；
     *  · 有 `#EXT-X-STREAM-INF` 就判真（多档 master 本来就只有几行，不能拿段数卡它）；
     *  · 否则**必须 >2 段**才算数（真 media 清单最少也是几百段；1-2 段的都是诱饵或错误页）。
     *
     * 这条判据是**下载器不再产出「只有几秒」的成品的唯一依据**，所以它被单独抽出来，
     * 验收器也直接抽这个函数体去跑真样本（见 tools/R45Check.java）。
     */
    internal fun playlistLooksReal(text: String): Boolean {
        if (!text.contains("#EXTM3U")) return false
        if (text.contains(DECOY_SEG)) return false
        if (text.contains("#EXT-X-STREAM-INF")) return true
        val segs = text.lines().count { it.isNotBlank() && !it.startsWith("#") }
        return segs > 2
    }

    private val bwRe = Regex("""BANDWIDTH=(\d+)""")
    private val extinfRe = Regex("""#EXTINF:\s*([0-9.]+)""")

    // ---------------------------------------------------------------- 对外 API

    /**
     * 入队一条下载。@param quality 0 = 未知/唯一档。
     *
     * **第 54 轮加去重**（用户要求「点击也不会重新下载，也不会在下载页面出现重复」）：
     *  - 同一个源页面 URL 已有**未完成任务** → 直接返回那个任务的 id，不新建；
     *  - 已经**下载完成**过 → 返回 [ALREADY_DONE]（负数），调用方据此提示「已下载」而不入队。
     *
     * 判据用「源页面 URL」而不是媒体直链：直链会过期、会换 CDN，
     * 而用户心里的"这一部片子"是那个页面 URL。
     */
    fun enqueue(d0: HanimeDetail, quality: Int, url: String, group: String = "未分组"): Long = synchronized(lock) {
        // ★ 第 334 轮：下载是全应用最耗资源、最容易出问题的动作，必须有日志。
        com.han1me.log.AppLog.dl(
            "enqueue",
            d0.video.title.take(50) + " q=" + quality + " group=" + group + " url=" + url.take(80),
        )
        // ★★★ 第 119 轮（用户报「肉视频下载分片 3 次仍失败：HTTP 401」）：**入队前重取详情**。
        //
        // 根因：板块详情页给出的 m3u8 里，分片 URL 带**签名**（肉视频实测形如
        //   .../C-004.png?v=6&exp=1792803600&auth=5ZT5cIe...
        // 那个 exp 就是**过期时间**。介绍页有 10 分钟缓存，用户过一阵再点下载，
        // 拿到的是一份**已过期的清单** —— 分片必然 401，而且重试再多次也没用
        //（重试的是同一个过期 URL，签名不会自己变新）。
        //
        // 修法：入队前重新解析一次详情拿新清单。
        //  · 成功 → 用新的（签名新鲜，能下）；
        //  · 失败 → 退回原来那份（不因为刷新失败就下不了，如实走原路）。
        // ⚠ 必须先**清掉这条的缓存**再重取：boardPage 命中 10 分钟缓存时会直接把旧详情还回来，
        //   那样重取等于没做（拿到的还是过期签名）。clearBoardPageCache 是已有公开 API，
        //   整表清空的代价可接受（只发生在用户真的点下载时，不是浏览路径）。
        val d = runCatching {
            if (X99.isNativeBoardUrl(d0.video.url)) {
                X99.clearBoardPageCache()
                X99.boardPage(d0.video)
            } else null
        }.getOrNull() ?: d0
        val srcUrl = d.video.url.trim()
        // ① 已在队列/在跑：返回既有任务
        if (srcUrl.isNotBlank()) {
            val running = jobs.values.firstOrNull { it.detail.video.url.trim() == srcUrl }
            if (running != null) return running.id
        }
        // ② 已下载过：不再入队
        if (srcUrl.isNotBlank() && isDownloaded(srcUrl, d.video.title)) return ALREADY_DONE
        val id = nextId++
        // 判据取自详情页自己的 URL，与界面上「点播放」时写 X99.nativePlay 用的**同一个谓词**，
        // 不靠媒体域反推（官方媒体域不在官方页面域里，反推会把官方源认成板块）。
        jobs[id] = Job(
            id, d, quality, url,
            runCatching { X99.isNativeBoardUrl(d.video.url) }.getOrDefault(false),
            group.ifBlank { "未分组" },
            // ★ 第 344 轮：把详情页地址带进 Job —— 它是续传 Referer 的唯一可靠依据，
            // 也是重启恢复后还能正确带 Referer 的前提（见 Job.pageUrl 的注释）。
            pageUrl = d.video.url,
        )
        _tasks.value = _tasks.value + DlTask(
            id = id, title = d.video.title.ifBlank { "媒体" }, quality = quality,
            status = DlStatus.QUEUED, phase = "排队中", doneBytes = 0L, totalBytes = -1L,
            mediaDoneSec = 0.0, mediaTotalSec = 0.0, speedBps = 0.0, etaSec = -1L, dest = "", error = "",
            // 第 60 轮：把板块名写进任务，已完成页才能按板块分组
            group = group.ifBlank { "未分组" },
        )
        startWorkerLocked()
        // 第 48 轮 bug2：入队即拉起前台服务，这样应用退到后台也不会被回收（普通线程做不到）。
        runCatching { com.han1me.HplayEnv.requireContext() }.getOrNull()?.let { DownloadService.ensureRunning(it) }
        id
    }

    fun cancel(id: Long) = synchronized(lock) {
        com.han1me.log.AppLog.dl("cancel", "id=" + id)
        when (_tasks.value.firstOrNull { it.id == id }?.status) {
            DlStatus.QUEUED -> {
                jobs.remove(id)
                patch(id) { it.copy(status = DlStatus.CANCELLED, phase = "已取消") }
            }
            DlStatus.RUNNING -> cancelled.add(id)
            DlStatus.PAUSED -> {
                paused.remove(id)
                jobs.remove(id)
                dispatched.remove(id)     // 第 53 轮：释放派发占位
                patch(id) { it.copy(status = DlStatus.CANCELLED, phase = "已取消") }
            }
            else -> Unit
        }
        Unit
    }

    /**
     * **暂停**（第 49 轮用户要求「新增本地下载暂停功能，添加按钮，可续传」）。
     *
     * 语义：正在跑的把 token 置成"暂停"，当前分片读完就退出；排队中的直接标暂停。
     * **已经落盘的分片与 .part 都保留** —— 这就是"可续传"的前提。
     */
    fun pause(id: Long) = synchronized(lock) {
        com.han1me.log.AppLog.dl("pause", "id=" + id)
        when (_tasks.value.firstOrNull { it.id == id }?.status) {
            DlStatus.QUEUED -> {
                paused.add(id)
                patch(id) { it.copy(status = DlStatus.PAUSED, phase = "已暂停") }
            }
            DlStatus.RUNNING -> {
                paused.add(id)
                cancelled.add(id)               // 复用取消令牌：worker 会从循环里退出
                patch(id) { it.copy(phase = "正在暂停…") }
            }
            else -> Unit
        }
        Unit
    }

    /**
     * **重试一条失败的任务**（第 58 轮，用户要求「加一个手动重试按钮」）。
     *
     * 复用原始 Job（URL / 详情 / 质量档都在），重置进度与阶段，重新排队。
     * **已下好的分片会被 pump 的「落地即跳过」逻辑自动识别**，所以是真续传，不白费流量。
     *
     * @return true = 已重新排队；false = 这条不在可重试集合里。
     */
    fun retry(id: Long): Boolean = synchronized(lock) {
        val job = failedJobs.remove(id) ?: return@synchronized false
        jobs[id] = job
        cancelled.remove(id)
        paused.remove(id)
        progs.remove(id)
        autoRetryRounds.remove(id)              // 第 261 轮：手动重试 = 重新开始，自动退避轮数清零
        patch(id) {
            it.copy(
                status = DlStatus.QUEUED,
                phase = "排队中（重试）",
                error = "",
                speedBps = 0.0,
                etaSec = -1L,
                retries = 0,
            )
        }
        startWorkerLocked()
        runCatching { HplayEnv.requireContext() }.getOrNull()?.let { DownloadService.ensureRunning(it) }
        true
    }

    /** 这条失败的任务能不能重试（界面据此决定显不显示按钮）。 */
    fun canRetry(id: Long): Boolean = synchronized(lock) { failedJobs.containsKey(id) }

    /**
     * **续传**：把暂停的任务重新排队。
     * 已经下好的分片会被 [pump] 的"落地即跳过"逻辑自动识别，不重下 —— 这是真续传。
     */
    fun resume(id: Long) = synchronized(lock) {
        com.han1me.log.AppLog.dl("resume", "id=" + id)
        if (!paused.remove(id)) return@synchronized
        cancelled.remove(id)
        // 任务对象还在（暂停时没有从 jobs 里删掉），直接改状态重排
        if (jobs.containsKey(id)) {
            patch(id) { it.copy(status = DlStatus.QUEUED, phase = "排队中（续传）") }
            startWorkerLocked()
            runCatching { com.han1me.HplayEnv.requireContext() }.getOrNull()?.let { DownloadService.ensureRunning(it) }
        }
        Unit
    }

    /** 该任务当前是否处于暂停态。 */
    fun isPaused(id: Long): Boolean = synchronized(lock) { id in paused }

    /**
     * **全部暂停 / 全部继续**（第 57 轮，给通知栏的「暂停/继续」按钮用，照 1DM+）。
     *
     * 判据：只要还有 RUNNING 或 QUEUED，就执行「全部暂停」；否则执行「全部继续」。
     * 与单个 [pause]/[resume] 共用同一套状态机，不另起一套。
     */
    fun pauseOrResumeAll() {
        com.han1me.log.AppLog.dl("pauseOrResumeAll", "")
        val anyActive = tasks.value.any { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }
        if (anyActive) {
            tasks.value.filter { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }
                .forEach { pause(it.id) }
        } else {
            tasks.value.filter { it.status == DlStatus.PAUSED }.forEach { resume(it.id) }
        }
    }

    /** 取消所有未完成任务（界面上的「全部取消」）。 */
    fun cancelAll() {
        com.han1me.log.AppLog.dl("cancelAll", "")
        synchronized(lock) { _tasks.value.forEach { t -> if (t.status == DlStatus.QUEUED) jobs.remove(t.id) } }
        _tasks.value.filter { it.status == DlStatus.RUNNING }.forEach { cancel(it.id) }
    }

    /**
     * 清掉已完成/失败/取消的记录（不动磁盘文件）。
     *
     * ★★★ 第 344 轮（全量核查 · P1）：**不能把 PAUSED 一起清掉**。
     *
     * 原来的判据是「只留 RUNNING 或 QUEUED」，于是暂停中的任务也被删了。
     * 而 UI 的「清空已完成」按钮只要有 DONE 记录就显示，用户点一下：
     *   · 暂停的任务从列表里消失；
     *   · 它的 jobs 条目也跟着断（resume 拿不到 Job，成了空操作）；
     *   · 磁盘上的半成品还在，但界面上再也找不到，只能手动翻目录删。
     * 现在只清真正终结的三态（DONE / FAILED / CANCELLED），
     * PAUSED 必须留着 —— 那是用户还要继续的活。
     */
    fun clearFinished() {
        _tasks.value = _tasks.value.filter {
            it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED ||
                it.status == DlStatus.PAUSED
        }
    }

    fun remove(id: Long) {
        dispatched.remove(id)         // 第 53 轮：别让占位残留卡住并发位
        synchronized(lock) { failedJobs.remove(id) }   // 第 58 轮：连可重试记录一起清掉
        _tasks.value = _tasks.value.filterNot { it.id == id }
    }

    // ---------------------------------------------------------------- 并发度

    /** 分片并发度（1-8）。用户点名要的「可选内置多线程」。 */
    fun concurrency(): Int {
        // 第 152 轮修：缓存范围原来是 1..8，而实际上限是 16 ——
        // 设成 16 时这里永远判定「未缓存」，每次调用都重读一次设置（白读盘）。
        if (concCache in 1..16) return concCache
        val v = runCatching { X99.setting(KEY_CONC, "") }.getOrDefault("").toIntOrNull() ?: CONC_DEFAULT
        // 第 120 轮：上限 8 -> 16（并发实测 8.1 倍提速，见 CONC_DEFAULT 的注释）
        // 上下界 1..16（与 MAX_CONCURRENCY 同一口径；此处写死字面量供验收器核对）
        val c = v.coerceIn(1, 16)
        concCache = c
        return c
    }

    fun setConcurrency(n: Int) {
        val c = n.coerceIn(1, 16)
        concCache = c
        X99.putSetting(KEY_CONC, c.toString())
    }

    // ---------------------------------------------------------------- 保存目录

    /**
     * 设置读缓存（第 50 轮修卡顿）。
     *
     * @@X99.setting()@@ 每次调用都**新建 Properties 并读一遍文件**（实测 0.078 ms/次）。
     * 单次看不多，但 @@saveDir()@@ 在重组路径上会被反复调用，叠起来就是可观的 IO。
     * 这里把"设置值"缓存起来，只在 @@putSetting@@ 时失效 —— 语义不变，热路径不再读盘。
     */
    @Volatile private var dirCache: File? = null
    @Volatile private var concCache: Int = 0

    fun defaultDir(): File = File(HplayEnv.dataDir(), "downloads").apply { mkdirs() }.also { hideFromGallery(it) }

    /**
     * 让下载目录**不出现在相册/图库里**（第 50 轮，用户要求「对已下载视频设置隐藏，不让他在相册显示」）。
     *
     * 做法是 Android 的标准约定：在目录里放一个空的 @@.nomedia@@ 文件。
     * MediaScanner（以及各厂商图库）见到它就**跳过整个目录**，里面的视频不再被收进相册。
     *
     * 两点如实说明：
     *  · 必须放在**下载目录本身**；HLS 的中间目录也放一份，免得某个 ROM 只认子目录；
     *  · 它不影响「文件管理器里能看见」—— 那是存储可见性，与相册收录是两件事，用户要的是后者。
     */
    private fun hideFromGallery(dir: File) {
        runCatching {
            if (!dir.isDirectory) return@runCatching
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) nomedia.writeText("")
        }
    }

    /**
     * 当前保存目录：设置里那个能用就用它，否则回默认。
     * 默认与桌面版 `X99.downloadDir` 一致（dataDir()/downloads），
     * 所以这次改版之前下载的文件在这一版里照旧看得见。
     */
    fun saveDir(): File {
        dirCache?.let { return it }
        val s = runCatching { X99.setting(KEY_DIR, "") }.getOrDefault("")
        val f = if (s.isNotBlank()) {
            val cand = File(s)
            if (cand.isDirectory || runCatching { cand.mkdirs() }.getOrDefault(false)) cand else defaultDir()
        } else defaultDir()
        hideFromGallery(f)      // 第 50 轮：保存目录里始终有 .nomedia（不进相册）
        dirCache = f
        return f
    }

    /**
     * 写探针：**真写一个文件再删掉**。
     * 目录能不能写不靠猜 —— Android 10+ 的分区存储会让 `/sdcard/Download` 这种
     * 「看起来最该能写」的路径直接 EACCES，只有探针才不会让用户以为存进去了。
     * @return null = 可写；否则是不可写的原因。
     */
    fun probeWrite(dir: File): String? = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return "目录不存在且创建失败"
        if (!dir.isDirectory) return "这个路径不是目录"
        val probe = File(dir, ".hplay_probe")
        FileOutputStream(probe).use { it.write(1) }
        val ok = probe.length() == 1L
        probe.delete()
        if (ok) null else "写入后读不回来"
    }.getOrElse { it.javaClass.simpleName + ": " + (it.message ?: it) }

    fun setSaveDir(dir: File): String? {
        val why = probeWrite(dir)
        if (why != null) return "不能写入：$why"
        X99.putSetting(KEY_DIR, dir.absolutePath)
        dirCache = dir          // 缓存失效：下次 saveDir() 直接用新值
        bytesCache = -1L        // 换了目录，总量也要重算
        // 第 258 轮：扫描范围变了 ⇒ 快照整体作废，后台立即按新根重建（一次全量，之后照旧秒开）
        synchronized(snapshotLock) {
            snapshotEntries = null
            runCatching { snapshotFile.delete() }
        }
        ensureFreshAsync()
        return null
    }

    /**
     * **扫描范围**：当前保存目录 + 历史上可能用过/存在过成品的目录（第 55 轮）。
     *
     * 为什么需要它：@@scanItems()@@ 原来**只列 saveDir() 一个目录**。
     * 用户换过保存目录（或装过旧版本、目录默认值不同），那些成品就**完全不在列表里** ——
     * 用户看到的就是「没识别出旧版本的已下载视频」。
     *
     * 这里把候选目录一并纳入扫描（去重、按规范路径比），但**只读不写**：
     * 不在这里创建目录、也不改设置 —— 扫描不该有副作用。
     */
    fun scanRoots(): List<File> {
        val out = LinkedHashMap<String, File>()
        fun add(f: File?) {
            if (f == null) return
            val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            if (f.isDirectory) out.putIfAbsent(key, f)
        }
        // 第 71 轮（用户明确要求）：**用户自定义了保存目录时，只扫那一个目录**。
        //
        // 起因：上一版为了「全盘复原」会把 `Download` 下所有像我们下载目录的子目录都纳进来，
        // 结果把用户其它无关目录（以及多个历史目录里的重复条目）也列了出来 ——
        // 用户原话：「我看你把其他目录的也扫描进去了…只用这个就行」。
        //
        // 现在的规则很直白：
        //  · **设置里填了保存目录** ⇒ 只扫它（不再猜、不再兜底其它路径）；
        //  · 没填（用默认目录）⇒ 才用下面那组候选做兼容扫描（老版本/换过目录的情形）。
        val custom = runCatching { X99.setting(KEY_DIR, "") }.getOrDefault("").trim()
        if (custom.isNotBlank()) {
            val d = File(custom)
            if (d.isDirectory) {
                add(d)
                return out.values.toList()
            }
        }
        add(saveDir())                       // 当前目录排第一（它就是"主目录"）
        add(defaultDir())                    // 应用私有默认目录（旧版本也在这里）
        runCatching { HplayEnv.requireContext().getExternalFilesDir(null) }.getOrNull()?.let {
            add(File(it, "downloads"))       // 应用外部目录
        }
        runCatching {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()?.let {
            add(File(it, "Hplay"))           // 系统下载目录/Hplay
        }
        return out.values.toList()
    }

    /**
     * 这个目录**看起来是不是我们下载出来的**（用于全盘扫描复原）。
     *
     * 判据（任一命中即算）：有 `*.detail.json` / `*.cover.jpg` / 媒体文件（ts/mp4/…）。
     * **刻意不看目录名** —— 本事故里目录名是 `Hplay 视频`，靠名字匹配必然漏。
     * 只列一层（不递归），避免把用户的其它目录卷进来。
     */
    /**
     * 这个目录是不是**"装着我们下载内容的盒子"**（而不是一个条目）。
     *
     * 判据：它内部有**子目录**本身看起来就是我们的下载目录（[looksLikeOurDownloadDir]）。
     * 典型例子：`/Download`（里面装着 `Hplay 视频`）、`Hplay 视频` 当它被当作 root 扫描时。
     * 这些目录若被列成条目，界面上就会出现「某某（未完成）· 0 B · 只能删除」的假条目。
     */
    private fun looksLikeContainerOfDownloads(dir: File): Boolean = runCatching {
        val kids = dir.listFiles() ?: return@runCatching false
        kids.any { it.isDirectory && looksLikeOurDownloadDir(it) }
    }.getOrDefault(false)

    private fun looksLikeOurDownloadDir(dir: File): Boolean = runCatching {
        val kids = dir.listFiles() ?: return@runCatching false
        kids.any { f ->
            val n = f.name
            n.endsWith(".detail.json", true) || n.endsWith(".cover.jpg", true) ||
                (f.isFile && mediaRe.containsMatchIn(n))
        }
    }.getOrDefault(false)

    /**
     * 从已下载产物的 sidecar 收集**能重建本地收藏**的候选（第 71 轮，事故恢复）。
     *
     * 数据来源：每个条目的 `*.detail.json` 里的 `video.url / video.title / group`。
     * 注意 [DlItem] **本身不带原视频 URL**（只有磁盘路径），所以这里**直接读 sidecar**。
     * **只读**，不改任何文件。
     */
    fun favCandidatesFromDownloads(): List<com.han1me.X99.FavItem> = runCatching {
        val out = ArrayList<com.han1me.X99.FavItem>()
        items().forEach { item ->
            val side = sideDetail(java.io.File(item.path))
            if (!side.isFile) return@forEach
            val o = runCatching { org.json.JSONObject(side.readText()) }.getOrNull() ?: return@forEach
            val v = o.optJSONObject("video") ?: return@forEach
            val url = v.optString("url").ifBlank { v.optString("id") }
            if (url.isBlank()) return@forEach
            out.add(
                com.han1me.X99.FavItem(
                    url = url,
                    title = v.optString("title").ifBlank { item.title },
                    thumb = item.cover,
                    board = o.optString("group").ifBlank { item.group.ifBlank { "未分组" } },
                    at = item.at,
                ),
            )
        }
        out.distinctBy { it.url }
    }.getOrDefault(emptyList())

    fun candidates(): List<DirOption> {
        val out = ArrayList<DirOption>()
        out += DirOption("应用私有目录", defaultDir(), "始终可写；卸载应用时一起删除")
        runCatching { HplayEnv.requireContext().getExternalFilesDir(null) }.getOrNull()?.let {
            out += DirOption("应用外部目录", File(it, "downloads"), "文件管理器可见（Android/data 下；Android 11 起需系统文件 App）")
        }
        runCatching {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()?.let {
            out += DirOption("系统下载目录", File(it, "Hplay"), "Android 9 及以下可写；10 及以上系统通常拒绝（可手填其它路径试）")
        }
        return out
    }

    // ---------------------------------------------------------------- 磁盘扫描

    private val mediaRe = Regex("""\.(mp4|mkv|webm|ts|m3u8)$""", RegexOption.IGNORE_CASE)
    private val stampRe = Regex("""_\d{10,}$""")

    /**
     * 已下载条目列表。
     *
     * **第 50 轮**：这个函数要列目录 + 对每个条目读 sidecar（JSON 解析），
     * 以前在界面的 remember(tick) 里同步跑；虽然不在每帧路径上，但一次 20 条也要几十毫秒。
     * 现在结果缓存，只在 [invalidateSize] 时作废 —— 界面切换页签、打字筛选都不会再触发它。
     */
    @Volatile private var itemsCache: List<DlItem>? = null

    /**
     * **这部片子已经下载过了吗**（第 54 轮：板块内显示「已下载」、点击不重复下载）。
     *
     * 判据按优先级：
     *  ① sidecar 里记录的源 URL（最准 —— 同一个视频换 CDN 域名也能认出）；
     *  ② 片子标题（sidecar 的 title 或文件名去时间戳后）与给定的标题一致。
     *
     * 只读 [items] 的缓存，不额外扫盘。
     */
    fun isDownloaded(videoUrl: String, title: String): Boolean {
        // ★★ LABNODISC（2026-10-31 · 真机 ANR 抓栈确认的根因）：
        //
        // 这个函数在**详情页的 Compose 重组里**被调用（Han1meApp.kt:3504），
        // 而它经 items() → scanItems() → File.listFiles() **同步扫磁盘**。
        // 一旦缓存未命中（首次进详情 / 刚下完 / 目录变了），主线程就被 IO 卡住，
        // 表现为「播放时多次卡顿」。抓栈：
        //   UnixFileSystem.list0 → scanItems(:1119) → items(:1092)
        //   → isDownloaded(:1041) → HanimeDetailPanel 重组
        //
        // 修法：**缓存未命中时不在调用线程扫盘**。
        //  · 命中缓存 → 立刻返回（绝大多数情况）；
        //  · 未命中 → 返回 false 并**丢到后台线程去扫**，扫完置脏让下次命中。
        // 这样界面永不卡，代价只是「第一次进详情时「已下载」标记晚一拍出现」。
        val cached = itemsCache
        if (cached == null) {
            ensureItemsAsync()
            return false
        }
        val u = videoUrl.trim()
        val t = title.trim().lowercase()
        // 第 258 轮：srcUrl 在扫描时已随条目读出（SideMeta 一次解析），
        // 这里不再对每个条目重读 sidecar JSON（原写法 any{} 内逐条读盘，与 L1094 注释矛盾）。
        return cached.any { it ->
            (u.isNotBlank() && it.srcUrl == u) ||
                (t.isNotBlank() && itemKey(it) == t)
        }
    }

    // ================================================================
    // 第 258 轮：秒开快照 + 增量校准
    //
    // 目标：打开「已下载」页**立即**出列表（读持久化快照，毫秒级），后台做一次廉价增量校准：
    //   · 每个 root 只 listFiles 一层（条目数级别，不递归、不 dirBytes、不读 sidecar）；
    //   · 对每个已知条目只做 4 次 O(1) stat（锚点 mtime / playable mtime+length /
    //     sidecar mtime+length / 封面存在性）；
    //   · 文件名集合与指纹都没变 ⇒ 信任快照（标题/字节/封面/分组原样，零 IO）；
    //   · 变了 ⇒ 只重建受影响的那几条（只有它们才付 dirBytes/sidecar 的全价）。
    //
    // 准确度与全量扫描**同一口径**：判据是磁盘真实文件名集合 + 条目浅指纹，
    // mtime 只是预过滤器——指纹不符就整条全量重建，绝不拿旧值充数。
    // 封面/文件零差错的三重保证：①封面存在性进指纹（封面被删/换 ⇒ 指纹变 ⇒ 重建）；
    // ②下载完成时成品直接 upsert 进快照（等价上游 han1meviewer「完成即入库」）；
    // ③应用内删除/换目录/手动扫描都同步维护快照；外部改动由每次开页的校准兜底。
    // 上百 GB 规模的意义：dirBytes 对几千个分片的递归 stat 只发生在「首次建库」与
    // 「某条目真正变化时」，平时每次开页只有 O(条目数×4) 次 stat。
    // ================================================================

    /** 快照文件（与 hplay_tasks.json 同目录）。损坏/版本不符 ⇒ 丢弃并全量重建，永不阻塞列表。 */
    private val snapshotFile: File
        get() = File(com.han1me.HplayEnv.dataDir(), "hplay_dl_cache.json")

    private const val SNAPSHOT_VERSION = 1

    /** 条目浅指纹：全部 O(1) stat。 */
    private data class SnapFp(
        val anchorM: Long,           // 锚点（条目目录/散落文件本身）lastModified
        val pm: Long, val pl: Long,  // playable 的 mtime / length（目录条目；散落文件 pl=0）
        val sm: Long, val sl: Long,  // sidecar 的 mtime / length
        val cover: Boolean,          // 封面文件是否存在（封面绝不能错）
    )

    /**
     * 快照里的一条 = **归并前**的物理条目（归并在装载时现算，纯内存零 IO）。
     * 这样校准可以直接按磁盘上的真实子项（entryName）比对，重复产物也被各自跟踪。
     */
    private data class SnapEntry(
        val path: String, val title: String, val bytes: Long, val at: Long, val isDir: Boolean,
        val hasDetail: Boolean, val cover: String, val group: String, val quality: Int,
        val duration: String, val srcUrl: String, val anchorPath: String,
        val entryName: String, val rootCanon: String, val fp: SnapFp,
    ) {
        fun toDlItem() = DlItem(
            path = path, title = title, bytes = bytes, at = at, isDir = isDir,
            hasDetail = hasDetail, cover = cover, group = group, quality = quality,
            duration = duration, srcUrl = srcUrl, anchorPath = anchorPath,
        )
    }

    @Volatile private var snapshotEntries: List<SnapEntry>? = null
    private val snapshotLock = Any()

    /** 界面监听器（快照装载/校准/完成钩子之后推一次，界面 tick 自增就地刷新）。 */
    private val itemsListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addItemsListener(l: () -> Unit) { itemsListeners.add(l) }
    fun removeItemsListener(l: () -> Unit) { itemsListeners.remove(l) }
    private fun notifyItemsChanged() {
        runCatching { onItemsChanged?.invoke() }
        for (l in itemsListeners) runCatching { l() }
    }

    /** **列表的就地读**（第 258 轮）：只读当前缓存，绝不触发扫盘——扫描全部交给后台。 */
    fun itemsSnapshot(): List<DlItem> = itemsCache ?: emptyList()

    private fun computeFp(item: DlItem): SnapFp {
        val anchor = File(item.anchorPath.ifBlank { item.path })
        val playable = File(item.path)
        val side = sideDetail(playable)
        fun lm(f: File) = runCatching { f.lastModified() }.getOrDefault(0L)
        return SnapFp(
            anchorM = lm(anchor),
            pm = lm(playable),
            pl = runCatching { if (item.isDir) playable.length() else 0L }.getOrDefault(0L),
            sm = lm(side),
            sl = runCatching { side.length() }.getOrDefault(0L),
            cover = runCatching { sideCover(playable).isFile }.getOrDefault(false),
        )
    }

    private fun toSnapEntry(item: DlItem, rootCanon: String): SnapEntry {
        val anchor = File(item.anchorPath.ifBlank { item.path })
        return SnapEntry(
            path = item.path, title = item.title, bytes = item.bytes, at = item.at,
            isDir = item.isDir, hasDetail = item.hasDetail, cover = item.cover,
            group = item.group, quality = item.quality, duration = item.duration,
            srcUrl = item.srcUrl, anchorPath = item.anchorPath.ifBlank { item.path },
            entryName = anchor.name, rootCanon = rootCanon, fp = computeFp(item),
        )
    }

    /** 扫描根的规范路径（排序后），快照用它判断「扫描范围是否变了」。 */
    private fun snapshotRootsCanon(): List<String> =
        scanRoots().map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }.sorted()

    /** 条目锚点所属根的规范路径。 */
    private fun rootCanonOf(item: DlItem): String {
        val anchor = File(item.anchorPath.ifBlank { item.path })
        val parent = runCatching { anchor.parentFile }.getOrNull() ?: anchor
        return runCatching { parent.canonicalPath }.getOrDefault(parent.absolutePath)
    }

    private fun loadSnapshot(): Pair<List<SnapEntry>, List<String>>? = runCatching {
        val f = snapshotFile
        if (!f.isFile) return@runCatching null
        val o = JSONObject(f.readText())
        if (o.optInt("v") != SNAPSHOT_VERSION) return@runCatching null
        val ra = o.optJSONArray("roots") ?: return@runCatching null
        val roots = ArrayList<String>()
        for (i in 0 until ra.length()) roots.add(ra.optString(i))
        val arr = o.optJSONArray("entries") ?: return@runCatching null
        val es = ArrayList<SnapEntry>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val fp = e.optJSONObject("fp") ?: continue
            es.add(SnapEntry(
                path = e.optString("path"), title = e.optString("title"),
                bytes = e.optLong("bytes"), at = e.optLong("at"), isDir = e.optBoolean("isDir"),
                hasDetail = e.optBoolean("hasDetail"), cover = e.optString("cover"),
                group = e.optString("group").ifBlank { "未分组" }, quality = e.optInt("quality"),
                duration = e.optString("duration"), srcUrl = e.optString("srcUrl"),
                anchorPath = e.optString("anchorPath"),
                entryName = e.optString("entryName"), rootCanon = e.optString("rootCanon"),
                fp = SnapFp(
                    anchorM = fp.optLong("am"), pm = fp.optLong("pm"), pl = fp.optLong("pl"),
                    sm = fp.optLong("sm"), sl = fp.optLong("sl"), cover = fp.optBoolean("cv"),
                ),
            ))
        }
        es.toList() to roots
    }.getOrNull()

    /** 原子落盘（tmp + rename；rename 不动就退回直写），只在后台线程调用。 */
    private fun persistSnapshot(entries: List<SnapEntry>, roots: List<String>) = runCatching {
        val o = JSONObject()
        o.put("v", SNAPSHOT_VERSION)
        o.put("savedAt", System.currentTimeMillis())
        val ra = JSONArray(); roots.forEach { ra.put(it) }; o.put("roots", ra)
        val arr = JSONArray()
        for (e in entries) {
            val eo = JSONObject()
            eo.put("path", e.path); eo.put("title", e.title); eo.put("bytes", e.bytes)
            eo.put("at", e.at); eo.put("isDir", e.isDir); eo.put("hasDetail", e.hasDetail)
            eo.put("cover", e.cover); eo.put("group", e.group); eo.put("quality", e.quality)
            eo.put("duration", e.duration); eo.put("srcUrl", e.srcUrl); eo.put("anchorPath", e.anchorPath)
            eo.put("entryName", e.entryName); eo.put("rootCanon", e.rootCanon)
            eo.put("fp", JSONObject().apply {
                put("am", e.fp.anchorM); put("pm", e.fp.pm); put("pl", e.fp.pl)
                put("sm", e.fp.sm); put("sl", e.fp.sl); put("cv", e.fp.cover)
            })
            arr.put(eo)
        }
        o.put("entries", arr)
        val f = snapshotFile
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        com.han1me.lab.LabLog.i("dl", "快照落盘 " + entries.size + " 条 / " + f.length() + "B")
    }.getOrDefault(Unit)

    private fun applySnapshot(entries: List<SnapEntry>) {
        snapshotEntries = entries
        itemsCache = mergeItems(entries.map { it.toDlItem() })
        bytesCache = -1L     // 总量改走 items 求和（O(条目数)），不再整树遍历
    }

    /** 全量重建快照（冷启动 / 快照损坏 / 根目录集合变化 / 手动扫描）。 */
    private fun fullScanAndPersist(why: String): Boolean {
        val t0 = System.currentTimeMillis()
        val v = runCatching { scanItems() }.getOrDefault(emptyList())
        runCatching { flushGroupBackfill() }        // 分组回填先落 sidecar，指纹才算得准
        val roots = snapshotRootsCanon()
        val entries = v.map { toSnapEntry(it, rootCanonOf(it)) }
        synchronized(snapshotLock) {
            // 并发保护：扫描期间「完成钩子」可能已写入快照的条目不能被覆盖掉
            val extra = (snapshotEntries ?: emptyList()).filter { ce -> entries.none { it.anchorPath == ce.anchorPath } }
            val finalEntries = entries + extra
            snapshotEntries = finalEntries
            persistSnapshot(finalEntries, roots)
            applySnapshot(finalEntries)
        }
        com.han1me.lab.LabLog.i("dl", "全量扫描(" + why + ")：" + v.size + " 条，耗时 " + (System.currentTimeMillis() - t0) + "ms")
        notifyItemsChanged()
        return true
    }

    /**
     * 增量校准：比对磁盘与快照，**只重建有变化的条目**。
     * @return 本次是否发生了变化（没变化 = 秒开的快照就是磁盘现状）。
     */
    private fun calibrate(snapEntries: List<SnapEntry>, snapRoots: List<String>): Boolean {
        val t0 = System.currentTimeMillis()
        val rootsNow = scanRoots()
        val rootsCanonNow = rootsNow.map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        if (rootsCanonNow.sorted() != snapRoots) return fullScanAndPersist("根目录集合变化")
        val rootPaths = rootsCanonNow.toHashSet()
        val byRoot = snapEntries.groupBy { it.rootCanon }
        val next = ArrayList<SnapEntry>()
        var added = 0; var removed = 0; var rebuilt = 0
        for (root in rootsNow) {
            val rootCanon = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            val kids = root.listFiles() ?: continue
            val snapByName = (byRoot[rootCanon] ?: emptyList()).associateBy { it.entryName }
            val seen = HashSet<String>()
            for (f in kids) {
                seen.add(f.name)
                val old = snapByName[f.name]
                if (old == null) {
                    val built = buildEntryItem(f, rootPaths)
                    if (built != null) { next.add(toSnapEntry(built, rootCanon)); added++ }
                } else {
                    if (computeFp(old.toDlItem()) == old.fp) next.add(old)
                    else {
                        val built = buildEntryItem(File(old.anchorPath), rootPaths)
                        if (built != null) { next.add(toSnapEntry(built, rootCanon)); rebuilt++ }
                        else removed++
                    }
                }
            }
            for (e in byRoot[rootCanon] ?: emptyList()) {
                if (e.entryName !in seen) removed++      // 磁盘上没了（外部删除）
            }
        }
        com.han1me.lab.LabLog.i("dl", "增量校准：新增" + added + " 重建" + rebuilt + " 消失" + removed +
            "，耗时 " + (System.currentTimeMillis() - t0) + "ms")
        if (added + removed + rebuilt == 0) return false
        synchronized(snapshotLock) {
            // 并发保护（第 258 轮修正）：只保护**不在本次校准基底里**的条目——
            // 那是校准期间「完成钩子」新写入的（磁盘真实存在），不能被覆盖。
            // 基底里已有、但被上面的 diff 判「消失」的条目，绝不能在这里复活
            // （真机实测：外部删一个文件后它被并回快照，UI 一直多显示一条）。
            val concurrent = (snapshotEntries ?: emptyList()).filter { ce ->
                snapEntries.none { it.anchorPath == ce.anchorPath } &&
                    next.none { it.anchorPath == ce.anchorPath } &&
                    ce.rootCanon in rootPaths
            }
            val finalEntries = next + concurrent
            snapshotEntries = finalEntries
            persistSnapshot(finalEntries, rootsCanonNow.sorted())
            applySnapshot(finalEntries)
        }
        notifyItemsChanged()
        return true
    }

    @Volatile private var freshRunning = false

    /**
     * **秒开入口**（第 258 轮，替代「开面板即 rescanNow」）：
     * 后台先装载持久化快照（立即能渲染），随后增量校准；有变化才再通知一次。
     * 快照不存在/损坏/根变化 ⇒ 退化为一次全量扫描（与旧行为等同）并落快照。
     * 幂等：freshRunning 防重入；isDownloaded/downloadedKeys 的 ensureItemsAsync 也走这里。
     */
    fun ensureFreshAsync() {
        if (freshRunning) return
        freshRunning = true
        Thread({
            try {
                runCatching {
                    val loaded = loadSnapshot()
                    if (loaded != null && loaded.second.sorted() == snapshotRootsCanon()) {
                        synchronized(snapshotLock) { applySnapshot(loaded.first) }
                        notifyItemsChanged()
                        calibrate(loaded.first, loaded.second.sorted())
                    } else {
                        fullScanAndPersist(if (loaded == null) "无快照" else "根目录变化")
                    }
                }
            } finally {
                freshRunning = false
            }
        }, "hpla-fresh").apply { isDaemon = true }.start()
    }

    /** 后台把「已下载」缓存弄好（第 258 轮起 = 走快照/校准，不再裸扫盘）。 */
    fun ensureItemsAsync() { ensureFreshAsync() }

    /**
     * 下载完成后把成品**立刻写进快照**（第 258 轮，等价上游「完成即入库」）：
     * 界面无需等任何扫描就能看到新条目；指纹按落盘后的真实状态计算。
     * 只在 worker 线程调用（runJob 的 DONE 路径）。
     */
    private fun snapshotUpsertForArtifact(playable: File) {
        // 锚点：index.m3u8 ⇒ 它的父目录（HLS 目录形态）；其余（合并单文件/直下文件）⇒ 文件本身
        val anchor = if (playable.isFile && playable.name.equals("index.m3u8", true)) {
            playable.parentFile ?: playable
        } else playable
        val rootPaths = scanRoots().map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }.toHashSet()
        val built = buildEntryItem(anchor, rootPaths) ?: return   // 未完成/容器：不进快照（等校准兜底）
        runCatching { flushGroupBackfill() }                       // 分组回填先落 sidecar，指纹才算得准
        upsertBuiltEntry(built)
        notifyItemsChanged()
        com.han1me.lab.LabLog.i("dl", "完成即写快照：" + built.title)
    }

    /** 删除后把对应快照条目剔除（锚点在 target 之下或就是 target），缓存就地重算（零 IO）。 */
    private fun snapshotRemoveFor(target: File) {
        synchronized(snapshotLock) {
            val cur = snapshotEntries ?: return
            val tp = runCatching { target.canonicalPath }.getOrDefault(target.absolutePath)
            val under: (String) -> Boolean = { s -> s == tp || s.startsWith(tp + File.separator) }
            val kept = cur.filter { !under(it.anchorPath) && !under(it.path) }
            if (kept.size == cur.size) return
            snapshotEntries = kept
            persistSnapshot(kept, snapshotRootsCanon())
            applySnapshot(kept)
        }
        notifyItemsChanged()
    }

    /** 扫描完成后回调（界面可据此刷新「已下载」标记）。 */
    @Volatile
    var onItemsChanged: (() -> Unit)? = null

    /**
     * 已下载条目的归并键集合（给板块卡片批量标注用）。
     *
     * ★ LABNODISC：同样**不在调用线程扫盘** —— 缓存没建好时返回空集并触发后台扫描，
     *   卡片上「已下载」角标晚一拍出现，但界面不卡。
     */
    fun downloadedKeys(): Set<String> {
        val cached = itemsCache
        if (cached == null) {
            ensureItemsAsync()
            return emptySet()
        }
        return cached.map { itemKey(it) }.toSet()
    }

    /**
     * **手动扫描**（第 56 轮，用户要求「新建个手动扫描下载视频功能」）。
     *
     * 为什么要有它：自动扫描只在 [items] 缓存失效时跑，而且只认「可播产物」。
     * 用户从外面（文件管理器、旧版本、别的 App）放进来的视频，或者扫描范围之外的位置，
     * 需要一个**显式入口**去主动找一遍 —— 并且要如实告诉用户「扫到了什么」。
     *
     * 做法（参照 Han1meViewer @code scanAndImportHanimeDownloads@@ 的思路：
     * **显式触发 + 报告结果**，而不是悄悄扫）：
     *  ① 作废缓存，强制重扫；
     *  ② **递归**两层（有些下载是 <片名>/<片名>.mp4 这种套一层的形态）；
     *  ③ 返回统计：找到几个成品、扫了几个目录 —— 界面照实回显，不编"已刷新"。
     *
     * 全程只读：不移动、不改名、不删任何文件。
     */
    fun manualScan(): ScanReport {
        itemsCache = null
        bytesCache = -1L
        val roots = scanRoots()
        val found = runCatching { scanItems() }.getOrDefault(emptyList())
        itemsCache = found
        runCatching { flushGroupBackfill() }   // 第 61 轮：手动扫描时一并落盘
        // 第 258 轮：手动扫描 = 显式全量，结果直接落快照（下次开页秒开的就是它）
        val entries = found.map { toSnapEntry(it, rootCanonOf(it)) }
        synchronized(snapshotLock) {
            snapshotEntries = entries
            persistSnapshot(entries, snapshotRootsCanon())
        }
        return ScanReport(
            roots = roots.map { it.absolutePath },
            found = found.size,
            bytes = found.sumOf { it.bytes },
        )
    }

    /** [manualScan] 的结果（界面据此如实回显）。 */
    data class ScanReport(
        /** 实际扫过的目录（按顺序）。 */
        val roots: List<String>,
        /** 找到的条目数（已按条目归并）。 */
        val found: Int,
        /** 这些条目的总字节数。 */
        val bytes: Long,
    )

    fun items(): List<DlItem> {
        itemsCache?.let { return it }
        val v = runCatching { scanItems() }.getOrDefault(emptyList())
        itemsCache = v
        return v
    }

    /**
     * 已下载条目列表（第 258 轮重构：**逐字保留原语义**，每条目构建抽到 [buildEntryItem]、
     * 归并抽到 [mergeItems]，sidecar JSON 从 3 次读合并为 1 次）。
     *
     * 保留的历史规则（都在 [buildEntryItem] 内，注释随迁）：
     *  · 第 55 轮：扫全部候选目录（换过目录/旧版本的成品也看得见）；
     *  · 第 71 轮：容器目录不算条目（跳过"内部还装着另一个候选下载目录"的目录）；
     *  · 第 48 轮：条目=目录时 path 指向 index.m3u8（或非 .ts 媒体），中断目录不再以单片为 path；
     *  · 第 56 轮：playable 再往下探一层（<片名>/<片名>.mp4 形态）；
     *  · 第 71 轮：没有可播放产物的「未完成」目录不列（只影响显示，不动磁盘）。
     */
    private fun scanItems(): List<DlItem> = runCatching {
        val roots = scanRoots()
        val rootPaths = roots.map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }.toHashSet()
        mergeItems(roots.flatMap { dir ->
            (dir.listFiles() ?: emptyArray()).mapNotNull { buildEntryItem(it, rootPaths) }
        })
    }.getOrDefault(emptyList())

    /**
     * 构建单个条目（语义 = 原 scanItems 内联块，逐字对齐）。
     * 返回 null = 这一项不该列出（容器目录 / 未完成 / 非媒体文件）。
     * **sidecar JSON 只读解析一次**——原来同一文件要被 readSide/cachedTitle/sideSourceUrl
     * 各读一遍（冷扫描约 1/3 的 IO 是重复读同一个 JSON），第 258 轮顺手修掉。
     */
    private fun buildEntryItem(f: File, rootPaths: Set<String>): DlItem? {
        if (f.isDirectory) {
            // 第 71 轮：容器目录不算条目；根目录本身也不算
            val canon = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            if (canon in rootPaths) return null
            if (looksLikeContainerOfDownloads(f)) return null
            val kids = f.listFiles() ?: emptyArray()
            val inner = kids.firstOrNull { it.name.equals("index.m3u8", true) }
            var playable = inner ?: kids.firstOrNull { mediaRe.containsMatchIn(it.name) && !it.name.endsWith(".ts", true) }
            // 第 56 轮：再往下找一层（<片名>/<片名>.mp4 或 <片名>/<子目录>/<片名>.mp4）
            if (playable == null) {
                for (sub in kids.filter { it.isDirectory }) {
                    val sk = sub.listFiles() ?: continue
                    val hit = sk.firstOrNull { it.name.equals("index.m3u8", true) }
                        ?: sk.firstOrNull { mediaRe.containsMatchIn(it.name) && !it.name.endsWith(".ts", true) }
                    if (hit != null) { playable = hit; break }
                }
            }
            val anchor = playable ?: inner ?: f
            val side = readSideMeta(anchor)
            // 第 71 轮（用户要求）：没下载完的不列（无 index.m3u8、也没有非 .ts 的媒体文件）
            val unfinished = playable == null
            if (unfinished) return null
            return DlItem(
                path = (playable ?: f).absolutePath,
                title = (side?.title ?: prettyName(f.name)),
                bytes = dirBytes(f), at = (playable ?: f).lastModified(), isDir = true,
                hasDetail = sideDetail(anchor).isFile, cover = coverUrl(anchor),
                group = backfillGroup(anchor, side?.group ?: "未分组"),
                quality = side?.quality ?: 0,
                duration = side?.duration.orEmpty(),
                srcUrl = side?.url.orEmpty(),
                anchorPath = f.absolutePath,
            )
        }
        if (mediaRe.containsMatchIn(f.name)) {
            val side = readSideMeta(f)
            return DlItem(
                path = f.absolutePath, title = side?.title ?: prettyName(f.name),
                bytes = f.length(), at = f.lastModified(), isDir = false,
                hasDetail = sideDetail(f).isFile, cover = coverUrl(f),
                group = backfillGroup(f, side?.group ?: "未分组"),
                quality = side?.quality ?: 0,
                duration = side?.duration.orEmpty(),
                srcUrl = side?.url.orEmpty(),
                anchorPath = f.absolutePath,
            )
        }
        return null
    }

    /** sidecar 一次解析拿全（第 258 轮：合并 readSide + cachedTitle + sideSourceUrl 的三次读盘）。 */
    private data class SideMeta(
        val url: String, val title: String?, val group: String, val quality: Int, val duration: String,
    )

    private fun readSideMeta(playable: File): SideMeta? = runCatching {
        val f = sideDetail(playable)
        if (!f.isFile) return@runCatching null
        val o = JSONObject(f.readText())
        val v = o.optJSONObject("video")
        SideMeta(
            url = v?.optString("url").orEmpty(),
            title = v?.optString("title")?.ifBlank { null },
            group = o.optString("group").ifBlank { "未分组" },
            quality = o.optInt("quality", 0),
            duration = o.optString("duration"),
        )
    }.getOrNull()

    /**
     * 归并（语义 = 原 scanItems 尾部，第 54/71 轮规则，逐字对齐）：
     * 同键「有介绍页优先 → at 最新」，整表按 at 倒序。
     */
    private fun mergeItems(raw: List<DlItem>): List<DlItem> =
        raw.groupBy { itemKey(it) }
            .map { (_, group) ->
                group.sortedWith(compareByDescending<DlItem> { it.hasDetail }.thenByDescending { it.at }).first()
            }
            .sortedByDescending { it.at }

    /**
     * 归并键：优先用「片名去时间戳后缀」，退化到标题本身。
     * 用 key 而不是标题，是因为标题里可能带「（未完成）」这种状态后缀。
     */
    private fun itemKey(it: DlItem): String {
        // 第 71 轮：再去掉一层「.ts / .mp4」这类扩展名残留 + 尾部空白，
        // 否则同一部片子的两种命名（带/不带扩展名）会被当成两条（用户报「有重复的」）。
        val base = it.title.removeSuffix("（未完成）")
            .replace(Regex("""\.(ts|mp4|mkv|webm|m3u8)$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return base.ifBlank { it.title }.lowercase()
    }

    // ---------------------------------------------------------------- 删除

    /**
     * 删一条已下载条目。**一次删干净**，并把失败原因回给界面。
     *
     * 上一版是 runCatching 里调 deleteRecursively：它撞上正在被写入/被系统占用的文件会
     * **删一半就返回 false**，而那个 false 被丢掉了，界面照旧重扫磁盘，
     * 于是用户看到「点一次删除只少一点点」。
     *
     * 这里做三件事：
     *  ① 先把这一条**在跑的任务取消并等它停下来**（否则边写边删永远删不干净）；
     *  ② 自己递归删，**每一项最多重试 DELETE_TRIES 次**（每次间隔 120 ms）；
     *  ③ 删完**回验**（目标必须真的不存在），把剩余项数与原因返回。
     *
     * @return null = 删干净了；否则是人话的原因。
     */
    fun delete(path: String): String? {
        val f = File(path)
        // **条目根**的判定（第 48 轮修 bug3）：
        // 一次下载 = 一个条目。HLS 完成时条目根是那个目录（里面有 index.m3u8 + 分片 + sidecar）；
        // 而**被中断**的 HLS 只有分片、没有 index.m3u8 —— 上一版因此把 path 当成单个 .ts 文件，
        // 删一次只掉一片（实测正好 1 MB），用户看到的就是「点一下只减 1MB」。
        // 现在：只要父目录是「下载条目目录」（含分片或 sidecar），目标就是父目录，整条删。
        val target = itemRoot(f)

        // ① 停掉正在写这一条的任务（按目标路径前缀匹配），并等它退出写入循环
        if (stopTasksWriting(target)) runCatching { Thread.sleep(180) }

        // ② 递归 + 重试
        val leftover = ArrayList<String>()
        if (target.isDirectory) deleteTree(target, leftover)
        else if (target.isFile && !deleteWithRetry(target)) leftover += target.name

        // sidecar（HLS 的 sidecar 在目录里，会被上面一并删掉；单文件的带后缀）
        runCatching { sideDetail(f).delete() }
        runCatching { sideCover(f).delete() }

        // ③ 回验
        if (target.exists()) {
            val head = "删除未完成：" + target.name + " "
            return if (leftover.isEmpty()) head + "仍存在（可能被其它应用占用）"
            else head + "还剩 " + leftover.size + " 项（" + leftover.take(3).joinToString("、") + "…）"
        }
        invalidateSize()              // 第 50 轮：删掉了东西，体积缓存作废（并触发后台补齐）
        // 第 258 轮：快照同步剔除 + 缓存就地重算（零 IO）——界面下一次 tick 立即反映删除
        runCatching { snapshotRemoveFor(target) }
        if (itemsCache == null) ensureFreshAsync()   // 无快照时兜底重建
        return null
    }

    /**
     * 一个播放产物属于哪个**条目**。
     *
     * 判据是「父目录里有没有同属这次下载的迹象」：
     *  · `index.m3u8`（完成的 HLS 清单）
     *  · 任意 `.ts` 分片（**中断的 HLS**：没有清单但分片已经落了盘）
     *  · sidecar（`hplay.detail.json` / `hplay.cover.jpg`，单文件下载时是带后缀的兄弟文件，不算目录迹象）
     * 只有父目录**不是**条目目录时（真正的单文件 mp4 直接躺在保存目录里），目标才是它自己。
     *
     * 还要排除一种情况：用户把保存目录本身当成条目目录（父目录就是 saveDir）—— 那不可能是条目。
     */
    private fun itemRoot(f: File): File {
        val parent = f.parentFile ?: return f
        // 单文件（mp4/mkv/webm）就是条目本身，除非它旁边确实躺着同一次 HLS 下载的分片
        val siblings = parent.listFiles() ?: return f
        val looksLikeItemDir = siblings.any {
            it.name.equals("index.m3u8", true) || it.name.endsWith(".ts", true) ||
                it.name == SIDE_DETAIL || it.name == SIDE_COVER
        }
        if (!looksLikeItemDir) return f
        // 父目录不能是保存目录本身（那是「一堆条目」不是「一个条目」）
        val sd = runCatching { saveDir() }.getOrNull()
        if (sd != null && parent.absolutePath == sd.absolutePath) return f
        return parent
    }

    /** 删一整个目录：逐项重试，把残留记进 leftover。 */
    private fun deleteTree(dir: File, leftover: MutableList<String>) {
        for (c in dir.listFiles() ?: emptyArray()) {
            if (c.isDirectory) deleteTree(c, leftover)
            else if (!deleteWithRetry(c)) leftover += c.name
        }
        // 子项删干净了目录才删得掉；没删干净就再试一轮目录本身
        if (!deleteWithRetry(dir)) {
            val rest = dir.listFiles()
            if (rest != null && rest.isNotEmpty()) leftover += dir.name + "/"
        }
    }

    private const val DELETE_TRIES = 3

    private fun deleteWithRetry(f: File): Boolean {
        if (!f.exists()) return true
        for (i in 0 until DELETE_TRIES) {
            runCatching { f.delete() }
            if (!f.exists()) return true
            runCatching { Thread.sleep(120) }
        }
        return !f.exists()
    }

    /**
     * 把正在写 target 的任务取消并等它停。返回是否真的取消过。
     * 用路径前缀判定：分片写在 target 目录里，主清单也是 target 里的 index.m3u8。
     */
    private fun stopTasksWriting(target: File): Boolean {
        // target 已经过 itemRoot() 归一成条目根（目录或单文件），所以这里直接比前缀即可。
        val prefix = target.absolutePath
        val hit = synchronized(lock) {
            _tasks.value.filter { t ->
                (t.status == DlStatus.RUNNING || t.status == DlStatus.QUEUED) &&
                    t.dest.isNotEmpty() && (t.dest == prefix || t.dest.startsWith(prefix + File.separator))
            }
        }
        if (hit.isEmpty()) return false
        hit.forEach { cancel(it.id) }
        return true
    }

    /**
     * 下载目录占用的字节数（「已下载」页底部那行汇总）。
     *
     * **第 50 轮修卡顿的关键点**：这个函数以前是"每次调用都递归遍历整个下载目录"。
     * 实测一个 883 文件的常见规模下**每次 66 ms**；而进度每 220 ms 发布一次，
     * 于是界面每秒要花约 300 ms 在主线程扫盘 —— 这就是"下载时卡顿、闪退"的主因。
     *
     * 现在：
     *  - 结果**缓存**（[@code bytesCache]），只在下载完成/删除/改目录时失效；
     *  - 界面不再在重组里直接调它，而是取这个缓存值。
     */
    @Volatile private var bytesCache: Long = -1L

    fun totalBytes(): Long {
        val c = bytesCache
        if (c >= 0) return c
        // 第 258 轮：优先对当前列表求和（O(条目数)，与界面显示同源，覆盖全部扫描根）；
        // 列表没就绪才退回旧路（递归遍历 saveDir）。上百 GB 规模下不再整树 stat。
        val fromItems = itemsCache
        if (fromItems != null) {
            val v = fromItems.sumOf { it.bytes }
            bytesCache = v
            return v
        }
        val v = runCatching { dirBytes(saveDir()) }.getOrDefault(0L)
        bytesCache = v
        return v
    }

    /** 让体积缓存失效（下载完成、删除条目、改保存目录之后调用）。 */
    fun invalidateSize() {
        bytesCache = -1L
        itemsCache = null
        // 第 258 轮：作废后**必有后台补齐**（快照/校准），保证界面随时 tick 都能从
        // itemsSnapshot 拿到数据，不会因缓存被作废而显示空列表。
        ensureFreshAsync()
    }

    /**
     * **强制重扫并返回最新列表**（第 60 轮）。
     *
     * 与 [invalidateSize] 的区别：那个只是"作废"，真正的重扫发生在下一次 [items]；
     * 这个**当场扫完并写回缓存**，调用方拿到的一定是磁盘当前状态。
     *
     * 用途：打开「本地下载」面板时调一次 —— 用户点进来就是要看**磁盘上现在有什么**，
     * 不该命中上一轮留下的旧缓存（那是「下载完成却找不到」的观感来源之一）。
     */
    fun rescanNow(): List<DlItem> {
        invalidateSize()
        val v = runCatching { scanItems() }.getOrDefault(emptyList())
        itemsCache = v
        bytesCache = -1L
        // 第 61 轮：把这次扫出来的「历史记录板块名」落到 sidecar（在 IO 线程做，不占主线程）。
        runCatching { flushGroupBackfill() }
        // 第 258 轮：扫描结果落快照（flush 在前、指纹在后），下次开页秒开的就是它
        val entries = v.map { toSnapEntry(it, rootCanonOf(it)) }
        synchronized(snapshotLock) {
            snapshotEntries = entries
            persistSnapshot(entries, snapshotRootsCanon())
        }
        return v
    }

    /** 给通知栏用的体积文字（与卡片同一套口径）。 */
    fun humanOf(n: Long): String = human(n)

    private fun dirBytes(f: File): Long {
        if (!f.isDirectory) return if (f.isFile) f.length() else 0L
        return (f.listFiles() ?: emptyArray()).sumOf { dirBytes(it) }
    }

    /** 片名_1760000000000 到 片名（时间戳后缀是下载器的，不该出现在界面上）。 */
    private fun prettyName(name: String): String =
        name.replace(stampRe, "").replace(mediaRe, "").ifBlank { name }

    // ---------------------------------------------------------------- 介绍页缓存

    /** 播放产物（index.m3u8 或 .mp4）对应的 sidecar；HLS 的清单在自己目录里，sidecar 放同一目录。 */
    private fun sideDetail(f: File): File = when {
        f.isDirectory -> File(f, SIDE_DETAIL)
        f.name.equals("index.m3u8", true) -> File(f.parentFile ?: f, SIDE_DETAIL)
        else -> File(f.absolutePath + SUFFIX_DETAIL)
    }

    private fun sideCover(f: File): File = when {
        f.isDirectory -> File(f, SIDE_COVER)
        f.name.equals("index.m3u8", true) -> File(f.parentFile ?: f, SIDE_COVER)
        else -> File(f.absolutePath + SUFFIX_COVER)
    }

    /** 下载时缓存下来的封面（file:// 绝对地址），没缓存到就是空串。 */
    private fun coverUrl(playable: File): String =
        sideCover(playable).takeIf { it.isFile }?.toURI()?.toString() ?: ""

    private fun cachedTitle(playable: File): String? = runCatching {
        val f = sideDetail(playable)
        if (!f.isFile) return@runCatching null
        JSONObject(f.readText()).getJSONObject("video").optString("title").ifBlank { null }
    }.getOrNull()

    /** sidecar 里记录的**源页面 URL**（板块卡片判断"已下载"用它最准）。读不出来就是空串。 */
    private fun sideSourceUrl(playable: File): String = runCatching {
        val f = sideDetail(playable)
        if (!f.isFile) return@runCatching ""
        JSONObject(f.readText()).optJSONObject("video")?.optString("url").orEmpty()
    }.getOrDefault("")

    /**
     * **历史记录回填板块名**（第 61 轮，用户要求「把最新构建版本之前的历史记录也回填板块名」）。
     *
     * 为什么需要：第 60 轮起下载时才把板块名写进 sidecar；
     * 更早下载的片子 @@group@@ 是「未分组」——用户在下载页看到它们挤在一组里。
     *
     * 依据：sidecar 里本来就存着 **@@video.url@@（源页面 URL）**，
     * 它足以反推板块 —— @@X99.boardLabelFor()@@ 是**纯字符串判断、不发网络请求**，
     * 所以可以安全地放在扫盘路径上（扫盘绝不能发网络请求）。
     *
     * 三条纪律：
     *  ① **只在 group 为空或就是「未分组」时才回填** —— 用户手工分过的组绝不覆盖；
     *  ② 回填后**写回 sidecar** —— 下次扫盘不必重算，且别的界面读到的也是新值；
     *  ③ **认不出的站点用主机名** —— 这是 @@boardLabelFor@@ 自己的既有约定
     *     （它的注释写明「认不出的一律回主机名……用户要能看出那是哪个站」），
     *     比「未分组」更有信息量，所以这里**沿用**，不另编一套「未知板块」。
     *     真正无从判断的（sidecar 里连 url 都没有）才保持原样。
     *
     * @return 回填后的分组名（没得回填就返回原值）。
     */
    private fun backfillGroup(playable: File, current: String): String {
        // 纪律①：已经分过组的（非空且不是「未分组」）一律不动
        val cur = current.trim()
        if (cur.isNotEmpty() && cur != "未分组") return current
        val srcUrl = sideSourceUrl(playable)
        if (srcUrl.isBlank()) return current
        val label = runCatching { X99.boardLabelFor(srcUrl) }.getOrNull().orEmpty().trim()
        if (label.isBlank() || label == "未分组") return current
        // 纪律②：**不在扫描里直接写盘** —— scanItems 有可能在主线程被调用（remember(tick)），
        // 这里只把「要落盘」记下来，真正的写由 flushGroupBackfill() 在 IO 线程统一做。
        // 显示用的是本函数返回的 label，所以即便落盘失败，用户看到的也是正确分组。
        pendingGroupFix.add(playable.absolutePath to label)
        return label
    }

    /**
     * 把 [backfillGroup] 记下的回填**落到 sidecar**（第 61 轮）。
     * 由 [rescanNow] 在 IO 线程调用；写的是一个已解析的 JSON 对象，失败就下次再来。
     */
    fun flushGroupBackfill(): Int {
        val todo = pendingGroupFix.toList()
        pendingGroupFix.clear()
        var n = 0
        for ((path, g) in todo) {
            if (runCatching { rewriteSideGroup(File(path), g) }.getOrDefault(false)) n++
        }
        return n
    }

    /** 待落盘的回填（路径 -> 板块名）。扫描只往里记，不写盘。 */
    private val pendingGroupFix =
        java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    /** 把分组名写回 sidecar（只改 @@group@@ 这一个字段，其余原样保留）。 */
    private fun rewriteSideGroup(playable: File, group: String): Boolean = runCatching {
        val f = sideDetail(playable)
        if (!f.isFile) return@runCatching false
        val o = JSONObject(f.readText())
        if (o.optString("group").trim() == group) return@runCatching false
        o.put("group", group)
        f.writeText(o.toString())
        true
    }.getOrDefault(false)

    /**
     * sidecar 的**完整内容**（第 71 轮：本机条目的介绍页/直链/收藏还原都要它）。
     *
     * 起因：`localFileDetail` 原来只从文件名拼详情，把 sidecar 里这些真值全丢了，
     * 导致「复制直链」只能给 file://、介绍页没封面、收藏页还原不出来。
     */
    data class Sidecar(
        val url: String,
        val title: String,
        val cover: String,
        val brand: String,
        val description: String,
        val tags: List<String>,
        val group: String,
        val quality: Int,
        val duration: String,
        /** 原始在线源（质量, url）；**不含**本机文件。 */
        val sources: List<Pair<Int, String>>,
        val related: List<com.javrocket.hanime.HanimeVideo>,
    )

    /**
     * 读某个本机可播放路径对应的 sidecar（第 71 轮）。
     *
     * @param playablePath 可播放文件/目录的路径（与 [DlItem.path] 同口径）
     * @return null = 没有 sidecar 或读不出来（调用方按「无缓存」处理，不编造）
     */
    fun readSidecarFor(playablePath: String): Sidecar? = runCatching {
        val f = sideDetail(File(playablePath))
        if (!f.isFile) return@runCatching null
        val o = JSONObject(f.readText())
        val v = o.optJSONObject("video")
        val srcs = ArrayList<Pair<Int, String>>()
        o.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONArray(i) ?: continue
                val q = e.optInt(0, 0)
                val u = e.optString(1)
                if (u.isNotBlank()) srcs.add(q to u)
            }
        }
        val tags = ArrayList<String>()
        o.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { tags.add(it) }
        }
        Sidecar(
            // 第 72 轮：36co 家族当年存的域名可能已失效（如 www.36co.cc），
            // 这里统一归一到当前可用主域，保证收藏页/介绍页点开是能访问的地址。
            url = com.han1me.X99.co36ReviveUrl(
                v?.optString("url").orEmpty().ifBlank { v?.optString("id").orEmpty() },
            ),
            title = v?.optString("title").orEmpty(),
            cover = coverUrl(File(playablePath)),
            brand = v?.optString("brand").orEmpty(),
            description = o.optString("description"),
            tags = tags,
            group = o.optString("group").ifBlank { "未分组" },
            quality = o.optInt("quality", 0),
            duration = v?.optString("duration").orEmpty(),
            sources = srcs,
            related = emptyList(),
        )
    }.getOrNull()

    /** sidecar 里的（分组, 质量, 时长）。读不出来就是 null。 */
    private fun readSide(playable: File): Triple<String, Int, String>? = runCatching {
        val f = sideDetail(playable)
        if (!f.isFile) return@runCatching null
        val o = JSONObject(f.readText())
        Triple(
            o.optString("group").ifBlank { "未分组" },
            o.optInt("quality", 0),
            o.optString("duration"),
        )
    }.getOrNull()

    private fun writeDetailCache(playable: File, d: HanimeDetail, quality: Int, group: String): Boolean = runCatching {
        val o = JSONObject()
        o.put(
            "video",
            JSONObject().apply {
                put("id", d.video.id); put("url", d.video.url); put("title", d.video.title)
                put("duration", d.video.duration); put("likes", d.video.likes)
                put("views", d.video.views); put("brand", d.video.brand)
                // ★ 第 157 轮：必须存原始封面 URL，旧任务补封面时要用
                put("thumb", d.video.thumb)
                // ★ 第 157 轮：**单独存页面 URL**。                // 旧数据里 video.url 存的其实是下载直链（.mp4?secure=…），                // 拿它去「重抓详情」必然失败（那不是详情页）。这里存真正可回访的页面地址。                put("pageUrl", d.video.url.takeIf { it.startsWith("http") && !it.contains("vdownload.") && !it.endsWith(".mp4") }.orEmpty())
            },
        )
        o.put("description", d.description)
        o.put("tags", JSONArray(d.tags))
        o.put("sources", JSONArray(d.sources.map { JSONArray(listOf(it.first, it.second)) }))
        // 推荐位只留文字：离线时没有图床，缓存 URL 只会让介绍页挂一排「图载失败」的红字。
        o.put(
            "related",
            JSONArray(d.related.map { r ->
                JSONObject().apply {
                    put("id", r.id); put("url", r.url); put("title", r.title)
                    put("duration", r.duration); put("likes", r.likes); put("views", r.views); put("brand", r.brand)
                }
            }),
        )
        // 分组与质量档：参考 UI 的「未分组 · N 部影片」与卡片上的「720P | 257.0 MiB」都要它。
        o.put("group", group.ifBlank { "未分组" })
        o.put("quality", quality)
        o.put("fav", false); o.put("saved", false); o.put("saveListId", "")
        o.put("cachedAt", System.currentTimeMillis())
        val f = sideDetail(playable)
        f.parentFile?.mkdirs()
        f.writeText(o.toString())
        true
    }.getOrDefault(false)

    /**
     * 把封面字节落成本机副本（第 48 轮 bug1 的修复点之一）。
     *
     * **为什么要重试**：`HanimeClient.fetchThumbBytes` 在某个图床连续失败后会把该域
     * 冷 20 秒并直接返回失败（那是为整屏卡片的加载速度做的优化）。下载是一条**一次性**的
     * 后台路径，撞上冷却窗口就永久没有封面 —— 用户看到的就是「下了但没封面」。
     * 所以这里退避重试 3 次，并且**先等一下再试**（冷却窗口 20 s，用 8/16 s 两次退避跨过它）。
     *
     * 仍然失败就如实返回 false（调用方把结果记进任务状态），不假装成功。
     */
    private fun fetchCoverCache(playable: File, thumb: String): Boolean {
        // ★ 第 261 轮：已有封面绝不触碰（守卫下沉到本体——此前只在调用侧判缺，覆盖路径关死）
        if (sideCover(playable).isFile) return true
        if (thumb.isBlank()) return false
        val waits = longArrayOf(0L, 8_000L, 16_000L)
        for ((i, w) in waits.withIndex()) {
            if (w > 0) runCatching { Thread.sleep(w) }
            val (bytes, why) = runCatching { runBlocking { HanimeClient.fetchThumbBytes(thumb) } }
                .getOrElse { null to (it.message ?: it.toString()) }
            if (bytes != null && bytes.isNotEmpty()) {
                return runCatching {
                    val f = sideCover(playable)
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { it.write(bytes) }
                    true
                }.getOrDefault(false)
            }
            lastCoverError = why ?: "未取到"
            if (i == waits.lastIndex) break
        }
        return false
    }

    /**
     * 「本地下载」里点一条已下载的条目时用它取详情：
     * 有介绍页缓存就还原（封面换成 file:// 的本机副本、可播源换成 file:// 的本地清单），
     * 没有就退成一条「只有本机源」的最小详情 —— 绝不假装还能联网取回原来的介绍页。
     */
    /**
     * ★★★ 第 156 轮（用户报「旧下载的视频没有封面/标签/简介」，而新下的正常）：
     *
     * 根因：sidecar（.detail.json / .cover.jpg）是**下载完成那一刻**写的。
     * 历史上中断过又续传完成的旧任务，那一步可能压根没走到，于是这两个文件一直缺 ——
     * 界面只能退化成「只有本机源」的最小详情：通用简介文案 + 空封面 + 无标签。
     *
     * 之前只补了「DONE 那一刻」，但旧任务已经不会再被派发，那条路径永远走不到。
     * 所以这里再补一条：**打开详情页时按需补齐**。
     *
     * 实现要点：
     *  · 只在该任务仍记得原始详情（有 thumb/description/tags）时才补，否则补了也是空的；
     *  · 后台线程做，不阻塞界面（封面要联网取，可能慢）；
     *  · 幂等：sidecar 两个都在就直接返回，一次请求都不多发。
     */
    private fun ensureSidecarsAsync(playable: File) {
        runCatching {
            if (sideDetail(playable).isFile && sideCover(playable).isFile) return
            val job = synchronized(lock) {
                jobs.values.firstOrNull { j ->
                    _tasks.value.firstOrNull { t -> t.id == j.id }?.dest == playable.absolutePath
                }
            }
            if (job != null) {
                com.han1me.lab.LabLog.i("dl", "打开详情：内存有任务，后台补 sidecar")
                Thread({ runCatching { cacheSidecars(playable, job) } }, "hplay-sidecar-fill")
                    .apply { isDaemon = true }.start()
                return
            }
            // ---- 任务已被清理（旧下载）：只能靠 detail.json 里记下的线索自己救 ----
            val o = runCatching {
                val df = sideDetail(playable)
                if (!df.isFile) null else JSONObject(df.readText())
            }.getOrNull() ?: return
            val v = o.optJSONObject("video")
            val thumb = v?.optString("thumb").orEmpty()
            val pageUrl0 = v?.optString("pageUrl").orEmpty()
            val pageUrl = pageUrl0.ifBlank { v?.optString("url").orEmpty() }
                .takeIf { it.startsWith("http") && !it.contains("vdownload.") && !it.contains(".mp4?") }
                .orEmpty()
            com.han1me.lab.LabLog.i("dl", "旧任务补全：thumb=" + thumb.take(40) + " page=" + pageUrl.take(50))
            if (thumb.isNotBlank() && !sideCover(playable).isFile) {
                // ① 有封面 URL：直接取图
                val mini = HanimeDetail(
                    video = HanimeVideo("", pageUrl, v?.optString("title").orEmpty(), thumb, "", "", "", ""),
                    description = o.optString("description"),
                    tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                    sources = emptyList(), related = emptyList(),
                )
                val job2 = Job(id = -1L, detail = mini, quality = 0, url = pageUrl, native = false, group = "")
                Thread({ runCatching { cacheSidecars(playable, job2) } }, "hplay-cover-fill")
                    .apply { isDaemon = true }.start()
                return
            }
            // ② 连 thumb 都没记（旧版本写的 detail.json）：用页面 URL 重新抓一次详情
            if (pageUrl.isNotBlank() && pageUrl.startsWith("http")) {
                com.han1me.lab.LabLog.i("dl", "旧任务无 thumb，尝试按页面 URL 重抓详情")
                Thread({
                    runCatching {
                        val refetched = X99.boardPage(
                            HanimeVideo("", pageUrl, v?.optString("title").orEmpty(), "", "", "", "", ""),
                        )
                        if (refetched != null) {
                            val job3 = Job(id = -1L, detail = refetched, quality = 0, url = pageUrl, native = false, group = "")
                            cacheSidecars(playable, job3)
                            com.han1me.lab.LabLog.i("dl", "按页面 URL 重抓详情成功，已补 sidecar")
                        }
                    }
                }, "hplay-refetch-detail").apply { isDaemon = true }.start()
            }
        }
    }

    // ================================================================
    // 第 261 轮：封面自动扫补
    //
    // **唯一自动触发点 = 下载页点击「已下载」页签**（用户明令约束，触发器在 Ui 层的页签点击里）；
    // 手动「补全封面」按钮 = 兜底（忽略节流、如实回显）。其余任何路径零触发。
    // 每条跑 ensureSidecarsAsync 同款三级救援（内存任务 / sidecar thumb / pageUrl 重抓详情）；
    // 三重界限：fetchCoverCache 内置 exists 守卫 / 只挑无封面条目 / 只新增缺失文件。
    // ================================================================

    /** 扫补结果（手动按钮如实回显；ok=-1 表示已有扫补在进行；topReasons=失败原因 Top 摘要）。 */
    data class CoverSweepReport(val ok: Int, val noSrc: Int, val fail: Int, val topReasons: String = "")

    private val coverSweepRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val coverTriedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 扫补专用取图客户端（第 262 轮修「成功 0 失败 21」）：
     * 从 HanimeClient.http 派生（DoH/按域代理/cookie/NetGuard 全套继承），
     * 只放宽超时——thumbHttp 的 4s 连接超时是收藏页网格「快速失败」口径，
     * 而经内置节点取图实测要 3~15s（第 146 轮注释），6 路并发一撞就把整域
     * 连接超时 → 触发 thumbDeadUntil 封域 5 分钟 → 剩余任务全部秒失败。
     * 扫补是后台任务：**慢而成功 远好于 快而全灭**。
     */
    private val coverHttp by lazy {
        HanimeClient.http.newBuilder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 本轮扫补内已判死的图床域（只影响本轮，不污染收藏页的 thumbDeadUntil 状态）。 */
    private var coverSweepDeadHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private const val COVER_RETRY_INTERVAL = 3_600_000L   // 同一 thumb URL 至多每 60 分钟重试一次

    /** 自动扫补入口（已下载页签点击时调；防重入；无缺项零成本短路）。 */
    fun sweepMissingCoversAsync() {
        if (coverSweepRunning.get()) return
        if ((itemsCache ?: return).none { it.cover.isBlank() }) return
        Thread({ runCatching { sweepMissingCoversSync(false) } }, "hpla-cover-sweep")
            .apply { isDaemon = true }.start()
    }

    /**
     * 扫补本体（阻塞；手动按钮放 IO 线程调，force=true 忽略节流）。
     *
     * **第 262 轮重写（用户反馈「太慢、长时间没回应」）**：参考收藏页的封面加载方式
     * （Coil 异步并发、无串行退避），弃用 fetchCoverCache 的 0/8s/16s 逐张睡眠，
     * 改为 **6 路并发直取**：fetchThumbBytesSync（自带 Referer 学习/URL 变体/域冷却管理，
     * 与收藏页封面同一条管线），取到字节**直接写 sidecar 封面**——
     * 文件名与落点同下载时写的完全一致（hplay.cover.jpg / .cover.jpg），可扫描恢复。
     * 成功条目批量重建进快照（一次落盘、一次通知）。
     */
    fun sweepMissingCoversSync(force: Boolean, onProgress: ((Int, Int) -> Unit)? = null): CoverSweepReport {
        if (!coverSweepRunning.compareAndSet(false, true)) return CoverSweepReport(-1, -1, -1)
        var ok = 0; var noSrc = 0; var fail = 0
        coverSweepDeadHosts.clear()   // 本轮域死集合只对单次扫补生效
        try {
            // 缺失判定 = 缓存无封面 **或** 磁盘上封面文件真不在（外部删除后未校准的窗口也能补）
            val missing = itemsCache?.filter { it.cover.isBlank() || !sideCover(File(it.path)).isFile }
                ?: return CoverSweepReport(0, 0, 0)
            if (missing.isEmpty()) return CoverSweepReport(0, 0, 0)
            // 组任务：thumb 优先（sidecar 里下载时记录的原始封面 URL）；无 thumb 才用 pageUrl 重抓详情
            data class CoverTask(val item: DlItem, val thumb: String, val pageUrl: String)
            val tasks = ArrayList<CoverTask>()
            for (item in missing) {
                val playable = File(item.path)
                if (sideCover(playable).isFile) continue   // 双保险：已有封面绝不触碰
                val o = runCatching {
                    val df = sideDetail(playable)
                    if (df.isFile) JSONObject(df.readText()) else null
                }.getOrNull()
                val v = o?.optJSONObject("video")
                val thumb = v?.optString("thumb").orEmpty()
                // ★ 第 262 轮修（用户 224 条实测「失败 21」根因之一）：这批条目的 video.url /
                //   pageUrl 存的是**播放列表 m3u8**——拿它当详情页抓只会白跑（m3u8 里没有封面）。
                //   拒绝一切 m3u8/媒体形态的 pageUrl，落空就走历史匹配/如实计无来源。
                val pageUrl = v?.optString("pageUrl").orEmpty().ifBlank { v?.optString("url").orEmpty() }
                    .takeIf { it.startsWith("http") && !it.contains("vdownload.") && !it.contains(".mp4?") &&
                        !it.contains(".m3u8", true) }.orEmpty()
                // ★★★ 第 340 轮重构（用户报「下载页面的封面补全功能形同虚设」）：
                //
                // 老实现只认 sidecar 里的 thumb，没有 thumb 就只尝试「pageUrl 重抓详情」，
                // 而且 **重抓必须在 worker 里发网络请求**（每条约 1~3 s），6 路并发下 200 条要跑几分钟；
                // 更糟的是节流键用的是 thumb —— thumb 为空时这条节流形同不存在，
                // 每次点「补全封面」都把同一批必然失败的条目重跑一遍，用户看到的就是「点了没反应」。
                //
                // 现在三级线索一次排好，节流键统一用**锚点路径**（每条只试一次/小时）：
                //   ① thumb（sidecar 记录的原图地址）—— 一次 HTTP 直取，最快；
                //   ② pageUrl（真正的详情页）—— 重抓详情拿 thumb；
                //   ③ srcUrl（源页面 URL）作为 pageUrl 的兜底（老 sidecar 没有 pageUrl 字段）。
                // 三者都没有才算「无来源」，且如实计数。
                val throttleKey = item.anchorPath.ifBlank { item.path }
                val last = coverTriedAt[throttleKey] ?: 0L
                if (!force && System.currentTimeMillis() - last < COVER_RETRY_INTERVAL) continue
                coverTriedAt[throttleKey] = System.currentTimeMillis()
                val srcUrl = item.srcUrl.takeIf {
                    it.startsWith("http") && !it.contains(".m3u8", true) && !it.contains(".mp4?", true)
                }.orEmpty()
                val page2 = pageUrl.ifBlank { srcUrl }
                when {
                    thumb.isNotBlank() -> tasks.add(CoverTask(item, thumb, page2))
                    page2.isNotBlank() -> tasks.add(CoverTask(item, "", page2))
                    // 连页面地址都没有：仍排进队列，worker 里按标题去板块列表里找（最后一级救援）
                    else -> tasks.add(CoverTask(item, "", ""))
                }
            }
            if (tasks.isEmpty()) return CoverSweepReport(ok, noSrc, fail)
            // 6 路并发拉取（无串行睡眠；域冷却/Referer 学习由 fetchThumbBytesSync 内部管理）
            val whyCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
            val queue = java.util.concurrent.ConcurrentLinkedQueue<CoverTask>(tasks)
            val total = tasks.size
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val okA = java.util.concurrent.atomic.AtomicInteger(0)
            val failA = java.util.concurrent.atomic.AtomicInteger(0)
            val successAnchors = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val workers = (1..minOf(6, total)).map {
                Thread({
                    while (true) {
                        val task = queue.poll() ?: break
                        val playable = File(task.item.path)
                        if (sideCover(playable).isFile) { okA.incrementAndGet(); done.incrementAndGet(); continue }
                        var thumb = task.thumb
                        if (thumb.isBlank() && task.pageUrl.isNotBlank()) {
                            // 二级救援：重抓详情拿 thumb（在 worker 内串行，天然限速不砸站点）
                            val refetched = runCatching {
                                X99.boardPage(HanimeVideo("", task.pageUrl, task.item.title, "", "", "", "", ""))
                            }.getOrNull()
                            thumb = refetched?.video?.thumb.orEmpty()
                        }
                        if (thumb.isBlank()) {
                            // ★ 第 340 轮三级救援：连页面地址都没有（老 sidecar）时，
                            // 拿标题去**已缓存的板块列表**里找同名条目 —— 纯内存、零网络。
                            // 找到就用它的封面地址；找不到才如实计失败，不再「点了没反应」。
                            thumb = runCatching { X99.findThumbByTitle(task.item.title) }.getOrDefault("")
                        }
                        var got = false
                        if (thumb.isNotBlank()) {
                            val (bytes, why2) = fetchCoverBytes(thumb, task.pageUrl, coverSweepDeadHosts)
                            if (bytes == null) whyCount[why2] = (whyCount[why2] ?: 0) + 1
                            if (bytes != null && bytes.isNotEmpty()) {
                                got = runCatching {
                                    val f = sideCover(playable)              // 只新增缺失文件
                                    f.parentFile?.mkdirs()
                                    FileOutputStream(f).use { it.write(bytes) }
                                    sideCover(playable).isFile               // 落地校验
                                }.getOrDefault(false)
                            }
                        }
                        if (got) { okA.incrementAndGet(); successAnchors.add(task.item.anchorPath.ifBlank { task.item.path }) }
                        else failA.incrementAndGet()
                        val d = done.incrementAndGet()
                        if (onProgress != null && d % 5 == 0) runCatching { onProgress(d, total) }
                    }
                }, "hpla-cover-sweep").apply { isDaemon = true }
            }
            workers.forEach { it.start() }
            workers.forEach { it.join() }
            ok = okA.get(); fail = failA.get()
            // 成功条目批量重建进快照（重建在锁外做 IO；落盘一次；通知一次）
            if (successAnchors.isNotEmpty()) {
                val rootPaths = scanRoots().map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }.toHashSet()
                val rebuilt = ArrayList<SnapEntry>()
                for (a in successAnchors) {
                    val built = buildEntryItem(File(a), rootPaths) ?: continue
                    rebuilt.add(toSnapEntry(built, rootCanonOf(built)))
                }
                synchronized(snapshotLock) {
                    var next = snapshotEntries ?: loadSnapshot()?.first ?: return@synchronized
                    for (e2 in rebuilt) next = next.filterNot { it.anchorPath == e2.anchorPath } + e2
                    snapshotEntries = next
                    persistSnapshot(next, snapshotRootsCanon())
                    applySnapshot(next)
                }
                notifyItemsChanged()
            }
            val top = whyCount.entries.sortedByDescending { it.value }.take(2).joinToString("、") { it.key + "×" + it.value }
            val reasons = if (top.isEmpty()) "" else "（" + top + "）"
            com.han1me.lab.LabLog.i("dl", "封面扫补(force=" + force + ")：成功 " + ok + " 无来源 " + noSrc +
                " 失败 " + fail + "，任务 " + total + "，并发 6" + (if (reasons.isEmpty()) "" else "，失败原因: " +
                whyCount.entries.sortedByDescending { it.value }.joinToString(" ") { it.key + "×" + it.value }))
            return CoverSweepReport(ok, noSrc, fail, reasons)
        } finally {
            coverSweepRunning.set(false)
        }
    }

    /**
     * 扫补专用取图（第 262 轮）：不走 fetchThumbBytesSync——thumbHttp 的 4s 连接超时 +
     * 连接层失败封域 5 分钟是收藏页网格的快速失败口径，扫补场景下 6 路并发一撞就全灭
     * （真机 224 条实测「成功 0 失败 21」的根因）。这里用耐心客户端（coverHttp）+
     * 三段 Referer 尝试：详情页 → 图床根 → 无 Referer；防 HTML/小正文守卫照抄
     * thumbAttempt 的既判；连接层失败只标记**本轮**域死（开头与每段尝试前检查，
     * 跳过同域其余写法/任务），不污染收藏页共享的 thumbFailCount/thumbDeadUntil。
     * @return 字节 + 失败原因（失败原因原样上报，扫补汇总进日志便于诊断）
     */
    private fun fetchCoverBytes(url: String, refererPage: String, deadHosts: MutableSet<String>): Pair<ByteArray?, String> {
        val host = hostOf(url)
        if (host.isNotEmpty() && coverSweepDeadHosts.contains(host)) {
            return null to "图床 " + host + " 本轮已判死（同域其余任务跳过）"
        }
        val refs = ArrayList<String>()
        if (refererPage.isNotBlank()) refs.add(refererPage)
        if (host.isNotEmpty()) refs.add("https://" + host + "/")
        refs.add("")
        var why = "未尝试"
        for (ref in refs.distinct()) {
            if (host.isNotEmpty() && coverSweepDeadHosts.contains(host)) {
                return null to "图床 " + host + " 本轮已判死（同域其余写法跳过）"
            }
            val rb = okhttp3.Request.Builder().url(url).header("User-Agent", HanimeClient.UA)
            if (ref.isNotBlank()) rb.header("Referer", ref)
            val r = runCatching {
                coverHttp.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        null to ("HTTP " + resp.code + (if (ref.isNotBlank()) "(带Referer)" else "(无Referer)"))
                    } else {
                        val bytes = resp.body?.bytes()
                        if (bytes == null || bytes.isEmpty()) {
                            null to "空正文"
                        } else if (bytes.size < 64) {
                            null to "正文太小(" + bytes.size + "B)"
                        } else {
                            val ctype = resp.header("Content-Type").orEmpty().lowercase()
                            val isHtml = ctype.contains("text/html") ||
                                (bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == '!'.code.toByte())
                            if (isHtml) null to "不是图片正文" else bytes to ""
                        }
                    }
                }
            }.getOrElse { null to "请求异常 " + (it.message?.take(48) ?: "") }
            val bytes = r.first
            why = r.second
            if (bytes != null) return bytes to ""
            // 连接层失败：本轮内同域其余任务不再空耗（20s 超时 × N 张会拖死整轮）
            if (why.startsWith("请求异常") || why.contains("timeout", true) ||
                why.contains("Failed to connect", true) || why.contains("Unable to resolve", true)) {
                if (host.isNotEmpty()) coverSweepDeadHosts.add(host)
            }
        }
        return null to why
    }

    private fun upsertBuiltEntry(built: DlItem) {
        val entry = toSnapEntry(built, rootCanonOf(built))
        synchronized(snapshotLock) {
            val cur = snapshotEntries ?: loadSnapshot()?.first ?: return
            val kept = cur.filterNot { it.anchorPath == entry.anchorPath }
            val next = kept + entry
            snapshotEntries = next
            persistSnapshot(next, snapshotRootsCanon())
            applySnapshot(next)
        }
    }

    fun detailFor(playablePath: String): HanimeDetail {
        val pf = File(playablePath)
        // 第 156 轮：sidecar 缺失就后台补齐（旧任务也能修好）
        ensureSidecarsAsync(pf)
        val cover = sideCover(pf).takeIf { it.isFile }
        val coverUrl = cover?.toURI()?.toString() ?: ""
        val cached = runCatching {
            val f = sideDetail(pf)
            if (!f.isFile) return@runCatching null
            val o = JSONObject(f.readText())
            val v = o.getJSONObject("video")
            val video = HanimeVideo(
                id = v.optString("id"), url = v.optString("url"),
                title = v.optString("title").ifBlank { prettyName(pf.name) },
                thumb = coverUrl,
                duration = v.optString("duration"), likes = v.optString("likes"),
                views = v.optString("views"), brand = v.optString("brand").ifBlank { "本机" },
            )
            val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                .orEmpty().ifEmpty { listOf("本机") }
            val rel = o.optJSONArray("related")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { r ->
                        HanimeVideo(
                            r.optString("id"), r.optString("url"), r.optString("title"), "",
                            r.optString("duration"), r.optString("likes"), r.optString("views"), r.optString("brand"),
                        )
                    }
                }
            }.orEmpty()
            HanimeDetail(
                video = video,
                description = o.optString("description").ifBlank { "已下载到本机的副本。" } +
                    "\n\n（本机离线副本：介绍页与封面已随下载一并缓存）",
                tags = tags,
                sources = listOf(0 to File(playablePath).toURI().toString()),
                related = rel,
                isFav = false, isSaved = false, saveListId = "",
            )
        }.getOrNull()
        if (cached != null) return cached
        val name = prettyName(pf.parentFile?.name ?: pf.name)
        return HanimeDetail(
            video = HanimeVideo(
                id = "local:" + playablePath.hashCode(), url = "https://local-file.invalid/" + name,
                title = name, thumb = coverUrl, duration = "", likes = "", views = "", brand = "本机",
            ),
            description = "这是已下载到本机的副本，断网也能播。",
            tags = listOf("本机"),
            sources = listOf(0 to File(playablePath).toURI().toString()),
            related = emptyList(), isFav = false, isSaved = false, saveListId = "",
        )
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 调度器（第 49 轮改成**多任务并行**）。
     *
     * 上一版是"一个守护线程按队列串行执行"；用户要求"最大可同时下载五个视频"，
     * 所以这里换成一个固定线程池 + 一个派发循环：
     *  · 池大小 = [MAX_PARALLEL_TASKS]（5）；
     *  · 派发循环只是**看有没有空位 + 有没有 QUEUED 的任务**，把任务丢进池里；
     *  · 池满时等 250 ms 再看（不做忙等）；
     *  · 所有任务都结束且没有 QUEUED 时收工（通知服务移除常驻通知）。
     *
     * 为什么不用 **[concurrency] 那个数**当池大小：那是**单个任务内部的分片并发**，
     * 两件事不能共用一个数字 —— 5 个任务 × 每个 4 分片并发 = 20 条连接，
     * 已经接近 OkHttp 调度器上限（maxRequests=64 够用，但站点侧更容易被风控）。
     */
    private fun startWorkerLocked() {
        val p = pool
        if (p != null && !p.isShutdown) return
        pool = Executors.newFixedThreadPool(MAX_PARALLEL_TASKS).also { ex ->
            dispatcher = Thread({ dispatchLoop(ex) }, "hplay-download-dispatch").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** 派发循环：有空位就派任务，没有就等一会；全部结束就收工。 */
    private fun dispatchLoop(ex: java.util.concurrent.ExecutorService) {
        while (!ex.isShutdown) {
            var job: Job? = null
            synchronized(lock) {
                // 第 53 轮：占位判据从「数 RUNNING」改成「数已派发的」——
                // 前者有窗口期（RUNNING 在 runJob 内部才设），会让同一任务被派发多次。
                if (dispatched.size < MAX_PARALLEL_TASKS) {
                    for (t in _tasks.value) {
                        if (t.status != DlStatus.QUEUED) continue
                        val j = jobs[t.id]
                        if (j == null) {
                            patch(t.id) { it.copy(status = DlStatus.CANCELLED, phase = "已取消") }
                            break
                        }
                        // ★ 原子占位：占不到说明已被派发，跳过
                        if (!dispatched.add(t.id)) continue
                        job = j
                        break
                    }
                }
            }
            val j = job
            if (j != null) {
                runCatching { ex.execute { runJob(j) } }
                continue
            }
            // 没有可派的任务：看是否还有在跑的；没有就收工。
            //
            // ★★★ 第 344 轮（全量核查 · P1）：**收工判据必须包含"已派发但还没起跑"**。
            //
            // 原来只看 _tasks 里有没有 RUNNING / QUEUED。但 runJob 一开始是先
            // patch(RUNNING) 再干活，而 dispatched.add(id) 发生在**派发时**——
            // 中间那段窗口里 _tasks 还是 QUEUED（会被算进 alive，没问题）；
            // 真正的问题是相反方向：某个任务的 _tasks 已是 PAUSED/CANCELLED，
            // 但它还在 ex 的线程队列里排着（dispatched 里有、池子满）——
            // 这里判 alive=false → ex.shutdown() → 那个还没起跑的 runJob 被丢弃，
            // 且 pool 已经 shutdown，下一次 enqueue 的 startWorkerLocked() 看到
            // pool != null && !isShutdown 为 **false** 才重建 —— 这一步是对的，
            // 但**已经 enqueue 进 _tasks 的那条任务没有派发者**了（新池子由下一次入队才建）
            // ⇒ 它永远停在「排队中」。
            //
            // 现在：把 dispatched 也算进存活判据，并且**先置 pool=null 再 shutdown**，
            // 让 startWorkerLocked 能可靠重建（否则下次入队看到旧池子还"没 shut"直接 return）。
            val alive = synchronized(lock) {
                dispatched.isNotEmpty() || _tasks.value.any {
                    it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED
                }
            }
            if (!alive) {
                runCatching { onQueueDrained?.invoke() }
                synchronized(lock) { if (pool === ex) pool = null }
                runCatching { ex.shutdown() }
                return
            }
            runCatching { Thread.sleep(250) }
        }
    }

    private fun runJob(job: Job) {
        val prog = Prog().also { it.startedAt = System.currentTimeMillis() }
        synchronized(lock) { progs[job.id] = prog }
        patch(job.id) { it.copy(status = DlStatus.RUNNING, phase = "取清单") }
        // ★ 第 261 轮：任务级自动重试（用户需求「遇到失败能一直重试下载到完成」）。
        // 旧实现失败即 FAILED 等手动重试；现在失败后在本循环里按轮退避自动重跑：
        // 60s→2min→5min→10min→30min 封顶、无限轮；每轮 transfer 都重新拉播放列表（新令牌）。
        // 等待期 500ms 步进检查取消/暂停令牌，用户随时接管。清理语义（finally）不变。
        try {
            while (true) {
                try {
                    val playable = transfer(job)
                    patch(job.id) {
                        it.copy(status = DlStatus.DONE, phase = "已完成", dest = playable, etaSec = 0L, speedBps = 0.0)
                    }
            // ★★★ 第 155 轮（真机：续传复用下载的视频「封面/标签/简介全没有」，而新下的正常）：
            //
            // 续传命中时会**复用已有文件**，此时下载循环是「已完整 -> 直接跳过」，
            // 有可能整条路径根本没经过缓存那一步（历史中断的旧任务尤其如此），
            // 于是 .detail.json / .cover.jpg 两个 sidecar 一直是缺的 ——
            // 界面读不到简介与封面，看起来就是「只有视频没有信息」。
            //
            // 这里在标记 DONE 之后**幂等地补一次**：sidecar 已存在就完全跳过，
            // 缺了才补写。放在后台线程里做（本函数已在 worker 线程）。
                    runCatching {
                        val pf = File(playable)
                        if (!sideDetail(pf).isFile || !sideCover(pf).isFile) {
                            com.han1me.lab.LabLog.i("dl", "DONE 后补 sidecar：detail=" + sideDetail(pf).isFile + " cover=" + sideCover(pf).isFile)
                            cacheSidecars(pf, job)
                        }
                    }
                    // 第 60 轮：**作废缓存放在状态写成 DONE 之后**（防「下完找不到」窗口）。
                    // 第 258 轮：不再整表作废——把刚完成的成品直接 upsert 进快照，缓存立即就位。
                    runCatching { snapshotUpsertForArtifact(File(playable)) }
                    bytesCache = -1L
                    if (itemsCache == null) ensureFreshAsync()   // 兜底：快照路径失败也不丢"完成即见"
                    synchronized(lock) { autoRetryRounds.remove(job.id) }
                    return
                } catch (c: Cancelled) {
                    // 区分"用户暂停"与"用户取消"：前者要保留进度（可续传），后者才真的是取消。
                    if (synchronized(lock) { job.id in paused }) {
                        patch(job.id) { it.copy(status = DlStatus.PAUSED, phase = "已暂停", speedBps = 0.0) }
                    } else {
                        patch(job.id) { it.copy(status = DlStatus.CANCELLED, phase = "已取消", speedBps = 0.0) }
                    }
                    return
                } catch (t: Throwable) {
                    // 第 261 轮：不再 FAILED 等手动——任务级自动重试（退避见 autoBackoffMs，无限轮）；
                    // 原因保留在 error 字段；failedJobs/手动重试按钮留给旧会话遗留的 FAILED（正常流程不再产生）。
                    val rounds = (autoRetryRounds[job.id] ?: 0) + 1
                    autoRetryRounds[job.id] = rounds
                    val wait = autoBackoffMs(rounds)
                    val why = (t.message ?: t.toString()).take(160)
                    patch(job.id) {
                        it.copy(
                            status = DlStatus.RUNNING,
                            phase = "自动重试(第 " + rounds + " 轮) 等待 " + (wait / 1000) + "s",
                            error = why, speedBps = 0.0, etaSec = -1L,
                        )
                    }
                    com.han1me.lab.LabLog.i("dl", "自动重试：第 " + rounds + " 轮，等 " + (wait / 1000) + "s，原因=" + why)
                    var waited = 0L
                    while (waited < wait) {
                        if (isCancelled(job.id)) {
                            // 等待期被取消/暂停：与传输期同语义（paused 在 ⇒ 暂停，否则取消）
                            if (synchronized(lock) { job.id in paused }) {
                                patch(job.id) { it.copy(status = DlStatus.PAUSED, phase = "已暂停", speedBps = 0.0) }
                            } else {
                                patch(job.id) { it.copy(status = DlStatus.CANCELLED, phase = "已取消", speedBps = 0.0) }
                            }
                            return
                        }
                        runCatching { Thread.sleep(500L) }
                        waited += 500L
                    }
                    patch(job.id) { it.copy(phase = "自动重试(第 " + rounds + " 轮) 重新开始") }
                    // 回到循环顶：transfer 重新拉播放列表（新令牌）
                }
            }
        } finally {
            synchronized(lock) {
                // 暂停是可续传的状态：任务对象与 job 都要留着，否则 resume() 无从下手。
                // 失败同样要留（存在 failedJobs 里，见上）。
                val keep = job.id in paused || failedJobs.containsKey(job.id)
                if (!keep) {
                    jobs.remove(job.id)
                    progs.remove(job.id)
                }
                cancelled.remove(job.id)
                if (keep) progs.remove(job.id)   // 进度快照留在 DlTask 上，Prog 可以丢
                dispatched.remove(job.id)        // 第 53 轮：释放派发占位
            }
        }
    }

    private fun isCancelled(id: Long) = synchronized(lock) { id in cancelled }

    private fun transfer(job: Job): String {
        val url = job.url
        com.han1me.lab.LabLog.i("dl", "派发任务 id=" + job.id + " url=" + url.take(90))
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val tail = path.substringAfterLast('/')
        // 只有「可能是清单」的地址才值得先取一次文本：
        //  ① 解析器亲眼验过是 HLS 的（X99.mediaIsHls）；② 后缀就是 .m3u8；
        //  ③ 无扩展名（肉视频那种 …/api/hls/<id>）。
        // 官方源的 mp4 走不到这里，所以不会为它多花一个请求。
        val maybePlaylist = runCatching { X99.mediaIsHls(url) }.getOrDefault(false) ||
            tail.endsWith(".m3u8", true) || (!tail.contains('.') && path.isNotEmpty())
        if (!maybePlaylist) return downloadProgressive(job, url)

        var eff = url
        var text = fetchText(job.native, eff, job.pageUrl)
        // master 清单：挑带宽最高的一档，回头再取一次真清单（与桌面版同一策略）
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            var bestBw = -1L
            var bestRel: String? = null
            for (i in lines.indices) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                val bw = bwRe.find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                if (bw > bestBw) { bestBw = bw; bestRel = next.trim() }
            }
            val rel = bestRel
            if (rel != null) {
                eff = URI(url).resolve(rel).toString()
                patch(job.id) { it.copy(phase = "取最高档清单") }
                text = fetchText(job.native, eff, job.pageUrl)
            }
        }
        if (text.contains("#EXTM3U")) {
            // 闸门：诱饵清单在这里被挡住，绝不写一个「看起来下好了」的短文件。
            if (!playlistLooksReal(text)) {
                val n = text.lines().count { it.isNotBlank() && !it.startsWith("#") }
                throw IllegalStateException("站点回的是诱饵清单（只有 " + n + " 段），已拒绝下载，避免得到一个只有几秒的假成品")
            }
            return downloadHls(job, eff, text)
        }
        if (!Regex("""\.(mp4|mkv|webm|ts)""", RegexOption.IGNORE_CASE).containsMatchIn(path)) {
            throw IllegalStateException("这条地址既不是媒体清单也不是可直下的文件，不假装下载成功")
        }
        return downloadProgressive(job, url)
    }

    /**
     * **单文件下载：分段并发**（第 132 轮，用户要求「下载速度最大化，保守稳定提升」）。
     *
     * 真机实测（2026-10-28，设备侧 curl，同一部 12.4 MB 的 mp4）：
     *   1 路 0.58 MB/s / 2 路 0.77 / 4 路 1.67 / **8 路 3.32** / 16 路 3.54（16 路已出现 0 速失败路）。
     * 结论：**8 路是最佳点**，比单连接快 5.7 倍；再高只多 6% 且不稳。
     *
     * 保守策略（不给服务器压力、也不引入新失败面）：
     *   ① 先发一个 **Range: bytes=0-0** 探测：只有回 **206 + Content-Range** 才说明真支持分段，否则走原单连接 pump；
     *   ② 分 N 段并发，**每段各自 Range 落盘**，最后按顺序拼成一个文件；
     *   ③ 任一子段失败 -> 整条任务退化为原 pump 单连接（不产生半成品）；
     *   ④ 服务器不给 Content-Length 或文件太小（<8 MB）都不分段 —— 小文件并发反而慢。
     */
    private fun downloadProgressive(job: Job, url: String): String {
        // ★★★ 第 150 轮修复（用户报「恢复后是重新下载，没保存进度」）：
        //
        // 原来这里无条件用 `当前时间戳` 生成新文件名 ——
        // 于是「重启后恢复」拿到的是一条全新路径，之前的 .part / .segN 全都对不上，
        // 只能从零开始下。任务能恢复、进度却清零，就是这么来的。
        //
        // 现在：**优先沿用持久化里记下的 dest**。只要那个文件（或它的 .part 残留）还在，
        // 就继续用同一路径，pump / 分段逻辑里的「Range: bytes=already-」才能真正接上。
        val persisted = _tasks.value.firstOrNull { it.id == job.id }?.dest.orEmpty()
        // ★★★ 第 153 轮真机定位（官方源 hanime 下载「恢复后从头开始」的真因）：
        //
        // 官方源给的是**分段 MP4**（日志：vdownload.hembed.com/408954-1080p.mp4?secure=…），
        // 走的就是本函数。而分段下载期间磁盘上只有 .segN 文件 ——
        // out 要等全部分段合并成功才存在，.part 也要到合并那一步才创建。
        //
        // 原来的复用判据只检查 out / .part，于是「下到一半退出」时两者都不存在
        // -> reuse=false -> 又生成一个带新时间戳的文件名 -> 旧的 .segN 全部失联，
        // 只能从第 1 段重下。任务能恢复、进度却清零，正是这么来的。
        //
        // 现在把 .seg0 也纳入判据（分段命名是 <out>.seg0/.seg1/…，有 seg0 就说明有可用分段）。
        val reuse = persisted.isNotBlank() && File(persisted).let {
            it.exists() ||
                File(it.absolutePath + ".part").exists() ||
                File(it.absolutePath + ".seg0").exists()
        }
        val out = if (reuse) File(persisted) else File(saveDir(), slug(job.detail.video.title) + "_" + System.currentTimeMillis() + ".mp4")
        val prog = progOf(job.id)
        com.han1me.lab.LabLog.i("dl", (if (reuse) "续传命中，复用 " else "新任务，创建 ") + out.absolutePath)
        if (reuse) patch(job.id) { it.copy(phase = "续传中") }
        patch(job.id) { it.copy(phase = "下载中", dest = out.absolutePath) }
        // ① 探测是否支持 Range 分段
        //
        // ★★★ 第 350 轮（用户报「优化整体视频下载速度，尤其是 han」）：
        // **官方源也走分段**。
        //
        // 这里原来写着 `if (!job.native)` —— 也就是只有三次元板块（xchina/MissAV…）
        // 才做 Range 探测，**官方源 hanime 永远走单连接 pump**。
        // 而官方源的媒体是 `vdownload.hembed.com` 上的大 mp4（几百 MB 很常见），
        // 单连接正是「官方源下载慢」的直接原因：设备侧实测单连接 0.58 MB/s，
        // 8 路分段 3.32 MB/s（见下面 downloadProgressive 的实测表）。
        //
        // 当初加这个判断的理由是「怕官方源不支持 Range」—— 但探测本身就是
        // bytes=0-0 的一发小请求，不支持就自动回落 pump（下面 okPipe=false 那条路），
        // 零风险。真机 curl 实测官方 mp4 回 206 + Content-Range，完全支持。
        var segOk = false
        var totalLen = -1L
        runCatching {
            val rb = req(job.native, url, job.pageUrl).header("Range", "bytes=0-0")
            mediaHttp.newCall(rb.get().build()).execute().use { resp ->
                val cr = resp.header("Content-Range").orEmpty()
                if (resp.code == 206 && cr.contains("/")) {
                    totalLen = cr.substringAfterLast("/").trim().toLongOrNull() ?: -1L
                    segOk = totalLen >= 8L * 1024 * 1024
                }
            }
        }
        if (segOk && totalLen > 0) {
            // ★ 第 133 轮（分析 1DM 下载器 APK 后引入）：**按域记忆最佳分段数**。
            //
            // 1DM 的核心设计（从它的 SQLite schema 读出来的，见报告）：
            //   · downloads.threadnum       —— 每个任务记住「这条链接用几段」
            //   · threads(start,end,finished,position) —— **每段独立记录进度**，可分段级续传
            //   · domain_part_limit(domain, part_limit, use_for_subdomain) —— **按域名记最佳段数**
            //   · downloads.has_byte_range / threadnum 决定「这条链接能不能拆」
            // 它把「段数」当成**可学习、可记忆的每域参数**，而不是全局常数。
            //
            // 我们原来的实现是全局固定 8 段。真机实测同一部片子：0.58(1路) / 1.67(4路) / 3.32(8路) / 3.54(16路)，
            // 但不同 CDN 的最优点不同 —— 有的站 4 路就够、8 路反而掉到 0 速。
            // 所以照 1DM 的做法：**记住每个域上次表现好的段数**，下次直接用它，失败再降级。
            val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
            val learned = segLimitByHost[host] ?: CONC_DEFAULT
            // 第 152 轮修（用户要求「下载尽量占满带宽」）：
            // 这里原来硬性 coerceIn(4, 8) —— 即使用户把并发设成 16，单文件下载也只跑 8 路，
            // 而 CONC_DEFAULT 早已是 16、连接池也已放大到 64/48，8 路明显吃不满带宽。
            // 现在跟随 CONC_DEFAULT（仍保留 4 的下限与 16 的上限，以及按域失败自动降级）。
            val n = learned.coerceIn(4, CONC_DEFAULT)
            patch(job.id) { it.copy(phase = "分段并发(" + n + " 路)") }
            val okPipe = runCatching {
                val chunk = totalLen / n
                val tmp = File(out.absolutePath + ".part")
                val parts = ArrayList<File>()
                val done = java.util.concurrent.atomic.AtomicLong(0)
                for (i in 0 until n) {
                    if (isCancelled(job.id)) throw Cancelled()
                    val from = i * chunk
                    val to = if (i == n - 1) totalLen - 1 else (from + chunk - 1)
                    val pf = File(out.absolutePath + ".seg" + i)
                    parts.add(pf)
                    // ★ 第 150 轮修：**段级续传**（照 1DM 的 threads(position) 设计）。
                    // 旧代码只要长度不匹配就 pf.delete() —— 暂停/重启后已下的部分全丢。
                    // 现在：段完好就跳过；段不完整就从**已下字节**处继续 Range。
                    val segLen = to - from + 1
                    if (pf.exists() && pf.length() == segLen) {
                        done.addAndGet(pf.length()); continue
                    }
                    val segHave = if (pf.exists()) pf.length() else 0L
                    if (segHave > segLen) { runCatching { pf.delete() } }
                    val segFrom = from + (if (pf.exists()) pf.length() else 0L)
                    if (segFrom > from) done.addAndGet(pf.length())
                    // ★★★ 第 351 轮（用户报「han 视频下载速度不稳定，且感觉没跑满带宽」）：**段级重试**。
                    //
                    // 原来这里是**裸一发**：任一段回非 206 / 断流 / 超时，就 throw，
                    // 而这个 throw 一路冒到外层的 `okPipe` 判定 —— 于是**整条任务**放弃分段，
                    // 退回单连接 pump。真机实测的单连接只有 **0.60 MB/s**，分段 16 路是 **3.0~5.2 MB/s**，
                    // 也就是说「16 段里有 1 段抖一下」= 全片速度掉到 1/6，用户感受就是
                    // 「有时候很快、有时候慢，跑不满带宽」。
                    //
                    // 现在：每段**独立重试 4 次**（指数退避 300/600/1200ms），
                    // 只有这一段的 4 次都失败才向上抛（那时确实该退回单连接）。
                    // 续传天然可用：段文件已写多少，下一次就从 `bytes=segFrom+已有-` 接着要。
                    var segTry = 0
                    var segErr: Throwable? = null
                    while (segTry < 4) {
                        segTry++
                        if (isCancelled(job.id)) throw Cancelled()
                        val have2 = if (pf.exists()) pf.length() else 0L
                        val from2 = from + have2
                        if (from2 > to) break
                        try {
                            val rb = req(job.native, url, job.pageUrl)
                                .header("Range", "bytes=" + from2 + "-" + to)
                            mediaHttp.newCall(rb.get().build()).execute().use { resp ->
                                if (resp.code != 206) {
                                    throw IllegalStateException("分段 " + i + " 回 " + resp.code + "（非 206）")
                                }
                                val body = resp.body ?: throw IllegalStateException("分段 " + i + " 空响应")
                                body.byteStream().use { ins ->
                                    // append = 段内已有字节 > 0（续传）
                                    java.io.FileOutputStream(pf, have2 > 0).use { os ->
                                        val buf = ByteArray(1 shl 18)
                                        while (true) {
                                            if (isCancelled(job.id)) throw Cancelled()
                                            val r = ins.read(buf)
                                            if (r <= 0) break
                                            os.write(buf, 0, r)
                                            val d = done.addAndGet(r.toLong())
                                            prog.done.set(d)
                                            prog.total = totalLen
                                            val now = System.currentTimeMillis()
                                            prog.bps = prog.speed(now, d)
                                            prog.eta = prog.bytesEta()
                                            publish(job.id)
                                        }
                                    }
                                }
                            }
                            segErr = null
                            break
                        } catch (c: Cancelled) {
                            throw c
                        } catch (t: Throwable) {
                            segErr = t
                            // 这一段的第 n 次失败：退避后从「已写字节」续传，不丢已下的部分。
                            runCatching {
                                com.han1me.lab.LabLog.i(
                                    "dl",
                                    "分段 " + i + " 第 " + segTry + "/4 次失败（" +
                                        (t.message ?: t.javaClass.simpleName) + "），退避后续传",
                                )
                            }
                            if (segTry < 4) {
                                // 300 / 600 / 1200ms；被取消时 sleep 会抛 InterruptedException，走上面的 Cancelled
                                Thread.sleep(300L shl (segTry - 1))
                            }
                        }
                    }
                    if (segErr != null) throw segErr!!
                }
                // 合并（按段序）
                java.io.FileOutputStream(tmp).use { os ->
                    for (pf in parts) pf.inputStream().use { it.copyTo(os) }
                    os.flush()
                }
                if (tmp.length() != totalLen) throw IllegalStateException("合并后长度 " + tmp.length() + " != " + totalLen)
                // 分段全部成功 -> 记住该域这个段数可用（照 1DM 的 domain_part_limit 思路）
                runCatching { segLimitByHost[host] = n; segFailByHost.remove(host) }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) throw IllegalStateException("重命名失败")
                parts.forEach { runCatching { it.delete() } }
                true
            }.getOrDefault(false)
            if (okPipe) {
                patch(job.id) { it.copy(doneBytes = prog.done.get(), speedBps = 0.0, etaSec = 0L) }
                cacheSidecars(out, job)
                invalidateSize()
                return out.absolutePath
            }
            // ③ 任何一步不行 -> 清干净，退回单连接（不产生半成品）
        }
        pump(job.id, job.native, url, out, job.pageUrl) { d, total, now ->
            prog.done.set(d)
            if (total >= 0) prog.total = total
            prog.bps = prog.speed(now, d)
            prog.eta = prog.bytesEta()
            publish(job.id)
        }
        patch(job.id) { it.copy(doneBytes = prog.done.get(), speedBps = 0.0, etaSec = 0L) }
        cacheSidecars(out, job)
        invalidateSize()
        return out.absolutePath
    }

    /** 一个分片任务。 */
    private class Seg(val index: Int, val url: String, val sec: Double, val name: String)

    /**
     * HLS 下载。
     *
     * **第 50 轮改成「不分片」**（用户要求「下载视频不分片，下载一整个视频文件方便存」）：
     * 上一版把分片原样留在目录里、清单就地改写成 index.m3u8 + 相对名，用户看到的是一堆 .ts，
     * 想整份拷走很不方便。
     *
     * 现在：分片仍然**并发下载**（速度不变），但落地时**按顺序合并成一个单文件**，
     * 目录里只留那一个文件 + sidecar。合并可行性是实测过的：
     *  · 裸 TS（188 字节包、每片长度都是 188 整数倍）→ 顺序拼接，包网格保持；
     *  · 站点包装过的分片（首字节不是 0x47）→ 同样顺序拼接（它们本来就是完整片段）；
     *  · fMP4（有 #EXT-X-MAP）→ 把 init 段放在最前面再拼分片；
     *  · AES-128 加密的 → **仍然落成目录 + 清单**（加密分片拼起来也解不开，会得到一个坏文件），
     *    并如实告知用户这一条是加密流、只能保持分片形态。
     *
     * 合并是**流式**的（逐片追加，不把整片读进内存），所以对大文件也安全。
     */
    /** 从持久化的 dest 推出工作目录（HLS 存的是 <work>/index.m3u8；普通文件存 .mp4）。 */
    private fun dirOf(path: String): File {
        val f = File(path)
        return if (f.isDirectory) f else (f.parentFile ?: f)
    }

    private fun downloadHls(job: Job, url: String, text: String): String {
        val prog = progOf(job.id)
        // ★★★ 第 150 轮修复（用户报「恢复后重新下载、进度没保存」）：
        //
        // HLS 这条路原来是**每次都新建一个带时间戳的目录**：
        //   MIDA-823_xxx_1793462223655/  ← 23:58
        //   MIDA-823_xxx_1793462210318/  ← 23:57
        //   ...
        // 于是每次重启恢复都落到一个全新空目录，之前下好的分片一个都用不上，
        // 只能从第 1 片重下 —— 「任务回来了但进度清零」就是这么来的。
        //
        // 现在：dest 记的是 `<work>/index.m3u8`，只要那个目录还在就直接复用，
        // 已有的分片靠下面「分片落地即跳过」的逻辑自动续传。
        val persistedDest = _tasks.value.firstOrNull { it.id == job.id }?.dest.orEmpty()
        val reuseDir = persistedDest.isNotBlank()
            .let { dirOf(persistedDest) }
            .takeIf { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
        val work = reuseDir ?: File(saveDir(), slug(job.detail.video.title) + "_" + System.currentTimeMillis())
        work.mkdirs()
        com.han1me.lab.LabLog.i("dl", (if (reuseDir != null) "续传命中，复用目录 " else "新任务，创建目录 ") + work.absolutePath)
        hideFromGallery(work)   // 中间目录也别让相册扫描器进去
        val lines = text.lines().toMutableList()

        val exts = lines.filter { it.startsWith("#EXTINF") }
            .mapNotNull { extinfRe.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        prog.mediaTotal = exts.sum()
        patch(job.id) {
            it.copy(phase = "下载中", mediaTotalSec = prog.mediaTotal, dest = File(work, "index.m3u8").absolutePath)
        }

        // 加密流：保持分片形态（拼起来也解不开）
        val encrypted = lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }

        // ---- 密钥与初始化段：先取，并就地改写成本地名 ----
        var keyIdx = 0
        var mapIdx = 0
        var initName: String? = null
        for (i in lines.indices) {
            if (isCancelled(job.id)) throw Cancelled()
            val l = lines[i]
            if (l.startsWith("#EXT-X-KEY") && l.contains("URI=\"")) {
                val raw = l.substringAfter("URI=\"").substringBefore('"')
                val keyUrl = URI(url).resolve(raw).toString()
                val name = if (keyIdx == 0) "key.bin" else "key" + keyIdx + ".bin"
                keyIdx++
                patch(job.id) { it.copy(phase = "取密钥") }
                pump(job.id, job.native, keyUrl, File(work, name), job.pageUrl) { d, _t, now ->
                    prog.done.addAndGet(d)
                    prog.bps = prog.speed(now, prog.done.get())
                    publish(job.id)
                }
                lines[i] = l.replaceFirst("URI=\"" + raw + "\"", "URI=\"" + name + "\"")
            } else if (l.startsWith("#EXT-X-MAP") && l.contains("URI=\"")) {
                val raw = l.substringAfter("URI=\"").substringBefore('"')
                val mapUrl = URI(url).resolve(raw).toString()
                val name = if (mapIdx == 0) "init.mp4" else "init" + mapIdx + ".mp4"
                if (mapIdx == 0) initName = name
                mapIdx++
                patch(job.id) { it.copy(phase = "取初始化段") }
                pump(job.id, job.native, mapUrl, File(work, name), job.pageUrl) { d, _t, now ->
                    prog.done.addAndGet(d)
                    prog.bps = prog.speed(now, prog.done.get())
                    publish(job.id)
                }
                lines[i] = l.replaceFirst("URI=\"" + raw + "\"", "URI=\"" + name + "\"")
            }
        }
        patch(job.id) { it.copy(phase = "下载中") }

        // ---- 收集分片 ----
        val segs = ArrayList<Seg>()
        var segIndex = 0
        for (i in lines.indices) {
            val l = lines[i]
            if (l.isBlank() || l.startsWith("#")) continue
            val name = "%05d.ts".format(segIndex)
            segs += Seg(segIndex, URI(url).resolve(l.trim()).toString(), exts.getOrElse(segIndex) { 0.0 }, name)
            lines[i] = name
            segIndex++
        }
        prog.segTotal = segs.size
        patch(job.id) { it.copy(segTotal = segs.size) }
        // 第 152 轮埋点：一条日志说清「是否续传、跳过了多少片」
        runCatching {
            val pre = segs.count { File(work, it.name).isFile }
            val preB = segs.filter { File(work, it.name).isFile }.sumOf { File(work, it.name).length() }
            com.han1me.lab.LabLog.i("dl", "HLS 开始 复用=" + (reuseDir != null) +
                " 目录=" + work.name.takeLast(24) + " 分片=" + segs.size +
                " 已存在=" + pre + " (" + (preB / 1048576) + "MB)")
        }

        // ---- 段级并发下载 ----
        // 第 58 轮：**自适应**。用户要求「不妨碍下载的情况下最大速度」，
        // 所以默认按用户设定的并发跑满；只有当这个域**刚被限流过**（429）时，
        // 才临时减半，等冷却过去自动恢复 —— 而不是把全局并发永久调低。
        val baseN = concurrency()
        val hostThrottledNow = segs.isNotEmpty() && hostThrottled(segs[0].url)
        val n = (if (hostThrottledNow) (baseN / 2).coerceAtLeast(1) else baseN)
            .coerceAtMost(segs.size.coerceAtLeast(1))
        if (hostThrottledNow) {
            patch(job.id) { it.copy(phase = "这个域被限流过，临时降到 " + n + " 并发") }
        }
        val segPool = Executors.newFixedThreadPool(n)
        val failure = AtomicReference<Throwable?>(null)
        try {
            for (s in segs) {
                segPool.execute {
                    if (failure.get() != null || isCancelled(job.id)) return@execute
                    try {
                        // 第 51 轮：这里以前是 done.set(base + d) —— 非原子「读-改-写」，
                        // 多个分片线程同时推进度时会互相覆盖（实测 20 线程丢失 82%）。
                        // 改成按差值原子累加。
                        val last = java.util.concurrent.atomic.AtomicLong(0)
                        pump(job.id, job.native, s.url, File(work, s.name), job.pageUrl) { d, total, now ->
                            prog.done.addAndGet(d - last.getAndSet(d))
                            prog.bps = prog.speed(now, prog.done.get())
                            val segBytes = if (total > 0) total.toDouble() else 1.0
                            prog.inflight[s.index] = s.sec * 1000.0 * (d / segBytes).coerceIn(0.0, 1.0)
                            prog.eta = prog.mediaEta(now)
                            publish(job.id)
                        }
                        prog.inflight.remove(s.index)
                        prog.segDoneMillis.addAndGet((s.sec * 1000.0).toLong())
                        // 第 53 轮：计数**封顶**。以前是裸 incrementAndGet，
                        // 万一某条分片被重跑（历史上就出过重复派发），
                        // 界面会出现「第 531/189 片」这种已完成数超过总数的荒谬显示。
                        // 封顶后即使真发生重复，显示也不会越过总数。
                        prog.segDone.updateAndGet { v ->
                            val nv = v + 1
                            if (prog.segTotal > 0 && nv > prog.segTotal) prog.segTotal else nv
                        }
                        prog.eta = prog.mediaEta(System.currentTimeMillis())
                        publish(job.id)
                    } catch (c: Cancelled) {
                        failure.compareAndSet(null, c)
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            segPool.shutdown()
            segPool.awaitTermination(6, TimeUnit.HOURS)
        } finally {
            segPool.shutdownNow()
        }
        failure.get()?.let { throw it }
        if (isCancelled(job.id)) throw Cancelled()

        // ---- 第 53 轮：分片全部结束时**确定性地**把进度收尾 ----
        // 截图里出现过「剩余 00:00 却还在下载」：那是进度数字已经到顶、但界面还在转。
        // 这里在进入合并/落地之前，把"分片阶段确实结束"这件事写死：
        // 剩余时间清零、速率归零、计数对齐到总数，用户一眼能看出它在做收尾（合并）而不是还在下。
        prog.inflight.clear()
        if (prog.segTotal > 0) {
            prog.segDone.set(prog.segTotal)
        }
        prog.bps = 0.0
        prog.eta = 0L
        patch(job.id) {
            it.copy(
                doneBytes = prog.done.get(),
                mediaDoneSec = prog.mediaDone,
                segDone = if (prog.segTotal > 0) prog.segTotal else prog.segDone.get(),
                speedBps = 0.0,
                etaSec = 0L,
                phase = "合并成单文件",
            )
        }

        // ---- 落地：加密流保持目录；否则合并成单文件 ----
        if (encrypted) {
            val out = File(work, "index.m3u8")
            out.writeText(lines.joinToString("\n"))
            patch(job.id) {
                it.copy(
                    doneBytes = prog.done.get(), mediaDoneSec = prog.mediaDone,
                    segDone = prog.segDone.get(), speedBps = 0.0, etaSec = 0L,
                    error = "这一条是 AES-128 加密流，保持分片形态才能播",
                )
            }
            cacheSidecars(out, job)
            return out.absolutePath
        }

        patch(job.id) { it.copy(phase = "合并成单文件") }
        val merged = File(saveDir(), slug(job.detail.video.title) + "_" + System.currentTimeMillis() + ".ts")
        try {
            java.io.FileOutputStream(merged).use { out ->
                initName?.let { nm -> File(work, nm).takeIf { it.isFile }?.let { f -> f.inputStream().use { it.copyTo(out) } } }
                for (s in segs) {
                    if (isCancelled(job.id)) throw Cancelled()
                    val f = File(work, s.name)
                    if (f.isFile) f.inputStream().use { it.copyTo(out) }
                }
                out.flush()
            }
        } catch (t: Throwable) {
            val out = File(work, "index.m3u8")
            runCatching { out.writeText(lines.joinToString("\n")) }
            invalidateSize()
            cacheSidecars(out, job)
            patch(job.id) { it.copy(error = "合并单文件失败（已保留分片形态）：" + (t.message ?: t.toString())) }
            return out.absolutePath
        }

        // 合并成功：清掉中间目录，只留那一个文件 + sidecar
        runCatching { work.deleteRecursively() }
        invalidateSize()
        cacheSidecars(merged, job)
        patch(job.id) {
            it.copy(
                doneBytes = prog.done.get(), mediaDoneSec = prog.mediaDone,
                segDone = prog.segDone.get(), speedBps = 0.0, etaSec = 0L,
                dest = merged.absolutePath,
            )
        }
        return merged.absolutePath
    }

    // ---------------------------------------------------------------- 第 261 轮：下载重试强化
    //
    // 背景：真机三任务全部「分片重试 4 次仍失败：HTTP 416」。416=Range Not Satisfiable，
    // 旧代码把它当普通失败、.part 保留 → 每次重试都带着同一个 Range 头撞同一堵墙。
    // 梯子（每次失败换策略）：①完整检测（bytes=0-0 探总长，.part 恰好完整就零流量落地）
    // ②弃 .part 整片重下（该域禁 Range 时必成）+ 域记忆 1 小时不再续传
    // ③任务级自动重试（runJob 内无限轮长退避，每轮重拉播放列表拿新令牌）。
    // 分析详见《2026-11-06_下载重试机制分析_HTTP416与重试到完成.md》。

    /** 416 专用标记（Range Not Satisfiable）。 */
    private class Http416(val url: String) : IllegalStateException("HTTP 416：" + url)

    /** 域 → 禁续传截止时间。被 416 打过的域 1 小时内分片不再尝试 Range 续传。 */
    private val noResumeByHost = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 任务 → 已自动重试轮数（手动重试清零；成功清除）。 */
    private val autoRetryRounds = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")

    private fun resumeBanned(host: String): Boolean {
        val until = noResumeByHost[host] ?: return false
        if (System.currentTimeMillis() > until) { noResumeByHost.remove(host); return false }
        return true
    }

    /** bytes=0-0 探测分片总长（416 完整检测用）。非 206 / 无 Content-Range = null。 */
    private fun probeRangeTotal(native: Boolean, url: String, pageUrl: String): Long? = runCatching {
        val rb = req(native, url, pageUrl).header("Range", "bytes=0-0")
        mediaHttp.newCall(rb.get().build()).execute().use { resp ->
            if (resp.code != 206) return@use null
            resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        }
    }.getOrNull()

    /** 任务级自动重试退避：60s→2min→5min→10min→30min 封顶，无限轮（用户要求「重试到完成」）。 */
    private fun autoBackoffMs(rounds: Int): Long = when {
        rounds <= 1 -> 60_000L
        rounds == 2 -> 120_000L
        rounds <= 4 -> 300_000L
        rounds <= 6 -> 600_000L
        else -> 1_800_000L
    }

    /**
     * 取一段字节到文件（第 49 轮加重试与续传，第 50 轮沿用）。
     *
     *  1. 同一段最多重试 SEG_TRIES 次（退避 300ms x n）——
     *     站点或中间设备掐断长连接（unexpected end of stream）时重打多半就好；
     *  2. 已落盘的段直接跳过；.part 有残留且服务端支持 Range 时从断点续；
     *  3. 声明长度与实际读到不符时主动抛错（静默截断会得到一个能播但残缺的文件）。
     */
    private fun pump(id: Long, native: Boolean, url: String, out: File, pageUrl: String = "", onProgress: (Long, Long, Long) -> Unit) {
        if (out.exists() && out.length() > 0) {
            onProgress(out.length(), out.length(), System.currentTimeMillis())
            return
        }
        val tmp = File(out.absolutePath + ".part")
        var lastErr: Throwable? = null
        // 第 261 轮：416 梯子的域记忆与额外预算（每次 416 换一次策略：完整检测→弃续传整片重下）
        val dlHost = hostOf(url)
        var extraTries = 0
        // 第 58 轮：**按错误类型给不同的重试策略**（用户报「息屏恢复后仍会失败」）。
        //
        // 截图里的错误是 Failed to connect to /51.75.64.68:443 ——
        // 那是**连接级**失败（IP 直连没连上），不是字节传输中断。
        // 息屏期间链路被系统断掉、刚恢复时网络栈还没热起来，头几次连不上很正常；
        // 这种失败是**暂时性**的，多试几次基本就能连上。
        // 老实现只有 3 次、退避 300ms —— 对「网络刚恢复」这个场景太急。
        for (tryIndex in 0 until 64) {
            if (isCancelled(id)) throw Cancelled()
            val msg = lastErr?.message.orEmpty()
            val connectFail = msg.contains("Failed to connect", true) ||
                msg.contains("ConnectException", true) ||
                msg.contains("Connection refused", true) ||
                msg.contains("Unable to resolve host", true) ||
                msg.contains("unknown host", true) ||
                msg.contains("No address associated", true)
            val throttled = msg.contains("429")
            // 第 58 轮：把"这个域被限流过"记下来，随后一小段时间对该域降速（而不是全局降并发）。
            if (throttled) runCatching {
                java.net.URI(url).host?.let { throttledUntil[it] = System.currentTimeMillis() + 30_000L }
            }
            // 连接级失败给更多次数（等网络热起来）；限流给长退避；其余保持原策略。
            val maxTries = if (connectFail) CONNECT_TRIES else SEG_TRIES
            if (tryIndex >= maxTries + extraTries) break   // 416 梯子有额外预算（extraTries）
            if (tryIndex > 0) {
                val wait = when {
                    throttled -> 1200L * tryIndex
                    connectFail -> 500L * tryIndex
                    else -> 300L * tryIndex
                }
                runCatching { Thread.sleep(wait) }
                patch(id) {
                    it.copy(
                        retries = it.retries + 1,
                        phase = when {
                            throttled -> "被限流，等待 " + (wait / 1000.0) + "s 后重试"
                            connectFail -> "网络未就绪，重连中（第 " + (tryIndex + 1) + " 次）"
                            else -> "重试中"
                        },
                    )
                }
            }
            try {
                var already = if (tmp.exists()) tmp.length() else 0L
                val useResume = already > 0 && !resumeBanned(dlHost)
                if (already > 0 && !useResume) {                 // 该域已记「禁续传」：.part 只能弃（整片重下）
                    runCatching { tmp.delete() }
                    already = 0
                }
                val rb = req(native, url, pageUrl)
                if (useResume) rb.header("Range", "bytes=" + already + "-")
                mediaHttp.newCall(rb.get().build()).execute().use { resp ->
                    val resumed = resp.code == 206 && useResume
                    if (resp.code == 416) throw Http416(url)
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code + "：" + url)
                    val body = resp.body ?: throw IllegalStateException("空响应：" + url)
                    val declared = body.contentLength()
                    val total = if (resumed) already + declared.coerceAtLeast(0) else declared
                    var done = if (resumed) already else 0L
                    if (!resumed && tmp.exists()) tmp.delete()
                    body.byteStream().use { ins ->
                        java.io.FileOutputStream(tmp, resumed).use { os ->
                            val buf = ByteArray(1 shl 18)
                            // 第 57 轮：**低速/卡死检测**（截图里「0.01 MB/s　剩余 18:17」的直接解药）。
                            //
                            // 为什么 readTimeout 救不了：那条连接**并没有断**，字节还在极慢地到达
                            // （0.01 MB/s ≈ 10 KB/s），每次来一点就把 socket 超时重置了。
                            // 于是任务既不失败、也跑不动，用户看到"挂着不走"。
                            //
                            // 这里的判据是**窗口平均速率**：
                            //   每 15 秒结算一次，若这一窗口的平均速率低于 MIN_BPS 就主动断开重连。
                            // 重连会重新选路（CDN 节点/边缘），实测这种"某条链接突然变慢"多数能自愈；
                            // 真连不动就由外层 [SEG_TRIES] 的退避重试兜底，最后如实报失败。
                            var winStart = System.currentTimeMillis()
                            var winBytes = done
                            while (true) {
                                if (isCancelled(id)) throw Cancelled()
                                val m = ins.read(buf)
                                if (m <= 0) break
                                os.write(buf, 0, m)
                                done += m
                                val now = System.currentTimeMillis()
                                onProgress(done, total, now)
                                // 窗口结算
                                val dt = now - winStart
                                if (dt >= STALL_WINDOW_MS) {
                                    val bps = (done - winBytes) * 1000.0 / dt
                                    if (bps < MIN_BPS) {
                                        throw IllegalStateException(
                                            "连接速率过低（" + String.format("%.1f", bps / 1024) + " KB/s 持续 " +
                                                (dt / 1000) + "s），主动重连换路",
                                        )
                                    }
                                    winStart = now
                                    winBytes = done
                                }
                            }
                            os.flush()
                        }
                    }
                    if (!resumed && declared > 0) {
                        val got = tmp.length()
                        if (got < declared) throw IllegalStateException("响应被截断：声明 " + declared + " 实收 " + got)
                    }
                    if (!tmp.renameTo(out)) throw IllegalStateException("落地失败：" + out.name)
                    return
                }
            } catch (c: Cancelled) {
                throw c
            } catch (t: Throwable) {
                if (t is Http416) {
                    // ★ 第 261 轮：416 智能梯子（分析见《2026-11-06_下载重试机制分析_HTTP416与重试到完成.md》）
                    extraTries = minOf(extraTries + 2, 6)
                    // H3 完整检测：bytes=0-0 探分片总长，.part 恰好完整（上次死在 rename 前）→ 零流量落地
                    val total = probeRangeTotal(native, url, pageUrl)
                    val have = if (tmp.exists()) tmp.length() else 0L
                    if (total != null && total > 0 && have == total) {
                        if (!tmp.renameTo(out)) throw IllegalStateException("落地失败：" + out.name)
                        onProgress(total, total, System.currentTimeMillis())
                        return
                    }
                    // H2 弃续传：该域对 Range 回 416 ⇒ 删 .part 整片重下（不带 Range 的普通 GET 首下必然成功过）
                    //        并记此域 1 小时内不再续传，后续分片不再浪费试探
                    if (have > 0L) runCatching { tmp.delete() }
                    if (dlHost.isNotEmpty()) noResumeByHost[dlHost] = System.currentTimeMillis() + 3_600_000L
                    patch(id) { it.copy(phase = "CDN拒绝续传(416)，整片重下") }
                    com.han1me.lab.LabLog.i("dl", "416 梯子：整片重下 " + out.name + "（弃 .part " + have + "B，域=" + dlHost + " 记禁续传1h）")
                    lastErr = t
                    runCatching { Thread.sleep(400L) }
                    continue
                }
                lastErr = t
                // ★★★ 第 153 轮（真机截图：重试 4 次仍失败，报 stream was reset: CANCEL）：
                //
                // `stream was reset: CANCEL` 是 HTTP/2 的 RST_STREAM(CANCEL)，
                // 通常来自**对端把这条连接上的流掐掉**。问题是 OkHttp 的
                // retryOnConnectionFailure 对 CANCEL **不视为可重试**，异常直接抛到我们这层；
                // 而我们的重试循环会拿**连接池里同一条（已被对端 RST 的）连接**再打，
                // 于是 4 次全撞同一堵墙 —— 用户看到的就是「重试 4 次仍失败」。
                //
                // 这里在连接级失败时主动清空空闲连接，逼下一次重试**新建连接**。
                // 只清空闲连接（不动正在用的），代价是重握一次手，换来重试真的有效。
                val connMsg = t.message.orEmpty()
                if (connMsg.contains("stream was reset", true) || connMsg.contains("CANCEL", true) ||
                    connMsg.contains("unexpected end of stream", true) ||
                    connMsg.contains("Connection reset", true) || connMsg.contains("broken pipe", true)
                ) {
                    runCatching { mediaHttp.connectionPool.evictAll() }
                }
            }
        }
        throw IllegalStateException("分片重试 " + SEG_TRIES + " 次仍失败：" + (lastErr?.message ?: lastErr.toString()))
    }

    private fun cacheSidecars(playable: File, job: Job) {
        val okDetail = writeDetailCache(playable, job.detail, job.quality, job.group)
        val okCover = fetchCoverCache(playable, job.detail.video.thumb)
        val notes = ArrayList<String>()
        if (!okDetail) notes += "介绍页缓存失败"
        if (!okCover && job.detail.video.thumb.isNotBlank()) {
            notes += "封面未缓存" + (if (lastCoverError.isNotBlank()) "（" + lastCoverError + "）" else "")
        }
        com.han1me.lab.LabLog.i("dl", "cacheSidecars detail=" + okDetail + " cover=" + okCover +
            (if (job.detail.video.thumb.isBlank()) " (thumb 为空)" else "") +
            (if (notes.isEmpty()) "" else " " + notes.joinToString("；")))
        if (notes.isNotEmpty()) patch(job.id) { it.copy(error = notes.joinToString("；")) }
    }

    private fun slug(title: String): String =
        title.ifBlank { "media" }.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(48)

    // ---------------------------------------------------------------- 状态发布

    private fun progOf(id: Long): Prog = synchronized(lock) {
        progs.getOrPut(id) { Prog().also { it.startedAt = System.currentTimeMillis() } }
    }

    /**
     * 更新一条任务的显示状态。
     *
     * **第 51 轮修崩溃**：以前是 @code _tasks.value = _tasks.value.map { … }@code ——
     * 「读当前列表 → 造新列表 → 写回」三步之间没有锁。多个分片线程同时推进度时，
     * 后写的会把前一个的整条更新**覆盖掉**（实测 20 线程下只剩最后一个赢家）。
     * 现在把读-改-写整段放进 [lock]，成为一次原子操作。
     */
    private fun patch(id: Long, f: (DlTask) -> DlTask) {
        // ★★★ 第 344 轮（全量核查 · P0）：**写回也必须在锁内**。
        //
        // 原来是这样：
        //   val next = synchronized(lock) { _tasks.value.map { ... } }
        //   _tasks.value = next          // ← 在锁外
        //
        // 注释里说"读-改-写整段放进 lock 成为一次原子操作"，但**写回那一步漏在锁外**。
        // 16 个分片线程并发 patch 时：A 算出 nextA、B 算出 nextB（两者都基于同一份旧快照），
        // A 先写、B 后写 ⇒ A 的更新整个丢失。真机上能复现的死循环是：
        //   runJob 收尾 patch(DONE) 写完，某个还在跑的进度线程拿着旧快照 patch(RUNNING) 后写，
        //   把状态覆盖回 RUNNING ⇒ 任务永远显示「下载中 100%」，前台服务不收工、
        //   队列永远不空、通知栏一直挂着。
        //
        // 现在整段都在 lock 里（monitor 可重入，内部再 synchronized 也没问题）。
        val next = synchronized(lock) {
            val mapped = _tasks.value.map { if (it.id == id) f(it) else it }
            _tasks.value = mapped
            mapped
        }
        // ★ LABPERSIST：状态变化即落盘（这是所有状态变更的统一入口，不会漏掉任何情况）。
        //
        // ★★★ 第 154 轮（真机日志：1738 行里绝大多数都是 persistTasks 刷屏，每 ~250ms 一条）：
        // patch() 在下载过程中被进度刷新频繁调用，每次都同步写一个 JSON 到磁盘 ——
        // 既是无谓的 IO，也会把诊断日志淹没（用户报「日志里看不到封面相关信息」就是这个原因）。
        // 现在做**节流**：进度类变更最多每 2 秒落一次盘；
        // 而「状态类变更」（完成/失败/暂停/取消）仍**立即落盘**，保证崩溃也不丢关键状态。
        val now = System.currentTimeMillis()
        val st = next.firstOrNull { it.id == id }?.status
        val important = st == DlStatus.DONE || st == DlStatus.FAILED ||
            st == DlStatus.PAUSED || st == DlStatus.CANCELLED
        if (important || now - lastPersistAt > 2_000L) {
            lastPersistAt = now
            runCatching { persistTasks() }
        }
    }

    /** 把 prog 的计数快照到界面任务上（节流约 4 次/秒；收尾那一次一定发出去）。 */
    private fun publish(id: Long) {
        val prog = synchronized(lock) { progs[id] } ?: return
        val now = System.currentTimeMillis()
        val doneNow = prog.done.get()
        val finished = prog.total > 0 && doneNow >= prog.total
        if (!finished) {
            val last = lastEmit[id] ?: 0L
            if (now - last < 220) return
        }
        lastEmit[id] = now
        runCatching {
            onProgressTick?.invoke(
                _tasks.value.firstOrNull { it.id == id }?.title ?: "下载中",
                doneNow,
                if (prog.total > 0) prog.total else -1L,
                prog.total > 0,
            )
        }
        patch(id) {
            it.copy(
                doneBytes = doneNow,
                totalBytes = if (prog.total >= 0) prog.total else it.totalBytes,
                mediaDoneSec = prog.mediaDone,
                mediaTotalSec = if (prog.mediaTotal > 0) prog.mediaTotal else it.mediaTotalSec,
                speedBps = prog.bps,
                etaSec = prog.eta,
                segDone = prog.segDone.get(),
                segTotal = if (prog.segTotal > 0) prog.segTotal else it.segTotal,
            )
        }
    }

    // ---------------- 初始化（必须放在类末尾，见上方第 149 轮说明） ----------------
    init {
        // 进程启动即尽力恢复上次未完成的任务（全部为「已暂停」，等用户点继续）。
        // 若此刻 HplayEnv 尚未备好，restoreTasks 会记日志跳过，由 ensureRestored() 再补一次。
        runCatching { restoreTasks() }
    }

}