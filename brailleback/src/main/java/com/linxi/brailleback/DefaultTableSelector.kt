/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

/**
 * 为什么需要它：
 *
 * 默认表选择要跨进程探测每张表能否加载，而界面每次刷新都会重复查询同一张
 * 默认表。把「命中记忆直返 / 探测取第一张可加载 / 全败退回目录兜底」的状态
 * 流转抽成不依赖 Android 的泛型类：候选列表、探测函数、目录兜底都由调用方
 * 注入，记忆命中、失效与探测次数可在纯 JVM 单测里钉住。
 *
 * 注意：目录兜底结果不写进记忆——服务稍后可能把表加载起来，兜底只是当下的
 * 显示推断，缓存它会把临时坏状态固定下来。
 */
internal class DefaultTableSelector<T>(
    private val probe: (T) -> Boolean
) {
    private var cached: T? = null
    private var cachedKey: Any? = null

    /** 上一次实际调用探测函数的次数；命中记忆时不更新。 */
    var lastProbedCount: Int = 0
        private set

    /** 上一轮探测失败的候选（不含胜出项）；命中记忆时清零，不重复上报旧失败。 */
    var lastFailed: List<T> = emptyList()
        private set

    /** 清空选择记忆；偏好变更、语言切换或需要强制重算时调用。 */
    fun invalidate() {
        cached = null
        cachedKey = null
    }

    /**
     * 选出第一张探测通过的候选并记入记忆；[key] 区分不同点数 / 缩写 / 语言的
     * 查询，参数不同不会复用旧记忆。全部探测失败时走 [catalogFallback]，其结果
     * 不缓存。
     */
    fun select(
        candidates: List<T>,
        key: Any?,
        maxProbes: Int = TableCandidatePicker.MAX_PROBES,
        catalogFallback: () -> T?
    ): T? {
        if (cached != null && cachedKey == key) {
            // 命中记忆：本轮没有探测，计数与失败名单归零，避免把上一轮的失败重复上报。
            lastProbedCount = 0
            lastFailed = emptyList()
            return cached
        }
        val picked = TableCandidatePicker.pickFirstLoadable(candidates, maxProbes, probe)
        lastProbedCount = picked.probedCount
        lastFailed = picked.failed
        picked.winner?.let {
            cached = it
            cachedKey = key
            return it
        }
        return catalogFallback()
    }
}
