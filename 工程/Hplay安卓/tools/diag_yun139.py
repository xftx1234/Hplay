# -*- coding: utf-8 -*-
"""第 226 轮：139 直连清单诊断（PC 端复刻 Yun139.fetchManifest 的完整链路）。

用途：App「检查更新」显示异常时，先跑本脚本确认 139 侧的真实数据。
  python tools/diag_yun139.py     # 打印 139 云盘上 version.json 的实际内容与关键判断
"""
import base64, hashlib, json, random, string, time, urllib.parse, urllib.request

CREDS = [
    "cGM6MTM0NjU5NTkzMDg6NW9xWlRlcHJ8MXxSQ1N8MTc5NDM1MjkwNzcwM3xXUHJRdWVWLlpfdTBfS3BaS0pVT1BhUDZOVFlMMzhfTGs1RnJ1eDBxcnhyekpDUjA3bVo2YVZaNHV0ekZ4QzNUMFlOSXNsazNvNHJQbTdWTW5rTUkxb2tHakx3YWhnN0FZYWZkS2NqSUp4MjFVWTE5ejhzZUQ3Z3FwZW9zQmNuR29EMFVacFJ3anJwTkh5ZG1UNk9TbmNSVjdDVFBVcXVycnNQODhJSzhiZkEt",
]
ROUTE_URL = "https://user-njs.yun.139.com/user/route/qryRoutePolicy"
FALLBACK = "https://personal-kd-njs.yun.139.com/hcy"

def md5h(b): return hashlib.md5(b).hexdigest()
def enc_uri(s):
    e = urllib.parse.quote(s, safe="")
    return e.replace("%21","!").replace("%27","'").replace("%28","(").replace("%29",")").replace("%2A","*")
def cal_sign(body, ts, rand):
    b64 = base64.b64encode("".join(sorted(enc_uri(body))).encode()).decode()
    a = md5h(b64.encode()); b = md5h(md5h((ts+":"+rand).encode()).encode())
    return md5h((a+b).encode()).upper()
def stamp():
    return time.strftime("%Y-%m-%d %H:%M:%S"), "".join(random.choice(string.ascii_lowercase+string.digits) for _ in range(16))

auth = CREDS[0]
acct = base64.b64decode(auth + "="*(-len(auth)%4)).decode().split(":")[1]
def base_h(ts, rand, sign):
    return {"Accept":"application/json, text/plain, */*","CMS-DEVICE":"default",
        "Authorization":"Basic "+auth,"mcloud-channel":"1000101","mcloud-client":"10701",
        "mcloud-sign":ts+","+rand+","+sign,"mcloud-version":"7.14.0","Origin":"https://yun.139.com",
        "Referer":"https://yun.139.com/w/","x-DeviceInfo":"||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||",
        "x-huawei-channelSrc":"10000034","x-inner-ntwk":"2","x-m4c-caller":"PC","x-m4c-src":"10002",
        "x-SvcType":"1","Inner-Hcy-Router-Https":"1","Content-Type":"application/json;charset=UTF-8"}
def pers_h(ts, rand, sign):
    return {"Accept":"application/json, text/plain, */*","Authorization":"Basic "+auth,"Caller":"web",
        "Cms-Device":"default","Mcloud-Channel":"1000101","Mcloud-Client":"10701","Mcloud-Route":"001",
        "Mcloud-Sign":ts+","+rand+","+sign,"Mcloud-Version":"7.14.0","Origin":"https://yun.139.com",
        "Referer":"https://yun.139.com/w/","x-DeviceInfo":"||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||",
        "x-huawei-channelSrc":"10000034","x-inner-ntwk":"2","x-m4c-caller":"PC","x-m4c-src":"10002",
        "x-SvcType":"1","X-Yun-Api-Version":"v1","X-Yun-App-Channel":"10000034",
        "X-Yun-Channel-Source":"10000034",
        "X-Yun-Client-Info":"||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||dW5kZWZpbmVk||",
        "X-Yun-Module-Type":"100","X-Yun-Svc-Type":"1","Content-Type":"application/json;charset=UTF-8"}
def post(url, body, hdrs):
    req = urllib.request.Request(url, data=body.encode(), headers=hdrs)
    return json.loads(urllib.request.urlopen(req, timeout=25).read().decode("utf-8","replace"))

body = json.dumps({"userInfo":{"userType":1,"accountType":1,"accountName":acct},"modAddrType":1})
ts, rand = stamp()
j = post(ROUTE_URL, body, base_h(ts, rand, cal_sign(body, ts, rand)))
lst = (j.get("data") or {}).get("routePolicyList") or []
host = next((it.get("httpsUrl") for it in lst if it.get("modName")=="personal"), FALLBACK) or FALLBACK
if not host.endswith("/hcy"): host = host.rstrip("/") + "/hcy"
print("host:", host)
body = json.dumps({"imageThumbnailStyleList":["Small","Large"],"orderBy":"updated_at","orderDirection":"DESC","pageInfo":{"pageCursor":"","pageSize":200},"parentFileId":"root"})
ts, rand = stamp()
items = (post(host+"/file/list", body, pers_h(ts, rand, cal_sign(body, ts, rand))).get("data") or {}).get("items") or []
dirid = next((it["fileId"] for it in items if it.get("name")=="hplay"), None)
print("hplay dir:", dirid)
body = json.dumps({"imageThumbnailStyleList":["Small","Large"],"orderBy":"updated_at","orderDirection":"DESC","pageInfo":{"pageCursor":"","pageSize":200},"parentFileId":dirid})
ts, rand = stamp()
files = (post(host+"/file/list", body, pers_h(ts, rand, cal_sign(body, ts, rand))).get("data") or {}).get("items") or []
mid = next((f["fileId"] for f in files if f.get("name")=="version.json"), None)
apks = [f["name"] for f in files if f.get("name","").endswith(".apk")]
print("manifest:", mid, "apks:", apks[:3])
body = json.dumps({"fileId": mid})
ts, rand = stamp()
dd = (post(host+"/file/getDownloadUrl", body, pers_h(ts, rand, cal_sign(body, ts, rand))).get("data") or {})
url = dd.get("cdnUrl") or dd.get("url")
txt = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent":"Mozilla/5.0"}), timeout=25).read().decode("utf-8","replace")
j = json.loads(txt)
print("=== 139 实际清单 ===")
print("versionName:", j.get("versionName"), " versionCode:", j.get("versionCode"), " notes:", (j.get("notes") or "")[:60], "...")
print("判断：若 versionCode 与本机一致或更低 →「已是最新」是正确行为；若更高 → App 侧 hasUpdate 判断有问题（把本结果发我）")
