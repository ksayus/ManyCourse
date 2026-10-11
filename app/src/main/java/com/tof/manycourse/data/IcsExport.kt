package com.tof.manycourse.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * 课表 → **ICS 日历文件**（RFC 5545）。
 *
 * ## 为什么这是"多端同步"最划算的替代品
 *
 * 项目明确不做服务端（README §10：不转发用户数据），所以"手机 / 平板 / 电脑都能看"
 * 不可能靠自建云。但**系统日历天生就是多端的**：导出一份 `.ics` 丢进系统日历 / Google 日历 /
 * Outlook，同步、锁屏提醒、勿扰模式、多设备全都白拿 —— 一行服务端代码都不用写。
 *
 * ## 两个必须做对的细节（也是这类导出最容易错的地方）
 *
 * ### 1. 折行按**字节**算，不是按字符
 *
 * RFC 5545 要求每行不超过 **75 个八位组**，续行以一个空格开头。
 * 中文一个字是 **3 个字节**（UTF-8），所以"75 个字符"和"75 个字节"差了 3 倍 ——
 * 按字符折行的实现会在长课程名上产出超长行，**有的日历客户端会直接把整份文件判为损坏**。
 *
 * 而且**绝不能在多字节字符中间切断**：切断了的不是"难看"，是**无效的 UTF-8**，
 * 解析出来是一串乱码。所以这里按**码点**（不是 `char`）遍历，连 emoji 一起保住。
 *
 * ### 2. 文本必须转义
 *
 * `,` `;` `\` 和换行在 TEXT 值里都是**结构字符**：课程名里有个英文逗号
 * （`高等数学,上册`）不转义，轻则被拆成两段，重则整个 VEVENT 解析失败。
 *
 * ## 时间用"浮动本地时间"（不带 `Z`、不带 `TZID`）
 *
 * 学生要的是"9:00 上课"，指的是**他所在时区的 9:00**。写死 UTC 会让跨时区/夏令时的时候
 * 偏移一小时；浮动时间交给日历客户端按本机时区解释，这正是我们想要的口径。
 * （例外是 `DTSTAMP`，规范要求它必须是 UTC —— 见 [calendar] 的 `stamp` 参数。）
 */
object IcsExport {

    /** RFC 5545：一行（不含 CRLF）最多 75 个八位组 */
    const val MAX_LINE_OCTETS = 75

    private val STAMP_UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val TIMED: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * 一条**具体发生的**日程（已经按周次展开到某一天）。
     *
     * 这里刻意不生成 `RRULE`：课表的"周次"是**离散集合**（`3-5,8-20周`，甚至单双周），
     * 用 `RRULE` 表达既绕又容易错；直接摊成一条条 VEVENT 反而更准，
     * 而且每条都带稳定 UID —— 重新导出时日历客户端会**更新**而不是堆出一份重复课表。
     */
    data class Event(
        val uid: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val summary: String,
        val location: String,
        val description: String,
        /**
         * 任课教师。
         *
         * 为什么单独占一个字段而不是塞进 [description]：ICS 的标准属性里**没有"教师"**，
         * 而 description 是我们自己拼的中文串（格式随时可能变），从里面正则抠教师属于
         * "靠约定解析自己的输出"。所以导出一个自描述的扩展属性 `X-MANYCOURSE-TEACHER` ——
         * 别的日历客户端会忽略它，我们自己的导入能无损读回来。
         */
        val teacher: String = "",
    )

    /**
     * 展开结果 + **如实说明哪些课没能导出**。
     *
     * 为什么要把跳过的课单独列出来：导出要是静默少了几门课，用户拿着日历去上课，
     * 少的那门就是白跑一趟。宁可界面上写一句"有 2 门课因为没有作息没导出"。
     */
    data class Plan(
        val events: List<Event>,
        /** 这所学校还没收录作息 → 排不出具体时刻 */
        val skippedNoTime: List<Course>,
        /** 拿不到教学周日期 → 不知道这些周次对应哪几天 */
        val skippedNoWeek: List<Course>,
    ) {
        val isEmpty: Boolean get() = events.isEmpty()
    }

    /**
     * 把课表展开成一条条具体日程。
     *
     * @param weeks 教学周（来自教务系统，**不是**客户端猜的开学日期）。空表时所有课都会被
     *   记进 [Plan.skippedNoWeek] —— 这是刻意的：没有教学周就不知道该摆到哪几天
     */
    fun plan(
        courses: List<Course>,
        weeks: List<SchoolWeek>,
        timetable: Timetable,
        keyword: String = "课多多",
    ): Plan {
        val events = mutableListOf<Event>()
        val noTime = mutableListOf<Course>()
        val noWeek = mutableListOf<Course>()

        courses.forEach { course ->
            val start = parseClock(timetable.startOfPeriod(course.startPeriod))
            val lastPeriod = course.startPeriod + course.periodCount.coerceAtLeast(1) - 1
            val end = parseClock(timetable.endOfPeriod(lastPeriod))
            if (start == null || end == null) {
                noTime += course
                return@forEach
            }

            // 这门课要落在哪几周：认不出周次串时按"每周"处理（与 WeekRanges 的口径一致）
            val wanted = WeekRanges.weeksOrNull(course.weeks) ?: weeks.map { it.index }.toSet()
            val matched = weeks.filter { it.index in wanted }
            if (matched.isEmpty()) {
                noWeek += course
                return@forEach
            }

            matched.forEach { week ->
                val date = dateOf(week, course.weekday) ?: return@forEach
                events += Event(
                    uid = uidOf(course, date, start, keyword),
                    start = LocalDateTime.of(date, start),
                    end = LocalDateTime.of(date, end),
                    summary = course.name,
                    location = course.room,
                    teacher = course.teacher,
                    description = descriptionOf(course, start, end),
                )
            }
        }

        return Plan(events.sortedBy { it.start }, noTime, noWeek)
    }

    /**
     * 渲染成 `.ics` 文本（**CRLF 行尾**，规范要求）。
     *
     * @param stamp **必须是 UTC**（规范要求 `DTSTAMP` 为 UTC）：
     *   调用方传 `LocalDateTime.now(ZoneOffset.UTC)`。做成参数是为了能单测钉住输出
     */
    fun calendar(plan: Plan, stamp: LocalDateTime, calendarName: String = "课多多课表"): String {
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//ManyCourse//课多多//CN",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "X-WR-CALNAME:${escapeText(calendarName)}",
        )
        plan.events.forEach { event ->
            lines += "BEGIN:VEVENT"
            lines += "UID:${escapeText(event.uid)}"
            lines += "DTSTAMP:${STAMP_UTC.format(stamp)}"
            lines += "DTSTART:${TIMED.format(event.start)}"
            lines += "DTEND:${TIMED.format(event.end)}"
            lines += "SUMMARY:${escapeText(event.summary)}"
            if (event.location.isNotBlank()) lines += "LOCATION:${escapeText(event.location)}"
            if (event.teacher.isNotBlank()) lines += "X-MANYCOURSE-TEACHER:${escapeText(event.teacher)}"
            if (event.description.isNotBlank()) lines += "DESCRIPTION:${escapeText(event.description)}"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"

        return lines.flatMap { fold(it) }.joinToString("\r\n", postfix = "\r\n")
    }

    /** [plan] + [calendar] 一步到位 */
    fun export(
        courses: List<Course>,
        weeks: List<SchoolWeek>,
        timetable: Timetable,
        stamp: LocalDateTime,
    ): Pair<Plan, String> {
        val plan = plan(courses, weeks, timetable)
        return plan to calendar(plan, stamp)
    }

    // ══════════════════ 下面都是可单测的纯逻辑 ══════════════════

    /**
     * TEXT 值转义。
     *
     * 顺序要紧：**先转义反斜杠**，否则后面插入的 `\,` 里的反斜杠会被再转义一次。
     */
    fun escapeText(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit // CRLF 统一在 \n 上处理，单独的回车丢掉
                else -> append(ch)
            }
        }
    }

    /**
     * 按 75 **八位组**折行，续行以**一个空格**开头。
     *
     * 两个容易错的点：
     *  - 按**码点**遍历，不按 `char`：`char` 会把 emoji 的代理对切成两半，
     *    产出无效 UTF-8；
     *  - 续行的可用宽度是 **74**：那一格前导空格也算在 75 之内。
     */
    fun fold(line: String): List<String> {
        if (line.toByteArray(Charsets.UTF_8).size <= MAX_LINE_OCTETS) return listOf(line)

        val out = mutableListOf<String>()
        val chunk = StringBuilder()
        var bytes = 0
        var isFirst = true

        var i = 0
        while (i < line.length) {
            val codePoint = line.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val text = line.substring(i, i + charCount)
            val textBytes = text.toByteArray(Charsets.UTF_8).size
            val limit = if (isFirst) MAX_LINE_OCTETS else MAX_LINE_OCTETS - 1

            if (bytes + textBytes > limit && chunk.isNotEmpty()) {
                out += if (isFirst) chunk.toString() else " $chunk"
                chunk.setLength(0)
                bytes = 0
                isFirst = false
            }
            chunk.append(text)
            bytes += textBytes
            i += charCount
        }
        if (chunk.isNotEmpty()) out += if (isFirst) chunk.toString() else " $chunk"
        return out
    }

    /** 教学周 [week] 里"星期 [weekday]"的那一天；越界返回 null */
    private fun dateOf(week: SchoolWeek, weekday: Int): LocalDate? {
        if (weekday !in 1..7) return null
        val date = week.start.plusDays((weekday - 1).toLong())
        return if (week.contains(date)) date else null
    }

    /**
     * 稳定 UID：**同一门课同一天永远算出同一个值**。
     *
     * 这样重新导出、再导入系统日历时，客户端会认成"同一个日程"去更新，
     * 而不是每次导出都堆出一份重复课表（用户会看到两份完全一样的课）。
     */
    private fun uidOf(course: Course, date: LocalDate, start: java.time.LocalTime, keyword: String): String =
        "mc-${DATE.format(date)}-p${course.startPeriod}-${abs(course.name.hashCode())}-${abs(keyword.hashCode())}@manycourse"

    private fun descriptionOf(course: Course, start: java.time.LocalTime, end: java.time.LocalTime): String =
        buildString {
            append(course.periodLabel)
            append(" · ").append(formatClock(start)).append('~').append(formatClock(end))
            if (course.teacher.isNotBlank()) append(" · ").append(course.teacher)
            if (course.weeks.isNotBlank()) append(" · ").append(course.weeks)
            append("\n由 ManyCourse 课多多导出")
        }

    private fun parseClock(text: String): java.time.LocalTime? {
        if (text.isBlank()) return null
        return runCatching { java.time.LocalTime.parse(text.padStart(5, '0')) }.getOrNull()
    }
}
