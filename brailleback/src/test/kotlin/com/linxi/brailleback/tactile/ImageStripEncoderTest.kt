/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 为什么需要它：
 * 点显字节是和 liblouis/BRLTTY 的硬契约：一个字节一方，dot1=bit0 … dot8=bit7。
 * 位序、6/8 点行高、横向切页顺序只要错一处，屏幕就会显示成镜像或错行。
 * 这里用 1 点 = 1 像素的小图手算期望字节，不拿编码器输出反推自己。
 */
class ImageStripEncoderTest {

    private fun image(width: Int, height: Int, black: (Int, Int) -> Boolean): GrayImage =
        GrayImage(width, height, IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (black(x, y)) 0 else 255
        })

    /** 左上角 2×4 全黑 → 第一方 8 点全亮 = 0xFF。 */
    @Test
    fun eightDotFirstCellAllBits() {
        val image = image(64, 48) { x, y -> x < 2 && y < 4 }
        val pages = ImageStripEncoder.encode(
            image, ImageStripEncoder.Spec(cellsPerLine = 40, dotsPerCell = 8, dotColumns = 64))
        // 64 点列 < 80 点/页 → 每带一页；48 行 / 4 行 = 12 页
        assertEquals(12, pages.size)
        assertEquals(40, pages[0].size)
        assertEquals(0xFF, pages[0][0].toInt() and 0xFF)
        assertEquals(0, pages[0][1].toInt() and 0xFF)
        // 第 2 行（4..7）没有黑点
        assertEquals(0, pages[1][0].toInt() and 0xFF)
    }

    /** 左上角 2×3 全黑 → 6 点全亮 = 0x3F；第 4 行不属于 6 点方。 */
    @Test
    fun sixDotFirstCell() {
        val image = image(64, 48) { x, y -> x < 2 && y < 3 }
        val pages = ImageStripEncoder.encode(
            image, ImageStripEncoder.Spec(cellsPerLine = 40, dotsPerCell = 6, dotColumns = 64))
        // 48 行 / 3 行 = 16 页
        assertEquals(16, pages.size)
        assertEquals(0x3F, pages[0][0].toInt() and 0xFF)
    }

    /** 20 方一行：64 点列切成 2 段，横向第 2 段的第 5 方接住 x=50 的竖线。 */
    @Test
    fun twentyCellsWrapHorizontally() {
        val image = image(64, 48) { x, _ -> x == 50 }
        val pages = ImageStripEncoder.encode(
            image, ImageStripEncoder.Spec(cellsPerLine = 20, dotsPerCell = 8, dotColumns = 64))
        // 2 段 × 12 带 = 24 页；第一段 0..39 没有竖线
        assertEquals(24, pages.size)
        assertEquals(20, pages[0].size)
        assertEquals(0, pages[0][5].toInt() and 0xFF)
        // 第二段 40..79 的局部列 10 → 第 5 方左列；4 行全亮 = dot1/2/3/7 = 1+2+4+64 = 71
        assertEquals(71, pages[1][5].toInt() and 0xFF)
        assertEquals(0, pages[1][4].toInt() and 0xFF)
    }

    /** 4 方 3 行 6 点：8 点列 × 9 点行一页，64×48 要 8 列 × 6 行 = 48 页。 */
    @Test
    fun fourCellsThreeLinesSixDot() {
        val image = image(64, 48) { x, y -> x < 2 && y < 3 }
        val pages = ImageStripEncoder.encode(
            image,
            ImageStripEncoder.Spec(cellsPerLine = 4, linesPerScreen = 3, dotsPerCell = 6, dotColumns = 64))
        assertEquals(48, pages.size)
        assertEquals(12, pages[0].size)
        assertEquals(0x3F, pages[0][0].toInt() and 0xFF)
    }

    /** 4 方 3 行 8 点：8 点列 × 12 点行一页，64×48 要 8 列 × 4 行 = 32 页。 */
    @Test
    fun fourCellsThreeLinesEightDot() {
        val image = image(64, 48) { _, _ -> false }
        val pages = ImageStripEncoder.encode(
            image,
            ImageStripEncoder.Spec(cellsPerLine = 4, linesPerScreen = 3, dotsPerCell = 8, dotColumns = 64))
        assertEquals(32, pages.size)
        assertEquals(12, pages[0].size)
    }
}

