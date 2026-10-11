package com.tof.manycourse.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 一门**导入进来的课**（还没有 id）。
 *
 * 为什么不用 [Course]：[Course] 的 `id` 是本地自增的，导入时还没有它。
 * 用哨兵 id 冒充"还没入库"会让"这门课能不能删"变成靠约定判断 —— 与 `CourseDraft` 同一个理由。
 */
data class ImportedCourse(
    val name: String,
    val teacher: String,
    val room: String,
    val weekday: Int,
    val startPeriod: Int,
    val periodCount: Int,
    val weeks: String,
)

/**
 * 导入结果 + **如实列出没进来的行**。
 *
 * 坏行绝不能静默丢弃：用户导 40 门课、实际进来 37 门，界面只说"导入成功"，
 * 他会以为课表齐了，然后漏掉三节课。所以每一行为什么被跳过都要说清楚。
 */
data class ImportReport(
    val courses: List<ImportedCourse>,
    /** 每一句都是"第 N 行：原因" */
    val problems: List<String>,
) {
    val isUsable: Boolean get() = courses.isNotEmpty()
}

/**
 * **课表导入**：CSV（Excel 导出 / 手填模板）与 ICS（系统日历 / 别的课表 App 导出）。
 *
 * ## 为什么这两种格式都要
 *
 * 它们是"用户手上真实存在的东西"的两个极端：
 *  - **CSV**：Excel 里排好的课表、或者从别的课表软件导出的表格 —— 中国人最常见的手工载体；
 *  - **ICS**：系统日历 / Google 日历 / 别的课表 App 的导出格式 —— 换机迁移的正规通道。
 *
 * 两者都不需要联网，与项目"不做服务端"的口径一致。
 *
 * ## 共同的失败方式：行/事件被静默丢掉
 *
 * 所以两个解析器都返回 [ImportReport]，把跳过的每一行/每一个事件的原因写出来。
 */
object ScheduleImport {

    // ══════════════════ CSV ══════════════════

    /**
     * CSV 的列名（**表头驱动**，顺序随意；中英文都认；大小写与空格不敏感）。
     *
     * 必需：课程名 + 星期 + 起始节次。其余选填。
     *
     * ```
     * 课程名,教师,教室,星期,起始节次,节数,周次
     * 高等数学,王建国,教学楼 A-101,周一,1,2,1-16周
     * ```
     */
    private val HEADER_ALIASES: Map<String, Set<String>> = mapOf(
        "name" to setOf("课程名", "课程名称", "name", "course", "subject"),
        "teacher" to setOf("教师", "老师", "任课教师", "teacher"),
        "room" to setOf("教室", "地点", "上课地点", "room", "location"),
        "weekday" to setOf("星期", "周几", "weekday", "day"),
        "startPeriod" to setOf("起始节次", "开始节次", "节次", "startperiod", "start"),
        "periodCount" to setOf("节数", "连续节数", "periodcount", "periods", "length"),
        "weeks" to setOf("周次", "上课周次", "weeks", "week"),
    )

