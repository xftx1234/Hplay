#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 351 轮规则：javchu 直链 / 搜索页清理 / 首页搜索框 MD3 / 半透明统一。

接在 round350 之后、logwire 之前。每条规则都**幂等**（先判 new 是否已在）。
只改 build/gen/*.kt（生成产物，不手改）。

  1. patch_javchu_srcok   javchu 媒体预检带**页面域** Referer（原来是自己引自己 → 恒 false）
  2. patch_home_search   首页顶部搜索框：去掉 🔍 小图标，按 MD3 SearchBar 重构
  3. patch_panel_alpha   聚合面板 0.82 → 0.72（与 app 根容器同值，消色差）
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GEN = ROOT / "build" / "gen"

NL = chr(10)


def _once(text, old, new, tag):
    n = text.count(old)
    if n != 1:
        raise SystemExit("[第351轮规则失配] %s：命中 %d 次（应为 1）：%r" % (tag, n, old[:100]))
    return text.replace(old, new, 1)


# ---------------------------------------------------------------- 1. javchu srcOk

SRCOK_OLD = NL.join([
    "    private fun srcOk(u: String): Boolean = runCatching {",
    "val req = Request.Builder().url(u)",
    '.header("User-Agent", UA)',
    '.header("Referer", originOf(u) + "/")',
    '.header("Range", "bytes=0-0")',
    ".get().build()",
    "shortTimeoutHttp(6_000L).newCall(req).execute().use { r ->",
    "r.isSuccessful || r.code == 206",
    "}",
    "}.getOrDefault(false)",
])

SRCOK_NEW = NL.join([
    "    private fun srcOk(u: String, pageUrl: String = \"\"): Boolean = runCatching {",
    "        // ★★★ 第 351 轮（用户报「javchu.com 的原生直链接播放接口没了」）：**两个真因都在这条预检里**。",
    "        //",
    "        // 实测（2026-11-27，本机直连 / 内置节点各一发，同一批 6 个样本）：",
    "        //   · t33.cdn2020.com 的 index.m3u8 直连 200 + 完整 #EXTM3U，分片 0000.ts 191 KB 也 200；",
    "        //   · 同一地址经内置节点（美西 35.89.113.126）/ 局域网代理 一律 **451**（Server: Quantum）。",
    "        //     451 是「因法律原因不可用」，只对外国 IP 生效 —— 节点出口被拒，不是站点删了源。",
    "        //",
    "        // 而这里原来写 `originOf(u) + \"/\"`，对 m3u8 等于 `https://t33.cdn2020.com/`（自己引自己）。",
    "        // 站点侧要求的其实是**页面域** javchu.com 的 Referer。两个错叠在一起 ⇒ srcOk 恒 false",
    "        // ⇒ jsSources 为空 ⇒ 界面「该站点未取得可验证直链」⇒ 用户以为接口没了。",
    "        //",
    "        // 现在：Referer 优先用**页面 URL 的 origin**（调用方传进来），没传才退回媒体域。",
    "        // 代理那边的真因在 GlobalProxy.ALWAYS_DIRECT（cdn2020.com 已加进直连白名单）。",
    "        val ref = runCatching {",
    "            java.net.URI(pageUrl).let { it.scheme + \"://\" + it.host + \"/\" }",
    "        }.getOrNull()?.takeIf { it.length > 8 } ?: (originOf(u) + \"/\")",
    "        val req = Request.Builder().url(u)",
    "            .header(\"User-Agent\", UA)",
    "            .header(\"Referer\", ref)",
    "            .header(\"Range\", \"bytes=0-0\")",
    "            .get().build()",
    "        shortTimeoutHttp(8_000L).newCall(req).execute().use { r ->",
    "            // 200/206 都算通：m3u8 清单对小 Range 常回 200（整份返回）",
    "            r.isSuccessful || r.code == 206",
    "        }",
    "    }.getOrDefault(false)",
])

CALL_OLD = "        val jsSources: List<Pair<Int, String>> = jsRaw.filter { srcOk(it) }.map { 0 to it }"

CALL_NEW = NL.join([
    "        // 第 351 轮：把**页面 URL** 传进预检 —— 它决定 Referer 用哪个域（见 srcOk 的注释）。",
    "        val jsSources: List<Pair<Int, String>> =",
    "            jsRaw.filter { srcOk(it, v.url) }.map { 0 to it }",
])


def patch_javchu_srcok(t):
    if "第 351 轮（用户报「javchu.com 的原生直链接播放接口没了」）" in t:
        return t
    if SRCOK_OLD not in t:
        raise SystemExit("[第351轮规则失配] srcOk 未找到")
    t = t.replace(SRCOK_OLD, SRCOK_NEW, 1)
    return _once(t, CALL_OLD, CALL_NEW, "srcOk 调用点")


# ---------------------------------------------------------------- 2. 首页搜索框 MD3

HOME_SEARCH_OLD = NL.join([
    "            Row(",
    "        // ★ 第 345 轮（用户要求）：**去掉「搜索影片 / 演员」提示文字，只留一个居中的放大镜**。",
    "        // 原来那行 12sp 提示在 34dp 胶囊里基线偏下、和被压扁的 emoji 挤在一起，",
    "        // 真机看就是「小字样排版不对」。去掉文字后用 horizontalArrangement 居中，",
    "        // 否则图标会顶在左内边距上。",
    "                verticalAlignment = Alignment.CenterVertically,",
    "                horizontalArrangement = Arrangement.Center,",
    "                modifier = Modifier.weight(1f).height(34.dp)",
    "                    .clip(RoundedCornerShape(17.dp))",
    "                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)",
    "                    .clickable { onSearchToggle?.invoke() }",
    "                    .padding(horizontal = 12.dp),",
    "            ) {",
    '                Text("\\uD83D\\uDD0D", fontSize = 13.sp)',
    "            }",
    "            Spacer(Modifier.width(8.dp))",
    "            Box(",
    "                Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))",
    "                    .background(MaterialTheme.colorScheme.primary)",
    "                    .clickable { onSearchToggle?.invoke() }",
    "                    .padding(horizontal = 14.dp),",
    "                contentAlignment = Alignment.Center,",
    '            ) { Text("搜索", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp) }',
    "        }",
])

HOME_SEARCH_NEW = NL.join([
    "            // ★★★ 第 351 轮（用户要求「首页顶部搜索框内删除小图标，拿 md3 风格重构」）：",
    "            //",
    "            // 原来是一颗 34dp 的胶囊，里面只有一把 🔍 emoji（第 345 轮按用户要求去掉了提示文字）。",
    "            // 现在按 M3 SearchBar 的规范重做：",
    "            //   · 容器 surfaceContainerHigh、**全圆角**（半高即半径）、高 44dp",
    "            //     （M3 SearchBar 展开 56dp、docked 48dp；这里在 52dp 顶栏里取 44dp 不挤）；",
    "            //   · **不放图标**（用户明确要求删掉那颗小图标），改放 onSurfaceVariant 的占位文案 ——",
    "            //     空胶囊没有任何信息量，M3 的搜索栏本来就用 placeholder 表达语义；",
    "            //   · 文字用 bodyMedium（13sp）+ 左内边距 16dp，与 M3 的 input 内距一致。",
    "            Box(",
    "                Modifier.weight(1f).height(44.dp)",
    "                    .clip(RoundedCornerShape(22.dp))",
    "                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)",
    "                    .clickable { onSearchToggle?.invoke() }",
    "                    .padding(horizontal = 16.dp),",
    "                contentAlignment = Alignment.CenterStart,",
    "            ) {",
    "                Text(",
    '                    // 文案不能太长：44dp 高的胶囊在 1080p 竖屏只放得下 ~9 个汉字，',
    '                    // 上一版写「搜索影片、演员或标签」在真机上被截成「搜索影片、演员或标」。',
    '                    "搜索影片、演员",',
    "                    color = MaterialTheme.colorScheme.onSurfaceVariant,",
    "                    fontSize = 13.sp,",
    "                    maxLines = 1,",
    "                )",
    "            }",
    "            Spacer(Modifier.width(8.dp))",
    "            // 右侧按钮：M3 的 filled tonal 形态（次要动作不用高饱和主色，",
    "            // 主色留给「搜索中」这类真正的强调态）",
    "            Box(",
    "                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))",
    "                    .background(MaterialTheme.colorScheme.primary)",
    "                    .clickable { onSearchToggle?.invoke() }",
    "                    .padding(horizontal = 18.dp),",
    "                contentAlignment = Alignment.Center,",
    '            ) { Text("搜索", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium) }',
    "        }",
])


def patch_home_search(t):
    if "首页顶部搜索框内删除小图标" in t:
        return t
    if HOME_SEARCH_OLD not in t:
        raise SystemExit("[第351轮规则失配] 首页搜索框未找到")
    return t.replace(HOME_SEARCH_OLD, HOME_SEARCH_NEW, 1)


# ---------------------------------------------------------------- 3. 半透明统一

ALPHA_OLD = NL.join([
    "    Column(Modifier.fillMaxSize()// ★ 2026-11-25（第 319 轮）：改半透明，让全局壁纸透出来",
    "        .background(BgColor.copy(alpha = 0.82f))) {",
])

ALPHA_NEW = NL.join([
    "    Column(Modifier.fillMaxSize()// ★ 2026-11-25（第 319 轮）：改半透明，让全局壁纸透出来",
    "        // ★★★ 第 351 轮（用户报「搜索页面上下有透明度色差，修正同步为 app 原始半透明度」）：",
    "        //",
    "        // 这里原来是 0.82，而 app 根容器是 0.72 —— 聚合面板比周围**深一档**，",
    "        // 同屏就是一条可见的水平色差；再加上顶栏原来还是不透明的，一共三道。",
    "        // 现在统一成 0.72（顶栏那层也在 HplaySearchBar 里改成同值），壁纸连续、没有接缝。",
    "        .background(BgColor.copy(alpha = 0.72f))) {",
])


def patch_panel_alpha(t):
    if "搜索页面上下有透明度色差" in t:
        return t
    if ALPHA_OLD not in t:
        raise SystemExit("[第351轮规则失配] 聚合面板背景未找到")
    return t.replace(ALPHA_OLD, ALPHA_NEW, 1)



# ---------------------------------------------------------------- 4. 搜索页只画一层

GST_OLD = "    Column(Modifier.fillMaxSize()) {" + NL + "        // ★★ 第 350 轮：换成带『高级』入口 + 生效条件条的顶栏（照参考实现 SearchAppBar）。"

GST_NEW = NL.join([
    "    // ★★★ 第 351 轮（用户报「搜索页面上下有透明度色差」）：**整页只画一层底色**。",
    "    //",
    "    // 原来顶栏背景=不透明 CardColor、聚合面板=0.82、外面根容器=0.72，三处各不相同。",
    "    // 把顶栏改成 0.72 之后色差仍在 —— 因为搜索页本身就是画在根容器 0.72 之上的**一层**，",
    "    // 0.72 再叠 0.72 合成是 1-(0.28×0.28)=0.92，壁纸只剩 8% 透出，肉眼就是「一块实心」。",
    "    //",
    "    // 正确解法：根部画一次 SEARCH_SCRIM（0.72），顶栏/条件条/面板全部透明。",
    "    // 只有一层，就没有「层数不同」这个变量，上下必然一致。",
    "    Column(Modifier.fillMaxSize().background(com.han1me.search.SEARCH_SCRIM)) {",
    "        // ★★ 第 350 轮：换成带『高级』入口 + 生效条件条的顶栏（照参考实现 SearchAppBar）。",
])

PANEL_BG_OLD = NL.join([
    "    Column(Modifier.fillMaxSize()// ★ 2026-11-25（第 319 轮）：改半透明，让全局壁纸透出来",
    "        // ★★★ 第 351 轮（用户报「搜索页面上下有透明度色差，修正同步为 app 原始半透明度」）：",
    "        //",
    "        // 这里原来是 0.82，而 app 根容器是 0.72 —— 聚合面板比周围**深一档**，",
    "        // 同屏就是一条可见的水平色差；再加上顶栏原来还是不透明的，一共三道。",
    "        // 现在统一成 0.72（顶栏那层也在 HplaySearchBar 里改成同值），壁纸连续、没有接缝。",
    "        .background(BgColor.copy(alpha = 0.72f))) {",
])

PANEL_BG_NEW = NL.join([
    "    Column(Modifier.fillMaxSize()// ★ 2026-11-25（第 319 轮）：改半透明，让全局壁纸透出来",
    "        // ★★★ 第 351 轮（第二次）：这里**必须完全透明**。",
    "        //",
    "        // 上一版把它从 0.82 改成 0.72 想「对齐根容器」，但根容器本身已经是 0.72，",
    "        // 再叠一层 0.72 的合成就成了 0.92（壁纸只剩 8%），于是面板比顶栏还实心 ——",
    "        // 色差不但没消，反而换了个地方。",
    "        // 现在：底色统一由 GlobalSearchTab 根部那**一层** SEARCH_SCRIM 提供，",
    "        // 面板自己不再叠任何背景。",
    "        ) {",
])


def patch_search_scrim(t):
    if "整页只画一层底色" in t:
        return t
    if GST_OLD not in t:
        raise SystemExit("[第351轮规则失配] GlobalSearchTab 根 Column 未找到")
    return t.replace(GST_OLD, GST_NEW, 1)


def patch_panel_transparent(t):
    if "这里**必须完全透明**" in t:
        return t
    if PANEL_BG_OLD not in t:
        raise SystemExit("[第351轮规则失配] 聚合面板背景（第二版）未找到")
    return t.replace(PANEL_BG_OLD, PANEL_BG_NEW, 1)

RULES = [
    ("X99.kt", patch_javchu_srcok),
    ("Han1meApp.kt", patch_home_search),
    ("Han1meApp.kt", patch_search_scrim),
    ("X99Board.kt", patch_panel_alpha),
    ("X99Board.kt", patch_panel_transparent),
]


def main():
    touched = []
    for name, fn in RULES:
        p = GEN / name
        if not p.exists():
            continue
        t = p.read_text(encoding="utf-8")
        nt = fn(t)
        if nt != t:
            p.write_text(nt, encoding="utf-8")
            touched.append(name)
    if touched:
        print("[第351轮] 已应用：" + "、".join(touched))
    else:
        print("[第351轮] 无需改动（幂等）")


if __name__ == "__main__":
    main()