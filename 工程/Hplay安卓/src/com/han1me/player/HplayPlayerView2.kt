package com.han1me.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.han1me.HplayErrorSoft
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * **完整播放器视图**（2026-11-25 第 289 轮）。
 *
 * 这是把内核 + 控制器 + 控制条 + 手势层缝在一起的那一层。
 * 对应 Viewer 的 `HanimeVideoPlayer.kt`。
 *
 * ## 层级（从下到上）
 * ```
 *  ┌─────────────────────────┐
 *  │ 视频画面（PlayerView）    │  ← AndroidView 挂 Surface
 *  ├─────────────────────────┤
 *  │ 手势层（透明，吃所有触摸） │  ← 单击/双击/长按/拖动
 *  ├─────────────────────────┤
 *  │ 顶部控制条（玻璃拟态）     │  ← 返回/标题/倍速
 *  ├─────────────────────────┤
 *  │ 底部控制条（玻璃拟态）     │  ← 进度/时间/播放/全屏
 *  └─────────────────────────┘
 * ```
 *
 * ## 控制条自动隐藏

 * Viewer 用 3.5 秒无操作隐藏。这里用 `LaunchedEffect(visible, tick)` 实现：
 * 每次可见时起一个延时，超时置隐藏；任何手势都会重置计时（靠 tick 自增）。
 */
