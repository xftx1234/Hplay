package com.han1me.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.han1me.HplayEnv
import com.javrocket.hanime.HanimeClient
import com.han1me.HplayOnSurface
import com.han1me.HplayOnSurfaceVariant
import com.han1me.HplayErrorSoft

/**
 * Android 内嵌播放器（对应桌面版 com.javrocket.playground.player.ui.VideoPlayer 体系）。
 *
 * ExoPlayer + OkHttpDataSource 直接挂 [HanimeClient.http]：DoH / 边缘节点 / 代理按域名
 * 对媒体流量天然生效 —— 等价于桌面版「本地中继让播放流量走网络策略」的作用（relay.enabled
 * 设置在 Android 端为等价空操作，见 HanimeClient 的 Android 补丁注释）。
 * 官方源直链为 mp4（多码率 source），ExoPlayer 原生支持；全屏 = 横屏沉浸（系统承担）。
 */
class HplayPlayerController internal constructor(
    val player: ExoPlayer,
    /** 每次 prepare 都要重写它的默认请求头，所以控制器必须持有工厂本身。 */
    private val source: OkHttpDataSource.Factory,
) {
    /**
     * 屏显诊断串。**真机抑制应用 logcat**（连 System.out 都空），所以播放失败必须有第二条通道：
     * 上一版失败时界面只剩「进度条 0 时长」，用户和我都无法区分「没拿到源」「源被拒（403/CF）」
     * 「解不了封装（缺 HLS 模块）」——2026-10-01 的 xchina 播放失效正是第三类，靠 dex 取证才定位。
     */
    var diag by mutableStateOf("")
        internal set
    var errorText by mutableStateOf("")
        internal set

    private val listener = object : Player.Listener {
        override fun onPlayerError(e: PlaybackException) {
            errorText = "播放失败 ${e.errorCodeName}"
            val why = e.cause?.message ?: e.message
            if (!why.isNullOrBlank()) errorText += "：$why"
            // 「None of the available extractors could read the stream」这类错误光看码名定不了位：
            // 到底是清单没取到、还是把 m3u8 当 mp4 喂给了 progressive，只有把**那条地址**打出来才分得清。
            // 手机抑制应用日志，屏显是唯一通道，所以直接拼进回显（截断，避免整条签名链刷屏）。
            val bad = runCatching {
                player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
            }.getOrDefault("")
            if (bad.isNotEmpty()) errorText += "\n地址: ${bad.take(150)}"
            diag = "错 ${e.errorCodeName} ${describe()}"
        }

        override fun onPlaybackStateChanged(state: Int) {
            diag = describe()
        }
    }

    init {
        player.addListener(listener)
    }

    /** 一行状态：播放态 / 时长 / 位置 / 缓冲 / 轨道数（时长为 0 就说明清单没解析出来）。 */
    private fun describe(): String = runCatching {
        val dur = player.duration
        "态${
            when (player.playbackState) {
                Player.STATE_IDLE -> "闲"; Player.STATE_BUFFERING -> "缓冲"; Player.STATE_READY -> "就绪"; else -> "结束"
            }
        } 长${if (dur == androidx.media3.common.C.TIME_UNSET) "?" else "%.1f".format(dur / 1000f)}s" +
            " 位%.1fs 缓${player.bufferedPercentage}%" +
            " 轨${player.currentTracks.groups.sumOf { it.length }}"
    }.getOrDefault("态?")

    internal fun removeListener() = runCatching { player.removeListener(listener) }

    /** 本次播放是否由「自动全屏」进入的沉浸态（决定 stop/组合退出时要不要替用户退回去）。 */
    private var autoFs = false

    /**
     * 「点播放视频自动播放全屏」与「补 Referer」都只对**原生板块**（xchina / MissAV）生效，
     * 而且必须由调用方**明确告诉这里是不是原生板块**——不能反过来用「不是官方域 ⇒ 是第三方板块」推断。
     * 上一版就是这么写的，实测踩穿：官方源的媒体域是 `vdownload.hembed.com`（官方详情页
     * `<source src=...>` 里取到的，见 `临时\avp_watch2.html`），而 `officialHosts` 只有
     * `hanime1.me / hanime1.com / hanimeone.me / javchu.com` 这四个**页面域**，
     * 于是官方源的 mp4 既被强行横屏全屏、又被多塞一个 Referer——直接违反「不影响官方源」。
     *
     * Referer 走 `setDefaultRequestProperties`（**跟着这一次播放走**，不是进程级全局、也不是按域名白名单）：
     * MissAV 的 /jmpres/ 链没有同域 Referer 时不报错，而是回 HTTP 200 + 一张诱饵 JPEG
     * （实测恒定 23 111 B、302 到 shimg.piccdn1.cfd），状态码判不出来；官方路径传空表，
     * 请求头一个字节都不会多出来。
     */
    fun prepare(url: String) {
        errorText = ""
        diag = ""
        // 本机副本（`file://`，含下载下来的 index.m3u8 与分片）：不补 Referer、不剥 Range、不自动全屏。
        // 它是「自己的文件」，与在线直链的防盗链/网络策略无关 —— 不隔离就会把在线那套规则
        // 误加到本地播放上（比如对 file:// 去查 refererFor、或因为全局 nativePlay 残留而强行横屏）。
        val localFile = url.startsWith("file://") || url.startsWith("content://")
        val nativeBoard = !localFile && com.han1me.X99.nativePlay
        // ★★ 第 91 轮真机修（用户报「优选站还是不可播放，三个画质键都播不了」）：
        //
        // 真机实测（决定性证据）：优选镜像的媒体地址**要求同域 Referer**：
        //   带 Referer + Range → 206 + video/mp4 + ftyp isom（真视频）
        //   不带 Referer       → 200 + **143 B** + application/vnd.apple.mpegurl（一个 m3u8 清单）
        // 播放器把那个 143 B 的清单当 mp4 解析，于是报
        //   ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED（正是用户截图里的报错与 143 B）。
        //
        // 原来这里只对 nativeBoard（xchina/MissAV 等）补 Referer，优选镜像落到 else ⇒ 空 Referer。
        // 现在：**优选线路也补**，用「当前优选线路域」作 Referer（与镜像页同源）。
        // 官方源仍保持空（它不需要，且上一版踩过「多塞 Referer 反而坏事」的坑）。
        val mirrorRef = if (!localFile && !nativeBoard) {
            runCatching { com.han1me.X99.mirrorRefererFor(url) }.getOrDefault("")
        } else ""
        val ref = if (nativeBoard) runCatching { com.han1me.X99.refererFor(url) }.getOrDefault("") else mirrorRef
        source.setDefaultRequestProperties(if (ref.isEmpty()) emptyMap() else mapOf("Referer" to ref))
        // 剥 Range 只跟着**这一次播放**：每次 prepare 整体重写，既不跨播放残留，也碰不到官方源
        // （官方源的 host 不满足 mediaRangeHostile 判据）。
        MediaFlags.stripRange = runCatching { com.han1me.X99.mediaRangeHostile(url) }.getOrDefault(false)
        // 必须显式声明 MIME：media3 靠 URI 后缀推断类型，而肉视频这类站的播址是
        // `https://rou01.cc/api/hls/<id>`——**无扩展名的 HLS 清单**。不声明就会被当普通文件
        // 交给 Mp4Extractor/TsExtractor 那批，报「None of the available extractors …
        // could read the stream」(ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)。
        // 判据不是猜后缀，而是解析器**实际取回并验过 #EXTM3U** 的那份名单（X99.mediaIsHls）。
        val item = if (com.han1me.X99.mediaIsHls(url) ||
            (localFile && url.substringBefore('?').endsWith(".m3u8"))) {
            MediaItem.Builder().setUri(url)
                .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build()
        } else {
            MediaItem.fromUri(url)
        }
        player.setMediaItem(item)
        player.prepare()
        autoFs = nativeBoard
    }

    /** 屏上「重试」：清单/key/分片任一环节失败后，重走一次 prepare（token 可能已过期，重取详情才有新链）。 */
    fun retry() {
        errorText = ""
        runCatching { player.prepare() }
    }

    fun play() {
        player.playWhenReady = true
        if (autoFs) HplayEnv.applyFullscreen(true, owner = HplayEnv.FsOwner.PLAYER)
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        leaveAutoFullscreen()
    }

    /**
     * 退出自动全屏。**组合退出也必须调**：用户不点「⤡ 收起」而是直接返回/切页时，
     * 播放器随浮层一起销毁，若没人解锁，列表页就还锁在横屏沉浸里。
     */
    fun leaveAutoFullscreen() {
        if (!autoFs) return
        autoFs = false
        HplayEnv.applyFullscreen(false, owner = HplayEnv.FsOwner.PLAYER)
    }

    fun toggleFullscreen() = HplayEnv.toggleFullscreen()
}

