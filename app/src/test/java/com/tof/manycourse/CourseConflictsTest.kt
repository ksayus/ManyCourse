package com.tof.manycourse

import com.tof.manycourse.data.ConflictKind
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseConflicts
import com.tof.manycourse.data.CourseDraft
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.toDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表冲突检测的单测。
 *
 * ## 这里真正难的不是"判重叠"
 *
 * 节次重叠是个三行就能写完的闭区间相交；难的是**周次那一维**：
 *
 * - 把只在第 2-4 周上的军训和整学期的高数算成冲突 → **假警报**（每周都提示，用户很快就不看了）；
 * - 把认不出周次的课判成"不冲突" → **漏报**（学生白跑一趟教室）。
 *
 * 所以下面有一整组断言专门盯"两边都说得清才算得出不是冲突"这条线。
 */
class CourseConflictsTest {

    private fun course(
        id: Long,
        name: String,
        weekday: Int = 1,
        startPeriod: Int = 1,
        periodCount: Int = 2,
        room: String = "",
        teacher: String = "",
        weeks: String = "",
    ) = Course(
        id = id,
        name = name,
        teacher = teacher,
        room = room,
        weekday = weekday,
        startPeriod = startPeriod,
        periodCount = periodCount,
        weeks = weeks,
    )

    private fun draft(
        weekday: Int = 1,
        startPeriod: Int = 1,
        periodCount: Int = 2,
        room: String = "",
        teacher: String = "",
        weeks: String = "",
    ) = CourseDraft(
        name = "新课",
        teacher = teacher,
        room = room,
        weekday = weekday,
        startPeriod = startPeriod,
        periodCount = periodCount,
        weeks = weeks,
    )

    // ══════════════════ 节次 / 星期 ══════════════════

    @Test
    fun `同一天同一节次算冲突`() {
        val result = CourseConflicts.against(draft(), listOf(course(1, "高等数学")))
        assertEquals(1, result.size)
        assertEquals("高等数学", result[0].other.name)
        assertTrue("一定含时间冲突", ConflictKind.Time in result[0].kinds)
    }

    @Test
    fun `不同天不算冲突`() {
        assertTrue(CourseConflicts.against(draft(weekday = 1), listOf(course(1, "体育", weekday = 3))).isEmpty())
    }

    @Test
    fun `节次不重叠不算冲突`() {
        // 第 1-2 节 vs 第 3-4 节
        assertTrue(CourseConflicts.against(draft(startPeriod = 1), listOf(course(1, "英语", startPeriod = 3))).isEmpty())
    }

    /** 边界：第 1-2 节与第 2-3 节共享第 2 节 → 是冲突（闭区间相交）*/
    @Test
    fun `压着一节也算冲突`() {
        val result = CourseConflicts.against(draft(startPeriod = 1, periodCount = 2), listOf(course(1, "英语", startPeriod = 2, periodCount = 2)))
        assertEquals(1, result.size)
    }

    @Test
    fun `脏数据不算冲突`() {
        // 节数为 0 / 起始节次为 0 的坏数据不该把整张表点红
        assertTrue(CourseConflicts.against(draft(periodCount = 0), listOf(course(1, "高数"))).isEmpty())
        assertTrue(CourseConflicts.against(draft(), listOf(course(1, "高数", startPeriod = 0))).isEmpty())
        assertTrue(CourseConflicts.against(draft(weekday = 9), listOf(course(1, "高数", weekday = 9))).isEmpty())
    }

    // ══════════════════ 周次：假警报 vs 漏报 ══════════════════

    /** ★ 两边都说得清、且一周都不重叠 → 它们永远不会同时发生，**不许报** */
    @Test
    fun `周次完全不重叠时不算冲突_避免假警报`() {
        val result = CourseConflicts.against(
            draft(weeks = "2-4周"),
            listOf(course(1, "高等数学", weeks = "8-16周")),
        )
        assertTrue("第 2-4 周与第 8-16 周永远不会撞", result.isEmpty())
    }

    /** 周次部分重叠 → 报，且**只报真正重叠的那几周** */
    @Test
    fun `周次部分重叠时报出重叠的那几周`() {
        val result = CourseConflicts.against(
            draft(weeks = "2-5周"),
            listOf(course(1, "高等数学", weeks = "4-10周")),
        )
        assertEquals(1, result.size)
        assertTrue(result[0].weeksKnown)
        assertEquals(setOf(4, 5), result[0].weeks)
    }

    /**
     * ★ 手动加的课没有周次串 —— 那是"**每周都上**"，不是"不知道"。
     * 所以它能和 `3-5周` 的高数算出交集 `{3,4,5}`（真冲突），而不是走"说不清"那条路。
     */
    @Test
    fun `手动课没有周次按每周算_能算出真实交集`() {
        val result = CourseConflicts.against(draft(weeks = ""), listOf(course(1, "高等数学", weeks = "3-5周")))
        assertEquals(1, result.size)
        assertTrue(result[0].weeksKnown)
        assertEquals(setOf(3, 4, 5), result[0].weeks)
    }

