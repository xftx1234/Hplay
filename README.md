# Hplay

Android 客户端，Kotlin + Jetpack Compose Material 3，从 AvPlay 上游派生 —— 构建时从上游复制源码、重放补丁后编译，本仓库同时携带补齐这些补丁所需的全部上游文件。

## 两条发布线

| | 实验版（lab） | 主线版（stable） |
| --- | --- | --- |
| 分支 | `lab` | `main` |
| tag | `lab-v<versionName>` | `v<versionName>` |
| Release | prerelease（预发布） | 正式版 |
| 定位 | 新板块、新内核、未定型的交互先在这条线上跑 | 稳定可用，实验功能验证成熟后合入 |

两条线**包名与签名相同**（`com.han1me.app` + `keystore/hplay-port.jks`），所以可以互相覆盖安装、共用同一份应用数据：装实验版试新功能，不满意就在更新页把线切回主线版，下载新包直接覆盖，不用卸载、不用重新登录。

## 功能

### 站点浏览与播放

- **本命板块**：hanime1.me 官方源的浏览、搜索、分区、详情、内嵌播放；官方源被 Cloudflare 挡住时按镜像线路逐条自动回退。
- **站点合集**：目录里的镜像站与第三方站点，通用卡片解析（标题/封面/时长/播放量），介绍页与播放页。
- **三次元板块**：xchina / qinav / 干下班 / ininav / 25jm / MissAV 等站点的原生解析；cosplaytele 的 cos 板块；糖心 Vlog 原生板块（走站内索引，翻页与搜索零延迟）。
- **内嵌播放器**：ExoPlayer + OkHttpDataSource 挂同一个 OkHttp 客户端，媒体流量天然走 DoH / 边缘节点 / 代理策略；倍速（含长按临时加速）、手势锁定、画质切换、HLS。
- **站点网页**：内置浏览器，注入去广告规则，HTML5 全屏由宿主接管（`onShowCustomView`），第三方弹窗脚本按域名拦截。
- **Getchu 新作预告**：月度新作列表与详情（EUC-JP 解码）。
- **我的订阅 / 冲了吗打卡**：关注作者与订阅视频；打卡日历、记录、报表。

### 三次元原生板块

- 独立的路由与卡片组件，与官方源互不干扰。
- 封面加载走自研 `HplayThumb`（同一套网络策略 + BitmapFactory + LRU），失败原因直接画在卡面。

### 下载

- HLS 整包下载（清单 + 分片落同一目录），**分片并发默认 16、上限 16**（实测 32 反而掉到 1.13 MB/s，是 h2 拥塞窗口与源站限速的叠加，不是越多越快）。
- **强制 HTTP/1.1**（`.protocols(listOf(Protocol.HTTP_1_1))`）：h2 复用单连接时所有分片共享一个拥塞窗口，「一个分片卡住全体等」；换回 h1.1 后实测 3.62 → 26.8 MB/s（509 分片 / 831 MB / 31 秒）。
- 前台服务 + `PARTIAL_WAKE_LOCK`：退到后台、息屏继续下，通知栏显示进度并可取消。
- 暂停 / 续传 / 重试计数如实显示；「诱饵清单」判据与播放器一致，避免下到 10 秒的假清单。
- 已下载条目可交给系统播放器（FileProvider `content://`，不会触发 `FileUriExposedException`）。
- 公共「下载」目录扫描导入（需要「所有文件访问」权限，界面如实显示是否已获批）。

### 账号

- WebView 登录（页面在 Cloudflare 之后且带 hCaptcha，表单 POST 不可靠）。
- **多账户**：多份 cookie + 账号信息整体存 JSON，一键切换，云端收藏与历史跟着走。
- Cloudflare 静默过盾：`cf_clearance` / `__cf_bm` / `session` 在 `CookieJar.loadForRequest` 合并注入（唯一不会被 `BridgeInterceptor` 覆盖的位置）。
- 收藏、历史、订阅、个人主页链接。

### 网络

