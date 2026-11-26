package com.han1me.web

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Environment
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.regex.Pattern

/**
 * Android WebView 封装（对应桌面 wry/WebView2 的 AppWebView 体系）。
 * 只暴露与桌面封装等价的 API 面：state(lastLoadedUrl / pageTitle / canGoBack) +
 * navigator(loadUrl / navigateBack / evaluateJavaScript)，上游 Han1meWeb.kt 的
 * 去广告 / 起播 / 剧场等 JS 注入逻辑按原名调用（build 时以补丁换类型）。
 *
 * UA 去掉 "; wv" 标记 —— 镜像/合集站点 CDN 只放行真实浏览器（桌面版走 WebView2 真指纹，
 * Android 端 WebView 默认 UA 带 wv，会被部分站点识别为内嵌浏览器）。
 */
class HplayWebState(initialUrl: String) {
    var initialUrl: String = initialUrl
    var lastLoadedUrl by mutableStateOf<String?>(null)
    var pageTitle by mutableStateOf<String?>(null)
    var canGoBack by mutableStateOf(false)

    /** 当前主文档主机：判定「第三方」用（网络线程读、UI 线程写；一律小写归一）。 */
    @Volatile
    internal var mainHost: String = if (isExternalScheme(initialUrl)) "" else hostOf(initialUrl)

    /** 站点页真全屏（HTML5 requestFullscreen）的宿主视图与回调；null 表示不在全屏。 */
    internal var fsHost: FrameLayout? = null
    internal var fsCallback: WebChromeClient.CustomViewCallback? = null

    /** 全屏期间抢在应用级出栈之前的返回键回调（见 webEnterFullscreen）。 */
    internal var fsBack: androidx.activity.OnBackPressedCallback? = null

    /**
     * **本页生命周期内**的媒体 host 白名单（挂在 state 上，绝不放文件级/进程级）。
     *
     * 进程级永久表会被一次污染就终身生效：任一页面（包括广告页自己的 iframe）出现
     * `X/a.js → 302 → Y/hls/p.ts`，Y 就被登记，此后**任何站点**向 Y 发媒体形态子资源全部放行，
     * 而 classic script 只看响应 MIME 不看扩展名 —— 上一棒刚关掉的弹屏脚本通道等于被重开。
     * 换站（registrable 变化）即清空，页面容器销毁随之消失。
     */
    internal val mediaHosts: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * 认领主文档主机。**换到另一个站时必须清媒体白名单**——白名单的语义是「本站发起的播放链」，
     * 带着上一站的授权去判本站的请求，等于把豁免做成跨站通行证。
     */
    internal fun claimMainHost(h: String) {
        if (h.isEmpty()) return
        if (registrable(mainHost) != registrable(h)) mediaHosts.clear()
        mainHost = h
    }

    internal var webView: WebView? = null
}

class HplayWebNavigator(private val state: HplayWebState) {
    val canGoBack: Boolean
        get() = state.canGoBack

    fun loadUrl(u: String) {
        val w = state.webView ?: return
        // ⟳ 刷新按的是 lastLoadedUrl；若上一次主文档被弹屏广告带走并给了空白页，
        // 这里不能把广告域认领成站点域，也不能真的再去一次广告。
        if (isAdHijack(u, state.mainHost) || isExternalScheme(u)) return
        state.claimMainHost(hostOf(u))
        w.post { w.loadUrl(u) }
    }

    fun navigateBack() {
        val w = state.webView ?: return
        w.post { if (w.canGoBack()) w.goBack() }
    }

    /** 与桌面 AppWebViewNavigator.evaluateJavaScript 同名同形（callback 在主线程回调；空结果以空串给出）。 */
    fun evaluateJavaScript(js: String, callback: (String) -> Unit = {}) {
        val w = state.webView
        if (w == null) {
            // WebView 还没挂上时**也必须回调**：调用方普遍写成 runCatching{evaluateJavaScript(...)}{回调}，
            // 「压根没回调」不在 onFailure 覆盖范围内，于是顶栏回显一直停在上一次的值。
            // 屏显是本端唯一通道，停更比报错更坏——会把「探针没数据」误读成「播放器正常」。
            runCatching { callback("") }
            return
        }
        w.post { w.evaluateJavascript(js) { r -> callback(r ?: "") } }
    }
}

@Composable
fun rememberHplayWebState(url: String): HplayWebState =
    remember(url) { HplayWebState(url) }

