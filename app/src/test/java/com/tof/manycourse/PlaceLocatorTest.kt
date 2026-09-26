package com.tof.manycourse

import com.tof.manycourse.data.MapAnchor
import com.tof.manycourse.data.MapPoint
import com.tof.manycourse.data.PlaceVerdict
import com.tof.manycourse.data.buildCalibration
import com.tof.manycourse.data.nearestPlaceByGround
import com.tof.manycourse.data.nearestPlaceByImage
import com.tof.manycourse.data.placeNoteOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** 地球半径（米）—— 和 `data/Geo.kt` 用同一个近似，测试里独立写一遍 */
private const val EARTH_RADIUS = 6_371_000.0

/**
 * **认楼（"我在哪栋楼"）的回归测试**（JVM，无需设备、不联网）。
 *
 * 这一层最该被钉住的是**"不敢说的时候别说"**：在"哪栋楼"这个问题上，
 * 报错的代价比说"分不出来"大得多 —— 用户会照着错误的方向走。
 *
 * 以及本条设计的核心主张：**GPS 认楼不经过地图投影**，所以标定错了也不影响
 * （见 [groundIsImmuneToCalibrationError]）—— 那是"能不能信这个功能"的根。
 */
class PlaceLocatorTest {

    // ── 合成一份"正北朝上、1000 m = 0.25 归一化单位"的地图 ─────────────────
    //
    // 和 MapPointTest 同一套路：四个锚点对称摆在中心四周，于是锚点经纬度的平均值
    // **正好是中心**，测试里的换算和 `localEastNorth` 的原点完全一致。
    // 由此 scale = 0.25 / 1000 = 2.5e-4（1 米对应多少归一化图长）。
    private val centerLatitude = 23.0
    private val centerLongitude = 113.0
    private val metersToLatitudeDegrees = Math.toDegrees(1000.0 / EARTH_RADIUS)
    private val metersToLongitudeDegrees =
        Math.toDegrees(1000.0 / (EARTH_RADIUS * cos(Math.toRadians(centerLatitude))))

    private fun anchors(eastShiftMeters: Double = 0.0): List<MapAnchor> {
        val longitudeShift = Math.toDegrees(eastShiftMeters / (EARTH_RADIUS * cos(Math.toRadians(centerLatitude))))
        return listOf(
            MapAnchor("campus", "东", 0.75f, 0.5f, centerLatitude, centerLongitude + metersToLongitudeDegrees + longitudeShift),
            MapAnchor("campus", "西", 0.25f, 0.5f, centerLatitude, centerLongitude - metersToLongitudeDegrees + longitudeShift),
            MapAnchor("campus", "北", 0.5f, 0.25f, centerLatitude + metersToLatitudeDegrees, centerLongitude + longitudeShift),
            MapAnchor("campus", "南", 0.5f, 0.75f, centerLatitude - metersToLatitudeDegrees, centerLongitude + longitudeShift),
        )
    }

    /** 图上一个点应处的位置：从中心往东 [eastMeters]、往北 [northMeters]（1000 m = 0.25 单位）*/
    private fun imageOf(eastMeters: Double, northMeters: Double): Pair<Float, Float> =
        (0.5 + eastMeters / 1000.0 * 0.25).toFloat() to (0.5 - northMeters / 1000.0 * 0.25).toFloat()

    private fun place(
        name: String,
        eastMeters: Double,
        northMeters: Double = 0.0,
        /** 图上位置：默认按"正北朝上的好标定"算出来；传 false = 只在原地采过（没有图上坐标）*/
        withImage: Boolean = true,
    ): MapPoint = MapPoint(
        id = name,
        campusId = "campus",
        name = name,
        latitude = centerLatitude + Math.toDegrees(northMeters / EARTH_RADIUS),
        longitude = centerLongitude +
            Math.toDegrees(eastMeters / (EARTH_RADIUS * cos(Math.toRadians(centerLatitude)))),
        imageX = if (withImage) imageOf(eastMeters, northMeters).first else null,
        imageY = if (withImage) imageOf(eastMeters, northMeters).second else null,
    )

    /** 从中心往东 [eastMeters] 的经纬度（模拟手机的位置）*/
    private fun groundAt(eastMeters: Double, northMeters: Double = 0.0): Pair<Double, Double> =
        (centerLatitude + Math.toDegrees(northMeters / EARTH_RADIUS)) to
            (centerLongitude + Math.toDegrees(eastMeters / (EARTH_RADIUS * cos(Math.toRadians(centerLatitude)))))

