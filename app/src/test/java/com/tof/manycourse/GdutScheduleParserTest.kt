package com.tof.manycourse

import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.gr_api.schools.GdutScheduleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 广东工业大学教务系统页面解析的回归测试（JVM，无需设备、不联网）。
 *
 * 片段全部**照抄实测抓包原文**（2026-09-16 那次登录 + 周次查询），只把姓名、
 * 学号、课名这些个人信息换成合成值。要守的是那几条
 * "写错了不报错、只表现为密码错误或者这学期没课"的地方：
 *
 *  - 盐的隐藏域 id（广工是 `pwdEncryptSalt`，新版金智是 `pwdDefaultEncryptSalt`）；
 *  - `execution` 必须能原样取出来（6000 个字符，一个都不能丢）；
 *  - 失败文案要从 `showErrorTip` 里取（登录失败也是 HTTP 200）；
 *  - `zcs` 是**乱序**的周次串，展示前必须归一化；
 *  - `jcdm2` 是两位补零的逗号串（`"01,02"`），不是正方的 `"1-2"`；
 *  - 欢迎页里 `class="top"` 出现了两次，别把"登录时间"当成姓名。
 */
class GdutScheduleParserTest {

    // ── ① 统一认证登录页的三个隐藏域 ───────────────────────────────────────

    /** 广工这版：`pwdEncryptSalt` + `execution` + `lt`，与抓包原文同形 */
    private val casLoginPage = """
        <form class="loginFromClass" method="post" id="pwdFromId" action="/authserver/login">
          <input type="hidden" id="_eventId" name="_eventId" value="submit" />
          <input type="hidden" id="cllt" name="cllt" value="userNameLogin">
          <input type="hidden" id="dllt" name="dllt" value="generalLogin">
          <input type="hidden" name="lt" id="lt" value="" />
          <input type="hidden" id="pwdEncryptSalt" value="ohANYJcqxqvvFJ31" />
          <input type="hidden" id="execution" name="execution" value="9fe6d1df-4f97-4687-a272-860a29dae821_ZXlKaGJHY2lPaUpJVXpVeE1pSjku" />
          <input type="text" id="username" name="username" value="">
          <input type="password" id="password" name="passwordText" value="">
          <input type="hidden" id="saltPassword" name="password" value="">
          <input type="text" id="captcha" name="captcha" value="">
          <input type="checkbox" id="rememberMe" name="rememberMe" value="true" />
        </form>
    """.trimIndent()

    @Test
    fun readsSaltExecutionAndLtFromTheLoginPage() {
        val form = GdutScheduleParser.parseCasLoginForm(casLoginPage)!!
        assertEquals("盐取错了，密码必然算错（表现为'用户名或密码错误'）", "ohANYJcqxqvvFJ31", form.salt)
        assertEquals(
            "execution 是服务端签发的防重放票据，必须原样回传",
            "9fe6d1df-4f97-4687-a272-860a29dae821_ZXlKaGJHY2lPaUpJVXpVeE1pSjku",
            form.execution,
        )
        assertEquals("lt 是空串，但字段本身必须带着（取到空串 ≠ 取不到）", "", form.lt)
    }

    @Test
    fun acceptsTheThreeSaltSpellingsInTheWild() {
        // 新版金智：pwdDefaultEncryptSalt
        assertEquals(
            "abcdefghijklmnop",
            GdutScheduleParser.parseCasLoginForm(
                """<input type="hidden" id="pwdDefaultEncryptSalt" value="abcdefghijklmnop">"""
            )!!.salt,
        )
        // 更老的版本：直接写在 <script> 里
        assertEquals(
            "ZzYyXxWwVvUuTtSs",
            GdutScheduleParser.parseCasLoginForm(
                """<script>var pwdDefaultEncryptSalt = "ZzYyXxWwVvUuTtSs";</script>"""
            )!!.salt,
        )
        // 属性顺序/引号换了也要认（教务系统的页面天天改这些）
        assertEquals(
            "ohANYJcqxqvvFJ31",
            GdutScheduleParser.parseCasLoginForm(
                """<input value='ohANYJcqxqvvFJ31' id='pwdEncryptSalt'>"""
            )!!.salt,
        )
    }

