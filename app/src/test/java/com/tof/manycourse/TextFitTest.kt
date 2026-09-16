package com.tof.manycourse

import com.tof.manycourse.ui.TextFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「长文本压进一行」的字号自适应（JVM，无需设备）。
 *
 * 所有常量都是**真机实测值**（1080×2400 / 440dpi / font_scale 1.17 的那台机器）：
 * ```
 * 16sp → 51.0px      12sp → 38.61px      0.25sp → 0.804375px
 * 「南京航空航天大学金城学院」12 个字 @16sp → 618px
 * ```
 * 真机上文本区的宽度经历过两个阶段，正好覆盖本文件的两种场景：
 *  - **640px（现状）**：卡片往两侧撑宽后，618px 直接放得下，**字号完全不用改**
 *    （真机确认：学校框高度与用户名/密码框一致，都是 147px）；
 *  - 574px：撑宽之前的实测值，也约等于更窄的屏幕 —— 这时才需要缩小，
 *    真机实测收敛到 46.65375px（≈14.6sp），文本 565px ≤ 574px。
 *
 * 这里只测纯函数 [TextFit.fitSizePx]；「读 Layout 真实宽度」「改字号」那部分
 * 是 Android 侧行为，由 `LoginSchoolPickerTest` 在真机上验证。
 */
class TextFitTest {

    private companion object {
        /** 16sp @font_scale 1.17 */
        const val BASE_PX = 51f

        /** 12sp：缩到这个字号就不再缩 */
        const val MIN_PX = 38.61f

        /** 0.25sp */
        const val STEP_PX = 0.804375f

        /** 真机上就是那个曾经被截断的校名 */
        const val LONG_NAME = "南京航空航天大学金城学院"

        /** 每个汉字在 16sp 下宽 51.5px → 12 字 = 618px */
        const val CHAR_WIDTH_AT_BASE_PX = 51.5f

        /** 现状：卡片撑宽后的文本区，618px 放得下 */
        const val CURRENT_AVAILABLE_PX = 640f

        /** 撑宽之前的文本区宽度（真机实测），也代表更窄的屏幕 */
        const val NARROW_AVAILABLE_PX = 574f

        /** 真机实测：窄文本区下缩小后的收敛字号 */
        const val MEASURED_FITTED_PX = 46.65375f

        /** 文本宽度与字号成正比、与字数成正比 —— 与 CJK 字体度量的实际行为一致 */
        fun widthOf(text: String, sizePx: Float): Float =
            text.length * CHAR_WIDTH_AT_BASE_PX * sizePx / BASE_PX

        fun fit(text: String, availablePx: Float, currentPx: Float = BASE_PX): Float =
            TextFit.fitSizePx(
                currentSizePx = currentPx,
                textWidthPx = widthOf(text, currentPx),
                availableWidthPx = availablePx,
                minSizePx = MIN_PX,
                stepPx = STEP_PX,
            )
    }

    @Test
    fun theRealLongName_isTwelveCharsAnd618PxWide() {
        // 下面的换算依赖这两个数；写成断言，免得以后改名字忘了改数字
        assertEquals(12, LONG_NAME.length)
        assertEquals(618f, widthOf(LONG_NAME, BASE_PX), 0.5f)
    }

    @Test
    fun currentLayout_keepsTheBaseSizeUntouched() {
        // ★ 现状：文本区 640px ≥ 618px —— 字号必须原地不动。
        // 真机确认：学校框高度与用户名/密码框一致（147px），说明确实没缩。
        assertEquals(BASE_PX, fit(LONG_NAME, CURRENT_AVAILABLE_PX), 0.0001f)
    }

    @Test
    fun narrowerField_shrinksToTheMeasuredValue() {
        // 窄屏 / 更长校名时才会走这里：真机实测收敛到 46.65375px
        val next = fit(LONG_NAME, NARROW_AVAILABLE_PX)
        assertEquals(MEASURED_FITTED_PX, next, 0.0001f)
        assertTrue("应当缩小：$next", next < BASE_PX)
        assertTrue("不该缩过头：$next", next > MIN_PX)
        assertTrue(
            "缩完必须真的放得下：${widthOf(LONG_NAME, next)}px vs ${NARROW_AVAILABLE_PX}px",
            widthOf(LONG_NAME, next) <= NARROW_AVAILABLE_PX,
        )
    }

