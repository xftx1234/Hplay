package com.han1me.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.han1me.HplayEnv

/**
 * **关于页的「引用与致谢」区块**（第 331 轮，用户明确要求）。
 *
 * 用户原话：「还有在关于页详细编写引用和原作者项目仓库，标注所有来源，看两个源码怎么写的」。
 *
 * ## 两个参考项目的关于页怎么写的
 *
 *  · **Han1meViewer**（\`AboutAppScreen.kt\`）：分三段 ——
 *    ①「应用信息」键值表；②「项目与支持」三条可点行
 *    （GitHub 仓库 / 提交 Bug / 论坛），都用 \`context.browse(url)\` 打开浏览器；
 *    ③「免责声明」两个分组卡片 + 一个高亮的地区声明卡片，
 *    最后是「支持原作者」与一个带动画的感谢语。
 *    关键点：**仓库 URL 是常量**（\`Constants.kt\` 里 HA1_GITHUB_URL / _ISSUE_URL / _FORUM_URL），
 *    不是硬编码在界面里。
 *
 *  · **Han1mePlus**（Flutter）：README 里有「鸣谢」段，App 内关于页是设置里的一个入口，
 *    内容比 Viewer 简略。
 *
 * 本实现取 Viewer 的结构（可点行 + 分组 + 免责声明），但**不抄它的四段式免责声明**：
 * 那些是针对它自己作者的处境写的，我们需要说的是我们自己的来源与义务。
 *
 * ## 为什么引用清单要写这么细
 *
 * 三个理由，按重要性排：
 *  1. **许可证义务**。Han1meViewer 是 Apache-2.0，要求保留版权声明并说明修改；
 *     Han1mePlus 是 AGPL-3.0 —— 我们**没有**把它的代码拷进来（只参考了交互与流程），
 *     所以不触发 AGPL 的源码开放义务，但**必须如实写明这是参考而非派生**。
 *     这一条如果写错（把 Plus 写成 fork），反而是错的声明。
 *  2. **溯源**。这个工程是"从上游 AvPlay 复制 + 打补丁"生成的，不是手写的一份新代码。
 *     不写清这一点，日后维护者会以为源码就是这里看到的全部。
 *  3. **用户可以自己去看**。给的都是可点的仓库/文档地址，不是一句「感谢开源」。
 */
data class CreditItem(
    val title: String,
    val sub: String,
    val url: String,
    val tag: String,
)

/**
 * 引用清单（全部来自 LICENSE 与 README 实读，见 交接/credits_raw.md）。
 *
 * 顺序按「对本工程影响大小」排：参考项目 → 上游血缘 → 依赖 → 工具链 → 内核参考。
 */
val HPLAY_CREDITS: List<CreditItem> = listOf(
    CreditItem(
        "Han1meViewer（misaka10032w 分支）",
        "Apache-2.0 · 关于页布局、打卡页、Getchu 预告、ExoPlayer 封装、CF 判据",
        "https://github.com/misaka10032w/Han1meViewer",
        "参考项目",
    ),
    CreditItem(
        "Han1meViewer（YenalyLiew 原库）",
        "Apache-2.0 · 上述分支的上游，版权 Copyright 2021 Yenaly",
        "https://github.com/YenalyLiew/Han1meViewer",
        "上游血缘",
    ),
    CreditItem(
        "Han1mePlus",
        "AGPL-3.0 · WebView 登录流程、Cookie 存储与回灌、MD3 视觉参考（仅参考，非派生）",
        "https://github.com/1wc10086/Han1mePlus",
        "参考项目",
    ),
    CreditItem(
        "NekoBox for Android",
        "GPL-3.0 · 内置节点的思路参考；本工程未包含其源码",
        "https://github.com/MatsuriDayo/NekoBoxForAndroid",
        "参考项目",
    ),
    CreditItem(
        "Jetpack Compose / AndroidX",
        "Apache-2.0 · UI 与基础库（76 个构件，完整清单见仓库 CREDITS.md）",
        "https://github.com/androidx/androidx",
        "依赖",
    ),
    CreditItem(
        "AndroidX Media3 (ExoPlayer)",
        "Apache-2.0 · 播放内核与 HLS 支持",
        "https://github.com/androidx/media",
        "依赖",
    ),
    CreditItem(
        "OkHttp / Okio",
        "Apache-2.0 · 网络层（含 HTTP/1.1 强制以避开 h2 拥塞窗口问题）",
        "https://github.com/square/okhttp",
        "依赖",
    ),
    CreditItem(
        "Coil 3",
        "Apache-2.0 · 图片加载",
        "https://github.com/coil-kt/coil",
        "依赖",
    ),
    CreditItem(
        "Kotlin / kotlinx.coroutines / kotlinx.serialization",
        "Apache-2.0 · 语言与协程、序列化",
        "https://github.com/JetBrains/kotlin",
        "依赖",
    ),
    CreditItem(
        "Android SDK Build-Tools / AOSP 工具链",
        "Apache-2.0 · aapt2 / d8 / zipalign / apksigner 手工构建管线",
        "https://source.android.com/docs/setup/build",
        "工具链",
    ),
)

