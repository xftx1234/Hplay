package com.han1me.getchu

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Calendar
import java.util.zip.GZIPInputStream

/**
 * **Getchu 新作预告**（第 76 轮，照 Han1meViewer 的 GetchuPreview 复刻，用户要求）。
 *
 * 参考源码：
 *  · `logic/network/service/GetchuService.kt` —— 端点 `all/month_title.html?genre=anime_dvd&gage=adult&year=&month=&gc=gc`
 *  · `logic/GetchuNetworkRepo.kt` —— **字符集 EUC-JP**（不是 UTF-8！）
 *  · `logic/GetchuParser.kt` —— 解析 `div.category_anime_t2`（日期表头）→ `div.category_anime_b`（该日分组）
 *     → `div.div_product`（商品）→ `a[href*=/soft.phtml]`
 *  · `logic/model/GetchuPreview.kt` —— 数据结构（dateCode + groups[releaseDate, items]）
 *
 * ## 本实现与原版的差异（如实说明）
 *  · 原版用 **Retrofit + Jsoup**；本应用依赖只有 OkHttp + kotlinx.coroutines，
 *    **不引新依赖**，改用 `HttpURLConnection` + 正则解析。
 *  · **EUC-JP 解码**：Android 自带该字符集，用 `String(bytes, Charset.forName("EUC-JP"))` 即可。
 *  · 原版有「详情页/系列/相关商品」；本页只做**预告列表**（用户要的是预告页一比一，
 *    列表 + 跳转按钮），点条目跳**原站详情页**（浏览器打开），不自己解析详情。
 *
 * ## 过去月 / 未来月
 * 页面上**没有**「過去/未来」导航链接（实测：页面里 過去/未来/次月 均为 0 次），
 * 所以月份切换是**客户端按 year/month 参数自己算的** —— 与 hanime 预告同一套路，
 * 用 [shiftMonthCode] 前后移位即可，**任意月份都能请求**（这就是「过去月未来月都有」的由来）。
 */
object HplayGetchu {

    private const val BASE = "https://www.getchu.com"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"

    /** 一个预告商品（对应参考实现 GetchuPreview.Item）。 */
    data class Item(
        val id: String,
        val title: String,
        val brand: String,
        val cover: String,
        /** 原站详情页（点条目跳这里）。 */
        val detailUrl: String,
        val price: String,
    )

    /** 一个发售日分组（对应参考实现 GetchuPreview.Group）。 */
    data class Group(val releaseDate: String, val items: List<Item>)

    /** 一整页（对应参考实现 GetchuPreview）。 */
    data class Page(
        val dateCode: String,
        val groups: List<Group>,
        /** 非空 = 这一页取不到，内容是给人看的理由。 */
        val error: String,
    ) {
        val count: Int get() = groups.sumOf { it.items.size }
    }