    // ── 按真实经纬度认楼（GPS 那条路）────────────────────────────────────────

    @Test
    fun groundPrefersTheNearestPlace() {
        val points = listOf(
            place("食堂", eastMeters = 400.0, northMeters = 0.0),
            place("图书馆", eastMeters = 0.0, northMeters = 0.0),
            place("宿舍", eastMeters = -600.0, northMeters = 0.0),
        )
        val (latitude, longitude) = groundAt(eastMeters = 25.0)

        val fix = requireNotNull(nearestPlaceByGround(latitude, longitude, points))
        assertEquals("图书馆", fix.point.name)
        assertEquals(25.0, fix.distanceMeters, 1.0)
        assertEquals(PlaceVerdict.Here, fix.verdict)
    }

    @Test
    fun groundIgnoresPointsWithoutCoordinates() {
        // "图上采的点"只有图上坐标、没有经纬度 —— 按真实距离认楼时它没法参与，
        // 而**不是**当成"在 0 米处"（那会让每一个图上采的点都变成"你就在这儿"）
        val points = listOf(
            MapPoint(id = "a", campusId = "campus", name = "只在图上采的", imageX = 0.5f, imageY = 0.5f),
            place("食堂", eastMeters = 80.0),
        )
        val (latitude, longitude) = groundAt(eastMeters = 0.0)

        val fix = requireNotNull(nearestPlaceByGround(latitude, longitude, points))
        assertEquals("食堂", fix.point.name)
        assertEquals(80.0, fix.distanceMeters, 1.0)
    }

    @Test
    fun groundReturnsNullWhenNothingIsCloseEnough() {
        // 最近的也在 400 m 外：这时候说"离你最近的是食堂"只是噪音（见 PLACE_MAX_METERS）
        val points = listOf(place("食堂", eastMeters = 400.0))
        val (latitude, longitude) = groundAt(eastMeters = 0.0)
        assertNull(nearestPlaceByGround(latitude, longitude, points))
    }

    @Test
    fun groundReturnsNullWhenNoPointHasCoordinates() {
        val points = listOf(
            MapPoint(id = "a", campusId = "campus", name = "图上点", imageX = 0.5f, imageY = 0.5f),
        )
        val (latitude, longitude) = groundAt(eastMeters = 0.0)
        assertNull(nearestPlaceByGround(latitude, longitude, points))
    }

    // ── "敢不敢说"：三种结论 ──────────────────────────────────────────────

    @Test
    fun nearestWhenTooFarToClaimYouAreThere() {
        // 100 m 外、且第二名远得多 —— 差得够清楚，但 100 m 已经不能说"就在这栋楼"
        val points = listOf(
            place("食堂", eastMeters = 100.0),
            place("图书馆", eastMeters = 600.0),
        )
        val (latitude, longitude) = groundAt(eastMeters = 0.0)

        val fix = requireNotNull(nearestPlaceByGround(latitude, longitude, points))
        assertEquals("食堂", fix.point.name)
        assertEquals(PlaceVerdict.Nearest, fix.verdict)
    }

    @Test
    fun ambiguousWhenTwoCandidatesAreEquallyClose() {
        // 站在两栋楼正中间（各 50 m）：这时候挑一个报出去就是错的
        val points = listOf(
            place("食堂", eastMeters = 100.0),
            place("图书馆", eastMeters = 0.0),
        )
        val (latitude, longitude) = groundAt(eastMeters = 50.0)

        val fix = requireNotNull(nearestPlaceByGround(latitude, longitude, points))
        assertEquals(PlaceVerdict.Ambiguous, fix.verdict)
        requireNotNull(fix.runnerUp)
        assertEquals(50.0, fix.distanceMeters, 1.0)
        assertEquals(50.0, fix.runnerUpMeters!!, 1.0)
    }

    @Test
    fun ambiguousTakesPrecedenceOverHere() {
        // 两个候选都进了 60 m 圈里且差不多近 —— 宁可说"可能是这两个"，
        // 也不因为"最近的那个 ≤60 m"就断言"就在这栋楼"
        val points = listOf(
            place("食堂", eastMeters = 40.0),
            place("图书馆", eastMeters = 0.0),
        )
        val (latitude, longitude) = groundAt(eastMeters = 20.0)

        val fix = requireNotNull(nearestPlaceByGround(latitude, longitude, points))
        assertEquals(PlaceVerdict.Ambiguous, fix.verdict)
    }

