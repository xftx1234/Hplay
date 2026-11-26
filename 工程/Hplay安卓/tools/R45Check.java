import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 第 45 轮离线验收器：本地下载（两板块 / 进度条 + MB·s⁻¹ + 剩余时间 / 自选保存目录 /
 * 下载完离线点播 / 介绍页与封面一并缓存 / 官方源也加下载按钮）。
 *
 * 纪律（沿用 R43Check，都是以前踩过之后定下来的）：
 *  · **不手抄生产逻辑**：MB/s 的分母、sidecar 文件名、ETA 格式串、面板形参名，全部从被审源码
 *    文本里抽出来用；抽不到就 exit(2)，不给「永不匹配的兜底模式」留活路。
 *  · 断言打在**用户可见面**上：两个板块的名字、保存目录、进度条，都要在生成源码与**包体字节**里
 *    各有一份证据（源码有、包里没有，等于没交付）。
 *  · 每条「不存在 X」都配一条「非空」，否则空输出上恒真。
 *  · 路径写死在本文件里（中文路径经 bash→Java argv 会被 ANSI 码页打成 mojibake）。
 */
public class R45Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN_APP = HPLAY + "build/gen/Han1meApp.kt";
    static final String ENGINE = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String UI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static final String PLAYER = HPLAY + "src/com/han1me/player/HplayPlayer.kt";
    static final String THUMB = HPLAY + "src/com/han1me/web/HplayThumb.kt";
    static final String MANIFEST = HPLAY + "src/AndroidManifest.xml";
    static final String APKDIR = HPLAY + "apk/";

    static int pass = 0;
    static int fail = 0;
    static final List<String> fails = new ArrayList<>();

    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    static void ok() {
        pass++;
    }

    static void no(String what) {
        fail++;
        fails.add(what);
        System.out.println("FAIL  " + what);
    }

    static void check(boolean cond, String what) {
        if (cond) ok(); else no(what);
    }

    /** 出现次数（子串，非正则）。 */
    static int n(String hay, String needle) {
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    /** 抽取，抽不到直接 exit(2)：验收器宁可炸，也不要拿着空模式给假绿灯。 */
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
        String player = read(PLAYER);
        String thumb = read(THUMB);
        String man = read(MANIFEST);

        // ---------------------------------------------------------------- A. 生成源码接线
        System.out.println("== A. 生成源码（build/gen/Han1meApp.kt）接线 ==");
        check(n(gen, "com.han1me.download.HplayDownloadsPanel(") == 1, "A1 下载页入口恰好 1 处（本地下载面板）");
        check(n(gen, "com.han1me.download.HplayDownloads.enqueue(") == 1, "A2 详情页下载按钮恰好 1 处入队");
        check(n(gen, "com.han1me.download.HplayDownloads.detailFor(") == 1, "A3 点已下载条目走 detailFor（离线介绍页）");
        check(n(gen, "var downloading by remember") == 0, "A4 桌面那对 downloading/downloadTick 已删（downloading）");
        check(n(gen, "var downloadTick by remember") == 0, "A5 桌面那对 downloading/downloadTick 已删（downloadTick）");
        check(n(gen, "X99.downloadMedia") == 0, "A6 桌面下载器 X99.downloadMedia 不再被 App 调用（非空：见 A7）");
        check(n(gen, "showDownloads -> showDownloads = false") >= 1, "A7 返回键栈里有本地下载这一级（非空）");
        // 第 47 轮在返回键栈里插入了 showUpdate（与 showDownloads 同级），
        // 所以不能再拿"紧跟 showDownloads"这个位置去断言；改成两条各自存在。
        check(n(gen, "enabled = webPage != null || showUpdate || showDownloads ||") == 1,
                "A8 返回键 enabled 含 showDownloads（第 47 轮起 showUpdate 排在它前面）");
        check(n(gen, "showUpdate -> showUpdate = false") >= 1,
                "A8b 返回键栈里也含 showUpdate（第 47 轮新增的那一级）");

        // 官方源下载按钮：判据放宽，但**只**改这一处（用户：只加按钮和功能，不影响其他任何地方）；
        // 并且要「确有在线直链」——从「本地下载」点进来的本机副本只有 file:// 源，不摆按钮。
        // 第 54 轮：该判据后面多了一行「已下载」查询，所以只匹配判据本身（不再连 DetailAction）。
        String guard = must(gen,
                "if \\(\\(fromBoard \\|\\| officialSite\\) && d\\.sources\\.any",
                "官方源下载按钮判据", 0);
        check(guard.length() > 0, "A9 下载按钮判据 = (fromBoard || officialSite) 且确有在线源");
        check(n(gen, "d.sources.any { !it.second.startsWith(\"file://\") }") == 1,
                "A10 按钮判据只出现 1 处（没有顺手改别处）");
        check(n(gen, "fromBoard && !officialSite") == 2, "A11 网页播放 + 本站收藏两支仍是原判据（未被扰动，应 2 处）");
        check(n(gen, "{ onDownload() }") == 1, "A12 下载按钮本身恰好 1 个（第 54 轮起文案随「已下载」变化）");
        // 入队挑源用的判据必须与按钮那条一致，否则会出现「按钮在、点了说没有直链」。
        check(n(gen, "val q = d.sources.firstOrNull { !it.second.startsWith(\"file://\") }") == 1,
                "A13 onDownload 只挑在线源（与 A10 同一条判据）");
        check(n(gen, "val q = d.sources.firstOrNull()") == 0, "A14 旧的无条件 firstOrNull() 挑源已消失");

        // ---------------------------------------------------------------- B. 引擎与界面的不变量
        System.out.println("== B. 引擎 / 界面不变量（数值与串都从源码抽） ==");

        // MB/s：用户点名的单位。分母从源码抽，不手抄一个 1048576 上来。
        String div = must(eng, "\"%.2f MB/s\"\\.format\\(speedBps / ([0-9.]+)\\)", "MB/s 的分母", 1);
        check(Double.parseDouble(div) == 1048576.0, "B1 速度单位是 MB/s 且按 1024² 折算（抽到分母 " + div + "）");
        check(n(eng, "MB/s") >= 1, "B2 界面文案里真的有 MB/s");

        // 剩余时间：格式串从源码抽，并核对分/秒的算术与之同源
        must(eng, "\"剩余 %02d:%02d\"\\.format\\(etaSec / 60, etaSec % 60\\)", "剩余时间格式串", 0);
        check(true, "B3 剩余时间格式 = 剩余 MM:SS，且由 etaSec/60 与 %60 喂");
        check(n(eng, "剩余 --:--") == 1, "B4 估不出来时明写「剩余 --:--」，不编一个数");

        // 进度条分母：不许假装百分比
        check(n(eng, "val determinate: Boolean") == 1 && n(ui, "t.determinate") == 1,
                "B5 进度条有「有没有真实分母」的判据并被界面使用");
        check(n(eng, "totalBytes = -1L") == 1, "B6 未知总量以 -1 起步（不编分母）");
        // 不数裸 "#EXTINF"：注释里也出现它（第 46 轮讲诱饵清单时写了两处），
        // 裸计数会随注释增删而假红。改数**代码形态**的两处。
        check(n(eng, "mediaTotalSec > 0 ->") == 1
                && n(eng, "private val extinfRe = Regex") == 1
                && n(eng, "it.startsWith(\"#EXTINF\")") == 1,
                "B7 HLS 以 #EXTINF 秒数当分母（总量未知时的真实分母）");

        // 两个板块（用户可见面）
        check(n(ui, "已下载 ") >= 1, "B8 板块「已下载」存在（第 65 轮起文案含总数）");
        check(ui.indexOf("正在下载 ") < ui.indexOf("已下载 "),
                "B8b 第 46 轮起页签顺序＝正在下载 / 已下载（按参考图）");
        check(n(ui, "TabPill(\"正在下载 ") == 1, "B9 板块「正在下载」存在（第 46 轮按参考图改名，原为「下载中」）");

        // 保存目录：读/写必须是同一个设置键，且保存前真的试写
        String keyDecl = must(eng, "KEY_DIR = \"([^\"]+)\"", "保存目录设置键", 1);
        check(n(eng, "KEY_DIR") >= 2, "B10 保存目录用同一个键（KEY_DIR = " + keyDecl + "）");
        check(n(eng, "X99.putSetting(KEY_DIR") == 1, "B11 保存目录用同一个键写");
        check(n(eng, "FileOutputStream(probe)") == 1 && n(eng, "probe.delete()") == 1,
                "B12 写探针真的写一个文件再删掉（不靠猜）");
        check(n(eng, "fun setSaveDir(dir: File): String?") == 1 && n(eng, "val why = probeWrite(dir)") == 1,
                "B13 setSaveDir 是唯一入口，且落盘前一定先过写探针");
        check(n(ui, "HplayDownloads.setSaveDir(") == 2,
                "B13b 候选目录与自定义路径都走 setSaveDir（同一道探针）");

        // 介绍页 / 封面 sidecar
        String sd = must(eng, "SIDE_DETAIL = \"([^\"]+)\"", "介绍页 sidecar 名", 1);
        String sc = must(eng, "SIDE_COVER = \"([^\"]+)\"", "封面 sidecar 名", 1);
        check(sd.equals("hplay.detail.json") && sc.equals("hplay.cover.jpg"),
                "B14 sidecar 名抽到：" + sd + " / " + sc);
        check(n(eng, "index.m3u8") >= 3,
                "B15 index.m3u8 特判齐全（sideDetail / sideCover 各 1；delete 自第 48 轮改用 itemRoot 判定条目）");
        check(n(eng, "File(f.parentFile ?: f, SIDE_DETAIL)") == 1, "B16 HLS 的介绍页写在清单同目录");
        check(n(eng, "fetchCoverCache") >= 2 && n(eng, "writeDetailCache") >= 2,
                "B17 下载收尾时两样都缓存（介绍页 + 封面）");

        check(n(eng, "File(playablePath).toURI().toString()") == 2,
                "B18 detailFor 两条路径都产出本机文件源（第 48 轮改用 toURI，兼容含空格路径）");
        check(n(eng, "if (cached != null) return cached") == 1,
                "B19 有介绍页缓存就用缓存、没有才退成最小详情（两条路都在）");
        check(n(player, "DefaultMediaSourceFactory(DefaultDataSource.Factory(context, factory))") == 1,
                "B20 播放器显式包了 DefaultDataSource（file:// 才走得通）");
        check(n(player, "DefaultMediaSourceFactory(context,") == 0,
                "B21 上一版那个不存在的两参重载已消失（曾经编译不过的那一行）");
        check(n(thumb, "removePrefix(") == 1,
                "B22 封面组件认本机副本（第 48 轮起兼容所有 file: 形式，含老 sidecar 里未转义的串）");

        // 面板与调用点的形参名必须对得上（跨文件契约）
        String sig = must(ui, "fun HplayDownloadsPanel\\(([\\s\\S]*?)\\n\\) \\{", "面板形参表", 1);
        Set<String> names = new LinkedHashSet<>();
        for (String line : sig.split("\n")) {
            Matcher m = Pattern.compile("^\\s*(on[A-Za-z]+):").matcher(line);
            if (m.find()) names.add(m.group(1));
        }
        check(names.equals(new LinkedHashSet<>(Arrays.asList("onPlay", "onClose"))),
                "B23 面板形参 = onPlay/onClose，抽到 " + names);
        check(n(gen, "onPlay = { path ->") == 1 && n(gen, "onClose = { showDownloads = false }") == 1,
                "B24 调用点按名字传参，且与 B23 同名");

        // Referer 只补原生板块：与内嵌播放器 prepare() 同一条规则，官方源一个头都不加。
        // （官方媒体域 vdownload.hembed.com 不在官方页面域表里，refererFor 只会退回自引用 Referer；
        //   而播放器实测不带 Referer 照样播 —— 下载若多塞这一个头就正好踩中「不影响官方源」。）
        check(n(player, "val nativeBoard = !localFile && com.han1me.X99.nativePlay") == 1,
                "B25 播放器那条「只有原生板块才补 Referer」的规则仍在（本条的参照物）");
        check(n(eng, "mirrorRefererFor") >= 1 && n(eng, "isOfficialHost") >= 1,
                "B26 下载器请求头三分支：原生/优选补 Referer，官方源不加（第 98 轮）");
        check(n(eng, "runCatching { X99.isNativeBoardUrl(d.video.url) }") == 1,
                "B27 native 取自详情页 URL 的同一个谓词（不按媒体域反推）");
        check(n(eng, "val native: Boolean") == 1, "B28 这个判据随任务走（Job 带 native）");
        // 第 46 轮新增 #EXT-X-MAP 初始化段，取媒体的调用从 5 处变 6 处
        // （2 清单 + 1 密钥 + 1 初始化段 + 1 分片 + 1 直下）。两条都要数，且不许有**不带开关**的裸调用。
        check(n(eng, "pump(job.id, job.native,") == 4 && n(eng, "fetchText(job.native,") == 2,
                "B29 六处取媒体全都带上这个开关（pump 4 + fetchText 2）");
        check(n(eng, "pump(job.id, ") == 4,
                "B29b 没有任何一处取媒体漏掉 native 开关（裸调用数 == 带开关数）");
        check(n(eng, "req(url)") == 0, "B30 旧的无条件补 Referer 的 req(url) 已消失（非空：见 B26/B29）");

        // ---------------------------------------------------------------- C. 交付包同源
        System.out.println("== C. 交付包（apk/*.apk 最新一份）字节同源 ==");
        Path apk = newestApk();
        System.out.println("  受审包：" + apk.getFileName());
        List<byte[]> blobs = new ArrayList<>();        try (ZipFile z = new ZipFile(apk.toFile())) {
            java.util.Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                if (en.getName().endsWith(".dex")) blobs.add(readAll(z.getInputStream(en)));
            }
        }
        check(!blobs.isEmpty(), "C1 包里有 dex（非空：后面几条才有意义）");
        check(anyContains(blobs, "com/han1me/download/HplayDownloads"), "C2 引擎对象进了 dex（类型描述符）");
        check(anyContains(blobs, "HplayDownloadsPanel"), "C3 面板方法名进了 dex");
        check(anyContains(blobs, "清空记录"), "C4 新面板的用户可见文案进了 dex（清空记录）");
        check(anyContains(blobs, "保存目录"), "C5 新面板的用户可见文案进了 dex（保存目录）");
        check(anyContains(blobs, "剩余 %02d:%02d"), "C6 剩余时间格式串进了 dex");
        check(anyContains(blobs, "%.2f MB/s"), "C7 速度单位 MB/s 进了 dex");
        check(anyContains(blobs, "DefaultDataSource"), "C8 本机文件播放机制（DefaultDataSource）在包里");

        check(n(man, "android.permission.WRITE_EXTERNAL_STORAGE") == 1
                && n(man, "android:maxSdkVersion=\"28\"") == 1,
                "C9 清单里给公共目录留了 ≤API28 的写权限（10+ 由探针如实报错）");

        System.out.println("========================================");
        System.out.println("R45Check  PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            for (String f : fails) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
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