package com.tof.manycourse.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.tof.manycourse.ui.theme.GlassMode

/**
 * UI 偏好持久化（SharedPreferences）。
 *
 * 项目原本没有任何持久化方案（课程/资料都在内存），这里不额外引入 DataStore 依赖，
 * 用系统 SharedPreferences 保持零依赖；读写都是 O(1)，同步读取可避免首帧闪一下旧主题。
 * 由 ManyCourseApp.onCreate 幂等初始化。
 */
object UiSettings {

    private const val PREFS_NAME = "manycourse_ui_prefs"
    private const val KEY_GLASS_MODE = "glass_mode"
    private const val KEY_NOTIFICATIONS = "notifications_enabled"
    private const val KEY_CALENDAR_GRID = "calendar_grid_layout"

    private var prefs: SharedPreferences? = null

    /** 当前玻璃风格；默认「高斯模糊」。Compose 观察此状态，切换后立即重组生效 */
    val glassMode = mutableStateOf(GlassMode.Default)

    /** 课前提醒开关；默认开启 */
    val notificationsEnabled = mutableStateOf(true)

    /**
     * **日历页布局**开关：
     * - `false`（默认）= 月历 + 当日课程列表（原有布局）；
     * - `true` = 日程网格：时间轴为骨架、二维网格为容器、课程卡片为载体。
     *
     * 默认关闭是刻意的：这个开关只影响**渲染方式**，不影响任何取值规则，
     * 所以老用户升级后应当看到和以前一模一样的日历，而不是被换掉一张不认识的页面。
     */
    val calendarGridLayout = mutableStateOf(false)

    /** 幂等初始化，由 ManyCourseApp.onCreate 调用 */
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        glassMode.value = GlassMode.fromKey(p.getString(KEY_GLASS_MODE, null))
        notificationsEnabled.value = p.getBoolean(KEY_NOTIFICATIONS, true)
        calendarGridLayout.value = p.getBoolean(KEY_CALENDAR_GRID, false)
    }

    /** 切换玻璃风格并持久化（无需重启 Activity） */
    fun setGlassMode(value: GlassMode) {
        if (glassMode.value == value) return
        glassMode.value = value
        prefs?.edit()?.putString(KEY_GLASS_MODE, value.key)?.apply()
    }

    /** 课前提醒开关，持久化 */
    fun setNotificationsEnabled(enabled: Boolean) {
        if (notificationsEnabled.value == enabled) return
        notificationsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_NOTIFICATIONS, enabled)?.apply()
    }

    /** 日历页布局切换（日程网格 / 月历列表），持久化 */
    fun setCalendarGridLayout(enabled: Boolean) {
        if (calendarGridLayout.value == enabled) return
        calendarGridLayout.value = enabled
        prefs?.edit()?.putBoolean(KEY_CALENDAR_GRID, enabled)?.apply()
    }
}
