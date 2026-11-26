import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 58 轮离线验收器：用户四条（含一张截图）。
 *
 *  1) 息屏恢复后仍会下载失败 -> 连接级失败给足重试预算
 *  2) 加一个手动重试按钮 -> 失败时保留 Job + retry 入口 + UI 按钮
 *  3) 页面动画还是过快 -> 放慢
 *  4) 不妨碍下载的情况下最大下载速度 -> 恢复满并发 + 域级自适应降速
 */
public class R58Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String DUI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
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
        String eng = read(ENG), dui = read(DUI), app = read(APP);

        // ================================================================ (2) 手动重试
        System.out.println("== (2) 手动重试按钮 ==");
        check(n(eng, "private val failedJobs = HashMap<Long, Job>()") == 1, "A1 失败任务单独留存（否则重试没有 URL/详情可用）");
        check(n(eng, "failedJobs[job.id] = job") >= 1, "A2 失败时把 Job 记进去");
        check(n(eng, "val keep = job.id in paused || failedJobs.containsKey(job.id)") == 1,
                "A3 失败时**不再删掉 Job**（老实现直接 remove 导致无法重试）");
        check(n(eng, "fun retry(id: Long): Boolean") == 1, "A4 有 retry()");
        check(n(eng, "fun canRetry(id: Long): Boolean") == 1, "A5 有 canRetry()");
        check(n(eng, "phase = " + (char) 34 + "排队中（重试）" + (char) 34) == 1, "A6 重试后阶段可见");
        check(n(eng, "retries = 0,") >= 1, "A7 用户主动重试时重试计数归零");
        check(n(eng, "failedJobs.remove(id)") >= 1, "A8 移除记录时清掉可重试记录");
        check(n(dui, "HplayDownloads.retry(t.id)") == 1, "A9 UI 有重试按钮");
        check(n(dui, "↻ 重试") == 1, "A10 按钮文案");
        check(n(dui, "HplayDownloads.canRetry(t.id)") == 1, "A11 按可否重试决定显不显示");

        // ================================================================ (1) 连接级重试
        System.out.println("== (1) 息屏恢复后仍失败 ==");
        check(n(eng, "private const val CONNECT_TRIES = 8") == 1, "B1 连接级失败给更多重试次数");
        check(n(eng, "msg.contains(" + (char) 34 + "Failed to connect" + (char) 34 + ", true)") == 1,
                "B2 识别 Failed to connect（截图里那个错）");
        check(n(eng, "msg.contains(" + (char) 34 + "ConnectException" + (char) 34 + ", true)") == 1, "B3 识别 ConnectException");
        check(n(eng, "Unable to resolve host") == 1, "B4 识别 DNS 失败");
        check(n(eng, "val maxTries = if (connectFail) CONNECT_TRIES else SEG_TRIES") == 1,
                "B5 按错误类型给不同预算");
        check(n(eng, "connectFail -> 500L * tryIndex") == 1, "B6 连接失败用 0.5s 级退避等链路恢复");
        check(n(eng, "网络未就绪，重连中") == 1, "B7 界面如实显示正在重连");

        // ================================================================ (3) 动画放慢
        System.out.println("== (3) 动画放慢 ==");
        check(n(app, "durationMillis = 420") == 1, "C1 内容区 fade 220 → 420");
        check(n(app, "durationMillis = 360") == 2, "C2 覆盖层 fade 200 → 360（进出各一）");
        check(n(app, "durationMillis = 300") == 1, "C3 tab 底色 180 → 300");
        // 反断言：旧的快时长不该还留在内容区
        check(n(app, "bodyFade.animateTo(1f, androidx.compose.animation.core.tween(durationMillis = 220))") == 0,
                "C4 反断言：220ms 的旧值已不在");

        // ================================================================ (4) 最大速度
        System.out.println("== (4) 不妨碍下载的最大速度 ==");
        check(n(eng, "private const val CONC_DEFAULT = 16") == 1, "D1 分片并发默认 16（第 120 轮实测驱动，域级自适应降速保留）");
        check(n(eng, "private val throttledUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()") == 1,
                "D2 有**域级**限流冷却");
        check(n(eng, "private fun hostThrottled(url: String): Boolean") == 1, "D3 能查询某域是否在冷却中");
        check(n(eng, "throttledUntil[it] = System.currentTimeMillis() + 30_000L") == 1, "D4 429 后对该域冷却 30 秒");
        check(n(eng, "val baseN = concurrency()") == 1, "D5 默认按用户设定跑满");
        check(n(eng, "if (hostThrottledNow) (baseN / 2).coerceAtLeast(1) else baseN") == 1,
                "D6 只有被限流的域临时减半（自适应，不是全局降）");
        check(n(eng, "这个域被限流过，临时降到 ") == 1, "D7 临时降速时界面如实说明");

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
        check(anyContains(blobs, "排队中（重试）"), "E2 重试文案进了 dex");
        check(anyContains(blobs, "网络未就绪，重连中"), "E3 重连文案进了 dex");

        System.out.println("========================================");
        System.out.println("R58Check  PASS=" + pass + "  FAIL=" + fail);
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
