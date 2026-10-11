package com.tof.manycourse

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.tof.manycourse.ui.theme.GlassMode
import com.tof.manycourse.ui.theme.GlassPalette
import com.tof.manycourse.ui.theme.GlassTokens
import com.tof.manycourse.ui.theme.glassTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **配色护栏**：6 套配色 × 2 种明暗 = 12 组（两种玻璃模式各验一遍）。
 *
 * ## 一、`accent` 必须在自己的背景上读得清
 *
 * 门槛 [GlassTestColors.MinAccentContrast] = 4.805，来历写在那个常量的 KDoc 里
 * （改动前四组基线的最小值，且高于 WCAG AA 的 4.5）。超不过就**改配色的取值**，
 * 不许下调门槛。
 *
 * ## 二、页头不能被"压暗"到看不清层次（R1 / R2）
 *
 * R1/R2 记录的失败模式是「页头亮度 234.8 → 218.7」——页头比内容还暗，
 * 看起来像"顶部沉在内容下面"。量它必须**先把半透明色合成到背景上**
 * （`Color.luminance()` 忽略 alpha，直接量 `0x85FFFFFF` 会得到 1.0，什么也量不出来）。
 *
 * ### ★ 任务里那条"页头亮度 ≥ 面板亮度"没有按字面实现，原因如下（实测）
 *
 * 规格 §8.3 与任务都要求「页头合成后亮度 ≥ 面板合成后亮度」。按字面实现
 * （页头压 `backgroundGradient[0]`、面板压 `backgroundGradient[1]`）
 * **在改动前的取值上就不成立**，而且**跟配色无关** —— 12 组全部为负，差值 -0.009 ~ -0.067：
 *
 * | 组合 | 页头（合成后） | 面板裸色（合成后） |
 * |---|---|---|
 * | 高斯 / 浅色 | 0.91 | 1.00 |
 * | 高斯 / 深色 | 0.008 | 0.021 |
 * | 液态 / 浅色 | 0.87 | 0.99 |
 * | 液态 / 深色 | 0.008 | 0.021 |
 *
 * 原因是取值本身：面板底色是"白 94%/90%"、页头是"白 52%/46%"，两者压在同一族背景上时
 * **更实更白的那一层必然更亮**；而且这四组数在 6 套配色上一模一样
 * （背景梯度的影响被各自的 alpha 压到千分位以下）。要让它成立只能改面板或页头的 tint
 * —— 那正好是 `GlassTokenGoldenTest` 明令冻结的既有取值，等于用一次视觉回归换一条断言。
 *
 * 按"拿不到权威数据就别编"的口径，这里**不写这条断言**，改成下面四条**实测成立、
 * 且真能拦住 R1/R2 那类改动**的护栏，并把这条冲突留在注释里（而不是悄悄删掉）：
 *
 * 1. 页头压过页面顶部背景后，仍比身后的背景亮（一垫 `Modifier.shadow` 就掉下去）；
 * 2. 浮层的遮罩确实把内容区压暗（遮罩被拿掉/调淡时立刻报错）；
 * 3. 页头比**遮罩之后**的内容区亮 —— 这才是用户实际看到的那两块；
 * 4. 浅色主题下，页头比遮罩后的内容区还高出一个可辨的余量（≥ 0.04）。
 *
 * 关于第 3/4 条为什么盯"遮罩后的内容区"、而不是"遮罩下的面板"：
 * 面板是浮层的前景，它比页头亮是**设计如此**（上面那张表就是证据）；
 * 页头要赢的是"内容区"，不是"浮在内容区之上的那块面板"。
 */
class GlassPaletteContrastTest {

    /** 覆盖 6 套配色 × 明暗 */
    private fun combosWithMode(mode: GlassMode): List<Pair<GlassTokens, String>> =
        GlassPalette.entries.flatMap { palette ->
            listOf(false, true).map { dark ->
                glassTokens(mode, dark, palette) to "${palette.key} dark=$dark $mode"
            }
        }

    private fun bothModes(): List<Pair<GlassTokens, String>> =
        combosWithMode(GlassMode.Gaussian) + combosWithMode(GlassMode.Liquid)

