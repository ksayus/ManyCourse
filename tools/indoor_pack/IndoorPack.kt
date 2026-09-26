package com.tof.manycourse.tools

import com.tof.manycourse.data.DEFAULT_STEP_LENGTH_METERS
import com.tof.manycourse.data.MapAnchor
import com.tof.manycourse.data.MapCalibration
import com.tof.manycourse.data.MapPoint
import com.tof.manycourse.data.MapPointCodec
import com.tof.manycourse.data.MapPointFile
import com.tof.manycourse.data.WalkFix
import com.tof.manycourse.data.WalkFixSource
import com.tof.manycourse.data.WalkFusion
import com.tof.manycourse.data.WalkLog
import com.tof.manycourse.data.WalkLogCodec
import com.tof.manycourse.data.WalkSample
import com.tof.manycourse.data.WifiFingerprint
import com.tof.manycourse.data.WifiFingerprintCodec
import com.tof.manycourse.data.buildCalibration
import com.tof.manycourse.data.calibrationResidualMeters
import com.tof.manycourse.data.fuseWalk
import com.tof.manycourse.data.normalizePlace
import com.tof.manycourse.data.projectToImage
import java.io.File
import kotlin.math.hypot
import kotlin.system.exitProcess

/**
 * ★ **离线把采集数据打成"随正式版下发的内置数据"** —— `assets/indoor/` 的那两份文件。
 *
 * ## 它补的是哪一段
 *
 * ```
 *   设备 ManyCourse/  ──adb pull──▶  point/
 *        │                              │
 *        │                              ├─ mappoints.txt / wifi.txt  ← 设备已经融合过的，直接并库
 *        │                              └─ walk-*.txt               ← ★ 原始轨迹，设备没融合进来
 *        │                                        │
 *        │                                        └── 本工具（调 App 自己的 fuseWalk）
 *        ▼
 *   app/src/main/assets/indoor/{mappoints.txt, wifi.txt}
 * ```
 *
 * **为什么需要它**：`WalkFusion.kt` 的注释写得很清楚 —— 轨迹要"先用 App 融合成点位/指纹再打包"，
 * 因为 `BundledIndoorData` **刻意不认** `manycourse-walk/1`（原始轨迹不是库里能用的数据）。
 * 而采集时如果没点「停止并融合」（或者中途 App 被杀），`walk-*.txt` 就会被拉到电脑上、
 * 却永远进不了库 —— 一份 131 KB、52 次扫描的轨迹就这么白走了。
 *
 * ## 为什么是 Kotlin 而不是在脚本里解析
 *
 * 和 `pack_walk_data.ps1` 立下的规矩一致：**解析与融合的逻辑只留在有单测的 Kotlin 里**
 * （`WalkLogCodec` / `fuseWalk` / `MapPointCodec` / `WifiFingerprintCodec`）。
 * 本文件不重新实现任何一步，只是把这些函数**在 JVM 上串起来跑一遍** ——
 * 所以它算出来的结果和 App 在手机上算的**是同一份代码**，不会出现"两边算法不一样"。
 *
 * 用到的这几个类都是**纯 JVM** 的（只依赖 `kotlin.math` 和 `java.util.Base64`），
 * 没有一个 `import android.` —— 这也是它能脱离 Android 跑起来的原因。
 *
 * ## 合并与去重（对齐 App 的口径）
 *
 * | 数据 | 去重键 | 依据 |
 * |---|---|---|
 * | 锚点 | `校区 + 名字` | `MapPointStore.removeAnchor` / `mergeBundled` 就是这么判同的 |
 * | 点位 | `校区 + 归一化名字` | `MapPointStore.key()`（`normalizePlace`，`A1N403` = `a1n403`）|
 * | 指纹 | `校区 + 采集时刻` | 一次扫描一个时刻；老数据没有时刻时退回"校区 + 图上坐标" |
 *
 * 另外还会**拆掉坐标撞车的锚点**（两个不同的地点拿到同一个经纬度 = 采的时候定位没刷新），
 * 留下的那个是"和其余锚点对得上"的那个 —— 见 [resolveContradictions]。
 * 不撞车的锚点一律不动：准不准是 GPS 的事，这里只管数据自相矛盾的部分。
 *
 * 用法：`IndoorPack <来源目录> <输出目录> [步长米]`
 */
