package com.tof.manycourse

import com.tof.manycourse.data.MapAnchor
import com.tof.manycourse.data.MapPoint
import com.tof.manycourse.data.MapPointAccuracyHint
import com.tof.manycourse.data.buildCalibration
import com.tof.manycourse.data.calibrationResidualMeters
import com.tof.manycourse.data.imagePlacementOf
import com.tof.manycourse.data.isInsideImage
import com.tof.manycourse.data.matchPointForRoom
import com.tof.manycourse.data.normalizePlace
import com.tof.manycourse.data.projectToImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 地球半径（米）—— 和 `localEastNorth` 用同一个近似，测试里独立写一遍 */
private const val EARTH_RADIUS = 6_371_000.0

/**
 * 点位匹配与地图标定的回归测试（JVM，无需设备、不联网）。
 *
 * 这一层错了**不会崩**，只会让"今日课程"标在错误的教学楼上、或者干脆标不出来 ——
 * 属于最该被测住的那一类。两件事必须钉死：
 *  1. **教室名 → 点位** 的匹配规则（松了会串楼，紧了永远匹配不上）；
 *  2. **经纬度 → 图上位置** 的相似变换（正北朝上的图、以及旋转过的图都要对）。
 */
class MapPointTest {

    // ── 造一份"正北朝上"的合成标定 ────────────────────────────────────────
    // 图片范围按 4000 m 见方算，所以 1000 m = 0.25 个归一化单位。
    // 四个锚点对称摆在中心四周，于是锚点经纬度的平均值**正好**是中心 ——
    // 这样测试里的换算和 `localEastNorth` 的原点完全一致，残差应当是浮点级而不是"几十米以内"。

    private val centerLatitude = 23.0
    private val centerLongitude = 113.0
    private val metersToLatitudeDegrees = Math.toDegrees(1000.0 / EARTH_RADIUS)
    private val metersToLongitudeDegrees =
        Math.toDegrees(1000.0 / (EARTH_RADIUS * cos(Math.toRadians(centerLatitude))))

    private fun northUpAnchors(): List<MapAnchor> = listOf(
        MapAnchor("campus", "东", 0.75f, 0.5f, centerLatitude, centerLongitude + metersToLongitudeDegrees),
        MapAnchor("campus", "西", 0.25f, 0.5f, centerLatitude, centerLongitude - metersToLongitudeDegrees),
        MapAnchor("campus", "北", 0.5f, 0.25f, centerLatitude + metersToLatitudeDegrees, centerLongitude),
        MapAnchor("campus", "南", 0.5f, 0.75f, centerLatitude - metersToLatitudeDegrees, centerLongitude),
    )

    private fun point(name: String) = MapPoint(id = "id-$name", campusId = "campus", name = name)

    // ── 名称匹配 ──────────────────────────────────────────────────────────

    @Test
    fun normalizePlaceIgnoresCaseWhitespaceAndHyphens() {
        assertEquals("a1n403", normalizePlace(" A1N403 "))
        assertEquals("教学楼a101", normalizePlace("教学楼 A-101"))
        assertEquals("三教201", normalizePlace("三教　201")) // 全角空格
    }

    @Test
    fun roomMatchesPointNameInsideLongerRoomText() {
        // 课表里给的是「教室 + 校区」拼起来的串，点位名只是其中一段
        val points = listOf(point("A1N403"), point("三教"))

        assertEquals("A1N403", matchPointForRoom("A1N403 禄口", points)?.name)
        assertEquals("三教", matchPointForRoom("三教 A101", points)?.name)
    }

    @Test
    fun longestPointNameWins() {
        // 同时采了「三教」和「三教A101」时，具体的那一个必须赢，否则整栋楼都会标到同一个点
        val points = listOf(point("三教"), point("三教A101"))

        assertEquals("三教A101", matchPointForRoom("三教 A101", points)?.name)
        assertEquals("三教", matchPointForRoom("三教 门前广场", points)?.name)
    }

    @Test
    fun tooShortPointNameDoesNotSweepEverything() {
        // 一个字的点位名会把所有含它的教室都吃掉，而且用户完全看不出为什么
        assertNull(matchPointForRoom("A1N403", listOf(point("A"))))
    }

    @Test
    fun unmatchedOrEmptyRoomReturnsNull() {
        val points = listOf(point("三教"))
        assertNull(matchPointForRoom("实验楼B205", points))
        assertNull(matchPointForRoom("", points))
        assertNull(matchPointForRoom("   ", points))
    }

    // ── 标定与投影 ────────────────────────────────────────────────────────

    @Test
    fun northUpAnchorsCalibrateWithoutRotation() {
        val calibration = requireNotNull(buildCalibration(northUpAnchors()))

        assertEquals("正北朝上的图不该拟合出旋转", 0.0, calibration.transform.rotationRadians, 1e-6)
        assertEquals("缩放 = 0.25 个归一化单位 / 1000 米", 0.25 / 1000.0, calibration.transform.scale, 1e-12)
        assertEquals("原点取各锚点平均值", centerLatitude, calibration.originLatitude, 1e-9)
    }

