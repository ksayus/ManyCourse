package com.tof.manycourse.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.CourseRepository
import com.tof.manycourse.data.DeviceLocation
import com.tof.manycourse.data.MapPoint
import com.tof.manycourse.data.MapPointAccuracyHint
import com.tof.manycourse.data.MapPointStore
import com.tof.manycourse.data.SchoolCampus
import com.tof.manycourse.data.WalkSummary
import com.tof.manycourse.data.WifiFingerprintStore
import com.tof.manycourse.data.calibrationResidualMeters
import com.tof.manycourse.data.locateByWifi
import com.tof.manycourse.data.matchPointForRoom
import com.tof.manycourse.data.scanWifiOnce
import com.tof.manycourse.ui.components.CampusButton
import com.tof.manycourse.ui.components.CampusChip
import com.tof.manycourse.ui.theme.LocalGlassTokens
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 开发者模式的**会话状态**（跨 Composable 共享，所以放单例 —— 与 `MainTabStore` 同一套路）。
 *
 * 为什么要有它：地图页有两块地方要读同一份状态 ——
 *  - 地图本体（`ZoomableMap`）要知道"现在是不是在等用户点图"（决定点击是采点还是双击复位）；
 *  - 开发者模式面板要知道"面板展开了没、刚才那步成不成"。
 * 把状态挂在某一个 Composable 里就得靠回调串上来串下去，反而更难读。
 */
internal object MapDevSession {

    /**
     * 正在**等用户在图上点一下**（点完就落库一条锚点/点位）；null = 不在等待。
     *
     * ★ 进入这个状态时面板会自动收起 —— 不然图被面板盖住，用户根本没地方点。
     */
    val pendingPick = mutableStateOf<MapPickRequest?>(null)

    /** 详细面板（点位列表 / 存储路径 / 待采集清单）是否展开；展开时它**占满**地图区域 */
    val panelExpanded = mutableStateOf(false)

    /** **轨迹采集模式**（走几圈）：同样占满地图区域 —— 走路时要按的是大按钮，不是地图 */
    val walkMode = mutableStateOf(false)

    /** 上一趟走完的结论（停下来之后一直显示，直到被下一趟覆盖）*/
    val lastWalkSummary = mutableStateOf<WalkSummary?>(null)

    /** 正在取定位（按钮要显示忙碌，避免连点）*/
    val locating = mutableStateOf(false)

    /** 上一步的反馈（"已采集「三教」" / "拿不到定位"），显示在工具栏那一行 */
    val message = mutableStateOf<String?>(null)

    /** 离开开发者模式 / 换校区时把临时状态清掉（别把上一步的提示留到下一次）*/
    fun reset() {
        pendingPick.value = null
        message.value = null
        locating.value = false
    }
}

/**
 * 开发者模式里"已经点了按钮、正在等用户在图点一下"的请求。
 *
 * 两种都能在图落点，区别只在**有没有真实坐标**：
 *  - [Anchor]：刚才取了定位 → 用户点出"我站的位置"→ 生成一个**标定锚点**（图上位置 + 真实坐标）；
 *  - [Point]：没有 GPS，只记图上位置（靠人眼把"图上那栋楼"和课表里的教室名对上）。
 */
internal sealed interface MapPickRequest {

    /** 点的时候给用户看的一句话 */
    val prompt: String

    /** 标定：站在这儿，点出你站的位置 */
    data class Anchor(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
    ) : MapPickRequest {
        override val prompt: String get() = "点一下图上「$name」的位置（你刚站的地方）"
    }

    /** 图上采点：只记图上位置 */
    data class Point(val name: String) : MapPickRequest {
        override val prompt: String get() = "点一下图上「$name」的位置"
    }
}

/** 「确定/取消」那个名称输入框要干什么 */
private sealed interface NameIntent {
    data class GpsPoint(val fix: DeviceLocation) : NameIntent
    data class ImagePoint(val prefill: String) : NameIntent
    data class AnchorAt(val fix: DeviceLocation) : NameIntent
}

