# -*- coding: utf-8 -*-
"""第 271 轮：139 凭据体检。

「检查更新」走自建网盘兜底时，第一件事就是核对 139 凭据是否还活着。
本脚本用 tools/139_cred.txt 里的每枚 token 走一次 route 接口（App 同款签名算法），
逐个打印 ✓/✗。全部 ✗ 就说明该去 OpenList（/api/admin/storage/list，139Yun 挂载的
addition.authorization）取现行 token 回填本文件了。

用法：
    python tools/check_139.py
"""
import base64
import hashlib
import json
import sys
import urllib.request
from pathlib import Path
from urllib.parse import quote

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

CRED = Path(__file__).parent / "139_cred.txt"
ROUTE = "https://user-njs.yun.139.com/user/route/qryRoutePolicy"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/133 Safari/537.36"


def md5(b: bytes) -> str:
    return hashlib.md5(b).hexdigest()


def cal_sign(body: str, ts: str, rand: str) -> str:
    sorted_body = "".join(sorted(quote(body)))
    b64 = base64.b64encode(sorted_body.encode()).decode()
    a = md5(b64.encode())
    b = md5(md5(f"{ts}:{rand}".encode()).encode())
    return md5((a + b).encode()).upper()


def account_of(auth: str) -> str:
    try:
        pad = "=" * ((4 - len(auth) % 4) % 4)
        return base64.b64decode(auth + pad).decode().split(":")[1]
    except Exception:
        return ""


def probe(auth: str) -> tuple[bool, str]:
    import datetime
    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    rand = "abcdefghij0123456"
    body = json.dumps({"userInfo": {"userType": 1, "accountType": 1, "accountName": account_of(auth)}, "modAddrType": 1})
    headers = {
        "Accept": "application/json, text/plain, */*",
        "CMS-DEVICE": "default",
        "Authorization": "Basic " + auth,
        "mcloud-channel": "1000101",
        "mcloud-client": "10701",
        "mcloud-sign": f"{ts},{rand},{cal_sign(body, ts, rand)}",
        "mcloud-version": "7.14.0",
        "Origin": "https://yun.139.com",
        "Referer": "https://yun.139.com/w/",
        "x-DeviceInfo": "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||",
        "x-huawei-channelSrc": "10000034",
        "x-inner-ntwk": "2",
        "x-m4c-caller": "PC",
        "x-m4c-src": "10002",
        "x-SvcType": "1",
        "Inner-Hcy-Router-Https": "1",
        "Content-Type": "application/json",
        "User-Agent": UA,
    }
    req = urllib.request.Request(ROUTE, data=body.encode(), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            j = json.loads(r.read().decode())
        if j.get("success") is False:
            return False, f"{j.get('code')} {str(j.get('message'))[:40]}"
        host = next((x.get("httpsUrl") for x in (j.get("data") or {}).get("routePolicyList", []) if x.get("modName") == "personal"), "")
        return bool(host), host or "无 personal host"
    except Exception as e:
        return False, repr(e)[:80]


def main():
    creds = [l.strip() for l in CRED.read_text(encoding="utf-8").splitlines() if l.strip() and not l.strip().startswith("#")]
    print(f"共 {len(creds)} 枚凭据")
    all_ok = True
    for i, c in enumerate(creds, 1):
        acct = account_of(c)
        acct = acct[:3] + "****" + acct[-4:] if len(acct) == 11 else acct
        ok, info = probe(c)
        all_ok = all_ok and ok
        print(f"  #{i} {acct}: {'✓ ' + info if ok else '✗ ' + info}")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