    @Test
    fun projectionLandsWhereItShould() {
        val calibration = requireNotNull(buildCalibration(northUpAnchors()))

        val center = requireNotNull(projectToImage(centerLatitude, centerLongitude, calibration))
        assertEquals(0.5f, center.first, 1e-5f)
        assertEquals(0.5f, center.second, 1e-5f)

        // 中心往东 1000 米 → 图中心右侧 0.25
        val east = requireNotNull(
            projectToImage(centerLatitude, centerLongitude + metersToLongitudeDegrees, calibration)
        )
        assertEquals(0.75f, east.first, 1e-5f)
        assertEquals(0.5f, east.second, 1e-5f)

        // 中心往北 1000 米 → 图上要**往上**（y 变小），北在图的上面
        val north = requireNotNull(
            projectToImage(centerLatitude + metersToLatitudeDegrees, centerLongitude, calibration)
        )
        assertEquals(0.5f, north.first, 1e-5f)
        assertEquals("北必须对应更小的 y", 0.25f, north.second, 1e-5f)
    }

    @Test
    fun residualIsZeroForConsistentAnchors() {
        val calibration = requireNotNull(buildCalibration(northUpAnchors()))
        assertEquals(0.0, requireNotNull(calibrationResidualMeters(calibration)), 1e-6)
    }

    @Test
    fun rotatedCampusMapStillCalibrates() {
        // 手绘平面图常常不是正北朝上：把整张图的坐标转 90°（(x,y) → (1−y, x)），
        // 标定必须照样解得出来，而且要解成"带旋转"的那一支
        val rotated = northUpAnchors().map { it.copy(imageX = 1f - it.imageY, imageY = it.imageX) }
        val calibration = requireNotNull(buildCalibration(rotated))

        assertEquals(
            "转了 90° 的图，拟合出的旋转角正弦应当接近 ±1",
            1.0,
            abs(sin(calibration.transform.rotationRadians)),
            1e-6,
        )
        rotated.forEach { anchor ->
            val projected = requireNotNull(projectToImage(anchor.latitude, anchor.longitude, calibration))
            assertEquals(anchor.name, anchor.imageX, projected.first, 1e-5f)
            assertEquals(anchor.name, anchor.imageY, projected.second, 1e-5f)
        }
        assertEquals(0.0, requireNotNull(calibrationResidualMeters(calibration)), 1e-6)
    }

    @Test
    fun twoAnchorsAreEnoughAndMapMidpointsToMidpoints() {
        // 最小可用标定就是两个锚点（4 个参数、4 个方程）。相似变换是仿射的，
        // 所以两个锚点的**中点**必须落到两个图上位置的中点
        val anchors = listOf(northUpAnchors()[0], northUpAnchors()[2]) // 东 + 北
        val calibration = requireNotNull(buildCalibration(anchors))

        val meanLatitude = anchors.map { it.latitude }.average()
        val meanLongitude = anchors.map { it.longitude }.average()
        val midpoint = requireNotNull(projectToImage(meanLatitude, meanLongitude, calibration))

        assertEquals((0.75f + 0.5f) / 2f, midpoint.first, 1e-5f)
        assertEquals((0.5f + 0.25f) / 2f, midpoint.second, 1e-5f)
    }

    @Test
    fun tooFewOrCoincidentAnchorsYieldNullInsteadOfGarbage() {
        // 返回 null 是"还没标定"的正常表达 —— 页面据此如实说"落不到图上"。
        // 绝不能返回一个瞎凑的变换：那会把课标到图上的随机位置，比不画更糟
        assertNull(buildCalibration(emptyList()))
        assertNull(buildCalibration(northUpAnchors().take(1)))

        val anchor = northUpAnchors().first()
        assertNull(buildCalibration(listOf(anchor, anchor.copy(name = "同一个点采了两次"))))
    }

    @Test
    fun projectionWithoutCalibrationIsNull() {
        assertNull(projectToImage(centerLatitude, centerLongitude, null))
    }

    @Test
    fun imagePlacementPrefersStoredImagePosition() {
        // 图上采的点不需要任何标定就能画
        val onImage = point("三教").copy(imageX = 0.2f, imageY = 0.3f, latitude = 1.0, longitude = 2.0)
        val placement = requireNotNull(imagePlacementOf(onImage, null))
        assertEquals(0.2f, placement.first, 1e-6f)
        assertEquals(0.3f, placement.second, 1e-6f)
    }

    @Test
    fun gpsOnlyPointNeedsCalibration() {
        val gpsOnly = point("三教").copy(latitude = centerLatitude, longitude = centerLongitude)

        assertNull("没有标定就落不到图上", imagePlacementOf(gpsOnly, null))
        assertNotNull(imagePlacementOf(gpsOnly, buildCalibration(northUpAnchors())))
    }

    @Test
    fun insideImageChecksBothAxes() {
        assertTrue(isInsideImage(0f, 0f))
        assertTrue(isInsideImage(1f, 1f))
        assertFalse(isInsideImage(-0.01f, 0.5f))
        assertFalse(isInsideImage(0.5f, 1.01f))
    }

    @Test
    fun accuracyHintFallsBackToUnknown() {
        assertEquals(MapPointAccuracyHint.Good, MapPointAccuracyHint.of(8f))
        assertEquals(MapPointAccuracyHint.Fair, MapPointAccuracyHint.of(35f))
        assertEquals(MapPointAccuracyHint.Poor, MapPointAccuracyHint.of(300f))
        assertEquals("没有精度信息时不猜", MapPointAccuracyHint.Unknown, MapPointAccuracyHint.of(null))
    }
}
