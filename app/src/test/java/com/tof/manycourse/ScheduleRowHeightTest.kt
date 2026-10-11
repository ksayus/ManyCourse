package com.tof.manycourse

import androidx.compose.ui.unit.dp
import com.tof.manycourse.ui.dayRowHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **课表页"一行课程"的高度**（`ui/ScheduleScreen.kt` 的 [dayRowHeight]）。
 *
 * 这条算式是"一天几门课都不用滚"的全部依据，所以把每种情形都钉一遍：
 * 平分、扣间距、封顶、兜底。真机上要复现"小屏 + 一天十门课"很难，
 * 在这里是一行断言。
 */
class ScheduleRowHeightTest {

    /** 与真实取值同量级的一组上下限（真值随系统字号变，见 CourseCardHeightTest）*/
    private val min = 62.dp
    private val max = 126.dp
    private val gap = 8.dp

    private fun assertDp(expected: Float, actual: androidx.compose.ui.unit.Dp) =
        assertEquals(expected, actual.value, 0.001f)

    @Test
    fun 三门课_扣掉间距之后平分() {
        // 250 - 8*2 = 234，234 / 3 = 78
        assertDp(78f, dayRowHeight(availableHeight = 250.dp, rowCount = 3, gap = gap, minRowHeight = min, maxRowHeight = max))
    }

    @Test
    fun 两门课比三门课每行更高() {
        // 250 - 8 = 242，242 / 2 = 121
        assertDp(121f, dayRowHeight(availableHeight = 250.dp, rowCount = 2, gap = gap, minRowHeight = min, maxRowHeight = max))
    }

    @Test
    fun 一天一门课_被上限封顶() {
        // 800 全给一门课也不会让它长成半屏 —— 那已经不是"一行课"了
        assertDp(126f, dayRowHeight(availableHeight = 800.dp, rowCount = 1, gap = gap, minRowHeight = min, maxRowHeight = max))
    }

    @Test
    fun 课多到平分不开时取下限_由调用方判定内部滚动() {
        // 300 - 8*5 = 260，260 / 6 = 43.3 → 压到下限 62
        val rowHeight = dayRowHeight(availableHeight = 300.dp, rowCount = 6, gap = gap, minRowHeight = min, maxRowHeight = max)
        assertDp(62f, rowHeight)
        // ★ 下限 × 行数 + 间距 > 可用高度 = "真的放不下"，这时才允许列表自己滚
        assertTrue(
            "取下限时应当已经放不下（调用方靠这个判断要不要内部滚动）",
            rowHeight * 6 + gap * 5 > 300.dp,
        )
    }

    @Test
    fun 放得下时_行数与行高的乘积不超过可用高度() {
        val available = 400.dp
        val rows = 4
        val rowHeight = dayRowHeight(available, rows, gap, min, max)
        assertTrue(
            "算出来的行高必须保证整份列表不溢出（这是'一屏看完'的底线）",
            rowHeight * rows + gap * (rows - 1) <= available,
        )
    }

    @Test
    fun 一天没有课时返回下限_不会算出负间距() {
        assertDp(62f, dayRowHeight(availableHeight = 400.dp, rowCount = 0, gap = gap, minRowHeight = min, maxRowHeight = max))
    }

    @Test
    fun 高度无界时退回下限_不让它崩() {
        // 理论上不会发生（调用方 weight(1f) 保证有界），但真的无界时不能让除法算出 Inf
        assertDp(62f, dayRowHeight(androidx.compose.ui.unit.Dp.Infinity, 3, gap, min, max))
        assertDp(62f, dayRowHeight(androidx.compose.ui.unit.Dp.Unspecified, 3, gap, min, max))
        assertDp(62f, dayRowHeight(0.dp, 3, gap, min, max))
    }
}
