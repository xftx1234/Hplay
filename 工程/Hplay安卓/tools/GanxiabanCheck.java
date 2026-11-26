// ganxiaban 板块离线验收器（2026-10-02 新增板块）
// 与 X99 的 gxUnpack / parseGx / gxCover / gxMediaUrl / gxDetail / gxCats 逐条同语义，
// 跑 临时\r38_ganxiaban\ 与 临时\r41_gx_*\ 的真实页面。
//
// **关键做法**：正则不是"我在 Java 里再写一遍"，而是**从 X99.kt 里抽出来直接用**。
// 上一轮就是因为验收器与生产各写一份谓词，出现「验收器全绿、真机显示 (无封面URL)」的分裂
// （见 2026-10-01 记录）。这里让"同语义"这句话由字节相同来保证，而不是由我声称。
//   编译：javac -encoding UTF-8 -d . GanxiabanCheck.java
//   运行：java -Dstdout.encoding=UTF-8 GanxiabanCheck [样本目录] [X99.kt 路径]
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class GanxiabanCheck {
    static int pass = 0, fail = 0;

    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (ok) pass++; else fail++;
    }

    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    // ================= 从生产源码里抽谓词 =================
    static String ktSrc;

    /** 抽 `… = Regex(\s*"""…""")` 里的那段原始正则（Kotlin 三引号内不转义，与 Java 语法子集一致）。 */
    static Pattern prodRegex(String name) {
        Matcher m = Pattern.compile("val\\s+" + name + "\\s*=\\s*Regex\\(\\s*\"\"\"([\\s\\S]*?)\"\"\"")
                .matcher(ktSrc);
        if (!m.find()) return null;
        return Pattern.compile(m.group(1));
    }

    /** 抽出来就用，抽不到必须炸在这里，不能让后面拿"永不匹配"的兜底模式跑出一片假绿。 */
    static Pattern need(String name) {
        Pattern p = prodRegex(name);
        if (p == null) { System.out.println("  !! 生产源码里抽不到 Regex 常量 " + name); System.exit(2); }
        return p;
    }

    /** 抽 `private val gxRewrite = listOf("a" to "b", …)` 的有序对。 */
    static List<String[]> prodPairs(String name) {
        Matcher block = Pattern.compile("val\\s+" + name + "\\s*=\\s*listOf\\(([\\s\\S]*?)\\n\\s*\\)")
                .matcher(ktSrc);
        if (!block.find()) { System.out.println("  FAIL  找不到列表 " + name); fail++; return new ArrayList<>(); }
        List<String[]> out = new ArrayList<>();
        Matcher p = Pattern.compile("\"([^\"]+)\"\\s+to\\s+\"([^\"]+)\"").matcher(block.group(1));
        while (p.find()) out.add(new String[]{p.group(1), p.group(2)});
        return out;
    }

    static Set<String> prodSet(String name) {
        Matcher block = Pattern.compile("val\\s+" + name + "\\s*=\\s*setOf\\(([^)]*)\\)").matcher(ktSrc);
        if (!block.find()) { System.out.println("  FAIL  找不到集合 " + name); fail++; return new LinkedHashSet<>(); }
        Set<String> out = new LinkedHashSet<>();
        Matcher s = Pattern.compile("\"([^\"]+)\"").matcher(block.group(1));
        while (s.find()) out.add(s.group(1));
        return out;
    }

    // ================= 与生产同语义的算法 =================
    static final Pattern HEXRUN = Pattern.compile("^[0-9a-fA-F]+$");

    static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) ((Character.digit(s.charAt(i * 2), 16) << 4) + Character.digit(s.charAt(i * 2 + 1), 16));
        return out;
    }

    static String jsUnescape(String s) {                    // 生产的 gxUnescape
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) { out.append((char) ((hi << 4) + lo)); i += 3; continue; }
            }
            out.append(s.charAt(i)); i++;
        }
        return out.toString();
    }

    /**
     * 与 Kotlin 的 `String.replace(String, String)` 同语义（Java 的 `String.replace(CharSequence, CharSequence)`
     * 也是"全部替换"，两边一致）。站点自己的 JS 用的是**只换第一处**的 `replace`，但每个 token 在一条 URL 里
     * 只可能出现一次，两种语义在这个语料上不可能分岔——所以不必为此再实现一个"只换第一处"。
     */
    static String rewrite(String u, String from, String to) { return u.replace(from, to); }

    /** 生产的 gxUnpack：匹不到打包外壳就原样返回。 */
    static String unpack(String html, Pattern packed) {
        Matcher m = packed.matcher(html);
        if (!m.find()) return html;
        try {
            return new String(unhex(m.group(1)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return html;
        }
    }

    static String gxCover(String u, Pattern base) {         // 生产的 gxCover
        if (!u.contains("/base/") || !u.endsWith(".cache")) return u;
        String b64 = u.substring(u.lastIndexOf("/base/") + 6).replace(".cache", "");
        int pad = (4 - b64.length() % 4) % 4;
        try {
            String real = new String(Base64.getDecoder().decode(b64 + "==".substring(0, pad)), StandardCharsets.UTF_8);
            return real.startsWith("https://") ? real : u;
        } catch (Exception e) {
            return u;
        }
    }

    static String gxMediaUrl(String playHtml, Pattern player, List<String[]> chain) {
        Matcher m = player.matcher(playHtml);
        if (!m.find()) return "";
        String arg = jsUnescape(m.group(1));
        int k = arg.indexOf("url=");
        if (k < 0) return "";
        String hex = arg.substring(k + 4).trim();
        if (hex.length() < 20 || !HEXRUN.matcher(hex).matches()) return "";
        String u;
        try { u = new String(unhex(hex), StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
        for (String[] pr : chain) u = rewrite(u, pr[0], pr[1]);   // 顺序执行，与站点 artplayer.js 的 replace 链一致
        return u.startsWith("http") ? u : "";
    }

    static class Card { String id, url, title = "", thumb = "", dur = ""; }

    static List<Card> parseGx(String pageUrl, String html, Pattern pic, Pattern tit) {
        String origin = pageUrl.replaceFirst("^(https?://[^/]+).*", "$1");
        Map<String, String> titles = new LinkedHashMap<>();
        Matcher t = tit.matcher(html);
        while (t.find()) {
            String id = t.group(1);
            String v = t.group(2).replaceAll("\\s+", " ").trim();
            titles.putIfAbsent(id, v);
        }
        List<Card> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = pic.matcher(html);
        while (m.find()) {
            String id = m.group(1), url = origin + "/v/" + id + ".php";
            if (!seen.add(url)) continue;
            String title = titles.getOrDefault(id, "");
            if (title.length() < 2) continue;
            Card c = new Card();
            c.id = id; c.url = url; c.title = title;
            c.thumb = gxCover(m.group(2), null);
            c.dur = (m.groupCount() >= 3 && m.group(3) != null) ? m.group(3).trim() : "";
            out.add(c);
        }
        return out;
    }

    static String rootDomain(String host) {
        String h = host.replaceFirst("^www\\.", "");
        String[] p = h.split("\\.");
        return p.length >= 2 ? p[p.length - 2] : p[0];
    }

    // ================= 主流程 =================
    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "D:/工作区/临时/r38_ganxiaban";
        String ktPath = a.length > 1 ? a[1] : "D:/工作区/工程/AvPlay/独立版/src/com/han1me/X99.kt";
        ktSrc = read(ktPath).replace("\r\n", "\n");
        System.out.println("样本目录 " + dir + "；生产源码 " + ktPath + "（" + ktSrc.length() + " 字符）");

        Pattern packed = need("gxPackedRe"), pic = need("gxPicRe"), tit = need("gxTitRe"),
                ep = need("gxEpisodeRe"), total = need("gxTotalRe"), h1 = need("gxH1Re"),
                poster = need("gxPosterRe"), player = need("gxPlayerRe");
        List<String[]> chain = prodPairs("gxRewrite");
        Set<String> roots = prodSet("gxRoots");
        ck("1 生产源码里 8 条 ganxiaban 正则全部抽到（验收器用的就是生产那一份，不是重写一遍）",
                chain.size() == 7 && roots.size() == 6,
                "重写链 " + chain.size() + " 步 · 根域 " + roots);

        // ---- 2 打包外壳还原：期望值取自 REPORT.md 里肉眼核对过的字面量，不取解析器自己的输出 ----
        String rawList = read(dir + "/gx_01_vl_56.php.html");
        String list = unpack(rawList, packed);
        ck("2 列表页 unpack：出现原文字面量《共22754部》与《亚洲有码》列表《当前第1页》",
                list.contains("共22754部") && list.contains("《亚洲有码》列表《当前第1页》"),
                list.length() + " 字符");
        Matcher tm = total.matcher(list);
        boolean gotTotal = tm.find();
        String totalStr = gotTotal ? tm.group(1) : "0";
        ck("3 计数正则独立量纲：共(数字)部 抓到 22754", gotTotal && totalStr.equals("22754"), totalStr);

        // ---- 4 否定对照：锚点少写一层调用就会去匹配**内层**那份，解出一段"看着像正文"的错位内容
        //      （2026-10-02 实测踩过：那一版的 var player 解出来是半截 hex，站点其实没变）。----
        String[] degenerate = {
            // 少掉内层 M(hex,"%") 这一级
            "document\\.write\\(\\s*[A-Za-z_$][\\w$]*\\(\\s*\"([0-9a-fA-F]{400,})\"",
            // 少掉 `,"%")` 这个参数约束
            "document\\.write\\(\\s*[A-Za-z_$][\\w$]*\\(\\s*unescape\\(\\s*[A-Za-z_$][\\w$]*\\(\\s*\"([0-9a-fA-F]+)\"",
        };
        Matcher sm = packed.matcher(rawList);
        boolean strictHit = sm.find();
        String strictHex = strictHit ? sm.group(1) : "";
        StringBuilder how = new StringBuilder();
        for (String d : degenerate) {
            Matcher dm = Pattern.compile(d).matcher(rawList);
            String out = dm.find() ? unpack(rawList, Pattern.compile(d)) : "(不匹配)";
            how.append(out.equals(list) ? "同" : "异").append('/');
        }
        ck("4 严格锚点与正文逐字节一致；两种『少写一点』的锚点里，少一层的那份必然解出不同正文（否定对照成立）",
                strictHit && strictHex.length() > 800 && how.toString().startsWith("异"),
                "严格 hex=" + strictHex.length() + " 字符 · 退化式表现=" + how);
        ck("5 未打包的正文原样返回（幂等，防二次解）",
                unpack(list, packed).equals(list), "");

        // ---- 6 卡片 ----
        List<Card> cards = parseGx("https://www.ganxiaban.com/vl/56.php", list, pic, tit);
        ck("6 列表页 20 张卡（实测 class=\"sTit\" 计数 = 20）", cards.size() == 20, cards.size() + " 张");
        int noThumb = 0, noTitle = 0, badUrl = 0, deproxied = 0;
        for (Card c : cards) {
            if (c.thumb.isEmpty()) noThumb++;
            if (c.title.length() < 2) noTitle++;
            if (!c.url.matches("https://www\\.ganxiaban\\.com/v/\\d+\\.php")) badUrl++;
            if (c.thumb.contains("imgcdn01.dycp444.com") && !c.thumb.contains("cache.sgvafw.com")) deproxied++;
        }
        ck("7 卡片无一张缺封面/缺标题/URL 形态错", noThumb == 0 && noTitle == 0 && badUrl == 0,
                "缺图 " + noThumb + " 缺题 " + noTitle + " 错URL " + badUrl);
        ck("8 封面 20/20 全部从 cache.sgvafw 反代还原成源站直链", deproxied == cards.size() && cards.size() == 20,
                deproxied + "/" + cards.size());
        Card first = cards.isEmpty() ? new Card() : cards.get(0);
        ck("9 首张卡逐字段核对（REPORT.md §4 卡片原文）",
                first.id.equals("509983")
                        && first.title.startsWith("MIAB-137")
                        && first.thumb.equals("https://imgcdn01.dycp444.com/pic8/c/8/c8f166e9a10247d51ae84d4783e9e567.320.255.jpg")
                        && first.dur.equals("01:56:37"),
                first.id + " / " + first.title.substring(0, Math.min(12, first.title.length())) + " / " + first.dur);
        ck("10 时长字段是 时:分:秒（这一族给 H:MM:SS，别的站给 MM:SS）",
                first.dur.matches("\\d{1,2}:\\d{2}:\\d{2}"), first.dur);

        // ---- 11 分页：两条独立来源必须一致（分页器最大页 vs 共N部/20）----
        Matcher pg = Pattern.compile("/vl/56-(\\d+)\\.php").matcher(list);
        int pager = 0;
        while (pg.find()) pager = Math.max(pager, Integer.parseInt(pg.group(1)));
        int byCount = (Integer.parseInt(totalStr) + 19) / 20;
        ck("11 末页两条独立推导都是 1138（分页器 " + pager + " / 计数 " + byCount + "）",
                pager == 1138 && byCount == 1138, "");

        // ---- 12 介绍页 ----
        String det = unpack(read(dir + "/gx_02_v_509743.php.html"), packed);
        Matcher hm = h1.matcher(det);
        boolean hasH1 = hm.find();
        String h1Txt = hasH1 ? hm.group(1) : "";
        ck("12 介绍页 h1 去掉装饰 <i> 后拿到完整片名",
                h1Txt.equals("【推特巨乳】家有e妻 最新紧束强奸系列3"), h1Txt);
        ck("13 公用的 h1Re（<h1[^>]*>([^<]{2,200})</h1>）在这一族上必然匹配不上 ⇒ 专用式不是多余的",
                !Pattern.compile("<h1[^>]*>([^<]{2,200})</h1>").matcher(det).find(), "");
        Matcher em = ep.matcher(det);
        boolean hasEp = em.find();
        String epPath = hasEp ? em.group(1) : "";
        ck("14 选集锚点 → /p/509743-5-1.php（查询名 nm=/gh= 每次不同，所以只锚路径）",
                epPath.equals("/p/509743-5-1.php"), epPath);
        Matcher pm = poster.matcher(det);
        boolean hasPoster = pm.find();
        String posterRaw = hasPoster ? pm.group(1) : "";
        ck("15 介绍页海报走 lazy 的 data-original（src 是 /img/grey.gif 占位）",
                hasPoster && posterRaw.contains("cache.sgvafw.com")
                        && gxCover(posterRaw, null).startsWith("https://imgcdn01.dycp444.com/pic8/6/1/"),
                gxCover(posterRaw, null));

        // ---- 16 播放页 → 播址：期望值是 REPORT.md §4 里肉眼抄下的那条 ---
        String play = unpack(read(dir + "/gx_01_p_509743-5-1.php.html"), packed);
        String media = gxMediaUrl(play, player, chain);
        ck("16 播址解码 + host 重写 = https://c32.dycp444.com/202405/06/S9pTLbLC/500kb/hls/index.m3u8?sign=kGkStR4KcJUjUSYLwf2GCA&t=1790956252",
                media.equals("https://c32.dycp444.com/202405/06/S9pTLbLC/500kb/hls/index.m3u8?sign=kGkStR4KcJUjUSYLwf2GCA&t=1790956252"),
                media.length() > 20 ? media.substring(0, 46) + "…" : "空");
        List<String[]> c2 = new ArrayList<>(chain);
        c2.removeIf(p -> p[0].equals("c22.cdn.dycp444.com"));
        String unrewritten = gxMediaUrl(play, player, c2);
        ck("17 去掉重写那一步就得到连不通的 c22.cdn.dycp444.com（证明这步不是装饰）",
                unrewritten.startsWith("https://c22.cdn.dycp444.com/"), unrewritten.substring(0, 32));
        String sig = play;
        ck("18 var player 的锚点在原文里唯一（只出现一次）",
                sig.split("var\\s+player\\s*=\\s*unescape", -1).length == 2,
                sig.split("var player", -1).length - 1 + " 处");
        // 顺序敏感：c11 要先变 c21 再变 c31
        Pattern fake = Pattern.compile("var\\s+player\\s*\\(\\s*\\)");
        String c11 = "https://c11.cdn.dycp444.com/a/index.m3u8";
        for (String[] pr : chain) c11 = rewrite(c11, pr[0], pr[1]);
        ck("19 重写链按站点顺序执行：c11 → c31（不是停在 c21）", c11.equals("https://c31.dycp444.com/a/index.m3u8"), c11);

        // ---- 20 栏目弹窗 ----
        String map = unpack(read(dir + "/gx_map.html"), packed);
        Set<String> vlInPage = new LinkedHashSet<>();
        Matcher vm = Pattern.compile("href=[\"'](/vl/(\\d+)\\.php)").matcher(map);
        while (vm.find()) vlInPage.add(vm.group(2));
        Matcher catBlock = Pattern.compile("val\\s+gxCats\\s*:\\s*List<Pair<String, String>>\\s*=\\s*listOf\\(([\\s\\S]*?)\\n\\s*\\)")
                .matcher(ktSrc);
        Set<String> declared = new LinkedHashSet<>();
        int catCount = 0;
        if (catBlock.find()) {
            Matcher cp = Pattern.compile("\"/vl/(\\d+)\\.php\"").matcher(catBlock.group(1));
            while (cp.find()) { declared.add(cp.group(1)); catCount++; }
        }
        ck("20 弹窗栏目全部命中 /map.html 里真实存在的 /vl/ 栏目（" + catCount + " 条，页面侧 " + vlInPage.size() + " 条）",
                catCount == 32 && vlInPage.containsAll(declared), "声明 " + catCount + " / 页面 " + vlInPage.size());
        List<String> nlIds = new ArrayList<>();
        for (String id : declared) {
            int n = Integer.parseInt(id);
            if ((n >= 38 && n <= 45) || (n >= 47 && n <= 54)) nlIds.add(id);
        }
        ck("21 图片专区(38..45)与小说专区(47..54)不进弹窗（那些是 /nl/ 文字目录，无卡无媒体）",
                nlIds.isEmpty(), "声明的 id 最小/最大 = " + Collections.min(declared) + "/" + Collections.max(declared));
        ck("22 /map.html 里 parseGx 得到 0 张卡（导航条/栏目链不会被当成视频）",
                parseGx("https://www.ganxiaban.com/map.html", map, pic, tit).isEmpty(), "");
        ck("23 列表页的卡片互不重复（20 个 id 全不同）",
                cards.stream().map(c -> c.id).distinct().count() == cards.size(), "");

        // ---- 24 URL 归属判定 ----
        String v = "https://www.ganxiaban.com/v/509743.php?v=1&aid=26273";
        String p1 = "https://www.ganxiaban.com/p/509743-5-1.php", nl = "https://www.ganxiaban.com/nl/38.php",
                ar = "https://www.ganxiaban.com/n/311124.php", other = "https://www.hanime1.me/watch?v=1";
        ck("24 isGanxiabanUrl：/v/ 认，/p/、/nl/、/n/、别的站都不认",
                belongs(v, roots) && !belongs(p1, roots) && !belongs(nl, roots)
                        && !belongs(ar, roots) && !belongs(other, roots), "");
        ck("25 同族前端（mei78 / avtt3581）也归这一板块：它们的 id 与 ganxiaban 同一命名空间",
                belongs("https://www.mei78.com/v/562.php", roots)
                        && belongs("https://www.avtt3581.com/v/509924.php", roots), "");

        // ---- 26 搜索必须拒绝：凭据是「与关键词无关的同一组 9 个 id」----
        String ids = read(dir + "/gx_search_ids.txt").trim();
        String[] arr = ids.split("\\s+");
        ck("26 /search.php?wd= 回的是 9 个换行分隔的 id、不是 HTML ⇒ boardSearch 对这一族必须写死 null",
                arr.length == 9 && ids.matches("[0-9\\s]+") && !ids.contains("<"),
                ids.replace("\n", ","));
        ck("27 生产源码里 ganxiaban 的搜索分支确实是 -> null（不是靠探测失败兜住）",
                Pattern.compile("GANXIE_NAME[^\\n]*\\n\\s*siteName\\.equals\\(GANXIE_NAME[\\s\\S]{0,400}?->\\s*null")
                        .matcher(ktSrc).find()
                        || Pattern.compile("siteName\\.equals\\(GANXIE_NAME, ignoreCase = true\\) -> null").matcher(ktSrc).find(),
                "");

        // ---- 28 板块路由齐备：boardOf / boardDetail / boardListing / boardMaxPage / boardOptions 都得认它 ----
        ck("28 五处派发里都挂了 ganxiaban（缺一个就会「板块在、点进去没内容」）",
                ktSrc.contains("isGanxiabanUrl(u) -> \"ganxiaban\"")
                        && ktSrc.contains("\"ganxiaban\" -> runCatching { gxDetail(v) }")
                        && ktSrc.contains("-> gxListing(base, tabPath ?: \"/vl/56.php\", page)")
                        && ktSrc.contains("-> gxMaxPage(tabPath ?: \"/vl/56.php\")")
                        && ktSrc.contains("+ gxCats"),
                "boardOf/boardDetail/boardListing/boardMaxPage/boardOptions");
        ck("29 站点表里 ganxiaban 三条线并存且 qinav/xchina 没被顶掉（上一轮改 builtinSites 时真顶掉过）",
                Pattern.compile("name = QINAV_NAME").matcher(ktSrc).find()
                        && Pattern.compile("name = XCHINA_NAME").matcher(ktSrc).find()
                        && Pattern.compile("name = GANXIE_NAME").matcher(ktSrc).find()
                        && read(ktPath).contains("https://www.mei78.com")
                        && read(ktPath).contains("https://www.avtt3581.com"), "");
        ck("30 上一版「只收进站按钮」的 isEntryOnly 已随真板块一起删掉（不留死码）",
                !ktSrc.contains("fun isEntryOnly") && !read(dir + "/REPORT.md").isEmpty(), "");
        // 「地区/来源/类型/更新时间」四档共用同一个 emTit，更新时间那档会被一起抓到 ⇒ 生产按日期形状筛掉
        Pattern gxDate = need("gxDateRe");
        Matcher fl = prodRegex("gxFieldRe").matcher(det);
        List<String> fields = new ArrayList<>();
        while (fl.find()) fields.add(fl.group(1).trim());
        ck("31 字段式确实会把「更新时间」的日期一起抓进来（这就是生产要筛掉它的原因）",
                fields.stream().anyMatch(s -> gxDate.matcher(s).matches()), fields.toString());
        ck("32 生产在 tags 那一步引用了 gxDateRe 做筛除（不是只写在注释里）",
                Pattern.compile("tags = gxFieldRe[\\s\\S]{0,220}?gxDateRe\\.matches\\(it\\)").matcher(ktSrc).find(), "");

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    static boolean belongs(String url, Set<String> roots) {
        Matcher m = Pattern.compile("^https?://([^/]+)(/[^?#]*)?").matcher(url);
        if (!m.find()) return false;
        String host = m.group(1).replaceAll(":\\d+$", "");
        String path = m.group(2) == null ? "/" : m.group(2);
        return roots.contains(rootDomain(host)) && path.matches("^/v/\\d+\\.php$");
    }
}
