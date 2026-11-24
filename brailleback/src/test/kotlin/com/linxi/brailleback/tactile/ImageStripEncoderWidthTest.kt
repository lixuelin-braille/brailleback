/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 为什么需要它：
 * 旧实现网格宽写死 64，40 方（80 点列）一行时第 32–39 方永远读不到像素，横向也不能切段。
 * 这里用合成灰度图把「默认宽度铺满一行」「最右侧八方确实取到像素」「6 点高位不亮」
 * 「40 方一行不产生横向第二段」四件事钉死，防止宽度模型再回退。
 */
class ImageStripEncoderWidthTest {

    private fun image(width: Int, height: Int, black: (Int, Int) -> Boolean): GrayImage =
        GrayImage(width, height, IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (black(x, y)) 0 else 255
        })

    /** 默认 / forDisplay 的横向点列必须等于 cellsPerLine×2，阈值走 Otsu。 */
    @Test
    fun defaultDotColumnsFillOneLine() {
        assertEquals(80, ImageStripEncoder.Spec(cellsPerLine = 40).dotColumns)
        assertEquals(40, ImageStripEncoder.Spec(cellsPerLine = 20).dotColumns)

        val spec = ImageStripEncoder.Spec.forDisplay(40, 1, 8)
        assertEquals(80, spec.dotColumns)
        assertEquals(-1, spec.threshold)
    }

    /** 右半 16 点列竖条涂黑：第 32–39 方必须有点（旧 64 列模型下这八方恒空）。 */
    @Test
    fun rightmostCellsReceivePixels() {
        val image = image(80, 40) { x, _ -> x >= 64 }
        val pages = ImageStripEncoder.encode(
            image, ImageStripEncoder.Spec.forDisplay(40, 1, 8))
        // 80 点列正好一页宽，不横向切段；40 点行 / 4 = 10 页。
        assertEquals(10, pages.size)
        val first = pages[0]
        assertEquals(40, first.size)
        for (cell in 0 until 32) {
            assertEquals("第 $cell 方应为空", 0, first[cell].toInt() and 0xFF)
        }
        for (cell in 32 until 40) {
            assertNotEquals("第 $cell 方应有黑点", 0, first[cell].toInt() and 0xFF)
        }
    }

    /** 左半黑右半白：前 20 方有点、后 20 方无点。 */
    @Test
    fun leftHalfInkedRightHalfBlank() {
        val image = image(80, 40) { x, _ -> x < 40 }
        val pages = ImageStripEncoder.encode(
            image, ImageStripEncoder.Spec.forDisplay(40, 1, 8))
        val first = pages[0]
        for (cell in 0 until 20) {
            assertNotEquals("第 $cell 方应有黑点", 0, first[cell].toInt() and 0xFF)
        }
        for (cell in 20 until 40) {
            assertEquals("第 $cell 方应为空", 0, first[cell].toInt() and 0xFF)
        }
    }

    /** 6 点模式下每字节第 7/8 点（bit6/bit7）不得出现。 */
    @Test
    fun sixDotHighBitsNeverSet() {
        val image = image(64, 48) { x, y -> (x + y) % 3 == 0 }
        val pages = ImageStripEncoder.encode(
            image, ImageStripEncoder.Spec.forDisplay(20, 1, 6))
        assertTrue(pages.isNotEmpty())
        for (page in pages) {
            for (b in page) {
                assertEquals(0, b.toInt() and 0xC0)
            }
        }
    }

    /** 40 方 1 行：横向只有一段，页数不被横向切段翻倍，每页长度恒为 40。 */
    @Test
    fun fortyCellsOneLineNoHorizontalSegment() {
        val image = image(80, 40) { x, y -> x < 40 && y < 20 }
        val pages = ImageStripEncoder.encode(
            image, ImageStripEncoder.Spec.forDisplay(40, 1, 8))
        assertEquals(10, pages.size)
        pages.forEach { assertEquals(40, it.size) }
    }
}
