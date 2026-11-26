# -*- coding: utf-8 -*-
"""R45 预检：按 build.py 同一步骤在内存里重放 main_app 补丁，逐条报命中次数。

build.py 的断言是 `count(old) == 1`，任一失配就 SystemExit —— 但那是整个构建的第一步，
要等 kotlinc 之外的东西都准备好。这个脚本只做第一步，几秒内给出每条规则的命中数，
并且**不写任何文件**（不碰 build/gen，不碰上游）。
"""
import ast
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent      # 工程\Hplay安卓
AVPLAY = ROOT.parent / "AvPlay"
sys.path.insert(0, str(ROOT / "tools"))

import patches as P  # noqa: E402

src = AVPLAY / P.UPSTREAM["main_app"]["src"]
text = src.read_text(encoding="utf-8")
# patches.py 自己先过一遍语法与「反斜杠有没有被转录吃掉」的检查
pfile = ROOT / "tools" / "patches.py"
raw = pfile.read_bytes().decode("utf-8")
ast.parse(raw)
print("patches.py 语法 OK，%d 字节" % len(pfile.read_bytes()))

bad = 0
for i, (old, new) in enumerate(P.UPSTREAM["main_app"]["patches"], 1):
    n = text.count(old)
    mark = "OK " if n == 1 else "!! "
    if n != 1:
        bad += 1
    print("%s#%02d 命中 %d  %r" % (mark, i, n, (old[:46] + ("…" if len(old) > 46 else ""))))
    if n == 1:
        text = text.replace(old, new)

print("---- 重放后的检查 ----")
for probe in ("HplayDownloadsPanel", "HplayDownloads.enqueue", "HplayDownloads.detailFor",
              "var downloading by remember", "var downloadTick by remember",
              "showDownloads -> showDownloads = false", "fromBoard || officialSite",
              "X99.downloadMedia"):
    print("  %-42s x%d" % (probe, text.count(probe)))
raise SystemExit(1 if bad else 0)
