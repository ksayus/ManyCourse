package com.tof.manycourse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import com.tof.manycourse.ui.AppIcons
import com.tof.manycourse.ui.theme.GlassEdge
import com.tof.manycourse.ui.theme.GlassLevel
import com.tof.manycourse.ui.theme.LocalGlassTokens

/**
 * 玻璃头部：标题 + 通知按钮（规范：56dp 高），自带安全区避让。
 *
 * 层次：页头是"浮在内容之上"的上层 → 底色比内容亮一档（barTint），
 * 并在玻璃条**下方**画一道柔和渐隐的投影（[GlassTokens.barShadow]）。
 * 投影刻意不垫在玻璃底下：半透明玻璃会把下层阴影透上来，整条反而发暗。
 *
 * 适配不同屏幕：
 * - 安全区用 `statusBars ∪ displayCutout`（状态栏 + **挖孔/刘海** + 横屏侧边安全区），
 *   普通手机竖屏与原来完全一致，异形屏 / 横屏不会被挖孔压住标题；不含 IME；
 * - 玻璃背景铺满到屏幕物理边缘（安全区内边距加在背景之后），
 *   且**四周都不画描边/分隔线**：页头玻璃与内容色差极小，一条 1dp 线反而把
 *   "状态栏 + 标题"切成独立横条；内容在自己的滚动容器里，不会滚到页头下方。
 */
@Composable
fun GlassHeader(hazeState: HazeState, title: String) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val tokens = LocalGlassTokens.current
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(hazeState, RectangleShape, GlassLevel.Bar, GlassEdge.None)
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(WindowInsets.displayCutout).only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    )
                )
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 24.sp,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = AppIcons.Bell,
                    contentDescription = "通知",
                    tint = contentColor,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { }
                        .padding(8.dp),
                )
            }
        }

        // 页头压在内容上的柔和投影：画在玻璃条下方渐隐（不是垫在玻璃底下）
        if (tokens.barShadow > 0.dp) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(tokens.barShadow)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                tokens.shadowColor.copy(alpha = tokens.shadowColor.alpha * 0.75f),
                                Color.Transparent,
                            ),
                        )
                    )
            )
        }
    }
}
