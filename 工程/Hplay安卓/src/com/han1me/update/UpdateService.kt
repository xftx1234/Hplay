package com.han1me.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.han1me.lab.LabLog
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * **更新包的后台下载服务**（第 331 轮）。
 *
 * 用户原话：「设置应用内下载更新弹窗，点击下载后后台通知栏下载并且显示进度」。
 * 两个参考项目怎么做的：
 *  · **Han1mePlus**（\`update_installer.dart\`）：用 dio 的 \`onReceiveProgress\` 报进度，
 *    文件落在 \`getExternalStorageDirectory()/updates/\`，下完 \`OpenFilex.open\` 拉安装器；
 *    并且**带 4 个 gh-proxy 镜像**做重试（GitHub 下载在国内经常断）。
 *  · **Han1meViewer**（\`HUpdater.injectUpdate\`）：边下边写 \`outputStream()\`，
 *    \`copyTo(out, contentLength, progress)\` 报进度，CI 的 zip 还按 0.56 压缩率估进度。
 *
 * 本项目取两边的共同点 + 一个不同的决定：
 *
 * ## 为什么是前台服务而不是在界面里下
 *
 * 更新包 20+ MB，国内直连 GitHub 常常要几十秒到几分钟。用户点完「下载」几乎一定会
 * 切出去看别的（谁盯着进度条看）。界面里的协程会随 Activity 销毁而取消 ——
 * 那就变成「切出去回来发现没下完」。前台服务 + PARTIAL_WAKE_LOCK 是 Android
 * 对「后台要下完一个大文件」的正规做法，和本项目已有的视频下载（DownloadService）同款。
 *
 * ## 为什么自己写断点续传，而不是像两个参考项目那样不支持
 *
 * GitHub 的 asset 直链支持 \`Range\`。Plus 那边失败就整包重下（靠镜像换源），
 * Viewer 那边没有续传。这里加 \`Range\` 续传：断网/切网后重试只补缺的那段。
 * 一个 24 MB 的包在第 20 MB 断了重下，用户会明显感觉到「怎么又从 0 开始」。
 *
 * ## 镜像策略（照 Plus）
 *
 * Plus 的 \`_updateMirrors\` 是 4 个 gh-proxy 前缀。这里同样保留：直连失败就换镜像，
 * 但**镜像只用于下载**，不用来判断版本（版本信息只从 api.github.com / atom 拿，
 * 免得镜像返回过期缓存把用户骗去下旧版）。
 */
class UpdateService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** CPU 锁：息屏时也要把包下完（与 DownloadService 同一个道理）。 */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelDownload()
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                val size = intent.getLongExtra(EXTRA_SIZE, 0L)
                val md5 = intent.getStringExtra(EXTRA_MD5).orEmpty()
                if (url.isBlank()) {
                    LabLog.i("upd", "下载请求没有 URL，忽略")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIF_ID, buildNotification("准备下载", 0, 0, true))
                startDownload(url, name, size, md5)
                return START_NOT_STICKY
            }
        }
        // 没有 action：可能是系统重启服务；直接收工，不留常驻通知。
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 下载

    private fun startDownload(url: String, fileName: String, expectSize: Long, expectMd5: String) {
        if (State.running) {
            LabLog.i("upd", "已有下载在跑，忽略重复请求")
            return
        }
        State.reset(fileName.ifBlank { "update.apk" }, expectSize)
        job = scope.launch {
            try {
                downloadWithRetry(url, expectSize, expectMd5)
            } catch (c: kotlinx.coroutines.CancellationException) {
                State.finish(false, "已取消")
                throw c
            } catch (t: Throwable) {
                LabLog.i("upd", "下载失败：" + (t.message ?: t.toString()))
                State.finish(false, t.message ?: "下载失败")
            } finally {
                runCatching { stopForegroundCompat() }
                runCatching { stopSelf() }
            }
        }
    }

    /**
     * 依次尝试：直连 → 4 个镜像。每一轮都**续传**（`Range`），失败不清零。
     *
     * 为什么按这个顺序：直连成功时最快（GitHub 的 CDN 对国内并不总是慢）；
     * 直连被墙/超时才走镜像 —— 镜像本身也要绕一圈，不该是首选。
     */
    private suspend fun downloadWithRetry(url: String, expectSize: Long, expectMd5: String) {
        val sources = ArrayList<String>()
        sources.add(url)
        // Release asset 的 github.com 直链可以加 gh-proxy 前缀；api 的 asset URL 不行。
        if (url.startsWith("https://github.com/")) {
            for (m in MIRRORS) sources.add(m + url)
        } else if (url.startsWith("https://objects.githubusercontent.com/") ||
            url.contains(".githubusercontent.com/")) {
            // 对象存储直链已经是 302 后的地址，镜像拼不上；只能直连重试
        }
        val target = File(updateDir(this), State.fileName)
        target.parentFile?.mkdirs()

        var last: Throwable? = null
        for ((i, src) in sources.withIndex()) {
            if (State.cancelled) return
            try {
                LabLog.i("upd", "第 " + (i + 1) + "/" + sources.size + " 个源：" + src.take(120))
                fetchOnce(src, target, expectSize)
                // 体积与校验
                if (expectSize > 0 && target.length() != expectSize) {
                    throw IllegalStateException("体积不符：期望 " + expectSize + "，实际 " + target.length())
                }
                if (expectMd5.isNotBlank()) {
                    val got = md5Of(target)
                    if (!got.equals(expectMd5, true)) {
                        target.delete()
                        throw IllegalStateException("校验失败（md5 不符）")
                    }
                }
                LabLog.i("upd", "下载完成：" + target.length() + " B -> " + target.absolutePath)
                State.finish(true, "")
                notifyDone(target)
                ApkInstaller.install(this, target)
                return
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                last = t
                LabLog.i("upd", "该源失败：" + (t.message ?: t.toString()))
                // 体积不符 / md5 不符说明这份内容是坏的，**不能续传**，删掉重来
                if (t is IllegalStateException && (t.message?.contains("体积不符") == true ||
                        t.message?.contains("校验失败") == true)) {
                    target.delete()
                }
            }
        }
        throw last ?: IllegalStateException("所有下载源都失败了")
    }

    /**
     * 单次 GET（带 Range 续传）。
     *
     * 进度节流：每 200ms 更新一次通知。不分片 —— 单个 APK 用多连接没有收益
     * （GitHub 对单 IP 有带宽上限，多连接只会互相抢），这与视频下载那条
     * 「16 分片」的策略不同，原因是文件类型不一样。
     */
    private fun fetchOnce(src: String, target: File, expectSize: Long) {
        var have = if (target.exists()) target.length() else 0L
        if (expectSize > 0 && have > expectSize) { target.delete(); have = 0L }
        val reqB = Request.Builder().url(src).header("User-Agent", "Hplay-Android-Updater")
        if (have > 0) reqB.header("Range", "bytes=" + have + "-")
        val resp = http.newCall(reqB.get().build()).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP " + r.code)
            val body = r.body ?: throw IllegalStateException("响应没有 body")
            val total = when {
                r.code == 206 -> {
                    // Content-Range: bytes 100-999/1000
                    val cr = r.header("Content-Range").orEmpty()
                    cr.substringAfterLast("/").trim().toLongOrNull() ?: expectSize
                }
                else -> body.contentLength().takeIf { it > 0 } ?: expectSize
            }
            // 服务端不认 Range（回 200 全量）→ 从头写
            val append = r.code == 206
            if (!append) have = 0L
            State.total = total
            State.done = have
            RandomAccessFile(target, "rw").use { raf ->
                if (!append) raf.setLength(0)
                raf.seek(have)
                val buf = ByteArray(64 * 1024)
                var lastTick = 0L
                var lastBytes = have
                while (true) {
                    if (State.cancelled) return
                    val n = body.byteStream().read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                    have += n
                    State.done = have
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 250L) {
                        val dt = (now - lastTick) / 1000.0
                        if (dt > 0) State.speed = (have - lastBytes) / dt
                        lastTick = now
                        lastBytes = have
                        tick()
                    }
                }
            }
        }
    }

    private fun tick() {
        runCatching {
            val pct = if (State.total > 0) (State.done * 100 / State.total).toInt() else 0
            notify(buildNotification(State.fileName, State.done, State.total, State.total <= 0))
            State.pct = pct
        }
    }

    private fun cancelDownload() {
        State.cancelled = true
        job?.cancel()
        runCatching { stopForegroundCompat() }
        runCatching { stopSelf() }
    }

    // ------------------------------------------------------------------ 通知

    private fun buildNotification(title: String, done: Long, total: Long, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, com.han1me.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 11,
            Intent(this, UpdateService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val body = buildString {
            append(if (total > 0) human(done) + " / " + human(total) else human(done))
            if (State.speed > 0) append(" · " + "%.2f MB/s".format(State.speed / 1048576.0))
            if (total > 0 && State.speed > 0) {
                val left = ((total - done) / State.speed).toLong()
                if (left > 0) append(" · 剩 " + left + "s")
            }
        }
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (indeterminate) "正在下载新版本" else "下载新版本 " + pct + "%")
            .setContentText(body)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "取消", cancel).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (indeterminate || total <= 0) b.setProgress(0, 0, true)
        else b.setProgress(100, pct, false)
        return b.build()
    }

    private fun notifyDone(f: File) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val install = PendingIntent.getActivity(
            this, 12,
            Intent(this, com.han1me.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText(f.name + " · " + human(f.length()) + "，正在打开安装器")
            .setContentIntent(install)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(DONE_NOTIF_ID, n) }
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW).apply {
                description = "下载 Hplay 新版本时的进度（静默，不打扰）"
                setShowBadge(false)
            },
        )
    }

    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock != null) return@runCatching
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "hplay:update")
                .apply { setReferenceCounted(false); acquire(30 * 60 * 1000L) }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            val w = wakeLock
            wakeLock = null
            if (w != null && w.isHeld) w.release()
        }
    }

    // ------------------------------------------------------------------ 状态

    /**
     * 进程级下载状态（界面直接读它，不需要绑定服务）。
     *
     * 用普通 \`@Volatile\` 而不是 \`mutableStateOf\` 的原因：
     * 这里是后台线程写、Compose 侧轮询（更新面板本来就每 300ms 刷一次通知文案），
     * 混用 Compose 快照状态会在非主线程写时抛 \`Snapshot apply\` 相关异常。
     */
    object State {
        @Volatile var running = false
        @Volatile var done = 0L
        @Volatile var total = 0L
        @Volatile var speed = 0.0
        @Volatile var pct = 0
        @Volatile var fileName = ""
        @Volatile var cancelled = false
        @Volatile var ok = false
        @Volatile var error = ""
        /** 完成时置位：这一次是「下完了」而不是「取消了」。 */
        @Volatile var finishedAt = 0L

        fun reset(name: String, size: Long) {
            running = true; cancelled = false; ok = false; error = ""
            done = 0L; total = size; speed = 0.0; pct = 0; fileName = name; finishedAt = 0L
        }

        fun finish(success: Boolean, why: String) {
            running = false; ok = success; error = why; finishedAt = System.currentTimeMillis()
            if (!success) speed = 0.0
        }
    }

    companion object {
        private const val CHANNEL_ID = "hplay.update"
        private const val NOTIF_ID = 0x48505544      // "HPUD"
        private const val DONE_NOTIF_ID = 0x48505545

        const val ACTION_DOWNLOAD = "com.han1me.update.DOWNLOAD"
        const val ACTION_CANCEL = "com.han1me.update.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_SIZE = "size"
        const val EXTRA_MD5 = "md5"

        /**
         * gh-proxy 镜像（照 Han1mePlus 的 \`_updateMirrors\`）。
         *
         * 只用于**下载**，不用于版本判断 —— 镜像有缓存，可能返回旧文件。
         */
        private val MIRRORS = listOf(
            "https://gh-proxy.org/",
            "https://v4.gh-proxy.org/",
            "https://v6.gh-proxy.org/",
            "https://cdn.gh-proxy.org/",
        )

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }

        /** 更新包落地目录：应用外部私有目录，**卸载才清**，普通覆盖安装不丢。 */
        fun updateDir(ctx: Context): File {
            val base = ctx.getExternalFilesDir("updates") ?: File(ctx.filesDir, "updates")
            base.mkdirs()
            return base
        }

        /** 已下好但还没装的包（界面用来显示「上次已下载」）。 */
        fun pendingApk(ctx: Context): File? {
            val f = File(updateDir(ctx), "Hplay-update.apk")
            return if (f.isFile && f.length() > 0) f else null
        }

        fun deletePending(ctx: Context) {
            runCatching { File(updateDir(ctx), "Hplay-update.apk").delete() }
        }

        /** 开下（幂等：已经在跑就不再起一个）。 */
        fun start(ctx: Context, url: String, name: String, size: Long, md5: String = "") {
            if (State.running) return
            val i = Intent(ctx, UpdateService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_URL, url)
                // 固定文件名：GitHub 的 asset 名带版本号，但我们只保留「最新一版」，
                // 免得旧包越攒越多（用户不会想清理这些）。
                putExtra(EXTRA_NAME, "Hplay-update.apk")
                putExtra(EXTRA_SIZE, size)
                putExtra(EXTRA_MD5, md5)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }

        fun cancel(ctx: Context) {
            State.cancelled = true
            runCatching { ctx.startService(Intent(ctx, UpdateService::class.java).setAction(ACTION_CANCEL)) }
        }

        fun human(n: Long): String = when {
            n >= 1073741824L -> "%.2f GB".format(n / 1073741824.0)
            n >= 1048576L -> "%.1f MB".format(n / 1048576.0)
            n >= 1024L -> "%.0f KB".format(n / 1024.0)
            else -> n.toString() + " B"
        }

        fun md5Of(f: File): String {
            val md = java.security.MessageDigest.getInstance("MD5")
            f.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
