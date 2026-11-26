# -*- coding: utf-8 -*-
"""Hplay 安卓版依赖下载器 v2：阿里云 Maven 镜像（google + central）BFS 拉 POM 传递依赖。

v2 修复（v1 实测暴露）：
  * 先取 POM 拿 packaging 再下载（jar/aar 都试）
  * KMP 变体去重：同 (group, 去后缀 base) 只留一个 —— 版本最高者赢；
    同版本时 androidx 偏 "-android" 真身（root 是桩）、io.coil-kt 偏无后缀（android 变体）
产物：工具\\android-deps\\<group>_<artifact>_<ver>\\{classes.jar, res\\, libs\\, meta.txt}
"""
import os
import re
import sys
import zipfile
import io
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

OUT = r"D:\工作区\工具\android-deps"
REPOS = [
    "https://maven.aliyun.com/repository/google",
    "https://maven.aliyun.com/repository/central",
]

ROOTS = [
    "androidx.compose.runtime:runtime-android:1.7.3",
    "androidx.compose.ui:ui-android:1.7.3",
    "androidx.compose.foundation:foundation-android:1.7.3",
    "androidx.compose.animation:animation-android:1.7.3",
    "androidx.compose.material3:material3-android:1.3.0",
    "androidx.activity:activity-compose:1.9.2",
    # ComposeView 首帧必需（PoolingContainer.<clinit> 触发 R$id）；POM 不声明、只在 .module 元数据里，
    # 纯 POM 走查必漏——2026-09-29 真机 NoClassDefFoundError 实测补的根
    "androidx.customview:customview-poolingcontainer:1.0.0",
    "androidx.lifecycle:lifecycle-runtime-compose-android:2.8.6",
    # lifecycle-common 2.8.x 为 KMP：root 是骨架；无 -android 变体，真身 = -jvm
    "androidx.lifecycle:lifecycle-common-jvm:2.8.7",
    "androidx.core:core-ktx:1.13.1",
    "androidx.media3:media3-exoplayer:1.4.1",
    "androidx.media3:media3-ui:1.4.1",
    "androidx.media3:media3-datasource-okhttp:1.4.1",
    # HLS 模块：站点合集（xchina / MissAV）的播址是 .m3u8。缺它时 DefaultMediaSourceFactory 反射
    # 找不到 HlsMediaSource（dex 里只剩那个类名字符串），m3u8 被当普通文件解 ⇒ 点播放没反应、进度条时长 0。
    # 2026-10-01 真机复现 + dex 取证（HlsMediaPeriod / HlsManifest / HlsChunkSource 全部缺席）后补。
    "androidx.media3:media3-exoplayer-hls:1.4.1",
    "io.coil-kt.coil3:coil-compose-android:3.0.4",
    "io.coil-kt.coil3:coil-core-android:3.0.4",
    # coil-network-okhttp 无 -android 变体（3.0.4 实测 404）；引擎为纯 JVM 代码，-jvm 真身 Android 可用
    "io.coil-kt.coil3:coil-network-okhttp-jvm:3.0.4",
    "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0",
    "com.squareup.okhttp3:okhttp-jvm:5.5.0",
]

SKIP_ARTIFACT = [
    r"^kotlin-stdlib",
    r"^annotations$",
    r"^kotlin-dom-api",
    r"^listenablefuture$",
    # appcompat 尾巴（emoji2 的 runtime 连带）：
    # 全依赖闭包中只有 appcompat-resources 自己引用 androidx.appcompat，本 app 无任何调用；
    # 且老版 d8 解析其类文件直接 NPE——排除（2026-09-29 实测）。
    r"^appcompat",
    # collection-ktx 1.2.0（老 lifecycle 连带）：collection 1.4.0 起 ktx 内容已并入本体，
    # 两者同 FQCN 重复定义（2026-09-29 实测）——保留本体、排除 ktx。
    r"^collection-ktx$",
]
# 平台/KMP 变体后缀（去重时剥掉）
SUFFIX = re.compile(
    r"-(android|jvm|android-native|macosx64|macosarm64|iosarm64|iosx64|iossimulatorarm64|"
    r"linuxx64|linuxarm64|linuxarm32hfp|mingwx64|mingwx86|watchosarm32|watchosarm64|"
    r"watchosx64|watchossimulatorarm64|tvosarm64|tvosx64|tvossimulatorarm64|wasm-js|"
    r"wasm-wasi|js|metadata)$"
)

_cache = {}
_poms = {}


