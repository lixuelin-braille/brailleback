/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读界面两条滑块的手势判定：
 *  1. 滑轨内的横坐标映射到 0..maxIndex 的整格；
 *  2. 拖出右端 / 读屏在本页最后一行还继续上调 → 下一页（overflow = +1）；
 *  3. 拖出左端 / 第一行还继续下调 → 上一页（overflow = -1）；
 *  4. 页滑块（overflowEnabled = false）永不越界翻页。
 */

package com.linxi.brailleback.compose

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderScrubberHitTest {

    private val trackStart = 640f
    private val trackEnd = 1023f
    private val span = trackEnd - trackStart

    @Test
    fun `滑轨两端映射到首尾格`() {
        assertEquals(0, scrubberHitAt(trackStart, trackStart, trackEnd, 18, true).index)
        assertEquals(18, scrubberHitAt(trackEnd, trackStart, trackEnd, 18, true).index)
    }

    @Test
    fun `滑轨中点落在中间一格`() {
        val mid = scrubberHitAt(
            trackStart + span / 2f, trackStart, trackEnd, 18, true,
        )
        assertEquals((0.5f * 18f).roundToInt(), mid.index)
        assertEquals(0, mid.overflow)
    }

    @Test
    fun `拖出右端要求下一页`() {
        val hit = scrubberHitAt(trackEnd + span * 0.2f, trackStart, trackEnd, 18, true)
        assertEquals(1, hit.overflow)
        assertEquals(-1, hit.index)
    }

    @Test
    fun `拖出左端要求上一页`() {
        val hit = scrubberHitAt(trackStart - span * 0.2f, trackStart, trackEnd, 18, true)
        assertEquals(-1, hit.overflow)
        assertEquals(-1, hit.index)
    }

    @Test
    fun `刚出右端一点点还不算翻页`() {
        // 手指压在滑轨最右端时总会有几个像素的抖动，不能一抖就翻页。
        val hit = scrubberHitAt(trackEnd + span * 0.02f, trackStart, trackEnd, 18, true)
        assertEquals(0, hit.overflow)
        assertEquals(18, hit.index)
    }

    @Test
    fun `页滑块拖出端点也不翻页`() {
        val hit = scrubberHitAt(trackEnd + span * 0.5f, trackStart, trackEnd, 200, false)
        assertEquals(0, hit.overflow)
        assertEquals(200, hit.index)
    }

    @Test
    fun `本页最后一行还继续上调就是下一页`() {
        val hit = scrubberHitForAdjust(index = 18, target = 18, maxIndex = 18, overflowEnabled = true)
        assertEquals(1, hit.overflow)
    }

    @Test
    fun `第一行还继续下调就是上一页`() {
        val hit = scrubberHitForAdjust(index = 0, target = 0, maxIndex = 18, overflowEnabled = true)
        assertEquals(-1, hit.overflow)
    }

    @Test
    fun `正常调整只换格不翻页`() {
        val hit = scrubberHitForAdjust(index = 5, target = 6, maxIndex = 18, overflowEnabled = true)
        assertEquals(6, hit.index)
        assertEquals(0, hit.overflow)
    }

    @Test
    fun `中间一格原地不动时不翻页`() {
        // 读屏把目标夹回同一格，但既不在头也不在尾：没有方向，什么都不做。
        val hit = scrubberHitForAdjust(index = 9, target = 9, maxIndex = 18, overflowEnabled = true)
        assertEquals(0, hit.overflow)
    }

    @Test
    fun `页滑块到头不翻页`() {
        val hit = scrubberHitForAdjust(index = 200, target = 200, maxIndex = 200, overflowEnabled = false)
        assertEquals(0, hit.overflow)
    }
}
