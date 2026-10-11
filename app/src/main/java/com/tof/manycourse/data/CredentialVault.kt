package com.tof.manycourse.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * **「记住密码」的落盘仓库**：Android Keystore 里的密钥 + AES-GCM 密文。
 *
 * ## 为什么不能明文存、也不能自己"加密"一下
 *
 * 存进来的是**能直接登录教务系统的账号密码**。明文写进 SharedPreferences 的话，
 * root 设备、`adb backup`、云备份都能直接拿走；自己写个"异或 / Base64"更糟 ——
 * 密钥就写在反编译就能看到的代码里，安全性是零，却给用户一种"已经加密了"的错觉。
 *
 * 项目**不引入新依赖**（`settings.gradle.kts` 是 FAIL_ON_PROJECT_REPOS，只有
 * google() + mavenCentral()），所以用不了 `EncryptedSharedPreferences`；
 * 而"加密一小段文本"这件事，Keystore + AES/GCM 本身就够了。
 * （会话存档 `SessionVault` 走的是同一套路子，但**密钥、prefs、生命周期都各管各的**，
 * 见下面"为什么另开一把密钥"。）
 *
 * ## 密钥怎么生成、怎么存、怎么删
 *
 * - **生成**：`AndroidKeyStore` 里生成 AES-256-GCM 密钥，`setUserAuthenticationRequired(false)`
 *   （见下面那条取舍）。密钥**不可导出**：应用只能"请求它加解密"，拿不到密钥本身，
 *   硬件支持时它由 TEE/StrongBox 保管；
 * - **存**：密钥**不落盘到应用目录**（这是 Keystore 的意义），prefs 里只放密文
 *   —— `base64(IV):base64(密文)`。GCM 每次加密都用新的随机 IV，所以同一段明文
 *   每次存出来的密文都不一样（这也是把 IV 一起存下来的原因）；
 * - **删**：`clear()` 把密文与**Keystore 里的密钥一起删掉**。只删密文是不行的：
 *   密钥留在 Keystore 里等于"本机还躺着一把能解开别人备份里那段密文的钥匙"。
 *   删密钥**不可逆** —— 这正是我们要的性质：清掉之后连应用自己都解不开；
 * - **卸载 / 清除数据即失效**：密钥属于应用私有数据，卸载或"清除数据"时系统一并抹掉，
 *   残留在别处的密文（如果有）从此解不开。这不是缺陷，而是"本机凭据"应有的边界。
 *
 * ## 为什么另开一把密钥（不复用会话那把）
 *
 * 用途与生命周期都不一样：会话密钥管的是"这次登录的通行证"（`SessionVault`），
 * 凭据密钥管的是"下次登录要用的密码"。用户取消勾选「记住密码」时要删的只是后者 ——
 * 复用一把密钥的话，"忘记密码"就会把**当前会话**一起废掉，人立刻被踢回登录页。
 * 同理，其中一把被系统作废（清锁屏密码、恢复出厂、换设备）也不该连带另一把。
 *
 * ## ★ 那条取舍：`setUserAuthenticationRequired(false)`
 *
 * 也就是说：**设备解锁之后，拿到手机的人就能用这个自动填充**（他不会看到密码明文，
 * 但能直接登进去）。要求每次解密都过指纹/密码的话，"打开就填好"这件事就不成立了 ——
 * 这两件事只能选一个，这里选"填好"，代价写进登录页那句提示里（`login_page_remember_password_hint`）。
 * 不写清这句的话，用户会以为"记住了密码 = 只有我能用"。
 *
 * ## 为什么"只自动填、不自动登录"
 *
 * 会话还在的时候根本走不到登录页（`MainActivity` 一开始就按 `SessionStore.isLoggedIn`
 * 跳过去了），所以"记住密码"真正解决的只是**会话没了之后重新输一遍**这件事。
 * 如果这时替用户把登录也做了：
 *  - 登录可能失败（密码改了、账号被锁、学校要求验证码），而用户连"我点过登录"这个前提都没有，
 *    只会看到一个莫名奇妙的报错；
 *  - "谁在什么时候用了教务系统"这件事就变得不透明 —— 一次悄悄发生的登录，
 *    在学校的登录日志里和用户手动点的一次长得一模一样。
 */
internal object CredentialVault {

    private const val TAG = "CredentialVault"
    private const val PREFS_NAME = "manycourse_credential_prefs"
    private const val KEY_BLOB = "credential_blob"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /** **与会话那把分开**（理由见类注释）；改名字等于让所有已存的凭据作废，别随手改 */
    private const val KEY_ALIAS = "manycourse.credential.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** IV 与密文之间的分隔符；两者都是 base64，不会出现冒号 */
    private const val BLOB_SEPARATOR = ':'

    /** 账号与密码之间的分隔符（只在**密文内部**出现，prefs 里看不到）*/
    private const val CREDENTIAL_SEPARATOR = '\n'

    private var prefs: SharedPreferences? = null

