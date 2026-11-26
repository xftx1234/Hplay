package com.han1me.lab

import okhttp3.Interceptor
import okhttp3.Response

/**
 * **每次都补 Cookie 的拦截器**（2026-11-25 第 322 轮新增）。
 *
 * ## 这一条是「CF 过一次全部不用重新验证」的核心
 *
 * 两个参考项目都是这个模型：
 *
 * ```kotlin
 * // Han1meViewer · HCookieJar.loadForRequest
 * cookies.addAll(Preferences.loginCookieStateFlow.value.toLoginCookieList(host))
 * cookies.addAll(Preferences.cloudFlareCookieStateFlow.value.toLoginCookieList(host))
 * ```
 *
 * 也就是：**cookie 不是"导入一次"，而是"每次请求都合并"**。
 *
 * 旧实现靠 `CfCookieBridge.importIntoClient` 往 `HanimeClient.cookieMap` 塞一次，
 * 而那个 map 有两条硬编码会破坏 clearance：
 *
 * 1. 导入时 `if (k.startsWith("_")) continue` —— 丢掉 `__cf_bm` / `_cfuvid`；
 * 2. 发请求时 `filterKeys { !it.startsWith("_") }` —— 再次丢掉；
 * 3. 域/路径硬编码成 `hanime1.me` + `/`，抹平了 WebView 里的 Path 语义。
 *
 * 现在改成：上游 jar 怎么处理都不管，**在应用层拦截器里把整串 Cookie 头盖上去**。
 * 上游 map 里那部分照样带着（我们只覆盖/追加同名项）。
 *
 * ## 顺手收 Set-Cookie
 *
 * CF 每次校验通过都会**刷新** `cf_clearance` 的到期时间（响应头带回新的值）。
 * 拦截器在这里把响应里的 Set-Cookie 交给 [CfCookieBridge.harvest] 落盘 ——
 * 只要 App 还在用，clearance 就一直续着，不会「隔几小时又弹一次」。
 */
class HplayCookieInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        // 只对本站生效：这条链虽然只发 hanime1.me，但保留判据防止将来复用
        val host = req.url.host.lowercase()
        if (!host.endsWith("hanime1.me")) return chain.proceed(req)

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

        // 收集 Set-Cookie（CF 刷新 clearance 就在这里）
        runCatching {
            val setCookies = resp.headers("Set-Cookie")
            if (setCookies.isNotEmpty()) CfCookieBridge.harvest(setCookies)
        }

        return resp
    }
}
