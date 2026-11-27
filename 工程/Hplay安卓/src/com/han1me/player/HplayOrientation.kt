package com.han1me.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * **屏幕方向控制**（第 305 轮；第 338 轮重做；第 345 轮加自动朝向）。
 *
 * ## 第 338 轮用户报的 bug
 *
 * 「全屏播放状态下使用安卓返回手势后，app 页面不竖屏反而保持横屏。」
 *
 * ## 根因（代码取证）
 *
 * 播放器全屏时调 [enterFullscreen]，把 Activity 的 requestedOrientation 设成
 * SENSOR_LANDSCAPE。退出全屏本该调 [exitFullscreen] 恢复，但它挂在两个地方：
 *
 *  · HplayEnv.exitFullscreen —— 只在 Compose 的「退出全屏」按钮里调；
 *  · HplayPlayerView2 的 DisposableEffect onDispose —— 只在播放页组件被正常销毁时执行。
 *
 * 而**安卓返回手势**在播放全屏时优先被系统手势导航拦下：Activity 直接 onBackPressed /
 * finish，播放器的 Compose 树是被**销毁路径**拆掉的，DisposableEffect 未必来得及跑；
 * 更要紧的是那时 requestedOrientation 已经是 SENSOR_LANDSCAPE，
 * Activity 自己**不会**因为内容没了就转回竖屏 —— 于是停在横屏。
 *
 * ## 第 338 轮的修法（三层，缺一层都还会复发）
 *
 *  ① [enterFullscreen] / [exitFullscreen] 记录 owner，退出时**只看 owner**；
 *  ② MainActivity.onResume / onPause 里兜底：不在播放页就强制回竖屏；
 *  ③ 锁方向期间**记住是谁锁的**，只有同一个 owner 能解锁。
 *
 * ## 第 345 轮：自动朝向（按视频朝向给默认屏幕方向）
 *
 * 用户报：「横版视频默认竖屏被拉伸变形……竖版视频默认竖屏，横屏视频默认横屏，
 * 横屏视频旋转竖屏后保持原尺寸不填充」。
 *
 * 拆成两件事，分别落在两个文件：
 *
 *  · **画面比例**（不拉伸）-> HplayPlayerView2 里按视频宽高比 aspectRatio 居中，
 *    黑底承担多余区域。照 Han1meViewer 的
 *    ui/player/video/HanimeVideoPlayer.kt 第 349-366 行（它写得很直白：
 *    「直连 surface 不会自动 letterbox」，所以自己套 aspectRatio）。
 *  · **屏幕方向**（默认按视频朝向）-> 本文件新增 [setAuto] / [releaseAuto]。
 *
 * 参考对照（两个源码的差别就在这）：
 *  · Han1meViewer VideoRouteHostScreen.enterFullscreen：竖版视频
 *    SCREEN_ORIENTATION_PORTRAIT，横版 SENSOR_LANDSCAPE；**只管全屏，
 *    非全屏不介入方向**。所以它做不到「非全屏时横版视频自动横过来」。
 *  · Han1mePlus video_player_panel._fullscreen：竖版锁
 *    portraitUp/portraitDown，横版锁 landscapeLeft/landscapeRight，
 *    退出时 setPreferredOrientations(DeviceOrientation.values) 放开。
 *    它同样只管全屏。
 *
 * 用户这次要的是**非全屏也按视频朝向**，并且**允许事后自由旋转**。
 * 两者叠加的做法是「先按视频朝向吸附到位，再放开成 SENSOR」：
 *  ① 立刻设成视频的天然朝向（横版->SENSOR_LANDSCAPE，竖版->SENSOR_PORTRAIT），
 *     让「默认横屏视频默认横屏」成立；
 *  ② 播放期间**保持**这个朝向（不自动放开）——用户要的是「默认横屏」；
 *     屏幕就转竖，画面由 aspectRatio 保比例居中，**不填充不拉伸**。
 *  ③ 全屏期间不做 ②（全屏由 [enterFullscreen] 独占）；
 *     退出全屏时 [release] 回到 ① 的基线而不是 UNSPECIFIED ——
 *     否则横版视频一退全屏就翻回竖屏，画面缩成中间一条。
 *
 * 用 [autoGen] 代次号防抖：连续切换媒体时，只有最新那一次允许把方向放开，
 * 旧的 postDelayed 回调会自己作废。
 */
object HplayOrientation {

    /** 谁锁的方向。null = 没锁，交还系统。 */
    @Volatile
    private var owner: String? = null

    /** 自动朝向的基线：null = 未介入（没有视频或已离开播放器）。 */
    @Volatile
    private var autoPortrait: Boolean? = null

