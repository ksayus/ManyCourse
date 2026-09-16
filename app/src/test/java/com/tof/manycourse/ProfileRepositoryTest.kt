package com.tof.manycourse

import com.tof.manycourse.data.ProfileRepository
import com.tof.manycourse.data.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 个人资料**按账号持久化**的回归测试（需求：*用户名更改后要本地保存，对应的账号有对应的存储信息*）。
 *
 * 为什么值得单测：这些规则错了**不会崩**，只会让名字串号 ——
 * 换了账号还显示上一个人的昵称、或者用户改完名字下次冷启动又被学校同步覆盖回去。
 * 两种都是"用户会怀疑整个 App 都在乱来"的错误。
 *
 * 用内存版 [ProfileStorage] 替掉 SharedPreferences（见 `ProfileRepository.attachStorage`），
 * 于是整套规则能在普通 JVM 上跑，不需要 Robolectric / 真机。
 */
class ProfileRepositoryTest {

    /** 内存版存储：行为和 SharedPreferences 一样（读不到给默认值）*/
    private class MemoryStorage : ProfileStorage {
        private val values = mutableMapOf<String, Any>()

        override fun getString(key: String): String? = values[key] as? String

        override fun getBoolean(key: String, default: Boolean): Boolean =
            values[key] as? Boolean ?: default

        override fun put(key: String, value: String) {
            values[key] = value
        }

        override fun put(key: String, value: Boolean) {
            values[key] = value
        }

        /** 存了些什么（单测断言用）*/
        val snapshot: Map<String, Any> get() = values.toMap()
    }

    private lateinit var storage: MemoryStorage

    @Before
    fun setUp() {
        storage = MemoryStorage()
        // 每个用例一份干净的存储，避免用例之间互相影响
        ProfileRepository.attachStorage(storage)
    }

    @Test
    fun editedNicknameSurvivesReLogin() {
        ProfileRepository.bindAccount("gzus", "20221101")
        ProfileRepository.setNickname("小李")

        // 退出登录：界面清空，但存档不删
        ProfileRepository.onLogout()
        assertEquals("未登录", ProfileRepository.nickname.value)

        // 同一个账号再登录：改过的名字还在
        ProfileRepository.bindAccount("gzus", "20221101")
        assertEquals("小李", ProfileRepository.nickname.value)
        assertEquals(
            "昵称必须以这个账号自己的存档为单位落盘",
            "小李",
            storage.snapshot["nickname:gzus|20221101"],
        )
    }

    @Test
    fun eachAccountKeepsItsOwnProfile() {
        ProfileRepository.bindAccount("gzus", "20221101")
        ProfileRepository.setNickname("小李")
        ProfileRepository.setMajor("软件工程 · 2022级")

        ProfileRepository.bindAccount("gzus", "20221102")
        ProfileRepository.setNickname("小王")

        // 换回第一个账号：拿到的必须是他自己那一份，不能是小王的
        ProfileRepository.bindAccount("gzus", "20221101")
        assertEquals("小李", ProfileRepository.nickname.value)
        assertEquals("软件工程 · 2022级", ProfileRepository.major.value)

        ProfileRepository.bindAccount("gzus", "20221102")
        assertEquals("小王", ProfileRepository.nickname.value)
        assertEquals(
            "没填过专业的账号不该看到别人的专业",
            "计算机科学 · 2022级",
            ProfileRepository.major.value,
        )
    }

    @Test
    fun sameAccountInAnotherSchoolIsADifferentPerson() {
        ProfileRepository.bindAccount("gzus", "20221101")
        ProfileRepository.setNickname("小李")

        ProfileRepository.bindAccount("nhjcxy", "20221101")
        assertEquals("另一所学校不应该看到上一所学校的名字", "张同学", ProfileRepository.nickname.value)
    }

    @Test
    fun schoolNameNeverOverwritesWhatTheUserEdited() {
        ProfileRepository.bindAccount("gzus", "20221101")
        // 学校系统里显示的是「李某某」，但用户把昵称改成了「小李」
        ProfileRepository.applySchoolProfile("李某某", "计算机科学 · 计算机学院")
        assertEquals("李某某", ProfileRepository.nickname.value)

        ProfileRepository.setNickname("小李")
        // 这次同步（冷启动 / 下拉刷新）不能再把用户改的覆盖掉
        ProfileRepository.applySchoolProfile("李某某", "计算机科学 · 计算机学院")
        assertEquals("小李", ProfileRepository.nickname.value)
        assertEquals(
            "没被用户改过的专业照旧跟着学校走",
            "计算机科学 · 计算机学院",
            ProfileRepository.major.value,
        )
    }

    @Test
    fun savingWithoutChangingAnythingDoesNotFreezeTheSchoolName() {
        ProfileRepository.bindAccount("gzus", "20221101")
        ProfileRepository.applySchoolProfile("李某某", "")

        // 用户只是打开「编辑资料」又点了保存（昵称没动过）→ 不该把这一项锁死，
        // 否则学校那边之后改了名字，这边永远同步不过来
        ProfileRepository.setNickname("李某某")
        ProfileRepository.applySchoolProfile("李某某（转专业）", "")
        assertEquals("李某某（转专业）", ProfileRepository.nickname.value)
    }

    @Test
    fun clearingTheMajorIsRespected() {
        ProfileRepository.bindAccount("gzus", "20221101")
        ProfileRepository.applySchoolProfile("李某某", "计算机科学 · 计算机学院")

        // 用户把专业清空 = 我不想填，之后同步回来的专业不许再填回去
        ProfileRepository.setMajor("")
        ProfileRepository.applySchoolProfile("李某某", "计算机科学 · 计算机学院")
        assertEquals("", ProfileRepository.major.value)
    }

    @Test
    fun editsWithoutAnAccountAreNotWrittenAnywhere() {
        // 未登录（没有账号绑定）时只改内存：一次误写不能污染下一个登录的账号
        ProfileRepository.bindAccount(null, "")
        ProfileRepository.setNickname("匿名用户")

        assertEquals("匿名用户", ProfileRepository.nickname.value)
        assertEquals("没有身份就一个字都不该写盘", emptyMap<String, Any>(), storage.snapshot)
    }

    @Test
    fun blankNicknameIsRejected() {
        ProfileRepository.bindAccount("gzus", "20221101")
        ProfileRepository.applySchoolProfile("李某某", "")

        ProfileRepository.setNickname("   ")
        assertEquals("空昵称不能把已有名字擦掉（表单已经拦过一次，这是最后一道）", "李某某", ProfileRepository.nickname.value)
    }

    @Test
    fun blankSchoolValuesAreIgnored() {
        ProfileRepository.bindAccount("gzus", "20221101")
        ProfileRepository.setNickname("小李")
        ProfileRepository.setMajor("")

        // 学校这两项都没给：不能把界面上显示的东西擦成空
        ProfileRepository.applySchoolProfile("", "")
        assertEquals("小李", ProfileRepository.nickname.value)
        assertEquals("", ProfileRepository.major.value)
    }

    @Test
    fun localDebugAccountIsStoredByAccountAlone() {
        ProfileRepository.bindAccount(null, "admin")
        ProfileRepository.setNickname("调试同学")

        assertEquals("调试同学", storage.snapshot["nickname:admin"])

        ProfileRepository.bindAccount(null, "admin")
        assertEquals("调试同学", ProfileRepository.nickname.value)
    }
}
