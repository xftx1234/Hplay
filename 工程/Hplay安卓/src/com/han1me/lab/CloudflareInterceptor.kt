package com.han1me.lab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Interceptor
import okhttp3.Response

/**
 * **Cloudflare 质询拦截器**（第 277 轮新增；第 322 轮重写；**第 355 轮改为按域**）。
 *
 * ## 第 355 轮为什么又要改
 *
 * 用户实测：「关注功能会随内置节点网络变化而出现无法关注问题，优化机制，让 cookie 可实现
 * 包含全部云端账户请求，或者在最初 cf 验证时就一次解决所有带 cf 验证的相关问题」。
 *
 * 上一版有三处**按域失效**的硬伤：
 *
 * ① 判据用 `CfCookieBridge.hasPersisted()` —— 那是**只看 hanime1.me** 的；
 *    于是 javchu.com 永远拿不到自己域的 clearance 判断，永远走不到「有 clearance 直接重放」。
 * ② 冷却表按 host 记，但 [HplayCfHolder.needsVerify] 用全局 `hasPersisted()` 判——
 *    一个站验证过了，另一个站的「需要验证」入口就被**误判成已完成**而隐藏。
 * ③ 重放前 `applyTo` 现在按域取 cookie（第 355 轮改），这条与上面两条必须一起改，
 *    否则「有 cookie 却没带上去」。
 *
 * ## 现在的模型（与两个参考实现逐条对齐）
 *
 * ```
 *  Viewer   CloudflareInterceptor：403+cf-mitigated:challenge → 起验证页 → latch.await() → 原样重放
 *  Plus     CloudflarePage：轮询 WebView cookie → 有 cf_clearance → 存 → 回退页面重试
 *  本实现   ① 该域有 clearance → 清池 + 重放（不碰 UI）
 *           ② 没 clearance / 重放仍挑战 → 标记「该域待验证」、进冷却、返回 403
 *           ③ 首页 / 设置里的「CF 验证」按钮 → HplayGateActivity（一次抓**所有域**的 cookie）
 * ```
 *
 * ## 与参考实现的差异（保留上一轮的保守设计）
 *
 *  · 加了冷却（[HplayCfHolder.QUIET_MS]）：clearance 对**非导航的 POST** 在部分节点下不生效，
 *    每次请求都起一次 WebView 是纯浪费（实测 1~3 秒白等）；
 *  · 冷却期**仍然重放一次**（第 347 轮修）：clearance 有效时重放就该过。
 */
class CloudflareInterceptor(
    private val context: Context,
) : Interceptor {

    /** ★ 挂载即记一条，便于确认补丁真的接进了请求链。 */
    init {
        LabLog.i("cf", "CloudflareInterceptor 已挂载 —— 403+cf-mitigated:challenge 时按域处理")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(CfCookieBridge.applyTo(request))

        if (!isChallenge(response)) return response

        val url = request.url.toString()
        val host = request.url.host.lowercase()
        // 先把这条挑战响应关掉：它的连接状态已被 CF 标记，留着只会污染后续重放
        runCatching { response.close() }

        // ---------- 冷却中：只跳过「起 WebView 过盾」，仍然重放一次 ----------
        if (HplayCfHolder.inCooldown(host)) {
            runCatching { HplayCfHolder.clearPool() }
            val retry = runCatching { chain.proceed(CfCookieBridge.applyTo(request)) }.getOrNull()
            if (retry != null && !isChallenge(retry)) {
                LabLog.i("cf", "冷却中但重放成功（" + host + "）")
                HplayCfHolder.markCleared(url)
                return retry
            }
            runCatching { retry?.close() }
            LabLog.i("cf", "冷却中且重放仍被挑战，直接返回 403：" + host)
            return runCatching { chain.proceed(CfCookieBridge.applyTo(request)) }.getOrElse {
                throw java.io.IOException("Cloudflare 403（冷却中）：" + url)
            }
        }

        // ---------- ① 该域有持久化 clearance：静默重放 ----------
        if (CfCookieBridge.hasPersistedFor(host)) {
            runCatching { HplayCfHolder.clearPool() }
            val retry = runCatching { chain.proceed(CfCookieBridge.applyTo(request)) }.getOrNull()
            if (retry != null && !isChallenge(retry)) {
                LabLog.i("cf", "有 clearance，直接重放成功（" + host + "，不弹窗）")
                HplayCfHolder.markCleared(url)
                return retry
            }
            runCatching { retry?.close() }
            LabLog.i("cf", "有 clearance 但重放仍被挑战，转待验证（" + host + "）")
        }

        // ---------- ② 不弹窗，只报告「该域需要验证」 ----------
        runCatching { com.han1me.log.AppLog.step("cf.NEED", url) }
        LabLog.i("cf", "命中质询 " + host + " -> 标记待验证，交由首页按钮处理")
        HplayCfHolder.markNeedVerify(host)
        HplayCfHolder.startCooldown(host)
        return runCatching { chain.proceed(CfCookieBridge.applyTo(request)) }.getOrElse {
            throw java.io.IOException("需要 Cloudflare 验证：" + url)
        }
    }

    /** 判据与 Han1meViewer / Han1mePlus 一致：403 + `cf-mitigated: challenge`。 */
    private fun isChallenge(r: Response): Boolean {
        if (r.code != 403) return false
        val mitigated = r.header("cf-mitigated") ?: r.header("CF-Mitigated")
        return mitigated.equals("challenge", ignoreCase = true)
    }
}

