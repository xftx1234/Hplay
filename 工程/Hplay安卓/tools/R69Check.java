import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 69 轮离线验收器：**36co 主域失效 → 加备用域名**（用户提供）。
 *
 * 用户 2026-10-06 提供：备用域名 www.25jm.cc、备用域名2 www.91gc.pro。
 *
 * 实测（本机 curl）：
 *   www.36co.cc  -> 两个方向都被 RST（整站不可达）
 *   www.25jm.cc  -> **HTTP 200**，同一套站：18 个栏目、同样的 /index.php/vod/... 路径、
 *                   同样的封面主机 23.225.11.173:8443
 *   www.91gc.pro -> 同样被 RST（保留为备用线路，日后可能恢复）
 *
 * 本轮关键风险：站方换域后，源码里散落的 rootDomain(u) == 36co 判定会**全部失配**，
 * 表现为「卡片进不了介绍页 / 介绍页按钮不对 / 板块名认不出」。故收成 co36Roots 集合。
 */
public class R69Check {
    static final String HPLAY = "D:/工作区/工程/Hplay安卓/";
    static final String SH = "D:/工作区/工程/AvPlay/独立版/src/com/han1me/X99.kt";
    static final String GEN = HPLAY + "build/gen/X99.kt";
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
        String up = read(SH), gen = read(GEN);

        System.out.println("== A) 备用域名进了站点线路 ==");
        check(n(gen, "25jm.cc") >= 3, "A1 25jm.cc 出现在生成物（主线路 + 文档 + 家族样本）");
        check(n(gen, "https://www.91gc.pro") >= 2, "A2 91gc.pro 保留为备用线路");
        check(n(gen, "X99Line(CO36_HOME, ") >= 1, "A3 主线路用 CO36_HOME（= 25jm.cc（裸域））");
        check(n(gen, "91gc.pro") >= 2, "A4 91gc.pro 作为备用线路注入");
        check(n(gen, "const val CO36_HOME = \"https://25jm.cc\"") == 1, "A5 主线路改指可用域名（裸域，www 被 DNS 污染）");
        check(n(gen, "co36Fixed") >= 2, "A6 co36Fixed 存在（对远端目录做域名纠正）");
        check(n(gen, "if (s.name.equals(CO36_NAME, ignoreCase = true)) co36Fixed(s) else s") == 3,
                "A7 ★ 纠正发生在**三个入口**（联网赋值 sites / 缓存回退 / 站点列表），否则板块取 base 仍用旧域名");
        check(n(gen, "private fun co36Dead") == 1, "A8 有失效域名的判定（剔除后旧保存值才会回落）");

        System.out.println("== B) 换域后仍认得出是同一个板块（关键风险） ==");
        check(n(gen, "private val co36Roots = setOf(\"36co\", \"25jm\", \"91gc\")") == 1, "B1 根域集合含三个域名");
        check(n(gen, "rootDomain(u) in co36Roots") >= 1, "B2 isCo36Url 改用集合");
        check(n(gen, "if (rootDomain(u) in co36Roots) return false") == 1, "B3 maccms 排除改用集合");
        check(n(gen, "r in co36Roots -> CO36_NAME") == 1, "B4 板块名归属改用集合");
        boolean onlyComment = true;
        for (String line : gen.split(Character.toString((char) 10))) {
            if (line.contains("== \"36co\"") && !line.trim().startsWith("*") && !line.trim().startsWith("//")) { onlyComment = false; }
        }
        check(onlyComment, "B5 写死的判定只出现在注释里（功能行已全部改为集合）");

        System.out.println("== C) 家族样本用可用域名 ==");
        check(n(gen, "\"https://www.25jm.cc/index.php/vod/play/id/38936/sid/1/nid/1.html\"") == 1,
                "C1 家族样本改用可用域名（第 44 轮教训：样本写错会让计数长期少报）");
        check(n(gen, "\"http://www.36co.cc/index.php/vod/play/id/38936") == 0, "C2 反断言：样本不再用失效主域");

        System.out.println("== D) 描述如实 ==");
        check(n(gen, "18 家传媒分区") >= 1, "D1 仍标注 18 家分区");
        check(n(gen, "多备用域名") >= 1, "D2 描述已更新（旧的「只有明文 80 可达」不再成立）");

        System.out.println("== E) 交付包内含本次修复 ==");
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
        check(!blobs.isEmpty(), "E1 包里有 dex");
        check(anyContains(blobs, "25jm.cc"), "E2 25jm.cc 进了包");
        check(anyContains(blobs, "www.91gc.pro"), "E3 91gc.pro 进了包");

        System.out.println("========================================");
        System.out.println("R69Check  PASS=" + pass + "  FAIL=" + fail);
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
                String nm = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (nm.contains("unsigned") || nm.contains("aligned")) continue;
                long t2 = Files.getLastModifiedTime(p).toMillis();
                if (t2 > bt) { bt = t2; best = p; }
            }
        }
        if (best == null) { System.out.println("EXIT2 找不到 apk"); System.exit(2); }
        return best;
    }
}