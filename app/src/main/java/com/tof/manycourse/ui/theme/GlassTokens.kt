package com.tof.manycourse.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局玻璃风格：设置页可切换，选择持久化到 SharedPreferences（见 UiSettings）。
 * 默认「高斯模糊」——更克制、可读性优先。
 */
enum class GlassMode(val key: String, val label: String, val summary: String) {
    /** 高斯模糊：简洁磨砂、低饱和、常规 blur、半透明背景 */
    Gaussian(
        key = "gaussian",
        label = "高斯模糊",
        summary = "简洁磨砂 · 低饱和 · 背景更清晰",
    ),

    /** 液态玻璃：更强高光、边缘光、内阴影、渐变与轻微流动感 */
    Liquid(
        key = "liquid",
        label = "液态玻璃",
        summary = "强高光 · 边缘光 · 层次更立体",
    ),
    ;

    companion object {
        val Default: GlassMode = Gaussian

        fun fromKey(key: String?): GlassMode = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * 全局**配色预设**：设置页可切换，选择持久化到 SharedPreferences（见 UiSettings）。
 *
 * ## 它管什么、不管什么
 *
 * 只管**色相**：`accent`、页面背景梯度、背景光斑、内侧色感、泛光。
 * **不碰**任何"结构性"外观（层级、模糊半径、阴影高度、描边分级）—— 那些是
 * [GlassMode] 的维度。两个维度正交，于是"6 套配色 × 2 种玻璃风格 × 明暗"
 * 不会退化成 24 份手写样式表。
 *
 * ## 为什么默认值必须等于改动前的取值
 *
 * [Default] 取 [CampusBlue]，且这套配色的每一个字段都**逐字段等于**引入配色系统之前的
 * 硬编码值。老用户升级后看不到任何视觉变化 —— 否则"加了个功能"和"界面被改了"
 * 就分不清了。这条约束由 `GlassTokenGoldenTest` 钉住。
 */
enum class GlassPalette(val key: String, val label: String) {
    /** 校园蓝：品牌主色，也是默认配色（= 引入配色系统之前的全部取值） */
    CampusBlue(key = "campus_blue", label = "校园蓝"),

    /** 薄荷：青绿系，冷而清透 */
    Mint(key = "mint", label = "薄荷"),

    /** 暮紫：紫罗兰系，偏夜色 */
    Dusk(key = "dusk", label = "暮紫"),

    /** 落日：琥珀 / 橙系，暖 */
    Sunset(key = "sunset", label = "落日"),

    /** 墨绿：祖母绿系，沉稳 */
    Ink(key = "ink", label = "墨绿"),

    /** 樱花：玫红 / 粉系 */
    Sakura(key = "sakura", label = "樱花"),
    ;

    companion object {
        val Default: GlassPalette = CampusBlue

        /** 认不出的键（手改 prefs / 降级安装）一律回落默认配色，不抛异常 */
        fun fromKey(key: String?): GlassPalette = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * 玻璃层级：
 * - [Bar]   页头 / 底栏：大面积、更通透，实时模糊采样背景
 * - [Panel] 弹窗 / 表单容器：可读性优先，不透明度更高、模糊半径更小
 */
enum class GlassLevel { Bar, Panel }

/**
 * 玻璃条上"与内容相接"的那一侧（分隔线画在这里）。
 *
 * 为什么不画整圈描边：页头/底栏是贴屏幕物理边缘的，而圆角屏、挖孔屏、曲面屏
 * 会裁切屏幕边缘——在物理边缘画线会出现"线伸进圆角里被切掉一截""两侧竖线贴边"
 * 的观感。因此只在玻璃与内容交界处画 1dp 分隔线，物理边缘一侧留空。
 */
enum class GlassEdge { None, Top, Bottom }

/**
 * 统一玻璃设计令牌。**模式 × 明暗 × 配色**的全部差异都收敛在这里，
 * 组件只消费令牌，不写两套逻辑，更不写 `if (mode == Liquid)`。
 *
 * ## 为什么没有 `luminance` 字段
 *
 * 设计规格 §3.1 曾列出一个"柔光明度"`luminance: Float`，实现时**删掉了**：
 * 它没有任何独立消费者 —— 玻璃的"亮 / 暗"已经完全由 tint 的 alpha 与
 * [highlight] 表达，再加一个谁也不读的浮点数，只是留给下一个人去猜
 * "它和 tint 的 alpha 是什么关系"。**要调亮度就调 tint / highlight**，
 * 不要再引入第三个明度旋钮。
 *
 * @param barTint / panelTint / cardTint 背景（background），含不透明度。
 *        面板（弹窗/表单）在 88%–92%，高于列表卡片：遮罩透上来不会把面板洗灰
 * @param barBlur                        blurRadius，控制在 8–16dp，避免过度模糊
 * @param border / borderWidth           描边（border）
 * @param barShadow / panelShadow / cardShadow / shadowColor 阴影（shadow）；
 *        页头/底栏的 barShadow 是"顶部浮在内容之上"的关键层次线索
 * @param highlight                      顶部柔光高光（highlight），方向由 [lightAngle] 决定
 * @param saturation / saturationOverlay 色感强度与叠加层（saturation）
 * @param edgeLight / innerShadow        液态玻璃专有：边缘光、内阴影
 * @param blobColors                     背景三个装饰光斑的**色相**（见其 KDoc）
 * @param innerTint                      玻璃内侧的**同色系**着色（区别于黑色的 [innerShadow]）
 * @param bloom                          静态泛光渐变，只画在"大表面"上（见其 KDoc）
 * @param lightAngle                     [highlight] 线性渐变的方向（度）
 * @param blobAlphaScale                 背景光斑色感缩放（高斯模糊模式更收敛）
 */
@Immutable
data class GlassTokens(
    val mode: GlassMode,
    val dark: Boolean,
    val palette: GlassPalette,
    // ── background ──
    val barTint: Color,
    val panelTint: Color,
    val cardTint: Color,
    val cardTintHighlighted: Color,
    /** 面板内输入框底色：必须与面板底色拉开一档，否则字段"消失"在面板里 */
    val fieldTint: Color,
    // ── blurRadius ──
    /** 页头/底栏的实时模糊半径（8–16dp，避免过度模糊） */
    val barBlur: Dp,
    // ── border ──
    val border: Color,
    val borderWidth: Dp,
    /** 玻璃条与内容交界处的分隔线；液态玻璃为亮白边缘光，高斯模糊为主题描边色 */
    val barSeparator: Color,
    // ── shadow ──
    /**
     * 页头投在内容上的柔和阴影高度（渐变渐隐）。
     * 画在玻璃条**下方**而不是垫在玻璃底下：玻璃是半透明的，
     * 垫在下面的阴影会透上来把整条压暗，反而显得"顶部在下面"。
     */
    val barShadow: Dp,
    val panelShadow: Dp,
    val cardShadow: Dp,
    val shadowColor: Color,
    // ── highlight ──
    val highlight: List<Color>,
    /** [highlight] 线性渐变的方向（度，0=向右、90=向下、180=自上而下） */
    val lightAngle: Float,
    // ── saturation ──
    val saturation: Float,
    val saturationOverlay: Color,
    // ── 液态玻璃专有 ──
    val edgeLight: Color,
    val innerShadow: Color,
    /** 内侧同色系着色（独立表面）：与黑色的 [innerShadow] 是两回事 —— 那个是"厚度"，这个是"染色" */
    val innerTint: Color,
    /** 静态泛光渐变的色档（由内到外），画在玻璃外沿下方，**不参与 Haze** */
    val bloom: List<Color>,
    // ── 页面背景 / 光斑 / 遮罩 / 强调色 ──
    val backgroundGradient: List<Color>,
    /**
     * 背景三个装饰光斑的**色相**（不透明度归令牌层，见下面的 `Blob*Alpha` 常量）。
     *
     * 为什么这三个颜色必须住在令牌层：它们原本写死在 `LiquidGlass.kt` 的渲染代码里，
     * 于是"换配色"根本换不动背景 —— 页面背景变成绿的了，光斑还是蓝的。
     * 渲染层只该决定**怎么画**（径向渐变、轨道运动），**画什么颜色**是样式决策。
     */
    val blobColors: List<Color>,
    val blobAlphaScale: Float,
    val scrim: Color,
    val accent: Color,
)

/** 当前生效的玻璃令牌，由 ManyCourseTheme 提供，切换模式/配色后所有组件自动重组 */
val LocalGlassTokens = staticCompositionLocalOf { glassTokens(GlassMode.Default, dark = false) }

/** 当前生效的玻璃模式（设置页做选中态用） */
val LocalGlassMode = staticCompositionLocalOf { GlassMode.Default }

/** 当前生效的配色（设置页做选中态用），与 [LocalGlassMode] 严格同构 */
val LocalGlassPalette = staticCompositionLocalOf { GlassPalette.Default }

/**
 * 光斑渐变的三档不透明度：中心 → 中段 → 外沿全透明。
 *
 * 放在令牌层而不是渲染层，是为了让"光斑有多浓"和"光斑什么色"一样可调，
 * 且升级配色系统时这三档取值**逐字节不变**（渲染层从此不再出现任何颜色字面量）。
 */
private const val BlobAlphaInner = 0x4D
private const val BlobAlphaMid = 0x1A
private const val BlobAlphaOuter = 0x00

/** 中部偏右那个小光斑更淡一档（原本写死 0x40 / 0x15，这里是各自的内/中档） */
private const val BlobAlphaInnerSoft = 0x40
private const val BlobAlphaMidSoft = 0x15

/** 同色系色感纱层的强度上限（与既有取值一致，避免"顺手调一下"造成回归） */
private const val SaturationOverlayBoost = 0.45f
private const val SaturationOverlayDamp = 0.4f
private const val SaturationOverlayMax = 0.08f

/**
 * 色感叠加层：>1 叠加主色调增强色彩，<1 叠加中性灰收敛。
 *
 * @param base 该配色的**基色**。高斯模糊那套原本用 `CampusSlate500`（中性灰）收敛、
 *   用 `CampusBlue400`（品牌色）增强 —— 换成别的配色后仍然叠蓝色就等于配色没生效，
 *   所以基色必须跟着配色走。
 */
private fun saturationOverlayOf(saturation: Float, base: Color): Color = when {
    saturation > 1f ->
        base.copy(alpha = ((saturation - 1f) * SaturationOverlayBoost).coerceIn(0f, SaturationOverlayMax))

    saturation < 1f ->
        CampusSlate500.copy(alpha = ((1f - saturation) * SaturationOverlayDamp).coerceIn(0f, SaturationOverlayMax))

    else -> Color.Transparent
}

/**
 * 一套配色里**与玻璃模式无关**的那部分取值。
 *
 * 分出来是为了让"加第 7 套配色"只写一个 `when` 分支、不碰到任何模式相关的逻辑 ——
 * 模式相关的取值（各 tint / 模糊 / 阴影 / 描边）在 [glassTokens] 里，与配色正交。
 */
@Immutable
private data class PaletteValues(
    /** 浅色主题下要在浅背景上可读的**深**档 */
    val accentLight: Color,
    /** 深色主题下要在深背景上可读的**亮**档 */
    val accentDark: Color,
    /**
     * 页面背景梯度（高斯 / 液态 × 浅色 / 深色 四组，见 [Gradient4] 的说明）。
     */
    val gradient: Gradient4,
    /** 三个背景光斑的色相，顺序 = (左上轨道光斑, 右下轨道光斑, 中部小光斑) */
    val blobs: List<Color>,
    /** 色感纱层的基色（见 [saturationOverlayOf] 的 `base`） */
    val overlayLight: Color,
    val overlayDark: Color,
    val innerTintLight: Color,
    val innerTintDark: Color,
    val bloomLight: List<Color>,
    val bloomDark: List<Color>,
) {
    fun accent(dark: Boolean): Color = if (dark) accentDark else accentLight

    fun background(mode: GlassMode, dark: Boolean): List<Color> = when (mode) {
        GlassMode.Gaussian -> if (dark) gradient.gaussianDark else gradient.gaussianLight
        GlassMode.Liquid -> if (dark) gradient.liquidDark else gradient.liquidLight
    }

    fun overlay(dark: Boolean): Color = if (dark) overlayDark else overlayLight

    fun innerTint(dark: Boolean): Color = if (dark) innerTintDark else innerTintLight

    fun bloom(dark: Boolean): List<Color> = if (dark) bloomDark else bloomLight
}

/**
 * 一套配色的四组页面背景梯度（高斯 / 液态 × 浅色 / 深色）。
 *
 * 拆成四个而不是"一套浅色 + 一套深色"，是因为**两种玻璃模式的背景本来就不同**：
 * 液态玻璃的梯度明显更沉、更饱和。这不是本轮新加的区别 ——
 * 当初把两套合成一套时，`GlassTokenGoldenTest` 立刻就在
 * `Liquid dark backgroundGradient[0]` 上把它拦下来了。
 */
@Immutable
private data class Gradient4(
    val gaussianLight: List<Color>,
    val gaussianDark: List<Color>,
    val liquidLight: List<Color>,
    val liquidDark: List<Color>,
)

/** 由三元组构造一组梯度色 */
private fun gradient(vararg hex: Long): List<Color> = hex.map { Color(it) }

/**
 * 各配色的取值表。
 *
 * ## 哪些数字是实测的、哪些是推出来的
 *
 * - [GlassPalette.CampusBlue] 的四组梯度**全部是实测的既有取值**，由 `GlassTokenGoldenTest`
 *   逐字节钉死 —— 它是"引入配色系统不产生视觉回归"的那条底线。
 * - 其余五套的**高斯**梯度是本轮按色相设计的；**液态**梯度则是照搬实测的那条通道关系推出来的：
 *
 *   ```
 *   浅色：高斯 - (09, 06, 00) = 液态      // 蓝通道顶着 0xFF 减不下去，所以那一档不被压
 *   深色：高斯 + (-01, +02, +08) = 液态   // 蓝通道被推高 = 夜色更饱和
 *   ```
 *
 *   这条关系是从校园蓝那一对既有取值里解出来的，**逐字节复现**它（见脚本推导记录）。
 *   照搬而不是给六套各编一套，是为了让"液态比高斯更沉"这件事在六套配色上**一致地成立** ——
 *   否则配色一换，液态/高斯的差别忽大忽小，用户会以为切换玻璃风格坏了。
 *
 * ## 设计口径
 *
 * - `background*Light` 用低饱和浅色（同色相混白）；`background*Dark` 用深色（同色相压暗）；
 * - `accentLight` 是**深**档、`accentDark` 是**亮**档 —— 两者都要在各自的背景上可读，
 *   因为 `accent` 会被当小字颜色用（页头刷新按钮、底栏选中态、chip 选中文字）。
 *   门槛是 `accent` 对背景中档的 WCAG 对比度 **≥ 4.805**（= 改动前四组基线的最小值，
 *   见 `GlassPaletteContrastTest` 里那段门槛来历）；
 * - `blobs` 用同色系中等饱和度的色相，不透明度另由 `Blob*Alpha` 决定；
 * - `innerTint` 是同一色相的极淡着色（玻璃内侧染色），`bloom` 是同一色相的静态泛光。
 */
private fun paletteValues(palette: GlassPalette): PaletteValues = when (palette) {
    // 校园蓝 = 原值。这三个光斑色相就是原本写死在 LiquidGlass.kt 里的 90CAF9 / CE93D8 / 7DD3FC
    GlassPalette.CampusBlue -> PaletteValues(
        accentLight = CampusBlue600,
        accentDark = CampusBlue400,
        gradient = Gradient4(
            gaussianLight = gradient(0xFFDCEAFF, 0xFFF5F9FF, 0xFFE8F0FE),
            gaussianDark = gradient(0xFF0B1220, 0xFF131C2E, 0xFF0E1729),
            liquidLight = gradient(0xFFD3E4FF, 0xFFF2F7FF, 0xFFE0EBFF),
            liquidDark = gradient(0xFF0A1428, 0xFF122040, 0xFF0B1730),
        ),
        blobs = gradient(0xFF90CAF9, 0xFFCE93D8, 0xFF7DD3FC),
        overlayLight = CampusBlue400,
        overlayDark = CampusBlue400,
        innerTintLight = CampusBlue600.copy(alpha = 0.05f),
        innerTintDark = CampusBlue400.copy(alpha = 0.08f),
        bloomLight = listOf(
            CampusBlue600.copy(alpha = 0.20f),
            CampusBlue600.copy(alpha = 0.07f),
            Color.Transparent,
        ),
        bloomDark = listOf(
            CampusBlue400.copy(alpha = 0.22f),
            CampusBlue400.copy(alpha = 0.08f),
            Color.Transparent,
        ),
    )

    GlassPalette.Mint -> PaletteValues(
        accentLight = Color(0xFF0E6E64),
        accentDark = Color(0xFF5EEAD4),
        gradient = Gradient4(
            gaussianLight = gradient(0xFFDCF5F1, 0xFFF2FBF9, 0xFFE4F7F3),
            gaussianDark = gradient(0xFF07231F, 0xFF0D332E, 0xFF082A25),
            liquidLight = gradient(0xFFD3EFF1, 0xFFE9F5F9, 0xFFDBF1F3),
            liquidDark = gradient(0xFF062527, 0xFF0C3536, 0xFF072C2D),
        ),
        blobs = gradient(0xFF4DD9C0, 0xFF8FE3C8, 0xFF6FD8E8),
        overlayLight = Color(0xFF2DD4BF),
        overlayDark = Color(0xFF2DD4BF),
        innerTintLight = Color(0xFF0E6E64).copy(alpha = 0.05f),
        innerTintDark = Color(0xFF5EEAD4).copy(alpha = 0.08f),
        bloomLight = listOf(
            Color(0xFF0E6E64).copy(alpha = 0.20f),
            Color(0xFF0E6E64).copy(alpha = 0.07f),
            Color.Transparent,
        ),
        bloomDark = listOf(
            Color(0xFF5EEAD4).copy(alpha = 0.22f),
            Color(0xFF5EEAD4).copy(alpha = 0.08f),
            Color.Transparent,
        ),
    )

    GlassPalette.Dusk -> PaletteValues(
        accentLight = Color(0xFF6D28D9),
        accentDark = Color(0xFFC4B5FD),
        gradient = Gradient4(
            gaussianLight = gradient(0xFFEBE3FF, 0xFFF7F3FF, 0xFFF0E8FF),
            gaussianDark = gradient(0xFF140B2E, 0xFF1E1445, 0xFF150D33),
            liquidLight = gradient(0xFFE2DDFF, 0xFFEEEDFF, 0xFFE7E2FF),
            liquidDark = gradient(0xFF130D36, 0xFF1D164D, 0xFF140F3B),
        ),
        blobs = gradient(0xFFA78BFA, 0xFFD8B4FE, 0xFF8B9CF7),
        overlayLight = Color(0xFFA78BFA),
        overlayDark = Color(0xFFA78BFA),
        innerTintLight = Color(0xFF6D28D9).copy(alpha = 0.05f),
        innerTintDark = Color(0xFFC4B5FD).copy(alpha = 0.08f),
        bloomLight = listOf(
            Color(0xFF6D28D9).copy(alpha = 0.20f),
            Color(0xFF6D28D9).copy(alpha = 0.07f),
            Color.Transparent,
        ),
        bloomDark = listOf(
            Color(0xFFC4B5FD).copy(alpha = 0.22f),
            Color(0xFFC4B5FD).copy(alpha = 0.08f),
            Color.Transparent,
        ),
    )

    GlassPalette.Sunset -> PaletteValues(
        accentLight = Color(0xFF92400E),
        accentDark = Color(0xFFFCD34D),
        gradient = Gradient4(
            gaussianLight = gradient(0xFFFFE9D6, 0xFFFFF6EE, 0xFFFFEEDF),
            gaussianDark = gradient(0xFF2A1509, 0xFF3A1F0D, 0xFF2B170A),
            liquidLight = gradient(0xFFF6E3D6, 0xFFF6F0EE, 0xFFF6E8DF),
            liquidDark = gradient(0xFF291711, 0xFF392115, 0xFF2A1912),
        ),
        blobs = gradient(0xFFFBBF72, 0xFFF49A7A, 0xFFFAD08A),
        overlayLight = Color(0xFFFBBF72),
        overlayDark = Color(0xFFFBBF72),
        innerTintLight = Color(0xFF92400E).copy(alpha = 0.05f),
        innerTintDark = Color(0xFFFCD34D).copy(alpha = 0.08f),
        bloomLight = listOf(
            Color(0xFF92400E).copy(alpha = 0.20f),
            Color(0xFF92400E).copy(alpha = 0.07f),
            Color.Transparent,
        ),
        bloomDark = listOf(
            Color(0xFFFCD34D).copy(alpha = 0.22f),
            Color(0xFFFCD34D).copy(alpha = 0.08f),
            Color.Transparent,
        ),
    )

    GlassPalette.Ink -> PaletteValues(
        accentLight = Color(0xFF065F46),
        accentDark = Color(0xFF6EE7B7),
        gradient = Gradient4(
            gaussianLight = gradient(0xFFDCF2E7, 0xFFF1FAF5, 0xFFE3F5EC),
            gaussianDark = gradient(0xFF07231A, 0xFF0C3327, 0xFF082820),
            liquidLight = gradient(0xFFD3ECE7, 0xFFE8F4F5, 0xFFDAEFEC),
            liquidDark = gradient(0xFF062522, 0xFF0B352F, 0xFF072A28),
        ),
        blobs = gradient(0xFF4ADE80, 0xFF7BD9B0, 0xFF5EC9A8),
        overlayLight = Color(0xFF34D399),
        overlayDark = Color(0xFF34D399),
        innerTintLight = Color(0xFF065F46).copy(alpha = 0.05f),
        innerTintDark = Color(0xFF6EE7B7).copy(alpha = 0.08f),
        bloomLight = listOf(
            Color(0xFF065F46).copy(alpha = 0.20f),
            Color(0xFF065F46).copy(alpha = 0.07f),
            Color.Transparent,
        ),
        bloomDark = listOf(
            Color(0xFF6EE7B7).copy(alpha = 0.22f),
            Color(0xFF6EE7B7).copy(alpha = 0.08f),
            Color.Transparent,
        ),
    )

    GlassPalette.Sakura -> PaletteValues(
        accentLight = Color(0xFFBE123C),
        accentDark = Color(0xFFFDA4AF),
        gradient = Gradient4(
            gaussianLight = gradient(0xFFFFE0E9, 0xFFFFF2F6, 0xFFFFE6EE),
            gaussianDark = gradient(0xFF2B0B18, 0xFF3C1224, 0xFF2C0C1A),
            liquidLight = gradient(0xFFF6DAE9, 0xFFF6ECF6, 0xFFF6E0EE),
            liquidDark = gradient(0xFF2A0D20, 0xFF3B142C, 0xFF2B0E22),
        ),
        blobs = gradient(0xFFF9A8D4, 0xFFFDA4AF, 0xFFE9A6E0),
        overlayLight = Color(0xFFF9A8D4),
        overlayDark = Color(0xFFF9A8D4),
        innerTintLight = Color(0xFFBE123C).copy(alpha = 0.05f),
        innerTintDark = Color(0xFFFDA4AF).copy(alpha = 0.08f),
        bloomLight = listOf(
            Color(0xFFBE123C).copy(alpha = 0.20f),
            Color(0xFFBE123C).copy(alpha = 0.07f),
            Color.Transparent,
        ),
        bloomDark = listOf(
            Color(0xFFFDA4AF).copy(alpha = 0.22f),
            Color(0xFFFDA4AF).copy(alpha = 0.08f),
            Color.Transparent,
        ),
    )
}

/**
 * 令牌工厂：模式 × 明暗 × 配色 的全部取值。
 * 面板/卡片不透明度都落在可读区间（浅色 72%–86% 白，深色 72%–85% 深灰）。
 *
 * `palette` 给默认值，是为了让"引入配色系统"这件事**不改变任何既有调用点**：
 * 不传配色的地方拿到的仍然是与改动前逐字段相同的令牌。
 */
fun glassTokens(
    mode: GlassMode,
    dark: Boolean,
    palette: GlassPalette = GlassPalette.Default,
): GlassTokens {
    val values = paletteValues(palette)
    // 光斑色只承载**色相**（完全不透明）：浓淡全部由 blobAlphas() 的三档决定。
    // 混在一起的话，"换个光斑色"会顺手改掉"光斑有多浓"，而后者是校过观感的
    val blobColors = values.blobs.toList()

    return when (mode) {
        GlassMode.Gaussian -> {
            val saturation = if (dark) 0.96f else 0.94f
            GlassTokens(
                mode = mode,
                dark = dark,
                palette = palette,
                // 高斯模糊：偏实的磨砂底；页头比内容亮一档 → 读起来是"上层"
                barTint = if (dark) Color(0xFF0F172A).copy(alpha = 0.62f) else Color.White.copy(alpha = 0.52f),
                // 弹窗/表单面板：不透明度拉高，避免遮罩透上来把面板"洗灰"
                panelTint = if (dark) Color(0xFF1E293B).copy(alpha = 0.94f) else Color.White.copy(alpha = 0.94f),
                cardTint = if (dark) Color(0xFF1E293B).copy(alpha = 0.78f) else Color.White.copy(alpha = 0.78f),
                cardTintHighlighted = if (dark) Color(0xFF1E293B).copy(alpha = 0.86f) else Color.White.copy(alpha = 0.86f),
                fieldTint = if (dark) Color(0xFF16202F) else Color(0xFFE3EBF7),
                barBlur = 14.dp,
                border = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.5f),
                borderWidth = 1.dp,
                // 高斯模糊：克制的发丝线，沿用设计系统中性色
                barSeparator = if (dark) Color(0xFF334155) else CampusSlate200,
                barShadow = 12.dp,
                panelShadow = 8.dp,
                cardShadow = 3.dp,
                shadowColor = if (dark) Color.Black.copy(alpha = 0.5f) else CampusSlate900.copy(alpha = 0.1f),
                highlight = listOf(
                    Color.White.copy(alpha = if (dark) 0.06f else 0.14f),
                    Color.White.copy(alpha = 0.02f),
                    Color.Transparent,
                ),
                lightAngle = HighlightAngleVertical,
                saturation = saturation,
                // 与 field 同源：既有的 saturationOverlay 值本来就是"按 saturation 算出来"的，
                // 明写这一行只是把"它必须等于 overlay 规则的结果"这件事钉在数据类里
                saturationOverlay = saturationOverlayOf(saturation, values.overlay(dark)),
                edgeLight = Color.Transparent,
                innerShadow = Color.Transparent,
                innerTint = values.innerTint(dark),
                bloom = values.bloom(dark),
                backgroundGradient = values.background(mode, dark),
                blobColors = blobColors,
                // 高斯模糊模式收敛光斑色感；液态玻璃保留满强度色彩（深色下也不收）
                blobAlphaScale = if (dark) 0.7f else 0.75f,
                scrim = if (dark) Color.Black.copy(alpha = 0.62f) else Color(0x520F172A),
                accent = values.accent(dark),
            )
        }

        GlassMode.Liquid -> {
            val saturation = if (dark) 1.04f else 1.1f
            GlassTokens(
                mode = mode,
                dark = dark,
                palette = palette,
                // 液态玻璃：页头底色比内容亮一档（"浮在内容之上"），质感靠高光/边缘光
                barTint = if (dark) Color(0xFF0F172A).copy(alpha = 0.58f) else Color.White.copy(alpha = 0.46f),
                // 液态玻璃：底更通透，但仍高于"看得清"的下限（高光/边缘光撑质感，不靠透明度）
                panelTint = if (dark) Color(0xFF1E293B).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.90f),
                cardTint = if (dark) Color(0xFF1E293B).copy(alpha = 0.74f) else Color.White.copy(alpha = 0.72f),
                cardTintHighlighted = if (dark) Color(0xFF1E293B).copy(alpha = 0.84f) else Color.White.copy(alpha = 0.82f),
                fieldTint = if (dark) Color(0xFF16202F) else Color(0xFFE8EFFA),
                barBlur = 16.dp,
                border = if (dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.72f),
                borderWidth = 1.dp,
                // 液态玻璃：交界处用亮白边缘光，替代整圈描边
                barSeparator = if (dark) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.6f),
                barShadow = 14.dp,
                panelShadow = 14.dp,
                cardShadow = 6.dp,
                shadowColor = if (dark) Color.Black.copy(alpha = 0.55f) else CampusSlate900.copy(alpha = 0.14f),
                highlight = listOf(
                    Color.White.copy(alpha = if (dark) 0.1f else 0.3f),
                    Color.White.copy(alpha = if (dark) 0.03f else 0.06f),
                    Color.White.copy(alpha = 0.02f),
                ),
                lightAngle = HighlightAngleVertical,
                saturation = saturation,
                saturationOverlay = saturationOverlayOf(saturation, values.overlay(dark)),
                edgeLight = Color.White.copy(alpha = if (dark) 0.22f else 0.6f),
                innerShadow = Color.Black.copy(alpha = if (dark) 0.1f else 0.05f),
                innerTint = values.innerTint(dark),
                bloom = values.bloom(dark),
                backgroundGradient = values.background(mode, dark),
                blobColors = blobColors,
                // 液态玻璃不分明暗，光斑都是满强度（与改动前一致：这里原本就写死 1f）
                blobAlphaScale = 1f,
                scrim = if (dark) Color.Black.copy(alpha = 0.58f) else Color(0x520F172A),
                accent = values.accent(dark),
            )
        }
    }
}

/**
 * `highlight` 的默认方向：**自上而下**。
 *
 * 这不是随便取的：既有实现用的是 `Brush.verticalGradient(tokens.highlight)`，
 * 而"90° 的线性渐变"与 `verticalGradient` 的取样完全等价 —— 于是把方向改成
 * `linearGradient` 这件事本身**一个像素都不动**（有金值测试盯着 highlight 的取值）。
 */
const val HighlightAngleVertical: Float = 90f

/**
 * 一个光斑渐变的三档不透明度：**中心 → 中段 → 外沿全透明**。
 *
 * 渲染层用它拼 `Brush.radialGradient`：颜色来自 [GlassTokens.blobColors]，
 * 每一档的浓淡来自这里。拆成两个来源是为了让"换个颜色"和"调个浓淡"互不牵连。
 *
 * @param index 第几个光斑（0/1 = 两个轨道光斑，2 = 中部那个小光斑，后者原本就更淡一档）
 */
fun blobAlphas(index: Int): List<Float> = if (index == 2) {
    listOf(BlobAlphaInnerSoft / 255f, BlobAlphaMidSoft / 255f, BlobAlphaOuter / 255f)
} else {
    listOf(BlobAlphaInner / 255f, BlobAlphaMid / 255f, BlobAlphaOuter / 255f)
}
