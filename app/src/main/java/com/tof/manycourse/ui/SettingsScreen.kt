package com.tof.manycourse.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
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
import com.tof.manycourse.data.HolidayCalendar
import com.tof.manycourse.data.LeadMinuteOptions
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.notify.ClassReminderNotifier
import com.tof.manycourse.notify.NotificationPermission
import com.tof.manycourse.notify.PresentationTier
import com.tof.manycourse.notify.RecoveryAction
import com.tof.manycourse.notify.VendorCapability
import com.tof.manycourse.reminder.ClassReminderScheduler
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.components.GlassHeader
import com.tof.manycourse.ui.components.LiquidGlassBackground
import com.tof.manycourse.ui.theme.GlassMode
import com.tof.manycourse.ui.theme.GlassPalette
import com.tof.manycourse.ui.theme.LocalGlassMode
import com.tof.manycourse.ui.theme.LocalGlassPalette
import com.tof.manycourse.ui.theme.LocalGlassTokens
import com.tof.manycourse.ui.theme.glassTokens

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

    // 开发者模式是**第二层页面**（从这一页点进去），所以状态放在这里：
    // 返回键先关开发者页、再关设置页 —— DevToolsScreen 内部的 BackHandler 后注册、优先响应
    var showDevTools by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }

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
                    SettingsContent(onOpenDevTools = { showDevTools = true }, onOpenTransfer = { showTransfer = true })
                }
            }
            // 开发者模式：**第二层页面**，盖在设置页之上（自带背景与页头，所以外层不用配合）
            DevToolsScreen(visible = showDevTools, onDismiss = { showDevTools = false })
            TransferScreen(visible = showTransfer, onDismiss = { showTransfer = false })
        }
    }
}

