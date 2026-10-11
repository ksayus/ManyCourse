package com.tof.manycourse

import androidx.compose.ui.graphics.luminance
import com.tof.manycourse.ui.theme.GlassLevel
import com.tof.manycourse.ui.theme.GlassMode
import com.tof.manycourse.ui.theme.GlassPalette
import com.tof.manycourse.ui.theme.HighlightAngleVertical
import com.tof.manycourse.ui.theme.glassTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃设计令牌的纯逻辑校验（JVM，无需设备）：
 * 保证两种模式确实产出不同的视觉参数，且各层的不透明度/半径落在可读区间。
 *
 * ## 覆盖范围：**6 套配色 × 2 种模式 × 2 种明暗 = 24 组**
 *
 * 原先只跑"模式 × 明暗"4 组。配色一进来，同一组断言必须对 6 套配色**全部**成立 ——
 * 否则会出现"校园蓝过得去、落日把输入框调得和面板同色"这种只在换配色时才暴露的退化。
 *
 * 金值（逐字段与改动前比对）不在这里，见 `GlassTokenGoldenTest`；
 * 对比度护栏见 `GlassPaletteContrastTest`。三者分工：
 * 这里是**区间**（"不能过透/不能越界"），那边是**定值**。
 */
class GlassTokensTest {

    /** 全部组合：模式 × 明暗 × 配色 */
    private fun allTokens(): List<Triple<GlassMode, Boolean, GlassPalette>> =
        GlassMode.entries.flatMap { mode ->
            listOf(false, true).flatMap { dark ->
                GlassPalette.entries.map { palette -> Triple(mode, dark, palette) }
            }
        }

    private fun where(mode: GlassMode, dark: Boolean, palette: GlassPalette) =
        "$mode dark=$dark ${palette.key}"

    @Test
    fun gaussian_isDefaultMode() {
        assertEquals(GlassMode.Gaussian, GlassMode.Default)
        assertEquals(GlassMode.Gaussian, GlassMode.fromKey(null))
        assertEquals(GlassMode.Liquid, GlassMode.fromKey("liquid"))
        assertEquals(GlassMode.Gaussian, GlassMode.fromKey("unknown-key"))
    }

    @Test
    fun campusBlue_isDefaultPalette() {
        assertEquals(GlassPalette.CampusBlue, GlassPalette.Default)
        assertEquals(GlassPalette.CampusBlue, GlassPalette.fromKey(null))
        // 老用户的 prefs 里没有这个键 / 键值来自更新的版本 → 一律回落默认，不抛异常
        assertEquals(GlassPalette.CampusBlue, GlassPalette.fromKey("0bviously-not-a-palette"))
        assertEquals(GlassPalette.Mint, GlassPalette.fromKey("mint"))
    }

    @Test
    fun paletteKeysAreUniqueAndCoverAllSix() {
        assertEquals(6, GlassPalette.entries.size)
        assertEquals("校园蓝", GlassPalette.CampusBlue.label)
        val keys = GlassPalette.entries.map { it.key }
        assertEquals("配色的 key 必须唯一（它要落盘，重了会读出另一套配色）", keys.size, keys.toSet().size)
    }

    /** 默认参数必须等于显式传默认配色 —— 这是"既有调用点零改动"的前提 */
    @Test
    fun defaultPaletteArgument_matchesExplicitDefault() {
        GlassMode.entries.forEach { mode ->
            listOf(false, true).forEach { dark ->
                assertEquals(
                    "$mode dark=$dark 省略 palette 参数与显式传 Default 结果不一致",
                    glassTokens(mode, dark, GlassPalette.Default),
                    glassTokens(mode, dark),
                )
            }
        }
    }

