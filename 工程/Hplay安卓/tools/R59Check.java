import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 59 轮离线验收器：用户一条 —— 「收藏页点击加载到介绍页太慢，优化到最快」。
 *
 * 先把慢在哪一步量清楚，再对着那一步优化（不是笼统地"加缓存"）：
 *   收藏卡片点击 -> X99.boardPage(v)
 *   boardPage = (if native) boardDetail ?: metaDetail
 *   · metaDetail 是**抓整张网页 + 一串正则解析**；
 *   · 原生板块要先等 boardDetail 失败才轮到它 => 最坏**两次串行网络请求**；
 *   · 原本**只有 xchina 一条有详情缓存**，其余板块每次点击都重新抓。
 */
public class R59Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String X99 = GEN + "X99.kt";
    static final String APP = GEN + "Han1meApp.kt";
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
        String x99 = read(X99), app = read(APP);

        // ================================================================ 缓存
        System.out.println("== (1) 通用详情缓存（点过就秒开） ==");
        check(n(x99, "private val boardPageCache = java.util.concurrent.ConcurrentHashMap<String, HanimeDetail>()") == 1,
                "A1 有**所有板块共用**的详情缓存（原来只有 xchina 一条）");
        check(n(x99, "private val boardPageAt = java.util.concurrent.ConcurrentHashMap<String, Long>()") == 1,
                "A2 有写入时间（TTL 判据）");
        check(n(x99, "private const val BOARD_TTL_MS = 10 * 60_000L") == 1, "A3 TTL 10 分钟");
        check(n(x99, "private fun boardPageFresh(url: String): HanimeDetail?") == 1, "A4 有读缓存");
        check(n(x99, "private fun boardPageRemember(url: String, d: HanimeDetail)") == 1, "A5 有写缓存");
        check(n(x99, "fun clearBoardPageCache()") == 1, "A6 有清缓存（切线路时用）");

        // ================================================================ boardPage 改造
        System.out.println("== (2) boardPage 命中即返回 ==");
        check(n(x99, "boardPageFresh(v.url)?.let { return it }") >= 1, "B1 命中缓存**直接返回**（不走网络）");
        check(n(x99, "if (d != null) boardPageRemember(v.url, d)") == 1, "B2 成功才写缓存（失败下次可重试）");
        // 反断言：旧的「每次都跑」写法必须已不存在
        check(n(x99, "fun boardPage(v: HanimeVideo): HanimeDetail? =") == 0,
                "B3 反断言：旧的「表达式体、无缓存」写法已移除");

        // ================================================================ 并发去重
        System.out.println("== (3) 并发点击不重复打网 ==");
        check(n(x99, "boardPageGate") >= 3, "C1 有 in-flight 闸门");
        check(n(x99, "val inflight = boardPageGate.putIfAbsent(v.url, mine)") == 1,
                "C2 按 URL 建闸门（putIfAbsent 区分「拥有者 / 等待者」）");
        check(n(x99, "inflight.await(8, java.util.concurrent.TimeUnit.SECONDS)") == 1, "C3 等待者最多等 8 秒（不能无限等）");
        check(n(x99, "boardPageGate.remove(v.url)?.countDown()") == 1, "C4 finally 里放行（异常也不会卡住别人）");

        // ---- 第 110 轮：闸门真根因修复（介绍页每次冷开固定空等 8 秒）----
        System.out.println("== (3b) 闸门不再固定空等 8 秒 ==");
        // 拥有者（putIfAbsent 返回 null）**绝不等**；只有等待者才 await。
        check(n(x99, "if (inflight != null) {") == 1, "C5 只有「已有请求在飞」才进等待分支");
        check(n(x99, "if (inflight == null) boardPageGate.remove(v.url)?.countDown()") == 1,
                "C6 只有拥有者摘表放行（等待者不动别人的表项）");
        // 反断言：旧实现的三处痕迹必须都不在
        check(n(x99, "private var boardPageOwner") == 0, "C7 反断言：失效的 boardPageOwner 字段已删除");
        check(n(x99, "boardPageOwner = v.url") == 0, "C8 反断言：旧「await 之后才赋值」的写法已移除");
        check(n(x99, "boardPageGate.computeIfAbsent(v.url)") == 0, "C9 反断言：无法区分身份的 computeIfAbsent 已移除");

        // ---- 第 110 轮：DNS 护栏与提速 ----
        System.out.println("== (3c) 板块 DNS 护栏 ==");
        check(n(x99, "ssl!!.soTimeout = timeoutMs") == 1, "C10 probeTls 补了 SO_TIMEOUT（防握手无限阻塞）");
        check(n(x99, "isDaemon = true; name = ") == 1, "C11 候选实测并发发出（工作线程是 daemon）");
        check(n(x99, "w.join(3500)") == 1, "C12 并发实测有总上限（不能无限等）");
        check(n(x99, "if (probed[ip] == true)") == 1, "C13 判定口径不变：仍按候选顺序取前两个成功的");

        // ================================================================ 预取
        System.out.println("== (4) 收藏页预取（点进去秒开的关键） ==");
        check(n(x99, "fun prefetchBoardPage(items: List<HanimeVideo>)") == 1, "D1 有预取函数");
        check(n(x99, "private const val PREFETCH_MAX = 3") == 1, "D2 预取上限 3 条（不漏成压测）");
        check(n(x99, "Thread.sleep(250)") == 1, "D3 预取串行 + 250ms 间隔（这些站有频控）");
        check(n(x99, "isDaemon = true; priority = Thread.MIN_PRIORITY") == 1, "D4 预取线程低优先级（不抢界面）");
        check(n(x99, "runCatching { boardPage(v) }") == 1, "D5 预取失败静默（不报给用户）");
        check(n(app, "X99.prefetchBoardPage(vs)") == 1, "D6 收藏页已接上预取");
        check(n(app, "androidx.compose.runtime.LaunchedEffect(favTick, favFilter)") == 1,
                "D7 只在筛选/收藏变化时重跑（不每帧打网）");

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
        byte[] all = blobs.get(0);
        check(anyContains(blobs, "boardPageCache"), "E2 缓存字段进了 dex");

        System.out.println("========================================");
        System.out.println("R59Check  PASS=" + pass + "  FAIL=" + fail);
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
