package com.han1me.lab

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * **原生订阅**（2026-11-25 第 301 轮）。
 *
 * 用户要求：「订阅板块也重构原生，所有云端接线并且实现方式照搬源码」。
 *
 * ## 端点（照 Han1meViewer）

 * | 能力 | 端点 | 来源 |
 * |---|---|---|
 * | 订阅列表 | `GET /subscriptions` | HanimeBaseService.kt 第 62 行 |
 * | 订阅作者 | `POST /subscribe` | HanimeSubscriptionService.kt 第 13 行 |

 * POST 字段（照原样）：
 * ```
 * _token               CSRF
 * subscribe-user-id    当前用户 id
 * subscribe-artist-id  作者 id
 * subscribe-status     "" = 订阅， "1" = 已订阅
 * X-CSRF-TOKEN         头，值同 _token
 * ```

 * ## 页面结构（照 Parser.getMySubscriptions 第 1251-1272 行）

 * ```html
 * <div class="subscriptions-nav">
 *   <div class="subscriptions-artist-card">
 *     <img ...>            <- 第 1 张是背景
 *     <img src="头像">      <- 第 2 张是头像（imgs[1]）
 *     <div class="card-mobile-title">作者名</div>
 *   </div>
 * </div>
 * <div class="content-padding-new"> ... 视频卡 ... </div>
 * ```
 */
object CloudSubscription {

    private const val BASE = "https://hanime1.me"

    /** 一个订阅作者。 */
    data class Artist(
        val name: String,
        val avatar: String,
        val artistId: String = "",
        val subscribed: Boolean = true,
    )

    /** 订阅页的一条视频。 */
    data class Video(
        val id: String,
        val title: String,
        val thumb: String,
        val artist: String = "",
    )

    /** 订阅页整体数据。 */
    data class Page(
        val artists: List<Artist> = emptyList(),
        val videos: List<Video> = emptyList(),
        val maxPage: Int = 1,
    )

    /**
     * 拉取订阅页。
     *
     * @return null = 未登录或网络失败
     */
    fun fetch(http: OkHttpClient, page: Int = 1): Page? {
        return try {
            val url = "$BASE/subscriptions?page=$page"
            val req = Request.Builder().url(url).get().build()
            val html = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.string().orEmpty()
            }
            if (!html.contains("subscriptions-nav") && !html.contains("subscriptions-artist-card")) {
                LabLog.i("sub", "订阅页无 subscriptions-nav（可能未登录）")
                return null
            }
            val artists = parseArtists(html)
            val videos = parseVideos(html)
            val maxPage = Regex("""\?page=(\d+)""")
                .findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1
            LabLog.i("sub", "订阅页 page=" + page + " 作者=" + artists.size + " 视频=" + videos.size)
            Page(artists = artists, videos = videos, maxPage = maxPage)
        } catch (t: Throwable) {
            LabLog.i("sub", "拉取失败：" + (t.message ?: t.javaClass.simpleName))
            null
        }
    }

    /**
     * 解析作者卡（照 Parser 第 1261-1272 行）。
     *
     * 每张卡里第 2 个 img 是头像（第 1 个是背景），标题在 div.card-mobile-title。
     */
    private fun parseArtists(html: String): List<Artist> {
        val out = ArrayList<Artist>()
        val cardRe = Regex(
            """<div[^>]*class=["'][^"']*subscriptions-artist-card[^"']*["'][^>]*>([\s\S]{0,1200}?)</div>\s*</div>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in cardRe.findAll(html)) {
            val inner = m.groupValues[1]
            val imgs = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(inner).map { it.groupValues[1] }.toList()
            val avatar = imgs.getOrNull(1) ?: imgs.firstOrNull() ?: continue
            val name = Regex(
                """<div[^>]*class=["'][^"']*card-mobile-title[^"']*["'][^>]*>([^<]+)<""",
                RegexOption.IGNORE_CASE,
            ).find(inner)?.groupValues?.get(1)?.trim() ?: continue
            val aid = Regex("""/artist/(\d+)""").find(inner)?.groupValues?.get(1).orEmpty()
            if (out.any { it.name == name }) continue
            out.add(Artist(name = name, avatar = avatar, artistId = aid))
        }
        return out
    }

    /** 解析订阅页里的视频卡。 */
    private fun parseVideos(html: String): List<Video> {
        val out = ArrayList<Video>()
        val re = Regex(
            """<a[^>]+href=["']([^"']*watch\?v=(\d+)[^"']*)["'][^>]*>([\s\S]{0,500}?)</a>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in re.findAll(html)) {
            val vid = m.groupValues[2]
            if (out.any { it.id == vid }) continue
            val inner = m.groupValues[3]
            val thumb = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1).orEmpty()
            val title = Regex("""title=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1)
                ?: inner.replace(Regex("<[^>]+>"), " ").trim().take(80)
            out.add(Video(id = vid, title = title, thumb = thumb))
        }
        return out
    }

    /**
     * 订阅 / 取消订阅作者。
     *
     * @param status "" = 订阅， "1" = 已订阅（照 Viewer 的注释）
     */
    fun subscribe(
        http: OkHttpClient,
        userId: String,
        artistId: String,
        csrf: String,
        subscribed: Boolean,
    ): Boolean {
        return try {
            val b = FormBody.Builder()
                .add("_token", csrf)
                .add("subscribe-user-id", userId)
                .add("subscribe-artist-id", artistId)
                .add("subscribe-status", if (subscribed) "1" else "")
                .build()
            val req = Request.Builder()
                .url("$BASE/subscribe")
                .post(b)
                .header("X-CSRF-TOKEN", csrf)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$BASE/")
                .build()
            http.newCall(req).execute().use { r -> r.isSuccessful || r.code == 302 }
        } catch (t: Throwable) {
            LabLog.i("sub", "订阅请求失败：" + (t.message ?: t.javaClass.simpleName))
            false
        }
    }
}
