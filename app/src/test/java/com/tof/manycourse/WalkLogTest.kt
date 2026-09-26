package com.tof.manycourse

import com.tof.manycourse.data.DEFAULT_STEP_LENGTH_METERS
import com.tof.manycourse.data.MapAnchor
import com.tof.manycourse.data.WalkFixSource
import com.tof.manycourse.data.WalkLog
import com.tof.manycourse.data.WalkLogCodec
import com.tof.manycourse.data.WalkSample
import com.tof.manycourse.data.WifiAp
import com.tof.manycourse.data.advanceOnImage
import com.tof.manycourse.data.buildCalibration
import com.tof.manycourse.data.fuseWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** 地球半径（米）—— 与 `localEastNorth` 同一个近似，测试里独立写一遍 */
private const val EARTH_RADIUS = 6_371_000.0

/**
 * 轨迹采集的编解码与融合的回归测试（JVM，无需设备）。
 *
 * 这一层是"走几圈"能不能出数据的全部逻辑：
 *  - 编解码错了 → 走了一小时回来发现文件读不出来；
 *  - 融合错了 → 点位被标到隔壁楼，而且**看起来完全正常**（比不显示更糟）。
 */
class WalkLogTest {

    private val centerLatitude = 23.0
    private val centerLongitude = 113.0
    private val metersToLatitude = Math.toDegrees(1.0 / EARTH_RADIUS)
    private val metersToLongitude = Math.toDegrees(1.0 / (EARTH_RADIUS * cos(Math.toRadians(centerLatitude))))

    /** 正北朝上、1000 米 = 0.25 个归一化单位（和 `MapPointTest` 同一个合成标定）*/
    private val calibration = requireNotNull(
        buildCalibration(
            listOf(
                MapAnchor("campus", "东", 0.75f, 0.5f, centerLatitude, centerLongitude + 1000 * metersToLongitude),
                MapAnchor("campus", "西", 0.25f, 0.5f, centerLatitude, centerLongitude - 1000 * metersToLongitude),
                MapAnchor("campus", "北", 0.5f, 0.25f, centerLatitude + 1000 * metersToLatitude, centerLongitude),
                MapAnchor("campus", "南", 0.5f, 0.75f, centerLatitude - 1000 * metersToLatitude, centerLongitude),
            )
        )
    )

    private fun aps(prefix: String, count: Int = 4): List<WifiAp> =
        (1..count).map { WifiAp(bssid = "$prefix-$it", rssi = -40 - it * 5) }

    private fun origin(at: Long = 1_000L) =
        WalkSample.Origin(at, centerLatitude, centerLongitude, accuracyMeters = 5f)

    private fun steps(from: Long, to: Long, at: Long) =
        listOf(WalkSample.Step(at, from), WalkSample.Step(at + 1, to))

    private fun log(vararg samples: WalkSample) = WalkLog(
        schoolId = "gdut",
        campusId = "campus",
        startedAt = 1_000L,
        samples = samples.toList(),
    )

    // ── 编解码 ────────────────────────────────────────────────────────────

    @Test
    fun codecRoundTripsEverySampleKind() {
        val walk = log(
            origin(),
            WalkSample.Step(1_100L, 5_000L),
            WalkSample.Heading(1_150L, 45.5f),
            WalkSample.Gps(1_200L, 23.0001, 113.0002, 8f),
            WalkSample.Wifi(1_300L, aps("aa")),
            WalkSample.Tap(1_400L, "三教 A101", "东门进去右手边"),
        )

        assertEquals(walk, WalkLogCodec.decode(WalkLogCodec.encode(walk)))
    }

    @Test
    fun wifiScanWithSameTimestampIsOneSample() {
        // 一次扫描的多个 AP 是连续若干行 `w=`：解码必须归成**一条** Wifi 样本，
        // 否则每条 AP 都会变成一次"扫描"，指纹点数量直接虚高 4 倍
        val text = WalkLogCodec.encode(log(WalkSample.Wifi(2_000L, aps("aa", count = 5))))
        val decoded = requireNotNull(WalkLogCodec.decode(text))

        assertEquals(1, decoded.wifiScans.size)
        assertEquals(5, decoded.wifiScans.single().aps.size)
    }

    @Test
    fun twoScansStayTwoSamples() {
        val text = WalkLogCodec.encode(log(WalkSample.Wifi(2_000L, aps("aa")), WalkSample.Wifi(32_000L, aps("bb"))))
        val decoded = requireNotNull(WalkLogCodec.decode(text))

        assertEquals(2, decoded.wifiScans.size)
        assertEquals(2_000L, decoded.wifiScans[0].at)
        assertEquals(32_000L, decoded.wifiScans[1].at)
    }

    @Test
    fun brokenLinesAreSkippedNotTheWholeWalk() {
        val text = WalkLogCodec.encode(log(origin(), WalkSample.Tap(1_400L, "三教")))
            .replaceFirst("t=", "t=不是数字|")

        val decoded = WalkLogCodec.decode(text)
        assertEquals("坏行只丢那一行", 1, requireNotNull(decoded).samples.size)
    }

    @Test
    fun foreignHeaderIsRejectedWholesale() {
        assertNull(WalkLogCodec.decode(""))
        assertNull(WalkLogCodec.decode("manycourse-mappoints/1\np=x"))
        assertTrue(WalkLogCodec.hasOurHeader(WalkLogCodec.encode(log(origin()))))
        assertEquals("manycourse-walk/1", WalkLogCodec.HEADER)
    }

