package com.tof.manycourse.gr_api.schools

import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.data.StudentProfile
import com.tof.manycourse.gr_api.htmlElementText
import com.tof.manycourse.gr_api.htmlLines
import com.tof.manycourse.gr_api.htmlTable
import com.tof.manycourse.gr_api.htmlTableRows
import com.tof.manycourse.gr_api.nbspFields
import com.tof.manycourse.gr_api.stripTags
import java.io.IOException

/**
 * 金城学院教务系统页面的解析逻辑。
 *
 * 单独拆出来（而不是塞在 [NhjcxyApi] 里）只有一个原因：**能被单测直接喂 HTML**。
 * 这两个解析器是全流程里最容易随对方改版而静默失效的部分 ——
 * 失效时的表现是"课表空了 / 姓名没同步上"，没有任何异常，光靠肉眼很难发现，
 * 所以 `NhjcPageParserTest` 用**真实抓下来的页面结构**把它们钉住了。
 */
internal object NhjcPageParser {

    /**
     * 解析学籍页（`/EaWeb/Manager/Module/NetEa/SchoolRoll/Student/Show/Default.aspx`）。
     *
     * 页面里同一份数据有两套 span：顶部摘要条带 `1` 后缀（`LabXm1`），
     * 正文表单不带（`LabXm`）。内容一致，优先取正文那套。
     * 注意 `id="LabXm"` 的匹配带闭引号，不会误命中 `LabXm1`。
     *
     * @param fallbackName 页面里取不到姓名时的兜底（调用方传老系统下发的 `U_NameCn` Cookie）
     */
    fun parseProfile(html: String, fallbackName: String?, fallbackAccount: String): StudentProfile {
        val name = html.firstElementText("LabXm", "LabXm1")
            ?: fallbackName?.takeIf { it.isNotBlank() }
            ?: throw IOException("学籍页里没找到姓名（LabXm），登录可能已过期")

        return StudentProfile(
            name = name,
            account = html.firstElementText("LabXh", "LabXh1") ?: fallbackAccount,
            // 班号，如 02220101 —— 课表查询就是按它查的
            className = html.firstElementText("LabBh", "LabBh1").orEmpty(),
            major = html.firstElementText("LabZym1", "LabZym").orEmpty(),
            college = html.firstElementText("LabXsm1", "LabXsm", "LabFym1").orEmpty(),
        )
    }

    /** 取第一个取到值的 id 的文本 */
    private fun String.firstElementText(vararg ids: String): String? =
        ids.firstNotNullOfOrNull { id -> htmlElementText(id)?.takeIf { it.isNotBlank() } }

    /**
     * 解析课表页 POST 之后的 `TabSchedule` 表格。实测结构：
     *
     * ```html
     * <table id="TabSchedule">
     *   <tr class="table_titles"><th>*</th><th>星期一</th>…<th>星期五</th></tr>
     *   <tr><td>1-2节</td>
     *       <td>英语听说（一）&nbsp;&nbsp;A1N403&nbsp;&nbsp;禄口<br>孔雁&nbsp;&nbsp;02220101&nbsp;&nbsp;3-5,8-20周</td>
     *       …
     *   </tr>
     * </table>
     * ```
     *
     * 三条必须遵守的规律：
     *  1. **第 1 行是星期几，第 1 列是节次区间**。星期几只能从表头读 ——
     *     系统只渲染有课的星期，周六周日没课时表头就只有星期一~星期五，
     *     写死"第 2 列 = 周一"会把课全排错。
     *  2. **一门课占两行**（`<br>` 分隔）：上行 `课程名 教室 校区`，下行 `教师 班级 周次`。
     *  3. 字段之间是**两个及以上**空白（页面里是 `&nbsp;&nbsp;`）；不足两行的格子
     *     （如「体育（一）」没有教室和教师）要能降级处理。
     */
    fun parseSchedule(html: String): List<SchoolCourse> {
        val table = html.htmlTable("TabSchedule")
            ?: throw IOException("课表页结构变了：没找到 TabSchedule 表格")
        val rows = table.htmlTableRows()
        if (rows.size < 2) return emptyList()

        val weekdayByColumn = HashMap<Int, Int>()
        rows[0].forEachIndexed { column, cell ->
            val weekday = WEEKDAY_LABELS.indexOfFirst { it == cell.stripTags() }
            if (weekday >= 0) weekdayByColumn[column] = weekday + 1
        }
        if (weekdayByColumn.isEmpty()) {
            throw IOException("课表页结构变了：表头里找不到「星期X」列")
        }

        val courses = mutableListOf<SchoolCourse>()
        for (row in rows.drop(1)) {
            if (row.size < 2) continue
            val range = PERIOD_RANGE_REGEX.find(row[0].stripTags()) ?: continue
            val startPeriod = range.groupValues[1].toIntOrNull() ?: continue
            val endPeriod = range.groupValues[2].toIntOrNull() ?: startPeriod
            val periodCount = (endPeriod - startPeriod + 1).coerceAtLeast(1)

            row.forEachIndexed { column, cell ->
                val weekday = weekdayByColumn[column] ?: return@forEachIndexed
                courses += parseCell(cell, weekday, startPeriod, periodCount)
            }
        }
        return courses
    }

    private fun parseCell(
        cellHtml: String,
        weekday: Int,
        startPeriod: Int,
        periodCount: Int,
    ): List<SchoolCourse> {
        // 空格子是 `&nbsp;`，去标签后是空白，直接丢掉
        val lines = cellHtml.htmlLines().filter { it.stripTags().isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val out = mutableListOf<SchoolCourse>()
        var index = 0
        while (index < lines.size) {
            val head = lines[index].nbspFields()
            val tail = lines.getOrNull(index + 1)?.nbspFields().orEmpty()
            val name = head.firstOrNull().orEmpty()

            if (name.isNotBlank()) {
                // 下行最后一个字段是周次（`3-5,8-20周`）；再往前一个若是纯数字/区间，
                // 那是班级列表而不是教师 ——「体育（一）」就没有教师，只给了班号。
                val weeks = tail.lastOrNull()?.takeIf { it.endsWith("周") }.orEmpty()
                val rest = if (weeks.isNotEmpty()) tail.dropLast(1) else tail
                val classList = rest.lastOrNull()?.takeIf { CLASS_LIST_REGEX.matches(it) }.orEmpty()
                val teacher = rest
                    .dropLast(if (classList.isNotEmpty()) 1 else 0)
                    .filterNot { CLASS_LIST_REGEX.matches(it) }
                    .joinToString(" ")

                out += SchoolCourse(
                    // 课名带「㊣」表示与实践环节交叉时正常上课，展示时去掉这个标记
                    name = name.removePrefix(WEEK_CLASH_MARK),
                    teacher = teacher,
                    room = head.getOrNull(1).orEmpty(),
                    campus = head.getOrNull(2).orEmpty(),
                    weekday = weekday,
                    startPeriod = startPeriod,
                    periodCount = periodCount,
                    weeks = weeks,
                )
            }
            index += 2
        }
        return out
    }

    private val WEEKDAY_LABELS =
        listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /** 节次标签，如 `1-2节` / `9-10节` */
    private val PERIOD_RANGE_REGEX = Regex("""(\d+)\s*-\s*(\d+)\s*节""")

    /** 班级列表，如 `02220101` 或 `02210101-102,02220101-403`（用来把它与教师区分开）*/
    private val CLASS_LIST_REGEX = Regex("""[\d,\-]+""")

    /** 实践环节交叉标记 */
    private const val WEEK_CLASH_MARK = "㊣"
}
