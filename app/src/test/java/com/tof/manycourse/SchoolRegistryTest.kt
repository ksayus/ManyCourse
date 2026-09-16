package com.tof.manycourse

import com.tof.manycourse.gr_api.SchoolRegistry
import com.tof.manycourse.gr_api.WeekScheduleApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学校清单的纯逻辑校验（JVM，无需设备）。
 *
 * 这组测试的作用是**给"加学校"这个高频手改操作上一道保险**：
 * 复制粘贴一个新 `XxxApi` 时最容易犯的错（id 重复、忘了 https、
 * baseUrl 多写一个斜杠、加进清单却忘了提供实现）都会在这里直接红掉。
 */
class SchoolRegistryTest {

    @Test
    fun idsAreUniqueLowercaseAndNonEmpty() {
        val ids = SchoolRegistry.schools.map { it.id }
        assertTrue("学校清单不能为空", ids.isNotEmpty())
        assertEquals("学校 id 不能重复 —— 它是 SharedPreferences 里的主键", ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertTrue("id 不能为空", id.isNotBlank())
            assertEquals("id 建议全小写英文（$id），避免持久化/日志里出现大小写歧义", id.lowercase(), id)
        }
    }

    @Test
    fun everySchoolIsUsableAsADropdownEntry() {
        SchoolRegistry.schools.forEach { school ->
            assertTrue("${school.id} 缺少展示名", school.name.isNotBlank())
            assertTrue("${school.id} 的 baseUrl 必须是 https", school.baseUrl.startsWith("https://"))
            assertFalse("${school.id} 的 baseUrl 结尾不能带 /", school.baseUrl.endsWith("/"))
            assertFalse("${school.id} 的 host 解析失败：${school.host}", school.host.isBlank())
            assertFalse("${school.id} 的 host 里不该还留着协议", school.host.contains("://"))
        }
    }

    @Test
    fun apiOf_resolvesEveryRegisteredSchool() {
        SchoolRegistry.schools.forEach { school ->
            val api = SchoolRegistry.apiOf(school.id)
            assertNotNull("${school.id} 在清单里却没有对应的 SchoolApi 实现", api)
            assertEquals("实现暴露的 school 必须与清单里的同一个对象", school, api!!.school)
            assertFalse("${school.id} 的登录地址不能为空", api.school.baseUrl.isBlank())
        }
    }

    @Test
    fun unknownOrMissingId_isNotResolved() {
        // 学校被下架后，持久化里残留的旧 id 必须安全地解析成 null（登录页据此回落到"未选择"）
        assertNull(SchoolRegistry.apiOf(null))
        assertNull(SchoolRegistry.apiOf(""))
        assertNull(SchoolRegistry.apiOf("no-such-school"))
        assertNull(SchoolRegistry.find(null))
        assertNull(SchoolRegistry.find("no-such-school"))
    }

    @Test
    fun preRegisteredSchools_arePresentWithTheirRealBaseUrls() {
        // 这几所是预写好的，删掉/改 id 会让老用户"上次选的学校"失效
        assertEquals("广州软件学院", SchoolRegistry.find("gzus")?.name)
        assertEquals("https://jwxt.gzus.edu.cn", SchoolRegistry.find("gzus")?.baseUrl)
        assertEquals("南京航空航天大学金城学院", SchoolRegistry.find("nhjcxy")?.name)
        assertEquals("https://jcjx.nhjcxy.edu.cn", SchoolRegistry.find("nhjcxy")?.baseUrl)
        assertEquals("广东工业大学", SchoolRegistry.find("gdut")?.name)
        assertEquals(
            "广工的课表在 jxfw（教学管理系统），统一认证在另一个 host（authserver.gdut.edu.cn）",
            "https://jxfw.gdut.edu.cn",
            SchoolRegistry.find("gdut")?.baseUrl,
        )
    }

