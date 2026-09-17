package com.tof.manycourse.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.CampusPick
import com.tof.manycourse.data.DeviceLocation
import com.tof.manycourse.data.SchoolMap
import com.tof.manycourse.data.pickCampus
import com.tof.manycourse.gr_api.SchoolRegistry
import com.tof.manycourse.ui.components.CampusChip
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.theme.LocalGlassTokens
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 缩放下限：1 = 整张图刚好完整显示（ContentScale.Fit），不允许再缩小 */
private const val MIN_SCALE = 1f

/** 缩放上限：地图原图 1200px 级，放到 6 倍足够看清楼名，再多就是马赛克了 */
private const val MAX_SCALE = 6f

/**
 * 校园地图页。
 *
 * ## 显示哪张图：**按定位挑校区**
 *
 * 一所学校可能有好几个校区、每个校区一张图（广工 5 个、广软 2 个），所以进页面先定位一次，
 * 挑**离得最近**的那个校区的地图（判定与兜底全在 `data/CampusPicker.kt` 的 [pickCampus] 里，
 * 这里只管把状态接上、把"为什么是这张图"说清楚）：
 *
 * ```
 *   点进来的那一刻
 *        ├── 还没定位 / 正在定位   → 先显示**主校区**，副标题写"正在定位…"
 *        ├── 有权限、定位成功      → 换成本校区那张图，并写明"已按定位选「大学城校区」（约 0.6 km）"
 *        ├── 没权限（拒绝 / 没问过）→ 主校区 + 一句"可手动选校区"，并给一个「定位」补救入口
 *        └── 拿不到位置（GPS 关 / 超时）→ 主校区 + 说明原因
 * ```
 *
 * ★ **手选的校区压过定位**：定位会飘（只给"大致位置"时误差可达几公里、在楼里可能落到隔壁校区），
 * 而"我在哪个校区"是用户一眼就知道的事 —— 点过校区切换条之后，下一次定位不再把它改回去
 * （换学校时手选自动作废，见 [pickCampus]）。
 *
 * ## 交互
 *
 * 双指缩放 / 拖动 / 双击复位，右下角有复位按钮；换校区时缩放与位置**重置**
 * （靠 `key(campus.id)` 重建，否则新图会继承上一张的缩放，看着像"图坏了"）。
 * 缩放用 `graphicsLayer`（绘制阶段）而不是改尺寸，避免每帧重新布局。
 *
 * @param schoolId 当前学校（登录的学校优先，其次登录页选中的那个，见 `MapFragment`）
 * @param visible 本页是否**可见且在前台**（`isTabForeground(2)`）。为什么需要它：
 *   四个页面常驻（hide/show 切换），不门控的话**App 一启动就会弹定位权限框** ——
 *   权限只在用户真的打开地图页时才问，之后每次回到本页再静默刷新一次定位
 *   （人可能从大学城走到东风路，图该跟着换）。
 */
