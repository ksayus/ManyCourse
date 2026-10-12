package com.tof.manycourse

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.ui.GridCardTier
import com.tof.manycourse.ui.GridNameStep
import com.tof.manycourse.ui.fitGridCardName
import com.tof.manycourse.ui.gridCardStyle
import com.tof.manycourse.ui.gridNameSteps
import com.tof.manycourse.ui.nameLineCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「课名怎么才能**完整显示**」的算法（`ui/CalendarGridLayout.kt` 的 [fitGridCardName]）。
 *
 * ## 为什么值得单测
 *
 * 用户报的原话是「课程的名字应该要能显示全，可以换行显示」。这件事落在两处：
 *  - `maxLines` 由"该字号的行框高"与课名区实高现算（[nameLineCount]）；
 *  - **字号自动缩小**：卡片只有 45dp 宽，基准字号一行放不下 4 个汉字，
 *    名字长了必须缩到"换行后真的放得下"的那一档。
 *
 * 算错的表现**都不会崩**：要么课上名字被省略号截成三四个字（用户认不出是哪门课），
 * 要么字号大了半档、最后一行被卡片下沿切掉半截。真机上要一门一门课地看才发现。
 *
 * ## 本文件里的"字宽模型"是什么、为什么可信
 *
 * 运行时 [fitGridCardName] 的行数由 `TextMeasurer` **实测**（同一个名字、同一个宽度、
 * 同一个字重，与真正画出来的排版逐字一致）。JVM 单测里没有字体，所以这里用一个
 * **字宽模型**替代它，模型只假设两件与真实 CJK 排版一致的事：
 *
 *  1. 一个汉字 / 全角标点的宽度 = `1em` = 字号 × `fontScale`；ASCII 按 `0.55em` 估
 *     （比实际略宽 —— 宁可多算一行）；
 *  2. 换行是**贪心**的：一行塞到塞不下为止（Compose 对 CJK 就是这么断的）。
 *
 * 于是本文件钉住的是**选择逻辑**（哪一档、几行、放不放得下这三者的一致性），
 * 而"实测和模型对得上"由设备侧的插桩测试兜底（`CalendarGridNameWrapTest`）。
 */
class GridCardNameFitTest {

    /** 1080p / 393dp 宽、fontScale 1.17 的真机度量（与 GridCardTierTest 同一台机器）*/
    private val fontScale = 1.17f

    /** 真机上格子里的课名框：7 列平分 393dp、扣掉轴宽与卡片留白后约 41dp 宽 */
    private val nameWidth = 41.dp

    /** 宽松档（[GridCardTier.Full]）的字号梯子：10sp → 6.5sp */
    private val fullSteps = gridNameSteps(gridCardStyle(GridCardTier.Full))

    // ══════════════════ ① 用户那个例子：11 个字的课名在密课表里也放得下 ══════════════════

    /**
     * ★ 这条就是用户报的那个 bug 的护栏。
     *
     * 8 行的密课表里一行 46dp 上下（`gridRowHeight` 算出来的），课名框约 30dp 高、41dp 宽。
     * `大学英语I（综合基础）` 11 个字：
     *  - 基准字号 10sp：一行只放得下 3 个字 → 要 4 行，而 30dp 只画得下 2 行 → **截断**（旧行为）；
     *  - 自动降档到 7sp：一行 5 个字 → 3 行，30dp 画得下 3 行 → **完整显示**。
     */
    @Test
    fun elevenCharacterCourseNameFitsTheDenseGrid() {
        val fit = fitWithModel(name = "大学英语I（综合基础）", areaHeight = 30.dp, steps = fullSteps)
        assertTrue("11 个字的课名必须能完整放下（否则就是用户报的「显示不全」）", fit.fits)
        assertTrue("放得下就必须真的会换行（一行放不下 11 个字）", fit.lines >= 2)
        assertTrue("字号应当是从基准降下来的（基准 10sp 放不下）", fit.fontSize.value < 10f)
        assertTrue("降档也不能降到最小可读字号之下", fit.fontSize.value >= 6.5f)
        assertNoOverflow(fit, areaHeight = 30.dp)
    }

    /** 一行放得下的短名字不该被降档；放不下的（4 个字在 41dp 里就是两行）也不该被截 */
    @Test
    fun shortCourseNameKeepsTheBaseFont() {
        val twoChars = fitWithModel(name = "体育", areaHeight = 30.dp, steps = fullSteps)
        assertEquals("两个字的课名用基准字号", 10f, twoChars.fontSize.value, 0.001f)
        assertEquals("两个字在 41dp 里就是一行", 1, twoChars.lines)

        val fourChars = fitWithModel(name = "军事理论", areaHeight = 30.dp, steps = fullSteps)
        assertEquals("4 个字在基准字号下也能完整放下（两行）", 10f, fourChars.fontSize.value, 0.001f)
        assertEquals("41dp 宽在 10sp 下一行放 3 个汉字，4 个字就是两行", 2, fourChars.lines)
        assertTrue(fourChars.fits)
    }

