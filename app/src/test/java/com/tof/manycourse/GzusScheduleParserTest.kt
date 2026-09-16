package com.tof.manycourse

import com.tof.manycourse.gr_api.schools.GzusScheduleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 广软正方课表解析的纯逻辑回归测试（JVM，无需设备、不联网）。
 *
 * 取值全部照抄**实测抓包**里的真实形态（课程名/教师/楼栋/周次都是真实格式，
 * 只把姓名学号换成合成值）：
 *
 * ```json
 * {"kcmc":"大学英语I（综合基础）","xm":"邓念利","cdmc":"U204","lh":"笃行楼U",
 *  "xqj":"1","jcs":"1-2","zcd":"4-19周","xqmc":"广州校区","zcmc":"讲师（高校）"}
 * ```
 *
 * 解析失效时的表现是"课表空了"，不会报错 —— 所以这几条必须钉住。
 * JSON 取字段那一层由真机测试 `GzusScheduleJsonTest` 覆盖
 * （`org.json` 在 JVM 单测里是未实现的 stub，跑不了）。
 */
class GzusScheduleParserTest {

    @Test
    fun mapsTheRealFieldSet() {
        val course = requireNotNull(
            GzusScheduleParser.toCourse(
                courseName = "大学英语I（综合基础）",
                teacher = "邓念利",
                building = "笃行楼U",
                room = "U204",
                campus = "广州校区",
                weekday = "1",
                periods = "1-2",
                weeks = "4-19周",
            )
        )

        assertEquals("大学英语I（综合基础）", course.name)
        assertEquals("教师必须取 xm", "邓念利", course.teacher)
        assertEquals("教学楼 + 教室要拼起来", "笃行楼U U204", course.room)
        assertEquals("广州校区", course.campus)
        assertEquals(1, course.weekday)
        assertEquals(1, course.startPeriod)
        assertEquals(2, course.periodCount)
        assertEquals("4-19周", course.weeks)
    }

    @Test
    fun periodParsingCoversTheShapesTheServerSends() {
        // 服务端给的是 "1-2"（也见过带"节"的写法）
        assertEquals(1 to 2, GzusScheduleParser.parsePeriods("1-2"))
        assertEquals(3 to 2, GzusScheduleParser.parsePeriods("3-4"))
        assertEquals(9 to 2, GzusScheduleParser.parsePeriods("9-10"))
        assertEquals(1 to 2, GzusScheduleParser.parsePeriods("1-2节"))
        // 只给单节时按"连上 1 节"算
        assertEquals(5 to 1, GzusScheduleParser.parsePeriods("5"))
        assertEquals(7 to 1, GzusScheduleParser.parsePeriods("第7节"))
        // 解析不了就明确失败，不要瞎猜
        assertNull(GzusScheduleParser.parsePeriods(""))
        assertNull(GzusScheduleParser.parsePeriods("待定"))
    }

    @Test
    fun roomJoinDegradesWhenOneSideIsMissing() {
        val onlyRoom = requireNotNull(
            GzusScheduleParser.toCourse("体育", "王老师", "", "体育馆", "", "5", "7-8", "1-16周")
        )
        assertEquals("体育馆", onlyRoom.room)

        val onlyBuilding = requireNotNull(
            GzusScheduleParser.toCourse("自习", "", "图书馆", "", "", "5", "7-8", "")
        )
        assertEquals("图书馆", onlyBuilding.room)

        val neither = requireNotNull(
            GzusScheduleParser.toCourse("线上课", "李老师", "", "", "", "5", "7-8", "")
        )
        assertEquals("", neither.room)
    }

    @Test
    fun rejectsIncompleteRowsInsteadOfGuessing() {
        // 没课名 / 星期不合法 / 节次解析不了 —— 三种都必须跳过，而不是塞一条脏数据进课表
        assertNull(GzusScheduleParser.toCourse("", "老师", "", "", "", "1", "1-2", ""))
        assertNull(GzusScheduleParser.toCourse("   ", "老师", "", "", "", "1", "1-2", ""))
        assertNull(GzusScheduleParser.toCourse("课", "老师", "", "", "", "0", "1-2", ""))
        assertNull(GzusScheduleParser.toCourse("课", "老师", "", "", "", "8", "1-2", ""))
        assertNull(GzusScheduleParser.toCourse("课", "老师", "", "", "", "一", "1-2", ""))
        assertNull(GzusScheduleParser.toCourse("课", "老师", "", "", "", "1", "", ""))
    }

    @Test
    fun weekdayIsOneToSevenAsTheServerSendsIt() {
        // 服务端 xqj 直接是 1~7（xqjmc 才是"星期一"），不要再自己映射一遍
        (1..7).forEach { day ->
            val course = requireNotNull(
                GzusScheduleParser.toCourse("课", "老师", "", "", "", day.toString(), "1-2", "")
            )
            assertEquals(day, course.weekday)
        }
    }

    @Test
    fun teacherFieldIsNotTheTitleField() {
        // zcmc 是"教师职称"（讲师（高校）），长得像"周次名称"、也挨着教师字段，
        // 是最容易串的一个。这里用真实取值说明两者不同：
        val course = requireNotNull(
            GzusScheduleParser.toCourse(
                courseName = "数字媒体导论",
                teacher = "葛建梅",      // xm
                building = "厚德楼G",
                room = "G301",
                campus = "广州校区",
                weekday = "1",
                periods = "3-4",
                weeks = "5-18周",
            )
        )
        assertEquals("葛建梅", course.teacher)
        assertEquals("教师名不该出现职称字样", false, course.teacher.contains("讲师"))
        assertEquals("周次应来自 zcd", "5-18周", course.weeks)
    }

