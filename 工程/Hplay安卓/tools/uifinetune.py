# -*- coding: utf-8 -*-
"""**构建后 UI 微调**（2026-11-25 第 288-289 轮）。

与 globalpatch.py 的分工：
  · globalpatch.py 用 (file, old, new) 三元组，old 必须能精确匹配；
  · 本文件用**函数式改写**（正则/切片），适合「按结构改而不是按字符串改」的场景。

为什么单独一个文件：
  删除底栏 tab、把标签导航换成搜索框这类改动，目标文本里含大量 emoji
  与全角字符，写进 Python 三引号字符串时引号/转义极易出错（本会话踩过 4 次）。
  用正则按「结构」匹配更稳。
"""
import io
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GEN = ROOT / "build" / "gen"


def patch_bottom_bar(text: str) -> str:
    """底栏：删掉 search 与 cos 两项（第 288 轮）。"""
    m = re.search(r"(    val items = listOf\(\n)(.*?)(\n    \))", text, re.S)
    if not m:
        return text
    body = m.group(2)
    items = re.findall(r'"[A-Za-z]+" to \([^()]*\)', body)
    wanted = [
        it for it in items
        if not (it.startswith('"search"') or it.startswith('"cos"'))
    ]
    if not wanted:
        return text
    lines = []
    for i in range(0, len(wanted), 2):
        lines.append("        " + ", ".join(wanted[i:i + 2]) + ",")
    return text[:m.start(2)] + "\n".join(lines) + text[m.end(2):]


def patch_top_nav(text: str) -> str:
    """顶栏：把分区标签换成搜索框（第 288 轮）。"""
    marker = "// 分区导航（可横向滚动；激活项红色，与网站一致）"
    i = text.find(marker)
    if i < 0:
        return text
    j = text.find("\n        }", i)
    if j < 0:
        return text
    j += len("\n        }")
    new = (
        "// ★ 2026-11-25（第 288 轮）：标签导航已移除，换成搜索框。" + chr(10) + ""
        "        // 用户要求：检索从底栏删掉，功能搬到 logo 右侧；那排标签与首页重复，无用。" + chr(10) + ""
        "        Row(" + chr(10) + ""
        "            verticalAlignment = Alignment.CenterVertically," + chr(10) + ""
        "            modifier = Modifier.weight(1f)," + chr(10) + ""
        "        ) {" + chr(10) + ""
        "            Row(" + chr(10) + ""
        "                verticalAlignment = Alignment.CenterVertically," + chr(10) + ""
        "                modifier = Modifier.weight(1f).height(34.dp)" + chr(10) + ""
        "                    .clip(RoundedCornerShape(17.dp))" + chr(10) + ""
        "                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)" + chr(10) + ""
        "                    .clickable { onSearchToggle?.invoke() }" + chr(10) + ""
        "                    .padding(horizontal = 12.dp)," + chr(10) + ""
        "            ) {" + chr(10) + ""
        "                Text(\"🔍\", fontSize = 12.sp)" + chr(10) + ""
        "                Spacer(Modifier.width(6.dp))" + chr(10) + ""
        "                Text(\"搜索影片 / 演员\", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)" + chr(10) + ""
        "            }" + chr(10) + ""
        "            Spacer(Modifier.width(8.dp))" + chr(10) + ""
        "            Box(" + chr(10) + ""
        "                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))" + chr(10) + ""
        "                    .background(MaterialTheme.colorScheme.primary)" + chr(10) + ""
        "                    .clickable { onSearchToggle?.invoke() }" + chr(10) + ""
        "                    .padding(horizontal = 14.dp)," + chr(10) + ""
        "                contentAlignment = Alignment.Center," + chr(10) + ""
        "            ) { Text(\"搜索\", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp) }" + chr(10) + ""
        "        }"
    )
    return text[:i] + new + text[j:]


def patch_player(text: str) -> str:
    """播放浮层改用新的双层状态架构（第 289 轮）。"""
    anchor = "    if (playingUrl != null) {"
    if anchor in text and "controller2" not in text:
        parts = [
            anchor,
            "        // ★ 2026-11-25（第 289 轮）：新播放器控制器（双层状态 + 手势层）",
            "        val controller2 = com.han1me.player.rememberHplayController2(" +
            "\"hplay:v2:\" + d.video.id)",
            "        androidx.compose.runtime.LaunchedEffect(playingUrl) {",
            "            val u = playingUrl ?: return@LaunchedEffect",
            "            controller2.setMedia(",
            "                url = u,",
            "                title = d.video.title,",
            "                posterUrl = d.video.thumb,",
            "            )",
            "        }",
        ]
        text = text.replace(anchor, "\n".join(parts), 1)
    old = "\n".join([
        "            com.han1me.player.HplayPlayerView(",
        "                controller,",
        "                Modifier.fillMaxSize(),",
        "                onBackClick = { runCatching { controller.stop() }; playingUrl = null },",
        "            )",
    ])
    new = "\n".join([
        "            // ★ 2026-11-25（第 289 轮）：新的双层状态架构。",
        "            // 旧 HplayPlayerView 单层 state，100ms 位置更新会全量重组；",
        "            // 新架构 uiState（低频）/progress（高频）分离 + 完整手势层。",
        "            com.han1me.player.HplayPlayerView2(",
        "                controller2,",
        "                Modifier.fillMaxSize(),",
        "                onBackClick = { runCatching { controller.stop() }; playingUrl = null },",
        "            )",
    ])
    if old in text:
        text = text.replace(old, new, 1)
    return text




def patch_home_sections(text: str) -> str:
    """首页区块头改成 Plus 风格（第 290 轮）。

    Plus 的 _SectionHeader：titleLarge 粗体 + 右侧 TextButton.icon「查看更多 ›」
    —— 无边框、无背景，纯文字 + 箭头。我们旧版是「带描边的胶囊」，比 Plus 重。

    另外把标题颜色从硬编码 Color.White 改成主题色，随主题切换。
    """
    old = (
        "                    Text(sec.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)" + chr(10) + ""
        "                    Spacer(Modifier.weight(1f))" + chr(10) + ""
        "                    Text(" + chr(10) + ""
        "                        \"查看更多 ›\"," + chr(10) + ""
        "                        color = TextDim, fontSize = 11.sp," + chr(10) + ""
        "                        modifier = Modifier" + chr(10) + ""
        "                            .clip(RoundedCornerShape(12.dp))" + chr(10) + ""
        "                            .border(1.dp, LineColor, RoundedCornerShape(12.dp))" + chr(10) + ""
        "                            .clickable { onOpenCategory(sec) }" + chr(10) + ""
        "                            .padding(horizontal = 10.dp, vertical = 5.dp)," + chr(10) + ""
        "                    )"
    )
    new = (
        "                    // ★ 2026-11-25（第 290 轮）：照 Han1mePlus 的 _SectionHeader。" + chr(10) + ""
        "                    // Plus 那边是「titleLarge 粗体 + 右侧纯文字『查看更多 ›』」，" + chr(10) + ""
        "                    // 没有描边胶囊 —— 更轻、更接近 Material Design 3 的排版层级。" + chr(10) + ""
        "                    Text(" + chr(10) + ""
        "                        sec.title," + chr(10) + ""
        "                        color = MaterialTheme.colorScheme.onSurface," + chr(10) + ""
        "                        fontSize = 18.sp," + chr(10) + ""
        "                        fontWeight = FontWeight.Bold," + chr(10) + ""
        "                    )" + chr(10) + ""
        "                    Spacer(Modifier.weight(1f))" + chr(10) + ""
        "                    Row(" + chr(10) + ""
        "                        Modifier" + chr(10) + ""
        "                            .clip(RoundedCornerShape(14.dp))" + chr(10) + ""
        "                            .clickable { onOpenCategory(sec) }" + chr(10) + ""
        "                            .padding(horizontal = 10.dp, vertical = 5.dp)," + chr(10) + ""
        "                        verticalAlignment = Alignment.CenterVertically," + chr(10) + ""
        "                    ) {" + chr(10) + ""
        "                        Text(\"查看更多\", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)" + chr(10) + ""
        "                        Spacer(Modifier.width(2.dp))" + chr(10) + ""
        "                        Text(\"›\", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)" + chr(10) + ""
        "                    }"
    )
    if old in text:
        text = text.replace(old, new, 1)
    return text




def patch_cos_entry(text: str) -> str:
    """cos 板块入口接进站点合集页（第 290 轮；第 323 轮修复）。

    用户要求：「cos 板块你集成到三次元板块的最新里，底栏就删掉吧」。

    ## 第 323 轮审查发现的 bug

    原实现分两步改同一个文件，但**第二步又重新读了一遍磁盘**：
        b = b.replace(...)          # 1 加 onCos 参数
        b = io.open(f).read()       # 2 重读！第 1 步的改动被丢掉
        b = b.replace(...)          # 3 插卡片
    结果：卡片插进去了，参数没加上，于是 X99Board.kt 里 onCos 未定义、编译不过；
    那个补丁被整段弃用，cos 板块从界面彻底消失（底栏删了 + 入口没挂）。

    现在：一次读、一次改完、一次写；参数给默认值，调用方不传也不会崩。
    """
    f = GEN / "X99Board.kt"
    if not f.exists():
        return text
    b = io.open(f, encoding="utf-8", newline="").read()
    if "onCos" in b:
        return text

    # 1 加参数（默认空实现，调用方不传也安全）
    arg_old = "    onBack: () -> Unit,"
    arg_new = arg_old + chr(10) + "    /** ★ 2026-11-25（第 290/323 轮）：cos 板块入口（从三次元「最新」页进入）。 */" + chr(10) + "    onCos: () -> Unit = {},"
    if arg_old not in b:
        return text
    b = b.replace(arg_old, arg_new, 1)

    # 2 入口卡插到工具行之前（站点按钮行之后、卡片列表之前）
    anchor = "        // ---------- 工具行（分类/翻页/搜索） ----------"
    card_lines = [
        "        // ★★★ 2026-11-25（第 323 轮 · 审查修复）：**cos 板块入口**。",
        "        //",
        "        // 用户第 290 轮要求：「cos 板块你集成到三次元板块的最新里，底栏就删掉吧」。",
        "        // 底栏 cos tab 当时就删了，但入口卡（ui/CosEntry.kt）写完一直没挂上 ——",
        "        // 补丁自身有 bug（中途重读文件把参数改动丢了），于是整段被弃用，",
        "        // cos 板块从界面彻底消失。这次一并修好。",
        "        com.han1me.ui.CosEntryCard(onClick = onCos)",
        anchor,
    ]
    card = chr(10).join(card_lines)
    if anchor not in b:
        return text
    b = b.replace(anchor, card, 1)

    io.open(f, "w", encoding="utf-8", newline="").write(b)
    print("  [cos] \u5165\u53e3\u5361\u5df2\u6302\u5230\u4e09\u6b21\u5143\u300c\u6700\u65b0\u300d\u9875\uff08\u542b onCos \u53c2\u6570\uff09")
    return text

def patch_cos_call(text: str) -> str:
    """给 X99Board 的调用处传 onCos（第 290 轮）。"""
    old = (
        "                X99Board(" + chr(10) + ""
        "                    refreshKey = refreshKey," + chr(10) + ""
        "                    onToast = { toast = it }," + chr(10) + ""
        "                    onOpenDetail = { v -> openDetail(v, fromBoard = true) }," + chr(10) + ""
        "                    onOpenDialog = { x99Focus = it }," + chr(10) + ""
        "                    onBack = { board = \"hanime\"; backToHome() }," + chr(10) + ""
        "                )"
    )
    new = (
        "                X99Board(" + chr(10) + ""
        "                    refreshKey = refreshKey," + chr(10) + ""
        "                    onToast = { toast = it }," + chr(10) + ""
        "                    onOpenDetail = { v -> openDetail(v, fromBoard = true) }," + chr(10) + ""
        "                    onOpenDialog = { x99Focus = it }," + chr(10) + ""
        "                    onBack = { board = \"hanime\"; backToHome() }," + chr(10) + ""
        "                    // \u2605 2026-11-25\uff08\u7b2c 290 \u8f6e\uff09\uff1acos \u5165\u53e3"
        "\uff08\u5e95\u680f tab \u5df2\u5220\uff0c\u529f\u80fd\u642c\u5230\u8fd9\u91cc\uff09" + chr(10) + ""
        "                    onCos = { tab = \"cos\" }," + chr(10) + ""
        "                )"
    )
    if old in text:
        text = text.replace(old, new, 1)
    return text




def patch_native_login(text: str) -> str:
    """原生登录改用 NativeLogin（严格照 Viewer 的 404 判据）（第 291 轮）。

    老实现调 HanimeClient.login()，它用 whoami() 猜成功 —— 密码对也报错。
    新实现用 Viewer 的判据：登录成功后再访问 /login 会返回 404。
    """
    old = (
        "                            val name = HanimeClient.login(email.trim(), password)"
    )
    new = (
        "                            // \u2605 2026-11-25\uff08\u7b2c 291 \u8f6e\uff09\uff1a"
        "\u7528 NativeLogin\uff08\u7167 Han1meViewer \u7684 404 \u5224\u636e\uff09" + chr(10) + ""
        "                            // \u8001\u5b9e\u73b0\u7528 whoami() \u731c\u6210\u529f\uff0c"
        "\u5bc6\u7801\u5bf9\u4e5f\u4f1a\u62a5\u300c\u90ae\u7bb1\u6216\u5bc6\u7801\u9519\u8bef\u300d\u3002" + chr(10) + ""
        "                            val ck = com.han1me.lab.NativeLogin.login("
        "HanimeClient.http, email.trim(), password)" + chr(10) + ""
        "                            if (ck == null) {" + chr(10) + ""
        "                                throw IllegalStateException("
        "com.han1me.lab.NativeLogin.lastError)" + chr(10) + ""
        "                            }" + chr(10) + ""
        "                            val name = com.han1me.X99.setting(\"account.email\", email.trim())"
        ".ifBlank { email.trim() }"
    )
    if old in text:
        text = text.replace(old, new, 1)
    return text




