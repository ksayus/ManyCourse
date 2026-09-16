package com.tof.manycourse.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.CourseSync
import com.tof.manycourse.data.LoginSettings
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.data.WeekScheduleStore
import com.tof.manycourse.data.WeekdayLabels
import com.tof.manycourse.data.coursesOfDate
import com.tof.manycourse.data.dateOfCurrentWeek
import com.tof.manycourse.data.nextCourseKey
import com.tof.manycourse.data.weekdayLabel
import com.tof.manycourse.ui.components.CampusButton
import com.tof.manycourse.ui.components.CampusChip
import com.tof.manycourse.ui.components.CourseCard
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
 * @param onAddCourse 点「添加课程」（参数 = 预选的星期几）
 * @param onCourseClick 点某张课程卡片 → 由 Fragment 打开课程详情浮层。
 *   参数是**那一天的全部课**（按节次排）：详情卡列的是"那天有什么课"，不是"点中的那一门"。
 *   **浮层不在这里组合**：本页的内容区被页头和底栏夹在中间，浮层挂在这里的话
 *   遮罩只能盖住中间那一段（页头/底栏还亮着），和「添加课程」的全屏浮层对不上。
 */
@Composable
fun ScheduleScreen(
    hazeState: HazeState,
    onAddCourse: (Int) -> Unit,
    onCourseClick: (LocalDate, List<CourseEntry>) -> Unit,
) {
    val today = LocalDate.now()
    val todayWeekday = today.dayOfWeek.value // 1=周一
    var selectedDay by remember { mutableIntStateOf(todayWeekday) }
    var pendingDelete by remember { mutableStateOf<Course?>(null) }

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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
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

        Text(
            text = "${weekdayLabel(selectedDay)} · ${entries.size} 门课程",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(16.dp))

        entries.forEach { entry ->
            CourseCard(
                course = entry,
                highlighted = entry.key == highlightedKey,
                metaText = "${weekdayLabel(entry.weekday)} ${entry.periodLabel}" +
                    entry.weeks.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                // 点击 → 详情浮层：列的是**这一天**的课（与上面这份列表同一份数据）
                onClick = { onCourseClick(selectedDate, entries) },
                // 教务系统的课删不掉（下次同步又会回来），只有本地课给长按删除
                onLongClick = (entry as? CourseEntry.Local)?.let { local ->
                    { pendingDelete = local.course }
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(8.dp))

        CampusButton(
            text = "添加课程",
            icon = AppIcons.Plus,
            onClick = { onAddCourse(selectedDay) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))
    }

    // 长按删除确认
    pendingDelete?.let { course ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除课程") },
            text = { Text("确定删除「${course.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    CourseRepository.remove(course.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
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
 *  - **登录过期 → 红字 + 重新登录**。这一条必须和"失败"分开：
 *    登录态失效时反复点重试是点不好的，得把用户送回登录页；
 *  - 成功 → 一行来源说明，让人知道课表是从学校系统来的；
 *  - 本地调试 / 手动录课 → 什么都不显示。
 */
@Composable
private fun SyncStatusBanner() {
    val state = CourseSync.state.value
    val tokens = LocalGlassTokens.current
    val context = LocalContext.current

    // (文案, 是否红色, 按钮文案 or null, 点击行为)
    val banner: Banner? = when (state) {
        is CourseSync.State.Loading -> Banner("正在从教务系统同步课表…", false)
        is CourseSync.State.Ready ->
            Banner("课表来源：${state.studentName} · 已同步 ${state.courses} 门课程", false)
        is CourseSync.State.Failed -> Banner(
            text = state.message,
            error = true,
            actionLabel = "重试",
            onAction = {
                CourseSync.sync(
                    schoolId = SessionStore.schoolId.value ?: LoginSettings.selectedSchoolId.value,
                    account = SessionStore.account.value,
                    force = true,
                )
            },
        )
        CourseSync.State.Expired -> Banner(
            text = "登录已过期，请重新登录",
            error = true,
            actionLabel = "重新登录",
            onAction = { context.logoutAndBackToLogin() },
        )
        CourseSync.State.Idle -> null
    }

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
