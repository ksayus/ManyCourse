package com.tof.manycourse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.LoginSettings
import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.data.WeekScheduleStore
import com.tof.manycourse.data.coursesOfDate
import com.tof.manycourse.data.naturalWeekOf
import com.tof.manycourse.ui.components.CourseCard
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.theme.LocalGlassTokens
import java.time.LocalDate

private val WeekdayShort = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 日历页。**两套布局，一个开关**（「我的」页的「日历布局」，见 [UiSettings.calendarGridLayout]）：
 *
 * | 开关 | 布局 | 长什么样 |
 * |---|---|---|
 * | 关（默认） | [MonthCalendarCard] + [DayCourseList] | 月历格子（课程圆点）+ 当日课程卡片列表 |
 * | 开 | [CalendarGridLayout] | 时间轴为骨架、二维网格为容器、课程卡片为载体的**日程网格** |
 *
 * 两套布局只是**渲染方式**不同：取值仍然是同一个 [coursesOfDate]，选中的日期也是同一个
 * 状态 —— 所以同一个日期在两种布局下显示的门数与内容必然一致，切换布局不会"换一份课表"。
 *
 * ## 数据来源
 *
 * 全权交给 [coursesOfDate]（**和课表页是同一个函数**）：
 *
 * | 情况 | 显示什么 |
 * |---|---|
 * | 这一天所在教学周有"按周"数据（广软） | 教务系统给的课（服务端已经筛过"这一周真的会上"）+ 用户自己加的课 |
 * | 没有按周数据，但这一天在**本周** | 本地课表（整学期课按星期几循环）—— 本周兜底不会骗人 |
 * | 没有按周数据，且不在本周（金城学院翻到别的月份） | **空**：宁可不显示，也不把本周的课画满整个学期 |
 *
 * 翻月 / 翻周时会按需补拉覆盖到的教学周（[WeekScheduleStore.ensureRange]），拉过的缓存在内存里。
 *
 * 两页共用同一个取值函数是**刻意的**：各自实现一遍的话，迟早出现
 * "课表页有课、日历页没课"这种对不上的情况。
 *
 * @param onAddCourse 点「+ 添加」（参数 = 预选的星期几）
 * @param onCourseClick 点某张课程卡片 → 由 Fragment 打开课程详情浮层（理由同课表页）。
 *   参数是**那一天的全部课**：详情卡列的是"那天有什么课"（按节次排），不是"这一格是什么课"
 */
