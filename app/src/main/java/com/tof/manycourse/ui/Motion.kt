package com.tof.manycourse.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset

/**
 * 动效规范：时长、缓动、弹性集中定义，页面切换 / 浮层进出场 / 按压反馈统一使用。
 *
 * Compose 动画与 Fragment 动画资源本身都会跟随系统「动画时长缩放」；
 * 这里再显式判断一次 [enabled]，系统关闭动画或开启"减少动态效果"时直接跳过，
 * 避免无意义的动画帧与闪烁。
 *
 * ## 弹性（spring）用在哪、不用在哪
 *
 * 弹性只用在**压得住的小动作**上：按压回弹、chip/开关的选中态、浮层的进出场缓动、
 * 列表插入的淡入上移。**不用在**任何位移课表骨架的动作上（Tab 切换、课表网格、
 * 换周翻月）—— 那几条是架构指南 §2.5 明确否决过的，理由与弹性无关，见那里的说明。
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

/**
 * 按压回弹（"Q 弹"）。
 *
 * `DampingRatioMediumBouncy` = 阻尼比约 0.5，收尾会有一次很轻的回弹 —— 这就是"Q 弹"的手感。
 * 不用 `HighBouncy`（0.2）：那在卡片上会看起来像"卡片在跳"，
 * 而这个项目的动效基调是克制（§2.5 的几条否决都是因为"抖"）。
 *
 * `visibilityThreshold` 设到 0.1%：缩放这种肉眼不敏感的量没必要追到浮点误差，
 * 早一点停能省下最后几帧的无用绘制。
 */
fun tapSpring(): FiniteAnimationSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = TapSpringVisibilityThreshold,
)

/**
 * 浮层进出场用的**轻**弹性：比 [tapSpring] 更硬（`LowBouncy`）。
 *
 * 浮层是一个大面板，回弹幅度一大就会显得"面板在晃"。这里只借它"收尾不急刹"的一点余韵，
 * **位移幅度一个像素都没改**（`GlassOverlay` 里仍是 `height / 14` 与 `height / 20`）——
 * §2.5 只禁了缩放入场，没禁换缓动。
 */
fun overlaySpring(): FiniteAnimationSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = OverlaySpringVisibilityThreshold,
)

/** 按压缩放的量化步进（0.1%）：见 [tapSpring] 的说明 */
private const val TapSpringVisibilityThreshold = 0.001f

/** 位移的量化步进（1/4 px）：比像素还细，停下时看不出台阶 */
private const val OverlaySpringVisibilityThreshold = 0.25f

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
 * 浮层进场：**位移幅度不变**，只把缓动换成轻弹性。
 *
 * 为什么按类型分成三个（而不是一个泛型函数）：Compose 的 `spring()` 返回的是
 * `SpringSpec<T>`，而 `T` 由 `visibilityThreshold` 的类型决定 ——
 * 泛型函数体里给不出 `T` 的实例，只能每个用到的类型写一个。
 * `fadeIn` 要 `Float`（alpha），`slideInVertically` 要 `IntOffset`（像素位移）。
 */
fun enterOverlayFade(): FiniteAnimationSpec<Float> =
    if (Motion.enabled()) overlaySpring() else enterTween()

fun enterOverlayOffset(): FiniteAnimationSpec<IntOffset> =
    if (Motion.enabled()) overlayOffsetSpring() else enterTween()

/** 浮层退场：同上 */
fun exitOverlayFade(): FiniteAnimationSpec<Float> =
    if (Motion.enabled()) overlaySpring() else exitTween()

fun exitOverlayOffset(): FiniteAnimationSpec<IntOffset> =
    if (Motion.enabled()) overlayOffsetSpring() else exitTween()

/** 位移用的轻弹性（量化步进按像素给，见 [overlaySpring] 的说明）*/
private fun overlayOffsetSpring(): FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset(1, 1),
)

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
