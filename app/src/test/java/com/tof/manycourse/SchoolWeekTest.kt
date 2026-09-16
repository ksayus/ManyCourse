package com.tof.manycourse

import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.data.naturalWeekOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 教学周（服务端给的）与自然周（没有服务端数据时的退路）的纯逻辑测试。
 *
 * 这两个概念错一个，日历就会"看着有课其实没有"：
 *  - [SchoolWeek].contains 错了 → 某一天被算进错误的教学周，画上那一周的课；
 *  - [naturalWeekOf] 错了 → 金城学院那种"只兜底本周"的规则会在错误的日期上兜底，
 *    等于又回到"把本周的课画满整个日历"。
 */
class SchoolWeekTest {

    private val week3 = SchoolWeek(
        index = 3,
        start = LocalDate.of(2026, 9, 14),   // 周一
        end = LocalDate.of(2026, 9, 20),     // 周日
    )

    @Test
    fun containsIsInclusiveOnBothEnds() {
        assertTrue("周一要算在里面", week3.contains(LocalDate.of(2026, 9, 14)))
        assertTrue("周日要算在里面", week3.contains(LocalDate.of(2026, 9, 20)))
        assertTrue(week3.contains(LocalDate.of(2026, 9, 17)))
        assertFalse("前一天不属于这一周", week3.contains(LocalDate.of(2026, 9, 13)))
        assertFalse("后一天不属于这一周", week3.contains(LocalDate.of(2026, 9, 21)))
    }

    @Test
    fun labelShowsWeekNumberAndDateRange() {
        assertEquals("第3周 09-14 ~ 09-20", week3.label)
        // 跨月/跨年也不能崩（0104 ~ 0110 这种）
        assertEquals(
            "第19周 01-04 ~ 01-10",
            SchoolWeek(19, LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 10)).label,
        )
    }

    @Test
    fun naturalWeekAlwaysStartsOnMondayAndEndsOnSunday() {
        // 2026-09-16 是周三
        val week = naturalWeekOf(LocalDate.of(2026, 9, 16))
        assertEquals(LocalDate.of(2026, 9, 14), week.start)
        assertEquals(LocalDate.of(2026, 9, 20), week.endInclusive)
        assertEquals(DayOfWeek.MONDAY, week.start.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, week.endInclusive.dayOfWeek)
    }

    @Test
    fun naturalWeekIsStableForEveryDayInsideIt() {
        // 同一个自然周里的七天必须得到同一个区间 —— 否则"本周"会在周末前后跳变
        val expected = naturalWeekOf(LocalDate.of(2026, 9, 16))
        (14..20).forEach { day ->
            assertEquals(
                "9月${day}日应当落在同一个自然周里",
                expected,
                naturalWeekOf(LocalDate.of(2026, 9, day)),
            )
        }
        // 越界的两个日子必须落到相邻周
        assertFalse(LocalDate.of(2026, 9, 13) in expected)
        assertFalse(LocalDate.of(2026, 9, 21) in expected)
    }

    @Test
    fun naturalWeekHandlesMonthAndYearBoundaries() {
        // 2027-01-01 是周五 → 那一周从 2026-12-28（周一）开始，跨了年
        val newYear = naturalWeekOf(LocalDate.of(2027, 1, 1))
        assertEquals(LocalDate.of(2026, 12, 28), newYear.start)
        assertEquals(LocalDate.of(2027, 1, 3), newYear.endInclusive)

        // 月末：2026-09-30 是周三 → 09-28 ~ 10-04，跨了月
        val monthEnd = naturalWeekOf(LocalDate.of(2026, 9, 30))
        assertEquals(LocalDate.of(2026, 9, 28), monthEnd.start)
        assertEquals(LocalDate.of(2026, 10, 4), monthEnd.endInclusive)
    }

    @Test
    fun naturalWeekCoversExactlySevenDays() {
        // 兜底范围多一天少一天都会让日历多画/少画一格
        val week = naturalWeekOf(LocalDate.of(2026, 9, 16))
        var count = 0
        var cursor = week.start
        while (cursor <= week.endInclusive) {
            count++
            cursor = cursor.plusDays(1)
        }
        assertEquals(7, count)
    }
}
