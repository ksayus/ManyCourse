package com.tof.manycourse

import com.tof.manycourse.data.SessionBlob
import com.tof.manycourse.data.SessionBlobCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话存档编解码的回归测试（JVM，无需设备、不联网）。
 *
 * `SessionVault` 那层要碰 Android Keystore（JVM 测不了），
 * 但"存档内容长什么样"是纯逻辑，必须测：
 * 这份文本是**唯一**能让 App 冷启动认出"上次是谁登录的"的东西，
 * 编错了的表现是"明明登录过，重启却要求重新登录"或者更糟 ——
 * 把账号/学校解成了空值，恢复出一个身份不明的会话。
 */
class SessionBlobCodecTest {

    private val blob = SessionBlob(
        schoolId = "nhjcxy",
        account = "0222010102",
        localDebug = false,
        cookies = "manycourse-cookies/1\nam9rag5oamN4eS5lZHUuY24\tQVNQLk5FVF9TZXNzaW9uSWQ",
    )

    @Test
    fun roundTripPreservesEverything() {
        val decoded = requireNotNull(SessionBlobCodec.decode(SessionBlobCodec.encode(blob)))

        assertEquals(blob.schoolId, decoded.schoolId)
        assertEquals(blob.account, decoded.account)
        assertEquals(blob.localDebug, decoded.localDebug)
        assertEquals(blob.cookies, decoded.cookies)
    }

    @Test
    fun handlesCookiesTextWithNewlinesAndCjk() {
        // Cookie 原文是多行的（每条 Cookie 一行），账号/学校理论上也可能非 ASCII
        val tricky = blob.copy(
            account = "学号-0222010102",
            cookies = "manycourse-cookies/1\n第一行\n第二行\ttab\n",
        )
        val decoded = requireNotNull(SessionBlobCodec.decode(SessionBlobCodec.encode(tricky)))

        assertEquals("学号-0222010102", decoded.account)
        assertEquals("多行 Cookie 文本必须逐字还原（换行不能被格式吃掉）", tricky.cookies, decoded.cookies)
    }

    @Test
    fun localDebugFlagSurvives() {
        val debug = blob.copy(localDebug = true)
        assertTrue(requireNotNull(SessionBlobCodec.decode(SessionBlobCodec.encode(debug))).localDebug)
        assertFalse(requireNotNull(SessionBlobCodec.decode(SessionBlobCodec.encode(blob))).localDebug)
    }

    @Test
    fun missingRequiredFieldDecodesToNull() {
        // 少了学校或账号就没法恢复身份 —— 必须返回 null 让上层清档回登录页，
        // 而不是恢复出一个"学校未知"的会话
        val text = SessionBlobCodec.encode(blob)
        val withoutAccount = text.lineSequence().filterNot { it.startsWith("account=") }.joinToString("\n")
        assertNull(SessionBlobCodec.decode(withoutAccount))

        val withoutSchool = text.lineSequence().filterNot { it.startsWith("schoolId=") }.joinToString("\n")
        assertNull(SessionBlobCodec.decode(withoutSchool))

        val withoutCookies = text.lineSequence().filterNot { it.startsWith("cookies=") }.joinToString("\n")
        assertNull(SessionBlobCodec.decode(withoutCookies))
    }

    @Test
    fun garbageInputDecodesToNullInsteadOfCrashing() {
        assertNull(SessionBlobCodec.decode(""))
        assertNull(SessionBlobCodec.decode("这不是存档"))
        assertNull(SessionBlobCodec.decode("schoolId=!!!not-base64!!!\naccount=x"))
    }

    @Test
    fun headerIdentifiesOurFormat() {
        // 用来区分"旧版本存档"（该丢弃）和"我们的存档"（该恢复）
        assertTrue(SessionBlobCodec.hasOurHeader(SessionBlobCodec.encode(blob)))
        assertFalse(SessionBlobCodec.hasOurHeader("manycourse-cookies/1\nxxx"))
        assertFalse(SessionBlobCodec.hasOurHeader(""))
        assertEquals("manycourse-session/1", SessionBlobCodec.HEADER)
    }

    @Test
    fun accountAndCookiesAreNotStoredInPlainText() {
        // 存档会被加密，但顺手确认一层：敏感值本身就不该以明文出现在文本里，
        // 这样即使哪天加密被绕过/关掉，泄露面也小一点
        val text = SessionBlobCodec.encode(blob)
        assertFalse("学号不应明文出现在存档里", text.contains(blob.account))
        assertFalse("Cookie 值不应明文出现在存档里", text.contains("ASP.NET_SessionId"))
    }
}
