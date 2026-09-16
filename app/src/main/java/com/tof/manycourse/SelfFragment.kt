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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.tof.manycourse.ui.EditProfileDialog
import com.tof.manycourse.ui.ProfileScreen
import com.tof.manycourse.ui.components.GlassBottomNav
import com.tof.manycourse.ui.components.GlassHeader
import com.tof.manycourse.ui.components.LiquidGlassBackground
import com.tof.manycourse.ui.components.isTabForeground
import com.tof.manycourse.ui.theme.ManyCourseTheme

/** “我的”页 Fragment：ComposeView 承载液态玻璃个人中心 */
class SelfFragment : Fragment() {

    companion object {
        /** 底部导航索引（课表/日历/地图/我的），用于背景动画可见性门控 */
        private const val TAB_INDEX = 3
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            ManyCourseTheme {
                val hazeState = remember { HazeState() }
                var showEditProfile by remember { mutableStateOf(false) }
                // 仅当本页可见且在前台时驱动背景动画，隐藏页零逐帧开销
                val backgroundAnimating = isTabForeground(TAB_INDEX)

                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().hazeSource(state = hazeState)) {
                        LiquidGlassBackground(animate = backgroundAnimating)
                    }
                    Column(Modifier.fillMaxSize()) {
                        GlassHeader(hazeState, "我的")
                        Box(
                            Modifier
                                .weight(1f)
                                // 横屏 / 异形屏：中间内容避开侧边挖孔与圆角（竖屏为 0，无影响）
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                                )
                        ) {
                            ProfileScreen(hazeState) { showEditProfile = true }
                        }
                        // 底栏在组件内部读取当前索引，避免切换 Tab 时三张全屏页面一起重组
                        GlassBottomNav(hazeState) { index ->
                            (activity as? ManyCourseMain)?.selectTab(index)
                        }
                    }
                    // 浮层常驻组合以便播放退出（pop）动画，可见性由 visible 控制
                    EditProfileDialog(
                        visible = showEditProfile,
                        onDismiss = { showEditProfile = false },
                    )
                }
            }
        }
    }
}
