package com.tof.manycourse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import com.tof.manycourse.MainActivity
import com.tof.manycourse.R
import com.tof.manycourse.data.ClassSlot
import com.tof.manycourse.data.UpcomingClass
import com.tof.manycourse.data.formatDistance
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 小组件的**渲染与刷新**（唯一的刷新入口）。
 *
 * ## 为什么不把渲染写在 `AppWidgetProvider` 里
 *
 * 小组件要在**三个不同时机**变新，而 `onUpdate` 只覆盖其中一个：
 *
 * | 时机 | 触发者 |
 * |---|---|
 * | 系统周期性刷新（30 分钟一次）| `NextClassWidgetProvider.onUpdate` |
 * | App 里课表变了（同步完成 / 退出登录）| `CourseSync` |
 * | 课前提醒响了（下一节换人了）| `ClassReminderReceiver` |
 *
 * 三处都去调 `AppWidgetManager` 的话，"怎么画这个格子"就会被复制三份 ——
 * 迟早出现"点进 App 是对的、桌面上还停在上周"这种对不上。
 * 所以渲染只有这一份实现，三处都调 [updateAll]。
 *
 * ## 数据从哪来
 *
 * 走 `UpcomingClass.forDisplay`，也就是课表页 / 日历页同一套取值口径
 * （`coursesOfDate` + `dayIsLit`）。小组件**不自己算课表**。
 */
object WidgetRefresh {

    /**
     * 课程名的字号。**必须与 `res/layout/widget_next_class.xml` 里 `widget_name` 的
     * `android:textSize` 一致** —— RemoteViews 没法读回布局里的值，只能两处都写。
     */
    private const val COURSE_NAME_SP = 16f

    /**
     * 空态标题（「没有课表数据」/「今天的课都上完了」）的字号。
     *
     * 比课程名小一档：这两句是**说明**不是内容，用 16sp 粗体会折成两行、
     * 把 2x2 的格子撑得头重脚轻（真机截图里一眼就看出来了）。
     */
    private const val EMPTY_TITLE_SP = 14f

