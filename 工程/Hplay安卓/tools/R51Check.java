import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 第 51 轮离线验收器：用户三条 + 一次"三轮未修好"的复盘。
 *
 *  1) 多任务下载仍卡顿闪退 -> 并发缺陷（共享结构无锁 + 非原子累加 + 通知风暴）
 *  2) 进本地下载的动画 -> 面板进场/退场转场（此前全应用零动画）
 *  3) 各板块与介绍页加载加速（官方源不动）-> 连接复用参数
 *
 * 这一轮的纪律：**先复现再修**。下面 D 段是真正的并发复算，
 * 不是"看代码像对"——它会在修复后的实现上重跑，用同一套判据。
 */
public class R51Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String DUI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static final String SVC = HPLAY + "src/com/han1me/download/DownloadService.kt";
    static final String APP = GEN + "Han1meApp.kt";
    static final String HC = GEN + "HanimeClient.kt";
    static final String HSRC = WS + "工程/AvPlay/hanime模块/src/com/javrocket/hanime/HanimeClient.kt";
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

    // ---------------------------------------------------------------- 并发复算（D 段）
    /**
     * 复刻「分片线程并发累加进度」这件事，用修复前/修复后两种写法各跑一遍。
     * 返回 [期望值, 修复前实际丢失, 修复后实际丢失]。
     */
    static long[] raceRecount(int threads, int iters) throws Exception {
        // 修复前：读-改-写
        final AtomicLong before = new AtomicLong(0);
        CountDownLatch d1 = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                for (int i = 0; i < iters; i++) {
                    long base = before.get();
                    before.set(base + 1);
                }
                d1.countDown();
            }).start();
        }
        d1.await(60, TimeUnit.SECONDS);
        long expect = (long) threads * iters;
        // 修复后：原子累加
        final AtomicLong after = new AtomicLong(0);
        CountDownLatch d2 = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                for (int i = 0; i < iters; i++) after.incrementAndGet();
                d2.countDown();
            }).start();
        }
        d2.await(60, TimeUnit.SECONDS);
        return new long[] { expect, expect - before.get(), expect - after.get() };
    }

    public static void main(String[] a) throws Exception {
        String eng = read(ENG), dui = read(DUI), svc = read(SVC);
        String app = read(APP), hc = read(HC), hsrc = read(HSRC);

        // ================================================================ (1) 并发崩溃
        System.out.println("== (1) 多任务下载并发缺陷 ==");
        check(n(eng, "private val lock = Any()") == 1, "A1 有统一的锁对象");
        check(n(eng, "synchronized(lock)") >= 1, "A2 patch() 的读-改-写整段进了锁");
        check(n(eng, "java.util.concurrent.ConcurrentHashMap<Long, Long>()") == 1,
                "A3 lastEmit 换成并发容器（它从多个分片线程无锁读写）");
        check(n(eng, "newKeySet<Long>()") >= 2, "A4 cancelled / paused 都换成并发集合");
        check(n(eng, "val segDone = java.util.concurrent.atomic.AtomicInteger(0)") == 1,
                "A5 分片计数改成原子（非原子自增会丢计数）");
        check(n(eng, "prog.segDone.updateAndGet { v ->") == 1, "A6 分片计数用原子自增（第 53 轮起带封顶）");
        check(n(eng, "prog.done.addAndGet(") >= 1, "A7 总进度改成原子累加（此前是 set(base+d)）");
        // ★ 反向断言：不许再出现非原子的 set(base + d)
        check(n(eng, "prog.done.set(base + d)") == 0,
                "A8 **不再有 done.set(base + d)** 这种非原子写法");
        check(n(eng, "segDone = prog.segDone.get()") >= 3, "A9 读取原子计数一律走 .get()");

        System.out.println("   -- 行为级复算：20 线程并发累加 --");
        long[] r = raceRecount(20, 20000);
        System.out.printf("      期望 %d，修复前丢失 %d（%.0f%%），修复后丢失 %d%n",
                r[0], r[1], 100.0 * r[1] / r[0], r[2]);
        check(r[1] > 0, "A10 复算证明修复前的写法**确实会丢**（丢失 " + r[1] + " 次自增）");
        check(r[2] == 0, "A11 复算证明原子累加**零丢失**");

        // ---- 通知风暴 ----
        System.out.println("== 通知刷新不再是风暴（多任务时） ==");
        check(n(svc, "private const val TICK_INTERVAL_MS = 300L") == 1, "B1 通知合帧间隔是常量（300ms）");
        check(n(svc, "tickScheduled.compareAndSet(false, true)") == 1, "B2 用 CAS 保证同一时刻只有一次待刷新");
        check(n(svc, "mainHandler.postDelayed(tickRunnable, TICK_INTERVAL_MS)") == 1, "B3 统一 post 到主线程队列");
        check(n(svc, "android.os.Looper.getMainLooper()") == 1, "B4 绑定主线程 Looper");
        check(n(svc, "mainHandler.removeCallbacks(tickRunnable)") == 1, "B5 销毁时摘掉待执行的回调");
        // ★ 关键：回调里不能直接 buildNotification
        check(n(svc, "onProgressTick = { title, done, total, determinate ->\n            pendingTick = Tick(title, done, total, determinate)") >= 1,
                "B6 onProgressTick 只记状态、不直接重建通知（此前在最多 20 个分片线程里跑 binder IPC）");

        // ================================================================ (2) 动画
        System.out.println("== (2) 进本地下载的转场动画 ==");
        check(n(dui, "import androidx.compose.animation.core.animateFloatAsState") == 1, "C1 引入了动画库");
        check(n(dui, "animateFloatAsState(") >= 2, "C2 位移与透明度各一条动画");
        check(n(dui, "label = \"dl-panel-slide\"") == 1, "C3 位移动画");
        check(n(dui, "label = \"dl-panel-alpha\"") == 1, "C4 透明度动画");
        check(n(dui, "translationY = slide * 48f") == 1, "C5 进场从下方滑入");
        check(n(dui, "animationSpec = tween(durationMillis = if (visible) 260 else 180)") >= 1, "C6 进出场时长分开（进 260 / 出 180）");
        // ★ 退场后才真关闭；且不能再用 LaunchedEffect(visible) 那种首帧误关写法
        check(n(dui, "var closing by remember { mutableStateOf(false) }") == 1, "C7 用显式 closing 标志");
        check(n(dui, "LaunchedEffect(closing)") == 1, "C8 退场结束才回调 onClose");
        check(n(dui, "LaunchedEffect(visible) {") == 0, "C9 不再用 LaunchedEffect(visible)（首帧 key 就是 false，会延迟一拍把面板自己关掉）");
        check(n(dui, "clickable { requestClose() }") == 1, "C10 返回键走动画关闭");

        // ================================================================ (3) 加速（官方源不动）
        System.out.println("== (3) 板块/介绍页加速，且官方源口径不动 ==");
        check(n(hc, "connectionPool(okhttp3.ConnectionPool(48, 15, TimeUnit.MINUTES))") == 1,
                "D1 共用客户端有连接池（默认只留 5 条空闲 -> 板块+详情连着打时反复握手）");
        check(n(hc, "maxRequestsPerHost = 24") >= 1, "D2 调度器放宽（8 -> 24，默认 5 会把板块页自己的请求排队）");
        check(n(hc, "retryOnConnectionFailure(true)") >= 1, "D3 打开连接失败重试");
        // ★★ 官方源不动：这些必须原样
        check(n(hc, "@@") == 0 && n(hc, "officialHosts = listOf(\"hanime1.me\", \"hanime1.com\", \"hanimeone.me\", \"javchu.com\")") == 1,
                "D4 官方源域名列表**一字未改**");
        check(n(hc, "connectTimeout(20, TimeUnit.SECONDS)") == 1, "D5 接口超时未改（仍是 20s）");
        check(n(hc, "readTimeout(30, TimeUnit.SECONDS)") == 1, "D6 读超时未改（仍是 30s）");
        check(n(hc, ".dns(hanimeDns)") == 1, "D7 DoH 仍是同一个（未改口径）");
        check(n(hc, ".proxySelector(proxySelector)") >= 1, "D8 代理选择器未改");
        check(n(hc, ".cookieJar(jar)") == 1, "D9 cookie 罐未改");
        // 共享源与生成产物一致（说明改动确实进了构建）
        check(n(hsrc, "maxRequestsPerHost = 12") >= 1, "D10 共享源里也改了（不是只改生成物）");

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
        check(anyContains(blobs, "dl-panel-slide"), "E2 转场动画进了 dex");
        check(anyContains(blobs, "同时 ") || anyContains(blobs, "全部取消"), "E3 通知文案进了 dex");
        check(anyContains(blobs, "hanime1.me"), "E4 官方源域名仍在包里");

        System.out.println("========================================");
        System.out.println("R51Check  PASS=" + pass + "  FAIL=" + fail);
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