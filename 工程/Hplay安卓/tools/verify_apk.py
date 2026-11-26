# -*- coding: utf-8 -*-
"""APK 静态验收：badging / 清单二进制 / zip 结构 / 签名 / 关键内容齐全性。"""
import os
import re
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = r"D:\工作区\工程\Hplay安卓"
# 取版本号最新的 APK（--release 升级后无需改本脚本）
from pathlib import Path
_ver = lambda f: tuple(int(x) for x in f.name[len("Hplay_v"):-len(".apk")].split("."))
_apks = sorted(Path(ROOT, "apk").glob("Hplay_v*.apk"), key=_ver)   # 按版本号数值排序（字符串序会把 v1.0.9 排在 v1.0.10 之后）
if not _apks:
    raise SystemExit("apk 目录无 Hplay_v*.apk")
APK = str(_apks[-1])
print("verify target:", APK)

# 期望版本号从 version.properties 动态计算（与 build.py 同口径）
vals = {}
for line in open(os.path.join(ROOT, "version.properties"), encoding="utf-8"):
    if "=" in line and not line.strip().startswith("#"):
        k, v = line.split("=", 1)
        vals[k.strip()] = v.strip()
# versionCode 不能按「今天」或「properties 里的 lastdate」去**重算**：
#   --release 与增量构建走的是同一个 version_strings()，code = int(构建当天)*100 + (lastdate==当天 ? seq : 1)，
#   所以隔天做的编译性构建会写成 today*100+1，而 properties 仍是上一次发布那天的 lastdate/seq。
#   重算 = 对好包判假 FAIL（前一棒已经踩过一次，只是当时归因归少了）。
# 改为**自洽校验**：从包里读出来的 code 必须能拆成「8 位真实日期 + ≥1 的两位序号」，且 versionName 与 properties 一致。
EXP_NAME = vals.get("versionName", "1.0.0")
AAPT2 = r"D:\工作区\工具\android-build\bt\android-14\aapt2.exe"
APKSIGNER = r"D:\工作区\工具\android-build\bt\android-14\lib\apksigner.jar"
JAVA = r"D:\工作区\工具\jdk-temurin\jdk-21.0.12.1+1\bin\java.exe"
KS = os.path.join(ROOT, "keystore", "hplay-port.jks")

ok = True


def check(cond, label, detail=""):
    global ok
    print(("  ✓ " if cond else "  ✗ ") + label + (f"  [{detail}]" if detail else ""))
    if not cond:
        ok = False


print("== 1. badging")
r = subprocess.run([AAPT2, "dump", "badging", APK], capture_output=True)
bad = r.stdout.decode("utf-8", "replace")
for line in bad.splitlines():
    if line.startswith(("package:", "sdkVersion", "targetSdkVersion", "application-label:",
                        "launchable-activity", "uses-permission", "application:")):
        print("   " + line[:170])
check("package: name='com.han1me.app'" in bad, "包名 com.han1me.app")
_vc = re.search(r"versionCode='(\d+)'", bad)
_code = _vc.group(1) if _vc else ""
_ok_date = len(_code) == 10 and _code[:8].isdigit() and int(_code[6:8]) <= 12 and int(_code[4:6]) <= 31
check(_ok_date and int(_code[8:]) >= 1, f"versionCode 自洽（{_code} = 日期 + 当日序号）",
      "不重算具体值：增量构建与 --release 用的是同一公式，隔天复放重算必假 FAIL")
check(f"versionName='{EXP_NAME}'" in bad, f"versionName {EXP_NAME}")
check("sdkVersion:'24'" in bad, "minSdk 24")
check("targetSdkVersion:'34'" in bad, "targetSdk 34")
check("launchable-activity: name='com.han1me.MainActivity'" in bad, "启动 Activity")
check("uses-permission: name='android.permission.INTERNET'" in bad, "INTERNET 权限")
check("application-label:'Hplay'" in bad, "应用名 Hplay")

