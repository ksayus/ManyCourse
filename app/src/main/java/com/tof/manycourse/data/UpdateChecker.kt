package com.tof.manycourse.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 一次检查更新的结果。
 *
 * @param update 找到的新版本；null = 已经是最新（或两个源都没查到可用的）
 * @param currentVersion 本机版本（`PackageManager` 读的，与设置页显示的是同一个来源）
 * @param failures **每个源各自的失败原因**（成功的源不在里面）。
 *   为什么不让失败静默：用户点了"检查更新"却什么都没发生的话，他会以为是 App 坏了 ——
 *   而真实原因可能是"GitHub 在这台手机上连不上"。两个源都失败时更要说清楚。
 */
data class UpdateCheckResult(
    val update: AvailableUpdate?,
    val currentVersion: String,
    val failures: Map<UpdateSource, String> = emptyMap(),
    /**
     * **根本没去查**的源（现在只有一种情况：Gitee 没配仓库路径）。
     *
     * 和 [failures] 分开是因为它不是错误 —— 设置页已经写了"Gitee 未配置"，
     * 这里再报一次会让"检查更新"看起来像失败了。
     */
    val skipped: Set<UpdateSource> = emptySet(),
) {
    /** 两个源都试过了、一个都没成 */
    val allFailed: Boolean get() = update == null && failures.size >= 2
}

/**
 * **检查更新**：把 GitHub 与 Gitee 的 Release 拉回来，挑出版本更高的那个。
 *
 * ## 为什么是"两个源都查"而不是"一个失败再查另一个"
 *
 * 两个源**互相不知道对方有没有更新**（GitHub 推了 tag 不会自动出现在 Gitee），
 * 而且国内网络下 GitHub 时通时不通。所以默认并行查两个、取版本高的那个：
 * 这样既不会因为某个源落后而漏掉更新，也不会因为某个源连不上就整个功能失效。
 * 用户在设置里也可以只选一个源（[UpdateSourcePreference]）。
 *
 * ## 这一层只做两件事
 *
 *  1. **HTTP**：拿两个平台的 Release JSON（各带各的请求头）；
 *  2. **摊平**：把 JSON 变成中立的 [ReleaseCandidate]。
 *
 * 之后"挑哪个版本、挑哪个附件"全交给 [latestUpdate]（纯函数、JVM 单测覆盖）——
 * 那部分错了的表现是"永远提示有更新"或"永远不提示"，是最该被测住的一类；
 * 而 JSON 那层只能靠 `UpdateJsonTest`（插桩）守。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** 更新检查**不该让用户等太久**：连不上就是连不上，8 秒够了（GitHub 在国内经常直接超时）*/
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 本机版本名（`1.2.0.3`）；读不到时给一个不可能被当成"新版本"的占位。
     *
     * 走 `PackageManager` 而不是 `BuildConfig.VERSION_NAME`：后者要在模块里打开
     * `buildFeatures { buildConfig = true }`，而这里只是想显示/比较一个字符串 ——
     * 不值得为它改构建配置（`BuildConfig` 里其它字段本项目一个都没用）。
     */
    fun currentVersion(context: Context): String =
        runCatching { versionNameOf(context) }.getOrNull().orEmpty().ifBlank { "0" }

    private fun versionNameOf(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION") // 33 起换成了 PackageInfoFlags 那个重载，老机器仍要走这个
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }

    /**
     * 检查更新。
     *
     * @param preference 用户在设置里选的更新源（默认 [UpdateSourcePreference.Auto] = 两个都查）
     */
    suspend fun check(context: Context, preference: UpdateSourcePreference): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            val current = currentVersion(context)
            val requested = when (preference) {
                UpdateSourcePreference.Auto -> listOf(UpdateSource.GitHub, UpdateSource.Gitee)
                UpdateSourcePreference.GitHub -> listOf(UpdateSource.GitHub)
                UpdateSourcePreference.Gitee -> listOf(UpdateSource.Gitee)
            }
            // 没配 Gitee 仓库的源**不算失败**，直接跳过（见 UpdateCheckResult.skipped 的注释）
            val skipped = requested.filter { it == UpdateSource.Gitee && UpdateConfig.giteeReleasesApi == null }.toSet()
            val sources = requested - skipped

            val failures = mutableMapOf<UpdateSource, String>()
            // ★ 每个结果都要**带上自己是哪个源**：少了这一步，"哪个源失败了"就无从谈起
            val found: List<Pair<UpdateSource, Result<List<ReleaseCandidate>>>> = coroutineScope {
                sources.map { source ->
                    async {
                        source to when (source) {
                            UpdateSource.GitHub -> fetch(source, UpdateConfig.githubReleasesApi)
                            // 上面已经把"没配 Gitee"的源过滤掉了，这里 url 必然存在
                            UpdateSource.Gitee -> fetch(source, UpdateConfig.giteeReleasesApi.orEmpty())
                        }
                    }
                }.map { it.await() }
            }

            val perSource = found.map { (source, result) ->
                result.fold(
                    onSuccess = { releases -> source to latestUpdate(releases, current, source) },
                    onFailure = { error ->
                        failures[source] = error.message ?: error.javaClass.simpleName
                        Log.w(TAG, "$source 查更新失败：${error.message}")
                        source to null
                    },
                )
            }

            UpdateCheckResult(
                update = bestOf(perSource),
                currentVersion = current,
                failures = failures,
                skipped = skipped,
            )
        }

    /** 拉一个源的 Release 列表并摊平成中立的候选 */
    private fun fetch(source: UpdateSource, url: String): Result<List<ReleaseCandidate>> = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UpdateConfig.USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 带上状态码：403 多半是 GitHub 限流（未认证 60 次/小时/IP），404 是仓库路径写错了
                error("HTTP ${response.code}${if (body.isBlank()) "" else "：" + body.take(120)}")
            }
            parseReleases(body, source)
        }
    }
}

