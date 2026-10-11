package com.tof.manycourse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.ui.AppIcons
import com.tof.manycourse.ui.rememberAnimationsEnabled
import com.tof.manycourse.ui.tapSpring
import com.tof.manycourse.ui.theme.LocalGlassTokens

/**
 * 玻璃卡片容器（静态玻璃质感，零逐帧成本；设计规范：12dp 圆角、高光描边）。
 * 底色、描边、阴影、高光、色感全部来自 GlassTokens，随"高斯模糊 / 液态玻璃"切换。
 *
 * 带**按压回弹**（`0.96` + 弹性）：卡片的用途多是"点开详情"，按下去有反馈才知道点到了。
 * 缩放在绘制阶段完成（见 [tapPressScale]），不触发重组/重新布局，也不会挤动相邻卡片。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Int = 12,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .glassSurface(
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
    /** true = **不亮**：课照旧列在这里，但今天不上（放假那天）—— 见 [dimTitle] */
    dim: Boolean = false,
    rowHeight: Dp? = null,
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
    dim = dim,
    rowHeight = rowHeight,
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
 * @param dim true = 这一门**不亮**（放假那天：课照旧列出，但今天不上）
 * @param rowHeight 这一行有多高（课表页按屏幕算出来；null = 不按行高自适应），
 *   各档画什么见 [courseCardTier] 上面那张表
 */
@Composable
fun CourseCard(
    course: CourseEntry,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    metaText: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    dim: Boolean = false,
    rowHeight: Dp? = null,
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
        dim = dim,
        rowHeight = rowHeight,
    )

    is CourseEntry.Local -> CourseCard(
        course = course.course,
        modifier = modifier,
        highlighted = highlighted,
        metaText = metaText,
        onClick = onClick,
        onLongClick = onLongClick,
        dim = dim,
        rowHeight = rowHeight,
    )
}

/**
 * 课程卡片的排版**按行高自适应**（课表页把整屏高度按课程张数分给每一行，见 `ui/ScheduleScreen.kt`）。
 *
 * ## 为什么排版要跟着"行有多高"变
 *
 * 一行的高度是**先算出来**的（屏幕剩多少 ÷ 课程张数），不是内容撑出来的。
 * 内容比行高多一点点，用户看到的就是被裁掉半截的字 —— 那比少显示一项更糟。
 * 所以按行高退档：先拿**最不重要**的那一项去换空间。
 *
 * ## 三档（阈值全部由**实测的字行高**推出来，见 [courseCardTier]）
 *
 * | 档 | 行高 | 课名 | 「时间 · 地点」行 | 节次行 |
 * |---|---|---|---|---|
 * | [CourseCardTier.Roomy] | 放得下两行课名 | **最多两行**，超出省略 | 有 | 有 |
 * | [CourseCardTier.Regular] | 放得下一行课名 + 时间地点 + 节次 | 一行，超出省略 | 有 | 有 |
 * | [CourseCardTier.Compact] | 只放得下课名 + 节次 | 一行，超出省略 | **整行撤掉** | 有 |
 *
 * ★ 撤的是「时间 · 地点」**整行**，而不是把字号调小、也不是把三项挤在一起：
 * 课表页看的是"今天第几节上什么课"，教室是细节（同一天的课通常还在同一个校区）；
 * 而那个行里同时装着**上课时刻**，字挤到看不清时两样都白搭 ——
 * 宁可少显示一项，也不要三项都看不清。
 *
 * ## 为什么阈值不写死 dp
 *
 * 字号是 sp，**系统字号调大的机器上行高会跟着变大**：阈值写死的话，
 * 大字体下"两行课名"就会被卡片下沿裁掉半截。所以门槛一律用组合时实测的
 * `sp.toDp()` 现算（与 `CalendarGridLayout.nameLineCount` 同一套做法）。
 */
private val CourseCardPaddingFull = 16.dp
private val CourseCardPaddingCompact = 10.dp
private val CourseCardClockIconSize = 16.dp
private val CourseCardNameLineHeight = 22.sp
private val CourseCardClockLineHeight = 20.sp
private val CourseCardMetaLineHeight = 18.sp
private val CourseCardNameGapFull = 4.dp
private val CourseCardNameGapCompact = 2.dp
private val CourseCardClockGapFull = 8.dp
private val CourseCardClockGapCompact = 2.dp

/** 卡片按行高分档；各档画什么见上面那张表 */
internal enum class CourseCardTier { Roomy, Regular, Compact }

/** 常规排版（课名一行 + 「时间 · 地点」行 + 节次行）需要的高度 */
internal fun courseCardRegularHeight(nameLine: Dp, clockLine: Dp, metaLine: Dp): Dp =
    CourseCardPaddingFull * 2 + nameLine + CourseCardNameGapFull + clockLine +
        CourseCardClockGapFull + metaLine

/**
 * 紧凑排版（课名一行 + 节次行，「时间 · 地点」整行撤掉）需要的高度。
 *
 * 也就是**卡片还读得出来**的最小行高：调用方（课表页）拿它当行高的下限，
 * 压到它还是放不下才让列表自己内部滚。
 */
