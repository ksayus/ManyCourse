package com.tof.manycourse.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.WeekdayLabels
import com.tof.manycourse.data.Timetable

/**
 * **把课表画成一张图片**（分享 / 存相册用）。
 *
 * ## 为什么用 `Canvas` 直接画，而不是截屏
 *
 * 截当前屏幕听起来省事，但截出来的东西**取决于用户当时的样子**：他翻到第 3 周、
 * 字号调到最大、或者正开着某个浮层，导出的就是那一屏 —— 而且周视图在小屏上是要滚动的，
 * 截屏只能拿到可见的那一半课。所以这里按"整张课表"重画一遍：
 * 尺寸固定、内容完整、跟屏幕状态无关。
 *
 * ## 为什么放在 `ui/` 而不是 `data/`
 *
 * 它依赖 `android.graphics` / `StaticLayout`（画字、换行都是 Android 的活儿），
 * 在 JVM 单测里跑不起来 —— 放 `data/` 会污染那一层的可测性。
 * 真正的取值口径（哪门课、几点、第几周）仍然全部来自 `data/`。
 */
object TimetableImageWriter {

    private const val WIDTH = 1080
    /**
     * 表头高度。
     *
     * ★ 为什么是 200 而不是 150：副标题（「第7周 10-12 ~ 10-18 · 共 19 门课」）画在 y=116，
     * 而星期表头（周一…周日）画在靠近网格的上沿。150 时两者只差 10px —— 真机上直接**压字**，
     * 副标题被星期几的笔画插花（实测截图可见）。留出 200 让三层（标题 / 副标题 / 星期）各有各的带。
     */
    private const val HEADER_HEIGHT = 200
    private const val FOOTER_HEIGHT = 90
    private const val ROW_HEIGHT = 168
    private const val AXIS_WIDTH = 132
    private const val PADDING = 24f

    private const val BACKGROUND = 0xFFF7FAFF.toInt()
    private const val CARD = 0xFFFFFFFF.toInt()
    private const val LINE = 0xFFD8E2F0.toInt()
    private const val TITLE = 0xFF0D1B2A.toInt()
    private const val SUBTITLE = 0xFF5B6B7F.toInt()
    private const val AXIS_TEXT = 0xFF44566C.toInt()
    private const val ROOM_TEXT = 0xFF2E6DB4.toInt()

    /**
     * 画一张整学期课表。
     *
     * @param courses 要画的课（调用方决定是全部还是某一周）
     * @param title   图片左上角的标题（如「本周课表」）
     * @param subtitle 标题下面那行小字（如「第7周 10-12 ~ 10-18 · 共 3 门课」）
     */
    fun render(
        courses: List<Course>,
        timetable: Timetable,
        title: String,
        subtitle: String,
    ): Bitmap {
        // 只画"真的排过课"的行：整学期都没课的行画出来是一排空格子，纯浪费
        val usedRows = courses.map { timetable.blockIndexOf(it.startPeriod) }
        val rowCount = (usedRows.maxOrNull()?.plus(1) ?: 1).coerceIn(1, timetable.blockCount)

        val columnWidth = (WIDTH - AXIS_WIDTH) / 7f
        val height = HEADER_HEIGHT + rowCount * ROW_HEIGHT + FOOTER_HEIGHT

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)

        drawHeader(canvas, title, subtitle)
        drawGrid(canvas, rowCount, columnWidth)
        drawAxis(canvas, timetable, rowCount)
        drawCourses(canvas, courses, timetable, rowCount, columnWidth)
        drawFooter(canvas, courses.size)

