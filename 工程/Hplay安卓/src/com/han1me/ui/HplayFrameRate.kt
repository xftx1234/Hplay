package com.han1me.ui

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import com.han1me.lab.LabLog

/**
 * 帧率自适应（2026-11-26 第 339 轮）。
 *
 * 用户要求：优化全局应用流畅转场动画，默认最高帧率可根据系统自动调节。
 *
 * 为什么必须自动而不是写死 120Hz：
 *  · 耗电与发热：高刷常驻，列表静止时也在空转；
 *  · 低端机反而更卡：GPU 画不满时 Choreographer 丢帧，观感比稳定 60 差得多；
 *  · 部分 ROM 会覆盖：写死会引发反复重新协商。
 *
 * 实现用的是哪个 API（这一点踩过坑，记下来）：
 *
 * 先试过 API 30 的 Window.setFrameRate —— 本工程的 android.jar 里没有这个方法
 * （javap 实测：Window 只有 setFrameRateBoostOnTouchEnabled 等，没有 setFrameRate；
 *  WindowManager.LayoutParams 也没有 FRAME_RATE_COMPATIBILITY_DEFAULT 常量）。
 * 写上去直接编译失败，所以改为公开可用的 WindowManager.LayoutParams.preferredRefreshRate：
 *  · 它是 API 23 起就有的公开字段（javap 已确认存在）；
 *  · 语义正是我希望这个窗口跑多少帧，由系统决定是否满足；
 *  · 置 0 即交还系统自己调度，天然满足静止时不高刷的要求。
 *
 * 策略：
 *  ① 探测面板支持的最高档（Display.supportedModes），上限 120Hz
 *  ② 高刷屏（>=90Hz）才介入；60Hz 屏不动（系统默认就是最优）
 *  ③ 只在动画期间抬高 preferredRefreshRate，结束立刻置 0
 */
object HplayFrameRate {

    /** 动画期目标上限。超过 120 没有实际收益（人眼 + 触控采样都跟不上）。 */
    private const val MAX_TARGET_HZ = 120f

    /** 低于这个值不折腾（普通 60Hz 屏）。 */
    private const val MIN_USEFUL_HZ = 90f

    @Volatile
    private var applied = false

    /** 本机可用动画帧率；0 = 不支持/不处理。 */
    @Volatile
    var targetHz: Float = 0f
        private set

    /** 初始化一次（Activity.onCreate 调，幂等）。只探测能力，不改当前帧率。 */
    fun init(ctx: Context) {
        if (applied) return
        applied = true
        runCatching {
            val hz = bestHz(ctx)
            targetHz = hz
            LabLog.i("framerate", "面板可用动画帧率：" + hz + "Hz")
        }.onFailure {
            LabLog.i("framerate", "探测失败：" + it.javaClass.simpleName)
        }
    }

    /** 探测面板支持的、不超过 MAX_TARGET_HZ 的最高刷新率。 */
    private fun bestHz(ctx: Context): Float {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return 0f
        val d = wm.defaultDisplay ?: return 0f
        var best = 0f
        runCatching {
            for (m in d.supportedModes) {
                val hz = m.refreshRate
                if (hz > best && hz <= MAX_TARGET_HZ + 0.5f) best = hz
            }
        }
        if (best <= 0f) best = runCatching { d.refreshRate }.getOrDefault(0f)
        // 60Hz 屏没必要折腾（系统默认就是它）
        if (best < MIN_USEFUL_HZ) return 0f
        return best
    }

    /** 动画/滚动开始：把窗口的首选刷新率提到目标值。 */
    fun beginAnimation(activity: Activity?) {
        val act = activity ?: return
        val hz = targetHz
        if (hz <= 0f) return
        runCatching {
            val lp = act.window.attributes
            lp.preferredRefreshRate = hz
            act.window.attributes = lp
        }
    }

    /** 动画结束：置 0 交还系统（省电，不常驻高刷）。 */
    fun endAnimation(activity: Activity?) {
        val act = activity ?: return
        if (targetHz <= 0f) return
        runCatching {
            val lp = act.window.attributes
            lp.preferredRefreshRate = 0f
            act.window.attributes = lp
        }
    }
}