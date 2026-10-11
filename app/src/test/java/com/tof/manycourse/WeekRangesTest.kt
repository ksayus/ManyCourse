package com.tof.manycourse

import com.tof.manycourse.data.Course
import com.tof.manycourse.data.WeekRanges
import com.tof.manycourse.data.appliesInWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周次解析的单测。
 *
 * ## 样本全部来自**实测**，不是我编的
 *
 * | 来源 | 周次串 |
 * |---|---|
 * | 广软 `zcd`（`GzusScheduleParserTest` / 抓包） | `4-19周` · `5-18周` · `2-3周` · `3周` |
 * | 金城 HTML（`NhjcPageParserTest`） | `3-5,8-20周` · `2-4周` · `2-5,8-13周` · `12-19周` |
 * | 广工 `zcs`（实测乱序） | `16,7,8,9,10,11,17,12,13,14,15` → `7-17周` |
 *
 * ## 这个功能的失败方式很特别
 *
 * 解析错了**不会崩、不会报错**，只会表现为"课表上少了一门课"或者"多了一门课"。
 * 少的那一种更坏：学生不会知道少了什么，只会白等或错过。
 * 所以下面有一整组断言专门盯着"认不出来时**不许过滤**"。
 */
class WeekRangesTest {

    // ══════════════════ 实测样本 ══════════════════

    @Test
    fun `真实样本_区间`() {
        assertEquals(setOf(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19), WeekRanges.parse("4-19周"))
        assertEquals(setOf(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18), WeekRanges.parse("5-18周"))
        assertEquals(setOf(2, 3), WeekRanges.parse("2-3周"))
        assertEquals(setOf(3), WeekRanges.parse("3周"))
    }

    @Test
    fun `真实样本_多段逗号`() {
        assertEquals(setOf(3, 4, 5) + (8..20).toSet(), WeekRanges.parse("3-5,8-20周"))
        assertEquals(setOf(2, 3, 4), WeekRanges.parse("2-4周"))
        assertEquals(setOf(2, 3, 4, 5) + (8..13).toSet(), WeekRanges.parse("2-5,8-13周"))
        assertEquals((12..19).toSet(), WeekRanges.parse("12-19周"))
    }

    /** 广工的 `zcs` 是**完全乱序**的逗号串，实测就是这样 */
    @Test
    fun `真实样本_广工乱序`() {
        val weeks = WeekRanges.parse("16,7,8,9,10,11,17,12,13,14,15")
        assertEquals((7..17).toSet(), weeks)
        assertEquals("归一化成连续区间", "7-17周", WeekRanges.normalize("16,7,8,9,10,11,17,12,13,14,15"))
    }

    // ══════════════════ appliesTo：三态 ══════════════════

    @Test
    fun `覆盖这一周返回 true_不覆盖返回 false`() {
        assertEquals(true, WeekRanges.appliesTo("2-4周", 3))
        assertEquals(false, WeekRanges.appliesTo("2-4周", 5))
        assertEquals(true, WeekRanges.appliesTo("3-5,8-20周", 12))
        assertEquals(false, WeekRanges.appliesTo("3-5,8-20周", 6))
    }

    /**
     * ★ **认不出来时返回 null（不过滤）** —— 这一组是本文件最要紧的断言。
     *
     * 返回 `false` 会让这门课从课表上消失，而学生不会知道少了什么。
     * 返回 `null` 顶多多列一门课。
     */
    @Test
    fun `认不出来时必须返回 null_而不是 false`() {
        assertNull("空串 = 没有周次信息", WeekRanges.appliesTo("", 3))
        assertNull("纯文字", WeekRanges.appliesTo("全周", 3))
        assertNull("日期被当成周次（越界丢弃后为空）", WeekRanges.appliesTo("20260914", 3))
        assertNull("只有单双标记、没有范围", WeekRanges.appliesTo("单周", 3))
        assertNull("周次越界（第 0 周 / 第 99 周）", WeekRanges.appliesTo("1-16周", 99))
    }

