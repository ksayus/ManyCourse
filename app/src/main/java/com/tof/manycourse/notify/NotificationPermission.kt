package com.tof.manycourse.notify

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * `POST_NOTIFICATIONS` 的**问询状态**，以及"下一步该走哪条路"的判定。
 *
 * ## 为什么需要落盘一个"问过一次"
 *
 * 光靠 `shouldShowRequestPermissionRationale()` **判断不出来**该不该再弹：
 * 它返回 false 有两种完全不同的处境 ——
 *
 *  - **从没问过**（系统还不认识这次申请）→ 应该弹；
 *  - **已经问死**（用户拒了两次 / 勾了"不再询问"）→ 再 `launch()` 系统**不弹**
 *    （回调直接以 false 回来）。这正是最难查的那种失败：点了按钮什么都没发生。
 *
 * 所以自己记一个"问过一次"，把这两种处境分开：只有**从没问过**才自动弹。
 *
 * ## 为什么在弹之前就记（而不是等回调）
 *
 * 进程要是在弹窗期间被杀，回调永远不会来。这时宁可**少弹一次**
 * （设置页那条「去授权」的路还在，用户想开随时能开），
 * 也不要变成"每次回到主界面都弹一遍"—— 后者是明确的骚扰，
 * 而且第二次之后系统根本不显示，用户只会觉得按钮坏了。
 *
 * ## 为什么另开一份 prefs，而不是塞进 `UiSettings`
 *
 * 与 `ReminderState` / `SessionVault` 分开是同一个理由：**生命周期不一样**。
 * `UiSettings` 装的是"用户偏好"（玻璃风格、提醒开关），用户想改就能改回来；
 * 这里装的是"系统**已经问过**用户了"这个事实，它是**不可逆的运行态**：
 * 清掉它 = 下次启动再弹一次系统弹窗，而那个弹窗一旦被拒死就再也弹不出来。
 * 混在一起的话，"恢复默认设置"（如果哪天真做了）会顺手把它抹掉。
 *
 * ## 卸载 / 清除数据即失效
 *
 * 这条标记跟着应用私有目录走：卸载重装、清除数据之后归零，
 * 于是新装的应用会重新问一次 —— 这正是我们要的（用户装了个新应用，
 * 系统那边对此也一无所知，本来就得重新申请）。
 */
internal object NotificationPermission {

    /**
     * Android 13（Tiramisu）= 通知变成运行时权限的那一代。
     *
     * 刻意写成字面量而不是 `Build.VERSION_CODES.TIRAMISU`：
     * [shouldAskAutomatically] / [recoveryAction] 是**纯判定**，要在 JVM 单测里跑，
     * 而单测里的 `android.jar` 是个空壳（碰 `Build` 的静态成员会抛 "not mocked"）。
     */
    private const val RuntimePermissionApi = 33

    private const val PREFS_NAME = "manycourse_permission_prefs"
    private const val KEY_ASKED = "post_notifications_asked"

    private var prefs: SharedPreferences? = null

    /** 幂等初始化；用到它的每个入口都先调一次（重复调没有代价）*/
    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 系统有没有**已经**问过用户（不代表给了）*/
    fun askedBefore(): Boolean = prefs?.getBoolean(KEY_ASKED, false) == true

    /** 记下"已经问过一次"；调用时机见类注释（弹之前记）*/
    fun markAsked() {
        prefs?.edit()?.putBoolean(KEY_ASKED, true)?.apply()
    }

    /**
     * 现在这条权限给没给。
     *
     * Android 13 以下**视为已给**：那两代系统没有这条运行时权限，
     * 用它去卡"能不能发通知"会直接把正常机器判成没权限。
     */
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < RuntimePermissionApi ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 该不该**自动**弹一次系统权限弹窗。只有"第一次进主界面"那一次为 true。
     *
     * @param sdkInt 由调用方传 `Build.VERSION.SDK_INT`（纯判定的前提，见 [RuntimePermissionApi]）
     */
    internal fun shouldAskAutomatically(
        sdkInt: Int,
        granted: Boolean,
        askedBefore: Boolean,
    ): Boolean = sdkInt >= RuntimePermissionApi && !granted && !askedBefore

    /**
     * 用户**主动**点「去授权」时该走哪条路 —— 纯判定，被单测钉住。
     *
     * 与 [shouldAskAutomatically] 的区别在于**这是用户点的**：
     * 只要系统还愿意显示弹窗（`canShowRationale` = true），弹一次就是有意义的，
     * 不算"反复骚扰"。真正该换路的是"系统已经不弹了"（false 且问过）。
     */
    internal fun recoveryAction(
        sdkInt: Int,
        granted: Boolean,
        askedBefore: Boolean,
        canShowRationale: Boolean,
    ): RecoveryAction = when {
        granted -> RecoveryAction.None
        // 老系统没有这条运行时权限可申请：只能去系统设置里开"通知"总开关
        sdkInt < RuntimePermissionApi -> RecoveryAction.OpenSystemSettings
        !askedBefore || canShowRationale -> RecoveryAction.RequestPermission
        // 问过、而且系统明确表示"不会再弹"：再 launch 也是无声失败，直接送用户去系统设置
        else -> RecoveryAction.OpenSystemSettings
    }

    /** [recoveryAction] 的现场版：把上下文、系统询问状态、权限状态一次问全 */
    fun currentRecoveryAction(context: Context): RecoveryAction {
        val activity = context.findActivity()
        val rationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        return recoveryAction(
            sdkInt = Build.VERSION.SDK_INT,
            granted = granted(context),
            askedBefore = askedBefore(),
            canShowRationale = rationale,
        )
    }

    /**
     * 跳到系统里**本应用的通知设置页**。
     *
     * 为什么是这一屏而不是应用详情页：应用详情页还要用户自己找"通知"那一项，
     * 而这一屏就是所有通知开关的落点 —— 厂商自己加的那些也能在这里看到
     * （小米的「悬浮通知」就是一个**只有系统设置里才有**的开关，
     * 应用侧无论怎么配参数都管不到它，见 [ClassReminderNotifier] 的类注释）。
     *
     * 机型没实现这个 action 时退到应用详情页，而不是点了没反应。
     */
    fun openSystemNotificationSettings(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        // 从非 Activity 上下文（Fragment 里的 Compose 拿到的 context）拉起也要带 NEW_TASK
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** 「去授权」按钮该走哪条路 */
internal enum class RecoveryAction {
    /** 已经给了 —— 什么都不用做（界面上不该再出现这个入口）*/
    None,

    /** 还能弹系统弹窗，直接申请 */
    RequestPermission,

    /** 系统不会再弹了（或老系统没这条权限）：只能去系统设置里开 */
    OpenSystemSettings,
}

/**
 * 从 `Context` 往上找 `Activity`。
 *
 * Fragment 里 Compose 拿到的 `LocalContext` 外面可能套着若干层 `ContextWrapper`
 * （主题包装、`ContextThemeWrapper`），直接 `as? Activity` 时常拿不到 ——
 * 而 `shouldShowRequestPermissionRationale` 只有 `Activity` 有。
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