    /** 名字越长，字号只可能**一样或更小**（这条保证"自动缩小"是单调的，不会忽大忽小）*/
    @Test
    fun longerNameNeverGetsABiggerFont() {
        val names = listOf("体育", "大学英语", "数字媒体导论", "中国近现代史纲要", "毛泽东思想和中国特色社会主义理论体系概论")
        val sizes = names.map { fitWithModel(it, areaHeight = 30.dp, steps = fullSteps).fontSize.value }
        sizes.zipWithNext().forEach { (shorter, longer) ->
            assertTrue("更长的名字不该用更大的字号（$sizes）", longer <= shorter)
        }
    }

    // ══════════════════ ② 不变量：任何组合都不会"字被切一半" ══════════════════

    /**
     * ★★ **绝不溢出**：`maxLines × 行框高 ≤ 课名区高` 恒成立。
     *
     * 这条是"宁可字小、也不出现半截字"的全部依据 —— 行框高由 [fitGridCardName] 按
     * `fontScale` 换算，行数由 [nameLineCount] 向下取整现算，所以把整个"高度 × 名字长度"
     * 的矩阵扫一遍都不该有例外（包括放不下、走省略号的那些）。
     *
     * 唯一的例外写在 [assertNoOverflow] 里：课名区比**最小可读字号的一行**还矮
     * （< 8.6dp，实际布局里被 [GridCardTier] 的 `MinNameArea` 挡在外面）时，
     * 那一行没有地方可放 —— 这是物理上做不到，不是算错。
     */
    @Test
    fun everyCombinationFitsVerticallyOrEllipsisesInsideTheBox() {
        val areas = listOf(0.dp, 8.dp, 14.dp, 20.dp, 30.dp, 46.dp, 70.dp, 88.dp, 200.dp)
        val names = listOf("体育", "大学英语", "数字媒体导论", "中国近现代史纲要", "毛泽东思想和中国特色社会主义理论体系概论")
        val ladders = GridCardTier.entries.map { gridNameSteps(gridCardStyle(it)) }
        areas.forEach { area ->
            names.forEach { name ->
                ladders.forEach { steps ->
                    val fit = fitWithModel(name, area, steps)
                    assertTrue("行数至少 1（否则课名整块消失）", fit.maxLines >= 1)
                    assertTrue("实际行数不该超过能画的行数", fit.lines <= fit.maxLines)
                    assertNoOverflow(fit, areaHeight = area, where = "「$name」在 $area 高的框里")
                }
            }
        }
    }

    /** 框再矮、名字再长，也必须给出一档字号（不能崩、不能返回 0）*/
    @Test
    fun degenerateInputsStillReturnAUsableDecision() {
        listOf(0.dp, 1.dp, 5.dp).forEach { area ->
            val fit = fitWithModel("毛泽东思想和中国特色社会主义理论体系概论", area, fullSteps)
            assertTrue("必须给出一档字号：${fit.fontSize}", fit.fontSize.value >= 6.5f)
            assertTrue("必须至少画一行", fit.maxLines >= 1)
        }
        // 空梯子（调用方给了个空列表）也要退回最小可读字号，而不是崩或返回 0
        val empty = fitWithModel("大学英语", 30.dp, emptyList())
        assertEquals(6.5f, empty.fontSize.value, 0.001f)
    }

    // ══════════════════ ③ 兜底：极端名字落到最小可读字号 + 省略号 ══════════════════

    /**
     * 20 个字 + 只有 20dp 高的框：连最小可读字号也放不下 → `fits = false`，
     * 但字号**停在最小可读字号**（不是继续缩到看不见），而且行数仍然不溢出。
     *
     * ★ 这是本轮唯一一处"允许显示不全"的地方，取舍写在这里：卡片是 45dp 宽的格子，
     * 20 个字的课名要 4 行 × 5 字，框里只有 2 行的高度 —— 除非把字缩到 5sp 上下，
     * 而那已经认不出来了。宁可如实给一个省略号，也不给一块看不清的字。
     */
    @Test
    fun absurdlyLongNameStopsAtTheReadableFloor() {
        val fit = fitWithModel(
            name = "毛泽东思想和中国特色社会主义理论体系概论",
            areaHeight = 20.dp,
            steps = fullSteps,
        )
        assertEquals("兜底必须停在最小可读字号", 6.5f, fit.fontSize.value, 0.001f)
        assertTrue("这种情况确实放不下", !fit.fits)
        assertEquals("省略号出现在最后一行，行数仍然算在框内", fit.maxLines, fit.lines)
        assertNoOverflow(fit, areaHeight = 20.dp)
    }

