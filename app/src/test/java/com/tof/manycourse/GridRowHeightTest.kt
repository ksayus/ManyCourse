package com.tof.manycourse

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tof.manycourse.ui.axisLabelsFor
import com.tof.manycourse.ui.gridRowHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周视图**行高**的算法与"骨架降级"的阈值（`ui/CalendarGridLayout.kt`）。
 *
 * ## 为什么这两条值得单测
 *
 * 1. **总高恒不超出可用高度** —— 这是"整周一眼看完、手指往下滑屏幕不动"的**全部依据**。
 *    早先行高有下限（36dp），节次多的学校（广工 12 行）或矮屏上 `行数 × 下限 > 可用高度`，
 *    于是 GridBody 挂了一层内部滚动兜底：用户一滑，连星期表头都被推走，
 *    "第几行 = 第几节"这个坐标系就散了。这类回归**不会崩**，只在真机上滑一下才发现，
 *    所以用一条不变式把它钉死；
 * 2. **时间轴减行的阈值** —— 行高变矮时必须**自动减行**（先去掉起止时刻、只留节次），
 *    而不是让三行小字溢出到相邻行：相邻行是**别的节次**，溢出等于把刻度本身画错。
 */
class GridRowHeightTest {

    /** 覆盖"行数很多 / 屏幕很矮 / 宽高比极端"这些真机与脏数据的组合 */
    private val rowCounts = listOf(1, 3, 5, 8, 12, 16, 30)

    private val heights = listOf(1.dp, 40.dp, 120.dp, 300.dp, 480.dp, 688.dp, 2000.dp)

    // ══════════════════ ① 总高恒不超出可用高度 ══════════════════

    @Test
    fun 总高恒不超出可用高度() {
        rowCounts.forEach { rows ->
            heights.forEach { available ->
                val rowHeight = gridRowHeight(availableHeight = available, rowCount = rows)
                val total = rowHeight * rows
                assertTrue(
                    "行数 $rows、可用 $available 时总高 $total 超出了可用高度（手指往下滑屏幕就会动）",
                    total <= available,
                )
            }
        }
    }

    @Test
    fun 没有下限_行数多时行高就是除出来的值() {
        // ★ 这一条钉的就是"下限被删掉"：12 行（广工）/ 688dp 曾经被 36dp 的下限顶到 432dp，
        //   现在就是 688 ÷ 12 ≈ 57dp —— 除完全是"一屏看完"的来源
        assertTrue(gridRowHeight(688.dp, 12).value < 36.dp.value * 2)
        assertDp(240f / 16f, gridRowHeight(availableHeight = 240.dp, rowCount = 16))
        // 极端：小屏 + 超大字号也可能算出十几 dp 的一行，照样不设下限
        assertTrue(gridRowHeight(240.dp, 16).value < 36f)
    }

    @Test
    fun 上限留着_课少时不把几个块拉满整屏() {
        // 整个学期只排到第 3 节 → 3 行 × 88dp 封顶，不铺满 688dp（那是空壳不是"更大"）
        assertDp(88f, gridRowHeight(availableHeight = 688.dp, rowCount = 3))
        // 刚好到上限
        assertDp(88f, gridRowHeight(availableHeight = 264.dp, rowCount = 3))
        // 差一点就还是除出来的值
        assertTrue(gridRowHeight(availableHeight = 260.dp, rowCount = 3).value < 88f)
    }

    @Test
    fun 上限必须同时受可用高度约束() {
        // ★ 这条钉的是一个真实踩到的坑：上限**不能**写成 `min(MaxRowHeight, 除法结果)` ——
        //   那样"很少几行 + 很高的屏"会算出 `88dp × 3 行 = 264dp > 240dp`，
        //   总高一旦超出可用高度，内部滚动就又回来了。
        //   现在的上限是"可用高度允许的分法"与"令牌上限"里更小的那个
        heights.forEach { available ->
            rowCounts.forEach { rows ->
                val total = gridRowHeight(available, rows) * rows
                assertTrue(
                    "可用 $available、行数 $rows：总高 $total 越过了可用高度" +
                        "（上限必须先受可用高度约束）",
                    total <= available,
                )
            }
        }
        // 具体值：240dp 分 3 行 → 每行 80dp（而不是被上限顶成 88dp）
        assertDp(80f, gridRowHeight(availableHeight = 240.dp, rowCount = 3))
    }