    /** 月份码 `yyyyMM`（与 hanime 预告同一格式）。 */
    fun currentMonthCode(): String {
        val c = Calendar.getInstance()
        return String.format("%04d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    /** 月份码移位（跨年正确）。delta 为负＝更早的月份。 */
    fun shiftMonthCode(code: String, delta: Int): String {
        if (code.length != 6) return code
        var y = code.substring(0, 4).toIntOrNull() ?: return code
        var m = (code.substring(4, 6).toIntOrNull() ?: return code) + delta
        while (m < 1) { m += 12; y -= 1 }
        while (m > 12) { m -= 12; y += 1 }
        return String.format("%04d%02d", y, m)
    }

    /** 月份码 → 界面标签（202611 → 2026/11）。 */
    fun monthLabel(code: String): String {
        if (code.length != 6) return code
        return code.substring(0, 4) + "/" + (code.substring(4, 6).toIntOrNull() ?: return code)
    }

    /** 该月的请求 URL（照参考实现的参数）。 */
    fun urlOf(monthCode: String): String {
        val y = monthCode.take(4)
        val m = monthCode.takeLast(2)
        return "$BASE/all/month_title.html?genre=anime_dvd&gage=adult&year=$y&month=$m&gc=gc"
    }

    /**
     * 抓取并解析某个月的 Getchu 预告。
     *
     * @return 永不为 null：失败时 [Page.error] 是**人话原因**（界面据此如实显示）
     */
    fun load(monthCode: String): Page {
        // 命中缓存直接返回（来回翻月份 / 返回列表再进来都不重新握手）
        monthCache[monthCode]?.let { (g, ts) ->
            if (System.currentTimeMillis() - ts < MONTH_TTL_MS) {
                return Page(monthCode, g, if (g.isEmpty()) "该月份没有预告商品（${monthLabel(monthCode)}）" else "")
            }
        }
        val html = runCatching { fetch(urlOf(monthCode)) }.getOrElse { t ->
            return Page(monthCode, emptyList(), "请求失败：${t.message ?: t}")
        }
        val groups = runCatching { parse(html) }.getOrElse { t ->
            return Page(monthCode, emptyList(), "解析失败：${t.message ?: t}")
        }
        if (groups.isNotEmpty()) monthCache[monthCode] = groups to System.currentTimeMillis()
        val err = if (groups.isEmpty()) "该月份没有预告商品（${monthLabel(monthCode)}）" else ""
        return Page(monthCode, groups, err)
    }

    /**
     * **专用客户端**（第 355 轮 · 修「getchu 加载慢」）。
     *
     * 上一版每次都用 `HttpURLConnection` 新建连接：
     *   · 一页预告 = 1 次 TCP + 1 次 TLS（经内置节点还要多一跳 SOCKS）；
     *   · 点进详情 = 又一次全新握手；返回再进 = 再来一次。
     * 实测「列表 → 详情 → 返回 → 详情」四步要 4 次完整握手，而 getchu 在日本，
     * 单次握手 1.5~3 秒 —— 这就是用户感到的「慢」。
     *
     * 现在从 [com.javrocket.hanime.HanimeClient.http] `newBuilder()` 派生：
     *   · 继承连接池（48 条 / 15 分钟）：同一台服务器第二次请求直接复用 TCP+TLS；
     *   · 继承代理选择器（内置节点 / 直连按 GlobalProxy 判），与其它板块同一套出网口径；
     *   · 超时收紧：连不上就早失败（20s/25s 的旧值在节点抖动时会白等半分钟）。
     *
     * 不设 `Accept-Encoding`：让 OkHttp 自己协商 gzip 并**自动解压**
     *（手工设了它就不再自动解压，旧代码才需要手写 GZIPInputStream）。
     */
    private val http: okhttp3.OkHttpClient by lazy {
        com.javrocket.hanime.HanimeClient.http.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 月份页缓存（10 分钟）：来回翻月份、返回列表时不再重复握手。 */
    private val monthCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Group>, Long>>()
    private const val MONTH_TTL_MS = 10 * 60_000L

    /** 详情页缓存（30 分钟）：在详情与列表间来回点时秒开。 */
    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Detail, Long>>()
    private const val DETAIL_TTL_MS = 30 * 60_000L

    /** GET 取页面，**按 EUC-JP 解码**（照参考实现的 GETCHU_CHARSET）。 */
    private fun fetch(url: String): String {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "ja,en;q=0.8")
            .header("Cache-Control", "no-cache")
            // ★ 第 77 轮真机踩坑：**不带这个 Cookie，详情页会返回「年齢認証ページ」**，
            //   参考实现正是用 GetchuInterceptor 给所有请求加这个 cookie（此处照抄）。
            .header("Cookie", "getchu_adalt_flag=getchu.com; gc=gc")
            .header("Referer", "https://www.getchu.com/")
            .build()
        http.newCall(req).execute().use { resp ->
            val code = resp.code
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val raw = resp.body?.bytes() ?: ByteArray(0)
            if (raw.isEmpty()) throw IllegalStateException("HTTP $code（空正文）")
            // ★ EUC-JP（实测：按 utf-8 解码会失败，按 euc-jp 成功且「発売」等关键词命中）
            return String(raw, Charset.forName("EUC-JP"))
        }
    }

    /**
     * 详情页数据（照参考实现 GetchuPreviewDetail）。
     *
     * 参考源码 `logic/GetchuParser.kt` 的 `getchuPreviewDetail`：
     *  · 标题：og:title → #soft-title → <title>；
     *  · 品牌：#brandsite；
     *  · 封面：og:image → /brandnew/<id>/...package.jpg；
     *  · 价格：span.redb2 → span.redb → ￥ 数字；
     *  · 发售日：_crelease_date → start_date → 発売日 附近；
     *  · 正文段落：商品紹介 / ストーリー / スタッフ（照参考实现排序）；
     *  · 原站详情页地址（「转到网页」按钮用）。
     *
     * 本实现用 HttpURLConnection + 正则，**不引 Jsoup**；字段与顺序对齐参考实现。
     */
    data class Detail(
        val id: String,
        val title: String,
        val brand: String,
        val cover: String,
        val price: String,
        val releaseDate: String,
        /** 正文段落，已按参考实现排序：商品介绍 → 故事 → 制作人员。 */
        val sections: List<TextSection>,
        /** 原站详情页（「转到网页」按钮）。 */
        val productUrl: String,
        /** 非空 = 取不到，内容是给人看的理由。 */
        val error: String,
    )

    /** 正文段落。 */
    data class TextSection(val title: String, val body: String)

    /** 详情页 URL（照参考实现 item/<id>/）。 */
    fun detailUrlOf(id: String): String = "$BASE/item/$id/"

    /**
     * 抓取并解析某个商品的**详情页**（第 77 轮，用户要求「点击新番跳转详情页」）。
     *
     * @return 永不为 null；失败时 [Detail.error] 是人话原因。
     */
    fun loadDetail(id: String): Detail {
        detailCache[id]?.let { (d, ts) ->
            if (System.currentTimeMillis() - ts < DETAIL_TTL_MS) return d
        }
        val html = runCatching { fetch(detailUrlOf(id)) }.getOrElse { t ->
            return Detail(id, "", "", "", "", "", emptyList(), detailUrlOf(id), "请求失败：${t.message ?: t}")
        }
        val d = runCatching { parseDetail(html, id) }.getOrElse { t ->
            Detail(id, "", "", "", "", "", emptyList(), detailUrlOf(id), "解析失败：${t.message ?: t}")
        }
        if (d.error.isBlank()) detailCache[id] = d to System.currentTimeMillis()
        return d
    }

    private val IMG_RE = Regex("<img[^>]*src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** 解析详情页（照参考实现的字段优先级）。 */
    internal fun parseDetail(html: String, id: String): Detail {
        fun meta(prop: String): String {
            val re = Regex(
                "<meta[^>]*(?:property|name)=[\"']" + Regex.escape(prop) + "[\"'][^>]*content=[\"']([^\"']*)[\"']",
                RegexOption.IGNORE_CASE,
            )
            return re.find(html)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
        }
        val title = meta("og:title").ifBlank {
            Regex("id=[\"']soft-title[\"'][^>]*>([\\s\\S]{0,200}?)<", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
        }.ifBlank {
            Regex("<title>([^<]*)</title>", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.substringBefore(" | ")?.let { clean(it) }.orEmpty()
        }
        val brand = Regex("id=[\"']brandsite[\"'][^>]*>([\\s\\S]{0,120}?)<", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
        // 封面：og:image → /brandnew/<id>/...package.jpg。
        // ★ 真机踩坑：og:image 在详情页里是**相对路径**（/brandnew/...），
        //   直接当 URL 用会报「封面地址无主机」—— 必须 absolutize 补上 https://www.getchu.com。
        val cover = absolutize(meta("og:image")).ifBlank {
            Regex("/brandnew/" + Regex.escape(id) + "/([^\"']*?package[^\"']*?\\.jpg)", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.let { absolutize("/brandnew/" + id + "/" + it) }.orEmpty()
        }
        val price = Regex("class=[\"']redb2[\"'][^>]*>([\\s\\S]{0,40}?)<", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { clean(it) }?.takeIf { it.isNotBlank() }
            ?: Regex("class=[\"']redb[\"'][^>]*>([\\s\\S]{0,40}?)<", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.let { clean(it) }?.takeIf { it.isNotBlank() }
            ?: Regex("([￥¥]\\s?[\\d,]+)").find(html)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
        val releaseDate = Regex("_crelease_date[\"']?\\s*:\\s*[\"']?([0-9]{4}-[0-9]{2}-[0-9]{2})", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("start_date=([0-9]{4}(?:/|%2F)[0-9]{2}(?:/|%2F)[0-9]{2})", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.replace("%2F", "/", ignoreCase = true)
            ?: Regex("発売日[^0-9]{0,60}([0-9]{4}[/年][0-9]{1,2}[/月][0-9]{1,2})", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            ?: ""
        return Detail(
            id = id,
            title = title,
            brand = brand,
            cover = cover,
            price = price,
            releaseDate = releaseDate,
            sections = parseSections(html),
            productUrl = detailUrlOf(id),
            error = if (title.isBlank()) "这一页没有解析到标题（页面结构可能已变）" else "",
        )
    }

    /**
     * 解析详情页的正文段落（照参考实现的 商品紹介 / ストーリー / スタッフ 三类，
     * 并按 商品介绍 → 故事 → 制作人员 排序）。
     */
    private fun parseSections(html: String): List<TextSection> {
        // ★ 第 79 轮修（用户报「有的视频获取不到详情页信息」）：
        //
        // Getchu 有**两套详情版式**（实测对比）：
        //  · 旧版式（同人/DL 商品）：段落标题是 <h3 class="page_title">…</h3>，
        //    正文在 class="description" / class="txt" 里；
        //  · 新版式（DVD/商业作品）：段落标题是 商品紹介 / ストーリー / スタッフ 纯文本。
        // 上一版只认后者，于是前一类页面「标题能拿到、正文全空」—— 正是用户看到的现象。
        // 现在两套都认，并按 商品介绍 → 故事 → 制作人员 排序。
        val out = ArrayList<TextSection>()

        // ---- 第一套：关键词标题（照参考实现） ----
        val keys = listOf("商品紹介" to "商品介绍", "ストーリー" to "故事", "スタッフ" to "制作人员")
        val stops = listOf("関連商品", "商品仕様", "この商品を見た人は", "関連ワード", "レビュー")
        for ((jp, cn) in keys) {
            val at = html.indexOf(jp)
            if (at < 0) continue
            val nextTitle = keys.mapNotNull { (k, _) -> html.indexOf(k).takeIf { it > at } }.minOrNull()
            val nextStop = stops.mapNotNull { s -> html.indexOf(s).takeIf { it > at } }.minOrNull()
            val after = listOfNotNull(nextTitle, nextStop).minOrNull() ?: html.length
            if (after <= at) continue
            val seg = stripTags(html.substring(at + jp.length, after))
            if (seg.length >= 20) out.add(TextSection(cn, seg.take(1500)))
        }

        // ---- 第二套：<h3 class="page_title">标题</h3> + description/txt 正文 ----
        if (out.isEmpty()) {
            // 逐个 page_title 当作段落标题，取到下一个标题为止的正文
            val titleRe = Regex("<h3[^>]*class=\"[^\"]*page_title[^\"]*\"[^>]*>([\\s\\S]{0,120}?)</h3>", RegexOption.IGNORE_CASE)
            val marks = titleRe.findAll(html).toList()
            for ((k, m) in marks.withIndex()) {
                val title = clean(m.groupValues[1]).trim()
                if (title.isBlank()) continue
                // 跳过站点通用标题（不是内容段落）
                if (title.contains("関連情報") || title.contains("情報（概要）")) {
                    // 这类标题下的正文仍可能有用（概要），继续取
                }
                val start = m.range.last
                val stop = if (k + 1 < marks.size) marks[k + 1].range.first else html.length
                if (stop <= start) continue
                val seg = stripTags(html.substring(start, stop))
                if (seg.length >= 30) out.add(TextSection(displayTitle(title), seg.take(1500)))
            }
        }

        // ---- 兜底：直接抓 description / txt 容器 ----
        if (out.isEmpty()) {
            val body = Regex("class=\"[^\"]*(?:description|txt)[^\"]*\"[^>]*>([\\s\\S]{0,3000}?)</div>", RegexOption.IGNORE_CASE)
                .findAll(html).map { stripTags(it.groupValues[1]) }.filter { it.length >= 30 }.toList()
            if (body.isNotEmpty()) out.add(TextSection("商品介绍", body.joinToString("\n\n").take(1500)))
        }

        val order = mapOf("商品介绍" to 0, "故事" to 1, "制作人员" to 2)
        return out.distinctBy { it.title }.sortedBy { order[it.title] ?: 9 }
    }

    /** 段落标题归一（把站点的表头文字映射成用户看得懂的类目）。 */
    private fun displayTitle(raw: String): String {
        val t = raw.trim()
        return when {
            t.contains("概要") || t.contains("紹介") || t.contains("説明") -> "商品介绍"
            t.contains("ストーリー") || t.contains("あらすじ") -> "故事"
            t.contains("スタッフ") || t.contains("制作") || t.contains("仕様") -> "制作人员"
            t.length > 24 -> t.take(24)
            else -> t
        }
    }

    /** 去 script/style/标签并压空白（供两套版式共用）。 */
    private fun stripTags(s: String): String = clean(
        s.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " "),
    )
    /**
     * 解析预告页（照参考实现的选择器结构）。
     *
     * 结构实测：
     *  `<div class="category_anime_t2">2026年11月6日発売タイトル</div>`  ← 日期表头
     *  紧随其后的 `<div class="category_anime_b">` 里是该日的 `div.div_product` 商品。
     */
    internal fun parse(html: String): List<Group> {
        // 先切出所有日期表头的位置
        val headerRe = Regex("<div\\s+class=\"category_anime_t2\"\\s*>([\\s\\S]{0,120}?)</div>")
        val marks = headerRe.findAll(html).toList()
        if (marks.isEmpty()) return emptyList()
        val out = ArrayList<Group>()
        for ((i, m) in marks.withIndex()) {
            val releaseDate = clean(m.groupValues[1])
            // 本组正文：从本表头结尾到下一个表头（或文末）
            val start = m.range.last
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else html.length
            if (start >= end) continue
            val seg = html.substring(start, end)
            val items = parseItems(seg)
            if (items.isNotEmpty()) out.add(Group(releaseDate, items))
        }
        return out
    }

    /** 解析一个日期分组里的商品（照参考实现：`div.div_product` → `a[href*=/soft.phtml]`）。 */
    private fun parseItems(seg: String): List<Item> {
        val out = ArrayList<Item>()
        // 以 <div class="div_product" 为界切块（每个商品一块）。
        // 注意：**不能用** "div_product" 裸串 —— 页内 <style> 里也有 .div_product 规则，
        // 会把样式表当成商品（实测第一刀就切在 <style> 上）。必须带 "<div class=" 前缀。
        val idx = Regex("<div\\s+class=\"div_product\"").findAll(seg).map { it.range.first }.toList()
        if (idx.isEmpty()) return emptyList()
        for ((k, s) in idx.withIndex()) {
            val e = if (k + 1 < idx.size) idx[k + 1] else seg.length
            val blk = seg.substring(s, e)
            // 详情链接：优先 soft.phtml（照参考实现）
            val href = Regex("href=\"([^\"]*soft\\.phtml\\?id=\\d+)\"").find(blk)?.groupValues?.get(1)
                ?: Regex("href=\"([^\"]*/item/\\d+[^\"]*)\"").find(blk)?.groupValues?.get(1)
                ?: continue
            val id = Regex("id=(\\d+)").find(href)?.groupValues?.get(1)
                ?: Regex("/(\\d+)/?").find(href)?.groupValues?.get(1) ?: continue
            val detail = absolutize(href)
            // 标题：真实结构是 <A href="/soft.phtml?id=..." class="black" ...>标题<br>(品牌)</A>
            // 参考实现取 td.dd > a 的 text；这里优先带 class="black" 的那个（就是标题链接）。
            val title = (
                Regex("<a[^>]*class=\"black\"[^>]*>([\\s\\S]{0,240}?)</a>", RegexOption.IGNORE_CASE).find(blk)
                    ?: Regex("<a[^>]*href=\"[^\"]*soft\\.phtml[^\"]*\"[^>]*>([\\s\\S]{0,240}?)</a>", RegexOption.IGNORE_CASE).find(blk)
                )?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            // 封面：真实结构 src="/brandnew/<id>/c<id>package_s.jpg"（相对路径要补全）
            // 封面：真实结构 src="/brandnew/<id>/c<id>package_s.jpg"（相对路径要补全）。
            //
            // ★★ 第 80 轮修（用户报「Getchu 里有的封面获取不到」）：
            //  Getchu 对**尚未公开封面**的商品会返回官方占位图
            //  `/common/images/nowprinting200.jpg`（白底 + 「Printing」字样）。
            //  上一版照单全收，界面上就显示成一张白图 —— 用户看到的就是「封面获取不到」。
            //  现在**识别并丢弃占位图**（置空），界面会如实显示「封面未公开」而不是一张白图。
            val coverRaw = Regex("<img[^>]*src=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(blk)
                ?.groupValues?.get(1)?.let { absolutize(it) }.orEmpty()
            val cover = if (isPlaceholderCover(coverRaw)) "" else coverRaw
            // 价格：真实结构 <span class="redb">￥4,180</span>（参考实现同取价格文本）
            val price = Regex("<span\\s+class=\"redb\"\\s*>([\\s\\S]{0,40}?)</span>", RegexOption.IGNORE_CASE)
                .find(blk)?.groupValues?.get(1)?.let { clean(it) }
                ?.takeIf { it.isNotBlank() }
                ?: Regex("([￥¥]\\s?[\\d,]+)").find(blk)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            // 品牌：标题里最后一个括号内的内容（参考实现同法：substringAfterLast("(")）。
            // 真实结构是 `标题<br>(メリー・ジェーン)`，clean() 后成了 `标题 (メリー・ジェーン)`。
            val brand = runCatching {
                val t2 = title.substringAfterLast("(", "").substringBeforeLast(")", "").trim()
                if (t2 == title) "" else t2
            }.getOrDefault("")
            if (title.isNotBlank()) {
                out.add(Item(id, title, brand, cover, detail, price))
            }
        }
        return out.distinctBy { it.id }
    }

    /** 相对链接补全为绝对地址。 */
    private fun absolutize(u: String): String {
        val s = u.replace("&amp;", "&").trim()
        return when {
            s.startsWith("http") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> BASE + s
            else -> "$BASE/$s"
        }
    }

    /** 去标签、去实体、压空白。 */
    private fun clean(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()
}

    /**
     * 这张封面是不是 Getchu 的「尚未公开」占位图（第 80 轮）。
     *
     * 实测占位图路径：`/common/images/nowprinting200.jpg`（白底 + Printing 字样）。
     * 它**不是封面**，当成封面显示只会让用户以为「获取失败」。
     */
    private fun isPlaceholderCover(url: String): Boolean {
        if (url.isBlank()) return true
        val u = url.lowercase()
        return u.contains("nowprinting") || u.contains("/common/images/logo") ||
            u.endsWith("noimage.jpg") || u.contains("no_image")
    }