# -*- coding: utf-8 -*-
"""生成后置规则：在 patches.py 重放**之后**对 build/gen/*.kt 做可校验的定点替换。

规矩：每条 (文件, old, new) 的 old 必须在重放结果里命中**恰好 1 次**，否则整体报错退出。
用法：python tools/postpatch.py            # 应用到 build/gen
"""
import io
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GEN = ROOT / "build" / "gen"

NL = chr(10)
K3 = chr(34) * 3   # Kotlin raw string 三双引号

# ---------------------------------------------------------------- 第 212 轮：麻豆 8xx6

MADAO_OLD = NL.join([
"    private fun madaoListing(base: String, page: Int): List<HanimeVideo> {",
"        val origin = originOf(base)",
"        val u = if (page <= 1) origin + \"/\" else origin + \"/page/\" + page + \"/\"",
"        val html = getHtml(u)",
"        val out = LinkedHashMap<String, HanimeVideo>()",
"        // 卡片结构：<a href=\"/video/197627201.html\"><img class=\"lozad w-full\" src=\"...\"></a>",
"        //           <div class=\"…truncate\"><a href=\"/video/197627201.html\">标题</a></div>",
"        for (m in Regex(" + K3 + "<a[^>]*href=[\"']([^\"']*/video/(\\d+)\\.html)[\"'][^>]*>([\\s\\S]{0,900}?)</a>" + K3 + ",",
"                RegexOption.IGNORE_CASE).findAll(html)) {",
"            val href = m.groupValues[1]",
"            val vid = m.groupValues[2]",
"            val abs = runCatching { URI(u).resolve(unescape(href)).toString() }.getOrNull() ?: continue",
"            if (out.containsKey(abs)) continue",
"            val inner = m.groupValues[3]",
"            val thumb = Regex(" + K3 + "<img[^>]*\\s(?:data-src|data-original|src)=[\"']([^\"']+)[\"']" + K3 + ")",
"                .find(inner)?.groupValues?.get(1)?.let { cleanThumb(it, u) }.orEmpty()",
"            // 标题一律用编号（原文被服务端随机化，无法还原）",
"            out[abs] = HanimeVideo(",
"                id = abs.takeLast(60), url = abs, title = \"麻豆传媒 · \" + vid,",
"                thumb = thumb, duration = \"\", likes = \"\", views = \"\", brand = \"\",",
"            )",
"        }",
"        return out.values.toList()",
"    }",
]) + NL

MADAO_NEW = NL.join([
"    /** 麻豆卡片：标题=document.write(d('base64'))，解码后剔掉 display:none 干扰 span；封面取 img.src。 */",
"    private fun madaoListing(base: String, page: Int): List<HanimeVideo> {",
"        // 第 213 轮：远端目录把「麻豆」线路写成 aplovesoon 镜像，站点表纠偏不保证传到调用点，",
"        // 所以这里**自己纠偏**：不是 8xx6 家族就一律走主域。",
"        val host = if (rootDomain(base).startsWith(\"8xx6\") || rootDomain(base).startsWith(\"madaoav\")) base else \"https://www.8xx6.lol\"",
"        val origin = originOf(host)",
"        val u = if (page <= 1) origin + \"/\" else origin + \"/?page=\" + page",
'        // 第 220 轮：8xx6 翻页实测 /page/N/ 404，/?page=N 才有效\n'
"        val html = runCatching { getHtml(u) }.getOrNull().orEmpty()",
"        com.han1me.lab.LabLog.i(\"x99\", \"madao base=\" + base.take(60) + \" html=\" + html.length)",
"        if (html.isBlank()) return emptyList()",
"        val out = LinkedHashMap<String, HanimeVideo>()",
"        val thumbs = HashMap<String, String>()",
"        for (t in Regex(" + K3 + "<a[^>]*href=\"([^\"]*/video/(\\d+)\\.html)\"[^>]*>\\s*<img[^>]*\\ssrc=\"([^\"]+)\"[^>]*>" + K3 + ",",
"                RegexOption.IGNORE_CASE).findAll(html)) {",
"            thumbs[t.groupValues[2]] = cleanThumb(t.groupValues[3], u)",
"        }",
"        for (t in Regex(" + K3 + "<a[^>]*href=\"([^\"]*/video/(\\d+)\\.html)\"[^>]*>\\s*<script[^>]*>\\s*document\\.write\\(d\\('([A-Za-z0-9+/=]+)'\\)\\)" + K3 + ",",
"                RegexOption.IGNORE_CASE).findAll(html)) {",
"            val vid = t.groupValues[2]",
"            val abs = runCatching { URI(u).resolve(unescape(t.groupValues[1])).toString() }.getOrNull() ?: continue",
"            if (out.containsKey(abs)) continue",
"            val title = madaoTitle(t.groupValues[3])",
"            out[abs] = HanimeVideo(",
"                id = abs.takeLast(60), url = abs, title = title.ifBlank { \"麻豆传媒 · \" + vid },",
"                thumb = thumbs[vid].orEmpty(), duration = \"\", likes = \"\", views = \"\", brand = \"\",",
"            )",
"        }",
"        com.han1me.lab.LabLog.i(\"x99\", \"madao 卡片=\" + out.size + \" 首=\" + out.values.firstOrNull()?.title)",
"        return out.values.toList()",
"    }",
"",
"    /** 麻豆标题解码：base64 -> 删 display:none 干扰 span -> 去标签 -> 压缩空白。 */",
"    private fun madaoTitle(b64: String): String {",
"        val h = runCatching {",
"            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)",
"        }.getOrNull().orEmpty()",
"        if (h.isBlank()) return \"\"",
"        var s = h.replace(Regex(" + K3 + "<span[^>]*display\\s*:\\s*none[^>]*>[\\s\\S]*?</span>" + K3 + ", RegexOption.IGNORE_CASE), \"\")",
"        s = s.replace(Regex(" + K3 + "<[^>]+" + K3 + "), \"\")",
"        return unescape(s).replace(Regex(" + K3 + "\\s+" + K3 + "), \" \").trim()",
"    }",
]) + NL

DISP_OLD = '            // 第 203 轮：麻豆列表用编号标题（服务端随机化，原文不可还原）' + NL + '            (rootDomain(base).startsWith("8xx6") || rootDomain(base).startsWith("madaoav")) -> madaoListing(base, page)'
DISP_NEW = '            // 第 212 轮：麻豆列表按**站名**分发（实际线路是镜像域名，只看域名会漏进通用解析）' + NL + '            siteName.contains("麻豆") || rootDomain(base).startsWith("8xx6") -> madaoListing(base, page)'

BOARD_OLD = "        val add = BOARD_LINE_FIX.entries.firstOrNull { e ->"
BOARD_NEW = NL.join([
'        // 第 212 轮：麻豆必须落在 8xx6 家族。远端目录 x99dh.cc 把它写成了 av.aplovesoon.com，',
'        // 而那是 AVPorn 的 Nuxt 站（没有 /video/<id>.html 卡片）→ 列表 0 卡、标题乱码、封面取到广告图。',
'        if (s.name.contains("麻豆") && !s.name.contains("madaoav")) {',
'            val fam = base.filter { ln ->',
'                val r = runCatching { rootDomain(ln.url) }.getOrDefault("")',
'                r.startsWith("8xx6") || r.startsWith("madaoav")',
'            }',
'            val pin = X99Line("https://www.8xx6.lol", "")',
'            val rest = fam.filterNot { it.url.trimEnd(\'/\').lowercase() == "https://www.8xx6.lol" }',
'            return s.copy(lines = listOf(pin) + rest)',
'        }',
'        val add = BOARD_LINE_FIX.entries.firstOrNull { e ->',
])

PUB_RE_OLD = "    private val pubAddrRe = Regex(" + K3 + 'href="?(https?://[a-z0-9.\\-]+\\.[a-z]{2,6}/?)"?[^>]*>' + K3 + ", RegexOption.IGNORE_CASE)"
PUB_RE_NEW = "    private val pubAddrRe = Regex(" + K3 + 'href="?(https?://[a-z0-9.\\-]+\\.[a-z]{2,6}/?)"?[^>]*>|<p>\\s*([a-z0-9.\\-]+\\.[a-z]{2,6})\\s*</p>' + K3 + ", RegexOption.IGNORE_CASE)"

