package com.tof.manycourse.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 校园地图上的**一个点位**：地图页"今日课程定位"和"我在这儿"都靠它。
 *
 * ## 为什么要有"点位"这一层
 *
 * 地图是一张**图片**（`res/drawable-nodpi/school_map_*.jpg`，见 [SchoolMap]），
 * 而图片上**没有任何地理信息** —— 它不知道自己在哪、每像素多少米、哪边是北。
 * 于是"我在这儿"和"这节课在哪栋楼"都没有落脚点。
 *
 * 点位就是补上这个缺口的最小数据：
 *
 * | 采集方式 | 有什么 | 用途 |
 * |---|---|---|
 * | **原地采点**（站在那儿点一下）| 经纬度（+ 精度） | 标定之后可投影到图上；也是"这栋楼真实坐标"的长期记录 |
 * | **图上采点**（在图上点一下）| 图上归一化坐标 | 不需要任何标定就能显示；靠人眼把"图上那栋楼"和"课表里的教室名"对上 |
 *
 * 两种可以只具备一种：图上采的点没有经纬度，原地采的点在**标定之前**落不到图上
 * （会如实显示成"未定位"，见 [projectToImage]）。
 *
 * ## 坐标约定
 *
 * [imageX] / [imageY] 是**归一化**的 0~1（相对图片本身的宽高，左上角为原点、y 向下），
 * **不是像素** —— 原图 1280×959，不同设备显示尺寸也不同，存像素等于把数据绑死在一块屏幕上。
 *
 * @param id        稳定 id（采集时生成，用来删除/去重）
 * @param campusId  属于哪个校区（`SchoolCampus.id`）—— 点位只在同一个校区里有意义
 * @param name      **点位名**。这是和课表对接的唯一钥匙：课表里写"三教 A101"，
 *   点位名就要能让 [matchPointForRoom] 在里面匹配上（见该函数的规则）
 * @param latitude / [longitude] 原地采到的坐标；图上采点为 null
 * @param accuracyMeters 采集时系统给的定位精度（米）；越大越不可信，见 [MapPointAccuracyHint]
 * @param note      备注（比如"这个是侧门，正门在西边"）
 * @param createdAt 采集时间（毫秒时间戳）
 */
data class MapPoint(
    val id: String,
    val campusId: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val imageX: Float? = null,
    val imageY: Float? = null,
    val note: String = "",
    val createdAt: Long = 0L,
) {
    /** 有经纬度（原地采的，或标定后回填的）*/
    val hasGps: Boolean get() = latitude != null && longitude != null

    /** 有图上位置（图上采的，或标定后投影出来的）*/
    val hasImage: Boolean get() = imageX != null && imageY != null
}

/**
 * **标定锚点**：图上某个位置 ↔ 一个已知的真实坐标。
 *
 * 一个校区至少要有 [MIN_ANCHORS] 个锚点，才能算出"经纬度 → 图上位置"的换算
 * （见 [buildCalibration]）。锚点越准、分得越开，投影越准 —— 两个点最好取
 * 校区里**相距最远的两个明显地点**（比如正门和体育馆），别取两栋挨着的楼。
 *
 * @param name 这个锚点是什么（"正门" / "图书馆门口"），给用户自己看的
 */
data class MapAnchor(
    val campusId: String,
    val name: String,
    val imageX: Float,
    val imageY: Float,
    val latitude: Double,
    val longitude: Double,
)

/**
 * **相似变换**：把局部平面坐标（东/北，单位米）映射到图上归一化坐标。
 *
 * ```
 *   u = a * east - b * north + tx
 *   v = b * east + a * north + ty          ← v 是**y 向上**的
 *   图上 y（向下）= -v
 * ```
 *
 * ## 为什么是"相似变换"（4 个参数）而不是简单缩放
 *
 * 校园平面图**不一定正北朝上**（有些学校的手绘图是斜的、或者干脆转了 90°），
 * 所以除了缩放还要允许**旋转**：[a] / [b] 一起表达"缩放 + 旋转"（`a = s·cosθ`、`b = s·sinθ`）。
 *
 * ## 为什么 v 要取负
 *
 * 图上 y 向下、而"北"在图上通常**向上**，这两者之间差一个**反射**；
 * 而反射不属于相似变换（相似变换 det > 0，反射 det < 0）。
 * 所以先把图上坐标翻成"y 向上"再拟合，投影时再翻回来 ——
 * 这样正北朝上的图拟合出来就是干净的 `b ≈ 0`（纯缩放），符合直觉，也方便看残差。
 *
 * @param a / [b] 线性部分（缩放 与 旋转）
 * @param tx / [ty] 平移部分
 */
