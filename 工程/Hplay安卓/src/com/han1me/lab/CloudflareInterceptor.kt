package com.han1me.lab

import android.content.Context
import android.content.Intent
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **Cloudflare 质询拦截器**（2026-11-24 第 277 轮新增；2026-11-25 第 322 轮重写）。
 *
 * ## 为什么需要它

 * hanime1 官方站在 Cloudflare 后面，机房/节点 IP 会被发「托管挑战」：
 *   HTTP 403 + 响应头 `cf-mitigated: challenge` + 一段 JS 挑战页。
 * 这种响应**不是普通 403**，重试多少次都是同一个结果 —— 必须拿到 `cf_clearance`。
 *
 * ## 第 322 轮重写：从「每次弹窗」改成「过一次全免」
 *
 * 用户要求：「让 cf 过一次全部都不用重新验证。」
 *
 * 旧实现的致命问题：只要重放后仍 403 就**弹可见验证页**，而「重放仍 403」
 * 在 clearance 过期时是常态 —— 于是每次点 han 相关都弹一次，
 * 又因为 WebView 里带着登录 cookie 而一秒自动通过并关闭（用户看到「闪一下」）。
 *
 * 现在改成三级，**只有最后一级才是可见的**：
 *
 * ```
 *  ① 有持久化 clearance  → 直接重放（不碰 UI）
 *  ② 静默 WebView 挑战   → 1×1 透明，纯 JS 自动求解，用户看不见
 *  ③ 静默仍失败          → 才弹可见验证页（需要人点的那种）
 * ```
 *
 * 拿到 clearance 之后：
 *  · [CfCookieBridge] 落盘三段 cookie；
 *  · [HplayCookieInterceptor] **每次请求**都合并整串 Cookie 头（Viewer 的
 *    `loadForRequest` 模型），并且顺手收 Set-Cookie 续期。
 *
 * 也就是说 clearance 一旦拿到，后续所有请求自动带着走，到期也会被服务端续上 ——
 * 除非换节点/换 IP，否则不会再挑战。
 *
 * ## 与 Han1meViewer 的差异（我们这边更保守的两点）
 *
 *  · 加了 [TIMEOUT_SECONDS] 上限（原版 `latch.await()` 无超时，WebView 崩溃时会永久挂住线程）；
 *  · 重放前**清空连接池**，逼新连接重新握手 —— 旧连接上带着被质询的会话状态，重放常仍 403。
 */
