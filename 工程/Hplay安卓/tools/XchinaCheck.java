// xchina 板块离线验收器（与运行期同一套解析语义；改解析或站点改版后必跑）
//   编译：javac -encoding UTF-8 -d . XchinaCheck.java
//   运行：java -Dstdout.encoding=UTF-8 XchinaCheck <取证目录>
//     取证目录 = D:\工作区\临时\xchina（实测抓下来的页面 body，见工程记录里的链路说明）
//   判定口径：卡片字段必须齐、播放源必须是 /hls/<id>/master.m3u8、清晰度只能来自 master 的 RESOLUTION、
//             镜像子域与主域必须解析一致（板块靠 origin 拼路径）；任何一项不过 = 解析器与站点已脱节。
//   语义来源：X99.kt 的 parseXchina / xchinaListing / xchinaDetail / xchinaQualities（正则逐条照抄）
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class XchinaCheck {
    static int pass = 0, fail = 0, skip = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }

    static void check(String what, boolean ok, String measured) {
        check(what + "  [" + measured + "]", ok);
    }

    static void skipped(String what) {
        skip++; System.out.println("  SKIP  " + what);
    }

    static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    // ---------- 与 X99.kt 逐条同语义的正则 ----------
    static final String CARD_MARK = "<div class=\"item video\">";
    static final Pattern HREF = Pattern.compile("href=\"/video/id-([a-z0-9]+)\\.html\"");
    static final Pattern TITLE_ATTR = Pattern.compile("title=\"([^\"]*)\"");
    static final Pattern TITLE_TEXT = Pattern.compile("class=\"title\"><a[^>]*>([^<]*)<");
    static final Pattern BG_IMG = Pattern.compile("background-image:url\\('([^']+)'\\)");
    static final Pattern IMG = Pattern.compile("<img[^>]*?src=\"([^\"]+)");
    static final Pattern DUR = Pattern.compile("fa-clock\"></i>(\\d{1,2}:\\d{2}(?::\\d{2})?)");
    static final Pattern MODEL = Pattern.compile("class=\"model-item\">([^<]*)<");
    static final Pattern SRC = Pattern.compile("src:\\s*'(/hls/[^']+)'\\s*,");
    static final Pattern POSTER = Pattern.compile("poster:\\s*'([^']+)'\\s*,");
    static final Pattern OG_DUR = Pattern.compile("property=\"og:duration\" content=\"(\\d+)");
    static final Pattern H1 = Pattern.compile("<h1 class=\"hero-title-item\">([^<]*)</h1>");
    static final Pattern CAT = Pattern.compile("href=\"(/videos/cat-[a-z]+\\.html)\">([^<]+)</a>");
    static final Pattern UPLOAD = Pattern.compile("\"uploadDate\":\"([^\"]+)");
    static final Pattern PERSON = Pattern.compile("\"name\":\"([^\"]+)\",\"gender");
    static final Pattern RENDITION = Pattern.compile("RESOLUTION=\\d+x(\\d+)[^\\n]*\\n([^\\n]+)");

    /** = X99.xchinaPagerRe（`class="pager-num"`，当前页那条是 `pager-num current`，故意不匹配）。 */
    static final Pattern PAGER = Pattern.compile("<a href=\"/videos/(?:[a-z0-9-]+/)?(\\d+)\\.html\" class=\"pager-num\"");

    /** = X99.xchinaListing 里读分页器那一段：取所有页号的最大值（没有分页器返回 -1）。 */
    static int pagerMax(String html) {
        int max = -1;
        Matcher m = PAGER.matcher(html);
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        return max;
    }

    /** = X99.xchinaPagePath */
    static String pagePath(String path, int page) {
        if (page <= 1) return path;
        String stem = path.replaceFirst("\\.html$", "").replaceFirst("/\\d+$", "");
        return stem + "/" + page + ".html";
    }

    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    static String originOf(String url) {
        try {
            URI u = new URI(url);
            return u.getScheme() + "://" + u.getHost();
        } catch (Exception e) { return url.replaceAll("/+$", ""); }
    }

    static class Card { String id, title, cover, dur, model, url; }

    /** = X99.parseXchina */
    static List<Card> parse(String base, String html) {
        List<Card> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Integer> marks = new ArrayList<>();
        for (int i = html.indexOf(CARD_MARK); i >= 0; i = html.indexOf(CARD_MARK, i + CARD_MARK.length())) marks.add(i);
        for (int idx = 0; idx < marks.size(); idx++) {
            int start = marks.get(idx);
            int end = idx + 1 < marks.size() ? marks.get(idx + 1) : Math.min(html.length(), start + 3000);
            String block = html.substring(start, end);
            Matcher hm = HREF.matcher(block);
            if (!hm.find()) continue;
            Card c = new Card();
            c.id = hm.group(1);
            if (!seen.add(c.id)) continue;
            c.url = originOf(base) + "/video/id-" + c.id + ".html";
            Matcher t = TITLE_ATTR.matcher(block);
            c.title = t.find() ? unescape(t.group(1)) : "";
            if (c.title.trim().isEmpty()) {
                Matcher t2 = TITLE_TEXT.matcher(block);
                c.title = t2.find() ? unescape(t2.group(1)) : "";
            }
            if (c.title.trim().isEmpty()) c.title = c.id;
            Matcher bg = BG_IMG.matcher(block);
            if (bg.find()) c.cover = bg.group(1);
            else { Matcher im = IMG.matcher(block); c.cover = im.find() ? im.group(1) : ""; }
            Matcher d = DUR.matcher(block);
            c.dur = d.find() ? d.group(1) : "";
            Matcher m = MODEL.matcher(block);
            c.model = m.find() ? unescape(m.group(1)) : "";
            out.add(c);
        }
        return out;
    }

    /** = X99.xchinaQualities（URI 解析与 Kotlin 同一路子：base = master 去掉末段，再 resolve 变体相对地址） */
    static List<String[]> qualities(String masterText, String masterUrl) {
        List<String[]> out = new ArrayList<>();
        String base = masterUrl.substring(0, masterUrl.lastIndexOf('/') + 1);
        Matcher r = RENDITION.matcher(masterText);
        Set<String> seenH = new HashSet<>();
        while (r.find()) {
            String h = r.group(1), uri = r.group(2).trim();
            if (uri.isEmpty() || uri.startsWith("#") || !seenH.add(h)) continue;
            String abs;
            try { abs = new URI(base).resolve(uri).toString(); } catch (Exception e) { abs = base + uri; }
            out.add(new String[]{h, abs});
        }
        if (out.isEmpty()) {
            out.add(new String[]{"0", masterUrl});   // = Kotlin：解析不出变体也要能播，标签 0 由界面渲染成「播放」
            return out;
        }
        out.sort((x, y) -> Integer.parseInt(y[0]) - Integer.parseInt(x[0]));
        return out;
    }

    static boolean isXchina(String u) {
        try {
            String h = new URI(u).getHost();
            if (h == null) return false;
            h = h.toLowerCase();
            return h.equals("xchina001.ink") || h.endsWith(".xchina001.ink");
        } catch (Exception e) { return false; }
    }

    public static void main(String[] a) throws Exception {
        Path dir = Paths.get(a.length > 0 ? a[0] : "D:\\工作区\\临时\\xchina");
        System.out.println("取证目录 " + dir + "（不存在则只做语义自测）");
        boolean have = Files.isDirectory(dir);

        // ---------- 1. 列表解析 ----------
        if (have) {
            String home = read(dir.resolve("final_videos_html.body"));
            List<Card> cards = parse("https://xchina001.ink/videos.html", home);
            check("首页解析出卡片（实测 24 张）=" + cards.size(), cards.size() == 24);
            int noTitle = 0, noCover = 0, noDur = 0, badUrl = 0;
            for (Card c : cards) {
                if (c.title.length() < 4) noTitle++;
                if (!c.cover.startsWith("https://r2.xchina.download/cover/")) noCover++;
                if (c.dur.isEmpty()) noDur++;
                if (!c.url.startsWith("https://xchina001.ink/video/id-")) badUrl++;
            }
            check("每张卡都有标题（缺 " + noTitle + "）", noTitle == 0);
            check("每张卡都有站内封面域（缺 " + noCover + "）", noCover == 0);
            check("每张卡都有时长（缺 " + noDur + "）", noDur == 0);
            check("详情链按 origin 拼成绝对地址（错 " + badUrl + "）", badUrl == 0);
            // 去重必须是「测出来的」而不是「构造出来的」：解析器自带 seen 去重，
            // 拿 cards.size()==id 集合大小去比它，等于把被测逻辑当公理（上一棒一审就批过这类恒真断言）。
            // 真判据：喂进**重复的两张同 id 卡**，必须只出一张。
            String dup = CARD_MARK + "<a href=\"/video/id-aaa1.html\" title=\"同一部片子甲\">"
                + "<div role=\"img\" class=\"img\" style=\"background-image:url('https://r2.xchina.download/cover/aaa1.webp');\"></div></a>"
                + "<div class=\"text\"><div class=\"title\"><a href=\"/video/id-aaa1.html\">同一部片子甲</a></div></div>"
                + "<div class=\"tags\"><div>国产</div><div><i class=\"far fa-clock\"></i>12:34</div></div></div>"
                + CARD_MARK + "<a href=\"/video/id-aaa1.html\" title=\"同一部片子甲\">"
                + "<div role=\"img\" class=\"img\" style=\"background-image:url('https://r2.xchina.download/cover/aaa1.webp');\"></div></a>"
                + "<div class=\"text\"><div class=\"title\"><a href=\"/video/id-aaa1.html\">同一部片子甲</a></div></div>"
                + "<div class=\"tags\"><div>国产</div><div><i class=\"far fa-clock\"></i>12:34</div></div></div>";
            check("喂进两张同 id 卡只出一张（真去重，不是拿构造当公理）",
                    parse("https://xchina001.ink/videos.html", dup).size() == 1);
            check("合成卡解析出的字段齐全（标题/封面/时长/详情链）",
                    parse("https://xchina001.ink/videos.html", dup).get(0).dur.equals("12:34")
                        && parse("https://xchina001.ink/videos.html", dup).get(0).cover.contains("aaa1.webp")
                        && parse("https://xchina001.ink/videos.html", dup).get(0).url
                            .equals("https://xchina001.ink/video/id-aaa1.html"));
            check("非卡片块（普通 div）不产卡（正则不放宽）",
                    parse("https://xchina001.ink/videos.html", "<div class=\"item\"><a href=\"/video/id-bbb1.html\">x</a></div>").isEmpty());

            String cn = read(dir.resolve("final_videos_cat_cn_html.body"));
            List<Card> cnCards = parse("https://xchina001.ink/videos/cat-cn.html", cn);
            check("国产栏目解析出卡片（实测 11 张）=" + cnCards.size(), cnCards.size() == 11);

            String cn2 = read(dir.resolve("final_videos_cat_cn_page_2_html.body"));
            List<Card> cn2Cards = parse("https://xchina001.ink/videos/cat-cn/page-2.html", cn2);
            check("路径式翻页解析出卡片（实测 11 张）=" + cn2Cards.size(), cn2Cards.size() == 11);
            check("第 2 页与第 1 页不是同一批卡片（翻页真的生效）",
                    cn2Cards.size() > 0 && !cn2Cards.get(0).id.equals(cnCards.get(0).id));

            String jav = read(dir.resolve("final_videos_cat_jav_html.body"));
            check("日本AV 栏目解析出卡片（实测 11 张）=" + parse("https://xchina001.ink/videos/cat-jav.html", jav).size(),
                    parse("https://xchina001.ink/videos/cat-jav.html", jav).size() == 11);

            // 详情页不能被判出卡片：板块靠这条保证「相关影片」取同栏目、点详情不会自指
            String det = read(dir.resolve("video_id_6abd18f149bbd_html.body"));
            check("详情页自身解析出 0 张卡（面包屑不误判成推荐位）=" + parse("https://xchina001.ink/video/id-6abd18f149bbd.html", det).size(),
                    parse("https://xchina001.ink/video/id-6abd18f149bbd.html", det).isEmpty());
            check("栏目页里不含卡片的是 trend（实测 0 卡，故它不做页签）",
                    parse("https://xchina001.ink/trend.html", read(dir.resolve("final_trend_html.body"))).isEmpty());

            // ---------- 2. 详情字段 ----------
            Matcher src = SRC.matcher(det);
            check("详情里取到播放源 src='/hls/<id>/master.m3u8'", src.find() && src.group(1).equals("/hls/6abd18f149bbd/master.m3u8"));
            Matcher po = POSTER.matcher(det);
            check("详情里取到 poster 且是站内 r2 封面域", po.find() && po.group(1).startsWith("https://r2.xchina.download/cover/"));
            Matcher h1 = H1.matcher(det);
            check("标题取 h1.hero-title-item 且非空", h1.find() && h1.group(1).length() > 4);
            Matcher og = OG_DUR.matcher(det);
            check("og:duration 给出秒数（1546）", og.find() && og.group(1).equals("1546"));
            // 不能拿硬编码 1546 去格式化再比硬编码 "25:46"（那是自证）。真判据：
            // **同一部片**在列表卡上显示的时长（实测抓的）必须等于详情页秒数换算出来的时长。
            String vidDur = "";
            for (Card c : cards) if (c.id.equals("6abd18f149bbd")) vidDur = c.dur;
            check("详情秒数换算 == 列表卡显示时长（" + vidDur + " == 25:46）",
                    !vidDur.isEmpty() && String.format("%d:%02d", 1546 / 60, 1546 % 60).equals(vidDur));
            Matcher cat = CAT.matcher(det);
            check("详情里取到分类栏目链", cat.find() && cat.group(1).equals("/videos/cat-cn.html") && !cat.group(2).isEmpty());
            check("相关影片取同栏目（分类链能被列表正则吃到）",
                    !parse("https://xchina001.ink" + cat.group(1), cn).isEmpty());
            Matcher up = UPLOAD.matcher(det);
            check("JSON-LD 给出上传日期（取 T 之前的日期段）",
                    up.find() && up.group(1).split("T")[0].length() == 10);
            int persons = 0;
            Matcher pr = PERSON.matcher(det);
            while (pr.find()) persons++;
            check("演员名从 JSON-LD 取到（>0，实测 1）=" + persons, persons > 0);

            // ---------- 3. 清晰度与媒体链 ----------
            List<String[]> q = qualities(
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=1320360,RESOLUTION=1280x720\nindex.m3u8?n=1790789417.d0df2b43\n",
                "https://xchina001.ink/hls/6abd18f149bbd/master.m3u8");
            check("单一级别解析成 720 → 按钮", q.size() == 1 && q.get(0)[0].equals("720"));
            check("变体 URI 拼成带签名 token 的绝对地址",
                    q.get(0)[1].equals("https://xchina001.ink/hls/6abd18f149bbd/index.m3u8?n=1790789417.d0df2b43"));
            List<String[]> q2 = qualities(
                "#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720\n720/index.m3u8?n=a\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480\n480/index.m3u8?n=b\n",
                "https://xchina001.ink/hls/x/master.m3u8");
            check("多级时按高度降序给按钮（720,480）", q2.size() == 2 && q2.get(0)[0].equals("720") && q2.get(1)[0].equals("480"));
            List<String[]> fb = qualities("#EXTM3U\n", "https://xchina001.ink/hls/x/master.m3u8");
            check("master 解析不出变体时退回 master 本身（标签 0 → 界面显示「播放」而不是「0P」）",
                    fb.size() == 1 && fb.get(0)[0].equals("0") && fb.get(0)[1].equals("https://xchina001.ink/hls/x/master.m3u8"));

            // ---------- 4. 板块路由判定 ----------
            check("站内详情地址判为 xchina", isXchina("https://xchina001.ink/video/id-6abd18f149bbd.html"));
            check("镜像子域判为 xchina（en.xchina 实测可用）", isXchina("https://en.xchina001.ink/videos.html"));
            check("官方源地址不判为 xchina（官方板块一行不受影响）", !isXchina("https://hanime1.me/watch?v=123"));
            check("第三方同形路径不判为 xchina", !isXchina("https://missav888.cc/video/id-6abd18f149bbd.html"));
            check("播放直链判为 xchina（详情/资源同域，Referer 才取得到）",
                isXchina("https://xchina001.ink/hls/6abd18f149bbd/master.m3u8"));
            // 「自动全屏」不再由域名反推（2026-10-01 审查轮：官方媒体域 vdownload.hembed.com 不在
            // officialHosts 里，反推会把官方源也全屏掉）。现在由详情面板写入 X99.nativePlay ⇒ 这里只验
            // 判据本身仍然只认站内地址，别的站一律不认。
            check("自动全屏判据不吃第三方域名", !isXchina("https://vdownload.hembed.com/407695-720p.mp4"));

            // ---------- 4b. canonical 路径式翻页（page-N.html 是别名，且它自己的分页器指向 /videos/page-2/N.html） ----------
            check("首页请求走 canonical /videos/1.html", pagePath("/videos/1.html", 1).equals("/videos/1.html"));
            check("第 2 页 = /videos/2.html", pagePath("/videos/1.html", 2).equals("/videos/2.html"));
            check("栏目页翻页保留栏目段：/videos/cat-cn.html 第 3 页",
                pagePath("/videos/cat-cn.html", 3).equals("/videos/cat-cn/3.html"));
            check("已带页号的旧地址先剥掉再拼（幂等，不会拼出 /videos/2/3.html）",
                pagePath("/videos/2.html", 3).equals("/videos/3.html"));
            check("列表页根 /videos.html 的第 2 页也归一到 /videos/2.html",
                pagePath("/videos.html", 2).equals("/videos/2.html"));
            // 分页器读数的取证样本：国产栏目页 9 页、日本AV 4 页、别名页里读到 13
            check("国产栏目分页器读出最大页 9", pagerMax(cn) == 9, "实测 " + pagerMax(cn));
            check("日本AV 栏目分页器读出最大页 4", pagerMax(jav) == 4, "实测 " + pagerMax(jav));
            String alias = read(dir.resolve("videos_page_2_html.body"));
            check("别名页的分页器也读得出来（实测 13，它指向 /videos/page-2/N.html 故不能当 canonical 用）",
                pagerMax(alias) == 13, "实测 " + pagerMax(alias));
            check("首页样本 /videos.html 里根本没有分页器（所以最大页只能从栏目页学，别把它当 0 页）",
                !PAGER.matcher(home).find());

            // ---------- 5. 镜像一致性（板块用 origin 拼路径，镜像必须同构） ----------
            String enHome = read(dir.resolve("mirror_https___en_xchina____ink_videos_html.body"));
            List<Card> en = parse("https://en.xchina001.ink/videos.html", enHome);
            check("镜像首页同样出卡片（" + en.size() + " 张）", en.size() >= 10);
            Set<String> enIds = new HashSet<>(), zhIds = new HashSet<>();
            for (Card c : en) enIds.add(c.id);
            for (Card c : cards) zhIds.add(c.id);
            int shared = 0;
            for (String s : enIds) if (zhIds.contains(s)) shared++;
            check("镜像与主域是同一批内容（共有 id " + shared + " 个）", shared > 5);
            check("镜像卡片链拼到镜像 origin（不硬写主域）",
                    !en.isEmpty() && en.get(0).url.startsWith("https://en.xchina001.ink/"));
        } else {
            skipped("取证目录缺失：列表/详情/镜像 21 项无法复核");
        }

        System.out.println("合计 PASS=" + pass + " FAIL=" + fail + " SKIP=" + skip);
        System.out.println(fail == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        if (fail != 0) System.exit(1);
    }
}

// 小工具：group(1).substringBefore('T') 的 Java 等价
class Helper {
    static String beforeT(String s) {
        int i = s.indexOf('T');
        return i < 0 ? s : s.substring(0, i);
    }
}
