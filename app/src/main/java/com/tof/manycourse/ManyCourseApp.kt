package com.tof.manycourse

import android.app.Application
import com.tof.manycourse.data.LoginSettings
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.data.UiSettings

/**
 * 应用入口：在任何 Activity 之前恢复偏好与登录会话 ——
 * UI 偏好（玻璃风格）保证登录页与主界面首帧就用上用户上次选择的样式；
 * 登录偏好（上次选的学校）保证登录页的下拉一打开就是对的学校；
 * **登录会话**（`SessionStore`）保证"上次登录过"的用户直接进主界面、不用再输密码。
 *
 * 顺序有讲究：`SessionStore` 会去改 `LoginSettings`（把恢复出来的学校同步进去），
 * 所以必须排在它后面。
 */
class ManyCourseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UiSettings.attach(this)
        LoginSettings.attach(this)
        SessionStore.attach(this)
    }
}
