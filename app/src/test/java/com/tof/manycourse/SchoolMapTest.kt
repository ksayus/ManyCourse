package com.tof.manycourse

import com.tof.manycourse.data.SchoolMap
import com.tof.manycourse.data.distanceMeters
import com.tof.manycourse.gr_api.SchoolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校园地图登记表的校验（JVM，无需设备）。
 *
 * 这组测试守的是几件很容易静默出错的事：
 *
 * 1. **登记的 id 必须是真实存在的学校 id**。拼错了不会崩，
 *    只会表现为"地图页说这学校没有地图"，很难往拼写上想；
 * 2. **`R.drawable.school_map_*` 必须真的存在**。图片被改名/挪走时，
 *    这里引用的 `R.drawable.xxx` 会直接编译不过（这正是我们想要的失败方式）——
 *    两个 `shcool_map_gdut_*.jpg`（把 school 拼成 shcool）就是这么被抓出来的；
 * 3. **同一所学校里，任意两个校区不能太近**。坐标是给"按定位挑图"用的，
 *    复制粘贴时忘了改经纬度（两个校区坐标一样），表现是"永远只显示列表里第一个"，
 *    在真机上根本看不出来 —— 所以在这里量距离钉死。
 *
 * 顺带记一笔：地图原图原本放在 `res/school_map/` 下，**那不是合法的资源目录**，
 * AAPT2 会整个忽略，代码里根本取不到。现已挪到 `res/drawable-nodpi/`。
 */
class SchoolMapTest {

    /** 同一所学校里两个校区至少隔开这么远（米）——比这个近就说明坐标抄错了 */
    private val minCampusGapMeters = 2_000.0

    @Test
    fun everyRegisteredMapPointsToARealSchool() {
        assertTrue("至少要登记一所学校的地图", SchoolMap.schoolIds.isNotEmpty())
        val knownIds = SchoolRegistry.schools.map { it.id }.toSet()
        SchoolMap.schoolIds.forEach { id ->
            assertTrue(
                "SchoolMap 里登记的「$id」不是 SchoolRegistry 里的学校 id（拼错了？）",
                id in knownIds,
            )
        }
    }

    @Test
    fun shippedSchoolsResolveToTheirCampusMaps() {
        // 这几所是仓库里实际带了地图的学校；对应 R.drawable.school_map_* 必须存在，
        // 否则这里编译不过（正是我们想要的失败方式）
        assertNotNull("金城学院应有地图（school_map_nhjcxy）", SchoolMap.drawableOf("nhjcxy"))
        assertNotNull(
            "广州软件学院应有地图（school_map_gzus_guangzhou）",
            SchoolMap.drawableOf("gzus"),
        )
        assertNotNull(
            "广东工业大学应有地图（school_map_gdut_daxuecheng）",
            SchoolMap.drawableOf("gdut"),
        )
    }

    @Test
    fun gdutHasItsFiveCampusesKeyedByCampusId() {
        // ★ 这次新加的 5 张广工地图：一个校区一张，id 与文件名一一对应。
        //   少一张 = 那个校区的用户看不到自己的图；顺序错了 = 默认显示的不是校本部
        val campuses = SchoolMap.campusesOf("gdut")
        assertEquals(
            listOf("daxuecheng", "dongfenglu", "longdong", "panyu", "jieyang"),
            campuses.map { it.id },
        )
        assertEquals(
            listOf("大学城校区", "东风路校区", "龙洞校区", "番禺校区", "揭阳校区"),
            campuses.map { it.name },
        )
        assertEquals(
            "默认（主校区）是大学城，没定位时就显示它",
            "daxuecheng",
            SchoolMap.defaultCampusOf("gdut")?.id,
        )
        assertEquals(
            "同一所学校里每张图都必须是不同的资源",
            campuses.size,
            campuses.map { it.drawableRes }.toSet().size,
        )
    }

