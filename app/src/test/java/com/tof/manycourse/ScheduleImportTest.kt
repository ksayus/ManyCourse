package com.tof.manycourse

import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.IcsExport
import com.tof.manycourse.data.ScheduleImport
import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.data.Timetables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课表导入（CSV / ICS）的单测。
 *
 * ## 这里最要紧的一条：**坏行不许静默丢掉**
 *
 * 导入 40 门课、实际进来 37 门，界面只说一句"导入成功" —— 用户会以为课表齐了，
 * 然后漏上三节课。所以两个解析器的每个跳过分支都要有断言，钉住"为什么没进来"。
 *
 * ## 第二条：ICS 必须能**自己导的能自己导回来**
 *
 * 换机迁移靠的就是这条路。所以 [导出的 ICS 能被自己完整导回来] 是本文件的重点 ——
 * 它同时验了折行、转义、时间解析、周次反推、教师扩展属性五件事。
 */
class ScheduleImportTest {

    /** 2026-09-07 是周一 */
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
    ) = Course(1L, name, teacher, room, weekday, startPeriod, periodCount, weeks)

    // ══════════════════ CSV ══════════════════

    @Test
    fun `中文表头的一行`() {
        val csv = """
            课程名,教师,教室,星期,起始节次,节数,周次
            高等数学,王建国,教学楼 A-101,周一,1,2,1-16周
        """.trimIndent()

        val report = ScheduleImport.csv(csv)
        assertTrue("不该有问题行：${report.problems}", report.problems.isEmpty())
        assertEquals(1, report.courses.size)
        val imported = report.courses[0]
        assertEquals("高等数学", imported.name)
        assertEquals("王建国", imported.teacher)
        assertEquals("教学楼 A-101", imported.room)
        assertEquals(1, imported.weekday)
        assertEquals(1, imported.startPeriod)
        assertEquals(2, imported.periodCount)
        assertEquals("1-16周", imported.weeks)
    }

    @Test
    fun `英文表头与乱序列都认`() {
        val csv = """
            weekday,startPeriod,name,room
            周三,3,大学英语,公实405
        """.trimIndent()

        val report = ScheduleImport.csv(csv)
        assertEquals(1, report.courses.size)
        assertEquals(3, report.courses[0].weekday)
        assertEquals(3, report.courses[0].startPeriod)
        assertEquals("大学英语", report.courses[0].name)
        assertEquals("节数没给 → 默认连上 2 节", 2, report.courses[0].periodCount)
        assertEquals("周次没给 → 留空（每周都上）", "", report.courses[0].weeks)
    }

    /**
     * ★ Excel 会把带逗号的字段用引号包起来，**里面的逗号不是分隔符**。
     *
     * 不处理引号的话，这一行会被拆成多一列，整行错位 ——
     * 表现为"导进来一堆名字被截断的空课"。
     */
    @Test
    fun `Excel 的引号字段不会被逗号拆开`() {
        val csv = """
            课程名,教师,教室,星期,起始节次,节数,周次
            "高等数学,上册",王建国,"教学楼 A-101, 三楼",周一,1,2,1-16周
        """.trimIndent()

        val report = ScheduleImport.csv(csv)
        assertEquals(1, report.courses.size)
        assertEquals("高等数学,上册", report.courses[0].name)
        assertEquals("教学楼 A-101, 三楼", report.courses[0].room)
    }

    @Test
    fun `字段里的双引号写成两个`() {
        val csv = "课程名,星期,起始节次\n\"他说\"\"上课\"\"\",周一,1"
        val report = ScheduleImport.csv(csv)
        assertEquals("他说\"上课\"", report.courses[0].name)
    }

    @Test
    fun `引号内的换行不算换行`() {
        val csv = "课程名,星期,起始节次\n\"第一行\n第二行\",周一,1"
        val report = ScheduleImport.csv(csv)
        assertEquals(1, report.courses.size)
        assertEquals("第一行\n第二行", report.courses[0].name)
    }

    /** ★ 坏行必须逐条说清楚，而不是安静地少几门课 */
    @Test
    fun `坏行逐条报出原因和行号`() {
        val csv = """
            课程名,教师,教室,星期,起始节次,节数,周次
            高等数学,王建国,A-101,周一,1,2,1-16周
            ,王建国,A-101,周二,1,2,1-16周
            大学英语,李芳,A-102,礼拜二,3,2,1-16周
            体育,赵强,操场,周四,99,2,1-16周
        """.trimIndent()

        val report = ScheduleImport.csv(csv)
        assertEquals("只有第一行是好的", 1, report.courses.size)
        assertEquals(3, report.problems.size)
        assertTrue(report.problems[0].startsWith("第 3 行：课程名为空"))
        assertTrue(report.problems[1].contains("第 4 行") && report.problems[1].contains("星期"))
        assertTrue(report.problems[2].contains("第 5 行") && report.problems[2].contains("起始节次"))
    }

    @Test
    fun `表头缺必需列时给出可操作的提示`() {
        val csv = "名字,老师\n高等数学,王老师"
        val report = ScheduleImport.csv(csv)
        assertTrue(report.courses.isEmpty())
        assertEquals(1, report.problems.size)
        assertTrue(report.problems[0].contains("缺少必需的列"))
        assertTrue("要写清楚缺哪几列", report.problems[0].contains("星期") && report.problems[0].contains("起始节次"))
    }

    @Test
    fun `节数会被夹到本学期最大节次`() {
        val csv = "课程名,星期,起始节次,节数\n高等数学,周一,${CourseRepository.MAX_PERIOD},2"
        val report = ScheduleImport.csv(csv)
        assertEquals("不能超过本节表的最大节次", 1, report.courses[0].periodCount)
    }

    @Test
    fun `周次会被归一化`() {
        val csv = "课程名,星期,起始节次,周次\n高等数学,周一,1,\"7,8,3\""
        val report = ScheduleImport.csv(csv)
        assertEquals("乱序周次归一成区间", "3,7-8周", report.courses[0].weeks)
    }

    @Test
    fun `空文件给出提示而不是空成功`() {
        val report = ScheduleImport.csv("")
        assertTrue(report.courses.isEmpty())
        assertEquals(1, report.problems.size)
    }

    @Test
    fun `星期各种写法都认`() {
        assertEquals(1, ScheduleImport.parseWeekday("周一"))
        assertEquals(1, ScheduleImport.parseWeekday("星期一"))
        assertEquals(1, ScheduleImport.parseWeekday(" 1 "))
        assertEquals(1, ScheduleImport.parseWeekday("Mon"))
        assertEquals(3, ScheduleImport.parseWeekday("周三"))
        assertEquals(7, ScheduleImport.parseWeekday("星期日"))
        assertEquals(5, ScheduleImport.parseWeekday("fri"))
        assertNull("「礼拜一」暂不支持 —— 认不出就别猜", ScheduleImport.parseWeekday("礼拜一"))
        assertNull(ScheduleImport.parseWeekday("8"))
        assertNull(ScheduleImport.parseWeekday(""))
    }

    // ══════════════════ ICS ══════════════════

    /**
     * ★ **自己导的 ICS 必须能自己完整导回来** —— 换机迁移靠的就是这条路。
     *
     * 一条断言同时验了：折行还原、文本转义、浮动时间解析、周次反推、教师扩展属性。
     */
    @Test
    fun `导出的 ICS 能被自己完整导回来`() {
        val original = listOf(
            course(name = "高等数学（上册）", teacher = "王建国", room = "教学楼 A-101", weeks = "3-5周"),
            course(name = "大学英语", teacher = "李芳", room = "公实405", weekday = 3, startPeriod = 8, periodCount = 2, weeks = "1-6周"),
        )
        val plan = IcsExport.plan(original, weeks, Timetables.default)
        val ics = IcsExport.calendar(plan, LocalDateTime.of(2026, 10, 12, 2, 15))

        val report = ScheduleImport.ics(ics, weeks, Timetables.default)
        assertTrue("不该有问题：${report.problems}", report.problems.isEmpty())
        assertEquals(2, report.courses.size)

        val math = report.courses.first { it.name == "高等数学（上册）" }
        assertEquals("教师靠 X-MANYCOURSE-TEACHER 无损往返", "王建国", math.teacher)
        assertEquals("教学楼 A-101", math.room)
        assertEquals(1, math.weekday)
        assertEquals(1, math.startPeriod)
        assertEquals(2, math.periodCount)
        assertEquals("3-5周", math.weeks)

        val english = report.courses.first { it.name == "大学英语" }
        assertEquals(3, english.weekday)
        assertEquals(8, english.startPeriod)
        assertEquals("1-6周", english.weeks)
    }

    /** 连上 4 节也能反推出正确的节数 */
    @Test
    fun `连上四节反推节数`() {
        val plan = IcsExport.plan(listOf(course(periodCount = 4, weeks = "3周")), weeks, Timetables.default)
        val ics = IcsExport.calendar(plan, LocalDateTime.of(2026, 10, 12, 2, 15))

        val report = ScheduleImport.ics(ics, weeks, Timetables.default)
        assertEquals(1, report.courses.size)
        assertEquals(1, report.courses[0].startPeriod)
        assertEquals("第1-4节", 4, report.courses[0].periodCount)
    }

    /** 折行必须还原：超长课程名会被折成多行 */
    @Test
    fun `折行的长课程名能被还原`() {
        val longName = "一门名字特别长的课程".repeat(5)
        val plan = IcsExport.plan(listOf(course(name = longName, weeks = "3周")), weeks, Timetables.default)
        val ics = IcsExport.calendar(plan, LocalDateTime.of(2026, 10, 12, 2, 15))

        val report = ScheduleImport.ics(ics, weeks, Timetables.default)
        assertEquals(1, report.courses.size)
        assertEquals(longName, report.courses[0].name)
    }

    @Test
    fun `unfold 把续行接回去`() {
        val text = "SUMMARY:高等\r\n 数学\r\nDTSTART:20260921T090000"
        assertEquals(listOf("SUMMARY:高等数学", "DTSTART:20260921T090000"), ScheduleImport.unfold(text))
    }

    /** 全天事件没有具体时刻，映射不到节次 —— 单独说一句，不和"坏数据"混为一谈 */
    @Test
    fun `全天事件被跳过并单独说明`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:校运会
            DTSTART;VALUE=DATE:20260921
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val report = ScheduleImport.ics(ics, weeks, Timetables.default)
        assertTrue(report.courses.isEmpty())
        assertEquals(1, report.problems.size)
        assertTrue(report.problems[0].contains("全天事件"))
    }

    /** 时刻对不上本机课表（不是这个学校的 ICS）→ 跳过并说明，而不是硬塞进第 1 节 */
    @Test
    fun `时刻对不上课表时跳过并说明`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:某门课
            DTSTART:20260921T070000
            DTEND:20260921T074500
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val report = ScheduleImport.ics(ics, weeks, Timetables.default)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.problems.any { it.contains("对不上本机课表") })
    }

    @Test
    fun `没有 DTSTART 的事件被跳过`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:坏日程
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val report = ScheduleImport.ics(ics, weeks, Timetables.default)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.problems.any { it.contains("DTSTART") })
    }

    @Test
    fun `没有日程的文件给出提示`() {
        val report = ScheduleImport.ics("BEGIN:VCALENDAR\nEND:VCALENDAR", weeks, Timetables.default)
        assertTrue(report.courses.isEmpty())
        assertTrue(report.problems.any { it.contains("没找到任何日程") })
    }

    /** 落不在已知教学周里的日程 → 按"每周"导入，并如实说明（这是我们知道的上限）*/
    @Test
    fun `落不在教学周里时按每周导入并说明`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:高等数学
            DTSTART:20270104T090000
            DTEND:20270104T102000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val report = ScheduleImport.ics(ics, weeks, Timetables.default)
        assertEquals(1, report.courses.size)
        assertEquals("周次留空 = 每周都上", "", report.courses[0].weeks)
        assertTrue(report.problems.any { it.contains("每周都上") })
    }

    /** 带 `Z` 的时间是 UTC，要换算成本机时区再对节次 */
    @Test
    fun `UTC 时间会被换算`() {
        // 2026-09-21 01:00Z 在 UTC+8 是 09:00 —— 正好是默认表第 1 节
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:高等数学
            DTSTART:20260921T010000Z
            DTEND:20260921T022000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val report = ScheduleImport.ics(ics, weeks, Timetables.default)
        if (java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds == 8 * 3600) {
            assertEquals(1, report.courses.size)
            assertEquals(1, report.courses[0].startPeriod)
        }
    }
}