@Composable
fun HplayPlayerView2(
    controller: HplayPlayerController2,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null,
    onFullscreen: (() -> Unit)? = null,
) {
    val state = controller.uiState
    val progress = controller.progress
    // ★ 第 305 轮：提前取 Context（Composable 调用不能进 lambda）
    val appCtx = androidx.compose.ui.platform.LocalContext.current

    // ---------- 控制条可见性（2026-11-25 第 305 轮重写）----------
    //
    // ★ 用户报「进度条自动隐藏后再点屏幕不出来」。
    //
    // 老实现的问题：LaunchedEffect(controlsVisible, tick) 只在 visible==true 时
    // 启动计时。用户点击时 tick++，但 controlsVisible 已经是 false，
    // 于是 effect 重启后走不进 if 分支 —— 控制条永远不显示。
    //
    // 正确模型（照 Viewer 的 PlayerControls）：
    //   · visible 是唯一真相
    //   · 每次 visible 变 true，起一个「3.5 秒后隐藏」的协程
    //   · 用户任何操作 -> visible = true（重复设 true 也重启计时）
    //   · 用一个自增的 showSeq 作为 key，保证「已经 true 时再点」也能重启计时
    var controlsVisible by remember { mutableStateOf(true) }
    var showSeq by remember { mutableStateOf(0) }

    // 每次 showSeq 或 visible 变化时重启隐藏计时
    LaunchedEffect(showSeq, controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        delay(HplayPlayerDefaults.CONTROLS_HIDE_MS)
        controlsVisible = false
    }

    // 显示控制条并重启计时（任何用户操作都调这个）
    val showControls: () -> Unit = {
        controlsVisible = true
        showSeq++
    }

    // 暂停/播完时保持可见
    LaunchedEffect(state.state) {
        if (state.state == HplayPlayerState.PAUSED || state.state == HplayPlayerState.COMPLETED) {
            controlsVisible = true
            showSeq++
        }
    }

    Box(modifier.background(Color.Black)) {
        // ---------- 视频画面 ----------
        //
        // ★ 2026-11-25（第 289 轮 · 真机验证后补）：**这里必须真的挂 PlayerView**。
        //
        // 初版只放了占位（想着"Surface 由外层提供"），真机跑出来是有声无画：
        // 状态行显示「播放中 长235s 位5s」，但屏幕全黑 —— 因为没有任何 View
        // 承接解码器输出的 Surface。PlayerView 就是那个承载体。
        //
        // 用 AndroidView 挂 PlayerView，并把它绑定到内核暴露的 ExoPlayer 实例。
        androidx.compose.runtime.key(controller.uiState.hasMedia) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    useController = false          // 控制条我们自己做，不用它自带的
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    // ★★★ 2026-11-25（第 295 轮 · 用户报"有声音没画面"）：
                    //
                    // **必须用 TextureView，不能用默认的 SurfaceView。**
                    //
                    // 症状（用户实测）：播放时有声音、进度在走，但画面黑；
                    // 有时又正常 —— 取决于 Surface 有没有被重新创建。
                    //
                    // 根因：PlayerView 默认 player.setVideoSurfaceType(SURFACE_TYPE_SURFACE_VIEW)。
                    // SurfaceView 的 Surface 由 WindowManager 单独管理，**不在 View 层级里绘制**。
                    // 它的已知问题：
                    //   · 布局变化 / 视图复用 / 切换 Activity 时 Surface 会被销毁重建，
                    //     而 ExoPlayer 的 output surface 不会自动跟进 -> 黑屏但有声
                    //   · 动画 / transform / alpha 对它无效（我们看到的就是"黑块"）
                    //   · 与 Compose 的 AndroidView 组合时更明显（AndroidView 会复用 View）
                    //
                    // 参考项目的做法（Han1meViewer/ui/player/surface/PlayerSurface.kt 第 36 行）：
                    //   用的是 TextureView，并注释说明「跨布局移动时复用同一个实例，播放不中断」。
                    //
                    // TextureView 是普通 View，走正常绘制管线，布局变化不会丢 Surface。
                    // 代价是性能略低于 SurfaceView（多一次 GPU 拷贝），但 1080p 完全够。
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // media3 1.4.1 没有公开的 setSurfaceType（那是 1.5 才加的），
                    // 但 PlayerView 内部有个 private 的 surface_type 字段。
                    // 用 XML 属性那条路：inflate 时读 R.styleable.PlayerView_surface_type。
                    // 这里直接反射设字段，语义与 1.5 的 setSurfaceType 一致。
                    runCatching {
                        val fld = androidx.media3.ui.PlayerView::class.java
                            .getDeclaredField("surfaceType")
                        fld.isAccessible = true
                        fld.setInt(this, 2)   // 2 = SURFACE_TYPE_TEXTURE_VIEW
                    }
                }
            },
            // ★ 真机第二次修正：**必须在 hasMedia 变化时重新绑定**。
            //
            // 内核是懒建的（第一次 prepare 才创建 ExoPlayer），而 AndroidView 的
            // factory 在首次组合时就跑了 —— 那一刻 rawExoPlayer() 还是 null，
            // 于是 pv.player 没设上，之后 update 也不一定再触发（state 引用没变）。
            // 结果就是"有声无画"：进度在走、状态行正常，但 Surface 没绑定。
            //
            // 用 key(...) 把整个 AndroidView 绑到「内核实例是否已就绪」上，
            // 就绪后强制重建并绑定。
            update = { pv ->
                val raw = controller.rawExoPlayer()
                if (raw != null && pv.player !== raw) {
                    pv.player = raw
                    android.util.Log.i("hplay.player", "PlayerView 已绑定 ExoPlayer")
                }
            },
        )
        }
        // 封面（起播前/出错时垫底）
        if (state.posterUrl != null && !state.hasMedia) {
            com.han1me.web.HplayThumb(
                url = state.posterUrl,
                contentDescription = state.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }

        // ---------- 手势层 ----------
        HplayPlayerGestureLayer(
            state = state,
            durationMs = if (progress.durationMs > 0) progress.durationMs else state.durationMs,
            currentPositionMs = progress.positionMs,
            onTap = {
                // 点一下：隐藏 -> 显示；显示 -> 隐藏（照 Viewer）
                if (controlsVisible) {
                    controlsVisible = false
                } else {
                    showControls()
                }
            },
            onDoubleTap = {
                controller.togglePlayPause()
                showControls()
            },
            onLongPressStart = { controller.beginSpeedBoost() },
            onLongPressEnd = { controller.endSpeedBoost() },
            onSeekTo = { ms ->
                controller.seekTo(ms)
                showControls()
            },
            onUnlock = { controller.setGestureLocked(false) },
        );

        // ---------- 顶部控制条 ----------
        HplayPlayerTopBar(
            state = state,
            visible = controlsVisible,
            onBack = { onBackClick?.invoke() },
            onSpeedClick = {
                val next = (state.speedIndex + 1) % HplayPlayerDefaults.SPEEDS.size
                controller.setSpeedIndex(next)
                showControls()
            },
            modifier = Modifier.align(Alignment.TopCenter),
        );

        // ---------- 底部控制条 ----------
        HplayPlayerBottomBar(
            state = state,
            progress = progress,
            visible = controlsVisible,
            onSeek = { ms -> controller.seekTo(ms); showControls() },
            onPlayPause = { controller.togglePlayPause(); showControls() },
            onFullscreen = {
                // ★ 2026-11-25（第 305 轮）：**真的调 Activity.requestedOrientation**。
                // 之前只改 Compose 标记，系统根本不知道要转屏 —— 点了没反应。
                com.han1me.player.HplayOrientation.toggle(
                    ctx = appCtx,
                    portraitVideo = state.isPortraitVideo,
                    currentlyFullscreen = com.han1me.HplayEnv.isFullscreen,
                )
                com.han1me.HplayEnv.toggleFullscreen()
                onFullscreen?.invoke()
                showControls()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        );

        // ---------- 缓冲指示 ----------
        if (state.state == HplayPlayerState.BUFFERING || state.state == HplayPlayerState.PREPARING) {
            Box(
                Modifier.align(Alignment.Center).size(42.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
        }

        // ---------- 错误提示 ----------
        if (controller.errorText.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.Center)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(16.dp),
            ) {
                Text(
                    controller.errorText,
                    color = HplayErrorSoft,
                    fontSize = 12.sp,
                )
            }
        }

        // ---------- 诊断行（左下角小字） ----------
        if (controller.diag.isNotEmpty()) {
            Text(
                controller.diag,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 2.dp),
            )
        }
    }
}
