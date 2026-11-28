#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 355 轮规则：CF cookie 按域 + javchu 媒体主机并行探测。

接在 round352 之后、logwire 之前。每条规则幂等（先判 new 是否已在）。
只改 build/gen/*.kt（生成产物，不手改）。

  1. patch_jar_domain  OkHttp CookieJar 按 url.host 取 cookie、并按该 host 构造 domain
  2. patch_probe       媒体预检：并发探测同路径的备选 t 主机，判真是 #EXTM3U
  3. patch_call        详情页用探测结果（可用地址）而不是原始地址
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GEN = ROOT / "build" / "gen"
NL = chr(10)


def _once(text, old, new, tag):
    n = text.count(old)
    if n != 1:
        raise SystemExit("[第355轮规则失配] %s：命中 %d 次（应为 1）：%r" % (tag, n, old[:120]))
    return text.replace(old, new, 1)


# ---------------------------------------------------------------- 1. CookieJar 按域

JAR_OLD = NL.join([
    "        override fun loadForRequest(url: HttpUrl): List<Cookie> {",
    "            loadCookies()",
    "            val baseCookies = synchronized(cookieMap) {",
    "                cookieMap.filterKeys { !isInternalCookieKey(it) }.map { (k, v) ->",
    "                    Cookie.Builder().name(k).value(v).domain(HOST).path(\"/\").build()",
    "                }",
    "            }",
    "            // \u2605 \u7b2c 322 \u8f6e\uff1a\u628a clearance / session \u4e5f\u5e76\u8fdb\u6765\uff08\u552f\u4e00\u4e0d\u88ab\u8986\u76d6\u7684\u4f4d\u7f6e\uff09",
    "            val extra = com.han1me.lab.CfCookieBridge.parsed()",
    "            if (extra.isEmpty()) return baseCookies",
    "            val have = baseCookies.map { it.name }.toHashSet()",
    "            val more = extra.filter { have.add(it.first) }.map { (k, v) ->",
    "                Cookie.Builder().name(k).value(v).domain(HOST).path(\"/\").build()",
    "            }",
    "            return baseCookies + more",
    "        }",
])

JAR_NEW = NL.join([
    "        override fun loadForRequest(url: HttpUrl): List<Cookie> {",
    "            loadCookies()",
    "            // \u2605\u2605\u2605 \u7b2c 355 \u8f6e\uff08\u7528\u6237\u62a5\u300c\u5173\u6ce8\u968f\u5185\u7f6e\u8282\u70b9\u7f51\u7edc\u53d8\u5316\u800c\u65e0\u6cd5\u5173\u6ce8\u300d\uff09\uff1a**\u6309\u57df\u53d6 cookie\u3001\u6309\u57df\u6784\u9020 Cookie**\u3002",
    "            //",
    "            // \u65e7\u5199\u6cd5\u4e24\u4e2a\u786c\u4f24\uff1a",
    "            //   \u00b7 parsed() \u4e0d\u63a5 url \u2014\u2014 \u5b83\u6c38\u8fdc\u8fd4\u56de hanime1.me \u90a3\u4e00\u4e32\uff1b",
    "            //   \u00b7 domain(HOST) \u786c\u7f16\u7801\u6210 hanime1.me \u2014\u2014 OkHttp \u4f1a\u6309\u57df\u8fc7\u6ee4\uff0c",
    "            //     \u4e8e\u662f\u53d1\u7ed9 javchu.com \u7684\u8bf7\u6c42**\u4e00\u4e2a cookie \u90fd\u5e26\u4e0d\u4e0a**\u3002",
    "            //",
    "            // \u73b0\u5728\u4e0e Viewer \u7684 HCookieJar.loadForRequest \u5b8c\u5168\u4e00\u81f4\uff1a\u6309\u8bf7\u6c42 host \u53d6\uff0c",
    "            // \u5e76\u7528\u8be5 host \u6784\u9020 Cookie.domain\u3002\u5b83\u4e5f\u662f\u552f\u4e00\u4e0d\u4f1a\u88ab BridgeInterceptor \u8986\u76d6\u7684\u4f4d\u7f6e\u3002",
    "            val reqHost = url.host.lowercase()",
    "            val isOfficialReq = reqHost == HOST || reqHost.endsWith(\".\" + HOST)",
    "            val baseCookies = if (!isOfficialReq) emptyList() else synchronized(cookieMap) {",
    "                cookieMap.filterKeys { !isInternalCookieKey(it) }.map { (k, v) ->",
    "                    Cookie.Builder().name(k).value(v).domain(HOST).path(\"/\").build()",
    "                }",
    "            }",
    "            // \u2605 \u7b2c 322 \u8f6e\uff1a\u628a clearance / session \u4e5f\u5e76\u8fdb\u6765\uff08\u552f\u4e00\u4e0d\u88ab\u8986\u76d6\u7684\u4f4d\u7f6e\uff09",
    "            val extra = com.han1me.lab.CfCookieBridge.parsed(url.toString())",
    "            if (extra.isEmpty()) return baseCookies",
    "            val have = baseCookies.map { it.name }.toHashSet()",
    "            val more = extra.filter { have.add(it.first) }.map { (k, v) ->",
    "                Cookie.Builder().name(k).value(v).domain(reqHost).path(\"/\").build()",
    "            }",
    "            return baseCookies + more",
    "        }",
])


def patch_jar_domain(t):
    if "\u7b2c 355 \u8f6e\uff08\u7528\u6237\u62a5\u300c\u5173\u6ce8\u968f\u5185\u7f6e\u8282\u70b9\u7f51\u7edc\u53d8\u5316\u800c\u65e0\u6cd5\u5173\u6ce8\u300d\uff09" in t:
        return t
    return _once(t, JAR_OLD, JAR_NEW, "CookieJar.loadForRequest")


# ---------------------------------------------------------------- 2. 媒体主机并行探测

PROBE_OLD = NL.join([
    "        val ref = runCatching {",
    "            java.net.URI(pageUrl).let { it.scheme + \"://\" + it.host + \"/\" }",
    "        }.getOrNull()?.takeIf { it.length > 8 } ?: (originOf(u) + \"/\")",
    "        val req = Request.Builder().url(u)",
    "            .header(\"User-Agent\", UA)",
    "            .header(\"Referer\", ref)",
    "            .header(\"Range\", \"bytes=0-0\")",
    "            .get().build()",
    "        shortTimeoutHttp(8_000L).newCall(req).execute().use { r ->",
    "            // 200/206 \u90fd\u7b97\u901a\uff1am3u8 \u6e05\u5355\u5bf9\u5c0f Range \u5e38\u56de 200\uff08\u6574\u4efd\u8fd4\u56de\uff09",
    "            r.isSuccessful || r.code == 206",
    "        }",
    "    }.getOrDefault(false)",
])

PROBE_NEW = NL.join([
    "        return@runCatching mediaProbeUrl(u, pageUrl, fallback = false) != null",
    "    }.getOrDefault(false)",
    "",
    "    /** \u5907\u9009 t \u4e3b\u673a\uff08\u540c\u4e00\u5957\u6cdb\u89e3\u6790\uff1a\u914d\u7f6e\u76f8\u540c\uff0c\u9010\u53f0\u8fb9\u7f18\u53ef\u7528\u6027\u4e0d\u540c\uff09\u3002 */",
    "    private val cdn2020Hosts = listOf(",
    "        \"t33\", \"t43\", \"t45\", \"t46\", \"t47\", \"t48\", \"t50\", \"t30\", \"t27\", \"t38\",",
    "    )",
    "",
    "    /**",
    "     * \u2605\u2605\u2605 \u7b2c 355 \u8f6e\uff08\u7528\u6237\u62a5\u300cjavchu \u539f\u751f\u94fe\u63a5\u53e3\u4e0d\u7a33\u5b9a\uff0c\u968f\u8282\u70b9\u53d8\u5316\u65f6\u6709\u65f6\u6ca1\u6709\u300d\uff09\uff1a",
    "     * **\u628a\u4e00\u6761\u5a92\u4f53\u5730\u5740\u89e3\u6790\u6210\u201c\u771f\u80fd\u7528\u201d\u7684\u5730\u5740**\uff08\u4e0d\u53ea\u662f\u5224\u6d3b\uff09\u3002",
    "     *",
    "     * \u4e0a\u4e00\u7248 srcOk \u53ea\u56de true/false\uff1a\u5373\u4f7f\u5b83\u53d1\u73b0 t33 \u6b7b\u4e86\u3001t45 \u6d3b\u7740\uff0c",
    "     * \u4ea4\u7ed9\u64ad\u653e\u5668/\u4e0b\u8f7d\u5668\u7684\u4ecd\u7136\u662f**\u6b7b\u7684 t33** \u2014\u2014 \u7528\u6237\u770b\u5230\u7684\u5c31\u662f\u201c\u65f6\u6709\u65f6\u6ca1\u6709\u201d\u3002",
    "     *",
    "     * \u5224\u771f\u53e3\u5f84\u4e0e\u4e0b\u8f7d\u5668 playlistLooksReal \u4e00\u81f4\uff1am3u8 \u5fc5\u987b\u542b `#EXTM3U`\uff1b",
    "     * \u534a\u622a\u7684\u201c206 + 1 \u5b57\u8282\u201d\u4e0d\u7b97\u6d3b\uff08\u771f\u673a\u5b9e\u6d4b\u5c31\u662f\u8fd9\u79cd\u56de\u5305\u628a\u6b7b\u94fe\u5f53\u6210\u6d3b\u94fe\uff09\u3002",
    "     *",
    "     * **\u5e76\u53d1\u63a2\u6d4b**\uff1a\u540c\u4e00\u4e2a index.m3u8 \u7684 10 \u4e2a\u5907\u9009\u4e3b\u673a\u540c\u65f6\u53d1\uff0c\u8c01\u5148\u56de\u771f\u6e05\u5355\u7528\u8c01\u3002",
    "     * \u4e32\u884c\u63a2\u6d4b\u6700\u574f\u8981 10\u00d75 \u79d2\uff0c\u800c\u8fd9\u5728\u8be6\u60c5\u9875\u7684\u5173\u952e\u8def\u5f84\u4e0a\uff08\u7528\u6237\u4f1a\u770b\u5230\u8f6c\u5708\uff09\u3002",
    "     *",
    "     * @param fallback \u5168\u90e8\u63a2\u6d4b\u5931\u8d25\u65f6\u662f\u5426\u539f\u6837\u4ea4\u51fa\u539f\u5730\u5740\uff08true = \u4e0d\u628a\u6309\u94ae\u5f04\u6ca1\uff09",
    "     * @return \u53ef\u7528\u5730\u5740\uff08\u53ef\u80fd\u662f\u6362\u4e86 t \u53f7\u7684\uff09",
    "     */",
    "    internal fun mediaProbeUrl(",
    "        u: String,",
    "        pageUrl: String = \"\",",
    "        fallback: Boolean = true,",
    "        deadlineAt: Long = 0L,",
    "    ): String? = runCatching {",
    "        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return@runCatching if (fallback) u else null",
    "        val host = uri.host?.lowercase().orEmpty()",
    "        if (host.isBlank()) return@runCatching if (fallback) u else null",
    "        val ref = runCatching {",
    "            java.net.URI(pageUrl).let { it.scheme + \"://\" + it.host + \"/\" }",
    "        }.getOrNull()?.takeIf { it.length > 8 } ?: (originOf(u) + \"/\")",
    "        val bare = uri.rawPath.orEmpty() +",
    "            (if (uri.rawQuery.isNullOrBlank()) \"\" else \"?\" + uri.rawQuery)",
    "        val scheme = uri.scheme ?: \"https\"",
    "        val isM3u8 = u.contains(\".m3u8\", true)",
    "        val hosts = ArrayList<String>()",
    "        hosts.add(host)",
    "        if (Regex(\"^(t\\\\d+)\\\\.cdn2020\\\\.com$\").matches(host)) {",
    "            for (n in cdn2020Hosts) {",
    "                val cand = n + \".cdn2020.com\"",
    "                if (cand != host) hosts.add(cand)",
    "            }",
    "        }",
    "        val budget = if (deadlineAt > 0L) deadlineAt - System.currentTimeMillis() else 5_000L",
    "        if (budget <= 0L) return@runCatching if (fallback) u else null",
    "        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)",
    "        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, hosts.size))",
    "        try {",
    "            for (h in hosts) {",
    "                val tryUrl = if (h == host) u else scheme + \"://\" + h + bare",
    "                runCatching {",
    "                    pool.submit {",
    "                        if (found.get() != null) return@submit",
    "                        val alive = runCatching {",
    "                            val req = Request.Builder().url(tryUrl)",
    "                                .header(\"User-Agent\", UA)",
    "                                .header(\"Referer\", ref)",
    "                                .apply { if (!isM3u8) header(\"Range\", \"bytes=0-1023\") }",
    "                                .get().build()",
    "                            shortTimeoutHttp(budget).newCall(req).execute().use { r ->",
    "                                if (!(r.isSuccessful || r.code == 206)) false",
    "                                else {",
    "                                    val body = runCatching { r.body?.string().orEmpty() }.getOrDefault(\"\")",
    "                                    if (isM3u8) body.contains(\"#EXTM3U\") else body.isNotEmpty()",
    "                                }",
    "                            }",
    "                        }.getOrDefault(false)",
    "                        if (alive) {",
    "                            if (found.compareAndSet(null, tryUrl) && h != host) {",
    "                                com.han1me.lab.LabLog.i(\"line\", \"\u5a92\u4f53\u4e3b\u673a\u56de\u9000\uff1a\" + host + \" -> \" + h)",
    "                            }",
    "                        }",
    "                    }",
    "                }",
    "            }",
    "            pool.shutdown()",
    "            pool.awaitTermination(budget + 1_500L, java.util.concurrent.TimeUnit.MILLISECONDS)",
    "        } catch (_: Throwable) {",
    "        } finally {",
    "            runCatching { pool.shutdownNow() }",
    "        }",
    "        val ok = found.get()",
    "        if (ok == null) {",
    "            com.han1me.lab.LabLog.i(\"line\", \"\u5a92\u4f53\u63a2\u6d4b\u5168\u5931\u8d25\uff1a\" + host)",
    "        }",
    "        ok ?: (if (fallback) u else null)",
    "    }.getOrNull()",
])