    @Test
    fun gzus_isReadyToLogin() {
        assertTrue(
            "广州软件学院已按正方 V9 接好（登录页 + 公钥接口都实测过），configured 应为 true",
            SchoolRegistry.apiOf("gzus")!!.configured,
        )
    }

    @Test
    fun nhjcxy_isReadyToLogin() {
        // 金城学院的登录入口已实测确认：SM2 公钥在登录页里（#PublicKey）、
        // POST /Mvc/Base/Login 返回 JSON、老系统 /EaWeb/ 还要再登一次。
        // 详见 NhjcxyApi 的类注释。
        assertTrue(
            "金城学院的登录协议已实测打通（SM2 + 两段登录），configured 应为 true",
            SchoolRegistry.apiOf("nhjcxy")!!.configured,
        )
    }

    @Test
    fun gdut_isReadyToLogin() {
        // 广工已按"金智统一身份认证（authserver）+ jxfw 课表接口"接好：
        // 登录页隐藏域、密码 AES 加密算法、课表接口（xsAllKbList / getKbRq）
        // 全部出自抓包与页面 JS 核对，并有逐字节回归测试。
        // 详见 GdutApi 与 AuthserverAesCipher 的类注释。
        assertTrue(
            "广东工业大学的登录协议已实测打通（统一认证 + xsgrkbcx 课表），configured 应为 true",
            SchoolRegistry.apiOf("gdut")!!.configured,
        )
    }

    @Test
    fun everySchoolDeclaresItsLoginMechanism() {
        // 加学校时容易只填 school 忘了填登录参数。这里只能检查"最低限度的自洽"：
        // configured = true 的学校，system 里必须写清楚它是什么系统，
        // 否则以后排查"登录为什么失败"时连该去抓哪个页面的包都不知道。
        SchoolRegistry.schools.forEach { school ->
            if (SchoolRegistry.apiOf(school.id)!!.configured) {
                assertTrue(
                    "${school.id} 声称已接入登录，但没说是什么系统（School.system 为空）",
                    school.system.isNotBlank(),
                )
            }
        }
    }

    /**
     * 「按教学周查课表」能力的**归属边界**（日历页的真实日期靠它）。
     *
     * - 广软有 `N2154` 周次课表接口（一次给一周，还带每一周的起止日期），所以实现了它；
     * - 广工也有（`xsgrkbcx!getKbRq.action?xnxqdm=…&zc=…` 一次给一周的课，
     *   响应里第二个数组就是那一周周一~周日的真实日期），所以也实现了它；
     * - 金城学院**没有**这种能力 —— 它的课表是"按班级查一张整学期表"，
     *   拿不到"某一周有哪些课"，所以**不要**给它实现 [WeekScheduleApi]：
     *   实现了也只会拉到本周的数据，日历照样填不满，白搭一次请求。
     *   日历页对它会**只给「本周」兜底**（其他周留空），见 `ui/CalendarScreen.kt`。
     *
     * 这条测试的价值在于**把边界钉住**：以后有人想"顺手给金城也接上"时，
     * 这里会先红一下，逼他先想清楚数据到底拿不拿得到。
     */
    @Test
    fun onlySchoolsWithPerWeekQueries_implementWeekScheduleApi() {
        assertTrue(
            "广软有周次课表接口（N2154），日历页要靠它拿真实日期",
            SchoolRegistry.apiOf("gzus") is WeekScheduleApi,
        )
        assertTrue(
            "广工有 getKbRq（一次给一周的课 + 那一周的日期），日历页要靠它拿真实日期",
            SchoolRegistry.apiOf("gdut") is WeekScheduleApi,
        )
        assertFalse(
            "金城学院拿不到「除了本周以外」的课表，不该实现 WeekScheduleApi" +
                "（实现了日历也填不满，只会白发请求）",
            SchoolRegistry.apiOf("nhjcxy") is WeekScheduleApi,
        )
    }
}
