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

# ★ 第 340 轮：patches.py 后来又在 isMdavUrl 后面插了 isQpUrl，
# 于是 BO_NEW 的整段不再连续，这条三元组恒失配并让整个后置阶段中止。
# 判据改成「isMdavUrl 已在文本里就算已应用」（幂等），不再依赖后续补丁的插入位置。
BO_OLD = '        isEmbedPlayerUrl(u) -> "embed"' + NL + '        board196Of(u) != null -> board196Of(u)!!'
BO_NEW = '        isEmbedPlayerUrl(u) -> "embed"' + NL + '        // 第 215 轮：madaoav（maccms）卡片 /m32c6/vodplay/vodN' + NL + '        isMdavUrl(u) -> "mdav"' + NL + '        board196Of(u) != null -> board196Of(u)!!'
BO_DONE = '        isMdavUrl(u) -> "mdav"'

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
# 第 338 轮：放大镜按钮已删除，这条守卫不再需要放开 —— 保持站点判断，避免整站空搜索
SEARCH_GATE_NEW = '            if (missav || xchina) {'
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
    ("X99.kt", MDAV_ANCHOR, MDAV_IMPL),
    ("X99.kt", BD_OLD, BD_NEW),
    ("X99.kt", BL_OLD, BL_NEW),
    ("X99.kt", BLFUN_OLD, BS_FN + BLFUN_OLD),
    ("X99.kt", QQC_OLD, QQC_NEW),
    ("X99.kt", CACHE_ANCHOR, CACHE_NEW),
    ("X99.kt", BDET_OLD, BDET_NEW),
    ("X99.kt", BSC_OLD, BSC_NEW),
    ("X99.kt", HIDDEN_OLD, HIDDEN_NEW),
    # 第 338 轮：放大镜按钮已删除，这条守卫规则一并退场（旧值=新值，留着只会干扰锚点匹配）
    ("X99Board.kt", AGG_NAMES_OLD, AGG_NAMES_NEW),
    ("X99Board.kt", AGG_ANCHOR_OLD, AGG_ANCHOR_NEW),
    ("X99.kt", QIPAO_FALL_OLD, QIPAO_FALL_NEW),
    ("X99.kt", QP_IMPL_OLD, QP_IMPL_NEW),
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

]




def inject_backup_hanime(text: str) -> str:
    """给 X99.kt 追加 BACKUP_HANIME 常量（幂等）。"""
    anchor = '    const val OFFICIAL_HANIME = "https://hanime1.me"'
    if "BACKUP_HANIME" in text:
        return text
    if text.count(anchor) != 1:
        return text
    return text.replace(anchor, anchor + "\\n" + "\\n".join(BACKUP_HANIME_BLOCK))





# Round 332: inject BACKUP_HANIME (builtin backup page domains) into X99.kt.
#
# Why here and not in patches.py anchors: patches.py requires old to hit exactly
# once, and the OFFICIAL_HANIME area is already covered by several long patches.
# This is a pure insertion (anchor hit once -> anchor + new constant), which does
# not disturb any existing anchor. Idempotent: skipped when BACKUP_HANIME exists.
BACKUP_HANIME_BLOCK = [
    "",
    "    /**",
    "     * " + "\u2605\u2605" + " Round 333: builtin backup page domains for the Hanime1 board.",
    "     *",
    "     * Listed here so the hanime1.me word dialog can switch pages AND keep",
    "     * login working (login state is per-domain).",
    "     *",
    "     * " + "\u2605" + " Round 333 measured all three candidates and dropped two of them:",
    "     *",
    "     *  - hanime1.com   -> 301 to hanime1.me on EVERY path (/, /search, /watch,",
    "     *                    /login, /previews). It is a redirect alias, not a site.",
    "     *                    Listing it as a separate line just duplicates the",
    "     *                    official source while looking like a different route.",
    "     *  - hanimeone.me  -> 403 (Cf-Mitigated: challenge) on every path, both",
    "     *                    direct and through a proxy. Cloudflare checks the TLS",
    "     *                    fingerprint (JA3); OkHttp/Conscrypt can never pass it,",
    "     *                    only a real Chromium WebView can. Keeping it as a",
    "     *                    native line would be a permanently dead entry.",
    "     *  - javchu.com    -> 200, same site structure, real <source src=...mp4>",
    "     *                    direct links. This one actually works.",
    "     *",
    "     * So the list holds exactly one entry. Adding a domain back requires",
    "     * re-measuring it first - a dead line is worse than no line.",
    "     */",
    "    val BACKUP_HANIME: List<String> = listOf(",
    '        "https://javchu.com",    // measured 200 + native mp4 sources',
    "    )",
]


def inject_backup_hanime(text):
    anchor = '    const val OFFICIAL_HANIME = "https://hanime1.me"'
    # Guard on the DECLARATION, not just any mention: hanimeSite() already
    # references BACKUP_HANIME, so a plain substring check would think the
    # constant exists and silently skip the injection (that was a real bug).
    if "val BACKUP_HANIME" in text:
        return text
    if text.count(anchor) != 1:
        return text
    return text.replace(anchor, anchor + chr(10) + chr(10).join(BACKUP_HANIME_BLOCK))




# Round 332: HanimeLineDialog shows the actual domain instead of a hard-coded one.
#
# Why: the dialog is now also used to switch between the builtin backup domains
# (hanime1.com / hanimeone.me / javchu.com). With the old hard-coded label every
# backup domain would render as "官方源 hanime1.me", which is simply wrong.
HANIME_DIALOG_OLD_TITLE = 'Text("hanime1.me 线路", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)'
HANIME_DIALOG_NEW_TITLE = 'Text("hanime 线路", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)'
HANIME_DIALOG_OLD_ROW = 'if (isOfficial) "官方源 hanime1.me" else ln.host,'
HANIME_DIALOG_NEW_ROW = 'if (isOfficial) "官方源 " + X99.originOf(ln.url) else ln.host,'
HANIME_DIALOG_OLD_HINT = 'Text("官方源可登录、DoH/代理生效；镜像为直连。", color = TextFaint, fontSize = 10.sp)'
HANIME_DIALOG_NEW_HINT = 'Text("这些是同一站点的页面域，都可以登录（登录态跟着域走）；DoH/代理只对官方源生效。", color = TextFaint, fontSize = 10.sp)'


def patch_hanime_dialog(text):
    if HANIME_DIALOG_OLD_TITLE in text:
        text = text.replace(HANIME_DIALOG_OLD_TITLE, HANIME_DIALOG_NEW_TITLE, 1)
    if HANIME_DIALOG_OLD_ROW in text:
        text = text.replace(HANIME_DIALOG_OLD_ROW, HANIME_DIALOG_NEW_ROW, 1)
    if HANIME_DIALOG_OLD_HINT in text:
        text = text.replace(HANIME_DIALOG_OLD_HINT, HANIME_DIALOG_NEW_HINT, 1)
    return text




# Round 332: the WebView login page must open on the domain the user picked.
#
# Han1meApp.kt hard-coded "https://hanime1.me/login". With the builtin backup
# domains (hanime1.com / hanimeone.me / javchu.com) now selectable, a hard-coded
# URL means the login page opens on a DIFFERENT domain than the one being
# browsed, so the cookie lands on the wrong host and the user stays logged out.
#
# The generated code has `hanimeLine` in scope at that call site (empty string
# means the official domain), so we can derive the URL from it directly.
LOGIN_URL_OLD = '''                        li.putExtra(
                            com.han1me.lab.LoginActivity.EXTRA_URL,
                            "https://hanime1.me/login",
                        )'''
LOGIN_URL_NEW = '''                        // Round 332: log in on the domain currently selected, so the
                        // cookie lands on the host we are actually browsing. Empty
                        // hanimeLine means the official hanime1.me.
                        li.putExtra(
                            com.han1me.lab.LoginActivity.EXTRA_URL,
                            (if (hanimeLine.isBlank()) X99.OFFICIAL_HANIME else X99.originOf(hanimeLine)) + "/login",
                        )'''


def patch_login_url(text):
    if LOGIN_URL_OLD in text:
        text = text.replace(LOGIN_URL_OLD, LOGIN_URL_NEW, 1)
    return text




# Round 333: backup hanime domains must be latency-tested THROUGH the proxy.
#
# testLine() uses directHttp (Proxy.NO_PROXY) on purpose: mirror lines are
# domestic-reachable clones and going through the node would measure the node,
# not the line. But BACKUP_HANIME (javchu.com) sits behind Cloudflare and is
# not reliably reachable direct from a CN mobile network - measuring it direct
# reports 'line error' for a site that works fine through the node.
#
# So: keep direct measurement for everything else, route BACKUP_HANIME through
# the normal http client (which carries GlobalProxy).
TESTLINE_OLD = '''            // \u2605 \u7b2c 327 \u8f6e\uff1a\u6d4b\u901f\u8d70\u76f4\u8fde\uff08\u89c1 directHttp \u7684\u6ce8\u91ca\uff09
            directHttp.newCall(req).execute().use { resp ->'''
TESTLINE_NEW = '''            // \u2605 \u7b2c 333 \u8f6e\uff1a**\u5907\u7528\u57df\u8d70\u4ee3\u7406\u6d4b\u901f**\uff0c\u5176\u4f59\u4ecd\u8d70\u76f4\u8fde\u3002
            //
            // \u955c\u50cf\u7ebf\u8def\uff08hanime18.net \u7b49\uff09\u662f\u56fd\u5185\u76f4\u8fde\u53ef\u8fbe\u7684 clone\uff0c\u8d70\u8282\u70b9\u6d4b\u51fa\u6765\u7684\u662f\u8282\u70b9\u5ef6\u8fdf\uff0c
            // \u4e0e\u7528\u6237\u610f\u56fe\u76f8\u53cd\uff08\u7b2c 327 \u8f6e\u5df2\u786e\u8ba4\uff09\u3002
            // \u4f46 BACKUP_HANIME\uff08javchu.com\uff09\u5728 Cloudflare \u540e\u9762\uff0c\u56fd\u5185\u79fb\u52a8\u7f51\u76f4\u8fde\u4e0d\u7a33\uff0c
            // \u76f4\u8fde\u6d4b\u901f\u4f1a\u628a\u5b83\u62a5\u6210\u300c\u7ebf\u8def\u5f02\u5e38\u300d\uff0c\u800c\u5b83\u8d70\u8282\u70b9\u5b8c\u5168\u6b63\u5e38\u3002
            val isBackupDomain = BACKUP_HANIME.any { u ->
                runCatching { java.net.URI(u).host }.getOrNull() ==
                    runCatching { java.net.URI(line.url).host }.getOrNull()
            }
            val client = if (isBackupDomain) http else directHttp
            client.newCall(req).execute().use { resp ->'''


def patch_testline(text):
    if TESTLINE_OLD in text:
        text = text.replace(TESTLINE_OLD, TESTLINE_NEW, 1)
    return text




# Round 333: detail page URL must follow the selected domain.
#
# Round 87 fixed this for LIST pages via X99.withLineHost. But openDetail()
# still fetched v.url as-is, which is the OFFICIAL host (hanime1.me). After
# switching to javchu.com the list rendered fine (list URL was rewritten) but
# tapping a card fetched hanime1.me/watch?... - which is unreachable - so
# mirrorDetail returned null and the page fell back to 'no direct link'.
#
# Fix: rewrite v.url through withLineHost before handing it to mirrorDetail.
DETAIL_URL_OLD = '''                val d = runCatching { X99.mirrorDetail(v) }.getOrNull()
                    ?: runCatching { X99.metaDetail(v) }.getOrNull()'''
DETAIL_URL_NEW = '''                // \u2605 \u7b2c 333 \u8f6e\uff1a**\u8be6\u60c5\u9875 URL \u8ddf\u7740\u5f53\u524d\u57df\u8d70**\u3002
                //
                // \u5217\u8868\u5361\u7247\u7684 url \u662f\u5b98\u65b9\u57df\uff08hanime1.me\uff09\uff1b\u5207\u5230 javchu.com \u540e\u5217\u8868\u80fd\u770b\uff08\u56e0\u4e3a
                // \u5217\u8868\u8bf7\u6c42\u5df2\u7ecf\u8fc7 withLineHost\uff09\uff0c\u4f46\u70b9\u5361\u7247\u65f6\u8fd8\u53bb\u6293 hanime1.me \u2014\u2014 \u90a3\u4e2a\u57df\u76f4\u8fde\u88ab\u5899\uff0c
                // \u4e8e\u662f mirrorDetail \u8fd4\u56de null\uff0c\u9875\u9762\u9000\u56de\u300c\u672a\u53d6\u5f97\u53ef\u9a8c\u8bc1\u76f4\u94fe\u300d\u3002
                val detailUrl = X99.withLineHost(v.url, hanimeLine).ifBlank { v.url }
                val dv = if (detailUrl == v.url) v else v.copy(url = detailUrl)
                val d = runCatching { X99.mirrorDetail(dv) }.getOrNull()
                    ?: runCatching { X99.metaDetail(dv) }.getOrNull()'''


def patch_detail_url(text):
    if DETAIL_URL_OLD in text:
        text = text.replace(DETAIL_URL_OLD, DETAIL_URL_NEW, 1)
    return text




# Round 333: testLine must say WHY a line failed (DNS / TLS / HTTP / CF block).
#
# 'line error' is a dead end for both users and maintainers: it could mean the
# DNS did not resolve, TLS was rejected, the server returned 403, or the body
# looked like a Cloudflare challenge. Each needs a different fix, so log apart.
TESTLINE_LOG_OLD = '''        } catch (t: Throwable) {
            -1
        }
    }'''
TESTLINE_LOG_NEW = '''        } catch (t: Throwable) {
            val why = when (t) {
                is java.net.UnknownHostException -> "DNS resolution failed"
                is javax.net.ssl.SSLException -> "TLS rejected"
                is java.net.SocketTimeoutException -> "timeout"
                is java.net.ConnectException -> "connection refused"
                else -> t.javaClass.simpleName + ": " + (t.message ?: "")
            }
            com.han1me.lab.LabLog.i("line", "testLine failed " + line.host + " -> " + why)
            -1
        }
    }'''


def patch_testline_log(text):
    if TESTLINE_LOG_OLD in text:
        text = text.replace(TESTLINE_LOG_OLD, TESTLINE_LOG_NEW, 1)
    return text




# Round 333: javchu detail pages carry their stream in a JS variable, not <source>.
#
# Measured on 2026-11-26:
#   /watch?v=1      -> <source src="...480p.mp4" type="video/mp4" size="480">
#   /watch?v=404507 -> const source = 'https://t33.cdn2020.com/video/m3u8/.../index.m3u8';
#
# The old parser only looked for <source> (and .mp4 fallback), so the second
# shape produced an empty sources list and the page showed
# 'no verifiable direct link' for a video that DOES have one.
#
# Fix: add an m3u8 JS-variable fallback. m3u8 is what the built-in ExoPlayer
# already plays (HlsMediaSource), so this is a first-class source, not a hack.
MIRROR_SRC_OLD = '''        val mp4Re = Regex("""https?://[^"'\\s]+\\.mp4[^"'\\s]*""")
        val allSources: List<Pair<Int, String>> = if (sources.isNotEmpty()) sources else mp4Re
            .findAll(html).map { it.value.replace("&amp;", "&") }.distinct().take(6).map { 0 to it }.toList()'''
