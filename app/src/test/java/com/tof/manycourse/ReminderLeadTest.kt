package com.tof.manycourse

import com.tof.manycourse.data.DefaultLeadMinutes
import com.tof.manycourse.data.LeadMinuteOptions
import com.tof.manycourse.data.coerceLeadMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课前提醒"提前量"档位的单测。
 *
 * 这是个很小的纯函数，但它守着一个**只在真机上才会暴露**的坑：
 * 这个值存在 SharedPreferences 里，用户换版本、或有人手改 prefs、或以后调整档位列表，
 * 都可能读出一个档位之外的值。读出 0 的后果不是崩，而是"课前 0 分钟提醒"——
 * 课都开始了我才通知你，等于没通知。所以读盘和落盘都必须过一遍 [coerceLeadMinutes]。
 */
class ReminderLeadTest {

    /** 档位必须有序、为正：界面上是按顺序排的，排乱了用户选不到想要的 */
    @Test
    fun `档位有序且都是正数`() {
        assertTrue(LeadMinuteOptions.isNotEmpty())
        assertEquals(LeadMinuteOptions.sorted(), LeadMinuteOptions)
        assertTrue(LeadMinuteOptions.all { it > 0 })
    }

    /** 默认值必须在档位里，否则设置页会出现"当前选中项不在列表里"的尴尬 */
    @Test
    fun `默认值是合法档位`() {
        assertTrue(DefaultLeadMinutes in LeadMinuteOptions)
    }

    @Test
    fun `合法档位原样返回`() {
        LeadMinuteOptions.forEach { assertEquals(it, coerceLeadMinutes(it)) }
    }

    /** 非档位值取**最近**的一档，而不是粗暴地一刀切到最小或最大 */
    @Test
    fun `非档位值取最近的档位`() {
        assertEquals(10, coerceLeadMinutes(12))
        assertEquals(15, coerceLeadMinutes(13))
        assertEquals(20, coerceLeadMinutes(22))
    }

    @Test
    fun `超出范围的取到两端`() {
        assertEquals(5, coerceLeadMinutes(1))
        assertEquals(5, coerceLeadMinutes(0))
        assertEquals(30, coerceLeadMinutes(999))
    }

    /** 极端值（手改 prefs 写进去的）不能让这个函数自己崩掉 */
    @Test
    fun `极端值不崩`() {
        assertEquals(5, coerceLeadMinutes(-100))
        assertEquals(5, coerceLeadMinutes(Int.MIN_VALUE))
        assertEquals(30, coerceLeadMinutes(Int.MAX_VALUE))
    }
}
