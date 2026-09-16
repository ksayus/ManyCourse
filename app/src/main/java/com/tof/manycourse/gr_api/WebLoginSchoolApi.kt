package com.tof.manycourse.gr_api

import com.tof.manycourse.api.CookieCodec
import com.tof.manycourse.api.HttpMethod
import com.tof.manycourse.api.HttpMethod.HttpResponse

/**
 * 「表单登录」型教务系统的通用骨架 —— 新学校照着这个抄，通常只需要改 4 个东西。
 *
 * 默认流程（覆盖了绝大多数国内教务系统）：
 * ```
 *   prepare()  可选：GET 登录页拿 CSRFTOKEN / GET 公钥（拿到的字段会合并进表单）
 *      ↓
 *   表单 POST  { 用户名字段 = 账号, 密码字段 = encodePassword(密码), ...额外字段 }
 *      ↓
 *   judge()    判定成功/失败
 * ```
 *
 * 新学校三步走：
 *  1. 继承本类，填 [school]、[loginUrl]、[usernameField]、[passwordField]；
 *  2. 密码要加密的（正方等）覆写 [encodePassword]，并在 [prepare] 里取公钥；
 *  3. 用真机跑一次，看 Logcat 里 `HttpLoggingInterceptor` 打的请求/响应，
 *     按实际情况微调字段名与 [judge]。
 *
 * 拿不准登录地址时：电脑浏览器打开教务系统 → F12 → Network → 勾 **Preserve log** →
 * 登录一次 → 找 `POST` 且 `Content-Type: application/x-www-form-urlencoded` 的那条请求，
 * 它的 Request URL 就是 [loginUrl]，Form Data 里的 key 就是两个字段名。
 */
abstract class WebLoginSchoolApi : SchoolApi {

    /** 每个学校一个 HTTP 会话（独立 Cookie），互不串登录态 */
    protected val http = HttpMethod()

    /** 登录表单提交地址（完整 URL，不是相对路径） */
    protected abstract val loginUrl: String

    /** 表单里「用户名」的字段名，如 `yhm` / `username` / `userAccount` */
    protected abstract val usernameField: String

    /** 表单里「密码」的字段名，如 `mm` / `password` / `userPassword` */
    protected abstract val passwordField: String

    /**
     * 密码字段的值怎么来的。默认原样提交；
     * 需要 RSA / MD5 的学校覆写这里（正方见 `GzusApi`）。
     */
    protected open fun encodePassword(password: String): String = password

    /**
     * 登录前的准备工作，返回**要合并进登录表单的额外字段**。
     *
     * 典型用途：先 GET 登录页拿到 `CSRFTOKEN`，或先取 RSA 公钥缓存起来给
     * [encodePassword] 用。默认什么都不做。
     *
     * 注意：`callback` 必须且只能被调用一次（两条分支都要覆盖）。
     */
    protected open fun prepare(callback: (Result<Map<String, String>>) -> Unit) {
        callback(Result.success(emptyMap()))
    }

    /** 登录结果判定。默认走关键字启发式，学校特殊就覆写 */
    protected open fun judge(response: HttpResponse): LoginResult = LoginJudge.byKeywords(response)

    // ── 会话持久化 ──────────────────────────────────────────────────────────
    //
    // 大多数学校共用「一个 HttpMethod 就是整个登录态」这套模型，
    // 所以这里给一份通用实现，具体学校不用重复写。

    /** 导出 Cookie 会话（含版本头；没有 Cookie 说明没登录过，返回 null 表示不存盘）*/
    override fun exportSession(): String? {
        val cookies = http.exportCookies()
        if (cookies.isEmpty()) return null
        return CookieCodec.encode(cookies)
    }

    override fun importSession(data: String): Boolean {
        if (data.isBlank()) return true
        // 头部对不上 = 不是这份格式的数据（比如旧版本存的），别硬灌
        if (!data.startsWith(CookieCodec.HEADER)) return false
        // 一条都没灌进去（全过期了 / 全解不出来）= 这次恢复等于没恢复
        return http.importCookies(CookieCodec.decode(data)) > 0
    }

    override fun clearSession() {
        http.clearCookies()
    }

    override fun login(
        account: String,
        password: String,
        captcha: LoginCaptcha?,
        callback: (LoginResult) -> Unit,
    ) {
        if (!configured) {
            callback(
                LoginResult.Pending(
                    "「${school.name}」的登录接口还没接入：" +
                        "请在 ${this::class.java.simpleName} 里补全 loginUrl 与表单字段后重试"
                )
            )
            return
        }

        // 这个骨架对应的是"一次表单 POST 就完事"的学校，登录页上没有验证码位。
        // 如果哪所学校真要用验证码，说明它的流程比骨架复杂（参见 GzusApi 的 CAS 登录），
        // 那就该直接实现 SchoolApi，而不是硬塞进这里 —— 与其静默忽略，不如明确报错。
        if (captcha != null) {
            callback(
                LoginResult.Failure(
                    "「${school.name}」的表单登录骨架不支持验证码，请改用带验证码流程的实现"
                )
            )
            return
        }

        prepare { prepared ->
            prepared.fold(
                onSuccess = { extra -> submit(account, password, extra, callback) },
                onFailure = { callback(LoginResult.Failure("无法连接教务系统：${it.readableMessage()}")) },
            )
        }
    }

    private fun submit(
        account: String,
        password: String,
        extra: Map<String, String>,
        callback: (LoginResult) -> Unit,
    ) {
        val form = LinkedHashMap<String, String>()
        form[usernameField] = account
        form[passwordField] = encodePassword(password)
        form.putAll(extra)

        http.postFormAsync(loginUrl, form) { result ->
            callback(
                result.fold(
                    onSuccess = { judge(it) },
                    onFailure = { LoginResult.Failure("网络异常：${it.readableMessage()}") },
                )
            )
        }
    }
}