data class MapTransform(
    val a: Double,
    val b: Double,
    val tx: Double,
    val ty: Double,
) {
    /**
     * 缩放系数：**1 米对应多少"归一化图长"**。
     *
     * 用它把"图上的误差"换算成"实际的米"（见 [calibrationResidualMeters]）：
     * 归一化误差 ÷ [scale] = 米。
     */
    val scale: Double get() = hypot(a, b)

    /** 旋转角（弧度，图上顺时针为正）；正北朝上的图应当接近 0 */
    val rotationRadians: Double get() = kotlin.math.atan2(b, a)
}

/** 一个校区**标定好**之后的换算关系（原点 + 变换 + 用了哪些锚点）*/
data class MapCalibration(
    val campusId: String,
    /** 局部平面坐标的原点（= 各锚点的平均值），投影时要用同一个原点 */
    val originLatitude: Double,
    val originLongitude: Double,
    val transform: MapTransform,
    val anchors: List<MapAnchor>,
)

/** 算一次标定最少要几个锚点：2 个点 + 已知"y 向上"就足以定出 4 个参数 */
internal const val MIN_ANCHORS = 2

/**
 * 点位名最短几个字才允许参与"包含匹配"。
 *
 * 为什么要有下限：教室串是 `A1N403 禄口` 这种，如果用户采了一个叫 `A` 的点，
 * 那它会把**所有**含 A 的教室都吃掉（`A1N403`、`B2A`…），而且用户还不知道为什么。
 * 两个字起步（`三教`、`A1`）仍然偏松，但至少不会一个字就横扫。
 */
internal const val MIN_MATCH_NAME_LENGTH = 2

/**
 * 采到的经纬度 → 相对 [originLatitude] / [originLongitude] 的**局部平面坐标**（东、北，单位米）。
 *
 * 用等距圆柱近似（和 `data/SchoolMap.kt` 的 [distanceMeters] 同一套）：
 * 校区尺度上（几公里内）误差远小于 GPS 自身的误差，不值得上投影/大地主题解算。
 */
internal fun localEastNorth(
    latitude: Double,
    longitude: Double,
    originLatitude: Double,
    originLongitude: Double,
): Pair<Double, Double> {
    val earthRadius = 6_371_000.0
    val east = Math.toRadians(longitude - originLongitude) * cos(Math.toRadians(originLatitude)) * earthRadius
    val north = Math.toRadians(latitude - originLatitude) * earthRadius
    return east to north
}

/**
 * 用锚点算出一份标定；锚点少于 [MIN_ANCHORS] 个、或锚点重合/共线导致解不出来时返回 null。
 *
 * 4 个未知参数用**最小二乘**解（锚点正好 2 个时是唯一解；3 个以上时是拟合，
 * 所以多采几个能摊平单次 GPS 的误差）。
 *
 * ⚠️ 返回 null **不是**异常情况：说明"这个校区还没标定"，页面应当如实说
 * "还落不到图上"，而不是拿一个瞎猜的变换把点画到图上。
 */
internal fun buildCalibration(anchors: List<MapAnchor>): MapCalibration? {
    if (anchors.size < MIN_ANCHORS) return null
    val originLatitude = anchors.map { it.latitude }.average()
    val originLongitude = anchors.map { it.longitude }.average()
    val transform = solveSimilarity(anchors, originLatitude, originLongitude) ?: return null
    if (transform.scale <= 0.0 || !transform.scale.isFinite()) return null
    return MapCalibration(
        campusId = anchors.first().campusId,
        originLatitude = originLatitude,
        originLongitude = originLongitude,
        transform = transform,
        anchors = anchors,
    )
}

/** 组装最小二乘的行（每个锚点两行），再交给 [solveLeastSquares] */
private fun solveSimilarity(
    anchors: List<MapAnchor>,
    originLatitude: Double,
    originLongitude: Double,
): MapTransform? {
    val rows = ArrayList<DoubleArray>(anchors.size * 2)
    anchors.forEach { anchor ->
        val (east, north) = localEastNorth(
            anchor.latitude, anchor.longitude, originLatitude, originLongitude,
        )
        // u = a·e − b·n + tx     （u 就是图上 x）
        rows += doubleArrayOf(east, -north, 1.0, 0.0, anchor.imageX.toDouble())
        // v = b·e + a·n + ty     （v = −图上 y）
        rows += doubleArrayOf(north, east, 0.0, 1.0, -anchor.imageY.toDouble())
    }
    val solution = solveLeastSquares(rows) ?: return null
    return MapTransform(solution[0], solution[1], solution[2], solution[3])
}

