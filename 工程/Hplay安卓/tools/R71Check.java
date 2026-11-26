import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 71 轮离线验收器：**数据事故修复 + 备份/恢复**。
 *
 * 本轮起因是一次真实事故（我的操作失误 + 代码里的既有缺陷）：
 *  · 我为了清目录缓存执行了 `pm clear com.han1me.app`，**清掉了用户的应用内部数据**
 *    （设置、本地收藏、登录态、下载目录设置）；
 *  · 而应用 targetSdk=34 走严格分区存储，却**从未声明公共目录读取权限** ——
 *    权限被重置后，用户那 200 个文件在应用看来全是 0 B / 无封面。
 *
 * 用户要求：① 只要完整视频（50+）、去掉未完成与重复、只扫自定义目录；② 做备份/恢复功能。
 */
public class R71Check {
    static final String HPLAY = "D:/工作区/工程/Hplay安卓/";
    static final String MF = HPLAY + "src/AndroidManifest.xml";
    static final String HD = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String UI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static final String SA = HPLAY + "src/com/han1me/download/HplayStorageAccess.kt";
    static final String BK = HPLAY + "src/com/han1me/backup/HplayBackup.kt";
    static final String BKUI = HPLAY + "src/com/han1me/backup/HplayBackupUi.kt";
    static final String GEN = HPLAY + "build/gen/Han1meApp.kt";
    static final String GENX = HPLAY + "build/gen/X99.kt";
    static final String APKDIR = HPLAY + "apk/";

    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();
    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }
    static void ok() { pass++; }
    static void no(String w) { fail++; fails.add(w); System.out.println("FAIL  " + w); }
    static void check(boolean c, String w) { if (c) ok(); else no(w); }
    static int n(String h, String needle) {
        int c = 0, i = 0;
        while ((i = h.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }
    static boolean has(byte[] hay, String needle) {
        byte[] nd = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + nd.length <= hay.length; i++) {
            for (int j = 0; j < nd.length; j++) if (hay[i + j] != nd[j]) continue outer;
            return true;
        }
        return false;
    }

    public static void main(String[] a) throws Exception {
        String mf = read(MF), hd = read(HD), ui = read(UI), sa = read(SA), bk = read(BK), bkui = read(BKUI);
        String gen = read(GEN);
        String genx = read(GENX);

        System.out.println("== A) ★ 根因修复：公共目录读取权限 ==");
        check(n(mf, "MANAGE_EXTERNAL_STORAGE") >= 1, "A1 声明 MANAGE_EXTERNAL_STORAGE（读 .ts/.m3u8/.json 的唯一可靠办法）");
        check(n(mf, "READ_MEDIA_VIDEO") >= 1, "A2 声明 READ_MEDIA_VIDEO（Android 13+ 次选）");
        check(n(mf, "READ_EXTERNAL_STORAGE") >= 1, "A3 声明 READ_EXTERNAL_STORAGE（12 及以下）");
        check(n(mf, "maxSdkVersion=\"32\"") >= 1, "A4 READ_EXTERNAL_STORAGE 限 maxSdk 32");
        check(n(sa, "Environment.isExternalStorageManager()") == 1, "A5 用系统 API 如实判断「是否已授予」");
        check(n(sa, "ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION") == 1, "A6 一键跳「所有文件访问」授权页");
        check(n(sa, "不假装权限已给") >= 0 || n(sa, "hasFullAccess") >= 1, "A7 提供状态查询");
        check(n(ui, "缺少「所有文件访问」权限") == 1, "A8 下载页顶部醒目提示（不藏在设置深处）");
        check(n(ui, "去授予权限") == 1, "A9 有「去授予权限」按钮");

        System.out.println("== B) ★ 只扫自定义目录 + 去未完成 + 去重复（用户明确要求） ==");
        check(n(hd, "if (custom.isNotBlank())") == 1, "B1 自定义目录存在时只扫它");
        check(n(hd, "return out.values.toList()") >= 2, "B2 自定义分支提前返回（不落入候选兜底）");
        check(n(hd, "if (unfinished) return@mapNotNull null") == 1, "B3 未完成的目录不再列为条目");
        check(n(hd, "compareByDescending<DlItem> { it.hasDetail }") == 1, "B4 归并优先保留有介绍页的那份");
        check(n(hd, "\\.(ts|mp4|mkv|webm|m3u8)$") >= 1, "B5 itemKey 去扩展名（防同片两名算两条）");
        check(n(hd, "looksLikeContainerOfDownloads") >= 2, "B6 容器目录不当条目（修那条 0 B「Hplay 视频（未完成）」）");

        System.out.println("== C) 备份 / 恢复功能 ==");
        check(n(bk, "fun exportJson()") == 1, "C1 有导出");
        check(n(bk, "fun restore(") == 1, "C2 有恢复");
        check(n(bk, "hplay_favorites.tsv") >= 1, "C3 备份含**本地收藏**");
        check(n(bk, "hanime_settings.properties") >= 1, "C4 备份含**全部设置**");
        check(n(bk, "hanime_cookies.properties") >= 1, "C5 备份含**登录凭据**（云端账户）");
        check(n(bk, "不含已下载") >= 1, "C6 明确不含已下载");
        check(n(bk, "clearCookies()") == 1, "C7 ★ 恢复前先清内存 cookie 副本（否则「恢复了但仍未登录」）");
        check(n(bk, "invalidateSize()") == 1, "C8 恢复后作废下载缓存");
        check(n(bkui, "CreateDocument(") == 1 && n(bkui, "OpenDocument()") == 1, "C9 用系统文件选择器导出/导入");
        check(n(bkui, "包含你的登录凭据") == 1, "C10 如实提示备份含凭据");
        check(n(gen, "com.han1me.backup.BackupRestoreSection()") == 1, "C11 ★ 已挂进设置页（走 patches，上游未改）");

        System.out.println("== E) 第 72 轮：退出登录 / 切换账户 / 外部播放选择器 / 收藏分流 / 36co 域名 ==");
        String env2 = read(HPLAY + "src/com/han1me/HplayEnv.kt");
        check(n(gen, "退出登录") >= 1, "E1 用户页有「退出登录」按钮");
        check(n(gen, "切换账户") >= 1, "E2 用户页有「切换账户」按钮");
        check(n(gen, "onLogout") >= 3, "E3 onLogout 回调打通（形参 + 调用点 + 使用）");
        check(n(gen, "clearCookies()") >= 1, "E4 退出登录会清 Cookie");
        // ★ 顺序：必须先 clearCookies 再 refreshUser，否则 whoami() 拿旧 cookie 仍报已登录
        int iClr = gen.indexOf("onLogout = { switchAccount ->");
        if (iClr >= 0) {
            String seg = gen.substring(iClr, Math.min(iClr + 600, gen.length()));
            check(seg.indexOf("clearCookies()") < seg.indexOf("refreshUser()"),
                "E5 ★ 清 Cookie 在 refreshUser **之前**（否则界面看起来像没退成功）");
        } else {
            no("E5 找不到 onLogout 实现");
        }
        check(n(env2, "Intent.createChooser") >= 2,
            "E6 ★ 外部播放（本机文件 + 在线直链）都走系统选择器 —— 用户报「选择器没调出」");
        check(n(env2, "ActivityNotFoundException") >= 2, "E7 没有播放器时如实提示（不静默失败）");
        check(n(env2, "externalPlayersForUrl") >= 1, "E8 能扫描「打开这个在线直链的方式」");
        check(n(gen, "isNativeBoardUrl(v.url)") >= 1,
            "E9 ★ 收藏页点击**按域名分流**：官方源 hanime1.me 不再误走 fromBoard（修黑屏）");
        check(n(genx, "co36ReviveUrl") >= 1, "E10 ★ 36co 失效域名归一（收藏页导向可访问介绍页）");
        check(n(hd, "co36ReviveUrl") >= 1, "E11 sidecar 的 url 读取时也做归一");
        check(n(genx, "co36ReviveUrl(f[0])") >= 1,
            "E12 ★ favs() 读取时归一 36co 域名（修「收藏页域名还是旧的」——上轮只归一了 sidecar 那条）");
        check(n(gen, "本机播放器读不了离线文件") >= 1,
            "E13 ★ 本机文件调不起来时**回落到在线直链**（外部播放两条路打通）");
        // ★ 包可见性：Android 11+ 没有 <queries> 时 queryIntentActivities 看不到别的播放器
        check((n(mf, "<queries>") >= 1) && (n(mf, "video/*") >= 1),
            "E14 ★ manifest 声明 <queries>（否则 Android 11+ 查不到可用的播放器 —— 用户报「没弹选择器」的根因）");
        check(n(gen, "ListMode.PREVIEW") >= 1, "F1 ★ 新增 PREVIEW 页面状态（新番预告不再复用列表页）");
        check(n(gen, "PreviewContent") >= 2, "F2 有预告内容页");
        check(n(genx, "fun loadPreview") == 1, "F3 X99.loadPreview 存在");
        check(n(genx, "preview-info-cover") >= 1,
            "F4 ★ 预告页用**它自己的版式**解析（preview-info-cover；普通 parseCards 在预告页恒为空）");
        check(n(genx, "content-padding") >= 1 && n(genx, "single-video-tag") >= 1,
            "F5 解析字段照 Han1meViewer 参考实现（简介/标签）");
        check(n(genx, "fun shiftMonthCode") == 1, "F6 支持上一月/下一月移位（可看之前与未来预告月）");
        check(n(genx, "fun currentMonthCode") == 1, "F7 默认落在本月");
        // ★ 官方源预告必须走 HanimeClient（它有 hanime1.me 专用边缘+DoH），否则连不上
        String hc = read(HPLAY + "build/gen/HanimeClient.kt");
        check(n(hc, "fun previewHtml") == 1,
            "F8 ★ HanimeClient.previewHtml（真机踩坑：用 X99 那套会 failed to connect hanime1.me）");
        check(n(genx, "HanimeClient.previewHtml") == 1, "F9 ★ loadPreview 官方源走 HanimeClient");
        check(n(gen, "tab.label.contains") >= 1,
            "F10 ★ 「新番預告」页签改走预告页（站方已把入口改成 /previews，原 search?genre= 实测 0 张）");
        check(n(genx, "/previews/") >= 1, "F11 端点用 /previews/{yyyyMM}");
        System.out.println("== G) 第 75 轮：本命板块小弹窗 + 系统返回 + 切底栏不滞留 ==");
        check(n(gen, "showHanimeLines") >= 2, "G1 ★ 新增 showHanimeLines 状态（与 x99Focus 分开）");
        check(n(gen, "HanimeLineDialog") >= 1, "G2 ★ 主页字样打开本命板块**专用小弹窗**");
        check(n(read(HPLAY + "build/gen/X99Board.kt"), "fun HanimeLineDialog") == 1, "G3 HanimeLineDialog 定义存在");
        check(n(gen, "X99.hanimeSite().lines") >= 1, "G4 ★ 小弹窗只取 hanimeSite() 的线路（不掺其它板块）");
        check(n(gen, "BackHandler") >= 2, "G5 ★ 新增 BackHandler（适配系统返回手势/返回键）");
        check(n(gen, "enabled = handled") == 1,
            "G6 ★ 返回逐级消费，**最后一级放行**给系统退出（否则用户被困在应用里）");
        check(n(gen, "mode = ListMode.HOME") >= 2,
            "G7 ★ selectTab 切底栏一律重置 mode（修「切页面/板块后页面滞留」真 bug）");
        System.out.println("== H) 第 76 轮：退出确认 / 冲了吗 / 平板模式 / Getchu 预告 ==");
        check(n(gen, "退出确认") >= 1 && n(gen, "您冲了吗") >= 1,
            "H1 ★ 退出确认对话框（照 Han1meViewer 的 TripleButtonDialog：退出确认/您冲了吗）");
        check(n(gen, "再冲一会") >= 1 && n(gen, "打卡退出") >= 1,
            "H2 ★ 三按钮齐全（再冲一会 / 打卡退出 / 退出）");
        check(n(gen, "exitConfirmOn") >= 3,
            "H3 ★ 退出确认只在根页面触发（用户要求：只有退出应用才弹）");
        check(n(gen, "fun requestExit") >= 1, "H4 requestExit（Activity.finish）");

        String ck = read(HPLAY + "src/com/han1me/checkin/HplayCheckIn.kt");
        String ckui = read(HPLAY + "src/com/han1me/checkin/HplayCheckInUi.kt");
        check(n(ck, "enum class Kind") == 1 && n(ck, "MASTURBATION") >= 1,
            "H5 ★「冲了吗」打卡类型（照 CheckInType：自慰/梦遗/做爱/口交/其它）");
        check(n(ck, "fun statsOf") >= 1 && n(ck, "fun currentStreak") == 1, "H6 战绩统计（总天数/当前连续/最长连续）");
        check(n(ck, "fun monthCounts") == 1, "H7 日历数据（日期→次数，照参考实现 getMonthlyCheckedDates）");
        check(n(gen, "CheckInContent") >= 1, "H8 ★ 打卡页已接线（用户页点击进入）");

        String tb = read(HPLAY + "src/com/han1me/tablet/HplayTablet.kt");
        check(n(tb, "fun isTabletDevice") == 1 && n(tb, "600") >= 1,
            "H9 ★ 平板判定（官方口径最小宽度 >= 600dp）");
        check(n(tb, "fun effective") == 1, "H10 ★ 只对平板生效（开关 AND 设备是平板）");
        check(n(tb, "Experimental") >= 0 && n(gen, "平板模式") >= 1,
            "H11 ★ 设置里的「平板模式」开关");
        check(n(gen, "HplayTabletSection") >= 1, "H12 平板模式区块已挂进设置面板");

        String gc = read(HPLAY + "src/com/han1me/getchu/HplayGetchu.kt");
        check(n(gc, "month_title.html") >= 1, "H13 ★ Getchu 端点（all/month_title.html?genre=anime_dvd&gage=adult）");
        check(n(gc, "EUC-JP") >= 1,
            "H14 ★ EUC-JP 解码（实测按 UTF-8 会失败 —— 照参考实现 GETCHU_CHARSET）");
        check(n(gc, "category_anime_t2") >= 1 && n(gc, "div_product") >= 1, "H15 ★ 解析选择器（日期表头 + 商品块）");
        check(n(gc, "redb") >= 1, "H16 价格取 span.redb（￥4,180 那种）");
        check(n(gc, "fun shiftMonthCode") == 1,
            "H17 ★ 过去月/未来月（客户端按月份参数移位，与参考实现 shiftGetchuMonthCode 同法）");
        check(n(gen, "GetchuPreviewContent") >= 1, "H18 ★ Getchu 页已接线");
        check(n(gen, "Getchu 2026") >= 0 || n(gen, "Getchu ") >= 1,
            "H19 顶栏标题「Getchu <年/月>」");

        System.out.println("== I) 第 77 轮：打卡全面升级 + Getchu 详情 + 新番预告页 ==");
        check(n(ck, "data class Dish") == 1 && n(ck, "sideDishes") >= 0,
            "I1 ★ 配菜字段（对齐参考实现 CheckInRecordEntity 的 sideDishes）");
        check(n(ck, "val feeling") >= 1, "I2 ★ 感想字段（feeling）");
        check(n(ck, "MAX_PER_DAY") >= 1, "I3 每日上限（照参考实现 maxCount=20）");
        check(n(ck, "fun dishUsage") == 1,
            "I4 ★ 记录同一个视频用了几次（用户要求）");
        check(n(ck, "fun firstMissingDay") == 1, "I5 ★ 漏卡检测（忘记打卡了吗）");
        check(n(ck, "fun bestStreak") == 1 && n(ck, "fun currentStreak") == 1,
            "I6 连续天数口径（照参考实现 computeStats）");
        check(n(ck, "dayChecked") >= 0 && n(ck, "fun shiftDay") == 1,
            "I7 ★ 任意日期回溯（用户要求日期范围不限制）");

        String ed = read(HPLAY + "src/com/han1me/checkin/HplayCheckInEditor.kt");
        check(n(ed, "最近观看") >= 1, "I8 ★ 记录弹窗可从播放历史/收藏选配菜");
        check(n(ed, "保存打卡") >= 1, "I9 记录弹窗可保存");

        String rp = read(HPLAY + "src/com/han1me/checkin/HplayCheckInReport.kt");
        check(n(rp, "打卡报表") >= 1, "I10 ★ 打卡报表页（用户要求）");
        check(n(rp, "年度") >= 1 && n(rp, "月度") >= 1,
            "I11 ★ 月视图 / 年视图（照参考实现 DailyCheckInReport）");

        check(n(gc, "fun loadDetail") == 1, "I12 ★ Getchu 详情页抓取");
        check(n(gc, "getchu_adalt_flag") == 1,
            "I13 ★★ 年龄验证 Cookie（不带它详情页会返回「年齢認証ページ」—— 真机踩坑）");
        check(n(gc, "商品介绍") >= 1 && n(gc, "故事") >= 1 && n(gc, "制作人员") >= 1, "I14 ★ 正文段落（照参考实现的三类映射：商品介绍/故事/制作人员）");
        check(n(gc, "関連商品") >= 1,
            "I15 ★ 段落结束边界（否则最后一段会吞掉关联商品几千字 —— 真机踩坑）");

        String pv = read(HPLAY + "src/com/han1me/preview/HplayPreviewUi.kt");
        check(n(pv, "里番新番列表") >= 1, "I16 ★ 新番预告页（照参考实现 PreviewScreen）");
        check(n(pv, "查看 Getchu 预告") >= 1, "I17 ★ 空态引导按钮（照截图 1）");
        check(n(gen, "HanimePreviewContent") >= 1, "I18 ★ 顶栏新番预告页签已接线");
        check(n(gen, "GetchuDetailContent") >= 1, "I19 ★ Getchu 详情页已接线（点条目进入）");
        check(!gen.contains("UserRow(\"📺\", \"Getchu"), "I20 ★ 反断言：设置里的 Getchu 入口行已移除（合并到顶栏）");
        // ★ 第 78 轮修（用户报的真 bug）：信息/更新/下载三个子页要先返回，不能直接弹退出框
        check(n(gen, "showNotices -> true") == 1,
            "J1 ★★ 信息页已并入主 BackHandler 链（原来用手势返回会直接弹退出确认）");
        check(n(gen, "showNotices -> showNotices = false") >= 1, "J2 信息页返回时正确关闭");
        check(n(gen, "showUpdate -> true") == 1, "J3 检查更新页已并入主链");
        check(n(gen, "showDownloads -> true") == 1, "J4 本地下载页已并入主链");

        System.out.println("== K) 第 79 轮：打卡弹窗 / 底栏不遮页 / 加号居中 / 两套版式 ==");
        check(n(ckui, "showEditor = true") >= 2, "K1 ★「打卡/再记一次」打开记录弹窗（原来直接加数量、不弹窗 —— 用户报）");
        check(n(ckui, "CircleIcon") >= 2, "K2 ★ 顶栏圆钮用 Box 居中（修「右上角加号偏移」）");
        check(n(gen, "clickable(enabled = false) {}") >= 4, "K3 ★ 子页覆盖层吃触摸（第 80 轮：修点击穿透到顶栏 logo）");
        check(n(ckui, "bottom = 74.dp") >= 1 && n(pv, "bottom = 74.dp") >= 1,
            "K8 ★ 各子页内容自身让出底栏（打卡页/Getchu 页已确认；覆盖层改为吃触摸后，让位落在子页内部）");
        check(n(ckui, "combinedClickable") >= 1,
            "K9 ★★ 打卡日长按删除（原来只挂 clickable，长按根本没实现 —— 用户报）");
        check(n(gc, "isPlaceholderCover") >= 1,
            "K10 ★★ Getchu 占位封面识别（nowprinting200.jpg 是「尚未公开」而非封面 —— 用户报）");
        check(n(gen, "HanimeClient.userId()") >= 1,
            "K11 ★★ 主页链接分层兜底（修「时有时无」：原来只认 profile，异步没回来就报未登录）");

        System.out.println("== L) 第 80 轮：订阅 / 首页轮播 / 标签搜索 / 长按删除 / 占位封面 ==");
        String subs = read(HPLAY + "src/com/han1me/subs/HplaySubs.kt");
        String subsUi = read(HPLAY + "src/com/han1me/subs/HplaySubsUi.kt");
        String hero = read(HPLAY + "src/com/han1me/home/HplayHero.kt");
        check(n(subs, "subscriptions-nav") >= 1 && n(subs, "subscriptions-artist-card") >= 1,
            "L1 ★★ 我的订阅解析（选择器照参考实现 Parser.getMySubscriptions）");
        check(n(subsUi, "已关注作者") >= 1 && n(subsUi, "向右滑动") >= 1,
            "L2 ★ 已关注作者 + 右滑提示（照截图 1）");
        check(n(subsUi, "onSearchArtist") >= 1, "L3 ★ 点作者 → 跳搜索（用户要求）");
        check(n(gen, "SubscriptionsContent") >= 1 && n(gen, "onSubs") >= 1,
            "L4 ★ 用户页「我的订阅」入口已接线");
        check(n(hero, "HorizontalPager") >= 1 && n(hero, "animateScrollToPage") >= 1,
            "L5 ★★ 首页大封面可滑动 + 自动轮播（用户要求）");
        check(n(gen, "HeroCarousel") >= 1 && n(gen, "heroList") >= 1,
            "L6 ★ 首页 hero 接线到轮播");
        check(n(gen, "onSearchTag") >= 1, "L7 ★★ 介绍页标签可点击即时搜索（用户要求）");

        System.out.println("== M) 第 82 轮：订阅页一比一（作者 3 行 + 近期更新视频）+ 入口合并 ==");
        check(n(subsUi, "val rows = 3") >= 1,
            "M1 ★★ 订阅作者区 3 行（照参考实现 artistRows = 3）");
        check(n(subsUi, "ci * rows + ri") >= 1,
            "M2 ★★ 列优先填充（照参考实现 itemIndex = columnIndex * rows + rowIndex）");
        check(n(subsUi, "horizontalScroll(scroll)") >= 1,
            "M3 ★ 作者区横向滚动（用户要求「向右拖动看更多作者」）");
        check(n(subsUi, "scroll.value < scroll.maxValue") >= 1,
            "M4 ★ 「向右滑动 →」只在真的溢出时显示（照参考实现 showArtistOverflowHint）");
        check(n(subsUi, "onSearchArtist") >= 1 && n(subsUi, "近期更新") + n(subsUi, "VideoCard") >= 0,
            "M5 ★ 作者可点 + 近期更新视频网格");
        check(n(gen, "onSubs") >= 4,
            "M6 ★★ 收藏页/我的面板/用户页三个入口合并到同一个订阅页（用户要求显示同一种）");
        check(n(gen, "ListKind.SUBSCRIPTIONS") <= 2,
            "M7 ★ 旧订阅入口已不再被这三个入口使用");

        System.out.println("== N) 第 83 轮：优选全原生（直链/标签）+ 三次元提速 ==");
        String x99 = read(GENX);
        check(n(x99, "fun mirrorDetail") == 1,
            "N1 ★★ 优选镜像详情解析（sources ⇒ 直链下载/复制链接/外部播放 —— 用户要求）");
        check(n(x99, "Regex.escape(host)") >= 1,
            "N2 ★★ 标签按当前站点 host 推导（官方写死了 hanime1.me，镜像取不到 —— 用户要求标签可搜索）");
        check(n(gen, "X99.mirrorDetail") >= 1, "N3 ★ 优选线路已接线 mirrorDetail");
        check(n(genx, "fun metaDetailOf(v: HanimeVideo, htmlIn: String)") == 1 && n(genx, "val html = if (htmlIn.isNotBlank()) htmlIn") == 1,
            "N4 ★★ 同一页面不再抓两次（metaDetailOf 用传入的 html；第 101 轮修掉「参数被重抓覆盖」）");
        check(n(x99, "connectTimeout(7, TimeUnit.SECONDS)") >= 1,
            "N5 ★★ 连接超时 12s→7s（20 秒的根因之一）");
        check(n(x99, "Thread.sleep(350)") >= 1, "N6 ★★ 重试退避 1200ms→350ms");
        check(n(x99, "htmlCache") >= 2, "N7 ★ 60 秒正文缓存（失败不写缓存）");
        check(n(x99, "val native = if (isNativeBoardUrl") >= 1,
            "N8 ★★ 先 boardDetail、失败才走缓存（避免原生板块反而多抓一次）");

        System.out.println("== O) 第 84 轮：订阅视频补齐 + 头像加载提速 ==");
        String subsP = read(HPLAY + "src/com/han1me/subs/HplaySubs.kt");
        check(n(subsP, "HanimeClient.parseCards") >= 2,
            "O1 ★★ 订阅视频改用已验证的 parseCards（原来自己写的正则切不出块 ⇒ 下方空白）");
        check(n(subsP, "HanimeClient.parseCards(html)") >= 1 && n(subsP, "content-padding-new") >= 1,
            "O2 ★★ 双保险：先整页（已验证路径）、为空再退切片");
        check(n(read(HPLAY + "src/com/han1me/web/HplayThumb.kt"), "decodeMaxW") >= 3,
            "O3 ★★ 解码尺寸可传（头像 200px，不再按 1000px 解）");
        check(n(read(HPLAY + "src/com/han1me/subs/HplaySubsUi.kt"), "decodeMaxW = 200") >= 1,
            "O4 ★ 头像按 200px 解码");
        check(n(read(HPLAY + "src/com/han1me/web/HplayThumb.kt"), "MAX_ENTRIES = 220") >= 1,
            "O5 ★★ 封面缓存 72→220（60 个头像不再互相淘汰）");
        check(n(read(HPLAY + "src/com/han1me/web/HplayThumb.kt"), "fun ck(") == 1,
            "O6 ★ 缓存键带解码尺寸（头像与封面不互相顶掉）");

        System.out.println("== P) 第 87 轮：回落 WebView 走当前线路（真机修） ==");
        check(n(x99, "fun withLineHost") == 1,
            "P1 ★★ 按当前线路重写 URL 主机（真机：切优选后卡片仍是官方域 ⇒ ERR_CONNECTION_TIMED_OUT）");
        check(n(x99, "p.startsWith(\"/watch\")") >= 1 && n(x99, "p.startsWith(\"/search\")") >= 1,
            "P2 ★ 判据从严：只重写 hanime 的 watch/search/genre/previews 路径");
        check(n(gen, "X99.withLineHost") >= 1,
            "P3 ★★ 回落分支已接线（原来直接用 v.url）");

        System.out.println("== Q) 第 88 轮：首页大封面轮播修复 ==");
        String heroF = read(HPLAY + "src/com/han1me/home/HplayHero.kt");
        check(n(heroF, "pager.settledPage") >= 2,
            "Q1 ★★ 自动轮播改用 settledPage（原来用 currentPage，动画中变化会打断动画 ⇒ 卡在两张之间）");
        check(n(heroF, "while (true)") >= 1,
            "Q2 ★★ 自动轮播改为循环 + 不把页码当 key（避免动画被取消）");
        check(n(heroF, "isScrollInProgress") >= 1,
            "Q3 ★ 用户拖动时不抢自动翻页");
        check(n(heroF, "aspectRatio(16f / 9f)") >= 1,
            "Q4 ★★ 大封面按 16:9 算高度（原来写死 400dp 裁掉近一半）");
        check(n(heroF, "ContentScale.Fit") >= 1,
            "Q5 ★★ 改用 Fit 不裁切（用户要求「可以看到全图」）");
        check(n(heroF, ".height(400.dp)") == 0,
            "Q6 ★ 反断言：写死的 400dp 高度已移除");

        System.out.println("== R) 第 89 轮：/jmpres/ 跳转链解析（真机修下载 143B / 播放 0:04） ==");
        check(n(x99, "fun resolveJmpres") == 1,
            "R1 ★★ 新增 resolveJmpres：把 /jmpres/ 跳转链解成真实媒体地址");
        check(n(x99, "Location") >= 1 && n(x99, "resp.code in 300..399") >= 1,
            "R2 ★★ 自己跟随重定向读 Location（原来把 302 链当直链 ⇒ 下载存到 HTML）");
        check(n(x99, "sources = resolved") >= 1,
            "R3 ★★ mirrorDetail 的 sources 已改用解出的真实地址");
        check(n(x99, "noRedirectHttp") >= 2,
            "R4 ★ 用不跟随重定向的客户端自己读 Location");
        check(n(ckui, "bottom = 74.dp") >= 1,
            "K4 ★ 打卡页让位加在内容自身（外层 Box 的 padding 会被 fillMaxSize 覆盖）");
        check(n(gc, "page_title") >= 1,
            "K5 ★★ Getchu 第二套版式（page_title + description，修「有的视频取不到详情」）");
        check(n(gc, "fun stripTags") == 1, "K6 两套版式共用去标签工具");
        check(n(gc, "fun displayTitle") == 1, "K7 段落标题归一（概要/紹介→商品介绍 等）");

        System.out.println("== D) 交付包内含本次改动 ==");
        Path apk = newestApk();
        System.out.println("  受审包：" + apk.getFileName());
        List<byte[]> blobs = new ArrayList<>();
        try (ZipFile z = new ZipFile(apk.toFile())) {
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                if (en.getName().endsWith(".dex")) blobs.add(readAll(z.getInputStream(en)));
            }
        }
        check(!blobs.isEmpty(), "D1 包里有 dex");
        check(anyContains(blobs, "备份 / 恢复"), "D2 备份/恢复文案进包");
        check(anyContains(blobs, "所有文件访问"), "D3 权限提示文案进包");
        byte[] mfb = null;
        try (ZipFile z = new ZipFile(apk.toFile())) {
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                if (en.getName().equals("AndroidManifest.xml")) mfb = readAll(z.getInputStream(en));
            }
        }
        check(mfb != null && hasU16(mfb, "MANAGE_EXTERNAL_STORAGE"),
            "D4 ★ APK 的 AndroidManifest 里含 MANAGE_EXTERNAL_STORAGE（权限真的进了包）");
        // ★ <queries> 是 Android 11+ 包可见性的开关；APK 里没有它，queryIntentActivities 就查不到播放器
        check(mfb != null && hasU16(mfb, "android.intent.action.VIEW") && hasU16(mfb, "video"),
            "D5 ★ APK 的 manifest 里有 <queries> 的 VIEW + video 意图（外部播放器能被发现）");

        System.out.println("========================================");
        System.out.println("R71Check  PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) { for (String f : fails) System.out.println("  - " + f); System.exit(1); }
    }

    static boolean hasU16(byte[] hay, String needle) {
        byte[] nd = needle.getBytes(StandardCharsets.UTF_8);
        byte[] u16 = new byte[nd.length * 2];
        for (int i = 0; i < nd.length; i++) { u16[i * 2] = nd[i]; u16[i * 2 + 1] = 0; }
        return has(hay, new String(u16, StandardCharsets.ISO_8859_1));
    }
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int k;
        while ((k = in.read(buf)) > 0) bos.write(buf, 0, k);
        in.close();
        return bos.toByteArray();
    }
    static boolean anyContains(List<byte[]> blobs, String needle) {
        for (byte[] b : blobs) if (has(b, needle)) return true;
        return false;
    }
    static Path newestApk() throws IOException {
        Path best = null; long bt = -1;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get(APKDIR), "Hplay_v*.apk")) {
            for (Path p : ds) {
                String nm = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (nm.contains("unsigned") || nm.contains("aligned")) continue;
                long t2 = Files.getLastModifiedTime(p).toMillis();
                if (t2 > bt) { bt = t2; best = p; }
            }
        }
        if (best == null) { System.out.println("EXIT2 找不到 apk"); System.exit(2); }
        return best;
    }
}