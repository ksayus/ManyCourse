package com.tof.manycourse

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tof.manycourse.ui.theme.GlassMode
import com.tof.manycourse.ui.theme.GlassTokens
import com.tof.manycourse.ui.theme.glassTokens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **零视觉回归金值测试**：把"引入配色系统之前"的令牌取值**硬编码**在这里。
 *
 * ## 为什么必须在改产品代码之前写
 *
 * `校园蓝`（[GlassPalette.CampusBlue]，也是默认配色）的契约是"与改动前逐字段完全一致"。
 * 金值只有在改动**之前**写死才有意义 —— 若等改完代码再"取当前值当金值"，
 * 那只是自己给自己发合格证：一旦重构顺手改动了某个取值（比如把 `0x1A90CAF9`
 * 写成 `0x1990CAF9`），测试会跟着新值一起变绿，回归就再也拦不住了。
 *
 * ## 覆盖边界
 *
 * 只断言**改动前就已存在**的字段。本轮新增的面（`blobColors` / `innerTint` / `bloom` /
 * `lightAngle`）没有"改动前"可比，改由 `GlassTokensTest` 的区间断言负责 ——
 * 在这里给新字段编一个金值，等于假装它们是既有取值。
 *
 * ## 比较口径
 *
 * 颜色一律比 `toArgb()`（比 `Color` 对象更严格：`Color` 是值类，
 * 两个不同色彩空间下"看起来一样"的颜色可能相等，而 ARGB 整数不会）；
 * `Dp` 比 `.value`；`List<Color>` 逐项比。
 *
 * 四组组合顺序 = `(Gaussian,false) (Gaussian,true) (Liquid,false) (Liquid,true)`。
 */
class GlassTokenGoldenTest {

    /** 一组"改动前"的四个模式 × 明暗组合的完整取值 */
    private class Golden(
        val barTint: Long,
        val panelTint: Long,
        val cardTint: Long,
        val cardTintHighlighted: Long,
        val fieldTint: Long,
        val barBlur: Float,
        val border: Long,
        val borderWidth: Float,
        val barSeparator: Long,
        val barShadow: Float,
        val panelShadow: Float,
        val cardShadow: Float,
        val shadowColor: Long,
        /** `highlight` 的三档，压平成 3 个数字 */
        val highlight: List<Long>,
        val saturation: Float,
        val saturationOverlay: Long,
        val edgeLight: Long,
        val innerShadow: Long,
        val backgroundGradient: List<Long>,
        val blobAlphaScale: Float,
        val scrim: Long,
        val accent: Long,
    )

    /** `FFRRGGBB` → `Color`。写成字面量而不是 `Color(0xFF2563EB)`，是为了让"金值"看起来就是一份取自测量结果的表 */
    private fun argb(value: Long): Int = value.toInt()

    private fun colorOf(value: Long): Color = Color(argb(value))

    private fun listOfColors(vararg values: Long): List<Color> = values.map(::colorOf)

    private fun assertColor(field: String, expected: Long, actual: Color) {
        assertEquals(
            "$field 发生了视觉回归：期望 #${expected.toString(16).uppercase()}，" +
                "实际 #${actual.toArgb().toUInt().toString(16).uppercase()}",
            argb(expected),
            actual.toArgb(),
        )
    }

    private fun assertColors(field: String, expected: List<Long>, actual: List<Color>) {
        assertEquals("$field 项数变了", expected.size, actual.size)
        expected.forEachIndexed { index, value -> assertColor("$field[$index]", value, actual[index]) }
    }

    private fun assertGolden(mode: GlassMode, dark: Boolean, golden: Golden) {
        val tokens: GlassTokens = glassTokens(mode, dark)
        val where = "$mode dark=$dark"

        assertColor("$where barTint", golden.barTint, tokens.barTint)
        assertColor("$where panelTint", golden.panelTint, tokens.panelTint)
        assertColor("$where cardTint", golden.cardTint, tokens.cardTint)
        assertColor("$where cardTintHighlighted", golden.cardTintHighlighted, tokens.cardTintHighlighted)
        assertColor("$where fieldTint", golden.fieldTint, tokens.fieldTint)
        assertEquals("$where barBlur", golden.barBlur, tokens.barBlur.value, 0f)
        assertColor("$where border", golden.border, tokens.border)
        assertEquals("$where borderWidth", golden.borderWidth, tokens.borderWidth.value, 0f)
        assertColor("$where barSeparator", golden.barSeparator, tokens.barSeparator)
        assertEquals("$where barShadow", golden.barShadow, tokens.barShadow.value, 0f)
        assertEquals("$where panelShadow", golden.panelShadow, tokens.panelShadow.value, 0f)
        assertEquals("$where cardShadow", golden.cardShadow, tokens.cardShadow.value, 0f)
        assertColor("$where shadowColor", golden.shadowColor, tokens.shadowColor)
        assertColors("$where highlight", golden.highlight, tokens.highlight)
        assertEquals("$where saturation", golden.saturation, tokens.saturation, 0f)
        assertColor("$where saturationOverlay", golden.saturationOverlay, tokens.saturationOverlay)
        assertColor("$where edgeLight", golden.edgeLight, tokens.edgeLight)
        assertColor("$where innerShadow", golden.innerShadow, tokens.innerShadow)
        assertColors("$where backgroundGradient", golden.backgroundGradient, tokens.backgroundGradient)
        assertEquals("$where blobAlphaScale", golden.blobAlphaScale, tokens.blobAlphaScale, 0f)
        assertColor("$where scrim", golden.scrim, tokens.scrim)
        assertColor("$where accent", golden.accent, tokens.accent)
    }