/** 站点与数据来源（只列域名与用途，不含任何规避手段说明）。 */
val HPLAY_SITES: List<Pair<String, String>> = listOf(
    "hanime1.me" to "官方源：浏览、搜索、播放、账号",
    "getchu.com" to "新作预告数据",
    "xchina.co" to "三次元板块",
    "qinav.com" to "三次元板块",
    "ganxiaban.com" to "三次元板块",
    "ininav.com" to "三次元板块",
    "25jm.cc" to "三次元板块（36co 现用主域）",
    "tangxinvlog.app" to "糖心 Vlog 原生板块",
    "missav.com" to "三次元板块",
)

/**
 * **引用与致谢全屏页**（第 332 轮）。
 *
 * 原来它是一段塞在关于页中间的长列表（滚到底才能看完），改成独立一层：
 *  · 关于页保持「一屏看全」的信息密度（照 Han1meViewer 的分组结构）；
 *  · 想看引用的人点「引用与致谢」进来，这里是完整清单，可以慢慢看、逐个点开。
 *
 * 由 MainActivity 在 [HplayAboutState.showCredits] 为真时覆盖显示
 * （与更新面板同级，这样它能盖住设置页，系统返回也能正常退出）。
 */
@Composable
fun AboutCreditsPage(onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize()
            .background(cs.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        // 返回栏（与设置页其它子页同款）
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = cs.onSurface, fontSize = 26.sp,
                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                    .clickable { onClose() }.padding(horizontal = 14.dp, vertical = 2.dp),
            )
            Text("引用与致谢", color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        AboutCreditsSection()
    }
}

@Composable
fun AboutCreditsSection() {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    CreditsSectionTitle("参考项目")

    // 一句话说清「这个工程是什么」，因为它是理解下面所有引用的前提。
    Text(
        "本工程不是手写的一份新代码：构建时从桌面上游 AvPlay 复制源码、重放补丁后编译。" +
            "UI 与交互参考下面这些项目，代码中以注释标注出处。" +
            "以下许可证类型均来自各项目 LICENSE 文件实读。",
        color = cs.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )

    for (c in HPLAY_CREDITS) {
        Row(
            Modifier.fillMaxWidth().clickable { HplayEnv.openExternal(c.url) }
                .padding(horizontal = 20.dp, vertical = 9.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row {
                    Text(c.title, color = cs.onSurface, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        c.tag,
                        color = cs.primary, fontSize = 9.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(cs.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Text(c.sub, color = cs.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Text("›", color = cs.onSurfaceVariant, fontSize = 16.sp)
        }
    }

    CreditsSectionTitle("站点与数据来源")
    Text(
        "本应用是第三方客户端，与下列站点的运营方无任何关联；内容与版权归各站点及其上传者所有。",
        color = cs.onSurfaceVariant, fontSize = 10.5.sp, lineHeight = 15.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    for ((host, use) in HPLAY_SITES) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(host, color = cs.onSurface, fontSize = 11.5.sp, modifier = Modifier.width(150.dp))
            Text(use, color = cs.onSurfaceVariant, fontSize = 10.5.sp)
        }
    }

    CreditsSectionTitle("许可与声明")
    Text(
        "· 本工程以 Apache License 2.0 分发（见仓库 LICENSE）。\n" +
            "· 参考了 Han1meViewer（Apache-2.0）：保留其版权声明，修改处已在代码注释中标注。\n" +
            "· 参考了 Han1mePlus（AGPL-3.0）：仅参考交互与流程，「未包含其源代码」，" +
            "因此不构成派生作品；如后续引入其代码，须整体按 AGPL-3.0 开放。\n" +
            "· 参考了 NekoBox for Android（GPL-3.0）：仅作思路参考，未包含其源码。\n" +
            "· 18+ 内容：本应用仅面向成年人。\n" +
            "· 仅供学习与研究，请于下载后 24 小时内删除；不得公开传播。\n" +
            "· 与原版人工汉化无关；如有侵权，请联系删除。",
        color = cs.onSurfaceVariant, fontSize = 10.5.sp, lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    Spacer(Modifier.height(24.dp))
}
/** 与小节标题同款（AboutPage 里那个是 private，跨文件用不了 —— 同模板重复声明比放宽可见性干净）。 */
@Composable
private fun CreditsSectionTitle(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}