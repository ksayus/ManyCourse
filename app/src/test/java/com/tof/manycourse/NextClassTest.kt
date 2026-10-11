package com.tof.manycourse

import com.tof.manycourse.data.ClassSlot
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.NextClass
import com.tof.manycourse.data.Timetable
import com.tof.manycourse.data.Timetables
import com.tof.manycourse.data.formatClock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「下一节课」解析的单测。
 *
 * 这里钉的都是**不会崩、只会让人不信任 App** 的错：
 * 放假那天把用户叫去教室、没有作息表的学校编出一个上课时间、
 * 三节连排的课第二节课刚上就被判成"已经上完了"。
 * 这些在真机上跑一遍 UI 是钉不住的，只能靠纯函数单测。
 *
 * 注意测试里**一律显式传 [Timetable]**：`CourseRepository.timetable` 读的是
 * `SessionStore`（JVM 单测里没有登录态，且各个用例会互相污染），
 * 传进去才能把"作息表"这一维变成可控的输入。
 */
class NextClassTest {

    /** 2026-10-12 是周一 */
    private val monday: LocalDate = LocalDate.of(2026, 10, 12)
    private val tuesday: LocalDate = monday.plusDays(1)
    private val wednesday: LocalDate = monday.plusDays(2)

    private fun at(hour: Int, minute: Int): LocalDateTime = LocalDateTime.of(monday, LocalTime.of(hour, minute))

    private fun entry(
        name: String,
        weekday: Int,
        startPeriod: Int,
        periodCount: Int = 2,
        room: String = "A-101",
    ): CourseEntry = CourseEntry.Local(
        Course(
            id = name.hashCode().toLong(),
            name = name,
            teacher = "王老师",
            room = room,
            weekday = weekday,
            startPeriod = startPeriod,
            periodCount = periodCount,
        )
    )

    /** 只认这几天的课，其余天当作"没有数据"（null）—— 与真实场景里"这一周还没拉到"一致 */
    private fun daysOf(vararg pairs: Pair<LocalDate, List<CourseEntry>>): (LocalDate) -> List<CourseEntry>? {
        val table = pairs.toMap()
        return { date -> table[date] }
    }

    // ══════════════════ 放假：整天跳过 ══════════════════

    /**
     * ★ 本功能最要紧的一条。
     *
     * 放假那天的课"照旧列在课表上，但一门都不点亮"= **那天不上课**。
     * 所以"下一节课"必须跳过整整一天，而不是把那天第一门课当成待上的课 ——
     * 否则国庆节早上七点会把用户叫去教室。
     */
    @Test
    fun `放假日整天跳过_落到下一个上课的日子`() {
        val slot = NextClass.nextForDisplay(
            timetable = Timetables.default,
            now = at(8, 0),
            days = daysOf(
                monday to listOf(entry("高等数学", 1, 1)),
                tuesday to listOf(entry("大学英语", 2, 1)),
            ),
            lit = { it != monday },
        )

        assertNotNull(slot)
        assertEquals(tuesday, slot!!.date)
        assertEquals("大学英语", slot.name)
    }

    /** 连着放两天假时也要继续往后找，而不是停在第一个放假日 */
    @Test
    fun `连着放假两天_继续往后找`() {
        val slot = NextClass.nextForDisplay(
            timetable = Timetables.default,
            now = at(8, 0),
            days = daysOf(
                monday to listOf(entry("高等数学", 1, 1)),
                tuesday to listOf(entry("大学英语", 2, 1)),
                wednesday to listOf(entry("数据结构", 3, 1)),
            ),
            lit = { it == wednesday },
        )

        assertEquals(wednesday, slot?.date)
        assertEquals("数据结构", slot?.name)
    }

    /** 提醒同样要跳过放假日 —— 这条走的是 nextForReminder，不是只有展示层跳过 */
    @Test
    fun `放假日不排提醒_顺延到下一个上课日`() {
        val slot = NextClass.nextForReminder(
            timetable = Timetables.default,
            now = at(8, 0),
            days = daysOf(
                monday to listOf(entry("高等数学", 1, 1)),
                tuesday to listOf(entry("大学英语", 2, 1)),
            ),
            lit = { it != monday },
        )

        assertEquals(tuesday, slot?.date)
    }

    // ══════════════════ 今天：正在上 / 已经上完 ══════════════════

