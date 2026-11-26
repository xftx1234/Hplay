# -*- coding: utf-8 -*-
"""把上游 onDownload 块的每一行按 repr 打出来，用来对齐补丁锚点里那几个字符。"""
from pathlib import Path

AVPLAY = Path(__file__).resolve().parent.parent.parent / "AvPlay"
p = AVPLAY / "独立版/src/com/han1me/Han1meApp.kt"
lines = p.read_text(encoding="utf-8").split("\n")
for i in range(594, 617):
    print("%3d %s" % (i + 1, repr(lines[i])))
