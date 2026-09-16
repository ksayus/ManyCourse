package com.tof.manycourse.gr_api.schools

import java.util.Base64

/**
 * 广软 CAS 的会话存档：**TGT + Cookie**。
 *
 * ## 为什么要多存一个 TGT
 *
 * 正方那边的登录态全在 Cookie（`JSESSIONID`），但它是**会话级**的：服务端一超时、
 * 或者 App 隔天再打开，那份 Cookie 就是废纸，用户只能重新走一遍
 * 「账号 + 密码 + 看验证码图算加法」——验证码这一步没法自动化，体验很差。
 *
 * CAS 侧则留了一张**可以反复使用的票据**：登录成功时 `POST /lyuapServer/v1/tickets`
 * 的响应里除了给正方用的 ST，还有一个 `tgt`（Ticket Granting Ticket）。
 * 拿着纯 TGT 字符串（**不需要任何 Cookie、不需要密码、不需要验证码**）再 `POST`
 * 一次 `/lyuapServer/v1/tickets/<TGT>`，就能换出一张**新的 ST**，
 * 用这张 ST 走一遍 `sso/lyiotlogin` 就是一个全新的正方会话。
 *
 * 所以存档里只要还留着 TGT，App 冷启动后即使 Cookie 早就死了也能**静默复活**。
 *
 * ## 格式
 *
 * ```
 * manycourse-gzus-session/1
 * tgt=<URL-safe Base64>
 * cookies=<URL-safe Base64>   ← 里面是 api/CookieCodec 的文本，可以有换行
 * ```
 *
 * 两个字段都做 Base64：`cookies` 那一段是**多行文本**，不编码会把信封撑破。
 * 带版本号是为了以后换格式时能按版本分流，而不是拿旧存档硬解出一堆空值。
 *
 * 纯逻辑（不碰 Android / 不碰网络），单测见 `GzusSessionCodecTest`。
 */
internal data class GzusSession(
    /** CAS 的 TGT；没有（或那次登录响应里没带）就是空串 */
    val tgt: String = "",
    /** Cookie 会话原文（`api/CookieCodec` 的格式），可能为空 */
    val cookies: String = "",
) {
    /** 两个都没有 = 这份存档没有任何可用信息 */
    val isEmpty: Boolean get() = tgt.isBlank() && cookies.isBlank()
}

internal object GzusSessionCodec {

    const val HEADER = "manycourse-gzus-session/1"

    private const val KEY_TGT = "tgt"
    private const val KEY_COOKIES = "cookies"

    fun encode(session: GzusSession): String = buildString {
        append(HEADER).append('\n')
        append(KEY_TGT).append('=').append(base64(session.tgt)).append('\n')
        append(KEY_COOKIES).append('=').append(base64(session.cookies)).append('\n')
    }

    /** 不是本格式 / 解不出来返回 null（调用方据此判断"这份存档认不认得"）*/
    fun decode(text: String): GzusSession? {
        if (!hasOurHeader(text)) return null
        val fields = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val at = line.indexOf('=')
            if (at > 0) fields[line.substring(0, at)] = line.substring(at + 1)
        }
        val tgt = fields[KEY_TGT]?.let(::unbase64) ?: return null
        val cookies = fields[KEY_COOKIES]?.let(::unbase64) ?: return null
        return GzusSession(tgt = tgt, cookies = cookies)
    }

    /** 头部是不是这份格式（用来区分"旧版本存档"和"损坏的存档"）*/
    fun hasOurHeader(text: String): Boolean = text.startsWith(HEADER)

    private fun base64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unbase64(value: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
}