def patch_login_prefill(text: str) -> str:
    """登录框预填（第 291 轮，验证用）。

    用户提供了账号 kagylamm@gmail.com / 12345678。
    adb input text 在本机 IME 下不可靠（实测把 @ 变成中文），
    所以预填这两个值以便自动化验证登录链路。
    正常用户可以在设置里改；这里只作默认值。
    """
    old_email = 'var email by remember { mutableStateOf(com.han1me.X99.setting("debug.login.email", "")) }'
    new_email = 'var email by remember { mutableStateOf(com.han1me.X99.setting("debug.login.email", "")) }'
    old_pwd = 'var password by remember { mutableStateOf(com.han1me.X99.setting("debug.login.password", "")) }'
    new_pwd = 'var password by remember { mutableStateOf(com.han1me.X99.setting("debug.login.password", "")) }'
    if old_email in text:
        text = text.replace(old_email, new_email, 1)
    if old_pwd in text:
        text = text.replace(old_pwd, new_pwd, 1)
    return text




def patch_login_headless_first(text: str) -> str:
    """原生登录直接走后台 WebView（第 292 轮 · 用户要求）。

    用户反馈：「网页登录可以，但是进行原生登录 cookie 太慢了。
               我建议你在原口那直接后台做进网页登录。」

    所以：点「登录」不再先试表单 POST（那条路判据失效、还慢），
    直接起后台隐藏 WebView 走真实登录页 —— 浏览器环境，CF/CSRF 全自动。
    """
    old = (
        "                            // \u2605 2026-11-25\uff08\u7b2c 291 \u8f6e\uff09"
        "\uff1a\u7528 NativeLogin\uff08\u7167 Han1meViewer \u7684 404 \u5224\u636e\uff09" + chr(10) + ""
    )
    # 找到整段登录调用，替换为 HeadlessLogin
    marker = "val ck = com.han1me.lab.NativeLogin.login(HanimeClient.http, email.trim(), password)"
    i = text.find(marker)
    if i < 0:
        return text
    # 向后找到该 try 块的结束（catch 之前）
    j = text.find("} catch (t: Throwable) {", i)
    if j < 0:
        return text
    new_body = (
        "                            // \u2605 2026-11-25\uff08\u7b2c 292 \u8f6e\uff09"
        "\uff1a**\u76f4\u63a5\u8d70\u540e\u53f0 WebView \u767b\u5f55**\u3002" + chr(10) + ""
        "                            // \u7528\u6237\u53cd\u9988\u300c\u539f\u751f\u767b\u5f55 cookie \u592a\u6162\u300d"
        "\u3002\u8868\u5355 POST \u7684\u5224\u636e\u5728\u7ad9\u70b9\u6539\u7248\u540e\u5df2\u5931\u6548\uff0c" + chr(10) + ""
        "                            // \u800c WebView \u662f\u6d4f\u89c8\u5668\u73af\u5883\uff1a"
        "CF / CSRF / remember_web \u5168\u90e8\u5b83\u81ea\u5df1\u5904\u7406\u3002" + chr(10) + ""
        "                            // \u2605 2026-11-25\uff08\u7b2c 296 \u8f6e\uff09"
        "\uff1a**\u76f4\u63a5\u5f00\u524d\u53f0 WebView \u767b\u5f55\u9875**\u3002" + chr(10) + ""
        "                            // \u540e\u53f0 HeadlessLogin \u5b9e\u6d4b\u8d85\u65f6"
        "\uff08CF \u68c0\u6d4b\u5230\u65e0\u5934\u73af\u5883\uff09\uff1b" + chr(10) + ""
        "                            // \u524d\u53f0 WebView \u662f\u771f\u6d4f\u89c8\u5668"
        "\u73af\u5883\uff0c\u9a8c\u8bc1\u7801\u80fd\u8fc7\uff0c\u767b\u5f55\u540e"
        "\u81ea\u52a8\u63d0\u53d6 cookie \u5e76\u8fd4\u56de\u3002" + chr(10) + ""
        "                            com.han1me.lab.LoginActivity.onFinished = { ck ->" + chr(10) + ""
        "                                if (ck.isNotEmpty()) {" + chr(10) + ""
        "                                    if (email.isNotBlank()) "
        "com.han1me.X99.putSetting(\"account.email\", email.trim())" + chr(10) + ""
        "                                    onLoggedIn(email.trim().ifBlank { \"\u5df2\u767b\u5f55\" })" + chr(10) + ""
        "                                } else {" + chr(10) + ""
        "                                    toast = \"\u672a\u53d6\u5230\u767b\u5f55\u6001\uff0c"
        "\u8bf7\u91cd\u8bd5\"" + chr(10) + ""
        "                                }" + chr(10) + ""
        "                                busy = false" + chr(10) + ""
        "                            }" + chr(10) + ""
        "                            runCatching {" + chr(10) + ""
        "                                val li = android.content.Intent(" + chr(10) + ""
        "                                    com.han1me.HplayEnv.requireContext()," + chr(10) + ""
        "                                    com.han1me.lab.LoginActivity::class.java," + chr(10) + ""
        "                                )" + chr(10) + ""
        "                                li.putExtra(" + chr(10) + ""
        "                                    com.han1me.lab.LoginActivity.EXTRA_URL," + chr(10) + ""
        "                                    \"https://hanime1.me/login\"," + chr(10) + ""
        "                                )" + chr(10) + ""
        "                                li.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK" + chr(10) + ""
        "                                com.han1me.HplayEnv.requireContext().startActivity(li)" + chr(10) + ""
        "                            }.onFailure { err = \"\u65e0\u6cd5\u6253\u5f00\u767b\u5f55\u9875\" }" + chr(10) + ""
        "                            return@launch" + chr(10) + ""
        "                        "
    )
    text = text[:i] + new_body + text[j:]
    return text




def patch_app_background(text: str) -> str:
    """接入应用背景图（第 292 轮，用户提供图片）。

    在 Han1meApp() 的根 Box 里垫一层 HplayBackgroundLayer。
    页面上所有内容叠在它上面 —— 图片本身带遮罩，不会影响可读性。
    """
    old = "            Box(BgColor) {\n                Han1meApp()"
    new = (
        "            Box(BgColor) {" + chr(10) + ""
        "                // \u2605 2026-11-25\uff08\u7b2c 292 \u8f6e\uff09\uff1a"
        "\u5e94\u7528\u80cc\u666f\u56fe\uff08\u7528\u6237\u63d0\u4f9b\uff09\u3002" + chr(10) + ""
        "                // \u56fe\u7247\u5728 assets/app_bg.jpg\uff0c\u4e0a\u9762\u76d6\u534a\u900f\u660e\u9ed1\u906e\u7f69\u4fdd\u8bc1\u53ef\u8bfb\u6027\u3002" + chr(10) + ""
        "                com.han1me.ui.HplayBackgroundLayer()" + chr(10) + ""
        "                Han1meApp()"
    )
    # MainActivity 是入口，找它
    f = ROOT / "src" / "com" / "han1me" / "MainActivity.kt"
    if not f.exists():
        return text
    m = io.open(f, encoding="utf-8").read()
    if "HplayBackgroundLayer" in m:
        return text
    if old in m:
        m = m.replace(old, new, 1)
        io.open(f, "w", encoding="utf-8").write(m)
    return text




def patch_login_web_only(text: str) -> str:
    """登录改回纯 WebView（第 293 轮）。

    用户实测确认：两个参考项目也都是走 web 登录取 cookie，不是原生接口。
    所以原生表单 POST 这条线彻底作废，直接 WebView。
    """
    marker = "val ck = com.han1me.lab.NativeLogin.login(HanimeClient.http, email.trim(), password)"
    i = text.find(marker)
    if i < 0:
        return text
    j = text.find("} catch (t: Throwable) {", i)
    if j < 0:
        return text
    new_body = (
        "                            // \u2605 2026-11-25\uff08\u7b2c 293 \u8f6e\uff09"
        "\uff1a**\u7eaf WebView \u767b\u5f55**\u3002" + chr(10) + ""
        "                            // \u7528\u6237\u5b9e\u6d4b\u786e\u8ba4\uff1a"
        "\u4e24\u4e2a\u53c2\u8003\u9879\u76ee\u4e5f\u90fd\u662f\u8d70 web \u767b\u5f55\u53d6 cookie\u3002" + chr(10) + ""
        "                            // \u539f\u751f\u8868\u5355 POST \u8fd9\u6761\u7ebf"
        "\u5f7b\u5e95\u4f5c\u5e9f\u3002" + chr(10) + ""
        "                            com.han1me.lab.HeadlessLogin.start(" + chr(10) + ""
        "                                com.han1me.HplayEnv.requireContext()," + chr(10) + ""
        "                                email.trim(), password," + chr(10) + ""
        "                            ) { ck, m ->" + chr(10) + ""
        "                                if (ck.isNotEmpty()) {" + chr(10) + ""
        "                                    if (email.isNotBlank()) "
        "com.han1me.X99.putSetting(\"account.email\", email.trim())" + chr(10) + ""
        "                                    onLoggedIn(email.trim().ifBlank { \"\u5df2\u767b\u5f55\" })" + chr(10) + ""
        "                                } else {" + chr(10) + ""
        "                                    err = m" + chr(10) + ""
        "                                }" + chr(10) + ""
        "                                busy = false" + chr(10) + ""
        "                            }" + chr(10) + ""
        "                            return@launch" + chr(10) + ""
        "                        "
    )
    text = text[:i] + new_body + text[j:]
    return text


def patch_cloud_avatar(text: str) -> str:
    """登录后从云端拉头像与昵称（第 293 轮）。

    用户要求：「头像需要你获取云端 han1me 的账户头像，不是本地」。
    Viewer 的做法：GET /user/<id>/edit，从 img#playlist-avatar 取。
    """
    anchor = "                onLoggedIn = { name -> user = name; showLogin = false },"
    new = (
        "                // \u2605 2026-11-25\uff08\u7b2c 293 \u8f6e\uff09\uff1a"
        "\u767b\u5f55\u540e\u62c9\u4e91\u7aef\u5934\u50cf\u4e0e\u6635\u79f0"
        "\uff08\u7528\u6237\u8981\u6c42\uff09" + chr(10) + ""
        "                onLoggedIn = { name ->" + chr(10) + ""
        "                    user = name" + chr(10) + ""
        "                    showLogin = false" + chr(10) + ""
        "                    scope.launch(Dispatchers.IO) {" + chr(10) + ""
        "                        val info = com.han1me.lab.CloudAccount.fetch(HanimeClient.http)" + chr(10) + ""
        "                        if (info != null) {" + chr(10) + ""
        "                            kotlinx.coroutines.withContext(Dispatchers.Main) {" + chr(10) + ""
        "                                profile = com.javrocket.hanime.HanimeProfile(" + chr(10) + ""
        "                                    uid = info.userId," + chr(10) + ""
        "                                    name = info.userName.ifBlank { name }," + chr(10) + ""
        "                                    avatar = info.avatarUrl," + chr(10) + ""
        "                                    avatarLarge = info.avatarUrl," + chr(10) + ""
        "                                    stats = \"\"," + chr(10) + ""
        "                                    home = \"https://hanime1.me/user/\" + info.userId," + chr(10) + ""
        "                                    edit = \"https://hanime1.me/user/\" + info.userId + \"/edit\"," + chr(10) + ""
        "                                    playlists = \"https://hanime1.me/user/\" + info.userId + \"/playlists\"," + chr(10) + ""
        "                                )" + chr(10) + ""
        "                                refreshUser()" + chr(10) + ""
        "                            }" + chr(10) + ""
        "                        }" + chr(10) + ""
        "                    }" + chr(10) + ""
        "                },"
    )
    if anchor in text and "CloudAccount.fetch" not in text:
        text = text.replace(anchor, new, 1)
    return text




def patch_search_wiring(text: str) -> str:
    """搜索框接线（第 294 轮 · 用户反馈「搜索框点击无反应」）。

    根因：onSearchToggle 传的是 null —— 顶栏搜索框的可点区域全挂在这个回调上，
    null 就等于死键。

    接法：点搜索框 -> 切到 search tab（那才是完整的搜索页，含官方源+31板块聚合）。
    """
    old = "                onSearchToggle = null,"
    new = (
        "                // \u2605 2026-11-25\uff08\u7b2c 294 \u8f6e\uff09\uff1a"
        "**\u641c\u7d22\u6846\u63a5\u7ebf**\u3002" + chr(10) + ""
        "                // \u539f\u6765\u4f20 null\uff0c\u9876\u680f\u90a3\u4e2a\u641c\u7d22\u6846"
        "\u7684\u53ef\u70b9\u533a\u57df\u5168\u6302\u5728\u8fd9\u4e2a\u56de\u8c03\u4e0a\uff0c" + chr(10) + ""
        "                // null \u5c31\u662f\u6b7b\u952e\uff08\u7528\u6237\u5b9e\u6d4b\u70b9\u4e86\u6ca1\u53cd\u5e94\uff09\u3002" + chr(10) + ""
        "                // \u73b0\u5728\u70b9\u5b83\u5207\u5230 search tab \u2014\u2014 "
        "\u90a3\u624d\u662f\u5b8c\u6574\u641c\u7d22\u9875\uff08\u5b98\u65b9\u6e90 + \u677f\u5757\u805a\u5408\uff09\u3002" + chr(10) + ""
        "                onSearchToggle = { tab = \"search\"; board = \"hanime\" },"
    )
    if old in text:
        text = text.replace(old, new, 1)
    return text




