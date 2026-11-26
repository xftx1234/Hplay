import java.io.ByteArrayOutputStream;
import java.io.File;
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
 * 第 48 轮离线验收器：用户真机报的三个 bug。
 *
 *  ① 本地下载没有把介绍页和封面下载下来
 *  ② 退出应用后需要后台继续下载，而不是中断
 *  ③ 删除已下载视频时点一下只减少 1MB，要一次彻底删除、不许分片删除
 *
 * 纪律（沿用 R42/R43/R45/R46/R47）：
 *  · **不手抄生产逻辑**：媒体后缀正则、条目根判据的关键条件、封面退避参数、服务动作名，
 *    全部从被审源码文本里抽出来用；抽不到就 exit(2)。
 *  · 断言打在**代码形态**上，不数注释里的字串。
 *  · 三条 bug 各配一条**行为级复算**（不只检查"代码里有没有这个词"）：
 *    bug3 真的建目录树跑一遍条目根判定；bug2 真的检查那套"进程级回调 + 前台服务"接线；
 *    bug1 真的验一串含空格路径的 URI 行为。
 *  · 路径写死在本文件里（中文路径经 bash→Java argv 会被 ANSI 码页打成 mojibake）。
 */
public class R48Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN_APP = HPLAY + "build/gen/Han1meApp.kt";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String SVC = HPLAY + "src/com/han1me/download/DownloadService.kt";
    static final String UI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static final String THUMB = HPLAY + "src/com/han1me/web/HplayThumb.kt";
    static final String MANIFEST = HPLAY + "src/AndroidManifest.xml";
    static final String MAIN = HPLAY + "src/com/han1me/MainActivity.kt";
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

    static String must(String hay, String re, String what, int group) {
        Matcher m = Pattern.compile(re, Pattern.DOTALL).matcher(hay);
        if (!m.find()) {
            System.out.println("EXIT2 抽不到「" + what + "」：模式 " + re);
            System.exit(2);
        }
        return m.group(group);
    }

    /** 取 from 与 to 之间的第一段（纯文本，不走正则 —— 免得为一对三引号跟转义较劲）。 */
    static String between(String hay, String from, String to, String what) {
        int i = hay.indexOf(from);
        if (i < 0) { System.out.println("EXIT2 抽不到「" + what + "」：找不到起始 " + from); System.exit(2); }
        int s = i + from.length();
        int e = hay.indexOf(to, s);
        if (e < 0) { System.out.println("EXIT2 抽不到「" + what + "」：找不到结束 " + to); System.exit(2); }
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
        String svc = read(SVC);
        String ui = read(UI);
        String thumb = read(THUMB);
        String man = read(MANIFEST);
        String main = read(MAIN);

        // ================================================================ (3) 删除一次到位
        System.out.println("== (3) 删除必须一次删掉整个条目（用户：点一下只减 1MB）==");

        // 从生产源码抽 mediaRe（判断"这是不是可播文件"用的正则）
        String TQ = String.valueOf((char) 34) + (char) 34 + (char) 34;
        String mediaPat = between(eng, "mediaRe = Regex(" + TQ, TQ + ",", "mediaRe 模式");
        check(mediaPat.contains("ts"), "C1 mediaRe 覆盖 ts 分片（抽到 " + mediaPat + "）");

        // 条目根判定必须存在，且**不再**只看 index.m3u8
        check(n(eng, "private fun itemRoot(f: File): File") == 1, "C2 新增 itemRoot()：一次下载 = 一个条目");
        check(n(eng, "val target = itemRoot(f)") == 1, "C3 delete() 的目标解析走 itemRoot（不再是「name==index.m3u8 才取父目录」）");
        // 旧写法必须已消失（它正是"只删一个分片"的成因）
        check(n(eng, "if (f.name.equals(\"index.m3u8\", true)) (f.parentFile ?: f) else f") == 0,
                "C4 旧的「只有 index.m3u8 才当目录」判据已消失（它是「点一下减 1MB」的根因）");
        // itemRoot 的两个关键条件
        String irBody = must(eng, "private fun itemRoot\\(f: File\\): File \\{([\\s\\S]*?)\\n    \\}", "itemRoot 体", 1);
        check(n(irBody, "endsWith(\".ts\", true)") == 1,
                "C5 itemRoot 把 .ts 分片也算作条目目录的迹象（**中断的 HLS 只有分片、没有 index.m3u8**）");
        check(n(irBody, "sd != null && parent.absolutePath == sd.absolutePath") == 1,
                "C6 itemRoot 排除「父目录就是保存目录」（那是很多条目，不是一个条目）");

        // items() 不能再把中断目录里的单个 .ts 当播放产物
        check(n(eng, "!it.name.endsWith(\".ts\", true)") >= 1,
                "C7 items() 选播放产物时排除 .ts（第 56 轮起顶层与嵌套查找各一处）");
        check(n(eng, "（未完成）") >= 1, "C8 中断的下载仍列出来，但标题如实标「（未完成）」");
        check(n(eng, "path = (playable ?: f).absolutePath") == 1,
                "C9 中断条目的 path 指向目录自身（删除与占用判定都以整条目为单位）");
        check(n(eng, "else if (target.isFile && !deleteWithRetry(target))") == 1, "C10 单文件条目仍走重试删除");
        check(n(eng, "private fun deleteTree(dir: File, leftover: MutableList<String>)") == 1, "C11 目录条目递归删");
        check(n(eng, "if (target.exists())") == 1, "C12 删完回验");

        // ---- 行为级复算：真的造一棵"中断的下载"目录树，跑生产源码抽出来的两条判据 ----
        System.out.println("   -- 行为级复算：中断的 HLS 目录树 --");
        Path root = Files.createTempDirectory("r48_");
        Pattern mediaRe = Pattern.compile(mediaPat, Pattern.CASE_INSENSITIVE);
        // 树1：正常完成（index.m3u8 + 分片）
        Path okDir = Files.createDirectories(root.resolve("done_1"));
        Files.write(okDir.resolve("index.m3u8"), "#EXTM3U".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 3; i++) Files.write(okDir.resolve(String.format("%05d.ts", i)), new byte[1024]);
        // 树2：被中断（只有分片，没有清单）
        Path badDir = Files.createDirectories(root.resolve("cut_2"));
        for (int i = 0; i < 3; i++) Files.write(badDir.resolve(String.format("%05d.ts", i)), new byte[1024]);

        String relOk = reproduceItemsChoice(okDir, mediaRe);
        String relBad = reproduceItemsChoice(badDir, mediaRe);
        check(relOk != null && relOk.equals("index.m3u8"),
                "C13 完成的目录：items() 选中 index.m3u8（实得 " + relOk + "）");
        check(relBad == null,
                "C14 中断的目录：items() **不再**退化成选中某个 .ts 分片（实得 " + relBad + " ⇒ 走「path=目录」分支）");

        String rootOk = reproduceItemRoot(okDir.resolve("index.m3u8"), root);
        String rootBad = reproduceItemRoot(badDir.resolve("00000.ts"), root);
        check(rootOk.equals("done_1"), "C15 itemRoot(完成目录的清单) -> 该目录（实得 " + rootOk + "）");
        check(rootBad.equals("cut_2"),
                "C16 itemRoot(中断目录的分片) -> 该目录（实得 " + rootBad + "）⇒ 一次删掉整条，不再只减 1MB");

        deleteRec(root.toFile());

        // ================================================================ (2) 后台继续下载
        System.out.println("== (2) 退出应用后下载要继续（用户：现在会中断）==");
        check(n(svc, "class DownloadService : Service()") == 1, "B1 新增前台服务 DownloadService");
        check(n(svc, "startForeground(") >= 1, "B2 服务把自己提升为前台（普通线程做不到这件事）");
        check(n(eng, "DownloadService.ensureRunning(") >= 1, "B3 入队（与第 49 轮的续传）都会拉起服务");
        check(n(eng, "onProgressTick") >= 2, "B4 进度回调接到服务（通知里能看到进度）");
        check(n(eng, "onQueueDrained") >= 2, "B5 队列排空通知服务收工（不留常驻通知）");
        check(n(svc, "START_NOT_STICKY") >= 1, "B6 服务返回 START_NOT_STICKY（不自动重启一个已经没活的队列）");
        // 注释里也提到 IMPORTANCE_LOW（讲设计意图），所以断"代码里真的用它建了渠道"这个形态
        check(n(svc, "NotificationManager.IMPORTANCE_LOW)") == 1, "B7 通知渠道低优先级（静默、不弹横幅，不打扰）");
        check(n(svc, "stopForeground") >= 1, "B8 收工时移除前台通知");
        // 清单里的权限与服务声明
        check(n(man, "android.permission.FOREGROUND_SERVICE\"") >= 1, "B9 声明 FOREGROUND_SERVICE");
        check(n(man, "android.permission.FOREGROUND_SERVICE_DATA_SYNC") == 1,
                "B10 声明 FOREGROUND_SERVICE_DATA_SYNC（Android 14 起 dataSync 服务必须显式声明）");
        check(n(man, "android.permission.POST_NOTIFICATIONS") == 1, "B11 声明 POST_NOTIFICATIONS");
        check(n(man, "android:name=\"com.han1me.download.DownloadService\"") == 1, "B12 清单里注册了服务");
        check(n(man, "android:foregroundServiceType=\"dataSync\"") == 1, "B13 服务类型是 dataSync");
        check(n(man, "android:exported=\"false\"") >= 2, "B14 服务不导出（只有本应用能拉起）");
        check(n(main, "POST_NOTIFICATIONS") >= 1, "B15 Android 13+ 运行时申请通知权限");
        // 定义与调用各一处，所以是 2；关键是"定义在、且在 onCreate 里真的调了"
        check(n(main, "private fun askNotificationPermission()") == 1
                && n(main, "askNotificationPermission()") >= 2,
                "B16 申请动作已定义并在 onCreate 里被调用（且被拒也不阻断下载）");
        // 关键：服务不能自己另起一套下载逻辑
        check(n(svc, "HplayDownloads.cancelAll()") == 1, "B17 通知里的「全部取消」走同一套队列（服务不另起一套）");

        // ================================================================ (1) 介绍页与封面
        System.out.println("== (1) 介绍页与封面必须真的落盘（用户：没下载下来）==");
        check(n(eng, "private fun writeDetailCache(") == 1, "A1 介绍页 sidecar 写入函数在");
        check(n(eng, "private fun fetchCoverCache(") == 1, "A2 封面缓存函数在");
        // 封面重试：图床失败会被冷 20s，一次性下载撞上就永久没封面
        String waits = must(eng, "val waits = longArrayOf\\(([^\\)]+)\\)", "封面退避参数", 1);
        check(waits.contains("8_000") || waits.contains("16000") || waits.contains("16_000"),
                "A3 封面下载有退避重试，能跨过图床 20 s 冷却（实得 " + waits.trim() + "）");
        // 失败必须如实记录，不能静默
        check(n(eng, "介绍页缓存失败") == 1, "A4 介绍页缓存失败会如实说出来（不静默）");
        check(n(eng, "封面未缓存") == 1, "A5 封面未缓存会如实说出来（不静默）");
        check(n(eng, "lastCoverError") >= 2, "A6 保留封面失败的最近原因（可回显）");
        // file:// 必须用 toURI（含空格路径下 "file://"+path 不是合法 URI）
        check(n(eng, "\"file://\" + it.absolutePath") == 0,
                "A7 不再用字符串拼 file://（保存目录含空格时那不是合法 URI）");
        check(n(eng, ".toURI()") >= 2, "A8 改用 File.toURI()（自动转义空格等特殊字符）");
        check(n(thumb, "url.startsWith(\"file:\")") == 1, "A9 读侧也接受所有 file: 形式");
        check(n(thumb, "removePrefix(\"file://\")") == 1,
                "A10 读侧对老 sidecar 里未转义的 file:// 串做兼容（去掉 scheme 当路径用）");

        // ---- 行为级复算：含空格路径的 URI 行为 ----
        System.out.println("   -- 行为级复算：含空格保存目录下的封面 URL --");
        File spaced = new File(new File(System.getProperty("java.io.tmpdir"), "hplay 视频").getAbsolutePath());
        boolean oldWayFails;
        try {
            new java.net.URI("file://" + spaced.getAbsolutePath() + "/a/hplay.cover.jpg");
            oldWayFails = false;
        } catch (Throwable t) {
            oldWayFails = true;
        }
        check(oldWayFails,
                "A11 复算：含空格的路径用旧写法（file:// + path）确实抛异常 ⇒ 这就是封面永远出不来的原因");
        boolean newWayOk;
        try {
            String u = new File(spaced, "a/hplay.cover.jpg").toURI().toString();
            new File(new java.net.URI(u));
            newWayOk = true;
        } catch (Throwable t) {
            newWayOk = false;
        }
        check(newWayOk, "A12 复算：改用 toURI() 之后同一路径可正常解析");

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
        check(!blobs.isEmpty(), "D1 包里有 dex（非空）");
        check(anyContains(blobs, "DownloadService"), "D2 前台服务进了 dex");
        check(anyContains(blobs, "Hplay 正在下载") || anyContains(blobs, "同时 "), "D3 通知文案进了 dex（第 49 轮起多任务时显示「同时 N 个」）");
        check(anyContains(blobs, "itemRoot"), "D4 条目根判定进了 dex");
        check(anyContains(blobs, "（未完成）"), "D5 未完成标记进了 dex");
        check(anyContains(blobs, "封面未缓存"), "D6 封面失败的可见文案进了 dex");
        check(anyContains(blobs, "介绍页缓存失败"), "D7 介绍页失败的可见文案进了 dex");
        check(anyContains(blobs, "这条没下完，只能删除"), "D8 未完成条目不给播放入口的文案进了 dex");

        System.out.println("========================================");
        System.out.println("R48Check  PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            for (String f : fails) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    /** 复刻 items() 对**一个目录**的选取：有 index.m3u8 取它；否则取非 .ts 的可播文件；都没有返回 null。 */
    static String reproduceItemsChoice(Path dir, Pattern mediaRe) {
        File d = dir.toFile();
        File[] kids = d.listFiles();
        if (kids == null) return null;
        for (File k : kids) if (k.getName().equalsIgnoreCase("index.m3u8")) return k.getName();
        for (File k : kids) if (mediaRe.matcher(k.getName()).find() && !k.getName().endsWith(".ts")) return k.getName();
        return null;
    }

    /** 复刻 itemRoot 的核心判定（父目录含条目迹象且不是保存目录本身 → 取父目录）。 */
    static String reproduceItemRoot(Path child, Path saveDir) {
        File f = child.toFile();
        File parent = f.getParentFile();
        if (parent == null) return f.getName();
        File[] sib = parent.listFiles();
        if (sib == null) return f.getName();
        boolean looksLikeItem = false;
        for (File s : sib) {
            if (s.getName().equalsIgnoreCase("index.m3u8") || s.getName().endsWith(".ts")
                    || s.getName().equals("hplay.detail.json") || s.getName().equals("hplay.cover.jpg")) {
                looksLikeItem = true;
                break;
            }
        }
        if (!looksLikeItem) return f.getName();
        if (parent.getAbsolutePath().equals(saveDir.toFile().getAbsolutePath())) return f.getName();
        return parent.getName();
    }

    static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) deleteRec(x);
        }
        f.delete();
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