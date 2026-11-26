package com.han1me.lab

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **实验功能诊断日志**（实验分叉 · 2026-10-31）。
 *
 * 为什么单独做一个：
 *  · 本应用是 release 包（不可 run-as），logcat 又被系统抑制 —— 出问题根本看不到原因；
 *  · 写到 **getExternalFilesDir()** 后，`adb shell cat` 直接可读（无需 root），
 *    这样「内核为什么没启动」这类问题第一次就能定位。
 *
 * 同时也作为用户要求的「运行日志」的落点之一（内存环形 + 外置文件双写）。
 */
object LabLog {

    @Volatile
    private var file: File? = null

    @Volatile
    private var mem: String = ""

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    fun attach(ctx: Context) {
        if (file != null) return
        runCatching {
            val d = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            file = File(d, "hpla_lab.log")
        }
    }

    fun i(tag: String, msg: String) {
        val line = fmt.format(Date()) + " [" + tag + "] " + msg
        mem = (mem + line + "\n").takeLast(20_000)
        runCatching { file?.appendText(line + "\n") }
    }

    /** 内存里的最近日志（供设置页显示）。 */
    fun dump(): String = mem

    fun clear() {
        mem = ""
        runCatching { file?.delete() }
    }
}
