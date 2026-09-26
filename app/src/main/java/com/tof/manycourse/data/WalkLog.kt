package com.tof.manycourse.data

import java.util.Base64

/**
 * 一次扫描里的一个 AP（Wi-Fi 热点）。
 *
 * @param bssid 热点 MAC。**指纹的唯一键**：SSID 会重名（`CMCC-EDU` 满校园都是），BSSID 才是"这一个 AP"
 * @param rssi 信号强度（dBm，负值，越大越强）。指纹定位用的就是它
 *
 * ★ **刻意不存 SSID**：
 *  - 它对指纹匹配没有测量价值（键是 BSSID）；
 *  - 读它已经废弃（`ScanResult.SSID` 在 API 33 被弃用），而且每次扫描每个 AP 都要重复存一遍，
 *    走半小时就是几千行的纯冗余。
 */
data class WifiAp(
    val bssid: String,
    val rssi: Int = 0,
    val frequencyMhz: Int = 0,
)

/**
 * 轨迹采集里的一条采样 —— **一趟走下来就是这些样本的序列**。
 *
 * 为什么是一个 sealed 序列而不是几张表：所有样本共享一个时间轴，
 * 融合时（`WalkFusion`）要按时间把它们**串成一条线**（"走到第 300 步时朝向多少、
 * 那一刻扫到了哪些 AP、有没有 GPS"），分成几张表就得每次归并排序一遍。
 */
sealed interface WalkSample {

    /** 采样时刻（毫秒）*/
    val at: Long

    /** 起点：开始采集时取的一次定位。**可以为 null**（室内常见），这时整趟只有相对轨迹 */
    data class Origin(
        override val at: Long,
        val latitude: Double?,
        val longitude: Double?,
        val accuracyMeters: Float?,
    ) : WalkSample

    /** 累计步数（`TYPE_STEP_COUNTER` 给的是开机以来的总数，这里**原样存**，差值才是这一趟走的）*/
    data class Step(override val at: Long, val totalSteps: Long) : WalkSample

    /** 朝向（度，正北为 0、顺时针增大）—— 来自 `TYPE_ROTATION_VECTOR` */
    data class Heading(override val at: Long, val degrees: Float) : WalkSample

    /** 定位点（室外才有）*/
    data class Gps(
        override val at: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
    ) : WalkSample

    /** 一次 Wi-Fi 扫描的结果（同一次扫描的多个 AP 共享同一个 [at]）*/
    data class Wifi(override val at: Long, val aps: List<WifiAp>) : WalkSample

    /**
     * **打点**：用户走过某个地方时按一下，并写下"这里是哪儿"。
     *
     * 它是整趟采集里**唯一零误差**的信息 —— 位置可能有漂移，但"这儿是 A101"是人给的，
     * 不用猜。所以走一趟的产出主要来自打点，Wi-Fi/GPS/PDR 都是给打点**配位置**的。
     */
    data class Tap(override val at: Long, val name: String, val note: String = "") : WalkSample
}

/**
 * 一趟轨迹采集的完整记录。
 *
 * @param schoolId / [campusId] 这趟属于谁 —— 点位与指纹只在同一个校区里有意义
 * @param startedAt 开始时刻（也用来给导出的文件起名）
 * @param samples 按时间升序的采样
 */
data class WalkLog(
    val schoolId: String,
    val campusId: String,
    val startedAt: Long,
    val samples: List<WalkSample>,
) {
    val taps: List<WalkSample.Tap> get() = samples.filterIsInstance<WalkSample.Tap>()
    val wifiScans: List<WalkSample.Wifi> get() = samples.filterIsInstance<WalkSample.Wifi>()

    /** 这一趟的步数（用最大减最小，而不是"最后一条" —— 中途重启计步器时不会算出负数）*/
    val steps: Long
        get() {
            val all = samples.filterIsInstance<WalkSample.Step>().map { it.totalSteps }
            return if (all.isEmpty()) 0L else all.max() - all.min()
        }
}

/**
 * [WalkLog] 的文本编解码 —— 纯逻辑，可 JVM 单测。
 *
 * 格式沿用项目既有的路子（带版本号头部 + 一行一条 + 文本字段 URL-safe Base64）：
 *
 * ```
 * manycourse-walk/1
 * h=<学校id>|<校区id>|<开始时刻>
 * o=<时刻>|<纬度>|<经度>|<精度>
 * s=<时刻>|<累计步数>
 * r=<时刻>|<朝向度>
 * g=<时刻>|<纬度>|<经度>|<精度>
 * t=<时刻>|<名称>|<备注>
 * w=<时刻>|<bssid>|<rssi>|<频率>
 * ```
 *
 * 一次 Wi-Fi 扫描 = **连续若干行 `w=`，时间戳相同**（解码时按"时间戳相同且相邻"归成一条）。
 * 这样文件是纯行的、可以流式追加，也不需要在 `w=` 里再塞一层嵌套编码。
 *
 * 坏行只丢那一行（少一个 AP 远好过整趟白走），头部不对则整份丢弃。
 */
object WalkLogCodec {

    const val HEADER = "manycourse-walk/1"

    private const val KEY_HEAD = "h"
    private const val KEY_ORIGIN = "o"
    private const val KEY_STEP = "s"
    private const val KEY_HEADING = "r"
    private const val KEY_GPS = "g"
    private const val KEY_TAP = "t"
    private const val KEY_WIFI = "w"

