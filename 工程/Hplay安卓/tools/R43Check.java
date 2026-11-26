import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第 43 轮离线验收器：36co / ininav 两个新板块 + qinav 边缘节点加速 + 封面加载提速。
 *
 * 纪律（都是以前踩过之后定下来的）：
 *  · **不手抄生产正则与表**：`edgeHostSuffixes`、`cfEdgeSeeds`、`co36Cats`、`withPage` 里那条 Regex、
 *    `thumbHttp` 的三个数——全部从 X99.kt / HanimeClient.kt 的源码文本里抽出来用；抽不到就 exit(2)，
 *    不给「永不匹配的兜底模式」留活路。
 *  · 断言打在**用户可见面**上：新板块必须与既有板块同时出现在 builtinSites 里。
 *  · 每条「不存在 X」都配一条「非空」，否则空输出上恒真。
 *  · 路径写死在本文件里（中文路径经 bash→Java argv 会被 ANSI 码页打成 mojibake）。
 */
public class R43Check {
    static final String WS = "D:/工作区/";
    static final String X99 = WS + "工程/AvPlay/独立版/src/com/han1me/X99.kt";
    static final String CLIENT = WS + "工程/AvPlay/hanime模块/src/com/javrocket/hanime/HanimeClient.kt";
    static final String BOARD = WS + "工程/AvPlay/独立版/src/com/han1me/X99Board.kt";
    static final String APP = WS + "工程/AvPlay/独立版/src/com/han1me/Han1meApp.kt";
    static final String THUMB = WS + "工程/Hplay安卓/src/com/han1me/web/HplayThumb.kt";
    static final String ENV = WS + "工程/Hplay安卓/src/com/han1me/HplayEnv.kt";
    static final String D36 = WS + "临时/r43_36co/";

    static int pass = 0;
    static int fail = 0;
    static final List<String> fails = new ArrayList<>();

    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    static void ok(String what) {
        pass++;
    }

    static void no(String what) {
        fail++;
        fails.add(what);
        System.out.println("FAIL  " + what);
    }

    static void check(boolean cond, String what) {
        if (cond) {
            ok(what);
        } else {
            no(what);
        }
    }

    /** 取 from 之后、到下一个「行首恰好是 pad 个空格 + }」为止的函数体（切片收尾必须锚行首）。 */
    static String bodyOf(String src, String from) {
        int i = src.indexOf(from);
        if (i < 0) {
            return "";
        }
        int j = src.indexOf("\n    }", i);
        return j < 0 ? "" : src.substring(i, j + 6);
    }

