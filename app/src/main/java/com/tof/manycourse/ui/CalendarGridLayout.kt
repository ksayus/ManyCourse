package com.tof.manycourse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.SchoolWeek
import com.tof.manycourse.data.WeekdayLabels
import com.tof.manycourse.data.coursesOfDate
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.components.glassSurface
import com.tof.manycourse.ui.theme.LocalGlassTokens
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/** 时间轴（左侧）宽度：要放下 `第10节` 与 `8:00~8:45` 两行 */
private val AxisWidth = 52.dp

/** 一节一行的高度 */
private val RowHeight = 56.dp

/** 星期表头高度（左侧时间轴要空出同高的占位才对得齐） */
private val HeaderHeight = 44.dp

/**
 * 网格最多画多少行。
 *
 * 正常数据是 1~10 节；留出余量是为了**越界的脏数据**（解析出 `第 20 节`）
 * 也能落在网格里而不是被悄悄丢掉（见 [buildDaySlots] 的夹取规则）。
 */
private const val MaxGridRows = 16

/** 在网格上左右滑动换周的门槛：滑动幅度不到这个值就当误触（避免翻页时蹭到就换周） */
private val SwipeWeekThreshold = 56.dp

/** 卡片两种状态的含义，写在滑块下面一行小字里 */
private const val CardStateLegend = "亮的 = 这一周会上，灰的 = 课表里有但不在这周"

/** 非本周的卡片底色（灰调）：`亮的=这一周真的会上`、`灰的=整学期课表里有但不在这周` */
private val DimCardTint = Color(0x0F0F172A)
private val DimCardFooterTint = Color(0x140F172A)

/**
 * 日历页的**「周视图」日程网格**：以时间轴为骨架、以二维网格为容器、以卡片为课程载体。
 *
 * ```
 *   第3周   9月14日 ~ 20日              ← 周次滑块（拖动换周，对应卡片当场亮起来）
 *   ●━━━━━━━━━━━━━━━━━━━━━━━━━
 *  ┌───────────────────────────────────────────────────────────┐
 *  │ 9月 │ 周一 14 │ 周二 15 │ 周三 16 │ 周四 17 │ …             │  ← 今天那格是实心蓝胶囊
 *  │─────┼─────────┼─────────┼─────────┼─────────┼──────────────│
 *  │第1节│  ▍高等数学（亮）        ▍军事理论（灰）               │
 *  │8:00~│   A-101                  T301                         │
 *  │8:45 │                                                      │
 *  │  …  │                                                      │
 *  └───────────────────────────────────────────────────────────┘
 * ```
 *
 * ## 三层结构，各管一件事
 *  - **时间轴为骨架**：左轴是「第 N 节 + 起止时刻」（横线是它的刻度）；
 *    卡片的纵向位置直接由节次决定，所以卡片上**不再写时间**（位置就是时间）；
 *  - **二维网格为容器**：一列一天（七列等宽铺满整屏，不横滚 —— 周视图要能一眼看全周）、
 *    一行一节，`第几节 × 星期几` 唯一确定一个格子；
 *  - **卡片为课程载体**：课程色块 + 课名 + 底部地点带，颜色走
 *    `CourseRepository.colorOfName(课名)`，与课表页/月历列表/圆点同色。
 *
 * ## 亮的与灰的：换周只改颜色，不动布局
 *
 * **画布**是本地整学期课表（`CourseRepository.coursesOn(星期几)`）——与"第几周"无关，
 * 所以换周时课程区**一个节点都不动**：位置、张数、跨几节、网格高度全都不变，
 * 变的只有每张卡片"亮不亮"这一个标记。
 *
 * | | 判定 | 长什么样 |
 * |---|---|---|
 * | **亮**（vivid） | 这一周真的会上（[coursesOfDate]，按「课名 + 开始节次」对齐到画布） | 课程色块、深色字 |
 * | **灰**（dim） | 画布上其余那些（课表里有、但这一周不上） | 灰底、淡字 |
 *
 * 按「课名 + 开始节次」对齐两份数据（而不是只看节次）：这周这节被临时换成别的课（军训/实习替课）时，
 * 画布那张灰着留在原位、真实那张亮着排在同一个格子里，比"把画布那张点亮"诚实。
 * 权威数据里有、画布上没有的课会补一张亮卡，绝不因为"画布上没有"就把真实课丢掉。
 *
 * 这一周没有权威数据（正在拉 / 不支持按周查 / 登录过期）时**整块灰着**，
 * 而不是换一张空白网格 —— 位置留着，"这一周到底上不上"由下方来源提示条讲清。
 *
 * ★ 灰卡**只影响观感，不影响任何数字**：汇总条上的「共 N 门课程」仍旧只数 [coursesOfDate]，
 * 与月历视图、课表页逐字一致（真机测试 `ScheduleCalendarParityTest` 对的就是这个数）。
 *
 * ## 换周的两条路
 *
 *  - **拖动刻度条**（[WeekSliderRow]）：跟手，落点即那一周；
 *  - **在网格上左右滑动**：往左滑到下一周、往右滑到上一周（门槛 56dp）。
 *    网格里没有横向滚动，所以横滑手势归换周用；纵向滑动仍然归页面滚动，两者不打架。
 *
 * 两条路都只改 `selected`（并保留"选中了星期几"），画布不动 → 卡片就地亮/灰切换。
 *
 * ## 数据缺失时
 *
 * 整周连画布都空（课表还没同步进来）时，网格位置显示「该周暂无课表数据」；
 * 其余情况照常画网格、灰着显示，具体原因由页面下方的来源提示条说明。
 *
 * @param weekStart 这一周的周一
 * @param selected 当前选中的日期（点日期表头切换；汇总条跟着变）
 * @param today 今天；用来给那一列加底色、给表头画实心胶囊、标出"现在上到第几节"
 * @param weeks 教学周列表（周次滑块的刻度）；为空 = 本校给不出按周数据，滑块整行不显示
 * @param onSelect 选中某一天
 * @param onWeekSelected 滑块拖到某一周（调用方负责换算成"同一星期几"的新日期）
 * @param onAddCourse 点「+ 添加」（参数 = 预选的星期几）
 * @param onCourseClick 点课程卡片 → 由 Fragment 打开课程详情浮层。
 *   参数是**那一天的全部课**（按节次排好）：详情卡列的是"那天有什么课"，不是"这一格是什么课"
 */