fun main(args: Array<String>) {
    val sourceDir = File(args.getOrElse(0) { "point" })
    val destDir = File(args.getOrElse(1) { "app/src/main/assets/indoor" })
    val stepLength = args.getOrNull(2)?.toDoubleOrNull() ?: DEFAULT_STEP_LENGTH_METERS

    // 边跑边打报告。写成 `val` 而不是局部函数：局部函数没法当值传给下面那些
    // describe*/merge* 辅助函数（`::say` 也行，但 `val` 更直白）。
    val say: (String) -> Unit = { line -> println(line) }

    say("=== 读来源目录 ${sourceDir.path} ===")
    if (!sourceDir.isDirectory) {
        println("✗ 没有这个目录：${sourceDir.absolutePath}")
        println("  先把设备上的数据拉下来：adb pull /sdcard/ManyCourse point")
        exitProcess(1)
    }

    val source = readSource(sourceDir)
    source.describe(say)

    if (source.recognizedCount == 0) {
        println()
        println("✗ 一份认得的数据都没有（头部要对得上 manycourse-mappoints/1、manycourse-wifi/1 或 manycourse-walk/1）")
        exitProcess(1)
    }

    // ── 标定：锚点 → 每个校区一份 ──────────────────────────────────────────
    say("")
    say("=== 标定 ===")
    val anchors = prepareAnchors(source.anchors, say)
    val calibrationByCampus = LinkedHashMap<String, MapCalibration?>()
    anchors.groupBy { it.campusId }.toSortedMap().forEach { (campusId, ofCampus) ->
        val calibration = buildCalibration(ofCampus)
        calibrationByCampus[campusId] = calibration
        describeCalibration(campusId, ofCampus, calibration, say)
    }

    // ── 轨迹融合 ──────────────────────────────────────────────────────────
    say("")
    say("=== 轨迹融合 ===")
    if (source.walks.isEmpty()) {
        say("（没有原始轨迹文件，跳过）")
    }
    val fixes = mutableListOf<MapPoint>()
    val fusedFingerprints = mutableListOf<WifiFingerprint>()
    source.walks.forEach { (name, log) ->
        val calibration = calibrationByCampus[log.campusId]
        val fusion = fuseWalk(log, calibration, stepLength)
        say("")
        say("  ${name}")
        describeWalk(log, fusion, calibration != null, say)
        fusion.fixes.mapTo(fixes) { it.toMapPoint(log) }
        fusedFingerprints += fusion.fingerprints
    }

    // ── 合并 ──────────────────────────────────────────────────────────────
    say("")
    say("=== 合并 ===")
    val points = mergePoints(source.points, fixes, say)
    val fingerprints = mergeFingerprints(source.fingerprints, fusedFingerprints, say)

    say("")
    say("=== 汇总 ===")
    say("  点位 ${points.size} 个 · 锚点 ${anchors.size} 个 · Wi-Fi 指纹 ${fingerprints.size} 条")
    points.groupBy { it.campusId }.toSortedMap().forEach { (campus, list) ->
        say("    $campus：点位 ${list.size} 个")
    }
    fingerprints.groupBy { it.campusId }.toSortedMap().forEach { (campus, list) ->
        val aps = list.map { it.aps.size }
        say("    $campus：指纹 ${list.size} 条（每个 AP 数 ${aps.min()}~${aps.max()}）")
    }

    // ── 写出 ──────────────────────────────────────────────────────────────
    if (!destDir.isDirectory && !destDir.mkdirs()) {
        println("✗ 建不了输出目录：${destDir.absolutePath}")
        exitProcess(1)
    }
    val pointsFile = File(destDir, "mappoints.txt")
    val wifiFile = File(destDir, "wifi.txt")

    // 排成固定顺序：同样的输入永远写出逐字节相同的文件，diff 才看得出真正的变化
    val sortedPoints = points.sortedWith(compareBy({ it.campusId }, { it.name }, { it.createdAt }))
    val sortedAnchors = anchors.sortedWith(compareBy({ it.campusId }, { it.name }))
    val sortedFingerprints = fingerprints.sortedWith(compareBy({ it.campusId }, { it.at }, { it.imageX }))

    // 显式 UTF-8：文本字段虽然是 Base64（纯 ASCII），也别让 JVM 的默认字符集参与进来
    pointsFile.writeText(
        MapPointCodec.encode(MapPointFile(points = sortedPoints, anchors = sortedAnchors)),
        Charsets.UTF_8,
    )
    wifiFile.writeText(WifiFingerprintCodec.encode(sortedFingerprints), Charsets.UTF_8)

    say("")
    say("=== 写出 ===")
    say("  ${pointsFile.path}  ${pointsFile.length()} B")
    say("  ${wifiFile.path}  ${wifiFile.length()} B")

    // ── 复核 ──────────────────────────────────────────────────────────────
    //
    // 把刚写出去的文件**按 App 读它的路子**再读一遍。打包工具最坏的失败是
    // "写出一份 App 认不出来的数据"：构建照样成功、assets 照样进包，
    // 直到新用户装上发现地图上什么都没有 —— 那时候已经发出去了。
    val verified = verifyWritten(
        pointsFile, wifiFile, sortedPoints, sortedAnchors, sortedFingerprints, say,
    )

    say("")
    if (!verified) {
        println("✗ 复核没过：写出去的 assets 有问题，别拿去构建")
        exitProcess(1)
    }
    say("下一步：重新构建（例如 .\\gradlew.bat assembleDebug）—— 点位与指纹会由 assets 自动载入。")
}