    static List<String> strLiterals(String block) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(block);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    public static void main(String[] a) throws Exception {
        String x99 = read(X99);
        String client = read(CLIENT);
        String board = read(BOARD);
        String app = read(APP);
        String thumb = read(THUMB);
        String env = read(ENV);
        check(!x99.isEmpty() && !client.isEmpty() && !board.isEmpty() && !app.isEmpty()
            && !thumb.isEmpty() && !env.isEmpty(), "六份被审源码都非空");

        // ---------- 1 边缘节点名单：谁进、谁绝不能进 ----------
        String setBlk = slice(x99, "private val edgeHostSuffixes = setOf(", ")");
        List<String> edge = strLiterals(setBlk);
        check(!edge.isEmpty(), "edgeHostSuffixes 抽到了内容（" + edge.size() + " 项）");
        check(edge.contains("qinav.com"), "名单含 qinav.com");
        check(edge.contains("xchina.co") && edge.contains("xchina001.ink"), "名单含 xchina 页面域两枚");
        check(edge.contains("xchina.download"), "名单含 xchina 的**分片域**（只给页面域做的话清单通了分片仍被污染）");
        for (String bad : new String[] {"hanime1.me", "hembed.com", "googlevideo.com", "cloudflare-dns.com"}) {
            check(!edge.contains(bad), "名单不含 " + bad + "（官方源与 DoH 端点的网络路径必须一字不动）");
        }
        check(!x99.contains("dohHostSuffixes"), "旧的 dohHostSuffixes 符号已不存在（不是改了两处留一处）");

        // ---------- 2 CF anycast 种子必须真的落在 Cloudflare 公布段里 ----------
        String seedBlk = slice(x99, "private val cfEdgeSeeds = listOf(", ")\n");
        List<String> seeds = strLiterals(seedBlk);
        check(seeds.size() >= 5, "cfEdgeSeeds 抽到 " + seeds.size() + " 个地址");
        Pattern v4 = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
        for (String ip : seeds) {
            check(v4.matcher(ip).matches(), "种子 " + ip + " 是合法 IPv4");
            String[] q = ip.split("\\.");
            int o0 = Integer.parseInt(q[0]);
            int o1 = Integer.parseInt(q[1]);
            boolean cf = (o0 == 104 && o1 >= 16 && o1 <= 31)      // 104.16.0.0/12
                || (o0 == 172 && o1 >= 64 && o1 <= 71)            // 172.64.0.0/13
                || (o0 == 141 && o1 == 101)                       // 141.101.0.0/16
                || (o0 == 188 && o1 == 114 && o2(q) >= 96 && o2(q) <= 99);   // 188.114.96.0/22
            check(cf, "种子 " + ip + " 属于 Cloudflare 公布段");
        }
        check(!seeds.contains("104.16.132.229"), "实测回 403 的那个边缘（104.16.132.229）没被收进种子");

        // ---------- 3 先验后给的实现要点 ----------
        String rh = bodyOf(x99, "private fun resolveBoardHost(");
        check(!rh.isEmpty(), "resolveBoardHost 函数体切到了");
        check(rh.contains("probeTls(hostname, ip)"), "候选是**逐个实测**后才采用的");
        check(rh.contains("persistedEdges(hostname) + cfEdgeSeeds + dohIps")
                && rh.contains("persistedEdges(hostname) + dohIps + cfEdgeSeeds")
                && rh.contains("cfFrontedFamilies.any"),
            "候选顺序分两种：实测过的 CF 族把内置边缘排在 DoH 前，其余族仍把 DoH 排在前面（xchina 今天的行为不变）");
        check(rh.contains("systemLookup()") && rh.contains("union.isEmpty()"),
            "一个都没验通时退回解析原样，不把失败伪装成空地址列表");
        String pt = bodyOf(x99, "private fun probeTls(");
        check(pt.contains("endpointIdentificationAlgorithm = \"HTTPS\""),
            "钉 IP 时保留完整证书校验（少这一句就等于把钉 IP 降级成绕过证书）");
        check(pt.contains("server_hostname") || pt.contains("createSocket(raw"), "TLS 用真实域名做 SNI");
        check(x99.contains("@Volatile") && x99.contains("var lastBoardDns"), "解析结论留了人话文案");
        check(board.contains("X99.lastBoardDns"), "板块失败时把解析结论画上屏（手机抑制日志）");

        // ---------- 4 builtinSites：用户可见面，新老板块必须同时在场 ----------
        String bs = slice(x99, "private val builtinSites: List<X99Site> = listOf(", "\n    )\n");
        check(bs.contains("XCHINA_NAME") && bs.contains("QINAV_NAME") && bs.contains("GANXIE_NAME"),
            "三家既有内置板块仍在（编辑没吃掉兄弟条目）");
        check(bs.contains("CO36_NAME") && bs.contains("ININAV_NAME"), "两家新板块同时在册");
        check(bs.split("X99Site\\(", -1).length - 1 == 5, "builtinSites 恰好 5 条（实际 "
            + (bs.split("X99Site\\(", -1).length - 1) + "）");
        check(bs.contains("lines = listOf("), "每条都自带 lines（UI 取 selectedLine 不会 NPE）");
        check(x99.contains("const val CO36_HOME = \"http://www.36co.cc\""),
            "36co 的站内地址是**明文 http**（实测 443 带该 SNI 被 RST、80 回 200）");

        // ---------- 5 归属判据：原生 families 表与「不装假原生」两头都要守住 ----------
        String bo = bodyOf(x99, "private fun boardOf(u: String): String? = when {");
        check(!bo.isEmpty() && bo.contains("isXchinaUrl"), "boardOf 函数体切到了");
        check(bo.contains("isCo36Url(u) -> \"co36\""), "第 44 轮：36co 已进 boardOf（播址直写 player_aaaa）");
        check(!bo.contains("ININAV") && !bo.contains("ininav"),
            "ininav 不许进 boardOf（一个页面都没取到过，写进去就是只有 UI 的假原生）");
        String cd = bodyOf(x99, "fun co36Detail(v: HanimeVideo): HanimeDetail? {");
        check(!cd.isEmpty() && cd.contains("getPlaylist(master, v.url)"),
            "36co 的清单必须**运行时逐条验**（getPlaylist 只认 #EXTM3U 且分片 >2、挡 tip.ts）");
        check(cd.contains("if (list.isEmpty()) return null"), "验不过就返回 null ⇒ 界面自动回落「网页播放」");
        check(cd.contains("openNativeDetail(v.url, listOf(master)"), "验通过的播址要登记原生媒体主机");
        String jm = bodyOf(x99, "private fun co36JsonField(blob: String, key: String): String {");
        check(!jm.isEmpty() && jm.contains("Json.parseToJsonElement(blob)"),
            "player_aaaa 走 JSON 解（宿具实测正则直取会把 `\\/` 的反斜杠带进播址，播放器必失败）");
        String ue = bodyOf(x99, "private fun jsonUnescape(s: String): String {");
        check(!ue.isEmpty() && ue.contains("val noSlash = s.replace(")
                && ue.contains("u([0-9a-fA-F]{4})"),
            "兜底的反斜杠还原住在 jsonUnescape 里（判据用不含反斜杠的片段，" +
                "免得断言自己在三层转义里写坏——本轮已栽三次）");
        String emb = bodyOf(x99, "fun isEmbedPlayerUrl(u: String): Boolean {");
        check(emb.contains("rootDomain(u) == \"36co\"") && emb.contains("return false"),
            "36co 与黄色仓库族形状相同，必须按域名互斥（否则两族互相抢、白花一发请求）");
        check(x99.contains("fun co36Listing(") && x99.contains("parseGeneric(u, html)"),
            "co36Listing 复用生产通用解析（不另抄一套正则）");
        check(x99.contains("co36Listing(base, tabPath, page)"), "boardListing 接上了 36co");
        // 家族数只能由一张表派生：本轮之前它和表里的样本各写一遍，两句一起说了假话
        check(x99.contains("setOfNotNull(*nativeFamilyProbe().map { it.second }.toTypedArray()).size"),
            "nativeBoardCount 由 nativeFamilyProbe 派生（不再手抄第二份家族表）");
        check(x99.contains("cn.avjoy.ws/video/14614") && x99.contains("cn.superporn.ws/video/10650"),
            "两条按记忆写错的样本已换成取证里真实出现过的 URL");
        check(!x99.contains("cn.avjoy.ws/1/x") && !x99.contains("www.superporn.com/videos/abc"),
            "旧的错误样本必须消失（留着就会有人再抄一次）");
        String fams = slice(x99, "private val NATIVE_FAMILY_SAMPLES: List<String> = listOf(", "\n    )");
        // 计数按 `"http` 而不是 `"https://`：36co 那条按实测是**明文 80**，写成 http 才是对的
        int nSample = fams.split("\"http", -1).length - 1;
        check(nSample >= 13, "家族样本表非空且有 13 条（实际 " + nSample + " 条）");
        check(x99.contains("isCo36Url(u) ||") || x99.contains("|| isCo36Url(u)"),
            "相关影片的同族判据里也认 36co");

        // ---------- 6 分类表 == 真实页面（逐条对样本） ----------
        String catBlk = slice(x99, "val co36Cats: List<Pair<String, String>> = listOf(", "\n    )");
        Map<String, String> cats = pairs(catBlk);
        check(cats.size() == 18, "co36Cats 18 条（实际 " + cats.size() + "）");
        String home = read(D36 + "home.html");
        check(!home.isEmpty(), "36co 首页样本非空");
        int miss = 0;
        for (Map.Entry<String, String> e : cats.entrySet()) {
            if (!home.contains("href=\"" + e.getValue() + "\"") || !home.contains(e.getKey())) {
                miss++;
                System.out.println("  样本里找不到这一条分类：" + e.getKey() + " -> " + e.getValue());
            }
        }
        check(miss == 0, "18 条分类的**名称与路径都能在取证样本里逐字找到**（缺 " + miss + " 条）");
        check(x99.contains("listOf(\"首页·最新\" to \"/\") + co36Cats"), "boardOptions 给 36co 列首页 + 18 分区");

        // ---------- 7 翻页形式：生产的正则吃样本自己写出的链接 ----------
        String wp = bodyOf(x99, "private fun withPage(u: String, page: Int): String {");
        check(!wp.isEmpty(), "withPage 函数体切到了");
        Matcher rxm = Pattern.compile("Regex\\(\"\"\"(\\^\\./vod/search.*?|.*?vod/search.*?)\"\"\"").matcher(wp);
        String rx = rxm.find() ? rxm.group(1) : "";
        check(!rx.isEmpty(), "从 withPage 里抽出了 maccms 分页正则（抽不到就判失败，不留兜底）");
        if (!rx.isEmpty()) {
            String search = newest(D36, "pg_", ".html");
            check(!search.isEmpty(), "找到搜索页样本：" + search);
            String sample = search.isEmpty() ? "" : read(D36 + search);
            String oneUrl = "";
            Matcher m1 = Pattern.compile("/index\\.php/vod/search/page/1/wd/[^\"<]*").matcher(sample);
            if (m1.find()) {
                oneUrl = m1.group();
            }
            check(!oneUrl.isEmpty(), "样本分页器里确实有 page/1 那一条");
            // 用**生产那条正则**自己算出第 2 页（替换部分是 withPage 里 "page/" + page 那一行）
            String twoUrl = "";
            Matcher mp = Pattern.compile(rx).matcher(oneUrl);
            if (mp.find()) {
                twoUrl = mp.group(1) + "page/2" + mp.group(2);
            }
            check(!twoUrl.isEmpty() && twoUrl.contains("page/2/") && sample.contains(twoUrl),
                "生产正则作用在样本的 page/1 上，算出的 page/2 链接样本里也真的存在（形式来自站内，不是我编的）");
        }
        check(x99.contains("search/page/1/wd/$k.html"), "searchForms 里加了 maccms 的路径式搜索入口");

        // ---------- 8 封面提速：数值从源码里读，不重新打一遍 ----------
        String thb = slice(client, "val thumbHttp: OkHttpClient by lazy {", "\n    }");
        check(!thb.contains(".dns("), "thumbHttp 没有另设 dns（DoH/边缘节点靠 newBuilder 继承）");
        check(thb.contains("http.newBuilder()"), "thumbHttp 由主客户端派生（代理/cookie/拦截器同源）");
        int tc = num(thb, "connectTimeout\\((\\d+), TimeUnit.SECONDS\\)");
        int tr = num(thb, "readTimeout\\((\\d+), TimeUnit.SECONDS\\)");
        int mc = num(slice(client, "val http: OkHttpClient by lazy {", "\n    }"), "connectTimeout\\((\\d+), TimeUnit.SECONDS\\)");
        int mr = num(slice(client, "val http: OkHttpClient by lazy {", "\n    }"), "readTimeout\\((\\d+), TimeUnit.SECONDS\\)");
        check(tc > 0 && mc > 0 && tc < mc, "封面连接超时 " + tc + "s 短于主客户端 " + mc + "s");
        check(tr > 0 && mr > 0 && tr < mr, "封面读超时 " + tr + "s 短于主客户端 " + mr + "s");
        check(num(thb, "ConnectionPool\\((\\d+),") >= 6, "封面连接池空闲上限已抬高（同域十几张不该反复握手）");
        check(thb.contains("maxRequestsPerHost = 8") || thb.contains("maxRequestsPerHost = 1"), "封面有自己的排队口径");
        String ft = bodyOf(client, "fun fetchThumbBytesSync(url: String): Pair<ByteArray?, String> {");
        check(!ft.isEmpty() && ft.contains("thumbDeadUntil[host]"), "取图有失败域短路");
        check(ft.contains("thumbRefererFirst[host]"), "Referer 写法按域记忆（不再每张必发两趟）");
        check(client.contains("thumbGate.withPermit"), "并发闸门挂在生产函数上");
        check(!thumb.contains("HanimeClient.http.newCall"), "界面层已不再自己发请求（旧的两趟逻辑撤干净了）");
        check(thumb.contains("HanimeClient.fetchThumbBytes"), "界面层调的是生产函数");
        check(thumb.contains("MAX_BYTES") && thumb.contains("values.sumOf"), "封面缓存按**解码后字节数**收，不再按张数");
        check(thumb.contains("inSampleSize"), "解码带降采样");
        // 封面域名改写试过又被撤掉：四种写法（无扩展名 / .jpg / 日期用 / 分段 / vod.jpg）实测全 404，
        // 改完之后没有任何一条能证明比站内原样更好 ⇒ 断言的是「不许留这种改动」。
        check(!x99.contains("co36ThumbIp") && !x99.contains("co36ThumbHost"),
            "封面的主机改写没有残留（只能变坏、验不出变好的改动不留）");
        check(x99.contains("全部 404") && x99.contains("r44_gate_final.log"),
            "撤掉改写的理由与取证文件留在源码注释里，免得以后又被照抄回来");

        // ---------- 9 用户页素材槽：桌面不留空壳 ----------
        check(app.contains("object HplaySlots"), "上游有 HplaySlots 注入槽");
        check(app.contains("if (HplaySlots.pickSupported)"), "没有选择器的平台（桌面）**整块不出现那两行**");
        check(env.contains("HplaySlots.pickSupported = true"), "安卓壳把槽填上了");
        check(env.contains("activityResultRegistry.register"), "安卓用 ActivityResultRegistry（不动 MainActivity 这一份他方脏文件）");
        check(env.contains("inSampleSize"), "本机相册解码也带降采样");
        String sp = bodyOf(env, "private fun savePicked(uri: Uri?, name: String): String? = runCatching {");
        check(sp.contains("openInputStream(uri) ?: return@runCatching null"),
            "先拿到输入流才截断目标文件（不能 open(t,'wb') 与读流同表达式）");

        // ---------- 10 禁词：只能对「本轮新增的那几段」判 ----------
        // （整份文件判不得有 java.awt 是**错的**：上游是桌面/安卓共用，桌面侧的 Desktop/Toolkit
        //  调用点本来就在那里，由 patches.py 换成安卓实现；build.py 的硬检查吃的是生成的安卓源码。）
        String co36Blk = slice(x99, "// ---------- 36co（麻豆网", "// ---------- ganxiaban");
        check(co36Blk.length() > 400, "36co 段落切到了（" + co36Blk.length() + " 字符）");
        String bs2 = slice(x99, "private val builtinSites: List<X99Site> = listOf(", "\n    )\n");
        String slots = slice(app, "object HplaySlots {", "\n}") + slice(app, "本机素材槽（第 43 轮", "清除本机素材");
        String envNew = bodyOf(env, "private fun registerSlots(activity: Activity) {")
            + sp + bodyOf(env, "private fun decodeLocal(path: String): ImageBitmap? = runCatching {");
        String cliNew = thb + ft + bodyOf(client, "private fun thumbAttempt(") ;
        String[] news = {co36Blk, bs2, rh, pt, slots, envNew, cliNew};
        int hit = 0;
        for (String s : news) {
            if (s.contains("java.awt") || s.contains("com.javrocket.playground")) {
                hit++;
                System.out.println("  新增段落里出现禁词：" + s.substring(0, Math.min(60, s.length())).replace("\n", " "));
            }
        }
        check(hit == 0, "本轮新增的六段代码里没有 java.awt / playground 残留（构建硬检查会拦的那个口径）");
        check(!thumb.contains("java.awt"), "HplayThumb.kt（安卓专属文件）整份都不许有桌面 API");
        check(client.contains("const val BASE = \"https://hanime1.me\""), "官方源常量仍在（本轮没碰它的地址）");

        System.out.println();
        System.out.println("R43Check  PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) {
            System.out.println("失败项：");
            for (String s : fails) {
                System.out.println("  - " + s);
            }
            System.exit(1);
        }
    }

