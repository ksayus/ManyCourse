package com.tof.manycourse.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/**
 * 时间轴文字的三行样式：**开始时刻 / 第 N-M 节 / 结束时刻**。
 *
 * 为什么拆成三行（原来两行 `第1-2节` + `9:00~10:20`）：七列平分屏幕后左轴只有 50dp 出头，
 * `17:00~18:20` 这种"区间"写法要 60dp 以上 —— 真机上被省略号截成 `17:00~1…`，
 * 也就是"时间显示不全"。三行之后**每行都短**（最长是 `第11-12节`），
 * 于是三行都完整显示，轴还能比原来更窄（见 [rememberAxisMetrics]）。
 */
private val AxisLabelStyle = TextStyle(fontSize = 9.sp, lineHeight = 11.sp)

/** 系统字号被调大时的退档样式（与 [AxisLabelStyle] 同族，只差一号字） */
private val AxisLabelStyleCompact = TextStyle(fontSize = 8.sp, lineHeight = 10.sp)

/** 时间轴文字右边到网格线之间的留白（轴宽 = 最宽那行文字 + 这点留白） */
private val AxisTextPadding = 6.dp

/**
 * 轴文字允许的最大宽度：超过它就退一档（先省「第」字、再降字号）。
 *
 * 七列平分屏幕，轴每宽 1dp、每列就窄 1/7dp —— 卡片本来就窄，所以轴上只花"刚好够用"的宽度，
 * 剩下的全给卡片（这就是"卡片再宽一点"的来源）。
 */
private val AxisTextMaxWidth = 44.dp

/** 轴的硬上限：极端字号下宁可让文字省略号，也不能把七列挤没 */
private val AxisTextHardMax = 52.dp

/**
 * 轴上的三种写法，**按优先级从好看往将就排**（实测宽度不超 [AxisTextMaxWidth] 就用它）：
 *
 * | 候选 | 中间那行 / 那两行时刻 | 9sp 下约需 |
 * |---|---|---|
 * | ① 9sp + `第1-2节` | 最正式 | 46dp+ |
 * | ② 9sp + `1-2节` | 省掉「第」字，仍然一眼可读 | 34dp |
 * | ③ 8sp + `1-2节` | 系统字号调大时的退档 | 30dp |
 *
 * 三行都**必须完整显示**：所以轴宽是"实测最宽那行 + 留白"，不是写死的常数。
 */
private val AxisCandidates = listOf(
    AxisLabelStyle to true,
    AxisLabelStyle to false,
    AxisLabelStyleCompact to false,
)

/**
 * 一行（两节）的高度**不再写死**：它由"这一屏还剩多少高度"决定（见 [GridBody]）。
 *
 * 于是"整周一眼看完、不用上下滚动"和"卡片尽量大"能同时成立：
 * 屏幕高、行数少就长得高（[MaxRowHeight] 封顶），屏幕矮就压下来（[MinRowHeight] 兜底），
 * 实在压不下才让网格自己内部滚动。
 */
private val MinRowHeight = 36.dp
private val MaxRowHeight = 88.dp

/** 行高低于它时卡片进入"紧凑"排版（字号收一档、留白收紧），保证**任何字都不会被裁掉** */
private val CompactCardRowHeight = 62.dp

/**
 * **一行 = 一个"两节块"**（1-2 / 3-4 / … / 15-16），与课表的实际排课一致
 * （两节连排 80 分钟、块间休息 10~40 分钟）。块的定义在数据层
 * （`CourseRepository.blockRanges`），这里不再另写一个 2。
 */
private val PeriodsPerRow = CourseRepository.PERIODS_PER_BLOCK

/** 星期表头高度（左侧时间轴要空出同高的占位才对得齐）：够放下「周一 + 14」两行即可 */
private val HeaderHeight = 40.dp

/**
 * 网格最多画多少行（= 块数）。
 *
 * 正常数据最多 `CourseRepository.BLOCK_COUNT` 行（16 节 → 8 行）；这里取 max 只是防脏数据
 * 把行数撑爆（见 [buildDaySlots] 的夹取规则）。
 */
private val MaxGridRows = (CourseRepository.MAX_PERIOD + PeriodsPerRow - 1) / PeriodsPerRow

/** 在网格上左右滑动换周的门槛：滑动幅度不到这个值就当误触（避免翻页时蹭到就换周） */
private val SwipeWeekThreshold = 56.dp

/**
 * 卡片两种状态的含义，写在滑块下面一行小字里。
 *
 * ★ 刻意压到**一行**：这段说明占的是"整周不用滚动"的预算，两行要多吃 18dp
 * （真机实测 35.6dp → 17.8dp），折算下来是每行 2dp 的卡片高度。
 */
private const val CardStateLegend = "亮 = 本周会上 · 灰 = 本周不上 · 左右滑动换周"

/** 非本周的卡片底色（灰调）：`亮的=这一周真的会上`、`灰的=整学期课表里有但不在这周` */
private val DimCardTint = Color(0x0F0F172A)
private val DimCardFooterTint = Color(0x140F172A)

/** 同一格里叠了好几张时，后面那几张往右下缩一点露出个边（提示"这里不止一门课"） */
private val StackedCardInset = 2.5.dp

