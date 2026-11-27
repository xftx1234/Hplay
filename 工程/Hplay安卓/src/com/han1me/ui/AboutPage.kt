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
 * **关于页**（第 306 轮建立，第 332 轮按两个参考源码重排）。
 *
 * ## 第 332 轮重排的依据
 *
 * 用户原话：「关于页面按照 han1 两个源码的样式重构，链接不放，点击跳转」。
 *
 * **Han1meViewer 的 AboutAppScreen.kt** 是这套布局的原型，它的结构是：
 *  ① 顶部品牌大卡（图标 + 名称 + 副标题 + 版本），整卡可点（彩蛋）；
 *  ② 「应用信息」分组：构建类型 / 提交 / 包名 / 当前站点 / Android / 机型 ——
 *     每一行是 `SettingInfoItem(title, valueText, iconRes)`；
 *  ③ 「项目与支持」分组：GitHub 仓库 / 提交 BUG / 论坛 ——
 *     每一行是 `SettingNavigationItem(title, summary, iconRes, onClick)`，
 *     **点击 `context.browse(url)` 跳浏览器**，界面上**不显示裸 URL**；
 *  ④ 「说明与免责声明」分组：两张 `DisclaimerRow`（图标+标题+正文）；
 *  ⑤ 一张**高亮**的地区声明卡（errorContainer 底 + 左边脉冲圆点）；
 *  ⑥ 居中收尾：「建议通过官方渠道支持原站内容」+ 一条分隔线 + 「感谢所有贡献者」。
 *
 * **Han1mePlus** 的关于页更简略（README 级的鸣谢），但它有一点值得学：
 * 图标用**圆形裁切**（CircleShape），比圆角矩形更像应用图标。
 *
 * ## 本页的取舍
 *
 *  · 保留 Viewer 的「分组 + 键值行 + 导航行」骨架（用户在截图里认可的就是这个）；
 *  · **不照搬它的免责文案**：那几条是针对它自己的处境写的（Fork 自 YenalyLiew、
 *    不做地区声明等）。我们的来源与义务不同，照抄会变成**错误声明**。
 *  · **不照搬它的彩蛋**（连点 7 次弹 toast）—— 与功能无关，且我们已经有开屏更新弹窗。
 *  · 链接一律**不显示裸 URL**，只显示标题 + 副标题，点击才跳（用户明确要求）。
 *  · 高亮声明卡保留：用于「18+ / 24 小时删除」这条最需要被看到的文字。
 *
 * @param onCheckUpdate 点「检查更新」的回调（面板由 MainActivity 弹出）
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
    val model = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim()

    Column(modifier.fillMaxWidth()) {
        // ---------- ① 品牌卡 ----------
        AboutHeaderCard(verName, verCode, HplayUpdateChannel.lineLabel(HplayBuildInfo.GH_LINE))

        // ---------- ② 应用信息 ----------
        AboutSectionTitle("应用信息")
        AboutKvRow("包名", pkg)
        AboutKvRow("版本", "v" + verName)
        AboutKvRow("构建号", verCode.toString())
        AboutKvRow("当前站点", "hanime1.me")
        AboutKvRow("Android 版本", androidVer)
        AboutKvRow("设备型号", model)

        // ---------- ③ 更新 ----------
        AboutSectionTitle("更新")
        AboutNavRow(
            icon = "🔄",
            title = if (checking) "正在检查…" else "检查更新",
            summary = HplayUpdateChannel.channelLabel(HplayUpdateChannel.channel) +
                " · " + HplayUpdateChannel.lineLabel(HplayUpdateChannel.line),
        ) {
            if (!checking) {
                checking = true
                onCheckUpdate()
                // 8 秒后复位：面板由 MainActivity 弹出，这里只负责按钮态。
                scope.launch { delay(8000); checking = false }
            }
        }

        // ---------- ④ 项目与支持（点击跳转，不显示裸 URL）----------
        AboutSectionTitle("项目与支持")
        // ★★★ 第 341 轮（用户要求「关于页面注入 github 仓库信息」）：
        //
        // 之前这四行只在构建时**注入了仓库地址**（HPLAY_GH_REPO）才会跳转，
        // 否则一律弹「这个构建没有注入仓库地址」—— 用户看到的关于页等于没有仓库信息。
        // 现在：
        //   · 仓库地址有了**构建期兜底**（build.py 的 GH_REPO 默认值改成真实仓库），
        //   · 这里再把「仓库名 / 当前线 / 构建时间 / 提交短 SHA」作为**副标题**直接显示，
        //     不点也能看见，符合用户「注入仓库信息」的原话。
        val repoLabel = if (HplayUpdateChannel.repoConfigured) HplayBuildInfo.GH_REPO else "未配置"
        val buildStamp = buildString {
            append(HplayBuildInfo.BUILD_AT)
            if (HplayBuildInfo.BUILD_SHA.isNotBlank()) append(" · ").append(HplayBuildInfo.BUILD_SHA)
        }
        AboutNavRow("💬", "GitHub 仓库", repoLabel) {
            if (HplayUpdateChannel.repoConfigured) HplayEnv.openExternal(HplayUpdateChannel.repoUrl)
            else HplayEnv.toast("这个构建没有注入仓库地址")
        }
        AboutNavRow("📦", "版本发布（Releases）", "所有历史版本与更新包") {
            if (HplayUpdateChannel.repoConfigured) HplayEnv.openExternal(HplayUpdateChannel.repoUrl + "/releases")
            else HplayEnv.toast("这个构建没有注入仓库地址")
        }
        AboutNavRow("🐞", "提交 BUG", "issues · 出问题了就来提一个") {
            if (HplayUpdateChannel.repoConfigured) HplayEnv.openExternal(HplayUpdateChannel.repoUrl + "/issues")
            else HplayEnv.toast("这个构建没有注入仓库地址")
        }
        // 构建标识单独一行：查更新拿到的版本对不对，全靠这一行比对
        AboutKvRow("构建时间", buildStamp)
        AboutKvRow("发布线", HplayBuildInfo.GH_LINE)
        AboutNavRow("≡", "引用与致谢", "参考项目、依赖与许可证") {
            HplayAboutState.showCredits = true
        }

        // ---------- ⑤ 说明与免责声明 ----------
        AboutSectionTitle("说明与免责声明")
        AboutDisclaimerCard(
            icon = "ℹ",
            title = "项目来源与许可",
            body = "本应用无官方网站，GitHub Release 与 CI 构建产物是唯一下载及更新渠道；" +
                "与 hanime1.me 及其关联方无任何隶属、合作或授权关系。" +
                "界面与交互参考 Han1meViewer（Apache-2.0）与 Han1mePlus（AGPL-3.0），" +
                "本工程未包含二者的源代码副本。",
        )
        AboutDisclaimerCard(
            icon = "🔒",
            title = "数据与版权",
            body = "本应用仅通过常规网络请求与 HTML DOM 解析获取公开页面信息，" +
                "不访问网站后端数据库，不进行注入攻击、绕过授权或获取非公开用户数据。" +
                "视频、图文及相关内容版权归原网站或原始制作、发行方所有。",
        )
        AboutHighlightCard(
            "使用限制",
            "本应用仅面向 18 岁以上成年人，仅供技术研究与学习交流。" +
                "请在下载后 24 小时内删除，并支持原站正版内容。" +
                "不得公开传播本应用的源码或安装包。" +
                "使用者应遵守所在地区法律法规及目标网站的服务条款。",
        )

        AboutThanks("感谢所有参考项目的作者与贡献者")
    }
}

