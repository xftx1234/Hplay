// 通用列表解析离线验收器：正则与算法逐条照抄 X99.parseGeneric（2026-10-01 重写版），
// 拿 r34_list 的**真实站点样本**跑，逐站给出「卡片数 / 坏封面数 / 前 3 张标题」。
//   编译：javac -encoding UTF-8 -d . GenericCheck.java
//   运行：java -Dstdout.encoding=UTF-8 GenericCheck [样本目录]   （默认 D:\工作区\临时\r34_list）
// 判定：与取证报告（r34_list 的 diff.py 对照表）逐项比对；任何一站从"有卡"变"0 卡"都是回归。
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class GenericCheck {
    static int pass = 0, fail = 0;
    static final boolean DUMP = System.getenv("GEN_DUMP") != null;   // GEN_DUMP=1 打印每站前 8 张卡的 URL

    /**
     * 导航/榜单项的**黑名单判据来自原始 HTML**（我用 grep 直接从样本里读出来的字面量），
     * 不是解析器自己算出来的——否则「解析结果里没有垃圾」这句话就会被解析器自己定义，成了恒真断言。
     * 2026-10-01 审查实测四站首卡：pb365「最新/New」、rou123「OnlyFans」、xhamster「顶级创作者视频」、
     * 18comic「查看更多…」。
     */
    static final String[] NAV_TITLES = {
        "最新/New", "周榜/Weekly", "月榜/Monthly", "5分钟/5min+", "10分钟/+",
        "顶级创作者视频", "查看更多", "OnlyFans",
    };
    static final String[] NAV_URLS = {
        "pb365.nl/list-1.htm", "pb365.nl/top7_list-1.htm", "pb365.nl/top_list-1.htm",
        "rou123.ws/vodtype/4.html", "zh.xhamster1.pw/creators/", "18comic.my/albums",
    };

    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (ok) pass++; else fail++;
    }

    // ---------- 与 X99.kt 逐条同语义 ----------
    static final Pattern HINT = Pattern.compile("(/v/|/video|/watch|/detail|/vod|/movies?/|/archives?/|/works?/|/post|/play|/mv/|/article|/albums?|/photos?/|/p/|/n/|/\\d{4,}|[.\\-]\\d{3,}|-[a-z]{2,}\\d{2,}|\\.htm|viewkey=|/g/\\d)", Pattern.CASE_INSENSITIVE);
    static final Pattern BAD = Pattern.compile("(javascript:|^#|/login|/register|/signup|/search|/user|/tag|/categories|/genres?|/actress|/maker|/page/|/vip|/help|/contact|/dmca|/terms|/privacy|/enter$|/static|/assets|/css|/js/|\\.(css|js|png|jpe?g|webp|svg|ico|gif)$)", Pattern.CASE_INSENSITIVE);
    static final Pattern ANCHOR = Pattern.compile("<a\\s[^>]*href=[\"']([^\"']+)[\"']([^>]*)>([\\s\\S]{0,20000}?)</a>", Pattern.CASE_INSENSITIVE);
    static final Pattern IMGTAG = Pattern.compile("<img[^>]*>", Pattern.CASE_INSENSITIVE);
    static final Pattern DATAATTR = Pattern.compile("(?:data-src|data-original|data-echo|data-lazy-src|data-background|data-url|data-thumb_url)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    static final Pattern SRCATTR = Pattern.compile("\\ssrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    static final Pattern BGIMG = Pattern.compile("background-image\\s*:\\s*url\\(\\s*['\"]?([^'\")]+)", Pattern.CASE_INSENSITIVE);
    static final Pattern ALT = Pattern.compile("alt=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    static final Pattern TITLEATTR = Pattern.compile("(?:title|aria-label)=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    static final Pattern DUR = Pattern.compile("(\\d{1,3}:\\d{2}(?::\\d{2})?|\\d{1,3}\\s*分\\s*\\d{1,2}\\s*秒)");
    static final Pattern COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
    static final Pattern TAG = Pattern.compile("<[^>]+>");
    static final Pattern BADTHUMB = Pattern.compile("(^data:|placeholder|/blank\\.jpg|blank\\.webp|zero\\.png|/a/i/|loading\\.(gif|png|webp)|spinner|no-image|nopic|default\\.(jpg|png|webp)|1px)", Pattern.CASE_INSENSITIVE);
    /** = X99.genListPathRe：带封面但本身仍是列表页（18comic 的 /albums、xhamster 的 /creators/）。 */
    static final Pattern LISTPATH = Pattern.compile("(/albums([/?#]|$)|/creators/)", Pattern.CASE_INSENSITIVE);
    /**
     * = X99.genNavClassRe / genNavUrlRe（2026-10-01 新增）：导航项的两条硬判据。
     * **字面量取自样本原文**，不是解析器自己算的：page-link（Jable「最后 »」、avjoy 数字页）、
     * list-group-item（avjoy 分类）、sugg-pep（Xanimu 搜索建议）、menuLink/js-menuAnalytics（Pornhub 菜单）、
     * text-nowrap（LissAV 页脚 2257）、hover:text-ink（肉视频「2257 聲明」）、nav-link（三级片年份条）、
     * outsidenotice（禁漫天堂外链公告）；URL 侧：user.htm?author=、_list-N.htm、/playlist/、/uncensored_makersr/。
     */
    static final Pattern NAVCLASS = Pattern.compile(
        "class=\"[^\"]*(page-link|pagination|list-group-item|sugg-pep|menuLink|js-menuAnalytics|"
            + "viewPlaylistLink|text-nowrap|hover:text-ink|outsidenotice|nav-link|breadcrumb|dropdown-item|"
            + "label-cat|all-cat|cat-name|mobile-menu-href)",
        Pattern.CASE_INSENSITIVE);
    /** = X99.genAnyImgAttrRe：封面不一定挂在 img 上（Pornhub 的 GIF 卡写在 `<video data-poster=…jpg>`，
     *  qinav 写在 `<img src=占位 img=真图>` 的 `img` 属性里）。两侧正则必须同步，否则验收器测的不是生产代码。 */
    static final Pattern ANYIMG = Pattern.compile(
        "\\s(?:data-src|data-original|data-poster|data-thumb|data-thumb_url|data-image|img|poster)\\s*=\\s*"
            + "[\"']([^\"']+\\.(?:jpg|jpeg|png|webp|gif)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    static final Pattern NAVURL = Pattern.compile(
        "(user\\.htm|[?&]author=|_list-\\d+\\.htm|^/?playlist/|/uncensored_makersr/)", Pattern.CASE_INSENSITIVE);
    /** = X99.jsonCoverRe：xhamster 的封面只写在页面 JSON 里（服务端只渲染首屏 15 个 img）。 */
    static final Pattern JSONCOVER = Pattern.compile(
        "\"pageURL\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"[^}]{0,600}?\"thumbURL\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"\\s*");
    static final Set<String> NAV = new HashSet<>(Arrays.asList(
        "首页", "登录", "注册", "搜索", "更多", "下一页", "上一页", "分类", "排行", "标签", "会员",
        "最新", "周榜", "月榜", "日榜", "热门", "推荐", "全部", "查看", "查看更多", "精选", "列表",
        "home", "login", "more", "next", "prev", "help", "about", "contact",
        "new", "hot", "top", "all", "view", "daily", "weekly", "monthly", "trending", "onlyfans"));

    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    static boolean junkTitle(String t) {
        String s = t.trim();
        if (s.length() < 6) return true;
        if (DUR.matcher(s).matches()) return true;
        boolean allPunct = true;
        for (char c : s.toCharArray()) if (!(Character.isDigit(c) || " :.-/_".indexOf(c) >= 0)) { allPunct = false; break; }
        if (allPunct) return true;
        if (NAV.contains(s.toLowerCase())) return true;
        // 「中英对照」拼接的菜单项：整体不在词表，但每个分词都是导航词（与 Kotlin 同语义：分词为空则不算垃圾）
        String[] toks = s.split("[\\s/|·、，,（）()\\[\\]-]+");
        int n = 0;
        for (String tok : toks) {
            if (tok.isEmpty()) continue;
            n++;
            if (!NAV.contains(tok.toLowerCase())) return false;
        }
        return n > 0;
    }

    /** = X99.pathQuery：只取 path+query，域名里的数字不许冒充作品号。 */
    static String pathQuery(String u) {
        int i = u.indexOf("://");
        if (i < 0) return u;
        int slash = u.indexOf('/', i + 3);
        return slash < 0 ? "/" : u.substring(slash);
    }

    static String bareHost(String h) {
        h = h.toLowerCase();
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    static String hostOf(String u) {
        try { return new java.net.URI(u).getHost() == null ? "" : new java.net.URI(u).getHost(); }
        catch (Exception e) { return ""; }
    }

    static String resolve(String base, String rel) {
        try { return new java.net.URI(base).resolve(rel).toString(); } catch (Exception e) { return ""; }
    }

    static String cleanThumb(String u, String pageUrl) {
        String t = u.trim().replace("&amp;", "&");
        if (t.isEmpty() || BADTHUMB.matcher(t).find()) return "";
        String abs = resolve(pageUrl, t);
        return abs.startsWith("http") ? abs : "";
    }

    static class Card { String url, title = "", thumb = "", dur = ""; }

    /** = X99.genDetailRe：没封面没时长时，链接要"像详情页"才收。 */
    static final Pattern DETAIL = Pattern.compile("(\\d{3,}|/article/|/post/|/read/|/book/|/chapter|viewkey=|/g/\\d+/|/videos/\\w|[?&]id=\\d)", Pattern.CASE_INSENSITIVE);

    static List<Card> parseGeneric(String pageUrl, String html) {
        String pageHost = bareHost(hostOf(pageUrl));
        LinkedHashMap<String, Card> best = new LinkedHashMap<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            String href = m.group(1), attrs = m.group(2), inner = m.group(3);
            if (BAD.matcher(href).find() || !HINT.matcher(href).find()) continue;
            String abs = resolve(pageUrl, unescape(href));
            if (!abs.startsWith("http")) continue;
            String h = bareHost(hostOf(abs));
            if (!pageHost.isEmpty() && !h.isEmpty() && !h.equals(pageHost)) continue;
            if (LISTPATH.matcher(pathQuery(abs)).find()) continue;   // = X99.genListPathRe：又是列表页（/albums、/creators/）
            if (NAVCLASS.matcher("<a" + attrs).find() || NAVURL.matcher(pathQuery(abs)).find()) continue;
            if (strip(abs).equals(strip(pageUrl))) continue;

            String innerNC = COMMENT.matcher(inner).replaceAll(" ");
            String thumb = "";
            Matcher im = IMGTAG.matcher(innerNC);
            while (im.find()) {
                String tag = im.group();
                Matcher d = DATAATTR.matcher(tag);
                if (d.find()) thumb = cleanThumb(d.group(1), pageUrl);
                if (thumb.isEmpty()) { Matcher s = SRCATTR.matcher(tag); if (s.find()) thumb = cleanThumb(s.group(1), pageUrl); }
                if (!thumb.isEmpty()) break;
            }
            if (thumb.isEmpty()) { Matcher b = BGIMG.matcher(innerNC); if (b.find()) thumb = cleanThumb(b.group(1), pageUrl); }
            if (thumb.isEmpty()) { Matcher d = DATAATTR.matcher(attrs); if (d.find()) thumb = cleanThumb(d.group(1), pageUrl); }
            if (thumb.isEmpty()) { Matcher a = ANYIMG.matcher(innerNC); if (a.find()) thumb = cleanThumb(a.group(1), pageUrl); }
            if (thumb.isEmpty()) { Matcher a = ANYIMG.matcher(attrs); if (a.find()) thumb = cleanThumb(a.group(1), pageUrl); }

            String title = "";
            Matcher a = ALT.matcher(innerNC);
            if (a.find()) title = unescape(a.group(1)).trim();
            if (junkTitle(title)) { Matcher t = TITLEATTR.matcher(attrs); if (t.find()) title = unescape(t.group(1)).trim(); }
            if (junkTitle(title)) { Matcher t = TITLEATTR.matcher(innerNC); if (t.find()) title = unescape(t.group(1)).trim(); }
            if (junkTitle(title)) title = unescape(TAG.matcher(innerNC).replaceAll(" ")).replaceAll("\\s+", " ").trim();
            if (junkTitle(title)) title = unescape(TAG.matcher(innerNC).replaceAll(" ")).replaceAll("\\s+", " ").trim();

            String dur = "";
            Matcher dm = DUR.matcher(TAG.matcher(innerNC).replaceAll(" "));
            if (dm.find()) { dur = dm.group(1).trim(); if (dur.equals(title)) dur = ""; }

            // 与 Kotlin 同语义：**不因标题垃圾就丢掉整条**，同一 URL 的字段逐个合并
            Card c = best.get(abs);
            if (c == null) { c = new Card(); c.url = abs; best.put(abs, c); }
            if (!junkTitle(title)) {
                String t = title.length() > 160 ? title.substring(0, 160) : title;
                if (c.title.isEmpty() || t.length() > c.title.length()) c.title = t;
            }
            if (c.thumb.isEmpty()) c.thumb = thumb;
            if (c.dur.isEmpty()) c.dur = dur;
        }
        fillCoversFromJson(pageUrl, html, best);
        List<Card> out = new ArrayList<>();
        for (Card c : best.values()) {
            if (junkTitle(c.title)) continue;
            if (c.thumb.isEmpty() && c.dur.isEmpty() && !DETAIL.matcher(pathQuery(c.url)).find()) continue;
            out.add(c);
        }
        return out;
    }

    /** = X99.fillCoversFromJson：只按 URL 精确回填，绝不模糊匹配。 */
    static void fillCoversFromJson(String pageUrl, String html, LinkedHashMap<String, Card> cards) {
        boolean anyMissing = false;
        for (Card c : cards.values()) if (c.thumb.isEmpty()) { anyMissing = true; break; }
        if (!anyMissing) return;
        Map<String, String> byUrl = new HashMap<>();
        Matcher m = JSONCOVER.matcher(html);
        while (m.find()) {
            String abs = resolve(pageUrl, jsonUnesc(m.group(1)));
            String thumb = cleanThumb(jsonUnesc(m.group(2)), pageUrl);
            if (abs != null && abs.startsWith("http") && !thumb.isEmpty())
                byUrl.put(strip(abs), thumb);
        }
        if (byUrl.isEmpty()) return;
        for (Card c : cards.values())
            if (c.thumb.isEmpty()) {
                String t = byUrl.get(strip(c.url));
                if (t != null) c.thumb = t;
            }
    }

    static String jsonUnesc(String s) {
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    static String strip(String u) { return u.replaceAll("/+$", ""); }

    public static void main(String[] a) throws Exception {
        Path dir = Paths.get(a.length > 0 ? a[0] : "D:\\工作区\\临时\\r34_list");
        if (!Files.isDirectory(dir)) { System.out.println("样本目录不存在：" + dir); System.exit(2); }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.html")) { for (Path p : ds) files.add(p); }
        Collections.sort(files);
        // 真实页面 URL 从取证计划里取（文件名前缀 = plan 的 name）：
        // 靠文件名猜 host 会让相对链接解析与同域判定都走偏，测出来的就不是运行期行为
        Map<String, String> urls = new HashMap<>();
        for (String plan : new String[]{"plan1.json", "plan2.json", "plan3.json"}) {
            Path pp = dir.resolve(plan);
            if (!Files.isRegularFile(pp)) continue;
            Matcher jm = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"url\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(new String(Files.readAllBytes(pp), StandardCharsets.UTF_8));
            while (jm.find()) urls.put(jm.group(1), jm.group(2).replace("\\/", "/"));
        }
        System.out.println("样本 " + files.size() + " 份（" + dir + "），页面 URL 映射 " + urls.size() + " 条");
        int totalCards = 0, totalBad = 0, zeroCard = 0;
        List<String> missSites = new ArrayList<>();
        List<String> navLeaks = new ArrayList<>();
        List<Object[]> perSiteLog = new ArrayList<>();   // {站点名, 卡片数, 无封面数}
        for (Path f : files) {
            String html = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            String name = f.getFileName().toString();
            String site = name.split("__")[0];
            String pageUrl = urls.getOrDefault(site, "");
            if (pageUrl.isEmpty()) {
                String host = name.contains("__") ? name.split("__")[1].split("_")[0] : "";
                pageUrl = "https://" + (host.isEmpty() ? "example.com" : host) + "/";
            }
            List<Card> cs = parseGeneric(pageUrl, html);
            for (Card c : cs) {
                // 只对**整个标题就是导航词**判违规：肉视频的真实片名里合法地带着「OnlyFans · 631 次觀看」这段尾巴，
                // 用 contains 会把真卡片也判成导航项（第一版就这么误伤过 24 条，是我这条判据的错，不是解析器的错）
                for (String nav : NAV_TITLES)
                    if (c.title.equals(nav) || (c.title.length() < nav.length() + 6 && c.title.startsWith(nav)))
                        navLeaks.add(site + " 标题「" + cut(c.title, 20) + "」");
                for (String nav : NAV_URLS) if (c.url.contains(nav)) navLeaks.add(site + " 链接 " + c.url);
            }
            int bad = 0, dur = 0;
            for (Card c : cs) { if (c.thumb.isEmpty()) bad++; if (!c.dur.isEmpty()) dur++; }
            perSiteLog.add(new Object[]{site, cs.size(), bad});
            // 「页面上有封面、我们却没取到」才算缺陷：只数 **img 标签上带懒加载属性** 的（xhamster 实测服务端只渲染首屏 15 个
            // img、其余卡片在 HTML 里根本没有图 —— 那是 SSR 限制，不是解析漏取，不该把它算成 bug）
            int pageImgs = 0;
            Matcher it = IMGTAG.matcher(html);
            while (it.find()) { Matcher di = DATAATTR.matcher(it.group()); if (di.find()) pageImgs++; }
            totalCards += cs.size(); totalBad += bad; if (cs.isEmpty()) zeroCard++;
            boolean coverMiss = cs.size() >= 6 && pageImgs >= 20 && bad * 10 > cs.size() * 3;
            if (coverMiss) missSites.add(String.format("%s（%d/%d 张无图，页内有 %d 个懒加载图）", site, bad, cs.size(), pageImgs));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(2, cs.size()); i++) {
                Card c = cs.get(i);
                sb.append(String.format("%s | %s | %s", cut(c.title, 26), c.thumb.isEmpty() ? "无封面" : "有图", c.dur.isEmpty() ? "-" : c.dur));
                if (i == 0 && cs.size() > 1) sb.append("  ‖  ");
            }
            System.out.printf("%-34s 卡%3d 无图%3d 有时长%3d  %s%n", cut(site, 32), cs.size(), bad, dur, sb);
            if (DUMP) {
                for (int i = 0; i < Math.min(8, cs.size()); i++) {
                    Card c = cs.get(i);
                    System.out.printf("      #%d %s | %s | 图%b 长%b%n", i, cut(c.title, 22), c.url,
                            !c.thumb.isEmpty(), !c.dur.isEmpty());
                }
            }
            if (System.getenv("GEN_MISS") != null)
                for (Card c : cs)
                    if (c.thumb.isEmpty())
                        System.out.printf("      无封面 %s | %s%n", cut(c.title, 26), c.url);
        }
        System.out.println();
        ck("总卡片数 > 800（57 份样本，重写前实测约 700 且 6 站为 0）", totalCards > 800, "实得 " + totalCards);
        // 不看全局无封面比例（小说站本来就没封面，混进来会把数字拉花），只看**页面有懒加载图却漏取**的站
        ck("没有『页面有 ≥20 个懒加载图、却 >30% 卡片无封面』的站点", missSites.isEmpty(), String.join("; ", missSites));
        ck("0 卡样本 ≤ 8 份（SPA/无 SSR 站点本来就没有列表）", zeroCard <= 8, "实得 " + zeroCard);
        ck("导航/榜单项混入 = 0（判据取自原始 HTML 的字面量，不是解析器自己算的）",
            navLeaks.isEmpty(), String.join("; ", navLeaks));

        // ---------- 2026-10-01 本轮：封面「无 url」三项修复的定点断言 ----------
        // 断言对象是**具体站点的计数**，取自改动前同一份样本的实测基线（见工程记录），不是解析器自算的口径。
        Map<String, int[]> perSite = new HashMap<>();   // site -> {卡片数, 无封面数}
        for (Object[] rec : perSiteLog) {
            String s = (String) rec[0];
            int[] v = perSite.getOrDefault(s, new int[2]);
            v[0] += (Integer) rec[1];
            v[1] += (Integer) rec[2];
            perSite.put(s, v);
        }
        int[] xh = perSite.getOrDefault("xhamster", new int[2]);
        ck("xhamster 无封面卡 ≤ 3（JSON 回补前实测 40+39 张；页面里 50/46 对 pageURL+thumbURL）",
            xh[1] <= 3, "实得 " + xh[1] + "/" + xh[0]);
        int[] hstv = perSite.getOrDefault("好色TV", new int[2]);
        ck("好色TV 无封面卡 ≤ 8（口径归一 + 导航黑名单前实测 12/28）", hstv[1] <= 8, "实得 " + hstv[1] + "/" + hstv[0]);
        int[] ph = perSite.getOrDefault("Pornhub", new int[2]);
        ck("Pornhub 无封面卡 ≤ 3（口径归一前实测 6~7 张菜单项）", ph[1] <= 3, "实得 " + ph[1] + "/" + ph[0]);
        int[] xa = perSite.getOrDefault("Xanimu", new int[2]);
        ck("Xanimu 无封面卡 ≤ 6（改前 14：sugg-pep/label-cat/all-cat 标签项已挡；"
            + "余下的是「排行榜」纯文字行，页面里确实没有图）", xa[1] <= 6, "实得 " + xa[1] + "/" + xa[0]);
        int[] rou = perSite.getOrDefault("肉视频", new int[2]);
        ck("肉视频 无封面卡 = 0（页脚「2257 聲明」，改前 1 张）", rou[1] == 0, "实得 " + rou[1] + "/" + rou[0]);
        // 黑名单**不许误伤**：这些站点的卡片总数不能掉（掉了说明把真作品也挡了）
        ck("Jable 两页合计卡片数 ≥ 50（黑名单误伤检查）",
            perSite.getOrDefault("Jable", new int[2])[0] + perSite.getOrDefault("Jable_list2", new int[2])[0]
                + perSite.getOrDefault("Jable_P2", new int[2])[0] >= 50, "");
        ck("avjoy 合计卡片数 ≥ 80",
            perSite.getOrDefault("avjoy", new int[2])[0] + perSite.getOrDefault("avjoy_P2", new int[2])[0] >= 80, "");
        ck("禁漫天堂合计卡片数 ≥ 100",
            perSite.getOrDefault("禁漫天堂", new int[2])[0] + perSite.getOrDefault("禁漫天堂_list", new int[2])[0] >= 100, "");
        System.out.println("合计 PASS=" + pass + " FAIL=" + fail);
        System.out.println(fail == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        if (fail != 0) System.exit(1);
    }

    static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}
