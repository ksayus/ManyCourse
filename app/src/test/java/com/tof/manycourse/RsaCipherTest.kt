package com.tof.manycourse

import com.tof.manycourse.api.RsaCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * 正方「裸 RSA」密码加密的校验（JVM，无需设备、不联网）。
 *
 * 登录失败最难查的一种原因就是"密码加密算错了"——服务端只会回一句
 * 「用户名或密码错误」，跟真的密码错长得一模一样。
 * 所以这里用一个现场生成的密钥对，把服务端的解密过程复现一遍，
 * 保证 [RsaCipher.encryptToHex] 算出来的密文能原样解回明文。
 */
class RsaCipherTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKey = keyPair.private as RSAPrivateKey

    private fun encode(value: BigInteger): String =
        Base64.getEncoder().encodeToString(value.toByteArray())

    @Test
    fun encryptedHex_decryptsBackToThePlainPassword() {
        val password = "MyPassw0rd"
        val hex = RsaCipher.encryptToHex(
            plain = password,
            modulusBase64 = encode(publicKey.modulus),
            exponentBase64 = encode(publicKey.publicExponent),
        )

        // 服务端做的事：new BigInteger(hex, 16) 后用私钥做 m = c^d mod n
        val decrypted = BigInteger(hex, 16).modPow(privateKey.privateExponent, privateKey.modulus)
        assertEquals(
            "解出来的大整数必须等于明文字节（服务端就是这么比的）",
            BigInteger(1, password.toByteArray(Charsets.UTF_8)),
            decrypted,
        )
        assertEquals(password, String(decrypted.toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun encryptedHex_isLowercaseHexAndNotPkcs1Padding() {
        val hex = RsaCipher.encryptToHex(
            plain = "MyPassw0rd",
            modulusBase64 = encode(publicKey.modulus),
            exponentBase64 = encode(publicKey.publicExponent),
        )
        assertTrue("输出必须是十六进制串：$hex", hex.matches(Regex("[0-9a-f]+")))
        assertTrue("1024 位模数的密文不会太长：${hex.length}", hex.length <= 256)
    }

    /**
     * 用**真实探测到的**广州软件学院公钥跑一遍。
     *
     * 这条同时守住一个很隐蔽的坑：教务系统下发的 modulus 是 DER 正整数补位过的
     * base64，解出来首字节是 `0x00`（129 字节而不是 128）。
     * 如果按有符号解析（`BigInteger(bytes)`），模数会变成负数，
     * 算出来的密文服务端永远解不开 —— 而错误表现依然只是"用户名或密码错误"。
     */
    @Test
    fun realGzusPublicKey_isAccepted() {
        // 实测取自 https://jwxt.gzus.edu.cn/jwglxt/xtgl/login_getPublicKey.html
        val modulusBase64 =
            "AJmZRrDzc9enqyXSBWdp5yt/cJyw2zgwNVxMUtpCgRNyY8imYoe6MJme9m7Jes0eMVyaFnSmobK4Gcv1t9QGMM9L+J5LjVu+TQZ+OmemT7qsOFb/Neh7fBUrffYT9JbnF9D0LzEjhzn/YgxoljJbOw5TpiGuUhdXvmxpjFPr+YZR"
        val exponentBase64 = "AQAB"

        val raw = Base64.getMimeDecoder().decode(modulusBase64)
        assertEquals("DER 补位：首字节应为 0x00", 0.toByte(), raw[0])
        assertEquals("1024 位模数 + 1 个补位字节", 129, raw.size)
        assertEquals("exponent 应是 65537", BigInteger(1, Base64.getMimeDecoder().decode(exponentBase64)), BigInteger.valueOf(65537))

        val hex = RsaCipher.encryptToHex("test1234", modulusBase64, exponentBase64)
        assertTrue("密文长度不该超过模数位数：${hex.length}", hex.length <= 256)
    }
}
