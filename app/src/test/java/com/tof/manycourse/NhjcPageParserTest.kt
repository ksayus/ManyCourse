package com.tof.manycourse

import com.tof.manycourse.gr_api.schools.NhjcPageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 金城学院教务系统页面解析的回归测试（JVM，无需设备、不联网）。
 *
 * 为什么值得单独测：这两个解析器**失效时不会报错**，
 * 只会表现为"课表空了""姓名没同步上"。对方一改版就很难靠肉眼发现，
 * 所以把**真实抓下来的页面结构**（已换成假姓名/假班号）固定在这里，
 * 改版后一跑就知道。
 *
 * HTML 里的 `&nbsp;`、`<br>`、`id="LabXxx"` 都是照着真实响应写的，
 * 只有人名/学号/班号换成了合成数据。
 */
class NhjcPageParserTest {

    // ── 课表 ────────────────────────────────────────────────────────────────

    private fun page(header: List<String>, rows: String): String = """
        <html><body>
        <table width="99%" border="1"><tr><td>
        <table id="TabSchedule" cellspacing="1" cellpadding="3" border="0" width="100%">
        <tr id="TableRow1" class="table_titles" valign="top">
        <th id="TableHeaderCell1" width="5%">*</th>${header.joinToString("") { "<th>$it</th>" }}
        </tr>
        $rows
        </table>
        </td></tr></table>
        </body></html>
    """.trimIndent()

    private fun row(period: String, vararg cells: String): String =
        "<tr><td class=\"table_titles\">$period</td>" +
            cells.joinToString("") { "<td>$it</td>" } +
            "</tr>"

    @Test
    fun parsesTeacherRoomWeeksAndCampus() {
        val html = page(
            listOf("星期一", "星期二"),
            row(
                "1-2节",
                "&nbsp;",
                "英语听说（一）&nbsp;&nbsp;A1N403&nbsp;&nbsp;禄口<br>孔雁&nbsp;&nbsp;02220101&nbsp;&nbsp;3-5,8-20周",
            ),
        )
        val courses = NhjcPageParser.parseSchedule(html)

        assertEquals(1, courses.size)
        val course = courses.single()
        assertEquals("英语听说（一）", course.name)
        assertEquals("孔雁", course.teacher)
        assertEquals("A1N403", course.room)
        assertEquals("禄口", course.campus)
        assertEquals("3-5,8-20周", course.weeks)
        assertEquals("星期二 → weekday=2", 2, course.weekday)
        assertEquals("1-2节 → 从第 1 节起", 1, course.startPeriod)
        assertEquals("1-2节 → 连上 2 节", 2, course.periodCount)
    }

    /**
     * 最重要的一条：**星期几必须从表头读**。
     *
     * 系统只渲染"有课的星期"，某天没课时表头会缩短、列号整体左移。
     * 如果写成"第 2 列 = 周一"，这些课就会被排到错误的星期上 ——
     * 页面看起来完全正常，错得悄无声息。
     */
    @Test
    fun readsWeekdayFromHeaderNotFromColumnIndex() {
        val html = page(
            listOf("星期三", "星期四"),
            row(
                "5-6节",
                "综合英语（一）&nbsp;&nbsp;A4S315&nbsp;&nbsp;禄口<br>罗文&nbsp;&nbsp;02220101&nbsp;&nbsp;3-5,8-20周",
                "大学物理&nbsp;&nbsp;B202&nbsp;&nbsp;禄口<br>赵敏&nbsp;&nbsp;02220101&nbsp;&nbsp;2-4周",
            ),
        )
        val courses = NhjcPageParser.parseSchedule(html)

        assertEquals(2, courses.size)
        assertEquals(3, courses[0].weekday)
        assertEquals(4, courses[1].weekday)
        assertEquals("5-6节 → 从第 5 节起", 5, courses[0].startPeriod)
    }

