package com.tof.manycourse.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * campus-schedule 设计系统图标（来源：ui_kits/app/index.html 内联 SVG）
 * 24x24 描边风格，strokeWidth=2，圆角端点；使用时通过 Icon(tint=…) 上色
 */
object AppIcons {

    private fun strokeIcon(vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = "AppIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Home: ImageVector by lazy {
        strokeIcon("M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 0 0 1 1h3m10-11l2 2m-2-2v10a1 1 0 0 1-1 1h-3m-6 0a1 1 0 0 0 1-1v-4a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v4a1 1 0 0 0 1 1m-6 0h6")
    }

    val Calendar: ImageVector by lazy {
        strokeIcon(
            "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
            "M16 2v4", "M8 2v4", "M3 10h18",
        )
    }

    val User: ImageVector by lazy {
        strokeIcon(
            "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2",
            "M12 3a4 4 0 1 1 0 8a4 4 0 1 1 0-8",
        )
    }

    val Bell: ImageVector by lazy {
        strokeIcon(
            "M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9",
            "M13.73 21a2 2 0 0 1-3.46 0",
        )
    }

    val Plus: ImageVector by lazy {
        strokeIcon("M12 5v14", "M5 12h14")
    }

    val Clock: ImageVector by lazy {
        strokeIcon(
            "M12 2a10 10 0 1 1 0 20a10 10 0 1 1 0-20",
            "M12 6v6l4 2",
        )
    }

    val MapPin: ImageVector by lazy {
        strokeIcon(
            "M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z",
            "M12 7a3 3 0 1 1 0 6a3 3 0 1 1 0-6",
        )
    }

    val BookOpen: ImageVector by lazy {
        strokeIcon(
            "M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z",
            "M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z",
        )
    }

    val ChevronLeft: ImageVector by lazy {
        strokeIcon("M15 18l-6-6 6-6")
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("M9 18l6-6-6-6")
    }

    val Close: ImageVector by lazy {
        strokeIcon("M18 6L6 18", "M6 6l12 12")
    }

    val Edit: ImageVector by lazy {
        strokeIcon("M17 3a2.83 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z")
    }

    /** 齿轮（设置入口）：外圈齿廓 + 内圆，Lucide 口径 */
    val Settings: ImageVector by lazy {
        strokeIcon(
            "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08" +
                "a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51" +
                "a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08" +
                "a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18" +
                "a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39" +
                "a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09" +
                "a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25" +
                "a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6",
        )
    }

    /** 复制（课程详情里"点一下复制课名/教师/教室"）：两张叠起来的圆角纸，Lucide 口径 */
    val Copy: ImageVector by lazy {
        strokeIcon(
            "M8 8h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z",
            "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
        )
    }
}