    /** 正上着的课要认出来（小组件写「正在上课」，而不是"还有 -20 分钟"）*/
    @Test
    fun `正在上的课_标记为 running 且倒计时为负`() {
        // 默认表第 1-2 节 = 9:00~10:20
        val slot = NextClass.nextForReminder(
            timetable = Timetables.default,
            now = at(9, 30),
            days = daysOf(monday to listOf(entry("高等数学", 1, 1))),
            lit = { true },
        )

        assertNotNull(slot)
        assertTrue(slot!!.running)
        assertTrue(slot.startsIn!!.isNegative)
        assertEquals(LocalTime.of(9, 0), slot.start)
        assertEquals(LocalTime.of(10, 20), slot.end)
    }

    /**
     * ★ 连上几节时，**按最后一节判有没有上完**。
     *
     * 用"第一节的结束时刻"当整门课的结束，会让 1-2 连排的课在 9:40 就被判成上完了 ——
     * 小组件会在第二节课刚上课时直接跳到下一门。
     */
    @Test
    fun `两节连排_第二节课期间仍算正在上`() {
        val slot = NextClass.nextForDisplay(
            timetable = Timetables.default,
            now = at(9, 50), // 第 1 节已结束（9:40），第 2 节还没（10:20）
            days = daysOf(monday to listOf(entry("高等数学", 1, 1, periodCount = 2))),
            lit = { true },
        )

        assertNotNull(slot)
        assertTrue(slot!!.running)
    }

    /** 今天已经下课的课不算"下一节课"；今天没有别的了就是 null */
    @Test
    fun `今天已经上完的课被跳过`() {
        val slot = NextClass.nextForDisplay(
            timetable = Timetables.default,
            now = at(11, 0), // 第 1-2 节 10:20 就结束了
            days = daysOf(monday to listOf(entry("高等数学", 1, 1))),
            lit = { true },
        )

        assertNull(slot)
    }

    /** 同一天里按**节次**排（没有作息表时时刻全是空，节次才是唯一可比的顺序）*/
    @Test
    fun `同一天按节次取最早的一门`() {
        val slot = NextClass.nextForDisplay(
            timetable = Timetables.default,
            now = at(8, 0),
            days = daysOf(
                monday to listOf(
                    entry("大学英语", 1, 5),
                    entry("高等数学", 1, 1),
                    entry("计算机基础", 1, 3),
                ),
            ),
            lit = { true },
        )

        assertEquals("高等数学", slot?.name)
    }

    // ══════════════════ 跨天 ══════════════════

    @Test
    fun `今天的课上完了_顺延到明天第一门`() {
        val slot = NextClass.nextForDisplay(
            timetable = Timetables.default,
            now = at(22, 0), // 晚上的课也上完了
            days = daysOf(
                monday to listOf(entry("高等数学", 1, 1)),
                tuesday to listOf(entry("大学英语", 2, 1)),
            ),
            lit = { true },
        )

        assertEquals(tuesday, slot?.date)
        assertEquals("大学英语", slot?.name)
    }

    // ══════════════════ 没有数据：不编 ══════════════════

    /**
     * `days()` 返回 null = 这一天**没有任何可用数据**（这一周还没拉到 / 压根没登录）。
     * 这种情况必须返回 null 交给调用方说"打开 App 同步课表"，
     * 而不是当成"这天没课"然后凭空给出一个结论。
     */
    @Test
    fun `没有课表数据时返回 null_不编出课来`() {
        val slot = NextClass.nextForDisplay(
            timetable = Timetables.default,
            now = at(8, 0),
            days = { null },
            lit = { true },
        )

        assertNull(slot)
        assertNull(NextClass.nextForReminder(Timetables.default, at(8, 0), days = { null }, lit = { true }))
    }

    /** 有数据但那天确实没课（空表）→ 也是 null，但语义是"上完了"（由调用方区分文案）*/
    @Test
    fun `有数据但没课_返回 null`() {
        val slot = NextClass.nextForDisplay(
            timetable = Timetables.default,
            now = at(8, 0),
            days = daysOf(monday to emptyList()),
            lit = { true },
        )

        assertNull(slot)
    }

    /** 只看 7 天：更远的课不算"下一节课"（否则周日会告诉你下下周三有课）*/
    @Test
    fun `超过前瞻天数就不找了`() {
        val far = monday.plusDays(30)
        val slot = NextClass.nextForDisplay(
            timetable = Timetables.default,
            now = at(8, 0),
            days = daysOf(far to listOf(entry("高等数学", 3, 1))),
            lit = { true },
        )

        assertNull(slot)
    }