/** 关于页里「引用与致谢」全屏页的开关（第 332 轮：从滚动行改成独立一层）。 */
object HplayAboutState {
    var showCredits by mutableStateOf(false)
}

@Composable
private fun AboutHeaderCard(verName: String, verCode: Long, lineLabel: String) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(cs.primary.copy(alpha = 0.86f))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 图标：优先画应用自己的 ic_launcher（build.py 从 hplay-icon.png 生成），
            // 拿不到就退回字母 H —— 绝不空白。用 getIdentifier 是因为本项目的 R 类
            // 只对依赖库生成，自己的包没有 R（见 build.py 的 gen_r）。
            val iconRes = remember {
                ctx.resources.getIdentifier("ic_launcher", "mipmap", ctx.packageName)
            }
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
                    .background(cs.onPrimary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                if (iconRes != 0) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(iconRes),
                        contentDescription = "Hplay",
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Text("H", color = cs.onPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Hplay", color = cs.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Hanime 客户端 · MD3 重构版",
                    color = cs.onPrimary.copy(alpha = 0.82f), fontSize = 11.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "v" + verName + " (" + verCode + ") · " + lineLabel,
                    color = cs.onPrimary.copy(alpha = 0.72f), fontSize = 11.sp,
                )
            }
        }
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
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
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

/**
 * 可点行（照 Viewer 的 SettingNavigationItem）：图标 + 标题 + 副标题 + 箭头。
 *
 * 关键点：**副标题里放人话说明，不放裸 URL**（用户明确要求「链接不放，点击跳转」）。
 */
@Composable
private fun AboutNavRow(icon: String, title: String, summary: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 16.sp, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = cs.onSurface, fontSize = 13.sp)
            Text(summary, color = cs.onSurfaceVariant, fontSize = 10.5.sp)
        }
        Text("›", color = cs.onSurfaceVariant, fontSize = 18.sp)
    }
}

@Composable
private fun AboutDisclaimerCard(icon: String, title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerLow)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(icon, fontSize = 16.sp)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 18.sp)
        }
    }
}

/** 高亮声明卡（照 Viewer 的 HighlightDisclaimerCard，但不用脉冲动画 —— 它有动画但不增信息量）。 */
@Composable
private fun AboutHighlightCard(title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.errorContainer.copy(alpha = 0.45f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("⚠", fontSize = 16.sp)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = cs.onErrorContainer, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                body, color = cs.onErrorContainer.copy(alpha = 0.85f),
                fontSize = 11.sp, lineHeight = 18.sp,
            )
        }
    }
}

/** 居中收尾（照 Viewer 的 AboutThanks：两条渐变线夹一个菱形 + 感谢语）。 */
@Composable
private fun AboutThanks(text: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("——  " + text + "  ——", color = cs.onSurfaceVariant.copy(alpha = 0.75f), fontSize = 11.sp)
    }
}