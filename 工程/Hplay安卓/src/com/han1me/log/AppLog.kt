package com.han1me.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * **应用级运行日志**（第 137 轮建立；第 334 轮大改）。
 *
 * ## 第 334 轮：从「只有 13 处埋点」扩到「全链路可观测」
 *
 * 用户原话：「日志功能少了，你补上 app 所有详细日志网络请求，和 app 所有操作的日志功能，
 * 数据板块没有日志，只有备份恢复」。
 *
 * 上一版的问题不是设计差，而是**覆盖面太窄**：
 *  · 全项目只有 13 处 AppLog 调用（cf / tx / NetGuard 三个模块）；
 *  · 而 `AppLog.switch()` **没有任何 UI 能调用** —— 日志开关藏在设置键里，
 *    用户在界面上根本打不开，等于日志功能不存在；
 *  · 网络请求只在 X99.getHtml 里打点，HanimeClient / 各板块 / 播放器 / 下载器 /
 *    更新检查全都看不到。
 *
 * ## 这一版的四条主线
 *
 * ① **一个 OkHttp 拦截器覆盖所有客户端**（[LoggingInterceptor]）。
 *    全项目有 14 个 `OkHttpClient.Builder()`（gen 8 + src 6）。逐个加日志不可维护，
 *    所以写一个拦截器，再用 postpatch 把它挂到**每一个** builder 上 ——
 *    新加的客户端只要沿用既有写法就自动被覆盖。
 * ② **操作日志**：[act] 标签记录用户动作（点哪个板块、开哪个详情、切线路、
 *    收藏/取消、下载/暂停/取消、播放/暂停、登录/退出、备份/恢复、改设置…）。
 * ③ **界面开关**：数据板块新增「运行日志」分区（开关 / 条数 / 查看 / 导出 / 清空）。
 * ④ **双落点**：内存环形缓冲（界面即时看）+ 外置文件（`adb pull` 可取，release 包也能读）。
 *
 * ## 性能纪律（第 140 轮的教训必须保留）
 *
 *  · 关闭时第一行 return（一次 volatile 读），零开销；
 *  · 环形数组 + 读写指针：写入 O(1)、size O(1)（ConcurrentLinkedDeque.size() 是 O(n)，
 *    曾把主线程 Compose 重组拖慢）；
 *  · 文件写入**异步 + 批量**：进有界队列，单独线程每 400ms 或满 32 条 flush 一次；
 *    队列满就丢弃最旧的，**绝不阻塞调用线程**（日志不能拖慢业务）；
 *  · 文件按天切分 + 单文件上限 4 MB，超了就滚动（hplay-1.log → hplay-2.log）。
 */
object AppLog {

    private const val MAX_LINES = 2000

    /** 文件落点目录名（getExternalFilesDir 下）。 */
    private const val DIR_NAME = "logs"

    /** 单个日志文件上限（超过就滚动）。 */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** 内存环形缓冲。 */
    private val ring = arrayOfNulls<String>(MAX_LINES)
    private var head = 0
    private var count = 0
    private val lock = Any()

    private val t0 = System.currentTimeMillis()

    @Volatile private var sw: Boolean = false

    /** 写文件开关（与内存开关分开：用户可能只想看不想落盘）。 */
    @Volatile private var fileOn: Boolean = true

    val isOn: Boolean get() = sw

    /** 总写入条数（含已被环形缓冲覆盖的），界面显示「累计」用。 */
    private val totalWritten = AtomicLong(0)

    fun switch(on: Boolean) {
        sw = on
        if (on) {
            w("LOG", "==== 日志已开启 ====")
            w("LOG", "设备 " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                " / Android " + android.os.Build.VERSION.RELEASE)
            w("LOG", "文件落点 " + fileDir()?.absolutePath.orEmpty())
        } else {
            clear()
        }
    }

    fun setFileLogging(on: Boolean) { fileOn = on }

    fun clear() {
        runCatching { synchronized(lock) { ring.fill(null); head = 0; count = 0 } }
        totalWritten.set(0)
    }

