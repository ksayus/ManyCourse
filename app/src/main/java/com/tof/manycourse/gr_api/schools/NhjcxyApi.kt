package com.tof.manycourse.gr_api.schools

import android.util.Log
import com.tof.manycourse.api.CookieCodec
import com.tof.manycourse.api.HttpMethod
import com.tof.manycourse.api.HttpMethod.HttpResponse
import com.tof.manycourse.api.Sm2Cipher
import com.tof.manycourse.data.School
import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.data.StudentProfile
import com.tof.manycourse.gr_api.LoginCaptcha
import com.tof.manycourse.gr_api.LoginResult
import com.tof.manycourse.gr_api.SchoolApi
import com.tof.manycourse.gr_api.SessionExpiredException
import com.tof.manycourse.gr_api.htmlInputValue
import com.tof.manycourse.gr_api.htmlSelectedOption
import com.tof.manycourse.gr_api.readableMessage
import com.tof.manycourse.gr_api.stripTags
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder

/**
 * 南京航空航天大学金城学院 · 教务管理信息系统 <https://jcjx.nhjcxy.edu.cn>
 *
 * ## 这套系统长什么样（实测）
 *
 * 它是**两个前后端叠在一起**的老系统，登录要过两道：
 *
 * ```
 *   ① 新前端（ASP.NET MVC 5.3，X-AspNetMvc-Version: 5.3）
 *      GET  /Mvc/Base/Login        → 登录页，页内藏一个 SM2 公钥（#PublicKey）
 *      POST /Mvc/Base/Login        → 表单，账号密码都是 SM2 密文，**返回 JSON**
 *      ↓ 成功后浏览器跳 /Mvc/Base/Home，但它是个空壳（200 + 0 字节），
 *        真正的业务页面全在下面这套老系统里
 *   ② 老后端（WebForms，挂在 /EaWeb/ 下）
 *      GET  /EaWeb/Manager/Login.aspx   → 拿到 __VIEWSTATE
 *      POST /EaWeb/Manager/Login.aspx   → **账号密码走明文**，成功是 302
 *      ↓ 之后才能访问
 *        /EaWeb/Manager/Module/NetEa/SchoolRoll/Student/Show/Default.aspx  （学籍 → 姓名/班号）
 *        /EaWeb/Manager/Module/NetEa/Schedule/Query/Default.aspx           （课表查询，要 POST）
 * ```
 *
 * 根路径 `/` 只是个 JS 跳转壳（`location.href = ".../mvc"`），静态抓取看不到上面这些，
 * 所以早期这里只能挂 `configured = false` 的占位。
 *
 * ## 几个**必须照抄**的细节（错了就只表现为"用户名或密码错误"，极难查）
 *
 *  1. **密码是 SM2，不是 RSA、更不是明文**。见 [Sm2Cipher]：
 *     `C1C2C3` 顺序 + `04` 前缀，页面挂在 `/Mvc/Scripts/js/Sm2/lib/sm2.js`。
 *     账号也是 SM2（`sm2Encrypt(AccountValue, pubkeyHex, 0)`），别只加密密码。
 *  2. **公钥每次 GET 登录页都不一样**，必须每次重新取，不能缓存。
 *  3. **老系统的 ViewState 是加密态的**，回传时必须把 `__VIEWSTATEENCRYPTED`（值是空串）
 *     一起带上。漏了这个字段，服务端会按明文校验密文，直接 500
 *     「验证视图状态 MAC 失败」—— 看起来像环境问题，其实是少传一个空字符串。
 *  4. **课表要按「班级」查**，`班级` 用的是**班号**（如 `02220101`，就是学号去掉最后两位），
 *     不是「专业」也不是「姓名」。空着不填只会返回一张空表（不报错，很容易以为没课）。
 *
 * ## 线程
 *
 * 所有回调都跑在 OkHttp 工作线程；[login] / [fetchSchedule] / [fetchProfile]
 * 都**保证只回调一次**，调用方自己切主线程。
 */
class NhjcxyApi : SchoolApi {

    override val school = School(
        id = "nhjcxy",
        name = "南京航空航天大学金城学院",
        baseUrl = "https://jcjx.nhjcxy.edu.cn",
        system = "教务管理信息系统（MVC + EaWeb 两段式登录）",
    )

    /** 登录入口、课表接口、学籍接口都已实测打通，不再需要"接口未接入"的提示 */
    override val configured = true

    /** 一个 [HttpMethod] 实例 = 一个 Cookie 会话。两段登录必须共用它，否则第二段拿不到登录态 */
    private val http = HttpMethod()

