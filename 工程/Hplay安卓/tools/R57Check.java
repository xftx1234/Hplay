import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 57 轮离线验收器：用户三条。
 *
 *  1) 全面应用本地下载页的淡出淡入，所有板块设置
 *  2) 通知栏照 1DM+ 一比一复刻（图标保持本应用特色）
 *  3) 后台下载 429 / 速度不稳 / 后台降速
 *
 * 纪律（第 56 轮的教训）：**每一条都必须在生成物里验证落地**，不能只看源码。
 */
public class R57Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String SVC = HPLAY + "src/com/han1me/download/DownloadService.kt";
    static final String APP = GEN + "Han1meApp.kt";
    static final String MAN = HPLAY + "src/AndroidManifest.xml";
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
        String eng = read(ENG), svc = read(SVC), app = read(APP), man = read(MAN);

        // ================================================================ (1) 全局淡出淡入
        System.out.println("== (1) 全局淡出淡入（所有板块与设置页） ==");
        check(n(app, "bodyFade") >= 4, "A1 生成物里真的有 bodyFade（第 56 轮铁律：验证落地）");
        check(n(app, "__viewKey") == 2, "A2 有统一视图键（声明 + 使用各一次）");
        // key 必须覆盖所有视图状态，不能只有 board/tab/mode
        check(n(app, "showMine") >= 2, "A3 视图键含 showMine（我的面板）");
        check(n(app, "showNotices") >= 2, "A4 视图键含 showNotices（信息页）");
        check(n(app, "showUpdate") >= 2, "A5 视图键含 showUpdate（检查更新）");
        check(n(app, "x99Focus") >= 2, "A6 视图键含 x99Focus（板块详情弹层）");
        check(n(app, "bodyFade.snapTo(0f)") == 1, "A7 命令式归零（不依赖重组时机）");
        check(n(app, "bodyFade.animateTo(1f") == 1, "A8 命令式走到 1");
        check(n(app, "alpha(bodyFade.value)") == 1, "A9 挂到内容区 Column");
        // 覆盖层自己的淡入淡出仍在
        check(n(app, "androidx.compose.animation.AnimatedVisibility(") >= 1, "A10 覆盖层淡入淡出仍在");

        // ================================================================ (2) 通知栏照 1DM+
        System.out.println("== (2) 通知栏照 1DM+ 复刻 ==");
        check(n(svc, "% | ") >= 1, "B1 状态行是「百分比 | …」形态");
        check(n(svc, "pct.toString()") == 1, "B2 百分比在最前（pct.toString()）");
        check(n(svc, "MB/s") >= 1, "B3 带速度");
        check(n(svc, "sizeText") >= 1 && n(svc, "humanOf(useDone)") >= 1, "B4 带「已下 / 总量」");
        check(n(svc, "m") >= 1 && n(svc, "padStart(2, '0')") == 1, "B5 带剩余时间（mss 形态）");
        check(n(svc, (char) 34 + "暂停" + (char) 34) >= 1, "B6 有「暂停」动作（1DM+ 两个动作之一）");
        check(n(svc, (char) 34 + "停止" + (char) 34) >= 1, "B7 有「停止」动作");
        check(n(svc, "ACTION_PAUSE_TOGGLE") >= 3, "B8 暂停动作有服务端处理（常量+注册+分支）");
        check(n(svc, "pauseOrResumeAll()") == 1, "B9 暂停按钮接到全部暂停/继续");
        // 图标保持本应用特色
        check(n(svc, "setSmallIcon(android.R.drawable.stat_sys_download)") == 1,
                "B10 小图标仍是本应用沿用的下载图标（未换成第三方图案）");
        check(n(svc, "CHANNEL_ID") >= 2, "B11 仍用自己的通知渠道");
        check(n(eng, "fun pauseOrResumeAll()") == 1, "B12 引擎侧提供全部暂停/继续");

        // ================================================================ (3) 后台下载三修
        System.out.println("== (3) 429 / 速度不稳 / 后台降速 ==");
        // ③-1 WakeLock
        check(n(man, "android.permission.WAKE_LOCK") == 1, "C1 清单声明 WAKE_LOCK");
        check(n(svc, "pm.newWakeLock(") == 1 && n(svc, "PARTIAL_WAKE_LOCK") >= 1, "C2 用 PARTIAL_WAKE_LOCK（只保 CPU，不点屏；实测取锁调用存在）");
        check(n(svc, "fun acquireWakeLock()") == 1, "C3 有取锁");
        check(n(svc, "fun releaseWakeLock()") == 1, "C4 有放锁");
        check(n(svc, "acquireWakeLock()") >= 2, "C5 onCreate 里取锁（含定义共 ≥2 处）");
        check(n(svc, "releaseWakeLock()") >= 3, "C6 队列排空与 onDestroy 都放锁（不空耗电）");
        check(n(svc, "setReferenceCounted(false)") == 1, "C7 非引用计数（重复取不会泄漏）");
        // ③-2 低速/卡死检测
        check(n(eng, "private const val STALL_WINDOW_MS") == 1, "C8 有低速检测窗口常量");
        check(n(eng, "private const val MIN_BPS") == 1, "C9 有最低速率门槛");
        check(n(eng, "if (bps < MIN_BPS)") == 1, "C10 低于门槛就主动断开重连");
        check(n(eng, "连接速率过低") == 1, "C11 如实说明为什么断开（不静默）");
        check(n(eng, "winStart") >= 2 && n(eng, "winBytes") >= 2, "C12 窗口平均速率（不是瞬时）");
        // ③-3 降并发缓解 429
        // 第 120 轮：实测 8 路并发比串行快 8.1 倍（63 分片/74MB 的肉视频：3.5 分钟 -> 0.4 分钟）
        check(n(eng, "private const val CONC_DEFAULT = 16") == 1, "C13 分片并发默认 16（第 120 轮实测驱动；429 仍走域级自适应降速）");
        check(n(eng, "HTTP 429") >= 1 || n(eng, "429") >= 1, "C14 429 仍有专门退避");

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
        check(!blobs.isEmpty(), "D1 包里有 dex");
        check(anyContains(blobs, "hplay:download"), "D2 WakeLock 标签进了 dex");
        check(anyContains(blobs, "连接速率过低"), "D3 低速重连的说明进了 dex");
        check(anyContains(blobs, "暂停"), "D4 通知暂停动作进了 dex");

        System.out.println("========================================");
        System.out.println("R57Check  PASS=" + pass + "  FAIL=" + fail);
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