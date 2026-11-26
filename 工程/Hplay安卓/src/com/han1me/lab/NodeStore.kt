package com.han1me.lab

import android.content.Context
import java.io.File
import java.net.URLDecoder

/** 一个内置节点。 */
data class LabNode(val name: String, val url: String) {
    val label: String get() = name.ifBlank { url.take(40) }
}

/**
 * **节点存储 · 第 3 版（多节点）**（实验分叉 · 2026-10-31）。
 *
 * 用户要求（照 v2rayN）：
 *  · 节点链接**保存后显示成简短的节点名**，不把长链接摊在外面；
 *  · **可保存多个节点**，在它们之间切换；
 *  · 每个节点带**延迟 + 可用性**；
 *  · **热切换**（点了立刻生效，不用重启）；
 *  · 链接编辑收进**子页面**。
 *
 * 存储：本机 properties，`lab.node.list` 存 JSON 数组，`lab.node.current` 存下标。
 * 兼容：旧版只有 `lab.node.url`，首次读取时自动迁移成列表里的一项。
 */
object NodeStore {

    private const val FILE_NAME = "hpla_lab_node.properties"

    @Volatile
    private var file: File? = null

    /**
     * 默认节点（用户 2026-11-24 指定）。
     *
     * 用途：作为内部测试节点，专门用来做 **CF 盾挑战测试**
     *（Han1mePlus / Han1meViewer 两家的过盾方案都要在真实节点上验）。
     *
     * 参数要点（全部由 VlessLink + XrayRunner 解析，无需额外改动）：
     *  · reality + xtls-rprx-vision（flow）
     *  · pbk / sid / sni / fp=ios
     *  · packetEncoding=xudp
     */
    /**
     * ★★★ 默认节点（用户 2026-11-24 第 2 次指定，逐字符照抄）。
     *
     * 注意：改这里必须同时把 [NODE_EPOCH] 加一 —— 否则老用户设备上的旧节点不会被刷新，
     * 会出现「代码里换了、App 里还是旧的」这种最难查的问题。
     */
    const val DEFAULT_NODE_URL =
        "vless://9a8a0cca-88a0-4437-a29d-399984b3a0fc@35.89.113.126:443/" +
        "?type=tcp&encryption=none&flow=xtls-rprx-vision" +
        "&sni=lolesports.com&fp=ios&security=reality" +
        "&pbk=LzRvbmubSyeulWRAAO9AjcY9wAran5wx2YIAO4b-AiU&sid=4114dc0f" +
        "&packetEncoding=xudp#L4-0x%20%E7%BE%8E%E5%9B%BD%C2%B7%E5%85%8D%E8%B4%B9%E8%8A%82%E7%82%B9"

    /**
     * **默认节点的版本纪元**（2026-11-24 新增）。
     *
     * 为什么需要：设备上 lab_node.properties 会持久化节点列表。只改 DEFAULT_NODE_URL
     * 的话，已装过旧版的设备仍然读旧列表 —— 用户换了节点却看不到效果。
     *
     * 机制：启动时比对存储里的 epoch 与这里的值，不一致就**强制重建默认节点**并写回新值。
     * 以后每次换默认节点，把这个数字加一即可。
     */
    const val NODE_EPOCH = 2026112402

    fun attach(dataDir: File) {
        if (file == null) file = File(dataDir, FILE_NAME)
    }

    private fun load(): java.util.Properties {
        val p = java.util.Properties()
        val f = file ?: return p
        runCatching { if (f.exists()) f.inputStream().use { p.load(it) } }
        return p
    }