/**
 * 复核写出去的两份文件：头部认不认、读回来条数对不对、内容是不是逐条一致。
 *
 * @return true = 全过
 */
private fun verifyWritten(
    pointsFile: File,
    wifiFile: File,
    expectedPoints: List<MapPoint>,
    expectedAnchors: List<MapAnchor>,
    expectedFingerprints: List<WifiFingerprint>,
    say: (String) -> Unit,
): Boolean {
    say("")
    say("=== 复核（按 App 读它的路子重新读一遍）===")
    var allOk = true
    fun check(label: String, ok: Boolean, why: () -> String) {
        say(if (ok) "  ✓ $label" else "  ✗ $label：${why()}")
        if (!ok) allOk = false
    }

    val pointText = runCatching { pointsFile.readText(Charsets.UTF_8) }.getOrNull()
    check("mappoints.txt 头部认得", pointText != null && MapPointCodec.hasOurHeader(pointText)) {
        if (pointText == null) "文件读不出来" else "App 会整份跳过（头部不是 ${MapPointCodec.HEADER}）"
    }
    val decodedFile = pointText?.let { MapPointCodec.decode(it) }
    check(
        "mappoints.txt 内容一致（点位 ${expectedPoints.size} / 锚点 ${expectedAnchors.size}）",
        decodedFile != null &&
            decodedFile.points == expectedPoints &&
            decodedFile.anchors == expectedAnchors,
    ) {
        if (decodedFile == null) {
            "解不出来"
        } else {
            "读回来是点位 ${decodedFile.points.size} / 锚点 ${decodedFile.anchors.size}，对不上"
        }
    }

    val wifiText = runCatching { wifiFile.readText(Charsets.UTF_8) }.getOrNull()
    check("wifi.txt 头部认得", wifiText != null && WifiFingerprintCodec.hasOurHeader(wifiText)) {
        if (wifiText == null) "文件读不出来" else "App 会整份跳过（头部不是 ${WifiFingerprintCodec.HEADER}）"
    }
    val decodedWifi = wifiText?.let { WifiFingerprintCodec.decode(it) }
    check("wifi.txt 内容一致（指纹 ${expectedFingerprints.size} 条）", decodedWifi == expectedFingerprints) {
        "读回来是 ${decodedWifi?.size ?: "解不出来"} 条，对不上"
    }
    return allOk
}