def http_get(url):
    if url in _cache:
        return _cache[url]
    req = urllib.request.Request(url, headers={"User-Agent": "hplay-deps/2.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            data = r.read()
        _cache[url] = data
        return data
    except urllib.error.HTTPError:
        _cache[url] = None
        return None
    except Exception as e:
        print("  ! 网络:", url, e)
        _cache[url] = None
        return None


def try_repos(path):
    for repo in REPOS:
        data = http_get(repo + path)
        if data is not None:
            return repo, data
    return None, None


def vtuple(v):
    def seg(s):
        m = re.match(r"^(\d+)", s)
        return (int(m.group(1)) if m else 0, s)
    parts = re.split(r"[.\-_]", v)
    return tuple(seg(p) for p in parts)


class Art:
    __slots__ = ("group", "artifact", "version", "packaging")

    def __init__(self, g, a, v, p="aar"):
        self.group, self.artifact, self.version, self.packaging = g, a, v, p

    @property
    def base(self):
        return (self.group, SUFFIX.sub("", self.artifact))

    @property
    def coord(self):
        return f"{self.group}:{self.artifact}:{self.version}"

    @property
    def dir(self):
        return f"{self.group}_{self.artifact}_{self.version}".replace(".", "_")

    def rank(self):
        """排序键：版本高者优先；androidx 偏 -android，coil 偏无后缀，其余偏 -jvm。"""
        has_android = self.artifact.endswith("-android")
        unsuffixed = not SUFFIX.search(self.artifact)
        has_jvm = self.artifact.endswith("-jvm")
        if self.group.startswith("androidx"):
            # 2024+ 的 androidx KMP root 多为骨架：-android > -jvm > root
            suf = 3 if has_android else (2 if has_jvm else (1 if unsuffixed else 0))
        elif self.group.startswith("io.coil-kt"):
            # coil3 的 android 变体带 -android 后缀（root 是 KMP 骨架 jar）
            suf = 2 if has_android else (1 if unsuffixed else 0)
        elif self.group.startswith("com.squareup.okhttp3"):
            # okhttp 5.x：root 是骨架，真身 = -jvm
            suf = 2 if has_jvm else (1 if unsuffixed else 0)
        else:
            suf = 2 if (unsuffixed or has_jvm) else (1 if has_android else 0)
        return (vtuple(self.version), suf)


NS = "{http://maven.apache.org/POM/4.0.0}"


def parse_pom(data):
    root = ET.fromstring(data)
    props = {}
    pe = root.find(NS + "properties")
    if pe is not None:
        for el in pe:
            props[el.tag.replace(NS, "")] = (el.text or "").strip()
    parent = root.find(NS + "parent")
    pg = pa = pv = None
    if parent is not None:
        pg = (parent.findtext(NS + "groupId") or "").strip() or None
        pa = (parent.findtext(NS + "artifactId") or "").strip() or None
        pv = (parent.findtext(NS + "version") or "").strip() or None
    packaging = (root.findtext(NS + "packaging") or "jar").strip()
    deps = []
    dr = root.find(NS + "dependencies")
    if dr is not None:
        for d in dr.findall(NS + "dependency"):
            def t(tag):
                return (d.findtext(NS + tag) or "").strip()
            deps.append({
                "g": t("groupId"), "a": t("artifactId"), "v": t("version"),
                "scope": t("scope") or "compile", "opt": t("optional").lower() == "true",
                "type": t("type") or "jar",
            })
    # dependencyManagement：为无版本的依赖提供版本（guava 等用）
    dm = {}
    dmr = root.find(NS + "dependencyManagement")
    if dmr is not None:
        ddeps = dmr.find(NS + "dependencies")
        if ddeps is not None:
            for d in ddeps.findall(NS + "dependency"):
                g = (d.findtext(NS + "groupId") or "").strip()
                a = (d.findtext(NS + "artifactId") or "").strip()
                v = (d.findtext(NS + "version") or "").strip()
                if g and a and v:
                    dm[(g, a)] = v
    return props, (pg, pa, pv), packaging, deps, dm


def get_pom(g, a, v):
    key = (g, a, v)
    if key in _poms:
        return _poms[key]
    gp = g.replace(".", "/")
    repo, data = try_repos(f"/{gp}/{a}/{v}/{a}-{v}.pom")
    if data is None:
        _poms[key] = None
        return None
    props, parent, packaging, deps, dm = parse_pom(data)
    if parent and all(parent):
        pp = get_pom(*parent)
        if pp is not None:
            merged = dict(pp[0])
            merged.update(props)
            props = merged
            merged_dm = dict(pp[3])
            merged_dm.update(dm)
            dm = merged_dm
    result = (props, packaging, deps, dm)
    _poms[key] = result
    return result


def resolve_version(v, props):
    """剥 Gradle 元数据记法（[1.7.3] / (,1.0] 等）+ 解 ${属性}。"""
    if v is None:
        return None
    v = v.strip()
    if v.startswith("[") or v.startswith("("):
        m = re.match(r"^[\[\(]([\d.]+(?:-[A-Za-z0-9]+)*)[\]\)]", v)
        if m:
            v = m.group(1)
        elif re.match(r"^[\[\(],[^\]]+\]$", v):
            return None
    out = re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), "\x00"), v)
    if "\x00" in out or "${" in out:
        return None
    return out


