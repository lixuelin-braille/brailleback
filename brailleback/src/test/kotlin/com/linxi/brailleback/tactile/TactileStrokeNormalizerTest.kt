/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线宽归一原型断言：
 * - 开关默认关闭，原样返回；
 * - 1 点宽横线 / 竖线补到有垂直 / 水平邻域，原墨点不丢；
 * - 粗实心块不被撑大（面积增幅受限）；
 * - 空图不变。
 */
class TactileStrokeNormalizerTest {

    private fun inkArray(cols: Int, rows: Int, inkAt: (x: Int, y: Int) -> Boolean): BooleanArray =
        BooleanArray(cols * rows) { i -> inkAt(i % cols, i / cols) }

    private fun countInk(ink: BooleanArray): Int = ink.count { it }

    @Test
    fun disabledReturnsOriginalArray() {
        val ink = inkArray(8, 4) { x, y -> y == 1 && x % 2 == 0 }
        // 关闭开关：必须原样返回同一数组，默认路径零改动。
        val off = TactileStrokeNormalizer.normalize(ink, 8, 4, TactileStrokeNormalizer.Config.OFF)
        assertSame(ink, off)
    }

    @Test
    fun thinHorizontalLineGainsVerticalSupport() {
        val cols = 12
        val rows = 5
        val ink = inkArray(cols, rows) { _, y -> y == 2 }
        val cfg = TactileStrokeNormalizer.Config.FORCE
        val out = TactileStrokeNormalizer.normalize(ink, cols, rows, cfg)

        // 原墨点一个不丢。
        for (i in ink.indices) assertTrue("原墨点丢失 @$i", !ink[i] || out[i])

        // 每个原墨点现在上方或下方有邻域（线厚 ≥2 行）。
        for (x in 0 until cols) {
            val above = out[(1) * cols + x]
            val below = out[(3) * cols + x]
            assertTrue("横线第 $x 点缺少垂直支撑", above || below)
        }
        // 确实补了点，但没有补满整图。
        val original = countInk(ink)
        val grown = countInk(out)
        assertTrue("补点后墨点应多于原线: $original → $grown", grown > original)
        assertTrue("补点不应把图填满: $grown", grown < cols * rows)
    }

    @Test
    fun thinVerticalLineGainsHorizontalSupport() {
        val cols = 5
        val rows = 12
        val ink = inkArray(cols, rows) { x, _ -> x == 2 }
        val cfg = TactileStrokeNormalizer.Config.FORCE
        val out = TactileStrokeNormalizer.normalize(ink, cols, rows, cfg)

        for (y in 0 until rows) {
            val left = out[y * cols + 1]
            val right = out[y * cols + 3]
            assertTrue("竖线第 $y 点缺少水平支撑", left || right)
        }
    }

    @Test
    fun solidBlockNotBloated() {
        val cols = 12
        val rows = 12
        val ink = inkArray(cols, rows) { x, y -> x in 2..9 && y in 2..9 }
        val cfg = TactileStrokeNormalizer.Config.FORCE
        val out = TactileStrokeNormalizer.normalize(ink, cols, rows, cfg)

        val original = countInk(ink)
        val grown = countInk(out)
        val growth = (grown - original).toDouble() / original
        assertTrue(
            "粗块面积增幅应受限: 原 $original, 现 $grown, 增幅 $growth",
            growth < 0.05
        )
        // 原块墨点不丢。
        for (i in ink.indices) assertTrue(!ink[i] || out[i])
    }

    @Test
    fun emptyGridUnchanged() {
        val ink = BooleanArray(8 * 6)
        val out = TactileStrokeNormalizer.normalize(
            ink, 8, 6, TactileStrokeNormalizer.Config.FORCE
        )
        assertEquals(0, countInk(out))
    }
}
