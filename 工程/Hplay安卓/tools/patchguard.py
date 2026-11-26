# -*- coding: utf-8 -*-
"""补丁预检：在花 90 秒构建之前，先把 patches.py 的问题挡下来。

检查项（与 build.py 同一套重放规则，但更快、更早）：
  1. tools/patches.py 能被 ast.parse —— 防止文本 replace 把文件改坏
  2. 每条补丁的 old 在重放上下文中命中恰好 1 次 —— 防止锚点重复/失配
  3. tail 标记存在
  4. 生成结果的结构体检：三引号配对、括号配平
  5. 行内尾注释吞代码 —— 第 207 轮构建失败的那个坑

用法：
  python tools/patchguard.py          # 全量预检，退出码 0 = 通过
  python tools/patchguard.py --quiet  # 只打印问题
"""
import ast
import importlib.util
import io
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WS = ROOT.parent.parent
AVPLAY = WS / "工程" / "AvPlay"
GEN = ROOT / "build" / "gen"
PATCHES = ROOT / "tools" / "patches.py"

FAILS = []
WARNS = []


def fail(msg):
    FAILS.append(msg)


def warn(msg):
    WARNS.append(msg)


# ---------- 1. patches.py 结构 ----------
def load_patches():
    src = PATCHES.read_text(encoding="utf-8")
    try:
        ast.parse(src)
    except SyntaxError as e:
        fail("patches.py 语法错误：%s (line %s)" % (e.msg, e.lineno))
        return None
    spec = importlib.util.spec_from_file_location("patches", str(PATCHES))
    mod = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(mod)
    except Exception as e:
        fail("patches.py 导入失败：%r" % (e,))
        return None
    if not hasattr(mod, "UPSTREAM"):
        fail("patches.py 缺少 UPSTREAM")
        return None
    return mod


# ---------- 2. 重放 ----------
def replay(mod):
    results = {}
    for key, spec in mod.UPSTREAM.items():
        out = spec["out"]
        src_path = AVPLAY / spec["src"]
        if not src_path.exists():
            fail("%s: 上游源不存在 %s" % (out, src_path))
            continue
        text = src_path.read_text(encoding="utf-8")
        for idx, item in enumerate(spec.get("patches", [])):
            if not (isinstance(item, tuple) and len(item) == 2):
                fail("%s: 第 %d 条补丁不是二元组（类型 %s）" % (out, idx, type(item).__name__))
                break
            old, new = item
            if not isinstance(old, str) or not isinstance(new, str):
                fail("%s: 第 %d 条补丁不是字符串对" % (out, idx))
                break
            n = text.count(old)
            if n != 1:
                fail("%s: 第 %d 条补丁 old 命中 %d 次（应为 1）：%r" % (out, idx, n, old[:60]))
                break
            text = text.replace(old, new)
        else:
            if "tail" in spec:
                marker, new_tail = spec["tail"]
                i = text.find(marker)
                if i < 0:
                    fail("%s: tail 标记未找到 %r" % (out, marker[:60]))
                else:
                    text = text[:i] + new_tail
            results[out] = text
    # 套用后置规则，让结构体检看到最终形态
    try:
        import importlib.util as _iu
        _sp = _iu.spec_from_file_location("postpatch", str(ROOT / "tools" / "postpatch.py"))
        _pp = _iu.module_from_spec(_sp)
        _sp.loader.exec_module(_pp)
        for out in list(results.keys()):
            try:
                results[out] = _pp.apply_rules(results[out], out)
            except SystemExit as e:
                fail(str(e))
    except Exception as e:
        fail("后置规则加载失败：%r" % (e,))
    return results


# ---------- 3. 结构体检 ----------
def _skip_raw(text, i, out):
    # 跳过 Kotlin 原始字符串（三引号），但保留模板表达式内的结构字符。
    # Kotlin 允许 """...一（4 个引号）：最后一组 3 个才是终结符，前面的属于内容。
    n = len(text)
    while i < n:
        if text[i] == '"':
            j = i
            while j < n and text[j] == '"':
                j += 1
            if j - i >= 3:
                return j
            i = j
            continue
        if text.startswith("$" + "{", i):
            i = _brace_expr(text, i + 1, out)
            continue
        i += 1
    fail("三引号未闭合")
    return i