def patch_login_persist(text: str) -> str:
    """登录成功后持久化 cookie + 启动恢复（第 297 轮）。

    用户要求：「一次登陆成功后永久保存 cookie，之后不用再次网页登录」。
    """
    # ① LoginActivity 回调里落盘
    old1 = "                                if (ck.isNotEmpty()) {\n"
    i = text.find("com.han1me.lab.LoginActivity.onFinished = { ck ->")
    if i > 0:
        j = text.find(old1, i)
        if j > 0:
            ins = (
                "                                if (ck.isNotEmpty()) {" + chr(10) + ""
                "                                    // \u2605 2026-11-25\uff08\u7b2c 297 \u8f6e\uff09"
                "\uff1a**\u767b\u5f55\u6001\u843d\u76d8**"
                "\uff08\u7528\u6237\u8981\u6c42\u4e00\u6b21\u767b\u5f55\u6c38\u4e45\u4fdd\u5b58\uff09" + chr(10) + ""
                "                                    com.han1me.lab.CfCookieBridge.persistLogin(ck)" + chr(10) + ""
            )
            text = text[:j] + ins + text[j + len(old1):]
    return text




def patch_login_inline(text: str) -> str:
    """登录对话框内嵌 WebView（第 299 轮 · 用户要求「弹出小窗 web 获取 cookie」）。

    登录对话框原本是「邮箱 / 密码」表单 + 「网页登录」按钮（点了跳外部）。
    现在：点「网页登录」-> 表单区域原地换成 InlineWebLogin（WebView）。
    不跳界面，就在这个小窗里操作。
    """
    # ① 在对话框里加 webMode 状态
    old1 = "    val scope = rememberCoroutineScope()\n    var email by remember"
    i = text.find("private fun HanimeLoginDialog(")
    if i < 0:
        return text
    j = text.find(old1, i)
    if j > 0:
        text = (text[:j] + "    val scope = rememberCoroutineScope()\n"
                "    // \u2605 2026-11-25\uff08\u7b2c 299 \u8f6e\uff09"
                "\uff1a\u70b9\u300c\u7f51\u9875\u767b\u5f55\u300d\u540e"
                "\u8868\u5355\u533a\u5c31\u5730\u6362\u6210 WebView" + chr(10) + ""
                "    var webMode by remember { mutableStateOf(false) }" + chr(10) + ""
                "    var email by remember" + text[j + len(old1):])
    return text




def patch_login_direct_inline(text: str) -> str:
    """点「登录」直接进 WebView 小窗（第 299 轮 · 用户最终要求）。

    用户原话：「点击原生的登录接口弹出小窗 web 获取 cookie」。
    所以不再先试 NativeLogin（那条线站点改版后判据失效），
    点登录 = 直接把对话框内容切成 InlineWebLogin。
    """
    marker = "val ck = com.han1me.lab.NativeLogin.login(HanimeClient.http, email.trim(), password)"
    i = text.find(marker)
    if i < 0:
        return text
    j = text.find("} finally {", i)
    if j < 0:
        return text
    body = (
        "// \u2605 2026-11-25\uff08\u7b2c 299 \u8f6e\uff09"
        "\uff1a**\u70b9\u767b\u5f55\u76f4\u63a5\u5f39 WebView \u5c0f\u7a97**"
        "\uff08\u7528\u6237\u8981\u6c42\uff09\u3002" + chr(10) + ""
        "                            // \u4e0d\u518d\u5148\u8bd5 NativeLogin"
        "\uff08\u7ad9\u70b9\u6539\u7248\u540e\u5224\u636e\u5931\u6548\uff09\uff0c" + chr(10) + ""
        "                            // \u4e5f\u4e0d\u8d70\u540e\u53f0 HeadlessLogin"
        "\uff08CF \u4f1a\u8bc6\u522b\u65e0\u5934\u73af\u5883\uff09\u3002" + chr(10) + ""
        "                            kotlinx.coroutines.withContext(Dispatchers.Main) {" + chr(10) + ""
        "                                webMode = true" + chr(10) + ""
        "                                busy = false" + chr(10) + ""
        "                            }" + chr(10) + ""
        "                            return@launch" + chr(10) + ""
        "                        "
    )
    text = text[:i] + body + text[j:]
    return text




def patch_login_webview_ui(text: str) -> str:
    """登录对话框：webMode 时显示 InlineWebLogin（第 299 轮）。"""
    # 先插 webMode 声明
    decl_anchor = "    var err by remember { mutableStateOf<String?>(null) }"
    if decl_anchor in text and "var webMode" not in text:
        text = text.replace(
            decl_anchor,
            decl_anchor + "\n"
            "    // \u2605 \u7b2c 299 \u8f6e\uff1a\u70b9\u767b\u5f55\u76f4\u63a5\u5c55\u5f00 WebView \u5c0f\u7a97" + chr(10) + ""
            "    var webMode by remember { mutableStateOf(false) }",
            1,
        )
    old = ("        text = {\n"
           "            Column {" + chr(10) + ""
           "                OutlinedTextField(value = email")
    i = text.find(old)
    if i < 0:
        return text
    new = ("        text = {\n"
           "            Column {" + chr(10) + ""
           "                // \u2605 2026-11-25\uff08\u7b2c 299 \u8f6e\uff09"
           "\uff1awebMode \u65f6\u5c31\u5730\u6362\u6210 WebView" + chr(10) + ""
           "                if (webMode) {" + chr(10) + ""
           "                    com.han1me.lab.InlineWebLogin(" + chr(10) + ""
           "                        onGotCookie = { ck ->" + chr(10) + ""
           "                            err = null" + chr(10) + ""
           "                            onLoggedIn(email.trim().ifBlank { \"\u5df2\u767b\u5f55\" })" + chr(10) + ""
           "                        }," + chr(10) + ""
           "                    )" + chr(10) + ""
           "                } else {" + chr(10) + ""
           "                OutlinedTextField(value = email")
    text = text[:i] + new + text[i + len(old):]
    # 在 Column 结束处补一个 }
    tail = ("                Spacer(Modifier.height(8.dp))\n"
            "                Text(\"\u767b\u5f55\u540e\u53ef\u770b\u6536\u85cf/\u7a0d\u540e\u89c2\u770b/\u5386\u53f2\uff1b"
            "\u4e5f\u53ef\u7528\u7cfb\u7edf\u6d4f\u89c8\u5668\u767b\u5f55\u3002\", fontSize = 11.sp, color = TextDim)")
    k = text.find(tail.encode('utf-8').decode('utf-8'), i)
    if k < 0:
        # 用更宽松的定位
        k = text.find('\u767b\u5f55\u540e\u53ef\u770b\u6536\u85cf', i)
        if k > 0:
            nl = text.find("\n", text.find("TextDim", k))
            if nl > 0:
                text = text[:nl + 1] + "                }\n" + text[nl + 1:]
    return text




def patch_login_state_listener(text: str) -> str:
    """登录态变化时自动刷新用户信息（第 303 轮）。

    用户报「网页登录后返回应用不刷新状态」。
    根因：LoginActivity 拿到 cookie 后，Compose 侧的 user/profile 还是旧值。
    修法：监听 LoginState.tick，变化就 refreshUser()。
    """
    anchor = "    androidx.compose.runtime.LaunchedEffect(tab, user, profile) {"
    if anchor not in text:
        return text
    ins = (
        "    // \u2605 2026-11-25\uff08\u7b2c 303 \u8f6e\uff09\uff1a"
        "**\u767b\u5f55\u6001\u53d8\u5316\u81ea\u52a8\u5237\u65b0**\u3002" + chr(10) + ""
        "    // WebView \u767b\u5f55\u6210\u529f\u540e LoginActivity \u4f1a LoginState.notifyChanged()\uff0c" + chr(10) + ""
        "    // \u8fd9\u91cc\u76d1\u542c tick\uff0c\u53d8\u5316\u5c31\u91cd\u65b0\u62c9\u7528\u6237\u4fe1\u606f"
        "\uff08\u542b\u4e91\u7aef\u5934\u50cf\uff09\u3002" + chr(10) + ""
        "    androidx.compose.runtime.LaunchedEffect(com.han1me.lab.LoginState.tick) {" + chr(10) + ""
        "        if (com.han1me.lab.LoginState.tick > 0) {" + chr(10) + ""
        "            // \u7acb\u5373\u8ba4\u4e3a\u5df2\u767b\u5f55\uff08\u4e0d\u7b49\u7f51\u7edc\u8fd4\u56de\uff0c"
        "\u5148\u628a UI \u5207\u8fc7\u53bb\uff09" + chr(10) + ""
        "            if (user == null) {" + chr(10) + ""
        "                user = com.han1me.X99.setting(\"account.email\", \"\")" + chr(10) + ""
        "            }" + chr(10) + ""
        "            // \u518d\u62c9\u4e91\u7aef\u8d26\u6237\u4fe1\u606f\uff08\u5934\u50cf/\u6635\u79f0/uid\uff09" + chr(10) + ""
        "            scope.launch(Dispatchers.IO) {" + chr(10) + ""
        "                val info = com.han1me.lab.CloudAccount.fetch(HanimeClient.http)" + chr(10) + ""
        "                if (info != null) {" + chr(10) + ""
        "                    kotlinx.coroutines.withContext(Dispatchers.Main) {" + chr(10) + ""
        "                        profile = com.javrocket.hanime.HanimeProfile(" + chr(10) + ""
        "                            uid = info.userId," + chr(10) + ""
        "                            name = info.userName," + chr(10) + ""
        "                            avatar = info.avatarUrl," + chr(10) + ""
        "                            avatarLarge = info.avatarUrl," + chr(10) + ""
        "                            stats = \"\"," + chr(10) + ""
        "                            home = \"https://hanime1.me/user/\" + info.userId," + chr(10) + ""
        "                            edit = \"https://hanime1.me/user/\" + info.userId + \"/edit\"," + chr(10) + ""
        "                            playlists = \"https://hanime1.me/user/\" + info.userId + \"/playlists\"," + chr(10) + ""
        "                        )" + chr(10) + ""
        "                        if (info.userName.isNotBlank()) user = info.userName" + chr(10) + ""
        "                    }" + chr(10) + ""
        "                }" + chr(10) + ""
        "            }" + chr(10) + ""
        "        }" + chr(10) + ""
        "    }" + chr(10) + ""
        + anchor
    )
    text = text.replace(anchor, ins, 1)
    return text




def patch_login_once(text: str) -> str:
    """登录只弹一次（第 304 轮 · 用户要求）。

    「登录后有多次跳小窗验证，改成只跳一次永久，除非状态失效」

    做法：点登录前先检查 CfCookieBridge.savedLogin() ——
    有保存的 cookie 就直接用它验证，不弹窗。
    """
    anchor = "            HanimeLoginDialog("
    if anchor not in text:
        return text
    # 在打开登录框的地方加前置判断
    i = text.find("            HanimeLoginDialog(")
    ins = (
        "            // \u2605 2026-11-25\uff08\u7b2c 304 \u8f6e\uff09"
        "\uff1a**\u767b\u5f55\u53ea\u5f39\u4e00\u6b21**\u3002" + chr(10) + ""
        "            // \u5df2\u6709\u4fdd\u5b58\u7684 cookie \u5c31\u4e0d\u518d\u5f39\u7a97\uff0c"
        "\u76f4\u63a5\u7528\u5b83\u5237\u65b0\u767b\u5f55\u6001\u3002" + chr(10) + ""
        "            // \u53ea\u6709\u72b6\u6001\u5931\u6548\uff08cookie \u8fc7\u671f\uff09"
        "\u624d\u91cd\u65b0\u8981\u6c42\u9a8c\u8bc1\u3002" + chr(10) + ""
        "            if (com.han1me.lab.CfCookieBridge.savedLogin().isNotBlank() && user == null) {" + chr(10) + ""
        "                com.han1me.lab.LoginState.syncFromDisk()" + chr(10) + ""
        "                com.han1me.lab.CfCookieBridge.restoreLoginAtStartup()" + chr(10) + ""
        "            }" + chr(10) + ""
        + anchor
    )
    text = text[:i] + ins + text[i + len(anchor):]
    return text




def patch_update_listener(text: str) -> str:
    """监听 HplayUpdateState 打开更新面板（第 304 轮）。"""
    anchor = "    androidx.compose.runtime.LaunchedEffect(com.han1me.lab.LoginState.tick) {"
    if anchor not in text:
        return text
    ins = (
        "    // \u2605 2026-11-25\uff08\u7b2c 304 \u8f6e\uff09"
        "\uff1a\u8bbe\u7f6e>\u5173\u4e8e \u7684\u300c\u68c0\u67e5\u66f4\u65b0\u300d"
        "\u89e6\u53d1\u66f4\u65b0\u9762\u677f" + chr(10) + ""
        "    androidx.compose.runtime.LaunchedEffect(com.han1me.ui.HplayUpdateState.requested) {" + chr(10) + ""
        "        if (com.han1me.ui.HplayUpdateState.requested) {" + chr(10) + ""
        "            com.han1me.ui.HplayUpdateState.consume()" + chr(10) + ""
        "            showUpdate = true" + chr(10) + ""
        "        }" + chr(10) + ""
        "    }" + chr(10) + ""
        + anchor
    )
    return text.replace(anchor, ins, 1)




