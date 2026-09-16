package com.tof.manycourse.ui

import android.view.Window

/**
 * 关闭系统对比度保护层，让渐变/玻璃背景真正铺满全屏。
 *
 * API 35 起 `isStatusBarContrastEnforced` / `isNavigationBarContrastEnforced` 已废弃
 * （在 35+ 上为 no-op，系统自行处理），低版本仍需显式关闭，故统一在此处抑制废弃告警，
 * 避免各 Activity 里散落 @Suppress。
 */
@Suppress("DEPRECATION")
fun Window.disableContrastScrims() {
    isStatusBarContrastEnforced = false
    isNavigationBarContrastEnforced = false
}
