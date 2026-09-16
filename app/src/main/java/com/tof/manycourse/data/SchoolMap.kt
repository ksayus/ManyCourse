package com.tof.manycourse.data

import com.tof.manycourse.R

/**
 * 学校 → 校园地图图片的映射。
 *
 * ## 图片放哪儿（重要）
 *
 * 地图原图原本放在 `app/src/main/res/school_map/` 下 —— **那不是合法的资源目录**。
 * Android 的 `res/` 只认固定的一批目录名（`drawable` / `layout` / `values` / `raw` …），
 * 别的名字 AAPT2 直接忽略，所以图片放那儿在代码里是**取不到**的
 * （`R.drawable.xxx` 根本不会生成）。
 *
 * 已经挪到 `res/drawable-nodpi/`，并改成小写下划线命名：
 *
 * | 原文件 | 现在 | 对应学校 |
 * |---|---|---|
 * | `res/school_map/nhjc_school_map.jpg` | `res/drawable-nodpi/school_map_nhjcxy.jpg` | `nhjcxy` 金城学院 |
 * | `res/school_map/gr_school_map.jpg` | `res/drawable-nodpi/school_map_gzus.jpg` | `gzus` 广州软件学院 |
 *
 * 两点说明：
 *  - **`-nodpi`**：地图是"整张图缩放查看"的大图，放进 `drawable-nodpi` 后系统
 *    不会按屏幕密度替我们放大（放进 `drawable` 会按 density bucket 放大 2~3 倍，
 *    1280×959 的图会膨胀成 3800+ 像素、白白吃内存）。
 *  - 资源名必须**全小写 + 下划线**（`school_map_nhjcxy`，不能有驼峰/连字符），
 *    这是 AAPT2 的硬性要求。
 *
 * ## 加一所学校的地图
 *
 * 1. 把图片按 `<学校id>.jpg`（小写）放进 `res/drawable-nodpi/`，名字建议 `school_map_<学校id>`；
 * 2. 在下面 [drawables] 里加一行。
 *
 * 没登记地图的学校不会崩：地图页会显示"这所学校还没有地图"的提示
 * （有 `SchoolMapTest` 守着"登记了的都能解析到资源"）。
 */
object SchoolMap {

    /**
     * 学校 id → 地图资源。
     *
     * 键必须是 `gr_api/SchoolRegistry` 里登记的学校 id（也是持久化主键），
     * 拼错了只会表现为"地图页空白"，所以有单测校验键的合法性。
     */
    private val drawables: Map<String, Int> = mapOf(
        "nhjcxy" to R.drawable.school_map_nhjcxy,
        "gzus" to R.drawable.school_map_gzus,
    )

    /** 已登记地图的学校 id；地图页/测试用它判断"这学校到底有没有图" */
    val schoolIds: Set<String> get() = drawables.keys

    /** 取某所学校的地图；没有登记返回 null（调用方展示空状态，不要崩） */
    fun drawableOf(schoolId: String?): Int? = schoolId?.let { drawables[it] }
}
