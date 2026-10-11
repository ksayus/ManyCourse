package com.tof.manycourse.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.IcsExport
import com.tof.manycourse.data.ScheduleImport
import com.tof.manycourse.data.WeekScheduleStore
import com.tof.manycourse.widget.WidgetRefresh
import com.tof.manycourse.ui.components.CampusButton
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.components.GlassHeader
import com.tof.manycourse.ui.components.LiquidGlassBackground
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * **课表导出与导入**（独立页面，从设置页点进来）。
 *
 * ## 为什么导出值得单独做一块
 *
 * 项目不做服务端（README §10：不转发用户数据），所以"手机 / 平板 / 电脑都能看"
 * 不可能靠自建云。但**系统日历天生就是多端的**：导出 `.ics` 丢进系统日历 / Google 日历 /
 * Outlook，多设备同步、锁屏提醒、勿扰模式全都白拿 —— 一行服务端代码都不用写。
 * 这是"多端同步"在**不违背隐私承诺**前提下的唯一正解。
 *
 * ## 导入为什么要两种格式
 *
 * 它们是用户手上真实存在的两个极端：**CSV** 是 Excel / 别的课表软件导出的表格
 * （手工载体），**ICS** 是系统日历的导出格式（换机迁移的正规通道）。
 *
 * ## 反馈必须说清楚"少了什么"
 *
 * 导出会跳过没有作息的课，导入会跳过认不出的行 —— 两处都**逐条列出来**。
 * 只报"成功"，用户会以为课表齐了，然后漏上几节课。
 */
@Composable
fun TransferScreen(visible: Boolean, onDismiss: () -> Unit) {
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
            Box(Modifier.fillMaxSize().hazeSource(state = hazeState)) {
                LiquidGlassBackground(animate = false)
            }
            Column(Modifier.fillMaxSize()) {
                GlassHeader(
                    hazeState = hazeState,
                    title = "导出与导入",
                    leading = { BackButton(onDismiss) },
                )
                Box(
                    Modifier
                        .weight(1f)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                        )
                ) {
                    TransferContent()
                }
            }
        }
    }
}

