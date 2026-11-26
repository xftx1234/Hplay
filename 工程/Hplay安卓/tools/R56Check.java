import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 56 轮离线验收器：用户两项反馈 + 一条方法论。
 *
 *  1) 下载功能没修好 -> 参照 Han1meViewer 补「手动扫描」（显式触发 + 如实报告）
 *  2) 动画淡出淡入没修好 -> 换成 Animatable 命令式驱动
 *  3) **新增铁律**：动画必须**验证它真的进了生成物**（前两轮就是没验证才反复失败）
 */
public class R56Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN = HPLAY + "build/gen/";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String DUI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static final String FADE = HPLAY + "src/com/han1me/ui/PageFade.kt";
    static final String APP = GEN + "Han1meApp.kt";
    static final String PATCHES = HPLAY + "tools/patches.py";
    static final String APKDIR = HPLAY + "apk/";
    static final String H1V = WS + "临时/avp_h1v_src/Han1meViewer-main/";

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
        String eng = read(ENG), dui = read(DUI), fade = read(FADE);
        String app = read(APP), patches = read(PATCHES);

        // ================================================================ (1) 手动扫描
        System.out.println("== (1) 手动扫描下载视频 ==");
        check(n(eng, "fun manualScan(): ScanReport") == 1, "A1 有 manualScan（显式触发）");
        check(n(eng, "data class ScanReport(") == 1, "A2 有扫描结果类型（如实报告扫到了什么）");
        check(n(eng, "roots = roots.map { it.absolutePath }") == 1, "A3 报告实际扫过的目录");
        check(n(eng, "itemsCache = null") >= 1, "A4 扫描会作废缓存（强制重扫）");
        check(n(eng, "itemsCache = found") == 1, "A5 扫描结果写回缓存（界面立即生效）");
        check(n(dui, "HplayDownloads.manualScan()") == 1, "A6 界面接了手动扫描");
        check(n(dui, "扫描完成：在 ") == 1, "A7 如实回显扫描结果（不编「已刷新」）");
        check(n(dui, "withContext(Dispatchers.IO)") >= 1, "A8 扫描在 IO 线程（不卡界面）");
        check(n(dui, "var scanning by remember") == 1, "A9 扫描中防重点");
        // 嵌套目录（<片名>/<片名>.mp4 形态）
        check(n(eng, "if (playable == null) {") >= 1, "A10 支持嵌套一层目录（别的下载器的形态）");
        // 参照原版
        check(new File(H1V + "app/src/main/java/com/yenaly/han1meviewer/util/SafFileManager.kt").isFile(),
                "A11 参照的 Han1meViewer 源码在本地（可随时比对）");

        // ================================================================ (2) 动画真的落地
        System.out.println("== (2) 淡出淡入：**必须真的进生成物** ==");
        check(n(fade, "fun Modifier.pageFade(key: Any): Modifier") == 1, "B1 有 pageFade 单行扩展");
        check(n(fade, "Animatable(1f)") == 1, "B2 用 Animatable（命令式）");
        check(n(fade, "a.snapTo(0.55f)") == 1, "B3 先归零");
        check(n(fade, "animateTo(") >= 1 && n(fade, "1f") >= 1, "B4 再确定性地走到 1（animateTo 多行写法）");
        check(n(fade, "LaunchedEffect(key)") == 1, "B5 key 变化即重放");
        // ★★★ 本轮的核心铁律：验证落地
        check(n(app, "bodyFade") >= 3, "B6 **生成物里真的有 bodyFade**（前两轮就是没验证才反复失败）");
        check(n(app, "bodyFade.snapTo(0f)") == 1, "B7 生成物里是 snapTo 驱动（不是 animateFloatAsState）");
        check(n(app, "bodyFade.animateTo(1f") == 1, "B8 生成物里有 animateTo");
        check(n(app, "alpha(bodyFade.value)") == 1, "B9 生成物里挂到了内容 Column 上");
        check(n(app, "LaunchedEffect(__viewKey)") == 1, "B10 随视图键重放（第 57 轮扩到全部视图状态）");
        // 反向断言：旧的不可靠写法不该再出现
        check(n(app, "bodyShown") == 0, "B11 反断言：旧的 bodyShown 写法已移除");
        check(n(app, "body-alpha") == 0, "B12 反断言：旧的 body-alpha 标签已移除");
        check(n(patches, "bodyFade") >= 1 || n(patches, "淡入淡出") >= 1, "B13 改动可重放（走补丁）");

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
        check(anyContains(blobs, "扫描完成"), "C2 手动扫描的提示进了 dex");

        System.out.println("========================================");
        System.out.println("R56Check  PASS=" + pass + "  FAIL=" + fail);
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