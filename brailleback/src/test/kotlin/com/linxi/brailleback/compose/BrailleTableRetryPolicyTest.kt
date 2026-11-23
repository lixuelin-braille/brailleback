/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 盲文表清单重试节奏的纯逻辑单测（零 Android 依赖，不依赖 Robolectric）。 */
class BrailleTableRetryPolicyTest {

    @Test
    fun exactlySixAttemptsThenStops() {
        // 六次全部落空：到第 6 次就停，不再安排第 7 次。
        val run = BrailleTableRetryPolicy.simulate(listOf(false, false, false, false, false, false))
        assertEquals(6, run.attempts)
        assertFalse(run.succeeded)
        assertFalse(
            "第 6 次之后必须停止重试",
            BrailleTableRetryPolicy.shouldRetry(6, cancelled = false)
        )
        assertTrue(
            "第 5 次之后仍应安排下一次",
            BrailleTableRetryPolicy.shouldRetry(5, cancelled = false)
        )
    }

    @Test
    fun worstCaseTotalDelayWithinFiveSecondBudget() {
        assertEquals(
            (BrailleTableRetryPolicy.MAX_ATTEMPTS - 1) *
                BrailleTableRetryPolicy.RETRY_INTERVAL_MS,
            BrailleTableRetryPolicy.worstCaseDelayMillis()
        )
        assertTrue(
            "最坏耗时不得超过 5 秒预算",
            BrailleTableRetryPolicy.worstCaseDelayMillis() <=
                BrailleTableRetryPolicy.TOTAL_BUDGET_MS
        )
        val run = BrailleTableRetryPolicy.simulate(
            listOf(false, false, false, false, false, false)
        )
        assertTrue(
            "跑满六次的累计等待不得超过 5 秒",
            run.totalDelayMillis <= BrailleTableRetryPolicy.TOTAL_BUDGET_MS
        )
        assertEquals(5 * BrailleTableRetryPolicy.RETRY_INTERVAL_MS, run.totalDelayMillis)
    }

    @Test
    fun successOnceStopsRetrying() {
        // 第一次空、第二次读到：成功即停，后面不再安排重试。
        val run = BrailleTableRetryPolicy.simulate(listOf(false, true))
        assertEquals(2, run.attempts)
        assertTrue(run.succeeded)
        assertEquals(BrailleTableRetryPolicy.RETRY_INTERVAL_MS, run.totalDelayMillis)
    }

    @Test
    fun cancelledRunTriggersNoFurtherRetry() {
        // 取消之后：挂起回调不再触发，累计等待为 0。
        val run = BrailleTableRetryPolicy.simulate(
            listOf(false, false, false),
            cancelAfterAttempt = 1,
        )
        assertEquals(1, run.attempts)
        assertFalse(run.succeeded)
        assertEquals(0L, run.totalDelayMillis)
        assertFalse(
            "已取消时任意次数都不再重试",
            BrailleTableRetryPolicy.shouldRetry(1, cancelled = true)
        )
        assertFalse(
            "已取消时任意次数都不再重试",
            BrailleTableRetryPolicy.shouldRetry(5, cancelled = true)
        )
        assertEquals(0L, BrailleTableRetryPolicy.delayMillis(6))
    }
}
