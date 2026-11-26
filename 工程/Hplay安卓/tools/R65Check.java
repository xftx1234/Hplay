import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 65 轮离线验收器：**实机测试**（Pixel 7 Pro / Android 13）测出的 3 个 bug 修复。
 *
 * 这是 65 轮以来第一次实机测试（此前 adb devices 一直为空）。
 *
 *  BUG-1 下载面板被详情浮层盖住 -> 入队成功后 detail = null
 *  BUG-2 36co 首页翻页拼出双斜杠 //page/2.html -> 先去掉结尾斜杠再拼
 *  BUG-3 36co 首页本就是固定聚合页（无分页链接、/page/2.html 404）
 *        -> co36MaxPage 对首页返回 1，界面显示「已到末页」而非发起必然失败的请求
 */
public class R65Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String APP = GEN + "Han1meApp.kt";
    static final String X99 = GEN + "X99.kt";
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
        String app = read(APP), x99 = read(X99);

        System.out.println("== BUG-1 下载面板被详情浮层盖住 ==");
        // 入队成功分支里必须先收起详情，再显示下载面板
        check(n(app, "detail = null") >= 1, "A1 存在收起详情浮层的动作");
        int iAd = app.indexOf("ALREADY_DONE");
        int iDn = app.indexOf("detail = null", iAd);
        int iSd = app.indexOf("showDownloads = true", iAd);
        check(iDn > 0 && iSd > 0 && iDn < iSd, "A2 在该分支里 detail=null 排在 showDownloads 之前");

        System.out.println("== BUG-2 36co 翻页双斜杠 ==");
        check(n(x99, "fun co36PagePath(path: String, page: Int): String {") == 1,
                "B1 co36PagePath 已改为函数体（可插入规范化）");
        check(n(x99, "val head = path.removeSuffix(") >= 1, "B2 拼装前规范化结尾（消除双斜杠）");
        check(n(x99, "return head + ") == 1, "B3 只拼一层斜杠");
        check(n(x99, "removeSuffix(") >= 2, "B4 规范化用到了 removeSuffix 两次（.html 与 /）");
        check(n(x99, "page.html") >= 1, "B5 仍拼出 page.html 结尾");

        System.out.println("== BUG-3 36co 首页不可翻页 ==");
        check(n(x99, "fun co36MaxPage(path: String): Int {") == 1, "C1 co36MaxPage 已改为函数体");
        check(n(x99, "if (p.isBlank()") >= 1, "C2 首页宣告「只有 1 页」");
        check(n(x99, "return 1") >= 1, "C2b 首页直接返回 1");
        check(n(x99, "co36PagerMax[p] ?: 0") == 1, "C3 栏目页仍按分页器读到的最大页");

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
        check(!blobs.isEmpty(), "D1 包里有 dex");
        check(anyContains(blobs, "已到末页"), "D2 「已到末页」文案进了 dex");

        System.out.println("========================================");
        System.out.println("R65Check  PASS=" + pass + "  FAIL=" + fail);
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
                long t2 = Files.getLastModifiedTime(p).toMillis();
                String nm = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (nm.contains("unsigned") || nm.contains("aligned")) continue;
                if (t2 > bt) { bt = t2; best = p; }
            }
        }
        if (best == null) { System.out.println("EXIT2 找不到 apk"); System.exit(2); }
        return best;
    }
}