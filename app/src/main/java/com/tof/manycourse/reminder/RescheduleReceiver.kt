package com.tof.manycourse.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * **重排入口**：下面这四种情况都会让已登记的闹钟作废，收到广播后重算一条。
 *
 * | 广播 | 为什么必须重排 |
 * |---|---|
 * | `BOOT_COMPLETED` | 重启后系统会清空所有闹钟 —— 不重排的话"重启一次就再也不提醒了" |
 * | `TIME_SET` | 用户改了系统时间（或自动对时跳了一下），已排的绝对时刻就错了 |
 * | `TIMEZONE_CHANGED` | 跨时区："9:00 上课"说的是**本地时间**，时区变了绝对时刻要跟着变 |
 * | `MY_PACKAGE_REPLACED` | 覆盖安装后 PendingIntent 可能失效（应用被换了一茬） |
 *
 * 这里**不判断具体是哪个 action**：四种情况的正确反应完全一样，
 * 分开写只会多出四条几乎相同的分支。保持"收到就重排"。
 */
class RescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ClassReminderScheduler.reschedule(context.applicationContext)
    }
}
