package com.tof.manycourse.gr_api.schools

import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.gr_api.htmlInputValue
import com.tof.manycourse.gr_api.htmlSelectedOption
import com.tof.manycourse.gr_api.stripTags
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

/**
 * 广东工业大学教学管理系统（`jxfw.gdut.edu.cn`）的**页面 / 课表解析**。
 *
 * 这套系统的 URL 是 `xxx!yyy.action` 的 Struts 风格，**没有 REST 接口**：
 * 课表被塞在 HTML 页面里的一个 JS 变量中，所以"解析页面"是绕不过去的一步。
 *
 * ## 两条链路、四个地址（全部出自抓包核对）
 *
 * ```
 * 统一身份认证（authserver.gdut.edu.cn，金智那套）
 *   ① GET  /authserver/login?service=https://jxfw.gdut.edu.cn/new/ssoLogin
 *          页面里藏着 pwdEncryptSalt / execution / lt 三个隐藏域 ← 本文件负责取出来
 *   ② POST 同一个地址（表单）→ 302 https://jxfw.gdut.edu.cn/new/ssoLogin?ticket=ST-…
 *          登录失败时**页面原样回 200**，错误文案在 <span id="showErrorTip">…</span> 里 ← 也在这里取
 *
 * 教务系统（jxfw.gdut.edu.cn）
 *   ③ GET  /xsgrkbcx!getXsgrbkList.action    学期下拉框（#xnxqdm 的 selected = 当前学期）
 *   ④ GET  /xsgrkbcx!xsAllKbList.action?xnxqdm=<学期>    整学期课表 → `var kbxx = [ … ];`
 *   ⑤ GET  /xsgrkbcx!getKbRq.action?xnxqdm=<学期>&zc=<周> 某一周 → `[[课程…],[星期→日期…]]`
 * ```
 *
 * ## 为什么按"字符串 / JSON"分两层
 *
 * `org.json` 在 JVM 单元测试里是**未实现的 stub**（一调用就抛 "not mocked"），
 * 所以把最容易写错的部分（节次拆分、周次归一化、隐藏域取值、错误文案）全放在
 * **只吃字符串**的函数里，由 `GdutScheduleParserTest` 穷举；
 * 剩下"JSON 数组 → 参数"的一层交给真机测试 `GdutScheduleJsonTest`。
 * 这个分层是照 `GzusScheduleParser` 抄的，理由完全一样。
 *
 * ## 几个**错了也不报错、只表现为"没课/密码错误"**的点
 *
 * | 点 | 说明 |
 * |---|---|
 * | 隐藏域 id | 广工这版是 `pwdEncryptSalt`（**不是**新版金智的 `pwdDefaultEncryptSalt`），三种写法都认；取不到就别提交 |
 * | `execution` | 页面里 4 个表单各有一个同名隐藏域，**取第一个即可**（四个值实测相同），且必须原样回传 |
 * | `zcs` | 整学期课表的周次是**乱序、逗号分隔**的（实测 `"16,7,8,9,10,11,17,12,13,14,15"`），不能当区间解析，也不能直接展示 |
 * | `jcdm2` | 节次是**两位补零的逗号串**（`"01,02,03,04"`），不是 `"1-4"` |
 * | `jxcdmcs` | 场地名里**已带校区括号**（`"教1-106(揭)"`），所以 `SchoolCourse.campus` 留空 —— 拆开反而更难读 |
 */
internal object GdutScheduleParser {

    // ── ① 登录页：三个隐藏域 ────────────────────────────────────────────────

    /**
     * 统一身份认证登录页里**必须原样回传**的隐藏域。
     *
     * @param salt      密码加密用的盐（页面上 id 无 `name`，只能按 id 取）
     * @param execution 服务端签发的防重放票据（`<uuid>_<base64(JWT)>`，6000 个字符左右）
     * @param lt        登录票据；广工这台服务器恒为空串，但**必须带上这个字段**（空值也要带）
     */
    data class CasLoginForm(
        val salt: String,
        val execution: String,
        val lt: String,
    )

