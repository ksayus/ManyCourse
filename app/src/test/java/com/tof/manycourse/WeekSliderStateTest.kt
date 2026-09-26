package com.tof.manycourse

import com.tof.manycourse.ui.weekSliderState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 周次滑块的「位置 / 区间」口径（`ui/CalendarGridLayout.kt` 的 [weekSliderState]）。
 *
 * 为什么值得测：滑块现在**没有教学周时也要占位**（高度恒定 —— 它一出现就会吃掉近 48dp，
 * 网格行高是"剩多少高 ÷ 行数"算的，课表整块会跟着缩，见 `WeekSliderRow` 的注释）。
 * 于是"没有周次"这条以前根本不存在的路径，也要给 Slider 一份**合法**参数：
 * `valueRange = 0f..0f` 是空区间，算比例会得到 NaN —— 既不崩也不报错，
 * 只会让滑块画不出来或者跳一下，正是最该被钉住的那一类。
 */
class WeekSliderStateTest {

    @Test
    fun noWeeksStillGetsALegalRange() {
        // 0 周（还没拉到 / 本校不支持按周查）：退回 0..1、停在 0 —— 区间不是空的
        assertEquals(0f to 1, weekSliderState(weekCount = 0, currentIndex = null))
    }

    @Test
    fun aSingleWeekAlsoGetsALegalRange() {
        // 1 周：一格刻度拖不动，但同样不能让区间为空
        assertEquals(0f to 1, weekSliderState(weekCount = 1, currentIndex = 0))
    }

    @Test
    fun lastIndexIsOneLessThanTheWeekCount() {
        assertEquals(0f to 17, weekSliderState(weekCount = 18, currentIndex = 0))
    }

    @Test
    fun currentIndexIsKeptWhenItIsInRange() {
        assertEquals(3f to 17, weekSliderState(weekCount = 18, currentIndex = 3))
    }

    @Test
    fun outOfRangeIndexIsClamped() {
        // 换学校 / 重新同步之后 index 可能落在新的周次范围之外：夹住，
        // 别把一个越界值喂给 Slider（它的 value 必须落在 valueRange 里）
        assertEquals(17f to 17, weekSliderState(weekCount = 18, currentIndex = 99))
        assertEquals(0f to 17, weekSliderState(weekCount = 18, currentIndex = -5))
    }

    @Test
    fun nullIndexFallsBackToTheFirstWeek() {
        assertEquals(0f to 5, weekSliderState(weekCount = 6, currentIndex = null))
    }
}
