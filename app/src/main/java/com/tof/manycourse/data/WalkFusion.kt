package com.tof.manycourse.data

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 打点的位置是**怎么来的** —— 决定页面上那句话怎么写，也决定可不可信 */
enum class WalkFixSource {
    /** GPS 锚定（室外采到的，或有起点定位 + 只走了一小段）*/
    Gps,

    /** PDR 推算（从上一个已知位置按步数 × 步长 × 朝向推的）—— 会累积漂移 */
    Pdr,
}

/**
 * 走一趟之后，一个打点最终落在图上的位置。
 *
 * @param errorMeters 位置误差估计（米）。**必须显示给用户**：
 *   GPS 段是系统给的精度；PDR 段按行走距离线性累积（见 [PDR_ERROR_RATIO]）。
 *   不显示的话，用户会把"推出来的位置"当成"测出来的位置"。
 */
data class WalkFix(
    val name: String,
    val imageX: Float,
    val imageY: Float,
    val source: WalkFixSource,
    val errorMeters: Double?,
    val at: Long,
    /**
     * 这一点的真实经纬度：只有 [source] 是 [WalkFixSource.Gps] 时才有（否则当前只靠 PDR 知道
     * "相对哪儿"，不知道绝对坐标）。
     *
     * 为什么要一起存下来：以后重新标定这个校区时，**带 GPS 的点位能自动跟着重算**
     * （投影是按当前标定现算的），而只有图上坐标的点不会动。
     */
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** [fuseWalk] 的结论 */
data class WalkFusion(
    /** 能落到图上的打点 */
    val fixes: List<WalkFix>,
    /** 打了点但**给不出位置**的名字（没有标定 / 整趟没拿到过定位）—— 要去图上点一下 */
    val unplaced: List<String>,
    /** 沿途采到的 Wi-Fi 指纹（已经带上位置）*/
    val fingerprints: List<WifiFingerprint>,
    /** 这一趟走了多少米（步数 × 步长）*/
    val walkedMeters: Double,
    /** 这个校区有没有标定；没有的话 GPS 也落不到图上 */
    val calibrated: Boolean,
)

/**
 * PDR 的**相对误差**：走过的距离每 1 米，位置误差增加多少米。
 *
 * 0.15（15%）是室内 PDR 的常见量级（步长估不准 + 陀螺漂移 + 转弯处多算）。
 * 这个数**直接出现在界面上的"误差 ±X 米"里**，所以它宁可偏大也不能偏小 ——
 * 偏小会让用户以为"走得越久越准"，而事实相反。
 */
internal const val PDR_ERROR_RATIO = 0.15

/**
 * 默认步长（米）。
 *
 * TODO(数据)：这是**每个人自己填的数** —— 身高腿长不同，步长 0.6~0.8 米都正常。
 * 想更准的话：走 20 步量一下实际距离（比如走廊上两块地砖的距离 × 步数），
 * 用 距离 ÷ 步数 得到自己的步长，改这个常量（或以后做成设置项）。
 */
internal const val DEFAULT_STEP_LENGTH_METERS = 0.7

/** 走多远之内还算"GPS 锚定"（米）；超过就说明位置主要靠 PDR 推出来的 */
private const val GPS_TRUST_METERS = 25.0

/** 一条指纹至少要扫到几个 AP 才值得存（太少的指纹没有区分度）*/
private const val MIN_APS_PER_FINGERPRINT = 3

/**
 * ★ **把"走一趟"融合成"点位 + 指纹库"** —— 纯函数（时间轴、标定、步长都是入参），可单测。
 *
 * ```
 *   起点定位(GPS) ──投影到图上──▶ 已知位置
 *        │
 *        ├── 每一步：位置 += 步长 × 朝向（PDR），误差 += 走的米数 × 15%
 *        ├── 每次扫描：在当前推算位置上存一条 Wi-Fi 指纹
 *        └── 每次打点：把"当前位置 + 误差"落成一个点位（名称是人给的，零误差的那部分）
 * ```
 *
 * ## 为什么打点才是主力，PDR/GPS 只是"配位置"
 *
 * "这儿是 A101"是**人给的**，不用猜、不会漂；会漂的只有"我在图上的哪儿"。
 * 所以这一趟的产出是**打点 + 它们的估算位置 + 误差**，而不是"一条精确轨迹"。
 *
 * ## 位置从哪来（按可信度）
 *
 * 1. **GPS**（室外）：经标定投影到图上 —— 米级；
 * 2. **PDR**（室内）：从上一个已知位置按步数推 —— 走 100 米大概差 15 米；
 * 3. **都没有** → 打点进 [WalkFusion.unplaced]，页面让用户到图上点一下（复用「图上采点」）。
 *
 * @param calibration 该校区标定；null 时 GPS 也落不到图上（但指纹照样存，
 *   指纹只要"位置"这个抽象坐标，标定好之后再算就一致了）—— 见下面的说明
 * @param stepLengthMeters 步长（见 [DEFAULT_STEP_LENGTH_METERS]）
 */
internal fun fuseWalk(
    log: WalkLog,
    calibration: MapCalibration?,
    stepLengthMeters: Double = DEFAULT_STEP_LENGTH_METERS,
): WalkFusion {
    val fixes = mutableListOf<WalkFix>()
    val unplaced = mutableListOf<String>()
    val fingerprints = mutableListOf<WifiFingerprint>()

    var position: Pair<Float, Float>? = null
    var anchoredByGps = false
    var errorMeters: Double? = null
    var metersSinceGps = Double.MAX_VALUE
    var headingDegrees = 0f
    var lastStepTotal: Long? = null
    var walkedMeters = 0.0
    /** 最近一次真实定位 —— 打点时如果还算"GPS 锚定"，就连同经纬度一起记下来 */
    var lastGpsLatitude: Double? = null
    var lastGpsLongitude: Double? = null

    /** 在当前位置上落一条指纹（位置还没定就别存 —— 存了也不知道它属于哪儿）*/
    fun storeFingerprint(at: Long, aps: Map<String, Int>) {
        val here = position ?: return
        if (aps.size < MIN_APS_PER_FINGERPRINT) return
        fingerprints += WifiFingerprint(
            campusId = log.campusId,
            imageX = here.first,
            imageY = here.second,
            aps = aps,
            at = at,
        )
    }

    log.samples.forEach { sample ->
        when (sample) {
            is WalkSample.Origin -> {
                val latitude = sample.latitude
                val longitude = sample.longitude
                if (calibration != null && latitude != null && longitude != null) {
                    val projected = projectToImage(latitude, longitude, calibration)
                    if (projected != null && isInsideImage(projected.first, projected.second)) {
                        position = projected
                        anchoredByGps = true
                        errorMeters = sample.accuracyMeters?.toDouble() ?: 15.0
                        metersSinceGps = 0.0
                        lastStepTotal = null
                        lastGpsLatitude = latitude
                        lastGpsLongitude = longitude
                    }
                }
            }

            is WalkSample.Gps -> {
                if (calibration != null) {
                    val projected = projectToImage(sample.latitude, sample.longitude, calibration)
                    if (projected != null && isInsideImage(projected.first, projected.second)) {
                        position = projected
                        anchoredByGps = true
                        errorMeters = sample.accuracyMeters?.toDouble() ?: 15.0
                        metersSinceGps = 0.0
                        lastGpsLatitude = sample.latitude
                        lastGpsLongitude = sample.longitude
                    }
                }
            }

            is WalkSample.Heading -> headingDegrees = sample.degrees

            is WalkSample.Step -> {
                val previous = lastStepTotal
                lastStepTotal = sample.totalSteps
                // 第一条只记基准：它的 totalSteps 是"开机以来的总数"，差值才有意义
                if (previous == null || sample.totalSteps <= previous) return@forEach
                val meters = (sample.totalSteps - previous) * stepLengthMeters
                walkedMeters += meters
                metersSinceGps += meters

                val here = position ?: return@forEach
                val current = calibration ?: return@forEach
                val next = advanceOnImage(
                    start = here,
                    meters = meters,
                    headingDegrees = headingDegrees,
                    calibration = current,
                )
                position = next
                // 误差随走过的距离线性累积：PDR 的错**不会自己变小**，只会越走越大
                errorMeters = (errorMeters ?: 0.0) + meters * PDR_ERROR_RATIO
            }

            is WalkSample.Wifi -> storeFingerprint(sample.at, sample.aps.associate { it.bssid to it.rssi })

            is WalkSample.Tap -> {
                val here = position
                if (here == null) {
                    unplaced += sample.name
                    return@forEach
                }
                val source = if (anchoredByGps && metersSinceGps <= GPS_TRUST_METERS) {
                    WalkFixSource.Gps
                } else {
                    WalkFixSource.Pdr
                }
                fixes += WalkFix(
                    name = sample.name,
                    imageX = here.first,
                    imageY = here.second,
                    source = source,
                    errorMeters = errorMeters,
                    at = sample.at,
                    latitude = if (source == WalkFixSource.Gps) lastGpsLatitude else null,
                    longitude = if (source == WalkFixSource.Gps) lastGpsLongitude else null,
                )
            }
        }
    }

    return WalkFusion(
        fixes = fixes,
        unplaced = unplaced,
        fingerprints = fingerprints,
        walkedMeters = walkedMeters,
        calibrated = calibration != null,
    )
}

/**
 * 从一个已知的图上位置，按"往某方向走了多少米"推到下一个图上位置。
 *
 * 数学上就是拿标定矩阵的线性部分做一次旋转 + 缩放：
 * ```
 *   du =  a·dEast − b·dNorth
 *   dv =  b·dEast + a·dNorth
 *   图上 dx = du,  dy = −dv        ← 别忘了 y 要翻回来（见 MapTransform 的注释）
 * ```
 * 直接在这里做，而不是"米 → 经纬度 → 再投影一次"：少一次近似，也少一次经纬度往返带来的误差。
 */
internal fun advanceOnImage(
    start: Pair<Float, Float>,
    meters: Double,
    headingDegrees: Float,
    calibration: MapCalibration,
): Pair<Float, Float> {
    val azimuth = Math.toRadians(headingDegrees.toDouble())
    val east = meters * sin(azimuth)
    val north = meters * cos(azimuth)

    val transform = calibration.transform
    val du = transform.a * east - transform.b * north
    val dv = transform.b * east + transform.a * north
    return (start.first + du).toFloat() to (start.second - dv).toFloat()
}

/** 走一步的距离文案（`12 m` / `1.4 km`），和 `formatDistanceMeters` 同一个口径但独立于 UI 层 */
internal fun formatWalkDistance(meters: Double): String =
    if (meters < 1_000) "${meters.toInt()} m" else String.format(java.util.Locale.US, "%.1f km", meters / 1000)

/** 误差文案：`±8 m`；没有估计值时如实说"未知" */
internal fun formatWalkError(errorMeters: Double?): String =
    errorMeters?.let { "±${it.toInt()} m" } ?: "误差未知"

/** 两点在图上的距离（归一化单位）—— 用来判断"两个打点是不是几乎在同一处" */
internal fun imageDistance(a: Pair<Float, Float>, b: Pair<Float, Float>): Double =
    hypot((a.first - b.first).toDouble(), (a.second - b.second).toDouble())