    @Test
    fun exactlyTheMarginIsEnoughToDecide() {
        // 边界：领先优势正好等于 PLACE_MARGIN_METERS(40) 时应当给出结论（用的是"小于"才算分不出来）
        // 站位选在离食堂 40 m 处：到图书馆 40 m、到食堂 0 m… 换个构型更清楚：
        // 食堂在 0 m，图书馆在 80 m，人站在食堂处 → 领先优势正好 80 - 0 = 80 ≥ 40
        val points = listOf(
            place("食堂", eastMeters = 0.0),
            place("图书馆", eastMeters = 80.0),
        )
        val (latitude, longitude) = groundAt(eastMeters = 0.0)

        val fix = requireNotNull(nearestPlaceByGround(latitude, longitude, points))
        assertEquals(PlaceVerdict.Here, fix.verdict)
    }

    // ── ★ 核心主张：认楼不吃标定误差 ───────────────────────────────────────

    @Test
    fun groundIsImmuneToCalibrationError() {
        // 构造一份**偏了 300 m** 的标定（模拟"锚点坐标采歪了/没刷新"那种脏数据）。
        // 两个地点都同时有"图上位置"和"真实经纬度"——这正是真实数据的样子：
        // 图上位置是人点的（准），经纬度是采的（可能有偏差）。
        val points = listOf(
            place("食堂", eastMeters = 0.0),
            place("图书馆", eastMeters = 400.0),
        )
        val (latitude, longitude) = groundAt(eastMeters = 30.0) // 站在食堂东边 30 m

        // ① GPS 这条路：直接比经纬度，**标定根本不参与** → 答案正确
        val byGround = requireNotNull(nearestPlaceByGround(latitude, longitude, points))
        assertEquals("食堂", byGround.point.name)
        assertEquals(30.0, byGround.distanceMeters, 1.0)
        assertEquals(PlaceVerdict.Here, byGround.verdict)

        // ② 图上那条路：手机位置要靠这份歪标定投影到图上，于是**被带偏**
        //    （手机被投到食堂东边 330 m 处 → 反而离 400 m 外的图书馆更近）
        //    把这条也钉住：它是"GPS 为什么不用图上那条路"的活证据，
        //    不是"偶发 bug"——将来谁想统一成图上距离，这条测试会当场拦住他。
        val wrongCalibration = requireNotNull(buildCalibration(anchors(eastShiftMeters = 300.0)))
        val wifiFix = requireNotNull(
            nearestPlaceByImage(
                imageX = imageOf(eastMeters = 330.0, northMeters = 0.0).first,
                imageY = 0.5f,
                points = points,
                calibration = wrongCalibration,
            ),
        )
        assertEquals("图书馆", wifiFix.point.name)
    }

    // ── 按图上距离认楼（Wi-Fi 指纹那条路）──────────────────────────────────

    @Test
    fun imageNeedsCalibration() {
        // 没有标定就没有"米"，也就无所谓远近 —— 如实返回 null，而不是拿图上单位当米报
        val points = listOf(place("食堂", eastMeters = 0.0))
        assertNull(nearestPlaceByImage(0.5f, 0.5f, points, calibration = null))
    }

    @Test
    fun imagePicksByImageDistanceAndReportsMeters() {
        val points = listOf(
            place("食堂", eastMeters = 0.0),
            place("图书馆", eastMeters = 1000.0),
        )
        val calibration = requireNotNull(buildCalibration(anchors()))

        // 站在食堂东边 40 m（1000 m = 0.25 单位 → 40 m = 0.01 单位）
        val fix = requireNotNull(
            nearestPlaceByImage(imageX = 0.51f, imageY = 0.5f, points = points, calibration = calibration),
        )
        assertEquals("食堂", fix.point.name)
        assertEquals(40.0, fix.distanceMeters, 1.0)
        assertEquals(PlaceVerdict.Here, fix.verdict)
    }

    @Test
    fun imageCanUsePointsThatOnlyHaveCoordinates() {
        // 只在原地采过的点位（没有图上坐标）也要能用：imagePlacementOf 会按标定把它投影出来
        val points = listOf(
            place("食堂", eastMeters = 0.0, withImage = false),
            place("图书馆", eastMeters = 1000.0, withImage = false),
        )
        val calibration = requireNotNull(buildCalibration(anchors()))

        val fix = requireNotNull(
            nearestPlaceByImage(imageX = 0.51f, imageY = 0.5f, points = points, calibration = calibration),
        )
        assertEquals("食堂", fix.point.name)
        assertEquals(40.0, fix.distanceMeters, 1.0)
    }

