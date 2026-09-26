package com.tof.manycourse

import com.tof.manycourse.ui.MapViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「图上归一化坐标 ↔ 屏幕像素」换算的回归测试（JVM，无需设备）。
 *
 * 这一层是**标记会不会漂移**的全部原因，而它最阴的地方是"不缩放时看着是对的"：
 * `graphicsLayer` 默认绕**容器中心**缩放（不是图片左上角），搞错了只有放大之后才暴露，
 * 表现是"放大后所有标记整体偏出去一块"—— 真机上一眼看不出是这里错了。
 */
class MapGeometryTest {

    /** 容器 1000×500、原图 2000×1000（同比例）→ Fit 之后刚好铺满 */
    private val viewport = MapViewport(
        containerWidth = 1000f,
        containerHeight = 500f,
        imageWidth = 2000f,
        imageHeight = 1000f,
    )

    @Test
    fun fitKeepsAspectRatio() {
        assertTrue(viewport.isReady)
        assertEquals(1000f, viewport.fittedWidth, 1e-3f)
        assertEquals(500f, viewport.fittedHeight, 1e-3f)
    }

    @Test
    fun imageCenterMapsToContainerCenter() {
        val (x, y) = requireNotNull(viewport.toScreen(0.5f, 0.5f))

        assertEquals(500f, x, 1e-3f)
        assertEquals(250f, y, 1e-3f)
    }

    @Test
    fun imageCornersMapToFittedRectangle() {
        val topLeft = requireNotNull(viewport.toScreen(0f, 0f))
        val bottomRight = requireNotNull(viewport.toScreen(1f, 1f))

        assertEquals(0f, topLeft.first, 1e-3f)
        assertEquals(0f, topLeft.second, 1e-3f)
        assertEquals(1000f, bottomRight.first, 1e-3f)
        assertEquals(500f, bottomRight.second, 1e-3f)
    }

    @Test
    fun zoomKeepsContainerCenterFixed() {
        // ★ 这条就是"缩放绕容器中心"的钉子：图中心在任何缩放下都必须停在容器中心（加平移）
        val zoomed = viewport.copy(scale = 2f, offsetX = 30f, offsetY = -10f)
        val (x, y) = requireNotNull(zoomed.toScreen(0.5f, 0.5f))

        assertEquals(530f, x, 1e-3f)
        assertEquals(240f, y, 1e-3f)
    }

    @Test
    fun roundTripIsIdentityAtAnyZoom() {
        // 标记画在图上是"正算"，用户点图采点是"反算" —— 两者必须是同一套换算的逆运算，
        // 否则采到的点和看到的点不是同一个地方
        val zoomed = viewport.copy(scale = 3.7f, offsetX = -120f, offsetY = 64f)
        listOf(0f to 0f, 0.5f to 0.5f, 1f to 1f, 0.23f to 0.81f).forEach { (imageX, imageY) ->
            val (screenX, screenY) = requireNotNull(zoomed.toScreen(imageX, imageY))
            val (backX, backY) = requireNotNull(zoomed.toImage(screenX, screenY))

            assertEquals(imageX, backX, 1e-4f)
            assertEquals(imageY, backY, 1e-4f)
        }
    }

    @Test
    fun letterboxedImageIsCentered() {
        // 原图 4:1、容器 2:1 → 左右铺满、上下留白：这时"图上位置"照样按**图片自己**的框算
        val letterboxed = MapViewport(
            containerWidth = 1000f,
            containerHeight = 500f,
            imageWidth = 2000f,
            imageHeight = 500f,
        )

        assertEquals(1000f, letterboxed.fittedWidth, 1e-3f)
        assertEquals(250f, letterboxed.fittedHeight, 1e-3f)
        // 图中心仍在容器中心
        val (x, y) = requireNotNull(letterboxed.toScreen(0.5f, 0.5f))
        assertEquals(500f, x, 1e-3f)
        assertEquals(250f, y, 1e-3f)
        // 图片的上边界落在容器中间偏上（上下各留 125px）
        assertEquals(125f, requireNotNull(letterboxed.toScreen(0.5f, 0f)).second, 1e-3f)
    }

    @Test
    fun notReadyReturnsNullInsteadOfGarbage() {
        // 首帧就是这么个状态（尺寸还没量出来）：此时宁可什么都不画，
        // 也不能除零算出一堆 NaN 画到屏幕上
        val empty = MapViewport(0f, 0f, 0f, 0f)
        assertFalse(empty.isReady)
        assertNull(empty.toScreen(0.5f, 0.5f))
        assertNull(empty.toImage(100f, 100f))
    }

    @Test
    fun zoomedOutBeyondFitIsUndefinedForZeroScale() {
        // scale = 0 理论上不会出现（有 MIN_SCALE 兜着），但反算要挡住除零
        val broken = viewport.copy(scale = 0f)
        assertNull(broken.toImage(500f, 250f))
    }
}
