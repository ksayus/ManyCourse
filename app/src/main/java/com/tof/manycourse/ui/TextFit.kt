package com.tof.manycourse.ui

import android.util.TypedValue
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.floor

/**
 * 「长文本压进一行」的字号自适应。
 *
 * 场景：登录页的学校下拉要单行显示校名，但中文校名很长
 * （「南京航空航天大学金城学院」12 个字），在系统字号被调大后
 * （实测机 font_scale = 1.17）放不下一行，于是被截断
 * ——多学校场景下用户根本没法确认自己选的是哪所。
 *
 * ## 为什么不用系统的 `autoSizeTextType`
 * 框架的自动缩放对 `EditText` 一类可编辑控件有额外限制，行为不确定；
 * 这里只需要「放得下 / 放不下」一个判断，自己算完全可控，而且能拆出纯函数做单测。
 *
 * ## 为什么不用「控件宽度 - 内边距」预估可用宽度（踩过的坑）
 * 真机日志证明这条路走不通：
 * ```
 * 第一趟 layoutW=772   ← Material 还没把起始图标的内边距加到 EditText 上
 * 第二趟 layoutW=574   ← 内边距补上后，真正可用的宽度只有 574px
 * ```
 * 预估值和真实值差了 198px（72dp），于是算出「放得下」而实际仍被截断。
 * 现在直接读 [android.text.Layout.getWidth] —— Layout **真正用来排版**的宽度。
 *
 * ## 现状：正常情况下根本轮不到它出场
 * 布局那边把卡片往两侧撑宽了（margin 24→16dp、内边距 16→12dp），
 * 学校框的文本区从 574px 涨到约 **640px**，
 * 「南京航空航天大学金城学院」16sp 需要 618px —— 于是放得下，**字号一个字都不用改**
 * （真机实测：学校框高度与用户名/密码框完全一致，都是 147px）。
 * 本文件负责的是"再长一点 / 屏幕再窄一点 / 系统字号再大一点"的情况。
 *
 * ## 顺带记下两条走不通的路（都真机验证过）
 *  - **`app:startIconMinSize`**：Material 把起始图标**居中**在它预留的区域里，
 *    把区域从默认 48dp 缩到 24dp 虽然能省出 66px，但图标会左移 33px 紧贴输入框边框，
 *    和相邻字段错位（实测：学校框图标 110–176，用户名/密码框 143–209）。
 *  - **给 EditText 写 `android:paddingStart/End`**：会被 Material 的 `TextInputLayout`
 *    按自己的规则覆盖，XML 里写了等于没写（实测文本区仍是 574px）。
 *
 * ## 为什么不靠 `maxLines="1"` 兜底
 * 实测 `MaterialAutoCompleteTextView`（`inputType="none"`）即使写了 `maxLines="1"`，
 * Layout 仍可能出现两行。所以这里不依赖它：**由"文本实测宽度 ≤ 可用宽度"自己保证单行**，
 * XML 上的 `maxLines` / `ellipsize` 只当额外保险。
 *
 * ## 幂等
 * [fitCurrentTextToOneLine] 只依赖「当前 Layout 的可用宽度 + 当前文本实测宽度 + 基准字号」，
 * 同样输入必得同样结果，所以可以在每次布局回调里无脑调用：
 * 放得下就回到基准字号（一个字都不动），放不下就一次算准，不会来回抖动、也不会死循环。
 */
object TextFit {

    /**
     * 目标尺寸留一点余量。
     * 按 `可用宽度 / 文本宽度` 算出来是「刚好压线」，字体度量的浮点误差
     * 仍可能让 Layout 判定为超出一丁点，所以缩 0.5%。
     */
    const val DEFAULT_SAFETY = 0.995f

    /** 默认步进（sp）：0.25sp 足够细，观感上没有字号台阶 */
    const val DEFAULT_STEP_SP = 0.25f

