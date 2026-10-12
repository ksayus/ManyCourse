package com.tof.manycourse

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.tof.manycourse.ui.GridCardDimTint
import com.tof.manycourse.ui.GridCardTier
import com.tof.manycourse.ui.contrastRatio
import com.tof.manycourse.ui.courseCardDimFill
import com.tof.manycourse.ui.courseCardFill
import com.tof.manycourse.ui.courseCardRoomBand
import com.tof.manycourse.ui.dimFillAlpha
import com.tof.manycourse.ui.gridCardStyle
import com.tof.manycourse.ui.gridCardTextColor
import com.tof.manycourse.ui.over
import com.tof.manycourse.ui.relativeLuminance
import com.tof.manycourse.ui.theme.CourseDotColors
import com.tof.manycourse.ui.theme.DarkColorScheme
import com.tof.manycourse.ui.theme.LightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周视图卡片的**可读性护栏**（`ui/CalendarGridLayout.kt` 的"卡片面貌"那一节）。
 *
 * ## 为什么值得单测
 *
 * 卡片是**浅色系填充色块 + 深色字**（课程色混一半白 / 浅灰），于是"深字压在这个底色上
 * 还看不看得清"变成一条需要算的约束 —— 而它错的后果**不会崩**：只是某几门课的课名糊在
 * 底色里，或者换周动画的中段课名整个消失。真机上要一门一门课地看才发现。
 *
 * ## 门槛用的是 WCAG 2.x，不是拍的
 *
 * - [MinTextContrast] = 4.5：WCAG AA 对**正文小字**的门槛。卡上的课名是 6.5~10sp SemiBold、
 *   地点 7~8sp —— 都够不到 WCAG 的「大字」（14pt 加粗 / 18pt），所以一律按小字算；
 * - [MinDistinctRatio] / [MinDistinctLuminanceGap]：亮卡与灰卡「看得出不一样」的下限
 *   （图例写的是「亮 = 本周会上 · 灰 = 本周不上」，两张卡长得一样这个图例就是假的）。
 *
 * 换周渐变**逐帧**用的是同一个 4.5（没有单独放宽）：本设计的字色是**常数**，
 * 而两个端点都是浅色，所以整条路径的对比度只升不降，最低点就是较暗的那一端（实测 7.0）。
 *
 * ## 实测值（五个课程色取最差的一个）
 *
 * | | 亮卡底 | 地点带 | 灰卡底（浅色主题） | 灰卡底（深色主题） | 字色 |
 * |---|---|---|---|---|---|
 * | 最差对比度 | 9.0 | 7.0 | 13.9 | 8.2 | 都是 `#0F172A` |
 */
class GridCardContrastTest {

    private companion object {
        /** WCAG AA 正文小字的门槛 */
        const val MinTextContrast = 4.5

        /** WCAG 2.x 允许放宽到 3.0 的「大字」下限：14pt 加粗 */
        const val WcagLargeBoldPt = 14f

        /** 亮卡与灰卡至少要有这么明显的差别（对比度那一支）*/
        const val MinDistinctRatio = 1.20

        /** 或者至少在亮度上差这么多（对比度对小色块不敏感，所以留一支亮度判据）*/
        const val MinDistinctLuminanceGap = 0.05

        /** 页面底色的两个极端：浅色主题纯白、深色主题 `#0F172A`（`ColorScheme.background`）*/
        val LightPage = Color(0xFFFFFFFF)
        val DarkPage = Color(0xFF0F172A)

        /** 课程色令牌一共 5 个（`CourseDotColors`）：每一门课都要过 */
        val accents get() = CourseDotColors

        /** 两个主题各自真实使用的那套色板角色（卡片字色从它们里面取）*/
        fun scheme(dark: Boolean) = if (dark) DarkColorScheme else LightColorScheme

        /** 某个主题下真实绘制出来的卡片字色 */
        fun cardText(dark: Boolean) = gridCardTextColor(scheme(dark))

        /** 某个主题的页面底色 */
        fun page(dark: Boolean) = if (dark) DarkPage else LightPage
    }

    // ══════════════════ ① 自身校验：两支亮度公式必须一致 ══════════════════

