package com.tof.manycourse.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tof.manycourse.ui.MainTabStore

/**
 * 当前标签页是否处于"可见且在前台"状态，用于冻结/恢复液态玻璃背景动画。
 *
 * 三个 Fragment 常驻（hide/show 切换）：被 hide 的页面视图虽不绘制，但其
 * Compose 无限动画默认仍在逐帧驱动重组，多页同时空转白白消耗主线程。
 * 此处把"当前 tab + 生命周期至少 STARTED"合成一个门控标志。
 *
 * 性能要点：[isCurrentTab] 用 derivedStateOf 包住——切换标签时**只有可见性真正
 * 变化的那一页**会因此重组；另外两页布尔值没变，Compose 不会向下游传播失效，
 * 避免"切一次 Tab 三张全屏页面一起重组"造成切换瞬间掉帧。
 */
@Composable
fun isTabForeground(tabIndex: Int): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current

    var lifecycleState by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleState = lifecycleOwner.lifecycle.currentState
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isCurrentTab by remember(tabIndex) {
        derivedStateOf { MainTabStore.currentIndex.intValue == tabIndex }
    }

    return isCurrentTab && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
}
