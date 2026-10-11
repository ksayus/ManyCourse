package com.tof.manycourse.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * **「下一节课」—— 桌面小组件与课前提醒共用的唯一解析口径。**
 *
 * ## 为什么必须有这一个归属
 *
 * 小组件要显示"下一节课"、提醒要知道"什么时候该发通知"，两处问的是同一个问题。
 * 各写一遍的话，迟早会出现"小组件写着下一节是英语、通知却报的高数"这种对不上的情况 ——
 * 与 `CourseEntries.kt` 里 [coursesOfDate] 当初被收拢成一处是同一个理由。
 *
 * ## 它建立在项目既有的两个唯一判据之上（**不另造一套**）
 *
 * | 问题 | 问谁 |
 * |---|---|
 * | 某一天课表上排了什么课 | [coursesOfDate]（课表页 / 日历页 / 我的页同源） |
 * | 这一天到底上不上课 | [dayIsLit]（放假置灰的唯一判据） |
 *
 * ## 一条容易漏掉、但代价很大的规则：**放假日必须整天跳过**
 *
 * 本项目对法定放假日的处理是「课照旧列在课表上，但一门都不点亮」——
 * 意思是**那天的课不上**。所以"下一节课"问到放假日时，正确回答是**跳过这一整天**
 * 去问下一天，而不是把那天第一门课当成"待上的课"。
 *
 * 漏了这条的表现很具体：国庆节早上七点，提醒把用户叫去教室。
 * 既有 [nextCourseKey] 只回答"当天高亮哪一门"，不看放假（那是课表页的职责），
 * 所以这条规则必须在这里补上 —— 已有单测钉住（`NextClassTest`）。
 *
 * ## 没有作息的块：**排不了提醒，也不编时刻**
 *
 * 落在**没有作息的块**里的课解不出时刻（见 [Timetable.Block]）：
 * 此时的取舍与 README §4.9 一致：
 *  - **小组件**照旧显示这一节课（写「第1-2节」而不是时刻）；
 *  - **提醒**整块停用（`forReminder` 返回 null）—— 没时刻就没法定闹钟，
 *    随便挑一个时刻等于编一个"看起来很确定"的时间骗人。
 */
data class ClassSlot(
    /** 这一课在哪一天 */
    val date: LocalDate,
    /** 课表上的那一条（名字 / 地点 / 节次都在里面） */
    val entry: CourseEntry,
    /** 开课时刻；**null = 这所学校还没收录作息** */
    val start: LocalTime?,
    /** 下课时刻；与 [start] 同生共死 */
    val end: LocalTime?,
    /**
     * 距开课还有多久，**相对解析时传进去的那个 `now`**。
     *
     * null = 没有可信时刻；零或负数 = 已经开课了（配合 [running] 用）。
     */
    val startsIn: Duration?,
    /** 解析那一刻，这门课是不是**正在上**（只有知道时刻、且就是今天才可能为 true）*/
    val running: Boolean,
) {
    val name: String get() = entry.name
    val room: String get() = entry.room
    val periodLabel: String get() = entry.periodLabel

    /** `9:00`（**不补前导零**，与 [Timetable] 里的写法一致）；没有作息表时为空串 */
    val startText: String get() = start?.let(::formatClock).orEmpty()

    /** `10:20`；没有作息表时为空串 */
    val endText: String get() = end?.let(::formatClock).orEmpty()

    /**
     * `9:00~10:20`；**没有作息表时是空串**。
     *
     * 空串而不是 `"~"`：调用方一律按 `isNotBlank` 过滤掉这一行/这一段，
     * 于是"没有作息表"就自然退化成只显示节次（「第1-2节」），不需要额外分支。
     */
    val timeRangeText: String
        get() = if (startText.isBlank() || endText.isBlank()) "" else "$startText~$endText"

    /** 「周一」…「周日」 */
    val weekdayText: String get() = weekdayLabel(entry.weekday)
}

/**
 * `LocalTime` → `9:00`。
 *
 * 刻意**不补前导零**：与 [Timetable.blocks] 里手写的作息表（`"9:00"`）保持一致，
 * 否则同一个时刻会在课表页显示 `9:00`、在小组件显示 `09:00`，看着像两个数据源。
 */
internal fun formatClock(time: LocalTime): String = "${time.hour}:%02d".format(time.minute)

