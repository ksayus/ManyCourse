package com.tof.manycourse.data

import java.time.LocalDate

/** 「周几」的中文标签（下标 0 = 周一）。课表页 Chip、添加课程、课程详情共用这一份 */
val WeekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 星期几 → 中文标签；越界返回空串（宁可空着，也不要因为一个脏数据把整页崩掉）*/
fun weekdayLabel(weekday: Int): String = WeekdayLabels.getOrElse(weekday - 1) { "" }

/**
 * 某一天里的一门课：**要么来自教务系统的"按教学周"数据，要么来自本地课表**。
 *
 * 为什么要分两种而不是统一塞进 [Course]：
 *  - [Week] 那门课**不是本地课程** —— 它没有本地自增 id、不该被写进
 *    `CourseRepository`（那是"用户自己的课表"），也删不掉（下次查又会回来）；
 *  - [Local] 那门课有本地 id，可以长按删除。
 *
 * 混成一个模型之后，"长按删除"就得靠猜这门课能不能删。
 */
sealed interface CourseEntry {

    /** 卡片高亮、列表去重用的稳定键 */
    val key: String

    /** 星期几（1=周一…7=周日）*/
    val weekday: Int

    /** 展示用："第3-4节" */
    val periodLabel: String

    /** 周次串（如 `3周` / `4-19周`），可能为空 */
    val weeks: String

    /** 这节课几点开始（`H:MM`）*/
    val startTime: String

    /** 课程名 —— 卡片/网格格子里的标题 */
    val name: String

    /** 地点（教务系统的课是「教室 + 校区」）*/
    val room: String

    /**
     * 开始节次（第 1 节起）。
     *
     * 课程卡片列表用不上它（列表本来就按节次排好了），但**日历页的日程网格**要用它
     * 把卡片摆到时间轴的第几行上，所以这里是两种来源的公共口径。
     */
    val startPeriod: Int

    /** 连上几节：网格里这张卡片要跨几行 */
    val periodCount: Int

    /** 教务系统"周次课表"接口给的那一课（只属于这一周）*/
    data class Week(val course: SchoolCourse) : CourseEntry {
        override val key: String get() = "week:${course.name}:${course.startPeriod}"
        override val weekday: Int get() = course.weekday
        override val periodLabel: String get() = course.periodLabel
        override val weeks: String get() = course.weeks
        override val startTime: String get() = CourseRepository.periodTime(course.startPeriod)
        override val name: String get() = course.name
        override val room: String get() = course.fullRoom
        override val startPeriod: Int get() = course.startPeriod
        override val periodCount: Int get() = course.periodCount
    }

    /** 本地课表里的那一课（同步来的整学期课，或用户自己加的课）*/
    data class Local(val course: Course) : CourseEntry {
        override val key: String get() = "local:${course.id}"
        override val weekday: Int get() = course.weekday
        override val periodLabel: String get() = course.periodLabel
        override val weeks: String get() = course.weeks
        override val startTime: String get() = course.startTime
        override val name: String get() = course.name
        override val room: String get() = course.room
        override val startPeriod: Int get() = course.startPeriod
        override val periodCount: Int get() = course.periodCount
    }
}

/**
 * ★ **课表页与日历页共用的"某一天上什么课"**（唯一实现）。
 *
 * 两页各自实现一遍的话，迟早会出现"课表页有课、日历页没课"这种对不上的情况 ——
 * 所以从数据源到兜底规则全部收在这一个函数里，两页只是渲染方式不同。
 *
 * ## 规则
 *
 * ```
 * 这一天所在的教学周有"按周"数据（广软）
 *        → 教务系统给的课（★ 服务端已经筛过"这一周真的会上"）
 *        + 用户自己在 App 里加的课（它们没有周次概念，任何一周都显示）
 *
 * 没有按周数据，但这一天在本周（含今天的那一个周一到周日）
 *        → 本地课表（整学期的课按星期几循环）——
 *          本周本来就是"现在真实在上的课"，拿本地兜底不会骗人
 *
 * 没有按周数据，而且这一天不在本周
 *        → null（**留空**）。宁可不显示，也不把本周的课循环画满整个学期
 * ```
 *
 * @return null = 这一天**没有任何可用数据**（调用方应显示"暂无课表数据"，
 *   而不是"当日无课"）。空表 = 有数据，那天确实没课。两者必须分开。
 */
fun coursesOfDate(date: LocalDate): List<CourseEntry>? {
    val weekday = date.dayOfWeek.value
    return buildDayEntries(
        // 服务端按周给的课：null = 这一周没有按周数据（不支持按周查 / 还没拉到）
        weekCourses = WeekScheduleStore.coursesOn(date),
        // 用户自己加的课：没有周次概念，任何一周都跟着显示
        ownCourses = CourseRepository.manualCourses.filter { it.weekday == weekday },
        // 本地课表：整学期的课按星期几循环，只在"本周"允许兜底
        localCourses = CourseRepository.coursesOn(date),
        allowLocalFallback = date in naturalWeekOf(LocalDate.now()),
    )
}