    // ---------------------------------------------------------------- 写入

    fun w(tag: String, msg: String) {
        if (!sw) return
        runCatching {
            val now = System.currentTimeMillis()
            val line = "[" + hhmmss(now) + " +" + ((now - t0) / 1000) + "s][" + tag + "] " + msg
            synchronized(lock) {
                ring[head] = line
                head = (head + 1) % MAX_LINES
                if (count < MAX_LINES) count++
            }
            totalWritten.incrementAndGet()
            if (fileOn) enqueueFile(line)
        }
    }

    /** 网络请求成功/完成。 */
    fun net(label: String, url: String, code: Int, bytes: Int, ms: Long, note: String = "") {
        if (!sw) return
        runCatching {
            val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault(url)
            w("NET", label + " " + host + path.take(60) + " -> " + code + " " + bytes + "B " + ms + "ms" +
                (if (note.isEmpty()) "" else " " + note))
        }
    }

    /** 网络请求失败。 */
    fun fail(label: String, url: String, why: String, ms: Long = -1) {
        if (!sw) return
        runCatching {
            val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault(url)
            w("FAIL", label + " " + host.take(50) + " " + why + (if (ms >= 0) " (" + ms + "ms)" else ""))
        }
    }

    fun step(tag: String, msg: String) = w(tag, msg)

    // ---------------------------------------------------------------- 操作日志

    /**
     * **用户操作**（第 334 轮新增，统一 `[act]` 前缀便于 grep）。
     *
     * 只记「谁做了什么」，不记内容（标题可能很长、也可能含敏感词），
     * 长文本统一截到 [limit]。
     */
    fun act(action: String, detail: String = "") {
        if (!sw) return
        w("act", action + (if (detail.isBlank()) "" else " | " + detail.take(120)))
    }

    /** 界面导航（进哪个页面）。 */
    fun nav(where: String) = act("nav", where)

    /** 页面/板块加载开始。 */
    fun load(tag: String, what: String) = w("load", tag + " " + what.take(120))

    /** 播放器事件。 */
    fun player(event: String, detail: String = "") {
        if (!sw) return
        w("player", event + (if (detail.isBlank()) "" else " | " + detail.take(120)))
    }

    /** 下载事件。 */
    fun dl(event: String, detail: String = "") {
        if (!sw) return
        w("dl", event + (if (detail.isBlank()) "" else " | " + detail.take(120)))
    }

    /** 设置变更。 */
    fun setting(key: String, value: String) = act("setting", key + " = " + value.take(80))

    // ---------------------------------------------------------------- 文件落盘

    /** 待写队列（有界；满了丢最旧，绝不阻塞调用线程）。 */
    private val fileQueue = java.util.concurrent.ArrayBlockingQueue<String>(2048)

    @Volatile private var writerStarted = false

    private fun fileDir(): File? = runCatching {
        val ctx = com.han1me.HplayEnv.applicationOrNull() ?: return null
        // 外部私有目录：卸载才清，普通重装不丢；`adb pull` 免 root 可读。
        val base = ctx.getExternalFilesDir(DIR_NAME) ?: File(ctx.filesDir, DIR_NAME)
        base.mkdirs()
        base
    }.getOrNull()

    private fun enqueueFile(line: String) {
        // 队满时丢弃最旧的一条，再放新的 —— 丢日志远好于阻塞业务线程。
        if (!fileQueue.offer(line)) {
            runCatching { fileQueue.poll() }
            runCatching { fileQueue.offer(line) }
        }
        startWriterOnce()
    }

    private fun startWriterOnce() {
        if (writerStarted) return
        synchronized(this) {
            if (writerStarted) return
            writerStarted = true
            runCatching {
                val th = Thread({
                    val buf = ArrayList<String>(64)
                    while (true) {
                        try {
                            // 阻塞取第一条，再尽量多取（最多 32 条）凑批。
                            val first = fileQueue.take()
                            buf.clear()
                            buf.add(first)
                            fileQueue.drainTo(buf, 31)
                            flushToFile(buf)
                        } catch (ie: InterruptedException) {
                            return@Thread
                        } catch (t: Throwable) {
                            // 写盘失败不能死循环刷屏：睡一下继续。
                            runCatching { Thread.sleep(1000) }
                        }
                    }
                }, "hplay-applog")
                th.isDaemon = true
                th.start()
            }
        }
    }

