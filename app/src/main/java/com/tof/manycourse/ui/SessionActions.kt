package com.tof.manycourse.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tof.manycourse.MainActivity
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.reminder.ClassReminderScheduler
import com.tof.manycourse.widget.WidgetRefresh

/**
 * 退出登录并回到登录页。
 *
 * 三件事的顺序不能变：
 *  1. **[SessionStore.logout]** 清服务端登录态 + 清本地加密存档 + 清个人数据；
 *  2. 再启动 `MainActivity`。反过来的话，`MainActivity` 一 `onCreate` 就发现
 *     "还登录着"，会立刻把用户又弹回主界面（看起来就是点了退出没反应）；
 *  3. `CLEAR_TASK` 清掉返回栈。否则用户按返回键会回到已经退出的主界面，
 *     看到的是上一个人的课表。
 *
 * 退出登录和"会话过期"走的是同一个入口 —— 两者对用户来说都是"得重新登一次"。
 */
fun Context.logoutAndBackToLogin() {
    SessionStore.logout()

    // ★ 课前提醒与桌面小组件展示的都是**上一个账号的课表**，退出时必须一起收掉：
    //   `ClassReminderScheduler.clearOnLogout` 撤掉已登记的闹钟**并清掉"这节已经提醒过"的运行态**
    //   （不清的话，下一个账号的第一节课只要恰好同天同节次同名就会被静默跳过），
    //   `WidgetRefresh` 把桌面上的格子刷成"打开 App 同步课表"。
    //
    //   ★ 必须放在 `SessionStore.logout()` **之后**：它清完课表，这里的重排才会
    //     正确判定成"没有数据"从而撤销闹钟。放前面的话闹钟会带着上一个人的课表活下去。
    ClassReminderScheduler.clearOnLogout(this)
    WidgetRefresh.updateAll(this)

    startActivity(
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    )
    (this as? Activity)?.finish()
}
