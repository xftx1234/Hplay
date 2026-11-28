package com.han1me.lab

import okhttp3.Interceptor
import okhttp3.Response

/**
 * **每次都按域补 Cookie 的拦截器**（2026-11-25 第 322 轮新增；第 355 轮改为按域）。
 *
 * ## 这一条是「CF 过一次全部不用重新验证」的核心
 *
 * 参考实现（Han1meViewer · HCookieJar.loadForRequest）是**每次请求**都按 `url.host`
 * 把 login + cloudflare 两串合并进去 —— cookie 不是"导入一次"，而是"每次都带"。
 *
 * ## 第 355 轮：从「只服务 hanime1.me」改成「按域服务所有 CF 前置站」
 *
 * 旧版第一句是 `if (!host.endsWith("hanime1.me")) return chain.proceed(req)`，
 * 于是 `javchu.com` 也是 Cloudflare 前置、也有自己的 `cf_clearance`，但这条拦截器
 * 根本不管它 —— 换节点后 javchu 页面 403 时，连"带着 clearance 重放一次"的机会都没有。
 * 用户看到的就是「关注功能随内置节点网络变化而时好时坏」。
 *
 * 现在：只要该 host 在 [CfCookieBridge.HOSTS] 里、或桥里确实存过它的 cookie，就合并；
 * 并且**按 host 收 Set-Cookie**（CF 续期 clearance 就是在这里落盘的）。
 *
 * 其它域（板块 CDN、图床）一条都不碰 —— 它们的鉴权与我们无关，多带 cookie 反而是越权。
 */
class HplayCookieInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val host = req.url.host.lowercase()
        val managed = isManaged(host)
        if (!managed) return chain.proceed(req)

        val ours = CfCookieBridge.allCookieHeader(req.url.toString())
        val next = if (ours.isBlank()) {
            req
        } else {
            // 上游自己的 Cookie 优先（它可能有更精确的域/路径信息），我们**补缺并覆盖**
            val theirs = req.header("Cookie").orEmpty()
            val merged = CfCookieBridge.mergeCookie(theirs, ours)
            req.newBuilder().header("Cookie", merged).build()
        }

        val resp = chain.proceed(next)

        // 收集 Set-Cookie（CF 刷新 clearance 就在这里）。★ 必须按 host 收。
        runCatching {
            val setCookies = resp.headers("Set-Cookie")
            if (setCookies.isNotEmpty()) CfCookieBridge.harvestFor(host, setCookies)
        }

        return resp
    }

    /** 该 host 是否归桥管理（避免把 hanime1 的会话发给无关的站）。 */
    private fun isManaged(host: String): Boolean {
        for (d in CfCookieBridge.HOSTS) {
            if (host == d || host.endsWith("." + d)) return true
        }
        return false
    }
}
