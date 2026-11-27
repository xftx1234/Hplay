# -*- coding: utf-8 -*-
'''第 350 轮：转场回到 Viewer / 搜索页重构 / 下载页搬出 Column / 更新与视频下载提速。

为什么单独一个文件：postpatch.py 已 3000 行，本轮改动分散在生成壳与多个 src 文件里，
塞进去就看不出这一轮改了什么。build.py 在 round348.py 之后调用一次。

四组规则：
  1. patch_downloads_overlay  下载页从 Column 里搬出来（『点击无响应』的真因）
  2. patch_search_bar         搜索顶栏换成带『高级』入口的那一版
  3. patch_search_state       未搜索时显示最近搜索
  4. patch_search_url         doSearch 用 HplaySearchFilter 拼真实参数
'''

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GEN = ROOT / 'build' / 'gen'

NL = chr(10)


def _once(text, old, new, tag):
    n = text.count(old)
    if n != 1:
        raise SystemExit('[第350轮规则失配] %s：命中 %d 次（应为 1）：%r' % (tag, n, old[:80]))
    return text.replace(old, new, 1)


ANIM_START = NL.join([
    '        androidx.compose.animation.AnimatedVisibility(',
    '            visible = showDownloads,',
])

COLS_CLOSE = '            }  // AnimatedContent 闭合'

NEW_CLOSE = '            }  // Column 闭合（★ 第 350 轮：原注释写 AnimatedContent，其实它早在上面就闭合了）'

WHY = NL.join([
    '',
    '        // ★★★ 第 350 轮修（用户报『下载板块点击无响应失灵』）：把下载页从 Column 里搬出来。',
    '        //',
    '        // 根因：这一整块原来写在上面那个 Column 内部，而 Column 的第一个子项是',
    '        // AnimatedContent(...) 的 tab 内容 —— 它内部用 fillMaxSize。',
    '        // Column 按剩余高度测量子项：第一个子项吃掉全部高度之后，后面这个',
    '        // fillMaxSize 的 AnimatedVisibility 拿到的可用高度就是 0，于是它虽然真的被',
    '        // 组合了（点击也真的把 showDownloads 置成了 true），但画出来是 0 高 ——',
    '        // 用户看到的就是『点了没反应』。',
    '        //',
    '        // 证据（真机）：点『本地下载』后按一次返回，退出确认没有弹出（说明',
    '        // showDownloads 确实是 true、被 BackHandler 消费掉了），界面上什么也没多出来。',
    '        // 其它整屏子页（信息 / 打卡 / Getchu / 订阅 / 预告）都写在 Box 这一层，',
    '        // 所以它们一直正常 —— 只有下载页被落在了 Column 里。',
])


def patch_downloads_overlay(text):
    '''把下载页那一段从 Column 内部搬到 Column 外面（Box 直接子节点）。

    用「起止标记 + 原文切片」而不是整段字面量：那段里有 4 处全角注释，
    写成 Python 字面量极易在引号/转义上出错（本会话已踩过）。切片法对注释内容免疫，
    只要起止标记还在就一定能搬对。
    '''
    if '把下载页从 Column 里搬出来' in text:
        return text
    i = text.find(ANIM_START)
    if i < 0:
        raise SystemExit('[第350轮] 下载页 AnimatedVisibility 起点未找到')
    j = text.find(COLS_CLOSE, i)
    if j < 0:
        raise SystemExit('[第350轮] AnimatedContent 闭合锚点未找到')
    block = text[i:j].strip(NL)
    head = text[:i]
    tail = text[j + len(COLS_CLOSE):]
    return head + NEW_CLOSE + NL + WHY + NL + block + NL + tail

SEARCHROW_OLD = NL.join([
    '        SearchRow(',
    '            query = query,',
    '            onQuery = onQuery,',
    '            onSearch = {',
    '                onOfficial()',
    '                boardTick++',
    '                showBoards = true',
    '            },',
    '            onClose = onResultsBack,',
    '        )',
])