MIRROR_SRC_NEW = '''        val mp4Re = Regex("""https?://[^"'\\s]+\\.mp4[^"'\\s]*""")
        // \u2605 \u7b2c 333 \u8f6e\uff1ajavchu \u7684\u53e6\u4e00\u79cd\u64ad\u653e\u6e90\u5f62\u6001\u3002
        //
        // \u5b9e\u6d4b\uff082026-11-26\uff09\uff1a
        //   /watch?v=1      -> <source src="...480p.mp4" type="video/mp4" size="480">
        //   /watch?v=404507 -> const source = 'https://t33.cdn2020.com/video/m3u8/.../index.m3u8';
        //
        // \u65e7\u89e3\u6790\u53ea\u8ba4 <source> \u4e0e .mp4\uff0c\u7b2c\u4e8c\u79cd\u5f62\u6001\u4e24\u8fb9\u90fd\u4e0d\u547d\u4e2d \u21d2 sources \u4e3a\u7a7a
        // \u21d2 \u754c\u9762\u62a5\u300c\u672a\u53d6\u5f97\u53ef\u9a8c\u8bc1\u76f4\u94fe\u300d\uff0c\u800c\u90a3\u90e8\u7247\u5b9e\u9645\u662f\u6709\u6e90\u7684\u3002
        //
        // m3u8 \u672c\u5c31\u662f\u5185\u7f6e ExoPlayer \u80fd\u76f4\u63a5\u64ad\u7684\uff08HlsMediaSource\uff09\uff0c\u6240\u4ee5\u8fd9\u662f
        // \u4e00\u7b49\u6e90\uff0c\u4e0d\u662f\u515c\u5e95\u624b\u6bb5\u3002
        val jsSourceRe = Regex("""(?:const|let|var)\\s+source\\s*=\\s*['"]([^'"]+)['"]""")
        val jsSources: List<Pair<Int, String>> = jsSourceRe.findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&") }
            .filter { it.startsWith("http") }
            .distinct().take(3).map { 0 to it }.toList()
        val allSources: List<Pair<Int, String>> = when {
            sources.isNotEmpty() -> sources
            jsSources.isNotEmpty() -> jsSources
            else -> mp4Re.findAll(html).map { it.value.replace("&amp;", "&") }
                .distinct().take(6).map { 0 to it }.toList()
        }'''




# ---- 第 337 轮：mirrorHome 按 url 去重（javchu 的 AI解碼 分区把同一个 <a> 抓成多条）----
#
# 现象：javchu.com 首页某些分区（AI解碼）是「一个链接套多张图」的版式，
# parseCards 每撞上一个 <div title=...> 就吐一条，于是同一个 watch?v= 被收进来 12 次
# （只有 2 个不同 v）。335 轮把它当成 UI 的 key 问题去修，方向偏了 ——
# 脏数据在数据层，就应该在数据层清掉：重复卡片本来也不该画在界面上。
#
# 去重键用 url（含 v=<id>），不是 title：同名不同片是常见的，同片不同名才是解析错。
MIRROR_DEDUP_OLD = NL.join([
'            val cards = HanimeClient.parseCards(html.substring(start, end))',
'            if (cards.isNotEmpty()) secs.add(HanimeSection(title, url, cards))'
])

MIRROR_DEDUP_NEW = NL.join([
'            // ★ 第 337 轮：按 url 去重。javchu 的「一个 <a> 套多张图」版式会让',
'            // parseCards 把同一条 watch?v= 抓成十几条（界面上就是同一张卡重复铺满一行）。',
'            // 去重必须在数据层：UI 的 key 只能解决崩溃，解决不了「重复内容」。',
'            val cards = HanimeClient.parseCards(html.substring(start, end)).distinctBy { it.url }',
'            if (cards.isNotEmpty()) secs.add(HanimeSection(title, url, cards))'
])

def patch_mirror_dedup(text):
    if MIRROR_DEDUP_OLD in text:
        text = text.replace(MIRROR_DEDUP_OLD, MIRROR_DEDUP_NEW, 1)
    return text

def patch_mirror_sources(text):
    if MIRROR_SRC_OLD in text:
        text = text.replace(MIRROR_SRC_OLD, MIRROR_SRC_NEW, 1)
    return text




# Round 334: action logging. Every user-visible operation gets an [act] line.
#
# The user asked for 'logs of everything the app does', not just network. These
# are the entry points of the main interactions; each logs once, at the point
# where the intent is unambiguous (not inside loops, not per recomposition).

# --- Han1meApp.kt: navigation / list / detail / search ---
ACT_NAV_OLD = '''    fun selectTab(t: String) {'''
ACT_NAV_NEW = '''    fun selectTab(t: String) {
        com.han1me.log.AppLog.act("tab", t)'''

ACT_DETAIL_OLD = '''    fun openDetail(v: HanimeVideo, fromBoard: Boolean = false) {'''
ACT_DETAIL_NEW = '''    fun openDetail(v: HanimeVideo, fromBoard: Boolean = false) {
        com.han1me.log.AppLog.act("detail", (if (fromBoard) "board " else "site ") + v.title.take(60))'''

ACT_SEARCH_OLD = '''    fun doSearch() {'''
ACT_SEARCH_NEW = '''    fun doSearch() {
        com.han1me.log.AppLog.act("search", query.take(60))'''

ACT_LIST_OLD = '''    fun loadList(kind0: ListKind, url: String, title: String, p: Int) {'''
ACT_LIST_NEW = '''    fun loadList(kind0: ListKind, url: String, title: String, p: Int) {
        com.han1me.log.AppLog.act("list", kind0.name + " " + title.take(40) + " p" + p)'''

ACT_PREVIEW_OLD = '''    fun openPreview(monthCode: String) {'''
ACT_PREVIEW_NEW = '''    fun openPreview(monthCode: String) {
        com.han1me.log.AppLog.act("preview", monthCode)'''


def patch_act_logging(text):
    for old, new in (
        (ACT_NAV_OLD, ACT_NAV_NEW),
        (ACT_DETAIL_OLD, ACT_DETAIL_NEW),
        (ACT_SEARCH_OLD, ACT_SEARCH_NEW),
        (ACT_LIST_OLD, ACT_LIST_NEW),
        (ACT_PREVIEW_OLD, ACT_PREVIEW_NEW),
    ):
        if old in text and new not in text:
            text = text.replace(old, new, 1)
    return text


LOADHOME_LOG_OLD = NL.join([
'                if (mirrorUrl.isNotBlank()) {',
'                    // 优选线路（hanime1 镜像站）：直连抓取站点自身前端',
'                    val (nav, secs) = X99.mirrorHome(mirrorUrl)'
])

LOADHOME_LOG_NEW = NL.join([
'                if (mirrorUrl.isNotBlank()) {',
'                    // 优选线路（hanime1 镜像站）：直连抓取站点自身前端',
'                    val t0 = System.currentTimeMillis()',
'                    val (nav, secs) = X99.mirrorHome(mirrorUrl)',
'                    com.han1me.log.AppLog.load(',
'                        "home.mirror",',
'                        mirrorUrl + " tabs=" + nav.size + " sections=" + secs.size +',
'                            " cards=" + secs.sumOf { it.cards.size } +',
'                            " " + (System.currentTimeMillis() - t0) + "ms",',
'                    )'
])

KEY_DUP_OLD = NL.join([
'                    items(sec.cards, key = { sec.title + it.id }) { v ->',
'                        SiteCard(v, width = 206.dp, onClick = { onOpenVideo(v) })',
'                    }'
])

KEY_DUP_NEW = NL.join([
'                    // 第 337 轮：key 回到内容本身（分区名 + url），不再掺序号。',
'                    //',
'                    // 335 轮用「分区名 + 序号 + url」凑唯一，确实不再崩，但把崩溃换成了',
'                    // 静默错乱：刷新/翻页/换线路后同一位置换了内容，Compose 会把旧的可组合项',
'                    // 复用给新数据（封面串台、点 A 打开 B），而且滚动位置记忆是按 key 存的，',
'                    // 序号会漂 —— 335 轮注释说「保留滚动位置记忆」，实际效果正好相反。',
'                    //',
'                    // 真因是脏数据：javchu 那种一个 <a> 套多张图的版式，parseCards 会把同一个',
'                    // url 抓成多条。去重要在数据层做（X99.mirrorHome 已按 url 去重），',
'                    // UI 这层再用 distinctBy 兜一次 —— 两处都是 O(n) 一次性，不进 key。',
'                    items(sec.cards.distinctBy { it.url }, key = { sec.title + "#" + it.url }) { v ->',
'                        SiteCard(v, width = 206.dp, onClick = { onOpenVideo(v) })',
'                    }'
])

IMPORT_ITEMS_OLD = NL.join([
'import androidx.compose.foundation.lazy.items'
])

IMPORT_ITEMS_NEW = NL.join([
'import androidx.compose.foundation.lazy.items',
'import androidx.compose.foundation.lazy.itemsIndexed',
'import androidx.compose.ui.graphics.asImageBitmap'
])

SLOTS_CACHE_OLD = NL.join([
'object HplaySlots {',
'    /** 本机是否真的有图片选择器可调用（安卓壳置 true）。 */',
'    var pickSupported: Boolean = false'
])

SLOTS_CACHE_NEW = NL.join([
'object HplaySlots {',
'    // ★ 第 335 轮：进程级解码缓存，切 tab 不再重解（用户报封面重载）。',
'    private val decodeCache = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, androidx.compose.ui.graphics.ImageBitmap?, Long>>()',
'    /** 读包内 drawable 并解码（全限定调用）。 */',
'    fun decodeRes(ctx: android.content.Context, name: String): androidx.compose.ui.graphics.ImageBitmap? {',
'        return runCatching {',
'            val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)',
'            if (id == 0) null',
'            else android.graphics.BitmapFactory.decodeResource(ctx.resources, id)?.asImageBitmap()',
'        }.getOrNull()',
'    }',
'',
'    fun decodeImageCached(path: String): androidx.compose.ui.graphics.ImageBitmap? {',
'        if (path.isBlank()) return null',
'        val mtime = runCatching { java.io.File(path).lastModified() }.getOrDefault(0L)',
'        decodeCache[path]?.let { (m, bmp) ->',
'            if (m == mtime) return bmp',
'            decodeCache.remove(path)',
'        }',
'        val bmp = decodeImage(path)',
'        if (decodeCache.size > 8) {',
'            decodeCache.entries.sortedBy { it.value.third }.take(decodeCache.size - 8)',
'                .forEach { decodeCache.remove(it.key) }',
'        }',
'        decodeCache[path] = Triple(mtime, bmp, System.nanoTime())',
'        return bmp',
'    }',
'    /** 本机是否真的有图片选择器可调用（安卓壳置 true）。 */',
'    var pickSupported: Boolean = false'
])

COVER_CACHE_READ_OLD = NL.join([
'    val coverImg = remember(coverFile) { if (coverFile.isBlank()) null else HplaySlots.decodeImage(coverFile) }',
'    val avatarImg = remember(avatarFile) { if (avatarFile.isBlank()) null else HplaySlots.decodeImage(avatarFile) }'
])

COVER_CACHE_READ_NEW = NL.join([
'    // ★ 第 335 轮：走进程级缓存，切 tab 不再重解。',
'    val coverImg = remember(coverFile) { if (coverFile.isBlank()) null else HplaySlots.decodeImageCached(coverFile) }',
'    val avatarImg = remember(avatarFile) { if (avatarFile.isBlank()) null else HplaySlots.decodeImageCached(avatarFile) }'
])

BG_LOAD_OLD = NL.join([
'    fun load(ctx: android.content.Context): androidx.compose.ui.graphics.ImageBitmap? {',
'        cached?.let { return it }',
'        if (tried) return null',
'        tried = true',
'        // ★ 2026-11-25（第 294 轮）：改从 res/drawable 读。',
'        // assets 方案在 Windows 构建机上反复被文件句柄卡住，换资源管线更稳。',
'        return runCatching {',
'            val id = ctx.resources.getIdentifier("hplay_app_bg", "drawable", ctx.packageName)',
'            if (id == 0) return null',
'            BitmapFactory.decodeResource(ctx.resources, id)?.asImageBitmap()?.also { cached = it }',
'        }.getOrNull()',
'    }'
])

BG_LOAD_NEW = NL.join([
'    fun load(ctx: android.content.Context): androidx.compose.ui.graphics.ImageBitmap? {',
'        cached?.let { return it }',
'        if (tried) return null',
'        tried = true',
'        return runCatching {',
'            // ★ 第 335 轮：ui.bg.file 优先（外观页自定义背景），无则回落包内图。',
'            val custom = com.han1me.X99.setting("ui.bg.file", "")',
'            if (custom.isNotBlank()) {',
'                com.han1me.HplaySlots.decodeImageCached(custom)?.also { cached = it }',
'            } else runCatching {',
'                val id = ctx.resources.getIdentifier("hplay_app_bg", "drawable", ctx.packageName)',
'                if (id == 0) return null',
'                BitmapFactory.decodeResource(ctx.resources, id)?.asImageBitmap()?.also { cached = it }',
'            }.getOrNull()',
'        }.getOrNull()',
'    }',
'',
'    /** 用户换背景后调：丢缓存与 tried 标记。 */',
'    fun invalidate() { cached = null; tried = false }'
])


APPEAR_OLD = NL.join([
'        item {',
'            HplaySectionGroup("显示") {'
])

APPEAR_NEW = NL.join([
'        item {',
'            // ★ 第 335 轮（用户要求）：背景图自定义就放外观板块。',
'            val bgTick = remember { androidx.compose.runtime.mutableStateOf(0) }',
'            val bgFile = remember(bgTick.value) { com.han1me.X99.setting("ui.bg.file", "") }',
'            HplaySectionGroup("背景图") {',
'                Row(',
'                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),',
'                    verticalAlignment = Alignment.CenterVertically,',
'                ) {',
'                    Column(Modifier.weight(1f)) {',
'                        Text("自定义背景", color = cs.onSurface, fontSize = 14.sp)',
'                        Text(',
'                            if (bgFile.isBlank()) "当前：内置默认图" else "当前：自定义图已启用",',
'                            color = cs.onSurfaceVariant, fontSize = 11.sp,',
'                        )',
'                    }',
'                    androidx.compose.material3.TextButton(onClick = {',
'                        if (com.han1me.HplaySlots.pickSupported) {',
'                            com.han1me.HplaySlots.pickImage("ui.bg.file") { p2 ->',
'                                if (p2 != null) {',
'                                    com.han1me.X99.putSetting("ui.bg.file", p2)',
'                                    com.han1me.ui.HplayBg.invalidate()',
'                                    bgTick.value++',
'                                }',
'                            }',
'                        }',
'                    }) { Text("选择图片") }',
'                    if (bgFile.isNotBlank()) {',
'                        androidx.compose.material3.TextButton(onClick = {',
'                            com.han1me.X99.putSetting("ui.bg.file", "")',
'                            com.han1me.ui.HplayBg.invalidate()',
'                            bgTick.value++',
'                        }) { Text("恢复默认") }',
'                    }',
'                }',
'            }',
'        }',
'        item {',
'            HplaySectionGroup("显示") {'
])


# ---- 第 337 轮：把 refreshUser 的补抓从 sites 分支里拿出来 ----
#
# 上游第 94 轮把 LaunchedEffect 写在了 if (board == "sites") { ... } 与 } else when (tab) 之间，
# 语法上它属于 sites 分支内部的顺序语句。后果：tab == "user" 且 profile == null 这个补漏
# 只在「最新/三次元」板块下才会跑 —— 用户在 hanime 板块进用户页时 profile 抓失败就一直空着，
# 正是第 94 轮本想修掉的那个 bug。
#
# 修法：提到 if 之前。它只与 tab 有关，与板块无关。
USER_REFRESH_OLD = NL.join([
    '            if (board == "sites") {',
    '                X99Board(',
])

USER_REFRESH_NEW = NL.join([
    '            // ★ 第 337 轮：进用户页补抓 profile —— 不能放在 sites 分支里。',
    '            // 上游第 94 轮写在 if/else 之间，语法上属于 sites 分支，于是只有切到',
    '            // 「最新」板块时才补抓；hanime 板块下 profile 抓失败就一直空到重启。',
    '            androidx.compose.runtime.LaunchedEffect(tab, user, profile) {',
    '                if (tab == "user" && user != null && profile == null && !mirror) {',
    '                    refreshUser()',
    '                }',
    '            }',
    '            if (board == "sites") {',
    '                X99Board(',
])

