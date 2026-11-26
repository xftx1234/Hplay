package com.han1me.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultDataSource

/**
 * **ExoPlayer 内核实现**（2026-11-25 第 289 轮，照 Han1meViewer 的 `kernel/ExoPlayerKernel.kt`）。
 *
 * 从 Viewer 那边继承的关键行为：
 *
 * 1. **播放器实例在专用后台线程构建与释放**
 *    ExoPlayer 的构造函数会初始化解码器与音频子系统，在主线程建会卡一帧以上；
 *    release() 也是 —— 用户退出播放页时不该看到掉帧。
 *
 * 2. **起播缓冲按 Java 堆分档**
 *    低内存设备（堆 < 128MB）用更小的缓冲，否则 HLS 多分片预读很容易 OOM。
 *    这是 Viewer 在真机上踩出来的经验值，直接沿用。
 *
 * 3. **HLS 与渐进式自动分流**
 *    `.m3u8` 走 HlsMediaSource（要解析播放列表、支持多码率切换）；
 *    其他走 ProgressiveMediaSource（mp4 直链）。选错会直接播不出来。
 *
 * 4. **seek 到未缓冲位置时回调 onBuffering**
 *    否则用户拖动进度条后画面会静止几百毫秒却没有任何提示。
 */
@OptIn(UnstableApi::class)
class HplayExoKernel(
    private val context: Context,
    /**
     * ★★★ 2026-11-25（第 289 轮 · 真机 Source error 后修正）：
     * **必须复用 [com.han1me.HanimeClient] 的 OkHttp 实例**。
     *
     * 初版用 DefaultHttpDataSource（Android 自带的 HttpURLConnection），真机报
     *   HttpDataSourceException: java.net.SocketException: Connection reset
     * 因为那个连接**不走我们的代理/DoH/边缘节点** —— 直连 hanime1.me 被墙，
     * 表现就是 connection reset。
     *
     * 我们自己的旧播放器（HplayPlayer.kt 第 41-44 行）早就写明：
     *   "ExoPlayer + OkHttpDataSource 直接挂 HanimeClient.http：
     *    DoH / 边缘节点 / 代理按域名分流"
     * 这是整个 App 的网络基础设施，播放器必须共用，不能自建。
     */
    private val httpClient: okhttp3.OkHttpClient,
) : HplayPlayerKernel {

    private companion object {
        const val MIB = 1024 * 1024

        /** 起播缓冲：1 秒够开画，再多只是白等。 */
        const val BUFFER_FOR_PLAYBACK_MS = 1_000

        /** 卡顿后重新起播：给 2 秒，避免反复卡。 */
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000
    }

    override val type: HplayKernelType = HplayKernelType.EXO

    override var listener: HplayKernelListener? = null

    /**
     * ★★★ 2026-11-25（第 289 轮 · 真机崩溃后修正）：
     * 播放器必须**在它被访问的线程上创建**。
     *
     * 初版照 Viewer 用独立 HandlerThread 创建（"构建/释放别卡主线程"），
     * 真机立刻崩：
     *   IllegalStateException: Player is accessed on the wrong thread.
     *   Current thread: 'main' / Expected thread: 'hplay-exo'
     *   at ExoPlayerImpl.getCurrentPosition
     * 因为我们会从主线程读 currentPositionMs（进度泵每 100ms 一次）。
     *
     * Viewer 能用后台线程是因为它**所有**属性访问都通过 runOnPlayerThread()
     * 转发并等待结果（见它 ExoPlayerKernel.kt 的注释）。我们没有那套转发机制，
     * 也犯不上为此引入 —— 直接在主线程创建，所有访问天然同线程。
     *
     * 代价：release() 在主线程执行会有 ~10ms 抖动。实测无感，
     * 比起崩溃和引入跨线程转发机制，这个代价可以接受。
     */
    private val playerHandler = Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var pendingRequest: HplayMediaRequest? = null

    override val videoWidth: Int
        get() = player?.videoSize?.width ?: 0

    override val videoHeight: Int
        get() = player?.videoSize?.height ?: 0

    override val isPlaying: Boolean
        get() = player?.isPlaying == true

    override val currentPositionMs: Long
        get() = player?.currentPosition ?: 0L

    override val durationMs: Long
        get() {
            val d = player?.duration ?: 0L
            return if (d == C.TIME_UNSET || d < 0) 0L else d
        }

    /**
     * 准备媒体。
     *
     * 播放器**懒建**：第一次 prepare 才创建实例，避免进页面就吃掉解码器资源。
     */
    override fun prepare(request: HplayMediaRequest) {
        pendingRequest = request
        // ★ 真机第三次修正：**同步创建播放器**。
        //
        // 原来是 playerHandler.post { ... } 异步建。问题：
        // AndroidView 的 update 在组合后立刻跑，那一刻 player 还是 null，
        // 之后 state 引用没变就不会再触发 update —— PlayerView 永远绑不上，
        // 结果"有声无画"。
        //
        // 何况 playerHandler 现在就是主线程 Looper，post 只是把工作推到下一帧，
        // 没有任何并发收益，反而制造了这个时序坑。直接同步做。
        val p = player ?: build().also { player = it }
        val src = buildSource(request)
        p.setMediaSource(src, request.startPositionMs)
        p.playWhenReady = !request.startPaused
        p.setPlaybackSpeed(request.speed)
        p.repeatMode = if (request.looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.prepare()
    }

    /**
     * 构建播放器。
     *
     * ★★★ 2026-11-25（第 300 轮 · 用户报「卡顿/音画不同步/显示暂停加载中」）：
     * **完整照搬 Han1meViewer 的 ExoPlayerKernel 构建配置**。
     *
     * 之前只设了 bufferDurations，缺了三样关键配置：
     *
     * 1. **setTargetBufferBytes**（Viewer 按 memoryClass 分档 48/64/96 MB）
     *    Media3 默认按 125MB 视频 + 12.5MB 音频准备缓冲 —— 在 256MB 堆设备上
     *    加上图片缓存很容易 OOM，触发 GC 抖动，表现就是卡顿。
     *
     * 2. **setPrioritizeTimeOverSizeThresholds(false)**
     *    默认 true 表示「时间优先」，会无视内存上限继续缓冲；
     *    设 false 改成「内存优先」，低内存设备不会被撑爆。
     *
     * 3. **DefaultBandwidthMeter + AdaptiveTrackSelection**
     *    HLS 多码率时按实测带宽自适应选档。没有它会在码率切换时
     *    出现音画不同步（视频换档、音频没跟上）。
     *
     * 4. **DefaultRenderersFactory**（显式传）
     *    默认构造会用 extensionRendererMode=OFF 但 enableDecoderFallback=false；
     *    Viewer 显式传一个，让解码器失败时能回退到软解，减少「有声无画」。
     */
    private fun build(): ExoPlayer {
        // ---- 缓冲按 memoryClass 分档（照 Viewer 第 69-99 行）----
        val memoryClassMb = runCatching {
            (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
                ?.memoryClass
        }.getOrNull()?.takeIf { it > 0 } ?: 256

        val (targetBytes, minBuf, maxBuf) = when {
            memoryClassMb <= 256 -> Triple(48 * MIB, 10_000, 20_000)
            memoryClassMb <= 384 -> Triple(64 * MIB, 15_000, 30_000)
            else -> Triple(96 * MIB, 20_000, 40_000)
        }
        com.han1me.lab.LabLog.i(
            "player.exo",
            "memoryClass=" + memoryClassMb + "MB targetBuffer=" + (targetBytes / MIB) +
                "MB range=" + (minBuf / 1000) + "s-" + (maxBuf / 1000) + "s",
        )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBuf,
                maxBuf,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(targetBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        // ---- 轨道选择 + 带宽自适应（防音画不同步）----
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(
            context,
            androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters.Builder(context)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .build(),
        )
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)   // 硬解失败回退软解

        val p = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(
                androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context).build(),
            )
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> listener?.onBuffering()
                    Player.STATE_READY -> listener?.onPrepared()
                    Player.STATE_ENDED -> listener?.onCompleted()
                    Player.STATE_IDLE -> Unit
                }
            }

            /**
             * ★★★ 2026-11-25（第 300 轮 · 修「有时显示暂停和加载中，声音有」）：
             *
             * 原来 onIsPlayingChanged(false) 一律报 onPaused()。
             * 但 ExoPlayer 的 isPlaying 在**缓冲时也会变 false** —— 于是用户看到：
             *   声音还在播 -> 突然显示「已暂停」-> 过一下又显示「加载中」
             *
             * 正确判据：
             *   · 缓冲中 -> 这是 onBuffering 的事，不在这里再报 onPaused
             *   · playWhenReady == false 且不在缓冲 -> 才是真的用户暂停
             *   · 播放完毕 -> 交给 onCompleted，不报 paused
             */
            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing) {
                    listener?.onPlaying()
                    return
                }
                val st = p.playbackState
                val ended = st == Player.STATE_ENDED
                val buffering = st == Player.STATE_BUFFERING
                val userPaused = !p.playWhenReady
                if (!ended && !buffering && userPaused) {
                    listener?.onPaused()
                }
            }

            override fun onVideoSizeChanged(vs: androidx.media3.common.VideoSize) {
                listener?.onVideoSizeChanged(vs.width, vs.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error)
            }
        })
        return p
    }

    /**
     * 构建媒体源。
     *
     * ★★★ 2026-11-25（第 289 轮 · 真机踩坑后重写）：
     *
     * 初版只看 URI 后缀判 HLS，真机立刻报 **Source error**。
     * 我们自己的旧播放器（HplayPlayer.kt）早就在注释里记下了这个坑，我重写时漏掉了：
     *
     *   1. **不能靠后缀判 MIME**。肉视频那类站的播址是 `https://xxx/api/hls/<id>`
     *      —— 无扩展名的 HLS 清单。按后缀判会当普通 mp4 交给 Mp4Extractor，
     *      报「None of the available extractors could read the stream」。
     *
     *   2. **要用站点自己的 HLS 判据**（`X99.mediaIsHls`）—— 它是"实际取回并验过
     *      #EXTM3U"的名单，比任何后缀猜测都准。
     *
     *   3. **Referer 必须按本次播放下发**。优选镜像的媒体地址要求同域 Referer：
     *      带 Referer 给真视频（206 + video/mp4），不带只给一个 143 字节的 m3u8 清单。
     *
     * 这三条都是旧实现用真机换来的，直接继承。
     */
    private fun buildSource(request: HplayMediaRequest): MediaSource {
        val url = request.url
        val localFile = url.startsWith("file://") || url.startsWith("content://")
        val nativeBoard = !localFile && runCatching { com.han1me.X99.nativePlay }.getOrDefault(false)

        // ---- Referer：按本次播放下发（官方源留空）----
        val ref = when {
            localFile -> ""
            nativeBoard -> runCatching { com.han1me.X99.refererFor(url) }.getOrDefault("")
            else -> runCatching { com.han1me.X99.mirrorRefererFor(url) }.getOrDefault("")
        }
        val headers = LinkedHashMap<String, String>()
        if (ref.isNotEmpty()) headers["Referer"] = ref
        headers.putAll(request.headers)

        // ★ 用 OkHttpDataSource 挂 App 的共享 client（代理/DoH/边缘节点全在里面）
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(httpClient)
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36",
            )
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }
        val dsFactory = DefaultDataSource.Factory(context, httpFactory)
        val uri = android.net.Uri.parse(url)

        // ---- MIME 判定：站点判据优先，后缀兜底 ----
        val declaredHls = runCatching { com.han1me.X99.mediaIsHls(url) }.getOrDefault(false)
        val suffixHls = (uri.path ?: "").lowercase().endsWith(".m3u8") ||
            (localFile && url.substringBefore('?').endsWith(".m3u8"))
        val isHls = declaredHls || suffixHls

        val item = if (isHls) {
            // 显式声明 MIME：无扩展名的清单也能被正确解析
            MediaItem.Builder()
                .setUri(uri)
                .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                .build()
        } else {
            MediaItem.fromUri(uri)
        }
        return if (isHls) {
            HlsMediaSource.Factory(dsFactory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dsFactory).createMediaSource(item)
        }
    }

    /** 供 PlayerView 挂载的底层实例（内核抽象不该泄漏类型，仅此一处例外）。 */
    fun exoPlayer(): ExoPlayer? = player

    override fun start() {
        playerHandler.post { player?.play() }
    }

    override fun pause() {
        playerHandler.post { player?.pause() }
    }

    override fun seekTo(positionMs: Long) {
        playerHandler.post {
            val p = player ?: return@post
            // seek 到未缓冲位置要提示缓冲（照 Viewer）
            val buffered = p.bufferedPosition
            if (positionMs > buffered) listener?.onBuffering()
            p.seekTo(positionMs)
        }
    }

    override fun setSpeed(speed: Float) {
        playerHandler.post { player?.setPlaybackSpeed(speed) }
    }

    override fun setLooping(looping: Boolean) {
        playerHandler.post {
            player?.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    override fun release() {
        playerHandler.post {
            runCatching { player?.release() }
            player = null
        }
    }
}