/** 「返回」：设置页是独立页，页头左侧给一个明确的退出口（右滑手势之外还能点） */
@Composable
internal fun BackButton(onBack: () -> Unit) {
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
private fun SettingsContent(onOpenDevTools: () -> Unit, onOpenTransfer: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SectionLabel("外观与布局")
        GlassModeCard()
        Spacer(Modifier.height(12.dp))
        PaletteCard()
        Spacer(Modifier.height(12.dp))
        CalendarLayoutCard()
        Spacer(Modifier.height(12.dp))
        HolidayDimCard()

        Spacer(Modifier.height(20.dp))

        SectionLabel("通知")
        NotificationCard()

        Spacer(Modifier.height(20.dp))

        SectionLabel("关于与更新")
        UpdateCheckCard()
        Spacer(Modifier.height(12.dp))
        UpdateSourceCard()

        Spacer(Modifier.height(20.dp))

        SectionLabel("导出与导入")
        TransferEntryCard(onOpenTransfer)

        Spacer(Modifier.height(20.dp))

        SectionLabel("开发者")
        DevToolsEntryCard(onOpenDevTools)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun SectionLabel(text: String) {
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
 * **配色预设**选择卡：一排圆形色卡，点一下全局立即生效。
 *
 * ## 色卡圆点用什么颜色
 *
 * 用该配色**在当前明暗主题下**的 `accent` 填实（浅色主题给深档、深色主题给亮档）——
 * 也就是用户点下去之后**立刻会看到的那一色**，而不是一个抽象的色相。
 * 取色走 `glassTokens(...)` 工厂，本文件里不出现任何颜色字面量：
 * 色卡与真实界面共用同一个真相源，改了令牌两边一起变。
 *
 * ## 为什么不做二次确认
 *
 * 六套配色都是**可逆的**（再点一下另一个就换回来，本页马上能预览效果），
 * 加一步"确定吗"只会让试配色这件事变累。选中的那一套带描边，随时看得出现在是哪套。
 */
@Composable
private fun PaletteCard() {
    val current = LocalGlassPalette.current
    val dark = isSystemInDarkTheme()
    GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "外观 · 配色",
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
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                GlassPalette.entries.forEach { palette ->
                    PaletteSwatch(
                        palette = palette,
                        // 用当前明暗下的 accent 当色卡：点下去看到的就是这一色
                        color = previewAccent(palette, dark),
                        selected = palette == current,
                        onClick = { UiSettings.setGlassPalette(palette) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "当前：${current.label}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一枚色卡：圆形色块 + 名称；选中时外面套一圈强调色描边。
 *
 * 描边用 `LocalGlassTokens` 的 accent（也就是**当前生效**的配色），
 * 而不是这枚色卡自己的颜色 —— 这样"选中的是哪一枚"永远是同一种视觉语言，
 * 不会因为选中的恰好是红/绿而看起来像某种警告。
 */
@Composable
private fun PaletteSwatch(
    palette: GlassPalette,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalGlassTokens.current
    // 用该配色自己的 label 做无障碍描述：色卡是纯视觉控件，读屏用户只能靠它区分
    val description = if (selected) "配色 ${palette.label}，已选中" else "配色 ${palette.label}"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(SwatchSize)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) SwatchSelectedBorder else SwatchIdleBorder,
                    color = if (selected) tokens.accent else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                )
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = palette.label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 色卡预览取的色：**浅色档 / 深色档**按当前主题选。
 *
 * 刻意不传当前配色 —— 预览必须展示"这一套配色长什么样"，与"现在用的是哪套"无关。
 * 玻璃模式传的是默认值：`accent` 与模式无关（两种模式共用同一档），
 * 传哪个模式结果都一样，这里不假装它有关。
 */
private fun previewAccent(palette: GlassPalette, dark: Boolean): Color =
    glassTokens(GlassMode.Default, dark, palette).accent

/** 色卡直径：六枚一排，`SpaceBetween` 会把余量摊到间隙里（窄屏也不会挤在一起）*/
private val SwatchSize = 36.dp
private val SwatchSelectedBorder = 3.dp
private val SwatchIdleBorder = 1.dp

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

/**
 * **节假日自动置灰**开关（默认开）。
 *
 * 打开后，法定放假日（国务院办公厅的通知，见 `data/HolidayCalendar.kt`）那天的课
 * **照旧列在课表上，但一门都不点亮**（灰着），并由一行小字说明"某某节放假 · 当天的课都不上"。
 * 调休上班日照常点亮，只把日子标出来。
 *
 * 为什么是"置灰"而不是"删掉"：把课从数据里拿掉的话，课程卡片会直接消失，
 * 用户看到的是"课表空了" —— 分不清是放假、还是同步挂了、还是自己没选课。
 * 灰着则一眼就知道"这天排了课、但今天不上"，也不必另教一套图例。
 *
 * 这是**唯一一处 App 覆盖教务系统课表**的地方（其他地方都是教务系统说什么就是什么），
 * 所以必须给用户一个退回去的开关：学校真有假期补课、或者就想让放假当天和平时长得一样时，
 * 关掉它即可 —— 关掉之后连那行说明也一起消失（既不置灰，也不解释）。
 *
 * 副标题里带上**数据覆盖到哪一年**：节假日表是内置的、一年一更，
 * 用户翻到表外的年份时得知道"这不过滤不是坏了，是还没收录"。
 */
@Composable
private fun HolidayDimCard() {
    val enabled by UiSettings.dimOnHolidays
    val coverage = HolidayCalendar.coverageLabel
    SettingSwitchCard(
        title = "节假日自动置灰",
        subtitle = if (enabled) "放假那天的课照旧列出，但不点亮（灰着）" else "已关闭，放假当天与平时一样",
        hint = if (coverage.isBlank()) {
            "法定节假日数据尚未收录"
        } else {
            "依据国务院办公厅放假安排（已收录 $coverage 年）；调休上班日照常上课"
        },
        checked = enabled,
        switchDesc = "节假日自动置灰开关",
        onCheckedChange = { UiSettings.setDimOnHolidays(it) },
    )
}

/**
 * **课前提醒设置卡**。
 *
 * 这个卡片原来是**空壳**（只记开关状态、没有任何推送逻辑，提示文案也如实写着
 * "推送逻辑见后续版本"）。现在它管五件事，每一件都**如实显示当前状态**，
 * 而不是假装已经生效：
 *
 *  1. 开关；
 *  2. 提前量（5/10/15/20/30 分钟，默认 15）；
 *  3. 通知权限 —— 没给就直说"提醒发不出来"，并给一条**真的走得通**的路：
 *     系统还愿意弹窗就再申请一次，已经不弹了（问过两次 / "不再询问"）就跳系统通知设置，
 *     而不是给一个点了没反应的「去授权」（判定见 `notify/NotificationPermission.kt`）；
 *  4. 闹钟精度 —— Android 12 起精确闹钟要单独授权，没给就只能用不精确闹钟，
 *     那种情况下提醒**可能晚几分钟**，这件事必须让用户知道，而不是让他以为很准；
 *  5. 展示能力诊断 —— 这台机器为什么落在"标准通知 / 焦点通知 / 超级岛"这一档；
 *  6. **悬浮通知**（小米系机型）—— 系统里对每个应用单独的开关。它关着时，
 *     我们这条通知的参数配得再对也不会弹横幅；这不是应用能改的，所以只能如实告诉用户
 *     去哪儿检查，并给一个直达系统通知设置的入口。
 *
 * 第 5 条是这个功能的诚实性所在：小米超级岛需要平台准入（企业认证 + 逐场景审核 +
 * 设备白名单，见 `notify/VendorCapability.kt`），本项目是个人侧载，**拿不到**。
 * 所以这里不写"已开启超级岛"，只把探测到的原始事实摆出来。
 */
@Composable
private fun NotificationCard() {
    val context = LocalContext.current
    val enabled by UiSettings.notificationsEnabled
    val leadMinutes by UiSettings.leadMinutes

    // 从系统设置页回来时权限状态会变，而系统**不会通知我们**。
    // 只能趁"回到前台"重新问一遍；不加这段的话，用户去设置里开了通知权限、
    // 回到 App 看到的还是"未授予"，会以为没生效又去点一遍。
    val lifecycleOwner = LocalLifecycleOwner.current
    val refreshTick = remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick.intValue++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationsAllowed = remember(refreshTick.intValue) {
        // 两种"没权限"一起看：系统总开关（areNotificationsEnabled）与 Android 13 起那条运行时权限。
        // 前者在权限被拒时通常也是 false，但两者判定来源不同 —— 与 `ClassReminderNotifier.notify`
        // 的两道闸门同一口径，免得"这里说已授予、通知却发不出来"。
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            NotificationPermission.granted(context)
    }
    val exactAllowed = remember(refreshTick.intValue) {
        ClassReminderScheduler.canScheduleExact(context)
    }
    val tier = remember(refreshTick.intValue) { ClassReminderNotifier.currentTier(context) }
    // 小米系机型才有"悬浮通知"那道系统开关 —— 探测一次记住，别在每次重组时重扫系统属性
    val xiaomiFamily = remember(refreshTick.intValue) {
        VendorCapability.isXiaomiFamily(VendorCapability.probe(context))
    }

    // 「去授权」到底该弹系统弹窗还是跳系统设置：判定在 NotificationPermission（被单测钉住），
    // 这里只负责照着画。被拒两次之后系统不会再弹，那时唯一的活路就是系统设置。
    val recovery = remember(refreshTick.intValue) {
        NotificationPermission.currentRecoveryAction(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshTick.intValue++
        // 刚拿到通知权限：立刻把提醒排上（没权限时闹钟是空的，不排就等于没开这个功能）
        if (granted) ClassReminderScheduler.reschedule(context)
    }

    GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "通知设置",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // 这句文案被真机插桩测试 `GlassUiTest` 断言，措辞不要随手改
                        text = if (enabled) "课前 $leadMinutes 分钟推送提醒" else "已关闭，不再推送提醒",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        UiSettings.setNotificationsEnabled(on)
                        // 开关一变立刻重排：关掉 = 撤销闹钟，打开 = 当场排上
                        ClassReminderScheduler.reschedule(context)
                    },
                    modifier = Modifier.semantics { contentDescription = "通知提醒开关" },
                )
            }

            if (!enabled) return@Column

            Spacer(Modifier.height(14.dp))
            Text(
                text = "提前多久提醒",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LeadMinutePicker(
                current = leadMinutes,
                onSelect = { minutes ->
                    UiSettings.setLeadMinutes(minutes)
                    // 改完档位立刻按新档位重排，而不是等下一次打开 App
                    ClassReminderScheduler.reschedule(context)
                },
            )

            Spacer(Modifier.height(14.dp))
            if (notificationsAllowed) {
                NotificationStatusLine(text = "通知权限：已授予")
            } else {
                NotificationStatusLine(
                    text = "通知权限：未授予 —— 提醒发不出来",
                    // 还能弹就再申请一次；其余两种都只有系统设置这一条路：
                    //   · OpenSystemSettings = 问过而且系统已不再显示弹窗（再 launch 只会无声失败）
                    //   · None = 权限本身没问题，是系统里把本应用的通知整个关了
                    // 否则用户点了「去授权」什么都不会发生 —— 那比没有按钮更糟
                    actionLabel = when (recovery) {
                        RecoveryAction.RequestPermission -> "去授权"
                        RecoveryAction.None, RecoveryAction.OpenSystemSettings -> "去系统设置"
                    },
                    onAction = {
                        if (recovery == RecoveryAction.RequestPermission) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            NotificationPermission.openSystemNotificationSettings(context)
                        }
                    },
                )
            }

            // ★ 小米系机型还有一道**我们管不到的**开关：系统给每个应用单独的「悬浮通知」。
            //   它关着的时候，渠道重要性、优先级、声音、振动配得再对也不会弹横幅 ——
            //   这不是我们的参数问题，用户只能去系统设置里开，所以这里如实说、并给出入口。
            //   只在通知已经允许时提示：没允许时上面那一行才是要先解决的。
            if (notificationsAllowed && xiaomiFamily) {
                NotificationStatusLine(
                    text = "悬浮通知：小米系统里对本应用单独开关，关着就不弹横幅",
                    actionLabel = "去系统设置",
                    onAction = { NotificationPermission.openSystemNotificationSettings(context) },
                )
            }
            if (exactAllowed) {
                NotificationStatusLine(text = "闹钟精度：精确，到点就响")
            } else {
                NotificationStatusLine(
                    text = "闹钟精度：不精确 —— 系统可能晚几分钟才提醒",
                    actionLabel = "去授权",
                    onAction = { openExactAlarmSettings(context) },
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = tierDiagnosis(context, tier),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 提前量档位选择：一排等宽胶囊，选中的用主色填实。
 *
 * 用等宽 + 一排到底，是因为档位只有 5 个且互斥 —— 下拉菜单那种交互要多两次点击，
 * 而这里一眼就能看到"现在选的是哪一档"。
 */
@Composable
private fun LeadMinutePicker(current: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LeadMinuteOptions.forEach { minutes ->
            val selected = minutes == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(minutes) }
                    .padding(vertical = 9.dp)
                    .semantics { contentDescription = "提前 $minutes 分钟" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$minutes",
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一行状态说明；[actionLabel] 不为空时右侧跟一个可点的动作 */
@Composable
private fun NotificationStatusLine(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = actionLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 跳到系统里的「精确闹钟」授权页。
 *
 * `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` **不是所有机型都实现了**，
 * 所以必须留一个兜底：真跳不过去时退回到本应用的详情页
 * （用户在那里也能自己翻到"闹钟和提醒"那一项），而不是点了按钮什么也不发生。
 */
private fun openExactAlarmSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    runCatching {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri))
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    }
}

/**
 * 展示能力诊断：把"这台机器为什么是这一档"的**原始事实**摆出来。
 *
 * 刻意不写"已开启超级岛"这种话 —— 能不能显示由系统版本、设备能力、用户设置
 * 和小米平台权限共同决定，我们只能报告探测结果（见 `notify/VendorCapability.kt`）。
 */
private fun tierDiagnosis(context: Context, tier: PresentationTier): String {
    val facts = VendorCapability.probe(context)
    val tierText = when (tier) {
        PresentationTier.Island -> "本机展示：超级岛（社区库提供，能否显示由系统决定）"
        PresentationTier.Focus -> "本机展示：小米焦点通知"
        PresentationTier.Standard -> "本机展示：标准通知（不加厂商私有参数）"
    }
    val device = if (VendorCapability.isXiaomiFamily(facts)) "小米系机型" else "非小米机型"
    val protocol = when {
        facts.focusProtocol >= 3 -> "超级岛协议(OS3)"
        facts.focusProtocol == 2 -> "焦点通知协议(OS2)"
        facts.focusProtocol == 1 -> "OS1(官方不建议接入)"
        else -> "未声明焦点通知协议"
    }
    val island = if (facts.islandFlag) "系统声明支持超级岛" else "系统未声明支持超级岛"
    return "$tierText\n$device · $protocol · $island"
}

/**
 * **「导出与导入」入口卡片**：点进去是一个独立页面（`ui/TransferScreen.kt`）。
 *
 * 为什么独立成页而不是在设置页上再铺三张卡：设置页已经够长了
 * （外观 / 通知 / 关于 / 开发者），而这三件事（导出日历、导出图片、导入）
 * 是**同一件事的三个动作**，铺开来只是更长的列表。
 */
@Composable
private fun TransferEntryCard(onClick: () -> Unit) {
    GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "导出与导入",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "导出 .ics 日历文件 · 导出课表图片 · 导入 CSV / ICS",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "导进系统日历就能多设备看 —— 课表不上传任何服务器",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * **「开发者模式」入口卡片**：点进去是一个独立页面（`ui/DevToolsScreen.kt`）。
 *
 * 以前这里是一个开关，只管"地图采集工具"；现在测试通知、超级岛/焦点通知诊断、
 * 下一条提醒时刻、地图采集都收进了那个页面 —— 设置页只留一个入口。
 *
 * 为什么不做成开关：这些是"看一眼 / 按一下试试"的工具，不是一个需要长期打开的**状态**。
 * 用开关去代表它们，用户会以为"关着 = 这些功能不存在"，而它们其实随时可用。
 */
@Composable
private fun DevToolsEntryCard(onClick: () -> Unit) {
    GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "开发者模式",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "测试通知 · 超级岛 / 焦点通知诊断 · 下一条提醒时刻 · 地图采集",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "点进去逐项自测（「试了不行」到底长什么样，这里看得见）",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「标题 + 说明 + 开关」的一张卡片（设置页里两个开关共用）。
 *
 * 开关带 `contentDescription`：设置页里开关不止一个，
 * "第几个 Switch"不是稳定的定位方式，无障碍朗读与真机测试（`GlassUiTest`）都靠这个名字。
 */
@Composable
internal fun SettingSwitchCard(
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
