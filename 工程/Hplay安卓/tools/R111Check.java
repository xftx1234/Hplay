import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 第 111 轮验收：两处真机 bug 的护栏。
 *
 * A) 封面「解码失败(0B)」/「封面未缓存」—— thumbAttempt 里两行守卫写成表达式语句（值被丢弃），
 *    0 字节被当成「取图成功」返回，且因此让「换另一种 Referer 写法再试」永远走不到。
 * B) 肉视频下载分片 403、重复下载仍失败 —— 下载任务借用了进程级 lastDetailUrl。
 */
public class R111Check {
    static final String HPLAY = "D:/工作区/工程/Hplay安卓/";
    static final String HC = HPLAY + "build/gen/HanimeClient.kt";
    static final String X99 = HPLAY + "build/gen/X99.kt";
    static final String DL = HPLAY + "src/com/han1me/download/HplayDownloads.kt";
    static final String DUI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";
    static int pass = 0, fail = 0;

    static void check(boolean c, String msg) {
        if (c) { pass++; System.out.println("  PASS " + msg); }
        else { fail++; System.out.println("FAIL " + msg); }
    }
    static String read(String p) {
        try { return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8); }
        catch (Exception e) { System.out.println("FAIL 读不到 " + p); fail++; return ""; }
    }
    static int n(String t, String needle) {
        int c = 0, i = 0;
        while ((i = t.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    public static void main(String[] a) {
        String hc = read(HC), x99 = read(X99), dl = read(DL), dui = read(DUI);
        System.out.println("== A) 封面：0 字节不再被当成取图成功 ==");
        check(n(hc, "if (bytes.isEmpty()) return@use (null to NOT_IMAGE)") == 1,
            "A1 空正文守卫**真的 return**（原来值被丢弃）");
        check(n(hc, "if (bytes.size < 64) return@use (null to") == 1,
            "A2 正文过小守卫**真的 return**");
        check(n(hc, "if (bytes.isEmpty()) null to NOT_IMAGE") == 0,
            "A3 反断言：丢弃式空正文判断已移除");
        check(n(hc, "if (bytes.size < 64) null to ") == 0,
            "A4 反断言：丢弃式过小正文判断已移除");
        // 第 122 轮：黄果板块连同封面 AES 解密一并移除，这里回到原始「二选一」形态
        check(n(hc, "if (isHtml) null to NOT_IMAGE else bytes to") == 1,
            "A5 末枝仍是「是 HTML 就判非图片，否则给字节」");
        System.out.println("== B) 下载分片 Referer 不再借用进程级状态 ==");
        check(n(x99, "fun refererFor(mediaUrl: String, pageUrl: String = ") == 1,
            "B1 refererFor 接受调用方自己的详情页");
        check(n(x99, "if (pageUrl.isNotBlank()) return pageUrl.trim()") == 1,
            "B2 给了详情页就直接用它（不读 lastDetailUrl）");
        check(n(dl, "native -> runCatching { X99.refererFor(url, pageUrl) }") == 1,
            "B3 下载器把自己的 pageUrl 传进去");
        check(n(dl, "native -> runCatching { X99.refererFor(url) }") == 0,
            "B4 反断言：旧的「只用 url」调用已移除");
        check(n(x99, "fun refererFor(mediaUrl: String): String") == 0,
            "B5 反断言：旧的一参签名已不存在");
        System.out.println("== C) 下载页不再在主线程扫盘（点进去不卡 5 秒） ==");
        check(n(dui, "var all by remember { mutableStateOf<List<DlItem>>(emptyList()) }") == 1,
            "C1 列表改为状态量（不再在组合期现算）");
        check(n(dui, "\n    val all = remember(tick) { HplayDownloads.items() }") == 0,
            "C2 反断言：主线程remember扫盘已移除");
        check(n(dui, "runCatching { HplayDownloads.items() }.getOrDefault(emptyList())") == 1,
            "C3 items() 改到 IO 线程求值");

        System.out.println();
        System.out.println("R111Check  PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}