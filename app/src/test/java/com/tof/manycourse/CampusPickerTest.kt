package com.tof.manycourse

import com.tof.manycourse.data.CampusPick
import com.tof.manycourse.data.DeviceLocation
import com.tof.manycourse.data.SchoolCampus
import com.tof.manycourse.data.SchoolMap
import com.tof.manycourse.data.distanceMeters
import com.tof.manycourse.data.pickCampus
import com.tof.manycourse.ui.LocationAction
import com.tof.manycourse.ui.formatDistanceMeters
import com.tof.manycourse.ui.locationHintOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **"按定位挑校区"** 的回归测试（JVM：不碰定位、不碰 UI，全是纯函数）。
 *
 * 为什么值得单测：这条链路上的错法**全都不崩**，只表现为"显示了一张不对的地图"，
 * 而正确的图长什么样只有用户自己知道：
 *
 * | 错法 | 用户看到什么 |
 * |---|---|
 * | 最近校区算错（或坐标抄重复） | 进了东风路校区，显示的却是大学城的图 |
 * | 手选被下一次定位覆盖 | 手动点了校区，过一会儿自己跳回去 |
 * | 换学校没清手选 | 从广工切到广软，页面变空白（拿广工的校区 id 去查广软） |
 * | 兜底不是主校区 | 没授权定位时显示一张随机的图（列表顺序一变就换图）|
 *
 * 对应实现：`data/SchoolMap.kt`（校区表 + 距离）、`data/CampusPicker.kt`（挑哪个校区）、
 * `ui/MapScreen.kt` 的 `locationHintOf` / `formatDistanceMeters`（那一行说明）。
 */
class CampusPickerTest {

    // ── 地理计算：先钉住"距离算得对"，否则后面全是空中楼阁 ──────────────────

    @Test
    fun distanceIsZeroForTheSamePointAndSaneBetweenCampuses() {
        assertEquals(0.0, distanceMeters(23.0417, 113.3874, 23.0417, 113.3874), 0.001)

        // 广州天河一带的 1 公里量级：纬度 0.009° ≈ 1 km
        val oneKm = distanceMeters(23.0, 113.0, 23.009, 113.0)
        assertTrue("纬度 0.009° 应该在 1 km 上下，实际 $oneKm", oneKm in 950.0..1050.0)

        // 大学城 ↔ 番禺（两个都在番禺区，是最容易认错的一对）≈ 9.3 km
        val daxuechengToPanyu = distanceMeters(23.0417332, 113.3873582, 22.9774866, 113.3284096)
        assertTrue("大学城↔番禺约 9 km，实际 $daxuechengToPanyu", daxuechengToPanyu in 8_000.0..11_000.0)

        // 大学城 ↔ 揭阳 ≈ 300 km（跨市，用来确认量级没算错一个数量级）
        val daxuechengToJieyang = distanceMeters(23.0417332, 113.3873582, 22.9827281, 116.3213657)
        assertTrue("大学城↔揭阳约 300 km，实际 $daxuechengToJieyang", daxuechengToJieyang in 250_000.0..350_000.0)
    }

    // ── 挑校区 ────────────────────────────────────────────────────────────

    @Test
    fun standingOnACampusPicksThatCampus() {
        // ★ 这条是"按定位挑图"最核心的保证：站在哪个校区里，就显示哪个校区 ——
        //   **每所学校的每个校区**逐个过一遍（坐标取的就是表里那份，所以距离为 0）：
        //   广工 5 个、广软 2 个（广州 / 江门）、金城 1 个
        SchoolMap.schoolIds.forEach { schoolId ->
            val campuses = SchoolMap.campusesOf(schoolId)
            campuses.forEach { campus ->
                val pick = pickCampus(
                    campuses = campuses,
                    manualCampusId = null,
                    location = DeviceLocation(campus.latitude, campus.longitude),
                )
                assertTrue("站在「${campus.name}」里却选了 $pick", pick is CampusPick.ByLocation)
                assertEquals(campus.id, (pick as CampusPick.ByLocation).campus.id)
                assertTrue("距离为 0 必须算\"就在校区里\"", pick.nearby)
            }
        }
    }

