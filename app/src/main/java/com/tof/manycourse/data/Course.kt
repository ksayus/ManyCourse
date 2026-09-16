package com.tof.manycourse.data

import androidx.compose.runtime.mutableStateListOf
import com.tof.manycourse.ui.theme.CourseDotColors
import java.time.LocalDate
import kotlin.math.abs

/**
 * 课程数据模型。
 * 时间由"节次"实时计算，不手动填写。
 *
 * 数据来源有两种：用户在「添加课程」里手填，或登录后从教务系统拉取
 * （见 [CourseRepository.replaceAll]）。两者共用这一个模型，
 * UI 不需要知道课程是哪来的。
 */
data class Course(
    val id: Long,
    val name: String,
    val teacher: String,
    val room: String,
    val weekday: Int,      // 1=周一 … 7=周日
    val startPeriod: Int,  // 开始节次（第1节起）
    val periodCount: Int,  // 连上几节
    /**
     * 原始周次串（如 `3-5,8-20周`），只有从教务系统拉来的课才有。
     *
     * 目前**仅作展示**：课表仍按"每周循环"渲染，不区分起止周/单双周
     * （已知限制，见 `docs/UI使用文档.md` §4）。留字段是为了让用户能看到
     * "这课不是每周都有"，也为以后支持周次留好位置。
     */
    val weeks: String = "",
    /**
     * 是不是**从教务系统同步来的**（[CourseRepository.replaceAll] 置 true）。
     *
     * 为什么要区分：课表页/日历页在拿到"按教学周"的数据后会**优先显示它**
     * （那才是"这一周真的会上什么课"），而用户自己在 App 里加的课不属于教务系统、
     * 也没有周次概念，任何一周都该显示 —— 靠这个标记把它们挑出来，
     * 否则"添加课程"加完就看不见了。
     */
    val fromSchool: Boolean = false,
) {
    /** 开始时间，按节次实时计算 */
    val startTime: String get() = CourseRepository.periodTime(startPeriod)

    /** 展示用："第1-2节" */
    val periodLabel: String
        get() = if (periodCount <= 1) "第${startPeriod}节"
        else "第${startPeriod}-${startPeriod + periodCount - 1}节"
}

/** 课程仓库：内存数据 + Compose 可观察状态，增删后所有页面实时刷新 */
object CourseRepository {

    /** 各节次上课时间（可改） */
    private val periodTimes = mapOf(
        1 to "8:00", 2 to "8:55", 3 to "10:10", 4 to "11:05",
        5 to "14:00", 6 to "14:55", 7 to "16:10", 8 to "17:05",
        9 to "19:00", 10 to "19:55",
    )

    const val MAX_PERIOD = 10

    /**
     * 一节课多长（分钟）。
     *
     * 课表里**只有开始时刻**（[periodTimes]），没有下课时间 —— 所以"第 1 节 8:00~8:45"
     * 里的 8:45 是按这个常量推出来的：相邻节次相差 55 分钟 = 45 分钟正课 + 10 分钟课间，
     * 与 [periodTimes] 完全自洽（8:00→8:55→10:10 …）。
     *
     * 之所以敢推：这只用来画时间轴的刻度文案。**真正的"这节课几点结束"仍以教务系统为准**，
     * 哪天接口给了下课时间，改这一个常量即可（见 `docs/UI使用文档.md` §2.2）。
     */
    const val PERIOD_MINUTES = 45

    fun periodTime(period: Int): String = periodTimes[period] ?: ""

    /** 某一节的结束时刻（按 [PERIOD_MINUTES] 推算）；没有这一节则返回空串 */
    fun periodEndTime(period: Int): String {
        val start = parsePeriodTime(period) ?: return ""
        return formatPeriodTime(start.plusMinutes(PERIOD_MINUTES.toLong()))
    }

    /** 时间轴上的刻度文案：`8:00~8:45`；节次越界或时刻解析不出时返回空串 */
    fun periodRangeLabel(period: Int): String {
        val start = periodTimes[period]
        val end = periodEndTime(period)
        return if (start.isNullOrBlank() || end.isBlank()) "" else "$start~$end"
    }

    /**
     * 连上几节时的整段时间：`8:00~9:40`（第一节的开始 → 最后一节的结束）。
     *
     * 课程详情里的「上课时间」用它：课表只给了每节的开始时刻，
     * 整段结束时刻按 [PERIOD_MINUTES] 推算（口径同 [periodRangeLabel]）。
     */
    fun periodRangeLabel(startPeriod: Int, periodCount: Int): String {
        val start = periodTimes[startPeriod] ?: return ""
        val lastPeriod = startPeriod + periodCount.coerceAtLeast(1) - 1
        val end = periodEndTime(lastPeriod)
        return if (end.isBlank()) "" else "$start~$end"
    }

