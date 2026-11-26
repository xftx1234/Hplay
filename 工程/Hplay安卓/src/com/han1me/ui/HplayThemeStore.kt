package com.han1me.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * **主题选择的持久化**（2026-11-24 第 277 轮重构新增）。
 *
 * 只有三个开关：种子色 / 明暗 / AMOLED。
 * 存 SharedPreferences，进程重启后保持；不依赖 Compose 的 rememberSaveable（那个跨进程不保留）。
 *
 * 读写在主线程做：三个 tiny 字段，SharedPreferences 内部有内存缓存，实测无卡顿。
 */
object HplayThemeStore {

    private const val FILE = "hplay_theme"
    private const val K_SEED = "seed"
    private const val K_DARK = "dark"
    private const val K_AMOLED = "amoled"

    /** 当前种子色（默认玫红）。改动会触发 Compose 重组。 */
    var seed by mutableStateOf(HplaySeed.ROSE)
        private set

    /** 是否深色（默认深色 —— 本站内容以深色底更适合看片）。 */
    var dark by mutableStateOf(true)
        private set

    /** 是否纯黑（仅深色下有效）。 */
    var amoled by mutableStateOf(false)
        private set

    private var loaded = false

    /** 读一次盘。重复调用无副作用。 */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        seed = HplaySeed.fromName(p.getString(K_SEED, null))
        dark = p.getBoolean(K_DARK, true)
        amoled = p.getBoolean(K_AMOLED, false)
    }

    fun setSeed(ctx: Context, s: HplaySeed) {
        seed = s
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(K_SEED, s.name).apply()
    }

    fun setDark(ctx: Context, v: Boolean) {
        dark = v
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(K_DARK, v).apply()
    }

    fun setAmoled(ctx: Context, v: Boolean) {
        amoled = v
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(K_AMOLED, v).apply()
    }

    /** 当前应当使用的 ColorScheme。 */
    fun scheme(): androidx.compose.material3.ColorScheme =
        if (dark) md3Dark(seed.seed, amoled) else md3Light(seed.seed)
}
