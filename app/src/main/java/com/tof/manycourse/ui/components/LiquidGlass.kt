package com.tof.manycourse.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import com.tof.manycourse.ui.theme.GlassEdge
import com.tof.manycourse.ui.theme.GlassLevel
import com.tof.manycourse.ui.theme.GlassTokens
import com.tof.manycourse.ui.theme.LocalGlassTokens
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 液态玻璃背景：对角渐变 + 三个沿轨道环绕、呼吸缩放的装饰光斑。
 * 光斑持续运动，透过 Haze 实时模糊形成"流动的玻璃"质感。
 * 渐变与光斑色感都来自 GlassTokens（明暗主题 / 两种玻璃模式各不相同）。
 * 注意：本背景必须放在 hazeSource 作用域内。
 *
 * ## 动画相位是**进程级共享**的（[BackdropPhase]）
 *
 * 四个页面各画一层一模一样的背景。如果各跑各的动画，切页交叉淡入的那 240ms 里
 * 两层**相位不同**的背景会叠在一起：看着像背景"化开"了一下，
 * 底部玻璃栏的模糊内容也跟着闪 —— 那正是"切页时底栏在抖"的另一半原因。
 *
 * 现在相位只有一份，且**只有当前可见的那一页在驱动它**，其余页面只是读同一个值。
 * 于是任意两页在切换瞬间画的是同一帧，交叉淡入只在"标题 + 内容"上有变化。
 *
 * ## 性能设计（不影响观感，勿删）
 *
 * - [animate] 为 false（页面隐藏 / 应用退后台）时**不启动驱动协程**：被 hide 的
 *   Fragment 视图虽不绘制，但动画默认仍在逐帧驱动重组，多路标签页同时
 *   空转是流畅度的一大隐患；停掉后隐藏页零逐帧开销，恢复时从当前相位继续，位置不跳变。
 * - 运动值经量化后消费（角度 1°、缩放 1% 步进）：光斑是 240dp 级的超柔和
 *   径向渐变、轨道一圈 32 秒，量化步进肉眼完全不可分辨；但背景层的
 *   "失效 → Haze 重渲染快照 + 重模糊"次数从 60 次/秒降到约 15 次/秒。
 */
@Composable
fun LiquidGlassBackground(modifier: Modifier = Modifier, animate: Boolean = true) {
    BackdropPhase.drive(animate)

    // 量化消费值：derivedStateOf 只在跨越步进时通知下游，
    // 图层属性更新与 Haze 重模糊频率随之降低约 3/4
    val angleQ by remember { derivedStateOf { BackdropPhase.angle.value.roundToInt().toFloat() } }
    val pulse1Q by remember { derivedStateOf { BackdropPhase.pulse1.quantized } }
    val pulse2Q by remember { derivedStateOf { BackdropPhase.pulse2.quantized } }
    val pulse3Q by remember { derivedStateOf { BackdropPhase.pulse3.quantized } }

    val tokens = LocalGlassTokens.current
    val density = LocalDensity.current
    // 轨道半径等常用 dp 值在组合时换算一次，避免每帧 graphicsLayer 块内重复换算
    val blob1X = with(density) { 110.dp.toPx() }
    val blob1Y = with(density) { 90.dp.toPx() }
    val blob2X = with(density) { 120.dp.toPx() }
    val blob2Y = with(density) { 100.dp.toPx() }
    val blob3X = with(density) { 80.dp.toPx() }
    val blob3Y = with(density) { 120.dp.toPx() }
    // 高斯模糊模式收敛光斑色感（低饱和），液态玻璃保留满强度色彩
    val blobScale = tokens.blobAlphaScale

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = tokens.backgroundGradient,
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                )
            )
    ) {
        // 蓝色光斑：绕左上区域轨道运动 + 呼吸
        // 所有运动均在绘制阶段（graphicsLayer）完成，避免每帧重新布局，保证流畅
        Box(
            Modifier
                .align(androidx.compose.ui.Alignment.TopStart)
                .offset(40.dp, 120.dp)
                .size(240.dp)
                .graphicsLayer {
                    val rad = Math.toRadians(angleQ.toDouble())
                    translationX = blob1X * cos(rad).toFloat()
                    translationY = blob1Y * sin(rad).toFloat()
                    scaleX = pulse1Q
                    scaleY = pulse1Q
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x4D90CAF9).scaleAlpha(blobScale),
                            Color(0x1A90CAF9).scaleAlpha(blobScale),
                            Color(0x0090CAF9),
                        ),
                    )
                )
        )
        // 紫色光斑：绕右下区域轨道运动（相位相反）+ 呼吸
        Box(
            Modifier
                .align(androidx.compose.ui.Alignment.BottomEnd)
                .offset(60.dp, 80.dp)
                .size(260.dp)
                .graphicsLayer {
                    val rad = Math.toRadians(angleQ.toDouble())
                    translationX = -blob2X * cos(rad).toFloat()
                    translationY = -blob2Y * sin(rad).toFloat()
                    scaleX = pulse2Q
                    scaleY = pulse2Q
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x4DCE93D8).scaleAlpha(blobScale),
                            Color(0x1ACE93D8).scaleAlpha(blobScale),
                            Color(0x00CE93D8),
                        ),
                    )
                )
        )
        // 天蓝光斑：中部偏右，小半径快速环绕 + 明显呼吸
        Box(
            Modifier
                .align(androidx.compose.ui.Alignment.CenterEnd)
                .offset((-20).dp, (-40).dp)
                .size(200.dp)
                .graphicsLayer {
                    val rad = Math.toRadians(angleQ.toDouble()) * 2 + 1.5
                    translationX = blob3X * cos(rad).toFloat()
                    translationY = blob3Y * sin(rad).toFloat()
                    scaleX = pulse3Q
                    scaleY = pulse3Q
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x407DD3FC).scaleAlpha(blobScale),
                            Color(0x157DD3FC).scaleAlpha(blobScale),
                            Color(0x007DD3FC),
                        ),
                    )
                )
        )
    }
}

