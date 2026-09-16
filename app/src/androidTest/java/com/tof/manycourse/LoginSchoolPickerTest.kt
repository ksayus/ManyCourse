package com.tof.manycourse

import android.widget.AutoCompleteTextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tof.manycourse.data.LoginSettings
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.gr_api.SchoolRegistry
import com.tof.manycourse.ui.TextFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 登录页「选择学校」的真机回归测试。
 *
 * 起因是一个真实 bug：**长校名显示不全**。
 * 「南京航空航天大学金城学院」有 12 个汉字，16sp 下需要约 618px，
 * 而输入框文本区原本只有 574px（测试机 1080px 宽 / 440dpi / font_scale 1.17），
 * 于是被截成「南京航空航天大学金城学…」——多学校场景下用户根本没法确认
 * 自己选的是哪所。
 *
 * 最后是**把卡片往两侧撑宽**解决的（margin 24→16dp、内边距 16→12dp）：
 * 文本区从 574px 到约 640px，618px 直接放得下，**字号保持 16sp**，
 * 真机上学校框高度与用户名/密码框一致（都是 147px），图标位置也没动。
 * `ui/TextFit.kt` 的按需缩字号作为兜底（校名更长 / 屏幕更窄时才触发）。
 *
 * 这里用 `Layout.getEllipsisCount()` 直接断言「没有省略号」，
 * 比截图比对可靠：它读的是真正参与渲染的那份 Layout，而不是像素。
 * 同理，字号断言读的是 `TextView.getTextSize()`，是真正生效的值。
 */
@RunWith(AndroidJUnit4::class)
class LoginSchoolPickerTest {

    /**
     * 先把登录态清干净，再启动登录页。
     *
     * **必须有这一步**：登录会话现在是会跨冷启动保留的（`SessionStore` + 加密存档），
     * 如果这台测试机之前手动登录过并留下了会话，`MainActivity` 一 `onCreate`
     * 就会直接跳到主界面并 `finish()` —— 这几个断言登录页控件的用例会全部失败，
     * 而且失败原因（"找不到 R.id.EditSchool"）看起来跟学校下拉毫无关系。
     *
     * 副作用是这台设备上的登录态会被清掉，下次要重新登录一次。
     */
    @Before
    fun ensureLoggedOut() {
        SessionStore.logout()
    }

    /**
     * 长校名必须**在一行内**完整显示：行数 = 1 且没有省略号。
     * 走的是真实路径：写入偏好 → Activity 启动时从 [LoginSettings] 恢复到输入框。
     */
    @Test
    fun longSchoolName_fitsOnOneLineWithoutEllipsis() {
        val nhjcxy = requireNotNull(SchoolRegistry.find(NHJCXY_ID)) { "金城学院必须还在学校清单里" }
        val previous = LoginSettings.selectedSchoolId.value
        LoginSettings.setSelectedSchoolId(NHJCXY_ID)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // 等主线程与布局跑完，否则 field.layout 还是 null、字号自适应也还没生效
                Espresso.onIdle()
                scenario.onActivity { activity ->
                    val field = activity.findViewById<AutoCompleteTextView>(R.id.EditSchool)
                    assertEquals("恢复的学校不对", nhjcxy.name, field.text.toString())

                    val layout = field.layout
                    assertNotNull("输入框还没完成布局", layout)
                    assertEquals(
                        "校名「${nhjcxy.name}」必须单行显示，不能折行",
                        1,
                        layout!!.lineCount,
                    )
                    assertTrue(
                        "校名「${nhjcxy.name}」被省略号截断了，应缩小字号塞进一行" +
                            "（当前字号 ${field.textSize}px）",
                        layout.getEllipsisCount(0) == 0,
                    )
                }
            }
        } finally {
            LoginSettings.setSelectedSchoolId(previous)
        }
    }

    /** 短校名不需要缩小：字号应当保持基准 16sp，不能"顺手也缩一点" */
    @Test
    fun shortSchoolName_keepsBaseTextSize() {
        val gzus = requireNotNull(SchoolRegistry.find(GZUS_ID))
        val previous = LoginSettings.selectedSchoolId.value
        LoginSettings.setSelectedSchoolId(GZUS_ID)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Espresso.onIdle()
                scenario.onActivity { activity ->
                    val field = activity.findViewById<AutoCompleteTextView>(R.id.EditSchool)
                    assertEquals(gzus.name, field.text.toString())
                    assertEquals("短校名不该折行", 1, field.layout.lineCount)

                    val expectedPx = TextFit.spToPx(16f, activity.resources.displayMetrics)
                    assertEquals(
                        "短校名「${gzus.name}」应保持基准 16sp（≈ ${expectedPx}px）",
                        expectedPx,
                        field.textSize,
                        // 容差 2%：Material 给 EditText 应用的 textAppearance 与 spToPx(16)
                        // 实测差约 1%（51.0px vs 50.545px），不必在这一层较真
                        expectedPx * 0.02f,
                    )
                }
            }
        } finally {
            LoginSettings.setSelectedSchoolId(previous)
        }
    }

    /**
     * 点输入框要能展开下拉，且下拉里是**全部**学校。
     *
     * 后半句是防回归重点：AutoCompleteTextView 默认会拿当前文本去过滤适配器，
     * 选中「广州软件学院」后再点开就只剩它一条（SchoolDropdownAdapter 关掉了这个行为）。
     */
    @Test
    fun tappingTheField_opensDropdownListingEverySchool() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.EditSchool)).perform(click())
            scenario.onActivity { activity ->
                val field = activity.findViewById<AutoCompleteTextView>(R.id.EditSchool)
                assertTrue("点学校输入框应当展开下拉", field.isPopupShowing)
                assertEquals(
                    "下拉里应当列出全部 ${SchoolRegistry.schools.size} 所学校（不能被当前文本过滤掉）",
                    SchoolRegistry.schools.size,
                    field.adapter.count,
                )
            }
            // 关掉下拉，别留给下一个测试
            Espresso.pressBack()
        }
    }

    private companion object {
        /** 12 个汉字，就是那个被截断的校名 */
        const val NHJCXY_ID = "nhjcxy"
        const val GZUS_ID = "gzus"
    }
}
