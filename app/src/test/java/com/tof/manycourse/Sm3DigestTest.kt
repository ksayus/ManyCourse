package com.tof.manycourse

import com.tof.manycourse.api.Sm3Digest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SM3 的官方向量校验（JVM，无需设备、不联网）。
 *
 * SM3 是自己实现的（项目不引 BouncyCastle / 国密库），而它同时是 SM2 的
 * C3 和 KDF 的基础 —— 只要有一个比特不对，金城学院的登录就会一直
 * 表现为「用户名或密码错误」，跟真的打错密码完全一样。
 *
 * 所以这里用的是**国标 GB/T 32905-2016 附录 A 里的标准测试向量**，
 * 不是"自己算一遍再抄进来"（那种测试只能证明代码没被改动过）。
 */
class Sm3DigestTest {

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString {
            bytes.forEach { b ->
                val v = b.toInt() and 0xFF
                append(digits[v ushr 4]).append(digits[v and 0x0F])
            }
        }
    }

    @Test
    fun matchesOfficialVector_forAbc() {
        // GB/T 32905-2016 附录 A.1：SM3("abc")
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            hex(Sm3Digest.hash("abc".toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun matchesOfficialVector_for512BitMessage() {
        // GB/T 32905-2016 附录 A.2：SM3("abcd" × 16)，正好 512 bit = 一个整分组
        val message = "abcd".repeat(16).toByteArray(Charsets.UTF_8)
        assertEquals(64, message.size)
        assertEquals(
            "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732",
            hex(Sm3Digest.hash(message)),
        )
    }

    @Test
    fun matchesKnownVector_forEmptyMessage() {
        // 空串也要能算：填充分支与"分组长度的边界"是最容易写错的地方
        assertEquals(
            "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
            hex(Sm3Digest.hash(ByteArray(0))),
        )
    }

    @Test
    fun digestIsAlways32Bytes() {
        // 长度必须恒定：KDF 是按 32 字节切块的，多一字节少一字节都会算错密钥流
        listOf(0, 1, 55, 56, 57, 63, 64, 65, 127, 128, 1000).forEach { size ->
            val digest = Sm3Digest.hash(ByteArray(size) { it.toByte() })
            assertEquals("输入 $size 字节时摘要长度不对", 32, digest.size)
        }
    }

    @Test
    fun differentInputsProduceDifferentDigests() {
        // 最廉价的一道"雪崩"检查：填充/分组逻辑写错时，长输入之间常会算出同一个值
        val a = hex(Sm3Digest.hash("密码A".toByteArray(Charsets.UTF_8)))
        val b = hex(Sm3Digest.hash("密码B".toByteArray(Charsets.UTF_8)))
        val c = hex(Sm3Digest.hash("0222010102".toByteArray(Charsets.UTF_8)))
        assertEquals("摘要不该重复", 3, setOf(a, b, c).size)
    }
}
