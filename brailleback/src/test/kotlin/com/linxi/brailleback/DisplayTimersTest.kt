/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三个定时器的虚拟时间验证。
 *
 * 这些行为原来只能在真机上“等三分钟看它是不是清屏了”：
 * 空闲清屏每 3 分钟一次、运动去抖 350 ms、闪烁 600/800 ms 交替。
 * 换成 Job + delay 后，`runTest` 里 `advanceTimeBy` 就能断言次数与顺序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisplayTimersTest {

    @Test
    fun `去抖只在安静满窗口后执行一次`() = runTest {
        val debouncer = Debouncer(backgroundScope)
        var calls = 0

        // 模拟事件洪流：每 100 ms 来一帧，持续 1 秒（最后一次排在 t=900，到期 1250）。
        repeat(10) {
            debouncer.schedule(350) { calls += 1 }
            advanceTimeBy(100)
        }
        assertEquals("洪流期间不该执行", 0, calls)

        advanceTimeBy(249)
        assertEquals("还没到 1250", 0, calls)
        advanceTimeBy(2)
        assertEquals("安静 350 ms 后只执行一次", 1, calls)

        // 再等很久也不会有第二次：它是去抖不是周期。
        advanceTimeBy(5_000)
        assertEquals(1, calls)
    }

    @Test
    fun `去抖取消后不再执行`() = runTest {
        val debouncer = Debouncer(backgroundScope)
        var calls = 0
        debouncer.schedule(350) { calls += 1 }
        advanceTimeBy(200)
        debouncer.cancel()
        assertFalse("取消后不该还挂在队列里", debouncer.isPending)
        advanceTimeBy(1_000)
        assertEquals(0, calls)
    }

    @Test
    fun `周期定时器按同一间隔反复执行`() = runTest {
        val timer = RepeatingTimer(backgroundScope)
        var clears = 0
        timer.restart(180_000) { clears += 1 }

        advanceTimeBy(179_999)
        assertEquals("还没到 3 分钟不该清", 0, clears)
        advanceTimeBy(2)
        assertEquals(1, clears)
        advanceTimeBy(180_001)
        assertEquals("持续空闲要继续清屏", 2, clears)
        advanceTimeBy(180_001)
        assertEquals(3, clears)

        timer.cancel()
        advanceTimeBy(180_000 * 3)
        assertEquals("取消后不再清屏", 3, clears)
    }

    @Test
    fun `每次活动都重置周期的计时`() = runTest {
        val timer = RepeatingTimer(backgroundScope)
        var clears = 0
        timer.restart(180_000) { clears += 1 }

        // 用户每 100 秒动一下：计时不断被重置，永远到不了 3 分钟。
        repeat(3) {
            advanceTimeBy(100_000)
            timer.restart(180_000) { clears += 1 }
        }
        assertEquals("持续有活动时不该清屏", 0, clears)

        // 最后一次重置在 t=300000，到期 480000。
        advanceTimeBy(180_001)
        assertEquals(1, clears)
    }

    @Test
    fun `间隔为 0 等于关闭`() = runTest {
        val timer = RepeatingTimer(backgroundScope)
        var clears = 0
        timer.restart(0) { clears += 1 }
        advanceTimeBy(600_000)
        assertEquals("0 是全项目统一的“关闭”约定", 0, clears)
    }

    @Test
    fun `单次定时器执行后允许动作内部再排下一次`() = runTest {
        // 闪烁链：闪一下 → 重画 → 重画时再排下一次，间隔按当前 overlaysOn 取 600/800。
        val timer = OneShotTimer(backgroundScope)
        var overlaysOn = true
        var flips = 0

        fun scheduleNext() {
            timer.schedule(if (overlaysOn) 600 else 800) {
                overlaysOn = !overlaysOn
                flips += 1
                scheduleNext()
            }
        }
        scheduleNext()

        advanceTimeBy(599)
        assertEquals(0, flips)
        advanceTimeBy(2)
        assertEquals("第一次 600 ms", 1, flips)
        assertFalse("overlaysOn 翻转后要换 800 ms", overlaysOn)

        advanceTimeBy(798)
        assertEquals("第二次要等 800 ms", 1, flips)
        advanceTimeBy(3)
        assertEquals(2, flips)
        assertTrue(overlaysOn)

        timer.cancel()
        advanceTimeBy(5_000)
        assertEquals("取消后闪烁停止", 2, flips)
    }

    @Test
    fun `单次定时器重复排程只会保留先到的那个`() = runTest {
        val timer = OneShotTimer(backgroundScope)
        var calls = 0
        timer.schedule(600) { calls += 1 }
        timer.schedule(600) { calls += 1 }
        advanceTimeBy(601)
        assertEquals("同一时刻只允许一个待执行", 1, calls)
    }
}