@Composable
fun MapScreen(
    schoolId: String?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val school = SchoolRegistry.find(schoolId)
    val campuses = remember(schoolId) { SchoolMap.campusesOf(schoolId) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 定位状态：只影响"显示哪张图"和提示文案 ────────────────────────────────
    var location by remember(schoolId) { mutableStateOf<DeviceLocation?>(null) }
    var locating by remember(schoolId) { mutableStateOf(false) }
    var permissionDenied by remember(schoolId) { mutableStateOf(false) }
    /** 被拒绝到系统不再弹窗（第二次拒绝）：只能去系统设置里改 */
    var permissionBlocked by remember(schoolId) { mutableStateOf(false) }
    var locationFailed by remember(schoolId) { mutableStateOf(false) }
    // 手选的校区（换学校自动作废 —— pickCampus 会忽略不属于这所学校的 id）
    var manualCampusId by remember(schoolId) { mutableStateOf<String?>(null) }

    /** 取一次定位并把三种失败分开记下来（文案不一样，见下面的提示行）*/
    fun locate() {
        if (locating) return
        locating = true
        locationFailed = false
        scope.launch {
            val fix = CampusLocation.currentLocation(context)
            location = fix
            locationFailed = fix == null
            locating = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // 精确或大致任一给到手就算有权限（Android 12 起用户可以只给"大致位置"）
        if (grants.values.any { it }) {
            permissionDenied = false
            permissionBlocked = false
            locate()
        } else {
            // ★ 第二次被拒 = 系统**不再弹窗**（Android 11 起"拒绝两次"会直接返回拒绝）。
            //   那时再怎么点「定位」都没用，必须把用户送到系统设置 —— 所以分开记一笔
            permissionBlocked = permissionDenied
            permissionDenied = true
            locating = false
        }
    }

    /** 打开本应用的系统设置页（权限被永久拒绝时唯一的出路）*/
    val openAppSettings = {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // 定位只在**本页可见时**进行：四个页面常驻，不门控的话 App 一启动就弹定位权限框。
    // 第一次进来先问权限（只问一次，被拒绝后不再骚扰 —— 想再试就点提示行右边的「定位」）；
    // 之后每次回到本页静默刷新一次（人可能在两个校区之间走动，图该跟着换）。
    var askedOnce by remember(schoolId) { mutableStateOf(false) }
    LaunchedEffect(schoolId, visible) {
        if (!visible || campuses.isEmpty()) return@LaunchedEffect
        if (CampusLocation.hasPermission(context)) {
            // ★ 权限可能在**系统设置里**被补上（用户被"去设置"送过去改完再回来）：
            //   这时候必须把"被拒/被阻断"的状态清掉，否则说明行会一直挂着那句"权限已被拒绝"
            permissionDenied = false
            permissionBlocked = false
            askedOnce = true
            locate()
            return@LaunchedEffect
        }
        if (askedOnce) return@LaunchedEffect
        askedOnce = true
        // 两条一起申请：这样系统弹窗里才会**同时**给出「精确 / 大致」两个选项
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    val pick = pickCampus(campuses = campuses, manualCampusId = manualCampusId, location = location)
    val campus = pick.campus

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ═══ 标题行：学校名 + 当前校区 ═══
        Column(Modifier.fillMaxWidth()) {
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
                text = when {
                    school == null -> "校园地图"
                    campus == null -> "校园地图"
                    else -> "校园地图 · ${campus.name} · 双指缩放、拖动查看"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ═══ 校区切换条：只在多校区时占位（单校区给一条没得选的控件是噪音）═══
        if (campuses.size >= 2) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                campuses.forEach { item ->
                    CampusChip(
                        text = item.name.removeSuffix("校区"),
                        selected = item.id == campus?.id,
                        onClick = { manualCampusId = item.id },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ═══ 定位状态：为什么显示这张图 / 怎么换 ═══
        LocationHintRow(
            hint = locationHintOf(
                pick = pick,
                // 只有"还没有任何定位结果"时才说"正在定位…先显示主校区"：
                // 回到本页的静默刷新不该让说明行闪一下（那时显示的是上一次的校区）
                locating = locating && location == null,
                permissionDenied = permissionDenied,
                permissionBlocked = permissionBlocked,
                locationFailed = locationFailed,
                hasPermission = CampusLocation.hasPermission(context),
            ),
            onAction = { action ->
                when (action) {
                    LocationAction.OpenSettings -> openAppSettings()
                    // 「定位」与「重新定位」是同一个动作：撤掉手选（回到"按定位选"，否则新结果
                    // 会被手选压住、按钮看着没反应），然后有权限就再取一次，没权限就申请
                    LocationAction.Locate, LocationAction.Relocate -> {
                        manualCampusId = null
                        if (CampusLocation.hasPermission(context)) {
                            locate()
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        }
                    }
                }
            },
        )

        Spacer(Modifier.height(8.dp))

        // ═══ 地图本体：换校区时重建（缩放/位移复位），否则新图会继承上一张的缩放 ═══
        GlassCard(
            cornerRadius = 12,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (campus == null) {
                NoMapPlaceholder(school?.name)
            } else {
                key(campus.id) {
                    ZoomableMap(
                        resId = campus.drawableRes,
                        description = "${school?.name.orEmpty()}${campus.name}地图",
                    )
                }
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

/**
 * 地图上方那一行**定位说明**：为什么显示这张图、怎么换、以及一个补救入口。
 *
 * @param hint 文案与补救入口（判定在纯函数 [locationHintOf] 里，可单测）
 * @param onAction 点右边那个链接式动作（「定位」/「重新定位」/「去设置」）
 */
@Composable
private fun LocationHintRow(hint: LocationHint, onAction: (LocationAction) -> Unit) {
    val tokens = LocalGlassTokens.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = hint.text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        hint.action?.let { action ->
            Text(
                text = action.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAction(action) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 定位说明的**文案 + 补救入口**（纯函数，所以能单测）。
 *
 * 每个状态都自报家门，不做静默行为 —— 用户看到一张图，必须知道它是"按定位来的"、
 * "手选的"还是"兜底的"，否则"进了东风路校区却显示大学城的图"只会被当成 bug：
 *
 * | 状态 | 文案 | 右侧动作 |
 * |---|---|---|
 * | 正在定位（还没有结果） | `正在定位…（先显示主校区，定位到就自动切换）` | —— |
 * | 按定位选中，3 km 内 | `已按定位选「大学城校区」（约 0.6 km）` | —— |
 * | 按定位选中，更远 | `最近的是「揭阳校区」（约 412 km），可点上面的校区名切换` | —— |
 * | 手选过 | `已手动选「东风路校区」，点「定位」可按当前位置重新选` | 「定位」 |
 * | 权限被永久拒绝 | `定位权限已被拒绝：请到系统设置里允许「位置信息」` | 「去设置」 |
 * | 没权限 / 拒绝过一次 | `未授权定位：默认显示主校区，点「定位」授权后自动切换` | 「定位」 |
 * | 有权限但拿不到位置 | `拿不到定位（GPS 可能没开）：默认显示主校区，可手动选校区` | 「重新定位」 |
 * | 其它 | `默认显示「XX校区」` / `这所学校还没有地图` | —— |
 *
 * 动作只出现在"再点一次能好"的情况；定位成功时不给按钮 ——
 * 那一行只是说明，不是控件（`TextButton` 自带 40dp 最小高度会把说明行撑成两倍高）。
 *
 * @param locating 正在取**第一次**定位（还没有任何结果）。已经有结果时**不要**传 true ——
 *   那时页面显示的是上一次的校区，跳过这一档才不会在静默刷新时闪一下
 * @param permissionDenied 这次会话里问过权限且被拒 → 优先说"未授权"，
 *   否则用户会一直点「重新定位」而其实根本没给过权限
 * @param locationFailed 有权限但没拿到位置（GPS 关着 / 超时）
 * @param permissionBlocked 拒绝到**系统不再弹窗**了 → 唯一的出路是去系统设置，
 *   这时给「定位」按钮是骗人的（点了没有任何反应）。**放在最后**是为了不动前面几个位置参数
 */
internal fun locationHintOf(
    pick: CampusPick,
    locating: Boolean,
    permissionDenied: Boolean,
    locationFailed: Boolean,
    hasPermission: Boolean,
    permissionBlocked: Boolean = false,
): LocationHint = when {
    locating -> LocationHint("正在定位…（先显示主校区，定位到就自动切换）")
    pick is CampusPick.ByLocation && pick.nearby -> LocationHint(
        "已按定位选「${pick.campus.name}」（约 ${formatDistanceMeters(pick.distanceMeters)}）",
    )
    pick is CampusPick.ByLocation -> LocationHint(
        "最近的是「${pick.campus.name}」（约 ${formatDistanceMeters(pick.distanceMeters)}），" +
            "可点上面的校区名切换",
    )
    pick is CampusPick.Manual -> LocationHint(
        text = if (permissionBlocked) {
            // 手选过、但定位权限被永久拒绝：这里给「定位」是骗人的（点了不会再弹窗）
            "已手动选「${pick.campus.name}」（定位权限已被拒绝，可在系统设置里打开）"
        } else {
            "已手动选「${pick.campus.name}」，点「定位」可按当前位置重新选"
        },
        action = if (permissionBlocked) LocationAction.OpenSettings else LocationAction.Locate,
    )
    permissionBlocked -> LocationHint(
        "定位权限已被拒绝（系统不再弹窗）：请到系统设置里允许「位置信息」",
        action = LocationAction.OpenSettings,
    )
    !hasPermission || permissionDenied -> LocationHint(
        "未授权定位：默认显示主校区，点「定位」授权后自动切换",
        action = LocationAction.Locate,
    )
    locationFailed -> LocationHint(
        "拿不到定位（GPS 可能没开）：默认显示主校区，可手动选校区",
        action = LocationAction.Relocate,
    )
    pick is CampusPick.Default && pick.campus != null -> LocationHint("默认显示「${pick.campus.name}」")
    else -> LocationHint("这所学校还没有地图")
}

/**
 * [locationHintOf] 的结论：一句话 + 右侧可选的补救入口。
 *
 * 动作用**枚举**而不是字符串：文案会改（"定位" / "重新定位" / "去设置"），
 * 而"点了要干什么"是行为 —— 分成两件事，测试断言行为、UI 显示文案。
 */
internal data class LocationHint(val text: String, val action: LocationAction? = null)

/** 说明行右侧那个动作：点它要干什么（文案在 [label] 上）*/
internal enum class LocationAction(val label: String) {
    /** 申请权限（还没有权限时）或再取一次定位 */
    Locate("定位"),

    /** 有权限但上次没拿到位置：再试一次 */
    Relocate("重新定位"),

    /** 权限被永久拒绝：跳到系统设置页 */
    OpenSettings("去设置"),
}

/**
 * 距离文案：`320 m` / `0.6 km` / `412 km`。
 *
 * 1 公里以内写米（"离主楼 300 米"比"0.3 公里"好读），再远一律公里 —— 这一步只是给用户
 * 一个"为什么是这张图"的量级感，不需要精确。小数位固定用 `Locale.US`：默认 locale 在
 * 某些语言下会写成 `0,6 km`（逗号），中文/英文环境里都应该是点号。
 */
internal fun formatDistanceMeters(meters: Double): String = when {
    meters < 1_000 -> "${meters.roundToInt()} m"
    meters < 10_000 -> String.format(java.util.Locale.US, "%.1f km", meters / 1000)
    else -> "${(meters / 1000).roundToInt()} km"
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
                "再在 data/SchoolMap.kt 里登记这所学校的校区即可",
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
 *
 * 调用方用 `key(campus.id)` 包着它：换校区时整块重建，缩放与位移回到初始 —— 否则
 * 上一张图放大到 4 倍，切到下一张还是 4 倍且位置偏移，看着像新图加载坏了。
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
