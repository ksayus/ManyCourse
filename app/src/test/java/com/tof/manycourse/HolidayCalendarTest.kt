package com.tof.manycourse

import com.tof.manycourse.data.HolidayCalendar
import com.tof.manycourse.data.HolidayKind
import com.tof.manycourse.data.HolidaySpan
import com.tof.manycourse.data.expandHolidaySpans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 内置节假日表的回归测试。
 *
 * ## 为什么这套数据必须逐条钉住
 *
 * 抄错一个日期**不会崩、不会报错**，只会让某一天的课表凭空少掉一整天的课
 * （或者反过来：国庆当天照常排满课）。这类错用户不一定马上发现，发现了也只会觉得"这 App 不准"。
 * 所以这里的每一条断言都对着《国务院办公厅关于X年部分节假日安排的通知》的**原文**
 * （2025 年：2024-11-12；2026 年：2025-11-04），核的是三件事：
 *
 *  1. **天数**（通知里写着"共N天"）；
 *  2. **起止那两天是星期几**（通知里写着"（周四）""（农历正月初七、周一）"）——
 *     星期一对不上，说明区间整体错位了；
 *  3. **调休上班日必须是周末**（通知里写的是"（周日）上班""（周六）上班"）——
 *     这条能拦住"把放假区间和上班日抄反"这类错。
 *
 * 加新一年数据时，把当年的通知对照着往下加一组即可（`spans` 里已有格式示例）。
 */
class HolidayCalendarTest {

    /** 通知里的一句话：节日名 / 起 / 止 / 共几天 / 起止分别是周几（1=周一 … 7=周日）*/
    private data class RestExpectation(
        val name: String,
        val start: LocalDate,
        val end: LocalDate,
        val days: Int,
        val startWeekday: Int,
        val endWeekday: Int,
    )