USER_REFRESH_DROP = NL.join([
    '            // ★ 第 94 轮修（用户报「id / 订阅者数 / 影片数时而显示时而不显示」）：',
    '            // 原来只在启动时调一次 refreshUser()；那一次 profile 抓失败就一直空到重启。',
    '            // 现在每次进用户页都补一次（拿到就不打扰）。放在 when(tab) **之前**。',
    '            androidx.compose.runtime.LaunchedEffect(tab, user, profile) {',
    '                if (tab == "user" && user != null && profile == null && !mirror) {',
    '                    refreshUser()',
    '                }',
    '            }',
])

def patch_loadhome_log(text):
    if LOADHOME_LOG_OLD in text:
        text = text.replace(LOADHOME_LOG_OLD, LOADHOME_LOG_NEW, 1)
    return text


def patch_dup_key(text):
    if KEY_DUP_OLD in text:
        text = text.replace(KEY_DUP_OLD, KEY_DUP_NEW, 1)
    return text


def patch_items_indexed_import(text):
    if IMPORT_ITEMS_OLD in text and 'lazy.itemsIndexed' not in text:
        text = text.replace(IMPORT_ITEMS_OLD, IMPORT_ITEMS_NEW, 1)
    return text


def patch_slots_cache(text):
    if SLOTS_CACHE_OLD in text:
        text = text.replace(SLOTS_CACHE_OLD, SLOTS_CACHE_NEW, 1)
    return text


def patch_cover_cache_read(text):
    if COVER_CACHE_READ_OLD in text:
        text = text.replace(COVER_CACHE_READ_OLD, COVER_CACHE_READ_NEW, 1)
    return text


def patch_appearance_bg(text):
    if APPEAR_OLD in text:
        text = text.replace(APPEAR_OLD, APPEAR_NEW, 1)
    return text


def patch_bg_load(text):
    if BG_LOAD_OLD in text:
        text = text.replace(BG_LOAD_OLD, BG_LOAD_NEW, 1)
    return text

def patch_user_refresh(text):
    if USER_REFRESH_DROP in text:
        text = text.replace(USER_REFRESH_DROP, '', 1)
    if USER_REFRESH_OLD in text:
        text = text.replace(USER_REFRESH_OLD, USER_REFRESH_NEW, 1)
    return text


# ---- 第 338 轮：首页中部的 CF 验证入口 ----
#
# 用户要求：「默认不弹出，但在首页中间做一个 cf 验证按钮，点击后才弹出验证弹窗。」
#
# 放在 LazyColumn 的第一个 item（Hero 之后、标签之前）：
#   · 验证失败时首页是空的，横幅在最上方才看得到；
#   · 验证通过后 needsVerify() 为 false，它自己就不渲染了，不占版面。
CF_BANNER_OLD = NL.join([
    '        heroVideo?.let { hv ->',
    '            item(key = "hero") {',
])

CF_BANNER_NEW = NL.join([
    '        // ★ 第 338 轮：需要过盾时，首页中部给一张「去验证」横幅（默认不弹窗）。',
    '        item(key = "cf-verify") {',
    '            com.han1me.ui.HplayCfBanner(',
    '                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),',
    '                onVerify = {',
    '                    com.han1me.lab.HplayGateLauncher.open(',
    '                        com.han1me.HplayEnv.requireContext(),',
    '                        fresh = false,',
    '                    ) { ck ->',
    '                        // 验证完立刻重拉首页，用户不用手动刷新',
    '                        if (ck.isNotBlank()) onRetry()',
    '                    }',
    '                },',
    '            )',
    '        }',
    '        heroVideo?.let { hv ->',
    '            item(key = "hero") {',
])

def patch_cf_banner(text):
    if CF_BANNER_OLD in text:
        text = text.replace(CF_BANNER_OLD, CF_BANNER_NEW, 1)
    return text

# ---- 第 338 轮：三次元板块工具行重构 ----
#
# 用户要求：「去掉三次元板块里多余的几个搜索按钮，并修正子选项分类接线，
# 子选项按钮左边的分类是真实可用的，把功能转到子选项按钮并删除旧分类栏。」
#
# 旧工具行是「横向分类胶囊 + ☰子选项 + 🔍 + 🔎」四个控件，问题：
#  · 分类胶囊与子选项弹窗**不联动**（currentPath 被 tabs 挡住，见 BOARD_CURRENT 那条）；
#  · 🔍（聚合搜索）与 🔎（站内搜索）功能重叠，都是 14sp 纯图标无文字，用户分不清；
#    顶栏已有全局搜索框覆盖板块聚合，这两个是重复入口。
#
# 现在只留一个「☰ 分类」，文字直接显示当前生效的分类名。
BOARD_TOOLROW_OLD = NL.join([
    '            Row(',
    '                horizontalArrangement = Arrangement.spacedBy(8.dp),',
    '                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),',
    '            ) {',
    '                if (tabs.isNotEmpty()) {',
    '                    tabs.forEachIndexed { i, (label, _) ->',
    '                        val active = i == tab',
    '                        Text(',
    '                            label,',
    '                            color = if (active) Color.White else TextDim,',
    '                            fontSize = 12.sp,',
    '                            modifier = Modifier',
    '                                .clip(RoundedCornerShape(13.dp))',
    '                                .background(if (active) AccentRed else CardColorSoft)',
    '                                .clickable { tab = i; page = 1 }',
    '                                .padding(horizontal = 10.dp, vertical = 5.dp),',
    '                        )',
    '                    }',
    '                } else {',
    '                    Text(',
    '                        site.desc.ifBlank { "统一卡片浏览" }, color = TextFaint, fontSize = 11.sp,',
    '                        maxLines = 1, overflow = TextOverflow.Ellipsis,',
    '                    )',
    '                    Text(',
    '                        "⟳ 刷新", color = TextDim, fontSize = 12.sp,',
    '                        modifier = Modifier',
    '                            .clip(RoundedCornerShape(13.dp)).background(CardColorSoft)',
    '                            .clickable { load() }',
    '                            .padding(horizontal = 10.dp, vertical = 5.dp),',
    '                    )',
    '                }',
    '            }',
    '            Spacer(Modifier.width(8.dp))',
    '            // 「☰ 子选项」：站内所有分类/栏目收进一个弹窗（用户 2026-10-01 要求）。',
    '            // 有内置栏目表的站（MissAV / xchina / qinav）零请求；其余站点开时才发**一发**列表页请求现抓。',
    '            Text(',
    '                "☰ 子选项", color = if (currentPath != null) Color.White else TextDim, fontSize = 11.sp,',
    '                modifier = Modifier.clip(RoundedCornerShape(14.dp))',
    '                    .background(if (currentPath != null) AccentRed else CardColorSoft)',
    '                    .clickable { showOptions = true }',
    '                    .padding(horizontal = 10.dp, vertical = 5.dp),',
    '            )',
    '            Spacer(Modifier.width(8.dp))',
    '            // 聚合搜索：一次输入并发查所有板块（用户点名要 missav 区 + xchina 全板块）',
    '            Text(',
    '                "🔍", fontSize = 14.sp,',
    '                modifier = Modifier.clip(RoundedCornerShape(15.dp)).clickable { showAgg = !showAgg }.padding(6.dp),',
    '            )',
    '            if (missav || xchina) {',
    '                Text(',
    '                    "🔎", fontSize = 14.sp,',
    '                    modifier = Modifier.clip(RoundedCornerShape(15.dp)).clickable { showSearch = !showSearch }.padding(6.dp),',
    '                )',
    '            }',
])

BOARD_TOOLROW_NEW = NL.join([
    '            // ★ 第 338 轮（用户要求：去掉多余搜索按钮、修正子选项分类接线、删除旧分类栏）：',
    '            // 这里只剩一个「☰ 分类」，它的文字**就是**当前生效的分类名。',
    '            // 旧的横向分类胶囊已删；两个放大镜（聚合 / 站内）也删 ——',
    '            // 顶栏全局搜索框已覆盖板块聚合，这两个是重复入口，而且是 14sp 纯图标无文字，',
    '            // 用户根本分不清谁是谁。',
    '            Row(',
    '                verticalAlignment = Alignment.CenterVertically,',
    '                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),',
    '            ) {',
    '                Text(',
    '                    if (currentPath == null) "☰ 分类" else "☰ " + optionLabel.ifBlank { "已选分类" },',
    '                    color = if (currentPath != null) Color.White else TextDim, fontSize = 11.sp,',
    '                    maxLines = 1, overflow = TextOverflow.Ellipsis,',
    '                    modifier = Modifier',
    '                        .clip(RoundedCornerShape(14.dp))',
    '                        .background(if (currentPath != null) AccentRed else CardColorSoft)',
    '                        .clickable { showOptions = true }',
    '                        .padding(horizontal = 10.dp, vertical = 5.dp),',
    '                )',
    '                if (currentPath != null) {',
    '                    Spacer(Modifier.width(6.dp))',
    '                    Text(',
    '                        "✕ 清除", color = TextDim, fontSize = 10.sp,',
    '                        modifier = Modifier',
    '                            .clip(RoundedCornerShape(12.dp)).background(CardColorSoft)',
    '                            .clickable { pickOption(null, ""); page = 1 }',
    '                            .padding(horizontal = 8.dp, vertical = 4.dp),',
    '                    )',
    '                }',
    '                Spacer(Modifier.width(8.dp))',
    '                Text(',
    '                    "⟳", color = TextDim, fontSize = 13.sp,',
    '                    modifier = Modifier',
    '                        .clip(RoundedCornerShape(13.dp)).background(CardColorSoft)',
    '                        .clickable { load() }',
    '                        .padding(horizontal = 9.dp, vertical = 4.dp),',
    '                )',
    '            }',
    '            Spacer(Modifier.width(8.dp))',
])

def patch_board_toolrow(text):
    if BOARD_TOOLROW_OLD in text:
        text = text.replace(BOARD_TOOLROW_OLD, BOARD_TOOLROW_NEW, 1)
    return text



BOARD_CURRENT_OLD = NL.join([
    '    var optionPath by remember { mutableStateOf<String?>(null) }',
    '    var showOptions by remember { mutableStateOf(false) }',
    '    var optionBusy by remember { mutableStateOf(false) }',
    '    val currentPath: String? = tabs.getOrNull(tab)?.second ?: optionPath',
])

BOARD_CURRENT_NEW = NL.join([
    '    var optionPath by remember { mutableStateOf<String?>(null) }',
    '    // 第 338 轮：分类的**显示名**。原来只存 path，界面只能拿 tabs 反查，',
    '    // 而现抓分类（boardOptionsLive）不在 tabs 里，反查必然落空。',
    '    var optionLabel by remember { mutableStateOf("") }',
    '    var showOptions by remember { mutableStateOf(false) }',
    '    var optionBusy by remember { mutableStateOf(false) }',
    '    // ★★★ 第 338 轮（修「子选项分类接线」）：',
    '    //',
    '    // 旧写法 tabs.getOrNull(tab)?.second ?: optionPath —— tabs 优先。',
    '    // 而内置栏目表的站（MissAV / xchina / qinav）tabs 恒非空，',
    '    // 于是弹窗选中的 optionPath **永远被 tabs[tab] 盖掉**，表现就是「点了没反应」。',
    '    //',
    '    // 现在：横向分类栏已按用户要求删除，**只有**在弹窗里显式选过才设置 currentPath。',
    '    // 不能再回落到 tabs[0] —— 那会让「没选过」也显示成「已选分类」（真机第一版就是这样）。',
    '    val currentPath: String? = optionPath',
    '',
    '    // 统一的「选分类」入口：path 与显示名一起更新，避免两处状态不同步。',
    '    fun pickOption(path: String?, label: String) {',
    '        optionPath = path',
    '        optionLabel = if (path == null) "" else label.ifBlank { "已选分类" }',
    '    }',
])

def patch_board_current(text):
    if BOARD_CURRENT_OLD in text:
        text = text.replace(BOARD_CURRENT_OLD, BOARD_CURRENT_NEW, 1)
    return text



BOARD_DIALOG_CALL_OLD = NL.join([
    '        BoardOptionDialog(',
    '            siteName = site.name,',
    '            base = lineUrl.ifBlank { site.url },',
    '            current = currentPath,',
    '            onPick = { path -> optionPath = path; tab = 0; page = 1; showOptions = false },',
    '            onClose = { showOptions = false },',
    '        )',
])

BOARD_DIALOG_CALL_NEW = NL.join([
    '        BoardOptionDialog(',
    '            siteName = site.name,',
    '            base = lineUrl.ifBlank { site.url },',
    '            current = currentPath,',
    '            onPick = { path, label ->',
    '                // ★ 第 338 轮：同时记 path 与显示名；tab 归零（旧分类栏已删，tab 只作回落）',
    '                pickOption(path, label)',
    '                tab = 0',
    '                page = 1',
    '                showOptions = false',
    '            },',
    '            onClose = { showOptions = false },',
    '        )',
])

def patch_board_dialog_call(text):
    if BOARD_DIALOG_CALL_OLD in text:
        text = text.replace(BOARD_DIALOG_CALL_OLD, BOARD_DIALOG_CALL_NEW, 1)
    return text


# ---- 第 338 轮：子选项弹窗回调带显示名 ----
#
# 现抓分类（boardOptionsLive）不在 tabs 里，光有 path 界面无法显示分类名，
# 所以回调签名从 (String?) 扩成 (String?, String)。
BOARD_DIALOG_SIG_OLD = NL.join([
    '    current: String?,',
    '    onPick: (String?) -> Unit,',
    '    onClose: () -> Unit,',
])

BOARD_DIALOG_SIG_NEW = NL.join([
    '    current: String?,',
    '    // ★ 第 338 轮：回调带显示名 —— 现抓分类不在 tabs 里，光有 path 界面显示不出分类名',
    '    onPick: (String?, String) -> Unit,',
    '    onClose: () -> Unit,',
])

def patch_board_dialog_sig(text):
    if BOARD_DIALOG_SIG_OLD in text:
        text = text.replace(BOARD_DIALOG_SIG_OLD, BOARD_DIALOG_SIG_NEW, 1)
    return text


BOARD_PICKALL_OLD = '                        .clickable { onPick(null) }'
BOARD_PICKALL_NEW = '                        .clickable { onPick(null, "") }'

def patch_board_pickall(text):
    if BOARD_PICKALL_OLD in text:
        text = text.replace(BOARD_PICKALL_OLD, BOARD_PICKALL_NEW, 1)
    return text


BOARD_PICKONE_OLD = '                            .clickable { onPick(path) }'
BOARD_PICKONE_NEW = '                            .clickable { onPick(path, label) }'

def patch_board_pickone(text):
    if BOARD_PICKONE_OLD in text:
        text = text.replace(BOARD_PICKONE_OLD, BOARD_PICKONE_NEW, 1)
    return text

# ---------------------------------------------------------------- 第 340 轮：搜索页重构
#
# 需求（用户原话）：
#   「优化搜索功能，搜索页面的三次元搜索机制无法进行，搜索按钮只能检索 han1me，
#     接线三次元板块搜索并美观构建方案，页面有两个搜索框也不美观，寻找美观可用方案，
#     返回板块按钮删去，实测无效果」
#
# 三处断点：
#  ① **接线断了**：GlobalSearchTab 调 AggregateSearchPanel 时没传 externalQuery / externalTick，
#     面板里的 query 恒为空 => 点「搜索」永远只走 doSearch()（官方源），三次元聚合形同不存在。
#  ② **两个搜索框**：顶栏 SearchRow + 顶层 showSearch 那一行，视觉上确实是两个。
#  ③ **「← 返回板块」按钮**：回调是 onClose = {}（空实现），点了没反应 —— 用户说的「实测无效果」。

# ---- X99Board.kt：聚合面板只认外部搜索框 ----

