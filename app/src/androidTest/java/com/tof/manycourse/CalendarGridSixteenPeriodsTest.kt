package com.tof.manycourse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.ui.CalendarGridLayout
import com.tof.manycourse.ui.contrastRatio
import com.tof.manycourse.ui.courseCardDimFill
import com.tof.manycourse.ui.courseCardFill
import com.tof.manycourse.ui.gridCardTextColor
import com.tof.manycourse.ui.relativeLuminance
import com.tof.manycourse.ui.theme.LightColorScheme
import com.tof.manycourse.ui.theme.ManyCourseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * **16 节 / 8 块**下，周视图真的画出了 8 行、而且每行都是**浅色系**课程色块（真机 / 模拟器）。
 *
 * ## 为什么要有这条（而不是只靠 JVM 单测）
 *
 * JVM 单测能证明 `gridRowCount` 算出 8、行标签是 `1-2节`、`courseCardFill` 算出来的颜色
 * 压得住深字……，但**证明不了"画出来是那个样子"**：行高是不是真的按 8 行等分、卡片有没有被裁、
 * **屏幕上真正的底色**是不是浅色、字是不是深色 —— 这些只有在真正渲染一遍、
 * 再把像素读回来才能确认。
 *
 * ★ 尤其是"渲染出来的底色 ≠ 算出来的色"这件事：卡片外面还裹着静态玻璃表面
 * （高光 / 描边 / 内阴影），而**读不读得清取决于屏幕上那个颜色**。
 * 所以这里的对比度是**两个都从像素里量**：这一行的主色（卡片底）与这一行最深的那些像素
 * （文字笔画）—— 而不是拿我们算出来的 `courseCardFill` 去对 `gridCardTextColor`。
 *
 * ★ 它跑在本机（`adb shell am instrument`），**不是** `connectedAndroidTest`：
 * 目标机的测试 APK 装不上，所以这个用例保留成"手动插桩"的手法。
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class CalendarGridSixteenPeriodsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 周一（本自然周的周一，保证 `gridRowCount` 与 `coursesOn` 用的是同一批课）*/
    private val monday: LocalDate = LocalDate.now().with(java.time.DayOfWeek.MONDAY)

    private val injected = listOf(
        Triple("十六节验证一", 1, "验证楼 101"),
        Triple("十六节验证二", 3, "验证楼 202"),
        Triple("十六节验证三", 5, "验证楼 303"),
        Triple("十六节验证四", 7, "验证楼 404"),
        Triple("十六节验证五", 9, "验证楼 505"),
        Triple("十六节验证六", 11, "验证楼 606"),
        Triple("十六节验证七", 13, "验证楼 707"),
        Triple("十六节验证八", 15, "验证楼 808"),
    )

    @Before
    fun seed() {
        CourseRepository.clear()
        injected.forEach { (name, start, room) ->
            CourseRepository.add(
                name = name,
                teacher = "验证老师",
                room = room,
                weekday = 1,
                startPeriod = start,
                periodCount = 2,
                fromSchool = true,
            )
        }
    }

    @After
    fun restore() {
        // 别的用例（以及手工看界面）不该被这 8 门课污染：还原成"仅预置示例数据"
        CourseRepository.clear()
    }

    @Test
    fun sixteenPeriodsAreDrawnAsEightTwoPeriodBlocksWithLightFilledCards() {
        // 用真实学校表（默认 16 节 / 8 块）算一遍，先把"要验什么"钉在数据上
        assertEquals("默认表应该是 16 节", 16, CourseRepository.MAX_PERIOD)
        assertEquals("默认表应该是 8 个两节块", 8, CourseRepository.BLOCK_COUNT)

        composeRule.setContent {
            // 强制浅色主题：卡片的灰卡底按当前**色板**取（isDarkColorScheme），
            // 所以这里指定的主题就是卡片真正用的那一套
            ManyCourseTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(8.dp)) {
                    CalendarGridLayout(
                        weekStart = monday,
                        selected = monday,
                        today = monday,
                        weeks = emptyList(),
                        onSelect = {},
                        onAddCourse = {},
                        onCourseClick = { _, _ -> },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // ① 八行时间轴都在界面上（行标签是紧凑写法，没有「第」字）
        listOf("1-2节", "3-4节", "5-6节", "7-8节", "9-10节", "11-12节", "13-14节", "15-16节")
            .forEach { label ->
                composeRule.onNodeWithText(label).assertExists("时间轴上缺了「$label」这一行")
            }
        // 块里的第一节时刻也照旧画（三行写法）
        composeRule.onNodeWithText("9:00").assertExists()
        composeRule.onNodeWithText("21:50").assertExists()

        // ② 把像素读回来：找出 8 条**浅色彩块**的行带
        val image = composeRule.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val width = pixels.width
        val height = pixels.height
        assertTrue("截图尺寸异常：${width}x$height", width > 100 && height > 400)
        println("【16节/8块】渲染分辨率 ${width}x$height（行高约 ${height / 8}px）")

        val rowHeight = height / 8f
        // ★ 判据换成了"浅色 + 有彩度"：卡片是浅底彩块（深字），不是上一版的深色块。
        //   灰卡（近中性的浅灰）与页面底/玻璃卡面（近白）都因为没有彩度被排除在外
        val lightRows = BooleanArray(height)
        run {
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val c = pixels[x, y]
                    val r = (c.red * 255).toInt()
                    val g = (c.green * 255).toInt()
                    val b = (c.blue * 255).toInt()
                    if (maxOf(r, g, b) - minOf(r, g, b) >= 30 && (r + g + b) / 3 >= 140) {
                        lightRows[y] = true
                    }
                    x += 1
                }
                y += 1
            }
        }
        val bands = buildList {
            var start = -1
            lightRows.forEachIndexed { y, on ->
                if (on && start < 0) start = y
                if (!on && start >= 0) {
                    add(start to y)
                    start = -1
                }
            }
            if (start >= 0) add(start to height)
        }.filter { it.second - it.first > 10 }
        val cardBands = bands.filter { it.second - it.first > rowHeight * 0.5f }
        val bandText = bands.joinToString(" ") { "${it.first}..${it.second}" }
        assertEquals(
            "浅色彩块行带的数量应该正好是 8（16 节 = 8 个两节块）。实际 ${bands.size} 段：$bandText；" +
                "其中够高的 ${cardBands.size} 段",
            8,
            cardBands.size,
        )
        // 8 条行带的高度必须几乎一致 —— "行高恒等于可用高度 ÷ 行数"就是靠这一条成立的
        val heights = cardBands.map { it.second - it.first }
        assertTrue(
            "8 行的行高必须一致（实际 $heights），差 ${heights.max() - heights.min()}px 说明不是等分",
            heights.max() - heights.min() <= 6,
        )
        // 相邻两条之间的间隔也必须一致（网格线间距 = 行高，没有"多挤一行"）
        val gaps = cardBands.zipWithNext { a, b -> b.first - a.second }
        assertTrue("行与行之间的间隔必须一致（实际 $gaps）", gaps.max() - gaps.min() <= 6)

        // ③ 逐行量**屏幕上真实那个底色 + 真实那些字**的对比度
        val expectedText = gridCardTextColor(LightColorScheme)
        val dimFill = courseCardDimFill(dark = false)
        val expectedFills = injected.map { CourseRepository.colorOfName(it.first) }.map(::courseCardFill)
        injected.forEachIndexed { row, (name, start, _) ->
            val (y0, y1) = cardBands[row]
            val band = scanBand(pixels, width, y0, y1)
            val fill = band.fill
            val text = band.text
            val measured = if (fill == null) {
                "（这一行没找到浅色彩块）"
            } else {
                "#%06X 亮度 %.3f，最深的字 #%06X，实测对比度 %s".format(
                    argb(fill),
                    relativeLuminance(fill),
                    text?.let(::argb) ?: 0,
                    text?.let { "%.2f".format(contrastRatio(fill, it)) } ?: "（没找到深色笔画）",
                )
            }
            assertTrue(
                "第 ${row + 1} 行（第 $start 节起，「$name」）的卡片应当是**浅色块 + 深色字**：$measured",
                fill != null &&
                    text != null &&
                    relativeLuminance(fill) >= 0.35 &&
                    relativeLuminance(text) < relativeLuminance(fill) &&
                    contrastRatio(fill, text) >= 4.5,
            )
            // 卡片底与"算出来的那个色"对得上（差得远说明画错了底色 / 被玻璃盖住了）
            assertTrue(
                "第 ${row + 1} 行的实测底色 $fill 与算出来的 ${expectedFills[row]} 差得太远",
                contrastRatio(fill!!, expectedFills[row]) <= 1.35 ||
                    kotlin.math.abs(relativeLuminance(fill) - relativeLuminance(expectedFills[row])) <= 0.08,
            )
            // 也顺便确认这张卡**不是**灰卡（8 门课都排在本周，应该全亮）。
            // 判据与 JVM 那条一致：对比度 ≥1.20 **或**亮度差 ≥0.05 ——
            // 最亮的琥珀（#FACE85）与浅灰卡只差 1.147 / 0.105，走的是第二支
            assertTrue(
                "第 ${row + 1} 行的底色与灰卡底 $dimFill 看起来会是同一张卡" +
                    "（实测 $fill）",
                contrastRatio(fill, dimFill) >= 1.20 ||
                    kotlin.math.abs(relativeLuminance(fill) - relativeLuminance(dimFill)) >= 0.05,
            )
        }
        // 字色（从像素里量到的那支）应当与色板角色算出来的一致（允许抗锯齿带来的偏差）
        val firstText = cardBands.first().let { (y0, y1) -> scanBand(pixels, width, y0, y1).text }
        assertTrue(
            "屏幕上的字色 $firstText 与色板角色算出来的 $expectedText 应当是一支（实测像素会有抗锯齿偏差）",
            firstText != null && contrastRatio(firstText, expectedText) <= 1.30,
        )

        // ④ 落一张截图（人可以直接看）——写到应用自己的 files 目录：
        //    Android 11+ 的分区存储不允许直接写 /sdcard 根，写里面会静默失败
        runCatching {
            val file = java.io.File(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation().targetContext.filesDir,
                "manycourse-8blocks.png",
            )
            java.io.FileOutputStream(file).use { out ->
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            println("【16节/8块】截图写到 ${file.absolutePath}")
        }.onFailure { println("【16节/8块】截图没写成：${it.message}") }
    }

    /**
     * 一条行带里"卡片的底色"与"文字笔画的颜色"。
     *
     * 挑法：底色取**出现次数最多**的"浅色 + 有彩度"像素（避开文字笔画与抗锯齿边缘，
     * 那些是离群值、数量少）；字色取出现次数最多的**深色**像素（文字笔画的主体，
     * 不是抗锯齿的过渡色）。
     */
    private fun scanBand(
        pixels: androidx.compose.ui.graphics.PixelMap,
        width: Int,
        y0: Int,
        y1: Int,
    ): Band {
        val fills = HashMap<Int, Int>()
        val texts = HashMap<Int, Int>()
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < width) {
                val c = pixels[x, y]
                val r = (c.red * 255).toInt()
                val g = (c.green * 255).toInt()
                val b = (c.blue * 255).toInt()
                val sat = maxOf(r, g, b) - minOf(r, g, b)
                val mean = (r + g + b) / 3
                val key = (r shl 16) or (g shl 8) or b
                if (sat >= 30 && mean >= 140) {
                    fills[key] = (fills[key] ?: 0) + 1
                } else if (mean <= 90) {
                    texts[key] = (texts[key] ?: 0) + 1
                }
                x += 1
            }
            y += 1
        }
        return Band(
            fill = fills.maxByOrNull { it.value }?.key?.let { Color(0xFF000000.toInt() or it) },
            text = texts.maxByOrNull { it.value }?.key?.let { Color(0xFF000000.toInt() or it) },
        )
    }

    private fun argb(color: Color): Int =
        (((color.red * 255).toInt() and 0xFF) shl 16) or
            (((color.green * 255).toInt() and 0xFF) shl 8) or
            ((color.blue * 255).toInt() and 0xFF)

    private data class Band(val fill: Color?, val text: Color?)
}
