package com.tof.manycourse

import com.tof.manycourse.api.Sm2Cipher
import com.tof.manycourse.api.Sm3Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * SM2 加密的校验（JVM，无需设备、不联网）。
 *
 * 这组测试守的是**金城学院登录最容易失败、又最难查的一条**：
 * 密码密文算错时，服务端只会回一句「用户名或密码错误」，
 * 和真的把密码打错长得一模一样。
 *
 * 三层保障，一层比一层独立：
 *
 * 1. **[encryptToHex_isByteIdenticalToTheReferenceVector]**
 *    与参考实现（Node 侧同一套算法，已用真账号在
 *    <https://jcjx.nhjcxy.edu.cn> 登录成功）在**固定随机数下逐字节比对**。
 * 2. **[ciphertext_decryptsBackToThePlaintext]**
 *    在测试内部**独立解密**一遍：用私钥 d=1 的测试公钥（即基点 G），
 *    共享点就是 C1，于是可以自己重算 KDF 与 C3 ——
 *    这条不依赖"我写的参考实现"，是真正意义上的正确性验证。
 * 3. **[rejectsAPublicKeyThatIsNotOnTheCurve]** 等负向用例。
 */
class Sm2CipherTest {

    /** sm2p256v1 的基点 G；把私钥取 1 时，公钥就等于 G，测试里能自己解密 */
    private val gx = "32c4ae2c1f1981195f9904466a39c9948fe30bbff2660be1715a4589334c74c7"
    private val gy = "bc3736a2f4f6779c59bdcee36b692153d0a9877cc62a474002df32e52139f0a0"
    private val testPublicKey = "04$gx$gy"

    /** 固定随机数：SM2 的密文依赖随机数 k，不固定住就没法断言具体值 */
    private val fixedK = BigInteger("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 16)

    /** 金城学院登录页实际下发的公钥格式（130 个十六进制字符，以 `04` 开头）*/
    private val realNhjcPublicKey =
        "04D242C8A5A162DD32CCA8DA4A3FA5B42C29150BDBDBC425BD335F51C2E457FC" +
            "A2C01ACD069D3F4472D4438593658C9EA366720CDD92672B042CC9C2D19FF0EE65"

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString {
            bytes.forEach { b ->
                val v = b.toInt() and 0xFF
                append(digits[v ushr 4]).append(digits[v and 0x0F])
            }
        }
    }

    // ── 1. 与参考实现逐字节一致 ─────────────────────────────────────────────

    @Test
    fun encryptToHex_isByteIdenticalToTheReferenceVector() {
        // 期望值由 Node 侧参考实现（同一套曲线/KDF/C3 规则、同一公钥、同一 k）算出
        assertEquals(
            "04344081b80805540a38d71d721bd072d8957eae15aeb852e72086ab4c5962b89b" +
                "5bb8628b9d9c4edd30f341a5a25886c063cff46dc04c7e68f2efb3b58830e0f3" +
                "4df9e70de6eef695d57cd90ad269aa606291150b7195034948a6f5ad58781f4a3267bd",
            Sm2Cipher.encryptToHex("abc", testPublicKey, fixedK),
        )
    }

    @Test
    fun encryptToHex_matchesReferenceVectorForAStudentId() {
        // 用一个像学号的输入（10 位纯数字），覆盖实际会走到的分支
        assertEquals(
            "04344081b80805540a38d71d721bd072d8957eae15aeb852e72086ab4c5962b89b" +
                "5bb8628b9d9c4edd30f341a5a25886c063cff46dc04c7e68f2efb3b58830e0f3" +
                "1ca9b679a654c29190e1c120f2e191eb581c7ee3688f30a510d563b06715fe142e7c31b4b5f439f7e7d2",
            Sm2Cipher.encryptToHex("0222010102", testPublicKey, fixedK),
        )
    }

    // ── 2. 独立解密验证（不依赖参考实现）────────────────────────────────────