    @Test
    fun profileUsesTheStudentFields() {
        val profile = requireNotNull(
            GzusScheduleParser.toProfile(
                name = "张同学",
                account = "0222010102",
                className = "2026级数字媒体技术1班",
                major = "数字媒体技术",
                schoolYear = "2026-2027",
            )
        )
        assertEquals("张同学", profile.name)
        assertEquals("0222010102", profile.account)
        assertEquals("2026级数字媒体技术1班", profile.className)
        // 副标题是"专业 · 学院"，这里学院位放的是学年（正方 xsxx 里没有学院字段）
        assertEquals("数字媒体技术 · 2026-2027", profile.subtitle)
    }

    @Test
    fun profileWithoutNameIsRejected() {
        assertNull(GzusScheduleParser.toProfile("", "x", "", "", ""))
        assertNull(GzusScheduleParser.toProfile("  ", "x", "", "", ""))
    }

    @Test
    fun termIsReadFromTheSelectedOptionsOfThePage() {
        // 实测页面片段：xnm 的选中项 value=2026（显示 2026-2027），xqm 的选中项 value=3（显示 1）
        val html = """
            <select name="xnm" id="xnm" class="form-control chosen-select">
              <option value="" ></option>
              <option value="2027">2027-2028</option>
              <option value="2026" selected="selected">2026-2027</option>
              <option value="2025">2025-2026</option>
            </select>
            <select name="xqm" id="xqm" class="form-control chosen-select">
              <option value="" ></option>
              <option value="3" selected="selected">1</option>
              <option value="12">2</option>
            </select>
        """.trimIndent()

        assertEquals("2026" to "3", GzusScheduleParser.parseTerm(html))
    }

    @Test
    fun termIsNullWhenThePageLacksTheSelectors() {
        // 读不到就不能瞎默认成一个学期（会查出一张空课表，还不报错）
        assertNull(GzusScheduleParser.parseTerm("<html><body>系统维护中</body></html>"))
        assertNull(GzusScheduleParser.parseTerm("""<select id="xnm"><option value="2026" selected>x</option></select>"""))
    }

    // ── 周次课表页：教学周列表 ──────────────────────────────────────────────

    @Test
    fun weeksComeFromTheZsSelectOfTheWeekSchedulePage() {
        // 实测页面原文（信息查询 → 周次课表）：每一项都自带起止日期，
        // 带 selected 的那一项就是"当前教学周"
        val html = """
            <select name="zs" id="zs" class="form-control chosen-select" validate="{required:true}">
              <option value="1">1(2026-08-31至2026-09-06)</option>
              <option value="2">2(2026-09-07至2026-09-13)</option>
              <option value="3" selected="selected">3(2026-09-14至2026-09-20)</option>
              <option value="20">20(2027-01-11至2027-01-17)</option>
            </select>
        """.trimIndent()

        val weeks = GzusScheduleParser.parseWeeks(html)
        assertEquals(listOf(1, 2, 3, 20), weeks.map { it.index })
        assertEquals(LocalDate.of(2026, 9, 14), weeks[2].start)
        assertEquals(LocalDate.of(2026, 9, 20), weeks[2].end)
        assertTrue("第 3 周应覆盖 09-16", weeks[2].contains(LocalDate.of(2026, 9, 16)))
        assertFalse("第 3 周不该覆盖 09-21", weeks[2].contains(LocalDate.of(2026, 9, 21)))
    }

    @Test
    fun weekLabelSurvivesOtherWordingInTheBrackets() {
        // 括号里那句文案各版本都改过（"至"/"-"/"~"），但日期格式是稳定的：
        // 解析只认两个 yyyy-MM-dd，周次以 value 为准
        val weeks = GzusScheduleParser.parseWeeks(
            """
            <select id="zs">
              <option value="7">7(2026-10-12-2026-10-18)</option>
              <option value="8">8(2026-10-19 ~ 2026-10-25)</option>
            </select>
            """.trimIndent()
        )
        assertEquals(listOf(7, 8), weeks.map { it.index })
        assertEquals(LocalDate.of(2026, 10, 19), weeks[1].start)
    }

    @Test
    fun brokenWeekOptionsAreSkippedInsteadOfKillingTheList() {
        // 一条坏数据只跳过那一条：整页读不出来 = 日历彻底没数据，代价太大
        val weeks = GzusScheduleParser.parseWeeks(
            """
            <select id="zs">
              <option value="">(待定)</option>
              <option value="abc">abc(2026-09-14至2026-09-20)</option>
              <option value="4">4(日期未定)</option>
              <option value="5">5(2026-09-21至2026-09-27)</option>
            </select>
            """.trimIndent()
        )
        assertEquals(listOf(5), weeks.map { it.index })
    }

    @Test
    fun weeksAreSortedAndTermIsIndependentOfTheWeekSelect() {
        // zs 下拉框里没有 xnm/xqm，parseTerm 仍应只认它自己那两个 select
        val html = """
            <select name="xnm" id="xnm"><option value="2026" selected="selected">2026-2027</option></select>
            <select name="xqm" id="xqm"><option value="3" selected="selected">1</option></select>
            <select name="zs" id="zs"><option value="5">5(2026-09-21至2026-09-27)</option></select>
        """.trimIndent()
        assertEquals("2026" to "3", GzusScheduleParser.parseTerm(html))
        assertEquals(listOf(5), GzusScheduleParser.parseWeeks(html).map { it.index })
    }

    @Test
    fun weekSchedulePageWithoutTheSelectYieldsNoWeeks() {
        // 页面改版时返回空表 → 调用方报"读不到教学周列表"，而不是画一张空日历
        assertEquals(emptyList<Any>(), GzusScheduleParser.parseWeeks("<html><body>系统维护中</body></html>"))
    }
}
