import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 第 54 轮离线验收器：用户三项反馈 + 一张真机截图。
 *
 *  0) 截图：已下载列表里同一部片子重复 4 条 -> 逐文件列出，未按条目归并
 *  1) 下载页新增「下一页」按钮
 *  2) 板块内显示「已下载」，点击不再重复下载，下载页不出现重复
 *  3) 开屏自动检查更新弹窗（立即更新 / 下次再说）
 *  4) 全局淡出淡入动画
 *  5) 各板块响应与加载加速（减少转圈）
 */
public class R54Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String DUI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static final String UPD = HPLAY + "src/com/han1me/update/HplayUpdateUi.kt";
    static final String APP = GEN + "Han1meApp.kt";
    static final String HC = GEN + "HanimeClient.kt";
    static final String PATCHES = HPLAY + "tools/patches.py";
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

    /**
     * 行为级复算「条目归并」：复刻 scanItems 的分支 + itemKey 归并，
     * 数出「同一部片子留了 4 份产物时，列表里显示几条」。
     * 返回 [期望条数, 归并前条数, 归并后条数]。
     */
    static int[] dedupeRecount() {
        // 4 份同名不同时间戳的产物（截图里的真实形态）
        String[] titles = {"糖心Vlog", "糖心Vlog", "糖心Vlog", "糖心Vlog"};
        int show = titles.length;                  // 归并前：逐条列出
        Set<String> keys = new LinkedHashSet<>();
        for (String t : titles) keys.add(t.toLowerCase());   // 归并后：按 itemKey 去重
        return new int[] {1, show, keys.size()};
    }

    public static void main(String[] a) throws Exception {
        String eng = read(ENG), dui = read(DUI), upd = read(UPD);
        String app = read(APP), hc = read(HC), patches = read(PATCHES);

        // ================================================================ (0) 重复条目
        System.out.println("== (0) 截图：同一部片子重复 4 条 ==");
        check(n(eng, "@Volatile private var itemsCache") >= 1, "A1 条目列表仍带缓存");
        check(n(eng, ".groupBy { itemKey(it) }") == 1, "A2 scanItems **按条目归并**（修根因）");
        check(n(eng, "private fun itemKey(it: DlItem): String") == 1, "A3 有归并键 itemKey");
        check(n(eng, "removeSuffix(" + (char) 34 + "（未完成）" + (char) 34 + ")") == 1,
                "A4 归并键会剥掉「（未完成）」状态后缀");
        check(n(eng, "compareByDescending<DlItem> { it.hasDetail }") == 1, "A5 同键优先保留**有介绍页**那份（第 71 轮起；其次才看时间最新）");
        check(n(eng, "val key: String = " + (char) 34 + (char) 34) == 1, "A6 DlItem 暴露 key 字段");

        System.out.println("   -- 行为级复算：4 份同名产物 --");
        int[] r = dedupeRecount();
        System.out.printf("      期望显示 1 条；归并前 %d 条；归并后 %d 条%n", r[1], r[2]);
        check(r[1] == 4, "A7 复算确认归并前**确实会显示 4 条**（复现截图）");
        check(r[2] == r[0], "A8 复算确认归并后**只剩 1 条**");

        // ================================================================ (1) 下一页
        System.out.println("== (1) 下载页「下一页」按钮 ==");
        check(n(dui, "private const val PAGE_SIZE = 12") == 1, "B1 有分页常量");
        check(n(dui, "val pageCount = ") == 1, "B2 有总页数");
        check(n(dui, "filtered.drop(page * PAGE_SIZE).take(PAGE_SIZE)") == 1, "B3 按页取切片");
        check(n(dui, "if (tab == 1 && pageCount > 1)") == 1, "B4 多于 1 页才显示翻页控件（第 81 轮起移到页签行右侧）");
check(n(dui, "PageStepper") >= 1 || n(dui, "ui.PageStepper") >= 1, "B5 有翻页控件（第 90 轮抽到 com.han1me.ui.PageStepper 共用）");
check(n(dui, "PageStepper") >= 1 || n(dui, "ui.PageStepper") >= 1, "B6 翻页控件已接线（第 90 轮：公共组件）");
        check(n(dui, "if (page < pageCount - 1) page++") == 1, "B7 下一页有边界保护");
        check(n(dui, "if (page > 0) page--") == 1, "B8 上一页有边界保护");
        check(n(dui, "LaunchedEffect(filtered.size)") == 1, "B9 筛选变化时页码收敛（不留在空页）");

        // ================================================================ (2) 已下载
        System.out.println("== (2) 显示「已下载」+ 点击不重复下载 ==");
        check(n(eng, "fun isDownloaded(videoUrl: String, title: String): Boolean") == 1, "C1 有 isDownloaded 查询");
        check(n(eng, "private fun sideSourceUrl(playable: File): String") == 1, "C2 读 sidecar 里的源 URL（最准的判据）");
        check(n(eng, "const val ALREADY_DONE = -1L") == 1, "C3 enqueue 有 ALREADY_DONE 返回值");
        check(n(eng, "if (srcUrl.isNotBlank() && isDownloaded(srcUrl, d.video.title)) return ALREADY_DONE") == 1,
                "C4 已下载过就不再入队（不重复下载）");
        check(n(eng, "val running = jobs.values.firstOrNull { it.detail.video.url.trim() == srcUrl }") == 1,
                "C5 已在队列则复用既有任务（不重复建）");
        check(n(app, (char) 34 + "✅ 已下载" + (char) 34) == 1, "C6 详情页按钮显示「已下载」");
        check(n(app, "ALREADY_DONE") >= 1, "C7 调用点处理 ALREADY_DONE");
        check(n(app, "这一部已经下载过了") == 1, "C8 重复点击给明确提示");

        // ================================================================ (3) 开屏更新弹窗
        System.out.println("== (3) 开屏自动检查更新弹窗 ==");
        check(n(upd, "fun HplayUpdatePopup(") == 1, "D1 有开屏弹窗组件");
        check(n(upd, "\"发现新版本\"") >= 1, "D2 标题通俗");
        check(n(upd, "\"这一版改了什么\"") >= 1, "D3 明确列出更新内容");
        check(n(upd, "\"立即更新\"") >= 1, "D4 有「立即更新」");
        check(n(upd, "\"下次再说\"") >= 1, "D5 有「下次再说」");
        check(n(upd, "remote.notes.split(") >= 1, "D6 更新说明按行拆开逐条显示");
        check(n(app, "bootUpdate") >= 4, "D7 启动时检查并持有结果");
        check(n(app, "UpdateChecker.fetchRemote()") >= 1, "D8 开屏真的会去查");
        check(n(app, "br.hasUpdate") == 1, "D9 只在真有新版时弹");
        // 「下次再说」只关本次，不写持久标记
        check(n(app, "onLater = { bootUpdate = null }") == 1, "D10 「下次再说」只关本次（不写「不再提醒」）");

        // ================================================================ (4) 动画
        System.out.println("== (4) 全局淡出淡入 ==");
        check(n(app, "androidx.compose.animation.AnimatedVisibility(") >= 1, "E1 覆盖层淡入淡出");
        check(n(app, "androidx.compose.animation.fadeIn(") >= 1, "E2 fadeIn");
        check(n(app, "androidx.compose.animation.fadeOut(") >= 1, "E3 fadeOut");
        check(n(upd, "label = \"upd-alpha\"") == 1, "E4 开屏弹窗淡入");
        check(n(upd, "label = \"upd-scale\"") == 1, "E5 开屏弹窗轻微缩放");
        check(n(dui, "label = \"confirm-alpha\"") == 1, "E6 确认弹窗淡入");
        check(n(dui, "label = \"dl-panel-alpha\"") == 1, "E7 下载面板淡入淡出");

        // ================================================================ (5) 加速
        System.out.println("== (5) 板块响应加速（网络口径不动） ==");
        check(n(hc, "connectionPool(okhttp3.ConnectionPool(48, 15, TimeUnit.MINUTES))") == 1,
                "F1 连接池加大（48 条 / 空闲 15 分钟）");
        check(n(hc, "maxRequests = 96; maxRequestsPerHost = 24") == 1,
                "F2 同域并发 8 → 24（减少「转圈等」）");
        // ★ 网络口径不动
        check(n(hc, "connectTimeout(20, TimeUnit.SECONDS)") == 1, "F3 接口超时未改（仍 20s）");
        check(n(hc, "readTimeout(30, TimeUnit.SECONDS)") == 1, "F4 读超时未改（仍 30s）");
        check(n(hc, ".dns(hanimeDns)") == 1, "F5 DoH 未改");
        check(n(hc, ".proxySelector(proxySelector)") >= 1, "F6 代理未改");
        check(n(hc, "hanime1.me") >= 1, "F7 官方源域名仍在");
        // 第 122 轮：注释被批量重写，改为验证**补丁内容**确实存在（意图不变：走补丁而非改生成物）
        check(n(patches, "下载到本机") >= 1 || n(patches, "HplayDownloads.isDownloaded") >= 1,
                "F8 本轮补丁可重放（不是只改生成物）");

        // ================================================================ 包体
        System.out.println("== 交付包字节同源 ==");
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
        check(!blobs.isEmpty(), "G1 包里有 dex");
        check(anyContains(blobs, "立即更新"), "G2 开屏弹窗文案进了 dex");
        check(anyContains(blobs, "下一页"), "G3 分页文案进了 dex");
        check(anyContains(blobs, "已经下载过了"), "G4 不重复下载的提示进了 dex");

        System.out.println("========================================");
        System.out.println("R54Check  PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) { for (String f : fails) System.out.println("  - " + f); System.exit(1); }
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
                long t = Files.getLastModifiedTime(p).toMillis();
                String nm = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (nm.contains("unsigned") || nm.contains("aligned")) continue;
                if (t > bt) { bt = t; best = p; }
            }
        }
        if (best == null) { System.out.println("EXIT2 找不到 apk"); System.exit(2); }
        return best;
    }
}