- DoH（默认关闭，可手动开，只作用于官方源）。
- 边缘节点优选：内置节点表 + 订阅源 + 手动 Hosts，带测速。
- 内置 VLESS/REALITY 内核（xray，以 `lib/arm64-v8a/libxray.so` 打进 APK），本地 SOCKS 只在应用内生效，不改系统设置。
- 全局代理作用域可切换（全部接口 / 仅官方源），三次元板块可单独设直连。
- IPv4 优先的 DNS 解析（移动网络下 IPv6 出口常不通，实测 8 秒超时）。

### 外观

- Material 3 主题：**9 种种子色**（玫红/深蓝/青碧/琥珀/苔绿/橘橙/靛蓝/樱粉/紫罗兰）、明暗切换、纯黑 AMOLED。
- 全局壁纸层（所有页面与覆盖层之上，不会被详情页/播放页盖掉）。
- 平板模式（最小宽度 ≥ 600dp 自动判定，也可手动开）、响应式网格列数。
- 页面切换动画、底栏与手势返回逐级消费。

### 数据

- **完整备份 / 恢复**：登录凭据、CF 通行证、三次元收藏与历史、下载索引、全部设置打包成 zip，可跨设备恢复；兼容主线旧备份。
- 隐私锁：冷启动验证设备锁屏密码（`KeyguardManager`），可设为仅打开时出现。
- 应用内检查更新与下载安装：后台通知栏下载（前台服务 + `PARTIAL_WAKE_LOCK`，息屏继续）、
  `Range` 断点续传（第 20 MB 断了不会从 0 重下）、直连失败自动换 4 个 gh-proxy 镜像、
  下完直接拉起系统安装器（含 Android 8+「安装未知应用」授权引导）。

## 构建方法

Windows，纯 SDK 工具链，不用 Gradle / AGP / Maven：

```bat
cd 工程\Hplay安卓
python build.py                         :: 增量构建，用 version.properties 当前版本号
python build.py --release               :: 发布：版本号 +1（versionCode = 日期 + 当日序号）并回写
python build.py --clean                 :: 先清 build\
python build.py --release --no-deploy   :: 只编译，不复制进 成品\
```

工具链要求（逐条）：

| 组件 | 版本 / 路径（相对本仓库根） | 用途 |
| --- | --- | --- |
| Python | 3.11 | 跑 `build.py` 与 `tools/` 下的脚本 |
| JDK | 21（Temurin 21.0.12.1+1）→ `工具/jdk-temurin/jdk-21.0.12.1+1` | javac / jar / keytool / d8 宿主 |
| Kotlin 编译器 | 2.4.20 → `工具/avp_kotlinc24/kotlinc` | kotlinc + `compose-compiler-plugin.jar` |
| Android SDK Build-Tools | 34.0.0 → `工具/android-build/bt/android-14` | aapt2 / aapt / zipalign / apksigner |
| Android Build-Tools 35 的 d8 | → `工具/android-build/bt35/android-15/lib/d8.jar` | 34 的 d8 对 appcompat-resources 类文件会 NPE |
| Android Platform | android-35 → `工具/android-build/platforms/android-35/android.jar` | 编译期 android.jar（Compose 1.7 需要 API 35） |
| AndroidX 依赖 | `工具/android-deps/` + `manifest.txt` | 77 个 classes.jar，由 `tools/fetch_deps.py` 拉取 |

目录布局约定：`build.py` 把 `ROOT.parent.parent` 当作工作区根，再找 `工程/AvPlay`（上游源码）与 `工具/`（工具链）。所以本仓库的工程放在 **`工程/Hplay安卓/`** 而不是仓库根 —— 只有这样 `ROOT.parent.parent` 才等于仓库根，上游与工具链才落在仓库内。把工具链铺到仓库根的 `工具/` 后即可原地构建，不依赖任何本机绝对路径（本仓库已按此布局实测跑通 kotlinc → d8 → aapt2 → 签名全流程）。

依赖拉取：

```bat
cd 工程\Hplay安卓
python tools\fetch_deps.py     :: 从阿里云 Maven 镜像拉 androidx/coil/okhttp/media3 闭包
```

补丁预检（10 秒，别等 90 秒的编译才发现锚点失配）：

```bat
python tools\patchguard.py
```

两个构建期变量（App 的「检查更新」靠它们拼 API 地址）：

```bat
set HPLAY_GH_REPO=OWNER/REPO
set HPLAY_GH_LINE=lab
```

