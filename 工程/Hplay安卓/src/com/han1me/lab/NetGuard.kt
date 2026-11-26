package com.han1me.lab

import android.os.Looper
import okhttp3.Interceptor
import okhttp3.Response

/**
 * **主线程网络守卫**（实验分叉 · 2026-10-31）。
 *
 * 背景：主线程看门狗已经抓到卡死的**形态** ——
 * ```
 *   #5  FastFallbackExchangeFinder.awaitTcpConnect
 *   #17 RealCall.execute(RealCall.kt:189)
 * ```
 * 但栈被截断，没看到**应用自己的那一帧**（是谁调的 execute）。
 *
 * 这个拦截器在**每个请求发出前**检查「当前是不是主线程」；
 * 是的话，把**完整调用栈**写进 LabLog（adb 可直接读）。
 * 这样下一次复现时，一眼就能看到是哪一行代码在主线程做网络。
 *
 * 开销：一次 `Looper.myLooper()` 比较（纳秒级），非主线程时零额外成本。
 */
object NetGuard {

    val interceptor: Interceptor = Interceptor { chain ->
        runCatching {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                val url = chain.request().url.toString()
                LabLog.i("MAINNET", "★★ 主线程网络请求: " + url)
                val st = Thread.currentThread().stackTrace
                // 从第 3 帧起（跳过本拦截器自身），把应用帧记全
                st.drop(2).take(28).forEachIndexed { i, e ->
                    LabLog.i("MAINNET", "  #" + i + " " + e.toString())
                }
                com.han1me.log.AppLog.w("MAINNET", "主线程网络请求 " + url)
            }
        }
        chain.proceed(chain.request())
    }
}