@Composable
fun rememberHplayWebNavigator(state: HplayWebState): HplayWebNavigator =
    remember(state) { HplayWebNavigator(state) }

private fun isExternalScheme(u: String): Boolean =
    !(u.startsWith("http://") || u.startsWith("https://"))

/*
 * 广告拦截（Android 独有能力，规则数据与桌面共用一份 hplay-adrules.txt）：
 * 桌面受 WebView2 封装限制只能做元素隐藏，Android 可以在网络层直接不给第三方广告域名发请求——
 * 第三方弹屏脚本（实测 a.magsrv.com/ad-provider.js 绑 36 个点击类监听 + window.open/location.href 跳转，
 * horribletrainer.com 为 PopAlpha popunder）一旦不加载，「点哪儿都跳广告」的文档级点击劫持就不会被绑上，
 * 点击落回站点自己的播放器。只作用于「第三方」：站点自有域名永不被硬拦。
 */

/**
 * 规则没加载上或正则坏了时必须「不拦」而不是「全拦」：`Pattern.compile("")` 匹配一切，
 * 那会把整个 WebView 变白屏。上游 `AdRules.load()` 已改成「解析发布完才置位 + 加锁」，
 * 因此这里读到空串只可能是资源真缺失，缓存这个结论即可，不必每个请求再进 monitor 重试。
 * 注意：关掉的只是「查表」这一路；`adSignature` 的路径写法特征与规则无关，无表时仍拦已知弹屏写法。
 */
private val adPattern: Pattern? by lazy {
    val rx = runCatching { com.han1me.AdRules.adsRegexOrEmpty() }.getOrDefault("").trim()
    if (rx.isEmpty()) null else runCatching { Pattern.compile(rx) }.getOrNull()
}

private fun hostOf(u: String): String = runCatching { Uri.parse(u).host.orEmpty() }.getOrDefault("").lowercase()

private fun isAdHost(u: String): Boolean {
    val p = adPattern ?: return false
    val h = hostOf(u)
    return h.isNotEmpty() && p.matcher(h).find()
}

/**
 * 同站判定：按「 registrable 域」比（取末两段）——`cdnjs.missav888.cc` 与 `www.missav888.cc` 算一站，
 * 而 `888.cc` 与 `www.missav888.cc` **不**算（避免"当前站主机的任意后缀"成为免检逃逸门）。
 * 本工作目录的站点域名全是「单标签后缀 + 主机」形态（.cc/.my/.one/.men/.tv/.icu/.art…），无需 PSL 表。
 */
private fun registrable(h: String): String {
    val parts = h.split('.')
    return if (parts.size <= 2) h else parts.takeLast(2).joinToString(".")
}

private fun sameSite(u: String, pageHost: String): Boolean {
    if (pageHost.isEmpty()) return false
    val h = hostOf(u)
    return h.isNotEmpty() && registrable(h) == registrable(pageHost)
}

/** 弹屏广告的固定写法：只在 **路径** 上比（搜索词在 query 里，不该误伤站点自己的搜索跳转）。 */
private fun adSignature(lowerUrl: String): Boolean {
    val path = lowerUrl.substringBefore('#').substringBefore('?')
    if (path.contains("erebyad") || path.contains("popunder")) return true
    if ((path.endsWith("/pop") || path.contains("/pop/")) && lowerUrl.contains("url=")) return true
    return lowerUrl.contains("rsrc=popscript")
}

/**
 * 广告跳转否决：第三方广告域名，或站点自有页面里的弹屏广告固定写法；
 * intent:// 与百分号编码的跳出先解码再判，否则广告换个壳就绕过闸门。
 */
private fun isAdHijack(u: String, pageHost: String): Boolean {
    val l = u.lowercase()
    if (adSignature(l)) return true
    if (!sameSite(u, pageHost) && isAdHost(u)) return true
    val dec = runCatching { Uri.decode(u) }.getOrDefault("").lowercase()
    if (dec.isNotEmpty() && dec != l) {
        if (adSignature(dec)) return true
        if (!sameSite(dec, pageHost) && isAdHost(dec)) return true
    }
    return false
}

/** intent:// 之类的外抛地址：解码后在**整串文本**上找广告域名，别让 `S.browser_fallback_url=` 这种壳把广告射给系统浏览器。 */
private fun isAdEscaped(u: String): Boolean {
    val p = adPattern ?: return false
    val t = runCatching { Uri.decode(u) }.getOrDefault(u)
    return p.matcher(t).find()
}