        return bitmap
    }

    private fun drawHeader(canvas: Canvas, title: String, subtitle: String) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TITLE
            textSize = 46f
            isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SUBTITLE
            textSize = 26f
        }
        canvas.drawText(title, PADDING * 2, 72f, titlePaint)
        canvas.drawText(subtitle, PADDING * 2, 116f, subPaint)
    }

    /** 网格线 + 星期表头 */
    private fun drawGrid(canvas: Canvas, rowCount: Int, columnWidth: Float) {
        val line = Paint().apply {
            color = LINE
            strokeWidth = 2f
        }
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AXIS_TEXT
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        for (index in 0 until 7) {
            val center = AXIS_WIDTH + columnWidth * (index + 0.5f)
            canvas.drawText(WeekdayLabels[index], center, HEADER_HEIGHT - 18f, headerPaint)
        }

        val bottom = HEADER_HEIGHT + rowCount * ROW_HEIGHT
        for (row in 0..rowCount) {
            val y = (HEADER_HEIGHT + row * ROW_HEIGHT).toFloat()
            canvas.drawLine(0f, y, WIDTH.toFloat(), y, line)
        }
        canvas.drawLine(0f, HEADER_HEIGHT - 52f, WIDTH.toFloat(), HEADER_HEIGHT - 52f, line)
        for (column in 0..7) {
            val x = AXIS_WIDTH + columnWidth * column
            canvas.drawLine(x, HEADER_HEIGHT - 52f, x, bottom.toFloat(), line)
        }
        canvas.drawLine(0f, bottom.toFloat(), WIDTH.toFloat(), bottom.toFloat(), line)
    }

    /** 左侧时间轴：`9:00` / `第1-2节` / `10:20` 三行（没有作息时只画中间那行）*/
    private fun drawAxis(canvas: Canvas, timetable: Timetable, rowCount: Int) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f }

        for (row in 0 until rowCount) {
            val firstPeriod = row * CourseRepository.PERIODS_PER_BLOCK + 1
            val label = "第$firstPeriod-${firstPeriod + 1}节"
            val start = timetable.startOfBlock(row)
            val end = timetable.endOfBlock(row)

            // 三行里只有中间那行是"节次"，它要加重 —— 用显式标记，不靠 indexOf(文本)：
            // 万一起止时刻与节次文字碰巧相同，indexOf 会指错行
            val lines = buildList {
                if (start.isNotBlank()) add(start to false)
                add(label to true)
                if (end.isNotBlank()) add(end to false)
            }

            val top = HEADER_HEIGHT + row * ROW_HEIGHT
            var y = top + (ROW_HEIGHT - lines.size * 28f) / 2f
            lines.forEach { (text, emphasized) ->
                paint.color = if (emphasized) TITLE else AXIS_TEXT
                paint.isFakeBoldText = emphasized
                canvas.drawText(text, PADDING, y + 22f, paint)
                y += 28f
            }
        }
    }

    /** 每门课一张圆角卡片：左侧色条 + 课程名（最多两行）+ 教室 */
    private fun drawCourses(
        canvas: Canvas,
        courses: List<Course>,
        timetable: Timetable,
        rowCount: Int,
        columnWidth: Float,
    ) {
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TITLE
            textSize = 26f
            isFakeBoldText = true
        }
        val roomPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ROOM_TEXT
            textSize = 22f
        }

        courses.forEach { course ->
            val row = timetable.blockIndexOf(course.startPeriod)
            if (row >= rowCount) return@forEach

            val left = AXIS_WIDTH + columnWidth * (course.weekday - 1) + 6f
            val right = left + columnWidth - 12f
            val top = HEADER_HEIGHT + row * ROW_HEIGHT + 8f
            val bottom = top + ROW_HEIGHT - 16f

            canvas.drawRoundRect(RectF(left, top, right, bottom), 16f, 16f, card)

            // 左侧色条：与课表页同一个"按名字取色"的口径，两处颜色对得上
            val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = CourseRepository.colorOfName(course.name).toArgb()
            }
            canvas.drawRoundRect(RectF(left, top, left + 10f, bottom), 5f, 5f, stripe)

            val textWidth = (right - left - 34f).toInt().coerceAtLeast(40)
            val layout = StaticLayout.Builder
                .obtain(course.name, 0, course.name.length, namePaint, textWidth)
                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setLineSpacing(4f, 1f)
                .build()

            canvas.save()
            canvas.translate(left + 22f, top + 18f)
            layout.draw(canvas)
            canvas.restore()

            if (course.room.isNotBlank()) {
                val roomLayout = StaticLayout.Builder
                    .obtain(course.room, 0, course.room.length, roomPaint, textWidth)
                    .setMaxLines(1)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build()
                canvas.save()
                canvas.translate(left + 22f, bottom - 34f)
                roomLayout.draw(canvas)
                canvas.restore()
            }
        }
    }

    private fun drawFooter(canvas: Canvas, courseCount: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SUBTITLE
            textSize = 24f
        }
        val y = HEADER_HEIGHT + (canvas.height - HEADER_HEIGHT - FOOTER_HEIGHT) + 56f
        canvas.drawText("由 ManyCourse 课多多导出 · 共 $courseCount 门课", PADDING * 2, y, paint)
    }
}
