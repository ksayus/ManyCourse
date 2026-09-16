package com.tof.manycourse

import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.data.buildDayEntries
import com.tof.manycourse.data.dateOfCurrentWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「某一天上什么课」的纯逻辑回归测试。
 *
 * 这是**课表页与日历页共用**的那一个函数（`coursesOfDate` 的纯逻辑部分）。
 * 它错了不会崩，只会让两页显示得不一样、或者在没有数据的月份里画出一堆编出来的课 ——
 * 所以每条规则都钉在这里。
 */
class CourseEntriesTest {

    private fun schoolCourse(
        name: String,
        weekday: Int,
        startPeriod: Int,
        weeks: String = "",
    ) = SchoolCourse(
        name = name,
        teacher = "某老师",
        room = "笃行楼T T301",
        weekday = weekday,
        startPeriod = startPeriod,
        periodCount = 2,
        weeks = weeks,
    )

    private fun localCourse(
        id: Long,
        name: String,
        weekday: Int,
        startPeriod: Int,
    ) = Course(
        id = id,
        name = name,
        teacher = "我",
        room = "自习室",
        weekday = weekday,
        startPeriod = startPeriod,
        periodCount = 2,
    )

    @Test
    fun weekDataWinsAndIsSortedByPeriod() {
        val entries = buildDayEntries(
            weekCourses = listOf(
                schoolCourse("军事理论", weekday = 1, startPeriod = 7, weeks = "3周"),
                schoolCourse("大学英语", weekday = 1, startPeriod = 1, weeks = "3周"),
            ),
            ownCourses = emptyList(),
            localCourses = listOf(localCourse(1, "整学期才有的课", weekday = 1, startPeriod = 3)),
            allowLocalFallback = true,
        )

        assertEquals(2, entries?.size)
        assertEquals("应按节次升序", "大学英语", (entries!![0] as CourseEntry.Week).course.name)
        assertEquals("军事理论", (entries[1] as CourseEntry.Week).course.name)
        assertTrue(
            "有按周数据时**不能**混进本地课表里的其他课 —— 那正是「课表页有、日历页没有」的来源",
            entries.none { it is CourseEntry.Local },
        )
    }

    @Test
    fun manualCoursesRideAlongWithWeekData() {
        // 用户自己加的课不属于教务系统、没有周次概念，有按周数据时也必须显示，
        // 否则"添加课程"加完就看不见了
        val entries = buildDayEntries(
            weekCourses = listOf(schoolCourse("军事理论", weekday = 3, startPeriod = 3)),
            ownCourses = listOf(localCourse(9, "我加的课", weekday = 3, startPeriod = 5)),
            localCourses = emptyList(),
            allowLocalFallback = false,
        )

        assertEquals(2, entries?.size)
        assertEquals("教务系统的课在前", "军事理论", (entries!![0] as CourseEntry.Week).course.name)
        assertEquals("用户加的课在后", "我加的课", (entries[1] as CourseEntry.Local).course.name)
    }

    @Test
    fun withoutWeekDataOnlyTheCurrentWeekFallsBackToTheLocalTable() {
        // 本周：允许本地兜底（那一周本来就是"现在真实在上的课"）
        val inCurrentWeek = buildDayEntries(
            weekCourses = null,
            ownCourses = emptyList(),
            localCourses = listOf(localCourse(1, "高等数学", weekday = 2, startPeriod = 1)),
            allowLocalFallback = true,
        )
        assertEquals(1, inCurrentWeek?.size)
        assertEquals("高等数学", (inCurrentWeek!![0] as CourseEntry.Local).course.name)

        // 其他周：**留空**。硬兜底等于把本周的课循环画满整个学期
        val outside = buildDayEntries(
            weekCourses = null,
            ownCourses = emptyList(),
            localCourses = listOf(localCourse(1, "高等数学", weekday = 2, startPeriod = 1)),
            allowLocalFallback = false,
        )
        assertNull("没有按周数据又不在本周时必须返回 null（而不是空表）", outside)
    }

