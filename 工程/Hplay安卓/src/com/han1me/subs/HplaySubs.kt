package com.han1me.subs

import com.javrocket.hanime.HanimeClient

/**
 * **我的订阅**（第 80 轮，照 Han1meViewer 的 MySubscriptions 复刻）。
 *
 * 参考源码：
 *  · logic/model/MySubscriptions.kt（数据模型）
 *  · logic/Parser.kt 的 getMySubscriptions（解析，逐选择器照抄）
 *  · ui/screen/home/subscription/SubscriptionContent.kt（界面）
 *
 * 页面结构（照用户截图 1）：
 *  · **已关注作者（N）** + 「向右滑动 →」提示 + 横向滚动的作者头像行（点作者 → 搜索他的作品）；
 *  · 下方订阅视频网格（封面/时长/播放量/标题/作者/点赞率/上传时间）。
 *
 * 本应用**不引 Jsoup**，用正则 + 简单的块切分实现同样的选择器语义。
 */
object HplaySubs {

    /** 一个已关注作者。 */
    data class Artist(val name: String, val avatar: String)

    /** 一条订阅视频。 */
    data class Video(
        val code: String,
        val title: String,
        val cover: String,
        val duration: String,
        val views: String,
        val artist: String,
        val uploadTime: String,
        val reviews: String,
    )

    /** 一页订阅数据。 */
    data class Page(
        val artists: List<Artist>,
        val videos: List<Video>,
        /** 非空 = 取不到，内容是给人看的理由。 */
        val error: String,
    )

    /**
     * 抓取并解析订阅页。
     *
     * 参考实现打的是 `GET /subscriptions?page=N`（HanimeBaseService）。
     * 这里用已封装的 [HanimeClient.get]（带 cookie / DoH / 代理链）。
     */
    fun load(page: Int = 1): Page {
        val html = runCatching { HanimeClient.htmlOf("/subscriptions?page=$page") }
            .getOrElse { t -> return Page(emptyList(), emptyList(), "请求失败：${t.message ?: t}") }
        return runCatching { parse(html) }
            .getOrElse { t -> Page(emptyList(), emptyList(), "解析失败：${t.message ?: t}") }
    }

