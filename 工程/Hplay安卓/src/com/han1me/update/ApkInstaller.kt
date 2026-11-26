package com.han1me.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.han1me.HplayEnv
import com.han1me.lab.LabLog
import java.io.File

/**
 * **把下载好的 APK 交给系统安装器**（第 331 轮）。
 *
 * 两个参考项目的做法：
 *  · **Han1mePlus**：\`OpenFilex.open(path, type: 'application/vnd.android.package-archive')\`，
 *    本质就是 \`ACTION_VIEW\` + FileProvider 的 content:// URI。
 *  · **Han1meViewer**：走系统下载后由系统的「下载」应用处理，自己不做安装。
 *
 * 这里照 Plus 的路子 —— 自己下载、自己拉起安装器，用户少走两步。
 *
 * ## 三个必须处理的坑（本项目前面几轮已经踩过其中两个）
 *
 * 1. **Android 7.0 起 \`file://\` 会被 StrictMode 拦**：只能走 FileProvider 的 content://。
 *    本项目的 FileProvider 已经在 AndroidManifest 里注册（authorities =
 *    \`<包名>.fileprovider\`），file_paths.xml 用的是 \`root-path path="/"\`，
 *    所以外部私有目录下的 APK 也在授权范围内。
 * 2. **Android 8.0 起需要 REQUEST_INSTALL_PACKAGES**，否则系统直接抛
 *    ActivityNotFoundException（用户看到的就是「点了没反应」）。
 *    没授权时这里**先把用户送到授权页**，回来再点一次即可 —— 不假装成功。
 * 3. **Android 10 起「安装未知应用」是按应用维度授权的**，
 *    开关状态要用 \`canRequestPackageInstalls()\` 查，不能用旧的全局设置。
 *
 * ## 为什么不做「静默安装」
 *
 * 静默安装需要系统签名或 root。本工程是普通应用，做不到；
 * 硬做只会变成「点了没反应」。所以走系统安装器，把「为什么跳出来一个安装界面」
 * 在更新页的说明文字里讲清楚。
 */
object ApkInstaller {

    /**
     * 拉起系统安装器。
     *
     * @return true = 已成功把 Intent 交出去（不代表用户点了安装）；
     *         false = 没有权限或文件不存在，原因已经 toast 给用户了。
     */
    fun install(ctx: Context, apk: File): Boolean {
        if (!apk.isFile || apk.length() == 0L) {
            HplayEnv.toast("安装包不存在或长度为 0：" + apk.name)
            return false
        }
        // ① Android 8+ 的「安装未知应用」授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstall(ctx)) {
            LabLog.i("upd", "未获得「安装未知应用」授权，跳授权页")
            HplayEnv.toast("需要先允许 Hplay 安装应用，请在打开的页面里打开开关，然后回来再点一次「安装」")
            openInstallPermission(ctx)
            return false
        }
        // ② 交给系统安装器（FileProvider content://）
        return runCatching {
            val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.packageName + ".fileprovider", apk,
            )
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 部分 ROM（MIUI/ColorOS）要求显式指定安装器包名才认这个 content://，
            // 但指定错了会 ActivityNotFound —— 所以先按通用方式试，失败再逐个别名试。
            try {
                ctx.startActivity(i)
                true
            } catch (e: android.content.ActivityNotFoundException) {
                LabLog.i("upd", "通用安装器调不起来：" + e.message + "，试系统包名")
                var ok = false
                for (pkg in INSTALLER_PKGS) {
                    val c = Intent(i).setPackage(pkg)
                    if (runCatching { ctx.startActivity(c); true }.getOrDefault(false)) { ok = true; break }
                }
                if (!ok) {
                    HplayEnv.toast("系统里没有可用的安装器（已试 " + INSTALLER_PKGS.size + " 个）。可以到文件管理器里手动打开：" + apk.absolutePath)
                }
                ok
            }
        }.getOrElse {
            LabLog.i("upd", "拉起安装器异常：" + it)
            HplayEnv.toast("拉起安装器失败：" + (it.message ?: it.toString()))
            false
        }
    }

    /** 是否已获得「安装未知应用」授权。 */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        else true

    /** 跳到本应用的「安装未知应用」授权页。 */
    fun openInstallPermission(ctx: Context) {
        runCatching {
            val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }.onFailure {
            HplayEnv.toast("打不开授权页，请到「设置 → 应用 → 特殊权限 → 安装未知应用」里手动允许")
        }
    }

    private const val APK_MIME = "application/vnd.android.package-archive"

    /** 常见系统安装器包名（通用 Intent 失败时逐个试）。 */
    private val INSTALLER_PKGS = listOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.coloros.packageinstaller",
        "com.iqoo.packageinstaller",
    )
}