def download(art: Art):
    gp = art.group.replace(".", "/")
    # packaging 仅作优先级提示；aar/jar 都要试（POM 声明与实际不符的镜像时有发生）
    first = art.packaging if art.packaging in ("aar", "jar") else "aar"
    exts = [first] + [e for e in ("aar", "jar") if e != first]
    for ext in exts:
        repo, data = try_repos(f"/{gp}/{art.artifact}/{art.version}/{art.artifact}-{art.version}.{ext}")
        if data is None:
            continue
        d = os.path.join(OUT, art.dir)
        os.makedirs(d, exist_ok=True)
        if ext == "aar":
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                names = z.namelist()
                if "classes.jar" in names:
                    with z.open("classes.jar") as f, open(os.path.join(d, "classes.jar"), "wb") as o:
                        o.write(f.read())
                resn = [n for n in names if n.startswith("res/")]
                if resn:
                    z.extractall(d, members=resn)
                libn = [n for n in names if n.startswith("libs/") and n.endswith(".jar")]
                if libn:
                    z.extractall(d, members=libn)
        else:
            with open(os.path.join(d, "classes.jar"), "wb") as o:
                o.write(data)
        with open(os.path.join(d, "meta.txt"), "w", encoding="utf-8") as f:
            f.write(f"{art.coord} [{repo}]\n")
        return True
    return False


def main():
    os.makedirs(OUT, exist_ok=True)
    queue = [Art(*r.split(":")) for r in ROOTS]
    chosen = {}          # base -> Art
    skipped = []
    errors = []
    warnings = []
    done_pom = set()
    while queue:
        art = queue.pop(0)
        if any(re.search(p, art.artifact) for p in SKIP_ARTIFACT):
            skipped.append(art.coord + " (skip-pattern)")
            continue
        # 版本解析：空版本（dependencyManagement 都没给出的注解类依赖）→ 警告跳过；非数字 → 硬错
        if not art.version:
            warnings.append("空版本已跳过: " + art.coord)
            continue
        if not re.match(r"^\d", art.version or ""):
            errors.append("版本未解析: " + art.coord)
            continue
        old = chosen.get(art.base)
        if old is not None:
            if art.rank() <= old.rank():
                skipped.append(f"{art.coord} (≤ {old.coord})")
                continue
            skipped.append(f"{old.coord} ← 被 {art.coord} 取代")
        chosen[art.base] = art
        print(f"[pick] {art.coord}")
        marker = os.path.join(OUT, art.dir, "classes.jar")
        if not os.path.exists(marker):
            if not download(art):
                # 下载失败必须释放占位，让低优先级变体有机会顶上
                chosen.pop(art.base, None)
                errors.append("下载失败: " + art.coord)
                continue
        else:
            print("  (已存在，跳过下载)")
        ck = (art.group, art.artifact, art.version)
        if ck in done_pom:
            continue
        done_pom.add(ck)
        pom = get_pom(art.group, art.artifact, art.version)
        if pom is None:
            print("  ! POM 缺失:", art.coord)
            continue
        props, packaging, deps, dm = pom
        for d in deps:
            # scope：compile + runtime 都要（v1.0.2 教训：lifecycle 的 core-runtime 是 runtime 依赖，
            # 漏收 = ArchTaskExecutor 缺类、Activity 构造即崩）
            if d["scope"] not in ("compile", "", "runtime") or d["opt"] or d["type"] not in ("jar", "aar"):
                continue
            v = resolve_version(d["v"], props)
            if v is None and (d["g"], d["a"]) in dm:
                v = resolve_version(dm[(d["g"], d["a"])], props)
            if v is None:
                # 注解类/工具类依赖解析不出不致命（jsr305 / checker-qual 等），降级为警告
                warnings.append(f"{art.coord} -> {d['g']}:{d['a']}:{d['v'] or '(空)'}")
                continue
            queue.append(Art(d["g"], d["a"], v))

    print("\n==== 保留 %d 个构件 ====" % len(chosen))
    for art in sorted(chosen.values(), key=lambda a: a.coord):
        print("  " + art.coord)
    if skipped:
        print("跳过 %d 项（首 30）：" % len(skipped))
        for s in skipped[:30]:
            print("  " + s)
    # 最终裁决清单：build.py 只信这份（磁盘上可能有历史迭代留下的多版本目录）
    man = os.path.join(OUT, "manifest.txt")
    with open(man, "w", encoding="utf-8") as f:
        f.write("\n".join(a.dir for a in sorted(chosen.values(), key=lambda x: x.coord)) + "\n")
    print("清单已写入:", man)
    if warnings:
        print("警告（未解析版本、已跳过）%d 条（首 20）：" % len(warnings))
        for w in warnings[:20]:
            print("  " + w)
    if errors:
        print("\n!! 错误：")
        for e in errors:
            print("  " + e)
        raise SystemExit(1)
    print("\nOK")


if __name__ == "__main__":
    main()
