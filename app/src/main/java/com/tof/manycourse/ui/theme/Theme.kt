package com.tof.manycourse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.tof.manycourse.data.UiSettings

// campus-schedule 设计系统 → Material3 配色映射
//
// ★ 两个 scheme 都是 `internal`：周视图卡片的**字色**要从这里取（`gridCardTextColor`），
//   而"卡片字色在两个主题下都读得清"这条护栏是 JVM 单测（`GridCardContrastTest`）用
//   **这套真实取值**量的 —— `private` 的话单测只能自己再编一份角色色，那两份迟早会漂。
internal val LightColorScheme = lightColorScheme(
    primary = CampusBlue600,
    onPrimary = Color.White,
    primaryContainer = CampusBlue50,
    onPrimaryContainer = CampusBlue900,
    secondary = CampusSlate200,
    onSecondary = CampusSlate900,
    secondaryContainer = CampusSlate100,
    onSecondaryContainer = CampusSlate800,
    background = Color.White,
    onBackground = CampusSlate900,
    surface = Color.White,
    onSurface = CampusSlate900,
    surfaceVariant = CampusSlate100,
    onSurfaceVariant = CampusSlate500,
    outline = CampusSlate200,
    error = CampusRose600,
    onError = Color.White,
    errorContainer = CampusRose50,
    // 反色表面（M3 里"浅色主题下的一块深色表面"那一对）：本项目只是把它补齐，
    // 让 `gridCardTextColor` 的"两支角色取更暗的那支"在本项目里是确定的取值
    inverseSurface = CampusSlate900,
    inverseOnSurface = Color.White,
)

internal val DarkColorScheme = darkColorScheme(
    primary = CampusBlue400,
    onPrimary = Color.White,
    primaryContainer = CampusBlue900,
    onPrimaryContainer = CampusBlue50,
    secondary = CampusSlate800,
    onSecondary = Color.White,
    background = CampusSlate900,
    onBackground = Color.White,
    surface = CampusSlate900,
    onSurface = Color.White,
    surfaceVariant = CampusSlate800,
    onSurfaceVariant = CampusSlate400,
    outline = CampusSlate800,
    error = CampusRose500,
    onError = Color.White,
    // ★ 这一对是周视图卡片字色的来源：深色主题下的卡片是一块**浅色**表面
    //   （浅底深字，见 `ui/CalendarGridLayout.kt` 的"卡片面貌"），而这里的 `onSurface`
    //   是白的 —— 白字压在浅卡上等于没字。M3 给"深色主题里的浅色表面"准备的就是这一对：
    //   `inverseSurface` 亮、`inverseOnSurface` 暗，卡片取后者。
    inverseSurface = CampusSlate50,
    inverseOnSurface = CampusSlate900,
)

@Composable
fun ManyCourseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 玻璃风格 + 配色都由全局设置驱动：任一变化 → 令牌重算 → 全部组件重组生效（无需重启）。
    // 配色是"色相"维度、模式是"结构"维度，两者正交（见 GlassPalette 的 KDoc）。
    val glassMode = UiSettings.glassMode.value
    val glassPalette = UiSettings.glassPalette.value
    val tokens = remember(glassMode, glassPalette, darkTheme) {
        glassTokens(glassMode, darkTheme, glassPalette)
    }

    CompositionLocalProvider(
        LocalGlassMode provides glassMode,
        LocalGlassPalette provides glassPalette,
        LocalGlassTokens provides tokens,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content
        )
    }
}
