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
    private const val KEY_DIM_ON_HOLIDAYS = "dim_on_holidays"
    private const val KEY_DEV_MODE = "dev_mode"
    private const val KEY_AUTO_UPDATE = "auto_update_check"
    private const val KEY_UPDATE_SOURCE = "update_source"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check_at"

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

    /**
     * **节假日自动置灰**开关（默认开）。
     *
     * 打开时，法定放假日（见 [HolidayCalendar]）那天的课**照旧列在课表上，但一门都不点亮**
     * （灰着显示，见 [dayIsLit]）。调休上班日照常点亮，只把日子标出来。
     *
     * 为什么是"置灰"而不是"删掉"：把课从数据里拿掉的话，课程卡片会直接消失，
     * 用户看到的是"课表空了" —— 分不清是放假、还是同步挂了、还是自己没选课。
     * 灰着则一眼就知道"这天排了课、但今天不上"，与周视图那条
     * 「亮 = 本周会上 · 灰 = 本周不上」是同一套语言。
     *
     * 为什么给开关而不是写死：这是**唯一一处 App 覆盖教务系统课表的地方**。
     * 万一学校真有假期补课、或者用户就想让放假当天和平时长得一样，得有个开关能退回去。
     * 默认开着是因为用户要的就是"自动" —— 不需要他去设置里点一下。
     */
    val dimOnHolidays = mutableStateOf(true)

    /**
     * **开发者模式**开关：打开后地图页多出一套"点位采集 / 标定 / 数据导出"工具。
     *
     * 默认关闭，而且刻意放在设置页（不是地图页上藏一个长按入口）：
     * 它改的是**数据采集**行为，只有开发者自己知道在干什么时才该打开；
     * 藏起来的结果是下一个用户偶然打开，然后对着采集按钮一脸茫然。
     *
     * 打开后地图页会多出：原地采点（站在那儿取 GPS）、图上采点、标定、点位面板，
     * 数据自动写到系统根目录下的 `ManyCourse/`（见 `data/MapPointStore.kt`）。
     */
    val devMode = mutableStateOf(false)

    /**
     * **启动时自动检查更新**（默认开）。
     *
     * 自动检查只是"查一下有没有新版本"（几 KB 的 JSON），**不会自动下载**——
     * 下载与安装必须用户点（既省流量，也不会在他不知情时装上一个新版本）。
     * 间隔由 [UpdateStore.AUTO_CHECK_INTERVAL_MS] 控制（12 小时）。
     */
    val autoUpdateCheck = mutableStateOf(true)

    /** 从哪个源查更新（默认两个都查，见 [UpdateSourcePreference]）*/
    val updateSource = mutableStateOf(UpdateSourcePreference.Auto)

    /**
     * 上次检查更新的时刻（毫秒）；0 = 从没查过。
     *
     * **落盘**是刻意的：只放内存的话，用户一天开关十次 App 就会打十次接口
     * （GitHub 未认证限流 60 次/小时/IP，两个人共用出口 IP 时很容易撞上）。
     */
    val lastUpdateCheckAt = mutableStateOf(0L)

    /** 幂等初始化，由 ManyCourseApp.onCreate 调用 */
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        glassMode.value = GlassMode.fromKey(p.getString(KEY_GLASS_MODE, null))
        notificationsEnabled.value = p.getBoolean(KEY_NOTIFICATIONS, true)
        calendarGridLayout.value = p.getBoolean(KEY_CALENDAR_GRID, false)
        dimOnHolidays.value = p.getBoolean(KEY_DIM_ON_HOLIDAYS, true)
        devMode.value = p.getBoolean(KEY_DEV_MODE, false)
        autoUpdateCheck.value = p.getBoolean(KEY_AUTO_UPDATE, true)
        updateSource.value = UpdateSourcePreference.fromKey(p.getString(KEY_UPDATE_SOURCE, null))
        lastUpdateCheckAt.value = p.getLong(KEY_LAST_UPDATE_CHECK, 0L)
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

    /** 「节假日自动置灰」开关，持久化。切换后课表页 / 日历页当场重组生效 */
    fun setDimOnHolidays(enabled: Boolean) {
        if (dimOnHolidays.value == enabled) return
        dimOnHolidays.value = enabled
        prefs?.edit()?.putBoolean(KEY_DIM_ON_HOLIDAYS, enabled)?.apply()
    }

    /** 开发者模式开关，持久化 */
    fun setDevMode(enabled: Boolean) {
        if (devMode.value == enabled) return
        devMode.value = enabled
        prefs?.edit()?.putBoolean(KEY_DEV_MODE, enabled)?.apply()
    }

    /** 自动检查更新开关，持久化 */
    fun setAutoUpdateCheck(enabled: Boolean) {
        if (autoUpdateCheck.value == enabled) return
        autoUpdateCheck.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO_UPDATE, enabled)?.apply()
    }

    /** 更新源，持久化 */
    fun setUpdateSource(source: UpdateSourcePreference) {
        if (updateSource.value == source) return
        updateSource.value = source
        prefs?.edit()?.putString(KEY_UPDATE_SOURCE, source.name)?.apply()
    }

    /** 记下"刚查过更新"（用于自动检查的节流）*/
    fun setLastUpdateCheckAt(millis: Long) {
        lastUpdateCheckAt.value = millis
        prefs?.edit()?.putLong(KEY_LAST_UPDATE_CHECK, millis)?.apply()
    }
}
