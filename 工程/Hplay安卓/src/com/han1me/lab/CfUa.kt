package com.han1me.lab

/**
 * **统一 User-Agent**（2026-11-24 第 277 轮新增，CF 过盾的命门）。
 *
 * ## 为什么必须有这个常量

 * Cloudflare 的 `cf_clearance` cookie **与发起挑战时的 User-Agent 强绑定**。
 * 拿到 clearance 之后，如果后续请求用**另一个 UA**，CF 会认为这是不同客户端，
 * 直接判定无效 —— 表现就是：验证页明明过了，接口仍然 403，
 * 或者干脆回 `Incompatible browser extension or network configuration`。
 *
 * 这就是「同一个节点，系统浏览器能过、应用里过不了」的真正原因：
 * 我们把 API 请求的 UA 写成了 **Windows 桌面版**，而验证页 WebView 用的是 Android UA。
 *
 * ## 取值依据

 * 照搬 Han1meViewer 的做法：**验证页 WebView 与所有 API 请求共用同一个常量**。
 * 值取 Android Chrome 的移动形态（与 WebView 默认 UA 形状一致），
 * 这样 WebView 侧不需要额外改 UA 也能对齐。
 *
 * ## 改动注意

 * 换这个值等于换客户端身份：**已存的 cf_clearance 会立即失效**，
 * 需要重新过一次挑战。所以不要随意改，改了要清缓存。
 */
object CfUa {

    /**
     * Android Chrome 移动 UA。
     *
     * 选 `Android 10; K` 这种「脱敏机型」写法：Chrome 新版本就是这么发的，
     * 不泄露真实机型，同时与 WebView 默认 UA 形状最接近。
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"

    /** 桌面 UA（仅个别需要桌面形态的页面用；**不要用在 CF 相关请求上**）。 */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
}