    @Test
    fun defaultPalette_matchesPrePaletteValues_gaussianLight() = assertGolden(
        GlassMode.Gaussian, dark = false,
        Golden(
            barTint = 0x85FFFFFF,
            panelTint = 0xF0FFFFFF,
            cardTint = 0xC7FFFFFF,
            cardTintHighlighted = 0xDBFFFFFF,
            fieldTint = 0xFFE3EBF7,
            barBlur = 14f,
            border = 0x80FFFFFF,
            borderWidth = 1f,
            barSeparator = 0xFFE2E8F0,
            barShadow = 12f,
            panelShadow = 8f,
            cardShadow = 3f,
            shadowColor = 0x1A0F172A,
            highlight = listOf(0x24FFFFFF, 0x05FFFFFF, 0x00000000),
            saturation = 0.94f,
            saturationOverlay = 0x0664748B,
            edgeLight = 0x00000000,
            innerShadow = 0x00000000,
            backgroundGradient = listOf(0xFFDCEAFF, 0xFFF5F9FF, 0xFFE8F0FE),
            blobAlphaScale = 0.75f,
            scrim = 0x520F172A,
            accent = 0xFF2563EB,
        ),
    )

    @Test
    fun defaultPalette_matchesPrePaletteValues_gaussianDark() = assertGolden(
        GlassMode.Gaussian, dark = true,
        Golden(
            barTint = 0x9E0F172A,
            panelTint = 0xF01E293B,
            cardTint = 0xC71E293B,
            cardTintHighlighted = 0xDB1E293B,
            fieldTint = 0xFF16202F,
            barBlur = 14f,
            border = 0x1FFFFFFF,
            borderWidth = 1f,
            barSeparator = 0xFF334155,
            barShadow = 12f,
            panelShadow = 8f,
            cardShadow = 3f,
            shadowColor = 0x80000000,
            highlight = listOf(0x0FFFFFFF, 0x05FFFFFF, 0x00000000),
            saturation = 0.96f,
            saturationOverlay = 0x0464748B,
            edgeLight = 0x00000000,
            innerShadow = 0x00000000,
            backgroundGradient = listOf(0xFF0B1220, 0xFF131C2E, 0xFF0E1729),
            blobAlphaScale = 0.70f,
            scrim = 0x9E000000,
            accent = 0xFF60A5FA,
        ),
    )

    @Test
    fun defaultPalette_matchesPrePaletteValues_liquidLight() = assertGolden(
        GlassMode.Liquid, dark = false,
        Golden(
            barTint = 0x75FFFFFF,
            panelTint = 0xE6FFFFFF,
            cardTint = 0xB8FFFFFF,
            cardTintHighlighted = 0xD1FFFFFF,
            fieldTint = 0xFFE8EFFA,
            barBlur = 16f,
            border = 0xB8FFFFFF,
            borderWidth = 1f,
            barSeparator = 0x99FFFFFF,
            barShadow = 14f,
            panelShadow = 14f,
            cardShadow = 6f,
            shadowColor = 0x240F172A,
            highlight = listOf(0x4DFFFFFF, 0x0FFFFFFF, 0x05FFFFFF),
            saturation = 1.10f,
            saturationOverlay = 0x0B60A5FA,
            edgeLight = 0x99FFFFFF,
            innerShadow = 0x0D000000,
            backgroundGradient = listOf(0xFFD3E4FF, 0xFFF2F7FF, 0xFFE0EBFF),
            blobAlphaScale = 1.00f,
            scrim = 0x520F172A,
            accent = 0xFF2563EB,
        ),
    )

    @Test
    fun defaultPalette_matchesPrePaletteValues_liquidDark() = assertGolden(
        GlassMode.Liquid, dark = true,
        Golden(
            barTint = 0x940F172A,
            panelTint = 0xEB1E293B,
            cardTint = 0xBD1E293B,
            cardTintHighlighted = 0xD61E293B,
            fieldTint = 0xFF16202F,
            barBlur = 16f,
            border = 0x2EFFFFFF,
            borderWidth = 1f,
            barSeparator = 0x3DFFFFFF,
            barShadow = 14f,
            panelShadow = 14f,
            cardShadow = 6f,
            shadowColor = 0x8C000000,
            highlight = listOf(0x1AFFFFFF, 0x08FFFFFF, 0x05FFFFFF),
            saturation = 1.04f,
            saturationOverlay = 0x0560A5FA,
            edgeLight = 0x38FFFFFF,
            innerShadow = 0x1A000000,
            backgroundGradient = listOf(0xFF0A1428, 0xFF122040, 0xFF0B1730),
            blobAlphaScale = 1.00f,
            scrim = 0x94000000,
            accent = 0xFF60A5FA,
        ),
    )
}
