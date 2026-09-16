package com.tof.manycourse.api

import java.math.BigInteger
import java.security.SecureRandom

/**
 * SM3 密码杂凑算法（GB/T 32905-2016）。
 *
 * 为什么自己写：金城学院教务系统的登录密码是 **SM2** 加密的，而 SM2 的
 * C3 = SM3(x2 ‖ M ‖ y2)、KDF 也基于 SM3。国密算法不在 JDK / Android 标准库里，
 * 项目又不引入 BouncyCastle（一个 6MB 的依赖，只为两次加密），所以这里按标准实现。
 *
 * 正确性由 `Sm3DigestTest` 用**国标附录 A 的官方向量**守着
 * （`SM3("abc")` = `66c7f0f4…8f4ba8e0`）。
 */
object Sm3Digest {

    /** 初始值 IV（GB/T 32905-2016 4.1） */
    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
    )

    /** 每轮的常量 T_j：前 16 轮与其余轮各一个值 */
    private const val T0 = 0x79cc4519
    private const val T1 = 0x7a879d8a

    /** 对 [data] 求 SM3 摘要，返回 32 字节 */
    fun hash(data: ByteArray): ByteArray {
        // ── 填充：0x80 + 若干 0x00 + 64 位大端比特长度 ──────────────────
        val bitLength = data.size.toLong() * 8
        val padded = ((data.size + 9 + 63) / 64) * 64
        val msg = ByteArray(padded)
        data.copyInto(msg)
        msg[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            msg[padded - 1 - i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
        }

        // ── 逐块压缩 ────────────────────────────────────────────────────
        val v = IV.copyOf()
        val w = IntArray(68)
        val w1 = IntArray(64)

        var offset = 0
        while (offset < padded) {
            for (j in 0 until 16) {
                val p = offset + j * 4
                w[j] = ((msg[p].toInt() and 0xFF) shl 24) or
                    ((msg[p + 1].toInt() and 0xFF) shl 16) or
                    ((msg[p + 2].toInt() and 0xFF) shl 8) or
                    (msg[p + 3].toInt() and 0xFF)
            }
            for (j in 16 until 68) {
                // W_j = P1(W_{j-16} ^ W_{j-9} ^ (W_{j-3} <<< 15)) ^ (W_{j-13} <<< 7) ^ W_{j-6}
                w[j] = p1(w[j - 16] xor w[j - 9] xor Integer.rotateLeft(w[j - 3], 15)) xor
                    Integer.rotateLeft(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]

            var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
            var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]

            for (j in 0 until 64) {
                val t = if (j < 16) T0 else T1
                val a12 = Integer.rotateLeft(a, 12)
                // SS1 = ((A <<< 12) + E + (T_j <<< (j mod 32))) <<< 7
                val ss1 = Integer.rotateLeft(a12 + e + Integer.rotateLeft(t, j % 32), 7)
                val ss2 = ss1 xor a12
                val tt1 = ff(a, b, c, j) + d + ss2 + w1[j]
                val tt2 = gg(e, f, g, j) + h + ss1 + w[j]
                d = c
                c = Integer.rotateLeft(b, 9)
                b = a
                a = tt1
                h = g
                g = Integer.rotateLeft(f, 19)
                f = e
                e = p0(tt2)
            }

            v[0] = v[0] xor a; v[1] = v[1] xor b; v[2] = v[2] xor c; v[3] = v[3] xor d
            v[4] = v[4] xor e; v[5] = v[5] xor f; v[6] = v[6] xor g; v[7] = v[7] xor h

            offset += 64
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (v[i] ushr 24).toByte()
            out[i * 4 + 1] = (v[i] ushr 16).toByte()
            out[i * 4 + 2] = (v[i] ushr 8).toByte()
            out[i * 4 + 3] = v[i].toByte()
        }
        return out
    }

    /** 置换函数 P0(x) = x ^ (x <<< 9) ^ (x <<< 17) */
    private fun p0(x: Int): Int = x xor Integer.rotateLeft(x, 9) xor Integer.rotateLeft(x, 17)

    /** 置换函数 P1(x) = x ^ (x <<< 15) ^ (x <<< 23) */
    private fun p1(x: Int): Int = x xor Integer.rotateLeft(x, 15) xor Integer.rotateLeft(x, 23)

    /** 布尔函数 FF：前 16 轮为异或，其余轮为多数表决 */
    private fun ff(x: Int, y: Int, z: Int, j: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x and z) or (y and z)

    /** 布尔函数 GG：前 16 轮为异或，其余轮为选择 */
    private fun gg(x: Int, y: Int, z: Int, j: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x.inv() and z)
}

