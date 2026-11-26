package com.han1me.tx

import com.han1me.log.AppLog
import com.han1me.lab.LabLog
import com.javrocket.hanime.HanimeClient
import com.javrocket.hanime.HanimeDetail
import com.javrocket.hanime.HanimeVideo
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * 糖心 Vlog 板块（原生解析，2026-11-17 实验区新增）。
 *
 * 站点是 Astro 静态站：首页/详情/演员/标签都是服务端渲染 HTML，另外带一份**全站索引**
 * search-index.json（实测 1,974,485 B / 10,767 条 / gzip 后约 575 KB），
 * 播放与封面在 CDN t.5gcdn.xyz，**m3u8 与分片必须带站内 Referer，封面不需要**（实测）。
 *
 * 设计取舍（为什么不用逐页抓 HTML）：
 *  · 站点**没有服务端分页**（/featured/?page=2、/tag/x/2/ 都回同一批 24 条，实测），
 *    逐页抓只能拿到首页那 24 条 → 索引是唯一能翻页/搜索的入口；
 *  · 索引拉一次（约 575 KB gzip）+ 本地切片，翻页/搜索零网络、零延迟；
 *  · 直链规则实测 30/30 命中：https://t.5gcdn.xyz/videos/<id>/index.m3u8（带 Referer 200），
 *    所以**不需要抓详情页**；详情页只作为直链失效时的兜底（JSON-LD VideoObject 里也有 contentUrl）。
 */
object TxBoard {