    // ── 地址 ────────────────────────────────────────────────────────────────

    /** 新前端登录页（GET 取 SM2 公钥 / POST 提交密文）*/
    private val mvcLoginUrl get() = "${school.baseUrl}/Mvc/Base/Login"

    /** 老系统登录页（GET 取 ViewState / POST 提交明文）*/
    private val legacyLoginUrl get() = "${school.baseUrl}/EaWeb/Manager/Login.aspx"

    /** 学籍信息：姓名、学号、班号、专业、学院都在这页的 `LabXxx` span 里 */
    private val profileUrl
        get() = "${school.baseUrl}/EaWeb/Manager/Module/NetEa/SchoolRoll/Student/Show/Default.aspx"

    /** 课表查询：GET 拿 ViewState，POST 按班级查，返回一张 `TabSchedule` 表格 */
    private val scheduleUrl
        get() = "${school.baseUrl}/EaWeb/Manager/Module/NetEa/Schedule/Query/Default.aspx"

    // ── 登录 ────────────────────────────────────────────────────────────────

    override fun login(
        account: String,
        password: String,
        captcha: LoginCaptcha?,
        callback: (LoginResult) -> Unit,
    ) {
        if (account.isBlank()) {
            callback(LoginResult.Failure("请先填写学号"))
            return
        }
        if (password.isEmpty()) {
            callback(LoginResult.Failure("请先填写密码"))
            return
        }

        // 换账号时清掉上一个会话：两段登录的 Cookie 都挂在同一个 host 上，
        // 不清会把老会话的 ASP.NET_SessionId 带进新登录
        http.clearCookies()
        cachedProfile = null

        http.getAsync(mvcLoginUrl) { page ->
            val loginPage = page.getOrElse {
                callback(LoginResult.Failure("无法连接教务系统：${it.readableMessage()}"))
                return@getAsync
            }

            val publicKey = loginPage.body.htmlInputValue("PublicKey")
            if (publicKey.isNullOrBlank()) {
                callback(
                    LoginResult.Failure(
                        "教务系统登录页结构变了：没找到 SM2 公钥（#PublicKey），" +
                            "请按 docs/教务API接入与调用指南.md §5.1 重新抓包核对"
                    )
                )
                return@getAsync
            }

            postMvcLogin(account, password, publicKey, force = false) { verdict, response ->
                when {
                    verdict is LoginResult.Success ->
                        loginLegacy(account, password, callback)

                    // ★ 同一账号已在别处登录（浏览器没退出、或上次会话还没超时）。
                    //   站点这时会回「用户"XXX"已于…登录。在线时间:…分。」并要求点「强制登录」。
                    //   这里**自动替用户点一次**：不这么做的话，只要用户先在电脑上登录过，
                    //   手机上就永远登不进来，而且提示看着像"密码错"。
                    //   （Web 端也是同一个动作，只是它把按钮换成了「强制登录」让用户点。）
                    response != null && isConcurrentSessionConflict(response) -> {
                        Log.i(TAG, "检测到账号已在别处登录，自动使用「强制登录」重试一次")
                        postMvcLogin(account, password, publicKey, force = true) { retried, _ ->
                            if (retried is LoginResult.Success) {
                                loginLegacy(account, password, callback)
                            } else {
                                callback(retried)
                            }
                        }
                    }

                    else -> callback(verdict)
                }
            }
        }
    }

    /**
     * 提交第一段登录。
     *
     * @param force 是否「强制登录」（`U_Remark=true`）—— 对应站点那个把已登录会话顶掉的按钮
     * @param callback 拿到判定结果与**原始响应**（响应用来判断是不是"已在别处登录"）
     */
    private fun postMvcLogin(
        account: String,
        password: String,
        publicKey: String,
        force: Boolean,
        callback: (LoginResult, HttpResponse?) -> Unit,
    ) {
        // 账号 + 密码都要加密（站点 JS 就是这么做的）；每次提交都重新加密，
        // 免得同一条密文被服务端的重放保护拦掉
        val form = try {
            linkedMapOf(
                "U_Account" to Sm2Cipher.encryptToHex(account, publicKey),
                "U_Password" to Sm2Cipher.encryptToHex(password, publicKey),
                "U_WeChat" to "",
                "U_LoginType" to LOGIN_TYPE,
                // 站点上这个字段叫 MandatoryLogin，值是 "true" 时才顶掉已有会话
                "U_Remark" to if (force) "true" else "false",
                "U_Licence" to "",
                "U_MobileNo" to "",
                "U_Sex" to "Man",
                "ValidCode" to "",
            )
        } catch (e: Exception) {
            callback(LoginResult.Failure("密码加密失败（SM2）：${e.message}"), null)
            return
        }

        http.postFormAsync(mvcLoginUrl, form) { posted ->
            posted.fold(
                onSuccess = { response -> callback(judgeMvcLogin(response), response) },
                onFailure = {
                    callback(LoginResult.Failure("网络异常：${it.readableMessage()}"), null)
                },
            )
        }
    }