@Composable
fun CalendarScreen(
    hazeState: HazeState,
    onAddCourse: (Int) -> Unit,
    onCourseClick: (LocalDate, List<CourseEntry>) -> Unit,
) {
    val today = LocalDate.now()
    // 「本周」= 含今天的那一个周一到周日。本地课表只在它里面兜底（规则在 data/CourseEntries.kt）
    val currentWeek = naturalWeekOf(today)
    var month by remember { mutableStateOf(today.withDayOfMonth(1)) }
    var selected by remember { mutableStateOf(today) }

    // 布局开关：「我的」页切换，切换后本页立即重组（无需重启，也不必重进 Tab）
    val gridLayout by UiSettings.calendarGridLayout

    // 网格布局是按**周**翻页的，月历布局按**月**：切换布局时把月份对齐到选中的日期，
    // 免得从网格切回月历时停在一个几个月前的月份上
    LaunchedEffect(gridLayout) { month = selected.withDayOfMonth(1) }

    // 教学周列表到了 / 用户翻月（或翻周）时，把可见范围覆盖到的周补拉回来。
    // ensureRange 内部会跳过已拉到和正在拉的，所以这里可以放心多调。
    val weekCount = WeekScheduleStore.weeks.size
    val weekStart = naturalWeekOf(selected).start
    LaunchedEffect(month, weekStart, weekCount, gridLayout) {
        if (weekCount == 0) return@LaunchedEffect
        if (gridLayout) {
            WeekScheduleStore.ensureRange(from = weekStart, to = weekStart.plusDays(6))
        } else {
            WeekScheduleStore.ensureRange(
                from = month.minusDays(7),
                to = month.withDayOfMonth(month.lengthOfMonth()).plusDays(7),
            )
        }
    }

    // null = 这一天没有任何可用数据（没有教学周数据、又不在本周）→ 显示"暂无课表数据"；
    // 空表 = 有数据，那天确实没课。两者的取值规则全部在 coursesOfDate 里，
    // **和课表页是同一个函数** —— 两页对不上就是从这里开始的，所以只留一个实现。
    val entries = coursesOfDate(selected)
    val selectedWeek = WeekScheduleStore.weekOf(selected)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (gridLayout) {
            CalendarGridLayout(
                weekStart = weekStart,
                selected = selected,
                today = today,
                // 教学周列表 = 周次滑块的刻度（服务端给的，不在客户端猜开学日期）
                weeks = WeekScheduleStore.weeks,
                onSelect = { selected = it },
                // 拖动滑块换周：保留"选中了星期几"，于是汇总条上的日期仍停在同一列
                onWeekSelected = { week ->
                    selected = week.start.plusDays((selected.dayOfWeek.value - 1).toLong())
                },
                onAddCourse = onAddCourse,
                onCourseClick = onCourseClick,
            )

            Spacer(Modifier.height(8.dp))

            // 数据来源说明：两种布局都要有 —— 网格里空着的地方到底是"没课"还是"没数据"，
            // 只有这一行讲得清
            WeekSourceHint(
                schoolId = SessionStore.schoolId.value,
                fromWeekApi = WeekScheduleStore.coursesOn(selected) != null,
            )
        } else {
            MonthCalendarCard(
                month = month,
                selected = selected,
                selectedWeek = selectedWeek,
                today = today,
                currentWeek = currentWeek,
                courseCount = entries?.size ?: 0,
                onMonthChange = { month = it },
                onSelect = { selected = it },
                onAddCourse = onAddCourse,
            )

            Spacer(Modifier.height(8.dp))

            WeekSourceHint(
                schoolId = SessionStore.schoolId.value,
                fromWeekApi = WeekScheduleStore.coursesOn(selected) != null,
            )

            Spacer(Modifier.height(8.dp))

            DayCourseList(entries = entries, selected = selected, onCourseClick = onCourseClick)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 页头右侧的「周 / 月」分段切换。
 *
 * - **周** = 日程网格（时间轴为骨架、二维网格为容器、课程卡片为载体，见 [CalendarGridLayout]）；
 * - **月** = 月历 + 当日课程列表（默认）。
 *
 * 与设置页的「日历布局」开关是**同一个状态**（[UiSettings.calendarGridLayout]），
 * 所以页面里切、设置里切、冷启动恢复，三处永远一致；切换后当场生效，不必重进页面。
 */
@Composable
internal fun CalendarLayoutToggle() {
    val gridLayout by UiSettings.calendarGridLayout
    val tokens = LocalGlassTokens.current
    // 顺序 = 参考布局的习惯：周在前（左），月在后（右）
    val options = listOf(true to "周", false to "月")

    Row(
        Modifier
            .clip(RoundedCornerShape(9999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (isGrid, label) ->
            val selected = gridLayout == isGrid
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(9999.dp))
                    .background(if (selected) tokens.accent else Color.Transparent)
                    .clickable { UiSettings.setCalendarGridLayout(isGrid) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** 月历布局的卡片：月份 + 教学周标签 + 翻月 + 日期网格（课程圆点）+ 当日汇总条 */
@Composable
private fun MonthCalendarCard(
    month: LocalDate,
    selected: LocalDate,
    selectedWeek: SchoolWeek?,
    today: LocalDate,
    currentWeek: ClosedRange<LocalDate>,
    courseCount: Int,
    onMonthChange: (LocalDate) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onAddCourse: (Int) -> Unit,
) {
    // 月历卡片（液态玻璃）
    GlassCard(cornerRadius = 16, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // 头部：月份 + 翻页
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "${month.year}年 ${month.monthValue}月",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // 教学周标签：就是教务系统"周次课表"页里那个周次下拉框的内容
                    selectedWeek?.let { week ->
                        Text(
                            text = week.label,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row {
                    CalendarNavButton(AppIcons.ChevronLeft, "上月") {
                        onMonthChange(month.minusMonths(1))
                    }
                    Spacer(Modifier.width(8.dp))
                    CalendarNavButton(AppIcons.ChevronRight, "下月") {
                        onMonthChange(month.plusMonths(1))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 星期表头
            Row(Modifier.fillMaxWidth()) {
                WeekdayShort.forEach { w ->
                    Text(
                        text = w,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 日期网格：课程圆点按当天**所在教学周**的课计算（拿不到才回落到本地课表）
            val leadingBlanks = month.dayOfWeek.value - 1
            val daysInMonth = month.lengthOfMonth()
            val totalCells = leadingBlanks + daysInMonth
            val rows = (totalCells + 6) / 7

            for (row in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val cellIndex = row * 7 + col
                        val day = cellIndex - leadingBlanks + 1
                        if (day < 1 || day > daysInMonth) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            val date = month.withDayOfMonth(day)
                            DayCell(
                                date = date,
                                isToday = date == today,
                                isSelected = date == selected,
                                // 本地课表只给「本周」画圆点，其他周取不到教学周数据就留空
                                allowLocalFallback = date in currentWeek,
                                onClick = { onSelect(date) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(12.dp))

            // 当日汇总条（与日程网格布局共用同一个组件，文案一致）
            DaySummaryBar(date = selected, courseCount = courseCount, onAddCourse = onAddCourse)
        }
    }
}

/**
 * 当日汇总条：`X月X日 共 N 门课程 [+ 添加]`。
 *
 * 两套布局共用 —— 「共 N 门课程」这句文案在两种布局里必须一模一样：
 * 真机测试 `ScheduleCalendarParityTest` 就是拿它和课表页的「周X · N 门课程」对账的。
 */
@Composable
internal fun DaySummaryBar(
    date: LocalDate,
    courseCount: Int,
    onAddCourse: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${date.monthValue}月${date.dayOfMonth}日",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "共 $courseCount 门课程",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "+ 添加",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onAddCourse(date.dayOfWeek.value) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 月历布局下半部分的「当日课程」列表；三种空状态必须分清（见 [coursesOfDate] 的注释） */
@Composable
private fun DayCourseList(
    entries: List<CourseEntry>?,
    selected: LocalDate,
    onCourseClick: (LocalDate, List<CourseEntry>) -> Unit,
) {
    Text(
        text = "当日课程",
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(Modifier.height(12.dp))

    when {
        // 这一天没有任何可用数据 —— 宁可说"没有数据"，也不把别的周的课画出来
        entries == null -> EmptyDayCard("该日期暂无课表数据")
        entries.isEmpty() -> EmptyDayCard("当日无课")
        else -> entries.forEach { entry ->
            CourseCard(
                course = entry,
                metaText = buildString {
                    append("${selected.monthValue}月${selected.dayOfMonth}日 ")
                    append(entry.periodLabel)
                    // 教务系统的课带着教师，本地课没有就不显示
                    val teacher = (entry as? CourseEntry.Week)?.course?.teacher.orEmpty()
                    if (teacher.isNotBlank()) {
                        append(" · ")
                        append(teacher)
                    }
                },
                // 点击 → 课程详情浮层：列的是**这一天**的课（与上面这份列表同一份数据）
                onClick = { onCourseClick(selected, entries.orEmpty()) },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 空状态卡片（设计规范：图标 + 提示）。
 *
 * @param text 具体说法。**必须传下去**：「当日无课」和「该日期暂无课表数据」是两件事
 *   （前者=有数据那天没课，后者=这一天根本没有可用数据），写死一句话会把两者混成一个。
 */
@Composable
private fun EmptyDayCard(text: String = "当日无课") {
    GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = AppIcons.BookOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「这一天的课是哪来的」提示条。
 *
 * 三件事必须让用户看得见，否则会以为 App 坏了：
 *  - 学校支持按周查、但这一周还没拉回来 → 正在加载；
 *  - 拉失败了（或没登录）→ 说明现在只有本周是本地课表，**其他周是空的**；
 *  - 学校根本不支持按周查（如金城学院）→ 同样只有本周有内容，翻到别的月份会看到空日历 ——
 *    这条不写出来，用户只会觉得"日历坏了"。
 *
 * @param fromWeekApi 这一天的课是不是来自教务系统的"按周"数据（否则就是本地课表兜底）
 */
@Composable
private fun WeekSourceHint(schoolId: String?, fromWeekApi: Boolean) {
    val context = LocalContext.current
    val state = WeekScheduleStore.state.value
    val text: String
    val error: Boolean
    val action: Pair<String, () -> Unit>?

    when {
        fromWeekApi -> {
            text = "课表来源：教务系统 · 周次课表（与「课表」页同一份数据）"
            error = false
            action = null
        }
        state is WeekScheduleStore.State.Loading -> {
            text = "正在读取教学周课表…"
            error = false
            action = null
        }
        state is WeekScheduleStore.State.Expired -> {
            text = "登录已过期，日历只显示本周"
            error = true
            action = "重新登录" to { context.logoutAndBackToLogin() }
        }
        state is WeekScheduleStore.State.Failed -> {
            text = "教学周课表没拉到（${state.message}），日历只显示本周"
            error = true
            action = "重试" to {
                WeekScheduleStore.sync(
                    schoolId = schoolId ?: LoginSettings.selectedSchoolId.value,
                    account = SessionStore.account.value,
                    force = true,
                )
            }
        }
        else -> {
            // Idle = 这所学校的教务系统给不出"某一周有哪些课"，只能在本地课表里兜本周
            text = "本校教务系统不支持按周查课表，日历只显示本周"
            error = false
            action = null
        }
    }

    val tokens = LocalGlassTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = if (error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        action?.let { (label, onClick) ->
            TextButton(onClick = onClick) {
                Text(label, fontSize = 12.sp, color = tokens.accent)
            }
        }
    }
}

/** 翻页按钮（月历布局翻月 / 日程网格布局翻周共用）：32dp、8dp 圆角、玻璃底 + 描边 */
@Composable
internal fun CalendarNavButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    allowLocalFallback: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 优先用"这一天所在教学周"的课（真实日期）；本周还可以回落到本地课表，
    // 其他周取不到教学周数据就**留空**（宁可没有，也不把别的周的课画上去）
    val dots: List<Color> = WeekScheduleStore.coursesOn(date)
        ?.sortedBy { it.startPeriod }
        ?.map { CourseRepository.colorOfName(it.name) }
        ?: if (allowLocalFallback) CourseRepository.coursesOn(date).map { CourseRepository.colorOf(it) }
        else emptyList()

    Column(
        modifier = modifier
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .then(
                if (isToday && !isSelected)
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            fontSize = 15.sp,
            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                isSelected -> Color.White
                isToday -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            dots.take(4).forEach { color ->
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
    }
}
