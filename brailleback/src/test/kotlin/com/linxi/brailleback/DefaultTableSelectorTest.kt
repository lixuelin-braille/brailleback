/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */
package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DefaultTableSelector] 纯逻辑单测：钉住「取第一张可加载而非目录第一张」、
 * 记忆命中不重探、失效后重算、全败回退目录兜底且探测不超上界。零 Android
 * 依赖，不碰服务进程与真机。
 */
class DefaultTableSelectorTest {

    /** 目录第一张坏、第二张好：标签必须落到第二张可加载的，而不是目录第一张。 */
    @Test
    fun returnsFirstLoadableNotCatalogFirst() {
        val candidates = listOf("bad", "good")
        val probed = mutableListOf<String>()
        val selector = DefaultTableSelector<String> { probed.add(it); it == "good" }

        val result = selector.select(candidates, key = "k", catalogFallback = { "fallback" })

        assertEquals("good", result)
        assertEquals(listOf("bad", "good"), probed)
        assertEquals(listOf("bad"), selector.lastFailed)
    }

    /** 记忆命中：第二次查询不再调用探测函数，本轮探测次数为 0。 */
    @Test
    fun cacheHitDoesNotReprobe() {
        val candidates = listOf("a", "b")
        val probed = mutableListOf<String>()
        val selector = DefaultTableSelector<String> { probed.add(it); true }

        selector.select(candidates, "k") { "fallback" }
        selector.select(candidates, "k") { "fallback" }

        assertEquals(1, probed.size)
        assertEquals(0, selector.lastProbedCount)
        assertEquals(emptyList<String>(), selector.lastFailed)
    }

    /** 偏好变更后失效：重算时探测函数再次被调用。 */
    @Test
    fun invalidateClearsMemoryAndReprobes() {
        val candidates = listOf("a")
        val probed = mutableListOf<String>()
        val selector = DefaultTableSelector<String> { probed.add(it); true }

        selector.select(candidates, "k") { "fallback" }
        selector.invalidate()
        selector.select(candidates, "k") { "fallback" }

        assertEquals(2, probed.size)
    }

    /** 全部候选加载失败：返回目录兜底表，探测次数不超过上界。 */
    @Test
    fun allFailReturnsFallbackAndProbeWithinBound() {
        val candidates = (1..10).map { "t$it" }
        val probed = mutableListOf<String>()
        val selector = DefaultTableSelector<String> { probed.add(it); false }

        val result = selector.select(candidates, "k") { "fallback" }

        assertEquals("fallback", result)
        assertEquals(TableCandidatePicker.MAX_PROBES, selector.lastProbedCount)
        assertTrue(selector.lastProbedCount <= TableCandidatePicker.MAX_PROBES)
        assertEquals(TableCandidatePicker.MAX_PROBES, probed.size)
        assertEquals(TableCandidatePicker.MAX_PROBES, selector.lastFailed.size)
    }

    /** 目录兜底结果不写进记忆：下一轮仍会重新探测。 */
    @Test
    fun catalogFallbackIsNotCached() {
        val candidates = listOf("a")
        val probed = mutableListOf<String>()
        val selector = DefaultTableSelector<String> { probed.add(it); false }

        selector.select(candidates, "k") { "fallback" }
        selector.select(candidates, "k") { "fallback" }

        assertEquals(2, probed.size)
    }

    /** 参数（点数 / 缩写 / 语言）不同时不复用旧记忆。 */
    @Test
    fun differentKeyDoesNotReuseMemory() {
        val candidates = listOf("a")
        val probed = mutableListOf<String>()
        val selector = DefaultTableSelector<String> { probed.add(it); true }

        selector.select(candidates, "key-1") { "fallback" }
        selector.select(candidates, "key-2") { "fallback" }

        assertEquals(2, probed.size)
    }
}
