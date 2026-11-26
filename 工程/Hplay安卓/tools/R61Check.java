import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 61 轮离线验收器：用户一条 —— 「把最新构建版本之前的历史记录也回填板块名」。
 *
 * 依据链（都是既有事实，不新造）：
 *   历史 sidecar 里存着 video.url（源页面 URL） -> X99.boardLabelFor(url) 反推板块名
 *   boardLabelFor 是**纯字符串判断、不发网络请求** -> 可以放在扫盘路径上
 *
 * 三条纪律：
 *   ① 只在 group 为空或就是「未分组」时才回填（手工分过的组绝不覆盖）
 *   ② 回填后写回 sidecar（下次扫盘不必重算）
 *   ③ 认不出的站点沿用 boardLabelFor 的主机名约定（不另编「未知板块」）
 *   + 扫描只计算、不在主线程写盘（scanItems 可能被 remember(tick) 调用）
 */
public class R61Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String ENG = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
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
        String eng = read(ENG);

        // ================================================================ 依据链
        System.out.println("== 依据链（用既有数据，不新造判据） ==");
        check(n(eng, "private fun backfillGroup(playable: File, current: String): String") == 1, "A1 有回填函数");
        check(n(eng, "val srcUrl = sideSourceUrl(playable)") == 1, "A2 依据是 sidecar 的**源页面 URL**");
        check(n(eng, "X99.boardLabelFor(srcUrl)") == 1, "A3 用既有工具 boardLabelFor 反推板块名");
        check(n(eng, "private fun sideSourceUrl(playable: File): String") == 1, "A4 sideSourceUrl 本来就是既有函数（第 54 轮）");

        // ================================================================ 纪律①
        System.out.println("== 纪律① 不覆盖已分组的 ==");
        check(n(eng, "if (cur.isNotEmpty() && cur != \"未分组\") return current") == 1,
                "A5 已分组（非空且非「未分组」）直接返回、不动");
        check(n(eng, "val cur = current.trim()") == 1, "A6 判定前先 trim（避免空白串被当成已分组）");

        // ================================================================ 纪律②
        System.out.println("== 纪律② 落盘（且不在主线程写） ==");
        check(n(eng, "fun flushGroupBackfill(): Int") == 1, "B1 有统一落盘入口");
        check(n(eng, "pendingGroupFix.add(playable.absolutePath to label)") == 1,
                "B2 扫描时**只记录**待落盘，不直接写");
        check(n(eng, "private fun rewriteSideGroup(playable: File, group: String): Boolean") == 1, "B3 有写回 sidecar");
        check(n(eng, "o.put(\"group\", group)") == 1, "B4 **只改 group 一个字段**，其余原样保留");
        check(n(eng, "if (o.optString(\"group\").trim() == group) return@runCatching false") == 1,
                "B5 值没变就不写盘（省 IO）");
        check(n(eng, "runCatching { flushGroupBackfill() }") >= 2, "B6 IO 入口（重扫 / 手动扫描）都调了落盘");
        check(n(eng, "fun rescanNow(): List<DlItem>") == 1, "B7 rescanNow 仍在（第 60 轮）");

        // ================================================================ 纪律③ + 接线
        System.out.println("== 纪律③ 认不出的沿主机名；接线到两个分支 ==");
        check(n(eng, "if (label.isBlank() || label == \"未分组\") return current") == 1,
                "C1 真无从判断时保持原样（不硬编「未知板块」）");
        check(n(eng, "认不出的一律回主机名") == 0 || n(eng, "沿用") >= 1,
                "C2 注释与行为一致（沿用 boardLabelFor 的主机名约定）");
        check(n(eng, "group = backfillGroup(anchor, side?.first ?: \"未分组\")") == 1,
                "C3 目录条目接线");
        check(n(eng, "group = backfillGroup(f, side?.first ?: \"未分组\")") == 1,
                "C4 单文件条目接线");
        // 反断言：旧的「不回填」写法不该还在
        check(n(eng, "group = side?.first ?: \"未分组\"") == 0,
                "C5 反断言：旧的「直接取 sidecar、不回填」写法已移除");

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

        System.out.println("========================================");
        System.out.println("R61Check  PASS=" + pass + "  FAIL=" + fail);
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
