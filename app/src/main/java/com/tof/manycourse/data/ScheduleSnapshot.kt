package com.tof.manycourse.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * 一份**课表快照**：登录后从教务系统拿到的全部可展示数据，打包成一份落盘。
 *
 * ## 它为什么必须同时包含"学期课表"和"按周课表"
 *
 * 课表页和日历页读的是**两套**数据（见 `WeekScheduleStore` 的注释）：
 *  - 学期课表 `courses`  → `CourseRepository`，课表页的骨架；
 *  - 教学周 `weeks` + 每周的课 `coursesByWeek` → 日历页的"那天到底有没有课"。
 *
 * 只存前者的话，离线时日历会退化成"本地课表按星期几循环"——
 * 只在第 2-4 周上的军训会被画满整个学期。
 *
 * ## 为什么不存 [Course]
 *
 * [Course] 是展示模型，`id` 是本地自增的，跨进程没有意义；
 * 真正从学校来的是 [SchoolCourse]，恢复时走 `CourseRepository.replaceAll` 重新翻译一遍，
 * 与在线同步**走的是同一条路**，不会出现"缓存恢复的课表和同步来的长得不一样"。
 *
 * @param schoolId / [account] 这份数据属于谁。**和 `SessionBlob` 一样放在加密块里**，
 *   不额外写明文（学号是 PII，规矩见 `SessionStore` 的注释）
 * @param studentName 已解析好的姓名（同步时就是 `name.ifBlank { account }`）
 * @param savedAt 写盘时刻，UI 用它显示"最后更新于 …"，也用来做 TTL 判断
 */
internal data class ScheduleSnapshot(
    val schoolId: String,
    val account: String,
    val studentName: String = "",
    val courses: List<SchoolCourse> = emptyList(),
    val weeks: List<SchoolWeek> = emptyList(),
    val coursesByWeek: Map<Int, List<SchoolCourse>> = emptyMap(),
    val savedAt: Long = 0L,
) {
    /** 什么都没有的快照不值得写盘（会把上一次的好数据冲成空的）*/
    val isEmpty: Boolean get() = courses.isEmpty() && weeks.isEmpty() && coursesByWeek.isEmpty()
}

/**
 * [ScheduleSnapshot] 的文本编解码 —— 纯逻辑，可 JVM 单测。
 *
 * 格式沿用 `SessionBlobCodec` / `CookieCodec` 那一套：**带版本号的头部 + 一行一键 + 值统一 URL-safe Base64**。
 * 值必须编码，因为课程名/教室里有中文、可能有 `|`，不编码就会把分隔符撑破。
 *
 * ```
 * manycourse-schedule/1
 * schoolId=Ym1ocXk
 * account=MDIyMjAxMDEwMg
 * name=…
 * savedAt=1758000000000
 * c=<名>|<师>|<教室>|<周几>|<起节>|<节数>|<周次串>|<校区>
 * c=…
 * week=<周次>|<周一 ISO>|<周日 ISO>
 * wh=<周次>            ← 下面跟着的是"这一周的课"
 * wc=<课程字段同 c>
 * wc=…
 * ```
 *
 * **坏行只丢那一行**，不整份作废：和 `CookieCodec` 的取舍一致
 * （"少一条非关键 Cookie 远好过整份读不出来只能重新登录"）。
 * 但身份字段（学校/账号）缺了就必须返回 null —— 那是"恢复出一个人身份不明的课表"，不能忍。
 */
internal object ScheduleSnapshotCodec {

    const val HEADER = "manycourse-schedule/1"

    private const val KEY_SCHOOL = "schoolId"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_NAME = "name"
    private const val KEY_SAVED_AT = "savedAt"
    private const val KEY_COURSE = "c"
    private const val KEY_WEEK = "week"
    private const val KEY_WEEK_HEAD = "wh"
    private const val KEY_WEEK_COURSE = "wc"

    /** 一条课程 8 个字段，顺序写死在这里，[decodeCourse] 按同样的顺序读 */
    private const val COURSE_FIELDS = 8

    fun encode(snapshot: ScheduleSnapshot): String = buildString {
        append(HEADER).append('\n')
        append(KEY_SCHOOL).append('=').append(b64(snapshot.schoolId)).append('\n')
        append(KEY_ACCOUNT).append('=').append(b64(snapshot.account)).append('\n')
        append(KEY_NAME).append('=').append(b64(snapshot.studentName)).append('\n')
        append(KEY_SAVED_AT).append('=').append(snapshot.savedAt.toString()).append('\n')
        snapshot.courses.forEach { append(KEY_COURSE).append('=').append(course(it)).append('\n') }
        snapshot.weeks.forEach { append(KEY_WEEK).append('=').append(week(it)).append('\n') }
        snapshot.coursesByWeek.forEach { (index, list) ->
            // ★ 空表也是数据：`wh=3` 后面一条 wc 都没有 = "第 3 周确实没课"。
            //   丢掉这个区别，日历就会把"这周没课"显示成"这周还没加载"
            append(KEY_WEEK_HEAD).append('=').append(index.toString()).append('\n')
            list.forEach { append(KEY_WEEK_COURSE).append('=').append(course(it)).append('\n') }
        }
    }

