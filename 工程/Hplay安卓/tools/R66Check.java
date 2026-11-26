import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 第 66 轮离线验收器：**下载页翻页**（用户实机指出）。
 *
 * 用户原话：「视频数还是 12，只有显示改成 18 了，也没有下一页按钮，
 *           构建一个翻页按钮固定下载页，可翻页查看其余视频」。
 *
 * 根因（代码实证）：**双层分页**。
 *   · byGroup 用的是 items —— 而 items 是**外层已切过的当前页**（<= PAGE_SIZE=12）；
 *   · 组内又按 PAGE_SIZE 切一次；
 *   · 外层那套 page/pageCount **只写在页签文案里，页脚从未渲染**。
 *   => 18 条永远只看到 12 条，且没有翻页入口。
 *
 * 修法：**单一层级分页**（对 filtered 整体分页）+ 底部**固定翻页栏**。
 */
public class R66Check {
    static final String HPLAY = "D:/工作区/工程/Hplay安卓/";
    static final String DUI = HPLAY + "src/com/han1me/download/HplayDownloadsUi.kt";

    static int pass = 0, fail = 0;
    static final List<String> fails = new ArrayList<>();
    static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }
    static void ok() { pass++; }
    static void no(String w) { fail++; fails.add(w); System.out.println("FAIL  " + w); }
    static void check(boolean c, String w) { if (c) ok(); else no(w); }
    static int n(String h, String needle) {
        int c = 0, i = 0;
        while ((i = h.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    public static void main(String[] a) throws Exception {
        String ui = read(DUI);

        System.out.println("== A) 单一层级分页（修掉双层分页） ==");
        check(n(ui, "val items = remember(filtered, page)") == 1, "A1 对 filtered 整体分页");
        check(n(ui, "val byGroup = remember(items)") == 1, "A2 分组基于**当前页**（不再二次切片）");
        check(n(ui, "gShown = gItems") == 1, "A3 组内不再单独切片（第 65 轮起取消组内分页）");
        check(n(ui, "gPageCount") == 0, "A4 反断言：旧的组内分页变量已移除");

        System.out.println("== B) 底部**固定**翻页栏 ==");
        check(n(ui, "if (tab == 1 && pageCount > 1)") == 1, "B1 多页时才显示翻页控件（第 81 轮：一处条件）");
        check(n(ui, "PageStepper") >= 1, "B2 翻页控件已移到页签行（第 81 轮，用户要求）");
        check(n(ui, "上一页") >= 1 && n(ui, "下一页") >= 1, "B3 上一页 / 下一页都在");
        check(n(ui, "if (page > 0) page--") == 1, "B4 上一页有边界保护");
        check(n(ui, "if (page < pageCount - 1) page++") == 1, "B5 下一页有边界保护");

        System.out.println("== C) 位置：翻页栏必须抬到底栏（74dp）之上 ==");
        check(n(ui, ".padding(bottom = 74.dp)") >= 1, "C1 翻页栏抬高 74dp（底栏高度）");
        check(n(ui, "padding(bottom = 74.dp)") >= 1, "C2 内容底部留白 = 底栏 74dp（第 81 轮：翻页栏已移出，不再额外 52dp）");
        int iBar = ui.indexOf("BottomCenter");
        int iPad = ui.indexOf(".padding(bottom = 74.dp)", iBar);
        int iBg = ui.indexOf(".background(CardColor)", iBar);
        check(iPad > iBar && (iBg < 0 || iPad < iBg), "C3 padding 排在背景之前（先让位再画底色）");

        System.out.println("== D) 页签显示真实总数 ==");
        check(n(ui, "已下载 ${filtered.size}") == 1, "D1 页签用 filtered.size（真实总数）");
        check(n(ui, "已下载 ${items.size}") == 0, "D2 反断言：旧的分页切片数已移除");

        System.out.println("========================================");
        System.out.println("R66Check  PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) { for (String f : fails) System.out.println("  - " + f); System.exit(1); }
    }
}