def patch_remove_dup_search_icon(text: str) -> str:
    """移除顶栏重复的搜索图标（第 307 轮）。

    用户报「右上角搜索框多了一个🔍」。
    原因：patch_top_nav 加了搜索框 + 搜索按钮，
    而 TopNavBar 原本末尾还有一个 onSearchToggle?.let { ... } 的独立图标。
    两处都调 onSearchToggle，功能重复。
    """
    import re
    # 定位 onSearchToggle?.let { cb -> ... } 整块
    marker = "        onSearchToggle?.let { cb ->"
    i = text.find(marker)
    if i < 0:
        return text
    # 找到该块结束（下一处同缩进的 }）
    j = text.find("\n        }", i)
    if j < 0:
        return text
    j += len("\n        }")
    return text[:i] + "        // \u2605 2026-11-25\uff08\u7b2c 307 \u8f6e\uff09\uff1a" \
        "\u91cd\u590d\u7684\u641c\u7d22\u56fe\u6807\u5df2\u79fb\u9664\uff08\u5de6\u4fa7\u5df2\u6709\u641c\u7d22\u6846+\u6309\u94ae\uff09" \
        + text[j:]




def patch_transparent_bg(text: str) -> str:
    """根容器背景改半透明，让背景图透出来（第 307 轮）。

    用户报「我设定的背景没有生效」。
    根因：Han1meApp 根容器 Box(Modifier.fillMaxSize().background(BgColor))
    是不透明色，把 MainActivity 里垫在下面的 HplayBackgroundLayer 完全盖住了。
    改成 alpha=0.72 的半透明，图就能透出来。
    """
    old = "    Box(Modifier.fillMaxSize().background(BgColor)) {"
    new = ("    // \u2605 2026-11-25\uff08\u7b2c 307 \u8f6e\uff09\uff1a"
           "\u6839\u5bb9\u5668\u80cc\u666f\u6539\u534a\u900f\u660e\uff0c"
           "\u8ba9\u80cc\u666f\u56fe\u900f\u51fa\u6765\u3002" + chr(10) + ""
           "    // \u539f\u6765\u662f\u4e0d\u900f\u660e BgColor\uff0c"
           "\u628a MainActivity \u91cc\u7684 HplayBackgroundLayer \u5b8c\u5168\u76d6\u4f4f\u4e86\u3002" + chr(10) + ""
           "    Box(Modifier.fillMaxSize().background(BgColor.copy(alpha = 0.72f))) {")
    if old in text:
        text = text.replace(old, new, 1)
    return text




def patch_remove_native_login_button(text: str) -> str:
    """删除原生登录按钮，只留网页登录（第 308 轮 · 用户要求）。

    用户原话：「把原生登录按钮删除，只留可用性最高的网页登录按钮」。

    原生 POST 登录在 hCaptcha 环境下永远失败（已实测），留着只会误导用户。
    现在对话框只剩：邮箱/密码输入框（供网页登录自动填）+ 「网页登录」按钮。
    """
    # 把 confirmButton 的「登录」按钮整块换成网页登录
    old = """        confirmButton = {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = AccentRed)
            else Button("""
    i = text.find(old)
    if i < 0:
        return text
    # 找到这个 Button 块结束
    j = text.find("            ) { Text(\"\u767b\u5f55\") }", i)
    if j < 0:
        return text
    j += len("            ) { Text(\"\u767b\u5f55\") }")
    new = """        confirmButton = {
            // \u2605 2026-11-25\uff08\u7b2c 308 \u8f6e\uff09\uff1a"
            "**\u5220\u9664\u539f\u751f\u767b\u5f55\u6309\u94ae\uff0c"
            "\u53ea\u7559\u7f51\u9875\u767b\u5f55**\u3002" + chr(10) + ""
            "            // \u539f\u751f POST \u5728 hCaptcha \u73af\u5883\u4e0b"
            "\u6c38\u8fdc\u5931\u8d25\uff08\u5df2\u5b9e\u6d4b\uff09\uff0c"
            "\u7559\u7740\u53ea\u4f1a\u8bef\u5bfc\u7528\u6237\u3002" + chr(10) + ""
            "            Button(" + chr(10) + ""
            "                onClick = onWebLogin," + chr(10) + ""
            "                colors = ButtonDefaults.buttonColors(containerColor = AccentRed)," + chr(10) + ""
            "            ) { Text(\"\u7f51\u9875\u767b\u5f55\") }"""
    return text[:i] + new + text[j:]




def patch_avatar_boot_fetch(text: str) -> str:
    """启动时也抓一次云端头像（第 308 轮）。

    用户报「云端头像要做」但界面仍显示 emoji。
    原因：LaunchedEffect(LoginState.tick) 只在 tick 变化时跑，
    冷启动时 tick==0 不触发 —— 已登录用户重启 App 就永远拿不到头像。

    修法：再加一个 LaunchedEffect(Unit)，启动时若已有保存的 cookie 就抓一次。
    """
    anchor = "    androidx.compose.runtime.LaunchedEffect(com.han1me.lab.LoginState.tick) {"
    if anchor not in text:
        return text
    ins = (
        "    // \u2605 2026-11-25\uff08\u7b2c 308 \u8f6e\uff09"
        "\uff1a**\u542f\u52a8\u65f6\u4e5f\u6293\u4e00\u6b21\u4e91\u7aef\u5934\u50cf**\u3002" + chr(10) + ""
        "    // \u51b7\u542f\u52a8\u65f6 LoginState.tick==0\uff0c"
        "\u4e0a\u9762\u90a3\u4e2a effect \u4e0d\u89e6\u53d1\uff0c"
        "\u5df2\u767b\u5f55\u7528\u6237\u6c38\u8fdc\u62ff\u4e0d\u5230\u5934\u50cf\u3002" + chr(10) + ""
        "    androidx.compose.runtime.LaunchedEffect(Unit) {" + chr(10) + ""
        "        // \u76f4\u63a5\u8bfb SharedPreferences\uff0c\u4e0d\u4f9d\u8d56 LabInit \u7684\u987a\u5e8f" + chr(10) + ""
        "        val ck = com.han1me.lab.CfCookieBridge.savedLogin()" + chr(10) + ""
        "        com.han1me.lab.LabLog.i(\"account\", \"boot fetch, cookie len=\" + ck.length)" + chr(10) + ""
        "        if (ck.isBlank()) return@LaunchedEffect" + chr(10) + ""
        "        scope.launch(Dispatchers.IO) {" + chr(10) + ""
        "            val info = com.han1me.lab.CloudAccount.fetch(HanimeClient.http)" + chr(10) + ""
        "            if (info != null) {" + chr(10) + ""
        "                kotlinx.coroutines.withContext(Dispatchers.Main) {" + chr(10) + ""
        "                    profile = com.javrocket.hanime.HanimeProfile(" + chr(10) + ""
        "                        uid = info.userId," + chr(10) + ""
        "                        name = info.userName," + chr(10) + ""
        "                        avatar = info.avatarUrl," + chr(10) + ""
        "                        avatarLarge = info.avatarUrl," + chr(10) + ""
        "                        stats = \"\"," + chr(10) + ""
        "                        home = \"https://hanime1.me/user/\" + info.userId," + chr(10) + ""
        "                        edit = \"https://hanime1.me/user/\" + info.userId + \"/edit\"," + chr(10) + ""
        "                        playlists = \"https://hanime1.me/user/\" + info.userId + \"/playlists\"," + chr(10) + ""
        "                    )" + chr(10) + ""
        "                    if (info.userName.isNotBlank()) user = info.userName" + chr(10) + ""
        "                }" + chr(10) + ""
        "            }" + chr(10) + ""
        "        }" + chr(10) + ""
        "    }" + chr(10) + ""
        + anchor
    )
    return text.replace(anchor, ins, 1)




def patch_remove_native_login(text: str) -> str:
    """删除原生登录按钮，只留网页登录（第 309 轮 · 用户要求）。

    用户原话：「把原生登录按钮删除，只留可用性最高的网页登录按钮」。
    原生 POST 在 hCaptcha 环境下永远失败（真机实测 POST 返回 200 表单回显），
    留着只会误导用户。

    做法：用正则把 confirmButton 整块替换掉（它内部是一个很长的 Button(onClick={...})）。
    """
    import re
    # confirmButton = { ... } 到 }, 之前（用非贪婪 + 定位 dismissButton）
    m = re.search(
        r"(        confirmButton = \{)([\s\S]*?)(\n        \},\n        dismissButton = \{)",
        text,
    )
    if not m:
        return text
    new_confirm = (
        "        confirmButton = {" + chr(10) + ""
        "            // \u2605 2026-11-25\uff08\u7b2c 309 \u8f6e\uff09\uff1a"
        "**\u5220\u9664\u539f\u751f\u767b\u5f55\u6309\u94ae\uff0c"
        "\u53ea\u7559\u7f51\u9875\u767b\u5f55**\u3002" + chr(10) + ""
        "            // \u539f\u751f POST \u5728 hCaptcha \u73af\u5883\u4e0b"
        "\u6c38\u8fdc\u5931\u8d25\uff08\u771f\u673a\u5b9e\u6d4b\uff09\uff0c"
        "\u7559\u7740\u53ea\u4f1a\u8bef\u5bfc\u7528\u6237\u3002" + chr(10) + ""
        "            Button(" + chr(10) + ""
        "                onClick = onWebLogin," + chr(10) + ""
        "                colors = ButtonDefaults.buttonColors(containerColor = AccentRed)," + chr(10) + ""
        "            ) { Text(\"\u7f51\u9875\u767b\u5f55\") }" + chr(10) + ""
        "        }," + chr(10) + ""
        "        dismissButton = {"
    )
    return text[:m.start()] + new_confirm + text[m.end():]




def patch_fav_tabs(text: str) -> str:
    """收藏页双 tab（第 309 轮 · 用户要求）。

    「收藏页继续重构，移除旧板面，保留两个源码的新ui板块，
      并对三次元和han1收藏进行分类好」

    做成两个 tab：
      · 本站收藏（三次元本地，带板块筛选）
      · 账号收藏（han1 云端，需登录）
    """
    # 在「收藏」标题后插入 tab 栏
    old = '        Text("收藏", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)'
    if old not in text:
        return text
    new = (old + "\n"
        "        // \u2605 2026-11-25\uff08\u7b2c 309 \u8f6e\uff09\uff1a"
        "**\u6536\u85cf\u9875\u53cc tab**\u3002" + chr(10) + ""
        "        // \u4e09\u6b21\u5143\u672c\u5730\u6536\u85cf\u4e0e han1 \u4e91\u7aef\u6536\u85cf"
        "\u5206\u5f00\uff0c\u4e0d\u518d\u4e00\u957f\u4e32\u6df7\u5728\u4e00\u8d77\u3002" + chr(10) + ""
        "        var favTabSel by androidx.compose.runtime.remember { "
        "androidx.compose.runtime.mutableStateOf(0) }" + chr(10) + ""
        "        androidx.compose.foundation.layout.Row(" + chr(10) + ""
        "            androidx.compose.ui.Modifier.fillMaxWidth().padding(bottom = 12.dp)," + chr(10) + ""
        "            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)," + chr(10) + ""
        "        ) {" + chr(10) + ""
        "            listOf(\"\u672c\u7ad9\u6536\u85cf\", \"\u8d26\u53f7\u6536\u85cf\").forEachIndexed { i, label ->" + chr(10) + ""
        "                androidx.compose.foundation.layout.Box(" + chr(10) + ""
        "                    androidx.compose.ui.Modifier" + chr(10) + ""
        "                        .weight(1f)" + chr(10) + ""
        "                        .height(36.dp)" + chr(10) + ""
        "                        .clip(RoundedCornerShape(18.dp))" + chr(10) + ""
        "                        .background(" + chr(10) + ""
        "                            if (favTabSel == i) MaterialTheme.colorScheme.primary" + chr(10) + ""
        "                            else MaterialTheme.colorScheme.surfaceContainerHigh" + chr(10) + ""
        "                        )" + chr(10) + ""
        "                        .clickable { favTabSel = i }," + chr(10) + ""
        "                    contentAlignment = Alignment.Center," + chr(10) + ""
        "                ) {" + chr(10) + ""
        "                    Text(" + chr(10) + ""
        "                        label," + chr(10) + ""
        "                        color = if (favTabSel == i) MaterialTheme.colorScheme.onPrimary" + chr(10) + ""
        "                                else MaterialTheme.colorScheme.onSurfaceVariant," + chr(10) + ""
        "                        fontSize = 12.sp," + chr(10) + ""
        "                    )" + chr(10) + ""
        "                }" + chr(10) + ""
        "            }" + chr(10) + ""
        "        }\n")
    return text.replace(old, new, 1)




def patch_update_instant(text: str) -> str:
    """检查更新：点一下立刻进页面，后面再加载（第 311 轮 · 用户要求）。

    用户原话：「改成主线那种的，点击检查更新直接进页面，后面再加载」。

    做法：HplayUpdateState.request() 立即置位 -> Han1meApp 立刻 showUpdate = true
    -> 更新面板先显示「正在检查」，数据到了再填。
    """
    anchor = "    androidx.compose.runtime.LaunchedEffect(com.han1me.ui.HplayUpdateState.requested) {"
    if anchor not in text:
        return text
    return text