/**
 * 卡片与格子边缘的缝隙。
 *
 * 刻意只有 1dp：七列平分屏幕，**列宽是硬约束**，缝隙每多 1dp、卡片就窄 1dp ——
 * 卡片本来就窄（约 45dp），这点宽度比"缝隙留白"值钱。
 */
private val CardGap = 1.dp

/** 网格卡片内部左右留白：同样是能省则省（时间轴那一列已经吃掉近 40dp） */
private val CardInnerPadding = 2.dp

/** 玻璃卡片的上下内边距（原来是 12dp；省下来的正是网格那一行的高度） */
private val CardInnerVerticalPadding = 8.dp

/**
 * 日历页的**「周视图」日程网格**：以时间轴为骨架、以二维网格为容器、以卡片为课程载体。
 *
 * ```
 *   第3周   9月14日 ~ 20日              ← 周次滑块（拖动 / 左右滑动都能换周）
 *   ●━━━━━━━━━━━━━━━━━━━━━━━━━
 *  ┌───────────────────────────────────────────────────────────┐
 *  │ 9月 │ 周一 14 │ 周二 15 │ 周三 16 │ 周四 17 │ …             │  ← 今天那格是实心蓝胶囊
 *  │─────┼─────────┼─────────┼─────────┼─────────┼──────────────│
 *  │ 9:00│  ▍高等数学（亮）        ▍军事理论（灰）               │  ┐
 *  │第1-2│   A-101                  T301                         │  │ 一行 = 两节
 *  │10:20│                                                      │  ┘
 *  │10:40│ …                                                     │
 *  └───────────────────────────────────────────────────────────┘
 * ```
 *
 * ## 三层结构，各管一件事
 *  - **时间轴为骨架**：左轴每一行**三行小字** = 开始时刻 / 第 N-M 节 / 结束时刻
 *    （如 `9:00` / `第1-2节` / `10:20`；横线是它的刻度）。拆三行是因为
 *    `9:00~10:20` 这种区间写法在 40dp 出头的窄轴上会被省略号截断（"时间显示不全"）；
 *    卡片的纵向位置直接由节次决定，所以卡片上**不再写时间**（位置就是时间）；
 *  - **二维网格为容器**：一列一天（七列等宽铺满整屏，不横滚 —— 周视图要能一眼看全周）、
 *    **一行两节**（1-2 / 3-4 / …），`哪两节 × 星期几` 唯一确定一个格子；
 *  - **卡片为课程载体**：课程色块 + 课名 + 底部地点带，颜色走
 *    `CourseRepository.colorOfName(课名)`，与课表页/月历列表/圆点同色。
 *
 * ## 整周一眼看完：**不滚动**，行高跟着屏幕走
 *
 * 调用方给 `weight(1f)`（高度有界），于是：
 *  - **行数**只算"整个学期真的排过课"的行（[gridRowCount]）：排到下午就是 5 行、有晚自习才 8 行；
 *  - **行高** = 网格剩多少高 ÷ 行数（[GridBody] 量出来现算，36~88dp）。
 *
 * 两者相乘正好铺满那条高度，所以**看完这一周的七天不用上下滑**，
 * 卡片又能长到该长的大小（5 行时约 47×86dp、8 行时约 47×49dp；
 * 早先固定 8 行 × 68dp 时是 39×66dp，而且一屏放不下、必须滚）。
 * 只有"压到最小行高仍放不下"（小屏 / 超大字号）才让网格**自己内部滚**，页头与汇总条不动。
 *
 * ## 同一格里的多门课：叠着放，该显示的那张压在最前面
 *
 * 一格（同一天 + 同两节）常有多门课 —— 因为"画布"是整学期课表，不同周次会共用同一个时间。
 * 它们**重叠着放**：这一周真的会上的那张**最前、发亮**，其余在后面灰着（往右下缩一点露个边）。
 * 于是换周时格子里的内容就地"换牌"，位置一张都不动。
 *
 * ## 点亮：点哪门课，哪一行、哪一列一起亮
 *
 * 点卡片 → 该课所在的**列（星期几）高亮**、**行（第几节）高亮**（时间轴那一格也变色），
 * 卡片本身加一圈强调色描边，同时弹出那一天的课程详情 —— 一眼看清"这门课落在哪一行哪一列"。
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
 *    网格里没有横向滚动，所以横滑手势归换周用；纵向滑动归网格自己（只有放不下时才内部滚），
 *    两者不打架。
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
 * @param modifier 调用方给 `weight(1f)`：**高度有界**是这个组件的前提 ——
 *   它按"还剩多少高 ÷ 行数"算行高，从而做到整周一眼看全、不用滚动
 */
