package com.tof.manycourse.data

/**
 * 版本号的**解析、比较**，以及"从一次 Release 的资产里挑出该下载哪个 APK" —— 纯逻辑，可 JVM 单测。
 *
 * 为什么要自己比而不是直接用字符串：本项目的版本号是**四段**的
 * （`major.minor.bugfix.学校数`，见 `app/build.gradle.kts` 的注释），
 * 字符串比较会把 `1.10.0.3` 判成比 `1.9.0.3` **小**，那会让更新提示永远不出现（或一直出现）。
 */

/**
 * 把版本串解析成数字段。
 *
 * 容忍常见的写法差异：`v1.2.0.3`、`ManyCourse 1.2.0.3`、`release-1.2.0.3` 都取到 `[1,2,0,3]`。
 * 取不到数字就返回空表（调用方据此判定"这个 tag 不是版本号"，例如 `nightly`）。
 *
 * @return 数字段；**末尾的 0 会被保留**（`1.2` 与 `1.2.0` 判等由 [compareVersionParts] 处理）
 */
internal fun parseVersionParts(text: String): List<Int> {
    val numbers = Regex("\\d+").findAll(text).map { it.value.toIntOrNull() ?: 0 }.toList()
    // 全是 0 段（比如 tag 叫 "0"）没意义；至少要有一个数字才算版本号
    return if (numbers.isEmpty()) emptyList() else numbers
}

/**
 * 比较两串版本号。
 *
 * 规则：逐段比数字，**短的补 0**（`1.2` == `1.2.0`）；
 * 完全解析不出数字时退化成字符串比较（宁可给个结果，也不要"什么都没有"）。
 *
 * @return 负数 = [left] 更旧；0 = 相同；正数 = [left] 更新
 */
