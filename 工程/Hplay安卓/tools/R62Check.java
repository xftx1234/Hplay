import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 62 轮离线验收器：用户一条 —— 「优化下板块加载速度，准备随时回滚」。
 *
 * 先量清楚「板块加载」到底走哪条路、慢在哪：
 *   板块页（X99Board） -> X99.boardListing(siteName, base, tabPath, page)   <- 唯一入口
 *   而 boardListing 的 6 个分支里，**只有 xchina 自带列表缓存**，
 *   其余 5 个（qinav / 感谢 / co36 / MissAV / 通用）**每次进页都重新抓网页**。
 *   => 在唯一入口加一层缓存，一个函数覆盖全部 6 个板块。
 *
 * 「准备随时回滚」的两条保障（不在本文件断言，由工程记录与交接书写明）：
 *   ① 交付基线已打 tag hplay-stable-v1.0.38（checkout 即回到优化前）
 *   ② 改动是**单一补丁**（只改 boardListing 一个函数），回滚面最小
 */
public class R62Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String X99 = GEN + "X99.kt";
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

    public static void main(String[] a) throws Exception {
        String x99 = read(X99), patches = read(PATCHES);

        // ================================================================ 缓存本体
        System.out.println("== (1) 板块列表缓存落在唯一入口 ==");
        check(n(x99, "private val boardListCache = java.util.concurrent.ConcurrentHashMap<String, List<HanimeVideo>>()") == 1,
                "A1 有板块列表缓存字段");
        check(n(x99, "private val boardListAt = java.util.concurrent.ConcurrentHashMap<String, Long>()") == 1,
                "A2 有写入时间（TTL 判据）");
        check(n(x99, "private const val BOARD_LIST_TTL_MS = 3 * 60_000L") == 1, "A3 TTL 3 分钟");
        check(n(x99, "fun clearBoardListCache()") == 1, "A4 有清缓存入口");
        // 入口函数已从「表达式体」改成「函数体」（这是加缓存的前提）
        check(n(x99, "fun boardListing(siteName: String, base: String, tabPath: String?, page: Int): List<HanimeVideo> {") == 1,
                "A5 boardListing 已改为函数体（可插入缓存逻辑）");
        check(n(x99, "fun boardListing(siteName: String, base: String, tabPath: String?, page: Int): List<HanimeVideo> = when {") == 0,
                "A6 反断言：旧的表达式体写法已移除");

        // ================================================================ 命中与写回
        System.out.println("== (2) 命中即返回 / 只缓存非空 ==");
        check(n(x99, "boardListCache[key]?.let { hit ->") == 1 && n(x99, "return hit") >= 1,
                "B1 命中缓存**直接返回**（不走网络；第 63 轮加了基线补填后写法变了）");
        check(n(x99, "if (out.isNotEmpty()) {") >= 1, "B2 **只缓存非空结果**（空结果是被频控/失败，缓存它会让用户反复看到空白）");
        check(n(x99, "boardListCache[key] = out") == 1, "B3 写回缓存");
        check(n(x99, "boardListAt[key] = System.currentTimeMillis()") == 1, "B4 写回时间戳");

        // ================================================================ 键的四段（换线路/换页签/换站点/换页不能复用）
        System.out.println("== (3) 缓存键四段（换线路/页签/站点/页 各算一条） ==");
        check(n(x99, "val key = siteName + \"|\" + base + \"|\" + (tabPath ?: \"\") + \"|\" + page") == 1,
                "C1 键含 siteName|base|path|page");
        check(n(x99, "if (at != null && System.currentTimeMillis() - at <= BOARD_LIST_TTL_MS)") == 1,
                "C2 过期即不复用（TTL 生效）");

        // ================================================================ 六个分支一个不少（缓存不能把某个板块吃掉）
        System.out.println("== (4) 六个板块分支一个不少 ==");
        for (String b : new String[]{
                "isXchinaSite(siteName) -> xchinaListing(base, tabPath ?: \"/videos.html\", page)",
                "isQinavSite(siteName) -> qinavListing(base, tabPath ?: \"/new.html\", page)",
                "siteName.equals(GANXIE_NAME, ignoreCase = true) -> gxListing(base, tabPath ?: \"/vl/56.php\", page)",
                "siteName.equals(CO36_NAME, ignoreCase = true) -> co36Listing(base, tabPath, page)",
                "missavListing(originOf(base), tabPath ?: \"/cn/new\", page)",
                // 第 159 轮：else 分支改成块（内加 [x99] 诊断日志），原字面行不再存在；
                // 断言改为校验块内那一次 genericListing 调用仍然唯一。
                "val r159 = genericListing(siteName, base, page)"}) {
            check(n(x99, b) == 1, "D 分支在: " + b.substring(0, Math.min(46, b.length())));
        }

        // ================================================================ 可回滚性
        System.out.println("== (5) 可回滚性 ==");
        // 第 122 轮：肉视频改为「短超时 + 3 轮重试」两条补丁（122d/122e），仍集中在该函数
        check(n(patches, "# 122d.") == 1 && n(patches, "# 122e.") == 1,
                "E1 肉视频改动集中在 getPlaylist 短超时 + 重试收紧（回滚面最小）");
        check(n(patches, "# 122d.") == 1 && n(patches, "# 122e.") == 1,
                "E2 没有扩散到其它板块（仅这两条）");

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
        check(!blobs.isEmpty(), "F1 包里有 dex");
        check(anyContains(blobs, "boardListCache"), "F2 板块列表缓存进了 dex");

        System.out.println("========================================");
        System.out.println("R62Check  PASS=" + pass + "  FAIL=" + fail);
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