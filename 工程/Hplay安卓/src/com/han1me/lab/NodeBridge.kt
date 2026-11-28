package com.han1me.lab

import android.content.Context
import com.javrocket.hanime.HanimeClient

/**
 * **内置节点 ↔ 主线网络栈的桥**（实验分叉 · 第 2 版）。
 *
 * ★★ 第 2 版修「开启后退出再进 App 卡死」（用户报的严重问题）：
 *
 * 根因：NodeBridge.apply() 是 @Synchronized，内部同步调 XrayRunner.start()（最多 15 秒）。
 *  后台恢复线程持锁时，UI 线程点开关也要拿同一把锁 ⇒ **UI 线程被阻塞 15 秒 ⇒ ANR**。
 *
 * 修法三条：
 *  ① **apply 不再持锁跑慢操作** —— 只留一个 AtomicBoolean 防重入，慢活放后台；
 *  ② **UI 永不等待**：从 Composable 调 apply 一律走后台线程，界面立刻更新为「启动中」；
 *  ③ 启动结果通过 NodeState 异步回传，UI 轮询/重组即可。
 */
object NodeBridge {

    private var savedMode: String? = null
    private var savedManual: String? = null
    private var savedDoh: Boolean? = null
    private var savedEdge: Boolean? = null
    private var savedGlobal: Boolean? = null

    /** 防重入（替代原来的 @Synchronized，避免 UI 线程被锁住）。 */
    private val applying = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    var activeHostPort: String = ""
        private set

    @Volatile
    var activeScope: String = "global"
        private set

    val active: Boolean get() = activeHostPort.isNotBlank()

    /**
     * **异步应用**：立即返回，慢活在后台线程。
     *
     * UI 调用这个，永远不会阻塞主线程。
     */
    fun applyAsync(ctx: Context) {
        if (applying.getAndSet(true)) return   // 已有任务在跑，直接返回
        NodeState.lastApplied = "启动中…"
        Thread({
            try {
                applyBlockingInner(ctx)
            } finally {
                applying.set(false)
            }
        }, "hpla-lab-node-apply").apply { isDaemon = true }.start()
    }