    @Test
    fun missingSaltIsNullNotBlank() {
        // 盐取不到就必须是 null：拿空串去加密 → base64 出来的东西服务端解不开 →
        // 回一句"用户名或密码错误"，用户和排查者一起被带沟里
        assertNull(GdutScheduleParser.parseCasLoginForm("<html><body>没有登录表单</body></html>"))
        assertNull(GdutScheduleParser.parseCasLoginForm("""<input id="pwdEncryptSalt" value="">"""))
    }

    // ── ①′ 防盗链拦截页（**实测踩过**）────────────────────────────────────

    /** 缺 Referer 时 jxfw 回的原文（HTTP 200、272 字节，真机上抓到的就是这个）*/
    private val accessDeniedPage = """
        <!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
        <html>
        <head>
            <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
            <title>非法访问</title>
        </head>
        <body>你没有该权限

        </body>
        </html>
    """.trimIndent()

    @Test
    fun recognizesTheAntiHotlinkBlockPage() {
        assertTrue(
            "缺 Referer 时服务端回的是「非法访问/你没有该权限」，必须能认出来 —— " +
                "否则它会伪装成「课表页结构变了：读不到当前学年学期」，把人往解析器那边带",
            GdutScheduleParser.isAccessDenied(accessDeniedPage),
        )
        // 正常页面不能被误判
        assertFalse(GdutScheduleParser.isAccessDenied(coursePage))
        assertFalse(GdutScheduleParser.isAccessDenied(semesterPage))
        assertFalse(GdutScheduleParser.isAccessDenied(welcomePage))
    }

    @Test
    fun theBlockedPageIsNotMistakenForAChangedPage() {
        // 这正是当初的现象：被防盗链挡住 → 学期下拉框读不到 → 报"页面改版"。
        // 现在两者必须能分开：解析器返回 null（事实），调用方用 isAccessDenied 给出准确原因
        assertNull(GdutScheduleParser.parseCurrentTerm(accessDeniedPage))
        assertTrue(GdutScheduleParser.isAccessDenied(accessDeniedPage))
    }

    // ── ② 登录失败文案 ─────────────────────────────────────────────────────

    @Test
    fun readsTheFailureMessageFromShowErrorTip() {
        // 实测：登录失败时服务端返回 **HTTP 200 + 登录页**，原因写在这里
        val html = """
            <div class="lang_text_ellipsis" style="width:100%;color: red;margin-top: 10px;">
              <span id="showErrorTip"><span>用户名或密码错误</span></span>
            </div>
        """.trimIndent()
        assertEquals("用户名或密码错误", GdutScheduleParser.parseCasError(html))
    }

    @Test
    fun emptyOrAbsentErrorTipIsNull() {
        assertNull(GdutScheduleParser.parseCasError("""<span id="showErrorTip"></span>"""))
        assertNull(GdutScheduleParser.parseCasError("<html><body>干净的登录页</body></html>"))
    }

    @Test
    fun ignoresTheErrorTipMentionedInsideTheLoginScript() {
        // 页面里的 JS 也写着 `$("#showErrorTip")`，从那儿往后截会截到一大段脚本 ——
        // 必须只认"真正的那个 span"
        val html = """
            <script>utils.cleanRequire($(".loginFromClass"), ${'$'}("#showErrorTip"));</script>
            <span id="showErrorTip"><span>验证码错误</span></span>
        """.trimIndent()
        assertEquals("验证码错误", GdutScheduleParser.parseCasError(html))
    }

    // ── ③ 学年学期 / 课表是否已发布 ────────────────────────────────────────

    /** 照抄 `xsgrkbcx!getXsgrbkList.action` 的原文档位（只留前后两个学期）*/
    private val coursePage = """
        <select id='xnxqdm' name='xnxqdm' style='width:120px' class='ntssselect' >
          <option value='202602' >2027春季</option><option value='202601' selected>2026秋季</option>
          <option value='202502' >2026春季</option>
        </select>
        <script>
          if('' == 'false'){
            ${'$'}.messager.alert('信息','本学期课表还未发布，请稍后关注!','warning');
          }
        </script>
    """.trimIndent()

