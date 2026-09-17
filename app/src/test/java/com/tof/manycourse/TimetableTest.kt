package com.tof.manycourse

import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.LoginSettings
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.data.Timetable
import com.tof.manycourse.data.Timetables
import com.tof.manycourse.gr_api.SchoolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **节次表（作息）按学校分**的回归测试。
 *
 * 为什么值得单测：这张表决定了"一天有几节、每节几点"，而它错的时候**不会崩**，
 * 只会安静地把不存在的第 15-16 节画出来、或者把别校的时刻当成自己的作息：
 *
 * | 错法 | 用户看到什么 |
 * |---|---|
 * | 广工用了默认的 8 行表 | "添加课程"能选到不存在的第 15-16 节；脏数据被夹到 16 而不是 14 |
 * | 时刻被"补"出来 | 时间轴写着 9:00，而广工第 1 节并不是 9:00 —— 比空着更糟（看着很像真的） |
 *
 * 对应实现：`data/Timetable.kt`（表 + 选表）、`data/School.kt`（挂在 `School` 上）、
 * `data/Course.kt`（`CourseRepository` 的取值口径）。
 */
class TimetableTest {

    // ── 默认表：16 节 / 8 个两节块 ──────────────────────────────────────────

    @Test
    fun defaultTableIsEightTwoPeriodBlocksOfSixteenPeriods() {
        // 默认表挂着广软/金城（正方系统），时刻逐条钉住：改它必须是刻意的
        val blocks = Timetables.default.blocks.map { it.start to it.end }
        assertEquals(
            listOf(
                "9:00" to "10:20",
                "10:40" to "12:00",
                "12:30" to "13:50",
                "14:00" to "15:20",
                "15:30" to "16:50",
                "17:00" to "18:20",
                "19:00" to "20:20",
                "20:30" to "21:50",
            ),
            blocks,
        )
        assertEquals(8, Timetables.default.blockCount)
        assertEquals(16, Timetables.default.maxPeriod)
        assertEquals(40, Timetables.default.periodMinutes)
        assertTrue("默认表有完整时刻", Timetables.default.hasTimes)
    }

    // ── 广工：14 节 / 7 个两节块，且**没有时刻** ─────────────────────────────

    @Test
    fun gdutHasFourteenPeriodsInSevenBlocks() {
        // ★ 这条钉的就是报上来的事实：广工一天 14 节。
        //   依据是它自己的课表页（服务端渲染「第01节 … 第14节」14 行）
        assertEquals("14 节 = 7 个两节块", 7, Timetables.gdut.blockCount)
        assertEquals(14, Timetables.gdut.maxPeriod)
        assertEquals(
            "第 13-14 节就在最后一行（块下标 6）",
            6,
            Timetables.gdut.blockIndexOf(14),
        )
        assertEquals("越界的节次夹到最后一行，不让课从网格里消失", 6, Timetables.gdut.blockIndexOf(99))
    }

    @Test
    fun gdutHasNoTimesSoNothingMayBeInvented() {
        // ★ 广工这版**没有作息数据**：宁可整张表都没有时刻，也不编一个"9:00"出来 ——
        //   编出来的时刻会以"看起来很确定"的样子骗人（"我第 1 节不是 9:00 啊"）
        assertFalse("整张表都没有时刻", Timetables.gdut.hasTimes)
        assertTrue("每个块的时刻都是空串", Timetables.gdut.blocks.all { it.start.isBlank() && it.end.isBlank() })
        assertEquals("", Timetables.gdut.startOfPeriod(1))
        assertEquals("", Timetables.gdut.endOfPeriod(1))
        assertEquals("", Timetables.gdut.startOfBlock(0))
        assertEquals("", Timetables.gdut.endOfBlock(6))
    }

    @Test
    fun halfKnownTablesCountAsUnknown() {
        // 半张表（有的块有时刻、有的没有）会让时间轴一半写时刻一半空着，看着像画错了；
        // 而且"现在上到第几节"只要少一块就判不出来 → 一律当成"没有时刻表"
        val half = Timetable(
            blocks = listOf(
                Timetable.Block("9:00", "10:20"),
                Timetable.Block("", ""),
            ),
            periodMinutes = 40,
        )
        assertFalse(half.hasTimes)
    }

