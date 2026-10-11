package com.tof.manycourse.notify

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 通知的**展示档位**。
 *
 * 三档是**叠加**关系，不是互斥的产品分支：通知本体永远是标准 Android 通知，
 * 档位只决定"要不要额外贴一层小米的展示参数"。这样任何一档失效，
 * 退回去的仍然是"用户能收到提醒"，而不是"什么都没有"。
 */
enum class PresentationTier {
    /** 普通 Android 通知（所有机型）。**这一档是基线，永远可用** */
    Standard,

    /**
     * 小米**焦点通知**（HyperOS 2 / OS2 形态）。
     *
     * 展示位置：状态栏、通知中心、锁屏、息屏、小折叠外屏。
     * 参数：`notification.extras["miui.focus.param"]`（见 [FocusParam]）。
     */
    Focus,

    /**
     * 小米**超级岛**（HyperOS 3 / OS3 形态）。
     *
     * 展示位置：岛摘要态、岛展开态，外加焦点通知除状态栏外的其它位置。
     * 由社区库 `hyperisland_kit` 生成 `param_island` 等岛上专用参数。
     *
     * ★ 这一档在本项目**无法承诺**：见 [VendorCapability] 的注释。
     */
    Island,
}

/**
 * 探测到的**设备事实**（纯数据，便于单测判定矩阵，不依赖任何 Android API）。
 *
 * @param manufacturer `Build.MANUFACTURER`（这台 Redmi 上报的是 `Xiaomi`）
 * @param brand        `Build.BRAND`（上报的是 `Redmi`）
 * @param focusProtocol 系统设置项 `notification_focus_protocol`：
 *   0 = 不支持，1 = OS1，2 = OS2，3 = OS3
 * @param islandFlag   系统属性 `persist.sys.feature.island` 是否存在且为 true
 */
data class VendorFacts(
    val manufacturer: String,
    val brand: String,
    val focusProtocol: Int,
    val islandFlag: Boolean,
)

/**
 * **小米展示能力的运行时探测**。
 *
 * ## 为什么必须运行时探测，而不是按机型硬编码
 *
 * 小米官方版本页写的是"暂不限制机型，以系统版本为主"，但产品页又注明
 * "超级岛目前仅支持部分机型和部分应用" —— 两句话都成立，所以**任何静态名单都是错的**。
 * 只能现场问系统三件事：
 *
 * | 问什么 | 怎么问 | 答什么 |
 * |---|---|---|
 * | 是不是小米机型 | `Build.MANUFACTURER` / `Build.BRAND` | 决定要不要走小米私有参数 |
 * | 焦点通知协议版本 | `Settings.System["notification_focus_protocol"]` | 0/1=不支持，2=OS2 焦点通知，3=OS3（且支持岛） |
 * | 系统是否声明支持岛 | `persist.sys.feature.island` | 与上一项**同时**成立才可能上岛 |
 *
 * 三个问题都是小米官方开发指南里给的查询接口（反射读 `android.os.SystemProperties`
 * 也是官方示例本身的做法）。
 *
 * ## ★ 本项目**不承诺**上岛，只承诺"试过了"
 *
 * 小米这套能力是**受平台准入控制**的：官方接入流程要求企业开发者认证、
 * 逐场景预审 + 正式审核、配置证书指纹、设备白名单（30 天 / 最多 10 台）、
 * 上线验证后才有正式权限。本应用是**个人侧载**（GitHub Releases），拿不到这个准入。
 *
 * 所以真实的期望值是这样的：
 *
 * ```
 *   装了本 App 的小米手机
 *     ├─ 系统声明支持岛 + 应用拿到焦点通知权限 → 走社区库试一次（不保证显示）
 *     ├─ 系统是 OS2（协议版本 2）            → 贴焦点通知参数（这台测试机就是这一档）
 *     └─ 其它（含所有非小米机型）             → 只发标准通知，不掺任何私货
 * ```
 *
 * 设置页会把**探测到的原始事实**直接写出来，让用户自己看到"这台机器为什么是这一档"，
 * 而不是让 App 说一句"已开启超级岛"却没有证据。
 */
object VendorCapability {

    /** 系统设置里那个协议版本键（小米官方开发指南给的查询方式）*/
    private const val FOCUS_PROTOCOL_KEY = "notification_focus_protocol"

    /** 系统属性：这台设备声明自己支持岛 */
    private const val ISLAND_PROPERTY = "persist.sys.feature.island"

    /**
     * 起点定在 **2（OS2）**：
     * 官方 Q&A 明确说 OS1 当前阶段不建议接入，所以协议版本 1 对我们等于"不支持"——
     * 按 1 去贴参数只会得到一个在 OS1 上不被识别的 extra。
     */
    const val MIN_FOCUS_PROTOCOL = 2

    /** 读一次设备事实。全部读失败时退化成"什么都不知道"（= 标准通知），不抛异常 */
    fun probe(context: Context): VendorFacts = VendorFacts(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        brand = Build.BRAND.orEmpty(),
        focusProtocol = readFocusProtocol(context),
        islandFlag = readIslandFlag(),
    )

    /**
     * **纯判定**：设备事实 + "社区库自己认为可用吗" → 最终档位。
     *
     * @param islandUsable 由 `HyperIslandNotification.isSupported(context)` 传进来
     *   （它是第三方库的判断，除了机型/属性还要求**应用已拿到焦点通知权限**）。
     *   之所以由外面传进来而不是在这里调库：这个函数要保持纯的，才能用单测把矩阵钉死。
     */
    fun tierOf(facts: VendorFacts, islandUsable: Boolean): PresentationTier = when {
        isXiaomiFamily(facts) && facts.islandFlag && islandUsable -> PresentationTier.Island
        facts.focusProtocol >= MIN_FOCUS_PROTOCOL -> PresentationTier.Focus
        else -> PresentationTier.Standard
    }

    /**
     * 是不是小米系机型（小米 / Redmi / POCO）。
     *
     * **两个字段都看**：这台测试机 `MANUFACTURER = Xiaomi` 而 `BRAND = Redmi`，
     * 不同机型/不同 ROM 版本上这两个字段谁是谁并不固定，只看一个会漏。
     */
    fun isXiaomiFamily(facts: VendorFacts): Boolean =
        listOf(facts.manufacturer, facts.brand).any { it.equals("xiaomi", true) } ||
            facts.brand.equals("redmi", true) ||
            facts.brand.equals("poco", true)

    private fun readFocusProtocol(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, FOCUS_PROTOCOL_KEY, 0)
    }.getOrDefault(0)

    /**
     * 反射读 `persist.sys.feature.island`。
     *
     * `android.os.SystemProperties` 是隐藏 API，正常编译期拿不到，所以走反射 ——
     * 这也是小米官方开发指南给出的示例做法。
     * 读不到（非小米 ROM 压根没这个属性）一律当 false，不抛异常。
     */
    private fun readIslandFlag(): Boolean = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod(
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.invoke(null, ISLAND_PROPERTY, false) as? Boolean ?: false
    }.getOrDefault(false)
}
