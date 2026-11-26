package com.han1me

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference

/**
 * Android 宿主环境桥：上游共享代码（HanimeClient / X99 / AdRules）经由此对象取得
 * 数据目录 / 资产文件 / 系统能力，替代桌面版的 %LOCALAPPDATA% 与 java.awt。
 * 由 MainActivity.onCreate 调 init() 完成注入。
 */
object HplayEnv {
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    private val main = Handler(Looper.getMainLooper())

    /**
     * **长按卡片 → 用内置网页打开**（第 128 轮，用户要求）。
     *
     * 用户原话：「长按视频封面可以直接跳转内置网页打开，也就是介绍页的通过网页打开，
     * 作为快捷手势和介绍页加载不出来的兜底」。
     *
     * 为什么放这里而不是给三个列表 composable 各加一个参数：
     * 卡片（[com.han1me.SiteCard]）出现在首页 / 列表页 / 介绍页相关影片三处，
     * 各加一个回调参数要连带改 3 个函数签名和它们的全部调用点；
     * 而「打开内置网页」本来就是**一个全局 UI 能力**（HanimeApp 里那个 webPage 状态），
     * 由 HanimeApp 注册一次即可。未注册时返回 null，长按静默无动作（不崩）。
     */
    @Volatile
    var openWeb: ((String, String) -> Unit)? = null


    /** 取 application context（不抛异常）。日志导出等后台工具用，第 139 轮加。 */
    fun applicationOrNull(): Context? = appContext

    fun init(ctx: Context, activity: Activity) {
        appContext = ctx.applicationContext
        activityRef = WeakReference(activity)
        // 新 Activity 起来时不能带着上一世的沉浸态：`isFullscreen` 是本对象的进程级状态，
        // 残留 true 会让播放器浮层的 BackHandler 每次都把返回键吃掉（表现为返回键失灵），
        // 而此时已经没有任何持有者会来解它。
        resetFullscreen()
        registerSlots(activity)
        bindUpdateCredential()
    }

    /**
     * 把「检查更新」用的 139 凭据注入 [com.han1me.update.Yun139]。
     *
     * 优先级：**用户在设置里改过的值** > **构建期注入的** [com.han1me.update.Yun139Cred.AUTH]。
     *  - 构建期那份来自 `tools/139_cred.txt`（**不入 git**，见 build.py 的 gen_yun139_credential）；
     *  - 设置里那份让用户能在不重新打包的情况下换凭据（键 `yun139.authorization`）。
     * 两处都为空时 [com.han1me.update.Yun139.fetchManifest] 会明确报「139 凭据不可用」，
     * 界面**如实显示并回落到自建网盘通道** —— 不假装查过。
     */
    private fun bindUpdateCredential() {
        runCatching {
            val fromSetting = com.han1me.X99.setting(KEY_YUN139_AUTH, "")
            com.han1me.update.Yun139.reset()          // 换凭据必须清路由缓存，否则会拿旧 host 打
            // ★★ 第 114 轮：**多账号**。
            //
            // 旧版只注入**一个**凭据（Yun139Cred.AUTH）。它一旦过期（实测 route 接口回 05050006），
            // 139 直连就永远失败 —— 用户每次检查更新都会看到「通道：自建网盘兜底」，
            // 哪怕 139 本身好好的、只是 token 换过了。
            // 现在注入**全部账号**（主账号 + 备用），Yun139 会逐个试到拿到清单为止。
            // 用户在设置里手填的那份仍然**优先**（只用它，覆盖构建期注入的整份列表）。
            com.han1me.update.Yun139.authorizations =
                if (fromSetting.isNotBlank()) listOf(fromSetting)
                else com.han1me.update.Yun139Cred.AUTHS
            com.han1me.update.Yun139.authorization = com.han1me.update.Yun139.authorizations.firstOrNull().orEmpty()
        }
    }

    /** 设置键：用户在面板里改的 139 凭据（覆盖构建期注入值）。 */
    const val KEY_YUN139_AUTH = "yun139.authorization"

    // ---------- 用户页本机素材槽（第 43 轮：填上游 HplaySlots 的两个空槽） ----------

    /** 挑图落盘用的固定文件名（覆盖写；设置里存的是绝对路径，所以改名不必动设置）。 */
    private const val COVER_FILE = "user_cover.jpg"
    private const val AVATAR_FILE = "user_avatar.jpg"

    private var imagePicker: ActivityResultLauncher<String>? = null
    private var pendingPick: ((String?) -> Unit)? = null
    private var pendingKey = "user.cover.file"