internal fun courseCardCompactHeight(nameLine: Dp, metaLine: Dp): Dp =
    CourseCardPaddingCompact * 2 + nameLine + CourseCardNameGapCompact + metaLine

/**
 * 行高落在哪一档（纯判定，`CourseCardHeightTest` 钉住）。
 *
 * Roomy 的门槛是"常规排版 + **一整行**课名"：两行课名必须整行算得出来才允许折行，
 * 否则第二行会被卡片下沿裁掉半截 —— 那正好是这套自适应要避免的事。
 */
internal fun courseCardTier(rowHeight: Dp, nameLine: Dp, clockLine: Dp, metaLine: Dp): CourseCardTier {
    val regular = courseCardRegularHeight(nameLine, clockLine, metaLine)
    return when {
        rowHeight >= regular + nameLine -> CourseCardTier.Roomy
        rowHeight >= regular -> CourseCardTier.Regular
        else -> CourseCardTier.Compact
    }
}

/**
 * 课表页"一行课程"能用的高度区间。
 *
 * @param min 卡片**还读得出来**的最小高度（紧凑档的实高）：调用方拿它当行高下限，
 *   压到它仍放不下才让列表自己内部滚
 * @param max 行高上限 = 课名两行 + 时间地点 + 节次的自然高度：到这儿卡片正好填满，
 *   再高就只是给一张卡片加空壳
 */
internal data class CourseCardRowBounds(val min: Dp, val max: Dp)

/**
 * 按**当前字体度量**算出行高区间（与卡片内部分档共用同一批常量，所以两者不会漂移）。
 *
 * 传 `LocalDensity.current` 进来而不是自己去读：这一层要能在组合之外被调用，
 * 也方便日后单独喂一组度量做验证。
 */
