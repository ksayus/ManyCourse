package com.tof.manycourse

import com.tof.manycourse.gr_api.schools.GzusSession
import com.tof.manycourse.gr_api.schools.GzusSessionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广软会话存档（TGT + Cookie）编解码的回归测试。
 *
 * 这一层错了的表现特别隐蔽：**不报错，只是"每次打开都要重新登录"**，
 * 或者更糟 —— 恢复出一个 TGT 是空的会话，之后每个请求都白试一遍换票。
 *
 * 真实形态取自实测：TGT 是 `TGT-120804-462a21905e484d668497aa782073facc`，
 * Cookie 那一段是多行的 `manycourse-cookies/1` 文本。
 */
class GzusSessionCodecTest {

    private val tgt = "TGT-120804-462a21905e484d668497aa782073facc"
    private val cookieText = """
        manycourse-cookies/1
        ancmdXQuZWR1LmNu	JSESSIONID	MzBBQ0Yx
        ancmdXQuZWR1LmNu	route	OTY3NmNkZTQ
    """.trimIndent()

    @Test
    fun roundTripsTgtAndCookies() {
        val encoded = GzusSessionCodec.encode(GzusSession(tgt = tgt, cookies = cookieText))

        assertTrue("头部要能被认出来", GzusSessionCodec.hasOurHeader(encoded))
        assertTrue("头部必须带版本号，以后才能按版本分流", encoded.startsWith("manycourse-gzus-session/1"))

        val decoded = requireNotNull(GzusSessionCodec.decode(encoded))
        assertEquals(tgt, decoded.tgt)
        assertEquals("Cookie 那段是多行文本，必须原样还原", cookieText, decoded.cookies)
    }

    @Test
    fun cookiesSectionSurvivesNewlinesAndTabs() {
        // CookieCodec 的文本里同时有换行和制表符，不编码就会把信封撑破 ——
        // 撑破之后 decode 会解出半截 Cookie，恢复出来的会话是"看着登录了其实没有"
        val nasty = "manycourse-cookies/1\nhost\tname\tvalue\nhost2\tn2\tv2\n"
        val decoded = requireNotNull(
            GzusSessionCodec.decode(GzusSessionCodec.encode(GzusSession(tgt = tgt, cookies = nasty)))
        )
        assertEquals(nasty, decoded.cookies)
        assertEquals("整份存档应当只有三行", 3, GzusSessionCodec.encode(GzusSession(tgt, nasty)).trimEnd().lines().size)
    }

    @Test
    fun tgtOnlyArchiveIsStillUsable() {
        // 这是最关键的一条：**没有 Cookie、只有 TGT 也必须算一份有效存档**。
        // 实测（tools/cas_probe.mjs tgt-follow）：清空全部 Cookie，只用 TGT 换票
        // 照样能重建正方会话、拉到课表。
        val decoded = requireNotNull(
            GzusSessionCodec.decode(GzusSessionCodec.encode(GzusSession(tgt = tgt, cookies = "")))
        )
        assertEquals(tgt, decoded.tgt)
        assertEquals("", decoded.cookies)
        assertFalse("只剩 TGT 也算有可用信息", decoded.isEmpty)
    }

    @Test
    fun emptyArchiveIsReportedAsEmpty() {
        // 两个都空 = 没有任何可用信息，SessionStore 不该把它落盘（否则下次冷启动白跑）
        val empty = GzusSession(tgt = "", cookies = "")
        assertTrue(empty.isEmpty)
        assertFalse(GzusSession(tgt = tgt).isEmpty)
        assertFalse(GzusSession(cookies = "manycourse-cookies/1").isEmpty)
        // 只有空白也按空处理
        assertTrue(GzusSession(tgt = "  ", cookies = "\n").isEmpty)
    }

    @Test
    fun recognisesForeignAndBrokenArchives() {
        // 老版本存档（只有 Cookie）与别人家的格式都不能被当成这份格式硬解
        assertNull(GzusSessionCodec.decode("manycourse-cookies/1\nabc"))
        assertNull(GzusSessionCodec.decode(""))
        assertFalse(GzusSessionCodec.hasOurHeader("manycourse-session/1"))

        // 头部对但字段缺失 / Base64 坏掉：返回 null，让调用方清存档重登录，
        // 而不是解出一个 tgt="" 的"成功"会话
        assertNull(GzusSessionCodec.decode("manycourse-gzus-session/1\ntgt="))
        assertNull(GzusSessionCodec.decode("manycourse-gzus-session/1\ncookies="))
        assertNull(GzusSessionCodec.decode("manycourse-gzus-session/1\ntgt=!!!not-base64!!!\ncookies="))
    }
}
