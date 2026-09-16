package com.tof.manycourse

import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.countWeekCourses
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 「我的」页课程统计的纯逻辑回归测试。
 *
 * 背景（修的就是这个 bug）：以前「本周课程」直接取 `CourseRepository.courses.size`，
 * 那是**整学期**的课表条数 —— 一门只在第 2-4 周上的课也算进去，和用户在本周课表上
 * 数出来的门数对不上。现在改成"含今天的那一周里，七天各有多少门课，加起来"，
 * 与课表页/日历页走同一个取值函数。
 *
 * 这里不需要（也不该）碰真实仓库：`countWeekCourses` 的取值函数是参数，
 * 所以"某一天有没有数据"可以直接摆出来。
 */
class CourseStatsTest {

    private fun entry(name: String) = CourseEntry.Local(
        Course(
            id = name.hashCode().toLong(),
            name = name,
            teacher = "某老师",
            room = "教学楼 A-101",
            weekday = 1,
            startPeriod = 1,
            periodCount = 2,
        )
    )

    /** 2026-09-14 是周一，这一周是 09-14 ~ 09-20 */
    private val monday = LocalDate.of(2026, 9, 14)

    private val week = (0L..6L).map { monday.plusDays(it) }

    @Test
    fun weekCountSumsEveryDayOfTheWeek() {
        val byDate = mapOf(
            monday to listOf(entry("高等数学"), entry("大学英语")),
            monday.plusDays(2) to listOf(entry("数据结构")),
            monday.plusDays(6) to listOf(entry("周日选修")),
        )

        assertEquals(4, countWeekCourses(week) { byDate[it] })
    }

    @Test
    fun daysWithoutDataCountZeroInsteadOfBlowingUp() {
        // 只有周一有数据：其余六天取不到（null）就是 0，不能让整页"我的"崩掉
        val byDate = mapOf(monday to listOf(entry("高等数学")))

        assertEquals(1, countWeekCourses(week) { byDate[it] })
    }

    @Test
    fun emptyListMeansNoClassAndIsNotTheSameAsMissingData() {
        // 空表 = 拿到了数据、那天确实没课 → 计 0 门，但**算进了统计**（不是"没数据"）
        val emptyDays = countWeekCourses(week) { emptyList() }
        assertEquals(0, emptyDays)

        val missingDays = countWeekCourses(week) { null }
        assertEquals("两种情况的数字都是 0，区别只在于「有没有数据」这件事本身", 0, missingDays)
    }

    @Test
    fun oneCourseOnEveryDayCountsSeven() {
        // 同一门课一周上 7 天就是 7 条 —— 统计的是"一周里的课时条目"，
        // 与课表页"共 N 门课程"的口径完全一致（那边也是按条目数的）
        val everyday = countWeekCourses(week) { listOf(entry("每天都有")) }
        assertEquals(7, everyday)
    }
}
