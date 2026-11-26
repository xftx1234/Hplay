package com.han1me.update

import android.content.Context
import com.han1me.X99

/**
 * **发布线与更新渠道**（第 331 轮）。
 *
 * ## 用户要的四条来源要能自由选
 *
 * 原话：「app 检查更新内新增 cl 更新渠道，可选实验版和主线版自由更新」——
 * 「cl」是「CI」的输入误差，结合上下文（两个参考项目都有 CI 渠道）判断为 CI。
 *
 * 于是更新来源一共四条，**全部保留、互不替代**：
 *
 * | 来源 | 取什么 | 谁在用 |
 * |---|---|---|
 * | [CH_YUN139]   | 移动云盘 139 直连（公网可达，出门也能查） | 既有主通道 |
 * | [CH_NETDISK]  | 自建网盘 OpenList（4 个挂载目录轮询）      | 既有兜底 |
 * | [CH_GITHUB]   | GitHub Releases（默认读本线的正式版）      | **本轮新增** |
 * | [CH_CI]       | GitHub Releases 里 `ci-*` 标签的预发布构建 | **本轮新增** |
 *
 * ## 两条发布线
 *
 * · **实验版**（lab）：分支 `lab`，tag 前缀 `lab-v`，Release 标 prerelease。
 * · **主线版**（stable）：分支 `main`，tag 前缀 `v`，Release 是正式版。
 *
 * 两条线**包名与签名相同**（见 build.py 的 PACKAGE 注释），所以能互相覆盖安装。
 * 用户可以装实验版、在更新页把线切到主线版、下主线的新包覆盖上去 —— 反之亦然。
 * 这条能力是用户明确要求的（「实现两个版本可覆盖升级」）。
 *
 * ## 为什么渠道要存成「用户选择」而不是「构建常量」
 *
 * 构建期只知道自己是哪条线（[HplayBuildInfo.GH_LINE]），那只是**默认值**。
 * 用户切过之后以用户的为准 —— 否则实验版用户永远看不到主线版。
 */
object HplayUpdateChannel {

    /** 实验版线标识。 */
    const val LINE_LAB = "lab"
    /** 主线版线标识。 */
    const val LINE_STABLE = "stable"

    /** 移动云盘 139 直连。 */
    const val CH_YUN139 = "yun139"
    /** 自建网盘（OpenList）。 */
    const val CH_NETDISK = "netdisk"
    /** GitHub Releases 正式版。 */
    const val CH_GITHUB = "github"
    /** GitHub Releases 的 CI 预发布构建。 */
    const val CH_CI = "ci"

    /** 四条渠道的展示名（设置页顺序即此顺序）。 */
    val CHANNELS: List<Pair<String, String>> = listOf(
        CH_YUN139 to "移动云盘 139（直连）",
        CH_NETDISK to "自建网盘（兜底）",
        CH_GITHUB to "GitHub Releases",
        CH_CI to "GitHub CI 构建",
    )

    /** 两条发布线的展示名。 */
    val LINES: List<Pair<String, String>> = listOf(
        LINE_LAB to "实验版（lab）",
        LINE_STABLE to "主线版（stable）",
    )

    private const val K_LINE = "update.line"
    private const val K_CHANNEL = "update.channel"
    private const val K_AUTO = "update.autoCheck"

    /** 构建时所属的线（[HplayBuildInfo.GH_LINE] 的合法化版本）。 */
    val buildLine: String
        get() = if (HplayBuildInfo.GH_LINE == LINE_STABLE) LINE_STABLE else LINE_LAB

    /** 用户选的线。没选过就跟随构建所属的线。 */
    var line: String
        get() = X99.setting(K_LINE, buildLine).let {
            if (it == LINE_STABLE) LINE_STABLE else LINE_LAB
        }
        set(v) {
            X99.putSetting(K_LINE, if (v == LINE_STABLE) LINE_STABLE else LINE_LAB)
        }

    /**
     * 用户选的渠道。没选过时：实验版默认 139（既有主通道），主线版默认 GitHub。
     *
     * 为什么实验版不默认 GitHub：139 那条是既有的、已验证能跑满的通道，
     * 换默认值等于把现有用户的更新链路切到未验证的新路上。
     * GitHub 在实验版里是**可选**的第二渠道（用户原话：「检查更新拿 github 作为备用渠道2」）。
     */
    var channel: String
        get() {
            val v = X99.setting(K_CHANNEL, "")
            if (v in CHANNELS.map { it.first }) return v
            return if (buildLine == LINE_STABLE) CH_GITHUB else CH_YUN139
        }
        set(v) {
            if (v in CHANNELS.map { it.first }) X99.putSetting(K_CHANNEL, v)
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

    /** 清掉用户选择，回到构建默认（设置页「恢复默认」用）。 */
    fun resetToBuildDefaults(ctx: Context) {
        X99.putSetting(K_LINE, buildLine)
        X99.putSetting(K_CHANNEL, if (buildLine == LINE_STABLE) CH_GITHUB else CH_YUN139)
    }
}