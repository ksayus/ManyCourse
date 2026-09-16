package com.tof.manycourse.gr_api

import com.tof.manycourse.api.HttpMethod.HttpResponse
import com.tof.manycourse.data.School
import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.data.StudentProfile

/**
 * 登录结果。做成多态而不是 `Boolean`，是为了让登录页能给出**准确**的提示：
 *  - [Success] 账号密码通过，进主界面；
 *  - [Failure] 明确失败（密码错 / 网络不通 / 服务端异常），文案直接展示；
 *  - [NeedCaptcha] 服务端要求再填一次验证码（图片 + token 都带回来了）；
 *  - [Pending] 这所学校的接口还没接入（预写占位）。**不能退化成 Failure**，
 *    否则用户会一直以为是自己密码错了。
 */
sealed interface LoginResult {
    data class Success(val message: String) : LoginResult
    data class Failure(val message: String) : LoginResult
    data class Pending(val message: String) : LoginResult

    /**
     * 还差一步人工识别的验证码。
     *
     * 拿到它之后界面应该：把 [captcha] 的图显示出来 → 让用户填得数 →
     * 带 `LoginCaptcha(token, 得数)` **重新调一次 [SchoolApi.login]**。
     * 注意 [message] 是要展示给用户看的说明（比如"请输入图片中的算术题答案"）。
     */
    data class NeedCaptcha(
        val message: String,
        val captcha: LoginCaptcha,
    ) : LoginResult
}

/**
 * 一所学校的教务系统接口。
 *
 * 约定：
 *  - 实现类由 [SchoolRegistry] 以单例持有，除 Cookie 会话外不保存状态；
 *  - 所有回调都是**异步**的，跑在 OkHttp 工作线程上，**调用方自己切回主线程**；
 *  - 加学校时**不要改这个接口**，照 [WebLoginSchoolApi] 抄一份就行。
 *
 * 「拉课表 / 拉学生信息」两个业务接口给了**默认实现**（直接回失败），
 * 这样"新学校只接了登录、还没接课表"也能编译 —— 见
 * `docs/教务API接入与调用指南.md` §4.2 的两种放法取舍。
 */
interface SchoolApi {

    /** 这所学校（id / 名称 / 根地址）。UI 只认这个对象 */
    val school: School

    /**
     * 登录参数是否已填好。
     * 预写但还没确认登录入口的学校返回 false —— 登录时直接给提示，不发无效请求。
     */
    val configured: Boolean get() = true

    /**
     * 登录。
     *
     * @param captcha 上一次返回 [LoginResult.NeedCaptcha] 时带回来的验证码答案；
     *   首次登录传 null（默认值）。
     * @param callback 结果回调，**在工作线程触发**；实现方保证只回调一次。
     *
     * 参数顺序是刻意的：`captcha` 有默认值且排在 `callback` **前面**，
     * 这样两个都好用 ——
     *  - 不需要验证码的学校，调用方照旧写 `api.login(账号, 密码) { … }`（尾随 lambda）；
     *  - 需要验证码的学校，写 `api.login(账号, 密码, captcha) { … }`。
     * 把 `captcha` 放最后会破坏尾随 lambda 的写法（Kotlin 会把大括号当成 captcha）。
     */
    fun login(
        account: String,
        password: String,
        captcha: LoginCaptcha? = null,
        callback: (LoginResult) -> Unit,
    )

    /**
     * 拉这所学校当前的课表。
     *
     * **必须先登录成功**：登录态就在实现类自己的 `http`（Cookie 会话）里，
     * 所以调用方拿到的必须是与登录同一个 [SchoolApi] 实例
     * （`SchoolRegistry.apiOf(id)` 返回单例，这点天然满足）。
     *
     * @param account 登录用的账号。正方这类系统要在 URL 上带 `su=学号`，
     *   所以即使登录态里已经有身份，也把账号传进来，免得实现类自己再去猜。
     * @param callback 在工作线程触发，`Result` 里失败时是**能直接展示**的中文原因。
     */
    fun fetchSchedule(account: String, callback: (Result<List<SchoolCourse>>) -> Unit) {
        callback(Result.failure(UnsupportedOperationException("「${school.name}」还没接入课表接口")))
    }

    /**
     * 拉这所学校系统里的学生信息（**姓名**在这里面）。
     *
     * 需求的「当前账户的名字自动写入学校系统中会显示的名字」就是靠它：
     * 登录成功后拉一次，把 [StudentProfile.name] 写进 `ProfileRepository.nickname`。
     */
    fun fetchProfile(account: String, callback: (Result<StudentProfile>) -> Unit) {
        callback(Result.failure(UnsupportedOperationException("「${school.name}」还没接入学生信息接口")))
    }

