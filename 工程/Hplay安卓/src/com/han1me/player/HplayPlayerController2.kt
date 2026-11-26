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
     * 取 App 的共享 OkHttp（带代理/DoH/边缘节点）。
     *
     * 用反射取是因为 HanimeClient 在 com.han1me 包、字段名可能随上游变化；
     * 取不到时回落一个空 client —— 那样播放会失败，但不会崩（错误会在 UI 上显示）。
     */
    private fun ctx2Http(): okhttp3.OkHttpClient {
        return runCatching {
            val cls = Class.forName("com.han1me.HanimeClient")
            val inst = cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            val m = cls.getDeclaredMethod("getHttp").apply { isAccessible = true }
            m.invoke(inst) as okhttp3.OkHttpClient
        }.getOrElse { okhttp3.OkHttpClient() }
    }

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
                errorText = error?.message ?: "播放失败"
            }
        }
    }

    /**
     * 设置媒体并起播。
     *
     * @param qualities 可选清晰度（高到低）；单清晰度传 listOf("默认") 即可
     */
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
        kernel.prepare(
            HplayMediaRequest(
                url = url,
                title = title,
                headers = headers,
                looping = uiState.isLooping,
                startPositionMs = startPositionMs,
                speed = if (startPaused) 0f else currentSpeed(),
                startPaused = startPaused,
            ),
        )
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
            bufferedPercent = if (dur > 0) ((pos * 100) / dur).toInt().coerceIn(0, 100) else 0,
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
        onDispose { runCatching { c.release() } }
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