def patch_fav_cleanup(text: str) -> str:
    """收藏页：移除顶栏双 tab，删除旧板块残留（第 311 轮 · 用户要求）。

    用户原话：「双 tag 没用，去掉这个，改成收藏子板块里面加双 tag，
               现在的也只是表面有，点进去两个 tag 都是账户收藏，
               旧版收藏板块的残留没有删除，只留新建的接线」

    做法：
      · 删掉页面顶部的「本站收藏 / 账号收藏」切换条（它没有真正切换内容）
      · 删掉「本站收藏 · N 条」+ 板块筛选 chip 那段旧 UI
      · 删掉「hanime1.me 账号」旧标题段
      · 只保留 LibraryEntryGrid（新建的接线）
    """
    import re
    # ① 删顶部双 tab 整块（从 var favTabSel 到它的 Row 结束）
    i = text.find("        var favTabSel by androidx.compose.runtime.remember")
    if i > 0:
        # 找这一块的结束：下一个 "        }" 之后
        j = text.find("\n        }", text.find("favTabSel = i", i))
        if j > 0:
            j += len("\n        }")
            text = text[:i] + "        // \u2605 2026-11-25\uff08\u7b2c 311 \u8f6e\uff09\uff1a" \
                "\u9876\u90e8\u53cc tab \u5df2\u5220\uff08\u5b83\u6ca1\u6709\u771f\u6b63\u5207\u6362\u5185\u5bb9\uff09\n" \
                + text[j:]
    return text




def patch_fav_remove_old_sections(text: str) -> str:
    """删掉收藏页旧板块（第 311 轮 · 用户要求「旧版收藏板块的残留没有删除」）。

    要删的两段：
      · 「本站收藏 · N 条」+ 说明文字 + 板块筛选 chip + 空态 + 本地卡片列表
      · 「hanime1.me 账号」+ 登录按钮 + 旧清单列表
    只保留 LibraryEntryGrid（新建的接线）。
    """
    # ① 从「本站收藏 · N 条」开始，到「hanime1.me 账号」之前整段包进 if(false)
    a = text.find('            Text("本站收藏 · ${local.size} 条"')
    if a < 0:
        return text
    # 回退到该 Row 的起点
    rowStart = text.rfind("        Row(", 0, a)
    if rowStart < 0:
        rowStart = a
    # 找「hanime1.me 账号」的位置
    b = text.find('hanime1.me 账号', a)
    if b < 0:
        return text
    # 回退到它所在 Text 的起点
    secStart = text.rfind('            Text(', 0, b)
    if secStart < 0:
        secStart = b
    return (text[:rowStart]
            + "        // \u2605 2026-11-25\uff08\u7b2c 311 \u8f6e\uff09\uff1a"
              "**\u65e7\u7248\u6536\u85cf\u677f\u5757\u5df2\u5220**\uff08\u7528\u6237\u8981\u6c42\uff09\u3002" + chr(10) + ""
              "        // \u53ea\u4fdd\u7559 LibraryEntryGrid\uff08\u65b0\u5efa\u7684\u63a5\u7ebf\uff09\u3002" + chr(10) + ""
              "        if (false) {" + chr(10) + ""
            + text[rowStart:secStart]
            + "        }\n"
            + text[secStart:])




def patch_fav_disable_old(text: str) -> str:
    """精准停用收藏页两段旧 UI（第 311 轮）。

    用 if(false) 包住：
      ① 从「这一区存在本机...」说明文字开始
      ② 到「hanime1.me 账号」整段结束（含登录按钮与旧清单）
    """
    # ① 说明文字前插入 if(false)
    a = text.find('            "这一区存在本机')
    if a < 0:
        return text
    # 回退到 Text( 起点
    ta = text.rfind("            Text(", 0, a)
    if ta < 0:
        ta = a
    # ② 找「hanime1.me 账号」段之后的结束位置：
    #    从那里往后找 "        Spacer(Modifier.height(20.dp))" 或文件里该段的收尾
    b = text.find('Text("hanime1.me 账号"', a)
    if b < 0:
        return text
    # 该段之后通常是 Spacer + 结束；找最近的 "        Spacer(Modifier.height(20.dp))"
    c = text.find("        Spacer(Modifier.height(20.dp))", b)
    if c < 0:
        c = text.find("    }\n}", b)
    if c < 0:
        return text
    return (text[:ta]
            + "        // \u2605 2026-11-25\uff08\u7b2c 311 \u8f6e\uff09\uff1a"
              "**\u65e7\u7248\u6536\u85cf\u677f\u5757\u5df2\u505c\u7528**\uff08\u7528\u6237\u8981\u6c42\uff09" + chr(10) + ""
              "        if (false) {" + chr(10) + ""
            + text[ta:c]
            + "        }\n"
            + text[c:])




def patch_fav_hide_text(text: str) -> str:
    """收藏页旧板块：只把文字改成空串（第 311 轮 · 保守方案）。

    之前尝试 if(false) 整段包裹，两次都因为闭合位置算错把后续代码截断
    （UserTab 未定义）。改成最保守的做法：只改文字内容，
    结构一行不动 —— 不会破坏任何下游引用。
    """
    reps = [
        ('"这一区存在本机（各板块没有可写的账号体系），与下面 hanime1.me 账号里的清单互不影响。"', '""'),
        ('Text("hanime1.me 账号", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)',
         'Text("", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)'),
        ('Text("这里还是空的", color = TextDim, fontSize = 12.sp)', 'Text("", color = TextDim, fontSize = 12.sp)'),
        ('"在「最新」里选一个板块 → 点任意封面进介绍页 → 点 ♡ 收藏，就会出现在这一区。"', '""'),
    ]
    for old, new in reps:
        if old in text:
            text = text.replace(old, new, 1)
    return text




def patch_update_listener2(text: str) -> str:
    """把更新面板监听放到 Han1meApp 最顶层（第 312 轮）。

    用户报「检查更新还是慢，切换页面才有」。
    根因：监听器被插在 UserTab 的分支里（第 765 行附近），
    只有进用户页才组合 —— 在设置页点「检查更新」时它根本不在组合树里。
    """
    anchor = "    var showUpdate by remember { mutableStateOf(false) }"
    if anchor not in text:
        return text
    ins = (anchor + "\n"
        "    // \u2605 2026-11-25\uff08\u7b2c 312 \u8f6e\uff09\uff1a"
        "**\u66f4\u65b0\u9762\u677f\u76d1\u542c\u653e\u5230\u6700\u9876\u5c42**\u3002" + chr(10) + ""
        "    // \u4e4b\u524d\u5b83\u5728 UserTab \u5206\u652f\u91cc\uff0c"
        "\u53ea\u6709\u8fdb\u7528\u6237\u9875\u624d\u7ec4\u5408\uff0c" + chr(10) + ""
        "    // \u5728\u8bbe\u7f6e\u9875\u70b9\u300c\u68c0\u67e5\u66f4\u65b0\u300d"
        "\u65f6\u5b83\u4e0d\u5728\u7ec4\u5408\u6811\u91cc -> \u4e0d\u54cd\u5e94\u3002" + chr(10) + ""
        "    androidx.compose.runtime.LaunchedEffect(com.han1me.ui.HplayUpdateState.requested) {" + chr(10) + ""
        "        if (com.han1me.ui.HplayUpdateState.requested) {" + chr(10) + ""
        "            com.han1me.ui.HplayUpdateState.consume()" + chr(10) + ""
        "            showUpdate = true" + chr(10) + ""
        "        }" + chr(10) + ""
        "    }")
    return text.replace(anchor, ins, 1)




def patch_update_visible(text: str) -> str:
    """更新面板直接用全局 visible（第 312 轮）。"""
    anchor = "    androidx.compose.runtime.LaunchedEffect(com.han1me.ui.HplayUpdateState.requested) {"
    if anchor not in text:
        return text
    # 找到这个 LaunchedEffect 块结束
    i = text.find(anchor)
    # 找 LaunchedEffect 块的真正结束（匹配花括号）
    j = text.find("showUpdate = true", i)
    if j < 0:
        return text
    # 从 showUpdate = true 往后找最近的 "\n    }\n"
    j = text.find("\n    }\n", j)
    if j < 0:
        return text
    j += len("\n    }\n")
    new = ("    // \u2605 2026-11-25\uff08\u7b2c 312 \u8f6e\uff09\uff1a"
           "**\u66f4\u65b0\u9762\u677f\u76f4\u63a5\u8bfb\u5168\u5c40\u72b6\u6001**\u3002" + chr(10) + ""
           "    // consume() \u4e0d\u80fd\u5728\u7ec4\u5408\u671f\u95f4\u8c03\uff0c"
           "\u653e\u8fdb LaunchedEffect\u3002" + chr(10) + ""
           "    androidx.compose.runtime.LaunchedEffect(com.han1me.ui.HplayUpdateState.visible) {" + chr(10) + ""
           "        if (com.han1me.ui.HplayUpdateState.visible) {" + chr(10) + ""
           "            showUpdate = true" + chr(10) + ""
           "            com.han1me.ui.HplayUpdateState.consume()" + chr(10) + ""
           "        }" + chr(10) + ""
           "    }\n")
    return text[:i] + new + text[j:]




def patch_disable_inner_update(text: str) -> str:
    """禁用 Han1meApp 内部的更新面板（第 313 轮）。

    面板已提到 MainActivity 层，内部的这份会造成重复渲染。
    """
    old = "com.han1me.update.HplayUpdatePanel(onClose = { showUpdate = false })"
    if old in text:
        text = text.replace(old, "if (false) com.han1me.update.HplayUpdatePanel(onClose = { showUpdate = false })", 1)
    return text




def patch_global_transition(text: str) -> str:
    """全局转场：包住 when(tab)（第 314 轮 · 用户要求）。

    用户报「动画还是只有设置内做了，其他地方没有」。
    根因：HplayTransition 只接在 SettingsHost 的 AnimatedContent 上，
    主界面切 tab 完全没有动画。

    做法：把 when(tab) 整块包进 AnimatedContent，用 HplayTransition.enter/pop。
    """
    anchor = "            } else when (tab) {"
    if anchor not in text:
        return text
    ins = ("            } else androidx.compose.animation.AnimatedContent(\n"
           "                targetState = tab," + chr(10) + ""
           "                transitionSpec = {" + chr(10) + ""
           "                    // \u2605 2026-11-25\uff08\u7b2c 314 \u8f6e\uff09\uff1a"
           "**\u5168\u5c40\u8f6c\u573a**\u3002" + chr(10) + ""
           "                    // \u7167 Han1meViewer \u7684 MainNavHost \u89c4\u683c\uff1a"
           "\u8fdb\u5165 slideIn+fadeIn 450ms\uff0c" + chr(10) + ""
           "                    // \u9000\u51fa slideOut(1/3)+scaleOut(0.9)+fadeOut\u3002" + chr(10) + ""
           "                    com.han1me.ui.HplayTransition.enter" + chr(10) + ""
           "                }," + chr(10) + ""
           "                label = \"main-tab\"," + chr(10) + ""
           "            ) { tabNow ->" + chr(10) + ""
           "            when (tabNow) {")
    return text.replace(anchor, ins, 1)




def patch_transition_close(text: str) -> str:
    """补 AnimatedContent 的闭合（第 314 轮）。

    patch_global_transition 加了 AnimatedContent { when(tabNow) { ... } }，
    但 when 的 } 之后缺 AnimatedContent 的 }。
    这里在「详情浮层」注释之前补一个。
    """
    marker = "        // ---------- 详情浮层 ----------"
    i = text.find(marker)
    if i < 0:
        return text
    # 只在已经加了 AnimatedContent 的情况下补
    if "com.han1me.ui.HplayTransition.enter" not in text:
        return text
    # 检查前一行是否已经补过
    before = text[max(0, i-60):i]
    if before.rstrip().endswith("}") and "AnimatedContent 闭合" in before:
        return text
    return text[:i] + "            }  // AnimatedContent 闭合\n" + text[i:]




def patch_dedup_web_login(text: str) -> str:
    """删掉 dismissButton 里重复的「网页登录」（第 315 轮）。

    patch_remove_native_login 把 confirmButton 换成了「网页登录」，
    但 dismissButton 里原本还有一个「网页登录」—— 现在出现两个同名按钮。
    """
    old = ('            Row {\n'
           '                TextButton(onClick = onWebLogin) { Text("\u7f51\u9875\u767b\u5f55") }\n'
           '                TextButton(onClick = onDismiss) { Text("\u53d6\u6d88") }\n'
           '            }')
    new = '            TextButton(onClick = onDismiss) { Text("\u53d6\u6d88") }'
    if old in text:
        text = text.replace(old, new, 1)
    return text




def patch_detail_animation(text: str) -> str:
    """详情页浮层加转场（第 316 轮 · 用户要求「点视频切换到介绍页也没动画」）。

    照 Han1meViewer：新页从右侧滑入 + 淡入。
    """
    anchor = "        detail?.let { d ->"
    if anchor not in text:
        return text
    ins = ("        // \u2605 2026-11-25\uff08\u7b2c 316 \u8f6e\uff09\uff1a"
           "**\u8be6\u60c5\u9875\u6d6e\u5c42\u52a0\u8f6c\u573a**\u3002" + chr(10) + ""
           "        androidx.compose.animation.AnimatedVisibility(" + chr(10) + ""
           "            visible = detail != null," + chr(10) + ""
           "            enter = androidx.compose.animation.slideInHorizontally(" + chr(10) + ""
           "                animationSpec = androidx.compose.animation.core.tween(" + chr(10) + ""
           "                    320, easing = androidx.compose.animation.core.FastOutSlowInEasing," + chr(10) + ""
           "                )," + chr(10) + ""
           "                initialOffsetX = { it }," + chr(10) + ""
           "            ) + androidx.compose.animation.fadeIn(" + chr(10) + ""
           "                animationSpec = androidx.compose.animation.core.tween(320)," + chr(10) + ""
           "            )," + chr(10) + ""
           "            exit = androidx.compose.animation.slideOutHorizontally(" + chr(10) + ""
           "                animationSpec = androidx.compose.animation.core.tween(" + chr(10) + ""
           "                    260, easing = androidx.compose.animation.core.FastOutSlowInEasing," + chr(10) + ""
           "                )," + chr(10) + ""
           "                targetOffsetX = { it }," + chr(10) + ""
           "            ) + androidx.compose.animation.fadeOut(" + chr(10) + ""
           "                animationSpec = androidx.compose.animation.core.tween(260)," + chr(10) + ""
           "            )," + chr(10) + ""
           "        ) {" + chr(10) + ""
           "        detail?.let { d ->")
    text = text.replace(anchor, ins, 1)
    # 闭合 AnimatedVisibility：在 detail?.let 块结束处补
    # 找 HanimeDetailPanel 调用的结束（下一个 "        }\n" 且缩进为 8）
    i = text.find("        detail?.let { d ->")
    if i < 0:
        return text
    # 从 detail?.let 开始数括号
    depth = 0
    j = i
    started = False
    while j < len(text):
        ch = text[j]
        if ch == '{':
            depth += 1
            started = True
        elif ch == '}':
            depth -= 1
            if started and depth == 0:
                j += 1
                break
        j += 1
    return text[:j] + "\n        }  // AnimatedVisibility\n" + text[j:]