    // ── 会话持久化（「维持登录」靠这三个方法）────────────────────────────────
    //
    // 都给了默认实现，所以"还没接会话持久化"的学校也能编译：导出返回 null
    // （`SessionStore` 就不会存盘），灌回返回 false（App 启动时当作没登录过）。

    /**
     * 把当前登录态（Cookie 会话）导出成可存盘的字符串。
     * 返回 null / 空串表示**这所学校不支持持久化** —— 调用方据此决定不存盘，
     * 而不是存一份恢复不了的垃圾数据。
     */
    fun exportSession(): String? = null

    /**
     * 把 [exportSession] 导出的字符串灌回会话。
     *
     * @return true = 数据格式没问题（**不代表会话还有效**：服务端那边可能早就超时了）。
     *   会话是否真的还能用，只有第一次请求才知道，届时会以
     *   [SessionExpiredException] 的形式报出来。
     */
    fun importSession(data: String): Boolean = false

    /** 退出登录 / 会话过期时清掉服务端登录态与本地缓存（必须能重复调用）*/
    fun clearSession() {}
}

/**
 * 教务系统的登录态已失效（会话超时 / 被别处登录顶掉）。
 *
 * 单独开一个类型是为了让上层能**区分"该重新登录了"和"网络出问题了"**：
 * 前者要把用户送回登录页并清掉本地登录态，后者只需要让他重试。
 * 混成一个 `IOException` 的话，用户会对着"网络异常"反复点重试，永远进不去。
 */
class SessionExpiredException(
    message: String = "登录已过期，请重新登录",
) : java.io.IOException(message)

/**
 * 国内教务系统通用的登录结果判定：**关键字命中即失败，跳转即成功**。
 *
 * 这是"够用就好"的启发式：真实系统各有各的文案，接具体学校时如果发现误判，
 * 在该学校实现里覆写 `judge()` 即可（见 [WebLoginSchoolApi.judge]）。
 */
internal object LoginJudge {

    /** 最常见的失败文案，命中任意一条即判失败；文案会直接展示给用户 */
    private val FAILURE_HINTS = listOf(
        "用户名或密码错误", "账号或密码错误", "密码错误", "密码不正确",
        "用户名不存在", "用户不存在", "账号不存在",
        "验证码错误", "验证码不正确", "验证码已失效", "验证码不能为空",
        "登录失败", "用户名不能为空", "密码不能为空",
    )

    fun byKeywords(response: HttpResponse): LoginResult {
        if (response.code == 401 || response.code == 403) {
            return LoginResult.Failure("用户名或密码错误（HTTP ${response.code}）")
        }
        if (response.code !in 200..399) {
            return LoginResult.Failure("教务系统返回 HTTP ${response.code}，请稍后重试")
        }
        val hint = FAILURE_HINTS.firstOrNull { response.body.contains(it) }
        if (hint != null) return LoginResult.Failure(hint)

        // 返回 200 且没有任何失败关键字时按成功处理（宽松判定）：
        // 教务系统登录成功一般是 302 跳首页，但有的系统是 AJAX 返回 200 + JSON。
        // 接具体学校时建议在这里盯着 HttpLoggingInterceptor 打出来的响应体核对一次。
        return LoginResult.Success("登录成功")
    }
}

/**
 * 把异常转成能直接展示给用户的一句话。
 *
 * 用 `when { }` 而不是 `when (this)`，是因为有一条分支只能靠**文案**判断
 * （明文流量被拦截时，OkHttp 在不同版本用的异常类型不一样，但消息是固定的）。
 */
internal fun Throwable.readableMessage(): String = when {
    // Android 从 targetSdk 28 起默认禁止明文 http。正常路径已经被
    // `UpgradeCleartextInterceptor` 拦下来升级成 https 了，走到这里说明
    // 对方**只支持 http**（不是"下发明文跳转"而是真没开 https）。
    // 这种时候要给一句能照做的话，而不是把英文原文糊到界面上。
    message?.contains("CLEARTEXT", ignoreCase = true) == true ->
        "该学校的地址只支持不安全的 http 连接，已被系统拦截。" +
            "请把学校根地址改成 https，或为它单独配置明文豁免（见接入指南 §7.1）"

    this is java.net.UnknownHostException ->
        "无法连接教务系统，请检查网络（学校地址：${message.orEmpty()}）"
    this is java.net.SocketTimeoutException -> "连接教务系统超时，请稍后重试"
    this is javax.net.ssl.SSLException -> "教务系统证书异常，暂时无法建立安全连接"
    else -> message ?: this::class.java.simpleName
}