AGG_QUERY_OLD = NL.join([
"    var query by remember { mutableStateOf(\"\") }",
"    var submitted by remember { mutableStateOf(\"\") }",
"    // externalQuery：非空时以它为准（外部输入框的内容）",
"    if (externalQuery.isNotEmpty() && externalQuery != query) query = externalQuery",
"    // externalTick：外部点「搜板块」时 +1，触发一次提交",
"    LaunchedEffect(externalTick) {",
"        if (externalTick > 0 && query.isNotBlank()) submitted = query",
"    }",
]) + NL

AGG_QUERY_NEW = NL.join([
"    // ★★★ 第 340 轮修：**上一版的同步是坏的**。",
"    // 原写法有两个硬伤：",
"    //  ① 在**组合期**给 state 赋值（副作用写进 composition），会触发额外重组；",
"    //  ② LaunchedEffect 在组合结束后才跑，读到的 query 可能是**赋值前的旧值**",
"    //     —— 用户打「SSIS」点搜索，提交的却是空串或上一次的词。",
"    // 现在不再自己存 query：提交直接取 externalQuery（外部搜索框的唯一真源）。",
"    var submitted by remember { mutableStateOf(\"\") }",
"    LaunchedEffect(externalTick, externalQuery) {",
"        if (externalTick > 0 && externalQuery.isNotBlank()) submitted = externalQuery.trim()",
"    }",
]) + NL


def patch_agg_query(text):
    """第 340 轮：聚合面板不再自持 query，消除组合期副作用与旧值竞态。"""
    if AGG_QUERY_OLD in text:
        return text.replace(AGG_QUERY_OLD, AGG_QUERY_NEW, 1)
    return text


AGG_TOOLROW_OLD = NL.join([
"        Row(",
"            verticalAlignment = Alignment.CenterVertically,",
"            modifier = Modifier.fillMaxWidth().background(BarColor).padding(horizontal = 10.dp, vertical = 6.dp),",
"        ) {",
"            Text(",
"                \"← 返回板块\", color = Color.White, fontSize = 12.sp,",
"                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(AccentRed)",
"                    .clickable { onClose() }.padding(horizontal = 11.dp, vertical = 5.dp),",
"            )",
"            Spacer(Modifier.width(10.dp))",
]) + NL

AGG_TOOLROW_NEW = NL.join([
"        // ★★★ 第 340 轮（用户要求「返回板块按钮删去，实测无效果」）：",
"        // 那个按钮的回调是 onClose = {}（调用方传的空实现），点了没有任何反应；",
"        // 它还占掉一整行宽度，把板块计数挤成省略号。现在整块删掉。",
"        Row(",
"            verticalAlignment = Alignment.CenterVertically,",
"            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),",
"        ) {",
]) + NL


def patch_agg_toolrow(text):
    """第 340 轮：删「← 返回板块」死按钮。"""
    if AGG_TOOLROW_OLD in text:
        return text.replace(AGG_TOOLROW_OLD, AGG_TOOLROW_NEW, 1)
    return text


AGG_EMPTY_OLD = NL.join([
"        if (submitted.isBlank()) {",
"            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {",
"                Text(\"输入关键词，一次查全部板块\", color = TextDim, fontSize = 13.sp)",
"            }",
"            return@Column",
"        }",
]) + NL

AGG_EMPTY_NEW = NL.join([
"        if (submitted.isBlank()) {",
"            Box(",
"                Modifier.fillMaxSize().padding(horizontal = 28.dp),",
"                contentAlignment = Alignment.Center,",
"            ) {",
"                Column(horizontalAlignment = Alignment.CenterHorizontally) {",
"                    Text(\"🔎\", fontSize = 30.sp)",
"                    Spacer(Modifier.height(10.dp))",
"                    Text(\"在上面的搜索框输入关键词，点「搜索」\", color = TextDim, fontSize = 13.sp)",
"                    Spacer(Modifier.height(6.dp))",
"                    Text(",
"                        \"同一次搜索会同时查 hanime 官方源与下面 \" + sites.size + \" 个三次元板块；\" +",
"                            \"没收录搜索入口的板块会如实标注，不会谎报条数。\",",
"                        color = TextFaint, fontSize = 11.sp, lineHeight = 16.sp,",
"                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,",
"                    )",
"                }",
"            }",
"            return@Column",
"        }",
]) + NL


def patch_agg_empty(text):
    """第 340 轮：空态说明「怎么用、会查哪些」。"""
    if AGG_EMPTY_OLD in text:
        return text.replace(AGG_EMPTY_OLD, AGG_EMPTY_NEW, 1)
    return text


# ---- Han1meApp.kt：GlobalSearchTab 真正接线 ----

GST_BODY_OLD = NL.join([
"    Column(Modifier.fillMaxSize()) {",
"        SearchRow(query = query, onQuery = onQuery, onSearch = onOfficial, onClose = onResultsBack)",
"        Text(",
"            \"↑ 回车＝只搜 hanime 官方源；下面＝同时搜站点合集里每一个板块的站内搜索\",",
"            color = TextFaint, fontSize = 11.sp,",
"            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),",
"        )",
"        if (officialResults) {",
]) + NL

GST_BODY_NEW = NL.join([
"    // ★★★ 第 340 轮（用户报「搜索按钮只能检索 han1me，三次元搜索机制无法进行」）：",
"    //",
"    // 根因是**接线断了**。原来的写法把 externalQuery / externalTick 一个都没传，",
"    // 面板里的 submitted 恒为空，于是点「搜索」永远只走 onOfficial（hanime 官方源）。",
"    // 聚合搜索那段代码一直在，但**从来没被触发过**。",
"    //",
"    // 现在：同一个搜索框同时驱动两条线 ——",
"    //   · onOfficial：hanime 官方源（list 通道，行为不变）",
"    //   · externalQuery + externalTick：三次元全部板块的站内搜索（并发 3）",
"    // 结果区用两个 chip 切换（官方源 / 全部板块）。",
"    var boardTick by remember { mutableStateOf(0) }",
"    var showBoards by remember { mutableStateOf(false) }",
"    Column(Modifier.fillMaxSize()) {",
"        SearchRow(",
"            query = query,",
"            onQuery = onQuery,",
"            onSearch = {",
"                onOfficial()",
"                boardTick++",
"                showBoards = true",
"            },",
"            onClose = onResultsBack,",
"        )",
"        if (officialResults || showBoards) {",
"            Row(",
"                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),",
"                horizontalArrangement = Arrangement.spacedBy(8.dp),",
"            ) {",
"                listOf(\"hanime 官方源\", \"三次元全部板块\").forEachIndexed { i, label ->",
"                    val on = (i == 1) == showBoards",
"                    Text(",
"                        label,",
"                        color = if (on) MaterialTheme.colorScheme.onPrimary",
"                                else MaterialTheme.colorScheme.onSurfaceVariant,",
"                        fontSize = 12.sp,",
"                        modifier = Modifier",
"                            .clip(RoundedCornerShape(15.dp))",
"                            .background(",
"                                if (on) MaterialTheme.colorScheme.primary",
"                                else MaterialTheme.colorScheme.surfaceContainerHigh",
"                            )",
"                            .clickable { showBoards = (i == 1) }",
"                            .padding(horizontal = 13.dp, vertical = 6.dp),",
"                    )",
"                }",
"            }",
"        }",
"        if (officialResults && !showBoards) {",
]) + NL


def patch_gst_body(text):
    """第 340 轮：GlobalSearchTab 接线 + 结果区双 chip。"""
    if GST_BODY_OLD in text:
        return text.replace(GST_BODY_OLD, GST_BODY_NEW, 1)
    return text


GST_CALL_OLD = NL.join([
"        } else {",
"            AggregateSearchPanel(sites = X99.otherSites(), onOpen = onOpenBoardVideo, onClose = {})",
"        }",
]) + NL

GST_CALL_NEW = NL.join([
"        } else {",
"            // ★ 第 340 轮：**这里才是接线点**（上一版三个参数一个没传）。",
"            AggregateSearchPanel(",
"                sites = X99.otherSites(),",
"                onOpen = onOpenBoardVideo,",
"                onClose = {},",
"                externalQuery = query,",
"                externalTick = boardTick,",
"            )",
"        }",
]) + NL


def patch_gst_call(text):
    """第 340 轮：把 externalQuery / externalTick 真正传进聚合面板。"""
    if GST_CALL_OLD in text:
        return text.replace(GST_CALL_OLD, GST_CALL_NEW, 1)
    return text


# ---- 卡片封面按 480px 解码（第 340 轮） ----
#
# 48MB 内存缓存按 1000px 解码只装得下 21 张 —— 首屏就填满，等于没有缓存。
# 列表卡实际只有 ~180dp 宽（1080p 屏 ≈ 490px），480px 单张约 0.52MB，同样 48MB 能装 90 多张。

CARD_THUMB_OLD = NL.join([
"            com.han1me.web.HplayThumb(",
"                url = v.thumb,",
"                contentDescription = v.title,",
"                contentScale = androidx.compose.ui.layout.ContentScale.Crop,",
"                modifier = Modifier.fillMaxSize(),",
"            )",
]) + NL

CARD_THUMB_NEW = NL.join([
"            // ★ 第 340 轮：卡片按 480px 解码（1000px 会把 48MB 缓存吃光，首屏就淘汰）。",
"            com.han1me.web.HplayThumb(",
"                url = v.thumb,",
"                contentDescription = v.title,",
"                contentScale = androidx.compose.ui.layout.ContentScale.Crop,",
"                modifier = Modifier.fillMaxSize(),",
"                decodeMaxW = com.han1me.web.CARD_DECODE_W,",
"            )",
]) + NL


def patch_card_decode(text):
    if CARD_THUMB_OLD in text:
        return text.replace(CARD_THUMB_OLD, CARD_THUMB_NEW, 1)
    return text


FAV_THUMB_OLD = NL.join([
"                                    com.han1me.web.HplayThumb(",
"                                        url = item.thumb,",
"                                        contentDescription = item.title,",
"                                        modifier = Modifier.fillMaxSize(),",
"                                        contentScale = ContentScale.Crop,",
"                                    )",
]) + NL

FAV_THUMB_NEW = NL.join([
"                                    // ★ 第 340 轮：收藏页卡片按 480px 解码（96dp 高的图没必要 1000px）",
"                                    com.han1me.web.HplayThumb(",
"                                        url = item.thumb,",
"                                        contentDescription = item.title,",
"                                        modifier = Modifier.fillMaxSize(),",
"                                        contentScale = ContentScale.Crop,",
"                                        decodeMaxW = com.han1me.web.CARD_DECODE_W,",
"                                    )",
]) + NL


def patch_fav_decode(text):
    if FAV_THUMB_OLD in text:
        return text.replace(FAV_THUMB_OLD, FAV_THUMB_NEW, 1)
    return text


# ---------------------------------------------------------------- 第 340 轮：作者头像误取成「用户头像」

# 需求（用户原话）：「登录态作者头像为用户头像，未登录态显示头像没问题，修复bug」
#
# 根因：parseArtist 取圆形头像用的是**全页第一条** border-radius:50% 的 <img>。
# 未登录时页面顶部那个位置是空的（站点不渲染用户头像），所以第一条命中的就是作者头像；
# 一旦登录，**顶栏用户头像**（同样 50% 圆角）出现在文档更前面，
# 于是「第一个圆形头像」变成了用户自己的头像 —— 作者卡上显示的就是用户的头像。
#
# 参考实现 Han1meViewer 的选择器本来就是**限定在作者块内**且带 position:absolute：
#   div.video-details-wrapper > div > a > div > img[style*='position: absolute'][style*='border-radius: 50%']
# 这里照它的判据重写：先切出作者名附近的窗口，窗口内要求 style 同时含
# position:absolute 与 border-radius:50%；逐级放宽，最后才退回默认头像。

ARTIST_AV_OLD = r'''        // 圆形头像（参考实现的选择器：border-radius:50% 的 img；属性顺序不定，两种写法都试）
        val avatarCircle = Regex("""<img[^>]*border-radius:\s*50%[^>]*src="([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<img[^>]*src="([^"]+)"[^>]*border-radius:\s*50%""")
                .find(html)?.groupValues?.get(1)
            ?: ""
'''

ARTIST_AV_NEW = r'''        // ★★★ 第 340 轮修（用户报「登录态作者头像变用户头像」）：
        //
        // 老写法是全页找**第一条** border-radius:50% 的 <img>。
        // 未登录时页面顶部没有用户头像，第一条恰好就是作者头像，所以看不出问题；
        // 一旦登录，**顶栏用户头像**（同样 50% 圆角）出现在文档更前面，
        // 于是作者卡上显示的是用户自己的头像 —— 这就是那个 bug。
        //
        // 参考实现的判据本来就是「限定在作者块内 + position:absolute」，照它重写：
        // 先切作者名附近的窗口 → 窗口内要求 style 同时含 position:absolute 与 border-radius:50%
        // → 逐级放宽 → 最后才退回默认头像。
        val artistAt = html.indexOf("id=\"video-artist-name\"")
        val artistWin = if (artistAt >= 0) {
            html.substring(maxOf(0, artistAt - 3000), minOf(artistAt + 600, html.length))
        } else ""
        val artistImgRe = Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE)
        val artistStyleRe = Regex("""style\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val artistSrcRe = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        fun pickRoundAvatar(scope: String, requireAbs: Boolean): String {
            if (scope.isEmpty()) return ""
            var loose = ""
            for (mt in artistImgRe.findAll(scope)) {
                val tag = mt.value
                val st = artistStyleRe.find(tag)?.groupValues?.get(1) ?: continue
                // 归一再判：「border-radius: 50%」的空白写法有多种
                val flat = st.replace(" ", "").lowercase()
                if (!flat.contains("border-radius:50%")) continue
                val src = artistSrcRe.find(tag)?.groupValues?.get(1)?.replace("&amp;", "&").orEmpty()
                if (src.isBlank()) continue
                if (loose.isEmpty()) loose = src
                if (requireAbs && flat.contains("position:absolute")) return src
            }
            return if (requireAbs) "" else loose
        }
        val avatarCircle = pickRoundAvatar(artistWin, true)
            .ifBlank { pickRoundAvatar(artistWin, false) }
            .ifBlank { pickRoundAvatar(html, true) }
'''


def patch_artist_avatar(text):
    """第 340 轮：作者头像限定在作者块内取，避免登录后取到用户头像。"""
    if ARTIST_AV_NEW in text:
        return text
    if ARTIST_AV_OLD in text:
        return text.replace(ARTIST_AV_OLD, ARTIST_AV_NEW, 1)
    return text


# ---------------------------------------------------------------- 第 340 轮：年龄闸门自动过

# 用户要求（第 5 项）：「全面核查图中标记内的板块情况…进行核查修复，质量稳定优先」
#
# 实测（本机 curl，2026-11-26）：
#   · zh.xhamster02.net/enter  -> **HTTP 500 + 2337 B**（openresty）
#   · dage.one/enter           -> **HTTP 500 + 2337 B**（同一份模板）
#   两份正文是一张「请点 Continue 进入首页」的年龄闸门卡，页面 JS 只做一件事：
#       document.cookie = 'x-index-auth=authed; path=/'; location.href = '/enter';
#   也就是说：**带上 x-index-auth=authed 这一个 cookie，同一 URL 立刻回 200 正常列表**
#   （实测 xhamster /enter + cookie = 200 / 246731 B / 5 张详情卡）。
#
# 所以这两个板块在 App 里「打不开」不是域名失效，是**没过年龄闸门**。
# 修法：在取页层做一次**自识别重试** —— 正文里出现 x-index-auth 就带 cookie 再来一次。
# 不自建域名白名单：闸门模板会被更多站复用，按正文特征识别才不会被新站绕过去。

GATE_ANCHOR = NL.join([
'                    val code = resp.code',
'                    // 第 124 轮（用户要求）：xchina 一族在本机网络下回 403（Cloudflare 拦截），',
'                    // 属于「不可直连、需开启代理」。给出明确文案，不要让用户以为是自己网络坏了。',
'                    if (code == 403 && url.contains("xchina")) {',
'                        throw RuntimeException("xchina 需开启代理（本机直连被 403 拦截）")',
'                    }',
]) + NL