    @Test
    fun gzusHasItsTwoCampusesAndSingleCampusSchoolsHaveOne() {
        assertEquals(listOf("guangzhou", "jiangmen"), SchoolMap.campusesOf("gzus").map { it.id })
        assertEquals(1, SchoolMap.campusesOf("nhjcxy").size)
    }

    @Test
    fun campusesOfTheSameSchoolAreFarEnoughApartToBeToldApart() {
        // ★ 坐标只有"挑最近的校区"一个用途，所以它错的时候**不会崩**：
        //   两个校区写成同一个点，永远是列表里第一个赢，看图上只是"定位好像不准"。
        //   同校任意两个校区至少隔开 2 km —— 广工最小的间距是大学城↔番禺（约 9 km）
        SchoolMap.schoolIds.forEach { schoolId ->
            val campuses = SchoolMap.campusesOf(schoolId)
            for (i in campuses.indices) {
                for (j in i + 1 until campuses.size) {
                    val a = campuses[i]
                    val b = campuses[j]
                    val gap = distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
                    assertTrue(
                        "「${a.name}」与「${b.name}」只隔 ${gap.roundToKm()} km —— 坐标八成抄错了",
                        gap >= minCampusGapMeters,
                    )
                }
            }
        }
    }

    @Test
    fun coordinatesAreInsideChinaAndNotFlipped() {
        // 经纬度写反（纬度填成经度）是这类表最常见的错法，而且只在"定位挑图"时才暴露。
        // 中国陆地大致在 纬度 18~54、经度 73~135 之间 —— 值不在这个范围说明填反了
        SchoolMap.schoolIds.forEach { schoolId ->
            SchoolMap.campusesOf(schoolId).forEach { campus ->
                assertTrue(
                    "「${campus.name}」的纬度 ${campus.latitude} 不像中国范围内的纬度（经纬度写反了？）",
                    campus.latitude in 18.0..54.0,
                )
                assertTrue(
                    "「${campus.name}」的经度 ${campus.longitude} 不像中国范围内的经度（经纬度写反了？）",
                    campus.longitude in 73.0..135.0,
                )
            }
        }
    }

    @Test
    fun unknownOrMissingSchoolReturnsNullInsteadOfCrashing() {
        // 没登记地图的学校必须安全返回 null，由地图页展示空状态
        assertNull(SchoolMap.drawableOf(null))
        assertNull(SchoolMap.drawableOf(""))
        assertNull(SchoolMap.drawableOf("no-such-school"))
        assertTrue(SchoolMap.campusesOf(null).isEmpty())
        assertTrue(SchoolMap.campusesOf("no-such-school").isEmpty())
        assertNull(SchoolMap.campusOf("gdut", "no-such-campus"))
        assertNull("换学校后旧校区的 id 不属于新学校", SchoolMap.campusOf("gzus", "daxuecheng"))
    }

    @Test
    fun mapsAreKeyedByLowercaseSchoolIds() {
        // 和学校 id 一样：小写英文短名，避免持久化/日志里的歧义
        SchoolMap.schoolIds.forEach { id ->
            assertEquals("地图登记的 id 必须全小写：$id", id.lowercase(), id)
        }
        // 校区 id 同理（它出现在资源名 mid：school_map_gdut_daxuecheng）
        SchoolMap.schoolIds.forEach { id ->
            SchoolMap.campusesOf(id).forEach { campus ->
                assertEquals("校区 id 必须全小写：${campus.id}", campus.id.lowercase(), campus.id)
                assertTrue("校区 id 不能用下划线以外的符号：${campus.id}", campus.id.all { it.isLetterOrDigit() })
            }
        }
    }

    @Test
    fun idsOfShippedSchoolsAreStable() {
        // 这些 id 是持久化主键，改了会让老用户"上次选的学校"和地图对不上
        assertEquals(setOf("nhjcxy", "gzus", "gdut"), SchoolMap.schoolIds)
    }
}

/** 测试失败信息里用的整数公里（只为了好读）*/
private fun Double.roundToKm(): Long = Math.round(this / 1000.0)