    /**
     * 把「当前字号下的文本宽度」折算成「刚好放进可用宽度」的字号。
     *
     * 纯函数 —— 不碰任何 View，所以能在 JVM 单测里直接跑（见 `TextFitTest`）。
     *
     * @param currentSizePx    当前字号（px）
     * @param textWidthPx      当前字号下文本的实测宽度（px）
     * @param availableWidthPx 真正可用的宽度（px），应取自 `Layout.getWidth()`
     * @param minSizePx        下限（px）：再小影响可读性，此时宁可让省略号兜底
     * @param stepPx           字号步进（px）
     * @return 目标字号（px）。**不会大于** [currentSizePx]（本函数只负责缩小），
     *         且一定落在 `[minSizePx, currentSizePx]` 内
     */
    fun fitSizePx(
        currentSizePx: Float,
        textWidthPx: Float,
        availableWidthPx: Float,
        minSizePx: Float,
        stepPx: Float,
        safety: Float = DEFAULT_SAFETY,
    ): Float {
        // 放得下、或者参数不可用：保持不动
        if (availableWidthPx <= 0f || textWidthPx <= availableWidthPx) return currentSizePx
        if (currentSizePx <= minSizePx) return currentSizePx

        // 文本宽度与字号成正比 → 反推出刚好放下所需的字号
        val scaled = currentSizePx * availableWidthPx / textWidthPx * safety
        if (scaled >= currentSizePx) return currentSizePx

        val stepped = floor(scaled / stepPx) * stepPx
        return stepped.coerceIn(minSizePx, currentSizePx)
    }

    /** sp → px（跟随系统字号缩放） */
    fun spToPx(sp: Float, metrics: android.util.DisplayMetrics): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
}

/**
 * 以**真实 Layout** 为准，把 [TextView] 的当前文本压进一行。
 *
 * 放得下就回到 [baseSizePx]（所以短校名的字号是原样、不做任何改动）；
 * 放不下（或算完仍有省略号）就缩小字号，直到实测宽度塞得进 Layout 的可用宽度。
 *
 * 注意：`setTextSize` 会把 `TextView` 的 `layout` 置空，所以本函数**必须在读完
 * 全部所需信息之后再改字号**，调用方也不要在这之后立刻读 `layout`。
 *
 * @param baseSizePx 基准字号（px）：放得下时用它。取布局里声明的字号原值，
 *                   不要用 `spToPx(16)` 重算 —— 实测两者会有 1% 的偏差（51.0 vs 50.545），
 *                   会导致"明明放得下却把字号改了"。
 * @param minSizePx  下限（px）
 * @param stepSizePx 步进（px）
 * @return 实际应用的字号（px）；无需改动时返回 null。调用方可据此打日志或断言。
 */
fun TextView.fitCurrentTextToOneLine(
    baseSizePx: Float,
    minSizePx: Float,
    stepSizePx: Float,
): Float? {
    val textLayout = layout ?: return null          // 还没完成布局
    if (textLayout.lineCount == 0) return null      // 空文本
    val content = text ?: return null
    if (content.isEmpty()) return null

    // ★ Layout 真正用来排版的宽度。用它就不必猜「内边距什么时候加上来的」
    val available = textLayout.width.toFloat()
    if (available <= 0f) return null

    val currentPx = textSize
    val widthAtCurrent = paint.measureText(content, 0, content.length)
    if (widthAtCurrent <= 0f) return null

    // 基准字号下就放得下 → 回到基准（这里同时负责「上次缩过的长名字换回短名字要长回来」）
    val widthAtBase = widthAtCurrent * baseSizePx / currentPx
    var target = if (widthAtBase <= available) {
        baseSizePx
    } else {
        TextFit.fitSizePx(currentPx, widthAtCurrent, available, minSizePx, stepSizePx)
    }

    // 兜底自校正：按算出来的字号仍有省略号（字体度量与 Layout 存在微小差异）就再让一档。
    // 每轮至少缩一档，且被 minSizePx 兜住，所以一定会收敛。
    //
    // 前提是这份 Layout 确实是「当前文本 + 当前字号」排出来的：宽高都没变时
    // TextView 会就地换 Layout（不触发布局回调），也可能反过来 —— setText 之后
    // 布局还没跑完、手里这份是旧文本的。旧 Layout 的省略号状态不能拿来判断，
    // 否则会白缩一档（下一轮虽然会长回来，但会闪一下）。
    val layoutIsUpToDate = textLayout.text.contentEquals(content) &&
        abs(textLayout.paint.textSize - currentPx) < 0.01f
    if (layoutIsUpToDate && target >= currentPx - 0.01f &&
        currentPx > minSizePx && textLayout.getEllipsisCount(0) > 0
    ) {
        target = (currentPx - stepSizePx).coerceAtLeast(minSizePx)
    }

    if (abs(target - currentPx) < 0.01f) return null
    setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
    return target
}
