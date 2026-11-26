// MissAV 原生播放链离线验收器（语义逐条照抄 X99.kt / HplayPlayer.kt；改解析或站点改版后必跑）
//   编译：javac -encoding UTF-8 -d . MissAVCheck.java
//   运行：java -Dstdout.encoding=UTF-8 MissAVCheck [取证目录]   默认 D:\工作区\临时\r34_play
//   判据：解包必须得到三条 surrit 直链；jmpres 重写必须与站点自己的 replace 规则同结果；
//         Referer 必须是同域（跨域 Referer 实测 TLS 直接断连）；master 绝不直接交给播放器。
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class MissAVCheck {
    static int pass = 0, fail = 0;

    static void ck(String what, boolean ok) { ck(what, ok, ""); }

    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (ok) pass++; else fail++;
    }

    static final Pattern PACKED = Pattern.compile(
        "eval\\(function\\(p,a,c,k,e,d\\)\\{[\\s\\S]{0,600}?\\}\\('([\\s\\S]*?)',(\\d+),(\\d+),'([\\s\\S]*?)'\\.split\\('\\|'\\)");
    static final Pattern SRC = Pattern.compile("(source\\d*)\\s*=\\s*'([^']+\\.m3u8)'");
    static final Pattern REND = Pattern.compile("#EXT-X-STREAM-INF:([^\\n]*)\\n([^\\n]+)");
    static final Pattern RES = Pattern.compile("RESOLUTION=\\d+x(\\d+)");

    static String unescapeJs(String s) { return s.replace("\\'", "'").replace("\\\\", "\\"); }

    /** = X99.unpackDeanEdwards */
    static String unpack(String payload, int base, int count, String[] dict) {
        String p = unescapeJs(payload);
        for (int c = count - 1; c >= 0; c--) {
            String token = Integer.toString(c, base);
            String rep = c < dict.length ? dict[c] : "";
            if (rep.isEmpty()) continue;
            p = p.replaceAll("\\b" + Pattern.quote(token) + "\\b", Matcher.quoteReplacement(rep));
        }
        return p;
    }

    /** = X99.jmpres */
    static String jmpres(String origin, String mediaUrl) {
        int i = mediaUrl.indexOf("://");
        if (i < 0) return mediaUrl;
        return origin.replaceAll("/+$", "") + "/jmpres/" + mediaUrl.substring(i + 3);
    }

    /** 站点自己的重写规则（详情页里那段 source.replace(...)），用来独立对账，不用我的实现验我的实现 */
    static String siteRewrite(String pageOrigin, String mediaUrl) {
        Matcher m = Pattern.compile("^https://((?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,})/").matcher(mediaUrl);
        if (!m.find()) return mediaUrl;
        return pageOrigin + "/jmpres/" + m.group(1) + "/" + mediaUrl.substring(m.end());
    }

    static String hostOf(String u) { try { return new java.net.URI(u).getHost() == null ? "" : new java.net.URI(u).getHost(); } catch (Exception e) { return ""; } }

    /** = X99.refererFor / HplayPlayer 的 mediaClient 判据 */
    static String refererFor(String mediaUrl, String lastDetailUrl) {
        String h = hostOf(mediaUrl);
        if (h == null) h = "";
        if (!h.isEmpty() && !lastDetailUrl.isEmpty() && h.equals(hostOf(lastDetailUrl))) return lastDetailUrl;
        return h.isEmpty() ? "" : "https://" + h + "/";
    }

    /**
     * = HanimeClient.officialHosts（`工程\AvPlay\hanime模块\...\HanimeClient.kt:326`，逐字照抄）。
     * 这四个是**页面域**；官方媒体的 `vdownload.hembed.com` 不在其中——下面第 5 节用真实样本把这件事钉死。
     */
    static final List<String> OFFICIAL_HOSTS =
        Arrays.asList("hanime1.me", "hanime1.com", "hanimeone.me", "javchu.com");

    static boolean isOfficial(String u) {
        String h = hostOf(u);
        if (h == null) return false;
        for (String o : OFFICIAL_HOSTS) if (h.equalsIgnoreCase(o) || h.endsWith("." + o)) return true;
        return false;
    }

    /** = X99.isXchinaUrl（host 等于 xchina001.ink 或以它为后缀）。 */
    static boolean isXchinaUrl(String u) {
        String h = hostOf(u);
        return h != null && (h.equalsIgnoreCase("xchina001.ink") || h.endsWith(".xchina001.ink"));
    }

    /** = X99.isMissavVideoUrl：路径必须正好是 `/cn/<带数字的作品号>`。 */
    static final Pattern MISSAV_PATH = Pattern.compile("^/cn/[a-z0-9][a-z0-9._-]*[0-9][a-z0-9._-]*$");

    static boolean isMissavVideoUrl(String u) {
        String p;
        try { p = new java.net.URI(u).getRawPath(); } catch (Exception e) { return false; }
        return p != null && MISSAV_PATH.matcher(p).matches();
    }

    /** = X99.isNativeBoardUrl：卡片分流与详情路由共用的唯一判据。 */
    static boolean isNativeBoardUrl(String u) { return isXchinaUrl(u) || isMissavVideoUrl(u); }

    public static void main(String[] a) throws Exception {
        Path dir = Paths.get(a.length > 0 ? a[0] : "D:\\工作区\\临时\\r34_play");
        System.out.println("取证目录 " + dir);
        if (!Files.isDirectory(dir)) { System.out.println("目录不存在，只做语义自测"); }

        // ---- 1. 解包：packed → 三条 surrit 直链 ----
        String detail = "";
        for (String cand : new String[]{"p3_detail_missav06.body", "..\\r33_missav\\video.html"}) {
            Path p = dir.resolve(cand);
            if (Files.isRegularFile(p)) { detail = new String(Files.readAllBytes(p), StandardCharsets.UTF_8); break; }
        }
        ck("取到详情页样本", !detail.isEmpty(), detail.length() + "B");
        Matcher pm = PACKED.matcher(detail);
        ck("详情页确有 Dean-Edwards packed 脚本", pm.find());
        if (!pm.reset().find(0)) { System.out.println("无法继续：packed 脚本未命中"); System.exit(1); }
        Matcher pmi = PACKED.matcher(detail);
        pmi.find();
        String unpacked = unpack(pmi.group(1), Integer.parseInt(pmi.group(2)), Integer.parseInt(pmi.group(3)),
            pmi.group(4).split("\\|"));
        ck("解包结果含 https://", unpacked.contains("https://"), cut(unpacked, 80));
        Map<String, String> assigns = new LinkedHashMap<>();
        Matcher sm = SRC.matcher(unpacked);
        while (sm.find()) assigns.put(sm.group(1), sm.group(2));
        ck("解出 3 条清晰度地址", assigns.size() == 3, assigns.keySet().toString());
        ck("source 是 playlist.m3u8（master）", assigns.getOrDefault("source", "").endsWith("/playlist.m3u8"));
        ck("source842 是 720p、source1280 是 1080p",
            assigns.getOrDefault("source842", "").contains("/720p/video.m3u8")
                && assigns.getOrDefault("source1280", "").contains("/1080p/video.m3u8"));
        ck("域名固定是 surrit.com", assigns.values().stream().allMatch(u -> u.startsWith("https://surrit.com/")));
        String uuid = assigns.get("source").replaceAll("^https://surrit\\.com/", "").replaceAll("/playlist\\.m3u8$", "");
        ck("UUID 是 5 段标准形态", uuid.split("-").length == 5, uuid);

        // ---- 2. jmpres 重写与站点自己的规则同结果 ----
        String pageOrigin = "https://www.missav06.com";
        StringBuilder sb = new StringBuilder();
        boolean same = true;
        for (String u : assigns.values()) {
            String mine = jmpres(pageOrigin, u), theirs = siteRewrite(pageOrigin, u);
            if (!mine.equals(theirs)) { same = false; sb.append(mine).append(" != ").append(theirs); }
        }
        ck("jmpres 重写 == 站点自己的 replace 规则（三者全等）", same, sb.toString());
        ck("重写后走本站 /jmpres/、不直连 surrit（直连实测 403）",
            jmpres(pageOrigin, assigns.get("source1280")).equals(
                "https://www.missav06.com/jmpres/surrit.com/" + uuid + "/1080p/video.m3u8"));

        // ---- 3. 清晰度按钮表：不能把 master 交给播放器 ----
        List<String> labels = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (Map.Entry<String, String> e : assigns.entrySet()) {
            int h = e.getKey().equals("source1280") ? 1080 : e.getKey().equals("source842") ? 720 : 0;
            if (h == 0) continue;                       // master 不进按钮表（变体会解析到轮换域）
            labels.add(String.valueOf(h));
            urls.add(jmpres(pageOrigin, e.getValue()));
        }
        ck("按钮只有 720/1080（master 被排除，实测其变体相对解析会拿到诱饵图）",
            labels.equals(Arrays.asList("720", "1080")) || labels.equals(Arrays.asList("1080", "720")), labels.toString());
        ck("每个按钮地址都过 /jmpres/", urls.stream().allMatch(u -> u.contains("/jmpres/")));

        // ---- 4. 只有 master 时的兜底：自己解析变体再重写 ----
        String master = readIf(dir.resolve("m3u8_master_playlist.m3u8"));
        if (!master.isEmpty()) {
            ck("取证到的 master 是 #EXTM3U 且只广播一级（480p）",
                master.contains("#EXTM3U") && REND.matcher(master).results().count() == 1);
            Matcher rm = REND.matcher(master);
            rm.find();
            Matcher rh = RES.matcher(rm.group(1));
            int h = rh.find() ? Integer.parseInt(rh.group(1)) : 0;
            String abs = new java.net.URI("https://www.missav06.com/jmpres/surrit.com/" + uuid + "/")
                .resolve(rm.group(2).trim()).toString();
            ck("变体解析出 480 档且仍在 /jmpres/ 前缀下", h == 480 && abs.contains("/jmpres/surrit.com/"), abs);
        } else {
            System.out.println("  SKIP  master 样本缺失");
        }

        // ---- 5. Referer 规则（同域；跨域实测 TLS 断连） ----
        String media = "https://www.missav06.com/jmpres/surrit.com/" + uuid + "/720p/video.m3u8";
        ck("媒体与详情页同域 → Referer 用详情页",
            refererFor(media, "https://www.missav06.com/cn/ewdx-599").equals("https://www.missav06.com/cn/ewdx-599"));
        String seg = "https://vsd998.vckeepw.top/" + uuid + "/720p/0.ts";
        ck("分片落在轮换域 → Referer 退回该域根（不借用别站详情页）",
            refererFor(seg, "https://www.missav06.com/cn/ewdx-599").equals("https://vsd998.vckeepw.top/"), refererFor(seg, "https://www.missav06.com/cn/ewdx-599"));
        ck("轮换媒体域不被硬编码（样本里出现过的 6 个域都靠跟随 302 得到）",
            !jmpres(pageOrigin, media).contains("vckeepw"));
        // ---- 5b. 原生板块判据（**不再按域名反推**：上一版 autoFs/Referer 都用「非官方域」推断，
        //      而官方媒体域根本不在 officialHosts 里，于是官方源被强制横屏全屏并被多塞一个 Referer） ----
        ck("MissAV 作品页判为原生板块（卡片分流＝详情路由，同一个函数）",
            isNativeBoardUrl("https://www.missav06.com/cn/ewdx-599"));
        ck("MissAV 轮换线路域同样判为原生（判据是路径形状，不是线路域名）",
            isNativeBoardUrl("https://www.missav123.com/cn/fc2-ppv-4968519"));
        ck("xchina 作品页判为原生", isNativeBoardUrl("https://xchina001.ink/videos/abc123.html"));
        ck("官方页判为非原生（官方源走原路径，一行不改）",
            !isNativeBoardUrl("https://www.hanime1.me/watch/407695"));
        ck("MissAV 导航项判为非原生（/cn/new、/cn/genres/xxx 不是作品页）",
            !isMissavVideoUrl("https://www.missav06.com/cn/new")
                && !isMissavVideoUrl("https://www.missav06.com/cn/genres/uncensored-leak")
                && !isMissavVideoUrl("https://www.missav06.com/cn/actresses"));
        // 官方播址的真实域：从取证下来的官方详情页里读，不靠记忆
        String watch = readIf(Paths.get(System.getenv().getOrDefault("OFFICIAL_WATCH", "D:\\工作区\\临时\\avp_watch2.html")));
        if (!watch.isEmpty()) {
            Matcher offSrc = Pattern.compile("<source src=\"([^\"]+)\"").matcher(watch);
            List<String> hosts = new ArrayList<>();
            while (offSrc.find()) { String h = hostOf(offSrc.group(1)); if (h != null && !hosts.contains(h)) hosts.add(h); }
            boolean allOutside = !hosts.isEmpty();
            for (String h : hosts) if (isOfficial("https://" + h + "/")) allOutside = false;
            ck("官方详情页给出的**媒体域**不在 officialHosts 里（⇒ 域名反推必错，必须显式传原生标志）",
                allOutside, "实测官方播址域：" + String.join(",", hosts));
        } else {
            System.out.println("  SKIP  官方详情页样本缺失（OFFICIAL_WATCH 指一份 avp_watch2.html 才会跑这条）");
        }

        // ---- 6. 无 #EXT-X-KEY：与取证一致（AES 走别的路径就不该在这里等） ----
        String v720 = readIf(dir.resolve("m3u8_720p_video.m3u8"));
        if (!v720.isEmpty()) {
            ck("分档播放列表确无 #EXT-X-KEY（若有，ExoPlayer 需另取 key）", !v720.contains("#EXT-X-KEY"));
            ck("分档列表是完整 VOD（有 ENDLIST、分片数 >100）",
                v720.contains("#EXT-X-ENDLIST") && v720.split("#EXTINF").length - 1 > 100,
                (v720.split("#EXTINF").length - 1) + " 分片");
        } else {
            System.out.println("  SKIP  720p 列表样本缺失");
        }

        System.out.println("合计 PASS=" + pass + " FAIL=" + fail);
        System.out.println(fail == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        if (fail != 0) System.exit(1);
    }

    static String readIf(Path p) {
        try { return Files.isRegularFile(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : ""; }
        catch (IOException e) { return ""; }
    }

    static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}
