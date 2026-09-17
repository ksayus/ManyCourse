package com.tof.manycourse.data

import com.tof.manycourse.gr_api.SchoolRegistry

/**
 * 一所学校的**节次表（作息）**：一行 = 一个"两节块"，行数决定这所学校一天有几节。
 *
 * ## 为什么必须按学校分表
 *
 * 各校的节数**根本不一样**：广软、金城是 16 节（8 行），而**广东工业大学是 14 节**——
 * 它自己的课表页（`xsgrkbcx!xsAllKbList.action`，抓包原文见
 * `web_fetch/gdut_extract/244_GET__xsgrkbcx_xsAllKbList.action.res.txt`）渲染的就是
 * 「第01节 … 第14节」这 14 行。
 *
 * 早先全应用共用一张写死的 8 行表（16 节），等于替广工**多造了两节课**：
 * 「添加课程」能选到不存在的第 15-16 节，脏数据也会被夹到第 16 节而不是第 14 节。
 *
 * ## 时刻可以有，也可以没有
 *
 * [Block.start] 为空串 = **这所学校我们还没拿到准确作息**。此时宁可：
 *  - 时间轴只画「第N-M节」那一行（不画开始/结束时刻，见 `CalendarGridLayout.PeriodAxis`）；
 *  - "现在上到第几节""下一节课"这类依赖时刻的功能**整块停用**（它们要的是真值）；
 *
 * 也不编一个时刻出来 —— 编出来的时刻会以"看起来很确定"的样子骗人，
 * 而"没时刻"至少是诚实的（与"读不到教学周就不猜开学日期"是同一条规矩）。
 *
 * @param blocks        每个"两节块"的起止（如 `("9:00", "10:20")`）；未知则 `("", "")`
 * @param periodMinutes 一节课多少分钟：用它把"块起点"推成块里两节各自的起止时刻
 */
data class Timetable(
    val blocks: List<Block>,
    val periodMinutes: Int,
) {

    /**
     * 一个"两节块"（时间轴的一行）。
     *
     * @param start 块里**第一节的开始时刻**（`9:00`）；空串 = 不知道
     * @param end   块里**第二节的结束时刻**（`10:20`）；空串 = 不知道
     */
    data class Block(val start: String, val end: String) {
        /** 这个块有没有可信的起止时刻 */
        val known: Boolean get() = start.isNotBlank() && end.isNotBlank()
    }

    /** 一天有几个"两节块"（= 周视图的行数上限）*/
    val blockCount: Int get() = blocks.size

    /** 一天有几节（= 块数 × 每块两节）*/
    val maxPeriod: Int get() = blockCount * CourseRepository.PERIODS_PER_BLOCK

    /**
     * 整张表有没有可信时刻。
     *
     * 用"**全部**块都有"而不是"部分有"：半张表会让时间轴一半写时刻、一半空着，
     * 看着像画错了；而且"现在上到第几节"只要少一块就无法判断。
     */
    val hasTimes: Boolean get() = blocks.isNotEmpty() && blocks.all { it.known }

    /**
     * 第 [period] 节落在第几个块（0 起）；越界夹到最近的块。
     *
     * 夹取（而不是返回 null）是给脏数据兜底的：解析出"第 99 节"时宁可画在最后一行，
     * 也不能让这门课从网格里消失。
     */
    fun blockIndexOf(period: Int): Int {
        if (blockCount == 0) return 0
        return ((period.coerceAtLeast(1) - 1) / CourseRepository.PERIODS_PER_BLOCK)
            .coerceAtMost(blockCount - 1)
    }

    /** 某个块的开始时刻（周视图时间轴的第一行）；越界或未知返回空串 */
    fun startOfBlock(blockIndex: Int): String = blocks.getOrNull(blockIndex)?.start.orEmpty()

    /** 某个块的结束时刻（周视图时间轴的最后一行）；越界或未知返回空串 */
    fun endOfBlock(blockIndex: Int): String = blocks.getOrNull(blockIndex)?.end.orEmpty()

    /**
     * 第 [period] 节的开始时刻；**节次越界或这所学校没有时刻表时返回空串**。
     *
     * 块内两节各 [periodMinutes] 分钟：奇数节就是块起点，偶数节是块起点 + 一节的时长。
     */
    fun startOfPeriod(period: Int): String {
        if (period !in 1..maxPeriod) return ""
        val blockStart = startOfBlock(blockIndexOf(period))
        if (blockStart.isBlank()) return ""
        return if (period % CourseRepository.PERIODS_PER_BLOCK == 1) {
            blockStart
        } else {
            parseTime(blockStart)?.let { formatTime(it.plusMinutes(periodMinutes.toLong())) }.orEmpty()
        }
    }

    /** 第 [period] 节的结束时刻（开始 + [periodMinutes]）；未知返回空串 */
    fun endOfPeriod(period: Int): String {
        val start = parseTime(startOfPeriod(period)) ?: return ""
        return formatTime(start.plusMinutes(periodMinutes.toLong()))
    }

    /** `9:00` → LocalTime；解析不出返回 null（脏数据不让页面崩） */
    private fun parseTime(text: String): java.time.LocalTime? = runCatching {
        java.time.LocalTime.parse(text.padStart(5, '0'))
    }.getOrNull()

    /** LocalTime → `9:40`（**不补前导零**，与表里的写法保持一致）*/
    private fun formatTime(time: java.time.LocalTime): String =
        "${time.hour}:%02d".format(time.minute)
}

