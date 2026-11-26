package com.han1me.log

/**
 * 轻量运行日志（第 137 轮；第 140 轮做性能优化）。
 *
 * 设计目标（按用户「占比微小、不影响日常运行」）：
 *  ① 默认关闭：不开时第一行就 return（一次 volatile 读）。
 *  ② 只在内存：固定容量数组环形缓冲，写入 O(1)，不写文件不占磁盘。
 *  ③ 不阻断业务：任何日志异常都被吞掉。
 *
 * ★ 第 140 轮性能修复（用户报「开启日志后界面切换有略微卡顿」）：
 *  · 原来用 ConcurrentLinkedDeque：它的 size() 是 O(n) 遍历，而 w() 每写一条都要判断
 *    「size > MAX_LINES」，UI 又每帧调 size() 显示条数 —— 开关一开，每次写日志都遍历
 *    400 个节点，主线程上的 Compose 重组被拖慢。
 *  · 换成固定数组 + 读写指针的环形缓冲：写入 O(1)、size O(1)。
 *  · SimpleDateFormat 非线程安全且每次 format 都有分配；改为自己拼时分秒。
 */
object AppLog {
    private const val MAX_LINES = 400

    private val ring = arrayOfNulls<String>(MAX_LINES)
    private var head = 0
    private var count = 0
    private val lock = Any()

    private val t0 = System.currentTimeMillis()

    @Volatile private var sw: Boolean = false

    val isOn: Boolean get() = sw

    fun switch(on: Boolean) {
        sw = on
        if (on) w("LOG", "==== 日志已开启 ====") else clear()
    }

    fun clear() {
        runCatching { synchronized(lock) { ring.fill(null); head = 0; count = 0 } }
    }

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
        }
    }

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

    fun net(label: String, url: String, code: Int, bytes: Int, ms: Long, note: String = "") {
        if (!sw) return
        runCatching {
            val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault(url)
            w("NET", label + " " + host + path.take(50) + " -> " + code + " " + bytes + "B " + ms + "ms" + (if (note.isEmpty()) "" else " " + note))
        }
    }

    fun fail(label: String, url: String, why: String, ms: Long = -1) {
        if (!sw) return
        runCatching {
            val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault(url)
            w("FAIL", label + " " + host.take(40) + " " + why + (if (ms >= 0) " (" + ms + "ms)" else ""))
        }
    }

    fun step(tag: String, msg: String) = w(tag, msg)

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

    /** 当前条数。O(1)（老实现是 ConcurrentLinkedDeque.size()，要遍历全部节点）。 */
    fun size(): Int = count

    fun exportToFile(): String? = runCatching {
        val ctx = com.han1me.HplayEnv.applicationOrNull() ?: return null
        val dir = java.io.File(ctx.cacheDir, "logs").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = java.io.File(dir, "hplay-log-" + stamp + ".txt")
        val body = buildString {
            appendLine("# Hplay 运行日志")
            appendLine("# 导出时间: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            appendLine("# 条数: " + size())
            appendLine("# 设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " / Android " + android.os.Build.VERSION.RELEASE)
            appendLine("# 应用: " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("?"))
            appendLine("")
            append(dump())
        }
        f.writeText(body, Charsets.UTF_8)
        f.absolutePath
    }.getOrNull()
}