    /**
     * 自己那份 [relativeLuminance] 对**不透明**色必须与 Compose 的 `luminance()` 完全一致。
     *
     * 少了这条，公式抄错会让下面所有断言"全绿但全假"。它专门盯着本文件最容易被写错的地方：
     * 灰卡底是**合成**出来的（不透明，但由叠色算出），亮卡底是**插值**出来的。
     */
    @Test
    fun luminanceAgreesWithComposeForOpaqueColours() {
        val samples = listOf(
            Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF0F172A), Color(0xFFE2E8F0),
        ) + accents + accents.map(::courseCardFill) +
            accents.map(::courseCardRoomBand) +
            listOf(courseCardDimFill(dark = false), courseCardDimFill(dark = true))
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

    // ══════════════════ ② 亮卡：浅色底 + 深色字 ══════════════════

    @Test
    fun courseCardTextIsReadableOnEveryCourseColour() {
        listOf(false, true).forEach { dark ->
            accents.forEach { accent ->
                val body = courseCardFill(accent)
                val band = courseCardRoomBand(accent)
                val text = cardText(dark)
                assertReadable("${theme(dark)}主题亮卡底（课程色 $accent）", body, text)
                assertReadable("${theme(dark)}主题地点带（课程色 $accent）", band, text)
            }
        }
    }

    /**
     * ★ **亮卡底必须是课程色的"浅色调"** —— 这一条钉的就是"浅色系"这个要求本身。
     *
     * 课程色令牌是给圆点/色条用的 500 档高饱和色：直接当色块用时它是一块**中等偏深**的
     * 颜色（用户否掉的那一版还要再压深一档去配白字）。哪天有人把 [courseCardFill]
     * 改成"原样返回课程色"或者"压深"，上面那条也许还绿（深字在中深底上照样达标），
     * 但卡片就不再是浅色系了 —— 这条会红，并说明为什么。
     */
    @Test
    fun litCardFillIsALightTintOfTheCourseColour() {
        accents.forEach { accent ->
            val fill = relativeLuminance(courseCardFill(accent))
            val raw = relativeLuminance(accent)
            assertTrue(
                "课程色 $accent 的亮卡底必须**明显比原色浅**（原色 $raw → 亮卡底 $fill）：" +
                    "卡片是「浅色系」，不是「把课程色原样当色块用」",
                fill - raw >= 0.10,
            )
        }
    }

    // ══════════════════ ③ 灰卡：也是浅色，而且深字读得清 ══════════════════

    @Test
    fun dimCardTextIsReadableInBothThemes() {
        listOf(false, true).forEach { dark ->
            assertReadable("${theme(dark)}主题灰卡底", courseCardDimFill(dark), cardText(dark))
        }
    }

    /**
     * ★ **灰卡也是浅色**（两个主题都是）—— 这是"字色可以是常数"的全部依据。
     *
     * 上一版为了让**白字**达标，把灰卡做成了中等偏深的中性色（浅色主题下 `#6A6F7B`）。
     * 现在字色是深色，灰卡就必须留在浅色这一侧：一旦它压到中深，
     * 深字与灰卡底的对比度就掉到 4.5 以下（下面那条会红），
     * 而且"亮↔灰"的插值中段会出现"两支字色都刚够"的缝。
     */
    @Test
    fun dimCardFillStaysOnTheLightSideInBothThemes() {
        listOf(false, true).forEach { dark ->
            val fill = courseCardDimFill(dark)
            assertTrue(
                "${theme(dark)}主题的灰卡底 $fill 亮度只有 ${"%.3f".format(relativeLuminance(fill))} —— " +
                    "它必须留在浅色这一侧，深字才是一支常数（见 GridCardContrastTest 的类注释）",
                relativeLuminance(fill) >= 0.35,
            )
        }
    }

    /**
     * 灰卡底是**近黑按不同 alpha 叠在纯白上**算出来的（不是叠在页面底上）——
     * 卡片是不透明色块，它的灰是一个**绝对值**。
     *
     * 这条断言把"叠在白上"这件事钉死：真的按页面底去叠的话，深色主题会得到一块近黑，
     * 那时深字（`#0F172A`）压在它上面等于没字 —— 正是这条口径挡住的那种退化。
     */
    @Test
    fun dimCardFillIsCompositedOnWhiteNotOnThePage() {
        listOf(false, true).forEach { dark ->
            assertEquals(
                "${theme(dark)}主题的灰卡底必须由「近黑 × alpha 叠在纯白上」算出",
                over(GridCardDimTint, dimFillAlpha(dark), Color.White),
                courseCardDimFill(dark),
            )
        }
    }