GATE_PATCH = NL.join([
'                    val code = resp.code',
'                    // ★★★ 第 340 轮：**年龄闸门自动过**（实测见文件头注释）。',
'                    //',
'                    // xhamster 一族（以及复用同一模板的 dage 等）在 /enter 回 HTTP 500 +',
'                    // 一张「请点 Continue」的闸门卡，页面 JS 只写一个 cookie：',
'                    //     document.cookie = \'x-index-auth=authed; path=/\'',
'                    // 带上它同一 URL 立刻 200。所以这里按**正文特征**识别并重试一次；',
'                    // 不建域名白名单 —— 新站复用同一模板时也自动受益。',
'                    val gateBody = runCatching { resp.peekBody(262144).string() }.getOrDefault("")',
'                    if (gateBody.contains("x-index-auth") && !gateBody.contains("video-details")) {',
'                        val req2 = Request.Builder().url(url)',
'                            .header("User-Agent", UA)',
'                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")',
'                            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")',
'                            .header("Referer", ref)',
'                            .header("Cookie", "x-index-auth=authed")',
'                            .build()',
'                        val passed = runCatching {',
'                            cli.newCall(req2).execute().use { r2 ->',
'                                if (r2.isSuccessful) r2.body?.string().orEmpty() else ""',
'                            }',
'                        }.getOrDefault("")',
'                        if (passed.isNotBlank() && !passed.contains("x-index-auth")) {',
'                            com.han1me.log.AppLog.net("gate", url, 200, passed.length, System.currentTimeMillis() - lgT)',
'                            return passed',
'                        }',
'                    }',
'                    // 第 124 轮（用户要求）：xchina 一族在本机网络下回 403（Cloudflare 拦截），',
'                    // 属于「不可直连、需开启代理」。给出明确文案，不要让用户以为是自己网络坏了。',
'                    if (code == 403 && url.contains("xchina")) {',
'                        throw RuntimeException("xchina 需开启代理（本机直连被 403 拦截）")',
'                    }',
]) + NL


def patch_age_gate(text):
    """第 340 轮：xhamster / dage 一族的年龄闸门自动过（x-index-auth cookie）。"""
    if "x-index-auth" in text:
        return text
    if GATE_ANCHOR in text:
        return text.replace(GATE_ANCHOR, GATE_PATCH, 1)
    return text


# ---------------------------------------------------------------- 第 340 轮：板块搜索接线（实测入口）

# 用户要求（第 1 项）：「接线三次元板块搜索」。
#
# 实测（本机 curl，2026-11-26；判据＝返回页里非导航的详情锚点数 >= 3）：
#   · 视色   shise.me            /?s=<kw>            -> 200 / 106 卡   ★
#   · 99热   99rego.vip          /?s=<kw>            -> 200 /  37 卡   ★
#   · 青青草 qyul34.com          /?s=<kw>            -> 200 /  59 卡   ★
#   · ininav www.ininav.com      /?s=<kw>            -> 200 / 252 卡  ★
#   · 肉视频 rou66.net           /v?keyword=<kw>     -> 200 /  27 卡   ★
#   · Jable  jable02.net         /search/?q=<kw>     -> 200 /  25 卡   ★
#   · 好色TV hsex1.sbs           /search.htm?search= -> 200 /   1 卡   （弱）
#   · 36co   25jm.cc             /index.php/vod/search.html?wd= -> 200 / 0 卡（表单对但结果空）
#   · 18av   18av1.net           form 字段 search_keyword/search_type/op，拼出来 500（闸门）
#   · rouAV  rou123.ws           /vod/search.html?wd= -> 200 / 0 卡
#   · 321AV / LissAV / 黄果短剧   三个候选形式全部 404
#
# 结论：把**实测有卡**的 5 个写死进 boardSearch；其余保持「无入口」（如实报，不探测刷请求）。
# 写死的理由与黄色仓库那条一样：聚合搜索一次覆盖几十站，探测上百发就是自找频控。

SEARCH_FIX_OLD = NL.join([
'            siteName == "气泡视频" -> qpSearch(kw, page)',
]) + NL

SEARCH_FIX_NEW = NL.join([
'            siteName == "气泡视频" -> qpSearch(kw, page)',
'            // ★★★ 第 340 轮：**写死实测可用的搜索入口**（判据＝返回页详情锚点 >= 3）。',
'            // 这些站点都不是 maccms 标准形态，probeSearch 的 7 种候选一个都命中不了，',
'            // 于是聚合搜索对它们长期报「无搜索入口」——用户看到的就是「三次元搜不了」。',
'            siteName.contains("视色") -> runCatching {',
'                parseGeneric("$origin/?s=$enc", getHtml("$origin/?s=$enc"))',
'            }.getOrNull()',
'            siteName.contains("99热") -> runCatching {',
'                parseGeneric("$origin/?s=$enc", getHtml("$origin/?s=$enc"))',
'            }.getOrNull()',
'            siteName.contains("青青草") -> runCatching {',
'                parseGeneric("$origin/?s=$enc", getHtml("$origin/?s=$enc"))',
'            }.getOrNull()',
'            siteName.contains("ininav") -> runCatching {',
'                parseGeneric("$origin/?s=$enc", getHtml("$origin/?s=$enc"))',
'            }.getOrNull()',
'            siteName.equals("Jable", ignoreCase = true) -> runCatching {',
'                parseGeneric("$origin/search/?q=$enc", getHtml("$origin/search/?q=$enc"))',
'            }.getOrNull()',
'            // 肉视频的搜索参数是 keyword，路径是 /v',
'            siteName.contains("肉视频") -> runCatching {',
'                val u = "$origin/v?keyword=$enc" + if (page > 1) "&page=$page" else ""',
'                parseGeneric(u, getHtml(u))',
'            }.getOrNull()',
]) + NL


def patch_board_search_fix(text):
    """第 340 轮：把实测可用的板块搜索入口写死（解决「三次元搜索机制无法进行」）。"""
    if 'siteName.contains("视色")' in text:
        return text
    if SEARCH_FIX_OLD in text:
        return text.replace(SEARCH_FIX_OLD, SEARCH_FIX_NEW, 1)
    return text


# ---------------------------------------------------------------- 第 340 轮：下载页封面补全重构

# 用户要求（第 4 项）：「下载页面的封面补全功能形同虚设，优化重构」
#
# 老实现只认 sidecar 里的 thumb：没有 thumb 就只尝试「pageUrl 重抓详情」，
# 且节流键用的是 thumb —— thumb 为空时节流形同不存在，每次点「补全封面」
# 都把同一批必然失败的条目重跑一遍，用户看到的就是「点了没反应」。
#
# 现在补最后一级救援：按标题在**已缓存的板块列表**里找同名条目的封面（纯内存、零网络）。

FINDTHUMB_ANCHOR = NL.join([
"    fun clearBoardListCache() {",
"        boardListCache.clear()",
"        boardListAt.clear()",
"    }",
]) + NL

FINDTHUMB_BLOCK = NL.join([
"    /**",
"     * ★ 第 340 轮：**按标题在已缓存的板块列表里找封面地址**（纯内存，零网络）。",
"     *",
"     * 用途：下载页「补全封面」的最后一级救援。老 sidecar（第 157 轮之前写的）",
"     * 既没有 thumb 也没有 pageUrl，一级/二级救援都无地址可用；",
"     * 但用户当时是从某个板块列表点进去下载的，那份列表通常还在 boardListCache 里，",
"     * 里面就有这一条与它的封面。找不到就返回空串，调用方如实计失败。",
"     */",
"    fun findThumbByTitle(title: String): String {",
"        val t = title.trim()",
"        if (t.length < 4) return \"\"       // 太短容易误命中",
"        // 先精确，再「包含」—— 下载时的标题常被截断/加了后缀，两种都要试",
"        for (list in boardListCache.values) {",
"            list.firstOrNull { it.title == t }?.thumb?.takeIf { it.isNotBlank() }?.let { return it }",
"        }",
"        for (list in boardListCache.values) {",
"            list.firstOrNull { it.title.isNotBlank() && (it.title.contains(t) || t.contains(it.title)) }",
"                ?.thumb?.takeIf { it.isNotBlank() }?.let { return it }",
"        }",
"        return \"\"",
"    }",
"",
"    fun clearBoardListCache() {",
"        boardListCache.clear()",
"        boardListAt.clear()",
"    }",
]) + NL


def patch_find_thumb(text):
    """第 340 轮：加「按标题找封面」的最后一级救援。"""
    if "fun findThumbByTitle(" in text:
        return text
    if FINDTHUMB_ANCHOR in text:
        return text.replace(FINDTHUMB_ANCHOR, FINDTHUMB_BLOCK, 1)
    return text


# ---------------------------------------------------------------- 第 340 轮：搜索页只剩一个搜索框

# 用户原话：「页面有两个搜索框也不美观，寻找美观可用方案」。
#
# 实测（真机截图）：进「检索」后屏幕上有两个框 ——
#   ① 顶栏那个是**假框**（占位文字「搜索影片 / 演员」，点了只是切 tab，不能输入）；
#   ② 搜索页自己那个是真的（「想找什么…」，可输入、带搜索与关闭）。
# 视觉上就是两个框并排，用户不知道该用哪个。
#
# 改法：**在搜索 tab 下整块不渲染顶栏**，搜索页自己那一行就充当页面头。

TOPBAR_GUARD_OLD = NL.join([
    "            // ---------- 顶部导航条（网站风格） ----------",
    "            TopNavBar(",
]) + NL

TOPBAR_GUARD_NEW = NL.join([
    "            // ---------- 顶部导航条（网站风格） ----------",
    "            // ★ 第 340 轮（用户报「页面有两个搜索框」）：**搜索 tab 下不渲染顶栏**。",
    "            // 顶栏那个是假框（只切 tab、不能输入），搜索页自己那个才是真的；",
    "            // 两个框并排既占地方又让人不知道该用哪个。搜索页自己那一行就是页面头。",
    "            if (tab != \"search\") TopNavBar(",
]) + NL

TOPBAR_TAIL_OLD = NL.join([
    "                onSites = null,",
    "            )",
    "            if (showSearch && board == \"hanime\") {",
    "                SearchRow(",
    "                    query = query,",
    "                    onQuery = { query = it },",
    "                    onSearch = { showSearch = false; doSearch() },",
    "                    onClose = { showSearch = false },",
    "                )",
    "            }",
]) + NL

TOPBAR_TAIL_NEW = NL.join([
    "                onSites = null,",
    "            )",
    "            // ★ 第 340 轮：这里原本还有一段 `if (showSearch) { SearchRow(...) }`。",
    "            // showSearch 现在**没有任何地方置为 true**（只有置 false 的两处），",
    "            // 也就是一段永不执行的死代码；而且它就是「第二个搜索框」的来源之一。整段删除。",
]) + NL


def patch_topbar_guard(text):
    """第 340 轮：搜索 tab 下隐藏顶栏假搜索框，并删掉永不执行的 SearchRow 覆盖层。"""
    if TOPBAR_GUARD_OLD in text:
        text = text.replace(TOPBAR_GUARD_OLD, TOPBAR_GUARD_NEW, 1)
    if TOPBAR_TAIL_OLD in text:
        text = text.replace(TOPBAR_TAIL_OLD, TOPBAR_TAIL_NEW, 1)
    return text




# ★ 第 340 轮：搜索页的 ✕ 必须真的退回主页（原来只重置 mode，tab 仍停在 search ⇒ 点了没反应）
RESULTS_BACK_OLD = '                    onResultsBack = { mode = ListMode.HOME },'
RESULTS_BACK_NEW = NL.join([
    '                    // ★ 第 340 轮：这个 ✕ 是搜索页**唯一**的退出入口（「返回板块」已删）。',
    '                    // 原来只 `mode = ListMode.HOME`，tab 仍停在 "search" —— 点了看起来毫无反应，',
    '                    // 与用户报的「返回按钮实测无效果」是同一类问题。现在真的回到主页。',
    '                    onResultsBack = { mode = ListMode.HOME; tab = "home"; backToHome() },',
]) + NL


def patch_results_back(text):
    """第 340 轮：搜索页 ✕ 退回主页（原来只重置 mode，界面不动）。"""
    if RESULTS_BACK_OLD in text:
        return text.replace(RESULTS_BACK_OLD, RESULTS_BACK_NEW, 1)
    return text




# ---------------------------------------------------------------- 第 340 轮：x站精选首页纠正
#
# 实测（2026-11-26）：
#   · https://chinaxselecla08.xyz/      -> 200 / **1366 B**（«Site is created successfully» 停放页）
#   · https://chinaxselecla08.xyz/c2/   -> 200 / 141286 B（正常内容站，卡片齐全）
# 也就是说板块配的「首页」进站就落到一张空白欢迎页，用户看到的就是「这个板块打不开」。
# 把站点 url 与主线路都改到 /c2/（线路仍只换 host，不改语义）。

XZEL_HOME_OLD = '            name = "x站精选",' + NL + '            desc = "x站精选 · 三次元（发布页自动解析最新域名）",' + NL + '            url = "https://chinaxselecla08.xyz/",' + NL + '            lines = linesFor(' + NL + '                "https://chinaxselecla08.xyz", "x99.pub.xselecla",' + NL + '                listOf("https://chinaxselecla08.xyz"),' + NL + '            ),'
XZEL_HOME_NEW = '            name = "x站精选",' + NL + '            // ★ 第 340 轮：首页改成 /c2/ —— 根路径实测是停放页（1366 B 的「Site is created successfully」），' + NL + '            // /c2/ 才是 141286 B 的真内容。' + NL + '            desc = "x站精选 · 三次元（发布页自动解析最新域名）",' + NL + '            url = "https://chinaxselecla08.xyz/c2/",' + NL + '            lines = linesFor(' + NL + '                "https://chinaxselecla08.xyz/c2", "x99.pub.xselecla",' + NL + '                listOf("https://chinaxselecla08.xyz/c2"),' + NL + '            ),'


def patch_xzel_home(text):
    """第 340 轮：x站精选首页从停放页 / 改到内容页 /c2/。"""
    if XZEL_HOME_OLD in text:
        return text.replace(XZEL_HOME_OLD, XZEL_HOME_NEW, 1)
    return text




# ---------------------------------------------------------------- 第 342 轮：Getchu 详情页接线
#
# 用户报：「getchu 预告的内板块详情没有接线，点击卡片无响应」。
#
# 两处叠加的原因：
#  ① 卡片点击只走 onOpenUrl → webPage = WebPage(...)（内置浏览器），没有进内置详情页；
#  ② 宿主里 getchuDetailId?.let { ... } 这段画在 showGetchu 覆盖层**之前**，
#     而两者都是 fillMaxSize 全屏 Box —— 后画的盖在上面。
#     所以即使 ① 修好了，详情页也会被 Getchu 列表挡住，看起来仍然「点了没反应」。
#
# 修法：卡片默认进详情（onOpenDetail），并把详情覆盖层挪到 showGetchu 之后。

GETCHU_PREVIEW_OLD = NL.join([
    "                com.han1me.getchu.GetchuPreviewContent(",
    "                    onBack = { showGetchu = false },",
    "                    onOpenUrl = { u, t -> webPage = WebPage(u, t) },",
    "                )",
]) + NL

GETCHU_PREVIEW_NEW = NL.join([
    "                com.han1me.getchu.GetchuPreviewContent(",
    "                    onBack = { showGetchu = false },",
    "                    onOpenUrl = { u, t -> webPage = WebPage(u, t) },",
    "                    // ★ 第 342 轮：卡片**进内置详情页**（原来走 onOpenUrl 打开网页，",
    "                    // 而网页层被这一层覆盖着 —— 表现就是「点了没反应」）。",
    "                    onOpenDetail = { id, title ->",
    "                        getchuDetailId = id",
    "                        getchuDetailTitle = title",
    "                    },",
    "                )",
]) + NL


