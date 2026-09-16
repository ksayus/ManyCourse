package com.tof.manycourse

import com.tof.manycourse.gr_api.schools.GdutScheduleParser
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广工课表接口的 **JSON 取字段**层（真机 / 模拟器测试）。
 *
 * 为什么放 androidTest：`org.json` 在 JVM 单测里是未实现的 stub（一调用就抛 "not mocked"），
 * 而为这一层专门重写一套 JSON 解析并不划算。放到设备上跑，用的就是 App 真正的实现。
 * 纯字符串那一层（隐藏域、周次归一化、节次拆分）在 `GdutScheduleParserTest` 里，不需要设备。
 *
 * 这一层要守的是"**哪个 JSON 字段对应哪个参数**"，因为写错了**不报任何错**：
 *
 *  - 整学期接口（`xsAllKbList`）的场地字段叫 `jxcdmcs`，而周课表接口（`getKbRq`）叫 `jxcdmc`
 *    —— 少一个 `s`，写死了另一个接口的字段名就会让"这一周的课全都没教室"；
 *  - 教师是 `teaxms`（"教师姓名s"），不是 `teadms`（那是工号）；
 *  - 星期是 `xq`，节次是 `jcdm2`，周次是 `zcs`（整学期）/ `zc`（单周）。
 *
 * 响应结构照抄实测抓包（2026-09-16、2026 秋季学期），只把课名/姓名换成合成值。
 */
class GdutScheduleJsonTest {

    /** 照抄 `xsgrkbcx!xsAllKbList.action?xnxqdm=202601` 页面里的 `var kbxx`（字段一个不少）*/
    private val semesterPage = """
        <script type="text/javascript">
        ${'$'}(document).ready(function(){
          var kbxx = [{"kcmc":"大学英语(1)","kcbh":"TMP0928","jxbmc":"产品设计26(3),产品设计26(4)","kcrwdm":"1363577","jcdm2":"01,02","zcs":"16,7,8,9,10,11,17,18,19,12,13,14,15","xq":"2","jxcdmcs":"公实405","teaxms":"刘萱"},{"kcmc":"综合设计基础(1)","kcbh":"TMP9639","jxbmc":"产品设计26(3)","kcrwdm":"1363593","jcdm2":"05,06,07,08","zcs":"16,17,12,13,14,15","xq":"2","jxcdmcs":"创新212","teaxms":"王平胜"},{"kcmc":"","kcbh":"TMP0000","jcdm2":"01","zcs":"1","xq":"3","jxcdmcs":"某室","teaxms":"某人"},{"kcmc":"坏数据：星期是 0","jcdm2":"01","zcs":"1","xq":"0","jxcdmcs":"某室","teaxms":"某人"}];
          ${'$'}.each(kbxx,function(index,item){});
        });
        </script>
    """.trimIndent()

    @Test
    fun parsesSemesterScheduleFromTheRealPageShape() {
        val courses = GdutScheduleParser.parseSemesterSchedule(semesterPage)

        assertEquals("坏数据（没课名 / 星期非法）应被跳过，不该让整张课表拉不出来", 2, courses.size)

        val english = courses[0]
        assertEquals("大学英语(1)", english.name)
        assertEquals("教师取 teaxms，不是 teadms（工号）", "刘萱", english.teacher)
        assertEquals("公实405", english.room)
        assertEquals(2, english.weekday)
        assertEquals(1, english.startPeriod)
        assertEquals(2, english.periodCount)
        assertEquals("乱序周次必须归一化", "7-19周", english.weeks)

        assertEquals("综合设计基础(1)", courses[1].name)
        assertEquals("同一周次的课要占 5~8 节", 5, courses[1].startPeriod)
        assertEquals(4, courses[1].periodCount)
    }

    @Test
    fun rawWeeksAreAvailableForCountingTeachingWeeks() {
        assertEquals(
            "教学周总数取数据里的最大周次（这里应该有 19）",
            19,
            GdutScheduleParser.maxWeekOf(GdutScheduleParser.parseRawWeeks(semesterPage)),
        )
    }

