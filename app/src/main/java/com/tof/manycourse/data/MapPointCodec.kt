package com.tof.manycourse.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * 点位文件的**内容**：采到的点位 + 标定锚点。
 *
 * 放在同一个文件里，是因为它们**必须一起走**：锚点单独丢了，点位还能在图上看（图上采的），
 * 但"我在这儿"就废了；点位单独丢了，锚点还在，标定不用重做。分开存只会让两边版本对不上。
 */
internal data class MapPointFile(
    val points: List<MapPoint> = emptyList(),
    val anchors: List<MapAnchor> = emptyList(),
)

/**
 * [MapPointFile] 的**行式**文本编解码 —— 纯逻辑，可 JVM 单测。
 *
 * 格式沿用项目里 `SessionBlobCodec` / `ScheduleSnapshotCodec` 那一套：
 * **带版本号的头部 + 一行一条 + 文本字段统一 URL-safe Base64**。
 *
 * ```
 * manycourse-mappoints/1
 * p=<id>|<校区id>|<名称>|<纬度>|<经度>|<精度>|<图上x>|<图上y>|<备注>|<时间戳>
 * a=<校区id>|<名称>|<图上x>|<图上y>|<纬度>|<经度>
 * ```
 *
 * 两类细节：
 *  - **数字留空 = 没有**（图上采的点没有经纬度、原地采的点标定前没有图上位置），
 *    空串走 `toDoubleOrNull()` 自然是 null，不需要额外标记；
 *  - **文本字段一律 Base64**（点位名里有中文、备注里可能有 `|`），否则分隔符会被撑破。
 *
 * 坏行只丢那一行，不整份作废 —— 少一个点位远好过整个文件读不出来，
 * 这也是 `CookieCodec` 立下的规矩。头部不对则整份丢弃（那是别人的/旧版本的文件）。
 */
internal object MapPointCodec {

    const val HEADER = "manycourse-mappoints/1"

    private const val KEY_POINT = "p"
    private const val KEY_ANCHOR = "a"

    /** 点位一行几个字段，写死在这里，[decodePoint] 按同样顺序读 */
    private const val POINT_FIELDS = 10
    private const val ANCHOR_FIELDS = 6

    fun encode(file: MapPointFile): String = buildString {
        append(HEADER).append('\n')
        file.points.forEach { point ->
            append(KEY_POINT).append('=').append(
                listOf(
                    b64(point.id), b64(point.campusId), b64(point.name),
                    point.latitude?.toString().orEmpty(),
                    point.longitude?.toString().orEmpty(),
                    point.accuracyMeters?.toString().orEmpty(),
                    point.imageX?.toString().orEmpty(),
                    point.imageY?.toString().orEmpty(),
                    b64(point.note),
                    point.createdAt.toString(),
                ).joinToString("|")
            ).append('\n')
        }
        file.anchors.forEach { anchor ->
            append(KEY_ANCHOR).append('=').append(
                listOf(
                    b64(anchor.campusId), b64(anchor.name),
                    anchor.imageX.toString(), anchor.imageY.toString(),
                    anchor.latitude.toString(), anchor.longitude.toString(),
                ).joinToString("|")
            ).append('\n')
        }
    }

