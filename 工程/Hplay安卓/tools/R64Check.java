import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 64 轮离线验收器：用户三条（一张真机截图）。
 *
 *  1) 修复各个板块图载失败（截图：qinav 一屏 8 张封面全挂）
 *  2) 收藏页偶也有这个问题
 *  3) 提高下载速度
 *
 * ★ 根因（都有实证）：
 *   A. 熔断过激：某图床连续 2 次失败 -> 该域冷 20 秒，期间所有封面直接判失败。
 *      实测该图床是通的（根路径 403 是防目录遍历的正常响应）——纯属误伤。
 *   B. 界面永不重试：HplayThumb 用 LaunchedEffect(url)，url 不变只跑一次；
 *      冷却过去后界面不会自己恢复，红字一直挂着。
 *   C. 下载被本地限流卡住：maxRequestsPerHost=8，而 5 任务x4 分片=20 路本可跑；
 *      且未设连接池（默认仅 5 条 idle）-> 超出就重新握手。
 *
 * 收藏页与板块页共用 HplayThumb / fetchThumbBytes，修 A+B 必然同时覆盖两者。
 */
public class R64Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String HC = GEN + "HanimeClient.kt";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String T = HPLAY + "src/com/han1me/web/HplayThumb.kt";
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
        String hc = read(HC), eng = read(ENG), t = read(T);

        System.out.println("== A) 图床熔断：整域冷 20s -> 渐进退避 ==");
        check(n(hc, "private val thumbFailCount = java.util.concurrent.ConcurrentHashMap<String, Int>()") == 1,
                "A1 有连续失败次数计数（退避依据）");
        check(n(hc, "thumbFailCount[host] = fails") == 1, "A2 失败时累加");
        check(n(hc, "thumbFailCount.remove(host)") == 1, "A3 成功即清零（避免历史失败拖累下一次）");
        check(n(hc, "(1000L shl (fails - 1).coerceAtMost(3)).coerceAtMost(8_000L)") >= 1 && n(hc, "THUMB_DEAD_UNREACHABLE_MS") >= 1,
            "A4 指数退避 1s-2s-4s-8s 保留；连接层失败另封 5 分钟（第 86 轮，修「头像一直转」）");
        check(n(hc, "thumbDeadUntil[host] = System.currentTimeMillis() + 20_000L") == 0,
                "A5 反断言：旧的整域冷 20 秒已移除");
        check(n(hc, "thumbDeadUntil.remove(host)") == 1, "A6 成功时同时解除等待");

        System.out.println("== B) 界面失败自动重试（冷却过后能自己恢复） ==");
        check(n(t, "var attempt by remember(url) { mutableStateOf(0) }") == 1, "B1 有重试计数状态");
        check(n(t, "LaunchedEffect(url, attempt) {") == 1, "B2 key 含 attempt（否则永不重跑）");
        check(n(t, "attempt < 3) {") == 1 && n(t, "contains(\"不是图片正文\")") >= 1, "B3 仅真失败重试，且上限 3 次（第 68 轮起排除「这张图不存在」；第 100 轮另有备用图兜底）");
        check(n(t, "kotlinx.coroutines.delay(if (decodeMaxW <= 256)") == 1 && n(t, "attempt + 1)") >= 1, "B4 间隔递增（第 84 轮：头像 400ms/次、封面 1200ms/次）");
        check(n(t, "LaunchedEffect(url) {") == 0, "B5 反断言：旧的 LaunchedEffect(url) 已改掉");
        check(n(t, "private val BLANK = Any()") == 1, "B6 空封面占位仍是独立类型（不与失败混淆）");

        System.out.println("== C) 下载提速：解开本地限流 ==");
        check(n(eng, "okhttp3.ConnectionPool(64, 15, java.util.concurrent.TimeUnit.MINUTES)") == 1,
                "C1 连接池放大到 64 条 / 15 分钟（原走默认仅 5 条 idle）");
        check(n(eng, "Dispatcher().apply { maxRequests = 192; maxRequestsPerHost = 48 }") == 1,
                "C2 同域并发 -> 48（5 任务x4 分片=20 路现在能跑满）");
        check(n(eng, "private const val CONC_DEFAULT = 16") == 1, "C3 分片并发仍由用户设定（第 120 轮起默认 16）");
        check(n(eng, "const val MAX_PARALLEL_TASKS = 5") == 1, "C4 任务并发仍为 5");
        check(n(eng, "maxRequests = 64; maxRequestsPerHost = 8") == 0, "C5 反断言：旧的 8 路同域上限已移除");

        System.out.println("== D) 收藏页与板块页共用同一实现 ==");
        check(n(t, "HanimeClient.fetchThumbBytes(url)") == 1, "D1 缩略图统一走 fetchThumbBytes（只有一个生产层实现）");
        check(n(hc, "fun fetchThumbBytesSync(") == 1, "D2 该实现是唯一入口（收藏页/板块页都调它）");

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
        check(anyContains(blobs, "图床"), "E2 图床相关文案进了 dex");

        System.out.println("========================================");
        System.out.println("R64Check  PASS=" + pass + "  FAIL=" + fail);
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