    @Test
    fun wideEnoughField_keepsTheBaseSize() {
        // 需要 618px，给到 618px 就够 —— 压线也算放得下，不该缩
        assertEquals(BASE_PX, fit(LONG_NAME, 618f), 0.0001f)
    }

    @Test
    fun shortSchoolName_neverShrinks() {
        // 短校名（如「广州软件学院」，6 字 = 309px）在真机文本区里绰绰有余
        assertEquals(BASE_PX, fit("广州软件学院", CURRENT_AVAILABLE_PX), 0.0001f)
    }

    @Test
    fun safetyMargin_leavesHeadroom() {
        // 不能算成"刚好压线"：字体度量的浮点误差会让 Layout 判成超出一点点
        val next = fit(LONG_NAME, NARROW_AVAILABLE_PX)
        assertTrue(
            "应留出余量：${widthOf(LONG_NAME, next)}px vs ${NARROW_AVAILABLE_PX}px",
            widthOf(LONG_NAME, next) < NARROW_AVAILABLE_PX,
        )
    }

    @Test
    fun neverGrowsBeyondTheCurrentSize() {
        // 本函数只负责缩小；"缩过的短名字要长回来"由 TextView 侧按基准字号判断
        assertEquals(BASE_PX, fit(LONG_NAME, 10_000f), 0.0001f)
        assertEquals(30f, fit(LONG_NAME, 10_000f, currentPx = 30f), 0.0001f)
    }

    @Test
    fun clampsToMinWhenNothingFits() {
        assertEquals(MIN_PX, fit(LONG_NAME, 1f), 0.0001f)
    }

    @Test
    fun applyingTheResultIsIdempotent() {
        // 关键性质：算出结果、应用、再算一次 —— 必须不再变化。
        // 不然每次布局回调都会再缩一档，字号会一路掉到下限。
        val next = fit(LONG_NAME, NARROW_AVAILABLE_PX)
        val again = TextFit.fitSizePx(
            currentSizePx = next,
            textWidthPx = widthOf(LONG_NAME, next),
            availableWidthPx = NARROW_AVAILABLE_PX,
            minSizePx = MIN_PX,
            stepPx = STEP_PX,
        )
        assertEquals("第二次调用不该再改字号", next, again, 0.0001f)
    }

    @Test
    fun resultAlwaysFitsOrHitsTheFloor() {
        listOf(1f, 100f, 300f, 573f, 574f, 618f, 619f, 640f, 10_000f).forEach { available ->
            val next = fit(LONG_NAME, available)
            assertTrue("字号应落在 [min, base]：$next（可用 $available）", next in MIN_PX..BASE_PX)
            assertTrue(
                "可用 ${available}px 时既没放下也没落到下限：$next",
                widthOf(LONG_NAME, next) <= available || next == MIN_PX,
            )
        }
    }

    @Test
    fun emptyOrDegenerateInputIsHandled() {
        // 可用宽度未知 / 文本还没量出来：保持不动，不要瞎缩
        assertEquals(BASE_PX, TextFit.fitSizePx(BASE_PX, 0f, CURRENT_AVAILABLE_PX, MIN_PX, STEP_PX), 0.0001f)
        assertEquals(BASE_PX, TextFit.fitSizePx(BASE_PX, 618f, 0f, MIN_PX, STEP_PX), 0.0001f)
        assertEquals(BASE_PX, TextFit.fitSizePx(BASE_PX, 618f, -5f, MIN_PX, STEP_PX), 0.0001f)
        // 已经在限下：不动
        assertEquals(20f, TextFit.fitSizePx(20f, 618f, 100f, MIN_PX, STEP_PX), 0.0001f)
    }

    @Test
    fun defaultConstantsAreSane() {
        assertTrue("余量必须在 (0,1) 内", TextFit.DEFAULT_SAFETY in 0f..1f)
        assertTrue("步进要足够细", TextFit.DEFAULT_STEP_SP <= 0.5f)
        assertTrue("步进不能是 0", TextFit.DEFAULT_STEP_SP > 0f)
    }
}
