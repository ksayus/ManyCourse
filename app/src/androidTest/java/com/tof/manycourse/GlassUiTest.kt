package com.tof.manycourse

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.ui.theme.GlassMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 主界面交互验证（真机 / 模拟器）：
 * 1. 玻璃风格切换 → 立即生效 + 持久化，重建 Activity 后保持；
 * 2. 添加课程浮层可打开、返回键可关闭（走退出动画路径）；
 * 3. 底部导航切换页面正常；
 * 4. 通知开关可关 + 持久化；
 * 5. 独立的**设置页**：「我的」→「设置」能进、返回键能退出，页里的开关照常生效；
 * 6. 日历布局开关（月视图 / 周视图）→ 日历页当场换布局 + 持久化，页头「周/月」也能切。
 *
 * 开关一律按 `contentDescription` 定位：设置页里开关不止一个，
 * "第几个 Switch"不是稳定的定位方式。
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION") // v1 规则已标记废弃（v2 改用 StandardTestDispatcher）；此处依赖其 activityRule 做重建验证
class GlassUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ManyCourseMain>()

    private fun prefs() = composeRule.activity.getSharedPreferences("manycourse_ui_prefs", 0)

    /** 「我的」→「设置」：设置页是独立的一页，开关都搬进去了 */
    private fun openSettings() {
        composeRule.onNodeWithContentDescription("我的").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("设置").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { hasText("外观 · 玻璃风格") }
    }

    @Test
    fun glassModeSwitch_appliesImmediatelyAndPersists() {
        openSettings()

        // 默认高斯模糊
        assertEquals(GlassMode.Gaussian, UiSettings.glassMode.value)
        composeRule.onNodeWithText("当前：高斯模糊 · 简洁磨砂 · 低饱和 · 背景更清晰").assertIsDisplayed()

        // 切到液态玻璃：立即生效（重组）+ 写入偏好
        composeRule.onNodeWithText("液态玻璃").performClick()
        composeRule.waitForIdle()
        assertEquals(GlassMode.Liquid, UiSettings.glassMode.value)
        assertEquals("liquid", prefs().getString("glass_mode", null))

        // 重建 Activity 后仍保持（模拟退出重进）
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        assertEquals(GlassMode.Liquid, UiSettings.glassMode.value)

        // 还原默认，避免影响后续手动使用
        UiSettings.setGlassMode(GlassMode.Gaussian)
    }

    /** 通知设置开关：可以关掉，并且状态持久化 */
    @Test
    fun notificationToggle_canBeTurnedOffAndPersists() {
        openSettings()
        composeRule.onNodeWithText("通知设置").assertIsDisplayed()

        // 默认开启
        assertEquals(true, UiSettings.notificationsEnabled.value)
        composeRule.onNodeWithText("课前 15 分钟推送提醒").assertIsDisplayed()

        // 关掉
        composeRule.onNodeWithContentDescription("通知提醒开关").performClick()
        composeRule.waitForIdle()
        assertEquals(false, UiSettings.notificationsEnabled.value)
        assertEquals(false, prefs().getBoolean("notifications_enabled", true))
        composeRule.onNodeWithText("已关闭，不再推送提醒").assertIsDisplayed()

        // 重建后仍保持关闭
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        assertEquals(false, UiSettings.notificationsEnabled.value)

        // 还原，避免影响后续手动使用
        UiSettings.setNotificationsEnabled(true)
    }

    /**
     * 日历布局开关：设置页里翻到「周视图」后
     * ① 日历页确实换了布局（时间轴出现、"当日课程"列表消失）；
     * ② 状态写进偏好，重建 Activity 后保持；
     * ③ 页头的「周/月」分段切换与设置页是同一个状态，能当场切回来。
     */
    @Test
    fun calendarLayoutSwitch_switchesCalendarPageAndPersists() {
        openSettings()
        composeRule.onNodeWithText("日历布局").assertIsDisplayed()

        // 默认是月视图
        assertEquals(false, UiSettings.calendarGridLayout.value)
        composeRule.onNodeWithText("月视图 · 月历 + 当日列表").assertIsDisplayed()

        // 翻到周视图：状态 + 偏好 + 设置页文案都跟上
        composeRule.onNodeWithContentDescription("日历网格布局开关").performClick()
        composeRule.waitForIdle()
        assertEquals(true, UiSettings.calendarGridLayout.value)
        assertEquals(true, prefs().getBoolean("calendar_grid_layout", false))
        composeRule.onNodeWithText("周视图 · 时间轴 + 课程卡片").assertIsDisplayed()

        // 返回（设置页是独立一页，返回键 / 返回按钮都能退）→ 进日历页
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("日历").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { hasText("第1节") }
        composeRule.onNodeWithText("第1节").assertIsDisplayed()
        assertFalse("周视图下不该再有『当日课程』列表", hasText("当日课程"))

        // 页头「周/月」切回月视图：与设置页同一个状态
        composeRule.onNodeWithText("月").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { hasText("当日课程") }
        assertEquals(false, UiSettings.calendarGridLayout.value)

        // 重建后仍保持关（= 月视图）
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        assertEquals(false, UiSettings.calendarGridLayout.value)
    }

    @Test
    fun addCoursePanel_opensAndClosesWithBack() {
        composeRule.onNodeWithContentDescription("课表").performClick()
        composeRule.waitForIdle()

        // 打开浮层（此时"添加课程"仅有底部按钮一处）
        composeRule.onNodeWithText("添加课程").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { hasText("保存课程") }
        composeRule.onNodeWithText("保存课程").assertIsDisplayed()

        // 返回键关闭浮层（走退出动画，而不是退出应用）
        Espresso.pressBack()
        composeRule.waitUntil(timeoutMillis = 3_000) { !hasText("保存课程") }
    }

    @Test
    fun bottomNav_switchesScreens() {
        composeRule.onNodeWithContentDescription("日历").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("日程日历").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("我的").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
