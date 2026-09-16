package com.tof.manycourse.gr_api

import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.data.SchoolWeek

/**
 * 「按教学周查课表」能力（**可选**，日历页用）。
 *
 * 不是每所学校的教务系统都提供这种接口，所以它**独立于 [SchoolApi]**：
 * 实现了就多说一句话（`SchoolRegistry.apiOf(id) as? WeekScheduleApi`），
 * 没实现的学校日历页只能给**本周**兜底（本地课表按星期几循环，往后画就全是编的）。
 *
 * ## 谁实现了它（**这是个数据能力问题，不是想不想做的问题**）
 *
 * | 学校 | 实现了吗 | 为什么 |
 * |---|---|---|
 * | 广州软件学院 | ✅ | 有「周次课表」页（`N2154`），一次给一周，**还带着每一周的起止日期** |
 * | 金城学院 | ❌ | 它只有"**按班级查一张整学期表**"，拿不到"某一周有哪些课" —— 实现了也填不满日历，只是白搭请求。**不要顺手给它加**（日历页对它只兜底本周） |
 *
 * 这条边界由 `SchoolRegistryTest.onlySchoolsWithPerWeekQueries_implementWeekScheduleApi`
 * 钉着，避免以后"顺手补上"。
 *
 * ## 为什么值得单独做
 *
 * [SchoolApi.fetchSchedule] 拿到的是**整个学期**的课（每条带一个周次串，
 * 如 `3-5,8-20周`）。日历页要回答的是"某个具体日期有没有课"，
 * 而"每周循环 + 周次只作展示"的做法会把只在第 2-4 周上的课画满整个学期。
 *
 * 按周查则天然准确：**先要教学周列表（哪一周从哪天到哪天），再要某一周的课**，
 * 两者拼起来就是一份带真实日期的课表。
 *
 * 约定与 [SchoolApi] 一致：回调跑在 OkHttp 工作线程，调用方自己切回主线程；
 * 登录态失效时抛 [SessionExpiredException]。
 */
interface WeekScheduleApi {

    /**
     * 拉本学期（或页面默认学年学期）的**全部教学周**，按周次升序。
     *
     * 读的是课表查询页里那个周次下拉框 —— 服务端直接给出了每周的起止日期，
     * 比自己按"开学第一周"推算可靠得多。
     */
    fun fetchWeeks(callback: (Result<List<SchoolWeek>>) -> Unit)

    /**
     * 拉**某一周**的课程（只含这一周真的会上课的课程）。
     *
     * @param week 周次（[SchoolWeek.index]）
     */
    fun fetchWeekCourses(week: Int, callback: (Result<List<SchoolCourse>>) -> Unit)
}
