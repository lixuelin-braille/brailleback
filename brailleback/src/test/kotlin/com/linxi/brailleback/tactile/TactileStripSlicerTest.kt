/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切片规则：空白行优先、无空白按线宽重叠 1～2 行、不丢墨、末带补空、6/8 点带高正确。
 */
class TactileStripSlicerTest {

    private fun inkArray(cols: Int, rows: Int, inkAt: (x: Int, y: Int) -> Boolean): BooleanArray =
        BooleanArray(cols * rows) { i -> inkAt(i % cols, i / cols) }

    @Test
    fun blankRowCutNoOverlap() {
        // 第 4、8 行整行无墨 → 切缝落在空白行，前后带不重叠。
        val ink = inkArray(8, 12) { _, y -> y != 4 && y != 8 }
        val slice = TactileStripSlicer.slice(
            ink, 8, 12, ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        )
        assertEquals(3, slice.bands.size)
        val first = slice.bands[0]
        val second = slice.bands[1]
        assertEquals(0, first.y0)
        assertEquals(4, first.height)
        assertEquals(0, first.overlapBottom)
        assertEquals(4, second.y0)
        assertEquals(0, second.overlapTop)
        // 两段行区间不相交。
        assertTrue(first.y0 + first.height <= second.y0)
    }

    @Test
    fun noBlankRowOverlapOneRow() {
        // 边界两行各有墨但列不相交（细线）→ 重叠 1 行。
        val ink = inkArray(8, 9) { x, y ->
            when (y) {
                3 -> x < 2
                4 -> x >= 6
                else -> true
            }
        }
        val slice = TactileStripSlicer.slice(
            ink, 8, 9, ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        )
        assertTrue(slice.bands.size >= 2)
        val first = slice.bands[0]
        val second = slice.bands[1]
        assertEquals(4, first.height)
        assertEquals(1, first.overlapBottom)
        assertEquals(1, second.overlapTop)
        assertEquals(first.y0 + slice.bandRows - 1, second.y0)
    }

    @Test
    fun twoPixelLineWidthOverlapTwoRows() {
        // 边界两行同列有墨（竖向 2 点线宽）→ 重叠 2 行。
        val ink = inkArray(8, 9) { _, _ -> true }
        val slice = TactileStripSlicer.slice(
            ink, 8, 9, ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        )
        assertTrue(slice.bands.size >= 2)
        val first = slice.bands[0]
        val second = slice.bands[1]
        assertEquals(2, first.overlapBottom)
        assertEquals(2, second.overlapTop)
        assertEquals(first.y0 + slice.bandRows - 2, second.y0)
    }

    @Test
    fun everyInkRowCoveredBySomeBand() {
        val ink = inkArray(8, 12) { x, y -> (x + y) % 3 == 0 }
        val slice = TactileStripSlicer.slice(
            ink, 8, 12, ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        )
        val covered = BooleanArray(12)
        slice.bands.forEach { band ->
            for (r in band.y0 until band.y0 + band.height) covered[r] = true
        }
        for (r in 0 until 12) {
            assertTrue("第 $r 行未被任何带覆盖", covered[r])
        }
    }

    @Test
    fun lastBandShortScreenPaddedEmpty() {
        // 10 行，第 4、8 行空白对齐切缝 → 末带 2 行；字节长度仍为 4，缺的两行补空。
        val ink = inkArray(8, 10) { _, y -> y != 4 && y != 8 }
        val slice = TactileStripSlicer.slice(
            ink, 8, 10, ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        )
        val last = slice.bands.last()
        assertEquals(2, last.height)
        assertEquals(4, last.bytes.size)
        // 8 点每行 4 个点行；末带只占前 2 行，第 3/4 行对应的点位（bit2/5、bit6/7）必须为空。
        val row2And3Mask = 0x24 or 0xC0
        last.bytes.forEach { b ->
            assertEquals(0, b.toInt() and row2And3Mask)
        }
    }

    @Test
    fun bandRowsByDotCount() {
        val eight = TactileStripSlicer.slice(
            BooleanArray(8 * 4) { true }, 8, 4,
            ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        )
        assertEquals(4, eight.bandRows)
        val six = TactileStripSlicer.slice(
            BooleanArray(8 * 3) { true }, 8, 3,
            ImageStripEncoder.Spec.forDisplay(4, 1, 6)
        )
        assertEquals(3, six.bandRows)
    }

    @Test
    fun horizontalCutWhenColsExceedPageCols() {
        // 16 点列，一行 4 方（8 点列/屏）→ 无整列空白时横向重叠 1 列，切三段。
        val ink = inkArray(16, 4) { _, _ -> true }
        val slice = TactileStripSlicer.slice(
            ink, 16, 4, ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        )
        assertEquals(3, slice.bands.size)
        assertEquals(0, slice.bands[0].x0)
        assertEquals(7, slice.bands[1].x0)
        assertEquals(14, slice.bands[2].x0)
    }
}
