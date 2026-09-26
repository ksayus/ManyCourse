package com.tof.manycourse

import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CoursePlacement
import com.tof.manycourse.data.MapAnchor
import com.tof.manycourse.data.MapPoint
import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.data.buildCalibration
import com.tof.manycourse.data.placeCoursesOnMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「今日课程 → 地图」归类的回归测试（JVM，无需设备）。
 *
 * 要钉住的是**四类结果不能互相混**：能画的、还没采点位的、有点位但没标定的、在别的校区的 ——
 * 混了以后用户看到的是一句"有 3 门课没显示"，而不知道该出门采点、该在图上点两下标定、
 * 还是该切校区。三种下一步的成本差一个数量级。
 */
class CourseMapPlacementTest {

    private val campusId = "daxuecheng"

    /** 两个锚点就够解出标定（4 参数 4 方程）；这里造一份"正北朝上、1 km 约 0.25 个归一化单位"的 */
    private val calibration = requireNotNull(
        buildCalibration(
            listOf(
                MapAnchor(campusId, "A", 0.5f, 0.5f, 23.0, 113.0),
                MapAnchor(campusId, "B", 0.75f, 0.5f, 23.0, 113.01),
            )
        )
    )

    private fun course(name: String, room: String): CourseEntry = CourseEntry.Week(
        SchoolCourse(
            name = name,
            teacher = "老师",
            room = room,
            weekday = 1,
            startPeriod = 1,
            periodCount = 2,
        )
    )

    private fun imagePoint(name: String, x: Float, y: Float) = MapPoint(
        id = "id-$name",
        campusId = campusId,
        name = name,
        imageX = x,
        imageY = y,
    )

    @Test
    fun matchedPointOnThisCampusIsPlacedOnMap() {
        val entries = listOf(course("高等数学", "三教 A101"))
        val points = listOf(imagePoint("三教A101", 0.4f, 0.6f))

        val placement = placeCoursesOnMap(entries, points, calibration, campusId).single()

        assertTrue(placement is CoursePlacement.OnMap)
        val onMap = placement as CoursePlacement.OnMap
        assertEquals(0.4f, onMap.imageX, 1e-6f)
        assertEquals(0.6f, onMap.imageY, 1e-6f)
        assertEquals("三教A101", onMap.pointName)
    }

    @Test
    fun gpsPointIsPlacedOnceCampusIsCalibrated() {
        // 站在教室里采的点（只有经纬度）：标定好了就该落到图上
        val entries = listOf(course("大学英语", "图书馆 302"))
        val points = listOf(
            MapPoint(
                id = "id-lib",
                campusId = campusId,
                name = "图书馆",
                latitude = 23.0,
                longitude = 113.0,
            )
        )

        val placement = placeCoursesOnMap(entries, points, calibration, campusId).single()

        assertTrue(placement is CoursePlacement.OnMap)
        // 锚点 A 就是 (23.0, 113.0) → (0.5, 0.5)
        assertEquals(0.5f, (placement as CoursePlacement.OnMap).imageX, 1e-4f)
        assertEquals(0.5f, placement.imageY, 1e-4f)
    }

    @Test
    fun gpsPointWithoutCalibrationIsReportedSeparately() {
        // 和"没采点位"必须分开：这个只要在图上点两个锚点就好了，不用出门
        val entries = listOf(course("大学英语", "图书馆 302"))
        val points = listOf(
            MapPoint(
                id = "id-lib",
                campusId = campusId,
                name = "图书馆",
                latitude = 23.0,
                longitude = 113.0,
            )
        )

        val placement = placeCoursesOnMap(entries, points, calibration = null, campusId = campusId).single()

        assertTrue(placement is CoursePlacement.OffImage)
        assertEquals("图书馆", (placement as CoursePlacement.OffImage).pointName)
    }

    @Test
    fun pointOutsideImageIsReportedAsOffImage() {
        val entries = listOf(course("体育", "体育馆"))
        val points = listOf(imagePoint("体育馆", 1.4f, 0.5f))

        val placement = placeCoursesOnMap(entries, points, calibration, campusId).single()

        assertTrue("投影到图片范围之外要如实说，不能夹到边上假装在图里", placement is CoursePlacement.OffImage)
    }

    @Test
    fun missingPointIsReportedAsNotCollected() {
        val entries = listOf(course("物理实验", "实验楼 B205"))

        val placement = placeCoursesOnMap(entries, emptyList(), calibration, campusId).single()

        assertTrue(placement is CoursePlacement.NotCollected)
        assertEquals("实验楼 B205", placement.entry.room)
    }

    @Test
    fun pointOnAnotherCampusIsReportedAsOtherCampus() {
        val entries = listOf(course("金工实习", "工程训练中心"))
        val points = listOf(imagePoint("工程训练中心", 0.3f, 0.3f).copy(campusId = "dongfenglu"))

        val placement = placeCoursesOnMap(entries, points, calibration, campusId).single()

        assertTrue(placement is CoursePlacement.OtherCampus)
        assertEquals("dongfenglu", (placement as CoursePlacement.OtherCampus).campusId)
    }

    @Test
    fun mixedTimetableIsClassifiedPerEntry() {
        val entries = listOf(
            course("高等数学", "三教 A101"),      // 有图
            course("大学物理", "四教 B202"),      // 没采
            course("金工实习", "工程训练中心"),    // 别的校区
        )
        val points = listOf(
            imagePoint("三教A101", 0.4f, 0.6f),
            imagePoint("工程训练中心", 0.3f, 0.3f).copy(campusId = "longdong"),
        )

        val placements = placeCoursesOnMap(entries, points, calibration, campusId)

        assertEquals(3, placements.size)
        assertTrue(placements[0] is CoursePlacement.OnMap)
        assertTrue(placements[1] is CoursePlacement.NotCollected)
        assertTrue(placements[2] is CoursePlacement.OtherCampus)
    }

    @Test
    fun emptyTimetableYieldsEmptyPlacements() {
        assertTrue(placeCoursesOnMap(emptyList(), emptyList(), calibration, campusId).isEmpty())
    }
}
