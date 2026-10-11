package com.tof.manycourse

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.ui.theme.GlassMode
import com.tof.manycourse.ui.theme.GlassPalette
import com.tof.manycourse.widget.WidgetRefresh
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * 桌面小组件的**玻璃底板真正落地**（`res/drawable/widget_next_class_*` + [WidgetRefresh]）。
 *
 * ## 为什么必须是插桩测试
 *
 * JVM 侧读不到颜色资源（本项目不引 Robolectric，`context.getColor` 会抛 "not mocked"），
 * 所以"底板到底换没换"只能在真机 / 模拟器上验：
 *
 * ```
 * adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb -s emulator-5554 shell am instrument -w -e class com.tof.manycourse.WidgetGlassDeviceTest \
 *     com.tof.manycourse.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * ★ **不要跑 `connectedAndroidTest`**：小米真机会拒绝安装测试 APK
 * （`INSTALL_FAILED_USER_RESTRICTED`），上面这条 `adb install` + `am instrument` 手动路径才是可用的。
 *
 * ## 这里验的三件事（都不靠"调用没抛异常"）
 *
 * 1. `RemoteViews` 真的能 apply 出来（id 写错会抛，但"底板资源 id 对不对"只有读出来才知道）；
 * 2. 切 `UiSettings.glassMode` 之后**根部那层背景 drawable 真的换了**：
 *    比 `layer-list` 最底层那个纯色 shape 的颜色 —— 它必须逐通道等于
 *    令牌 `barTint`（`WidgetRefresh.surfaceTint` 独立算一遍），并且液态比高斯更透。
 *    这一条同时拦住"drawable 里的固定色值写错了"和"忘了按设置切换"两种回归；
 * 3. 空态的两行说明**没有被染成强调色**（`setTextColor` 那步在空态会复位）。
 *
 * ★ 刻意不验"地点 / 状态被染成该配色的 accent"：那需要真实课表数据，
 *   而这台设备上没有会话（`UpcomingClass.hasScheduleData()` 为 false）。
 */
@RunWith(AndroidJUnit4::class)
class WidgetGlassDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val previousMode: GlassMode = UiSettings.glassMode.value
    private val previousPalette: GlassPalette = UiSettings.glassPalette.value

    @After
    fun restore() {
        UiSettings.setGlassMode(previousMode)
        UiSettings.setGlassPalette(previousPalette)
    }

    @Test
    fun 切成液态玻璃后小组件的底板drawable真的换了() {
        val gaussian = renderRootWith(GlassMode.Gaussian)
        val liquid = renderRootWith(GlassMode.Liquid)

        val gaussianColor = bottomFillColor(gaussian.background)
        val liquidColor = bottomFillColor(liquid.background)

        Log.i(
            TAG,
            "小组件底板：高斯=${hex(gaussianColor)}(alpha=${alphaOf(gaussianColor)}) " +
                "液态=${hex(liquidColor)}(alpha=${alphaOf(liquidColor)}) night=${isNight()}",
        )

        assertNotEquals(
            "切换玻璃风格必须真的换一块底板（资源或分层不同）",
            gaussian.background,
            liquid.background,
        )
        // 令牌口径：液态玻璃"更通透、质感靠高光撑"（见 GlassTokens.kt）。桌面那份镜像必须同一关系
        assertTrue(
            "液态的底应该比高斯更透（alpha 更小）：${alphaOf(liquidColor)} vs ${alphaOf(gaussianColor)}",
            alphaOf(liquidColor) < alphaOf(gaussianColor),
        )
        // 而且底必须是令牌 barTint 的取值（独立算一遍：drawable 里写的固定色值不能漂）
        assertSameArgb(
            "高斯那份底板的底色必须等于令牌 barTint",
            WidgetRefresh.surfaceTint(GlassMode.Gaussian, isNight()),
            gaussianColor,
        )
        assertSameArgb(
            "液态那份底板的底色必须等于令牌 barTint",
            WidgetRefresh.surfaceTint(GlassMode.Liquid, isNight()),
            liquidColor,
        )
    }

    @Test
    fun 底板的多层装饰都在_顶部柔光与亮边() {
        listOf(GlassMode.Gaussian, GlassMode.Liquid).forEach { mode ->
            val layer = renderRootWith(mode).background as LayerDrawable
            Log.i(TAG, "${mode.label} 底板层数=${layer.numberOfLayers}")
            // 至少三层：底 / 内侧着色 / 柔光（液态多一层内阴影）/ 亮边
            assertTrue(
                "${mode.label}：底板应该是一个多层 layer-list，实际 ${layer.numberOfLayers} 层",
                layer.numberOfLayers >= 3,
            )
            // 柔光那一层必须是渐变（静态绘制，零逐帧成本 —— 小组件没有逐帧概念）
            val hasGradient = (0 until layer.numberOfLayers).any { index ->
                when (val d = layer.getDrawable(index)) {
                    is GradientDrawable -> true
                    is LayerDrawable -> (0 until d.numberOfLayers).any { d.getDrawable(it) is GradientDrawable }
                    else -> false
                }
            }
            assertTrue("${mode.label}：底板里必须有顶部柔光那种渐变层", hasGradient)
        }
    }

    @Test
    fun 空态的两行说明不会被染成强调色() {
        // 空态是"说明"不是"内容"：强调色只给有课时的地点 / 状态行
        val root = renderViews().apply(context, FrameLayout(context))
        val room = root.findViewById<TextView>(R.id.widget_room)
        val status = root.findViewById<TextView>(R.id.widget_status)
        assertNotNull(room)
        assertNotNull(status)
        Log.i(TAG, "空态字色：room=${hex(room.currentTextColor)} status=${hex(status.currentTextColor)}")
        assertEquals("空态地点行应该是布局里的中性色", context.getColor(R.color.widget_room), room.currentTextColor)
        assertEquals("空态状态行应该是布局里的中性色", context.getColor(R.color.widget_status), status.currentTextColor)
    }

    // ══════════════════ 辅助 ══════════════════

    private fun renderViews(): RemoteViews = WidgetRefresh.render(context, LocalDateTime.now())

    /** 把小组件 apply 成一个真实 View（背景 drawable 已经落到 `View.background` 上）*/
    private fun renderRootWith(mode: GlassMode): View {
        UiSettings.setGlassMode(mode)
        val host = FrameLayout(context)
        val root = renderViews().apply(context, host)
        assertNotNull("${mode.label}：根部那层必须真的挂上了背景 drawable", root.background)
        assertTrue("小组件的根应该是个 ViewGroup", root is ViewGroup)
        return root
    }

    /** `layer-list` 最底层那个纯色 shape 的颜色（= 半透明底）*/
    private fun bottomFillColor(background: android.graphics.drawable.Drawable?): Int {
        val layer = background as? LayerDrawable
            ?: error("底板应该是一个 layer-list（实际 ${background?.javaClass?.name}）")
        var d = layer.getDrawable(0)
        var guard = 0
        while (d is LayerDrawable && guard++ < 8) d = d.getDrawable(0)
        val gd = d as? GradientDrawable ?: error("最底层应该是纯色 shape（实际 ${d?.javaClass?.name}）")
        val fill = gd.color ?: error("最底层 shape 没有 solid 颜色")
        return fill.defaultColor
    }

    private fun assertSameArgb(where: String, expected: Int, actual: Int) {
        listOf("A", "R", "G", "B").forEachIndexed { index, channel ->
            val shift = 24 - index * 8
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue("$where：$channel 通道 ${e.toString(16)} vs ${a.toString(16)}", kotlin.math.abs(e - a) <= 1)
        }
    }

    /** 与 `WidgetRefresh` 内部同一判定（小组件是独立进程，读的是自己 Resources 的配置）*/
    private fun isNight(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF

    private fun hex(argb: Int): String = "#%08X".format(argb)

    private companion object {
        const val TAG = "WidgetGlassDevice"
    }
}