/**
 * SM2 公钥加密（GB/T 32918.4-2016），输出格式为 **C1C2C3 拼接的十六进制**，
 * 且带 `04` 前缀（未压缩点）。
 *
 * 这两点都是**照着金城学院教务系统页面里的 `sm2.js` 抄的**，不是自己挑的：
 *
 * ```js
 * // /Mvc/Scripts/js/Sm2/lib/sm2.js
 * function sm2Encrypt(data, publickey, cipherMode) {
 *     cipherMode = cipherMode == 0 ? cipherMode : 1;   // 页面传 0 → C1C2C3
 *     if (pubkeyHex.length > 64 * 2) pubkeyHex = pubkeyHex.substr(pubkeyHex.length - 64 * 2);
 *     ...
 *     return '04' + encryptData;
 * }
 * ```
 *
 * 页面上真正的调用是 `sm2Encrypt(account, pubkeyHex, 0)` —— `0` 就是
 * [SM2CipherMode.C1C2C3]。写成 C1C3C2（国标 `GB/T 32918.4` 的默认顺序）服务端解不开，
 * 而失败表现只是「用户名或密码错误」，与真的密码打错一模一样，极难排查。
 *
 * 正确性验证方式（`Sm2CipherTest`）：Node 侧同一套算法、同一个公钥、同一个随机数
 * 算出的密文必须逐字节一致；Node 那版已用真机账号在
 * <https://jcjx.nhjcxy.edu.cn> 登录成功。
 */
object Sm2Cipher {