    /** `8:00` → LocalTime；解析不出返回 null（脏数据不让页面崩） */
    private fun parsePeriodTime(period: Int): java.time.LocalTime? = runCatching {
        java.time.LocalTime.parse(periodTimes[period].orEmpty().padStart(5, '0'))
    }.getOrNull()

    /** LocalTime → `8:45`（**不补前导零**，与 [periodTimes] 的写法保持一致） */
    private fun formatPeriodTime(time: java.time.LocalTime): String =
        "${time.hour}:%02d".format(time.minute)

    private var nextId = 1L

    val courses = mutableStateListOf<Course>()

    init {
        // 预置示例数据（可自行删除/修改）
        add("高等数学", "王建国", "教学楼 A-101", weekday = 1, startPeriod = 1, periodCount = 2)
        add("大学英语", "李芳", "图书馆 302", weekday = 1, startPeriod = 3, periodCount = 2)
        add("计算机基础", "张伟", "实验楼 B-205", weekday = 1, startPeriod = 5, periodCount = 2)
        add("程序设计", "刘强", "实验楼 C-305", weekday = 2, startPeriod = 5, periodCount = 2)
        add("数据结构", "陈静", "实验楼 C-301", weekday = 3, startPeriod = 1, periodCount = 2)
        add("大学物理", "赵敏", "教学楼 B-202", weekday = 4, startPeriod = 1, periodCount = 2)
        add("体育", "孙鹏", "体育馆", weekday = 5, startPeriod = 7, periodCount = 2)
    }

    fun add(
        name: String,
        teacher: String,
        room: String,
        weekday: Int,
        startPeriod: Int,
        periodCount: Int,
        weeks: String = "",
        fromSchool: Boolean = false,
    ): Course {
        val course = Course(nextId++, name, teacher, room, weekday, startPeriod, periodCount, weeks, fromSchool)
        courses.add(course)
        return course
    }

    /**
     * 用教务系统拉回来的课表**整体替换**本地课程。
     *
     * 为什么是"替换"而不是"合并"：教务系统的课表才是权威数据源，
     * 合并会让已经退掉的课永远留在本地。用户在 App 里手动加的课
     * 在同步后会被清掉 —— 这是刻意的取舍，需要保留的话应该做"多课表"，
     * 而不是在这里悄悄合并。
     *
     * 由 `CourseSync` 在登录后调用（已在主线程），所以这里可以安全地改 SnapshotStateList。
     */
    fun replaceAll(remote: List<SchoolCourse>) {
        courses.clear()
        remote.forEach { item ->
            add(
                name = item.name,
                teacher = item.teacher,
                // 教室与校区拼在一起展示，如「A1N403 禄口」
                room = listOf(item.room, item.campus).filter { it.isNotBlank() }.joinToString(" "),
                weekday = item.weekday,
                startPeriod = item.startPeriod,
                periodCount = item.periodCount,
                weeks = item.weeks,
                fromSchool = true,
            )
        }
    }

    /**
     * **用户自己加的**课（不是从教务系统同步来的）。
     *
     * 课表页 / 日历页在展示"按教学周"的权威数据时，仍然要把这些课加进去 ——
     * 它们没有周次概念，每一周都该出现，而且只有这样"添加课程"才看得见效果。
     */
    val manualCourses: List<Course> get() = courses.filter { !it.fromSchool }

    /** 清掉预置的示例数据（课程数据由教务系统提供时用）*/
    fun clear() = courses.clear()

    fun remove(id: Long) {
        courses.removeAll { it.id == id }
    }

    /** 某天（星期几）的全部课程，按节次排序 */
    fun coursesOn(weekday: Int): List<Course> =
        courses.filter { it.weekday == weekday }.sortedBy { it.startPeriod }

    /** 某具体日期的课程（按周循环课表实时计算） */
    fun coursesOn(date: LocalDate): List<Course> = coursesOn(date.dayOfWeek.value)

    /** 课程卡片/圆点颜色：按课程名哈希取色，同日多门课颜色不同 */
    fun colorOf(course: Course) = colorOfName(course.name)

    /** 同上，但直接吃课程名 —— 教务系统按周拉回来的课（`SchoolCourse`）也要同一个颜色 */
    fun colorOfName(name: String) = CourseDotColors[abs(name.hashCode()) % CourseDotColors.size]
}