    /**
     * 2025 年通知的六句话（原文见 `data/HolidayCalendar.kt` 的注释）。
     *
     * 注意 2025 年元旦是「放假1天，不调休」、国庆与中秋**合并**放假 8 天 ——
     * 这两条是那一年的特例，写在这里也顺便说明"表为什么不能按固定模板生成"。
     */
    private val rest2025 = listOf(
        RestExpectation("元旦", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 1), 1, 3, 3),
        RestExpectation("春节", LocalDate.of(2025, 1, 28), LocalDate.of(2025, 2, 4), 8, 2, 2),
        RestExpectation("清明节", LocalDate.of(2025, 4, 4), LocalDate.of(2025, 4, 6), 3, 5, 7),
        RestExpectation("劳动节", LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 5), 5, 4, 1),
        RestExpectation("端午节", LocalDate.of(2025, 5, 31), LocalDate.of(2025, 6, 2), 3, 6, 1),
        RestExpectation("国庆节·中秋节", LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 8), 8, 3, 3),
    )

    /** 2026 年通知的七句话 */
    private val rest2026 = listOf(
        RestExpectation("元旦", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3), 3, 4, 6),
        RestExpectation("春节", LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 23), 9, 7, 1),
        RestExpectation("清明节", LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 6), 3, 6, 1),
        RestExpectation("劳动节", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5), 5, 5, 2),
        RestExpectation("端午节", LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 21), 3, 5, 7),
        RestExpectation("中秋节", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 27), 3, 5, 7),
        RestExpectation("国庆节", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), 7, 4, 3),
    )

    /** 通知里每一句"X月X日（周X）上班"（2025 年 5 天；2026 年 6 天）*/
    private val work2025 = listOf(
        LocalDate.of(2025, 1, 26) to "春节",
        LocalDate.of(2025, 2, 8) to "春节",
        LocalDate.of(2025, 4, 27) to "劳动节",
        LocalDate.of(2025, 9, 28) to "国庆节",
        LocalDate.of(2025, 10, 11) to "国庆节",
    )

    private val work2026 = listOf(
        LocalDate.of(2026, 1, 4) to "元旦",
        LocalDate.of(2026, 2, 14) to "春节",
        LocalDate.of(2026, 2, 28) to "春节",
        LocalDate.of(2026, 5, 9) to "劳动节",
        LocalDate.of(2026, 9, 20) to "国庆节",
        LocalDate.of(2026, 10, 10) to "国庆节",
    )

    private val allRest = rest2025 + rest2026

    @Test
    fun everyRestSpanHasTheDayCountTheNoticeStated() {
        // 通知里"共N天"是硬指标：区间抄错一天，这里立刻红
        allRest.forEach { expected ->
            val span = HolidayCalendar.spans.firstOrNull { it.start == expected.start && it.kind == HolidayKind.Rest }
            requireNotNull(span) { "内置表里找不到 ${expected.name} 的放假区间（${expected.start}）" }
            assertEquals(
                "${expected.name}：通知说的是共 ${expected.days} 天",
                expected.days,
                span.days,
            )
            assertEquals("${expected.name} 的结束日期", expected.end, span.end)
        }
    }

    @Test
    fun everyRestSpanStartsAndEndsOnTheWeekdayTheNoticeStated() {
        // 起止那两天是周几：通知里写着"（周四）""（农历正月初七、周一）"——
        // 这一条能拦住"区间整体错位一天"（天数还对得上、但日子全错一天）这种最阴的错法
        allRest.forEach { expected ->
            val span = HolidayCalendar.spans.single { it.start == expected.start && it.kind == HolidayKind.Rest }
            assertEquals(
                "${expected.name} 开始那天（${expected.start}）应当是周${expected.startWeekday}",
                expected.startWeekday,
                span.start.dayOfWeek.value,
            )
            assertEquals(
                "${expected.name} 结束那天（${expected.end}）应当是周${expected.endWeekday}",
                expected.endWeekday,
                span.end.dayOfWeek.value,
            )
        }
    }

    @Test
    fun everyRestDayInTheTableIsADayTheCalendarCallsARestDay() {
        // 表里的每一天都要查得到、且身份是"放假"（区间铺开这一步不能漏日期）
        allRest.forEach { expected ->
            var date = expected.start
            while (!date.isAfter(expected.end)) {
                val day = HolidayCalendar.of(date)
                requireNotNull(day) { "$date 在通知的放假区间里，但表里查不到" }
                assertEquals("$date 应当是放假", HolidayKind.Rest, day.kind)
                assertEquals("$date 的节日名", expected.name, day.name)
                assertTrue("$date 应当被判定为放假日", HolidayCalendar.isRestDay(date))
                date = date.plusDays(1)
            }
        }
    }

    @Test
    fun makeUpWorkdaysAreWeekendDaysAndNeverOverlapRestDays() {
        // "X月X日（周六/周日）上班"：调休上班日必然是周末（这是调休的定义），
        // 而且绝不能同时又是放假日 —— 抄反了就是"周六放假、周日上课"这种离谱结果
        (work2025 + work2026).forEach { (date, name) ->
            val day = HolidayCalendar.of(date)
            requireNotNull(day) { "$date 在通知里是上班日，但表里查不到" }
            assertEquals("$date 应当是调休上班", HolidayKind.Work, day.kind)
            assertEquals("$date 的节日名", name, day.name)
            assertFalse("$date 是调休上班日，不是放假日", day.isRest)
            assertFalse("$date 是调休上班日，课表照常点亮（不置灰）", HolidayCalendar.isRestDay(date))
            assertTrue(
                "$date 是调休上班日，通知里写的就该是周六或周日（实际 ${date.dayOfWeek}）",
                date.dayOfWeek.value >= 6,
            )
        }
    }

    @Test
    fun theDaysJustOutsideAHolidayAreNotRestDays() {
        // 边界：放假前一天 / 后一天都**不是放假日** —— 差一天的错法最伤
        // （9月25日放假，那 9月24日 就还照常上课）。
        //
        // ★ 这里断的是 isRestDay（"课要不要不亮"），不是 of() == null（"是不是什么特别日子"）：
        //   两者不是一回事 —— 2026-01-04 紧跟在元旦假期后面，它本身是**调休上班日**，
        //   照常上课、只是被标记出来。断 of() == null 的话，正确的数据反而会被判错
        allRest.forEach { expected ->
            val before = expected.start.minusDays(1)
            val after = expected.end.plusDays(1)
            assertFalse(
                "$before 在 ${expected.name} 前一天，课照常点亮（不置灰）",
                HolidayCalendar.isRestDay(before),
            )
            assertFalse(
                "$after 在 ${expected.name} 后一天，课照常点亮（不置灰）",
                HolidayCalendar.isRestDay(after),
            )
        }
    }

    @Test
    fun theWorkdayRightAfterNewYearIsNotMistakenForHoliday() {
        // 上一条的具体化：2026 年元旦是 1月1~3日放假，**1月4日（周日）上班**。
        // "假期紧接着的那个调休上班日"最容易被顺手写进放假期里（差一天的经典错法），
        // 所以单独钉住：它必须是"调休上班"，而不是"放假"
        val afterNewYear = LocalDate.of(2026, 1, 4)
        assertFalse("1月4日是上班日，课表照常点亮", HolidayCalendar.isRestDay(afterNewYear))
        assertEquals(HolidayKind.Work, HolidayCalendar.of(afterNewYear)?.kind)
        assertEquals("元旦", HolidayCalendar.of(afterNewYear)?.name)
    }

    @Test
    fun theMidAutumnWindowOfTodayIsCovered() {
        // 2026 年中秋节（9月25日~27日）是"今天"所在的假期 —— 内置表必须认出来，
        // 否则用户在假期里打开 App 会看到一屏本该不上的课
        assertEquals("中秋节", HolidayCalendar.of(LocalDate.of(2026, 9, 25))?.name)
        assertEquals("中秋节", HolidayCalendar.of(LocalDate.of(2026, 9, 26))?.name)
        assertEquals("中秋节", HolidayCalendar.of(LocalDate.of(2026, 9, 27))?.name)
        assertTrue(HolidayCalendar.isRestDay(LocalDate.of(2026, 9, 26)))
        // 而调休上班的那个周日不置灰（只是标出来）
        assertEquals(HolidayKind.Work, HolidayCalendar.of(LocalDate.of(2026, 9, 20))?.kind)
        assertFalse(HolidayCalendar.isRestDay(LocalDate.of(2026, 9, 20)))
    }

    @Test
    fun yearsWithoutDataAreNotFilteredAndSaySo() {
        // 表外年份：**一律不过滤**（宁可照常显示课表，也不拿"一般是10月1日放假"去猜）。
        // 页面靠 covers() 如实说明"当年数据还没收录"
        listOf(2024, 2027, 2028).forEach { year ->
            assertFalse("$year 年不在内置表里", HolidayCalendar.covers(year))
            assertNull(HolidayCalendar.of(LocalDate.of(year, 10, 1)))
            assertFalse("$year 年的国庆不该被过滤（数据没收录）", HolidayCalendar.isRestDay(LocalDate.of(year, 10, 1)))
        }
        assertTrue(HolidayCalendar.covers(2025))
        assertTrue(HolidayCalendar.covers(2026))
        assertEquals("2025-2026", HolidayCalendar.coverageLabel)
    }

    @Test
    fun theTablesYearsComeFromTheDataItself() {
        // 年份是从 spans 现算的：加完新一年忘了改常量这种"功能静默失效"就无从发生
        assertEquals(setOf(2025, 2026), HolidayCalendar.years.toSet())
    }

    @Test
    fun expandingSpansWalkseveryDayOfARangeInclusive() {
        // 铺表是纯函数：闭区间、首尾都在、相邻区间不串味
        val table = expandHolidaySpans(
            listOf(
                HolidaySpan(
                    name = "测试节",
                    kind = HolidayKind.Rest,
                    start = LocalDate.of(2030, 3, 1),
                    end = LocalDate.of(2030, 3, 3),
                ),
                HolidaySpan(
                    name = "测试节",
                    kind = HolidayKind.Work,
                    start = LocalDate.of(2030, 3, 8),
                    end = LocalDate.of(2030, 3, 8),
                ),
            ),
        )

        assertEquals(4, table.size)
        assertTrue(table.keys.containsAll((1..3).map { LocalDate.of(2030, 3, it) }))
        assertEquals(HolidayKind.Work, table[LocalDate.of(2030, 3, 8)]?.kind)
        assertNull(table[LocalDate.of(2030, 3, 4)])
    }

    @Test
    fun noDateIsSpannedTwice() {
        // 同一天被两条区间盖住 = 表抄错了（铺表时后者会静默盖掉前者）。
        // 这里直接按"日期总数 == 各区间天数之和"来拦
        val expanded = expandHolidaySpans(HolidayCalendar.spans)
        val declared = HolidayCalendar.spans.sumOf { it.days }
        assertEquals(
            "各区间天数之和应当等于铺出来的日期数（不等就是有日期被两条区间盖住了）",
            declared,
            expanded.size,
        )
    }

    @Test
    fun restDayLabelReadsLikeChinese() {
        // 文案：放假 → `中秋节放假`；调休 → `国庆节调休上班`
        assertEquals("中秋节放假", HolidayCalendar.of(LocalDate.of(2026, 9, 26))?.label)
        assertEquals("国庆节调休上班", HolidayCalendar.of(LocalDate.of(2026, 10, 10))?.label)
    }
}
