"""探测 MissAV 各镜像线路视频页的「点击播放→弹广告」实现形态（只读，不改任何工程文件）。"""
import io, json, os, re, subprocess, sys

UA = ("Mozilla/5.0 (Linux; Android 13; SM-A536B Build/TP1A.220624.014) "
      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
def _catalog_path():
    """目录缓存位置：命令行参数 > 桌面 App 数据目录（只读引用，不改）。"""
    if len(sys.argv) > 1:
        return sys.argv[1]
    env = os.environ.get("LOCALAPPDATA") or (os.path.expanduser("~") + chr(92) + "AppData" + chr(92) + "Local")
    return env + chr(92) + "AvPlay" + chr(92) + "x99_catalog.json"

CAT = _catalog_path()


def get(url, ref=""):
    cmd = ["curl", "-sL", "--max-time", "25", "-A", UA, "-w", "\n@@FINAL:%{url_effective}"]
    if ref:
        cmd += ["-e", ref]
    cmd += [url]
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.stdout or ""


def first_card(html, base):
    mk = 'class="thumbnail group"'
    idxs = [m.start() for m in re.finditer(re.escape(mk), html)]
    for i, s in enumerate(idxs):
        e = idxs[i + 1] if i + 1 < len(idxs) else len(html)
        m = re.search(r'<a href="(https?://[^"]+)"', html[s:e])
        if m:
            return m.group(1)
    return None


def probe(line):
    origin = re.match(r"(https?://[^/]+)", line).group(1)
    listing = get(origin + "/cn/new")
    url = first_card(listing, origin)
    if not url:
        return origin, "无卡片", ""
    page = get(url, origin + "/cn/new")
    opens = re.findall(r"(//\s*)?window\.open\(", page)
    live = sum(1 for o in opens if not o)
    dead = len(opens) - live
    poproute = "/pop?url=" in page
    adscripts = sorted(set(re.findall(r'src="(?:https?:)?//([^/"]+)', page)) - {""})
    extern = [h for h in adscripts if origin.replace("www.", "") not in h]
    return origin, f"卡片={url.rsplit('/',1)[-1]} window.open 活跃={live} 注释={dead} /pop路由={poproute}", ",".join(extern)


def main():
    cat = json.load(io.open(CAT, encoding="utf-8"))
    site = [s for s in cat if s["name"] == "MissAV"][0]
    lines = [l[0] for l in site["lines"]][:12]
    for ln in lines:
        try:
            origin, verdict, ext = probe(ln)
            print(f"{origin:28} {verdict}")
            if ext:
                print(f"{'':28}   外部脚本域: {ext}")
        except Exception as e:
            print(f"{ln:28} 异常 {e}")


main()