    /**
     * 解析订阅页（选择器语义照参考实现 Parser.getMySubscriptions）。
     *
     *  · 作者：在 `div.subscriptions-nav` 里找 `div.subscriptions-artist-card`，
     *    取第 2 个 img 的 src 作头像、`div.card-mobile-title` 作文本；
     *  · 视频：在 `div.content-padding-new` 里找 `div[class^=video-item-container]`，
     *    取 `a[class^=video-link]` 的 `watch?v=<code>`、`img[class^=main-thumb]` 的 src、
     *    卡片的 title 属性、`div[class^=duration]`、`div[class^=stat-item]`、`div.subtitle`。
     */
    internal fun parse(html: String): Page {
        val nav = sliceBetween(html, "subscriptions-nav") ?: ""
        val artists = Regex(
            "<div[^>]*class=\"[^\"]*subscriptions-artist-card[^\"]*\"[^>]*>([\\s\\S]*?)(?=<div[^>]*class=\"[^\"]*subscriptions-artist-card|\\z)",
            RegexOption.IGNORE_CASE,
        ).findAll(nav).mapNotNull { m ->
            val blk = m.groupValues[1]
            val imgs = Regex("<img[^>]*src=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .findAll(blk).map { it.groupValues[1] }.toList()
            val avatar = absolutize(imgs.getOrNull(1) ?: imgs.firstOrNull().orEmpty())
            val name = Regex(
                "class=\"[^\"]*card-mobile-title[^\"]*\"[^>]*>([\\s\\S]{0,80}?)<",
                RegexOption.IGNORE_CASE,
            ).find(blk)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            if (name.isBlank()) null else Artist(name, avatar)
        }.distinctBy { it.name }.toList()

        // ---------- 视频：**复用官方源那套已验证的卡片解析** ----------
        //
        // ★ 第 84 轮修（用户报「我的订阅下方是空白，没有最近发布视频推荐」）：
        //
        // 我上一版自己写了一套 `div[class^=video-item-container]` 的正则切块，
        // 但订阅页的卡片结构与普通列表页并不完全一致（实测切不出块 ⇒ 一个视频都没有），
        // 而**作者那部分照旧能解析** —— 于是表现就是「作者有、下面空白」。
        //
        // 现在改成复用 HanimeClient.parseCards() —— 首页/搜索/相关推荐/收藏都在用，
        // 也是**旧的订阅入口**（HanimeClient.subscriptions）在用的同一套解析。
        //
        // ★ 顺序很重要（自查修正）：**先走已验证能工作的整页解析**，再退到切片。
        //
        // 旧入口 HanimeClient.subscriptions() 就是 parseCards(整页)，用户截图里它是有视频的 ——
        // 所以「整页」是已被证实的输入形态，必须排在第一位。
        // 反过来（先切片）有个隐患：切片里若解析出**别的**卡片（非空），兜底就不会触发，
        // 于是又变成"有东西但不是订阅视频" —— 不如把确定性高的放前面。
        // 切片只作为兜底（整页解析为空时的第二次尝试）。
        val videos = runCatching {
            val parsed = com.javrocket.hanime.HanimeClient.parseCards(html)
                .ifEmpty {
                    val root = sliceBetween(html, "content-padding-new") ?: html
                    com.javrocket.hanime.HanimeClient.parseCards(root)
                }
            parsed.map { v ->
                Video(
                    code = v.id,
                    title = v.title,
                    cover = v.thumb,
                    duration = v.duration,
                    views = v.views,
                    artist = v.brand,
                    uploadTime = "",
                    reviews = v.likes,
                )
            }
        }.getOrDefault(emptyList())

        return Page(
            artists = artists,
            videos = videos,
            error = when {
                !html.contains("subscriptions-nav") -> "这个页面没有订阅区块（可能未登录）"
                artists.isEmpty() && videos.isEmpty() -> "没有订阅内容"
                // ★ 注意：Kotlin 的 when 里 else 分支**不能跟逗号**（会报
                //   "'else' entry must be the last one"）。这个坑本轮已踩第 3 次，记牢。
                else -> ""
            },
        )
    }

    // ---------------- 小工具 ----------------

    /** 取 `class="<name>..."` 那个容器到下一个同级/末尾之间的一段（替代 Jsoup 的 selectFirst 范围）。 */
    private fun sliceBetween(html: String, name: String): String? {
        val i = html.indexOf(name)
        if (i < 0) return null
        val end = html.length
        return html.substring(i, end)
    }

    /** 相对路径补全为绝对地址（照参考实现的 absUrl）。 */
    internal fun absolutize(url: String): String {
        val u = url.trim()
        return when {
            u.isBlank() -> ""
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> HanimeClient.BASE + u
            else -> "${HanimeClient.BASE}/$u"
        }
    }

    /** 去标签、解实体、压空白。 */
    internal fun clean(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

    // ---------------- 订阅 / 取消订阅（第 301 轮）----------------
    //
    // 照 Han1meViewer 的 HanimeSubscriptionService：
    //   POST /subscribe
    //     _token               CSRF
    //     subscribe-user-id    当前用户 id
    //     subscribe-artist-id  作者 id
    //     subscribe-status     "" = 订阅， "1" = 已订阅
    //   + X-CSRF-TOKEN 头
    //
    // 之前只有「拉取订阅列表」，没有「订阅动作」—— 用户点作者只能去搜索。
    // 现在补上，能真正关注/取关。

    /**
     * 订阅或取消订阅一个作者。
     *
     * @param artistId 作者 id（从作者卡里的 /artist/<id> 或 /user/<id> 抽）
     * @param subscribed true = 订阅，false = 取消
     * @return 成功与否
     */
    fun setSubscribe(artistId: String, subscribed: Boolean): Boolean {
        if (artistId.isBlank()) return false
        return runCatching {
            val uid = HanimeClient.userId().orEmpty()
            if (uid.isBlank()) return false
            // csrfToken() 在 HanimeClient 里是 private，用 CloudSync 的公开实现
            val csrf = com.han1me.lab.CloudSync.csrf(HanimeClient.http)
            if (csrf.isBlank()) return false
            val form = okhttp3.FormBody.Builder()
                .add("_token", csrf)
                .add("subscribe-user-id", uid)
                .add("subscribe-artist-id", artistId)
                .add("subscribe-status", if (subscribed) "1" else "")
                .build()
            val req = okhttp3.Request.Builder()
                .url(HanimeClient.BASE + "/subscribe")
                .post(form)
                .header("X-CSRF-TOKEN", csrf)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", HanimeClient.BASE + "/")
                .build()
            HanimeClient.http.newCall(req).execute().use { r ->
                val ok = r.isSuccessful || r.code == 302
                com.han1me.lab.LabLog.i("sub", "setSubscribe " + artistId + " -> " + r.code)
                ok
            }
        }.getOrDefault(false)
    }
}