    fun encode(log: WalkLog): String = buildString {
        append(HEADER).append('\n')
        append(KEY_HEAD).append('=')
            .append(b64(log.schoolId)).append('|')
            .append(b64(log.campusId)).append('|')
            .append(log.startedAt).append('\n')

        log.samples.forEach { sample ->
            when (sample) {
                is WalkSample.Origin -> append(KEY_ORIGIN).append('=')
                    .append(sample.at).append('|')
                    .append(sample.latitude?.toString().orEmpty()).append('|')
                    .append(sample.longitude?.toString().orEmpty()).append('|')
                    .append(sample.accuracyMeters?.toString().orEmpty()).append('\n')

                is WalkSample.Step -> append(KEY_STEP).append('=')
                    .append(sample.at).append('|').append(sample.totalSteps).append('\n')

                is WalkSample.Heading -> append(KEY_HEADING).append('=')
                    .append(sample.at).append('|').append(sample.degrees).append('\n')

                is WalkSample.Gps -> append(KEY_GPS).append('=')
                    .append(sample.at).append('|')
                    .append(sample.latitude).append('|')
                    .append(sample.longitude).append('|')
                    .append(sample.accuracyMeters?.toString().orEmpty()).append('\n')

                is WalkSample.Tap -> append(KEY_TAP).append('=')
                    .append(sample.at).append('|')
                    .append(b64(sample.name)).append('|')
                    .append(b64(sample.note)).append('\n')

                is WalkSample.Wifi -> sample.aps.forEach { ap ->
                    append(KEY_WIFI).append('=')
                        .append(sample.at).append('|')
                        .append(ap.bssid).append('|')
                        .append(ap.rssi).append('|')
                        .append(ap.frequencyMhz).append('\n')
                }
            }
        }
    }

    /** 解不出来（头部不对 / 缺学校校区）返回 null；单行坏掉只跳过那一行 */
    fun decode(text: String): WalkLog? {
        if (!hasOurHeader(text)) return null

        var schoolId: String? = null
        var campusId: String? = null
        var startedAt = 0L
        val samples = mutableListOf<WalkSample>()
        // 正在归并的那一次 Wi-Fi 扫描
        var wifiAt = 0L
        var wifiAps = mutableListOf<WifiAp>()

        fun flushWifi() {
            if (wifiAps.isNotEmpty()) {
                samples += WalkSample.Wifi(wifiAt, wifiAps.toList())
                wifiAps = mutableListOf()
            }
        }

        text.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val value = line.substring(separator + 1)
            val parts = value.split('|')
            when (line.substring(0, separator)) {
                KEY_HEAD -> {
                    if (parts.size < 3) return@forEach
                    schoolId = unB64(parts[0])
                    campusId = unB64(parts[1])
                    startedAt = parts[2].toLongOrNull() ?: 0L
                }

                KEY_ORIGIN -> {
                    flushWifi()
                    val at = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
                    samples += WalkSample.Origin(
                        at = at,
                        latitude = parts.getOrNull(1)?.toDoubleOrNull(),
                        longitude = parts.getOrNull(2)?.toDoubleOrNull(),
                        accuracyMeters = parts.getOrNull(3)?.toFloatOrNull(),
                    )
                }

                KEY_STEP -> {
                    flushWifi()
                    val at = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
                    val total = parts.getOrNull(1)?.toLongOrNull() ?: return@forEach
                    samples += WalkSample.Step(at, total)
                }

                KEY_HEADING -> {
                    flushWifi()
                    val at = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
                    val degrees = parts.getOrNull(1)?.toFloatOrNull() ?: return@forEach
                    samples += WalkSample.Heading(at, degrees)
                }

                KEY_GPS -> {
                    flushWifi()
                    val at = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
                    val latitude = parts.getOrNull(1)?.toDoubleOrNull() ?: return@forEach
                    val longitude = parts.getOrNull(2)?.toDoubleOrNull() ?: return@forEach
                    samples += WalkSample.Gps(at, latitude, longitude, parts.getOrNull(3)?.toFloatOrNull())
                }

                KEY_TAP -> {
                    flushWifi()
                    val at = parts.getOrNull(0)?.toLongOrNull() ?: return@forEach
                    val name = unB64(parts.getOrNull(1).orEmpty())
                    if (name.isNullOrBlank()) return@forEach
                    samples += WalkSample.Tap(at, name, unB64(parts.getOrNull(2).orEmpty()).orEmpty())
                }

                KEY_WIFI -> {
                    if (parts.size < 4) return@forEach
                    val at = parts[0].toLongOrNull() ?: return@forEach
                    val bssid = parts[1]
                    if (bssid.isBlank()) return@forEach
                    // 时间戳变了 = 新的一次扫描：先把上一条收尾
                    if (wifiAps.isNotEmpty() && at != wifiAt) flushWifi()
                    wifiAt = at
                    wifiAps += WifiAp(
                        bssid = bssid,
                        rssi = parts[2].toIntOrNull() ?: 0,
                        frequencyMhz = parts[3].toIntOrNull() ?: 0,
                    )
                }
            }
        }
        flushWifi()

        if (schoolId.isNullOrBlank() || campusId.isNullOrBlank()) return null
        return WalkLog(
            schoolId = schoolId,
            campusId = campusId,
            startedAt = startedAt,
            samples = samples,
        )
    }

    fun hasOurHeader(text: String): Boolean = text.startsWith(HEADER)

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unB64(value: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
}
