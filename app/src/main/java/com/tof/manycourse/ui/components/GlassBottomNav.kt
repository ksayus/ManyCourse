package com.tof.manycourse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import com.tof.manycourse.ui.AppIcons
import com.tof.manycourse.ui.MainTabStore
import com.tof.manycourse.ui.theme.GlassEdge
import com.tof.manycourse.ui.theme.GlassLevel
import com.tof.manycourse.ui.theme.LocalGlassTokens

/**
 * 液态玻璃底部导航（规范：64dp 高、24dp 图标 + 12sp 标签、选中蓝色）。
 * 选中项：蓝色胶囊背景（弹性动画）+ 图标/文字缩放 + 颜色平滑过渡。
 *
 * 适配不同屏幕：
 * - 安全区用 `navigationBars ∪ displayCutout`（手势条/导航栏 + 横屏侧边挖孔），
 *   横屏时图标不会被侧边挖孔或圆角压住；**不含 IME**，键盘弹出时底栏不会跟着跳；
 * - 玻璃背景铺满到屏幕物理底边，但**底边与两侧不画描边**（圆角屏/曲面屏会裁切边缘），
 *   分隔线只画在与内容相接的顶边（[GlassEdge.Top]）。
 *
 * 点击**不带涟漪**（`indication = null`）：默认涟漪会被裁成一块灰色圆角矩形盖在玻璃上，
 * 看起来就是"点底栏时出现一层灰罩"。选中反馈由胶囊底 + 图标缩放 + 颜色过渡承担。
 *
 * 性能要点：[activeIndex] 在组件内部读取（而不是由调用方作为参数传入）——
 * 否则读取发生在 Fragment 的组合作用域里，切一次 Tab 会让三个全屏 Fragment 全部重组。
 */
@Composable
fun GlassBottomNav(
    hazeState: HazeState,
    onSelect: (Int) -> Unit,
) {
    val activeIndex = MainTabStore.currentIndex.intValue
    // remember 避免每次重组重建列表（组件随 activeIndex 重组较频繁）
    val items = remember {
        listOf(
            BottomNavItem("课表", AppIcons.Home),
            BottomNavItem("日历", AppIcons.Calendar),
            BottomNavItem("地图", AppIcons.MapPin),
            BottomNavItem("我的", AppIcons.User),
        )
    }
    val tokens = LocalGlassTokens.current
    val selectedColor = tokens.accent
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 选中胶囊：品牌色低透明度底（浅色下与原先 CampusBlue100 观感一致，深色自动适配）
    val pillSelected = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    Row(
        Modifier
            .fillMaxWidth()
            .liquidGlass(hazeState, RectangleShape, GlassLevel.Bar, GlassEdge.Top)
            .windowInsetsPadding(
                WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                )
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == activeIndex
            // 全部使用 320~380ms 缓出动画，过渡沉稳不仓促
            val tint by animateColorAsState(
                targetValue = if (selected) selectedColor else unselectedColor,
                animationSpec = tween(320),
                label = "navTint",
            )
            val pillColor by animateColorAsState(
                targetValue = if (selected) pillSelected else pillSelected.copy(alpha = 0f),
                animationSpec = tween(380),
                label = "navPill",
            )
            val scale = remember { Animatable(1f) }
            androidx.compose.runtime.LaunchedEffect(selected) {
                scale.animateTo(
                    targetValue = if (selected) 1f else 0.92f,
                    animationSpec = tween(
                        durationMillis = 320,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    // indication = null：**不要涟漪**。
                    // 默认涟漪会被上面这行 clip 裁成一块半透明灰色圆角矩形，盖住四分之一条
                    // 底栏 —— 也就是"点了底栏就有一层灰罩"的那个 bug。玻璃底栏没有实体底色，
                    // 那层灰只能被看成脏。选中反馈由下面的胶囊动画 + 缩放承担，足够了。
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .graphicsLayer {
                                scaleX = scale.value
                                scaleY = scale.value
                            }
                            .clip(RoundedCornerShape(9999.dp))
                            .background(pillColor)
                            .padding(horizontal = 18.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.label,
                        fontSize = 12.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = tint,
                    )
                }
            }
        }
    }
}

private data class BottomNavItem(val label: String, val icon: ImageVector)
