#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""主线完整性自检（第 266 轮防火墙）。
对照 tools/integrity_manifest.sha256 重算 SHA256，任何差异/缺失即报告。
用法: python tools/verify_integrity.py   （退出码 0=PASS 1=TAMPERED）
"""
import hashlib, sys, os
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MANIFEST = os.path.join(HERE, "integrity_manifest.sha256")
bad, missing = [], []
n = 0
with open(MANIFEST, "r", encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line: continue
        h, rel = line.split("  ", 1)
        p = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.isfile(p):
            missing.append(rel); continue
        n += 1
        d = hashlib.sha256(open(p, "rb").read()).hexdigest()
        if d != h: bad.append(rel)
print("校验文件数:", n)
if bad:
    print("!! 篡改/变更", len(bad), "个:")
    for x in bad: print("   M", x)
if missing:
    print("!! 缺失", len(missing), "个:")
    for x in missing: print("   -", x)
if bad or missing:
    print("RESULT: TAMPERED —— 主线被改动。回滚金印: 备份/Hplay安卓/金印-v1.1.69/")
    sys.exit(1)
print("RESULT: PASS —— 主线与基线一致")
sys.exit(0)
