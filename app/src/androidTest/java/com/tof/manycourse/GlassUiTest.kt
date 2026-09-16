package com.tof.manycourse

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 主界面交互验证（真机 / 模拟器）：
 * 1. 玻璃风格切换 → 立即生效 + 持久化，重建 Activity 后保持；
 * 2. 添加课程浮层可打开、返回键可关闭（走退出动画路径）；
 * 3. 底部导航切换页面正常。
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION") // v1 规则已标记废弃（v2 改用 StandardTestDispatcher）；此处依赖其 activityRule 做重建验证
class GlassUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ManyCourseMain>()

    @Test
    fun glassModeSwitch_appliesImmediatelyAndPersists() {
        // 进入「我的」页
        composeRule.onNodeWithContentDescription("我的").performClick()
        composeRule.onNodeWithText("外观 · 玻璃风格").assertIsDisplayed()

        // 默认高斯模糊
        assertEquals(GlassMode.Gaussian, UiSettings.glassMode.value)
        composeRule.onNodeWithText("当前：高斯模糊 · 简洁磨砂 · 低饱和 · 背景更清晰").assertIsDisplayed()

        // 切到液态玻璃：立即生效（重组）+ 写入偏好
        composeRule.onNodeWithText("液态玻璃").performClick()
        composeRule.waitForIdle()
        assertEquals(GlassMode.Liquid, UiSettings.glassMode.value)

        val prefs = composeRule.activity.getSharedPreferences("manycourse_ui_prefs", 0)
        assertEquals("liquid", prefs.getString("glass_mode", null))

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
        composeRule.onNodeWithContentDescription("我的").performClick()
        composeRule.onNodeWithText("通知设置").assertIsDisplayed()

        // 默认开启
        assertEquals(true, UiSettings.notificationsEnabled.value)
        composeRule.onNodeWithText("课前 15 分钟推送提醒").assertIsDisplayed()

        // 关掉
        composeRule.onNode(isToggleable()).performClick()
        composeRule.waitForIdle()
        assertEquals(false, UiSettings.notificationsEnabled.value)
        assertEquals(false, composeRule.activity
            .getSharedPreferences("manycourse_ui_prefs", 0)
            .getBoolean("notifications_enabled", true))
        composeRule.onNodeWithText("已关闭，不再推送提醒").assertIsDisplayed()

        // 重建后仍保持关闭
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        assertEquals(false, UiSettings.notificationsEnabled.value)

        // 还原，避免影响后续手动使用
        UiSettings.setNotificationsEnabled(true)
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
        composeRule.onNodeWithText("外观 · 玻璃风格").assertIsDisplayed()
    }

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
