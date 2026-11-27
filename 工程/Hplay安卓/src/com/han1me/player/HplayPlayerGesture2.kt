package com.han1me.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * **播放器手势层**（2026-11-25 第 289 轮，照 Han1meViewer 的 `PlayerGestureLayer.kt`）。
 *
 * ## 手势协议（与 Viewer 一致）

 * | 手势 | 行为 |
 * |---|---|
 * | 单击 | 显示/隐藏控制条 |
 * | 双击 | 播放 ⇄ 暂停 |
 * | 长按 | 临时 3× 加速（松手恢复） |
 * | 横向拖动 | 调进度（松手才 seek，拖动中只显示预览） |
 * | 纵向拖动 | 左半边调亮度 / 右半边调音量 |
 *
 * ## 为什么拖动中不 seek

 * 拖动过程中每帧 seek 会让解码器反复重排（ExoPlayer 会不断丢弃缓冲），
 * 结果是指示器数字在跳、画面卡死。Viewer 的做法是**松手才 seek**，
 * 拖动中只更新一个本地浮点值用于显示预览。我们沿用。
 *
 * ## 锁定态

 * `locked = true` 时除「解锁」外的全部手势失效 —— 防止躺在床上看时误触。
 */
@Composable
fun HplayPlayerGestureLayer(
    state: HplayPlayerUiState,
    durationMs: Long,
    currentPositionMs: Long,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * ★ 第 341 轮：**纵向拖动真的调音量/亮度了**。
     *
     * 之前 dragMode==2 只算了一个百分比数字画在屏幕上，没调任何系统 API。
     * 现在把「这一步该调多少」交给宿主（它才知道当前音量/亮度的真实值），
     * 手势层只负责把**累积位移**换算成步进数与相对量。
     */
    onVolumeDelta: (Int) -> Unit = {},
    onBrightnessDelta: (Float) -> Unit = {},
) {
    val curTap by rememberUpdatedState(onTap)
    val curDouble by rememberUpdatedState(onDoubleTap)
    val curLongStart by rememberUpdatedState(onLongPressStart)
    val curLongEnd by rememberUpdatedState(onLongPressEnd)
    val curSeek by rememberUpdatedState(onSeekTo)
    val curVolume by rememberUpdatedState(onVolumeDelta)
    val curBrightness by rememberUpdatedState(onBrightnessDelta)
    // ★ 第 344 轮：锁定态下的单击必须走 onUnlock（唤出解锁按钮），
    // 原来这里传的是 curTap —— 而锁定态下控制条根本不画，curTap 只会把
    // 一个看不见的 controlsVisible 翻来翻去，用户被锁住后无路可退。
    val curUnlock by rememberUpdatedState(onUnlock)
    // ★ 第 343 轮：位置也用 rememberUpdatedState —— 否则 pointerInput 块里读到的是
    // 首次组合时的旧值（见 onDragStart 的注释）。
    val curPos by rememberUpdatedState(currentPositionMs)
    // ★★★ 第 344 轮（播放链路审计 · P0）：**时长也要包一层**。
    //
    // dragStartPos 在 343 轮已经用 rememberUpdatedState 修过，很容易以为同类问题都清了。
    // 但 durationMs 与它同源同险，而且症状更隐蔽：
    //   · pointerInput 协程块只在 key（isGestureLocked）变化时重建，闭包固化首次组合值；
    //   · 首次组合时 setMedia 还在 LaunchedEffect 里没跑，durationMs 必为 0；
    //   · onDragEnd 里 durationMs > 0 于是恒为 false ⇒ 松手不 seek；
    //   · 而预览框读的是 Composable 参数（最新值），照常显示时间。
    // 用户看到的就是「拖动有预览、松手没反应」—— 被预览层掩盖了。
    val curDuration by rememberUpdatedState(durationMs)
    // 音量总级数在 Composable 顶部取一次（LocalContext 不能在 pointerInput 的 lambda 里调）。
    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    val maxVolNow = remember(ctx0) { HplayPlayerVolume.maxSteps(ctx0) }

    // 拖动预览状态
    var dragMode by remember { mutableStateOf(0) }   // 0 无 / 1 横向 / 2 纵向
    var dragDelta by remember { mutableFloatStateOf(0f) }
    var dragStartPos by remember { mutableFloatStateOf(0f) }
    var dragSide by remember { mutableStateOf(0) }   // 0 左(亮度) / 1 右(音量)
    // ★ 第 341 轮：纵向拖动**已应用到系统的位移**。每次只把「新增那一段」交给宿主，
    // 否则每帧都按累积值重算，音量会一路狂飙到最大（这是增量式 API 的经典踩坑）。
    var appliedY by remember { mutableFloatStateOf(0f) }
    // 拖动开始时把画布宽/高记下来 —— 预览区在 PointerInputScope 之外，那里读不到 size。
    var dragWidth by remember { mutableFloatStateOf(1f) }
    var dragHeight by remember { mutableFloatStateOf(1f) }
    // ★ 第 343 轮：音量手势的**未消费位移**余数。
    // 原来逐帧 (fresh / 每格像素).toInt() —— 每帧只移动几像素，商恒为 0，
    // 而 appliedY 已经被更新，那一小段位移就**永久丢失**；结果是怎么拖都调不动音量。
    // 现在把余数留着，累计够一整格才调一次。
    var volumeCarry by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .fillMaxSize()
            // ★★★ 2026-11-25（第 316 轮 · 用户报「控制条隐藏后点不出来」）：
            //
            // 根因：pointerInput 的 key 里放了 currentPositionMs —— 它**每 100ms 变一次**
            // （进度泵频率）。key 变化会让整个手势检测器重建，
            // 单击事件在重建间隙几乎必丢 —— 表现就是「点了没反应」。
            //
            // 修法：key 只留 isGestureLocked（真正需要重置手势的状态）。
            // 需要读的实时值（durationMs / position）通过 rememberUpdatedState 取。
            .pointerInput(state.isGestureLocked) {
                if (state.isGestureLocked) {
                    // 锁定态：只有单击，且必须是「唤出解锁按钮」而不是「切控制条」。
                    detectTapGestures(onTap = { curUnlock() })
                    return@pointerInput
                }
                detectTapGestures(
                    onTap = { curTap() },
                    onDoubleTap = { curDouble() },
                    onLongPress = {
                        curLongStart()
                    },
                    // ★ 第 344 轮（全量核查 · P0）：**长按加速必须能恢复**。
                    //
                    // 根因：detectTapGestures 没有 onLongPressEnd 这个参数 —— 它只在
                    // 长按判定成立的**那一刻**回调一次 onLongPress，之后不再通知"松手"。
                    // 所以原来 onLongPressEnd 这个参数定义了、包了 rememberUpdatedState、
                    // 一路传进来，却**从来没有被调用过**（全项目 grep 只有赋值）。
                    //
                    // 真机后果：长按一次屏幕 → setSpeed(3.0f) → 一松手仍在 3 倍速播放，
                    // 而且没有任何 UI 能看出来（顶栏显示的还是原来的档位）。
                    // 只有用户手动点一次倍速按钮才会被覆盖回来。
                    //
                    // 正确写法（照 Han1meViewer 的 PlayerGestureLayer.playerGestures）：
                    // onPress 挂住整段按压生命周期，tryAwaitRelease() 返回时（抬起或被取消）
                    // 一定调 end —— 幂等，没加速时调也只是把速度设回 baseSpeed。
                    onPress = {
                        tryAwaitRelease()
                        curLongEnd()
                    },
                )
            }
            .pointerInput(state.isGestureLocked) {
                if (state.isGestureLocked) return@pointerInput
                detectDragGestures(
                    onDragStart = { off ->
                        dragDelta = 0f
                        appliedY = 0f
                        volumeCarry = 0f
                        dragMode = 0
                        // ★★★ 第 343 轮（用户报「播放到 5 分钟，拉动却从头开始」）：
                        //
                        // 这里原来直接读 currentPositionMs —— 但它是**函数参数**，
                        // 而 pointerInput 的协程块只在 key（isGestureLocked）变化时才重建。
                        // 也就是说闭包捕获的是**首次组合那一刻**的位置（通常是 0）。
                        // 于是无论播到第几分钟，拖动的起点永远是 0 → 用户看到的「从头快进」。
                        //
                        // 改用 rememberUpdatedState 包一层（curPos 永远是最新值）。
                        dragStartPos = curPos.toFloat()
                        dragSide = if (off.x < size.width / 2f) 0 else 1
                        dragWidth = size.width.toFloat().coerceAtLeast(1f)
                        dragHeight = size.height.toFloat().coerceAtLeast(1f)
                    },
                    onDrag = { _, delta ->
                        if (dragMode == 0) {
                            // 判定主轴：前 12px 决定是横还是纵
                            if (abs(delta.x) > abs(delta.y)) dragMode = 1
                            else if (abs(delta.y) > abs(delta.x)) dragMode = 2
                        }
                        when (dragMode) {
                            1 -> dragDelta += delta.x
                            2 -> {
                                // 向上拖动 = 调大（屏幕 y 轴向下为正，所以取负）
                                dragDelta += -delta.y
                                // 只把「自上次以来新增的那一段」交给系统
                                val fresh = dragDelta - appliedY
                                if (fresh != 0f) {
                                    appliedY = dragDelta
                                    if (dragSide == 1) {
                                        // 右侧＝音量：整屏高度 ≈ 音量总级数（通常 15 级）
                                        //
                                        // ★★★ 第 343 轮（用户报「音量手势接线不行，无法操控系统声音」）：
                                        //
                                        // 老写法 (fresh / (h/15)).toInt() 有两个致命问题：
                                        //  ① 逐帧的 fresh 只有几像素，而一格要 100+ 像素
                                        //     ⇒ 商恒为 0，永远不调；
                                        //  ② 即便偶尔够一格，余数被 appliedY 吃掉 —— 那一段位移永久丢失。
                                        // 两点叠加的最终效果就是「怎么拖都没反应」。
                                        //
                                        // 现在用未消费余数累计：够一格才调一次，不够就攒着。
                                        // ★ 第 344 轮：档数不能写死 15。
                                        // 有些 ROM/车机的 getStreamMaxVolume 是 100，
                                        // 写死 15 的话拖满一屏只能到 15%。
                                        val maxVol = maxVolNow.coerceAtLeast(1)
                                        val perStep = (dragHeight / maxVol.toFloat()).coerceAtLeast(1f)
                                        volumeCarry += fresh
                                        val steps = (volumeCarry / perStep).toInt()
                                        if (steps != 0) {
                                            volumeCarry -= steps * perStep
                                            curVolume(steps)
                                        }
                                    } else {
                                        // 左侧＝亮度：整屏高度 ≈ 100% 亮度变化
                                        curBrightness(fresh / dragHeight)
                                    }
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        if (dragMode == 1 && curDuration > 0) {
                            // ★★★ 第 341 轮（用户报「进度条手势拉动快进过少」）：
                            //
                            // 老算法是硬编码「每 4px = 1 秒」。真机上一屏宽度 1080px，
                            // 也就是说**拖满整屏只能走 270 秒** —— 对 40 分钟的片子不到 1/9，
                            // 用户感觉「拉半天才挪一点」。
                            //
                            // 现在按**屏幕宽度映射到「整片时长的 2 倍」**：
                            // 拖满一屏 = 可跨越 2 倍全片时长，手感与 MX Player / VLC 一致
                            // （既能精细微调，也能一次拉很远）。
                            // 下限给 90 秒：短片拖满一屏也能覆盖全程，不会被 2× 缩得过小。
                            // ★ 第 343 轮（用户要求「快进时间要多一些」）：2 倍 → **3 倍**。
                            //
                            // 选 3 而不是 4：实测 4 倍时**拖 25% 屏宽就撞到片尾**（0.25×4=1），
                            // 长片上一不小心就跳完，反而难用。3 倍下要拖过 1/3 屏才到结尾，
                            // 40 分钟的片子拖 1/3 屏 ≈ 40 分钟，比原来的「每 4px=1 秒」（满屏仅 270 秒）宽得多。
                            // 下限 120 秒：短片也不至于要拖半天。
                            val span = (curDuration.toFloat() * 3f).coerceAtLeast(120_000f)
                            // ★ 第 344 轮：用 onDragStart 记下的 dragWidth，而不是 onDragEnd 时的 size.width。
                            // 两者理论上相同，但全屏切换/控制条显隐会改布局 —— 预览用的是 dragWidth，
                            // 落点若用 size.width 就会与预览差一截（松手跳一下）。
                            val deltaMs = (dragDelta / dragWidth * span).toLong()
                            val target = (dragStartPos.toLong() + deltaMs)
                                .coerceIn(0L, curDuration)
                            curSeek(target)
                        }
                        dragMode = 0
                        dragDelta = 0f
                        appliedY = 0f
                        volumeCarry = 0f
                    },
                    onDragCancel = {
                        dragMode = 0
                        dragDelta = 0f
                        appliedY = 0f
                        volumeCarry = 0f
                    },
                )
            },
    ) {
        // 拖动预览指示器
        if (dragMode == 1 && durationMs > 0) {
            // ★ 第 341 轮：与 onDragEnd 用**同一个公式**（预览与落点必须一致，
            // 否则松手后会「跳一下」——差值就是两套系数之差）。
            // ★ 第 343 轮：与 onDragEnd **同一个公式**（3 倍 / 下限 120 秒）。
            val span = (durationMs.toFloat() * 3f).coerceAtLeast(120_000f)
            val deltaMs = (dragDelta / dragWidth * span).toLong()
            val target = (dragStartPos.toLong() + deltaMs).coerceIn(0L, durationMs)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    fmt(target) + " / " + fmt(durationMs),
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
        }
        if (dragMode == 2) {
            // ★ 第 341 轮：显示**真实值**（之前是凭位移算出来的假百分比，与实际音量无关）。
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val pct = if (dragSide == 0) HplayPlayerVolume.brightnessPercent(ctx)
                      else HplayPlayerVolume.volumePercent.coerceAtLeast(0)
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (dragSide == 0) "☀" else "🔊", color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("$pct%", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}
