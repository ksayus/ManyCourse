package com.tof.manycourse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.HolidayDay
import com.tof.manycourse.data.HolidayKind
import com.tof.manycourse.data.holidayOf
import com.tof.manycourse.ui.theme.LocalGlassTokens
import java.time.LocalDate

/**
 * 「这一天的课为什么灰着」提示条 —— **课表页与日历页共用**。
 *
 * 放假那天课表**照旧列着当天的课**，只是**一门都不点亮**（灰着）。
 * 不说一句的话，用户要么以为这些课照常上（于是白跑一趟教室），
 * 要么以为课表坏了（卡片怎么是灰的）。所以放假日必须**用一行字把原因说出来**。
 *
 * 两种身份都要提示，理由不同：
 *
 * | 身份 | 文案 | 为什么必须说 |
 * |---|---|---|
 * | 放假（休） | `中秋节放假 · 当天的课都不上` | 课表照旧列出那几门课，不说明的话用户会以为照常上 |
 * | 调休上班（班） | `国庆节调休上班 · 按学校安排上课` | 这天**有课**，得让用户知道为什么周六要上课 / 要留意学校通知 |
 *
 * ★ **放假不是"把课删掉"**：那天的课照旧列在课表上，只是**一门都不点亮**（灰着）——
 * 所以这里的文案是"课都不上"，不是"已滤除"。灰着与周视图那条
 * 「亮 = 本周会上 · 灰 = 本周不上」是同一套语言（判据见 `data/CourseEntries.kt` 的 `dayIsLit`）。
 *
 * 「休 / 班」两个小标记沿用手机日历的写法（中国用户一眼就懂），
 * 也用在月历格子和周视图表头上 —— 三处同一个记号，不用另教一套图例。
 *
 * ★ 取不到节假日（不是节假日、或开关关掉了）时**整块不画**，不占高度：
 * [holidayOf] 已经把开关也算进去了，这里不再判一次（判两遍迟早会不一致）。
 */
@Composable
internal fun HolidayNotice(date: LocalDate, modifier: Modifier = Modifier) {
    val day = holidayOf(date) ?: return
    val tokens = LocalGlassTokens.current
    // 放假用强调色（"今天真的没课"），调休用中性色（"今天照常，只是日子特殊"）
    val tint = if (day.isRest) tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HolidayBadge(day)
        Spacer(Modifier.width(6.dp))
        Text(
            text = holidayNoticeText(day),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = tint,
        )
    }
}

/**
 * `休` / `班` 小胶囊（提示条、月历格子、周视图表头共用同一个记号）。
 *
 * @param compact true = 格子/表头里那种更小的写法（那里一行只有 8~10sp 的位置）
 */
@Composable
internal fun HolidayBadge(
    day: HolidayDay,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val tint = if (day.isRest) LocalGlassTokens.current.accent else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = holidayBadgeLabel(day),
        fontSize = if (compact) 8.sp else 9.sp,
        lineHeight = if (compact) 10.sp else 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = if (compact) 2.dp else 4.dp, vertical = if (compact) 1.dp else 2.dp),
    )
}

/**
 * 提示条上的一句话（纯函数，`HolidayNoticeTest` 钉住）。
 *
 * 两句话都**不能**长得像「共 N 门课程」或「周X · N 门课程」：真机测试
 * `ScheduleCalendarParityTest` 是拿这两种句式在界面上对账的，多出一个形似的节点
 * 会让它读到错的数字（所以这里连"门课程"三个字都不出现）。
 */
internal fun holidayNoticeText(day: HolidayDay): String = when (day.kind) {
    HolidayKind.Rest -> "${day.name}放假 · 当天的课都不上"
    HolidayKind.Work -> "${day.name}调休上班 · 按学校安排上课"
}

/** 格子/表头上的单字标记：放假 `休`、调休上班 `班` */
internal fun holidayBadgeLabel(day: HolidayDay): String =
    if (day.isRest) "休" else "班"
