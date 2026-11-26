package com.han1me.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.lab.AccountStore
import com.han1me.lab.CachedAccount
import com.han1me.lab.CfCookieBridge
import com.han1me.lab.HplayBackup
import com.han1me.lab.LoginState

/**
 * **备份与多账户**（2026-11-25 第 309 轮；第 322 轮接线到系统文件选择器）。
 *
 * 用户要求：
 *  · 「备份要涵盖登录凭据，三次元收藏历史，下载和设定等所有操作，恢复要兼容主线旧的备份」
 *  · 「多账户体系没有实现，登录后显示没有保存的账户」
 *
 * ## 第 322 轮改了什么
 *
 * 旧版两个按钮只写/读应用私有目录里的一份 hplay_backup.json ——
 * 换设备时根本拿不到那个文件，等于**没接线**。
 * 现在：
 *  · 「导出备份」→ 系统文件选择器（CreateDocument），用户自己选存到网盘/U盘/下载目录；
 *  · 「从文件恢复」→ 系统文件选择器（OpenDocument），能选任何 json，含**主线旧备份**；
 *  · 「导出到本机」/「恢复本机」→ 仍保留，用于同一台设备上的快速回滚。
 *
 * 账户区也一并修好：登录后自动出现条目，可切换、可移除、可添加。
 */