    /**
     * 从登录页 HTML 里取盐 / execution / lt。
     *
     * 三种盐的写法都认（按实测概率排序）：
     *  1. `<input type="hidden" id="pwdEncryptSalt" value="…">` ← 广工
     *  2. `<input type="hidden" id="pwdDefaultEncryptSalt" value="…">` ← 新版金智
     *  3. `var pwdDefaultEncryptSalt = "…";` ← 更老的版本直接写在 `<script>` 里
     *
     * **盐取不到时返回 null**，调用方必须直接失败：拿空盐去加密，服务端只会回
     * "用户名或密码错误"，而那个提示会把用户和排查者一起带沟里。
     */
    fun parseCasLoginForm(html: String): CasLoginForm? {
        val salt = html.htmlInputValue("pwdEncryptSalt")
            ?: html.htmlInputValue("pwdDefaultEncryptSalt")
            ?: Regex("""pwdDefaultEncryptSalt\s*=\s*["']([^"']*)["']""").find(html)?.groupValues?.get(1)
        val saltValue = salt?.trim().orEmpty()
        if (saltValue.isEmpty()) return null
        return CasLoginForm(
            salt = saltValue,
            execution = html.htmlInputValue("execution").orEmpty(),
            lt = html.htmlInputValue("lt").orEmpty(),
        )
    }

    /**
     * 登录页上的错误提示（`<span id="showErrorTip"><span>用户名或密码错误</span></span>`）。
     *
     * 登录失败时服务端**不返回错误码**，只是把登录页原样回一遍（HTTP 200），
     * 所以判"成功/失败"只能靠"有没有跳走"，而"为什么失败"只能靠这段文案。
     * 取不到就返回 null，由上层给一句通用的失败提示。
     */
    fun parseCasError(html: String): String? {
        // 直接在全文档里找这个 span：页面里的 JS 也写着 `$("#showErrorTip")`，
        // 但那不是标签，匹配不上；凭空"从关键字往后截"反而会把真正的标签截掉
        val text = Regex("""<span\b[^>]*id\s*=\s*["']showErrorTip["'][^>]*>([\s\S]*?)</span>""")
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.stripTags()
            .orEmpty()
        return text.takeIf { it.isNotBlank() }
    }

    /**
     * 服务端回的**「非法访问 / 你没有该权限」**页（HTTP 200、272 字节左右）。
     *
     * 实测原文：
     *
     * ```html
     * <!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" …>
     * <html><head><title>非法访问</title></head><body>你没有该权限</body></html>
     * ```
     *
     * 它是 jxfw 的**防盗链校验**：`xxx!action` 这类地址必须带 `Referer`
     * （只校验"是不是本站地址"，不校验具体哪一页），不带就回这个。
     *
     * 为什么要单独认出来：它长得像"页面空了一下"，如果混进
     * [parseCurrentTerm] 那种"读不到就返回 null"的路径，用户看到的就是
     * 「课表页结构变了：读不到当前学年学期」—— 一句把人往错误方向带的话。
     */
    fun isAccessDenied(html: String): Boolean =
        html.contains(ACCESS_DENIED_TITLE) || html.contains(ACCESS_DENIED_BODY)

    private const val ACCESS_DENIED_TITLE = "非法访问"
    private const val ACCESS_DENIED_BODY = "你没有该权限"

    // ── ② 教务页面：学期 / 是否已发布课表 ───────────────────────────────────

    /**
     * 课表页 `#xnxqdm`（学年学期）下拉框里**选中的那一个**，如 `"202601"`（2026 秋季）。
     *
     * 为什么不在客户端按日期算"现在是哪个学期"：`xnxqdm` 是学校自定的编码
     * （`202601` = 2026 学年秋季，`202502` = 2025 学年春季），而且寒暑假、开学时间各校不同 ——
     * 页面选中的那个就是权威答案。
     */
    fun parseCurrentTerm(pageHtml: String): String? =
        pageHtml.htmlSelectedOption("xnxqdm")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 本学期课表**发布了没有**。
     *
     * 页面里有这么一句（服务端把变量渲染进去）：
     *
     * ```js
     * if('' == 'false'){ $.messager.alert('信息','本学期课表还未发布，请稍后关注!','warning'); }
     * ```
     *
     * - 注入值是空串 → 页面没给结论，返回 null（**别乱猜**）；
     * - 注入值是 `false` → 课表还没发布，返回 `false`；
     * - 其它 → true。
     *
     * 有它就能在"课表拉到 0 条"时给一句准确的话（"本学期课表尚未发布"），
     * 而不是让用户以为同步坏了。
     */
    fun parseSchedulePublished(pageHtml: String): Boolean? {
        val token = Regex("""if\(\s*'([^']*)'\s*==\s*'false'\s*\)""")
            .find(pageHtml)
            ?.groupValues
            ?.get(1)
            ?: return null
        return when (token.trim()) {
            "" -> null
            "false" -> false
            else -> true
        }
    }

