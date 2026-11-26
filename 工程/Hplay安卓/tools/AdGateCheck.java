// Hplay 安卓版 · 广告闸门离线验收器（与运行期同一套语义；改规则或改闸门后必跑）
//   编译：javac -encoding UTF-8 -d . AdGateCheck.java   （JDK 用 工具/jdk-temurin/jdk-21.0.12.1+1）
//   运行：java -Dstdout.encoding=UTF-8 AdGateCheck <规则文件> [实测视频页样本]
//     规则文件 = 工程/AvPlay/独立版/hplay-adrules.txt（Android 构建时同源复制进 assets）
//     第二个参数是可选取证项：不给样本时那三项记 SKIP，不影响 RESULT
//   **计数口径**：不给样本 = PASS 少 3（样本取证那 3 项记 SKIP）；带样本的全部条目见末尾自报合计。
//   本仓实测命令与结果（两条都要跑，别只跑带样本那条就当"验收可复放"）：
//     java … AdGateCheck <规则文件>                      → 自报合计（样本项 SKIP）
//     java … AdGateCheck <规则文件> <实测视频页 HTML>     → 自报合计（样本项计入）
//   样本文件在 gitignore 的 临时/ 下，清理后即不可复放——取证项因此不计入闸门语义结论。
//   判定口径：广告必须被拦；站点与媒体链路必须放行；规则缺失时必须「不拦」而不是「全拦」
//   语义来源：AdRules.kt（解析与 cssFor）、AndroidWeb.kt（三层闸门与 mainHost 认领）、Han1meWeb.kt（adsRegexCapped）
//   覆盖范围：本器复刻判定逻辑（谓词 + 分支），能复核下面 A/B/C 全部结论；
//   不能替代真机——Uri.parse 对协议相对 //host 与 IPv6 的解析、WebResourceResponse 各重载在 Android 7
//   上的实际行为、以及"点播放键是否真的起播"只能在真机验证。
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class AdGateCheck {
    static List<String> generic = new ArrayList<>();
    static List<String[]> byDomain = new ArrayList<>();
    static String adsRegex = "";
    static int pass = 0, fail = 0, skip = 0;

    static void load(Path p) throws IOException {
        for (String line0 : new String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\r?\n")) {
            String line = line0.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("!")) {
                if (line.startsWith("!ads=")) adsRegex = line.substring(5).trim();
                continue;
            }
            int i = line.indexOf("##");
            if (i < 0) generic.add(line);
            else byDomain.add(new String[]{line.substring(0, i), line.substring(i + 2)});
        }
    }

    static String cssFor(String host) {
        List<String> sel = new ArrayList<>(generic);
        String h = host.toLowerCase();
        for (String[] e : byDomain) {
            for (String seg : e[0].split(",")) {
                String d = seg.trim().toLowerCase();
                if (!d.isEmpty() && (h.endsWith(d) || h.contains(d))) { sel.add(e[1]); break; }
            }
        }
        return String.join(",", sel);
    }

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    static void skipped(String what) { skip++; System.out.println("  SKIP  " + what); }

    /** 约等于 Uri.parse(u).host（http(s) 形态）；协议相对与 IPv6 见文件头覆盖范围声明 */
    static String host(String u) {
        try {
            String s = u.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
            String[] cuts = {"/", "?", "#"};
            for (String c : cuts) { int i = s.indexOf(c); if (i >= 0) s = s.substring(0, i); }
            int i = s.indexOf('@'); if (i >= 0) s = s.substring(i + 1);
            i = s.indexOf(':'); if (i >= 0) s = s.substring(0, i);
            return s.toLowerCase();
        } catch (Exception e) { return ""; }
    }

    /** = adPattern()：空或坏规则 → null（不拦）；生产只在拿到非空 Pattern 后才缓存 */
    static Pattern pattern(String rx) {
        if (rx == null) return null;
        String t = rx.trim();
        if (t.isEmpty()) return null;
        try { return Pattern.compile(t); } catch (PatternSyntaxException e) { return null; }
    }

    static boolean isAdHost(String u, Pattern p) {
        if (p == null) return false;
        String h = host(u);
        return !h.isEmpty() && p.matcher(h).find();
    }

    /** = registrable()：取末两段（本工作目录站点域名均为「单标签后缀 + 主机」形态，无需 PSL 表） */
    static String registrable(String h) {
        String[] parts = h.split("\\.");
        return parts.length <= 2 ? h : parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    static boolean sameSite(String u, String pageHost, Pattern p) {
        if (pageHost.isEmpty()) return false;
        String h = host(u);
        return !h.isEmpty() && registrable(h).equals(registrable(pageHost));
    }

    static boolean adSignature(String l) {
        String path = l;
        int i = path.indexOf('#'); if (i >= 0) path = path.substring(0, i);
        i = path.indexOf('?'); if (i >= 0) path = path.substring(0, i);
        if (path.contains("erebyad") || path.contains("popunder")) return true;
        if ((path.endsWith("/pop") || path.contains("/pop/")) && l.contains("url=")) return true;
        return l.contains("rsrc=popscript");
    }

    /** 约等于 android.net.Uri.decode：只还原 %XX，不动加号，坏百分号原样保留、不抛 */
    static String uriDecode(String u) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < u.length(); ) {
            char c = u.charAt(i);
            if (c == '%' && i + 2 < u.length() && isHex(u.charAt(i + 1)) && isHex(u.charAt(i + 2))) {
                sb.append((char) Integer.parseInt(u.substring(i + 1, i + 3), 16));
                i += 3;
            } else { sb.append(c); i++; }
        }
        return sb.toString();
    }

    static boolean isHex(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }

    static boolean isAdHijack(String u, String pageHost, Pattern p) {
        String l = u.toLowerCase();
        if (adSignature(l)) return true;
        if (!sameSite(u, pageHost, p) && isAdHost(u, p)) return true;
        String dec = uriDecode(u).toLowerCase();
        if (!dec.isEmpty() && !dec.equals(l)) {
            if (adSignature(dec)) return true;
            if (!sameSite(dec, pageHost, p) && isAdHost(dec, p)) return true;
        }
        return false;
    }

    static boolean isAdEscaped(String u, Pattern p) {
        if (p == null) return false;
        return p.matcher(uriDecode(u)).find();
    }

    /** = AndroidWeb.isMediaUrl：媒体形态判定（只看路径，query 里的 token 不参与） */
    static boolean isMediaUrl(String u) {
        String path = u.toLowerCase();
        int i = path.indexOf('#'); if (i >= 0) path = path.substring(0, i);
        i = path.indexOf('?'); if (i >= 0) path = path.substring(0, i);
        if (path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".ts") || path.endsWith(".key")) return true;
        if (path.contains("/jmpres/") || path.contains("/hls/")) return true;
        return path.matches(".+/(480|720|1080|2160)p/?$") || path.endsWith("/playlist") || path.endsWith("/master");
    }

    /** 五参版＝「此前没有任何媒体请求」的干净会话（每断言自带隔离，别共享白名单）。 */
    static String intercept(String u, boolean forMainFrame, boolean isRedirect, String mainHost, Pattern p) {
        // referer 缺省给本页自身：不影响「同站发起」那条，只让不带来源的重定向照常登记（老行为）
        return intercept(u, forMainFrame, isRedirect, "https://" + mainHost + "/", mainHost, p, new HashSet<String>());
    }

    /** 六参版（显式 referer）：三审把白名单收紧成「重定向必须来自本页站点」后，referer 就是判据的一部分。 */
    static String intercept(String u, boolean forMainFrame, boolean isRedirect, String referer,
                            String mainHost, Pattern p, java.util.Set<String> mediaHosts) {
        // 与运行期一致：**主文档不参与登记**（否则广告域把落地页写成媒体路径就自封通行证）
        if (isMediaUrl(u) && !mainHost.isEmpty() && !forMainFrame) {
            String h = host(u);
            String rh = host(referer);
            boolean fromPage = !rh.isEmpty() && registrable(rh).equals(registrable(mainHost));
            if (sameSite(u, mainHost, p) || (isRedirect && fromPage)) mediaHosts.add(h);
        }
        if (sameSite(u, mainHost, p) || !isAdHost(u, p)) return null;
        if (forMainFrame) {
            if (isMediaUrl(u) && mediaHosts.contains(host(u))) return null;
            return isRedirect ? "BLANK" : null;
        }
        return isMediaUrl(u) && mediaHosts.contains(host(u)) ? null : "404";
    }

    /** 旧五参形态（referer 固定为本页）：只给不关心 referer 路径的断言用。 */
    static String intercept(String u, boolean forMainFrame, boolean isRedirect, String mainHost,
                            Pattern p, java.util.Set<String> mediaHosts) {
        return intercept(u, forMainFrame, isRedirect, "https://" + mainHost + "/", mainHost, p, mediaHosts);
    }

    /**
     * = AndroidWeb.shouldInterceptRequest 的决策：null=放行，"404"=子资源空响应，"BLANK"=主文档空白页。
     *
     * mediaHosts 是运行期的媒体白名单：**只有「同站发起」或「被重定向带出」的媒体请求**才会把 host
     * 记进来（noteMediaHost 的等价），进来之后该 host 的媒体形态请求继续放行、非媒体形态照样过表。
     * 一审指出的绕过面——广告域只要把脚本 URL 写成 /hls/payload.ts 就躲过一切判定——靠这个范围收窄关掉：
     * classic script 只看响应 MIME 不看扩展名，所以无条件按形态放行等于重开弹屏脚本通道。
     * 主文档判定也必须排在豁免之前，否则广告落地页只要长个媒体路径就能整页载入。
     */


    /** = HplayWebState.claimMainHost：换站（registrable 变化）时白名单必须整张作废 */
    static java.util.Set<String> claimAndHosts(String prevMainHost, java.util.Set<String> hosts, String newHost) {
        if (newHost.isEmpty()) return hosts;
        if (!registrable(prevMainHost).equals(registrable(newHost))) hosts.clear();
        return hosts;
    }

    /** = shouldOverrideUrlLoading 的主文档否决 */
    static boolean vetoMain(String u, String mainHost, Pattern p) { return isAdHijack(u, mainHost, p); }

    /** = mainHost 的写入点（构造 / navigator.loadUrl / shouldOverrideUrlLoading / onPageStarted 条件写入） */
    static String claimHost(String current, String url, Pattern p) {
        if (vetoMain(url, current, p)) return current;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return current; // 非 http(s) 不认领
        return host(url);
    }

    /** = Han1meWeb.kt adsRegexCapped */
    static String adsRegexCapped(String src, int cap) {
        if (src.length() <= cap) return src;
        int cut = src.lastIndexOf('|', cap);
        return cut > 0 ? src.substring(0, cut) : "";
    }

    public static void main(String[] args) throws Exception {
        load(Paths.get(args[0]));
        Pattern p = pattern(adsRegex);
        boolean hasSample = args.length > 1;
        String MISSAV = "www.missav888.cc";
        System.out.println("规则: 通用 " + generic.size() + " / 按域名 " + byDomain.size() + " / adsRegex " + adsRegex.length() + " 字符");

        System.out.println("== A 规则数据 ==");
        check("adsRegex 未超 1500 注入上限", adsRegex.length() <= 1500);
        check("adsRegex 编译成功", p != null);
        check("空规则 → pattern() 返回 null（不抛）", pattern("") == null && pattern("   ") == null);
        check("坏规则 → pattern() 返回 null", pattern("[unclosed") == null);
        check("通用规则含点击劫持层隐藏", cssFor(MISSAV).contains("[data-cl-overlay]"));
        check("missav 域名规则含 herebyad 广告位", cssFor(MISSAV).contains("erebyad"));
        check("njav 线路同样命中（域名表已换 .cc/.my/.one 世代）", cssFor("www.njavtv.my").contains("erebyad"));
        check("非 missav 站点不带 herebyad 规则", !cssFor("cn.pornhub163.com").contains("erebyad"));
        if (hasSample) {
            String sample = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
            check("实测视频页确有 herebyad 广告位", sample.toLowerCase().contains("erebyad"));
            check("实测视频页确有 wheelroot 弹屏目标", sample.contains("wheelroot.com"));
            check("实测视频页确有 myavlive 弹屏目标", sample.toLowerCase().contains("myavlive.com"));
        } else {
            skipped("样本取证 3 项（未给第二个参数：实测视频页）");
        }

        System.out.println("== B 主文档否决：必须拦 ==");
        String[] veto = {
            "https://horribletrainer.com/bL3.VM?sseq=1&dseq=1&rsrc=popscript_popunder.",
            "https://animeplayhub.com/lg/lg_0424/land/?haff_pid=1034",
            "https://a.magsrv.com/ad-provider.js",
            "https://www.missav888.cc/pop?url=https%3A%2F%2Fwheelroot.com%2Fapi%2Fv1%2Fdirect%2Fc65c80b4",
            "https://www.missav888.cc/pop/1?url=x",
            "https://www.missav888.cc/herebyad4",
            "https://www.missav888.cc/HereByAD1",
        };
        for (String u : veto) check("否决: " + cut(u), vetoMain(u, MISSAV, p));

        System.out.println("== B 主文档否决：必须放行 ==");
        String[] allow = {
            "https://www.missav888.cc/cn/kira-011-2",
            "https://www.missav888.cc/dm539/cn/new",
            "https://www.missav888.cc/cn/chinese-subtitle?page=2",
            "https://www.missav888.cc/cn/search/%E6%BA%AA?query=%E7%86%9F%E5%A5%B3",
            "https://www.missav888.cc/cn/actresses/%E6%9F%B3%E7%94%B0%E5%92%8C%E7%BE%8E",
            "https://www.missav888.cc/cn/favorites",
            "https://www.missav888.cc/cn/popular",
            "https://www.missav888.cc/en/pops-guide",
            "https://www.missav888.cc/cn/search/x?kw=popunder-leak",
            "https://cdnjs.missav888.cc/ajax/libs/plyr/3.6.8/plyr.min.js",
            "https://www.missav888.cc/view_booster.js?v=21",
            "https://spic2-147.71352.men/kira-011-2/cover-n.jpg",
            "https://www.hanime1.me/search?query=abc",
        };
        for (String u : allow) check("放行: " + cut(u), !vetoMain(u, MISSAV, p));

        System.out.println("== B intent 外壳：外抛前否决（isAdEscaped 正向覆盖） ==");
        String intentEnc = "intent://scan/#Intent;scheme=https;package=com.android.chrome;S.browser_fallback_url=https%3A%2F%2Fhorribletrainer.com%2Fx;end";
        String intentPlain = "intent://scan/#Intent;scheme=https;S.browser_fallback_url=https://a.magsrv.com/ad-provider.js;end";
        check("intent（编码广告域）→ isAdEscaped", isAdEscaped(intentEnc, p));
        check("intent（未编码广告域）→ isAdEscaped", isAdEscaped(intentPlain, p));
        check("intent 不认领 mainHost", claimHost(MISSAV, intentEnc, p).equals(MISSAV));
        check("uriDecode 只还原 %XX、不动加号", uriDecode("a+b%20c").equals("a+b c") && uriDecode("100%").equals("100%"));

        System.out.println("== B 子资源层：404 / 放行 ==");
        String[] subBlock = {"https://a.magsrv.com/ad-provider.js", "https://horribletrainer.com/b.x",
            "https://frozenpayerpregnant.com/bn.js", "https://www.quizzical-topic.com/a.js",
            "https://footbathmockerpurse.com/aas/x.js", "https://acmexxx.com/tracker.js",
            "https://magsrv.com/x.js", "https://cdn.magsrv.com/x.js"};
        for (String u : subBlock) check("404 子资源: " + cut(u), "404".equals(intercept(u, false, false, MISSAV, p)));
        // 注：后两条（spic2-…men 的 .ts / master.m3u8）**本来就不在广告域名表里**，删掉媒体豁免也照样放行，
        // 它们测的是「非表内域」而不是豁免逻辑——媒体豁免的真实覆盖在下面「白名单」一组断言里。
        String[] subAllow = {"https://www.missav888.cc/cn/kira-011-2", "https://cdnjs.missav888.cc/ajax/libs/hls.js/1.4.3/hls.min.js",
            "https://spic2-147.71352.men/seg-1.ts", "https://spic2-147.71352.men/kira-011-2/master.m3u8",
            "https://www.missav888.cc/view_booster.js?v=21"};
        for (String u : subAllow) check("放行子资源(与豁免无关): " + cut(u), intercept(u, false, false, MISSAV, p) == null);
        check("表内内容站作为本站首屏资源时放行（同站豁免）", intercept("https://4tube.com/app.js", false, false, "4tube.com", p) == null);
        check("表内内容站作为第三方资源时仍 404", "404".equals(intercept("https://4tube.com/app.js", false, false, MISSAV, p)));
        check("4tube 页面里的第三方广告仍 404", "404".equals(intercept("https://a.magsrv.com/x.js", false, false, "4tube.com", p)));
        System.out.println("== B 媒体链路豁免（轮换域撞上表不误杀，但不给广告域开形态后门） ==");
        Set<String> wl = new HashSet<>();
        check("同站媒体请求先登记（jmpres 代理路径）",
            intercept("https://www.missav888.cc/jmpres/surrit.com/x/1080p/video.m3u8", false, false, MISSAV, p, wl) == null
                && wl.contains("www.missav888.cc"));
        check("302 带到表内轮换域：媒体形态放行且登记",
            intercept("https://magsrv.com/seg/x/master.m3u8", false, true, MISSAV, p, wl) == null && wl.contains("magsrv.com"));
        check("登记后分片直连请求（已非重定向）仍放行",
            intercept("https://magsrv.com/seg-9.ts", false, false, MISSAV, p, wl) == null);
        check("登记后密钥请求仍放行", intercept("https://magsrv.com/key/1.key", false, false, MISSAV, p, wl) == null);
        check("登记后清晰度目录仍放行", intercept("https://magsrv.com/720p", false, false, MISSAV, p, wl) == null);
        check("白名单 host 的广告脚本仍 404（豁免只给媒体形态）",
            "404".equals(intercept("https://magsrv.com/ad-provider.js", false, false, MISSAV, p, wl)));
        check("绕过面已关：表内域凭空冒出的 .ts 子资源仍 404",
            "404".equals(intercept("https://magsrv.com/hls/payload.ts", false, false, MISSAV, p, new HashSet<>())));
        check("绕过面已关：主文档里未经授权的广告域媒体路径照样 BLANK",
            "BLANK".equals(intercept("https://magsrv.com/jmpres/x/master.m3u8", true, true, MISSAV, p, new HashSet<>())));
        check("主文档豁免回归：已授权的本站媒体链 302 不被误杀（三审指出换序会杀掉这条）",
            intercept("https://magsrv.com/hls/x/master.m3u8", true, true, MISSAV, p, wl) == null);
        check("白名单不外溢：另一表内域的媒体请求仍 404",
            "404".equals(intercept("https://frozenpayerpregnant.com/seg/1.m3u8", false, false, MISSAV, p, new HashSet<>(Arrays.asList("magsrv.com")))));
        // 「撞上表」必须用**真的在表里**的域：vckeepw.top / vcsheaye.cc / surrit.com 都不在 adsRegex，
        // 拿它们测豁免等于让 !isAdHost 先早退，删掉整条白名单也照 PASS（上一段刚写下这个坑，不能自己再踩）。
        check("表内轮换域的真实播放链不误杀（同站发起 → 重定向带出 → 直连分片）",
            intercept("https://magsrv.com/hls/a/master.m3u8", false, true, MISSAV, p, wl) == null
                && intercept("https://magsrv.com/seg-3.ts", false, false, MISSAV, p, wl) == null);
        check("三审污染面已关：广告页自己的 302（referer 非本站）进不了白名单",
            "404".equals(intercept("https://magsrv.com/hls/payload.ts", false, true,
                "https://horribletrainer.com/b.html", MISSAV, p, new HashSet<>())));
        check("三审污染面已关：白名单是**按页**的，换站后旧授权作废",
            intercept("https://magsrv.com/seg-9.ts", false, false, MISSAV, p, wl) == null
                && "404".equals(intercept("https://magsrv.com/seg-9.ts", false, false, "cn.pornhub163.com", p,
                    new HashSet<>())));

        System.out.println("== B 主文档 3xx 侧门：BLANK / 首屏与链接不误杀 ==");
        check("3xx 进广告域 → BLANK", "BLANK".equals(intercept("https://a.magsrv.com/land", true, true, MISSAV, p)));
        check("首屏 loadUrl 到表内内容站 → 不 BLANK", intercept("https://4tube.com/index.html", true, false, "4tube.com", p) == null);
        check("站内导航（本站域）→ 不 BLANK", intercept("https://www.missav888.cc/cn/new", true, false, MISSAV, p) == null);

        System.out.println("== B 同站判定（registrable） ==");
        check("兄弟子域算同站", sameSite("https://cdnjs.missav888.cc/a.js", MISSAV, p));
        check("裸主域算同站", sameSite("https://missav888.cc/a.js", MISSAV, p));
        check("后缀逃逸门已关（evil.888.cc 不算同站）", !sameSite("https://evil.888.cc/a.js", MISSAV, p));
        check("媒体 CDN（71352.men）与本站不同站", !sameSite("https://spic2-147.71352.men/x.ts", MISSAV, p));

        System.out.println("== B mainHost 认领 ==");
        String mh = claimHost(MISSAV, "https://www.missav888.cc/cn/abc", p);
        check("站内导航认领本站", mh.equals("www.missav888.cc"));
        mh = claimHost(mh, "https://horribletrainer.com/x?sseq=1&rsrc=popscript_popunder", p);
        check("广告跳转不认领", mh.equals("www.missav888.cc"));
        mh = claimHost(mh, "https://www.missav888.cc/pop?url=x", p);
        check("站点自有弹屏路由不改写认领值", mh.equals("www.missav888.cc"));
        mh = claimHost(mh, "https://THISAV01.CC/cn/new", p);
        check("大写域名归一为小写", mh.equals("thisav01.cc"));

        System.out.println("== B 退化安全：规则缺失必须「不拦」 ==");
        check("空规则 → 子资源不拦", intercept("https://a.magsrv.com/x.js", false, false, MISSAV, null) == null);
        check("空规则 → 主文档不否决", !vetoMain("https://a.magsrv.com/x.js", MISSAV, null));
        check("空规则 → 3xx 不给 BLANK", intercept("https://a.magsrv.com/x.js", true, true, MISSAV, null) == null);
        check("空规则 → intent 不外抛否决", !isAdEscaped("intent://x;S.browser_fallback_url=https%3A%2F%2Fa.magsrv.com", null));
        check("写法特征独立于域名表仍生效", vetoMain("https://www.missav888.cc/pop?url=x", MISSAV, null));
        check("query 里的 popunder 不误伤路径签名", !vetoMain("https://www.missav888.cc/cn/search/x?kw=popunder", MISSAV, null));

        System.out.println("== C 正则截断边界（Han1meWeb.adsRegexCapped） ==");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append(i > 0 ? "|" : "").append("dom").append(i).append(".com");
        String capped = adsRegexCapped(sb.toString(), 1500);
        check("截断结果不以竖线结尾", !capped.endsWith("|"));
        check("截断结果是完整条目", capped.split("\\|")[capped.split("\\|").length - 1].matches("dom\\d+\\.com"));
        check("截断后可编译", pattern(capped) != null);
        check("不超长时原样返回", adsRegexCapped(adsRegex, 1500).equals(adsRegex));

        System.out.println("合计 PASS=" + pass + " FAIL=" + fail + " SKIP=" + skip);
        System.out.println(fail == 0 ? "RESULT: PASS" : "RESULT: FAIL(" + fail + ")");
        if (fail != 0) System.exit(1);
    }

    static String cut(String u) { return u.length() > 62 ? u.substring(0, 62) + "..." : u; }
}