@Composable
fun BackupAccountSettings(
    modifier: Modifier = Modifier,
    onToast: (String) -> Unit = {},
    /** 点「添加账户」时回调（由宿主打开网页登录）。 */
    onAddAccount: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }

    // 每次重组重读（tick 变化触发）
    val accounts = remember(tick) { AccountStore.all(ctx) }
    val activeEmail = remember(tick) { AccountStore.activeEmail(ctx) }
    val loggedIn = remember(tick) { CfCookieBridge.savedLogin().isNotBlank() }

    fun say(s: String) {
        msg = s
        onToast(s)
    }

    // ---------- 导出到用户选的位置 ----------
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) { say("已取消导出"); return@rememberLauncherForActivityResult }
        val json = HplayBackup.export(ctx)
        if (json == null) { say("导出失败：读取本地数据出错"); return@rememberLauncherForActivityResult }
        val ok = HplayBackup.saveToUri(ctx, uri, json)
        if (ok) {
            val s = HplayBackup.summarize(json)
            say(
                "已导出：收藏 " + (s?.favorites ?: 0) + " 条 · 账户 " + (s?.accounts ?: 0) +
                    " 个 · 设置 " + (s?.settingsKeys ?: 0) + " 项 · " +
                    (if (s?.hasLogin == true) "含登录凭据" else "无登录凭据"),
            )
        } else {
            say("导出失败：无法写入所选位置")
        }
    }

    // ---------- 从用户选的文件恢复 ----------
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) { say("已取消恢复"); return@rememberLauncherForActivityResult }
        val json = HplayBackup.loadFromUri(ctx, uri)
        if (json == null) { say("恢复失败：读不到所选文件"); return@rememberLauncherForActivityResult }
        val n = HplayBackup.restore(ctx, json)
        say(if (n >= 0) "已恢复 " + n + " 类数据（含登录凭据与账户）" else "恢复失败：不是可识别的备份文件")
        CachedAccount.clear()
        LoginState.notifyChanged()
        tick++
    }

    Column(modifier.fillMaxWidth()) {

        // ============ 账户 ============
        HplaySectionGroup("账户") {
            Column(Modifier.padding(14.dp)) {
                // 当前登录态提示
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (loggedIn) cs.primary else cs.outline),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (loggedIn) "已登录" else "未登录",
                        color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("共 " + accounts.size + " 个账户", color = cs.onSurfaceVariant, fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))

                if (accounts.isEmpty()) {
                    Text(
                        if (loggedIn) {
                            "当前已登录，但这个账户还没存进账户表。点下面的「保存当前账户」即可。"
                        } else {
                            "还没有保存的账户。用「网页登录」登录一次就会自动记住。"
                        },
                        color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
                    )
                    if (loggedIn) {
                        Spacer(Modifier.height(8.dp))
                        CTA("保存当前账户", cs.primary, cs.onPrimary) {
                            val ck = CfCookieBridge.savedLogin()
                            if (ck.isBlank()) {
                                say("没有可保存的登录态")
                            } else {
                                val email = com.han1me.X99.setting("account.email", "")
                                    .ifBlank { CachedAccount.info?.email.orEmpty() }
                                    .ifBlank { "account@" + System.currentTimeMillis() }
                                AccountStore.save(
                                    ctx,
                                    AccountStore.Account(
                                        email = email,
                                        uid = com.han1me.X99.setting("account.uid", ""),
                                        name = com.han1me.X99.setting("account.name", "")
                                            .ifBlank { CachedAccount.userName },
                                        avatar = CachedAccount.avatarUrl,
                                        cookie = ck,
                                    ),
                                )
                                say("已保存账户 " + email)
                                tick++
                            }
                        }
                    }
                } else {
                    accounts.forEach { acc ->
                        val active = acc.email.equals(activeEmail, true)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) cs.primaryContainer else cs.surfaceContainerHigh)
                                .clickable {
                                    if (!active) {
                                        AccountStore.switchTo(ctx, acc.email)
                                        CachedAccount.clear()
                                        LoginState.notifyChanged()
                                        say("已切换到 " + acc.email + "，云端收藏/历史随 cookie 一起切换")
                                        tick++
                                    }
                                }
                                .padding(11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 首字母头像
                            Box(
                                Modifier.size(30.dp).clip(CircleShape)
                                    .background(cs.primary.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    acc.name.take(1).ifBlank { acc.email.take(1) }.ifBlank { "?" },
                                    color = cs.onSurface, fontSize = 13.sp,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    acc.name.ifBlank { acc.email },
                                    color = cs.onSurface, fontSize = 12.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    acc.email + if (acc.uid.isNotBlank()) " · @" + acc.uid else "",
                                    color = cs.onSurfaceVariant, fontSize = 10.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (active) {
                                Text("当前", color = cs.primary, fontSize = 10.sp)
                            }
                            Text(
                                "移除",
                                color = cs.error, fontSize = 10.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        AccountStore.remove(ctx, acc.email)
                                        if (active) {
                                            CachedAccount.clear()
                                            LoginState.notifyChanged()
                                        }
                                        say("已移除 " + acc.email)
                                        tick++
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                CTA("＋ 添加账户（网页登录）", cs.primary.copy(alpha = 0.16f), cs.primary) {
                    // 自己起登录页 —— 设置层是独立覆盖层，没有宿主可回调；
                    // 直接 startActivity 与用户页「登录/注册」走同一条路（同一个 Activity）。
                    val ok = runCatching {
                        val i = android.content.Intent(ctx, com.han1me.lab.LoginActivity::class.java)
                        i.putExtra(com.han1me.lab.LoginActivity.EXTRA_URL, "https://hanime1.me/login")
                        i.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        com.han1me.lab.LoginActivity.onFinished = { ck ->
                            if (ck.isNotEmpty()) tick++
                        }
                        ctx.startActivity(i)
                        true
                    }.getOrDefault(false)
                    if (!ok) say("无法打开登录页") else say("已打开登录页，登录完成后回来这里即可看到账户")
                    onAddAccount()
                }
            }
        }

        // ============ 备份与恢复 ============
        HplaySectionGroup("备份与恢复") {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "备份内容：登录凭据（cf_clearance + session）、多账户、账号信息、" +
                        "三次元收藏、cos 历史、下载任务、打卡记录、全部设置（含内置节点与线路）、外观。",
                    color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "不含已下载的视频文件（体积太大，可在「本地下载」里全盘扫描复原）。",
                    color = cs.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp,
                )
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CTA("导出备份到文件", cs.primary, cs.onPrimary, Modifier.weight(1f)) {
                        runCatching { saveLauncher.launch(HplayBackup.suggestedName()) }
                            .onFailure { say("打不开文件选择器：" + it.message) }
                    }
                    CTA("从文件恢复", cs.surfaceContainerHigh, cs.onSurface, Modifier.weight(1f)) {
                        runCatching { openLauncher.launch(arrayOf("application/json", "*/*")) }
                            .onFailure { say("打不开文件选择器：" + it.message) }
                    }
                }
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CTA("存到本机", cs.surfaceContainerHigh, cs.onSurface, Modifier.weight(1f)) {
                        val json = HplayBackup.export(ctx)
                        if (json == null) {
                            say("导出失败")
                        } else {
                            HplayBackup.saveToFile(ctx, json)
                            say("已存到本机（应用数据目录）")
                        }
                    }
                    CTA("从本机恢复", cs.surfaceContainerHigh, cs.onSurface, Modifier.weight(1f)) {
                        val json = HplayBackup.loadFromFile(ctx)
                        if (json == null) {
                            say("本机没有备份文件")
                        } else {
                            val n = HplayBackup.restore(ctx, json)
                            say(if (n >= 0) "已恢复 " + n + " 类数据" else "备份文件损坏")
                            CachedAccount.clear()
                            LoginState.notifyChanged()
                            tick++
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "兼容主线（正式版）导出的旧备份：favorites / settings / cookies / labNode / " +
                        "history / catalog / checkin / tasks 这些键都会被识别并回写。",
                    color = cs.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "注意：备份文件里包含你的登录凭据（等同账号在本机的登录态），请妥善保管。",
                    color = cs.error.copy(alpha = 0.86f), fontSize = 10.sp, lineHeight = 14.sp,
                )

                msg?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = cs.primary, fontSize = 10.5.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}

/** 统一按钮（避免三次重复的 Box + clip + background + clickable）。 */
@Composable
private fun CTA(
    text: String,
    bg: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 12.sp) }
}
