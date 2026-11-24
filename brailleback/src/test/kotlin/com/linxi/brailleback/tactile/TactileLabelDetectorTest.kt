/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签区识别原型断言：小连通域判为候选标签，长线条 / 大轮廓不判；开关默认关闭。
 */
class TactileLabelDetectorTest {

    private val cols = 16
    private val rows = 12

    private fun inkArray(inkAt: (x: Int, y: Int) -> Boolean): BooleanArray =
        BooleanArray(cols * rows) { i -> inkAt(i % cols, i / cols) }

    /** 画一个 3×4 的小字形块（类笔画簇）。 */
    private fun glyph(pts: MutableSet<Pair<Int, Int>>, ox: Int, oy: Int) {
        val rows = listOf(".#.", "###", "###", ".#.")
        rows.forEachIndexed { ry, row ->
            row.forEachIndexed { rx, c -> if (c == '#') pts.add(ox + rx to oy + ry) }
        }
    }

    @Test
    fun smallComponentDetectedAsLabel() {
        val pts = HashSet<Pair<Int, Int>>()
        glyph(pts, 6, 4)
        val ink = inkArray { x, y -> pts.contains(x to y) }

        val boxes = TactileLabelDetector.detect(
            ink, cols, rows, TactileLabelDetector.Config(enabled = true)
        )
        assertEquals(1, boxes.size)
        val box = boxes.first()
        assertEquals(6, box.x)
        assertEquals(4, box.y)
        assertEquals(3, box.width)
        assertEquals(4, box.height)
        assertTrue(box.area > 0)
    }

    @Test
    fun longStrokeNotTreatedAsLabel() {
        // 12 点长横线：宽度超出标签上限，不判为标签。
        val ink = inkArray { x, y -> y == 2 && x in 0..11 }
        val boxes = TactileLabelDetector.detect(
            ink, cols, rows, TactileLabelDetector.Config(enabled = true)
        )
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun multipleClustersSortedByPosition() {
        val pts = HashSet<Pair<Int, Int>>()
        glyph(pts, 2, 7)
        glyph(pts, 10, 2)
        val ink = inkArray { x, y -> pts.contains(x to y) }

        val boxes = TactileLabelDetector.detect(
            ink, cols, rows, TactileLabelDetector.Config(enabled = true)
        )
        assertEquals(2, boxes.size)
        // 先上后下、先左后右。
        assertTrue(boxes[0].y < boxes[1].y)
    }

    @Test
    fun disabledReturnsEmpty() {
        val pts = HashSet<Pair<Int, Int>>()
        glyph(pts, 6, 4)
        val ink = inkArray { x, y -> pts.contains(x to y) }
        val boxes = TactileLabelDetector.detect(ink, cols, rows, TactileLabelDetector.Config.OFF)
        assertTrue(boxes.isEmpty())
    }
}