FETCH_OLD = "        for (m in pubAddrRe.findAll(html)) {" + NL + "            val u = m.groupValues[1].trimEnd('/').lowercase()"
FETCH_NEW = "        for (m in pubAddrRe.findAll(html)) {" + NL + "            // 第 215 轮：qxx556 用 <p>qyulNN.Com</p> 列线路（裸域名无 href）；第二分支抓它并补 https://" + NL + "            val u = (m.groupValues[1].ifBlank { \"https://\" + m.groupValues[2] }).trimEnd('/').lowercase()"

PUBSRC_MADAO_OLD = '        "x99.pub.madao" to "https://aplovesoon.com/contact/?entry=latest",'
PUBSRC_MADAO_NEW = '        // 第 215 轮：aplovesoon.com 是独立板块（AVPorn 系），不是麻豆的发布页 —— 之前张冠李戴。' + NL + '        "x99.pub.madao" to "https://madaoavmodv029.xyz/m32c6",'

PUBSRC_QIPAO_OLD = '        "x99.pub.qipao" to "https://qpdizhi.pages.dev/",'
PUBSRC_QIPAO_NEW = '        // 第 215 轮：气泡有 4 个互为镜像的发布页，全登记（fetch 时逐个尝试，取到即用）' + NL + '        "x99.pub.qipao" to "https://qpdizhi.pages.dev/",' + NL + '        "x99.pub.qipao2" to "https://qpdizhi.netlify.app/",' + NL + '        "x99.pub.qipao3" to "https://qpdizhi.github.io/",' + NL + '        "x99.pub.qipao4" to "https://qpdizhi2.pages.dev/",'

MADAO_SPLIT_OLD = NL.join([
'        X99Site(',
'            name = "麻豆传媒",',
'            desc = "麻豆传媒 · 8xx6 主站（原生 HLS · 标题有服务端反爬混淆，显示编号）",',
'            url = "https://www.8xx6.lol/",',
'            lines = linesFor(',
'                "https://www.8xx6.lol", "x99.pub.madao",',
'                listOf("https://www.8xx6.lol", "https://madaoavmodv029.xyz"),',
'            ),',
'        ),',
])
MADAO_SPLIT_NEW = NL.join([
'        // 第 215 轮：拆成两个独立板块 —— 8xx6 与 madaoav 是两个不同的站，不是同站镜像。',
'        X99Site(',
'            name = "麻豆传媒",',
'            desc = "麻豆传媒 8xx6 · 国产传媒（标题 base64 反爬，自动解码）",',
'            url = "https://www.8xx6.lol/",',
'            lines = linesFor(',
'                "https://www.8xx6.lol", "x99.pub.madao",',
'                listOf("https://www.8xx6.lol"),',
'            ),',
'        ),',
'        X99Site(',
'            name = "madaoav",',
'            desc = "madaoav · 国产传媒（maccms 形态，player_aaaa 直出 m3u8）",',
'            url = "https://madaoavmodv029.xyz/m32c6/",',
'            lines = listOf(X99Line("https://madaoav.com/", "")),',
'        ),',
])

BO_OLD = '        isEmbedPlayerUrl(u) -> "embed"' + NL + '        board196Of(u) != null -> board196Of(u)!!'
BO_NEW = '        isEmbedPlayerUrl(u) -> "embed"' + NL + '        // 第 215 轮：madaoav（maccms）卡片 /m32c6/vodplay/vodN' + NL + '        isMdavUrl(u) -> "mdav"' + NL + '        board196Of(u) != null -> board196Of(u)!!'

MDAV_ANCHOR = "    /** 麻豆卡片：标题=document.write(d('base64'))"

MDAV_IMPL = NL.join([
'    /** madaoav（maccms）：卡片 /m32c6/vodplay/vodN；详情页 player_aaaa.url 直出 m3u8。 */',
'    fun isMdavUrl(u: String): Boolean =',
'        rootDomain(u).startsWith("madaoav") && Regex(' + K3 + '^(?:/m32c6)?/vodplay/[^/?#]+/?$' + K3 + ').matches(pathOnly(u))',
'',
'    private val mdavCardRe = Regex(' + K3 + '<article[\\s\\S]*?<a href="([^"]*(?:/m32c6)?/vodplay/[^"]+)"[^>]*title="([^"]*)"[^>]*>[\\s\\S]*?<img[^>]*src="([^"]+)"' + K3 + ',',
'        RegexOption.IGNORE_CASE)',
'',
'    private fun mdavListing(base: String, page: Int): List<HanimeVideo> {',
'        val origin = originOf(base)',
'        val u = if (page <= 1) origin + "/" else origin + "/index-" + page + ".html"',
'        val html0 = runCatching { getHtml(u) }.getOrNull().orEmpty()',
'        // 第 215 轮：站方对无 cookie 高频访问弹「赤壁赋」挑战页（实测 1177B），',
'        // 等 900ms 重试一次即可拿到真页（curl 每次都成功说明只是频控而非指纹封锁）。',
'        var html2 = html0',
'        if (html2.contains("赤壁赋")) {',
'            runCatching { Thread.sleep(900) }',
'            html2 = runCatching { getHtml("https://madaoav.com/") }.getOrNull().orEmpty().ifBlank { html2 }',
'            com.han1me.lab.LabLog.i("x99", "mdav 重试 html=" + html2.length)',
'        }',
'        val html = html2',
'        val out = LinkedHashMap<String, HanimeVideo>()',
'        for (t in mdavCardRe.findAll(html)) {',
'            val abs = runCatching { URI("https://madaoavmodv029.xyz/m32c6/vodplay/" + t.groupValues[1].substringAfterLast("/")).toString() }.getOrNull() ?: continue\n''            if (out.containsKey(abs)) continue\n''            out[abs] = HanimeVideo(',
'                id = abs.takeLast(60), url = abs, title = unescape(t.groupValues[2]).trim(),',
'                thumb = cleanThumb(t.groupValues[3], u), duration = "", likes = "", views = "", brand = "madaoav",',
'            )',
'        }',
'        return out.values.toList()',
'    }',
'',
'    private fun mdavDetail(v: HanimeVideo): HanimeDetail? {',
'        val html = runCatching { getHtml(v.url) }.getOrNull().orEmpty()',
'        val m = Regex(' + K3 + 'player_aaaa\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*</script>' + K3 + ').find(html) ?: return null',
'        val url = Regex(' + K3 + '"url"\\s*:\\s*"([^"]+)"' + K3 + ').find(m.groupValues[1])?.groupValues?.get(1) ?: return null',
'        val name = Regex(' + K3 + '"vod_name"\\s*:\\s*"([^"]*)"' + K3 + ').find(m.groupValues[1])?.groupValues?.get(1).orEmpty()',
'        val master = unescape(url.replace("\\\\/", "/"))',
'        runCatching { hlsVerified.add(master) }',
'        return HanimeDetail(',
'            video = HanimeVideo(v.id, v.url, name.ifBlank { v.title }, v.thumb, "", "", "", "madaoav"),',
'            description = "", tags = listOf("madaoav"),',
'            sources = listOf(1080 to master), related = emptyList(),',
'        )',
'    }',
'',
]) + MDAV_ANCHOR

BD_OLD = '        "madao" -> runCatching { madaoDetail(v) }.getOrNull()'
BD_NEW = '        "madao" -> runCatching { madaoDetail(v) }.getOrNull()' + NL + '        "mdav" -> runCatching { mdavDetail(v) }.getOrNull()'

BL_OLD = '            siteName == "x站精选" -> xzelListing(base, page)'
BL_NEW = '            siteName == "x站精选" -> xzelListing(base, page)' + NL + '            // 第 215 轮：madaoav 独立板块走自己的 maccms 解析' + NL + '            siteName == "madaoav" -> mdavListing(base, page)'

