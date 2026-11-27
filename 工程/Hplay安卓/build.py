# -*- coding: utf-8 -*-
"""Hplay Android 版一键构建（无 Gradle/AGP/Maven 客户端，纯 SDK 工具链）。

管线（借鉴 quder WAKAN 移植的实测经验，含真机 404 换来的坑）：
  上游复制+补丁 → kotlinc(Compose) → d8(多dex) → API 层审计 → aapt2 compile/link
  → aapt add classes*.dex → aapt add META-INF/services → zipalign → 反斜杠修复
  → apksigner(v1+v2+v3) → badging 校验 → 部署到 成品\\Hplay\\apk\\

用法：
  python build.py            # 增量构建（用 version.properties 当前版本号）
  python build.py --release  # 发布构建：版本号 +1（versionCode=日期+当日序号）并回写
  python build.py --clean    # 先清 build\\

工具链约定：
  * aapt2/zipalign/aapt 是原生窄字符程序，吃不了中文绝对路径 —— 一律 cwd=本目录 +
    相对 ASCII 路径；工具链与依赖经 build\\tool\\ 目录联接（junction）获得 ASCII 相对路径；
  * JDK/Kotlin 工具是宽字符程序，中文路径安全（沿用上游 build.py 的 @argfile 实证）；
  * aapt2(Windows) 会把资产名里的反斜杠同时写进中央目录与本地头 → 真机资源 404；
    必须在 zipalign 之后、签名之前按原始字节改成正斜杠（fix_zip_separators）。
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import struct
import subprocess
import sys
import time
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent
WS = ROOT.parent.parent                       # D:\工作区
AVPLAY = WS / "工程" / "AvPlay"
TOOLS = WS / "工具"

SDK = TOOLS / "android-build"                 # bt\ + platforms\
BT = SDK / "bt" / "android-14"                # build-tools 34（quder 实测可用）
ANDROID_JAR_SRC = SDK / "platforms" / "android-35" / "android.jar"
DEPS = TOOLS / "android-deps"

JDK = TOOLS / "jdk-temurin" / "jdk-21.0.12.1+1"
JAVA = JDK / "bin" / "java.exe"
KOTLINC_BAT = TOOLS / "avp_kotlinc24" / "kotlinc" / "bin" / "kotlinc.bat"
COMPOSE_PLUGIN = TOOLS / "avp_kotlinc24" / "kotlinc" / "lib" / "compose-compiler-plugin.jar"
KOTLIN_STDLIB = TOOLS / "avp_kotlinc24" / "kotlinc" / "lib" / "kotlin-stdlib.jar"

AAPT2 = BT / "aapt2.exe"
AAPT = BT / "aapt.exe"
ZIPALIGN = BT / "zipalign.exe"
# d8 用 bt35 的（R8 8.5+，能解析 profileinstaller/appcompat-resources 等新类文件；
# bt34 的 8.2.2-dev 对其 NPE——v1.0.2 构建实测）。aapt2/zipalign/aapt 保留 bt34（quder 真机验证过）。
D8_JAR = SDK / "bt35" / "android-15" / "lib" / "d8.jar"
APKSIGNER_JAR = BT / "lib" / "apksigner.jar"

SRC = ROOT / "src"
GEN = ROOT / "build" / "gen"                  # 上游源码打补丁后的落点
BUILD = ROOT / "build"
APK_OUT = ROOT / "apk"

# ★ 2026-11-26（第 331 轮 · 用户要求「实验区包名同步主线版本，实现两个版本可覆盖升级」）：
#
# 包名从 com.han1me.app.lab 改回 com.han1me.app —— 与主线同一个应用身份。
# 前置条件是**签名必须一致**，否则系统拒装（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
# 本工程的 keystore/hplay-port.jks 与主线的 hplay-port.jks 已实测为同一份
# （git hash-object 两处都是 1325f74f374d82524155e1d85d63b8bf2510e719），
# alias/keypass 也一致，所以两条线可以互相覆盖安装。
#
# 代价（已与用户确认过口径）：两条线共用同一份应用数据
# （filesDir/AvPlay 下的 settings / cookies / 收藏 / 下载索引）。
# 好处是实验版装上后用户不必重新登录、不必重设参数。
PACKAGE = "com.han1me.app"
APK_NAME = "Hplay.apk"

# ★ 第 331 轮：两条发布线的身份（由 gen_line_info() 注入成 Kotlin 常量）。
#
# GitHub 仓库 OWNER/REPO 与当前线（lab = 实验版 / stable = 主线版）。
# App 的「检查更新」用它拼 API 地址、并默认检查本线；
# 用户在更新页可以把线切到另一条（用户要求「可选实验版和主线版自由更新」）。
# ★★★ 第 341 轮（用户要求「关于页面注入 github 仓库信息」）：
# 默认值从占位符 OWNER/REPO 改成**真实仓库** —— 否则不带环境变量构建出来的包，
# 关于页四行仓库入口全是「这个构建没有注入仓库地址」，等于没有仓库信息。
# 仍可用环境变量覆盖（fork 出去的人设 HPLAY_GH_REPO 即可）。
GH_REPO = os.environ.get("HPLAY_GH_REPO", "xftx1234/Hplay")
GH_LINE = os.environ.get("HPLAY_GH_LINE", "lab")

MIN_SDK = "24"
TARGET_SDK = "34"
KS = ROOT / "keystore" / "hplay-port.jks"
KS_ALIAS = "hplay-port"
KS_PASS = "hplayport"

TIMINGS = []


def step(name):
    def deco(fn):
        def wrapped(*a, **kw):
            t0 = time.time()
            print(f"\n=== {name}")
            r = fn(*a, **kw)
            print(f"--- {name}: {time.time() - t0:.1f}s")
            TIMINGS.append((name, time.time() - t0))
            return r
        return wrapped
    return deco


def run(cmd, cwd=None, env=None, quiet=False):
    printable = " ".join(str(c) for c in cmd)
    if not quiet:
        print("  $ " + printable[:300] + (" ..." if len(printable) > 300 else ""))
    e = dict(os.environ)
    e["JAVA_HOME"] = str(JDK)
    if env:
        e.update(env)
    proc = subprocess.run([str(c) for c in cmd], cwd=str(cwd or ROOT), env=e,
                          capture_output=True)
    out = decode_tool(proc.stdout)
    err = decode_tool(proc.stderr)
    if not quiet and out.strip():
        print("    " + out.strip()[:4000].replace("\n", "\n    "))
    if proc.returncode != 0:
        print("    STDERR: " + err.strip()[:6000])
        raise SystemExit(f"命令失败({proc.returncode}): {printable[:200]}")
    if not quiet and err.strip():
        print("    [stderr] " + err.strip()[:1500].replace("\n", "\n    "))
    return out, err


def decode_tool(b):
    if not b:
        return ""
    for enc in ("utf-8", "gbk", "cp936"):
        try:
            return b.decode(enc)
        except UnicodeDecodeError:
            continue
    return b.decode("utf-8", "replace")


def ensure_junction(link: Path, target: Path):
    """目录联接：给中文真实路径一个 ASCII 相对入口（mklink /J 无需管理员）。"""
    if link.exists() or link.is_symlink():
        return
    r = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(target)],
                       capture_output=True)
    if not link.exists():
        raise SystemExit(f"junction 创建失败: {link} -> {target} :: {decode_tool(r.stderr)}")


# ---------------------------------------------------------------- 版本号

def load_version():
    vp = ROOT / "version.properties"
    vals = {"major": "1", "minor": "0", "patch": "0", "seq": "0", "lastdate": ""}
    if vp.exists():
        for line in vp.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                vals[k.strip()] = v.strip()
    # versionName 是权威字段（手写/回写皆用它），解析覆盖 major/minor/patch
    vn = vals.get("versionName", "")
    parts = vn.split(".")
    if len(parts) == 3 and all(p.isdigit() for p in parts):
        vals["major"], vals["minor"], vals["patch"] = parts
    return vp, vals


def bump_version(vp, vals):
    today = time.strftime("%Y%m%d")
    seq = int(vals["seq"]) + 1 if vals["lastdate"] == today else 1
    patch = int(vals["patch"])
    if vals["lastdate"]:
        patch += 1
    vals.update({"patch": str(patch), "seq": str(seq), "lastdate": today})
    vp.write_text(
        "versionName=%d.%d.%d\nseq=%s\nlastdate=%s\n" % (
            int(vals["major"]), int(vals["minor"]), patch, seq, today),
        encoding="utf-8")
    return vals


def version_strings(vals):
    today = time.strftime("%Y%m%d")
    seq = int(vals["seq"]) if vals["lastdate"] == today else 1
    code = int(today) * 100 + seq
    name = f"{vals['major']}.{vals['minor']}.{vals['patch']}"
    return name, code


# ---------------------------------------------------------------- 上游源码

sys.path.insert(0, str(ROOT / "tools"))
import patches as P  # noqa: E402


def sha256_file(p: Path):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- 139 凭据（已删除）
#
# ★★ 第 332 轮：**139 直连渠道整条移除**（用户要求「代码层也去掉」）。
#
# 原来这里有一整套：tools/139_cred.txt（本机私有凭据）→ build/gen/Yun139Cred.kt
# → App 的 Yun139 通道。用户决定彻底不要这条渠道，所以：
#
#   · src/com/han1me/update/Yun139.kt        已删除
#   · build/gen/Yun139Cred.kt                 不再生成
#   · tools/139_cred.txt                      仍留在本机（不进 git），但没有任何读者
#   · --no-cred / HPLAY_NO_CRED               保留 —— 见下
#
# 为什么保留 --no-cred 开关：它现在**没有实际作用**（已经没有凭据会被写进 dex），
# 但 tools/publish_github.py 与 CI workflow 都调用了它。删掉会让那两处报
# 「unknown argument」。留着是零成本的兼容壳，而且它的发布闸门（扫 dex 找凭据原文）
# 仍然有价值 —— 万一将来有人重新引入类似的东西，那道闸门会拦住。
#
# 以后的注意点：如果哪天真要恢复 139 或类似的带凭据通道，
# 必须同时恢复这里的注入逻辑，并确保发布路径带 --no-cred。

# 保留给 CLI / 环境变量的名字（现在只影响日志文案，不影响产物）。
NO_CRED = os.environ.get("HPLAY_NO_CRED", "") in ("1", "true", "yes")


def gen_build_info():
    """生成 build/gen/HplayBuildInfo.kt（发布身份注入）。

    ★ 第 331 轮：为什么这些值不能写死在源码里。

    同一条源码要能构建出**实验版**和**主线版**两个包：
      · 实验版：分支 lab，tag 前缀 lab-，Release 标 prerelease；
      · 主线版：分支 main，tag 前缀 v，Release 是正式版。
    源码里如果硬编码其中一条，另一条就得改代码 —— 那不是"两个版本"，那是两份分叉。

    所以仓库地址与线标识由构建变量注入：
      · 环境变量 HPLAY_GH_REPO（OWNER/REPO）
      · 环境变量 HPLAY_GH_LINE（lab / stable）
    缺省时写入占位符，App 里 GitHub 渠道会**明确报「未配置仓库地址」**，
    不会假装查过（与 139 凭据缺失时的处理口径一致）。

    构建 SHA 从 git 拿；拿不到就空串（关于页会省略这一行，不显示假的）。
    """
    _ver_name = ""
    try:
        for _l in (ROOT / "version.properties").read_text(encoding="utf-8").splitlines():
            if _l.startswith("versionName="):
                _ver_name = _l.split("=", 1)[1].strip()
                break
    except Exception:
        _ver_name = ""
    repo = (os.environ.get("HPLAY_GH_REPO") or GH_REPO).strip()
    line = (os.environ.get("HPLAY_GH_LINE") or GH_LINE).strip()
    if line not in ("lab", "stable"):
        line = "lab"
    sha = ""
    try:
        r = subprocess.run(["git", "rev-parse", "--short=7", "HEAD"], cwd=str(ROOT),
                           capture_output=True, text=True, timeout=8)
        if r.returncode == 0:
            sha = (r.stdout or "").strip()
    except Exception:
        sha = ""
    at = time.strftime("%Y-%m-%d %H:%M")
    # ★ 第 331 轮：**不再注入内网端点**。
    # 理由见 UpdateChecker.ENDPOINTS 的注释：dex 里是明文，公开分发的 APK
    # 不应该带任何内网地址。家里访问公网端点只多一次往返，不影响可用性。
    kt = (
        "package com.han1me.update\n\n"
        "/** 自动生成，请勿手改（见 build.py 的 gen_build_info）。 */\n"
        "internal object HplayBuildInfo {\n"
        '    const val GH_REPO: String = "' + repo.replace('"', '') + '"\n'
        '    const val GH_LINE: String = "' + line + '"\n'
        '    const val BUILD_AT: String = "' + at + '"\n'
        '    const val BUILD_SHA: String = "' + sha + '"\n'
        # ★ 第 346 轮：**版本号也注入**。
        # 设置页「关于」里那行「Hplay 实验版 · v1.1.69-refactor」
        # 是**硬编码**的，从 1.1.69 一直显示到 1.1.79 没动过——
        # 用户看到的版本号是错的，而且每轮都要手改。
        # 现在从 version.properties 读出来一并注入。
        '    const val VERSION_NAME: String = "' + _ver_name + '"\n'
        "}\n"
    )
    GEN.mkdir(parents=True, exist_ok=True)
    (GEN / "HplayBuildInfo.kt").write_text(kt, encoding="utf-8")
    print("  HplayBuildInfo.kt 仓库=" + repo + " 线=" + line + " sha=" + (sha or "（无）"))


@step("1. 上游源码复制 + 补丁重放")
def upstream():
    # 预检闸门：补丁结构问题在这里挡下来，不要拖到 kotlinc 报错（第 208 轮）
    import subprocess as _sp
    _g = _sp.run([sys.executable, str(ROOT / "tools" / "patchguard.py"), "--quiet"],
                 capture_output=True, text=True, encoding="utf-8", errors="replace")
    if _g.returncode != 0:
        sys.stdout.write(_g.stdout or "")
        raise SystemExit("[预检未通过] 先修 tools/patches.py，构建已中止")
    snapshot_path = BUILD / "upstream_snapshot.json"
    prev = json.loads(snapshot_path.read_text(encoding="utf-8")) if snapshot_path.exists() else {}
    now = {}
    GEN.mkdir(parents=True, exist_ok=True)
    gen_build_info()
    for key, spec in P.UPSTREAM.items():
        src = AVPLAY / spec["src"]
        text = src.read_text(encoding="utf-8")
        now[spec["out"]] = sha256_file(src)
        applied = 0
        for old, new in spec.get("patches", []):
            n = text.count(old)
            if n != 1:
                raise SystemExit(
                    f"[补丁失配] {spec['out']}：模式命中 {n} 次（应为 1）。"
                    f"上游已改动相关代码 —— 按 .zcode\\skills\\hplay-android-apk\\SKILL.md "
                    f"的同步流程人工移植后更新 tools\\patches.py。\n模式前 80 字符：{old[:80]!r}")
            text = text.replace(old, new)
            applied += 1
        if "tail" in spec:
            marker, new_tail = spec["tail"]
            i = text.find(marker)
            if i < 0:
                raise SystemExit(f"[补丁失配] {spec['out']}：尾段标记未找到：{marker!r}")
            text = text[:i] + new_tail
            applied += 1
        (GEN / spec["out"]).write_text(text, encoding="utf-8")
        print(f"  {spec['out']}: {len(spec.get('patches', []))} 补丁 + "
              f"{'尾段替换' if 'tail' in spec else '—'} ✓")
    # ★★★ 2026-11-25（第 278 轮）：**全局替换**（批量规则，允许多次命中）。
    #
    # patches.py / postpatch.py 都要求 old 恰好命中 1 次 —— 那是单点植入的安全网。
    # 但 UI 重构里有「同一种颜色出现在几十处」「同一段代码风格要统一」这类批量改动，
    # 逐处写上下文补丁既脆弱又难维护。所以单独一个阶段做全量替换。
    # 规则在 tools/globalpatch.py。
    _gp = subprocess.run([sys.executable, str(ROOT / "tools" / "globalpatch.py")],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    if _gp.returncode != 0:
        sys.stdout.write(_gp.stdout or "")
        sys.stdout.write(_gp.stderr or "")
        raise SystemExit("[全局替换未通过] 构建已中止")
    for line in (_gp.stdout or "").splitlines():
        if line.strip():
            print(line)

    # 后置规则（第 212 轮）：重放之后对 gen 做定点替换，规则在 tools/postpatch.py
    _pp = subprocess.run([sys.executable, str(ROOT / "tools" / "postpatch.py")],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    if _pp.returncode != 0:
        sys.stdout.write(_pp.stdout or "")
        raise SystemExit("[后置规则未通过] 构建已中止")


    # ★★★ 2026-11-25（第 288 轮）：**UI 微调**（函数式改写，按结构匹配）。
    #
    # 与 globalpatch 的分工：globalpatch 用 (file, old, new) 三元组，old 必须精确匹配；
    # 删除底栏 tab、把标签导航换成搜索框这类改动，目标文本含大量 emoji 与全角字符，
    # 写进 Python 三引号时引号/转义极易出错（本会话踩过多次）。
    # 这里用正则按结构匹配（如 val items = listOf\(.*?\)），稳得多。
    # 规则在 tools/uifinetune.py。
    try:
        sys.path.insert(0, str(ROOT / "tools"))
        import uifinetune as _uf_mod
        import importlib as _il
        _il.reload(_uf_mod)
        _uf_mod.main()
    except Exception as _e:
        print("  [UI 微调] 异常：" + repr(_e))
        raise SystemExit("[UI 微调未通过] 构建已中止")

    # 第 348 轮：**动画校准 + 关注即时化**（规则在 tools/round348.py）。
    #
    # 为什么单独一个阶段：postpatch.py 已经 2800+ 行，本轮改动再加进去
    # 会让 diff 完全看不出改了什么。这个模块只此四件事：
    #   关注按钮乐观更新 / 详情页预热 WebView / 列表项入场动画 / tab 方向转场 / 轻提示进出。
    # 放在 uifinetune 之后：本轮要替换的 tab transitionSpec 与 toast 块都是 uifinetune
    # 之后才最终定形的（uifinetune 会重排 tab 分支的缩进）。
    _r348 = subprocess.run([sys.executable, str(ROOT / "tools" / "round348.py")],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
    for line in (_r348.stdout or "").splitlines():
        if line.strip():
            print(line)
    if _r348.returncode != 0:
        sys.stdout.write(_r348.stderr or "")
        raise SystemExit("[第348轮规则未通过] 构建已中止")

    # ★ 第 350 轮：**转场回 Viewer / 搜索页重构 / 下载页搬出 Column**（规则在 tools/round350.py）。
    #
    # 放在 round348 之后：本轮要改的 doSearch / SearchRow / 下载页块，都是 round348
    # （以及 uifinetune）稳定之后才定形的。
    _r350 = subprocess.run([sys.executable, str(ROOT / "tools" / "round350.py")],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
    for line in (_r350.stdout or "").splitlines():
        if line.strip():
            print(line)
    if _r350.returncode != 0:
        sys.stdout.write(_r350.stderr or "")
        raise SystemExit("[第350轮规则未通过] 构建已中止")

    # ★ 第 351 轮：**javchu 直链预检 Referer / 首页搜索框 MD3 / 半透明统一**（规则在 tools/round351.py）。
    #
    # 与 round350 同一位置（它之后、logwire 之前）：本轮要改的 srcOk 调用点与
    # 首页 TopNavBar 里的搜索胶囊，都是 round350 定形之后才稳定的。
    _r351 = subprocess.run([sys.executable, str(ROOT / "tools" / "round351.py")],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
    for line in (_r351.stdout or "").splitlines():
        if line.strip():
            print(line)
    if _r351.returncode != 0:
        sys.stdout.write(_r351.stderr or "")
        raise SystemExit("[第351轮规则未通过] 构建已中止")

    # ★★★ 第 334 轮：**日志拦截器接线**（gen + src 两处都要）。
    #
    # 全项目 14 个 OkHttpClient.Builder（gen 8 + src 6），逐个手改不可维护。
    # tools/logwire.py 扫描两个目录、按 builder 定位插入 addInterceptor(LoggingInterceptor)。
    # 幂等：已含 LoggingInterceptor 的文件整段跳过。
    #
    # 放在这里（uifinetune 之后、硬检查之前）的原因：
    #   · uifinetune 会改 gen 里的 UI 代码，但不动 OkHttp builder，顺序无冲突；
    #   · 硬检查要确认没有桌面符号残留 —— 日志拦截器是新代码，不该被它误伤。
    _lw = subprocess.run([sys.executable, str(ROOT / "tools" / "logwire.py")],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    for line in (_lw.stdout or "").splitlines():
        if line.strip():
            print(line)
    if _lw.returncode != 0:
        sys.stdout.write(_lw.stderr or "")
        raise SystemExit("[日志接线未通过] 构建已中止")

    # 硬检查：桌面专属符号不得残留
    bad = []
    for f in GEN.glob("*.kt"):
        t = f.read_text(encoding="utf-8")
        for pat in P.FORBIDDEN:
            if pat in t:
                bad.append(f"{f.name}: 含 {pat!r}")
    if bad:
        for b in bad:
            print("  !! " + b)
        raise SystemExit("生成源码含桌面专属符号（补丁不完整）")
    print("  硬检查通过（无 java.awt / com.javrocket / httpserver / HplayWindow 残留）")
    # 与上次快照比对：提示上游 UI 改动（需人工核对移植面）
    for out, oldsha in prev.items():
        if oldsha != now.get(out):
            print(f"  [提示] 上游 {out} 自上次构建后有改动（补丁已重放，请核对行为）")
    snapshot_path.write_text(json.dumps(now, ensure_ascii=False, indent=1), encoding="utf-8")


@step("2. 启动图标（hplay-icon.png → mipmap-*/ic_launcher.png）")
def icons():
    src_icon = AVPLAY / "独立版" / "hplay-icon.png"
    res = SRC / "res"
    marker = BUILD / "icon_sha.txt"
    sha = sha256_file(src_icon)
    if marker.exists() and marker.read_text(encoding="utf-8").strip() == sha \
            and (res / "mipmap-xxxhdpi" / "ic_launcher.png").exists():
        print("  图标未变，跳过")
        return
    # ★ 2026-11-24（第 277 轮）：**图标已存在且 PIL 不可用时直接跳过**。
    #
    # 背景：主线 toolchain 里没有 pylibs/PIL，而 5 档 mipmap 图标早已在 src/res 里。
    # 旧逻辑只要 marker 缺失就一定要重新生成 → 直接 ModuleNotFoundError 崩在构建第 2 步。
    # 现在：图标文件齐全就认为无需生成（marker 只是"上次生成时的源图 hash"缓存，
    # 不是图标的必要条件），并把当前 hash 补写进 marker，下次自然命中。
    if (res / "mipmap-xxxhdpi" / "ic_launcher.png").exists():
        try:
            import PIL  # noqa: F401
            have_pil = True
        except Exception:
            have_pil = False
        if not have_pil:
            runCatchingMarker = True
            marker.parent.mkdir(parents=True, exist_ok=True)
            marker.write_text(sha, encoding="utf-8")
            print("  图标已存在且 PIL 不可用，跳过生成（marker 已补写）")
            return
    sys.path.insert(0, str(TOOLS / "pylibs"))
    from PIL import Image
    img = Image.open(src_icon).convert("RGBA")
    side = min(img.size)
    x = (img.width - side) // 2
    y = (img.height - side) // 2
    square = img.crop((x, y, x + side, y + side))
    for name, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        d = res / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)
        square.resize((px, px), Image.LANCZOS).save(d / "ic_launcher.png")
    marker.write_text(sha, encoding="utf-8")
    print("  5 档密度图标已生成")


@step("3. 依赖与工具链入口（junction + classpath 清单）")
def stage_tooling():
    ensure_junction(BUILD / "tool", TOOLS)           # build\tool\android-deps / android-build
    ensure_junction(BUILD / "sdkjar", ANDROID_JAR_SRC.parent)  # build\sdkjar\android.jar
    manifest = DEPS / "manifest.txt"
    dirs = []
    if manifest.exists():
        dirs = [l.strip() for l in manifest.read_text(encoding="utf-8").splitlines() if l.strip()]
    else:
        raise SystemExit("缺 %s —— 先跑 tools\\fetch_deps.py 生成最终依赖清单" % manifest)
    jars = []
    for d in dirs:
        cj = DEPS / d / "classes.jar"
        if cj.exists():
            jars.append(f"build/tool/android-deps/{d}/classes.jar")
    if not jars:
        raise SystemExit("依赖清单为空")
    (BUILD / "dep_jars.txt").write_text("\n".join(jars), encoding="utf-8")
    print(f"  依赖 {len(jars)} 个 classes.jar 已登记（按 manifest.txt）")


@step("4. kotlinc 编译（Compose 插件 + 依赖 classpath）")
def kotlin_compile():
    outjar = BUILD / "app.jar"
    if outjar.exists():
        outjar.unlink()
    dep_jars = (BUILD / "dep_jars.txt").read_text(encoding="utf-8").split()
    classpath = ";".join(["build/sdkjar/android.jar"] + dep_jars)
    sources = []
    for base in (SRC / "com" / "han1me", GEN):
        for f in sorted(base.rglob("*.kt")):
            sources.append(str(f.relative_to(ROOT)).replace("\\", "/"))
    argfile = BUILD / "kotlinc_args.txt"
    argfile.write_text("\n".join([
        "-no-jdk",
        "-jvm-target", "1.8",
        "-classpath", classpath,
        "-Xplugin", str(COMPOSE_PLUGIN),
        "-d", "build/app.jar",
    ] + sources) + "\n", encoding="utf-8")
    env = {"JAVA_OPTS": "-Xmx3g"}
    out, err = run([str(KOTLINC_BAT), "@build/kotlinc_args.txt"], env=env, quiet=True)
    tail = (out or "") + (err or "")
    # 只打印告警摘要与错误
    for line in tail.splitlines():
        if "error" in line.lower() or "warning: " in line.lower():
            print("    " + line.strip()[:200])
    if not outjar.exists():
        print(tail[-5000:])
        raise SystemExit("kotlinc 未产出 app.jar")
    print(f"  app.jar {outjar.stat().st_size} B（{len(sources)} 个源文件）")


@step("5. aapt2 compile + link（产出资源表符号 R.txt）")
def aapt2_step(unsigned):
    res_dirs = (BUILD / "res_dirs.txt").read_text(encoding="utf-8").split()
    flats = []
    for i, rd in enumerate(res_dirs):
        flata = BUILD / f"res-{i}.flata"
        if flata.exists():
            flata.unlink()
        run([str(AAPT2), "compile", "--dir", rd, "-o", os.path.relpath(str(flata), str(ROOT))])
        flats.append(os.path.relpath(str(flata), str(ROOT)))
    if unsigned.exists():
        unsigned.unlink()
    name, code = version_strings(VER_VALS)
    rtxt = os.path.relpath(str(BUILD / "R.txt"), str(ROOT))
    run([str(AAPT2), "link",
         "-o", "build/unsigned-nodex.apk",
         "--manifest", "src/AndroidManifest.xml",
         "-I", "build/sdkjar/android.jar",
         "--min-sdk-version", MIN_SDK,
         "--target-sdk-version", TARGET_SDK,
         "--version-code", str(code),
         "--version-name", name,
         "--auto-add-overlay",
         "--output-text-symbols", rtxt] + flats +
        # 2026-11-25（第 294 轮）：**恢复相对路径写法**。
        # 绝对路径在 Windows 上被 aapt2 解析成 "...\build\assets." （多了个点）
        # 于是报"系统找不到指定的文件"。相对路径配合 cwd=ROOT 一直是对的。
        ["-A", "build/assets", "--no-compress"])


@step("6. 生成各库 R 类（AAR 不带 R；漏掉 = 运行期 NoClassDefFoundError: R$id）")
def gen_r():
    """AGP 在 app 构建时为每个库命名空间生成 R 类（含全部资源 id，字段 final 由常量内联）。
    手工管线没有这一步 → PoolingContainer.<clinit> 引用 R$id 直接崩（v1.0.2 真机实测）。
    做法：扫描所有保留 jar 引用到的 L<pkg>/R$* → 为每个 pkg 从 R.txt 生成完整 R.java → javac → jar。"""
    dep_jars = (BUILD / "dep_jars.txt").read_text(encoding="utf-8").split()
    roots = set()
    import re as _re
    # 注意：CONSTANT_Class 的 Utf8 是裸形态（无 L/; 包裹），描述符形态反而匹配不到——v1.0.2 实测
    pat = _re.compile(rb"([A-Za-z0-9_./]+?)/R\$[A-Za-z0-9_]+")
    for rel in ["build/app.jar"] + dep_jars:
        p = ROOT / rel.replace("/", os.sep)
        if not p.exists():
            continue
        with zipfile.ZipFile(p) as z:
            for n in z.namelist():
                if n.endswith(".class"):
                    for m in pat.finditer(z.read(n)):
                        pkg = m.group(1).decode().replace("/", ".")
                        if pkg != PACKAGE and not pkg.startswith("android."):
                            roots.add(pkg)
    if not roots:
        print("  无库 R 引用，跳过")
        return
    # 解析 R.txt（aapt2 符号表）
    arrays = {}   # styleable name -> [ids]
    entries = {}  # (type, name) -> id ；styleable 子字段也在其中
    for line in (BUILD / "R.txt").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("int[] "):
            m = re.match(r"int\[\]\s+styleable\s+(\S+)\s*\{\s*([\dxA-Fa-f,\s]*?)\s*\}", line)
            arrays[m.group(1)] = [x.strip() for x in m.group(2).split(",") if x.strip()]
        elif line.startswith("int "):
            m = re.match(r"int\s+(\S+)\s+(\S+)\s+(0x[0-9a-fA-F]+|\d+)", line)
            entries[(m.group(1), m.group(2))] = m.group(3)
    types = sorted({t for (t, _n) in entries})
    # styleable 字段与数组统一在 arrays 块生成，避免重复类
    gen_types = [t for t in types if not (t == "styleable" and arrays)]
    src = BUILD / "Rsrc"
    if src.exists():
        shutil.rmtree(src)
    java_files = []
    for pkg in sorted(roots):
        pdir = src / pkg.replace(".", os.sep)
        pdir.mkdir(parents=True, exist_ok=True)
        lines = [f"package {pkg};", "public final class R {"]
        for t in gen_types:
            lines.append(f"  public static final class {t} {{")
            for (tt, n) in sorted(entries):
                if tt == t:
                    lines.append(f"    public static final int {n} = {entries[(t, n)]};")
            lines.append("  }")
        if arrays:
            lines.append("  public static final class styleable {")
            for name in sorted(arrays):
                lines.append(f"    public static final int[] {name} = {{{','.join(arrays[name])}}};")
            for (tt, n) in sorted(entries):
                if tt == "styleable":
                    lines.append(f"    public static final int {n} = {entries[(tt, n)]};")
            lines.append("  }")
        lines.append("}")
        jf = pdir / "R.java"
        jf.write_text("\n".join(lines) + "\n", encoding="utf-8")
        java_files.append(os.path.relpath(str(jf), str(ROOT)))
    rclasses = BUILD / "Rclasses"
    if rclasses.exists():
        shutil.rmtree(rclasses)
    rclasses.mkdir()
    run([str(JDK / "bin" / "javac.exe"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-cp", "build/sdkjar/android.jar", "-d", "build/Rclasses"] + java_files, quiet=True)
    jar_out = os.path.relpath(str(BUILD / "Rclasses.jar"), str(ROOT))
    run([str(JDK / "bin" / "jar.exe"), "cf", jar_out, "-C", "build/Rclasses", "."], quiet=True)
    print(f"  生成 {len(roots)} 个库 R 命名空间：{', '.join(sorted(roots)[:6])}{' …' if len(roots) > 6 else ''}")


@step("7. d8 脱糖 → classes*.dex（多 dex，含库 R 类）")
def d8_step():
    dexdir = BUILD / "dex"
    if dexdir.exists():
        shutil.rmtree(dexdir)
    dexdir.mkdir(parents=True)
    dep_jars = (BUILD / "dep_jars.txt").read_text(encoding="utf-8").split()
    # ★ kotlin-stdlib 必须显式入 dex：kotlinc 只把它放编译期 classpath、不打进产物 jar，
    #   fetch_deps 又刻意排除 stdlib（防旧版混入）——漏掉 = 真机首类加载即闪退（v1.0.1 实测教训）
    if not KOTLIN_STDLIB.exists():
        raise SystemExit("kotlin-stdlib.jar 缺失: " + str(KOTLIN_STDLIB))
    inputs = ["build/app.jar", "build/tool/avp_kotlinc24/kotlinc/lib/kotlin-stdlib.jar"]
    if (BUILD / "Rclasses.jar").exists():
        inputs.append("build/Rclasses.jar")
    inputs += dep_jars
    # java 是宽字符程序，中文绝对路径安全；dex 输出走相对 ASCII 路径
    out, err = run([str(JAVA), "-Xmx3g", "-cp", str(D8_JAR), "com.android.tools.r8.D8",
                    "--lib", "build/sdkjar/android.jar",
                    "--min-api", MIN_SDK,
                    "--release",
                    "--output", "build/dex"] + inputs, quiet=True)
    dexes = sorted(dexdir.glob("classes*.dex"))
    if not dexes:
        print((out or "")[-4000:])
        print((err or "")[-4000:])
        raise SystemExit("d8 未产出 dex")
    total = sum(d.stat().st_size for d in dexes)
    print(f"  {len(dexes)} 个 dex，共 {total/1048576:.1f} MB")


@step("9. java.* API 层审计（dex 引用 ⊆ android.jar）")
def api_floor():
    with zipfile.ZipFile(ANDROID_JAR_SRC) as z:
        available = {n[:-6] for n in z.namelist() if n.endswith(".class")}
    missing = set()
    warn = set()
    total = 0
    for dex in sorted((BUILD / "dex").glob("classes*.dex")):
        blob = dex.read_bytes()
        total += len(blob)
        for m in re.finditer(rb"L(java|javax)/[A-Za-z0-9_/$]+;", blob):
            name = m.group(0)[1:-1].decode("ascii")
            if name not in available:
                if name.startswith(("java/time", "java/util/stream", "java/nio/file",
                                    "java/util/concurrent/Flow")):
                    warn.add(name + "  (API26+，未调用即无害)")
                elif name.startswith(("javax/annotation", "java/lang/instrument")):
                    # JSR305 注解 / javaagent 钩子：只有元数据引用，运行期不会执行
                    warn.add(name + "  (注解/agent 钩子，不会执行)")
                else:
                    missing.add(name)
    print(f"  dex 总量 {total/1048576:.1f} MB")
    if warn:
        print("  [注意] 引用了高版本 java.*（设备 API26 才有；未调用即无害）：")
        for w in sorted(warn):
            print("    " + w)
    if missing:
        print("  android.jar 缺失的 java/javax 类型：")
        for m in sorted(missing):
            print("    " + m)
        raise SystemExit("dex 引用了 Android 不存在的 JDK API（会被 audit 拦截）")
    print("  审计通过")


@step("7. 资源目录清单 + 资产暂存")
def stage_res():
    # 不做目录级合并（各 AAR 的 values.xml 同名会互覆）；
    # 每个 res 目录单独 aapt2 compile 成 flata，link 时一并列出（--auto-add-overlay 处理同名）
    dirs = ["src/res"]
    dep_jars = (BUILD / "dep_jars.txt").read_text(encoding="utf-8").split()
    for rel in dep_jars:
        # rel = build/tool/android-deps/<artifact_dir>/classes.jar
        parts = rel.split("/")
        if len(parts) < 4:
            continue
        d = DEPS / parts[3] / "res"
        if d.exists():
            dirs.append(f"build/tool/android-deps/{parts[3]}/res")
    (BUILD / "res_dirs.txt").write_text("\n".join(dirs), encoding="utf-8")
    n = sum(1 for base in dirs for _ in (ROOT / base.replace("/", os.sep)).rglob("*") if _.is_file())
    assets = BUILD / "assets"
    # ★ 2026-11-25（第 294 轮）：**只保证目录存在，不删任何东西**。
    #
    # 前后踩了三个坑：
    #   1. rmtree -> app_bg.jpg 被 aapt2 句柄占着，PermissionError
    #   2. rename 走 -> aapt2 -A 找不到路径
    #   3. mkdir(exist_ok=True) -> 目录被当文件时仍抛 FileExistsError
    # 现在：存在就复用，不存在才建；文件用 copy2 覆盖（下面那步）。
    if not assets.is_dir():
        assets.mkdir(parents=True, exist_ok=True)
    shutil.copy2(AVPLAY / "独立版" / "hplay-adrules.txt", assets / "hplay-adrules.txt")
    # ★ 第 349 轮：**加载图标资源**。本工程手搓管线不给 app 包生成 R 类
    # （gen_r() 刻意排除 PACKAGE，见那里注释），所以 drawable 里的图在代码里
    # 拿不到 R.drawable.xxx —— 只能走 assets，用 assets.open() 读。
    # 源图在 工程/Hplay安卓/assets/load/，拷到 build/assets/ 供 aapt2 -A 打包。
    _load_dir = ROOT / "assets" / "load"
    if _load_dir.is_dir():
        for _f in sorted(_load_dir.glob("*")):
            if _f.is_file() and not _f.name.startswith("."):
                # 三个坑叠在一起（第 349 轮实测踩到）：
                #  · copy2 会把源文件的**只读位**一起带过去 → 下次覆盖 PermissionError；
                #  · 目标已存在且是只读 → 连 copyfile 也 PermissionError；
                #  · 所以先删目标、再 copyfile，源侧只读不影响读取。
                _dst = assets / _f.name
                if _dst.exists():
                    try:
                        os.chmod(str(_dst), stat.S_IWRITE | stat.S_IREAD)
                    except OSError:
                        pass
                    _dst.unlink()
                shutil.copyfile(_f, _dst)
    # ★ 2026-11-25（第 294 轮）：背景图改用 res/drawable，**不再走 assets**。
    #
    # 原因：assets 里的 app_bg.jpg 在 Windows 上反复被 aapt2 句柄占着，
    # copy2 / rmtree / rename 三种方式都试过，都会 PermissionError 或路径失效。
    # drawable 走资源编译管线，根本没有"按名覆盖同名文件"这一步 —— 干净得多。
    _load_n = len([x for x in (ROOT / "assets" / "load").glob("*") if x.is_file()]) if (ROOT / "assets" / "load").is_dir() else 0
    print(f"  {len(dirs)} 个 res 目录（{n} 文件）；assets: hplay-adrules.txt + 加载图标 {_load_n} 个")


@step("10. 注入 dex 与 META-INF/services")
def inject(unsigned):
    dexdir = BUILD / "dex"
    for dex in sorted(dexdir.glob("classes*.dex")):
        run([str(AAPT), "add", os.path.relpath(str(unsigned), str(dexdir)), dex.name],
            cwd=str(dexdir), quiet=True)
    # services：从依赖 jar 收集并合并，用 aapt add 以正斜杠参数名注入
    svc_stage = BUILD / "services_stage" / "META-INF" / "services"
    if svc_stage.exists():
        shutil.rmtree(svc_stage.parents[1])
    svc_stage.mkdir(parents=True)
    merged = {}
    jars = ["build/app.jar"] + (BUILD / "dep_jars.txt").read_text(encoding="utf-8").split()
    for rel in jars:
        p = ROOT / rel.replace("/", os.sep)
        if not p.exists():
            continue
        with zipfile.ZipFile(p) as z:
            for n in z.namelist():
                if n.startswith("META-INF/services/") and not n.endswith("/"):
                    lines = z.read(n).decode("utf-8", "replace").splitlines()
                    merged.setdefault(n, set()).update(x.strip() for x in lines if x.strip())
    added = 0
    for name, lines in sorted(merged.items()):
        rel = name.replace("META-INF/services/", "")
        f = svc_stage / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text("\n".join(sorted(lines)) + "\n", encoding="utf-8")
        relpath = os.path.relpath(str(unsigned), str(svc_stage.parents[1]))
        run([str(AAPT), "add", relpath, name], cwd=str(svc_stage.parents[1]), quiet=True)
        added += 1
    print(f"  dex {len(list(dexdir.glob('classes*.dex')))} 个；services {added} 个")



def pack_native_lib(apk):
    """
    ★ 第 147 轮（从实验区 v1.1.32 复刻）：把内置节点内核打进 APK 的 lib/arm64-v8a/。
    必须走 lib/ 而不是 assets：Android 10+ 的 W^X 政策禁止从可写 filesDir 执行二进制，
    唯一合法位置是 APK 内的 native library 目录（系统解压到只读 nativeLibraryDir），
    且文件名必须形如 lib*.so，否则系统不会解压。
    """
    lab_xray = ROOT / "内核" / "xray"
    if not lab_xray.exists():
        print("  ⚠ 未找到内核，跳过 lib 打包")
        return
    so_name = "lib/arm64-v8a/libxray.so"
    tmp = apk.with_suffix(".lib.apk")
    with zipfile.ZipFile(apk, "r") as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename == so_name:
                continue
            zout.writestr(item, zin.read(item.filename))
        zout.write(str(lab_xray), so_name)
    tmp.replace(apk)
    print(f"  ★ 已打入 native lib: {so_name} ({lab_xray.stat().st_size:,} B)")


@step("10. zipalign")
def align(unsigned, aligned):
    if aligned.exists():
        aligned.unlink()
    run([str(ZIPALIGN), "-p", "-f", "4",
         os.path.relpath(str(unsigned), str(ROOT)),
         os.path.relpath(str(aligned), str(ROOT))])
    print(f"  {aligned.stat().st_size} B")


def _eocd(data):
    at = data.rfind(b"PK\x05\x06")
    if at < 0:
        raise SystemExit("no EOCD")
    total = struct.unpack_from("<H", data, at + 10)[0]
    cd_size = struct.unpack_from("<I", data, at + 12)[0]
    cd_off = struct.unpack_from("<I", data, at + 16)[0]
    return cd_off, cd_size, total


def check_zip_separators(apk):
    with open(apk, "rb") as f:
        data = f.read()
    cd_off, cd_size, total = _eocd(data)
    pos = cd_off
    n = bad = 0
    while pos < cd_off + cd_size:
        nlen, elen, clen = struct.unpack_from("<HHH", data, pos + 28)
        lho = struct.unpack_from("<I", data, pos + 42)[0]
        cname = data[pos + 46:pos + 46 + nlen]
        ln = struct.unpack_from("<H", data, lho + 26)[0]
        lname = data[lho + 30:lho + 30 + ln]
        n += 1
        if b"\\" in cname or b"\\" in lname or cname != lname:
            bad += 1
        pos += 46 + nlen + elen + clen
    if n != total:
        raise SystemExit("CD 条目数与 EOCD 不符")
    if bad:
        raise SystemExit(f"{bad} 个条目仍带反斜杠或两头不一致")
    print(f"  原始字节校验：{n} 条目全为正斜杠且两头一致")


@step("11. 反斜杠修复（aapt2 Windows 坑，真机 404 的根因）")
def fix_separators(apk):
    with open(apk, "rb") as f:
        data = f.read()
    cd_off, cd_size, _total = _eocd(data)
    patches = []
    pos = cd_off
    while pos < cd_off + cd_size:
        if data[pos:pos + 4] != b"PK\x01\x02":
            raise SystemExit("bad central header")
        nlen, elen, clen = struct.unpack_from("<HHH", data, pos + 28)
        name = data[pos + 46:pos + 46 + nlen]
        if b"\\" in name:
            patches.append((pos + 46, name.replace(b"\\", b"/")))
        lho = struct.unpack_from("<I", data, pos + 42)[0]
        if data[lho:lho + 4] != b"PK\x03\x04":
            raise SystemExit("bad local header")
        ln = struct.unpack_from("<H", data, lho + 26)[0]
        lname = data[lho + 30:lho + 30 + ln]
        if b"\\" in lname:
            patches.append((lho + 30, lname.replace(b"\\", b"/")))
        pos += 46 + nlen + elen + clen
    if patches:
        with open(apk, "r+b") as f:
            for at, newname in patches:
                f.seek(at)
                f.write(newname)
    print(f"  修正条目 {len(patches)} 个")
    check_zip_separators(apk)


@step("12. 签名（v1+v2+v3）+ 校验")
def sign(aligned, final):
    final.parent.mkdir(parents=True, exist_ok=True)
    if final.exists():
        final.unlink()
    if not KS.exists():
        KS.parent.mkdir(parents=True, exist_ok=True)
        run([str(JDK / "bin" / "keytool.exe"), "-genkeypair", "-v",
             "-keystore", os.path.relpath(str(KS), str(ROOT)), "-alias", KS_ALIAS,
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
             "-storepass", KS_PASS, "-keypass", KS_PASS,
             "-dname", "CN=Hplay Android, OU=AlbireoSaya, O=local, L=local, C=CN"])
    run([str(JAVA), "-jar", str(APKSIGNER_JAR), "sign",
         "--ks", os.path.relpath(str(KS), str(ROOT)), "--ks-key-alias", KS_ALIAS,
         "--ks-pass", f"pass:{KS_PASS}", "--key-pass", f"pass:{KS_PASS}",
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
         "--out", os.path.relpath(str(final), str(ROOT)),
         os.path.relpath(str(aligned), str(ROOT))])
    out, _ = run([str(JAVA), "-jar", str(APKSIGNER_JAR), "verify", "-v",
                  os.path.relpath(str(final), str(ROOT))], quiet=True)
    schemes = [l.split()[0] for l in out.splitlines() if "true" in l]
    print("  签名校验：" + "、".join(schemes[:6]))


@step("13. badging 校验")
def badging(final):
    out, _ = run([str(AAPT2), "dump", "badging",
                  os.path.relpath(str(final), str(ROOT))], quiet=True)
    keep = [l for l in out.splitlines() if l.startswith((
        "package:", "sdkVersion:", "targetSdkVersion:", "application-label:",
        "launchable-activity:", "uses-permission:", "application:"))]
    for l in keep:
        print("  " + l[:160])


def main():
    global VER_VALS, NO_CRED
    ap = argparse.ArgumentParser()
    ap.add_argument("--clean", action="store_true")
    ap.add_argument("--release", action="store_true", help="版本号 +1 并回写 version.properties")
    ap.add_argument("--no-deploy", action="store_true", help="不复制到 成品\\Hplay\\apk\\")
    ap.add_argument("--no-cred", action="store_true",
                    help="兼容壳（第 332 轮起 139 渠道已删除，本来就不再有凭据进 dex）；"
                         "publish_github.py 与 CI 仍在传这个参数，保留以免报 unknown argument")
    args = ap.parse_args()

    if args.no_cred:
        NO_CRED = True

    for p, label in ((JAVA, "JDK"), (KOTLINC_BAT, "kotlinc"), (AAPT2, "aapt2"),
                     (ANDROID_JAR_SRC, "android.jar"), (DEPS, "deps")):
        if not Path(p).exists():
            raise SystemExit(f"工具链缺失：{label} -> {p}")

    BUILD.mkdir(parents=True, exist_ok=True)
    if args.clean:
        for sub in ("gen", "dex", "res-merged", "assets", "services_stage"):
            if (BUILD / sub).exists():
                shutil.rmtree(BUILD / sub)
        for f in ("app.jar", "res.flata", "unsigned-nodex.apk", "unsigned.apk", "aligned.apk"):
            if (BUILD / f).exists():
                (BUILD / f).unlink()
        print("已清 build/ 编译产物")

    vp, VER_VALS = load_version()
    if args.release:
        VER_VALS = bump_version(vp, dict(VER_VALS))
    name, code = version_strings(VER_VALS)
    print(f"版本：{name} (versionCode {code})")

    upstream()
    icons()
    stage_tooling()
    kotlin_compile()
    stage_res()
    unsigned = BUILD / "unsigned.apk"
    aapt2_step(BUILD / "unsigned-nodex.apk")
    gen_r()
    d8_step()
    api_floor()
    shutil.copyfile(BUILD / "unsigned-nodex.apk", unsigned)
    inject(unsigned)
    pack_native_lib(unsigned)
    aligned = BUILD / "aligned.apk"
    align(unsigned, aligned)
    fix_separators(aligned)
    # ★ 第 331 轮：文件名带**发布线**与**版本号**。
    # 两条线包名相同（com.han1me.app），只能靠文件名区分 —— 否则
    # 「Hplay_v1.1.69.apk」到底是实验版还是主线版，发出去就说不清了。
    line_tag = "lab" if GH_LINE == "lab" else "stable"
    final = APK_OUT / f"Hplay_{line_tag}_v{name}.apk"
    sign(aligned, final)
    badging(final)

    if not args.no_deploy:
        dest = WS / "成品" / "Hplay" / "apk"
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copy2(final, dest / final.name)
        print(f"\n已部署：{dest / final.name}")

    # ---- 第 47 轮：发布构建自动推送到 mea 网盘（用户 2026-10-03 要求）----
    # 只在 --release 且实际部署时推；--no-deploy 是编译性验证，不该动网盘。
    # 推送失败**不阻断构建**（APK 已经在盘上了），但必须把失败原因完整打出来 ——
    # 静默失败会让「构建好了但网盘还是旧版」变成没人知道的事。
    if args.release and not args.no_deploy:
        print("\n================ 推送到 mea 网盘（4 通道） ================")
        # 第 273 轮（用户报告「其他网盘里只有旧版本」的根因）：第 113 轮定的 4 通道优先级
        # （移动云盘134 → 移动云盘195 → 天翼云盘134 → 123云盘134）里，只有第①条接进了自动推送，
        # 后 3 条靠人工补推 —— 从 v1.1.58 起就漏了，检查更新的兜底链拿到的是 v1.1.57 旧清单。
        # 现在 4 条全部自动推；单条失败不阻断后面的通道（APK 已在盘上），但失败必须打全。
        pusher = ROOT / "tools" / "push_to_mea.py"
        if not pusher.exists():
            print("  跳过：找不到 tools\\push_to_mea.py")
        else:
            PUSH_DIRS = [
                "/移动云盘134/hplay",        # ① 默认通道
                "/移动云盘195/hplay",        # ② 备用（139 家族另一个账号）
                "/天翼云盘134-个人云/hplay",   # ③ 天翼云盘
                "/123云盘134/hplay",        # ④ 123 云盘
            ]
            failed = []
            for d in PUSH_DIRS:
                print(f"  ---- {d} ----")
                pr = subprocess.run([sys.executable, str(pusher), "--dir", d], cwd=str(ROOT),
                                    capture_output=True, text=True, encoding="utf-8", errors="replace")
                for line in (pr.stdout or "").splitlines():
                    if any(k in line for k in ("RESULT", "失败", "上传", "清单", "mkdir", "大小一致")):
                        print("  " + line)
                if pr.returncode != 0:
                    failed.append(d)
                    print(f"  !! {d} 推送失败（APK 已生成，该通道可能仍是旧版）")
                    for line in (pr.stderr or "").splitlines()[-8:]:
                        print("     " + line)
            if failed:
                print("  !! 通道推送失败：" + "、".join(failed) + "（可用 tools\\push_to_mea.py --dir 手工补推）")
            else:
                print("  4 通道全部已更新")

        # ---- 第 331 轮：**同时发 GitHub Release**（用户要求「每次构建完成新版本
        #      自动更新源码和发行版本」）----
        #
        # 顺序刻意放在网盘之后：网盘是国内用户的主通道，万一 GitHub 这边
        # 卡在网络上（国内直连 GitHub 常常要几十秒），不该让国内那条路白等。
        #
        # 失败同样**不阻断构建**：APK 已在盘上、网盘也已更新；GitHub 那条路
        # 失败只意味着「GitHub 渠道还是旧版」，把原因打出来让用户知道该修哪。
        if not GH_REPO.startswith("OWNER/") and "/" in GH_REPO:
            print("\n================ 发布到 GitHub Releases ================")
            publisher = ROOT / "tools" / "publish_github.py"
            if not publisher.exists():
                print("  跳过：找不到 tools\\publish_github.py")
            else:
                # 先导出（把当前 src/tools/上游 同步到 发布/Hplay），再发布。
                exporter = ROOT / "tools" / "export_github.py"
                if exporter.exists():
                    ex = subprocess.run([sys.executable, str(exporter)], cwd=str(ROOT),
                                        capture_output=True, text=True, encoding="utf-8", errors="replace")
                    print("  导出：" + ("成功" if ex.returncode == 0 else "失败（继续发布已有内容）"))
                    if ex.returncode != 0:
                        for line in (ex.stderr or "").splitlines()[-6:]:
                            print("     " + line)
                pub = subprocess.run(
                    [sys.executable, str(publisher),
                     "--repo", GH_REPO,
                     "--line", "lab" if GH_LINE == "lab" else "stable",
                     "--apk", str(final)],
                    cwd=str(ROOT), capture_output=True, text=True, encoding="utf-8", errors="replace")
                for line in (pub.stdout or "").splitlines():
                    if any(k in line for k in ("仓库", "发布线", "版本", "APK", "Release", "推送", "上传", "OK", "完成")):
                        print("  " + line)
                if pub.returncode != 0:
                    print("  !! GitHub 发布失败（GitHub 渠道可能仍是旧版）：")
                    for line in ((pub.stdout or "") + (pub.stderr or "")).splitlines()[-10:]:
                        print("     " + line)
        else:
            print("\n跳过 GitHub 发布：构建时没有设 HPLAY_GH_REPO（或仍是 OWNER/REPO 占位）")

    print("\n================ build summary ================")
    for n, dt in TIMINGS:
        print(f"  {dt:7.1f}s  {n}")
    print(f"  APK: {final} ({final.stat().st_size/1048576:.1f} MB)")


if __name__ == "__main__":
    main()