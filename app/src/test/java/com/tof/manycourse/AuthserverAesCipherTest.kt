package com.tof.manycourse

import com.tof.manycourse.api.AuthserverAesCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 金智统一身份认证（authserver）密码加密的**逐字节回归**（JVM，无需设备）。
 *
 * 这一层为什么值得单独钉死：密码算错时服务端只会回一句
 * 「用户名或密码错误」——和"密码真的输错了"**长得一模一样**，
 * 用户不可能自查，我们也拿不到任何线索。所以这里用**固定随机数**
 * 跑一遍，跟 Node 的 `crypto`（等价于页面里的 CryptoJS）算出来的
 * 密文逐字节比对。
 *
 * 期望值怎么来的：把页面 `encrypt.js` 里的
 * `getAesString(randomString(64) + 密码, 盐, randomString(16))`
 * 换成固定的前缀 / IV，用 Node 的 `crypto.createCipheriv('aes-128-cbc', …)`
 * 算出来的 base64（见 `tools/gdut_probe.mjs` 的 `crypto` 模式）。
 */
class AuthserverAesCipherTest {

    /** 广工登录页实测下发的盐形态（16 位 ASCII，每次刷新都变）*/
    private val salt = "ohANYJcqxqvvFJ31"

    /** 固定下来的"随机"前缀：把字符表原样重复一遍再截 64 位 */
    private val prefix =
        "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678ABCDEFGHJKMNPQRS"

    private val iv = "abcdefghijkmnpqr"

    @Test
    fun encryptsExactlyLikeTheBrowser() {
        val encrypted = AuthserverAesCipher.encrypt("Test@1234", salt, prefix, iv)
        assertEquals(
            "与页面 encrypt.js 的算法必须逐字节一致（AES-128-CBC/PKCS7 + 64 位前缀 + 16 位 IV）",
            "mPd1sJAUk9pI5pooNmmbPdpMVn4OAPpqjI8eACoRJLkqEQFmp8Om7YUWS6iWNk6wfH12UOguDnT0daFezkZ9j6/LFsxc011+EhigpO+gKo0=",
            encrypted,
        )
    }

    @Test
    fun emptyPasswordStillProducesACiphertext() {
        // 空密码不会被上层拦下来（SchoolApi 只管非空校验的顺序），这里钉住"不会炸、也不会回明文"
        val encrypted = AuthserverAesCipher.encrypt("", salt, prefix, iv)
        assertEquals(
            "mPd1sJAUk9pI5pooNmmbPdpMVn4OAPpqjI8eACoRJLkqEQFmp8Om7YUWS6iWNk6wfH12UOguDnT0daFezkZ9j8SRiqdhLJv0eM4RX+duBJ4=",
            encrypted,
        )
    }

    @Test
    fun nonAsciiPasswordIsUtf8Encoded() {
        // 中文密码要把多字节字符正确喂进 AES（CryptoJS 那边走的是 UTF-8）
        val encrypted = AuthserverAesCipher.encrypt("密码Abc123", salt, prefix, iv)
        assertEquals(
            "mPd1sJAUk9pI5pooNmmbPdpMVn4OAPpqjI8eACoRJLkqEQFmp8Om7YUWS6iWNk6wfH12UOguDnT0daFezkZ9j5NtO9XyOV3Rsz8/tOTXt+Y=",
            encrypted,
        )
    }

    @Test
    fun ciphertextLengthMatchesTheCapturedTraffic() {
        // 实测抓包：某种长度的密码 → 80 字节密文。这里钉住"密文 = 前缀 + 密码 + PKCS7 填充"这条关系，
        // 前缀长度一旦写错（比如照新版金智写成别的位数），密文长度立刻不对
        val bytes = Base64.getDecoder().decode(AuthserverAesCipher.encrypt("123456".repeat(2), salt, prefix, iv))
        assertEquals("64 位前缀 + 12 位密码 = 76 → 填充到 80 字节", 80, bytes.size)
    }

    @Test
    fun saltIsTrimmedAndMustBe16Bytes() {
        // 页面 JS 里有一句 key0.replace(/(^\s+)|(\s+$)/g, "")，照抄
        assertEquals(
            "盐首尾的空白必须去掉（页面 JS 就是这么做的）",
            AuthserverAesCipher.encrypt("Test@1234", salt, prefix, iv),
            AuthserverAesCipher.encrypt("Test@1234", "  $salt ", prefix, iv),
        )

        // 长度不对时必须**立刻炸**，不能拿着错盐去提交：那样服务端只会回"密码错误"
        val error = runCatching { AuthserverAesCipher.encrypt("Test@1234", "short", prefix, iv) }
        assertTrue("盐长度不对时应当抛 IllegalArgumentException", error.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun randomStringFollowsTheBrokenCharsetOfThePage() {
        val value = AuthserverAesCipher.randomString(64)
        assertEquals(64, value.length)
        val allowed = AuthserverAesCipher.CHARS.toSet()
        value.forEach { c ->
            assertTrue("随机串字符必须来自页面那张（残缺的）字符表，出现了 $c", c in allowed)
        }
        // 字符表里确实没有这些易混字符 —— 如果哪天换了字符表，这条会先红
        assertTrue("字符表不含 I/O/l/0/1/9（照抄自页面 \$aes_chars）",
            listOf('I', 'O', 'l', '0', '1', '9').none { it in allowed })
    }

    @Test
    fun everyEncryptUsesFreshRandomness() {
        // 同一个密码连加密两次必须不同：否则"前缀 + IV 都是随机"这条就退化了
        assertNotEquals(
            AuthserverAesCipher.encrypt("Test@1234", salt),
            AuthserverAesCipher.encrypt("Test@1234", salt),
        )
    }
}
