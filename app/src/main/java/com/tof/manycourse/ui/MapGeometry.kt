package com.tof.manycourse.ui

import kotlin.math.min

/**
 * 地图**当前这一帧的视图状态**：把"图上归一化坐标"和"屏幕上的像素位置"互相换算。
 *
 * ## 为什么要单独抽出来（而不是在 Composable 里就地算）
 *
 * `ZoomableMap` 用的是「`ContentScale.Fit` 画图 + `graphicsLayer` 缩放位移」：
 * 图片先按容器等比缩放到刚好放得下（fitted），再整体乘 [scale]、平移 [offsetX] / [offsetY]。
 * 标记（今日课程、我在这儿）要落在图上，就必须**复现**这套换算 ——
 * 而"复现"最容易出的错是**缩放中心搞错**：`graphicsLayer` 默认绕**容器中心**缩放，
 * 不是绕图片左上角。差这一处，放大后所有标记会整体漂移，而且不放大时看着是对的（最难查）。
 *
 * 抽成纯数据 + 纯函数之后，这条换算能被单测钉住（往返一致、中心不动、角点位置）。
 *
 * ★ **不变量**：`toImage(toScreen(p)) == p`（两者互为逆运算）。
 *
 * @param containerWidth / [containerHeight] 地图容器（Box）的尺寸，px
 * @param imageWidth / [imageHeight] 图片的**内在**尺寸，px（`painter.intrinsicSize`）
 * @param scale 用户缩放倍数（1 = 整张图刚好放下）
 * @param offsetX / [offsetY] 用户拖动产生的平移，px
 */
internal data class MapViewport(
    val containerWidth: Float,
    val containerHeight: Float,
    val imageWidth: Float,
    val imageHeight: Float,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** 图片按 `Fit` 缩放后的显示宽度（px）—— 未再乘 [scale] */
    val fittedWidth: Float
        get() = if (isReady) imageWidth * fitRatio else 0f

    /** 图片按 `Fit` 缩放后的显示高度（px）*/
    val fittedHeight: Float
        get() = if (isReady) imageHeight * fitRatio else 0f

    private val fitRatio: Float
        get() = min(containerWidth / imageWidth, containerHeight / imageHeight)

    /** 尺寸都量出来了才谈得上换算；否则调用方应当什么都不画（首帧就是这个状态）*/
    val isReady: Boolean
        get() = containerWidth > 0f && containerHeight > 0f && imageWidth > 0f && imageHeight > 0f

    /** 图上归一化坐标（0~1，左上角原点） → 容器内的屏幕坐标（px）*/
    fun toScreen(imageX: Float, imageY: Float): Pair<Float, Float>? {
        if (!isReady) return null
        // 先算"相对容器中心"的偏移：这是 graphicsLayer 的缩放原点
        val fromCenterX = (imageX * fittedWidth - fittedWidth / 2f) * scale
        val fromCenterY = (imageY * fittedHeight - fittedHeight / 2f) * scale
        return (containerWidth / 2f + fromCenterX + offsetX) to
            (containerHeight / 2f + fromCenterY + offsetY)
    }

    /** [toScreen] 的逆运算：屏幕坐标（px） → 图上归一化坐标（0~1）*/
    fun toImage(screenX: Float, screenY: Float): Pair<Float, Float>? {
        if (!isReady || scale == 0f) return null
        val imageX = ((screenX - containerWidth / 2f - offsetX) / scale + fittedWidth / 2f) / fittedWidth
        val imageY = ((screenY - containerHeight / 2f - offsetY) / scale + fittedHeight / 2f) / fittedHeight
        if (imageX.isNaN() || imageY.isNaN()) return null
        return imageX to imageY
    }
}