// ── 读来源 ────────────────────────────────────────────────────────────────

/** 来源目录里认出来的东西（以及认不出来的文件名，照实列出来）*/
private class Source {
    val points = mutableListOf<MapPoint>()
    val anchors = mutableListOf<MapAnchor>()
    val fingerprints = mutableListOf<WifiFingerprint>()
    val walks = mutableListOf<Pair<String, WalkLog>>()
    val recognized = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    val recognizedCount: Int
        get() = recognized.size

    fun describe(say: (String) -> Unit) {
        if (recognized.isNotEmpty()) {
            say("  认出来的文件：")
            recognized.forEach { say("    $it") }
        }
        if (skipped.isNotEmpty()) {
            say("  跳过的文件：")
            skipped.forEach { say("    $it") }
        }
    }
}

/**
 * 按头部认文件 —— 和 `BundledIndoorData.load` **完全同一套判据**，
 * 所以"这个工具认出来的"就等于"构建出来的 APK 载得进去的"。
 */
private fun readSource(dir: File): Source {
    val source = Source()
    dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
        if (!file.isFile) return@forEach
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            source.skipped += "${file.name}（读不出来：${it.message}）"
            return@forEach
        }
        when {
            MapPointCodec.hasOurHeader(text) -> {
                val decoded = MapPointCodec.decode(text)
                if (decoded == null) {
                    source.skipped += "${file.name}（头部对但解不出来）"
                } else {
                    source.points += decoded.points
                    source.anchors += decoded.anchors
                    source.recognized += "${file.name}  点位 ${decoded.points.size} 个、锚点 ${decoded.anchors.size} 个"
                }
            }

            WifiFingerprintCodec.hasOurHeader(text) -> {
                val decoded = WifiFingerprintCodec.decode(text)
                if (decoded == null) {
                    source.skipped += "${file.name}（头部对但解不出来）"
                } else {
                    source.fingerprints += decoded
                    source.recognized += "${file.name}  Wi-Fi 指纹 ${decoded.size} 条"
                }
            }

            WalkLogCodec.hasOurHeader(text) -> {
                val decoded = WalkLogCodec.decode(text)
                if (decoded == null) {
                    source.skipped += "${file.name}（头部对但解不出来）"
                } else {
                    source.walks += file.name to decoded
                    source.recognized +=
                        "${file.name}  原始轨迹：采样 ${decoded.samples.size} 条、" +
                        "扫描 ${decoded.wifiScans.size} 次、打点 ${decoded.taps.size} 个"
                }
            }

            file.extension.equals("csv", ignoreCase = true) ->
                source.skipped += "${file.name}（CSV 是导出给人看的，不入库 —— 数据以同名 .txt 为准）"

            else -> source.skipped += "${file.name}（头部不认识）"
        }
    }
    return source
}

// ── 锚点与标定 ────────────────────────────────────────────────────────────

/**
 * 锚点进库前的两步清理：**去重名** + **拆坐标撞车**。
 *
 * 两步都只做"数据自己就自相矛盾"的那部分，不动任何"只是不太准"的锚点 ——
 * 准不准是 GPS 的事，矛盾不矛盾是数据的事，后者可以机械地判定，前者不行。
 */
private fun prepareAnchors(raw: List<MapAnchor>, say: (String) -> Unit): List<MapAnchor> {
    // 1) 同校区 + 同名 = 同一个人地方（和 MapPointStore 判同的口径一致），只留一条
    val byName = LinkedHashMap<String, MapAnchor>()
    var duplicateNames = 0
    raw.forEach { anchor ->
        if (byName.putIfAbsent("${anchor.campusId}|${anchor.name}", anchor) != null) duplicateNames++
    }
    if (duplicateNames > 0) say("  锚点里有 $duplicateNames 条同校区重名，只留了第一条")

    // 2) 坐标撞车：同一个经纬度不可能同时属于两个不同的地点，逐校区拆
    return byName.values
        .groupBy { it.campusId }
        .toSortedMap()
        .flatMap { (campusId, ofCampus) -> resolveContradictions(campusId, ofCampus, say) }
}

