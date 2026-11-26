// 板块详情介绍页离线验收器：与 X99.metaDetail 逐条同语义，跑 临时\r36_detail\ 的**真实详情页样本**。
// 断言的期望值取自**原始 HTML 里我肉眼核对过的字面量**（og:title / og:image / og:description 的原文），
// 不是解析器自己算出来的结果——否则「解析对了」这句话就成了恒真断言。
//   编译：javac -encoding UTF-8 -d . GenericCheck.java BoardPageCheck.java
//   运行：java -Dstdout.encoding=UTF-8 BoardPageCheck [详情页目录]
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class BoardPageCheck {
    static int pass = 0, fail = 0;

    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (ok) pass++; else fail++;
    }

    // ---------- 与 X99 同语义的正则 ----------
    static final Pattern OG_TITLE = Pattern.compile("property=\"og:title\" content=\"([^\"]*)\"");
    static final Pattern OG_IMAGE = Pattern.compile("property=\"og:image\" content=\"([^\"]*)\"");
    static final Pattern OG_DESC = Pattern.compile("(?:property|name)=\"og:description\" content=\"([^\"]*)\"");
    static final Pattern META_DESC = Pattern.compile("<meta[^>]+name=\"description\"[^>]+content=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    static final Pattern H1 = Pattern.compile("<h1[^>]*>([^<]{2,200})</h1>", Pattern.CASE_INSENSITIVE);
    static final Pattern TITLE = Pattern.compile("<title[^>]*>([^<]{3,200})</title>", Pattern.CASE_INSENSITIVE);
    static final Pattern KEYWORDS = Pattern.compile("<meta[^>]+name=\"keywords\"[^>]+content=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    static final Pattern OG_TAG = Pattern.compile("(?:property|name)=\"og:video:tag\"\\s+content=\"([^\"]+)\"\\s*/?>");

    static String unesc(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    static String g1(Pattern p, String h) {
        Matcher m = p.matcher(h);
        return m.find() ? m.group(1) : "";
    }

    /** = X99.absImg */
    static String absImg(String u, String pageUrl) {
        String t = u.trim();
        if (t.isEmpty()) return "";
        if (t.startsWith("//")) return "https:" + t;
        if (t.startsWith("http")) {
            String c = GenericCheck.cleanThumb(t, pageUrl);
            return c.isEmpty() ? t : c;
        }
        return GenericCheck.cleanThumb(t, pageUrl);
    }

    /** = X99.sameShape */
    static String pathOf(String u) {
        try { String p = new java.net.URI(u).getRawPath(); return p == null ? "" : p; }
        catch (Exception e) { return ""; }
    }

    static String[] segs(String u) {
        List<String> out = new ArrayList<>();
        for (String s : pathOf(u).split("/")) if (!s.isEmpty()) out.add(s);
        return out.toArray(new String[0]);
    }

    static String hostOf(String u) {
        try { String h = new java.net.URI(u).getHost(); return h == null ? "" : h; }
        catch (Exception e) { return ""; }
    }

    static String segShape(String s) { return s.replaceAll("\\d+", "#"); }

    static boolean sameShape(String a, String b) {
        String ha = GenericCheck.bareHost(hostOf(a)), hb = GenericCheck.bareHost(hostOf(b));
        if (ha.isEmpty() || !ha.equals(hb)) return false;
        String[] sa = segs(a), sb = segs(b);
        if (sa.length == 0 || sa.length != sb.length) return false;
        return sa[0].equals(sb[0]) || segShape(sa[0]).equals(segShape(sb[0]));
    }

    /** = X99.workKey：多段路径取末段（作品号）；单段式（/view_video.php?viewkey=…）取 path+query。 */
    static String workKey(String u) {
        String[] s = segs(u);
        if (s.length >= 2) return s[s.length - 1];
        try {
            java.net.URI x = new java.net.URI(u);
            return (x.getRawPath() == null ? "" : x.getRawPath()) + "?" + (x.getRawQuery() == null ? "" : x.getRawQuery());
        } catch (Exception e) { return u; }
    }

    /** = X99.sameWork：同一个作品的另一种语言/别名入口，不算「相关影片」。 */
    static boolean sameWork(String a, String b) {
        return segs(a).length == segs(b).length && workKey(a).equals(workKey(b));
    }

    static class Meta {
        String title = "", cover = "", desc = "", url = "";
        List<String> tags = new ArrayList<>();
        List<GenericCheck.Card> related = new ArrayList<>();
    }

    /** = X99.metaDetail（sources 恒为空，这里不建） */
    static Meta metaDetail(String pageUrl, String html) {
        Meta d = new Meta();
        d.url = pageUrl;
        String t = unesc(g1(OG_TITLE, html));
        if (t.trim().length() < 2) t = unesc(g1(H1, html));
        if (t.trim().length() < 2) t = unesc(g1(TITLE, html));
        d.title = t.replaceAll("\\s+", " ").trim();
        if (d.title.length() > 160) d.title = d.title.substring(0, 160);
        d.cover = absImg(g1(OG_IMAGE, html), pageUrl);
        String de = unesc(g1(OG_DESC, html));
        if (de.trim().length() < 2) de = unesc(g1(META_DESC, html));
        d.desc = de.trim();
        List<String> tg = new ArrayList<>();
        Matcher ot = OG_TAG.matcher(html);
        while (ot.find() && tg.size() < 8) {
            String v = unesc(ot.group(1)).trim();
            if (!v.isEmpty() && !tg.contains(v)) tg.add(v);
        }
        if (tg.isEmpty()) {
            String kw = g1(KEYWORDS, html);
            for (String s : unesc(kw).split(",")) {
                String v = s.trim();
                if (v.length() >= 2 && v.length() <= 24 && !tg.contains(v)) tg.add(v);
                if (tg.size() == 8) break;
            }
        }
        d.tags = tg;
        for (GenericCheck.Card c : GenericCheck.parseGeneric(pageUrl, html)) {
            if (!c.thumb.isEmpty() && sameShape(c.url, pageUrl) && !sameWork(c.url, pageUrl))
                d.related.add(c);
        }
        if (d.related.size() > 18) d.related = d.related.subList(0, 18);
        return d;
    }

    /**
     * 这五份样本**不是作品详情页**（抓的时候被站点的分类/博客/外链广告页骗了），不参与评分：
     * 拿列表页去考「简介/推荐位」等于让验收器测错东西。
     */
    static final Set<String> NOT_DETAIL = new LinkedHashSet<>(Arrays.asList(
        // /zh-CN/24-部电视节目/9483… = 分类页，不是作品页
        "Xanimu__xanimu.one_zh-CN_24-_E9_83_A8_E7_94_B5_E8_A7_86_E8_8A_82_E7_9B_AE_9483-american-dad_.html",
        "三级片_P2__ptt2.my_p_1_page_2_year_2026.html",                        // /p/1?page=2&year= 年份列表
        "三级片资源，稀缺资源__ptt2.my_p_1_year_2026.html",                      // /p/1?year= 同上
        "禁漫天堂__18comic.my_blog_1225.html",                                  // /blog/ 全站公告页
        "禁漫天堂_list__ntlthwf6vut1du60i4rps.com_mk_207887_ncruccnk.html"      // 外链广告跳转页
    ));

    public static void main(String[] a) throws Exception {
        Path dir = Paths.get(a.length > 0 ? a[0] : "D:\\工作区\\临时\\r36_detail");
        if (!Files.isDirectory(dir)) { System.out.println("样本目录不存在：" + dir); System.exit(2); }
        // 页面 URL 一律取 _manifest.tsv 里**当初真正请求过的那一条**：
        // 从文件名反推会把 host 与 path 的分界读错（`321av.net_vodplay_…` 实测就因此整站推荐位为 0）。
        Map<String, String> pageOf = new LinkedHashMap<>();
        Path mf = dir.resolve("_manifest.tsv");
        if (Files.isRegularFile(mf))
            for (String line : Files.readAllLines(mf, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length >= 2) pageOf.put(p[0], p[1]);
            }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.html")) { for (Path p : ds) files.add(p); }
        Collections.sort(files);
        List<Path> scored = new ArrayList<>();
        for (Path p : files) {
            String n = p.getFileName().toString();
            if (!NOT_DETAIL.contains(n) && pageOf.containsKey(n)) scored.add(p);
        }
        System.out.println("详情页样本 " + files.size() + " 份，其中作品详情页 " + scored.size()
            + " 份参与评分（" + dir + "）");

        Map<String, Meta> bySite = new LinkedHashMap<>();
        int okTitle = 0, okCover = 0, okDesc = 0, okRel = 0;
        for (Path f : scored) {
            String html = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            String name = f.getFileName().toString();
            String site = name.split("__")[0];
            String pageUrl = pageOf.get(name);
            Meta d = metaDetail(pageUrl, html);
            d.url = pageUrl;
            if (!bySite.containsKey(site)) bySite.put(site, d);
            if (d.title.length() >= 2) okTitle++;
            if (!d.cover.isEmpty()) okCover++;
            if (d.desc.length() >= 8) okDesc++;
            if (!d.related.isEmpty()) okRel++;
            System.out.printf("%-14s 题%3d 封面%b 简介%3d 标签%2d 推荐%2d  %s%n",
                site, d.title.length(), !d.cover.isEmpty(), d.desc.length(), d.tags.size(),
                d.related.size(), cut(d.title, 30));
        }
        int n = scored.size();

        // ---------- 断言：期望值是**样本原文里核对过的字面量** ----------
        ck("标题取到率 100%（og:title/h1/title 三级回退）", okTitle == n, okTitle + "/" + n);
        ck("封面取到率 ≥ 70%（og:image，含协议相对写法）", okCover * 10 >= n * 7, okCover + "/" + n);
        ck("简介取到率 ≥ 60%（og:description → meta description）", okDesc * 10 >= n * 6, okDesc + "/" + n);
        ck("推荐位非空率 ≥ 60%（同形状且带封面）", okRel * 10 >= n * 6, okRel + "/" + n);

        // 定点核对（字面量来自样本原文）
        Meta j = bySite.get("321AV");
        ck("321AV 标题 == 样本 og:title 原文「JUY-041 公公的儿媳，公公，请原谅我......远野美穗」",
            j != null && j.title.equals("JUY-041 公公的儿媳，公公，请原谅我......远野美穗"), j == null ? "缺样本" : j.title);
        ck("321AV 封面 == 样本 og:image 原文 https://spic2-88.71352.men/juy-041/cover-t.jpg",
            j != null && j.cover.equals("https://spic2-88.71352.men/juy-041/cover-t.jpg"), j == null ? "" : j.cover);
        ck("321AV 简介非空（样本 og:description 是剧情简介）", j != null && j.desc.length() > 20,
            j == null ? "" : "len=" + j.desc.length());
        ck("321AV 推荐位全部是 /vodplay/ 作品页", j != null && !j.related.isEmpty()
            && j.related.stream().allMatch(c -> pathOf(c.url).startsWith("/vodplay/")),
            j == null ? "" : first(j));

        Meta cp = bySite.get("CartoonPorno");
        ck("CartoonPorno 封面把协议相对写法补成 https:（样本原文是 //jre1hgo-….71352.men/…jpg）",
            cp != null && cp.cover.startsWith("https://jre") && cp.cover.endsWith(".jpg"),
            cp == null ? "缺样本" : cp.cover);

        Meta hg = bySite.get("Hentai");
        ck("Hentai 推荐位不含翻页项 /g/685147/2/（sameShape 段数判据）",
            hg != null && hg.related.stream().noneMatch(c -> pathOf(c.url).matches("/g/\\d+/\\d+/?")),
            hg == null ? "缺样本" : hg.related.size() + " 条");

        Meta ls = bySite.get("LissAV");
        ck("LissAV 推荐位非空且不含语言切换项 /asian/en/video/…（末段作品号判据）",
            ls != null && !ls.related.isEmpty()
                && ls.related.stream().noneMatch(c -> pathOf(c.url).contains("/en/video/"))
                && ls.related.stream().noneMatch(c -> pathOf(c.url).contains("/zh-HK/video/")),
            ls == null ? "（本轮无该站样本）" : ls.related.size() + " 条");

        Meta ph = bySite.get("Pornhub");
        ck("Pornhub 推荐位非空且不含分类菜单 /video（首段判据）",
            ph != null && !ph.related.isEmpty()
                && ph.related.stream().noneMatch(c -> pathOf(c.url).equals("/video")),
            ph == null ? "（本轮无该站样本）" : ph.related.size() + " 条 "
                + (ph == null || ph.related.isEmpty() ? "" : first(ph)));

        // 诚实性：通用介绍页**不许**凭空造播址。这条不看解析器的输出（那会成恒真断言），
        // 直接读生产源码 metaDetail 的函数体，核对它确实只给空清单、没调用任何取播址的例程。
        Path kt = Paths.get("D:\\工作区\\工程\\AvPlay\\独立版\\src\\com\\han1me\\X99.kt");
        String body = "";
        if (Files.isRegularFile(kt)) {
            String src = new String(Files.readAllBytes(kt), StandardCharsets.UTF_8);
            int i = src.indexOf("fun metaDetail(");
            int k = src.indexOf("fun boardPage(", i);
            if (i >= 0 && k > i) body = src.substring(i, k);
        }
        ck("X99.metaDetail 函数体确实写 sources = emptyList()", body.contains("sources = emptyList()"),
            body.isEmpty() ? "没定位到函数体" : "已定位");
        ck("X99.metaDetail 没有调用任何取播址的例程（不会把网页版硬说成原生播）",
            !body.isEmpty()
                && !body.contains("resolveMaster") && !body.contains("renditionsOf")
                && !body.contains("xchinaQualities") && !body.contains("missavSources")
                && !body.contains("getPlaylist"), "");

        System.out.println("合计 PASS=" + pass + " FAIL=" + fail);
        System.out.println(fail == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        if (fail != 0) System.exit(1);
    }

    static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    static String first(Meta d) { return d.related.isEmpty() ? "（推荐位为空）" : d.related.get(0).url; }
}
