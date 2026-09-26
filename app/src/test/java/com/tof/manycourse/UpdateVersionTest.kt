package com.tof.manycourse

import com.tof.manycourse.data.AssetRef
import com.tof.manycourse.data.AvailableUpdate
import com.tof.manycourse.data.ReleaseCandidate
import com.tof.manycourse.data.UpdateConfig
import com.tof.manycourse.data.UpdateSource
import com.tof.manycourse.data.bestOf
import com.tof.manycourse.data.compareVersionParts
import com.tof.manycourse.data.formatBytes
import com.tof.manycourse.data.isNewerVersion
import com.tof.manycourse.data.latestUpdate
import com.tof.manycourse.data.parseVersionParts
import com.tof.manycourse.data.pickApkAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动更新的**纯逻辑**回归测试（JVM，无需设备、不联网）。
 *
 * 这一层错了**不会崩**，只会有两种最难查的表现：
 *  - 永远提示"有新版本"（版本比较写错），用户被骚扰到关掉开关；
 *  - 永远不提示（比较写反 / 挑错了资产），用户以为这功能不存在。
 *
 * 另外"挑哪个 APK"错了的后果更重：**把 debug 包当正式包推给用户**。
 */
class UpdateVersionTest {

    // ── 版本号解析与比较 ──────────────────────────────────────────────────

    @Test
    fun parsesVersionNumbersOutOfTags() {
        assertEquals(listOf(1, 2, 0, 3), parseVersionParts("1.2.0.3"))
        assertEquals(listOf(1, 2, 0, 3), parseVersionParts("v1.2.0.3"))
        assertEquals(listOf(1, 2, 0, 3), parseVersionParts("ManyCourse 1.2.0.3"))
        assertEquals(listOf(1, 10, 0, 3), parseVersionParts("release-1.10.0.3"))
        assertEquals("不是版本号就返回空表", emptyList<Int>(), parseVersionParts("nightly"))
    }