    @Test
    fun emptyListMeansNoClassWhileNullMeansNoData() {
        // 这两个被混成一个值的话，日历会把"这周还没加载"显示成"今天没课"
        val noClass = buildDayEntries(
            weekCourses = emptyList(),   // 拿到数据了，这一周确实没课
            ownCourses = emptyList(),
            localCourses = emptyList(),
            allowLocalFallback = true,
        )
        assertEquals(emptyList<CourseEntry>(), noClass)

        val noData = buildDayEntries(
            weekCourses = null,
            ownCourses = emptyList(),
            localCourses = emptyList(),
            allowLocalFallback = false,
        )
        assertNull(noData)
    }

    @Test
    fun outsideTheCurrentWeekNothingIsShownNotEvenManualCourses() {
        // 服务端按周数据缺失 + 不在本周 = 这一天没有任何可用数据。
        // 手加的课也一并留空，规则保持单一（"日历上只有本周有内容"），
        // 免得出现"这一周手加的课显示、教务系统的课不显示"这种半截状态。
        val entries = buildDayEntries(
            weekCourses = null,
            ownCourses = listOf(localCourse(3, "我加的课", weekday = 5, startPeriod = 9)),
            localCourses = listOf(localCourse(4, "高等数学", weekday = 5, startPeriod = 1)),
            allowLocalFallback = false,
        )
        assertNull("本周之外一律留空", entries)
    }

    @Test
    fun noWeekDataButCurrentWeekKeepsManualAndSyncedCoursesTogether() {
        // 没有按周数据（金城学院）时，本周显示的是**整个本地课表**：
        // 同步来的整学期课与手加的课都在里面，课表页与日历页看到的是同一份
        val entries = buildDayEntries(
            weekCourses = null,
            ownCourses = emptyList(),
            localCourses = listOf(
                localCourse(1, "高等数学", weekday = 5, startPeriod = 1),
                localCourse(2, "我加的课", weekday = 5, startPeriod = 9),
            ),
            allowLocalFallback = true,
        )
        assertEquals(listOf("高等数学", "我加的课"), entries?.map { (it as CourseEntry.Local).course.name })
    }

    // ── 课表页的"星期几 → 日期"换算（两页对齐的关键）──────────────────────

    @Test
    fun dateOfCurrentWeekMapsEveryWeekdayToThatSameWeek() {
        // 2026-09-16 是周三
        val today = LocalDate.of(2026, 9, 16)
        val monday = LocalDate.of(2026, 9, 14)

        (1..7).forEach { weekday ->
            val date = dateOfCurrentWeek(weekday, today)
            assertEquals("周几就必须落在周几", weekday, date.dayOfWeek.value)
            assertTrue("$date 必须在本周内", date in monday..monday.plusDays(6))
        }
        assertEquals("点『周三』应当就是今天", today, dateOfCurrentWeek(3, today))
        assertEquals(monday, dateOfCurrentWeek(1, today))
        assertEquals(LocalDate.of(2026, 9, 20), dateOfCurrentWeek(7, today))
    }

    @Test
    fun dateOfCurrentWeekMatchesWhatTheCalendarPageWouldLookUp() {
        // 课表页点某个星期几，与日历页点本周对应的那一天，必须是**同一个日期** ——
        // 两页只要日期一样、取值函数又相同（coursesOfDate），显示就必然一致
        val today = LocalDate.of(2026, 9, 16)
        (1..7).forEach { weekday ->
            val fromSchedulePage = dateOfCurrentWeek(weekday, today)
            // 日历页那一格就是"本周里星期几 = weekday 的那天"
            val fromCalendarPage = (0..6)
                .map { today.minusDays((today.dayOfWeek.value - 1).toLong()).plusDays(it.toLong()) }
                .first { it.dayOfWeek.value == weekday }
            assertEquals(fromCalendarPage, fromSchedulePage)
        }
    }

    @Test
    fun dateOfCurrentWeekClampsNonsenseInput() {
        val today = LocalDate.of(2026, 9, 16)
        // 越界的星期几不能算出离谱的日期（宁可退化成周一/周日）
        assertEquals(1, dateOfCurrentWeek(0, today).dayOfWeek.value)
        assertEquals(7, dateOfCurrentWeek(99, today).dayOfWeek.value)
    }
}
