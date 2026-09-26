package com.tof.manycourse.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import java.io.File

/** 一趟走完之后给用户看的结论（不打折扣，走了多少、成了几个、哪几个没成）*/
data class WalkSummary(
    /** 原始记录存到哪了（null = 没存成，面板会如实说）*/
    val savedPath: String?,
    val taps: Int,
    /** 落成点位的打点 */
    val placed: Int,
    /** 点位库新增 / 覆盖 / 因为"已有的更准"而保留 */
    val added: Int,
    val updated: Int,
    val kept: Int,
    /** 打了点但给不出位置的名字（要去图上点一下）*/
    val unplaced: List<String>,
    val fingerprints: Int,
    val walkedMeters: Double,
)

/**
 * **轨迹采集**：走几圈，把"点位 + Wi-Fi 指纹"自动采下来。
 *
 * ## 采什么、为什么
 *
 * | 传感器 | 采到的 | 用途 |
 * |---|---|---|
 * | `TYPE_STEP_COUNTER` | 步数（硬件级、极省电） | PDR 的位移长度 |
 * | `TYPE_ROTATION_VECTOR` | 朝向 | PDR 的方向 |
 * | `LocationManager` | GPS | 室外给绝对位置（PDR 的锚点） |
 * | `WifiManager` | 每次扫描的 AP 强度 | Wi-Fi 指纹库（室内定位） |
 * | **用户按的「打点」** | **名称** | ★ 整趟里唯一零误差的信息 |
 *
 * ## 三个必须说清楚的现实约束
 *
 * 1. **Wi-Fi 扫描被系统节流**：前台应用 2 分钟最多 4 次 `startScan()`。所以扫描间隔起步
 *    20 秒，被拒就**指数退避**（最多 2 分钟一次）—— 指纹点之间因此隔着十几二十米，
 *    这决定了 Wi-Fi 定位只能到**楼栋/区域级**。
 * 2. **计步传感器需要 `ACTIVITY_RECOGNITION` 权限**（API 29+），且不一定每台机器都有。
 *    拿不到就**如实说**"这台设备/这次授权没有步数，只能靠 GPS 锚定打点"。
 * 3. **室内 GPS 基本没有**：所以室内靠 PDR 从上一次已知位置推，误差**越走越大** ——
 *    走完的结论里带 `±X 米`，不藏。
 *
 * ## 线程
 *
 * 传感器回调与定位回调都在主线程（注册时指定主 Looper），所以状态读写不需要额外同步；
 * 私有档案与导出文件都是小文件，落盘同步做（见 [MapPointStore] 的说明）。
 */
object WalkRecorder {

    private const val TAG = "WalkRecorder"

    /** 朝向采样间隔：15 Hz 存下来一趟就是几万行，2 Hz 足够 PDR 用 */
    private const val HEADING_SAMPLE_INTERVAL_MS = 500L

    /** 自动落盘间隔（防"走了一小时 App 被杀"）*/
    private const val AUTOSAVE_INTERVAL_MS = 30_000L

    private const val WIFI_SCAN_INTERVAL_MS = 20_000L
    private const val WIFI_SCAN_MAX_INTERVAL_MS = 120_000L

    // ── 给 UI 的状态 ──────────────────────────────────────────────────────
    val recording = mutableStateOf(false)
    val startedAt = mutableStateOf(0L)
    val sampleCount = mutableStateOf(0)
    val tapCount = mutableStateOf(0)
    val wifiScanCount = mutableStateOf(0)
    val lastApCount = mutableStateOf(0)
    val fingerprintsSoFar = mutableStateOf(0)
    val stepCount = mutableStateOf(0L)
    val headingDegrees = mutableStateOf<Float?>(null)
    val hasFix = mutableStateOf(false)
    val status = mutableStateOf<String?>(null)
    val lastSavedPath = mutableStateOf<String?>(null)
    val stepSensorAvailable = mutableStateOf(true)
    val gpsEnabled = mutableStateOf(true)

    // ── 内部状态 ──────────────────────────────────────────────────────────
    private var appContext: Context? = null
    private var schoolId: String? = null
    private var campusId: String? = null
    private val samples = mutableListOf<WalkSample>()
    private val handler = Handler(Looper.getMainLooper())
    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var wifiManager: WifiManager? = null
    private var receiverRegistered = false
    private var lastHeadingSampleAt = 0L
    private var lastAutosaveAt = 0L
    private var wifiScanIntervalMs = WIFI_SCAN_INTERVAL_MS
    private var stepsAtStart: Long? = null

