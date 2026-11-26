package com.han1me.checkin

import com.han1me.HplayEnv
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * **冲了吗（打卡）** —— 照 Han1meViewer 的 DailyCheckIn 复刻（第 77 轮全面升级）。
 *
 * 参考源码：ui/screen/home/dailycheckin 目录、logic/entity/CheckInRecordEntity.kt、
 * logic/dao/CheckInRecordDao.kt、ui/viewmodel/CheckInCalendarViewModel.kt。
 *
 * ## 与参考实现的数据结构对应
 * 原版 `CheckInRecordEntity(id, date, time, type, sideDishes, feeling)`，一张表、**一天可多条**；
 * 这里逐字段对齐（见 [Record]）。
 *  · date = yyyy-MM-dd（**任意日期**，支持回溯补卡）
 *  · time = HH:mm
 *  · type = 自慰/梦遗/做爱/口交/其它（[Kind]）
 *  · sideDishes = 配菜（看的片子），格式 `标题<US>videoCode`，多条用逗号分隔（与参考实现同一约定）
 *  · feeling = 感想（参考实现的 feeling 字段）
 *
 * ## 为什么用文件而不是 Room
 * 原版用 Room；本应用没有 Room，也不宜为此引依赖（会动构建配置）。
 * 用 hplay_checkin.tsv 持久化，**字段与语义完全对齐**。
 * 转义约定：制表符/换行/逗号在写入时转义，读出时还原（避免分隔符冲突）。
 */
object HplayCheckIn {

    /** 打卡类型（label 与参考实现 CheckInType.storeName 一致）。 */
    enum class Kind(val label: String) {
        MASTURBATION("自慰"),
        WET_DREAM("梦遗"),
        SEX("做爱"),
        ORAL("口交"),
        OTHER("其它");

        companion object {
            fun from(name: String): Kind = entries.firstOrNull { it.label == name } ?: MASTURBATION
        }
    }

    /** 配菜条目（看的片子）。title 用于显示，code 用于去重/统计「同一视频用几次」。 */
    data class Dish(val title: String, val code: String) {
        /** 参考实现的序列化形式：`标题<US>code`。 */
        fun encode(): String = title + SEP + code

        companion object {
            /** 参考实现用的字段分隔符（U+001E，避免与标题里的逗号冲突）。 */
            const val SEP = "\u001E"

            fun decode(s: String): Dish? {
                if (s.isBlank()) return null
                if (s.contains(SEP)) return Dish(s.substringBefore(SEP), s.substringAfter(SEP))
                // 兼容旧数据：`标题|code` 或纯标题
                if (s.contains('|')) return Dish(s.substringBefore('|'), s.substringAfter('|'))
                return Dish(s, "")
            }
        }
    }

    /** 一条打卡记录（对应参考实现 CheckInRecordEntity）。 */
    data class Record(
        val date: String,
        val time: String,
        val kind: Kind,
        /** 配菜（可为空列表）。 */
        val dishes: List<Dish>,
        /** 感想。 */
        val feeling: String,
    )

    private val file: File by lazy { File(HplayEnv.dataDir().apply { mkdirs() }, "hplay_checkin.tsv") }
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    /** 每天最多记多少条（照参考实现 TodayCheckInCard 的 maxCount=20）。 */
    const val MAX_PER_DAY = 20

    @Volatile private var cache: List<Record>? = null

    // ---------------- 读写 ----------------