/**
 * 「还有多久」的说法：`45 分钟` / `6 小时 18 分钟`。
 *
 * ## 为什么不能一路报分钟
 *
 * 凌晨两点打开桌面，下一节课是 8:30 —— 只写「还有 378 分钟」的话，
 * 用户得自己拿 378 除以 60 才知道那是六个多小时。**小时的量级要自己说出来**。
 *
 * ## 规则
 *
 * | 时长 | 文案 | 为什么 |
 * |---|---|---|
 * | < 1 分钟 | `不到 1 分钟` | 写「0 分钟」看着像坏了 |
 * | < 1 小时 | `45 分钟` | 这个量级下"小时"那半段恒为 0，写出来是噪音 |
 * | ≥ 1 小时 | `6 小时 18 分钟` | 整点小时时省掉「0 分钟」（`2 小时` 比 `2 小时 0 分钟` 顺眼）|
 *
 * 纯函数（`Duration` 进、`String` 出），可直接 JVM 单测。
 */
fun formatDistance(duration: Duration): String {
    val totalMinutes = duration.toMinutes()
    return when {
        totalMinutes < 1 -> "不到 1 分钟"
        totalMinutes < 60 -> "$totalMinutes 分钟"
        else -> {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            if (minutes == 0L) "$hours 小时" else "$hours 小时 $minutes 分钟"
        }
    }
}

/**
 * 「下一节课」的**纯解析核心**（不碰任何仓库 / 状态，可直接 JVM 单测）。
 *
 * 调用方一律走下面那个接了真实数据源的 [UpcomingClass]；这个 object 只回答算法问题。
 */
object NextClass {

    /** 最多向后找几天。7 天足够跨过周末（周五放学 → 周一的课）*/
    const val DEFAULT_LOOKAHEAD_DAYS = 7

    /**
     * **小组件要的**：不知道时刻也要把这节课显示出来（显示「第1-2节」而不是时刻）。
     *
     * @param days 某一天的课表；**null = 这一天没有任何可用数据**（与"这天确实没课"是两回事）
     * @param lit  这一天到底上不上课（放假返回 false）
     */
    fun nextForDisplay(
        timetable: Timetable,
        now: LocalDateTime,
        lookaheadDays: Int = DEFAULT_LOOKAHEAD_DAYS,
        days: (LocalDate) -> List<CourseEntry>?,
        lit: (LocalDate) -> Boolean,
    ): ClassSlot? = resolve(timetable, now, lookaheadDays, days, lit, requireTime = false)

    /**
     * **提醒要的**：只认**有可信时刻**的那一门 —— 没时刻就排不了闹钟。
     *
     * 这所学校整张作息表都没收录（[Timetable.hasTimes] = false）时恒返回 null，
     * 于是"课前提醒"对它是**整块停用**的（见 [ClassSlot] 的注释）。
     */
    fun nextForReminder(
        timetable: Timetable,
        now: LocalDateTime,
        lookaheadDays: Int = DEFAULT_LOOKAHEAD_DAYS,
        days: (LocalDate) -> List<CourseEntry>?,
        lit: (LocalDate) -> Boolean,
    ): ClassSlot? = resolve(timetable, now, lookaheadDays, days, lit, requireTime = true)

    private fun resolve(
        timetable: Timetable,
        now: LocalDateTime,
        lookaheadDays: Int,
        days: (LocalDate) -> List<CourseEntry>?,
        lit: (LocalDate) -> Boolean,
        requireTime: Boolean,
    ): ClassSlot? {
        val today = now.toLocalDate()
        val nowTime = now.toLocalTime()

        for (offset in 0..lookaheadDays.coerceAtLeast(0)) {
            val date = today.plusDays(offset.toLong())

            // ★ 放假日**整天跳过**：那天的课一门都不上（见类注释里那条规则），
            //   所以"下一节课"要落到下一个真正上课的日子上。提醒也跟着跳过。
            if (!lit(date)) continue

            // null = 这一天没有可用数据（不在已拉到的教学周里 / 压根没数据）。
            // 不当成"这天没课"往下走，而是**这一天没有候选** —— 跨过去继续找，
            // 找到的仍然是真数据；七天里一个都没有才是 null。
            val entries = days(date) ?: continue

            // 同一天里按节次排（不按时刻排：没有作息表时时刻全是空，节次才是唯一可比的顺序）
            for (entry in entries.sortedBy { it.startPeriod }) {
                val start = startOf(timetable, entry)
                val end = endOf(timetable, entry)

                // 提醒没有"没有时刻也凑合"这个选项：没时刻就换下一门
                if (requireTime && (start == null || end == null)) continue

                // 今天已经下课的课不算"下一节课"。end 为 null（这一条时刻脏）时**不判**，
                // 沿用项目既定口径"宁可信它还没上"（见 `nextCourseKey` 的注释）——
                // 宁可多显示一门，也不要把一门真会上、只是时刻没解析出来的课吞掉。
                if (offset == 0 && end != null && !nowTime.isBefore(end)) continue

                return ClassSlot(
                    date = date,
                    entry = entry,
                    start = start,
                    end = end,
                    startsIn = start?.let { Duration.between(now, date.atTime(it)) },
                    running = offset == 0 && start != null && end != null &&
                        !nowTime.isBefore(start) && nowTime.isBefore(end),
                )
            }
        }
        return null
    }

