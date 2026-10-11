package com.tof.manycourse.data

/**
 * **周次串 → 「第几周上」**（全项目唯一实现）。
 *
 * ## 为什么要有它
 *
 * 教务系统给每条课的周次是一串自由文本（`4-19周` / `3-5,8-20周` / `16,7,8,…`）。
 * 在这之前它**只被展示、不参与渲染** —— 于是"只在第 3-5 周上的军训"会被画满整个学期。
 *
 * ## 为什么从 `GdutScheduleParser` 搬到这里
 *
 * 这套解析（`1-4` / `1~4` / `1至4` 都认、乱序去重、越界丢弃）本来是写在
 * **广工那个学校专属文件**里的，但三所学校的周次串格式是同一回事 ——
 * 它在这之前只是碰巧只被广工用到而已。搬出来之后 `data/` 这一层不再依赖 `gr_api/schools/`。
 *
 * ## 一条重要的取值口径：**解析不出就别过滤**
 *
 * [appliesTo] 返回的是 `Boolean?` 而不是 `Boolean`：
 *
 * | 情况 | 返回 | 调用方该怎么做 |
 * |---|---|---|
 * | 周次串覆盖第 n 周 | `true` | 显示 |
 * | 周次串不覆盖第 n 周 | `false` | 不显示 |
 * | 空串 / 全是认不出的字符 / 单双周标记把范围筛空了 | **`null`** | **不过滤**（按老样子显示） |
 *
 * `null` 那一档是刻意的：周次串认不出来时，"照旧显示"顶多是多列一门课；
 * 而**猜着过滤**会让一门真会上的课从课表上消失 —— 学生不会知道少了什么，
 * 只会白等或错过，比多显示一门糟糕得多（与"读不到教学周就不猜开学日期"同一条规矩）。
 */
object WeekRanges {

    /**
     * 一条课最多认到第几周。
     *
     * 上限不是"学期最长多少周"，而是一道**防脏数据**的闸：服务端哪天把学号、
     * 日期（`20260914`）塞进周次字段，也不至于把周次集合撑成上万个元素。
     */
    const val MAX_WEEKS = 30

    /** 周次区间的写法：`1-4` / `1~4` / `1～4` / `1至4`（有的系统用中文"至"）*/
    private val WEEK_RANGE = Regex("""(\d{1,2})\s*[-~～至]\s*(\d{1,2})""")

    /**
     * 单/双周标记。
     *
     * ⚠️ **实测数据里还没有出现过**：三所学校的教务系统给的都是**显式周次**
     * （`4-19周`、`3-5,8-20周`、`16,7,8,…`），没有"单周/双周"这种标记。
     * 所以这里是**防御性**支持的 —— 认得出就按它筛，认不出走 `null` 那一档（不过滤）。
     * 哪天真遇到一所学校用这套写法，再补样本与单测。
     */
    private val ODD_MARKERS = listOf("（单）", "(单)", "单周")
    private val EVEN_MARKERS = listOf("（双）", "(双)", "双周")

    /**
     * 从周次串里解析出全部周次（升序去重）。
     *
     * **两种写法都认**：逗号分隔的单周（广工实测就是这种：`"16,7,8,…"`）
     * 和区间（`"4-19周"`）。只认 `1..`[MAX_WEEKS] 的数字。
     *
     * 解析不出任何周次时返回**空集**（而不是抛异常）——
     * 调用方要区分"空集"与"说不清"就用 [appliesTo]。
     */
    fun parse(raw: String): java.util.SortedSet<Int> {
        val weeks = java.util.TreeSet<Int>()
        var rest = raw
        WEEK_RANGE.findAll(raw).forEach { match ->
            val from = match.groupValues[1].toIntOrNull() ?: return@forEach
            val to = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (from in 1..MAX_WEEKS && to in from..MAX_WEEKS) (from..to).forEach { weeks += it }
            rest = rest.replace(match.value, " ")
        }
        Regex("""\d+""").findAll(rest).forEach { match ->
            match.value.toIntOrNull()?.takeIf { it in 1..MAX_WEEKS }?.let { weeks += it }
        }
        return weeks
    }