    /** ★ 认不出的周次串 → **照样报**，但标记 weeksKnown = false（宁可多提醒一句） */
    @Test
    fun `周次认不出时照样报_但标记为未知`() {
        val result = CourseConflicts.against(draft(weeks = "全周"), listOf(course(1, "高等数学", weeks = "8-16周")))
        assertEquals("说不清就不能替用户断定'不冲突'", 1, result.size)
        assertFalse(result[0].weeksKnown)
    }

    // ══════════════════ 教室 / 教师 ══════════════════

    @Test
    fun `同一间教室会额外标出教室冲突`() {
        val result = CourseConflicts.against(
            draft(room = "教学楼 A-101"),
            listOf(course(1, "高等数学", room = "教学楼 A-101")),
        )
        assertTrue(result[0].touchesRoom)
    }

    /** 教务系统给的 `教1-106(揭)` 与用户手打的 `教 1-106(揭)` 是同一间教室 */
    @Test
    fun `地点比较时忽略空格与大小写`() {
        val result = CourseConflicts.against(
            draft(room = "教 1-106(揭)"),
            listOf(course(1, "高等数学", room = "教1-106(揭)")),
        )
        assertTrue("去空白之后应当认出是同一间", result[0].touchesRoom)
    }

    /** 两门课**都没填**地点 → 两个未知，不是"同一个地方"（否则每两门课都会报教室冲突）*/
    @Test
    fun `都没填地点时不算教室冲突`() {
        val result = CourseConflicts.against(draft(room = ""), listOf(course(1, "高等数学", room = "")))
        assertFalse(result[0].touchesRoom)
    }

    /** 保存时给空地点填的占位符「地点待定」不是一间真的教室 */
    @Test
    fun `地点待定占位符不算同一间教室`() {
        val result = CourseConflicts.against(
            draft(room = CourseRepository.ROOM_PLACEHOLDER),
            listOf(course(1, "高等数学", room = CourseRepository.ROOM_PLACEHOLDER)),
        )
        assertFalse("占位符不是教室", result[0].touchesRoom)
    }

    @Test
    fun `同一位教师会额外标出教师冲突`() {
        val result = CourseConflicts.against(
            draft(teacher = "王建国"),
            listOf(course(1, "高等数学", teacher = "王建国")),
        )
        assertTrue(result[0].touchesTeacher)
    }

    @Test
    fun `只有一边有教师时不算教师冲突`() {
        val result = CourseConflicts.against(draft(teacher = "王建国"), listOf(course(1, "高等数学", teacher = "")))
        assertFalse(result[0].touchesTeacher)
    }

    // ══════════════════ summary / countByCourse ══════════════════

    @Test
    fun `summary 是一句可以直接展示的中文`() {
        val result = CourseConflicts.against(
            draft(weeks = "3-5周", room = "A-101"),
            listOf(course(1, "高等数学", weekday = 1, startPeriod = 1, room = "A-101", weeks = "3-5周")),
        )
        assertEquals("与「高等数学」3-5周 · 周一 第1-2节 时间、教室冲突", result[0].summary())
    }

    @Test
    fun `summary 在周次未知时如实说明`() {
        val result = CourseConflicts.against(draft(weeks = "全周"), listOf(course(1, "高等数学", weeks = "1-16周")))
        assertTrue("不能编一个具体周次出来", result[0].summary().contains("周次未知"))
    }

    @Test
    fun `countByCourse 一对冲突两边各计一次`() {
        val a = course(1, "高等数学")
        val b = course(2, "大学英语", startPeriod = 2)
        val c = course(3, "体育", weekday = 3)
        val counts = CourseConflicts.countByCourse(listOf(a, b, c))
        assertEquals(1, counts[1])
        assertEquals(1, counts[2])
        assertTrue("没卷进冲突的课不该出现在表里", counts[3] == null)
    }

    @Test
    fun `countByCourse 对周次不重叠的一对不计`() {
        val a = course(1, "军训", weeks = "2-4周")
        val b = course(2, "高等数学", weeks = "8-16周")
        assertTrue(CourseConflicts.countByCourse(listOf(a, b)).isEmpty())
    }

    /** 被编辑的那门课要从 others 里排除掉，否则它会跟自己撞 */
    @Test
    fun `编辑时把自己排除掉就没有冲突`() {
        val editing = course(7, "高等数学")
        val others = listOf(editing, course(8, "体育", weekday = 3))
        // 不排除 → 自己跟自己撞
        assertEquals(1, CourseConflicts.against(editing.toDraft(), others).size)
        // 排除掉 → 干净
        assertTrue(CourseConflicts.against(editing.toDraft(), others.filterNot { it.id == editing.id }).isEmpty())
    }
}
