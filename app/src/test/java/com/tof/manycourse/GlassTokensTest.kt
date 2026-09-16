package com.tof.manycourse

import androidx.compose.ui.graphics.luminance
import com.tof.manycourse.ui.theme.GlassLevel
import com.tof.manycourse.ui.theme.GlassMode
import com.tof.manycourse.ui.theme.glassTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃设计令牌的纯逻辑校验（JVM，无需设备）：
 * 保证两种模式确实产出不同的视觉参数，且面板不透明度落在可读区间。
 */
class GlassTokensTest {

    @Test
    fun gaussian_isDefaultMode() {
        assertEquals(GlassMode.Gaussian, GlassMode.Default)
        assertEquals(GlassMode.Gaussian, GlassMode.fromKey(null))
        assertEquals(GlassMode.Liquid, GlassMode.fromKey("liquid"))
        assertEquals(GlassMode.Gaussian, GlassMode.fromKey("unknown-key"))
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

        // 背景渐变不同（模式可感知）
        assertNotEquals(gaussian.backgroundGradient, liquid.backgroundGradient)
    }

    @Test
    fun panelOpacity_staysReadable_inBothModesAndThemes() {
        GlassMode.entries.forEach { mode ->
            listOf(false, true).forEach { dark ->
                val tokens = glassTokens(mode, dark)
                // 面板/卡片不透明度落在可读区间（面板 90%–94%，卡片 70%–86%）
                assertTrue(
                    "$mode dark=$dark 面板过透：${tokens.panelTint.alpha}",
                    tokens.panelTint.alpha in 0.88f..0.96f,
                )
                assertTrue(
                    "$mode dark=$dark 卡片过透：${tokens.cardTint.alpha}",
                    tokens.cardTint.alpha in 0.70f..0.86f,
                )
                // 弹窗/表单面板必须比列表卡片更实：遮罩透上来不能把面板洗灰
                assertTrue(
                    "$mode dark=$dark 面板应比卡片更不透明：panel=${tokens.panelTint.alpha} card=${tokens.cardTint.alpha}",
                    tokens.panelTint.alpha > tokens.cardTint.alpha,
                )
                // 输入框底色必须是不透明的独立色（不能和面板同色，否则字段消失在面板里）
                assertEquals(
                    "$mode dark=$dark 输入框底色应完全不透明",
                    1f,
                    tokens.fieldTint.alpha,
                    0.001f,
                )
                // 输入框必须比面板底色暗一档（浅色=浅灰蓝/深色=更深的槽），否则字段消失在面板里
                assertTrue(
                    "$mode dark=$dark 输入框应比面板底色暗一档：field=${tokens.fieldTint.luminance()} panel=${tokens.panelTint.luminance()}",
                    tokens.fieldTint.luminance() < tokens.panelTint.luminance(),
                )
                // 模糊半径控制在 8–16dp，避免过度模糊（弹窗面板用静态表面，不做实时模糊）
                assertTrue(
                    "$mode dark=$dark 页头模糊越界：${tokens.barBlur}",
                    tokens.barBlur.value in 8f..16f,
                )
                // 描边宽度固定 1dp
                assertEquals(1f, tokens.borderWidth.value, 0.001f)
            }
        }
    }

    @Test
    fun levelEnum_hasBarAndPanel() {
        assertEquals(2, GlassLevel.entries.size)
    }
}