/**
 * 拆掉"坐标撞车"的锚点 —— ★ 这一步直接决定整份标定准不准。
 *
 * ## 撞车是怎么来的
 *
 * 锚点的经纬度是在点「确定」那一刻从**最近一次定位**抄下来的
 * （`DevModePanel` 的 `NameIntent.AnchorAt` 带的是当时那个 `DeviceLocation`）。
 * 连着采几个点时如果定位还没刷新，几个锚点就会拿到**同一个经纬度** ——
 * 于是"图上三个不同的位置"被要求投影到"地面上同一个点"，
 * 这对最小二乘是一组自相矛盾的约束，会把整份标定拽歪，
 * 而且从界面上**完全看不出来**（每个锚点看起来都采成功了）。
 *
 * ## 怎么决定留谁
 *
 * 不猜"哪个先采的就是对的"，而是**量**：先用不撞车的那些锚点算一份参照标定，
 * 再拿它去投影撞车的每个锚点，看谁"用户标的图上位置"和"投影回来的位置"对得上 ——
 * 对得上的那个才是真的，其余的是抄了别人的坐标。
 *
 * 参照标定算不出来（不撞车的锚点不足 2 个）时**一个都不删**：宁可标定差一点，
 * 也不能在没有依据的情况下丢掉用户采的数据。
 */
private fun resolveContradictions(
    campusId: String,
    anchors: List<MapAnchor>,
    say: (String) -> Unit,
): List<MapAnchor> {
    val groups = anchors.groupBy { "%.7f,%.7f".format(it.latitude, it.longitude) }
    val collisions = groups.filterValues { it.size > 1 }
    if (collisions.isEmpty()) return anchors

    val clean = groups.filterValues { it.size == 1 }.values.flatten()
    val reference = buildCalibration(clean)
    if (reference == null) {
        say("  ⚠ $campusId：有 ${collisions.size} 组坐标撞车的锚点，但不撞车的锚点不足 2 个 —— 无从判断，全留着")
        return anchors
    }

    say("  $campusId 里坐标撞车的锚点（同一个经纬度被标到了图上好几个位置，只有一个是对的）：")
    val kept = mutableListOf<MapAnchor>()
    kept += clean
    collisions.values.forEach { group ->
        val ranked = group
            .map { it to anchorErrorMeters(it, reference) }
            .sortedBy { (_, error) -> error }
        val (best, bestError) = ranked.first()
        say("    ✓ 留「${best.name}」：和其余锚点对得上（偏 ${bestError.toInt()} m）")
        ranked.drop(1).forEach { (anchor, error) ->
            say("      ✗ 去「${anchor.name}」：偏 ${error.toInt()} m，且和「${best.name}」共用坐标 " +
                "%.7f,%.7f".format(anchor.latitude, anchor.longitude))
        }
        kept += best
    }
    say("    （撞车的锚点多半是连着采时定位没刷新 —— 建议到开发者模式里删掉重采）")
    return kept
}

/**
 * 把一份标定的成色**如实讲出来**：残差多大、每个锚点偏多少、有没有锚点坐标是重复的。
 *
 * 为什么要专门查"坐标重复"：锚点的经纬度是在**点「确定」那一刻**从最近一次定位抄下来的
 * （`DevModePanel` 的 `NameIntent.AnchorAt` 带的是当时那个 `DeviceLocation`）。
 * 连着采几个锚点时如果定位还没刷新，几个锚点就会拿到**同一个经纬度** ——
 * 那对最小二乘是自相矛盾的约束（同一个真实位置被要求落在图上好几个地方），
 * 会把整份标定拽歪，而且从界面上完全看不出来。
 */
