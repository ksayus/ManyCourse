package com.tof.manycourse.data

/**
 * **自动更新的两个源**：GitHub 与 Gitee。
 *
 * ## 为什么要有两个源
 *
 * 只有一个源的话，那个源"在你这儿打不开"就等于没有自动更新 —— 对国内网络来说这是常态。
 * 所以两个源是**互补**而不是冗余：能连 GitHub 就用 GitHub，连不上就退到 Gitee。
 *
 * ## 两个源必须各自发布一次（这一点没有捷径）
 *
 * GitHub Release 和 Gitee Release 是**两套独立的东西**：往 GitHub 推 tag 不会自动出现在 Gitee。
 * 本项目让 CI 一趟做完（见 `.github/workflows/android-ci.yml` 的 release job）：
 *
 * ```
 *   git push origin v1.2.0.3
 *        ↓
 *   CI 签名出 ManyCourse-1.2.0.3.apk
 *        ├─ 建 GitHub Release（softprops/action-gh-release）
 *        └─ 建 Gitee Release + 传附件（gitee API：POST /releases → POST /releases/{id}/attach_files）
 * ```
 *
 * 前提是在 GitHub 仓库里配两样东西（**只配一次**）：
 *  - **Variables** → `GITEE_REPO` = `owner/repo`（Gitee 上的仓库路径）
 *  - **Secrets** → `GITEE_TOKEN` = Gitee 私人令牌（勾 `projects` 权限，见
 *    <https://gitee.com/profile/personal_access_tokens>）
 *
 * 两个都没配时 CI 会**跳过** Gitee 那一步（不会让整个发布失败），App 侧也只用 GitHub。
 */
object UpdateConfig {

    /**
     * GitHub 仓库（**规范名**，不要带 `.git`）。
     *
     * ⚠️ 这里曾经写的是 `ksayus/--ManyCourse` —— 那是更早的名字，仓库后来改名成
     * `ksayus/ManyCourse` 了。**API 会重定向老名字**（还能用），但多一跳、也随时可能失效，
     * 所以一律用规范名。要是哪天又改了仓库名，改这一行（或干脆用 `git remote -v` 核对）。
     */
    const val GITHUB_REPO = "ksayus/ManyCourse"

    /**
     * Gitee 仓库路径（`owner/repo`），**大小写照 Gitee 上显示的写**。
     *
     * 必须先与 CI 里的 `GITEE_REPO` 变量（GitHub 仓库 → Settings → Variables）**完全一致**：
     * 一边写 app、一边写仓库，两处不一致的表现就是"Gitee 源永远查不到新版本"，
     * 而且不会有任何报错（API 会返回空列表）。留空 = 不使用 Gitee 源。
     */
    const val GITEE_REPO = "Ksayus/ManyCourse"

    /** 一次拉多少个 Release 来挑（两个平台都支持 `per_page`；20 个足够覆盖"最新几个版本"）*/
    const val RELEASES_PER_PAGE = 20

    /** GitHub API 的 User-Agent：**不填会被 403**（GitHub 的硬性要求）*/
    const val USER_AGENT = "ManyCourse-Android-Updater"

    val githubReleasesApi: String
        get() = "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=$RELEASES_PER_PAGE"

    /** Gitee 的 Releases 列表接口；没配仓库（[GITEE_REPO] 为空）时返回 null */
    val giteeReleasesApi: String?
        get() = GITEE_REPO.takeIf { it.isNotBlank() }
            ?.let { "https://gitee.com/api/v5/repos/$it/releases?per_page=$RELEASES_PER_PAGE" }

    val githubReleasesPage: String get() = "https://github.com/$GITHUB_REPO/releases"

    val giteeReleasesPage: String?
        get() = GITEE_REPO.takeIf { it.isNotBlank() }?.let { "https://gitee.com/$it/releases" }

    /** Release 里附带的校验文件（CI 会一起上传；用于展示，不做强制校验，理由见下方注释）*/
    const val CHECKSUM_ASSET_NAME = "apk.sha256"
}

/** 从哪个源拿更新（用户在设置里选；默认两个都试）*/
enum class UpdateSourcePreference(val label: String, val summary: String) {
    /** 两个源都查，取版本更新的那个；一个不通就用另一个 */
    Auto("自动（推荐）", "两个源都查，谁新用谁；一个连不上就用另一个"),

    /** 只查 GitHub */
    GitHub("仅 GitHub", "只查 GitHub Release"),

    /** 只查 Gitee */
    Gitee("仅 Gitee", "只查 Gitee Release（国内网络更稳）"),
    ;

    companion object {
        fun fromKey(key: String?): UpdateSourcePreference =
            entries.firstOrNull { it.name == key } ?: Auto
    }
}

/** 更新来自哪个平台（下载链接、文案都要跟着它走）*/
enum class UpdateSource(val label: String) {
    GitHub("GitHub"),
    Gitee("Gitee"),
}