    // ── ③ 整学期课表：`var kbxx = [ … ];` ───────────────────────────────────

    /**
     * 从 `xsgrkbcx!xsAllKbList.action` 的页面里抠出 `var kbxx = [...]` 那段 JSON 文本。
     *
     * 为什么要"抠"：整套课表是一个**内联在 `<script>` 里的 JS 字面量**，
     * 不是接口响应。用非贪婪匹配到第一个 `];` 为止 —— 课程对象里只有 `{}`，
     * 不会提前出现 `]`（课程名里也不会）。
     *
     * @return JSON 数组文本；页面结构变了返回 null（上层据此报错，而不是"这学期没课"）
     */
    fun extractKbxx(html: String): String? =
        Regex("""var\s+kbxx\s*=\s*(\[[\s\S]*?\])\s*;""").find(html)?.groupValues?.get(1)

    /** kbxx 里每一条记录里我们**真正要**的字段（名字与教务系统一致）*/
    fun toCourse(
        courseName: String,
        teacher: String,
        room: String,
        weekday: String,
        periodCodes: String,
        weeks: String,
    ): SchoolCourse? {
        val name = courseName.trim()
        if (name.isEmpty()) return null

        val day = weekday.trim().toIntOrNull()?.takeIf { it in 1..7 } ?: return null
        val period = parsePeriodCodes(periodCodes) ?: return null

        return SchoolCourse(
            name = name,
            teacher = teacher.trim(),
            // 场地名本身已带校区括号（"教1-106(揭)"），原样展示
            room = room.trim(),
            weekday = day,
            startPeriod = period.first,
            periodCount = period.second,
            weeks = normalizeWeeks(weeks),
        )
    }

    /**
     * `jcdm2`（节次代码，实测 `"01,02,03,04"` / `"06,07"`）→ `(开始节次, 连上几节)`。
     *
     * 服务端给的是**两位补零的逗号串**，不是 `"1-4"`，所以不能套正方的解析器。
     * 首尾之间即使跳号（`"01,02,05"`）也按一整段算 —— 那一节本来就是被占住的
     * （课间休息），显示成 `第1-5节` 与学生在页面上看到的一致。
     */
    fun parsePeriodCodes(raw: String): Pair<Int, Int>? {
        val numbers = Regex("""\d+""").findAll(raw).mapNotNull { it.value.toIntOrNull() }.toList()
        val start = numbers.minOrNull() ?: return null
        val end = numbers.maxOrNull() ?: return null
        if (start <= 0) return null
        return start to (end - start + 1)
    }

    /**
     * 周次串归一化：`"16,7,8,9,10,11,17,12,13,14,15"` → `"7-17周"`。
     *
     * 教务系统给的周次是**乱序**的（实测的 `zcs` 完全没排序），直接展示就是
     * `16,7,8,9,10,11,17,…` 这种谁也读不懂的东西；而归一化之后既能展示，
     * 也能一眼看出"这门课只上前 8 周"。
     *
     * 规则：解析出全部周次（`1,2,3` 与 `1-3` 两种写法都认）→ 去重升序 →
     * 连续的数字压成 `a-b` → 单周保持 `a` → 末尾补 `周`。
     * 空串 / 解析不出数字时**原样返回**（宁可难看也别把服务端的话弄丢）。
     */
    fun normalizeWeeks(raw: String): String {
        val weeks = parseWeekNumbers(raw)
        if (weeks.isEmpty()) return raw.trim()

        val parts = mutableListOf<String>()
        var rangeStart = -1
        var previous = -1
        weeks.forEach { week ->
            if (rangeStart < 0) {
                rangeStart = week
                previous = week
                return@forEach
            }
            if (week == previous + 1) {
                previous = week
                return@forEach
            }
            parts += if (rangeStart == previous) "$rangeStart" else "$rangeStart-$previous"
            rangeStart = week
            previous = week
        }
        parts += if (rangeStart == previous) "$rangeStart" else "$rangeStart-$previous"
        return parts.joinToString(",") + "周"
    }