    // ── sm2p256v1 曲线参数（GB/T 32918.5-2017）────────────────────────────
    private val P = BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF", 16)
    private val A = BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC", 16)
    private val B = BigInteger("28E9FA9E9D9F5E344D5A9E4BCF6509A7F39789F515AB8F92DDBCBD414D940E93", 16)
    private val N = BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123", 16)
    private val G = EcPoint(
        BigInteger("32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7", 16),
        BigInteger("BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0", 16),
    )

    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)

    /**
     * 用 [publicKeyHex]（服务端下发的十六进制 x‖y，可能带 `04` 前缀）加密 [plain]。
     *
     * @return 小写十六进制密文，形如 `04 ‖ x1 ‖ y1 ‖ C2 ‖ C3`
     */
    fun encryptToHex(plain: String, publicKeyHex: String): String =
        encryptToHex(plain, publicKeyHex, randomScalar())

    /**
     * 指定随机数 k 的版本。
     *
     * 随机数可注入是为了**可测**：SM2 每次加密结果都不同（k 是随机的），
     * 单测没法断言"等于某个固定值"。把 k 固定住以后就是确定性函数，
     * 才能和 Node 侧参考实现逐字节比对。
     */
    internal fun encryptToHex(plain: String, publicKeyHex: String, k: BigInteger): String {
        val publicPoint = parsePublicKey(publicKeyHex)
        val msg = plain.toByteArray(Charsets.UTF_8)

        // C1 = k·G
        val c1 = multiply(k, G) ?: throw IllegalArgumentException("SM2: k·G 落在无穷远点")
        // 共享点 S = k·P
        val shared = multiply(k, publicPoint) ?: throw IllegalArgumentException("SM2: k·P 落在无穷远点")

        val x2 = shared.x.toFixed32()
        val y2 = shared.y.toFixed32()
        // C2 = M ⊕ KDF(x2 ‖ y2, |M|)
        val mask = kdf(x2, y2, msg.size)
        val c2 = ByteArray(msg.size) { i -> (msg[i].toInt() xor mask[i].toInt()).toByte() }
        // C3 = SM3(x2 ‖ M ‖ y2)
        val c3 = Sm3Digest.hash(x2 + msg + y2)

        return "04" + c1.x.toFixed32().toHex() + c1.y.toFixed32().toHex() + c2.toHex() + c3.toHex()
    }

    /** 解析公钥十六进制：超过 128 个字符时**只取最后 128 个**（剥掉 `04` 前缀），与服务端 JS 一致 */
    private fun parsePublicKey(publicKeyHex: String): EcPoint {
        val hex = publicKeyHex.trim()
            .let { if (it.length > 128) it.substring(it.length - 128) else it }
        require(hex.length == 128 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "SM2 公钥应为 128 位十六进制（x‖y），实际 ${hex.length} 位：$publicKeyHex"
        }
        val point = EcPoint(BigInteger(hex.substring(0, 64), 16), BigInteger(hex.substring(64), 16))
        // 校验公钥确实在曲线上：省得把服务端下发的乱码一路带到"密码错误"
        require(point.isOnCurve()) { "SM2 公钥不在 sm2p256v1 曲线上" }
        return point
    }

    /** KDF：ct 从 1 开始、4 字节大端，逐块 SM3(x2 ‖ y2 ‖ ct)，取前 [length] 字节 */
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

    /** 取 [1, n-1] 上的随机标量 */
    private fun randomScalar(): BigInteger {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return BigInteger(1, bytes).mod(N - BigInteger.ONE).add(BigInteger.ONE)
    }

    // ── 椭圆曲线点运算（仿射坐标）────────────────────────────────────────

    private class EcPoint(val x: BigInteger, val y: BigInteger) {
        /** y² ≡ x³ + ax + b (mod p) */
        fun isOnCurve(): Boolean =
            y.modPow(TWO, P) == (x.modPow(THREE, P) + A * x + B).mod(P)
    }

    /** 点加；返回 null 表示无穷远点 */
    private fun add(p: EcPoint?, q: EcPoint?): EcPoint? {
        if (p == null) return q
        if (q == null) return p
        if (p.x == q.x) return if (p.y == q.y) double(p) else null
        val lambda = (q.y - p.y).mod(P) * (q.x - p.x).mod(P).modInverse(P) % P
        val x = (lambda * lambda - p.x - q.x).mod(P)
        return EcPoint(x, (lambda * (p.x - x) - p.y).mod(P))
    }

    /** 倍点；返回 null 表示无穷远点 */
    private fun double(p: EcPoint?): EcPoint? {
        if (p == null || p.y.signum() == 0) return null
        val lambda = (THREE * p.x * p.x + A).mod(P) * (TWO * p.y).mod(P).modInverse(P) % P
        val x = (lambda * lambda - TWO * p.x).mod(P)
        return EcPoint(x, (lambda * (p.x - x) - p.y).mod(P))
    }

    /** 标量乘（倍点-点加，256 位）*/
    private fun multiply(k: BigInteger, point: EcPoint): EcPoint? {
        var n = k.mod(N)
        var result: EcPoint? = null
        var base: EcPoint? = point
        while (n.signum() > 0) {
            if (n.testBit(0)) result = add(result, base)
            base = double(base)
            n = n.shiftRight(1)
        }
        return result
    }
}

/** 大端固定 32 字节（左边补零），与 SM2/SM3 的字节序约定一致 */
internal fun BigInteger.toFixed32(): ByteArray {
    val raw = toByteArray() // 可能带一个 0x00 符号补位
    val unsigned = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
    val out = ByteArray(32)
    unsigned.copyInto(out, 32 - unsigned.size)
    return out
}

/** 小写十六进制 */
internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return sb.toString()
}
