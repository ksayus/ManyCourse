package com.tof.manycourse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.DataRefresh
import com.tof.manycourse.ui.components.GlassPanel
import com.tof.manycourse.ui.theme.LocalGlassTokens
import kotlinx.coroutines.delay

/**
 * 手动刷新的三件界面（**课表页与日历页共用这一份**）。
 *
 * 为什么收在一个文件里：三处都只是 [DataRefresh] 的不同画法，
 * 各页自己写一遍的话，"哪些情况能刷、刷不了说什么"迟早会各长一样 ——
 * 而那正是这个功能唯一能被用户看见的部分。
 *
 * | 组件 | 放哪 |
 * |---|---|
 * | [RefreshButton] | 两页的页头（`GlassHeader` 的 actions） |
 * | [RefreshHintRow] | 课表页顶部那一行（设计规范里的「下拉刷新」提示） |
 * | [RefreshNotice] | 两页内容区底部（"没刷成"的原因，浮在内容上，几秒后自己消失） |
 */

/** 提示停留多久（够读完一句话；之后自己消失，不需要用户去点掉）*/
private const val NoticeMillis = 4000L

/** 刷新中图标转一圈的时长：快到一眼看出在转，又不至于转成一团 */
private const val SpinMillis = 900

/**
 * 页头上的**刷新按钮**：平时一按即刷，刷新中转圈且不可点。
 *
 * 为什么要页头按钮（明明课表页能下拉）：日历页的周视图**整屏不滚动**
 * （见 `CalendarScreen` 的高度预算），下拉手势在那里根本没有触发点。
 * 页头按钮两页都在、两种布局都能用，不依赖"这页能不能滑"。
 *
 * 转圈只在**真的在刷新时**才创建动画（[DataRefresh.running]）：
 * 常驻一个无限动画等于常驻逐帧刷新，而这是个静止页面（架构指南 §5）。
 */
@Composable
fun RefreshButton(modifier: Modifier = Modifier) {
    val running = DataRefresh.running
    val tokens = LocalGlassTokens.current
    val animations = rememberAnimationsEnabled()

    val angle = if (running && animations) {
        val transition = rememberInfiniteTransition(label = "refreshSpin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(SpinMillis, easing = LinearEasing)),
            label = "refreshAngle",
        ).value
    } else {
        0f
    }

    Icon(
        imageVector = AppIcons.Refresh,
        // 无障碍与真机测试都靠这个名字定位（设置页的开关也是同一套路子）
        contentDescription = "刷新数据",
        tint = if (running) tokens.accent else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !running) { DataRefresh.start() }
            .padding(8.dp)
            .rotate(angle),
    )
}

/**
 * 课表页顶部那一行（设计规范里就是「下拉刷新」）：**能刷时是提示，不能刷时是原因**。
 *
 * 不能刷的原因（[DataRefresh.blocker]）在这里是**常驻**的，而不是"点一下才说"：
 * 本地调试账号那种状态下，下拉手势本来就不会有任何反应 ——
 * 一直挂着"下拉刷新"的提示却刷不动，比什么都不写更糟。
 *
 * @param hint 本页固定的手势提示；没有下拉刷新的页面（日历页）传 null
 */
@Composable
fun RefreshHintRow(hint: String? = null, modifier: Modifier = Modifier) {
    val text = DataRefresh.blocker ?: hint ?: return
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    )
}

/**
 * 「没刷成」的原因：浮在内容区底部的一句话，几秒后自己消失（[NoticeMillis]）。
 *
 * ★ 必须是**浮层**而不是往页面里插一行：日历的周视图是按屏幕高度分给七天的
 * （"整周一眼看完、不用滚动"），插一行会把网格挤一下再弹回来 —— 那一下比提示本身还显眼。
 *
 * 用 [GlassPanel]（高不透明度）而不是 [com.tof.manycourse.ui.components.GlassCard]：
 * 它是浮在课程卡片上的，必须**保证读得清**，而不是维持玻璃的透亮。
 */
@Composable
fun BoxScope.RefreshNotice() {
    val notice = DataRefresh.notice.value
    // 文字要留着：退出动画那 180ms 里 notice 已经是 null 了，没有内容就没东西可画
    var shown by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(notice) {
        if (notice != null) {
            shown = notice
            delay(NoticeMillis)
            DataRefresh.clearNotice()
        }
    }

    val animations = rememberAnimationsEnabled()
    AnimatedVisibility(
        visible = notice != null,
        enter = if (animations) fadeIn(enterTween()) else EnterTransition.None,
        exit = if (animations) fadeOut(exitTween()) else ExitTransition.None,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        GlassPanel(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = shown.orEmpty(),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