BS_FN = NL + '    /** 第 216 轮：统一板块搜索（实测三站可用：8xx6 /?s=、madaoav /vodsearch/、qyul /search/<enc>/）。 */\n    fun boardSearchQuick(siteName: String, base: String, query: String): List<HanimeVideo> {\n        val origin = originOf(base)\n        val enc = java.net.URLEncoder.encode(query, "UTF-8")\n        val html: String = when {\n            siteName.contains("麻豆") -> runCatching { getHtml("https://www.8xx6.lol/?s=" + enc) }.getOrDefault("")\n            siteName.equals("madaoav", true) -> runCatching { getHtml("https://madaoav.com/vodsearch/" + enc + "------------.html") }.getOrDefault("")\n            siteName.contains("青青草") -> runCatching { getHtml("https://qyul24.com/search/" + enc + "/") }.getOrDefault("")\n            else -> ""\n        }\n        if (html.isBlank()) return emptyList()\n        val out = LinkedHashMap<String, HanimeVideo>()\n        // 麻豆 8xx6 卡片：document.write(d(\'base64\')) 标题\n        for (t in Regex("""<a[^>]*href="([^"]*/video/(\\d+)\\.html)"[^>]*>\\s*<script[^>]*>\\s*document\\.write\\(d\\(\'([A-Za-z0-9+/=]+)\'\\)\\)""",\n                RegexOption.IGNORE_CASE).findAll(html)) {\n            val vid = t.groupValues[2]\n            val abs = runCatching { URI("https://www.8xx6.lol").resolve(unescape(t.groupValues[1])).toString() }.getOrNull() ?: continue\n            if (out.containsKey(abs)) continue\n            out[abs] = HanimeVideo(\n                id = abs.takeLast(60), url = abs, title = madaoTitle(t.groupValues[3]).ifBlank { "麻豆传媒 · " + vid },\n                thumb = "", duration = "", likes = "", views = "", brand = "",\n            )\n        }\n        // madaoav maccms 卡片\n        for (t in Regex("""<a[^>]*href="([^"]*(?:/m32c6)?/vodplay/[^"]+)"[^>]*title="([^"]*)"[^>]*>[\\s\\S]{0,600}?<img[^>]*src="([^"]+)"""",\n                RegexOption.IGNORE_CASE).findAll(html)) {\n            val abs = runCatching { URI("https://madaoav.com").resolve(unescape(t.groupValues[1])).toString() }.getOrNull() ?: continue\n            if (out.containsKey(abs)) continue\n            out[abs] = HanimeVideo(\n                id = abs.takeLast(60), url = abs, title = unescape(t.groupValues[2]).trim(),\n                thumb = cleanThumb(t.groupValues[3], "https://madaoav.com/"), duration = "", likes = "", views = "", brand = "madaoav",\n            )\n        }\n        // 青青草卡片：a[title] + img[data-original]\n        for (t in Regex("""<a href="(https?://[^"\' ]+/videos/\\d+/[^"\' ]*/)"[^>]*title="([^"]*)"[^>]*>[\\s\\S]{0,500}?<img[^>]*data-original="([^"]+)"""",\n                RegexOption.IGNORE_CASE).findAll(html)) {\n            val abs = t.groupValues[1]\n            if (out.containsKey(abs)) continue\n            out[abs] = HanimeVideo(\n                id = abs.takeLast(60), url = abs, title = unescape(t.groupValues[2]).trim(),\n                thumb = cleanThumb(t.groupValues[3], "https://qyul24.com/"), duration = "", likes = "", views = "", brand = "",\n            )\n        }\n        com.han1me.lab.LabLog.i("x99", "搜索 " + siteName + " q=" + query.take(20) + " => " + out.size + " 卡")\n        return out.values.toList()\n    }\n'

SBD_OLD = '                            val r = runCatching {\n                                if (xchina) X99.xchinaSearch(X99.originOf(lineUrl), q)\n                                else X99.missavSearch(X99.originOf(lineUrl), q, 1)\n                            }'
SBD_NEW = '                            val r = runCatching {\n                                // 第 216 轮：接入统一板块搜索（8xx6 / madaoav / 青青草 实测可用；其余站回落旧路径）\n                                val bs = X99.boardSearchQuick(site.name, lineUrl, q)\n                                if (bs.isNotEmpty()) bs\n                                else if (xchina) X99.xchinaSearch(X99.originOf(lineUrl), q)\n                                else X99.missavSearch(X99.originOf(lineUrl), q, 1)\n                            }'

BLFUN_OLD = '    fun boardListing(siteName: String, base: String, tabPath: String?, page: Int): List<HanimeVideo> {'
QIPAO_OLD = '        X99Site(\n            name = "气泡视频",\n            desc = "气泡视频 qipaotv · 三次元（发布页 qpdizhi 自动解析最新域名）",\n            url = "https://www.qipaotv.com/",\n            lines = linesFor(\n                "https://www.qipaotv.com", "x99.pub.qipao",\n                listOf("https://www.qipaotv.com", "https://qipaotv.com"),\n            ),\n        ),\n'
QIPAO_NEW = '        X99Site(\n            name = "气泡视频",\n            desc = "气泡视频 qipaotv · 三次元（发布页 qpdizhi 自动解析最新域名）",\n            url = "https://www.qipaotv.com/",\n            lines = linesFor(\n                "https://www.qipaotv.com", "x99.pub.qipao",\n                listOf("https://www.qipaotv.com", "https://qipaotv.com", "https://www.qipaosp2.kdns.fr", "https://qipaosp.dpdns.org", "https://www.qipaosp.kdns.fr"),\n            ),\n        ),\n'

QQC_OLD = '    private fun qqcDetail(v: HanimeVideo): HanimeDetail? {\n        val html = getHtml(v.url)\n        val raws = Regex("""(?:function/\\d+/)?(https?://[^"\'\\s<>]+/get_file/[^"\'\\s<>]*\\.mp4[^"\'\\s<>]*)""")\n            .findAll(html).map { it.groupValues[1] }\n            // 第 207 轮（真机报错 CONTAINER_UNSUPPORTED：拿到的是 screenshots/4.jpg/）：\n            // get_file 下既有视频也有截图，必须按扩展名过滤\n            .filter { it.contains(".mp4") && !it.contains("screenshot", true) && !it.contains(".jpg", true) && !it.contains(".png", true) }\n            .distinct().toList()\n        if (raws.isEmpty()) return null\n        val sources = raws.map { u ->\n            val br = Regex("""[?&]br=(\\d+)""").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: 0\n            br to u\n        }.sortedByDescending { it.first }.map { (br, u) ->\n            when {\n                br >= 1000 -> 1080\n                br >= 500 -> 720\n                br > 0 -> 480\n                else -> 1080\n            } to u\n        }.distinctBy { it.second }\n        return HanimeDetail(\n            video = HanimeVideo(v.id, v.url, metaOf(html, "og:title").ifBlank { v.title },\n                metaOf(html, "og:image").ifBlank { v.thumb }, "", "", "", "青青草视频"),\n            description = metaOf(html, "og:description").take(900), tags = listOf("青青草视频"),\n            // 第 205 轮：get_file 只做 302 派发，必须自己跟到 remote_control.php（实测跟随后 206/video/mp4）\n            sources = sources.map { (q2, u2) -> q2 to follow302(u2, v.url) }.distinctBy { it.second }, related = emptyList(),\n        )\n    }\n'
QQC_NEW = '    /** 第 217 轮：页面下发的 get_file token 只对浏览器放行（OkHttp/curl 指纹一律 404），\n    //  原生直链不可行 → 返回 null 落网页版，WebView 的浏览器栈可以播。 */\n    private fun qqcDetail(v: HanimeVideo): HanimeDetail? = null\n'

CACHE_ANCHOR = '    private fun qqcDetail(v: HanimeVideo): HanimeDetail? = null'
CACHE_NEW = '    private fun qqcDetail(v: HanimeVideo): HanimeDetail? = null\n\n    // ==================== 第 218 轮：详情/搜索内存缓存（性能） ====================\n    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, HanimeDetail>>()\n    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<HanimeVideo>>>()\n    private const val DETAIL_CACHE_MS = 10 * 60_000L\n    private const val SEARCH_CACHE_MS = 2 * 60_000L\n\n    fun detailCacheGet(key: String): HanimeDetail? {\n        val e = detailCache[key] ?: return null\n        return if (System.currentTimeMillis() - e.first <= DETAIL_CACHE_MS) e.second else { detailCache.remove(key); null }\n    }\n\n    fun detailCachePut(key: String, d: HanimeDetail) {\n        detailCache[key] = System.currentTimeMillis() to d\n        if (detailCache.size > 40) detailCache.keys.take(detailCache.size - 40).forEach { detailCache.remove(it) }\n    }\n\n    fun searchCacheGet(key: String): List<HanimeVideo>? {\n        val e = searchCache[key] ?: return null\n        return if (System.currentTimeMillis() - e.first <= SEARCH_CACHE_MS) e.second else { searchCache.remove(key); null }\n    }\n\n    fun searchCachePut(key: String, v: List<HanimeVideo>) {\n        searchCache[key] = System.currentTimeMillis() to v\n        if (searchCache.size > 24) searchCache.keys.take(searchCache.size - 24).forEach { searchCache.remove(it) }\n    }\n'


