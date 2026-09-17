package com.tof.manycourse.gr_api.schools

import com.tof.manycourse.api.AuthserverAesCipher
import com.tof.manycourse.api.CookieCodec
import com.tof.manycourse.api.HttpMethod
import com.tof.manycourse.api.HttpMethod.HttpResponse
import com.tof.manycourse.data.School
import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.data.StudentProfile
import com.tof.manycourse.data.Timetables
import com.tof.manycourse.gr_api.LoginCaptcha
import com.tof.manycourse.gr_api.LoginResult
import com.tof.manycourse.gr_api.SchoolApi
import com.tof.manycourse.gr_api.SessionExpiredException
import com.tof.manycourse.gr_api.WeekScheduleApi
import com.tof.manycourse.gr_api.readableMessage
import com.tof.manycourse.gr_api.stripTags
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * 广东工业大学 · 教学管理系统 <https://jxfw.gdut.edu.cn>
 *
 * ## 两套系统、一条登录链（每一跳都出自抓包核对）
 *
 * 登录**不在教务系统里做**，而是委托给学校的统一身份认证平台
 * （`authserver.gdut.edu.cn`，金智教育那套，与南大/厦大/湖大是同一个产品）：
 *
 * ```
 * ① GET  https://authserver.gdut.edu.cn/authserver/login?service=<教务系统SSO地址>
 *        ↳ 教务系统SSO地址 = https://jxfw.gdut.edu.cn/new/ssoLogin
 *        页面里藏着三个必须回传的隐藏域：pwdEncryptSalt（密码盐）/ execution / lt
 *        ⚠️ 这是**广工这版**的 id —— 新版金智叫 pwdDefaultEncryptSalt（解析器两种都认）
 * ② (可选) GET /authserver/checkNeedCaptcha.htl?username=<学号>   → {"isNeed":false}
 * ③ POST 同一个地址（表单编码）
 *        username / password(AES 密文) / captcha="" / rememberMe=true /
 *        _eventId=submit / cllt=userNameLogin / dllt=generalLogin / lt / execution
 *        ↳ 成功：302 → https://jxfw.gdut.edu.cn/new/ssoLogin?ticket=ST-…
 *        ↳ 失败：**回的还是那张登录页**（实测账号不存在 / 密码错 = HTTP 401；
 *          要验证码等情况 = HTTP 200），原因写在 <span id="showErrorTip"> 里（如"认证失败"）
 * ④ GET  <ticket 地址>（OkHttp 自动跟完 302 链）
 *        ?ticket → ;jsessionid=… → login!welcome.action   ← jxfw 会话就在这里建立
 * ⑤ 之后才能查课表
 * ```
 *
 * ## 六个**必须照抄、错了就只表现为"密码错误 / 这学期没课 / 页面改版"**的点
 *
 *  1. **密码要加密**：AES-128-CBC/PKCS7，`key = 盐`、`iv = 随机 16 位`、
 *     明文是 `随机 64 位 + 密码`，输出 base64。算法逐字节抄自登录页的 `encrypt.js`
 *     （见 [AuthserverAesCipher]），**盐每次刷新都变，必须现取**。
 *  2. **`execution` 要原样回传**，6000 个字符，一个都不能动。
 *  3. **`cllt=userNameLogin`**：页面上有账号密码 / 手机验证码 / FIDO / 扫码四个表单，
 *     `cllt` 就是"我现在走的哪一路"。不传或传错，服务端按 FIDO 那条路处理。
 *  4. **`rememberMe=true`**：就是登录页那个「7 天免登录」。它让服务端下发长效
 *     的 `CASTGC` —— 也就是 [renewSession] 能"免密复活会话"的凭据（见下）。
 *  5. **★ 每个 `!action` 都要带 `Referer`**：不带就回 **HTTP 200 + 243 字节的
 *     「非法访问 / 你没有该权限」**（见 [jxfwHeaders]）。这个坑最阴 ——
 *     它长得像"页面改版"，第一次接的时候就是被它伪装成
 *     「读不到当前学年学期」的，直到把设备日志里的响应原文打出来才看穿。
 *  6. **判成功看"跳没跳回教务系统"，判失败才看正文**：失败时状态码可能是 401
 *     （实测：账号不存在 / 密码错就是 401 + 登录页），也可能是 200（页面原样返回），
 *     光看状态码分不出来。
 *
 * ## 课表：三个 `xxx!yyy.action`（没有 REST 接口）
 *
 * | 用途 | 地址 |
 * |---|---|
 * | 当前学年学期（`#xnxqdm` 的 selected）| `GET /xsgrkbcx!getXsgrbkList.action` |
 * | **整学期**课表（`var kbxx = […]`）| `GET /xsgrkbcx!xsAllKbList.action?xnxqdm=<学期>` |
 * | **某一周**的课 + 那周的日期 | `GET /xsgrkbcx!getKbRq.action?xnxqdm=<学期>&zc=<周>` |
 *
 * 所以 [fetchSchedule] 拿整学期（一次请求、自带乱序的周次串），
 * [fetchWeekCourses] 拿单周 —— **两个接口都有**，于是这所学校也实现了
 * [WeekScheduleApi]（日历页因此能显示"某天真的会上什么课"，而不是把只上 4 周的课
 * 画满整学期）。
 *
 * ## 「维持登录」靠 CASTGC，不是靠密码
 *
 * 登录成功时服务端会在 `authserver.gdut.edu.cn` 上种一个 `CASTGC`（CAS 的票据授予 Cookie，
 * 因为带了 `rememberMe=true`，它是长效的）。它的用法**不需要额外协议**：
 *
 * ```
 * GET https://authserver.gdut.edu.cn/authserver/login?service=<SSO地址>
 *   ↳ CASTGC 还有效 → 302 直接带着一张**新的 ST** 回到教务系统（不弹登录页、不要密码）
 *   ↳ CASTGC 已失效 → 200 返回登录页
 * ```
 *
 * 所以 [renewSession] 就是"再 GET 一次这个地址"：OkHttp 会自动跟完重定向链，
 * 落到教务系统就是续期成功，落到登录页就是失败（此时如实报"登录已过期"）。
 * 存档（[exportSession]）存的是两个 host 的全部 Cookie —— 教务系统的 `JSESSIONID`
 * 管当下，`CASTGC` 管复活。
 *
 * ## 验证码：**滑块**，App 里过不去
 *
 * 广工登录页里写死 `captchaSwitch = "2"`，也就是"需要验证码时弹滑块"
 * （不是 [LoginCaptcha] 那种能显示成图片的字符验证码，所以没法像广软那样做成两步登录）。
 * 平时 `checkNeedCaptcha` 返回 `isNeed:false`，登录顺畅；一旦服务端要求，
 * 这里**直接给一句能照做的提示**，而不是把一个空 captcha 提交上去领一句"密码错误"。
 *
 * 线程：所有回调都在 OkHttp 工作线程；[login] / [fetchSchedule] / [fetchProfile] /
 * [fetchWeeks] / [fetchWeekCourses] 都**保证只回调一次**，调用方自己切主线程。
 */