def patch_probe(t):
    if "\u5a92\u4f53\u63a2\u6d4b\u5168\u5931\u8d25" in t:
        return t
    return _once(t, PROBE_OLD, PROBE_NEW, "srcOk \u9884\u68c0")


# ---------------------------------------------------------------- 3. 调用点用解析结果

CALL_OLD = NL.join([
    "        val jsSources: List<Pair<Int, String>> =",
    "            jsRaw.filter { srcOk(it, v.url) }.map { 0 to it }",
])

CALL_NEW = NL.join([
    "        // \u2605 \u7b2c 355 \u8f6e\uff1a\u7528\u89e3\u6790\u540e\u7684\u5730\u5740\uff0c\u4e0d\u662f\u539f\u59cb\u5730\u5740\u3002",
    "        // \u4e00\u5171\u7ed9 7 \u79d2\u603b\u9884\u7b97\uff08\u5e76\u53d1\u63a2\u6d4b\uff0c\u8d85\u65f6\u5c31\u7528\u539f\u59cb\u5730\u5740\uff09\u3002",
    "        val probeDeadline = System.currentTimeMillis() + 7_000L",
    "        val jsSources: List<Pair<Int, String>> = jsRaw.mapNotNull { raw ->",
    "            mediaProbeUrl(raw, v.url, deadlineAt = probeDeadline)?.let { fixed -> 0 to fixed }",
    "        }",
])


def patch_call(t):
    if "\u7528\u89e3\u6790\u540e\u7684\u5730\u5740\uff0c\u4e0d\u662f\u539f\u59cb\u5730\u5740" in t:
        return t
    return _once(t, CALL_OLD, CALL_NEW, "jsSources \u8c03\u7528\u70b9")


RULES = [
    ("HanimeClient.kt", patch_jar_domain),
    ("X99.kt", patch_probe),
    ("X99.kt", patch_call),
]


def main():
    touched = []
    for name, fn in RULES:
        p = GEN / name
        if not p.exists():
            continue
        t = p.read_text(encoding="utf-8")
        nt = fn(t)
        if nt != t:
            p.write_text(nt, encoding="utf-8")
            touched.append(name)
    print(("[\u7b2c355\u8f6e] \u5df2\u5e94\u7528\uff1a" + "\u3001".join(touched)) if touched else "[\u7b2c355\u8f6e] \u65e0\u9700\u6539\u52a8\uff08\u5e42\u7b49\uff09")


if __name__ == "__main__":
    main()