package com.tof.manycourse.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.core.app.NotificationManagerCompat
import com.tof.manycourse.data.AppVersion
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.data.UpcomingClass
import com.tof.manycourse.notify.ClassReminderNotifier
import com.tof.manycourse.notify.PresentationTier
import com.tof.manycourse.notify.VendorCapability
import com.tof.manycourse.reminder.ClassReminderScheduler
import com.tof.manycourse.reminder.ReminderPlan
import com.tof.manycourse.reminder.ReminderState
import com.tof.manycourse.ui.components.CampusButton
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.components.GlassHeader
import com.tof.manycourse.ui.components.LiquidGlassBackground
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.time.LocalDateTime

/**
 * **开发者模式**（独立页面，从设置页点进来）。
 *
 * ## 为什么值得单独做一个页面
 *
 * 这一页回答的是"**为什么这台机器上是这个样子**"，而这件事**光看代码答不出来**：
 *
 * | 想看什么 | 只看代码 | 这一页 |
 * |---|---|---|
 * | 课前提醒到底排上了没有 | 得等到课前 | 显示下一条提醒的**具体时刻** |
 * | 通知长什么样 | 一天只响几次，等不起 | **一键发一条**（真的走 `NotificationManager`） |
 * | 超级岛/焦点通知这台机器支不支持 | 探测逻辑分散在三处 | 把**原始事实**摆出来，并能强制各档各发一条 |
 * | 周次/冲突这些新逻辑生效没有 | 得造数据 | 发样本通知、看诊断 |
 *
 * 这不是"给开发者开后门"，而是**把本来就存在的判定结果摊开给用户看**：
 * 设置页只写"当前是焦点通知档"，这里能看见"为什么不是超级岛档"
 * （机型 / 协议版本 / 系统属性 / 社区库判定 四项）。
 *
 * ## 与设置页的关系
 *
 * 页面骨架（背景、页头、返回键、滑入淡入）与设置页同一套，配件也直接用它的
 * （`BackButton` / `SectionLabel` / `SettingSwitchCard` 就在本 package 的 `SettingsScreen.kt` 里）。
 * 所以这里是**第二层页面**：返回到设置页，而不是回到「我的」。
 */
@Composable
fun DevToolsScreen(visible: Boolean, onDismiss: () -> Unit) {
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
                    title = "开发者模式",
                    leading = { BackButton(onDismiss) },
                )
                Box(
                    Modifier
                        .weight(1f)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                        )
                ) {
                    DevToolsContent()
                }
            }
        }
    }
}