    @Test
    fun stepCountUsesRangeNotLastValue() {
        // 计步器给的是"开机以来总数"，中途重启会变小 —— 直接取最后一条会算出负数
        val walk = log(WalkSample.Step(1L, 9_000L), WalkSample.Step(2L, 9_120L), WalkSample.Step(3L, 50L))

        assertEquals(9_070L, walk.steps)
    }

    // ── 融合 ──────────────────────────────────────────────────────────────

    @Test
    fun gpsOriginPlacesTapsAtTheirRealPosition() {
        val walk = log(origin(), WalkSample.Tap(1_100L, "正门"))

        val fusion = fuseWalk(walk, calibration)
        val fix = fusion.fixes.single()

        assertEquals("正门", fix.name)
        assertEquals(0.5f, fix.imageX, 1e-4f)
        assertEquals(0.5f, fix.imageY, 1e-4f)
        assertEquals(WalkFixSource.Gps, fix.source)
        assertEquals("GPS 精度原样带出来", 5.0, fix.errorMeters ?: 0.0, 1e-6)
        assertEquals(centerLatitude, fix.latitude ?: 0.0, 1e-9)
        assertTrue(fusion.calibrated)
    }

    @Test
    fun pdrWalksNorthAndAccumulatesError() {
        // 起点在图中心 → 朝北走 1000 步（= 700 米）→ 打点应当落在中心正上方 0.175 个归一化单位，
        // 并且**误差随距离累积**（不是一直等于 GPS 的 5 米）
        val walk = log(
            origin(),
            WalkSample.Heading(1_050L, 0f),
            WalkSample.Step(1_100L, 1_000L),
            WalkSample.Step(1_200L, 1_500L),
            WalkSample.Step(1_300L, 2_000L),
            WalkSample.Tap(1_400L, "图书馆"),
        )

        val fix = fuseWalk(walk, calibration).fixes.single()
        val expectedMeters = 1000 * DEFAULT_STEP_LENGTH_METERS

        assertEquals(WalkFixSource.Pdr, fix.source)
        assertEquals(0.5f, fix.imageX, 1e-3f)
        assertEquals(
            "正北应当落在更小的 y 上",
            (0.5 - expectedMeters / 1000.0 * 0.25).toFloat(),
            fix.imageY,
            1e-3f,
        )
        assertEquals(5.0 + expectedMeters * 0.15, fix.errorMeters ?: 0.0, 1e-6)
        assertEquals("只有 PDR 时不该假装有经纬度", null, fix.latitude)
    }

    @Test
    fun headingEastMovesRightOnNorthUpImage() {
        val walk = log(
            origin(),
            WalkSample.Heading(1_050L, 90f), // 正东
            WalkSample.Step(1_100L, 1_000L),
            WalkSample.Step(1_200L, 2_000L),
            WalkSample.Tap(1_300L, "体育馆"),
        )

        val fix = fuseWalk(walk, calibration).fixes.single()

        assertTrue("正东应当落在更大的 x 上", fix.imageX > 0.5f)
        assertEquals(0.5f, fix.imageY, 1e-3f)
    }

    @Test
    fun advanceOnImageIsConsistentWithCalibration() {
        val north = advanceOnImage(0.5f to 0.5f, meters = 1000.0, headingDegrees = 0f, calibration = calibration)
        val east = advanceOnImage(0.5f to 0.5f, meters = 1000.0, headingDegrees = 90f, calibration = calibration)

        assertEquals(0.5f, north.first, 1e-4f)
        assertEquals(0.25f, north.second, 1e-4f)
        assertEquals(0.75f, east.first, 1e-4f)
        assertEquals(0.5f, east.second, 1e-4f)
    }

    @Test
    fun withoutCalibrationNothingCanBePlaced() {
        val walk = log(origin(), WalkSample.Tap(1_100L, "三教"))

        val fusion = fuseWalk(walk, calibration = null)

        assertTrue(fusion.fixes.isEmpty())
        assertEquals(listOf("三教"), fusion.unplaced)
        assertEquals(false, fusion.calibrated)
    }

    @Test
    fun tapsBeforeAnyFixAreReportedAsUnplaced() {
        // 室内开始、GPS 一直没拿到：名字照样记下来了，但位置给不出 —— 如实列出来让用户去图上点
        val walk = log(WalkSample.Tap(1_100L, "A101"), WalkSample.Tap(1_200L, "A102"))

        val fusion = fuseWalk(walk, calibration)

        assertEquals(listOf("A101", "A102"), fusion.unplaced)
        assertTrue(fusion.fixes.isEmpty())
    }

    @Test
    fun fingerprintsGetPositionsOnlyAfterThePositionIsKnown() {
        val walk = log(
            WalkSample.Wifi(900L, aps("before")), // 还没有位置：不存（存了也不知道它属于哪儿）
            origin(),
            WalkSample.Wifi(1_100L, aps("after")),
            WalkSample.Wifi(1_200L, aps("few", count = 2)), // AP 太少：没有区分度，不存
        )

        val fusion = fuseWalk(walk, calibration)

        assertEquals(1, fusion.fingerprints.size)
        val fingerprint = fusion.fingerprints.single()
        assertEquals("campus", fingerprint.campusId)
        assertEquals(0.5f, fingerprint.imageX, 1e-4f)
        assertEquals(4, fingerprint.aps.size)
    }
}