    @Test
    fun readsTheSelectedTermInsteadOfGuessing() {
        assertEquals("202601", GdutScheduleParser.parseCurrentTerm(coursePage))
        assertNull("没有 selected 时不要瞎猜，交给上层报错", GdutScheduleParser.parseCurrentTerm("<select id='xnxqdm'><option value='202601'>x</option></select>"))
    }

    @Test
    fun schedulePublishedFlagIsTriState() {
        // 页面把服务端的布尔值渲染进 JS：空串 = 没给结论（不能当成"没发布"）
        assertNull("空串表示页面没给结论", GdutScheduleParser.parseSchedulePublished(coursePage))
        assertFalse(
            "注入值是 false = 课表确实还没发布",
            GdutScheduleParser.parseSchedulePublished("if('false' == 'false'){}")!!,
        )
        assertTrue(
            "注入值是别的 = 已发布",
            GdutScheduleParser.parseSchedulePublished("if('true' == 'false'){}")!!,
        )
        assertNull("页面上没这句话就表示无从判断", GdutScheduleParser.parseSchedulePublished("<html></html>"))
    }

    // ── ④ 整学期课表 ───────────────────────────────────────────────────────

    /** 照抄 `xsAllKbList.action` 的 `var kbxx`（真实字段、合成值）*/
    private val semesterPage = """
        <script type="text/javascript">
        ${'$'}(document).ready(function(){
          var kbxx = [{"kcmc":"大学英语(1)","kcbh":"TMP0928","jxbmc":"产品设计26(3)","kcrwdm":"1363577","jcdm2":"01,02","zcs":"16,7,8,9,10,11,17,18,19,12,13,14,15","xq":"2","jxcdmcs":"公实405","teaxms":"刘萱"},{"kcmc":"军事理论","kcbh":"TMP3230","jxbmc":"产品设计26(3)","kcrwdm":"1363585","jcdm2":"06,07","zcs":"16,9,10,17,11,12,13,14,15","xq":"3","jxcdmcs":"教1-106(揭)","teaxms":"李强"}];
          ${'$'}.each(kbxx,function(index,item){ });
        });
        </script>
    """.trimIndent()

    @Test
    fun extractsTheKbxxArrayFromThePage() {
        val json = GdutScheduleParser.extractKbxx(semesterPage)!!
        assertTrue("必须正好是那个数组（末尾的分号不属于它）", json.startsWith("[{") && json.endsWith("}]"))
        assertTrue(json.contains("公实405"))
        assertNull("页面里没有 kbxx 就是没有（上层据此报错）", GdutScheduleParser.extractKbxx("<html>登录页</html>"))
    }

    @Test
    fun toCourseMapsTheRealFields() {
        val course = GdutScheduleParser.toCourse(
            courseName = "大学英语(1)",
            teacher = "刘萱",
            room = "公实405",
            weekday = "2",
            periodCodes = "01,02",
            weeks = "16,7,8,9,10,11,17,18,19,12,13,14,15",
        )!!
        assertEquals("大学英语(1)", course.name)
        assertEquals("教师取 teaxms", "刘萱", course.teacher)
        assertEquals("公实405", course.room)
        assertEquals("校区不拆：场地名里本来就带括号（教1-106(揭)）", "", course.campus)
        assertEquals(2, course.weekday)
        assertEquals(1, course.startPeriod)
        assertEquals(2, course.periodCount)
        assertEquals("乱序周次必须归一化", "7-19周", course.weeks)
        assertEquals("第1-2节", course.periodLabel)
    }

