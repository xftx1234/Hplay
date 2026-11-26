package com.han1me.download

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.han1me.HplayEnv

/**
 * **公共目录访问**（第 71 轮，真实事故修复）。
 *
 * ## 为什么要这个文件
 * 用户把保存目录指到 `/storage/emulated/0/Download/Hplay 视频`，但应用 `targetSdk=34` 走**严格分区存储**，
 * 而此前**没有声明任何公共目录读取权限** —— 结果 `File.listFiles()` 返回空，
 * 「已下载」里所有条目显示 **0 B / 无封面**（用户报的「视频和介绍页都没了」）。
 * 本机实测：清过一次应用数据（权限被重置）后必现。
 *
 * ## 为什么必须要「所有文件访问」
 * 分区存储下，媒体权限（READ_MEDIA_VIDEO）**只覆盖标准媒体文件**；
 * 而我们的产物里有 `.ts` 分片、`.m3u8` 清单、以及自己写的 `.detail.json` —— 这些**不是媒体**，
 * 用媒体权限一律读不到。所以读下载目录的唯一可靠办法是 MANAGE_EXTERNAL_STORAGE
 * （系统设置里的「所有文件访问」）。媒体权限保留为次选。
 *
 * 本对象只做「查状态 / 拉起申请」，**不假装权限已给**：拿不到就如实返回 false，界面照实显示。
 */
object HplayStorageAccess {

    /** 是否已获得**完全**的公共目录读取能力（决定已下载列表能否读到文件）。 */
    fun hasFullAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        }
        return hasLegacyRead()
    }

    /** 至少能读到标准媒体（次选路径）。 */
    fun hasAnyAccess(): Boolean = hasFullAccess() || hasMediaRead() || hasLegacyRead()

    private fun hasMediaRead(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val c = ctx() ?: return false
        return ContextCompat.checkSelfPermission(c, "android.permission.READ_MEDIA_VIDEO") ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasLegacyRead(): Boolean {
        val c = ctx() ?: return false
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ctx(): Context? = runCatching { HplayEnv.requireContext() }.getOrNull()

    /** 当前状态的**人话**描述（界面直接显示，不美化）。 */
    fun statusText(): String = when {
        hasFullAccess() -> "已授予「所有文件访问」：可读取任意保存目录（含 .ts/.m3u8/.json 等非媒体文件）"
        hasMediaRead() -> "仅有媒体读取权限：只能看到标准视频文件（.mp4 等）；.ts 分片与我们自己写的介绍页缓存**读不到**"
        hasLegacyRead() -> "仅有旧版存储读取权限：覆盖面有限"
        else -> "**没有**公共目录读取权限 —— 保存目录里的文件在应用看来不可见（会显示 0 B / 无封面）"
    }

    /**
     * 拉起申请。
     * @return true = 已发出申请（用户需在接下来的系统界面上放行）；false = 环境不支持，需用户手动去设置。
     */
    fun request(): Boolean {
        val c = ctx() ?: return false
        if (Build.VERSION.SDK_INT >= 30) {
            // 30+ 走「所有文件访问」的特殊授权页（不是普通运行时弹窗）
            val ok = runCatching {
                c.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrElse {
                runCatching {
                    c.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrDefault(false)
            }
            return ok
        }
        // 30 以下走普通运行时权限
        val a = runCatching { com.han1me.HplayEnv.activity() }.getOrNull() ?: return false
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf("android.permission.READ_MEDIA_VIDEO")
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return runCatching {
            ActivityCompat.requestPermissions(a as Activity, perms, 4711)
            true
        }.getOrDefault(false)
    }
}
