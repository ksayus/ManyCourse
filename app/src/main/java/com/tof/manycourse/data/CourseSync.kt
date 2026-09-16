package com.tof.manycourse.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import com.tof.manycourse.gr_api.SchoolRegistry
import com.tof.manycourse.gr_api.SessionExpiredException
import com.tof.manycourse.gr_api.readableMessage

/**
 * 登录成功后把「学校系统里的名字 + 课表」同步进本地仓库。
 *
 * 数据流（需求：*根据登录的学校来获取*，*名字自动写成学校系统里显示的名字*）：
 *
 * ```
 *   MainActivity 登录成功 / App 冷启动恢复会话
 *        ↓ SessionStore.onLogin(学校, 账号)  或  SessionStore.attach() 恢复
 *   ManyCourseMain.onCreate
 *        ↓ CourseSync.sync(schoolId, account)
 *   SchoolRegistry.apiOf(schoolId)     ← 按学校分发（唯一的加学校入口）
 *        ├─ fetchProfile()  →  ProfileRepository.nickname / major
 *        └─ fetchSchedule() →  CourseRepository.replaceAll()
 * ```
 *
 * 线程：[sync] 的回调来自 OkHttp 工作线程，**所有仓库写入都切回主线程**
 * （`mutableStateListOf` / `mutableStateOf` 在非主线程改会触发 Compose 快照崩溃）。
 */
object CourseSync {

    /** 同步状态，供课表页显示"正在同步 / 同步失败原因 / 该重新登录了" */
    sealed interface State {
        /** 还没同步过（例如本地调试账号），或已退出登录 */
        data object Idle : State

        data object Loading : State

        /** 成功；[courses] 是拉到的课程数 */
        data class Ready(val courses: Int, val studentName: String) : State

        /** 失败；[message] 是可以直接展示的中文原因 */
        data class Failed(val message: String) : State

        /**
         * 登录态在服务端失效（Cookie 还在，但教务系统不认了）。
         *
         * 和 [Failed] 分开是因为**处理方式完全不同**：失败让你重试，
         * 过期只能重新登录 —— 反复点重试是点不好的。
         */
        data object Expired : State
    }

    val state = mutableStateOf<State>(State.Idle)

    /** 上次同步过的「学校 + 账号」；用来避免每次 `onCreate`（如转屏）都重新拉一遍 */
    private var lastSyncedKey: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 同步当前登录学校的数据。
     *
     * @param schoolId 登录的学校 id
     * @param account  登录账号
     * @param force    true = 忽略"已经同步过"，强制重拉（用于"重试"按钮）
     */
    fun sync(schoolId: String?, account: String, force: Boolean = false) {
        if (SessionStore.isLocalDebug.value) {
            state.value = State.Idle
            return
        }
        if (schoolId.isNullOrBlank()) {
            state.value = State.Failed("没有记录到登录的学校，请退出后重新登录")
            return
        }
        if (account.isBlank()) {
            state.value = State.Failed("没有记录到登录账号，请退出后重新登录")
            return
        }

        val key = "$schoolId|$account"
        // 失败/过期时不拦：转屏或再次进入主界面都允许自动重来一次
        val alreadyDone = state.value is State.Ready || state.value is State.Loading
        if (!force && key == lastSyncedKey && alreadyDone) return
        lastSyncedKey = key

        val api = SchoolRegistry.apiOf(schoolId)
        if (api == null) {
            state.value = State.Failed("这所学校（$schoolId）不在学校清单里了")
            return
        }

        state.value = State.Loading

        // 日历页的"按教学周"数据独立于课表：它走另一个接口，失败也不影响主课表，
        // 所以这里并行发起，不塞进下面的回调链里。
        // 不支持按周查的学校（没实现 WeekScheduleApi）会在里面直接返回。
        WeekScheduleStore.sync(schoolId, account, force)

        // 先拉学生信息：姓名要立刻写进「我的」页，且课表接口（金城学院）也依赖班号
        api.fetchProfile(account) { profile ->
            val name = profile.getOrNull()?.name.orEmpty()
            main {
                profile.onSuccess { student ->
                    // ★ 需求：当前的账户名字自动写成学校系统中会显示的名字
                    ProfileRepository.nickname.value = student.name
                    student.subtitle.takeIf { it.isNotBlank() }?.let {
                        ProfileRepository.major.value = it
                    }
                }

                // 学籍这一步就发现登录过期的话，不用再去拉课表了
                if (profile.isSessionExpired()) {
                    state.value = State.Expired
                    return@main
                }

                api.fetchSchedule(account) { schedule ->
                    main {
                        schedule.fold(
                            onSuccess = { list ->
                                CourseRepository.replaceAll(list)
                                state.value = State.Ready(list.size, name.ifBlank { account })
                                // 教务系统在访问过程中会续期 Cookie，同步成功顺手刷新存档，
                                // 让下次冷启动拿到的会话尽可能新鲜
                                SessionStore.persist()
                            },
                            onFailure = { error ->
                                state.value = if (error is SessionExpiredException) {
                                    State.Expired
                                } else {
                                    // 课表失败但姓名拿到了：名字保留，只报课表的错
                                    State.Failed(error.readableMessage() ?: "课表同步失败")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /** 登录成功/恢复会话时调用一次，让"已经同步过"的判断失效（换账号/换学校必须重拉）*/
    fun reset() {
        lastSyncedKey = null
        state.value = State.Idle
    }

    /**
     * 退出登录时清掉个人数据，避免下一个账号看到上一个人的信息。
     *
     * 课程也一并清空：它来自上一个账号的教务系统，留着就是数据串号。
     * 下次登录会由 [sync] 重新拉回来。
     */
    fun clearOnLogout() {
        reset()
        ProfileRepository.nickname.value = "未登录"
        ProfileRepository.major.value = ""
        CourseRepository.clear()
        WeekScheduleStore.clearOnLogout()
    }

    private fun Result<*>.isSessionExpired(): Boolean =
        exceptionOrNull() is SessionExpiredException

    /** 把一段逻辑切到主线程执行 */
    private fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