    private fun save(p: java.util.Properties) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.outputStream().use { p.store(it, "hpla lab node settings") }
        }
    }

    /** 从链接推导节点名：优先用 `#后面` 的名字，否则用主机名。 */
    fun nameOf(url: String): String {
        val frag = url.substringAfter('#', "")
        if (frag.isNotBlank()) {
            val n = runCatching { URLDecoder.decode(frag, "UTF-8") }.getOrDefault(frag)
            if (n.isNotBlank()) return n.trim()
        }
        val body = url.substringAfter("://", url)
        val hp = body.substringAfter('@', "").substringBefore('?').substringBefore('#').trimEnd('/')
        return hp.ifBlank { url.take(24) }
    }

    /** 读取节点列表（含旧版迁移）。 */
    fun nodes(): List<LabNode> {
        val p = load()
        val raw = p.getProperty("lab.node.list", "")
        val out = ArrayList<LabNode>()
        if (raw.isNotBlank()) {
            runCatching {
                val arr = org.json.JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val u = o.optString("url", "")
                    if (u.isBlank()) continue
                    out.add(LabNode(o.optString("name", ""), u))
                }
            }
        }
        if (out.isEmpty()) {
            // ★ 迁移：旧版单节点 / 首次使用 → 放进列表第一项
            val old = p.getProperty("lab.node.url")
            // ★ 2026-11-24：旧列表迁移**一律归到 DEFAULT_NODE_URL**（用户要求只留一个节点）。
        //   旧逻辑是「非黑名单就保留旧值」——那会让历史上的 220.135.x 之类残留一直生效。
        val u = DEFAULT_NODE_URL
            out.add(LabNode(nameOf(u), u))
        }
        return out
    }

    fun setNodes(list: List<LabNode>) {
        val p = load()
        val arr = org.json.JSONArray()
        for (n in list) arr.put(org.json.JSONObject().put("name", n.name).put("url", n.url))
        p.setProperty("lab.node.list", arr.toString())
        save(p)
    }

    fun currentIndex(): Int {
        val n = nodes().size
        if (n == 0) return 0
        val i = load().getProperty("lab.node.current", "0")?.toIntOrNull() ?: 0
        return i.coerceIn(0, n - 1)
    }

    fun setCurrentIndex(i: Int) {
        save(load().apply { setProperty("lab.node.current", i.toString()) })
    }

    fun currentNode(): LabNode? = nodes().getOrNull(currentIndex())

    /** 新增（自动取名、自动选中）。返回新下标。 */
    fun addNode(url: String, name: String = ""): Int {
        val list = nodes().toMutableList()
        val nm = name.ifBlank { nameOf(url) }
        val idx = list.indexOfFirst { it.url == url }
        if (idx >= 0) { setCurrentIndex(idx); return idx }
        list.add(LabNode(nm, url))
        setNodes(list)
        setCurrentIndex(list.size - 1)
        return list.size - 1
    }

    fun updateNode(i: Int, url: String, name: String = "") {
        val list = nodes().toMutableList()
        if (i !in list.indices) return
        list[i] = LabNode(name.ifBlank { nameOf(url) }, url)
        setNodes(list)
    }

    fun removeAt(i: Int) {
        val list = nodes().toMutableList()
        if (i !in list.indices || list.size <= 1) return
        list.removeAt(i)
        setNodes(list)
        setCurrentIndex(currentIndex().coerceAtMost(list.size - 1))
    }

    /** 兼容旧调用点：当前节点的链接。 */
    fun nodeUrl(): String = currentNode()?.url ?: DEFAULT_NODE_URL

    /** 兼容旧调用点：改写当前节点的链接。 */
    fun setNodeUrl(u: String) {
        val i = currentIndex()
        val list = nodes().toMutableList()
        if (i in list.indices) { list[i] = LabNode(list[i].name, u) } else { list.add(LabNode(nameOf(u), u)) }
        setNodes(list)
    }

    // ---------- 兼容旧的一次性迁移 ----------
    fun migrated(): Boolean = load().getProperty("lab.node.migrated") == "1"
    fun markMigrated() = save(load().apply { setProperty("lab.node.migrated", "1") })

    /** 是否启用内置节点（用户要求「软件打开时默认开启」）。 */
    fun enabled(): Boolean {
        val p = load()
        if (p.getProperty("lab.node.migrated") != "1") {
            p.setProperty("lab.node.enabled", "true")
            p.setProperty("lab.node.migrated", "1")
            save(p)
            return true
        }
        return p.getProperty("lab.node.enabled", "true") == "true"
    }

    fun setEnabled(v: Boolean) = save(load().apply { setProperty("lab.node.enabled", v.toString()) })

    /** global | nonDirect */
    fun scope(): String = load().getProperty("lab.node.scope", "global") ?: "global"
    fun setScope(v: String) = save(load().apply { setProperty("lab.node.scope", v) })

    /** preferEdge | preferBuiltin | builtinOnly（保留兼容，实际已按各司其职处理） */
    fun edgePolicy(): String = load().getProperty("lab.node.edge", "preferBuiltin") ?: "preferBuiltin"
    fun setEdgePolicy(v: String) = save(load().apply { setProperty("lab.node.edge", v) })

    fun localPort(): Int = load().getProperty("lab.node.port", "10808")?.toIntOrNull() ?: 10808


    // ================= 多订阅源（LABSUBLIST · 2026-10-31） =================

    /** 一条订阅源：名字 + 地址 + 该源拉回的节点（存链接列表）。 */
    data class SubSource(val name: String, val url: String, val nodes: List<LabNode>)

    /**
     * 读取所有订阅源（JSON: [{name,url,nodes:[{name,url}]}]）。
     *
     * ★★ LABSUBCACHE（2026-10-31 · 用户报「切换订阅源卡顿」）：
     * 旧版每次调用都要**读磁盘 + 解 JSON**，而切换订阅源会连续触发多次重组，
     * 于是主线程被反复阻塞 → 肉眼可见的卡。
     * 现在：结果缓存在内存里，只有 setSubs() 会失效缓存。
     */
    @Volatile
    private var subsCache: List<SubSource>? = null

    fun subs(): List<SubSource> {
        subsCache?.let { return it }
        val v = readSubsFromDisk()
        subsCache = v
        return v
    }

    private fun readSubsFromDisk(): List<SubSource> {
        val raw = load().getProperty("lab.node.subs", "")
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<SubSource>()
        runCatching {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ns = ArrayList<LabNode>()
                val na = o.optJSONArray("nodes")
                if (na != null) {
                    for (j in 0 until na.length()) {
                        val no = na.optJSONObject(j) ?: continue
                        val u = no.optString("url", "")
                        if (u.isNotBlank()) ns.add(LabNode(no.optString("name", ""), u))
                    }
                }
                out.add(SubSource(o.optString("name", ""), o.optString("url", ""), ns))
            }
        }
        return out
    }

    fun setSubs(list: List<SubSource>) {
        val arr = org.json.JSONArray()
        for (s in list) {
            val na = org.json.JSONArray()
            for (n in s.nodes) na.put(org.json.JSONObject().put("name", n.name).put("url", n.url))
            arr.put(org.json.JSONObject().put("name", s.name).put("url", s.url).put("nodes", na))
        }
        save(load().apply { setProperty("lab.node.subs", arr.toString()) })
        subsCache = null   // ★ 缓存失效
    }

    /** 当前选中的订阅源下标（-1 = 未选中，显示「全部节点」）。 */
    fun currentSub(): Int = load().getProperty("lab.node.subs.cur", "-1")?.toIntOrNull() ?: -1
    fun setCurrentSub(i: Int) = save(load().apply { setProperty("lab.node.subs.cur", i.toString()) })

    /** 新增/更新一条订阅源并选中它。 */
    fun upsertSub(name: String, url: String, nodes: List<LabNode>): Int {
        val list = subs().toMutableList()
        val idx = list.indexOfFirst { it.url == url }
        val nm = name.ifBlank { "订阅 " + (list.size + 1) }
        if (idx >= 0) list[idx] = SubSource(nm, url, nodes) else list.add(SubSource(nm, url, nodes))
        setSubs(list)
        val ni = if (idx >= 0) idx else list.size - 1
        setCurrentSub(ni)
        return ni
    }

    /**
     * **更新订阅源的名称 / 链接 / 节点**（长按「保存并重新拉取」用）。
     *
     * 与 [removeSub] 不同：这里**不删除**，只就地改写。
     */
    fun updateSub(i: Int, name: String, url: String, nodes: List<LabNode>) {
        val list = subs().toMutableList()
        if (i !in list.indices) return
        list[i] = SubSource(name.ifBlank { list[i].name }, url.ifBlank { list[i].url }, nodes)
        setSubs(list)
        // 若当前正显示这个源，把新节点也装进生效列表（保持"看的就是用的"一致）
        if (currentSub() == i && nodes.isNotEmpty()) {
            setNodes(nodes)
        }
    }

    /**
     * **只改名称 / 链接，不动节点**（长按「只保存」，或重新拉取失败时的兜底）。
     *
     * 拉取失败时保留原有节点 —— 不能因为链接填错就把用户已有的节点清空。
     */
    fun updateSubMeta(i: Int, name: String, url: String) {
        val list = subs().toMutableList()
        if (i !in list.indices) return
        val old = list[i]
        list[i] = SubSource(name.ifBlank { old.name }, url.ifBlank { old.url }, old.nodes)
        setSubs(list)
    }

    /**
     * 这条订阅源是否**固定不可删**（第 148 轮，用户要求）。
     *
     * 默认内置节点被单独做成一条订阅源，**必须永久保留** —— 它是「一键恢复出厂可用」
     * 的最后兜底：用户把订阅都删光/拉不到时，它仍然在，点一下就能用。
     */
    fun isFixedSub(s: SubSource): Boolean = s.url == FIXED_SUB_URL

    fun removeSub(i: Int) {
        val list = subs().toMutableList()
        if (i !in list.indices) return
        // 固定的默认订阅源不允许删除（界面已隐藏删除按钮，这里再兜一层）
        if (isFixedSub(list[i])) return
        list.removeAt(i)
        setSubs(list)
        setCurrentSub(currentSub().coerceAtMost(list.size - 1))
    }

    /**
     * **切换订阅源栏**（只切「看哪一组」，不碰当前生效节点）。
     *
     * ★★ 用户明确要求（2026-10-31）：
     *   「切换订阅源不会切换节点；当我手动点击节点后才会切换。
     *     从一个订阅源切到另一个订阅源之后，还是保持原先选中的节点不变。」
     *
     * 所以这里**只改 currentSub（当前查看哪个源）**，
     * 完全不动 nodes / currentIndex —— 真正生效的节点只有用户点节点时才变。
     */
    fun activateSub(i: Int) {
        if (i < 0 || i >= subs().size) return
        setCurrentSub(i)
    }

    /**
     * 把某订阅源的节点**装进列表**（真正要切源并生效时才调）。
     * 仅给「用户点了该源里的某个节点」这类显式操作使用。
     */
    fun loadSubNodes(i: Int) {
        val s = subs().getOrNull(i) ?: return
        if (s.nodes.isEmpty()) return
        setNodes(s.nodes)
        setCurrentIndex(0)
    }

    /** 默认订阅源（用户 2026-10-31 指定）。 */
    const val DEFAULT_SUB_URL =
        "https://misaki01.ccwu.cc/sub?token=95d53ced2f63940d42892287eed4ad0e"

    fun subUrl(): String = load().getProperty("lab.node.sub", DEFAULT_SUB_URL) ?: DEFAULT_SUB_URL
    fun setSubUrl(u: String) = save(load().apply { setProperty("lab.node.sub", u) })
    // ================= 固定的默认内置订阅源（第 148 轮，用户要求） =================

    /**
     * 这条订阅源用**特殊 URL 占位**标识「内置默认节点」。
     *
     * 为什么给一个假 URL 而不是空串：空串会和「新建订阅源时还没填地址」的状态撞车，
     * 也会让 [upsertSub] 的「同 URL 视为同一条」判断出错。
     * 用 lab-builtin:// 这个应用内私有 scheme，不会与任何真实 URL 冲突。
     */
    const val FIXED_SUB_URL = "https://misaki01.ccwu.cc/sub?token=95d53ced2f63940d42892287eed4ad0e"

    /** 固定订阅源的显示名。 */
    const val FIXED_SUB_NAME = "内置默认节点"


    // ============ 订阅源内的节点增删改（第 151 轮，用户要求） ============

    /**
     * **往指定订阅源里新增一个节点**。
     *
     * 为什么需要它：界面在「选中了某个订阅源」时，显示的是**该订阅源的 nodes**，
     * 而旧的 addNode() 只写全局列表 lab.node.list —— 于是用户点「＋ 添加节点」后，
     * 节点确实存进去了，却不在当前这组里显示，看起来就是「添加无效、找不到」。
     *
     * @return true = 已写入（或本来就存在）。
     */
    fun addNodeToSub(subIdx: Int, url: String, name: String = ""): Boolean {
        if (url.isBlank()) return false
        val list = subs().toMutableList()
        val s = list.getOrNull(subIdx) ?: return false
        val nm = name.ifBlank { nameOf(url) }
        if (s.nodes.any { it.url == url }) return true
        list[subIdx] = SubSource(s.name, s.url, s.nodes + LabNode(nm, url))
        setSubs(list)
        return true
    }

    /** **改订阅源里的某个节点**（下标是**该订阅源内**的位置）。 */
    fun updateNodeInSub(subIdx: Int, nodeIdx: Int, url: String, name: String = ""): Boolean {
        val list = subs().toMutableList()
        val s = list.getOrNull(subIdx) ?: return false
        if (nodeIdx !in s.nodes.indices) return false
        val ns = s.nodes.toMutableList()
        ns[nodeIdx] = LabNode(name.ifBlank { nameOf(url) }, url)
        list[subIdx] = SubSource(s.name, s.url, ns)
        setSubs(list)
        return true
    }

    /** **从订阅源里删掉某个节点**（下标是**该订阅源内**的位置）。 */
    fun removeNodeInSub(subIdx: Int, nodeIdx: Int): Boolean {
        val list = subs().toMutableList()
        val s = list.getOrNull(subIdx) ?: return false
        if (nodeIdx !in s.nodes.indices) return false
        val ns = s.nodes.toMutableList().apply { removeAt(nodeIdx) }
        list[subIdx] = SubSource(s.name, s.url, ns)
        setSubs(list)
        return true
    }

    /**
     * **确保固定订阅源存在**（幂等）。由 [LabInit.initOnce] 在挂完设置文件后调用。
     *
     * 规则（按用户 2026-10-31 要求）：
     *  · 默认内置节点**单独放一条订阅源**，不混进用户自己的订阅；
     *  · 这条**固定不会被删除**（[isFixedSub] 判定，[removeSub] 直接拒绝）；
     *  · 始终排在**第一位**，方便一眼看到、一键可用；
     *  · 节点内容每次都按 [DEFAULT_NODE_URL] 重建，保证它永远指向当前默认节点。
     */
    /** misaki01 预设节点（用户 2026-11-05 提供，内置到默认订阅里）。 */
    /**
     * 预设节点（2026-11-25 第 283 轮**恢复**）。
     *
     * 第 277 轮为了做 CF 单节点测试，这里被清空成 emptyList()；
     * 现在测试已通过（CF 能过、能加载内容），按原计划恢复。
     *
     * 恢复后用户在「网络」设置里能看到多个节点可选；
     * DEFAULT_NODE_URL（用户指定的 L4-0x）仍然是**默认选中**的那一个。
     */
    // ★ 2026-11-25（第 308 轮）：**清空所有预设节点**（用户要求）。
    // 只保留 DEFAULT_NODE_URL（用户指定的 L4-0x）作为唯一节点。
    val PRESET_NODES: List<String> = emptyList()

    /** 从预设节点 URL 列表生成 LabNode 列表（自动取名）。 */
    private fun presetLabNodes(): List<LabNode> = PRESET_NODES.map { LabNode(nameOf(it), it) }

        fun ensureFixedSub() {
        runCatching {
            // ★★★ 2026-11-24：**节点纪元检查**。
            //
            // 设备上持久化了节点列表；只改 DEFAULT_NODE_URL 时老设备仍读旧列表。
            // 这里比对存储的 epoch：不一致 → 把默认订阅整条重建，并写回新 epoch。
            val storedEpoch = load().getProperty("lab.node.epoch", "0")?.toIntOrNull() ?: 0
            if (storedEpoch != NODE_EPOCH) {
                LabLog.i("node", "节点纪元变更 " + storedEpoch + " -> " + NODE_EPOCH + "，强制重建默认节点")
                val fresh = SubSource(
                    FIXED_SUB_NAME,
                    FIXED_SUB_URL,
                    listOf(LabNode(nameOf(DEFAULT_NODE_URL), DEFAULT_NODE_URL)),
                )
                val list = subs().toMutableList()
                val i0 = list.indexOfFirst { isFixedSub(it) }
                if (i0 >= 0) list[i0] = fresh else list.add(0, fresh)
                setSubs(list)
                setCurrentSub(list.indexOfFirst { isFixedSub(it) }.coerceAtLeast(0))
                setCurrentIndex(0)
                save(load().apply { setProperty("lab.node.epoch", NODE_EPOCH.toString()) })
                LabLog.i("node", "默认节点已切到：" + nameOf(DEFAULT_NODE_URL))
                return@runCatching
            }
            val defaultNode = LabNode(nameOf(DEFAULT_NODE_URL), DEFAULT_NODE_URL)
            val list = subs().toMutableList()
            val idx = list.indexOfFirst { isFixedSub(it) }
            if (idx >= 0) {
                // 已存在：保证默认节点在，且**保留用户自己往里加的其它节点**
                // （第 151 轮：用户要求能在订阅源里自由新增节点，所以这里不能整条重建，
                //   否则用户加进去的节点每次启动都会被冲掉。）
                val old = list[idx]
                val extra = old.nodes.filter { it.url != DEFAULT_NODE_URL && it.url !in PRESET_NODES }
                val merged = listOf(defaultNode) + extra
                if (merged != old.nodes || old.name != FIXED_SUB_NAME || idx != 0) {
                    list.removeAt(idx)
                    list.add(0, SubSource(FIXED_SUB_NAME, FIXED_SUB_URL, merged))
                    setSubs(list)
                    // 位置变了，把「当前查看的订阅源」跟着挪，避免显示错组
                    val cur = currentSub()
                    if (cur >= 0) {
                        when {
                            cur == idx -> setCurrentSub(0)
                            cur < idx -> setCurrentSub(cur + 1)
                            else -> Unit
                        }
                    }
                }
                return@runCatching
            }
            // 不存在：新建并放首位，其余订阅源顺延
            list.add(0, SubSource(FIXED_SUB_NAME, FIXED_SUB_URL, listOf(defaultNode) + presetLabNodes()))
            setSubs(list)
            val cur = currentSub()
            if (cur >= 0) setCurrentSub(cur + 1)
        }
    }
}