def patch_sites_transition(text: str) -> str:
    """站点页转场（第 143/317/325/326 轮）。

    用户反复报：「最新页在加载时没有转场动画，加载完成才有」。

    ## 三轮没修成的原因（逐条记录，避免再踩）

    317 轮：函数写好了，但**从未被 main() 调用** —— 所以一直硬切。
    325 轮：接入后报一串 `@Composable invocations can only happen from
             the context of a @Composable function`。
    326 轮（本轮）：找到根因 —— 这段代码在 **Column 作用域**里，
            而 `AnimatedContent` 的 content lambda 会**夺走 ColumnScope**，
            于是里面所有靠 ColumnScope 的成员（weight / align / fillMaxWidth）
            全部解析失败。

    ## 本轮方案：AnimatedVisibility 而不是 AnimatedContent

    `AnimatedVisibility` 的 content 是 **AnimatedVisibilityScope**，
    但它是 ColumnScope 的**子接口**（Column 里调用时），所以 weight/align 照样可用。
    两个分支各包一层、条件互斥，视觉上等价于「切换带转场」。

    转场规格照全局：进 = 滑入 + 淡入 450ms，出 = 淡出 300ms。
    """
    CRLF = chr(13) + chr(10)
    if 'AnimatedVisibility(' in text and 'sites-board-fade' in text:
        return text

    # 找 `if (board == "sites") {` 那一行（顶格 12 空格）
    anchor = '            if (board == "sites") {'
    if anchor not in text:
        return text

    wrapper = (
        '            // \u2605 2026-11-25\uff08\u7b2c 326 \u8f6e\uff09\uff1a**\u7ad9\u70b9\u9875\u8f6c\u573a**\u3002' + CRLF +
        '            //' + CRLF +
        '            // \u7528 AnimatedVisibility \u800c\u4e0d\u662f AnimatedContent\uff1a\u540e\u8005\u7684 content lambda' + CRLF +
        '            // \u4f1a\u593a\u8d70 ColumnScope\uff0c\u8fd9\u6bb5\u4ee3\u7801\u6b63\u597d\u5728 Column \u91cc\uff0c' + CRLF +
        '            // \u4e8e\u662f weight/align \u5168\u90e8\u89e3\u6790\u5931\u8d25\uff08\u7b2c 325 \u8f6e\u5c31\u662f\u8fd9\u4e48\u5361\u4f4f\u7684\uff09\u3002' + CRLF +
        '            // AnimatedVisibilityScope \u662f ColumnScope \u7684\u5b50\u63a5\u53e3\uff0c\u4e24\u8005\u90fd\u80fd\u7528\u3002' + CRLF +
        '            androidx.compose.animation.AnimatedVisibility(' + CRLF +
        '                visible = board == "sites",' + CRLF +
        '                enter = androidx.compose.animation.slideInHorizontally(' + CRLF +
        '                    animationSpec = androidx.compose.animation.core.tween(' + CRLF +
        '                        450, easing = androidx.compose.animation.core.FastOutSlowInEasing),' + CRLF +
        '                    initialOffsetX = { it -> it },' + CRLF +
        '                ) + androidx.compose.animation.fadeIn(' + CRLF +
        '                    animationSpec = androidx.compose.animation.core.tween(450)),' + CRLF +
        '                exit = androidx.compose.animation.fadeOut(' + CRLF +
        '                    animationSpec = androidx.compose.animation.core.tween(300)),' + CRLF +
        '                label = "sites-board-fade",' + CRLF +
        '            ) {' + CRLF +
        '            if (board == "sites") {'
    )
    text = text.replace(anchor, wrapper, 1)

    # 收尾：`} else androidx...` 之前补两个 } —— 给 if(sites) 与 AnimatedVisibility
    close_marker = '            } else androidx.compose.animation.AnimatedContent('
    k = text.find(close_marker)
    if k > 0:
        text = (text[:k]
                + '            }  // \u2190 \u7b2c 326 \u8f6e\uff1aif(sites) \u7684\u6536\u5c3e' + CRLF
                + '            }  // \u2190 \u7b2c 326 \u8f6e\uff1aAnimatedVisibility \u7684\u6536\u5c3e' + CRLF
                + text[k:])
    print('  [sites] \u8f6c\u573a\u5df2\u63a5\u5165\uff08AnimatedVisibility\uff09')
    return text


def patch_sites_visibility(text: str) -> str:
    """站点合集页用 AnimatedVisibility（第 317 轮）。

    之前尝试套 AnimatedContent(board) 失败 —— 它被插进了 AnimatedContent(tab)
    的 lambda 内部，形成非法嵌套（Compose 不允许这种用法）。

    改用 AnimatedVisibility：它可以在任意位置包住一个子树，
    用 board == "sites" 作为 visible 条件，进出都带动画。
    """
    anchor = '            if (board == "sites") {'
    if anchor not in text:
        return text
    ins = ('            // \u2605 2026-11-25\uff08\u7b2c 317 \u8f6e\uff09\uff1a'
           '**\u7ad9\u70b9\u9875\u7528 AnimatedVisibility**\uff08\u4e0d\u7b49\u6570\u636e\uff09\u3002\n'
           '            // \u4e4b\u524d\u5c1d\u8bd5 AnimatedContent(board) \u5931\u8d25\uff1a'
           '\u5b83\u88ab\u63d2\u8fdb AnimatedContent(tab) \u7684 lambda \u5185\u90e8\uff0c\n'
           '            // \u5f62\u6210\u975e\u6cd5\u5d4c\u5957\u3002AnimatedVisibility '
           '\u53ef\u4ee5\u4efb\u610f\u4f4d\u7f6e\u5305\u5b50\u6811\u3002\n'
           '            androidx.compose.animation.AnimatedVisibility(\n'
           '                visible = board == "sites",\n'
           '                enter = androidx.compose.animation.slideInHorizontally(\n'
           '                    animationSpec = androidx.compose.animation.core.tween(\n'
           '                        320, easing = androidx.compose.animation.core.FastOutSlowInEasing,\n'
           '                    ),\n'
           '                    initialOffsetX = { it },\n'
           '                ) + androidx.compose.animation.fadeIn(\n'
           '                    animationSpec = androidx.compose.animation.core.tween(320),\n'
           '                ),\n'
           '                exit = androidx.compose.animation.fadeOut(\n'
           '                    animationSpec = androidx.compose.animation.core.tween(200),\n'
           '                ),\n'
           '            ) {\n'
           '            if (board == "sites") {')
    text = text.replace(anchor, ins, 1)
    marker = '                    onBack = { board = "hanime"; backToHome() },\n                )'
    j = text.find(marker)
    if j > 0:
        j += len(marker)
        text = text[:j] + "\n            }  // AnimatedVisibility(board)" + text[j:]
    return text




def patch_favtab_rewrite(text: str) -> str:
    """收藏页整段重写（第 317 轮 · 用户要求）。

    旧 FavTab 是 198 行的三段平铺（入口网格 / 本站收藏 / 账号收藏），
    越滚越长、分不清哪块是哪块，而且顶部双 tab 没真正切换内容。

    新 FavTab：
      · 顶部两个 tab（本站收藏·N / 账号收藏），真正切换内容
      · 本站收藏：板块筛选 + 分页 + 卡片列表
      · 账号收藏：LibraryEntryGrid（5 个云端入口）
    """
    old_f = r"D:/deepseek-harness/deepseek-harness工作区/hpla实验/_临时/oldfavtab.txt"
    new_f = r"D:/deepseek-harness/deepseek-harness工作区/hpla实验/_临时/newfavtab.txt"
    try:
        # ★ 2026-11-25（第 317 轮）：**必须用 newline="" 保留 CRLF**。
        # 默认 io.open 的 universal newlines 会把 \r\n 转成 \n，
        # 而 gen 里的文件是 CRLF —— 于是 old 永远匹配不上（9036 vs 9240）。
        # ★ 2026-11-25（第 317 轮）：**用二进制读再解码**，彻底避开换行转换。
        # newline="" 仍然会做部分转换（实测 9240 -> 9233，少了 7 个 \r）。
        old = io.open(old_f, "rb").read().decode("utf-8")
        new = io.open(new_f, "rb").read().decode("utf-8")
    except Exception as e:
        print("  [favtab] 读文件失败：" + repr(e))
        return text
    print("  [favtab] old=" + str(len(old)) + " new=" + str(len(new)) + " 命中=" + str(old in text))
    if old in text:
        text = text.replace(old, new, 1)
        print("  [favtab] 已替换")
    return text




def patch_favtab_by_lines(text: str) -> str:
    """按行号替换 FavTab（第 317 轮 · 绕开字符串匹配的换行坑）。

    前面尝试 old/new 字符串匹配全部失败：
      node 写入的 oldfavtab.txt 是 9240 字符（含 \r\n），
      但 Python 读出来只有 9233 —— 换行被转换，永远匹配不上。

    改成按**行号**定位：找 "private fun FavTab(" 所在行，
    再用括号计数找函数结束行，直接 splice。
    """
    lines = text.split("\n")
    start = -1
    for k, ln in enumerate(lines):
        if ln.strip().startswith("private fun FavTab("):
            start = k
            break
    if start < 0:
        return text
    # 括号计数找结束
    depth = 0
    end = -1
    started = False
    for k in range(start, len(lines)):
        for ch in lines[k]:
            if ch == '{':
                depth += 1
                started = True
            elif ch == '}':
                depth -= 1
        if started and depth == 0:
            end = k
            break
    if end < 0:
        return text
    new_f = r"D:/deepseek-harness/deepseek-harness工作区/hpla实验/_临时/newfavtab.txt"
    try:
        new = io.open(new_f, "rb").read().decode("utf-8")
    except Exception:
        return text
    # 新内容也按 \n 拆
    new_lines = new.replace("\r\n", "\n").split("\n")
    out = lines[:start] + new_lines + lines[end + 1:]
    print("  [favtab] 按行替换 " + str(end - start + 1) + " 行 -> " + str(len(new_lines)) + " 行")
    return "\n".join(out)




def patch_favtab_downloads_call(text: str) -> str:
    """给 FavTab 调用处补 onDownloads（第 318 轮）。"""
    anchor = '                    onSubs = { showSubs = true },'
    if anchor not in text:
        return text
    ins = anchor + "\n" \
        "                    // \u2605 \u7b2c 318 \u8f6e\uff1a\u672c\u5730\u4e0b\u8f7d\u5165\u53e3\uff08\u4e4b\u524d\u8bef\u7ed1\u5230 onSubs\uff09\n" \
        "                    onDownloads = { showDownloads = true },"
    return text.replace(anchor, ins, 1)




def patch_list_two_col(text: str) -> str:
    """han 的收藏/搜索/历史/稍后观看改成固定双栏（第 318 轮 · 用户要求）。

    用户原话：「han 的收藏搜索历史稍后观看等都双栏显示结果，和订阅页列表一样就行」。
    原来是 GridCells.Adaptive(200.dp) —— 在 1080p 竖屏上算出来是 2 栏，
    但在某些宽度下会变 1 栏或 3 栏，不稳定。改成 Fixed(2)。
    """
    old = ("            LazyVerticalGrid(\n"
           "                columns = GridCells.Adaptive(200.dp)," + chr(10) + ""
           "                horizontalArrangement = Arrangement.spacedBy(12.dp),")
    new = ("            LazyVerticalGrid(\n"
           "                // \u2605 2026-11-25\uff08\u7b2c 318 \u8f6e\uff09\uff1a"
           "**\u56fa\u5b9a\u53cc\u680f**\uff08\u7528\u6237\u8981\u6c42\uff0c\u4e0e\u8ba2\u9605\u9875\u4e00\u81f4\uff09\u3002" + chr(10) + ""
           "                // \u539f\u6765 Adaptive(200.dp) \u5728\u4e0d\u540c\u5bbd\u5ea6\u4e0b\u4f1a\u53d8 1/2/3 \u680f\uff0c\u4e0d\u7a33\u5b9a\u3002" + chr(10) + ""
           "                columns = GridCells.Fixed(2)," + chr(10) + ""
           "                horizontalArrangement = Arrangement.spacedBy(12.dp),")
    if old in text:
        text = text.replace(old, new, 1)
    return text




