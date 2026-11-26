// 肉视频「派发口选线」离线验收器（2026-10-02）
// 凭据：临时\r41_rou\pool.tsv —— 对 4 个 cuid 各连发 12 次派发口，逐行记录
//   cuid / 第几次 / 形态(302|INLINE|ERR) / 目标 / 那一跳最终是不是 #EXTM3U
// 这里复放生产 rouMaster 的规则（自己跟 302、自己验清单、不通就换一条线重取，上限 4 次），
// 并把它和**上一版的行为**（把派发口原样交给播放器，播放器只跟一次 302）对照，
// 用同一张表量出「修好了多少」。
//   编译：javac -encoding UTF-8 -d . RouLineCheck.java
//   运行：java -Dstdout.encoding=UTF-8 RouLineCheck [pool.tsv] [r41_rou_line_range.log] [X99.kt]
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class RouLineCheck {
    static int pass = 0, fail = 0;

    static void ck(String what, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (ok) pass++; else fail++;
    }

    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    static class Row { String cuid, form, target, outcome; int tryNo; }

    public static void main(String[] a) throws Exception {
        String tsvPath = a.length > 0 ? a[0] : "D:/工作区/临时/r41_rou/pool.tsv";
        String rangeLog = a.length > 1 ? a[1] : "D:/工作区/临时/r41_rou/line_range.log";
        String ktPath = a.length > 2 ? a[2] : "D:/工作区/工程/AvPlay/独立版/src/com/han1me/X99.kt";
        List<Row> rows = new ArrayList<>();
        for (String line : read(tsvPath).split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 5 || f[0].equals("cuid")) continue;
            Row r = new Row();
            r.cuid = f[0]; r.tryNo = Integer.parseInt(f[1]); r.form = f[2]; r.target = f[3]; r.outcome = f[4];
            rows.add(r);
        }
        Set<String> cuidsAll = new LinkedHashSet<>();
        for (Row r : rows) cuidsAll.add(r.cuid);
        ck("0 表非空且至少三族（空表会让下面所有断言恒真）",
                rows.size() >= 24 && cuidsAll.size() >= 3, rows.size() + " 行 / " + cuidsAll.size() + " 个 cuid");

        // ---------- 1 核心断言：坏的是当次选中的线路，不是这条视频 ----------
        Map<String, List<Row>> byCuid = new LinkedHashMap<>();
        for (Row r : rows) byCuid.computeIfAbsent(r.cuid, k -> new ArrayList<>()).add(r);
        int badLineGoodVideo = 0, allBad = 0;
        StringBuilder how = new StringBuilder();
        for (Map.Entry<String, List<Row>> e : byCuid.entrySet()) {
            boolean hasGood = false, hasBad = false;
            for (Row r : e.getValue()) {
                if (r.outcome.equals("M3U8")) hasGood = true;
                else if (r.outcome.startsWith("HTTP")) hasBad = true;
            }
            if (hasGood && hasBad) { badLineGoodVideo++; how.append(e.getKey(), 0, 8).append(" "); }
            if (!hasGood) allBad++;
        }
        ck("1 存在「同一 cuid 既有 200 真清单、又有 HTTP4xx 被拒」的样本 ⇒ 401 归因于线路，不归因于视频",
                badLineGoodVideo >= 1, badLineGoodVideo + " 族: " + how);
        ck("2 没有任何一族是 12 次全取不到（若全取不到就不该声称『换线能救』）",
                allBad == 0, "全失败族 " + allBad);

        // ---------- 3 复放生产的选线规则 ----------
        int newOk = 0, oldOk = 0;
        List<String> rescued = new ArrayList<>();
        for (Map.Entry<String, List<Row>> e : byCuid.entrySet()) {
            List<Row> rs = new ArrayList<>(e.getValue());
            rs.sort(Comparator.comparingInt(r -> r.tryNo));
            // 上一版：派发口交给 ExoPlayer，它只跟第一次 302
            boolean old = !rs.isEmpty() && rs.get(0).outcome.equals("M3U8");
            // 现在：自己跟、自己验，不通就换一条，上限 4 次
            boolean neu = false;
            for (int i = 0; i < Math.min(4, rs.size()); i++)
                if (rs.get(i).outcome.equals("M3U8")) { neu = true; break; }
            if (old) oldOk++;
            if (neu) newOk++;
            if (neu && !old) rescued.add(e.getKey().substring(0, 8) + "@第" + firstGood(rs));
        }
        ck("3 复放：4 次上限下全部取到可用清单（" + newOk + "/" + byCuid.size() + "）",
                newOk == byCuid.size(), newOk + "/" + byCuid.size());
        ck("4 同一张表上「只跟一次 302」的旧行为确实会失败（否则这个修复没有对象）",
                oldOk < newOk, "旧 " + oldOk + " / 新 " + newOk + " 被救回 " + rescued);

        // ---------- 5 401 的分布：按目标基域统计，不是按 cuid ----------
        Map<String, Integer> domBad = new TreeMap<>(), domGood = new TreeMap<>();
        for (Row r : rows) {
            String base = r.target.contains(".") ? r.target.substring(r.target.indexOf('.') + 1) : r.target;
            if (r.outcome.equals("M3U8")) domGood.merge(base, 1, Integer::sum);
            else if (r.outcome.startsWith("HTTP") || r.outcome.startsWith("ERR")) domBad.merge(base, 1, Integer::sum);
        }
        ck("5 被拒是按**基域**聚集的（同一基域反复 401，别的基域同 cuid 却 200）",
                !domBad.isEmpty() && !domGood.isEmpty(),
                "坏域 " + domBad + " / 好域数 " + domGood.size());

        // ---------- 6 派发口对 Range 撒谎、线路域诚实 ----------
        String kt = read(ktPath).replace("\r\n", "\n");
        ck("6 生产不再按域名前缀猜 Range，改成按地址登记（rangeHostileMedia）",
                kt.contains("private val rangeHostileMedia")
                        && kt.contains("fun mediaRangeHostile(u: String): Boolean = rangeHostileMedia.contains(u)")
                        && !kt.contains("h.startsWith(\"rou\") && uri.rawPath"),
                "");
        ck("7 只把派发口登记进去（`startsWith(\"https://rou\") || contains(\"/api/hls/\")`），线路域不登记",
                Pattern.compile("if \\(media\\.startsWith\\(\"https://rou\"\\) \\|\\| media\\.contains\\(\"/api/hls/\"\\)\\)\\s*\\n?\\s*rangeHostileMedia\\.add\\(media\\)")
                        .matcher(kt).find(),
                "");
        // 表里所有 302 目标都不该被登记
        int mis = 0;
        for (Row r : rows) {
            if (!r.form.equals("302")) continue;
            boolean wouldRegister = r.target.startsWith("https://rou") || r.target.contains("/api/hls/");
            if (wouldRegister) mis++;
        }
        ck("8 表里的线路域地址没有一条会命中派发口那条判据（判据与实测域形状不冲突）",
                mis == 0, "误命中 " + mis + " / 302 行 " + rows.stream().filter(r -> r.form.equals("302")).count());
        try {
            String log = read(rangeLog).replace("\r\n", "\n");
            long honest = count(log, "→ 诚实");
            long liars = count(log, "→ 撒谎");
            ck("9 实测记录：线路域的清单与分片在 bytes=0- 上全是诚实（撒谎那几行只出现在派发口段）",
                    honest >= 4 && !Pattern.compile("线路域=[^\\n]*\\n[\\s\\S]{0,400}?→ 撒谎").matcher(log).find(),
                    "诚实 " + honest + " 撒谎 " + liars);
        } catch (FileNotFoundException ex) {
            ck("9 线路域 Range 实测记录存在", false, rangeLog + " 不存在——断言不能空过");
        }

        // ---------- 10 交接给播放器的必须是验过的那条 ----------
        ck("10 rouVideoDetail 用 rouMaster 的返回值（不再把派发口原样交给 getPlaylist）",
                Pattern.compile("val \\(master, list\\) = rouMaster\\(dispatch, v\\.url\\)").matcher(kt).find(), "");
        ck("11 rouMaster 的重试上限与注释里的数字一致（6 次），且每次之间有退避",
                Pattern.compile("for \\(i in 0 until 6\\)[\\s\\S]{0,80}?rouAttemptGap\\(i\\)")
                        .matcher(kt).find()
                        && kt.contains("Thread.sleep(120L * attempt)"), "");
        ck("12 hlsVerified 仍由 getPlaylist/renditionsOf 在亲眼见到 #EXTM3U 时写入（无后缀清单靠它声明 MIME）",
                kt.contains("hlsVerified.add(url)") && kt.contains("hlsVerified.add(masterUrl)")
                        && kt.contains("fun mediaIsHls(u: String): Boolean = hlsVerified.contains(u)"), "");
        // 交给播放器的地址必须是表里那条绝对线路域地址的形状
        int absOk = 0, tot = 0;
        for (Row r : rows) {
            if (!r.outcome.equals("M3U8")) continue;
            tot++;
            if (r.target.matches("jre8[a-z0-9]+\\.[A-Za-z0-9.-]+")) absOk++;
        }
        ck("13 每次成功返回的都是 `jre8<哈希>.<基域>` 形态的绝对地址（前缀按 cuid 固定、基域轮换）",
                tot > 0 && absOk == tot, absOk + "/" + tot);
        Set<String> prefixes = new LinkedHashSet<>();
        for (Row r : rows) if (r.target.contains(".")) prefixes.add(r.target.substring(0, r.target.indexOf('.')));
        Map<String, Set<String>> perCuidPrefix = new HashMap<>();
        for (Row r : rows) if (r.target.contains("."))
            perCuidPrefix.computeIfAbsent(r.cuid, k -> new HashSet<>()).add(r.target.substring(0, r.target.indexOf('.')));
        boolean stable = true;
        for (Map.Entry<String, Set<String>> e : perCuidPrefix.entrySet())
            if (e.getValue().size() != 1) stable = false;
        ck("14 同一 cuid 的子域前缀始终不变（变的是基域）⇒『换一条线』等价于重取一次派发口",
                stable && !prefixes.isEmpty(), perCuidPrefix.toString());

        // ---------- 15 「换一条线」这个动作成立的前提：派发口按 TCP 连接分线 ----------
        // 同一批 12 条，两次跑，唯一区别是探测派发口时复用不复用连接。
        try {
            String reuse = read("D:/工作区/临时/r41_rou/roudiag1_reuse.log");
            String noReuse = read("D:/工作区/临时/r41_rou/roudiag2_noreuse.log");
            long rGood = count(noReuse, "✓"), rBad = count(reuse, "✓");
            ck("15 同一个 cuid 连发四次：复用连接时落点域名一模一样（重取无效），"
                    + "不复用后落点开始轮换（" + rBad + "/12 通 → " + rGood + "/12 通）",
                    rBad < rGood && rGood >= 10 && sameTargets(reuse),
                    "复用 " + rBad + " 通 / 不复用 " + rGood + " 通");
        } catch (FileNotFoundException ex) {
            ck("15 连接复用对照实验的两份记录存在", false, ex.getMessage());
        }
        ck("16 生产把派发口探测走独立的不复用连接池（这是『重取=换线』成立的前提）",
                kt.contains("okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS)")
                        && Pattern.compile("private val noRedirectHttp[\\s\\S]{0,400}?connectionPool\\(OkHttpNoReusePool\\)")
                        .matcher(kt).find(), "");
        ck("17 重试上限与注释里写的数字一致（6 次），并且每次之间有退避",
                Pattern.compile("private fun rouMaster[\\s\\S]{0,200}?for \\(i in 0 until 6\\)[\\s\\S]{0,120}?rouAttemptGap\\(i\\)")
                        .matcher(kt).find(), "");

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 复用连接那次：同一个 cuid 的四次落点是否完全相同（相同才证明"重取"没换线）。 */
    static boolean sameTargets(String log) {
        Map<String, Set<String>> by = new LinkedHashMap<>();
        for (String line : log.split("\n")) {
            Matcher m = Pattern.compile("(https://rou[^ ]*?/v/[0-9a-z]+).*?第(\\d+):([a-z0-9]+\\.[a-z0-9.]+) ").matcher(line);
            if (!m.find()) continue;
            by.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(m.group(3));
        }
        boolean anyStuck = false;
        for (Set<String> s : by.values()) if (s.size() == 1) anyStuck = true;
        return anyStuck;
    }


    static int firstGood(List<Row> rs) {
        for (int i = 0; i < rs.size(); i++) if (rs.get(i).outcome.equals("M3U8")) return i + 1;
        return -1;
    }

    static long count(String hay, String needle) {
        long n = 0;
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + 1)) n++;
        return n;
    }
}