    /** 自动朝向的代次。每次重设自增，旧回调凭它作废。 */
    @Volatile
    private var autoGen: Int = 0

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** 从 Context 里挖出 Activity（Compose 的 LocalContext 常常是 ContextWrapper）。 */
    fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return c as? Activity
    }

    /** 当前是否已经是横屏。 */
    fun isLandscape(ctx: Context): Boolean =
        ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 现在是否被某个 owner 锁着方向。 */
    fun lockedBy(): String? = owner

    /** 是否有自动朝向基线（非全屏播放器正在按素材朝向管方向）。 */
    fun hasAuto(): Boolean = autoPortrait != null

    // ---------------------------------------------------------------- 自动朝向

    /**
     * 按视频朝向设定**默认**屏幕方向（非全屏基线）。
     *
     * 幂等：同一朝向重复调用不会重复设方向、也不会重启放开计时。
     * 全屏锁定期间只记基线、不动方向（退出全屏时由 [release] 应用）。
     */
    fun setAuto(ctx: Context, portraitVideo: Boolean) {
        val same = autoPortrait == portraitVideo
        autoPortrait = portraitVideo
        if (owner != null) return
        // ★ 第 346 轮：用户手动旋转过就**不再跟随素材**（否则按钮一点就被覆盖）
        if (userPortrait != null) return
        val act = activityOf(ctx) ?: return
        val want = if (portraitVideo) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (act.requestedOrientation != want) {
            act.requestedOrientation = want
            com.han1me.lab.LabLog.i(
                "player.orient",
                "自动朝向 " + (if (portraitVideo) "竖屏" else "横屏") + "（保持视频天然朝向）",
            )
        }
        if (same) return
        com.han1me.lab.LabLog.i("player.orient", "自动朝向生效 " + (if (portraitVideo) "竖屏" else "横屏"))
    }

    /** 离开播放器：撤销自动朝向，交还系统。 */
    fun releaseAuto(ctx: Context, reason: String = "auto-off") {
        autoPortrait = null
        userPortrait = null
        autoGen++
        if (owner != null) return
        release(ctx, reason)
    }

    // ---------------------------------------------------------------- 全屏

    /**
     * 进入全屏：按视频朝向决定横屏还是竖屏。
     *
     * @param portraitVideo 视频是竖屏（宽<高）—— 竖屏视频全屏时保持竖屏
     * @param who 锁的持有者（默认播放器）。退出时只有同一 who 能解锁。
     */
    fun enterFullscreen(ctx: Context, portraitVideo: Boolean, who: String = "player") {
        val act = activityOf(ctx) ?: return
        owner = who
        // 全屏期间不允许自动放开（基线仍在，退出时用）
        autoGen++
        act.requestedOrientation = if (portraitVideo) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        com.han1me.lab.LabLog.i("player.orient", "进入全屏 " + (if (portraitVideo) "竖屏" else "横屏") + " by " + who)
    }

    /**
     * 退出全屏：回到自动朝向基线（有基线时），否则交还系统。
     *
     * @param who 只有锁的持有者才能解锁；传 null 表示强制解锁（Activity 重建等兜底路径）
     */
    fun exitFullscreen(ctx: Context, who: String = "player") {
        val cur = owner
        if (cur != null && cur != who) {
            com.han1me.lab.LabLog.i("player.orient", "忽略解锁请求：当前由 " + cur + " 持有，请求方 " + who)
            return
        }
        release(ctx, "exitFullscreen")
    }

    /** 解除占据（全屏或自动），按需回到基线。 */
    fun release(ctx: Context, reason: String = "") {
        owner = null
        autoGen++
        val act = activityOf(ctx) ?: return
        val ap = autoPortrait
        if (ap != null) {
            // 退出全屏后回到「按视频朝向」的基线，而不是交给系统自由旋转 ——
            // 否则横版视频一退全屏就翻回竖屏，画面缩成中间一条。
            act.requestedOrientation = if (ap) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            com.han1me.lab.LabLog.i("player.orient", "回到自动朝向基线（" + reason + "）")
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            com.han1me.lab.LabLog.i("player.orient", "方向已交还系统（" + reason + "）")
        }
    }

    /**
     * ★★ 第 346 轮（用户要求）：**手动旋转到指定方向**。
     *
     * 与 [setAuto] 的区别：setAuto 是「跟随素材天然朝向」，本方法是「用户说了算」。
     * 用户点了旋转按钮之后，屏幕方向**不再跟随素材**，也不再被自动朝向覆盖 ——
     * 所以这里把 [userPortrait] 记下来，[setAuto] 会尊重它。
     *
     * @param portrait true = 转成竖屏，false = 转成横屏
     */
    fun rotateTo(ctx: Context, portrait: Boolean) {
        val act = activityOf(ctx) ?: return
        userPortrait = portrait
        owner = null
        autoGen++
        act.requestedOrientation = if (portrait) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        com.han1me.lab.LabLog.i("player.orient", "手动旋转 " + (if (portrait) "竖屏" else "横屏"))
    }

    /** 用户手动旋转过的方向；null = 未手动干预，仍跟随素材。 */
    @Volatile
    private var userPortrait: Boolean? = null

    /** 切换。 */
    fun toggle(ctx: Context, portraitVideo: Boolean, currentlyFullscreen: Boolean) {
        if (currentlyFullscreen) exitFullscreen(ctx) else enterFullscreen(ctx, portraitVideo)
    }
}
