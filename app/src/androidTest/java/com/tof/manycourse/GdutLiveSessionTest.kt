package com.tof.manycourse

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tof.manycourse.api.CookieCodec
import com.tof.manycourse.api.HttpMethod
import com.tof.manycourse.data.SessionBlobCodec
import com.tof.manycourse.data.SessionVault
import com.tof.manycourse.gr_api.SchoolRegistry
import com.tof.manycourse.gr_api.WeekScheduleApi
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 广工的**真机联调自检**（要联网、要有一个真实的已登录会话）。
 *
 * 它不是回归测试，而是"下一次接这类系统时，怎么把问题钉死"的那把锤子：
 *
 *  1. [refererHeaderIsMandatoryForJxfwActions] 用同一份会话、只改请求头，
 *     证明 jxfw 的 `!action` **必须带 `Referer`**（不带时回 200 + 243 字节
 *     「非法访问/你没有该权限」，很容易被误读成"页面改版"）；
 *  2. [realApiRoundTrip] 直接跑**生产代码**（`GdutApi`）把课表 / 姓名 / 教学周 /
 *     某一周的课全拉一遍，验证整条链路。
 *
 * 没登录广工（或验机不是广工会话）时**自动跳过**（`assumeTrue`），
 * 所以放在测试集里不会误报；但它**只读**存档、只发 GET，不会退出登录。
 *
 * 结果：`adb logcat -s GdutProbe:I`
 */
@RunWith(AndroidJUnit4::class)
class GdutLiveSessionTest {

    private val tag = "GdutProbe"

    private fun log(msg: String) = Log.i(tag, msg)

    /** 读出设备上那份已登录会话（广工才继续，否则跳过）*/
    private fun storedGdutSession(): Pair<String, String>? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SessionVault.attach(context)
        val blob = SessionVault.load()?.let { SessionBlobCodec.decode(it) } ?: return null
        if (blob.schoolId != "gdut") return null
        log("存档：school=${blob.schoolId} account=${blob.account} cookies=${blob.cookies.length}B")
        return blob.account to blob.cookies
    }

    @Test
    fun refererHeaderIsMandatoryForJxfwActions() {
        val (_, cookiesRaw) = storedGdutSession() ?: run {
            log("跳过：设备上没有广工会话")
            return
        }
        assumeTrue(true)

        val url = "https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action"
        val referer = "https://jxfw.gdut.edu.cn/xsgrkbcx!xsgrkbMain.action"

        fun fetch(label: String, headers: Map<String, String>): String {
            val http = HttpMethod()
            http.importCookies(CookieCodec.decode(cookiesRaw))
            val body = http.getResponse(url, headers).body
            log("[$label] ${body.length}B :: ${body.replace(Regex("\\s+"), " ").take(80)}")
            return body
        }

        val withoutReferer = fetch("不带 Referer", emptyMap())
        assertTrue(
            "jxfw 的 !action 没有 Referer 时应当被拒（非法访问/你没有该权限）——" +
                "如果这条挂了，说明学校取消了防盗链，GdutApi 里的 Referer 可以留着（无害）但注释该更新",
            withoutReferer.contains("你没有该权限") || withoutReferer.contains("非法访问"),
        )

        val withReferer = fetch("带 Referer", mapOf("Referer" to referer))
        assertTrue(
            "带上 Referer 后必须能拿到真页面（含 #xnxqdm 学期下拉框）",
            withReferer.contains("xnxqdm"),
        )
    }

    @Test
    fun realApiRoundTrip() {
        val session = storedGdutSession() ?: run {
            log("跳过：设备上没有广工会话")
            return
        }
        val (account, cookiesRaw) = session
        val api = SchoolRegistry.apiOf("gdut")!!
        assertTrue("存档里的 Cookie 必须能灌回会话", api.importSession(cookiesRaw))

        fun <T> await(label: String, call: ((Result<T>) -> Unit) -> Unit): Result<T>? {
            val latch = CountDownLatch(1)
            var result: Result<T>? = null
            call {
                result = it
                latch.countDown()
            }
            if (!latch.await(30, TimeUnit.SECONDS)) {
                log("[$label] 超时")
                return null
            }
            result!!.fold(
                onSuccess = { log("[$label] ✅ ${describe(it)}") },
                onFailure = { log("[$label] ❌ ${it.message}") },
            )
            return result
        }

        val profile = await("fetchProfile") { api.fetchProfile(account, it) }
        val schedule = await("fetchSchedule") { api.fetchSchedule(account, it) }
        val weekApi = api as WeekScheduleApi
        val weeks = await("fetchWeeks") { weekApi.fetchWeeks(it) }
        val week3 = await("fetchWeekCourses(第3周)") { weekApi.fetchWeekCourses(3, it) }

        assertTrue("姓名必须拿到（欢迎页页头）", profile?.getOrNull()?.name?.isNotBlank() == true)
        assertTrue("课表必须非空", (schedule?.getOrNull()?.size ?: 0) > 0)
        assertTrue("教学周必须非空（日历页靠它拿真实日期）", (weeks?.getOrNull()?.size ?: 0) > 0)
        assertTrue("第 3 周应当有课或至少不报错", week3?.isSuccess == true)
    }

    private fun describe(value: Any?): String = when (value) {
        is com.tof.manycourse.data.StudentProfile -> "姓名=${value.name}"
        is List<*> -> {
            val first = value.firstOrNull()
            when (first) {
                is com.tof.manycourse.data.SchoolCourse ->
                    "${value.size} 门课；样例：${first.name} ${first.teacher} ${first.room} " +
                        "周${first.weekday} ${first.periodLabel} ${first.weeks}"
                is com.tof.manycourse.data.SchoolWeek -> {
                    val last = value.lastOrNull() as? com.tof.manycourse.data.SchoolWeek
                    "${value.size} 个教学周；第 1 周 ${first.start} ~ ${first.end}；" +
                        "最后一周 = 第 ${last?.index} 周（${last?.end}）"
                }
                else -> value.size.toString()
            }
        }
        else -> value.toString()
    }
}
