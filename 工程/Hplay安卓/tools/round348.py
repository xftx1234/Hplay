# -*- coding: utf-8 -*-
"""第 348 轮：**动画全面校准 + 关注即时化** 的后置规则。

为什么单独一个文件而不是塞进 postpatch.py：
  postpatch.py 已经 2800+ 行、十几个 patch_ 函数共用一份 RULES 表，
  再往里加会让这一轮的改动在 diff 里看不出来。这个文件只管第 348 轮的四件事，
  由 build.py 在 postpatch 之后、uifinetune 之前调用一次。

四条规则：
  1. patch_subscribe_instant   关注按钮乐观更新（按下即翻转，失败回滚）
  2. patch_subscribe_prewarm   打开详情页时预热 WebView
  3. patch_item_anim           卡片列表项入场动画（Viewer AnimatedLazy 规格）
  4. patch_tab_direction       底栏 tab 切换按方向转场（Plus 方向 + Viewer 形状）
  5. patch_toast_motion        轻提示改成带进出的胶囊（Viewer GlobalToast 规格）
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GEN = ROOT / "build" / "gen"

NL = chr(10)
STAR = chr(0x2605)


def _once(text, old, new, tag):
    """old 必须恰好命中 1 次：这是安全网，误伤会立刻暴露。"""
    n = text.count(old)
    if n != 1:
        raise SystemExit("[第348轮规则失配] %s：命中 %d 次（应为 1）：%r" % (tag, n, old[:80]))
    return text.replace(old, new, 1)


# --------------------------------------------------------------------------
# 1. 关注按钮：乐观更新
# --------------------------------------------------------------------------

SUB_BTN_FILE = ROOT / "tools" / "subscribe_button.kt.txt"

SUB_BTN_OLD = NL.join([
    '                        if (ar.canSubscribe) {',
    '                            val subbed = remember(ar.artistId, ar.isSubscribed) { mutableStateOf(ar.isSubscribed) }',
    '                            Text(',
    '                                if (subbed.value) "已关注" else "关注",',
    '                                color = Color.White, fontSize = 12.sp,',
    '                                modifier = Modifier',
    '                                    .clip(RoundedCornerShape(15.dp))',
    '                                    .background(if (subbed.value) CardColorSoft else AccentRed)',
    '                                    .clickable(enabled = !busy) {',
    '                                        busy = true',
    '                                        scope.launch(Dispatchers.IO) {',
    '                                            try {',
    '                                                val now = com.han1me.lab.HplaySubscribe.toggle(',
    '                                                    d.video.url,',
    '                                                    ar.userId, ar.artistId, !subbed.value,',
    '                                                )',
])



SUB_END = NL.join([
    '                                    .padding(horizontal = 14.dp, vertical = 6.dp),',
    '                            )',
    '                        }',
])


def patch_subscribe_instant(text):
    """关注按钮改成**乐观更新**（第 348 轮 · 用户报「点击后会界面卡几秒」）。

    第 347 轮的做法：点击 -> 后台 toggle()（OkHttp 四形态 + WebView 提交，实测 4-7 秒）
    -> 才改 subbed.value。用户看到的就是「按钮几秒没反应」，于是以为没点中、再点一次。

    本轮：按下**立刻**翻转，后台提交，成功用站点返回值校正、失败回滚。
    即使网络要 0.35 秒，视觉上也是瞬时的 —— 这是用户要的"瞬时关注成功"。

    同时把全局面板 busy 换成独立的 subBusy：原来 busy=true 会把
    「收藏 / 稍后看 / 外部播放 / 复制直链」一起禁用几秒。
    """
    if "subBusy" in text:
        return text
    i = text.find(SUB_BTN_OLD)
    if i < 0:
        return text
    j = text.find(SUB_END, i)
    if j < 0:
        return text
    body = SUB_BTN_FILE.read_text(encoding="utf-8")
    tail = text[j + len(SUB_END):]
    tail = tail.lstrip(NL) if tail.startswith(NL) else tail
    return text[:i] + body + NL + tail


# --------------------------------------------------------------------------
# 2. 详情页预热 WebView
# --------------------------------------------------------------------------

PREWARM_OLD = "        val seq = ++detailSeq"

PREWARM_NEW = NL.join([
    '        val seq = ++detailSeq',
    '        // ' + STAR + ' 第 348 轮：**关注用的 WebView 在这里预热**（用户报「点关注卡几秒」）。',
    '        //',
    '        // 第 347 轮是在点击那一刻才 new WebView + loadUrl(详情页)，而这个页面挂着',
    '        // hCaptcha / jsdelivr / gstatic / 广告等十几个第三方资源，全部经节点转发，',
    '        // 加载要 2-4 秒。用户在简介里读几秒、点关注时才发现这 4 秒的空白。',
    '        //',
    '        // 现在：进详情页就后台把它加载好。用户读完简介再点时，只差一次 fetch（约 0.25 秒）。',
    '        // 预热失败/被回收都不影响别的功能，点关注时会自动重建。',
    '        runCatching { com.han1me.lab.HplaySubscribe.prewarm(v.url) }',
])


def patch_subscribe_prewarm(text):
    """打开详情页时预热关注用的 WebView。"""
    if "HplaySubscribe.prewarm" in text:
        return text
    if text.count(PREWARM_OLD) != 1:
        return text
    return text.replace(PREWARM_OLD, PREWARM_NEW, 1)


PREWARM_FIN_OLD = "        val seq = ++detailSeq"
CLOSE_OLD = "                onClose = { detail = null },"
CLOSE_NEW = NL.join([
    '                onClose = {',
    '                    detail = null',
    '                    // ' + STAR + ' 第 348 轮：离开详情页 -> 25 秒后回收预热的 WebView。',
    '                    // 不立刻销毁是因为用户常常点开一个、退出来又点另一个（相关影片），',
    '                    // 立刻销毁等于每次都重新付那 2-4 秒的加载。',
    '                    runCatching { com.han1me.lab.HplaySubscribe.releaseLater() }',
    '                },',
])


def patch_subscribe_release(text):
    """离开详情页时延迟回收预热的 WebView。"""
    if "HplaySubscribe.releaseLater" in text:
        return text
    if text.count(CLOSE_OLD) != 1:
        return text
    return text.replace(CLOSE_OLD, CLOSE_NEW, 1)

# --------------------------------------------------------------------------
# 3. 列表项入场动画
# --------------------------------------------------------------------------

# 首页横向卡片行
ITEM_HOME_OLD = '                    items(sec.cards.distinctBy { it.url }, key = { sec.title + "#" + it.url }) { v ->\n                        SiteCard(v, width = 206.dp, onClick = { onOpenVideo(v) })\n                    }'
ITEM_HOME_NEW = NL.join([
    '                    // ' + STAR + ' 第 348 轮：卡片入场动画（照 Han1meViewer 的 AnimatedLazy）。',
    '                    // alpha 100ms + scale 0.985->1 180ms + 档位 12ms x min(i,6)。',
    '                    itemsA(sec.cards.distinctBy { it.url }, key = { sec.title + "#" + it.url }) { v, i ->',
    '                        SiteCard(v, width = 206.dp, onClick = { onOpenVideo(v) },',
    '                            modifier = Modifier.itemIn(i))',
    '                    }',
])

# 板块网格（X99Board）
ITEM_BOARD_OLD = '                items(cards, key = { it.url }) { v ->'
ITEM_BOARD_NEW = NL.join([
    '                // ' + STAR + ' 第 348 轮：卡片入场动画（同 Viewer AnimatedLazy 规格）',
    '                itemsA(cards, key = { it.url }) { v, i ->',
])

ITEM_BOARD_CALL_OLD = '                    SiteCard(v, width = null, onClick = { onOpenDetail(v) })\n                }'
ITEM_BOARD_CALL_NEW = NL.join([
    '                    SiteCard(v, width = null, onClick = { onOpenDetail(v) },',
    '                        modifier = Modifier.itemIn(i))',
    '                }',
])

# 列表页网格（Han1meApp 的 ListContent）
ITEM_LIST_OLD = '                items(cards, key = { it.id }) { v ->\n                    SiteCard(v, width = null, onClick = { onOpenVideo(v) })\n                }'
ITEM_LIST_NEW = NL.join([
    '                // ' + STAR + ' 第 348 轮：卡片入场动画（同 Viewer AnimatedLazy 规格）',
    '                itemsA(cards, key = { it.id }) { v, i ->',
    '                    SiteCard(v, width = null, onClick = { onOpenVideo(v) },',
    '                        modifier = Modifier.itemIn(i))',
    '                }',
])

# 详情页相关影片
ITEM_REL_OLD = '                    items(d.related.take(16), key = { it.url }) { v ->\n                        SiteCard(v, width = 158.dp, onClick = { onOpenVideo(v) })\n                    }'
ITEM_REL_NEW = NL.join([
    '                    itemsA(d.related.take(16), key = { it.url }) { v, i ->',
    '                        SiteCard(v, width = 158.dp, onClick = { onOpenVideo(v) },',
    '                            modifier = Modifier.itemIn(i))',
    '                    }',
])


def patch_item_anim(text, name):
    """卡片列表项入场动画。

    只用 itemsA 换掉 items —— 三处都要 index 才能算阶梯延迟，
    而 items(list, key) 那个便捷重载不给 index（见 ItemsAnim.kt 的注释）。
    """
    if name == "Han1meApp.kt":
        if "itemsA(sec.cards.distinctBy" not in text and ITEM_HOME_OLD in text:
            text = text.replace(ITEM_HOME_OLD, ITEM_HOME_NEW, 1)
        if "itemsA(cards, key = { it.id })" not in text and ITEM_LIST_OLD in text:
            text = text.replace(ITEM_LIST_OLD, ITEM_LIST_NEW, 1)
        if "itemsA(d.related.take(16)" not in text and ITEM_REL_OLD in text:
            text = text.replace(ITEM_REL_OLD, ITEM_REL_NEW, 1)
    if name == "X99Board.kt":
        if "itemsA(cards, key = { it.url })" not in text:
            if ITEM_BOARD_OLD in text and ITEM_BOARD_CALL_OLD in text:
                text = text.replace(ITEM_BOARD_OLD, ITEM_BOARD_NEW, 1)
                text = text.replace(ITEM_BOARD_CALL_OLD, ITEM_BOARD_CALL_NEW, 1)
    return text


# --------------------------------------------------------------------------
# 4. SiteCard 增加 modifier 参数
# --------------------------------------------------------------------------

CARD_SIG_OLD = 'internal fun SiteCard(v: HanimeVideo, width: androidx.compose.ui.unit.Dp?, onClick: () -> Unit) {\n    Column(\n        modifier = (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())'

CARD_SIG_NEW = NL.join([
    'internal fun SiteCard(',
    '    v: HanimeVideo,',
    '    width: androidx.compose.ui.unit.Dp?,',
    '    onClick: () -> Unit,',
    '    // ' + STAR + ' 第 348 轮：外部注入 Modifier（入场动画走这里）。',
    '    // 不给默认值以外的语义变化，旧调用点一行不用改。',
    '    modifier: Modifier = Modifier,',
    ') {',
    '    Column(',
    '        modifier = modifier',
    '            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())',
])


def patch_card_modifier(text):
    """SiteCard 接受外部 Modifier（入场动画的挂载点）。"""
    if "modifier: Modifier = Modifier,\n) {" in text and "fun SiteCard(" in text:
        if CARD_SIG_OLD not in text:
            return text
    if CARD_SIG_OLD not in text:
        return text
    return text.replace(CARD_SIG_OLD, CARD_SIG_NEW, 1)

# --------------------------------------------------------------------------
# 5. 底栏 tab 切换：方向感知转场
# --------------------------------------------------------------------------

TAB_SPEC_OLD = '                    com.han1me.ui.HplayTransition.enter\n                },'


TAB_SPEC_NEW = NL.join([
    '                    // ' + STAR + ' 第 348 轮：**方向感知**（两个参考源码的合成）。',
    '                    //',
    '                    // 形状与时长照 Viewer（MainNavHost）：新页滑入 + 淡入 450ms，',
    '                    // 旧页滑 1/3 宽 + 缩到 0.9 + 300ms 淡出 —— 位移量小、帧开销低。',
    '                    // 方向照 Plus（app_shell.dart:44-46）：去右边的格子从右进，去左边从左进。',
    '                    // 原来固定用 enter，于是「收藏 -> 主页」也是从右边滑进来，与手指方位相反。',
    '                    com.han1me.ui.HplayTransition.tab(',
    '                        initialState, targetState, com.han1me.ui.HplayTransition.TAB_ORDER,',
    '                    )',
    '                },',
])


def patch_tab_direction(text):
    """底栏 tab 切换按方向转场。

    原来在 transitionSpec 里固定写 HplayTransition.enter —— 四个 tab 无论往哪边切，
    新页都从右边滑进来。Plus 的 app_shell.dart:44-46 是按 index 大小算方向的，
    这里照它补上；形状与时长仍是 Viewer 的。
    """
    if "HplayTransition.tab(" in text:
        return text
    if TAB_SPEC_OLD not in text:
        return text
    return text.replace(TAB_SPEC_OLD, TAB_SPEC_NEW, 1)


# --------------------------------------------------------------------------
# 6. 轻提示：带进出的胶囊（Viewer GlobalToast）
# --------------------------------------------------------------------------

TOAST_OLD = NL.join([
    '        toast?.let { msg ->',
    '            Box(',
    '                Modifier.fillMaxSize().padding(bottom = 48.dp),',
    '                contentAlignment = Alignment.BottomCenter,',
    '            ) {',
    '                Text(',
    '                    msg, color = Color.White, fontSize = 12.sp,',
    '                    modifier = Modifier',
    '                        .clip(RoundedCornerShape(16.dp))',
    '                        .background(Color(0xE61F1F23))',
    '                        .clickable { toast = null }',
    '                        .padding(horizontal = 14.dp, vertical = 8.dp),',
    '                )',
    '                LaunchedEffect(msg) {',
    '                    kotlinx.coroutines.delay(3200)',
    '                    toast = null',
    '                }',
    '            }',
    '        }',
])

TOAST_NEW = NL.join([
    '        // ---------- 轻提示 ----------',
    '        // ' + STAR + ' 第 348 轮：**带进出动画**（照 Han1meViewer 的 GlobalToast）。',
    '        //',
    '        // 原来是硬切：出现与消失都是瞬间，看着像"闪了一下"。',
    '        // 现在按 Viewer 的规格做成胶囊：圆角 50%、背景 0.8 透明、从下方整高滑入 + 淡入。',
    '        // 时长 220ms（进入）/ 180ms（退出）—— Viewer 用的是默认 spring(400f)，',
    '        // Compose 里那条约等于 200ms，取 220/180 与它的观感一致且更省帧。',
    '        //',
    '        // 关键：**用 MutableTransitionState 而不是 AnimatedVisibility(visible)**。',
    '        // 后者在 visible 变 false 时会立刻把内容从组合里摘掉，退出动画根本看不见。',
    '        // 必须先让退出动画跑完、再清 toast（Viewer GlobalToast.kt:280-288 同一手法）。',
    '        val toastShown = remember { androidx.compose.animation.core.MutableTransitionState(false) }',
    '        // 退出动画期间 toast 已经是 null 了，正文不能跟着清空（否则胶囊里空一段）。',
    '        // 留下最后一条，等退出动画跑完自然被盖住。',
    '        var toastText by remember { mutableStateOf("") }',
    '        LaunchedEffect(toast) {',
    '            if (toast != null) {',
    '                toastText = toast.orEmpty()',
    '                toastShown.targetState = true',
    '                kotlinx.coroutines.delay(com.han1me.ui.HplayMotion.TOAST_HOLD_MS)',
    '                toast = null',
    '                toastShown.targetState = false',
    '            } else {',
    '                toastShown.targetState = false',
    '            }',
    '        }',
    '        androidx.compose.animation.AnimatedVisibility(',
    '            visibleState = toastShown,',
    '            enter = androidx.compose.animation.fadeIn(',
    '                animationSpec = androidx.compose.animation.core.tween(',
    '                    com.han1me.ui.HplayMotion.ms(com.han1me.ui.HplayMotion.TOAST_IN),',
    '                ),',
    '            ) + androidx.compose.animation.slideInVertically(',
    '                animationSpec = androidx.compose.animation.core.tween(',
    '                    com.han1me.ui.HplayMotion.ms(com.han1me.ui.HplayMotion.TOAST_IN),',
    '                ),',
    '                initialOffsetY = { it },',
    '            ),',
    '            exit = androidx.compose.animation.fadeOut(',
    '                animationSpec = androidx.compose.animation.core.tween(',
    '                    com.han1me.ui.HplayMotion.ms(com.han1me.ui.HplayMotion.TOAST_OUT),',
    '                ),',
    '            ) + androidx.compose.animation.slideOutVertically(',
    '                animationSpec = androidx.compose.animation.core.tween(',
    '                    com.han1me.ui.HplayMotion.ms(com.han1me.ui.HplayMotion.TOAST_OUT),',
    '                ),',
    '                targetOffsetY = { it },',
    '            ),',
    '        ) {',
    '            Box(',
    '                Modifier.fillMaxSize().padding(bottom = 48.dp),',
    '                contentAlignment = Alignment.BottomCenter,',
    '            ) {',
    '                Text(',
    '                    toastText, color = Color.White, fontSize = 12.sp,',
    '                    modifier = Modifier',
    '                        .clip(RoundedCornerShape(50))',
    '                        .background(Color(0xCC1F1F23))',
    '                        .clickable {',
    '                            toast = null',
    '                            toastShown.targetState = false',
    '                        }',
    '                        .padding(horizontal = 18.dp, vertical = 9.dp),',
    '                )',
    '            }',
    '        }',
])


def patch_toast_motion(text):
    """轻提示进出动画（Viewer GlobalToast 规格）。"""
    if "toastShown" in text:
        return text
    if TOAST_OLD not in text:
        return text
    return text.replace(TOAST_OLD, TOAST_NEW, 1)


# --------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------



# --------------------------------------------------------------------------
# 7. 去掉 bodyFade 与 AnimatedContent 的**双重动画**
# --------------------------------------------------------------------------

BODYFADE_OLD = NL.join([
    '        val __viewKey = board + "|" + tab + "|" + mode + "|" + showMine + "|" + showNotices + "|" + showSearch + "|" + x99Focus',
    '        androidx.compose.runtime.LaunchedEffect(__viewKey) {',
    '            bodyFade.snapTo(0f)',
    '            bodyFade.animateTo(1f, androidx.compose.animation.core.tween(durationMillis = 420))',
    '        }',
])

BODYFADE_NEW = NL.join([
    '        // ' + STAR + ' 第 348 轮：**去掉与 AnimatedContent 的双重动画**。',
    '        //',
    '        // 第 55/57 轮给整块内容挂了一个 alpha 淡入（key 含 board/tab/mode），',
    '        // 第 314 轮又在同一个位置上加了 AnimatedContent 转场 ——',
    '        // 于是切 tab 时**两层动画同时跑**：内容一边滑一边整体淡入，',
    '        // 旧页也在淡出，三层 alpha 叠加，真机看起来是"叠影 / 发虚"。',
    '        //',
    '        // 本轮把 bodyFade 的 key 收窄到**只有覆盖层开关**（顶栏搜索、我的、提示、',
    '        // 板块弹窗）—— 这些没有 AnimatedContent 管，淡入还是有意义的。',
    '        // tab / board / mode 的变化交给 AnimatedContent 一处负责，不再叠加。',
    '        //',
    '        // 时长也从 420ms 收到 260ms：覆盖层淡入是"局部补充"，不该比页面转场还慢。',
    '        val __viewKey = showMine.toString() + "|" + showNotices + "|" + showSearch + "|" + x99Focus',
    '        androidx.compose.runtime.LaunchedEffect(__viewKey) {',
    '            bodyFade.snapTo(0f)',
    '            bodyFade.animateTo(1f, androidx.compose.animation.core.tween(',
    '                durationMillis = com.han1me.ui.HplayMotion.ms(260),',
    '            ))',
    '        }',
])


def patch_body_fade_key(text):
    """去掉 bodyFade 与 AnimatedContent 的叠加。"""
    if "只有覆盖层开关" in text:
        return text
    if BODYFADE_OLD not in text:
        return text
    return text.replace(BODYFADE_OLD, BODYFADE_NEW, 1)




# --------------------------------------------------------------------------
# 8. 加载图标：转圈 -> 跳动小图标（第 349 轮）
# --------------------------------------------------------------------------

SPIN_CASES = [
    # (文件, 原文, 替换, 说明)
    ("Han1meApp.kt",
     "                CircularProgressIndicator(color = AccentRed)\n            }",
     "                com.han1me.ui.HplayLoadMark(markSize = 34.dp)\n            }",
     "详情加载态"),
    ("Han1meApp.kt",
     "        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentRed) }\n        return",
     "        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { com.han1me.ui.HplayLoadMark(markSize = 34.dp) }\n        return",
     "首页加载态"),
    ("Han1meApp.kt",
     "                    CircularProgressIndicator(color = AccentRed)\n                }\n            }\n            page == null || page.items.isEmpty() -> {",
     "                    com.han1me.ui.HplayLoadMark(markSize = 34.dp)\n                }\n            }\n            page == null || page.items.isEmpty() -> {",
     "预告页加载态"),
    ("Han1meApp.kt",
     "            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentRed) }\n        } else {",
     "            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { com.han1me.ui.HplayLoadMark(markSize = 34.dp) }\n        } else {",
     "列表页加载态"),
    ("X99Board.kt",
     "                    CircularProgressIndicator(color = AccentRed)\n                    if (picking) {",
     "                    com.han1me.ui.HplayLoadMark(markSize = 34.dp)\n                    if (picking) {",
     "板块测速态"),
    ("X99Board.kt",
     "                        CircularProgressIndicator(color = AccentRed, modifier = Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)",
     "                        com.han1me.ui.HplayLoadMark(markSize = 14.dp)",
     "读分类内联"),
    ("X99Board.kt",
     "                            CircularProgressIndicator(color = AccentRed, modifier = Modifier.height(12.dp).width(12.dp), strokeWidth = 2.dp)",
     "                            com.han1me.ui.HplayLoadMark(markSize = 12.dp)",
     "翻页脚内联"),
]


def patch_spinners(text, name):
    """把所有 LoadMark 位置从转圈换成跳动图标。

    用户本轮要求「所有页面的加载图标从转圈改成如图」。逐个锚点替换，
    不用全局 replace —— CircularProgressIndicator 在不同位置的尺寸与上下文都不同，
    全局换会把大小和间距一并毁掉。
    """
    for f, old, new, _why in SPIN_CASES:
        if f != name:
            continue
        if new in text:
            continue
        if old not in text:
            continue
        text = text.replace(old, new, 1)
    return text


def apply(text, name):
    text = patch_spinners(text, name)
    if name == "Han1meApp.kt":
        text = patch_subscribe_instant(text)
        text = patch_subscribe_prewarm(text)
        text = patch_subscribe_release(text)
        text = patch_card_modifier(text)
        text = patch_item_anim(text, name)
        text = patch_tab_direction(text)
        text = patch_toast_motion(text)
        text = patch_body_fade_key(text)
    if name == "X99Board.kt":
        text = patch_item_anim(text, name)
    return text


def main():
    changed = 0
    for f in sorted(GEN.glob("*.kt")):
        old = f.read_text(encoding="utf-8")
        new = apply(old, f.name)
        if new != old:
            f.write_text(new, encoding="utf-8", newline="")
            print("  第348轮规则 ->", f.name)
            changed += 1
    print("  第348轮规则：改动 %d 个文件" % changed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())