    /**
     * 周次串归一化：`"16,7,8,9,10,11,17,12,13,14,15"` → `"7-17周"`。
     *
     * 教务系统给的周次是**乱序**的（实测的 `zcs` 完全没排序），直接展示就是
     * `16,7,8,9,10,11,17,…` 这种谁也读不懂的东西；而归一化之后既能展示，
     * 也能一眼看出"这门课只上前 8 周"。
     *
     * 规则：解析出全部周次 → 去重升序 → 连续的数字压成 `a-b` → 单周保持 `a` → 末尾补 `周`。
     * 空串 / 解析不出数字时**原样返回**（宁可难看也别把服务端的话弄丢）。
     */
    fun normalize(raw: String): String {
        val weeks = parse(raw)
        if (weeks.isEmpty()) return raw.trim()

        val parts = mutableListOf<String>()
        var rangeStart = -1
        var previous = -1
        weeks.forEach { week ->
            if (rangeStart < 0) {
                rangeStart = week
                previous = week
                return@forEach
            }
            if (week == previous + 1) {
                previous = week
                return@forEach
            }
            parts += if (rangeStart == previous) "$rangeStart" else "$rangeStart-$previous"
            rangeStart = week
            previous = week
        }
        parts += if (rangeStart == previous) "$rangeStart" else "$rangeStart-$previous"
        return parts.joinToString(",") + "周"
    }

    /** 一批周次串里的**最大周次**（用来定教学周总数：`"16,7,17"` → 17）*/
    fun maxWeekOf(rawWeeks: List<String>): Int =
        rawWeeks.flatMap { parse(it) }.maxOrNull() ?: 0

    /**
     * 周次串能确定的那几周。
     *
     * @return `null` = **说不清** —— 空串 / 认不出任何周次 / 单双标记把范围整个筛空了。
     *   调用方一律按"不过滤、照旧显示"处理（见类注释里那张表）。
     */
    fun weeksOrNull(raw: String): Set<Int>? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val base = parse(text)
        if (base.isEmpty()) return null

        val parity = parityOf(text) ?: return base
        val filtered = base.filter { it % 2 == parity }.toSet()
        // 单双标记把范围整个筛空了（如「3周(双)」）→ 自相矛盾的数据，
        // 按"说不清"处理，而不是把这门课整个藏掉
        return filtered.ifEmpty { null }
    }

    /**
     * 周次串 [raw] 覆盖不覆盖第 [week] 教学周。
     *
     * @return `null` = **说不清，调用方不要过滤**（空串 / 认不出 / 单双标记把范围筛空了）
     */
    fun appliesTo(raw: String, week: Int): Boolean? {
        if (week !in 1..MAX_WEEKS) return null
        return weeksOrNull(raw)?.contains(week)
    }

    /** 串里有没有单/双周标记；`1` = 单周，`0` = 双周，`null` = 没提 */
    private fun parityOf(text: String): Int? = when {
        ODD_MARKERS.any { text.contains(it) } -> 1
        EVEN_MARKERS.any { text.contains(it) } -> 0
        else -> null
    }
}

/**
 * 这门课在**第 [week] 教学周**上不上。
 *
 * @param week `null` = 不知道这一天是第几教学周（没有教学周数据）= **不过滤**
 *
 * 两种"不知道"都返回 `true`（照旧显示）：
 *  - 不知道第几周（[week] 为 null）；
 *  - 这门课的周次串认不出来（见 [WeekRanges.appliesTo]）。
 *
 * 手动添加的课没有周次串（[weeks] 为空）→ 任何一周都上，返回 `true`。
 */
fun Course.appliesInWeek(week: Int?): Boolean {
    if (week == null) return true
    return WeekRanges.appliesTo(weeks, week) ?: true
}
