package com.tof.manycourse.data

import kotlin.math.hypot

/**
 * ★ **"我现在在哪栋楼"** —— 把位置估算**收敛到一个已知地点**，而不是画一个精确的点。
 *
 * ## 为什么要有这一层
 *
 * 画"我在这儿"的蓝点等于在承诺**米级**精度，而这个应用拿不到那个精度：
 *  - 地图是**图片**，经纬度靠标定换算，标定残差实测在**几十米**量级（`gzus/guangzhou`
 *    清理完锚点后仍有 55 m）；
 *  - 室内没有 GPS；Wi-Fi 指纹被系统节流到"2 分钟 4 次"，指纹点之间隔着 20 米上下。
 *
 * 而用户真正想知道的是"**我在哪栋楼**"—— 这是个**离散**问题。离散化有个好处：
 * 误差只要**小于楼间距的一半**就不影响答案，几十米的误差不再是致命的。
 *
 * ## ★ 关键设计：认楼**不经过地图投影**
 *
 * 有两条路，分别对应两种数据，都用**最不依赖标定**的那条：
 *
 * | 位置来源 | 用什么比 | 标定误差参与吗 |
 * |---|---|---|
 * | GPS（室外）| [nearestPlaceByGround]：手机经纬度 vs 地点的**真实经纬度** | **完全不参与** |
 * | Wi-Fi 指纹（室内）| [nearestPlaceByImage]：两者都只有图上坐标，只能在图上比 | 参与，但两边同源、一阶抵消 |
 *
 * 这一点是本节最重要的地方：**"我在哪栋楼"根本不需要知道地图长什么样** ——
 * 只要知道每个地点真实的经纬度，拿手机的位置一比就够了。所以那条 55 m 的标定残差
 * 在认楼时**一点都用不上**，剩下的误差只有 GPS 本身（几米到几十米）和地点坐标本身的误差。
 * 把认楼硬做成"先投影到图上、再在图上看离哪个标记近"反而会把标定误差**请进来**。
 *
 * ## 不敢说的时候就别说
 *
 * 结论里带一个 [PlaceVerdict]：两个候选差得太近（低于 [PLACE_MARGIN_METERS]）时
 * 如实说"可能是这两栋"，而不是挑一个报出去。这不是保守 —— 在"哪栋楼"这个问题上，
 * 报错的代价比说"分不出来"大得多（用户会照着走错方向）。
 *
 * @param distanceMeters 到最近那个地点的距离（米）
 * @param runnerUp / [runnerUpMeters] 第二名，用来判断"是不是拿不准"；没有第二个地点时为 null
 */
data class PlaceFix(
    val point: MapPoint,
    val distanceMeters: Double,
    val runnerUp: MapPoint?,
    val runnerUpMeters: Double?,
    val verdict: PlaceVerdict,
)

/** [PlaceFix] 能说到哪一步 —— 直接决定界面上那句话怎么写 */
enum class PlaceVerdict {
    /** **就在这儿**：最近的那个够近（≤ [PLACE_HERE_METERS]），且明显比第二名近 */
    Here,

    /** **分不出来**：两个候选离得差不多，只能说"可能是这两个之一" */
    Ambiguous,

    /** **不在它那儿**：最近的就是它，但还隔着一段距离 —— 只说"离你最近的是……" */
    Nearest,
}

/**
 * 认楼时**假定我们自己的位置误差量级**（米）。
 *
 * 由两部分构成，都取的是常见值而不是最好值：
 *  - 手机定位：室外 GPS 常见 4~30 m，室内基站/Wi-Fi 定位 30~100 m（后者本来就认不了楼）；
 *  - **地点坐标本身的误差**：锚点的经纬度是采集当时抄下来的，实测就有过"两个地点同一个坐标"
 *    这种明显偏差（见 `tools/indoor_pack/IndoorPack.kt` 的坐标撞车检查）。
 *
 * 取 20 m 是为了让 [PLACE_MARGIN_METERS] 有个**有依据**的来源，而不是随手写个"看起来合理"的数。
 */
internal const val PLACE_ERROR_METERS = 20.0

