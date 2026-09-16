package com.tof.manycourse

import com.tof.manycourse.data.profileStorageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「昵称/专业按账号分开存」的键映射测试。
 *
 * 这个键错了不会崩，只会让**别人的名字出现在自己账号上**（或者自己改的名字下次登录没了）——
 * 都属于"看着正常、其实数据串了"的问题，所以规则钉在这里。
 */
class ProfileStorageKeyTest {

    @Test
    fun sameAccountInDifferentSchoolsAreDifferentPeople() {
        // 同一个学号在两所学校不是同一个人：混在一起会让另一所学校看到上一所学校的名字
        assertNotEquals(
            profileStorageKey("gzus", "20221101"),
            profileStorageKey("nhjcxy", "20221101"),
        )
    }

    @Test
    fun sameAccountAndSchoolIsStable() {
        // 稳定性是"改完下次登录还在"的前提
        assertEquals(
            profileStorageKey("gzus", "20221101"),
            profileStorageKey("gzus", "20221101"),
        )
    }

    @Test
    fun differentAccountsInTheSameSchoolDoNotShareStorage() {
        assertNotEquals(
            profileStorageKey("gzus", "20221101"),
            profileStorageKey("gzus", "20221102"),
        )
    }

    @Test
    fun blankAccountHasNoStorageAtAll() {
        // 没有身份就不该读写任何人的资料：未登录时的一次误写会污染下一个登录的账号
        assertNull(profileStorageKey("gzus", ""))
        assertNull(profileStorageKey("gzus", "   "))
        assertNull(profileStorageKey(null, ""))
    }

    @Test
    fun localDebugAccountWithoutSchoolStillGetsItsOwnSlot() {
        // 本地调试账号（admin/2481）没有学校 id，但也要有自己的位置
        assertEquals("admin", profileStorageKey(null, "admin"))
        assertEquals("admin", profileStorageKey("", "admin"))
    }

    @Test
    fun whitespaceAroundAccountAndSchoolIsIgnored() {
        // 登录页的账号是 trim 过的，存储键也必须 trim —— 否则 "20221101 " 会另开一份存档
        assertEquals(
            profileStorageKey("gzus", "20221101"),
            profileStorageKey(" gzus ", "20221101 "),
        )
    }
}
