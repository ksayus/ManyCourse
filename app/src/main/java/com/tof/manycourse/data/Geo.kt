package com.tof.manycourse.data

import kotlin.math.cos
import kotlin.math.hypot

/**
 * 两点间的**近似距离**（米）—— 等距圆柱近似（把经纬度当平面算，经度按纬度收紧）。
 *
 * ## 为什么不上 Haversine
 *
 * 用它的地方都只关心"谁更近 / 差多少公里"这个量级：
 *  - 挑最近的校区（校区之间隔着几公里到几百公里）；
 *  - 认"我现在在哪栋楼"（候选地点之间几十米，而手机定位本身就有几米到几十米误差）。
 *
 * 这个近似的误差在 <1% 量级，比上面那两件事要分辨的尺度小两三个数量级 ——
 * 不值得为它多写一段三角函数。真要算大圆距离时再换，别提前上。
 *
 * ## 为什么单独一个文件
 *
 * 它原先长在 `data/SchoolMap.kt` 里，而那个文件 `import com.tof.manycourse.R`
 * （要拿地图资源）—— 于是"纯几何"被"Android 资源"连坐：任何只用到本函数的逻辑
 * 都没法脱离 Android 编译、也就没法在 JVM 上离线跑（`tools/pack_indoor_data.ps1`
 * 就是这么复用 App 逻辑的）。搬到这个**不 import 任何 android.\*** 的文件里，
 * 同包所以所有调用点与 import 一行都不用改。
 *
 * @return 距离（米）；同一个点返回 0
 */
internal fun distanceMeters(
    latitude1: Double,
    longitude1: Double,
    latitude2: Double,
    longitude2: Double,
): Double {
    val earthRadius = 6_371_000.0
    val meanLatitude = Math.toRadians((latitude1 + latitude2) / 2)
    val deltaLatitude = Math.toRadians(latitude2 - latitude1)
    val deltaLongitude = Math.toRadians(longitude2 - longitude1) * cos(meanLatitude)
    return earthRadius * hypot(deltaLatitude, deltaLongitude)
}
