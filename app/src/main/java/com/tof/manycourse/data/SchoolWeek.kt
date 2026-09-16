package com.tof.manycourse.data

import java.time.LocalDate

/**
 * 一个**教学周**：第几周 + 这一周的起止日期。
 *
 * 教务系统的课表页会把「周次」做成一个下拉框，每一项自带日期区间
 * （广软实测：`3(2026-09-14至2026-09-20)`），所以"今天属于第几周"
 * **不用在客户端猜**（开学日期各校不同，猜必错），直接读页面给的就行。
 *
 * 有了它，日历页才能回答"2026-09-16 那天到底有没有课" ——
 * 本地课表只能按星期几循环，分不清"第 3 周有、第 20 周没有"。
 *
 * @param index 第几周（1 起）
 * @param start 周一
 * @param end   周日
 */
data class SchoolWeek(
    val index: Int,
    val start: LocalDate,
    val end: LocalDate,
) {
    /** [date] 是否落在这一周里（闭区间）*/
    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    /** 展示用：`第3周 09-14 ~ 09-20` */
    val label: String
        get() = "第${index}周 %02d-%02d ~ %02d-%02d".format(
            start.monthValue, start.dayOfMonth, end.monthValue, end.dayOfMonth,
        )
}

/**
 * 含 [date] 的那个**自然周**（周一~周日，ISO 口径）。
 *
 * ## 它和 [SchoolWeek] 不是一回事，别混用
 *
 * [SchoolWeek] 是**服务端给的教学周**（第几周、哪几天），只有支持按周查课表的学校才有；
 * 这个函数是"没有教学周数据时"的退路 —— 比如金城学院，日历页只肯给「本周」兜底，
 * 而当时手上并没有周次表，只能按自然周算。
 *
 * 两者的差别是**寒暑假和调休**：自然周不知道学校什么时候放假。
 * 所以它只用在"本来就拿不到权威数据"的兜底路径上，且只用一周（含今天的那一周），
 * 差也差不到哪去；**不要拿它去代替 [SchoolWeek] 判断"今天第几周"**。
 */
fun naturalWeekOf(date: LocalDate): ClosedRange<LocalDate> {
    // dayOfWeek.value：周一 = 1 … 周日 = 7
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    return monday..monday.plusDays(6)
}