    /** 解不出来（头部不对）返回 null；单行坏掉只跳过那一行 */
    fun decode(text: String): MapPointFile? {
        if (!hasOurHeader(text)) return null
        val points = mutableListOf<MapPoint>()
        val anchors = mutableListOf<MapAnchor>()
        text.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val value = line.substring(separator + 1)
            when (line.substring(0, separator)) {
                KEY_POINT -> decodePoint(value)?.let(points::add)
                KEY_ANCHOR -> decodeAnchor(value)?.let(anchors::add)
            }
        }
        return MapPointFile(points = points, anchors = anchors)
    }

    /** 头部是不是这份格式（用来区分"我们的文件"和"别人的/旧版本的"）*/
    fun hasOurHeader(text: String): Boolean = text.startsWith(HEADER)

    private fun decodePoint(value: String): MapPoint? {
        val parts = value.split('|')
        if (parts.size != POINT_FIELDS) return null
        val id = unB64(parts[0]) ?: return null
        val campusId = unB64(parts[1]) ?: return null
        val name = unB64(parts[2]) ?: return null
        // id / 校区 / 名称缺一个，这个点位就没法用（既删不掉、也不知道该画在哪个校区）
        if (id.isEmpty() || campusId.isEmpty() || name.isEmpty()) return null
        return MapPoint(
            id = id,
            campusId = campusId,
            name = name,
            latitude = parts[3].toDoubleOrNull(),
            longitude = parts[4].toDoubleOrNull(),
            accuracyMeters = parts[5].toFloatOrNull(),
            imageX = parts[6].toFloatOrNull(),
            imageY = parts[7].toFloatOrNull(),
            note = unB64(parts[8]).orEmpty(),
            createdAt = parts[9].toLongOrNull() ?: 0L,
        )
    }

    private fun decodeAnchor(value: String): MapAnchor? {
        val parts = value.split('|')
        if (parts.size != ANCHOR_FIELDS) return null
        val campusId = unB64(parts[0]) ?: return null
        val name = unB64(parts[1]) ?: return null
        val imageX = parts[2].toFloatOrNull() ?: return null
        val imageY = parts[3].toFloatOrNull() ?: return null
        val latitude = parts[4].toDoubleOrNull() ?: return null
        val longitude = parts[5].toDoubleOrNull() ?: return null
        if (campusId.isEmpty()) return null
        return MapAnchor(campusId, name, imageX, imageY, latitude, longitude)
    }

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unB64(value: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
}

/** 人类可读的时间（导出 CSV 用）：`2026-09-26 14:30:00` */
private val CSV_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * 点位 → **CSV**（给人和给别的工具看的那个文件）。
 *
 * 为什么要额外导一份 CSV（应用自己读的是 [MapPointCodec] 的文本格式）：
 * 采完点是拿去**改代码**的（把好用的点位固化进 `SchoolMap.kt` / 点位表），
 * 而改代码时用 Excel / 编辑器打开 CSV 比看那一行行 Base64 现实得多。
 *
 * @param zone 时间列用哪个时区格式化（可注入 → 可单测）
 */
internal fun pointsToCsv(points: List<MapPoint>, zone: ZoneId = ZoneId.systemDefault()): String {
    val header = "campus_id,name,latitude,longitude,accuracy_m,image_x,image_y,note,collected_at"
    val rows = points.map { point ->
        listOf(
            csvField(point.campusId),
            csvField(point.name),
            point.latitude?.toString().orEmpty(),
            point.longitude?.toString().orEmpty(),
            point.accuracyMeters?.toString().orEmpty(),
            point.imageX?.toString().orEmpty(),
            point.imageY?.toString().orEmpty(),
            csvField(point.note),
            formatCsvTime(point.createdAt, zone),
        ).joinToString(",")
    }
    return (listOf(header) + rows).joinToString("\n") + "\n"
}

/** 标定锚点 → CSV（和 [pointsToCsv] 同一个理由）*/
internal fun anchorsToCsv(anchors: List<MapAnchor>): String {
    val header = "campus_id,name,image_x,image_y,latitude,longitude"
    val rows = anchors.map { anchor ->
        listOf(
            csvField(anchor.campusId),
            csvField(anchor.name),
            anchor.imageX.toString(),
            anchor.imageY.toString(),
            anchor.latitude.toString(),
            anchor.longitude.toString(),
        ).joinToString(",")
    }
    return (listOf(header) + rows).joinToString("\n") + "\n"
}

/** 时间戳 → CSV 里的可读时间；0 或负数（老数据没记时间）留空 */
private fun formatCsvTime(millis: Long, zone: ZoneId): String =
    if (millis <= 0L) "" else CSV_TIME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(millis))

/**
 * CSV 字段转义：含逗号 / 引号 / 换行时用双引号包起来，内部的引号翻倍。
 *
 * 点位名和备注都是用户自己敲的，敲进一个逗号就会把列错开 ——
 * 那时候 Excel 打开是一片错位，而不是报错，最难查。
 */
private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
