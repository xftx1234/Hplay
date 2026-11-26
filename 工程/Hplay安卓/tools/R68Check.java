import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 68 轮离线验收器：**「200 + 空正文」被误判成图床故障**（用户实机截图）。
 *
 * 用户原话：「提交bug,正文太小导致的图床加载失败，在不影响现在图床路线成果的基础上，修这个bug」。
 *
 * 实机复现（本机 curl，非猜测）：
 *   GET https://picmeta2025.sbs/1.JPG -> **HTTP 200 + 0 字节 + Content-Type: text/html**
 *   （该域名已变成「停放页」：/upload/... 等路径回 403，某些路径回 200 空正文）
 *
 * 老实现的三处问题：
 *   ① 只按 `bytes.size < 64` 判失败，把「200 + 0B」记成「正文太小(0B)」——
 *      既不区分「HTTP 失败」与「拿到的不是图片」，也看不出到底是网络还是内容的问题；
 *   ② 这类失败**照样进整域指数退避**：一个坏链接会让同图床其它正常封面跟着等；
 *   ③ 界面**照样重试 3 次**（每次递增延迟）——这张图本来就不存在，重试毫无意义。
 */
public class R68Check {
    static final String HPLAY = "D:/工作区/工程/Hplay安卓/";
    static final String HC = HPLAY + "build/gen/HanimeClient.kt";
    static final String THUMB = HPLAY + "src/com/han1me/web/HplayThumb.kt";
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
        String hc = read(HC), th = read(THUMB);

        System.out.println("== A) 判据：区分「空正文/HTML 正文」与「HTTP 失败」 ==");
        check(n(hc, "private const val NOT_IMAGE") == 1, "A1 有 NOT_IMAGE 标记（「这个地址上没有图片」）");
        // ★ 第 111 轮修：这里原来断言的是**丢弃式**写法（if (...) <值>），那正是「0 字节被当成功」的成因。
        check(n(hc, "if (bytes.isEmpty()) return@use (null to NOT_IMAGE)") == 1,
            "A2 空正文 -> NOT_IMAGE（**必须真的 return**；丢弃式写法已被第 111 轮修掉）");
        check(n(hc, "if (bytes.size < 64) return@use (null to") == 1,
            "A2b 过小正文也必须真的 return");
        check(n(hc, "ctype.contains(\"text/html\")") == 1, "A3 按 Content-Type 判 HTML");
        check(n(hc, "bytes[0] == ") >= 1, "A4 按正文头判 HTML（防 200 + HTML 冒充图片）");
        // 第 122 轮：黄果板块连同其封面 AES 解密一并移除，这里回到原始「二选一」形态
        check(n(hc, "if (isHtml) null to NOT_IMAGE else bytes to \"\"") == 1, "A5 HTML 正文不交给解码器");
        check(n(hc, "正文太小") >= 1, "A6 「正文太小」保留给真正的短正文（<64B）");

        System.out.println("== B) 这类失败**不进整域熔断** ==");
        check(n(hc, "if (err == NOT_IMAGE) notImage = true") == 1, "B1 记录「不是图片」");
        check(n(hc, "if (notImage) {") == 1, "B2 提前返回（不落到退避代码）");
        check(n(hc, "没有这张图（返回非图片正文）") == 1, "B3 给出人话原因");
        // 关键次序断言：notImage 的 return 必须排在 thumbFailCount 自增**之前**
        int iRet = hc.indexOf("if (notImage) {");
        int iFails = hc.indexOf("val fails = (thumbFailCount[host] ?: 0) + 1");
        check(iRet > 0 && iFails > iRet, "B4 ★ notImage 提前返回排在退避自增之前（否则仍会连坐同域）");
        check(n(hc, "thumbDeadUntil[host] = System.currentTimeMillis() + backoff") == 1, "B5 真正的网络失败仍保留退避（未破坏第 64 轮成果）");

        System.out.println("== C) 界面：不重试「这张图不在」 + 文案分层 ==");
        check(n(th, "!r.contains(\"不是图片正文\")") == 1, "C1 这类失败**不重试**（重试也没用）");
        check(n(th, "!r.contains(\"没有这张图\")") == 1, "C2 同上（两种文案都挡）");
        check(n(th, "无封面") == 1, "C3 「无封面」中性文案（内容形态，不是故障）");
        check(n(th, "noImage") >= 2, "C4 两类失败分开渲染");
        check(n(th, "1200L * (attempt + 1)") == 1, "C5 真正的网络失败仍重试（未破坏第 64 轮成果）");

        System.out.println("== D) 不影响现有图床路线成果 ==");
        check(n(hc, "thumbGate = Semaphore(16)") == 1, "D1 并发闸门保留（第 84 轮：8→16，修头像加载太慢）");
        check(n(hc, "thumbRefererFirst") >= 1, "D2 Referer 写法记忆保留");
        check(n(hc, "(1000L shl (fails - 1).coerceAtMost(3)).coerceAtMost(8_000L)") >= 1, "D3 渐进退避保留（1s→8s；第 86 轮加：连接层失败另封 5 分钟）");
        check(n(hc, "thumbFailCount.remove(host)") == 1, "D4 成功清零失败计数保留");
        check(n(hc, "thumbDeadUntil.remove(host)") == 1, "D5 成功解除短时等待保留");
        check(n(th, "HanimeClient.fetchThumbBytes(url)") == 1, "D6 界面仍走唯一生产入口");

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
        check(anyContains(blobs, "不是图片正文"), "E2 NOT_IMAGE 文案进了包");
        check(anyContains(blobs, "无封面"), "E3 「无封面」文案进了包");

        System.out.println("========================================");
        System.out.println("R68Check  PASS=" + pass + "  FAIL=" + fail);
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