    /**
     * 幂等初始化。
     *
     * 只有登录页会用它，所以由 `MainActivity.onCreate` 自己 attach ——
     * 不像 `SessionStore` 那样必须赶在**任何界面之前**恢复（"要不要显示登录页"取决于它）。
     */
    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 存下来的那份凭据（明文只在内存里过一手，随调用方一起消失）*/
    internal data class RememberedCredential(val account: String, val password: String)

    /**
     * 保存凭据。**只在登录成功那一刻调**：存下来的必须是验证过的账号密码，
     * 否则下次自动填进去的是一份错的。
     *
     * @return false = 没存成（Keystore 不可用等）。调用方不必做什么 ——
     *   最坏的结果是"这次没记住"，而不是登录失败
     */
    fun save(account: String, password: String): Boolean {
        val store = prefs ?: return false
        return try {
            val packed = encrypt(secretKey(), encodeCredential(account, password))
            store.edit().putString(KEY_BLOB, packed).commit()
        } catch (e: Exception) {
            Log.w(TAG, "凭据加密失败，跳过保存：${e.message}")
            false
        }
    }

    /**
     * 读回凭据；**没有存档 / 解不开都返回 null**。
     *
     * 解不开（密钥被系统作废、存档被改过、换了设备）时顺手清掉整份存档 ——
     * 留着只会每次启动都白试一遍。绝不在这里抛异常：这是登录页的启动路径，
     * 崩在这里等于"应用打不开"。
     */
    fun load(): RememberedCredential? {
        val store = prefs ?: return null
        val packed = store.getString(KEY_BLOB, null) ?: return null
        val plain = try {
            decrypt(secretKey(), packed)
        } catch (e: Exception) {
            Log.w(TAG, "凭据读取失败：${e.message}")
            null
        }
        if (plain == null) {
            Log.w(TAG, "凭据解不开（密钥已作废 / 存档损坏），已清除")
            clear()
            return null
        }
        val credential = decodeCredential(plain)
        if (credential == null) {
            Log.w(TAG, "凭据格式不对，已清除")
            clear()
        }
        return credential
    }

    /**
     * **真的删掉**：密文 + Keystore 里的密钥。
     *
     * 用户在登录页取消勾选时立刻调用（而不是等他下次登录）—— "取消勾选"就是
     * "把本机存的那份密码删掉"，这件事不该拖到某个未来时刻才发生。
     */
    fun clear() {
        prefs?.edit()?.remove(KEY_BLOB)?.apply()
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }.onFailure { Log.w(TAG, "删除凭据密钥失败：${it.message}") }
    }

    // ── 加解密：**刻意不碰任何 Android API**，于是往返与防篡改都能在 JVM 单测里跑 ──

    /**
     * 加密并打包成 `base64(IV):base64(密文)`。
     *
     * 用 `java.util.Base64` 而不是 `android.util.Base64`：后者在 JVM 单测里是个空壳
     * （调用即抛 "not mocked"），而这段打包/解包逻辑正是最该被测的部分。
     * minSdk 31 ⇒ JDK 那套 Base64 一定有。
     */
    internal fun encrypt(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) + BLOB_SEPARATOR + encode(cipherText)
    }

    /**
     * 解密；**解不开一律返回 null**，不抛异常、也不打日志（副作用留给调用方）。
     *
     * GCM 自带完整性校验：密文被改过一个字节，这里就会失败而不是解出一段乱码 ——
     * 所以"存档被人动过"与"密钥不对"是同一个结果：当作没有存档。
     */
    internal fun decrypt(key: SecretKey, packed: String): String? {
        val at = packed.indexOf(BLOB_SEPARATOR)
        if (at <= 0 || at == packed.lastIndex) return null
        return runCatching {
            val iv = decode(packed.substring(0, at))
            val cipherText = decode(packed.substring(at + 1))
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * 账号与密码打包成一段明文，**整段一起进密文**。
     *
     * 为什么不把账号明文放进 prefs：这个项目的规矩是"别让学号明文落在 prefs 里"
     * （见 `ScheduleVault.prefKey` 那条注释）。账号单独存的话，
     * 光看 pref 文件就知道这台机器上是谁的课表 —— 没必要暴露到这个程度。
     */
    internal fun encodeCredential(account: String, password: String): String =
        account + CREDENTIAL_SEPARATOR + password

    /** 拆回账号与密码；格式不对（没有分隔符 / 密码为空）返回 null，当作"没有存档" */
    internal fun decodeCredential(plain: String): RememberedCredential? {
        val at = plain.indexOf(CREDENTIAL_SEPARATOR)
        if (at <= 0) return null
        val password = plain.substring(at + 1)
        if (password.isEmpty()) return null
        return RememberedCredential(account = plain.substring(0, at), password = password)
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    /**
     * 取（必要时生成）Keystore 里的 AES-256 密钥。
     *
     * 不设 `setUserAuthenticationRequired`：登录页要"打开就填好"，要求指纹/密码才能解密
     * 会直接破坏这件事。这条取舍的代价写在类注释里，也写在登录页那句提示上。
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