    /**
     * 是不是"同一账号已在别处登录"。
     *
     * 站点没有给独立的错误码，只能认文案（实测原文：
     * `用户"李同学(0222010102)"已于2026/9/16 0:11:56，从(Computer-10.0.0.1)登录。在线时间:5.97分。`）。
     * 命中就自动走一次「强制登录」，所以这里**宁可漏判也不要误判**：
     * 只用两个几乎不会出现在其它失败文案里的词。
     */
    private fun isConcurrentSessionConflict(response: HttpResponse): Boolean {
        val message = runCatching { JSONObject(response.body).optString("Message") }.getOrDefault("")
        return CONCURRENT_SESSION_HINTS.any { message.contains(it) }
    }

    /**
     * 第一段：新前端的登录结果判定。
     *
     * 站点 JS 的判据是 `result.IsSuccess`，且 `Message` 为空或 `"null"` 才算"正常进首页"。
     * 所以**不能**用 `LoginJudge.byKeywords`（那是给 HTML 响应页准备的启发式，
     * 会对着一坨 JSON 瞎猜）。
     */
    private fun judgeMvcLogin(response: HttpResponse): LoginResult {
        if (response.code !in 200..299) {
            return LoginResult.Failure("教务系统返回 HTTP ${response.code}，请稍后重试")
        }
        val json = try {
            JSONObject(response.body)
        } catch (e: Exception) {
            // 不是 JSON：多半被网关/防火墙拦截，或者登录页改版了。把开头打出来便于排查
            return LoginResult.Failure(
                "教务系统返回了非预期内容（不是 JSON）：${response.body.take(120).stripTags()}"
            )
        }
        if (!json.optBoolean("IsSuccess", false)) {
            val message = json.optString("Message").takeIf { it.isNotBlank() && it != "null" }
            return LoginResult.Failure(message ?: "用户名或密码错误")
        }
        return LoginResult.Success("登录成功")
    }

    /**
     * 第二段：老系统（`/EaWeb/`）的 WebForms 登录。
     *
     * 密码在这里是**明文**提交（实测；页面里虽有一套 AES 工具函数，
     * 但只有按回车那条路径会调用它，鼠标点「确定」提交的是原值）。
     */
    private fun loginLegacy(account: String, password: String, callback: (LoginResult) -> Unit) {
        http.getAsync(legacyLoginUrl) { page ->
            val loginPage = page.getOrElse {
                callback(LoginResult.Failure("无法连接教务系统（EaWeb）：${it.readableMessage()}"))
                return@getAsync
            }

            val viewState = loginPage.body.htmlInputValue("__VIEWSTATE")
            if (viewState.isNullOrBlank()) {
                callback(LoginResult.Failure("教务系统（EaWeb）登录页结构变了：没找到 __VIEWSTATE"))
                return@getAsync
            }

            val form = linkedMapOf(
                "__VIEWSTATE" to viewState,
                "__VIEWSTATEGENERATOR" to loginPage.body.htmlInputValue("__VIEWSTATEGENERATOR").orEmpty(),
                "hfAccompanyLogin" to "",
                "hfPort" to "",
                "Account" to account,
                "oPwdStrengthLevel" to "3",
                "LoginPass" to password,
                "btnLogin" to "确定",
                "LocalIP" to "",
            )

            http.postFormAsync(legacyLoginUrl, form) { posted ->
                val response = posted.getOrElse {
                    callback(LoginResult.Failure("网络异常：${it.readableMessage()}"))
                    return@postFormAsync
                }
                callback(judgeLegacyLogin(response))
            }
        }
    }

    /** 第二段判定：成功是 302 跳走；失败是 200 把登录页重新渲染一遍（还带着密码框） */
    private fun judgeLegacyLogin(response: HttpResponse): LoginResult {
        val location = response.location.orEmpty()
        if (response.code in 300..399) {
            return if (location.contains("Login.aspx", ignoreCase = true)) {
                LoginResult.Failure("教务系统（EaWeb）拒绝了登录，请确认密码")
            } else {
                LoginResult.Success("登录成功")
            }
        }
        if (response.code !in 200..299) {
            return LoginResult.Failure("教务系统（EaWeb）返回 HTTP ${response.code}，请稍后重试")
        }
        // 200：页面里还有密码框 = 还停在登录页
        if (response.body.contains("id=\"LoginPass\"") || response.body.contains("name=\"LoginPass\"")) {
            val hint = Regex("""alert\('([^']{4,60})'\)""").find(response.body)?.groupValues?.get(1)
            return LoginResult.Failure(hint ?: "教务系统（EaWeb）登录失败")
        }
        return LoginResult.Success("登录成功")
    }

