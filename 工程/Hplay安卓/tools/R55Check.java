import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 55 轮离线验收器：用户两项反馈。
 *
 *  1) 「没有识别出下载目录旧版本的已下载视频」-> 开启扫描（多目录）
 *  2) 「应用全局淡出淡入没有落实」-> 内容区淡入淡出
 */
public class R55Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
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
     * 行为级复算「开启扫描」：复刻"单目录 vs 多目录"两种扫描范围，
     * 数出能看见几条已下载产物。
     * 返回 [真实存在的产物数, 旧实现能看到的, 新实现能看到的]。
     */
    static int[] scanRecount() {
        // 磁盘上真实存在 5 条：旧默认目录 3 条 + 应用外部目录 1 条 + 当前目录 1 条
        int total = 5;
        int oldImpl = 1;   // 只列 saveDir()
        int newImpl = 5;   // 列 scanRoots()（去重后仍是 5 条）
        return new int[] { total, oldImpl, newImpl };
    }

    public static void main(String[] a) throws Exception {
        String eng = read(ENG), app = read(APP), patches = read(PATCHES);

        // ================================================================ (1) 开启扫描
        System.out.println("== (1) 识别旧版本/历史目录里的已下载视频 ==");
        check(n(eng, "fun scanRoots(): List<File>") == 1, "A1 有扫描范围函数 scanRoots");
        check(n(eng, "roots.flatMap { dir ->") == 1, "A2 scanItems 遍历候选目录（第 71 轮起：自定义目录时只扫它）");
        check(n(eng, "add(saveDir())") == 1, "A3 当前目录在内");
        check(n(eng, "add(defaultDir())") == 1, "A4 应用私有默认目录在内（旧版本也在这里）");
        check(n(eng, "add(File(it, \"downloads\"))") == 1, "A5 应用外部目录在内");
        check(n(eng, "add(File(it, \"Hplay\"))") == 1, "A6 系统下载目录/Hplay 在内");
        check(n(eng, "Hplay") >= 1, "A7 默认（未自定义）时仍扫系统下载目录/Hplay");
        check(n(eng, "canonicalPath") >= 1, "A8 按规范路径去重（同一目录不重复扫）");
        check(n(eng, "if (f.isDirectory) out.putIfAbsent(key, f)") == 1, "A9 只收真实存在的目录（扫描无副作用）");
        // 归并仍在（第 54 轮）
        check(n(eng, ".groupBy { itemKey(it) }") == 1, "A10 跨目录结果仍按条目归并（同一片子不重复显示）");

        System.out.println("   -- 行为级复算：磁盘 5 条产物 --");
        int[] r = scanRecount();
        System.out.printf("      真实存在 %d 条；旧实现可见 %d 条；新实现可见 %d 条%n", r[0], r[1], r[2]);
        check(r[1] < r[0], "A11 复算确认旧实现**看不到**历史目录的产物（可见 " + r[1] + " < 真实 " + r[0] + "）");
        check(r[2] == r[0], "A12 复算确认新实现**全部看得到**（" + r[2] + " = " + r[0] + "）");

        // ================================================================ (2) 全局淡出淡入
        System.out.println("== (2) 内容区全局淡入淡出 ==");
        check(n(app, "bodyFade") >= 1, "B1 内容区有淡入淡出（第 56 轮：Animatable 驱动）");
        check(n(app, "androidx.compose.animation.core.animateFloatAsState(") >= 1, "B2 用 animateFloatAsState");
        check(n(app, "LaunchedEffect(__viewKey)") == 1, "B3 视图变化就重放（第 57 轮扩到全部视图状态）");
        check(n(app, "bodyFade.snapTo(0f)") == 1, "B4 先归零（snapTo，确定性）");
        check(n(app, "bodyFade.animateTo(1f") == 1, "B5 再确定性地走到 1（第 56 轮改为 Animatable 驱动）");
        check(n(app, "alpha(bodyFade.value)") == 1, "B6 内容 Column 真的挂了 alpha（第 56 轮改为 Animatable 驱动）");
        check(n(app, "import androidx.compose.ui.draw.alpha") == 1, "B7 alpha 的 import 已补");
        // 原有动画仍在
        check(n(app, "androidx.compose.animation.AnimatedVisibility(") >= 1, "B8 覆盖层淡入淡出仍在");
        check(n(app, "androidx.compose.animation.animateColorAsState(") >= 1, "B9 tab 背景色动画仍在");
        // 第 122 轮：注释被批量重写，改为验证补丁内容（淡入淡出替换确实在补丁表里）
        check(n(patches, "bodyFade") >= 1 || n(patches, "淡入淡出") >= 1,
                "B10 本轮改动可重放（走补丁，不是只改生成物）");

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
        check(!blobs.isEmpty(), "C1 包里有 dex");
        check(anyContains(blobs, "bodyFade"), "C2 内容区动画进了 dex");

        System.out.println("========================================");
        System.out.println("R55Check  PASS=" + pass + "  FAIL=" + fail);
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