class CloudflareInterceptor(
    private val context: Context,
) : Interceptor {

    /** ★ 挂载即记一条，便于确认补丁真的接进了请求链（否则无法区分「没挂上」和「没触发」）。 */
    init {
        LabLog.i("cf", "CloudflareInterceptor 已挂载 —— 403+cf-mitigated:challenge 时静默过盾")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(CfCookieBridge.applyTo(request))

        if (!isChallenge(response)) return response

        val url = request.url.toString()
        // 先把这条挑战响应关掉：它的连接状态已被 CF 标记，留着只会污染后续重放
        runCatching { response.close() }

        // ★★★ 2026-11-25（第 322 轮 · 真机实测后的冷却）：
        //
        // 关键事实：**cf_clearance 除了 UA，还绑定 TLS 指纹（JA3）**。
        // WebView 用 Chromium 的 BoringSSL 能过，OkHttp 用 Java/Conscrypt 过不了 ——
        // 无论 cookie 怎么带都是 403。日志里反复出现的
        //   「静默过盾成功，cookie 3532 字节」→「重放仍被挑战」
        // 就是这条（cookie 真的拿到了，但对 OkHttp 这条连接无效）。
        //
        // 于是每次请求都跑一遍静默挑战是纯浪费：要起 WebView、走代理、加载页面，
        // 1~3 秒白等，最后照样 403。用户看到的「点一下等半天才报错」就是它。
        //
        // 现在加冷却：拿到 clearance 后 [QUIET_MS] 内不再尝试，直接返 403 给上层
        // （上层本来就按「取不到就显示占位」处理，比如头像退回 emoji）。
        val host = request.url.host.lowercase()
        if (HplayCfHolder.inCooldown(host)) {
            LabLog.i("cf", "冷却中（" + host + "），跳过质询直接返回 403")
            return runCatching { chain.proceed(CfCookieBridge.applyTo(request)) }.getOrElse {
                throw java.io.IOException("Cloudflare 403（冷却中）：" + url)
            }
        }

        // ---------- ① 有持久化 clearance：静默重放 ----------
        if (CfCookieBridge.hasPersisted()) {
            runCatching { HplayCfHolder.clearPool() }
            val retry = runCatching { chain.proceed(CfCookieBridge.applyTo(request)) }.getOrNull()
            if (retry != null && !isChallenge(retry)) {
                LabLog.i("cf", "有 clearance，直接重放成功（不弹窗）")
                HplayCfHolder.markCleared(url)
                return retry
            }
            runCatching { retry?.close() }
            LabLog.i("cf", "有 clearance 但重放仍被挑战，转静默挑战")
        }

        // ---------- ② 静默 WebView 挑战（用户看不见） ----------
        runCatching { com.han1me.log.AppLog.step("cf.CHALLENGE", url) }
        LabLog.i("cf", "命中质询 " + request.url.host + request.url.encodedPath.take(50) + " -> 静默过盾")
        if (SilentCfVerifier.run(url)) {
            HplayCfHolder.startCooldown(host)
            runCatching { HplayCfHolder.clearPool() }
            val retry = runCatching { chain.proceed(CfCookieBridge.applyTo(request)) }.getOrNull()
            if (retry != null && !isChallenge(retry)) {
                LabLog.i("cf", "静默过盾成功，重放成功")
                HplayCfHolder.markCleared(url)
                return retry
            }
            runCatching { retry?.close() }
            LabLog.i("cf", "静默过盾拿到 cookie 但重放仍被挑战")
        }

        // ---------- ③ 兜底：可见验证页（真需要人点的那种挑战） ----------
        if (!runVerification(url)) {
            LabLog.i("cf", "可见验证也未通过，原样返回 403")
            return runCatching { chain.proceed(CfCookieBridge.applyTo(request)) }.getOrElse {
                throw java.io.IOException("Cloudflare 质询未通过：" + url)
            }
        }
        HplayCfHolder.startCooldown(host)
        runCatching { HplayCfHolder.clearPool() }
        LabLog.i("cf", "可见验证通过，重放请求")
        return chain.proceed(CfCookieBridge.applyTo(request))
    }

    /** 判据与 Han1meViewer / Han1mePlus 一致：403 + `cf-mitigated: challenge`。 */
    private fun isChallenge(r: Response): Boolean {
        if (r.code != 403) return false
        val mitigated = r.header("cf-mitigated") ?: r.header("CF-Mitigated")
        return mitigated.equals("challenge", ignoreCase = true)
    }

    /** 起可见验证页并等待结果。 */
    private fun runVerification(url: String): Boolean {
        val latch = CountDownLatch(1)
        CfVerifyActivity.onFinished = {
            CfVerifyActivity.onFinished = null
            latch.countDown()
        }
        val started = runCatching {
            val i = Intent(context, CfVerifyActivity::class.java)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            i.putExtra(CfVerifyActivity.EXTRA_URL, url)
            context.startActivity(i)
            true
        }.getOrElse {
            LabLog.i("cf", "验证页启动失败：" + it.javaClass.simpleName + " " + (it.message ?: ""))
            false
        }
        if (!started) {
            CfVerifyActivity.onFinished = null
            return false
        }
        val ok = runCatching { latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!ok) return false
        return CfCookieBridge.hasPersisted() || HplayCfHolder.hasClearance(url)
    }

    companion object {
        /** 等用户过盾的上限。太短来不及操作，太长会把请求线程吊死。 */
        private const val TIMEOUT_SECONDS = 180L
    }
}

/**
 * cf_clearance 的**持有者与判定**。
 *
 * 为什么要单独一个 object：
 *  · 拦截器要能问「这个 host 现在有没有 clearance」；
 *  · 验证页要能在拿到 cookie 后**写进来**；
 *  · 还要能清连接池（重放前必做）。
 * 三者跨类，用 object 比互相传引用干净。
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
        val h = hostOf(url) ?: return
        cleared[h] = System.currentTimeMillis()
        LabLog.i("cf", "已记录 clearance: " + h)
    }

    fun hasClearance(url: String): Boolean {
        val h = hostOf(url) ?: return false
        val at = cleared[h] ?: return false
        return System.currentTimeMillis() - at < TTL_MS
    }

    fun forget(url: String) {
        hostOf(url)?.let { cleared.remove(it) }
    }

    // ---------------- 冷却（第 322 轮） ----------------
    //
    // 背景：cf_clearance 绑定 TLS 指纹，OkHttp 这条连接**永远过不了**。
    // 拿到 clearance 之后 3 分钟内不再尝试，避免「每次请求都白等 1~3 秒」。
    // 这个时长是权衡：太短会重复白等，太长则真正恢复（换节点 / CF 放宽）后要等太久。
    private const val QUIET_MS = 3 * 60_000L

    private val cooldownAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun startCooldown(host: String) {
        if (host.isBlank()) return
        cooldownAt[host] = System.currentTimeMillis()
        LabLog.i("cf", "进入冷却 " + (QUIET_MS / 1000) + " 秒：" + host)
    }

    fun inCooldown(host: String): Boolean {
        if (host.isBlank()) return false
        val at = cooldownAt[host] ?: return false
        if (System.currentTimeMillis() - at < QUIET_MS) return true
        cooldownAt.remove(host)
        return false
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
}
