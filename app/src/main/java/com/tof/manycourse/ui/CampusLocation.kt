package com.tof.manycourse.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.tof.manycourse.data.DeviceLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 校园地图页的**定位**：取一次性位置，用来挑校区（见 `data/CampusPicker.kt`）。
 *
 * ## 三条刻意定下的规矩
 *
 *  1. **只用系统 `LocationManager`，不引第三方定位 SDK**：国内设备普遍没有 Google Play 服务，
 *     `FusedLocationProviderClient` 在这些机器上根本不可用；框架自带的
 *     `getCurrentLocation()`（API 30+，本应用 minSdk 31）就够取"一次"位置。
 *  2. **只在前台取一次**：没有前台服务、没有后台定位（Manifest 里也没声明
 *     `ACCESS_BACKGROUND_LOCATION`）—— 地图页不需要持续跟踪位置。
 *  3. **拿不到就说拿不到**：超时 / 没有 provider / 用户拒绝一律返回 null，
 *     由页面回落到"主校区 + 手动切换"，绝不编一个坐标出来挑图。
 */
internal object CampusLocation {

    /**
     * 取一次定位的等待上限（毫秒）。
     *
     * 8 秒是"够 GPS 冷启动出一个粗定位、又不至于让用户盯着转圈"的折中：
     * 超时就当拿不到（页面会解释一句、并允许手动选校区），
     * 而不是让页面一直停在"正在定位…"。
     */
    const val TIMEOUT_MS = 8_000L

    /**
     * 有没有定位权限。
     *
     * ★ 精确**或**大致**任一**即可：Android 12 起用户可以在弹窗里只给"大致位置"，
     * 那时 `ACCESS_FINE_LOCATION` 是 denied、`ACCESS_COARSE_LOCATION` 是 granted ——
     * 只看 FINE 会误判成"用户拒绝了"，于是白弹一次权限框、还把提示写成"未授权"。
     */
    fun hasPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 取一次位置；没有权限、没有可用 provider（定位总开关关着 / 飞行模式）、超时都返回 null。
     *
     * @param timeoutMs 等待上限，默认 [TIMEOUT_MS]
     */
    suspend fun currentLocation(
        context: Context,
        timeoutMs: Long = TIMEOUT_MS,
    ): DeviceLocation? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = bestProvider(context, manager) ?: return null
        val location = withTimeoutOrNull(timeoutMs) { awaitOneFix(context, manager, provider) } ?: return null
        return DeviceLocation(latitude = location.latitude, longitude = location.longitude)
    }

    /**
     * 挑一个**当前可用**的 provider。
     *
     * 顺序 = 从"最省电、最不挑权限"到"最准但要求精确权限"：
     *  - `fused`：系统融合定位（API 31+ 的框架 provider；没有 Play 服务的机器上可能不可用，所以要试一下）；
     *  - `network`：基站 / WiFi 定位，**只有"大致位置"权限时也能用**；
     *  - `gps`：只在用户给了**精确**位置时才带上 —— 只有大致权限时用 GPS provider
     *    会直接抛 `SecurityException`（"provider requires ACCESS_FINE_LOCATION"）。
     *
     * @return 可用的 provider 名；一个都没有返回 null
     */
    private fun bestProvider(context: Context, manager: LocationManager): String? {
        val precise = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val candidates = buildList {
            add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (precise) add(LocationManager.GPS_PROVIDER)
        }
        // `isProviderEnabled` 在不支持 / 无权限的 provider 上会抛，所以逐个兜住
        return candidates.firstOrNull { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    /**
     * 等一次位置回调。
     *
     * `getCurrentLocation` 自己有缓存就直接回调，没缓存才真去定位；传进去的
     * [CancellationSignal] 在协程被取消（= 超时）时 `cancel()`，**不留后台定位**。
     * 服务端给不出位置时回调参数会是 null，这里如实返回 null。
     */
    private suspend fun awaitOneFix(
        context: Context,
        manager: LocationManager,
        provider: String,
    ): Location? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        runCatching {
            manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }.onFailure {
            // 没权限 / provider 被关掉：当成"拿不到位置"，让页面走兜底
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
