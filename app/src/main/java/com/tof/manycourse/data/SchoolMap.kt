package com.tof.manycourse.data

import com.tof.manycourse.R

/**
 * 一所学校的**一个校区**：名字 + 地图图片 + 坐标。
 *
 * ## 为什么需要它
 *
 * 同一所学校可能有**多个校区**（广工 5 个、广软 2 个），每个校区一张平面图 ——
 * "一校一图"那版只能显示主校区，住在别的校区的用户永远看不到自己那张。
 *
 * 坐标的作用只有一个：**按定位挑图**（[SchoolMap.nearestCampus]）。
 * 所以它不需要精确到门口，只要"谁离得近"算得对 —— 但同一所学校的校区之间必须能分辨
 * （单测钉住"任意两个校区至少隔开 2 公里"，防复制粘贴把坐标抄成一样）。
 *
 * @param id          校区短名（小写英文，如 `daxuecheng`）；**只用在校内选择**，不参与持久化
 * @param name        展示名（`大学城校区`）；校区切换条上会省掉「校区」两个字
 * @param drawableRes 地图图片资源（`R.drawable.school_map_<学校id>_<校区id>`）
 * @param latitude    纬度（校区中心，取自 OSM）
 * @param longitude   经度（校区中心，取自 OSM）
 */
data class SchoolCampus(
    val id: String,
    val name: String,
    val drawableRes: Int,
    val latitude: Double,
    val longitude: Double,
)

/**
 * 学校 → **校区地图**。
 *
 * ## 图片放哪儿（重要）
 *
 * 地图原图原本放在 `app/src/main/res/school_map/` 下 —— **那不是合法的资源目录**。
 * Android 的 `res/` 只认固定的一批目录名（`drawable` / `layout` / `values` / `raw` …），
 * 别的名字 AAPT2 直接忽略，所以图片放那儿在代码里是**取不到**的
 * （`R.drawable.xxx` 根本不会生成）。
 *
 * 现在放在 `res/drawable-nodpi/`，命名 `school_map_<学校id>[_<校区id>].jpg`：
 *
 * | 文件 | 对应 |
 * |---|---|
 * | `school_map_nhjcxy.jpg` | 金城学院（单校区） |
 * | `school_map_gzus_guangzhou.jpg` / `_jiangmen.jpg` | 广软 广州校区 / 江门校区 |
 * | `school_map_gdut_daxuecheng.jpg` / `_dongfenglu.jpg` / `_longdong.jpg` / `_panyu.jpg` / `_jieyang.jpg` | 广工 5 个校区 |
 *
 * 两点说明：
 *  - **`-nodpi`**：地图是"整张图缩放查看"的大图，放进 `drawable-nodpi` 后系统
 *    不会按屏幕密度替我们放大（放进 `drawable` 会按 density bucket 放大 2~3 倍，
 *    1280×959 的图会膨胀成 3800+ 像素、白白吃内存）；
 *  - 资源名必须**全小写 + 下划线**（`school_map_gdut_panyu`，不能有驼峰/连字符，
 *    也别拼错成 `shcool_…` —— 拼错了只表现为"取不到图"），这是 AAPT2 的硬性要求。
 *
 * ## 坐标从哪来
 *
 * 各校区中心点取自 **OpenStreetMap**（`amenity=university` 那块的几何中心，误差几十米级，
 * 挑校区完全够用；广软江门校区取的是校门口的公交站点位）。
 * 它们**只**用于"按定位挑最近的校区"，不显示在地图上、也不做导航。
 */
object SchoolMap {

