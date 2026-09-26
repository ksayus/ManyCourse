package com.tof.manycourse

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tof.manycourse.data.DataRefresh
import com.tof.manycourse.data.SessionStore
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 手动刷新在**真机**上的接线验证（判断规则本身由纯逻辑测试 `DataRefreshTest` 钉住）。
 *
 * 为什么必须补这一层：这条链路的每一步失败起来都是**不崩、也不报错**的 ——
 * 下拉手势没接上、页头按钮没画出来、刷不了却什么都不说，
 * 普通单测一个都发现不了（它们只测 `DataRefresh` 的判断，不认识界面）。
 *
 * ## 两个写法上的约定
 *
 * 1. **存在性**一律用 `onAllNodes…`：页面是"四个 Fragment 常驻组合 + hide/show"
 *    （架构指南 §1），不能假定某个控件在整棵树里只有一处。
 *    只有"这一页独有"的文案才用 `onNodeWithText(...).assertIsDisplayed()`。
 * 2. "刷不了"的两条用例**先把状态摆成确定的**（把会话标成本地调试账号），
 *    而不是依赖"这台设备上当前有没有登录"—— 否则同一条测试在开发者手机上过、
 *    在别人的设备上挂。
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class RefreshUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ManyCourseMain>()

    /**
     * 课表页（默认页）顶部那行提示：**能刷时是「下拉刷新课表」，不能刷时是原因**。
     *
     * 两种文案按 [DataRefresh.blocker] 分叉断言：设备上有没有登录会话不归测试管
     * （`ManyCourseMain` 直接启动，会话是从 `SessionStore` 恢复出来的）。
     */
    @Test
    fun schedulePage_showsThePullHintOrTheReasonItCannotRefresh() {
        val blocker = DataRefresh.blocker
        composeRule.onNodeWithText(blocker ?: "下拉刷新课表").assertIsDisplayed()
        assertTrue("课表页页头应该有刷新按钮", refreshButtonsAreComposed())
    }

    /**
     * 「刷不了」时必须**说明原因**，而不是点了没反应。
     *
     * 按组里的**第一个**刷新按钮就行：两页的按钮做的是同一件事
     * （都是 `DataRefresh.start()`），点哪个结果都一样。
     */
    @Test
    fun refreshButton_saysWhyWhenThereIsNothingToRefresh() {
        withLocalDebugAccount {
            composeRule.waitForIdle()
            val reason = DataRefresh.blocker
            assertTrue("本地调试账号必须给出原因（没有可刷新的数据）", !reason.isNullOrBlank())

            composeRule.onAllNodesWithContentDescription("刷新数据").onFirst().performClick()
            composeRule.waitUntil(timeoutMillis = 3_000) { hasText(reason.orEmpty()) }
        }
    }

    /**
     * 下拉手势**真的接到了**刷新入口上。
     *
     * 在"本地调试账号"这个确定的状态下，下拉的**唯一**正确表现就是给出一句话 ——
     * 而那句话只可能来自 `DataRefresh.start()`：手势要是没接上，下拉什么都不会发生。
     */
    @Test
    fun pullDown_onSchedulePage_reachesTheRefreshEntry() {
        withLocalDebugAccount {
            composeRule.waitForIdle()
            val reason = DataRefresh.blocker.orEmpty()
            val handle = composeRule.onNodeWithText(reason)
            handle.assertIsDisplayed()

            handle.performTouchInput { swipeDown(startY = top + 4f, endY = top + 600f) }

            composeRule.waitUntil(timeoutMillis = 3_000) { hasText(reason) }
        }
    }

    /**
     * 日历页：**周视图与月视图下页头都要有刷新入口**。
     *
     * 这条是页头按钮存在的全部理由：周视图整屏不滚动（内容按屏幕高度分给七天），
     * 下拉手势在那里没有触发点 —— 少了这个按钮，周视图就完全没有手动刷新的办法。
     */
    @Test
    fun calendarPage_keepsTheRefreshEntryInBothLayouts() {
        composeRule.onNodeWithContentDescription("日历").performClick()
        composeRule.waitForIdle()
        assertTrue("月视图下页头应该有刷新按钮", refreshButtonsAreComposed())

        composeRule.onNodeWithText("周").performClick()
        composeRule.waitForIdle()
        assertTrue("周视图整屏不滚动，页头刷新按钮是唯一入口", refreshButtonsAreComposed())

        // 还原成月视图，别把设备上的布局偏好留在周视图
        composeRule.onNodeWithText("月").performClick()
        composeRule.waitForIdle()
    }

    /**
     * 把会话摆成"本地调试账号"再跑 [block]。
     *
     * 只改内存里的标记，**不动磁盘上的会话**：跑完恢复原值，
     * 开发者自己在这台手机上的登录态不受影响。
     */
    private fun withLocalDebugAccount(block: () -> Unit) {
        val wasLocalDebug = SessionStore.isLocalDebug.value
        composeRule.runOnUiThread { SessionStore.isLocalDebug.value = true }
        try {
            block()
        } finally {
            composeRule.runOnUiThread { SessionStore.isLocalDebug.value = wasLocalDebug }
        }
    }

    private fun refreshButtonsAreComposed(): Boolean =
        composeRule.onAllNodesWithContentDescription("刷新数据")
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
