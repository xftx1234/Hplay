package com.han1me.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * **设备朝向传感器**（第 346 轮 · 用户要求「加入方向传感器检测」）。
 *
 * ## 要解决的事
 *
 * 上一轮把播放朝向锁在素材的天然方向（横版 → 锁横屏、竖版 → 锁竖屏），
 * 好处是「横版视频默认横屏」成立，代价是**用户把手机转过来时画面不跟**。
 * 用户要求：不默认旋转，但检测到重力方向变化后**提示可以手动旋转**，
 * 并在左下角给一个旋转按钮。
 *
 * ## 为什么用重力传感器而不是 OrientationEventListener
 *
 * `OrientationEventListener` 走的是磁力计融合（`TYPE_ORIENTATION` 已废弃），
 * 在室内/磁干扰环境下会飘；播放器只需要判「现在是横握还是竖握」，
 * 用 `TYPE_GRAVITY` 的 x/y 分量做 atan2 就够，且不受磁场影响。
 *
 * ## 判定与防抖
 *
 * · 重力向量 → 倾角 → 归到 [portrait] / [landscape]（含左右翻转两种）；
 * · 只有**连续 [STABLE_MS] 毫秒**都落在同一侧才认为「用户真的转过去了」，
 *   避免手抖 / 走动 / 换手引起的一次性跳变；
 * · 只有**与当前视频天然朝向不同**时才把 [RotationHint.visible] 置 true ——
 *   本来就是横屏的素材、手机也横着，没必要提示。
 *
 * ## 与 HplayOrientation 的分工
 *
 * 本对象**只检测、只提示**，不改屏幕方向。真正转屏由用户点旋转按钮触发，
 * 走 `HplayOrientation.rotateTo(...)`。这样「不默认旋转」这条要求才成立。
 */
object HplayOrientationSensor {

    /** 连续多少毫秒同一侧才算稳定。300ms 是手感阈值：再短会误报，再长显得迟钝。 */
    private const val STABLE_MS = 300L

    /** 倾角容差（度）。手机与水平面夹角小于它时方向不确定，维持上一次判断。 */
    private const val FLAT_DEG = 25.0

    /**
     * 当前设备是横握还是竖握。
     *
     * true = 竖握（portrait），false = 横握（landscape）。
     * 初值取 true，等第一帧传感器数据到达后立刻被覆盖。
     */
    var devicePortrait by mutableStateOf(true)
        private set

    /**
     * 是否该提示「可以手动旋转」。
     *
     * 置 true 的条件：设备朝向稳定且**与视频天然朝向不同**。
     * 由 [updateHint] 维护；用户点旋转按钮后由 [clearHint] 清掉。
     */
    var hintVisible by mutableStateOf(false)
        private set

    /** 上一次稳定判定出的方向（null = 还没判过）。 */
    private var stablePortrait: Boolean? = null

    /** 正在计时的那一侧及其起始时刻（防抖用）。 */
    private var pendingSide: Boolean? = null
    private var pendingSince = 0L

    /** 视频天然朝向（由播放器写入）。null = 没有视频，不提示。 */
    private var videoPortrait: Boolean? = null

    /** 播放器把视频天然朝向告诉这里，并重新计算提示。 */
    fun setVideoPortrait(portrait: Boolean?) {
        videoPortrait = portrait
        recomputeHint()
    }

    /** 用户点了旋转按钮：这一轮提示到此为止（下次方向再变才会重新出现）。 */
    fun clearHint() {
        hintVisible = false
    }

    private fun recomputeHint() {
        val vp = videoPortrait ?: run { hintVisible = false; return }
        val dp = stablePortrait ?: run { hintVisible = false; return }
        // 设备朝向与素材朝向不同 → 提示可以转
        val want = vp != dp
        if (hintVisible != want) hintVisible = want
    }

    /** 由传感器回调喂入重力分量。 */
    internal fun feed(gx: Float, gy: Float, now: Long) {
        val magnitude = kotlin.math.sqrt((gx * gx + gy * gy).toDouble())
        // 平放桌面时 gx/gy 都很小，atan2 无意义 —— 维持上一次判断
        if (magnitude < 1.5) return
        // 屏幕坐标：x 向右、y 向下。竖握时 gy 占主导，横握时 gx 占主导。
        val deg = Math.toDegrees(kotlin.math.atan2(gy.toDouble(), gx.toDouble()))
        val absDeg = kotlin.math.abs(deg)
        // 接近竖直（±90°）或接近水平（0°）都算竖握；其余算横握
        val portrait = absDeg > (90.0 - FLAT_DEG) || absDeg < FLAT_DEG

        // 防抖：只在同一侧持续 STABLE_MS 之后才认账
        if (pendingSide != portrait) {
            pendingSide = portrait
            pendingSince = now
            // 第一帧：直接落定，不让人干等 300ms
            if (stablePortrait == null) {
                stablePortrait = portrait
                devicePortrait = portrait
                recomputeHint()
            }
            return
        }
        if (now - pendingSince < STABLE_MS) return
        if (stablePortrait == portrait) return
        stablePortrait = portrait
        devicePortrait = portrait
        recomputeHint()
    }

    /** 注册/注销传感器监听（Composable 内调用，离开时自动解注册）。 */
    @Composable
    fun rememberSensor(): Boolean {
        val ctx = LocalContext.current
        var registered by remember { mutableStateOf(false) }
        DisposableEffect(ctx) {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val g = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (e.values.size < 2) return
                    feed(e.values[0], e.values[1], System.currentTimeMillis())
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
            }
            if (sm != null && g != null) {
                runCatching { sm.registerListener(listener, g, SensorManager.SENSOR_DELAY_UI) }
                registered = true
            }
            onDispose {
                runCatching { sm?.unregisterListener(listener) }
                registered = false
                videoPortrait = null
                hintVisible = false
                stablePortrait = null
                pendingSide = null
                pendingSince = 0L
            }
        }
        return registered
    }
}