    @Test
    fun badRecordsAreSkippedNotFatal() {
        // 教务系统偶发出现缺星期 / 缺节次的记录，不该让整张课表拉不出来
        assertNull("没有课名", GdutScheduleParser.toCourse("", "师", "室", "1", "01", "1"))
        assertNull("星期不在 1..7", GdutScheduleParser.toCourse("课", "师", "室", "9", "01", "1"))
        assertNull("节次解析不出来", GdutScheduleParser.toCourse("课", "师", "室", "1", "", "1"))
    }

    @Test
    fun periodCodesAreZeroPaddedCommaLists() {
        assertEquals(1 to 4, GdutScheduleParser.parsePeriodCodes("01,02,03,04"))
        assertEquals(6 to 2, GdutScheduleParser.parsePeriodCodes("06,07"))
        assertEquals("跳号也算一整个连续段（课间是被占住的）", 1 to 5, GdutScheduleParser.parsePeriodCodes("01,02,05"))
        assertEquals(3 to 1, GdutScheduleParser.parsePeriodCodes("3"))
        assertNull(GdutScheduleParser.parsePeriodCodes(""))
        assertNull(GdutScheduleParser.parsePeriodCodes("上午"))
    }

    @Test
    fun weeksAreSortedDeduplicatedAndCompressed() {
        assertEquals("7-17周", GdutScheduleParser.normalizeWeeks("16,7,8,9,10,11,17,12,13,14,15"))
        assertEquals("7-9周", GdutScheduleParser.normalizeWeeks("9,7,8"))
        assertEquals("6周", GdutScheduleParser.normalizeWeeks("6"))
        assertEquals("1-3,7-8周", GdutScheduleParser.normalizeWeeks("7,8,3,1,2,3"))
        assertEquals("", GdutScheduleParser.normalizeWeeks(""))
        assertEquals(
            "本来就是区间也要认（换成别家接口时不用改这里）",
            "4-19周",
            GdutScheduleParser.normalizeWeeks("4-19周"),
        )
        assertEquals("1-4,7周", GdutScheduleParser.normalizeWeeks("1至4,7"))
        assertEquals(
            "解析不出周次时**原样返回**：宁可难看，也别把服务端的话弄丢",
            "全周",
            GdutScheduleParser.normalizeWeeks("全周"),
        )
        assertTrue(
            "超出 1..30 的数字不算周次（脏数据不该把周次表撑爆）",
            GdutScheduleParser.parseWeekNumbers("20260914").isEmpty(),
        )
        assertEquals(
            "但整串都解析不出来时仍然原样返回（上层照样能展示）",
            "20260914",
            GdutScheduleParser.normalizeWeeks("20260914"),
        )
    }

    @Test
    fun maxWeekDrivesTheTeachingWeekCount() {
        assertEquals(
            "周数取数据里的最大周次，不是写死的 22",
            19,
            GdutScheduleParser.maxWeekOf(listOf("16,7,8,9,10,11,17,18,19,12,13,14,15", "6")),
        )
        assertEquals("一条课都没有 → 0（调用方据此退回默认 22 周）", 0, GdutScheduleParser.maxWeekOf(emptyList()))
        assertEquals(0, GdutScheduleParser.maxWeekOf(listOf("", "全周")))
    }

    // ── ⑤ 某一周 ───────────────────────────────────────────────────────────

    @Test
    fun findsTheMondayOfTheFirstWeek() {
        // 实测 `getKbRq&xnxqdm=202601&zc=3` 的第二个数组（顺序是乱的）
        val dates = listOf(
            4 to "2026-09-17", 5 to "2026-09-18", 6 to "2026-09-19", 7 to "2026-09-20",
            2 to "2026-09-15", 3 to "2026-09-16", 1 to "2026-09-14",
        )
        assertEquals(LocalDate.of(2026, 9, 14), GdutScheduleParser.parseFirstMonday(dates))
    }

    @Test
    fun fallsBackWhenTheServerDoesNotSendMonday() {
        // 服务端漏了星期一时按"最早那天 + 星期几"往回推
        val dates = listOf(4 to "2026-09-17", 3 to "2026-09-16")
        assertEquals(LocalDate.of(2026, 9, 14), GdutScheduleParser.parseFirstMonday(dates))
        assertNull(GdutScheduleParser.parseFirstMonday(emptyList()))
        assertNull("日期不是 yyyy-MM-dd 就当没有", GdutScheduleParser.parseFirstMonday(listOf(1 to "9月14日")))
    }