    static int o2(String[] q) {
        return Integer.parseInt(q[2]);
    }

    /**
     * 从 start 标记之后截到 stop。
     *
     * 第 71 轮修（验收器自身的脆弱点）：上游源码在本机是 **CRLF** 行尾，
     * 而这里的 stop 参数一律写成 "\n" 结尾 —— 直接 indexOf 会**永远匹配不到**，
     * 表现为「抽到 0 项」这类假 FAIL（本轮 git 还原文件后暴露）。
     * 现在统一把两边的换行归一成 \n 再匹配，CRLF / LF 都能用。
     */
    static String slice(String src, String start, String stop) {
        String s = src.replace("\r\n", "\n");
        int i = s.indexOf(start);
        if (i < 0) {
            return "";
        }
        int j = s.indexOf(stop, i + start.length());
        return j < 0 ? "" : s.substring(i, j);
    }

    static Map<String, String> pairs(String blk) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"\\s*to\\s*\"([^\"]*)\"").matcher(blk);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    static int num(String blk, String rx) {
        Matcher m = Pattern.compile(rx).matcher(blk);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static String newest(String dir, String prefix, String suffix) throws IOException {
        String best = "";
        long bestT = -1;
        try (java.util.stream.Stream<Path> s = Files.list(Paths.get(dir))) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.startsWith(prefix) && n.endsWith(suffix) && p.toFile().lastModified() > bestT) {
                    bestT = p.toFile().lastModified();
                    best = n;
                }
            }
        }
        return best;
    }
}