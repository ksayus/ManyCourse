package com.tof.manycourse

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.tof.manycourse.ui.GridCardTextOnFill
import com.tof.manycourse.ui.contrastRatio
import com.tof.manycourse.ui.courseCardDimFill
import com.tof.manycourse.ui.courseCardDimOutline
import com.tof.manycourse.ui.courseCardFill
import com.tof.manycourse.ui.courseCardRoomBand
import com.tof.manycourse.ui.dimFillAlpha
import com.tof.manycourse.ui.dimFillTint
import com.tof.manycourse.ui.over
import com.tof.manycourse.ui.relativeLuminance
import com.tof.manycourse.ui.theme.CourseDotColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周视图卡片的**可读性护栏**（`ui/CalendarGridLayout.kt` 的"卡片面貌"那一节）。
 *
 * ## 为什么值得单测
 *
 * 卡片改成了**填充色块 + 白字**（对齐参考图），于是"白字压在色块上还看不看得清"
 * 变成一条需要算的约束 —— 而它错的后果**不会崩**：只是某几门课的课名糊在底色里，
 * 或者换周动画的中段课名整个消失。真机上要一门一门课地看才发现。
 *
 * ## 门槛用的是 WCAG 2.x，不是拍的
 *
 * - [MinTextContrast] = 4.5：WCAG AA 对**正文小字**的门槛。卡上的课名是 10sp SemiBold、
 *   地点 8sp —— 都够不到 WCAG 的"大字"（18pt / 14pt 粗），所以按小字算；
 * - [MinGraphicContrast] = 3.0：WCAG AA 对图形/大字的分辨门槛，只用在**描边**上
 *   （它不承载文字，只负责划出卡片边界），**没有**用它给课名放水。
 *
 * 换周渐变**逐帧**用的是同一个 4.5（没有单独放宽）：本设计把灰卡也做成了深色，
 * 于是整条路径上白字的对比度单调变化，最低点就是灰卡那一端。
 */
class GridCardContrastTest {

    private companion object {
        /** WCAG AA 正文小字的门槛 */
        const val MinTextContrast = 4.5

        /** WCAG AA 图形/大字的分辨门槛（只用于描边） */
        const val MinGraphicContrast = 3.0

        /** 页面底色的两个极端：浅色主题纯白、深色主题 `#0F172A`（`ColorScheme.background`）*/
        val LightPage = Color(0xFFFFFFFF)
        val DarkPage = Color(0xFF0F172A)

        /** 课程色令牌一共 5 个（`CourseDotColors`）：每一门课都要过 */
        val accents get() = CourseDotColors
    }

    // ══════════════════ ① 自身校验：两支亮度公式必须一致 ══════════════════

