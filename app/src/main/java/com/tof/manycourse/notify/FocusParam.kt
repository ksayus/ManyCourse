package com.tof.manycourse.notify

/**
 * 小米**焦点通知**（HyperOS 2 / OS2）的 `miui.focus.param` 载荷。
 *
 * ## 为什么这一段是自己拼 JSON，而不是用社区库
 *
 * 社区库 `hyperisland_kit` 的 `buildResourceBundle()` **一进门就查 `isSupported()`**，
 * 不满足（比如这台机器没有岛、或没拿到焦点通知权限）就直接返回空 Bundle ——
 * 它的定位是"超级岛模板库"，不是"焦点通知库"。
 *
 * 而焦点通知这一档恰恰是**最需要它可靠**的一档：它是超级岛试不成之后的回退路线，
 * 也是所有 OS2 小米机型上唯一真正能显示出来的那一档。所以它**不能依赖第三方库的可用性判断**，
 * 只按小米官方开发指南的字段自己拼 —— 字段少、结构浅，拼法本身是稳定可测的。
 *
 * 顺带与项目一贯做法一致：仓库里没有 Retrofit / codegen / kotlinx-serialization，
 * `ScheduleSnapshotCodec` / `CookieCodec` / `SessionBlobCodec` 都是手写编解码 + 单测钉住。
 *
 * ## 载荷结构（官方开发指南「基础数据构建」）
 *
 * ```json
 * { "param_v2": {
 *     "protocol": 1,                       // 协议版本，固定 1
 *     "business": "manycourse_class_reminder",
 *     "enableFloat": true,                 // 首次出现时展开
 *     "updatable": true,                   // 允许用同一个 id 更新
 *     "timeout": 30,                       // 30 分钟后自动消失（单位：分钟）
 *     "baseInfo": {                        // ← 焦点通知真正显示的内容
 *       "type": 1,                         // 1 = 标准布局
 *       "title": "还有 15 分钟上课",
 *       "content": "高等数学 · 第1-2节 9:00~10:20 · A-101"
 *     }
 * } }
 * ```
 *
 * 写进 `notification.extras` 的键是 [EXTRA_KEY]。
 *
 * ## 一件值得说明的事：`filterWhenNoPermission` 我们**没有**打开
 *
 * 官方字段表里它默认 false，含义是"应用焦点通知权限被关闭时，通知正常显示、不被过滤"。
 * 默认值正是我们想要的：焦点通知参数只是**增强**，权限没了就退回普通通知，
 * 而不是把整条提醒吞掉 —— 提醒本身比"好看"重要得多。
 */
object FocusParam {

    /** `notification.extras` 里放 JSON 的那个键（小米官方定的名字）*/
    const val EXTRA_KEY = "miui.focus.param"

    /** 协议版本，官方示例固定写 1 */
    private const val PROTOCOL = 1

    /**
     * 业务标识。
     *
     * 官方字段表把它描述为"运营场景（数据统计）"，超级岛接入流程里还要按场景审核。
     * 我们**没有**通过审核（个人侧载拿不到准入），所以这里只是给系统一个稳定的字符串，
     * 不代表任何已获批的场景 —— 这一点写在这里，免得后来人误以为它有用。
     */
    const val BUSINESS = "manycourse_class_reminder"

    /** 焦点通知自动消失时间（分钟）。默认 720 太长（半天），一条课前提醒留 30 分钟足够 */
    const val DEFAULT_TIMEOUT_MINUTES = 30

    /**
     * 生成 `miui.focus.param` 的 JSON 字符串。
     *
     * @param title 通知标题（如「还有 15 分钟上课」）
     * @param content 通知正文（课程名 / 节次 / 时刻 / 教室）
     */
    fun json(title: String, content: String, timeoutMinutes: Int = DEFAULT_TIMEOUT_MINUTES): String =
        buildString(title.length + content.length + 192) {
            append("{\"param_v2\":{")
            append("\"protocol\":").append(PROTOCOL).append(',')
            append("\"business\":\"").append(escape(BUSINESS)).append("\",")
            append("\"enableFloat\":true,")
            append("\"updatable\":true,")
            append("\"timeout\":").append(timeoutMinutes.coerceAtLeast(1)).append(',')
            append("\"baseInfo\":{")
            append("\"type\":1,")
            append("\"title\":\"").append(escape(title)).append("\",")
            append("\"content\":\"").append(escape(content)).append('"')
            append("}}}")
        }

    /**
     * JSON 字符串转义。
     *
     * 这个函数**必须存在**，不能图省事直接拼：课程名和教室是教务系统给的原始文本，
     * 里面完全可能有英文双引号（`"听说" 课`）、反斜杠、或者从 HTML 带过来的换行。
     * 不转义的话生成的是一段**坏 JSON** —— 系统解析失败后既不上岛也不报错，
     * 表现就是"焦点通知时灵时不灵"，而且只在特定课程名上出现，极难查。
     *
     * 中文/emoji **不转义**：JSON 允许直接放 UTF-8 字符，
     * 而 `extras.putString` 传的就是 UTF-8，转成 `\uXXXX` 只是白白变长。
     */
    internal fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                // 其余控制字符（含 0x00~0x1F）一律转义：它们在 JSON 里本就不该裸着出现
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
}
