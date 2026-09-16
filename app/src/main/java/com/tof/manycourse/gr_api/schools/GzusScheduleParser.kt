package com.tof.manycourse.gr_api.schools

import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.data.StudentProfile
import com.tof.manycourse.gr_api.htmlSelectOptions
import com.tof.manycourse.gr_api.htmlSelectedOption
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

/**
 * 广州软件学院正方教务系统**课表接口的解析**。
 *
 * 接口返回 JSON（不是 HTML），来自实测抓包：
 *
 * ```
 * POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151
 * body: xnm=2026&xqm=3&kzlx=ck&xsdm=&kclbdm=&kclxdm=
 * ```
 *
 * 响应里两块要的东西：
 *  - `xsxx`：学生信息（**姓名**、学号、班级、专业）—— 所以不用另调学生信息接口；
 *  - `kbList`：课程数组（一学期 30 条左右）。
 *
 * ## 为什么拆成两层
 *
 * `org.json` 在 JVM 单元测试里是**未实现的 stub**（一调用就抛
 * "not mocked"），所以"JSON → 模型"这段只能在真机上测。
 * 于是这样分层：
 *
 *  - **[toCourse] / [toProfile] 只吃字符串**：节次拆分、地点拼接、
 *    星期校验这些**真正容易写错**的逻辑全在这里，可以在 JVM 单测里穷举（见 `GzusScheduleParserTest`）；
 *  - **`JSONObject` → 字符串** 的取字段部分留在 [parseSchedule] / [parseProfile]，
 *    由真机测试 `GzusScheduleJsonTest` 覆盖。
 *
 * ## 几个容易写错、错了又看不出来的字段
 *
 * | 字段 | 含义 | 坑 |
 * |---|---|---|
 * | `kcmc` | 课程名 | |
 * | `xm` | **教师姓名** | |
 * | `zcmc` | **教师职称**（"讲师（高校）"）| ⚠️ 名字看着像"周次名称"，其实**是职称**，既不是教师也不是周次 |
 * | `zcd` | 周次（"4-19周"）| 周次的真名是 `zcd` |
 * | `cdmc` / `lh` | 教室 / 教学楼 | 要拼起来才是完整地点（"笃行楼U" + "U204"）|
 * | `xqj` | 星期几（1-7）| 服务端直接给数字；`xqjmc` 才是"星期一" |
 * | `jcs` | 节次（"1-2"）| 要拆成 startPeriod + periodCount |
 * | `xqmc` | 校区（"广州校区"）| |
 */
internal object GzusScheduleParser {

    // ── 课表页（拿默认学年学期）──────────────────────────────────────────────

    /**
     * 从课表查询页的 HTML 里读**默认选中的学年/学期**。
     *
     * 为什么不在客户端算"现在是第几学期"：`xqm` 的取值是学校自己定的
     * （实测这台服务器：`3` = 第 1 学期、`12` = 第 2 学期；`xnm` 是 `2026`，
     * 页面上显示成 `2026-2027`），而且开学/放假时间各校不同 ——
     * 直接用页面选中的那个最靠谱。
     *
     * @return `(xnm, xqm)`，如 `("2026", "3")`；读不到返回 null
     */
    fun parseTerm(pageHtml: String): Pair<String, String>? {
        val xnm = pageHtml.htmlSelectedOption("xnm")?.takeIf { it.isNotBlank() } ?: return null
        val xqm = pageHtml.htmlSelectedOption("xqm")?.takeIf { it.isNotBlank() } ?: return null
        return xnm to xqm
    }

    // ── 周次课表页（教学周列表）──────────────────────────────────────────────

    /**
     * 从「周次课表」页（`xskbcxZccx_cxXskbcxIndex.html`）读**全部教学周**。
     *
     * 页面里那个 `#zs` 下拉框长这样（实测原文）：
     *
     * ```html
     * <select name="zs" id="zs">
     *   <option value="1">1(2026-08-31至2026-09-06)</option>
     *   <option value="3" selected="selected">3(2026-09-14至2026-09-20)</option>
     *   ...
     * </select>
     * ```
     *
     * **带 `selected` 的那一项就是"当前教学周"** —— 服务端已经替我们算好了，
     * 不要在客户端按开学日期推。
     *
     * @return 按周次升序；读不到（页面改版）返回空表，调用方据此决定要不要报错
     */
    fun parseWeeks(pageHtml: String): List<SchoolWeek> =
        pageHtml.htmlSelectOptions("zs")
            .mapNotNull { (value, label) -> toWeek(value, label) }
            .sortedBy { it.index }

