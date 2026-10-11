package com.tof.manycourse

import androidx.compose.ui.graphics.Color

/**
 * 令牌对比度测试的共用口径。
 *
 * ## 为什么需要自己算，不能直接用 `Color.luminance()`
 *
 * `Color.luminance()` 按定义**忽略 alpha** —— 对一个"白 52%"的页头底色 `0x85FFFFFF`，
 * 它会算出 1.0（纯白），因为 RGB 三个通道都是 0xFF。而页头的实际观感亮度取决于
 * **它压在哪层背景上**：同一块白 52% 的玻璃，压在浅蓝背景和压在深色背景上完全是两种亮度。
 *
 * 这个项目里 R1/R2 记录的就是这个失败模式（页头亮度 234.8 → 218.7）：
 * 页头是**半透明**的，它的真实亮度只能靠"合成到背景上再算"才量得出来。
 * 所以这里先按 `tint.rgb * alpha + base.rgb * (1 - alpha)` 合成，再算相对亮度。
 *
 * ## 顺手加一条自身校验
 *
 * [compositedLuminance] 对**完全不透明**的颜色必须与 `Color.luminance()` 一致（误差 < 1e-4）
 * —— 否则说明这套自己写的公式抄错了，后面所有断言都是假绿。
 * 这条校验由 `GlassPaletteContrastTest.compositorAgreesWithComposeLuminance` 钉住。
 */
object GlassTestColors {

    /**
     * 把 [top] 按自身 alpha 合成到 [base] 上。
     *
     * 刻意不逐通道 `roundToInt()` 回写：这里要的是**连续**的亮度值，
     * 四舍五入到 8 位整数会在"刚好差一点点"的对比度断言上引入随机性。
     */
    fun over(top: Color, base: Color): Color {
        val a = top.alpha
        return Color(
            red = top.red * a + base.red * (1f - a),
            green = top.green * a + base.green * (1f - a),
            blue = top.blue * a + base.blue * (1f - a),
            alpha = 1f,
        )
    }

    /** WCAG 2.x 相对亮度（alpha 视为 1，[over] 已保证合成后的颜色是不透明的）*/
    fun luminance(color: Color): Double {
        fun channel(raw: Float): Double {
            val c = raw.toDouble()
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /** 一次"合成之后再量亮度"*/
    fun compositedLuminance(tint: Color, base: Color): Double = luminance(over(tint, base))

    /** WCAG 对比度 `(L1 + 0.05) / (L2 + 0.05)`，L1 是较亮的一方 */
    fun contrastRatio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * 对比度护栏的**门槛**。
     *
     * ## 这个数是怎么来的（实测，不是拍的）
     *
     * "改动前"的 `accent`（品牌蓝 `CampusBlue600` / `CampusBlue400`）对各自
     * 页面背景梯度**中档**（`backgroundGradient[1]`）的对比度，四组组合实测为：
     *
     * | 组合 | accent | 背景中档 | 对比度 |
     * |---|---|---|---|
     * | 高斯 / 浅色 | `#2563EB` | `#F5F9FF` | 4.892 |
     * | 高斯 / 深色 | `#60A5FA` | `#131C2E` | 6.698 |
     * | 液态 / 浅色 | `#2563EB` | `#F2F7FF` | **4.805** ← 最小 |
     * | 液态 / 深色 | `#60A5FA` | `#122040` | 6.323 |
     *
     * 取最小值 **4.805**（更精确是 4.8052）= "新配色一律不得比改动前更难读"。
     * 它同时也高于 WCAG AA 对正文小字的 4.5 —— `accent` 在本项目里确实被当小字色用
     * （页头刷新按钮、底栏选中态、chip 选中文字），所以 4.5 是硬底线，4.805 是现状底线，
     * 两者取大。
     *
     * **这个数字不允许为了过测试而下调**：调低它等于把门禁拆了，
     * 正确的做法是调那套配色的取值直到达标。
     */
    const val MinAccentContrast: Double = 4.805
}
