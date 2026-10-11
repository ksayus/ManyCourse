package com.tof.manycourse

import com.tof.manycourse.data.Course
import com.tof.manycourse.data.IcsExport
import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.data.Timetable
import com.tof.manycourse.data.Timetables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ICS 导出的单测。
 *
 * 这类导出的失败方式很特别：**导出不会报错，是"导进去之后不对"** ——
 * 少了几门课、名字被截成两段、日历客户端直接说文件损坏。
 * 所以下面盯的是规范里的硬性要求（75 八位组折行、TEXT 转义、CRLF），
 * 而不是"大概长得像"。
 */
class IcsExportTest {

    /** 2026-09-07 是周一（2026-10-12 往前推 5 周）*/
    private val firstMonday: LocalDate = LocalDate.of(2026, 9, 7)

    private fun week(index: Int) = SchoolWeek(
        index = index,
        start = firstMonday.plusWeeks((index - 1).toLong()),
        end = firstMonday.plusWeeks((index - 1).toLong()).plusDays(6),
    )

    private val weeks = (1..6).map { week(it) }

    private fun course(
        name: String = "高等数学",
        teacher: String = "王建国",
        room: String = "教学楼 A-101",
        weekday: Int = 1,
        startPeriod: Int = 1,
        periodCount: Int = 2,
        weeks: String = "3-5周",
    ) = Course(
        id = 1L,
        name = name,
        teacher = teacher,
        room = room,
        weekday = weekday,
        startPeriod = startPeriod,
        periodCount = periodCount,
        weeks = weeks,
    )

    private val fixedStamp = LocalDateTime.of(2026, 10, 12, 2, 15)

    // ══════════════════ 展开：周次 → 具体日期 ══════════════════

