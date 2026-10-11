package com.tof.manycourse

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.tof.manycourse.data.CourseSync
import com.tof.manycourse.data.LoginSettings
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.notify.ClassReminderNotifier
import com.tof.manycourse.notify.NotificationPermission
import com.tof.manycourse.reminder.ClassReminderScheduler
import com.tof.manycourse.ui.MainTabStore
import com.tof.manycourse.ui.Motion
import com.tof.manycourse.ui.disableContrastScrims
import com.tof.manycourse.widget.WidgetRefresh

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
 *
 * 也是**通知权限的唯一申请入口**（第一次进主界面时问一次，见 [askNotificationPermissionOnce]）。
 */
class ManyCourseMain : AppCompatActivity() {

    private lateinit var tabFragments: List<Fragment>
    private var activeIndex = 0

    /**
     * 通知权限的申请结果。
     *
     * 拿到权限后重排一次提醒（幂等）：闹钟链本身**不看**这条权限，
     * 但"提醒发得出来"要看 —— 重排一次让下一条提醒按刚变化的状态重算一遍，
     * 与设置页拿到权限后的处理是同一条口径。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ClassReminderScheduler.reschedule(this)
    }

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

        // 第一次进主界面时问一次通知权限（Android 13+）—— 详见方法注释
        askNotificationPermissionOnce()
    }

    /**
     * **第一次进到主界面**时申请一次通知权限。
     *
     * ## 为什么不放在登录页
     *
     * 登录页在**还没登录**的时候就会显示（用户可能只是打开看一眼学校列表），
     * 那时候弹一个"要不要允许通知"没有任何上下文：用户不知道这是为了课前提醒，
     * 顺手点掉就再也拿不回来了。主界面才是"他已经在用这个 App"的地方。
     *
     * ## 为什么必须有一个"问过就不再自动问"的闸门
     *
     * 这个方法是挂在 `onCreate` 上的，而主界面**每次冷启动、每次从登录页进来**都会重建。
     * 没有闸门的话就是"每次回来都弹"—— 而 Android 13 起同一条权限被拒两次之后
     * 系统**根本不再显示弹窗**，那时用户点开 App 只会看到什么都没发生。
     * 闸门与"系统还能不能弹"的判定都在 [NotificationPermission] 里，被单测钉住。
     *
     * ## 被拒之后的路
     *
     * 这里**不再自动弹**，但设置页的「通知设置」卡片会把"未授予 —— 提醒发不出来"如实写出来，
     * 并给一个入口（还能弹就再申请一次，系统已经不弹了就直接跳系统通知设置）。
     * 也就是说：不骚扰，但也不是死路。
     */
    private fun askNotificationPermissionOnce() {
        // ★ 渠道**先建好**再问权限，理由有两层：
        //   ① Android 13 的权限弹窗里引用的就是这条渠道的说明（用户点"允许"时知道自己在允许什么）；
        //   ② 渠道不先建出来，系统设置里就只有"通知"一个总开关，用户无从知道该开哪一项。
        ClassReminderNotifier.ensureChannel(this)

        NotificationPermission.attach(this)
        val shouldAsk = NotificationPermission.shouldAskAutomatically(
            sdkInt = Build.VERSION.SDK_INT,
            granted = NotificationPermission.granted(this),
            askedBefore = NotificationPermission.askedBefore(),
        )
        if (!shouldAsk) return

        // 弹之前就记下"问过了"：见 NotificationPermission.markAsked 的注释
        NotificationPermission.markAsked()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 每次回到主界面都重排一次课前提醒、刷新一次桌面小组件。
     *
     * ## 为什么挂在这里，而不是挂在"同步成功"的回调里
     *
     * 同步是异步的，而"课表变了"这件事并不只有同步一个来源（退出登录、用户改提前量、
     * 系统时间跳变都会影响"下一次该在什么时候提醒"）。挂在每次 `onResume` 上，
     * 这些路径**一条都不用单独接**，而且天然覆盖了刚登录完那一次
     * （登录流程走完就是进这个 Activity）。
     *
     * ## 就算这里漏了一次，也不会"提醒错"
     *
     * 闹钟醒来时 `ClassReminderReceiver` 会拿**当时**的课表重新解一遍再决定发不发，
     * 所以排早了/排旧了最多是多醒一次，不会发出错误的提醒。
     * 这里只是让"该响的时候真的排上了"，属于及时性而不是正确性。
     */
    override fun onResume() {
        super.onResume()
        ClassReminderScheduler.reschedule(this)
        WidgetRefresh.updateAll(this)
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
