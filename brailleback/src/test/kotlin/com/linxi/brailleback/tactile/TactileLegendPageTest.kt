/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 图例页接线断言：
 * - 小字母 / 数字类小连通域被检出，源行含「n 处」与带号 / 方号；
 * - 空检测不崩，图例行给「未检测到图内标注」；
 * - 打包字节长度正确、6/8 点合法；
 * - 措辞只报「标注位置」，不声称识别出标签文字内容。
 */
class TactileLegendPageTest {

    private val cols = 16
    private val rows = 12

    private fun inkArray(inkAt: (x: Int, y: Int) -> Boolean): BooleanArray =
        BooleanArray(cols * rows) { i -> inkAt(i % cols, i / cols) }

    private fun glyph(pts: MutableSet<Pair<Int, Int>>, ox: Int, oy: Int) {
        val rows = listOf(".#.", "###", "###", ".#.")
        rows.forEachIndexed { ry, row ->
            row.forEachIndexed { rx, c -> if (c == '#') pts.add(ox + rx to oy + ry) }
        }
    }

    @Test
    fun detectedBoxesProduceCountAndPositionLines() {
        val pts = HashSet<Pair<Int, Int>>()
        glyph(pts, 2, 7)
        glyph(pts, 10, 2)
        val ink = inkArray { x, y -> pts.contains(x to y) }

        val boxes = TactileLabelDetector.detect(
            ink, cols, rows, TactileLabelDetector.Config(enabled = true)
        )
        assertEquals(2, boxes.size)

        val source = TactileLegendPage.labelSourceLines(
            boxes, bandRows = 4, cellsPerLine = 20,
        )
        val joined = source.joinToString("|")
        assertTrue(joined.contains("检测到 2 处图内标注"))
        assertTrue(joined.contains("第 1 带"))
        assertTrue(joined.contains("第 2 带"))
        // 位置行含方号。
        assertTrue(source.any { it.startsWith("第") && it.contains("方") })
        // 只报位置：源行不得声称识别出文字内容。
        assertFalse("不得出现「标注文字」这类内容识别措辞", joined.contains("标注文字"))
    }

    @Test
    fun emptyDetectionDoesNotCrashAndSaysSo() {
        val ink = inkArray { _, _ -> false }
        val boxes = TactileLabelDetector.detect(
            ink, cols, rows, TactileLabelDetector.Config(enabled = true)
        )
        assertTrue(boxes.isEmpty())
        val source = TactileLegendPage.labelSourceLines(boxes, 4, 20)
        assertEquals(1, source.size)
        assertEquals(TactileLegendPage.EMPTY_MESSAGE, source.first())
    }

    @Test
    fun packedBytesLegalForSixAndEightDot() {
        val spec8 = ImageStripEncoder.Spec.forDisplay(20, 1, 8)
        val spec6 = ImageStripEncoder.Spec.forDisplay(20, 1, 6)
        // ⠁ = 点 1；fake 翻译行。
        val braille = listOf("⠁⠂")

        val out8 = TactileLegendPage.build(braille, spec8)
        assertEquals(20, out8.size)
        assertEquals(1, out8[0].toInt() and 0xFF)
        assertEquals(2, out8[1].toInt() and 0xFF)

        val out6 = TactileLegendPage.build(braille, spec6)
        assertEquals(20, out6.size)
        for (b in out6) assertEquals(0, b.toInt() and 0xC0)
    }
}