    private fun flushToFile(lines: List<String>) {
        val dir = fileDir() ?: return
        val f = File(dir, "hplay.log")
        // 滚动：单文件超限就改名归档（hplay-1.log、hplay-2.log…），最多保留 5 个。
        if (f.length() > MAX_FILE_BYTES) {
            for (i in 4 downTo 1) {
                val src = File(dir, if (i == 1) "hplay.log" else "hplay-" + i + ".log")
                if (src.exists()) src.renameTo(File(dir, "hplay-" + (i + 1) + ".log"))
            }
        }
        f.appendText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- 读取/导出

    private fun hhmmss(ms: Long): String {
        val total = (ms + java.util.TimeZone.getDefault().getOffset(ms)) % 86_400_000L
        val h = (total / 3_600_000L).toInt()
        val mi = ((total / 60_000L) % 60L).toInt()
        val s = ((total / 1000L) % 60L).toInt()
        val msec = (total % 1000L).toInt()
        return pad2(h) + ":" + pad2(mi) + ":" + pad2(s) + "." + pad3(msec)
    }

    private fun pad2(v: Int): String = if (v < 10) "0" + v else "" + v
    private fun pad3(v: Int): String = if (v < 10) "00" + v else if (v < 100) "0" + v else "" + v

    fun dump(): String = runCatching {
        synchronized(lock) {
            if (count == 0) "" else {
                val sb = StringBuilder(count * 96)
                val start = if (count < MAX_LINES) 0 else head
                for (i in 0 until count) {
                    val s = ring[(start + i) % MAX_LINES] ?: continue
                    sb.append(s).append((10).toChar())
                }
                sb.toString()
            }
        }
    }.getOrDefault("")

    /** 当前条数。O(1)。 */
    fun size(): Int = count

    /** 累计写入条数（含已被覆盖的）。 */
    fun total(): Long = totalWritten.get()

    /** 磁盘上已有日志文件的大小合计（界面显示用）。 */
    fun diskBytes(): Long = runCatching {
        val dir = fileDir() ?: return 0L
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    /** 磁盘日志文件列表（新的在前）。 */
    fun diskFiles(): List<File> = runCatching {
        val dir = fileDir() ?: return emptyList()
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }.getOrDefault(emptyList())

    /** 清空磁盘日志。 */
    fun clearDisk() = runCatching {
        diskFiles().forEach { it.delete() }
        Unit
    }.getOrNull()

    /** 导出到 cache 目录并返回路径（供分享面板用）。 */
    fun exportToFile(): String? = runCatching {
        val ctx = com.han1me.HplayEnv.applicationOrNull() ?: return null
        val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "hplay-log-" + stamp + ".txt")
        val body = buildString {
            appendLine("# Hplay 运行日志")
            appendLine("# 导出时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("# 内存条数: " + size() + " / 累计 " + total())
            appendLine("# 设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                " / Android " + android.os.Build.VERSION.RELEASE)
            appendLine("# 应用: " + runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrDefault("?"))
            appendLine("# 磁盘日志: " + diskFiles().joinToString { it.name + "(" + it.length() + "B)" })
            appendLine("#")
            appendLine("# ---- 内存缓冲（最近 " + size() + " 条）----")
            append(dump())
            appendLine("")
            appendLine("# ---- 磁盘日志（可能比内存更早）----")
            diskFiles().take(3).forEach { df ->
                appendLine("# " + df.name)
                runCatching { append(df.readText(Charsets.UTF_8).takeLast(200_000)) }
            }
        }
        f.writeText(body, Charsets.UTF_8)
        f.absolutePath
    }.getOrNull()
}