package com.tof.manycourse.data

import androidx.compose.runtime.mutableStateOf

/**
 * **手动刷新数据**的唯一入口（下拉刷新、页头的刷新按钮、失败提示条上的「重试」都走这里）。
 *
 * ## 为什么需要它
 *
 * 自动同步的时机只有两个：**进主界面**和**登录成功**。可"课表变了"这件事发生在
 * App 之外（学校补录、退了课、调了教室），而 [ScheduleCache] 还有"6 小时内不联网"的
 * TTL —— 也就是说，在用户看来一切正常的时候，数据完全可能是旧的，
 * 而以前**唯一的强制刷新入口是"同步失败"提示条上的「重试」**：
 * 只有它才会带 `force = true`。数据没出错时用户反而没有刷新按钮可点。
 *
 * ## 一次刷新拉到什么
 *
 * [CourseSync.sync] 且 `force = true`，它会连着把这几样都拉回来：
 *
 * ```
 *   学生信息（姓名/专业）→ 学期课表 → （内部再带上）教学周 + 按周课表
 * ```
 *
 * `force = true` 是**必须**的：不传就会命中"缓存够新"和"已经同步过"两道判断，
 * 用户下拉一次等于什么都没发生。
 *
 * ## 「刷新中」不在这里记
 *
 * 转圈看的是真实状态（[running]），不另存一份"我在刷新"的布尔值：
 * 多记一份就会和真实状态对不上 —— 转圈停了但请求还在飞，或者反过来请求结束了还在转。
 */
object DataRefresh {

    /**
     * 手动刷新的即时反馈；null = 无话可说。
     *
     * 只用于"**没刷成**"（本地调试账号 / 未登录 / 没记录到学校）。
     * 真的开始刷新之后的进展与结果由页面自己的状态条显示 —— 它本来就在显示
     * （课表页「正在从教务系统同步课表…」→「课表来源：…」，日历页「正在读取教学周课表…」）。
     */
    val notice = mutableStateOf<String?>(null)

    /**
     * 当前会话**能不能**手动刷新；不能时给出要展示给用户的原因（null = 能）。
     *
     * 页头按钮与下拉刷新都先问它：不能刷的时候**不转圈**，只把原因说出来 ——
     * 否则用户拉一下什么都没发生，只会以为刷新功能坏了。
     */
    val blocker: String?
        get() = refreshBlocker(
            // 与 `CourseSync.sync` 取学校 id 的口径完全一致：会话优先，兜底才用登录页选中的
            schoolId = SessionStore.schoolId.value ?: LoginSettings.selectedSchoolId.value,
            account = SessionStore.account.value,
            localDebug = SessionStore.isLocalDebug.value,
        )

    /**
     * 是不是正在刷新（页头按钮转圈、下拉的转圈都看它）。
     *
     * 两个仓库都看：一次手动刷新里，学期课表（[CourseSync]）和按周课表
     * （[WeekScheduleStore]）是**两条并行的链路**，谁先回来不一定。
     * 只看一条的话，另一条还在飞的时候转圈就停了。
     */
    val running: Boolean
        get() = CourseSync.state.value is CourseSync.State.Loading ||
            WeekScheduleStore.state.value is WeekScheduleStore.State.Loading

    /**
     * 发起一次手动刷新。
     *
     * @return true = 真的发起了联网刷新；false = 没刷成，原因写在 [notice] 里
     *   （调用方不必自己判断，直接展示 [notice] 就行）
     */
    fun start(): Boolean {
        val reason = blocker
        if (reason != null) {
            notice.value = reason
            return false
        }
        notice.value = null
        CourseSync.sync(
            schoolId = SessionStore.schoolId.value ?: LoginSettings.selectedSchoolId.value,
            account = SessionStore.account.value,
            force = true,
        )
        return true
    }

    /** 这条提示用户看过了（页面在几秒后调；见 `ui/RefreshUi.kt` 的 `RefreshNotice`）*/
    fun clearNotice() {
        notice.value = null
    }
}

/**
 * 「这次能不能刷新」的**纯逻辑部分**（可单测，见 `DataRefreshTest`）：能刷返回 null，
 * 不能刷返回要展示给用户的原因。
 *
 * 为什么值得单独拎出来测：这三种"刷不了"的情形**都不会联网、也不会报错**，
 * 界面不说明的话就是"点了没反应"。文案本身是这个功能唯一能被感知的部分，
 * 所以连文案一起钉住。
 *
 * 判断顺序有讲究：**本地调试账号排在最前面** —— 它有账号（`admin`）、
 * 登录页也要求选学校，所以后两条都拦不住它，漏了这条就会带着一个
 * "没连教务系统的假会话"去发请求。
 */
internal fun refreshBlocker(schoolId: String?, account: String, localDebug: Boolean): String? = when {
    localDebug -> "本地调试账号没连教务系统，没有可刷新的数据"
    account.isBlank() -> "未登录，先登录才能刷新数据"
    schoolId.isNullOrBlank() -> "没有记录到登录的学校，请退出后重新登录"
    else -> null
}