/**
 * 敢说"就是它"所需的**领先优势**（米）：最近的必须比第二名近这么多。
 *
 * 取 [PLACE_ERROR_METERS] 的 2 倍：两个候选的距离差要**明显大于**我们自己的误差量级，
 * 才算"这个差是真的"。差值在误差量级以内时，那次排序随时可能反过来。
 */
internal const val PLACE_MARGIN_METERS = PLACE_ERROR_METERS * 2

/**
 * 敢说"**就在这栋楼**"的距离上限（米）。
 *
 * 60 m 大致是"一栋楼加上它门口那一圈"的尺度。比这还远就只能说"离你最近的是它"——
 * 哪怕它比第二名近得多（你在空旷处，最近的一栋楼在 120 m 外，"你在这栋楼"是句假话）。
 */
internal const val PLACE_HERE_METERS = 60.0

/**
 * 超过这个距离就**什么都不说**（米）。
 *
 * 走路时误触、或者人在别的校区、或者地点坐标本身是错的，都可能让"最近的"隔着几百米。
 * 那时候报一个"离你最近的是图书馆，约 3 km"只是噪音 —— 不如老实承认这儿没有已知地点。
 */
internal const val PLACE_MAX_METERS = 150.0

/**
 * **按真实经纬度认楼**（GPS 走这条，见类注释里那张表）。
 *
 * @return null = 一个带经纬度的地点都没有 / 最近的也超出 [PLACE_MAX_METERS]（说不出什么）
 */
internal fun nearestPlaceByGround(
    latitude: Double,
    longitude: Double,
    points: List<MapPoint>,
): PlaceFix? = rankToFix(
    points
        .mapNotNull { point ->
            val placeLatitude = point.latitude ?: return@mapNotNull null
            val placeLongitude = point.longitude ?: return@mapNotNull null
            point to distanceMeters(latitude, longitude, placeLatitude, placeLongitude)
        }
        .sortedBy { (_, meters) -> meters },
)

/**
 * **按图上距离认楼**（只有图上坐标的 Wi-Fi 指纹走这条）。
 *
 * 这里比的是"图上靠得多近"，所以**标定误差是参与进来的** —— 但两个坐标（指纹的位置、
 * 地点的位置）本来就是同一套标定算/标的，误差同向，一阶上互相抵消，
 * 比"投影后再看离哪个标记近"要稳（后者会把标定误差**单向**加进来）。
 *
 * @return null = 没有可用的标定（没有标定就没有"米"，也就无所谓远近）/ 没有可用地点 /
 *   最近的超出 [PLACE_MAX_METERS]
 */
internal fun nearestPlaceByImage(
    imageX: Float,
    imageY: Float,
    points: List<MapPoint>,
    calibration: MapCalibration?,
): PlaceFix? {
    // scale = "1 米对应多少归一化图长"，所以 米 = 图上距离 / scale
    val scale = calibration?.transform?.scale
    if (scale == null || scale <= 0.0 || !scale.isFinite()) return null

    return rankToFix(
        points
            .mapNotNull { point ->
                // imagePlacementOf 会在"有图上坐标"和"能按经纬度投影"之间自己选，
                // 所以图上采的点位和原地采的点位都能参与
                val placement = imagePlacementOf(point, calibration) ?: return@mapNotNull null
                val units = hypot(
                    (placement.first - imageX).toDouble(),
                    (placement.second - imageY).toDouble(),
                )
                point to units / scale
            }
            .sortedBy { (_, meters) -> meters },
    )
}

/** 把"按距离排好序的候选"收成结论；最近的太远就返回 null（见 [PLACE_MAX_METERS]）*/
private fun rankToFix(ranked: List<Pair<MapPoint, Double>>): PlaceFix? {
    val best = ranked.firstOrNull() ?: return null
    if (best.second > PLACE_MAX_METERS) return null
    val second = ranked.getOrNull(1)
    return PlaceFix(
        point = best.first,
        distanceMeters = best.second,
        runnerUp = second?.first,
        runnerUpMeters = second?.second,
        verdict = placeVerdict(best.second, second?.second),
    )
}

