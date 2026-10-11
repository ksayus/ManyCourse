package com.tof.manycourse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.CourseSync
import com.tof.manycourse.data.DataRefresh
import com.tof.manycourse.data.WeekScheduleStore
import com.tof.manycourse.data.WeekdayLabels
import com.tof.manycourse.data.coursesOfDate
import com.tof.manycourse.data.dateOfCurrentWeek
import com.tof.manycourse.data.dayIsLit
import com.tof.manycourse.data.nextCourseKey
import com.tof.manycourse.data.weekdayLabel
import com.tof.manycourse.data.ScheduleCache
import com.tof.manycourse.data.formatCacheTime
import com.tof.manycourse.ui.components.CampusButton
import com.tof.manycourse.ui.components.CampusChip
import com.tof.manycourse.ui.components.CourseCard
import com.tof.manycourse.ui.components.courseCardRowBounds
import com.tof.manycourse.ui.theme.LocalGlassTokens
import java.time.LocalDate
import java.time.LocalTime

/**
 * 课表页（标题就是「本周课表」）。
 *
 * ## 数据来源
 *
 * 全权交给 [coursesOfDate]（**和日历页是同一个函数**）：学校支持按周查时用它给的
 * "这一周真的会上什么课"，否则本周回落到本地课表。
 *
 * 为什么不做成"整个学期的课按星期几循环"：那样第 20 周的周一也会画上
 * 只在第 2-4 周上的军事理论，而且**会和日历页对不上** —— 日历页拿的是按周的真实数据。
 * 两页要显示同一天的课，就必须用同一个来源、同一套兜底规则。
 *
 * ## 手动刷新
 *
 * **下拉即刷新**（设计规范里课表页顶部那行「下拉刷新」说的就是这件事），
 * 走的入口是 [DataRefresh]：它强制联网重拉（绕开 6 小时缓存与"已经同步过"两道判断）。
 * 页头也有一个刷新按钮（见 `RefreshButton`）—— 下拉是手势，按钮是"一定能点到"的那条路。
 *
 * ## 整屏不滚动
 *
 * 这一页**不做整页滚动**：固定信息（下拉提示 / 星期 / 状态条 / 周次 / 节假日 / 门数）
 * 与「添加课程」按钮各占各的高度，中间那一条全部给课程卡片，卡片区再按"当天有几门课"
 * 把高度平分给每一行（见 [DayCourseList]）。只有"压到最小行高仍放不下"时才让卡片区
 * 自己内部滚 —— 与日历页周视图同一个口径。
 *
 * ★ 这里**只算高度，不加任何动效**：课表的位置本身就是信息（架构指南 §2.5）。
 *
 * @param onAddCourse 点「添加课程」（参数 = 预选的星期几）
 * @param onCourseClick 点某张课程卡片 → 由 Fragment 打开课程详情浮层。
 *   参数是**那一天的全部课**（按节次排）：详情卡列的是"那天有什么课"，不是"点中的那一门"。
 *   **浮层不在这里组合**：本页的内容区被页头和底栏夹在中间，浮层挂在这里的话
 *   遮罩只能盖住中间那一段（页头/底栏还亮着），和「添加课程」的全屏浮层对不上。
 * @param onEditCourse 长按课程卡片 → 选「编辑」→ 由 Fragment 打开「编辑课程」浮层。
 *   和「添加课程」共用同一个面板（`AddCourseScreen(editing = …)`），所以这里只往上抛意图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    hazeState: HazeState,
    onAddCourse: (Int) -> Unit,
    onCourseClick: (LocalDate, List<CourseEntry>) -> Unit,
    onEditCourse: (Course) -> Unit,
) {
    val today = LocalDate.now()
    val todayWeekday = today.dayOfWeek.value // 1=周一
    var selectedDay by remember { mutableIntStateOf(todayWeekday) }
    // 长按卡片弹出的操作（编辑 / 删除）—— 以前只支持删除，所以原来叫 pendingDelete
    var pendingAction by remember { mutableStateOf<Course?>(null) }

    // 教学周列表可能比课表晚到（两条链路并行）；到齐了就把"本周"的课补拉一次。
    // ensureWeek 对已缓存/在飞的周次是空操作，可以放心调。
    val weekCount = WeekScheduleStore.weeks.size
    LaunchedEffect(weekCount) {
        WeekScheduleStore.currentWeek?.let { WeekScheduleStore.ensureWeek(it.index) }
    }

    // 选中的星期几 → 本周里的具体日期。**按日期取课**（而不是按星期几），
    // 这样"课表页"和"日历页"走的是同一条取值路径，天然一致。
    val selectedDate = dateOfCurrentWeek(selectedDay, today)
    val entries = coursesOfDate(selectedDate).orEmpty()

    // 实时计算：今天"下一节"课程高亮（当前时间未到开课时间的第一门）
    val highlightedKey = remember(entries, selectedDay, todayWeekday) {
        if (selectedDay != todayWeekday) null else nextCourseKey(entries, LocalTime.now())
    }

    // 外层 Box 是给「没刷成」那一句提示用的：它是浮层，不能挤动下面的课表
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            // 转圈看的是真实同步状态（见 DataRefresh.running），不另记一份"我在刷新"
            isRefreshing = DataRefresh.running,
            onRefresh = { DataRefresh.start() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    // ★ 这一层**没有** verticalScroll：整页要一屏显示完（见 [DayCourseList]）。
                    //   下拉刷新不受影响 —— 滚动容器挪到了卡片区（那里带着它），
                    //   PullToRefreshBox 靠的正是子级滚动容器的嵌套滚动事件
                    .padding(horizontal = 16.dp)
            ) {
                // 下拉刷新提示（/ 刷不了的原因）。按设计规范放在日期筛选**上面**
                RefreshHintRow(hint = "下拉刷新课表")

                // 星期筛选（设计规范：32dp 胶囊 Chip）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WeekdayLabels.forEachIndexed { index, label ->
                        CampusChip(
                            text = label,
                            selected = selectedDay == index + 1,
                            onClick = { selectedDay = index + 1 },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 同步状态：登录后会按"登录的学校"去拉课表，这里把过程和失败原因说清楚
                SyncStatusBanner()

                // 教学周标签：和日历页月份标题下那一行是**同一个来源**，
                // 让"两页看的是同一周"这件事一眼可见
                WeekLabel()

                // 节假日提示：放假那天**课表照旧列出当天的课，但一门都不点亮**（灰着），
                // 不说一句"今天放假"的话，用户会以为这些课照常上
                // ★ 放在门数那行**上面**、且自成一行：门数那行的文案（`周X · N 门课程`）
                //   被真机测试 ScheduleCalendarParityTest 逐字对账，不能改也不能混进别的字
                HolidayNotice(selectedDate)

                Text(
                    text = "${weekdayLabel(selectedDay)} · ${entries.size} 门课程",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(DayHeaderGap))

                // ★ 卡片区吃掉"上面这些固定信息 + 下面那个按钮"之后剩下的全部高度，
                //   于是行高能由它**量出来**（见 DayCourseList）。这里给 weight(1f) 是关键：
                //   没有它，卡片区的高度会由内容撑，行高也就无从算起
                DayCourseList(
                    entries = entries,
                    // 放假那天整列不亮（课照旧列出，灰着）—— 判据是 dayIsLit，与日历页/周视图同一个
                    lit = dayIsLit(selectedDate),
                    highlightedKey = highlightedKey,
                    selectedDate = selectedDate,
                    // 点击 → 详情浮层：列的是**这一天**的课（与上面这份列表同一份数据）
                    onCourseClick = { onCourseClick(selectedDate, entries) },
                    onEditCourse = { course -> pendingAction = course },
                    modifier = Modifier.weight(1f),
                )

                Spacer(Modifier.height(DayListGap))

                CampusButton(
                    text = "添加课程",
                    icon = AppIcons.Plus,
                    onClick = { onAddCourse(selectedDay) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(DayBottomGap))
            }
        }

        // 刷不了的原因（本地调试账号等）：浮在底部，几秒后自己消失
        RefreshNotice()
    }

    // 长按卡片的操作：编辑 / 删除。
    // 「编辑」为什么也放这儿：在卡片上再塞一个图标按钮会让课表变吵，
    // 而长按本来就已经是"针对这一门课"的手势了。
    pendingAction?.let { course ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(course.name) },
            text = {
                Text(
                    listOf(
                        weekdayLabel(course.weekday),
                        course.periodLabel,
                        course.weeks,
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        pendingAction = null
                        onEditCourse(course)
                    }) { Text("编辑") }
                    TextButton(onClick = {
                        CourseRepository.remove(course.id)
                        pendingAction = null
                    }) { Text("删除") }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text("取消") }
            },
        )
    }
}

/** 卡片之间的间距（行高预算里要把它扣掉，见 [dayRowHeight]）*/
private val DayCardGap = 8.dp

