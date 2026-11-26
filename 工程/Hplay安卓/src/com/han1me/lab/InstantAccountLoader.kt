package com.han1me.lab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **登录后即时加载**（2026-11-25 第 320 轮；第 322 轮加静默过盾重试）。
 *
 * 用户要求：「网页登录之后，用户页和其他相关版面瞬间加载出云端内容，
 * 延迟不超过 2 秒。可以在这 2 秒中间加入正在加载态，但不允许它一直停留」。
 *
 * ## 时序
 *
 * 登录成功的**那一刻**（还在 LoginActivity 里）就开始抓，
 * 并立刻把 loadingAccount 置 true 让 UI 显示加载态。
 * 抓取完成（或超时）就写 CachedAccount + 关掉加载态。
 *
 * ## 为什么之前抓不到（第 322 轮）
 *
 * 日志证据：`GET / -> 403 len=-1` —— 撞上了还没过的 clearance。
 * 第 321 轮的做法是「等 1.5 秒再试一次」，但那时 CF 拦截器会弹可见验证页，
 * 而弹窗要人点，2 秒内不可能 —— 于是永远失败。
 *
 * 现在 CF 拦截器改成「静默 WebView 挑战」（[SilentCfVerifier]），
 * 第一次 403 会自己把 clearance 拿回来再重放，所以这里**第一次就能拿到**。
 * 保留第二次重试作为兜底（静默挑战超时的情况下）。
 */
object InstantAccountLoader {

    /** 硬超时：2 秒（用户明确要求「延迟不超过 2 秒」）。 */
    private const val TIMEOUT_MS = 2_000L

    /** 兜底重试的等待与上限。 */
    private const val RETRY_WAIT_MS = 1_200L
    private const val RETRY_TIMEOUT_MS = 4_000L

    /**
     * 登录成功后立刻调用（也可在启动 / 切换账户后调用）。
     *
     * 会：① 置加载态 ② 后台抓取 ③ 写入 CachedAccount ④ 关加载态
     */
    suspend fun loadNow(http: okhttp3.OkHttpClient) {
        // 先磁盘预热：即便这次网络失败，界面上也有上次的头像，不留空白
        runCatching { CachedAccount.warmFromDisk() }
        LoginState.beginLoadingAccount()
        try {
            var info = withContext(Dispatchers.IO) {
                withTimeoutOrNull(TIMEOUT_MS) { CloudAccount.fetch(http) }
            }
            if (info == null || info.avatarUrl.isBlank()) {
                LabLog.i("account", "首次抓取不完整，等 " + RETRY_WAIT_MS + "ms 重试")
                withContext(Dispatchers.IO) { kotlinx.coroutines.delay(RETRY_WAIT_MS) }
                val again = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(RETRY_TIMEOUT_MS) { CloudAccount.fetch(http) }
                }
                // 重试只要拿到更有用的（头像非空）就替换
                if (again != null && (info == null || again.avatarUrl.isNotBlank())) {
                    info = again
                }
            }
            if (info != null) {
                CachedAccount.set(info)
                LabLog.i(
                    "account",
                    "即时加载成功 name=" + info.userName +
                        " avatar=" + (if (info.avatarUrl.isBlank()) "无" else "有"),
                )
            } else {
                LabLog.i("account", "即时加载失败（已保留磁盘缓存）")
            }
        } catch (t: Throwable) {
            LabLog.i("account", "即时加载异常：" + (t.message ?: t.javaClass.simpleName))
        } finally {
            // 无论成功失败超时，都必须关掉加载态 —— 不允许它一直停留
            LoginState.endLoadingAccount()
        }
    }
}