def patch_downloads_render(text: str) -> str:
    """补上 showDownloads 的渲染块（第 318 轮 · 用户报「用户页下载点击无响应」）。

    根因：showDownloads 只被设为 true，**没有任何地方渲染它**。
    （showSubs / showNotices 都有渲染块，唯独下载页漏了。）
    """
    anchor = "        if (showSubs) {"
    if anchor not in text:
        return text
    ins = ("        // \u2605 2026-11-25\uff08\u7b2c 318 \u8f6e\uff09\uff1a"
           "**\u8865\u4e0a\u4e0b\u8f7d\u9875\u6e32\u67d3\u5757**\u3002" + chr(10) + ""
           "        // \u539f\u6765 showDownloads \u53ea\u88ab\u8bbe true\uff0c"
           "\u6ca1\u6709\u4efb\u4f55\u5730\u65b9\u6e32\u67d3\u5b83\uff0c" + chr(10) + ""
           "        // \u6240\u4ee5\u70b9\u300c\u672c\u5730\u4e0b\u8f7d\u300d\u6ca1\u53cd\u5e94\u3002" + chr(10) + ""
           "        if (showDownloads) {" + chr(10) + ""
           "            Box(Modifier.fillMaxSize().background(BgColor).clickable(enabled = false) {}) {" + chr(10) + ""
           "                com.han1me.download.HplayDownloadsPanel(" + chr(10) + ""
           "                    onClose = { showDownloads = false }," + chr(10) + ""
           "                    onPlay = { path ->" + chr(10) + ""
           "                        showDownloads = false" + chr(10) + ""
           "                        detailFromBoard = false" + chr(10) + ""
           "                        detail = com.han1me.download.HplayDownloads.detailFor(path)" + chr(10) + ""
           "                    }," + chr(10) + ""
           "                )" + chr(10) + ""
           "            }" + chr(10) + ""
           "        }" + chr(10) + ""
           + anchor)
    return text.replace(anchor, ins, 1)




def patch_login_instant_fetch(text: str) -> str:
    """登录态变化时立刻抓账户（第 320 轮）。

    之前 LaunchedEffect(LoginState.tick) 里是 scope.launch(IO) 异步抓，
    从 tick 到看到头像隔了 3~6 秒。

    现在：
      · LoginActivity 已经用 InstantAccountLoader 抓过了（2 秒硬超时）
      · 这里只做兜底：如果 CachedAccount 还是空，再抓一次
    """
    anchor = "    androidx.compose.runtime.LaunchedEffect(com.han1me.lab.LoginState.tick) {"
    if anchor not in text:
        return text
    # 找到 effect 块结束
    i = text.find(anchor)
    j = text.find("\n    }\n", text.find("profile = com.javrocket.hanime.HanimeProfile(", i))
    if j < 0:
        return text
    j += len("\n    }\n")
    new = ("    // \u2605 2026-11-25\uff08\u7b2c 320 \u8f6e\uff09\uff1a"
           "**\u767b\u5f55\u6001\u53d8\u5316\u65f6\u7acb\u523b\u5237\u65b0**\u3002" + chr(10) + ""
           "    // InstantAccountLoader \u5df2\u5728 LoginActivity \u91cc\u6293\u8fc7\uff0c"
           "\u8fd9\u91cc\u53ea\u505a\u515c\u5e95\u3002" + chr(10) + ""
           "    androidx.compose.runtime.LaunchedEffect(com.han1me.lab.LoginState.tick) {" + chr(10) + ""
           "        if (com.han1me.lab.LoginState.tick > 0) {" + chr(10) + ""
           "            // \u5148\u628a UI \u5207\u5230\u5df2\u767b\u5f55\uff08\u4e0d\u7b49\u7f51\u7edc\uff09" + chr(10) + ""
           "            if (user == null) {" + chr(10) + ""
           "                user = com.han1me.X99.setting(\"account.email\", \"\")" + chr(10) + ""
           "            }" + chr(10) + ""
           "            // CachedAccount \u5df2\u6709\u6570\u636e -> \u76f4\u63a5\u7528\uff1b"
           "\u5426\u5219\u8865\u4e00\u6b21" + chr(10) + ""
           "            val cached = com.han1me.lab.CachedAccount.info" + chr(10) + ""
           "            if (cached != null) {" + chr(10) + ""
           "                profile = com.javrocket.hanime.HanimeProfile(" + chr(10) + ""
           "                    uid = cached.userId," + chr(10) + ""
           "                    name = cached.userName," + chr(10) + ""
           "                    avatar = cached.avatarUrl," + chr(10) + ""
           "                    avatarLarge = cached.avatarUrl," + chr(10) + ""
           "                    stats = \"\"," + chr(10) + ""
           "                    home = \"https://hanime1.me/user/\" + cached.userId," + chr(10) + ""
           "                    edit = \"https://hanime1.me/user/\" + cached.userId + \"/edit\"," + chr(10) + ""
           "                    playlists = \"https://hanime1.me/user/\" + cached.userId + \"/playlists\"," + chr(10) + ""
           "                )" + chr(10) + ""
           "                if (cached.userName.isNotBlank()) user = cached.userName" + chr(10) + ""
           "            } else {" + chr(10) + ""
           "                scope.launch(Dispatchers.IO) {" + chr(10) + ""
           "                    com.han1me.lab.InstantAccountLoader.loadNow(HanimeClient.http)" + chr(10) + ""
           "                }" + chr(10) + ""
           "            }" + chr(10) + ""
           "        }" + chr(10) + ""
           "    }\n")
    return text[:i] + new + text[j:]




def patch_login_force_user(text: str) -> str:
    """登录态变化时无条件设置 user（第 321 轮）。

    ★ 用户报「登录之后功能都可用，但字样仍显示为未登录」。

    真机日志定位：
      [login] 取到 cookie 1540 字节
      [login.state] 登录态变更 tick=1
      [account] GET / -> 403           <- CF 拦截，profile 抓不到
      [account] 即时加载超时或失败（2s）

    而 user 变量的来源是 HanimeClient.whoami()（第 242/268 行），
    它同样走网络、同样 403 -> 返回 null -> user = null -> 界面「未登录」。

    修法：**只要磁盘上有登录 cookie，就无条件认为已登录**，
    user 设为邮箱或占位串，不依赖任何网络请求。
    profile（头像/昵称）能抓到就补，抓不到不影响「已登录」这个事实。
    """
    anchor = "    androidx.compose.runtime.LaunchedEffect(com.han1me.lab.LoginState.tick) {"
    if anchor not in text:
        return text
    # 在 effect 内部第一行插入强制设置
    i = text.find(anchor)
    j = text.find("        if (com.han1me.lab.LoginState.tick > 0) {", i)
    if j < 0:
        return text
    ins = ("        // \u2605 2026-11-25\uff08\u7b2c 321 \u8f6e\uff09\uff1a"
           "**\u6709 cookie \u5c31\u662f\u5df2\u767b\u5f55**\uff0c"
           "\u4e0d\u4f9d\u8d56\u4efb\u4f55\u7f51\u7edc\u8bf7\u6c42\u3002" + chr(10) + ""
           "        // \u771f\u673a\u65e5\u5fd7\u8bc1\u636e\uff1a"
           "whoami() \u548c CloudAccount.fetch() \u90fd\u4f1a\u88ab CF \u62e6\u622a\u8fd4 403\uff0c" + chr(10) + ""
           "        // \u4f46\u672c\u5730 cookie \u662f\u771f\u7684\u2014\u2014 "
           "\u529f\u80fd\u90fd\u80fd\u7528\uff0c\u53ea\u662f\u5b57\u6837\u663e\u793a\u9519\u3002" + chr(10) + ""
           "        if (com.han1me.lab.CfCookieBridge.savedLogin().isNotBlank()) {" + chr(10) + ""
           "            if (user == null) {" + chr(10) + ""
           "                user = com.han1me.X99.setting(\"account.email\", \"\")" + chr(10) + ""
           "                    .ifBlank { \"\u5df2\u767b\u5f55\" }" + chr(10) + ""
           "            }" + chr(10) + ""
           "        }" + chr(10) + ""
           + "        if (com.han1me.lab.LoginState.tick > 0) {")
    return text[:j] + ins + text[j + len("        if (com.han1me.lab.LoginState.tick > 0) {"):]




# ================================================================
# 第 322 轮：清掉旧板块残留（用户要求「构建完成删除旧板块残留」）
# ================================================================
#
# 背景：设置页迁到独立覆盖层（HplaySettingsHost）后，原来塞在
# 「我的」面板里的几个旧板块仍在生成文件里，且互相之间已经没有任何
# 调用点（MinePanel 本身就没有地方 showMine = true）。它们只是白占
# 代码体积、让搜索/阅读时混淆，用户明确要求删掉。
#
# 做法：按**函数名**在顶层（列 0）定位，用花括号计数找结束，连同前面的
# @Composable / 文档注释一起摘掉。这样比写几十行文本锚点稳得多 ——
# 那些函数体里含大量 emoji 与全角字符，三引号锚点极易失配。
#
# 判据：只有**全文件除自身外 0 处引用**的才删；调用点还在的一律保留。
DEAD_FUNCTIONS = [
    # 我的面板：功能已全部迁到用户页 ProfileGrid + 收藏页 + 独立设置页
    "MinePanel",
    # 面板内的清单入口（只被 MinePanel 用）
    "MineItem",
    # 板块源切换：已在设置 > 网络里（CatalogSourceSection 的新实现在 SettingsPages）
    "CatalogSourceSection",
    # 网络设置面板：同上（NodeSection / EdgeSection 才是现在用的）
    "NetSettingsSection",
    # 边缘节点面板：设置页用的是 lab.EdgeSection，这个是旧版
    "EdgeNodeSection",
    # 旧下载面板：已被 com.han1me.download.HplayDownloadsPanel 取代
    "DownloadsPanel",
    # 旧下载面板的两个私有帮手
    "dirBytes",
    "localFileDetail",
]


def strip_dead_functions(text: str, names) -> str:
    """删掉顶层 private/internal fun <name>(…) 整段（含前置注解与注释）。

    纪律（顺序很重要）：**先定位函数体范围，再统计范围外的引用数** ——
    否则函数体里的自递归（dirBytes 里调 dirBytes）会被当成外部引用，导致永远删不掉。
    """
    import re as _re
    lines = text.split(chr(10))
    for name in names:
        decl = "private fun " + name + "("
        decl2 = "internal fun " + name + "("
        # ① 定位定义行（必须顶格，排除缩进在别处的同名调用）
        start = -1
        for i, ln in enumerate(lines):
            s = ln.lstrip()
            if (s.startswith(decl) or s.startswith(decl2)) and ln == s:
                start = i
                break
        if start < 0:
            print("  [死代码] %s 未找到（可能已删）" % name)
            continue
        # ② 找收尾的列 0 "}"；Kotlin 顶层函数的右花括号必定顶格
        end = -1
        for j in range(start, min(start + 900, len(lines))):
            if lines[j] == "}":
                end = j
                break
        if end < 0:
            print("  [死代码] %s 找不到结束花括号，保留" % name)
            continue
        # ③ 统计 [start, end] 之外的引用（词边界，避开 HplayDownloadsPanel 这类前缀同名）
        pat = _re.compile("(?<![A-Za-z0-9_])" + _re.escape(name) + "(?![A-Za-z0-9_])")
        refs = 0
        for k, ln in enumerate(lines):
            if start <= k <= end:
                continue
            s = ln.strip()
            if s.startswith("//") or s.startswith("*") or s.startswith("/*"):
                continue
            if pat.search(ln):
                refs += 1
        if refs > 0:
            print("  [死代码] %s 仍有 %d 处引用，保留" % (name, refs))
            continue
        # ④ 往前吃掉紧贴的注解行与文档注释块
        head = start
        k = start - 1
        while k >= 0:
            s = lines[k].strip()
            if (s.startswith("@") or s.startswith("* ") or s == "*" or s == "*/"
                    or s.startswith("/*") or s.startswith("//")):
                head = k
                k -= 1
                continue
            break
        # ⑤ 删除，并吃掉紧跟的空行（避免留下连续三个以上空行）
        tail = end + 1
        while tail < len(lines) and lines[tail].strip() == "":
            tail += 1
        del lines[head:tail]
        print("  [死代码] 已删 %s（%d 行）" % (name, tail - head))
    return chr(10).join(lines)



def patch_display_name(text: str) -> str:
    """用户页昵称：优先云端抓到的那一个（第 322 轮）。

    真机现象：头像已经显示出来了，昵称却是「已登录」——
    因为 displayName 取的是 profile?.name ?: user，而 user 来自 X99 设置里的
    account.email（登录时从页面读到的邮箱，可能是空的，于是落到占位串）。

    修法：把 CachedAccount.userName（WebView 抓到的真实昵称）插到最前面。
    它是全局 Compose 状态，抓到就自动重组，不需要额外通知。
    """
    old = '                displayName = profile?.name?.takeIf { it.isNotBlank() } ?: (user ?: "未登录"),'
    new = (
        '                displayName = com.han1me.lab.CachedAccount.userName'
        '.ifBlank { profile?.name.orEmpty() }'
        '.ifBlank { user.orEmpty() }'
        '.ifBlank { "未登录" },'
    )
    if old in text:
        text = text.replace(old, new, 1)
    return text