    /**
     * 学校 id → 校区列表。
     *
     * ★ **第一个是主校区（默认显示的那张）**：没定位、没权限、拿不到位置时就用它 ——
     * 所以顺序不是随便排的。键必须是 `gr_api/SchoolRegistry` 里登记的学校 id
     * （拼错了只表现为"地图页空白"，有单测校验）。
     */
    private val campuses: Map<String, List<SchoolCampus>> = mapOf(
        // ── 广东工业大学：5 个校区（大学城是校本部）────────────────────────
        "gdut" to listOf(
            SchoolCampus("daxuecheng", "大学城校区", R.drawable.school_map_gdut_daxuecheng, 23.0417332, 113.3873582),
            SchoolCampus("dongfenglu", "东风路校区", R.drawable.school_map_gdut_dongfenglu, 23.1353836, 113.2947050),
            SchoolCampus("longdong", "龙洞校区", R.drawable.school_map_gdut_longdong, 23.1982394, 113.3541294),
            SchoolCampus("panyu", "番禺校区", R.drawable.school_map_gdut_panyu, 22.9774866, 113.3284096),
            SchoolCampus("jieyang", "揭阳校区", R.drawable.school_map_gdut_jieyang, 22.9827281, 116.3213657),
        ),
        // ── 广州软件学院：广州（从化）与江门两个校区 ────────────────────────
        "gzus" to listOf(
            SchoolCampus("guangzhou", "广州校区", R.drawable.school_map_gzus_guangzhou, 23.4534652, 113.4898836),
            SchoolCampus("jiangmen", "江门校区", R.drawable.school_map_gzus_jiangmen, 22.4168632, 112.9318344),
        ),
        // ── 南京航空航天大学金城学院：单校区（禄口）────────────────────────
        "nhjcxy" to listOf(
            SchoolCampus("lukou", "禄口校区", R.drawable.school_map_nhjcxy, 31.7030146, 118.8790195),
        ),
    )

    /** 已登记地图的学校 id；地图页/测试用它判断"这学校到底有没有图" */
    val schoolIds: Set<String> get() = campuses.keys

    /** 某所学校的全部校区；没登记（或 id 为空）时返回空表（调用方展示空状态，不要崩） */
    fun campusesOf(schoolId: String?): List<SchoolCampus> =
        schoolId?.let { campuses[it] }.orEmpty()

    /**
     * 某所学校的**默认校区** = 列表里的第一个（主校区）。
     *
     * 没定位 / 没权限 / 定位不可用时的兜底，也是 [drawableOf] 的口径。
     */
    fun defaultCampusOf(schoolId: String?): SchoolCampus? = campusesOf(schoolId).firstOrNull()

    /** 按校区 id 取；找不到返回 null（比如换了学校，旧的校区 id 就不属于它了）*/
    fun campusOf(schoolId: String?, campusId: String?): SchoolCampus? {
        if (campusId == null) return null
        return campusesOf(schoolId).firstOrNull { it.id == campusId }
    }

    /** 某所学校的**默认**地图资源（老调用点的口径：没定位时显示主校区那张）*/
    fun drawableOf(schoolId: String?): Int? = defaultCampusOf(schoolId)?.drawableRes

    /**
     * 离 ([latitude], [longitude]) **最近的校区** —— 定位挑图就靠它。
     *
     * 并列（距离完全相同）时取列表里靠前的那个：结果必须稳定，否则同一位置
     * 两次进来可能显示两张图。这所学校没登记地图时返回 null。
     */
    fun nearestCampus(schoolId: String?, latitude: Double, longitude: Double): SchoolCampus? =
        campusesOf(schoolId).minByOrNull { distanceMeters(latitude, longitude, it.latitude, it.longitude) }
}

/**
 * 两点间的**近似距离**（米）。
 *
 * 用等距圆柱近似（把经纬度当平面算，经度按纬度收紧）而不是 Haversine：
 * 这里唯一的用途是"挑最近的校区"，校区之间隔着几公里到几百公里，
 * 这个近似的误差（<1%）比那个量级小两三个数量级 —— 不值得为它多写一段三角函数。
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
    val deltaLongitude = Math.toRadians(longitude2 - longitude1) * kotlin.math.cos(meanLatitude)
    return earthRadius * kotlin.math.hypot(deltaLatitude, deltaLongitude)
}