    @Test
    fun picksBetweenTheTwoGzusCampusesAcrossCities() {
        // 广软是"双校区运营"：广州校区在从化太平（地铁 14 号线太平站），
        // 江门校区在新会双水 —— 两地隔着一百多公里，定位必须能分开
        val campuses = SchoolMap.campusesOf("gzus")
        assertEquals(listOf("guangzhou", "jiangmen"), campuses.map { it.id })

        val gap = distanceMeters(
            campuses[0].latitude, campuses[0].longitude,
            campuses[1].latitude, campuses[1].longitude,
        )
        assertTrue("广州校区↔江门校区约 130 km，实际 $gap", gap in 100_000.0..160_000.0)

        // 人在从化（校区所在的太平镇一带）→ 广州校区那张
        val atConghua = pickCampus(campuses, null, DeviceLocation(23.5500, 113.6000))
        assertEquals("guangzhou", (atConghua as CampusPick.ByLocation).campus.id)

        // 人在新会（江门校区所在的双水镇一带）→ 江门校区那张
        val atXinhui = pickCampus(campuses, null, DeviceLocation(22.5200, 113.0300))
        assertEquals("jiangmen", (atXinhui as CampusPick.ByLocation).campus.id)
    }

    @Test
    fun aRealWorldFixNearACampusPicksTheRightOne() {
        val campuses = SchoolMap.campusesOf("gdut")

        // 东风路校区外面几百米（区庄一带）
        val nearDongfenglu = pickCampus(campuses, null, DeviceLocation(23.1400, 113.2900))
        assertEquals("dongfenglu", (nearDongfenglu as CampusPick.ByLocation).campus.id)

        // ★ 最容易错的一对：番禺校区旁边（大学城也在番禺区，但隔了 9 km）
        val nearPanyu = pickCampus(campuses, null, DeviceLocation(22.9800, 113.3300))
        assertEquals("panyu", (nearPanyu as CampusPick.ByLocation).campus.id)

        // 揭阳校区外面（跨市，说明"最近"是按真实距离算的，不是就近挑个广州的）
        val nearJieyang = pickCampus(campuses, null, DeviceLocation(22.9800, 116.3000))
        assertEquals("jieyang", (nearJieyang as CampusPick.ByLocation).campus.id)
    }

    @Test
    fun nearbyFlagSeparatesOnCampusFromElsewhereInTheCity() {
        val campuses = SchoolMap.campusesOf("gdut")
        val daxuecheng = campuses.first { it.id == "daxuecheng" }

        // 校区内（偏 ~300 m）→ 敢说"已按定位选…"
        val inside = pickCampus(campuses, null, DeviceLocation(daxuecheng.latitude + 0.003, daxuecheng.longitude))
        assertTrue((inside as CampusPick.ByLocation).nearby)

        // 市区里离它 30 km 开外（还是在广东）→ 只能说"最近的是…"，
        // 因为"你在广州塔下"和"你在大学城"是两件事，不能混成一句"已按定位选"
        val far = pickCampus(campuses, null, DeviceLocation(23.1065, 113.3245))
        assertFalse((far as CampusPick.ByLocation).nearby)
    }

    @Test
    fun manualChoiceBeatsLocation() {
        val campuses = SchoolMap.campusesOf("gdut")
        // 人在大学城，但手动选了揭阳 —— 用户说了算（定位会飘，用户在哪个校区只有自己知道）
        val pick = pickCampus(campuses, manualCampusId = "jieyang", location = DeviceLocation(23.0417, 113.3874))
        assertTrue(pick is CampusPick.Manual)
        assertEquals("jieyang", pick.campus?.id)
    }