    /**
     * 灰卡与亮卡必须**看得出不一样**（否则"亮 = 本周会上 / 灰 = 本周不上"这个图例就失效了）。
     *
     * 亮卡在浅色一侧（课程色的浅色调），灰卡也在浅色一侧，所以判据留两支：
     * 对比度 ≥ [MinDistinctRatio] **或**亮度差 ≥ [MinDistinctLuminanceGap]
     * （浅色之间对比度天生不敏感，实测最亮的琥珀与灰卡是 1.147 / 0.105 —— 走的是第二支）。
     */
    @Test
    fun dimCardIsVisiblyDifferentFromLitCards() {
        listOf(false, true).forEach { dark ->
            val dim = courseCardDimFill(dark)
            accents.forEach { accent ->
                val fill = courseCardFill(accent)
                val ratio = contrastRatio(dim, fill)
                val lumGap = kotlin.math.abs(relativeLuminance(dim) - relativeLuminance(fill))
                assertTrue(
                    "${theme(dark)}主题 课程色 $accent：灰卡与亮卡的对比度只有 " +
                        "${"%.2f".format(ratio)}、亮度差只有 ${"%.3f".format(lumGap)} —— " +
                        "两张卡看起来会像同一张",
                    ratio >= MinDistinctRatio || lumGap >= MinDistinctLuminanceGap,
                )
            }
        }
    }