private fun describeCalibration(
    campusId: String,
    anchors: List<MapAnchor>,
    calibration: MapCalibration?,
    say: (String) -> Unit,
) {
    say("")
    say("  $campusId：锚点 ${anchors.size} 个")
    if (calibration == null) {
        say("    ✗ 算不出标定（锚点少于 2 个，或锚点重合/共线）—— GPS 也落不到图上，轨迹融合只会产出指纹")
        return
    }
    val scale = calibration.transform.scale
    val rotation = Math.toDegrees(calibration.transform.rotationRadians)
    say("    缩放 1 图长 ≈ ${if (scale > 0) "%.0f".format(1 / scale) else "?"} m · 旋转 %.1f°".format(rotation))
    say("    残差（最差）%.0f m".format(calibrationResidualMeters(calibration) ?: Double.NaN))

    // 每个锚点自己偏多少 —— 这一列直接指出"哪个锚点点歪了"
    say("    逐点（图上位置 → 按标定投影回来的位置）：")
    anchors.sortedByDescending { anchorErrorMeters(it, calibration) }.forEach { anchor ->
        val projected = projectToImage(anchor.latitude, anchor.longitude, calibration)
        val error = anchorErrorMeters(anchor, calibration)
        val where = projected?.let { "(%.3f, %.3f)".format(it.first, it.second) } ?: "(投影不出来)"
        val mark = if (error > 50) "⚠" else "·"
        say("      $mark ${anchor.name}：(%.3f, %.3f) → %s  偏 %.0f m".format(anchor.imageX, anchor.imageY, where, error))
    }

    // 坐标完全一样的锚点 = 采的时候定位没刷新
    anchors.groupBy { "%.7f,%.7f".format(it.latitude, it.longitude) }
        .filter { it.value.size > 1 }
        .forEach { (coordinate, group) ->
            say("    ⚠ 坐标重复：${group.joinToString(" / ") { it.name }} 都是 $coordinate")
            say("       （多半是连着采点时定位没刷新；这几个锚点互相矛盾，会让整份标定偏掉）")
        }
}

/** 一个锚点"图上位置"与"按标定投影回来的位置"差多少米 */
private fun anchorErrorMeters(anchor: MapAnchor, calibration: MapCalibration): Double {
    val projected = projectToImage(anchor.latitude, anchor.longitude, calibration) ?: return Double.NaN
    val scale = calibration.transform.scale
    if (scale <= 0.0) return Double.NaN
    return hypot(
        (projected.first - anchor.imageX).toDouble(),
        (projected.second - anchor.imageY).toDouble(),
    ) / scale
}

// ── 轨迹 ──────────────────────────────────────────────────────────────────

private fun describeWalk(log: WalkLog, fusion: WalkFusion, calibrated: Boolean, say: (String) -> Unit) {
    val first = log.samples.firstOrNull()?.at ?: 0L
    val last = log.samples.lastOrNull()?.at ?: 0L
    val gps = log.samples.count { it is WalkSample.Gps }
    say("    ${log.schoolId}/${log.campusId} · ${duration(last - first)} · 采样 ${log.samples.size} 条")
    say("    步数 ${log.steps}（按步长推 ${"%.0f".format(fusion.walkedMeters)} m）· GPS $gps 次 · 扫描 ${log.wifiScans.size} 次")
    if (!calibrated) {
        say("    ⚠ 这个校区没有标定：整趟推不出位置，所以一条指纹也存不下来（打点会进 unplaced）")
    }
    say("    → 指纹 ${fusion.fingerprints.size} 条（扫描 ${log.wifiScans.size} 次，其余没落库：位置未定或 AP 太少）")
    say("    → 打点 ${log.taps.size} 个：落图 ${fusion.fixes.size} 个、落不了图 ${fusion.unplaced.size} 个")
    if (fusion.unplaced.isNotEmpty()) say("      落不了的：${fusion.unplaced.joinToString("、")}")
}