BDET_OLD = '    fun boardDetail(v: HanimeVideo): HanimeDetail? = when (boardOf(v.url)) {'
BDET_NEW = '    fun boardDetail(v: HanimeVideo): HanimeDetail? {\n        detailCacheGet(v.url)?.let { return it }\n        return boardDetailUncached(v)?.also { detailCachePut(v.url, it) }\n    }\n\n    private fun boardDetailUncached(v: HanimeVideo): HanimeDetail? = when (boardOf(v.url)) {'
BSC_OLD = '        com.han1me.lab.LabLog.i("x99", "搜索 " + siteName + " q=" + query.take(20) + " => " + out.size + " 卡")\n        return out.values.toList()'
BSC_NEW = '        val results = out.values.toList()\n        com.han1me.lab.LabLog.i("x99", "搜索 " + siteName + " q=" + query.take(20) + " => " + results.size + " 卡")\n        searchCachePut(siteName + "|" + query, results)\n        return results'

MDAV_UNESC_ANCHOR = '    /** madaoav（maccms）：卡片 /m32c6/vodplay/vodN；详情页 player_aaaa.url 直出 m3u8。 */'
MDAV_UNESC_NEW = '    /** 第 220 轮：还原 JSON 字符串里的 \\uXXXX（Kotlin 无内置，手写扫描）。 */\n    private fun jsonUnescapeU(s: String): String {\n        if (!s.contains("\\\\u")) return s\n        val sb = StringBuilder(s.length)\n        var i = 0\n        while (i < s.length) {\n            val c = s[i]\n            if (c == \'\\\\\' && i + 5 < s.length && s[i + 1] == \'u\') {\n                val hex = s.substring(i + 2, i + 6)\n                val code = hex.toIntOrNull(16)\n                if (code != null) { sb.append(code.toChar()); i += 6; continue }\n            }\n            sb.append(c); i++\n        }\n        return sb.toString()\n    }\n    /** madaoav（maccms）：卡片 /m32c6/vodplay/vodN；详情页 player_aaaa.url 直出 m3u8。 */'
MDAV_MASTER_OLD = '        val master = unescape(url.replace("\\\\/", "/"))'
MDAV_MASTER_NEW = '        val master = unescape(url.replace("\\\\/", "/"))\n        // 第 220 轮：JSON 里的 \\uXXXX 转义（介绍页标题乱码数字的根因）——这里手工还原。'
MDAV_NAME_OLD = '        val name = Regex(""""vod_name"\\s*:\\s*"([^"]*)"""").find(m.groupValues[1])?.groupValues?.get(1).orEmpty()'
MDAV_NAME_NEW = '        val name = jsonUnescapeU(Regex(""""vod_name"\\s*:\\s*"([^"]*)"""").find(m.groupValues[1])?.groupValues?.get(1).orEmpty())'

HIDDEN_OLD = 'private val HIDDEN_SITES: Set<String> = setOf(\n        "XVIDEOS",\n        "Xanimu",\n        "avjoy",\n        "SuperPorn",\n        "亚洲素人精选",\n        "YesPorn",\n        "禁漫天堂",\n        "99漫画",\n        "爱漫岛",\n        "CartoonPorno",\n        "Hentai",\n        "情色小说",\n        "OKXXX",\n        "VJAV",\n        "成人影片数据库",\n        "三级片资源，稀缺资源",\n        "奶豆Ai",\n    )'
HIDDEN_NEW = 'private val HIDDEN_SITES: Set<String> = setOf(\n        "xchina",\n        "XVIDEOS",\n        "Xanimu",\n        "avjoy",\n        "SuperPorn",\n        "亚洲素人精选",\n        "YesPorn",\n        "禁漫天堂",\n        "99漫画",\n        "爱漫岛",\n        "CartoonPorno",\n        "Hentai",\n        "情色小说",\n        "OKXXX",\n        "VJAV",\n        "成人影片数据库",\n        "三级片资源，稀缺资源",\n        "奶豆Ai",\n    )'

SEARCH_GATE_OLD = '            if (missav || xchina) {'
SEARCH_GATE_NEW = '            if (true) {'
SEARCH_FALL_OLD = '                                if (bs.isNotEmpty()) bs\n                                else if (xchina) X99.xchinaSearch(X99.originOf(lineUrl), q)\n                                else X99.missavSearch(X99.originOf(lineUrl), q, 1)'
SEARCH_FALL_NEW = '                                if (bs.isNotEmpty()) bs\n                                else if (xchina) X99.xchinaSearch(X99.originOf(lineUrl), q)\n                                else if (missav) X99.missavSearch(X99.originOf(lineUrl), q, 1)\n                                else emptyList<HanimeVideo>()'

ANIM_OLD = '                    SiteCard(v, width = null, onClick = { onOpenDetail(v) })'
ANIM_NEW = '                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.animateItemPlacement()) {\n                        SiteCard(v, width = null, onClick = { onOpenDetail(v) })\n                    }'

AGG_NAMES_OLD = '        val names = sites.map { it.name }.filter { status.containsKey(it) }'
AGG_NAMES_NEW = '        val names = sites.map { it.name }.filter { status.containsKey(it) && (filterSite == null || it == filterSite) }'
AGG_ANCHOR_OLD = '        // 分组结果：板块名做整跨页脚，卡片两列（与 xchina 板块同一版式）'
AGG_ANCHOR_NEW = '        // 第 222 轮：顶栏 chip 过滤（点击只看该站；再点回全部）\n        var filterSite by remember { mutableStateOf<String?>(null) }\n        // 第 222 轮：与最新页同款的可滑动板块顶栏\n        Row(\n            verticalAlignment = Alignment.CenterVertically,\n            horizontalArrangement = Arrangement.spacedBy(8.dp),\n            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),\n        ) {\n            sites.map { it.name }.filter { status.containsKey(it) }.forEach { nm ->\n                val active = filterSite == nm\n                Text(\n                    nm + " " + (status[nm] ?: ""),\n                    color = if (active) Color.White else Color(0xFFB9B9C0),\n                    fontSize = 12.sp,\n                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,\n                    modifier = Modifier\n                        .clip(RoundedCornerShape(14.dp))\n                        .background(if (active) AccentRed else CardColorSoft)\n                        .clickable { filterSite = if (active) null else nm }\n                        .padding(horizontal = 11.dp, vertical = 5.dp),\n                )\n            }\n        }\n        // 分组结果：板块名做整跨页脚，卡片两列（与 xchina 板块同一版式）'
QIPAO_FALL_OLD = '    fun boardListing(siteName: String, base: String, tabPath: String?, page: Int): List<HanimeVideo> {'
QIPAO_FALL_NEW = '    fun boardListing(siteName: String, base: String, tabPath: String?, page: Int): List<HanimeVideo> {\n        // 第 235 轮：气泡 = qipaotv 原生 JSON 源（qpListing），不再走 genericListing。\n        if (siteName == "气泡视频") {\n            return qpListing(page)\n        }'

SMART_FALL_OLD = '            return genericListing("jptt", "https://jptt.tv/", page)\n        }'
SMART_FALL_NEW = '            // 有内置节点时先试气泡原站（历史行为），无节点直接 jptt（避免 SPA 白等）\n            if (com.han1me.lab.GlobalProxy.active) {\n                val qipao = runCatching { genericListing(siteName, base, page) }.getOrDefault(emptyList())\n                if (qipao.isNotEmpty()) return qipao\n            }\n            return genericListing("jptt", "https://jptt.tv/", page)\n        }'

