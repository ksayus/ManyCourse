package com.tof.manycourse.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.tof.manycourse.gr_api.SchoolRegistry
import com.tof.manycourse.gr_api.SessionExpiredException
import com.tof.manycourse.gr_api.WeekScheduleApi
import com.tof.manycourse.gr_api.readableMessage
import java.time.LocalDate

/**
 * **日历页的数据源**：按"教学周"取课，让每个日期格子里显示的是**那天真的会上**的课。
 *
 * ## 为什么不能只用本地课表
 *
 * `CourseRepository` 里的课是"整个学期的课 + 一个周次串"（如 `2-4周`），
 * 渲染时按**星期几循环** —— 于是只在第 2-4 周上的军训会被画满整个学期。
 * 日历要回答的是"2026-09-16 到底有没有课"，这必须知道两件事：
 * 那天属于第几周、那一周有哪些课。
 *
 * ## 数据流
 *
 * ```
 *   登录后 CourseSync.sync()
 *        ↓ WeekScheduleStore.sync(schoolId, account)
 *   ① api.fetchWeeks()        → 教学周列表（第 3 周 = 09-14 ~ 09-20）  ← 服务端给的，不用猜
 *   ② api.fetchWeekCourses(n) → 第 n 周的课（只含这一周真的会上课的）
 *        ↑ 日历翻月 / 点某天时按需补拉（[ensureRange]），拉过的在内存里缓存
 *
 *   日历渲染：coursesOn(date) → null（这一周还没拉到）就走本地课表兜底
 * ```
 *
 * 不支持 [WeekScheduleApi] 的学校（如金城学院）这里全程空转，
 * 日历页对它**只给"本周"兜底**（其他周留空）—— 见 `ui/CalendarScreen.kt` 的注释。
 *
 * ## 线程
 *
 * 回调来自 OkHttp 工作线程，**所有 Compose 状态写入都切回主线程**
 * （`mutableStateListOf` / `mutableStateMapOf` 在非主线程改会触发快照崩溃）。
 */
object WeekScheduleStore {

    /** 教学周列表本身的状态，供日历页显示"正在加载 / 失败了" */
    sealed interface State {
        /** 没登录 / 这所学校不支持按周查 */
        data object Idle : State

        data object Loading : State

        /** 成功；[weeks] 是教学周数 */
        data class Ready(val weeks: Int) : State

        data class Failed(val message: String) : State

        /** 登录过期（和 CourseSync 的处理方式一致：只能重新登录）*/
        data object Expired : State
    }

    val state = mutableStateOf<State>(State.Idle)

    /** 教学周列表（按周次升序）；空 = 没有按周课表能力或还没同步 */
    val weeks = mutableStateListOf<SchoolWeek>()

    /** 周次 → 那一周的课；**只有拿到过才放进来**（放空表代表"这一周确实没课"）*/
    private val coursesByWeek = mutableStateMapOf<Int, List<SchoolCourse>>()

    /** 正在请求中 / 排队中的周次，避免同一周被并发拉好几遍 */
    private val pendingWeeks = mutableSetOf<Int>()

    /** 待拉取的周次队列；[MAX_CONCURRENT] 控制同时在飞的请求数 */
    private val queue = ArrayDeque<Int>()
    private var inFlight = 0

    /**
     * 「这是第几次登录的数据」计数器。
     *
     * 发起请求时记下当时的代数，回调回来先比一下：不相等说明中途退出登录 / 换过账号，
     * 这份结果属于上一个人，直接丢掉 —— 否则会把上一个人的课表写进下一个人的日历
     * （`CourseSync.clearOnLogout` 的注释里那条"数据串号"说的就是这件事）。
     */
    private var generation = 0

    /** 上次同步的「学校 + 账号」，用来避免转屏/重进页面时重复拉 */
    private var lastKey: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前登录学校支持"按周查课表"吗（不支持就返回 null，调用方静默跳过）*/
    fun apiOf(schoolId: String?): WeekScheduleApi? =
        SchoolRegistry.apiOf(schoolId) as? WeekScheduleApi

    /**
     * 拉教学周列表（登录成功后由 [CourseSync.sync] 调一次）。
     *
     * @param force true = 忽略"已经同步过"，强制重拉（用于"重试"）
     */
    fun sync(schoolId: String?, account: String, force: Boolean = false) {
        if (SessionStore.isLocalDebug.value) {
            state.value = State.Idle
            return
        }
        val api = apiOf(schoolId)
        if (api == null || account.isBlank()) {
            // 这所学校没有按周课表能力：保持 Idle，日历页会走本地课表兜底
            state.value = State.Idle
            return
        }

        val key = "$schoolId|$account"
        val alreadyDone = state.value is State.Ready || state.value is State.Loading
        if (!force && key == lastKey && alreadyDone) return
        lastKey = key

        state.value = State.Loading
        // 代数 +1：上一次同步留在路上的请求（列表 + 各周课程）全部作废，
        // 免得它们回来把这一次的结果盖掉
        val gen = ++generation
        api.fetchWeeks { result ->
            main {
                if (gen != generation) return@main
                result.fold(
                    onSuccess = { list ->
                        weeks.clear()
                        weeks.addAll(list)
                        coursesByWeek.clear()
                        pendingWeeks.clear()
                        queue.clear()
                        state.value = State.Ready(list.size)
                        // 顺手把"今天"那一周拉回来：用户切到日历页立刻就有内容
                        weekOf(LocalDate.now())?.let { ensureWeek(it.index) }
                    },
                    onFailure = { error ->
                        state.value = if (error is SessionExpiredException) {
                            State.Expired
                        } else {
                            State.Failed(error.readableMessage() ?: "教学周同步失败")
                        }
                    },
                )
            }
        }
    }

