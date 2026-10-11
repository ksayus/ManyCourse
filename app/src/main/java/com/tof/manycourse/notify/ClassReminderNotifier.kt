package com.tof.manycourse.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tof.manycourse.MainActivity
import com.tof.manycourse.R
import com.tof.manycourse.data.ClassSlot
import com.tof.manycourse.data.Course
import com.tof.manycourse.data.CourseEntry
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * **课前提醒通知**：先发一条标标准准的 Android 通知，再按设备能力叠一层小米展示参数。
 *
 * ## 分层结构（这是整个需求里"超级岛 / 焦点通知"部分的落点）
 *
 * ```
 *   标准 Android 通知                        ← 所有机型、任何情况下都建好
 *     ├─ 档位 = Island → hyperisland_kit     ← 第三方社区库（小米 + 系统声明支持岛）
 *     ├─ 档位 = Focus  → miui.focus.param    ← 焦点通知（OS2 起的小米机型）
 *     └─ 档位 = Standard → 什么都不加          ← 非小米机型：不掺任何私货
 * ```
 *
 * 三层是**叠加**不是替换：增强只是往同一条通知的 `extras` 里多塞几个键。
 * 所以哪怕小米那边完全不认这些参数，用户收到的仍然是一条正常的课前提醒。
 *
 * ## 超级岛那一层为什么包在 `runCatching` 里
 *
 * 它是**唯一**依赖第三方代码的一层，而且是**非官方协议**（官方准入个人应用拿不到，
 * 见 [VendorCapability] 的注释）。第三方库抛异常、字段变了、或者系统不认，
 * 都不该让"课前提醒"这个功能整体失效 —— 所以失败就**降级成焦点通知**，
 * 这正是需求里"如果尝试了不行就改成焦点通知"那条回退。
 *
 * ## 通知 id 固定，但**每条提醒都先撤再发**
 *
 * 同一时刻通知栏里只该有**一条**课前提醒：下一节课开始时上一条已经没有意义了，
 * 留着只会在通知栏里堆一串过去的课。所以固定 id、覆盖式发送。
 *
 * 但"固定 id"与"每条都要响"本身是矛盾的：系统把**同 id 的再次发送**当成
 * **同一条通知的更新**，默认就可能不再提醒 —— 早先还显式加了 `setOnlyAlertOnce(true)`
 * （那是为了压住"同一节课被系统提前唤醒"造成的重复触发）。结果就是：
 * 上一条提醒还躺在通知栏里时，下一条同 id 更新 **既不响也不弹**，
 * 用户看到的现象正是"通知发了，但没提醒"。
 *
 * 现在的做法是**发之前先 `cancel(NOTIFICATION_ID)`**：撤掉之后这一次 `notify`
 * 在系统眼里就是一条**新通知**，一定会响、一定会弹。而重复触发那件事本来就有更合适的
 * 守卫 —— `reminder/ReminderState` 按"课名 + 日期 + 节次"记着这节课提醒过没有，
 * 它比 `onlyAlertOnce` 准得多（`onlyAlertOnce` 连"下一节**不同**的课"一起压掉了）。
 *
 * ## 渠道 id 为什么是 `class_reminder_v2`
 *
 * **通知渠道一旦创建就不能修改**：重要性、声音、振动、锁屏可见性全都在创建那一刻定死，
 * 之后应用侧再改也不生效（用户在系统里亲手调过的设置更不能被应用覆盖回去）。
 * 所以"给渠道补上声音与振动模式"这件事只有一个做法 —— **换一个新 id 重新建**。
 * 旧 id（`class_reminder`）留在系统里不动：它已经不会再被写入，用户想删自己删；
 * 反过来"复用旧 id 再把参数改一遍"是**不存在**的操作，只会让下一个人以为改了。
 *
 * ## ★ 小米还有一道我们管不到的开关：「悬浮通知」
 *
 * 上面做的所有事情（渠道重要性 = HIGH、`priority = MAX`、`category = REMINDER`、
 * 声音、振动）都只能保证"系统**愿意**弹"。小米（MIUI / HyperOS）在系统设置里对
 * **每个应用**还有一道单独的「悬浮通知」权限，它关着的时候**参数再对也不会弹横幅** ——
 * 通知只会安静地进通知栏，看起来就和"没提醒"一样。这不是应用能读、能改的东西，
 * 所以只剩一条路：**设置页 / 开发者页应当如实告诉用户去检查这一项**
 * （落点在 `ui/SettingsScreen.kt` 的「通知设置」卡片：那一行会直说，
 * 并给一个直达系统通知设置的入口）。
 */