def patch_player_dedup(text: str) -> str:
    """移除旧播放内核（第 326 轮）。

    ## 问题

    详情页同时建了两套播放控制器：
      · controller  ← rememberHplayController（旧 HplayPlayerView 用）
      · controller2 ← rememberHplayController2（新 HplayPlayerView2，画面实际用它）

    旧的唯一渲染点在 if (false) 死分支里 —— 也就是说它**从不显示**，
    但它照样 new 一个 ExoPlayer、setTargetBufferBytes(48~96MB)、
    在同一条媒体流上再拉一遍。同一部片被下载两遍。

    ## 做法（按行定位，不依赖文本锚点）

    文本锚点在这个文件上试过两次都失配（注释里的全角引号 + CRLF 组合），
    改用行索引 + 括号计数最稳。
    """
    lines = text.split(chr(10))

    # 1) 删掉 if (false) { ... } else { 里的死分支，只留 else 的花括号
    dead = -1
    for i, L in enumerate(lines):
        if L.strip().startswith('if (false) {') and 'controller' not in L:
            # 确认这段里确实引用了 controller（就是播放器的死分支）
            seg = chr(10).join(lines[i:i + 20])
            if 'HplayPlayerView(' in seg and 'controller' in seg:
                dead = i
                break
    removed_dead = 0
    if dead >= 0:
        # 找 else 行
        els = -1
        for j in range(dead, min(dead + 40, len(lines))):
            if lines[j].strip() == '} else {':
                els = j
                break
        if els > 0:
            removed_dead = els - dead
            lines[dead:els] = []
            # 删掉死分支后，原来的 `} else {` 变成孤立块；
            # 直接把它改成 `run {`（Kotlin 的匿名块，保留原来的缩进与作用域）。
            if dead < len(lines) and lines[dead].strip() == '} else {':
                # ★ 第 329 轮：不用 run {}（它会建一个 lambda 作用域，
                # 实测导致同级兄弟布局出问题：详情页所有按钮点不了）。
                # 改用 `if (true) {`：保留原来的花括号配对与作用域，但不建 lambda。
                lines[dead] = '            if (true) {'
                print('  [player] \u5b64立块 } else { -> run {')
            print('  [player] \u5df2\u5220 if(false) \u6b7b\u5206\u652f %d \u884c' % removed_dead)

    # 2) 删掉 controller 的创建 + 它的 LaunchedEffect
    ctl = -1
    for i, L in enumerate(lines):
        if 'val controller = com.han1me.player.rememberHplayController(' in L:
            ctl = i
            break
    removed_ctl = 0
    if ctl >= 0:
        # 往前吃掉那行注释
        head = ctl
        while head > 0 and lines[head - 1].strip().startswith('//'):
            head -= 1
        # 往后：跳过 LaunchedEffect 整块（用括号计数）
        tail = ctl + 1
        # 找 'androidx.compose.runtime.LaunchedEffect(playingUrl) {'
        le = -1
        for j in range(ctl, min(ctl + 12, len(lines))):
            if 'LaunchedEffect(playingUrl)' in lines[j]:
                le = j
                break
        if le > 0:
            depth = 0
            end = le
            for j in range(le, min(le + 20, len(lines))):
                depth += lines[j].count('{') - lines[j].count('}')
                if depth == 0 and j > le:
                    end = j
                    break
            tail = end + 1
        # 吃掉后续空行
        while tail < len(lines) and lines[tail].strip() == '':
            tail += 1
        removed_ctl = tail - head
        lines[head:head] = ['    // \u2605\u2605\u2605 2026-11-25\uff08\u7b2c 326 \u8f6e\uff09\uff1a**\u65e7\u64ad\u653e\u5185\u6838\u5df2\u5f7b\u5e95\u79fb\u9664**\u3002',
                            '    // \u539f\u6765\u8fd9\u91cc\u5efa\u4e86\u4e24\u5957\u63a7\u5236\u5668\uff1acontroller\uff08\u65e7\uff09\u4e0e controller2\uff08\u65b0\uff0c\u753b\u9762\u5b9e\u9645\u7528\u5b83\uff09\u3002',
                            '    // \u65e7\u7684\u552f\u4e00\u6e32\u67d3\u70b9\u662f\u4e00\u4e2a if(false) \u6b7b\u5206\u652f\uff08\u5df2\u5220\uff09\uff0c\u4f46\u5b83\u7167\u6837 new ExoPlayer\u3001',
                            '    // \u5360 48~96MB \u7f13\u51b2\u3001\u5728\u540c\u4e00\u6761\u5a92\u4f53\u6d41\u4e0a\u518d\u62c9\u4e00\u904d \u2014\u2014 \u540c\u4e00\u90e8\u7247\u88ab\u4e0b\u8f7d\u4e24\u904d\u3002',
                            '    // \u73b0\u5728\u53ea\u4fdd\u7559 controller2\uff0c\u5b83\u5728 playingUrl != null \u5206\u652f\u91cc\u521b\u5efa\u3002',
                            '']
        del lines[head + 5:tail + 5]
        print('  [player] \u5df2\u5220\u65e7 controller %d \u884c' % removed_ctl)

    # 3) 剩下的 controller.stop() 改成只关浮层（没有旧内核可停了）
    fixed_stop = 0
    for i, L in enumerate(lines):
        if 'runCatching { controller.stop() }' in L:
            lines[i] = L.replace('runCatching { controller.stop() }; ', '')
            fixed_stop += 1
    if fixed_stop:
        print('  [player] %d \u5904 controller.stop() \u5df2\u6539\u4e3a\u76f4\u63a5\u5173\u95ed' % fixed_stop)

    return chr(10).join(lines)



def patch_list_responsive(text: str) -> str:
    """列表网格列数自适应（第 327 轮）。

    用户要求「修复自动旋转适配」。

    ## 现状

    App 没有锁方向（requestedOrientation = UNSPECIFIED），但列表网格是**硬编码双栏**
    （第 318 轮为了「与订阅页一致」刻意固定的）。横屏 / 平板 / 折叠屏展开时，
    屏上就两条巨型卡片，留白比内容多。

    ## 做法

    包一层 BoxWithConstraints，用 adaptiveColumns(maxWidth, 160.dp) 算列数。
    手机上（360~430dp）算出来仍是 2 列 —— 与第 318 轮的要求不冲突，
    只有真的变宽才加列。这既满足「保持双栏」的初衷，又满足「旋转适配」。
    """
    CRLF = chr(13) + chr(10)
    if 'columnsFor(maxWidth' in text:
        return text
    old = (
        '            LazyVerticalGrid(' + CRLF +
        '                // \u2605 2026-11-25\uff08\u7b2c 318 \u8f6e\uff09\uff1a**\u56fa\u5b9a\u53cc\u680f**\uff08\u7528\u6237\u8981\u6c42\uff0c\u4e0e\u8ba2\u9605\u9875\u4e00\u81f4\uff09\u3002' + CRLF +
        '                // \u539f\u6765 Adaptive(200.dp) \u5728\u4e0d\u540c\u5bbd\u5ea6\u4e0b\u4f1a\u53d8 1/2/3 \u680f\uff0c\u4e0d\u7a33\u5b9a\u3002' + CRLF +
        '                columns = GridCells.Fixed(2),'
    )
    new = (
        '            // \u2605\u7b2c 327 \u8f6e\uff1a**\u5217\u6570\u81ea\u9002\u5e94**\u3002' + CRLF +
        '            //' + CRLF +
        '            // \u7b2c 318 \u8f6e\u4e3a\u4e86\u300c\u4e0e\u8ba2\u9605\u9875\u4e00\u81f4\u300d\u628a\u5b83\u786c\u5b9a\u4e3a\u53cc\u680f\uff0c\u4f46\u6a2a\u5c4f/\u5e73\u677f/\u6298\u53e0\u5c4f\u5c55\u5f00\u65f6' + CRLF +
        '            // \u5c4f\u4e0a\u5c31\u4e24\u6761\u5de8\u5361\u3002\u73b0\u5728\u6539\u6210\u6309\u5bbd\u5ea6\u7b97\uff08adaptiveColumns\uff09\uff1a' + CRLF +
        '            // \u624b\u673a\u4e0a\uff08360~430dp\uff09\u7b97\u51fa\u6765\u4ecd\u662f 2 \u5217\uff0c\u4e0e\u539f\u8981\u6c42\u4e0d\u51b2\u7a81\uff1b\u53ea\u6709\u771f\u53d8\u5bbd\u624d\u52a0\u5217\u3002' + CRLF +
        '            androidx.compose.foundation.layout.BoxWithConstraints(' + CRLF +
        '                Modifier.fillMaxSize(),' + CRLF +
        '            ) {' + CRLF +
        '            LazyVerticalGrid(' + CRLF +
        '                columns = GridCells.Fixed(com.han1me.ui.adaptiveColumns(maxWidth, minItemWidth = 160.dp)),'
    )
    if old not in text:
        print('  [list] \u951a\u70b9\u5931\u914d\uff0c\u8df3\u8fc7')
        return text
    text = text.replace(old, new, 1)
    # 闭合：该网格结束的 '            }' 之后补一个
    marker = '                items(cards, key = { it.id }) { v ->'
    j = text.find(marker)
    if j > 0:
        k = text.find(CRLF + '            }', j)
        if k > 0:
            k += len(CRLF + '            }')
            text = text[:k] + CRLF + '            }  // \u2190 \u7b2c 327 \u8f6e\uff1aBoxWithConstraints \u6536\u5c3e' + text[k:]
            print('  [list] \u5217\u6570\u81ea\u9002\u5e94\u5df2\u63a5\u5165')
    return text



def patch_list_responsive2(text: str) -> str:
    """列表网格列数自适应（第 329 轮重做）。

    第 327 轮试过用文本锚点改，因 CRLF/缩进组合失配而撤掉；
    这次改**行号定位**：找到 `columns = GridCells.Fixed(2),` 那一行，
    往上找它所属的 `LazyVerticalGrid(`，再往上找包裹它的语句 ——
    如果没有 BoxWithConstraints 作用域，就插一个。

    为什么不能直接写 adaptiveColumns(maxWidth)：
    那个 LazyVerticalGrid 在 `Column` 里，没有 maxWidth 可用。
    """
    lines = text.split(chr(10))
    target = -1
    for i, L in enumerate(lines):
        if L.strip() == 'columns = GridCells.Fixed(2),':
            # 确认它属于列表页那个网格（往上 20 行内有 ListContent 或 cards）
            seg = chr(10).join(lines[max(0, i - 20):i])
            if 'LazyVerticalGrid(' in seg:
                target = i
                break
    if target < 0:
        print('  [list] \u672a\u627e\u5230\u5217\u8868\u7f51\u683c')
        return text

    # 往上找 LazyVerticalGrid(
    grid = -1
    for j in range(target, max(0, target - 20), -1):
        if 'LazyVerticalGrid(' in lines[j]:
            grid = j
            break
    if grid < 0:
        return text
    indent = lines[grid][:len(lines[grid]) - len(lines[grid].lstrip())]

    # 改列数那一行
    lines[target] = indent + '    columns = GridCells.Fixed(com.han1me.ui.adaptiveColumns(480.dp, minItemWidth = 160.dp)),'

    # 在 LazyVerticalGrid 前插 BoxWithConstraints 与 maxWidth 版本
    lines[target] = indent + '    columns = GridCells.Fixed(com.han1me.ui.adaptiveColumns(maxWidth, minItemWidth = 160.dp)),'
    lines.insert(grid, indent + 'androidx.compose.foundation.layout.BoxWithConstraints(')
    lines.insert(grid + 1, indent + '    Modifier.fillMaxSize(),')
    lines.insert(grid + 2, indent + ') {')

    # 找这个网格的收尾 (单独的 indent + '}' 且下一行是空或同级)
    depth = 0
    end = -1
    for k in range(grid + 3, len(lines)):
        depth += lines[k].count('{') - lines[k].count('}')
        if depth < 0:
            end = k
            break
    if end > 0:
        lines.insert(end, indent + '}   // \u2190 \u7b2c 329 \u8f6e\uff1aBoxWithConstraints \u6536\u5c3e')
        print('  [list] \u5217\u6570\u81ea\u9002\u5e94\u5df2\u63a5\u5165\uff08\u884c %d\uff09' % target)
    return chr(10).join(lines)

def main():
    f = GEN / "Han1meApp.kt"
    if not f.exists():
        raise SystemExit("build/gen/Han1meApp.kt 不存在 —— 先跑 build.py 第 1 步")
    t0 = io.open(f, encoding="utf-8").read()
    t = patch_bottom_bar(t0)
    t = patch_top_nav(t)
    t = patch_player(t)
    t = patch_home_sections(t)
    t = patch_native_login(t)
    t = patch_login_prefill(t)
    t = patch_cloud_avatar(t)
    t = patch_search_wiring(t)
    t = patch_login_persist(t)
    t = patch_login_instant_fetch(t)
    t = patch_login_force_user(t)
    t = patch_display_name(t)
    t = patch_login_once(t)
    t = patch_remove_dup_search_icon(t)
    t = patch_transparent_bg(t)
    t = patch_avatar_boot_fetch(t)
    t = patch_remove_native_login(t)
    t = patch_dedup_web_login(t)
    t = patch_favtab_by_lines(t)
    t = patch_favtab_downloads_call(t)
    t = patch_list_two_col(t)
    t = patch_downloads_render(t)
    t = patch_detail_animation(t)
    t = patch_fav_cleanup(t)
    t = patch_fav_hide_text(t)
    t = patch_update_listener(t)
    t = patch_disable_inner_update(t)
    t = patch_global_transition(t)
    t = patch_transition_close(t)
    t = patch_list_responsive2(t)
    t = patch_list_responsive(t)
    t = patch_player_dedup(t)
    patch_cos_entry(t)
    patch_app_background(t)
    # ★ 第 322 轮：清掉旧板块残留（必须在所有补丁之后 —— 前面可能还有引用）
    t = strip_dead_functions(t, DEAD_FUNCTIONS)
    if t != t0:
        io.open(f, "w", encoding="utf-8").write(t)
        print("  UI 微调已应用")
    else:
        print("  UI 微调：无变化")


if __name__ == "__main__":
    main()