    /**
     * `"3"` + `"3(2026-09-14至2026-09-20)"` → [SchoolWeek]。
     *
     * 只认标签里的两个 `yyyy-MM-dd`，**不依赖"第几周"那几个字符怎么写** ——
     * 括号里那句文案各校各版本都改过（有的写"至"、有的写"-"），
     * 但日期格式是稳定的。周次以 `value` 为准（`value` 才是发给服务端的东西）。
     *
     * 解析不了返回 null（调用方跳过这一条，而不是整页失败）。
     */
    fun toWeek(value: String, label: String): SchoolWeek? {
        val index = value.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        val dates = WEEK_DATE_REGEX.findAll(label).take(2).map { it.value }.toList()
        if (dates.size < 2) return null
        val start = runCatching { LocalDate.parse(dates[0]) }.getOrNull() ?: return null
        val end = runCatching { LocalDate.parse(dates[1]) }.getOrNull() ?: return null
        // 起止写反了也认（服务端出过这种数据），按小的当 start
        return if (end.isBefore(start)) SchoolWeek(index, end, start) else SchoolWeek(index, start, end)
    }

    private val WEEK_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

    // ── 纯逻辑层（JVM 可测）─────────────────────────────────────────────────

    /**
     * 一条课表记录 → [SchoolCourse]。**所有输入都是字符串**，与教务系统字段一一对应。
     *
     * @param courseName `kcmc`
     * @param teacher    `xm`（★ 不是 `zcmc`，那是职称）
     * @param building   `lh`（"笃行楼U"）
     * @param room       `cdmc`（"U204"）
     * @param campus     `xqmc`（"广州校区"）
     * @param weekday    `xqj`（"1"…"7"）
     * @param periods    `jcs`（"1-2" / "3"）
     * @param weeks      `zcd`（"4-19周"）
     * @return 数据不完整（没有课名 / 星期不合法 / 节次解析不了）时返回 null，由调用方跳过
     */
    fun toCourse(
        courseName: String,
        teacher: String,
        building: String,
        room: String,
        campus: String,
        weekday: String,
        periods: String,
        weeks: String,
    ): SchoolCourse? {
        val name = courseName.trim()
        if (name.isEmpty()) return null

        val day = weekday.trim().toIntOrNull()?.takeIf { it in 1..7 } ?: return null
        val period = parsePeriods(periods) ?: return null

        return SchoolCourse(
            name = name,
            teacher = teacher.trim(),
            // 教学楼 + 教室拼成完整地点；两者可能任一为空
            room = listOf(building.trim(), room.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" "),
            campus = campus.trim(),
            weekday = day,
            startPeriod = period.first,
            periodCount = period.second,
            weeks = weeks.trim(),
        )
    }

    /** `"1-2"` / `"1-2节"` / `"3"` → `(开始节次, 连上几节)`；解析不了返回 null */
    fun parsePeriods(raw: String): Pair<Int, Int>? {
        val numbers = Regex("""\d+""").findAll(raw).map { it.value.toInt() }.toList()
        val start = numbers.firstOrNull() ?: return null
        val end = numbers.getOrNull(1) ?: start
        return start to (end - start + 1).coerceAtLeast(1)
    }

    /** 学生信息（姓名就在这里）*/
    fun toProfile(
        name: String,
        account: String,
        className: String,
        major: String,
        schoolYear: String,
    ): StudentProfile? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return null
        return StudentProfile(
            name = trimmedName,
            account = account.trim(),
            className = className.trim(),
            major = major.trim(),
            college = schoolYear.trim(),
        )
    }

    // ── JSON 适配层（真机测试覆盖）───────────────────────────────────────────

    /**
     * 解析 `kbList` → 课程列表。
     *
     * 容错策略：**单条坏数据只跳过那一条** —— 教务系统偶发出现缺 `jcs`、
     * 缺 `xqj` 的记录，不该因为一条脏数据让整张课表拉不出来。
     */
    fun parseSchedule(response: JSONObject): List<SchoolCourse> {
        val list = response.optJSONArray("kbList")
            ?: throw IOException("课表接口没返回 kbList（登录可能已过期）")

        val courses = mutableListOf<SchoolCourse>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val course = toCourse(
                courseName = item.optString("kcmc"),
                // ★ 教师是 xm；zcmc 是职称（"讲师（高校）"），别搞混
                teacher = item.optString("xm"),
                building = item.optString("lh"),
                room = item.optString("cdmc"),
                campus = item.optString("xqmc"),
                weekday = item.optString("xqj"),
                // jcs 是 "1-2"；个别版本只给 jc（"1-2节"），兜底一下
                periods = item.optString("jcs").ifBlank { item.optString("jc") },
                weeks = item.optString("zcd"),
            ) ?: continue
            courses += course
        }
        return courses
    }

    /** 从响应的 `xsxx` 里取学生信息 */
    fun parseProfile(response: JSONObject, fallbackAccount: String): StudentProfile {
        val xsxx = response.optJSONObject("xsxx")
            ?: throw IOException("课表接口没返回 xsxx（学生信息），登录可能已过期")
        return toProfile(
            name = xsxx.optString("XM"),
            account = xsxx.optString("XH").ifBlank { fallbackAccount },
            className = xsxx.optString("BJMC"),
            major = xsxx.optString("ZYMC"),
            schoolYear = xsxx.optString("XNMC"),
        ) ?: throw IOException("课表接口的 xsxx 里没有姓名（XM）")
    }
}
