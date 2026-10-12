package com.tof.manycourse

import com.tof.manycourse.data.AppVersion
import com.tof.manycourse.data.AppVersionInfo
import com.tof.manycourse.data.FALLBACK_VERSION_NAME
import com.tof.manycourse.data.UNREADABLE_VERSION
import com.tof.manycourse.data.formatAppVersion
import com.tof.manycourse.data.isNewerVersion
import com.tof.manycourse.data.parseVersionParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「当前版本」的**取值口径**（`data/AppVersion.kt`）。
 *
 * ## 这个 bug 长什么样
 *
 * 设置页的「检查更新」卡片显示 **当前版本 读取失败**，而开发者页读**同一份**版本信息是好的。
 * 根因不是"读失败"，而是那张卡片**根本没去读**：它显示的是
 * `UpdateStore.lastResult` 里那个"上一次联网检查的结果"，而那个结果只有用户点过
 * "检查更新"之后才存在 —— 卡片一打开当然是 null，于是兜底文案被当成了版本号。
 * 也就是说：**"本机版本"被当成了"检查结果"的一部分**。
 *
 * ## 为什么这几条值得单测
 *
 * 版本读取本身要一台设备（[com.tof.manycourse.data.AppVersion.read] 走 `PackageManager`），
 * 但**格式与兜底**是纯函数，而且它们错了不会崩：
 *  - 兜底文案写错 → 用户看到"读取失败"（就是本 bug 的表象），
 *    或者更糟：拿一个**看起来像版本的编造值**去比较（"永远提示有新版本"）；
 *  - 比较口径用错兜底 → "检查更新"永远沉默（用户以为这功能不存在）。
 */
class AppVersionTest {

    // ── 读得到：格式是唯一的（两处显示必须逐字一致）─────────────────────

    @Test
    fun readsBackTheVersionItWasGiven() {
        assertEquals("1.5.1.3（57）", formatAppVersion(AppVersionInfo(name = "1.5.1.3", code = 57)))
    }

    @Test
    fun nameAndCodeBothAppearSoUsersCanTellBuildsApart() {
        // versionName 相同、versionCode 不同的两个包（CI 按 tag + run number 发版）必须区分得开
        val a = formatAppVersion(AppVersionInfo("1.5.1.3", 57))
        val b = formatAppVersion(AppVersionInfo("1.5.1.3", 58))
        assertTrue("versionCode 不同就该显示得不一样", a != b)
        assertTrue("两个都要带上版本名", a.startsWith("1.5.1.3") && b.startsWith("1.5.1.3"))
        assertTrue("两个都要带上版本号", a.contains("57") && b.contains("58"))
    }

    @Test
    fun versionNameIsKeptVerbatimForComparison() {
        // 展示用的括号是**展示**层加的：比较用的那一份必须是裸版本号，
        // 否则 "1.5.1.3（57）" 会被 parseVersionParts 当成 1.5.1.3.57 而虚高
        val info = AppVersionInfo("1.5.1.3", 57)
        assertEquals(listOf(1, 5, 1, 3), parseVersionParts(info.name))
        assertEquals(
            "1.5.1.3 对 1.5.1.3 不该判成有新版本",
            false,
            isNewerVersion("1.5.1.3", info.name),
        )
    }

    // ── 读不到：兜底文案是唯一的，而且**绝不能伪装成版本号** ──────────────

    @Test
    fun unreadableVersionFallsBackToTheSharedCopy() {
        assertEquals(UNREADABLE_VERSION, formatAppVersion(null))
        assertEquals("读取失败", UNREADABLE_VERSION)
        // 自定义文案仍然要能覆盖（设置页/开发者页想各说各话时用得上）
        assertEquals("读不到", formatAppVersion(null, "读不到"))
    }

