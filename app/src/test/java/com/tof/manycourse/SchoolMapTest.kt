package com.tof.manycourse

import com.tof.manycourse.data.SchoolMap
import com.tof.manycourse.gr_api.SchoolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校园地图映射的校验（JVM，无需设备）。
 *
 * 这组测试守的是两件很容易静默出错的事：
 *
 * 1. **地图登记的 id 必须是真实存在的学校 id**。拼错了不会崩，
 *    只会表现为"地图页说这学校没有地图"，很难往拼写上想。
 * 2. **`R.drawable.school_map_*` 必须真的存在**。图片文件被改名/挪走时，
 *    代码里那行 `R.drawable.xxx` 会直接编译不过 —— 这条测试引用了它，
 *    等于顺手把"资源名对不对"钉在编译期。
 *
 * 顺带记一笔：地图原图原本放在 `res/school_map/` 下，**那不是合法的资源目录**，
 * AAPT2 会整个忽略，代码里根本取不到。现已挪到 `res/drawable-nodpi/`。
 */
class SchoolMapTest {

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
    fun bothShippedSchoolsResolveToTheirMapImage() {
        // 这两所是仓库里实际带了地图的学校；对应 R.drawable.school_map_* 必须存在，
        // 否则这里编译不过（这正是我们想要的失败方式）
        val nhjcxy = SchoolMap.drawableOf("nhjcxy")
        val gzus = SchoolMap.drawableOf("gzus")
        assertNotNull("金城学院应有地图（school_map_nhjcxy）", nhjcxy)
        assertNotNull("广州软件学院应有地图（school_map_gzus）", gzus)
        assertTrue("两所学校的地图不该是同一份资源", nhjcxy != gzus)
    }

    @Test
    fun unknownOrMissingSchoolReturnsNullInsteadOfCrashing() {
        // 没登记地图的学校必须安全返回 null，由地图页展示空状态
        assertNull(SchoolMap.drawableOf(null))
        assertNull(SchoolMap.drawableOf(""))
        assertNull(SchoolMap.drawableOf("no-such-school"))
    }

    @Test
    fun mapsAreKeyedByLowercaseSchoolIds() {
        // 和学校 id 一样：小写英文短名，避免持久化/日志里的歧义
        SchoolMap.schoolIds.forEach { id ->
            assertEquals("地图登记的 id 必须全小写：$id", id.lowercase(), id)
        }
    }

    @Test
    fun idsOfShippedSchoolsAreStable() {
        // 这两个 id 是持久化主键，改了会让老用户"上次选的学校"和地图对不上
        assertEquals(setOf("nhjcxy", "gzus"), SchoolMap.schoolIds)
    }
}
