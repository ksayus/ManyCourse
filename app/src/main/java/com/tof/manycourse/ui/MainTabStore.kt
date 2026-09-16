package com.tof.manycourse.ui

import androidx.compose.runtime.mutableIntStateOf

/**
 * 主界面 Tab 状态：Activity 与 Compose 底栏共享。
 *
 * 索引与 `ManyCourseMain.tabFragments` 的顺序**必须一致**：
 * `0=课表 1=日历 2=地图 3=我的`。
 * 顺序即底栏从左到右的顺序（见 `GlassBottomNav` 里的 items）。
 */
object MainTabStore {
    val currentIndex = mutableIntStateOf(0)

    /** Tab 总数；底栏与 Fragment 列表都用它，免得两处各写一个 4 */
    const val TAB_COUNT = 4
}
