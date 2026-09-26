package com.tof.manycourse

import com.tof.manycourse.data.MapAnchor
import com.tof.manycourse.data.MapPoint
import com.tof.manycourse.data.MapPointCodec
import com.tof.manycourse.data.MapPointFile
import com.tof.manycourse.data.anchorsToCsv
import com.tof.manycourse.data.pointsToCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 点位文件编解码的回归测试（JVM，无需设备、不联网）。
 *
 * 这份文件是**采集数据的唯一载体**：格式编错了，表现是"重启之后点位全没了"
 * 或者更隐蔽的"经纬度少了几位精度"（点位看着还在，但标到隔壁楼上）。
 * 所以往返必须逐字段相等，可选字段（图上采的点没有经纬度）必须原样保持"没有"。
 */
class MapPointCodecTest {

    private val gpsPoint = MapPoint(
        id = "gdut-1",
        campusId = "daxuecheng",
        name = "三教 A101",
        latitude = 23.0417332,
        longitude = 113.3873582,
        accuracyMeters = 8.5f,
        note = "侧门进去右手边",
        createdAt = 1_758_000_000_000L,
    )

    /** 图上采的点：没有经纬度，只有图上位置 */
    private val imagePoint = MapPoint(
        id = "gdut-2",
        campusId = "daxuecheng",
        name = "体育馆",
        imageX = 0.3125f,
        imageY = 0.75f,
        createdAt = 1_758_000_100_000L,
    )

    private val anchor = MapAnchor(
        campusId = "daxuecheng",
        name = "正门",
        imageX = 0.5f,
        imageY = 0.92f,
        latitude = 23.0410,
        longitude = 113.3865,
    )

    private val file = MapPointFile(listOf(gpsPoint, imagePoint), listOf(anchor))

    @Test
    fun roundTripPreservesEverything() {
        val decoded = MapPointCodec.decode(MapPointCodec.encode(file))

        assertEquals(file, decoded)
    }

    @Test
    fun missingOptionalValuesStayMissing() {
        // ★ 关键：图上采的点**没有**经纬度，解码后必须还是 null，
        //   不能被写成 0.0（0.0 是几内亚湾，会把它标到地球另一端）
        val decoded = requireNotNull(MapPointCodec.decode(MapPointCodec.encode(file)))
        val restoredImagePoint = decoded.points.single { it.id == "gdut-2" }

        assertNull(restoredImagePoint.latitude)
        assertNull(restoredImagePoint.longitude)
        assertNull(restoredImagePoint.accuracyMeters)
        assertEquals(0.3125f, restoredImagePoint.imageX!!, 1e-7f)
        assertEquals(0.75f, restoredImagePoint.imageY!!, 1e-7f)
        assertFalse(restoredImagePoint.hasGps)
        assertTrue(restoredImagePoint.hasImage)
    }

    @Test
    fun coordinatesKeepFullPrecision() {
        // 经纬度少一位小数 ≈ 10 米：点位看着还在，但已经标到隔壁楼上
        val decoded = requireNotNull(MapPointCodec.decode(MapPointCodec.encode(file)))
        val restored = decoded.points.single { it.id == "gdut-1" }

        assertEquals(gpsPoint.latitude, restored.latitude)
        assertEquals(gpsPoint.longitude, restored.longitude)
        assertEquals(gpsPoint.accuracyMeters, restored.accuracyMeters)
    }

    @Test
    fun separatorsAndCjkInNamesSurvive() {
        val tricky = gpsPoint.copy(
            name = "三教|A101\n（东侧）",
            note = "备注里有 | 和换行\n第二行",
        )
        val decoded = requireNotNull(
            MapPointCodec.decode(MapPointCodec.encode(MapPointFile(listOf(tricky), emptyList())))
        )

        assertEquals(tricky.name, decoded.points.single().name)
        assertEquals(tricky.note, decoded.points.single().note)
    }

    @Test
    fun brokenLineIsSkippedNotTheWholeFile() {
        val text = MapPointCodec.encode(file).replaceFirst("p=", "p=坏了|字段|不够")
        val decoded = MapPointCodec.decode(text)

        assertEquals("坏行只丢那一行", 1, requireNotNull(decoded).points.size)
        assertEquals("锚点不受影响", 1, decoded.anchors.size)
    }

    @Test
    fun foreignHeaderIsRejectedWholesale() {
        assertNull(MapPointCodec.decode(""))
        assertNull(MapPointCodec.decode("这不是点位文件"))
        assertNull(MapPointCodec.decode("manycourse-schedule/1\nschoolId=x"))
        assertTrue(MapPointCodec.hasOurHeader(MapPointCodec.encode(file)))
        assertEquals("manycourse-mappoints/1", MapPointCodec.HEADER)
    }

    @Test
    fun pointWithoutIdOrCampusIsDropped() {
        // 没有 id 就删不掉、没有校区就不知道该画在哪张图 —— 这种点位留着只会添乱
        val text = MapPointCodec.encode(
            MapPointFile(listOf(gpsPoint.copy(id = ""), gpsPoint.copy(campusId = "")), emptyList())
        )
        assertEquals(0, requireNotNull(MapPointCodec.decode(text)).points.size)
    }

    @Test
    fun csvEscapesCommasQuotesAndNewlines() {
        val point = gpsPoint.copy(name = "三教, \"东\" 侧", note = "第一行\n第二行")
        val csv = pointsToCsv(listOf(point), ZoneId.of("Asia/Shanghai"))
        val dataLine = csv.lineSequence().toList()[1]

        // 逗号必须被引号包住，否则 Excel 打开就是错位的
        assertTrue(dataLine.contains("\"三教, \"\"东\"\" 侧\""))
        // 表头固定，列数不能变
        assertEquals(
            "campus_id,name,latitude,longitude,accuracy_m,image_x,image_y,note,collected_at",
            csv.lineSequence().first(),
        )
    }

    @Test
    fun csvFormatsTimeInGivenZone() {
        val point = gpsPoint.copy(createdAt = 1_758_000_000_000L)
        val csv = pointsToCsv(listOf(point), ZoneId.of("Asia/Shanghai"))

        // 时间列是给人看的，必须带时区算；算错时区在导出的表里几乎看不出来
        assertTrue(csv.contains("2025-09-16"))
    }

    @Test
    fun anchorsCsvHasItsOwnHeaderAndRows() {
        val csv = anchorsToCsv(listOf(anchor))

        assertEquals(
            "campus_id,name,image_x,image_y,latitude,longitude",
            csv.lineSequence().first(),
        )
        assertTrue(csv.contains("正门,0.5,0.92,23.041,113.3865"))
    }
}
