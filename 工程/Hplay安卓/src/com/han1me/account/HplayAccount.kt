package com.han1me.account

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * **hanime1.me 云端账户**（第 350 轮，照 Han1meViewer 的 AccountScreen + UserAccountViewModel 复刻）。
 *
 * ## 参考实现调了哪四个请求（全部照抄，参数名一个不改）
 *
 * | 用途 | 请求 | 出处 |
 * |---|---|---|
 * | 读账户页 | `GET /user/{id}/edit` | HanimeMyListService.kt:44-47 |
 * | 改昵称 | `POST /user/{id}` form：`_token` `_method=patch` `type=profile` `name` + 头 `X-CSRF-TOKEN` | :63-73 |
 * | 改密码 | `POST /user/{id}` form：`_token` `_method=patch` `type=password` `password_old` `password_new` `password_new_confirm` + 头 `X-CSRF-TOKEN` | :75-86 |
 * | 改头像 | `POST /user/{id}` **multipart**：`_token` `_method=patch` `type=photo` `photo`（jpeg） | :88-96 |
 *
 * 成功码是 **302**（Laravel 的重定向），不是 200 —— 参考实现把 302 列进 permittedSuccessCode，
 * 本类同样接受 2xx 与 302。
 *
 * ## 解析为什么用正则而不是 jsoup
 *
 * 本工程的依赖清单（build/dep_jars.txt）里**没有 jsoup**，为这一个页面引一个解析库不划算。
 * 需要的字段只有 7 个，选择器都写在下面，逐条对应参考实现 Parser.kt:836-880。
 *
 * 注意 `emailEditable`：2026-11 站点改版后邮箱输入框可能带 `disabled`，
 * 那时**不能**让用户改（提交也会被站点忽略）。参考实现就是这么判的（:856）。
 */
object HplayAccount {

    private val BASE = com.javrocket.hanime.HanimeClient.BASE

    private fun http() = com.javrocket.hanime.HanimeClient.http

