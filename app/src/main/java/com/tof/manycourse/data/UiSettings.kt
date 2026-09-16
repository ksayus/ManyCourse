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

    private var prefs: SharedPreferences? = null

    /** 当前玻璃风格；默认「高斯模糊」。Compose 观察此状态，切换后立即重组生效 */
    val glassMode = mutableStateOf(GlassMode.Default)

    /** 课前提醒开关；默认开启 */
    val notificationsEnabled = mutableStateOf(true)

    /** 幂等初始化，由 ManyCourseApp.onCreate 调用 */
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        glassMode.value = GlassMode.fromKey(p.getString(KEY_GLASS_MODE, null))
        notificationsEnabled.value = p.getBoolean(KEY_NOTIFICATIONS, true)
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
}