    @Test
    fun 每行按半dp向下取整() {
        // 取整只会让总高更小 —— "总高 ≤ 可用高度"这条不变式因此不依赖取整方式。
        // 688 ÷ 12 = 57.33… → 57.0（不是 57.5，向上取整会把 12 行重新顶出屏幕）
        assertDp(57f, gridRowHeight(availableHeight = 688.dp, rowCount = 12))
        // 正中间的值照常保留
        assertDp(43f, gridRowHeight(availableHeight = 86.dp, rowCount = 2))
    }

    @Test
    fun 脏行数与无界高度都不算出负值或无穷() {
        // rowCount = 0 / 负数按 1 行处理：除零会算出 Infinity，喂给 height 就是崩溃
        assertDp(88f, gridRowHeight(availableHeight = 500.dp, rowCount = 0))
        assertDp(88f, gridRowHeight(availableHeight = 500.dp, rowCount = -3))
        // 真实无界（理论上不会发生：调用方给 weight(1f)）：退回上限，不滚动
        assertDp(88f, gridRowHeight(availableHeight = Dp.Infinity, rowCount = 12))
        // 高度为 0（首帧 / 被父约束压扁）：给 0 而不是负值
        assertDp(0f, gridRowHeight(availableHeight = 0.dp, rowCount = 12))
    }

    // ══════════════════ ② 时间轴减行的阈值 ══════════════════

    @Test
    fun 三行放得下就画起止时刻() {
        // 9sp 字、行高 11sp：1.17 倍字体下约 12.9dp 一行
        val line = 12.9.dp
        val labels = axisLabelsFor(rowHeight = 44.dp, lineHeight = line)
        assertEquals(3, labels.lineCount)
        assertTrue("行高够时必须画起止时刻", labels.withStartTime)
    }

    @Test
    fun 三行放不下就只留节次() {
        // 3 × 12.9 + 2 × 2 = 42.7dp：42dp 已经塞不下三行
        val line = 12.9.dp
        val notEnough = axisLabelsFor(rowHeight = 42.dp, lineHeight = line)
        assertEquals("放不下就该减到一行", 1, notEnough.lineCount)
        assertTrue("减行时**整行去掉**起止时刻", !notEnough.withStartTime)

        // 广工 12 行在 688dp 上是 57.3dp 一行 —— 三行放得下，时刻照画
        assertEquals(3, axisLabelsFor(rowHeight = 57.3.dp, lineHeight = line).lineCount)
        // 小屏 + 16 行：15dp 一行连两行都放不下，必须减到只留节次
        assertEquals(1, axisLabelsFor(rowHeight = 15.dp, lineHeight = line).lineCount)
    }

    @Test
    fun 阈值正好落在三行加行间距上() {
        // 边界：恰好等于门槛 → 画；少 0.1dp → 减行（相邻的高度必须落在不同档）
        val line = 11.7.dp
        val threshold = line * 3 + 2.dp * 2
        assertTrue(axisLabelsFor(threshold, line).withStartTime)
        assertTrue(!axisLabelsFor(threshold - 0.1.dp, line).withStartTime)
    }

    @Test
    fun 系统字号变大时减行门槛跟着抬高() {
        // ★ 阈值不能写死 dp：同一行高在 1.3 倍字体下已经塞不下三行（去掉时刻），
        //   在 1 倍字体下还够 —— 写死的话，大字号机器上时间轴会溢出到相邻节次
        val normal = 12.9.dp
        val big = 12.9.dp * 1.3f
        val rowHeight = 44.dp
        assertTrue("1 倍字体下画得起三行", axisLabelsFor(rowHeight, normal).withStartTime)
        assertTrue("1.3 倍字体下同一行高已放不下三行", !axisLabelsFor(rowHeight, big).withStartTime)
    }

    // ══════════════════ ③ 与卡片档位的口径一致 ══════════════════

    @Test
    fun 每一档行高都让时间轴落在确定的一档上() {
        // 时间轴减行是**纯函数**：同一个行高必得同一个结果（不存在"上一行三行字、下一行一行字"）
        val line = 12.9.dp
        heights.forEach { h ->
            val a = axisLabelsFor(h, line)
            val b = axisLabelsFor(h, line)
            assertEquals(a, b)
        }
        // 而且"时间画几行"永远只有 1 或 3（没有两行的中间态）
        heights.forEach { h ->
            val count = axisLabelsFor(h, line).lineCount
            assertTrue("实际的档位：$count", count == 1 || count == 3)
        }
    }

    private fun assertDp(expected: Float, actual: Dp) = assertEquals(expected, actual.value, 0.001f)
}