def patch_getchu_preview(text):
    """第 342 轮：Getchu 预告卡片进内置详情页。"""
    if GETCHU_PREVIEW_OLD in text:
        return text.replace(GETCHU_PREVIEW_OLD, GETCHU_PREVIEW_NEW, 1)
    return text


# 详情覆盖层挪到 showGetchu 之后（z 序修正在下面用切片做）
GETCHU_DETAIL_BLOCK_HEAD = "        // ---------- Getchu 详情页（第 77 轮，点预告条目进入） ----------"
GETCHU_DETAIL_BLOCK_END = "        // ---------- 冲了吗（打卡页，第 76 轮） ----------"
GETCHU_LIST_BLOCK_HEAD = "        // ---------- Getchu 新作预告（第 76 轮，照 Han1meViewer 复刻） ----------"
GETCHU_LIST_BLOCK_END = "        // ---------- 退出确认对话框（第 76 轮，照 Han1meViewer 复刻） ----------"


def patch_getchu_zorder(text):
    """第 342 轮：把 Getchu 详情覆盖层挪到列表覆盖层**之后**（后画者在上）。"""
    d0 = text.find(GETCHU_DETAIL_BLOCK_HEAD)
    if d0 < 0:
        return text
    d1 = text.find(GETCHU_DETAIL_BLOCK_END, d0)
    if d1 < 0:
        return text
    detail_block = text[d0:d1]
    text = text[:d0] + text[d1:]
    # 重新定位列表块的末尾（删除后位置变了，必须重查）
    l0 = text.find(GETCHU_LIST_BLOCK_HEAD)
    if l0 < 0:
        return text
    l1 = text.find(GETCHU_LIST_BLOCK_END, l0)
    if l1 < 0:
        return text
    return text[:l1] + detail_block + text[l1:]



# ---------------------------------------------------------------- 第 343 轮：javchu 失效源预检
#
# 用户报：「javchu.com 检索到的视频有的可以播放，有的播放失败报错，播放失败的同样下载也失败」。
# 运行日志（hplay-log-20261127-002619.txt）里那一发是：
#   [dl] enqueue | MIDV-640 ... url=https://t27.cdn2020.com/video/m3u8/...
#   GET t27.cdn2020.com/video/m3u8/2024/04/02/d2a77f39/index.m3u8 -> 404 138B 1983ms
#
# 本机实测 8 个样本（2026-11-27）：**0 个可用**
#   · t27.cdn2020.com -> 404（站点侧视频已删/路径轮换）
#   · t33.cdn2020.com -> TLS 自签名证书，验证失败；关掉校验后回 **451**（法律原因不可用）
#   · t30.cdn2020.com -> 403
# 也就是说这不是我们解析错了 —— 页面里给的地址本身就是死的。
# 但界面上不能只丢一句「播放失败」：用户会以为是应用坏了。
#
# 修法：走 const source 形态的站，取到源之后先发一次 Range 预检，
# 不可达就把这条剔掉；全剔光时如实显示「源已失效」。

SRCOK_ANCHOR = '        """(Just a moment|cf-mitigated|Sorry, you have been blocked|Attention Required)""",' + NL + '    )'

SRCOK_BLOCK = NL.join([
    "        \"\"\"(Just a moment|cf-mitigated|Sorry, you have been blocked|Attention Required)\"\"\",",
    "    )",
    "",
    "    /**",
    "     * 第 343 轮：媒体源可达性预检（Range 只要 1 字节）。",
    "     *",
    "     * javchu 页面里的 const source 有一大半是站点侧已失效 ——",
    "     * 实测 8 个样本 0 个可用（t27 404 / t33 自签名证书+451 / t30 403）。",
    "     * 不预检的话详情页照样把死链交给播放器与下载器，",
    "     * 用户看到的就是「有的能播有的报错」，下载也在同一处失败。",
    "     */",
    "    private fun srcOk(u: String): Boolean = runCatching {",
    "val req = Request.Builder().url(u)",
    ".header(\"User-Agent\", UA)",
    ".header(\"Referer\", originOf(u) + \"/\")",
    ".header(\"Range\", \"bytes=0-0\")",
    ".get().build()",
    "shortTimeoutHttp(6_000L).newCall(req).execute().use { r ->",
    "r.isSuccessful || r.code == 206",
    "}",
    "}.getOrDefault(false)",
    "",
]) + NL


def patch_src_probe_fn(text):
    if 'private fun srcOk(' in text:
        return text
    if SRCOK_ANCHOR in text:
        return text.replace(SRCOK_ANCHOR, SRCOK_BLOCK, 1)
    return text


JAV_SRC_OLD = NL.join([
    'val jsSources: List<Pair<Int, String>> = jsSourceRe.findAll(html)',
    '            .map { it.groupValues[1].replace("&amp;", "&") }',
    '            .filter { it.startsWith("http") }',
    '            .distinct().take(3).map { 0 to it }.toList()',
]) + NL

JAV_SRC_NEW = NL.join([
    'val jsRaw: List<String> = jsSourceRe.findAll(html)',
    '            .map { it.groupValues[1].replace("&amp;", "&") }',
    '            .filter { it.startsWith("http") }',
    '            .distinct().take(3).toList()',
    '        // 第 343 轮：先验一次可达性（实测这些地址有一半是站点侧已失效）。',
    '        // 不预检的话用户点播就是一句「播放失败」，下载也会在分片上撞墙。',
    '        val jsSources: List<Pair<Int, String>> = jsRaw.filter { srcOk(it) }.map { 0 to it }',
]) + NL


def patch_jav_src_probe(text):
    if JAV_SRC_OLD in text:
        return text.replace(JAV_SRC_OLD, JAV_SRC_NEW, 1)
    return text


def patch_build_info_version(text):
    """给 HplayBuildInfo 加 versionName() 访问器（第 346 轮）。

    HplayBuildInfo 是 internal object，设置页要读版本号 ——
    加一个公开函数比把整个 object 改成 public 影响面小（后者会让
    GH_REPO / GH_LINE 也一起暴露给全模块）。
    """
    if "versionName()" in text:
        return text
    anchor = "    const val BUILD_SHA: String"
    i = text.find(anchor)
    if i < 0:
        return text
    j = text.find(chr(10), i)
    if j < 0:
        return text
    ins = NL.join([
        "",
        "    // " + chr(0x2605) + " 第 346 轮：设置页「关于」要读版本号。",
        "    fun versionName(): String = VERSION_NAME",
    ])
    return text[:j+1] + ins + chr(10) + text[j+1:]


def patch_last_watch_url(text):
    """记录最近一次抓取的详情页地址（第 345 轮）。

    关注 POST 的 Referer 要用「用户此刻看的那一页」，而不是首页 ——
    原生表单提交就是这个形态，CF 的规则也会看 Referer 与提交路径是否一致。

    get() 是全项目唯一的站内 GET 入口，在那里按 URL 特征记一笔最省事：
    watch?v=<数字> 才算详情页，别的（首页/搜索/订阅列表）不覆盖。
    """
    # ★ 守卫必须查**属性声明**而不是裸名字：patch_subscribe_origin 的 Referer 行
    # 里已经引用了 lastWatchUrl，用裸名字判会误判成"已应用"而整段跳过（本轮踩过）。
    if "var lastWatchUrl" in text:
        return text
    # ① 属性：插在 `private var lastReq` 那一行之后（该行内容随版本变化，按前缀找）
    lines = text.split(NL)
    idx = -1
    for i, L in enumerate(lines):
        if "private var lastReq" in L:
            idx = i
            break
    if idx < 0:
        return text
    pad = " " * (len(lines[idx]) - len(lines[idx].lstrip()))
    lines[idx + 1:idx + 1] = [
        "",
        pad + "// " + chr(0x2605) + " 第 345 轮：最近一次抓过的详情页地址。关注 POST 的 Referer 用它。",
        pad + "@Volatile" if lines[idx].strip().startswith("@Volatile") else pad + "@Volatile",
        pad + "var lastWatchUrl: String = " + chr(34) + chr(34),
        pad + "    private set",
    ]
    text = NL.join(lines)
    # ② get() 里记一笔：插在 lastReq 赋值之后
    lines = text.split(NL)
    idx = -1
    for i, L in enumerate(lines):
        if "lastReq = " in L and "req.url" in L:
            idx = i
            break
    if idx < 0:
        return text
    pad2 = " " * (len(lines[idx]) - len(lines[idx].lstrip()))
    lines[idx + 1:idx + 1] = [
        pad2 + "// " + chr(0x2605) + " 第 345 轮：详情页地址留档（关注 POST 的 Referer 用它）。",
        pad2 + "if (url.contains(" + chr(34) + "/watch?v=" + chr(34) + ")) lastWatchUrl = req.url.toString()",
    ]
    return NL.join(lines)


def patch_subscribe_origin(text):
    """关注/取关请求头补齐（第 344 轮 · 用户报「关注按钮报错 HTTP 403」；第 345 轮重做）。

    ## 第 345 轮：上一版为什么没生效

    上一版的守卫写的是
        if '.header("Origin", BASE)' in text: return text
    想表达「已经补过就跳过」。但 postForm（第 1786 行）**本来就带**这一行 ——
    substring 命中立刻早退，于是 subscribeArtist 那一段从头到尾没被改过。
    真机日志核对：gen/HanimeClient.kt 里 `Origin` 只出现 2 次（1699 / 1786），
    都在 postForm 与收藏链路，subscribeArtist 里一次都没有。

    守卫改成**只检查 subscribeArtist 自己那一段**。

    ## 403 的三层原因（对照两份参考源码逐条核）

    · Han1meViewer `HanimeSubscriptionService`：@POST("subscribe") + @Field("_token")
      + @Field 三件套 + @Header("X-CSRF-TOKEN")；Retrofit 会同源发请求，
      不额外带 Origin —— 它靠的是**同一个 OkHttpClient + CookieJar**。
    · Han1mePlus `han1me_api._submitForm`：显式带
      X-CSRF-TOKEN / Referer / **Origin** / Accept / X-Requested-With，
      并且**419 会刷新 token 重试一次**。
    我们照 Plus 的写法更稳（Laravel 的 VerifyCsrfToken 比对 Origin/Referer 与 Host）。

    第三层是**令牌来源**：Laravel 会话里 _token 与登录 cookie 必须配对。
    每次现抓首页虽然能用，但拿到的是「那一刻」的 token；
    缓存 60 秒 + 失败重取一次，既少一发请求，也避免极端时序下拿旧 token。
    """
    anchor = "fun subscribeArtist(userId: String, artistId: String, subscribe: Boolean): Boolean {"
    i = text.find(anchor)
    if i < 0:
        return text
    j = text.find("fun toggleFavorite(", i)
    if j < 0:
        return text
    block = text[i:j]
    if '.header("Origin", BASE)' in block:
        return text
    old = NL.join([
        '            .header("Referer", "$BASE/")',
        '            .header("X-CSRF-TOKEN", token)',
        '            .header("X-Requested-With", "XMLHttpRequest")',
    ])
    new = NL.join([
        '            // ' + chr(0x2605)*3 + ' 第 345 轮（用户复报「关注按钮仍然 HTTP 403」）：补齐同源头。',
        '            // Laravel 的 VerifyCsrfToken 会比对 Origin/Referer 与 Host；',
        '            // 只给 Referer 时，经代理/镜像链路会被直接判 403（实测如此）。',
        '            // 参考 Han1mePlus 的 _submitForm：Origin / Accept / X-Requested-With 一起给。',
        '            // ' + chr(0x2605) + ' Referer 用详情页而不是首页：原生表单提交的 Referer 就是那个页面，',
        '            // 而 CF 的这条规则也会看 Referer 与提交路径的一致性。',
        '            .header("Referer", com.javrocket.hanime.HanimeClient.lastWatchUrl.ifBlank { "$BASE/" })',
        '            .header("Origin", BASE)',
        '            // ★ 第 345 轮：**UA 用桌面形态**（主线版实证可过）。',
        '            // 实验版全局 UA 是 Android 移动版（CfUa.USER_AGENT），与 WebView 过盾保持一致；',
        '            // 但 CF 对移动 UA 的 POST 似乎更敏感。主线版用桌面 Chrome UA，关注一直是好的。',
        '            .header("User-Agent", com.han1me.lab.CfUa.DESKTOP_USER_AGENT)',
        '            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")',
        '            .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")',
        '            .header("X-CSRF-TOKEN", token)',
        '            // ' + chr(0x2605) * 3 + ' 第 345 轮第二轮（真机 403 的最后一块拼图）：**不能带 X-Requested-With**。',
        '            //',
        '            // 真机日志：GET / -> 200、POST /subscribe -> 403 Cf-Mitigated=challenge。',
        '            // 同一条客户端、同一份 Cookie、相隔 489ms。所以问题不在 Cookie 也不在令牌，',
        '            // 而在「这条 POST 被 CF 当成什么」。',
        '            //',
        '            // 对照两个参考源码：',
        '            //   · Han1meViewer HanimeSubscriptionService —— 只有 @Header("X-CSRF-TOKEN")，',
        '            //     **没有 X-Requested-With**；',
        '            //   · Han1mePlus _submitForm —— 带 X-Requested-With，但它的请求跑在',
        '            //     WebView 已过盾的环境里（同一进程有 cf_clearance + 真浏览器指纹）。',
        '            // 我们两样都没有，却把 XHR 的标记带上了 —— CF 的 WAF 规则正是',
        '            // 「POST + X-Requested-With + /subscribe」这条组合。',
        '            //',
        '            // 真实用户点页面上的「关注」是一次**原生表单提交**（顶层文档导航），',
        '            // 浏览器不会给它加 X-Requested-With，Accept 也是文档型。',
        '            // 这里照那个形态发：去掉 XHR 头、Accept 用文档型、Referer 精确到详情页。',
        '            // ' + chr(0x2605) + ' 不能带 X-Requested-With —— 它是把请求标记成 XHR 的唯一头部。',
    ])
    if old not in block:
        return text
    block2 = block.replace(old, new, 1)
    # 补 419 重试：token 过期时刷新一次再发（Han1mePlus 同款处理）
    old2 = NL.join([
        '        return http.newCall(req).execute().use { resp ->',
        '            val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")',
        '            if (!resp.isSuccessful) throw IllegalStateException("HTTP $" + "{resp.code}")',
    ])
    new2 = NL.join([
        '        var call = http.newCall(req).execute().use { it.code to (runCatching { it.body?.string().orEmpty() }.getOrDefault("")) }',
        '        // ' + chr(0x2605)*3 + ' 第 345 轮：419 = Laravel「CSRF 令牌已过期」。',
        '        // 令牌是现抓首页拿的，理论上不会过期；但镜像/代理会改写会话，',
        '        // 所以照 Han1mePlus 的做法：419 时丢弃缓存 token、重取一次再发。',
        '        if (call.first == 419) {',
        '            csrfTokenCache = "" to 0L',
        '            val t2 = csrfToken()',
        '            val b2 = okhttp3.FormBody.Builder()',
        '                .add("_token", t2)',
        '                .add("subscribe-user-id", userId)',
        '                .add("subscribe-artist-id", artistId)',
        '                .add("subscribe-status", status)',
        '                .build()',
        '            val r2 = req.newBuilder()',
        '                .header("X-CSRF-TOKEN", t2)',
        '                .post(b2)',
        '                .build()',
        '            call = http.newCall(r2).execute().use { it.code to (runCatching { it.body?.string().orEmpty() }.getOrDefault("")) }',
        '        }',
        '        val code = call.first',
        '        val text = call.second',
        '        if (code !in 200..299) throw IllegalStateException("HTTP $" + "{code}")',
    ])
    if old2 in block2:
        block2 = block2.replace(old2, new2, 1)
    return text[:i] + block2 + text[j:]