    /** 账户页解析结果（字段与参考实现的 UserAccount 一一对应）。 */
    data class Info(
        val csrfToken: String,
        val avatarUrl: String,
        val username: String,
        val email: String,
        val userId: String,
        val joinedLabel: String,
        val subscriberCount: Int,
        val videoCount: Int,
        val emailEditable: Boolean,
    )

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    /**
     * 读账户页并解析。
     *
     * @param userId 站点用户 id（纯数字）。调用方从详情页 / 主页链接里取。
     * @throws IllegalStateException 未登录 / 网络失败 / 页面结构不认识时抛，带人话原因。
     */
    fun fetch(userId: String): Info {
        require(userId.isNotBlank()) { "缺少用户 id" }
        val req = Request.Builder()
            .url("$BASE/user/$userId/edit")
            .header("User-Agent", com.javrocket.hanime.HanimeClient.UA)
            .header("Referer", "$BASE/user/$userId")
            .get().build()
        val html = http().newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                throw IllegalStateException(
                    if (r.code == 404) "账户页不存在（可能未登录或 id 不对）" else "读取账户页失败：HTTP " + r.code
                )
            }
            r.body?.string().orEmpty()
        }
        if (html.isBlank()) throw IllegalStateException("账户页是空的（可能被 Cloudflare 拦了）")
        // 页面里出现登录表单 = Cookie 已失效（参考实现同样把这种情况当失败）
        if (html.contains("name=\"password\"") && !html.contains("profile-sub-stats")) {
            throw IllegalStateException("登录已失效，请重新登录后再试")
        }

        val csrf = Regex("""<meta name=\"csrf-token\" content=\"([^\"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<input[^>]*name=\"_token\"[^>]*value=\"([^\"]+)"""")
                .find(html)?.groupValues?.get(1)
            ?: ""

        val avatar = Regex("""<img[^>]*id=\"playlist-avatar\"[^>]*src=\"([^\"]+)"""")
            .find(html)?.groupValues?.get(1).orEmpty()

        val name = Regex("""<input[^>]*name=\"name\"[^>]*value=\"([^\"]*)"""")
            .find(html)?.groupValues?.get(1).orEmpty()

        // 邮箱：优先 #email，回退 input[name=email]（参考实现 :851-854）
        val emailBlock = Regex("""<input[^>]*id=\"email\"[^>]*>"""").find(html)?.value
            ?: Regex("""<input[^>]*name=\"email\"[^>]*>"""").find(html)?.value
            ?: ""
        val email = Regex("""value=\"([^\"]*)"""").find(emailBlock)?.groupValues?.get(1).orEmpty()
        val emailEditable = emailBlock.isNotEmpty() && !emailBlock.contains("disabled")

        val uidText = Regex("""class=\"profile-sub-stats-id\"[^>]*>([^<]*)<"""")
            .find(html)?.groupValues?.get(1).orEmpty()
        val uid = Regex("""\d+""").find(uidText)?.value ?: userId

        val joined = unescape(
            Regex("""id=\"user-modal-created\"[^>]*>([^<]*)<"""")
                .find(html)?.groupValues?.get(1).orEmpty()
        ).trim()

        val statLine = Regex("""class=\"profile-sub-stats-new-line\"[^>]*>([^<]*)<"""")
            .find(html)?.groupValues?.get(1).orEmpty()
        val nums = Regex("""\d+""").findAll(statLine).mapNotNull { it.value.toIntOrNull() }.toList()

        return Info(
            csrfToken = csrf,
            avatarUrl = avatar,
            username = unescape(name).trim(),
            email = unescape(email).trim(),
            userId = uid,
            joinedLabel = joined,
            subscriberCount = nums.getOrElse(0) { 0 },
            videoCount = nums.getOrElse(1) { 0 },
            emailEditable = emailEditable,
        )
    }

    private fun postForm(path: String, fields: Map<String, String>, csrf: String): String {
        val fb = FormBody.Builder()
        fields.forEach { (k, v) -> fb.add(k, v) }
        val req = Request.Builder()
            .url(if (path.startsWith("http")) path else "$BASE$path")
            .post(fb.build())
            .header("User-Agent", com.javrocket.hanime.HanimeClient.UA)
            .header("Referer", if (path.startsWith("http")) path else "$BASE$path")
            .header("Origin", BASE)
            .header("X-CSRF-TOKEN", csrf)
            .build()
        http().newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            // 302 = Laravel 成功重定向；2xx 也可能（取决于版本）
            if (r.code != 302 && !r.isSuccessful) {
                throw IllegalStateException("提交失败：HTTP " + r.code + formError(body))
            }
            return body
        }
    }

    /** 从响应体里挑出站点的表单错误（参考实现 Parser.extractFormError 的同款选择器）。 */
    private fun formError(html: String): String {
        for (cls in listOf("alert-danger", "invalid-feedback", "text-danger", "help-block", "error-message")) {
            val pat = buildString {
                append("class=")
                append(0x22.toChar())
                append("[^")
                append(0x22.toChar())
                append("]*")
                append(cls)
                append("[^")
                append(0x22.toChar())
                append("]*")
                append(0x22.toChar())
                append("[^>]*>([^<]{2,200})<")
            }
            val m = Regex(pat).find(html)
            if (m != null) {
                val t = unescape(m.groupValues[1]).trim()
                if (t.isNotEmpty()) return "：" + t
            }
        }
        return ""
    }

    /** 改昵称（邮箱站点侧只读，不提交）。 */
    fun updateName(info: Info, newName: String) {
        val n = newName.trim()
        require(n.isNotEmpty()) { "昵称不能为空" }
        postForm(
            "/user/" + info.userId,
            mapOf(
                "_token" to info.csrfToken,
                "_method" to "patch",
                "type" to "profile",
                "name" to n,
            ),
            info.csrfToken,
        )
    }

    /** 改密码。 */
    fun updatePassword(info: Info, oldPw: String, newPw: String, confirmPw: String) {
        require(oldPw.isNotEmpty() && newPw.isNotEmpty()) { "旧密码与新密码都要填" }
        require(newPw == confirmPw) { "两次输入的新密码不一致" }
        postForm(
            "/user/" + info.userId,
            mapOf(
                "_token" to info.csrfToken,
                "_method" to "patch",
                "type" to "password",
                "password_old" to oldPw,
                "password_new" to newPw,
                "password_new_confirm" to confirmPw,
            ),
            info.csrfToken,
        )
    }

    /**
     * 换头像（multipart，字段名固定 `photo`，MediaType image/jpeg）。
     *
     * 注意参考实现这一路**不发 X-CSRF-TOKEN 头**，只把 `_token` 当普通 part 提交 ——
     * 照抄，不要自作聪明加头（加了不影响，但不必要）。
     */
    fun updateAvatar(info: Info, jpegBytes: ByteArray, fileName: String = "avatar.jpg") {
        require(jpegBytes.isNotEmpty()) { "图片是空的" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("_token", info.csrfToken)
            .addFormDataPart("_method", "patch")
            .addFormDataPart("type", "photo")
            .addFormDataPart(
                "photo", fileName,
                jpegBytes.toRequestBody("image/jpeg".toMediaType()),
            )
            .build()
        val url = "$BASE/user/" + info.userId
        val req = Request.Builder()
            .url(url).post(body)
            .header("User-Agent", com.javrocket.hanime.HanimeClient.UA)
            .header("Referer", url)
            .header("Origin", BASE)
            .build()
        http().newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (r.code != 302 && !r.isSuccessful) {
                throw IllegalStateException("头像上传失败：HTTP " + r.code + formError(txt))
            }
        }
    }
}
