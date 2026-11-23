/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

/**
 * 盲文表清单的有界重试策略：纯逻辑、零 Android 依赖，便于 JVM 单测。
 *
 * 节奏选固定 500 ms 间隔，而不是逐次翻倍：服务绑定 / 翻译器初始化是一次性握手，
 * 不是慢链路轮询；固定短间隔最好算清最坏耗时（6 次尝试之间共 5 段等待，合计
 * 2.5 s，在 5 s 预算以内），翻倍会让后段等待过长，用户对着空屏干等。
 */
internal object BrailleTableRetryPolicy {

    /** 最多读取尝试次数（含 onCreate 那一次），到点直接落失败态，不再重试。 */
    const val MAX_ATTEMPTS = 6

    /** 每次重试前固定等待的毫秒数。 */
    const val RETRY_INTERVAL_MS = 500L

    /** 整轮重试的总时长上界，单测钉住不超过 5 s。 */
    const val TOTAL_BUDGET_MS = 5_000L

    /**
     * 已完成 [attempt] 次读取（从 1 起）之后，是否还安排下一次重试。
     * [cancelled] 为 true 表示 Activity 已销毁 / 用户已手动刷新 / 清单已到达，
     * 挂起中的重试必须取消，后续不再触发。
     */
    fun shouldRetry(attempt: Int, cancelled: Boolean): Boolean =
        !cancelled && attempt in 1 until MAX_ATTEMPTS

    /** 下一次重试前等待的毫秒数；不重试时返回 0。 */
    fun delayMillis(attempt: Int): Long =
        if (shouldRetry(attempt, cancelled = false)) RETRY_INTERVAL_MS else 0L

    /** 最坏情况总等待（全部失败、跑满次数），单测用来钉住 5 s 预算。 */
    fun worstCaseDelayMillis(): Long = (MAX_ATTEMPTS - 1) * RETRY_INTERVAL_MS

    /**
     * 串行模拟一整轮：[outcomes] 第 i 项表示第 i+1 次是否读到清单；
     * [cancelAfterAttempt] 给出后，从该次起视为已取消，后续不再触发。
     */
    fun simulate(
        outcomes: List<Boolean>,
        cancelAfterAttempt: Int? = null,
    ): SimulatedRun {
        var attempts = 0
        var totalDelay = 0L
        var succeeded = false
        var cancelled = false
        for ((index, ok) in outcomes.withIndex()) {
            attempts = index + 1
            if (cancelAfterAttempt != null && attempts >= cancelAfterAttempt) {
                cancelled = true
            }
            if (ok) {
                succeeded = true
                break
            }
            if (!shouldRetry(attempts, cancelled)) break
            totalDelay += delayMillis(attempts)
        }
        return SimulatedRun(
            attempts = attempts,
            totalDelayMillis = totalDelay,
            succeeded = succeeded,
        )
    }

    /** 一轮模拟的结果：尝试次数、累计等待毫秒数、是否读到清单。 */
    data class SimulatedRun(
        val attempts: Int,
        val totalDelayMillis: Long,
        val succeeded: Boolean,
    )
}