    @Test
    fun buildsContiguousTeachingWeeks() {
        val weeks = GdutScheduleParser.buildWeeks(LocalDate.of(2026, 9, 14), 5)
        assertEquals(5, weeks.size)
        assertEquals(1, weeks[0].index)
        assertEquals(LocalDate.of(2026, 9, 14), weeks[0].start)
        assertEquals(LocalDate.of(2026, 9, 20), weeks[0].end)
        // 实测第 3/5 周的周一是 09-28 / 10-12 —— 正好每 7 天一个，所以推导成立
        assertEquals(LocalDate.of(2026, 9, 28), weeks[2].start)
        assertEquals(LocalDate.of(2026, 10, 12), weeks[4].start)
        assertTrue(
            "周次 0 或负数 → 空表（别凭空造出第 0 周）",
            GdutScheduleParser.buildWeeks(LocalDate.now(), 0).isEmpty(),
        )
    }

    @Test
    fun weekLabelIsASingleWeek() {
        assertEquals("3周", GdutScheduleParser.weekLabel("3"))
        assertEquals("", GdutScheduleParser.weekLabel(" "))
    }

    // ── ⑥ 欢迎页里的姓名 ───────────────────────────────────────────────────

    /** 照抄 `login!welcome.action` 的顶部（姓名已换成合成值）*/
    private val welcomePage = """
        <body class="easyui-layout" id='mainlayout' data-options="fit:true">
          <div data-options="region:'north',border:false" style="height:60px;padding:0px;" >
            <div id="header">
              <img id="logo" src="/styles/images/customize/GDUT_logo_school2.gif" >
              <div class="infoDiv">
                <div class="top"> 张同学 </div>
                <div class="bottom" >
                  <a href="javascript:void(0)" onclick="changepw(true)">修改密码</a> |
                  <a onclick="lock()" href="javascript:void(0)">锁定</a> |
                  <a href="/new/logout">注销</a>
                </div>
              </div>
              <div id="topbg" >
                <div class="top"> 登录时间：2026-09-17 00:12:33 </div>
                <div id="m1_container"> </div>
              </div>
              <div class="clearfix"></div>
            </div>
          </div>
        </body>
    """.trimIndent()

    @Test
    fun readsTheStudentNameFromTheHeader() {
        assertEquals(
            "姓名在 .infoDiv 里那个 class=top；下面 id=topbg 里还有一个同名 class（登录时间），别取错",
            "张同学",
            GdutScheduleParser.parseStudentName(welcomePage),
        )
    }

    @Test
    fun doesNotMistakeTheLoginTimeForTheName() {
        // 只留 topbg 那一段（没有 infoDiv）：不能把"登录时间：…"当成姓名
        val onlyTime = """<div id="topbg"><div class="top"> 登录时间：2026-09-17 00:12:33 </div></div>"""
        assertNull(GdutScheduleParser.parseStudentName(onlyTime))
        assertNull(GdutScheduleParser.parseStudentName("<html><body>登录页</body></html>"))
    }

    // ── ⑦ 与 UI 口径的一致性 ───────────────────────────────────────────────

    @Test
    fun coursesKeepTheSameShapeAsOtherSchools() {
        // SchoolCourse 的口径：weekday 1=周一…7=周日，startPeriod 从第 1 节起
        val course: SchoolCourse = GdutScheduleParser.toCourse(
            courseName = "综合设计基础(1)", teacher = "王平胜", room = "创新212",
            weekday = "2", periodCodes = "05,06,07,08", weeks = "12,13,14,15,16,17",
        )!!
        assertEquals("第5-8节", course.periodLabel)
        assertEquals("12,13,14,15,16,17 连成一段", "12-17周", course.weeks)
        assertTrue("星期必须是 1..7 的数字", course.weekday in 1..7)
    }
}
