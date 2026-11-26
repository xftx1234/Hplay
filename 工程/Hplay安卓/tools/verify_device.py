# -*- coding: utf-8 -*-
"""设备验证一条龙：装包 -> 启动 -> 等探测 -> 拉日志 -> 只打印关键行。

用法：
  python tools/verify_device.py                # 用 apk/ 下最新 APK
  python tools/verify_device.py --apk xxx.apk
  python tools/verify_device.py --timeout 240  # 等探测完成的最长秒数
  python tools/verify_device.py --continue     # 只拉日志，不装不启动
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = r"D:/工作区/工具/android-build/platform-tools/adb.exe"
DEV = "LMG820TMf09b0c53"
PKG = "com.han1me.app"
LOG = "/sdcard/Android/data/com.han1me.app/files/hpla_lab.log"
LOCAL = ROOT / "build" / "hpla_lab.log"

KEY = re.compile(r"\[x99\].*(列表 |原生 |播址 |板块 |详情 |探测完成|失配|error)", re.I)


def adb(*args, timeout=300):
    return subprocess.run([ADB, "-s", DEV] + [str(a) for a in args],
                          capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)


def latest_apk():
    apks = sorted((ROOT / "apk").glob("Hplay_v*.apk"), key=lambda p: p.stat().st_mtime)
    if not apks:
        sys.exit("apk/ 下没有 APK")
    return apks[-1]


def install_and_start(apk):
    print("[1/3] 安装", apk.name)
    r = adb("install", "-r", "-d", apk, timeout=300)
    if "Success" not in (r.stdout or ""):
        print((r.stdout or "") + (r.stderr or ""))
        sys.exit("安装失败")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "logcat", "-c")
    time.sleep(1)
    adb("shell", "am", "start", "-n", PKG + "/com.han1me.MainActivity")
    print("[2/3] 已启动，等待探测")


def wait_probe(timeout):
    t0 = time.time()
    while time.time() - t0 < timeout:
        time.sleep(8)
        r = adb("shell", "grep", "-a", "探测完成", LOG, timeout=60)
        if "探测完成" in (r.stdout or ""):
            print("[3/3] 探测完成，用时 %ds" % int(time.time() - t0))
            return True
        print("  等待中 %ds" % int(time.time() - t0), flush=True)
    print("[!] 超时，仍尝试拉日志")
    return False


def dump_log():
    LOCAL.parent.mkdir(parents=True, exist_ok=True)
    adb("pull", LOG, str(LOCAL), timeout=120)
    if not LOCAL.exists():
        sys.exit("拉不到日志")
    text = LOCAL.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    print("--- 日志 %d 行，关键行如下 ---" % len(lines))
    hits = [l for l in lines if KEY.search(l)]
    for l in hits[-45:]:
        print(l[:190])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk")
    ap.add_argument("--timeout", type=int, default=240)
    ap.add_argument("--continue", dest="cont", action="store_true")
    a = ap.parse_args()
    if not a.cont:
        install_and_start(Path(a.apk) if a.apk else latest_apk())
        wait_probe(a.timeout)
    dump_log()


if __name__ == "__main__":
    main()
