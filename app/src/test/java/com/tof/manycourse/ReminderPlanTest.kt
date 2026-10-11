package com.tof.manycourse

import com.tof.manycourse.data.ClassSlot
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.reminder.ReminderPlan
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课前提醒的**纯时间计算**单测。
 *
 * 这里最要紧的是第 4 条规则（提醒时刻已过、但还没开课 → **立刻提醒**）和
 * 它的反面（开课之后一律不发）。写错任何一边的后果是：
 * 要么用户整节课都收不到提醒，要么课上到一半手机再震一次"就要上课了"。
 */
class ReminderPlanTest {

    private val monday: LocalDate = LocalDate.of(2026, 10, 12)

    private fun slot(
        start: LocalTime? = LocalTime.of(9, 0),
        end: LocalTime? = LocalTime.of(10, 20),
        name: String = "高等数学",
        startPeriod: Int = 1,
        date: LocalDate = monday,
    ): ClassSlot = ClassSlot(
        date = date,
        entry = CourseEntry.Local(
            Course(
                id = 1L,
                name = name,
                teacher = "王老师",
                room = "A-101",
                weekday = date.dayOfWeek.value,
                startPeriod = startPeriod,
                periodCount = 2,
            )
        ),
        start = start,
        end = end,
        startsIn = null,
        running = false,
    )

    private fun at(hour: Int, minute: Int): LocalDateTime = LocalDateTime.of(monday, LocalTime.of(hour, minute))

    // ══════════════════ triggerAt ══════════════════

    @Test
    fun `提醒时刻等于开课时刻减去提前量`() {
        assertEquals(at(8, 45), ReminderPlan.triggerAt(slot(), 15))
        assertEquals(at(8, 55), ReminderPlan.triggerAt(slot(), 5))
        assertEquals(at(8, 30), ReminderPlan.triggerAt(slot(), 30))
    }

    /** 没有作息表 → 压根排不了提醒（不是"排到 0 点"）*/
    @Test
    fun `没有作息表时提醒时刻为 null`() {
        assertNull(ReminderPlan.triggerAt(slot(start = null, end = null), 15))
        assertNull(ReminderPlan.alarmAt(slot(start = null, end = null), 15, at(8, 0)))
        assertFalse(ReminderPlan.isDue(slot(start = null, end = null), 15, at(8, 0)))
    }

    // ══════════════════ alarmAt ══════════════════

    @Test
    fun `提前量还没到_闹钟就定在提醒时刻`() {
        assertEquals(at(8, 45), ReminderPlan.alarmAt(slot(), 15, at(8, 0)))
    }

    /**
     * ★ 用户课前 5 分钟才打开 App，而提前量是 15 分钟 ——
     * "还有 5 分钟上课"这句话仍然有用，所以**立刻提醒**，而不是默默跳过。
     */
    @Test
    fun `提醒时刻已过但还没开课_立刻提醒`() {
        val now = at(8, 55) // 提醒时刻 8:45 已过，9:00 还没到
        assertEquals(now, ReminderPlan.alarmAt(slot(), 15, now))
    }

    /** 开课之后就不再排这节课的提醒了 */
    @Test
    fun `已经开课_不再排提醒`() {
        assertNull(ReminderPlan.alarmAt(slot(), 15, at(9, 0)))
        assertNull(ReminderPlan.alarmAt(slot(), 15, at(9, 30)))
        assertNull(ReminderPlan.alarmAt(slot(), 15, at(10, 30)))
    }

    // ══════════════════ isDue ══════════════════

    @Test
    fun `还没到提醒时刻_不发`() {
        assertFalse(ReminderPlan.isDue(slot(), 15, at(8, 44)))
    }

    @Test
    fun `进入提醒窗口_发`() {
        assertTrue(ReminderPlan.isDue(slot(), 15, at(8, 45)))
        assertTrue(ReminderPlan.isDue(slot(), 15, at(8, 50)))
        assertTrue(ReminderPlan.isDue(slot(), 15, at(8, 59)))
    }

    /** ★ 开课那一刻起就不再发了：课都开始了才通知"就要上课了"是纯打扰 */
    @Test
    fun `开课之后_不发`() {
        assertFalse(ReminderPlan.isDue(slot(), 15, at(9, 0)))
        assertFalse(ReminderPlan.isDue(slot(), 15, at(9, 30)))
    }

    // ══════════════════ slotKey ══════════════════

    /** 同一节课的身份必须稳定 —— 它决定了"这节课是否已经提醒过" */
    @Test
    fun `同一节课的键稳定`() {
        assertEquals(ReminderPlan.slotKey(slot()), ReminderPlan.slotKey(slot()))
    }

    /** 换一天 / 换节次 / 换课名都必须是**不同的**键，否则会把新课当成"提醒过了"而静默不提醒 */
    @Test
    fun `不同课或不同天或不同节次_键不同`() {
        val base = ReminderPlan.slotKey(slot())
        assertNotEquals(base, ReminderPlan.slotKey(slot(name = "大学英语")))
        assertNotEquals(base, ReminderPlan.slotKey(slot(startPeriod = 3)))
        assertNotEquals(base, ReminderPlan.slotKey(slot(date = monday.plusDays(1))))
    }
}
