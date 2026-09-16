package com.tof.manycourse.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * 个人信息仓库（昵称 / 专业）—— **登录态级别的持久化**。
 *
 * ## 为什么必须按账号分开存
 *
 * 需求：*用户名更改后要本地保存，对应的账号有对应的存储信息*。
 *
 * 这个 App 是**多个账号轮流用同一台手机**的（同学之间借手机看课表很常见），
 * 所以资料不能只存一份"全局的"：
 *
 * ```
 *   20221101 登录 → 把昵称改成「小李」      → 存进 manycourse_profile_prefs 的 20221101 档
 *   退出 / 换 20221102 登录 → 昵称是「小王」→ 存进 20221102 档
 *   20221101 再登录        → 昵称仍是「小李」（不是学校系统里的「李某某」，也不是小王）
 * ```
 *
 * 存储键以 **`学校 id + 账号`** 为单位（见 [profileStorageKey]）：同一个学号在两所学校
 * 就不是同一个人，混在一起会让另一所学校看到上一所学校的名字。
 *
 * ## 谁能改昵称
 *
 * 两条写入路径，优先级不同：
 *
 * | 来源 | 方法 | 会不会覆盖用户改过的值 |
 * |---|---|---|
 * | 用户在「编辑资料」里改 | [setNickname] / [setMajor] | —— 它本来就是权威 |
 * | 教务系统同步回来的姓名 | [applySchoolProfile] | **不会**（用户改过就以用户为准） |
 *
 * 第二行是关键：登录后 `CourseSync` 每次都会把「学校系统里显示的名字」写进昵称，
 * 如果无条件覆盖，用户改完名字下次冷启动就被打回去了 —— "改了等于没改"。
 * 所以每个账号、每个字段各记一个"用户自己动过"的标记。
 *
 * ## 线程
 *
 * [attach] 在 `ManyCourseApp.onCreate` 里跑（只开一次 SharedPreferences，几毫秒），
 * 其余读写都是 O(1)，因此可以在主线程直接调，不需要 DataStore / 协程。
 */
object ProfileRepository {

    private const val PREFS_NAME = "manycourse_profile_prefs"

    /** 同上，但字段名要按账号分开，实际键是 `nickname:<账号键>` */
    private const val FIELD_NICKNAME = "nickname"
    private const val FIELD_MAJOR = "major"

    /** 「用户自己改过这一项」的标记键前缀；实际键是 `nickname_custom:<账号键>` */
    private const val FIELD_NICKNAME_CUSTOM = "nickname_custom"
    private const val FIELD_MAJOR_CUSTOM = "major_custom"

    /** 没登录 / 新账号还没同步到学校资料时的占位文案 */
    private const val DEFAULT_NICKNAME = "张同学"
    private const val DEFAULT_MAJOR = "计算机科学 · 2022级"

    /** 退出登录后的中性文案（不显示上一个人的名字）*/
    private const val LOGGED_OUT_NICKNAME = "未登录"

    private var storage: ProfileStorage? = null

    /** 当前资料**属于哪个账号**（`学校id|账号`）；null = 还没有账号绑定 */
    private var storageKey: String? = null

    private val nicknameState = mutableStateOf(DEFAULT_NICKNAME)
    private val majorState = mutableStateOf(DEFAULT_MAJOR)

    /**
     * 当前显示昵称。
     *
     * 对外**只读**：写得走 [setNickname]（会落盘），暴露成可写的 `MutableState`
     * 就等于给"改了但没存"留了一道后门 —— 这一条以前就是这么漏的。
     */
    val nickname: State<String> get() = nicknameState

    /** 当前显示的专业 / 年级。只读，原因同 [nickname] */
    val major: State<String> get() = majorState

