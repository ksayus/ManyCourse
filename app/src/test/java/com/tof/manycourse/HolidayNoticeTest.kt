package com.tof.manycourse

import com.tof.manycourse.data.HolidayDay
import com.tof.manycourse.data.HolidayKind
import com.tof.manycourse.ui.holidayBadgeLabel
import com.tof.manycourse.ui.holidayNoticeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

/**
 * 节假日提示文案的回归测试（纯函数，不需要真机）。
 *
 * 两句文案都是**用户唯一能看到的"为什么今天没课"**，所以有两件事必须钉住：
 *
 *  1. 它们真的把原因说出来了（放假 / 调休要分得开）；
 *  2. 它们**不能长得像「共 N 门课程」或「周X · N 门课程」** ——
 *     真机测试 `ScheduleCalendarParityTest` 正是拿这两种句式在界面上对账的，
 *     多出一个形似的节点，那条测试就会读到错的数字，而且是"偶尔红一次"那种最难查的红。
 */
class HolidayNoticeTest {

    private fun day(kind: HolidayKind, name: String) =
        HolidayDay(date = LocalDate.of(2026, 9, 26), kind = kind, name = name)

    @Test
    fun restDaySaysClassesDoNotHappenButAreStillListed() {
        // 放假**不是把课删掉**：那天的课照旧列在课表上，只是不点亮。
        // 所以文案说的是"当天的课都不上"，而不是"已滤除"（后者会让用户以为课表被改过）
        val text = holidayNoticeText(day(HolidayKind.Rest, "中秋节"))
        assertEquals("中秋节放假 · 当天的课都不上", text)
        assertFalse("不能说成『已滤除』：课照旧列着，只是不亮", text.contains("滤除"))
    }

    @Test
    fun makeUpWorkdaySaysClassesStillHappen() {
        // 调休上班日**有课**，文案不能说成"放假"，否则用户会以为可以不来
        val text = holidayNoticeText(day(HolidayKind.Work, "国庆节"))
        assertEquals("国庆节调休上班 · 按学校安排上课", text)
        assertFalse("调休上班不是放假", text.contains("放假"))
    }

    @Test
    fun noticeTextNeverLooksLikeACourseCount() {
        // 见类注释第 2 条：既不能出现「门课程」，也不能出现「周X · N 门课程」那种句式
        listOf(HolidayKind.Rest, HolidayKind.Work).forEach { kind ->
            val text = holidayNoticeText(day(kind, "国庆节"))
            assertFalse("提示文案里不能出现『门课程』：$text", text.contains("门课程"))
            assertFalse(
                "提示文案不能长得像课表页的『周X · N 门课程』：$text",
                Regex("""周[一二三四五六日] · \d+ 门课程""").containsMatchIn(text),
            )
        }
    }

    @Test
    fun badgeLabelUsesThePhoneCalendarConvention() {
        // 「休 / 班」是手机日历的通行写法，月历格子与周视图表头都用它
        assertEquals("休", holidayBadgeLabel(day(HolidayKind.Rest, "中秋节")))
        assertEquals("班", holidayBadgeLabel(day(HolidayKind.Work, "国庆节")))
    }
}
