package com.tof.manycourse.data

/**
 * 冲突的**成因**。一门课可能同时占多项（撞在同一间教室、又是同一位老师），
 * 所以这里是集合而不是单选 —— 单选会丢掉"为什么可疑"这条信息。
 */
enum class ConflictKind {
    /** 同一时间段（学生只能去一个）*/
    Time,

    /** 同一间教室（而且在同一时间段）—— 那是排课的错 */
    Room,

    /** 同一位教师（而且在同一时间段）—— 那要么是排课的错，要么是同名/重复录入 */
    Teacher,
}

/**
 * 一门**还没保存的课**（表单里的那几项）。
 *
 * 为什么不直接用 [Course]：`Course` 有本地自增 `id` 与 `fromSchool`，
 * 而表单里的东西还没有身份。用哨兵 id 冒充"新增"会让"这是新加的课还是已有的课"变成靠约定，
 * 迟早有人写出 `id = -1` 这种判断。
 */
data class CourseDraft(
    val name: String = "",
    val teacher: String = "",
    val room: String = "",
    val weekday: Int = 1,
    val startPeriod: Int = 1,
    val periodCount: Int = 2,
    val weeks: String = "",
)

/** 拿一门已存在的课当草稿（编辑时用）*/
fun Course.toDraft(): CourseDraft =
    CourseDraft(name, teacher, room, weekday, startPeriod, periodCount, weeks)

/**
 * 一处冲突：**这门课**（草稿）与 [other]（已在课表里的某门课）撞了。
 */
data class CourseConflict(
    /** 撞上的那门课 */
    val other: Course,

    /** 成因（一定含 [ConflictKind.Time]）*/
    val kinds: Set<ConflictKind>,

    /** 两边**真正会同时发生**的周次；[weeksKnown] 为 false 时不可信 */
    val weeks: Set<Int>,

    /**
     * [weeks] 是不是可信的。
     *
     * `false` = 至少一边的周次串**认不出**（`全周` 这种），此时我们没有依据说
     * "它们其实不同周" —— 那就**照样报**。宁可多提醒一句，也不能漏掉一次撞课：
     * 漏报的代价是学生白跑一趟教室。
     */
    val weeksKnown: Boolean,
) {
    val touchesRoom: Boolean get() = ConflictKind.Room in kinds
    val touchesTeacher: Boolean get() = ConflictKind.Teacher in kinds

    /**
     * 一句可以直接展示的中文说明。
     *
     * 放在这里而不是 UI：它要跟冲突判定共用同一套口径（周次怎么算、成因怎么排序），
     * 分开写迟早出现"提示里说第 3-5 周、判定其实是第 4-6 周"。纯函数，可单测。
     */
    fun summary(): String {
        val weekText = if (weeksKnown) WeekRanges.normalize(weeks.sorted().joinToString(",")) else "周次未知"
        val kindText = buildList {
            add("时间")
            if (touchesRoom) add("教室")
            if (touchesTeacher) add("教师")
        }.joinToString("、")
        return "与「${other.name}」$weekText · ${weekdayLabel(other.weekday)} ${other.periodLabel} ${kindText}冲突"
    }
}

/**
 * **课表冲突检测**（纯逻辑，可直接 JVM 单测）。
 *
 * ## 判"撞了"的三个条件，缺一不可
 *
 * ```
 *   ① 同一天（星期几相同）
 *   ② 节次区间有重叠
 *   ③ 周次有交集 —— 而且这一条**说不清时按"可能撞"处理**
 * ```
 *
 * 第 ③ 条是最容易做错的地方：把只在第 2-4 周上的军训和整学期的高数算成冲突，
 * 那是**假警报**（每周都提示一次，用户很快就不看了）；反过来把认不出周次的课判成"不冲突"，
 * 那是**漏报**（学生白跑一趟）。
 *
 * 这里的取舍是：**两边都说得清、且交集为空 → 不是冲突**（过滤掉假警报）；
 * **只要有一边说不清 → 照报**（不放过真冲突）。
 */
object CourseConflicts {

    /**
     * "没有周次限制"的等价集合。
     *
     * 用户自己加的课没有周次串 —— 那是"每周都上"，不是"不知道"。
     * 这两者在判定里必须分开：前者可以和高数的 `3-5周` 算出交集 `{3,4,5}`（真冲突），
     * 后者只能按"说不清"保守处理。
     */
    private val EveryWeek: Set<Int> = (1..WeekRanges.MAX_WEEKS).toSet()

