package com.han1me.lock

import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.han1me.X99
import com.han1me.HplayOnSurface
import com.han1me.HplayOnSurfaceVariant
import com.han1me.HplayErrorSoft

/**
 * 隐私锁（用户 2026-09-29 要求）：打开 App 时验证设备锁屏密码（KeyguardManager 凭据确认），
 * 退后台超过 [RE_LOCK_AFTER_MS] 再回来时重新上锁。
 *
 * 说明：使用系统锁屏凭据（PIN/图案/密码），App 本身不接触也不存储任何密码；
 * 无锁屏密码的设备无法验证，锁屏页会明示并允许直接进入（避免把用户锁死在门外）。
 * 开关持久化在 hanime_settings.properties（键 privacy.lock），可在「我的」面板切换。
 */
object HplayLock {
    const val RE_LOCK_AFTER_MS = 45_000L

    fun isEnabled(): Boolean = X99.setting("privacy.lock", "true").toBoolean()

    fun setEnabled(v: Boolean) = X99.putSetting("privacy.lock", v.toString())

    /**
     * **退后台超时重回时是否重新上锁**（第 152 轮，用户要求）。
     *
     * 默认 **false = 仅在打开时出现**（用户原话「隐私锁仅在打开时出现」）。
     * 置 true 则保留原来的「后台回来重新验证」（隐私性更强，但会多一次验证）。
     * 注意：无论哪种模式，第 152 轮起重新上锁都只盖遮罩、**不会重载应用**。
     */
    fun relockOnBackground(): Boolean = X99.setting("privacy.lock.relock", "false").toBoolean()

    fun setRelockOnBackground(v: Boolean) = X99.putSetting("privacy.lock.relock", v.toString())
}

@Composable
fun PrivacyLockGate(content: @Composable () -> Unit) {
    var unlocked by remember { mutableStateOf(!HplayLock.isEnabled()) }
    // ★★★ 第 152 轮（用户报「后台回来会整个重新加载应用、重新登录」）：
    //
    // 老实现是 `if (unlocked) { content() }; return` —— 一上锁就把整个 Han1meApp
    // **从组合树里摘掉**。而 App 的全部状态都是 remember（全仓无 rememberSaveable），
    // 被摘掉即清零；再解锁时 LaunchedEffect(Unit) 重跑一遍：重新拉首页、重新测速、
    // 重新检查更新、重新 whoami/profile —— 用户看到的就是「应用重启了一遍」。
    //
    // 现在：**首次解锁后内容常驻**，重新上锁只盖一层不透明遮罩，绝不销毁内容。
    var everUnlocked by remember { mutableStateOf(!HplayLock.isEnabled()) }
    var noDeviceLock by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            unlocked = true
            everUnlocked = true
        }
    }

    fun prompt() {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            noDeviceLock = true
            return
        }
        val intent = km.createConfirmDeviceCredentialIntent("Hplay 隐私锁", "验证锁屏密码后进入")
        if (intent != null) launcher.launch(intent) else noDeviceLock = true
    }

    LaunchedEffect(unlocked) {
        if (!unlocked) prompt()
    }

    // 退后台超时重回时重新上锁。
    // 第 152 轮两处修正：
    //  · **实时读设置**（原来用 remember 缓存的 enabled，导致「我的」里开关隐私锁
    //    当前进程内不生效、必须重启 App）；
    //  · 是否重锁交给 [HplayLock.relockOnBackground]（默认关 = 仅在打开时出现，
    //    这正是用户要的「隐私锁仅在打开时出现」）。
    val owner = LocalLifecycleOwner.current
    var bgAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> bgAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    if (HplayLock.isEnabled() && HplayLock.relockOnBackground() && bgAt > 0 &&
                        System.currentTimeMillis() - bgAt > HplayLock.RE_LOCK_AFTER_MS
                    ) {
                        unlocked = false
                    }
                    bgAt = 0L
                }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val shown by animateFloatAsState(
        targetValue = if (unlocked) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "unlockFade",
    )

    Box(Modifier.fillMaxSize()) {
        // 内容常驻（首次解锁后永远留在组合树里，重新上锁也不会被销毁）
        if (everUnlocked) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown }) { content() }
        }
        if (!unlocked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0D0F))
                    // 遮罩必须**吃掉所有触摸**：此时内容还在下面（alpha=0），
                    // 不拦截的话用户能盲操作到看不见的界面。
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔒", fontSize = 42.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Hplay 隐私锁", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (noDeviceLock) {
                        Text("设备未设置锁屏密码，无法验证", color = HplayErrorSoft, fontSize = 12.sp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "直接进入", color = Color.White, fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFFFF4757))
                                .clickable { unlocked = true; everUnlocked = true }
                                .padding(horizontal = 22.dp, vertical = 8.dp),
                        )
                    } else {
                        Text("需要验证锁屏密码才能进入", color = HplayOnSurfaceVariant, fontSize = 12.sp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "解锁", color = Color.White, fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFFFF4757))
                                .clickable { prompt() }
                                .padding(horizontal = 22.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 「我的」面板里的隐私锁开关（由 patches.py 补丁 16 挂进 MinePanel）。 */
@Composable
fun PrivacyLockRow() {
    var on by remember { mutableStateOf(HplayLock.isEnabled()) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "🔒 隐私锁（打开 App 验证锁屏密码）",
            color = HplayOnSurface, fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = on,
            onCheckedChange = {
                on = it
                HplayLock.setEnabled(it)
            },
        )
    }
}
