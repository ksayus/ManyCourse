package com.tof.manycourse

import android.app.NotificationManager
import android.util.Log
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tof.manycourse.data.ClassSlot
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.UpcomingClass
import com.tof.manycourse.notify.ClassReminderNotifier
import com.tof.manycourse.notify.FocusParam
import com.tof.manycourse.notify.PresentationTier
import com.tof.manycourse.notify.VendorCapability
import com.tof.manycourse.widget.WidgetRefresh
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **真机插桩测试**：小米超级岛 / 焦点通知那条链路的现场取证。
 *
 * ## 这个文件回答的问题
 *
 * 单元测试能钉住"判定矩阵"和"JSON 拼得对不对"，但钉不住下面这三件事 ——
 * 它们只有在**真的设备**上才有答案：
 *
 *  1. 这台机器实际探测到的是什么？（机型 / 焦点通知协议版本 / 岛属性 / 库的判断）
 *  2. 通知到底**发出来了没有**？`miui.focus.param` 到底**附上去了没有**？
 *  3. 桌面小组件拿**这台机器上真实的课表**渲染出来的，是不是真的那一节？
 *
 * 第 2 条尤其重要：焦点通知参数写错了**不会报错**，只会静默退化成普通通知 ——
 * 单测只能证明"我们拼的字符串长这样"，只有把通知真的发给
 * `NotificationManager` 再读回来，才能证明它确实挂在了那条通知上。
 *
 * ## 为什么断言的是"推导是否自洽"而不是写死期望值
 *
 * 写死"本机必须是焦点通知档"会让这个测试换一台机器就红。
 * 所以除了"必须是小米系机型"（不然这个测试没意义）之外，
 * 其余断言都写成**档位与事实之间的蕴含关系**。
 * 本机的实际事实会打进 logcat（`ClassReminderDeviceTest` 这个 tag），作为取证记录。
 *
 * ## 前置条件
 *
 * 通知要发得出来，必须已经有 `POST_NOTIFICATIONS`（Android 13+ 的运行时权限）。
 * 没授权时第 2 条会**明确地**失败并说明原因，而不是默默跳过 ——
 * 一个"永远在被跳过的测试"比没有测试更糟。
 */
