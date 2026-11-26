package com.han1me.player

/**
 * **播放器状态层**（2026-11-25 第 289 轮，照 Han1meViewer 的 `ui/player/PlayerUiState.kt`）。
 *
 * ## 为什么照它的两层设计

 * Viewer 把播放器状态拆成两层，这是它整套播放器最值得学的一点：
 *
 * | 层 | 频率 | 内容 | 消费方 |
 * |---|---|---|---|
 * | [HplayPlayerUiState] | 低频（切换/暂停/换画质） | 标题、画质、倍速、锁定、宽高 | 整个控制层 |
 * | [HplayPlayerProgress] | 高频（约 100ms） | 位置、时长、缓冲 | 只给进度条与时间文本 |
 *
 * 合并成一层的后果（我们旧实现就是这样）：每 100ms 一次位置更新会让
 * **整个控制层重组** —— 顶栏、按钮、画质选择全部白算一遍。
 * 拆开之后高频数据只驱动进度条那一小块。
 *
 * ## 与 Viewer 的差异

 * - 它用 `StateFlow` + `collectAsState`；我们用 Compose `mutableStateOf`，
 *   因为本项目没有引入 ViewModel/协程状态流那一套，直接用 Compose 快照更轻。
 * - 它的 `superResolutionIndex` 是给 MPV 内核用的；我们只有 ExoPlayer，保留字段但不生效（留位）。
 */

/** 播放器状态机。语义对照 ExoPlayer 的 `Player.STATE_*`。 */
enum class HplayPlayerState {
    /** 空闲：尚未设置媒体，或已重置。 */
    IDLE,

    /** 首次加载准备中。 */
    PREPARING,

    /** 播放中缓冲。 */
    BUFFERING,

    /** 播放中。 */
    PLAYING,

    /** 已暂停。 */
    PAUSED,

    /** 播放完毕。 */
    COMPLETED,

    /** 出错。 */
    ERROR,
}

/**
 * 低频 UI 状态。
 *
 * 变化时整个控制层重组是可接受的（用户主动操作才变），
 * 所以进度这类高频数据**不在**这里。
 */
data class HplayPlayerUiState(
    /** 当前状态机状态。 */
    val state: HplayPlayerState = HplayPlayerState.IDLE,

    /** 视频标题（顶栏显示）。 */
    val title: String = "",

    /** 可选清晰度列表（高到低）。 */
    val qualities: List<String> = emptyList(),

    /** 当前清晰度下标。 */
    val currentQualityIndex: Int = 0,

    /** 当前倍速下标（指向 [HplayPlayerDefaults.SPEEDS]）。 */
    val speedIndex: Int = HplayPlayerDefaults.DEF_SPEED_INDEX,

    /** 单视频循环。 */
    val isLooping: Boolean = false,

    /** 手势锁定（锁定后只保留锁开关）。 */
    val isGestureLocked: Boolean = false,

    /** 视频像素宽，未就绪为 0。 */
    val videoWidth: Int = 0,

    /** 视频像素高，未就绪为 0。 */
    val videoHeight: Int = 0,

    /** 封面 URL，起播前展示。 */
    val posterUrl: String? = null,

    /** 是否已设置可用媒体。 */
    val hasMedia: Boolean = false,

    /** 总时长（ms），未知为 0。 */
    val durationMs: Long = 0L,
) {
    /** 竖屏视频（宽 < 高）。用于决定默认缩放模式。 */
    val isPortraitVideo: Boolean get() = videoHeight > videoWidth && videoWidth > 0
}

/**
 * 高频进度。**只有进度条与时间文本订阅它**。
 *
 * 控制器以约 100ms 周期刷新；其余 UI 不读这个对象。
 */
data class HplayPlayerProgress(
    /** 当前播放位置（ms）。 */
    val positionMs: Long = 0L,

    /** 总时长（ms）。 */
    val durationMs: Long = 0L,

    /** 已缓冲百分比（0..100）。 */
    val bufferedPercent: Int = 0,
)

/** 播放器常量（照 Viewer 的 `PlayerDefaults.kt`）。 */
object HplayPlayerDefaults {
    /** 倍速档位。1.0 在正中间，便于左右各三档。 */
    val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    /** 默认倍速下标（= 1.0）。 */
    const val DEF_SPEED_INDEX = 2

    /** 长按临时加速到的倍速（松手恢复）。 */
    const val BOOST_SPEED = 3.0f

    /** 进度刷新周期（ms）。100ms 是人眼分辨「跟手」的下限，再快只是白烧电。 */
    const val PROGRESS_TICK_MS = 100L

    /** 快进/快退步长（ms）。双击左右各一次。 */
    const val SEEK_STEP_MS = 10_000L

    /** 控制层自动隐藏延迟（ms）。 */
    const val CONTROLS_HIDE_MS = 3_500L
}
