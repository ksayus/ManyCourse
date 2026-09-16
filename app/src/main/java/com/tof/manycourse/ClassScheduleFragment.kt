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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.ui.AddCourseScreen
import com.tof.manycourse.ui.CourseDetailDialog
import com.tof.manycourse.ui.ScheduleScreen
import com.tof.manycourse.ui.components.GlassBottomNav
import com.tof.manycourse.ui.components.GlassHeader
import com.tof.manycourse.ui.components.LiquidGlassBackground
import com.tof.manycourse.ui.components.isTabForeground
import com.tof.manycourse.ui.theme.ManyCourseTheme
import java.time.LocalDate

/** 课表页 Fragment：ComposeView 承载液态玻璃课表界面 */
class ClassScheduleFragment : Fragment() {

    companion object {
        /** 底部导航索引（课表/日历/地图/我的），用于背景动画可见性门控 */
        private const val TAB_INDEX = 0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            ManyCourseTheme {
                val hazeState = remember { HazeState() }
                var showAddCourse by remember { mutableStateOf(false) }
                var addCourseWeekday by remember { mutableIntStateOf(1) }
                // 课程详情浮层：内容和可见性**分开存** —— 关闭时内容要留着，
                // 退出动画那 200ms 才有东西可画（与 AddCourseScreen 同一套路子）。
                // 内容是"某一天的全部课"：详情卡列那天有什么课，不是点中的那一门
                var detailDate by remember { mutableStateOf<LocalDate?>(null) }
                var detailCourses by remember { mutableStateOf<List<CourseEntry>>(emptyList()) }
                var showDetail by remember { mutableStateOf(false) }
                // 仅当本页可见且在前台时驱动背景动画，隐藏页零逐帧开销
                val backgroundAnimating = isTabForeground(TAB_INDEX)

                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().hazeSource(state = hazeState)) {
                        LiquidGlassBackground(animate = backgroundAnimating)
                    }
                    Column(Modifier.fillMaxSize()) {
                        GlassHeader(hazeState, "本周课表")
                        Box(
                            Modifier
                                .weight(1f)
                                // 横屏 / 异形屏：中间内容避开侧边挖孔与圆角（竖屏为 0，无影响）
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                                )
                        ) {
                            ScheduleScreen(
                                hazeState = hazeState,
                                onAddCourse = { weekday ->
                                    addCourseWeekday = weekday
                                    showAddCourse = true
                                },
                                onCourseClick = { date, courses ->
                                    detailDate = date
                                    detailCourses = courses
                                    showDetail = true
                                },
                            )
                        }
                        // 底栏在组件内部读取当前索引，避免切换 Tab 时三张全屏页面一起重组
                        GlassBottomNav(hazeState) { index ->
                            (activity as? ManyCourseMain)?.selectTab(index)
                        }
                    }
                    // 浮层常驻组合以便播放退出（pop）动画，可见性由 visible 控制
                    AddCourseScreen(
                        visible = showAddCourse,
                        initialWeekday = addCourseWeekday,
                        onDismiss = { showAddCourse = false },
                        onSaved = { showAddCourse = false },
                    )
                    // 课程详情：挂在根 Box 上（和「添加课程」一样）才能盖住整页 ——
                    // 挂在内容区里的话遮罩会被页头/底栏截断
                    CourseDetailDialog(
                        visible = showDetail,
                        date = detailDate,
                        courses = detailCourses,
                        onDismiss = { showDetail = false },
                    )
                }
            }
        }
    }
}