GENLIST_PROBE_OLD = '                val u = pageUrlFor(siteName, base, page)\n                val cards = parseGeneric(u, getHtml(u, referer = origin + "/"))'
GENLIST_PROBE_NEW = '                val u = pageUrlFor(siteName, base, page)\n                val __t0 = System.currentTimeMillis()\n                val cards = parseGeneric(u, getHtml(u, referer = origin + "/"))\n                com.han1me.lab.LabLog.i("x99", "列表尝试 " + siteName + " p=" + page + " " + u + " -> " + cards.size + " 卡 " + (System.currentTimeMillis() - __t0) + "ms")'

QP_IMPL_OLD = '    // ---------- 板块分发'
QP_IMPL_NEW = '    // ---------- 第 235 轮：气泡视频（qipaotv 原生 JSON 源，纯应用内 HTTP） ----------\n    // 数据链路（全部经 App 自身网络栈，零系统修改）：\n    //   1. POST /api-verify {"token":"fallback"} -> {ok,tk}（tk=过期毫秒.哈希，临近过期自动刷新）\n    //   2. GET  /api-jisu/?ac=videolist&pg=N（头带 x-app-token）-> 标准 maccms JSON（total=5万+）\n    //   3. GET  /api-jisu/?ac=detail&ids=ID -> vod_play_url「第1集$m3u8」\n    // 响应体是【反转的 base64】；另有间歇性 JS 陷阱页（约 1/3 概率），识别后重试即可。\n    private fun qpDec(text: String): org.json.JSONObject {\n        val t = text.trim()\n        if (t.startsWith("{") || t.startsWith("[")) return org.json.JSONObject(t)\n        val rev = t.reversed().trimStart(\'=\')\n        val sb = StringBuilder(rev)\n        while (sb.length % 4 != 0) sb.append(\'=\')\n        val bytes = java.util.Base64.getDecoder().decode(sb.toString())\n        return org.json.JSONObject(String(bytes, Charsets.UTF_8))\n    }\n\n    private fun qpIsBad(b: String): Boolean =\n        b.isBlank() || b.contains("abc = 0") || b.lowercase().startsWith("<!doctype")\n\n    private fun qpToken(): String {\n        val cached = setting("x99.qp.token", "")\n        val exp = cached.substringBefore(\'.\').toLongOrNull() ?: 0L\n        if (cached.isNotBlank() && exp - System.currentTimeMillis() > 1_800_000L) return cached\n        repeat(3) {\n            try {\n                val body = "{\\"token\\":\\"fallback\\"}".toRequestBody("application/json".toMediaType())\n                val req = Request.Builder().url("https://www.qipaotv.com/api-verify")\n                    .header("User-Agent", UA)\n                    .header("Referer", "https://www.qipaotv.com/")\n                    .post(body).build()\n                http.newCall(req).execute().use { resp ->\n                    val b = resp.body?.string().orEmpty()\n                    if (!resp.isSuccessful || qpIsBad(b)) throw IllegalStateException("HTTP " + resp.code)\n                    val tk = qpDec(b).optString("tk")\n                    if (tk.isNotBlank()) {\n                        putSetting("x99.qp.token", tk)\n                        return tk\n                    }\n                    throw IllegalStateException("no tk")\n                }\n            } catch (t: Throwable) {\n                Thread.sleep(600)\n            }\n        }\n        return cached\n    }\n\n    private fun qpApi(path: String): org.json.JSONObject {\n        var last: Throwable? = null\n        repeat(3) {\n            try {\n                val tk = qpToken()\n                val req = Request.Builder().url("https://www.qipaotv.com" + path)\n                    .header("User-Agent", UA)\n                    .header("x-app-token", tk)\n                    .header("Referer", "https://www.qipaotv.com/")\n                    .build()\n                http.newCall(req).execute().use { resp ->\n                    val b = resp.body?.string().orEmpty()\n                    if (!resp.isSuccessful || qpIsBad(b)) throw IllegalStateException("HTTP " + resp.code)\n                    return qpDec(b)\n                }\n            } catch (t: Throwable) {\n                last = t\n                putSetting("x99.qp.token", "")   // token 可能过期，强制刷新\n                Thread.sleep(500)\n            }\n        }\n        throw IllegalStateException(last?.message ?: "qipaotv 请求失败")\n    }\n\n    private fun qpCards(j: org.json.JSONObject): List<HanimeVideo> {\n        val arr = j.optJSONArray("list") ?: return emptyList()\n        val out = ArrayList<HanimeVideo>()\n        for (i in 0 until arr.length()) {\n            val o = arr.optJSONObject(i) ?: continue\n            val id = o.optString("vod_id")\n            val name = o.optString("vod_name")\n            if (id.isBlank() || name.isBlank()) continue\n            out.add(HanimeVideo(\n                id = id,\n                url = "https://www.qipaotv.com/api-jisu/?ac=detail&ids=" + id,\n                title = name,\n                thumb = o.optString("vod_pic"),\n                duration = "", likes = "", views = "", brand = "气泡视频",\n            ))\n        }\n        return out\n    }\n\n    fun qpListing(page: Int): List<HanimeVideo> {\n        val cards = qpCards(qpApi("/api-jisu/?ac=videolist&pg=" + page.coerceAtLeast(1)))\n        com.han1me.lab.LabLog.i("x99", "qpListing pg=" + page + " -> " + cards.size + " 卡")\n        return cards\n    }\n\n    fun qpSearch(kw: String, page: Int): List<HanimeVideo> {\n        val enc = java.net.URLEncoder.encode(kw, "UTF-8")\n        val path = "/api-jisu/?ac=videolist&wd=" + enc + if (page > 1) "&pg=" + page else ""\n        return qpCards(qpApi(path))\n    }\n\n    fun qpDetail(v: HanimeVideo): HanimeDetail? {\n        val id = Regex("ids=(\\\\d+)").find(v.url)?.groupValues?.get(1) ?: v.id\n        val j = qpApi("/api-jisu/?ac=detail&ids=" + id)\n        val arr = j.optJSONArray("list") ?: return null\n        val o = arr.optJSONObject(0) ?: return null\n        val play = o.optString("vod_play_url")\n        val m3u8 = play.split(\'#\').firstOrNull().orEmpty().substringAfter(\'$\', "").trim()\n        if (m3u8.isBlank()) return null\n        runCatching { hlsVerified.add(m3u8) }\n        return HanimeDetail(\n            video = HanimeVideo(v.id, v.url, o.optString("vod_name").ifBlank { v.title },\n                o.optString("vod_pic").ifBlank { v.thumb }, "", "", "", "气泡视频"),\n            description = o.optString("vod_content"),\n            tags = listOf("气泡视频"),\n            sources = listOf(1080 to m3u8),\n            related = emptyList(),\n        )\n    }\n\n    fun isQpUrl(u: String): Boolean = u.contains("qipaotv.com/api-jisu/")\n    // ---------- 板块分发'
QP_BOARDOF_OLD = '        isMdavUrl(u) -> "mdav"'
QP_BOARDOF_NEW = '        isMdavUrl(u) -> "mdav"\n        isQpUrl(u) -> "qp"'
QP_DETAIL_OLD = '        "mdav" -> runCatching { mdavDetail(v) }.getOrNull()'
QP_DETAIL_NEW = '        "mdav" -> runCatching { mdavDetail(v) }.getOrNull()\n        "qp" -> runCatching { qpDetail(v) }.getOrNull()'
QP_SEARCH_OLD = '            isQinavSite(siteName) -> null'
QP_SEARCH_NEW = '            siteName == "气泡视频" -> qpSearch(kw, page)\n            isQinavSite(siteName) -> null'
COS_TAB_OLD = '        "fav" to ("♥" to "收藏"), "user" to ("◉" to "用户"),'
COS_TAB_NEW = '        "cos" to ("🎭" to "cos"),         "fav" to ("♥" to "收藏"), "user" to ("◉" to "用户"),'
COS_SEL_OLD = '            "latest" -> board = "sites"'
COS_SEL_NEW = '            "latest" -> board = "sites"\n            "cos" -> board = "cos"'
COS_ROUTE_OLD = '                "search" -> GlobalSearchTab('
COS_ROUTE_NEW = '                "cos" -> com.han1me.cos.CosBoardScreen(onBack = {\n                    board = "hanime"; tab = "home"; backToHome()\n                })\n                "search" -> GlobalSearchTab('
OS_FAV_NEW = 'if (v.url.contains("cosplaytele.com")) {'
OS_FAV_NEW = '                    onOpenBoardVideo = { v ->\n                        if (v.url.contains("cosplaytele.com")) {\n                            val cosPost = com.han1me.cos.CosRepo.Post(v.url, v.title, v.thumb,\n                                v.title.contains("video", true))\n                            cosFavOpen = cosPost\n                        } else {\n                        val isBoard = runCatching { X99.isNativeBoardUrl(v.url) }.getOrDefault(false)\n                        openDetail(v, fromBoard = isBoard)\n                        }'
# 第 250 轮：cos 收藏跳转状态
COS_FAV_STATE_OLD = '    var favFilter by remember { mutableStateOf("全部") }'
COS_FAV_STATE_NEW = '    var favFilter by remember { mutableStateOf("全部") }\n    var cosFavOpen by remember { mutableStateOf<com.han1me.cos.CosRepo.Post?>(null) }'
COS_SCREEN_ARGS_OLD = '                "cos" -> com.han1me.cos.CosBoardScreen(onBack = {\n                    board = "hanime"; tab = "home"; backToHome()\n                })'
COS_SCREEN_ARGS_NEW = '                "cos" -> com.han1me.cos.CosBoardScreen(onBack = {\n                    board = "hanime"; tab = "home"; backToHome()\n                }, cosFavOpen = cosFavOpen, onCosFavClose = { cosFavOpen = null })'
FS_BAR_OLD = '        if (detail == null && webPage == null) {'
FS_BAR_NEW = '        if (detail == null && webPage == null && !com.han1me.HplayEnv.isFullscreen) {'
COS_FAV_ROUTE_OLD = '                    onOpenBoardVideo = { v ->\n                        val isBoard = runCatching { X99.isNativeBoardUrl(v.url) }.getOrDefault(false)\n                        openDetail(v, fromBoard = isBoard)\n                    },'
COS_FAV_ROUTE_NEW = '                    onOpenBoardVideo = { v ->\n                        if (v.url.contains("cosplaytele.com")) {\n                            cosFavOpen = com.han1me.cos.CosRepo.Post(v.url, v.title, v.thumb,\n                                v.title.contains("video", true))\n                            tab = "cos"\n                        } else {\n                            val isBoard = runCatching { X99.isNativeBoardUrl(v.url) }.getOrDefault(false)\n                            openDetail(v, fromBoard = isBoard)\n                        }\n                    },'
COS_FAV_OLD = '                    onOpenBoardVideo = { v ->\n                        val isBoard = runCatching { X99.isNativeBoardUrl(v.url) }.getOrDefault(false)\n                        openDetail(v, fromBoard = isBoard)\n                    },'
COS_FAV_NEW = '                    onOpenBoardVideo = { v ->\n                        // 第 252 轮：cos 收藏条目跳转 cos 详情页\n                        if (v.url.contains("cosplaytele.com")) {\n                            cosFavOpen = com.han1me.cos.CosRepo.Post(v.url, v.title, v.thumb,\n                                v.title.contains("video", true))\n                            }\n                        else {\n                        val isBoard = runCatching { X99.isNativeBoardUrl(v.url) }.getOrDefault(false)\n                        openDetail(v, fromBoard = isBoard)\n                    },'
X99_CLR_OLD = '    fun isFav(url: String): Boolean = url.isNotEmpty() && favs().any { it.url == url }'
X99_CLR_NEW = '    fun isFav(url: String): Boolean = url.isNotEmpty() && favs().any { it.url == url }\n    fun clearFavsCache() { favsCache = null }'
    # ★ 第 324 轮：换站后丢弃旧站回包（含 error）
