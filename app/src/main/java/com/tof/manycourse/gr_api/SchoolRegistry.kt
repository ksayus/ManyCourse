package com.tof.manycourse.gr_api

import com.tof.manycourse.data.School
import com.tof.manycourse.gr_api.schools.GzusApi
import com.tof.manycourse.gr_api.schools.NhjcxyApi

/**
 * ★★★ 学校清单 —— 整个应用**唯一**的「加一所学校」入口 ★★★
 *
 * 新增学校的完整步骤（不需要改任何 UI 代码）：
 *  1. 复制 `gr_api/schools/GzusApi.kt`（或 `NhjcxyApi.kt`）为 `XxxApi.kt`，
 *     改 `school`（id 用小写英文短名，**定了就别再改**，它是持久化主键）、
 *     改 `loginUrl` / `usernameField` / `passwordField` 三个登录参数；
 *  2. 在下面 [apis] 的 `listOf(...)` 里加一行 `XxxApi(),`。
 *
 * 完成后自动生效的地方：
 *  - 登录页的学校下拉（顺序 = `listOf` 顺序）；
 *  - 上次选择的学校持久化 / 恢复；
 *  - 点登录时按选中的学校分发到对应实现。
 *
 * 详细抓包方法、改动与调用方式见 `docs/教务API接入与调用指南.md`。
 */
object SchoolRegistry {

    /**
     * 全部学校实现。**顺序即登录页下拉顺序**（建议把"待接入"的放后面）。
     *
     * 注意：这里是 `object` 的初始化代码，会在首次访问时构造所有 API 实例；
     * 每个实例只持有一个 OkHttpClient + 空 Cookie，开销可以忽略，不要改成懒加载把事情搞复杂。
     */
    private val apis: List<SchoolApi> = listOf(
        GzusApi(),
        NhjcxyApi(),
    )

    /** 给 UI 用的纯数据清单（登录页下拉直接遍历它） */
    val schools: List<School> = apis.map { it.school }

    /** 按 id 取接口实现；id 为空或不存在时返回 null（调用方负责提示「请选择学校」） */
    fun apiOf(schoolId: String?): SchoolApi? = apis.firstOrNull { it.school.id == schoolId }

    /**
     * 按 id 取学校。和 [apiOf] 分开是为了 UI 不必依赖 `gr_api` 包：
     * 登录页只需要 [School] 这个纯数据模型。**已下架的学校 id 会返回 null**，
     * 登录页据此回落到"未选择"，而不是显示一个已经不存在的学校。
     */
    fun find(schoolId: String?): School? = apiOf(schoolId)?.school
}
