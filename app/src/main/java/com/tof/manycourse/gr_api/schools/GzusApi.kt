package com.tof.manycourse.gr_api.schools

import com.tof.manycourse.api.CasRsaCipher
import com.tof.manycourse.api.CookieCodec
import com.tof.manycourse.api.HttpMethod
import com.tof.manycourse.api.HttpMethod.HttpResponse
import com.tof.manycourse.data.School
import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.data.StudentProfile
import com.tof.manycourse.gr_api.LoginCaptcha
import com.tof.manycourse.gr_api.LoginResult
import com.tof.manycourse.gr_api.SchoolApi
import com.tof.manycourse.gr_api.SessionExpiredException
import com.tof.manycourse.gr_api.WeekScheduleApi
import com.tof.manycourse.gr_api.readableMessage
import com.tof.manycourse.gr_api.stripTags
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

/**
 * 广州软件学院 · 教务系统（正方 V9）
 *
 * ## 登录走**统一身份认证（CAS）**，不是正方的账号密码
 *
 * 这所学校的师生账号是**统一身份认证账号**，正方那边没有对应密码 ——
 * 实测：拿真实的统一身份认证账号直登正方的 `login_slogin.html`，
 * 服务端既不跳转也不报错，只是把登录页原样返回
 * （"密码错"和"账号不存在"表现完全相同，用户没法自查）。
 *
 * 所以登录是这条链路（**每一跳都出自抓包核对**）：
 *
 * ```
 *  ① GET  https://cas.gzus.edu.cn/lyuapServer/login?service=<正方SSO地址>
 *  ② POST https://cas.gzus.edu.cn/lyuapServer/v1/tickets      ← 表单编码，不是 JSON！
 *          username / password(RSA PKCS#1) / service / loginType / id / code
 *          ↓ 成功返回 {"tgt":"TGT-…","ticket":"ST-…"}
 *  ③ GET  https://jwxt.gzus.edu.cn/sso/lyiotlogin?ticket=<ST>  ← 跟随后续 302
 *          └→ /sso/lyiotlogin → /jwglxt/ticketlogin?uid=…&verify=… → login_slogin.html
 *             （正方会话就是在这里建立/续期的）
 *  ④ 之后才能查课表
 * ```
 *
 * **不需要经过"办事大厅"**：抓包显示正方自己就有 SSO 入口 `/sso/lyiotlogin`，
 * 让 CAS 的 `service` 直接指向它即可（办事大厅用的是同一个 CAS，只是多绕一层）。
 *
 * ## 三个必须照抄、错了就只表现为"密码错误"的细节
 *
 *  1. **请求体是表单编码，不是 JSON**。同一个请求发 JSON 会得到
 *     `HTTP 500「系统内部错误」`；发 `application/x-www-form-urlencoded` 才会进入业务校验。
 *     原因：前端用 axios，其默认 `Content-Type` 就是 form-urlencoded，
 *     而代码里又 `JSON.stringify(...)` —— 属"错配但真实存在"的行为，**必须照抄**。
 *  2. **密码是 RSA PKCS#1 v1.5 + base64**（见 [CasRsaCipher]），
 *     **不是**正方那套无填充裸 RSA。公钥（1024 位）硬编码在前端 bundle 里。
 *  3. **必须带两个自定义头**：`loginUserToken` = `RSA(TAG + 服务端毫秒时间)`
 *     （服务端时间取响应 `Date` 头）、`loginToken` = `"loginToken"`。
 *
 * ## 验证码：每次登录都要，且只能人工识别
 *
 * 服务端 `GET /lyuapServer/loginType` 返回 `isVerifyCode: "1"` ——
 * 与"输错几次才要"无关，**每次账号密码登录都要填**。
 * 验证码是一张 100×25 的**算术题图片**，所以登录设计成两步：
 * 先返回 [LoginResult.NeedCaptcha]（把图和 token 交给界面），
 * 用户填完得数后再带着 `LoginCaptcha(token, 图, 得数)` 重新调 [login]。
 *
 * ## 「维持登录」靠的是 **TGT**，不是验证码更不是"7 天保持登录"
 *
 * 登录页上那个「七天保持登录状态」实测**只是个客户端行为**：勾了之后
 * 前端把 `用户名 & RSA(密码)` 写进自己的 `accountInfo` Cookie，下次打开时
 * 用 bundle 里硬编码的**私钥**解密回填输入框 —— 它既不发给服务端、
 * 也不会延长任何服务端会话（而且每次打开仍然要重新看验证码图）。
 * 也就是说**那个勾对我们没有任何可扒的价值**（我们已经知道密码本身）。
 *
 * 真正能持久化的是登录响应里的 `tgt`：
 *
 * ```
 * POST https://cas.gzus.edu.cn/lyuapServer/v1/tickets/<TGT>
 *      service=<正方SSO地址>&loginToken=loginToken      ← 只带 TGT，不需要 Cookie / 密码 / 验证码
 *   ← 纯文本 "ST-…"（注意：不是 JSON，只有第一次登录才是 JSON）
 * GET  https://jwxt.gzus.edu.cn/sso/lyiotlogin?ticket=<ST>   → 全新的正方会话
 * ```
 *
 * 所以 [exportSession] 把 TGT 和 Cookie 一起存（加密落盘那条链路见 `SessionStore`），
 * 冷启动时只要 TGT 还在，[renewSession] 就能**静默换出一套新会话**，
 * 用户完全不用再算验证码。实测（`tools/cas_probe.mjs`）：清空全部 Cookie，
 * 只用 TGT 换票照样能查课表。
 */
