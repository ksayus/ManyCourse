package com.tof.manycourse.api

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okio.IOException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * HTTP 客户端。
 *
 * 两套用法并存，不要混：
 *  - `get` / `post`：**同步**返回 String，原有接口，保持不动（调用方自己保证不在主线程）。
 *  - `getAsync` / `postFormAsync`：**异步** + 返回 [HttpResponse] 快照，登录流程用这套。
 *    回调在 OkHttp 的工作线程上触发，调用方（Activity）必须自己切回主线程。
 *
 * 与旧版的两点关键差异，都是教务系统接入踩过的坑：
 *  1. **Cookie 会话**：教务系统登录靠 `JSESSIONID` / `CSRFTOKEN` 维持，
 *     而 OkHttp 默认**不保存** Cookie，第二步请求就掉登录态。这里加了内存 CookieJar。
 *  2. **表单提交**：教务系统登录页是 `<form>`，不是 JSON。
 *     `postFormAsync` 用 `application/x-www-form-urlencoded`（UTF-8）提交。
 *
 * 每个 [HttpMethod] 实例 = 一个独立的 Cookie 会话，
 * 所以不同学校、不同用户的登录态天然隔离（`SchoolApi` 各自持有一个实例）。
 */
class HttpMethod {

    /**
     * 响应快照：登录判定需要「状态码 / 跳转地址 / 正文」三样，
     * 而 OkHttp 的 Response 用完就关（body 只能读一次），所以先落成不可变快照再交给业务。
     */
    data class HttpResponse(
        val code: Int,
        val body: String,
        /** 重定向目标（登录成功的典型信号：302 → 首页） */
        val location: String?,
        /** 跟随重定向后的最终地址 */
        val finalUrl: String,
        /**
         * 服务端时间（HTTP `Date` 响应头，毫秒）；取不到为 null。
         *
         * 金智 CAS 的登录请求要带一个 `RSA(TAG + 服务端毫秒时间)` 的反重放头 ——
         * 它刻意用**服务端时间**而不是本地时间，所以这里必须把 `Date` 头留下来。
         * 用本机时间的话，设备时钟偏一点就会被判成过期。
         */
        val serverTimeMillis: Long? = null,
    ) {
        val isRedirect: Boolean get() = code in 300..399
    }


    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 登录成功后教务系统通常 302 跳首页，必须跟随，否则判定不了登录态
        .followRedirects(true)
        .followSslRedirects(true)
        // 必须放在日志拦截器之前，这样日志里看到的就是真正发出去的地址。
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (cookies.isEmpty()) return
                val jar = cookieStore.getOrPut(url.host) { mutableListOf() }
                synchronized(jar) {
                    cookies.forEach { cookie ->
                        // 同名 Cookie 覆盖：教务系统重新登录会下发新的 JSESSIONID
                        jar.removeAll { it.name == cookie.name }
                        jar.add(cookie)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val jar = cookieStore[url.host] ?: return emptyList()
                val now = System.currentTimeMillis()
                synchronized(jar) {
                    // 顺带滤掉已过期的：进程活着期间 Cookie 也可能过期，
                    // 带上一堆死 Cookie 只会让服务端把我们当成未登录
                    // （与 MemoryCookieJar 的处理保持一致）
                    return jar.filter { it.matches(url) && it.expiresAt > now }
                }
            }
        })
        .addInterceptor(HttpLoggingInterceptor { message -> logRedactedSensitiveFields(message) }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    public fun get(url: String): String {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful){
                throw IOException("Unexpected code $response")
            }
            return response.body?.string() ?: ""
        }
    }

    public fun post(url: String, json: JSONObject): String {
        val requestBody = json.toString().toRequestBody("application/json, charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            return response.body?.string() ?: ""
        }
    }

    /** 同步 GET，返回响应快照（要在协程 / 子线程里调） */
    fun getResponse(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse = client.newCall(buildRequest(url, headers)).execute().use { it.toSnapshot() }

    /** 同步表单 POST，返回响应快照（要在协程 / 子线程里调） */
    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse =
        client.newCall(buildFormRequest(url, form, headers)).execute().use { it.toSnapshot() }

    /**
     * 异步 GET。回调在 OkHttp 工作线程，别在里面碰 View。
     * 用途：登录前先 GET 登录页拿 CSRFTOKEN / 公钥。
     */
    fun getAsync(
        url: String,
        headers: Map<String, String> = emptyMap(),
        callback: (Result<HttpResponse>) -> Unit,
    ) = enqueue(buildRequest(url, headers), callback)

    /**
     * 异步表单 POST（`application/x-www-form-urlencoded`，UTF-8）。
     * 教务系统的登录表单基本都是这一种，中文账号/密码也不会乱码。
     */
    fun postFormAsync(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        callback: (Result<HttpResponse>) -> Unit,
    ) = enqueue(buildFormRequest(url, form, headers), callback)

    /** 读取某个地址当前会带上的 Cookie 值（登录流程要回填 csrftoken 时会用到） */
    fun cookieValue(url: String, name: String): String? =
        cookiesFor(url).firstOrNull { it.name == name }?.value

    /** 当前会话在某个地址上的全部 Cookie */
    fun cookiesFor(url: String): List<Cookie> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()
        return client.cookieJar.loadForRequest(httpUrl)
    }

    /** 清空会话（换学校 / 退出登录时调，避免带着上一个账号的登录态） */
    fun clearCookies() = cookieStore.clear()

    // ── 会话持久化 ──────────────────────────────────────────────────────────
    //
    // 用途：App 被杀掉/重装页面之后还要「维持登录」，而登录态全在 Cookie 里
    // （教务系统靠 `ASP.NET_SessionId` / `.Yyw.Base` 这类 Cookie 认人），
    // 所以必须能把整个会话取出来存盘、下次再灌回去。

    /** 把当前会话里的全部 Cookie 导出成可持久化的结构 */
    fun exportCookies(): List<PersistedCookie> = cookieStore.entries.flatMap { (host, jar) ->
        synchronized(jar) {
            jar.map { cookie ->
                PersistedCookie(
                    host = host,
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path,
                    expiresAt = cookie.expiresAt,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    hostOnly = cookie.hostOnly,
                )
            }
        }
    }

    /**
     * 把导出的 Cookie 灌回会话。**逐条做，单条失败只跳过那一条** ——
     * 一条 Cookie 解析不了（比如服务端下发过奇怪的值）不该让整个登录态起不来。
     *
     * 已过期的直接丢：冷启动恢复出来的存档可能放了好几天，
     * 灌一堆死 Cookie 进去毫无意义。
     *
     * @return 真正灌进去的条数。调用方据此判断"这次恢复到底有没有恢复出东西"——
     *   全部过期时应当回落到登录页，而不是让用户进到主界面再看一堆报错。
     */
    fun importCookies(cookies: List<PersistedCookie>): Int {
        val now = System.currentTimeMillis()
        var imported = 0
        cookies.forEach { persisted ->
            if (persisted.expiresAt <= now) return@forEach
            val cookie = persisted.toOkHttpCookie() ?: return@forEach
            val jar = cookieStore.getOrPut(persisted.host) { mutableListOf() }
            synchronized(jar) {
                // 同一 host 下同名 Cookie 覆盖，和 saveFromResponse 的策略保持一致
                jar.removeAll { it.name == cookie.name }
                jar.add(cookie)
            }
            imported++
        }
        return imported
    }

    private fun PersistedCookie.toOkHttpCookie(): Cookie? = runCatching {
        Cookie.Builder()
            .name(name)
            .value(value)
            // hostOnly 决定要不要写前导点：`hostOnlyDomain` 是精确匹配，
            // `domain` 是子域匹配。两者混了会出现"Cookie 存进去了但请求不带"
            .apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
            .path(path)
            .expiresAt(expiresAt)
            .apply {
                if (secure) secure()
                if (httpOnly) httpOnly()
            }
            .build()
    }.getOrNull()

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun buildRequest(url: String, headers: Map<String, String>): Request =
        Request.Builder()
            .url(url)
            .apply {
                defaultHeaders.forEach { (k, v) -> header(k, v) }
                headers.forEach { (k, v) -> header(k, v) }
                get()
            }
            .build()

    private fun buildFormRequest(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): Request {
        val body = FormBody.Builder().apply {
            form.forEach { (name, value) -> add(name, value) }
        }.build()
        return Request.Builder()
            .url(url)
            .apply {
                defaultHeaders.forEach { (k, v) -> header(k, v) }
                headers.forEach { (k, v) -> header(k, v) }
            }
            .post(body)
            .build()
    }

    private fun enqueue(request: Request, callback: (Result<HttpResponse>) -> Unit) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { callback(runCatching { it.toSnapshot() }) }
            }
        })
    }

    private fun Response.toSnapshot(): HttpResponse = HttpResponse(
        code = code,
        body = body?.string().orEmpty(),
        location = header("Location"),
        finalUrl = request.url.toString(),
        // 解析失败就当没有：调用方（CAS 登录）会退化成用本机时间，不至于整条流程失败
        serverTimeMillis = header("Date")?.let { runCatching { parseHttpDate(it) }.getOrNull() },
    )

    /** 解析 HTTP-date（RFC 7231 的三种格式 OkHttp 都能给；这里走最常用的 IMF-fixdate） */
    private fun parseHttpDate(value: String): Long =
        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .parse(value, java.time.Instant::from)
            .toEpochMilli()

    private companion object {
        /**
         * 部分教务系统会按 UA 判浏览器（非浏览器 UA 直接返回错误页），
         * 统一伪装成手机 Chrome；`Accept-Language` 保证服务端回中文错误文案，否则关键字判定失效。
         */
        val defaultHeaders = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )

        /** 日志 TAG：`adb logcat -s OkHttp:D` */
        const val LOG_TAG = "OkHttp"

        /**
         * 表单里凡是**密码**字段名，值一律在日志里打码。
         *
         * 为什么必须做：`HttpLoggingInterceptor` 是 **BODY 级别**（排查接口基本全靠它），
         * 而它会把整个请求体原样打出来。金城学院的老系统（`/EaWeb/Manager/Login.aspx`）
         * **密码是明文提交的**，不处理的话 `adb logcat` 就能读到所有人的密码 ——
         * 登录页自己的 Log 特意不打密码，不能从这条路上又漏出去。
         *
         * 覆盖到各家系统的字段名：`LoginPass`（金城学院）、`mm`（正方加密后的密码）、
         * 通用 `password` / `pwd` / `passwd`。正方那个虽然是密文，
         * 但每次登录都不同、也没有排查价值，一并打码。
         */
        val SENSITIVE_FIELDS = listOf(
            "LoginPass", "password", "Password", "pwd", "passwd", "mm", "U_Password",
        )

        /**
         * 把日志文本里敏感字段的值换成 `***`。
         *
         * 同时认两种写法：
         *  - 表单（urlencoded）：`LoginPass=值` —— 字段名前必须是**行首 / `&` / `?` / 空白**，
         *    否则 `summ=…` 这种会被误伤成 `sum***`;
         *  - JSON：`"password":"值"`。
         */
        fun logRedactedSensitiveFields(message: String): String {
            var redacted = message
            SENSITIVE_FIELDS.forEach { field ->
                val escaped = Regex.escape(field)
                redacted = redacted.replace(
                    Regex("(^|[&?\\s])($escaped=)[^&\\r\\n]*", RegexOption.MULTILINE),
                    "$1$2***",
                )
                redacted = redacted.replace(
                    Regex("(\"$escaped\"\\s*:\\s*\")[^\"]*(\")"),
                    "$1***$2",
                )
            }
            Log.d(LOG_TAG, redacted)
            return redacted
        }
    }
}
