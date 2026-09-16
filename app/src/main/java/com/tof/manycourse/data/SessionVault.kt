package com.tof.manycourse.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 登录会话的落盘仓库 —— **用 Android Keystore 里的密钥做 AES-GCM 加密**。
 *
 * ## 为什么不直接写 SharedPreferences
 *
 * 存进来的东西是 Cookie 会话（`ASP.NET_SessionId`、老系统的 Forms 票据），
 * 它等价于**一张能直接以该用户身份访问教务系统的通行证** ——
 * 教务系统里躺着身份证号、家庭住址、成绩。
 * 明文写进 SharedPreferences 的话，root 设备、`adb backup`、云备份都能直接拿走，
 * 比存密码还危险（密码至少还得先过一遍服务端校验）。
 *
 * 项目在 `LoginSettings` 里已经立了规矩："**不存账号密码**，真要记住得走
 * EncryptedSharedPreferences / Keystore，不要图省事写明文"。
 * 这里就是那句话说的做法，而且**不引新依赖**：
 * `EncryptedSharedPreferences` 要拉 `androidx.security:security-crypto`，
 * 而我们只需要"加密一小段文本"，直接用 Keystore + AES/GCM 就够了。
 *
 * ## 实现要点
 *
 * - **密钥不落盘**：`AndroidKeyStore` 里的密钥由系统（TEE/StrongBox）保管，
 *   应用只能"用它加解密"，拿不到密钥本身；就算把 SharedPreferences 文件拷走也解不开。
 * - **GCM 而不是 CBC**：GCM 自带完整性校验，密文被改过会解密失败而不是解出乱码；
 *   代价是每次加密要带一个随机 IV，所以要把 IV 和密文一起存（`iv:ciphertext`）。
 * - **失败一律当作"没有会话"**：密钥被系统作废（比如用户清了锁屏密码、
 *   恢复出厂、换了设备）时解密会抛异常 —— 此时清掉存档、回到登录页，
 *   绝不让 App 崩在启动路径上。
 * - minSdk 31，Keystore 的 AES/GCM 完全可用，不需要任何兼容分支。
 */
internal object SessionVault {

    private const val TAG = "SessionVault"
    private const val PREFS_NAME = "manycourse_session_prefs"
    private const val KEY_BLOB = "session_blob"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "manycourse.session.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    private var prefs: SharedPreferences? = null

    /** 幂等初始化，由 `ManyCourseApp.onCreate` 调用 */
    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 加密存盘；返回 false 表示没存成（调用方不必管，最多下次要重新登录）*/
    fun save(plain: String): Boolean {
        val store = prefs ?: return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" + Base64.encodeToString(cipherText, Base64.NO_WRAP)
            store.edit().putString(KEY_BLOB, packed).commit()
        } catch (e: Exception) {
            Log.w(TAG, "会话加密失败，跳过持久化：${e.message}")
            false
        }
    }

    /** 读盘解密；没有存档 / 解不开都返回 null */
    fun load(): String? {
        val store = prefs ?: return null
        val packed = store.getString(KEY_BLOB, null) ?: return null
        val at = packed.indexOf(':')
        if (at <= 0) {
            clear()
            return null
        }
        return try {
            val iv = Base64.decode(packed.substring(0, at), Base64.NO_WRAP)
            val cipherText = Base64.decode(packed.substring(at + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥被系统作废 / 存档损坏：清掉，让用户重新登录，而不是反复报错
            Log.w(TAG, "会话解密失败，已清除存档：${e.message}")
            clear()
            null
        }
    }

    fun clear() {
        prefs?.edit()?.remove(KEY_BLOB)?.apply()
    }

    /**
     * 取（必要时生成）Keystore 里的 AES-256 密钥。
     *
     * 不设 `setUserAuthenticationRequired`：登录态要在「解锁后才能用手机」的场景下
     * 静默恢复，要求指纹/密码才能读会话会直接破坏"打开就是登录状态"这个需求。
     */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // GCM 本来就要求每次加密用新的随机 IV，显式声明一下，
                // 免得哪天误改成固定 IV 又被系统拦下来
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
