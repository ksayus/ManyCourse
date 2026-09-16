package com.tof.manycourse.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.weekdayLabel
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.components.GlassOverlay
import com.tof.manycourse.ui.components.GlassPanel
import com.tof.manycourse.ui.theme.LocalGlassTokens
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * **课程详情卡片**（点击课程卡片 / 网格格子后弹出的浮层）。
 *
 * ## 结构（对齐参考设计）
 *
 * 列的是**那一天有什么课、按节次排**（不是"点中的那一格是什么课"）：
 * 头部胶囊写清是哪一天、共几门，下面一门一张卡。
 *
 * ```
 * ┌──────────────────────────────────────────┐
 * │    ( 9月14日 周一 · 共 3 门课 )       (×) │  ← 小胶囊：哪天 · 几门课
 * │              课程详情                     │  ← 居中标题
 * │  ┌────────────────────────────────────┐  │
 * │  │ ▍高等数学  ⧉                        │  │  ← 色条 + 彩色课名 + 复制
 * │  │  任课教师          全部周次          │  │  ← 两列「标签 / 值」
 * │  │  王建国 ⧉          3-5,8-20周        │  │
 * │  │  上课时间                            │  │  ← 长值独占一行
 * │  │  第1-2节 · 8:00~9:40                │  │
 * │  │  教室安排                            │  │
 * │  │  A1N403 禄口 ⧉                       │  │
 * │  │  来源                                │  │
 * │  │  教务系统 · 周次课表（这一周真的会上） │  │
 * │  └────────────────────────────────────┘  │
 * │  ──────────────────────────────────────  │  ← 课与课之间一条淡分隔线
 * │  ┌────────────────────────────────────┐  │
 * │  │ ▍大学英语  ⧉   …                    │  │
 * └──────────────────────────────────────────┘
 * ```
 *
 * 「那一天有什么课」由**调用方**决定（周视图给那一列的课、月历给选中那天的课、
 * 课表页给当前选中的那天），口径都在各自页面上与列表/网格**完全一致**；
 * 这里只负责按节次排序后照着画（[orderedDayCourses]）。
 *
 * ## 三条不变的老规矩
 *
 * 1. **值取不到就不显示那一格**（手加的课没有教师、没有周次），不写"暂无"；
 * 2. 取值全部收在纯函数 [courseDetailOf] 里（可单测，`CourseDetailTest` 守着），
 *    两种来源（教务系统按周给的 / 本地课表里的）的口径差异只写一遍；
 * 3. 面板用 [GlassPanel]（静态高不透明度表面，可读性优先），返回键 / 点遮罩 / 右上角 ×
 *    都能关；进出场动画由 [GlassOverlay] 统一处理。
 *
 * @param visible 是否显示。**和 [courses] 分开传**是必要的：关闭时内容要留着，
 *   退出动画那 200ms 才有东西可画（否则面板会先"空掉"再淡出）。
 * @param date 这一组课是哪一天的（头部胶囊用；null = 只有一组课、说不清哪天）
 * @param courses 那一天要展示的课；空 = 那天没课 / 还没点过任何卡片（此时整个浮层不组合）
 */
