package com.tof.manycourse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseConflict
import com.tof.manycourse.data.CourseConflicts
import com.tof.manycourse.data.CourseDraft
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.WeekRanges
import com.tof.manycourse.data.WeekdayLabels
import com.tof.manycourse.data.weekdayLabel
import com.tof.manycourse.ui.components.CampusButton
import com.tof.manycourse.ui.components.CampusChip
import com.tof.manycourse.ui.components.GlassOverlay
import com.tof.manycourse.ui.components.GlassPanel
import com.tof.manycourse.ui.theme.LocalGlassTokens

/**
 * 添加 / **编辑**课程（本地数据）：全屏玻璃表单浮层。
 *
 * 面板走 [GlassPanel]（静态高不透明度表面：可读性优先 + 进出场无闪帧），
 * 进出场动画、返回键关闭、点击遮罩关闭由 [GlassOverlay] 统一处理。
 * 时间不用手填，由"开始节次"实时计算。
 *
 * ## 三件事在这个面板里合流
 *
 * 1. **新增与编辑是同一个面板**：表单字段完全一样，差别只有标题、按钮文案，
 *    以及保存时走 `add` 还是 `update`。分成两个面板会让"周次栏"这类字段要改两处。
 * 2. **周次**（选填）：填了就能表达"只在第 3-5 周上"，也就能被 [CourseConflicts] 正确判定。
 *    解析不出时如实提示，并按"每周都上"处理（与 `WeekRanges` 的口径一致）。
 * 3. **冲突提醒**：每改一下就重算，**只提醒不阻止** ——
 *    冲突课表本身可能是用户故意的（比如两门课要二选一先都加上），App 没资格替他决定。
 *
 * @param editing 非 null = 编辑这门课；null = 新增
 */
