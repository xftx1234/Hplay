package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **Cloudflare 验证入口卡**（2026-11-26 第 338 轮）。
 *
 * ## 用户要求
 *
 * 「首次启动调用的 cf 验证弹窗有延迟，而且会导致界面卡一下才出现验证。改为默认不弹出，
 *   但在首页中间做一个 cf 验证按钮，点击后才弹出验证弹窗。」
 *
 * ## 为什么是「横幅」而不是「小按钮」
 *
 * 验证失败时首页其实是**空的**（所有请求都 403）。这时候放一个小按钮在角落，
 * 用户会以为应用坏了。做成整条横幅、说明「为什么看不到内容」，才是可用的引导 ——
 * 这也是 Material 3 里 empty state 的标准做法：说明原因 + 给出唯一动作。
 *
 * 验证通过后这张卡不再渲染（needsVerify() 为 false），所以它不会长期占版面。
 *
 * ## 状态来自哪里
 *
 * HplayCfHolder.needVerifyHost 是 Compose 可观察状态：
 * 拦截器命中 403+challenge 时置位，HplayGateActivity 完成后清除。
 * 界面不需要轮询 —— 状态一变就重组。
 */
@Composable
fun HplayCfBanner(
    modifier: Modifier = Modifier,
    /** 点击后的动作：打开验证页。 */
    onVerify: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(HplayDimens.radiusSheet)

    val host = com.han1me.lab.HplayCfHolder.needVerifyHost
    if (!com.han1me.lab.HplayCfHolder.needsVerify()) return

    Surface(
        modifier = modifier.fillMaxWidth().height(132.dp),
        shape = shape,
        color = cs.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(cs.primaryContainer, cs.surfaceContainerHigh),
                    ),
                )
                .clip(shape)
                .clickable { onVerify() },
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🛡", fontSize = 30.sp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        "需要一次人机验证",
                        color = cs.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (host.isNotBlank()) "站点 " + host + " 要求验证后才能读取内容"
                        else "站点要求验证后才能读取内容",
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "验证 + 登录 + 保存一次完成，之后不再需要",
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(HplayDimens.radiusChip),
                    color = cs.primary,
                    contentColor = cs.onPrimary,
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        "去验证",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
