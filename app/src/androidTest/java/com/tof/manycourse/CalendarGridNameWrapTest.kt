package com.tof.manycourse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * **课名要能显示全、可以换行**（真机 / 模拟器）：把参考图里那几门长名字的课注入进去，
 * 渲染整张周视图，再逐条验"换行了、没被裁、字是深色压在浅色块上"。
 *
 * ## 为什么要插桩（而不是只靠 JVM 单测）
 *
 * JVM 单测钉住的是"字号梯子怎么选"（`GridCardNameFitTest`，用字宽模型替代真实字体）。
 * 而"**真实字体**下这个名字到底占几行、真的画出来了吗、有没有被卡片下沿切掉"
 * 只有渲染一遍才知道 —— 卡片的宽只有 41dp，字体度量的微小差别就足以让 7sp 变 6.5sp。
 *
 * 所以这里读两样**运行时的事实**：
 *  1. **Compose 自己的排版结果**（`SemanticsActions.GetTextLayoutResult`）：
 *     `lineCount` 与"每一行有没有被省略号截断"（`isLineEllipsized`）—— 这是"显示全了吗"
 *     最权威的答案，比人眼看截图还准；
 *  2. **屏幕像素**：卡片底是不是浅色、字是不是深色、两者实测对比度够不够 ——
 *     "渲染出来的底色 ≠ 算出来的色"（玻璃表面 / 抗锯齿）只有量屏幕才算数。
 *
 * ## 截图
 *
 * 跑的时候带 `-e shot true` 会在这两个用例里各停一段时间（见 [shotHold]），
 * 外面用 `adb exec-out screencap -p` 就能拍到同一屏（浅色 / 深色各一张）。
 * 两个用例也各自把截图写进应用私有目录，可以直接 `adb pull`。
 *
 * ★ 手动插桩，不跑 `connectedAndroidTest`：
 * ```
 * adb shell am instrument -w -e shot true \
 *   -e class com.tof.manycourse.CalendarGridNameWrapTest \
 *   com.tof.manycourse.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class CalendarGridNameWrapTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val monday: LocalDate = LocalDate.now().with(DayOfWeek.MONDAY)

    /** 周一一天排满 8 个两节块（= 8 行都排过课 → 每格只有 46dp 上下，最挤的那种）*/
    private val injected = listOf(
        Triple("大学英语I（综合基础）", 1, "笃行楼U101"),
        Triple("中国近现代史纲要", 3, "明德楼 202"),
        Triple("数字媒体导论", 5, "厚德楼G303"),
        Triple("C程序设计基础", 7, "笃行楼U404"),
        Triple("形势与政策I", 9, "明德楼 505"),
        Triple("高等数学I（理）", 11, "明德楼 606"),
        Triple("职业生涯规划", 13, "明德楼 707"),
        Triple("大学体育I", 15, "明德楼 808"),
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
        CourseRepository.clear()
    }

    // ══════════════════ 浅色主题 ══════════════════

    @Test
    fun lightThemeShowsLightCardsWithFullyDrawnWrappedNames() {
        renderAndVerify(dark = false)
    }

    // ══════════════════ 深色主题（卡片仍是浅底深字）══════════════════

    @Test
    fun darkThemeShowsTheSameLightCardsWithFullyDrawnWrappedNames() {
        renderAndVerify(dark = true)
    }

    // ══════════════════ 灰卡（这一周不上）也必须是浅色 + 深字 ══════════════════

    @Test
    fun aWeekWithoutClassesShowsLightGreyCardsInTheLightTheme() {
        renderDimWeek(dark = false)
    }

    @Test
    fun aWeekWithoutClassesShowsGreyCardsInTheDarkTheme() {
        renderDimWeek(dark = true)
    }

    /**
     * 翻到**上一周**（不在本自然周里 → 本地课表不兜底、也没有按周数据）：
     * 画布照旧、**一门都不点亮** —— 整屏都是"这一周不上"的灰卡。
     *
     * 这条量的是灰卡的**屏幕像素**：底色是不是浅灰（深色主题是中灰）、字是不是深色、
     * 两者对比度够不够。上一版灰卡是深底白字，这一版必须是一块浅底。
     */
    private fun renderDimWeek(dark: Boolean) {
        val theme = if (dark) "深色" else "浅色"
        val page = if (dark) Color(0xFF0F172A) else Color.White
        val previousWeek = monday.minusWeeks(1)
        val dimFill = courseCardDimFill(dark)

        composeRule.setContent {
            ManyCourseTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(page).padding(8.dp)) {
                    CalendarGridLayout(
                        weekStart = previousWeek,
                        selected = previousWeek,
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

        val image = composeRule.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val dimArgb = argb(dimFill)

        // ① **整块灰着**：灰卡的底色在屏幕上占了一大片（8 张卡 + 它们之间由网格线连成一片）。
        //    这里按**精确像素值**数，不用 sameColour —— 后者对近白色也成立（白对浅灰只有 1.28），
        //    数进来的就会是页面底而不是卡片
        var dimPixels = 0
        var darkest = 255
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (argb(c) == dimArgb) dimPixels += 1
                darkest = minOf(darkest, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
            }
        }
        assertTrue(
            "【$theme】上一周应当是一大片灰卡底（$dimFill），实测只有 $dimPixels 个像素是那个色",
            dimPixels >= 100_000,
        )
        assertTrue("【$theme】满屏灰卡上还得有深色字（实测最深的通道是 $darkest）", darkest <= 90)

        // ② 每一门课：名字完整画出（没有省略号），而且它的**局部底色**就是灰卡色、字是深色
        injected.forEach { (name, _, _) ->
            val layout = textLayoutOf(name)
            assertTrue("【$theme】灰卡上的「$name」取不到排版结果", layout != null)
            val lines = (0 until layout!!.lineCount).filter { layout.isLineEllipsized(it) }
            assertTrue(
                "【$theme】灰卡上的「$name」有 $lines 行被省略号截断（灰卡也要把名字显示全）",
                lines.isEmpty(),
            )
            val bounds = composeRule.onNodeWithText(name, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val band = sampleRect(
                pixels = pixels,
                width = pixels.width,
                height = pixels.height,
                // 名字四周多取几个像素：底色就在笔画周围，扩一点才够本
                x0 = bounds.left.toInt() - 6,
                y0 = bounds.top.toInt() - 6,
                x1 = bounds.right.toInt() + 6,
                y1 = bounds.bottom.toInt() + 6,
                fillPredicate = ::isDimFillPixel,
            )
            val fill = band.fill
            val text = band.text
            assertTrue(
                "【$theme】「$name」所在的灰卡应当是**浅底 + 深字**：" +
                    "实测底色 $fill（亮度 ${fill?.let { "%.3f".format(relativeLuminance(it)) } }，" +
                    "算出来是 $dimFill）、字 $text、实测对比度 " +
                    "${if (fill != null && text != null) "%.2f".format(contrastRatio(fill, text)) else "无"}",
                fill != null && text != null &&
                    relativeLuminance(fill) >= 0.35 &&
                    relativeLuminance(text) < relativeLuminance(fill) &&
                    contrastRatio(fill, text) >= 4.5,
            )
        }

        dumpShot(image, "manycourse-week-dim-${if (dark) "dark" else "light"}.png", theme)
        shotHold(theme)
    }

    private fun renderAndVerify(dark: Boolean) {
        val theme = if (dark) "深色" else "浅色"
        val page = if (dark) Color(0xFF0F172A) else Color.White

        composeRule.setContent {
            ManyCourseTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(page).padding(8.dp)) {
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

        // ① 每门课的名字都必须在语义树里（这是"这门课画出来了"的最低要求）
        injected.forEach { (name, _, _) ->
            composeRule.onNodeWithText(name, useUnmergedTree = true)
                .assertExists("【$theme】画面上没有「$name」这张卡")
        }

        // ② 名字**完整显示**：Compose 自己的排版结果里没有一行被省略号截断
        val longName = "大学英语I（综合基础）"
        val layout = textLayoutOf(longName)
        assertTrue(
            "【$theme】取不到「$longName」的排版结果（语义树里没有 GetTextLayoutResult），" +
                "就没法证明「显示全了」—— 这是插桩该给的那半个答案",
            layout != null,
        )
        layout!!
        assertTrue(
            "【$theme】「$longName」11 个字在 41dp 宽的格子里一行放不下，必须换行（实际 ${layout.lineCount} 行）",
            layout.lineCount >= 2,
        )
        val ellipsised = (0 until layout.lineCount).filter { layout.isLineEllipsized(it) }
        assertTrue(
            "【$theme】「$longName」的第 $ellipsised 行被省略号截断了 —— " +
                "用户要的是「名字显示全」，不是「截得好看一点」（实际 ${layout.lineCount} 行）",
            ellipsised.isEmpty(),
        )

        // ③ 名字**没有被卡片下沿切掉**：文字节点的排版高度必须落在卡片节点之内
        val textBounds = composeRule.onNodeWithText(longName, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val cardBounds = composeRule.onNodeWithText(longName)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "【$theme】课名的排版高度 ${textBounds.height}px 超出了卡片 ${cardBounds.height}px —— 字被切了",
            textBounds.height <= cardBounds.height,
        )
        assertTrue(
            "【$theme】课名（${textBounds.top}..${textBounds.bottom}）跑到卡片" +
                "（${cardBounds.top}..${cardBounds.bottom}）外面去了",
            textBounds.top >= cardBounds.top && textBounds.bottom <= cardBounds.bottom,
        )

        // ④ 屏幕像素：卡片是浅色的、字是深色的、实测对比度达标
        val image = composeRule.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val expectedText = gridCardTextColor(LightColorScheme)
        // 8 门课各自"算出来"的浅色调：行带识别用它，顺手也验了屏幕上的色与算出来的是同一个
        val expectedFills = injected
            .map { courseCardFill(CourseRepository.colorOfName(it.first)) }
            .distinct()
        val raw = candidateBands(pixels, pixels.width, pixels.height, ::isLightFill)
        val bands = cardBands(pixels, pixels.width, pixels.height, ::isLightFill, expectedFills)
        assertEquals(
            "【$theme】8 门课应当画出 8 条浅色彩块行带，实际 ${bands.size} 条。" +
                "所有候选行带（含主色与像素数）：${describe(pixels, pixels.width, raw, ::isLightFill)}；" +
                "算出来的 8 个浅色调：$expectedFills",
            8,
            bands.size,
        )
        bands.forEachIndexed { index, (y0, y1) ->
            val band = sampleRect(pixels, pixels.width, pixels.height, 0, y0, pixels.width, y1)
            val fill = band.fill
            val text = band.text
            assertTrue(
                "【$theme】第 ${index + 1} 行的卡片应当是浅色块 + 深色字：" +
                    "底色 $fill（亮度 ${fill?.let { "%.3f".format(relativeLuminance(it)) } }）、" +
                    "字 $text、实测对比度 " +
                    "${if (fill != null && text != null) "%.2f".format(contrastRatio(fill, text)) else "无"}",
                fill != null && text != null &&
                    relativeLuminance(fill) >= 0.35 &&
                    relativeLuminance(text) < relativeLuminance(fill) &&
                    contrastRatio(fill, text) >= 4.5,
            )
            assertTrue(
                "【$theme】第 ${index + 1} 行的实测字色 $text 应当就是那支深色字（色板角色 $expectedText）：" +
                    "实测亮度 ${text?.let { "%.3f".format(relativeLuminance(it)) }}。" +
                    "★ 判据留了余量：笔画边缘的抗锯齿会把「出现最多的那个深色像素」抬亮一点点，" +
                    "所以量的是「足够暗（≤0.10）且与那支纯色差得不多」",
                text != null && relativeLuminance(text) <= 0.10 && contrastRatio(text, expectedText) <= 1.60,
            )
            // 也顺便确认这张卡**不是**灰卡（8 门课都排在本周，应该全亮）。
            // 判据与 JVM 那条一致：对比度 ≥1.20 **或**亮度差 ≥0.05
            assertTrue(
                "【$theme】第 ${index + 1} 行的底色与「这一周不上」的灰卡色 ${courseCardDimFill(dark)}" +
                    "看起来会是同一张卡（实测 $fill）",
                fill != null &&
                    (contrastRatio(fill, courseCardDimFill(dark)) >= 1.20 ||
                        kotlin.math.abs(
                            relativeLuminance(fill) - relativeLuminance(courseCardDimFill(dark)),
                        ) >= 0.05),
            )
        }

        dumpShot(image, "manycourse-week-${if (dark) "dark" else "light"}.png", theme)
        shotHold(theme)
    }

    // ══════════════════ 工具 ══════════════════

    /**
     * 某个课名**真实排版**的结果（行数 / 每行有没有省略号 / 排版尺寸）。
     *
     * 走 Compose 给无障碍用的那个语义动作（`GetTextLayoutResult`）—— 它就是"画出来的排版"
     * 本身，比自己按字数估可靠得多。取不到时返回 null，由调用方断言。
     */
    private fun textLayoutOf(name: String): TextLayoutResult? = runCatching {
        val node = composeRule.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        results.firstOrNull()
    }.getOrNull()

    /**
     * 卡片行带的候选：一行里只要有满足 [predicate] 的像素就算一行，再把相邻的连成一整条。
     *
     * 候选里会混进周次滑块、表头"今天"的胶囊、底部「+ 添加」的蓝字、深色主题下表头的
     * 星期文字（`onSurfaceVariant` 本身就浅）—— 它们的抗锯齿边缘同样落在这些色域里。
     * 谁是真正的卡片由 [cardBands] 判。
     */
    private fun candidateBands(
        pixels: androidx.compose.ui.graphics.PixelMap,
        width: Int,
        height: Int,
        predicate: (Color) -> Boolean,
    ): List<Pair<Int, Int>> {
        val rows = BooleanArray(height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (predicate(pixels[x, y])) {
                    rows[y] = true
                    break
                }
            }
        }
        val raw = mutableListOf<Pair<Int, Int>>()
        var start = -1
        rows.forEachIndexed { y, on ->
            if (on && start < 0) start = y
            if (!on && start >= 0) {
                raw += start to y
                start = -1
            }
        }
        if (start >= 0) raw += start to height
        return raw.filter { it.second - it.first > 10 }
    }

    /**
     * 真正的**卡片行带**：主色要占足够多的像素（几万，而不是几百），
     * 而且主色必须**就是算出来的那个底色**（[expected] 之一）。
     *
     * 后者顺手把"屏幕上的底色与算出来的色是不是同一个"也一起验了。
     */
    private fun cardBands(
        pixels: androidx.compose.ui.graphics.PixelMap,
        width: Int,
        height: Int,
        predicate: (Color) -> Boolean,
        expected: List<Color>,
    ): List<Pair<Int, Int>> = candidateBands(pixels, width, height, predicate).filter { (y0, y1) ->
        val (dominant, count) = dominant(pixels, width, y0, y1, predicate) ?: return@filter false
        count >= MinCardBandPixels && expected.any { sameColour(dominant, it) }
    }

    /** 行带里满足 [predicate] 的那些像素的主色与它的像素数 */
    private fun dominant(
        pixels: androidx.compose.ui.graphics.PixelMap,
        width: Int,
        y0: Int,
        y1: Int,
        predicate: (Color) -> Boolean,
    ): Pair<Color, Int>? {
        val hist = HashMap<Int, Int>()
        for (y in y0 until y1) {
            for (x in 0 until width) {
                val c = pixels[x, y]
                if (!predicate(c)) continue
                val key = argb(c)
                hist[key] = (hist[key] ?: 0) + 1
            }
        }
        val best = hist.maxByOrNull { it.value } ?: return null
        return Color(0xFF000000.toInt() or best.key) to best.value
    }

    /** 失败时用：把每条行带的主色与像素数写进断言消息（插桩的 stdout 不进报告）*/
    private fun describe(
        pixels: androidx.compose.ui.graphics.PixelMap,
        width: Int,
        bands: List<Pair<Int, Int>>,
        predicate: (Color) -> Boolean,
    ): String = bands.joinToString(" ") { (y0, y1) ->
        val d = dominant(pixels, width, y0, y1, predicate)
        "[$y0..$y1 ${d?.first} ×${d?.second ?: 0}]"
    }

    /**
     * 屏幕上的色与算出来的色是不是同一个。
     *
     * 允许一点偏差：卡片外面裹着静态玻璃表面（高光 / 描边 / 内阴影）与抗锯齿，
     * 实测会与算出来的值差一点点 —— 判据与 `CalendarGridSixteenPeriodsTest` 同一口径。
     */
    private fun sameColour(a: Color, b: Color): Boolean =
        contrastRatio(a, b) <= 1.35 ||
            kotlin.math.abs(relativeLuminance(a) - relativeLuminance(b)) <= 0.08

    /** "浅色 + 有彩度" = 浅色系课程色块的那个色域（灰卡与页面底都是中性色，被排除）*/
    private fun isLightFill(c: Color): Boolean {
        val r = (c.red * 255).toInt()
        val g = (c.green * 255).toInt()
        val b = (c.blue * 255).toInt()
        return maxOf(r, g, b) - minOf(r, g, b) >= 30 && (r + g + b) / 3 >= 140
    }

    /**
     * 灰卡的色域：**中性**（没有彩度）而且**在中间那一段亮度上**。
     *
     * 上下两侧都排除掉了：页面底与玻璃卡面（本机实测均值 242~255）比它亮，
     * 深色字（≤90）比它暗，卡片里的彩色块有彩度 —— 于是这个色域里只剩灰卡自己。
     * 浅色主题的浅灰（`#E2E3E5`，均值 228）与深色主题的中灰（`#ADB0B7`，均值 177）都在里面。
     */
    private fun isDimFillPixel(c: Color): Boolean {
        val r = (c.red * 255).toInt()
        val g = (c.green * 255).toInt()
        val b = (c.blue * 255).toInt()
        val mean = (r + g + b) / 3
        return maxOf(r, g, b) - minOf(r, g, b) <= 20 && mean in 150..240
    }

    /**
     * 一块区域里"卡片的底色"与"文字笔画的颜色"（出现次数最多的那两个）。
     *
     * [fillPredicate] 决定底色按哪个色域找：亮卡（浅色 + 有彩度）与灰卡（中性浅灰）
     * 用的是两套色域。字色始终是"出现次数最多的深色像素"。
     */
    private fun sampleRect(
        pixels: androidx.compose.ui.graphics.PixelMap,
        width: Int,
        height: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        fillPredicate: (Color) -> Boolean = ::isLightFill,
    ): Band {
        val fills = HashMap<Int, Int>()
        val texts = HashMap<Int, Int>()
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(height)) {
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(width)) {
                val c = pixels[x, y]
                val r = (c.red * 255).toInt()
                val g = (c.green * 255).toInt()
                val b = (c.blue * 255).toInt()
                val mean = (r + g + b) / 3
                val key = (r shl 16) or (g shl 8) or b
                if (fillPredicate(c)) {
                    fills[key] = (fills[key] ?: 0) + 1
                } else if (mean <= 90) {
                    texts[key] = (texts[key] ?: 0) + 1
                }
            }
        }
        return Band(
            fill = fills.maxByOrNull { it.value }?.key?.let { Color(0xFF000000.toInt() or it) },
            text = texts.maxByOrNull { it.value }?.key?.let { Color(0xFF000000.toInt() or it) },
        )
    }

    private fun dumpShot(image: androidx.compose.ui.graphics.ImageBitmap, fileName: String, theme: String) {
        runCatching {
            val file = File(
                InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                fileName,
            )
            FileOutputStream(file).use { out ->
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            println("【周视图/$theme】截图写到 ${file.absolutePath}")
        }.onFailure { println("【周视图/$theme】截图没写成：${it.message}") }
    }

    /**
     * `-e shot true` 时停 25 秒：给外面的 `adb exec-out screencap -p` 一个拍**真屏幕**的窗口。
     *
     * 不带这个参数时直接返回 —— 正常跑测试不该白等 25 秒。
     */
    private fun shotHold(theme: String) {
        val shot = InstrumentationRegistry.getArguments().getString("shot") == "true"
        if (!shot) return
        println("【周视图/$theme】截图窗口：现在可以用 adb exec-out screencap 拍这一屏（25 秒）")
        Thread.sleep(25_000)
    }

    private fun argb(color: Color): Int =
        (((color.red * 255).toInt() and 0xFF) shl 16) or
            (((color.green * 255).toInt() and 0xFF) shl 8) or
            ((color.blue * 255).toInt() and 0xFF)

    private data class Band(val fill: Color?, val text: Color?)

    private companion object {
        /**
         * 一条行带里"主色"至少要占这么多像素才算一张卡片。
         *
         * 本机（1080×2424 / 420dpi）实测：一张卡片行带的主色有**几万**个像素，
         * 而表头"今天"那个蓝胶囊、周次滑块、底部「+ 添加」蓝字的抗锯齿边缘只有几百个 ——
         * 中间隔了两个数量级，所以这个门槛不敏感
         */
        const val MinCardBandPixels = 2000
    }
}
