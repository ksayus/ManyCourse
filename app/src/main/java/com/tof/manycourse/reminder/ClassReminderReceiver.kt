package com.tof.manycourse.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.data.UpcomingClass
import com.tof.manycourse.notify.ClassReminderNotifier
import java.time.LocalDateTime

/**
 * 闹钟到点：**发通知，然后排下一条**。
 *
 * ## 为什么每次都要重新解一遍"下一节课"
 *
 * 闹钟是提前很久登记的，登记时的那份课表到点时可能已经不作数了（用户刚同步过、
 * 学校临时调课、或者这天变成了法定假日）。所以这里**不复用登记时的结论**，
 * 而是拿"现在"重新问一遍 [UpcomingClass.forReminder] —— 问到什么才发什么。
 *
 * 这一步也是"放假当天不发提醒"的最后一道保险：
 * 即便登记时算错了，到点重新解一次仍然会发现那天不上课，从而不发。
 *
 * ## 为什么不用 goAsync()
 *
 * 整条路径**没有网络、没有磁盘 IO 放大**：读几个内存里的仓库、比对一次 prefs、
 * 发一条通知、登记一条闹钟，全是毫秒级。用 goAsync 反而要自己管超时和线程，
 * 没有收益。
 */
class ClassReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        ReminderState.attach(app)
        UiSettings.attach(app)

        val now = LocalDateTime.now()

        // 用户可能在中途关掉了提醒；广播已经在路上了，这里再确认一次
        if (UiSettings.notificationsEnabled.value) {
            val slot = UpcomingClass.forReminder(now)
            if (slot != null &&
                !ReminderState.wasNotified(slot) &&
                ReminderPlan.isDue(slot, UiSettings.leadMinutes.value, now)
            ) {
                ClassReminderNotifier.notify(app, slot)
                // ★ 顺序要紧：先标记再重排。反过来的话，重排时它还认为"没提醒过"，
                //   会把下一条闹钟又定在同一个提醒时刻 → 立刻再响一次。
                ReminderState.markNotified(slot)
            }
        }

        ClassReminderScheduler.reschedule(app, now)
    }
}
