package com.tof.manycourse

import com.tof.manycourse.data.WifiFingerprint
import com.tof.manycourse.data.WifiFingerprintCodec
import com.tof.manycourse.data.locateByFingerprint
import com.tof.manycourse.data.rssiDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wi-Fi 指纹库（编解码 + kNN 定位）的回归测试（JVM，无需设备）。
 *
 * 这一层错了的表现是"我在 A 栋，它说我在 B 栋"，而且**看起来很正常**（地图上就是个蓝点）。
 * 所以三件事必须钉住：指纹能不能原样存回来、像不像怎么算、"不像"时必须敢返回 null。
 */
class WifiFingerprintTest {

    private fun fingerprint(x: Float, y: Float, vararg aps: Pair<String, Int>) = WifiFingerprint(
        campusId = "campus",
        imageX = x,
        imageY = y,
        aps = aps.toMap(),
        at = 1_000L,
    )

    @Test
    fun codecRoundTrips() {
        val samples = listOf(
            fingerprint(0.25f, 0.75f, "aa:bb:cc:00:00:01" to -40, "aa:bb:cc:00:00:02" to -55),
            fingerprint(0.5f, 0.5f, "aa:bb:cc:00:00:03" to -70),
        )

        assertEquals(samples, WifiFingerprintCodec.decode(WifiFingerprintCodec.encode(samples)))
    }

    @Test
    fun codecDropsSamplesWithoutApsOrWithBadCampus() {
        val text = WifiFingerprintCodec.encode(
            listOf(
                fingerprint(0.5f, 0.5f),                       // 一个 AP 都没有：没有区分度
                fingerprint(0.5f, 0.5f, "aa:bb:cc:00:00:01" to -40),
                WifiFingerprint("", 0.5f, 0.5f, mapOf("x" to -40)),  // 校区为空
            )
        )

        assertEquals(1, requireNotNull(WifiFingerprintCodec.decode(text)).size)
    }

    @Test
    fun codecRejectsForeignHeader() {
        assertNull(WifiFingerprintCodec.decode(""))
        assertNull(WifiFingerprintCodec.decode("manycourse-walk/1\nh=x"))
        assertTrue(WifiFingerprintCodec.hasOurHeader(WifiFingerprintCodec.encode(emptyList())))
    }

    // ── 距离与 kNN ────────────────────────────────────────────────────────

    @Test
    fun identicalScanHasZeroDistance() {
        val scan = mapOf("a" to -40, "b" to -60, "c" to -80)

        assertEquals(0.0, rssiDistance(scan, scan) ?: -1.0, 1e-9)
    }

    @Test
    fun missingStrongApCountsAgainstTheMatch() {
        // "库里这条有个很强的 AP，而我完全没扫到它" —— 那是"我不在这儿"的强证据，
        // 只比共同 AP 的话会把楼东头判成楼西头
        val scan = mapOf("a" to -40, "b" to -60)
        val withExtraStrong = mapOf("a" to -40, "b" to -60, "c" to -30)

        val withoutExtra = rssiDistance(scan, mapOf("a" to -40, "b" to -60)) ?: -1.0
        val withExtra = rssiDistance(scan, withExtraStrong) ?: -1.0

        assertTrue("多出来的强 AP 必须让距离变大", withExtra > withoutExtra)
    }

    @Test
    fun fewerThanTwoSharedApsIsNotComparable() {
        // 返回 null 而不是"距离很大"：不可比和不可信是两件事
        assertNull(rssiDistance(mapOf("a" to -40), mapOf("a" to -40, "b" to -60)))
        assertNull(rssiDistance(mapOf("a" to -40, "b" to -50), mapOf("c" to -40, "d" to -50)))
    }

    @Test
    fun knnPicksTheNearestNeighbourhood() {
        val samples = listOf(
            fingerprint(0.2f, 0.2f, "a" to -40, "b" to -50, "c" to -60),
            fingerprint(0.3f, 0.2f, "a" to -45, "b" to -55, "c" to -65),
            fingerprint(0.8f, 0.8f, "d" to -40, "e" to -50, "f" to -60),
        )

        val fix = requireNotNull(locateByFingerprint(mapOf("a" to -41, "b" to -51, "c" to -61), samples))

        assertEquals("只有前两条有共同 AP", 2, fix.matched)
        assertTrue("应当落在两条候选之间", fix.imageX > 0.2f && fix.imageX < 0.3f)
        assertEquals(0.2f, fix.imageY, 1e-6f)
    }

    @Test
    fun knnReturnsNullWhenNothingIsComparable() {
        val samples = listOf(fingerprint(0.2f, 0.2f, "a" to -40, "b" to -50))

        // 只共同扫到一个 AP：不可比
        assertNull(locateByFingerprint(mapOf("a" to -40), samples))
        // 完全不同的 AP：不可比
        assertNull(locateByFingerprint(mapOf("x" to -40, "y" to -50), samples))
        // 空库 / 空扫描
        assertNull(locateByFingerprint(mapOf("a" to -40, "b" to -50), emptyList()))
        assertNull(locateByFingerprint(emptyMap(), samples))
    }

    @Test
    fun singleNeighbourHasZeroSpreadButIsFlaggedByMatchedCount() {
        // 只有一条候选时位置就是它（离散度为 0），但 `matched = 1` 让界面能说"只有一条参考，别太当真"
        val samples = listOf(fingerprint(0.42f, 0.17f, "a" to -40, "b" to -50))

        val fix = requireNotNull(locateByFingerprint(mapOf("a" to -41, "b" to -51), samples))

        assertEquals(0.42f, fix.imageX, 1e-6f)
        assertEquals(1, fix.matched)
        assertEquals(0.0f, fix.spread, 1e-6f)
        assertNotNull(fix)
    }
}