class GzusApi : SchoolApi, WeekScheduleApi {

    override val school = School(
        id = "gzus",
        name = "广州软件学院",
        baseUrl = "https://jwxt.gzus.edu.cn",
        system = "正方教务系统 V-9.0（统一身份认证 CAS 登录）",
    )

    override val configured = true

    /**
     * 一个 [HttpMethod] 实例 = 一个会话。
     * CAS 与正方分处两个 host，但都靠它统一持有 Cookie（登录链路要跨 host 保持会话）。
     */
    private val http = HttpMethod()

    // ── 地址 ────────────────────────────────────────────────────────────────

    /** 正方自己提供的 SSO 入口：CAS 的 `service` 就指向它 */
    private val jwxtSsoUrl get() = "${school.baseUrl}/sso/lyiotlogin"

    private val casLoginPageUrl
        get() = "$CAS_ORIGIN/lyuapServer/login?service=${encode(jwxtSsoUrl)}"

    private val casTicketsUrl get() = "$CAS_ORIGIN/lyuapServer/v1/tickets"

    /** 个人课表查询页：用来读默认学年学期（页面里 `#xnm` / `#xqm` 的选中项）*/
    private val kbPageUrl
        get() = "${school.baseUrl}/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html" +
            "?gnmkdm=$GNMKDM_KB&layout=default"

    /** 个人课表**数据**接口：返回 JSON（`xsxx` 学生信息 + `kbList` 课程）*/
    private val kbDataUrl
        get() = "${school.baseUrl}/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=$GNMKDM_KB"

    /**
     * **周次课表**查询页：页面里除了默认学年学期，还带着 `#zs`（全部教学周 + 各自起止日期）。
     * 这是"今天是第几周"的唯一可靠来源。
     */
    private val weekPageUrl
        get() = "${school.baseUrl}/jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html" +
            "?gnmkdm=$GNMKDM_ZC&layout=default"

    /** 周次课表**数据**接口：一次给一周的课（`zs` = 周次）*/
    private val weekDataUrl
        get() = "${school.baseUrl}/jwglxt/kbcx/xskbcxMobile_cxXsKb.html?gnmkdm=$GNMKDM_ZC"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private val xhrHeaders get() = mapOf("X-Requested-With" to XML_HTTP_REQUEST)

    // ── 登录（CAS）──────────────────────────────────────────────────────────

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
        // 换账号时清掉上一个会话，免得把旧的正方 JSESSIONID 带进新登录
        clearSession()

