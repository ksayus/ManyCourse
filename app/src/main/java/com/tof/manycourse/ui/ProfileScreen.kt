package com.tof.manycourse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import com.tof.manycourse.data.ProfileRepository
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.data.WeekScheduleStore
import com.tof.manycourse.data.currentWeekCourseCount
import com.tof.manycourse.data.todayCourseCount
import com.tof.manycourse.gr_api.SchoolRegistry
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.theme.LocalGlassMode
import com.tof.manycourse.ui.theme.LocalGlassTokens

/**
 * 「我的」页：个人信息卡片（含编辑入口）+ 实时统计 + **设置入口**。
 *
 * 所有开关（玻璃风格 / 日历布局 / 通知）都搬到了独立的 [SettingsScreen]：
 * 这一页只负责"我是谁、我有什么"，设置页负责"怎么显示"，
 * 否则"我的"会越加越长，把个人信息和统计挤到看不见。
 *
 * @param onOpenSettings 打开设置页（由 [SelfFragment] 承载，返回键可关）
 * @param onEditProfile 打开编辑资料浮层
 */
@Composable
fun ProfileScreen(
    hazeState: HazeState,
    onOpenSettings: () -> Unit,
    onEditProfile: () -> Unit,
) {
    val nickname by ProfileRepository.nickname
    val major by ProfileRepository.major

    // 实时计算统计数据。
    // ★ 必须走 currentWeekCourseCount / todayCourseCount（与课表页、日历页同一个取值函数）：
    //   以前这里是 `CourseRepository.courses.size`，那是**整学期**的课表条数，
    //   和卡片上写的"本周课程"对不上（见 data/CourseEntries.kt 里的注释）。
    val weekCount = currentWeekCourseCount()
    val todayCount = todayCourseCount()

    // 教学周列表可能比课表页晚到；到了就把本周的课补拉回来，否则上面两个数字会偏小。
    // ensureWeek 对已缓存/在飞的周次是空操作，重复调无副作用。
    val weekListSize = WeekScheduleStore.weeks.size
    LaunchedEffect(weekListSize) {
        WeekScheduleStore.currentWeek?.let { WeekScheduleStore.ensureWeek(it.index) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ═══ 个人信息卡片（独立放置，右侧铅笔按钮打开编辑对话框）═══
        GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 头像：首字符 + 蓝色（设计规范：圆形 initials 头像）
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = nickname.firstOrNull()?.toString() ?: "未",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalGlassTokens.current.accent,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = nickname.ifBlank { "未命名" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = major,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 编辑资料入口
                Icon(
                    imageVector = AppIcons.Edit,
                    contentDescription = "编辑资料",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onEditProfile)
                        .padding(8.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ═══ 统计（实时计算）═══
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatBlock(value = weekCount.toString(), label = "本周课程", modifier = Modifier.weight(1f))
            StatBlock(value = todayCount.toString(), label = "今日课程", modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        // ═══ 设置入口：玻璃风格 / 日历布局 / 通知全在那一页里 ═══
        SettingsEntryCard(onOpenSettings)

        Spacer(Modifier.height(16.dp))

        // ═══ 账号卡片：显示当前登录身份 + 退出登录 ═══
        AccountCard()

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 设置入口：一行「齿轮 + 设置 + 摘要 + ›」。
 *
 * 摘要写"当前用的什么"（玻璃风格 / 日历是周视图还是月视图），
 * 这样不进设置页也能一眼知道现在是什么状态。
 */
@Composable
private fun SettingsEntryCard(onOpen: () -> Unit) {
    val tokens = LocalGlassTokens.current
    val glassMode = LocalGlassMode.current
    val gridLayout by UiSettings.calendarGridLayout
    val summary = "玻璃风格：${glassMode.label} · 日历：${if (gridLayout) "周视图" else "月视图"}"

    GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.Settings,
                contentDescription = null,
                tint = tokens.accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "设置",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 账号卡片：把"当前登录的是谁"摆出来，并提供退出登录。
 *
 * 为什么要显示学校和账号：登录态现在会**跨冷启动保留**（不必每次输密码），
 * 界面上不写清楚"以谁的身份在看这些课表"，换人用手机时就很容易串号 ——
 * 学生共用一台手机的场景并不少见。
 */
@Composable
private fun AccountCard() {
    val school = SchoolRegistry.find(SessionStore.schoolId.value)
    val account = SessionStore.account.value
    var confirmLogout by remember { mutableStateOf(false) }
    val context = LocalContext.current

    GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "登录账号",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (SessionStore.isLocalDebug.value) {
                    "本地调试账号（$account），未连接教务系统"
                } else {
                    "${school?.name ?: "未知学校"} · $account"
                },
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "登录状态会保留，下次打开不用重新输入密码",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            // 退出登录：危险动作，用 error 色 + 二次确认，不让人误触
            Text(
                text = "退出登录",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { confirmLogout = true }
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = {
                Text(
                    "退出后将清除本机保存的登录状态和已同步的课表，" +
                        "下次需要重新输入账号密码。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        context.logoutAndBackToLogin()
                    }
                ) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatBlock(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    GlassCard(cornerRadius = 12, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = LocalGlassTokens.current.accent,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