/**
 * ★ **「我的」页的「本周课程」门数**。
 *
 * 为什么不能用 `CourseRepository.courses.size`（旧实现）：
 * 那里的 `courses` 是**教务系统拉回来的整学期课表**，而卡片上写的是"本周课程" ——
 * 数字会比用户在本周课表上看到的课多出一大截（一门只上 2-4 周的课也被算进来，
 * 而且拿不到"这一周真的会上哪些课"这个信息）。用户一眼就能看出对不上。
 *
 * 所以这里走**和课表页/日历页同一个取值函数**（[coursesOfDate]）：
 * 含今天的那一个自然周里，七天各有多少门课，加起来就是本周课程数。
 * 本地兜底只在这一周生效，所以这七天不会返回 null；真取不到就按 0 记，
 * 宁可少算一门，也不要抛异常把整页"我的"打挂。
 */
fun currentWeekCourseCount(today: LocalDate = LocalDate.now()): Int {
    // ClosedRange<LocalDate> 本身不可迭代，这里显式展开成周一到周日那七天
    val week = naturalWeekOf(today)
    val days = (0L..6L).map { week.start.plusDays(it) }
    return countWeekCourses(days) { coursesOfDate(it) }
}

/** 「我的」页的「今日课程」门数 —— 同样与课表页/日历页同源 */
fun todayCourseCount(today: LocalDate = LocalDate.now()): Int =
    coursesOfDate(today)?.size ?: 0

/**
 * [currentWeekCourseCount] 的**纯逻辑部分**（不碰任何仓库/状态，可单测）。
 *
 * @param dates 要统计的日期（一般是含今天的那一个自然周）
 * @param entriesOf 取某一天课程的函数；**返回 null 表示这一天没有可用数据**，
 *   不计入门数（而不是当成"0 门"混进去 —— 展示上两者一样，但取不到数据时
 *   "少算一天"和"那天确实没课"是两回事，别把前者说成后者）
 */
internal fun countWeekCourses(
    dates: Iterable<LocalDate>,
    entriesOf: (LocalDate) -> List<CourseEntry>?,
): Int = dates.sumOf { entriesOf(it)?.size ?: 0 }

/**
 * [coursesOfDate] 的**纯逻辑部分**（不碰任何仓库/状态，可单测）。
 *
 * 拆出来的原因很实际：这一段要是错了，表现是"课表页和日历页显示得不一样"
 * 或者"翻到没数据的月份看到编出来的课"—— 都是不会崩、只会让人不信任 App 的错。
 * 拆开就能用普通 JVM 单测钉住（真机跑一遍 UI 是钉不住这些边角的）。
 *
 * @param weekCourses 服务端按周给的课；**null = 这一周没有按周数据**
 * @param ownCourses  用户自己在 App 里加的课（调用方已按当天筛过）
 * @param localCourses 本地课表里当天的课（调用方已按当天筛过）
 * @param allowLocalFallback 这一天允不允许用本地课表兜底（= 含今天的那一个自然周）
 * @return null = 没有任何可用数据（调用方显示"暂无课表数据"）；空表 = 有数据但那天没课
 */
internal fun buildDayEntries(
    weekCourses: List<SchoolCourse>?,
    ownCourses: List<Course>,
    localCourses: List<Course>,
    allowLocalFallback: Boolean,
): List<CourseEntry>? {
    val own = ownCourses.sortedBy { it.startPeriod }.map { CourseEntry.Local(it) }

    if (weekCourses != null) {
        // 教务系统的课在前（它是权威数据），用户自己加的课跟在后面
        return weekCourses.sortedBy { it.startPeriod }.map { CourseEntry.Week(it) } + own
    }

    if (!allowLocalFallback) return null
    return localCourses.sortedBy { it.startPeriod }.map { CourseEntry.Local(it) }
}

/** 同一天的课里，"当前时间之后的第一门"的 [CourseEntry.key]（用于高亮提示下一节课）*/
fun nextCourseKey(entries: List<CourseEntry>, now: java.time.LocalTime): String? =
    entries.firstOrNull { entry ->
        val time = runCatching { java.time.LocalTime.parse(entry.startTime.padStart(5, '0')) }
            .getOrNull()
        time == null || time >= now
    }?.key

/**
 * 本周里"星期几 = [weekday]"的那一天。
 *
 * 课表页是按**星期几**选的（周一~周日七个 Chip），但它必须和日历页一样**按日期取课**
 * （见 [coursesOfDate]），否则两页永远对不上。这一步就是那个换算：
 *
 * ```
 *   dateOfCurrentWeek(1)  = 本周的周一
 *   dateOfCurrentWeek(today.dayOfWeek.value) = 今天
 * ```
 *
 * 注意这里的"本周"是**自然周**（周一开头），不是教学周 —— 教学周可能因为调休而错位，
 * 但那属于"这一周到底上不上课"的问题，由 `WeekScheduleStore` 的数据本身决定；
 * 这里只需要回答"用户点了周三，指的是哪一天"。
 */
fun dateOfCurrentWeek(weekday: Int, today: LocalDate = LocalDate.now()): LocalDate {
    val safeWeekday = weekday.coerceIn(1, 7)
    return today.plusDays((safeWeekday - today.dayOfWeek.value).toLong())
}
