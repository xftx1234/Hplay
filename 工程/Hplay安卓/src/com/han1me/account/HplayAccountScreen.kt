package com.han1me.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.AccentRed
import com.han1me.CardColor
import com.han1me.CardColorSoft
import com.han1me.HplayOnSurface
import com.han1me.HplayErrorSoft
import com.han1me.HplaySuccess
import com.han1me.TextDim
import com.han1me.TextFaint
import com.han1me.ui.HplayLoadMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **账户与资料编辑页**（第 350 轮，照 Han1meViewer 的 AccountScreen 复刻）。
 *
 * 用户要求：「已登录后点击登录区域不再显示登录弹窗，新增资料编辑板块可同步 han 云端账户，
 * 具体实现和接线可完全参照 viewer 复刻；设定页面的账户切换器不受影响」。
 *
 * ## 参考实现的四个区块（AccountScreen.kt:218-486）
 *
 * | 区块 | 内容 |
 * |---|---|
 * | 头像卡 | 108dp 圆形头像 + 右下小按钮换图、昵称、@id 徽章、「N 位订阅者 · M 部影片」、加入时间 |
 * | 资料编辑 | 昵称输入框 + 邮箱输入框（站点只读时禁改）+ 「更新个人资料」 |
 * | 更改密码 | 旧 / 新 / 确认三个密码框 + 忘记密码链接 |
 * | 退出登录 | 红框按钮 |
 *
 * 本页把四处全部做出来，网络层在 [HplayAccount]。换头像用的是本机已有的挑图能力
 * （HplaySlots.pickImage），挑完压成 JPEG 再上传 —— 参考实现用第三方裁剪库（cn.mucute），
 * 本工程没有它，就退一步：原图直接按 512px 缩放后上传（站点自己会再裁一次圆形）。
 *
 * @param userId 站点用户 id；空白时按「未登录」渲染，只给一个「去登录」
 * @param onClose 关闭本页
 * @param onLogout 退出登录（交给外层走既有清 Cookie 链路）
 * @param onNeedLogin 未登录时点登录
 */