class GdutApi : SchoolApi, WeekScheduleApi {

    override val school = School(
        id = "gdut",
        name = "广东工业大学",
        baseUrl = "https://jxfw.gdut.edu.cn",
        system = "教学管理系统（统一身份认证 CAS 登录 + 课表查询）",
        // ★ 广工**一天 14 节**（7 个两节块），不是默认那张 16 节的表：
        //   它自己的课表页渲染的就是「第01节 … 第14节」14 行（抓包原文见
        //   `web_fetch/gdut_extract/244_…xsAllKbList…res.txt`）。用默认表会多出
        //   不存在的第 15-16 节（"添加课程"能选到、脏数据也被夹到 16 而不是 14）。
        //   时刻暂时为空（那次抓包只有节数、没有作息）—— 时间轴因此只画「第N-M节」。
        timetable = Timetables.gdut,
    )

    override val configured = true

    /**
     * 一个 [HttpMethod] 实例 = 一个会话。
     * 统一认证（`authserver`）与教务系统（`jxfw`）分处两个 host，
     * 但登录跳转要跨 host 带着 Cookie 走，所以必须共用同一个实例。
     */
    private val http = HttpMethod()

    // ── 地址 ────────────────────────────────────────────────────────────────

    /** 教务系统自己的 SSO 入口：统一认证的 `service` 就指向它 */
    private val ssoServiceUrl get() = "${school.baseUrl}/new/ssoLogin"