@Composable
internal fun CalendarGridLayout(
    weekStart: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    weeks: List<SchoolWeek>,
    onSelect: (LocalDate) -> Unit,
    onAddCourse: (Int) -> Unit,
    onCourseClick: (LocalDate, List<CourseEntry>) -> Unit,
    modifier: Modifier = Modifier,
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
    // 行数（每行两节）：只画"整个学期真的排过课"的那几行 —— 见 gridRowCount。
    // 画布与周次无关，所以换周时行数不变、网格高度不变（不会"重新渲染"）
    val rowCount = gridRowCount(cardsOfDay)
    // 时间轴度量（轴宽 + 三行文字的字号/写法）：按实测文字宽度现算，见 rememberAxisMetrics
    val axis = rememberAxisMetrics()

    // 选中日期当天的课：汇总条上的「共 N 门课程」只数**真实**的（不含灰卡）。
    // days 是周一到周日，所以下标 = 星期几 - 1
    val selectedEntries = realOfDay.getOrNull(selected.dayOfWeek.value - 1)
    val currentPeriod = currentPeriodOf(today, days, rowCount * PeriodsPerRow)
    val weekIndex = remember(weekStart, weeks) { weekIndexFor(weekStart, weeks) }
    // 点过的格子（行 = 第几行两节，列 = 星期几）：点的行会连同时间轴一起点亮
    var tappedRow by remember(weekStart) { mutableStateOf<Int?>(null) }
    // 左右滑动换周：门槛 56dp，滑动幅度不够就当误触，不动周次
    val swipeThreshold = with(LocalDensity.current) { SwipeWeekThreshold.toPx() }
    // 换周一律走 onSelect（保留"选中了星期几"）：教学周列表在就按列表翻，
    // 不在（放假 / 还没同步 / 本校不支持按周查）就按自然周翻 —— 刚进页面也滑得动
    val onSwipeStep: (Int) -> Unit = { delta ->
        val target = weekStepTarget(weekStart, weeks, delta)
        onSelect(target.plusDays((selected.dayOfWeek.value - 1).toLong()))
    }

    Column(
        modifier
            .fillMaxSize()
            // 滑动换周挂在整块"周视图"上（滑块 + 说明 + 网格都能滑），不必瞄准卡片；
            // 滑块自己是横向拖动控件，手指落在滑块上时由滑块优先接管
            .pointerInput(weekStart, weeks, selected, today) {
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
        WeekSliderRow(
            weekStart = weekStart,
            weeks = weeks,
            currentIndex = weekIndex,
            // 滑块拖到某一周：同样保留"选中了星期几"
            onWeekSelected = { week ->
                onSelect(week.start.plusDays((selected.dayOfWeek.value - 1).toLong()))
            },
        )

        // 亮的/灰的是什么意思、还能怎么换周：一行小字说清（不确定就别让用户猜）
        Text(
            text = CardStateLegend,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(Modifier.height(6.dp))

        // ★ 卡片**吃掉这一屏剩下的全部高度**（weight(1f)）：网格因此永远"一眼看完"，不用上下滚动；
        //   行高由卡片内部按"还剩多少高 ÷ 行数"现算（见 GridBody）
        GlassCard(
            cornerRadius = 16,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = CardInnerPadding, vertical = CardInnerVerticalPadding)
            ) {
                DayHeaderRow(
                    monthLabel = "${weekStart.monthValue}月",
                    days = days,
                    selected = selected,
                    today = today,
                    axisWidth = axis.width,
                    onSelect = onSelect,
                )

                // 表头与网格之间的分隔线（参考图里那条横线）：用强调色，和网格自身的细线区分开
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 4.dp)
                        .height(1.dp)
                        .background(LocalGlassTokens.current.accent.copy(alpha = 0.35f))
                )

                if (hasAnything) {
                    GridBody(
                        days = days,
                        cardsOfDay = cardsOfDay,
                        rowCount = rowCount,
                        axis = axis,
                        today = today,
                        selected = selected,
                        currentPeriod = currentPeriod,
                        tappedRow = tappedRow,
                        onSelect = onSelect,
                        onTappedRow = { tappedRow = it },
                        // 点任意一张卡 → 点亮那一行 / 那一列，并打开**那一天**的课程详情
                        onOpenDayDetail = { index, row ->
                            tappedRow = row
                            onSelect(days[index])
                            onCourseClick(days[index], dayCourses[index])
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    EmptyWeekHint(Modifier.weight(1f))
                }

                Spacer(Modifier.height(8.dp))

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
 *
 * @param axisWidth 左轴占的宽度（与网格的时间轴**同一份实测值**，否则表头和网格会错位）
 */
@Composable
private fun DayHeaderRow(
    monthLabel: String,
    days: List<LocalDate>,
    selected: LocalDate,
    today: LocalDate,
    axisWidth: Dp,
    onSelect: (LocalDate) -> Unit,
) {
    val tokens = LocalGlassTokens.current
    Row(Modifier.fillMaxWidth().height(HeaderHeight)) {
        Box(
            Modifier
                .width(axisWidth)
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
 * 左侧时间轴（骨架）：**每两节一行**，三行文字 —— **开始时刻 / 第 N-M 节 / 结束时刻**。
 *
 * ```
 *  9:00   ← 这一段从几点开始
 * 第1-2节 ← 是哪两节（中间）
 * 10:20   ← 这一段到几点结束
 * ```
 *
 * ## 为什么是三行
 *
 * 原来是两行（`第1-2节` + `9:00~10:20`）。七列平分屏幕后左轴只有 50dp 出头，
 * 而 `17:00~18:20` 这种"区间"写法在 9sp 下要 60dp 上下 —— 真机上被截成 `17:00~1…`，
 * 也就是"时间显示不全"。拆成三行以后**每行都很短**（最长的是 `第11-12节`），
 * 三行都完整显示，而且轴还能比原来更窄（省下的宽度全给卡片）。
 * 起止时刻的口径在数据层：[CourseRepository.blockStartTime] / [CourseRepository.blockEndTime]。
 *
 * 它**不参与任何滚动** —— 与右侧网格逐行对齐才有"刻度"的意义；
 * 行高由 [GridBody] 现算（整屏自适应），所以三行用 `SpaceBetween` 摊在整行上：
 * 开始时刻贴着这一行的上沿、结束时刻贴着下沿，读起来就是一把尺子。
 *
 * 被点亮的那一行（点过课程卡片 / "现在"正在上的那一段）走强调色 + 加粗，
 * 于是"这门课落在哪一段"不用数格子。
 *
 * @param metrics 轴宽 + 字号 + 中间那行的写法（[rememberAxisMetrics] 实测得到）
 */
@Composable
private fun PeriodAxis(
    rowCount: Int,
    rowHeight: Dp,
    metrics: AxisMetrics,
    highlightRow: Int?,
) {
    val tokens = LocalGlassTokens.current
    Column(Modifier.width(metrics.width)) {
        for (row in 0 until rowCount) {
            val firstPeriod = row * PeriodsPerRow + 1
            val highlighted = row == highlightRow
            Column(
                Modifier
                    .height(rowHeight)
                    .fillMaxWidth()
                    .padding(end = 4.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                AxisLabel(
                    text = CourseRepository.blockStartTime(row),
                    metrics = metrics,
                    highlighted = highlighted,
                )
                AxisLabel(
                    text = axisPeriodLabel(firstPeriod, metrics.withPrefix),
                    metrics = metrics,
                    highlighted = highlighted,
                    emphasized = true,
                )
                AxisLabel(
                    text = CourseRepository.blockEndTime(row),
                    metrics = metrics,
                    highlighted = highlighted,
                )
            }
        }
    }
}

/** 时间轴上的一行小字：三行同一个样式（[AxisMetrics] 里那份），亮起时整格变强调色 */
@Composable
private fun AxisLabel(
    text: String,
    metrics: AxisMetrics,
    highlighted: Boolean,
    emphasized: Boolean = false,
) {
    val tokens = LocalGlassTokens.current
    Text(
        text = text,
        fontSize = metrics.style.fontSize,
        lineHeight = metrics.style.lineHeight,
        fontWeight = if (emphasized || highlighted) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            highlighted -> tokens.accent
            emphasized -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

/**
 * 二维网格本体：七列（等宽） × [rowCount] 行（每行两节）。
 *
 * 网格线用 `drawBehind` 一次画完（静态、零逐帧成本）：横线 = 时间轴刻度，竖线 = 天与天的分界。
 * 几层底色，从下到上：今天那一列 → "现在"正在上的那一行 → **点过的那一行**（最亮），
 * 它们都是静止绘制，不动画。
 *
 * ## 行高 = "这一屏还剩多少高 ÷ 行数"
 *
 * 这是"整周一眼看完、不用上下滚动"与"卡片尽量大"能同时成立的那一处：
 * 调用方给的高度是**屏幕上真正剩下的那一条**（[BoxWithConstraints] 量出来的 `maxHeight`），
 * 行数又是"真的排过课的行数"（[gridRowCount]），所以
 *
 *  - 课表只排到下午 → 5 行 → 每行能分到 80dp 上下，卡片是**又大又方正**的一张；
 *  - 课表排到晚自习 → 8 行 → 每行 50dp 上下，照样全在一屏里；
 *  - 屏幕太小（[MinRowHeight] 都放不下）→ 网格**自己内部滚动**，七列仍然一次看全，
 *    页头、滑块、汇总条不动。
 *
 * @param axis 时间轴度量；[AxisMetrics.width] 同时决定表头"月份格"与网格左轴的宽度
 * @param modifier 由调用方 `weight(1f)` 给出**有界**高度（所以这里能读 `maxHeight`）
 */
@Composable
private fun GridBody(
    days: List<LocalDate>,
    cardsOfDay: List<List<GridCard>>,
    rowCount: Int,
    axis: AxisMetrics,
    today: LocalDate,
    selected: LocalDate,
    currentPeriod: Int?,
    tappedRow: Int?,
    onSelect: (LocalDate) -> Unit,
    onTappedRow: (Int) -> Unit,
    /** 点某一天里的某一行卡片：参数是那一列在 [days] 里的下标、以及行号 */
    onOpenDayDetail: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    val todayTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
    val accent = LocalGlassTokens.current.accent
    val nowTint = accent.copy(alpha = 0.07f)
    val tappedTint = accent.copy(alpha = 0.12f)
    val nowRow = currentPeriod?.let { blockOf(it) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 行高：整屏剩下的高度平分给每一行（上下限见 MinRowHeight / MaxRowHeight）。
        // maxHeight 有界是调用方 weight(1f) 保证的；真无界（理论上不会）时退回下限，不让它崩
        val rowHeight = if (maxHeight == Dp.Infinity) MinRowHeight
        else (maxHeight / rowCount).coerceIn(MinRowHeight, MaxRowHeight)

        // 只有"压到最小行高还是放不下"才自己滚（小屏 / 超大字号）：七列仍然一眼看全。
        // 滚动状态照常先建（不放在 if 里），免得条件分支上反而多一层可辨识性开销
        val innerScroll = rememberScrollState()
        val scrollable = rowHeight * rowCount > maxHeight
        Box(
            Modifier
                .fillMaxSize()
                .then(if (scrollable) Modifier.verticalScroll(innerScroll) else Modifier),
            // 行数很少、又被 MaxRowHeight 封了顶时，网格比槽位矮 —— 上下居中，
            // 免得"网格全挤在卡片上半截、下半截空着"看起来像漏画。
            // 只在**不滚动**时居中（此时内容必然 ≤ 槽位，居中绝不会把内容顶出可视区）
            contentAlignment = if (scrollable) Alignment.TopStart else Alignment.Center,
        ) {
            GridCanvas(
                days = days,
                cardsOfDay = cardsOfDay,
                rowCount = rowCount,
                rowHeight = rowHeight,
                axis = axis,
                today = today,
                selected = selected,
                nowRow = nowRow,
                tappedRow = tappedRow,
                onSelect = onSelect,
                onTappedRow = onTappedRow,
                onOpenDayDetail = onOpenDayDetail,
                outline = outline,
                todayTint = todayTint,
                nowTint = nowTint,
                tappedTint = tappedTint,
                accent = accent,
            )
        }
    }
}

/**
 * 网格的**画面本体**：时间轴 + 网格线 + 七列卡片，行高由调用方给定。
 *
 * 拆出来是因为行高是"量出来的"（[GridBody] 的 `maxHeight ÷ 行数`），
 * 有了它这个函数就是纯绘制：给定行高必得同样的画面，与"屏幕多大"无关。
 */
@Composable
private fun GridCanvas(
    days: List<LocalDate>,
    cardsOfDay: List<List<GridCard>>,
    rowCount: Int,
    rowHeight: Dp,
    axis: AxisMetrics,
    today: LocalDate,
    selected: LocalDate,
    nowRow: Int?,
    tappedRow: Int?,
    onSelect: (LocalDate) -> Unit,
    onTappedRow: (Int) -> Unit,
    onOpenDayDetail: (Int, Int) -> Unit,
    outline: Color,
    todayTint: Color,
    nowTint: Color,
    tappedTint: Color,
    accent: Color,
) {
    Row(Modifier.fillMaxWidth().height(rowHeight * rowCount)) {
        PeriodAxis(
            rowCount = rowCount,
            rowHeight = rowHeight,
            metrics = axis,
            highlightRow = tappedRow ?: nowRow,
        )

        Box(
            Modifier
                .weight(1f)
                .height(rowHeight * rowCount)
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
            // ① "现在"正在上的那一段：底色 + 顶部一条线（时间轴上的"此刻"）
            //    注意只在**课内**才亮：一天最后一节下课之后不该还蓝着一行
            nowRow?.let { row ->
                val top = rowHeight * row
                Box(
                    Modifier
                        .offset(y = top)
                        .fillMaxWidth()
                        .height(rowHeight)
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

            // ② 点过的那一行：整行淡淡铺一层，配合时间轴上那一格的强调色（X/Y 一起亮）
            tappedRow?.let { row ->
                Box(
                    Modifier
                        .offset(y = rowHeight * row)
                        .fillMaxWidth()
                        .height(rowHeight)
                        .background(tappedTint)
                )
            }

            // ③ 七天七列
            Row(Modifier.fillMaxSize()) {
                days.forEachIndexed { index, date ->
                    DayColumn(
                        cards = cardsOfDay.getOrElse(index) { emptyList() },
                        rowCount = rowCount,
                        rowHeight = rowHeight,
                        isToday = date == today,
                        isSelected = date == selected,
                        todayTint = todayTint,
                        onSelect = { onSelect(date) },
                        onOpenDetail = { row -> onOpenDayDetail(index, row) },
                        tappedRow = tappedRow,
                        onTappedRow = onTappedRow,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 网格里的一天（一列）：按 [buildDaySlots] 切出的"空行 / 卡片块"自上而下铺满整列。
 *
 * 同一块里的多张卡片**重叠**着放：这一周会上的那张压在最前面（[GridCard.dim] == false 后画），
 * 后面那几张灰着、往右下缩一点露出个边 —— 换周时"换牌"，位置一张不动。
 */
@Composable
private fun DayColumn(
    cards: List<GridCard>,
    rowCount: Int,
    rowHeight: Dp,
    isToday: Boolean,
    isSelected: Boolean,
    todayTint: Color,
    onSelect: () -> Unit,
    /** 点这一列里的某一行：打开这一天的课表（那一天有哪些课，由调用方给） */
    onOpenDetail: (Int) -> Unit,
    tappedRow: Int?,
    onTappedRow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots = remember(cards, rowCount) { buildDaySlots(cards, rowCount) }
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier
            .height(rowHeight * rowCount)
            .background(
                when {
                    isToday -> todayTint
                    isSelected -> Color.White.copy(alpha = 0.18f)
                    else -> Color.Transparent
                }
            )
    ) {
        slots.forEachIndexed { rowIndex, slot ->
            when (slot) {
                // 空行也能点：点一下就把这一天选中（表头胶囊 + 汇总条跟着变）。
                // 反馈用"无涟漪 + 只改选中态"，与玻璃卡片的按下反馈保持同一种克制
                is DaySlot.Gap -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight * slot.rows)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onSelect,
                        )
                )

                is DaySlot.Block -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight * slot.rows)
                        .padding(CardGap),
                ) {
                    // 后画的在上面：把 dim 的排在前面先画，亮的排在最后 => 亮的那张在最前
                    val ordered = slot.cards.sortedBy { if (it.dim) 0 else 1 }
                    ordered.forEachIndexed { index, card ->
                        val depth = ordered.lastIndex - index
                        GridCourseCard(
                            card = card,
                            highlighted = rowIndex == tappedRow && !card.dim,
                            // 多行高的卡片（连上四节）不算"矮"，排版按格子本身多高来定
                            compact = rowHeight * slot.rows < CompactCardRowHeight,
                            modifier = Modifier
                                .fillMaxSize()
                                // 越靠下层缩得越多：露出一点点边，提示"这里还有别的课"
                                .padding(
                                    end = StackedCardInset * depth,
                                    bottom = StackedCardInset * depth,
                                ),
                            onClick = {
                                onTappedRow(rowIndex)
                                onOpenDetail(rowIndex)
                            },
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
 * ## 排版按"格子有多高"自适应（[compact] + 课名行数现算）
 *
 * 行高是整屏自适应算出来的（[GridBody]），所以卡片有时只有 48dp 上下（8 行）、
 * 有时高到 86dp（4 行）。两处跟着变：
 *  - [compact]（格子 < [CompactCardRowHeight]）：课名与地点带各收一档字号、留白收紧；
 *  - **课名画几行**由课名框的实高现算 —— **宁可少显示一行课名，也不能让地点被裁掉**：
 *    地点是"去哪儿上课"，课名看颜色和开头也认得出，裁掉地点就真的少信息了。
 *    课名区用 `weight(1f)` 先让位，地点带永远留在卡片底部。
 *
 * @param card [GridCard.dim] = true 时画成灰调（这一周不上），
 *   所以同一门课"这周亮、下周灰"是同一张卡片换个底色，一眼可比
 * @param highlighted 点过的那一张：加一圈强调色描边（和 X/Y 轴的点亮配套）
 * @param compact 格子矮（< [CompactCardRowHeight]）时的紧凑排版
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCourseCard(
    card: GridCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(8.dp)
    val entry = card.entry
    val accent = CourseRepository.colorOfName(entry.name)
    val tokens = LocalGlassTokens.current
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
            .then(
                if (highlighted) Modifier.border(1.5.dp, tokens.accent, shape) else Modifier
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        // 课名区**可以让位**（weight(1f)）：格子矮的时候少显示一行，也绝不把下面的地点挤掉
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = if (compact) 1.dp else 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            val nameFontSize = if (compact) 9.5.sp else 10.sp
            val nameLineHeight = if (compact) 11.sp else 12.sp
            // 行数按**这个框真有多高**算：框里放得下几行就画几行。
            // 写死 maxLines 在格子矮时会把最后一行裁掉半截（比省略号更难看），
            // 写死 1 行又浪费高格子 —— 所以让行数跟着格子走，这是"任何字号都不裁字"的那一处
            val nameLineHeightDp = with(LocalDensity.current) { nameLineHeight.toDp() }
            val nameLines = (maxHeight / nameLineHeightDp).toInt().coerceIn(1, 3)

            Text(
                text = entry.name,
                fontSize = nameFontSize,
                lineHeight = nameLineHeight,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = textColor,
                maxLines = nameLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.room.isNotBlank()) {
            Text(
                text = entry.room,
                fontSize = if (compact) 7.5.sp else 8.sp,
                lineHeight = if (compact) 9.sp else 10.sp,
                textAlign = TextAlign.Center,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(footerTint)
                    .padding(horizontal = 2.dp, vertical = if (compact) 1.dp else 2.dp),
            )
        }
    }
}

/**
 * 整周都没有可用数据时的占位（原因由页面下方的来源提示条解释）。
 *
 * @param modifier 调用方给 `weight(1f)`：占位也长在"整屏不滚动"的骨架里，高度随屏幕走
 */
@Composable
private fun EmptyWeekHint(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
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
 * 时间轴实测出来的一组度量：**轴宽 + 字号 + 中间那行的写法**。
 *
 * 三者必须一起定、一起用（[PeriodAxis] 画的文字必须与这里量的是同一份），
 * 否则会出现"按 A 量宽、画 B 的文案"→ 重新被省略号截断。
 */
private data class AxisMetrics(val width: Dp, val style: TextStyle, val withPrefix: Boolean)

/**
 * 轴上的节次文案：`第1-2节`（[withPrefix]）或 `1-2节`。
 *
 * 一个函数同时给"量宽度"和"画文字"用 —— 两处各写一遍迟早会不一致。
 */
private fun axisPeriodLabel(firstPeriod: Int, withPrefix: Boolean): String {
    val range = "$firstPeriod-${firstPeriod + PeriodsPerRow - 1}"
    return if (withPrefix) "第${range}节" else "${range}节"
}

/**
 * 量出时间轴该多宽、用哪档字号：**先量最宽的那行文字，再决定轴宽**。
 *
 * | 候选（[AxisCandidates]） | 什么时候用 | 最宽那行文字约需 |
 * |---|---|---|
 * | 9sp + `第1-2节` | 放得下就用它（最正式） | 46dp（本机字号下 45.5dp ⇒ 用不上） |
 * | 9sp + `1-2节` | 系统字号稍大时（省掉「第」字） | 35dp（本机实测就是这一档） |
 * | 8sp + `1-2节` | 系统字号很大时（降一档字号） | 31dp |
 *
 * ## 为什么必须实测，而不是写死一个 52dp
 *
 *  - 写死会把时刻挤成省略号 —— 就是"时间显示不全"那个问题；
 *  - 写死了也会**白白占宽**：七列平分屏幕，轴每多 1dp、每列就少 1/7dp，
 *    实测（本机 font_scale = 1.17）下轴只要 40dp 出头，省下的 10dp 全归卡片。
 *
 * `remember` 的两个 key 都是必要的：字宽随 [LocalDensity] 里的 **fontScale** 变，
 * 系统字号一改就要重量（不然量出来的宽度和实际画出来的对不上，又会被截断）。
 */
@Composable
private fun rememberAxisMetrics(): AxisMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        /** 某一档样式下，三行文字里最宽的那行有多宽（px） */
        fun widest(style: TextStyle, withPrefix: Boolean): Int =
            (0 until CourseRepository.BLOCK_COUNT).maxOf { block ->
                listOf(
                    axisPeriodLabel(block * PeriodsPerRow + 1, withPrefix),
                    CourseRepository.blockStartTime(block),
                    CourseRepository.blockEndTime(block),
                ).maxOf { measurer.measure(it, style).size.width }
            }

        val limit = with(density) { AxisTextMaxWidth.roundToPx() }
        val hardMax = with(density) { AxisTextHardMax.roundToPx() }
        // 从"最好看"往"最将就"挑：第一个宽度不超 limit 的候选就是答案；都不行就用最后一档并封顶
        val chosen = AxisCandidates.firstOrNull { (style, prefix) -> widest(style, prefix) <= limit }
            ?: AxisCandidates.last()
        val (style, prefix) = chosen
        val textWidth = widest(style, prefix).coerceAtMost(hardMax)

        AxisMetrics(
            width = with(density) { textWidth.toDp() } + AxisTextPadding,
            style = style,
            withPrefix = prefix,
        )
    }
}

/**
 * 网格要画几行（一行 = 两节）。
 *
 * ## 只画"真的排过课的行"
 *
 * 原来是**恒定 8 行**（16 节的地图全画出来，哪怕整学期只有下午的课）：
 * 8 行 × 68dp = 544dp，比屏幕上能用的高度还多，于是"看完这一周必须上下滚"
 * （真机实测：周视图 764dp vs 可用 688dp）。
 *
 * 现在行数 = **画布上最后一门课落在第几行**（画布 = 整学期课表，与"第几周"无关，
 * 所以换周时行数不变、网格高度不变），末尾那几行"整学期都没课"的空行不再占地方：
 *
 * | 课表排到 | 行数 | 每行能分到 |
 * |---|---|---|
 * | 第 10 节（下午） | 5 | 约 80dp —— 卡片又大又方正 |
 * | 第 16 节（含晚自习） | 8 | 约 50dp —— 仍然全在一屏 |
 *
 * 中间的空白行**照画**（第 1-2 节有课、第 5-6 节没课）：那是"这个时段空着"的信息，
 * 只有**末尾**的空行才是纯浪费。行数最少 1 行、最多 [MaxGridRows]（防脏数据撑爆）。
 *
 * @param cardsOfDay 七天各自的卡片（画布 + 补的亮卡，见 [buildGridCards]）
 */
internal fun gridRowCount(cardsOfDay: List<List<GridCard>>): Int {
    val lastPeriod = cardsOfDay.asSequence()
        .flatten()
        .maxOfOrNull { it.entry.startPeriod + it.entry.periodCount.coerceAtLeast(1) - 1 }
        ?.coerceIn(1, CourseRepository.MAX_PERIOD)
        ?: return MaxGridRows
    return blocksOf(lastPeriod)
}

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
 * "现在"正落在第几节 —— 网格里那一段会加底色并在顶部画一条强调色线，时间轴上那一格也变色。
 *
 * ## 为什么必须卡在"课内"
 *
 * 一开始的实现是"取已经开始上课的最后一节"，结果**晚上十点第十节还一直蓝着**
 * （一天最后一节之后它再也没有下一节可以交班）。现在按每节的**上课时间窗**判断：
 * `[本节开始, 本节开始 + PERIOD_MINUTES)`，于是课间、午休、下课后、上课前**谁都不亮**。
 * （不把"到下一节开始"当窗口：那会让 11:05 的第 4 节一路亮到 14:00，
 * 午休两个小时显示"正在上课"是错的。）
 *
 * 粒度仍然是**节次**：课表只给了每节的开始时刻，不推断分钟级的当前位置。
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
        val start = periodStartTime(period) ?: return@lastOrNull false
        val end = start.plusMinutes(CourseRepository.PERIOD_MINUTES.toLong())
        !now.isBefore(start) && now.isBefore(end)
    }
}

/** 第 [period] 节的开始时刻；越界或解析不出返回 null（脏数据不让页面崩） */
private fun periodStartTime(period: Int): LocalTime? = runCatching {
    LocalTime.parse(CourseRepository.periodTime(period).padStart(5, '0'))
}.getOrNull()

/**
 * 左右滑动换周时，下一周该落到哪一天（返回的是那一周的周一）。
 *
 * 分三种情况 —— 这就是"刚进页面也滑得动"的关键：
 *  1. 当前这一周在教学周列表里 → 按列表前后翻一档；
 *  2. 当前这一周**不在**列表里（放假 / 未开学 / 学期已过）→ 按日期找列表里"下一个 / 上一个"教学周；
 *  3. 列表压根是空的（本校不支持按周查，或还没同步回来）→ **按自然周**翻 ±7 天。
 *     此时页面会显示整学期课表那张灰画布，等教学周数据到了就自动对齐。
 */
internal fun weekStepTarget(weekStart: LocalDate, weeks: List<SchoolWeek>, delta: Int): LocalDate {
    if (weeks.isEmpty()) return weekStart.plusWeeks(delta.toLong())

    val sorted = weeks.sortedBy { it.start }
    val current = sorted.indexOfFirst { it.contains(weekStart) }
    if (current >= 0) {
        return sorted[(current + delta).coerceIn(0, sorted.lastIndex)].start
    }
    return if (delta > 0) {
        sorted.firstOrNull { it.start > weekStart }?.start ?: weekStart.plusWeeks(1)
    } else {
        sorted.lastOrNull { it.start < weekStart }?.start ?: weekStart.minusWeeks(1)
    }
}

/**
 * 网格里的一块：要么是空行，要么是一块课程（可能叠着好几张）。
 *
 * 拆成纯数据是为了能单测 —— 这一段的错法（课被吞掉、跨行的卡片把下面的课顶掉一行）
 * 都不会崩，只会让课表看起来"少了节课"，真机上很难发现（见 `ScheduleGridSlotsTest`）。
 */
internal sealed interface DaySlot {
    /** 这一块占几行（一行 = 两节） */
    val rows: Int

    /** 空行（那天这个时段没课） */
    data class Gap(override val rows: Int) : DaySlot

    /**
     * 课程块；[cards] 多于一张时**叠在一起**（同一天同一时间有多门课：不同周次共用该时间，
     * 或这一周真的撞课），渲染时把这一周会上的那张压在最前面。
     */
    data class Block(val cards: List<GridCard>, override val rows: Int) : DaySlot
}

/** 第 [period] 节落在第几行（0 起）—— 一行 [PeriodsPerRow] 节 */
internal fun blockOf(period: Int): Int = CourseRepository.blockIndexOf(period)

/** 铺到第 [period] 节为止，一共要几行 */
internal fun blocksOf(period: Int): Int = blockOf(period) + 1

/**
 * 把一天的卡片铺到 [rowCount] 行的时间轴上（**一行 = 两节**）：顺序铺、按行定位、块内跨行。
 *
 * 规则（每条都有对应的单测）：
 *  1. 卡片先换算到"行空间"：`第1-2节` = 第 1 行、`第3-4节` = 第 2 行……（[blockOf]）；
 *     连上四节（1-4）就跨两行；
 *  2. 从第 1 行开始；最早的行还没到，就先铺 [DaySlot.Gap]（空行）把它顶到正确的行上；
 *  3. 与当前块**行区间重叠**的卡片一律收进同一块（**叠着放**，不是并排也不是上下分），
 *     块的跨度取最长的那个。宁可叠，**不能把课藏起来**；
 *  4. 块的总行数不会越过 [rowCount]；节次/节数越界的脏数据先夹到合法范围再参与铺排，
 *     所以"解析出一个第 20 节"也不会让这门课从网格里消失；
 *  5. 返回值覆盖 [rowCount] 行（不足的部分由末尾的 [DaySlot.Gap] 补满），
 *     这样每一列的高度天然一致。
 *
 * @return 自上而下的块列表，`sumOf { it.rows } == rowCount`
 */
internal fun buildDaySlots(cards: List<GridCard>, rowCount: Int): List<DaySlot> {
    if (rowCount <= 0) return emptyList()

    val maxPeriod = rowCount * PeriodsPerRow
    val pending = cards
        .map { card ->
            val start = card.entry.startPeriod.coerceIn(1, maxPeriod)
            val end = (start + card.entry.periodCount.coerceAtLeast(1) - 1).coerceAtMost(maxPeriod)
            PendingBlock(
                card = card,
                start = blockOf(start),
                span = blockOf(end) - blockOf(start) + 1,
            )
        }
        .toMutableList()

    val slots = mutableListOf<DaySlot>()
    var row = 0

    while (row < rowCount && pending.isNotEmpty()) {
        val earliest = pending.minOf { it.start }
        if (earliest > row) {
            // 这一段的空白也是信息：它表示"这几行没课"
            slots += DaySlot.Gap(earliest - row)
            row = earliest
            continue
        }

        // 以 row 为起点铺一块：把与它重叠的卡片都收进来。
        // 收进来的卡片可能更长（把这一块的终点往下推），所以要循环到稳定为止。
        val block = mutableListOf<PendingBlock>()
        var endExclusive = row + 1
        while (true) {
            val newly = pending.filter { it.start < endExclusive && it !in block }
            if (newly.isEmpty()) break
            block += newly
            endExclusive = maxOf(endExclusive, newly.maxOf { it.start + it.span })
        }

        val span = (endExclusive - row).coerceAtMost(rowCount - row)
        slots += DaySlot.Block(block.map { it.card }, span)
        pending.removeAll(block)
        row += span
    }

    // 末尾补齐：每一列都必须正好 rowCount 行高，否则列与列会错位
    if (row < rowCount) slots += DaySlot.Gap(rowCount - row)
    return slots
}

/** [buildDaySlots] 的中间态：一张卡片 + 已换算并夹取过的起始行与跨度（行） */
private data class PendingBlock(val card: GridCard, val start: Int, val span: Int)
