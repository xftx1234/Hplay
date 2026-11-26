package com.han1me.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.han1me.lab.LabLog
import kotlinx.coroutines.delay

/**
 * **开屏自动检查更新**（第 331 轮接线）。
 *
 * ## 为什么单独做一个 composable
 *
 * [HplayUpdatePopup] 早就写好了（第 54 轮）但**从来没有被调用**——
 * 全项目搜索只有定义处。功能等于不存在。这一轮把它接上。
 *
 * 放在 MainActivity 的最外层，与更新面板同级（都是 [com.han1me.ui.HplayUpdateState]
 * 那一层的兄弟节点），这样它显示时能盖住一切，也不受设置页/详情页的影响。
 *
 * ## 三层节流（都是必要的）
 *
 * 1. **延迟 1.5 秒**：冷启动首屏要拉列表、图、Cookie，几十个请求同时在跑。
 *    更新检查也挤进去只会让首屏更慢，而用户此刻根本不在乎有没有新版。
 * 2. **每次进程生命周期只查一次**（[HplayUpdateState.startupChecked]）：
 *    这个 composable 会在重组时反复进入，不设标记就会反复发请求 ——
 *    匿名 GitHub 只有 60 次/小时，139 那边也有频率限制。
 * 3. **尊重用户开关**（[HplayUpdateChannel.autoCheck]）：
 *    用户关掉了就是关掉了，不能靠"反正查一下也没事"越过。
 *
 * ## 为什么只在「有更新」时弹
 *
 * 查询失败**不弹**。开屏弹一个「检查更新失败：GitHub API 限额用尽」是最讨人厌的设计 ——
 * 用户没要求检查，失败跟他无关。失败只在日志里留一行，供排查。
 * 想主动看状态的用户会自己进「设置 → 关于 → 检查更新」，那里信息是全的。
 */
@Composable
fun HplayStartupUpdate() {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<UpdateChecker.Remote?>(null) }

    LaunchedEffect(Unit) {
        if (com.han1me.ui.HplayUpdateState.startupChecked) return@LaunchedEffect
        com.han1me.ui.HplayUpdateState.startupChecked = true
        if (!HplayUpdateChannel.autoCheck) {
            LabLog.i("upd", "开屏检查已关闭（设置里 update.autoCheck=0）")
            return@LaunchedEffect
        }
        delay(1500)
        if (!UpdateChecker.tryAcquire()) {
            LabLog.i("upd", "开屏检查跳过：另一处检查正在进行")
            return@LaunchedEffect
        }
        try {
            val r = UpdateChecker.fetchRemote()
            when (r) {
                is UpdateChecker.Result.Ok -> {
                    LabLog.i("upd", "开屏检查：" + r.via + " 远端 " + r.remote.versionName +
                        " 本机 " + UpdateChecker.currentVersionName() + " 有更新=" + r.hasUpdate)
                    if (r.hasUpdate) pending = r.remote
                }
                is UpdateChecker.Result.Failed -> {
                    // 不弹。只记录。
                    LabLog.i("upd", "开屏检查失败（不打扰用户）：" + r.why)
                }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            LabLog.i("upd", "开屏检查异常（不打扰用户）：" + (t.message ?: t.toString()))
        } finally {
            UpdateChecker.release()
        }
    }

    val rem = pending ?: return
    Box(Modifier.fillMaxSize().background(Color.Transparent)) {
        HplayUpdatePopup(
            remote = rem,
            onUpdate = {
                pending = null
                // 「立即更新」= 打开更新面板，让用户看清版本/渠道/体积再决定。
                // 不在这里直接开下 —— 用户点了「立即」不代表他同意走当前渠道，
                // 面板里能换线换渠道，那是他该看到的。
                com.han1me.ui.HplayUpdateState.request()
            },
            onLater = { pending = null },
        )
    }
}