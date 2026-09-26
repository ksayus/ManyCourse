package com.tof.manycourse

import com.tof.manycourse.data.refreshBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「手动刷新」那套判断的回归测试（规则与文案都在 `data/DataRefresh.kt`）。
 *
 * 为什么值得测：几种"刷不了"的情形**都不会联网、也不会报错** ——
 * 在用户看来就是"点了没反应/下拉了没反应"。也就是说，**文案是这个功能唯一能被感知的部分**，
 * 所以连文案一起钉住：以后改文案至少得是有意改的，而不是顺手改没了。
 */
class DataRefreshTest {

    @Test
    fun loggedInSessionCanRefresh() {
        assertNull(
            "登录态齐全（学校 + 账号）时应当时刻能刷",
            refreshBlocker("gzus", "20221101", localDebug = false),
        )
    }

    @Test
    fun localDebugAccountIsToldThereIsNothingToRefresh() {
        // 本地调试账号**有**账号名（admin）、登录页也强制选了学校 ——
        // 后两条判断都拦不住它，只有这一条能
        assertEquals(
            "本地调试账号没连教务系统，没有可刷新的数据",
            refreshBlocker("gzus", "admin", localDebug = true),
        )
    }

    @Test
    fun loggedOutSessionIsToldToLogInFirst() {
        assertEquals("未登录，先登录才能刷新数据", refreshBlocker(null, "", localDebug = false))
        // 空白账号也算未登录：存储与同步用的是同一套"trim 后为空即无效"的口径
        assertEquals("未登录，先登录才能刷新数据", refreshBlocker("gzus", "   ", localDebug = false))
    }

    @Test
    fun missingSchoolIsExplained() {
        assertEquals(
            "没有记录到登录的学校，请退出后重新登录",
            refreshBlocker(null, "20221101", localDebug = false),
        )
    }

    @Test
    fun localDebugWinsOverTheOtherReasons() {
        // 顺序错了会怎样：拿着一个"没连教务系统的假会话"去发请求，用户白等一次超时
        assertEquals(
            "本地调试账号没连教务系统，没有可刷新的数据",
            refreshBlocker(null, "", localDebug = true),
        )
    }
}
