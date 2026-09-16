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
import com.tof.manycourse.ui.CalendarLayoutToggle
import com.tof.manycourse.ui.CalendarScreen
import com.tof.manycourse.ui.CourseDetailDialog
import com.tof.manycourse.ui.components.GlassBottomNav
import com.tof.manycourse.ui.components.GlassHeader
import com.tof.manycourse.ui.components.LiquidGlassBackground
import com.tof.manycourse.ui.components.isTabForeground
import com.tof.manycourse.ui.theme.ManyCourseTheme
import java.time.LocalDate

/** 日历页 Fragment：ComposeView 承载液态玻璃日历界面（类名沿用 CalenderFragment） */
class CalenderFragment : Fragment() {

    companion object {
        /** 底部导航索引（课表/日历/地图/我的），用于背景动画可见性门控 */
        private const val TAB_INDEX = 1
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
                // 课程详情浮层：内容和可见性分开存（关闭时内容留着，退出动画才有东西可画）。
                // 内容是"某一天的全部课"：详情卡列那天有什么课（按节次排），不是点中的那一格
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
                        // 页头右侧的「周 / 月」切换：与设置页的「日历布局」是同一个状态，
                        // 放在页头是因为"换视图"是看日历时的常用动作，不该每次都绕进设置页
                        GlassHeader(
                            hazeState,
                            "日程日历",
                            actions = { CalendarLayoutToggle() },
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                // 横屏 / 异形屏：中间内容避开侧边挖孔与圆角（竖屏为 0，无影响）
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                                )
                        ) {
                            CalendarScreen(
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
                    // 课程详情：挂在根 Box 上（和「添加课程」一样）才能盖住整页
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
