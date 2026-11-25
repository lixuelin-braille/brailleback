/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链路停滞判定单测：只管「什么时候该认定连接已死」，不碰线程与定时器。
 *
 * 现场背景：USB 写超时后连接状态还挂着，刷新永远停住，用户只能拔插（11-13 记录）。
 * 判死了才会走既有重连退避，所以阈值宁可宽一点，不能误伤慢设备。
 */
class LinkStallPolicyTest {

    private val stallMs = LinkStallPolicy.DEFAULT_STALL_MILLIS

    @Test
    fun consecutiveFailuresAloneCanEscalate() {
        val policy = LinkStallPolicy()
        // 有内容要发、也刚成功过，但连续失败满 3 次：通道明确报错，直接判死。
        assertFalse(policy.shouldEscalate(1000, 900, true, 2))
        assertTrue(policy.shouldEscalate(1000, 900, true, 3))
        assertTrue(policy.shouldEscalate(1000, 900, false, 3))
    }

    @Test
    fun pendingWorkWithNoSuccessTimesOut() {
        val policy = LinkStallPolicy()
        // 刚成功过：不判死。
        assertFalse(policy.shouldEscalate(1000, 900, true, 0))
        // 差 1 毫秒：仍给一次机会。
        assertFalse(policy.shouldEscalate(stallMs - 1, 0, true, 0))
        // 正好到阈值（含）就判死：回调丢了 / 写调用卡住。
        assertTrue(policy.shouldEscalate(stallMs, 0, true, 0))
        assertTrue(policy.shouldEscalate(stallMs + 1, 0, true, 0))
    }

    @Test
    fun idleDisplayIsNeverDeclaredDead() {
        val policy = LinkStallPolicy()
        // 没有新内容要发，哪怕一小时没写过，也是「屏没变化」，不是死链。
        assertFalse(policy.shouldEscalate(3_600_000, 0, false, 0))
    }

    @Test
    fun thresholdsAreConfigurable() {
        val strict = LinkStallPolicy(stallMillis = 2000, maxConsecutiveFailures = 1)
        assertTrue(strict.shouldEscalate(1000, 999, true, 1))
        assertTrue(strict.shouldEscalate(2001, 0, true, 0))
        assertFalse(strict.shouldEscalate(1500, 0, true, 0))
    }
}
