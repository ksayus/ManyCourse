package com.tof.manycourse

import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.ui.DaySlot
import com.tof.manycourse.ui.GridCard
import com.tof.manycourse.ui.buildDaySlots
import com.tof.manycourse.ui.buildGridCards
import com.tof.manycourse.ui.currentPeriodOf
import com.tof.manycourse.ui.weekIndexFor
import com.tof.manycourse.ui.weekRangeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 日历页**周视图网格**的纯逻辑回归测试（铺排 + 亮/灰判定 + 周次滑块刻度）。
 *
 * 为什么值得单测：这些错法都是"不会崩"的那种 —— 课被吞掉一门、跨节次的卡片把后面的课顶掉一行、
 * 整列高度和别的列差一格（列与列错位）、"这一周不上"的灰卡被算进了门数。
 * 真机上要一格一格数才看得出来，所以规则全部钉在这里。
 *
 * 对应实现：`ui/CalendarGridLayout.kt` 与 `data/Course.kt` 的节次时刻表。
 */
class ScheduleGridSlotsTest {

    private fun course(
        id: Long,
        name: String,
        startPeriod: Int,
        periodCount: Int,
        weekday: Int = 1,
    ) = Course(
        id = id,
        name = name,
        teacher = "某老师",
        room = "A-101",
        weekday = weekday,
        startPeriod = startPeriod,
        periodCount = periodCount,
    )

    /** 这一周真的会上（亮卡） */
    private fun vivid(
        id: Long,
        name: String,
        startPeriod: Int,
        periodCount: Int,
        weekday: Int = 1,
    ) = GridCard(CourseEntry.Local(course(id, name, startPeriod, periodCount, weekday)), dim = false)

    /** 整学期课表里有、但这一周不上（灰卡） */
    private fun dim(
        id: Long,
        name: String,
        startPeriod: Int,
        periodCount: Int,
        weekday: Int = 1,
    ) = GridCard(CourseEntry.Local(course(id, name, startPeriod, periodCount, weekday)), dim = true)

    /** 网格里的第几行 = 一张卡片的开始节次；用来断言"卡片落在时间轴的正确位置上" */
    private fun rowOf(slots: List<DaySlot>, courseName: String): Int {
        var row = 1
        slots.forEach { slot ->
            if (slot is DaySlot.Block && slot.cards.any { it.entry.name == courseName }) return row
            row += slot.periods
        }
        error("网格里没有「$courseName」这一块")
    }

    // ── 铺排：卡片落在时间轴的哪一行、占几行 ─────────────────────────────

    @Test
    fun emptyDayFillsTheWholeAxisWithOneGap() {
        assertEquals(listOf(DaySlot.Gap(10)), buildDaySlots(emptyList(), 10))
    }

    @Test
    fun twoPeriodCourseSpansTwoRows() {
        // 连上两节 → 卡片跨两行（"以卡片为课程载体"在网格里的表现）
        assertEquals(
            listOf(DaySlot.Block(listOf(vivid(1, "高等数学", 1, 2)), 2), DaySlot.Gap(8)),
            buildDaySlots(listOf(vivid(1, "高等数学", 1, 2)), 10),
        )
    }

    @Test
    fun gapsKeepEveryCourseOnItsOwnPeriodRow() {
        val slots = buildDaySlots(
            listOf(
                vivid(1, "大学英语", startPeriod = 3, periodCount = 2),
                vivid(2, "程序设计", startPeriod = 7, periodCount = 1),
            ),
            maxPeriod = 10,
        )

        assertEquals("第 3 节开始的课就必须落在第 3 行", 3, rowOf(slots, "大学英语"))
        assertEquals(7, rowOf(slots, "程序设计"))
        assertEquals("整列必须正好铺满 10 行", 10, slots.sumOf { it.periods })
    }