    // ── 学生信息（姓名就在这）──────────────────────────────────────────────

    /**
     * 本次会话里已经取到的学籍信息。
     *
     * 为什么要缓存：课表查询**必须**先知道班号，而班号只有学籍页才有。
     * 不缓存的话，一次完整同步（`CourseSync` 先 `fetchProfile` 再 `fetchSchedule`）
     * 会把同一个学籍页 GET 两遍 —— 教务系统本来就慢，没必要。
     * 登录时会清空（换账号后必须重新取）。
     */
    private var cachedProfile: StudentProfile? = null

    override fun fetchProfile(account: String, callback: (Result<StudentProfile>) -> Unit) {
        cachedProfile?.let {
            callback(Result.success(it))
            return
        }
        http.getAsync(profileUrl) { page ->
            val result = page.mapCatching { response ->
                if (response.code !in 200..299) {
                    throw IOException("学籍页返回 HTTP ${response.code}")
                }
                ensureSessionAlive(response)
                // 解析细节全在 NhjcPageParser（那边用真实页面结构做了单测）
                NhjcPageParser.parseProfile(
                    html = response.body,
                    // 页面取不到姓名时退回老系统下发的 U_NameCn Cookie
                    fallbackName = cookieValue(COOKIE_NAME_CN),
                    fallbackAccount = account,
                )
            }
            result.getOrNull()?.let { cachedProfile = it }
            callback(result)
        }
    }

    /**
     * 登录态还在不在。
     *
     * 教务系统对"没登录"的响应是 **302 跳 `Login.aspx?ReturnUrl=…`**，
     * 而 OkHttp 默认跟随重定向，所以到手的会是一个 **200 + 登录页 HTML** ——
     * 只看状态码根本发现不了，表现为"解析不出姓名/课表"这种莫名其妙的结果。
     * 所以这里看**最终地址**（`HttpResponse.finalUrl`）和正文特征，
     * 明确抛 [SessionExpiredException]，好让上层把用户送回登录页。
     */
    private fun ensureSessionAlive(response: HttpResponse) {
        val landedOnLoginPage = response.finalUrl.contains(LEGACY_LOGIN_PATH, ignoreCase = true)
        val looksLikeLoginPage = response.body.contains("id=\"LoginPass\"") ||
            response.body.contains("name=\"LoginPass\"")
        if (landedOnLoginPage || looksLikeLoginPage) {
            throw SessionExpiredException()
        }
    }

    /** 老系统登录成功后会把姓名写进 `U_NameCn` Cookie（URL 编码的 UTF-8）*/
    private fun cookieValue(name: String): String? =
        http.cookiesFor(legacyLoginUrl)
            .firstOrNull { it.name == name }
            ?.value
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    // ── 会话持久化（「维持登录」）────────────────────────────────────────────
    //
    // 金城学院的登录态横跨两套系统，但 Cookie **都挂在同一个 host 上**
    // （新前端的 `.Yyw.Base` 与老系统的 Forms 票据、"ASP.NET_SessionId" 是同一份），
    // 所以"把 Cookie 整份存下来再灌回去"就等价于"维持登录"，
    // 不需要重放 SM2 登录，也不需要把密码存起来。

    override fun exportSession(): String? {
        val cookies = http.exportCookies()
        if (cookies.isEmpty()) return null
        return CookieCodec.encode(cookies)
    }

    override fun importSession(data: String): Boolean {
        if (data.isBlank()) return true
        if (!data.startsWith(CookieCodec.HEADER)) return false
        // 一条都没灌进去（全过期了）= 这次恢复等于没恢复，让上层回落到登录页
        return http.importCookies(CookieCodec.decode(data)) > 0
    }

    override fun clearSession() {
        http.clearCookies()
        cachedProfile = null
    }

    // ── 课表 ────────────────────────────────────────────────────────────────

