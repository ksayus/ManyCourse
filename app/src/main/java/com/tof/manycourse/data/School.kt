package com.tof.manycourse.data

/**
 * 一所学校的教务系统描述 —— **纯数据，不含任何网络实现**。
 *
 * 为什么把「学校」和「接口实现」拆开：
 *  - UI（登录页下拉）只依赖这个模型，加学校时不用碰任何界面代码；
 *  - 网络实现放在 `gr_api/schools/` 下，一所学校一个文件，互不影响；
 *  - 两者由 `gr_api/SchoolRegistry` 组装，那里是整个应用唯一的「加学校」入口。
 *
 * @param id      稳定标识，用于持久化（放在 SharedPreferences 里）。**不允许改**：
 *                改了就相当于把老用户"上次选的学校"清空了。用英文小写短名，如 `gzus`。
 * @param name    展示名（下拉列表里显示的中文全称）
 * @param baseUrl 教务系统根地址，**必须带协议且不带结尾斜杠**，如 `https://jwxt.gzus.edu.cn`；
 *                各接口的完整地址由实现类在这个根地址上拼
 * @param system  教务系统类型备注（仅用于排查问题 / 下拉副标题，不参与业务）
 */
data class School(
    val id: String,
    val name: String,
    val baseUrl: String,
    val system: String = "",
) {
    /** 主机名（去掉协议与结尾斜杠），登录页下拉的副标题用它区分同名学校 */
    val host: String
        get() = baseUrl.substringAfter("://").trimEnd('/')
}
