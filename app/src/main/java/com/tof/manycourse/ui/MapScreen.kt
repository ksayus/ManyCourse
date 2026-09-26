package com.tof.manycourse.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.CampusPick
import com.tof.manycourse.data.CourseEntry
import com.tof.manycourse.data.CoursePlacement
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.DeviceLocation
import com.tof.manycourse.data.MapAnchor
import com.tof.manycourse.data.MapPoint
import com.tof.manycourse.data.MapPointStore
import com.tof.manycourse.data.SchoolCampus
import com.tof.manycourse.data.SchoolMap
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.data.WifiFingerprintStore
import com.tof.manycourse.data.WifiFix
import com.tof.manycourse.data.coursesOfDate
import com.tof.manycourse.data.imagePlacementOf
import com.tof.manycourse.data.isInsideImage
import com.tof.manycourse.data.locateByWifi
import com.tof.manycourse.data.pickCampus
import com.tof.manycourse.data.placeCoursesOnMap
import com.tof.manycourse.data.projectToImage
import com.tof.manycourse.data.scanWifiOnce
import com.tof.manycourse.gr_api.SchoolRegistry
import com.tof.manycourse.ui.components.CampusChip
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.theme.LocalGlassTokens
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.max
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
    val tokens = LocalGlassTokens.current

    // ── 开发者模式 ────────────────────────────────────────────────────────
    val devMode by UiSettings.devMode
    // 换校区时清掉"正在等你点图"这类临时状态：否则切到新图之后，
    // 上一次没点完的那一下会落到新校区上，采出一条莫名其妙的数据
    LaunchedEffect(campus?.id) { MapDevSession.reset() }

    // ── 标定 & 标记 ───────────────────────────────────────────────────────
    // 标定每次现算（几个锚点的最小二乘，微秒级）：刚采完一个锚点，图上的标记立刻跟着动
    val calibration = MapPointStore.calibrationOf(campus?.id)
    val todayEntries = coursesOfDate(LocalDate.now())
    val todayPlacements = placeCoursesOnMap(
        entries = todayEntries.orEmpty(),
        points = MapPointStore.allPoints,
        calibration = calibration,
        campusId = campus?.id,
    )
    val onMapCourses = todayPlacements.filterIsInstance<CoursePlacement.OnMap>()

    val markers = buildList {
        // 今日课程：图上用**序号**（图上写不下课程名），序号与下面那份清单一一对应
        onMapCourses.forEachIndexed { index, placement ->
            add(
                MapMarker(
                    imageX = placement.imageX,
                    imageY = placement.imageY,
                    label = "${index + 1}",
                    kind = MapMarkerKind.Course,
                    color = CourseRepository.colorOfName(placement.entry.name),
                )
            )
        }
        // 开发者模式：把采过的点位也画出来 —— 采完立刻能看出"标到哪儿了、偏没偏"
        if (devMode) {
            MapPointStore.pointsOf(campus?.id).forEach { point ->
                val placement = imagePlacementOf(point, calibration) ?: return@forEach
                if (isInsideImage(placement.first, placement.second)) {
                    add(MapMarker(placement.first, placement.second, "", MapMarkerKind.Point, tokens.accent))
                }
            }
        }
    }

    /**
     * 「我在这儿」有两个来源，**GPS 优先**：
     *  1. GPS（室外）：经标定投影到图上 —— 米级；
     *  2. **Wi-Fi 指纹**（室内，GPS 拿不到或落在图外）：走几圈采下来的指纹库反查 —— 区域级。
     *
     * 两个都没有就画不出来，那时页面会如实说清缺的是哪一样（见 [TodayCourseMapLegend]）。
     */
    val gpsMarker = location
        ?.let { fix -> projectToImage(fix.latitude, fix.longitude, calibration) }
        ?.takeIf { isInsideImage(it.first, it.second) }
        ?.let { (x, y) -> MapMarker(x, y, "", MapMarkerKind.MyLocation, tokens.accent) }

    var wifiFix by remember(campus?.id) { mutableStateOf<WifiFix?>(null) }
    var wifiFixUsedCache by remember(campus?.id) { mutableStateOf(false) }

    val wifiMarker = if (gpsMarker == null) {
        wifiFix
            ?.takeIf { isInsideImage(it.imageX, it.imageY) }
            ?.let { MapMarker(it.imageX, it.imageY, "", MapMarkerKind.MyLocation, tokens.accent) }
    } else {
        null
    }

    val myMarker = gpsMarker ?: wifiMarker

    // 室内没有 GPS 时才去扫一次 Wi-Fi（这个校区**已经有指纹**才有意义）——
    // 否则白扫一次，还占掉一次被系统节流的扫描机会
    LaunchedEffect(campus?.id, visible, location, calibration) {
        val id = campus?.id ?: return@LaunchedEffect
        if (!visible || gpsMarker != null) return@LaunchedEffect
        if (WifiFingerprintStore.countFor(id) == 0) return@LaunchedEffect
        val scan = scanWifiOnce(context) ?: return@LaunchedEffect
        wifiFix = locateByWifi(id, scan.aps)
        wifiFixUsedCache = scan.fromCache
    }

    val myLocationNote = when {
        gpsMarker != null -> "蓝点 = 你现在的位置（GPS）"
        wifiMarker != null ->
            "蓝点 = Wi-Fi 估算的区域位置" +
                (if (wifiFixUsedCache) "（用的是系统缓存的扫描；" else "（") +
                "参考 ${wifiFix?.matched ?: 0} 条指纹 · 区域级，不是教室级）"
        location != null && calibration == null -> "已定位，但这个校区还没标定过，所以画不出「我在这儿」"
        location != null -> "已定位，但你的位置不在这张图的范围内"
        else -> null
    }

    /**
     * 开发者模式在"等你点图"时，把点到的屏幕位置换算成图上归一化坐标并落库。
     *
     * 采完立刻清掉 [MapDevSession.pendingPick]（一次点击只采一个点）——
     * 留着的话，用户随手再点一下图就会多出一个同名点位，而且没有任何提示。
     */
    val onPickAt: (Float, Float) -> Unit = { imageX, imageY ->
        val request = MapDevSession.pendingPick.value
        if (request != null && campus != null) {
            when (request) {
                is MapPickRequest.Point -> {
                    MapPointStore.addPoint(
                        context,
                        MapPoint(
                            id = "${campus.id}-${System.currentTimeMillis()}",
                            campusId = campus.id,
                            name = request.name,
                            imageX = imageX,
                            imageY = imageY,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    MapDevSession.message.value = "已采集「${request.name}」（图上位置）"
                }

                is MapPickRequest.Anchor -> {
                    MapPointStore.addAnchor(
                        context,
                        MapAnchor(
                            campusId = campus.id,
                            name = request.name,
                            imageX = imageX,
                            imageY = imageY,
                            latitude = request.latitude,
                            longitude = request.longitude,
                        ),
                    )
                    val total = MapPointStore.anchorsOf(campus.id).size
                    MapDevSession.message.value =
                        if (total < 2) {
                            "已加锚点「${request.name}」· 还差 ${2 - total} 个才能标定"
                        } else {
                            "已加锚点「${request.name}」· 共 $total 个，标定已生效"
                        }
                }
            }
            MapDevSession.pendingPick.value = null
        }
    }

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

        // ═══ 开发者模式：采集工具栏（采点 / 标定都从这里发起）═══
        if (devMode) {
            Spacer(Modifier.height(8.dp))
            DevModeHeader(schoolId = schoolId, campus = campus)
        }

        Spacer(Modifier.height(8.dp))

        // ═══ 地图本体：换校区时重建（缩放/位移复位），否则新图会继承上一张的缩放 ═══
        GlassCard(
            cornerRadius = 12,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                // 开发者模式的两个"占满整块"的界面：走几圈采集 / 点位面板
                devMode && MapDevSession.walkMode.value ->
                    WalkPanel(schoolId = schoolId, campus = campus)

                devMode && MapDevSession.panelExpanded.value ->
                    DevModePanel(schoolId = schoolId, campus = campus)

                campus == null -> NoMapPlaceholder(school?.name)

                else -> key(campus.id) {
                    ZoomableMap(
                        resId = campus.drawableRes,
                        description = "${school?.name.orEmpty()}${campus.name}地图",
                        markers = markers,
                        myLocation = myMarker,
                        // 只有"正在等你点图"时才让点击去采点，平时点一下什么也不做
                        // （双击复位仍随时可用，见 ZoomableMap）
                        onPickAt = if (MapDevSession.pendingPick.value != null) onPickAt else null,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ═══ 今日课程：图上那几个序号分别是什么、缺的又是哪一类 ═══
        TodayCourseMapLegend(
            todayEntries = todayEntries,
            placements = todayPlacements,
            campuses = campuses,
            onSwitchCampus = { manualCampusId = it },
            hasCalibration = calibration != null,
            devMode = devMode,
            myLocationNote = myLocationNote,
        )

        Spacer(Modifier.height(10.dp))

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
 * 图上要画的一个标记。
 *
 * [imageX] / [imageY] 是**图上归一化坐标**（0~1，左上角原点），不是像素 ——
 * 同一份标记在任何屏幕尺寸、任何缩放倍数下都能画对。
 */
internal data class MapMarker(
    val imageX: Float,
    val imageY: Float,
    /** 圆点里的字（课程用序号；其它标记留空）*/
    val label: String,
    val kind: MapMarkerKind,
    val color: Color,
)

/** 标记的三种画法：课程（带序号的实心圆）/ 采集点位（小点）/ 我在这儿（蓝点 + 光圈）*/
internal enum class MapMarkerKind { Course, Point, MyLocation }

/**
 * 可缩放拖动的图片：`graphicsLayer` 做缩放位移，`transformable` 收手势。
 *
 * 位移做了**边界收拢**：缩放到 N 倍后，最多只能拖到"图片边缘贴住容器边缘"，
 * 不允许把图拖出视野 —— 否则很容易出现"地图被拖没了，看起来像加载失败"。
 * 边界按 `ContentScale.Fit` 算出的实际显示尺寸推。
 *
 * 调用方用 `key(campus.id)` 包着它：换校区时整块重建，缩放与位移回到初始 —— 否则
 * 上一张图放大到 4 倍，切到下一张还是 4 倍且位置偏移，看着像新图加载坏了。
 *
 * ## 标记与"图上点选"都建立在同一套换算上
 *
 * 标记的位置和"用户点在图上的哪一点"是**同一个换算的正反两面**，
 * 都收在 [MapViewport] 里（纯数据 + 纯函数，单测钉住"缩放绕**容器中心**"这个关键点）。
 * 这里只负责：把视口交给 [MapViewport] → 拿它算标记位置 / 反算点选坐标。
 *
 * @param onPickAt 不为 null 时表示"正在等用户在图上点一下"（开发者模式采点/标定），
 *   点到的位置会以**图上归一化坐标**回调出去；为 null 时单击没有任何副作用
 *   （双击复位始终可用）
 */
@Composable
private fun ZoomableMap(
    resId: Int,
    description: String,
    markers: List<MapMarker>,
    myLocation: MapMarker?,
    onPickAt: ((Float, Float) -> Unit)?,
) {
    val painter = painterResource(resId)
    val imageWidth = painter.intrinsicSize.width
    val imageHeight = painter.intrinsicSize.height

    var container by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    /**
     * ★ 用 `derivedStateOf` 而不是普通 `val`：下面 `pointerInput` 里的那个 lambda
     * **会活很久**（只在点选模式开关时重建），闭包捕获一个普通 `val` 拿到的是
     * "创建那一刻"的视口 —— 于是缩放/拖动之后点图采点会整体偏移，而且不缩放时看着还是对的。
     * 读 State 的当前值才是对的。
     */
    val viewportState = remember {
        derivedStateOf {
            MapViewport(
                containerWidth = container.width.toFloat(),
                containerHeight = container.height.toFloat(),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                scale = scale,
                offsetX = offset.x,
                offsetY = offset.y,
            )
        }
    }
    val viewport = viewportState.value

    fun clampOffset(candidate: Offset, currentScale: Float): Offset {
        if (currentScale <= MIN_SCALE) return Offset.Zero
        val maxX = max(0f, (viewport.fittedWidth * currentScale - container.width) / 2f)
        val maxY = max(0f, (viewport.fittedHeight * currentScale - container.height) / 2f)
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

    val pickMode = onPickAt != null

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .onSizeChanged { container = it }
            .pointerInput(pickMode) {
                detectTapGestures(
                    onTap = { position ->
                        // 只有"正在等点图"时才响应单击；平时点一下什么也不做，
                        // 不然用户随手一碰就多出一条数据
                        if (pickMode) {
                            viewportState.value.toImage(position.x, position.y)
                                ?.let { (imageX, imageY) -> onPickAt?.invoke(imageX, imageY) }
                        }
                    },
                    onDoubleTap = { reset() },
                )
            }
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

        // 标记画在图片**之上**，但**不放进 graphicsLayer**：位置由 MapViewport 现算，
        // 所以放大时标记不会跟着一起放大（6 倍下圆点还是圆点，否则会糊成一个色块遮住楼名）
        MarkerOverlay(markers = markers, myLocation = myLocation, viewport = viewport)

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

/**
 * 把标记画到图上（一个 Canvas 画完，不用一堆 Composable）。
 *
 * 为什么用 Canvas 而不是给每个标记摆一个 Composable：位置要按 [MapViewport] 现算，
 * 用 Composable 就得自己算 dp/px 并让每个标记"中心对齐到某个点"（还得反过来补偿自身尺寸），
 * 而 Canvas 里 `drawCircle(center = ...)` 天生就是中心对齐。
 *
 * 画在 Canvas 上的东西**不参与命中测试** —— 所以开发者模式"点图采点"不会误点标记，
 * 点哪儿就是哪儿（这正是采点需要的）。
 */
@Composable
private fun MarkerOverlay(
    markers: List<MapMarker>,
    myLocation: MapMarker?,
    viewport: MapViewport,
) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        markers.forEach { marker ->
            if (marker.kind == MapMarkerKind.MyLocation) return@forEach // 单独画，要压在最上层
            val (x, y) = viewport.toScreen(marker.imageX, marker.imageY) ?: return@forEach
            val center = Offset(x, y)
            when (marker.kind) {
                MapMarkerKind.Course -> {
                    // 白圈垫底：课程色可能与地图底色接近，不垫就"看不见有个标记"
                    drawCircle(Color.White.copy(alpha = 0.92f), radius = 13.dp.toPx(), center = center)
                    drawCircle(marker.color, radius = 11.dp.toPx(), center = center)
                    if (marker.label.isNotBlank()) {
                        val layout = measurer.measure(
                            text = marker.label,
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(
                                center.x - layout.size.width / 2f,
                                center.y - layout.size.height / 2f,
                            ),
                        )
                    }
                }

                MapMarkerKind.Point -> {
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = center)
                    drawCircle(marker.color, radius = 4.dp.toPx(), center = center)
                }

                MapMarkerKind.MyLocation -> Unit
            }
        }

        // 「我在这儿」画最后：它是"当前这一刻"的信息，不该被任何标记盖住
        myLocation?.let { marker ->
            val (x, y) = viewport.toScreen(marker.imageX, marker.imageY) ?: return@let
            val center = Offset(x, y)
            drawCircle(marker.color.copy(alpha = 0.22f), radius = 20.dp.toPx(), center = center)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = center)
            drawCircle(marker.color, radius = 6.5.dp.toPx(), center = center)
        }
    }
}

/**
 * 地图下方那一行/几行：**"今日课程"在地图上的解释**。
 *
 * 它要回答三件事，缺了任何一件用户都会觉得"这功能没用"：
 *  1. 图上那些序号是什么课（[CoursePlacement.OnMap] → 逐条列出）；
 *  2. **没画出来的那些为什么没画** —— 分成"教室还没采点位"（要出门）/ "有点位但没标定"
 *     （在图上点两下就行）/ "点位在别的校区"（切过去就能看）三类，逐类给出下一步动作；
 *  3. 蓝点（我在这儿）画出来了没有；没画出来是缺定位还是缺标定。
 */
@Composable
private fun TodayCourseMapLegend(
    todayEntries: List<CourseEntry>?,
    placements: List<CoursePlacement>,
    campuses: List<SchoolCampus>,
    onSwitchCampus: (String) -> Unit,
    hasCalibration: Boolean,
    devMode: Boolean,
    /** 「我在这儿」那句话（含"为什么没画出来"）；null = 什么都不用说 */
    myLocationNote: String?,
) {
    val tokens = LocalGlassTokens.current
    val onMap = placements.filterIsInstance<CoursePlacement.OnMap>()
    val notCollected = placements.filterIsInstance<CoursePlacement.NotCollected>()
    val offImage = placements.filterIsInstance<CoursePlacement.OffImage>()
    val otherCampus = placements.filterIsInstance<CoursePlacement.OtherCampus>()

    Column(Modifier.fillMaxWidth()) {
        Text(
            // 用 if/else 而不是 when：`coursesOfDate` 的 null（没有课表数据）与空表（今天真的没课）
            // 是两件事，两句话不能混；写过 when 分支条件里依赖前一个分支的非空推断，改起来容易踩空
            text = if (todayEntries == null) {
                "今天没有课表数据（去「课表」页同步一次）"
            } else if (todayEntries.isEmpty()) {
                "今天没课"
            } else {
                "今日 ${todayEntries.size} 门课 · 图上标出 ${onMap.size} 门"
            },
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // 蓝点状态：GPS 不行时会退到 Wi-Fi 指纹，两样都不行就说清缺的是哪一样
        myLocationNote?.let { HintLine(text = it) }

        // ① 图上那几门，序号与圆点一一对应
        onMap.take(4).forEachIndexed { index, placement ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CourseRepository.colorOfName(placement.entry.name),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${placement.entry.name} · ${placement.entry.periodLabel} · ${placement.entry.room}",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onMap.size > 4) {
            HintLine(text = "…图上还有 ${onMap.size - 4} 门（完整课表见「课表」页）")
        }

        // ② 没画出来的三类，各给各的下一步
        if (notCollected.isNotEmpty()) {
            HintLine(
                text = "${notCollected.size} 门教室还没采点位：" +
                    notCollected.take(3).joinToString("、") { it.entry.room } +
                    if (notCollected.size > 3) " 等" else "" +
                        if (devMode) "　→ 到那儿用「原地采点」" else "　→ 设置里打开开发者模式即可采集",
            )
        }
        if (offImage.isNotEmpty()) {
            HintLine(
                text = "${offImage.size} 门有点位、但落不到图上" +
                    (if (!hasCalibration) "（这个校区还没标定）" else "（点位落在图片范围之外）") +
                    if (devMode) "　→ 用「标定」采两个锚点" else "",
            )
        }
        if (otherCampus.isNotEmpty()) {
            val campusName = campuses.firstOrNull { it.id == otherCampus.first().campusId }?.name
                ?: otherCampus.first().campusId
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${otherCampus.size} 门课在「$campusName」",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "切过去看",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSwitchCampus(otherCampus.first().campusId) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 图例里的次要一行（11sp、次要色）*/
@Composable
private fun HintLine(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