不设时写入 `OWNER/REPO` 占位，App 会明确报「未配置仓库地址」，不会假装查过。

## 目录结构

```
.
├── 工程/
│   ├── Hplay安卓/              Android 工程（build.py 在这层）
│   │   ├── src/                自写源码（Kotlin + Compose），AndroidManifest.xml 在此
│   │   │   ├── com/han1me/     入口、环境桥、主题、UI
│   │   │   │   ├── lab/        网络与节点、CF 过盾、登录、多账户、备份
│   │   │   │   ├── player/     ExoPlayer 封装、控制层、手势、方向
│   │   │   │   ├── download/   HLS 下载器、前台服务、存储权限
│   │   │   │   ├── update/     检查更新：139 / 网盘 / GitHub / CI 四渠道
│   │   │   │   ├── cos/ tx/    三次元原生板块
│   │   │   │   ├── checkin/ subs/ getchu/ preview/ tablet/
│   │   │   │   └── ui/         组件、主题、设置页、关于页
│   │   │   └── res/            图标、样式、file_paths
│   │   ├── tools/              构建期脚本（补丁、预检、依赖、验收、发布）
│   │   │   ├── patches.py      上游 → Android 的补丁定义（UPSTREAM 字典）
│   │   │   ├── patchguard.py   补丁预检（锚点唯一性 / 结构体检）
│   │   │   ├── globalpatch.py  批量替换规则（同一条命多次）
│   │   │   ├── postpatch.py    生成后定点修正
│   │   │   ├── uifinetune.py   按结构匹配的 UI 微调
│   │   │   ├── fetch_deps.py   依赖拉取
│   │   │   └── verify_apk.py   产物静态验收
│   │   ├── res/                额外资源目录（与 src/res 一起交给 aapt2）
│   │   ├── assets/             资产（放 hplay-adrules.txt）
│   │   ├── keystore/           签名密钥 hplay-port.jks —— 丢了就无法覆盖安装
│   │   ├── build.py            构建管线
│   │   └── version.properties  版本号唯一事实源
│   └── AvPlay/                 上游源码（patches.py 的 UPSTREAM 引用 + 图标 + 去广告规则）
│       ├── hanime模块/src/com/javrocket/hanime/HanimeClient.kt
│       └── 独立版/             src/com/han1me/*.kt、hplay-adrules.txt、hplay-icon.png
├── 工具/                       （不入库）本机工具链，按上表铺进来
├── .github/workflows/build.yml CI 构建
├── .gitattributes              关掉行尾转换（补丁锚点必须保持 LF）
├── LICENSE                     Apache License 2.0
├── CREDITS.md                  完整引用与致谢清单
└── README.md
```

## 更新渠道

App 内「检查更新」可选四个来源，全部保留、互不替代：

| 渠道 | 取什么 | 备注 |
| --- | --- | --- |
| 移动云盘 139（直连） | 网盘目录清单 | 公网可达，出门也能查 |
| 自建网盘（OpenList） | 多个挂载目录轮询 | 兜底通道 |
| GitHub Releases | 本线的正式 Release | 读 `releases/latest`，实验版因标 prerelease 不会被当成正式版 |
| GitHub CI 构建 | `ci-*` 标签的预发布 Release | 匿名可读，不需要 token |

两条版本线可以在更新页自由切换：构建期只决定**默认**是哪条线，切换后以用户的选择为准 —— 否则实验版用户永远看不到主线版。

GitHub 渠道优先走 REST API，匿名限额（60 次/小时/IP）打满时退回 `releases.atom`；429/403、404、5xx、网络异常四类分开报，说人话。下载走 `browser_download_url` 直链，不耗 API 限额。

## 引用与致谢

完整清单见 [CREDITS.md](CREDITS.md)。

## 免责声明

- 本项目**仅供 18 岁以上成年人**学习与技术研究使用。
- 所有内容版权归各自权利人所有；本项目不存储、不托管、不分发任何媒体文件，只提供访问用户所指定站点的客户端。
- 本项目与所列任何站点、商标、公司**没有任何隶属、授权或合作关系**，官方行为与本项目无关。
- 请在下载后 **24 小时内删除**，并支持正版。
- **不得公开传播**本项目源码或产物；因使用本项目产生的一切后果由使用者自行承担。
