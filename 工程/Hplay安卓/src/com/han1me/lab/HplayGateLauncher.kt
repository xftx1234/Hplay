package com.han1me.lab

import android.content.Context
import android.content.Intent

/**
 * **验证页的统一入口**（2026-11-26 第 338 轮）。
 *
 * 起 Activity 需要在主线程、需要 NEW_TASK（从非 Activity 上下文调用时），
 * 还要在结束后把「标记清除 + 全局刷新」做全。这三处在首页按钮、设置页、
 * 多账户面板里都要用 —— 复制三份必然漏。所以集中到这一个函数。
 *
 * 结束后的四件事（最容易漏的部分）：
 *  · 清 HplayCfHolder 的待验证标记，否则首页横幅一直挂着；
 *  · 解除冷却，否则刚验证完仍要等 3 分钟才恢复请求；
 *  · 清连接池，让新连接带上新 cookie（旧连接带着被质询的会话状态）；
 *  · 通知 Compose 刷新（LoginState.notifyChanged），登录态/头像/收藏才会重拉。
 */
object HplayGateLauncher {

    /**
     * 打开验证页。
     *
     * @param fresh true = 先清空 WebView cookie（**添加第二个账户**必须）
     * @param onDone 结束回调（cookie 串；取消为空）
     */
    fun open(ctx: Context, fresh: Boolean = false, onDone: ((String) -> Unit)? = null) {
        HplayGateActivity.onFinished = { cookies ->
            HplayGateActivity.onFinished = null
            runCatching { HplayCfHolder.clearNeedVerify() }
            runCatching { HplayCfHolder.endCooldown("hanime1.me") }
            runCatching { HplayCfHolder.clearPool() }
            if (cookies.isNotBlank()) {
                runCatching { LoginState.notifyChanged() }
            }
            onDone?.invoke(cookies)
        }
        runCatching {
            val i = Intent(ctx, HplayGateActivity::class.java)
            if (ctx !is android.app.Activity) i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            i.putExtra(HplayGateActivity.EXTRA_FRESH, fresh)
            ctx.startActivity(i)
        }.onFailure {
            LabLog.i("gate", "启动验证页失败：" + it.javaClass.simpleName + " " + (it.message ?: ""))
            HplayGateActivity.onFinished = null
            onDone?.invoke("")
        }
    }
}