    @Test
    fun missingKbxxFailsLoudly() {
        // 页面里没有 kbxx = 没登录 / 接口改版，必须抛错让上层报"登录已过期"，
        // 而不是静默返回空课表（那会被读成"这学期没课"）
        val error = runCatching { GdutScheduleParser.parseSemesterSchedule("<html>登录页</html>") }
        assertTrue("应当抛异常", error.isFailure)
        assertTrue(
            "报错里要提 kbxx，方便对照日志定位",
            error.exceptionOrNull()?.message.orEmpty().contains("kbxx"),
        )

        // 抠出来了但不是合法 JSON（页面被截断）→ 同样要报错
        assertTrue(
            runCatching { GdutScheduleParser.parseSemesterSchedule("var kbxx = [{不是JSON}];") }.isFailure,
        )
    }

    /**
     * 周课表接口的响应：**二元数组** `[[课程…],[星期→日期…]]`。
     *
     * 字段与整学期接口**长得像但不完全一样** —— 场地是 `jxcdmc`（少一个 s）、
     * 周次是 `zc`（单周）而不是 `zcs`。这条测试钉的就是这件事。
     */
    private val weekJson = """
        [[{"dgksdm":"3437912","kbdm":"","kcbh":"TMP9640","kcmc":"设计造型","teaxms":"林老师","jxbdm":"1389894","xnxqdm":"202601","jxbmc":"产品设计26(3)","zc":"5","jcdm":"01020304","jcdm2":"01,02,03,04","xq":"3","jxcdmc":"创新212","sknrjj":"设计手绘基础知识","teadms":"4212052138","zxs":"48","xs":"4","pkrs":"30","kxh":"2"},
          {"dgksdm":"3417061","kbdm":"","kcbh":"TMP11579","kcmc":"人工智能概论：人文与科学","teaxms":"罗老师","jxbdm":"1389882","jxbmc":"产品设计26(3)","zc":"5","jcdm":"0304","jcdm2":"03,04","xq":"2","jxcdmc":"教2-208(揭)","teadms":"00003199","zxs":"32","xs":"2"}],
         [{"xqmc":"1","rq":"2026-09-28"},{"xqmc":"2","rq":"2026-09-29"},{"xqmc":"3","rq":"2026-09-30"},{"xqmc":"4","rq":"2026-10-01"},{"xqmc":"5","rq":"2026-10-02"},{"xqmc":"6","rq":"2026-10-03"},{"xqmc":"7","rq":"2026-10-04"}]]
    """.trimIndent()

    @Test
    fun parsesWeekCoursesWithTheWeeklyFieldNames() {
        val courses = GdutScheduleParser.parseWeekCourses(JSONArray(weekJson))

        assertEquals(2, courses.size)
        assertEquals("设计造型", courses[0].name)
        assertEquals("教师仍然是 teaxms", "林老师", courses[0].teacher)
        assertEquals("周课表的场地字段是 jxcdmc（少一个 s）", "创新212", courses[0].room)
        assertEquals(3, courses[0].weekday)
        assertEquals(1, courses[0].startPeriod)
        assertEquals(4, courses[0].periodCount)
        assertEquals("单周课表的周次展示成「第 n 周」", "5周", courses[0].weeks)

        assertEquals("教2-208(揭)", courses[1].room)
        assertEquals(2, courses[1].weekday)
    }

    @Test
    fun parsesWeekDatesAndKeepsThemUnordered() {
        val dates = GdutScheduleParser.parseWeekDates(JSONArray(weekJson))

        assertEquals(7, dates.size)
        assertEquals(1 to "2026-09-28", dates[0])
        assertEquals(7 to "2026-10-04", dates[6])
        // 周一 → 第 5 周的第一天（实测第 5 周的周一是 09-28）
        assertEquals(
            java.time.LocalDate.of(2026, 9, 28),
            GdutScheduleParser.parseFirstMonday(dates),
        )
    }

    @Test
    fun emptyOrUnexpectedResponsesDoNotExplode() {
        // 接口改版返回 `{}` / 空数组时：宁可给空课表（这一周没课），也不要崩
        assertEquals(0, GdutScheduleParser.parseWeekCourses(JSONArray("[]")).size)
        assertEquals(0, GdutScheduleParser.parseWeekDates(JSONArray("[]")).size)
        assertEquals(0, GdutScheduleParser.parseWeekCourses(JSONArray("[[]]")).size)
    }
}
