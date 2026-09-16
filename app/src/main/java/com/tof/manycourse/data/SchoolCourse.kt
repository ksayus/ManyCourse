package com.tof.manycourse.data

/**
 * 从学校教务系统拉回来的一条课程。
 *
 * 为什么不直接用 [Course]：
 *  - [Course] 是本地的展示模型，`id` 是本地自增的（见 `CourseRepository`）；
 *  - 网络层只负责"把教务系统的字段翻译成这个中立结构"，写库由
 *    `CourseRepository.replaceAll` 统一做，网络层不碰 UI 状态。
 *
 * 字段口径与 [Course] 完全一致（`weekday` 1=周一…7=周日，`startPeriod` 从第 1 节起），
 * 见 `docs/UI使用文档.md` 第 2 章。
 */
data class SchoolCourse(
    val name: String,
    val teacher: String,
    val room: String,
    val weekday: Int,
    val startPeriod: Int,
    val periodCount: Int,
    /**
     * 原始周次串，如 `3-5,8-20周`。
     *
     * 目前**只做展示**：App 的课表按"每周循环"渲染，不区分单双周/起止周
     * （限制见 `docs/UI使用文档.md` §4）。留着它，一是给用户一个"这课不是每周都有"的提示，
     * 二是以后要支持周次时不用再改接口。
     */
    val weeks: String = "",
    /** 校区备注（金城学院会返回「禄口」），并进 [room] 展示 */
    val campus: String = "",
) {
    /** 展示用："第1-2节"（与 [Course.periodLabel] 口径一致）*/
    val periodLabel: String
        get() = if (periodCount <= 1) "第${startPeriod}节"
        else "第${startPeriod}-${startPeriod + periodCount - 1}节"

    /** 教室 + 校区拼成的地点（与 `CourseRepository.replaceAll` 的拼法一致）*/
    val fullRoom: String
        get() = listOf(room, campus).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * 学校系统里的学生信息 —— **姓名就在这里**。
 *
 * 需求「当前账户的名字自动写入学校系统中会显示的名字」指的就是
 * 登录后拉一次这个结构，把 [name] 交给 `ProfileRepository.applySchoolProfile`
 * （用户自己在「编辑资料」里改过的昵称优先，不会被这里覆盖）。
 *
 * 为什么不直接用用户输入的账号当昵称：学校系统里显示的是**姓名**（如「李同学」），
 * 而登录用的是**学号**；两者不是一回事。
 */
data class StudentProfile(
    /** 姓名，如「李同学」 */
    val name: String,
    /** 学号 / 工号（登录账号） */
    val account: String = "",
    /** 班级（金城学院是班号，如 `02220101`；正方是 `bjmc` 班级名称） */
    val className: String = "",
    /** 专业 */
    val major: String = "",
    /** 学院 / 院系 */
    val college: String = "",
) {
    /** 「我的」页副标题：专业 + 学院，缺哪个就省哪个 */
    val subtitle: String
        get() = listOf(major, college).filter { it.isNotBlank() }.joinToString(" · ")
}
