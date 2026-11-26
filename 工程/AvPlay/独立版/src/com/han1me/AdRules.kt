package com.han1me

/**
 * AdGuard 模块：规则文件随 jar 打包（`hplay-adrules.txt`），内置浏览器打开页面时按域名筛选后注入。
 *
 * 规则形态（沿用 AdGuard / EasyList 写法）：
 *   `!ads=<正则>`        广告域名正则（识别广告图 / 广告 iframe）
 *   `selector`           通用元素隐藏规则
 *   `domain##selector`   按域名元素隐藏规则
 *
 * 说明：受 WebView2 封装接口限制，本模块只实现「元素隐藏」子集（网络请求拦截不可用），
 * 规则取自 EasyList Adult（adult_specific_hide / adult_adservers / adult_thirdparty）+ 实测补充。
 */
object AdRules {
    private var loaded = false
    private var generic: List<String> = emptyList()
    private var byDomain: List<Pair<String, String>> = emptyList()

    @Volatile
    var adsRegex: String = ""
        private set

    @Synchronized
    private fun load() {
        if (loaded) return
        runCatching {
            val txt = javaClass.getResourceAsStream("/hplay-adrules.txt")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return@runCatching
            val g = ArrayList<String>()
            val d = ArrayList<Pair<String, String>>()
            for (line0 in txt.lineSequence()) {
                val line = line0.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("!")) {
                    if (line.startsWith("!ads=")) adsRegex = line.removePrefix("!ads=").trim()
                    continue
                }
                val i = line.indexOf("##")
                if (i < 0) g.add(line) else d.add(line.substring(0, i) to line.substring(i + 2))
            }
            generic = g
            byDomain = d
            loaded = true   // 解析并发布完才置位：先置位会让并发首读者拿到空表（广告域名正则静默失效）
        }
    }

    /** 规则条数（通用 + 按域名），供界面/记录展示。 */
    fun ruleCount(): Int {
        load()
        return generic.size + byDomain.size
    }

    /** 广告域名正则（可能很长，注入时由调用方截断）。 */
    fun adsRegexOrEmpty(): String {
        load()
        return adsRegex
    }

    /** 当前页面要注入的选择器文本（通用规则 + 命中该域名的规则）。 */
    fun cssFor(host: String): String {
        load()
        val sel = ArrayList<String>(generic)
        for ((dom, s) in byDomain) {
            val hit = dom.split(",").any { seg ->
                val d = seg.trim()
                d.isNotEmpty() && (host.endsWith(d, true) || host.contains(d, true))
            }
            if (hit) sel.add(s)
        }
        return sel.joinToString(",")
    }
}