/**
 * 解 4 元最小二乘：`A·x = rhs`（[rows] 每行 = 4 个系数 + 1 个右端项）。
 *
 * 走**正规方程** `(AᵀA)x = Aᵀrhs` + 带主元的高斯消元。不上 QR/SVD：
 * 这里只有 4 个未知数、锚点也就几个，正规方程在数值上完全够用，
 * 而 SVD 得多写一百行没人会看的代码。
 *
 * @return 4 个解；矩阵奇异（锚点重合 / 共线）时返回 null
 */
private fun solveLeastSquares(rows: List<DoubleArray>): DoubleArray? {
    val n = 4
    if (rows.size < n) return null
    val ata = Array(n) { DoubleArray(n) }
    val atb = DoubleArray(n)
    rows.forEach { row ->
        for (i in 0 until n) {
            atb[i] += row[i] * row[n]
            for (j in 0 until n) ata[i][j] += row[i] * row[j]
        }
    }
    return gaussianSolve(ata, atb)
}

/**
 * 高斯消元解 `matrix·x = rhs`（部分主元法）。
 *
 * 奇异性判据用**相对**阈值：`AᵀA` 的量级取决于锚点离原点多远（几百米的锚点，
 * 元素能到 1e5~1e6），写死一个绝对阈值要么误判"正常标定"为奇异、要么放过真的重合锚点。
 */
private fun gaussianSolve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
    val n = rhs.size
    val m = Array(n) { matrix[it].copyOf() }
    val b = rhs.copyOf()

    var magnitude = 0.0
    for (row in 0 until n) for (col in 0 until n) magnitude = maxOf(magnitude, abs(m[row][col]))
    val epsilon = 1e-9 * maxOf(magnitude, 1.0)

    for (col in 0 until n) {
        var pivot = col
        for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
        if (abs(m[pivot][col]) < epsilon) return null // 奇异：锚点重合 / 共线
        if (pivot != col) {
            val rowSwap = m[pivot]; m[pivot] = m[col]; m[col] = rowSwap
            val valueSwap = b[pivot]; b[pivot] = b[col]; b[col] = valueSwap
        }
        for (row in col + 1 until n) {
            val factor = m[row][col] / m[col][col]
            if (factor == 0.0) continue
            for (k in col until n) m[row][k] -= factor * m[col][k]
            b[row] -= factor * b[col]
        }
    }

    val x = DoubleArray(n)
    for (row in n - 1 downTo 0) {
        var sum = b[row]
        for (k in row + 1 until n) sum -= m[row][k] * x[k]
        x[row] = sum / m[row][row]
    }
    return if (x.all { it.isFinite() }) x else null
}

/**
 * 经纬度 → **图上归一化坐标**（0~1，左上角原点、y 向下）。
 *
 * @return null = 这个校区还没有标定（[calibration] 为 null）
 *
 * ⚠️ 返回值**可能超出 0~1**：那说明这个点在图片范围之外（人跑到校区外面去了，
 * 或者标定本身是歪的）。调用方应当如实说明"在图外"，而不是把它夹到边上假装在图里。
 */
internal fun projectToImage(
    latitude: Double,
    longitude: Double,
    calibration: MapCalibration?,
): Pair<Float, Float>? {
    if (calibration == null) return null
    val (east, north) = localEastNorth(
        latitude, longitude, calibration.originLatitude, calibration.originLongitude,
    )
    val t = calibration.transform
    val u = t.a * east - t.b * north + t.tx
    val v = t.b * east + t.a * north + t.ty
    val x = u.toFloat()
    val y = (-v).toFloat()
    if (x.isNaN() || y.isNaN() || x.isInfinite() || y.isInfinite()) return null
    return x to y
}

/** 图上归一化坐标在不在图片范围内（0~1）；给"图外"提示用 */
internal fun isInsideImage(x: Float, y: Float): Boolean =
    x >= 0f && x <= 1f && y >= 0f && y <= 1f

/**
 * 一个点位**最终落在图上的位置**（标记就画在这儿）。
 *
 * 优先用采集时直接记下的图上坐标（图上采的点），否则拿 GPS 按当前标定投影过来。
 *
 * ★ 投影是**每次渲染现算**的，不在采集时写死到点位里：这样"后来才补的锚点"
 * 会立刻让先前采的那批 GPS 点位全部落到图上，不需要重新采一遍。
 *
 * @return null = 这个点既没有图上坐标、也投影不出来（没有标定 / 没有 GPS）
 */
internal fun imagePlacementOf(
    point: MapPoint,
    calibration: MapCalibration?,
): Pair<Float, Float>? {
    if (point.hasImage) return point.imageX!! to point.imageY!!
    val latitude = point.latitude ?: return null
    val longitude = point.longitude ?: return null
    return projectToImage(latitude, longitude, calibration)
}

