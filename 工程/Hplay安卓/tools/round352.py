#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 352 轮规则：通知跳转 / javchu 路由开关接线 / 下载真并发。

接在 round351 之后、logwire 之前。每条规则幂等（先判 new 是否已在）。
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GEN = ROOT / "build" / "gen"
NL = chr(10)


def _once(text, old, new, tag):
    n = text.count(old)
    if n != 1:
        raise SystemExit("[第352轮规则失配] %s：命中 %d 次（应为 1）：%r" % (tag, n, old[:100]))
    return text.replace(old, new, 1)


# ---------------------------------------------------------- 1. 通知跳转（消费入口请求）

NAV_OLD = NL.join([
    "    var showDownloads by remember { mutableStateOf(false) }",
    "    // ★ 第 350 轮：账户资料编辑页（照 Viewer 的 AccountRoute）",
])

NAV_NEW = NL.join([
    "    var showDownloads by remember { mutableStateOf(false) }",
    "    // ★★★ 第 352 轮（用户报「通知栏点击下载中的通知不跳转正在下载页」）：**消费入口请求**。",
    "    //",
    "    // MainActivity.onNewIntent / onCreate 把 Intent 的 hplay.open=downloads 写进",
    "    // HplayNavRequest.pending；这里读走并打开下载面板。",
    "    //",
    "    // 用 LaunchedEffect(pending) 而不是直接在组合体里读：组合体里读+清空是副作用，",
    "    // 会在重组中再触发一次（而且 consume() 会写 state，属于「组合期写状态」的禁忌）。",
    "    val __navPending = com.han1me.HplayNavRequest.pending",
    "    androidx.compose.runtime.LaunchedEffect(__navPending) {",
    "        if (__navPending == null) return@LaunchedEffect",
    "        val what = com.han1me.HplayNavRequest.consume()",
    "        when (what) {",
    "            com.han1me.HplayNavRequest.OPEN_DOWNLOADS -> {",
    "                // 先关掉会挡住下载面板的其它覆盖层（否则面板在下面、看起来还是「没跳转」）",
    "                webPage = null",
    "                detail = null",
    "                showMine = false",
    "                showSearch = false",
    "                showDownloads = true",
    "            }",
    "        }",
    "    }",
    "    // ★ 第 350 轮：账户资料编辑页（照 Viewer 的 AccountRoute）",
])


def patch_nav(t):
    if "通知栏点击下载中的通知不跳转正在下载页" in t:
        return t
    return _once(t, NAV_OLD, NAV_NEW, "showDownloads 声明处")


# ---------------------------------------------------------- 2. tutu1.space 证书豁免

THUMB_OLD = NL.join([
    "    val thumbHttp: OkHttpClient by lazy {",
    "        http.newBuilder()",
    "            // \u2605 \u7b2c 86 \u8f6e\u771f\u673a\u5b9e\u6d4b\uff08\u7528\u6237\u62a5\u300c\u5934\u50cf\u6709\u7684\u52a0\u8f7d\u4e0d\u51fa\u6765\u300d\uff09\uff1a",
])

THUMB_NEW = NL.join([
    "    val thumbHttp: OkHttpClient by lazy {",
    "        http.newBuilder()",
    "            // \u2605\u2605\u2605 \u7b2c 352 \u8f6e\uff08\u8bbe\u5907\u65e5\u5fd7 18 \u6761 SSLPeerUnverifiedException\uff09\uff1a**javchu \u5c01\u9762\u56fe\u5e8a\u8bc1\u4e66\u4e0d\u5339\u914d**\u3002",
    "            //",
    "            // \u5b9e\u6d4b\uff1atutu1.space \u7684\u8bc1\u4e66 DN=CN=666.9\uff0cSAN \u91cc\u6ca1\u6709 tutu1.space\u3002",
    "            // \u4e0d\u505a\u8c41\u514d\uff0cjavchu \u5217\u8868\u7684\u5c01\u9762**\u5fc5\u7136**\u5168\u90e8\u52a0\u8f7d\u5931\u8d25\uff08\u65e5\u5fd7\u91cc\u8fde\u62a5 18 \u6b21\uff09\u3002",
    "            //",
    "            // \u53ea\u653e\u884c\u8fd9\u4e00\u4e2a\u4e3b\u673a\u540d\uff0c\u4e14**\u53ea\u5bf9\u5c01\u9762\u5ba2\u6237\u7aef**\uff08thumbHttp\uff09\u751f\u6548\uff1a",
    "            //   \u00b7 \u4e0d\u52a8 HanimeClient.http\uff08\u767b\u5f55/\u63a5\u53e3/\u5a92\u4f53\u5168\u90fd\u4e0d\u53d7\u5f71\u54cd\uff09\uff1b",
    "            //   \u00b7 \u4e0d\u52a8\u4e0b\u8f7d\u4e0e\u64ad\u653e\u5ba2\u6237\u7aef\u3002",
    "            .hostnameVerifier { hostname, session ->",
    "                if (hostname.equals(\"tutu1.space\", true) || hostname.endsWith(\".tutu1.space\", true)) return@hostnameVerifier true",
    "                javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)",
    "            }",
    "            // \u2605 \u7b2c 86 \u8f6e\u771f\u673a\u5b9e\u6d4b\uff08\u7528\u6237\u62a5\u300c\u5934\u50cf\u6709\u7684\u52a0\u8f7d\u4e0d\u51fa\u6765\u300d\uff09\uff1a",
])


def patch_tutu1(t):
    if "tutu1.space \u7684\u8bc1\u4e66 DN=CN=666.9" in t:
        return t
    return _once(t, THUMB_OLD, THUMB_NEW, "thumbHttp builder")


RULES = [
    ("Han1meApp.kt", patch_nav),
    ("HanimeClient.kt", patch_tutu1),
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
    print(("[第352轮] 已应用：" + "、".join(touched)) if touched else "[第352轮] 无需改动（幂等）")


if __name__ == "__main__":
    main()