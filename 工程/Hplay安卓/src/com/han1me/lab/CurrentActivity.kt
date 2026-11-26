package com.han1me.lab

import android.app.Activity

/**
 * **取当前前台 Activity**（2026-11-25 第 322 轮新增）。
 *
 * ## 为什么需要

 * CF 静默过盾 / 后台登录都要往界面里挂一个 WebView，而 WebView **必须 attach 到窗口**
 * 才会真正执行 JS（真机验证过：完全没 attach 的 WebView，`evaluateJavascript` 不回调、
 * 页面也不加载）。所以需要一个真实 Activity 承载一个 1×1 的透明 Dialog。
 *
 * ## 为什么要反射

 * 拦截器跑在 OkHttp 的 IO 线程，手里只有 application Context。
 * 正规做法是给每个 Activity 注册 ActivityLifecycleCallbacks 维护一个弱引用 —— 那要改
 * MainActivity 与所有子 Activity。反射 `ActivityThread.mActivities` 一次拿到，
 * 不改任何既有文件，失败也只是返回 null（调用方降级）。
 *
 * 与 [HeadlessLogin] 里那份逻辑同源，抽出来共用，避免两处各写一遍。
 */
object CurrentActivity {

    fun find(): Activity? = runCatching {
        val cls = Class.forName("android.app.ActivityThread")
        val m = cls.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }
        val thread = m.invoke(null) ?: return null
        val f = cls.getDeclaredField("mActivities").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val acts = f.get(thread) as? Map<Any, Any> ?: return null
        for (rec in acts.values) {
            val rc = rec?.javaClass ?: continue
            val pf = runCatching { rc.getDeclaredField("paused").apply { isAccessible = true } }.getOrNull()
            val paused = runCatching { pf?.get(rec) as? Boolean ?: false }.getOrDefault(false)
            if (paused) continue
            val af = runCatching { rc.getDeclaredField("activity").apply { isAccessible = true } }.getOrNull()
            val a = runCatching { af?.get(rec) as? Activity }.getOrNull()
            if (a != null && !a.isFinishing && !a.isDestroyed) return a
        }
        null
    }.getOrNull()
}
