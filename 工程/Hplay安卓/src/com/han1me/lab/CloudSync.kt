package com.han1me.lab

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * **云端同步核心**（2026-11-25 第 296 轮）。
 *
 * 用户要求：「收藏播放云同步历史体系等全部照搬两个源码的最好方案，抛弃老旧方案」。
 *
 * ## 端点清单（逐条对照 Han1meViewer 的 `logic/network/service/HanimeMyListService.kt`）

 * | 能力 | 方法 | 端点 |
 * |---|---|---|
 * | 喜欢清单 | GET | `/user/{uid}/likes` |
 * | 稍后观看 | GET | `/user/{uid}/saves` |
 * | 观看历史 | GET | `/user/{uid}/histories` |
 * | 播放列表 | GET | `/playlist` |
 * | 收藏 | POST | `/like` |
 * | 加入清单 | POST | `/save` |
 * | 评分 | POST | `/like`（带 rating） |
 * | 删历史 | POST | `/user/{uid}/histories/delete` |
 * | 删清单项 | POST | `/user/{uid}/{type}/delete` |

 * 这些端点在 **HTML 页面**上返回列表（不是 JSON），所以解析要用正则抽卡片。
 * Viewer 用 Jsoup 解析；我们没有 Jsoup，用正则做等价抽取。
 *
 * ## 与旧方案的区别

 * 旧方案：只存本地，账号体系形同虚设（登录了也只显示「需登录」）。
 * 新方案：登录后**真的读写云端**，跨设备同步。
 */
object CloudSync {

    private const val BASE = "https://hanime1.me"

    /** 列表类型。对应 Viewer 的 `MyListType`。 */
    enum class ListType(val path: String) {
        LIKES("likes"),
        SAVES("saves"),
        HISTORY("histories"),
    }

    /** 云端列表里的一条。 */
    data class Item(
        val id: String,
        val title: String,
        val thumb: String,
        val url: String,
        val meta: String = "",
    )

    /**
     * 拉取云端清单。
     *
     * @return null = 失败（网络/未登录），空 list = 确实没有
     */
    fun fetchList(http: OkHttpClient, uid: String, type: ListType): List<Item>? {
        if (uid.isBlank()) return null
        return try {
            val url = "$BASE/user/$uid/" + type.path
            val req = Request.Builder().url(url).get().build()
            val html = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.string().orEmpty()
            }
            parseItems(html)
        } catch (t: Throwable) {
            LabLog.i("cloud", "拉取 " + type.path + " 失败：" + (t.message ?: t.javaClass.simpleName))
            null
        }
    }

    /**
     * 从列表页 HTML 抽条目。
     *
     * 站点结构：每条是一个 `<a href="/watch?v=xxx">` 包着 `<img src=封面>` 和标题。
     * 用正则等价实现 Viewer 的 `extractHanimeInfo`。
     */
    private fun parseItems(html: String): List<Item> {
        val out = ArrayList<Item>()
        // 匹配：<a ... href="/watch?v=ID" ...> ... <img ... src="THUMB" ...> ... </a>
        val re = Regex(
            """<a[^>]+href=["']([^"']*watch\?v=(\d+)[^"']*)["'][^>]*>""" +
                """([\s\S]{0,600}?)</a>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in re.findAll(html)) {
            val href = m.groupValues[1]
            val vid = m.groupValues[2]
            val inner = m.groupValues[3]
            if (out.any { it.id == vid }) continue
            val thumb = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1).orEmpty()
            // 标题：优先 title 属性，其次 alt，最后纯文本
            val title = Regex("""title=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1)
                ?: Regex("""alt=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(inner)?.groupValues?.get(1)
                ?: stripTags(inner).trim().take(120)
            val abs = if (href.startsWith("http")) href else BASE + href
            out.add(Item(id = vid, title = title, thumb = thumb, url = abs))
        }
        return out
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), " ").replace(Regex("""\s+"""), " ").trim()

    /** 收藏（POST /like）。 */
    fun like(http: OkHttpClient, videoId: String, csrf: String): Boolean =
        postForm(http, "$BASE/like", mapOf(
            "_token" to csrf,
            "video_id" to videoId,
            "type" to "1",
            "action" to "like",
        ))

    /** 稍后观看（POST /save）。 */
    fun save(http: OkHttpClient, videoId: String, csrf: String): Boolean =
        postForm(http, "$BASE/save", mapOf(
            "_token" to csrf,
            "video_id" to videoId,
        ))

    /** 通用表单 POST。 */
    private fun postForm(
        http: OkHttpClient,
        url: String,
        fields: Map<String, String>,
    ): Boolean {
        return try {
            val b = FormBody.Builder()
            fields.forEach { (k, v) -> b.add(k, v) }
            val req = Request.Builder()
                .url(url)
                .post(b.build())
                .header("X-CSRF-TOKEN", fields["_token"].orEmpty())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", BASE + "/")
                .build()
            http.newCall(req).execute().use { r -> r.isSuccessful || r.code == 302 }
        } catch (t: Throwable) {
            LabLog.i("cloud", "POST 失败：" + (t.message ?: t.javaClass.simpleName))
            false
        }
    }

    /** 从任意页面抽 CSRF token。 */
    fun csrf(http: OkHttpClient): String {
        return try {
            val req = Request.Builder().url("$BASE/").get().build()
            val html = http.newCall(req).execute().use { it.body?.string().orEmpty() }
            Regex("""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
                ?: Regex("""name=["']_token["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)
                ?: ""
        } catch (t: Throwable) {
            ""
        }
    }
}