/**
 * 各校的节次表 + 「按学校 id 取表」。
 *
 * 表挂在 [School] 上（`School.timetable`，默认 [default]）—— 加一所学校时，
 * 节次表在学校自己的那个文件里写（与登录参数、课表接口放在一起），不必回来改这里。
 */
object Timetables {

    /**
     * **默认表：16 节 / 8 个"两节块"**（广软、金城这两所正方系统的学校）。
     *
     * ```
     * 1-2   9:00-10:20      9-10   15:30-16:50
     * 3-4  10:40-12:00     11-12   17:00-18:20
     * 5-6  12:30-13:50     13-14   19:00-20:20
     * 7-8  14:00-15:20     15-16   20:30-21:50
     * ```
     *
     * 为什么按"块"存而不是按"节"存：课表本来就是两节连排（连上 80 分钟、块间休息 10~40 分钟），
     * 周视图是**一行两节**、时间轴也按块写；把块起止写在一张表里，
     * 块内的两节（各 [Timetable.periodMinutes] 分钟）由它推出来，就不会出现"表和展示对不上"。
     * 单测 `TimetableTest` / `ScheduleGridSlotsTest` 把这 8 行逐条钉住。
     */
    val default = Timetable(
        blocks = listOf(
            Timetable.Block("9:00", "10:20"),
            Timetable.Block("10:40", "12:00"),
            Timetable.Block("12:30", "13:50"),
            Timetable.Block("14:00", "15:20"),
            Timetable.Block("15:30", "16:50"),
            Timetable.Block("17:00", "18:20"),
            Timetable.Block("19:00", "20:20"),
            Timetable.Block("20:30", "21:50"),
        ),
        periodMinutes = 40,
    )

    /**
     * **广东工业大学：14 节 / 7 个"两节块"**。
     *
     * 节数来自它自己的课表页：服务端渲染的就是「第01节 … 第14节」14 行
     * （抓包原文在 `web_fetch/gdut_extract/244_…xsAllKbList…res.txt`，单测钉住 14 节 / 7 块）。
     *
     * ★ **时刻是空的，这是刻意的**：那次抓包里只有节数，没有作息；`getKbRq` 的 JSON 里
     * 也没有时间字段，学校页面上也没有作息表。所以这张表只画「第N-M节」，
     * 等拿到准确的作息（第 1 节几点到几点…）再往 [blocks] 里填 —— 填上之后
     * 时间轴的时刻、"现在上到第几节"会自动开始工作，不用改别处。
     */
    val gdut = Timetable(
        blocks = List(7) { Timetable.Block(start = "", end = "") },
        // 时刻未知时这个数字用不上（推不出任何东西），先沿用默认表的 40 分钟
        periodMinutes = 40,
    )

    /**
     * 按学校 id 取节次表。
     *
     * **没登记 / 未登录 / 本地调试**一律用 [default] —— 这类情况下课表本来就是用户自己加的，
     * 用最完整的那张表最不容易错。
     */
    fun of(schoolId: String?): Timetable = SchoolRegistry.find(schoolId)?.timetable ?: default
}
