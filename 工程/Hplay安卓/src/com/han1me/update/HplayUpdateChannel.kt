package com.han1me.update

import android.content.Context
import com.han1me.X99

/**
 * **发布线与更新渠道**（第 331 轮建立；第 332 轮重做）。
 *
 * ## 第 332 轮：139 直连渠道**整条移除**
 *
 * 用户原话：「彻底去除 139 直连相关更新渠道，代码层也去掉，改成 github 默认 + 自建垫底」。
 *
 * 移除理由（用户没说，但从工程角度是硬理由）：
 *  · 139 需要一条 Basic 凭据（pc:<手机号>:<token>）才能取清单与直链。
 *    这条凭据等价于两个移动云盘账号的访问权，而 APK 是公开分发的 ——
 *    第 331 轮实测发现它能被原样编进 classes*.dex，只能靠 --no-cred 剥掉。
 *    剥掉之后 139 渠道在公开发布版里**本来就用不了**：留着一条默认就失效的渠道
 *    只会让用户看到「未配置凭据」的失败页。
 *  · GitHub Release 不需要任何凭据，公网匿名可读，天然适合公开分发。
 *
 * 于是现在的渠道只剩三条，**GitHub 是默认，自建网盘垫底**：
 *
 * | 渠道 | 取什么 | 凭据要求 |
 * |---|---|---|
 * | CH_GITHUB  | GitHub Releases 本线正式发布 | 无（匿名） |
 * | CH_CI      | GitHub 上 ci-* 标签的预发布构建 | 无（匿名） |
 * | CH_NETDISK | 自建网盘 OpenList（多挂载目录轮询） | 无（只读 API） |
 *
 * ## 两条发布线
 *
 * · **实验版**（lab）：分支 lab，tag 前缀 lab-v，Release 标 prerelease。
 * · **主线版**（stable）：分支 main，tag 前缀 v，Release 是正式版。
 *
 * 两条线**包名与签名相同**（见 build.py 的 PACKAGE 注释），所以能互相覆盖安装。
 *
 * ## 默认值口径
 *
 * 用户要求「自动检查更新默认 github 实验版」。所以：
 *  · 默认渠道 = CH_GITHUB（与构建线无关，两条线都默认 GitHub）；
 *  · 默认发布线 = LINE_LAB（实验版），**即使这个包是主线版构建的**。
 *
 * 第二条是有意的：用户明确说「默认 github 实验版」。主线版的用户想看主线更新时，
 * 在更新页把线切过去即可（选择会持久化，不用每次改）。
 * 但构建线仍然是 buildLine，界面会标注「本包」，让用户知道自己在跑哪条线的包。
 */
object HplayUpdateChannel {

    /** 实验版线标识。 */
    const val LINE_LAB = "lab"
    /** 主线版线标识。 */
    const val LINE_STABLE = "stable"

    /** GitHub Releases 正式版。**默认渠道**。 */
    const val CH_GITHUB = "github"
    /** GitHub Releases 的 CI 预发布构建。 */
    const val CH_CI = "ci"
    /** 自建网盘（OpenList）—— 垫底通道。 */
    const val CH_NETDISK = "netdisk"

    /** 三条渠道的展示名（界面顺序即此顺序：GitHub 在前 = 默认）。 */
    val CHANNELS: List<Pair<String, String>> = listOf(
        CH_GITHUB to "GitHub Releases",
        CH_CI to "GitHub CI 构建",
        CH_NETDISK to "自建网盘（垫底）",
    )

    /** 两条发布线的展示名。 */
    val LINES: List<Pair<String, String>> = listOf(
        LINE_LAB to "实验版（lab）",
        LINE_STABLE to "主线版（stable）",
    )

    private const val K_LINE = "update.line"
    private const val K_CHANNEL = "update.channel"
    private const val K_AUTO = "update.autoCheck"

    /** 构建时所属的线（HplayBuildInfo.GH_LINE 的合法化版本）。 */
    val buildLine: String
        get() = if (HplayBuildInfo.GH_LINE == LINE_STABLE) LINE_STABLE else LINE_LAB

    /** 用户选的线。没选过就是**实验版**（用户要求「默认 github 实验版」）。 */
    var line: String
        get() = X99.setting(K_LINE, LINE_LAB).let {
            if (it == LINE_STABLE) LINE_STABLE else LINE_LAB
        }
        set(v) {
            X99.putSetting(K_LINE, if (v == LINE_STABLE) LINE_STABLE else LINE_LAB)
        }

    /**
     * 用户选的渠道。没选过就是 GitHub。
     *
     * 存的值若已不在列表里（例如旧版本留下的 yun139），这里会**回落 GitHub** ——
     * 用 any{} 而不是 contains，是因为 CHANNELS 是 Pair 列表，contains 要整个 Pair。
     */
    var channel: String
        get() {
            val v = X99.setting(K_CHANNEL, "")
            if (CHANNELS.any { it.first == v }) return v
            return CH_GITHUB
        }
        set(v) {
            if (CHANNELS.any { it.first == v }) X99.putSetting(K_CHANNEL, v)
        }

    /** 开屏是否自动检查更新（默认开）。 */
    var autoCheck: Boolean
        get() = X99.setting(K_AUTO, "1") != "0"
        set(v) {
            X99.putSetting(K_AUTO, if (v) "1" else "0")
        }

    /** 渠道展示名（界面上如实标注「这次走的是哪条」）。 */
    fun channelLabel(id: String): String =
        CHANNELS.firstOrNull { it.first == id }?.second ?: id

    /** 线的展示名。 */
    fun lineLabel(id: String): String =
        LINES.firstOrNull { it.first == id }?.second ?: id

    /** 本线在 GitHub 上的 tag 前缀。 */
    fun tagPrefix(line: String): String =
        if (line == LINE_STABLE) "v" else "lab-v"

    /** GitHub 仓库是否已配置（构建变量没填时为 false）。 */
    val repoConfigured: Boolean
        get() = HplayBuildInfo.GH_REPO.contains("/") &&
            !HplayBuildInfo.GH_REPO.startsWith("OWNER/")

    /** 仓库地址（网页用）。 */
    val repoUrl: String get() = "https://github.com/" + HplayBuildInfo.GH_REPO

    /** 清掉用户选择，回到默认（GitHub + 实验版）。 */
    fun resetToBuildDefaults(ctx: Context) {
        X99.putSetting(K_LINE, LINE_LAB)
        X99.putSetting(K_CHANNEL, CH_GITHUB)
    }

    /**
     * 迁移旧的渠道值（第 332 轮）。
     *
     * 旧版本可能存过 yun139（已删除）。getter 已经会回落 GitHub，
     * 但**磁盘上的旧值不清掉** —— 万一以后要恢复那条渠道，线索还在。
     * 这里把非法值就地改写成 GitHub，让设置文件读起来是干净的。
     */
    fun migrateLegacy() {
        val raw = X99.setting(K_CHANNEL, "")
        if (raw.isNotEmpty() && CHANNELS.none { it.first == raw }) {
            X99.putSetting(K_CHANNEL, CH_GITHUB)
        }
    }
}