/** 打点 → 点位（和 `WalkRecorder.mergeFix` 同一个意图：位置来自融合，名称来自人）*/
private fun WalkFix.toMapPoint(log: WalkLog): MapPoint {
    val how = when (source) {
        WalkFixSource.Gps -> "GPS 锚定"
        WalkFixSource.Pdr -> "PDR 推算"
    }
    return MapPoint(
        id = "walk-${log.startedAt}-$at",
        campusId = log.campusId,
        name = name,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = errorMeters?.toFloat(),
        imageX = imageX,
        imageY = imageY,
        note = "轨迹融合（$how${errorMeters?.let { "，±${it.toInt()} m" }.orEmpty()}）",
        createdAt = at,
    )
}

private fun duration(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"
}

// ── 合并 ──────────────────────────────────────────────────────────────────

/**
 * 点位去重：`校区 + 归一化名字`（`MapPointStore.key()` 的口径）。
 *
 * 同名冲突时**留误差小的那条**：轨迹融合出来的位置有 `±X m` 的估计，
 * 而图上采的点是零误差的（人点的），所以后者的 `accuracyMeters` 为 null 时不该被顶掉 ——
 * 下面按 `accuracyMeters` 升序取最小，null 视为"零误差"排最前。
 */
private fun mergePoints(own: List<MapPoint>, fused: List<MapPoint>, say: (String) -> Unit): List<MapPoint> {
    val byKey = LinkedHashMap<String, MapPoint>()
    (own + fused).forEach { point ->
        val key = "${point.campusId}|${normalizePlace(point.name)}"
        val previous = byKey[key]
        if (previous == null) {
            byKey[key] = point
        } else if (errorOf(point) < errorOf(previous)) {
            say("  同名点位「${point.name}」留了误差更小的那条（${describeError(point)} < ${describeError(previous)}）")
            byKey[key] = point
        } else {
            say("  同名点位「${point.name}」留了误差更小的那条（${describeError(previous)} ≤ ${describeError(point)}）")
        }
    }
    say("  点位：设备采的 ${own.size} 个 + 轨迹融合的 ${fused.size} 个 → 去重后 ${byKey.size} 个")
    return byKey.values.toList()
}

/** 误差：图上采的点没有精度信息 = 零误差（人点的位置就是准的）*/
private fun errorOf(point: MapPoint): Float = point.accuracyMeters ?: -1f

private fun describeError(point: MapPoint): String =
    point.accuracyMeters?.let { "±${it.toInt()} m" } ?: "图上采的（零误差）"

/**
 * 指纹去重：`校区 + 采集时刻`。
 *
 * **一次扫描 = 一个时刻**，所以这个键能精确地把"同一趟里同一次扫描"认出来 ——
 * 设备上那份 `wifi.txt` 和本工具从同一份 `walk-*.txt` 融合出来的结果就是靠它对齐的。
 *
 * 冲突时**留融合出来的那条**：它是对着**最终随包下发的这份锚点**重算的，
 * 和包里的标定自洽；设备上那条是采集当时按"当时的标定"算的，锚点后来改过就对不上了。
 *
 * 没有时刻的老数据（`at = 0`）退回用图上坐标当键 —— 否则所有老指纹会撞成一个。
 */
private fun mergeFingerprints(
    own: List<WifiFingerprint>,
    fused: List<WifiFingerprint>,
    say: (String) -> Unit,
): List<WifiFingerprint> {
    val byKey = LinkedHashMap<String, WifiFingerprint>()
    own.forEach { byKey[fingerprintKey(it)] = it }
    var replaced = 0
    fused.forEach { fingerprint ->
        val key = fingerprintKey(fingerprint)
        if (byKey.put(key, fingerprint) != null) replaced++
    }
    say("  指纹：设备采的 ${own.size} 条 + 轨迹融合的 ${fused.size} 条 → 去重后 ${byKey.size} 条")
    if (replaced > 0) {
        say("    （其中 $replaced 条两边都有，按最终锚点重算的那份为准）")
    }
    return byKey.values.toList()
}

private fun fingerprintKey(fingerprint: WifiFingerprint): String =
    if (fingerprint.at > 0L) {
        "${fingerprint.campusId}|t${fingerprint.at}"
    } else {
        "${fingerprint.campusId}|x${"%.6f".format(fingerprint.imageX)}|y${"%.6f".format(fingerprint.imageY)}"
    }