    @Test
    fun comparesNumericallyNotAsText() {
        // ★ 这是"自己比而不是用字符串"的全部理由：字符串比较会把 1.10 判成比 1.9 小
        assertTrue(compareVersionParts("1.10.0.3", "1.9.0.3") > 0)
        assertTrue(compareVersionParts("1.9.0.3", "1.10.0.3") < 0)
        assertEquals(0, compareVersionParts("1.2.0.3", "v1.2.0.3"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertEquals("1.2 与 1.2.0 是同一个版本", 0, compareVersionParts("1.2", "1.2.0"))
        assertEquals(0, compareVersionParts("1.2.0.0", "1.2"))
    }

    @Test
    fun newerOrNotIsTheOnlyQuestionAutoUpdateAsks() {
        assertTrue(isNewerVersion("1.2.0.4", "1.2.0.3"))
        assertTrue(isNewerVersion("2.0.0.0", "1.9.9.9"))
        assertEquals(false, isNewerVersion("1.2.0.3", "1.2.0.3"))
        assertEquals(false, isNewerVersion("1.1.9.9", "1.2.0.3"))
    }

    @Test
    fun unparsableTagsFallBackToTextComparisonInsteadOfCrashing() {
        // 版本号认不出来时也要给个确定结果，不能让"检查更新"整个失败
        assertTrue(compareVersionParts("nightly", "1.2.0.3") != 0)
        assertEquals(0, compareVersionParts("nightly", "nightly"))
    }

    // ── 挑哪个 APK ────────────────────────────────────────────────────────

    @Test
    fun picksTheApkAndIgnoresChecksumFiles() {
        val names = listOf("apk.sha256", "ManyCourse-1.2.0.3.apk")

        assertEquals("ManyCourse-1.2.0.3.apk", pickApkAsset(names, "1.2.0.3"))
    }

    @Test
    fun neverPicksADebugOrUnsignedBuild() {
        val names = listOf(
            "ManyCourse-debug-57-abc1234.apk",
            "app-release-unsigned.apk",
            "ManyCourse-release-1.2.0.3.apk",
        )

        assertEquals("ManyCourse-release-1.2.0.3.apk", pickApkAsset(names, "1.2.0.3"))
    }

    @Test
    fun returnsNullWhenOnlyDebugBuildsExist() {
        // ★ 宁可说"这个版本没有可下载的安装包"，也不能把 debug 包推给用户
        assertNull(pickApkAsset(listOf("ManyCourse-debug-57-abc1234.apk"), "1.2.0.3"))
        assertNull(pickApkAsset(listOf("notes.txt", "apk.sha256"), "1.2.0.3"))
        assertNull(pickApkAsset(emptyList(), "1.2.0.3"))
    }

    @Test
    fun prefersTheAssetCarryingThisVersion() {
        val names = listOf("ManyCourse-release-1.1.0.3.apk", "ManyCourse-1.2.0.3.apk")

        assertEquals("ManyCourse-1.2.0.3.apk", pickApkAsset(names, "1.2.0.3"))
    }

    // ── 从一堆 Release 里挑最新 ───────────────────────────────────────────

    private fun release(
        version: String,
        apkNames: List<String> = listOf("ManyCourse-$version.apk"),
        prerelease: Boolean = false,
        draft: Boolean = false,
    ) = ReleaseCandidate(
        version = version,
        notes = "notes $version",
        pageUrl = "https://example.com/$version",
        prerelease = prerelease,
        draft = draft,
        assets = apkNames.map { AssetRef(it, "https://example.com/$it", 1024L) },
    )

    @Test
    fun picksTheHighestVersionThatHasAnApk() {
        val releases = listOf(release("1.2.0.3"), release("1.4.0.3"), release("1.3.0.3"))

        val update = latestUpdate(releases, currentVersion = "1.2.0.3", source = UpdateSource.GitHub)

        assertEquals("1.4.0.3", update?.version)
        assertEquals("ManyCourse-1.4.0.3.apk", update?.apkName)
        assertEquals(1024L, update?.sizeBytes)
    }

    @Test
    fun skipsDraftsAndPrereleases() {
        val releases = listOf(
            release("9.9.9.9", prerelease = true),
            release("8.8.8.8", draft = true),
            release("1.3.0.3"),
        )

        assertEquals("1.3.0.3", latestUpdate(releases, "1.2.0.3", UpdateSource.GitHub)?.version)
    }

    @Test
    fun fallsBackToAnOlderReleaseWhenTheNewestHasNoApk() {
        // 真实会遇到：先建了 Release 再传包，或者那一版忘了传 APK。
        // 这时应当退到"有包的最高版本"，而不是干脆不提示
        val releases = listOf(
            release("1.5.0.3", apkNames = listOf("apk.sha256")),
            release("1.4.0.3"),
        )

        assertEquals("1.4.0.3", latestUpdate(releases, "1.2.0.3", UpdateSource.GitHub)?.version)
    }

    @Test
    fun noUpdateWhenNothingIsNewer() {
        val releases = listOf(release("1.2.0.3"), release("1.1.0.3"))

        assertNull(latestUpdate(releases, "1.2.0.3", UpdateSource.GitHub))
        assertNull(latestUpdate(emptyList(), "1.2.0.3", UpdateSource.GitHub))
    }

    @Test
    fun versionPrefixIsStrippedForDisplay() {
        val releases = listOf(release("v1.3.0.3"))

        assertEquals("1.3.0.3", latestUpdate(releases, "1.2.0.3", UpdateSource.GitHub)?.version)
    }

    // ── 两个源的结果怎么合并 ──────────────────────────────────────────────

    private fun update(version: String, source: UpdateSource) = AvailableUpdate(
        version = version,
        notes = "",
        apkUrl = "https://example.com/$version.apk",
        apkName = "$version.apk",
        sizeBytes = 1L,
        source = source,
        pageUrl = "",
    )

    @Test
    fun takesTheHigherVersionAcrossSources() {
        val best = bestOf(
            listOf(
                UpdateSource.GitHub to update("1.3.0.3", UpdateSource.GitHub),
                UpdateSource.Gitee to update("1.4.0.3", UpdateSource.Gitee),
            )
        )

        assertEquals("1.4.0.3", best?.version)
        assertEquals(UpdateSource.Gitee, best?.source)
    }

    @Test
    fun oneSourceFailingStillYieldsTheOther() {
        val best = bestOf(
            listOf(
                UpdateSource.GitHub to null, // 连不上
                UpdateSource.Gitee to update("1.3.0.3", UpdateSource.Gitee),
            )
        )

        assertEquals("1.3.0.3", best?.version)
    }

    @Test
    fun sameVersionPrefersGithub() {
        val best = bestOf(
            listOf(
                UpdateSource.Gitee to update("1.3.0.3", UpdateSource.Gitee),
                UpdateSource.GitHub to update("1.3.0.3", UpdateSource.GitHub),
            )
        )

        assertEquals(UpdateSource.GitHub, best?.source)
    }

    @Test
    fun bothFailingYieldsNothing() {
        assertNull(bestOf(listOf(UpdateSource.GitHub to null, UpdateSource.Gitee to null)))
        assertNull(bestOf(emptyList()))
    }

    // ── 体积文案 ──────────────────────────────────────────────────────────

    @Test
    fun formatsBytesForHumans() {
        assertEquals("大小未知", formatBytes(0L))
        assertEquals("大小未知", formatBytes(-1L))
        assertEquals("2 KB", formatBytes(2048L))
        assertEquals("5.0 MB", formatBytes(5L * 1024L * 1024L))
    }

    // ── 两个源的地址拼接 ──────────────────────────────────────────────────

    @Test
    fun releaseApiUrlsPointAtTheRealRepos() {
        // 直接钉住仓库名：写错了**不会报错**，只会"永远查不到新版本"（API 对不存在的仓库返回 404，
        // 对空仓库返回 []），这种错最难发现 —— 所以让单测来盯
        assertEquals("ksayus/ManyCourse", UpdateConfig.GITHUB_REPO)
        assertTrue(UpdateConfig.githubReleasesApi.startsWith("https://api.github.com/repos/ksayus/ManyCourse/releases"))
        assertTrue(UpdateConfig.githubReleasesPage == "https://github.com/ksayus/ManyCourse/releases")

        // Gitee 现在配了仓库，地址必须是 gitee 的 v5 API（不是 github 那套路径）
        assertEquals("Ksayus/ManyCourse", UpdateConfig.GITEE_REPO)
        val giteeUrl = requireNotNull(UpdateConfig.giteeReleasesApi)
        assertTrue(giteeUrl.startsWith("https://gitee.com/api/v5/repos/Ksayus/ManyCourse/releases"))
        assertEquals("https://gitee.com/Ksayus/ManyCourse/releases", UpdateConfig.giteeReleasesPage)
    }
}