    // ── 按学校选表 ────────────────────────────────────────────────────────

    @Test
    fun everyRegisteredSchoolResolvesToATable() {
        // 新加一所学校忘了声明作息时，至少会落到默认表（而不是空表 → 整块网格消失）
        SchoolRegistry.schools.forEach { school ->
            assertTrue("「${school.name}」的节次表不能是空的", school.timetable.blockCount > 0)
            assertEquals(
                "「${school.name}」的节数必须等于块数 × 2",
                school.timetable.blockCount * 2,
                school.timetable.maxPeriod,
            )
        }
    }

    @Test
    fun gdutIsTheFourteenPeriodSchoolAndOthersKeepTheDefaultTable() {
        assertSame("广工 = 14 节那张表", Timetables.gdut, Timetables.of("gdut"))
        assertEquals(
            "学校自己声明的那张表才是权威（不要把表的来源换成另一处）",
            Timetables.gdut,
            SchoolRegistry.find("gdut")?.timetable,
        )
        // 没声明作息的学校 / 没选学校 / 学校 id 拼错 → 默认表（宁可多几行，也别画不出网格）
        assertSame(Timetables.default, Timetables.of("gzus"))
        assertSame(Timetables.default, Timetables.of("nhjcxy"))
        assertSame(Timetables.default, Timetables.of(null))
        assertSame(Timetables.default, Timetables.of(""))
        assertSame(Timetables.default, Timetables.of("no-such-school"))
    }

    // ── `CourseRepository` 的取值口径跟着当前学校走 ──────────────────────────

    @Test
    fun courseRepositoryFollowsTheCurrentSchool() {
        withSchool("gdut") {
            assertEquals("广工 14 节", 14, CourseRepository.MAX_PERIOD)
            assertEquals("14 节 = 7 行", 7, CourseRepository.BLOCK_COUNT)
            assertEquals("第 1-2 节 = 第 1 行（0 起）", 0, CourseRepository.blockIndexOf(1))
            assertEquals("第 13-14 节 = 第 7 行（0 起）", 6, CourseRepository.blockIndexOf(13))
            assertEquals(6, CourseRepository.blockIndexOf(14))
            assertEquals("第 15 节不存在（夹到第 14 节所在的行）", 6, CourseRepository.blockIndexOf(15))
            assertEquals("没有作息 → 第 1 节的时刻是空串", "", CourseRepository.periodTime(1))
            assertEquals("", CourseRepository.blockStartTime(0))
            assertEquals("", CourseRepository.blockRangeLabel(0))
            assertEquals("课程详情的「上课时间」也不该凭空多出时刻", "", CourseRepository.periodRangeLabel(1, 2))
        }
    }

    @Test
    fun gdutAndDefaultSchoolsDifferOnTheSameRepository() {
        // 同一份数据、同一处调用：只因为"当前学校"不同，节数与时刻就不同 ——
        // 这正是"表按学校分"的全部意义（也是这条测试的存在理由）
        withSchool("gdut") {
            assertEquals(14, CourseRepository.MAX_PERIOD)
            assertEquals("", CourseRepository.periodTime(1))
        }
        withSchool(null) {
            assertEquals(16, CourseRepository.MAX_PERIOD)
            assertEquals("9:00", CourseRepository.periodTime(1))
        }
    }

    /**
     * 临时把"当前学校"设成 [schoolId] 跑一段，**用完还原**。
     *
     * 为什么必须还原：`SessionStore` 是进程级单例，测试跑在同一个 JVM 里 ——
     * 留着"广工"的话，别的用例（比如默认表那 8 行的断言）会莫名其妙地红。
     */
    private fun <T> withSchool(schoolId: String?, block: () -> T): T {
        val previousSchool = SessionStore.schoolId.value
        val previousSelected = LoginSettings.selectedSchoolId.value
        return try {
            SessionStore.schoolId.value = schoolId
            LoginSettings.selectedSchoolId.value = null
            block()
        } finally {
            SessionStore.schoolId.value = previousSchool
            LoginSettings.selectedSchoolId.value = previousSelected
        }
    }
}
