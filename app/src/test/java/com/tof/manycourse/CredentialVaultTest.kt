package com.tof.manycourse

import com.tof.manycourse.data.CredentialVault
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **「记住密码」的加密信封**（`data/CredentialVault.kt`）。
 *
 * 真机上这段逻辑的密钥在 Android Keystore 里（TEE/StrongBox 保管、不可导出），
 * JVM 单测里拿不到 —— 但**信封本身**（AES/GCM 的用法、IV 与密文的打包、
 * 账号与密码的拼装）一行 Android API 都不依赖，所以在这里用一把临时生成的
 * AES-256 密钥把"往返能不能通"和"被改过会不会装作没事"都验一遍。
 *
 * 密钥怎么生成、怎么存、怎么删（以及为什么删密钥而不是只删密文），
 * 写在 `CredentialVault` 的类注释里；这里验的是**密文这一侧的算术**。
 */
class CredentialVaultTest {

    /** 单测里的密钥：SUN provider 的 AES-256，和 Keystore 里那把是同一套算法/模式/填充 */
    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    // ══════════════════ 加解密往返 ══════════════════

    @Test
    fun 加密再解密_拿回原样() {
        val key = aesKey()
        // 中文、空格、符号、emoji 都得原样回来（密码里什么字符都可能有）
        listOf("2481", "P@ssw0rd!", "带中文的密码 123", "emoji🔐也算", "a".repeat(200)).forEach { plain ->
            val packed = CredentialVault.encrypt(key, plain)
            assertEquals("往返之后必须一模一样：$plain", plain, CredentialVault.decrypt(key, packed))
        }
    }

    @Test
    fun 同一个明文每次密文都不同_因为IV是随机的() {
        val key = aesKey()
        val first = CredentialVault.encrypt(key, "2481")
        val second = CredentialVault.encrypt(key, "2481")
        assertNotEquals("GCM 每次都用新 IV，密文不该相同", first, second)
        // 但两段都要能解回同一个明文
        assertEquals("2481", CredentialVault.decrypt(key, first))
        assertEquals("2481", CredentialVault.decrypt(key, second))
    }

    @Test
    fun 密文里不含明文_不是base64糊弄() {
        val key = aesKey()
        val password = "MySecretPassword"
        val packed = CredentialVault.encrypt(key, CredentialVault.encodeCredential("2023123456", password))

        // 把密文那一段解回字节，明文不该原样躺在里面（"异或 / Base64"那种假加密会）
        val cipherBytes = Base64.getDecoder().decode(packed.substringAfter(':'))
        val raw = String(cipherBytes, Charsets.ISO_8859_1)
        assertFalse("密文里不该出现明文密码", raw.contains(password))
        assertFalse("密文里不该出现明文账号", raw.contains("2023123456"))
    }

    // ══════════════════ 解不开的情形一律当作"没有存档" ══════════════════

    @Test
    fun 换一把密钥就解不开() {
        val packed = CredentialVault.encrypt(aesKey(), "2481")
        assertNull("换设备 / 密钥被系统作废之后，存档必须解不开", CredentialVault.decrypt(aesKey(), packed))
    }

    @Test
    fun 密文被改过一个字节就解不开_GCM自带完整性校验() {
        val key = aesKey()
        val packed = CredentialVault.encrypt(key, "2481")
        val iv = packed.substringBefore(':')
        val cipherText = packed.substringAfter(':')
        val tampered = Base64.getDecoder().decode(cipherText).copyOf().also { it[0] = (it[0] + 1).toByte() }
        val broken = iv + ":" + Base64.getEncoder().encodeToString(tampered)
        assertNull("被改过的密文不能解出乱码，必须直接失败", CredentialVault.decrypt(key, broken))
    }

    @Test
    fun 格式不对的存档解不开而不是抛异常() {
        val key = aesKey()
        listOf("", "没有分隔符", ":只有分隔符", "只有IV:", ":只有密文", "不是base64:也不是base64").forEach { broken ->
            assertNull("坏存档必须安静地返回 null：$broken", CredentialVault.decrypt(key, broken))
        }
    }

    // ══════════════════ 账号与密码的拼装 ══════════════════

    @Test
    fun 账号密码打包往返() {
        val credential = CredentialVault.decodeCredential(CredentialVault.encodeCredential("2023123456", "P@ss"))
        assertEquals("2023123456", credential?.account)
        assertEquals("P@ss", credential?.password)
    }

    @Test
    fun 账号里不会出现换行_密码里的换行也照样还原() {
        // 拼装用的是"第一个换行"，所以密码里真有换行也不会被切断
        val credential = CredentialVault.decodeCredential(CredentialVault.encodeCredential("stu01", "line1\nline2"))
        assertEquals("stu01", credential?.account)
        assertEquals("line1\nline2", credential?.password)
    }

    @Test
    fun 拼装不完整时当作没有存档() {
        assertNull(CredentialVault.decodeCredential(""))
        assertNull("没有分隔符 = 只有账号", CredentialVault.decodeCredential("2023123456"))
        assertNull("账号为空", CredentialVault.decodeCredential("\nP@ss"))
        assertNull("密码为空", CredentialVault.decodeCredential("2023123456\n"))
    }

    @Test
    fun 整条凭据往返_账号与密码各归各位() {
        val key = aesKey()
        val packed = CredentialVault.encrypt(key, CredentialVault.encodeCredential("2023123456", "P@ssw0rd"))
        val credential = CredentialVault.decodeCredential(CredentialVault.decrypt(key, packed).orEmpty())
        assertEquals("2023123456", credential?.account)
        assertEquals("P@ssw0rd", credential?.password)
        assertTrue("能读回来的才算记住了", credential != null)
    }
}