/**
 * 实时模糊玻璃（Haze）：**只用于页头 / 底栏**（[GlassLevel.Bar]）。
 * 底色、模糊半径、描边、阴影、高光、色感、边缘光、内阴影全部由
 * [LocalGlassTokens] 决定，切换模式时调用方零改动。
 *
 * @param separatorEdge 分隔线画在哪一侧。[GlassEdge.None] 表示**完全不画线**
 *   （页头用这个：它和内容区颜色几乎一致，画线反而把"状态栏+标题"切成一条独立的条，
 *   产生割裂感；内容在自己的滚动容器里，也不会滚到页头下面，本就不需要分隔线）。
 *   底栏传 [GlassEdge.Top]：底栏是独立功能区，需要一条线界定与内容的分界。
 *
 * 贴屏幕边缘的那一侧**不画任何线**：圆角屏 / 挖孔屏 / 曲面屏会裁切屏幕边缘，
 * 在物理边缘画描边会出现线被圆角切断、两侧竖线贴边的观感。
 *
 * 注意：弹窗/表单等**会做进出场缩放淡入**的表面不要用实时模糊——
 * Haze 的 RenderEffect 在动画图层里会重采样，进出场时会出现闪帧，
 * 这类表面请用 [glassPanel] / [glassSurface]（静态、零逐帧成本）。
 */
@Composable
fun Modifier.liquidGlass(
    hazeState: HazeState,
    shape: Shape = RoundedCornerShape(12.dp),
    level: GlassLevel = GlassLevel.Bar,
    separatorEdge: GlassEdge = GlassEdge.None,
): Modifier {
    val tokens = LocalGlassTokens.current
    return this
        // 注意：这里不能加 Modifier.shadow —— 页头/底栏是半透明玻璃，
        // 垫在下层的阴影会透过玻璃把整条压暗（看起来像被内容压住），
        // "浮在内容之上"的柔和投影由 GlassHeader 画在玻璃条下方（见 barShadow）。
        .shadow(
            elevation = 0.dp,
            shape = shape,
            clip = false,
            ambientColor = tokens.shadowColor,
            spotColor = tokens.shadowColor,
        )
        .clip(shape)
        .hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = tokens.barTint,
                tints = emptyList<HazeTint>(),
                blurRadius = tokens.barBlur,
                // 噪点着色器每帧全屏计算、开销大且视觉差异小，关闭以保证流畅
                noiseFactor = 0f,
            ),
        )
        .glassOverlays(
            tokens = tokens,
            shape = shape,
            standalone = usesFullBorder(level),
            separatorEdge = separatorEdge,
        )
}