private val mediaPathRe = Regex(".+/(480|720|1080|2160)p/?$")

/** 媒体形态的 URL（只看路径，query 里的签名 token 不参与）。 */
private fun isMediaUrl(u: String): Boolean {
    val path = u.substringBefore("#").substringBefore("?").lowercase()
    if (path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".ts") || path.endsWith(".key")) return true
    if (path.contains("/jmpres/") || path.contains("/hls/")) return true
    // 第 215 轮（青青草真机排障）：KT-play 族的 get_file 派发与 remote_control.php 都是媒体链：
    // 路径以 /get_file/ 开头、或本体就是 remote_control.php。query 里的签名 token 不参与判定。
    if (path.contains("/get_file/")) return true
    if (path.endsWith("/remote_control.php")) return true
    return path.matches(mediaPathRe) || path.endsWith("/playlist") || path.endsWith("/master")
}

/**
 * 媒体链路豁免（**只在「同站发起」或「重定向带出」这条链上成立**）：
 * MissAV 的播放地址是站内 /jmpres/ → 302 到轮换的随机域名（实测同一集子两次分别落到
 * vckeepw.top 与 vcsheaye.cc），广告域名表随时可能撞上某个新轮换域，撞上的表现就是「点播放没画面」。
 *
 * 但豁免不能只看 URL 形态：`<script src="https://广告域/hls/payload.ts">` 同样长成媒体形态，
 * 而 classic script 只看响应 MIME 不看扩展名 —— 无条件按形态放行等于把上一棒刚关掉的弹屏脚本通道重开，
 * 也违反本棒自设的「广告规制只增不减」。故：**只有同站请求或被同站重定向带出的媒体请求，才把该
 * host 记入白名单**；白名单 host 之后（分片、密钥、各级 m3u8 都是直连请求、不再经重定向）继续放行，
 * 非媒体形态（脚本/iframe/图）照样过表。表内域名自己凭空冒出一个 .ts 子资源进不了白名单。
 */
private fun hostOfReferer(ref: String): String = if (ref.isBlank()) "" else hostOf(ref)

/**
 * 登记媒体 host：只认两种来源——① **本站自己发起**的媒体请求；② **来自本站 referer 的重定向**带出的媒体请求。
 * 只看 `isRedirect` 是不够的：弹屏链路本身就在做各种 302，任一广告页的一次 302 就能把它的域写进通行证。
 */
private fun noteMediaHost(state: HplayWebState, u: String, isRedirect: Boolean, referer: String) {
    // 调用方保证只对子资源调用（主文档不参与登记）；这里再守一道：媒体形态 + 已有本站主机
    if (!isMediaUrl(u) || state.mainHost.isEmpty()) return
    val h = hostOf(u)
    if (h.isEmpty()) return
    val refHost = hostOfReferer(referer)
    val fromPage = refHost.isNotEmpty() && registrable(refHost) == registrable(state.mainHost)
    if (sameSite(u, state.mainHost) || (isRedirect && fromPage)) state.mediaHosts.add(h)
}

private fun mediaExempt(state: HplayWebState, u: String): Boolean =
    isMediaUrl(u) && state.mediaHosts.contains(hostOf(u))
/** 空响应（404）：脚本不执行、广告图与 iframe 落空，且不会因 MIME 错位产生半截资源。 */
private fun adBlocked(): WebResourceResponse =
    WebResourceResponse(null, null, 404, "Not Found", null, null)

/** 主文档被 3xx 带进广告域名时给的空白页（比「停在广告页」更干净，用户按返回即回站点）。 */
private fun adBlankPage(): WebResourceResponse =
    WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(ByteArray(0)))

/**
 * 能吃返回键的全屏容器：站点页全屏时返回键先退全屏，而不是关页面。
 *
 * DOWN 与 UP **都要消费**：ComponentActivity 的返回键是在 UP 时经 OnBackPressedDispatcher 出栈的，
 * 只吃 UP 会留下同一次按键被应用层先处理一遍的路径（页面被 pop、视频中断）。
 * isFocusable 也必须显式开：FrameLayout 默认不可聚焦，而 `View.requestFocus()` 对不可聚焦的视图
 * 按契约直接返回 false —— 只设 isFocusableInTouchMode 时焦点能否落进来全凭 Chromium 传进来的那个 view。
 */
