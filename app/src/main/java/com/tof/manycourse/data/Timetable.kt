package com.tof.manycourse.data

import com.tof.manycourse.gr_api.SchoolRegistry

/**
 * 一所学校的**节次表（作息）**：一行 = 一个"两节块"，行数决定这所学校一天有几节。
 *
 * ## 为什么必须按学校分表
 *
 * 各校的节数**根本不一样**：广软、金城是 16 节（8 行），而**广东工业大学是 12 节**（6 行）。
 *
 * 早先全应用共用一张写死的 8 行表（16 节），等于替广工**多造了四节课**：
 * 「添加课程」能选到不存在的第 13-16 节，脏数据也会被夹到第 16 节而不是第 12 节。
 *
 * ## 时刻可以有，也可以没有（而且是**逐块**的）
 *
 * [Block.start] 为空串 = **这个块我们还没拿到准确作息**。此时宁可：
 *  - 时间轴这一行只画「第N-M节」、不画开始/结束时刻（`CalendarGridLayout.PeriodAxis`
 *    本来就是**逐行**判断的，所以"半张表"也能如实画出来，不需要整表开关）；
 *  - 落在这些块里的课**不排课前提醒**（`NextClass.nextForReminder` 只认有可信时刻的课）。
 *
 * 也不编一个时刻出来 —— 编出来的时刻会以"看起来很确定"的样子骗人，
 * 而"没时刻"至少是诚实的（与"读不到教学周就不猜开学日期"是同一条规矩）。
 *
 * @param blocks        每个"两节块"的起止（如 `("9:00", "10:20")`）；未知则 `("", "")`
 * @param periodMinutes 一节课多少分钟：块里第二节的开始时刻**没有显式给出**时用它推
 *   （见 [Block.secondStart]）
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
     * @param secondStart 块里**第二节的开始时刻**（`9:20`）；
     *   **空串 = 按 [Timetable.periodMinutes] 推**（块起点 + 一节课的时长）
     *
     *   ## 为什么需要这个字段
     *
     *   "两节连排"这件事**各校不一样**：默认表里块内两节等长（各 40 分钟，间隔 0），
     *   于是"第二节几点开始"能由"块起点 + 一节课"推出来。但有的学校**块内间隔是不等的** ——
     *   广工就是：第 1-2 节隔 5 分钟、第 7-8 节隔 15 分钟、第 9-10 节隔 **25** 分钟。
     *
     *   只给一个统一的 `periodMinutes` 硬推的话，第 8 节会算成 16:15（实际 **16:30**）、
     *   第 10 节会算成 18:05（实际 **18:30**）—— 课前提醒会早 15~25 分钟，
     *   而且是**把错误的时刻当成事实显示**（见 README §4.9 那条规矩）。
     *   所以宁可多这一个可选字段，也不猜。
     */
    data class Block(
        val start: String,
        val end: String,
        val secondStart: String = "",
    ) {
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
     * - 块里的**第一节**就是块的起点；
     * - 块里的**第二节**：学校给了 [Block.secondStart] 就用它（块内间隔不等的学校靠这个才准），
     *   没给才按 `块起点 + periodMinutes` 推。
     */
    fun startOfPeriod(period: Int): String {
        if (period !in 1..maxPeriod) return ""
        val block = blocks.getOrNull(blockIndexOf(period)) ?: return ""

        if (period % CourseRepository.PERIODS_PER_BLOCK == 1) return block.start

        if (block.secondStart.isNotBlank()) return block.secondStart
        val blockStart = parseTime(block.start) ?: return ""
        return formatTime(blockStart.plusMinutes(periodMinutes.toLong()))
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
     * **广东工业大学：12 节 / 6 个"两节块"，作息已收录**。
     *
     * ## 时刻从哪来
     *
     * 学校官方的**作息时间表**，逐条抄下来，**没有推算任何一项**：
     *
     * ```
     * 第1节 8:30-9:15    第2节 9:20-10:05   第3节 10:25-11:10  第4节 11:15-12:00
     * 第5节 13:50-14:35  第6节 14:40-15:25  第7节 15:30-16:15  第8节 16:30-17:15
     * 第9节 17:20-18:05  第10节 18:30-19:15 第11节 19:20-20:05 第12节 20:10-20:55
     * ```
     *
     * ★ **每个块的 [Timetable.Block.secondStart] 都必须显式写出来**：广工块内两节的间隔
     * **不是固定的**（5 / 5 / 5 / **15** / **25** / 5 分钟）。只给一个统一的 `periodMinutes`
     * 硬推的话，第 8 节会算成 16:15（实际 16:30）、第 10 节会算成 18:05（实际 18:30）——
     * 课前提醒会早 15~25 分钟，而且那是**把错误的时刻当成事实显示**。
     *
     * ## 节数：为什么是 12 而不是 14
     *
     * 学校课表页渲染的确实是**「第01节 … 第14节」14 行**（抓包原文在
     * `web_fetch/gdut_extract/244_GET__xsgrkbcx_xsAllKbList.action.res.txt`，可逐行核对：
     * `<td>第01节</td><td id="01-1">` … `<td>第14节</td><td id="14-1">`）。
     * 但**官方的作息时间表只定义到第 12 节**，真实课表里也没有任何一门课排在第 13 节之后 ——
     * 那两行是教务系统的**固定模板**，不是广工的作息。
     *
     * 所以按"作息是学校的事实"取 **12 节**。代价写在这里：万一服务端真返回第 13 节的课，
     * 它会被 `blockIndexOf` 夹到最后一行的第 12 节上（与其它脏数据的夹取行为一致）——
     * 真遇到了就把这里改回 7 块，并把第 7 个块的时刻一起补上即可。
     */
    val gdut = Timetable(
        blocks = listOf(
            Timetable.Block("8:30", "10:05", secondStart = "9:20"),
            Timetable.Block("10:25", "12:00", secondStart = "11:15"),
            Timetable.Block("13:50", "15:25", secondStart = "14:40"),
            Timetable.Block("15:30", "17:15", secondStart = "16:30"),
            Timetable.Block("17:20", "19:15", secondStart = "18:30"),
            Timetable.Block("19:20", "20:55", secondStart = "20:10"),
        ),
        // 一节课 45 分钟。注意：这不等于"上一节下课到下一节上课"的间隔 ——
        // 后者各块不同，由 secondStart 显式给出（见上）
        periodMinutes = 45,
    )

    /**
     * 按学校 id 取节次表。
     *
     * **没登记 / 未登录 / 本地调试**一律用 [default] —— 这类情况下课表本来就是用户自己加的，
     * 用最完整的那张表最不容易错。
     */
    fun of(schoolId: String?): Timetable = SchoolRegistry.find(schoolId)?.timetable ?: default
}