    @Test
    fun ciphertext_decryptsBackToThePlaintext() {
        // 公钥 = G ⟹ 私钥 d = 1 ⟹ 共享点 S = C1 · d = C1
        val cases = listOf("abc", "0222010102", "Wang123456!", "中文密码也要能加密")
        cases.forEach { plain ->
            val cipher = Sm2Cipher.encryptToHex(plain, testPublicKey, fixedK)
            assertTrue("密文应以未压缩点前缀 04 开头：$cipher", cipher.startsWith("04"))

            val x1 = cipher.substring(2, 66)
            val y1 = cipher.substring(66, 130)
            val body = cipher.substring(130)
            // 明文是 UTF-8，密文 C2 与它等长；剩下 64 个十六进制字符是 C3
            val plainBytes = plain.toByteArray(Charsets.UTF_8)
            val c2 = body.substring(0, body.length - 64)
            val c3 = body.substring(body.length - 64)
            assertEquals("C2 长度应与明文一致", plainBytes.size * 2, c2.length)

            // 自己重算 KDF 与 C3（SM2 标准里的 KDF：SM3(x2‖y2‖ct)，ct 从 1 起、4 字节大端）
            val x2 = hexToBytes(x1)
            val y2 = hexToBytes(y1)
            val mask = kdf(x2, y2, plainBytes.size)
            val recovered = ByteArray(plainBytes.size) { i ->
                (hexToBytes(c2)[i].toInt() xor mask[i].toInt()).toByte()
            }
            assertEquals("解密结果应与明文一致", plain, String(recovered, Charsets.UTF_8))

            // C3 = SM3(x2 ‖ M ‖ y2)
            assertEquals(
                "C3 校验值不对（x2‖M‖y2 的 SM3）",
                c3,
                hex(Sm3Digest.hash(x2 + plainBytes + y2)),
            )
        }
    }

    // ── 3. 真实公钥 / 边界与负向用例 ────────────────────────────────────────

    @Test
    fun acceptsTheRealSchoolPublicKeyWithAndWithoutTheUncompressedPrefix() {
        // 站点下发的公钥是 130 字符（04 + x + y）；sm2.js 会砍掉前两位再算。
        // 两种写法必须得到同样的结果，否则"服务端换了格式"就会静默算错。
        val withPrefix = Sm2Cipher.encryptToHex("abc", realNhjcPublicKey, fixedK)
        val withoutPrefix = Sm2Cipher.encryptToHex("abc", realNhjcPublicKey.substring(2), fixedK)
        assertEquals(withPrefix, withoutPrefix)
        // 04 + x(64) + y(64) + C2(2×明文长) + C3(64)
        assertEquals(130 + 3 * 2 + 64, withPrefix.length)
    }

    @Test
    fun ciphertextIsLowercaseHex() {
        val cipher = Sm2Cipher.encryptToHex("abc", testPublicKey, fixedK)
        assertTrue("密文必须是纯十六进制：$cipher", cipher.matches(Regex("[0-9a-f]+")))
        assertEquals("不该把 04 前缀写成大写", "04", cipher.substring(0, 2))
    }

    @Test
    fun randomScalarMakesEachCiphertextDifferent() {
        // 每次登录都该是不同的密文（k 是随机的）；如果这里相等，说明随机数没用上
        val a = Sm2Cipher.encryptToHex("abc", testPublicKey)
        val b = Sm2Cipher.encryptToHex("abc", testPublicKey)
        assertTrue("两次加密结果不该相同：$a", a != b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAPublicKeyThatIsNotOnTheCurve() {
        // 公钥乱码时要在本地就报错，而不是把垃圾密文发给服务端、
        // 让用户看到一句莫名其妙的「用户名或密码错误」
        Sm2Cipher.encryptToHex("abc", "00".repeat(64), fixedK)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAPublicKeyOfTheWrongLength() {
        Sm2Cipher.encryptToHex("abc", "04d242c8", fixedK)
    }

    // ── 测试内部用的小工具 ──────────────────────────────────────────────────

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    private fun kdf(x2: ByteArray, y2: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        val seed = x2 + y2
        var offset = 0
        var ct = 1
        while (offset < length) {
            val counter = byteArrayOf(
                (ct ushr 24).toByte(), (ct ushr 16).toByte(), (ct ushr 8).toByte(), ct.toByte(),
            )
            val block = Sm3Digest.hash(seed + counter)
            val take = minOf(block.size, length - offset)
            block.copyInto(out, offset, 0, take)
            offset += take
            ct++
        }
        return out
    }
}