/**
 * 媒体流量直接走 `HanimeClient.http`：DoH / 边缘节点 / 代理按域名生效，与桌面同构。
 *
 * 这里**不再挂全局 Referer 拦截器**（上一版是 `newBuilder().addInterceptor{ 非官方域就补 }`）。
 * 那样写有两个问题：一是判据是反推的（官方媒体域 `vdownload.hembed.com` 不在 `officialHosts` 四个页面域里，
 * 于是官方源的 mp4 也被补了头）；二是进程级拦截器对所有播放器实例生效，一次原生播放会把
 * Referer 带进之后任何第三方请求。Referer 现在由 `prepare(url, nativeBoard)` 按**这一次播放**下发。
 */

/** 只跟着"这一次播放"的开关：每次 prepare 整体重写，不留跨播放状态。 */
internal object MediaFlags {
    @Volatile var stripRange: Boolean = false
}

@Composable
fun rememberHplayController(ownerKey: String? = null): HplayPlayerController {
    val context = LocalContext.current
    val controller = remember(ownerKey) {
        // 媒体流量走 HanimeClient.http（DoH/边缘节点/代理按域名生效）；Referer 由 prepare 按次下发
        // 媒体客户端在 HanimeClient.http 之上只做两件事，都不碰官方源的解析路径：
        //  ① Dns 换成链式（板块 DoH 在前、HanimeClient 原本解析垫后）；
        //  ② 挂一个"只跟着这一次播放"的剥 Range 拦截器。
        // ★★ LABVIDEO（2026-10-31 · 用户报「官方源播放卡顿」）
        //
        // 根因：这个客户端从 HanimeClient.http 派生，继承了**页面加载**的参数：
        //   · readTimeout 30s —— 视频大分片读到一半被判超时 → 重试 → 肉眼看到卡顿；
        //   · 连接池 24 —— 预读 + 拖动需要更多长连接；
        // 网页端流畅是因为浏览器对视频流用「长超时 + 独立大连接池」。
        // 这里把该思路搬到 ExoPlayer 的数据源上（**只动传输参数，不碰解析路径**）。
        val mediaHttp = HanimeClient.http.newBuilder()
            .dns(com.han1me.X99.boardDnsOver(HanimeClient.http.dns))
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(32, 10, java.util.concurrent.TimeUnit.MINUTES))
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val b = chain.request()
                if (MediaFlags.stripRange && b.header("Range") != null) {
                    chain.proceed(b.newBuilder().removeHeader("Range").build())
                } else chain.proceed(b)
            }
            .build()
        val factory = OkHttpDataSource.Factory(mediaHttp)
            .setUserAgent(HanimeClient.UA)
        // 显式包一层 DefaultDataSource：`file://`（本机下载的副本）交给 FileDataSource，
        // http(s) 仍然落到上面的 OkHttpDataSource —— Referer / DoH / 网络策略一个字节不变。
        // media3 的 DefaultMediaSourceFactory(DataSource.Factory) **不会**自动包，
        // 不给这一层，离线播 `file://…/index.m3u8` 会把 file scheme 直接甩给 OkHttp
        // （`Unexpected scheme: file`），下载好的东西点了播不出来。
        // ★★ LABVIDEO3（2026-10-31 · 真机日志定位「播放反复缓冲」）：
        //
        // 日志证据（vdownload.hembed.com 的请求时间）：
        //   22:24:32.954 × 5 条（起播预读）
        //   22:24:55.726（**23 秒后**才有下一次！）
        //   22:25:02 / 22:25:11 …
        //
        // 这形态说明：播放器**预读一小段就停了**，等缓存耗尽才重新发请求 ——
        // 而这时如果网络要重建连接（内置节点下还要多一跳），就是肉眼看到的「缓冲」。
        //
        // 上一版把 minBuffer 设成 20s 是错的：ExoPlayer 的语义是
        //   「缓冲 >= maxBuffer 才停；但低于 minBuffer 就必须补」
        // 而 bufferForPlayback 只影响起播。真正要的是：**一直往前预读、别停下来**。
        //
        // 现在的取值（贴近浏览器行为）：
        //  · minBufferMs 15s：低于 15s 就继续补，不等耗完；
        //  · maxBufferMs 120s：**上限拉高到 2 分钟**，让它在后台持续预读整段缓存，
        //    这样正常播放时几乎不会触发「重新发请求」；
        //  · bufferForPlayback 800ms：起播更快（点开就出画面）；
        //  · bufferForPlaybackAfterRebuffer 1500ms：万一卡了，攒 1.5s 就恢复；
        //  · prioritizeTimeOverSizeThresholds = true：按时间判定，忽略码率差异。
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 120_000, 800, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            // ★ 允许在缓冲不足时继续加载（不让调度器因为「快播完了」而暂停读取）
            .setBackBuffer(30_000, true)
            .build()
        val player = ExoPlayer.Builder(
            context, DefaultMediaSourceFactory(DefaultDataSource.Factory(context, factory)),
        ).setLoadControl(loadControl).build()
        HplayPlayerController(player, factory)
    }
    DisposableEffect(controller) {
        onDispose {
            controller.leaveAutoFullscreen()
            controller.removeListener()
            runCatching { controller.player.release() }
        }
    }
    return controller
}