SEARCHROW_NEW = NL.join([
    '        // ★★ 第 350 轮：换成带『高级』入口 + 生效条件条的顶栏（照参考实现 SearchAppBar）。',
    '        com.han1me.search.HplaySearchBar(',
    '            query = query,',
    '            onQuery = onQuery,',
    '            onSearch = {',
    '                onOfficial()',
    '                boardTick++',
    '                showBoards = true',
    '            },',
    '            onBack = onResultsBack,',
    '            // 高级搜索只服务 hanime 官方源：改条件后强制走官方通道并收起『全部板块』',
    '            onAdvancedSearch = {',
    '                onOfficial()',
    '                boardTick++',
    '                showBoards = false',
    '            },',
    '        )',
])


def patch_search_bar(text):
    if 'com.han1me.search.HplaySearchBar(' in text:
        return text
    if SEARCHROW_OLD not in text:
        raise SystemExit('[第350轮] SearchRow 调用点未找到')
    return text.replace(SEARCHROW_OLD, SEARCHROW_NEW, 1)


AGG_OLD = NL.join([
    '            // ★ 第 340 轮：**这里才是接线点**（上一版三个参数一个没传）。',
    '            AggregateSearchPanel(',
])

AGG_NEW = NL.join([
    '            // ★★ 第 350 轮：未输入时先给最近搜索（照参考实现 SearchScreen 的未搜索态）。',
    '            if (query.isBlank()) {',
    '                com.han1me.search.HplayRecentSearches(',
    '                    onPick = { q -> onQuery(q); onOfficial(); boardTick++; showBoards = false },',
    '                    modifier = Modifier.fillMaxWidth(),',
    '                )',
    '                Spacer(Modifier.height(6.dp))',
    '            }',
    '            AggregateSearchPanel(',
])


def patch_search_state(text):
    if 'HplayRecentSearches(' in text:
        return text
    if AGG_OLD not in text:
        raise SystemExit('[第350轮] 聚合面板调用点未找到')
    return text.replace(AGG_OLD, AGG_NEW, 1)


DOS_HEAD = '    fun doSearch() {'
DOS_END = NL + '    }' + NL

DOS_NEW = NL.join([
    '    /**',
    '     * **发起搜索**（第 350 轮重写：把高级搜索的过滤条件拼进 URL）。',
    '     *',
    '     * 参考实现 HanimeBaseService.kt:25-38 的 9 个 @Query 决定参数名，拼装规则集中在',
    '     * HplaySearchFilter.buildUrl（含 date 的年月拼法、broad=on 魔法串、tags[] 重复键）。',
    '     * 这里只负责取当前条件 + 走官方源 / 镜像两件事。',
    '     */',
    '    fun doSearch() {',
    '        val q = query.trim()',
    '        // 关键词同步进条件对象（高级搜索弹窗与顶栏是同一份 state）',
    '        com.han1me.search.HplaySearchState.setQuery(q)',
    '        val f = com.han1me.search.HplaySearchState.filter',
    '        // 没词也没过滤 = 什么都不用搜（参考实现同样是直接 return）',
    '        if (q.isEmpty() && !f.hasFilters) return',
    '        com.han1me.log.AppLog.act("search", q.take(60) + " " + f.summary())',
    '        val u = if (mirror) {',
    '            // 镜像站不一定认这套参数：只带关键词（其余过滤保持旧行为，不拼不存在的参数）',
    '            X99.mirrorSearchUrl(hanimeLine, q, 1)',
    '        } else {',
    '            f.buildUrl(HanimeClient.BASE)',
    '        }',
    '        com.han1me.search.HplaySearchState.edit { it.query = q }',
    '        loadList(ListKind.SEARCH, u, com.han1me.search.HplaySearchState.title(), 1)',
    '    }',
])