    /**
     * 注册素材槽。`init` 是 MainActivity.onCreate 调的，正好是 ActivityResultRegistry
     * 允许登记的时机（保存状态之前）；换新 Activity 时先把上一个 launcher 注销，不攒泄漏。
     *
     * 上游那两个槽默认什么都不做，`pickSupported` 也只有这里会置 true ——
     * 所以桌面不会出现「点了没反应」的按钮，而这边一旦出现就一定是真能弹相册的。
     */
    private fun registerSlots(activity: Activity) {
        val a = activity as? ComponentActivity ?: return
        runCatching { imagePicker?.unregister() }
        imagePicker = a.activityResultRegistry.register(
            "hplay.pick.image", a, ActivityResultContracts.GetContent(),
        ) { uri ->
            val cb = pendingPick
            pendingPick = null
            cb?.invoke(savePicked(uri, if (pendingKey == "user.avatar.file") AVATAR_FILE else COVER_FILE))
        }
        HplaySlots.pickSupported = true
        // 「信息」页那句版本不许再抄一份常量：包里的版本是构建时从 version.properties 注入 aapt2 的，
        // 这里直接问 PackageManager。第 43 轮的 v1.0.19 就是界面写成了 Tokens 里的旧常量才对的不上。
        HplaySlots.versionLabel = { packagedVersionName() }
        HplaySlots.pickImage = { key, done ->
            val l = imagePicker
            if (l == null) done(null)
            else {
                pendingKey = key
                pendingPick = done
                runCatching { l.launch("image/*") }.onFailure { pendingPick = null; done(null) }
            }
        }
        HplaySlots.decodeImage = { path -> decodeLocal(path) }
    }

    /**
     * 把相册选中的流**拷进应用私有目录**并回绝对路径。
     *
     * 拷而不是记 content:// URI：content 授权是临时的，进程一死 URI 就作废，
     * 那样「换好的封面」下次启动就没了。取不到流就返回 null —— 不能先把目标文件截断再发现没流。
     */
    private fun savePicked(uri: Uri?, name: String): String? = runCatching {
        if (uri == null) return@runCatching null
        val src = requireContext().contentResolver.openInputStream(uri) ?: return@runCatching null
        val to = File(dataDir(), name)
        src.use { ins -> to.outputStream().use { ins.copyTo(it) } }
        // ★★ LABKEEP（2026-10-31 · 用户报「新版本装完封面头像被清」）：
        //
        // 用户素材原本只存在 filesDir/AvPlay 里（应用私有）。**覆盖安装正常不会丢**，
        // 但只要出现「签名不一致→系统要求卸载重装」「清应用数据」这类操作，就全没了。
        // 这里额外在**外部私有目录**留一份副本（卸载才会清，普通重装不受影响），
        // 启动时若发现私有目录里的文件没了，就自动从外部副本恢复。
        runCatching {
            val ext = requireContext().getExternalFilesDir("slots") ?: return@runCatching
            ext.mkdirs()
            to.copyTo(File(ext, name), overwrite = true)
        }
        to.absolutePath
    }.getOrNull()

    /**
     * **启动时恢复用户素材**（LABKEEP）。
     *
     * 若私有目录里的 user_cover.jpg / user_avatar.jpg 不见了，但外部目录有副本，
     * 就复制回来并重写设置里的路径 —— 用户「换个版本封面就没了」的问题从根上兜住。
     */
    fun restoreSlots() = runCatching {
        val ext = requireContext().getExternalFilesDir("slots") ?: return@runCatching
        if (!ext.isDirectory) return@runCatching
        for (name in arrayOf(COVER_FILE, AVATAR_FILE)) {
            val backups = File(ext, name)
            if (!backups.isFile) continue
            val dst = File(dataDir(), name)
            val key = if (name == AVATAR_FILE) "user.avatar.file" else "user.cover.file"
            val cur = com.han1me.X99.setting(key, "")
            // 目标文件缺失，或设置里的路径已失效 → 从备份恢复
            if (!dst.isFile || cur.isBlank() || !File(cur).isFile) {
                backups.copyTo(dst, overwrite = true)
                com.han1me.X99.putSetting(key, dst.absolutePath)
            }
        }
    }