    @Test
    fun cardsStartingAtTheSamePeriodShareOneBlock() {
        // 同一格多张卡（课表冲突 / 亮卡与灰卡重叠）：并排显示，不能只画一张
        val slots = buildDaySlots(
            listOf(
                vivid(1, "高等数学", startPeriod = 5, periodCount = 2),
                dim(2, "大学物理", startPeriod = 5, periodCount = 2),
            ),
            maxPeriod = 10,
        )

        val block = slots.filterIsInstance<DaySlot.Block>().single()
        assertEquals(setOf("高等数学", "大学物理"), block.cards.map { it.entry.name }.toSet())
        assertEquals(2, block.periods)
        assertEquals(10, slots.sumOf { it.periods })
    }

    @Test
    fun cardStartingInsideAnotherCardIsKeptNotPushedDown() {
        // 起点落在别的卡的跨度里（数据打架）：收进同一块并排显示。
        // 若把它排到下一块，卡片会掉到第 5 行 —— 时间轴上的位置就谎报了
        val slots = buildDaySlots(
            listOf(
                vivid(1, "连上四节的实验", startPeriod = 1, periodCount = 4),
                vivid(2, "插在中间的一节", startPeriod = 2, periodCount = 1),
            ),
            maxPeriod = 10,
        )

        val block = slots.filterIsInstance<DaySlot.Block>().single()
        assertEquals(1, rowOf(slots, "连上四节的实验"))
        assertEquals(1, rowOf(slots, "插在中间的一节"))
        assertEquals(4, block.periods)
        assertEquals(10, slots.sumOf { it.periods })
    }

    @Test
    fun everyCardIsPlacedExactlyOnce() {
        // 完整性：无论怎么铺，卡片一张都不能少（少一张是"用户以为课没了"的错，最难发现）
        val cards = listOf(
            vivid(1, "A", startPeriod = 1, periodCount = 1),
            vivid(2, "B", startPeriod = 2, periodCount = 3),
            vivid(3, "C", startPeriod = 4, periodCount = 2),
            dim(4, "D", startPeriod = 9, periodCount = 2),
            dim(5, "E", startPeriod = 10, periodCount = 1),
        )

        val placed = buildDaySlots(cards, maxPeriod = 10)
            .filterIsInstance<DaySlot.Block>()
            .flatMap { it.cards }

        assertEquals("每一张卡都必须出现在网格里", cards.toSet(), placed.toSet())
        assertEquals("而且只能出现一次", cards.size, placed.size)
    }

    @Test
    fun dirtyPeriodNumbersAreClampedInsteadOfDropped() {
        // 教务系统解析出的脏数据（第 20 节 / 连上 99 节）不能让课从网格里消失：
        // 先夹到合法范围再铺排（行数上限见 CalendarGridLayout 的 MaxGridRows）
        val slots = buildDaySlots(
            listOf(
                vivid(1, "越界的课", startPeriod = 20, periodCount = 1),
                vivid(2, "超长的课", startPeriod = 1, periodCount = 99),
            ),
            maxPeriod = 10,
        )

        assertEquals(10, slots.sumOf { it.periods })
        val names = slots.filterIsInstance<DaySlot.Block>().flatMap { it.cards }.map { it.entry.name }
        assertEquals(setOf("越界的课", "超长的课"), names.toSet())
    }

    @Test
    fun columnHeightAlwaysMatchesTheAxis() {
        // 列高必须恒等于时间轴的行数：差一格，列与列就会错位（横线也对不上课）
        val cases = listOf(
            emptyList(),
            listOf(vivid(1, "A", 1, 1)),
            listOf(vivid(1, "A", 10, 1)),
            listOf(vivid(1, "A", 5, 4), dim(2, "B", 1, 2), vivid(3, "C", 8, 9)),
        )
        listOf(1, 5, 10).forEach { maxPeriod ->
            cases.forEach { cards ->
                assertEquals(
                    "maxPeriod=$maxPeriod 时列高必须正好铺满",
                    maxPeriod,
                    buildDaySlots(cards, maxPeriod).sumOf { it.periods },
                )
            }
        }
    }

    @Test
    fun nonsenseAxisLengthYieldsNothing() {
        assertEquals(emptyList<DaySlot>(), buildDaySlots(listOf(vivid(1, "A", 1, 1)), maxPeriod = 0))
    }