    @Test
    fun manualChoiceFromAnotherSchoolIsIgnoredInsteadOfBlankingThePage() {
        val gzus = SchoolMap.campusesOf("gzus")
        // 从广工的「大学城校区」切到广软：那个 id 不属于广软 → 当没选过，用定位/默认
        val withFix = pickCampus(gzus, manualCampusId = "daxuecheng", location = DeviceLocation(22.4168, 112.9318))
        assertTrue(withFix is CampusPick.ByLocation)
        assertEquals("jiangmen", withFix.campus?.id)

        val withoutFix = pickCampus(gzus, manualCampusId = "daxuecheng", location = null)
        assertTrue(withoutFix is CampusPick.Default)
        assertEquals("guangzhou", withoutFix.campus?.id)
    }

    @Test
    fun withoutAFixItFallsBackToTheMainCampus() {
        val campuses = SchoolMap.campusesOf("gdut")
        // 没权限 / 还没定位 / 定位失败：一律显示**主校区**（列表第一个 = 大学城），
        // 不能"随手挑一张"—— 列表顺序一变就换图，用户会以为定位坏了
        val pick = pickCampus(campuses, manualCampusId = null, location = null)
        assertTrue(pick is CampusPick.Default)
        assertEquals("daxuecheng", pick.campus?.id)
    }

    @Test
    fun aSchoolWithoutMapsYieldsNoCampusInsteadOfCrashing() {
        val pick = pickCampus(emptyList(), manualCampusId = "daxuecheng", location = DeviceLocation(23.0, 113.0))
        assertTrue(pick is CampusPick.Default)
        assertNull(pick.campus)

        // 单校区学校也走同一条路（列表里就一个，定位不定位都是它）
        val single = pickCampus(SchoolMap.campusesOf("nhjcxy"), null, DeviceLocation(31.7030, 118.8790))
        assertEquals("lukou", (single as CampusPick.ByLocation).campus.id)
    }

    @Test
    fun pickingTheSameFixTwiceAlwaysGivesTheSameCampus() {
        // 稳定：同一位置两次进来必须显示同一张图（并列时的取舍要确定，不能靠哈希顺序）
        val campuses = SchoolMap.campusesOf("gdut")
        val fix = DeviceLocation(23.1350, 113.2950)
        val first = (pickCampus(campuses, null, fix) as CampusPick.ByLocation).campus.id
        repeat(5) {
            assertEquals(first, (pickCampus(campuses, null, fix) as CampusPick.ByLocation).campus.id)
        }
    }

    // ── 那一行说明：每种状态都要有一句人话 ──────────────────────────────────

