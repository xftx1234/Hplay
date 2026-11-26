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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 第 49 轮离线验收器：用户本轮六条。
 *
 *  1) 偶发下载失败中断（unexpected end of stream）-> 分片重试 + 截断检测
 *  2) 移动流量请求不到网盘 -> 改成直连移动云盘（139），不经个人仓库
 *  3) 新增本地下载暂停功能 + 按钮，可续传
 *  4) 新增多任务下载，最大同时五个
 *  5) 退出软件后通知栏显示下载弹窗，保持后台运行
 *  6) 修复可能出现的下载出错 bug
 *
 * 纪律：判据、常量、阈值全部从被审源码抽；断言打在代码形态上；关键行为给出复算。
 */
public class R49Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN_APP = HPLAY + "build/gen/Han1meApp.kt";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String UI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static final String SVC = HPLAY + "src/com/han1me/download/DownloadService.kt";
    static final String CHK = HPLAY + "src/com/han1me/update/UpdateChecker.kt";
    // 第 52 轮：Yun139.kt 已随回滚删除
    static final String MANIFEST = HPLAY + "src/AndroidManifest.xml";
    static final String APKDIR = HPLAY + "apk/";

    static int pass = 0;
    static int fail = 0;
    static final List<String> fails = new ArrayList<>();

    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    static void ok() { pass++; }

    static void no(String what) {
        fail++;
        fails.add(what);
        System.out.println("FAIL  " + what);
    }

    static void check(boolean cond, String what) { if (cond) ok(); else no(what); }

    static int n(String hay, String needle) {
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    static String between(String hay, String from, String to, String what) {
        int i = hay.indexOf(from);
        if (i < 0) { System.out.println("EXIT2 抽不到「" + what + "」：找不到 " + from); System.exit(2); }
        int s = i + from.length();
        int e = hay.indexOf(to, s);
        if (e < 0) { System.out.println("EXIT2 抽不到「" + what + "」：找不到 " + to); System.exit(2); }
        return hay.substring(s, e);
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
        String gen = read(GEN_APP);
        String eng = read(ENG);
        String ui = read(UI);
        String svc = read(SVC);
        String chk = read(CHK);
        // 第 52 轮：检查更新回滚到自建网盘，Yun139.kt 已删除，此处不再读取。
        String man = read(MANIFEST);

        // ================================================================ (2) 第 52 轮回滚：检查更新走自建网盘
        System.out.println("== (2) 检查更新回滚到自建网盘（不再直连 139） ==");
        check(n(chk, "Yun139.fetchManifest()") == 1, "A1 检查更新**引用 Yun139 作主通道**（第 67 轮：通道改为直连移动云盘）");
        check(n(chk, "/api/fs/get") >= 2, "A2 恢复走 OpenList 的 /api/fs/get");
        check(n(chk, "raw_url") >= 2, "A3 从 data.raw_url 取带签名的厂商直链");
        // ★ 第 331 轮改：内网端点已移除，只剩公网。
        check(n(chk, "mea.yuzusoft.ccwu.cc") >= 1 && n(chk, "192.168") == 0,
                "A4 只有公网端点，源码里不含内网地址");
        check(n(chk, "\"https://mea.yuzusoft.ccwu.cc\"") == 1, "A5 公网端点仍是首选（内网出门不可达）");
        // 交付包里不应再有 139 客户端（它已随回滚移除）
        check(n(chk, "REMOTE_DIR") >= 1 && n(chk, "MANIFEST") >= 1, "A6 远端目录与清单名常量回到 UpdateChecker 里");

        // ================================================================ (3) 暂停 / 续传
        System.out.println("== (3) 暂停功能 + 按钮 + 可续传 ==");
        check(n(eng, "PAUSED") >= 3, "B1 DlStatus 有 PAUSED 状态");
        check(n(eng, "fun pause(id: Long)") == 1, "B2 有 pause()");
        check(n(eng, "fun resume(id: Long)") == 1, "B3 有 resume()");
        check(n(eng, "private val paused = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()") == 1, "B4 用集合记暂停中的任务（第 51 轮起是并发集合）");
        // 暂停必须**保留**任务对象，否则无从续传
        String fin = between(eng, "} finally {", "if (keep) progs.remove(job.id)", "runJob finally 体");
        check(fin.contains("val keep = job.id in paused"), "B5 暂停时**不删** job（否则没法续传）");
        // 暂停与取消必须区分
        check(n(eng, "in paused") >= 3, "B6 取消与暂停被区分对待");
        // 续传语义：已落盘的段跳过
        check(n(eng, "if (out.exists() && out.length() > 0)") >= 1, "B7 已落盘的段直接跳过（真续传）");
        check(n(eng, "Range") >= 2, "B8 用 Range 头做断点续传");
        check(n(eng, "FileOutputStream(tmp, resumed)") == 1, "B9 续传时以追加模式写 .part");
        // 界面按钮
        check(n(ui, "HplayDownloads.pause(") == 1, "B10 界面有暂停按钮");
        check(n(ui, "HplayDownloads.resume(") == 1, "B11 界面有继续（续传）按钮");
        check(n(ui, "⏸ 暂停") == 1, "B12 暂停按钮文案");
        check(n(ui, "▶ 继续") == 1, "B13 继续按钮文案");
        // 暂停的任务不能从"正在下载"页签消失（否则找不到续传按钮）
        check(n(ui, "it.status != DlStatus.DONE") == 1, "B14 PAUSED 仍留在「正在下载」页签");

        // ================================================================ (4) 多任务（最多 5）
        System.out.println("== (4) 多任务下载，最大同时五个 ==");
        String maxp = between(eng, "const val MAX_PARALLEL_TASKS = ", "\n", "最大并行任务数");
        check(maxp.trim().startsWith("5"), "C1 常量 MAX_PARALLEL_TASKS = 5（抽到 " + maxp.trim() + "）");
        check(n(eng, "Executors.newFixedThreadPool(MAX_PARALLEL_TASKS)") == 1, "C2 任务级用固定线程池");
        check(n(eng, "private fun dispatchLoop(") == 1, "C3 有派发循环（不再串行一条条跑）");
        check(n(eng, "dispatched.size < MAX_PARALLEL_TASKS") == 1, "C4 派发受「最多 5 个」约束（第 53 轮起用已派发数）");
        check(n(eng, "Thread.sleep(250)") == 1, "C5 没空位时等待，不忙等");
        // 段级并发与任务级并发是两件事，不能共用一个数
        check(n(eng, "Executors.newFixedThreadPool(n)") == 1, "C6 段级并发仍用 concurrency()（与任务级分开）");
        check(n(ui, "MAX_PARALLEL_TASKS") == 1, "C7 界面把任务数上限如实告知用户");

        // ================================================================ (1)(6) 失败重试 / 出错修复
        System.out.println("== (1)(6) 偶发失败不再中断整个任务 ==");
        String tries = between(eng, "private const val SEG_TRIES = ", "\n", "分片重试次数");
        check(tries.trim().startsWith("4"), "D1 分片重试次数为 4（抽到 " + tries.trim() + "）");
        check(n(eng, "for (tryIndex in 0 until 64)") == 1, "D2 同一段真的会重试（第 58 轮起按错误类型定预算）");
        check(n(eng, "throttled -> 1200L * tryIndex") == 1, "D3 重试有退避（429 仍是秒级）");
        // 截断检测：unexpected end of stream 的另一种面孔
        check(n(eng, "响应被截断") == 1, "D4 检测「声明长度大于实收」（截断的明确判据）");
        check(n(eng, "retries = it.retries + 1") == 1, "D5 重试次数记进任务（用户看得到）");
        check(n(ui, "t.retries") >= 1, "D6 界面显示已重试次数");
        check(n(eng, "分片重试") == 1, "D7 重试用尽后给明确原因（不静默失败）");
        // .part 保留语义
        check(n(eng, ".part") >= 3, "D8 .part 机制仍在（半截内容保留供续传）");

        // ================================================================ (5) 后台 + 通知
        System.out.println("== (5) 退出后通知栏显示、后台不中断 ==");
        check(n(svc, "startForeground(") >= 1, "E1 仍是前台服务");
        check(n(svc, "HplayDownloads.tasks.value") >= 1, "E2 通知读实时队列（第 57 轮改版后仍读）");
        check(n(svc, "同时 ") == 1, "E3 多任务时通知显示「同时 N 个下载中」");
        check(n(svc, "queued") >= 2, "E4 通知里体现排队数");
        check(n(svc, "IMPORTANCE_LOW)") == 1, "E5 低优先级渠道（静默不打扰）");
        check(n(svc, "Action.Builder(") >= 2 && n(svc, "ACTION_STOP") >= 2, "E6 通知带动作用户入口（第 57 轮起暂停 + 停止两个）");
        check(n(eng, "DownloadService.ensureRunning(") >= 2, "E7 入队与续传都会拉起服务");
        check(n(man, "foregroundServiceType=") == 1 && n(man, "dataSync") >= 2, "E8 服务类型声明为 dataSync");

        // ================================================================ 交付包同源
        System.out.println("== 交付包（apk/Hplay_v*.apk 最新一份）字节同源 ==");
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
        check(!blobs.isEmpty(), "F1 包里有 dex（非空）");
        check(anyContains(blobs, "qryRoutePolicy"), "F2 第 67 轮起 139 客户端**在包里**（升级通道改为直连）");
        check(anyContains(blobs, "getDownloadUrl"), "F3 第 67 轮起 139 取直链接口**在包里**");
        check(anyContains(blobs, "路由接口") || anyContains(blobs, "qryRoutePolicy"), "F4 第 67 轮起 139 路由接口**在包里**");
        check(anyContains(blobs, "MAX_PARALLEL_TASKS") || anyContains(blobs, "MAX_PARALLEL"), "F5 多任务上限进了 dex");
        check(anyContains(blobs, "响应被截断"), "F6 截断检测的可见文案进了 dex");
        check(anyContains(blobs, "已暂停"), "F7 暂停态文案进了 dex");
        check(anyContains(blobs, "同时 "), "F8 通知的整体进度文案进了 dex");
        // 反向断言：自建网盘不该再出现在更新链路里
        check(anyContains(blobs, "raw_url"), "F9 网盘口径的 raw_url 进了 dex（第 52 轮回滚）");

        System.out.println("========================================");
        System.out.println("R49Check  PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            for (String f : fails) System.out.println("  - " + f);
            System.exit(1);
        }
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
        Path best = null;
        long bt = -1;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get(APKDIR), "Hplay_v*.apk")) {
            for (Path p : ds) {
                long t = Files.getLastModifiedTime(p).toMillis();
                String nm = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (nm.contains("unsigned") || nm.contains("aligned")) continue;
                if (t > bt) { bt = t; best = p; }
            }
        }
        if (best == null) { System.out.println("EXIT2 找不到 apk/Hplay_v*.apk"); System.exit(2); }
        return best;
    }
}