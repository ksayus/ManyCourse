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
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.ui.MapScreen
import com.tof.manycourse.ui.components.GlassBottomNav
import com.tof.manycourse.ui.components.GlassHeader
import com.tof.manycourse.ui.components.LiquidGlassBackground
import com.tof.manycourse.ui.components.isTabForeground
import com.tof.manycourse.ui.theme.ManyCourseTheme

/**
 * 校园地图页 Fragment：ComposeView 承载「当前学校的校区地图」。
 *
 * 显示哪张图由**当前学校**（`SessionStore.schoolId`，其次登录页选中的那个）决定，
 * 具体是哪个校区由**定位**决定（挑最近的校区，见 `ui/MapScreen.kt` 与 `data/SchoolMap.kt`）；
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
                // 本页是否"可见且在前台"：两个用途 ——
                //  ① 冻结/恢复背景动画（隐藏页零逐帧开销）；
                //  ② 门控定位：四个页面常驻，不门控的话 App 一启动就会弹定位权限框
                val tabVisible = isTabForeground(TAB_INDEX)

                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().hazeSource(state = hazeState)) {
                        LiquidGlassBackground(animate = tabVisible)
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
                            // 与课表页/日历页取"哪所学校的课表"同一个口径：登录的学校优先，
                            // 其次是登录页里选中的那个 —— 三处不能各说各话
                            MapScreen(
                                schoolId = SessionStore.schoolId.value
                                    ?: LoginSettings.selectedSchoolId.value,
                                visible = tabVisible,
                            )
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
