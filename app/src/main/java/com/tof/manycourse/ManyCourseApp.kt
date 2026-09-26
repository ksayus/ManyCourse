package com.tof.manycourse

import android.app.Application
import com.tof.manycourse.data.LoginSettings
import com.tof.manycourse.data.MapPointStore
import com.tof.manycourse.data.ProfileRepository
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.data.WifiFingerprintStore
import com.tof.manycourse.data.ScheduleCache

/**
 * 应用入口：在任何 Activity 之前恢复偏好与登录会话 ——
 * UI 偏好（玻璃风格）保证登录页与主界面首帧就用上用户上次选择的样式；
 * 登录偏好（上次选的学校）保证登录页的下拉一打开就是对的学校；
 * **登录会话**（`SessionStore`）保证"上次登录过"的用户直接进主界面、不用再输密码。
 *
 * 顺序有讲究：`SessionStore` 会去改 `LoginSettings`（把恢复出来的学校同步进去），
 * 所以必须排在它后面；**个人资料**（昵称/专业，按账号分开存）要等会话恢复完才知道
 * 是哪个账号，所以绑定放在最后一步。
 */
class ManyCourseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UiSettings.attach(this)
        LoginSettings.attach(this)
        ProfileRepository.attach(this)
        SessionStore.attach(this)
        ScheduleCache.attach(this)
        // 开发者模式采集的**点位与标定**：读私有存档，并顺手把 /sdcard/ManyCourse/ 那份同步出来
        // （不依赖登录态：采集数据只属于这台设备，与"当前是谁登录"无关）
        MapPointStore.attach(this)
        // Wi-Fi 指纹库（室内"我在这儿"用）：同样是自己采的 + 随版本下发的
        WifiFingerprintStore.attach(this)
        // 会话恢复出来之后才知道"现在的资料属于谁"：把那个账号自己存过的昵称/专业读回来，
        // 于是冷启动首帧「我的」页显示的就是这个人自己那一份（用户改过的名字、或上次登录时
        // 学校同步回来的姓名 —— 两者都会落盘），而不是占位学生"张同学"。
        // ★ 这一步**不依赖网络**：离线打开（课表走本地缓存 / 静态数据）时同步补不回来，
        //   全靠这里读盘，所以「我的」页照样是对的。
        ProfileRepository.bindAccount(SessionStore.schoolId.value, SessionStore.account.value)
        ScheduleCache.prime(SessionStore.schoolId.value, SessionStore.account.value)
    }
}