internal fun courseCardRowBounds(density: Density): CourseCardRowBounds {
    val nameLine = with(density) { CourseCardNameLineHeight.toDp() }
    val clockLine = maxOf(CourseCardClockIconSize, with(density) { CourseCardClockLineHeight.toDp() })
    val metaLine = with(density) { CourseCardMetaLineHeight.toDp() }
    return CourseCardRowBounds(
        min = courseCardCompactHeight(nameLine, metaLine),
        max = courseCardRegularHeight(nameLine, clockLine, metaLine) + nameLine,
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
 * @param dim **不亮**：这一门今天不上（放假那天）。课**照旧列在课表上**，只是整张卡
 *   走灰调 —— 强调条去掉颜色、文字压暗。为什么不是"不显示"：卡片一消失，
 *   用户看到的是"课表空了"，分不清是放假、同步挂了、还是自己没选课；
 *   灰着则一眼就知道"这天排了课、但今天不上"（与周视图那条
 *   「亮 = 本周会上 · 灰 = 本周不上」是同一套语言）。
 * @param onClick 点击 → 打开详情；传 null 时卡片不可点
 * @param rowHeight 这一行**有多高**（调用方按屏幕算出来，见 `ui/ScheduleScreen.kt`）。
 *   传 null = 不按行高自适应，就画今天这一套（课名一行 + 时间地点 + 节次）——
 *   日历页月视图那条当日课程列表走的就是这条默认路。
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
    dim: Boolean = false,
    rowHeight: Dp? = null,
) {
    val density = LocalDensity.current
    val nameLine = with(density) { CourseCardNameLineHeight.toDp() }
    // 「时间 · 地点」那一行的高度：时钟图标与文字谁高听谁的
    val clockLine = maxOf(CourseCardClockIconSize, with(density) { CourseCardClockLineHeight.toDp() })
    val metaLine = with(density) { CourseCardMetaLineHeight.toDp() }
    val tier = rowHeight?.let { courseCardTier(it, nameLine, clockLine, metaLine) }
        ?: CourseCardTier.Regular
    val compact = tier == CourseCardTier.Compact
    val innerPadding = if (compact) CourseCardPaddingCompact else CourseCardPaddingFull

    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    // 「不亮」的三处灰调：强调条去色、标题压暗、副行更淡。
    // 玻璃底与布局一个字不改 —— 亮与不亮是**同一张卡的两个面貌**，位置与大小完全一致
    val accentTint = if (dim) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f) else accent
    val titleColor = if (dim) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val subColor = if (dim) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .tapPressScale(interactionSource)
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
        // 左侧强调条（颜色按课程名哈希实时计算；不亮时去色）
        Box(
            Modifier
                .width(if (highlighted) CardAccentWidthHighlighted else CardAccentWidth)
                .fillMaxHeight()
                .background(accentTint)
        )
        Column(
            Modifier
                // 行高比内容高时会多出一点空间（比如封了顶的行高）——
                // 上下居中，免得看起来像"字挤在卡片上半截、下半截空着"
                .align(Alignment.CenterVertically)
                .padding(start = innerPadding, top = innerPadding, end = innerPadding, bottom = innerPadding)
                .weight(1f)
        ) {
            Text(
                text = name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = CourseCardNameLineHeight,
                color = titleColor,
                // 只有"整行课名也算得出来"的那一档才允许折成两行，见 courseCardTier
                maxLines = if (tier == CourseCardTier.Roomy) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(if (compact) CourseCardNameGapCompact else CourseCardNameGapFull))
            // 紧凑档整行撤掉：这一行同时装着上课时刻与教室，挤窄了哪一样都看不清
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.Clock,
                        contentDescription = null,
                        tint = subColor,
                        modifier = Modifier.size(CourseCardClockIconSize),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = listOf(startTime, room).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 15.sp,
                        color = subColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(if (compact) CourseCardClockGapCompact else CourseCardClockGapFull))
            Text(
                text = metaText.orEmpty(),
                fontSize = 12.sp,
                lineHeight = CourseCardMetaLineHeight,
                color = subColor.copy(alpha = 0.85f),
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
    val interactionSource = remember { MutableInteractionSource() }
    // 选中态由 tween 换 spring：手指点下去的一瞬间有回弹，才像"按到了一个开关"。
    // 这是**颜色**过渡（组合阶段读），chip 只有 32dp 高、一屏最多七八个，逐帧重组无碍
    val background by animateColorAsState(
        targetValue = if (selected) tokens.accent else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = chipSelectSpring(),
        label = "chipBackground",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
        animationSpec = chipSelectSpring(),
        label = "chipLabel",
    )
    Box(
        modifier = modifier
            .height(32.dp)
            .tapPressScale(interactionSource)
            .clip(RoundedCornerShape(9999.dp))
            .background(background)
            .combinedClickableCompat(interactionSource, onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = labelColor,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.combinedClickable(
    interactionSource = interactionSource,
    indication = null,
    onClick = onClick,
)

/**
 * 主按钮（规范：44dp 高、12dp 圆角、实心蓝）。
 *
 * 按下反馈是**缩放到 0.96 并弹性回弹**，不是 Material 那圈涟漪 ——
 * 理由与 [CourseCard] 相同：默认涟漪在这种玻璃界面上会褪成一块灰罩。
 * 这里把 `interactionSource` 交给 [tapPressScale]，Button 自己仍用它做涟漪/水波状态，
 * 我们只是不再把涟漪画出来而已（`colors` 里已经是不透明主色，灰罩本来也看不见）。
 */
@Composable
fun CampusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        // 缩放挂在 Button 自己的 modifier 上：只影响绘制，不改布局（按钮不会顶开旁边的控件）
        modifier = modifier
            .height(44.dp)
            .tapPressScale(interactionSource),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
        ),
        interactionSource = interactionSource,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * **按压反馈**：按下时缩到 [PressedScale]，松手弹回 1。
 *
 * ## 三条不能破的约束
 *
 * 1. **只在绘制阶段读动画值**：`graphicsLayer { }` 的 lambda 在绘制阶段执行，
 *    动画每一帧既不触发重组、也不重新布局 —— 卡片不会被挤动、行高不变
 *    （§2.5 的硬规矩 1）。
 * 2. **系统关动画时不挂**：[Motion.enabled] 为 false 时直接给 1f，
 *    连 `animateFloatAsState` 的目标值都只有 1f，不会有任何中间帧。
 * 3. **不做位移、不做旋转**：只有缩放。这个项目的动效基调是克制，
 *    整块移动已经被 §2.5 否决过（"看起来像在抖"）。
 *
 * @param interactionSource 调用方自己的交互源。传同一个给 `clickable`，
 *   按下事件才会传到这里；分开建两个的话按下去永远不缩。
 */
@Composable
private fun Modifier.tapPressScale(interactionSource: MutableInteractionSource): Modifier {
    val animations = rememberAnimationsEnabled()
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressScaleEnabled(animations, pressed)) PressedScale else 1f,
        // 系统关动画时连弹性都不必挂：直接给一个瞬时 spec
        animationSpec = if (animations) tapSpring() else tween(durationMillis = 0),
        label = "tapPressScale",
    )
    return this.graphicsLayer {
        scaleX = pressScale
        scaleY = pressScale
    }
}

private fun pressScaleEnabled(animations: Boolean, pressed: Boolean): Boolean = animations && pressed

/**
 * 卡片 / 按钮按下时的缩放。
 *
 * 0.96：比原先的 0.98 更"按得下去"一点（0.98 在小卡片上几乎看不出来），
 * 又不至于让卡片看起来在躲手指。
 */
private const val PressedScale = 0.96f

/**
 * chip / 开关选中态的弹性。
 *
 * 比 [tapSpring] 稍硬一档（`LowBouncy`）：它是**颜色**过渡，
 * 颜色回弹过头会看起来像"闪了一下"，取一点余韵即可。
 *
 * 返回 `FiniteAnimationSpec<Color>` 而不是泛型：`spring()` 的返回类型由
 * `visibilityThreshold` 的类型定，颜色在这里给 1/255 一档（肉眼分辨不出的一级色阶）。
 */
private fun chipSelectSpring(): FiniteAnimationSpec<Color> = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = Color(0f, 0f, 0f, 1f / 255f),
)