/**
 * 静态玻璃质感（无实时模糊、零逐帧成本）：列表卡片与常规卡片容器。
 * 滚动场景不做实时模糊，用更高的不透明度 + 阴影保证层次与可读性。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(12.dp),
    highlighted: Boolean = false,
): Modifier = glassStatic(
    shape = shape,
    tint = if (highlighted) LocalGlassTokens.current.cardTintHighlighted else LocalGlassTokens.current.cardTint,
    elevation = LocalGlassTokens.current.cardShadow,
)

/**
 * 静态玻璃面板（无实时模糊）：弹窗 / 表单容器。
 *
 * 不透明度更高（浅色 78%–84% 白 / 深色 80%–85% 深灰）+ 1dp 描边 + 阴影分层，
 * 正文对比度有保证；同时因为这些面板会做进出场缩放/淡入，
 * 用静态表面可以彻底避免实时模糊在动画图层里重采样造成的闪帧。
 */
@Composable
fun Modifier.glassPanel(
    shape: Shape = RoundedCornerShape(24.dp),
): Modifier = glassStatic(
    shape = shape,
    tint = LocalGlassTokens.current.panelTint,
    elevation = LocalGlassTokens.current.panelShadow,
)

/** 静态玻璃表面的公共实现：阴影 + 裁剪 + 底色 + 统一叠加层 */
@Composable
private fun Modifier.glassStatic(
    shape: Shape,
    tint: Color,
    elevation: Dp,
): Modifier {
    val tokens = LocalGlassTokens.current
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = tokens.shadowColor,
            spotColor = tokens.shadowColor,
        )
        .clip(shape)
        .background(tint)
        // 四周都远离屏幕物理边缘（页面留白 / 浮层内缩），可以放心画整圈描边
        .glassOverlays(
            tokens = tokens,
            shape = shape,
            standalone = true,
            separatorEdge = GlassEdge.None,
        )
}

/** 是否画整圈描边：只有四周都远离屏幕边缘的面板才画；页头/底栏一律不画 */
private fun usesFullBorder(level: GlassLevel): Boolean = level == GlassLevel.Panel

/**
 * 色感层 + 顶部高光 + 描边/分隔线 + 内阴影 + 边缘光：两种模式视觉差异的唯一实现点。
 *
 * @param standalone 是否是"独立的玻璃表面"（面板、卡片：四周都远离屏幕边缘）。
 *   只有这类表面才画整圈描边、顶边反光、底部内阴影与色感纱层。
 *   页头/底栏不是：贴屏幕边缘画线会被圆角切断，而内阴影/色感纱会把整条压暗，
 *   让"顶部"看起来沉在内容下面（要求是浮在内容之上）。
 */
private fun Modifier.glassOverlays(
    tokens: GlassTokens,
    shape: Shape,
    standalone: Boolean,
    separatorEdge: GlassEdge,
): Modifier = this
    // saturation：>1 叠加品牌色增强色彩，<1 叠加中性灰收敛（面板/卡片的色感差异）
    .then(
        if (standalone) Modifier.background(tokens.saturationOverlay) else Modifier
    )
    // highlight：顶部柔光高光（液态玻璃更强；页头也有，帮助"上层"的观感）
    .background(Brush.verticalGradient(tokens.highlight))
    .then(
        if (standalone) Modifier.border(tokens.borderWidth, tokens.border, shape) else Modifier
    )
    .drawWithContent {
        drawContent()
        val lineWidth = tokens.borderWidth.toPx()
        // innerShadow：底部内阴影，模拟玻璃厚度（仅液态玻璃、仅独立玻璃表面）
        if (standalone && tokens.innerShadow.alpha > 0.001f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, tokens.innerShadow),
                    startY = size.height * 0.55f,
                    endY = size.height,
                ),
            )
        }
        // edgeLight：顶边反光（仅液态玻璃、仅独立玻璃表面）
        if (standalone && tokens.edgeLight.alpha > 0.001f) {
            drawLine(
                color = tokens.edgeLight,
                start = Offset(0f, lineWidth / 2f),
                end = Offset(size.width, lineWidth / 2f),
                strokeWidth = lineWidth,
            )
        }
        // 页头/底栏：只在与内容相接的一侧画分隔线，物理边缘一侧留空
        when (separatorEdge) {
            GlassEdge.Top -> drawLine(
                color = tokens.barSeparator,
                start = Offset(0f, lineWidth / 2f),
                end = Offset(size.width, lineWidth / 2f),
                strokeWidth = lineWidth,
            )

            GlassEdge.Bottom -> drawLine(
                color = tokens.barSeparator,
                start = Offset(0f, size.height - lineWidth / 2f),
                end = Offset(size.width, size.height - lineWidth / 2f),
                strokeWidth = lineWidth,
            )

            GlassEdge.None -> Unit
        }
    }

