package com.tof.manycourse.reminder

import com.tof.manycourse.data.ClassSlot
import java.time.LocalDateTime

/**
 * 课前提醒的**纯时间计算**（不碰 AlarmManager / Context，可直接 JVM 单测）。
 *
 * 真正的登记动作在 [ClassReminderScheduler]，这里只回答"该在什么时刻叫醒我"。
 */
object ReminderPlan {

    /**
     * 一节课的**稳定身份**：`2026-10-12|1|高等数学`。
     *
     * 用它记住"这节课已经提醒过了"。[ClassReminderScheduler] 的闹钟是**链式**的
     * （醒来 → 发通知 → 排下一次），如果不用身份去重，会出现这样一条死循环：
     *
     * ```
     *   T-15min 闹钟响 → 发通知 → 重排 → 解出的还是这节课 → 算出的提醒时刻仍是 T-15min
     *            → 立刻再响 → 再发一遍 …（同一条通知被无限重发）
     * ```
     *
     * 带上课名是因为同一天同一节次不会有两门课，但**换周/换课表**后
     * 日期+节次不足以区分"另一门课"，名字一起算进去更稳。
     */
    fun slotKey(slot: ClassSlot): String =
        "${slot.date}|${slot.entry.startPeriod}|${slot.name}"

    /**
     * 这节课"该提醒的那个时刻" = 开课时刻 − 提前量。
     *
     * @return null = 这节课**排不了提醒**（没有作息表，即 [ClassSlot.start] 为空）
     */
    fun triggerAt(slot: ClassSlot, leadMinutes: Int): LocalDateTime? {
        val start = slot.start ?: return null
        return slot.date.atTime(start).minusMinutes(leadMinutes.coerceAtLeast(0).toLong())
    }

    /**
     * **该把闹钟定在什么时刻**。
     *
     * 三种情况：
     *  1. `start` 为空（学校没收录作息）→ null，这节课根本排不了；
     *  2. 已经开课了 → null —— 课都开始了才提醒，等于没提醒；
     *  3. 提醒时刻还没到 → 就是它；
     *  4. **提醒时刻已经过去了、但还没开课**（用户课前 5 分钟才打开 App，而提前量是 15 分钟）
     *     → 返回 `now`，也就是**立刻提醒**。
     *
     * 第 4 条是刻意的："还有 5 分钟上课"这句话仍然有用，
     * 比"错过了就不说了"对用户好得多。重复触发不会刷屏 ——
     * 通知 id 是跟着 [slotKey] 走的稳定 id，重发只是更新同一条通知。
     */
    fun alarmAt(slot: ClassSlot, leadMinutes: Int, now: LocalDateTime): LocalDateTime? {
        val start = slot.start ?: return null
        val startAt = slot.date.atTime(start)
        if (!now.isBefore(startAt)) return null

        val planned = startAt.minusMinutes(leadMinutes.coerceAtLeast(0).toLong())
        return if (planned.isBefore(now)) now else planned
    }

    /**
     * 现在是不是**该发这条通知**了。
     *
     * 区间是 `[开课 − 提前量, 开课)`：
     *  - 下限：还没到提醒时刻就不发（闹钟有可能被系统提前一点点唤醒）；
     *  - 上限：**开课之后一律不发** —— 课都开始了再通知"就要上课了"是纯打扰。
     */
    fun isDue(slot: ClassSlot, leadMinutes: Int, now: LocalDateTime): Boolean {
        val start = slot.start ?: return false
        val startAt = slot.date.atTime(start)
        val triggerAt = startAt.minusMinutes(leadMinutes.coerceAtLeast(0).toLong())
        return !triggerAt.isAfter(now) && now.isBefore(startAt)
    }
}
