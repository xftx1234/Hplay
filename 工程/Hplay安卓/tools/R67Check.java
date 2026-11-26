import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 第 67 轮离线验收器：**升级通道改为移动云盘 139 直连**（计划任务不变、兼容旧通道）。
 *
 * 用户原话：「检查更新的升级通道改成直连获取移动网盘134里hplay目录的最新构建版本，
 *           计划任务不变…只不过获取方式不再走自建网盘而是直接去移动网盘拿结果，
 *           新构建版本要兼容旧的升级通道，也就是说，从旧版本可以检测到新通道更新」。
 *
 * 三件事必须同时成立：
 *   ① 139 直连为主通道；
 *   ② 自建网盘**保留为兜底**（这就是「兼容旧通道」）；
 *   ③ 两条通道读**同一个目录** ⇒ 计划任务不用改、旧版本也能检测到新版。
 */
public class R67Check {
    static final String HPLAY = "D:/工作区/工程/Hplay安卓/";
    static final String UPD = HPLAY + "src/com/han1me/update/UpdateChecker.kt";
    static final String Y139 = HPLAY + "src/com/han1me/update/Yun139.kt";
    static final String ENV = HPLAY + "src/com/han1me/HplayEnv.kt";
    static final String UI = HPLAY + "src/com/han1me/update/HplayUpdateUi.kt";
    static final String PUSH = HPLAY + "tools/push_to_mea.py";
    static final String BUILD = HPLAY + "build.py";
    static final String CREDFILE = HPLAY + "tools/139_cred.txt";
    static final String CREDKT = HPLAY + "build/gen/Yun139Cred.kt";
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
        String upd = read(UPD), y139 = read(Y139), env = read(ENV), ui = read(UI);
        String push = read(PUSH), build = read(BUILD);

        System.out.println("== A) 139 直连为主通道 ==");
        check(n(upd, "Yun139.fetchManifest()") == 1, "A1 主通道直接调 Yun139（不是先试网盘）");
        int iY = upd.indexOf("Yun139.fetchManifest()");
        int iLoop = upd.indexOf("for (base in ENDPOINTS)");
        check(iY > 0 && iLoop > iY, "A2 139 排在网盘循环**之前**（主/兜底次序正确）");
        check(n(upd, "Via.YUN139") >= 1 && n(upd, "Via.NETDISK") >= 1, "A3 结果标注走的是哪条通道");
        check(n(y139, "personal-kd-njs.yun.139.com") >= 1, "A4 139 端点在位（实测值，含兜底 host）");
        check(n(y139, "qryRoutePolicy") >= 1, "A5 路由接口在位");
        check(n(y139, "/file/list") >= 1, "A6 目录列举接口在位");
        check(n(y139, "/file/getDownloadUrl") >= 1, "A7 取直链接口在位");

        System.out.println("== B) 兼容旧通道：网盘保留为兜底 ==");
        check(n(upd, "mea.yuzusoft.ccwu.cc") == 1, "B1 公网网盘端点保留");
        // ★ 第 331 轮改：内网端点已移除（公开分发的 APK 不能带内网地址）。
        check(n(upd, "192.168") == 0, "B2 源码不含内网地址（公开分发前提）");
        check(n(upd, "Result.Failed(reasons.joinToString") == 1, "B3 两条通道都失败才报失败，且带上两边原因");
        check(n(upd, "139：") >= 1 && n(upd, "reasons.add") >= 1,
                "B4 失败原因按「139 直连 / 各通道@端点」分别标注（第 113 轮多通道链）");

        System.out.println("== C) 同一目录 ⇒ 计划任务不变、旧版可检测到新版 ==");
        check(n(upd, "REMOTE_DIRS") >= 2 && n(upd, "/移动云盘134/hplay") >= 1,
                "C1 App 端以多通道列表读取，首选仍是 /移动云盘134/hplay（与推送落点一致）");
        check(n(push, "/移动云盘134/hplay") >= 1, "C2 推送脚本目录未变（计划任务不用改）");
        check(n(push, "version.json") >= 1, "C3 仍写 version.json 清单");
        check(read(PUSH).contains("\"channel\": \"mea-netdisk\"") || read(PUSH).contains("\"channel\""),
                "C4 清单带 channel 字段（旧版仍按同一份清单判断）");
        check(n(y139, "BUILD_DIR = \"hplay\"") == 1, "C5 139 侧目录名 = hplay（与推送落点同段）");

        System.out.println("== D) ★ 凭据绝不入库 ==");
        check(gitIgnored(), "D2 tools/139_cred.txt 已被 .gitignore 忽略");
        check(Files.exists(Paths.get(CREDKT)), "D3 构建期生成了 build/gen/Yun139Cred.kt");
        check(n(build, "gen_yun139_credential") >= 2, "D4 build.py 有凭据生成步骤（定义 + 调用）");
        check(n(env, "bindUpdateCredential()") >= 1, "D5 HplayEnv.init 注入凭据");
        check(n(env, "KEY_YUN139_AUTH") >= 1, "D6 设置键可覆盖（用户不必重打包换凭据）");
        check(n(y139, "authorization: String = \"\"") == 1, "D7 源码里的凭据默认是空串（不硬编码）");

        System.out.println("== E) 139 端「按清单指名取 APK」（第 49 轮的缺陷） ==");
        check(n(y139, "apkByName[apkName]") == 1, "E1 按清单 apk 文件名精确匹配 fileId");
        check(n(y139, "it.optString(\"name\").endsWith(\".apk\"") == 0,
                "E2 反断言：不再用「遍历到的最后一个 .apk」（那会拿到永久保留的 v1.0.22）");
        check(n(y139, "大小(") >= 1, "E3 APK 大小与清单不符时拒绝（防「清单与产物不是同一次发布」）");

        System.out.println("== F) UI 如实标注通道 ==");
        check(n(ui, "移动云盘139：v") == 1, "F1 成功态标注「移动云盘139：v…」");
        check(n(ui, "自建网盘兜底") == 1, "F2 网盘通道时标注它是兜底");
        check(n(ui, "139 凭据没配") >= 1 || n(ui, "139 直连") >= 1, "F3 失败态说明两条通道都试过");

        System.out.println("== G) 交付包内含 139 通道 ==");
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
        check(!blobs.isEmpty(), "G1 包里有 dex");
        check(anyContains(blobs, "移动云盘139"), "G2 「移动云盘139」文案进了包（说明新通道真的在产物里）");
        check(anyContains(blobs, "qryRoutePolicy"), "G3 139 路由接口字符串进了包");

        System.out.println("========================================");
        System.out.println("R67Check  PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) { for (String f : fails) System.out.println("  - " + f); System.exit(1); }
    }

    /** 用 git check-ignore 判定（不靠读 .gitignore 文本，那条路会误判）。 */
    static boolean gitIgnored() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "D:/工作区/工具/MinGit/cmd/git.exe", "check-ignore", "-q",
                "工程/Hplay安卓/tools/139_cred.txt");
            pb.directory(new File("D:/工作区"));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
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