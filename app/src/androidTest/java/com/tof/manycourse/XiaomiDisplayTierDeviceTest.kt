package com.tof.manycourse

import android.app.NotificationManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tof.manycourse.notify.ClassReminderNotifier
import com.tof.manycourse.notify.FocusParam
import com.tof.manycourse.notify.PresentationTier
import com.tof.manycourse.notify.VendorCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **小米展示档位的现场取证**（HyperOS 3/4 的焦点通知与超级岛）。
 *
 * ## 为什么这件事只能用插桩测试，不能在 adb 里问
 *
 * 判定"能不能上岛"的最后一步是 `HyperIslandNotification.isSupported(context)`，
 * 它内部要走 `content://miui.statusbar.notification.public` 的 `canShowFocus`。
 * 那个 Provider **要求调用方就是应用自己** —— 从 adb shell（uid 2000）问会直接
 * `SecurityException: Package is not owned by uid 2000`。
 *
 * 所以"这台机器到底能不能上岛"**只有跑在应用进程里的代码才知道**。
 *
 * ## 另外它还回答一个 UI 测不出来的问题
 *
 * 焦点通知参数写错了**不会报错**，只会静默退化成普通通知。
 * 所以这里把三档**各强制发一条**，再把真实挂上去的 `miui.focus.param` 读回来 ——
 * 证明的是"参数确实挂在了那条通知上"，而不是"我们拼的字符串长这样"。
 *
 * ## 断言策略：岛那一档只记录、不断言
 *
 * 本项目是个人侧载，拿不到小米的平台准入（见 `VendorCapability` 的注释），
 * 所以"岛上显示不出来"**不是缺陷**。标准档与焦点档则必须成立 —— 那两档是我们承诺的。
 */
@RunWith(AndroidJUnit4::class)
class XiaomiDisplayTierDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)!!

    @Test
    fun 现场取证_焦点通知与超级岛() {
        val facts = VendorCapability.probe(context)
        val islandUsable = ClassReminderNotifier.islandUsableNow(context)
        val tier = ClassReminderNotifier.currentTier(context)

        Log.i(
            TAG,
            "FACTS|manufacturer=${facts.manufacturer}|brand=${facts.brand}" +
                "|focusProtocol=${facts.focusProtocol}|islandFlag=${facts.islandFlag}" +
                "|islandUsable=$islandUsable|tier=$tier",
        )
        Log.i(TAG, "SDK=${android.os.Build.VERSION.SDK_INT}|release=${android.os.Build.VERSION.RELEASE}")

        // 渠道要**先建出来**再读：渠道 id 换过一次（`class_reminder` → `class_reminder_v2`，
        // 理由见 ClassReminderNotifier 的类注释），所以只装过旧版本、还没打开过新版本 App 的
        // 机器上根本不存在 v2 这个渠道 —— 那样这里读到的就是 null，断言会红成"渠道没建起来"，
        // 而它其实只是没被建过。ensureChannel 是幂等的公开入口，自己建一次最稳。
        ClassReminderNotifier.ensureChannel(context)
        val channel = manager.getNotificationChannel(ClassReminderNotifier.CHANNEL_ID)
        assertNotNull("通知渠道没建起来", channel)
        Log.i(
            TAG,
            "CHANNEL|importance=${channel!!.importance}|sound=${channel.sound}" +
                "|vibration=${channel.vibrationPattern?.joinToString()}|vibrationEnabled=${channel.shouldVibrate()}" +
                "|lockscreen=${channel.lockscreenVisibility}",
        )
        assertEquals(
            "课前提醒渠道必须是 HIGH，否则不会弹浮动通知",
            NotificationManager.IMPORTANCE_HIGH,
            channel.importance,
        )

        // ── 三档各强制发一条，把真实挂上的 extras 读回来 ──
        PresentationTier.entries.forEach { forced ->
            manager.cancelAll()
            ClassReminderNotifier.notify(context, ClassReminderNotifier.sampleSlot(), forced)

            val posted = manager.activeNotifications
                .firstOrNull { it.notification.channelId == ClassReminderNotifier.CHANNEL_ID }
            val focusParam = posted?.notification?.extras?.getString(FocusParam.EXTRA_KEY)
            val privateKeys = posted?.notification?.extras?.keySet()
                .orEmpty()
                .filter { it.contains("miui", true) || it.contains("island", true) || it.contains("focus", true) }
                .sorted()

            Log.i(
                TAG,
                "FORCED|$forced|posted=${posted != null}|focusParamLen=${focusParam?.length ?: -1}" +
                    "|privateKeys=$privateKeys",
            )
            if (focusParam != null) {
                // 个人行程别整段打进日志，只留够定位格式问题的头部
                Log.i(TAG, "FORCED|$forced|focusParamHead=${focusParam.take(160)}")
            }

            when (forced) {
                // ★ 标准档：一个厂商私有参数都不许有
                PresentationTier.Standard -> assertNull(
                    "标准档不该带 ${FocusParam.EXTRA_KEY}（不掺厂商私货）",
                    focusParam,
                )
                // ★ 焦点档：必须真的挂上，否则焦点通知静默失效
                PresentationTier.Focus -> {
                    assertNotNull("焦点档没挂上 ${FocusParam.EXTRA_KEY}", focusParam)
                    assertNotNull("焦点档没发出通知", posted)
                }
                // 岛那一档只记录：拿不到小米准入，显示不出来不算缺陷
                PresentationTier.Island -> Unit
            }

            // 给浮动通知留一个展示窗口，便于同时截图取证
            Thread.sleep(1500)
        }

        manager.cancelAll()
    }

    private companion object {
        const val TAG = "XiaomiTierTest"
    }
}
