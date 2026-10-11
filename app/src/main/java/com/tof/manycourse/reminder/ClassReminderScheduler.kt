package com.tof.manycourse.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.data.UpcomingClass
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * **课前提醒的登记口**：算出"下一次该在什么时候醒来"，然后只登记**一条**闹钟。
 *
 * ## 为什么是"链式的一条"，而不是"每节课一条"
 *
 * 排三五条当然也能用，代价是它们各自独立、课表一变就全成了过期信息，
 * 得逐个去撤销（漏撤一条 = 上一份课表把你叫起来）。这里只保留一条：
 * 醒来 → 发通知 → **用最新课表重算下一条**。课表怎么变都不会留下孤儿闹钟。
 *
 * ## 什么时候会被调用（这些时机合起来才构成"不会漏"）
 *
 * | 时机 | 谁调 |
 * |---|---|
 * | 进程每次启动 | `ManyCourseApp.onCreate` |
 * | 开机 / 改时间 / 换时区 / 应用更新 | `RescheduleReceiver` |
 * | 上一条闹钟醒来 | `ClassReminderReceiver` |
 * | 用户改提前量 / 开关提醒 | `ui/SettingsScreen.kt` |
 * | 课表同步完成 / 退出登录 | `data/CourseSync.kt` |
 *
 * 这里面**只有"进程启动"是每次必到的**（用户点开一次 App 就恢复），
 * 所以其它时机漏掉一两个也不会让提醒永远失效 —— 这是刻意的冗余。
 *
 * ## 精确闹钟与降级
 *
 * `canScheduleExactAlarms()` 为 false 时退成 `setAndAllowWhileIdle`（不精确）。
 * 不精确意味着系统可能把闹钟攒到"合适的时机"一起放，
 * 表现是提醒可能早到或晚到几分钟 —— 设置页会把这件事**如实写给用户看**，
 * 而不是假装很准（与项目一贯的"拿不到准确的就别编"一致）。
 */
object ClassReminderScheduler {

    private const val TAG = "ClassReminder"

    /**
     * 只登记一条闹钟，所以固定 requestCode + `FLAG_UPDATE_CURRENT`：
     * 新的一次登记天然把上一次覆盖掉，不会攒下孤儿闹钟。
     */
    private const val REQUEST_CODE = 0x4D43 // 'MC'

    /**
     * 重排下一条提醒。可以放心地反复调（幂等：它总是按"当前这一刻"重算）。
     *
     * @param now 便于单测注入；生产环境永远用默认值
     */
    fun reschedule(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val app = context.applicationContext
        ReminderState.attach(app)
        UiSettings.attach(app)

        // 关掉提醒 / 退出登录 / 从没同步过课表 → 撤销闹钟，并且**不留运行态**
        if (!UiSettings.notificationsEnabled.value || !UpcomingClass.hasScheduleData()) {
            cancel(app)
            return
        }

        val slot = UpcomingClass.forReminder(now)
        if (slot == null) {
            cancel(app)
            return
        }

        // forReminder 要求必须有时刻，所以这里拿得到；拿不到只可能是逻辑被改坏了，直接撤掉不崩
        val start = slot.start ?: run {
            cancel(app)
            return
        }
        val startAt = slot.date.atTime(start)

        val at = if (ReminderState.wasNotified(slot)) {
            // 这节课已经提醒过了。**不能在原地重排**（那会立刻再响一次、无限重发），
            // 而是把下一次醒来定在"它开课的那一刻"——
            // 那时 forReminder 会自然跳过已经开课的它，给出下一节课。
            startAt
        } else {
            ReminderPlan.alarmAt(slot, UiSettings.leadMinutes.value, now) ?: startAt
        }

        setAlarm(app, at)
    }

    /** 撤销提醒（关开关 / 退出登录 / 没有课表数据时）*/
    fun cancel(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.cancel(alarmIntent(app))
    }

    /**
     * 这台机器上能不能用**精确**闹钟。
     *
     * 设置页用它决定显示"提醒是准时的"还是"系统未授权，提醒可能晚几分钟"，
     * 所以它必须问系统、不能靠我们自己缓存。
     */
    fun canScheduleExact(context: Context): Boolean =
        (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
            ?.canScheduleExactAlarms() ?: false

    /**
     * 退出登录时调：撤销闹钟 + 清掉"已提醒"运行态。
     *
     * ★ 运行态**必须一起清**：不清的话，下一个账号的第一节课只要恰好和上一个人
     * 某节课同天同节次同名，就会被当成"已经提醒过了"而**静默不提醒** ——
     * 用户看到的是"提醒时灵时不灵"，最难查的那一类问题。
     */
    fun clearOnLogout(context: Context) {
        cancel(context)
        ReminderState.attach(context.applicationContext)
        ReminderState.clear()
    }

    private fun setAlarm(context: Context, at: LocalDateTime) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAtMillis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = alarmIntent(context)

        if (canScheduleExact(context)) {
            try {
                // RTC_WAKEUP：要在息屏时把人叫醒，所以必须能唤醒设备（不能用 RTC）
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
                return
            } catch (e: SecurityException) {
                // canScheduleExactAlarms() 与实际授权状态在极少数机型上会短暂不一致。
                // 这里**不能**把提醒整个丢掉 —— 退成不精确仍然是有用的提醒。
                Log.w(TAG, "精确闹钟被系统拒绝，退化为不精确闹钟：${e.message}")
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ClassReminderReceiver::class.java),
        // FLAG_IMMUTABLE：Android 12 起可变 PendingIntent 必须显式声明，而我们不需要别人改它
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