    /** 统一认证登录页（GET 取隐藏域 / POST 提交表单，都是这个地址）*/
    private val casLoginUrl get() = "$CAS_ORIGIN/authserver/login?service=${encode(ssoServiceUrl)}"

    /** 登录前问一句"这个账号要不要验证码"（不需要登录态，随时可问）*/
    private val casCheckCaptchaUrl get() = "$CAS_ORIGIN/authserver/checkNeedCaptcha.htl"

    /** 登录后的欢迎页：**姓名**在这里（页面顶部那个"严海涛"）*/
    private val welcomeUrl get() = "${school.baseUrl}/login!welcome.action"

    /** 课表查询页的**壳**（里面有"我的课表 / 全校课表"两个 iframe）：它是课表页的 Referer */
    private val scheduleFrameUrl get() = "${school.baseUrl}/xsgrkbcx!xsgrkbMain.action"

    /** 课表查询页（"我的课表"那个 iframe 的壳）：`#xnxqdm` 的 selected = 当前学年学期 */
    private val coursePageUrl get() = "${school.baseUrl}/xsgrkbcx!getXsgrbkList.action"

    /** 整学期课表的页面（数据藏在 `var kbxx = […]` 里）*/
    private fun semesterUrl(term: String) =
        "${school.baseUrl}/xsgrkbcx!xsAllKbList.action?xnxqdm=${encode(term)}"

    /** 某一周的课 + 那一周的星期→日期表 */
    private fun weekUrl(term: String, week: Int) =
        "${school.baseUrl}/xsgrkbcx!getKbRq.action?xnxqdm=${encode(term)}&zc=$week"

    /** 周课表页（`getKbRq` 的 Referer；我们自己不打开它，但浏览器是从它发起 AJAX 的）*/
    private fun weekPageUrl(term: String, week: Int) =
        "${school.baseUrl}/xsgrkbcx!xskbList.action?xnxqdm=${encode(term)}&zc=$week"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * jxfw 的**每一个** `!action` 都要求带 `Referer` —— 这是实测出来的硬要求，不是"更逼真"。
     *
     * 不带 Referer 时服务端回的是 **HTTP 200 + 243 字节的
     * `<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" …><title>非法访问</title>
     * <body>你没有该权限`**，看起来像"页面改版"，实际上是被防盗链挡在门外。
     *
     * 实测（同一份会话，只改请求头）：
     *
     * | 请求 | 不带 Referer | 带 Referer |
     * |---|---|---|
     * | `xsgrkbcx!getXsgrbkList.action` | 243B 非法访问 | **6271B 真页面** |
     * | `xsgrkbcx!xsgrkbMain.action` | 243B | 2468B |
     * | `xsgrkbcx!xsAllKbList.action?xnxqdm=…` | —— | **13341B 整学期课表** |
     * | `default!getMenu.action` | 243B | 5428B 菜单 JSON |
     *
     * 而且**只校验"是不是本站的地址"，不校验具体是哪一页**（拿
     * `login!welcome.action` 当 Referer 也一样能过）。这里仍然按浏览器那样一页一页地给，
     * 因为万一哪天服务端开始校验得更细，照抄浏览器行为才是不用改代码的那条路。
     *
     * @param referer 这次请求"是从哪个页面发出来的"
     * @param xhr     true = 按页面里那些 AJAX 调用那样带 `X-Requested-With` 与 JSON 的 Accept
     */
    private fun jxfwHeaders(referer: String, xhr: Boolean = false): Map<String, String> = buildMap {
        put("Referer", referer)
        put("Accept", if (xhr) ACCEPT_JSON else ACCEPT_HTML)
        if (xhr) put("X-Requested-With", "XMLHttpRequest")
    }

