package com.han1me.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * **播放器控制器**（2026-11-25 第 289 轮，照 Han1meViewer 的 `HanimePlayerController.kt`）。
 *
 * ## 职责边界
 *
 * ```
 * UI (PlayerControls/手势层)
 *   ↓ 读 uiState / progress，调 play()/pause()/seekTo()/switchQuality()
 * HplayPlayerController   ← 本文件：状态机 + 高频进度泵
 *   ↓ 调用接口
 * HplayPlayerKernel       ← 抽象层，不知道底下是谁
 *   ↓ 实现
 * HplayExoKernel          ← ExoPlayer
 * ```
 *
 * ## 与 Viewer 的差异

 * - Viewer 用 `StateFlow` + `collectAsState`；我们用 Compose `mutableStateOf`。
 *   理由：本项目没有 ViewModel 层，Compose 快照状态能直接参与重组，少一层转换。
 * - Viewer 的 `events` 是 `SharedFlow`（流量确认、续播提示等一次性事件）；
 *   我们用 `pendingEvent` 单槽，够用且不用引协程流。
 */
class HplayPlayerController2(
    private val context: Context,
    val kernelType: HplayKernelType = HplayKernelType.EXO,
) {

    /** 低频状态。 */
    var uiState by mutableStateOf(HplayPlayerUiState())
        private set

    /** 高频进度（只有进度条/时间订阅）。 */
    var progress by mutableStateOf(HplayPlayerProgress())
        private set

    /** 一次性事件（消费后置空）。 */
    var pendingEvent by mutableStateOf<HplayPlayerEvent?>(null)

    /** 错误文本（UI 直接展示）。 */
    var errorText by mutableStateOf("")
        private set

    /** 诊断行（时长/轨道/缓冲）。 */
    var diag by mutableStateOf("")
        private set

    private val kernel: HplayPlayerKernel = when (kernelType) {
        // ★ 传 App 的共享 OkHttp（带代理/DoH/边缘节点）——
        // 不传的话播放器自己建 HttpURLConnection，直连会被墙（Connection reset）。
        HplayKernelType.EXO -> HplayExoKernel(context, ctx2Http())
    }

    /**
     * 取 App 的共享 OkHttp（带代理 / DoH / 边缘节点）。
     *
     * ★★★ 第 344 轮（播放链路审计 · P0）：**这里原来整段是空的**。
     *
     * 原写法 Class.forName("com.han1me.HanimeClient") —— 这个类**根本不存在**。
     * 上游把 HanimeClient 放在 `com.javrocket.hanime` 包（见 build/gen/HanimeClient.kt 第 1 行），
     * 所以那次 Class.forName 每次必然抛 ClassNotFoundException，被 getOrElse
     * 静默吞掉、回落成 `okhttp3.OkHttpClient()` —— 一个**裸客户端**：
     *   · 不走 DoH，域名解析回到系统 DNS；
     *   · 不走代理/节点，官方源直连被墙；
     *   · 不带 App 的 cookie / UA / Referer 默认值。
     * 真机表现就是「网页播放能看，内嵌播放器报 Source error / 看着看着断」。
     *
     * 现在**直接引用**（编译期就检查，再也不会静默降级）。
     * 另外这条链路要的是**媒体**口径，不是网页口径：
     *   · 读超时放宽到 90 秒 —— 大分片/慢 CDN 在网页的 30 秒上会被硬掐；
     *   · 连接池放大到 32 —— 分段下载会同时开十几条连接。
     */
    private fun ctx2Http(): okhttp3.OkHttpClient = runCatching {
        com.javrocket.hanime.HanimeClient.http.newBuilder()
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .connectionPool(okhttp3.ConnectionPool(32, 5, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }.getOrElse { com.javrocket.hanime.HanimeClient.http }

    /** 用户设定的倍速（长按加速结束后恢复到这个）。 */
    private var baseSpeed = 1.0f

    init {
        kernel.listener = object : HplayKernelListener {
            override fun onPreparing() {
                uiState = uiState.copy(state = HplayPlayerState.PREPARING)
            }

            override fun onPrepared() {
                val d = kernel.durationMs
                uiState = uiState.copy(
                    state = if (kernel.isPlaying) HplayPlayerState.PLAYING else HplayPlayerState.PAUSED,
                    durationMs = d,
                    hasMedia = true,
                    videoWidth = kernel.videoWidth,
                    videoHeight = kernel.videoHeight,
                )
                progress = progress.copy(durationMs = d)
            }

            override fun onBuffering() {
                uiState = uiState.copy(state = HplayPlayerState.BUFFERING)
            }

            override fun onPlaying() {
                uiState = uiState.copy(state = HplayPlayerState.PLAYING)
            }

            override fun onPaused() {
                if (uiState.state != HplayPlayerState.COMPLETED) {
                    uiState = uiState.copy(state = HplayPlayerState.PAUSED)
                }
            }

            override fun onCompleted() {
                uiState = uiState.copy(state = HplayPlayerState.COMPLETED)
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                uiState = uiState.copy(videoWidth = width, videoHeight = height)
            }

            override fun onError(error: Throwable?) {
                uiState = uiState.copy(state = HplayPlayerState.ERROR)
                // ★ 第 344 轮：media3 的 PlaybackException.message 恒为 Source error，
                // 真正原因在 cause 链里（HttpDataSourceException / SocketException / 403 等）。
                // 旧播放器专门拼了 cause.message，重写时丢了 —— 诊断能力回退。
                val chain = generateSequence(error as Throwable?) { it.cause }
                    .mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }
                    .distinct().take(4).joinToString(" · ")
                errorText = chain.ifBlank { error?.javaClass?.simpleName ?: "播放失败" }
            }
        }
    }

    /**
     * 设置媒体并起播。
     *
     * @param qualities 可选清晰度（高到低）；单清晰度传 listOf("默认") 即可
     */
    /**
     * 最近一次起播请求（重试用）。
     *
     * ★ 第 344 轮：新播放器出错时只画一行 errorText，**没有任何重收入口** ——
     * 用户只能退出去再点进来。旧播放器（HplayPlayer.kt）是有「重试」的，
     * 重写时丢了。这里留一份请求，供 UI 的「重试」原样重放。
     */
    private var lastRequest: HplayMediaRequest? = null

    /** 用上一次的请求重试（出错后调）。 */
    fun retry() {
        val r = lastRequest ?: return
        errorText = ""
        uiState = uiState.copy(state = HplayPlayerState.PREPARING)
        kernel.prepare(r.copy(startPaused = false))
    }

    fun setMedia(
        url: String,
        title: String = "",
        posterUrl: String? = null,
        qualities: List<String> = listOf("默认"),
        qualityIndex: Int = 0,
        headers: Map<String, String> = emptyMap(),
        startPositionMs: Long = 0L,
        startPaused: Boolean = false,
    ) {
        errorText = ""
        uiState = uiState.copy(
            state = HplayPlayerState.PREPARING,
            title = title,
            posterUrl = posterUrl,
            qualities = qualities,
            currentQualityIndex = qualityIndex.coerceIn(0, (qualities.size - 1).coerceAtLeast(0)),
            hasMedia = true,
        )
        val req = HplayMediaRequest(
            url = url,
            title = title,
            headers = headers,
            looping = uiState.isLooping,
            startPositionMs = startPositionMs,
            // ★★★ 第 344 轮（播放链路审计 · P0）：**speed 不能传 0**。
            //
            // 原来写 if (startPaused) 0f else currentSpeed()。0f 一路传到
            // ExoPlayer.setPlaybackSpeed -> PlaybackParameters.withSpeed -> checkArgument(speed > 0)，
            // 抛 IllegalArgumentException 崩主线程。预加载语义由下面的 pause() 完成，
            // 不需要把速度设成 0（Exo 根本不支持 0 倍速）。
            speed = currentSpeed(),
            startPaused = startPaused,
        )
        // 记下来供「重试」原样重放（见 lastRequest 的注释）。
        lastRequest = req
        kernel.prepare(req)
        if (startPaused) {
            // 预加载：先 prepare 但不开播，等用户点
            kernel.pause()
        }
    }

    /** 切换清晰度（保留当前进度）。 */
    fun switchQuality(index: Int, url: String) {
        val pos = kernel.currentPositionMs
        uiState = uiState.copy(currentQualityIndex = index)
        kernel.prepare(
            HplayMediaRequest(
                url = url,
                title = uiState.title,
                looping = uiState.isLooping,
                startPositionMs = pos,
                speed = currentSpeed(),
            ),
        )
    }

    fun play() {
        if (uiState.state == HplayPlayerState.COMPLETED) {
            kernel.seekTo(0)
        }
        kernel.start()
    }

    fun pause() = kernel.pause()

    fun togglePlayPause() {
        if (kernel.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        val d = kernel.durationMs
        val p = if (d > 0) positionMs.coerceIn(0L, d) else positionMs.coerceAtLeast(0L)
        kernel.seekTo(p)
        progress = progress.copy(positionMs = p)
    }

    /** 相对跳转（双击快进/快退用）。 */
    fun seekBy(deltaMs: Long) = seekTo(kernel.currentPositionMs + deltaMs)

    fun restartFromBeginning() = seekTo(0)

    /** 设置倍速档位。 */
    fun setSpeedIndex(index: Int) {
        val i = index.coerceIn(0, HplayPlayerDefaults.SPEEDS.size - 1)
        baseSpeed = HplayPlayerDefaults.SPEEDS[i]
        kernel.setSpeed(baseSpeed)
        uiState = uiState.copy(speedIndex = i)
    }

    /** 长按临时加速（松手恢复）。 */
    fun beginSpeedBoost() = kernel.setSpeed(HplayPlayerDefaults.BOOST_SPEED)

    fun endSpeedBoost() = kernel.setSpeed(baseSpeed)

    fun setLooping(looping: Boolean) {
        kernel.setLooping(looping)
        uiState = uiState.copy(isLooping = looping)
    }

    fun setGestureLocked(locked: Boolean) {
        uiState = uiState.copy(isGestureLocked = locked)
    }

    private fun currentSpeed(): Float =
        HplayPlayerDefaults.SPEEDS.getOrElse(uiState.speedIndex) { 1.0f }


    /**
     * 暴露底层 ExoPlayer 实例（供 AndroidView 的 PlayerView 挂载用）。
     *
     * 为什么需要：PlayerView 要一个真实的 ExoPlayer 才能渲染画面。
     * 但内核抽象层不该把 ExoPlayer 类型泄漏给 UI —— 所以这个 getter
     * 只在内核是 EXO 时返回实例，其他内核返回 null（UI 应改用 Surface 方案）。
     */
    fun rawExoPlayer(): androidx.media3.exoplayer.ExoPlayer? {
        return (kernel as? HplayExoKernel)?.exoPlayer()
    }

    /** 上一次挂上去的 TextureView（同一实例不重复挂，避免每帧一次 binder 调用）。 */
    private var lastTexture: android.view.TextureView? = null

    /**
     * 把渲染目标挂到内核。
     *
     * ★★★ 第 344 轮（全量核查 · P1）：**渲染层从 PlayerView 改成裸 TextureView**。
     *
     * 原来的写法（HplayPlayerView2 的 factory 里）：
     * ```kotlin
     * runCatching {
     *     val fld = PlayerView::class.java.getDeclaredField("surfaceType")
     *     fld.isAccessible = true
     *     fld.setInt(this, 2)   // 2 = SURFACE_TYPE_TEXTURE_VIEW
     * }
     * ```
     * 这段**从来没有生效过**。用 javap 查 media3-ui 1.4.1 的 PlayerView：
     *   · 字段只有 `private final View surfaceView`，**没有 surfaceType 字段**；
     *   · 它只在**构造函数**里读 XML 属性 `R.styleable.PlayerView_surface_type`
     *     （`PlayerView(Context, AttributeSet, int)`），构造完就定死了。
     * 所以 `getDeclaredField("surfaceType")` 必然抛 NoSuchFieldException，
     * 被 runCatching 静默吞掉 —— 实际仍然是默认的 SurfaceView。
     * 也就是说"必须用 TextureView 否则黑屏"这个结论**从来没落地**。
     *
     * 现在不用 PlayerView 了：直接创一个 TextureView，调 ExoPlayer 官方的
     * `videoComponent.setVideoTextureView(...)`。这条路不依赖任何私有字段，
     * 也正是 Han1meViewer 的 PlayerSurface.kt 的做法（它干脆自己写 TextureView）。
     *
     * 为什么必须 TextureView 而不是 SurfaceView：
     *   · SurfaceView 的 Surface 由 WindowManager 单独合成，**不在 View 层级里**，
     *     布局变化 / 视图复用 / Compose 的 AndroidView 重建都会让 Surface 销毁重建，
     *     而 ExoPlayer 的 output surface 不会自动跟进 → 有声音、进度在走、画面全黑；
     *   · 动画 / transform / alpha 对它无效（我们看到的就是一块黑）；
     *   · TextureView 是普通 View，走正常绘制管线，布局变化不丢 Surface。
     * 代价是多一次 GPU 拷贝，1080p 完全够。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun attachTextureView(tv: android.view.TextureView?) {
        val exo = rawExoPlayer() ?: return
        if (tv == null) {
            lastTexture = null
            runCatching { exo.videoComponent?.clearVideoSurface() }
            return
        }
        if (lastTexture === tv) return
        lastTexture = tv
        runCatching { exo.videoComponent?.setVideoTextureView(tv) }
            .onFailure { android.util.Log.w("hplay.player", "setVideoTextureView 失败：" + it.message) }
    }

    /** 释放（页面离开时调）。 */
    fun release() {
        runCatching { kernel.release() }
        uiState = HplayPlayerUiState()
        progress = HplayPlayerProgress()
    }

    /** 高频进度泵：由 UI 用 LaunchedEffect 驱动。 */
    suspend fun tickProgress() {
        val pos = kernel.currentPositionMs
        val dur = kernel.durationMs
        val playing = kernel.isPlaying
        progress = HplayPlayerProgress(
            positionMs = pos,
            durationMs = dur,
            // ★ 第 344 轮（全量核查 · P1）：**这里原来填的是「播放位置百分比」，不是缓冲**。
            //   bufferedPercent = (pos * 100) / dur
            // 底栏那个 "xx%" 徽标写的位置就在"缓冲"旁边，用户读到的是进度数字，
            // 而它跟左边刚显示的时间完全是同一个比例 —— 真正的缓冲（能跳到多远）根本看不到。
            // 现在读内核的 bufferedPercentage（ExoPlayer 给的是已缓冲占比）。
            bufferedPercent = kernel.bufferedPercent,
        )
        diag = buildString {
            append(if (playing) "播放中" else "已暂停")
            if (dur > 0) append("  长 ").append(dur / 1000).append("s");
            append("  位 ").append(pos / 1000).append("s")
        }
    }
}

/** 一次性事件。 */
sealed interface HplayPlayerEvent {
    /** 需要用户确认在移动网络下播放。 */
    data object RequestWifiConfirmation : HplayPlayerEvent

    /** 检测到历史进度（提示是否续播）。 */
    data class ShowResumeButton(val savedPositionMs: Long) : HplayPlayerEvent
}

/**
 * 记住一个控制器（照 Viewer 的 `rememberHanimePlayerController`）。
 *
 * `ownerKey` 变化时重建 —— 切视频时靠它拿到干净实例。
 */
@Composable
fun rememberHplayController2(ownerKey: String? = null): HplayPlayerController2 {
    val ctx = LocalContext.current
    val c = remember(ownerKey) { HplayPlayerController2(ctx.applicationContext) }
    DisposableEffect(c) {
        onDispose {
            runCatching { c.release() }
            // ★★★ 第 338 轮（用户报「全屏播放用返回手势后停在横屏」）：
            //
            // 播放器被销毁时必须把方向锁交还。原来只有「点退出全屏按钮」才解锁，
            // 而安卓返回手势走的是**销毁路径** —— 系统手势导航在播放全屏时优先拦下返回，
            // Activity 直接走 finish/onBackPressed，Compose 树被拆掉，
            // 于是 requestedOrientation 永远停在 SENSOR_LANDSCAPE。
            //
            // owner = null 是强制解锁：销毁路径不该再问"是谁锁的"，
            // 组件都没了，锁一定是失效的。
            runCatching {
                if (com.han1me.HplayEnv.isFullscreen) {
                    com.han1me.HplayEnv.applyFullscreen(false)
                }
                com.han1me.player.HplayOrientation.release(
                    ctx.applicationContext, "player-dispose",
                )
            }
        }
    }
    // 进度泵：只在有媒体时跑
    LaunchedEffect(c, c.uiState.hasMedia) {
        if (!c.uiState.hasMedia) return@LaunchedEffect
        while (true) {
            c.tickProgress()
            delay(HplayPlayerDefaults.PROGRESS_TICK_MS)
        }
    }
    return c
}
