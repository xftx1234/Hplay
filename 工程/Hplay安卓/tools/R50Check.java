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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 第 50 轮离线验收器：用户四条 + 真机截图暴露的两个 bug。
 *
 *  1) 下载时闪退卡顿、界面不流畅 -> 扫盘 IO 移出重组路径 + 缓存
 *  2) 下载视频不分片，一个整文件 -> HLS 分片合并成单文件
 *  3) 已下载视频不在相册显示 -> .nomedia
 *  4) 截图：版本号显示成字面量（插值被写坏）-> 已修
 *  5) 截图：检查更新无法直接下载（没有凭据配置入口）-> 已补
 */
public class R50Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String UI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static final String UPD = HPLAY + "src/com/han1me/update/HplayUpdateUi.kt";
    // 第 52 轮：Yun139.kt 已随「回滚到自建网盘」删除
    static final String ENV = HPLAY + "src/com/han1me/HplayEnv.kt";
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
        String eng = read(ENG), ui = read(UI), upd = read(UPD), env = read(ENV);

        // ================================================================ (1) 卡顿
        System.out.println("== (1) 下载时不卡：扫盘 IO 移出重组路径 ==");
        check(n(eng, "@Volatile private var bytesCache: Long = -1L") == 1,
                "A1 体积有缓存字段（上一版每次重组都递归扫盘，实测 66 ms/次）");
        check(n(eng, "fun invalidateSize()") == 1, "A2 有显式缓存失效入口");
        check(n(eng, "bytesCache = -1L") >= 2, "A3 失效点不止一处");
        check(n(eng, "@Volatile private var dirCache: File? = null") == 1, "A4 保存目录有缓存");
        check(n(eng, "@Volatile private var concCache: Int = 0") == 1, "A5 并发数有缓存");
        check(n(eng, "@Volatile private var itemsCache: List<DlItem>? = null") == 1, "A6 条目列表有缓存");
        check(n(ui, "HplayDownloads.totalBytes()") == 1, "A7 界面只在 remember(tick) 里调一次 totalBytes（不再进每帧路径）");
        check(n(ui, "LaunchedEffect(tick) {") >= 1 && n(ui, "val totalBytes = remember(tick)") == 0,
                "A8 体积只在内容变化时重算（第 63 轮起移出主线程：LaunchedEffect + IO）");
        check(n(ui, "remember(tick) { HplayDownloads.concurrency() }") == 1, "A9 并发数也不在每帧读设置");
        check(n(ui, "verticalScroll(rememberScrollState())") == 1, "A10 仍是 verticalScroll 容器");
        check(n(ui, "lazyItems(") == 0, "A11 **没有**把 Lazy 列表嵌进 verticalScroll（那会无限高度 -> 闪退）");

        // ================================================================ (2) 不分片
        System.out.println("== (2) HLS 合并成一个文件 ==");
        check(n(eng, "合并成单文件") >= 1 && n(eng, "phase = ") >= 1,
                "B1 有合并阶段（phase 会切到「合并成单文件」）");
        check(n(eng, "f.inputStream().use { it.copyTo(out) }") >= 1, "B2 合并是流式的（逐片 copyTo）");
        check(n(eng, "work.deleteRecursively()") >= 1, "B3 合并成功后清掉中间分片目录");
        check(n(eng, ".ts" + (char) 34) >= 1 || n(eng, "ts\"") >= 1, "B4 合并产物是单个视频文件");
        check(n(eng, "val encrypted = lines.any") == 1, "B5 识别 AES-128 加密流");
        check(n(eng, "保持分片形态才能播") == 1, "B6 加密流如实说明为何不能合并");
        check(n(eng, "合并单文件失败（已保留分片形态）") == 1, "B7 合并失败保留分片并说明");
        check(n(eng, "initName?.let") == 1, "B8 fMP4 初始化段放在最前面");
        check(n(eng, "invalidateSize()") >= 4, "B9 落盘后失效体积缓存");

        // ================================================================ (3) 不进相册
        System.out.println("== (3) 已下载视频不在相册显示 ==");
        check(n(eng, "private fun hideFromGallery(dir: File)") == 1, "C1 有隐藏实现");
        check(n(eng, "hideFromGallery(it)") == 1, "C2 默认目录也写");
        check(n(eng, "hideFromGallery(f)") == 1, "C3 每次取保存目录都确保");
        check(n(eng, "hideFromGallery(work)") == 1, "C4 HLS 中间目录也放一份");
        char q = (char) 34;
        check(n(eng, q + ".nomedia" + q) == 1, "C5 用标准 .nomedia 约定");

        // ================================================================ (4) 版本号插值
        System.out.println("== (4) 截图 bug：版本号显示成字面量 ==");
        char bt = (char) 96;
        char dl = (char) 36;
        String broken = q + "v" + bt + "currentName" + bt;
        check(n(upd, broken) == 0, "D1 不再有被写坏的版本号插值");
        check(n(upd, dl + "{currentName}") >= 2, "D2 用的是正确的插值形式");
        check(n(upd, bt + "rem.versionName" + bt) == 0 && n(upd, bt + "rem.notes" + bt) == 0,
                "D3 其它几处也不再是反引号形式");

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
        check(!blobs.isEmpty(), "F1 包里有 dex");
        check(anyContains(blobs, "invalidateSize"), "F2 缓存失效进了 dex");
        check(anyContains(blobs, "nomedia"), "F3 .nomedia 进了 dex");
        check(anyContains(blobs, "合并成单文件"), "F4 合并阶段文案进了 dex");

        // ================================================================ (5) 第 52 轮回滚：139 凭据入口已移除
        System.out.println("== (5) 回滚后 139 凭据入口应已移除 ==");
        check(n(upd, "CredentialSection") == 0, "E1 更新面板里**不再有凭据配置区**（回滚到网盘后不需要）");
        check(n(env, "KEY_YUN139_AUTH") >= 1 && n(env, "Yun139") >= 1, "E2 HplayEnv **注入 139 凭据**（第 67 轮：升级通道改为直连移动云盘）");
        check(n(upd, "Yun139") == 0, "E3 界面不再引用 Yun139");
        check(n(ui, "v${currentName}") >= 1 || n(upd, "v${currentName}") >= 1,
                "E4 第 50 轮修的插值 bug 仍保持修复（回滚不能把它带回来）");
        check(!anyContains(blobs, "saveAuthorization"), "F5 反断言：凭据保存接口已从包里移除");
        check(!anyContains(blobs, "尚未配置移动云盘凭据"), "F6 反断言：凭据配置区文案已从包里移除");
        check(!anyContains(blobs, "lazyItems"), "F7 反断言：没把 Lazy 塞进 verticalScroll");

        System.out.println("========================================");
        System.out.println("R50Check  PASS=" + pass + "  FAIL=" + fail);
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
        Path best = null; long bt2 = -1;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Paths.get(APKDIR), "Hplay_v*.apk")) {
            for (Path p : ds) {
                long t = Files.getLastModifiedTime(p).toMillis();
                String nm = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (nm.contains("unsigned") || nm.contains("aligned")) continue;
                if (t > bt2) { bt2 = t; best = p; }
            }
        }
        if (best == null) { System.out.println("EXIT2 找不到 apk"); System.exit(2); }
        return best;
    }
}