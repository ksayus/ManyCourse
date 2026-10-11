package com.tof.manycourse

import com.tof.manycourse.data.formatDistance
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

/**
 * 「还有多久」文案的单测。
 *
 * 这条文案出现在**桌面小组件**上 —— 用户最常看、也最容易被一个小错误毁掉信任的地方
 * （写「还有 0 分钟」看着像坏了；写「还有 378 分钟」得自己除 60）。
 */
class FormatDistanceTest {

    @Test
    fun `不到一分钟不说 0 分钟`() {
        assertEquals("不到 1 分钟", formatDistance(Duration.ZERO))
        assertEquals("不到 1 分钟", formatDistance(Duration.ofSeconds(30)))
        assertEquals("不到 1 分钟", formatDistance(Duration.ofSeconds(59)))
    }

    @Test
    fun `一小时内只报分钟`() {
        assertEquals("1 分钟", formatDistance(Duration.ofMinutes(1)))
        assertEquals("45 分钟", formatDistance(Duration.ofMinutes(45)))
        assertEquals("59 分钟", formatDistance(Duration.ofMinutes(59)))
    }

    /** ★ 这条就是要修的那个 case：378 分钟 = 6 小时 18 分钟 */
    @Test
    fun `超过一小时要说清小时`() {
        assertEquals("6 小时 18 分钟", formatDistance(Duration.ofMinutes(378)))
        assertEquals("1 小时 1 分钟", formatDistance(Duration.ofMinutes(61)))
        assertEquals("2 小时 59 分钟", formatDistance(Duration.ofMinutes(179)))
    }

    /** 整点小时省掉「0 分钟」：`2 小时` 比 `2 小时 0 分钟` 顺眼 */
    @Test
    fun `整点小时不报零分钟`() {
        assertEquals("1 小时", formatDistance(Duration.ofMinutes(60)))
        assertEquals("2 小时", formatDistance(Duration.ofMinutes(120)))
        assertEquals("25 小时", formatDistance(Duration.ofHours(25)))
    }

    /** 跨天也照样算（小组件会看未来 7 天），不做"多少天"的换算 —— 那会让量级反而变模糊 */
    @Test
    fun `跨天仍是小时`() {
        assertEquals("73 小时 30 分钟", formatDistance(Duration.ofMinutes(73 * 60 + 30)))
    }
}
