package com.tof.manycourse.data

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * 课表缓存门面：**唯一的"课表 ↔ 磁盘"出入口**。
 *
 * ## 数据流
 *
 * ```
 *   冷启动 / 登录成功
 *        ↓ ScheduleCache.prime(学校, 账号)          ← 读盘
 *   CourseRepository.replaceAll(courses)            ← 课表页立刻有内容
 *   WeekScheduleStore.restore(weeks, coursesByWeek) ← 日历页立刻有内容
 *        ↓ （随后 CourseSync.sync 照常联网）
 *   联网成功 → saveCourses / saveWeekSchedule       ← 覆盖写盘
 *   联网失败 / 登录过期 → 什么都不做，界面继续显示已灌好的那一份
 *
 *   用户主动退出登录 → clearMemory()（**只丢内存，磁盘留着**）
 * ```
 *
 * ## 为什么是"先灌仓库"而不是"让 UI 去读缓存"
 *
 * 页面（课表页、日历页）读的是 `CourseRepository` / `WeekScheduleStore` 这两个仓库，
 * 不认识缓存。缓存要做的是**在网络回来之前把这两个仓库填成"上次的样子"**，
 * 这样 UI 一行都不用改就拿到了离线数据。
 *
 * ## 线程
 *
 * [prime] / `save*` **都必须在主线程调用**：它们写的是 Compose 状态
 * （`mutableStateListOf` / `mutableStateMapOf` / `mutableStateOf`），且内部有一个
 * 内存镜像 `current`，跨线程改会打架。现有调用点（`Application.onCreate`、
 * `SessionStore.onLogin`、`CourseSync`/`WeekScheduleStore` 的主线程回调）都在主线程。
 */
object ScheduleCache {

    /** 内存镜像：磁盘上那一份的"当前版本"，让多次局部保存能合并成一份完整快照 */
    private var currentKey: String? = null
    private var current: ScheduleSnapshot? = null

    private val savedAtState = mutableStateOf<Long?>(null)

    /**
     * 磁盘上那份课表的写入时刻；null = 这台机器上还没有可用的缓存。
     *
     * UI 用它显示"最后更新于 …"，也用来判断"过期时到底有没有缓存可以撑场面"。
     */
    val savedAt: State<Long?> get() = savedAtState

    /** 幂等初始化，由 `ManyCourseApp.onCreate` 调用 */
    fun attach(context: Context) {
        ScheduleVault.attach(context)
    }

    /**
     * 把 [schoolId] / [account] 这一档的缓存读出来灌进仓库。
     *
     * @return true = 真的恢复了内容（UI 无关，给"启动即可看缓存"那类判断用）
     *
     * 调用的三个时机都在主线程：`ManyCourseApp.onCreate`（冷启动恢复会话之后）、
     * `SessionStore.onLogin`（换账号之后）。
     */
    fun prime(schoolId: String?, account: String): Boolean {
        val key = profileStorageKey(schoolId, account) ?: return false
        if (key == currentKey && current != null) return false // 已经是这个账号了，别重复灌

        val text = ScheduleVault.load(key)
        if (text == null) {
            clearMemory()
            return false
        }
        val snapshot = ScheduleSnapshotCodec.decode(text)
        if (snapshot == null) {
            // 认不出来就丢掉：留着它只会在下次恢复时再失败一遍
            ScheduleVault.clear(key)
            clearMemory()
            return false
        }

        currentKey = key
        current = snapshot
        apply(snapshot)
        savedAtState.value = snapshot.savedAt.takeIf { it > 0 }
        return !snapshot.isEmpty
    }

    /**
     * 学期课表同步成功：写盘。
     *
     * @param studentName 已经解析好的姓名（`name.ifBlank { account }`），
     *   离线时那条"课表来源：某某"的提示条要用它
     */
    fun saveCourses(
        schoolId: String,
        account: String,
        courses: List<SchoolCourse>,
        studentName: String,
    ) {
        merge(schoolId, account) {
            it.copy(courses = courses, studentName = studentName)
        }
    }

    /** 教学周 + 已拉到的周课表同步成功：写盘 */
    fun saveWeekSchedule(
        schoolId: String,
        account: String,
        weeks: List<SchoolWeek>,
        coursesByWeek: Map<Int, List<SchoolCourse>>,
    ) {
        merge(schoolId, account) {
            it.copy(weeks = weeks, coursesByWeek = coursesByWeek)
        }
    }

    /**
     * 退出登录 / 换账号：把内存镜像清掉。
     *
     * ★ **磁盘一个字都不动** —— 和 `ProfileRepository.onLogout` 完全同一个取舍：
     * 盘上那份是"那个账号的课表"，不是上一个人的残渣。删了的话，用户下次用回
     * 自己的账号会发现课表要重新联网才有；而界面上的数据本来就会被
     * `CourseSync.clearOnLogout` 清掉，不存在"下一个账号看到上一个人的课表"。
     */
    fun clearMemory() {
        currentKey = null
        current = null
        savedAtState.value = null
    }

    /** 用户主动"清除缓存"：把这一档删掉（目前没接 UI，留着给设置页用）*/
    fun forget(schoolId: String?, account: String) {
        val key = profileStorageKey(schoolId, account) ?: return
        ScheduleVault.clear(key)
        if (key == currentKey) clearMemory()
    }

    /** 缓存是不是够新（TTL 判断；[savedAt] 为空 = 没缓存，返回 false）*/
    fun isFresh(ttlMillis: Long, now: Long = System.currentTimeMillis()): Boolean {
        val at = savedAtState.value ?: return false
        return now - at in 0..ttlMillis
    }

    private fun apply(snapshot: ScheduleSnapshot) {
        // 空表不灌：否则"这一档只存过教学周、还没存过课表"会把界面清成空白
        if (snapshot.courses.isNotEmpty()) CourseRepository.replaceAll(snapshot.courses)
        if (snapshot.weeks.isNotEmpty()) WeekScheduleStore.restore(snapshot.weeks, snapshot.coursesByWeek)
    }

    /**
     * 把一份局部更新合并进当前快照并写盘。
     *
     * 为什么要合并而不是各写各的：课表（`CourseSync`）和教学周（`WeekScheduleStore`）
     * 是**两条独立的网络链**，谁先回来不一定；各写一份完整的快照就会互相把对方的字段
     * 覆盖成旧值（课表存完了，教学周那边拿着上一次的课表又存一遍）。
     */
    private fun merge(schoolId: String, account: String, transform: (ScheduleSnapshot) -> ScheduleSnapshot) {
        val user = account.trim()
        val key = profileStorageKey(schoolId, user) ?: return
        val base = if (key == currentKey) current else null
        val next = transform(
            base ?: ScheduleSnapshot(schoolId = schoolId, account = user)
        ).copy(savedAt = System.currentTimeMillis())

        if (next.isEmpty) return // 空快照不写盘：别把上一次的好数据冲成空的

        currentKey = key
        current = next
        ScheduleVault.save(key, ScheduleSnapshotCodec.encode(next))
        savedAtState.value = next.savedAt
    }
}