/**
 * 把平台的 Release JSON 摊平成 [ReleaseCandidate] 列表。
 *
 * 两个平台的字段名基本一致（都源自同一个设计）：`tag_name` / `name` / `body` /
 * `prerelease` / `draft` / `assets[]`（`name` / `browser_download_url` / `size`）。
 * 所以**一个解析器吃两份数据**，只在"发版页面地址"上分叉（GitHub 拼 `/releases/tag/x`，
 * Gitee 拼 `/releases/tag/x`，两边路径一致）。
 *
 * 解析原则与项目里其它 JSON 适配层一致：**坏字段降级、不要抛**——
 * 一个 Release 少了 `body` 不该让"更新检查"整个失败。
 */
internal fun parseReleases(json: String, source: UpdateSource): List<ReleaseCandidate> {
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val version = item.optString("tag_name").ifBlank { item.optString("name") }
        if (version.isBlank()) return@mapNotNull null
        ReleaseCandidate(
            version = version,
            notes = item.optString("body"),
            pageUrl = releasePageUrl(source, version),
            prerelease = item.optBoolean("prerelease", false),
            draft = item.optBoolean("draft", false),
            assets = parseAssets(item.optJSONArray("assets")),
        )
    }
}

/** 附件数组 → [AssetRef]；拿不到下载地址的附件直接丢掉（留着也没法下载）*/
private fun parseAssets(array: JSONArray?): List<AssetRef> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val name = item.optString("name")
        val url = item.optString("browser_download_url")
        if (name.isBlank() || url.isBlank()) return@mapNotNull null
        AssetRef(name = name, url = url, sizeBytes = item.optLong("size", 0L))
    }
}

/** 某个版本在平台上的页面地址（下载不了时给用户一个"去浏览器看"的出口）*/
private fun releasePageUrl(source: UpdateSource, version: String): String = when (source) {
    UpdateSource.GitHub -> "${UpdateConfig.githubReleasesPage}/tag/$version"
    UpdateSource.Gitee -> UpdateConfig.giteeReleasesPage?.let { "$it/tag/$version" }.orEmpty()
}

/** 本机装的是不是"可安装未知来源应用"（下载完要装的时候才需要）*/
internal fun canInstallPackages(context: Context): Boolean =
    context.packageManager.canRequestPackageInstalls()