    // ══════════════════ 没有作息表：展示照旧，提醒整块停用 ══════════════════

    /**
     * 一张**完全没收录作息**的表（时刻全空）。
     *
     * 现在三所已接入的学校里已经没有一所是这样的了（广工的作息已收录，见 `Timetables.gdut`），
     * 但"拿不到作息"这条路径必须一直有人验 —— 它是项目的底线规矩，不是某个学校的临时状态。
     */
    private val noTimesTimetable = Timetable(
        blocks = List(7) { Timetable.Block("", "") },
        periodMinutes = 40,
    )

    /**
     * 取舍与 README §4.9 一致：
     *  - 小组件照旧显示这一节课，只是写「第1-2节」而不写时刻；
     *  - **提醒整块停用** —— 没时刻就没法定闹钟，随便挑一个时刻等于编一个时间骗人。
     */
    @Test
    fun `没有作息表_展示照旧但提醒停用`() {
        val days = daysOf(monday to listOf(entry("高等数学", 1, 1)))

        val shown = NextClass.nextForDisplay(noTimesTimetable, at(8, 0), days = days, lit = { true })
        assertNotNull(shown)
        assertNull(shown!!.start)
        assertNull(shown.end)
        assertNull(shown.startsIn)
        assertFalse(shown.running)
        assertEquals("第1-2节", shown.periodLabel)
        assertEquals("", shown.startText)

        assertNull(NextClass.nextForReminder(noTimesTimetable, at(8, 0), days = days, lit = { true }))
    }

    /** 作息表有、但**某一条**课的时刻脏（节次越界）：展示留着，提醒跳过它去找下一条有时刻的 */
    @Test
    fun `单条课时刻脏_展示保留_提醒跳过`() {
        val days = daysOf(
            monday to listOf(entry("脏数据课", 1, startPeriod = 99)),
            tuesday to listOf(entry("大学英语", 2, 1)),
        )

        val shown = NextClass.nextForDisplay(Timetables.default, at(8, 0), days = days, lit = { true })
        assertEquals("脏数据课", shown?.name)
        assertNull(shown?.start)

        val reminded = NextClass.nextForReminder(Timetables.default, at(8, 0), days = days, lit = { true })
        assertEquals("大学英语", reminded?.name)
        assertEquals(tuesday, reminded?.date)
    }

    // ══════════════════ 时刻格式 ══════════════════

    /** 时刻**不补前导零**：与 `Timetable` 里手写的 `9:00` 保持一致，否则同一时刻会有两种写法 */
    @Test
    fun `时刻格式与作息表一致_不补前导零`() {
        assertEquals("9:00", formatClock(LocalTime.of(9, 0)))
        assertEquals("10:20", formatClock(LocalTime.of(10, 20)))
        assertEquals("8:05", formatClock(LocalTime.of(8, 5)))
    }

    /** 没有作息表时 `startText` 是空串（调用方按 isNotBlank 过滤掉那一行），不是 "null" */
    @Test
    fun `没有时刻时文本为空串`() {
        val slot: ClassSlot? = NextClass.nextForDisplay(
            timetable = noTimesTimetable,
            now = at(8, 0),
            days = daysOf(monday to listOf(entry("高等数学", 1, 1))),
            lit = { true },
        )

        assertEquals("", slot?.startText)
        assertEquals("", slot?.endText)
        assertEquals("周一", slot?.weekdayText)
    }

    /** 自定义一张作息表也要能正常解析（作息表是"学校的事实"，各校不一样）*/
    @Test
    fun `自定义作息表按传入的那张算`() {
        val custom = Timetable(
            blocks = listOf(Timetable.Block("7:30", "8:50")),
            periodMinutes = 40,
        )
        val slot = NextClass.nextForReminder(
            timetable = custom,
            now = LocalDateTime.of(monday, LocalTime.of(7, 0)),
            days = daysOf(monday to listOf(entry("早课", 1, 1))),
            lit = { true },
        )

        assertEquals(LocalTime.of(7, 30), slot?.start)
        assertEquals(LocalTime.of(8, 50), slot?.end)
        assertEquals(30L, slot?.startsIn?.toMinutes())
    }
}