    /** 幂等初始化，由 `ManyCourseApp.onCreate` 调用 */
    fun attach(context: Context) {
        if (storage != null) return
        storage = PrefsProfileStorage(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    }

    /**
     * 换一份存储实现（**只给单测用**）：`ProfileRepositoryTest` 用它换成内存版，
     * 这样"按账号分开存""用户改过的名字不被学校同步覆盖"这些规则能在普通 JVM 上跑。
     */
    internal fun attachStorage(newStorage: ProfileStorage) {
        storage = newStorage
        storageKey = null
    }

    /**
     * 绑定到某个账号：把**这个账号自己存过的**资料读回内存。
     *
     * 调用时机：冷启动恢复会话后（`ManyCourseApp`）、以及每次登录成功（`SessionStore.onLogin`）。
     * 账号为空（未登录）时清成默认文案。
     */
    fun bindAccount(schoolId: String?, account: String) {
        val key = profileStorageKey(schoolId, account)
        storageKey = key
        if (key == null) {
            nicknameState.value = DEFAULT_NICKNAME
            majorState.value = DEFAULT_MAJOR
            return
        }
        val s = storage
        nicknameState.value = s?.getString(scoped(FIELD_NICKNAME, key)) ?: DEFAULT_NICKNAME
        majorState.value = s?.getString(scoped(FIELD_MAJOR, key)) ?: DEFAULT_MAJOR
    }

    /**
     * 用户在「编辑资料」里保存昵称：立即生效 + 落盘到当前账号。
     *
     * 空白昵称直接忽略（表单已经拦过一次，这里是最后一道）。
     * 只有**值真的变了**才会打上"用户改过"的标记 —— 见 [write] 的注释。
     */
    fun setNickname(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        val changed = trimmed != nicknameState.value
        nicknameState.value = trimmed
        write(FIELD_NICKNAME, FIELD_NICKNAME_CUSTOM, trimmed, markCustomized = changed)
    }

    /**
     * 用户在「编辑资料」里保存专业 / 年级。
     *
     * 允许存空串：用户把这一栏清空就是"我不想填"，之后同步回来的专业也不该再填回去。
     */
    fun setMajor(value: String) {
        val trimmed = value.trim()
        val changed = trimmed != majorState.value
        majorState.value = trimmed
        write(FIELD_MAJOR, FIELD_MAJOR_CUSTOM, trimmed, markCustomized = changed)
    }

    /**
     * 教务系统同步回来的姓名 / 专业：**只在用户没亲手改过这一项时**才采用。
     *
     * 空值一律忽略（学校没给这一项时，不要把界面上显示的东西擦掉）。
     */
    fun applySchoolProfile(name: String, subtitle: String) {
        val key = storageKey
        if (name.isNotBlank() && !isCustomized(FIELD_NICKNAME_CUSTOM, key)) {
            nicknameState.value = name
        }
        if (subtitle.isNotBlank() && !isCustomized(FIELD_MAJOR_CUSTOM, key)) {
            majorState.value = subtitle
        }
    }

    /**
     * 退出登录：把界面清成中性文案，**但不动磁盘**。
     *
     * 磁盘上的东西是"那个账号的资料"，不是"上一个人的残渣"；删掉的话
     * 用户下次用回自己的账号会发现改过的昵称没了。
     */
    fun onLogout() {
        storageKey = null
        nicknameState.value = LOGGED_OUT_NICKNAME
        majorState.value = ""
    }

    private fun isCustomized(field: String, key: String?): Boolean =
        key != null && storage?.getBoolean(scoped(field, key), false) == true

    /**
     * 把一项资料写进当前账号的存档。
     *
     * @param markCustomized 是否同时打上"用户自己动过这一项"的标记。
     *   **只有值真的变了才打**：用户只是把「编辑资料」打开、顺手点了保存（昵称还是
     *   学校同步来的那个），不该把这一项永久锁死 —— 否则学校那边之后改了名字，
     *   这边就再也同步不过来了。
     *   标记**只会被设置、永远不会被清除**：一旦用户真的定过这一项，它就是权威。
     *
     * 没有绑定账号（未登录）时**只改内存、一个字都不写盘**：宁可不存，
     * 也不能把 A 的资料写到 B 的存档上。
     */
    private fun write(field: String, customField: String, value: String, markCustomized: Boolean) {
        val key = storageKey ?: return
        val s = storage ?: return
        s.put(scoped(field, key), value)
        if (markCustomized) s.put(scoped(customField, key), true)
    }

    /** 字段名 + 账号键拼成实际存储键（两个字段名不会互相前缀，不会串）*/
    private fun scoped(field: String, key: String): String = "$field:$key"
}

/**
 * 个人资料的**存储接口**。
 *
 * 为什么不直接在仓库里调 SharedPreferences：把这一层切开之后，
 * "按账号分开存""用户改过的名字不被学校同步覆盖""未登录时一个字都不写盘"
 * 这些规则可以用普通 JVM 单测钉住（见 `ProfileRepositoryTest`）——
 * SharedPreferences 要 Android 运行时，测不了；而这些规则错了**不会崩**，
 * 只会让名字串号，属于最该被测住的那一类。
 */
internal interface ProfileStorage {
    fun getString(key: String): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun put(key: String, value: String)
    fun put(key: String, value: Boolean)
}

/** 正式实现：SharedPreferences（同步读写、零额外依赖，与 [UiSettings] 同一套路子）*/
private class PrefsProfileStorage(private val prefs: SharedPreferences) : ProfileStorage {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun put(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

/**
 * **账号 → 存储键**，纯函数（可单测）。
 *
 * 规则：
 * - 账号为空 → `null`：没有身份就不该读写任何人的资料（比"存到空键上"安全得多，
 *   否则未登录时的一次误写会污染下一个登录的账号）；
 * - 有学校 → `学校id|账号`：同一学号在两所学校是两个人；
 * - 没有学校（本地调试账号 `admin`）→ 只用账号。
 */
internal fun profileStorageKey(schoolId: String?, account: String): String? {
    val user = account.trim()
    if (user.isEmpty()) return null
    val school = schoolId?.trim().orEmpty()
    return if (school.isEmpty()) user else "$school|$user"
}