object ClassReminderNotifier {

    private const val TAG = "ClassReminder"

    /** 渠道 id；**换过**一次，理由见类注释（渠道不可变）*/
    const val CHANNEL_ID = "class_reminder_v2"

    /** 固定 id + 发前先撤：见类注释。取值本身不重要，只要不与应用内其它通知撞上 */
    private const val NOTIFICATION_ID = 0x4D43

    /** 岛上那张小图在 `miui.focus.pics` 里的键（前缀由社区库补）*/
    private const val ISLAND_PIC_KEY = "reminder"

    /**
     * 建通知渠道（幂等）。
     *
     * 渠道重要性一旦创建就**不能改**，所以必须一次定对：
     * `IMPORTANCE_HIGH` = 会弹浮动通知（heads-up）。课前提醒要的就是这个 ——
     * 一条安静躺在通知栏里的提醒，对"还有 15 分钟上课"没有任何意义。
     *
     * 声音与振动同理，**必须在创建渠道的这一刻给全**：默认通知音（跟随系统，
     * 用户换过提示音才符合预期）+ 两段短振动，都是"要让人抬头看一眼"的配置。
     * 换渠道 id 才可能改这些，见类注释。
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
                // 默认通知音而不是自带音频资源：不去替用户决定提示音长什么样。
                // 第二个参数（AudioAttributes）传 null = 用平台默认的用法/内容类型，
                // 我们没有任何理由在这里另立一套音频属性
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
                enableVibration(true)
                // 振动模式（0, 300, 200, 300）：等 300ms 振一次是"提醒"，
                // 连振两下比单次更长、更容易在口袋里被注意到
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setShowBadge(true)
                // 锁屏上只显示"有一条提醒"，内容要点开才看得到：课程名+教室属于个人行程
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    /**
     * 发出（或更新）课前提醒。
     *
     * @param slot **必须是有可信时刻的那一节**（由 `UpcomingClass.forReminder` 解出来）。
     *   没有作息表的学校根本走不到这里 —— 见 `data/NextClass.kt` 的注释。
     * @param forcedTier **仅供开发者模式**（见 `ui/DevToolsScreen.kt`）：跳过能力检测、
     *   强制按指定档位发一条，用来在真机上当场看"这一档到底长什么样"。
     *   传 null（默认）走正常的运行时探测 —— 生产路径永远是这个。
     */
    fun notify(context: Context, slot: ClassSlot, forcedTier: PresentationTier? = null) {
        ensureChannel(context)

        // 两道闸门，管的不是同一件事：
        //
        //  ① areNotificationsEnabled() = **用户有没有把通知整个关掉**（系统设置里的开关）
        //  ② checkSelfPermission(POST_NOTIFICATIONS) = Android 13 起那个**独立的运行时权限**
        //
        // 只查 ① 是不够的：权限没给时 ① 可能仍返回 true，而 notify() 会被系统直接丢掉。
        // 这一步也是 lint 的 `MissingPermission` 要求的 —— 它是**对的**，不是误报。
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.i(TAG, "系统通知被关闭，跳过本次课前提醒")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "POST_NOTIFICATIONS 未授予，跳过本次课前提醒")
            return
        }

        val title = reminderTitle(context, slot)
        val text = reminderText(slot)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(title)
            .setContentText(text)
            // 教室有时会很长（「实验楼 B-205 江宁校区」），展开样式让它在折叠态被截断后仍看得全
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // MAX：课前提醒是"放下手里的事"级别的，通道重要性已经定了 HIGH，
            // 这里再给到最高，是为了让系统在排序/免打扰判定上把它当成要紧的那一类
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // REMINDER 而不是 EVENT：这是"到点要去做某件事"，不是"发生了一件事"。
            // 系统会按类型决定排序、免打扰与专注模式下的表现
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openScheduleIntent(context))

        applyVendorEnhancement(context, builder, slot, title, text, forcedTier)

        val manager = NotificationManagerCompat.from(context)
        try {
            // ★ 先撤再发，**刻意不用 setOnlyAlertOnce**：见类注释 ——
            //   固定 id 的"更新"在系统眼里不是新通知，同 id 更新时既不响也不弹，
            //   于是"上一条还躺在通知栏里"的时候，下一条提醒就静默了。
            manager.cancel(NOTIFICATION_ID)
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // 竞态：权限恰好在上面那次检查与这次发送之间被撤销（用户就在那一刻关掉了通知）。
            // 少发一条提醒是可以接受的，为此崩掉整个闹钟接收器不行。
            Log.w(TAG, "发送课前提醒时通知权限被撤销：${e.message}")
        }
    }

    /** 「还有 N 分钟上课」/「就要上课了」 */
    private fun reminderTitle(context: Context, slot: ClassSlot): String {
        val minutes = slot.startsIn?.toMinutes() ?: 0L
        return if (minutes > 0) {
            context.getString(R.string.reminder_title_soon, minutes.toInt())
        } else {
            context.getString(R.string.reminder_title_now)
        }
    }

    /**
     * 正文：`高等数学 · 第1-2节 9:00~10:20 · A-101`。
     *
     * 三段都可能缺（没有作息表 → 没时刻；教务系统没给教室 → 没地点），
     * 所以一律 `filter { isNotBlank() }` 之后再拼 —— 缺哪段就少哪段，
     * 不会留下「· ·」这种看不出是数据缺了还是排版坏了的痕迹。
     */
    private fun reminderText(slot: ClassSlot): String = listOf(
        slot.name,
        listOf(slot.periodLabel, slot.timeRangeText).filter { it.isNotBlank() }.joinToString(" "),
        slot.room,
    ).filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * 按设备能力叠一层小米展示参数。**任何一层失败都只是少一层增强**。
     */
    private fun applyVendorEnhancement(
        context: Context,
        builder: NotificationCompat.Builder,
        slot: ClassSlot,
        title: String,
        text: String,
        forcedTier: PresentationTier?,
    ) {
        val tier = forcedTier ?: currentTier(context)

        when (tier) {
            PresentationTier.Island -> {
                // ★ 试过不行就退焦点通知：这一档是"尝试"，不是"保证"（见类注释）。
                //   但**开发者模式强制指定这一档时不回退** —— 那时要看的正是"这一档真实的样子"，
                //   悄悄换成焦点通知会让人以为岛成功了。
                if (!applyIsland(context, builder, slot, text, title) && forcedTier == null) {
                    applyFocus(builder, title, text)
                }
            }

            PresentationTier.Focus -> applyFocus(builder, title, text)

            // 非小米机型 / 系统不支持：什么都不加。标准通知已经发出去了
            PresentationTier.Standard -> Unit
        }
    }

    /** 焦点通知那一层：只往 extras 里塞一个 JSON 字符串 */
    private fun applyFocus(builder: NotificationCompat.Builder, title: String, text: String) {
        builder.addExtras(
            Bundle().apply { putString(FocusParam.EXTRA_KEY, FocusParam.json(title, text)) }
        )
    }

    /**
     * 超级岛那一层（第三方社区库）。
     *
     * @return true = 参数生成成功；false = 失败，调用方应退回焦点通知
     *
     * ★ 整个体包在 `runCatching` 里：这是全项目**唯一**依赖第三方非官方协议的代码路径，
     *   它挂了不能带走"课前提醒"这个功能。
     */
    private fun applyIsland(
        context: Context,
        builder: NotificationCompat.Builder,
        slot: ClassSlot,
        text: String,
        ticker: String,
    ): Boolean = runCatching {
        val island = HyperIslandNotification.Builder(context, FocusParam.BUSINESS, ticker)
            // 库的日志默认开着，会把整段 JSON 打进 logcat；课程名/教室属于个人行程，关掉
            .setLogEnabled(false)
            .setIslandConfig(priority = 2, dismissible = true)

        island.addPicture(HyperPicture(ISLAND_PIC_KEY, context, R.drawable.ic_reminder))
        island.setBaseInfo(title = slot.name, content = text, pictureKey = ISLAND_PIC_KEY, type = 1)
        island.setSmallIsland(ISLAND_PIC_KEY)

        // buildResourceBundle() 会带上 miui.focus.pics（图片）与 miui.focus.actions；
        // buildJsonParam() 才是岛/焦点通知认的那段 JSON。两者必须一起给，缺一个岛上就是空壳。
        val extras = island.buildResourceBundle()
        extras.putString(FocusParam.EXTRA_KEY, island.buildJsonParam())
        builder.addExtras(extras)
        true
    }.getOrElse { error ->
        Log.w(TAG, "超级岛参数生成失败，本条提醒退回焦点通知：${error.message}")
        false
    }

    /**
     * 点通知 → 打开 App 的**课表页**。
     *
     * 走 `MainActivity` 而不是直接拉 `ManyCourseMain`：入口 Activity 会按登录态决定去哪，
     * 冷启动（进程已经被系统回收）时也只有它能正确把整条启动链走对。
     * `MainTabStore.currentIndex` 默认就是 0（课表），所以不用额外指定 Tab。
     */
    private fun openScheduleIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * **这台设备最终落到哪一档**（设置页的"展示能力"那一行就用它）。
     *
     * 它是 [VendorCapability.tierOf]（纯判定，被单测钉住）加上一次第三方库的可用性询问。
     * 拆成两层是为了让判定矩阵能在 JVM 里测 —— 见 `VendorCapabilityTest`。
     */
    fun currentTier(context: Context): PresentationTier =
        VendorCapability.tierOf(VendorCapability.probe(context), islandUsableNow(context))

    /**
     * 社区库自己认为"现在能用超级岛吗"。
     *
     * 它比 [VendorCapability] 的机型/属性判断更严：还要求**应用已经拿到焦点通知权限**
     * （`content://miui.statusbar.notification.public` 的 `canShowFocus`）。
     *
     * 包 `runCatching`：库的判断会走 ContentProvider + 反射，
     * 在非小米 ROM 上任何一步都可能抛异常 —— 那种情况下答案就是"不能用"，
     * 而不是让整条提醒挂掉。
     */
    internal fun islandUsableNow(context: Context): Boolean =
        runCatching { HyperIslandNotification.isSupported(context) }.getOrDefault(false)

    /**
     * **仅供开发者模式**：造一条"十分钟后上课"的样本，用来在真机上当场看通知长什么样。
     *
     * 为什么需要它：真实提醒一天只响几次、而且要等到课前才响 ——
     * 想确认"焦点通知参数到底生效没有""改完文案是什么效果"，不可能每次都等一节课。
     * 所以给一个随时能按的样本。
     *
     * 深夜（22:00 之后）退回到固定的 8:00：`23:50 + 10 分钟` 会跨到第二天，
     * 那样正文会显示成 `23:50~01:10` 这种自相矛盾的时间段，反而干扰判断。
     */
    fun sampleSlot(now: LocalDateTime = LocalDateTime.now()): ClassSlot {
        val start = if (now.hour >= 22) LocalTime.of(8, 0) else now.toLocalTime().plusMinutes(10)
        return ClassSlot(
            date = now.toLocalDate(),
            entry = CourseEntry.Local(
                Course(
                    // 合成样本**不进仓库**，所以这个 id 只用于占位（不会被任何地方按 id 查找）
                    id = 0L,
                    name = "测试课程",
                    teacher = "测试老师",
                    room = "测试楼 A-101",
                    weekday = now.toLocalDate().dayOfWeek.value,
                    startPeriod = 1,
                    periodCount = 2,
                )
            ),
            start = start,
            end = start.plusMinutes(80),
            startsIn = Duration.between(now.toLocalTime(), start),
            running = false,
        )
    }
}
