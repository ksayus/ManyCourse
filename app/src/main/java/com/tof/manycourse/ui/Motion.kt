package com.tof.manycourse.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

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