@Composable
fun AddCourseScreen(
    visible: Boolean,
    initialWeekday: Int,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    editing: Course? = null,
) {
    val editingId = editing?.id
    val isEditing = editingId != null

    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var weekday by remember { mutableIntStateOf(initialWeekday.coerceIn(1, 7)) }
    var startPeriod by remember { mutableIntStateOf(1) }
    var periodCount by remember { mutableIntStateOf(2) }
    var weeks by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }

    val nameError = touched && name.isBlank()
    val accent = LocalGlassTokens.current.accent

    // 浮层常驻组合（为了播退出动画），每次打开时重置表单，避免残留上次输入。
    // ★ key 里带上 editingId：从"编辑 A"直接切到"编辑 B"时也要重新灌值
    LaunchedEffect(visible, editingId) {
        if (visible) {
            name = editing?.name.orEmpty()
            teacher = editing?.teacher.orEmpty()
            // 「地点待定」是保存时给空地点填的占位符，编辑时按"没填"显示
            room = editing?.room.orEmpty().takeIf { it != CourseRepository.ROOM_PLACEHOLDER }.orEmpty()
            weekday = (editing?.weekday ?: initialWeekday).coerceIn(1, 7)
            startPeriod = (editing?.startPeriod ?: 1).coerceIn(1, CourseRepository.MAX_PERIOD)
            periodCount = (editing?.periodCount ?: 2).coerceIn(1, CourseRepository.MAX_PERIOD)
            weeks = editing?.weeks.orEmpty()
            touched = false
        }
    }

    val endPeriod = (startPeriod + periodCount - 1).coerceAtMost(CourseRepository.MAX_PERIOD)
    val startTime = CourseRepository.periodTime(startPeriod)

    // ── 周次解析结果（如实展示：认不出就说认不出，不装作识别成功）──
    val weeksNormalized = remember(weeks) { WeekRanges.normalize(weeks) }
    val weeksUnreadable = weeks.isNotBlank() && WeekRanges.weeksOrNull(weeks) == null

    // ── 实时冲突检测 ──
    // 读 `CourseRepository.courses` 会订阅这份快照列表，列表一变这里就会重算。
    // ★ 编辑时必须把自己从 others 里排除，否则它会跟自己撞。
    val draft = CourseDraft(
        name = name,
        teacher = teacher,
        room = room,
        weekday = weekday,
        startPeriod = startPeriod,
        periodCount = periodCount,
        weeks = weeks,
    )
    val others = CourseRepository.courses.filterNot { it.id == editingId }
    val conflicts = remember(draft, others) { CourseConflicts.against(draft, others) }

    GlassOverlay(visible = visible, onDismissRequest = onDismiss) {
        GlassPanel(
            cornerRadius = 24, // 设计规范：sheet 用 24dp 圆角
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // 标题栏
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isEditing) "编辑课程" else "添加课程",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable(onClick = onDismiss),
                    )
                }

                Spacer(Modifier.height(16.dp))

                FormInput(
                    label = "课程名称",
                    value = name,
                    placeholder = "例如：高等数学",
                    isError = nameError,
                    onValueChange = { name = it },
                )
                if (nameError) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "请输入课程名称",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(12.dp))

                FormInput(
                    label = "任课教师（选填）",
                    value = teacher,
                    placeholder = "例如：王建国",
                    onValueChange = { teacher = it },
                )

                Spacer(Modifier.height(12.dp))

                FormInput(
                    label = "上课地点（选填）",
                    value = room,
                    placeholder = "例如：教学楼 A-101",
                    onValueChange = { room = it },
                )

                Spacer(Modifier.height(12.dp))

                FormInput(
                    label = "上课周次（选填）",
                    value = weeks,
                    placeholder = "例如：1-16周 / 3-5,8-20周",
                    onValueChange = { weeks = it },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        weeks.isBlank() -> "不填 = 每周都上"
                        weeksUnreadable -> "这段周次认不出来，将按「每周都上」处理"
                        else -> "已识别：$weeksNormalized"
                    },
                    fontSize = 12.sp,
                    color = if (weeksUnreadable) MaterialTheme.colorScheme.error else accent,
                )

                Spacer(Modifier.height(16.dp))

                FormLabel("星期")
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WeekdayLabels.forEachIndexed { index, label ->
                        CampusChip(
                            text = label,
                            selected = weekday == index + 1,
                            onClick = { weekday = index + 1 },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                FormLabel("开始节次")
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..CourseRepository.MAX_PERIOD).forEach { p ->
                        CampusChip(
                            text = "第${p}节",
                            selected = startPeriod == p,
                            onClick = {
                                startPeriod = p
                                if (startPeriod + periodCount - 1 > CourseRepository.MAX_PERIOD) {
                                    periodCount = CourseRepository.MAX_PERIOD - startPeriod + 1
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                FormLabel("连续节数")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..4).forEach { c ->
                        CampusChip(
                            text = "${c}节",
                            selected = periodCount == c,
                            onClick = {
                                periodCount = if (startPeriod + c - 1 > CourseRepository.MAX_PERIOD)
                                    CourseRepository.MAX_PERIOD - startPeriod + 1
                                else c
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 实时计算预览：周几 · 第几节 ·（有时刻表才写）几点上课。
                // 广工这类还没拿到作息的学校只写节次 —— 不编一个"9:00 上课"出来
                Text(
                    text = "${weekdayLabel(weekday)} · 第${startPeriod}-${endPeriod}节" +
                        startTime.takeIf { it.isNotBlank() }?.let { " · $it 上课" }.orEmpty(),
                    fontSize = 12.sp,
                    color = accent,
                )

                if (conflicts.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    ConflictNotice(conflicts)
                }

                Spacer(Modifier.height(20.dp))

                CampusButton(
                    text = if (isEditing) "保存修改" else "保存课程",
                    onClick = {
                        touched = true
                        if (name.isBlank()) return@CampusButton
                        val safeRoom = room.trim().ifBlank { CourseRepository.ROOM_PLACEHOLDER }
                        val safeWeeks = weeks.trim()
                        if (editingId != null) {
                            // ★ 走 update 而不是"删了再加"：换了 id，课表页卡片高亮、
                            //   详情浮层、冲突角标共用的那个稳定键就断了
                            CourseRepository.update(
                                id = editingId,
                                name = name.trim(),
                                teacher = teacher.trim(),
                                room = safeRoom,
                                weekday = weekday,
                                startPeriod = startPeriod,
                                periodCount = periodCount,
                                weeks = safeWeeks,
                            )
                        } else {
                            CourseRepository.add(
                                name = name.trim(),
                                teacher = teacher.trim(),
                                room = safeRoom,
                                weekday = weekday,
                                startPeriod = startPeriod,
                                periodCount = periodCount,
                                weeks = safeWeeks,
                            )
                        }
                        onSaved()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 冲突警示条。
 *
 * **只提醒、不阻止保存**：文案里明写「仍可保存」。
 * 冲突的课表可能是用户故意的（两门课先都加上、回头再退一门），
 * App 没有资格替他决定该留哪一门 —— 它只该把"这跟哪门撞了"说清楚。
 *
 * 最多列 3 条：再多会把面板撑得很长，而第 4 条之后的增量信息很小。
 */
@Composable
private fun ConflictNotice(conflicts: List<CourseConflict>) {
    val error = MaterialTheme.colorScheme.error
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(error.copy(alpha = 0.10f))
            .padding(12.dp),
    ) {
        Text(
            text = "有 ${conflicts.size} 处冲突（仍可保存）",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = error,
        )
        conflicts.take(3).forEach { conflict ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = conflict.summary(),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = error,
            )
        }
        if (conflicts.size > 3) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "…还有 ${conflicts.size - 3} 处",
                fontSize = 12.sp,
                color = error,
            )
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun FormInput(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    isError: Boolean = false,
) {
    val fieldTint = LocalGlassTokens.current.fieldTint
    Column {
        FormLabel(label)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = isError,
            placeholder = { Text(placeholder, fontSize = 15.sp) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                errorBorderColor = MaterialTheme.colorScheme.error,
                // 输入框底色走 fieldTint 令牌：与面板底色明确拉开一档，
                // 否则字段会"消失"在玻璃面板里（半透明叠加也会显得糊/透）
                focusedContainerColor = LocalGlassTokens.current.fieldTint,
                unfocusedContainerColor = LocalGlassTokens.current.fieldTint,
            ),
        )
    }
}
