package com.tof.manycourse

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tof.manycourse.ui.nameLineCount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「课名画几行」的算法（`ui/CalendarGridLayout.kt` 的 [nameLineCount]）。
 *
 * 为什么值得单测：周视图的卡片只有 45dp 上下一格，课名是这张卡**唯一**的信息
 * （时间由位置表示、地点在底部小字里）。行数算错的表现是"课名被截成两三个字"
 * 或者"最后一行被裁掉半截"—— 都不会崩，只会让人认不出这门课，
 * 而这正是用户报上来的那个 bug（"课程卡片的字显示不全"）。
 *
 * 数的是**整行**：`23.9dp` 的框在 12dp 行高下只算 1 行 —— 把半行也算进去的话，
 * 最后一行会被卡片边缘裁掉半截，比省略号更难看。
 */
class GridCardNameLinesTest {

    @Test
    fun areaFitsTwoLines() {
        assertEquals(2, nameLineCount(areaHeight = 24.dp, lineHeight = 12.dp))
    }

    @Test
    fun partialLineIsNotCounted() {
        // 1.99 行 = 只画 1 行（第 2 行会被裁掉半截）
        assertEquals(1, nameLineCount(areaHeight = 23.9.dp, lineHeight = 12.dp))
    }

    @Test
    fun tallAreaIsCapped() {
        // 再高也不画成一整块竖排：上限是 NameLineMax(4)
        assertEquals(4, nameLineCount(areaHeight = 200.dp, lineHeight = 12.dp))
    }

    @Test
    fun shortAreaStillDrawsOneLine() {
        // 框再矮也得画一行，否则课名会整块消失（比省略号更莫名其妙）
        assertEquals(1, nameLineCount(areaHeight = 8.dp, lineHeight = 12.dp))
        assertEquals(1, nameLineCount(areaHeight = 0.dp, lineHeight = 12.dp))
    }

    @Test
    fun unboundedAreaUsesTheCap() {
        // 理论上不会发生（调用方给 weight(1f)，高度有界），但不能让除零/无穷算出脏值
        assertEquals(4, nameLineCount(areaHeight = Dp.Infinity, lineHeight = 12.dp))
    }

    @Test
    fun nonsenseLineHeightDoesNotCrashOrReturnZero() {
        assertEquals(4, nameLineCount(areaHeight = 30.dp, lineHeight = 0.dp))
    }

    @Test
    fun realDenseGridNumbers() {
        // 真机实测（1080p / 393dp 宽、font_scale 1.17、8 行的密课表）：
        // 每行 46.5dp，课名框约 30dp，矮格子的行高 9.5sp → 11.1dp
        assertEquals(2, nameLineCount(areaHeight = 30.dp, lineHeight = 11.1.dp))
    }
}
