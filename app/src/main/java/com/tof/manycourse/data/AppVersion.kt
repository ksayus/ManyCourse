package com.tof.manycourse.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * **本机应用版本**的唯一读取口径。
 *
 * ## 为什么必须收敛到这一处（这是「当前版本 读取失败」那个 bug 的根因）
 *
 * 同一个版本号在本项目里原先有**三份**各自独立的读法：
 *
 *  1. `UpdateChecker.currentVersion()` —— 走 `PackageManager`，**读得到**（1.5.1.3）；
 *  2. `DevToolsScreen.appVersion()` —— 也走 `PackageManager`，**读得到**；
 *  3. 设置页那张「检查更新」卡片 —— **不是读的**，它显示的是
 *     `UpdateStore.lastResult.value?.currentVersion ?: "读取失败"`，
 *     而那个结果**只有用户点过"检查更新"之后才存在**。卡片刚打开时它是 null，
 *     于是"当前版本 读取失败"并不是读失败，而是**根本还没去读**。
 *
 * 也就是说：那一行文案把"上一次联网检查的结果"当成了"本机版本"的载体 ——
 * 把两件不相干的事捆在一起，才是这个 bug 的真正来源。所以这里的修法不是
 * "在卡片里也调一次 PackageManager"，而是**让三处都从这一个函数取值**：
 * 版本号从此只有一个来源，不再有任何一处依赖"检查过更新没有"。
 *
 * ## 为什么走 `PackageManager` 而不是 `BuildConfig.VERSION_NAME`
 *
 * 后者要在模块里打开 `buildFeatures { buildConfig = true }`（见 `app/build.gradle.kts`），
 * 而这里只是想显示/比较一个字符串，不值得为它改构建配置。
 */
object AppVersion {

    /**
     * 读取本机版本；**任何一步失败都返回 null**，绝不抛。
     *
     * 读不到的可能是真实存在的（包被卸载中、`PackageManager` 状态异常），
     * 所以调用方必须能表达"不知道"——见 [formatAppVersion] 的兜底文案。
     */
    fun read(context: Context): AppVersionInfo? = runCatching {
        fromPackageInfo(versionNameOf(context), versionCodeOf(context))
    }.getOrNull()

    /**
     * 把 `PackageManager` 那两个原始字段收成 [AppVersionInfo]（**纯函数**，JVM 单测钉住）。
     *
     * 为什么要从 [read] 里拆出来：`read` 需要一个真实的 `Context`（只有设备上有），
     * 而"版本名是空串怎么办""版本号读不到怎么办"这些**分支**必须在单测里覆盖到 ——
     * 它们错了的表现正好就是"当前版本 读取失败"这一类文案问题。
     *
     * **空版本名 = 读不到**：宁可显示兜底文案，也不显示"（57）"这种缺了版本名的半截信息。
     */
    internal fun fromPackageInfo(name: String?, code: Long?): AppVersionInfo? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return AppVersionInfo(name = trimmed, code = code ?: 0L)
    }

    /** 显示用文案：`1.5.1.3（57）`；读不到时给 [unreadable] */
    fun display(context: Context, unreadable: String = UNREADABLE_VERSION): String =
        formatAppVersion(read(context), unreadable)

    /**
     * 参与版本比较用的版本名（`1.5.1.3`）；读不到时给 `"0"`。
     *
     * 兜底取 `"0"` 而不是空串或 `"读取失败"`：这个值会被 [isNewerVersion] 拿去比较，
     * 而 `"0"` **不可能被误判成"比任何发布版本都新"**（[parseVersionParts] 认得它），
     * 于是读不到版本时最坏的结果只是"提示有更新"，不会变成"永远不提示"。
     */
    fun nameOrFallback(context: Context): String = read(context)?.name ?: FALLBACK_VERSION_NAME

    /**
     * `PackageInfo.versionName`：**API 33 起换了重载**。
     *
     * 老的那个（`getPackageInfo(String, Int)`）在 33+ 上已废弃，但**只是警告、不会失败** ——
     * 这一条就是"两处读同一份版本信息、一处好一处坏"里最容易让人查偏的地方：
     * 废弃 API 不是这个 bug 的原因（版本号确实读出来了），根因在调用方拿错了载体。
     * 这里两种都留着，只是把废弃的那支收在 `@Suppress` 里。
     */
    private fun versionNameOf(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName
        } else {
            @Suppress("DEPRECATION") // 33 起换成 PackageInfoFlags 那个重载，老机器仍要走这个
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }

    /** `PackageInfo.longVersionCode` 同样按 SDK 分重载；读不到就交给 [fromPackageInfo] 兜成 0 */
    private fun versionCodeOf(context: Context): Long? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                .longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }
}

/**
 * 读到的版本信息。
 *
 * @param name `versionName`（`1.5.1.3`）
 * @param code `versionCode`（`57`）——只用于显示，不做比较
 */
data class AppVersionInfo(val name: String, val code: Long)

/** 读不到版本时的兜底文案（**唯一**的值，界面上不该再各写一份）*/
const val UNREADABLE_VERSION = "读取失败"

/**
 * 版本号的展示格式：`1.5.1.3（57）`。
 *
 * @param info [AppVersion.read] 的结果；null = 读不到
 * @param unreadable 读不到时的文案（默认 [UNREADABLE_VERSION]）
 */
fun formatAppVersion(info: AppVersionInfo?, unreadable: String = UNREADABLE_VERSION): String =
    if (info == null) unreadable else "${info.name}（${info.code}）"

/**
 * 版本比较里读不到版本时的兜底值（见 `AppVersion.nameOrFallback`）。
 *
 * 取 `"0"` 的理由写在那里：它只可能让"有新版本"多提示一次，
 * 不会让"检查更新"永远沉默。
 */
const val FALLBACK_VERSION_NAME = "0"