        // ① 先打开 CAS 登录页：建立会话 Cookie，顺便把服务端时间读出来（构造反重放头要用）
        http.getAsync(casLoginPageUrl) { page ->
            val loginPage = page.getOrElse {
                callback(LoginResult.Failure("无法连接统一身份认证：${it.readableMessage()}"))
                return@getAsync
            }
            // 服务端时间取不到就退回本机时间：CAS 对时间戳有容忍窗口，
            // 设备时钟正常时不会有问题，直接失败反而更糟
            val serverTime = loginPage.serverTimeMillis ?: System.currentTimeMillis()

            val token = try {
                buildLoginToken(serverTime)
            } catch (e: Exception) {
                callback(LoginResult.Failure("无法构造登录令牌：${e.message}"))
                return@getAsync
            }
            val encryptedPassword = try {
                CasRsaCipher.encryptToHex(password, CAS_MODULUS_HEX, CAS_EXPONENT_HEX)
            } catch (e: Exception) {
                callback(LoginResult.Failure("密码加密失败（RSA）：${e.message}"))
                return@getAsync
            }

            val form = linkedMapOf(
                "username" to account,
                "password" to encryptedPassword,
                "service" to jwxtSsoUrl,
                // 前端传的是空串（不是 false、也不是省略）
                "loginType" to "",
                // 验证码：首次都是空串，服务端会回 CODEFALSE 并把 uid 交回来
                "id" to (captcha?.token ?: ""),
                "code" to (captcha?.answer ?: ""),
            )
            val headers = mapOf(
                "loginUserToken" to token,
                "loginToken" to LOGIN_TOKEN,
                "X-Requested-With" to XML_HTTP_REQUEST,
                "Origin" to CAS_ORIGIN,
                "Referer" to casLoginPageUrl,
            )

            // ② 提交登录（表单编码 + 两个自定义头）
            http.postFormAsync(casTicketsUrl, form, headers) { posted ->
                val response = posted.getOrElse {
                    callback(LoginResult.Failure("网络异常：${it.readableMessage()}"))
                    return@postFormAsync
                }
                when (val verdict = judgeCasLogin(response)) {
                    is CasVerdict.Accepted -> {
                        // ★ 把 TGT 留下来：这是唯一能"免密免验证码"复活会话的凭证
                        verdict.tgt.takeIf { it.isNotBlank() }?.let { ssoTgt = it }
                        finishSsoLogin(verdict.ticket, callback)
                    }
                    CasVerdict.NeedsCaptcha -> fetchCaptcha(callback)
                    is CasVerdict.Rejected -> callback(LoginResult.Failure(verdict.message))
                }
            }
        }
    }

    /**
     * 构造 `loginUserToken` = `RSA(TAG + 服务端毫秒时间)`。
     *
     * ⚠️ **实测服务端并不校验这个头**：同一个登录请求，不带任何 token 头、
     * 带一段乱写的定长十六进制、带这里算出来的值，服务端都返回同样的业务码
     * （`CODEFALSE`，也就是"要验证码"）。所以这里的算法**不追求与浏览器逐字节一致** ——
     * 想对齐也没法对齐：浏览器那个值里还掺了只有它自己知道的东西，
     * 用已知的 TAG + 服务端时间复算不出来（已经试过大小端、秒/毫秒、±3 秒窗口）。
     *
     * 保留它是因为：浏览器每个请求都带，服务端哪天开始校验了也不用改代码。
     */
    private fun buildLoginToken(serverTimeMillis: Long): String =
        CasRsaCipher.encryptToHex("$CAS_TAG$serverTimeMillis", CAS_MODULUS_HEX, CAS_EXPONENT_HEX)

    /** CAS 登录的三态判定结果（拿 `LoginResult` 表达会把"要验证码"和"失败"混在一起）*/
    private sealed interface CasVerdict {
        /** [tgt] 是登录响应里的 `tgt`（可能为空 —— 那就只存 Cookie，退化成老行为）*/
        data class Accepted(val ticket: String, val tgt: String = "") : CasVerdict
        data object NeedsCaptcha : CasVerdict
        data class Rejected(val message: String) : CasVerdict
    }

    /**
     * CAS 登录结果判定。
     *
     * 响应信封：`{"meta":{"success":…,"statusCode":…,"message":…},"data":{"code":"…"}}`；
     * 成功时顶层带 `ticket`（ST 票据）与 `tgt`（可复用的凭据）。
     *
     * 业务码含义取自前端 bundle 的映射表（`CODEFALSE` 一条已实测确认）：
     * `FALSE` 账号密码错、`NOUSER` 账号不存在、`PASSERROR` 密码错（带锁定次数）、
     * `CODEFALSE` 要验证码、`ISMODIFYPASS` 要先改密码、
     * `ISPHONEOREMAILORANSWER` 要短信/邮箱/密保二次验证。
     */
    private fun judgeCasLogin(response: HttpResponse): CasVerdict {
        if (response.code !in 200..299) {
            return CasVerdict.Rejected("统一身份认证返回 HTTP ${response.code}，请稍后重试")
        }
        val json = try {
            JSONObject(response.body)
        } catch (e: Exception) {
            return CasVerdict.Rejected(
                "统一身份认证返回了非预期内容：${response.body.take(120).stripTags()}"
            )
        }

        json.optString("ticket").takeIf { it.isNotBlank() }?.let {
            return CasVerdict.Accepted(ticket = it, tgt = json.optString("tgt"))
        }

        val code = json.optJSONObject("data")?.optString("code").orEmpty()
        val message = json.optJSONObject("meta")?.optString("message").orEmpty()

        return when (code) {
            "CODEFALSE" -> CasVerdict.NeedsCaptcha
            "FALSE" -> CasVerdict.Rejected("账号或密码错误")
            "NOUSER" -> CasVerdict.Rejected("账号不存在")
            "PASSERROR" -> CasVerdict.Rejected("密码错误，请确认后重试（连续错误会锁定账号）")
            "ISMODIFYPASS" ->
                CasVerdict.Rejected("需要先修改密码：请在电脑浏览器登录一次完成改密")
            "ISPHONEOREMAILORANSWER" ->
                CasVerdict.Rejected("需要短信/邮箱/密保验证：请在电脑浏览器完成一次验证后再登录")
            "ISBINDWX" -> CasVerdict.Rejected("需要先绑定微信：请在电脑浏览器完成绑定")
            "NETWORKCOMMITMENT" -> CasVerdict.Rejected("需要在电脑浏览器签署网络使用承诺书")
            "PEOPLEMOREACCOUNT" ->
                CasVerdict.Rejected("该身份对应多个账号，请在电脑浏览器选择后再登录")
            "NOREGISTER", "NOAUTHORIZATION" ->
                CasVerdict.Rejected("该账号没有访问教务系统的权限")
            else -> CasVerdict.Rejected(
                message.takeIf { it.isNotBlank() && it != "ok" } ?: "统一身份认证登录失败（$code）"
            )
        }
    }

    /**
     * 取一张验证码图，连 token 一起交给界面。
     *
     * 全异步：**不要**在这里阻塞等待另一个请求 —— 这个函数本身就跑在
     * OkHttp 的 dispatcher 线程上，阻塞它再去发请求，最坏情况会和
     * "同一 host 最多 5 个并发"的限制撞成死锁。
     */
    private fun fetchCaptcha(callback: (LoginResult) -> Unit) {
        val uid = UUID.randomUUID().toString().replace("-", "")
        http.getAsync("$CAS_ORIGIN/lyuapServer/kaptcha?uid=$uid") { page ->
            callback(
                page.fold(
                    onSuccess = { response ->
                        val json = runCatching { JSONObject(response.body) }.getOrNull()
                        val content = json?.optString("content").orEmpty()
                        val token = json?.optString("uid")?.takeIf { it.isNotBlank() } ?: uid
                        if (content.isBlank()) {
                            LoginResult.Failure("统一身份认证要求验证码，但验证码图片为空，请稍后重试")
                        } else {
                            LoginResult.NeedCaptcha(
                                message = "请输入图片中算式的得数",
                                captcha = LoginCaptcha(token = token, imageBase64 = content),
                            )
                        }
                    },
                    onFailure = { LoginResult.Failure("获取验证码失败：${it.readableMessage()}") },
                )
            )
        }
    }

    /**
     * 用 ST 票据换正方会话。
     *
     * 只发一个 GET 并**跟随重定向**：后续的
     * `/jwglxt/ticketlogin?uid=…&verify=…` 由服务端在 302 里给出，
     * OkHttp 自动走完，正方会话（JSESSIONID）就是在这一段建立/续期的。
     */
    private fun finishSsoLogin(ticket: String, callback: (LoginResult) -> Unit) {
        http.getAsync("$jwxtSsoUrl?ticket=${encode(ticket)}") { jumped ->
            jumped.fold(
                onSuccess = { response ->
                    when {
                        // 走完重定向后**最终**落在登录页 = 票据没被接受
                        response.finalUrl.contains(LOGIN_PAGE_MARK, ignoreCase = true) ->
                            callback(LoginResult.Failure("统一身份认证已通过，但教务系统未接受该票据"))
                        response.code !in 200..299 ->
                            callback(LoginResult.Failure("教务系统返回 HTTP ${response.code}"))
                        else -> callback(LoginResult.Success("登录成功"))
                    }
                },
                onFailure = {
                    callback(LoginResult.Failure("换取教务系统会话失败：${it.readableMessage()}"))
                },
            )
        }
    }

    // ── 会话持久化（「维持登录」）────────────────────────────────────────────
    //
    // 存两样东西：正方 Cookie（当下就能用）+ CAS 的 TGT（Cookie 死了还能复活）。

    /**
     * 登录成功后从 CAS 响应里留下的 TGT。
     *
     * 它不是"会话 Cookie 的替代品"，而是**入场券**：拿它换新 ST 就能重新建会话。
     * 只在内存里存活，落盘由 [exportSession] / `SessionVault` 负责（Keystore 加密）。
     */
    private var ssoTgt: String? = null

    override fun exportSession(): String? {
        val cookies = http.exportCookies()
        val cookieText = if (cookies.isEmpty()) "" else CookieCodec.encode(cookies)
        val session = GzusSession(tgt = ssoTgt.orEmpty(), cookies = cookieText)
        // 两样都没有就别存 —— 存一份恢复不了的存档只会让下次冷启动白跑一趟
        if (session.isEmpty) return null
        return GzusSessionCodec.encode(session)
    }

    override fun importSession(data: String): Boolean {
        if (data.isBlank()) return true

        // 老版本存档（只有 Cookie，头是 manycourse-cookies/1）：照样认，
        // 否则升级一次 App 就把所有人的登录态清了
        if (data.startsWith(CookieCodec.HEADER)) {
            return http.importCookies(CookieCodec.decode(data)) > 0
        }

        val session = GzusSessionCodec.decode(data) ?: return false
        if (session.cookies.startsWith(CookieCodec.HEADER)) {
            http.importCookies(CookieCodec.decode(session.cookies))
        }
        ssoTgt = session.tgt.takeIf { it.isNotBlank() }
        cachedScheduleJson = null
        cachedWeeks = null
        cachedTerm = null
        // ★ 有 TGT 就算恢复成功，**哪怕一条 Cookie 都没灌进去**：
        //   第一次请求发现登录态不在时，renewSession() 会用 TGT 换一套新的。
        return true
    }

    override fun clearSession() {
        http.clearCookies()
        ssoTgt = null
        cachedScheduleJson = null
        cachedWeeks = null
        cachedTerm = null
    }

    /**
     * 正在续期时的并发闸门。
     *
     * 为什么需要它：课表同步和日历同步是**两条并行的链路**（`CourseSync` 里同时发出去了），
     * 两边可能在同一瞬间都发现"登录态失效"。不加约束的话会并发换两张 ST、
     * 给同一个账号建**两套正方会话** —— 正方对同一账号的多会话并不总是宽容，
     * 后建的那套可能把前一套顶掉，表现成"刚续期完又过期了"这种诡异循环。
     */
    private val renewLock = Any()
    private var renewInFlight = false
    private val renewWaiters = mutableListOf<(Boolean) -> Unit>()

    /**
     * 用 TGT 静默换一套新的正方会话（**不需要密码、不需要验证码**）。
     *
     * 两步，都是实测过的：
     *  1. `POST /lyuapServer/v1/tickets/<TGT>`，表单只有 `service` + `loginToken`；
     *     **成功时响应体是纯文本 ST**（`ST-…`），不是 JSON —— 只有第一次登录才是 JSON。
     *     TGT 本身没绑任何 Cookie（实测清空 CookieJar 也能换），所以这是一张
     *     "拿着就能用"的凭据，也正是我们要落盘保护的东西。
     *  2. 拿 ST 走一遍 `sso/lyiotlogin`，重定向链会把新的 `JSESSIONID` 种下来。
     *
     * 同一时刻只跑一次：已经在跑的期间进来的调用**排队等同一个结果**（见 [renewLock]）。
     *
     * @param callback true = 已经拿到一套可用的新会话
     */
    private fun renewSession(callback: (Boolean) -> Unit) {
        val tgt = ssoTgt
        if (tgt.isNullOrBlank()) {
            callback(false)
            return
        }
        synchronized(renewLock) {
            renewWaiters += callback
            // 已经有人在换了：挂上去等它的结果就行
            if (renewInFlight) return
            renewInFlight = true
        }
        doRenewSession(tgt) { renewed ->
            // 先摘掉闸门再回调，免得回调里再触发续期时被自己挡住
            val waiters = synchronized(renewLock) {
                renewInFlight = false
                renewWaiters.toList().also { renewWaiters.clear() }
            }
            waiters.forEach { it(renewed) }
        }
    }

    private fun doRenewSession(tgt: String, callback: (Boolean) -> Unit) {
        http.postFormAsync(
            "$casTicketsUrl/${encode(tgt)}",
            linkedMapOf("service" to jwxtSsoUrl, "loginToken" to LOGIN_TOKEN),
            mapOf(
                "X-Requested-With" to XML_HTTP_REQUEST,
                "Origin" to CAS_ORIGIN,
                "Referer" to casLoginPageUrl,
            ),
        ) { posted ->
            // 网络层面就没拿到响应：**不要把 TGT 丢掉**，下次请求再试
            val response = posted.getOrNull()
            if (response == null) {
                callback(false)
                return@postFormAsync
            }
            val ticket = extractTicket(response.body)
            if (ticket == null) {
                // 服务端明确拒绝了这张 TGT（过期 / 已被登出作废）：清掉，
                // 免得以后每个请求都白试一遍
                ssoTgt = null
                callback(false)
                return@postFormAsync
            }
            http.getAsync("$jwxtSsoUrl?ticket=${encode(ticket)}") { jumped ->
                val jumpedOrNull = jumped.getOrNull()
                val ok = jumpedOrNull?.let { sessionLooksAlive(it) } == true
                when {
                    ok -> {
                        // 换了新会话，之前缓存的数据可能属于旧会话，全部作废
                        cachedScheduleJson = null
                        cachedWeeks = null
                        cachedTerm = null
                    }
                    // 有响应但 ST 没被接收 = 服务端不认了；纯网络异常则留着 TGT
                    jumpedOrNull != null -> ssoTgt = null
                }
                callback(ok)
            }
        }
    }

    /**
     * 换票接口的响应体：**成功是纯文本 `ST-…`**，失败时才是 JSON 信封。
     * 两种都认，免得服务端哪天把成功也包成 JSON。
     */
    private fun extractTicket(body: String): String? {
        val text = body.trim()
        if (text.startsWith(ST_PREFIX)) return text.substringBefore('\n').trim()
        return runCatching { JSONObject(text).optString("ticket") }
            .getOrNull()
            ?.takeIf { it.startsWith(ST_PREFIX) }
    }

    /**
     * 跑一段"可能因为登录态失效而失败"的请求；服务端说未登录、而手上还有 TGT 时，
     * **静默换一套新会话再重跑一次**。
     *
     * `depth` 限制成只重试一次：TGT 也没用了就该老实报"登录已过期"，
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
            if (!expired || depth >= 1 || ssoTgt.isNullOrBlank()) {
                callback(result)
                return@attempt
            }
            renewSession { renewed ->
                if (renewed) withSessionRetry(depth + 1, attempt, callback) else callback(result)
            }
        }
    }

    // ── 课表 / 学生信息 ─────────────────────────────────────────────────────

    /**
     * 本次会话里已经取到的课表接口原始响应。
     *
     * 为什么要缓存：`xsxx`（姓名）和 `kbList`（课程）在**同一个响应**里，
     * 而 `CourseSync` 会先 `fetchProfile` 再 `fetchSchedule` ——
     * 不缓存就会拿同一份数据打两遍接口（教务系统本来就慢）。
     */
    private var cachedScheduleJson: JSONObject? = null

    /** 默认学年学期（`xnm` / `xqm`），从课表页读出来的 */
    private var cachedTerm: Pair<String, String>? = null

    /** 教学周列表（周次课表页的 `#zs`）。日历页要它来判断"某天属于第几周" */
    private var cachedWeeks: List<SchoolWeek>? = null

    override fun fetchProfile(account: String, callback: (Result<StudentProfile>) -> Unit) {
        loadScheduleJson { result ->
            callback(result.mapCatching { GzusScheduleParser.parseProfile(it, account) })
        }
    }

    override fun fetchSchedule(account: String, callback: (Result<List<SchoolCourse>>) -> Unit) {
        loadScheduleJson { result ->
            callback(result.mapCatching { GzusScheduleParser.parseSchedule(it) })
        }
    }

    /** 取课表接口的 JSON：GET 课表页拿默认学年学期 → POST 数据接口 */
    private fun loadScheduleJson(callback: (Result<JSONObject>) -> Unit) {
        cachedScheduleJson?.let {
            callback(Result.success(it))
            return
        }
        withSessionRetry({ done -> requestScheduleJson(done) }, callback)
    }

    private fun requestScheduleJson(callback: (Result<JSONObject>) -> Unit) {
        http.getAsync(kbPageUrl) { page ->
            val coursePage = page.getOrElse {
                callback(Result.failure(it))
                return@getAsync
            }
            sessionErrorOrNull(coursePage)?.let {
                callback(Result.failure(it))
                return@getAsync
            }

            val term = GzusScheduleParser.parseTerm(coursePage.body)
            if (term == null) {
                callback(Result.failure(IOException("课表页结构变了：读不到默认学年学期（#xnm / #xqm）")))
                return@getAsync
            }
            cachedTerm = term

            val form = linkedMapOf(
                "xnm" to term.first,
                "xqm" to term.second,
                "kzlx" to "ck",
                "xsdm" to "",
                "kclbdm" to "",
                "kclxdm" to "",
            )
            http.postFormAsync(kbDataUrl, form, xhrHeaders) { posted ->
                callback(posted.mapCatching { response -> readScheduleJson(response) })
            }
        }
    }

    /** 把课表接口的响应校验 + 解析成 JSON，并写进缓存 */
    private fun readScheduleJson(response: HttpResponse): JSONObject {
        if (response.code !in 200..299) {
            throw IOException("课表接口返回 HTTP ${response.code}")
        }
        sessionErrorOrNull(response)?.let { throw it }
        val json = try {
            JSONObject(response.body)
        } catch (e: Exception) {
            throw IOException("课表接口没返回 JSON：${response.body.take(120).stripTags()}")
        }
        cachedScheduleJson = json
        return json
    }

    // ── 周次课表（日历页用）──────────────────────────────────────────────────

    /**
     * 拉教学周列表。
     *
     * 只读**页面**（`xskbcxZccx_cxXskbcxIndex.html`）就够：`#zs` 下拉框里
     * 每一周都自带起止日期，`selected` 的那一项就是当前教学周。
     */
    override fun fetchWeeks(callback: (Result<List<SchoolWeek>>) -> Unit) {
        cachedWeeks?.let {
            callback(Result.success(it))
            return
        }
        withSessionRetry({ done -> requestWeeks(done) }, callback)
    }

    private fun requestWeeks(callback: (Result<List<SchoolWeek>>) -> Unit) {
        http.getAsync(weekPageUrl) { page ->
            callback(
                page.mapCatching { response ->
                    sessionErrorOrNull(response)?.let { throw it }
                    if (response.code !in 200..299) {
                        throw IOException("周次课表页返回 HTTP ${response.code}")
                    }
                    GzusScheduleParser.parseTerm(response.body)?.let { cachedTerm = it }
                    val weeks = GzusScheduleParser.parseWeeks(response.body)
                    if (weeks.isEmpty()) {
                        throw IOException("周次课表页结构变了：读不到教学周列表（#zs）")
                    }
                    cachedWeeks = weeks
                    weeks
                }
            )
        }
    }

    /**
     * 拉**某一周**的课程。
     *
     * `POST /jwglxt/kbcx/xskbcxMobile_cxXsKb.html?gnmkdm=N2154`，表单
     * `xnm & xqm & zs=<周次> & kblx=1 & doType=app & xh=`（`xh` 留空 = 查自己）。
     *
     * 响应里 `kbList` 只含**这一周真的会上**的课，所以不用再自己按周次过滤；
     * `xsxx` 与个人课表接口同构（姓名/班级/专业都在），`rqazcList` 还能给出
     * 本周一到周日的真实日期。
     */
    override fun fetchWeekCourses(week: Int, callback: (Result<List<SchoolCourse>>) -> Unit) {
        if (week <= 0) {
            callback(Result.failure(IllegalArgumentException("周次要大于 0")))
            return
        }
        cachedTerm?.let { term ->
            requestWeekCourses(term, week, callback)
            return
        }
        // 还没读过学年学期：先读页面（顺带把教学周列表也缓存下来），再发数据请求
        fetchWeeks { result ->
            val term = result.getOrNull()?.let { cachedTerm }
            if (term == null) {
                callback(
                    result.exceptionOrNull()?.let { Result.failure(it) }
                        ?: Result.failure(
                            IOException("周次课表页结构变了：读不到默认学年学期（#xnm / #xqm）")
                        )
                )
                return@fetchWeeks
            }
            requestWeekCourses(term, week, callback)
        }
    }

    private fun requestWeekCourses(
        term: Pair<String, String>,
        week: Int,
        callback: (Result<List<SchoolCourse>>) -> Unit,
    ) {
        val form = linkedMapOf(
            "xnm" to term.first,
            "xqm" to term.second,
            "zs" to week.toString(),
            // 1 = 周次课表（页面上另一个选项 2 是"学期课表"）
            "kblx" to "1",
            "doType" to "app",
            "xh" to "",
        )
        withSessionRetry(
            attempt = { done ->
                http.postFormAsync(weekDataUrl, form, xhrHeaders) { posted ->
                    done(posted.mapCatching { GzusScheduleParser.parseSchedule(readWeekJson(it)) })
                }
            },
            callback = callback,
        )
    }

    private fun readWeekJson(response: HttpResponse): JSONObject {
        if (response.code !in 200..299) {
            throw IOException("周次课表接口返回 HTTP ${response.code}")
        }
        sessionErrorOrNull(response)?.let { throw it }
        return try {
            JSONObject(response.body)
        } catch (e: Exception) {
            throw IOException("周次课表接口没返回 JSON：${response.body.take(120).stripTags()}")
        }
    }

    /**
     * 登录态还在不在；失效则返回可抛的异常。
     *
     * 正方对未登录请求的处理是**重定向到 `login_slogin.html`**，
     * 而 OkHttp 默认跟随重定向 —— 到手的其实是「200 + 登录页」，
     * 光看状态码发现不了，表现为"解析不出课表"这种莫名其妙的结果。
     */
    private fun sessionErrorOrNull(response: HttpResponse): IOException? =
        if (!sessionLooksAlive(response)) SessionExpiredException() else null

    private fun sessionLooksAlive(response: HttpResponse): Boolean =
        !response.body.contains(LOGIN_PAGE_MARK) &&
            !response.finalUrl.contains(LOGIN_PAGE_MARK, ignoreCase = true)

    private companion object {
        /** 统一身份认证的服务地址（与教务系统同属 gzus.edu.cn）*/
        const val CAS_ORIGIN = "https://cas.gzus.edu.cn"

        /** 反重放令牌里的固定前缀（前端 bundle 里的 `TAG`）*/
        const val CAS_TAG = "lyasp"

        /**
         * CAS 的 RSA 公钥 —— **硬编码在前端 bundle 里**（`app_config_names` 模块）。
         *
         * 1024 位，十六进制带前导 `00`（DER 正整数补位）。它并不是秘密
         * （浏览器里人人可见），作用只是避免密码明文过网。
         */
        const val CAS_MODULUS_HEX =
            "00b5eeb166e069920e80bebd1fea4829d3d1f3216f2aabe79b6c47a3c18dcee5" +
                "fd22c2e7ac519cab59198ece036dcf289ea8201e2a0b9ded307f8fb704136eae" +
                "b670286f5ad44e691005ba9ea5af04ada5367cd724b5a26fdb5120cc95b6431" +
                "604bd219c6b7d83a6f8f24b43918ea988a76f93c333aa5a20991493d4eb1117e7b1"
        const val CAS_EXPONENT_HEX = "010001"

        /** 前端每个请求都会带上的固定值 */
        const val LOGIN_TOKEN = "loginToken"
        const val XML_HTTP_REQUEST = "XMLHttpRequest"

        /** 个人课表的功能模块号（实测；信息查询 → 个人课表）*/
        const val GNMKDM_KB = "N2151"

        /** 周次课表的功能模块号（实测；信息查询 → 周次课表）*/
        const val GNMKDM_ZC = "N2154"

        /** 正方登录页特征串：响应里出现它说明登录态已失效 */
        const val LOGIN_PAGE_MARK = "login_slogin"

        /** 服务票据前缀（换票接口成功时直接返回 `ST-…` 纯文本）*/
        const val ST_PREFIX = "ST-"
    }
}
