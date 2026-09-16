package com.tof.manycourse.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.ui.components.CampusButton
import com.tof.manycourse.ui.components.CampusChip
import com.tof.manycourse.ui.components.GlassOverlay
import com.tof.manycourse.ui.components.GlassPanel
import com.tof.manycourse.ui.theme.LocalGlassTokens

private val WeekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 添加课程（本地数据）：全屏玻璃表单浮层。
 *
 * 面板走 [GlassPanel]（静态高不透明度表面：可读性优先 + 进出场无闪帧），
 * 进出场动画、返回键关闭、点击遮罩关闭由 [GlassOverlay] 统一处理。
 * 时间不用手填，由“开始节次”实时计算。
 */
@Composable
fun AddCourseScreen(
    visible: Boolean,
    initialWeekday: Int,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var weekday by remember { mutableIntStateOf(initialWeekday.coerceIn(1, 7)) }
    var startPeriod by remember { mutableIntStateOf(1) }
    var periodCount by remember { mutableIntStateOf(2) }
    var touched by remember { mutableStateOf(false) }

    val nameError = touched && name.isBlank()
    val accent = LocalGlassTokens.current.accent

    // 浮层常驻组合（为了播退出动画），每次打开时重置表单，避免残留上次输入
    LaunchedEffect(visible) {
        if (visible) {
            name = ""
            teacher = ""
            room = ""
            weekday = initialWeekday.coerceIn(1, 7)
            startPeriod = 1
            periodCount = 2
            touched = false
        }
    }

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
                        text = "添加课程",
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

                // 实时计算预览：周几 · 第几节 · 几点上课
                val endPeriod = (startPeriod + periodCount - 1).coerceAtMost(CourseRepository.MAX_PERIOD)
                Text(
                    text = "${WeekdayLabels[weekday - 1]} · 第${startPeriod}-${endPeriod}节 · ${CourseRepository.periodTime(startPeriod)} 上课",
                    fontSize = 12.sp,
                    color = accent,
                )

                Spacer(Modifier.height(20.dp))

                CampusButton(
                    text = "保存课程",
                    onClick = {
                        touched = true
                        if (name.isBlank()) return@CampusButton
                        CourseRepository.add(
                            name = name.trim(),
                            teacher = teacher.trim(),
                            room = room.trim().ifBlank { "地点待定" },
                            weekday = weekday,
                            startPeriod = startPeriod,
                            periodCount = periodCount,
                        )
                        onSaved()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
