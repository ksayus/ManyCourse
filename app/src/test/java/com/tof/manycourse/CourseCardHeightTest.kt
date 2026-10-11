package com.tof.manycourse

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tof.manycourse.ui.components.CourseCardTier
import com.tof.manycourse.ui.components.courseCardCompactHeight
import com.tof.manycourse.ui.components.courseCardRegularHeight
import com.tof.manycourse.ui.components.courseCardRowBounds
import com.tof.manycourse.ui.components.courseCardTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **课程卡片按行高分档**（`ui/components/CampusComponents.kt`）。
 *
 * 三档的门槛决定了"行变矮时先撤哪一项"，而它们又是按 sp→dp 实测推出来的
 * （阈值写死 dp 的话，系统字号调大之后字就会被卡片下沿裁掉半截）。
 * 这里把两件事分别钉住：
 *
 *  1. 分界点本身（边界值相邻的两个高度必须落在不同档）；
 *  2. **阈值跟着字体度量走** —— 同一组字号在 1.3 倍字体下算出更高的门槛。
 */
class CourseCardHeightTest {

    /** fontScale = 1 时的实测字行高（与卡片内部的 `sp` 取值对应）*/
    private val nameLine = 22.dp
    private val clockLine = 20.dp
    private val metaLine = 18.dp

    // ══════════════════ 各档需要多高 ══════════════════

    @Test
    fun 紧凑档的高度_课名一行加节次一行() {
        // 上下内边距 10*2 + 课名 22 + 间隙 2 + 节次 18 = 62
        assertDp(62f, courseCardCompactHeight(nameLine, metaLine))
    }

    @Test
    fun 常规档的高度_课名一行加时间地点行加节次行() {
        // 16*2 + 22 + 4 + 20 + 8 + 18 = 104
        assertDp(104f, courseCardRegularHeight(nameLine, clockLine, metaLine))
    }

    // ══════════════════ 三档的分界 ══════════════════

    @Test
    fun 放得下两行课名才允许折行() {
        // 常规档 + 一整行课名 = 126：正好够 = Roomy
        assertEquals(
            CourseCardTier.Roomy,
            courseCardTier(126.dp, nameLine, clockLine, metaLine),
        )
        // 差一点点就只画一行 —— 第二行会被卡片下沿裁掉半截，那正是要避免的
        assertEquals(
            CourseCardTier.Regular,
            courseCardTier(125.9.dp, nameLine, clockLine, metaLine),
        )
    }

    @Test
    fun 放得下时间地点行就是常规档() {
        assertEquals(
            CourseCardTier.Regular,
            courseCardTier(104.dp, nameLine, clockLine, metaLine),
        )
        assertEquals(
            CourseCardTier.Compact,
            courseCardTier(103.9.dp, nameLine, clockLine, metaLine),
        )
    }

    @Test
    fun 再矮也是紧凑档_课名与节次还在() {
        assertEquals(
            CourseCardTier.Compact,
            courseCardTier(62.dp, nameLine, clockLine, metaLine),
        )
        assertEquals(
            CourseCardTier.Compact,
            courseCardTier(0.dp, nameLine, clockLine, metaLine),
        )
    }

    // ══════════════════ 阈值必须跟着系统字号走 ══════════════════

    @Test
    fun 字体调大后门槛跟着抬高() {
        // fontScale = 1.3：同一批 sp 换算出来的行高全部变大
        val bigName = nameLine * 1.3f
        val bigClock = clockLine * 1.3f
        val bigMeta = metaLine * 1.3f
        val regularAtNormalFont = courseCardRegularHeight(nameLine, clockLine, metaLine)
        val regularAtBigFont = courseCardRegularHeight(bigName, bigClock, bigMeta)

        assertTrue("字号大了，常规档需要的高度必须跟着变大", regularAtBigFont > regularAtNormalFont)
        // ★ 这就是"阈值不能写死 dp"的实证：104dp 在 1 倍字体下够常规排版，
        //   在 1.3 倍字体下已经不够了（那时必须退成紧凑档，否则字被裁）
        assertEquals(
            CourseCardTier.Regular,
            courseCardTier(regularAtNormalFont, nameLine, clockLine, metaLine),
        )
        assertEquals(
            CourseCardTier.Compact,
            courseCardTier(regularAtNormalFont, bigName, bigClock, bigMeta),
        )
    }

    // ══════════════════ 课表页要的行高区间 ══════════════════

    @Test
    fun 行高区间_下限是紧凑档上限是两行课名的自然高度() {
        val bounds = courseCardRowBounds(Density(density = 2.75f, fontScale = 1f))
        assertDp(62f, bounds.min)
        assertDp(126f, bounds.max)
    }

    @Test
    fun 行高区间随系统字号一起变大() {
        val normal = courseCardRowBounds(Density(density = 2.75f, fontScale = 1f))
        val big = courseCardRowBounds(Density(density = 2.75f, fontScale = 1.3f))
        assertTrue("1.3 倍字体下，卡片读得出来的最小高度必须更高", big.min > normal.min)
        assertTrue("1.3 倍字体下，两行课名的自然高度也必须更高", big.max > normal.max)
        // 每档都需要更多高度，但档位关系不变：上限仍然高于下限
        assertTrue(big.max > big.min)
    }

    private fun assertDp(expected: Float, actual: androidx.compose.ui.unit.Dp) =
        assertEquals(expected, actual.value, 0.001f)
}