@Composable
fun HplayAccountScreen(
    userId: String,
    onClose: () -> Unit,
    onLogout: () -> Unit,
    onNeedLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<HplayAccount.Info?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 表单草稿：进页面时用云端值初始化一次；保存成功后重新加载会再同步。
    var name by remember { mutableStateOf("") }
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var newPw2 by remember { mutableStateOf("") }

    fun load() {
        if (userId.isBlank()) { loading = false; return }
        loading = true; err = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { HplayAccount.fetch(userId) } }
            loading = false
            r.onSuccess { info = it; name = it.username }
             .onFailure { err = it.message ?: "读取失败" }
        }
    }
    LaunchedEffect(userId) { load() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // ---------- 标题行 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp))
                    .background(AccentRed).clickable { onClose() },
            ) { Text("←", color = Color.White, fontSize = 16.sp) }
            Spacer(Modifier.width(10.dp))
            Text("我的账户", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                if (loading) "读取中…" else "刷新", color = TextDim, fontSize = 11.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(CardColorSoft)
                    .clickable { if (!loading) load() }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        if (userId.isBlank()) {
            Card {
                Text("现在还没有登录", color = HplayOnSurface, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "登录后这里会显示头像、昵称、订阅数与加入时间，并可直接改昵称 / 换头像 / 改密码。",
                    color = TextFaint, fontSize = 11.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "去登录", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(AccentRed).clickable { onNeedLogin() }.padding(vertical = 9.dp),
                )
            }
            return@Column
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                HplayLoadMark(markSize = 34.dp)
            }
            return@Column
        }
        err?.let {
            Card {
                Text("读取账户失败", color = HplayErrorSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(it, color = TextDim, fontSize = 11.sp, lineHeight = 16.sp)
            }
            return@Column
        }
        val inf = info ?: return@Column

        // ---------- ① 头像卡 ----------
        Card {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(CardColorSoft)) {
                    if (inf.avatarUrl.isNotBlank()) {
                        com.han1me.web.HplayThumb(
                            url = inf.avatarUrl,
                            contentDescription = inf.username,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text(
                            "👤", fontSize = 26.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        inf.username.ifBlank { "（无昵称）" },
                        color = HplayOnSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "@" + inf.userId,
                        color = TextFaint, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(9.dp))
                            .background(AccentRed.copy(alpha = 0.16f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        inf.subscriberCount.toString() + " 位订阅者 · " + inf.videoCount + " 部影片",
                        color = TextDim, fontSize = 11.sp,
                    )
                    if (inf.joinedLabel.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(inf.joinedLabel, color = TextFaint, fontSize = 10.sp)
                    }
                }
                Text(
                    if (busy) "…" else "换头像",
                    color = Color.White, fontSize = 11.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(AccentRed)
                        .clickable(enabled = !busy) {
                            if (!com.han1me.HplaySlots.pickSupported) {
                                msg = "这台设备不支持挑图（HplaySlots.pickSupported=false）"
                                return@clickable
                            }
                            com.han1me.HplaySlots.pickImage("account.avatar.src") { path ->
                                if (path == null) return@pickImage
                                busy = true; msg = null
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        runCatching {
                                            HplayAccount.updateAvatar(inf, jpegOf(path))
                                        }
                                    }
                                    busy = false
                                    r.onSuccess { msg = "头像已更新"; load() }
                                     .onFailure { msg = "头像更新失败：" + (it.message ?: "未知") }
                                }
                            }
                        }.padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        }

        // ---------- ② 资料编辑 ----------
        Spacer(Modifier.height(10.dp))
        Card {
            Text("编辑个人资料", color = HplayOnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            FieldLabel("用户名")
            InputBox(name, { name = it })
            Spacer(Modifier.height(6.dp))
            FieldLabel(if (inf.emailEditable) "电邮" else "电邮（站点只读）")
            if (inf.email.isBlank()) {
                // 站点在账户页不回邮箱时（改版后常见）如实说明，不摆一个空白框让人以为坏了。
                Text(
                    "站点没有返回邮箱（改版后该字段可能不再公开）",
                    color = TextFaint, fontSize = 10.5.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            } else {
                InputBox(inf.email, {}, enabled = false)
            }
            Spacer(Modifier.height(10.dp))
            PrimaryBtn(
                text = if (busy) "提交中…" else "更新个人资料",
                enabled = !busy && name.isNotBlank(),
            ) {
                busy = true; msg = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { HplayAccount.updateName(inf, name) }
                    }
                    busy = false
                    r.onSuccess { msg = "资料已更新"; load() }
                     .onFailure { msg = "更新失败：" + (it.message ?: "未知") }
                }
            }
        }

        // ---------- ③ 更改密码 ----------
        Spacer(Modifier.height(10.dp))
        Card {
            Text("更改密码", color = HplayOnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            FieldLabel("旧密码")
            InputBox(oldPw, { oldPw = it }, password = true)
            Spacer(Modifier.height(6.dp))
            FieldLabel("新密码")
            InputBox(newPw, { newPw = it }, password = true)
            Spacer(Modifier.height(6.dp))
            FieldLabel("确认新密码")
            InputBox(newPw2, { newPw2 = it }, password = true)
            Spacer(Modifier.height(6.dp))
            Text(
                "忘记密码？到站点重置",
                color = AccentRed, fontSize = 11.sp,
                modifier = Modifier.clickable {
                    com.han1me.HplayEnv.openExternal(com.javrocket.hanime.HanimeClient.BASE + "/password/reset")
                }.padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            PrimaryBtn(
                text = if (busy) "提交中…" else "更改密码",
                enabled = !busy && oldPw.isNotEmpty() && newPw.isNotEmpty() && newPw2.isNotEmpty(),
            ) {
                if (newPw != newPw2) { msg = "两次输入的新密码不一致"; return@PrimaryBtn }
                busy = true; msg = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { HplayAccount.updatePassword(inf, oldPw, newPw, newPw2) }
                    }
                    busy = false
                    r.onSuccess {
                        msg = "密码已更改"; oldPw = ""; newPw = ""; newPw2 = ""
                    }.onFailure { msg = "更改失败：" + (it.message ?: "未知") }
                }
            }
        }

        // ---------- ④ 退出登录 ----------
        Spacer(Modifier.height(10.dp))
        Text(
            "退出登录", color = HplayErrorSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(CardColorSoft)
                .clickable { onLogout() }.padding(vertical = 11.dp),
        )

        msg?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                color = if (it.contains("失败") || it.contains("不一致") || it.contains("不支持"))
                    HplayErrorSoft else HplaySuccess,
                fontSize = 11.5.sp, lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(CardColor).padding(14.dp),
        content = content,
    )
}

@Composable
private fun FieldLabel(t: String) {
    Text(t, color = TextFaint, fontSize = 10.5.sp, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun InputBox(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    password: Boolean = false,
) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
            .background(CardColorSoft).padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        // ★ 第 350 轮真机修：密码框**不能**用「••••••」当占位符 ——
        // 用户看到六个点会以为里面已经有内容（甚至以为浏览器记过密码），
        // 于是不敢动、或直接点提交。占位符一律用人话提示。
        if (value.isEmpty() && enabled) {
            Text(
                if (password) "请输入" else "",
                color = TextFaint, fontSize = 12.5.sp,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            cursorBrush = SolidColor(AccentRed),
            textStyle = TextStyle(
                color = if (enabled) HplayOnSurface else TextDim,
                fontSize = 12.5.sp,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PrimaryBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) Color.White else TextDim,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (enabled) AccentRed else CardColorSoft)
            .clickable(enabled = enabled) { onClick() }.padding(vertical = 10.dp),
    )
}

/**
 * 把挑来的图片读出来压成 JPEG（上传用）。
 *
 * 参考实现用第三方裁剪库出 1:1 方形；本工程没有它，这里只做**等比缩到 512 长边**，
 * 站点侧接收后会自己按圆形裁一次（它的 playlist-avatar 就是圆形的）。
 */
private fun jpegOf(path: String): ByteArray {
    val bmp = android.graphics.BitmapFactory.decodeFile(path)
        ?: throw IllegalStateException("读不到这张图")
    val longSide = maxOf(bmp.width, bmp.height)
    val scaled = if (longSide > 512) {
        val k = 512f / longSide
        android.graphics.Bitmap.createScaledBitmap(
            bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true,
        )
    } else bmp
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
    return out.toByteArray()
}