    @Test
    fun imageReturnsNullWhenNothingIsCloseEnough() {
        val points = listOf(place("食堂", eastMeters = 0.0))
        val calibration = requireNotNull(buildCalibration(anchors()))

        // 图上离了 0.1 单位 = 400 m
        assertNull(
            nearestPlaceByImage(imageX = 0.6f, imageY = 0.5f, points = points, calibration = calibration),
        )
    }

    // ── 那句话本身（措辞也要被钉住：说了"在"就必须真的近）──────────────────

    @Test
    fun noteSaysYouAreHereOnlyWhenYouReallyAre() {
        val points = listOf(place("食堂", eastMeters = 0.0), place("图书馆", eastMeters = 600.0))

        val (nearLatitude, nearLongitude) = groundAt(eastMeters = 12.0)
        val here = requireNotNull(nearestPlaceByGround(nearLatitude, nearLongitude, points))
        val hereNote = requireNotNull(placeNoteOf(here, hasPosition = true, hasPlaces = true))
        // 刻意**不钉**"约 N m"里的那个 N：距离是等距圆柱近似 + `formatWalkDistance` 取整，
        // 12 m 会印成"约 11 m"。这里要钉的是**措辞档位**（"在这儿" vs "离你最近的是"）
        assertTrue(hereNote, hereNote.startsWith("你在这儿：食堂（约 "))
        assertTrue(hereNote, hereNote.endsWith(" m）"))

        // 100 m 外那栋楼**不能**说成"你在这儿"——只能说"离你最近的是它"
        val (farLatitude, farLongitude) = groundAt(eastMeters = 100.0)
        val far = requireNotNull(nearestPlaceByGround(farLatitude, farLongitude, points))
        val farNote = requireNotNull(placeNoteOf(far, hasPosition = true, hasPlaces = true))
        assertTrue(farNote, farNote.startsWith("离你最近的是「食堂」（约 "))
        assertTrue(farNote, farNote.endsWith(" m）"))
    }

    @Test
    fun noteListsBothCandidatesWhenAmbiguous() {
        val points = listOf(place("食堂", eastMeters = 100.0), place("图书馆", eastMeters = 0.0))
        val (latitude, longitude) = groundAt(eastMeters = 50.0)
        val fix = requireNotNull(nearestPlaceByGround(latitude, longitude, points))

        val note = requireNotNull(placeNoteOf(fix, hasPosition = true, hasPlaces = true))
        assertTrue(note, note.contains("也可能是"))
        assertTrue(note, note.contains("分不出来"))
        assertTrue(note, note.contains("食堂") && note.contains("图书馆"))
    }

    @Test
    fun noteDowngradesWordingWhenTheFixIsOnlyWifi() {
        // Wi-Fi 那条不能跟着 GPS 一起说"你在这儿"：指纹库现在全是室外走出来的，
        // 室内拿它反查能出结果、但不该被当成结论
        val points = listOf(place("食堂", eastMeters = 0.0), place("图书馆", eastMeters = 600.0))
        val (latitude, longitude) = groundAt(eastMeters = 12.0)
        val fix = requireNotNull(nearestPlaceByGround(latitude, longitude, points))

        val note = requireNotNull(placeNoteOf(fix, hasPosition = true, hasPlaces = true, precise = false))
        assertTrue(note, note.startsWith("Wi-Fi 估算："))
        assertTrue(note, note.contains("大概在"))
        // 降档之后不能再出现"你在这儿"这个断言
        assertFalse(note, note.contains("你在这儿"))
    }

    @Test
    fun noteExplainsWhyItCannotTell() {
        // 没有位置：交给"为什么画不出蓝点"那句去解释，这句闭嘴（同一个原因不说两遍）
        assertNull(placeNoteOf(null, hasPosition = false, hasPlaces = true))
        assertNull(placeNoteOf(null, hasPosition = false, hasPlaces = false))

        // 有位置但没采过地点 vs 采过但都太远 —— 两句话的下一步不一样
        assertEquals(
            "认不出这是哪栋楼（这张图上还没有采过地点）",
            placeNoteOf(null, hasPosition = true, hasPlaces = false),
        )
        assertEquals(
            "认不出这是哪栋楼（离已知地点都太远）",
            placeNoteOf(null, hasPosition = true, hasPlaces = true),
        )
    }
}