private class FsFrame(ctx: android.content.Context) : FrameLayout(ctx) {
    var onBack: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK &&
            (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP)
        ) {
            if (e.action == KeyEvent.ACTION_UP) onBack?.invoke()
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}

/** 读当前播放内容的宽高比（决定横竖屏；竖向素材强转横屏会两边留大黑边）。 */
private const val ASPECT_JS =
    "(function(){try{var e=document.fullscreenElement||document.webkitFullscreenElement;" +
    "var v=(e&&e.querySelector&&e.querySelector('video'))||document.querySelector('video');" +
    "return (v&&v.videoWidth>0&&v.videoHeight>0)?(v.videoWidth+'/'+v.videoHeight):''}catch(x){return ''}})()"

private fun dp(ctx: android.content.Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

/**
 * 退出站点页全屏：交还给 WebView、恢复系统栏与方向。
 * 返回 **false 表示旧层没能摘掉**——调用方（尤其重入接管那条路径）必须据此中止，
 * 否则会出现「旧 FsFrame 永挂在 content、旧 fsCallback 从未 onCustomViewHidden（渲染端卡在全屏态）、
 * 旧 fsBack 仍留在 Dispatcher 里」三层同时泄漏。
 */
private fun webExitFullscreen(state: HplayWebState, wv: WebView): Boolean {
    val host = state.fsHost ?: return true
    val cb = state.fsCallback
    // 摘掉视图才许清引用：removeView 一旦失败，若 fsHost 已为 null，「⤡ 收起」和返回键都会早退，
    // 黑层却仍盖在屏上，用户只剩「再触发一次全屏」这条自救路。
    val detached = runCatching { (host.parent as? ViewGroup)?.removeView(host) }.isSuccess && host.parent == null
    if (!detached) return false
    state.fsHost = null
    state.fsCallback = null
    runCatching { state.fsBack?.isEnabled = false }
    runCatching { state.fsBack?.remove() }
    state.fsBack = null
    runCatching { cb?.onCustomViewHidden() }
    runCatching { wv.visibility = View.VISIBLE }
    com.han1me.HplayEnv.applyFullscreen(false, owner = com.han1me.HplayEnv.FsOwner.SITE_WEB)
    return true
}

/**
 * 站点页真全屏的接管体：把 Chromium 交来的视图铺满 `android.R.id.content`，
 * 返回键、方向与系统栏一并接住。任何一步失败都必须 callback.onCustomViewHidden()，
 * 绝不让异常从 WebChromeClient 回调里裸抛（宿主进程直接崩）。
 */
private fun webEnterFullscreen(
    state: HplayWebState,
    wv: WebView,
    view: View,
    callback: WebChromeClient.CustomViewCallback,
) {
    val act = com.han1me.HplayEnv.activity()
    // ContentFrameLayout（FrameLayout 系，子视图互相覆盖）。不是 decorView：DecorView 是
    // LinearLayout(VERTICAL)，不带 params 的 addView 走「剩余高度」语义，而 content 子已占满 weight，
    // 有被压成 0 高的实测风险 = 全屏只剩黑屏、画面不显示。
    val root = act?.findViewById<ViewGroup>(android.R.id.content)
    if (act == null || root == null) {
        runCatching { callback.onCustomViewHidden() }
        return
    }
    val host = try {
        FsFrame(act).apply {
            setBackgroundColor(Color.BLACK)
            // Chromium 交来的 view 可能还挂在 WebView 自己的容器上：不先摘掉，addView 抛
            // "The specified child already has a parent"。
            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            val exit = TextView(act).apply {
                text = "⤡ 收起"
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                setBackgroundColor(0xE60A0A0C.toInt())
                setPadding(dp(act, 12), dp(act, 5), dp(act, 12), dp(act, 5))
            }
            exit.setOnClickListener { webExitFullscreen(state, wv) }
            addView(
                exit,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10))
                },
            )
            onBack = { webExitFullscreen(state, wv) }
        }
    } catch (t: Throwable) {
        runCatching { callback.onCustomViewHidden() }
        return
    }
    runCatching {
        root.addView(
            host,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }.onFailure {
        runCatching { callback.onCustomViewHidden() }
        return
    }
    state.fsHost = host
    state.fsCallback = callback
    runCatching { wv.visibility = View.INVISIBLE }
    runCatching { host.requestFocus() }
    // 返回键双保险：焦点没落进 FsFrame 时，BACK 会走 OnBackPressedDispatcher → 应用级 BackHandler
    // 直接把 webPage 整页 pop 掉（用户要的是「退全屏」，拿到的是「关页面、视频中断」）。
    // Dispatcher 按「后注册者优先」派发，全屏期间注册、退全屏即摘，应用级出栈栈序不受影响。
    runCatching {
        val back = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { webExitFullscreen(state, wv) }
        }
        (act as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.addCallback(back)
        state.fsBack = back
    }
    com.han1me.HplayEnv.applyFullscreen(
        true,
        landscape = true,
        owner = com.han1me.HplayEnv.FsOwner.SITE_WEB,
    )
    // 内容宽高比到手前，默认按横屏沉浸只是暂态，故轮询若干次而不是只问一遍：
    // videoWidth 为 0（元数据未到 / 根本没源的页面）时一次性回调永远不会纠正。
    pollAspect(state, wv, host, tries = 6)
}

