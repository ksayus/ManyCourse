package com.tof.manycourse.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.tof.manycourse.gr_api.SchoolRegistry

/**
 * 当前登录会话（**进程内存 + 加密落盘**）。
 *
 * ## 「维持登录」是怎么做到的
 *
 * 教务系统的登录态 **完全在 Cookie 里**（`ASP.NET_SessionId` / 正方 `JSESSIONID` /
 * 老系统的 Forms 票据），所以不需要保存密码、也不需要重放登录请求：
 *
 * ```
 *   登录成功      → api.exportSession()  取出全部 Cookie
 *                 → SessionBlob 打包（学校 id + 账号 + Cookie）
 *                 → SessionVault 用 Keystore 密钥 AES-GCM 加密后落盘
 *
 *   App 冷启动    → SessionVault 解密 → SessionBlob 解开
 *                 → api.importSession(…) 把 Cookie 灌回会话
 *                 → MainActivity 看到「已登录」直接进主界面，不再要求输密码
 *
 *   会话在服务端过期（Cookie 存着但服务端不认了）
 *                 → 第一次拉数据时抛 SessionExpiredException
 *                 → CourseSync 置为 Expired，界面提示「登录已过期 + 重新登录」
 * ```
 *
 * **账号本身也只存在这个加密 blob 里**，不另外写明文 SharedPreferences ——
 * 学号是 PII，能少存一处就少存一处。学校 id 例外（`LoginSettings` 里已有，
 * 那是登录页下拉要用的，不含个人信息）。
 *
 * ## 线程
 *
 * [attach] 在 `Application.onCreate` 里同步跑（只读一次 SharedPreferences + 解密，
 * 几十毫秒级），这样首帧就知道要不要跳过登录页，不会闪一下登录界面。
 */
object SessionStore {

    private const val TAG = "SessionStore"

    /** 当前登录账号（学号 / 工号）；空串 = 未登录 */
    val account = mutableStateOf("")

    /** 会话所属的学校 id；恢复时按它找回同一个 `SchoolApi` 实例 */
    val schoolId = mutableStateOf<String?>(null)

    /** 本地调试账号（`admin`/`2481`）：不连教务系统，也不需要同步数据 */
    val isLocalDebug = mutableStateOf(false)

    /** 是否处于登录态（含"冷启动恢复出来的"登录态）*/
    val isLoggedIn: Boolean get() = account.value.isNotBlank()

    private var attached = false

    /**
     * 幂等初始化：由 `ManyCourseApp.onCreate` 在每个进程的第一时间调用。
     *
     * 恢复失败（没存档、密钥失效、格式对不上、学校已下架）一律静默回落到"未登录"，
     * 让用户走一次登录页 —— 这是唯一不会把用户困住的行为。
     */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        SessionVault.attach(context)
        restore()
    }

    private fun restore() {
        val raw = SessionVault.load() ?: return
        if (!SessionBlobCodec.hasOurHeader(raw)) {
            // 旧版本或者别人的格式：认不出来就丢掉，别拿它去灌 Cookie
            SessionVault.clear()
            return
        }
        val blob = SessionBlobCodec.decode(raw)
        if (blob == null) {
            SessionVault.clear()
            return
        }
        val api = SchoolRegistry.apiOf(blob.schoolId)
        if (api == null) {
            // 学校被下架了：老存档没意义了
            Log.i(TAG, "存档里的学校 ${blob.schoolId} 已不在清单里，丢弃会话")
            SessionVault.clear()
            return
        }
        if (!api.importSession(blob.cookies)) {
            SessionVault.clear()
            return
        }
        // 把 Cookie 灌回会话之后才算真恢复：先灌再置状态，避免出现
        // "看着已登录、其实会话是空的"这种半吊子状态
        schoolId.value = blob.schoolId
        account.value = blob.account
        isLocalDebug.value = blob.localDebug
        // 地图页 / 同步流程读的都是 LoginSettings，保持一致
        LoginSettings.setSelectedSchoolId(blob.schoolId)
        Log.i(TAG, "已恢复登录会话：${blob.schoolId} / ${blob.account}")
    }

    /**
     * 登录成功时调用：记下会话并立刻落盘。
     *
     * 必须在 `startActivity(ManyCourseMain)` **之前**调用 ——
     * 主界面 onCreate 会立刻拿 [schoolId] / [account] 去同步数据。
     */
    fun onLogin(school: String, userAccount: String, localDebug: Boolean) {
        schoolId.value = school
        account.value = userAccount
        isLocalDebug.value = localDebug
        CourseSync.reset()
        persist()
    }

    /**
     * 把当前会话重新落盘。
     *
     * 每次同步成功后都会调一次：教务系统会在访问过程中**续期/重发** Cookie
     * （新的 `ASP.NET_SessionId`、新的 Forms 票据），不刷新存档的话，
     * 存的是那份最老的 Cookie，恢复时更容易已经失效。
     */
    fun persist() {
        val school = schoolId.value ?: return
        val user = account.value
        if (user.isBlank()) return
        val api = SchoolRegistry.apiOf(school) ?: return
        // 学校没实现会话导出（exportSession 默认返回 null）→ 不存，
        // 免得存下一份注定恢复不了的存档，让下次冷启动白跑一趟
        val cookies = api.exportSession() ?: return
        val blob = SessionBlob(
            schoolId = school,
            account = user,
            localDebug = isLocalDebug.value,
            cookies = cookies,
        )
        SessionVault.save(SessionBlobCodec.encode(blob))
    }

    /**
     * 退出登录：清服务端登录态 + 清本地存档 + 清个人数据。
     *
     * 顺序上**先清服务端的 Cookie 会话**：金城学院的登录态是"一个账号只能在线一处"，
     * 只清本地存档的话，下次用别的账号登录会被"已在别处登录"拦一下
     * （虽然会自动强制登录，但绕一圈没必要）。
     */
    fun logout() {
        schoolId.value?.let { SchoolRegistry.apiOf(it)?.clearSession() }
        SessionVault.clear()
        schoolId.value = null
        account.value = ""
        isLocalDebug.value = false
        CourseSync.clearOnLogout()
        Log.i(TAG, "已退出登录")
    }
}