    /**
     * 解析 CSV。
     *
     * 按 **RFC 4180** 处理引号：Excel 导出的"高等数学,上册"会写成 `"高等数学,上册"`，
     * 里面的逗号**不是分隔符**；字段里的引号写成两个（`""`）。
     * 不处理引号的话，带逗号的课程名会被拆成两列，整行列数错位 ——
     * 表现为"导入进来一堆叫『高等数学』的空课"。
     */
    fun csv(text: String): ImportReport {
        val rows = parseCsvRows(text)
        if (rows.isEmpty()) return ImportReport(emptyList(), listOf("文件是空的"))

        val header = rows.first().map { normalizeHeader(it) }
        val index = HEADER_ALIASES.mapValues { (_, aliases) ->
            header.indexOfFirst { it in aliases }
        }

        val missing = listOf("name" to "课程名", "weekday" to "星期", "startPeriod" to "起始节次")
            .filter { (key, _) -> index[key] == -1 }
        if (missing.isNotEmpty()) {
            return ImportReport(
                emptyList(),
                listOf("表头缺少必需的列：${missing.joinToString("、") { it.second }}（当前表头：${rows.first().joinToString(",")}）"),
            )
        }

        val courses = mutableListOf<ImportedCourse>()
        val problems = mutableListOf<String>()

        rows.drop(1).forEachIndexed { offset, row ->
            val lineNo = offset + 2 // 表头是第 1 行
            if (row.all { it.isBlank() }) return@forEachIndexed

            val name = row.getOrElse(index["name"]!!) { "" }.trim()
            if (name.isEmpty()) {
                problems += "第 $lineNo 行：课程名为空"
                return@forEachIndexed
            }

            val weekday = parseWeekday(row.getOrElse(index["weekday"]!!) { "" })
            if (weekday == null) {
                problems += "第 $lineNo 行：星期「${row.getOrElse(index["weekday"]!!) { "" }}」认不出来（可用：周一…周日 / 星期一…星期日 / 1…7）"
                return@forEachIndexed
            }

            val startPeriod = row.getOrElse(index["startPeriod"]!!) { "" }.trim().toIntOrNull()
            if (startPeriod == null || startPeriod !in 1..CourseRepository.MAX_PERIOD) {
                problems += "第 $lineNo 行：起始节次「${row.getOrElse(index["startPeriod"]!!) { "" }}」不是 1…${CourseRepository.MAX_PERIOD} 的数字"
                return@forEachIndexed
            }

            val count = index["periodCount"]?.takeIf { it >= 0 }
                ?.let { row.getOrElse(it) { "" }.trim().toIntOrNull() }
                ?.takeIf { it >= 1 }
                ?: 2
            val safeCount = count.coerceAtMost(CourseRepository.MAX_PERIOD - startPeriod + 1)

            val rawWeeks = index["weeks"]?.takeIf { it >= 0 }?.let { row.getOrElse(it) { "" }.trim() }.orEmpty()

            courses += ImportedCourse(
                name = name,
                teacher = index["teacher"]?.takeIf { it >= 0 }?.let { row.getOrElse(it) { "" }.trim() }.orEmpty(),
                room = index["room"]?.takeIf { it >= 0 }?.let { row.getOrElse(it) { "" }.trim() }.orEmpty(),
                weekday = weekday,
                startPeriod = startPeriod,
                periodCount = safeCount,
                // 归一化：`7,8,3` → `3,7-8周`，与界面上看到的口径一致
                weeks = rawWeeks.takeIf { it.isNotBlank() }?.let { WeekRanges.normalize(it) }.orEmpty(),
            )
        }

        return ImportReport(courses, problems)
    }

    // ══════════════════ ICS ══════════════════