    /**
     * 自己那份 [relativeLuminance] 对**不透明**色必须与 Compose 的 `luminance()` 完全一致。
     *
     * 少了这条，公式抄错会让下面所有断言"全绿但全假"。本轮确实在这上面走过两轮弯路：
     * 早先两版分别把"浅底深字 + 深底白字"当成同一支字色、以及漏算了灰卡底是半透明叠色，
     * 数字看着都合理，实际路径中段掉到 1.5 / 端点掉到 1.2（都被下面几条抓出来了）。
     */
    @Test
    fun luminanceAgreesWithComposeForOpaqueColours() {
        val samples = listOf(
            Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF0F172A), Color(0xFFE2E8F0),
        ) + accents + accents.map(::courseCardFill)
        samples.forEach { color ->
            assertEquals(
                "相对亮度公式与 Compose 的 luminance() 不一致：$color",
                color.luminance().toDouble(),
                relativeLuminance(color),
                1e-4,
            )
        }
    }

    /** [over] 按 alpha 混合；两端的极端必须落回预期，且与 `GlassTestColors.over` 同口径 */
    @Test
    fun overTreatsAlphaAsTheHighByte() {
        val base = Color(0xFFF5F9FF)
        assertEquals("alpha=0 应等于底色", base, over(Color(0x000F172A), 0f, base))
        assertEquals("alpha=1 应等于它自己", Color(0xFF0F172A), over(Color(0xFF0F172A), 1f, base))
        // 与 GlassPaletteContrastTest 同一组回归数（0x52/255 的近黑压在 #F5F9FF 上）
        val alpha = 0x52 / 255f
        val expectedRed = 0x0F / 255f * alpha + 0xF5 / 255f * (1f - alpha)
        assertEquals(expectedRed.toDouble(), over(Color(0x520F172A), alpha, base).red.toDouble(), 1e-3)
        assertEquals(
            "与 GlassTestColors.over 必须是同一个口径",
            GlassTestColors.over(Color(0x520F172A), base),
            over(Color(0x520F172A), alpha, base),
        )
    }

    // ══════════════════ ② 亮卡：课程色块上的课名/地点必须读得清 ══════════════════

    @Test
    fun courseCardTextIsReadableOnEveryCourseColour() {
        accents.forEach { accent ->
            val body = courseCardFill(accent)
            val band = courseCardRoomBand(accent)
            assertReadable("亮卡底（课程色 $accent）", body, GridCardTextOnFill)
            assertReadable("地点带（课程色 $accent）", band, GridCardTextOnFill)
        }
    }

    /**
     * ★ **原色**（不压深）必须被这条量出来 —— 它钉的是"为什么要压深"。
     *
     * 课程色令牌是给圆点/色条用的 500 档高饱和色：直接当色块用时**五个色一个都不达标**
     * （最差的琥珀配白字只有 2.15）。哪天有人把 [courseCardFill] 改成"原样返回课程色"，
     * 上面那条会红，而这条会告诉他**为什么**不能那样改。
     */
    @Test
    fun rawCourseColoursWouldNotBeReadableAsFills() {
        val unreadable = accents.filter { contrastRatio(it, GridCardTextOnFill) < MinTextContrast }
        assertEquals(
            "5 个课程色原色都不该达标 —— 全达标说明有人偷偷换了调色板，" +
                "那时 [courseCardFill] 的压深就没有存在理由了",
            accents.size,
            unreadable.size,
        )
    }

    // ══════════════════ ③ 灰卡：同样要达标（浅色主题下它是深色，见 KDoc 那条取舍）══════

    @Test
    fun dimCardTextIsReadableInBothThemes() {
        listOf(false, true).forEach { dark ->
            assertReadable(
                "${if (dark) "深色" else "浅色"}主题灰卡底",
                courseCardDimFill(dark),
                GridCardTextOnFill,
            )
        }
    }

    /**
     * 灰卡底是**半透明叠色**算出来的，所以它的实际颜色取决于**它自己那边**的页面底 ——
     * 而"哪一边"是主题决定的事实（浅色主题不会压在近黑页面上）。
     *
     * 这里按各主题**真实会出现的那几档底**去量：
     *  - 浅色主题：纯白页面、以及玻璃卡片内的浅色表面；
     *  - 深色主题：`ColorScheme.background` 近黑、以及玻璃里的深色表面。
     *
     * ★ 故意**不**写成"每个主题都过白+黑两个极端"：那等于要求同一层 62% 的近黑
     * 压在纯白与压在近黑上都达标 —— 前者得到中灰（白字 5.0），后者几乎纯黑（白字 19.6），
     * 但**反过来**（深色主题的 50% 近黑压在纯白上）会得到一块中灰，白字只有 3.4。
     * 而那一种组合在真实界面里不存在（深色主题的页面底永远不会是纯白）。
     */
    @Test
    fun dimCardTextStaysReadableOnRealisticPageBases() {
        val bases = mapOf(
            false to listOf(LightPage, Color(0xFFF5F9FF)),
            true to listOf(DarkPage, Color(0xFF131C2E)),
        )
        bases.forEach { (dark, pages) ->
            pages.forEach { page ->
                val fill = over(dimFillTint(dark), dimFillAlpha(dark), page)
                assertReadable("灰卡（dark=$dark）压在页面底 $page 上", fill, GridCardTextOnFill)
            }
        }
    }

    // ══════════════════ ④ 换周渐变：逐帧可读 ══════════════════

    /**
     * ★★ **换周渐变逐帧可读** —— 这一条是本轮最贵的一个结论的护栏。
     *
     * 灰卡与亮卡一深一浅，如果让**字色跟着底色一起插值**，中间必然有一帧两者亮度相等，
     * 那一帧对比度**恒等于 1.0**（实测那一版五门课在渐变中段都掉到 1.5，
     * 表现是"换周时课名先糊掉、再重新出现"）。本设计把字色做成常数（白）、
     * 并把灰卡也做成深色 —— 于是底色单调变化，对比度只升不降，
     * 整条路径的最低点就是灰卡那一端。这条测试按 5% 步长把 0→1 走一遍来验它。
     */
    @Test
    fun weekChangeKeepsTextReadableAtEveryFrame() {
        val steps = 20
        var worst = Double.MAX_VALUE
        var worstWhere = ""
        listOf(false, true).forEach { dark ->
            val theme = if (dark) "深色" else "浅色"
            val dim = courseCardDimFill(dark)
            accents.forEach { accent ->
                val body = courseCardFill(accent)
                val band = courseCardRoomBand(accent)
                for (step in 0..steps) {
                    val t = step.toFloat() / steps
                    listOf("卡底" to body, "地点带" to band).forEach { (what, target) ->
                        val ratio = contrastRatio(lerp(dim, target, t), GridCardTextOnFill)
                        if (ratio < worst) {
                            worst = ratio
                            worstWhere = "$theme 主题 $what t=$t（课程色 $accent）"
                        }
                    }
                }
            }
        }
        assertTrue(
            "换周渐变最差的一帧是 $worstWhere，只有 ${"%.2f".format(worst)}" +
                "（下限 $MinTextContrast）—— 字色一旦跟着底色插值，这里会掉到 1 附近",
            worst >= MinTextContrast,
        )
    }

    /**
     * ★ **白字是常数**：字色不能随换周动画变（这正是上面那条的前提）。
     *
     * 用反射之前的更直接的办法：把整条路径上"实际会被绘制的那支字色"取出来 ——
     * 现在它就是 [GridCardTextOnFill]，一个 `val`，没有任何输入能改变它。
     * 这条测试因此断言的是**它的取值本身**（白），以及它在对比度上确实是最优选择：
     * 对每一档底色，白字都比"近黑"那支更清楚（不然就该换字色，而不是硬用白字）。
     */
    @Test
    fun whiteIsTheRightTextColourForEveryFill() {
        assertEquals("卡片字色必须是纯白（唯一的取值口径）", Color.White, GridCardTextOnFill)
        val nearBlack = Color(0xFF0F172A)
        listOf(false, true).forEach { dark ->
            val fills = listOf(courseCardDimFill(dark)) + accents.map(::courseCardFill)
            fills.forEach { fill ->
                assertTrue(
                    "底色 $fill 上白字（${"%.2f".format(contrastRatio(fill, GridCardTextOnFill))}）" +
                        "并不比近黑字（${"%.2f".format(contrastRatio(fill, nearBlack))}）更清楚 —— " +
                        "那就该换字色，而不是硬用白字",
                    contrastRatio(fill, GridCardTextOnFill) >= contrastRatio(fill, nearBlack),
                )
            }
        }
    }

    /** 地点带**永远比卡底深**：地点那行字的对比度只会更好，两处判定不会打架 */
    @Test
    fun roomBandIsAlwaysDarkerThanTheCardBody() {
        accents.forEach { accent ->
            val body = relativeLuminance(courseCardFill(accent))
            val band = relativeLuminance(courseCardRoomBand(accent))
            assertTrue("课程色 $accent：地点带应该比卡底暗", band < body)
        }
    }

    /** 灰卡与亮卡必须**看得出不一样**（否则"亮/灰"这个图例就失效了）*/
    @Test
    fun dimCardIsVisiblyDifferentFromLitCards() {
        listOf(false, true).forEach { dark ->
            val dim = courseCardDimFill(dark)
            accents.forEach { accent ->
                val fill = courseCardFill(accent)
                val ratio = contrastRatio(dim, fill)
                val lumGap = kotlin.math.abs(relativeLuminance(dim) - relativeLuminance(fill))
                assertTrue(
                    "dark=$dark 课程色 $accent：灰卡与亮卡的对比度只有 ${"%.2f".format(ratio)}、" +
                        "亮度差只有 ${"%.3f".format(lumGap)} —— 两张卡看起来会像同一张",
                    ratio >= 1.20 || lumGap >= 0.05,
                )
            }
        }
    }

    /**
     * 灰卡的**描边**只负责划分边界，不承载文字 —— 所以按图形算（≥ 3.0）而不是按小字算。
     *
     * 浅色主题刻意不描边（灰卡底本身就够沉），那时它是全透明的，跳过。
     */
    @Test
    fun dimCardOutlineIsAVisibleBoundaryInDarkTheme() {
        val dim = courseCardDimFill(dark = true)
        val outline = courseCardDimOutline(dark = true)
        // 描边是半透明的：要按它**压在灰卡底上**之后的样子量
        val composited = over(outline, outline.alpha, dim)
        val ratio = contrastRatio(composited, dim)
        assertTrue(
            "深色主题的灰卡描边要看得见（对灰卡底的对比度 ${"%.2f".format(ratio)} < $MinGraphicContrast）",
            ratio >= MinGraphicContrast,
        )
        assertEquals("浅色主题靠底色本身划界，不额外描边", Color.Transparent, courseCardDimOutline(dark = false))
    }

    // ══════════════════ 辅助 ══════════════════

    private fun assertReadable(where: String, background: Color, text: Color) {
        val ratio = contrastRatio(background, text)
        assertTrue(
            "$where：字色压在底色上的对比度 ${"%.2f".format(ratio)} < $MinTextContrast（WCAG AA 正文小字）",
            ratio >= MinTextContrast,
        )
    }
}
