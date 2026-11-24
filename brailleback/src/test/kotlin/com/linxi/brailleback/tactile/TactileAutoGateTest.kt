/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTO 门控实测断言（7 类样本，40×1×8 主规格）：
 * - 无断裂迹象的完整轮廓（圆 / 方框 / 心形 / 字母 A）：AUTO 输出与 OFF 逐字节相同，带数零回归；
 * - 有孤立点的箭头：AUTO 加固后孤立点数下降；
 * - 平面图：被断裂门控拦下，AUTO 输出与 OFF 逐字节相同。
 */
class TactileAutoGateTest {

    private val spec = ImageStripEncoder.Spec.forDisplay(40, 1, 8)

    private fun dotGrid(img: GrayImage): Pair<BooleanArray, IntArray> {
        val cols = spec.dotColumns
        val rows = maxOf(1, Math.round(cols.toFloat() * img.height.toFloat() / img.width))
        val ink = ImageStripEncoder.dotGrid(img, cols, rows, spec.threshold)
        return ink to intArrayOf(cols, rows)
    }

    private fun normalize(
        img: GrayImage,
        mode: StockNormalizerMode,
    ): Pair<BooleanArray, IntArray> {
        val (raw, dim) = dotGrid(img)
        val ink = TactileStrokeNormalizer.normalize(
            raw, dim[0], dim[1], TactileStrokeNormalizer.Config(mode = mode)
        )
        return ink to dim
    }

    private fun isolated(ink: BooleanArray, cols: Int, rows: Int): Int {
        var n = 0
        for (y in 0 until rows) for (x in 0 until cols) {
            if (!ink[y * cols + x]) continue
            var neighbors = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue
                if (ink[ny * cols + nx]) neighbors++
            }
            if (neighbors <= 1) n++
        }
        return n
    }

    private fun bands(ink: BooleanArray, dim: IntArray): Int {
        val slice = TactileStripBookBuilder.stripEmpty(
            TactileStripSlicer.slice(ink, dim[0], dim[1], spec)
        )
        return slice.totalBands
    }

    @Test
    fun intactOutlinesAutoByteIdenticalToOff() {
        val fixtures = TactileShapeFixtures.all()
        listOf("圆", "方框", "心形", "字母A").forEach { name ->
            val img = fixtures.getValue(name)
            val (off, dimOff) = normalize(img, StockNormalizerMode.OFF)
            val (auto, dimAuto) = normalize(img, StockNormalizerMode.AUTO)

            assertEquals("$name 行列数应一致", dimOff.toList(), dimAuto.toList())
            assertEquals("$name AUTO 应与 OFF 逐字节相同", off.toList(), auto.toList())
            assertEquals(
                "$name AUTO 带数应与 OFF 相同",
                bands(off, dimOff), bands(auto, dimAuto),
            )
            // 门控②：完整轮廓无断裂迹象。
            assertTrue("$name 不应判为有断裂迹象", !TactileStrokeNormalizer.hasFragmentationSign(off, dimOff[0], dimOff[1]))
        }
    }

    @Test
    fun arrowAutoReducesIsolatedDots() {
        val img = TactileShapeFixtures.all().getValue("箭头")
        val (off, dimOff) = normalize(img, StockNormalizerMode.OFF)
        val (auto, dimAuto) = normalize(img, StockNormalizerMode.AUTO)

        val offIsolated = isolated(off, dimOff[0], dimOff[1])
        val autoIsolated = isolated(auto, dimAuto[0], dimAuto[1])
        assertTrue(
            "箭头 AUTO 后孤立点应下降: $offIsolated → $autoIsolated",
            autoIsolated < offIsolated,
        )
        // 原墨点一个不丢。
        for (i in off.indices) assertTrue(!off[i] || auto[i])
    }

    @Test
    fun planBlockedByGateAndByteIdenticalToOff() {
        val img = TactileShapeFixtures.all().getValue("平面图")
        val (off, dimOff) = normalize(img, StockNormalizerMode.OFF)
        val (auto, dimAuto) = normalize(img, StockNormalizerMode.AUTO)

        // 平面图不满足断裂门控，AUTO 原样返回。
        assertEquals("平面图 AUTO 应与 OFF 逐字节相同", off.toList(), auto.toList())
        assertEquals(
            "平面图 AUTO 带数应与 OFF 相同",
            bands(off, dimOff), bands(auto, dimAuto),
        )
    }

    @Test
    fun fragmentationSignSeparatesShapes() {
        val fixtures = TactileShapeFixtures.all()
        listOf("圆", "方框", "心形", "字母A", "平面图").forEach { name ->
            val (ink, dim) = dotGrid(fixtures.getValue(name))
            assertTrue("$name 不应有断裂迹象", !TactileStrokeNormalizer.hasFragmentationSign(ink, dim[0], dim[1]))
        }
        listOf("箭头", "房屋").forEach { name ->
            val (ink, dim) = dotGrid(fixtures.getValue(name))
            assertTrue("$name 应有断裂迹象", TactileStrokeNormalizer.hasFragmentationSign(ink, dim[0], dim[1]))
        }
    }
}
