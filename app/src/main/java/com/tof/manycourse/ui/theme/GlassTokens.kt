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
 * 统一玻璃设计令牌。两种模式 / 明暗主题的全部差异都收敛在这里，
 * 组件只消费令牌，不写两套逻辑。
 *
 * @param barTint / panelTint / cardTint 背景（background），含不透明度。
 *        面板（弹窗/表单）在 88%–92%，高于列表卡片：遮罩透上来不会把面板洗灰
 * @param barBlur                        blurRadius，控制在 8–16dp，避免过度模糊
 * @param border / borderWidth           描边（border）
 * @param barShadow / panelShadow / cardShadow / shadowColor 阴影（shadow）；
 *        页头/底栏的 barShadow 是"顶部浮在内容之上"的关键层次线索
 * @param highlight                      顶部柔光高光（highlight）
 * @param saturation / saturationOverlay 色感强度与叠加层（saturation）
 * @param edgeLight / innerShadow        液态玻璃专有：边缘光、内阴影
 * @param blobAlphaScale                 背景光斑色感缩放（高斯模糊模式更收敛）
 */
@Immutable
data class GlassTokens(
    val mode: GlassMode,
    val dark: Boolean,
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
    // ── saturation ──
    val saturation: Float,
    val saturationOverlay: Color,
    // ── 液态玻璃专有 ──
    val edgeLight: Color,
    val innerShadow: Color,
    // ── 页面背景 / 遮罩 / 强调色 ──
    val backgroundGradient: List<Color>,
    val blobAlphaScale: Float,
    val scrim: Color,
    val accent: Color,
)

/** 当前生效的玻璃令牌，由 ManyCourseTheme 提供，切换模式后所有组件自动重组 */
val LocalGlassTokens = staticCompositionLocalOf { glassTokens(GlassMode.Default, dark = false) }

/** 当前生效的玻璃模式（设置页做选中态用） */
val LocalGlassMode = staticCompositionLocalOf { GlassMode.Default }

private fun saturationOverlayOf(saturation: Float): Color = when {
    saturation > 1f -> CampusBlue400.copy(alpha = ((saturation - 1f) * 0.45f).coerceIn(0f, 0.08f))
    saturation < 1f -> CampusSlate500.copy(alpha = ((1f - saturation) * 0.4f).coerceIn(0f, 0.08f))
    else -> Color.Transparent
}

/**
 * 令牌工厂：模式 × 明暗 的全部取值。
 * 面板/卡片不透明度都落在可读区间（浅色 72%–86% 白，深色 72%–85% 深灰）。
 */
fun glassTokens(mode: GlassMode, dark: Boolean): GlassTokens = when (mode) {
    GlassMode.Gaussian -> GlassTokens(
        mode = mode,
        dark = dark,
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
        saturation = if (dark) 0.96f else 0.94f,
        saturationOverlay = saturationOverlayOf(if (dark) 0.96f else 0.94f),
        edgeLight = Color.Transparent,
        innerShadow = Color.Transparent,
        backgroundGradient = if (dark) {
            listOf(Color(0xFF0B1220), Color(0xFF131C2E), Color(0xFF0E1729))
        } else {
            listOf(Color(0xFFDCEAFF), Color(0xFFF5F9FF), Color(0xFFE8F0FE))
        },
        blobAlphaScale = if (dark) 0.7f else 0.75f,
        scrim = if (dark) Color.Black.copy(alpha = 0.62f) else Color(0x520F172A),
        accent = if (dark) CampusBlue400 else CampusBlue600,
    )

    GlassMode.Liquid -> GlassTokens(
        mode = mode,
        dark = dark,
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
        saturation = if (dark) 1.04f else 1.1f,
        saturationOverlay = saturationOverlayOf(if (dark) 1.04f else 1.1f),
        edgeLight = Color.White.copy(alpha = if (dark) 0.22f else 0.6f),
        innerShadow = Color.Black.copy(alpha = if (dark) 0.1f else 0.05f),
        backgroundGradient = if (dark) {
            listOf(Color(0xFF0A1428), Color(0xFF122040), Color(0xFF0B1730))
        } else {
            listOf(Color(0xFFD3E4FF), Color(0xFFF2F7FF), Color(0xFFE0EBFF))
        },
        blobAlphaScale = 1f,
        scrim = if (dark) Color.Black.copy(alpha = 0.58f) else Color(0x520F172A),
        accent = if (dark) CampusBlue400 else CampusBlue600,
    )
}
