package com.tof.manycourse.data

/**
 * 「今日课程」在地图上的归类结果 —— [placeCoursesOnMap] 的结论。
 *
 * 分成四类而不是"能画的 / 不能画的"两类的理由：**不能画的原因不一样，用户要做的事也不一样**：
 *
 * | 分类 | 原因 | 用户要做什么 |
 * |---|---|---|
 * | [OnMap] | —— | 什么都不用做，标记已经在图上 |
 * | [NotCollected] | 这个教室还没有点位 | 走到那栋楼，开发者模式里「原地采点」 |
 * | [OffImage] | 有点位，但落不到图上（没有标定 / 点在图外） | 去**标定**这个校区（至少 2 个锚点） |
 * | [OtherCampus] | 点位在**另一个校区** | 切到那个校区看（不是数据缺失） |
 *
 * 混成一句"有 N 门课没显示"就等于什么都没说：用户不知道该去采点、该去标定、还是该切校区。
 */
internal sealed interface CoursePlacement {

    /** 对应的那门课 */
    val entry: CourseEntry

    /** 能画在**当前显示的校区**图上 */
    data class OnMap(
        override val entry: CourseEntry,
        val imageX: Float,
        val imageY: Float,
        /** 匹配到的点位名（和课程地点可能差几个字符，所以显示原样）*/
        val pointName: String,
    ) : CoursePlacement

    /** 匹配到了点位，但那个点位属于另一个校区 */
    data class OtherCampus(
        override val entry: CourseEntry,
        val campusId: String,
        val pointName: String,
    ) : CoursePlacement

    /** 这个教室还没有点位（最常见的一种）*/
    data class NotCollected(override val entry: CourseEntry) : CoursePlacement

    /** 有点位，但算不出图上位置：没标定，或者投影结果在图片范围之外 */
    data class OffImage(override val entry: CourseEntry, val pointName: String) : CoursePlacement
}

/**
 * ★ **把"今天上什么课"分配到图上** —— 纯函数（点位、标定、校区都是入参），所以能单测。
 *
 * 规则：
 * ```
 *   课表里的地点  --matchPointForRoom-->  点位
 *         没有点位 → NotCollected（要去采）
 *         点位在别的校区 → OtherCampus（切校区就能看）
 *         点位有位置且在图内 → OnMap（画标记）
 *         有点位但算不出图上位置 → OffImage（要去标定）
 * ```
 *
 * 为什么"没有点位"和"没有标定"要分开报：前者要**出门走一趟**，后者只要**在图上点两下**，
 * 成本差一个数量级；混在一起用户只会觉得"这功能没用"。
 *
 * @param calibration 当前显示校区的标定；没有标定时 GPS 采的点位一律落不到图上
 * @param campusId 当前**显示**的那个校区（`SchoolCampus.id`）
 */
internal fun placeCoursesOnMap(
    entries: List<CourseEntry>,
    points: List<MapPoint>,
    calibration: MapCalibration?,
    campusId: String?,
): List<CoursePlacement> = entries.map { entry ->
    val point = matchPointForRoom(entry.room, points)
    when {
        point == null -> CoursePlacement.NotCollected(entry)

        campusId != null && point.campusId != campusId ->
            CoursePlacement.OtherCampus(entry, point.campusId, point.name)

        else -> {
            val placement = imagePlacementOf(point, calibration)
            if (placement == null || !isInsideImage(placement.first, placement.second)) {
                CoursePlacement.OffImage(entry, point.name)
            } else {
                CoursePlacement.OnMap(entry, placement.first, placement.second, point.name)
            }
        }
    }
}
