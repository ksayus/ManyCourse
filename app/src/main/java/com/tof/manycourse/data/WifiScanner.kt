package com.tof.manycourse.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 一次性 Wi-Fi 扫描结果。
 *
 * @param fromCache true = 这是系统**缓存**的上一次扫描结果（`startScan()` 被节流时只能拿到它）。
 *   缓存可能是几分钟前的 —— 用来做"我大概在哪个区域"够用，但界面要如实标一句，
 *   否则用户会把"我站在 A 栋却显示 B 栋"当成 bug。
 */
internal data class WifiScanResult(
    val aps: Map<String, Int>,
    val fromCache: Boolean,
)

/**
 * 扫一次 Wi-Fi 并返回 `bssid → rssi`。
 *
 * 与 `WalkRecorder` 里那套（注册广播 + 定时 `startScan`）分开的原因：那边是**连续采集**
 * （一走走半小时、还要处理节流退避），这边是**点一下要个结果**（一次定位请求）。
 * 两者的生命周期完全不同，混在一起只会互相拖累。
 *
 * 节流处理：`startScan()` 被拒（前台 2 分钟 4 次）时**退回缓存的扫描结果**并标记
 * `fromCache = true`，而不是返回"扫不到" —— 缓存结果对区域级定位照样有用。
 *
 * @return null = 没有 Wi-Fi 服务 / 没有权限 / Wi-Fi 关着 / 连缓存都没有
 */
internal suspend fun scanWifiOnce(
    context: Context,
    timeoutMs: Long = 6_000L,
): WifiScanResult? {    if (!WifiScanner.hasPermission(context)) return null
    val manager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
    if (!runCatching { manager.isWifiEnabled }.getOrDefault(false)) return null

    val started = requestWifiScan(manager)
    if (!started) {
        // 被节流：能用缓存就用缓存，并如实标记
        return cachedScan(manager)?.let { WifiScanResult(it, fromCache = true) }
    }

    val fresh = withTimeoutOrNull(timeoutMs) { awaitScanResults(context, manager) }
    return fresh?.let { WifiScanResult(it, fromCache = false) }
        ?: cachedScan(manager)?.let { WifiScanResult(it, fromCache = true) }
}

/** 有没有扫描所需的权限（粗略或精确定位任一；Android 10 起扫描结果里的 BSSID 算位置信息）*/
internal object WifiScanner {
    fun hasPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * 请求一次扫描。
 *
 * `startScan()` 在 API 28 起被标记为 deprecated（因为前台应用本来就被节流），
 * 但它**仍然是唯一能主动触发扫描的入口** —— 收在这里一处，抑制警告也只在这一处。
 */
@Suppress("DEPRECATION")
internal fun requestWifiScan(manager: WifiManager): Boolean =
    runCatching { manager.startScan() }.getOrDefault(false)

/**
 * 读系统**缓存**的上一次扫描结果（`startScan()` 被节流时的退路）。
 *
 * ★ `@SuppressLint("MissingPermission")`：[scanWifiOnce] 开头已经查过权限，
 * 而这里还包了 `runCatching`（权限被中途撤销只会得到 null，不会崩）——
 * lint 看不穿"跨方法的检查"，所以在这一处抑制。
 */
@SuppressLint("MissingPermission")
private fun cachedScan(manager: WifiManager): Map<String, Int>? {
    val results = runCatching { manager.scanResults }.getOrNull().orEmpty()
    if (results.isEmpty()) return null
    return results
        .mapNotNull { result -> result.BSSID?.let { bssid -> bssid to result.level } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, levels) -> levels.max() }
}

/**
 * 等 `SCAN_RESULTS_AVAILABLE` 广播（扫描请求发出后系统扫完就会发）。
 *
 * 理由同 [cachedScan]：权限在 [scanWifiOnce] 里查过，且这里对系统调用都包了 `runCatching`。
 */
@SuppressLint("MissingPermission")
private suspend fun awaitScanResults(
    context: Context,
    manager: WifiManager,
): Map<String, Int>? = suspendCancellableCoroutine { continuation ->
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            val results = runCatching { manager.scanResults }.getOrNull().orEmpty()
            val aps = results
                .mapNotNull { result -> result.BSSID?.let { bssid -> bssid to result.level } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, levels) -> levels.max() }
            runCatching { context.applicationContext.unregisterReceiver(this) }
            if (continuation.isActive) continuation.resume(aps.ifEmpty { null })
        }
    }
    continuation.invokeOnCancellation {
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
    }
    val registered = runCatching {
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        true
    }.getOrDefault(false)
    if (!registered && continuation.isActive) continuation.resume(null)
}

/**
 * 用一次扫描 + 指纹库算出"我大概在图上哪儿"。
 *
 * @return null = 定不了位（库里没这个校区的指纹 / 共同 AP 太少）—— 页面据此**如实说定不了**，
 *   而不是给一个随机位置
 */
internal fun locateByWifi(
    campusId: String?,
    scan: Map<String, Int>,
): WifiFix? = locateByFingerprint(scan, WifiFingerprintStore.samplesFor(campusId))