    /**
     * 草稿 [draft] 与已有课表 [others] 之间的全部冲突。
     *
     * 编辑已有课程时，**要把被编辑的那门课从 [others] 里排除掉**，否则它会跟自己撞。
     *
     * @return 按节次排序；没有冲突返回空表（调用方据此决定要不要显示警示条）
     */
    fun against(draft: CourseDraft, others: List<Course>): List<CourseConflict> {
        if (draft.weekday !in 1..7) return emptyList()
        if (draft.periodCount <= 0) return emptyList()

        return others
            .filter { it.weekday == draft.weekday }
            .filter {
                periodsOverlap(draft.startPeriod, draft.periodCount, it.startPeriod, it.periodCount)
            }
            .mapNotNull { other ->
                val mine = possibleWeeks(draft.weeks)
                val theirs = possibleWeeks(other.weeks)
                val weeks = if (mine == null || theirs == null) null else mine intersect theirs

                // ★ 两边都说得清、而且一周都不重叠 → 它们永远不会同时发生，**不是冲突**
                if (weeks != null && weeks.isEmpty()) return@mapNotNull null

                CourseConflict(
                    other = other,
                    kinds = kindsOf(draft.teacher, draft.room, other),
                    weeks = weeks.orEmpty(),
                    weeksKnown = weeks != null,
                )
            }
            .sortedBy { it.other.startPeriod }
    }

    /**
     * **每门课（按 id）卷进几处冲突** —— 课表页给卡片打"冲突"角标就靠它。
     *
     * 一对冲突**两边都计一次**（A 与 B 撞了，A 记一处、B 也记一处）：
     * 角标挂在卡片上，学生从哪一边看过去都该看到。
     */
    fun countByCourse(courses: List<Course>): Map<Long, Int> {
        val counts = mutableMapOf<Long, Int>()
        for (i in courses.indices) {
            for (j in i + 1 until courses.size) {
                val a = courses[i]
                val b = courses[j]
                if (a.weekday != b.weekday) continue
                if (!periodsOverlap(a.startPeriod, a.periodCount, b.startPeriod, b.periodCount)) continue

                val mine = possibleWeeks(a.weeks)
                val theirs = possibleWeeks(b.weeks)
                val weeks = if (mine == null || theirs == null) null else mine intersect theirs
                if (weeks != null && weeks.isEmpty()) continue

                counts[a.id] = (counts[a.id] ?: 0) + 1
                counts[b.id] = (counts[b.id] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * 这门课**可能上的周次**。
     *
     * @return `null` = 认不出周次串（按"说不清"保守处理）；
     *   空串 → [EveryWeek]（用户没填周次 = 每周都上）
     */
    private fun possibleWeeks(raw: String): Set<Int>? =
        if (raw.isBlank()) EveryWeek else WeekRanges.weeksOrNull(raw)

    /**
     * 节次区间有没有重叠。
     *
     * 用"闭区间相交"判：`aStart <= bEnd && bStart <= aEnd`。
     * 脏数据（起始节次 ≤ 0 或节数为 0）一律不算冲突，免得一条坏数据把整张表点红。
     */
    private fun periodsOverlap(aStart: Int, aCount: Int, bStart: Int, bCount: Int): Boolean {
        if (aStart <= 0 || bStart <= 0 || aCount <= 0 || bCount <= 0) return false
        val aEnd = aStart + aCount - 1
        val bEnd = bStart + bCount - 1
        return aStart <= bEnd && bStart <= aEnd
    }

    private fun kindsOf(draftTeacher: String, draftRoom: String, other: Course): Set<ConflictKind> = buildSet {
        add(ConflictKind.Time)
        if (sameSpot(draftRoom, other.room)) add(ConflictKind.Room)
        if (sameSpot(draftTeacher, other.teacher)) add(ConflictKind.Teacher)
    }

    /**
     * 两串文字是不是"同一个地方 / 同一个人"。
     *
     * 先**去掉所有空白再比**：教务系统给的 `"教1-106(揭)"` 和用户手打的 `"教 1-106(揭)"`
     * 是同一间教室，不去空白就会漏报。两边只要有一边是空的（选填没填）就**不算同一处** ——
     * "都没填"是两个未知，不是"同一个地方"（否则每两门没填教室的课都会报教室冲突）。
     */
    private fun sameSpot(a: String, b: String): Boolean {
        val x = normalizeSpot(a)
        val y = normalizeSpot(b)
        return x.isNotEmpty() && x == y
    }

    private fun normalizeSpot(value: String): String {
        val text = value.filterNot { it.isWhitespace() }.lowercase()
        // 「地点待定」是保存时给空地点填的占位符，不是一间真的教室
        return if (text == CourseRepository.ROOM_PLACEHOLDER) "" else text
    }
}