    /** **同步应用**（仅限后台线程调用，禁止在主线程调）。 */
    fun applyBlockingInner(ctx: Context) {
        // 内部实现（供 applyAsync / hotSwitchAsync 共用）
        runCatching {
            if (!NodeStore.enabled()) {
                revokeBlocking()
                return
            }
            LabLog.i("bridge", "开始启动内核…")
            val (ok, msg) = XrayRunner.start(ctx)
            LabLog.i("bridge", "启动结果 ok=" + ok + " msg=" + msg)
            if (!ok) {
                revokeBlocking()
                NodeState.lastApplied = "启动失败（已回落边缘节点）: " + msg
                return
            }
            val port = NodeStore.localPort()
            val policy = NodeStore.edgePolicy()
            val scope = NodeStore.scope()
            if (savedMode == null) {
                savedMode = HanimeClient.proxyMode
                savedManual = HanimeClient.manualProxy
                savedGlobal = HanimeClient.globalProxyEnabled
                savedDoh = HanimeClient.dohEnabled
                savedEdge = HanimeClient.edgeHostsEnabled
            }
            activeHostPort = "socks5://127.0.0.1:" + port
            activeScope = scope
            // ★★ LABGLOBAL（2026-10-31）：先挂**全局选择器** —— 立刻对所有网络接口生效，
            //   然后才改主线设置。这一步是「开启内置后立刻运行加载」的关键：
            //   否则要等下次构件客户端才生效。
            GlobalProxy.set(activeHostPort, scope)
            LabLog.i("bridge", "GlobalProxy 已挂: " + activeHostPort + " scope=" + scope)
            // ★ 第 356 轮：**挂载之后再打路由自检**。
            // 上一版把它放在 LabInit 启动流程里，早于内核挂载 —— 打出来是 no-node，误导诊断。
            runCatching { GlobalProxy.dumpRoutes() }
            // ★★★ 第 168 轮（用户要求「有 root 就全方位优化，没有也不影响」）：
            //
            // GlobalProxy 只覆盖 OkHttp（原生解析 / 下载）。**WebView 的网页播放走不到它** ——
            // WebView 不支持 SOCKS，而 ProxyController 是 @SystemApi 普通应用用不了。
            // 用户实测现象：「浏览器能播的视频，在内置网页里播不了」。
            //
            // 有 root 时用 `settings put global http_proxy` 让**整个系统**走 Xray 的 HTTP 入站，
            // 网页播放因此也能通；没 root 就静默跳过（本函数自己判定，不影响其它板块）。
            runCatching { HplayRootBoost.applySystemProxy(port + 1) }
            // ★★ LABSPLIT（2026-10-31 · 真机 403 实测后修正架构）：
            //
            // 【不再一刀切走代理，改为各司其职】
            //
            // 真机实测证据：
            //   · 经内置节点访问 hanime1.me → **403**（Cf-Mitigated: challenge，
            //     Cloudflare 人机验证 —— 免费节点是机房 IP，被判可疑）；
            //   · 直连 Cloudflare 边缘 IP + SNI → **200**（104.19.0.1 / 172.64.229.154）。
            //
            // 所以：
            //   · **非官方域名** → GlobalProxy 接管，走内置节点（谷歌/板块/优选源/下载）；
            //   · **hanime 官方源** → 绕过内置节点（见 proxyFor），走**边缘节点**。
            //
            // 关键：这里**不能**把 proxyMode 设成 manual ——
            // 否则官方源也会被塞进手工代理，边缘节点就白开了。
            runCatching { HanimeClient.reprobeProxy() }
            // ★★ LABSPEED（2026-10-31 · 启动提速）：**不在启动路径上测速**。
            //
            // 真机日志显示：旧版在这里同步调 pickFastestNode() 做边缘节点测速，
            // **整整阻塞 21 秒**（21:43:15 启动 → 21:43:37 才测完才继续），
            // 这就是「内置节点启动慢」的主因。
            //
            // 现在：内置节点模式下**不需要**边缘节点（用户要求两者互斥），
            // 所以启动时干脆不碰它 —— 内核 0.3 秒起来就能用。
            // 边缘节点的测速仍然保留在它自己的面板里，用户手动点「测速」时才跑。
            LabUi.bump()   // ★ 通知所有面板实时刷新
            // ★★ 用户要求（2026-10-31）：「进入应用后默认启动内置节点，边缘节点保持关闭」。
            // 内置一启用就把边缘关掉，保证两者不同时开。
            runCatching { HanimeClient.updateEdgeHosts(false, "") }
            LabUi.bump()
            // DoH 关掉：流量已走 SOCKS，DNS 由代理解析更优
            runCatching { HanimeClient.updateSettings(doh = false) }
            // 重新探测一次：proxyInUse() 变了，界面状态要跟上
            runCatching { HanimeClient.reprobeProxy() }
            NodeState.lastApplied = "已启用 · " + msg + " · 边缘策略=" + policy
        }.onFailure {
            LabLog.i("bridge", "应用失败: " + (it.message ?: ""))
            NodeState.lastApplied = "应用失败: " + (it.message ?: "")
        }
    }

    /**
     * **热切换**（v2rayN 那种：点一下就换节点，立刻生效）。
     *
     * 做法：直接走 applyAsync 的完整流程 —— 它会先 stop 旧内核、再按新节点起内核、
     * 最后重挂 GlobalProxy。对已经建立的连接会短暂中断（1~3 秒），这是热切换的固有代价。
     */
    fun hotSwitchAsync(ctx: Context, onDone: () -> Unit) {
        // ★★ LABDEBOUNCE（2026-10-31）：**切换防抖**。
        //
        // 用户在列表上连点几个节点时，旧版会排队触发多次「停内核→起内核」，
        // 每次都重建整条 TLS 隧道 —— 既慢又容易出现「起来又被停」的抖动。
        // 这里：正在切换时直接忽略后续请求（只保留第一次），界面会看到「切换中…」。
        if (applying.getAndSet(true)) {
            onDone()
            return
        }
        NodeState.lastApplied = "切换中…"
        LabUi.bump()
        Thread({
            try {
                XrayRunner.stop()
                GlobalProxy.clear()
                // 第 168 轮：**必须还原系统代理**，否则会残留在系统里影响其它 App
                runCatching { HplayRootBoost.clearSystemProxy() }
                applyBlockingInner(ctx)
            } finally {
                applying.set(false)
                LabUi.bump()   // 切完立刻让所有面板刷新
                runCatching { onDone() }
            }
        }, "hpla-lab-node-switch").apply { isDaemon = true }.start()
    }

