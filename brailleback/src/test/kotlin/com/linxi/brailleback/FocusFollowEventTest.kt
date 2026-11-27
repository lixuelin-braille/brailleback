/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统无障碍焦点事件该不该跟。
 *
 * 要同时满足两个真机诉求：
 * 1. 翻页/滚屏后不跟：系统会把焦点重置到新一屏第一行，跟了就是跳两下、停首行。
 * 2. 用手指滑读屏时照常跟：那和滚动无关，不能被冷却窗口误伤。
 */
class FocusFollowEventTest {

    @Test
    fun `滚动更新未落定时不跟`() {
        assertFalse(
                "跟了会先闪错帧，settle 后再闪对帧，摸起来是跳两下",
                shouldFollowFocusEvent(scrollPending = true, sinceScrollMillis = 10_000L))
    }

    @Test
    fun `滚动后冷却窗口内不跟`() {
        assertFalse(
                "系统自动重置到首行就发生在这段时间里",
                shouldFollowFocusEvent(scrollPending = false, sinceScrollMillis = 200L))
        assertFalse(
                "499 仍在窗口内",
                shouldFollowFocusEvent(scrollPending = false,
                        sinceScrollMillis = SCROLL_FOCUS_COOLDOWN_MILLIS - 1))
    }

    @Test
    fun `冷却窗口过后的焦点照常跟`() {
        assertTrue(
                "手指滑读屏属于这类，必须正常跟随",
                shouldFollowFocusEvent(scrollPending = false,
                        sinceScrollMillis = SCROLL_FOCUS_COOLDOWN_MILLIS))
        assertTrue(
                "很久没滚动也要跟",
                shouldFollowFocusEvent(scrollPending = false, sinceScrollMillis = 60_000L))
    }

    @Test
    fun `从未滚动过一律跟`() {
        assertTrue(
                "刚启动时没有滚动记录，不能因为冷却误判成不跟",
                shouldFollowFocusEvent(scrollPending = false, sinceScrollMillis = Long.MAX_VALUE))
    }
}