@Composable
internal fun CalendarGridLayout(
    weekStart: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    weeks: List<SchoolWeek>,
    onSelect: (LocalDate) -> Unit,
    onWeekSelected: (SchoolWeek) -> Unit,
    onAddCourse: (Int) -> Unit,
    onCourseClick: (LocalDate, List<CourseEntry>) -> Unit,
) {
    val days = remember(weekStart) { (0L..6L).map(weekStart::plusDays) }
    // ★ 画布 = 整学期课表（`CourseRepository.coursesOn`）：**与"第几周"无关**，
    //   所以换周时卡片的位置/张数全都不动，网格高度也不变 —— 不会"重新渲染"。
    //   每天只需再取一次权威数据（coursesOfDate，课表页/月历视图共用的同一个取值函数），
    //   用来决定画布上哪些卡片**亮**：亮 = 这一周真的会上，灰 = 课表里有但这一周不上。
    val canvasOfDay = days.map { CourseRepository.coursesOn(it) }
    val realOfDay = days.map { coursesOfDate(it) }
    val cardsOfDay = days.mapIndexed { index, _ -> buildGridCards(realOfDay[index], canvasOfDay[index]) }
    // 详情浮层要列的是"那一天有什么课"（按节次）：有权威数据就用它（只有这一周真的会上的），
    // 没有（或那天这周真没课）就退回画布 —— 与网格里看到的东西一致，不自说自话
    val dayCourses = days.mapIndexed { index, _ ->
        realOfDay[index]?.takeIf { it.isNotEmpty() }
            ?: canvasOfDay[index].map { CourseEntry.Local(it) }
    }
    // 整周连画布都空（新课表还没同步进来）时才退化成提示；只要有画布就照常画网格
    val hasAnything = cardsOfDay.any { it.isNotEmpty() }
    // 行数只由画布决定（至少 10 节）：换周不会让网格忽高忽低
    val rowCount = cardsOfDay.flatten()
        .maxOfOrNull { it.entry.startPeriod + it.entry.periodCount - 1 }
        ?.coerceIn(CourseRepository.MAX_PERIOD, MaxGridRows)
        ?: CourseRepository.MAX_PERIOD

    // 选中日期当天的课：汇总条上的「共 N 门课程」只数**真实**的（不含灰卡）。
    // days 是周一到周日，所以下标 = 星期几 - 1
    val selectedEntries = realOfDay.getOrNull(selected.dayOfWeek.value - 1)
    val currentPeriod = currentPeriodOf(today, days, rowCount)
    val weekIndex = remember(weekStart, weeks) { weekIndexFor(weekStart, weeks) }
    val canSwitchWeek = weekIndex != null && weeks.size >= 2
    // 左右滑动换周：整块网格都能滑（卡片只吃点击、不吃拖动，两者不打架）。
    // 门槛 56dp：滑动幅度不够就当成误触，不动周次
    val swipeThreshold = with(LocalDensity.current) { SwipeWeekThreshold.toPx() }
    val onSwipeStep: (Int) -> Unit = { delta ->
        val index = weekIndex
        if (index != null) {
            weeks.getOrNull(index + delta)?.let(onWeekSelected)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        WeekSliderRow(
            weekStart = weekStart,
            weeks = weeks,
            currentIndex = weekIndex,
            onWeekSelected = onWeekSelected,
        )

        // 亮的/灰的是什么意思、还能怎么换周：一行小字说清（不确定就别让用户猜）
        Text(
            text = if (canSwitchWeek) {
                "$CardStateLegend · 左右滑动可换周"
            } else {
                CardStateLegend
            },
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(Modifier.height(8.dp))

        GlassCard(
            cornerRadius = 16,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(weekIndex, weeks.size) {
                    // 累积位移放在协程里（不是 Compose 状态）：拖动过程零重组
                    var dragTotal = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragCancel = { dragTotal = 0f },
                        onDragEnd = {
                            when {
                                dragTotal <= -swipeThreshold -> onSwipeStep(1)
                                dragTotal >= swipeThreshold -> onSwipeStep(-1)
                            }
                            dragTotal = 0f
                        },
                    ) { _, amount -> dragTotal += amount }
                },
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                DayHeaderRow(
                    monthLabel = "${weekStart.monthValue}月",
                    days = days,
                    selected = selected,
                    today = today,
                    onSelect = onSelect,
                )

                // 表头与网格之间的分隔线（参考图里那条横线）：用强调色，和网格自身的细线区分开
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 6.dp)
                        .height(1.dp)
                        .background(LocalGlassTokens.current.accent.copy(alpha = 0.35f))
                )

                if (hasAnything) {
                    GridBody(
                        days = days,
                        cardsOfDay = cardsOfDay,
                        rowCount = rowCount,
                        today = today,
                        selected = selected,
                        currentPeriod = currentPeriod,
                        onSelect = onSelect,
                        // 点任意一张卡 → 打开**那一天**的课表（按节次排），不是这一格
                        onOpenDayDetail = { index -> onCourseClick(days[index], dayCourses[index]) },
                    )
                } else {
                    EmptyWeekHint()
                }

                Spacer(Modifier.height(12.dp))

                DaySummaryBar(
                    date = selected,
                    courseCount = selectedEntries?.size ?: 0,
                    onAddCourse = onAddCourse,
                )
            }
        }
    }
}

