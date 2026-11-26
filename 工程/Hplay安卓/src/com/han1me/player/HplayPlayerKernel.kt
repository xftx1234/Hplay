package com.han1me.player

/**
 * **播放内核抽象层**（2026-11-25 第 289 轮，照 Han1meViewer 的 `ui/player/kernel/PlayerKernel.kt`）。
 *
 * ## 为什么要抽这一层（这是 Viewer 播放器最核心的设计）

 * Viewer 有 ExoPlayer 和 libmpv **两个内核**，各有长短：
 *
 * | 内核 | 强项 | 短板 |
 * |---|---|---|
 * | ExoPlayer | 官方支持、HLS/DASH 完备、性能稳 | 格式/解码受限，无超分 |
 * | libmpv | 几乎什么都能播、可挂 Anime4K 超分 | 体积大、与 Android 集成复杂 |
 *
 * 抽象层的价值：**上层 UI 与控制器完全不知道底下是哪个**。
 * 换内核只改一处工厂，控制层一行不动。
 *
 * ## 我们的取舍

 * 本项目只集成了 ExoPlayer（`androidx.media3`），没有 libmpv 的 native 库。
 * 所以这里**保留完整抽象**、只实现一个内核：
 *  · 现在：代码结构与 Viewer 一致，将来要接 MPV 只需新增一个实现类；
 *  · 不假装支持双内核：`KernelType` 只有 EXO，UI 上不出现内核切换开关。
 */

/** 内核类型。留 MPV 位以便将来扩展（当前未实现，UI 不暴露）。 */
enum class HplayKernelType {
    /** ExoPlayer（androidx.media3）。 */
    EXO,
}

/** 起播请求。把「放什么、从哪开始、什么速度」打包，内核只认这个结构。 */
data class HplayMediaRequest(
    /** 媒体 URL（HLS/mp4 均可）。 */
    val url: String,

    /** 标题（顶栏显示）。 */
    val title: String = "",

    /** 额外请求头（部分站点校验 Referer）。 */
    val headers: Map<String, String> = emptyMap(),

    /** 单视频循环。 */
    val looping: Boolean = false,

    /** 起播位置（ms）。断点续播用。 */
    val startPositionMs: Long = 0L,

    /** 起播倍速。 */
    val speed: Float = 1.0f,

    /** 准备好后是否暂停（预加载用）。 */
    val startPaused: Boolean = false,
)

/**
 * 内核事件回调。
 *
 * 全部有默认空实现 —— 内核不必关心自己不支持的事件。
 */
interface HplayKernelListener {
    /** 开始准备。 */
    fun onPreparing() {}

    /** 准备完成（可以起播）。 */
    fun onPrepared() {}

    /** 缓冲中。 */
    fun onBuffering() {}

    /** 开始播放。 */
    fun onPlaying() {}

    /** 已暂停。 */
    fun onPaused() {}

    /** 播放完毕。 */
    fun onCompleted() {}

    /** seek 完成。 */
    fun onSeekComplete() {}

    /** 缓冲进度（0..100）。 */
    fun onBufferedUpdate(percent: Int) {}

    /** 视频尺寸变化。 */
    fun onVideoSizeChanged(width: Int, height: Int) {}

    /** 出错。 */
    fun onError(error: Throwable? = null) {}
}

/**
 * 播放内核接口。
 *
 * 只暴露「与 UI 有关」的操作；播放器内部的缓冲策略、解码器选择
 * 都是实现类的私事，不放到接口上。
 */
interface HplayPlayerKernel {
    /** 内核类型。 */
    val type: HplayKernelType

    /** 事件监听。 */
    var listener: HplayKernelListener?

    /** 视频宽（未就绪 0）。 */
    val videoWidth: Int

    /** 视频高（未就绪 0）。 */
    val videoHeight: Int

    /** 是否正在播放。 */
    val isPlaying: Boolean

    /** 当前位置（ms）。 */
    val currentPositionMs: Long

    /** 总时长（ms）。 */
    val durationMs: Long

    /** 准备媒体。 */
    fun prepare(request: HplayMediaRequest)

    /** 开始/继续播放。 */
    fun start()

    /** 暂停。 */
    fun pause()

    /** 跳转。 */
    fun seekTo(positionMs: Long)

    /** 设置倍速。 */
    fun setSpeed(speed: Float)

    /** 设置循环。 */
    fun setLooping(looping: Boolean)

    /** 释放资源。 */
    fun release()
}
