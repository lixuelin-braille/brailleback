/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 薄带合并原型断言：
 * - 只跨整行空白行合并，含墨行隔开的两带不并；
 * - 合并后总高不超上限；
 * - 合并前后含墨行并集不变，字节由全局点阵重新打包；
 * - 开关默认关闭，原样返回。
 */
class TactileBandPackerTest {

    private val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
    private val cols = 8
    private val rows = 8

    private fun inkArray(inkAt: (x: Int, y: Int) -> Boolean): BooleanArray =
        BooleanArray(cols * rows) { i -> inkAt(i % cols, i / cols) }

    private fun makeBand(
        ink: BooleanArray, x0: Int, y0: Int, height: Int,
    ): Band = Band(
        bytes = TactileStripSlicer.toBytes(ink, cols, x0, y0, height, spec),
        x0 = x0,
        y0 = y0,
        height = height,
        overlapTop = 0,
        overlapBottom = 0,
        bandIndex = 0,
        total = 0,
    )

    private fun makeSlice(bands: List<Band>): SliceResult = SliceResult(
        bands = bands,
        totalBands = bands.size,
        cols = cols,
        rows = rows,
        pageCols = spec.cellsPerLine * 2,
        bandRows = spec.linesPerScreen * 4,
    )

    /** 切片结果实际覆盖到的含墨行并集（空白行不计入）。 */
    private fun inkRowsInBands(
        slice: SliceResult, ink: BooleanArray, cols: Int, rows: Int,
    ): Set<Int> {
        val rowHasInk = BooleanArray(rows) { r ->
            (0 until cols).any { ink[r * cols + it] }
        }
        val out = mutableSetOf<Int>()
        slice.bands.forEach { band ->
            for (r in band.y0 until band.y0 + band.height) {
                if (rowHasInk[r]) out.add(r)
            }
        }
        return out
    }

    @Test
    fun mergesBandsAcrossBlankGapWithinHeightCap() {
        // 第 0 行、第 4 行各一条横线，中间 1..3 行空白。
        val ink = inkArray { x, y -> y == 0 || y == 4 }
        val slice = makeSlice(listOf(makeBand(ink, 0, 0, 2), makeBand(ink, 0, 4, 2)))

        val packed = TactileBandPacker.pack(
            ink, cols, rows, slice, spec,
            TactileBandPacker.Config(enabled = true, maxBandRows = 8),
        )

        assertEquals(1, packed.bands.size)
        val merged = packed.bands.first()
        assertEquals(0, merged.y0)
        assertEquals(6, merged.height)
        // 字节由全局点阵在合并区间重新打包，与 toBytes 一致。
        val expected = TactileStripSlicer.toBytes(ink, cols, 0, 0, 6, spec)
        assertTrue(merged.bytes.contentEquals(expected))
        // 含墨行并集不变。
        assertEquals(inkRowsInBands(slice, ink, cols, rows), inkRowsInBands(packed, ink, cols, rows))
    }

    @Test
    fun doesNotMergeAcrossInkRow() {
        // 第 3 行也有墨 → 两带之间隔着含墨行，禁止合并。
        val ink = inkArray { x, y -> y == 0 || y == 3 || y == 4 }
        val slice = makeSlice(listOf(makeBand(ink, 0, 0, 2), makeBand(ink, 0, 4, 2)))

        val packed = TactileBandPacker.pack(
            ink, cols, rows, slice, spec,
            TactileBandPacker.Config(enabled = true, maxBandRows = 8),
        )
        assertEquals(2, packed.bands.size)
        assertEquals(inkRowsInBands(slice, ink, cols, rows), inkRowsInBands(packed, ink, cols, rows))
    }

    @Test
    fun doesNotMergeWhenCombinedHeightExceedsCap() {
        val ink = inkArray { x, y -> y == 0 || y == 4 }
        val slice = makeSlice(listOf(makeBand(ink, 0, 0, 2), makeBand(ink, 0, 4, 2)))

        // 默认带高 4 行；合并后 6 行超限，不并。
        val packed = TactileBandPacker.pack(
            ink, cols, rows, slice, spec, TactileBandPacker.Config(enabled = true),
        )
        assertEquals(2, packed.bands.size)
    }

    @Test
    fun disabledReturnsOriginalSlice() {
        val ink = inkArray { x, y -> y == 0 || y == 4 }
        val slice = makeSlice(listOf(makeBand(ink, 0, 0, 2), makeBand(ink, 0, 4, 2)))

        val packed = TactileBandPacker.pack(
            ink, cols, rows, slice, spec, TactileBandPacker.Config.OFF
        )
        assertSame(slice, packed)
    }

    @Test
    fun integrationWithRealSlicerPreservesInkUnion() {
        // 两条上下分离的横线，走真实切片器后再合并；含墨行并集必须不变。
        val ink = inkArray { x, y -> y == 1 || y == 6 }
        val raw = TactileStripSlicer.slice(ink, cols, rows, spec)

        val packed = TactileBandPacker.pack(
            ink, cols, rows, raw, spec,
            TactileBandPacker.Config(enabled = true, maxBandRows = 8),
        )
        assertEquals(inkRowsInBands(raw, ink, cols, rows), inkRowsInBands(packed, ink, cols, rows))
        assertTrue(packed.bands.size <= raw.bands.size)
        // 合并后每带字节长度仍是整屏方数。
        packed.bands.forEach { band ->
            assertEquals(spec.linesPerScreen * spec.cellsPerLine, band.bytes.size)
        }
    }
}