internal fun compareVersionParts(left: String, right: String): Int {
    val a = parseVersionParts(left)
    val b = parseVersionParts(right)
    if (a.isEmpty() || b.isEmpty()) return left.compareTo(right, ignoreCase = true)

    val size = maxOf(a.size, b.size)
    for (index in 0 until size) {
        val diff = (a.getOrElse(index) { 0 }) - (b.getOrElse(index) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

/** [remote] 是不是比 [local] 新（自动更新唯一要回答的问题）*/
internal fun isNewerVersion(remote: String, local: String): Boolean =
    compareVersionParts(remote, local) > 0

/**
 * 从一次 Release 的**资产名列表**里挑出该下载的那个 APK。
 *
 * 现实里一个 Release 里可能躺着好几个文件（本项目就有 `ManyCourse-1.2.0.3.apk` 和
 * `apk.sha256`，CI 的构建产物里还有 `ManyCourse-debug-<run>-<sha>.apk`），挑错就等于
 * **把调试包当正式包推给用户**，所以规则要写死：
 *
 *  1. 只认 `.apk` 结尾；
 *  2. **排除**名字里带 `debug` / `unsigned` 的（调试包、未签名包装不上或不该装）；
 *  3. 优先名字里带**这次版本号**的（`ManyCourse-1.2.0.3.apk`）；
 *  4. 其次优先带 `release` 的；
 *  5. 仍然并列就取**名字最长**的那个（`ManyCourse-release-1.2.0.3.apk` 比 `a.apk` 更像正式包）。
 *
 * @return 选中的资产名；一个都没有返回 null（调用方据此说"这个版本没有可下载的 APK"）
 */
internal fun pickApkAsset(names: List<String>, version: String): String? {
    val candidates = names.filter { it.endsWith(".apk", ignoreCase = true) }
    if (candidates.isEmpty()) return null

    val usable = candidates.filterNot { name ->
        val lower = name.lowercase()
        lower.contains("debug") || lower.contains("unsigned")
    }
    // 全被排除了（比如只发了一个 debug 包）：**明说挑不出来**，而不是把 debug 包推给用户
    if (usable.isEmpty()) return null

    val normalizedVersion = version.removePrefix("v").lowercase()
    return usable.maxWithOrNull(
        compareBy(
            { if (normalizedVersion.isNotBlank() && it.lowercase().contains(normalizedVersion)) 1 else 0 },
            { if (it.lowercase().contains("release")) 1 else 0 },
            { it.length },
        )
    )
}

/** 下载体积的展示文案：`12.3 MB` */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "大小未知"
    bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

// ── Release 的抽象 ───────────────────────────────────────────────────────
// 两个平台返回的 JSON 字段名几乎一样（tag_name / name / body / prerelease / draft / assets），
// 但**解析那一层不参与单测**（org.json 在 JVM 单测里是未实现 stub，见 GzusScheduleJsonTest 的注释）。
// 所以：网络层只负责把 JSON 摊成下面这两个纯数据结构，**挑哪个版本、挑哪个资产全在这之上做**，
// 于是这部分能用普通 JVM 单测钉住 —— 它错了的表现是"永远提示有新版本"或"永远不提示"。

/** Release 里的一个附件（下载地址 + 名字 + 体积）*/
internal data class AssetRef(
    val name: String,
    val url: String,
    val sizeBytes: Long = 0L,
)

/** 从两个平台拉回来的**中立** Release：字段口径与平台无关 */
internal data class ReleaseCandidate(
    val version: String,
    val notes: String = "",
    /** 发版页面（拿不到 APK 时给用户一个"去浏览器看"的出口）*/
    val pageUrl: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<AssetRef> = emptyList(),
)

/** 真正要提示给用户的那个更新 */
data class AvailableUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    val source: UpdateSource,
    val pageUrl: String,
)

/** 逐段比较两个 Release 的版本号（短的补 0），用于"从一堆 Release 里挑最新" */
private val releaseVersionComparator: Comparator<ReleaseCandidate> =
    Comparator { left, right -> compareVersionParts(left.version, right.version) }

/**
 * ★ **从一堆 Release 里挑出该提示的那个更新** —— 纯函数。
 *
 * 规则（每条都对应一个真实会踩的坑）：
 *  1. 跳过 `prerelease` 与 `draft`（**草稿根本不该被用户看到**，预览版也不该自动推）；
 *  2. **按版本号从高到低**找第一个"版本比当前新、而且有可下载 APK"的 ——
 *     不能只看列表第一个：最新的那个 Release 可能只有源码包没传 APK；
 *  3. 版本号比较用 [compareVersionParts]（四段数字），不能用字符串。
 *
 * @return null = 没有比 [currentVersion] 更新的可用版本
 */
internal fun latestUpdate(
    releases: List<ReleaseCandidate>,
    currentVersion: String,
    source: UpdateSource,
): AvailableUpdate? {
    val usable = releases
        .filterNot { it.prerelease || it.draft }
        .sortedWith(releaseVersionComparator.reversed())

    usable.forEach { release ->
        if (!isNewerVersion(release.version, currentVersion)) return@forEach
        val assetName = pickApkAsset(release.assets.map { it.name }, release.version) ?: return@forEach
        val asset = release.assets.first { it.name == assetName }
        if (asset.url.isBlank()) return@forEach
        return AvailableUpdate(
            version = release.version.removePrefix("v"),
            notes = release.notes.trim(),
            apkUrl = asset.url,
            apkName = assetName,
            sizeBytes = asset.sizeBytes,
            source = source,
            pageUrl = release.pageUrl,
        )
    }
    return null
}

/**
 * 两个源各查到结果时，取**版本更高**的那个。
 *
 * 版本号相同时优先 GitHub：纯粹是偏好（它的 Release 说明通常更全），不是正确性问题 ——
 * 两个平台本来就该是同一份产物。
 */
internal fun bestOf(results: List<Pair<UpdateSource, AvailableUpdate?>>): AvailableUpdate? {
    var best: AvailableUpdate? = null
    results.forEach { (_, candidate) ->
        if (candidate == null) return@forEach
        val current = best
        if (current == null) {
            best = candidate
            return@forEach
        }
        val diff = compareVersionParts(candidate.version, current.version)
        if (diff > 0 || (diff == 0 && candidate.source == UpdateSource.GitHub)) best = candidate
    }
    return best
}

