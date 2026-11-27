package com.han1me.lab

/**
 * **全局转发代理持有者**（2026-11-24 第 277 轮新增）。
 *
 * ## 为什么需要一个全局持有者

 * 本地转发代理原本只在 CF 验证页里起（已删除的旧验证页），问题：
 *
 *  · 验证页是**按需**弹出的，起之前系统代理就已经要指向它了；
 *  · 端口若跟着验证页生命周期变，系统代理会指向死端口 → 验证页白屏。
 *
 * 所以改成：**App 启动时起一次，固定端口 18080，整个进程生命周期都在**。
 * 这样系统代理（adb 设一次）可以长期有效。
 *
 * ## 与「不走系统设置」原则的边界

 * 本类**只在自己进程内起一个监听端口**，不写任何系统设置；
 * 系统代理那条 `settings put` 由用户在电脑上用 adb 执行（我们无权也不去写）。
 * 与 HplayRootBoost「对系统网络设置零接触」的承诺不冲突。
 */
object CfForward {

    @Volatile
    private var proxy: LocalForwardProxy? = null

    /** 已启动的端口；0 = 未启动。 */
    @Volatile
    var port: Int = 0
        private set

    /**
     * 启动（幂等）。可重复调用，只在第一次真正起监听。
     *
     * @return 监听端口；0 表示失败
     */
    fun ensureStarted(): Int {
        val p = proxy
        if (p != null && port > 0) return port
        synchronized(this) {
            if (port > 0) return port
            val hp = GlobalProxy.hostPort
            val bare = hp.substringAfter("://", hp).trim().removePrefix("/")
            val upHost = bare.substringBeforeLast(":", "127.0.0.1").ifBlank { "127.0.0.1" }
            val upPort = bare.substringAfterLast(":", "10808").toIntOrNull() ?: 10808
            val f = LocalForwardProxy(upHost, upPort)
            val pt = f.start()
            if (pt > 0) {
                proxy = f
                port = pt
                LabLog.i("cf", "转发代理就绪 127.0.0.1:" + pt + " -> socks " + upHost + ":" + upPort)
            } else {
                LabLog.i("cf", "转发代理启动失败（端口被占用？）")
            }
            return pt
        }
    }
}
