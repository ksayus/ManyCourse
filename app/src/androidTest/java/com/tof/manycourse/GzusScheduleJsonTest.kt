package com.tof.manycourse

import com.tof.manycourse.gr_api.schools.GzusScheduleParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广软课表接口的 **JSON 取字段**层（真机 / 模拟器测试）。
 *
 * 为什么放在 androidTest 而不是 JVM 单测：`org.json` 在单元测试里是
 * 未实现的 stub（一调用就抛 "not mocked"），而为了测这一层专门重写一套 JSON 解析
 * 并不划算。放到设备上跑，用的就是 App 真正的实现。
 *
 * 这一层要守的是"**哪个 JSON 字段对应哪个参数**"——
 * 尤其是 `xm`(教师) 与 `zcmc`(职称) 这一对：写反了课表里教师列会变成"讲师（高校）"，
 * 而且不报任何错。
 *
 * JSON 结构照抄实测抓包（值全部换成合成数据）。
 */
class GzusScheduleJsonTest {

    private val kbJson = """
        {
          "xsxx": {
            "XM": "张同学",
            "XH": "0222010102",
            "BJMC": "2026级数字媒体技术1班",
            "ZYMC": "数字媒体技术",
            "XNMC": "2026-2027"
          },
          "kbList": [
            {
              "kcmc": "大学英语I（综合基础）",
              "xm": "邓念利",
              "zcmc": "讲师（高校）",
              "lh": "笃行楼U",
              "cdmc": "U204",
              "xqmc": "广州校区",
              "xqj": "1",
              "xqjmc": "星期一",
              "jcs": "1-2",
              "jc": "1-2节",
              "zcd": "4-19周"
            },
            {
              "kcmc": "C程序设计基础",
              "xm": "张鹏",
              "lh": "厚德楼G",
              "cdmc": "G202",
              "xqmc": "广州校区",
              "xqj": "3",
              "jcs": "7-8",
              "zcd": "10-18周"
            },
            { "kcmc": "坏数据：缺节次", "xm": "某人", "xqj": "2" }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesScheduleFromTheRealJsonShape() {
        val courses = GzusScheduleParser.parseSchedule(JSONObject(kbJson))

        assertEquals("坏数据那条应被跳过", 2, courses.size)

        val english = courses[0]
        assertEquals("大学英语I（综合基础）", english.name)
        assertEquals("教师必须来自 xm，不是 zcmc", "邓念利", english.teacher)
        assertEquals("笃行楼U U204", english.room)
        assertEquals("广州校区", english.campus)
        assertEquals(1, english.weekday)
        assertEquals(1, english.startPeriod)
        assertEquals(2, english.periodCount)
        assertEquals("4-19周", english.weeks)

        assertEquals(3, courses[1].weekday)
        assertEquals(7, courses[1].startPeriod)
    }

    @Test
    fun parsesProfileFromXsxx() {
        val profile = GzusScheduleParser.parseProfile(JSONObject(kbJson), fallbackAccount = "fallback")

        assertEquals("张同学", profile.name)
        assertEquals("0222010102", profile.account)
        assertEquals("2026级数字媒体技术1班", profile.className)
        assertEquals("数字媒体技术", profile.major)
    }

    @Test
    fun missingKbListFailsLoudly() {
        // 没有 kbList = 没登录 / 接口改版，必须抛错让上层报"登录已过期"，
        // 而不是静默返回空课表（那会被读成"这学期没课"）
        val error = runCatching { GzusScheduleParser.parseSchedule(JSONObject("""{"xsxx":{}}""")) }
        assertTrue("应当抛异常", error.isFailure)
        assertTrue(error.exceptionOrNull()?.message.orEmpty().contains("kbList"))
    }

    @Test
    fun missingXsxxFailsLoudly() {
        val error = runCatching { GzusScheduleParser.parseProfile(JSONObject("""{"kbList":[]}"""), "x") }
        assertTrue(error.isFailure)
        assertTrue(error.exceptionOrNull()?.message.orEmpty().contains("xsxx"))
    }

    /**
     * 「周次课表」接口（`xskbcxMobile_cxXsKb.html?gnmkdm=N2154`）的响应。
     *
     * 它和个人课表接口**长得像但不是一个接口**：`kbList` 里只有**这一周真的会上**的课，
     * 而且 `zcd` 这一栏变成单个周次（`"3周"` / `"2-3周"`）而不是区间（`"4-19周"`）。
     * 这条测试钉住的就是"换个接口也不用改解析器"——
     * 一旦哪天两个接口的字段名分叉了，这里会立刻红。
     *
     * JSON 取自实测响应（2026-09-16，第 3 周；姓名已换成合成值）。
     */
    @Test
    fun parsesTheWeekScheduleResponseWithTheSameParser() {
        val weekJson = """
            {
              "zs": "3",
              "qsxqj": "1",
              "xsxx": {
                "XM": "张同学",
                "XH": "0222010102",
                "BJMC": "2026级数字媒体技术1班",
                "ZYMC": "数字媒体技术(游戏开发卓越工程师班）",
                "XNMC": "2026-2027"
              },
              "rqazcList": [
                { "xqj": 1, "xqjmc": "星期一", "rq": "2026-09-14" },
                { "xqj": 3, "xqjmc": "星期三", "rq": "2026-09-16" }
              ],
              "kbList": [
                {
                  "kcmc": "军事理论",
                  "xm": "鲁鲜亮",
                  "zcmc": "助教（高校）",
                  "lh": "笃行楼T",
                  "cdmc": "T301",
                  "xqmc": "广州校区",
                  "xqj": "1",
                  "xqjmc": "星期一",
                  "jcs": "3-4",
                  "jc": "3-4节",
                  "zcd": "3周"
                },
                {
                  "kcmc": "军事技能",
                  "xm": "庞露荷",
                  "lh": "",
                  "cdmc": "",
                  "xqmc": "广州校区",
                  "xqj": "5",
                  "jcs": "3-4",
                  "zcd": "2-3周"
                }
              ]
            }
        """.trimIndent()

        val courses = GzusScheduleParser.parseSchedule(JSONObject(weekJson))

        assertEquals(2, courses.size)
        assertEquals("军事理论", courses[0].name)
        assertEquals("教师仍然取 xm", "鲁鲜亮", courses[0].teacher)
        assertEquals("笃行楼T T301", courses[0].room)
        assertEquals(1, courses[0].weekday)
        assertEquals(3, courses[0].startPeriod)
        assertEquals("周次是单个周次，不是区间", "3周", courses[0].weeks)
        // 没教室没楼栋的课（军训）也要能进来，不能因为地点空就被丢掉
        assertEquals("", courses[1].room)
        assertEquals("2-3周", courses[1].weeks)
    }
}
