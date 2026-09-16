package com.tof.manycourse

import com.tof.manycourse.api.CookieCodec
import com.tof.manycourse.api.PersistedCookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 编解码的回归测试（JVM，无需设备、不联网）。
 *
 * 这是「维持登录」链路上**唯一能纯逻辑测的一环**，而且它一旦出错，
 * 后果是"冷启动恢复出一个登录态残缺的会话"：
 * 界面看着已登录，一拉数据就报错 —— 比直接要求重新登录还难查。
 *
 * 所以重点覆盖：
 *  - 教务系统 Cookie 值的**真实形态**（`%e7%8e%8b…` 这种 URL 编码串、
 *    很长的 Forms 票据、带 `=` 的 base64、含中文）；
 *  - 单条坏数据不能带崩整份（宁可少一条，也不要整份读不出来）。
 */
class CookieCodecTest {

    private fun cookie(
        host: String = "jcjx.nhjcxy.edu.cn",
        name: String = "ASP.NET_SessionId",
        value: String = "exi2ruxatuyj40v4q53vcnlx",
        domain: String = "jcjx.nhjcxy.edu.cn",
        path: String = "/",
        expiresAt: Long = Long.MAX_VALUE,
        secure: Boolean = false,
        httpOnly: Boolean = true,
        hostOnly: Boolean = true,
    ) = PersistedCookie(host, name, value, domain, path, expiresAt, secure, httpOnly, hostOnly)

    @Test
    fun roundTripPreservesEveryField() {
        val original = listOf(
            cookie(),
            cookie(
                name = ".Yyw.Base",
                value = "BE3DD4E97D49B1C9E3FC26033D3CC5076AD85BF6F0886D439407816EA04E1AEF",
                secure = true,
                httpOnly = true,
                hostOnly = false,
                expiresAt = 1_793_000_000_000L,
            ),
        )

        val decoded = CookieCodec.decode(CookieCodec.encode(original))

        assertEquals("条数不对", original.size, decoded.size)
        original.zip(decoded).forEach { (expected, actual) ->
            assertEquals("host", expected.host, actual.host)
            assertEquals("name", expected.name, actual.name)
            assertEquals("value", expected.value, actual.value)
            assertEquals("domain", expected.domain, actual.domain)
            assertEquals("path", expected.path, actual.path)
            assertEquals("expiresAt", expected.expiresAt, actual.expiresAt)
            assertEquals("secure", expected.secure, actual.secure)
            assertEquals("httpOnly", expected.httpOnly, actual.httpOnly)
            assertEquals("hostOnly", expected.hostOnly, actual.hostOnly)
        }
    }

    @Test
    fun survivesTheAwkwardCookieValuesRealSchoolsSend() {
        // 这些值都是从真机抓包里抄下来的形态：
        // U_NameCn 是 URL 编码的中文；Forms 票据里可能有 `=` 和 `+`；
        // MenuStyle/PageSize 是短数字
        val awkward = listOf(
            cookie(name = "U_NameCn", value = "%e7%8e%8b%e6%95%8f%e5%a9%b7"),
            cookie(name = "MenuStyle", value = "1"),
            cookie(name = "ticket", value = "a=b+c/d=="),
            cookie(name = "chinese", value = "会话票据含中文"),
            cookie(name = "spaces", value = "a b\tc"),
            cookie(name = "empty", value = ""),
        )

        val decoded = CookieCodec.decode(CookieCodec.encode(awkward))

        assertEquals(awkward.size, decoded.size)
        assertEquals("%e7%8e%8b%e6%95%8f%e5%a9%b7", decoded[0].value)
        assertEquals("a=b+c/d==", decoded[2].value)
        assertEquals("会话票据含中文", decoded[3].value)
        assertEquals("制表符也要原样保留（它是格式分隔符，必须靠 Base64 躲过去）", "a b\tc", decoded[4].value)
        assertEquals("空值也是合法 Cookie", "", decoded[5].value)
    }

    @Test
    fun encodedTextIsOneRecordPerLineSoTabAndNewlineCannotBreakIt() {
        val text = CookieCodec.encode(listOf(cookie(value = "line1\nline2\ttabbed")))
        val lines = text.trim().split('\n')
        assertEquals("头部 + 一条记录", 2, lines.size)
        assertEquals("原值要能还原", "line1\nline2\ttabbed", CookieCodec.decode(text).single().value)
    }

    @Test
    fun emptyInputProducesHeaderOnlyAndDecodesToNothing() {
        val text = CookieCodec.encode(emptyList())
        assertTrue("空会话也要带头部", text.startsWith(CookieCodec.HEADER))
        assertTrue(CookieCodec.decode(text).isEmpty())
    }

    @Test
    fun foreignOrMissingHeaderIsRejected() {
        // 不是我们写的格式：必须一条都不认，让上层回落到"未登录"
        assertTrue(CookieCodec.decode("").isEmpty())
        assertTrue(CookieCodec.decode("name=value\nother=thing").isEmpty())
        assertTrue(CookieCodec.decode("manycourse-cookies/999\nxxx").isEmpty())
    }

    @Test
    fun oneCorruptLineDoesNotTakeDownTheWholeSession() {
        // 关键取舍：少一条非关键 Cookie 远好过"整份会话读不出来只能重新登录"
        val good = CookieCodec.encode(listOf(cookie(), cookie(name = "MenuStyle", value = "1")))
        val lines = good.trim().split('\n').toMutableList()
        lines.add(1, "这不是一条合法记录")
        lines.add(3, "a\tb\tc") // 字段数不对

        val decoded = CookieCodec.decode(lines.joinToString("\n"))

        assertEquals("两条好的必须活下来", 2, decoded.size)
        assertEquals("ASP.NET_SessionId", decoded[0].name)
        assertEquals("MenuStyle", decoded[1].name)
    }

    @Test
    fun decodeIgnoresBlankLines() {
        val text = CookieCodec.encode(listOf(cookie()))
        val padded = text.replace("\n", "\n\n")
        assertEquals(1, CookieCodec.decode(padded).size)
    }

    @Test
    fun headerIsStableBecauseItIsTheFormatVersion() {
        // 头部就是版本号，改了它等于宣布"旧存档不认了"（老用户会被登出一次）
        assertEquals("manycourse-cookies/1", CookieCodec.HEADER)
        assertFalse(CookieCodec.HEADER.contains("\n"))
    }
}
