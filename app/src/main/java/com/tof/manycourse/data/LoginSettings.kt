package com.tof.manycourse.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * 登录态偏好持久化（SharedPreferences），与 [UiSettings] 同一套路子：
 * 不额外引入 DataStore，同步读取保证登录页首帧就显示上次选的学校。
 * 由 `ManyCourseApp.onCreate` 幂等初始化。
 *
 * 目前只存「上次选择的学校」。**不存账号密码**——那是敏感信息，
 * 真要记住得走 EncryptedSharedPreferences / Keystore，不要图省事写明文。
 */
object LoginSettings {

    private const val PREFS_NAME = "manycourse_login_prefs"
    private const val KEY_SCHOOL_ID = "school_id"

    private var prefs: SharedPreferences? = null

    /** 上次选择的学校 id；null = 没选过（登录页会强制用户先选一次） */
    val selectedSchoolId = mutableStateOf<String?>(null)

    /** 幂等初始化，由 ManyCourseApp.onCreate 调用 */
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        selectedSchoolId.value = p.getString(KEY_SCHOOL_ID, null)
    }

    /** 记住当前选择的学校（传 null 表示清空） */
    fun setSelectedSchoolId(schoolId: String?) {
        if (selectedSchoolId.value == schoolId) return
        selectedSchoolId.value = schoolId
        prefs?.edit()?.apply {
            if (schoolId == null) remove(KEY_SCHOOL_ID) else putString(KEY_SCHOOL_ID, schoolId)
        }?.apply()
    }
}
