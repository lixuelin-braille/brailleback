/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */
package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TableCandidatePicker] 纯逻辑单测：钉住候选顺序、探测次数、失败名单与
 * 异常兜底。零 Android 依赖，不碰服务进程与真机。
 */
class TableCandidatePickerTest {

    private fun <T> pick(candidates: List<T>, probe: (T) -> Boolean) =
        TableCandidatePicker.pickFirstLoadable(candidates, probe = probe)

    /** 第一张就能加载：取第一张，后面的候选不再探测。 */
    @Test
    fun firstCandidateLoadableIsWinnerAndRestNotProbed() {
        val candidates = listOf("a", "b", "c")
        val probed = mutableListOf<String>()
        val result = pick(candidates) {
            probed.add(it)
            it == "a"
        }
        assertEquals("a", result.winner)
        assertEquals(1, result.probedCount)
        assertEquals(emptyList<String>(), result.failed)
        assertEquals(listOf("a"), probed)
    }

    /** 前两张失败、第三张成功：取第三张，失败名单恰好两条，前三张各探测一次。 */
    @Test
    fun skipsFailedCandidatesUntilThirdLoadable() {
        val candidates = listOf("a", "b", "c", "d")
        val probed = mutableListOf<String>()
        val result = pick(candidates) {
            probed.add(it)
            it == "c"
        }
        assertEquals("c", result.winner)
        assertEquals(3, result.probedCount)
        assertEquals(listOf("a", "b"), result.failed)
        assertEquals(listOf("a", "b", "c"), probed)
    }

    /** 全部失败：返回 null，失败名单完整覆盖所有被探测候选。 */
    @Test
    fun allFailReturnsNullWithCompleteFailureList() {
        val candidates = listOf("a", "b", "c")
        val probed = mutableListOf<String>()
        val result = pick(candidates) {
            probed.add(it)
            false
        }
        assertNull(result.winner)
        assertEquals(3, result.probedCount)
        assertEquals(candidates, result.failed)
        assertEquals(candidates, probed)
    }

    /** 候选为空：返回 null 且不调用探测函数，不崩。 */
    @Test
    fun emptyCandidatesReturnsNullWithoutProbing() {
        val result = pick(emptyList<String>()) { error("不该被调用") }
        assertNull(result.winner)
        assertEquals(0, result.probedCount)
        assertEquals(emptyList<String>(), result.failed)
    }

    /** 探测抛异常按失败处理并继续下一张，异常候选计入失败名单。 */
    @Test
    fun probeExceptionIsTreatedAsFailureAndContinues() {
        val candidates = listOf("a", "b", "c")
        val probed = mutableListOf<String>()
        val result = pick(candidates) {
            probed.add(it)
            if (it == "a") throw IllegalStateException("表加载超时")
            it == "c"
        }
        assertEquals("c", result.winner)
        assertEquals(3, result.probedCount)
        assertEquals(listOf("a", "b"), result.failed)
        assertEquals(candidates, probed)
    }

    /** 探测次数受上界约束：超过 MAX_PROBES 的候选不再被探测。 */
    @Test
    fun probeCountIsCappedAtMaxProbes() {
        val candidates = (1..10).map { "t$it" }
        val probed = mutableListOf<String>()
        val result = TableCandidatePicker.pickFirstLoadable(candidates) {
            probed.add(it)
            false
        }
        assertNull(result.winner)
        assertEquals(TableCandidatePicker.MAX_PROBES, result.probedCount)
        assertEquals(TableCandidatePicker.MAX_PROBES, result.failed.size)
        assertEquals(TableCandidatePicker.MAX_PROBES, probed.size)
    }
}
