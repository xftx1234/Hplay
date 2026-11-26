package com.han1me

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---------- 设计 token（与注入版一致，参考 Han1meViewer 与 hanime1.me 网页版） ----------
internal val BgColor: Color
    @Composable get() = MaterialTheme.colorScheme.background
internal val BarColor: Color
    @Composable get() = MaterialTheme.colorScheme.surface
internal val LineColor: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant
internal val CardColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainer
internal val CardColorSoft: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
internal val AccentRed: Color
    @Composable get() = MaterialTheme.colorScheme.primary
internal val TextDim: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val TextFaint: Color
    @Composable get() = MaterialTheme.colorScheme.outline

/**
 * 界面显示的版本号。改版本必须**同时**改 `工程/Hplay安卓/version.properties` 的 versionName，
 * 两者不一致时 R42Check 会直接失败（"信息"页里那句版本是用户会当真的话，不能靠记忆）。
 */
internal const val APP_VERSION = "1.0.18"
