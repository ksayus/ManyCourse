package com.tof.manycourse

import com.tof.manycourse.api.CasRsaCipher
import com.tof.manycourse.gr_api.LoginCaptcha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAS 登录的两块支持件（JVM，无需设备、不联网）：
 *  - [CasRsaCipher]：金智 CAS 的 RSA 加密；
 *  - [LoginCaptcha]：验证码的图片/token/答案载体。
 *
 * 为什么单独测 RSA：它和正方那套**看起来像、其实不同** ——
 * 虽然都是"无填充裸模幂 + 十六进制"，但**明文字节序一个是大端一个是小端**，
 * 而且 CAS 还要把明文末尾补零到 126 字节。写错了的表现都是
 * 「账号或密码错误」，光看日志分不出来。
 *
 * 算法本身是从 CAS 前端 bundle 的 `util_rsa` 模块逐行读出来的（见 `CasRsaCipher` 的注释）。
 */
class CasLoginSupportTest {

    /** 实测 CAS 前端 bundle 里硬编码的公钥（1024 位，十六进制带前导 00） */
    private val realModulusHex =
        "00b5eeb166e069920e80bebd1fea4829d3d1f3216f2aabe79b6c47a3c18dcee5" +
            "fd22c2e7ac519cab59198ece036dcf289ea8201e2a0b9ded307f8fb704136eae" +
            "b670286f5ad44e691005ba9ea5af04ada5367cd724b5a26fdb5120cc95b6431" +
            "604bd219c6b7d83a6f8f24b43918ea988a76f93c333aa5a20991493d4eb1117e7b1"

    private fun modPow(base: java.math.BigInteger, exp: java.math.BigInteger, mod: java.math.BigInteger) =
        base.modPow(exp, mod)

    /**
     * 用私钥把密文解回来，复现服务端那一步。
     *
     * 解密要做 [CasRsaCipher] 的逆运算：`m = c^d mod n`，再把整数按**小端**拆回字节、
     * 去掉末尾补的零。这能证明"明文确实被完整装进去了、服务端解得开"。
     */
    @Test
    fun ciphertextDecryptsBackToThePlaintext() {
        val keyPair = java.security.KeyPairGenerator.getInstance("RSA")
            .apply { initialize(1024) }
            .generateKeyPair()
        val privateKey = keyPair.private as java.security.interfaces.RSAPrivateKey
        val publicKey = keyPair.public as java.security.interfaces.RSAPublicKey

        val modulusHex = publicKey.modulus.toString(16)
        val exponentHex = publicKey.publicExponent.toString(16)
        val modulus = publicKey.modulus
        val chunkSize = 2 * ((modulus.bitLength() - 1) / 16)

        listOf("abc", "Synth-P@ssw0rd-2026", "P@ssw0rd!", "a".repeat(120)).forEach { plain ->
            val hex = CasRsaCipher.encryptToHex(plain, modulusHex, exponentHex)

            // 服务端侧：c^d mod n，再把整数按**小端**拆回字节。
            // 明文在低字节（数组开头），末尾补的零在高字节，所以要 takeWhile 而不是 dropWhile。
            val decrypted = modPow(java.math.BigInteger(hex, 16), privateKey.privateExponent, modulus)
            val littleEndian = mutableListOf<Byte>()
            var remaining = decrypted
            while (remaining > java.math.BigInteger.ZERO) {
                littleEndian += remaining.and(java.math.BigInteger.valueOf(0xFF)).toByte()
                remaining = remaining.shiftRight(8)
            }
            val textBytes = littleEndian.takeWhile { it != 0.toByte() }.toByteArray()

            assertEquals("明文应能原样解回：$plain", plain, String(textBytes, Charsets.UTF_8))
            assertTrue("明文长度不该超过一个 chunk（$chunkSize 字节）", plain.toByteArray().size <= chunkSize)
        }
    }

    @Test
    fun isDeterministicBecauseThereIsNoRandomPadding() {
        // 这个算法**没有填充**，所以同一明文每次结果完全相同。
        // （和 PKCS#1 不同 —— 别哪天"顺手"给它加上随机填充，那会让服务端解出垃圾字节）
        val a = CasRsaCipher.encryptToHex("same-password", realModulusHex, "010001")
        val b = CasRsaCipher.encryptToHex("same-password", realModulusHex, "010001")
        assertEquals(a, b)
    }

    @Test
    fun tinyPlaintextStillProducesAFullWidthHexBlock() {
        // 明文远超/远小于 chunkSize 都要能算；输出是定长的 4 位/16-bit digit
        // 实测那把 1024 位公钥 → 256 个十六进制字符
        listOf("x", "Synth-P@ssw0rd-2026", "a".repeat(200)).forEach { plain ->
            val hex = CasRsaCipher.encryptToHex(plain, realModulusHex, "010001")
            assertEquals("明文「${plain.take(8)}…」的密文长度不对", 256, hex.length)
            assertTrue("必须是纯小写十六进制", hex.matches(Regex("[0-9a-f]+")))
        }
    }

    @Test
    fun acceptsTheKeyWithOrWithoutLeadingZeroPadding() {
        // 服务端下发的十六进制以 00 开头（DER 正整数补位）；去掉它应当等价
        val withZero = CasRsaCipher.encryptToHex("x", realModulusHex, "010001")
        val withoutZero = CasRsaCipher.encryptToHex("x", realModulusHex.substring(2), "010001")
        assertEquals(withZero, withoutZero)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsATinyModulus() {
        // 模数小到没有 chunk 可用时必须明确报错，而不是算出个假密文发出去
        CasRsaCipher.encryptToHex("x", "ff", "03")
    }

    @Test
    fun captchaUnwrapsTheDataUriAndTracksWhetherItIsAnswered() {
        val fromServer = LoginCaptcha(
            token = "uid-123",
            imageBase64 = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg",
        )
        // 服务端返回的是完整 data URI，界面解码前要剥掉前缀
        assertEquals("iVBORw0KGgoAAAANSUhEUg", fromServer.rawBase64)
        assertTrue("刚拿到时用户还没填答案", !fromServer.isAnswered)

        val answered = fromServer.copy(answer = "17")
        assertTrue("填了答案就该认为已作答", answered.isAnswered)
        assertEquals("token 要原样带回", "uid-123", answered.token)
    }

    @Test
    fun captchaAcceptsPlainBase64Too() {
        // 万一哪天服务端不带头前缀了，也要能用
        val plain = LoginCaptcha(token = "t", imageBase64 = "iVBORw0KGgo=")
        assertEquals("iVBORw0KGgo=", plain.rawBase64)
    }
}