    /**
     * 从周次串里解析出全部周次（升序去重）。
     *
     * **两种写法都认**：逗号分隔的单周（广工实测就是这种：`"16,7,8,…"`）
     * 和区间（`"4-19周"`，别的学校/别的接口会出现）。
     * 只认 1..[MAX_WEEKS] 的数字：服务端哪天塞进来一个学号或者日期，
     * 也不至于把周次表撑爆。
     */
    fun parseWeekNumbers(raw: String): java.util.SortedSet<Int> {
        val weeks = java.util.TreeSet<Int>()
        var rest = raw
        WEEK_RANGE.findAll(raw).forEach { match ->
            val from = match.groupValues[1].toIntOrNull() ?: return@forEach
            val to = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (from in 1..MAX_WEEKS && to in from..MAX_WEEKS) (from..to).forEach { weeks += it }
            rest = rest.replace(match.value, " ")
        }
        Regex("""\d+""").findAll(rest).forEach { match ->
            match.value.toIntOrNull()?.takeIf { it in 1..MAX_WEEKS }?.let { weeks += it }
        }
        return weeks
    }

    /** 一批周次串里的**最大周次**（用来定教学周总数：`"16,7,17"` → 17）*/
    fun maxWeekOf(rawWeeks: List<String>): Int =
        rawWeeks.flatMap { parseWeekNumbers(it) }.maxOrNull() ?: 0

    /** 周次区间的写法：`1-4` / `1~4` / `1至4`（有的系统用中文"至"）*/
    private val WEEK_RANGE = Regex("""(\d{1,2})\s*[-~～至]\s*(\d{1,2})""")

    /** 一学期不可能有这么多周；超过就当它不是周次（防脏数据把内存撑爆）*/
    private const val MAX_WEEKS = 30

    // ── ④ 某一周：`[[课程…],[星期→日期…]]` ─────────────────────────────────

    /**
     * 从周课表接口（`getKbRq`）的响应里取"星期 → 日期"那张表，算出**这一周的周一**。
     *
     * 服务端给的是 `[{"xqmc":"1","rq":"2026-09-14"}, …]`（`xqmc` 是"星期几"的数字 1~7）。
     * 万一没有星期一的记录（服务端漏了 / 字段名变了），就退化成"取最早的那天、按星期往回推"。
     */
    fun parseFirstMonday(dates: List<Pair<Int, String>>): LocalDate? {
        val monday = dates.firstOrNull { it.first == 1 }?.second
        if (monday != null) return parseDate(monday)
        val (day, raw) = dates.minByOrNull { it.second } ?: return null
        val date = parseDate(raw) ?: return null
        return date.minusDays((day - 1).toLong())
    }

