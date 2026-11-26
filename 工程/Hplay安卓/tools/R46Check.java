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
 * 第 46 轮离线验收器：用户真机报的四个 bug。
 *
 *  ① 下下来的视频只有几秒   -> 诱饵清单必须在**下载之前**被挡住
 *  ② 删除要删好几次         -> 删除必须递归 + 重试 + 回验，且删前停掉在写的任务
 *  ③ 本地下载页没还原参考UI -> 参考图的可见元素逐条要在源码与包体里各有一份证据
 *  ④ 下载要能提速可选多线程 -> 分片并发、线程池、连接池放得开、计数原子
 *
 * 纪律（沿用 R42/R43/R45，都是以前踩过之后定下来的）：
 *  · **不手抄生产逻辑**：诱饵判据、段数阈值、并发上下界、线程池大小，全部从被审源码文本里
 *    抽出来用；抽不到就 exit(2)，不给「永不匹配的兜底模式」留活路。
 *  · 断言打在**用户可见面**上：文案与行为都要在生成源码与**包体字节**里各有一份证据。
 *  · 每条「不存在 X」都配一条「非空」，否则空输出上恒真。
 *  · 路径写死在本文件里（中文路径经 bash→Java argv 会被 ANSI 码页打成 mojibake）。
 */
public class R46Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN_APP = HPLAY + "build/gen/Han1meApp.kt";
    static final String ENGINE = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String UI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static final String ENV = HPLAY + "src/com/han1me/HplayEnv.kt";
    static final String MAIN = HPLAY + "src/com/han1me/MainActivity.kt";
    static final String MANIFEST = HPLAY + "src/AndroidManifest.xml";
    static final String FILEPATHS = HPLAY + "src/res/xml/file_paths.xml";
    static final String APKDIR = HPLAY + "apk/";
    /** 真样本：诱饵清单与真清单都在 临时/ 下，是前几轮真机/联网抓的原始字节。 */
    static final String TMP = WS + "临时/";

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
        String eng = read(ENGINE);
        String ui = read(UI);
        String env = read(ENV);
        String main = read(MAIN);
        String man = read(MANIFEST);
        String fp = read(FILEPATHS);

        // ================================================================ (1) 诱饵清单
        System.out.println("== (1) 只有几秒的根因：诱饵清单必须在下载之前被挡住 ==");

        String decoy = must(eng, "DECOY_SEG = \"([^\"]+)\"", "诱饵分片名常量", 1);
        check(decoy.equals("tip.ts"), "A1 诱饵分片名抽到 tip.ts（实测 Jable/LissAV 那族的 143 B 假清单）");
        check(n(eng, "internal fun playlistLooksReal(text: String): Boolean") == 1,
                "A2 判真函数 playlistLooksReal 存在且是 internal（可被验收器抽用）");
        String body = must(eng, "internal fun playlistLooksReal\\(text: String\\): Boolean \\{([\\s\\S]*?)\\n    \\}",
                "判真函数体", 1);
        check(n(body, "!text.contains(\"#EXTM3U\")") == 1, "A3 判真：没有 #EXTM3U 直接假");
        check(n(body, "text.contains(DECOY_SEG)") == 1, "A4 判真：含诱饵分片名直接假");
        check(n(body, "text.contains(\"#EXT-X-STREAM-INF\")") == 1, "A5 判真：多档 master 判真（不能被段数卡死）");
        String thr = must(body, "segs > ([0-9]+)", "真清单的最小段数阈值", 1);
        check(Integer.parseInt(thr) == 2, "A6 判真：非 master 时要求段数 > " + thr + "（抽到阈值 " + thr + "）");
        check(n(body, "it.isNotBlank() && !it.startsWith(\"#\")") == 1,
                "A7 段数只数非注释非空行（与 X99.getPlaylist 同口径）");

        // 注意：不能用 "\{([\s\S]*?)\}" 去抽分支体 —— 分支里还有 count { } 的闭合括号，
        // 非贪婪匹配会停在**那个** } 上，于是拿到的窗口里既没有 throw 也没有文案（假 FAIL）。
        // 改成取「闸门 -> downloadHls」这一段的窗口，锚点是两个都在源码里的串。
        String gate = must(eng, "if \\(!playlistLooksReal\\(text\\)\\) \\{([\\s\\S]*?)return downloadHls",
                "闸门分支体（到 downloadHls 为止）", 1);
        check(n(gate, "throw IllegalStateException") == 1, "A8 判假就抛错（不写「看起来下好了」的短文件）");
        check(n(gate, "诱饵清单") == 1, "A9 抛错文案说清是诱饵清单（用户能看到原因）");
        check(n(eng, "return downloadHls(job, eff, text)") == 1, "A10 判真才进 downloadHls（非空：见 A8）");
        int gateAt = eng.indexOf("if (!playlistLooksReal(text))");
        int hlsAt = eng.indexOf("return downloadHls(job, eff, text)");
        check(gateAt > 0 && hlsAt > gateAt, "A11 闸门位置在 downloadHls 之前（不是下完再判）");

        String x99 = read(WS + "工程/AvPlay/独立版/src/com/han1me/X99.kt");
        check(n(x99, "text.contains(\"tip.ts\")") == 1 && n(x99, "segs > 2") == 1,
                "A12 解析器 X99.getPlaylist 用的就是同一条判据（tip.ts + segs > 2）");

        System.out.println("   -- 真样本回归（判据从源码抽，样本是真抓的字节）--");
        String[] decoys = {
                TMP + "r35_familyB/jable666.com__DECOY_no_referer.m3u8",
                TMP + "r35_familyB/lissav__media_no_referer.m3u8",
        };
        int decoySeen = 0;
        for (String p : decoys) {
            if (!Files.isRegularFile(Paths.get(p))) continue;
            String t = read(p);
            check(!looksReal(t, decoy, Integer.parseInt(thr)), "A13 真诱饵样本被判假：" + Paths.get(p).getFileName());
            decoySeen++;
        }
        check(decoySeen > 0, "A14 至少见到一份真诱饵样本（非空，否则上面那条恒真）");
        String[] reals = {
                TMP + "r35_familyB/jable666.com__MASTER_signed.m3u8",
                TMP + "r40_rou_final.m3u8",
        };
        int realSeen = 0;
        for (String p : reals) {
            if (!Files.isRegularFile(Paths.get(p))) continue;
            String t = read(p);
            check(looksReal(t, decoy, Integer.parseInt(thr)), "A15 真清单样本被判真：" + Paths.get(p).getFileName());
            realSeen++;
        }
        check(realSeen > 0, "A16 至少见到一份真清单样本（非空）");

        // ================================================================ (2) 删除一次到位
        System.out.println("== (2) 删除要一次到位（递归 + 重试 + 回验） ==");
        check(n(eng, "fun delete(path: String): String?") == 1, "B1 delete 返回可诊断的结果（不是 Unit）");
        check(n(eng, "target.deleteRecursively()") == 0, "B2 旧的一发 deleteRecursively 已消失（它会删一半就返回 false）");
        check(n(eng, "private fun deleteTree(dir: File, leftover: MutableList<String>)") == 1, "B3 自己递归删");
        check(n(eng, "private fun deleteWithRetry(f: File): Boolean") == 1, "B4 逐项重试");
        String tries = must(eng, "DELETE_TRIES = ([0-9]+)", "重试次数常量", 1);
        check(Integer.parseInt(tries) >= 2, "B5 重试次数 >= 2（抽到 " + tries + "）");
        check(n(eng, "if (target.exists())") == 1, "B6 删完回验目标是否真的不存在");
        check(n(eng, "删除未完成") == 1, "B7 回验失败要把原因说出来（不静默）");
        check(n(eng, "private fun stopTasksWriting(target: File): Boolean") == 1, "B8 删前先停掉正在写这一条的任务");
        check(n(eng, "t.dest.startsWith(prefix + File.separator)") == 1, "B9 按目标路径前缀匹配在写的任务");
        check(n(ui, "onDelete = { p -> confirmDelete = listOf(p) }") == 1, "B10 单条删除先走确认弹窗（第 53 轮）");
        check(n(ui, "msg = if (bad.isEmpty())") >= 1, "B11 删除结果回显到界面");
        check(n(ui, "onDeletePicked") >= 2, "B12 多选批量删除也在（非空）");

        // ================================================================ (3) 参考 UI 还原
        System.out.println("== (3) 本地下载页还原参考 UI ==");
        check(n(ui, "Text(\"下载\", color = Color.White, fontSize = 18.sp") == 1, "C1 标题是「下载」（不是「本地下载」）");
        check(n(ui, "TabPill(\"正在下载 ") == 1, "C2 页签「正在下载」");
        check(n(ui, "已下载 ") >= 1, "C3 页签「已下载」（第 65 轮起文案含总数）");
        check(ui.indexOf("正在下载 ") < ui.indexOf("已下载 "), "C4 页签顺序＝正在下载 / 已下载（与参考图一致）");
        check(n(ui, "byGroup") >= 1, "C5 分组头（第 60 轮起按板块名遍历，不再是写死的「未分组」）");
        // 注释里也出现「部影片」（参考图说明），所以断言打在**代码形态**上。
        check(n(ui, "${gItems.size}部影片") == 1, "C6 分组头「N部影片」由该组真实条数渲染");
        check(n(ui, "\"收起\"") == 1, "C7 红色「收起」键（可折叠）");
        check(n(ui, "\"外部播放器\"") == 1, "C8 底排键「外部播放器」");
        check(n(ui, "\"本地观看\"") == 1, "C9 底排键「本地观看」");
        check(n(ui, "\"删除\"") >= 1, "C10 底排键「删除」");
        // "IconChip(" 也匹配函数**定义**那一行，所以调用点数 = 出现次数 - 定义次数。
        int chips = n(ui, "IconChip(") - n(ui, "private fun IconChip(");
        check(chips >= 5, "C11 标题行图标键（抽到 " + chips + " 个；第 56 轮新增手动扫描）");
        check(n(ui, "IconChip(\"🔍\")") == 1, "C12 搜索键");
        check(n(ui, "IconChip(\"☰\")") == 1, "C13 筛选/设置键（并发度入口）");
        check(n(ui, "IconChip(\"＋\")") == 1, "C14 新建键（保存目录入口）");
        check(n(ui, "IconChip(\"⬇\")") == 1, "C15 下载键");
        String coverW = must(ui, "size\\(width = ([0-9]+)\\.dp, height = ([0-9]+)\\.dp\\)", "封面尺寸", 1);
        String coverH = must(ui, "size\\(width = [0-9]+\\.dp, height = ([0-9]+)\\.dp\\)", "封面尺寸高", 1);
        check(Integer.parseInt(coverW) >= 90 && Integer.parseInt(coverH) >= 120,
                "C16 封面是大图（" + coverW + "x" + coverH + "，参考图里封面是主体）");
        check(n(ui, "size(width = 46.dp, height = 30.dp)") == 0, "C17 上一版 46x30 的小封面已消失");
        check(n(ui, "0xFF8A6D1F") == 1, "C18 质量胶囊的橄榄色底在（参考图那个小块）");
        check(n(ui, "\"P | \"") == 1, "C19 卡片上「720P | 257.0 MiB」形态的体积行（代码字面量）");
        check(n(ui, "yyyy-MM-dd HH:mm") == 1, "C20 卡片上的时间格式（参考图 2026-09-28 18:46）");
        check(n(ui, "▶ 播放") == 0, "C21 上一版的「播放 / 叉」两键已换成底排三键");

        check(n(eng, "o.put(\"quality\", quality)") == 1, "C22 下载时把质量档写进 sidecar");
        check(n(eng, "o.put(\"group\", group.ifBlank { \"未分组\" })") == 1, "C23 下载时把分组写进 sidecar");
        check(n(eng, "o.optInt(") >= 1, "C24 扫描时把质量档读回来");
        check(n(eng, "data class DlItem(") == 1 && n(eng, "val quality: Int = 0") == 1, "C25 DlItem 带上质量档");

        // ================================================================ (4) 多线程提速
        System.out.println("== (4) 下载提速：分片并发（可选内置多线程） ==");
        check(n(eng, "Executors.newFixedThreadPool(n)") == 1, "D1 分片用固定线程池并发下（不是串行 for）");
        String conc = must(eng, "CONC_DEFAULT = ([0-9]+)", "并发默认值", 1);
        check(n(eng, "v.coerceIn(1, 16)") == 1 || n(eng, "coerceIn(1, 16)") >= 1,
                "D3 并发上下界收在 1..16（第 120 轮实测驱动；仍走 coerceIn）");
        check(n(eng, "fun concurrency(): Int") == 1 && n(eng, "fun setConcurrency(n: Int)") == 1,
                "D4 并发度可读可写（用户可自选）");
        check(n(eng, "X99.putSetting(KEY_CONC") == 1, "D5 并发度落盘（与其它设置同一个 properties）");
        check(n(ui, "HplayDownloads.setConcurrency(n)") == 1, "D6 界面能改并发度");
        check(n(ui, "(1..8)") == 1, "D7 界面给出 1..8 的选择");
        String mph = must(eng, "maxRequestsPerHost = ([0-9]+)", "每主机并发上限", 1);
        check(Integer.parseInt(mph) >= 8, "D8 OkHttp 每主机并发抬到 " + mph + "（默认 5 会把并发卡回去）");
        check(n(eng, "AtomicLong") >= 1 && n(eng, "prog.done.addAndGet(d)") >= 1, "D9 字节计数用原子加");
        check(n(eng, "segDoneMillis.addAndGet") == 1, "D10 媒体秒数进度用原子加");
        check(n(eng, "ConcurrentHashMap<Int, Double>") == 1, "D11 在途段进度用并发容器（并发下进度才连续）");
        check(n(eng, "AtomicReference<Throwable?>") == 1, "D12 并发里第一个失败要被收住并抛出（不静默吞）");

        // ================================================================ (5) 外部播放器
        System.out.println("== (5) 外部播放器（file:// 在 Android 7+ 会被拒，必须走 content://） ==");
        check(n(env, "fun playExternalFile(path: String): Boolean") == 1, "E1 HplayEnv 提供本机文件播放");
        check(n(env, "FileProvider.getUriForFile(") >= 1, "E2 走 FileProvider 取 content://");
        check(n(env, "Intent.FLAG_GRANT_READ_URI_PERMISSION") >= 1, "E3 只对发出的这一个 URI 授临时读权限");
        check(n(main, "HplayDownloads.onPlayExternal = { path ->") == 1, "E4 MainActivity 注册了外部播放回调");
        check(n(main, "HplayEnv.toast(") == 1, "E5 调不起来要明确提示（不点了没反应）");
        check(n(man, "androidx.core.content.FileProvider") == 1, "E6 清单里声明了 FileProvider");
        check(n(man, "android:authorities=\"com.han1me.app.fileprovider\"") == 1, "E7 authority 与代码里用的同一个");
        check(n(man, "android:exported=\"false\"") >= 1, "E8 provider 不导出");
        check(n(man, "android:grantUriPermissions=\"true\"") == 1, "E9 provider 允许按次授权");
        check(n(man, "@xml/file_paths") == 1 && n(fp, "<root-path") == 1,
                "E10 路径配置存在且覆盖用户自选目录（root-path，保存目录是用户自选的）");
        check(n(eng, "onPlayExternal") == 1, "E11 引擎侧只有一个外部播放回调（无死代码）");
        check(n(eng, "onPlayLocal") == 0, "E12 未使用的 onPlayLocal 已删（不留死代码）");

        // ================================================================ (6) 交付包同源
        System.out.println("== (6) 交付包（apk/Hplay_v*.apk 最新一份）字节同源 ==");
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
        check(!blobs.isEmpty(), "F1 包里有 dex（非空：后面几条才有意义）");
        check(anyContains(blobs, "playlistLooksReal"), "F2 诱饵判真函数进了 dex");
        check(anyContains(blobs, "tip.ts"), "F3 诱饵分片名进了 dex");
        check(anyContains(blobs, "诱饵清单"), "F4 拒绝诱饵的用户可见文案进了 dex");
        check(anyContains(blobs, "deleteTree"), "F5 递归删除进了 dex");
        check(anyContains(blobs, "删除未完成"), "F6 删除失败的可见文案进了 dex");
        check(anyContains(blobs, "未分组"), "F7 分组头文案进了 dex");
        check(anyContains(blobs, "外部播放器"), "F8 外部播放器文案进了 dex");
        check(anyContains(blobs, "本地观看"), "F9 本地观看文案进了 dex");
        check(anyContains(blobs, "下载线程数"), "F10 并发度设置文案进了 dex");
        check(anyContains(blobs, "FileProvider"), "F11 FileProvider 在包里");
        check(anyContains(blobs, "fileprovider"), "F12 authority 进了包");
        check(!anyContains(blobs, "▶ 播放"), "F13 上一版「▶ 播放」按钮文案已不在包里（反向断言）");

        System.out.println("========================================");
        System.out.println("R46Check  PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            for (String f : fails) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    /**
     * 判真逻辑的**独立复算**：与生产代码同口径，但按抽出来的字面量实现，
     * 所以它验证的是"抽出来的那三件事"在真样本上的行为，而不是复制一份生产代码。
     */
    static boolean looksReal(String text, String decoySeg, int minSegs) {
        if (!text.contains("#EXTM3U")) return false;
        if (text.contains(decoySeg)) return false;
        if (text.contains("#EXT-X-STREAM-INF")) return true;
        int segs = 0;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) segs++;
        }
        return segs > minSegs;
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