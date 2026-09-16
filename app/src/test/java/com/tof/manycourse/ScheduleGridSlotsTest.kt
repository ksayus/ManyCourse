package com.tof.manycourse

import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.ui.DaySlot
import com.tof.manycourse.ui.GridCard
import com.tof.manycourse.ui.blockOf
import com.tof.manycourse.ui.blocksOf
import com.tof.manycourse.ui.buildDaySlots
import com.tof.manycourse.ui.buildGridCards
import com.tof.manycourse.ui.currentPeriodOf
import com.tof.manycourse.ui.gridRowCount
import com.tof.manycourse.ui.weekIndexFor
import com.tof.manycourse.ui.weekRangeLabel
import com.tof.manycourse.ui.weekStepTarget
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

    /** 网格里的第几行（一行两节）= 一张卡片所在的块；用来断言"卡片落在时间轴的正确位置上" */
    private fun rowOf(slots: List<DaySlot>, courseName: String): Int {
        var row = 1
        slots.forEach { slot ->
            if (slot is DaySlot.Block && slot.cards.any { it.entry.name == courseName }) return row
            row += slot.rows
        }
        error("网格里没有「$courseName」这一块")
    }

    // ── 铺排：卡片落在时间轴的哪一行、占几行 ─────────────────────────────

    @Test
    fun emptyDayFillsTheWholeAxisWithOneGap() {
        assertEquals(listOf(DaySlot.Gap(5)), buildDaySlots(emptyList(), 5))
    }

    @Test
    fun twoPeriodCourseTakesExactlyOneRow() {
        // 一行 = 两节：连上两节的课正好占满第一行（"以两节为一个节点"）
        assertEquals(
            listOf(DaySlot.Block(listOf(vivid(1, "高等数学", 1, 2)), 1), DaySlot.Gap(4)),
            buildDaySlots(listOf(vivid(1, "高等数学", 1, 2)), 5),
        )
    }

    @Test
    fun fourPeriodCourseTakesTwoRows() {
        // 连上四节（1-4）跨两行：一行两节，所以它占 1-2 与 3-4 两行
        assertEquals(
            listOf(DaySlot.Block(listOf(vivid(1, "连上四节的实验", 1, 4)), 2), DaySlot.Gap(3)),
            buildDaySlots(listOf(vivid(1, "连上四节的实验", 1, 4)), 5),
        )
    }

    @Test
    fun gapsKeepEveryCourseOnItsOwnBlockRow() {
        val slots = buildDaySlots(
            listOf(
                vivid(1, "大学英语", startPeriod = 5, periodCount = 2),
                vivid(2, "程序设计", startPeriod = 9, periodCount = 2),
            ),
            rowCount = 5,
        )

        assertEquals("第 5-6 节 = 第 3 行", 3, rowOf(slots, "大学英语"))
        assertEquals("第 9-10 节 = 第 5 行", 5, rowOf(slots, "程序设计"))
        assertEquals("整列必须正好铺满 5 行", 5, slots.sumOf { it.rows })
    }

    @Test
    fun cardsStartingAtTheSameRowShareOneBlock() {
        // 同一格多张卡（课表冲突 / 亮卡与灰卡重叠）：收进同一块，渲染时叠在一起
        val slots = buildDaySlots(
            listOf(
                vivid(1, "高等数学", startPeriod = 5, periodCount = 2),
                dim(2, "大学物理", startPeriod = 5, periodCount = 2),
            ),
            rowCount = 5,
        )

        val block = slots.filterIsInstance<DaySlot.Block>().single()
        assertEquals(setOf("高等数学", "大学物理"), block.cards.map { it.entry.name }.toSet())
        assertEquals(1, block.rows)
        assertEquals(5, slots.sumOf { it.rows })
    }

    @Test
    fun cardStartingInsideAnotherCardIsKeptNotPushedDown() {
        // 起点落在别的卡的跨度里（数据打架）：收进同一块叠着放。
        // 若把它排到下一块，卡片会掉到下一行 —— 时间轴上的位置就谎报了
        val slots = buildDaySlots(
            listOf(
                vivid(1, "连上四节的实验", startPeriod = 1, periodCount = 4),
                vivid(2, "插在中间的一节", startPeriod = 3, periodCount = 1),
            ),
            rowCount = 5,
        )

        val block = slots.filterIsInstance<DaySlot.Block>().single()
        assertEquals(1, rowOf(slots, "连上四节的实验"))
        assertEquals(1, rowOf(slots, "插在中间的一节"))
        assertEquals(2, block.rows)
        assertEquals(5, slots.sumOf { it.rows })
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

        val placed = buildDaySlots(cards, rowCount = 5)
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
            rowCount = 5,
        )

        assertEquals(5, slots.sumOf { it.rows })
        val names = slots.filterIsInstance<DaySlot.Block>().flatMap { it.cards }.map { it.entry.name }
        assertEquals(setOf("越界的课", "超长的课"), names.toSet())
    }

    @Test
    fun columnHeightAlwaysMatchesTheAxis() {
        // 列高必须恒等于时间轴的行数：差一行，列与列就会错位（横线也对不上课）
        val cases = listOf(
            emptyList(),
            listOf(vivid(1, "A", 1, 1)),
            listOf(vivid(1, "A", 10, 1)),
            listOf(vivid(1, "A", 5, 4), dim(2, "B", 1, 2), vivid(3, "C", 8, 9)),
        )
        listOf(1, 3, 5).forEach { rowCount ->
            cases.forEach { cards ->
                assertEquals(
                    "rowCount=$rowCount 时列高必须正好铺满",
                    rowCount,
                    buildDaySlots(cards, rowCount).sumOf { it.rows },
                )
            }
        }
    }

    @Test
    fun rowsAreTwoPeriodsEach() {
        // "每两个节次为一个节点"：行号 = (节次 - 1) / 2，行数 = 行号 + 1
        assertEquals(0, blockOf(1))
        assertEquals(0, blockOf(2))
        assertEquals(1, blockOf(3))
        assertEquals(1, blockOf(4))
        assertEquals(4, blockOf(9))
        assertEquals(4, blockOf(10))
        assertEquals(5, blocksOf(10))
        assertEquals("越界的节次也不能算出负行号", 0, blockOf(0))
    }

    @Test
    fun nonsenseAxisLengthYieldsNothing() {
        assertEquals(emptyList<DaySlot>(), buildDaySlots(listOf(vivid(1, "A", 1, 1)), rowCount = 0))
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
    fun swipingMovesAlongTheTeachingWeekList() {
        // 在教学周列表里：前后各翻一档
        assertEquals(
            LocalDate.of(2026, 9, 21),
            weekStepTarget(LocalDate.of(2026, 9, 14), weeks, delta = 1),
        )
        assertEquals(
            LocalDate.of(2026, 9, 14),
            weekStepTarget(LocalDate.of(2026, 9, 21), weeks, delta = -1),
        )
        // 到头了就停在头尾（不越界、不跳空）
        assertEquals(
            LocalDate.of(2026, 10, 4).minusDays(6),
            weekStepTarget(LocalDate.of(2026, 9, 28), weeks, delta = 1),
        )
        assertEquals(
            LocalDate.of(2026, 9, 14),
            weekStepTarget(LocalDate.of(2026, 9, 14), weeks, delta = -1),
        )
    }

    @Test
    fun swipingStillWorksWhenTheShownWeekIsNotATeachingWeek() {
        // ★ "刚进页面滑不动"就是这么来的：今天落在假期/开学前时，当前这一周不在教学周列表里。
        //   这时往后滑要落到"列表里第一个更晚的周"、往前滑落到"第一个更早的周"
        assertEquals(
            "开学前（8/31）：往后滑 → 第 3 周",
            LocalDate.of(2026, 9, 14),
            weekStepTarget(LocalDate.of(2026, 8, 31), weeks, delta = 1),
        )
        assertEquals(
            "学期过完（10/12）：往前滑 → 最后一周",
            LocalDate.of(2026, 9, 28),
            weekStepTarget(LocalDate.of(2026, 10, 12), weeks, delta = -1),
        )
        assertEquals(
            "比列表里所有周都晚：往前滑也落到最后一周",
            LocalDate.of(2026, 9, 28),
            weekStepTarget(LocalDate.of(2027, 3, 1), weeks, delta = -1),
        )
    }

    @Test
    fun swipingFallsBackToNaturalWeeksWithoutATeachingWeekList() {
        // 列表空（本校不支持按周查 / 还没同步回来）：按自然周翻 —— 页面先显示整学期课表那张灰画布，
        // 教学周数据到了再自动对齐。这样"刚进来"也滑得动
        assertEquals(
            LocalDate.of(2026, 9, 21),
            weekStepTarget(LocalDate.of(2026, 9, 14), emptyList(), delta = 1),
        )
        assertEquals(
            LocalDate.of(2026, 9, 7),
            weekStepTarget(LocalDate.of(2026, 9, 14), emptyList(), delta = -1),
        )
    }

    @Test
    fun weekRangeLabelHidesTheRepeatedMonth() {
        assertEquals("9月14日 ~ 20日", weekRangeLabel(LocalDate.of(2026, 9, 14)))
        assertEquals("跨月时两段都要写", "8月31日 ~ 9月6日", weekRangeLabel(LocalDate.of(2026, 8, 31)))
    }

    // ── 时间轴：节次 → 起止时刻 ────────────────────────────────────────

    @Test
    fun axisShowsEachPeriodsStartAndEnd() {
        // 一节课 40 分钟：块内第一节 9:00~9:40、第二节 9:40~10:20
        assertEquals("9:00~9:40", CourseRepository.periodRangeLabel(1))
        assertEquals("9:40~10:20", CourseRepository.periodRangeLabel(2))
        assertEquals("10:40~11:20", CourseRepository.periodRangeLabel(3))
        assertEquals("21:10~21:50", CourseRepository.periodRangeLabel(16))
        assertEquals("越界的节次没有刻度文案", "", CourseRepository.periodRangeLabel(0))
        assertEquals("", CourseRepository.periodRangeLabel(99))
    }

    @Test
    fun timetableIsEightTwoPeriodBlocksOfSixteenPeriods() {
        // ★ 时刻表本身就是课表的骨架，钉死这 8 行：改动它是刻意的，不该被顺手改掉
        val expected = listOf(
            1 to "9:00~10:20",
            3 to "10:40~12:00",
            5 to "12:30~13:50",
            7 to "14:00~15:20",
            9 to "15:30~16:50",
            11 to "17:00~18:20",
            13 to "19:00~20:20",
            15 to "20:30~21:50",
        )
        expected.forEachIndexed { block, (firstPeriod, label) ->
            assertEquals("第 $block 块（第 $firstPeriod-${firstPeriod + 1} 节）", label, CourseRepository.blockRangeLabel(block))
            assertEquals("连上两节时也应该是这个区间", label, CourseRepository.periodRangeLabel(firstPeriod, 2))
            assertEquals("第 $firstPeriod 节从块起点开始", label.substringBefore('~'), CourseRepository.periodTime(firstPeriod))
        }

        assertEquals("8 个块、16 节", 16, CourseRepository.MAX_PERIOD)
        assertEquals(8, CourseRepository.BLOCK_COUNT)
        assertEquals("块内两节各 40 分钟", 40, CourseRepository.PERIOD_MINUTES)
        assertEquals("第 16 节落在最后一个块（第 8 行）", 7, blockOf(16))
        assertEquals(8, blocksOf(16))
    }

    // ── "现在上到第几节" ───────────────────────────────────────────────

    @Test
    fun currentPeriodFollowsThePeriodStartTimes() {
        val days = (0L..6L).map { LocalDate.of(2026, 9, 14).plusDays(it) }
        val tuesday = LocalDate.of(2026, 9, 15)

        // 8:30 还没到第 1 节（9:00）
        assertNull(currentPeriodOf(tuesday, days, maxPeriod = 16, now = LocalTime.of(8, 30)))
        // 9:00 上第 1 节；9:45 落在第 2 节（9:40~10:20）
        assertEquals(1, currentPeriodOf(tuesday, days, maxPeriod = 16, now = LocalTime.of(9, 0)))
        assertEquals(2, currentPeriodOf(tuesday, days, maxPeriod = 16, now = LocalTime.of(9, 45)))
        // 10:40 是第 3 节（块 3-4 的开头）
        assertEquals(3, currentPeriodOf(tuesday, days, maxPeriod = 16, now = LocalTime.of(10, 40)))
        // 13:00 落在第 5 节（12:30~13:10）
        assertEquals(5, currentPeriodOf(tuesday, days, maxPeriod = 16, now = LocalTime.of(13, 0)))
    }

    @Test
    fun currentPeriodStopsWhenTheDaysClassesAreOver() {
        // ★ 这条钉的就是"晚上第十节还一直蓝着"：一节只在**自己的时间窗内**才算"现在"，
        //   课间（10:20~10:40）、午休（12:00~12:30）、下课后（21:50 之后）谁都不亮
        val days = (0L..6L).map { LocalDate.of(2026, 9, 14).plusDays(it) }
        val monday = LocalDate.of(2026, 9, 14)

        assertEquals("21:30 还在最后一节里", 16, currentPeriodOf(monday, days, 16, LocalTime.of(21, 30)))
        assertNull("21:50 下课了，不该再亮任何一节", currentPeriodOf(monday, days, 16, LocalTime.of(21, 50)))
        assertNull("深夜更不该亮", currentPeriodOf(monday, days, 16, LocalTime.of(23, 30)))
        assertNull("10:30 是块间休息", currentPeriodOf(monday, days, 16, LocalTime.of(10, 30)))
        assertNull("12:15 是午休", currentPeriodOf(monday, days, 16, LocalTime.of(12, 15)))
        assertNull("18:30 是晚饭时间（18:20 下课、19:00 才上）", currentPeriodOf(monday, days, 16, LocalTime.of(18, 30)))
    }

    @Test
    fun currentPeriodIsHiddenWhenTodayIsNotOnTheGrid() {
        // 翻到别的周时不画"现在"：那一行在今天之外，画上去只会误导
        val days = (0L..6L).map { LocalDate.of(2026, 9, 14).plusDays(it) }
        val nextMonth = LocalDate.of(2026, 10, 6)
        assertNull(currentPeriodOf(nextMonth, days, maxPeriod = 10, now = LocalTime.of(10, 30)))
    }

    // ── 时间轴三行：开始时刻 / 第 N-M 节 / 结束时刻 ─────────────────────

    @Test
    fun axisSplitsTheBlockRangeIntoStartAndEnd() {
        // ★ 三行写法（`9:00` / `第1-2节` / `10:20`）与区间写法必须永远指同一段时间：
        //   拆开是为了"在 40dp 宽的左轴上完整显示"（区间写法会被省略号截断）
        val expected = listOf(
            "9:00" to "10:20",
            "10:40" to "12:00",
            "12:30" to "13:50",
            "14:00" to "15:20",
            "15:30" to "16:50",
            "17:00" to "18:20",
            "19:00" to "20:20",
            "20:30" to "21:50",
        )
        expected.forEachIndexed { block, (start, end) ->
            assertEquals("第 $block 块的开始时刻", start, CourseRepository.blockStartTime(block))
            assertEquals("第 $block 块的结束时刻", end, CourseRepository.blockEndTime(block))
            assertEquals(
                "起 + 止 拼起来就是区间文案",
                CourseRepository.blockRangeLabel(block),
                "${CourseRepository.blockStartTime(block)}~${CourseRepository.blockEndTime(block)}",
            )
            assertEquals("开始时刻 = 这一块第一节的开始", start, CourseRepository.periodTime(block * 2 + 1))
            assertEquals("结束时刻 = 这一块第二节的结束", end, CourseRepository.periodEndTime(block * 2 + 2))
        }

        assertEquals("越界的块没有刻度", "", CourseRepository.blockStartTime(-1))
        assertEquals("", CourseRepository.blockEndTime(99))
    }

    @Test
    fun axisLabelsStayShortEnoughForTheNarrowLeftAxis() {
        // 左轴只有 40dp 上下：三行文字里最长的是"第15-16节"（7 个字符）。
        // 这条钉的是"轴宽是按最长那行实测出来的"这个前提 —— 哪天有人把节次写成
        // "第 15-16 节（20:30~21:50）"这种长文案，这里会立刻红
        val longest = (0 until CourseRepository.BLOCK_COUNT).maxOf { block ->
            "第${block * 2 + 1}-${block * 2 + 2}节".length
        }
        assertEquals(7, longest)
        (0 until CourseRepository.BLOCK_COUNT).forEach { block ->
            assertTrue("开始时刻要短", CourseRepository.blockStartTime(block).length <= 5)
            assertTrue("结束时刻要短", CourseRepository.blockEndTime(block).length <= 5)
        }
    }

    // ── 行数：只画"真的排过课"的那几行（整周一眼看全的关键） ──────────────

    @Test
    fun gridRowCountStopsAtTheLastCourseOfTheWholeSemester() {
        // 课表只排到下午（第 10 节）→ 5 行，末尾三行空行不再占地方
        assertEquals(5, gridRowCount(listOf(listOf(dim(1, "高数", 9, 2)), listOf(dim(2, "英语", 1, 2)))))
        // 有晚自习（第 16 节）→ 8 行，一屏放得下 8 行（行高由屏幕高度现算）
        assertEquals(8, gridRowCount(listOf(listOf(dim(3, "晚课", 15, 2)))))
        // 连上四节（第 5-8 节）跨两行 → 铺到第 4 行
        assertEquals(4, gridRowCount(listOf(listOf(dim(4, "实验", 5, 4)))))
    }

    @Test
    fun gridRowCountKeepsMiddlesGapsAndGuardsDirtyData() {
        // 中间的空行照画（第 1-2 节有课、第 5-6 节没课 → 仍是 5 行）：
        // 只有**末尾**的空行才是纯浪费，中间的空行是"这个时段空着"的信息
        assertEquals(5, gridRowCount(listOf(listOf(dim(5, "早课", 1, 2), dim(6, "下午课", 9, 2)))))
        // 一节单独的课（第 7 节）→ 落在第 4 行
        assertEquals(4, gridRowCount(listOf(listOf(dim(7, "单节", 7, 1)))))
        // 脏数据：第 99 节被夹回最后一行，不会把网格撑成几十行
        assertEquals(8, gridRowCount(listOf(listOf(dim(8, "脏数据", 99, 2)))))
        // 越界的节次（0 / 负数）当成第 1 节，不会算出 0 行导致整块网格消失
        assertEquals(1, gridRowCount(listOf(listOf(dim(9, "越界", 0, 1)))))
        // 整周一张卡都没有 → 退回"整张时刻表"（此时页面显示的是"该周暂无课表数据"占位）
        assertEquals(8, gridRowCount(listOf(emptyList(), emptyList())))
    }

    @Test
    fun gridRowCountIgnoresWhichWeekItIs() {
        // ★ 行数只由"画布 + 补的亮卡"决定（两者都与周次无关的那部分一致），
        //   所以换周不会让网格忽高忽低 —— 与 buildGridCards 的"位置一张不动"是同一件事
        val canvas = listOf(course(10, "高数", 1, 2), course(11, "晚课", 15, 2))
        val thisWeek = listOf(CourseEntry.Local(canvas[0]))
        val otherWeek = listOf(CourseEntry.Local(canvas[1]))

        assertEquals(
            gridRowCount(listOf(buildGridCards(thisWeek, canvas))),
            gridRowCount(listOf(buildGridCards(otherWeek, canvas))),
        )
    }
}