    /** 表单 POST 要带的头（浏览器都有；服务端不校验，但少一个都可能被 WAF 挑刺）*/
    private val casFormHeaders
        get() = mapOf(
            "Origin" to CAS_ORIGIN,
            "Referer" to casLoginUrl,
            "Upgrade-Insecure-Requests" to "1",
        )

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
        // 换账号时清掉上一个会话：不清会把旧的 JSESSIONID / CASTGC 带进新登录，
        // 表现成"换个账号登还是上一个人的课表"
        clearSession()

        // ① 打开统一认证登录页：既建立会话 Cookie，也拿到盐 / execution / lt
        http.getAsync(casLoginUrl) { page ->
            val loginPage = page.getOrElse {
                callback(LoginResult.Failure("无法连接统一身份认证：${it.readableMessage()}"))
                return@getAsync
            }
            val form = GdutScheduleParser.parseCasLoginForm(loginPage.body)
            if (form == null) {
                callback(
                    LoginResult.Failure(
                        "统一身份认证登录页里读不到加密盐（pwdEncryptSalt），页面可能改版了"
                    )
                )
                return@getAsync
            }

            val encrypted = try {
                AuthserverAesCipher.encrypt(password, form.salt)
            } catch (e: Exception) {
                callback(LoginResult.Failure("密码加密失败：${e.message}"))
                return@getAsync
            }

            // ② 先问一句要不要验证码：广工要的是**滑块**，App 里过不去，
            //    与其提交一个空 captcha 换回一句"密码错误"，不如直接说清楚
            http.getAsync("$casCheckCaptchaUrl?username=${encode(account)}") { check ->
                if (needsCaptcha(check.getOrNull())) {
                    callback(LoginResult.Failure(CAPTCHA_REQUIRED_MESSAGE))
                    return@getAsync
                }
                submitLogin(account, encrypted, form, callback)
            }
        }
    }

    /** `{"isNeed":false}`；接口挂了 / 返回不是 JSON 时按"不需要"处理（失败开放，别挡住正常登录）*/
    private fun needsCaptcha(response: HttpResponse?): Boolean {
        val body = response?.body ?: return false
        return runCatching { JSONObject(body).optBoolean("isNeed", false) }.getOrDefault(false)
    }

    private fun submitLogin(
        account: String,
        encryptedPassword: String,
        form: GdutScheduleParser.CasLoginForm,
        callback: (LoginResult) -> Unit,
    ) {
        val body = linkedMapOf(
            "username" to account,
            // 服务端收的 `password` 是**密文**；页面上那个明文输入框是 `passwordText`，
            // 而且提交前会被 JS 置成 disabled（所以浏览器抓包里看不到它）
            "password" to encryptedPassword,
            "captcha" to "",
            // 「7 天免登录」：让服务端下发长效 CASTGC，冷启动才能免密续期
            "rememberMe" to "true",
            "_eventId" to "submit",
            "cllt" to "userNameLogin",
            "dllt" to "generalLogin",
            "lt" to form.lt,
            "execution" to form.execution,
        )
        http.postFormAsync(casLoginUrl, body, casFormHeaders) { posted ->
            val response = posted.getOrElse {
                callback(LoginResult.Failure("网络异常：${it.readableMessage()}"))
                return@postFormAsync
            }
            // ③ 还在统一认证 = 没通过。实测：账号不存在 / 密码错时服务端回
            //    **HTTP 401 + 登录页正文**，原因写在 showErrorTip 里（"认证失败"）；
            //    某些情况（比如要验证码）则回 200 + 同一张页面 —— 两种都在这里判掉
            if (looksLikeCasPage(response)) {
                callback(
                    LoginResult.Failure(
                        GdutScheduleParser.parseCasError(response.body)
                            ?: "学号或密码错误，请确认后重试"
                    )
                )
                return@postFormAsync
            }
            if (response.code !in 200..299) {
                callback(LoginResult.Failure("教务系统返回 HTTP ${response.code}，请稍后重试"))
                return@postFormAsync
            }
            // 跳是跳回教务系统了，但落到它**自己那个登录页** = 票据没被接受
            // （正常情况应该落在欢迎页上）。不判这一条的话，登录会报成功、
            // 紧接着课表同步报"登录已过期"，用户看到的是自相矛盾的提示。
            if (response.body.contains(JXFW_LOGIN_FORM_MARK)) {
                callback(LoginResult.Failure("统一身份认证已通过，但教务系统没接受这张票据，请重试"))
                return@postFormAsync
            }
            // ④ 重定向链的最后一跳就是欢迎页，顺手把姓名拿下来（省一次请求）
            cachedProfile = GdutScheduleParser.parseStudentName(response.body)
                ?.let { StudentProfile(name = it, account = account) }
            callback(LoginResult.Success("登录成功"))
        }
    }

    // ── 会话持久化（「维持登录」）────────────────────────────────────────────

    /** 本次会话里已经取到过学生信息（登录时的欢迎页 / [fetchProfile]）*/
    private var cachedProfile: StudentProfile? = null

    /** 默认学年学期（`xnxqdm`），从课表页读出来的 */
    private var cachedTerm: String? = null

    /** 本学期课表是否已发布（[GdutScheduleParser.parseSchedulePublished] 的结论）*/
    private var cachedPublished: Boolean? = null

    /** 整学期课表（含原始周次串，算教学周总数要用）*/
    private var cachedCourses: List<SchoolCourse>? = null

    private var cachedRawWeeks: List<String> = emptyList()

    /** 教学周列表（第 1 周的周一 + 总周数推出来的）*/
    private var cachedWeeks: List<SchoolWeek>? = null

    override fun exportSession(): String? {
        val cookies = http.exportCookies()
        if (cookies.isEmpty()) return null
        return CookieCodec.encode(cookies)
    }

    override fun importSession(data: String): Boolean {
        if (data.isBlank()) return true
        // 头部对不上 = 不是这份格式（比如别的学校 / 旧版本存的），别硬灌
        if (!data.startsWith(CookieCodec.HEADER)) return false
        val imported = http.importCookies(CookieCodec.decode(data))
        invalidateCaches()
        // 一条都没灌进去（全过期了 / 全解不出来）= 这次恢复等于没恢复
        return imported > 0
    }

    override fun clearSession() {
        http.clearCookies()
        invalidateCaches()
    }

    /**
     * 丢掉"属于上一个会话"的缓存。
     *
     * 为什么必须丢：这些数据都是用**旧 Cookie** 拉回来的，
     * 续期 / 重新导入存档之后服务端那边已经是另一套会话了，
     * 继续端上旧课表会表现成"换了学期课表没变"这种莫名其妙的现象。
     */
    private fun invalidateCaches() {
        cachedProfile = null
        cachedTerm = null
        cachedPublished = null
        cachedCourses = null
        cachedRawWeeks = emptyList()
        cachedWeeks = null
    }

    /**
     * 续期时的并发闸门。
     *
     * 为什么需要：课表同步与日历同步是**两条并行的链路**（`CourseSync` 同时发出去的），
     * 两边可能在同一瞬间都发现"登录态失效"。不加约束就会并发换两张 ST、
     * 给同一个账号建两套 jxfw 会话，后建的那套可能把前一套顶掉 ——
     * 表现成"刚续期完又过期了"这种诡异循环。（与 `GzusApi` 同样的处理。）
     */
    private val renewLock = Any()
    private var renewInFlight = false
    private val renewWaiters = mutableListOf<(Boolean) -> Unit>()

    /**
     * 用 `CASTGC` 静默换一套新会话（**不需要密码、不需要验证码**）。
     *
     * 就是"再打开一次统一认证登录页"：Cookie 还有效时服务端会直接 302 带一张新 ST
     * 回到教务系统，OkHttp 跟完这串重定向就等于重新登录了一遍。
     *
     * @param callback true = 已经拿到一套可用的新会话
     */
    private fun renewSession(callback: (Boolean) -> Unit) {
        synchronized(renewLock) {
            renewWaiters += callback
            // 已经有人在续期了：挂上去等它的结果就行
            if (renewInFlight) return
            renewInFlight = true
        }
        doRenewSession { renewed ->
            // 先摘掉闸门再回调，免得回调里再触发续期时被自己挡住
            val waiters = synchronized(renewLock) {
                renewInFlight = false
                renewWaiters.toList().also { renewWaiters.clear() }
            }
            waiters.forEach { it(renewed) }
        }
    }

    private fun doRenewSession(callback: (Boolean) -> Unit) {
        http.getAsync(casLoginUrl) { result ->
            val response = result.getOrNull()
            // 还在统一认证 = CASTGC 也过期了（只能重新登录）；落到教务系统 = 续期成功
            val renewed = response != null &&
                response.code in 200..299 &&
                !looksLikeCasPage(response)
            if (renewed) {
                invalidateCaches()
                cachedProfile = GdutScheduleParser.parseStudentName(response.body)?.let {
                    StudentProfile(name = it)
                }
            }
            callback(renewed)
        }
    }

    /**
     * 跑一段"可能因为登录态失效而失败"的请求；服务端说未登录时**先试着静默续期，再重跑一次**。
     *
     * `depth` 限制成只重试一次：CASTGC 也没用了就该老实报"登录已过期"，
     * 不能在这里转圈。
     */
    private fun <T> withSessionRetry(
        attempt: ((Result<T>) -> Unit) -> Unit,
        callback: (Result<T>) -> Unit,
    ) = withSessionRetry(0, attempt, callback)

    private fun <T> withSessionRetry(
        depth: Int,
        attempt: ((Result<T>) -> Unit) -> Unit,
        callback: (Result<T>) -> Unit,
    ) {
        attempt { result ->
            val expired = result.exceptionOrNull() is SessionExpiredException
            if (!expired || depth >= 1) {
                callback(result)
                return@attempt
            }
            renewSession { renewed ->
                if (renewed) withSessionRetry(depth + 1, attempt, callback) else callback(result)
            }
        }
    }

    // ── 学生信息（姓名）────────────────────────────────────────────────────

    override fun fetchProfile(account: String, callback: (Result<StudentProfile>) -> Unit) {
        cachedProfile?.let {
            callback(Result.success(it))
            return
        }
        withSessionRetry(
            attempt = { done ->
                http.getAsync(welcomeUrl, jxfwHeaders("${school.baseUrl}/")) { page ->
                    done(
                        page.mapCatching { response ->
                            guard(response, "欢迎页")
                            val name = GdutScheduleParser.parseStudentName(response.body)
                                ?: throw IOException("欢迎页结构变了：读不到姓名")
                            StudentProfile(name = name, account = account).also { cachedProfile = it }
                        }
                    )
                }
            },
            callback = callback,
        )
    }

    // ── 课表（整学期）──────────────────────────────────────────────────────

    override fun fetchSchedule(account: String, callback: (Result<List<SchoolCourse>>) -> Unit) {
        cachedCourses?.let {
            callback(Result.success(it))
            return
        }
        withSessionRetry(
            attempt = { done ->
                semesterCoursesWithTerm { done(it.map { (_, courses) -> courses }) }
            },
            callback = callback,
        )
    }

    /**
     * 取（并缓存）整学期课表，连当前学年学期一起交出去。
     *
     * **不带重试**：谁调用谁负责包一层 `withSessionRetry` —— 反过来（在这里自带重试）
     * 会让 [fetchWeeks] 那种"顺带取一次课表"的用法套成两层重试，
     * 登录态真失效时会连换两次票。
     */
    private fun semesterCoursesWithTerm(
        callback: (Result<Pair<String, List<SchoolCourse>>>) -> Unit,
    ) {
        ensureTerm { termResult ->
            val term = termResult.getOrElse {
                callback(Result.failure(it))
                return@ensureTerm
            }
            // Referer 是**课表页**：浏览器就是从这个页面点"全部/查询课表"的
            http.getAsync(semesterUrl(term), jxfwHeaders(coursePageUrl)) { page ->
                callback(page.mapCatching { term to readSemesterSchedule(it) })
            }
        }
    }

    /** 读课表页拿当前学年学期（`#xnxqdm` 选中的那个），顺带记下"课表发布了吗" */
    private fun ensureTerm(callback: (Result<String>) -> Unit) {
        cachedTerm?.let {
            callback(Result.success(it))
            return
        }
        // Referer 是课表查询的**壳页**：这个 iframe 就是它加载的（实测不带就回"你没有该权限"）
        http.getAsync(coursePageUrl, jxfwHeaders(scheduleFrameUrl)) { page ->
            callback(
                page.mapCatching { response ->
                    guard(response, "课表页")
                    val term = GdutScheduleParser.parseCurrentTerm(response.body)
                        ?: throw IOException("课表页结构变了：读不到当前学年学期（#xnxqdm）")
                    cachedTerm = term
                    GdutScheduleParser.parseSchedulePublished(response.body)?.let { cachedPublished = it }
                    term
                }
            )
        }
    }

    private fun readSemesterSchedule(response: HttpResponse): List<SchoolCourse> {
        guard(response, "课表接口")
        val courses = GdutScheduleParser.parseSemesterSchedule(response.body)
        // 一条课都没有、而页面明说"本学期课表还没发布"时给一句准确的话，
        // 否则用户会以为同步坏了（这个页面里没有那句话，结论沿用课表页的）
        if (courses.isEmpty() && cachedPublished == false) {
            throw IOException("本学期课表还没发布，请稍后再试")
        }
        cachedCourses = courses
        cachedRawWeeks = GdutScheduleParser.parseRawWeeks(response.body)
        return courses
    }

    // ── 课表（按周，日历页用）──────────────────────────────────────────────

    /**
     * 教学周列表 = "第 1 周的周一" + 总周数。
     *
     * - 第 1 周的周一来自 `getKbRq&zc=1`（服务端给出每一天的真实日期）；
     * - 总周数取整学期课表里出现过的**最大周次**（数据说了算），
     *   一门课都没有时退回页面自己写死的 22 周（`xskbcx` 页的周次下拉框就是 1~22）。
     *
     * 只发两个请求就够：实测第 1/3/5 周的周一正好每 7 天一个 ——
     * 教学周是连续的自然周，不必把 22 周挨个问一遍。
     */
    override fun fetchWeeks(callback: (Result<List<SchoolWeek>>) -> Unit) {
        cachedWeeks?.let {
            callback(Result.success(it))
            return
        }
        withSessionRetry(
            attempt = { done ->
                // 先要整学期课表：既算得出总周数，也顺手把课表缓存热上
                // （CourseSync 与这里几乎同时发车，谁先到谁省一次请求）
                semesterCoursesWithTerm { result ->
                    val term = result.getOrElse {
                        done(Result.failure(it))
                        return@semesterCoursesWithTerm
                    }.first
                    http.getAsync(weekUrl(term, FIRST_WEEK), jxfwHeaders(weekPageUrl(term, FIRST_WEEK), xhr = true)) { page ->
                        done(
                            page.mapCatching { response ->
                                val json = readWeekJson(response)
                                val monday = GdutScheduleParser.parseFirstMonday(
                                    GdutScheduleParser.parseWeekDates(json)
                                ) ?: throw IOException("周课表接口没给出第 1 周的日期，读不出教学周")
                                val count = GdutScheduleParser.maxWeekOf(cachedRawWeeks)
                                    .takeIf { it > 0 } ?: DEFAULT_WEEK_COUNT
                                GdutScheduleParser.buildWeeks(monday, count).also { cachedWeeks = it }
                            }
                        )
                    }
                }
            },
            callback = callback,
        )
    }

    override fun fetchWeekCourses(week: Int, callback: (Result<List<SchoolCourse>>) -> Unit) {
        if (week <= 0) {
            callback(Result.failure(IllegalArgumentException("周次要大于 0")))
            return
        }
        withSessionRetry(
            attempt = { done ->
                ensureTerm { termResult ->
                    val term = termResult.getOrElse {
                        done(Result.failure(it))
                        return@ensureTerm
                    }
                    http.getAsync(weekUrl(term, week), jxfwHeaders(weekPageUrl(term, week), xhr = true)) { page ->
                        done(
                            page.mapCatching { response ->
                                GdutScheduleParser.parseWeekCourses(readWeekJson(response))
                            }
                        )
                    }
                }
            },
            callback = callback,
        )
    }

    /** 周课表接口的响应是**二元数组**：`[[课程…],[星期→日期…]]` */
    private fun readWeekJson(response: HttpResponse): JSONArray {
        guard(response, "周课表接口")
        return try {
            JSONArray(response.body)
        } catch (e: Exception) {
            throw IOException("周课表接口没返回 JSON：${response.body.take(120).stripTags()}")
        }
    }

    // ── 登录态判定 ──────────────────────────────────────────────────────────

    /**
     * 一次 jxfw 请求的**统一体检**：登录态还在吗、状态码对吗、有没有被防盗链挡住。
     *
     * 三件事合成一处，是为了不让任何一个调用点漏掉其中一项 ——
     * 漏掉"防盗链"那一项时，`非法访问/你没有该权限`会伪装成"页面改版"，很难查。
     */
    private fun guard(response: HttpResponse, what: String) {
        sessionErrorOrNull(response)?.let { throw it }
        if (response.code !in 200..299) throw IOException("$what 返回 HTTP ${response.code}")
        if (GdutScheduleParser.isAccessDenied(response.body)) throw IOException(ACCESS_DENIED_MESSAGE)
    }

    /**
     * 登录态还在不在；失效则返回可抛的异常。
     *
     * ⚠️ jxfw 对未登录请求**不重定向**：它回 **HTTP 200 + 自己的登录页**
     * （`<form id="login_form">`，实测原文），所以只看状态码发现不了，
     * 表现为"解析不出课表"这种莫名其妙的结果。
     */
    private fun sessionErrorOrNull(response: HttpResponse): IOException? =
        if (looksLoggedOut(response)) SessionExpiredException() else null

    private fun looksLoggedOut(response: HttpResponse): Boolean =
        response.finalUrl.contains(CAS_HOST) ||
            response.body.contains(JXFW_LOGIN_FORM_MARK) ||
            response.body.contains(CAS_SALT_MARK)

    /** 响应还停在统一认证的登录页上（登录失败 / 续期失败都是这个样子）*/
    private fun looksLikeCasPage(response: HttpResponse): Boolean =
        response.finalUrl.contains(CAS_HOST) ||
            response.body.contains(CAS_SALT_MARK) ||
            response.body.contains(CAS_LOGIN_FORM_MARK)

    private companion object {
        const val CAS_ORIGIN = "https://authserver.gdut.edu.cn"
        const val CAS_HOST = "authserver.gdut.edu.cn"

        /** 统一认证登录页特征（盐那个隐藏域）*/
        const val CAS_SALT_MARK = "pwdEncryptSalt"

        /** 统一认证登录页特征（表单 id，登录失败时也在）*/
        const val CAS_LOGIN_FORM_MARK = "id=\"loginFromId\""

        /** jxfw 未登录时回的**自己那个**登录页的特征 */
        const val JXFW_LOGIN_FORM_MARK = "id=\"login_form\""

        /** 第 1 周（教学周列表要以它为锚点往前推）*/
        const val FIRST_WEEK = 1

        /** 整学期一门课都没有时用的周数：`xskbcx` 页的周次下拉框就是 1~22 */
        const val DEFAULT_WEEK_COUNT = 22

        val CAPTCHA_REQUIRED_MESSAGE =
            "统一身份认证要求先通过滑块验证码，App 里过不去。" +
                "请稍后用手机浏览器登录一次统一认证（登录成功后 7 天内本 App 可直接登录），" +
                "或换个网络环境再试"

        /**
         * 被防盗链挡下来时的提示。
         *
         * 这句话必须**区别于**"页面改版"：两者的表现都是"解析不出东西"，
         * 但一个要改请求头、一个要改解析器，混在一起会把排查带偏
         * （这个坑真踩过：`你没有该权限` 被当成了"读不到当前学年学期"）。
         */
        const val ACCESS_DENIED_MESSAGE =
            "教务系统拒绝了这次请求（你没有该权限）：这是它的防盗链校验，通常是请求缺少 Referer 头" +
                "（若一直这样，多半是学校换了校验方式，请反馈）"

        /** 浏览器的 HTML Accept */
        const val ACCEPT_HTML =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

        /** 页面里 AJAX 用的 Accept */
        const val ACCEPT_JSON = "application/json, text/javascript, */*; q=0.01"
    }
}
