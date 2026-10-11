package com.tof.manycourse

import com.tof.manycourse.notify.NotificationPermission
import com.tof.manycourse.notify.RecoveryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知权限的**判定矩阵**。
 *
 * 这两条判定决定了"什么时候弹窗""被拒之后按钮点了有没有反应"，
 * 而它们在真机上**很难复现全**（要么把权限拒两次、要么刷机），
 * 所以拆成纯函数在这里把每种组合钉一遍 —— 与 `VendorCapabilityTest` 同一套路子。
 *
 * 用字面量 33 而不是 `Build.VERSION_CODES.TIRAMISU`：JVM 单测里的 `android.jar`
 * 是空壳，碰 `Build` 的静态成员会抛 "not mocked"（同一条理由写在
 * `NotificationPermission.RuntimePermissionApi` 的注释里）。
 */
class NotificationPermissionTest {

    private val tiramisu = 33

    // ══════════════════ 自动申请：只有"第一次"这一次 ══════════════════

    @Test
    fun 没问过_没给_新系统_才自动弹() {
        assertTrue(NotificationPermission.shouldAskAutomatically(tiramisu, granted = false, askedBefore = false))
    }

    @Test
    fun 已经给过权限就不再弹() {
        assertFalse(NotificationPermission.shouldAskAutomatically(tiramisu, granted = true, askedBefore = false))
    }

    @Test
    fun 问过一次之后_不管给没给都不再自动弹() {
        // 这是"不能每次回主界面都弹"的那条闸门：问过就是问过了
        assertFalse(NotificationPermission.shouldAskAutomatically(tiramisu, granted = false, askedBefore = true))
        assertFalse(NotificationPermission.shouldAskAutomatically(tiramisu, granted = true, askedBefore = true))
    }

    @Test
    fun 老系统上没有这条运行时权限_不弹() {
        // Android 12 及以下：POST_NOTIFICATIONS 不存在，弹了也没有意义
        assertFalse(NotificationPermission.shouldAskAutomatically(32, granted = false, askedBefore = false))
        assertFalse(NotificationPermission.shouldAskAutomatically(31, granted = false, askedBefore = false))
    }

    // ══════════════════ 设置页那条可恢复的路 ══════════════════

    @Test
    fun 已授予时不该再出现任何入口() {
        listOf(false, true).forEach { asked ->
            listOf(false, true).forEach { rationale ->
                assertEquals(
                    "已授予就不该再给「去授权」",
                    RecoveryAction.None,
                    NotificationPermission.recoveryAction(tiramisu, granted = true, askedBefore = asked, canShowRationale = rationale),
                )
            }
        }
    }

    @Test
    fun 还没问过时点去授权就是申请权限() {
        assertEquals(
            RecoveryAction.RequestPermission,
            NotificationPermission.recoveryAction(tiramisu, granted = false, askedBefore = false, canShowRationale = false),
        )
    }

    @Test
    fun 问过但系统还愿意弹_仍然走申请() {
        assertEquals(
            RecoveryAction.RequestPermission,
            NotificationPermission.recoveryAction(tiramisu, granted = false, askedBefore = true, canShowRationale = true),
        )
    }

    @Test
    fun 问过且系统不再弹_只能去系统设置() {
        // 这是"点了按钮什么都没发生"那种失败的防线：系统已经不会再显示弹窗，
        // 再 launch 一次只会静默返回 false
        assertEquals(
            RecoveryAction.OpenSystemSettings,
            NotificationPermission.recoveryAction(tiramisu, granted = false, askedBefore = true, canShowRationale = false),
        )
    }

    @Test
    fun 老系统上没有运行时权限可申请_直接去系统设置() {
        assertEquals(
            RecoveryAction.OpenSystemSettings,
            NotificationPermission.recoveryAction(32, granted = false, askedBefore = false, canShowRationale = true),
        )
    }
}