    /** 手机相册动辄 4000×3000，直接解会一口气吃掉 48 MB；先量尺寸，按 2 的幂降采样到 1400 px 以内。 */
    private fun decodeLocal(path: String): ImageBitmap? = runCatching {
        val f = File(path)
        if (!f.isFile) return@runCatching null
        val bytes = f.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1400) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.asImageBitmap()
    }.getOrNull()

    /**
     * 包里真正生效的 versionName（构建时由 aapt2 从 `version.properties` 注入）。
     * 「信息」页那句版本不许再抄一份常量：第 43 轮的 v1.0.19 界面写成 Tokens 里的旧常量就是教训。
     */
    private fun packagedVersionName(): String = runCatching {
        val c = requireContext()
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 无条件复位（不看持有者）。只在 Activity 创建这一条安全路径上调用。 */
    private fun resetFullscreen() {
        fsOwner = null
        isFullscreen = false
        main.post {
            runCatching {
                val a = activityRef?.get() ?: return@post
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                a.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
    }

    fun bindActivity(activity: Activity?) {
        activityRef = if (activity == null) null else WeakReference(activity)
    }

    /** 当前 Activity（站点页真全屏要把视图铺到 decorView 上，绕开 Compose 树与返回键栈）。 */
    fun activity(): Activity? = activityRef?.get()

    fun requireContext(): Context =
        appContext ?: throw IllegalStateException("HplayEnv 未初始化")

    /** 设置 / cookies / 目录缓存所在（桌面版为 %LOCALAPPDATA%\AvPlay）。 */
    fun dataDir(): File = File(requireContext().filesDir, "AvPlay").apply { mkdirs() }

    fun cacheDir(): File = requireContext().cacheDir

    fun asset(name: String): InputStream? =
        runCatching { requireContext().assets.open(name) }.getOrNull()

    /** 复制到剪贴板（桌面版 java.awt.Toolkit.systemClipboard）。 */
    fun copyText(text: String): Boolean = runCatching {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hplay", text))
        true
    }.getOrDefault(false)

    /** 系统浏览器打开（桌面版 java.awt.Desktop.browse）。 */
    fun openExternal(url: String): Boolean = runCatching {
        requireContext().startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    /**
     * 把**本机文件**交给系统播放器（第 46 轮「外部播放器」）。
     *
     * 必须走 content://：Android 7.0 起把 `file://` 跨进程交出去会抛
     * `FileUriExposedException`（StrictMode 的 FileUriExposure 检查），
     * 所以这条路在安卓上**只能**用 FileProvider。
     * 只对我们发出的这一个 URI 授予临时读权限（FLAG_GRANT_READ_URI_PERMISSION），
     * 不是把目录暴露出去。
     *
     * @return true = 已成功调起；false = 文件不存在，或系统里没有能处理该 MIME 的应用。
     */
    /**
     * **把任意文件交给系统分享面板**（第 139 轮：运行日志导出成 txt）。
     *
     * 与 [playExternalFile] 同一套 FileProvider 路线，只是 MIME 换成 text/plain、
     * 动作换成 ACTION_SEND（分享面板：微信/QQ/邮件/存到网盘都能选）。
     * 调用方负责先把内容写进这个文件。
     */
    fun shareFile(path: String, mime: String = "text/plain"): Boolean = runCatching {
        val ctx = requireContext()
        val f = File(path)
        if (!f.exists() || f.length() == 0L) return false
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", f,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享日志文件").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
        true
    }.getOrDefault(false)

    fun playExternalFile(path: String): Boolean = runCatching {
        val ctx = requireContext()
        val f = File(path)
        if (!f.exists()) return false
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", f,
        )
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 第 71 轮（用户要求「点击可跳转链接外部播放器，扫描本机可用的打开方式」）：
        // 原来直接 startActivity —— 系统里若有多个播放器，行为由默认关联决定，用户无从挑选；
        // 若一个都没有则抛 ActivityNotFoundException（runCatching 吞掉后只是静默失败）。
        // 现在：**用系统选择器列出本机所有能打开它的应用**，用户自己挑。
        val chooser = Intent.createChooser(view, "选择播放方式").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 先把临时读权限授予 chooser 里最终被选中的应用（否则对方拿到 URI 也读不到）
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<android.content.Intent>())
        try {
            ctx.startActivity(chooser)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            // 没有任何应用能打开：如实告诉用户，而不是「点了没反应」
            toast("本机没有可用的播放器；请先安装一个（如 VLC / MX Player）")
            false
        }
    }.getOrDefault(false)

    /** 本机有哪些应用能打开这个文件（供界面如实列出「扫描到的打开方式」）。 */
    fun externalPlayersFor(path: String): List<String> = runCatching {
        val ctx = requireContext()
        val f = File(path)
        if (!f.exists()) return emptyList()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", f,
        )
        val probe = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "video/*") }
        ctx.packageManager.queryIntentActivities(probe, 0)
            .map { it.loadLabel(ctx.packageManager).toString() }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    /** 用**指定的**应用打开本机文件（外部播放器菜单里选定某个之后调用）。 */
    fun playExternalFileWith(path: String, packageName: String): Boolean = runCatching {
        val ctx = requireContext()
        val f = File(path)
        if (!f.exists()) return false
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", f,
        )
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        true
    }.getOrDefault(false)

    /** 轻量提示（第 46 轮：外部播放器调不起来时必须说话，不能点了没反应）。 */
    fun toast(msg: String) {
        main.post {
            runCatching {
                android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 把**在线直链**交给系统播放器（桌面版写 .strm 后 Desktop.open）。
     *
     * 第 72 轮修正（用户报「外部播放没有弹出系统级分享或者播放选项，系统选择器没有调出」）：
     * 上一版只给**本机文件**那条路加了选择器，**在线直链这条路漏了** ——
     * 它仍然直接 startActivity，于是当系统里有多个播放器时由默认关联决定、用户无从挑选，
     * 看起来就是「没弹选择器」。
     * 现在同样走 **createChooser**（并 try/catch 住 ActivityNotFoundException，失败会说话）。
     *
     * MIME 统一用 video 类型（只列能放视频的应用）；站点给的 m3u8 也按这个处理。
     */
    fun playExternal(url: String): Boolean = runCatching {
        val ctx = requireContext()
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(view, "选择播放方式").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(chooser)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            toast("本机没有能打开这个链接的应用；请先安装一个播放器（如 VLC / MX Player）")
            false
        }
    }.getOrDefault(false)

    /** 本机有哪些应用能打开这个**在线直链**（供界面列出「扫描到的打开方式」）。 */
    fun externalPlayersForUrl(url: String): List<String> = runCatching {
        val ctx = requireContext()
        val probe = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(url), "video/*") }
        ctx.packageManager.queryIntentActivities(probe, 0)
            .map { it.loadLabel(ctx.packageManager).toString() }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    // ---------- 全屏（视频横屏沉浸，对应桌面 HplayWindow.toggle 的移动端语义） ----------

    /**
     * 全屏持有者：`PLAYER` ＝ App 自己的内嵌播放器浮层（官方源与内置 xchina 都算），
     * `SITE_WEB` ＝ WebView 里站点页自己请求的 HTML5 全屏。两者共用同一套方向锁与沉浸态。
     */
    enum class FsOwner { PLAYER, SITE_WEB }

    @Volatile
    private var fsOwner: FsOwner? = null

    /** 当前由谁持有全屏（方向轮询等异步回调据此判断自己是否还有权改方向）。 */
    fun fullscreenOwner(): FsOwner? = fsOwner

    // Compose Snapshot 状态：全屏切换直接驱动重组（BackHandler enabled 等据实求值）
    var isFullscreen: Boolean by mutableStateOf(false)
        private set

    fun toggleFullscreen(owner: FsOwner = FsOwner.PLAYER) =
        applyFullscreen(!(isFullscreen && fsOwner == owner), owner = owner)

    /**
     * landscape=false 用于竖向素材（强转横屏会两边留大黑边）。
     *
     * **非持有者的退出请求一律忽略**：两处全屏共用方向锁与系统栏，站点页退出时若不辨持有者，
     * 会把官方源满窗浮层的锁向与沉浸态一起解掉（浮层还挂着，画面却回竖、系统栏弹回）。
     */
    fun applyFullscreen(
        on: Boolean,
        landscape: Boolean = true,
        owner: FsOwner = FsOwner.PLAYER,
    ) {
        if (!on && fsOwner != null && fsOwner != owner) return
        // 进入仲裁：两处都想进沉浸时后到者接管（把 owner 换过来）。
        // 不换的话旧持有者已不在位却仍被自己的退出请求忽略，新持有者又解不掉 → 永久卡在横屏沉浸。
        if (on) fsOwner = owner
        fsOwner = if (on) owner else null
        isFullscreen = on
        main.post {
            runCatching {
                val a = activityRef?.get() ?: return@post
                val w = a.window
                if (on) {
                    a.requestedOrientation =
                        if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    w.decorView.systemUiVisibility =
                        (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                } else {
                    a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                }
            }
        }
    }
}