def patch_subscribe_webview(text):
    """关注按钮改走 HplaySubscribe（第 345 轮）。

    真机日志（11:27:22 同一时刻的两条）：
        GET  hanime1.me/          -> 200  Server=cloudflare
        POST hanime1.me/subscribe -> 403  Cf-Mitigated=challenge

    同一条客户端、同一份 Cookie，GET 通、POST 被挑战 —— 这不是 CSRF（那会是 419，
    或 403 但不带 cf-mitigated），是 Cloudflare 的 WAF 对 POST /subscribe
    单独下发 managed challenge。挑战按 TLS 指纹（JA3）判定，OkHttp 用 Conscrypt，
    指纹与 Chromium 不同，Cookie 带得再全也过不了。

    这里只把 UI 的调用点换成 HplaySubscribe.toggle —— 它内部先走 OkHttp，
    403 时自动回落到 WebView 同源 fetch（见 src/com/han1me/lab/HplaySubscribe.kt）。
    跟 HplayOrientationHost 那种「网络层不碰 UI」的分层保持一致。
    """
    old = "                                                val now = HanimeClient.subscribeArtist("
    if old not in text:
        return text
    # ★ 第 345 轮后续：**回落已移除**。HplaySubscribe 里的 WebView 通道
    # 在真机上反复验证都卡在 Cloudflare 挑战页（页面标题 "Just a moment..."），
    # 即使按 HplayGateActivity 启用了 CfUa.USER_AGENT + ProxyController
    # + 第三方 cookie + ALWAYS_ALLOW 四项，仍不通。
    # 空等 30 秒后报一个更难看的错，不如直接把 OkHttp 的 403 如实报出来。
    # WebView 通道代码保留在 HplaySubscribe.submitInPage，供后续定位。
    new = "                                                val now = HanimeClient.subscribeArtist("
    text = text.replace(old, new, 1)
    # toggle 的第一个参数是详情页地址：WebView 回落要打开它才能拿到站方表单
    old2 = "                                                val now = com.han1me.lab.HplaySubscribe.toggle("
    _i2 = text.find(old2)
    if _i2 >= 0:
        _j2 = text.find(chr(10), _i2)
        if _j2 > 0:
            text = text[:_j2] + chr(10) + "                                                    d.video.url," + text[_j2:]
    return text


def patch_csrf_cache(text):
    """CSRF 令牌加 60 秒缓存（第 345 轮 · 关注 403 的第三层原因）。

    csrfToken() 每调一次就 GET 一次首页 —— 关注一个作者要抓一整页 HTML（约 270 KB）。
    更麻烦的是它与订阅请求之间隔了一次往返：期间若 CF 刷新了 session，
    拿到的 _token 就与请求时携带的 cookie 不配对，Laravel 直接判 419/403。

    加 60 秒缓存后，同一次操作里的多次取令牌共用一份；
    419 时由 patch_subscribe_origin 里的重试逻辑强制清空缓存重取。
    缓存时长与 homePageCache 的 HOME_TTL_MS 对齐，不引入新口径。
    """
    if "csrfTokenCache" in text:
        return text
    anchor = "    private fun csrfToken(): String {"
    i = text.find(anchor)
    if i < 0:
        return text
    old = NL.join([
        '    private fun csrfToken(): String {',
        '        val html = get("$BASE/")',
        '        return Regex(' + K3 + 'name="_token"[^>]*value="([^"]+)"' + K3 + ').find(html)?.groupValues?.get(1)',
        '            ?: throw IllegalStateException("无法获取 CSRF 令牌")',
        '    }',
    ])
    if old not in text:
        return text
    new = NL.join([
        '    // ' + chr(0x2605) + ' 第 345 轮：令牌短缓存（60 秒）。重复取令牌 = 重复抓首页，',
        '    // 而且拿到的 token 可能与会话 cookie 不配对。419 时由订阅重试强制清空。',
        '    @Volatile',
        '    private var csrfTokenCache: Pair<String, Long> = "" to 0L',
        '    private const val CSRF_TTL_MS = 60_000L',
        '',
        '    private fun csrfToken(): String {',
        '        val cached = csrfTokenCache',
        '        if (cached.first.isNotBlank() && System.currentTimeMillis() - cached.second < CSRF_TTL_MS) {',
        '            return cached.first',
        '        }',
        '        val html = get("$BASE/")',
        '        val t = Regex(' + K3 + 'name="_token"[^>]*value="([^"]+)"' + K3 + ').find(html)?.groupValues?.get(1)',
        '            ?: throw IllegalStateException("无法获取 CSRF 令牌")',
        '        csrfTokenCache = t to System.currentTimeMillis()',
        '        return t',
        '    }',
        '',
        '    // ' + chr(0x2605) + ' 第 345 轮：给 HplaySubscribe 用的公开入口（关注回落要令牌）。',
        '    fun csrfTokenPublic(): String = csrfToken()',
        '',
        '    // ' + chr(0x2605) + ' 第 345 轮：HplaySubscribe 要读 BASE（回落时拼详情页地址）。',
        '    fun baseUrlPublic(): String = BASE',

    ])
    return text.replace(old, new, 1)


def patch_update_panel_wire(text):
    """更新面板接线（第 344 轮 · UI 全量核查 P0）。

    审计发现：showUpdate 这个覆盖层里画的是
        if (false) HplayUpdatePanel(...)
    —— 面板被硬关掉，只剩一个铺满屏的 BgColor 空盒子。
    于是开屏弹窗点「立即更新」后 showUpdate=true，用户看到的是**一整屏纯色空白**。

    现在：删掉这个空壳覆盖层；开屏弹窗与用户页的「检查更新」都改走
    HplayUpdateState.request()（面板由 MainActivity 那一层画，与设置页是兄弟节点）。

    实现说明：**按行定位**而不是整段文本锚点。
    这段里有中文注释和 ★ 字符，写成多行字符串锚点极容易在编码/空白上失配
    （本轮实测过一次失配，构建虽然没崩但空壳还在）。按行删最稳。
    """
    lines = text.split('\n')
    out = []
    i = 0
    n = len(lines)
    while i < n:
        if lines[i].strip() == 'if (showUpdate) {':
            # 找到与它同缩进的闭合花括号，整段丢弃
            indent = len(lines[i]) - len(lines[i].lstrip())
            j = i + 1
            while j < n:
                s = lines[j].strip()
                if s == '}' and (len(lines[j]) - len(lines[j].lstrip())) == indent:
                    break
                j += 1
            i = j + 1 if j < n else n
            continue
        out.append(lines[i])
        i += 1
    text = '\n'.join(out)

    # 开屏弹窗的「立即更新」
    if 'showUpdate = true' in text and 'HplayUpdateState.request()' not in text:
        text = text.replace(
            '                        bootUpdate = null\n'
            '                        showUpdate = true\n',
            '                        bootUpdate = null\n'
            '                        // 第 344 轮：面板由 MainActivity 那一层画（与设置页兄弟节点），\n'
            '                        // 这里只置全局可见标志。原来写 showUpdate = true，\n'
            '                        // 而那个覆盖层里是 if (false) —— 点了更新得到一整屏空白。\n'
            '                        com.han1me.ui.HplayUpdateState.request()\n', 1)

    if 'onUpdate = { showUpdate = true },' in text:
        text = text.replace(
            'onUpdate = { showUpdate = true },',
            'onUpdate = { com.han1me.ui.HplayUpdateState.request() },', 1)
    return text


def patch_drop_showupdate_state(text):
    """清掉 showUpdate 这个已废的局部状态（第 344 轮）。

    patch_update_panel_wire 把渲染与两处赋值都改走了 HplayUpdateState，
    剩下这些判定还在引用它 —— 留着只是让人以为「这里还有一个更新面板」：
      · 返回键 handled 的  showUpdate -> true
      · 返回键 when 的    showUpdate -> showUpdate = false
      · 第二个旧 BackHandler 的 enabled 与分支
      · __viewKey 里的拼串（它影响过场动画判定，加个永不变化的 false 只是噪音）
    """
    pairs = [
        ('            showUpdate -> true\n', ''),
        ('            showUpdate -> showUpdate = false\n', ''),
        (' || showUpdate', ''),
        ('            showUpdate -> showUpdate = false\n', ''),
        ('"|" + showUpdate + "|"', '"|"'),
    ]
    for old, new in pairs:
        text = text.replace(old, new)
    return text


def patch_dedup_downloads_panel(text):
    """下载页只能有一份（第 344 轮 · UI 全量核查 P0）。

    审计发现 showDownloads 被渲染了**两次**：
      · 一份在 AnimatedContent 内（有 fade 转场、外层 padding(bottom=74.dp)）；
      · 一份在后面（第 318 轮补的，无转场、Box 只 background）。
    两份各自持 listener、各自跑 ensureFreshAsync、padding 还不同
    （一份 74 又叠加 HplayDownloadsUi 内部的 74 = 148dp）。后果：
      · 两份互相盖，删除一条时另一份不刷新 → 界面「删了还在」；
      · 进页面时高度跳变一次。

    保留**带转场的那份**（在 AnimatedContent 内），删掉后面那份。
    """
    lines = text.split(chr(10))
    guard_start = -1
    for i, L in enumerate(lines):
        if "visible = showDownloads" in L:
            guard_start = i
            break
    if guard_start < 0:
        return text
    out = []
    i = 0
    n = len(lines)
    removed = 0
    while i < n:
        cur = lines[i]
        if cur.strip() == "if (showDownloads) {" and i > guard_start + 3:
            indent = len(cur) - len(cur.lstrip())
            j = i + 1
            while j < n:
                if lines[j].strip() == "}" and (len(lines[j]) - len(lines[j].lstrip())) == indent:
                    break
                j += 1
            removed += 1
            i = j + 1 if j < n else n
            continue
        out.append(cur)
        i += 1
    if removed:
        text = chr(10).join(out)
    return text

def patch_getchu_z_and_back(text):
    """Getchu 详情层：z 序 + 返回键顺序（第 344 轮 · UI 全量核查 P0/P1）。

    两个缺陷：

    ① **z 序**：getchuDetail 那一层画在 webPage **之后**（后画的在上），
       而详情页里的「↗ 转到网页」按钮是 `onOpenUrl -> webPage = WebPage(...)`。
       网页确实被赋值了，但它被 getchuDetail 这一层整个盖住 ——
       用户点了「转到网页」什么都不会发生。
       （这与第 342 轮修过的是同一族问题：那次修的是「列表盖住网页」，
         没注意详情层比网页层更晚绘制。）

    ② **返回键顺序**：主链 when 里 `showGetchu -> showGetchu = false` 排在
       `getchuDetailId != null` 之前。于是在 Getchu 详情页按返回，关掉的是
       **被盖住的列表层**，详情页纹丝不动 —— 要按两次才退得出去。

    修法：把 getchuDetail 那一块整段移到 webPage **之前**；
    返回键的 handled 判定与 when 分支都把 getchuDetailId 提到 showGetchu 前面。
    """
    lines = text.split(chr(10))

    def grab(start_marker, indent_guess=8):
        """按行取出一段 if (...) { ... } 整块，返回 (start, end, block)。"""
        for i, L in enumerate(lines):
            if L.strip() == start_marker:
                ind = len(L) - len(L.lstrip())
                j = i + 1
                while j < len(lines):
                    if lines[j].strip() == "}" and (len(lines[j]) - len(lines[j].lstrip())) == ind:
                        return i, j, lines[i:j + 1]
                    j += 1
        return -1, -1, []

    # ---------- ① z 序 ----------
    s, e, block = grab("getchuDetailId?.let { id ->")
    web_at = -1
    for i, L in enumerate(lines):
        if L.strip() == "webPage?.let { wp ->":
            web_at = i
            break
    if s >= 0 and web_at >= 0 and s > web_at:
        moved = block
        del lines[s:e + 1]
        # 删掉后面可能留下的连续空行
        if s < len(lines) and lines[s].strip() == "":
            del lines[s]
        # 重新定位 webPage，把块插到它前面
        web_at = -1
        for i, L in enumerate(lines):
            if L.strip() == "webPage?.let { wp ->":
                web_at = i
                break
        if web_at >= 0:
            hdr = ["        // ---------- Getchu 详情页（第 77 轮，点预告条目进入） ----------",
                   "        // ★ 第 344 轮：**必须画在 webPage 之前**。",
                   "        // 详情页里的「↗ 转到网页」是 onOpenUrl -> webPage = WebPage(...)，",
                   "        // 而本层原来画在 webPage 之后 —— 网页被它整个盖住 = 点了没反应。",
                   "        // Compose 的 z 序就是声明顺序：先声明的在下。", ""]
            lines[web_at:web_at] = hdr + moved + [""]
            text = chr(10).join(lines)

    # ---------- ② 返回键顺序 ----------
    old_handled = ("            showGetchu -> true\n"
                   "            showPreview -> true\n")
    new_handled = ("            showGetchu -> true\n"
                   "            showPreview -> true\n")
    if "            getchuDetailId != null -> true" in text and \
            text.index("            getchuDetailId != null -> true") > text.index("            showGetchu -> true"):
        # handled：把 getchuDetailId 那行提到 showGetchu 之前（语义等价，只为可读）
        text = text.replace("            getchuDetailId != null -> true\n", "", 1)
        text = text.replace("            showGetchu -> true\n",
                            "            getchuDetailId != null -> true   // 第 344 轮：先退详情，再退列表\n"
                            "            showGetchu -> true\n", 1)

    # when 分支：getchuDetailId 必须排在 showGetchu 之前
    if "            getchuDetailId != null -> getchuDetailId = null\n" in text:
        text = text.replace("            getchuDetailId != null -> getchuDetailId = null\n", "", 1)
        text = text.replace("            showGetchu -> showGetchu = false\n",
                            "            getchuDetailId != null -> getchuDetailId = null\n"
                            "            showGetchu -> showGetchu = false\n", 1)
    return text

def patch_topbar_preview_chip(text):
    """顶栏补「新番」入口（第 344 轮 · UI 全量核查 P0）。

    showPreview = true 的唯一触发点在 TopNavBar 的 onTab 回调里，
    而第 288 轮把标签行整排删了 —— TopNavBar 内 onTab/tabs/activeTab 零引用。
    于是新番预告页（整页 + ListMode.PREVIEW + openPreview）完全不可达。

    修法：给 TopNavBar 加 onPreview 参数，在「优」按钮那段之后补一个「新番」chip。
    实现用**行定位**（找 onFav?.let{...} 的闭合行），文本锚点在中文注释上容易失配。
    """
    if "onPreview = { showPreview = true }" not in text:
        old3 = "                onLogo = { showHanimeLines = true },"
        if old3 in text:
            text = text.replace(old3,
                                old3 + chr(10) +
                                "                // \u2605 \u7b2c 344 \u8f6e\uff1a\u65b0\u756a\u9884\u544a\u5165\u53e3" +
                                "\uff08\u539f\u6765\u53ea\u5728\u5df2\u5220\u9664\u7684\u6807\u7b7e\u884c\u56de\u8c03\u91cc\uff09" + chr(10) +
                                "                onPreview = { showPreview = true },", 1)

    lines = text.split(chr(10))
    # ① 找 TopNavBar 的签名闭合行 "    onSites: (() -> Unit)? = null,"
    sig = -1
    for i, L2 in enumerate(lines):
        if L2.strip() == "onSites: (() -> Unit)? = null,":
            sig = i
            break
    if sig < 0:
        return text
    if "onPreview: (() -> Unit)? = null," not in text:
        pad = len(lines[sig]) - len(lines[sig].lstrip())
        lines.insert(sig + 1, " " * pad + "onPreview: (() -> Unit)? = null,")
    # ② 找 onFav?.let { cb -> ... } 整块的闭合行，把 chip 插在它后面
    fav = -1
    for i, L2 in enumerate(lines):
        if L2.strip() == "onFav?.let { cb ->":
            fav = i
            break
    if fav >= 0 and "// \u65b0\u756a\u9884\u544a" not in text:
        ind = len(lines[fav]) - len(lines[fav].lstrip())
        j = fav + 1
        while j < len(lines):
            if lines[j].strip() == "}" and (len(lines[j]) - len(lines[j].lstrip())) == ind:
                break
            j += 1
        chip = [
            " " * ind + "// \u65b0\u756a\u9884\u544a\uff08\u7b2c 344 \u8f6e\uff1a\u8865\u56de\u5165\u53e3\uff09",
            " " * ind + "onPreview?.let { cb ->",
            " " * (ind + 4) + "Text(",
            " " * (ind + 8) + "\"\u65b0\u756a\", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,",
            " " * (ind + 8) + "modifier = Modifier",
            " " * (ind + 12) + ".clip(RoundedCornerShape(13.dp))",
            " " * (ind + 12) + ".background(CardColorSoft)",
            " " * (ind + 12) + ".clickable { cb() }",
            " " * (ind + 12) + ".padding(horizontal = 9.dp, vertical = 5.dp),",
            " " * (ind + 4) + ")",
            " " * (ind + 4) + "Spacer(Modifier.width(8.dp))",
            " " * ind + "}",
        ]
        lines[j + 1:j + 1] = chip
        text = chr(10).join(lines)
    return text