    private fun parseDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value.trim()) }.getOrNull()

    /**
     * 由"第 1 周的周一"和总周数推出整个教学周列表。
     *
     * 推导之所以可靠：实测第 1/3/5 周的周一分别是 09-14 / 09-28 / 10-12，
     * 正好每 7 天一个 —— 教学周就是**连续的自然周**，服务端自己也是这么算的。
     * 所以只发一个 `zc=1` 的请求就够，不必把 22 周挨个问一遍。
     *
     * @param count 周数；≤0 时返回空表（调用方据此报错，别凭空造 0 周）
     */
    fun buildWeeks(firstMonday: LocalDate, count: Int): List<SchoolWeek> {
        if (count <= 0) return emptyList()
        return (1..count).map { index ->
            val start = firstMonday.plusDays(7L * (index - 1))
            SchoolWeek(index = index, start = start, end = start.plusDays(6))
        }
    }

    /** 周课表里每门课都带 `zc`（周次）—— 单周课表的 `weeks` 展示成 `"3周"` */
    fun weekLabel(rawWeek: String): String = rawWeek.trim().takeIf { it.isNotEmpty() }?.let { "${it}周" } ?: ""

    // ── ⑤ 学生信息：欢迎页里的姓名 ─────────────────────────────────────────

    /**
     * 从 `login!welcome.action` 的页面里取**姓名**（页面顶部那个"严海涛"）。
     *
     * 页面结构（实测原文）：
     *
     * ```html
     * <div id="header">
     *   <div class="infoDiv">
     *     <div class="top"> 严海涛 </div>          ← 就是这个
     *     <div class="bottom"> 修改密码 | 设置 | … </div>
     *   </div>
     *   <div id="topbg">
     *     <div class="top"> 登录时间：2026-09-17 00:12:33 </div>   ← 同名 class，别取错了
     * ```
     *
     * 做法：先把范围收在 `id="header"` 与 `id="topbg"` 之间，再取第一个
     * `class="top"` —— 这样即便"登录时间"那行哪天挪了位置也不会取错。
     * 兜底（两个锚点都没了）时才退化成"全页面找第一个不像时间/操作的 class=top"。
     */
    fun parseStudentName(html: String): String? {
        val header = html.substringAfter("id=\"header\"", html)
        val segment = header.substringBefore("id=\"topbg\"", header)
        return firstTopBlockText(segment) ?: firstTopBlockText(html)
    }

    private fun firstTopBlockText(html: String): String? =
        Regex("""<div\b[^>]*class\s*=\s*["']top["'][^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1].stripTags() }
            .firstOrNull { it.isNotBlank() && it.length <= NAME_MAX_LENGTH && "：" !in it && ":" !in it }

    private const val NAME_MAX_LENGTH = 20

    // ── ⑥ JSON 适配层（真机测试覆盖）───────────────────────────────────────

    /** 整学期课表页面 → 课程列表；抠不出 `kbxx` 时抛错（上层报"登录已过期/改版"）*/
    fun parseSemesterSchedule(html: String): List<SchoolCourse> {
        val json = extractKbxx(html) ?: throw IOException(EXPIRED_OR_CHANGED)
        val array = try {
            JSONArray(json)
        } catch (e: Exception) {
            throw IOException(EXPIRED_OR_CHANGED)
        }
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { toCourse(it) }
        }
    }

    /** 只要原始周次串（算教学周总数用），不做成课程对象 */
    fun parseRawWeeks(html: String): List<String> {
        val json = extractKbxx(html) ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("zcs") }
    }

    private fun toCourse(item: JSONObject): SchoolCourse? = toCourse(
        courseName = item.optString("kcmc"),
        teacher = item.optString("teaxms"),
        room = item.optString("jxcdmcs"),
        weekday = item.optString("xq"),
        periodCodes = item.optString("jcdm2"),
        weeks = item.optString("zcs"),
    )

    /**
     * 周课表接口（`getKbRq`）的响应 → 课程列表。
     *
     * 响应是个**二元数组**：`[ [课程…], [星期→日期…] ]`。
     * 每条课程带单周 `zc`、`jcdm2`、`xq`、`jxcdmc`（注意这里**没有 s**：
     * 整学期接口叫 `jxcdmcs`，周课表叫 `jxcdmc`，就是这么不一致）。
     */
    fun parseWeekCourses(response: JSONArray): List<SchoolCourse> {
        val array = response.optJSONArray(0) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            toCourse(
                courseName = item.optString("kcmc"),
                teacher = item.optString("teaxms"),
                room = item.optString("jxcdmc").ifBlank { item.optString("jxcdmcs") },
                weekday = item.optString("xq"),
                periodCodes = item.optString("jcdm2"),
                weeks = weekLabel(item.optString("zc")),
            )
        }
    }

    /** 周课表接口响应里的"星期 → 日期"表（下标 1 那个数组），解析成 `(星期几, yyyy-MM-dd)` */
    fun parseWeekDates(response: JSONArray): List<Pair<Int, String>> {
        val array = response.optJSONArray(1) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val weekday = item.optString("xqmc").trim().toIntOrNull() ?: return@mapNotNull null
            val date = item.optString("rq").trim()
            if (date.isEmpty()) null else weekday to date
        }
    }

    /** 抓包/页面结构变化时的统一文案：既是"登录过期"也是"改版"的兜底说法 */
    private const val EXPIRED_OR_CHANGED =
        "课表页里读不到 kbxx（课表数据），可能登录已过期或教务系统改版了"
}