    @Test
    fun unreadableCopyIsNotMistakableForAVersion() {
        // ★ 这条钉的是"别编一个版本出来"：兜底文案里不能出现看起来像版本号的数字串，
        //   否则用户/日志会把它当成真的版本（"读取失败"里没有数字，是对的）
        assertTrue(
            "兜底文案不该包含数字：$UNREADABLE_VERSION",
            UNREADABLE_VERSION.none { it.isDigit() },
        )
        assertTrue(
            "兜底文案不该被解析成一个版本号",
            parseVersionParts(UNREADABLE_VERSION).isEmpty(),
        )
    }

    @Test
    fun comparisonFallbackOnlyEverCausesAnExtraPrompt() {
        // ★ 读不到版本时给 "0"（而不是空串、"读取失败"）的全部理由：
        //   它必须被解析成一个**比任何真实版本都低**的值 ——
        //   于是最坏结果只是"多提示一次更新"，绝不会变成"永远不提示"
        assertFalse("0 不该比 1.5.1.3 更新", isNewerVersion(FALLBACK_VERSION_NAME, "1.5.1.3"))
        assertTrue("1.5.1.3 必须比兜底的 0 更新", isNewerVersion("1.5.1.3", FALLBACK_VERSION_NAME))
        assertEquals(listOf(0), parseVersionParts(FALLBACK_VERSION_NAME))
        // 而"读取失败"这种文案是**解析不出**版本的：拿它去比较会走到纯文本兜底那条路上，
        // 结果取决于字符串比较，不是我们想要的确定性 —— 所以它只能当展示文案
        assertTrue(parseVersionParts(UNREADABLE_VERSION).isEmpty())
    }

    // ── 三个调用方共用一份取值：读得到 / 读不到 两条路径 ────────────────────

    /**
     * ★ **读得到**这条路径：`PackageManager` 给出的两个字段被原样收下。
     *
     * 用 [AppVersion.fromPackageInfo] 而不是 `AppVersion.read`：后者要真实 `Context`
     * （只有设备上有），而"字段收得对不对"是纯逻辑，必须在 JVM 单测里覆盖。
     */
    @Test
    fun packageFieldsBecomeTheVersionInfo() {
        val info = AppVersion.fromPackageInfo(name = "1.5.1.3", code = 57L)
        assertEquals(AppVersionInfo(name = "1.5.1.3", code = 57L), info)
        assertEquals("1.5.1.3（57）", formatAppVersion(info))
        // 展示与比较读的是同一条路径：比较用的那一份仍然是没有括号的裸版本号
        assertEquals("1.5.1.3", info?.name)
    }

    /**
     * ★ **读不到**这条路径：空版本名 → null → 兜底文案。
     *
     * 三种真实见过的"读到了空"都要走这条路：字段是 null、空串、只有空白。
     * 它们绝不能被当成"版本号是空"继续往下走 —— 那样设置页会显示 `当前版本 （57）`。
     */
    @Test
    fun blankVersionNameMeansUnreadable() {
        listOf(null, "", "   ").forEach { blank ->
            val info = AppVersion.fromPackageInfo(name = blank, code = 57L)
            assertNull("版本名是「${blank ?: "null"}」时应该算读不到", info)
            assertEquals(UNREADABLE_VERSION, formatAppVersion(info))
        }
    }

    /** 版本号读不到（`longVersionCode` 拿不到）时不影响"读到了版本号"这件事，只补 0 */
    @Test
    fun missingVersionCodeStillKeepsTheVersionName() {
        val info = AppVersion.fromPackageInfo(name = "1.5.1.3", code = null)
        assertEquals(AppVersionInfo(name = "1.5.1.3", code = 0L), info)
        assertEquals("1.5.1.3（0）", formatAppVersion(info))
    }

    /** 版本名两边的空白要去掉（`PackageManager` 不会给，但兜底路径要有确定的输出）*/
    @Test
    fun versionNameIsTrimmed() {
        assertEquals("1.5.1.3", AppVersion.fromPackageInfo(name = " 1.5.1.3 ", code = 57L)?.name)
    }
}
