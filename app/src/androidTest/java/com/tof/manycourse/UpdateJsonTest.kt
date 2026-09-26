package com.tof.manycourse

import com.tof.manycourse.data.UpdateSource
import com.tof.manycourse.data.parseReleases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新用的 Release JSON 解析（**插桩测试**）。
 *
 * 为什么放 androidTest 而不是 JVM 单测：`org.json` 在普通 JVM 单测里是**未实现的 stub**
 * （一调用就抛 "not mocked"），这一点 `GzusScheduleJsonTest` 的注释里已经记过一笔。
 * 所以这里跟它一个待遇：真机/模拟器上跑。
 *
 * 覆盖的是"真实响应的形状"：GitHub 与 Gitee 的 Release 对象字段基本一致
 * （`tag_name` / `body` / `prerelease` / `draft` / `assets[].browser_download_url`），
 * 所以一个解析器吃两份数据 —— 但**字段缺失/为 null 时必须降级而不是抛**：
 * 某个 Release 少了 body、assets 是 null、size 缺失，都不该让"检查更新"整个失败。
 */
class UpdateJsonTest {

    private val githubJson = """
        [
          {
            "tag_name": "v1.2.0.3",
            "name": "ManyCourse 1.2.0.3",
            "body": "### 下载\n\n- 新功能：自动更新",
            "prerelease": false,
            "draft": false,
            "assets": [
              {
                "name": "ManyCourse-1.2.0.3.apk",
                "browser_download_url": "https://github.com/ksayus/--ManyCourse/releases/download/v1.2.0.3/ManyCourse-1.2.0.3.apk",
                "size": 12582912
              },
              {
                "name": "apk.sha256",
                "browser_download_url": "https://github.com/ksayus/--ManyCourse/releases/download/v1.2.0.3/apk.sha256",
                "size": 96
              }
            ]
          },
          {
            "tag_name": "v1.1.0.3",
            "prerelease": true,
            "draft": false,
            "assets": []
          }
        ]
    """.trimIndent()

    @Test
    fun parsesGithubShapedReleases() {
        val releases = parseReleases(githubJson, UpdateSource.GitHub)

        assertEquals(2, releases.size)
        val first = releases.first()
        assertEquals("v1.2.0.3", first.version)
        assertTrue(first.notes.contains("自动更新"))
        assertEquals(false, first.prerelease)
        assertEquals(2, first.assets.size)
        assertEquals("ManyCourse-1.2.0.3.apk", first.assets.first().name)
        assertEquals(12582912L, first.assets.first().sizeBytes)
        assertTrue(first.pageUrl.endsWith("/tag/v1.2.0.3"))
        assertTrue(releases[1].prerelease)
    }

    @Test
    fun missingOptionalFieldsDegradeInsteadOfFailing() {
        // 真实世界：某个 Release 忘了写说明、assets 为 null、附件缺 size
        val json = """
            [
              { "tag_name": "v1.3.0.3", "assets": null },
              { "tag_name": "v1.4.0.3", "assets": [ { "name": "a.apk", "browser_download_url": "https://x/a.apk" } ] },
              { "name": "只有一个 name 字段" },
              { "body": "没有版本号" }
            ]
        """.trimIndent()

        val releases = parseReleases(json, UpdateSource.Gitee)

        // 有 tag_name 或 name 的才留；两条都没有的（只有 body）丢掉
        assertEquals(3, releases.size)
        assertEquals(0, releases[0].assets.size)
        assertEquals(0L, releases[1].assets.first().sizeBytes)
        assertEquals("只有一个 name 字段", releases[2].version)
    }

    @Test
    fun assetWithoutDownloadUrlIsDropped() {
        // 没有下载地址的附件留着也没法下载 —— 早点丢掉，免得界面上出现一个点了没反应的按钮
        val json = """[ { "tag_name": "v1.0.0", "assets": [ { "name": "a.apk" } ] } ]"""

        assertEquals(0, parseReleases(json, UpdateSource.GitHub).first().assets.size)
    }

    @Test
    fun garbageReturnsEmptyInsteadOfCrashing() {
        // 被运营商劫持/限流时可能拿到一段 HTML：不能崩，只能"什么都没有"
        assertEquals(0, parseReleases("", UpdateSource.GitHub).size)
        assertEquals(0, parseReleases("<html>403</html>", UpdateSource.GitHub).size)
        assertEquals(0, parseReleases("""{"message":"API rate limit exceeded"}""", UpdateSource.GitHub).size)
        assertEquals(0, parseReleases("[]", UpdateSource.GitHub).size)
    }

    @Test
    fun giteePageUrlIsBuiltFromConfiguredRepo() {
        val releases = parseReleases(githubJson, UpdateSource.Gitee)

        // 没配 GITEE_REPO 时页面地址为空串（不是 "null/releases"），界面据此不显示"去浏览器看"
        val pageUrl = releases.first().pageUrl
        assertTrue(pageUrl.isEmpty() || pageUrl.startsWith("https://gitee.com/"))
        assertNull(releases.firstOrNull { it.version.isBlank() })
    }
}
