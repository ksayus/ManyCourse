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
private val LightColorScheme = lightColorScheme(
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
)

private val DarkColorScheme = darkColorScheme(
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
)

@Composable
fun ManyCourseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 玻璃风格由全局设置驱动：切换后 mode 变化 → 令牌重算 → 全部组件重组生效（无需重启）
    val glassMode = UiSettings.glassMode.value
    val tokens = remember(glassMode, darkTheme) { glassTokens(glassMode, darkTheme) }

    CompositionLocalProvider(
        LocalGlassMode provides glassMode,
        LocalGlassTokens provides tokens,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content
        )
    }
}