/** 有限次轮询内容比例，据此纠正横/竖屏（竖向素材强转横屏 = 两侧大黑边）。 */
private fun pollAspect(state: HplayWebState, wv: WebView, host: FsFrame, tries: Int) {
    if (tries <= 0 || state.fsHost !== host) return
    wv.postDelayed({
        if (state.fsHost !== host) return@postDelayed
        runCatching {
            wv.evaluateJavascript(ASPECT_JS) { r ->
                val parts = r?.trim('"')?.split('/')
                val vw = parts?.getOrNull(0)?.toFloatOrNull()
                val vh = parts?.getOrNull(1)?.toFloatOrNull()
                if (vw != null && vh != null && vh > 0 && state.fsHost === host &&
                    com.han1me.HplayEnv.fullscreenOwner() == com.han1me.HplayEnv.FsOwner.SITE_WEB
                ) {
                    com.han1me.HplayEnv.applyFullscreen(
                        true, landscape = vw >= vh, owner = com.han1me.HplayEnv.FsOwner.SITE_WEB,
                    )
                } else {
                    pollAspect(state, wv, host, tries - 1)
                }
            }
        }
    }, 400)
}

/**
 * ★★★ 第 163 轮（用户要求「网页端播放也走内置节点」）：
 *
 * 原本「放行」都是直接 return null —— 即交给 WebView 自己**直连**。
 * 但 WebView 不支持 SOCKS、也不吃 JVM 代理属性，而内置节点对外是 SOCKS5，
 * 于是网页版一直是直连；这些站直连基本不通（实测 99热/青青草 直连 000），
 * 表现就是「网页播放打不开、封面一片空」。
 *
 * 这里改成：先问 [com.han1me.lab.HplayWebProxy] 能否经节点取回来，
 * 拿不到就返回 null（回落 WebView 原本行为）—— 只多一条通路，不改变原语义。
 */
