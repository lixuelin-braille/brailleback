/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点稳定闸门。
 *
 * 钉住两条不变量：
 * 1. 空闲后的第一帧必须立刻放行，否则正常单步浏览也会变慢；
 * 2. 窗口起点不随新事件顺延，事件洪流下最多等一个窗口，不会永远轮不到。
 */
class FocusSettleGateTest {

    @Test
    fun `空闲后第一帧立刻放行`() {
        val gate = FocusSettleGate(120L)

        assertEquals("首帧不能被延迟，否则单步移动会变木", 0L, gate.onEvent(1000L))
        assertFalse("刚放行的帧不产生待补帧", gate.expired(1000L))
    }

    @Test
    fun `窗口内的事件被合并`() {
        val gate = FocusSettleGate(120L)
        gate.onEvent(1000L)

        assertEquals("窗口内第二帧应返回剩余时间而不是 0", 80L, gate.onEvent(1040L))
        assertEquals("窗口未到不算过期", false, gate.expired(1119L))
        assertTrue("窗口到期必须能补一帧", gate.expired(1120L))
    }

    @Test
    fun `窗口起点不顺延`() {
        val gate = FocusSettleGate(120L)
        gate.onEvent(1000L)
        // 一直有事件也不能把到期时间往后推。
        gate.onEvent(1080L)
        gate.onEvent(1100L)

        assertTrue("到期时间必须仍以首帧为基准", gate.expired(1120L))
    }

    @Test
    fun `补刷之后重新享受立刻放行`() {
        val gate = FocusSettleGate(120L)
        gate.onEvent(1000L)
        assertTrue(gate.expired(1120L))

        gate.onFlushed()
        assertEquals("补刷完窗口关闭，下一帧要立刻上屏", 0L, gate.onEvent(1200L))
    }

    @Test
    fun `窗口过期但没补刷时新事件直接放行`() {
        val gate = FocusSettleGate(120L)
        gate.onEvent(1000L)

        assertEquals("过期未补刷却继续吞事件会让屏长期停住", 0L, gate.onEvent(1500L))
    }

    @Test
    fun `退出模式后不继承半开窗口`() {
        val gate = FocusSettleGate(120L)
        gate.onEvent(1000L)
        gate.reset()

        assertEquals("切模式回来第一帧必须是即时的", 0L, gate.onEvent(1050L))
    }
}