/**
 * 标定**残差**（米）：把每个锚点当输入投影一遍，看它落在离"用户标注的图上位置"多远的地方。
 *
 * 这是用户在开发者模式里唯一能看到的"标定到底准不准"的反馈：
 *  - 残差几百米 → 锚点点错了（把图上另一个地方点成了自己站的地方），或者两个锚点几乎重合；
 *  - 残差几十米 → 正常（GPS 本身就有这个量级的误差）；
 *  - 想变小：多采几个锚点，并且**取相距远的**地方。
 *
 * @return null = 没有标定 / 锚点为空 / 缩放系数为 0（算不出米）
 */
internal fun calibrationResidualMeters(calibration: MapCalibration): Double? {
    if (calibration.anchors.isEmpty()) return null
    val scale = calibration.transform.scale
    if (scale <= 0.0 || !scale.isFinite()) return null
    var worst = 0.0
    calibration.anchors.forEach { anchor ->
        val projected = projectToImage(anchor.latitude, anchor.longitude, calibration) ?: return null
        val error = hypot(
            (projected.first - anchor.imageX).toDouble(),
            (projected.second - anchor.imageY).toDouble(),
        )
        worst = maxOf(worst, error)
    }
    return worst / scale
}

/**
 * 地点名归一化：抹掉大小写、空白和连字符的差异。
 *
 * 课表里的教室是教务系统给的（`A1N403 禄口`、`教学楼 A-101`、`三教 201`），
 * 而点位名是用户自己敲的（`A1N403`、`三教A101`）—— 两边差的就是这些字符。
 *
 * 只抹这几类，**不抹数字和字母**：抹多了会把 `A101` 和 `B101` 判成同一个地方。
 */
internal fun normalizePlace(text: String): String =
    buildString(text.length) {
        text.forEach { char ->
            when {
                char.isWhitespace() -> Unit          // 半角/全角空格、制表符
                char == '-' || char == '_' || char == '－' -> Unit
                char == '·' || char == '・' -> Unit
                else -> append(char.lowercaseChar())
            }
        }
    }

/**
 * 给"课表里的一行地点"找一个点位 —— ★ 今日课程能不能画到图上，全看这个函数。
 *
 * 规则（按可信度从高到低）：
 *  1. **完全相等**（归一化之后）→ 直接用；
 *  2. 否则取"**教室串里包含点位名**"里**最长**的那个点位名。
 *     为什么要最长：采了 `三教` 和 `三教A101` 两个点时，`三教 A101` 应当匹配到后者。
 *  3. 点位名短于 [MIN_MATCH_NAME_LENGTH] 个字的**不参与**第 2 条（见该常量的注释）。
 *
 * @return 匹配到的点位；一个都匹配不上返回 null（调用方如实显示"没采过这个地点"）
 */
internal fun matchPointForRoom(room: String, points: List<MapPoint>): MapPoint? {
    val target = normalizePlace(room)
    if (target.isEmpty()) return null

    points.firstOrNull { normalizePlace(it.name) == target }?.let { return it }

    return points
        .map { it to normalizePlace(it.name) }
        .filter { (_, normalized) -> normalized.length >= MIN_MATCH_NAME_LENGTH && target.contains(normalized) }
        .maxByOrNull { (_, normalized) -> normalized.length }
        ?.first
}

/**
 * 采集到的点位精度够不够用（给开发者模式里的提示用）。
 *
 * 这几档不是随便定的：教务系统的教室名最细到"哪一间"，而一间教室十几米宽 ——
 * 所以点位误差 **≤30 m** 才敢说"就是这个楼"；100 m 以上基本只能定到"哪一片"。
 */
enum class MapPointAccuracyHint(val label: String) {
    /** ≤15 m：GPS 已经锁上了，可以直接用来标定 */
    Good("精度好（≤15 m）"),

    /** ≤50 m：能用，但标定时最好多采几个点摊一摊 */
    Fair("精度一般（≤50 m）"),

    /** >50 m：多半是基站/WiFi 定位，建议走到室外再采一次 */
    Poor("精度差（>50 m），建议到室外重采"),

    /** 系统没给精度（图上采的点、或老数据）*/
    Unknown("没有精度信息"),
    ;

    companion object {
        fun of(accuracyMeters: Float?): MapPointAccuracyHint = when {
            accuracyMeters == null -> Unknown
            accuracyMeters <= 15f -> Good
            accuracyMeters <= 50f -> Fair
            else -> Poor
        }
    }
}