    @Test
    fun splitsTwoCoursesInsideOneCell() {
        // 实测里同一个格子可能塞两门课（不同周次），必须拆成两条
        val html = page(
            listOf("星期一"),
            row(
                "3-4节",
                "综合英语（一）&nbsp;&nbsp;A1N507&nbsp;&nbsp;禄口<br>罗文&nbsp;&nbsp;02220101&nbsp;&nbsp;3-5,8-11周<br>" +
                    "大学生心理健康（一）&nbsp;&nbsp;A4S404&nbsp;&nbsp;禄口<br>杨玥&nbsp;&nbsp;02220101&nbsp;&nbsp;12-19周",
            ),
        )
        val courses = NhjcPageParser.parseSchedule(html)

        assertEquals(2, courses.size)
        assertEquals("综合英语（一）", courses[0].name)
        assertEquals("3-5,8-11周", courses[0].weeks)
        assertEquals("大学生心理健康（一）", courses[1].name)
        assertEquals("杨玥", courses[1].teacher)
        assertEquals("12-19周", courses[1].weeks)
        assertEquals("两门课都在周一", 1, courses[1].weekday)
    }

    @Test
    fun handlesCoursesWithoutRoomOrTeacher() {
        // 「体育（一）」实测就是这样：没有教室和教师，下行只给了班号和周次。
        // 不能因此把班号当成教师名。
        val html = page(
            listOf("星期一"),
            row("5-6节", "体育（一）&nbsp;&nbsp;&nbsp;&nbsp;<br>&nbsp;&nbsp;02220101&nbsp;&nbsp;3-5,8-20周"),
        )
        val courses = NhjcPageParser.parseSchedule(html)

        assertEquals(1, courses.size)
        val course = courses.single()
        assertEquals("体育（一）", course.name)
        assertEquals("教师应为空", "", course.teacher)
        assertEquals("教室应为空", "", course.room)
        assertEquals("3-5,8-20周", course.weeks)
    }

    @Test
    fun keepsMultiClassCoursesOutOfTheTeacherField() {
        // 合班课：尾行是「教师 班级列表 周次」，班级列表里带逗号和区间
        val html = page(
            listOf("星期四"),
            row(
                "3-4节",
                "思想道德与法治&nbsp;&nbsp;A4N205&nbsp;&nbsp;禄口<br>" +
                    "丁茵&nbsp;&nbsp;02210101,02220101-403&nbsp;&nbsp;2-5,8-13周",
            ),
        )
        val course = NhjcPageParser.parseSchedule(html).single()

        assertEquals("丁茵", course.teacher)
        assertEquals("2-5,8-13周", course.weeks)
    }

    @Test
    fun stripsThePracticeClashMarkerFromCourseName() {
        // 页面备注：课程名含「㊣」表示与实践环节交叉时正常上课
        val html = page(
            listOf("星期五"),
            row("7-8节", "㊣军事训练&nbsp;&nbsp;A4S205&nbsp;&nbsp;禄口<br>罗文&nbsp;&nbsp;02220101&nbsp;&nbsp;12周"),
        )
        val course = NhjcPageParser.parseSchedule(html).single()

        assertEquals("军事训练", course.name)
        assertEquals("12周", course.weeks)
        assertEquals("单周次也要能解析成 1 节", 7, course.startPeriod)
    }

    @Test
    fun skipsEmptyCellsAndSupportsSelfClosingBr() {
        // 空格子实测是 &nbsp;，同时正方/金城两种 <br> 写法都要认
        val html = page(
            listOf("星期一", "星期二"),
            row("9-10节", "&nbsp;", "英语泛读（一）&nbsp;&nbsp;A4S111&nbsp;&nbsp;禄口<br/>王军红&nbsp;&nbsp;02220101&nbsp;&nbsp;2-4周"),
        )
        val courses = NhjcPageParser.parseSchedule(html)

        assertEquals("空格子不应产出课程", 1, courses.size)
        assertEquals("王军红", courses.single().teacher)
        assertEquals("9-10节 → 从第 9 节起", 9, courses.single().startPeriod)
    }