    /** 自相矛盾的数据（单周标记 + 全是偶数周）→ 按"说不清"处理，不把课藏掉 */
    @Test
    fun `单双标记把范围筛空时返回 null`() {
        // 第 2 周不可能在单周、第 3 周不可能在双周 —— 这种串解析出来会是空集，
        // 与其据此把这门课整个藏掉，不如承认"这份数据说不清"
        assertNull(WeekRanges.appliesTo("2周(单)", 2))
        assertNull(WeekRanges.appliesTo("3周(双)", 3))
    }

    @Test
    fun `单双周标记_防御性支持`() {
        // 单周：1-6 里的奇数周
        assertEquals(true, WeekRanges.appliesTo("1-6周(单)", 3))
        assertEquals(false, WeekRanges.appliesTo("1-6周(单)", 4))
        // 双周：1-6 里的偶数周
        assertEquals(true, WeekRanges.appliesTo("1-6周(双)", 4))
        assertEquals(false, WeekRanges.appliesTo("1-6周(双)", 3))
        // 全角括号 / 不带括号两种写法都认
        assertEquals(false, WeekRanges.appliesTo("1-6周（单）", 2))
        assertEquals(true, WeekRanges.appliesTo("1-6单周", 5))
        // ★ 自相矛盾（第 3 周不是双周）→ 说不清，不过滤
        assertNull(WeekRanges.appliesTo("3周(双)", 3))
    }

    // ══════════════════ 归一化 / 最大周次（从 GdutScheduleParser 搬来的行为）══════════════════

    @Test
    fun `归一化压成连续区间`() {
        assertEquals("7-9周", WeekRanges.normalize("9,7,8"))
        assertEquals("6周", WeekRanges.normalize("6"))
        assertEquals("1-3,7-8周", WeekRanges.normalize("7,8,3,1,2,3"))
        assertEquals("4-19周", WeekRanges.normalize("4-19周"))
        assertEquals("1-4,7周", WeekRanges.normalize("1至4,7"))
    }

    @Test
    fun `归一化认不出时原样返回`() {
        assertEquals("空串进空串出", "", WeekRanges.normalize(""))
        assertEquals("纯文字原样返回", "全周", WeekRanges.normalize("全周"))
        assertEquals("日期原样返回（不把学号/日期当周次）", "20260914", WeekRanges.normalize("20260914"))
    }

    @Test
    fun `最大周次`() {
        assertEquals(19, WeekRanges.maxWeekOf(listOf("16,7,8,9,10,11,17,18,19,12,13,14,15", "6")))
        assertEquals("一条课都没有 → 0（调用方据此退回默认 22 周）", 0, WeekRanges.maxWeekOf(emptyList()))
        assertEquals(0, WeekRanges.maxWeekOf(listOf("", "全周")))
    }

    // ══════════════════ Course.appliesInWeek ══════════════════

    private fun course(weeks: String) = Course(
        id = 1L,
        name = "高等数学",
        teacher = "王老师",
        room = "A-101",
        weekday = 1,
        startPeriod = 1,
        periodCount = 2,
        weeks = weeks,
    )

    @Test
    fun `手动添加的课没有周次_每一周都上`() {
        assertTrue(course("").appliesInWeek(1))
        assertTrue(course("").appliesInWeek(20))
    }

    @Test
    fun `有周次串时按周次判断`() {
        assertTrue(course("2-4周").appliesInWeek(3))
        assertFalse(course("2-4周").appliesInWeek(5))
    }

    /** 不知道这是第几周（没有教学周数据）→ 不过滤，照旧显示 */
    @Test
    fun `不知道第几周时不过滤`() {
        assertTrue(course("2-4周").appliesInWeek(null))
    }

    /** 认不出的周次串 → 不过滤（见 `认不出来时必须返回 null` 那条的理由） */
    @Test
    fun `周次串认不出时不过滤`() {
        assertTrue(course("全周").appliesInWeek(5))
    }
}