/**
 * **开发者模式：工具栏那一行**（原地采点 / 图上采点 / 标定 / 面板 / 重载）。
 *
 * ## 它在整套东西里的位置
 *
 * 地图页要画出"我在这儿"和"今天这几门课在哪栋楼"，前提是**图上有地理参照** ——
 * 而校园平面图本身只是一张图片（谁也不知道它是哪、哪边是北）。补上这个缺口的动作就是
 * **采集**，而这个面板是采集入口：
 *
 * ```
 *   站在校门口 → 「原地采点」→ 命名"正门"        → 得到一个有经纬度的点位
 *   站在校门口 → 「标定」    → 命名"正门" + 在图上点出校门 → 得到一个锚点
 *   采到 ≥2 个锚点 → 经纬度就能换算成图上位置了（此后"我在这儿"和 GPS 采的点都会自动落图）
 * ```
 *
 * 采集的每一步都**如实反馈**（拿不到定位、图上点在哪、落到哪个文件），不静默成功 ——
 * 野外采数据最怕的就是"以为采上了"。
 */
@Composable
internal fun DevModeHeader(
    schoolId: String?,
    campus: SchoolCampus?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokens = LocalGlassTokens.current

    // 名称输入框（三个动作共用；内容不同所以带一个 intent）
    var naming by remember { mutableStateOf<NameIntent?>(null) }
    var draftName by remember { mutableStateOf("") }

    val campusId = campus?.id

    /**
     * 先取一次定位再继续。
     *
     * 为什么不缓存上一次定位：采点要求"人站在那个地方"，用几分钟前的定位等于把点位标错，
     * 而且错得**看不出来**。宁可多等这两秒。
     */
    fun withFreshFix(what: String, then: (DeviceLocation) -> Unit) {
        if (MapDevSession.locating.value) return
        if (!CampusLocation.hasPermission(context)) {
            MapDevSession.message.value = "没有定位权限：先在地图页点「定位」授权"
            return
        }
        MapDevSession.locating.value = true
        MapDevSession.message.value = "正在取定位…"
        scope.launch {
            val fix = CampusLocation.currentLocation(context)
            MapDevSession.locating.value = false
            if (fix == null) {
                MapDevSession.message.value = "$what：拿不到定位（GPS 可能没开，或在校内被遮挡）"
            } else {
                then(fix)
            }
        }
    }

    fun startNaming(intent: NameIntent, prefill: String) {
        draftName = prefill
        naming = intent
    }

    // ── 工具栏 ────────────────────────────────────────────────────────────
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DevChip("原地采点", enabled = !MapDevSession.locating.value && campus != null) {
                withFreshFix("原地采点") { fix -> startNaming(NameIntent.GpsPoint(fix), "") }
            }
            DevChip("图上采点", enabled = campus != null) {
                startNaming(NameIntent.ImagePoint(prefill = ""), "")
            }
            DevChip("标定", enabled = !MapDevSession.locating.value && campus != null) {
                withFreshFix("标定") { fix -> startNaming(NameIntent.AnchorAt(fix), "") }
            }
            DevChip(if (MapDevSession.panelExpanded.value) "收起面板" else "点位面板") {
                MapDevSession.panelExpanded.value = !MapDevSession.panelExpanded.value
                MapDevSession.walkMode.value = false
                MapDevSession.message.value = null
            }
            // 走几圈就采完：比"站定一个个采"快得多，见 ui/WalkPanel.kt
            DevChip(if (MapDevSession.walkMode.value) "退出轨迹采集" else "轨迹采集") {
                MapDevSession.walkMode.value = !MapDevSession.walkMode.value
                MapDevSession.panelExpanded.value = false
                MapDevSession.message.value = null
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── 状态行：正在等点图 / 上一步结果 / 常驻的采集统计 ──────────────
        val picking = MapDevSession.pendingPick.value
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = picking?.prompt ?: MapDevSession.message.value ?: campusSummary(campusId),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = if (picking != null) tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (picking != null) {
                Text(
                    text = "取消",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { MapDevSession.pendingPick.value = null }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    // ── 名称输入 ──────────────────────────────────────────────────────────
    naming?.let { intent ->
        val suggestions = when (intent) {
            is NameIntent.AnchorAt -> listOf("正门", "图书馆", "体育馆", "食堂", "宿舍区")
            else -> uncollectedRooms(campusId).take(8)
        }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = {
                Text(
                    text = when (intent) {
                        is NameIntent.AnchorAt -> "标定：这个点是什么地方"
                        is NameIntent.GpsPoint -> "原地采点：这里叫什么"
                        is NameIntent.ImagePoint -> "图上采点：这里叫什么"
                    },
                    fontSize = 16.sp,
                )
            },
            text = {
                Column {
                    Text(
                        text = when (intent) {
                            is NameIntent.AnchorAt ->
                                "名字只是给你自己看的。关键是**在图上点出你刚站的位置**，所以请挑一个图上认得出的地方（正门、某栋楼的角）。"
                            is NameIntent.GpsPoint ->
                                "名字会用来和课表里的地点匹配 —— 课表写「A1N403 禄口」时，这里填「A1N403」就能对上。"
                            is NameIntent.ImagePoint ->
                                "图上采点没有真实坐标，只记图上位置；适合「我认得这栋楼在图上的哪、但懒得走过去」的场合。"
                        },
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        singleLine = true,
                        label = { Text("名称", fontSize = 12.sp) },
                    )
                    if (suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "待采集（来自你的课表）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            suggestions.forEach { room ->
                                CampusChip(
                                    text = room,
                                    selected = draftName == room,
                                    onClick = { draftName = room },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draftName.isNotBlank(),
                    onClick = {
                        val name = draftName.trim()
                        naming = null
                        when (intent) {
                            is NameIntent.GpsPoint -> addGpsPoint(context, campusId, name, intent.fix)
                            is NameIntent.ImagePoint -> {
                                MapDevSession.panelExpanded.value = false // 要能看到图才点得到
                                MapDevSession.pendingPick.value = MapPickRequest.Point(name)
                            }
                            is NameIntent.AnchorAt -> {
                                MapDevSession.panelExpanded.value = false
                                MapDevSession.pendingPick.value = MapPickRequest.Anchor(
                                    name = name,
                                    latitude = intent.fix.latitude,
                                    longitude = intent.fix.longitude,
                                    accuracyMeters = intent.fix.accuracyMeters,
                                )
                            }
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("取消") } },
        )
    }
}

/** 工具栏上的一个小胶囊按钮（32dp 高，和校区条一致）*/
@Composable
private fun DevChip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = LocalGlassTokens.current
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(9999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (enabled) tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * **开发者模式：详细面板**（展开时占满地图区域）。
 *
 * 四块，都是"野外采数据"当场要看的：
 *  1. **存储**：数据到底写到哪个路径了、有没有拿到"所有文件访问权限"、上一份是什么时候写的；
 *  2. **标定**：这个校区有几个锚点、还差几个、**残差多少米**（这是判断"标得准不准"的唯一反馈）；
 *  3. **待采集地点**：从你的课表里列出**还没有点位**的教室 —— 这就是"还需要采什么"的清单；
 *  4. **已采集**：点位与锚点列表，采错了当场删。
 */
@Composable
internal fun DevModePanel(
    schoolId: String?,
    campus: SchoolCampus?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokens = LocalGlassTokens.current
    var scanning by remember { mutableStateOf(false) }
    val campusId = campus?.id
    val storage = MapPointStore.storage.value
    val calibration = MapPointStore.calibrationOf(campusId)
    val residual = calibration?.let { calibrationResidualMeters(it) }
    // 列表里只列"我自己采的"（内置那批随 APK 走、删不掉，列出来只会让人误以为能删）
    val points = MapPointStore.ownPointsOf(campusId)
    val anchors = MapPointStore.ownAnchorsOf(campusId)
    val allAnchors = MapPointStore.anchorsOf(campusId)
    val bundledPointCount = MapPointStore.bundledPoints.count { it.campusId == campusId }
    val bundledAnchorCount = MapPointStore.bundledAnchors.count { it.campusId == campusId }
    val pendingRooms = remember(campusId, MapPointStore.points.size, MapPointStore.bundledPoints.size) {
        uncollectedRooms(campusId)
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        // ═══ ① 存储 ═══
        PanelTitle("采集数据存到哪")
        Text(
            text = storage.directory ?: "还没有初始化存储",
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                storage.lastError != null -> "上次导出失败：${storage.lastError}"
                !storage.shared -> "⚠️ 没有「所有文件访问权限」，现在落在**应用专属目录**（不是 /sdcard/ManyCourse）。" +
                    "授权后会立刻改到系统根目录下的 ManyCourse/。"
                else -> "已写入系统根目录下的 ManyCourse/（mappoints.txt · mappoints.csv · mapanchors.csv）"
            },
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = if (storage.lastError != null || !storage.shared) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!storage.shared) {
                CampusButton(
                    text = "去授权",
                    modifier = Modifier.weight(1f),
                    onClick = { context.openAllFilesAccessSettings() },
                )
            } else {
                CampusButton(
                    text = "刷新路径",
                    modifier = Modifier.weight(1f),
                    onClick = { MapPointStore.updateStorage(context) },
                )
            }
            CampusButton(
                text = "重载文件",
                modifier = Modifier.weight(1f),
                onClick = {
                    val ok = MapPointStore.reloadFromShared(context)
                    MapDevSession.message.value =
                        if (ok) "已从文件重载" else "没读到可用文件（路径或格式不对）"
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // ═══ ② 标定 ═══
        PanelTitle("标定（把经纬度换算到图上）")
        Text(
            text = when {
                campus == null -> "还没有选中校区"
                allAnchors.size < 2 -> "这个校区只有 ${allAnchors.size} 个锚点，**还不够**（至少 2 个）。" +
                    "做法：站在一个图上认得出的地方 → 点「标定」→ 在图上点出你站的位置。两个锚点尽量隔远。" +
                    "（走几圈的「轨迹采集」也能顺便采点位，但**锚点只能定点采**）"
                residual == null -> "锚点够了，但解不出变换（两个锚点可能几乎重合）—— 重新采一个隔得远的"
                else -> "锚点 ${allAnchors.size} 个 · 标定残差约 ${residual.roundToInt()} m（" +
                    if (residual <= 30) "可以用了）" else "偏大，建议重采或补锚点）"
            },
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // ═══ ②b Wi-Fi 指纹库（室内「我在这儿」靠它）═══
        PanelTitle("Wi-Fi 指纹库（室内定位用）")
        val wifiCount = WifiFingerprintStore.countFor(campusId)
        Text(
            text = when {
                campus == null -> "还没有选中校区"
                wifiCount == 0 -> "本校区 ${wifiCount} 条：室内还定不了位。" +
                    "用上面的「轨迹采集」走一圈就有了（走的时候保证 Wi-Fi 开着）"
                else -> "本校区 $wifiCount 条（自己采的 ${WifiFingerprintStore.ownSamples.count { it.campusId == campusId }} 条 · " +
                    "随版本下发的 ${WifiFingerprintStore.bundledSamples.count { it.campusId == campusId }} 条）。" +
                    "精度是**区域级**（楼栋/楼层），不是教室级 —— 扫描被系统节流，指纹点之间隔着十几米"
            },
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (campus != null && wifiCount > 0) {
            Spacer(Modifier.height(8.dp))
            CampusButton(
                text = if (scanning) "正在扫描…" else "扫一次试试能不能定到位",
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scanning = true
                    scope.launch {
                        val scan = scanWifiOnce(context)
                        val fix = scan?.let { locateByWifi(campusId, it.aps) }
                        MapDevSession.message.value = when {
                            scan == null -> "扫不到 Wi-Fi（关着？没权限？）"
                            fix == null -> "扫到 ${scan.aps.size} 个 AP，但库里没有可比的指纹（共同 AP 太少）"
                            else -> "定到了图上 (%.3f, %.3f) · 参考 %d 条指纹 · 离散度 %.3f%s"
                                .format(fix.imageX, fix.imageY, fix.matched, fix.spread, if (scan.fromCache) "（缓存扫描）" else "")
                        }
                        scanning = false
                    }
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // ═══ ③ 待采集清单（来自课表）═══
        PanelTitle("待采集地点（来自你的课表）")
        if (pendingRooms.isEmpty()) {
            Text(
                text = if (CourseRepository.courses.isEmpty()) {
                    "课表还是空的 —— 先登录同步一次课表，这里就会列出所有教室"
                } else {
                    "课表里的地点都已经有对应点位了 👍"
                },
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "${pendingRooms.size} 个地点还没有点位。走到那儿点「原地采点」，名字照抄下面这些就能自动匹配上：",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            pendingRooms.forEach { room ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = room,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "去地图上找",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ═══ ④ 已采集 ═══
        PanelTitle(
            "已采集 · 点位 ${points.size} 个" +
                if (bundledPointCount > 0) " · 内置 $bundledPointCount 个（随版本下发，删不掉）" else ""
        )
        if (points.isEmpty()) {
            Text(
                text = if (bundledPointCount > 0) {
                    "我自己还没采过点位（下面的待采集清单已经排除内置点位了）。" +
                        "走到那栋楼点「原地采点」，或者用上面的「轨迹采集」走一圈。"
                } else {
                    "还没有点位。走到要标记的地方点上面的「原地采点」，或用「轨迹采集」走一圈。"
                },
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        points.forEach { point ->
            CollectedRow(
                title = point.name,
                detail = point.detailText(),
                onDelete = {
                    MapPointStore.removePoint(context, point.id)
                    MapDevSession.message.value = "已删除「${point.name}」"
                },
            )
        }

        if (anchors.isNotEmpty() || bundledAnchorCount > 0) {
            Spacer(Modifier.height(12.dp))
            PanelTitle(
                "已采集 · 锚点 ${anchors.size} 个" +
                    if (bundledAnchorCount > 0) " · 内置 $bundledAnchorCount 个" else ""
            )
            anchors.forEach { anchor ->
                CollectedRow(
                    title = anchor.name,
                    detail = String.format(
                        Locale.US,
                        "图上 (%.3f, %.3f) · %.5f, %.5f",
                        anchor.imageX, anchor.imageY, anchor.latitude, anchor.longitude,
                    ),
                    onDelete = {
                        MapPointStore.removeAnchor(context, anchor.campusId, anchor.name)
                        MapDevSession.message.value = "已删除锚点「${anchor.name}」"
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "采集只写你自己手机上这个 ManyCourse 目录，不上传任何地方。",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "学校 id：${schoolId ?: "（未登录）"}　校区 id：${campusId ?: "（未选中）"}",
            fontSize = 11.sp,
            color = tokens.accent,
        )
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun CollectedRow(title: String, detail: String, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = detail,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "删除",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDelete)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 工具栏那一行的常驻摘要：`点位 3 · 锚点 2（残差 18 m）` */
private fun campusSummary(campusId: String?): String {
    if (campusId == null) return "还没有选中校区"
    val points = MapPointStore.pointsOf(campusId).size
    val anchors = MapPointStore.anchorsOf(campusId)
    val residual = MapPointStore.calibrationOf(campusId)?.let { calibrationResidualMeters(it) }
    val calibrationText = when {
        anchors.size < 2 -> "锚点 ${anchors.size}/2（还标不了）"
        residual == null -> "锚点 ${anchors.size}（解不出）"
        else -> "锚点 ${anchors.size} · 残差 ${residual.roundToInt()} m"
    }
    return "点位 $points · $calibrationText"
}

/**
 * 课表里**还没有点位**的地点清单 —— 也就是"你还需要去采哪些点"。
 *
 * 数据来源是 `CourseRepository.courses` 的 `room`（教务系统给的是「教室 + 校区」拼起来的串，
 * 与 `CourseEntry.room` 是同一个口径，所以匹配规则能共用一套）。
 *
 * `internal` 而不是 private：[WalkPanel]（走几圈那个界面）的打点输入框也要用同一份清单 ——
 * 两处各算一份的话，迟早出现"站着采时看到的清单"和"走着采时看到的清单"不一样。
 */
internal fun uncollectedRooms(campusId: String?): List<String> {
    val points = MapPointStore.allPoints
    if (points.isEmpty() && CourseRepository.courses.isEmpty()) return emptyList()
    return CourseRepository.courses
        .map { it.room }
        .filter { it.isNotBlank() }
        .distinct()
        .filter { matchPointForRoom(it, points) == null }
        .sorted()
}

/** 原地采一个点（有 GPS；图上位置在渲染时用当前标定现算，见 `imagePlacementOf`）*/
private fun addGpsPoint(
    context: Context,
    campusId: String?,
    name: String,
    fix: DeviceLocation,
) {
    if (campusId == null) return
    MapPointStore.addPoint(
        context = context,
        point = MapPoint(
            id = "${campusId}-${System.currentTimeMillis()}",
            campusId = campusId,
            name = name,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = fix.accuracyMeters,
            createdAt = System.currentTimeMillis(),
        ),
    )
    val hint = MapPointAccuracyHint.of(fix.accuracyMeters)
    MapDevSession.message.value = "已采集「$name」· ${hint.label}"
}

/** 点位在列表里的明细：坐标 / 图上位置 / 精度，有什么写什么 */
private fun MapPoint.detailText(): String {
    val parts = mutableListOf<String>()
    if (latitude != null && longitude != null) {
        parts += String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    }
    if (imageX != null && imageY != null) {
        parts += String.format(Locale.US, "图上 (%.3f, %.3f)", imageX, imageY)
    }
    if (!hasGps && !hasImage) parts += "没有坐标"
    if (accuracyMeters != null) parts += "±${accuracyMeters.roundToInt()} m"
    if (note.isNotBlank()) parts += note
    return parts.joinToString(" · ")
}

/** 跳到"所有文件访问权限"设置页（Android 11+ 唯一的入口，普通弹窗给不了这个权限）*/
private fun Context.openAllFilesAccessSettings() {
    val intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
        .onFailure {
            // 少数 ROM 没有"指定应用"那个 Action：退回"所有文件访问"列表页
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
}