SITEGUARD_OLD = '                if (myGen != loadGen) return@withContext'
SITEGUARD_NEW = '                if (myGen != loadGen) return@withContext\n                // ★★★ 2026-11-25（第 324 轮 · SITEGUARD）：**换站后丢弃旧站回包（含 error）**。\n                //\n                // 真机实测（22:57:19）：点进糖心（索引命中 10838 条）后，\n                // 上一站 MissAV 的请求才迟迟返回 404，于是界面上出现\n                //   「出错：HTTP 404（不可重试）：https://tangxinvlog.app/cn/new」\n                // —— **新站的名字 + 旧站的错误**，两边都不对。\n                // loadGen 只能阻止卡片被覆盖，拦不住 onFailure 里的 error 赋值\n                // （MissAV 那次 load 发起时 loadGen 还没递增）。\n                // 现在直接比站名：回包时已经不是同一个站就整包丢弃。\n                if (s.name != site?.name) return@withContext\n                if (s.name != loadedSite) return@withContext'

# ★ 第 324 轮：cos 入口回调（调用处无法不传）
ONCOS_CALL_OLD = '                X99Board(\n                    refreshKey = refreshKey,\n                    onToast = { toast = it },\n                    onOpenDetail = { v -> openDetail(v, fromBoard = true) },\n                    onOpenDialog = { x99Focus = it },\n                    onBack = { board = "hanime"; backToHome() },\n                )'
ONCOS_CALL_NEW = '                X99Board(\n                    refreshKey = refreshKey,\n                    onToast = { toast = it },\n                    onOpenDetail = { v -> openDetail(v, fromBoard = true) },\n                    onOpenDialog = { x99Focus = it },\n                    onBack = { board = "hanime"; backToHome() },\n                    // ★★★ 2026-11-25（第 324 轮）：**cos 入口回调**。\n                    //\n                    // 上一步只把入口卡挂进了 X99Board，但**调用处没传 onCos**，\n                    // 于是它用的是默认值 {}（空实现）—— 卡片显示了，\n                    // 点下去反应一点都没有（真机 23:05 实测）。\n                    // 现在接到 tab = "cos"，与底栏那个已删的 cos tab 走同一条路径。\n                    onCos = { board = "hanime"; tab = "cos" },\n                )'

COLOR_TOKENS = [
    ("Color(0xFFE8E8EA)", "HplayOnSurface"),
    ("Color(0xFFCFCFD4)", "HplayOnSurface"),
    ("Color(0xFFD8D8DC)", "HplayOnSurface"),
    ("Color(0xFFE7E7EC)", "HplayOnSurface"),
    ("Color(0xFFB8B8C0)", "HplayOnSurfaceVariant"),
    ("Color(0xFF9A9AA2)", "HplayOnSurfaceVariant"),
    ("Color(0xFF8A8A92)", "HplayOnSurfaceVariant"),
    ("Color(0xFF6A6A72)", "HplayOnSurfaceVariant"),
    ("Color(0xFFB9B9C0)", "HplayOnSurfaceVariant"),
    ("Color(0xFF23232A)", "HplaySurfaceContainer"),
    ("Color(0xFF2E2E34)", "HplaySurfaceContainer"),
    ("Color(0xFF1C1C20)", "HplaySurfaceContainer"),
    ("Color(0xFF1C1C21)", "HplaySurfaceContainer"),
    ("Color(0xFF26262B)", "HplaySurfaceContainer"),
    ("Color(0xFF141417)", "HplaySurfaceLow"),
    ("Color(0xFF141416)", "HplaySurfaceLow"),
    ("Color(0xFF1A1A1E)", "HplaySurfaceLow"),
    ("Color(0xFFB3262C)", "HplayPrimary"),
    ("Color(0xFFFF8A8A)", "HplayErrorSoft"),
    ("Color(0xFF7ED97E)", "HplaySuccess"),
    ("Color(0xFF4CAF50)", "HplaySuccess"),
    ("Color(0xFFFFC46B)", "HplayWarning"),
    ("Color(0xFFE5A05A)", "HplayWarning"),
    ("Color(0xFF4A4A52)", "HplayOutline"),
    ("Color(0xFF2A2A30)", "HplaySurfaceContainer"),
    ("Color(0xFFB8E0B8)", "HplaySuccess"),
    ("Color(0xFFFF6B6B)", "HplayErrorSoft"),
]

TOKEN_FILES = ["X99Board.kt", "Han1meWeb.kt", "Han1meApp.kt"]


