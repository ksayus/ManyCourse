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

    // ── 广工：12 节 / 6 个两节块，作息已收录 ─────────────────────────────────

    @Test
    fun gdutHasTwelvePeriodsInSixBlocks() {
        // ★ 作息时间表只定义到第 12 节。学校课表页渲染的「第01节 … 第14节」14 行
        //   是教务系统的**固定模板**，不是广工的作息（证据见 `Timetables.gdut` 的注释）。
        assertEquals("12 节 = 6 个两节块", 6, Timetables.gdut.blockCount)
        assertEquals(12, Timetables.gdut.maxPeriod)
        assertEquals("第 11-12 节就在最后一行（块下标 5）", 5, Timetables.gdut.blockIndexOf(12))
        assertEquals("越界的节次夹到最后一行，不让课从网格里消失", 5, Timetables.gdut.blockIndexOf(99))
    }

    /**
     * ★ 广工作息的要害：**块内两节的间隔不是固定的**。
     *
     * 第 1-2 节隔 5 分钟，第 7-8 节隔 15 分钟，第 9-10 节隔 **25** 分钟。
     * 只靠一个统一的 `periodMinutes` 推，第 8 节会算成 16:15（实际 16:30）、
     * 第 10 节会算成 18:05（实际 18:30）—— 课前提醒会早 15~25 分钟，
     * 而且是把错时刻当事实显示。这几条断言钉的就是 `Block.secondStart` 真的生效了。
     */
    @Test
    fun gdutUnevenBreaksAreExactForEveryPeriod() {
        val expected = mapOf(
            1 to ("8:30" to "9:15"),
            2 to ("9:20" to "10:05"),
            3 to ("10:25" to "11:10"),
            4 to ("11:15" to "12:00"),
            5 to ("13:50" to "14:35"),
            6 to ("14:40" to "15:25"),
            7 to ("15:30" to "16:15"),
            8 to ("16:30" to "17:15"),
            9 to ("17:20" to "18:05"),
            10 to ("18:30" to "19:15"),
            11 to ("19:20" to "20:05"),
            12 to ("20:10" to "20:55"),
        )
        expected.forEach { (period, range) ->
            assertEquals("第 $period 节的开始时刻", range.first, Timetables.gdut.startOfPeriod(period))
            assertEquals("第 $period 节的结束时刻", range.second, Timetables.gdut.endOfPeriod(period))
        }
        assertTrue("整张表的时刻都收齐了", Timetables.gdut.hasTimes)
        assertEquals("第 13 节不存在 → 空串，绝不编一个时刻", "", Timetables.gdut.startOfPeriod(13))
    }

    /**
     * `secondStart` 是**可选**的：没给时仍按"块起点 + periodMinutes"推。
     *
     * 这条是给默认表（广软 / 金城，块内两节等长）兜底的 ——
     * 加了新字段不能把老学校的时刻算歪。
     */
    @Test
    fun secondStartIsOptionalAndFallsBackToPeriodMinutes() {
        val derived = Timetable(
            blocks = listOf(Timetable.Block("9:00", "10:20")),
            periodMinutes = 40,
        )
        assertEquals("9:00", derived.startOfPeriod(1))
        assertEquals("9:40", derived.startOfPeriod(2))

        val explicit = Timetable(
            blocks = listOf(Timetable.Block("8:30", "10:05", secondStart = "9:20")),
            periodMinutes = 45,
        )
        assertEquals("8:30", explicit.startOfPeriod(1))
        assertEquals("9:20", explicit.startOfPeriod(2))
        assertEquals("9:15", explicit.endOfPeriod(1))
        assertEquals("10:05", explicit.endOfPeriod(2))
    }

    @Test
    fun halfKnownTablesCountAsUnknown() {
        // 半张表在 `hasTimes` 上仍算"没有时刻表"（它是给"要不要显示时刻"这类整表判断用的）。
        // 但**时间轴本身是逐行判断的**：有时刻的行照常写时刻，没时刻的行只写「第N-M节」——
        // 所以"半张表"在界面上是如实画出来的，不会一半空着像画错。
        val half = Timetable(
            blocks = listOf(
                Timetable.Block("9:00", "10:20"),
                Timetable.Block("", ""),
            ),
            periodMinutes = 40,
        )
        assertFalse(half.hasTimes)
        assertEquals("有时刻的那一行照常给时刻", "9:00", half.startOfPeriod(1))
        assertEquals("没时刻的那一行给空串", "", half.startOfPeriod(3))
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
    fun gdutIsTheTwelvePeriodSchoolAndOthersKeepTheDefaultTable() {
        assertSame("广工 = 12 节那张表", Timetables.gdut, Timetables.of("gdut"))
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
            assertEquals("广工 12 节", 12, CourseRepository.MAX_PERIOD)
            assertEquals("12 节 = 6 行", 6, CourseRepository.BLOCK_COUNT)
            assertEquals("第 1-2 节 = 第 1 行（0 起）", 0, CourseRepository.blockIndexOf(1))
            assertEquals("第 11-12 节 = 第 6 行（0 起）", 5, CourseRepository.blockIndexOf(11))
            assertEquals(5, CourseRepository.blockIndexOf(12))
            assertEquals("第 13 节不存在（夹到第 12 节所在的行）", 5, CourseRepository.blockIndexOf(13))
            // 作息已收录：时刻现在**推得出来**了（以前这里全是空串）
            assertEquals("第 1 节 8:30", "8:30", CourseRepository.periodTime(1))
            assertEquals("8:30", CourseRepository.blockStartTime(0))
            assertEquals("8:30~10:05", CourseRepository.blockRangeLabel(0))
            assertEquals(
                "课程详情「上课时间」= 第一节开始 ~ 最后一节结束",
                "8:30~10:05",
                CourseRepository.periodRangeLabel(1, 2),
            )
        }
    }

    @Test
    fun gdutAndDefaultSchoolsDifferOnTheSameRepository() {
        // 同一份数据、同一处调用：只因为"当前学校"不同，节数与时刻就不同 ——
        // 这正是"表按学校分"的全部意义（也是这条测试的存在理由）
        withSchool("gdut") {
            assertEquals(12, CourseRepository.MAX_PERIOD)
            assertEquals("8:30", CourseRepository.periodTime(1))
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
