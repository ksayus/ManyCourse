package com.tof.manycourse.data

import java.util.Base64
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 一条 **Wi-Fi 指纹**：某个位置（图上归一化坐标）上扫到的一组 AP 强度。
 *
 * ## 它是干什么用的
 *
 * 补上室内定位的缺口：室内没有 GPS，而"我在这儿"总得有个来源。
 * 指纹方案不需要任何硬件（复用学校已有的 AP）：走一趟把"(位置 → 那儿的信号长什么样)"
 * 存下来，之后在同一个地方扫描，就能反查出"我在哪一片"。
 *
 * ## 精度要说实话
 *
 * 3~8 米是**论文里**的数字（同一楼层、AP 密集、指纹点也密）。本应用的现实约束更硬：
 * Android 前台 `startScan()` 被节流到 **2 分钟 4 次**，一分钟走 80 米的话
 * 指纹点之间就隔着 20 米上下 —— 所以它给的是**楼栋/楼层级（区域级）**，不是教室门口级。
 * 界面上按"区域级"来写，不吹。
 *
 * @param aps bssid → rssi（dBm）
 * @param at 采集时刻（用来在走一趟时把指纹插到轨迹的哪一段上）
 */
data class WifiFingerprint(
    val campusId: String,
    val imageX: Float,
    val imageY: Float,
    val aps: Map<String, Int>,
    val at: Long = 0L,
)

/**
 * 指纹定位的结论。
 *
 * @param matched 参与投票的指纹条数（越多越可信）
 * @param spread 邻居在**图上**的离散度（归一化单位）；乘标定的 `scale` 倒数才是米。
 *   离散度大 = 几个候选位置互相离得远 = 这次定位不太可信
 */
data class WifiFix(
    val imageX: Float,
    val imageY: Float,
    val matched: Int,
    val spread: Float,
)

/**
 * [WifiFingerprint] 的文本编解码 —— 纯逻辑，可 JVM 单测。
 *
 * ```
 * manycourse-wifi/1
 * s=<校区id>|<图上x>|<图上y>|<时刻>|<ap 条数>
 * a=<bssid>|<rssi>
 * a=…
 * s=…
 * ```
 *
 * 一个指纹 = 一行 `s=` 后面跟着它的若干行 `a=`（直到下一个 `s=`）。
 * 和 `WalkLogCodec` 里"一次扫描 = 连续若干行 `w=`"是同一个思路：
 * 文件保持纯行式，任何一行坏了只丢那一行。
 */
object WifiFingerprintCodec {

    const val HEADER = "manycourse-wifi/1"

    private const val KEY_SAMPLE = "s"
    private const val KEY_AP = "a"

    fun encode(fingerprints: List<WifiFingerprint>): String = buildString {
        append(HEADER).append('\n')
        fingerprints.forEach { sample ->
            append(KEY_SAMPLE).append('=')
                .append(b64(sample.campusId)).append('|')
                .append(sample.imageX).append('|')
                .append(sample.imageY).append('|')
                .append(sample.at).append('|')
                .append(sample.aps.size).append('\n')
            sample.aps.forEach { (bssid, rssi) ->
                append(KEY_AP).append('=').append(bssid).append('|').append(rssi).append('\n')
            }
        }
    }

    /** 头部不对返回 null；单条指纹坏掉（校区读不出来 / 一个 AP 都没有）就跳过它 */
    fun decode(text: String): List<WifiFingerprint>? {
        if (!hasOurHeader(text)) return null
        val result = mutableListOf<WifiFingerprint>()
        var campusId: String? = null
        var imageX = 0f
        var imageY = 0f
        var at = 0L
        var aps = mutableMapOf<String, Int>()

        fun flush() {
            val campus = campusId
            if (campus != null && aps.isNotEmpty()) {
                result += WifiFingerprint(campus, imageX, imageY, aps.toMap(), at)
            }
            campusId = null
            aps = mutableMapOf()
        }

        text.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val value = line.substring(separator + 1)
            val parts = value.split('|')
            when (line.substring(0, separator)) {
                KEY_SAMPLE -> {
                    flush()
                    if (parts.size < 5) return@forEach
                    val campus = unB64(parts[0]) ?: return@forEach
                    val x = parts[1].toFloatOrNull() ?: return@forEach
                    val y = parts[2].toFloatOrNull() ?: return@forEach
                    if (campus.isBlank() || !isInsideImage(x, y)) return@forEach
                    campusId = campus
                    imageX = x
                    imageY = y
                    at = parts[3].toLongOrNull() ?: 0L
                }

                KEY_AP -> {
                    if (parts.size < 2) return@forEach
                    val bssid = parts[0]
                    val rssi = parts[1].toIntOrNull() ?: return@forEach
                    if (bssid.isNotBlank()) aps[bssid] = rssi
                }
            }
        }
        flush()
        return result
    }

    fun hasOurHeader(text: String): Boolean = text.startsWith(HEADER)

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unB64(value: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
}