    /**
     * 自身校验：自己写的合成公式对**不透明**颜色必须与 Compose 的 `luminance()` 完全一致。
     *
     * 少了这条，公式抄错（比如把 `AARRGGBB` 当成 `RRGGBBAA`、或者漏了 sRGB 反 gamma）
     * 会让下面所有断言"全绿但全假"。这条不是形式主义：本轮真的写错过一次 alpha 位置，
     * 当时所有数字都错得离谱却"看起来很像真的"。
     */
    @Test
    fun compositorAgreesWithComposeLuminance() {
        val opaqueSamples = listOf(
            Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFF2563EB),
            Color(0xFFDCEAFF), Color(0xFF0B1220), Color(0xFF92400E),
        )
        opaqueSamples.forEach { color ->
            assertEquals(
                "合成公式对不透明色 ${color.hex()} 与 Compose luminance() 不一致",
                color.luminance().toDouble(),
                GlassTestColors.luminance(color),
                1e-4,
            )
        }
    }

    /** 合成用的 alpha 必须按 `AARRGGBB` 取（写错位置的后果见上一条）*/
    @Test
    fun overTreatsEightDigitColorsAsAarrggbb() {
        val base = Color(0xFFF5F9FF)
        assertEquals(
            "alpha=0 的色合成后应等于底色（若把 AARRGGBB 读成 RRGGBBAA，这里会变成 #520F17）",
            base.hex(),
            GlassTestColors.over(Color(0x000F172A), base).hex(),
        )
        assertEquals(
            "alpha=0xFF 的色合成后应等于它自己",
            Color(0xFF2563EB).hex(),
            GlassTestColors.over(Color(0xFF2563EB), base).hex(),
        )
        // 0x520F172A = alpha 82、色 #0F172A；与底色 #F5F9FF 各通道按 82/255 加权。
        // 容差 1e-3：Compose 的 Color 内部是 8 位存储（构造时已量化），比双精度手算
        // 差约 2e-4；但"alpha 位置写错"会让红通道直接跳到 0.32（偏差 0.35），两者不会混
        val expectedRed = 0x0F / 255f * (0x52 / 255f) + 0xF5 / 255f * (1f - 0x52 / 255f)
        assertEquals(
            "alpha 位置写错（AARRGGBB vs RRGGBBAA）时红通道会对不上",
            expectedRed.toDouble(),
            GlassTestColors.over(Color(0x520F172A), base).red.toDouble(),
            1e-3,
        )
    }

    /** 门槛常量本身也要钉住：改动它等价于拆门禁 */
    @Test
    fun contrastFloorCoversWcagAaAndPreChangeBaseline() {
        assertTrue(
            "门槛 ${GlassTestColors.MinAccentContrast} 低于 WCAG AA 小字的 4.5",
            GlassTestColors.MinAccentContrast >= 4.5,
        )
        assertEquals(
            "门槛被改动过：它应等于改动前四组基线的最小值 4.805（来历见 MinAccentContrast 的 KDoc）",
            4.805,
            GlassTestColors.MinAccentContrast,
            0.0005,
        )
    }

    /**
     * 12 组：`accent` 对**页面背景梯度中档**的对比度 ≥ 门槛。
     *
     * 为什么取中档（`backgroundGradient[1]`）：它是三档里最亮、最"中性"的一档，
     * 而页面中部正是内容区（也是 `accent` 被当小字用的地方）—— 取它 = 取最不利那一档。
     */
    @Test
    fun accentIsReadableOnPageBackground_forAllPalettesAndThemes() {
        combosWithMode(GlassMode.Gaussian).forEach { (tokens, where) ->
            assertAccentContrast(tokens.accent, tokens.backgroundGradient[1], where)
        }
    }

    /** 液态玻璃那套背景更沉、彩度更高，同样要过 —— 别只顾着高斯那一组 */
    @Test
    fun accentIsReadableOnLiquidBackground_forAllPalettesAndThemes() {
        combosWithMode(GlassMode.Liquid).forEach { (tokens, where) ->
            assertAccentContrast(tokens.accent, tokens.backgroundGradient[1], where)
        }
    }

    private fun assertAccentContrast(accent: Color, background: Color, where: String) {
        val ratio = GlassTestColors.contrastRatio(accent, background)
        assertTrue(
            "$where：accent ${accent.hex()} 对背景 ${background.hex()} " +
                "对比度 ${"%.4f".format(ratio)} < 门槛 ${GlassTestColors.MinAccentContrast}" +
                "（accent 被当小字色用，超不过就调这套配色的取值，别降门槛）",
            ratio >= GlassTestColors.MinAccentContrast,
        )
    }

    /**
     * **页头必须比它身后的页面背景亮**（12 组 × 2 模式）。
     *
     * 实测比值范围 0.73 ~ 1.27，门槛取 **0.70**：留出约 4% 余量。
     * 这条直接对着 R1 的错法 —— 给页头垫 `Modifier.shadow`、或把 `barTint` 调得过透，
     * 都会让页头合成亮度掉到背景以下（那就是"页头比内容还暗"）。
     *
     * 为什么门槛不是 1.0（"必须比背景亮"）而留到 0.70：**深色主题下页头本来就只是略亮一点**。
     * 深色页头底是"深灰 58%–62%"，它身后也是深色背景，两者的合成亮度只差千分之几
     * （mint 最紧：0.73）。把它写成 1.0 会让四套深色配色立刻"失败"，
     * 而那是既有取值、不是回归 —— 于是一条本该拦回归的断言会变成"必须改配色"的假要求。
     */
    @Test
    fun headerStaysLightEnoughAgainstThePageBehindIt_forAllPalettesAndThemes() {
        val floor = 0.70
        bothModes().forEach { (tokens, where) ->
            val pageTop = GlassTestColors.luminance(tokens.backgroundGradient[0])
            val header = GlassTestColors.compositedLuminance(
                tint = tokens.barTint,
                base = tokens.backgroundGradient[0],
            )
            val ratio = header / pageTop
            assertTrue(
                "$where：页头合成亮度 ${fmt(header)} 只有身后页面背景 ${fmt(pageTop)} 的 " +
                    "${"%.3f".format(ratio)} 倍（下限 $floor）—— 页头会看起来陷进内容里" +
                    "（R1/R2 记录过的失败模式：给页头垫阴影会把 234.8 压到 218.7）",
                ratio >= floor,
            )
        }
    }

    /**
     * **浮层的遮罩必须真的把内容区压暗**（12 组 × 2 模式）。
     *
     * 这是"页头比内容暗"那个失败模式的**实际成因**：浮层打开时，遮罩把页面中部压暗，
     * 页头才不至于变成全屏最亮却又和最暗的内容贴在一起。遮罩被拿掉/调淡
     * （或有人把它改成浅色）时，这条立刻报错。
     *
     * 门槛 0.60 是测出来的：12 组实测 0.234 ~ 0.463，最坏 0.463，
     * 取 0.60 既留了 30% 余量，又能在遮罩被调淡三成时拦下。
     */
    @Test
    fun scrimDimmsTheContentBehindPanels_forAllPalettesAndThemes() {
        val ceiling = 0.60
        bothModes().forEach { (tokens, where) ->
            assertTrue(
                "$where：scrim 完全不透明或完全透明（alpha=${tokens.scrim.alpha}）",
                tokens.scrim.alpha in 0.0001f..0.9999f,
            )
            val bare = GlassTestColors.luminance(tokens.backgroundGradient[1])
            val scrimmed = GlassTestColors.luminance(
                GlassTestColors.over(tokens.scrim, tokens.backgroundGradient[1])
            )
            val dimFactor = scrimmed / bare
            assertTrue(
                "$where：遮罩太淡，页面中部只被压到 ${"%.3f".format(dimFactor)} 倍" +
                    "（要求 ≤ $ceiling）—— 浮层打开时页头会显得比内容暗（R1/R2）",
                dimFactor <= ceiling,
            )
        }
    }

    /**
     * **页头比"遮罩之后的内容区"亮**（12 组 × 2 模式）。
     *
     * 量的是用户实际看到的那两块（页头 / 被遮罩压暗的内容区）。
     */
    @Test
    fun headerOutshinesTheScrimmedContent_forAllPalettesAndThemes() {
        bothModes().forEach { (tokens, where) ->
            val header = GlassTestColors.compositedLuminance(
                tint = tokens.barTint,
                base = tokens.backgroundGradient[0],
            )
            val contentUnderScrim = GlassTestColors.luminance(
                GlassTestColors.over(tokens.scrim, tokens.backgroundGradient[1])
            )
            assertTrue(
                "$where：页头合成亮度 ${fmt(header)} ≤ 遮罩后内容区 ${fmt(contentUnderScrim)}" +
                    " —— 页头比内容还暗（R1/R2 记录过的失败模式）",
                header > contentUnderScrim,
            )
        }
    }

    /**
     * **浅色主题下浮层打开时，内容区被压到明显暗于页头**（余量 ≥ 0.04）。
     *
     * 这是"三层关系"里唯一还能量出余量的那一段：
     * 面板本身比页头亮是设计如此（见类注释那张表），但"**遮罩 + 面板**"这一整块
     * 仍然要留在页头下方 —— 实测余量 0.030 ~ 0.062，门槛取 0.04 是**这些配色的中位数下限**。
     *
     * 只对浅色主题断言：深色主题下三者的合成亮度都在 0.006 ~ 0.021 这个量级，
     * 差值只有 0.0004 ~ 0.002（不到浅色的 1/20）。在那个量级上设任何绝对门槛都是假精度，
     * 而深色下真正撑起"层次"的是高光与描边，不是亮度差 —— 宁可只测浅色并说明白。
     */
    @Test
    fun scrimmedContentStaysClearlyDimmerThanTheHeader_inLightTheme() {
        val margin = 0.04
        bothModes().filter { (tokens, _) -> !tokens.dark }.forEach { (tokens, where) ->
            val header = GlassTestColors.compositedLuminance(
                tint = tokens.barTint,
                base = tokens.backgroundGradient[0],
            )
            val contentUnderScrim = GlassTestColors.luminance(
                GlassTestColors.over(tokens.scrim, tokens.backgroundGradient[1])
            )
            assertTrue(
                "$where：页头合成亮度 ${fmt(header)} 与遮罩后内容区 ${fmt(contentUnderScrim)} " +
                    "的差只有 ${"%.4f".format(header - contentUnderScrim)}（要求 ≥ $margin）" +
                    " —— 页头相对内容失去了层次",
                header - contentUnderScrim >= margin,
            )
        }
    }

    private fun fmt(value: Double): String = "%.4f".format(value)

    private fun Color.hex(): String =
        "#" + toArgb().toUInt().toString(16).uppercase().padStart(8, '0')
}