    @Test
    fun twoModes_differVisibly() {
        val gaussian = glassTokens(GlassMode.Gaussian, dark = false)
        val liquid = glassTokens(GlassMode.Liquid, dark = false)

        // 液态玻璃：更强高光 + 边缘光 + 内阴影（高斯模糊这两项为完全透明）
        assertTrue("液态玻璃应有顶边反光", liquid.edgeLight.alpha > gaussian.edgeLight.alpha)
        assertTrue("液态玻璃应有内阴影", liquid.innerShadow.alpha > 0f)
        assertEquals("高斯模糊不应有边缘光", 0f, gaussian.edgeLight.alpha, 0.0001f)
        assertEquals("高斯模糊不应有内阴影", 0f, gaussian.innerShadow.alpha, 0.0001f)

        // 高光强度：液态玻璃更亮
        assertTrue(liquid.highlight.first().alpha > gaussian.highlight.first().alpha)

        // 描边与阴影层次：液态玻璃更明显
        assertTrue(liquid.border.alpha > gaussian.border.alpha)
        assertTrue(liquid.panelShadow.value > gaussian.panelShadow.value)

        // 色感：高斯模糊低饱和、液态玻璃增强
        assertTrue(liquid.saturation > 1f)
        assertTrue(gaussian.saturation < 1f)

        // 背景渐变不同（模式可感知）—— 6 套配色都要成立
        GlassPalette.entries.forEach { palette ->
            listOf(false, true).forEach { dark ->
                assertNotEquals(
                    "${palette.key} dark=$dark 两种模式的背景渐变不该相同",
                    glassTokens(GlassMode.Gaussian, dark, palette).backgroundGradient,
                    glassTokens(GlassMode.Liquid, dark, palette).backgroundGradient,
                )
            }
        }
    }

    @Test
    fun panelOpacity_staysReadable_inAllModesThemesAndPalettes() {
        allTokens().forEach { (mode, dark, palette) ->
            val tokens = glassTokens(mode, dark, palette)
            val at = where(mode, dark, palette)
            // 面板/卡片不透明度落在可读区间（面板 90%–94%，卡片 70%–86%）
            assertTrue(
                "$at 面板过透：${tokens.panelTint.alpha}",
                tokens.panelTint.alpha in 0.88f..0.96f,
            )
            assertTrue(
                "$at 卡片过透：${tokens.cardTint.alpha}",
                tokens.cardTint.alpha in 0.70f..0.86f,
            )
            // 弹窗/表单面板必须比列表卡片更实：遮罩透上来不能把面板洗灰
            assertTrue(
                "$at 面板应比卡片更不透明：panel=${tokens.panelTint.alpha} card=${tokens.cardTint.alpha}",
                tokens.panelTint.alpha > tokens.cardTint.alpha,
            )
            // 输入框底色必须是不透明的独立色（不能和面板同色，否则字段消失在面板里）
            assertEquals(
                "$at 输入框底色应完全不透明",
                1f,
                tokens.fieldTint.alpha,
                0.001f,
            )
            // 输入框必须比面板底色暗一档（浅色=浅灰蓝/深色=更深的槽），否则字段消失在面板里
            assertTrue(
                "$at 输入框应比面板底色暗一档：field=${tokens.fieldTint.luminance()} panel=${tokens.panelTint.luminance()}",
                tokens.fieldTint.luminance() < tokens.panelTint.luminance(),
            )
            // 模糊半径控制在 8–16dp，避免过度模糊（弹窗面板用静态表面，不做实时模糊）
            assertTrue(
                "$at 页头模糊越界：${tokens.barBlur}",
                tokens.barBlur.value in 8f..16f,
            )
            // 描边宽度固定 1dp
            assertEquals("$at", 1f, tokens.borderWidth.value, 0.001f)
        }
    }

    /** 背景梯度：三档、每档不透明（页面底必须是实的，否则后面的东西会透出来）*/
    @Test
    fun backgroundGradient_hasThreeOpaqueStops() {
        allTokens().forEach { (mode, dark, palette) ->
            val tokens = glassTokens(mode, dark, palette)
            assertEquals("${where(mode, dark, palette)} 背景梯度应为三档", 3, tokens.backgroundGradient.size)
            tokens.backgroundGradient.forEachIndexed { index, color ->
                assertEquals(
                    "${where(mode, dark, palette)} 背景梯度[$index] 必须完全不透明",
                    1f,
                    color.alpha,
                    0.001f,
                )
            }
        }
    }