def patch_search_url(text):
    '''把 doSearch 换成会拼过滤参数的那一版。

    用切片而不是字面量比对：旧实现那一行标题里有全角冒号与模板串，
    写成 Python 字面量两次都栽在转义上。起止标记（函数签名 + 紧随的四个空格加右括号）稳定。
    '''
    if 'HplaySearchState.filter' in text:
        return text
    i = text.find(DOS_HEAD)
    if i < 0:
        raise SystemExit('[第350轮] doSearch 未找到')
    j = text.find(DOS_END, i)
    if j < 0:
        raise SystemExit('[第350轮] doSearch 结束标记未找到')
    return text[:i] + DOS_NEW + NL + text[j + len(DOS_END):]




# --------------------------------------------------------------------------
# 5. 用户页资料卡：已登录不再弹登录框，改开「我的账户」页
# --------------------------------------------------------------------------

ACCOUNT_ANCHOR = '                onAccountClick = { onLogin() },'

ACCOUNT_NEW = NL.join([
    '                // ★★★ 第 350 轮（用户报「已登录后点击登录区域不再显示登录弹窗」）：',
    '                // 已登录 -> 进「我的账户」资料编辑页（照 Viewer 的 AccountRoute）；',
    '                // 未登录 -> 才弹登录框。原来是无条件 onLogin()，已登录也弹。',
    '                onAccountClick = {',
    '                    if (user == null) onLogin() else onOpenAccount()',
    '                },',
])


def patch_account_click(text):
    if 'onOpenAccount()' in text:
        return text
    if ACCOUNT_ANCHOR not in text:
        raise SystemExit('[第350轮] onAccountClick 未找到')
    return text.replace(ACCOUNT_ANCHOR, ACCOUNT_NEW, 1)


# 6. UserTab 增加 onOpenAccount 参数
ACCT_SIG_ANCHOR = NL.join([
    '    /** 第 80 轮：进入「我的订阅」页（照 Han1meViewer 的 SubscriptionScreen 复刻）。 */',
    '    onSubs: () -> Unit,',
    ') {',
])

ACCT_SIG_NEW = NL.join([
    '    /** 第 80 轮：进入「我的订阅」页（照 Han1meViewer 的 SubscriptionScreen 复刻）。 */',
    '    onSubs: () -> Unit,',
    '    /** ★ 第 350 轮：进「我的账户」资料编辑页（已登录时点资料卡走这里）。 */',
    '    onOpenAccount: () -> Unit = {},',
    ') {',
])


def patch_usertab_sig(text):
    if 'onOpenAccount: () -> Unit = {},' in text:
        return text
    if ACCT_SIG_ANCHOR not in text:
        raise SystemExit('[第350轮] UserTab 签名锚点未找到')
    return text.replace(ACCT_SIG_ANCHOR, ACCT_SIG_NEW, 1)


# 7. 调用点传 onOpenAccount
# 锚点必须**唯一**：FavTab 里也有 onSubs = { showSubs = true }（缩进 20）。
# 所以带上紧随其后的 onOpenWeb —— 那是 UserTab 独有的参数。
CALL_ANCHOR = NL.join([
    '                    onSubs = { showSubs = true },',
    '                    onOpenWeb = { u, t -> webPage = WebPage(u, t) },',
])

CALL_NEW = NL.join([
    '                    onSubs = { showSubs = true },',
    '                    // ★ 第 350 轮：已登录点资料卡进「我的账户」',
    '                    onOpenAccount = { showAccount = true },',
    '                    onOpenWeb = { u, t -> webPage = WebPage(u, t) },',
])


def patch_usertab_call(text):
    if 'onOpenAccount = { showAccount = true }' in text:
        return text
    if CALL_ANCHOR not in text:
        raise SystemExit('[第350轮] UserTab 调用点未找到')
    return text.replace(CALL_ANCHOR, CALL_NEW, 1)


# 8. 声明 showAccount 状态
STATE_ANCHOR = '    var showDownloads by remember { mutableStateOf(false) }'

