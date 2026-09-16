package com.tof.manycourse.api

import java.util.Base64

/**
 * 一条可持久化的 Cookie。
 *
 * 为什么不直接序列化 `okhttp3.Cookie`：那是 OkHttp 的内部类型，
 * 字段和构造器在版本之间变动过（`sameSite` 就是后加的）。
 * 自己定义一份**只包含重建 Cookie 所需字段**的结构，
 * 升级 OkHttp 时不会因为反序列化失败把用户的登录态搞丢。
 *
 * [host] 是 [HttpMethod] 里 Cookie 分桶的键（请求的 host），
 * 必须一起存 —— 只存 `domain` 的话，带前导点的 `.nhjcxy.edu.cn`
 * 会被还原到另一个桶里，等于没登录。
 */
data class PersistedCookie(
    val host: String,
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

/**
 * `List<PersistedCookie>` 与一行行文本之间的编解码（**纯 JVM，可单测**）。
 *
 * 格式：
 * ```
 * manycourse-cookies/1
 * <host>\t<name>\t<value>\t<domain>\t<path>\t<expiresAt>\t<secure>\t<httpOnly>\t<hostOnly>
 * …
 * ```
 * 每个字段各自做 **URL-safe Base64**，所以字段里出现制表符、换行、
 * 中文、`=` 都不会把格式撑破 —— 教务系统的 Cookie 值里啥都有
 * （`U_NameCn` 就是 `%e7%8e%8b…` 这种 URL 编码串）。
 *
 * 头部带版本号：以后字段变了可以按版本分流，而不是拿旧数据硬解。
 */
object CookieCodec {

    /** 格式头 + 版本号。调用方可以用它先判断"这份数据是不是我认识的格式" */
    const val HEADER = "manycourse-cookies/1"

    private const val FIELD_SEPARATOR = '\t'

    fun encode(cookies: List<PersistedCookie>): String = buildString {
        append(HEADER).append('\n')
        cookies.forEach { cookie ->
            append(
                listOf(
                    cookie.host,
                    cookie.name,
                    cookie.value,
                    cookie.domain,
                    cookie.path,
                    cookie.expiresAt.toString(),
                    flag(cookie.secure),
                    flag(cookie.httpOnly),
                    flag(cookie.hostOnly),
                ).joinToString(FIELD_SEPARATOR.toString()) { base64(it) }
            )
            append('\n')
        }
    }

    /**
     * 解码。**任何一行坏掉就丢掉那一行**，不让整份会话失效 ——
     * 恢复登录态时"少一条非关键 Cookie"远好过"整份读不出来只能重新登录"。
     */
    fun decode(text: String): List<PersistedCookie> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty() || lines.first() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull { parse(it) }
    }

    private fun parse(line: String): PersistedCookie? = runCatching {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != 9) return@runCatching null
        PersistedCookie(
            host = unbase64(parts[0]),
            name = unbase64(parts[1]),
            value = unbase64(parts[2]),
            domain = unbase64(parts[3]),
            path = unbase64(parts[4]),
            expiresAt = unbase64(parts[5]).toLongOrNull() ?: return@runCatching null,
            // 注意：三个布尔标志在编码时也走了 Base64（所有字段统一处理，
            // 免得再解释"哪个字段是特例"）。这里必须同样 unbase64 回来 ——
            // 直接拿 "1"/"0" 去比会永远解成 false，把 secure / httpOnly
            // 这类属性悄悄丢掉（`CookieCodecTest` 守着这条）。
            secure = unbase64(parts[6]) == "1",
            httpOnly = unbase64(parts[7]) == "1",
            hostOnly = unbase64(parts[8]) == "1",
        )
    }.getOrNull()

    private fun flag(value: Boolean) = if (value) "1" else "0"

    private fun base64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unbase64(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
