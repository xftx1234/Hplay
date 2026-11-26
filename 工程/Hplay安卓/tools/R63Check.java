import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 63 轮离线验收器：用户「检查版本是否有bug，并进行多轮性能响应速度优化」。
 *
 * 本轮做了 3 项性能优化 + 修 1 个 bug（都有代码/复算实证）：
 *  A. **启动并行化**：原来 loadHome() 在 pickFastestNode() **之后**，
 *     而测速内部是 refreshSubNodes(最长18s)+全节点测速(最长8s) -> 首屏可能干等 ~20 秒。
 *  B. **主线程 totalBytes 下移**：remember(tick){ totalBytes() } 在主线程跑，
 *     未命中缓存时递归遍历整个下载目录（片多时几千次 stat）。
 *  C. **板块缓存加淘汰**：原是无界 ConcurrentHashMap，长跑会膨胀。
 *  D. **修 bug**：板块缓存命中时 genericListing 不执行 -> firstPageCache 无基线
 *     -> 翻页自校验失效 -> 部分站点「加载更多」可能拿回重复页（第54轮修过的问题复发）。
 */
public class R63Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String APP = GEN + "Han1meApp.kt";
    static final String X99 = GEN + "X99.kt";
    static final String DUI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
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
        String app = read(APP), x99 = read(X99), dui = read(DUI);

        // ================================================================ A 启动并行化
        System.out.println("== A) 启动并行化（首页不等测速） ==");
        // 关键：loadHome() 必须**不在**测速协程里
        int iPick = app.indexOf("HanimeClient.pickFastestNode()");
        int iLoad = app.indexOf("scope.launch { loadHome() }");
        check(iPick > 0, "A1 找得到 pickFastestNode 调用");
        check(iLoad > 0, "A2 找得到「首页立即加载」那一行");
        check(iLoad > iPick, "A3 立即加载出现在测速之后（顺序上并列、不嵌套）");
        // 反断言：测速协程体内不得再有 loadHome()
        int blaze = app.indexOf("val n = HanimeClient.edgeNode");
        int close = app.indexOf("}", blaze);
        // 更稳：取 pickFastestNode 之后 400 字符窗口，应含 toast 而不含 loadHome()
        int iNote = app.indexOf("// 首页**立即**加载");   // 测速协程的边界
        check(iNote > iPick && !app.substring(iPick, iNote).contains("loadHome()"), "A4 反断言：测速协程内已无 loadHome()（原来就卡在这）");
        check(n(app, "scope.launch { loadHome() }") == 1, "A5 首页立即加载恰好一处");

        // ================================================================ B 主线程下移
        System.out.println("== B) 总大小计算移出主线程 ==");
        check(n(dui, "var totalBytes by remember { mutableStateOf(0L) }") == 1, "B1 totalBytes 改为状态");
        check(n(dui, "LaunchedEffect(tick) {") >= 1, "B2 用 LaunchedEffect 触发（不在组合中同步算）");
        check(n(dui, "runCatching { HplayDownloads.totalBytes() }.getOrDefault(0L)") == 1, "B3 取总量带兜底");
        check(n(dui, "val totalBytes = remember(tick) { HplayDownloads.totalBytes() }") == 0,
                "B4 反断言：旧的「主线程同步算」写法已移除");

        // ================================================================ C 缓存淘汰
        System.out.println("== C) 板块缓存加淘汰上限 ==");
        check(n(x99, "private const val BOARD_LIST_MAX = 256") == 1, "C1 有容量上限常量");
        check(n(x99, "if (boardListCache.size >= BOARD_LIST_MAX) {") == 2, "C2 超限才清理（外层判断 + 清理后再复查，共 2 处）");
        check(n(x99, "boardListAt.entries.removeAll { now - it.value > BOARD_LIST_TTL_MS }") == 1,
                "C3 先清过期的");
        check(n(x99, "boardListCache.keys.retainAll(boardListAt.keys)") == 1,
                "C4 再按最早写入裁掉一半（近似 LRU）");

        // ================================================================ D bug 修复
        System.out.println("== D) 修板块缓存导致的翻页自校验失效 ==");
        check(n(x99, "if (page == 1) runCatching { firstPageCache[base] = hit }") == 1,
                "D1 命中缓存时补填 firstPageCache 基线（否则自校验失效）");
        check(n(x99, "private fun pageDistinct(") == 1, "D2 pageDistinct 仍在（既有自校验判据）");
        check(n(x99, "if (prev.isEmpty()) return true") == 1,
                "D3 复算依据：基线为空即判『有区别』——正是 D1 要防的那条路径");

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
        check(anyContains(blobs, "boardListCache"), "E2 板块缓存仍在 dex 里");

        System.out.println("========================================");
        System.out.println("R63Check  PASS=" + pass + "  FAIL=" + fail);
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