package com.tof.manycourse.data

/** 一次定位结果 —— 只要经纬度：本应用不显示坐标、不做导航，坐标唯一的用途是"挑校区" */
data class DeviceLocation(val latitude: Double, val longitude: Double)

/**
 * 地图页最终**显示哪个校区**（[pickCampus] 的结论）。
 *
 * 带上"为什么是它"，是为了让页面能如实解释一句（"已按定位选…" / "未授权，默认…"），
 * 而不是默默换一张图让人以为看错了。
 */
sealed interface CampusPick {

    /** 要显示的校区；这所学校压根没登记地图时为 null（页面显示空状态）*/
    val campus: SchoolCampus?

    /**
     * **用户手选的**校区：压过定位结果。
     *
     * 为什么要它：定位可能错（在宿舍楼里飘到隔壁校区、GPS 没热起来、只给了"大致位置"），
     * 而地图是"我到底看哪张图"这种用户一眼就能判断的事 —— 一旦手选，就不该被下一次定位带走。
     */
    data class Manual(override val campus: SchoolCampus) : CampusPick

    /**
     * 按定位挑的**最近**校区。
     *
     * @param distanceMeters 到那个校区的直线距离（米）
     * @param nearby 是否算"就在校区附近"（[NearbyCampusRadiusMeters] 以内）——
     *   只有这个范围内的定位才敢说"你就在这个校区"，否则文案退成"最近的是…"
     */
    data class ByLocation(
        override val campus: SchoolCampus,
        val distanceMeters: Double,
        val nearby: Boolean,
    ) : CampusPick

    /** 兜底：还没有定位结果 / 没权限 / 拿不到位置 → 显示**主校区**（= 列表第一个）*/
    data class Default(override val campus: SchoolCampus?) : CampusPick
}

/**
 * 多远之内算"就在这个校区"（米）。
 *
 * 取 3 km：校区本身就有 1~2 km 见方，加上定位误差（大致位置时可能误差几公里），
 * 这个半径能把"人在校内"和"人在城里"分开；放宽了会把"在市区别的区"也说成在校区里。
 */
internal const val NearbyCampusRadiusMeters = 3_000.0

/**
 * 决定地图页显示哪个校区 —— **纯函数**（不碰定位、不碰状态），所以能直接单测。
 *
 * 优先级（从高到低）：
 *  1. [manualCampusId] 指定的校区（用户手选过，且它确实属于这所学校）；
 *  2. [location] 最近的校区（[SchoolMap.nearestCampus]）；
 *  3. 主校区（列表第一个）—— 没权限 / 还没定位 / 定位失败都走这里。
 *
 * ★ 手选的校区 id **换学校后自动作废**（[manualCampusId] 不属于新学校时忽略）：
 * 否则从广工的"东风路校区"切到广软，会拿一个广软没有的 id 去查，页面变成空白。
 */
internal fun pickCampus(
    campuses: List<SchoolCampus>,
    manualCampusId: String?,
    location: DeviceLocation?,
): CampusPick {
    val manual = campuses.firstOrNull { it.id == manualCampusId }
    if (manual != null) return CampusPick.Manual(manual)

    if (location != null && campuses.isNotEmpty()) {
        val campus = campuses.minByOrNull {
            distanceMeters(location.latitude, location.longitude, it.latitude, it.longitude)
        }
        if (campus != null) {
            val distance = distanceMeters(location.latitude, location.longitude, campus.latitude, campus.longitude)
            return CampusPick.ByLocation(
                campus = campus,
                distanceMeters = distance,
                nearby = distance <= NearbyCampusRadiusMeters,
            )
        }
    }

    return CampusPick.Default(campuses.firstOrNull())
}