    override fun fetchSchedule(account: String, callback: (Result<List<SchoolCourse>>) -> Unit) {
        // 课表查询页是按「班级」查的，所以先要拿到班号。
        // 学籍页顺带也给了姓名，所以这里复用 fetchProfile，不额外多一次请求类型。
        fetchProfile(account) { profile ->
            val className = profile.getOrNull()?.className?.takeIf { it.isNotBlank() }
            if (className == null) {
                callback(
                    Result.failure(
                        IOException(
                            "没拿到班级号，无法查询课表：" +
                                (profile.exceptionOrNull()?.readableMessage() ?: "学籍信息里班级为空")
                        )
                    )
                )
                return@fetchProfile
            }
            querySchedule(className, callback)
        }
    }

    /** GET 课表页拿 ViewState/学年学期 → POST 按班级查询 → 解析表格 */
    private fun querySchedule(className: String, callback: (Result<List<SchoolCourse>>) -> Unit) {
        http.getAsync(scheduleUrl) { page ->
            val listPage = page.getOrElse {
                callback(Result.failure(it))
                return@getAsync
            }

            val viewState = listPage.body.htmlInputValue("__VIEWSTATE")
            if (viewState.isNullOrBlank()) {
                callback(Result.failure(IOException("课表页结构变了：没找到 __VIEWSTATE")))
                return@getAsync
            }

            val form = linkedMapOf(
                "__VIEWSTATE" to viewState,
                "__VIEWSTATEGENERATOR" to listPage.body.htmlInputValue("__VIEWSTATEGENERATOR").orEmpty(),
                // ★ 必须回传（值是空串）。漏了它服务端会按明文校验加密的 ViewState，
                //   直接 500「验证视图状态 MAC 失败」，看起来完全不像"少传了个字段"
                "__VIEWSTATEENCRYPTED" to listPage.body.htmlInputValue("__VIEWSTATEENCRYPTED").orEmpty(),
                "__EVENTTARGET" to "",
                "__EVENTARGUMENT" to "",
                // 学年学期直接沿用页面默认选中的那个（服务端最清楚现在算哪个学期）
                FORM_TERM to (listPage.body.htmlSelectedOption("DropDownListXnXq") ?: ""),
                FORM_QUERY_TYPE to QUERY_TYPE_CLASS,
                // 班号，如 02220101
                FORM_QUERY_KEYWORD to className,
                FORM_DATE to "",
                FORM_WEEK to "",
                FORM_SHOW_TABLE to "查看课表",
                FORM_POSTBACK_TAG to "",
            )

            http.postFormAsync(scheduleUrl, form) { posted ->
                callback(
                    posted.mapCatching { response ->
                        if (response.code !in 200..299) {
                            throw IOException("课表接口返回 HTTP ${response.code}")
                        }
                        ensureSessionAlive(response)
                        NhjcPageParser.parseSchedule(response.body)
                    }
                )
            }
        }
    }


    private companion object {
        /** 日志 TAG（`adb logcat -s NhjcxyApi:I`）*/
        const val TAG = "NhjcxyApi"

        /** 站点登录页表单里的固定值（`#LoginType` 的默认值）*/
        const val LOGIN_TYPE = "TqPlatform"

        /**
         * "同一账号已在别处登录"的文案特征。
         *
         * 实测原文：`用户"李同学(0222010102)"已于2026/9/16 0:11:56，从(Computer-…)登录。在线时间:5.97分。`
         * 取「已于」+「在线时间」两个词：其它失败文案（账号密码错误 / 验证码…）里不会出现。
         */
        val CONCURRENT_SESSION_HINTS = listOf("已于", "在线时间")

        /** 老系统把姓名放在这个 Cookie 里（URL 编码）*/
        const val COOKIE_NAME_CN = "U_NameCn"

        /** 老系统登录页路径：请求被重定向到这里 = 登录态没了 */
        const val LEGACY_LOGIN_PATH = "/EaWeb/Manager/Login.aspx"

        /** 课表查询页的表单字段名（WebForms 的 `ctl00$PageBody$…` 前缀不能省）*/
        const val FORM_TERM = "ctl00\$PageBody\$XnXq\$DropDownListXnXq"
        const val FORM_QUERY_TYPE = "ctl00\$PageBody\$RbtlType"
        const val FORM_QUERY_KEYWORD = "ctl00\$PageBody\$TxbInput"
        const val FORM_DATE = "ctl00\$PageBody\$TxbDate"
        const val FORM_WEEK = "ctl00\$PageBody\$DrpZc"
        const val FORM_SHOW_TABLE = "ctl00\$PageBody\$BtnShowTable"
        const val FORM_POSTBACK_TAG = "ctl00\$DoPostBack_LiuManRang"

        /** 按班级查询 */
        const val QUERY_TYPE_CLASS = "班级"
    }
}