    /**
     * 刷新所有「下一节课」小组件。
     *
     * @param ids 只刷新这几个实例；**传 null 表示全部**
     */
    fun updateAll(context: Context, ids: IntArray? = null) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app) ?: return
        val targets = ids ?: manager.getAppWidgetIds(
            ComponentName(app, NextClassWidgetProvider::class.java)
        )
        // 桌面上一个这个小组件都没放：直接返回，别做无谓的 binder 调用
        if (targets.isEmpty()) return
        val now = LocalDateTime.now()
        targets.forEach { manager.updateAppWidget(it, render(app, now)) }
    }

    /**
     * 画一份小组件内容。
     *
     * ★ **`RemoteViews` 的 id 必须与 `res/layout/widget_next_class.xml` 一致** ——
     *   `setTextViewText` 找不到控件会抛异常（不是静默降级），改布局时要一起改这里。
     *
     * @param now 解析"下一节课"的基准时刻；做成参数是为了让"同一次刷新里所有实例看到同一时刻"
     */
    fun render(context: Context, now: LocalDateTime): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_next_class)

        // ★ **先问"有没有可信的课表数据"，再去解"下一节课"。顺序不能反。**
        //
        //   反过来的话，没登录 / 从没同步过的手机上，`coursesOfDate` 会把
        //   `CourseRepository.init` 预置的 7 门演示课（高等数学、大学英语…）当成
        //   真实课表返回，桌面小组件就会理直气壮地显示"高等数学"——
        //   而用户没有任何办法知道那是假的（见 `UpcomingClass.hasScheduleData` 的注释）。
        //   这个洞是真机测试里发现的：卸载重装后没有会话，小组件照样显示出了"高等数学"。
        val hasScheduleData = UpcomingClass.hasScheduleData()
        val slot = if (hasScheduleData) UpcomingClass.forDisplay(now) else null

        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))

        if (slot == null) {
            // 两种"没课可显示"必须分开（见 strings.xml 那段注释）：
            //  · 没数据 = 还没登录 / 从没同步过 → 让用户去 App 里同步
            //  · 有数据 = 有课表，只是往后七天都没课了
            //
            // 名字行是给课程名用的 16sp 粗体，整句提示塞进去会折成两行、
            // 把 2x2 的格子撑得头重脚轻（真机截图里看出来的）。所以：
            // **短标题进名字行、要用户做的事进状态行**，并把字号降一档。
            val titleRes = if (hasScheduleData) R.string.widget_no_more_today else R.string.widget_no_data
            views.setTextViewText(R.id.widget_name, context.getString(titleRes))
            views.setTextViewTextSize(R.id.widget_name, TypedValue.COMPLEX_UNIT_SP, EMPTY_TITLE_SP)
            views.setTextViewText(
                R.id.widget_status,
                if (hasScheduleData) "" else context.getString(R.string.widget_no_data_hint),
            )
            views.setTextViewText(R.id.widget_meta, "")
            views.setTextViewText(R.id.widget_room, "")
            return views
        }

        // 有课：名字行恢复成正常字号（同一个 RemoteViews 可能被复用，别的分支改过它）
        views.setTextViewTextSize(R.id.widget_name, TypedValue.COMPLEX_UNIT_SP, COURSE_NAME_SP)

        views.setTextViewText(
            R.id.widget_name,
            // 课程名可能很长（「大学英语（视听说）B班」），布局里给了 2 行 + 省略号，
            // 所以这里不做任何截断 —— 截在代码里反而会把能放下的名字砍掉
            slot.name,
        )

        // 「第1-2节 · 9:00~10:20」；这一节所在的块还没收录作息时退化成只有「第1-2节」
        views.setTextViewText(
            R.id.widget_meta,
            listOf(slot.periodLabel, slot.timeRangeText).filter { it.isNotBlank() }.joinToString(" · "),
        )
        views.setTextViewText(R.id.widget_room, slot.room)
        views.setTextViewText(R.id.widget_status, statusText(context, slot, now.toLocalDate()))
        return views
    }

    /**
     * 状态行：`正在上课` / `还有 12 分钟` / `今天` / `明天 9:00` / `周三 9:00`。
     *
     * 这节课所在的块没有作息时（[ClassSlot.start] 为 null）**不写倒计时也不写时刻** ——
     * 那两样都要真实时刻才算得出来。但也不能就这么空着：格子标题写的是「下一节课」，
     * 只给一个节次会让用户不知道这是**哪一天**的课。所以退化成写日期
     * （`今天` / `明天` / `周三`），信息少一个量级但仍然是真话。
     */
    private fun statusText(context: Context, slot: ClassSlot, today: LocalDate): String {
        if (slot.running) return context.getString(R.string.widget_running)

        val startsIn = slot.startsIn
        if (slot.date == today) {
            return if (startsIn != null && startsIn.toMinutes() > 0) {
                // 「还有 6 小时 18 分钟」——小时的量级要自己说出来，
                // 不能让用户拿 378 分钟去除 60（见 `formatDistance` 的注释）
                context.getString(R.string.widget_until_start, formatDistance(startsIn))
            } else {
                // 今天、但没有可信时刻：只能说是"今天"的课（见上面的注释）
                context.getString(R.string.widget_today)
            }
        }

        val whenText = slot.startText.ifBlank { slot.periodLabel }
        return if (slot.date == today.plusDays(1)) {
            context.getString(R.string.widget_tomorrow, whenText)
        } else {
            context.getString(R.string.widget_day_after, slot.weekdayText, whenText)
        }
    }

    /**
     * 点小组件 → 打开 App 的**课表页**。
     *
     * 与通知走同一个入口 Activity（见 `notify/ClassReminderNotifier.kt` 的注释）：
     * 冷启动时只有它能按登录态把启动链走对。`MainTabStore.currentIndex` 默认是 0（课表）。
     */
    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