    /** 这一趟用的位置起点（取到的第一个新鲜定位）—— 逐点推算由 `WalkFusion` 做 */
    private var walkingReference: Pair<Double, Double>? = null

    private fun now() = System.currentTimeMillis()

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 开始采集。
     *
     * @return false = 没开始（缺定位权限）；`status` 里写了原因
     */
    fun start(context: Context, schoolId: String?, campusId: String?): Boolean {
        if (recording.value) return false
        if (schoolId.isNullOrBlank() || campusId.isNullOrBlank()) {
            status.value = "还没有确定学校和校区，先在地图页选好校区"
            return false
        }
        // Wi-Fi 扫描结果与 GPS 都要定位权限（Android 10 起，扫描结果里的 BSSID 属于位置信息）
        val fine = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            status.value = "没有定位权限：Wi-Fi 扫描和 GPS 都拿不到（先在地图页授权）"
            return false
        }

        val app = context.applicationContext
        appContext = app
        this.schoolId = schoolId
        this.campusId = campusId
        samples.clear()
        startedAt.value = now()
        lastAutosaveAt = startedAt.value
        stepsAtStart = null
        stepCount.value = 0
        tapCount.value = 0
        wifiScanCount.value = 0
        sampleCount.value = 0
        lastApCount.value = 0
        fingerprintsSoFar.value = 0
        hasFix.value = false
        headingDegrees.value = null
        lastSavedPath.value = null
        walkingReference = null
        wifiScanIntervalMs = WIFI_SCAN_INTERVAL_MS
        status.value = null

        // 起点这一条先占位：真正的位置靠之后到达的 GPS（见 WalkSample.Origin 的注释）
        append(WalkSample.Origin(at = startedAt.value, latitude = null, longitude = null, accuracyMeters = null))

