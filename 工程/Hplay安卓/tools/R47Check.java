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
 * 第 47 轮离线验收器：**构建后自动推送网盘** + **用户页「检查更新」**。
 *
 * 纪律（沿用 R42/R43/R45/R46，都是以前踩过之后定下来的）：
 *  · **不手抄生产逻辑**：远端目录、清单文件名、权限位、端点、版本比较口径，全部从被审源码
 *    或脚本文本里抽出来用；抽不到就 exit(2)。
 *  · 断言打在**代码形态**上，不数注释里出现的字串（第 46 轮的教训：注释里也含同样的串）。
 *  · 每条「不存在 X」都配一条「非空」。
 *  · 路径写死在本文件里（中文路径经 bash→Java argv 会被 ANSI 码页打成 mojibake）。
 */
public class R47Check {
    static final String WS = "D:/工作区/";
    static final String HPLAY = WS + "工程/Hplay安卓/";
    static final String GEN_APP = HPLAY + "build/gen/Han1meApp.kt";
    static final String PUSHER = HPLAY + "tools/push_to_mea.py";
    static final String BUILD = HPLAY + "build.py";
    static final String CHECKER = HPLAY + "src/com/han1me/update/UpdateChecker.kt";
    static final String UI = HPLAY + "src/com/han1me/update/HplayUpdateUi.kt";
    static final String APKDIR = HPLAY + "apk/";
    static final String MANIFEST = HPLAY + "src/AndroidManifest.xml";

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
        String pusher = read(PUSHER);
        String build = read(BUILD);
        String checker = read(CHECKER);
        String ui = read(UI);

        // ================================================================ (1) 推送脚本
        System.out.println("== (1) 自动推送脚本（tools/push_to_mea.py）==");

        // 落点必须与 App 端一致 —— 两边各写一份字符串，所以**互相核对**是唯一有效断言。
        String remoteDir = must(pusher, "REMOTE_DIR = \"([^\"]+)\"", "脚本里的远端目录", 1);
        check(remoteDir.equals("/移动云盘134/hplay"),
                "A1 推送落点是用户指定的 /移动云盘134/hplay（抽到 " + remoteDir + "）");
        // 第 52 轮：检查更新**回滚到自建网盘**，远端目录常量回到 UpdateChecker 里。
        // ★ 第 113 轮：App 端改为 REMOTE_DIRS 多通道列表（用户要求「故障时自动切换」），
        // 这里改为断言「推送脚本的落点**仍在** App 的通道列表里」。
        boolean appHasDir = checker.contains("REMOTE_DIRS") && checker.contains(remoteDir);
        check(appHasDir, "A2 推送落点在 App 的 REMOTE_DIRS 通道列表里（" + remoteDir + "）");
        String mf = must(pusher, "MANIFEST = \"([^\"]+)\"", "脚本里的清单名", 1);
        String amf = must(checker, "MANIFEST = \"([^\"]+)\"", "App 里的清单名", 1);

        // 中文路径必须 percent-encoding（HTTP 头只能 latin-1，第 43 轮踩过）
        check(n(pusher, "urllib.parse.quote(p, safe=\"/\")") == 1,
                "A4 中文路径走 percent-encoding（File-Path 头只能 latin-1，明文会抛 UnicodeEncodeError）");
        check(n(pusher, "headers={\"File-Path\": enc(") >= 1,
                "A5 上传确实把编码后的路径放进 File-Path 头");
        // 上传必须用 PUT（不是 multipart）
        check(n(pusher, "method=\"PUT\"") >= 1, "A6 上传走 PUT /api/fs/put");
        // 权限借还：读当前真实权限位，只在需要时借
        // 必须按**整行**抽，否则 DOTALL 会跨行匹配到别的 "RW = 数字"
        String roRw = must(pusher, "(?m)^RO, RW = ([0-9]+), ([0-9]+)$", "权限位常量行", 0);
        String ro = must(pusher, "(?m)^RO, RW = ([0-9]+),", "只读权限位", 1);
        String rw = must(pusher, "(?m)^RO, RW = [0-9]+, ([0-9]+)$", "可写权限位", 1);
        check(ro.equals("28935") && rw.equals("29183"),
                "A7 权限位沿用工程既有约定（只读 " + ro + " / 可写 " + rw + "）");
        check(n(pusher, "if perm != RW:") == 1,
                "A8 只在当前不可写时才借权限（已可写就不借不还，不制造无谓的 token 失效）");
        // borrow() 内部必须重新登录一次并**实测权限位真的生效**（返回值对不等于生效）
        String borrowBody = must(pusher, "def borrow\\(value\\):([\\s\\S]*?)\\n\\n", "borrow 函数体", 1);
        check(n(borrowBody, "tk = login()") >= 1, "A9a borrow 内部重新登录（改权限会作废所有已发 token）");
        check(n(borrowBody, "assert now == value") == 1,
                "A9b 改完实测权限位真的生效（返回值对不等于生效）");
        check(n(pusher, "def six_write_attempts(tk)") == 1 && n(pusher, "c != 403") == 1,
                "A10 归还后六种写操作逐项实测被拒（只认 403；只测 mkdir/put 不够）");
        // 判成功只看 JSON code（SPA 兜底会把 404 伪装成 200 + HTML）
        check(n(pusher, "非 JSON（SPA 兜底") == 1, "A11 非 JSON 视为路由不存在（SPA 兜底会把 404 伪装成 200）");
        check(n(pusher, "r.get(\"code\") != 200") >= 1, "A12 判成功只看 JSON 里的 code==200");
        // 传完双向复验
        check(n(pusher, "if got.get(apk.name) != size:") == 1, "A13 传完核对远端大小");
        check(n(pusher, "raw_url") >= 2 && n(pusher, "PK") >= 1,
                "A14 传完真取一次 raw_url 并验 zip 魔数（不是只看列表里有没有名字）");
        // 凭据不进日志
        check(n(pusher, "只回显用户名") >= 1 || n(pusher, "\"  登录 OK（用户 %s）\"") == 1,
                "A15 凭据只读、日志里只出现用户名");
        check(n(pusher, "CRED.read_text") == 1, "A16 凭据从工程既有位置读，不在脚本里硬写");