    /**
     * 光斑色相：三个、**完全不透明**（浓淡由渲染层的 `blobAlphas` 现算），
     * 且三套配色之间互不相同。
     *
     * 为什么必须是三档、且都不透明：`blobColors[i]` 只承载**色相**，
     * 不透明度归令牌层的 `Blob*Alpha` 常量。混在一起的话，
     * "换个光斑色"会顺手改掉"光斑有多浓"，而后者是校过观感的。
     */
    @Test
    fun blobColors_areThreeOpaqueHues() {
        allTokens().forEach { (mode, dark, palette) ->
            val tokens = glassTokens(mode, dark, palette)
            val at = where(mode, dark, palette)
            assertEquals("$at 光斑应为三个", 3, tokens.blobColors.size)
            tokens.blobColors.forEachIndexed { index, color ->
                assertEquals(
                    "$at 光斑色[$index] 应只承载色相（不透明），浓淡由 blobAlphas 负责",
                    1f,
                    color.alpha,
                    0.001f,
                )
            }
        }
    }

    /** 光斑色感缩放：高斯模糊更收敛、液态玻璃满强度，落在 (0, 1] */
    @Test
    fun blobAlphaScale_staysInRange() {
        allTokens().forEach { (mode, dark, palette) ->
            val tokens = glassTokens(mode, dark, palette)
            assertTrue(
                "${where(mode, dark, palette)} 光斑色感缩放越界：${tokens.blobAlphaScale}",
                tokens.blobAlphaScale > 0f && tokens.blobAlphaScale <= 1f,
            )
        }
    }

    /**
     * 内侧同色系着色（[com.tof.manycourse.ui.theme.GlassTokens.innerTint]）：
     * 必须是**极淡**的（它压在整块玻璃上，浓了会把正文染色）。
     *
     * 上限 0.10 是刻意的：它远低于"看得出来在染色"的门槛，
     * 又足以在渐变上留下一层同色系倾向。
     */
    @Test
    fun innerTint_isSubtleAndWithinRange() {
        allTokens().forEach { (mode, dark, palette) ->
            val tokens = glassTokens(mode, dark, palette)
            assertTrue(
                "${where(mode, dark, palette)} innerTint 过浓：${tokens.innerTint.alpha}",
                tokens.innerTint.alpha in 0.001f..0.10f,
            )
        }
    }

    /** 泛光：三档、由内到外淡出（末档全透明），首档要淡 */
    @Test
    fun bloom_isAGradientFadingToTransparent() {
        allTokens().forEach { (mode, dark, palette) ->
            val tokens = glassTokens(mode, dark, palette)
            val at = where(mode, dark, palette)
            assertEquals("$at 泛光应为三档", 3, tokens.bloom.size)
            assertTrue(
                "$at 泛光首档过浓：${tokens.bloom.first().alpha}",
                tokens.bloom.first().alpha in 0.05f..0.35f,
            )
            assertTrue(
                "$at 泛光应逐档变淡：${tokens.bloom.map { it.alpha }}",
                tokens.bloom[0].alpha > tokens.bloom[1].alpha && tokens.bloom[1].alpha > tokens.bloom[2].alpha,
            )
            assertEquals("$at 泛光末档必须全透明（否则边缘会出现一圈硬边）", 0f, tokens.bloom.last().alpha, 0.0001f)
        }
    }

    /** 定向光角度落在合法区间；默认角度等价于改动前的"自上而下" */
    @Test
    fun lightAngle_isWithinRange() {
        allTokens().forEach { (mode, dark, palette) ->
            val tokens = glassTokens(mode, dark, palette)
            assertTrue(
                "${where(mode, dark, palette)} 光照角度越界：${tokens.lightAngle}",
                tokens.lightAngle in 0f..360f,
            )
        }
        assertEquals(
            "默认光照角度必须保持 90°（= 自上而下，与改动前的 verticalGradient 等价）",
            90f,
            HighlightAngleVertical,
            0f,
        )
    }

    @Test
    fun levelEnum_hasBarAndPanel() {
        assertEquals(2, GlassLevel.entries.size)
    }
}
