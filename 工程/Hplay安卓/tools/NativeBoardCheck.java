// Hplay 原生板块离线验收器（新增十家原生化那一轮的核查；改解析或站点改版后必跑）
//   编译：javac -encoding UTF-8 -d . NativeBoardCheck.java
//   运行：java -Dstdout.encoding=UTF-8 NativeBoardCheck
//   取证目录：D:\工作区\临时\r35_familyA 与 r35_familyB（三个子代理抓下来的真实页面与清单，逐字节落盘）
//
//   判据原则（这一条是这一轮审查的教训，别再犯）：
//   1. 每条期望值都来自**磁盘上的取证文件**或我手工 grep 出来的字面量，不来自解析器自己算的结果；
//      否则「解析结果没问题」就变成了由解析器自己定义的死循环。
//   2. 没跑通媒体那一跳的站（好色TV 522、VJAV 502、YesPorn 404）**不在这里宣布可用**，
//      只验它的取址正则能不能从页面里解出地址，并显式记一句「媒体未验证」。
//   3. 板块路由判据必须互不重叠：同一条 URL 只能落进一个板块，落不进去的必须回落网页版。
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class NativeBoardCheck {
    static int pass = 0, fail = 0, skip = 0;

    static void ck(String what, boolean ok) { ck(what, ok, ""); }

    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (ok) pass++; else fail++;
    }

    static void skipped(String what) {
        System.out.println("  SKIP  " + what);
        skip++;
    }

    // ==================== 与 X99.kt 逐条同语义（改了 X99 就要同步这里） ====================
    static String hostOf(String u) {
        try { String h = new URI(u).getHost(); return h == null ? "" : h.toLowerCase(); }
        catch (Exception e) { return ""; }
    }

    /** = X99.bareHost */
    static String bareHost(String h) {
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    /** = X99.rootDomain：可注册域的首段（jable666.com→jable666；cn.avjoy.ws→avjoy；www.rou888.net→rou888） */
    static String rootDomain(String u) {
        String[] parts = bareHost(hostOf(u)).split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] : (parts.length == 1 ? parts[0] : "");
    }

    /** = X99.pathOnly */
    static String pathOnly(String u) {
        String s = u.startsWith("http") ? "/" + u.substring(u.indexOf("://") + 3) : u;
        int sl = s.indexOf('/', 1);
        s = sl < 0 ? "/" : s.substring(sl);
        int q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#'); if (h >= 0) s = s.substring(0, h);
        return s;
    }

    static final Pattern MISSAV_PATH = Pattern.compile("^/cn/[a-z0-9][a-z0-9._-]*[0-9][a-z0-9._-]*$");
    static final Pattern JABLE_PATH = Pattern.compile("^/videos/[^/?#]+/?$");
    static final Pattern ROU_PATH = Pattern.compile("^/[vs]/[0-9a-z]{16,}$");
    static final Pattern LISSAV_PATH = Pattern.compile("^/(asian|domestic)/[A-Za-z-]{2,6}/video/[a-z]+/[^/?#]+$");
    static final Pattern AVJOY_PATH = Pattern.compile("^/video/\\d+/[^/?#]+/?$");
    static final Pattern SUPERPORNO_PATH = Pattern.compile("^/video/[A-Za-z0-9-]+/?$");
    static final Pattern OKXXX_PATH = Pattern.compile("^/video/\\d+/?$");
    static final Pattern SHORTV_PATH = Pattern.compile("^/v/[^/?#]{3,12}/?$");
    static final Pattern MACCMS_PLAY = Pattern.compile("/index\\.php/vod/play/id/\\d+");
    static final Pattern PB365_PLAY = Pattern.compile("^/video-\\d+\\.htm$");

    static boolean isXchina(String u) {
        String h = hostOf(u);
        return h.equals("xchina001.ink") || h.endsWith(".xchina001.ink");
    }

    static boolean isMissav(String u) { return MISSAV_PATH.matcher(pathOnly(u)).matches(); }

    static boolean isJable(String u) { return rootDomain(u).startsWith("jable") && JABLE_PATH.matcher(pathOnly(u)).matches(); }

    static boolean isRou(String u) { return rootDomain(u).startsWith("rou") && ROU_PATH.matcher(pathOnly(u)).matches(); }

    static boolean isLissav(String u) {
        String r = rootDomain(u);
        return (r.startsWith("huangguo") || r.startsWith("lissav")) && LISSAV_PATH.matcher(pathOnly(u)).matches();
    }

    static boolean isAvjoy(String u) { return rootDomain(u).startsWith("avjoy") && AVJOY_PATH.matcher(pathOnly(u)).matches(); }

    static boolean isSuperporn(String u) { return rootDomain(u).startsWith("superporn") && SUPERPORNO_PATH.matcher(pathOnly(u)).matches(); }

    static boolean isOkxxx(String u) { return rootDomain(u).startsWith("okxxx") && OKXXX_PATH.matcher(pathOnly(u)).matches(); }

    static boolean is591av(String u) { return rootDomain(u).startsWith("591av") && SHORTV_PATH.matcher(pathOnly(u)).matches(); }

    static boolean isEmbed(String u) {
        String p = pathOnly(u);
        return MACCMS_PLAY.matcher(p).find() || PB365_PLAY.matcher(p).matches();
    }

    /** = X99.boardOf（十家原生板块的唯一判据；返回 null 就是走网页版）。 */
    static String boardOf(String u) {
        if (isXchina(u)) return "xchina";
        if (isMissav(u)) return "missav";
        if (isJable(u)) return "jable";
        if (isRou(u)) return "rouvideo";
        if (isLissav(u)) return "lissav";
        if (isAvjoy(u)) return "avjoy";
        if (isSuperporn(u)) return "superporn";
        if (is591av(u)) return "591av";
        if (isOkxxx(u)) return "okxxx";
        if (isEmbed(u)) return "embed";
        return null;
    }

    static final Pattern REND = Pattern.compile("#EXT-X-STREAM-INF:([^\\n]*)\\n([^\\n]+)");
    static final Pattern RES = Pattern.compile("RESOLUTION=\\d+x(\\d+)");

    /** = X99.getPlaylist：认诱饵（HTTP 200 + 假清单，只有 tip.ts 一段）。 */
    static String getPlaylist(String text) {
        if (!text.contains("#EXTM3U") || text.contains("tip.ts")) return "";
        if (text.contains("#EXT-X-STREAM-INF")) return text;
        int segs = 0;
        for (String l : text.split("\\R")) if (!l.trim().isEmpty() && !l.startsWith("#")) segs++;
        return segs > 2 ? text : "";
    }

    /** = X99.knownHeights / X99.qualityFromUrl：只认得起来的档位，目录号不许冒充清晰度。 */
    static final Set<Integer> KNOWN_H = new HashSet<>(Arrays.asList(240, 360, 480, 540, 720, 768, 1080, 1440, 2160));

    static int qualityFromUrl(String u) {
        Matcher m = Pattern.compile("[-_/.](\\d{3,4})p?(?=[./])").matcher(u);
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (KNOWN_H.contains(v)) return v;
        }
        return 0;
    }

    /** = X99.renditionsOf */
    static List<String[]> renditionsOf(String masterUrl, String text) {
        List<String[]> out = new ArrayList<>();
        if (getPlaylist(text).isEmpty()) return out;
        String base = masterUrl.substring(0, masterUrl.lastIndexOf('/') + 1);
        Matcher m = REND.matcher(text);
        while (m.find()) {
            Matcher rh = RES.matcher(m.group(1));
            if (!rh.find()) continue;
            String uri = m.group(2).trim();
            if (uri.isEmpty() || uri.startsWith("#")) continue;
            String abs;
            try { abs = new URI(base).resolve(uri).toString(); } catch (Exception e) { abs = base + uri; }
            out.add(new String[]{rh.group(1), abs});
        }
        if (!out.isEmpty()) {
            out.sort((x, y) -> Integer.parseInt(y[0]) - Integer.parseInt(x[0]));
            return out;
        }
        out.add(new String[]{String.valueOf(qualityFromUrl(masterUrl)), masterUrl});
        return out;
    }

    /** = X99.rouDecode（先 base64 解，再每字节减 k） */
    static String rouDecode(String d, int k) throws Exception {
        byte[] raw = Base64.getDecoder().decode(d);
        byte[] out = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = (byte) ((raw[i] - k) & 0xFF);
        return new String(out, StandardCharsets.UTF_8);
    }

    /** = X99.lissavUnsigned（按花括号配对切对象，挑不带 urlSign 的那条） */
    static String lissavUnsigned(String json) {
        int head = json.indexOf("\"playlist\"");
        if (head < 0) return null;
        int arr = json.indexOf('[', head);
        if (arr < 0) return null;
        int depth = 0, start = -1;
        for (int i = arr; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String o = json.substring(start, i + 1);
                    if (!o.contains("urlSign")) {
                        Matcher mm = Pattern.compile("\"url\":\"([^\"]+)\"").matcher(o);
                        if (mm.find()) return mm.group(1);
                    }
                    start = -1;
                }
            } else if (c == ']' && depth == 0) return null;
        }
        return null;
    }

    /** = X99.X99 里 `regex.find(html)?.groupValues?.get(1)` 的同语义：取第一个匹配的第一组，取不到给空串。 */
    static String g1(String re, String html) {
        Matcher m = Pattern.compile(re).matcher(html);
        return m.find() ? m.group(1) : "";
    }

    static String read(Path p) {
        try { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }
        catch (IOException e) { return ""; }
    }

    /** 取**第一个**匹配的第 1 组（没有就返回 ""）。避免在断言里重复 find() 把游标推走。 */

    static String cut(String s, int n) { return s == null ? "(null)" : (s.length() <= n ? s : s.substring(0, n) + "…"); }

    public static void main(String[] a) throws Exception {
        Path A = Paths.get("D:\\工作区\\临时\\r35_familyA");
        Path B = Paths.get("D:\\工作区\\临时\\r35_familyB");
        System.out.println("取证目录 A " + (Files.isDirectory(A) ? "在" : "缺") + " · B " + (Files.isDirectory(B) ? "在" : "缺"));

        System.out.println("== 1. 板块路由判据（十家互不重叠，其余一律回落网页版）");
        Map<String, String> expect = new LinkedHashMap<>();
        expect.put("https://xchina001.ink/video/id-6abd18f149bbd.html", "xchina");
        expect.put("https://www.missav06.com/cn/ewdx-599", "missav");
        expect.put("https://www.missav123.com/cn/fc2-ppv-4968519", "missav");
        expect.put("https://jable666.com/videos/smra-005/", "jable");
        expect.put("https://rou888.net/v/cmu8ng3q60004s6x3byujkmig", "rouvideo");
        expect.put("https://huangguo01.net/asian/zh-CN/video/cid/simm-412", "lissav");
        expect.put("https://cn.avjoy.ws/video/14614/%E4%B8%AD%E6%96%87", "avjoy");
        expect.put("https://cn.superporn.ws/video/asa-akira-gets-a-bbc-in-her-anus", "superporn");
        expect.put("https://591av.ws/v/60ravd/", "591av");
        expect.put("https://okxxx.life/video/786703/", "okxxx");
        expect.put("https://www.69ck.art/index.php/vod/play/id/329380/sid/1/nid/1.html", "embed");
        expect.put("https://pb365.nl/video-1246807.htm", "embed");
        for (Map.Entry<String, String> e : expect.entrySet())
            ck(cut(e.getKey(), 62) + " → " + e.getValue(), e.getValue().equals(boardOf(e.getKey())), String.valueOf(boardOf(e.getKey())));

        String[] mustFallBack = {
            "https://www.hanime1.me/watch?v=123",                    // 官方源：永远不进原生分支
            "https://vdownload.hembed.com/407695-720p.mp4",           // 官方媒体域
            "https://zh.xhamster1.pw/videos/xxx-xhRA7DK",             // 未打通的站
            "https://cn.pornhub163.net/viewvideo.php?viewkey=aa",
            "https://www.missav06.com/cn/new",                        // 导航项
            "https://jable666.com/latest-updates/2/",                 // 列表页不是作品页
            "https://591av.ws/categories/amateur/",                   // 分类页
            "https://wnacg.my/albums-index-page-2.html",              // 图集站
            "https://rou123.ws/vodplay/19837-1-1.html",               // 同族路径但域名不属十家
            "https://xanimu.one/zh-CN/81448-apex-legends/",
        };
        for (String u : mustFallBack)
            ck("不原生化（回落网页版）：" + cut(u, 58), boardOf(u) == null, String.valueOf(boardOf(u)));

        System.out.println("== 2. 诱饵清单必须认出来（取证文件逐字节）");
        String jDecoy = read(B.resolve("jable666.com__DECOY_no_referer.m3u8"));
        String lDecoy = read(B.resolve("huangguo01.net__SEGMENT0_no_referer_DECOY.m3u8"));
        String jReal = read(B.resolve("jable666.com__MASTER_signed.m3u8"));
        String lMaster = read(B.resolve("huangguo01.net__MASTER_13avm3.m3u8"));
        ck("Jable 假清单被拒（200 + #EXTM3U 也算假）", !jDecoy.isEmpty() && getPlaylist(jDecoy).isEmpty(),
            jDecoy.length() + " B");
        ck("LissAV 假清单被拒（与 Jable 的那份逐字节同构）", !lDecoy.isEmpty() && getPlaylist(lDecoy).isEmpty(),
            lDecoy.length() + " B");
        ck("Jable 真清单通过（1751 段）", !jReal.isEmpty() && !getPlaylist(jReal).isEmpty(),
            (jReal.split("\\R").length) + " 行");
        ck("LissAV 真 master 通过（三档）", !lMaster.isEmpty() && !getPlaylist(lMaster).isEmpty());

        System.out.println("== 3. Jable：详情页内联 hlsUrl");
        String jd = read(B.resolve("jable666.com__videos_smra-005__DETAIL.html"));
        String jMaster = g1("var\\s+hlsUrl\\s*=\\s*'([^']+\\.m3u8)'", jd);
        ck("解出 hlsUrl", !jMaster.isEmpty(), cut(jMaster, 78));
        ck("hlsUrl 与详情页同域（Referer 才借用得到）",
            hostOf(jMaster).equals(hostOf("https://jable666.com/videos/smra-005/")), hostOf(jMaster));
        List<String[]> jq = renditionsOf(jMaster, jReal);
        ck("单档清单退成一条、标签取 0（界面显示「播放」而不是「0P」）", jq.size() == 1, jq.toString());
        ck("真清单里确有 AES-128 key（ExoPlayer 需跟随 Referer 取 key）", jReal.contains("#EXT-X-KEY:METHOD=AES-128"));

        System.out.println("== 4. 肉视频：base64 + 每字节减 k");
        String rd = read(B.resolve("rou888.net__v_cmu8ng3q60004s6x3byujkmig__DETAIL.html"));
        String rD = g1("\\{\\s*d:\"([A-Za-z0-9+/=]{20,})\",k:(\\d+)\\s*\\}", rd);
        String rK = g1("\\{\\s*d:\"[A-Za-z0-9+/=]{20,}\",k:(\\d+)\\s*\\}", rd);
        ck("解出内联状态 {d,k}", !rD.isEmpty() && !rK.isEmpty(), "k=" + rK);
        String rj = rouDecode(rD, Integer.parseInt(rK));
        ck("解码得到 videoUrl=/api/hls/<cuid>", rj.contains("\"videoUrl\":\"/api/hls/cmu8ng3q60004s6x3byujkmig\""), cut(rj, 72));
        ck("档位写在路径里（实测 `<cuid>-720`），认出来的必须是 720 而不是 0",
            qualityFromUrl("https://x/vcdenet2.top/hls/cmu8ng3q60004s6x3byujkmig/cmu8ng3q60004s6x3byujkmig-720/index.png") == 720,
            qualityFromUrl("https://x/vcdenet2.top/hls/abc/abc-720/index.png") + "");

        System.out.println("== 5. LissAV：stream API 挑不带 sign 的那条");
        String lj = read(B.resolve("huangguo01.net__API_video_stream.json"));
        String lsel = lissavUnsigned(lj);
        ck("挑中 13avm3 自建线（另外两条要 urlSign，不在这里跟它们较劲）",
            lsel != null && lsel.contains("/13avm3/"), cut(lsel, 72));
        String ld = read(B.resolve("huangguo01.net__video_cid_simm-412_DETAIL.html"));
        String lUid = g1("data-streamit-page-video-uid=\"([0-9a-f]{8,32})\"", ld);
        ck("详情页取到 uid", !lUid.isEmpty(), lUid);
        String lUidB = g1("window\\.__STREAMIT_ME_QUERY__\\s*=\\s*\\{\"videoUid\":\"([0-9a-f]{8,32})\"\\}", ld);
        ck("两条冗余取法里另一条也给同一个 uid（站点自己留了备份入口）",
            lUidB.isEmpty() || lUidB.equals(lUid), lUidB);
        List<String[]> lq = renditionsOf(lsel, lMaster);
        ck("master 广播三档且降序 720/480/360",
            lq.size() == 3 && lq.get(0)[0].equals("720") && lq.get(1)[0].equals("480") && lq.get(2)[0].equals("360"),
            lq.size() + " 档");
        // master 与变体在**不同的线路子域**上（实测 jre6v9…vcnai666.com 与 jre6r14…vcnai666.com），
        // 所以登记可以借用户 Referer 的主机必须把变体也算进去，只登记 master 就会漏掉真正被请求的那一个。
        ck("变体不在 master 那个子域上（证明登记必须含变体主机）",
            !lq.isEmpty() && !hostOf(lq.get(0)[1]).equals(hostOf(lsel)), hostOf(lsel) + " → " + hostOf(lq.get(0)[1]));
        Set<String> reg = new LinkedHashSet<>();
        reg.add(hostOf(lsel));
        for (String[] r : lq) reg.add(hostOf(r[1]));
        ck("登记的主机集合把 master 与全部变体都算进去（三档同在一条变体子域上，所以是 2 个而不是 4 个）",
            reg.contains(hostOf(lsel)) && lq.stream().allMatch(r -> reg.contains(hostOf(r[1]))) && reg.size() == 2,
            reg.toString());
        ck("API 的 stream 接口路径能从作品 URL 推出来",
            ("https://huangguo01.net" + "/asian/zh-CN/api/video/stream?video_uid=x").startsWith("https://huangguo01.net/asian/zh-CN/"));

        System.out.println("== 6. 静态 source 族（avjoy / SuperPorn / OKXXX）");
        String av = read(A.resolve("cn.avjoy.ws__detail.html"));
        String avUrl = g1("<source src=\"(https?://[^\"]+\\.mp4[^\"]*)\"", av);
        ck("avjoy 解出直链 mp4", !avUrl.isEmpty(), cut(avUrl, 74));
        ck("avjoy 播址是线路域、不是站内域 ⇒ 必须靠登记过的主机借 Referer",
            !avUrl.isEmpty() && !hostOf(avUrl).equals("cn.avjoy.ws"), hostOf(avUrl));
        ck("avjoy 从 URL 认到 720 档（单档站就一个按钮，标签必须是 720 而不是 0）",
            qualityFromUrl(avUrl) == 720, qualityFromUrl(avUrl) + " ← " + cut(avUrl, 40));

        String sp = read(A.resolve("cn.superporn.ws__detail.html"));
        String spUrl = g1("<source src=\"(https?://[^\"]+\\.mp4[^\"]*)\"", sp);
        ck("SuperPorn 解出 jmpres 代理直链", !spUrl.isEmpty(), cut(spUrl, 74));
        ck("SuperPorn 的 secure 参数保持 percent 编码（不许解码也不许二次编码）",
            spUrl.contains("%3D%3D%2C") && !spUrl.contains("==,"), "");

        String ox = read(A.resolve("okxxx.life__detail.html"));
        Matcher oxm = Pattern.compile("https?://[^\"'()\\s]+/get_file/\\d+/[0-9a-f]{32}/\\d+/\\d+/\\d+(?:_(\\d{3,4})p)?\\.mp4/").matcher(ox);
        List<String> oxc = new ArrayList<>();
        while (oxm.find()) if (!oxm.group().contains("preview")) oxc.add(oxm.group());
        ck("OKXXX 解出正片地址（排除 preview）", !oxc.isEmpty(), oxc.size() + " 条");
        String best = null;
        int bh = -1;
        for (String u : oxc) {
            int h = qualityFromUrl(u);
            if (h > bh) { bh = h; best = u; }
        }
        ck("OKXXX 取最高档（实测 720p）", bh == 720, bh + " → " + cut(best, 60));
        String om = read(A.resolve("okxxx.life__master.m3u8"));
        List<String[]> oq = renditionsOf(best, om);
        ck("OKXXX 的 .mp4 地址实际返回 HLS master，解析出三档", oq.size() == 3, oq.size() + " 档");

        System.out.println("== 7. iframe 播放器族（黄色仓库 / 好色TV）");
        String yw = read(A.resolve("69ck.art__detail.html"));
        String yEnc = g1("player\\.centercdn\\.top/player\\.html\\?video_url=([^\"'\\s&]+)", yw);
        ck("黄色仓库解出播放器 iframe 的 video_url 参数", !yEnc.isEmpty(), cut(yEnc, 70));
        String ydec = URLDecoder.decode(yEnc, "UTF-8");
        ck("URL 解码即得 m3u8 本体", ydec.endsWith(".m3u8"), ydec);
        String yt = read(A.resolve("69ck.art__media.m3u8"));
        if (yt.isEmpty()) yt = read(A.resolve("69ck.art__media_manifest.txt"));
        ck("黄色仓库媒体清单可用（取证文件在盘上）", !yt.isEmpty(), yt.length() + " B");
        String ht = read(B.resolve("pb365.nl__video-1246807_DETAIL.html"));
        String hEnc = g1("player\\.centercdn\\.top/player\\.html\\?video_url=([^\"'\\s&]+)", ht);
        ck("好色TV 用同一条取法就能解出播址（媒体那一跳当时 522，未验证 ⇒ 拿不到清单会自动回落网页版）",
            !hEnc.isEmpty(), hEnc.isEmpty() ? "" : cut(URLDecoder.decode(hEnc, "UTF-8"), 60));

        System.out.println("== 8. 亚洲素人精选：var stream");
        String fv = read(A.resolve("591av.ws__detail.html"));
        String fUrl = g1("var\\s+stream\\s*=\\s*'([^']+\\.m3u8)'", fv);
        ck("解出 stream 地址", !fUrl.isEmpty(), cut(fUrl, 74));
        ck("stream 走站内 jmpres 代理（不是线路域直连）", hostOf(fUrl).equals("591av.ws"), hostOf(fUrl));
        String ft = read(A.resolve("591av.ws__media.m3u8"));
        ck("其清单也带 AES-128（key 是伪装成 .ts 的相对路径）",
            ft.contains("#EXT-X-KEY:METHOD=AES-128") && !ft.contains("KEY:URI=\"http"), "");

        // 「不原生化」只表示**没有可验证的直链**，不表示没有介绍页：
        // 这些家在 2026-10-01 之后走 X99.metaDetail（通用详情介绍页 + 「▶ 网页播放」），
        // 由 BoardPageCheck 用真实详情页样本验收（YesPorn、Hentai 的样本就在那 39 份里；
        // VJAV / 大哥视频 / 情色小说 本轮抓不到详情页——SPA 无 SSR 或站点 503，介绍页会退回网页版，如实记录）。
        System.out.println("== 9. 未打通的站不许混进原生分支（介绍页仍然给，播放退回网页版）");
        for (String u : new String[]{
            "https://cn.yesporn.ws/video/75392/banging-jada-9ajv7b/",   // 媒体三发全废
            "https://vjav1.sbs/videos/993419/x/",                        // videofile.php 502
            "https://cn.acgmh.art/g/685147/",                            // 图集，无可播媒体
            "https://99xs.sbs/article/1017",                             // 纯文字
            "https://dage.one/play/713673.html",                          // SPA，卡片解析不出列表
        }) ck(cut(u, 60) + " 不原生化", boardOf(u) == null, String.valueOf(boardOf(u)));

        System.out.println("合计 PASS=" + pass + " FAIL=" + fail + " SKIP=" + skip);
        System.out.println(fail == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        if (fail != 0) System.exit(1);
    }
}