STATE_NEW = NL.join([
    '    var showDownloads by remember { mutableStateOf(false) }',
    '    // ★ 第 350 轮：账户资料编辑页（照 Viewer 的 AccountRoute）',
    '    var showAccount by remember { mutableStateOf(false) }',
])


def patch_account_state(text):
    if 'var showAccount by remember' in text:
        return text
    if STATE_ANCHOR not in text:
        raise SystemExit('[第350轮] showDownloads 声明未找到')
    return text.replace(STATE_ANCHOR, STATE_NEW, 1)


# 9. BackHandler 消费 showAccount
BH_ANCHOR = '            showDownloads -> true'
BH_NEW = '            showAccount -> true' + NL + '            showDownloads -> true'

BH2_ANCHOR = '            showDownloads -> showDownloads = false'
BH2_NEW = '            showAccount -> showAccount = false' + NL + '            showDownloads -> showDownloads = false'


def patch_account_back(text):
    if 'showAccount -> showAccount = false' in text:
        return text
    if BH_ANCHOR not in text or BH2_ANCHOR not in text:
        raise SystemExit('[第350轮] BackHandler 锚点未找到')
    text = text.replace(BH_ANCHOR, BH_NEW, 1)
    text = text.replace(BH2_ANCHOR, BH2_NEW, 1)
    return text


# 10. 渲染账户页（与下载页同级，Box 直接子节点）
ACC_RENDER_ANCHOR = '        androidx.compose.animation.AnimatedVisibility(' + NL + '            visible = showDownloads,'

ACC_RENDER_NEW = NL.join([
    '        // ★ 第 350 轮：账户资料编辑页（整屏覆盖，与下载页同级）',
    '        if (showAccount) {',
    '            Box(Modifier.fillMaxSize().background(BgColor).padding(bottom = 74.dp)) {',
    '                com.han1me.account.HplayAccountScreen(',
    '                    userId = com.han1me.X99.setting("account.uid", ""),',
    '                    onClose = { showAccount = false },',
    '                    // 退出登录走与用户页同一条链：先清 Cookie 再 refreshUser（顺序不能反，',
    '                    // 否则 whoami 会拿着旧 Cookie 仍报「已登录」）。',
    '                    onLogout = {',
    '                        showAccount = false',
    '                        runCatching { HanimeClient.clearCookies() }',
    '                        user = null',
    '                        profile = null',
    '                        refreshUser()',
    '                        toast = "已退出登录"',
    '                    },',
    '                    onNeedLogin = { showAccount = false; showLogin = true },',
    '                )',
    '            }',
    '        }',
    '',
    '        androidx.compose.animation.AnimatedVisibility(',
    '            visible = showDownloads,',
])


def patch_account_render(text):
    if 'HplayAccountScreen(' in text:
        return text
    if ACC_RENDER_ANCHOR not in text:
        raise SystemExit('[第350轮] 下载页 AnimatedVisibility 锚点未找到（账户页要插在它前面）')
    return text.replace(ACC_RENDER_ANCHOR, ACC_RENDER_NEW, 1)

RULES = [
    ('Han1meApp.kt', patch_downloads_overlay),
    ('Han1meApp.kt', patch_search_bar),
    ('Han1meApp.kt', patch_search_state),
    ('Han1meApp.kt', patch_search_url),
    ('Han1meApp.kt', patch_account_state),
    ('Han1meApp.kt', patch_account_click),
    ('Han1meApp.kt', patch_usertab_sig),
    ('Han1meApp.kt', patch_usertab_call),
    ('Han1meApp.kt', patch_account_back),
    ('Han1meApp.kt', patch_account_render),
]


def main():
    changed = 0
    for name, fn in RULES:
        p = GEN / name
        old = p.read_text(encoding='utf-8')
        new = fn(old)
        if new != old:
            p.write_text(new, encoding='utf-8')
            changed += 1
            print('  [第350轮] ' + name + ' <- ' + fn.__name__)
    print('  [第350轮] 规则应用完成，改动文件数 ' + str(changed))


if __name__ == '__main__':
    main()