/**
 * 没扫到的 AP 按这个强度参与距离计算（dBm）。
 *
 * 为什么不"只比共同扫到的 AP"：那样"对方有几个强 AP 而我这一个都没扫到"就完全不扣分，
 * 会把"在楼东头"判成"在楼西头"。——100 dBm 大约就是"收不到"的水平，用它当缺失值，
 * 距离的含义仍然统一在 dB 上。
 */
private const val MISSING_AP_RSSI = -100

/** 至少要**共同**扫到几个 AP，才认这条指纹是候选（一个 AP 分不出楼层）*/
private const val MIN_SHARED_APS = 2

/**
 * 扫描结果与一条指纹的**距离**（dB，越小越像）。
 *
 * @return null = 共同扫到的 AP 少于 [MIN_SHARED_APS] 个，这条指纹不参与（不是"距离无穷大"，
 *   而是"没有可比性"——把不可比当成不可信，会把完全不同的位置判成最像）
 */
internal fun rssiDistance(scan: Map<String, Int>, sample: Map<String, Int>): Double? {
    val shared = scan.keys.count { it in sample }
    if (shared < MIN_SHARED_APS) return null

    val keys = scan.keys + sample.keys
    var sum = 0.0
    keys.forEach { key ->
        val a = scan[key] ?: MISSING_AP_RSSI
        val b = sample[key] ?: MISSING_AP_RSSI
        val delta = (a - b).toDouble()
        sum += delta * delta
    }
    return sqrt(sum / keys.size)
}

/**
 * ★ **Wi-Fi 指纹定位**（kNN）—— 纯函数，所以能单测。
 *
 * 取距离最近的 [k] 条指纹，按 `1/(d+1)` 加权平均它们的位置。加权而不是取最近那一张：
 * 单张指纹对"人体朝向/手机握法造成的 5~10 dB 抖动"毫无抵抗力，平均几张能压掉一部分。
 *
 * @return null = 一条可比的指纹都没有（库里没数据 / 共同 AP 太少）—— 页面据此**如实说"定不了位"**，
 *   而不是给一个随机位置
 */
internal fun locateByFingerprint(
    scan: Map<String, Int>,
    samples: List<WifiFingerprint>,
    k: Int = 3,
): WifiFix? {
    if (scan.isEmpty() || samples.isEmpty()) return null

    val scored = samples.mapNotNull { sample ->
        rssiDistance(scan, sample.aps)?.let { distance -> sample to distance }
    }
    if (scored.isEmpty()) return null

    val best = scored.sortedBy { (_, distance) -> distance }.take(k.coerceAtLeast(1))
    var weightSum = 0.0
    var x = 0.0
    var y = 0.0
    best.forEach { (sample, distance) ->
        val weight = 1.0 / (distance + 1.0)
        weightSum += weight
        x += sample.imageX * weight
        y += sample.imageY * weight
    }
    if (weightSum <= 0.0) return null

    val centerX = (x / weightSum).toFloat()
    val centerY = (y / weightSum).toFloat()
    var spread = 0.0
    best.forEach { (sample, _) ->
        spread += hypot((sample.imageX - centerX).toDouble(), (sample.imageY - centerY).toDouble())
    }
    return WifiFix(centerX, centerY, best.size, (spread / best.size).toFloat())
}
