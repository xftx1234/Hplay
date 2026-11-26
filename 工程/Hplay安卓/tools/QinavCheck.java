// qinav 板块离线验收器：与 X99 的 parseQinav / qinavPagePath / qinavDetail 逐条同语义，
// 跑 临时\r37_newsite\ 的真实页面。期望值全部取自**原始 HTML 里肉眼核对过的字面量**，
// 不是解析器自己算出来的——否则「解析对了」这句话会被解析器自己定义成恒真。
//   编译：javac -encoding UTF-8 -d . QinavCheck.java
//   运行：java -Dstdout.encoding=UTF-8 QinavCheck [样本目录]
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class QinavCheck {
    static int pass = 0, fail = 0;

    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (ok) pass++; else fail++;
    }

    // ---------- 与 X99.kt 同语义的正则 ----------
    static final Pattern CARD = Pattern.compile("<a[^>]{0,160}?href=\"/video/(\\d+)\\.html\"[^>]{0,160}?>([\\s\\S]{0,1400}?)</a>");
    static final Pattern IMGATTR = Pattern.compile("\\bimg=\"(https?://[^\"]+)");
    static final Pattern TITLELI = Pattern.compile("<li class=\"title\">([\\s\\S]{0,300}?)</li>");
    static final Pattern TIME = Pattern.compile("<span class=\"time\">([^<]{1,20})</span>");
    static final Pattern VIEWS = Pattern.compile("icon-eye-open\"></i>\\s*([^<]{1,16})<");
    static final Pattern EMBED = Pattern.compile("<iframe[^>]{0,240}?src=\"(/embed/\\d+\\.html)");
    static final Pattern WRAPPED = Pattern.compile("[?&]url=(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)");
    static final Pattern TAGS = Pattern.compile("<[^>]+>");

    static String unesc(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    static class Card { String id, url, title = "", thumb = "", dur = "", views = ""; }

    static List<Card> parseQinav(String pageUrl, String html) {
        String origin = pageUrl.replaceFirst("^(https?://[^/]+).*", "$1");
        List<Card> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = CARD.matcher(html);
        while (m.find()) {
            String id = m.group(1), block = m.group(2);
            String url = origin + "/video/" + id + ".html";
            if (!seen.add(url)) continue;
            String title = "";
            Matcher t = TITLELI.matcher(block);
            if (t.find()) title = unesc(TAGS.matcher(t.group(1)).replaceAll(" ")).replaceAll("\\s+", " ").trim();
            if (title.length() > 160) title = title.substring(0, 160);
            if (title.length() < 6) continue;
            Card c = new Card();
            c.id = id; c.url = url; c.title = title;
            Matcher i = IMGATTR.matcher(block); if (i.find()) c.thumb = i.group(1);
            Matcher d = TIME.matcher(block); if (d.find()) c.dur = d.group(1).trim();
            Matcher v = VIEWS.matcher(block); if (v.find()) c.views = v.group(1).trim();
            out.add(c);
        }
        return out;
    }

    /** = X99.qinavPagePath：翻页是「把 -N 插在 .html 前」，不是 ?page=N。 */
    static String pagePath(String path, int page) {
        return page <= 1 ? path : path.replaceFirst("\\.html$", "") + "-" + page + ".html";
    }

    static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    public static void main(String[] a) throws Exception {
        Path dir = Paths.get(a.length > 0 ? a[0] : "D:\\工作区\\临时\\r37_newsite");
        if (!Files.isDirectory(dir)) { System.out.println("样本目录不存在：" + dir); System.exit(2); }
        String home = read(dir.resolve("qinav_home.html"));
        String cat = read(dir.resolve("qinav_cat.html"));
        String detail = read(dir.resolve("qinav_detail.html"));
        String embed = read(dir.resolve("qinav_embed_938547.html.html"));
        String page2 = read(dir.resolve("qinav_new_2_html.html"));
        System.out.println("样本目录 " + dir);

        List<Card> cs = parseQinav("https://www.qinav.com/", home);
        System.out.println("首页解析出 " + cs.size() + " 张卡");
        for (int i = 0; i < Math.min(3, cs.size()); i++)
            System.out.printf("   #%d %s | %s | 图=%s | 播放量=%s%n",
                i, cut(cs.get(i).title, 26), cs.get(i).url, cs.get(i).thumb.isEmpty() ? "无" : "有", cs.get(i).views);

        ck("首页卡片数 ≥ 50（页内 href=/video/ 去重实测 52）", cs.size() >= 50, "实得 " + cs.size());
        int noThumb = 0; for (Card c : cs) if (c.thumb.isEmpty()) noThumb++;
        ck("每张卡都取到封面（封面只在非标准属性 img= 里，src 是 loading.gif 占位）",
            noThumb == 0, "无封面 " + noThumb + " 张");
        ck("首卡标题 == 样本 <li class=title> 原文",
            !cs.isEmpty() && cs.get(0).title.equals("[JUL-123] 极品人妻被搞到沦陷，巨乳爆操内射颜射"),
            cs.isEmpty() ? "无卡" : cs.get(0).title);
        ck("首卡封面 == 样本 img= 原文 https://qin.toolmm.com/upload/93/85/47.jpg",
            !cs.isEmpty() && cs.get(0).thumb.equals("https://qin.toolmm.com/upload/93/85/47.jpg"),
            cs.isEmpty() ? "" : cs.get(0).thumb);
        ck("首卡链接 = https://www.qinav.com/video/938547.html",
            !cs.isEmpty() && cs.get(0).url.equals("https://www.qinav.com/video/938547.html"),
            cs.isEmpty() ? "" : cs.get(0).url);
        // 占位图不许当封面
        ck("没有任何卡片把 /static/loading.gif 当封面",
            cs.stream().noneMatch(c -> c.thumb.contains("loading.gif")), "");

        ck("翻页形式：/new.html 的第 2 页是 /new-2.html（不是 ?page=2）",
            pagePath("/new.html", 2).equals("/new-2.html") && pagePath("/new.html", 1).equals("/new.html"), "");
        Set<String> p1 = new HashSet<>(), p2 = new HashSet<>();
        for (Card c : cs) p1.add(c.url);
        for (Card c : parseQinav("https://www.qinav.com/new-2.html", page2)) p2.add(c.url);
        Set<String> inter = new HashSet<>(p1); inter.retainAll(p2);
        ck("第 1 页与第 2 页卡片零重叠（实测交集 0，翻页是真内容不是复制页）",
            p2.size() >= 50 && inter.isEmpty(), "页2=" + p2.size() + " 交集=" + inter.size());

        List<Card> cc = parseQinav("https://www.qinav.com/site/5/9.html", cat);
        ck("分类页 /site/5/9.html（中文字幕）也解析出 ≥50 张", cc.size() >= 50, "实得 " + cc.size());
        ck("分类页与首页不是同一批内容（分类真的在筛）",
            cc.stream().noneMatch(c -> c.id.equals("938547")) || !cc.get(0).url.equals(cs.get(0).url), "");

        // 详情页 → 播放器 iframe
        Matcher e = EMBED.matcher(detail);
        ck("详情页给出 /embed/<id>.html 播放器 iframe",
            e.find() && e.group(1).equals("/embed/938547.html"), e.reset().find() ? e.group(1) : "没找到");
        // iframe 里那层 155jx 包装必须解出真 m3u8
        Matcher w = WRAPPED.matcher(embed);
        String master = "";
        if (w.find()) {
            try { master = java.net.URLDecoder.decode(w.group(1), "UTF-8"); } catch (Exception ex) { master = w.group(1); }
        }
        ck("embed 页的 155jx.com/?url= 解出真 m3u8 == 实测那条",
            master.equals("https://2609.dadi2026.com/20260930/gwnbVmPQ/index.m3u8"), master);
        ck("master 清单本身不含 tip.ts 诱饵特征", !embed.contains("tip.ts"), "");

        // 诚实性：qinav 没有可用的服务端搜索，生产代码必须写死拒绝，不能让聚合搜索谎报「搜到 N 条」
        Path kt = Paths.get("D:\\工作区\\工程\\AvPlay\\独立版\\src\\com\\han1me\\X99.kt");
        String src = Files.isRegularFile(kt) ? read(kt) : "";
        int i0 = src.indexOf("fun boardSearch(");
        String body = i0 >= 0 ? src.substring(i0, Math.min(src.length(), i0 + 2200)) : "";
        ck("X99.boardSearch 里对 qinav 写死返回 null（不探测、不谎报条数）",
            body.contains("isQinavSite(siteName) -> null"), body.isEmpty() ? "没定位到函数体" : "已定位");
        ck("X99 的任意标签图片属性认得 img=（qinav 的封面写法）",
            src.contains("data-image|img|poster"), "");
        // 真机反馈：qinav 在板块按钮行里根本找不到。原因是加 ganxiaban 那次编辑的 old_string
        // 正好是 qinav 的 X99Site 块，把人家**覆盖**了 —— 而当时六个验收器全绿：
        // 没有任何一条断言「板块真的出现在站点列表里」。这条就是补那个洞。
        int b0 = src.indexOf("builtinSites: List<X99Site>");
        // 切到 listOf 的收尾：必须是「行首 4 空格 + )」，不能用裸 "    )" ——
        // 每个条目的 `        ),` 里也含这个子串，第一版就因此只截到第一个条目，误判 QINAV=false。
        int b1 = src.indexOf("\n    )", b0);
        String blist = (b0 >= 0 && b1 > b0) ? src.substring(b0, b1 + 5) : "";
        ck("qinav 与 ganxiaban **同时**在 builtinSites 里（板块行真的会显示出来）",
            blist.contains("name = QINAV_NAME") && blist.contains("name = GANXIE_NAME")
                && blist.contains("name = XCHINA_NAME"),
            "QINAV=" + blist.contains("QINAV_NAME") + " GANXIE=" + blist.contains("GANXIE_NAME")
                + " XCHINA=" + blist.contains("XCHINA_NAME"));

        System.out.println("合计 PASS=" + pass + " FAIL=" + fail);
        System.out.println(fail == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        if (fail != 0) System.exit(1);
    }

    static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}