    /** 高格子里同一个长名字**能**完整放下（说明上面那条不是"一律放不下"）*/
    @Test
    fun theSameLongNameFitsWhenTheBoxIsTall() {
        val name = "毛泽东思想和中国特色社会主义理论体系概论"
        val fit = fitWithModel(name, areaHeight = 70.dp, steps = fullSteps)
        assertTrue("70dp 高的课名框应该放得下 20 个字：$fit", fit.fits)
        assertTrue("这么长的名字必然多行", fit.lines >= 3)
    }

    // ══════════════════ ④ 字号梯子本身 ══════════════════

    @Test
    fun ladderIsDescendingAndEndsAtTheReadableFloor() {
        GridCardTier.entries.forEach { tier ->
            val steps = gridNameSteps(gridCardStyle(tier))
            val style = gridCardStyle(tier)
            assertEquals("梯子第一档就是这一档的基准字号", style.nameFont.value, steps.first().font.value, 0.001f)
            assertEquals("梯子最后一档是最小可读字号", 6.5f, steps.last().font.value, 0.001f)
            assertTrue(
                "梯子必须由大到小（从大到小试才能「能大就大」）",
                steps.zipWithNext().all { (big, small) -> small.font.value < big.font.value },
            )
            // 行框高跟着字号按比例走：写死行框的话，缩字号就换不来"多画一行"
            steps.forEach { step ->
                assertEquals(
                    "行框高必须与字号成同一比例（${tier}）",
                    style.nameLine.value / style.nameFont.value,
                    step.lineHeight.value / step.font.value,
                    1e-4f,
                )
            }
        }
    }

    /** 步进 0.5sp：梯子越细，"刚好放得下"的字号就越贴边（但也不必细过 0.5sp）*/
    @Test
    fun ladderStepIsHalfASp() {
        val steps = fullSteps
        steps.zipWithNext().forEach { (a, b) ->
            assertEquals("步进必须是 0.5sp", 0.5f, a.font.value - b.font.value, 1e-4f)
        }
        assertEquals("10sp → 6.5sp 共 8 档", 8, steps.size)
    }

    // ══════════════════ 辅助 ══════════════════

    /** 用"字宽模型"跑一遍 [fitGridCardName]（运行时这里换的是 `TextMeasurer` 实测）*/
    private fun fitWithModel(
        name: String,
        areaHeight: Dp,
        steps: List<GridNameStep>,
    ) = fitGridCardName(
        name = name,
        nameAreaHeight = areaHeight,
        steps = steps,
        fontScale = fontScale,
    ) { text, step -> modelledLineCount(text, step) }

    /**
     * 「行长 × 行数 ≤ 框高」这条不变式。
     *
     * 例外只有一个、而且是物理性的：框比**一行**还矮时，那一行没有地方可放。
     * 实际布局里它被 [GridCardTier] 的 `MinNameArea`（14dp）挡在外面 —— 最小可读字号的
     * 一行在本机字体度量下只有 8.6dp 上下，永远塞得进 14dp。真出现时溢出也是**上下对称**
     * 的一点点，不会出现"字被切一半"。
     */
    private fun assertNoOverflow(
        fit: com.tof.manycourse.ui.GridNameFit,
        areaHeight: Dp,
        where: String = "课名框",
    ) {
        val lineHeightDp = fit.lineHeight.value * fontScale
        val total = fit.maxLines * lineHeightDp
        assertTrue(
            "$where：${fit.maxLines} 行 × ${"%.2f".format(lineHeightDp)}dp = " +
                "${"%.2f".format(total)}dp 超过了框高 $areaHeight —— 最后一行会被切掉半截",
            total <= areaHeight.value + 1e-3f || lineHeightDp > areaHeight.value,
        )
    }

    /**
     * 字宽模型（见类注释）：整行贪心塞字，塞不下就换行。
     *
     * ASCII（英文、数字、半角括号）按 0.55em 估 —— 刻意估宽一点：宁可多算一行，
     * 也不能算出"放得下"而真机上放不下。
     */
    private fun modelledLineCount(name: String, step: GridNameStep): Int {
        val em = step.font.value * fontScale
        val width = nameWidth.value
        if (em <= 0f || width <= 0f) return 1
        var lines = 1
        var used = 0f
        name.forEach { ch ->
            val advance = em * if (ch.code < 0x80) 0.55f else 1f
            if (used > 0f && used + advance > width) {
                lines += 1
                used = 0f
            }
            used += advance
        }
        return lines
    }

    /** 模型本身也要有下限感：整行放不下就换行，而不是永远返回 1 行 */
    @Test
    fun modelActuallyWraps() {
        assertEquals(1, modelledLineCount("体育", fullSteps.first()))
        assertEquals("3 个汉字占满一行，第 4 个换行", 2, modelledLineCount("大学英语", fullSteps.first()))
        assertEquals(4, modelledLineCount("大学英语I（综合基础）", fullSteps.first()))
        assertTrue(
            "字号小一行就该放得更多字",
            modelledLineCount("大学英语I（综合基础）", fullSteps.last()) <
                modelledLineCount("大学英语I（综合基础）", fullSteps.first()),
        )
    }
}