    // ── 亮的 / 灰的：换周"只改颜色、不动布局"靠这一段 ──────────────────

    @Test
    fun courseRunningThisWeekLightsUpOnTheCanvas() {
        val cards = buildGridCards(
            real = listOf(CourseEntry.Local(course(1, "高等数学", 1, 2))),
            semesterCourses = listOf(course(1, "高等数学", 1, 2)),
        )

        assertEquals(1, cards.size)
        assertFalse("这一周真的会上 → 亮卡", cards.single().dim)
    }

    @Test
    fun courseOutsideThisWeekStaysOnTheCanvasButGoesDim() {
        val cards = buildGridCards(
            real = listOf(CourseEntry.Local(course(1, "军训", 1, 2))),
            semesterCourses = listOf(
                course(1, "军训", 1, 2),        // 这周会上 → 亮
                course(2, "高等数学", 5, 2),    // 这周不上 → 灰，但**位置留着**
            ),
        )

        assertEquals(listOf("军训", "高等数学"), cards.map { it.entry.name })
        assertEquals(listOf(false, true), cards.map { it.dim })
    }

    @Test
    fun swappedSlotShowsTheCanvasCardDimPlusTheRealOneLit() {
        // 这周这一节换成别的课了（军训替课）：画布那张灰着留在原位，真实那张亮着并排显示 ——
        // 比"把画布那张点亮"诚实（否则会显示成"这周上高等数学"）
        val cards = buildGridCards(
            real = listOf(CourseEntry.Local(course(1, "军训", 1, 2))),
            semesterCourses = listOf(course(2, "高等数学", 1, 2)),
        )

        assertEquals(listOf("高等数学", "军训"), cards.map { it.entry.name })
        assertEquals(listOf(true, false), cards.map { it.dim })
    }

    @Test
    fun weekWithoutAuthoritativeDataKeepsTheWholeCanvasDim() {
        // 这一周没有权威数据（正在拉 / 不支持按周查）：画布整块灰着，位置保留 ——
        // 不能把"不知道上不上"画成"确定会上"
        val cards = buildGridCards(
            real = null,
            semesterCourses = listOf(course(1, "高等数学", 1, 2), course(2, "大学英语", 3, 2)),
        )

        assertEquals(listOf("高等数学", "大学英语"), cards.map { it.entry.name })
        assertTrue("没有权威数据时一张亮卡都不该有", cards.all { it.dim })
    }

    @Test
    fun switchingWeeksKeepsTheSameCardsAndOnlyFlipsTheLitFlag() {
        // ★ 这条钉的就是"换周不用重新渲染"：同一张画布、不同周次的权威数据，
        //   卡片的构成与**顺序**必须逐项相同（Compose 才会复用同一批节点），变的只有 dim
        val canvas = listOf(
            course(1, "高等数学", 1, 2),
            course(2, "大学英语", 3, 2),
            course(3, "程序设计", 5, 2),
        )
        val weekA = buildGridCards(
            real = listOf(
                CourseEntry.Local(course(1, "高等数学", 1, 2)),
                CourseEntry.Local(course(3, "程序设计", 5, 2)),
            ),
            semesterCourses = canvas,
        )
        val weekB = buildGridCards(
            real = listOf(CourseEntry.Local(course(2, "大学英语", 3, 2))),
            semesterCourses = canvas,
        )

        assertEquals(
            "换周时卡片构成与顺序必须完全一致（否则就是「重新渲染」）",
            weekA.map { it.entry },
            weekB.map { it.entry },
        )
        assertEquals(listOf(false, true, false), weekA.map { it.dim })
        assertEquals(listOf(true, false, true), weekB.map { it.dim })
    }

    // ── 周次滑块的刻度 ─────────────────────────────────────────────────