    /** 解不出来返回 null（调用方丢弃这一档，而不是崩在启动路径上）*/
    fun decode(text: String): ScheduleSnapshot? {
        if (!hasOurHeader(text)) return null

        var schoolId = ""
        var account = ""
        var studentName = ""
        var savedAt = 0L
        val courses = mutableListOf<SchoolCourse>()
        val weeks = mutableListOf<SchoolWeek>()
        val byWeek = LinkedHashMap<Int, MutableList<SchoolCourse>>()
        var currentWeek: Int? = null

        text.lineSequence().forEach { line ->
            val at = line.indexOf('=')
            if (at <= 0) return@forEach // 头部那行、空行
            val key = line.substring(0, at)
            val value = line.substring(at + 1)
            when (key) {
                KEY_SCHOOL -> schoolId = unB64(value) ?: return@forEach
                KEY_ACCOUNT -> account = unB64(value) ?: return@forEach
                KEY_NAME -> studentName = unB64(value).orEmpty()
                KEY_SAVED_AT -> savedAt = value.toLongOrNull() ?: 0L
                KEY_COURSE -> decodeCourse(value)?.let(courses::add)
                KEY_WEEK -> decodeWeek(value)?.let(weeks::add)
                KEY_WEEK_HEAD -> currentWeek = value.toIntOrNull()?.also { byWeek.getOrPut(it) { mutableListOf() } }
                KEY_WEEK_COURSE -> currentWeek?.let { w ->
                    decodeCourse(value)?.let { byWeek.getOrPut(w) { mutableListOf() }.add(it) }
                }
            }
        }

        if (schoolId.isBlank() || account.isBlank()) return null
        return ScheduleSnapshot(
            schoolId = schoolId,
            account = account,
            studentName = studentName,
            courses = courses,
            weeks = weeks,
            coursesByWeek = byWeek,
            savedAt = savedAt,
        )
    }

    /** 头部是不是这份格式（用来区分"旧版本存档"和"别人的数据"）*/
    fun hasOurHeader(text: String): Boolean = text.startsWith(HEADER)

    private fun course(c: SchoolCourse): String = listOf(
        b64(c.name), b64(c.teacher), b64(c.room),
        c.weekday.toString(), c.startPeriod.toString(), c.periodCount.toString(),
        b64(c.weeks), b64(c.campus),
    ).joinToString("|")

    private fun decodeCourse(value: String): SchoolCourse? {
        val parts = value.split('|')
        if (parts.size != COURSE_FIELDS) return null
        val name = unB64(parts[0]) ?: return null
        val weekday = parts[3].toIntOrNull() ?: return null
        val start = parts[4].toIntOrNull() ?: return null
        val count = parts[5].toIntOrNull() ?: return null
        // 越界数据直接丢掉：一条脏课会在周视图上画到时间轴外面去
        if (name.isBlank() || weekday !in 1..7 || start <= 0 || count <= 0) return null
        return SchoolCourse(
            name = name,
            teacher = unB64(parts[1]).orEmpty(),
            room = unB64(parts[2]).orEmpty(),
            weekday = weekday,
            startPeriod = start,
            periodCount = count,
            weeks = unB64(parts[6]).orEmpty(),
            campus = unB64(parts[7]).orEmpty(),
        )
    }

    private fun week(w: SchoolWeek): String =
        listOf(w.index.toString(), b64(w.start.toString()), b64(w.end.toString())).joinToString("|")

    private fun decodeWeek(value: String): SchoolWeek? {
        val parts = value.split('|')
        if (parts.size != 3) return null
        val index = parts[0].toIntOrNull() ?: return null
        val start = unB64(parts[1])?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val end = unB64(parts[2])?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        if (end.isBefore(start)) return null
        return SchoolWeek(index, start, end)
    }

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unB64(value: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
}

/** 缓存时间的展示格式：`09-16 14:30` */
private val CACHE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 把 [millis] 格式化成界面上的"最后更新于 …"。
 *
 * 纯函数（`zone` 可注入）—— 所以它能被 JVM 单测钉住，不用起设备。
 */
internal fun formatCacheTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    CACHE_TIME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(millis))