package com.tof.manycourse

import com.tof.manycourse.notify.PresentationTier
import com.tof.manycourse.notify.VendorCapability
import com.tof.manycourse.notify.VendorFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这台设备能上哪一档」的判定矩阵单测。
 *
 * 这个矩阵之所以值得单独钉住：它决定了**非小米机型上会不会被塞小米私有参数**。
 * 塞错了不会崩，只会在别的品牌手机上出现一条渲染异常的通知 ——
 * 而这条路径在开发者自己的手机（小米）上永远测不出来。
 */
class VendorCapabilityTest {

    private fun facts(
        manufacturer: String = "Xiaomi",
        brand: String = "Redmi",
        focusProtocol: Int = 2,
        islandFlag: Boolean = false,
    ) = VendorFacts(manufacturer, brand, focusProtocol, islandFlag)

    // ══════════════════ 机型识别 ══════════════════

    /**
     * ★ 两个字段都要看：本项目唯一的测试机上报的是
     * `MANUFACTURER = Xiaomi` 而 `BRAND = Redmi` —— 只看一个字段的实现会在某些机型上漏判。
     */
    @Test
    fun `小米家族三个品牌都认`() {
        assertTrue(VendorCapability.isXiaomiFamily(facts(manufacturer = "Xiaomi", brand = "Xiaomi")))
        assertTrue(VendorCapability.isXiaomiFamily(facts(manufacturer = "Xiaomi", brand = "Redmi")))
        assertTrue(VendorCapability.isXiaomiFamily(facts(manufacturer = "Xiaomi", brand = "POCO")))
        // 大小写不该影响判断
        assertTrue(VendorCapability.isXiaomiFamily(facts(manufacturer = "xiaomi", brand = "redmi")))
    }

    @Test
    fun `别的品牌不是小米家族`() {
        assertFalse(VendorCapability.isXiaomiFamily(facts(manufacturer = "samsung", brand = "samsung")))
        assertFalse(VendorCapability.isXiaomiFamily(facts(manufacturer = "HUAWEI", brand = "HONOR")))
        assertFalse(VendorCapability.isXiaomiFamily(facts(manufacturer = "", brand = "")))
    }

    // ══════════════════ 档位矩阵 ══════════════════

    /**
     * ★ **非小米机型绝不上岛**：哪怕有人把 `persist.sys.feature.island` 造假成 true，
     * 只要不是小米家族，一个私货参数都不许加。
     */
    @Test
    fun `非小米机型_即使属性为真也只发标准通知`() {
        assertEquals(
            PresentationTier.Standard,
            VendorCapability.tierOf(facts(manufacturer = "samsung", brand = "samsung", focusProtocol = 0, islandFlag = true), islandUsable = true),
        )
    }

    /** 三档齐全时给最高的那一档 */
    @Test
    fun `小米_支持岛且库可用_上岛`() {
        assertEquals(
            PresentationTier.Island,
            VendorCapability.tierOf(facts(islandFlag = true, focusProtocol = 3), islandUsable = true),
        )
    }

    /**
     * 系统声明支持岛，但**应用没拿到焦点通知权限**（社区库的 `isSupported()` 会返回 false）
     * → 退焦点通知，而不是什么都不加。
     */
    @Test
    fun `小米_支持岛但库不可用_退焦点通知`() {
        assertEquals(
            PresentationTier.Focus,
            VendorCapability.tierOf(facts(islandFlag = true, focusProtocol = 3), islandUsable = false),
        )
    }

    /** 本机（Redmi / OS2 / 无岛属性）落到的正是这一档 —— 焦点通知 */
    @Test
    fun `小米_OS2_没有岛属性_焦点通知`() {
        assertEquals(
            PresentationTier.Focus,
            VendorCapability.tierOf(facts(manufacturer = "Xiaomi", brand = "Redmi", focusProtocol = 2, islandFlag = false), islandUsable = false),
        )
    }

    /** OS1（协议 1）：官方明确不建议接入，对我们等于"不支持" */
    @Test
    fun `协议版本 1 不算支持`() {
        assertEquals(
            PresentationTier.Standard,
            VendorCapability.tierOf(facts(focusProtocol = 1, islandFlag = false), islandUsable = false),
        )
    }

    /** 非小米 ROM 上这个设置项根本不存在，读到的是 0 */
    @Test
    fun `协议版本 0 表示不支持`() {
        assertEquals(
            PresentationTier.Standard,
            VendorCapability.tierOf(facts(focusProtocol = 0, islandFlag = false), islandUsable = false),
        )
    }

    /** 协议版本比支持的还新（以后 OS4）时不能掉档 —— 用 `>=` 而不是 `==` 的理由 */
    @Test
    fun `更高的协议版本仍然算支持`() {
        assertEquals(
            PresentationTier.Focus,
            VendorCapability.tierOf(facts(focusProtocol = 4, islandFlag = false), islandUsable = false),
        )
    }

    /** 有岛属性但没有协议版本（理论上不该出现）→ 只要库说能用就上岛 */
    @Test
    fun `有岛属性但协议缺失_库可用时仍上岛`() {
        assertEquals(
            PresentationTier.Island,
            VendorCapability.tierOf(facts(focusProtocol = 0, islandFlag = true), islandUsable = true),
        )
    }
}
