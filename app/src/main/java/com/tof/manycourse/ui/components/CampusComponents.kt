package com.tof.manycourse.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.ui.AppIcons
import com.tof.manycourse.ui.theme.LocalGlassTokens

/**
 * 玻璃卡片容器（静态玻璃质感，零逐帧成本；设计规范：12dp 圆角、高光描边）。
 * 底色、描边、阴影、高光、色感全部来自 GlassTokens，随"高斯模糊 / 液态玻璃"切换。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 12,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.glassSurface(
            shape = RoundedCornerShape(cornerRadius.dp),
        )
    ) {
        content()
    }
}

/**
 * 玻璃面板容器（弹窗 / 表单专用）：静态高不透明度表面。
 *
 * 之所以不做实时模糊：面板会做进出场缩放/淡入，Haze 的 RenderEffect 在动画图层里
 * 重采样会闪帧；而且面板不透明度本来就高（78%–85%），模糊带来的观感差异很小，
 * 去掉后正文更清晰、进出场更稳、也没有逐帧成本。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 24,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.glassPanel(
            shape = RoundedCornerShape(cornerRadius.dp),
        )
    ) {
        content()
    }
}

/**
 * 课程卡片：玻璃底 + 左侧蓝色强调条（规范：默认 4dp / 高亮 8dp）
 * 点击打开详情浮层，长按可删除课程（只有本地课可删）
 */
@Composable
fun CourseCard(
    course: Course,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    metaText: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) = CourseCard(
    name = course.name,
    room = course.room,
    startTime = course.startTime,
    accent = CourseRepository.colorOf(course),
    modifier = modifier,
    highlighted = highlighted,
    metaText = metaText ?: course.periodLabel,
    onClick = onClick,
    onLongClick = onLongClick,
)

/**
 * 同上一张卡片的**"一天的课"版**（[CourseEntry]）—— **课表页与日历页都用这个**。
 *
 * 两页显示同一天的课时必须长得一模一样，所以入口只留一个：
 * 卡片外观由下面的字段版实现，配色统一走 `CourseRepository.colorOfName(课名)`，
 * 于是同一门课在课表页、日历页、月历圆点里永远是同一个颜色。
 *
 * @param onClick 点击卡片 → 打开课程详情浮层
 * @param onLongClick 只有本地课（[CourseEntry.Local]）才该传 —— 教务系统按周给的课
 *   删不掉（下次查还会回来），给它删除入口只会让用户以为删掉了。
 */
@Composable
fun CourseCard(
    course: CourseEntry,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    metaText: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) = when (course) {
    is CourseEntry.Week -> CourseCard(
        name = course.course.name,
        room = course.course.fullRoom,
        startTime = course.startTime,
        accent = CourseRepository.colorOfName(course.course.name),
        modifier = modifier,
        highlighted = highlighted,
        metaText = metaText ?: course.periodLabel,
        onClick = onClick,
        onLongClick = onLongClick,
    )

    is CourseEntry.Local -> CourseCard(
        course = course.course,
        modifier = modifier,
        highlighted = highlighted,
        metaText = metaText,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/**
 * 同上一张卡片的**字段版**重载。
 *
 * 为什么需要它：教务系统按周拉回来的课（`SchoolCourse`）没有 `Course` 的本地 id
 * （它不属于"用户自己的课表"），但它和本地课必须长得一样。
 * 与其在别处再抄一份卡片样式，不如把卡片拆成"只吃字段"的版本共用。
 *
 * ## 按下反馈为什么不是涟漪
 *
 * `combinedClickable` 的默认涟漪会被 `clip(RoundedCornerShape(12.dp))` 裁成一块
 * **半透明灰色圆角矩形**，正好盖住整张玻璃卡片 —— 玻璃质感全被那层灰罩洗掉了
 * （底部导航栏是同一个问题，见 `GlassBottomNav` 里那段注释）。所以这里 `indication = null`，
 * 按下反馈改成卡片**轻微缩到 98%**：有反馈、又没有那层灰。
 *
 * @param accent 左侧强调条的底色，用 `CourseRepository.colorOfName(课名)` 取
 * @param onClick 点击 → 打开详情；传 null 时卡片不可点
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CourseCard(
    name: String,
    room: String,
    startTime: String,
    accent: Color,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    metaText: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PressedCardScale else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "cardPressScale",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            // 缩放只在绘制阶段做（graphicsLayer 里读 pressScale）：
            // 不触发重新布局，也不会把相邻卡片挤动
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .glassSurface(shape, highlighted = highlighted)
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = onClick != null || onLongClick != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            ),
    ) {
        // 左侧强调条（颜色按课程名哈希实时计算）
        Box(
            Modifier
                .width(if (highlighted) CardAccentWidthHighlighted else CardAccentWidth)
                .fillMaxHeight()
                .background(accent)
        )
        Column(
            Modifier
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp)
                .weight(1f)
        ) {
            Text(
                text = name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.Clock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = listOf(startTime, room).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = metaText.orEmpty(),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 星期筛选 Chip（规范：32dp 高、胶囊圆角、选中实心蓝） */
@Composable
fun CampusChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalGlassTokens.current
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(9999.dp))
            .background(
                if (selected) tokens.accent else MaterialTheme.colorScheme.surfaceVariant
            )
            .combinedClickableCompat(onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

/** 主按钮（规范：44dp 高、12dp 圆角、实心蓝 + 按下变暗由 Button 涟漪承担） */
@Composable
fun CampusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
        ),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 课程卡片按下时的缩放（见 [CourseCard] 的注释：按下反馈用缩放，**不用涟漪**）。
 * 0.98 足够让人感到"按到了"，又不会让卡片看起来在躲手指。
 */
private const val PressedCardScale = 0.98f
