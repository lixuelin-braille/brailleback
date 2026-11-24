/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 线宽归一转正断言：
 * - [TactileStrokeNormalizer.isPureLineArt] 纯函数：线稿 / 高墨填充 / 实心块分别判对；
 * - auto 模式下非线条图整体跳过，不增墨；
 * - 密集细墙（平面图类）不再爆增，增幅有界且不产生新的 2×2 实心块；
 * - 实心方零增长；细线条图增幅有界、原墨点不丢。
 */
class TactileStrokeNormalizerV2Test {

    private fun inkArray(cols: Int, rows: Int, inkAt: (x: Int, y: Int) -> Boolean): BooleanArray =
        BooleanArray(cols * rows) { i -> inkAt(i % cols, i / cols) }

    private fun countInk(ink: BooleanArray): Int = ink.count { it }

    private fun solidBlockCount(ink: BooleanArray, cols: Int, rows: Int): Int {
        var n = 0
        for (y in 0 until rows - 1) {
            for (x in 0 until cols - 1) {
                val i = y * cols + x
                if (ink[i] && ink[i + 1] && ink[i + cols] && ink[i + cols + 1]) n++
            }
        }
        return n
    }

    @Test
    fun pureLineArtAcceptsThinOutline() {
        // 方框轮廓：1 点细线，墨占比低、无 2×2 实心块。
        val ink = inkArray(20, 12) { x, y ->
            y == 1 || y == 10 || x == 1 || x == 18
        }
        assertTrue(TactileStrokeNormalizer.isPureLineArt(ink, 20, 12))
    }

    @Test
    fun pureLineArtRejectsDenseFill() {
        // 大面积半填充：墨占比超阈值。
        val ink = inkArray(20, 12) { x, y -> (x + y) % 2 == 0 }
        assertFalse(TactileStrokeNormalizer.isPureLineArt(ink, 20, 12))
    }

    @Test
    fun pureLineArtRejectsSolidBlock() {
        // 中部一块实心矩形，2×2 实心块占比超阈值。
        val ink = inkArray(20, 12) { x, y -> x in 6..13 && y in 3..8 }
        assertFalse(TactileStrokeNormalizer.isPureLineArt(ink, 20, 12))
    }

    @Test
    fun autoSkipsNonLineArtGrid() {
        val ink = inkArray(16, 10) { x, y -> (x * y) % 3 == 0 }
        val out = TactileStrokeNormalizer.normalize(
            ink, 16, 10, TactileStrokeNormalizer.Config.AUTO
        )
        // 非线条图：auto 直接跳过，原样返回不增墨。
        assertEquals(countInk(ink), countInk(out))
    }

    @Test
    fun denseWallPlanDoesNotExplode() {
        // 平面图类：多道平行横 / 竖细墙密集交错，无断裂迹象。
        val ink = inkArray(24, 16) { x, y ->
            y % 4 == 2 || x % 4 == 2
        }
        // AUTO 生产路径：无断裂迹象，门控②拦下，逐字节不变。
        val auto = TactileStrokeNormalizer.normalize(
            ink, 24, 16, TactileStrokeNormalizer.Config.AUTO
        )
        assertEquals("AUTO 密集墙应原样不变", ink.toList(), auto.toList())

        // FORCE 仅调试用：绕过门控直接处理，增幅有界且不产生新的 2×2 实心块。
        val force = TactileStrokeNormalizer.normalize(
            ink, 24, 16, TactileStrokeNormalizer.Config.FORCE
        )
        val original = countInk(ink)
        val grown = countInk(force)
        val growth = (grown - original).toDouble() / original
        assertTrue(
            "FORCE 密集墙增幅应有界: 原 $original 现 $grown 增幅 $growth",
            growth <= 0.40 + 1e-9
        )
        assertEquals(solidBlockCount(ink, 24, 16), solidBlockCount(force, 24, 16))
        for (i in ink.indices) assertTrue(!ink[i] || force[i])
    }

    @Test
    fun solidSquareUnchanged() {
        val ink = inkArray(10, 10) { x, y -> x in 2..7 && y in 2..7 }
        val cfg = TactileStrokeNormalizer.Config.FORCE
        val out = TactileStrokeNormalizer.normalize(ink, 10, 10, cfg)
        assertEquals(countInk(ink), countInk(out))
    }

    @Test
    fun thinOutlineGrowthBoundedAndOriginalKept() {
        val ink = inkArray(20, 12) { x, y ->
            y == 1 || y == 10 || x == 1 || x == 18
        }
        val cfg = TactileStrokeNormalizer.Config.FORCE
        val out = TactileStrokeNormalizer.normalize(ink, 20, 12, cfg)

        val original = countInk(ink)
        val grown = countInk(out)
        // 细线补到 2 点宽，增幅有界（闭合轮廓四边双向补点，允许到 3 倍；不允许把图填满）。
        assertTrue("增长应大于原线: $original → $grown", grown > original)
        assertTrue("增幅应有界: $original → $grown", grown <= original * 3)
        for (i in ink.indices) assertTrue(!ink[i] || out[i])
    }
}