@Composable
fun CourseDetailDialog(
    visible: Boolean,
    date: LocalDate?,
    courses: List<CourseEntry>,
    onDismiss: () -> Unit,
) {
    if (courses.isEmpty()) return
    val details = orderedDayCourses(courses).map { courseDetailOf(it) }

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
                DetailHeader(
                    badge = courseDetailBadge(date, details.size),
                    onDismiss = onDismiss,
                )

                Spacer(Modifier.height(18.dp))

                details.forEachIndexed { index, detail ->
                    if (index > 0) {
                        Spacer(Modifier.height(14.dp))
                        // 多门课之间一条淡分隔线（参考图里两张卡之间那条）
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    CourseDetailBlock(detail)
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** 头部：居中的小胶囊（周几 + 第几节）+ 居中标题 + 右上角圆形关闭 */
@Composable
private fun DetailHeader(badge: String, onDismiss: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Box(Modifier.fillMaxWidth()) {
        Column(
            // 左右留出关闭按钮的位置，标题才不会顶到它
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = badge,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(9999.dp))
                    .background(tokens.accent.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "课程详情",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Icon(
            imageVector = AppIcons.Close,
            contentDescription = "关闭",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onDismiss)
                .padding(8.dp),
        )
    }
}

/** 一门课一张卡：色条 + 彩色课名 + 复制，下面是两列"标签 / 值"明细 */
@Composable
private fun CourseDetailBlock(detail: CourseDetail) {
    val context = LocalContext.current
    // 复制反馈：点了复制在卡片底部一句"已复制"，1.5 秒后自己消失
    // （Android 13+ 系统自己也会弹一条确认，两条不冲突，只是各自服务不同的注意力）
    var copiedLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            delay(1500)
            copiedLabel = null
        }
    }
    val copy: (String, String) -> Unit = { label, value ->
        // 用系统剪贴板而不是 Compose 的 ClipboardManager：后者在 Compose 1.8 起已废弃
        // （架构指南 R12：不引入废弃 API）
        context.copyToClipboard(value)
        copiedLabel = label
    }

    GlassCard(cornerRadius = 14, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(detail.accent)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = detail.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                    color = detail.accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                CopyIcon(onClick = { copy("课名", detail.name) })
            }

            Spacer(Modifier.height(14.dp))

            DetailFields(
                fields = detail.fields,
                onCopy = { field -> copy(field.label, field.value) },
            )

            if (copiedLabel != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "已复制${copiedLabel}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 两列明细：`full = true` 的值独占一行（上课时间 / 教室安排 / 来源这种长值），
 * 其余两两配对。左列没配到对时留空，右边的值不会被拉宽 —— 两列的列宽在整张卡里是稳定的。
 */
@Composable
private fun DetailFields(
    fields: List<DetailField>,
    onCopy: (DetailField) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var index = 0
        while (index < fields.size) {
            val field = fields[index]
            if (field.full) {
                DetailFieldCell(field = field, onCopy = onCopy, modifier = Modifier.fillMaxWidth())
                index += 1
            } else {
                val paired = fields.getOrNull(index + 1)?.takeIf { !it.full }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DetailFieldCell(
                        field = field,
                        onCopy = onCopy,
                        modifier = Modifier.weight(1f),
                    )
                    if (paired != null) {
                        DetailFieldCell(
                            field = paired,
                            onCopy = onCopy,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
                index += if (paired != null) 2 else 1
            }
        }
    }
}

/** 一格明细：小号标签 + 值（可复制的值走链接色，右侧一个复制图标） */
@Composable
private fun DetailFieldCell(
    field: DetailField,
    onCopy: (DetailField) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = field.label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = field.value,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = if (field.copyable) {
                    LocalGlassTokens.current.accent
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            if (field.copyable) {
                Spacer(Modifier.width(4.dp))
                CopyIcon(onClick = { onCopy(field) })
            }
        }
    }
}

/** 复制小图标：16dp，点一下把对应的值写进剪贴板 */
@Composable
private fun CopyIcon(onClick: () -> Unit) {
    Icon(
        imageVector = AppIcons.Copy,
        contentDescription = "复制",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(2.dp),
    )
}

/** 把一段文字写进系统剪贴板（列表页/详情卡共用） */
private fun Context.copyToClipboard(text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("课多多", text))
}

/**
 * 详情卡片里的一项明细。
 *
 * @param full true = 独占一行（值较长：上课时间 / 教室 / 来源）
 * @param copyable true = 值可复制（按链接色显示 + 右侧复制图标）
 */
internal data class DetailField(
    val label: String,
    val value: String,
    val full: Boolean = false,
    val copyable: Boolean = false,
)

/**
 * 详情卡片要展示的**一门课**（来源已经归一过）。
 *
 * @param accent 左侧强调条与课名的颜色，取 `CourseRepository.colorOfName(课名)` ——
 *   同一门课在课表页、日历页、月历圆点、网格卡片和这张详情卡上永远同一个颜色
 * @param fields 展示用的明细，顺序即渲染顺序（两列配对见 [DetailFields]）
 */
internal data class CourseDetail(
    val name: String,
    val accent: Color,
    val fields: List<DetailField>,
)

/**
 * 详情卡里那一天的课**按节次排好序**。
 *
 * 纯函数：排序放在这里而不是各页面各排一遍 —— "按顺序显示"是这张卡的约定，
 * 不能因为某个页面的数据恰好有序就以为到处都有序（手加的课是拼在周次课后面的）。
 */
internal fun orderedDayCourses(courses: List<CourseEntry>): List<CourseEntry> =
    courses.sortedBy { it.startPeriod }

/**
 * 头部小胶囊文案：`9月14日 周一 · 共 3 门课`。
 *
 * @param date 那一天的日期；null = 说不清哪天（只有一组课），此时只写门数
 */
internal fun courseDetailBadge(date: LocalDate?, count: Int): String = buildString {
    if (date != null) {
        append("${date.monthValue}月${date.dayOfMonth}日 ")
        append(weekdayLabel(date.dayOfWeek.value))
        append(" · ")
    }
    append("共 $count 门课")
}

/**
 * 把两种课（教务系统按周给的 / 本地课表里的）归一成 [CourseDetail]。
 *
 * 纯函数（不碰任何状态）所以能直接单测：以前这种"哪种来源显示什么"的判断散在 UI 里，
 * 一改就会漏掉另一条路径（比如按周查的课显示不出教师）。
 *
 * 值为空的字段**直接不产出**（手加的课没有教师/周次，就不该出现那一格）。
 */
internal fun courseDetailOf(entry: CourseEntry): CourseDetail {
    val name: String
    val teacher: String
    val room: String
    val weeks: String
    val source: String

    when (entry) {
        is CourseEntry.Week -> {
            name = entry.course.name
            teacher = entry.course.teacher
            // 校区拼在教室后面（如「A1N403 禄口」）—— 和卡片上的口径一致
            room = entry.course.fullRoom
            weeks = entry.course.weeks
            source = "教务系统 · 周次课表（这一周真的会上）"
        }

        is CourseEntry.Local -> {
            name = entry.course.name
            teacher = entry.course.teacher
            room = entry.course.room
            weeks = entry.course.weeks
            source = if (entry.course.fromSchool) {
                "教务系统 · 整学期课表（按每周循环显示）"
            } else {
                "手动添加"
            }
        }
    }

    val timeRange = CourseRepository.periodRangeLabel(entry.startPeriod, entry.periodCount)
    // 节次 + 起止时刻合成一格："第3-4节 · 10:10~11:50"
    val classTime = listOf(entry.periodLabel, timeRange)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    return CourseDetail(
        name = name,
        accent = CourseRepository.colorOfName(name),
        fields = listOfNotNull(
            teacher.takeIf { it.isNotBlank() }?.let { DetailField("任课教师", it, copyable = true) },
            weeks.takeIf { it.isNotBlank() }?.let { DetailField("全部周次", it) },
            classTime.takeIf { it.isNotBlank() }?.let { DetailField("上课时间", it, full = true) },
            room.takeIf { it.isNotBlank() }?.let {
                DetailField("教室安排", it, full = true, copyable = true)
            },
            DetailField("来源", source, full = true),
        ),
    )
}
