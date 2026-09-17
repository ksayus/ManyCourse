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
    /**
     * 开始时间，按节次实时计算（走**当前学校**的作息表）。
     *
     * **可能是空串**：这所学校还没拿到准确作息时（如广工，见 [Timetables.gdut]）
     * 宁可空着，也不编一个时刻出来 —— 卡片那行会只显示地点（`listOf(startTime, room)
     * .filter { it.isNotBlank() }`）。
     */
    val startTime: String get() = CourseRepository.periodTime(startPeriod)

    /** 展示用："第1-2节" */
    val periodLabel: String
        get() = if (periodCount <= 1) "第${startPeriod}节"
        else "第${startPeriod}-${startPeriod + periodCount - 1}节"
}

/** 课程仓库：内存数据 + Compose 可观察状态，增删后所有页面实时刷新 */
object CourseRepository {

    /** 一个块里连排两节（各校一致：课表就是两节连排） */
    const val PERIODS_PER_BLOCK = 2

    /**
     * **当前作息** —— 节次表跟着"当前学校"走（见 [Timetable]）。
     *
     * 取"哪所学校的课表"的口径与 `CourseSync` / 日历页完全一致：
     * **登录的学校优先，其次是登录页选中的学校**；都没有（未登录 / 本地调试）就是默认那张
     * 16 节的表。
     *
     * 读的是 [SessionStore.schoolId] / [LoginSettings.selectedSchoolId] 这两个 Compose 状态，
     * 所以在页面里读它会在**换学校时自动重组**（时间轴行数、节次选择都跟着变）。
     */
    val timetable: Timetable
        get() = Timetables.of(SessionStore.schoolId.value ?: LoginSettings.selectedSchoolId.value)

    /**
     * 一节课多长（分钟）：块长 80 分钟 ÷ 2 = **40**（默认表）。
     *
     * 用它把"块起点"推成两节的开始/结束时刻（`9:00~9:40`、`9:40~10:20`），
     * 也用来判断"现在正在上哪一节"。口径在 [Timetable.periodMinutes]（按学校）。
     */
    val PERIOD_MINUTES: Int get() = timetable.periodMinutes

    /**
     * 最大节次 = 块数 × 每块两节。
     *
     * 默认表 16 节；**广东工业大学 14 节**（它自己的课表页就是 14 行，见 [Timetables.gdut]）——
     * 「添加课程」的节次选择与数据夹取都读这里，所以广工不会再出现第 15-16 节。
     */
    val MAX_PERIOD: Int get() = timetable.maxPeriod

    /** 块数（= 周视图的行数上限）：默认 16 节 → 8 行；广工 14 节 → 7 行 */
    val BLOCK_COUNT: Int get() = timetable.blockCount

    /** 第 [period] 节的开始时刻；**越界、或这所学校还没有时刻表时返回空串**（不编时刻）*/
    fun periodTime(period: Int): String = timetable.startOfPeriod(period)

    /** 某一节的结束时刻（按 [PERIOD_MINUTES] 推算）；没有这一节/没有时刻则返回空串 */
    fun periodEndTime(period: Int): String = timetable.endOfPeriod(period)

    /** 某一节自己的刻度文案：`9:00~9:40`；节次越界或时刻未知时返回空串 */
    fun periodRangeLabel(period: Int): String {
        val start = periodTime(period)
        val end = periodEndTime(period)
        return if (start.isBlank() || end.isBlank()) "" else "$start~$end"
    }

    /**
     * 连上几节时的整段时间：`9:00~10:20`（第一节开始 → 最后一节结束）。
     *
     * 课程详情的「上课时间」用它；**时刻未知时返回空串**，那一格就只写节次
     * （调用方按 `isNotBlank` 过滤，见 `courseDetailOf`）。
     */
    fun periodRangeLabel(startPeriod: Int, periodCount: Int): String {
        val start = periodTime(startPeriod)
        val lastPeriod = startPeriod + periodCount.coerceAtLeast(1) - 1
        val end = periodEndTime(lastPeriod)
        return if (start.isBlank() || end.isBlank()) "" else "$start~$end"
    }

    /**
     * **某个块的区间文案**（周视图时间轴每一行用）：`9:00~10:20`；时刻未知返回空串。
     *
     * @param blockIndex 0 起的块序号（0 = 第 1-2 节）
     */
    fun blockRangeLabel(blockIndex: Int): String {
        val start = blockStartTime(blockIndex)
        val end = blockEndTime(blockIndex)
        return if (start.isBlank() || end.isBlank()) "" else "$start~$end"
    }

    /**
     * 某个块的**开始时刻**（周视图时间轴的第一行，如 `9:00`）；越界或未知返回空串。
     *
     * 时间轴上的时刻刻意分两行写（开始在上面、结束在下面），而不是
     * [blockRangeLabel] 那种 `9:00~10:20` 的区间写法：七列平分屏幕后左轴只有 40dp 上下，
     * 区间写法会被省略号截断（"时间显示不全"）。拆开以后每行都短，两行都完整。
     */
    fun blockStartTime(blockIndex: Int): String = timetable.startOfBlock(blockIndex)

    /** 某个块的**结束时刻**（周视图时间轴的最后一行，如 `10:20`）；越界或未知返回空串 */
    fun blockEndTime(blockIndex: Int): String = timetable.endOfBlock(blockIndex)

    /** 第 [period] 节落在第几个块（0 起）；越界则夹到最近的块 */
    fun blockIndexOf(period: Int): Int = timetable.blockIndexOf(period)

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