/** 门数那行与卡片区之间 / 卡片区与「添加课程」之间 / 底部留白 */
private val DayHeaderGap = 12.dp
private val DayListGap = 8.dp
private val DayBottomGap = 16.dp

/**
 * **一天的课程列表：把整屏高度按课程张数分给每一行**（与日历页周视图同一套口径）。
 *
 * ## 它解决的是"课多了要滚"
 *
 * 这一页原来是整页 `verticalScroll`：一天六七门课就得上下滑。而"今天有什么课"
 * 本来就该一眼看完，所以现在：
 *
 *  - 卡片区由调用方给 `weight(1f)` —— 也就是**下拉提示 / 星期 / 状态条 / 周次 /
 *    节假日 / 门数这些固定信息**与「添加课程」按钮之后，真正剩下的那一条高度；
 *  - **行数** = 当天**真正有课**的张数（`coursesOfDate` 给的，不猜、也不写死：
 *    一天排几门由数据说，跟"这所学校一天几节""一节课多少分钟"都无关）；
 *  - **行高** = (这条高度 − 卡片间距总和) ÷ 行数，再夹进
 *    [courseCardRowBounds] 给的上下限（下限 = 卡片还读得出来的最小高度，
 *    上限 = 课名两行 + 时间地点 + 节次的自然高度）；
 *  - 于是"行数 × 行高 + 间距"正好铺满这条高度 —— **一屏看完，不用滚**。
 *
 * ## 变矮的是行，退档的是卡片
 *
 * 行高可能只有 60dp 出头。那种高度下由**卡片自己**退档（课名收成一行、
 * 整条「时间 · 地点」行撤掉，见 `CourseCard` 与它上面那张分档表）——
 * 这一层只负责"把高度算对"，不做"挤一挤"。
 *
 * ## 只有压到下限仍放不下时才让列表自己滚
 *
 * 小屏 + 超大系统字号 + 一天七八门课，三者凑齐时**真的放不下**。那就按最小行高铺开、
 * 让卡片区自己内部滚（固定信息与按钮都不动），而不是把行高继续往下压 ——
 * 压到字被裁掉半截，比"需要滚动"更糟。日历页周视图也是这么处理的。
 *
 * ## 滚动容器为什么**始终**挂着
 *
 * 一屏放得下时它其实滚不动（内容比容器矮），但不能省：`PullToRefreshBox` 的
 * 「下拉刷新」靠的是**子级滚动容器的嵌套滚动事件**，去掉它这一页的下拉刷新就没了。
 *
 * ## ★ 这里没有任何动效，也不许加
 *
 * 课表的位置本身就是信息（架构指南 §2.5「只改颜色，不做位移/缩放」）。
 * 这次只算**高度**：卡片该在第几行还在第几行，骨架一格都不动。
 *
 * @param onCourseClick 点某张卡片（详情浮层列的是这一天**全部**课，调用方已经绑好）
 * @param onEditCourse 长按某张卡片 → 调用方弹「编辑 / 删除」的确认框
 */