@Composable
private fun DevToolsContent() {
    val context = LocalContext.current
    // 上一次按按钮的结果：这一页全是"按一下看看会怎样"，没有反馈会让人反复按
    var notice by remember { mutableStateOf("") }

    // ── 通知与提醒 ─────────────────────────────────────────────────────────
    val now = LocalDateTime.now()
    val nextSlot = UpcomingClass.forReminder(now)
    val leadMinutes = UiSettings.leadMinutes.value
    val alarmAt = nextSlot?.let { ReminderPlan.alarmAt(it, leadMinutes, now) }
    val notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val exactAllowed = ClassReminderScheduler.canScheduleExact(context)

    // ── 展示能力（小米）────────────────────────────────────────────────────
    val facts = VendorCapability.probe(context)
    val islandUsable = ClassReminderNotifier.islandUsableNow(context)
    val tier = ClassReminderNotifier.currentTier(context)
    val focusUsable = facts.focusProtocol >= VendorCapability.MIN_FOCUS_PROTOCOL

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (notice.isNotBlank()) {
            GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = notice,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(14.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        SectionLabel("通知与提醒")
        GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                InfoRow("课表数据", if (UpcomingClass.hasScheduleData()) "有（可解出下一节课）" else "没有（先去同步课表）")
                Spacer(Modifier.height(8.dp))
                InfoRow("下一节课", nextSlot?.let { "${it.name} · ${it.periodLabel} · ${it.startText.ifBlank { "无作息" }}" } ?: "解不出（没有作息 / 没有课）")
                Spacer(Modifier.height(8.dp))
                InfoRow("下一条提醒", alarmAt?.let { "${formatStamp(it)}（提前 $leadMinutes 分钟）" } ?: "没排上")
                Spacer(Modifier.height(8.dp))
                InfoRow("通知权限", if (notificationsAllowed) "已授予" else "未授予 —— 收不到提醒")
                Spacer(Modifier.height(8.dp))
                InfoRow("闹钟精度", if (exactAllowed) "精确（到点就响）" else "不精确（系统可能晚几分钟）")
            }
        }

        Spacer(Modifier.height(12.dp))
        CampusButton(
            text = "立刻发一条课前提醒",
            onClick = {
                // 有真实课表就用真实的下一节课；没有就发一条合成样本 ——
                // 这样"还没登录"的机器上也能验通知外观
                val slot = nextSlot ?: ClassReminderNotifier.sampleSlot()
                ClassReminderNotifier.notify(context, slot)
                notice = "已发出一条课前提醒：${slot.name}（${if (nextSlot != null) "真实课表" else "合成样本"}）"
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        CampusButton(
            text = "重排下一条提醒",
            onClick = {
                ClassReminderScheduler.reschedule(context)
                notice = "已重排。下一条：" + (
                    UpcomingClass.forReminder()
                        ?.let { slot -> ReminderPlan.alarmAt(slot, leadMinutes, LocalDateTime.now()) }
                        ?.let { formatStamp(it) }
                        ?: "没有（没课表数据 / 没有作息 / 已关提醒）"
                    )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        CampusButton(
            text = "清除「已提醒」记录",
            onClick = {
                ReminderState.clear()
                notice = "已清除。这样同一节课会被再提醒一次（用于反复验通知）"
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        SectionLabel("展示能力（小米）")
        GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                InfoRow("机型", "${facts.manufacturer} / ${facts.brand}")
                Spacer(Modifier.height(8.dp))
                InfoRow("焦点通知协议", if (facts.focusProtocol > 0) "${facts.focusProtocol}（2=OS2，3=OS3）" else "未声明（非小米 ROM 通常为 0）")
                Spacer(Modifier.height(8.dp))
                InfoRow("系统声明支持岛", if (facts.islandFlag) "是（persist.sys.feature.island）" else "否")
                Spacer(Modifier.height(8.dp))
                InfoRow("社区库判定可用", if (islandUsable) "是" else "否（还要焦点通知权限 + HyperOS 3+）")
                Spacer(Modifier.height(8.dp))
                InfoRow("最终档位", tierName(tier))
            }
        }

        Spacer(Modifier.height(12.dp))
        CampusButton(
            text = "发一条标准通知",
            onClick = {
                ClassReminderNotifier.notify(context, nextSlot ?: ClassReminderNotifier.sampleSlot(), PresentationTier.Standard)
                notice = "已按「标准通知」强制发一条（不带任何厂商私有参数）"
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        CampusButton(
            text = "发一条焦点通知",
            onClick = {
                if (!focusUsable) {
                    notice = "本机没有焦点通知协议（notification_focus_protocol = ${facts.focusProtocol}），这一档发出来也是普通通知"
                }
                ClassReminderNotifier.notify(context, nextSlot ?: ClassReminderNotifier.sampleSlot(), PresentationTier.Focus)
                if (focusUsable) notice = "已按「焦点通知」强制发一条（挂 miui.focus.param）"
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        CampusButton(
            text = "发一条超级岛通知",
            onClick = {
                ClassReminderNotifier.notify(context, nextSlot ?: ClassReminderNotifier.sampleSlot(), PresentationTier.Island)
                notice = if (islandUsable) {
                    "已按「超级岛」强制发一条。能不能显示由系统决定 —— 不显示就是这台机器不支持"
                } else {
                    "本机社区库判定不可用（见上面四项事实），强制发出来大概率什么都不会发生 —— 这正是「试了不行」的样子"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        SectionLabel("地图与采集")
        val devMode by UiSettings.devMode
        SettingSwitchCard(
            title = "地图采集工具",
            subtitle = if (devMode) "地图页已显示点位采集与标定" else "已关闭，地图页是普通界面",
            hint = "用于实地采集校区点位（教室 / 楼栋）与地图标定；数据存到本机 ManyCourse 目录，不上传",
            checked = devMode,
            switchDesc = "地图采集工具开关",
            onCheckedChange = { UiSettings.setDevMode(it) },
        )

        Spacer(Modifier.height(20.dp))

        SectionLabel("关于本机")
        GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                InfoRow("应用包名", context.packageName)
                Spacer(Modifier.height(8.dp))
                InfoRow("应用版本", AppVersion.display(context))
                Spacer(Modifier.height(8.dp))
                InfoRow("系统", "Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
                Spacer(Modifier.height(8.dp))
                InfoRow("设备", Build.MODEL)
                Spacer(Modifier.height(8.dp))
                InfoRow("课表内的课", "${CourseRepository.courses.size} 门")
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/** 「标签 —— 值」一行；值太长时换行，不省略（这一页要的就是"看得全"）*/
@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun tierName(tier: PresentationTier): String = when (tier) {
    PresentationTier.Island -> "超级岛（社区库）"
    PresentationTier.Focus -> "小米焦点通知"
    PresentationTier.Standard -> "标准通知（不加厂商参数）"
}

/** `10-12 08:15` */
private fun formatStamp(at: LocalDateTime): String =
    "%02d-%02d %02d:%02d".format(at.monthValue, at.dayOfMonth, at.hour, at.minute)
