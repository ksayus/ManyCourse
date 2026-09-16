package com.tof.manycourse.data

import java.util.Base64

/**
 * 待持久化的登录会话（**纯数据 + 纯编解码，可单测**）。
 *
 * 拆成独立一层的原因和 `api/CookieCodec` 一样：`SessionStore` 要碰
 * Android Keystore 与 SharedPreferences（JVM 单测跑不了），
 * 但"会话内容怎么变成一行文本、又怎么变回来"是纯逻辑，必须能测。
 *
 * @param schoolId 会话属于哪所学校（恢复时要按它找回同一个 `SchoolApi` 实例）
 * @param account  登录账号。**只存在这里** —— 它是 PII，
 *   放在这个会被加密的 blob 里，比另外塞进明文 SharedPreferences 干净
 * @param localDebug 是不是"本地调试账号"（不连教务系统，恢复后也不用同步数据）
 * @param cookies  Cookie 会话原文（`api/CookieCodec` 的格式），对这里是不透明的字符串
 */
internal data class SessionBlob(
    val schoolId: String,
    val account: String,
    val localDebug: Boolean,
    val cookies: String,
)

/**
 * [SessionBlob] 的文本编解码。
 *
 * 格式：一行一个键，值统一做 URL-safe Base64 ——
 * `account` 和 `cookies` 里可能有换行/制表符/中文，不编码就会把格式撑破。
 *
 * ```
 * manycourse-session/1
 * schoolId=bmhqY3h5
 * account=MDIyMjAxMDEwMg
 * debug=0
 * cookies=…
 * ```
 *
 * 带版本号是为了以后加字段（比如会话时间戳）时能按版本分流，
 * 而不是拿旧数据硬解出一堆空值。
 */
internal object SessionBlobCodec {

    const val HEADER = "manycourse-session/1"

    private const val KEY_SCHOOL = "schoolId"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_DEBUG = "debug"
    private const val KEY_COOKIES = "cookies"

    fun encode(blob: SessionBlob): String = buildString {
        append(HEADER).append('\n')
        append(KEY_SCHOOL).append('=').append(base64(blob.schoolId)).append('\n')
        append(KEY_ACCOUNT).append('=').append(base64(blob.account)).append('\n')
        append(KEY_DEBUG).append('=').append(if (blob.localDebug) "1" else "0").append('\n')
        append(KEY_COOKIES).append('=').append(base64(blob.cookies)).append('\n')
    }

    /** 解不出来就返回 null（调用方清掉存档，让用户重新登录，而不是崩） */
    fun decode(text: String): SessionBlob? {
        val fields = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val at = line.indexOf('=')
            if (at > 0) fields[line.substring(0, at)] = line.substring(at + 1)
        }
        val schoolId = fields[KEY_SCHOOL]?.let(::unbase64)
        val account = fields[KEY_ACCOUNT]?.let(::unbase64)
        val cookies = fields[KEY_COOKIES]?.let(::unbase64)
        if (schoolId.isNullOrBlank() || account.isNullOrBlank() || cookies == null) return null
        return SessionBlob(
            schoolId = schoolId,
            account = account,
            localDebug = fields[KEY_DEBUG] == "1",
            cookies = cookies,
        )
    }

    /** 头部是不是这份格式（用于区分"旧版本存档"和"损坏的存档"）*/
    fun hasOurHeader(text: String): Boolean = text.startsWith(HEADER)

    private fun base64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unbase64(value: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
}