def _brace_expr(text, i, out):
    # text[i] == "{"，跳过模板表达式，把其中的括号结构写进 out。
    n = len(text)
    depth = 0
    while i < n:
        if text.startswith('"""', i):
            i = _skip_raw(text, i + 3, out)
            out.append(" ")
            continue
        ch = text[i]
        if ch == '"':
            i = _skip_plain(text, i + 1, '"', out)
            out.append(" ")
            continue
        if ch == "'":
            i = _skip_plain(text, i + 1, "'", out)
            out.append(" ")
            continue
        if ch == "{":
            depth += 1
            out.append("{")
            i += 1
            continue
        if ch == "}":
            depth -= 1
            out.append("}")
            i += 1
            if depth == 0:
                return i
            continue
        if ch in "()[]":
            out.append(ch)
        i += 1
    return i


def _skip_plain(text, i, q, out):
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "\\":
            i += 2
            continue
        if ch == q:
            return i + 1
        if ch == "$" and i + 1 < n and text[i + 1] == "{":
            i = _brace_expr(text, i + 1, out)
            continue
        if ch == "\n":
            return i
        i += 1
    return i


def strip_kotlin(text):
    # 去掉字符串与注释，只留结构字符。
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            out.append("\n")
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            out.append(" ")
            continue
        if text.startswith('"""', i):
            i = _skip_raw(text, i + 3, out)
            out.append(" ")
            continue
        if text[i] in "\"'":
            i = _skip_plain(text, i + 1, text[i], out)
            out.append(" ")
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def check_balance(out, text):
    s = strip_kotlin(text)
    pairs = {")": "(", "]": "[", "}": "{"}
    stack = []
    for ch in s:
        if ch in "([{":
            stack.append(ch)
        elif ch in pairs:
            if not stack or stack[-1] != pairs[ch]:
                fail("%s: 括号不配平（多余的 %r）" % (out, ch))
                return
            stack.pop()
    if stack:
        fail("%s: 括号未闭合（残留 %r）" % (out, "".join(stack[-5:])))


CODE_AFTER_COMMENT = (".filter", ".map", ".distinct", ".toList", ".take",
                      ".sortedBy", ".firstOrNull", ".drop")


def check_inline_comment(out, text):
    """行内 // 之后又跟代码链式调用 —— 第 207 轮把 .filter/.toList 注释掉的坑。"""
    for ln, line in enumerate(text.splitlines(), 1):
        p = line.find("//")
        if p <= 0:
            continue
        before, after = line[:p], line[p + 2:]
        if not before.strip():
            continue
        # 注释后仍出现第二处 // 且带代码链 —— 典型塌行
        if "//" in after and any(k in after for k in CODE_AFTER_COMMENT):
            fail("%s:%d 行内注释吞掉了代码链（%s）" % (out, ln, after.strip()[:70]))
            continue
        if len(line) > 200 and any(k in after for k in CODE_AFTER_COMMENT):
            warn("%s:%d 超长行且注释疑似含代码（%s）" % (out, ln, after.strip()[:70]))


def main():
    quiet = "--quiet" in sys.argv
    mod = load_patches()
    if mod is None:
        report(quiet)
        return 1
    results = replay(mod)
    for out, text in results.items():
        if text.count('"""') % 2:
            fail("%s: 三引号数量为奇数" % out)
        check_balance(out, text)
        check_inline_comment(out, text)
        if text.count("\x00"):
            fail("%s: 生成结果含 NUL" % out)
        print("  %-16s %6d 行  补丁 %s" % (
            out, text.count("\n") + 1,
            len(mod.UPSTREAM[[k for k, v in mod.UPSTREAM.items() if v["out"] == out][0]].get("patches", []))))
    report(quiet)
    return 1 if FAILS else 0


def report(quiet):
    if WARNS and not quiet:
        print("\n  WARN %d 条" % len(WARNS))
        for w in WARNS[:10]:
            print("    ! " + w)
    if FAILS:
        print("\n  FAIL %d 条" % len(FAILS))
        for f in FAILS[:20]:
            print("    x " + f)
        print("\n预检未通过，不要开始构建。")
    else:
        print("\n预检通过（%d 个输出文件）。" % len(WARNS), end="\n")


if __name__ == "__main__":
    sys.exit(main())