@Composable
private fun TransferContent() {
    val context = LocalContext.current
    var notice by remember { mutableStateOf("") }
    var problems by remember { mutableStateOf(emptyList<String>()) }

    // 文件选择器：**MIME 一律给 */* **。.ics / .csv 在各家 ROM 上被报成
    // application/octet-stream、text/plain 的都有，按类型过滤会让文件在选择器里变灰点不了
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        if (text == null) {
            notice = "这个文件读不出来（可能没有读取权限）"
            problems = emptyList()
            return@rememberLauncherForActivityResult
        }

        // 按文件内容而不是后缀判断格式：用户从微信/QQ 收到的文件经常没有正确后缀
        val report = if (text.contains("BEGIN:VCALENDAR", ignoreCase = true)) {
            ScheduleImport.ics(text, WeekScheduleStore.weeks.toList(), CourseRepository.timetable)
        } else {
            ScheduleImport.csv(text)
        }
        applyImport(report, context)
        notice = if (report.courses.isEmpty()) {
            "没有导入任何课程"
        } else {
            "已导入 ${report.courses.size} 门课程" + if (report.problems.isEmpty()) "" else "（${report.problems.size} 处被跳过）"
        }
        problems = report.problems
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (notice.isNotBlank()) {
            GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = notice,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    problems.forEach { problem ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "· $problem",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        SectionLabel("导出")
        GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "导出成系统日历能读的 .ics",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "导入系统日历后，多设备同步、锁屏提醒、勿扰模式全都白拿 —— " +
                        "这是不把课表上传到任何服务器就能多端看的办法",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        CampusButton(
            text = "导出 .ics 日历文件",
            onClick = {
                val result = exportIcs(context)
                notice = result.first
                problems = result.second
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        CampusButton(
            text = "导出课表图片",
            onClick = {
                val result = exportImage(context)
                notice = result.first
                problems = result.second
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        SectionLabel("导入")
        GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "支持 CSV 与 ICS",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "CSV 表头（顺序随意、中英文都认）：\n" +
                        "课程名,教师,教室,星期,起始节次,节数,周次\n" +
                        "高等数学,王建国,教学楼 A-101,周一,1,2,1-16周",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "导入进来的课算「你自己加的课」：与教务系统同步的课分开存，可以随时长按删除，" +
                        "也不会被下次同步覆盖",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        CampusButton(
            text = "选择文件导入",
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(32.dp))
    }
}

/** 把导入结果落库；返回是否真的写进去了东西 */
private fun applyImport(report: com.tof.manycourse.data.ImportReport, context: Context) {
    report.courses.forEach { imported ->
        CourseRepository.add(
            name = imported.name,
            teacher = imported.teacher,
            room = imported.room.ifBlank { CourseRepository.ROOM_PLACEHOLDER },
            weekday = imported.weekday,
            startPeriod = imported.startPeriod,
            periodCount = imported.periodCount,
            weeks = imported.weeks,
        )
    }
    if (report.courses.isNotEmpty()) {
        // 课表变了：桌面小组件与课前提醒都要跟着更新（不然要等下次回到主界面才刷）
        WidgetRefresh.updateAll(context)
    }
}

/**
 * 导出 `.ics`。
 *
 * @return (给用户看的一句话, 逐条说明"哪些没导出")
 */
private fun exportIcs(context: Context): Pair<String, List<String>> {
    val courses = CourseRepository.courses.toList()
    if (courses.isEmpty()) return "课表是空的，没有可导出的课" to emptyList()

    val weeks = WeekScheduleStore.weeks.toList()
    val plan = IcsExport.plan(courses, weeks, CourseRepository.timetable)
    if (plan.isEmpty) {
        return "没有任何课程可以导出（见下面的原因）" to reasonsOf(plan)
    }

    // DTSTAMP 按规范必须是 UTC —— 这里给的就是 UTC 的那个"现在"
    val text = IcsExport.calendar(plan, LocalDateTime.now(ZoneOffset.UTC))
    val file = File(exportsDir(context), "manycourse-${stamp()}.ics")
    file.writeText(text, Charsets.UTF_8)

    share(context, file, "text/calendar")
    return "已导出 ${plan.events.size} 条日程（${file.name}）" to reasonsOf(plan)
}

/** 导出课表图片 */
private fun exportImage(context: Context): Pair<String, List<String>> {
    val courses = CourseRepository.courses.toList()
    if (courses.isEmpty()) return "课表是空的，没有可导出的课" to emptyList()

    val current = WeekScheduleStore.currentWeek
    val subtitle = buildString {
        append(current?.label ?: "全部课程")
        append(" · 共 ${courses.size} 门课")
    }

    val bitmap = TimetableImageWriter.render(
        courses = courses,
        timetable = CourseRepository.timetable,
        title = "我的课表",
        subtitle = subtitle,
    )
    val file = File(exportsDir(context), "manycourse-${stamp()}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()

    share(context, file, "image/png")
    return "已导出课表图片（${file.name}）" to emptyList()
}

/** 导出时跳过的那两类课，逐条说清楚 */
private fun reasonsOf(plan: IcsExport.Plan): List<String> = buildList {
    plan.skippedNoTime.forEach { add("「${it.name}」没有作息时刻（这所学校还没收录作息），排不出具体时间") }
    plan.skippedNoWeek.forEach { add("「${it.name}」落不在任何已知教学周里，不知道排到哪几天") }
}

/**
 * 导出目录：**cache 下**，不是相册也不是文档。
 *
 * 导出文件是"交给分享面板就完成使命"的中间产物 —— 落进用户的相册/文档里反而是垃圾，
 * 还得他手动清理。放 cache 里系统会自动回收。
 */
private fun exportsDir(context: Context): File =
    File(context.cacheDir, "exports").apply { mkdirs() }

private fun stamp(): String = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())

private fun share(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "ManyCourse 课表")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "分享课表"))
    }
}
