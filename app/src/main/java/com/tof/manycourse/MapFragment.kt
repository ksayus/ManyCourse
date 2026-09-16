package com.tof.manycourse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.tof.manycourse.data.LoginSettings
import com.tof.manycourse.ui.MapScreen
import com.tof.manycourse.ui.components.GlassBottomNav
import com.tof.manycourse.ui.components.GlassHeader
import com.tof.manycourse.ui.components.LiquidGlassBackground
import com.tof.manycourse.ui.components.isTabForeground
import com.tof.manycourse.ui.theme.ManyCourseTheme

/**
 * 校园地图页 Fragment：ComposeView 承载「当前登录学校的地图」。
 *
 * 显示哪张图由 `LoginSettings.selectedSchoolId`（登录页选中的学校）决定，
 * 映射表在 `data/SchoolMap.kt`。
 */
class MapFragment : Fragment() {

    companion object {
        /** 底部导航索引（课表/日历/地图/我的），用于背景动画可见性门控 */
        private const val TAB_INDEX = 2
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            ManyCourseTheme {
                val hazeState = remember { HazeState() }
                // 仅当本页可见且在前台时驱动背景动画，隐藏页零逐帧开销
                val backgroundAnimating = isTabForeground(TAB_INDEX)

                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().hazeSource(state = hazeState)) {
                        LiquidGlassBackground(animate = backgroundAnimating)
                    }
                    Column(Modifier.fillMaxSize()) {
                        GlassHeader(hazeState, "校园地图")
                        Box(
                            Modifier
                                .weight(1f)
                                // 横屏 / 异形屏：中间内容避开侧边挖孔与圆角（竖屏为 0，无影响）
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                                )
                        ) {
                            // 直接读持久化的学校 id：登录页选完就写进去了，
                            // 地图页不需要自己再维护一份"当前学校"
                            MapScreen(schoolId = LoginSettings.selectedSchoolId.value)
                        }
                        GlassBottomNav(hazeState) { index ->
                            (activity as? ManyCourseMain)?.selectTab(index)
                        }
                    }
                }
            }
        }
    }
}
