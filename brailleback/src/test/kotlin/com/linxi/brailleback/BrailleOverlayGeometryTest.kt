/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 叠加层点阵几何数学 JVM 单测：内框两分支、8 点位坐标、文字分片。
 * 另含旧内联算法 vs 抽出函数的单次布局耗时 nanoTime 对比（结果打到 stdout）。
 */

package com.linxi.brailleback.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrailleOverlayGeometryTest {

    // dimens 默认值：cellWidth=5dp, cellHeight=15dp, cellPadding=6.5dp。
    private val cellWidth = 5f
    private val cellHeight = 15f
    private val cellPadding = 6.5f

    @Test
    fun outerDimensionsMatchPaddingRule() {
        assertEquals(cellWidth + cellPadding * 2,
            BrailleCellGeometry.outerWidth(cellWidth, cellPadding), 1e-6f)
        assertEquals(cellHeight + cellPadding * 2,
            BrailleCellGeometry.outerHeight(cellHeight, cellPadding), 1e-6f)
    }

    @Test
    fun innerRectHorizontalBranchWhenThreeWidthsFitHeight() {
        // 3*cellWidth == cellHeight -> 横向补 padding 分支。
        val rect = BrailleCellGeometry.innerRect(cellWidth, cellHeight, cellPadding)
        assertEquals(cellHeight / 3, rect.innerWidth, 1e-6f)
        assertEquals(cellHeight, rect.innerHeight, 1e-6f)
        assertEquals(cellPadding, rect.offsetY, 1e-6f)
        // 水平居中。
        val expectedX = cellPadding + (cellWidth - cellHeight / 3) / 2
        assertEquals(expectedX, rect.offsetX, 1e-6f)
    }

    @Test
    fun innerRectVerticalBranchWhenWidthTooNarrow() {
        // 3*cellWidth < cellHeight -> 纵向补 padding 分支。
        val rect = BrailleCellGeometry.innerRect(cellWidth, 30f, cellPadding)
        assertEquals(cellWidth, rect.innerWidth, 1e-6f)
        assertEquals(cellWidth * 3, rect.innerHeight, 1e-6f)
        assertEquals(cellPadding, rect.offsetX, 1e-6f)
        val expectedY = cellPadding + (30f - cellWidth * 3) / 2
        assertEquals(expectedY, rect.offsetY, 1e-6f)
    }

    @Test
    fun dotCentersUseEightPositionRatios() {
        val rect = BrailleCellGeometry.innerRect(cellWidth, cellHeight, cellPadding)
        // dot 1: 列比 0、行比 0。
        val (x1, y1) = BrailleCellGeometry.dotCenter(rect, 0)
        assertEquals(rect.offsetX, x1, 1e-6f)
        assertEquals(rect.offsetY, y1, 1e-6f)
        // dot 5: 列比 1、行比 0.33。
        val (x5, y5) = BrailleCellGeometry.dotCenter(rect, 4)
        assertEquals(rect.offsetX + rect.innerWidth, x5, 1e-6f)
        assertEquals(rect.offsetY + 0.33f * rect.innerHeight, y5, 1e-6f)
        // dot 8: 列比 1、行比 1.00。
        val (x8, y8) = BrailleCellGeometry.dotCenter(rect, 7)
        assertEquals(rect.offsetX + rect.innerWidth, x8, 1e-6f)
        assertEquals(rect.offsetY + rect.innerHeight, y8, 1e-6f)
    }

    @Test
    fun textSegmentsSliceByPositions() {
        // 16 格，文字 8 个字符，每两格共享一个字符。
        val positions = IntArray(16) { it / 2 }
        val segments = BrailleCellGeometry.textSegments(positions, 16, 8)
        assertEquals(8, segments.size)
        assertEquals(0, segments[0].brailleStart)
        assertEquals(2, segments[0].brailleEnd)
        assertEquals(0, segments[0].textStart)
        assertEquals(1, segments[0].textEnd)
        assertEquals(14, segments[7].brailleStart)
        assertEquals(16, segments[7].brailleEnd)
        assertEquals(7, segments[7].textStart)
        assertEquals(8, segments[7].textEnd)
    }

    @Test
    fun textSegmentsEmptyWhenNoCells() {
        assertTrue(
            BrailleCellGeometry.textSegments(IntArray(0), 0, 0).isEmpty()
        )
    }

    /**
     * 旧内联算法（复刻原 BrailleView.onDraw 的分支与点位计算）vs 抽出的纯函数，
     * 单次布局耗时对比。结果打到 stdout；这里只断言抽出函数没有数量级退化。
     */
    @Test
    fun geometryBenchmarkNoRegression() {
        val iterations = 200_000
        val w = cellWidth
        val h = cellHeight
        val p = cellPadding

        // 旧：内联在 onDraw 里的分支计算。
        val oldStart = System.nanoTime()
        var oldChecksum = 0.0
        repeat(iterations) {
            val innerWidth: Float
            val innerHeight: Float
            val offsetX: Float
            val offsetY: Float
            if (3 * w >= h) {
                innerWidth = h / 3
                innerHeight = h
                offsetX = p + (w - innerWidth) / 2
                offsetY = p
            } else {
                innerWidth = w
                innerHeight = w * 3
                offsetX = p
                offsetY = p + (h - innerHeight) / 2
            }
            oldChecksum += innerWidth + innerHeight + offsetX + offsetY
        }
        val oldNanos = System.nanoTime() - oldStart

        // 新：抽出的纯函数。
        val newStart = System.nanoTime()
        var newChecksum = 0.0
        repeat(iterations) {
            val r = BrailleCellGeometry.innerRect(w, h, p)
            newChecksum += r.innerWidth + r.innerHeight + r.offsetX + r.offsetY
        }
        val newNanos = System.nanoTime() - newStart

        val oldPerOp = oldNanos.toDouble() / iterations
        val newPerOp = newNanos.toDouble() / iterations
        println("[C2 几何基准] 旧内联 ${String.format("%.2f", oldPerOp)} ns/格, " +
            "新函数 ${String.format("%.2f", newPerOp)} ns/格, " +
            "比值 ${String.format("%.2f", newPerOp / oldPerOp)}")
        assertEquals(oldChecksum, newChecksum, 1e-3)
        // 抽出函数允许少量调用开销，但不得超过旧内联的 3 倍。
        assertTrue(
            "新几何函数退化过多：${newPerOp}ns vs 旧 ${oldPerOp}ns",
            newPerOp <= oldPerOp * 3,
        )
    }
}