    /**
     * ★ `3-5周` 的周一课 → 第 3/4/5 周的**星期一**各一条。
     *
     * 这条正是"周次串参与渲染"的另一面：以前周次只展示，所以这门课在日历里
     * 要么一条都没有、要么被摊满整个学期。
     */
    @Test
    fun `按周次展开成具体日期`() {
        val plan = IcsExport.plan(listOf(course()), weeks, Timetables.default)

        assertEquals(3, plan.events.size)
        assertEquals(LocalDateTime.of(2026, 9, 21, 9, 0), plan.events[0].start)
        assertEquals(LocalDateTime.of(2026, 9, 21, 10, 20), plan.events[0].end)
        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 0), plan.events[1].start)
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), plan.events[2].start)
        assertEquals("高等数学", plan.events[0].summary)
        assertEquals("教学楼 A-101", plan.events[0].location)
    }

    /** 星期几映射：第 3 周的**周三** = 周一 + 2 天 */
    @Test
    fun `星期几映射到当周的那一天`() {
        val plan = IcsExport.plan(listOf(course(weekday = 3, weeks = "3周")), weeks, Timetables.default)
        assertEquals(1, plan.events.size)
        assertEquals(LocalDate.of(2026, 9, 23), plan.events[0].start.toLocalDate())
    }

    /** 周次串认不出 → 按"每周"处理（与 `WeekRanges` 的口径一致），而不是一条都不导 */
    @Test
    fun `周次认不出时按每周展开`() {
        val plan = IcsExport.plan(listOf(course(weeks = "全周")), weeks, Timetables.default)
        assertEquals("6 个教学周就展开 6 条", 6, plan.events.size)
    }

    /** 没有作息表 → 排不出时刻，**如实记进 skippedNoTime**，不编一个 9:00 出来 */
    @Test
    fun `没有作息时跳过并如实记录`() {
        val blank = Timetable(blocks = List(6) { Timetable.Block("", "") }, periodMinutes = 45)
        val plan = IcsExport.plan(listOf(course()), weeks, blank)

        assertTrue("一条都不该导出来", plan.events.isEmpty())
        assertEquals(1, plan.skippedNoTime.size)
        assertEquals("高等数学", plan.skippedNoTime[0].name)
    }

    /** 没有教学周日期 → 不知道该摆到哪几天，同样如实记录而不是猜一个开学日期 */
    @Test
    fun `没有教学周时跳过并如实记录`() {
        val plan = IcsExport.plan(listOf(course()), emptyList(), Timetables.default)
        assertTrue(plan.events.isEmpty())
        assertEquals(1, plan.skippedNoWeek.size)
    }

    /** UID 必须稳定：重新导出时日历客户端要**更新**而不是堆出第二份课表 */
    @Test
    fun `同一天同一门课的 UID 稳定`() {
        val a = IcsExport.plan(listOf(course()), weeks, Timetables.default)
        val b = IcsExport.plan(listOf(course()), weeks, Timetables.default)
        assertEquals(a.events.map { it.uid }, b.events.map { it.uid })
        assertEquals("每天一个不同的 UID", 3, a.events.map { it.uid }.toSet().size)
    }

    // ══════════════════ 文本转义 ══════════════════

    @Test
    fun `逗号分号反斜杠换行都要转义`() {
        assertEquals("高等数学\\,上册", IcsExport.escapeText("高等数学,上册"))
        assertEquals("A\\;B", IcsExport.escapeText("A;B"))
        assertEquals("A\\\\B", IcsExport.escapeText("A\\B"))
        assertEquals("A\\nB", IcsExport.escapeText("A\nB"))
    }

    /**
     * ★ 转义顺序：**先转义反斜杠**。
     *
     * 反过来的话，逗号转义产生的那个反斜杠会被再转义一次（`\,` → `\\,`），
     * 在日历客户端里就变成了"一个字面反斜杠 + 一个结构逗号" —— 又一次把名字拆开。
     */
    @Test
    fun `反斜杠先转义`() {
        assertEquals("\\\\" + "\\,", IcsExport.escapeText("\\,"))
    }

    // ══════════════════ 折行 ══════════════════

    @Test
    fun `短行不动`() {
        assertEquals(listOf("SUMMARY:高等数学"), IcsExport.fold("SUMMARY:高等数学"))
    }

    /**
     * ★ 75 是**八位组**不是字符：中文一个字 3 字节。
     *
     * 按字符折行的实现会在中文长名字上产出超长行，有的客户端直接把整份文件判为损坏。
     */
    @Test
    fun `每行不超过 75 字节且续行以空格开头`() {
        val long = "SUMMARY:" + "课".repeat(60)
        val folded = IcsExport.fold(long)

        assertTrue("确实折了", folded.size > 1)
        folded.forEachIndexed { index, line ->
            assertTrue(
                "第 $index 行 ${line.toByteArray(Charsets.UTF_8).size} 字节，超了 75",
                line.toByteArray(Charsets.UTF_8).size <= IcsExport.MAX_LINE_OCTETS,
            )
            if (index > 0) assertTrue("续行必须以空格开头", line.startsWith(" "))
        }
        assertEquals("拼回去必须和原来一模一样", long, unfold(folded))
    }

    /**
     * ★ 绝不能在多字节字符（含 emoji 的代理对）中间切断 ——
     * 切断的不是"难看"，是**无效 UTF-8**，解析出来是一串乱码。
     */
    @Test
    fun `折行不会切断多字节字符`() {
        val emoji = "SUMMARY:" + "🏀".repeat(30)
        val folded = IcsExport.fold(emoji)

        assertTrue(folded.size > 1)
        folded.forEach { line ->
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= IcsExport.MAX_LINE_OCTETS)
        }
        assertEquals("emoji 不能被切成半个", emoji, unfold(folded))
    }

    // ══════════════════ 整份文件 ══════════════════

    @Test
    fun `整份文件的结构与行尾`() {
        val plan = IcsExport.plan(listOf(course()), weeks, Timetables.default)
        val ics = IcsExport.calendar(plan, fixedStamp)

        assertTrue("必须 CRLF 行尾（规范要求）", ics.contains("\r\n"))
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertEquals("三条日程", 3, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertTrue(ics.contains("VERSION:2.0"))
        assertTrue(ics.contains("X-WR-CALNAME:课多多课表"))
    }

    /**
     * `DTSTART` 是**浮动本地时间**（不带 `Z`）—— 学生要的是"我这儿 9:00 上课"；
     * `DTSTAMP` 按规范必须是 UTC（带 `Z`）。两者不能搞混。
     */
    @Test
    fun `DTSTART 浮动本地时间而 DTSTAMP 是 UTC`() {
        val plan = IcsExport.plan(listOf(course()), weeks, Timetables.default)
        val ics = IcsExport.calendar(plan, fixedStamp)

        assertTrue(ics.contains("DTSTART:20260921T090000\r\n"))
        assertTrue(ics.contains("DTEND:20260921T102000\r\n"))
        assertTrue(ics.contains("DTSTAMP:20261012T021500Z\r\n"))
        assertTrue("DTSTART 不该带 Z", !ics.contains("DTSTART:20260921T090000Z"))
    }

    /** 名字里的逗号经过转义之后，仍然只对应**一行 SUMMARY**（没被拆成两条属性）*/
    @Test
    fun `带逗号的课程名不会撑破 VEVENT`() {
        val plan = IcsExport.plan(listOf(course(name = "高等数学,上册")), weeks, Timetables.default)
        val ics = IcsExport.calendar(plan, fixedStamp)

        val escaped = "SUMMARY:" + IcsExport.escapeText("高等数学,上册")
        assertEquals("三条日程 → 三条转义后的 SUMMARY", 3, ics.split(escaped).size - 1)
        assertTrue("裸逗号一个都不该出现（它会把属性值截成两段）", !ics.contains("SUMMARY:高等数学,上册"))
        assertEquals(
            "SUMMARY 行数应等于日程数 —— 说明没有被折行拆开",
            3,
            ics.split("\r\n").count { it.startsWith("SUMMARY:") },
        )
    }

    /** 每一行都必须能过 75 字节这道关（整份文件级别再验一次）*/
    @Test
    fun `整份文件的每一行都不超长`() {
        val long = course(name = "一门名字特别长的课程".repeat(6), room = "某栋楼某个特别长的教室名字".repeat(3))
        val plan = IcsExport.plan(listOf(long), weeks, Timetables.default)
        val ics = IcsExport.calendar(plan, fixedStamp)

        ics.split("\r\n").filter { it.isNotEmpty() }.forEach { line ->
            assertTrue("这行超长了：$line", line.toByteArray(Charsets.UTF_8).size <= IcsExport.MAX_LINE_OCTETS)
        }
    }

    private fun unfold(folded: List<String>): String =
        folded.mapIndexed { index, line -> if (index == 0) line else line.removePrefix(" ") }
            .joinToString("")
}
