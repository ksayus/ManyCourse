package com.tof.manycourse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.components.GlassHeader
import com.tof.manycourse.ui.components.LiquidGlassBackground
import com.tof.manycourse.ui.theme.GlassMode
import com.tof.manycourse.ui.theme.LocalGlassMode
import com.tof.manycourse.ui.theme.LocalGlassTokens

/**
 * **设置页**（从「我的」页打开的独立全屏页）。
 *
 * ## 为什么是"页"而不是"卡片"
 *
 * 这些开关原来直接铺在「我的」页上（个人信息、统计、玻璃风格、日历布局、通知、账号一长条），
 * 页面越加越长，"我的"该干的事（看自己是谁、看统计）反而被设置项挤到看不见。
 * 拆开之后：**「我的」放"我是谁/我有什么"，设置放"怎么显示"**，两边都清爽。
 *
 * ## 实现方式
 *
 * 项目没有 Navigation 组件（Fragment 手工 hide/show，见架构指南 §1），所以这里沿用
 * 项目里"浮层常驻组合 + visible 控制"的既有套路（与 `AddCourseScreen` 一致）：
 *  - 全屏页，从右侧滑入 + 淡入（比"弹窗"更像"翻页"）；
 *  - **返回键可关**（[BackHandler]），关的是这一页而不是退出应用；
 *  - 页面自带 `hazeSource` 背景 + [GlassHeader]（带「返回」），与四个 Tab 页同一套视觉语言；
 *  - 背景不驱动动画（`animate = false`）：静止页面零逐帧成本（架构指南 §5）。
 */
@Composable
fun SettingsScreen(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = visible, onBack = onDismiss)

    val animations = rememberAnimationsEnabled()
    val enter = remember(animations) {
        if (animations) {
            fadeIn(enterTween()) + slideInHorizontally(enterTween()) { width -> width / 8 }
        } else {
            EnterTransition.None
        }
    }
    val exit = remember(animations) {
        if (animations) {
            fadeOut(exitTween()) + slideOutHorizontally(exitTween()) { width -> width / 8 }
        } else {
            ExitTransition.None
        }
    }

    AnimatedVisibility(visible = visible, enter = enter, exit = exit) {
        val hazeState = remember { HazeState() }
        Box(Modifier.fillMaxSize()) {
            // 页面自己的背景（铺满，遮住「我的」页）
            Box(Modifier.fillMaxSize().hazeSource(state = hazeState)) {
                LiquidGlassBackground(animate = false)
            }
            Column(Modifier.fillMaxSize()) {
                GlassHeader(
                    hazeState = hazeState,
                    title = "设置",
                    leading = { BackButton(onDismiss) },
                )
                Box(
                    Modifier
                        .weight(1f)
                        // 横屏 / 异形屏：内容避开侧边挖孔与圆角（竖屏为 0，无影响）
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                        )
                ) {
                    SettingsContent()
                }
            }
        }
    }
}

/** 「返回」：设置页是独立页，页头左侧给一个明确的退出口（右滑手势之外还能点） */
@Composable
private fun BackButton(onBack: () -> Unit) {
    Icon(
        imageVector = AppIcons.ChevronLeft,
        contentDescription = "返回",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onBack)
            .padding(8.dp),
    )
}

@Composable
private fun SettingsContent() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SectionLabel("外观与布局")
        GlassModeCard()
        Spacer(Modifier.height(12.dp))
        CalendarLayoutCard()

        Spacer(Modifier.height(20.dp))

        SectionLabel("通知")
        NotificationCard()

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** 玻璃风格切换卡：全局统一样式令牌，切换后立即重组生效并写入偏好 */
@Composable
private fun GlassModeCard() {
    val current = LocalGlassMode.current
    GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "外观 · 玻璃风格",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "全局生效，选择会被记住",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassMode.entries.forEach { mode ->
                    GlassModeOption(
                        mode = mode,
                        selected = mode == current,
                        onClick = { UiSettings.setGlassMode(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "当前：${current.label} · ${current.summary}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GlassModeOption(
    mode: GlassMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalGlassTokens.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else Color.Transparent
            )
            .border(
                width = 1.dp,
                color = if (selected) tokens.accent else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = mode.label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) tokens.accent else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = mode.summary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 日历布局开关：日历页有两套布局，这里切换。
 *
 * ```
 * 月（默认）= 月历 + 当日课程列表
 * 周        = 日程网格：时间轴为骨架、二维网格为容器、课程卡片为载体
 * ```
 *
 * 两种布局**只差渲染方式**：取值都是 `coursesOfDate`，所以开关一翻，
 * 同一个日期上的课一门不多、一门不少（详见 `ui/CalendarGridLayout.kt`）。
 * 与日历页页头的「周/月」分段切换是**同一个状态**，两处永远一致。
 */
@Composable
private fun CalendarLayoutCard() {
    val gridLayout by UiSettings.calendarGridLayout
    SettingSwitchCard(
        title = "日历布局",
        subtitle = if (gridLayout) "周视图 · 时间轴 + 课程卡片" else "月视图 · 月历 + 当日列表",
        hint = "在「日历」页立即生效，选择会被记住",
        checked = gridLayout,
        switchDesc = "日历网格布局开关",
        onCheckedChange = { UiSettings.setCalendarGridLayout(it) },
    )
}

/** 课前提醒开关（持久化；真正的推送还没接，文案里不吹） */
@Composable
private fun NotificationCard() {
    val enabled by UiSettings.notificationsEnabled
    SettingSwitchCard(
        title = "通知设置",
        subtitle = if (enabled) "课前 15 分钟推送提醒" else "已关闭，不再推送提醒",
        hint = "当前只记住开关状态，推送逻辑见后续版本",
        checked = enabled,
        switchDesc = "通知提醒开关",
        onCheckedChange = { UiSettings.setNotificationsEnabled(it) },
    )
}

/**
 * 「标题 + 说明 + 开关」的一张卡片（设置页里两个开关共用）。
 *
 * 开关带 `contentDescription`：设置页里开关不止一个，
 * "第几个 Switch"不是稳定的定位方式，无障碍朗读与真机测试（`GlassUiTest`）都靠这个名字。
 */
@Composable
private fun SettingSwitchCard(
    title: String,
    subtitle: String,
    hint: String,
    checked: Boolean,
    switchDesc: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hint,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.semantics { contentDescription = switchDesc },
            )
        }
    }
}
