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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 第 53 轮离线验收器：真机截图报的四个 bug + 两处动画。
 *
 *  1) 「第 531/189 片」+ HTTP 429 + 剩余 00:00 仍在跑
 *     -> 重复派发竞态（同一个 job 被两个线程并发跑）
 *  2) 删除视频卡死 -> 主线程递归删文件
 *  3) 删除要确认弹窗
 *  4) 页面/弹窗切换淡出淡入
 *  5) 分片最终合并成一个视频文件（用户重申）
 *
 * 纪律：先复现再修。A 段是**行为级并发复算**，同一套判据跑修复前/修复后。
 */
public class R53Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String DUI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static final String APP = GEN + "Han1meApp.kt";
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

    /**
     * 行为级复算「重复派发」：复刻两个版本的派发判据，数同一任务被跑了几次。
     * 返回 [期望执行次数, 旧判据的实际次数, 新判据的实际次数]。
     */
    static int[] dispatchRecount(int taskCount, int parallel) throws Exception {
        int[] out = new int[3];
        out[0] = taskCount;

        // 旧：判据只看 RUNNING（RUNNING 在 worker 内部才设）——有窗口期
        out[1] = runDispatch(taskCount, parallel, false);
        // 新：派发时原子占位
        out[2] = runDispatch(taskCount, parallel, true);
        return out;
    }

    static int runDispatch(int taskCount, int parallel, boolean usePlaceholder) throws Exception {
        final ConcurrentHashMap<Long, String> status = new ConcurrentHashMap<>();
        for (int i = 0; i < taskCount; i++) status.put((long) i, "QUEUED");
        final ConcurrentHashMap<Long, Integer> runs = new ConcurrentHashMap<>();
        for (int i = 0; i < taskCount; i++) runs.put((long) i, 0);
        final java.util.Set<Long> placeholder = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        // 先占住 worker，制造"派发后要排队"的窗口（真机多任务时就是这样）
        for (int i = 0; i < parallel; i++) pool.execute(() -> { try { Thread.sleep(900); } catch (Exception e) {} });

        Thread d = new Thread(() -> {
            long end = System.currentTimeMillis() + 1200;
            while (System.currentTimeMillis() < end) {
                Long pick = null;
                int busy = usePlaceholder ? placeholder.size()
                        : (int) status.values().stream().filter(s -> "RUNNING".equals(s)).count();
                if (busy < parallel) {
                    for (int i = 0; i < taskCount; i++) {
                        final long id = i;
                        if (!"QUEUED".equals(status.get(id))) continue;
                        if (usePlaceholder && !placeholder.add(id)) continue;
                        pick = id;
                        break;
                    }
                }
                if (pick != null) {
                    final long id = pick;
                    pool.execute(() -> {
                        status.put(id, "RUNNING");
                        runs.put(id, runs.get(id) + 1);
                        try { Thread.sleep(600); } catch (Exception e) {}
                        status.put(id, "DONE");
                        placeholder.remove(id);
                    });
                }
                try { Thread.sleep(250); } catch (Exception e) {}
            }
        });
        d.start(); d.join();
        pool.shutdownNow();
        int total = 0;
        for (int v : runs.values()) total += v;
        return total;
    }

    public static void main(String[] a) throws Exception {
        String eng = read(ENG), dui = read(DUI), app = read(APP), patches = read(PATCHES);

        // ================================================================ (1) 重复派发
        System.out.println("== (1) 「531/189 片」的根因：同一任务被重复派发 ==");
        check(n(eng, "private val dispatched = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()") == 1,
                "A1 有「已派发」占位集合");
        check(n(eng, "if (!dispatched.add(t.id)) continue") == 1,
                "A2 派发时用原子占位（占不到就跳过）——这是修根因的那一行");
        check(n(eng, "dispatched.remove(job.id)") == 1, "A3 任务结束时释放占位");
        check(n(eng, "dispatched.size < MAX_PARALLEL_TASKS") == 1,
                "A4 并发位判据改成「已派发数」（原判据是数 RUNNING，有窗口期）");
        // ★ 反向断言：不许再用「数 RUNNING」当派发判据
        check(n(eng, "_tasks.value.count { it.status == DlStatus.RUNNING }") == 0,
                "A5 **不再用「数 RUNNING」当派发判据**（RUNNING 在 runJob 内部才设）");
        check(n(eng, "dispatched.remove(id)") >= 1, "A6 cancel/remove 路径也释放占位");

        System.out.println("   -- 行为级复算：3 任务 / 并发位 5，数实际执行次数 --");
        // 竞态是时序相关的：跑 3 轮取「旧判据的最坏值」，避免偶发跑不出重复派发
        int[] r = dispatchRecount(3, 5);
        for (int round = 0; round < 2; round++) {
            int[] r2 = dispatchRecount(3, 5);
            if (r2[1] > r[1]) { r[1] = r2[1]; }
            if (r2[2] > r[2]) { r[2] = r2[2]; }
        }
        System.out.printf("      期望每任务 1 次（共 %d）；旧判据实际 %d 次；新判据实际 %d 次%n", r[0], r[1], r[2]);
        check(r[1] > r[0], "A7 复算证明旧判据**确实会重复派发**（实际 " + r[1] + " > 期望 " + r[0] + "）");
        check(r[2] == r[0], "A8 复算证明原子占位**恰好派发一次**（实际 " + r[2] + " = 期望 " + r[0] + "）");

        // ================================================================ 计数封顶 / 收尾
        System.out.println("== 「剩余 00:00 仍在跑」与计数越界 ==");
        check(n(eng, "prog.segDone.updateAndGet { v ->") == 1, "B1 分片计数改成带封顶的原子更新");
        check(n(eng, "if (prog.segTotal > 0 && nv > prog.segTotal) prog.segTotal else nv") == 1,
                "B2 计数不会越过总数（杜绝 531/189）");
        check(n(eng, "prog.inflight.clear()") >= 1, "B3 分片结束时清掉在途比例");
        check(n(eng, "prog.segDone.set(prog.segTotal)") == 1, "B4 收尾时把计数对齐到总数");
        check(n(eng, "prog.eta = 0L") >= 1, "B5 收尾时剩余时间确定性清零");

        // ================================================================ 429 退避
        System.out.println("== HTTP 429 限流退避 ==");
        check(n(eng, "val throttled = msg.contains(") == 1, "C1 识别 429");
        check(n(eng, "throttled -> 1200L * tryIndex") == 1,
                "C2 429 用秒级退避（普通错误仍是 300ms 级）");
        check(n(eng, "被限流，等待") == 1, "C3 界面如实显示「被限流，等待 Ns 后重试」");

        // ================================================================ (2) 删除不再卡主线程
        System.out.println("== (2) 删除卡死：主线程不再做重 IO ==");
        check(n(dui, "withContext(Dispatchers.IO)") >= 1, "D1 删除放进 IO 线程");
        check(n(dui, "HplayDownloads.delete(p)?.let { acc += it }") == 1, "D2 后台批量删除");
        // ★ 反向断言：不能还在点击回调里直接同步删
        check(n(dui, "onDelete = { p -> HplayDownloads.delete(p)") == 0,
                "D3 **不再在点击回调里同步删**（旧写法会卡死主线程）");

        // ================================================================ (3) 确认弹窗
        System.out.println("== (3) 删除确认弹窗 ==");
        check(n(dui, "var confirmDelete by remember") == 1, "E1 有确认弹窗状态");
        check(n(dui, "private fun ConfirmDialog(") == 1, "E2 有确认弹窗组件");
        check(n(dui, "删除这个视频？") == 1, "E3 单条删除的确认文案");
        check(n(dui, "删除选中的 ") == 1, "E4 批量删除的确认文案");
        check(n(dui, "if (busy) \"删除中…\" else \"删除\"") == 1, "E5 删除进行中如实显示（不假装已完成）");
        check(n(dui, "onDelete = { p -> confirmDelete = listOf(p) }") == 1, "E6 删除先走确认，不直接执行");

        // ================================================================ (4) 淡出淡入
        System.out.println("== (4) 淡出淡入（页面与弹窗） ==");
        check(n(app, "androidx.compose.animation.AnimatedVisibility(") == 1, "F1 下载页覆盖层用 AnimatedVisibility");
        check(n(app, "androidx.compose.animation.fadeIn(") >= 1, "F2 有 fadeIn");
        check(n(app, "androidx.compose.animation.fadeOut(") >= 1, "F3 有 fadeOut");
        check(n(app, "androidx.compose.animation.animateColorAsState(") >= 1, "F4 tab 选中背景用颜色动画（原来硬切）");
        check(n(dui, "label = \"confirm-alpha\"") == 1, "F5 确认弹窗有淡入");
        check(n(dui, "label = \"dl-panel-alpha\"") == 1, "F6 下载面板进出场仍是淡入淡出");
        check(n(patches, "18a-1.") == 1 && n(patches, "18a-2.") == 1 && n(patches, "18a-3.") == 1,
                "F7 三条动画改动是**可重放的补丁**（不是改生成物）");

        // ================================================================ (5) 合并成单文件
        System.out.println("== (5) 分片最后合并成一个视频文件 ==");
        check(n(eng, "合并成单文件") >= 1, "G1 有合并阶段");
        check(n(eng, "f.inputStream().use { it.copyTo(out) }") >= 1, "G2 流式合并（逐片 copyTo，内存安全）");
        check(n(eng, "work.deleteRecursively()") >= 1, "G3 合并后清掉中间分片目录");
        check(n(eng, "合并单文件失败（已保留分片形态）") == 1, "G4 合并失败保留分片并说明");
        check(n(eng, "initName?.let") == 1, "G5 fMP4 初始化段先写");

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
        check(!blobs.isEmpty(), "H1 包里有 dex");
        check(anyContains(blobs, "被限流，等待"), "H2 429 退避文案进了 dex");
        check(anyContains(blobs, "删除这个视频？"), "H3 确认弹窗文案进了 dex");

        System.out.println("========================================");
        System.out.println("R53Check  PASS=" + pass + "  FAIL=" + fail);
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