def apply_color_tokens():
    """把生成文件里的硬编码色换成 MD3 语义 token（第 325 轮）。"""
    n_total = 0
    for name in TOKEN_FILES:
        f = GEN / name
        if not f.exists():
            continue
        s = io.open(f, encoding="utf-8", newline="").read()
        orig = s
        n = 0
        for old, tok in COLOR_TOKENS:
            c = s.count(old)
            if c:
                s = s.replace(old, tok)
                n += c
        if s != orig:
            io.open(f, "w", encoding="utf-8", newline="").write(s)
            print("  [MD3 token] %s: %d" % (name, n))
            n_total += n
    return n_total



# ★ 第 326 轮：旧播放内核彻底移除（消除重复 ExoPlayer）
# ★ 第 327 轮：列表网格列数自适应

# ★ 第 328 轮：卡片到达淡入（最新页转场）
CARDFADE_OLD = '                    modifier = Modifier.fillMaxSize(),\n                ) {\n                items(cards, key = { it.url }) { v ->'
CARDFADE_NEW = '                    // ★★★ 2026-11-26（第 328 轮）：**卡片到达时淡入**。\n                    //\n                    // 用户反复报「最新页在加载时没有转场，加载完成才有」。\n                    // 真因不在外层（外层 Column 已挂 bodyFade，key 含 board），\n                    // 而在这个 when：转圈 → 空态 → 网格，是**硬切**；\n                    // 卡片一到整屏瞬间换掉，看起来就是「突然出现」。\n                    //\n                    // 用 pageFade（单行 Modifier 扩展，不产生新大括号），key 用首张卡片的 url：\n                    // 首页到达/换板块/换线路都重放一次；加载更多时首张不变，不会闪。\n                    modifier = Modifier.fillMaxSize().boardFade(cards.firstOrNull()?.url ?: "empty"),\n                ) {\n                items(cards, key = { it.url }) { v ->'

# ★ 第 328 轮：导入 pageFade（卡片淡入用）

