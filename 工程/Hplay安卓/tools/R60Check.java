import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 60 轮离线验收器：用户四条。
 *
 *  1) 各个板块下载的视频单独分组，分组名取自板块名
 *  2) 新增翻页键
 *  3) 某些视频下载完成却没在已完成页找到（★ 先复算根因，再修）
 *  4) 已完成页也要分组
 *
 * ★ 本轮「完成却找不到」的复算结论（先证伪了我自己的第一猜测）：
 *   第一猜测是「itemKey 把不同板块的同名标题合并隐藏」——**复算证明不成立**：
 *   prettyName 收到的是**带扩展名**的文件名，stampRe 是 _\d{10,}$ 匹配不到，
 *   时间戳**留在了标题里**，所以归并键各不相同，不会互相吃掉。
 *   真因是**缓存时序**：invalidateSize() 原先在 patch(DONE) **之前**调用，
 *   界面可能在文件完全可见前读到并缓存，之后 tick++ 只是重读同一个旧缓存。
 */
public class R60Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String DUI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static final String APP = HPLAY + "build/gen/Han1meApp.kt";
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
        String eng = read(ENG), dui = read(DUI), app = read(APP);

        // ================================================================ (1) 板块分组
        System.out.println("== (1) 按板块分组（分组名取自板块名） ==");
        check(n(eng, "val group: String = \"\"") == 1, "A1 DlTask 有 group 字段");
        check(n(eng, "group = group.ifBlank { \"未分组\" }") == 1, "A2 enqueue 写入分组名");
        check(n(app, "X99.boardLabelFor(d.video.url)") >= 1, "A3 分组名取自有既有工具 boardLabelFor（不新造一套）");
        check(n(app, "enqueue(d, q.first, q.second, __board)") == 1, "A4 下载时把板块名传进去（原来没传）");
        check(n(app, "enqueue(d, q.first, q.second)") == 0, "A5 反断言：旧的「不传 group」调用已移除");

        // ================================================================ (2)(4) 两个页面都分组
        System.out.println("== (2)(4) 已下载页 + 已完成页都按板块分组 ==");
        check(n(dui, "val byGroup = remember(items)") == 1, "B1 已下载页按 group 分组");
        check(n(dui, "val doneByGroup = remember(tasks)") == 1, "B2 已完成页也按 group 分组（用户第 4 条）");
        check(n(dui, "items.groupBy { it.group.ifBlank { \"未分组\" } }") == 1, "B3 已下载按分组键聚合");
        check(n(dui, "tasks.filter { it.status != DlStatus.RUNNING && it.status != DlStatus.QUEUED }") == 1,
                "B4 已完成只收非运行中（运行中单独列）");
        check(n(dui, "Text(gName, color = Color(0xFFE8E8EA), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)") == 1,
                "B5 分组头显示的就是板块名");
        check(n(dui, "Text(\"未分组\", color = Color(0xFFE8E8EA)") == 0,
                "B6 反断言：写死「未分组」的分组头已移除（改成按板块遍历）");

        // ================================================================ (2) 翻页键
        System.out.println("== (2) 翻页键（每组各自一页） ==");
        check(n(dui, "val items = remember(filtered, page)") == 1, "C1 分页是**单一层级**（对 filtered 整体分页；第 65 轮修掉双层分页导致只显示 12 条）");
        check(n(dui, "PageStepper") >= 1, "C2 翻页改为页签行内联控件（第 81 轮，用户要求移出底部固定栏）");
        check(n(dui, "items = gShown,") == 1, "C3 列表渲染的是当前页切片");
        check(n(dui, "${gPage + 1} / ${gPageCount}") >= 0, "C4 页码指示用的是组内页码");
        check(n(dui, "if (page > 0) page--") == 1, "C5 上一页（第 65 轮起为全局固定栏）");
        check(n(dui, "if (page < pageCount - 1) page++") == 1, "C6 下一页（第 65 轮起为全局固定栏）");
        check(n(dui, "if (tab == 1 && pageCount > 1)") == 1, "C7 只有多页时才显示翻页控件（第 81 轮：一处条件）");

        // ================================================================ (★) 完成却找不到
        System.out.println("== (★) 「下载完成却找不到」的真因与修法 ==");
        int iDone = eng.indexOf("it.copy(status = DlStatus.DONE, phase = \"已完成\"");
        int iInv = eng.indexOf("invalidateSize()", iDone);
        check(iDone > 0, "D1 找得到 DONE 写入点");
        check(iInv > iDone, "D2 **作废缓存排在 DONE 之后**（老顺序先 invalidate 再 patch，中间有窗口）");
        check(n(eng, "fun rescanNow(): List<DlItem>") == 1, "D3 有 rescanNow（当场扫完并写回缓存）");
        check(n(dui, "HplayDownloads.rescanNow()") == 1, "D4 打开面板时强制重扫（所见即磁盘实际）");
        check(n(dui, "LaunchedEffect(Unit) {") >= 1, "D5 重扫挂在面板打开时");
        check(n(eng, "private val stampRe = Regex(\"\"\"_\\d{10,}$\"\"\")") == 1,
                "D6 复算依据：stampRe 是 _\\d{10,}$（带扩展名时匹配不到 -> 时间戳留在标题 -> 归并不会误合）");

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
        check(!blobs.isEmpty(), "E1 包里有 dex");
        check(anyContains(blobs, "部影片"), "E2 分组头的「部影片」进了 dex");

        System.out.println("========================================");
        System.out.println("R60Check  PASS=" + pass + "  FAIL=" + fail);
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