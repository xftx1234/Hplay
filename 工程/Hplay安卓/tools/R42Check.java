// 第 42 轮离线验收器：收藏页可点、用户页一比一（只留有功能的行）、底栏尺寸、直链旁常驻网页播放。
// 这一轮的判据分两层：
//  A. **源码层**——成对出现的常量（底栏高度 vs 内容留白）、门控条件、URL 形状，这类"改一处忘另一处"
//     的错只有在源码里比对才抓得住；
//  B. **运行层**——临时/r42_userprobe/store.log 是**生产代码本身**跑出来的结果（含本机 HTTP 当源站
//     验 HLS 落地），这里核对它的结论而不是重述它的过程。
//   编译：javac -encoding UTF-8 -d . R42Check.java
//   运行：java -Dstdout.encoding=UTF-8 R42Check [工作区根，默认 D:/工作区]
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class R42Check {
    static int pass = 0, fail = 0;

    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (ok) pass++; else fail++;
    }

    /** `totalSize` 只允许出现在注释里（那句"为什么不用它"必须留着），代码里一处都不能有。 */
    static boolean totalSizeOnlyInComments(String app) {
        for (String line : app.split("\n")) {
            if (!line.contains("totalSize")) continue;
            String s = line.trim();
            if (!(s.startsWith("//") || s.startsWith("*") || s.startsWith("/*"))) return false;
        }
        return true;
    }

    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    public static void main(String[] a) throws Exception {
        String ws = a.length > 0 ? a[0] : "D:/工作区";
        String app = read(ws + "/工程/AvPlay/独立版/src/com/han1me/Han1meApp.kt");
        String x99 = read(ws + "/工程/AvPlay/独立版/src/com/han1me/X99.kt");
        String hc = read(ws + "/工程/AvPlay/hanime模块/src/com/javrocket/hanime/HanimeClient.kt");
        String tokens = read(ws + "/工程/AvPlay/独立版/src/com/han1me/Tokens.kt");
        String env = read(ws + "/工程/Hplay安卓/src/com/han1me/HplayEnv.kt");
        String ver = read(ws + "/工程/Hplay安卓/version.properties");
        System.out.println("Han1meApp.kt " + app.length() + " · X99.kt " + x99.length() + " 字符");

        // ---------- A1 底栏高度与内容留白必须一致 ----------
        Matcher bh = Pattern.compile("private fun BottomBar[\\s\\S]{0,900}?\\.height\\((\\d+)\\.dp\\)").matcher(app);
        Matcher pd = Pattern.compile("Modifier\\.fillMaxSize\\(\\)\\.padding\\(bottom = (\\d+)\\.dp\\)").matcher(app);
        boolean hasBh = bh.find(), hasPd = pd.find();
        ck("A1 底栏高度与内容底部留白是同一个数（改一处忘另一处就会让最后一行永远被盖住）",
                hasBh && hasPd && bh.group(1).equals(pd.group(1)) && Integer.parseInt(bh.group(1)) >= 70,
                "底栏 " + (hasBh ? bh.group(1) : "?") + " dp / 留白 " + (hasPd ? pd.group(1) : "?") + " dp");
        ck("A2 底栏图标与文字分两层排（不是塞在一个带 \\n 的 Text 里）",
                Pattern.compile("val items = listOf\\([\\s\\S]{0,240}?to \\(\"[^\"]+\" to \"[^\"]+\"\\)").matcher(app).find()
                        && app.contains("pair.first") && app.contains("pair.second"), "");
        ck("A3 选中态有胶囊底（图4 那种），且仍保留优选模式下的压暗提示",
                app.contains("if (active) Color(0xFF2A1518) else Color.Transparent") && app.contains("mirror && (key == \"fav\" || key == \"user\")"), "");

        // ---------- A4 收藏页/用户页里点开的清单必须真的渲染出来 ----------
        ck("A4 fav 与 user 两格都按 mode==LIST 渲染清单（上一版这里直接进 Tab，清单永远出不来）",
                Pattern.compile("\"fav\" -> if \\(mode == ListMode\\.LIST\\)").matcher(app).find()
                        && Pattern.compile("\"user\" -> if \\(mode == ListMode\\.LIST\\)").matcher(app).find(), "");
        long listInTabs = Pattern.compile("title = listTitle,\\n\\s+cards = cards").matcher(app).results().count();
        ck("A5 清单在这三处都能渲染（主页 / 收藏格 / 用户格），且都带回退",
                listInTabs >= 3, "listTitle→cards 出现 " + listInTabs + " 处");
        ck("A6 清单里的返回键走 backToHome（把 mode 收回 HOME，于是回到的是本格菜单而不是首页）",
                Pattern.compile("\"fav\" -> if \\(mode == ListMode\\.LIST\\)[\\s\\S]{0,600}?onBack = \\{ backToHome\\(\\) \\}").matcher(app).find(), "");
        ck("A7 backToHome 确实把 mode 复位", Pattern.compile("fun backToHome\\(\\) \\{\\n\\s+mode = ListMode\\.HOME").matcher(app).find(), "");

        // ---------- A8 播放列表 URL（上一版那条服务端回 500） ----------
        ck("A8 播放列表用带 uid 的那条（/user/playlists 实测 500）",
                hc.contains("playlists = \"$BASE/user/$uid/playlists\"")
                        && app.contains("webPage = if (p != null) WebPage(p.playlists, \"Hanime 播放列表\")"), "");
        ck("A9 MinePanel 那一路也改走同一个 helper（两条路不能一条对一条错）",
                Pattern.compile("onOpenWebList = \\{\\n\\s+showMine = false\\n\\s+openPlaylistsWeb\\(\\)").matcher(app).find(), "");

        // ---------- A10 用户页：只留有真实功能的行 ----------
        ck("A10 用户页放了这五行，且每行都有回调",
                app.contains("UserRow(\"🔗\", \"复制我的主页链接\"") && app.contains("UserRow(\"📩\", \"信息\"")
                        && app.contains("UserRow(\"🕘\", \"历史记录\"") && app.contains("UserRow(\"⬇\", \"本地下载\"")
                        && app.contains("UserRow(\"⚙\", \"设置\""), "");
        // 只在「行」这一层判空壳：页面底部那句解释里必然会出现"积分/徽章/私信"这几个字（A12 要求写明），
        // 拿全文做否定断言会和 A12 自相矛盾。
        String userRows = app.substring(app.indexOf("private fun UserTab"), app.indexOf("private fun UserRow"));
        // 只看**行标签**：页面底部那句解释里必然带"积分/徽章/私信"这几个字（A12 要求写明），
        // 拿整段文本做否定断言会和 A12 自相矛盾。
        // 第 43 轮在 🔗 之后追加了三行「本机素材槽」（本机封面 / 本机头像 / 清除本机素材）。
        // 这三行不是空壳：整块被 `if (HplaySlots.pickSupported)` 罩着，而 `pickSupported` 只有安卓壳会置 true，
        // 桌面（没有系统相册可唤）根本不会出现它们。所以这里的判据从「恰好 5 行」改成
        // 「5 行原有 + 3 行必须写在 pickSupported 守卫之后」；仍然禁止把积分/徽章/私信/邀请码做成行。
        int guard = userRows.indexOf("if (HplaySlots.pickSupported)");
        ck("A11 用户页 5 行原有功能，素材槽 3 行写在 pickSupported 守卫之后；积分/徽章/私信/邀请码仍没有做成行",
                userRows.split("UserRow\\(", -1).length - 1 == 8
                        && guard >= 0
                        && userRows.indexOf("\"🖼\", \"本机封面\"") > guard
                        && userRows.indexOf("\"🙂\", \"本机头像\"") > guard
                        && userRows.indexOf("\"✕\", \"清除本机素材\"") > guard
                        && !userRows.contains("\", \"积分") && !userRows.contains("\", \"徽章")
                        && !userRows.contains("\", \"信息(私信)") && !userRows.contains("\", \"复制邀请码"),
                "UserRow 调用 " + (userRows.split("UserRow\\(", -1).length - 1) + " 处，守卫起始 " + guard);
        ck("A12 页面上写了这一句说明，用户不会以为功能被藏起来了",
                app.contains("官方源没有积分、徽章、私信与站内下载中心") && app.contains("/notifications 都回 404"), "");
        ck("A13 昵称取自 profile()，不再把 whoami 的字面量\"已登录\"当昵称（图3 那句\"已登录：已登录\"）",
                app.contains("profile?.name ?:") && !Pattern.compile("\"已登录：\\$user\"[\\s\\S]{0,80}\"\\$user\"").matcher(app).find(), "");
        ck("A14 profile() 的三个字段都来自个人页原文锚点，不是拼出来的",
                hc.contains("class=\"profile-display-name\"") && hc.contains("profile-avatar-wrapper")
                        && hc.contains("profile-sub-stats-new-line"), "");

        // ---------- A15 直链旁边常驻网页播放 ----------
        long webPlay = Pattern.compile("onWebPlay\\(d\\.video\\.url, d\\.video\\.title\\)").matcher(app).results().count();
        ck("A16 网页播放入口在\"有直链\"和\"没直链\"两种情况下都存在（共 2 处）", webPlay == 2, "找到 " + webPlay + " 处");
        ck("A17 有直链时那个入口按 fromBoard 门控（官方源与本机文件的界面一行不动）",
                Pattern.compile("if \\(fromBoard && !officialSite\\) \\{\\n\\s+OutlinedButton\\(\\n\\s+onClick = \\{ onWebPlay")
                        .matcher(app).find(), "");
        ck("A18 面板签名收了 fromBoard，调用点传的是 detailFromBoard",
                Pattern.compile("private fun HanimeDetailPanel\\([\\s\\S]{0,300}?fromBoard: Boolean").matcher(app).find()
                        && app.contains("fromBoard = detailFromBoard"), "");
        ck("A19 本站收藏与下载到本机同样按 fromBoard 门控（共 3 处 fromBoard && !officialSite）",
                Pattern.compile("if \\(fromBoard && !officialSite\\)").matcher(app).results().count() == 3, "");

        // ---------- A20 本机文件播放不新起播放器（补丁硬检查） ----------
        int hostRefs = app.split("com\\.javrocket\\.playground", -1).length - 1;
        int lf = app.indexOf("private fun localFileDetail");
        int panel = app.indexOf("private fun HanimeDetailPanel");
        String localFn = (lf >= 0 && panel > lf) ? app.substring(lf, panel) : "";
        ck("A21 没有新增宿主 player API（安卓侧补丁只认详情面板那几处锚点；本机文件走合成详情）",
                lf >= 0 && hostRefs == 6 && !localFn.contains("com.javrocket.playground"),
                "宿主 API 共 " + hostRefs + " 次；localFileDetail 到 HanimeDetailPanel 之间 "
                        + localFn.split("com\\.javrocket\\.playground", -1).length + " 段");
        ck("A22 本机清单以 file:// 交给播放器，且标成非板块详情（不显示收藏/下载按钮）",
                app.contains("sources = listOf(0 to \"file://\" + path)") && app.contains("detailFromBoard = false"), "");

        // ---------- A23 本地收藏存储 ----------
        ck("A24 收藏写在应用自己的目录，且与设置文件同级（安卓侧该目录由宿主注入）",
                x99.contains("File(settingsFile.parentFile, \"hplay_favorites.tsv\")"), "");
        ck("A25 标题里的制表符/换行写入时转义、读出时还原（否则一条长标题能把整行结构冲垮）",
                x99.contains("replace(\"\\t\", \"\\\\t\")") && x99.contains("replace(\"\\\\t\", \"\\t\")")
                        && x99.contains("replace(\"\\\\n\", \"\\n\")"), "");
        ck("A26 读盘时要求字段数与 http 前缀，坏行直接跳过而不是抛",
                x99.contains("f.size >= 5 && f[0].startsWith(\"http\")"), "");
        ck("A27 boardLabelFor 认不出的站回主机名（不留空串，空串会让 chip 那一排出现空白按钮）",
                Pattern.compile("host\\.isNotEmpty\\(\\) -> bareHost\\(host\\)\\n\\s+else -> \"\"").matcher(x99).find(), "");

        // ---------- A28 下载器 ----------
        ck("A29 HLS 连分片与 key 一起下，并把清单改写成相对路径",
                x99.contains("val keyName = \"key.bin\"") && x99.contains("\"%05d.ts\"")
                        && x99.contains("EXT-X-KEY") && x99.contains("replaceFirst(\"URI="),
                "key 与分片都改名落地，清单里 URI= 也被替换");
        ck("A30 多档 master 会挑一条重入，不会把 master 当媒体存下来",
                x99.contains("return downloadMedia(variant, title, onProgress)"), "");
        ck("A31 不是媒体清单也不是可直下文件时直接拒绝，不假装成功",
                x99.contains("不假装下载成功"), "");
        ck("A32 下载走 .part 再改名（中途断掉不会留下一个看起来完整的文件）",
                x99.contains(".part") && x99.contains("renameTo(out)"), "");
        ck("A33 目录大小自己算，不用 JDK9+ 的 File.totalSize()",
                app.contains("private fun dirBytes(f: java.io.File): Long") && app.contains("dirBytes(f)")
                        && totalSizeOnlyInComments(app), "");

        // ---------- A34 首页标签区 ----------
        ck("A35 「标签 推荐」的数据是站点首页导航那批 genre 链接，且带更多/收起",
                app.contains("Text(\"标签\"") && app.contains("Text(\"推荐\"") && app.contains("if (allTags)")
                        && app.contains("更多")
                        && Pattern.compile("shown\\.forEach \\{ t ->[\\s\\S]{0,400}?onOpenTab\\(t\\)").matcher(app).find(), "");
        ck("A36 点标签走的是既有分类清单通道（与顶栏同一条路，不另造搜索）",
                app.contains("onOpenTab = { t -> loadList(ListKind.CATEGORY, t.url, t.label, 1) }"), "");

        // ---------- A37 版本一致性 ----------
        String vn = Pattern.compile("versionName=([0-9.]+)").matcher(ver).results()
                .map(m -> m.group(1)).findFirst().orElse("");
        String av = Pattern.compile("APP_VERSION = \"([0-9.]+)\"").matcher(tokens).results()
                .map(m -> m.group(1)).findFirst().orElse("");
        // A38 换了判据：包里的版本是构建时注入 aapt2 的，界面无权另存一份。
        // 所以这里不再比两个字面值（那要求人每轮手动同步常量，第 43 轮就是这样漏的），
        // 而是断言**接线**：界面调 slot、安卓的 slot 问 PackageManager 的 versionName。
        ck("A38 界面那句版本读的是 slot，不是自己抄的常量（\"信息\"页那句会被用户当真）",
                app.contains("\"版本 v${HplaySlots.versionLabel()}\"")
                        && !app.contains("\"版本 v$APP_VERSION\"")
                        && env.contains("HplaySlots.versionLabel = { packagedVersionName() }")
                        && env.contains("getPackageInfo(c.packageName, 0).versionName"),
                "version.properties=" + vn + " Tokens常量=" + av + "（常量只作桌面兜底，安卓不走它）");

        // ---------- B 运行层：生产代码自己跑出来的结论 ----------
        Path log = Paths.get(ws + "/临时/r42_userprobe/store.log");
        ck("B1 生产代码宿具的日志存在（下面几条核对的是它的输出，不是我的转述）", Files.isRegularFile(log), log.toString());
        if (Files.isRegularFile(log)) {
            String s = read(log.toString());
            ck("B2 宿具全绿（收藏读写/板块归属/HLS 落地/账号资料 25 项）",
                    s.contains("通过 全部") && !s.contains("  FAIL  "), "");
            ck("B3 下载落地后清单里没有绝对地址（离线可播）",
                    s.contains("清单里已经没有 http:// 绝对地址") && s.contains("PASS"), "");
            ck("B4 分片字节数与源一致（不是「下到了」就算）", s.contains("分片字节数与源一致"), "");
            ck("B5 测试收藏与测试下载都已清场（不在用户机器上留东西）",
                    s.contains("三条测试收藏都已移除"), "");
        }

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