print("== 2. 清单二进制（cleartext / configChanges / backup）")
r = subprocess.run([AAPT2, "dump", "xmltree", "--file", "AndroidManifest.xml", APK],
                   capture_output=True)
xml = r.stdout.decode("utf-8", "replace")
check("usesCleartextTraffic" in xml, "usesCleartextTraffic 存在", "允许可行线路 http 兜底")
check("configChanges" in xml, "configChanges 防重建")

print("== 3. zip 结构")
with zipfile.ZipFile(APK) as z:
    names = z.namelist()
    sizes = {n: z.getinfo(n) for n in names}


def uleb128(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, off
        shift += 7


def defined_kotlin_classes(dex):
    """解析 dex 头 + class_defs，统计已定义（非仅引用）的 Lkotlin/ 开头类数。"""
    if dex[:4] != b"dex\n":
        return 0
    string_ids_size, string_ids_off = struct.unpack_from("<II", dex, 0x38)
    type_ids_size, type_ids_off = struct.unpack_from("<II", dex, 0x40)
    class_defs_size, class_defs_off = struct.unpack_from("<II", dex, 0x60)
    str_offs = struct.unpack_from("<%dI" % string_ids_size, dex, string_ids_off)

    def descriptor(type_idx):
        sidx = struct.unpack_from("<I", dex, type_ids_off + 4 * type_idx)[0]
        off = str_offs[sidx]
        _, p = uleb128(dex, off)
        end = dex.index(b"\x00", p)
        return dex[p:end]

    n = 0
    for i in range(class_defs_size):
        base = class_defs_off + 32 * i
        type_idx = struct.unpack_from("<H", dex, base)[0]
        if descriptor(type_idx).startswith(b"Lkotlin/"):
            n += 1
    return n


import struct  # noqa: E402

with zipfile.ZipFile(APK) as z:
    dex_bytes = {n: z.read(n) for n in names
                 if n.startswith("classes") and n.endswith(".dex")}
kotlin_defs = sum(defined_kotlin_classes(d) for d in dex_bytes.values())
check(kotlin_defs >= 300, f"Kotlin 运行时已入 dex（已定义 Lkotlin/* 类 {kotlin_defs} 个 ≥ 300）",
      "v1.0.1 闪退根因即 stdlib 缺失，此检查防复发")
check("classes.dex" in names, "classes.dex")
check("classes2.dex" in names, "classes2.dex（多 dex）")
check("assets/hplay-adrules.txt" in names, "assets: 去广告规则")
check("resources.arsc" in names, "resources.arsc")
svc = [n for n in names if n.startswith("META-INF/services/")]
check(len(svc) >= 3, f"META-INF/services × {len(svc)}（coroutines Main / coil fetcher 等）",
      "; ".join(s.split("/")[-1][:40] for s in svc[:5]))
mips = [n for n in names if n.startswith("res/mipmap")]
check(len(mips) >= 5, f"mipmap 图标 × {len(mips)}")
print("   dex 大小: classes.dex=%.1fMB classes2.dex=%.1fMB" % (
    sizes["classes.dex"].file_size / 1048576, sizes["classes2.dex"].file_size / 1048576))

print("== 4. 签名")
r = subprocess.run([JAVA, "-jar", APKSIGNER, "verify", "--print-certs", APK],
                   capture_output=True)
out = r.stdout.decode("utf-8", "replace")
check("DOES NOT VERIFY" not in out and r.returncode == 0, "apksigner verify 通过")
check("CN=Hplay Android" in out, "签名者 CN=Hplay Android")

print("== 5. 对齐与反斜杠")
r = subprocess.run([os.path.join(os.path.dirname(AAPT2), "zipalign.exe"), "-c", "4", APK],
                   capture_output=True)
check(r.returncode == 0, "zipalign -c 4 校验通过")

print()
print("RESULT:", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
