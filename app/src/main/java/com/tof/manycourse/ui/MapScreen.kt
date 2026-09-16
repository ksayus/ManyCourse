package com.tof.manycourse.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.SchoolMap
import com.tof.manycourse.gr_api.SchoolRegistry
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.theme.LocalGlassTokens
import kotlin.math.max
import kotlin.math.min

/** 缩放下限：1 = 整张图刚好完整显示（ContentScale.Fit），不允许再缩小 */
private const val MIN_SCALE = 1f

/** 缩放上限：地图原图 1200px 级，放到 6 倍足够看清楼名，再多就是马赛克了 */
private const val MAX_SCALE = 6f

/**
 * 校园地图页。
 *
 * 显示**当前登录学校**的地图（`data/SchoolMap.kt` 里的 id → 图片映射）。
 * 学校没登记地图时给出明确提示，而不是一片空白。
 *
 * 交互：双指缩放 / 拖动 / 双击复位，右下角有复位按钮。
 * 缩放用 `graphicsLayer`（绘制阶段）而不是改尺寸，避免每帧重新布局。
 */
@Composable
fun MapScreen(
    schoolId: String?,
    modifier: Modifier = Modifier,
) {
    val school = SchoolRegistry.find(schoolId)
    val mapResId = SchoolMap.drawableOf(schoolId)

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ═══ 标题行：学校名 + 复位 ═══
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = school?.name ?: "还没有选择学校",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (mapResId != null) "校园地图 · 双指缩放、拖动查看" else "校园地图",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ═══ 地图本体 ═══
        GlassCard(
            cornerRadius = 12,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (mapResId == null) {
                NoMapPlaceholder(school?.name)
            } else {
                ZoomableMap(
                    resId = mapResId,
                    description = "${school?.name.orEmpty()}校园地图",
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = if (school == null) {
                "请先返回登录页选择学校"
            } else {
                "地图仅为示意，实际方位以学校现场为准"
            },
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 当前学校没有地图资源时的占位：说清楚"为什么没有"，以及怎么加 */
@Composable
private fun NoMapPlaceholder(schoolName: String?) {
    val tokens = LocalGlassTokens.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = AppIcons.MapPin,
            contentDescription = null,
            tint = tokens.accent,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (schoolName == null) "还没有选择学校" else "「$schoolName」还没有地图",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "把地图图片放进 res/drawable-nodpi/，\n" +
                "再在 data/SchoolMap.kt 里登记这所学校的 id 即可",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 可缩放拖动的图片：`graphicsLayer` 做缩放位移，`transformable` 收手势。
 *
 * 位移做了**边界收拢**：缩放到 N 倍后，最多只能拖到"图片边缘贴住容器边缘"，
 * 不允许把图拖出视野 —— 否则很容易出现"地图被拖没了，看起来像加载失败"。
 * 边界按 `ContentScale.Fit` 算出的实际显示尺寸推。
 */
@Composable
private fun ZoomableMap(resId: Int, description: String) {
    val painter = painterResource(resId)
    val imageWidth = painter.intrinsicSize.width
    val imageHeight = painter.intrinsicSize.height

    var container by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 容器内按 Fit 缩放后的实际显示尺寸（px）
    val fittedWidth = if (imageWidth > 0f && imageHeight > 0f && container.width > 0) {
        val ratio = min(container.width / imageWidth, container.height / imageHeight)
        imageWidth * ratio
    } else {
        0f
    }
    val fittedHeight = if (imageWidth > 0f && imageHeight > 0f && container.width > 0) {
        val ratio = min(container.width / imageWidth, container.height / imageHeight)
        imageHeight * ratio
    } else {
        0f
    }

    fun clampOffset(candidate: Offset, currentScale: Float): Offset {
        if (currentScale <= MIN_SCALE) return Offset.Zero
        val maxX = max(0f, (fittedWidth * currentScale - container.width) / 2f)
        val maxY = max(0f, (fittedHeight * currentScale - container.height) / 2f)
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY),
        )
    }

    // 注意：lambda 里读的是 state 的当前值（不是闭包快照），所以不存在
    // "remember 把第一次的 lambda 存下来、之后一直用旧 scale" 的经典问题
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        scale = next
        offset = clampOffset(offset + panChange, next)
    }

    val reset = {
        scale = MIN_SCALE
        offset = Offset.Zero
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .onSizeChanged { container = it }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
            .transformable(transformState),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )

        // 放大后给一个复位入口：双击虽然也行，但没人知道能双击
        if (scale > MIN_SCALE + 0.01f) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(9999.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { reset() }) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "复位",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
