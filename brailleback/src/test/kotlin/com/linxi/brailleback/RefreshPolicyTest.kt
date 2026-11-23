/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新前置条件判定。
 *
 * 这三条判断原来是 `refresh()` 里的 early return，只能在真机上通过“点显器不动”
 * 间接观察；抽成纯函数后可以直接断言真值表。
 */
class RefreshPolicyTest {

    private fun decide(connected: Boolean, frozen: Boolean, motion: Boolean) =
        decideRefresh(connected, frozen, motion)

    @Test
    fun `sends when nothing blocks`() {
        assertEquals(
            RefreshDecision.Send,
            decide(connected = true, frozen = false, motion = false),
        )
    }

    @Test
    fun `未连点显器一律不刷`() {
        // 未连接时连冻结/运动模式都不必看：没有下游可发。
        assertEquals(
            RefreshDecision.Blocked(RefreshBlock.NOT_CONNECTED),
            decide(connected = false, frozen = false, motion = false),
        )
        assertEquals(
            RefreshDecision.Blocked(RefreshBlock.NOT_CONNECTED),
            decide(connected = false, frozen = true, motion = false),
        )
        assertEquals(
            RefreshDecision.Blocked(RefreshBlock.NOT_CONNECTED),
            decide(connected = false, frozen = false, motion = true),
        )
    }

    @Test
    fun `冻结优先于运动模式`() {
        // 暂停输出时屏上内容保持不变；此时即使处于运动模式也不该排去抖刷新。
        assertEquals(
            RefreshDecision.Blocked(RefreshBlock.FROZEN),
            decide(connected = true, frozen = true, motion = true),
        )
    }

    @Test
    fun `运动模式是延后而不是丢弃`() {
        val decision = decide(connected = true, frozen = false, motion = true)
        assertTrue(
            "运动模式必须单独给一个原因，调用方要据此安排去抖刷新，不能和“丢弃”混为一谈",
            decision is RefreshDecision.Blocked,
        )
        assertEquals(
            RefreshBlock.MOTION,
            (decision as RefreshDecision.Blocked).reason,
        )
    }

    @Test
    fun `拒绝时一定带出原因`() {
        for (connected in booleanArrayOf(true, false)) {
            for (frozen in booleanArrayOf(true, false)) {
                for (motion in booleanArrayOf(true, false)) {
                    val decision = decide(connected, frozen, motion)
                    if (decision is RefreshDecision.Blocked) {
                        assertTrue(
                            "计数器要按原因分类，不能漏",
                            decision.reason in RefreshBlock.values(),
                        )
                    }
                }
            }
        }
    }
}