/**
 * 播放器视图：PlayerView（自带播放/进度/音量控件，3 秒自动隐藏）+ 左上返回浮层 +
 * 控制条上的全屏键（media3 原生 fullscreen button → 横屏沉浸）。
 * 全屏态下按系统返回键先退全屏，不退详情页。
 */
@Composable
fun HplayPlayerView(
    controller: HplayPlayerController,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null,
) {
    // 全屏态下按系统返回键先退全屏，不退详情页（HplayEnv.isFullscreen 为 Snapshot 状态，实时生效）
    BackHandler(enabled = HplayEnv.isFullscreen) { HplayEnv.applyFullscreen(false) }
    // 退后台/锁屏即暂停（不留持续出声的孤播放器）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) controller.pause()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    setFullscreenButtonClickListener { HplayEnv.toggleFullscreen() }
                    player = controller.player
                }
            },
            update = { pv -> if (pv.player !== controller.player) pv.player = controller.player },
        )
        if (onBackClick != null) {
            // ★ 2026-11-25（第 281 轮）：改用 Viewer 的玻璃拟态返回按钮。
            // 原来是硬编码 0xB3262626 直角方块；现在半透明黑 + 细白边 + 圆角。
            com.han1me.ui.PlayerBackButton(
                onClick = onBackClick,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )
        }
        // 失败与状态都画在屏上（真机没有 logcat 可看）：红字是错误 + 重试，灰字是一行诊断
        if (controller.errorText.isNotEmpty()) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.align(Alignment.Center).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    controller.errorText, color = HplayErrorSoft, fontSize = 12.5.sp,
                    modifier = Modifier.background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "重试", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.background(Color(0xCCB3262C.toInt()))
                        .clickable { controller.retry() }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        } else if (controller.diag.isNotEmpty()) {
            Text(
                controller.diag, color = HplayOnSurfaceVariant, fontSize = 10.5.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                    .background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}