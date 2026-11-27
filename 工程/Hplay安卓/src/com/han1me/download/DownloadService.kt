package com.han1me.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 下载前台服务（第 48 轮修 bug2：「退出应用后要后台继续下载，而不是中断」）。
 *
 * ## 为什么必须用前台服务
 * 上一版把下载放在一个**普通守护线程**里（@code Thread(isDaemon = true)）。
 * 那种线程只在**进程活着**时有效；而 Android 在应用退到后台之后会很快回收进程
 * （targetSdk 34 下尤其严格），于是用户一退出就断。这不是"没写重试"，
 * 是**进程都不在了**。要跨"应用退到后台"继续跑，唯一的正规做法是把工作
 * 挂到**前台服务**上，并常驻一条通知（用户随时能看到进度、也能主动停）。
 *
 * ## 设计
 *  · 本服务**不自己实现下载**：真正的队列与状态在 [HplayDownloads] 里（进程级 object）。
 *    服务只负责"让进程活着" + 显示通知 + 提供取消入口。
 *  · 队列一空就 @code stopSelf()，不留常驻通知。
 *  · 通知用低优先级渠道（IMPORTANCE_LOW，不出声、不弹横幅），只在状态栏显示进度。
 *  · Android 13+ 需要 POST_NOTIFICATIONS 才能显示通知；拿不到权限时服务仍能跑
 *    （foregroundServiceType=dataSync 不依赖通知权限），只是用户看不到通知 —— 不会因此中断下载。
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        instance = this
        acquireWakeLock()
    }

    /**
     * **持住 CPU**（第 57 轮，修「后台下载降速」）。
     *
     * 用户报的现象是「后台速度会降低」。前台服务（第 48 轮加的）只解决
     * **进程不被回收**，**不解决息屏后 CPU 进入休眠** ——
     * 息屏几秒后 SoC 降频/挂起，下载线程被大幅让出时间片，速度自然掉下来，
     * 严重时会长时间停在 0.01 MB/s（截图里那条 18 分钟没动静的就是这种）。
     *
     * [PowerManager.PARTIAL_WAKE_LOCK] 是 Android 给「后台要干活」的正规手段：
     * 只保 CPU 不睡，不点亮屏幕。**在队列排空时立刻释放**（见 onQueueDrained / onDestroy），
     * 不在没有下载任务时空耗电。
     */
    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock != null) return@runCatching
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "hplay:download",
            ).apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        }
    }

    /** 释放 CPU 锁（队列空了、或服务销毁时）。 */
    private fun releaseWakeLock() {
        runCatching {
            val w = wakeLock
            wakeLock = null
            if (w != null && w.isHeld) w.release()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HplayDownloads.cancelAll()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE_TOGGLE) {
            // 暂停/继续全部（第 57 轮通知栏按钮）。
            HplayDownloads.pauseOrResumeAll()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("准备中", 0, 0, true))
        // 进程级 object 在服务进程里是同一份；把通知刷新挂上即可。
        //
        // **第 51 轮修崩溃/卡顿**：这个回调以前是**在调用线程里直接跑**的 ——
        // 而调用它的是最多 20 个分片线程（5 任务 × 4 分片），
        // 每次都要做 2 个 PendingIntent （binder IPC）+ Notification.Builder.build()。
        // 多任务时约 22.5 次/秒、且并发无串行化，本身就是抖动源。
        // 现在统一 post 到主线程的单条队列，并按 300ms 合帧：无论多少线程在推进度，
        // 通知最多每 300ms 重建一次。
        HplayDownloads.onProgressTick = { title, done, total, determinate ->
            pendingTick = Tick(title, done, total, determinate)
            if (tickScheduled.compareAndSet(false, true)) {
                mainHandler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
            }
        }
        HplayDownloads.onQueueDrained = {
            // 没有下载任务了 -> 立刻放掉 CPU 锁，不在空闲时空耗电。
            releaseWakeLock()
            runCatching { stopForegroundCompat(); stopSelf() }
        }
        // 若调用方直接带下载入队（当前不用，保留给将来），这里也可解析 extras
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        runCatching { mainHandler.removeCallbacks(tickRunnable) }
        HplayDownloads.onProgressTick = null
        HplayDownloads.onQueueDrained = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** CPU 锁（第 57 轮）：后台下载保速用。 */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** 一条待刷新的进度（第 51 轮：合帧用）。 */
    private data class Tick(val title: String, val done: Long, val total: Long, val determinate: Boolean)

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tickScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var pendingTick: Tick? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            tickScheduled.set(false)
            val t = pendingTick ?: return
            pendingTick = null
            runCatching { notify(buildNotification(t.title, t.done, t.total, t.determinate)) }
        }
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
            NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "本地下载进度（静默，不打扰）"
                setShowBadge(false)
            },
        )
    }

    /**
     * 通知内容（第 49 轮：多任务之后，通知要显示**整体**而不是最后一个任务）。
     * 单任务时仍然显示那一条的标题与进度（信息量最大）；多任务时显示"共 N 个"。
     */
    private fun buildNotification(title: String, done: Long, total: Long, determinate: Boolean): Notification {
        // 从下载队列取一眼全局：几个在跑、几个在排队、整体进度
        val tasks = HplayDownloads.tasks.value
        val running = tasks.count { it.status == DlStatus.RUNNING }
        val queued = tasks.count { it.status == DlStatus.QUEUED }
        val pausedN = tasks.count { it.status == DlStatus.PAUSED }
        val sumDone = tasks.filter { it.status == DlStatus.RUNNING }.sumOf { it.doneBytes }
        val sumTotal = tasks.filter { it.status == DlStatus.RUNNING }.map { it.totalBytes }.filter { it > 0 }.sum()
        val multi = running > 1 || queued > 0
        // ---------- 第 57 轮：照 1DM+ 的形态重排通知 ----------
        // 参考图（用户提供）：
        //   标题  = 文件名
        //   状态行 = 「14% | 2.58GB | 11.26MB/s | 29s/3m32s」  ← 百分比 | 已下/总量 | 速度 | 已用/剩余
        //   动作   = 暂停 / 停止
        // 单任务时信息量最大，就照这个形态给；多任务时退化成"整体"（否则一行塞不下）。
        val head = if (multi) {
            "同时 " + running + " 个下载中" + (if (queued > 0) "，排队 " + queued else "") +
                (if (pausedN > 0) "，暂停 " + pausedN else "")
        } else title
        val useTotal = if (multi) sumTotal else total
        val useDone = if (multi) sumDone else done
        val body = if (multi && sumTotal > 0) {
            HplayDownloads.humanOf(sumDone) + " / " + HplayDownloads.humanOf(sumTotal)
        } else {
            // 1DM+ 那行：百分比 | 体积 | 速度 | 已用/剩余
            val t = tasks.firstOrNull { it.status == DlStatus.RUNNING || it.status == DlStatus.PAUSED }
            val pct = if (useTotal > 0) (useDone * 100 / useTotal).toInt().coerceIn(0, 100) else 0
            val sizeText = if (useTotal > 0) {
                HplayDownloads.humanOf(useDone) + " / " + HplayDownloads.humanOf(useTotal)
            } else HplayDownloads.humanOf(useDone)
            val speedText = if (t != null && t.speedBps > 0) "%.2f MB/s".format(t.speedBps / 1048576.0) else "— MB/s"
            val etaText = if (t != null && t.etaSec > 0) {
                val s = t.etaSec
                (s / 60).toString() + "m" + (s % 60).toString().padStart(2, '0') + "s"
            } else "—"
            pct.toString() + "% | " + sizeText + " | " + speedText + " | " + etaText
        }
        return buildNotificationInner(head, body, useDone, useTotal, if (multi) useTotal > 0 else determinate)
    }

    private fun buildNotificationInner(
        head: String,
        body: String,
        done: Long,
        total: Long,
        determinate: Boolean,
    ): Notification {
        // ★★★ 第 352 轮（用户报「通知栏点击下载中的通知不跳转正在下载页」）：**这里原来只拉起 App**。
        //
        // 旧写法是一个**光秃秃的 Intent**：没 extra、没 action。点通知的效果只是
        // 「把 MainActivity 拉到前台」——如果用户本来就在首页，什么也不会变，
        // 看起来就是「点了没反应」。要跳到下载页，必须把「要打开哪个页」这件事传给 App。
        //
        // 现在：带 EXTRA_OPEN = "downloads"，由 MainActivity.onNewIntent/onCreate 读取
        // 并写进 HplayNavRequest，Compose 侧收集后打开下载面板（见 HplayNavRequest.kt）。
        //
        // requestCode 仍是 0（唯一），FLAG_UPDATE_CURRENT 保证 extra 每次刷新 ——
        // 否则第二次点通知拿到的还是第一次那份不带 extra 的旧 Intent。
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.han1me.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(com.han1me.HplayNavRequest.EXTRA_OPEN, com.han1me.HplayNavRequest.OPEN_DOWNLOADS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 暂停/继续（第 57 轮，照 1DM+ 的「暂停 / 停止」两个动作）。
        // 语义：全部任务里只要还有在跑的 -> 按钮显示「暂停」（全部暂停）；
        //       全都已暂停 -> 显示「继续」（全部续传）。
        val pause = PendingIntent.getService(
            this, 2,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val anyRunning = runCatching {
            HplayDownloads.tasks.value.any { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }
        }.getOrDefault(false)
        // ★★★ 第 344 轮（全量核查 · P0）：**API 24/25 上这里会抛 NoSuchMethodError**。
        //
        // Notification.Builder(Context, String) 是 **API 26 才加的**构造函数，
        // 而 build.py 的 MIN_SDK 是 24。在 Android 7.x 上 new 这个构造直接抛
        // NoSuchMethodError（是 Error，不是 Exception），startForeground 外层的
        // runCatching 接不住 —— 下载服务当场崩溃，用户点下载就闪退。
        //
        // ensureChannel() 做了 SDK 保护，buildNotificationInner 这里漏了。
        // 现在按版本分两路：26+ 用带 channel 的构造（渠道生效、用户能单独静音），
        // 24/25 用老构造 + setPriority(LOW) —— 老系统本来就没有渠道概念。
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
            // 图标保持本应用特色（用户要求）：沿用系统下载图标形状 + 本应用的渠道，
            // 不换成第三方应用的图案。小图标在状态栏是单色剪影，用系统那个最稳。
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(head)
            .setContentText(body)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, if (anyRunning) "暂停" else "继续", pause).build(),
            )
            .addAction(
                Notification.Action.Builder(null, "停止", stop).build(),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (determinate && total > 0) {
            b.setProgress(100, ((done * 100) / total).toInt().coerceIn(0, 100), false)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL_ID = "hplay.download"
        private const val NOTIF_ID = 0x48504C59   // "HPLY"
        /** 通知重建的最小间隔（第 51 轮合帧）。 */
        private const val TICK_INTERVAL_MS = 300L
        const val ACTION_STOP = "com.han1me.download.STOP"
        /** 通知栏「暂停/继续」按钮（第 57 轮，照 1DM+）。 */
        const val ACTION_PAUSE_TOGGLE = "com.han1me.download.PAUSE_TOGGLE"

        @Volatile
        private var instance: DownloadService? = null

        /**
         * 让下载服务起来。**幂等**：已经在跑就什么都不做。
         * 由 [HplayDownloads.startWorkerLocked] 在入队第一条任务时调用。
         */
        fun ensureRunning(ctx: Context) {
            if (instance != null) return
            val i = Intent(ctx, DownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }

        /** 队列空了就收工（由 [HplayDownloads] 在空转退出时调用）。 */
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, DownloadService::class.java)) }
        }
    }
}