    /** 异步关闭（同样不阻塞主线程）。 */
    fun revokeAsync() {
        if (applying.getAndSet(true)) return
        NodeState.lastApplied = "正在关闭…"
        Thread({
            try {
                revokeBlocking()
            } finally {
                applying.set(false)
            }
        }, "hpla-lab-node-revoke").apply { isDaemon = true }.start()
    }

    /** **同步关闭**（仅限后台线程）。 */
    fun revokeBlocking() {
        runCatching {
            XrayRunner.stop()
            activeHostPort = ""
            // ★★ LABGLOBAL：撤销全局选择器（立刻回到直连/原设置）
            runCatching { GlobalProxy.clear() }
            runCatching { HplayRootBoost.clearSystemProxy() }
            val m = savedMode
            if (m != null) {
                HanimeClient.updateSettings(
                    mode = m,
                    manual = savedManual ?: "",
                    globalProxy = savedGlobal ?: true,
                    doh = savedDoh ?: true,
                )
                HanimeClient.updateEdgeHosts(savedEdge ?: true, "")
                LabUi.bump()   // ★ 通知所有面板实时刷新
            }
            savedMode = null; savedManual = null; savedGlobal = null
            savedDoh = null; savedEdge = null
            NodeState.lastApplied = "已关闭（已还原主线网络设置）"
        }
    }

    /**
     * 第 232 轮（用户批准方案 B）：双向同步巡检 —— 纯应用内，零系统网络修改。
     *
     * 修的是两台设备气泡行为差异的根因：GlobalProxy 的挂载状态与 Xray 引擎实际存活脱节。
     *  - LG：引擎活 + 已挂载 → 气泡走原站 API，秒开
     *  - Pixel：引擎活但未挂载 → 判定"无节点"→ 走 jptt 直连 fallback，20s+
     *
     * 规则（每 15 秒一次，只用 App 内 API）：
     *  · 用户意图开 + 未挂载 + 端口无监听 → 先尝试自启引擎
     *  · 用户意图开 + 未挂载 + 端口有监听 → 重新挂载 GlobalProxy（复活）
     *  · 用户意图关 → 不动作（尊重用户选择）
     *
     * 全程不调用任何系统命令、不读不写系统 settings。
     */
    fun startSyncLoop(ctx: android.content.Context) {
        Thread({
            while (true) {
                runCatching {
                    val intended = NodeStore.enabled()
                    if (intended && !GlobalProxy.active) {
                        val port = NodeStore.localPort()
                        fun alive(): Boolean = runCatching {
                            java.net.Socket().use { s ->
                                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 800); true
                            }
                        }.getOrDefault(false)
                        if (!alive() && !applying.get()) {
                            LabLog.i("bridge", "同步巡检：意图开但引擎未监听，尝试自启…")
                            val r = XrayRunner.start(ctx)
                            LabLog.i("bridge", "同步巡检自启 ok=" + r.first + " " + r.second)
                        }
                        if (alive()) {
                            activeHostPort = "socks5://127.0.0.1:" + port
                            activeScope = NodeStore.scope()
                            GlobalProxy.set(activeHostPort, activeScope)
                            LabLog.i("bridge", "同步巡检：GlobalProxy 已复活 -> " + activeHostPort)
                            LabUi.bump()
                        } else {
                            LabLog.i("bridge", "同步巡检：引擎仍不可用，保持直连")
                        }
                    }
                }
                try {
                    Thread.sleep(15_000)
                } catch (t: Throwable) {
                    return@Thread
                }
            }
        }, "hpla-lab-node-sync").apply { isDaemon = true }.start()
    }
}

/** 运行状态（UI 读取）。 */
object NodeState {
    @Volatile
    var lastApplied: String = "未启用"
}