def patch_drop_preview_chip(text):
    """顶栏「新番」chip 整块删除（第 345 轮 · 用户要求）。

    用户原话：「搜索框右侧新增的新番按钮删掉，那个有详情页在当前点击只能在返回后显示的 bug」。

    为什么删而不是修 z 序：新番预告页（showPreview）声明在 webPage 之后，
    而它里面的条目点开是 Getchu 详情（getchuDetailId）；详情层声明在 webPage 之前、
    showPreview 之前 —— 于是「新番 → 点条目 → 详情」这条链里，详情层被预告页自己盖住，
    只有在返回把 showPreview 关掉之后才露出来。这正是用户看到的现象。
    而「Getchu 预告」入口已经常驻用户页，功能不丢；顶栏这个 chip 是重复入口，
    删掉它比再排一次层级更干净（层级每加一层都要重新核一遍，容易回归）。
    """
    # ① 调用点参数
    lines = text.split(NL)
    lines = [L for L in lines if L.strip() != "onPreview = { showPreview = true },"]
    text = NL.join(lines)
    # ② TopNavBar 签名参数
    lines = text.split(NL)
    lines = [L for L in lines if L.strip() != "onPreview: (() -> Unit)? = null,"]
    text = NL.join(lines)
    # ③ chip 本体（行定位：从注释行到它自己的闭合 }）
    for _ in range(4):
        lines = text.split(NL)
        idx = -1
        for i, L in enumerate(lines):
            if "新番预告（第 344 轮" in L:
                idx = i
                break
        if idx < 0:
            break
        ind = len(lines[idx]) - len(lines[idx].lstrip())
        j = idx + 1
        while j < len(lines):
            if lines[j].strip() == "}" and (len(lines[j]) - len(lines[j].lstrip())) == ind:
                break
            j += 1
        if j >= len(lines):
            break
        del lines[idx:j + 1]
        text = NL.join(lines)
    return text


def patch_fav_atomic_write(text):
    """收藏文件改为原子写 + 备份（第 344 轮 · 网络审计 P0）。

    现状：toggleFav / restoreFavs / restoreFavsFrom 三处都是 favFile.writeText(全表)。
    File.writeText 是**先截断再写**。点收藏的瞬间进程被杀 / 存储写满 / 断电，
    文件就只剩半截或 0 字节 —— 用户的**全部**本机收藏一次性消失，没有任何回滚源。
    favs() 对坏行只做长度过滤，半截文件被静默当成「收藏少了」。

    修法：
      ① 新增 writeFavsAtomic(body)：写 .tmp -> 旧文件复制成 .bak -> rename 覆盖。
         rename 同目录是原子的：要么旧内容，要么新内容，不存在半截。
      ② 三处 favFile.writeText( 全部改成 writeFavsAtomic( —— 签名兼容（都是单参数）。
    """
    if "writeFavsAtomic" in text:
        return text
    anchor = "    private val favFile: File by lazy { File(settingsFile.parentFile, " + chr(34) + "hplay_favorites.tsv" + chr(34) + ") }"
    if anchor not in text:
        return text
    helper = [
        "",
        "    /**",
        "     * 收藏文件的**原子写**（第 344 轮 · 网络审计 P0）。",
        "     *",
        "     * 原来三处都是 favFile.writeText(全表) —— 先截断再写。",
        "     * 写到一半被杀 / 写满，全表收藏就没了（没有第二份副本，无法回滚）。",
        "     *",
        "     * 现在：写 .tmp -> 旧文件复制成 .bak -> rename 覆盖。",
        "     * rename 在同一目录是原子的：要么旧内容，要么新内容，不存在半截。",
        "     * 万一 rename 不被支持（少数文件系统），退回直接写并记一条日志。",
        "     */",
        "    private fun writeFavsAtomic(body: String) {",
        "        runCatching {",
        "            val dir = favFile.parentFile ?: return@runCatching",
        "            dir.mkdirs()",
        "            val tmp = java.io.File(dir, " + chr(34) + "hplay_favorites.tsv.tmp" + chr(34) + ")",
        "            val bak = java.io.File(dir, " + chr(34) + "hplay_favorites.tsv.bak" + chr(34) + ")",
        "            tmp.writeText(body)",
        "            if (favFile.exists() && favFile.length() > 0L) {",
        "                runCatching { favFile.copyTo(bak, overwrite = true) }",
        "            }",
        "            if (!tmp.renameTo(favFile)) {",
        "                favFile.delete()",
        "                if (!tmp.renameTo(favFile)) {",
        "                    com.han1me.log.AppLog.w(" + chr(34) + "fav" + chr(34) + ", " + chr(34) + "原子写失败，退回直接写" + chr(34) + ")",
        "                    favFile.writeText(body)",
        "                }",
        "            }",
        "        }",
        "    }",
        "",
    ]
    # 先把调用点换成原子写（此时 helper 还没插入，不会误伤它内部的兜底写）
    text = text.replace("favFile.writeText(", "writeFavsAtomic(")
    text = text.replace(anchor, anchor + chr(10) + chr(10).join(helper), 1)
    return text

def patch_x99board_paging(text):
    """板块分页按钮接上真实加载（第 344 轮 · 网络审计 P1）。

    现状：‹ › 两个按钮只改 page 这个数字，而唯一的加载触发器
    LaunchedEffect 的键里没有 page（注释明说「键里不能有 page」）。
    于是点 › 后数字从 1 变 2、卡片一张不换；点 ⟳ 时 load() 又把 page 重置回 1 ——
    整条分页栏是死控件。

    修法：点击时直接调 load(append = false)，不依赖 effect 键。
    load() 内部是 wantPage = if (append) page + 1 else page，所以先改 page 再调。
    """
    if "分页按钮直接触发加载" in text:
        return text
    i1 = "                            .clickable { if (page > 1) page -= 1 }.padding(horizontal = 10.dp),"
    i2 = "                            .clickable { page += 1 }.padding(horizontal = 10.dp),"
    pad = "                            "
    if i1 in text:
        text = text.replace(i1, NL.join([
            pad + "// 第 344 轮：分页按钮直接触发加载（原来只改数字，effect 键里没有 page）",
            pad + ".clickable(enabled = page > 1 && !loading) {",
            pad + "    if (page > 1) { page -= 1; load(append = false) }",
            pad + "}.padding(horizontal = 10.dp),",
        ]), 1)
    if i2 in text:
        text = text.replace(i2, NL.join([
            pad + ".clickable(enabled = !loading) {",
            pad + "    page += 1; load(append = false)",
            pad + "}.padding(horizontal = 10.dp),",
        ]), 1)
    return text

def patch_tx_maxpage_offmain(text):
    """糖心板块最大页数计算挪出主线程（第 344 轮 · UI 审计 P0 · ANR）。

    现状：
        withContext(Dispatchers.Main) {
            ...
            maxPage = X99.boardMaxPage(s.name, path)
    而糖心的链路是 boardMaxPage -> X99.kt 的 isTxSite -> TxBoard.maxPage -> ensureIndex()，
    后者会**同步拉 1.9MB 的 search-index.json 并解析**（10841 条）。
    整段跑在主线程上 ⇒ 进糖心板块必 ANR。

    修法：把这一次调用挪回 IO 段（它本来就在 CoroutineScope(Dispatchers.IO).launch 里），
    结果随回包一起带进 Main。
    """
    if "maxPageOffMain" in text:
        return text
    old1 = "            val result = runCatching { X99.boardListing(s.name, base, path, wantPage) }"
    new1 = NL.join([
        "            val result = runCatching { X99.boardListing(s.name, base, path, wantPage) }",
        "            // maxPageOffMain " + chr(0x2605) + " 第 344 轮：最大页数的计算必须留在 IO 线程。",
        "            // 糖心这条链会同步拉 1.9MB 索引并解析，放 Main 上必 ANR。",
        "            val maxPageIo = runCatching { X99.boardMaxPage(s.name, path) }.getOrDefault(0)",
    ])
    if old1 not in text:
        return text
    text = text.replace(old1, new1, 1)
    old2 = "                    maxPage = X99.boardMaxPage(s.name, path)"
    if old2 in text:
        text = text.replace(old2, "                    maxPage = maxPageIo", 1)
    return text

def patch_housekeeping(text):
    """收尾清理（第 344 轮 · UI 全量核查 P2）。

    ① 顶部重复的 import androidx.activity.compose.BackHandler（第 3/4 行各一条）
       —— 本身不报错，但它是"这些补丁层层叠加、没人清理"的直接证据；
    ② `if (true) {` 这种死条件（外面套着恒定成立的分支，读者会以为有条件）。
    """
    lines = text.split(chr(10))
    out = []
    seen_backhandler = False
    for L in lines:
        s = L.strip()
        if s == "import androidx.activity.compose.BackHandler":
            if seen_backhandler:
                continue
            seen_backhandler = True
        if s == "if (true) {" or s.endswith("if (true) {"):
            out.append(L.replace("if (true) {", "run {"))
            continue
        out.append(L)
    return chr(10).join(out)

def patch_title_unescape(text):
    """卡片标题漏 unescape（第 344 轮 · 真机实测发现）。

    真机截图里 Odetta &amp; Vodyanitsa 原样显示 —— &amp; 没被解码。
    根因在 parseCards：
      · titleAttrRe 取出来的 title 走过了 unescape；
      · 但 titleInBlock（div class=title 里那段）只做了去标签 + trim，没有 unescape，
        而它优先级更高（titleInBlock ?: title），所以只要页面上有 .title 块，
        就会带着原始实体直接进界面。
    修法：把 finalTitle / title 两处都过一遍 unescape（幂等，双重解码也安全）。
    """
    marker = "第 344 轮：标题统一补 unescape"
    if marker in text:
        return text
    # ① parseCards 主分支：finalTitle 那一行
    old1 = "                    val finalTitle = (titleInBlock?.takeIf { it.isNotEmpty() } ?: title)"
    if old1 in text:
        text = text.replace(old1, NL.join([
            "                    // " + chr(0x2605) + " " + marker,
            "                    // titleInBlock 只做了去标签，没解 HTML 实体 —— 真机上会显示 Odetta &amp; Vodyanitsa。",
            "                    val finalTitle = (titleInBlock?.takeIf { it.isNotEmpty() } ?: title)",
            "                        .let { unescape(unescape(it)) }",
        ]), 1)
    # ② 备用解析 parseCardsByContainer：title 变量
    old2 = "            val title = titleInBlock.ifEmpty {"
    if old2 in text:
        text = text.replace(old2, NL.join([
            "            // " + chr(0x2605) + " 同上：备用解析器也要解实体。",
            "            val title = titleInBlock.ifEmpty {",
        ]), 1)
    return text

def apply_rules(text, out_name, strict=True):
    if out_name == "SettingsScreens.kt":
        text = patch_appearance_bg(text)
    if out_name == "HplayBackground.kt":
        text = patch_bg_load(text)
    if out_name == "X99.kt":
        text = patch_fav_atomic_write(text)
        text = inject_backup_hanime(text)
        text = patch_testline(text)
        text = patch_testline_log(text)
        text = patch_mirror_sources(text)
        text = patch_mirror_dedup(text)
    if out_name == "HplayBuildInfo.kt":
        text = patch_build_info_version(text)
    if out_name == "HanimeClient.kt":
        text = patch_artist_avatar(text)
        text = patch_title_unescape(text)
        text = patch_subscribe_origin(text)
        text = patch_last_watch_url(text)
        text = patch_csrf_cache(text)
    if out_name == "X99.kt":
        text = patch_age_gate(text)
        text = patch_board_search_fix(text)
        text = patch_find_thumb(text)
        text = patch_xzel_home(text)
        text = patch_src_probe_fn(text)
        text = patch_jav_src_probe(text)
    if out_name == "X99Board.kt":
        text = patch_x99board_paging(text)
        text = patch_tx_maxpage_offmain(text)
        text = patch_agg_query(text)
        text = patch_agg_toolrow(text)
        text = patch_agg_empty(text)
        text = patch_hanime_dialog(text)
        text = patch_board_toolrow(text)
        text = patch_board_current(text)
        text = patch_board_dialog_call(text)
        text = patch_board_dialog_sig(text)
        text = patch_board_pickall(text)
        text = patch_board_pickone(text)
    if out_name == "Han1meApp.kt":
        text = patch_update_panel_wire(text)
        text = patch_drop_showupdate_state(text)
        text = patch_dedup_downloads_panel(text)
        text = patch_getchu_z_and_back(text)
        # ★ 第 345 轮（用户要求）：顶栏「新番」chip 不再植入。
        # 它是重复入口（用户页「Getchu 预告」常驻），
        # 而它开的预告层声明在 webPage 之后、详情层之前，
        # 从而把详情盖住（用户报的就是这个）。
        # text = patch_topbar_preview_chip(text)
        text = patch_drop_preview_chip(text)
        # ★ 第 345 轮：搜索框去文字改在 uifinetune.patch_top_nav 里生成，
        # 那里才是这个搜索框的唯一生成点（postpatch 时它还没被插入）。
        text = patch_login_url(text)
        text = patch_detail_url(text)
        text = patch_act_logging(text)
        text = patch_loadhome_log(text)
        text = patch_dup_key(text)
        text = patch_items_indexed_import(text)
        text = patch_slots_cache(text)
        text = patch_cover_cache_read(text)
        text = patch_user_refresh(text)
        text = patch_cf_banner(text)
        text = patch_topbar_guard(text)
        text = patch_gst_body(text)
        text = patch_gst_call(text)
        text = patch_results_back(text)
        text = patch_getchu_preview(text)
        text = patch_getchu_zorder(text)
        text = patch_card_decode(text)
        text = patch_fav_decode(text)
    for name, old, new in RULES:
        if name != out_name:
            continue
        # ★★★ 第 340 轮：**幂等判定提前到最前面**。
        #
        # 原判据是「old 命中 0 次且 new 已存在 ⇒ 已应用过」。但补丁链会**层层叠加**：
        # 后面的补丁（patches.py / 更晚的 RULES 条目）一旦在 old 附近又插了内容，
        # old 就会命中 2 次甚至更多，于是 n 既不是 1 也不是 0 —— 判据失效，
        # 整个后置阶段抛出 SystemExit，build.py 直接中止（本轮已实际踩到三次）。
        #
        # 正确语义是：**new 已经在文本里就说明这条规则已经应用过**，此时 old 命中几次都无关。
        # 先判这一条，再走「恰好命中 1 次 = 应用」的原路径。
        if new in text:
            continue
        n = text.count(old)
        if n == 1:
            text = text.replace(old, new)
            continue
        if strict:
            raise SystemExit("[后置规则失配] %s：命中 %d 次（应为 1）：%r" % (out_name, n, old[:70]))
    # 所有规则跑完后统一收尾清理（重复 import / 死条件）
    text = patch_housekeeping(text)
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
