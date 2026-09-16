package com.tof.manycourse.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tof.manycourse.ui.enterTween
import com.tof.manycourse.ui.exitTween
import com.tof.manycourse.ui.rememberAnimationsEnabled
import com.tof.manycourse.ui.theme.LocalGlassTokens

/**
 * 玻璃浮层容器（弹窗 / 全屏表单）：遮罩淡入淡出 + 面板淡入并轻微上移缩放，
 * 返回键与点击遮罩都能关闭，并播放对应的退出（pop）动画。
 *
 * 系统关闭动画（动画时长缩放 = 0 / 减少动态效果）时自动跳过过渡，避免闪烁。
 * 面板高度限制在可用高度的 92%，横竖屏 / 小屏都不会被裁掉，超高时内部滚动生效。
 */
@Composable
fun GlassOverlay(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    panel: @Composable () -> Unit,
) {
    // 返回键 = 关闭浮层（播放退出动画，而不是直接退出应用）
    BackHandler(enabled = visible, onBack = onDismissRequest)

    val animations = rememberAnimationsEnabled()
    val tokens = LocalGlassTokens.current

    // 过渡实例必须 remember：否则面板内部任何重组（如表单在打开瞬间重置字段）
    // 都会生成新的 EnterTransition 实例，动画被中断重放，表现就是"卡片闪几下"
    val scrimEnter = remember(animations) {
        if (animations) fadeIn(enterTween()) else EnterTransition.None
    }
    val scrimExit = remember(animations) {
        if (animations) fadeOut(exitTween()) else ExitTransition.None
    }
    // 只做淡入 + 轻微位移，不做缩放：缩放会和阴影层叠加产生"跳一下"的观感
    val panelEnter = remember(animations) {
        if (animations) {
            fadeIn(enterTween()) + slideInVertically(enterTween()) { height -> height / 14 }
        } else {
            EnterTransition.None
        }
    }
    val panelExit = remember(animations) {
        if (animations) {
            fadeOut(exitTween()) + slideOutVertically(exitTween()) { height -> height / 20 }
        } else {
            ExitTransition.None
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 横屏 / 异形屏：面板左右避开挖孔与圆角（竖屏普通机型为 0，无影响）
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            )
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        // 限高后内部 verticalScroll 才能真正滚动（小屏 / 横屏不会裁切内容）
        val maxPanelHeight = maxHeight * 0.92f - 48.dp

        // ── 遮罩：仅淡入淡出 ──
        AnimatedVisibility(
            visible = visible,
            enter = scrimEnter,
            exit = scrimExit,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tokens.scrim)
                    // indication = null：遮罩不显示涟漪（此前整屏涟漪是明显的观感问题）
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest,
                    )
            )
        }

        // ── 面板：淡入 + 8dp 上移；退出反向 ──
        AnimatedVisibility(
            visible = visible,
            enter = panelEnter,
            exit = panelExit,
        ) {
            // 面板是遮罩的兄弟节点：天然不会点击穿透，无需再挂"空 clickable"拦截
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .heightIn(max = maxPanelHeight)
            ) {
                panel()
            }
        }
    }
}
