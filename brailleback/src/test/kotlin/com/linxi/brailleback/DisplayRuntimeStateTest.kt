/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 运行时状态快照。
 *
 * 原来 `connected` / `frozen` / `motionMode` 是三个独立 `@Volatile`，`refresh()` 连着读三次：
 * 回调线程刚把 connected 置 true，display 线程可能还在用旧的 frozen 值算这一帧。
 * 这里锁住两条：单次裁决只读一份快照；并发写不丢更新。
 */
class DisplayRuntimeStateTest {

    @Test
    fun `默认是未连接未暂停`() {
        val state = DisplayRuntimeState()
        assertEquals(false, state.connected)
        assertEquals(false, state.frozen)
        assertEquals(false, state.motionMode)
        assertEquals(RefreshDecision.Blocked(RefreshBlock.NOT_CONNECTED), state.refreshDecision())
    }

    @Test
    fun `连接后只有暂停或运动模式才拦刷新`() {
        val connected = DisplayRuntimeState(connected = true)
        assertEquals(RefreshDecision.Send, connected.refreshDecision())

        assertEquals(
            RefreshDecision.Blocked(RefreshBlock.FROZEN),
            connected.copy(frozen = true).refreshDecision(),
        )
        assertEquals(
            RefreshDecision.Blocked(RefreshBlock.MOTION),
            connected.copy(motionMode = true).refreshDecision(),
        )
        // 冻结优先于运动模式：暂停时不该还去排去抖刷新。
        assertEquals(
            RefreshDecision.Blocked(RefreshBlock.FROZEN),
            connected.copy(frozen = true, motionMode = true).refreshDecision(),
        )
    }

    @Test
    fun `更新一个字段不影响其他字段`() {
        val flow = MutableStateFlow(DisplayRuntimeState(connected = true, cursorDots = true))
        flow.update { it.copy(frozen = true) }

        val state = flow.value
        assertTrue("连接状态被顺手改掉了", state.connected)
        assertTrue("光标叠加被顺手改掉了", state.cursorDots)
        assertTrue(state.frozen)
    }

    @Test
    fun `并发更新不丢更新`() {
        // CAS 更新：两个线程各自置不同的位，最终两个位都必须在。
        val flow = MutableStateFlow(DisplayRuntimeState())
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        val a = Thread {
            start.await()
            repeat(100) { flow.update { it.copy(frozen = true) } }
            done.countDown()
        }
        val b = Thread {
            start.await()
            repeat(100) { flow.update { it.copy(cursorDots = true) } }
            done.countDown()
        }
        a.start(); b.start(); start.countDown()
        assertTrue("并发更新超时", done.await(5, TimeUnit.SECONDS))

        assertTrue("frozen 丢了", flow.value.frozen)
        assertTrue("cursorDots 丢了", flow.value.cursorDots)
    }
}