    /**
     * 灰卡**不再描边**：它的边界完全由底色承担（浅色主题比卡面深一档、深色主题比页面亮得多），
     * 所以"看得出来这里排过课"这件事必须能被量出来 —— 拿它跟**页面底**比。
     *
     * 上一版深色主题的灰卡是深色，必须靠一圈白描边才划得出边界；现在两张卡都是浅的，
     * 再描一圈深线只会让"这一周不上"看着比实体课还重（见"卡片面貌"那一节）。
     */
    @Test
    fun dimCardIsVisibleAgainstItsOwnPageWithoutAnyOutline() {
        listOf(false, true).forEach { dark ->
            val dim = courseCardDimFill(dark)
            val ratio = contrastRatio(dim, page(dark))
            assertTrue(
                "${theme(dark)}主题：灰卡底 $dim 对页面底 ${page(dark)} 的对比度只有 " +
                    "${"%.2f".format(ratio)}，而且它已经不描边了 —— 那样就是一块看不见的卡",
                ratio >= MinDistinctRatio,
            )
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

    // ══════════════════ ④ 字色是色板角色，而且是常数 ══════════════════

    /**
     * ★★ **字色必须是一支与主题、与面貌都无关的常数**。
     *
     * 这是"换周插值每一帧都读得清"的**全部依据**：底色在插值，字色不在。
     * 浅色主题取 `onSurface`（`#0F172A`），深色主题的 `onSurface` 是白的 ——
     * 白字压在浅卡上等于没字，所以那里退到同一套色板的 `inverseOnSurface`。
     * 两者在本项目的取值**恰好是同一个深色**，这条断言把它钉住：
     * 哪天有人改了色板里这两个角色（例如把深色主题的 `inverseOnSurface` 调浅），
     * 这里会立刻红，而不是等到真机上换周时课名糊掉才发现。
     */
    @Test
    fun gridCardTextColourIsTheSameDeepColourInBothThemes() {
        assertEquals(
            "浅色主题的卡片字色应当就是 onSurface 角色",
            LightColorScheme.onSurface,
            gridCardTextColor(LightColorScheme),
        )
        assertEquals(
            "深色主题的卡片字色应当取 inverseOnSurface（那里 onSurface 是白的）",
            DarkColorScheme.inverseOnSurface,
            gridCardTextColor(DarkColorScheme),
        )
        assertEquals(
            "两个主题的卡片字色必须是同一支 —— 否则「字色是常数」就不成立，换周渐变会撞车",
            gridCardTextColor(LightColorScheme),
            gridCardTextColor(DarkColorScheme),
        )
    }

    /**
     * 深色主题**不能**用 `onSurface` 当卡片字色 —— 这条量出"为什么必须换角色"。
     *
     * 深色主题的 `onSurface` 是纯白；卡片底是浅色（亮卡底 `#9DC0FA` 一档）。白字压上去
     * 最差的只有 1.1 上下，那不是"可读性取舍"，是看不见。
     */
    @Test
    fun theDarkThemeOnSurfaceWouldBeInvisibleOnALightCard() {
        val white = DarkColorScheme.onSurface
        accents.forEach { accent ->
            val ratio = contrastRatio(courseCardFill(accent), white)
            assertTrue(
                "深色主题的 onSurface（$white）压在亮卡底上只有 ${"%.2f".format(ratio)} —— " +
                    "正因为它不达标，卡片字色才要退到 inverseOnSurface",
                ratio < MinTextContrast,
            )
        }
        assertTrue(
            "深色主题的 onSurface 本来就该是浅色（白），否则这条测试的前提不成立",
            relativeLuminance(white) > 0.5,
        )
    }

    // ══════════════════ ⑤ 换周渐变：逐帧可读 ══════════════════

    /**
     * ★★ **换周渐变逐帧可读** —— 这一条是本轮最贵的一个结论的护栏。
     *
     * 灰卡与亮卡一浅一彩，如果让**字色跟着底色一起插值**，中间必然有一帧两者亮度相等，
     * 那一帧对比度**恒等于 1.0**（上一版实测那一版五门课在渐变中段都掉到 1.5，
     * 表现是"换周时课名先糊掉、再重新出现"）。本设计的字色是常数（见上一条），
     * 两个端点又都在浅色一侧 —— 于是整条路径的最低点就是较暗的那一端（实测 7.0）。
     * 这条测试按 5% 步长把 0→1 走一遍来验它。
     */
    @Test
    fun weekChangeKeepsTextReadableAtEveryFrame() {
        val steps = 20
        var worst = Double.MAX_VALUE
        var worstWhere = ""
        listOf(false, true).forEach { dark ->
            val dim = courseCardDimFill(dark)
            val text = cardText(dark)
            accents.forEach { accent ->
                val body = courseCardFill(accent)
                val band = courseCardRoomBand(accent)
                for (step in 0..steps) {
                    val t = step.toFloat() / steps
                    listOf("卡底" to body, "地点带" to band).forEach { (what, target) ->
                        val ratio = contrastRatio(lerp(dim, target, t), text)
                        if (ratio < worst) {
                            worst = ratio
                            worstWhere = "${theme(dark)}主题 $what t=$t（课程色 $accent）"
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
     * 字色在两套色板角色里取的永远是**更暗**的那一支（这就是 [gridCardTextColor] 的规则）。
     *
     * 用真实的两套 scheme 各自断言一次：卡片底恒为浅色，深字才有对比度。
     */
    @Test
    fun cardTextAlwaysPicksTheDarkerRole() {
        listOf(LightColorScheme, DarkColorScheme).forEach { scheme ->
            val picked = gridCardTextColor(scheme)
            assertTrue(
                "卡片字色 ${picked} 不是 onSurface / inverseOnSurface 里更暗的那一支" +
                    "（onSurface=${scheme.onSurface}, inverseOnSurface=${scheme.inverseOnSurface}）",
                relativeLuminance(picked) <= relativeLuminance(scheme.onSurface) &&
                    relativeLuminance(picked) <= relativeLuminance(scheme.inverseOnSurface),
            )
        }
    }

    /**
     * 为什么门槛是 4.5 而不是「大字」的 3.0：卡上最大的课名也只有 10sp SemiBold。
     * WCAG 只在「18pt 常规 / 14pt 加粗」以上才允许放宽到 3.0 —— 这条把两者的距离写下来，
     * 免得下一个人觉得「10sp 加粗也算大字」从而顺手把门槛放宽。
     */
    @Test
    fun nameTextIsSmallTextSoTheStrictThresholdApplies() {
        val biggest = GridCardTier.entries.maxOf { gridCardStyle(it).nameFont.value }
        assertTrue(
            "课名最大字号 ${biggest}sp 仍远低于 WCAG 的「大字」门槛 ${WcagLargeBoldPt}pt —— " +
                "所以卡片上所有文字都按小字算，门槛是 $MinTextContrast 而不是 3.0",
            biggest < WcagLargeBoldPt,
        )
    }

    // ══════════════════ 辅助 ══════════════════

    private fun theme(dark: Boolean) = if (dark) "深色" else "浅色"

    private fun assertReadable(where: String, background: Color, text: Color) {
        val ratio = contrastRatio(background, text)
        assertTrue(
            "$where：字色 $text 压在底色 $background 上的对比度 ${"%.2f".format(ratio)} " +
                "< $MinTextContrast（WCAG AA 正文小字）",
            ratio >= MinTextContrast,
        )
    }
}
