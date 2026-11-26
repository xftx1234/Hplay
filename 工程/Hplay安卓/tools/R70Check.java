import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 70 轮离线验收器：**板块源切换 + 连通性检测 + 36co 主备域名进优选弹窗带测速**。
 *
 * 用户原话：「默认主板块优选源 https://x99dh.cc/ 的其他备用域名1: https://x99dh.vip 域名2: https://x99dh.my，
 *           在设置页面新建一个板块源切换选项，并对主板块源和这两个备用的连通性进行检测，手动切换不自动，
 *           还有 www.36co.cc 不来自 x99dh，主域名和备用域名落实到优选弹窗路线里，带测速」。
 *
 * ★ 用户还纠正了一处事实：「www.36co.cc 不来自 x99dh」—— 实测证实：
 *   远端目录 31 条站点里**没有任何 36co**（它来自源码的 builtinSites）。
 *   第 69 轮我写的注释判断有误，本轮一并订正。
 */
public class R70Check {
    static final String HPLAY = "D:/工作区/工程/Hplay安卓/";
    static final String GENX = HPLAY + "build/gen/X99.kt";
    static final String GENA = HPLAY + "build/gen/Han1meApp.kt";
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
        String x = read(GENX), app = read(GENA);

        System.out.println("== A) 三个板块源 + 手动切换（不自动） ==");
        check(n(x, "x99dh.cc") >= 2, "A1 主源 x99dh.cc 在列");
        check(n(x, "x99dh.vip") >= 1, "A2 备用源 x99dh.vip 在列");
        check(n(x, "x99dh.my") >= 1, "A3 备用源 x99dh.my 在列");
        check(n(x, "val CATALOG_SOURCES = listOf(") == 1, "A4 三个源收成一个列表（CATALOG_SOURCES）");
        check(n(x, "KEY_CATALOG_SOURCE") >= 2, "A5 选择被持久化（设置键）");
        check(n(x, "val src = catalogSource()") == 1, "A6 ★ loadCatalog 用**当前选中的源**（不是写死主源）");
        check(n(x, "val list = decodeCatalog(getHtmlLoose(CATALOG_URL))") == 0, "A7 反断言：不再写死 CATALOG_URL");

        System.out.println("== B) 连通性检测 ==");
        check(n(x, "data class SourceProbe") == 1, "B1 有检测结果类型（耗时 + 站点数）");
        check(n(x, "fun probeCatalogSource(") == 1, "B2 有单源检测");
        check(n(x, "fun probeAllCatalogSources(") == 1, "B3 有并发检测全部源");
        check(n(x, "decodeCatalog(html).size") == 1, "B4 ★ 判据是「**能解出目录**」而不是 HTTP 状态码（实测三个源都回 500 但正文正常）");
        check(n(x, "val ok: Boolean get() = ms >= 0 && sites > 0") == 1, "B5 通的定义 = 有耗时且有站点");

        System.out.println("== C) 设置页有「板块源」区块 + 手动切换 ==");
        check(n(app, "private fun CatalogSourceSection()") == 1, "C1 区块存在");
        check(n(app, "CatalogSourceSection()") == 2, "C2 已挂进「我的」面板");
        check(n(app, "检测连通性") >= 1, "C3 有「检测连通性」按钮");
        check(n(app, "X99.setCatalogSource(src)") == 1, "C4 点某一条即切换（手动）");
        check(n(app, "probeAllCatalogSources()") == 1, "C5 检测走并发探测");
        check(n(app, "不自动") >= 1 || n(app, "手动切换") >= 1, "C6 界面上写明「手动切换，不自动」");

        System.out.println("== D) ★ 36co：主/备域名进线路，且**带测速** ==");
        check(n(x, "fun co36GoodLines()") == 1, "D1 36co 线路有统一出处");
        check(n(x, "X99Line(CO36_HOME, CO36_HOME + ") == 1, "D2 主域名带**测速地址**");
        check(n(x, "www.91gc.pro") >= 2, "D3 备用域名带**测速地址**");
        check(n(x, "lines = co36GoodLines(),") == 1, "D4 内置站点表用 co36GoodLines()（优选弹窗读的就是它）");
        check(n(x, "val good = co36GoodLines()") == 1, "D5 co36Fixed 也用同一套（两条路径一致）");
        check(n(x, "www.36co.cc") >= 1, "D6 失效域名仍被剔除");

        System.out.println("== E) ★ testLine 不只认 \"ok\"（否则 36co 测速恒 -1） ==");
        check(n(x, "!cfBlockRe.containsMatchIn(body)") == 1, "E1 判据含「不是 Cloudflare 拦截页」");
        check(n(x, "if (ok) (el / 2.5).toLong().coerceAtLeast(1L) else -1") == 1, "E2 通了就返回耗时（≥1ms）");
        check(n(x, "if (body == ") == 0, "E3 反断言：旧的「只认 ok」已改掉");

        System.out.println("== F) ★ 订正 36co 来源的错误注释（用户指出） ==");
        check(n(x, "36co 并不来自远端目录") == 1, "F1 注释已订正");
        check(n(x, "远端目录 31 条站点里没有任何 36co 条目") == 1, "F2 写清实测依据");
        System.out.println("== H) ★ 优选弹窗：含内置站 + 切站点重新测速 + 不被底栏遮 ==");
        String board = read(HPLAY + "build/gen/X99Board.kt");
        check(n(board, "X99.sitesWithHanime()") == 2 && n(board, "X99.otherSites()") >= 1,
                "H1 ★ 弹窗用 sitesWithHanime()（含本命板块 + 内置站 36co），站点合集仍用 otherSites()");
        check(n(board, "X99.sitesWithHanime()") == 2, "H2 目录加载后重新并上（本命板块 + 内置站）");
        check(n(board, "LaunchedEffect(round, current, sites)") == 1, "H3 ★ effect key 含 current/sites（否则切站点不重新测速，线路永远显示 …）");
        check(n(board, "LaunchedEffect(round) {") == 0, "H4 反断言：旧的 LaunchedEffect(round) 已改掉");
        check(n(board, ".padding(bottom = 74.dp)") >= 1, "H5 ★ 弹窗给底部导航栏让出 74dp（用户反馈：域名栏被底栏遮住）");

        System.out.println("== G) 交付包内含本次改动 ==");
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
        check(anyContains(blobs, "x99dh.vip"), "G2 x99dh.vip 进了包");
        check(anyContains(blobs, "x99dh.my"), "G3 x99dh.my 进了包");
        check(anyContains(blobs, "板块源"), "G4 「板块源」文案进了包");
        check(anyContains(blobs, "www.25jm.cc"), "G5 36co 可用域名在包里");

        System.out.println("========================================");
        System.out.println("R70Check  PASS=" + pass + "  FAIL=" + fail);
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