    private val weeks = listOf(
        SchoolWeek(index = 3, start = LocalDate.of(2026, 9, 14), end = LocalDate.of(2026, 9, 20)),
        SchoolWeek(index = 4, start = LocalDate.of(2026, 9, 21), end = LocalDate.of(2026, 9, 27)),
        SchoolWeek(index = 5, start = LocalDate.of(2026, 9, 28), end = LocalDate.of(2026, 10, 4)),
    )

    @Test
    fun sliderIndexMapsAWeekToItsTeachingWeek() {
        assertEquals(0, weekIndexFor(LocalDate.of(2026, 9, 14), weeks))
        assertEquals(1, weekIndexFor(LocalDate.of(2026, 9, 21), weeks))
        // 周内的任何一天都指向同一格刻度（滑块按"周"吸附，不按天）
        assertEquals(2, weekIndexFor(LocalDate.of(2026, 10, 2), weeks))
    }

    @Test
    fun sliderHasNoPositionOutsideTheTeachingWeeks() {
        // 放假 / 还没开学 / 本校给不出按周数据 → 滑块没有落点（调用方据此不画滑块）
        assertNull(weekIndexFor(LocalDate.of(2026, 8, 31), weeks))
        assertNull(weekIndexFor(LocalDate.of(2027, 3, 1), weeks))
        assertNull(weekIndexFor(LocalDate.of(2026, 9, 14), emptyList()))
    }

    @Test
    fun weekRangeLabelHidesTheRepeatedMonth() {
        assertEquals("9月14日 ~ 20日", weekRangeLabel(LocalDate.of(2026, 9, 14)))
        assertEquals("跨月时两段都要写", "8月31日 ~ 9月6日", weekRangeLabel(LocalDate.of(2026, 8, 31)))
    }

    // ── 时间轴：节次 → 起止时刻 ────────────────────────────────────────

    @Test
    fun axisShowsEachPeriodsStartAndEnd() {
        // 课表只给了开始时刻，结束时刻按 CourseRepository.PERIOD_MINUTES 推算：
        // 相邻节次相差 55 分钟 = 45 分钟正课 + 10 分钟课间，与时刻表自洽
        assertEquals("8:00~8:45", CourseRepository.periodRangeLabel(1))
        assertEquals("8:55~9:40", CourseRepository.periodRangeLabel(2))
        assertEquals("10:10~10:55", CourseRepository.periodRangeLabel(3))
        assertEquals("19:55~20:40", CourseRepository.periodRangeLabel(10))
        assertEquals("越界的节次没有刻度文案", "", CourseRepository.periodRangeLabel(0))
        assertEquals("", CourseRepository.periodRangeLabel(99))
    }

    // ── "现在上到第几节" ───────────────────────────────────────────────

    @Test
    fun currentPeriodFollowsThePeriodStartTimes() {
        val days = (0L..6L).map { LocalDate.of(2026, 9, 14).plusDays(it) }
        val tuesday = LocalDate.of(2026, 9, 15)

        // 7:30 还没到第 1 节（8:00）
        assertNull(currentPeriodOf(tuesday, days, maxPeriod = 10, now = LocalTime.of(7, 30)))
        // 8:00 上第 1 节；9:30 第 2 节已开始（8:55）
        assertEquals(1, currentPeriodOf(tuesday, days, maxPeriod = 10, now = LocalTime.of(8, 0)))
        assertEquals(2, currentPeriodOf(tuesday, days, maxPeriod = 10, now = LocalTime.of(9, 30)))
        // 10:10 是第 3 节
        assertEquals(3, currentPeriodOf(tuesday, days, maxPeriod = 10, now = LocalTime.of(10, 10)))
    }

    @Test
    fun currentPeriodIsHiddenWhenTodayIsNotOnTheGrid() {
        // 翻到别的周时不画"现在"：那一行在今天之外，画上去只会误导
        val days = (0L..6L).map { LocalDate.of(2026, 9, 14).plusDays(it) }
        val nextMonth = LocalDate.of(2026, 10, 6)
        assertNull(currentPeriodOf(nextMonth, days, maxPeriod = 10, now = LocalTime.of(10, 30)))
    }
}