    @Test
    fun parsesEveryPeriodRowOfTheRealLayout() {
        val html = page(
            listOf("星期一"),
            listOf("1-2节", "3-4节", "5-6节", "7-8节", "9-10节").joinToString("") { period ->
                row(period, "课程$period&nbsp;&nbsp;A101&nbsp;&nbsp;禄口<br>老师&nbsp;&nbsp;02220101&nbsp;&nbsp;1-2周")
            },
        )
        val courses = NhjcPageParser.parseSchedule(html)

        assertEquals(5, courses.size)
        assertEquals(listOf(1, 3, 5, 7, 9), courses.map { it.startPeriod })
        assertEquals("每行都是两节", listOf(2, 2, 2, 2, 2), courses.map { it.periodCount })
    }

    @Test
    fun failsLoudlyWhenTheTableIsGone() {
        // 改版时宁可报错，也不要静默返回空表（那会被误读成"这学期没课"）
        val error = assertThrows(IOException::class.java) {
            NhjcPageParser.parseSchedule("<html><body><p>系统维护中</p></body></html>")
        }
        assertTrue(error.message.orEmpty().contains("TabSchedule"))
    }

    @Test
    fun failsLoudlyWhenTheHeaderHasNoWeekday() {
        val html = page(listOf("第1列", "第2列"), row("1-2节", "&nbsp;", "&nbsp;"))
        val error = assertThrows(IOException::class.java) {
            NhjcPageParser.parseSchedule(html)
        }
        assertTrue(error.message.orEmpty().contains("星期"))
    }

    // ── 学籍（姓名）────────────────────────────────────────────────────────

    private val profileHtml = """
        <html><body>
        <span id="LabXm1">张同学</span>&nbsp;
        <span id="LabXm">张同学</span>
        <span id="LabXh1">0222010101</span>&nbsp;
        <span id="LabXh">0222010101</span>
        <span id="LabBh">02220101</span>
        <span id="LabZym1">翻译</span>
        <span id="LabXsm1">人文社科学院</span>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesStudentProfileFromTheRealLayout() {
        val profile = NhjcPageParser.parseProfile(profileHtml, fallbackName = null, fallbackAccount = "0222010101")

        // ★ 需求：姓名必须取"学校系统里显示的名字"，而不是登录用的学号
        assertEquals("张同学", profile.name)
        assertEquals("0222010101", profile.account)
        assertEquals("02220101", profile.className)
        assertEquals("翻译", profile.major)
        assertEquals("人文社科学院", profile.college)
        assertEquals("翻译 · 人文社科学院", profile.subtitle)
    }

    @Test
    fun prefersTheBodySpansOverTheSummaryBar() {
        // 带 1 后缀的是顶部摘要条，不带的是正文。id 匹配带闭引号，不会串。
        val html = "<span id=\"LabXm1\">旧值</span><span id=\"LabXm\">新值</span>"
        assertEquals("新值", NhjcPageParser.parseProfile(html, null, "acc").name)
    }

    @Test
    fun fallsBackToTheNameCookieWhenThePageHasNoName() {
        // 老系统登录后必定下发 U_NameCn Cookie，页面改版时靠它兜底
        val profile = NhjcPageParser.parseProfile(
            html = "<html><body><span id=\"LabBh\">02220101</span></body></html>",
            fallbackName = "李同学",
            fallbackAccount = "0222010101",
        )
        assertEquals("李同学", profile.name)
        assertEquals("02220101", profile.className)
    }

    @Test
    fun failsLoudlyWhenNeitherNameNorCookieIsAvailable() {
        val error = assertThrows(IOException::class.java) {
            NhjcPageParser.parseProfile("<html></html>", fallbackName = null, fallbackAccount = "x")
        }
        assertTrue(error.message.orEmpty().contains("LabXm"))
    }
}
