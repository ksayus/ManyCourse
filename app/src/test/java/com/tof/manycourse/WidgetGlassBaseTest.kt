package com.tof.manycourse

import com.tof.manycourse.ui.theme.GlassMode
import com.tof.manycourse.ui.theme.glassTokens
import com.tof.manycourse.widget.WidgetRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面小组件的**玻璃底板映射**（`widget/WidgetRefresh.kt` + `res/drawable/widget_next_class_*`）。
 *
 * ## 这个类能拦什么、拦不住什么（说清楚，免得下一个人以为它管得比实际多）
 *
 * **拦得住**：设置页那个「玻璃风格」开关在桌面侧的接线 ——
 * `baseDrawable(Gaussian/Liquid)` 必须指向**两份不同的** drawable。漏了这一步的表现是
 * "设置里切成液态玻璃、桌面还是高斯那块"，不崩、也不报错，只能靠断言拦。
 *
 * **拦不住**：drawable 里那几层固定色值（`values/colors.xml` 的 `widget_glass_*`）
 * 与令牌的逐字节一致。JVM 单测没有 Robolectric（本项目不引新依赖），
 * `context.getColor(R.color.…)` 会直接抛 "not mocked" —— 资源读不到就没法自动比对。
 * 这一条目前靠**手动核对 + 打包后核对**（可复现，写在这里供下次改动复用）：
 *
 * ```
 * aapt2 dump resources app-debug.apk | Select-String widget_glass
 * ```
 *
 * 输出的 `#85ffffff` / `#75ffffff` / `(night) #9e0f172a` / `(night) #940f172a`
 * 就是 `glassTokens(mode, dark).barTint` 的 8 位取值，两两相等即对齐。
 * 下面 [底板的底就是令牌的barTint] 顺带钉住 [WidgetRefresh.surfaceTint] 这个"口径函数"
 * 本身没写反（模式搞混时它会跟着错，从而与 aapt2 的输出对不上）。
 */
class WidgetGlassBaseTest {

    // ══════════════════ ① 玻璃风格 → 底板资源 ══════════════════

    @Test
    fun 两块底板都在_而且液态与高斯不是同一份() {
        val gaussian = WidgetRefresh.baseDrawable(GlassMode.Gaussian)
        val liquid = WidgetRefresh.baseDrawable(GlassMode.Liquid)

        assertTrue("高斯的底板资源 id 不能是 0", gaussian != 0)
        assertTrue("液态的底板资源 id 不能是 0", liquid != 0)
        assertNotEquals("切换玻璃风格必须真的换一份 drawable（否则设置页那个开关等于没接）", gaussian, liquid)

        // 两份都要能在 APK 里找到：R.drawable 的常量本身就是编译期资源 id，
        // 这里显式引用一次，改名/删除时编译期就会红
        assertEquals(gaussian, R.drawable.widget_next_class_bg)
        assertEquals(liquid, R.drawable.widget_next_class_bg_liquid)
    }

    // ══════════════════ ② drawable 里的底 = 令牌 barTint ══════════════════

    /**
     * 两组数字必须一致（容差 1/255：Compose 的 `Color` 内部是 8 位存储，
     * 而 `values/colors.xml` 里写的是同一批值的 8 位十六进制）。
     */
    @Test
    fun 底板的底就是令牌的barTint() {
        listOf(false, true).forEach { dark ->
            val where = if (dark) "深色" else "浅色"
            assertSameArgb(
                "$where：高斯底板的底色与独立算出来的 barTint 不一致（改了令牌没改桌面？）",
                WidgetRefresh.surfaceTint(GlassMode.Gaussian, dark),
                glassTokens(GlassMode.Gaussian, dark).barTint.toArgbValue(),
            )
            assertSameArgb(
                "$where：液态底板的底色与独立算出来的 barTint 不一致",
                WidgetRefresh.surfaceTint(GlassMode.Liquid, dark),
                glassTokens(GlassMode.Liquid, dark).barTint.toArgbValue(),
            )
        }
    }

    @Test
    fun 液态的底比高斯更透一档() {
        // 令牌口径：液态玻璃"更通透、质感靠高光/边缘光撑"（见 GlassTokens.kt 的注释）。
        // 桌面那份镜像必须保持同一关系，否则两档在桌面上看不出区别
        listOf(false, true).forEach { dark ->
            val where = if (dark) "深色" else "浅色"
            val gaussian = alphaOf(WidgetRefresh.surfaceTint(GlassMode.Gaussian, dark))
            val liquid = alphaOf(WidgetRefresh.surfaceTint(GlassMode.Liquid, dark))
            assertTrue("$where：液态的底应该比高斯更透（alpha 更小），实际 $liquid vs $gaussian", liquid < gaussian)
        }
    }

    private fun assertSameArgb(where: String, expected: Int, actual: Int) {
        val tolerance = 1
        listOf("A", "R", "G", "B").forEachIndexed { index, channel ->
            val shift = 24 - index * 8
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue(
                "$where：$channel 通道 ${e.toString(16)} vs ${a.toString(16)}（容差 $tolerance）",
                kotlin.math.abs(e - a) <= tolerance,
            )
        }
    }

    private fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF

    /** 不依赖 android.graphics：Compose 的 Color 是纯 Kotlin，JVM 单测里可以直接算 */
    private fun androidx.compose.ui.graphics.Color.toArgbValue(): Int =
        ((alpha * 255f).toInt() shl 24) or
            ((red * 255f).toInt() shl 16) or
            ((green * 255f).toInt() shl 8) or
            (blue * 255f).toInt()
}
