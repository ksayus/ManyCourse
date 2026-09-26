package com.tof.manycourse.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 动效规范：时长与缓动集中定义，页面切换 / 浮层进出场统一使用。
 *
 * Compose 动画与 Fragment 动画资源本身都会跟随系统「动画时长缩放」；
 * 这里再显式判断一次 [enabled]，系统关闭动画或开启"减少动态效果"时直接跳过，
 * 避免无意义的动画帧与闪烁。
 */
object Motion {
    /** 进入：200–300ms */
    const val EnterMillis = 240

    /** 退出：略快于进入，收尾干脆 */
    const val ExitMillis = 180

    /** 轻微位移量：进入 8dp → 0，退出 0 → -4dp */
    const val EnterOffsetDp = 8
    const val ExitOffsetDp = -4

    val Easing = FastOutSlowInEasing

    /** 系统是否允许播放动画（animator_duration_scale > 0） */
    fun enabled(): Boolean = runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
}

/** 组合内读取一次系统动效开关（系统设置变化通常伴随重建，无需实时监听） */
@Composable
fun rememberAnimationsEnabled(): Boolean = remember { Motion.enabled() }

/** 进入 tween（泛型：alpha 用 Float、位移用 IntOffset，调用处自动推断） */
fun <T> enterTween(): TweenSpec<T> =
    tween(durationMillis = Motion.EnterMillis, easing = Motion.Easing)

/** 退出 tween */
fun <T> exitTween(): TweenSpec<T> =
    tween(durationMillis = Motion.ExitMillis, easing = Motion.Easing)

/**
 * **「内容换了就淡入淡出」的进度**：0 = 还没显出来（全透明），1 = 就位。
 *
 * ## 它做什么、**不**做什么
 *
 * 课表是"同一位子上换内容"的界面：换周、翻月、点另一天，卡片、圆点会**就地换牌**。
 * 这个进度只做一件事 —— 让新内容**淡进来**（透明度 0 → 1），把"换了"看进眼里。
 *
 * ★ **不做位移、不做缩放**：整块内容上下浮动会看成"整张表在抖"，
 * 而课表的位置本身就是信息（第几行 = 第几节、第几列 = 星期几），
 * 挪一下反而要重新找。所以它只改 **alpha**，位置一动不动。
 * （换周时"哪些课上、哪些不上"的**颜色**淡入淡出是另一层，见 `CalendarGridLayout`
 * 的 `rememberGridCardFace`。）
 *
 * ## 用法：**只在绘制阶段读它**
 *
 * 直接用它 → `Modifier.contentFade(key)`（推荐）；要自己组合时：
 *
 * ```kotlin
 * val fade by rememberContentFade(key = weekStart)
 * Modifier.graphicsLayer { alpha = fade }
 * ```
 *
 * [graphicsLayer] 的 lambda 在绘制阶段执行，所以动画的每一帧**不会触发重组、也不会重新布局**——
 * 只改透明度就更是如此：行高、格子尺寸、页面高度一概不变
 * （课表页"整周一眼看完、不用滚动"那套按屏幕算行高的机制因此完全不受影响）。
 * **别在组合阶段读它的 value 去算尺寸**，那会把 240ms 变成几十次重组。
 *
 * @param key 内容身份：**变了就从头淡入一遍**（换周传这一周的日期、翻月传月份、换天传日期）。
 *   传 `List` 之类的值类型也安全（按结构比较，内容没变就不会重播）
 * @param active false = 这次不淡入，直接给 1（就位）
 * @param enabled 系统动效开关（[Motion.enabled]）；关掉时一律直接给 1
 */
@Composable
fun rememberContentFade(
    key: Any?,
    active: Boolean = true,
    enabled: Boolean = Motion.enabled(),
): State<Float> {
    val play = enabled && active
    // 初始值就按"要不要播"定：要播的从 0 起（首帧即淡入），不播的直接 1（不闪）
    val progress = remember { mutableStateOf(if (play) 0f else 1f) }
    val animatable = remember { Animatable(progress.value) }

    LaunchedEffect(key, play) {
        if (!play) {
            // 不播也必须**归位**：否则上一次动画播到一半被打断，内容会永久停在半透明
            animatable.snapTo(1f)
            progress.value = 1f
            return@LaunchedEffect
        }
        animatable.snapTo(0f)
        progress.value = 0f
        // 每帧把值推进 State：调用方在 graphicsLayer 里读它，读发生在绘制阶段
        animatable.animateTo(1f, enterTween()) { progress.value = value }
    }
    return progress
}

/**
 * **[rememberContentFade] 的现成用法**：`Modifier.contentFade(内容身份)` —— 换内容时淡入一遍。
 *
 * ```kotlin
 * // 月历：换月 / 数据到达时，这一格的课程圆点淡进来
 * Row(Modifier.contentFade(dots, active = dots.isNotEmpty())) { … }
 * ```
 *
 * ★ **只给"内容"用，别给骨架用**：淡入的是课程与圆点这类内容；
 * 时间轴、网格线、星期表头是骨架，跟着一起淡会像整块在闪。
 *
 * @param key 内容身份：变了就从头淡入（换周传这七天、翻月传月份、换天传日期）
 * @param active false = 这次不淡入，直接落到就位
 */
@Composable
fun Modifier.contentFade(key: Any?, active: Boolean = true): Modifier {
    val fade by rememberContentFade(key = key, active = active)
    return this.graphicsLayer { alpha = fade }
}
