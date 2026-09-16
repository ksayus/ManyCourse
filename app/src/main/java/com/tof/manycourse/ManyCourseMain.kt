package com.tof.manycourse

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.tof.manycourse.data.CourseSync
import com.tof.manycourse.data.LoginSettings
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.ui.MainTabStore
import com.tof.manycourse.ui.Motion
import com.tof.manycourse.ui.disableContrastScrims

/**
 * 主界面 Activity：底部导航为 Compose 玻璃底栏（位于各 Fragment 内），
 * 本类负责 Fragment 的挂载/切换与恢复。
 *
 * 页面顺序（= 底栏顺序，两处必须一致，见 [MainTabStore]）：
 * `0 课表 / 1 日历 / 2 地图 / 3 我的`
 *
 * 另外这里还是**登录后同步的起点**：挂载完页面就按登录时选的学校去拉
 * 姓名 + 课表（`CourseSync`），失败也不拦着用户进主界面，
 * 只在课表页显示原因。
 */
class ManyCourseMain : AppCompatActivity() {

    private lateinit var tabFragments: List<Fragment>
    private var activeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // auto：夜间模式下系统栏图标自动转为浅色，避免深色背景上看不见
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        window.disableContrastScrims()
        setContentView(R.layout.manycourse_main)

        if (savedInstanceState == null) {
            // 首次进入：挂载四个 Fragment，默认显示课表
            tabFragments = listOf(
                ClassScheduleFragment(),
                CalenderFragment(),
                MapFragment(),
                SelfFragment(),
            )
            supportFragmentManager.beginTransaction().apply {
                // 倒序 add 是为了让"课表"最后加进去、天然位于最上层
                add(R.id.fragment_container, tabFragments[3], TAB_PROFILE).hide(tabFragments[3])
                add(R.id.fragment_container, tabFragments[2], TAB_MAP).hide(tabFragments[2])
                add(R.id.fragment_container, tabFragments[1], TAB_CALENDAR).hide(tabFragments[1])
                add(R.id.fragment_container, tabFragments[0], TAB_SCHEDULE)
            }.commit()
            activeIndex = 0
        } else {
            // 横屏旋转 / 系统回收后恢复：必须找回 FragmentManager 恢复的实例，
            // 若使用字段上的新实例去 show 会挂载失败、页面空白
            tabFragments = TAB_TAGS.mapIndexed { i, tag ->
                supportFragmentManager.findFragmentByTag(tag) ?: run {
                    val fresh = newFragmentAt(i)
                    supportFragmentManager.beginTransaction()
                        .add(R.id.fragment_container, fresh, tag).hide(fresh).commit()
                    fresh
                }
            }
            activeIndex = tabFragments.indexOfFirst { !it.isHidden }
            if (activeIndex < 0) {
                // 全部被隐藏（恢复异常兜底）：显示课表
                activeIndex = 0
                supportFragmentManager.beginTransaction().show(tabFragments[0]).commit()
            }
        }
        MainTabStore.currentIndex.intValue = activeIndex

        // 按登录的学校拉课表 + 姓名。转屏/重建时 CourseSync 内部会跳过重复请求。
        // 学校优先取会话里的那个（冷启动恢复出来的会话更权威），兜底才用下拉里保存的。
        CourseSync.sync(
            schoolId = SessionStore.schoolId.value ?: LoginSettings.selectedSchoolId.value,
            account = SessionStore.account.value,
        )
    }

    /**
     * 底部导航切换（由 Fragment 内的 Compose 底栏调用）。
     *
     * 页面切换动画：通过 FragmentTransaction 的进入/离开动画资源实现——
     * 淡入淡出 + 8dp/-4dp 轻微位移（FastOutSlowInEasing，240/180ms），
     * 同时配置了反向（pop）动画，保证返回方向也有对应过渡；
     * 系统关闭动画（动画时长缩放 = 0 / 减少动态效果）时不挂动画，避免无效帧。
     */
    fun selectTab(index: Int) {
        if (index == activeIndex || index !in tabFragments.indices) return
        supportFragmentManager.beginTransaction().apply {
            if (Motion.enabled()) {
                setCustomAnimations(
                    R.anim.tab_enter,
                    R.anim.tab_exit,
                    R.anim.tab_pop_enter,
                    R.anim.tab_pop_exit,
                )
            }
            hide(tabFragments[activeIndex])
            show(tabFragments[index])
        }.commit()
        activeIndex = index
        MainTabStore.currentIndex.intValue = index
    }

    companion object {
        private const val TAB_SCHEDULE = "schedule"
        private const val TAB_CALENDAR = "calendar"
        private const val TAB_MAP = "map"
        private const val TAB_PROFILE = "profile"

        /** Fragment tag，**顺序即 Tab 索引**（与 [MainTabStore] 的注释一一对应） */
        private val TAB_TAGS = listOf(TAB_SCHEDULE, TAB_CALENDAR, TAB_MAP, TAB_PROFILE)

        /** 按 Tab 索引新建页面；恢复时找不到旧实例才用得上 */
        private fun newFragmentAt(index: Int): Fragment = when (index) {
            0 -> ClassScheduleFragment()
            1 -> CalenderFragment()
            2 -> MapFragment()
            else -> SelfFragment()
        }
    }
}