        startSensors(app)
        startLocation(app)
        startWifi(app)
        recording.value = true
        return true
    }

    /** 记一个打点（走路时按一下）*/
    fun tap(name: String, note: String = "") {
        if (!recording.value || name.isBlank()) return
        append(WalkSample.Tap(now(), name.trim(), note))
        tapCount.value = tapCount.value + 1
    }

    /**
     * 停止采集：写盘 + 融合 + 并入点位库与指纹库。
     *
     * @param calibration 当前校区的标定；null 时 GPS 也落不到图上（打点会进 `unplaced`）
     */
    fun stopAndMerge(context: Context, calibration: MapCalibration?): WalkSummary? {
        if (!recording.value) return null
        stopInternal(context)

        val log = snapshot()
        val fusion = fuseWalk(log, calibration, DEFAULT_STEP_LENGTH_METERS)
        val savedPath = saveToShared(context, log)

        var added = 0
        var updated = 0
        var kept = 0
        fusion.fixes.forEach { fix ->
            when (mergeFix(context, log.campusId, fix)) {
                MergeOutcome.Added -> added++
                MergeOutcome.Updated -> updated++
                MergeOutcome.KeptExisting -> kept++
            }
        }
        WifiFingerprintStore.addAll(context, fusion.fingerprints)

        return WalkSummary(
            savedPath = savedPath,
            taps = log.taps.size,
            placed = fusion.fixes.size,
            added = added,
            updated = updated,
            kept = kept,
            unplaced = fusion.unplaced,
            fingerprints = fusion.fingerprints.size,
            walkedMeters = fusion.walkedMeters,
        )
    }

    /** 丢下这一趟（不融合、不写盘点）—— 走错了从头来 */
    fun discard() {
        val context = appContext
        if (recording.value) stopInternal(context)
        samples.clear()
        status.value = "已丢弃这一趟"
    }

    // ── 传感器 ────────────────────────────────────────────────────────────

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    val total = event.values.firstOrNull()?.toLong() ?: return
                    if (stepsAtStart == null) stepsAtStart = total
                    stepCount.value = (total - (stepsAtStart ?: total)).coerceAtLeast(0L)
                    append(WalkSample.Step(now(), total))
                }

                Sensor.TYPE_ROTATION_VECTOR -> {
                    val degrees = azimuthOf(event.values) ?: return
                    headingDegrees.value = degrees
                    // 按 2 Hz 存：朝向本身是 15 Hz 级别的传感器，全存下来文件会大得没意义
                    val at = now()
                    if (at - lastHeadingSampleAt >= HEADING_SAMPLE_INTERVAL_MS) {
                        lastHeadingSampleAt = at
                        append(WalkSample.Heading(at, degrees))
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** 旋转矢量 → 方位角（度，正北 0、顺时针增大）*/
    private fun azimuthOf(rotationVector: FloatArray): Float? {
        if (rotationVector.size < 3) return null
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        return runCatching {
            SensorManager.getRotationMatrixFromVector(matrix, rotationVector)
            SensorManager.getOrientation(matrix, orientation)
            ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
        }.getOrNull()
    }

    private fun startSensors(context: Context) {
        val manager = context.getSystemService(SensorManager::class.java)
        sensorManager = manager
        if (manager == null) {
            stepSensorAvailable.value = false
            status.value = "这台设备没有传感器服务：只能靠 GPS 锚定打点"
            return
        }

        val stepSensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val canReadSteps = stepSensor != null &&
            hasPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
        stepSensorAvailable.value = canReadSteps
        if (stepSensor == null) {
            status.value = "这台设备没有计步传感器：室内打点只能靠 GPS（进楼前先在门口打一个点）"
        } else if (!canReadSteps) {
            status.value = "没有「身体活动」权限，拿不到步数：室内打点只能靠 GPS"
        } else {
            manager.registerListener(sensorListener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            manager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    // ── 定位 ──────────────────────────────────────────────────────────────

    private val locationListener = LocationListener { location: Location ->
        val accuracy = location.accuracy.takeIf { it > 0f }
        if (walkingReference == null) {
            walkingReference = location.latitude to location.longitude
            hasFix.value = true
        }
        append(WalkSample.Gps(now(), location.latitude, location.longitude, accuracy))
    }

    /**
     * 订阅定位更新。
     *
     * ★ `@SuppressLint("MissingPermission")` 的理由：**权限在 [start] 里已经查过**
     * （粗略或精确定位任一即可，两者都没有时 `start` 直接返回 false，根本走不到这里），
     * lint 看不穿"跨方法的检查"，所以在这里抑制 —— 而且这里的调用还额外包了
     * `runCatching`，权限被中途撤销也只会记一条日志，不会崩。
     */
    @SuppressLint("MissingPermission")
    private fun startLocation(context: Context) {
        val manager = context.getSystemService(LocationManager::class.java)
        locationManager = manager
        if (manager == null) {
            gpsEnabled.value = false
            return
        }
        val enabled = runCatching { manager.isLocationEnabled }.getOrDefault(false)
        gpsEnabled.value = enabled
        if (!enabled) {
            status.value = "系统定位开关关着：GPS 拿不到，室内的打点只能靠 PDR 推算"
            return
        }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, 3_000L, 0f, locationListener, Looper.getMainLooper())
            }.onFailure { Log.w(TAG, "请求 $provider 失败：${it.message}") }
        }
    }

    // ── Wi-Fi ─────────────────────────────────────────────────────────────

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            readScanResults()
        }
    }

    private val scanTick = object : Runnable {
        override fun run() {
            if (!recording.value) return
            val manager = wifiManager
            if (manager == null || !manager.isWifiEnabled) {
                status.value = "Wi-Fi 关着：扫不到 AP，指纹库采不到数据"
                handler.postDelayed(this, WIFI_SCAN_MAX_INTERVAL_MS)
                return
            }
            val started = requestWifiScan(manager)
            if (!started) {
                // 被节流（前台 2 分钟最多 4 次）或瞬时不可用：退避，别原地死循环地请求
                wifiScanIntervalMs = (wifiScanIntervalMs * 2).coerceAtMost(WIFI_SCAN_MAX_INTERVAL_MS)
                status.value = "Wi-Fi 扫描被系统节流，已退避到每 ${wifiScanIntervalMs / 1000} 秒一次"
            } else if (wifiScanIntervalMs > WIFI_SCAN_INTERVAL_MS) {
                wifiScanIntervalMs = WIFI_SCAN_INTERVAL_MS
            }
            handler.postDelayed(this, wifiScanIntervalMs)
        }
    }

    private fun startWifi(context: Context) {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java)
        wifiManager = manager
        if (manager == null) {
            status.value = "这台设备没有 Wi-Fi 服务"
            return
        }
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                scanReceiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }.onFailure {
            Log.w(TAG, "注册 Wi-Fi 扫描广播失败：${it.message}")
        }
        handler.post(scanTick)
    }

    /** 读一次扫描结果并记成一条样本（理由同 [startLocation]：权限在 [start] 里查过）*/
    @SuppressLint("MissingPermission")
    private fun readScanResults() {
        val manager = wifiManager ?: return
        val results = runCatching { manager.scanResults }.getOrNull().orEmpty()
        if (results.isEmpty()) return
        // 同一个 BSSID 只留最强的那条（一次扫描里偶尔会有重复项）
        val aps = results
            .mapNotNull { result -> result.BSSID?.let { bssid -> bssid to result } }
            .groupBy({ it.first }, { it.second })
            .map { (bssid, group) ->
                val best = group.maxByOrNull { it.level } ?: group.first()
                WifiAp(
                    bssid = bssid,
                    rssi = best.level,
                    frequencyMhz = best.frequency,
                )
            }
        if (aps.isEmpty()) return
        wifiScanCount.value = wifiScanCount.value + 1
        lastApCount.value = aps.size
        append(WalkSample.Wifi(now(), aps))
    }

    // ── 记录与落盘 ────────────────────────────────────────────────────────

    private fun append(sample: WalkSample) {
        samples += sample
        sampleCount.value = samples.size
        val context = appContext ?: return
        val at = now()
        if (at - lastAutosaveAt >= AUTOSAVE_INTERVAL_MS) {
            lastAutosaveAt = at
            saveToShared(context, snapshot())
        }
    }

    private fun snapshot(): WalkLog = WalkLog(
        schoolId = schoolId.orEmpty(),
        campusId = campusId.orEmpty(),
        startedAt = startedAt.value,
        samples = samples.toList(),
    )

    /** 写到系统根目录下的 `ManyCourse/walk-<开始时刻>.txt`；返回实际路径（失败为 null）*/
    private fun saveToShared(context: Context, log: WalkLog): String? {
        val directory = MapPointStore.sharedDirectory(context)
        val file = File(directory, "walk-${log.startedAt}.txt")
        val error = runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) return@runCatching "建不了目录"
            file.writeText(WalkLogCodec.encode(log))
            null
        }.getOrElse { it.message ?: it.javaClass.simpleName }

        return if (error == null) {
            lastSavedPath.value = file.absolutePath
            file.absolutePath
        } else {
            status.value = "轨迹记录写盘失败：$error"
            lastSavedPath.value = null
            null
        }
    }

    private fun stopInternal(context: Context?) {
        recording.value = false
        sensorManager?.unregisterListener(sensorListener)
        sensorManager = null
        locationManager?.let { manager ->
            runCatching { manager.removeUpdates(locationListener) }
        }
        locationManager = null
        handler.removeCallbacks(scanTick)
        if (receiverRegistered && context != null) {
            runCatching { context.applicationContext.unregisterReceiver(scanReceiver) }
            receiverRegistered = false
        }
        wifiManager = null
    }

    // ── 融合结果并入库 ────────────────────────────────────────────────────

    private enum class MergeOutcome { Added, Updated, KeptExisting }

    /**
     * 把一个打点结果并入点位库。
     *
     * 覆盖策略（**不能让"走出来的粗位置"盖掉"站着采的准位置"**）：
     *  - 库里没有同名点位 → 新增；
     *  - 新来的带 GPS、旧的没有 → 覆盖；
     *  - 新来的靠 PDR 推、旧的带 GPS → **保留旧的**（并在结论里说"已有点位更准"）；
     *  - 其它情况（都是 PDR / 都是图上点选）→ 以**最新一次**为准覆盖。
     */
    private fun mergeFix(context: Context, campusId: String, fix: WalkFix): MergeOutcome {
        val existing = MapPointStore.pointsOf(campusId)
            .firstOrNull { normalizePlace(it.name) == normalizePlace(fix.name) }

        val note = buildString {
            append("轨迹采集")
            append(if (fix.source == WalkFixSource.Gps) "（GPS 锚定 " else "（PDR 推算 ")
            append(formatWalkError(fix.errorMeters))
            append("）")
        }
        val incoming = MapPoint(
            id = "$campusId-${fix.at}",
            campusId = campusId,
            name = fix.name,
            latitude = fix.latitude,
            longitude = fix.longitude,
            imageX = fix.imageX,
            imageY = fix.imageY,
            note = note,
            createdAt = fix.at,
        )

        if (existing == null) {
            MapPointStore.addPoint(context, incoming)
            return MergeOutcome.Added
        }
        if (existing.hasGps && !incoming.hasGps) return MergeOutcome.KeptExisting

        MapPointStore.removePoint(context, existing.id)
        MapPointStore.addPoint(context, incoming.copy(id = existing.id))
        return MergeOutcome.Updated
    }
}