    @Test
    fun hintExplainsWhyThisMapIsShown() {
        val campuses = SchoolMap.campusesOf("gdut")
        val daxuecheng = campuses.first { it.id == "daxuecheng" }

        // ① 正在定位
        assertTrue(
            locationHintOf(
                pick = pickCampus(campuses, null, null),
                locating = true,
                permissionDenied = false,
                locationFailed = false,
                hasPermission = true,
            ).text.contains("正在定位"),
        )

        // ② 按定位选（校区内）：敢说"已按定位选"
        val nearby = pickCampus(campuses, null, DeviceLocation(daxuecheng.latitude, daxuecheng.longitude))
        val nearbyHint = locationHintOf(nearby, false, false, false, hasPermission = true)
        assertTrue(nearbyHint.text.contains("已按定位选"))
        assertTrue("要带上距离，用户才知道凭据是「近」", nearbyHint.text.contains("0 m"))
        assertNull("定位成功时不给按钮（那一行只是说明）", nearbyHint.action)

        // ③ 手选过：文案必须说明"是你自己选的"，并且**留一个回退到"按定位选"的入口**
        //    （不给入口的话，手选之后就再也回不到自动模式了）
        val manual = pickCampus(campuses, "dongfenglu", DeviceLocation(daxuecheng.latitude, daxuecheng.longitude))
        val manualHint = locationHintOf(manual, false, false, false, hasPermission = true)
        assertTrue(manualHint.text.contains("已手动选"))
        assertEquals(LocationAction.Locate, manualHint.action)

        // ③' 手选 + 权限被永久拒绝：按钮要换成「去设置」（此时「定位」点了不会再弹窗）
        val manualBlocked = locationHintOf(
            pick = manual,
            locating = false,
            permissionDenied = true,
            locationFailed = false,
            hasPermission = false,
            permissionBlocked = true,
        )
        assertTrue(manualBlocked.text.contains("已手动选"))
        assertEquals(LocationAction.OpenSettings, manualBlocked.action)

        // ④ 没权限：给补救入口「定位」
        val noPermission = locationHintOf(
            pick = pickCampus(campuses, null, null),
            locating = false,
            permissionDenied = false,
            locationFailed = false,
            hasPermission = false,
        )
        assertTrue(noPermission.text.contains("未授权定位"))
        assertEquals(LocationAction.Locate, noPermission.action)

        // ⑤ 有权限但拿不到位置（GPS 关着 / 超时）：换一个词（"重新定位"），并说清可能的原因
        val failed = locationHintOf(
            pick = pickCampus(campuses, null, null),
            locating = false,
            permissionDenied = false,
            locationFailed = true,
            hasPermission = true,
        )
        assertTrue(failed.text.contains("拿不到定位"))
        assertEquals(LocationAction.Relocate, failed.action)

        // ⑤' 拒绝到系统不再弹窗：只能去系统设置 —— 这时给「定位」是骗人的（点了没反应）
        val blocked = locationHintOf(
            pick = pickCampus(campuses, null, null),
            locating = false,
            permissionDenied = true,
            locationFailed = false,
            hasPermission = false,
            permissionBlocked = true,
        )
        assertTrue(blocked.text.contains("系统设置"))
        assertEquals(LocationAction.OpenSettings, blocked.action)

        // ⑤'' "没权限"优先于"拿不到位置"：两者同时成立时该说的还是"先去授权"，
        //      否则用户会一直点「重新定位」而根本没给过权限
        val deniedAndFailed = locationHintOf(
            pick = pickCampus(campuses, null, null),
            locating = false,
            permissionDenied = true,
            locationFailed = true,
            hasPermission = false,
        )
        assertTrue(deniedAndFailed.text.contains("未授权定位"))
        assertEquals(LocationAction.Locate, deniedAndFailed.action)

        // ⑥ 没地图的学校：也要有一句话，而不是空白
        val noCampus = locationHintOf(
            pick = pickCampus(emptyList(), null, null),
            locating = false,
            permissionDenied = false,
            locationFailed = false,
            hasPermission = true,
        )
        assertTrue(noCampus.text.isNotBlank())
    }

    @Test
    fun distanceTextIsReadableAtEveryScale() {
        // 1 km 以内写米（校区里"离主楼 300 米"比"0.3 公里"好读），再远写公里
        assertEquals("0 m", formatDistanceMeters(0.0))
        assertEquals("320 m", formatDistanceMeters(320.4))
        assertEquals("999 m", formatDistanceMeters(999.0))
        assertEquals("1.5 km", formatDistanceMeters(1_500.0))
        assertEquals("9.4 km", formatDistanceMeters(9_351.0))
        assertEquals("412 km", formatDistanceMeters(412_345.0))
    }

    // ── 表本身的形状（校区 = 名字 + 图 + 坐标，缺一不可）────────────────────

    @Test
    fun everyCampusIsComplete() {
        SchoolMap.schoolIds.forEach { schoolId ->
            assertTrue("「$schoolId」至少要有一个校区", SchoolMap.campusesOf(schoolId).isNotEmpty())
            SchoolMap.campusesOf(schoolId).forEach { campus: SchoolCampus ->
                assertTrue("校区名不能为空", campus.name.isNotBlank())
                assertTrue("校区资源 id 不能为 0", campus.drawableRes != 0)
                assertTrue("校区 id 不能为空", campus.id.isNotBlank())
            }
        }
    }
}