private fun Color.scaleAlpha(factor: Float): Color =
    copy(alpha = (alpha * factor).coerceIn(0f, 1f))

/**
 * 液态玻璃背景的**共享相位**（进程级唯一）。
 *
 * 为什么必须是共享的、且只有一个驱动者：四个页面各画一层同样的背景，
 * 切页时两页会交叉淡入 240ms。若两层背景的相位不同，这 240ms 里会看到
 * 两层光斑互相叠加"化开"，底部玻璃栏（实时模糊采样这层背景）也跟着闪。
 * 共享之后，切换瞬间两页画的是同一帧，背景与底栏完全静止。
 *
 * 驱动者由调用方通过 `animate` 指定（当前只有"可见且在前台"的那一页传 true）：
 * `LaunchedEffect(animate)` 取消旧协程、启动新协程，因此同一时刻至多一个驱动；
 * 切换瞬间若短暂重叠，`Animatable` 自身的 MutatorMutex 会让后发起者接管，
 * 不会出现两个协程抢同一个值。
 *
 * 值用 `Animatable` 而不是普通变量：它自带 `withFrameNanos` 驱动，
 * 天然跟屏幕刷新对齐，也便于"从当前值无缝续走"。
 */
private object BackdropPhase {

    /** 三个光斑共用的环绕角度（0→360° 循环，一圈 32 秒）*/
    val angle = Animatable(0f)

    /** 光斑呼吸缩放（各不同周期的往返线性动画）*/
    val pulse1 = Animatable(PULSE1_LOW)
    val pulse2 = Animatable(PULSE2_LOW)
    val pulse3 = Animatable(PULSE3_LOW)

    // 往返方向也放在外面：驱动协程重启时方向不能变，
    // 否则"从当前值继续"会变成"半路掉头"，肉眼能看出来
    private var forward1 = true
    private var forward2 = true
    private var forward3 = true

    /**
     * 由**当前可见的页面**调用，驱动共享相位。
     *
     * @param enabled false 时不启动驱动协程（隐藏页 / 退后台），
     *   相位原地冻结，恢复时从冻结值按原速度续走。
     */
    @Composable
    fun drive(enabled: Boolean) {
        LaunchedEffect(enabled) {
            if (!enabled) return@LaunchedEffect
            launch { loopAngle() }
            launch { loopPulse(pulse1, PULSE1_LOW, PULSE1_HIGH, 9000) { forward1.also { forward1 = !forward1 } } }
            launch { loopPulse(pulse2, PULSE2_LOW, PULSE2_HIGH, 13000) { forward2.also { forward2 = !forward2 } } }
            launch { loopPulse(pulse3, PULSE3_LOW, PULSE3_HIGH, 11000) { forward3.also { forward3 = !forward3 } } }
        }
    }

    private suspend fun loopAngle() {
        while (true) {
            val remaining = (360f - angle.value.rem(360f)) / 360f
            angle.animateTo(
                targetValue = angle.value + remaining * 360f,
                animationSpec = tween(
                    durationMillis = (32000 * remaining).roundToInt().coerceAtLeast(1),
                    easing = LinearEasing,
                ),
            )
        }
    }

    /** 一次往返：`low ⇄ high`，整程耗时 [periodMillis]（按剩余路程折算）*/
    private suspend fun loopPulse(
        value: Animatable<Float, *>,
        low: Float,
        high: Float,
        periodMillis: Int,
        nextForward: () -> Boolean,
    ) {
        while (true) {
            val forward = nextForward()
            val target = if (forward) high else low
            val fraction = kotlin.math.abs(target - value.value) / (high - low)
            value.animateTo(
                targetValue = target,
                animationSpec = tween(
                    durationMillis = (periodMillis * fraction).roundToInt().coerceAtLeast(1),
                    easing = LinearEasing,
                ),
            )
        }
    }

    private const val PULSE1_LOW = 0.82f
    private const val PULSE1_HIGH = 1.18f
    private const val PULSE2_LOW = 0.85f
    private const val PULSE2_HIGH = 1.22f
    private const val PULSE3_LOW = 0.75f
    private const val PULSE3_HIGH = 1.25f
}

/** 量化到 1% 步进（见 [LiquidGlassBackground] 的性能说明）*/
private val Animatable<Float, *>.quantized: Float get() = (value * 100).roundToInt() / 100f

/** 课程卡片左侧强调条宽度（设计规范：默认 4dp，高亮 8dp） */
val CardAccentWidth = 4.dp
val CardAccentWidthHighlighted = 8.dp
