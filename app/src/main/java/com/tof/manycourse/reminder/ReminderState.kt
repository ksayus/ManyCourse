package com.tof.manycourse.reminder

import android.content.Context
import android.content.SharedPreferences
import com.tof.manycourse.data.ClassSlot

/**
 * 「这节课已经提醒过了」的落盘记忆。
 *
 * ## 为什么需要它
 *
 * [ClassReminderScheduler] 的闹钟是**链式**的：醒来 → 发通知 → 排下一次。
 * 而"下一次"是用 `UpcomingClass.forReminder(now)` 重新解出来的 ——
 * 只要这节课还没开课，重解出来的**还是同一节**，算出的提醒时刻也还是同一个，
 * 于是一条闹钟会变成"立刻再响 → 再发一遍 → 再排"的死循环。
 *
 * ## 为什么必须落盘（不能只放内存）
 *
 * 闹钟触发时进程可能是**被杀之后重新拉起来的**，内存里的标记一定是空的 ——
 * 那一刻正是最需要这个标记的时候。所以它必须活过进程死亡。
 *
 * ## 为什么另开一份 prefs，而不是塞进 `UiSettings`
 *
 * 和 `SessionVault` / `ScheduleVault` 当初分开是同一个理由：**生命周期不一样**。
 * `UiSettings` 装的是用户偏好（玻璃风格、档位、开关），用户关掉提醒开关它们仍然有意义；
 * 这里装的是**调度运行态**（"上一节提醒到哪儿了"），退出登录、关掉提醒、换账号时
 * 都该被清掉，留着只会让新账号的第一节课被误判成"提醒过了"从而**静默不提醒**。
 * 混在一起的话，"清运行态"就会顺手把用户的偏好一起抹掉。
 */
internal object ReminderState {

    private const val PREFS_NAME = "manycourse_reminder_prefs"
    private const val KEY_NOTIFIED = "last_notified_slot"

    private var prefs: SharedPreferences? = null

    /** 幂等初始化；调度链上每个入口都先调一次（重复调没有代价）*/
    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 这节课是不是已经发过提醒了 */
    fun wasNotified(slot: ClassSlot): Boolean =
        prefs?.getString(KEY_NOTIFIED, null) == ReminderPlan.slotKey(slot)

    /** 记下"刚提醒过这一节" */
    fun markNotified(slot: ClassSlot) {
        prefs?.edit()?.putString(KEY_NOTIFIED, ReminderPlan.slotKey(slot))?.apply()
    }

    /**
     * 清掉运行态：退出登录 / 用户关掉提醒开关时调。
     *
     * 不清的话会出现"新账号的第一节课不提醒"这种**静默失败**：
     * 那节课恰好和上一个人的某节课同一天同一节次同名（比如都是"高等数学"），
     * 就会被当成"已经提醒过了"直接跳过。
     */
    fun clear() {
        prefs?.edit()?.remove(KEY_NOTIFIED)?.apply()
    }
}