    /**
     * 把 [from]～[to] 之间覆盖到的教学周都拉一遍（日历翻月时调）。
     *
     * 已拉到 / 正在拉的会跳过，所以可以放心地反复调。
     */
    fun ensureRange(from: LocalDate, to: LocalDate) {
        weeks.filter { !it.end.isBefore(from) && !it.start.isAfter(to) }
            .forEach { ensureWeek(it.index) }
    }

    /** 按需拉某一周（有缓存 / 已在队列里就跳过）*/
    fun ensureWeek(week: Int) {
        if (week <= 0) return
        if (coursesByWeek.containsKey(week)) return
        if (!pendingWeeks.add(week)) return
        queue.addLast(week)
        drain()
    }

    /**
     * 把队列里的周次挨个发出去，**最多 [MAX_CONCURRENT] 个同时在飞**。
     *
     * 为什么要排队而不是一把全发：日历一次翻月可能覆盖 5~6 个周次，
     * 全发就是 6 个并发请求砸到学校服务器上；这里限成 2 个，
     * 用户看到第一个格子有数据的时间几乎一样，但给对方留了余地。
     */
    private fun drain() {
        while (inFlight < MAX_CONCURRENT && queue.isNotEmpty()) {
            val week = queue.removeFirst()
            val api = apiOf(SessionStore.schoolId.value)
            if (api == null || coursesByWeek.containsKey(week)) {
                pendingWeeks.remove(week)
                continue
            }
            inFlight++
            val gen = generation
            api.fetchWeekCourses(week) { result ->
                main {
                    if (gen != generation) return@main
                    inFlight--
                    pendingWeeks.remove(week)
                    result.fold(
                        onSuccess = { coursesByWeek[week] = it },
                        onFailure = { error ->
                            if (error is SessionExpiredException) state.value = State.Expired
                            // 单周失败不外抛：这一周渲染时回落到本地课表即可，
                            // 不值得把整张日历变成错误页
                        },
                    )
                    drain()
                }
            }
        }
    }

    /** 某个日期落在哪一教学周；不在任何教学周里（假期/未开学）返回 null */
    fun weekOf(date: LocalDate): SchoolWeek? = weeks.firstOrNull { it.contains(date) }

    /** 含今天的教学周；没有教学周数据（或今天在假期）返回 null */
    val currentWeek: SchoolWeek? get() = weekOf(LocalDate.now())

    /**
     * **当前教学周的全部课程**（不按星期过滤）—— 课表页显示的"本周课表"就是它。
     *
     * @return null = 这一周没有教学周数据（这所学校不支持按周查 / 还没拉到），
     *   调用方据此回落到本地课表。空表 = 拿到了，这一周真的没课。
     */
    fun currentWeekCourses(): List<SchoolCourse>? = currentWeek?.let { coursesByWeek[it.index] }

    /** 某个日期所在教学周的课程；语义与 [currentWeekCourses] 一致 */
    fun weekCoursesOf(date: LocalDate): List<SchoolCourse>? = weekOf(date)?.let { coursesByWeek[it.index] }

    /**
     * 某个日期的课程。
     *
     * @return **null = 这一周的数据还没拿到**（日历页据此回落到本地课表），
     *   空表 = 拿到了，那天确实没课 —— 两者必须区分开，
     *   否则"这周还没加载"会被显示成"今天没课"。
     */
    fun coursesOn(date: LocalDate): List<SchoolCourse>? =
        weekCoursesOf(date)?.filter { it.weekday == date.dayOfWeek.value }

    /** 退出登录：连教学周一起清掉（否则下一个账号会看到上一个人的课表）*/
    fun clearOnLogout() {
        // 代数 +1：在飞的那几个请求回来时会被认成"上一个人的数据"而丢弃
        generation++
        lastKey = null
        pendingWeeks.clear()
        queue.clear()
        coursesByWeek.clear()
        weeks.clear()
        inFlight = 0
        state.value = State.Idle
    }

    /** 同时在飞的取周次请求上限（见 [drain]）*/
    private const val MAX_CONCURRENT = 2

    /** 把一段逻辑切到主线程执行 */
    private fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
