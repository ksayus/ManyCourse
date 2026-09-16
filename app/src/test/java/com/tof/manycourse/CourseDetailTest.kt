package com.tof.manycourse

import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.SchoolCourse
import com.tof.manycourse.ui.CourseDetail
import com.tof.manycourse.ui.courseDetailBadge
import com.tof.manycourse.ui.courseDetailOf
import com.tof.manycourse.ui.orderedDayCourses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 课程详情卡片的字段口径测试（`ui/CourseDetailDialog.kt` 的 `courseDetailOf`）。
 *
 * 详情卡是两种来源（教务系统按周给的 / 本地课表里的）共用一个界面，
 * 所以最容易出的错是"某一条路径少了一个字段"：按周查的课显示不出教师、
 * 手加的课硬塞一格"全部周次：（空）"。这些都不会崩，只会让用户觉得信息不对。
 */
class CourseDetailTest {

    private fun weekEntry(
        teacher: String = "王建国",
        room: String = "A1N403",
        campus: String = "禄口",
        weeks: String = "3-5,8-20周",
    ) = CourseEntry.Week(
        SchoolCourse(
            name = "高等数学",
            teacher = teacher,
            room = room,
            weekday = 1,
            startPeriod = 1,
            periodCount = 2,
            weeks = weeks,
            campus = campus,
        )
    )

    private fun localEntry(
        name: String = "我加的课",
        teacher: String = "",
        room: String = "自习室",
        weeks: String = "",
        fromSchool: Boolean = false,
    ) = CourseEntry.Local(
        Course(
            id = 1,
            name = name,
            teacher = teacher,
            room = room,
            weekday = 3,
            startPeriod = 5,
            periodCount = 2,
            weeks = weeks,
            fromSchool = fromSchool,
        )
    )

    /** 明细按标签取「值」，方便断言；顺带忽略顺序 */
    private fun valuesOf(detail: CourseDetail): Map<String, String> =
        detail.fields.associate { it.label to it.value }

    private fun fullLabelsOf(detail: CourseDetail): List<String> =
        detail.fields.filter { it.full }.map { it.label }

    @Test
    fun weekCourseShowsTeacherWeeksAndCampusMergedRoom() {
        val values = valuesOf(courseDetailOf(weekEntry()))

        assertEquals("教务系统的课必须带上教师", "王建国", values["任课教师"])
        assertEquals("教室和校区要拼在一起，与课程卡片上的口径一致", "A1N403 禄口", values["教室安排"])
        assertEquals("3-5,8-20周", values["全部周次"])
        assertTrue(
            "来源要说清楚这是按周查回来的（只属于这一周），不是整学期课表",
            values.getValue("来源").contains("周次课表"),
        )
    }

    @Test
    fun syncedLocalCourseIsLabeledAsWholeTermSchedule() {
        val values = valuesOf(courseDetailOf(localEntry(name = "大学英语", teacher = "李芳", weeks = "1-16周", fromSchool = true)))

        assertEquals("李芳", values["任课教师"])
        assertEquals("1-16周", values["全部周次"])
        assertTrue(values.getValue("来源").contains("整学期课表"))
    }

    @Test
    fun manuallyAddedCourseIsLabeledAsManual() {
        val values = valuesOf(courseDetailOf(localEntry()))

        assertEquals("手动添加", values["来源"])
        assertEquals("自习室", values["教室安排"])
    }

    @Test
    fun blankFieldsDisappearInsteadOfPrintingEmptyCells() {
        // 手加的课没有教师、没有周次：这两格整个不出现，而不是显示"任课教师："
        // 也不要写"暂无"（用户自己知道没填）
        val values = valuesOf(courseDetailOf(localEntry(teacher = "", weeks = "")))

        assertTrue("空教师不该占一格", "任课教师" !in values)
        assertTrue("空周次不该占一格", "全部周次" !in values)
        assertTrue("有值的教室安排还在", "教室安排" in values)
        assertTrue("来源永远有（它是我们自己归一出来的）", "来源" in values)
    }

    @Test
    fun badgeTellsWhichDayAndHowManyCourses() {
        // 详情卡列的是"那一天有什么课"：胶囊要写清哪天、共几门
        assertEquals(
            "9月14日 周一 · 共 3 门课",
            courseDetailBadge(LocalDate.of(2026, 9, 14), count = 3),
        )
        assertEquals(
            "说不清哪天时就只写门数（不编一个日期出来）",
            "共 1 门课",
            courseDetailBadge(date = null, count = 1),
        )
    }

    @Test
    fun dayCoursesAreOrderedByPeriod() {
        // "按顺序显示"：排序放在这里做，各页面不必各自排一遍
        // （手加的课是拼在周次课后面的，不排就会跑到最后）
        val ordered = orderedDayCourses(
            listOf(
                localEntry(name = "第三门").let { it },
                weekEntry(),
                localEntry(name = "第二门"),
            ).sortedByDescending { it.startPeriod },
        )

        assertEquals(
            "按开始节次升序",
            ordered.map { it.startPeriod }.sorted(),
            ordered.map { it.startPeriod },
        )
    }

    @Test
    fun classTimeIsTheSpanPlusWhichPeriods() {
        // 上课时间 = 节次 + 起止时刻（连上两节就是「第1-2节 · 8:00~9:40」）
        assertEquals("第1-2节 · 8:00~9:40", valuesOf(courseDetailOf(weekEntry()))["上课时间"])
        assertEquals("第5-6节 · 14:00~15:40", valuesOf(courseDetailOf(localEntry()))["上课时间"])
    }

    @Test
    fun longValuesTakeAWholeRowAndNamesAreCopyable() {
        // 两列布局的约定：上课时间/教室/来源 独占一行；课名与教室/教师可复制
        val detail = courseDetailOf(weekEntry())

        assertEquals(listOf("上课时间", "教室安排", "来源"), fullLabelsOf(detail))
        val copyable = detail.fields.filter { it.copyable }.map { it.label }
        assertEquals(listOf("任课教师", "教室安排"), copyable)
    }

    @Test
    fun accentColorComesFromTheCourseName() {
        // 同一门课在课表页、日历页、月历圆点、网格卡片和详情卡上必须同色 —— 都取 colorOfName
        assertEquals(
            CourseRepository.colorOfName("高等数学"),
            courseDetailOf(weekEntry()).accent,
        )
    }
}