/**
 * 能说到哪一步。
 *
 * **先判"分不分得出来"再判"够不够近"**：分不出来的时候，"够近"这个判断本身也不可靠
 * （两个候选都近，或者都不近，排序都可能翻），所以那种情况一律退到 [PlaceVerdict.Ambiguous]。
 */
private fun placeVerdict(nearestMeters: Double, runnerUpMeters: Double?): PlaceVerdict = when {
    runnerUpMeters != null && runnerUpMeters - nearestMeters < PLACE_MARGIN_METERS ->
        PlaceVerdict.Ambiguous

    nearestMeters <= PLACE_HERE_METERS -> PlaceVerdict.Here

    else -> PlaceVerdict.Nearest
}

/**
 * 「我在哪栋楼」**那句话本身** —— 纯函数，所以措辞能被单测钉住
 * （和 `ui/MapScreen.kt` 的 `locationHintOf` 同一个路子：判定不进 Composable）。
 *
 * ## 措辞分三档，刻意**都不说"你在 X 里面"**，除非确实够近
 *
 * | 结论 | 说出来 |
 * |---|---|
 * | [PlaceVerdict.Here] | `你在这儿：图书馆（约 12 m）` |
 * | [PlaceVerdict.Nearest] | `离你最近的是「图书馆」（约 120 m）` |
 * | [PlaceVerdict.Ambiguous] | `你大概在「图书馆」一带，也可能是「冬天超市」（两处都约 50 m，分不出来）` |
 *
 * [PlaceVerdict.Nearest] 那一档**不能**写成"你在图书馆"：100 米外那栋楼跟你没关系，
 * 说"你在里面"是假话。而"离你最近的是图书馆"仍然是**有用**的信息（至少知道往哪边看），
 * 所以也不该像"分不出来"那样干脆闭嘴 —— 诚实和有用在这里可以同时做到。
 *
 * @param fix 认楼的结论；null = 没认出来
 * @param hasPosition 手上到底有没有位置估算。为 false 时**不解释为什么认不出** ——
 *   那时该由"为什么画不出蓝点"那句去说，否则同一个原因会被说两遍
 * @param hasPlaces 这个校区采过地点没有（决定"认不出"是"没采过"还是"都太远"，两句话的下一步不一样）
 * @param precise 位置是不是**精确来源**（GPS）。false = Wi-Fi 指纹估算，**一律降一档说法**：
 *   不说"你在这儿"，只说"你大概在……一带"并明写"Wi-Fi 估算" ——
 *   理由见类注释：现有的指纹库全是从**室外**那趟走出来的，在室内拿它反查
 *   等于"用手电筒照隔壁房间"，能出结果、但不该被当成结论端上去
 * @return null = 什么都不用说
 */
internal fun placeNoteOf(
    fix: PlaceFix?,
    hasPosition: Boolean,
    hasPlaces: Boolean,
    precise: Boolean = true,
): String? {
    if (fix != null) {
        val distance = "约 ${formatWalkDistance(fix.distanceMeters)}"
        val prefix = if (precise) "" else "Wi-Fi 估算："
        return when (fix.verdict) {
            // 只有"精确来源 + 确实够近"才敢说"你在这儿"
            PlaceVerdict.Here ->
                if (precise) {
                    "你在这儿：${fix.point.name}（$distance）"
                } else {
                    "${prefix}你大概在「${fix.point.name}」一带（$distance）"
                }

            PlaceVerdict.Nearest -> "${prefix}离你最近的是「${fix.point.name}」（$distance）"

            PlaceVerdict.Ambiguous ->
                "${prefix}你大概在「${fix.point.name}」一带" +
                    (fix.runnerUp?.let { "，也可能是「${it.name}」" } ?: "") +
                    "（两处都 $distance，分不出来）"
        }
    }
    if (!hasPosition) return null
    return if (hasPlaces) {
        "认不出这是哪栋楼（离已知地点都太远）"
    } else {
        "认不出这是哪栋楼（这张图上还没有采过地点）"
    }
}