    const val NAME = "糖心Vlog"
    const val HOME = "https://tangxinvlog.app/"
    private const val INDEX_URL = "https://tangxinvlog.app/search-index.json"
    private const val CDN = "https://t.5gcdn.xyz/videos/"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; LM-G820) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val PAGE_SIZE = 24
    /** 索引缓存 12 小时内不重拉。 */
    private const val CACHE_TTL_MS = 12 * 3600_000L

    data class Item(
        val id: String,
        val title: String,
        val actor: String,
        val tags: List<String>,
        val duration: String,
    )

    private val items = AtomicReference<List<Item>>(emptyList())
    private val loadedAt = AtomicReference(0L)

    fun isTxUrl(u: String): Boolean = runCatching {
        val h = URI(u).host.orEmpty().lowercase()
        h == "tangxinvlog.app" || h.endsWith(".tangxinvlog.app")
    }.getOrDefault(false)

    private fun indexFile(): File = File(com.han1me.HplayEnv.dataDir(), "xsite_tx_index.json")

    /** 只看内存里已加载的索引（绝不联网 / 绝不读盘阻塞）。 */
    fun peekIndex(): List<Item> = items.get()

    fun isReady(): Boolean = peekIndex().isNotEmpty()

    /**
     * 后台预热索引（Compose 在 IO 线程调用；**任一入口进板块前先调它**）。
     * 完成后 bump UI 版本号，让正在显示的「全部」下拉框换成真实标签。
     */
    @Synchronized
    fun prewarm() {
        if (isReady()) return
        runCatching { ensureIndex() }
            .onSuccess {
                com.han1me.lab.LabLog.i("tx", "预热完成 " + it.size + " 条")
                com.han1me.lab.LabUi.bump()
            }
            .onFailure {
                com.han1me.lab.LabLog.i("tx", "预热失败：" + it.message)
            }
    }

    // ---------------------------------------------------------------- 索引

    /** 取索引（内存 → 磁盘缓存 → 网络）。失败抛异常，由调用方提示。 */
    @Synchronized
    private fun ensureIndex(force: Boolean = false): List<Item> {
        val cur = items.get()
        if (!force && cur.isNotEmpty() && System.currentTimeMillis() - loadedAt.get() < CACHE_TTL_MS) return cur
        if (!force && cur.isEmpty()) {
            val f = indexFile()
            if (f.isFile && System.currentTimeMillis() - f.lastModified() < CACHE_TTL_MS) {
                runCatching {
                    val list = parse(f.readText())
                    if (list.isNotEmpty()) {
                        items.set(list); loadedAt.set(System.currentTimeMillis())
                        LabLog.i("tx", "索引命中磁盘缓存 " + list.size + " 条（" + (f.length() / 1024) + " KB）")
                        AppLog.step("tx.INDEX_CACHE", "entries=" + list.size)
                        return list
                    }
                }.onFailure { LabLog.i("tx", "磁盘索引解析失败：" + it.message) }
            }
        }
        val t0 = System.currentTimeMillis()
        val body = httpGet(INDEX_URL, HOME)
        val list = parse(body)
        val ms = System.currentTimeMillis() - t0
        if (list.isEmpty()) throw IllegalStateException("索引解析为 0 条（站点结构可能变了）")
        items.set(list); loadedAt.set(System.currentTimeMillis())
        runCatching { indexFile().writeText(body) }
        LabLog.i("tx", "索引拉取 " + body.length + " B / " + list.size + " 条 / " + ms + "ms")
        AppLog.net("tx.INDEX", INDEX_URL, 200, body.length, ms, "entries=" + list.size)
        return list
    }

    private fun parse(raw: String): List<Item> {
        val arr = JSONArray(raw)
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            val id = a.optString(0)
            if (id.isBlank()) continue
            val tags = ArrayList<String>(4)
            a.optJSONArray(3)?.let { t ->
                for (k in 0 until t.length()) t.optString(k).takeIf { it.isNotBlank() }?.let(tags::add)
            }
            out.add(
                Item(
                    id = id,
                    title = a.optString(1),
                    actor = a.optString(2),
                    tags = tags,
                    duration = a.optString(4),
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 列表

    /** 板块列表：索引本地切片（24/页）。tabPath 形如 tag:制服，空或 all = 全部。 */
    fun listing(page: Int, tabPath: String?): List<HanimeVideo> {
        val list = ensureIndex()
        val filtered = tagOf(tabPath)?.let { tag ->
            list.filter { it.tags.any { t -> t.equals(tag, true) } }
        } ?: list
        val from = ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).coerceAtMost(filtered.size)
        val to = (from + PAGE_SIZE).coerceAtMost(filtered.size)
        AppLog.step("tx.LIST_LOAD", "page=" + page + " from=" + from + " to=" + to + " total=" + filtered.size)
        return filtered.subList(from, to).map { videoOf(it) }
    }

    fun maxPage(tabPath: String?): Int {
        val total = runCatching {
            val list = ensureIndex()
            tagOf(tabPath)?.let { tag -> list.count { it.tags.any { t -> t.equals(tag, true) } } } ?: list.size
        }.getOrDefault(0)
        return if (total <= 0) 0 else (total + PAGE_SIZE - 1) / PAGE_SIZE
    }

    private fun tagOf(tabPath: String?): String? =
        tabPath?.takeIf { it.startsWith("tag:") }?.substringAfter("tag:")?.takeIf { it.isNotBlank() }

    /**
     * 子选项：索引里出现频次最高的 20 个标签（标签就是站点的分类主入口）。
     *
     * ★ 必须**只读已加载的索引**，绝不在这里联网：
     *   boardOptions 是在 Compose 组合期被调的，同步联网 = 主线程卡死。
     *   真机实测（2026-11-17）：首次进板块时 MainWatchdog 直接打出
     *     [MAINNET] TxBoard.ensureIndex ← TxBoard.options
     *     [ANR]     TxBoard.ensureIndex ← TxBoard.options
     *   索引 1 MB / 3.9 s 全压在 UI 线程上。
     *   现在：没加载就回「全部」，下拉框等预热后台线程（[prewarm]）填好后再刷新。
     */
    fun options(): List<Pair<String, String>> {
        val list = peekIndex()
        if (list.isEmpty()) return listOf("全部" to "all")
        val freq = HashMap<String, Int>()
        for (it in list) for (t in it.tags) freq[t] = (freq[t] ?: 0) + 1
        val top = freq.entries.sortedByDescending { it.value }.take(20).map { it.key }
        return listOf("全部" to "all") + top.map { it to "tag:" + it }
    }

    private fun videoOf(it: Item): HanimeVideo = HanimeVideo(
        id = it.id,
        url = HOME + "v/" + it.id + "/",
        title = it.title.ifBlank { it.id },
        thumb = CDN + it.id + "/cover.jpg",
        duration = it.duration,
        likes = "",
        views = "",
        brand = it.actor,
    )

    // ---------------------------------------------------------------- 详情

    /**
     * 详情：先用「索引 + 直链规则」直接给结果（零额外请求），失败再抓详情页解析 JSON-LD。
     * 直链规则 30/30 实测成立，所以绝大多数情况下详情是**纯本地**的。
     */
    fun detail(v: HanimeVideo): HanimeDetail? {
        val id = v.id.ifBlank { idOf(v.url) }
        if (id.isBlank()) return null
        val it = runCatching { ensureIndex().firstOrNull { x -> x.id == id } }.getOrNull()
        val m3u8 = CDN + id + "/index.m3u8"
        AppLog.step("tx.DETAIL", "id=" + id + " m3u8=" + m3u8)
        LabLog.i("tx", "详情 " + id + " 直链=" + m3u8)
        return HanimeDetail(
            video = v,
            description = it?.let { buildDesc(it) } ?: "",
            tags = it?.tags ?: emptyList(),
            sources = listOf(0 to m3u8),
            related = relatedOf(it),
        )
    }

    /** 详情页兜底解析（直链规则失效时用）：取 ld+json 里 @type=VideoObject 的 contentUrl。 */
    fun detailFromPage(v: HanimeVideo): HanimeDetail? = runCatching {
        val page = v.url.ifBlank { HOME + "v/" + v.id + "/" }
        val html = httpGet(page, HOME)
        val blocks = Regex("""<script type="application/ld\+json">([\s\S]*?)</script>""").findAll(html)
        var contentUrl = ""
        var name = ""
        var desc = ""
        var tags = emptyList<String>()
        for (b in blocks) {
            val o = runCatching { JSONObject(b.groupValues[1]) }.getOrNull() ?: continue
            if (o.optString("@type") != "VideoObject") continue
            contentUrl = o.optString("contentUrl")
            name = o.optString("name")
            desc = o.optString("description")
            tags = o.optString("keywords").split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
            break
        }
        if (contentUrl.isBlank()) null
        else HanimeDetail(
            video = v.copy(title = name.ifBlank { v.title }),
            description = desc,
            tags = tags,
            sources = listOf(0 to contentUrl),
            related = emptyList(),
        )
    }.getOrNull()

    private fun idOf(u: String): String = runCatching {
        val path = URI(u).path.orEmpty()
        Regex("""/v/([A-Za-z0-9]+)/?""").find(path)?.groupValues?.get(1).orEmpty()
    }.getOrDefault("")

    private fun buildDesc(i: Item): String = buildString {
        append("演员：").append(i.actor.ifBlank { "—" }).append('\n')
        append("时长：").append(i.duration.ifBlank { "—" }).append('\n')
        if (i.tags.isNotEmpty()) append("标签：").append(i.tags.joinToString("、"))
    }.trim()

    private fun relatedOf(cur: Item?): List<HanimeVideo> {
        if (cur == null) return emptyList()
        return runCatching {
            ensureIndex().asSequence()
                .filter { it.id != cur.id && it.tags.any { t -> cur.tags.contains(t) } }
                .take(12).map { videoOf(it) }.toList()
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- 搜索

    /** 本地全文检索（标题 / 演员 / 标签）。10.7k 条线性匹配 <20ms，不发请求。 */
    fun search(kw: String, limit: Int = 60): List<HanimeVideo>? {
        val k = kw.trim().lowercase()
        if (k.isEmpty()) return emptyList()
        val list = ensureIndex()
        val t0 = System.currentTimeMillis()
        val hits = ArrayList<HanimeVideo>(limit)
        for (it in list) {
            if (it.title.lowercase().contains(k) ||
                it.actor.lowercase().contains(k) ||
                it.tags.any { t -> t.lowercase().contains(k) }
            ) {
                hits.add(videoOf(it))
                if (hits.size >= limit) break
            }
        }
        AppLog.step("tx.SEARCH", "hits=" + hits.size + " ms=" + (System.currentTimeMillis() - t0))
        return hits
    }

    // ---------------------------------------------------------------- HTTP

    private fun httpGet(url: String, referer: String = ""): String {
        val b = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "*/*")
        if (referer.isNotEmpty()) b.header("Referer", referer)
        val t0 = System.currentTimeMillis()
        HanimeClient.http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            AppLog.net("tx.GET", url, r.code, body.length, System.currentTimeMillis() - t0,
                if (referer.isEmpty()) "direct" else "referer")
            if (!r.isSuccessful) throw IllegalStateException("HTTP " + r.code + "：" + url)
            return body
        }
    }

    /** 播放/下载用：CDN 上这条地址的 Referer（站点根域即可，实测详情页 URL 与根域都 200）。 */
    fun refererFor(u: String): String = if (isCdnUrl(u)) HOME else ""

    fun isCdnUrl(u: String): Boolean = runCatching {
        val h = URI(u).host.orEmpty().lowercase()
        h == "5gcdn.xyz" || h.endsWith(".5gcdn.xyz")
    }.getOrDefault(false)

    fun cachePath(): String = indexFile().absolutePath
}