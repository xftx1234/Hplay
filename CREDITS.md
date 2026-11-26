# 引用与致谢

本工程不是手写的一份新代码：构建时从桌面上游 AvPlay 复制源码、重放补丁后编译（见 `Hplay安卓/build.py` 文件头）。UI 与交互参考下列项目，代码中以注释标注出处。许可证类型一律来自各项目 LICENSE 文件实读。

## 参考项目

| 项目 | 仓库 | 许可证 | 本项目借鉴了什么 |
| --- | --- | --- | --- |
| Han1meViewer（misaka10032w 分支） | https://github.com/misaka10032w/Han1meViewer | Apache-2.0 | 关于页布局、打卡页（DailyCheckIn）、Getchu 新作预告三页、ExoPlayer 封装、Cloudflare 质询判据与 UA 统一、Cookie 桥接思路、下载目录扫描导入 |
| Han1meViewer（YenalyLiew 原库） | https://github.com/YenalyLiew/Han1meViewer | Apache-2.0（Copyright 2021 Yenaly） | 上述分支的上游血缘；本项目未直接引用其代码 |
| Han1mePlus | https://github.com/1wc10086/Han1mePlus | AGPL-3.0 | WebView 登录流程（InAppWebView + 轮询 + DOM 标记）、整串 Cookie 存储与启动回灌方案、MD3 视觉与板块结构 —— **仅参考，非派生**，本仓库不含其代码 |
| NekoBox for Android | https://github.com/MatsuriDayo/NekoBoxForAndroid | GPL-3.0 | 内置节点与本地 SOCKS 的思路参考；本工程未包含其源码 |

## 依赖（全部 Apache-2.0）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| Jetpack Compose (runtime / ui / foundation / animation / material3 / material-icons) | 1.7.3 / material3 1.3.0 | 全部界面 |
| AndroidX（activity / lifecycle / core / annotation / collection / arch / customview 等，共 32 项） | 见 `工具/android-deps/manifest.txt` | 基础库 |
| AndroidX Media3 (ExoPlayer / UI / HLS / datasource-okhttp) | 1.4.1 | 播放内核与 HLS |
| OkHttp / Okio | okhttp-jvm 5.5.0 / okio-jvm 3.18.1 | 网络层（DoH、Cookie、边缘节点都挂在它上面） |
| Coil 3 | 3.0.4 | 图片加载（封面实际走自研 `HplayThumb`，coil3 在测试机静默失败） |
| Kotlin / kotlinx.coroutines / kotlinx.serialization | 2.4.20 / 1.11.0 / 1.11.0 | 语言与协程、序列化 |
| Guava（android 精简版）+ failureaccess | 33.0.0-android / 1.0.2 | ListenableFuture 等 |

构件的下载源是阿里云 Maven 镜像（`maven.aliyun.com/repository/google`、`.../central`），由 `tools/fetch_deps.py` 拉取并输出 `manifest.txt` —— 共 77 个构件。

## 构建工具链

| 项目 | 版本 | 说明 |
| --- | --- | --- |
| Eclipse Temurin JDK | 21.0.12.1+1 | javac / jar / keytool / apksigner / d8 宿主（GPLv2 + Classpath Exception） |
| Kotlin 编译器 | 2.4.20 | 含 `compose-compiler-plugin.jar` |
| Android SDK Build-Tools | 34.0.0 | aapt2 / aapt / zipalign / apksigner（Apache-2.0，AOSP） |
| Android SDK Build-Tools 的 d8 | 35 世代（D8 8.6.2） | 34 的 d8 对 appcompat-resources 类文件会 NPE |
| Android Platform | android-35 | 编译期 android.jar（Compose 1.7 需要） |

## 其它致谢

- **quder 的 WAKAN 移植管线**：本工程的构建思路（cwd + 相对 ASCII 路径绕过 aapt2 的窄字符限制、junction 提供 ASCII 入口、build-tools 34 的可用性）来自其真机实测经验。
- **EasyList Adult 规则集**：去广告元素隐藏规则取自 `adult_specific_hide` / `adult_adservers` / `adult_thirdparty`，另有本机实测补充；规则语法沿用 AdGuard / EasyList 写法。
- **Han1meViewer 与 Han1mePlus 的维护者与贡献者**：UI 结构、打卡、Getchu 预告、Cloudflare 判据、Cookie 桥接、WebView 登录流程均以其实现为参照。

## 义务声明

**Han1meViewer（Apache-2.0）** —— 按 §4 的四项义务逐条落实：

| 义务 | 本仓库怎么做的 |
| --- | --- |
| (a) 向接收者提供许可证副本 | 本仓库 `LICENSE` 即 Apache License 2.0 全文（与本项目自用许可同文本） |
| (b) 修改过的文件带显著「已修改」声明 | 所有参考处均在代码注释里写明来源文件与改动（例：`checkin/HplayCheckIn.kt` 注明「照 Han1meViewer 的 DailyCheckIn 复刻」） |
| (c) 保留全部版权、专利、商标与署名声明 | 本条即保留 `Copyright 2021 Yenaly`（见下） |
| (d) 若原作含 NOTICE 文件则一并收录 | 原作仓库**没有** NOTICE 文件（README 与 LICENSE 目录实读确认），无需收录 |

原版权声明保留如下：

```
Han1meViewer — Copyright 2021 Yenaly
           —— Copyright misaka10032w（该分支维护者）
Licensed under the Apache License, Version 2.0.
```

**Han1mePlus（AGPL-3.0）** —— 本仓库**未包含**其任何源代码，仅参考交互流程与视觉结构，
故不构成衍生作品，不触发 AGPL 的源码开放义务。如后续引入其代码，须整体按 AGPL-3.0 授权
并附完整源码，且网络服务形式提供时须向使用者提供对应源码（§13）。

**NekoBox for Android（GPL-3.0）** —— 仅作思路参考（本地 SOCKS 与内核分层），
未包含其源码，不构成衍生作品。

**本项目自身**以 Apache License 2.0 分发，见 `LICENSE`。