RULES = [
    ("X99Board.kt", CARDFADE_OLD, CARDFADE_NEW),
    ("Han1meApp.kt", ONCOS_CALL_OLD, ONCOS_CALL_NEW),
    # ★ 第 324 轮：换站后丢弃旧站回包（含 error）
    ("X99Board.kt", SITEGUARD_OLD, SITEGUARD_NEW),
    ("X99.kt", MADAO_OLD, MADAO_NEW),
    ("X99.kt", DISP_OLD, DISP_NEW),
    ("X99.kt", BOARD_OLD, BOARD_NEW),
    ("X99.kt", PUB_RE_OLD, PUB_RE_NEW),
    ("X99.kt", FETCH_OLD, FETCH_NEW),
    ("X99.kt", PUBSRC_MADAO_OLD, PUBSRC_MADAO_NEW),
    ("X99.kt", PUBSRC_QIPAO_OLD, PUBSRC_QIPAO_NEW),
    ("X99.kt", MADAO_SPLIT_OLD, MADAO_SPLIT_NEW),
    ("X99.kt", BO_OLD, BO_NEW),
    ("X99.kt", MDAV_ANCHOR, MDAV_IMPL),
    ("X99.kt", BD_OLD, BD_NEW),
    ("X99.kt", BL_OLD, BL_NEW),
    ("X99.kt", BLFUN_OLD, BS_FN + BLFUN_OLD),
    ("X99Board.kt", SBD_OLD, SBD_NEW),
    ("X99.kt", QIPAO_OLD, QIPAO_NEW),
    ("X99.kt", QQC_OLD, QQC_NEW),
    ("X99.kt", CACHE_ANCHOR, CACHE_NEW),
    ("X99.kt", BDET_OLD, BDET_NEW),
    ("X99.kt", BSC_OLD, BSC_NEW),
    ("X99.kt", HIDDEN_OLD, HIDDEN_NEW),
    ("X99Board.kt", SEARCH_GATE_OLD, SEARCH_GATE_NEW),
    ("X99Board.kt", SEARCH_FALL_OLD, SEARCH_FALL_NEW),
    ("X99Board.kt", AGG_NAMES_OLD, AGG_NAMES_NEW),
    ("X99Board.kt", AGG_ANCHOR_OLD, AGG_ANCHOR_NEW),
    ("X99.kt", QIPAO_FALL_OLD, QIPAO_FALL_NEW),
    ("X99.kt", QP_IMPL_OLD, QP_IMPL_NEW),
    ("X99.kt", QP_BOARDOF_OLD, QP_BOARDOF_NEW),
    ("X99.kt", QP_DETAIL_OLD, QP_DETAIL_NEW),
    ("X99.kt", QP_SEARCH_OLD, QP_SEARCH_NEW),
    ("Han1meApp.kt", COS_TAB_OLD, COS_TAB_NEW),
    ("Han1meApp.kt", COS_SEL_OLD, COS_SEL_NEW),
    ("Han1meApp.kt", COS_ROUTE_OLD, COS_ROUTE_NEW),
    ("X99.kt", GENLIST_PROBE_OLD, GENLIST_PROBE_NEW),
    ("Han1meApp.kt", FS_BAR_OLD, FS_BAR_NEW),
    ("X99.kt", X99_CLR_OLD, X99_CLR_NEW),
    ("Han1meApp.kt", COS_FAV_STATE_OLD, COS_FAV_STATE_NEW),
    ("Han1meApp.kt", COS_SCREEN_ARGS_OLD, COS_SCREEN_ARGS_NEW),
    ("Han1meApp.kt", COS_FAV_ROUTE_OLD, COS_FAV_ROUTE_NEW),
    ("X99.kt", '    private fun qpDec(text: String): org.json.JSONObject {\n        val t = text.trim()\n        if (t.startsWith("{") || t.startsWith("[")) return org.json.JSONObject(t)\n        val rev = t.reversed().trimStart(\'=\')\n        val sb = StringBuilder(rev)\n        while (sb.length % 4 != 0) sb.append(\'=\')\n        val bytes = java.util.Base64.getDecoder().decode(sb.toString())\n        return org.json.JSONObject(String(bytes, Charsets.UTF_8))\n    }',
     '    private fun qpDec(text: String): org.json.JSONObject {\n        val t = text.trim()\n        if (t.startsWith("{") || t.startsWith("[")) return org.json.JSONObject(t)\n        val rev = t.reversed().trimStart(\'=\')\n        val sb = StringBuilder(rev)\n        while (sb.length % 4 != 0) sb.append(\'=\')\n        val bytes = java.util.Base64.getDecoder().decode(sb.toString())\n        // ★★★ 第 267 轮（真机「Value & of type String cannot be converted to JSONObject」）：\n        // 站点升级混淆——反转 b64 之上叠加**每请求随机的单字节 XOR**（实测 0x23/0xe6/0xa8/0xe5\n        // 每请求都不同），解出明文还带 2 字节垃圾前缀。旧解码解出二进制 → JSONObject 必炸\n        // → 整个气泡板块「延迟在线但解析出错」。修复：256 键逐一探测，解出「{…} 且带\n        // tk/list/ok 字样」的窗口即取；站点若回退旧口径，末行兜底仍能解析。\n        for (k in 0 until 256) {\n            val s = String(bytes.map { (it.toInt() xor k).toByte() }.toByteArray(), Charsets.UTF_8)\n            val i = s.indexOf(\'{\')\n            val j = s.lastIndexOf(\'}\')\n            if (i >= 0 && j > i) {\n                val cand = s.substring(i, j + 1)\n                if (cand.contains("\\"tk\\"") || cand.contains("\\"list\\"") || cand.contains("\\"ok\\"")) {\n                    return org.json.JSONObject(cand)\n                }\n            }\n        }\n        return org.json.JSONObject(String(bytes, Charsets.UTF_8))\n    }'),
    ("X99.kt", '    private fun qpToken(): String {\n        val cached = setting("x99.qp.token", "")\n        val exp = cached.substringBefore(\'.\').toLongOrNull() ?: 0L\n        if (cached.isNotBlank() && exp - System.currentTimeMillis() > 1_800_000L) return cached\n        repeat(3) {\n            try {\n                val body = "{\\"token\\":\\"fallback\\"}".toRequestBody("application/json".toMediaType())\n                val req = Request.Builder().url("https://www.qipaotv.com/api-verify")\n                    .header("User-Agent", UA)\n                    .header("Referer", "https://www.qipaotv.com/")\n                    .post(body).build()\n                http.newCall(req).execute().use { resp ->\n                    val b = resp.body?.string().orEmpty()\n                    if (!resp.isSuccessful || qpIsBad(b)) throw IllegalStateException("HTTP " + resp.code)\n                    val tk = qpDec(b).optString("tk")\n                    if (tk.isNotBlank()) {\n                        putSetting("x99.qp.token", tk)\n                        return tk\n                    }\n                    throw IllegalStateException("no tk")\n                }\n            } catch (t: Throwable) {\n                Thread.sleep(600)\n            }\n        }\n        return cached\n    }',
     '    /** 气泡 API 基址候选（第 267 轮 优化线路）：发布页解析出的线路优先 + 记住的可用基线 + 写死兜底。 */\n    private fun qpBases(): List<String> {\n        val all = LinkedHashSet<String>()\n        setting("x99.qp.base", "").takeIf { it.isNotBlank() }?.let { all.add(it) }\n        publishedLines("x99.pub.qipao").forEach { all.add(it.trimEnd(\'/\')) }\n        listOf(\n            "https://www.qipaotv.com",\n            "https://qipaosp.dpdns.org",\n            "https://qipaotv.dpdns.org",\n            "https://www.qipaosp.kdns.fr",\n            "https://www.qipaosp2.kdns.fr",\n            "https://qipaosp.devs.li",\n        ).forEach { all.add(it.trimEnd(\'/\')) }\n        return all.filter { it.startsWith("http") }\n    }\n\n    private fun qpToken(): String {\n        val cached = setting("x99.qp.token", "")\n        val exp = cached.substringBefore(\'.\').toLongOrNull() ?: 0L\n        if (cached.isNotBlank() && exp - System.currentTimeMillis() > 1_800_000L) return cached\n        var last: Throwable? = null\n        for (base in qpBases()) {\n            repeat(2) {\n                try {\n                    val body = "{\\"token\\":\\"fallback\\"}".toRequestBody("application/json".toMediaType())\n                    val req = Request.Builder().url(base + "/api-verify")\n                        .header("User-Agent", UA)\n                        .header("Referer", "https://www.qipaotv.com/")\n                        .post(body).build()\n                    http.newCall(req).execute().use { resp ->\n                        val b = resp.body?.string().orEmpty()\n                        if (!resp.isSuccessful || qpIsBad(b)) throw IllegalStateException("HTTP " + resp.code)\n                        val tk = qpDec(b).optString("tk")\n                        if (tk.isNotBlank()) {\n                            putSetting("x99.qp.base", base)   // 记住可用线路，下次直接用它\n                            putSetting("x99.qp.token", tk)\n                            return tk\n                        }\n                        throw IllegalStateException("no tk")\n                    }\n                } catch (t: Throwable) {\n                    last = t\n                    Thread.sleep(400)\n                }\n            }\n        }\n        return cached\n    }'),
    ("X99.kt", '    private fun qpApi(path: String): org.json.JSONObject {\n        var last: Throwable? = null\n        repeat(3) {\n            try {\n                val tk = qpToken()\n                val req = Request.Builder().url("https://www.qipaotv.com" + path)\n                    .header("User-Agent", UA)\n                    .header("x-app-token", tk)\n                    .header("Referer", "https://www.qipaotv.com/")\n                    .build()\n                http.newCall(req).execute().use { resp ->\n                    val b = resp.body?.string().orEmpty()\n                    if (!resp.isSuccessful || qpIsBad(b)) throw IllegalStateException("HTTP " + resp.code)\n                    return qpDec(b)\n                }\n            } catch (t: Throwable) {\n                last = t\n                putSetting("x99.qp.token", "")   // token 可能过期，强制刷新\n                Thread.sleep(500)\n            }\n        }\n        throw IllegalStateException(last?.message ?: "qipaotv 请求失败")\n    }',
     '    private fun qpApi(path: String): org.json.JSONObject {\n        var last: Throwable? = null\n        for (base in qpBases()) {\n            repeat(2) {\n                try {\n                    val tk = qpToken()\n                    val req = Request.Builder().url(base + path)\n                        .header("User-Agent", UA)\n                        .header("x-app-token", tk)\n                        .header("Referer", "https://www.qipaotv.com/")\n                        .build()\n                    http.newCall(req).execute().use { resp ->\n                        val b = resp.body?.string().orEmpty()\n                        if (!resp.isSuccessful || qpIsBad(b)) throw IllegalStateException("HTTP " + resp.code)\n                        return qpDec(b)\n                    }\n                } catch (t: Throwable) {\n                    last = t\n                    putSetting("x99.qp.token", "")   // token 可能过期，强制刷新\n                    Thread.sleep(400)\n                }\n            }\n        }\n        throw IllegalStateException(last?.message ?: "qipaotv 请求失败")\n    }'),
    ("X99.kt", '                val same = seedRoots.any { sr -> r == sr || r.contains(sr) || sr.contains(r) }\n                if (!same) continue\n            }\n            out.add(u)\n        }\n        return out.toList()\n    }',
     '                val same = seedRoots.any { sr -> r == sr || r.contains(sr) || sr.contains(r) }\n                if (!same) continue\n            }\n            out.add(u)\n        }\n        // ★★★ 第 267 轮：气泡发布页改版为「接入状态与服务导航」——线路改藏在\n        // VIDEO_NODES[].cipher（base64(反转URL)）里，pubAddrRe 一条都抓不到（真机\n        // 「延迟在线但解析出错」的线路侧根因）。识别：页面含 cipher 字段且发布页属\n        // qpdizhi 家族；只收 host 含 qipao 的内容域（qipaotv/qipaosp/qipaodk…），\n        // 发布页镜像（qpdizhi.*）天然被过滤。\n        if (out.isEmpty() && publishUrl.contains("qpdizhi")) {\n            for (m in Regex("""cipher:\\s*\'([A-Za-z0-9+/=]{8,})\'""").findAll(html)) {\n                val dec = runCatching {\n                    java.util.Base64.getDecoder().decode(m.groupValues[1])\n                        .toString(Charsets.UTF_8).reversed()\n                }.getOrDefault("")\n                val u = dec.trim().trimEnd(\'/\')\n                if (!u.startsWith("http")) continue\n                val h = runCatching { URI(u).host.orEmpty().lowercase() }.getOrDefault("")\n                if (h.isBlank() || h == pubHost || !h.contains("qipao")) continue\n                out.add(u.lowercase().trimEnd(\'/\'))\n            }\n        }\n        return out.toList()\n    }'),
    ("X99.kt", '                listOf("https://www.qipaotv.com", "https://qipaotv.com", "https://www.qipaosp2.kdns.fr", "https://qipaosp.dpdns.org", "https://www.qipaosp.kdns.fr"),',
     '                listOf("https://www.qipaotv.com", "https://qipaotv.com", "https://qipaosp.dpdns.org",\n                    "https://qipaotv.dpdns.org", "https://www.qipaosp.kdns.fr", "https://www.qipaosp2.kdns.fr",\n                    "https://qipaosp.devs.li"),'),

]


def apply_rules(text, out_name, strict=True):
    for name, old, new in RULES:
        if name != out_name:
            continue
        n = text.count(old)
        if n == 1:
            text = text.replace(old, new)
            continue
        # 第 229 轮：old 命中 0 次且 new 已存在（此前已应用过）= 幂等跳过；
        # 否则才是真失配（锚点失效），strict 下报错。
        if n == 0 and new in text:
            continue
        if strict:
            raise SystemExit("[后置规则失配] %s：命中 %d 次（应为 1）：%r" % (out_name, n, old[:70]))
    return text
def main():
    apply_color_tokens()
    fails = []
    for f in sorted(GEN.glob("*.kt")):
        text = f.read_text(encoding="utf-8")
        try:
            new = apply_rules(text, f.name)
        except SystemExit as e:
            fails.append(str(e))
            continue
        if new != text:
            f.write_text(new, encoding="utf-8", newline="")
            print("  后置规则 ->", f.name)
    if fails:
        for x in fails:
            print(" ", x)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
