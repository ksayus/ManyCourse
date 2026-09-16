package com.tof.manycourse

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tof.manycourse.data.CourseSync
import com.tof.manycourse.data.WeekScheduleStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「课表页」与「日历页」必须显示**同一天的同一批课**（真机 / 模拟器）。
 *
 * 为什么值得单开一条真机测试：两页的差别只在渲染方式（星期 Chip vs 月历格子），
 * 数据却有可能来自两个地方（教务系统的"按周课表" / 本地课表兜底）。
 * 一旦两边对不上，用户看到的就是"课表页有课、日历页没课" —— 不会崩、不会报错，
 * 只是让人不敢信这个 App。
 *
 * 取值规则本身由纯逻辑测试 `CourseEntriesTest` 钉住；这里补的是**接线**：
 * 页面真的调了那个函数、两页真的选的是同一天。
 *
 * 做法是最朴素的：两页都把"今天有几门课"写在界面上
 * （课表页 `周三 · 3 门课程`、日历页 `共 3 门课程`），读出来比一比。
 * 这样无论数据来自哪条链路（真登录 / 本地调试账号 / 离线），这条断言都成立。
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class ScheduleCalendarParityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ManyCourseMain>()

    @Test
    fun schedulePageAndCalendarPage_showTheSameCourseCountForToday() {
        // ① 等两条异步链路落定：课表同步、教学周列表（不然读到的数字会中途变）
        composeRule.waitUntil(timeoutMillis = 20_000) {
            CourseSync.state.value !is CourseSync.State.Loading &&
                WeekScheduleStore.state.value !is WeekScheduleStore.State.Loading
        }

        // ② 课表页（星期 Chip 默认选中今天）
        composeRule.onNodeWithContentDescription("课表").performClick()
        composeRule.waitForIdle()
        val fromSchedule = awaitCount(
            pattern = Regex("""^周[一二三四五六日] · (\d+) 门课程$"""),
            what = "课表页的『周X · N 门课程』",
        )

        // ③ 日历页（月历默认也选中今天）
        composeRule.onNodeWithContentDescription("日历").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("当日课程").assertIsDisplayed()
        val fromCalendar = awaitCount(
            pattern = Regex("""^共 (\d+) 门课程$"""),
            what = "日历页的『共 N 门课程』",
        )

        assertEquals(
            "课表页与日历页对『今天』的课程数必须一致" +
                "（课表页=$fromSchedule，日历页=$fromCalendar）",
            fromSchedule,
            fromCalendar,
        )
    }

    /**
     * 等界面上出现匹配 [pattern] 的那段文字，返回捕获到的数字。
     *
     * 为什么要 `waitUntil`：点进页面后教学周数据可能才补拉回来，数字会晚一拍稳定。
     */
    private fun awaitCount(pattern: Regex, what: String): Int {
        var found: Int? = null
        composeRule.waitUntil(timeoutMillis = 15_000) {
            found = matchedCounts(pattern).firstOrNull()
            found != null
        }
        return found ?: error("界面上没找到$what")
    }

    /** 当前界面上所有文本里，匹配 [pattern] 的节点数（可能同时存在多个）*/
    private fun matchedCounts(pattern: Regex): List<Int> =
        composeRule.onAllNodesWithText("门课程", substring = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                // Compose 的文本匹配器只做子串，正则得自己上，所以先把原文取出来
                node.config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }
            }
            .mapNotNull { text -> pattern.find(text)?.groupValues?.get(1)?.toIntOrNull() }
}
