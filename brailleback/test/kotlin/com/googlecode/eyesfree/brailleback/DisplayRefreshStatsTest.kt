/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新计数。
 *
 * 这些字段原来散在 `DisplayManager` 里、用 `@Volatile Int` 自增，只能靠读日志猜。
 * 现在可以直接断言“哪一类拦截加到了哪个计数上”。
 */
class DisplayRefreshStatsTest {

    @Test
    fun `各类拦截分别计数`() {
        val stats = DisplayRefreshStats()
        stats.onBlocked(RefreshBlock.NOT_CONNECTED)
        stats.onBlocked(RefreshBlock.FROZEN)
        stats.onBlocked(RefreshBlock.FROZEN)
        stats.onBlocked(RefreshBlock.MOTION)
        val snapshot = stats.snapshot()

        assertEquals(1L, snapshot.blockedNotConnected)
        assertEquals(2L, snapshot.blockedFrozen)
        assertEquals(1L, snapshot.blockedMotion)
        // 不能互相串台：三个原因必须落在三个计数上。
        assertEquals(0L, snapshot.blockedIdentical)
        assertEquals(0L, snapshot.blockedEmpty)
    }

    @Test
    fun `重复帧与空帧不计入成功刷新`() {
        val stats = DisplayRefreshStats()
        stats.onContent()
        stats.onIdentical()
        stats.onEmpty()
        val snapshot = stats.snapshot()

        assertEquals(1L, snapshot.content)
        assertEquals(1L, snapshot.blockedIdentical)
        assertEquals(1L, snapshot.blockedEmpty)
        assertEquals(0L, snapshot.refreshOk)
    }

    @Test
    fun `成功刷新记录格数与摘要`() {
        val stats = DisplayRefreshStats()
        stats.onRefreshOk(cells = 32, digest = 0x1A2B3C4D)

        val snapshot = stats.snapshot()
        assertEquals(1L, snapshot.refreshOk)
        assertEquals(32L, snapshot.lastCells)
        assertEquals(0x1A2B3C4DL, snapshot.lastDigest)
        assertEquals(32, stats.lastCells())
    }

    @Test
    fun `空帧抑制读取的是上一次实时格数`() {
        val stats = DisplayRefreshStats()
        // 首次无内容时格数为 0，空帧属于正常起点，不该被抑制。
        assertEquals(0, stats.lastCells())

        stats.onRefreshOk(cells = 20, digest = 1)
        // 之后才允许“空帧保持原屏”。
        assertEquals(20, stats.lastCells())
    }

    @Test
    fun `合并与重译各自累计`() {
        val stats = DisplayRefreshStats()
        stats.onCoalesced()
        stats.onCoalesced()
        stats.onRetranslate()
        val snapshot = stats.snapshot()

        assertEquals(2L, snapshot.coalesced)
        assertEquals(1L, snapshot.retranslate)
    }

    @Test
    fun `心跳行保留现场排障要用的字段名`() {
        val stats = DisplayRefreshStats()
        stats.onContent()
        stats.onRefreshOk(cells = 40, digest = 0x0000FFFF)
        val line = stats.describe(connected = true, frozen = false, motionMode = true)

        // 现场靠这几项在日志里逐次比对，改名等于让旧日志无法对照。
        for (token in listOf("conn=", "content=", "retranslate=", "ok=", "cells=", "digest=",
            "blocked(nc=", "frozen=", "motion=", "identical=", "empty=", "coalesced=")) {
            assertTrue("心跳行缺少字段 $token", line.contains(token))
        }
        assertTrue(line.contains("content=1"))
        assertTrue(line.contains("ok=1"))
        assertTrue(line.contains("cells=40"))
        assertTrue(line.contains("0000FFFF"))
    }
}
