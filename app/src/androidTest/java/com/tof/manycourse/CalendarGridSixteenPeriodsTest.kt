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
 * **16 节 / 8 块**下，周视图真的画出了 8 行、而且每行都是课程色块（真机 / 模拟器）。
 *
 * ## 为什么要有这条（而不是只靠 JVM 单测）
 *
 * JVM 单测能证明 [com.tof.manycourse.ui.gridRowCount] 算出 8、行标签是 `1-2节`……，
 * 但**证明不了"画出来是那个样子"**：行高是不是真的按 8 行等分、卡片有没有被裁、
 * 底色是不是课程色 —— 这些只有在真正渲染一遍、再把像素读回来才能确认。
 *
 * ★ 它跑在本机（`adb shell am instrument`），**不是** `connectedAndroidTest`：
 * 目标机的测试 APK 装不上，所以这个用例保留成"手动插桩"的手法。
 *
 * ## 它验了什么
 *
 * 1. **一屏 8 行**：时间轴上 `1-2节` … `15-16节` 八行都在（`onNodeWithText` 逐个断言），
 *    说明 16 节确实被折成 8 个块、而且没有一行掉到屏幕外；
 * 2. **行高是等分的、每行都是课程色块**：把整棵 Compose 树的像素读回来，先按像素找出
 *    "深色色块"的 8 条行带，断言它们**等高、等间距**（实测每行 214px、间距 12px），
 *    再逐行量"白字压在这个**渲染出来的**底色上"的对比度（实测最差 **8.31**，
 *    WCAG AA 小字门槛是 4.5）；
 * 3. 顺带把整屏截图写到应用私有目录的 `manycourse-8blocks.png`，人可以直接看。
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
    fun sixteenPeriodsAreDrawnAsEightTwoPeriodBlocksWithFilledCards() {
        // 用真实学校表（默认 16 节 / 8 块）算一遍，先把"要验什么"钉在数据上
        assertEquals("默认表应该是 16 节", 16, CourseRepository.MAX_PERIOD)
        assertEquals("默认表应该是 8 个两节块", 8, CourseRepository.BLOCK_COUNT)

        composeRule.setContent {
            ManyCourseTheme {
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

        // ② 把像素读回来：8 个行带里各找一次"这一行课程的压深色"
        val image = composeRule.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val width = pixels.width
        val height = pixels.height
        assertTrue("截图尺寸异常：${width}x$height", width > 100 && height > 400)
        println("【16节/8块】渲染分辨率 ${width}x${height}（行高约 ${height / 8}px）")

        val rowHeight = height / 8f
        // 这一屏到底有些什么颜色：**放进断言消息里**（插桩测试的 stdout 不进报告，
        // 只有失败消息一定会被带回来 —— 这正是排查时最需要的那半张图）
        val hist = HashMap<Int, Int>()
        run {
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val c = pixels[x, y]
                    val key = ((c.red * 255).toInt() shl 16) or
                        ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
                    hist[key] = (hist[key] ?: 0) + 1
                    x += 2
                }
                y += 2
            }
        }
        val top = hist.entries.sortedByDescending { it.value }.take(20)
            .joinToString(" ") { "#%06X×%d".format(it.key, it.value) }

        // ★ 量的是**渲染出来的**像素，不是我们算出来的那个色：
        //   卡片外面还裹着静态玻璃表面（高光/描边/内阴影），实测渲染色比
        //   `courseCardFill` 更暗（#21498A → #153870）。既然"读不读得清"取决于
        //   屏幕上真正的那个颜色，断言就必须落在它上面。
        // 先找出"深色色块"在竖直方向上的**真实分布**：网格不是从 y=0 开始的
        // （上面还有周次滑块、图例、星期表头），所以不能拿 height/8 当行边界
        val darkRows = BooleanArray(height)
        run {
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val c = pixels[x, y]
                    val r = (c.red * 255).toInt(); val g = (c.green * 255).toInt(); val b = (c.blue * 255).toInt()
                    if (maxOf(r, g, b) - minOf(r, g, b) >= 40 && (r + g + b) / 3 <= 120) darkRows[y] = true
                    x += 2
                }
                y += 1
            }
        }
        val bands = buildList {
            var start = -1
            darkRows.forEachIndexed { y, on ->
                if (on && start < 0) start = y
                if (!on && start >= 0) { add(start to y); start = -1 }
            }
            if (start >= 0) add(start to height)
        }.filter { it.second - it.first > 10 }
        // 卡片自己那 8 条：最下面的第 9 条是「+ 添加」那个蓝色按钮，不是卡片
        // （宽度远小于整行 —— 用高度把两者分开，卡片行带高约等于行高）
        val cardBands = bands.filter { it.second - it.first > rowHeight * 0.5f }
        val bandText = bands.joinToString(" ") { "${it.first}..${it.second}" }
        assertEquals(
            "深色卡片行带的数量应该正好是 8（16 节 = 8 个两节块）。实际 ${bands.size} 段：$bandText；" +
                "其中够高的 ${cardBands.size} 段；这一屏最多的颜色：$top",
            8,
            cardBands.size,
        )
        // 8 条行带的高度必须几乎一致 —— "行高恒等于可用高度 ÷ 行数"就是靠这一条成立的
        val heights = cardBands.map { it.second - it.first }
        val minH = heights.min()
        val maxH = heights.max()
        assertTrue(
            "8 行的行高必须一致（实际 $heights），差 ${maxH - minH}px 说明不是等分",
            maxH - minH <= 6,
        )
        // 相邻两条之间的间隔也必须一致（网格线间距 = 行高，没有"多挤一行"）
        val gaps = cardBands.zipWithNext { a, b -> b.first - a.second }
        assertTrue("行与行之间的间隔必须一致（实际 $gaps）", gaps.max() - gaps.min() <= 6)

        injected.forEachIndexed { row, (_, start, _) ->
            val (y0, y1) = cardBands[row]
            val band = rowBand(pixels, width, y0, y1)
            val fill = band.worstFill
            val ratio = if (fill == null) 0.0 else contrastRatio(fill, Color.White)
            val measured = if (fill == null) "（这一行没找到深色块）" else
                "#%06X 白字 %.2f".format(
                    ((fill.red * 255).toInt() shl 16) or
                        ((fill.green * 255).toInt() shl 8) or (fill.blue * 255).toInt(),
                    ratio,
                )
            assertTrue(
                "第 ${row + 1} 行（第 $start 节起）的课程色块不该看不出来：$measured" +
                    "；这一屏最多的颜色：$top",
                fill != null && ratio >= 4.5,
            )
        }

        // ③ 落一张截图（人可以直接看）——写到应用自己的 files 目录：
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
     * 某一行里"最像课程色块"的那个像素。
     *
     * 挑法：在这条行带里找**饱和度足够高、又足够暗**（白字压得住）的像素，
     * 取其中出现次数最多的那个 —— 用"出现最多"而不是"最暗"，是为了避开文字笔画
     * 与抗锯齿边缘（那些是离群值，数量少）。
     *
     * @return null = 这一行里根本没有"深色高饱和块"（说明卡片没画出来）
     */
    private fun rowBand(
        pixels: androidx.compose.ui.graphics.PixelMap,
        width: Int,
        y0: Int,
        y1: Int,
    ): Band {
        val hist = HashMap<Int, Int>()
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < width) {
                val c = pixels[x, y]
                val r = (c.red * 255).toInt()
                val g = (c.green * 255).toInt()
                val b = (c.blue * 255).toInt()
                val sat = maxOf(r, g, b) - minOf(r, g, b)
                // "深色块"的判据：有彩度（不是灰底）+ 够暗（白字能到 4.5）
                if (sat >= 40 && (r + g + b) / 3 <= 120) {
                    val key = (r shl 16) or (g shl 8) or b
                    hist[key] = (hist[key] ?: 0) + 1
                }
                x += 1
            }
            y += 2
        }
        val key = hist.maxByOrNull { it.value }?.key ?: return Band(null)
        return Band(Color(0xFF000000.toInt() or key))
    }

    private data class Band(val worstFill: Color?)
}