/**
 * 周次滑块：`第3周 · 9月14日 ~ 20日` + 可拖动的刻度条。
 *
 * 刻度的每一格 = 一个**教学周**（[SchoolWeek]，服务端给的，不是客户端猜的开学日期）。
 * 拖动过程中就按落点换周（不是松手才换），这样"对应的卡片亮起来"是跟手的。
 *
 * 教学周只有一个（或一个都没有）时不画滑块：一格刻度既拖不动、也没有第二个选择，
 * 与其放一个死控件，不如只留文字。
 */
@Composable
private fun WeekSliderRow(
    weekStart: LocalDate,
    weeks: List<SchoolWeek>,
    currentIndex: Int?,
    onWeekSelected: (SchoolWeek) -> Unit,
) {
    val tokens = LocalGlassTokens.current
    val currentWeek = currentIndex?.let { weeks.getOrNull(it) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = currentWeek?.let { "第${it.index}周" } ?: "本周",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = weekRangeLabel(weekStart),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        if (weeks.size >= 2) {
            Slider(
                value = (currentIndex ?: 0).toFloat(),
                onValueChange = { value ->
                    weeks.getOrNull(value.roundToInt())?.let(onWeekSelected)
                },
                valueRange = 0f..(weeks.size - 1).toFloat(),
                // 一档 = 一周，拖到位就是整周（中间不停顿）
                steps = (weeks.size - 2).coerceAtLeast(0),
                colors = SliderDefaults.colors(
                    thumbColor = tokens.accent,
                    activeTrackColor = tokens.accent,
                ),
            )
        }
    }
}