@RunWith(AndroidJUnit4::class)
class ClassReminderDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)!!

    /** 造一节"十分钟后开始"的课，用来走完整的通知发送链路 */
    private fun slotStartingIn(minutes: Long): ClassSlot {
        val start = LocalTime.now().plusMinutes(minutes)
        return ClassSlot(
            date = LocalDate.now(),
            entry = CourseEntry.Local(
                Course(
                    id = 1L,
                    name = "高等数学",
                    teacher = "王老师",
                    room = "教学楼 A-101",
                    weekday = LocalDate.now().dayOfWeek.value,
                    startPeriod = 1,
                    periodCount = 2,
                )
            ),
            start = start,
            end = start.plusMinutes(80),
            startsIn = Duration.ofMinutes(minutes),
            running = false,
        )
    }

    // ══════════════════ 1. 现场能力探测 ══════════════════

    /**
     * 把"这台机器为什么是这一档"的原始事实打出来，并校验档位推导自洽。
     *
     * 超级岛能不能上，取决于**系统版本 + 系统属性 + 应用焦点通知权限**三项同时成立；
     * 少任何一项都必须退到下一档，而不是硬塞参数。
     */
    @Test
    fun 设备能力探测_档位推导与现场事实自洽() {
        val facts = VendorCapability.probe(context)
        val islandUsable = ClassReminderNotifier.islandUsableNow(context)
        val tier = ClassReminderNotifier.currentTier(context)

        Log.i(
            TAG,
            "现场事实：manufacturer=${facts.manufacturer} brand=${facts.brand} " +
                "focusProtocol=${facts.focusProtocol} islandFlag=${facts.islandFlag} " +
                "islandUsable=$islandUsable → tier=$tier"
        )

        if (!VendorCapability.isXiaomiFamily(facts)) {
            // ★ 非小米机型（Google 模拟器就是这一类）：必须落到**标准档** ——
            //   一个厂商私有参数都不许加。这条以前只有 JVM 单测覆盖，现在是真设备在验。
            assertEquals(
                "非小米机型（${facts.manufacturer}/${facts.brand}）必须走标准档",
                PresentationTier.Standard,
                tier,
            )
        } else {
            // 上岛必须三项同时成立（小米 + 系统声明支持岛 + 库判定可用）；少一项都不许上岛
            if (!facts.islandFlag || !islandUsable) {
                assertNotEquals("系统属性或应用权限不满足，绝不能上岛", PresentationTier.Island, tier)
            }
            // 焦点通知档必须真的有协议版本撑着
            if (tier == PresentationTier.Focus) {
                assertTrue("焦点通知档要求协议版本 ≥ 2，实际 ${facts.focusProtocol}", facts.focusProtocol >= VendorCapability.MIN_FOCUS_PROTOCOL)
            }
            // 小米机型上落到标准档，只可能是因为"没有协议版本"
            if (tier == PresentationTier.Standard) {
                assertTrue("支持焦点通知的小米机型不该落到标准档", facts.focusProtocol < VendorCapability.MIN_FOCUS_PROTOCOL)
            }
        }

        // 社区库自己的判断也记一笔：它 false 就是"超级岛这条路在这台机器上走不通"的直接证据
        Log.i(TAG, "社区库 HyperIslandNotification.isSupported = $islandUsable")
    }

    // ══════════════════ 2. 通知真的发得出来 + 焦点通知参数真的挂上了 ══════════════════

    @Test
    fun 课前提醒通知_能发出且挂上焦点通知参数() {
        assertTrue(
            "通知权限未授予（POST_NOTIFICATIONS）—— 请先 `adb shell pm grant com.tof.manycourse " +
                "android.permission.POST_NOTIFICATIONS`，否则这个测试证明不了任何事",
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )

        // 清场，避免读到别的用例/上一次运行留下的通知
        notificationManager.cancelAll()

        ClassReminderNotifier.notify(context, slotStartingIn(minutes = 10))

        val posted = notificationManager.activeNotifications
            .firstOrNull { it.notification.channelId == ClassReminderNotifier.CHANNEL_ID }
        assertNotNull("课前提醒通知没有出现在 NotificationManager 里", posted)

        val notification = posted!!.notification
        val title = notification.extras.getString("android.title").orEmpty()
        val text = notification.extras.getString("android.text").orEmpty()
        Log.i(TAG, "通知已发出：title=$title text=$text")

        // 标题应当是"还有 N 分钟上课"，正文里必须带上这节课的关键信息
        assertTrue("标题应包含倒计时，实际：$title", title.contains("分钟") || title.contains("上课"))
        assertTrue("正文应包含课程名，实际：$text", text.contains("高等数学"))
        assertTrue("正文应包含地点，实际：$text", text.contains("A-101"))

        // ★ 这一段是本测试的核心。两种设备的**期望正好相反**，所以必须按档位分开断言：
        //   · 小米机型（焦点通知 / 超级岛档）：miui.focus.param 必须真的挂在 extras 上，JSON 合法
        //   · 非小米机型（标准档）：**必须一个都没有** —— 那才是"不掺厂商私货"
        val focusParam = notification.extras.getString(FocusParam.EXTRA_KEY)
        Log.i(TAG, "${FocusParam.EXTRA_KEY} = $focusParam")
        when (ClassReminderNotifier.currentTier(context)) {
            PresentationTier.Standard -> assertNull(
                "非小米机型上绝不能带 ${FocusParam.EXTRA_KEY}（不掺厂商私货），实际=$focusParam",
                focusParam,
            )

            else -> {
                assertNotNull(
                    "通知上没有 ${FocusParam.EXTRA_KEY} —— 本机是小米展示档却没把参数挂上去" +
                        "（焦点通知会静默失效）",
                    focusParam,
                )
                assertTrue("协议版本字段缺失", focusParam!!.contains("\"protocol\":1"))
                assertTrue("business 字段缺失或写错", focusParam.contains("\"business\":\"${FocusParam.BUSINESS}\""))
                assertTrue("焦点通知内容里应带课程名", focusParam.contains("高等数学"))
                // 括号必须配平 —— 拼坏了的 JSON 一样不会报错，只会静默失效
                assertEquals(
                    "JSON 大括号不配平：$focusParam",
                    focusParam.count { it == '{' },
                    focusParam.count { it == '}' },
                )
            }
        }

        // 渠道重要性决定它会不会弹浮动通知（课前提醒要的就是弹出来）
        val channel = notificationManager.getNotificationChannel(ClassReminderNotifier.CHANNEL_ID)
        assertNotNull("通知渠道没建起来", channel)
        assertEquals(
            "课前提醒渠道必须是 HIGH，否则不会弹浮动通知",
            NotificationManager.IMPORTANCE_HIGH,
            channel!!.importance,
        )

        // 收尾：把测试通知撤掉，别留在用户的通知栏里
        notificationManager.cancelAll()
    }

    // ══════════════════ 3. 小组件用真实课表渲染 ══════════════════

    /**
     * 先把 `RemoteViews` 真的 inflate 出来，再读它上面那几个 `TextView` 的文字。
     *
     * 这样验的是**完整渲染路径**（含 `res/layout/widget_next_class.xml` 的 id 对不对），
     * 而不只是"调用没抛异常"。id 写错时 `setTextViewText` 会抛异常，
     * 但文字逻辑写错只有读出来才看得见。
     *
     * ## ★ 这里钉的是一条真机测试里踩出来的坑
     *
     * `CourseRepository.init` 里预置了 7 门演示课（高等数学 / 大学英语…）。
     * 没有登录态、也没有缓存课表时，`coursesOfDate` **照样会返回这些演示课** ——
     * 于是小组件会理直气壮地显示"高等数学"，而用户没有任何办法知道那是假的。
     *
     * 所以"有没有可信数据"必须**先于**"下一节课是什么"判断，
     * 下面无数据分支就是专门盯这件事的。
     */
    @Test
    fun 小组件_用真实课表渲染出下一节课() {
        val now = LocalDateTime.now()
        val hasData = UpcomingClass.hasScheduleData()
        // 与 WidgetRefresh.render 同一套口径：没有可信数据时根本不问"下一节课"
        val slot = if (hasData) UpcomingClass.forDisplay(now) else null

        val views = WidgetRefresh.render(context, now)
        val root = views.apply(context, FrameLayout(context))
        val name = root.findViewById<TextView>(R.id.widget_name).text.toString()
        val meta = root.findViewById<TextView>(R.id.widget_meta).text.toString()
        val room = root.findViewById<TextView>(R.id.widget_room).text.toString()
        val status = root.findViewById<TextView>(R.id.widget_status).text.toString()

        val noData = context.getString(R.string.widget_no_data)
        val noMore = context.getString(R.string.widget_no_more_today)

        Log.i(TAG, "小组件渲染：hasData=$hasData name=$name meta=$meta room=$room status=$status")
        Log.i(TAG, "解析结果：${slot?.let { "${it.name} / ${it.periodLabel}" }}")
        if (!hasData) {
            // 把"本来会被误显示的演示数据"打出来当证据
            Log.i(TAG, "无数据时 coursesOfDate 仍会返回：${UpcomingClass.forDisplay(now)?.name}（这是预置演示课，必须被忽略）")
        }

        assertTrue("小组件第一行必须有内容", name.isNotBlank())

        when {
            !hasData -> {
                // ★ 没有可信课表数据：只能是"没有课表数据"，绝不能是任何课程名
                assertEquals("没有课表数据时小组件必须显示「$noData」，绝不能被预置演示课顶替", noData, name)
                assertEquals("空态不该显示节次", "", meta)
                assertEquals("空态不该显示地点", "", room)
                // 要用户做的事写在状态行（名字行是 16sp 粗体，整句塞进去会折成两行）
                assertEquals(
                    "空态应提示用户去 App 里同步",
                    context.getString(R.string.widget_no_data_hint),
                    status,
                )
            }

            slot != null -> {
                // 有课可显示时，格子上写的必须与"下一节课"解析结果**完全一致**
                assertEquals("小组件的课程名与解析口径不一致", slot.name, name)
                assertTrue("节次行应包含「${slot.periodLabel}」，实际：$meta", meta.contains(slot.periodLabel))
                assertEquals("地点应与解析口径一致", slot.room, room)
            }

            else -> {
                // 有数据但往后七天都没课
                assertEquals("有数据但无课时应显示「$noMore」", noMore, name)
            }
        }
    }

    private companion object {
        const val TAG = "ClassReminderDeviceTest"
    }
}