    /** 第 [CourseEntry.startPeriod] 节的开始时刻；越界或没作息表返回 null */
    private fun startOf(timetable: Timetable, entry: CourseEntry): LocalTime? =
        parseClock(timetable.startOfPeriod(entry.startPeriod))

    /**
     * 这门课**最后一节**的结束时刻。
     *
     * 连上几节时不能拿"第一节的结束"当整门课的结束：那样一节 3-4 连排的课，
     * 第二节课刚上课就会被判成"已经上完了"，小组件会直接跳到下一门。
     */
    private fun endOf(timetable: Timetable, entry: CourseEntry): LocalTime? {
        val lastPeriod = entry.startPeriod + entry.periodCount.coerceAtLeast(1) - 1
        return parseClock(timetable.endOfPeriod(lastPeriod))
    }

    /**
     * `9:00` → `LocalTime`；空串 / 脏数据 → null。
     *
     * 补零是因为 [Timetable] 里手写的时刻是 `9:00` 这种**不补零**的写法，
     * 而 `LocalTime.parse` 只吃 `09:00` —— 这一步与 `nextCourseKey` 的做法一致。
     */
    private fun parseClock(text: String): LocalTime? {
        if (text.isBlank()) return null
        return runCatching { LocalTime.parse(text.padStart(5, '0')) }.getOrNull()
    }
}

/**
 * [NextClass] 的**真实数据源适配层**：把纯算法接到项目既有的仓库上。
 *
 * 小组件与课前提醒都只调这里，绝不自己去读 `CourseRepository` / `WeekScheduleStore`
 * —— 否则"下一节课是哪门"又会散成好几份口径。
 */
object UpcomingClass {

    /** 当前该用哪张作息表（跟着"当前学校"走，与课表页 / 日历页同源）*/
    fun timetable(): Timetable = CourseRepository.timetable

    /**
     * ★ **有没有"可信的"课表数据** —— 桌面小组件靠它决定显示课程还是显示"打开 App 同步课表"。
     *
     * ## 为什么不直接看 `CourseRepository.courses` 空不空
     *
     * `CourseRepository.init` 里**预置了 7 门示例课**（高等数学 / 大学英语 / 计算机基础…）。
     * 那是给开发者看排版用的演示数据，**不是这个用户的课表**。
     * 直接读它的话，一个从没登录过的手机上，桌面小组件会理直气壮地显示"高等数学"——
     * 那是纯编造，而且用户没有任何办法知道它是假的。
     *
     * ## 判据
     *
     * ```
     * 磁盘上有这个账号的课表快照（ScheduleCache.savedAt != null）
     *   或 这次进程里已经成功拉到过教学周（WeekScheduleStore 为 Ready）
     * ```
     *
     * 两条都只可能由**教务系统的数据**满足；示例数据两条都不满足。
     * 退出登录时 `CourseSync.clearOnLogout()` 会把两者都清掉，所以登出后这里立刻变 false。
     */
    fun hasScheduleData(): Boolean =
        ScheduleCache.savedAt.value != null ||
            WeekScheduleStore.state.value is WeekScheduleStore.State.Ready

    /** 小组件要的：没有作息表也显示（写「第1-2节」）*/
    fun forDisplay(now: LocalDateTime = LocalDateTime.now()): ClassSlot? =
        NextClass.nextForDisplay(
            timetable = timetable(),
            now = now,
            days = ::entriesOn,
            lit = ::dayIsLit,
        )

    /** 提醒要的：**必须有可信时刻**，否则返回 null（这所学校整块不提醒）*/
    fun forReminder(now: LocalDateTime = LocalDateTime.now()): ClassSlot? =
        NextClass.nextForReminder(
            timetable = timetable(),
            now = now,
            days = ::entriesOn,
            lit = ::dayIsLit,
        )

    /** 某一天的课表 —— 直接复用课表页 / 日历页那一个唯一口径 */
    private fun entriesOn(date: LocalDate): List<CourseEntry>? = coursesOfDate(date)
}
