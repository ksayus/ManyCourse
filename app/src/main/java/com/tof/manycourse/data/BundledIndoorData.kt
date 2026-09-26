package com.tof.manycourse.data

import android.content.Context
import android.util.Log

/**
 * **随正式版下发的室内数据**（点位、锚点、Wi-Fi 指纹）。
 *
 * ## 它是"走几圈 → 打进正式版"这条链的最后一环
 *
 * ```
 *   你拿着手机走几圈（开发者模式「轨迹采集」）
 *        ↓  自动落到 /sdcard/ManyCourse/{walk-*.txt, mappoints.txt, wifi.txt}
 *   tools/pack_walk_data.ps1  ← 把这几份拷进 app/src/main/assets/indoor/
 *        ↓  重新构建
 *   这一版 APK 自带数据：**新用户不用走，打开就有点位与指纹**
 * ```
 *
 * ## 为什么放 assets 而不是"让 App 自己写进安装包"
 *
 * 应用改不了自己的 APK（除非自更新），所以"打进正式版"这一步必然发生在**构建之前**：
 * 设备上是采集与导出，构建时才是打包。脚本只做拷贝，**合并与去重全部在 App 里做**
 * （`MapPointStore` / `WifiFingerprintStore`）—— 逻辑留在有单测的 Kotlin 里，
 * 脚本保持成"十行拷贝"，不做格式解析。
 *
 * ## 目录约定
 *
 * `app/src/main/assets/indoor/` 下的**任意文件**都会被读一遍，按头部识别类型：
 *  - `manycourse-mappoints/1` → 点位 + 锚点；
 *  - `manycourse-wifi/1` → Wi-Fi 指纹。
 *
 * 认不出的文件跳过（不是错误：可能放了说明文档）。文件不存在时返回空数据，
 * 一切照旧跑 —— 没有内置数据不是异常情况。
 */
internal object BundledIndoorData {

    private const val TAG = "BundledIndoorData"

    /** assets 下的目录名（`tools/pack_walk_data.ps1` 会往这里拷）*/
    const val ASSET_DIR = "indoor"

    data class Payload(
        val points: List<MapPoint> = emptyList(),
        val anchors: List<MapAnchor> = emptyList(),
        val fingerprints: List<WifiFingerprint> = emptyList(),
    ) {
        val isEmpty: Boolean get() = points.isEmpty() && anchors.isEmpty() && fingerprints.isEmpty()
    }

    fun load(context: Context): Payload {
        val names = runCatching { context.assets.list(ASSET_DIR)?.toList().orEmpty() }
            .getOrElse {
                // assets 里没有这个目录是很正常的状态（还没打过包）
                return Payload()
            }
        if (names.isEmpty()) return Payload()

        val points = mutableListOf<MapPoint>()
        val anchors = mutableListOf<MapAnchor>()
        val fingerprints = mutableListOf<WifiFingerprint>()

        names.forEach { name ->
            val text = runCatching {
                context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
            }.getOrElse {
                Log.w(TAG, "内置数据 $name 读不出来：${it.message}")
                return@forEach
            }

            when {
                MapPointCodec.hasOurHeader(text) -> MapPointCodec.decode(text)?.let { file ->
                    points += file.points
                    anchors += file.anchors
                }

                WifiFingerprintCodec.hasOurHeader(text) -> WifiFingerprintCodec.decode(text)?.let {
                    fingerprints += it
                }

                WalkLogCodec.hasOurHeader(text) ->
                    Log.i(TAG, "$name 是原始轨迹记录，不直接入库（先用 App 融合成点位/指纹再打包）")

                else -> Log.i(TAG, "$name 头部不认识，跳过")
            }
        }

        return Payload(points = points, anchors = anchors, fingerprints = fingerprints)
    }
}