        // ================================================================ (2) 构建接线
        System.out.println("== (2) build.py --release 自动推送 ==");
        check(n(build, "push_to_mea.py") >= 1, "B1 build.py 里引用了推送脚本");
        check(n(build, "if args.release and not args.no_deploy:") == 1,
                "B2 只在 --release 且实际部署时推（--no-deploy 是编译性验证，不该动网盘）");
        check(n(build, "推送失败（APK 已生成") == 1,
                "B3 推送失败不阻断构建，但把原因完整打出来（不静默失败）");

        // ================================================================ (3) App 端取清单
        System.out.println("== (3) App 端「检查更新」取清单 ==");
        check(n(checker, "/api/fs/get") >= 2, "C1 走 OpenList 的 /api/fs/get（裸 /d/ 与 /p/ 被 CF 拦成 error 1010）");
        check(n(checker, "\"raw_url\"") >= 2, "C2 从 data.raw_url 取带签名的厂商直链");
        check(n(checker, "JSONObject().put(\"path\"") >= 2, "C3 请求体是 {path: …} 的 JSON");
        // ★ 第 331 轮改：**内网端点已从 App 里移除**（公开分发的 dex 不能带内网地址）。
        // 现在只有公网端点，且是 listOf 单元素 —— 多端点轮询代码路径保留，便于以后加镜像。
        check(n(checker, "\"https://mea.yuzusoft.ccwu.cc\"") == 1,
                "C4 公网端点写在源码里（唯一端点）");
        check(n(checker, "192.168") == 0,
                "C5 源码里没有任何内网地址（公开分发的 dex 不能带）");
        check(n(checker, "Result.Failed(reasons.joinToString") == 1, "C7 两条通道（139 主 / 网盘兜底）都失败才报失败，并带上两边原因");
        check(n(checker, "remote.versionCode > current") == 1, "C8 有没有新版按 versionCode 比（不是按字符串比版本号）");
        // versionCode 必须从 PackageManager 读，不许界面另存常量（第 43 轮的教训）
        check(n(checker, "getPackageInfo(c.packageName, 0).versionCode") == 1,
                "C9 versionCode 从 PackageManager 读（不许在界面另存一份常量）");
        check(n(checker, "时效") >= 1 || n(checker, "900") >= 1 || n(checker, "15 分钟") >= 1, "C11 如实标注了直链的时效性");

        // ================================================================ (4) 界面接线
        System.out.println("== (4) 用户页接线与界面 ==");
        check(n(gen, "com.han1me.update.HplayUpdatePanel(") == 1, "D1 更新面板挂进覆盖层（恰好 1 处）");
        check(n(gen, "UserRow(\"🔄\", \"检查更新\"") == 1, "D2 用户页有「检查更新」这一格（恰好 1 处）");
        check(n(gen, "onUpdate = { showUpdate = true }") == 1, "D3 调用点传了 onUpdate");
        check(n(gen, "onUpdate: () -> Unit,") == 1, "D4 UserTab 定义里也有这个形参（只改调用点会编译不过）");
        check(n(gen, "var showUpdate by remember") == 1, "D5 有 showUpdate 状态");
        check(n(gen, "showUpdate -> showUpdate = false") >= 1, "D6 返回键栈里有这一级");
        check(n(gen, "showUpdate || showDownloads") == 1, "D7 返回键 enabled 也含 showUpdate");
        // 界面的诚实性
        check(n(ui, "\"没取到更新信息\"") == 1, "D8 取不到时明写「没取到更新信息」（不是「已是最新」）");
        check(n(ui, "\"这不代表已经是最新版本 —— 只是这次没查到。\"") == 1,
                "D9 取不到时明确告诉用户这不等于最新（不谎报）");
        check(n(ui, "\"已是最新版本\"") == 1, "D10 只有在真比对通过后才说已是最新");
        check(n(ui, "HplayEnv.openExternal(") >= 1, "D11 点下载把直链交给系统浏览器");
        check(n(ui, "UpdateChecker.apkDirectUrl(rem)") == 1, "D12 每次点都重新取一次直链（签名会过期，不复用）");
        check(n(ui, "TextAlign") >= 1, "D13 下载按钮居中");

        // ================================================================ (5) 交付包同源
        System.out.println("== (5) 交付包（apk/Hplay_v*.apk 最新一份）字节同源 ==");
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
        check(!blobs.isEmpty(), "E1 包里有 dex（非空）");
        check(anyContains(blobs, "com/han1me/update/UpdateChecker"), "E2 更新模块进了 dex");
        check(anyContains(blobs, "HplayUpdatePanel"), "E3 更新面板进了 dex");
        check(anyContains(blobs, "检查更新"), "E4 用户可见文案「检查更新」进了 dex");
        check(anyContains(blobs, "没取到更新信息"), "E5 诚实性文案进了 dex");
        check(anyContains(blobs, "qryRoutePolicy"), "E6 第 67 轮起 139 直连通道**在包里**（升级通道改为直连移动云盘）");
        check(anyContains(blobs, "version.json"), "E7 清单文件名进了 dex");
        check(anyContains(blobs, "raw_url"), "E8 网盘的 raw_url 口径进了 dex");

        System.out.println("========================================");
        System.out.println("R47Check  PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            for (String f : fails) System.out.println("  - " + f);
            System.exit(1);
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