/**
 * cf_clearance 的**持有者与判定**（第 355 轮：全部按域）。
 */
object HplayCfHolder {

    /** host -> 是否已拿到过 clearance（进程内记忆，重启重来）。 */
    private val cleared = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** clearance 的有效期记忆（保守 4 小时，CF 实际通常更长）。 */
    private const val TTL_MS = 4 * 60 * 60_000L

    /** 供拦截器清连接池用（由 HanimeClient 初始化时注入）。 */
    @Volatile
    var clearPool: () -> Unit = {}

    fun markCleared(url: String) {
        val h = hostKey(url) ?: return
        cleared[h] = System.currentTimeMillis()
        LabLog.i("cf", "已记录 clearance: " + h)
    }

    fun hasClearance(url: String): Boolean {
        val h = hostKey(url) ?: return false
        val at = cleared[h] ?: return false
        return System.currentTimeMillis() - at < TTL_MS
    }

    fun forget(url: String) {
        hostKey(url)?.let { cleared.remove(it) }
    }

    // ---------------- 冷却 ----------------
    private const val QUIET_MS = 3 * 60_000L

    private val cooldownAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun startCooldown(host: String) {
        if (host.isBlank()) return
        cooldownAt[host] = System.currentTimeMillis()
        LabLog.i("cf", "进入冷却 " + (QUIET_MS / 1000) + " 秒：" + host)
    }

    /** 用户过完验证后立刻解除冷却（否则要白等 3 分钟才恢复）。 */
    fun endCooldown(host: String) {
        if (host.isBlank()) return
        cooldownAt.remove(host)
        cleared[host] = System.currentTimeMillis()
        LabLog.i("cf", "冷却已解除：" + host)
    }

    // ---------------- 待验证标记 ----------------
    var needVerifyHost by mutableStateOf("")
        private set

    fun markNeedVerify(host: String) {
        if (host.isBlank()) return
        if (needVerifyHost == host) return
        needVerifyHost = host
        LabLog.i("cf", "标记待验证：" + host)
    }

    /**
     * 是否当前确实需要用户去验证。
     *
     * ★ 第 355 轮：**按被标记的那个域判**，不再用 hanime1 的全局状态
     * （旧写法会让 javchu 的待验证入口永远不显示）。
     */
    fun needsVerify(): Boolean {
        val h = needVerifyHost
        if (h.isBlank()) return false
        return !CfCookieBridge.hasPersistedFor(h)
    }

    /** 验证完成后清除标记。 */
    fun clearNeedVerify() {
        if (needVerifyHost.isBlank()) return
        needVerifyHost = ""
        LabLog.i("cf", "待验证标记已清除")
    }

    fun inCooldown(host: String): Boolean {
        if (host.isBlank()) return false
        val at = cooldownAt[host] ?: return false
        if (System.currentTimeMillis() - at < QUIET_MS) return true
        cooldownAt.remove(host)
        return false
    }

    private fun hostKey(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
}
