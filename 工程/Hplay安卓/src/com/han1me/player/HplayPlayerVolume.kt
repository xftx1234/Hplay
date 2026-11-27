package com.han1me.player

import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * **播放器音量 / 亮度控制**（第 341 轮，用户报「音量和亮度手势接线」）。
 *
 * ## 之前为什么是假的
 *
 * 手势层里纵向拖动只做了一件事：算一个 `50 + delta/4` 的**百分比数字画在屏幕上**，
 * 从来没有调用过任何系统 API —— 所谓「调亮度 / 调音量」纯粹是文字动画，
 * 视频音量一点没变、屏幕亮度一点没变。用户报的「接线」，指的就是这个。
 *
 * ## 真正要接的两条线
 *
 * · **音量**：`AudioManager.STREAM_MUSIC`。用 `adjustStreamVolume` 而不是 `setStreamVolume`，
 *   这样系统音量面板、其它应用的音量变化都会正常联动，也不需要「恢复原值」的善后逻辑。
 *   `FLAG_SHOW_UI` 不要加 —— 我们自己的指示器已经在屏幕上，再加一个系统面板会叠两层。
 * · **亮度**：`Window.attributes.screenBrightness`（0f..1f）。
 *   **必须写 window 属性而不是 Settings.System.SCREEN_BRIGHTNESS**：后者要 WRITE_SETTINGS 权限、
 *   而且改的是全局设置，退出应用后别的应用也变亮 —— 那是错的。
 *   用完恢复成 `BRIGHTNESS_OVERRIDE_NONE`（-1f），把亮度控制权还给系统。
 *
 * 两者都是**相对增量**（手势给的是 delta），所以这里只提供 adjust 语义，不暴露绝对设值。
 */
object HplayPlayerVolume {

    /** window 属性里表示「不覆盖系统亮度」的哨兵值。 */
    private const val NONE = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    /** 当前窗口亮度（0f..1f）；尚未读取时为 -1（= 跟随系统）。 */
    var brightness: Float
        get() = _brightness
        private set(v) { _brightness = v }
    private var _brightness by mutableFloatStateOf(NONE)

    /** 系统媒体音量百分比（0..100），供指示器显示。 */
    private var _volumePercent by mutableIntStateOf(-1)
    val volumePercent: Int get() = _volumePercent

    /**
     * 取 AudioManager。
     *
     * ★ 第 343 轮：**优先用 Activity**。部分 ROM 对从 application context 发起的
     * adjustStreamVolume 会静默丢弃 —— 返回正常、音量不动、没有任何异常。
     * 用 Activity 更接近系统音量键的实际路径。
     */
    private fun audio(ctx: Context): AudioManager? {
        val act = com.han1me.HplayEnv.activity()
        val target = act ?: ctx
        return runCatching { target.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()
    }

    /**
     * 系统媒体音量的**总级数**（通常 15，部分 ROM/车机是 100）。
     *
     * ★ 第 344 轮：手势层原来把档数写死成 15，
     * 在这些 ROM 上拖满一屏只能到 15% —— 用户报的「音量拖不满」就是这条。
     */
    fun maxSteps(ctx: Context): Int {
        val am = audio(ctx) ?: return 15
        return runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(15).coerceAtLeast(1)
    }

    /** 读出当前媒体音量百分比（进播放器时调一次）。 */
    fun readVolume(ctx: Context): Int {
        val am = audio(ctx) ?: return -1
        val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val cur = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val p = if (max > 0) (cur * 100 / max) else 0
        _volumePercent = p
        return p
    }

    /**
     * 按**步进数**调音量（正数调大）。
     *
     * 用 `adjustStreamVolume` 而不是算绝对值：系统自己能处理「已经最大/最小」的边界，
     * 也会正确触发系统侧的联动（蓝牙音量、耳机音量）。
     * 返回调完之后的百分比。
     */
    fun adjustVolume(ctx: Context, steps: Int): Int {
        val am = audio(ctx) ?: return _volumePercent
        // 上/下键与「调大/调小」的对应与系统一致：KEYCODE_VOLUME_UP 是调大。
        val dir = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val n = kotlin.math.abs(steps).coerceAtLeast(1)
        var ok = 0
        repeat(n) {
            val r = runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
            if (r.isSuccess) ok++ else
                com.han1me.lab.LabLog.i("vol", "adjustStreamVolume 异常：" + (r.exceptionOrNull()?.message ?: ""))
        }
        val p = readVolume(ctx)
        // ★ 第 343 轮：**失败必须留下痕迹**。之前没有日志，
        // 「拖了没反应」在日志里完全看不出是没调用、还是调了没生效。
        com.han1me.lab.LabLog.i(
            "vol",
            "adjust steps=" + n + " dir=" + (if (steps > 0) "up" else "down") +
                " ok=" + ok + " now=" + p + "%",
        )
        return p
    }

    /**
     * 按**相对量**调窗口亮度（delta 为 0f..1f 的相对增量，负数是调暗）。
     *
     * 首次调节时如果窗口还没覆盖过亮度，以**系统当前亮度**作为起点，
     * 否则从 0f 起步会让屏幕瞬间全黑（这是最容易踩的坑）。
     */
    fun adjustBrightness(ctx: Context, delta: Float): Float {
        val act = com.han1me.HplayEnv.activity() ?: return _brightness
        val w = act.window ?: return _brightness
        val lp = w.attributes ?: return _brightness
        val cur = if (lp.screenBrightness < 0f) systemBrightness(act) else lp.screenBrightness
        val next = (cur + delta).coerceIn(0.02f, 1f)
        lp.screenBrightness = next
        val wr = runCatching { w.attributes = lp }
        _brightness = next
        if (wr.isFailure) {
            com.han1me.lab.LabLog.i("vol", "设亮度失败：" + (wr.exceptionOrNull()?.message ?: ""))
        }
        return next
    }

    /** 系统当前亮度（0f..1f）；读不到就退回 0.5f（比 0 安全）。 */
    private fun systemBrightness(act: android.app.Activity): Float = runCatching {
        val v = android.provider.Settings.System.getInt(
            act.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS,
        )
        (v / 255f).coerceIn(0.02f, 1f)
    }.getOrDefault(0.5f)

    /** 把亮度交还给系统（退出播放器时调）。 */
    fun releaseBrightness() {
        if (_brightness < 0f) return
        runCatching {
            val act = com.han1me.HplayEnv.activity() ?: return@runCatching
            val w = act.window ?: return@runCatching
            val lp = w.attributes ?: return@runCatching
            lp.screenBrightness = NONE
            w.attributes = lp
        }
        _brightness = NONE
    }

    /** 指示器用的百分比文本（亮度未接管时显示系统亮度）。 */
    fun brightnessPercent(ctx: Context): Int {
        val v = if (_brightness < 0f) {
            val act = com.han1me.HplayEnv.activity()
            if (act != null) systemBrightness(act) else 0.5f
        } else _brightness
        return (v * 100).toInt().coerceIn(0, 100)
    }
}