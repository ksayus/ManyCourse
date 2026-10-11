package com.tof.manycourse.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * 2x2「下一节课」桌面小组件。
 *
 * 尺寸与更新周期声明在 `res/xml/next_class_widget_info.xml`，
 * 排版在 `res/layout/widget_next_class.xml`，**渲染逻辑在 [WidgetRefresh]**。
 *
 * ## 这个类只做一件事：把系统的回调转给 [WidgetRefresh]
 *
 * `AppWidgetProvider` 的 `onUpdate` 只是"该刷新了"的**其中一个**来源
 * （系统每 30 分钟一次、以及用户刚把小组件拖到桌面时）。
 * App 里课表同步完、退出登录、课前提醒响 —— 这三个时机系统都不知道，
 * 得由我们自己去调 [WidgetRefresh.updateAll]。
 *
 * 所以这里不写任何渲染代码：渲染只有一份实现，见 [WidgetRefresh] 的类注释。
 *
 * ## `onEnabled` / `onDisabled` / `onDeleted` 都刻意不覆写
 *
 * 这个小组件**没有任何常驻资源**要管（不注册监听、不起服务、不持有 wake lock），
 * 每次刷新都是"现场读一次数据、画一次"。所以"最后一个实例被删掉了"不需要额外清理 ——
 * 覆写一堆空方法只会让人以为这里有什么需要收尾。
 * （作为对照：课前的**闹钟**是有常驻资源的，它由 `reminder/` 那一套独立管理，
 *  与桌面小组件在不在没有任何关系。）
 */
class NextClassWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 注意这里的 appWidgetIds 可能是**多个**（用户放了不止一个这个小组件），
        // 所以交给 updateAll 统一处理，而不是只画第一个
        WidgetRefresh.updateAll(context, appWidgetIds)
    }
}