    /** 全部记录（按日期、时间倒序）。 */
    fun all(): List<Record> {
        cache?.let { return it }
        val out = ArrayList<Record>()
        runCatching {
            if (file.isFile) {
                file.readLines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val f = line.split('\t')
                    if (f.size < 3 || f[0].length != 10) return@forEach
                    val dishes = f.getOrElse(3) { "" }
                        .split(',')
                        .mapNotNull { d -> Dish.decode(unesc(d)) }
                        .filter { it.title.isNotBlank() || it.code.isNotBlank() }
                    out.add(
                        Record(
                            date = f[0],
                            time = f[1],
                            kind = Kind.from(f[2]),
                            dishes = dishes,
                            feeling = unesc(f.getOrElse(4) { "" }),
                        ),
                    )
                }
            }
        }
        val sorted = sortDesc(out)
        cache = sorted
        return sorted
    }

    private fun sortDesc(list: List<Record>) =
        list.sortedWith(compareByDescending<Record> { it.date }.thenByDescending { it.time })

    private fun write(list: List<Record>): Boolean {
        cache = null
        val ok = runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                sortDesc(list).joinToString("\n") { r ->
                    listOf(
                        r.date, r.time, r.kind.label,
                        r.dishes.joinToString(",") { esc(it.encode()) },
                        esc(r.feeling),
                    ).joinToString("\t")
                } + if (list.isEmpty()) "" else "\n",
            )
            true
        }.getOrDefault(false)
        cache = null
        return ok
    }

    // 制表符/换行在字段内转义（逗号已在 Dish 层用 U+001E 规避）
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")

    // ---------------- 增删 ----------------

    /**
     * 记一条打卡（**可指定任意日期**，用于回溯补卡；同一天可多条）。
     *
     * @return true = 已写入
     */
    @Synchronized
    fun add(
        date: String,
        time: String,
        kind: Kind,
        dishes: List<Dish> = emptyList(),
        feeling: String = "",
    ): Boolean {
        val cur = all()
        if (cur.count { it.date == date } >= MAX_PER_DAY) return false
        return write(cur + Record(date, time, kind, dishes, feeling))
    }

    /** 今天打卡（默认类型自慰）。 */
    fun addToday(kind: Kind = Kind.MASTURBATION, dishes: List<Dish> = emptyList(), feeling: String = ""): Boolean =
        add(today(), nowTime(), kind, dishes, feeling)

    /** 删某一天的**最后一条**记录（长按日期清除）。 */
    @Synchronized
    fun removeLastOn(date: String): Boolean {
        val cur = all()
        val hit = cur.filter { it.date == date }.maxByOrNull { it.time } ?: return false
        return write(cur - hit)
    }

    /** 删某一天全部记录。 */
    @Synchronized
    fun removeAllOn(date: String): Boolean {
        val cur = all()
        val next = cur.filterNot { it.date == date }
        if (next.size == cur.size) return false
        return write(next)
    }

    /** 删一条精确记录。 */
    @Synchronized
    fun remove(date: String, time: String, kind: Kind): Boolean {
        val cur = all()
        val idx = cur.indexOfFirst { it.date == date && it.time == time && it.kind == kind }
        if (idx < 0) return false
        return write(cur.toMutableList().also { it.removeAt(idx) })
    }

    // ---------------- 查询（对齐参考实现的 DAO） ----------------

    /** 某天的记录（对齐 getRecordsByDate）。 */
    fun on(date: String): List<Record> = all().filter { it.date == date }.sortedBy { it.time }

    /** 某天条数（对齐 getCountByDate）。 */
    fun countOn(date: String): Int = all().count { it.date == date }

    fun checkedInToday(): Boolean = countOn(today()) > 0

    /** 某月的「日期 → 次数」（对齐 getMonthlyCheckedDates + 计数）。 */
    fun monthCounts(yearMonth: String): Map<String, Int> =
        all().filter { it.date.startsWith(yearMonth) }.groupingBy { it.date }.eachCount()

    /** 某年全部记录。 */
    fun yearRecords(year: String): List<Record> = all().filter { it.date.startsWith(year) }

    /** 日期区间记录（对齐 getRecordsBetween）。 */
    fun between(start: String, end: String): List<Record> =
        all().filter { it.date >= start && it.date <= end }.sortedWith(compareBy({ it.date }, { it.time }))

    /** 最近的配菜标题（去重，供「自定义配菜」自动补全）。 */
    fun recentDishTitles(limit: Int = 20): List<String> = all()
        .flatMap { it.dishes }.map { it.title }.filter { it.isNotBlank() }.distinct().take(limit)

    // ---------------- 统计（照参考实现 computeStats 的口径） ----------------

    /**
     * 一段记录的统计（对齐参考实现 MonthlyStats）。
     *
     * 口径逐项照抄：总次数 / 打卡天数 / 最长连续 / 类型计数 / 配菜种类 /
     * 最爱配菜及次数 / 时段分布（早 5-10、晚 22-2、午 12-16）/ 感想字数 /
     * 单日最多类型数。
     */
    data class Stats(
        val totalCount: Int = 0,
        val daysChecked: Int = 0,
        val bestStreak: Int = 0,
        /** 类型 → 次数 */
        val typeCounts: Map<Kind, Int> = emptyMap(),
        /** 配菜种类数 */
        val uniqueDishes: Int = 0,
        /** 最爱配菜标题 */
        val topDish: String = "",
        val topDishCount: Int = 0,
        /** 最爱配菜的 videoCode（可跳到该片） */
        val topDishCode: String = "",
        val morningCount: Int = 0,
        val nightCount: Int = 0,
        val afternoonCount: Int = 0,
        val totalFeelingChars: Int = 0,
        val maxDailyTypes: Int = 0,
    ) {
        /** 主导时段（照参考实现：只有严格大于才算）。 */
        val dominantPeriod: String get() = when {
            nightCount > morningCount && nightCount > afternoonCount -> "22~02"
            morningCount > nightCount && morningCount > afternoonCount -> "05~10"
            afternoonCount > morningCount && afternoonCount > nightCount -> "12~16"
            else -> ""
        }

        val topType: Kind? get() = typeCounts.maxByOrNull { it.value }?.key
    }

    fun statsOf(records: List<Record>): Stats {
        if (records.isEmpty()) return Stats()
        val typeCounts = records.groupingBy { it.kind }.eachCount()
        val dishItems = records.flatMap { it.dishes }
        val dishTitles = dishItems.map { it.title }.filter { it.isNotBlank() }
        val topEntry = dishTitles.groupingBy { it }.eachCount().maxByOrNull { it.value }
        val topDish = topEntry?.key.orEmpty()
        val topCode = if (topDish.isNotBlank()) {
            dishItems.firstOrNull { it.title == topDish }?.code.orEmpty()
        } else ""
        fun hourOf(t: String) = t.substringBefore(':').toIntOrNull() ?: 0
        val morning = records.count { hourOf(it.time) in 5..10 }
        val night = records.count { val h = hourOf(it.time); h in 22..23 || h in 0..2 }
        val afternoon = records.count { hourOf(it.time) in 12..16 }
        val maxDailyTypes = records.groupBy { it.date }.values.maxOfOrNull { g ->
            g.map { it.kind }.distinct().size
        } ?: 0
        return Stats(
            totalCount = records.size,
            daysChecked = records.map { it.date }.distinct().size,
            bestStreak = bestStreak(records.map { it.date }.distinct()),
            typeCounts = typeCounts,
            uniqueDishes = dishTitles.distinct().size,
            topDish = topDish,
            topDishCount = topEntry?.value ?: 0,
            topDishCode = topCode,
            morningCount = morning,
            nightCount = night,
            afternoonCount = afternoon,
            totalFeelingChars = records.sumOf { it.feeling.length },
            maxDailyTypes = maxDailyTypes,
        )
    }

    fun statsOfMonth(yearMonth: String): Stats = statsOf(all().filter { it.date.startsWith(yearMonth) })

    fun statsOfYear(year: String): Stats = statsOf(yearRecords(year))

    /**
     * 当前连续天数（照参考实现：从「今天（今天没打则昨天）」往前数）。
     */
    fun currentStreak(): Int {
        val set = all().map { it.date }.toHashSet()
        var cursor = if (set.contains(today())) today() else shiftDay(today(), -1)
        var n = 0
        while (set.contains(cursor)) { n++; cursor = shiftDay(cursor, -1) }
        return n
    }

    /** 最长连续（对齐参考实现 computeStats 里的 bestStreak）。 */
    fun bestStreak(dates: List<String>): Int {
        if (dates.isEmpty()) return 0
        val sorted = dates.distinct().sorted()
        var streak = 0
        var best = 0
        var prev: String? = null
        for (d in sorted) {
            streak = if (prev != null && shiftDay(prev, 1) == d) streak + 1 else 1
            if (streak > best) best = streak
            prev = d
        }
        return best
    }

    /**
     * 同一部片子被当作配菜用了几次（用户要求「记录同一个视频使用几次」）。
     * 按 videoCode 统计；没有 code 的按标题统计。
     */
    fun dishUsage(): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        all().forEach { r ->
            r.dishes.forEach { d ->
                val key = d.code.ifBlank { d.title }
                if (key.isNotBlank()) m[key] = (m[key] ?: 0) + 1
            }
        }
        return m
    }

    /** 某部片子作为配菜的总次数。 */
    fun dishCountOf(title: String, code: String): Int =
        all().sumOf { r -> r.dishes.count { it.title == title || (code.isNotBlank() && it.code == code) } }

    /**
     * **漏卡检测**（照参考实现：提醒补卡）。
     *
     * 返回「已过日期里、有相邻打卡却空着的那一天」，从近到远第一个；没有则 null。
     * 用户要求在日历上给「忘记打卡了吗？」的补卡提示。
     */
    fun firstMissingDay(limitDays: Int = 60): String? {
        val set = all().map { it.date }.toHashSet()
        val t = today()
        for (i in 1..limitDays) {
            val d = shiftDay(t, -i)
            if (set.contains(d)) continue
            // 前后至少有一天打过卡，才算「漏卡」；完全没开始用不算
            val prevHit = set.contains(shiftDay(d, -1))
            val nextHit = set.contains(shiftDay(d, 1))
            if (prevHit && nextHit) return d
        }
        return null
    }

    // ---------------- 日期工具 ----------------

    fun today(): String = dayFmt.format(Date())

    fun nowTime(): String = timeFmt.format(Date())

    /** yyyy-MM-dd 加减天数（跨月跨年正确）。 */
    fun shiftDay(d: String, delta: Int): String {
        val c = parseDay(d) ?: return d
        c.add(Calendar.DAY_OF_MONTH, delta)
        return dayFmt.format(c.time)
    }

    private fun parseDay(d: String): Calendar? = runCatching {
        val f = dayFmt.parse(d) ?: return null
        Calendar.getInstance().apply { time = f }
    }.getOrNull()

    /** 月份码 yyyy-MM。 */
    fun monthCodeOf(date: String): String = date.take(7)

    fun shiftMonth(monthCode: String, delta: Int): String {
        val y = monthCode.take(4).toIntOrNull() ?: return monthCode
        var m = (monthCode.drop(5).toIntOrNull() ?: return monthCode) + delta
        var yy = y
        while (m < 1) { m += 12; yy-- }
        while (m > 12) { m -= 12; yy++ }
        return String.format("%04d-%02d", yy, m)
    }

    fun currentMonthCode(): String = today().take(7)

    /** 该月天数。 */
    fun daysInMonth(monthCode: String): Int {
        val y = monthCode.take(4).toIntOrNull() ?: return 30
        val m = monthCode.drop(5).toIntOrNull() ?: return 30
        return runCatching {
            Calendar.getInstance().apply {
                clear()
                set(y, m - 1, 1)
            }.getActualMaximum(Calendar.DAY_OF_MONTH)
        }.getOrDefault(30)
    }

    /** 该月 1 号是星期几（1=周一 ... 7=周日，与 ISO 一致）。 */
    fun firstWeekdayOfMonth(monthCode: String): Int {
        val y = monthCode.take(4).toIntOrNull() ?: return 1
        val m = monthCode.drop(5).toIntOrNull() ?: return 1
        return runCatching {
            val c = Calendar.getInstance()
            c.clear()
            c.set(y, m - 1, 1)
            // Calendar: SUNDAY=1..SATURDAY=7 -> ISO: MONDAY=1..SUNDAY=7
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SUNDAY) 7 else dow - 1
        }.getOrDefault(1)
    }

    /** 日期 → 显示用（10月08日 星期四）。 */
    fun labelOf(date: String): String = runCatching {
        val c = parseDay(date) ?: return date
        SimpleDateFormat("MM月dd日 EEEE", Locale.CHINA).format(c.time)
    }.getOrDefault(date)

    /** 日期 → 2026年10月08日（记录弹窗标题用）。 */
    fun fullLabelOf(date: String): String = runCatching {
        val c = parseDay(date) ?: return date
        SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(c.time)
    }.getOrDefault(date)
}
