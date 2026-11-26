package com.han1me.lab

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * **主线程看门狗**（实验分叉 · 2026-10-31）。
 *
 * 为什么需要它：
 *  · 用户报「加入内置节点后，很多时候都会出现无法响应（ANR）」；
 *  · 但本应用是 release 包，logcat 被抑制、ANR 堆栈读不到（/data/anr 无权限）；
 *  · 所以**在应用内自己测主线程**：每 2 秒往主线程投一个任务，
 *    若迟迟不执行就说明主线程被卡住，**当场把主线程栈抓下来**写进 LabLog。
 *
 * 这样「卡死在哪里」第一次就能看到，不用猜。
 */
object MainWatchdog {

    private val handler = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger(0)
    private val beat = AtomicLong(0)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        val t = Thread({
            while (true) {
                runCatching {
                    val my = seq.incrementAndGet()
                    handler.post { beat.set(my.toLong()) }
                    Thread.sleep(2000)
                    val got = beat.get()
                    if (got < my) {
                        // 主线程 2 秒还没跑完这个空任务 -> 卡住
                        LabLog.i("ANR", "主线程 >2s 未响应，抓栈：")
                        val st = Looper.getMainLooper().thread.stackTrace
                        st.take(45).forEachIndexed { i, e ->
                            LabLog.i("ANR", "  #" + i + " " + e.toString())
                        }
                        // 等它缓过来，避免刷屏
                        Thread.sleep(4000)
                    }
                }
            }
        }, "hpla-watchdog").apply { isDaemon = true }
        t.start()
        LabLog.i("ANR", "看门狗已启动")
    }
}
