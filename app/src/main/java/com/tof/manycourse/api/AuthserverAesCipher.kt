package com.tof.manycourse.api

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * **金智教育统一身份认证平台（authserver）的登录密码加密**。
 *
 * 用它的学校：广东工业大学 <https://authserver.gdut.edu.cn>（见 `GdutApi`），
 * 以及所有部署了金智同一套 `authserver` 的学校
 * （南大 / 厦大 / 湖大 … 页面结构、字段名、加密函数一字不差）。
 *
 * ## 这套加密长什么样（**照抄自广工登录页的 `encrypt.js`**，不是猜的）
 *
 * ```js
 * function getAesString(data, key0, iv0) {
 *   key0 = key0.replace(/(^\s+)|(\s+$)/g, "");      // ← 盐要去掉首尾空白
 *   var key = CryptoJS.enc.Utf8.parse(key0);
 *   var iv  = CryptoJS.enc.Utf8.parse(iv0);
 *   return CryptoJS.AES.encrypt(data, key, {
 *     iv: iv, mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.Pkcs7,
 *   }).toString();                                  // ← 输出 base64
 * }
 * function encryptAES(data, aesKey) {
 *   return aesKey ? getAesString(randomString(64) + data, aesKey, randomString(16)) : data;
 * }
 * function encryptPassword(pwd, salt) { return encryptAES(pwd, salt); }
 * var $aes_chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";
 * ```
 *
 * 翻译成人话：**AES-128-CBC / PKCS7，key = 盐（16 位）、iv = 随机 16 位、
 * 明文 = 随机 64 位前缀 + 密码，最后整体 base64**。
 *
 * | 要素 | 值 | 为什么 |
 * |---|---|---|
 * | 算法 | `AES/CBC/PKCS5Padding` | 与 CryptoJS 的 `pad.Pkcs7` 等价（16 字节分组下 PKCS5 == PKCS7）|
 * | key | `pwdEncryptSalt`（页面隐藏域，**16 位，每次刷新都变**）| 必须现取现用，不能缓存 |
 * | iv | 随机 16 位字符 | **不随密文回传**，服务端解不开也无所谓 —— 它只做"别让密码明文过网" |
 * | 明文 | `randomString(64) + 密码` | 前缀只是把明文"顶开"，服务端解出来后自己截掉 |
 * | 输出 | base64（标准字母表，含 `+` `/` `=`）| 表单提交时由 urlencode 负责转义 |
 *
 * ## 三个真的会踩到的点
 *
 *  1. **盐的 id 各版本不同**：广工这版是 `<input id="pwdEncryptSalt">`，
 *     新版金智是 `pwdDefaultEncryptSalt`，还有的直接写
 *     `var pwdDefaultEncryptSalt = "…"`。`GdutScheduleParser.parseCasLoginForm`
 *     三种都认 —— 抓不到盐就别提交（必是"密码错误"，用户无从自查）。
 *  2. **64 位前缀不是可选项**。去掉它服务端解密后拿到的就是"密码 = 前缀+密码"，
 *     表现同样是"用户名或密码错误"。实测抓包里的密文长 80 字节 =
 *     (64 + 密码 + 填充) → 反推前缀长度正好 64。
 *  3. **`randomString` 的字符表是残缺的**（没有 `I` `O` `l` `0` `1` `9` 等易混字符）。
 *     服务端并不校验字符表（它只是截掉前 64 位），这里照抄一份是为了
 *     与浏览器行为逐字节可比 —— 排查问题时少一个变量。
 */
object AuthserverAesCipher {

    /** 随机前缀长度（浏览器：`randomString(64) + 密码`）*/
    const val PREFIX_LENGTH = 64

    /** IV 长度（AES 分组长度，浏览器：`randomString(16)`）*/
    const val IV_LENGTH = 16

    /** 盐的字节数；金智下发的盐恒为 16 位 ASCII */
    const val SALT_LENGTH = 16

    /**
     * 随机串字符表 —— **原样抄自页面里的 `$aes_chars`**。
     * 里面确实没有 `I` / `O` / `l` / `0` / `1` / `9`，这不是抄漏了。
     */
    const val CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEY_ALGORITHM = "AES"

    private val random = SecureRandom()

    /**
     * 把 [password] 加密成可以放进表单 `password` 字段的密文。
     *
     * @param salt 登录页隐藏域里的盐（[SALT_LENGTH] 位）；**每次登录都要重新取**
     * @return base64 密文
     * @throws IllegalArgumentException 盐长度不对（页面改版 / 解析失败）——
     *   宁可在这里炸掉让上层给一句人话，也不要提交一个"看起来像密文"的错值
     */
    fun encrypt(password: String, salt: String): String =
        encrypt(password, salt, randomString(PREFIX_LENGTH), randomString(IV_LENGTH))

    /**
     * 随机数可控的版本，**只给单元测试用**（生产代码走上面那个）。
     *
     * 分开写而不是加默认参数：默认参数会让"忘了传随机值"这种错误静默通过，
     * 而这里的随机值一旦退化成常量，密文就可预测了。
     */
    internal fun encrypt(password: String, salt: String, prefix: String, iv: String): String {
        val keyBytes = salt.trim().toByteArray(Charsets.UTF_8)
        require(keyBytes.size == SALT_LENGTH) {
            "统一身份认证的加密盐长度应为 $SALT_LENGTH 字节，实际 ${keyBytes.size} 字节（登录页结构可能变了）"
        }
        val ivBytes = iv.toByteArray(Charsets.UTF_8)
        require(ivBytes.size == IV_LENGTH) {
            "IV 长度应为 $IV_LENGTH 字节，实际 ${ivBytes.size} 字节"
        }

        val plain = (prefix + password).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, KEY_ALGORITHM),
            IvParameterSpec(ivBytes),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain))
    }

    /**
     * 从 [CHARS] 里随机取 [length] 个字符（对应页面里的 `randomString`）。
     *
     * 用 [SecureRandom] 而不是 `Random`：这两个值虽然不参与保密，
     * 但它们决定密文形态；用一个可预测的种子会让"每次登录密文不同"这件事失效。
     */
    fun randomString(length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        val out = StringBuilder(length)
        for (b in bytes) {
            // 取正数再取模：字符表长度 54，`b % 54` 在有符号字节上会得到负数
            out.append(CHARS[(b.toInt() and 0xFF) % CHARS.length])
        }
        return out.toString()
    }
}
