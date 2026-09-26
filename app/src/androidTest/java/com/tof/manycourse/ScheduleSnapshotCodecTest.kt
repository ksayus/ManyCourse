package com.tof.manycourse

import com.tof.manycourse.data.ScheduleSnapshot
import com.tof.manycourse.data.ScheduleSnapshotCodec
import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.data.formatCacheTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 课表快照编解码的回归测试（JVM，无需设备、不联网）。
 *
 * `ScheduleVault` 那层要碰 Android Keystore（JVM 测不了），但"快照长什么样"是纯逻辑。
 * 编错了的表现**不会崩**，只会让用户在登录过期时看到一份错的课表 —— 属于最该被测住的那一类。
 */
class ScheduleSnapshotCodecTest {

    private val courses = listOf(
        SchoolCourse("高等数学", "王建国", "教学楼 A-101", 1, 1, 2, "1-16周"),
        SchoolCourse("军事理论", "李教官", "报告厅", 3, 5, 2, "2-4周", "禄口"),
    )

    private val snapshot = ScheduleSnapshot(
        schoolId = "nhjcxy",
        account = "0222010102",
        studentName = "李同学",
        courses = courses,
        weeks = listOf(
            SchoolWeek(1, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13)),
            SchoolWeek(3, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20)),
        ),
        coursesByWeek = mapOf(1 to courses, 3 to emptyList()),
        savedAt = 1758000000000L,
    )

    @Test
    fun roundTripPreservesEverything() {
        val decoded = requireNotNull(ScheduleSnapshotCodec.decode(ScheduleSnapshotCodec.encode(snapshot)))

        assertEquals(snapshot.schoolId, decoded.schoolId)
        assertEquals(snapshot.account, decoded.account)
        assertEquals(snapshot.studentName, decoded.studentName)
        assertEquals(snapshot.savedAt, decoded.savedAt)
        assertEquals(snapshot.courses, decoded.courses)
        assertEquals(snapshot.weeks, decoded.weeks)
        assertEquals(snapshot.coursesByWeek, decoded.coursesByWeek)
    }

    @Test
    fun emptyWeekStaysEmptyInsteadOfDisappearing() {
        // ★ 这条是日历页的命门：空表 = "这一周确实没课"，缺失 = "这一周还没拉到"。
        //   两者混掉，日历就会把"没课"显示成"还没加载"，进而回落到本地课表兜底
        val decoded = requireNotNull(ScheduleSnapshotCodec.decode(ScheduleSnapshotCodec.encode(snapshot)))

        assertTrue("第 3 周必须存在", decoded.coursesByWeek.containsKey(3))
        assertTrue("第 3 周必须是空表", decoded.coursesByWeek.getValue(3).isEmpty())
        assertEquals(courses, decoded.coursesByWeek.getValue(1))
    }

    @Test
    fun handlesSeparatorsNewlinesAndCjkInFields() {
        // 课程名/教室里有 `|`、换行、中文都不能把格式撑破（值一律 Base64）
        val tricky = courses.first().copy(
            name = "形势|与政策\n（含换行）",
            teacher = "张\u0000老师",
            room = "A|B\tC",
            campus = "禄口·新区",
        )
        val decoded = requireNotNull(
            ScheduleSnapshotCodec.decode(ScheduleSnapshotCodec.encode(snapshot.copy(courses = listOf(tricky))))
        )

        assertEquals(tricky, decoded.courses.single())
    }

    @Test
    fun missingIdentityDecodesToNull() {
        // 少了学校或账号 = 恢复出一份"身份不明的课表"，必须整份作废
        val text = ScheduleSnapshotCodec.encode(snapshot)
        assertNull(ScheduleSnapshotCodec.decode(text.lineSequence().filterNot { it.startsWith("schoolId=") }.joinToString("\n")))
        assertNull(ScheduleSnapshotCodec.decode(text.lineSequence().filterNot { it.startsWith("account=") }.joinToString("\n")))
    }

    @Test
    fun oneBrokenCourseLineIsDroppedNotTheWholeSnapshot() {
        // 坏行只丢那一行：宁可少一门课，也不要整份作废后把用户打回空白课表
        val text = ScheduleSnapshotCodec.encode(snapshot).replaceFirst("c=", "c=!!!!not-base64!!!!|x")
        val decoded = ScheduleSnapshotCodec.decode(text)

        assertNotNull(decoded)
        assertEquals(1, requireNotNull(decoded).courses.size)
        assertEquals(2, decoded.weeks.size) // 教学周不受影响
    }

    @Test
    fun garbageDecodesToNullInsteadOfCrashing() {
        assertNull(ScheduleSnapshotCodec.decode(""))
        assertNull(ScheduleSnapshotCodec.decode("这不是存档"))
        assertNull(ScheduleSnapshotCodec.decode("manycourse-session/1\naccount=x"))
    }

    @Test
    fun headerIdentifiesOurFormat() {
        assertTrue(ScheduleSnapshotCodec.hasOurHeader(ScheduleSnapshotCodec.encode(snapshot)))
        assertFalse(ScheduleSnapshotCodec.hasOurHeader("manycourse-session/1\nxxx"))
        assertEquals("manycourse-schedule/1", ScheduleSnapshotCodec.HEADER)
    }

    @Test
    fun accountAndNameAreNotStoredInPlainText() {
        // 快照会被加密，但顺手确认一层：敏感值本身不该明文出现
        val text = ScheduleSnapshotCodec.encode(snapshot)
        assertFalse("学号不应明文出现在快照里", text.contains(snapshot.account))
        assertFalse("姓名不应明文出现在快照里", text.contains(snapshot.studentName))
    }

    @Test
    fun formatsCacheTimeInTheGivenZone() {
        val millis = LocalDateTime.of(2026, 9, 16, 14, 30)
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

        assertEquals("09-16 14:30", formatCacheTime(millis, ZoneId.of("Asia/Shanghai")))
    }
}