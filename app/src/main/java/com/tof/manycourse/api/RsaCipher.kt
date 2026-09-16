package com.tof.manycourse.api

import java.math.BigInteger
import java.util.Base64

/**
 * 教务系统登录用的「裸 RSA」加密。
 *
 * 国内用这套的以**正方**（ZFSoft）为代表，流程固定三步：
 *  1. GET 登录页，拿到 `CSRFTOKEN` Cookie；
 *  2. GET 公钥接口（正方是 `/jwglxt/xtgl/login_getPublicKey.html`），返回
 *     `{"modulus":"<base64>","exponent":"<base64>"}`；
 *  3. 密码做 `c = m^e mod n`（**无填充**），结果转十六进制字符串填进表单。
 *     服务端拿 `new BigInteger(hex, 16)` 再用私钥 `m = c^d mod n` 解回来。
 *
 * 注意三点：
 *  - **不是** `RSA/ECB/PKCS1Padding`：那套是填随机数的，服务端解出来会带垃圾字节，登录必然失败。
 *    这里就是纯大数模幂，[BigInteger.modPow] 一把梭；
 *  - 公钥的 base64 解出来首字节常常是 `0x00`（DER 正整数补位），
 *    所以必须用 `BigInteger(1, bytes)`（正数）而不是有符号构造；
 *  - 输出用**小写**十六进制。服务端按 `BigInteger(hex, 16)` 解析，大小写都认，
 *    但小写和主流开源实现保持一致，方便抓包比对。
 *
 * 用 `java.util.Base64`（API 26+，本项目 minSdk 31）而不是 `android.util.Base64`，
 * 这样这段逻辑能直接在 JVM 单测里跑（见 `RsaCipherTest`）。
 */
object RsaCipher {

    /**
     * @param plain            明文密码（UTF-8 编码后当作大端无符号整数）
     * @param modulusBase64    公钥 modulus（base64）
     * @param exponentBase64   公钥 exponent（base64，常见值 `AQAB` = 65537）
     * @return 小写十六进制的密文
     */
    fun encryptToHex(plain: String, modulusBase64: String, exponentBase64: String): String {
        val modulus = BigInteger(1, decodeBase64(modulusBase64))
        val exponent = BigInteger(1, decodeBase64(exponentBase64))
        val message = BigInteger(1, plain.toByteArray(Charsets.UTF_8))
        return message.modPow(exponent, modulus).toString(16)
    }

    /**
     * 公钥里的 base64 可能带换行（部分是 DER 直接 base64），
     * MIME 解码比严格解码更宽容，省得因为一个 `\n` 抛异常。
     */
    private fun decodeBase64(value: String): ByteArray =
        Base64.getMimeDecoder().decode(value.trim())
}
