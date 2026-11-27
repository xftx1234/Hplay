package com.han1me.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
    // ★★★ 第 344 轮（播放链路审计 · P1）：**全屏时的返回键**。
    //
    // 旧播放器有 BackHandler(enabled = HplayEnv.isFullscreen)，重写成新播放器时丢了。
    // 后果：全屏下按返回直接走 Han1meApp 根 handler（detail != null -> detail = null），
    // 详情页被关掉、isFullscreen 残留 true、系统栏不恢复 —— 第 338 轮那个 bug 的回归。
    androidx.activity.compose.BackHandler(enabled = com.han1me.HplayEnv.isFullscreen) {
        com.han1me.HplayEnv.applyFullscreen(false)
        onFullscreen?.invoke()
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var showSeq by remember { mutableStateOf(0) }

    // 每次 showSeq 或 visible 变化时重启隐藏计时
    LaunchedEffect(showSeq, controlsVisible, state.state) {
        if (!controlsVisible) return@LaunchedEffect
        // ★ 第 344 轮：**暂停 / 播完时不自动隐藏**。
        // 原来这里无条件 delay 3.5s 就隐藏 —— 而下面那个 LaunchedEffect(state.state)
        // 在暂停时把它置 true 意在图「保持可见」，两个 effect 打架：
        // 暂停后控制条自己消失，用户还得再点一下屏幕。
        if (state.state == HplayPlayerState.PAUSED ||
            state.state == HplayPlayerState.COMPLETED ||
            state.state == HplayPlayerState.ERROR) return@LaunchedEffect
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

    // ★ 第 341 轮：进入播放器读一次系统媒体音量（手势指示器要显示真实值）；
    // 离开时把窗口亮度交还系统 —— 否则关掉播放器后屏幕会一直保持我们设的那个亮度。
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.han1me.HplayEnv.applicationOrNull()?.let { HplayPlayerVolume.readVolume(it) }
        onDispose { HplayPlayerVolume.releaseBrightness() }
    }

    // ★★★ 第 344 轮（全量核查 · P1）：**退后台/锁屏必须暂停**。
    //
    // 这一条旧播放器（HplayPlayer.kt 第 304 行）有，重写成双层状态的新播放器时**丢了**：
    //   DisposableEffect(controller, lifecycleOwner) { ON_STOP -> controller.pause() }
    //
    // 后果（真机可直接复现）：播放中按 Home 回桌面、或按电源键锁屏，
    // 声音**继续播**，锁屏界面上一路出声；用户回来时进度已经跑掉几分钟。
    // 也会和系统媒体会话、蓝牙耳机的焦点争夺打成一团。
    //
    // 这里用同一套 LifecycleEventObserver（不引额外依赖），
    // 只认 ON_STOP —— ON_PAUSE 在弹系统对话框时也会触发，太激进。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(controller, lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) controller.pause()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ★★★ 第 345 轮（用户要求「竖版视频默认竖屏，横屏视频默认横屏」）：
    //
    // 尺寸一报出来就按视频朝向给默认屏幕方向。**必须放在这里**（不是全屏按钮里）：
    // 用户要的是「进播放器就按素材朝向摆正」，而不是「点了全屏才转」。
    // 两个参考源码都只在全屏里设方向（Viewer 的 enterFullscreen / Plus 的 _fullscreen），
    // 所以这条是超出参考物、按用户明确要求加的 —— 逻辑集中在 HplayOrientation.setAuto，
    // 它内部做「先吸附到天然朝向，600ms 后放开成 SENSOR 允许自由旋转」，
    // 与本轮「旋转后保持原尺寸不填充」的要求配套（保比例在渲染层的 aspectRatio）。
    androidx.compose.runtime.LaunchedEffect(state.videoWidth, state.videoHeight) {
        if (state.videoWidth <= 0 || state.videoHeight <= 0) return@LaunchedEffect
        com.han1me.player.HplayOrientation.setAuto(appCtx, state.isPortraitVideo)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { runCatching { com.han1me.player.HplayOrientation.releaseAuto(appCtx) } }
    }

    // ★★★ 第 346 轮（用户要求）：**方向传感器 + 旋转提示**。
    //
    // 上一轮把播放朝向锁在素材天然方向，好处是「横版默认横屏」成立，
    // 代价是用户把手机转过来画面不跟。用户要求「不默认旋转，但检测到方向变化后
    // 提示可以手动旋转，并给一个旋转按钮」——所以这里只**检测 + 提示**，
    // 真正转屏由用户点按钮触发（见下面 onRotate）。
    HplayOrientationSensor.rememberSensor()
    androidx.compose.runtime.LaunchedEffect(state.videoWidth, state.videoHeight) {
        if (state.videoWidth > 0 && state.videoHeight > 0) {
            HplayOrientationSensor.setVideoPortrait(state.isPortraitVideo)
        }
    }
    val rotateHint = HplayOrientationSensor.hintVisible

    Box(modifier.background(Color.Black)) {
        // ---------- 视频画面 ----------
        //
        // ★★★ 第 344 轮（全量核查 · P1）：**渲染层改成裸 TextureView**。
        //
        // 历史三段，每段都是真机换来的，别删：
        //  ① 第 289 轮初版只放占位，真机「有声无画」——必须有 View 承接解码输出；
        //  ② 第 291 轮改 PlayerView + 反射设 surfaceType=2，想切 TextureView；
        //  ③ 第 344 轮复核发现 **② 从来没生效**，于是换掉 PlayerView。
        //
        // 为什么 ② 是空的：media3-ui 1.4.1 的 PlayerView **没有 surfaceType 字段**
        // （javap 实测只有 `private final View surfaceView`）。它只在构造函数里读
        // XML 属性 R.styleable.PlayerView_surface_type，构造完就定死。
        // 所以 getDeclaredField("surfaceType") 每次抛 NoSuchFieldException，
        // 被 runCatching 吞掉 —— 实际一直是默认的 SurfaceView。
        //
        // 现在：自己 new TextureView，调 ExoPlayer 官方
        // videoComponent.setVideoTextureView()，不碰任何私有字段。
        // 参照 Han1meViewer/ui/player/surface/PlayerSurface.kt —— 它也是自写
        // TextureView，且 onSurfaceTextureDestroyed 返回 false 保住纹理，
        // 跨布局移动时画面不重建。我们只有一处播放器，但那半条同样有用：
        // 控制条显隐、全屏切换都会改布局。
        // ★★★ 第 345 轮（用户报「横版视频默认竖屏被拉伸变形，全屏横屏也被填充拉伸」）：
        //
        // 根因：TextureView 的布局是 Modifier.fillMaxSize() —— 容器多大它就多大，
        // 而 ExoPlayer 往这个 surface 上做的是**拉伸铺满**（applyTransform 默认单位矩阵），
        // 于是任何「容器比例 != 视频比例」的场合都变形：
        //   · 竖屏手机里放横版视频（容器 9:19，视频 16:9）→ 横向压扁；
        //   · 全屏横屏里放竖版视频（容器 19:9，视频 9:16）→ 横向拉宽；
        //   · 详情页弹出的满窗浮层同理。
        //
        // 两个参考源码都不是这样做的：
        //  · Han1meViewer `ui/player/video/HanimeVideoPlayer.kt` 第 349-366 行：
        //    「直连 surface 不会自动 letterbox」，所以外面套一层
        //    `Modifier.aspectRatio(videoRatio, matchHeightConstraintsFirst = videoRatio < 1f)`
        //    并 Box 居中 —— 竖版视频优先按高度撑满，横版优先按宽度撑满。
        //  · Han1mePlus `video_player_surface.dart` 第 404 行 auto 模式：
        //    `Center(child: AspectRatio(aspectRatio: ratio, child: VideoPlayer))`。
        //    另有 crop / stretch 两个可选模式，但**默认是 auto（contain）**。
        //
        // 我们照 auto（contain）做：视频按自身宽高比居中，多余区域是父层的黑底。
        // 这样「原始尺寸不拉伸填充」「旋转后仍不填充」两条要求同时成立 ——
        // 旋转只是换了容器尺寸，aspectRatio 每次都按新的约束重新解，
        // 永远不会去填满（要填满得显式写 fillMaxSize，这里不再有）。
        //
        // matchHeightConstraintsFirst 的判据（照 Viewer）：
        //   竖版视频（ratio < 1）→ true，先吃满可用高度再定宽度；
        //   横版 / 方形 → false，先吃满可用宽度再定高度。
        val vw = state.videoWidth
        val vh = state.videoHeight
        val videoRatio = if (vw > 0 && vh > 0) vw.toFloat() / vh.toFloat() else 0f
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.runtime.key(controller.uiState.hasMedia) {
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = if (videoRatio > 0f) {
                        Modifier.aspectRatio(
                            videoRatio,
                            matchHeightConstraintsFirst = videoRatio < 1f,
                        )
                    } else {
                        // 尺寸未知（还没 prepare 完）：先铺满，避免首帧闪一下小方块。
                        // 一旦 onVideoSizeChanged 报出真实宽高，上面那个分支立刻接管。
                        Modifier.fillMaxSize()
                    },
                    factory = { ctx ->
                        android.view.TextureView(ctx).apply {
                            // ★ 第 344 轮：原来 keepScreenOn 设在 PlayerView 上，换成 TextureView
                            // 之后要跟过来 —— 否则看片时屏幕会按系统超时熄灭
                            // （用户报过「看到一半黑屏」，根因就在这里）。
                            keepScreenOn = true
                            // 纹理不能在 detach 时被回收，否则回来是黑帧，
                            // 要等下一个关键帧才恢复（真机表现为「切全屏黑一下」）。
                            surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    st: android.graphics.SurfaceTexture, w: Int, h: Int,
                                ) {
                                    controller.attachTextureView(this@apply)
                                }
                                override fun onSurfaceTextureSizeChanged(
                                    st: android.graphics.SurfaceTexture, w: Int, h: Int,
                                ) = Unit
                                override fun onSurfaceTextureDestroyed(
                                    st: android.graphics.SurfaceTexture,
                                ): Boolean = false
                                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                            }
                        }
                    },
                    // 纹理可能先就绪，也可能内核后建（懒建）。update 每帧都跑，
                    // 这里做幂等挂载：同一实例不重复挂（内部有 lastTexture 判断）。
                    update = { tv -> controller.attachTextureView(tv) },
                )
            }
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
            // ★ 第 341 轮：纵向手势的**真实**落点（之前是空实现，只画数字）。
            onVolumeDelta = { steps ->
                val c = com.han1me.HplayEnv.applicationOrNull()
                if (c != null) HplayPlayerVolume.adjustVolume(c, steps)
            },
            onBrightnessDelta = { d ->
                val c = com.han1me.HplayEnv.applicationOrNull()
                if (c != null) HplayPlayerVolume.adjustBrightness(c, d)
            },
        );

        // ---------- 锁定态：只剩一个解锁按钮 ----------
        //
        // ★ 第 344 轮（全量核查 · P1）：手势锁定原来**是个死功能**。
        //   · 没有任何地方调 setGestureLocked(true)（全项目只有传 false 的那一处）；
        //   · 即便锁上了，锁定态单击走的是 onTap（切一个看不见的控制条），
        //     而 onUnlock 这个参数从头到尾没有被调用 —— 锁上就出不来了。
        // 现在补齐三件：顶栏的锁按钮（锁上）、这里的解锁按钮（打开）、
        // 以及 gesture 层锁定态单击 → onUnlock。
        if (state.isGestureLocked) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp)
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable {
                        controller.setGestureLocked(false)
                        showControls()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("🔓", fontSize = 17.sp)
            }
        }

        // ---------- 顶部控制条 ----------
        HplayPlayerTopBar(
            state = state,
            // 锁定后控制条一律不画（否则锁定毫无意义 —— 一碰就全出来了）
            visible = controlsVisible && !state.isGestureLocked,
            onLock = { controller.setGestureLocked(true) },
            // ★ 第 346 轮：旋转按钮。点一下把屏幕转到「另一个方向」——
            // 相对当前设备握持方向翻转，而不是相对素材，这样用户想怎么看就怎么看。
            onRotate = {
                val wantPortrait = !HplayOrientationSensor.devicePortrait
                com.han1me.player.HplayOrientation.rotateTo(appCtx, wantPortrait)
                HplayOrientationSensor.clearHint()
                showControls()
            },
            rotateHint = rotateHint,
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
            visible = controlsVisible && !state.isGestureLocked,
            onSeek = { ms -> controller.seekTo(ms); showControls() },
            onPlayPause = { controller.togglePlayPause(); showControls() },
            onFullscreen = {
                // ★★★ 第 338 轮：**只走 HplayEnv 一条路**。
                //
                // 原来这里先 HplayOrientation.toggle（按视频朝向设 SENSOR_LANDSCAPE/PORTRAIT），
                // 再 HplayEnv.toggleFullscreen（内部又设一次方向）—— 两次设置互相覆盖，
                // 而且第一次用的是 state.isPortraitVideo、第二次用的是 landscape 参数，
                // 两者不一致时（竖屏视频）就被锁成竖屏，表现为「点全屏不转屏」。
                //
                // 现在 HplayEnv.applyFullscreen 内部统一 → HplayOrientation，
                // 方向只有一个来源。这里只负责驱动状态与通知宿主。
                com.han1me.HplayEnv.toggleFullscreen(
                    landscape = !state.isPortraitVideo,
                )
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

        // ---------- 错误提示 + 重试 ----------
        //
        // ★ 第 344 轮：原来只有一行红字，**没有重试按钮** ——
        // 网络抖一下就播不了，只能退出去再点进来（旧播放器是有重试的，重写时丢了）。
        if (controller.errorText.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.Center)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(16.dp),
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        controller.errorText,
                        color = HplayErrorSoft,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "重试",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                controller.retry()
                                showControls()
                            }
                            .padding(horizontal = 18.dp, vertical = 7.dp),
                    )
                }
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
