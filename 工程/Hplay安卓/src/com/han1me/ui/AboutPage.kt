package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.HplayEnv
import com.han1me.update.HplayBuildInfo
import com.han1me.update.HplayUpdateChannel

/**
 * **关于页**（2026-11-25 第 306 轮，照 Han1meViewer 的 AboutScreen）。
 *
 * 布局对照（用户截图那张）：
 *  - 顶部：品牌色大卡片（图标 + 名称 + 版本）
 *  - 中部：「应用信息」键值表（构建类型/提交/包名/站点/Android/机型）
 *  - 下部：「项目与支持」可点行
 *
 * 与旧版的差别：旧版是三段卡片堆叠（版本/技术说明），信息密度低。
 * 这里改成左右两栏键值表，一屏看全，且把「检查更新」也放进来。
 */
@Composable
fun AboutPageViewerStyle(
    onCheckUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    val pkg = ctx.packageName
    val verName = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0).versionName
    }.getOrNull() ?: "?"

    val verCode = runCatching {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else pi.versionCode.toLong()
    }.getOrNull() ?: 0L

    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val androidVer = android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")"
    val model = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL

    Column(modifier.fillMaxWidth()) {
        // ---- 品牌卡 ----
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(cs.primary.copy(alpha = 0.86f))
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ★★★ 2026-11-25（第 322 轮 · 用户要求「关于里把图标改成我们 hplay 的」）：
                //
                // 原来是一个字母 "H" 占位。现在直接画**应用自己的启动图标**
                // （mipmap/ic_launcher，由 build.py 的 icons() 从 hplay-icon.png 生成）。
                //
                // 为什么用 getIdentifier 而不是 R.mipmap.ic_launcher：
                // 本项目的 R 类只对依赖库生成（build.py 的 gen_r 扫描 jar 引用），
                // 自己的包 com.han1me 没有 R 类 —— 写 R.mipmap 编译不过。
                // 资源名查找是这项目里既有的做法（见 HplayBg.load）。
                val iconRes = remember {
                    ctx.resources.getIdentifier("ic_launcher", "mipmap", ctx.packageName)
                }
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(cs.onPrimary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (iconRes != 0) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(iconRes),
                            contentDescription = "Hplay",
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        // 拿不到资源时退回字母（绝不空白）
                        Text("H", color = cs.onPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Hplay", color = cs.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Han1me 客户端 · MD3 重构版",
                        color = cs.onPrimary.copy(alpha = 0.82f), fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "v" + verName + " (" + verCode + ")",
                        color = cs.onPrimary.copy(alpha = 0.72f), fontSize = 11.sp,
                    )
                }
            }
        }

        // ---- 应用信息 ----
        AboutSectionTitle("应用信息")
        AboutKvRow("包名", pkg)
        AboutKvRow("版本", "v" + verName)
        AboutKvRow("构建号", verCode.toString())
        AboutKvRow("当前站点", "https://hanime1.me/")
        AboutKvRow("Android 版本", androidVer)
        AboutKvRow("设备型号", model)

        // ---- 更新 ----
        AboutSectionTitle("更新")
        AboutLinkRow(
            "检查更新",
            // ★ 2026-11-25（第 310 轮 · 用户报「检查更新很慢，转到其他页面才出来」）：
            // 原因是 UpdateChecker 要依次试 4 个网盘通道，全失败才回落，
            // 最慢要十几秒。这里先给出「正在检查…」的即时反馈，
            // 用户不会觉得按钮坏了。
            if (checking) "正在检查…"
            else "走「" + HplayUpdateChannel.channelLabel(HplayUpdateChannel.channel) + "」",
        ) {
            if (!checking) {
                checking = true
                onCheckUpdate()
                // 8 秒后复位（面板由 Han1meApp 弹出，这里只负责按钮态）
                scope.launch {
                    delay(8000)
                    checking = false
                }
            }
        }

        // ---- 更新渠道与发布线（第 331 轮）----
        // 这几行是**只读事实**，改渠道要进更新面板（那里有选择器）。
        // 放在关于页的理由：用户想知道「我这一包是哪条线、走哪条渠道」时，
        // 不该还得点进面板才知道。
        AboutKvRow("当前发布线", HplayUpdateChannel.lineLabel(HplayUpdateChannel.line))
        AboutKvRow("更新渠道", HplayUpdateChannel.channelLabel(HplayUpdateChannel.channel))
        // ★ 第 331 轮（真机发现）：仓库地址不能用 AboutKvRow ——
        // 它是「左 key + 右 value(maxLines=1, ellipsis)」，URL 一长就把 key 挤掉，
        // 真机截图里显示成「GitHub 仓库https://github.com/...」两段粘在一起。
        // 改成键值分开两行：第一行 key，第二行 URL（可点击）。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("GitHub 仓库", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (HplayUpdateChannel.repoConfigured) "打开 ›" else "未配置",
                color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                modifier = Modifier.clickable(enabled = HplayUpdateChannel.repoConfigured) {
                    HplayEnv.openExternal(HplayUpdateChannel.repoUrl)
                },
            )
        }
        if (HplayUpdateChannel.repoConfigured) {
            Text(
                HplayUpdateChannel.repoUrl,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp),
            )
        }
        if (HplayBuildInfo.BUILD_SHA.isNotBlank()) {
            AboutKvRow("构建提交", HplayBuildInfo.BUILD_SHA)
        }
        AboutKvRow("构建时间", HplayBuildInfo.BUILD_AT)

        // ---- 项目与支持 ----
        AboutSectionTitle("项目与支持")
        AboutLinkRow("技术栈", "Kotlin + Compose MD3 + media3") {}
        if (HplayUpdateChannel.repoConfigured) {
            AboutLinkRow("GitHub 仓库", HplayUpdateChannel.repoUrl) {
                HplayEnv.openExternal(HplayUpdateChannel.repoUrl)
            }
            AboutLinkRow("问题反馈", "在仓库里提 Issue") {
                HplayEnv.openExternal(HplayUpdateChannel.repoUrl + "/issues")
            }
        }

        // ---- 引用与致谢（第 331 轮：用户要求详细列出所有来源）----
        AboutCreditsSection()

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AboutSectionTitle(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun AboutKvRow(key: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = cs.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = cs.onSurface, fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AboutLinkRow(title: String, sub: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = cs.onSurface, fontSize = 13.sp)
            Text(sub, color = cs.onSurfaceVariant, fontSize = 10.sp)
        }
        Text("›", color = cs.onSurfaceVariant, fontSize = 18.sp)
    }
}