/**
 * 日期表头：最左边是月份（占时间轴那一格的宽度），右边七格是「周几 + 日期」。
 *
 * 今天那格画成**实心胶囊**（参考图的蓝色胶囊）：一眼就能找到今天，比一条描边清楚；
 * 选中的那天用淡色底，两者可以同时成立（选中今天）。
 */
@Composable
private fun DayHeaderRow(
    monthLabel: String,
    days: List<LocalDate>,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Row(Modifier.fillMaxWidth().height(HeaderHeight)) {
        Box(
            Modifier
                .width(AxisWidth)
                .fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = monthLabel,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        days.forEach { date ->
            val isToday = date == today
            val isSelected = date == selected
            Column(
                Modifier
                    .weight(1f)
                    .height(HeaderHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            isToday -> tokens.accent
                            isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else -> Color.Transparent
                        }
                    )
                    .clickable { onSelect(date) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = WeekdayLabels.getOrElse(date.dayOfWeek.value - 1) { "" },
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = when {
                        isToday -> Color.White
                        isSelected -> tokens.accent
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
                Text(
                    text = date.dayOfMonth.toString(),
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        isToday -> Color.White
                        isSelected -> tokens.accent
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 左侧时间轴（骨架）：每一节一行，`第 N 节` + `起~止`。
 *
 * 它**不参与任何滚动** —— 与右侧网格逐行对齐才有"刻度"的意义。
 * 结束时刻是按 [CourseRepository.PERIOD_MINUTES] 从开始时刻推算的（课表只给了开始时刻），
 * 口径写在那个常量上。
 */
@Composable
private fun PeriodAxis(rowCount: Int, currentPeriod: Int?) {
    val tokens = LocalGlassTokens.current
    Column(Modifier.width(AxisWidth)) {
        for (period in 1..rowCount) {
            Column(
                Modifier
                    .height(RowHeight)
                    .fillMaxWidth()
                    .padding(end = 6.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "第${period}节",
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = if (period == currentPeriod) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (period == currentPeriod) {
                        tokens.accent
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                )
                Text(
                    text = CourseRepository.periodRangeLabel(period),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 二维网格本体：七列（等宽） × [rowCount] 行。
 *
 * 网格线用 `drawBehind` 一次画完（静态、零逐帧成本）：横线 = 时间轴刻度，竖线 = 天与天的分界。
 * 今天那一列铺一层极淡的主色；"现在上到第几节"那一行再铺一层强调色 + 顶部一条强调色线。
 */
@Composable
private fun GridBody(
    days: List<LocalDate>,
    cardsOfDay: List<List<GridCard>>,
    rowCount: Int,
    today: LocalDate,
    selected: LocalDate,
    currentPeriod: Int?,
    onSelect: (LocalDate) -> Unit,
    /** 点某一天里的任意一张卡：参数是那一列在 [days] 里的下标 */
    onOpenDayDetail: (Int) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val todayTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
    val accent = LocalGlassTokens.current.accent
    val nowTint = accent.copy(alpha = 0.07f)

    Row(Modifier.fillMaxWidth().height(RowHeight * rowCount)) {
        PeriodAxis(rowCount = rowCount, currentPeriod = currentPeriod)

        Box(
            Modifier
                .weight(1f)
                .height(RowHeight * rowCount)
                .drawBehind {
                    val lineWidth = 1.dp.toPx()
                    val lineColor = outline.copy(alpha = 0.7f)
                    val columnWidth = size.width / 7f
                    for (row in 1 until rowCount) {
                        val y = size.height * row / rowCount
                        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), lineWidth)
                    }
                    for (column in 1 until 7) {
                        val x = column * columnWidth
                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), lineWidth)
                    }
                },
        ) {
            // ① "现在"那一行：底色 + 顶部一条线（时间轴上的"此刻"）
            currentPeriod?.let { period ->
                val top = RowHeight * (period - 1)
                Box(
                    Modifier
                        .offset(y = top)
                        .fillMaxWidth()
                        .height(RowHeight)
                        .background(nowTint)
                )
                Box(
                    Modifier
                        .offset(y = top)
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .background(accent)
                )
            }

            // ② 七天七列
            Row(Modifier.fillMaxSize()) {
                days.forEachIndexed { index, date ->
                    DayColumn(
                        cards = cardsOfDay.getOrElse(index) { emptyList() },
                        rowCount = rowCount,
                        isToday = date == today,
                        isSelected = date == selected,
                        todayTint = todayTint,
                        onSelect = { onSelect(date) },
                        onOpenDetail = { onOpenDayDetail(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 网格里的一天（一列）：按 [buildDaySlots] 切出的"空行 / 卡片块"自上而下铺满整列 */
@Composable
private fun DayColumn(
    cards: List<GridCard>,
    rowCount: Int,
    isToday: Boolean,
    isSelected: Boolean,
    todayTint: Color,
    onSelect: () -> Unit,
    /** 点这一列里的任意一张卡：打开这一天的课表（那一天有哪些课，由调用方给） */
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots = remember(cards, rowCount) { buildDaySlots(cards, rowCount) }
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier
            .height(RowHeight * rowCount)
            .background(
                when {
                    isToday -> todayTint
                    isSelected -> Color.White.copy(alpha = 0.18f)
                    else -> Color.Transparent
                }
            )
    ) {
        slots.forEach { slot ->
            when (slot) {
                // 空行也能点：点一下就把这一天选中（表头胶囊 + 汇总条跟着变）。
                // 反馈用"无涟漪 + 只改选中态"，与玻璃卡片的按下反馈保持同一种克制
                is DaySlot.Gap -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(RowHeight * slot.periods)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onSelect,
                        )
                )

                is DaySlot.Block -> Column(
                    Modifier
                        .fillMaxWidth()
                        .height(RowHeight * slot.periods)
                        .padding(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 同一格里多门课（不同周次共用同一时间，或这一周真的撞课）→ **上下均分**。
                    // 为什么不并排：格子总共才 40dp 宽，并排后每张只剩 16dp 左右，
                    // 连课名都放不下（文字会挤成一列竖排）；上下排则每张都是整列宽、看得清。
                    // 而且"哪几张在格子里"与周次无关 —— 换周仍然只是颜色变。
                    slot.cards.forEach { card ->
                        GridCourseCard(
                            card = card,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            // 点这一格里的任意一张 → 打开**这一天的课表**（按节次排）
                            onClick = onOpenDetail,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 网格里的课程卡片：课程色块 + 居中的课名 + 底部地点带（参考图的卡片就是这个结构）。
 *
 * 时间**故意不写**：纵向位置就是时间轴上的位置，再写一遍「第3-4节」是噪音 ——
 * 要知道确切时间，点开课程详情浮层。
 *
 * @param card [GridCard.dim] = true 时画成灰调（这一周不上），
 *   所以同一门课"这周亮、下周灰"是同一张卡片换个底色，一眼可比
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCourseCard(
    card: GridCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val entry = card.entry
    val accent = CourseRepository.colorOfName(entry.name)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PressedGridCardScale else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "gridCardPressScale",
    )
    val bodyTint = if (card.dim) DimCardTint else accent.copy(alpha = 0.22f)
    val footerTint = if (card.dim) DimCardFooterTint else accent.copy(alpha = 0.34f)
    val textColor = if (card.dim) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .glassSurface(shape)
            .clip(shape)
            .background(bodyTint)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Text(
            text = entry.name,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = textColor,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp, vertical = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        if (entry.room.isNotBlank()) {
            Text(
                text = entry.room,
                fontSize = 8.sp,
                lineHeight = 10.sp,
                textAlign = TextAlign.Center,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(footerTint)
                    .padding(horizontal = 3.dp, vertical = 2.dp),
            )
        }
    }
}

/** 整周都没有可用数据时的占位（原因由页面下方的来源提示条解释） */
@Composable
private fun EmptyWeekHint() {
    Column(
        Modifier
            .fillMaxWidth()
            .height(160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = AppIcons.BookOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "该周暂无课表数据",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 网格卡片按下的缩放（与 [CourseCard] 同一种反馈：缩放，不用涟漪） */
private const val PressedGridCardScale = 0.97f

/**
 * 网格里的一张卡片：这一周的课（亮）或整学期课表里"这周不上"的课（灰）。
 *
 * @param dim true = 灰卡（只做观感提示，**不参与任何计数**，见 [CalendarGridLayout] 的说明）
 */
internal data class GridCard(val entry: CourseEntry, val dim: Boolean)

/**
 * 把"这一周真的会上"（[real]）落到**整学期课表这张画布**（[semesterCourses]）上。
 *
 * ★ **卡片只由画布决定，而且顺序永远按节次排**：位置、张数、跨几节、先后顺序都与"第几周"无关，
 * 所以换周时课程区**一个节点都不动**，变的只有 `dim` 这一个标记（亮 ↔ 灰）——
 * 这就是"拖动周次不用重新渲染、直接改亮的卡片"。
 *
 * 判定规则（每条都有单测）：
 *  1. 对齐两份数据的键 = **课名 + 开始节次**（同一门课在同一时间的身份）。
 *     节次决定"画在哪一行"（画布说了算），课名 + 节次决定"亮不亮"；
 *  2. 键在 [real] 里 → **亮**；不在 → **灰**（课表里有、这一周不上）；
 *  3. [real] 里有、画布上没有的课（临时调课：这周这节换成别的课了 / 课表接口没给全）
 *     → 在画布卡片之后补一张**亮卡**，绝不因为"画布上没有"就把真实课丢掉；
 *     它与画布上那张同槽位的灰卡并排显示，正好说明"这周这节换了课"；
 *  4. [real] = null（这一周没有权威数据）→ 画布**全灰**：位置留着，
 *     宁可整块灰着，也不把"不知道上不上"说成"确定会上"。
 *
 * @return 画布卡片（按节次升序，顺次与周次无关）+ 补的亮卡
 */
internal fun buildGridCards(
    real: List<CourseEntry>?,
    semesterCourses: List<Course>,
): List<GridCard> {
    val canvas = semesterCourses.sortedBy { it.startPeriod }
    val litKeys = real?.map { it.name.trim() to it.startPeriod }?.toSet().orEmpty()
    val canvasKeys = canvas.map { it.name.trim() to it.startPeriod }.toSet()

    val onCanvas = canvas.map { course ->
        GridCard(
            entry = CourseEntry.Local(course),
            dim = (course.name.trim() to course.startPeriod) !in litKeys,
        )
    }
    val extras = real.orEmpty()
        .filter { it.name.trim() to it.startPeriod !in canvasKeys }
        .sortedBy { it.startPeriod }
        .map { GridCard(it, dim = false) }

    return onCanvas + extras
}

/**
 * 某一周在教学周列表里的下标（周次滑块的位置）。
 *
 * @return null = 这一周不在教学周列表里（放假 / 还没开学 / 本校给不出按周数据）
 */
internal fun weekIndexFor(weekStart: LocalDate, weeks: List<SchoolWeek>): Int? =
    weeks.indexOfFirst { it.contains(weekStart) }.takeIf { it >= 0 }

/** 这一周的区间文案：同月省掉重复的月份（`9月14日 ~ 20日`），跨月才两段都写 */
internal fun weekRangeLabel(weekStart: LocalDate): String {
    val end = weekStart.plusDays(6)
    return if (weekStart.monthValue == end.monthValue) {
        "${weekStart.monthValue}月${weekStart.dayOfMonth}日 ~ ${end.dayOfMonth}日"
    } else {
        "${weekStart.monthValue}月${weekStart.dayOfMonth}日 ~ ${end.monthValue}月${end.dayOfMonth}日"
    }
}

/**
 * "现在"落在第几节 —— 网格里那一行会加底色并在顶部画一条强调色线，时间轴上那一节的时刻变色。
 *
 * 粒度就是**节次**：取"已经开始上课的最后一节"。所以一天最后一节之后仍会停在第 10 节，
 * 这是刻意的 —— 课表只给了每节的开始时刻（`CourseRepository.periodTime`），
 * 没有下课时间，凭它推不出分钟级的当前位置，那就不要假装推得出来。
 *
 * @param days 网格上显示的这七天；今天不在其中（翻到别的周）就返回 null
 */
internal fun currentPeriodOf(
    today: LocalDate,
    days: List<LocalDate>,
    maxPeriod: Int,
    now: LocalTime = LocalTime.now(),
): Int? {
    if (days.none { it == today }) return null
    return (1..maxPeriod).lastOrNull { period ->
        val start = runCatching {
            LocalTime.parse(CourseRepository.periodTime(period).padStart(5, '0'))
        }.getOrNull()
        start != null && !now.isBefore(start)
    }
}

/**
 * 网格里的一块：要么是空行，要么是一块课程卡片。
 *
 * 拆成纯数据是为了能单测 —— 这一段的错法（课被吞掉、跨节次的卡片把下面的课顶掉一行）
 * 都不会崩，只会让课表看起来"少了节课"，真机上很难发现（见 `ScheduleGridSlotsTest`）。
 */
internal sealed interface DaySlot {
    /** 这一块占几行（节） */
    val periods: Int

    /** 空行（那天这个时段没课） */
    data class Gap(override val periods: Int) : DaySlot

    /**
     * 卡片块；[cards] 多于一张时**在块内上下均分**（同一时间有多门课：不同周次共用该时间，
     * 或这一周真的撞课）。上下排而不是并排，是因为一列只有 40dp 宽，并排后连课名都放不下。
     */
    data class Block(val cards: List<GridCard>, override val periods: Int) : DaySlot
}

/**
 * 把一天的卡片铺到 [maxPeriod] 行的时间轴上：**顺序铺、按节次定位、块内跨行**。
 *
 * 规则（每条都有对应的单测）：
 *  1. 从第 1 行开始；最早的课还没到，就先铺 [DaySlot.Gap]（空行）把它顶到正确的行上；
 *  2. 一张卡片占 `periodCount` 行 —— 连上两节就撑两行，这就是"卡片跨节次"；
 *  3. 与当前块**时间重叠**的卡片（起点相同、或起点落在这一块的行区间里）一律收进同一块
 *     并排显示，块的跨度取最长的那个。宁可挤一点，**不能把课藏起来**；
 *  4. 块的总行数不会越过 [maxPeriod]；节次/节数越界的脏数据先夹到合法范围再参与铺排，
 *     所以"解析出一个第 20 节"也不会让这门课从网格里消失；
 *  5. 返回值覆盖 [maxPeriod] 行（不足的部分由末尾的 [DaySlot.Gap] 补满），
 *     这样每一列的高度天然一致。
 *
 * @return 自上而下的块列表，`sumOf { it.periods } == maxPeriod`
 */
internal fun buildDaySlots(cards: List<GridCard>, maxPeriod: Int): List<DaySlot> {
    if (maxPeriod <= 0) return emptyList()

    val pending = cards
        .map { card ->
            PendingBlock(
                card = card,
                start = card.entry.startPeriod.coerceIn(1, maxPeriod),
                span = card.entry.periodCount.coerceAtLeast(1),
            )
        }
        .toMutableList()

    val slots = mutableListOf<DaySlot>()
    var period = 1

    while (period <= maxPeriod && pending.isNotEmpty()) {
        val earliest = pending.minOf { it.start }
        if (earliest > period) {
            // 这一段的空白也是信息：它表示"第 period~earliest-1 节没课"
            slots += DaySlot.Gap(earliest - period)
            period = earliest
            continue
        }

        // 以 period 为起点铺一块：把与它重叠的卡片都收进来。
        // 收进来的卡片可能更长（把这一块的终点往下推），所以要循环到稳定为止。
        val block = mutableListOf<PendingBlock>()
        var endExclusive = period + 1
        while (true) {
            val newly = pending.filter { it.start < endExclusive && it !in block }
            if (newly.isEmpty()) break
            block += newly
            endExclusive = maxOf(endExclusive, newly.maxOf { it.start + it.span })
        }

        val span = (endExclusive - period).coerceAtMost(maxPeriod - period + 1)
        slots += DaySlot.Block(block.map { it.card }, span)
        pending.removeAll(block)
        period += span
    }

    // 末尾补齐：每一列都必须正好 maxPeriod 行高，否则列与列会错位
    if (period <= maxPeriod) slots += DaySlot.Gap(maxPeriod - period + 1)
    return slots
}

/** [buildDaySlots] 的中间态：一张卡片 + 已夹取过的起点与跨度 */
private data class PendingBlock(val card: GridCard, val start: Int, val span: Int)
