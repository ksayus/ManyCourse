package com.tof.manycourse.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 课表快照的落盘仓库 —— 与 [SessionVault] 同一套做法（Keystore + AES/GCM），
 * 但**刻意用另一份 prefs 和另一个 key alias**。
 *
 * ## 为什么必须分开
 *
 * 会话存档和课表数据的**生命周期不一样**（这正是本次需求的核心）：
 *
 * | | 会话（凭证） | 课表（数据） |
 * |---|---|---|
 * | 服务端过期 | 已经没用了 | **还有用，要继续能看** |
 * | 用户主动退出登录 | 必须清 | 留着（下次同账号登录立刻可见） |
 * | 密钥被系统作废 | 清 | 跟着清（解不开就是解不开，不必为此加复杂度） |
 *
 * 存进同一个 key 的话，"会话失效"这条路径上的任何一次 `clear()` 都会顺手抹掉课表 ——
 * 那本次需求就白做了。
 *
 * ## 与 SessionVault 的两处有意不同
 *
 * - **`apply()` 而不是 `commit()`**：会话存不上就得重新登录，所以那边同步落盘；
 *   课表存不上顶多下次联网重拉，不值得在一次同步回调里阻塞主线程。
 * - **按账号分档**：一档 = 一个 `学校id|账号`（`profileStorageKey` 的口径），
 *   这台手机是多人轮流用的，只有一份全局存档就会串号；
 *   实际写进 prefs 的键是它的 **SHA-256**（见 [prefKey]）—— 学号不能明文落在键里。
 *
 * @see SessionVault
 */
internal object ScheduleVault {

    private const val TAG = "ScheduleVault"
    private const val PREFS_NAME = "manycourse_schedule_prefs"
    private const val KEY_PREFIX = "snapshot:"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "manycourse.schedule.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private var prefs: SharedPreferences? = null

    /** 幂等初始化，由 `ManyCourseApp.onCreate` 调用 */
    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 加密存盘（异步）；返回 false 表示没存成，调用方不必管 */
    fun save(storageKey: String, plain: String): Boolean {
        val store = prefs ?: return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                    ":" + Base64.encodeToString(cipherText, Base64.NO_WRAP)
            store.edit().putString(prefKey(storageKey), packed).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "课表快照加密失败，跳过本次持久化：${e.message}")
            false
        }
    }

    /** 读盘解密；这一档没有 / 解不开都返回 null */
    fun load(storageKey: String): String? {
        val store = prefs ?: return null
        val packed = store.getString(prefKey(storageKey), null) ?: return null
        val at = packed.indexOf(':')
        if (at <= 0) {
            clear(storageKey)
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
            // 密钥被系统作废 / 存档损坏：丢掉这一档，回落成"没有缓存"
            Log.w(TAG, "课表快照解密失败，已丢弃这一档：${e.message}")
            clear(storageKey)
            null
        }
    }

    /** 只清这一档（换账号 / 用户主动清缓存时用）*/
    fun clear(storageKey: String) {
        prefs?.edit()?.remove(prefKey(storageKey))?.apply()
    }

    /**
     * 「学校id|账号」→ SharedPreferences 的键。
     *
     * ★ **必须哈希，不能直接用**：`storageKey` 里含**学号**，直接当键等于把学号明文写进
     * prefs 文件 —— 值是加密的，键不是，而 §10.2 立的规矩是"账号也只存在加密存档里"。
     * 哈希之后键变成一串无意义的 44 字符，认不出是谁。
     *
     * 恢复不受影响：快照内容里本来就带 `schoolId` / `account`（见 `ScheduleSnapshot`），
     * 键只用来定位那一档。
     *
     * ⚠️ 这是**一次性破坏性改动**：老版本留下的明文键（`snapshot:学校|账号`）读不到了，
     * 表现为"第一次冷启动没有缓存"（重新同步一次就回来了）。缓存本来就是可丢弃数据，
     * 不值得为它写迁移。
     */
    private fun prefKey(storageKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(storageKey.toByteArray(Charsets.UTF_8))
        return KEY_PREFIX + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    /** 取（必要时生成）本模块自己的 AES-256 密钥；与会话那把完全独立 */
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
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}