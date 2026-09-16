package com.tof.manycourse.api

import java.math.BigInteger

/**
 * 金智 CAS（统一身份认证）用的 RSA 加密。
 *
 * ## ⚠️ 它不是"标准 RSA"，三条都不标准 —— 全部照抄前端实现
 *
 * 算法是从 CAS 前端 bundle 里那个 RSA 工具模块（`util_rsa`）逐行读出来的：
 *
 * ```js
 * // 建 key：A(e,t,n) { this.e=m(e); this.d=m(t); this.m=m(n);
 * //                    this.chunkSize = 2*h(m); this.radix = 16 }
 * // 加密：P(key,text) {
 * //   while (o < a) n[o] = text.charCodeAt(o), o++;          // 明文变字节
 * //   while (n.length % key.chunkSize != 0) n[o++] = 0;      // ★ 末尾补零到 chunkSize 整数倍
 * //   c.digits[r] = n[l++]; c.digits[r] += n[l++] << 8;      // ★ 按小端装进 BigInt
 * //   var m = key.barrett.powMod(c, key.e);                  // ★ 裸模幂，没有任何填充
 * //   d += (key.radix == 16 ? biToHex(m) : …)                // ★ 输出十六进制
 * // }
 * ```
 *
 * 所以正确做法是 **裸模幂 + 小端 + 末尾补零 + 十六进制**：
 *
 * | | 正方教务系统（[RsaCipher]） | 金智 CAS（本文件） |
 * |---|---|---|
 * | 填充 | 无 | **无**（一样） |
 * | 明文字节序 | 大端（`BigInteger(1, bytes)`） | **小端**，且末尾补零到 `chunkSize` 的整数倍 |
 * | 输出 | 小写十六进制（不补零） | 小写十六进制，**按 4 位/16-bit digit 定长补零** |
 * | 公钥来源 | 服务端接口下发 | 前端 bundle 里硬编码（1024 位）|
 *
 * `chunkSize = 2 * h(modulus)`，`h()` 是"最高非零 16 位 digit 的下标"，
 * 也就是 `2 * ((bitLength - 1) / 16)`；实测这把 1024 位公钥的 chunkSize = **126**。
 *
 * ## 一个已知偏差（不影响正常密码）
 *
 * JS 用的是 `charCodeAt`（UTF-16 码元），对**非 ASCII 密码**会打包出畸形字节；
 * 这里用 UTF-8 —— 对纯 ASCII 密码两者完全一致，只对中文密码有差别，
 * 而那种情况在网页端本身也是坏的。留这条注释是为了以后有人拿中文密码试时能想起来。
 *
 * 纯 JVM 逻辑（不碰 Android API），单测覆盖见 `CasLoginSupportTest`。
 */
object CasRsaCipher {

    /**
     * @param plain        明文
     * @param modulusHex   模数，十六进制（CAS 的是 1024 位，bundle 里带前导 `00`）
     * @param exponentHex  指数，十六进制（`010001` = 65537）
     * @return 小写十六进制密文，长度固定为 `4 * (h + 1)`（这把公钥下是 256）
     */
    fun encryptToHex(plain: String, modulusHex: String, exponentHex: String): String {
        // 十六进制按**无符号正数**解析：bundle 里的模数以 00 开头，
        // 用有符号构造会解析成负数，密文服务端解不开
        val modulus = BigInteger(modulusHex.trim(), 16)
        val exponent = BigInteger(exponentHex.trim(), 16)
        require(modulus.bitLength() > 16) { "RSA 模数太小：${modulus.bitLength()} 位" }

        val chunkSize = chunkSizeOf(modulus)
        val bytes = plain.toByteArray(Charsets.UTF_8)

        // 末尾补零到 chunkSize 的整数倍（对照 JS 的 while (n.length % chunkSize != 0)）
        val paddedSize = ((bytes.size + chunkSize - 1) / chunkSize) * chunkSize
        val padded = bytes.copyOf(paddedSize)

        // 按小端解释成一个整数：digit 的下标越大权重越高，字节在 digit 内也是低字节在前
        var value = BigInteger.ZERO
        for (i in padded.indices.reversed()) {
            value = value.shiftLeft(8).or(BigInteger.valueOf((padded[i].toInt() and 0xFF).toLong()))
        }

        val cipher = value.modPow(exponent, modulus)
        // 定长补零：JS 的 biToHex 按 16-bit digit 逐个输出 4 位十六进制，
        // 所以高位 digit 的前导零也会被写出来
        return cipher.toString(16).padStart(4 * ((modulus.bitLength() - 1) / 16 + 1), '0')
    }

    /** `2 * h(modulus)`：h 是最高非零 16 位 digit 的下标 */
    private fun chunkSizeOf(modulus: BigInteger): Int = 2 * ((modulus.bitLength() - 1) / 16)
}