@Composable
private fun DayCourseList(
    entries: List<CourseEntry>,
    lit: Boolean,
    highlightedKey: String?,
    selectedDate: LocalDate,
    onCourseClick: () -> Unit,
    onEditCourse: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 行高的上下限由**实测的字行高**推出来（系统字号变大时它会跟着变），
    // 不在这里写死 dp —— 理由见 CourseCard 上面那段注释
    val rowBounds = courseCardRowBounds(LocalDensity.current)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val rowCount = entries.size
        // maxHeight 有界是调用方 weight(1f) 保证的；真无界（理论上不会）时退回下限，不让它崩
        val rowHeight = dayRowHeight(
            availableHeight = maxHeight,
            rowCount = rowCount,
            gap = DayCardGap,
            minRowHeight = rowBounds.min,
            maxRowHeight = rowBounds.max,
        )
        val totalHeight = if (rowCount <= 0) 0.dp else rowHeight * rowCount + DayCardGap * (rowCount - 1)

        // 滚动状态照常先建（不放进 if 里，免得条件分支上反而多一层可辨识性开销）
        val innerScroll = rememberScrollState()
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(innerScroll),
            // 行数少、行高被上限封住时整块比卡片区矮 —— 上下居中，
            // 免得"课程全挤在上半截、下半截空着"看起来像漏画。
            // 只在**不滚动**时居中（此时内容必然 ≤ 卡片区，居中绝不会把内容顶出可视区）
            contentAlignment = if (totalHeight > maxHeight) Alignment.TopStart else Alignment.Center,
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 本次真正"新增"的卡片（换天/首次组合都不算）——见 rememberFreshCardKeys 的边界说明
                val freshKeys = rememberFreshCardKeys(selectedDate, entries.map { it.key })
                entries.forEachIndexed { index, entry ->
                    if (index > 0) Spacer(Modifier.height(DayCardGap))
                    NewCourseCardReveal(animate = entry.key in freshKeys) {
                        CourseCard(
                            course = entry,
                            // 行高固定：整份列表的高度因此是**算出来的**，不是内容撑出来的
                            modifier = Modifier.height(rowHeight),
                            highlighted = entry.key == highlightedKey,
                            dim = !lit,
                            metaText = "${weekdayLabel(entry.weekday)} ${entry.periodLabel}" +
                                entry.weeks.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                            // 卡片按这个高度决定画几行课名、要不要撤掉「时间 · 地点」行
                            rowHeight = rowHeight,
                            onClick = onCourseClick,
                            // 教务系统的课改不了（下次同步又会回来），只有本地课给长按操作（编辑 / 删除）
                            onLongClick = (entry as? CourseEntry.Local)?.let { local ->
                                { onEditCourse(local.course) }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * **一行课程卡的高度** = "卡片区真正剩下的高度 ÷ 课程张数"（纯函数，`ScheduleRowHeightTest` 钉住）。
 *
 * 这是"一屏看完"与"卡片尽量大"能同时成立的那一处：
 *
 *  - 一天三门课 → 每行能分到 120dp 上下（被 [maxRowHeight] 封顶），课名能折到两行；
 *  - 一天六门课 → 每行 70dp 上下，卡片自己退成紧凑档，照样全在一屏里；
 *  - 一天十门课（小屏）→ 压到 [minRowHeight] 也放不下 → 调用方据此让列表**内部滚**。
 *
 * @param gap 卡片之间的间距（**先扣掉间距再平分**，否则"行数 × 行高"会超出可用高度）
 * @param minRowHeight 卡片还读得出来的最小高度（来自 `courseCardRowBounds`）
 * @param maxRowHeight 行高上限：再高就不是"一行课"了，而是一张空壳卡片
 */
internal fun dayRowHeight(
    availableHeight: Dp,
    rowCount: Int,
    gap: Dp,
    minRowHeight: Dp,
    maxRowHeight: Dp,
): Dp {
    if (rowCount <= 0) return minRowHeight
    // 高度无界 / 非有限值（理论上不会发生）时退回下限，不让它崩
    if (!availableHeight.value.isFinite()) return minRowHeight
    val gaps = gap * (rowCount - 1)
    return ((availableHeight - gaps) / rowCount).coerceIn(minRowHeight, maxRowHeight)
}

/**
 * **新出现的课程卡片**：淡入 + 轻微上移。
 *
 * ## 为什么要一个组件、而不是直接给 `CourseCard` 挂 `Modifier.animateXxx`
 *
 * 这一页的列表是普通 `Column` + `verticalScroll`（不是 `LazyColumn`），
 * 没有 `animateItem` 可挂的 item scope；而 `AnimatedVisibility` 需要一个"可见性"入口，
 * 所以包一层。**位移量刻意很小**（高度的 1/14，约 6dp）——
 * §2.5 记录过"整块浮起 6dp + 淡入"在课表上被否决，理由是课表的位置本身就是信息；
 * 这里只动**新增的那一张**（其余卡片一动不动），6dp 只够表达"它是刚加进来的"。
 *
 * ## ★ 关键：不能每次重组都重播
 *
 * [animate] 由 [rememberFreshCardKeys] 给出，只有**真正新增**的 key 才是 true。
 * 否则滚动一次、算一次"下一节课"高亮、甚至换配色导致的整树重组，
 * 都会让满屏卡片重新淡入一遍 —— 看起来就是"整页在闪"。
 */
@Composable
private fun NewCourseCardReveal(animate: Boolean, content: @Composable () -> Unit) {
    val animations = rememberAnimationsEnabled()
    AnimatedVisibility(
        visible = true,
        // 系统关动画 / 不是新增的卡片：EnterTransition.None = 首帧即就位，不闪
        enter = if (animations && animate) {
            fadeIn(enterTween()) + slideInVertically(enterTween()) { height -> -height / 14 }
        } else {
            EnterTransition.None
        },
    ) {
        content()
    }
}

/**
 * 记住"这个日期下**已经出现过**的卡片 key"，返回本次**新增**的那些。
 *
 * ## 边界（每条都对应一种"整页闪一下"）
 *
 * - **换了一个日期**：整份列表换了内容，不是"新增了一门课" → 一律不算新增，
 *   并清空上一天记下的 key（否则在两天之间来回切，第二天的课会被当成"新增"重播）；
 * - **首次组合 / 这一页刚出现**：[cardKeys] 为空 → 没有可比的基准，不算新增；
 * - **算完必须立刻把本轮 key 记下**：写在组合体里而不是 `LaunchedEffect` 里 ——
 *   后者要等一个副作用周期，中间那一帧新卡片已经画出来了，随后才播一遍进场
 *   （观感是"先闪一下再淡入"）。
 *
 * 只在组合阶段写这一次状态，且写的是"上一轮的集合"，属于 Compose 里
 * "记住上一次输入"的常规写法：不会每帧都写，也不会反复触发重组。
 */
@Composable
private fun rememberFreshCardKeys(date: LocalDate, cardKeys: List<String>): Set<String> {
    val cache = remember { mutableStateOf<Pair<LocalDate, Set<String>>?>(null) }
    val cachedDate = cache.value?.first
    val cachedKeys = cache.value?.second ?: emptySet()
    val fresh = if (cachedDate != date || cardKeys.isEmpty()) {
        emptySet()
    } else {
        cardKeys.filterNot { it in cachedKeys }.toSet()
    }
    val nextKeys = if (cachedDate == date) cachedKeys + cardKeys else cardKeys.toSet()
    if (cachedDate != date || cachedKeys != nextKeys) {
        cache.value = date to nextKeys
    }
    return fresh
}

/**
 * 教学周标签（`第3周 09-14 ~ 09-20`）。
 *
 * 只在拿到教学周数据时显示 —— 没有数据时这一行整个不出现，而不是显示一个
 * 客户端拍脑袋算出来的周次（开学日期各校不同，猜必错）。
 */
@Composable
private fun WeekLabel() {
    val week = WeekScheduleStore.currentWeek ?: return
    Text(
        text = week.label,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/**
 * 课表同步状态条。
 *
 * 只在"有话说"的时候占位：
 *  - 同步中 → 提示正在拉；
 *  - 失败 → **红字 + 重试**，把"为什么没有课表"讲清楚（教务系统挂了 / 接口改版，
 *    表现都是"课表空着"，不提示的话用户只会以为 App 坏了）；
 *  - **登录过期 → 有缓存时是"继续看缓存"+「重新登录以刷新」**（页面不跳走），
 *    没缓存才退回"红字 + 重新登录"。这一条必须和"失败"分开：
 *    登录态失效时反复点重试是点不好的；
 *  - **没在同步但有缓存 → 「离线课表 · 最后更新 …」**（TTL 内跳过联网 / 本地调试时不联网）；
 *  - 成功 → 一行来源说明，让人知道课表是从学校系统来的；
 *  - 本地调试 / 手动录课 → 什么都不显示。
 */
@Composable
private fun SyncStatusBanner() {
    val state = CourseSync.state.value
    val tokens = LocalGlassTokens.current
    val context = LocalContext.current

    // 磁盘上那份课表的写入时刻（读 Compose 状态，写盘后这条会自动重组）
    val cachedAt = ScheduleCache.savedAt.value
    val cachedLabel = cachedAt?.let(::formatCacheTime)

    // (文案, 是否红色, 按钮文案 or null, 点击行为)
    val banner: Banner? = when (state) {
        is CourseSync.State.Loading -> Banner("正在从教务系统同步课表…", false)
        is CourseSync.State.Ready ->
            Banner("课表来源：${state.studentName} · 已同步 ${state.courses} 门课程", false)
        is CourseSync.State.Failed -> Banner(
            text = if (cachedLabel != null) {
                "${state.message}（当前显示 $cachedLabel 的缓存课表）"
            } else {
                state.message
            },
            error = true,
            actionLabel = "重试",
            // 和下拉刷新、页头按钮是**同一个入口**：三者都只是"再拉一次"的不同画法，
            // 走两份实现的话，"哪些情况刷不了、刷不了说什么"迟早会各长一样
            onAction = { DataRefresh.start() },
        )
        // ★ 登录过期不再把人赶回登录页：先把缓存的课表继续给他看。
        //   "重新登录"从"看数据的前提"降级成"刷新数据时才要做的事"
        CourseSync.State.Expired -> if (cachedLabel != null) {
            Banner(
                text = "登录已过期 · 正在显示 $cachedLabel 的缓存课表",
                error = true,
                actionLabel = "重新登录以刷新",
                onAction = { context.logoutAndBackToLogin() },
            )
        } else {
            // 这台机器上没缓存：老行为，只能重新登录
            Banner(
                text = "登录已过期，请重新登录",
                error = true,
                actionLabel = "重新登录",
                onAction = { context.logoutAndBackToLogin() },
            )
        }
        // 没在同步、也没报错，但有缓存 → 说明这就是一份离线课表
        CourseSync.State.Idle -> cachedLabel?.let { Banner("离线课表 · 最后更新 $it", false) }
    }

    // ★ 这个渲染块是"状态条真的会显示"的唯一保证 —— 上面那个 `when` 只是**算**出内容，
    //   少了下面这几行，banner 算得再对也不会画出来（"正在同步…"「重试」「重新登录以刷新」全都消失）
    if (banner == null) return

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = banner.text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = if (banner.error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        banner.actionLabel?.let { label ->
            TextButton(onClick = { banner.onAction?.invoke() }) {
                Text(label, fontSize = 12.sp, color = tokens.accent)
            }
        }
    }
}

/** 状态条的内容描述，避免在 `when` 里塞一堆三元表达式 */
private data class Banner(
    val text: String,
    val error: Boolean,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)
