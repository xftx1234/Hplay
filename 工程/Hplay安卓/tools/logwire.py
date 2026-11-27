# -*- coding: utf-8 -*-
"""**日志接线器**（第 334 轮）。

把 [com.han1me.log.LoggingInterceptor] 挂到项目里**每一个** OkHttpClient.Builder 上。

## 为什么单独一个脚本

| 工具 | 作用域 | 为什么不用它 |
|---|---|---|
| patches.py | build/gen（上游重放） | 只能改上游文件，改不到 src 下自写的 6 个客户端 |
| postpatch.py | build/gen（定点替换） | 同上，且要求 old 恰好命中 1 次 —— 这里有 14 处 |
| globalpatch.py | build/gen（批量） | 作用域同样只有 gen |
| **本脚本** | **gen + src** | 两个目录都要覆盖，且要做「按 builder 定位插入」这种结构化操作 |

## 做法

对每个文件扫描 `OkHttpClient.Builder()` 出现位置，在**紧随其后的第一行链式调用之前**
插入 `.addInterceptor(com.han1me.log.LoggingInterceptor)`。

为什么插在链式调用的第一行而不是 `.build()` 前：
  · `.build()` 前的位置需要精确匹配缩进与最后一个调用，容易失配；
  · 紧接 `Builder()` 之后的位置是稳定的（后面一定是 `.` 开头的链式调用）。

幂等：已含 `LoggingInterceptor` 的文件整段跳过，重复构建不会插两次。
"""
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GEN = ROOT / "build" / "gen"
SRC = ROOT / "src"

MARK = "com.han1me.log.LoggingInterceptor"


def wire_file(f: Path) -> int:
    text = io.open(f, encoding="utf-8").read()
    if MARK in text:
        return 0
    if "OkHttpClient.Builder()" not in text:
        return 0

    out = []
    lines = text.split("\n")
    n = 0
    i = 0
    while i < len(lines):
        ln = lines[i]
        out.append(ln)
        # 命中 builder 行：本行可能自带链式调用（`.proxySelector(...)` 同行）
        if "OkHttpClient.Builder()" in ln:
            # 若本行以 `)` 结尾且没有后续链式调用，说明 builder 单独成行
            stripped = ln.rstrip()
            if stripped.endswith("Builder()"):
                # 找下一行的缩进，用它作为插入行的缩进
                nxt = lines[i + 1] if i + 1 < len(lines) else ""
                m = re.match(r"^(\s*)", nxt)
                indent = m.group(1) if m else (re.match(r"^(\s*)", ln).group(1) + "    ")
                out.append(indent + ".addInterceptor(" + MARK + ")")
                n += 1
            else:
                # 同行链式：`.proxySelector(...)` 之类，插在 Builder() 之后
                head, sep, tail = ln.partition("OkHttpClient.Builder()")
                if sep and tail.lstrip().startswith("."):
                    indent = re.match(r"^(\s*)", ln).group(1)
                    out[-1] = head + sep
                    out.append(indent + "    .addInterceptor(" + MARK + ")" + tail)
                    n += 1
        i += 1

    if n:
        io.open(f, "w", encoding="utf-8", newline="\n").write("\n".join(out))
    return n


def main():
    total = 0
    for base in (GEN, SRC):
        if not base.exists():
            continue
        for f in sorted(base.rglob("*.kt")):
            try:
                n = wire_file(f)
            except Exception as e:
                print("  !! " + f.name + ": " + repr(e))
                continue
            if n:
                print("  " + str(f.relative_to(ROOT)).replace("\\", "/") + ": 挂载 " + str(n) + " 处")
                total += n
    print("日志拦截器共挂载 " + str(total) + " 处")
    if total == 0:
        print("（0 处：可能已全部挂载过，或没找到任何 OkHttpClient.Builder）")
    return 0


if __name__ == "__main__":
    sys.exit(main())