    private val ICS_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val ICS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * 解析 ICS 里的 VEVENT，并把"同一门课的多条日程"**合并回一条带周次的课**。
     *
     * 为什么不直接一条日程一门课：我们的 ICS 导出是**按周展开**的（见 [IcsExport]），
     * 一门上 16 周的课导出后有 16 条 VEVENT。不合并的话，导进来会变成 16 门"同名课"，
     * 课表上每格都重复堆着 —— 完全没法用。
     *
     * 合并键是 `(课程名, 教室, 星期, 起始节次, 节数)`；合并出来的周次由**日期反推**
     * （用教学周表把日期映射回第几周）。拿不到教学周时周次留空 = 每周都上，
     * 并在 problems 里说明这件事 —— 那确实是我们知道的极限。
     */
    fun ics(text: String, weeks: List<SchoolWeek>, timetable: Timetable = CourseRepository.timetable): ImportReport {
        val lines = unfold(text)
        val problems = mutableListOf<String>()

        data class Raw(
            val name: String,
            val teacher: String,
            val room: String,
            val start: LocalDateTime,
            val end: LocalDateTime,
        )

        val raws = mutableListOf<Raw>()
        var inside = false
        var summary: String? = null
        var location = ""
        var teacher = ""
        var dtStart: LocalDateTime? = null
        var dtEnd: LocalDateTime? = null
        var allDay = false
        var eventNo = 0

        lines.forEach { line ->
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                    inside = true
                    eventNo++
                    summary = null; location = ""; teacher = ""; dtStart = null; dtEnd = null; allDay = false
                }

                line.equals("END:VEVENT", ignoreCase = true) -> {
                    inside = false
                    val start = dtStart
                    when {
                        start != null -> {
                            val end = dtEnd ?: start.plusHours(1)
                            raws += Raw(summary.orEmpty().ifBlank { "未命名课程" }, teacher, location, start, end)
                        }
                        // 全天事件（`DTSTART;VALUE=DATE:20260921`）没有具体时刻，映射不到节次。
                        // 单独说一句，而不是和"坏数据"混成同一条提示
                        allDay -> problems += "第 $eventNo 个日程是全天事件（没有几点到几点），已跳过"

                        else -> problems += "第 $eventNo 个日程：没有可识别的 DTSTART，已跳过"
                    }
                }

                inside -> {
                    val (key, value) = splitProperty(line)
                    when (key) {
                        "SUMMARY" -> summary = unescapeText(value)
                        "LOCATION" -> location = unescapeText(value)
                        "DTSTART" -> {
                            dtStart = parseIcsDateTime(value)
                            if (dtStart == null && parseIcsDate(value) != null) allDay = true
                        }
                        "DTEND" -> dtEnd = parseIcsDateTime(value)
                        // 我们自己导出的扩展属性：ICS 标准里没有"教师"这一项
                        "X-MANYCOURSE-TEACHER" -> teacher = unescapeText(value)
                    }
                }
            }
        }

        if (raws.isEmpty()) {
            // 一个 VEVENT 都没有 = 多半是选错了文件；有 VEVENT 但全被跳过 = 文件没错，
            // 是里面的日程用不上 —— 那时**不能**再说"是不是选错文件了"，会把人带偏
            return ImportReport(
                emptyList(),
                if (eventNo == 0) problems + "没找到任何日程（是不是选错文件了？）" else problems,
            )
        }

        // ── 合并（键里带上教师：同一门课不同老师不该被并成一条）────────────────
        val merged = linkedMapOf<String, MutableList<Raw>>()
        raws.forEach { raw ->
            val key = listOf(
                raw.name,
                raw.teacher,
                raw.room,
                raw.start.dayOfWeek.value.toString(),
                raw.start.toLocalTime().toString(),
                raw.end.toLocalTime().toString(),
            ).joinToString("|")
            merged.getOrPut(key) { mutableListOf() } += raw
        }

        val courses = mutableListOf<ImportedCourse>()
        var sawUnknownTime = false
        var sawNoWeeks = false

        merged.values.forEach { group ->
            val first = group.first()
            val weekday = first.start.dayOfWeek.value
            val startTime = first.start.toLocalTime()

            val startPeriod = periodStartingAt(timetable, startTime)
            if (startPeriod == null) {
                sawUnknownTime = true
                return@forEach
            }
            val periodCount = periodsCovered(timetable, startPeriod, first.start, first.end)

            val weekIndexes = group.mapNotNull { raw -> weekIndexOf(weeks, raw.start.toLocalDate()) }.toSortedSet()
            if (weekIndexes.isEmpty()) sawNoWeeks = true

            courses += ImportedCourse(
                name = first.name,
                teacher = first.teacher,
                room = first.room,
                weekday = weekday,
                startPeriod = startPeriod,
                periodCount = periodCount,
                weeks = if (weekIndexes.isEmpty()) "" else WeekRanges.normalize(weekIndexes.joinToString(",")),
            )
        }

        if (sawUnknownTime) {
            val firstPeriodAt = timetable.startOfPeriod(1).ifBlank { "未知（这所学校还没收录作息）" }
            problems += "有日程的时刻对不上本机课表（当前学校第 1 节起于 $firstPeriodAt），这些已跳过"
        }
        if (sawNoWeeks) {
            problems += "有日程落不在已知的教学周里（可能不是本学期的课表），这些按「每周都上」导入"
        }

        return ImportReport(courses, problems)
    }

    // ══════════════════ 下面都是可单测的纯逻辑 ══════════════════

    /** 表头归一：去空格、去 BOM、转小写，这样"课程名 "和"课程名"能对上 */
    private fun normalizeHeader(value: String): String =
        value.removePrefix("\uFEFF").trim().lowercase().replace(" ", "")

    /** `周一` / `星期一` / `1` / `mon` → 1..7；认不出返回 null */
    fun parseWeekday(raw: String): Int? {
        val text = raw.trim().lowercase().replace(" ", "")
        if (text.isEmpty()) return null
        text.toIntOrNull()?.let { return it.takeIf { d -> d in 1..7 } }

        WeekdayLabels.forEachIndexed { index, label ->
            if (text == label || text == label.replace("周", "星期")) return index + 1
        }
        val english = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        english.forEachIndexed { index, name ->
            if (text.startsWith(name)) return index + 1
        }
        return null
    }

    /**
     * 极简 RFC 4180 分词：支持 `"` 包裹、字段内 `""` 表示一个引号、引号内的逗号与换行。
     */
    fun parseCsvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            rows += row
            row = mutableListOf()
        }

        while (i < text.length) {
            val ch = text[i]
            when {
                quoted -> when {
                    ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    ch == '"' -> quoted = false
                    else -> field.append(ch)
                }

                ch == '"' -> quoted = true
                ch == ',' -> endField()
                ch == '\r' -> Unit
                ch == '\n' -> endRow()
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        // 去掉结尾的空行
        return rows.filterNot { it.size == 1 && it[0].isBlank() }
    }

    /**
     * 反解 ICS 的折行：以空格或制表符开头的行是上一行的续行。
     *
     * `trimEnd()` 之后再看开头，是因为不同客户端在续行里留的空白不一致。
     */
    fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        text.split("\r\n", "\n").forEach { raw ->
            val line = raw.trimEnd()
            if (line.isEmpty()) return@forEach
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last().trimEnd() + line.substring(1)
            } else {
                out += line
            }
        }
        return out
    }

    /** `SUMMARY;LANGUAGE=zh:高等数学` → `("SUMMARY", "高等数学")`（属性参数被丢掉）*/
    private fun splitProperty(line: String): Pair<String, String> {
        val colon = line.indexOf(':')
        if (colon < 0) return line.uppercase() to ""
        val key = line.substring(0, colon).substringBefore(';').uppercase()
        return key to line.substring(colon + 1)
    }

    private fun unescapeText(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> append('\n')
                    ',', ';', '\\' -> append(next)
                    else -> append(next)
                }
                i += 2
            } else {
                append(ch)
                i++
            }
        }
    }

    /**
     * `20260921T090000`（浮动本地时间）/ `20260921T010000Z`（UTC → 换算成本机时区）/
     * `20260921`（全天，返回 null —— 全天事件映射不到节次）
     */
    fun parseIcsDateTime(value: String): LocalDateTime? {
        val text = value.trim()
        if (text.isEmpty()) return null
        if ('T' !in text) return null

        return if (text.endsWith("Z")) {
            val local = runCatching { LocalDateTime.parse(text.dropLast(1), ICS_STAMP) }.getOrNull()
                ?: return null
            local.atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        } else {
            runCatching { LocalDateTime.parse(text, ICS_STAMP) }.getOrNull()
        }
    }

    /** 全天事件的日期（`20260921`）；不是全天返回 null —— 给它留个明确的出口 */
    fun parseIcsDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value.trim(), ICS_DATE) }.getOrNull()

    /** 哪一节的开始时刻正好是 [time]；对不上返回 null（说明这份 ICS 不是这个学校的作息）*/
    private fun periodStartingAt(timetable: Timetable, time: LocalTime): Int? {
        for (period in 1..timetable.maxPeriod) {
            val start = parseClock(timetable.startOfPeriod(period)) ?: continue
            if (start == time) return period
        }
        return null
    }

    /** [start, end) 覆盖了从 [firstPeriod] 起的连续几节 */
    private fun periodsCovered(
        timetable: Timetable,
        firstPeriod: Int,
        start: LocalDateTime,
        end: LocalDateTime,
    ): Int {
        var count = 1
        for (period in (firstPeriod + 1)..timetable.maxPeriod) {
            val periodStart = parseClock(timetable.startOfPeriod(period)) ?: break
            if (LocalDateTime.of(end.toLocalDate(), periodStart).isBefore(end)) count++ else break
        }
        return count.coerceAtLeast(1)
    }

    private fun weekIndexOf(weeks: List<SchoolWeek>, date: LocalDate): Int? =
        weeks.firstOrNull { it.contains(date) }?.index

    private fun parseClock(text: String): LocalTime? {
        if (text.isBlank()) return null
        return runCatching { LocalTime.parse(text.padStart(5, '0')) }.getOrNull()
    }
}
