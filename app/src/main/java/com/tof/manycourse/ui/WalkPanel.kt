package com.tof.manycourse.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.MapPointStore
import com.tof.manycourse.data.SchoolCampus
import com.tof.manycourse.data.WalkRecorder
import com.tof.manycourse.data.WalkSummary
import com.tof.manycourse.data.formatWalkDistance
import com.tof.manycourse.ui.components.CampusButton
import com.tof.manycourse.ui.components.CampusChip
import com.tof.manycourse.ui.theme.LocalGlassTokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * **轨迹采集面板**（走几圈就把点位和 Wi-Fi 指纹采下来）。
 *
 * ## 走的时候只有两个动作
 *
 * ```
 *   开始采集 ──▶ 边走边按「打点」（弹名字输入，可以从"课表里还没点位的教室"里点选）
 *              └─▶ 走完按「停止并入库」→ 自动融合成点位 + 指纹，并写进 /sdcard/ManyCourse/
 * ```
 *
 * 为什么是"打点"而不是"自动识别教室"：位置会漂，但"这儿是 A101"是**你说的**，不漂。
 * 所以整趟的产出 = 打点（人给的名字，零误差）+ 给它们配上的估算位置（会漂、带 ±米数）。
 *
 * ## 界面上必须说清楚的四件事
 *
 * 1. **没有步数传感器 / 没有「身体活动」权限** → 室内打点只能靠 GPS；
 * 2. **Wi-Fi 关着** → 采不到指纹（这时只有点位，没有区域定位能力）；
 * 3. **Wi-Fi 被节流** → 说明扫描频率已经退避（指纹点会稀）；
 * 4. **误差** → 每个点位带着 ±米数入库，不藏。
 *
 * @param campus 当前显示的校区；没有校区不能开始（点位与指纹都要落到具体校区）
 */
@Composable
internal fun WalkPanel(
    schoolId: String?,
    campus: SchoolCampus?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tokens = LocalGlassTokens.current
    val campusId = campus?.id

    val recording by WalkRecorder.recording
    var naming by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }

    // 每秒刷新一次"走了多久"：只有录制中才跑循环，停下来就退出
    LaunchedEffect(recording) {
        while (recording) {
            tick = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) WalkRecorder.status.value = "没有「身体活动」权限：室内打点只能靠 GPS"
        startWalk(context, schoolId, campusId)
    }

    /** 开始采集：先确认"身体活动"权限（计步传感器需要），再启动 */
    fun begin() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACTIVITY_RECOGNITION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            startWalk(context, schoolId, campusId)
        } else {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        if (!recording) {
            Text(
                text = "轨迹采集：走几圈，把点位和 Wi-Fi 指纹采下来",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "怎么走：\n" +
                    "1. 先在**门口或楼外**按「开始采集」——要在这儿拿到一次 GPS，后面的打点才有参照；\n" +
                    "2. 进楼沿走廊走，路过一间教室就按一次「打点」，名字从课表待采集清单里点选；\n" +
                    "3. 走完按「停止并入库」：点位直接进库，Wi-Fi 指纹也一并存下。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            CampusButton(
                text = if (campus == null) "先选一个校区" else "开始采集",
                enabled = campus != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = { begin() },
            )

            MapDevSession.lastWalkSummary.value?.let { summary ->
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "上一趟的结果",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                WalkSummaryView(summary)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "关于精度（不粉饰）：Wi-Fi 扫描被系统节流（前台 2 分钟最多 4 次），" +
                    "所以指纹点之间隔着十几二十米 —— Wi-Fi 定位只能到**楼栋/区域级**；" +
                    "室内没有 GPS 时靠步数推算，误差随走的距离累积，每个点位都会带上 ±米数。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        // ── 采集中 ────────────────────────────────────────────────────────
        val elapsed = ((tick - WalkRecorder.startedAt.value) / 1000L).coerceAtLeast(0L)
        Text(
            text = "采集中 · %d:%02d".format(elapsed / 60, elapsed % 60),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = tokens.accent,
        )
        Spacer(Modifier.height(8.dp))

        // ★ 走路时按的按钮：做大、放最上面
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.accent)
                .clickable { draftName = ""; naming = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "打点：我在这儿（${WalkRecorder.tapCount.value}）",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }

        Spacer(Modifier.height(10.dp))
        StatusRow("步数", "${WalkRecorder.stepCount.value} 步（约 ${formatWalkDistance(WalkRecorder.stepCount.value * 0.7)}）")
        StatusRow("Wi-Fi 扫描", "${WalkRecorder.wifiScanCount.value} 次 · 最近一次 ${WalkRecorder.lastApCount.value} 个 AP")
        StatusRow("定位", if (WalkRecorder.hasFix.value) "已拿到 GPS（打点会用它锚定）" else "还没拿到 GPS（室内打点只能靠步数推算）")
        StatusRow("朝向", WalkRecorder.headingDegrees.value?.let { "${it.roundToInt()}°" } ?: "还没拿到")
        StatusRow("采样", "${WalkRecorder.sampleCount.value} 条")

        WalkRecorder.status.value?.let { status ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = status,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!WalkRecorder.stepSensorAvailable.value) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "⚠️ 拿不到步数：这一次的室内打点位置会不完整（进楼前在门口打一个点可以补救）",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(14.dp))
        CampusButton(
            text = "停止并入库",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                MapDevSession.lastWalkSummary.value =
                    WalkRecorder.stopAndMerge(context, MapPointStore.calibrationOf(campusId))
                MapDevSession.walkMode.value = false
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { WalkRecorder.discard() }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(text = "丢弃这一趟", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }
    }

    // ── 打点：名称输入 ────────────────────────────────────────────────────
    if (naming) {
        val suggestions = remember(campusId, MapPointStore.points.size) { uncollectedRooms(campusId) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("这里是哪儿", fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "名字照抄课表里的写法最省事（课表写「A1N403 禄口」，这里填「A1N403」就能对上）。",
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
                            text = "课表里还没有点位的教室（点一下就填上）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            suggestions.take(12).forEach { room ->
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
                        WalkRecorder.tap(draftName.trim())
                        naming = false
                    },
                ) { Text("记下") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("取消") } },
        )
    }
}

/** 开始采集的统一入口（权限分支最后都走到这里）*/
private fun startWalk(
    context: android.content.Context,
    schoolId: String?,
    campusId: String?,
) {
    val ok = WalkRecorder.start(context, schoolId, campusId)
    if (ok) MapDevSession.message.value = "开始采集：走到教室门口按「打点」"
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(text = value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 一趟走完的结论（每一项都如实写，包括"没成的那几个"）*/
@Composable
private fun WalkSummaryView(summary: WalkSummary) {
    val tokens = LocalGlassTokens.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "走 ${summary.placed} 个点位 · 新增 ${summary.added}" +
                (if (summary.updated > 0) " · 更新 ${summary.updated}" else "") +
                (if (summary.kept > 0) " · 保留原有 ${summary.kept}" else ""),
            fontSize = 13.sp,
            color = tokens.accent,
        )
        Text(
            text = "打点 ${summary.taps} 次 · 走了约 ${summary.walkedMeters.roundToInt()} 米 · 存下 ${summary.fingerprints} 条 Wi-Fi 指纹",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (summary.unplaced.isNotEmpty()) {
            Text(
                text = "${summary.unplaced.size} 个打点没有位置（${summary.unplaced.take(4).joinToString("、")}" +
                    (if (summary.unplaced.size > 4) " 等" else "") +
                    "）：去「图上采点」在图上点一下就行",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        summary.savedPath?.let {
            Text(
                text = "轨迹记录：$it",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