private fun allowByNode(request: WebResourceRequest): WebResourceResponse? =
    runCatching { com.han1me.lab.HplayWebProxy.intercept(request) }.getOrNull()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HplayWebView(
    state: HplayWebState,
    navigator: HplayWebNavigator,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                val wv = this
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = userAgentString.replace("; wv", "")
                    textZoom = 100
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val u = request.url.toString()
                        // 主文档被广告带走 → 拒绝加载，留在原页面（本站未开多窗口支持，脚本的
                        // window.open / target=_blank 与改 location 一样都落在这里，一道闸门全覆盖）。
                        // 真要跳出本站请用顶栏 ↗ 交给系统浏览器。
                        if (request.isForMainFrame) {
                            if (isAdHijack(u, state.mainHost)) return true
                            // 只认真实站点的 http(s) 主机：intent:// 之类会解出 "scan" 这种垃圾主机，
                            // 认领了会让后续 sameSite 全部失效（本站自有资源被当第三方拦掉）
                            if (!isExternalScheme(u)) state.claimMainHost(hostOf(u))
                        }
                        if (isExternalScheme(u)) {
                            // 非 http(s)（intent:// 等）默认交给系统；但外壳里藏着广告域名的照样拒掉
                            if (isAdEscaped(u)) return true
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, request.url)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            return true
                        }
                        return false
                    }

                    /**
                     * 请求层拦截（Android 独有；只拦「第三方命中广告域名表」的请求）：
                     * 弹屏脚本不加载，就没人能给文档绑点击劫持——这是「点播放键被弹走」的根治层。
                     * 主文档只在 3xx 被带进广告域名时给空白页（shouldOverrideUrlLoading 看不到重定向）；
                     * 用户自己点开/站点首屏的域名一律放行，避免目录里某站域名恰好在广告表里时被误杀。
                     */
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val u = request.url.toString()
                        val ref = request.requestHeaders.entries
                            .firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value.orEmpty()
                        // 只有**子资源**才有资格登记白名单：主文档 302 若能登记，
                        // 「广告域把落地页写成 /jmpres/x/master.m3u8」就自己给自己发通行证，BLANK 分支又被绕开。
                        if (!request.isForMainFrame) noteMediaHost(state, u, request.isRedirect, ref)
                        if (sameSite(u, state.mainHost) || !isAdHost(u)) return allowByNode(request)
                        if (request.isForMainFrame) {
                            // 主文档进广告域名：已被本站授权过的媒体链照常放行，其余 3xx 给空白页。
                            // （把这条判定整个提到豁免之前并不能关掉「广告域把落地页写成 /hls/…」——
                            //   非重定向的主文档本来就走 `else null`；它只会误杀真实媒体主文档，三审指出。）
                            if (mediaExempt(state, u)) return allowByNode(request)
                            return if (request.isRedirect) adBlankPage() else allowByNode(request)
                        }
                        if (mediaExempt(state, u)) return allowByNode(request)
                        return adBlocked()
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        if (!url.isNullOrBlank() && !isAdHijack(url, state.mainHost) && !isExternalScheme(url)) state.claimMainHost(hostOf(url))
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        state.lastLoadedUrl = url ?: view.url
                        state.pageTitle = view.title?.takeIf { it.isNotBlank() }
                        state.canGoBack = view.canGoBack()
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        state.lastLoadedUrl = url ?: view.url
                        state.canGoBack = view.canGoBack()
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: android.webkit.RenderProcessGoneDetail,
                    ): Boolean {
                        // 渲染进程崩溃时不连带宿主进程；销毁该 WebView，页面由用户返回重建
                        webExitFullscreen(state, view)
                        if (state.webView === view) state.webView = null
                        runCatching { view.destroy() }
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView, title: String?) {
                        state.pageTitle = title?.takeIf { it.isNotBlank() }
                    }

                    /**
                     * 站点页真全屏（复刻官方站的满窗形式）：Chromium 把要全屏的视图交给宿主，
                     * 宿主不实现本回调时**站点的全屏键点了没有任何反应**（安卓端实测）。
                     * 这里把它铺满 decorView、隐藏网页与系统栏、右上角给一枚「⤡ 收起」，返回键也先收全屏。
                     */
                    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                        // 重入：站点快速连点全屏键、或上一次 onHideCustomView 迟到时，
                        // 一律「拒绝并回 onCustomViewHidden」会把第二次请求整个吞掉——那正是本棒要修的
                        // 「全屏键点了没反应」。先按正常路径收干净旧层再接管新的。
                        if (state.fsHost != null && !webExitFullscreen(state, wv)) {
                            // 旧层摘不掉就不要叠第二层：把新请求按协议退回，界面维持现状（仍有「⤡ 收起」可自救）
                            runCatching { callback.onCustomViewHidden() }
                            return
                        }
                        webEnterFullscreen(state, wv, view, callback)
                    }

                    override fun onHideCustomView() {
                        webExitFullscreen(state, wv)
                    }
                }
                setDownloadListener(DownloadListener { url, ua, contentDisposition, mimetype, _ ->
                    runCatching {
                        val req = DownloadManager.Request(Uri.parse(url))
                            .setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                            )
                            .setTitle("Hplay 下载")
                        if (ua != null) req.addRequestHeader("User-Agent", ua)
                        val cookies = CookieManager.getInstance().getCookie(url)
                        if (cookies != null) req.addRequestHeader("Cookie", cookies)
                        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        dm.enqueue(
                            req.setDestinationInExternalPublicDir(
                                Environment.DIRECTORY_DOWNLOADS, "Hplay",
                            ),
                        )
                    }
                })
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(wv, true)
                }
                state.webView = this
                loadUrl(state.initialUrl)
            }
        },
        onRelease = { wv ->
            webExitFullscreen(state, wv)
            CookieManager.getInstance().flush()
            runCatching {
                wv.stopLoading()
                wv.destroy()
            }
            if (state.webView === wv) state.webView = null
        },
    )
}
