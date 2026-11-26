# -*- coding: utf-8 -*-
"""第 47 轮：构建完成后自动把 APK 推送到 mea 网盘的 移动云盘134/hplay。

设计要点（每条都是实测出来的，不是照文档猜的）：

 1. **目标目录含中文，而 HTTP 头只能 latin-1**。
    上传走 PUT /api/fs/put + File-Path 头，头值必须纯 ASCII，
    所以中文路径一律 **percent-encoding**（实测：编码后 code=200 success，明文会抛
    UnicodeEncodeError）。第 43 轮交付脚本踩的就是这个坑。
 2. **OpenList 的 mkdir/put/remove 用的是 JSON body**（可带中文），不受上面那条限制。
 3. **权限位是借还的**：本工程历史上共享账号平时只读（28935），上传前借 29183、传完立刻还，
    并且**六种写操作逐项实测被拒**（只测 mkdir/put 不够 —— 曾经 29175 挡不住 rename）。
    本脚本读的是**当前真实权限位**：若已是可写（如 65535）就不借不还，只在开工/收尾各记一行；
    若发现是只读，则走「借 → 传 → 还」并在最后逐项复验。
    ⚠ 改权限会作废所有已发 token ⇒ 每次改完都要**重新登录**。
 4. **不存在的 /api 路由会被 SPA 兜底成 200 + HTML** ⇒ 判成功只看 JSON 里的 code==200。
 5. **凭据只读、不进日志**：从 admin_cred.txt 读，打印时只回显用户名。
 6. **传完双向复验**：远端 fs/get 的 size 要对上，raw_url 要能真取到前若干字节，
    最后再对本地文件算一次 md5 写进清单。

用法：
    python tools/push_to_mea.py                 # 推当前 version.properties 对应的 APK
    python tools/push_to_mea.py --apk <路径>     # 指定 APK
    python tools/push_to_mea.py --check          # 只检查网盘上现在是什么版本，不上传
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

# Windows 控制台默认 GBK，print 里出现 ✓ 这类非 GBK 字符会直接抛 UnicodeEncodeError
# （本轮实测：APK 已上传成功、清单已写，却在打印 "远端大小一致 OK" 时崩掉，
#   于是构建侧看到的是"推送失败"，而网盘上其实已经是新版 —— 这种"成功被报成失败"最难查）。
# 统一把 stdout/stderr 重配成 UTF-8，并且**任何 print 都不该让流程失败**。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ---- 端点与位置（软路由上的 OpenList；本机 127.0.0.1:5244 已不再监听，见记忆库）----
#
# ★ 第 331 轮（进公开仓库前处理）：内网地址**不再硬编码**。
# 这个脚本会随工程一起进公开仓库，把内网 IP 写死在里面等于公开本机网络拓扑。
# 现在从 tools/lan_endpoint.txt 读（该文件在 .gitignore 里，不入 git），
# 缺文件时只用公网端点 —— 家里跑推送时公网也能到，只是多一跳。
def _lan() -> str:
    p = Path(__file__).resolve().parent / "lan_endpoint.txt"
    try:
        for line in p.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                return line.rstrip("/")
    except Exception:
        pass
    return ""


LAN = _lan()
PUB = "https://mea.yuzusoft.ccwu.cc"
CRED = Path(r"D:\quder工作区\文件共享\openlist\admin_cred.txt")
# 用户 2026-10-03 指定的落点（参考图里选中的那个文件夹）
REMOTE_DIR = "/移动云盘134/hplay"
MANIFEST = "version.json"

# ----------------------------------------------------------------
# **永久保留清单**（用户 2026-10-05 明确要求：「网盘永久保留 v1.0.38」）。
#
# 为什么要写进脚本：此前每轮收尾都由我临时写一段清理脚本、各自带一个 KEEP 集合，
# 结果是第 64 轮那个集合漏了 v1.0.38，**把这个回滚锚点在网盘上删掉了**（已恢复）。
# 靠"记得"不可靠 —— 把保留清单**固化在这里**，任何清理都必须先读它。
#
# 纪律：**本清单里的文件永不允许从网盘删除**；
#      只有用户明确说"可以删"时，才由用户口述移除并同步改这里。
# ----------------------------------------------------------------
PINNED = [
    "Hplay_v1.0.38.apk",   # ★ 用户要求永久保留（第 62 轮回滚锚点 / tag hplay-stable-v1.0.38）
    "Hplay_v1.0.22.apk",   # 长期稳定版（第 46 轮）
]

# 权限位
RO, RW = 28935, 29183

ROOT = Path(__file__).resolve().parent.parent
APK_DIR = ROOT / "apk"
VERSION_FILE = ROOT / "version.properties"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")


def enc(p):
    """中文路径 → percent-encoding（HTTP 头只能 latin-1）。"""
    return urllib.parse.quote(p, safe="/")


def api(path, body=None, token=None, raw=None, method=None, headers=None, timeout=300):
    # LAN 可能是空串（tools/lan_endpoint.txt 不存在时）→ 退回公网端点。
    # 这样这份脚本在别人的机器上也能直接跑，不需要先配内网地址。
    base = LAN or PUB
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(base + path, data=data, method=method or ("POST" if data else "GET"))
    if raw is None:
        req.add_header("Content-Type", "application/json")
    req.add_header("User-Agent", UA)
    if token:
        req.add_header("Authorization", token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            txt = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return {"code": e.code, "message": (e.read().decode("utf-8", "replace") or "")[:250], "layer": "http"}
    except Exception as e:
        return {"code": -1, "message": repr(e)[:200], "layer": "transport"}
    try:
        j = json.loads(txt)
        j["layer"] = "json"
        return j
    except Exception:
        return {"code": -1, "message": "非 JSON（SPA 兜底 ⇒ 路由不存在）", "layer": "spa", "data": txt[:160]}


def login():
    user, pwd = CRED.read_text(encoding="utf-8").strip().split(":", 1)
    r = api("/api/auth/login", {"username": user, "password": pwd})
    if r.get("code") != 200:
        raise SystemExit("登录失败：%s %s" % (r.get("code"), str(r.get("message"))[:150]))
    print("  登录 OK（用户 %s）" % user)
    return r["data"]["token"]


def me(tk):
    return (api("/api/me", token=tk).get("data") or {}).get("permission")


def admin_record(tk):
    last = {}
    for attempt in ({"method": "GET"}, {"body": {"page": 1, "per_page": 50}}):
        r = api("/api/admin/user/list", token=tk, **attempt)
        d = r.get("data")
        users = d if isinstance(d, list) else ((d or {}).get("content") if isinstance(d, dict) else None)
        if users:
            for u in users:
                if u.get("role") == 2:
                    return u
            raise SystemExit("用户列表里没有 role=2")
        last = r
    raise SystemExit("取管理员记录失败：%s %s" % (last.get("code"), str(last.get("message"))[:150]))


def set_permission(tk, value):
    u = admin_record(tk)
    body = {k: u.get(k) for k in ("id", "username", "password", "base_path", "role", "disabled",
                                  "can_login", "sso_id", "otp", "guest_view", "default_page")}
    body.update(permission=value, use_otp=False)
    body["password"] = ""            # 空串 = 不改密码（OpenList 语义）
    return api("/api/admin/user/update", body, tk)


def borrow(value):
    """改权限 → 重新登录 → 实测这一位真的生效（返回值对不等于生效）。"""
    tk = login()
    r = set_permission(tk, value)
    if r.get("code") != 200:
        raise SystemExit("改权限到 %s 失败：%s" % (value, str(r.get("message"))[:150]))
    tk = login()
    now = me(tk)
    assert now == value, "权限位与要求不符（要求 %s 实得 %s），停止" % (value, now)
    return tk


def put_file(tk, remote, data):
    return api("/api/fs/put", token=tk, raw=data, method="PUT", headers={"File-Path": enc(remote)})


def listing(tk, path):
    r = api("/api/fs/list", {"path": path, "page": 1, "per_page": 0, "refresh": True}, tk)
    d = r.get("data") or {}
    return [(x.get("name"), x.get("size")) for x in (d.get("content") or [])], r.get("code")


def six_write_attempts(tk):
    """六种写操作各发一发。判「被拒」只认 403：400/500 是形状写错，不算归还成功。"""
    probe = REMOTE_DIR + "/_r47_perm_probe.txt"
    probe_dir = REMOTE_DIR + "/_r47_perm_probe_dir"
    ops = [
        ("put", lambda: api("/api/fs/put", token=tk, raw=b"probe", method="PUT", headers={"File-Path": enc(probe)})),
        ("mkdir", lambda: api("/api/fs/mkdir", {"path": probe_dir}, tk)),
        ("rename", lambda: api("/api/fs/rename", {"path": probe, "name": "_r47_probe_renamed"}, tk)),
        ("remove", lambda: api("/api/fs/remove", {"names": [probe]}, tk)),
        ("copy", lambda: api("/api/fs/copy", {"src_dir": REMOTE_DIR, "dst_dir": REMOTE_DIR,
                                              "names": ["_r47_perm_probe.txt"]}, tk)),
        ("move", lambda: api("/api/fs/move", {"src_dir": REMOTE_DIR, "dst_dir": REMOTE_DIR,
                                              "names": ["_r47_perm_probe.txt"]}, tk)),
    ]
    out = []
    for n, fn in ops:
        r = fn()
        code = r.get("code")
        out.append((n, code))
        print("    %-7s -> %s %s" % (n, code, str(r.get("message"))[:60]))
    return out


def read_version():
    vals = {}
    for line in VERSION_FILE.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            vals[k.strip()] = v.strip()
    return vals


def version_code(vals):
    d = vals.get("lastdate", "")
    seq = vals.get("seq", "0")
    return int(d) * 100 + int(seq) if d else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=None, help="指定 APK 路径；默认取 version.properties 对应那份")
    ap.add_argument("--check", action="store_true", help="只检查网盘上的版本，不上传")
    ap.add_argument("--dir", default=REMOTE_DIR, help="远端目录（默认 %s）" % REMOTE_DIR)
    args = ap.parse_args()
    remote_dir = args.dir

    vals = read_version()
    ver = vals.get("versionName", "0.0.0")
    vcode = version_code(vals)
    apk = Path(args.apk) if args.apk else (APK_DIR / ("Hplay_v%s.apk" % ver))
    remote_apk = remote_dir + "/" + apk.name

    print("== 目标 ==")
    print("  端点 %s" % (LAN or (PUB + "（公网；未配置内网端点）")))
    print("  远端 %s" % remote_apk)
    print("  版本 v%s (versionCode %d)" % (ver, vcode))

    tk = login()
    perm = me(tk)
    print("  当前权限位 = %s（可写 %s / 只读 %s）" % (perm, RW, RO))

    if args.check:
        items, code = listing(tk, remote_dir)
        print("\n== 网盘现状（code=%s）==" % code)
        names = [n for n, _ in items]
        for n, s in items:
            mark = "  ★永久保留" if n in PINNED else ""
            print("    %-30s %s%s" % (n, s, mark))
        # 第 65 轮：**每次检查都复核永久保留清单**（缺一个就报警，不等人发现）
        missing = [p for p in PINNED if p not in names]
        if missing:
            print("\n  ★★ 警告：永久保留的文件不在网盘上：%s" % "、".join(missing))
            print("     请从 成品\\Hplay\\apk\\ 恢复，或按工程记录重建后重新上传。")
        else:
            print("\n  永久保留清单完整（%s）" % "、".join(PINNED))
        r = api("/api/fs/get", {"path": remote_dir + "/" + MANIFEST}, tk)
        if r.get("code") == 200:
            print("\n== 网盘上的清单 ==")
            try:
                ru = (r.get("data") or {}).get("raw_url") or ""
                with urllib.request.urlopen(urllib.request.Request(ru, headers={"User-Agent": UA}), timeout=60) as rr:
                    print("    " + rr.read().decode("utf-8", "replace").replace("\n", "\n    "))
            except Exception as e:
                print("    取 raw_url 失败：%s" % repr(e)[:150])
        return 0

    if not apk.is_file():
        raise SystemExit("APK 不存在：%s（先跑 build.py --release）" % apk)

    size = apk.stat().st_size
    md5 = hashlib.md5(apk.read_bytes()).hexdigest()
    print("  本地 %s  %d B  md5=%s" % (apk.name, size, md5))

    # ---- 权限：只在需要时借；**归还一定回到"我看到的那个值"** ----
    #
    # 这里有个本轮踩到的真缺陷：第一版把"归还"写成硬编码的 RO(28935)，
    # 而现场实际是 65535 —— 那样一来脚本不仅借还，还会**擅自改变用户的权限配置**
    # （从 65535 落到 28935，WebDAV 之类依赖写位的功能会跟着不可用）。
    # 正确语义是：记住开工时看到的原值，收尾原样还回去；只有"我改过"才需要还。
    original_perm = perm
    borrowed = False
    if perm != RW:
        print("\n== 借写权限 %s ==" % RW)
        tk = borrow(RW)
        borrowed = True
        print("  借到，权限位 = %s（原始值 %s，收尾会还回它）" % (me(tk), original_perm))
    else:
        print("\n== 当前已可写（%s），不借权限，收尾也不动它 ==" % perm)

    try:
        print("\n== 建目录（幂等）==")
        print("  mkdir ->", api("/api/fs/mkdir", {"path": remote_dir}, tk).get("code"))

        print("\n== 上传 APK ==")
        with apk.open("rb") as f:
            r = put_file(tk, remote_apk, f)
        print("  -> code=%s %s" % (r.get("code"), str(r.get("message"))[:80]))
        if r.get("code") != 200:
            raise SystemExit("上传失败")

        print("\n== 写版本清单 %s ==" % MANIFEST)
        manifest = {
            "versionName": ver,
            "versionCode": vcode,
            "size": size,
            "md5": md5,
            "apk": apk.name,
            "minSdk": 24,
            "publishedAt": vals.get("lastdate", ""),
            "channel": "mea-netdisk",
            # 第 226 轮：没传 HPLAY_RELEASE_NOTES 时自动生成默认简介（版本 + 当日要点占位），
        # 不再让「检查更新」页出现空 notes。
        "notes": os.environ.get("HPLAY_RELEASE_NOTES") or (
            "v" + ver + " 更新：应用内功能修复与优化；详情见工程记录。"),
        }
        r2 = put_file(tk, remote_dir + "/" + MANIFEST,
                      json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8"))
        print("  -> code=%s %s" % (r2.get("code"), str(r2.get("message"))[:80]))

        # ---- 复验：远端大小 + raw_url 真取得到 ----
        print("\n== 复验 ==")
        items, code = listing(tk, remote_dir)
        got = dict(items)
        print("  目录（code=%s）: %s" % (code, got))
        if got.get(apk.name) != size:
            raise SystemExit("远端大小不符：远端 %s / 本地 %d" % (got.get(apk.name), size))
        print("  远端大小一致 OK")

        g = (api("/api/fs/get", {"path": remote_apk}, tk).get("data") or {})
        ru = g.get("raw_url") or ""
        if not ru:
            raise SystemExit("fs/get 没给出 raw_url（App 的检查更新就取不到直链）")
        req = urllib.request.Request(ru, method="GET", headers={"User-Agent": UA, "Range": "bytes=0-63"})
        with urllib.request.urlopen(req, timeout=90) as rr:
            head = rr.read(64)
        ok_zip = head[:4] == b"PK\x03\x04"
        print("  raw_url 可取：HTTP %s  %d B  首字节=%r  是 zip=%s" % (rr.status, len(head), head[:4], ok_zip))
        if not ok_zip:
            raise SystemExit("raw_url 取回来的不是 zip（APK 损坏？）")
    finally:
        if borrowed:
            print("\n== 归还权限到原值 %s ==" % original_perm)
            tk = borrow(original_perm)
            print("  归还后权限位 = %s" % me(tk))
            if original_perm == RO:
                # 原值就是只读：把"六种写操作确实被拒"逐项实测一遍（只认 403）。
                print("  六种写操作逐项实测：")
                res = six_write_attempts(tk)
                bad = [n for n, c in res if c != 403]
                print("  被正确拒绝 %d/6%s" % (6 - len(bad), "" if not bad else "，异常项：%s" % bad))
            else:
                # 原值本身可写：不去测"应该被拒"，那会误报；只如实记下当前仍可写。
                print("  原始权限位（%s）本身可写，不做「应被拒」断言（避免把配置事实当成失败）" % original_perm)

    print("\n== 完成 ==")
    print("  APK    %s" % (PUB + "/d" + enc(remote_apk)))
    print("  清单   %s" % (PUB + "/d" + enc(remote_dir + "/" + MANIFEST)))
    print("  App 端取法：匿名 POST /api/fs/get {path: %s} → data.raw_url" % (remote_dir + "/" + MANIFEST))
    print("  RESULT: PUSHED v%s (%d B, md5 %s)" % (ver, size, md5))
    return 0


if __name__ == "__main__":
    sys.exit(main())