/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成书顺序：封面 → 图形带 → 封底；totalBands 与切片一致；图形页字节只来自点阵。
 */
class TactileStripBookTest {

    private fun slice(rows: Int): SliceResult {
        val ink = BooleanArray(8 * rows) { true }
        return TactileStripSlicer.slice(
            ink, 8, rows, ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        )
    }

    @Test
    fun coverGraphicBackOrder() {
        val s = slice(10)
        val cover = List(2) { ByteArray(4) }
        val back = List(1) { ByteArray(4) }
        val book = TactileStripBookBuilder.build(
            s, ImageStripEncoder.Spec.forDisplay(4, 1, 8), cover, back
        )

        assertEquals(s.totalBands, book.totalBands)
        assertEquals(2 + s.totalBands + 1, book.pages.size)

        assertEquals(StripPageKind.COVER, book.pages[0].kind)
        assertEquals(StripPageKind.COVER, book.pages[1].kind)
        for (i in 2 until 2 + s.totalBands) {
            assertEquals(StripPageKind.GRAPHIC, book.pages[i].kind)
        }
        assertEquals(StripPageKind.BACK, book.pages.last().kind)

        // 封底必须排在最后一条图形带之后。
        val lastGraphic = book.pages.indexOfLast { it.kind == StripPageKind.GRAPHIC }
        assertTrue(lastGraphic < book.pages.lastIndex)
    }

    @Test
    fun graphicBytesComeFromBand() {
        val s = slice(4)
        val book = TactileStripBookBuilder.build(
            s, ImageStripEncoder.Spec.forDisplay(4, 1, 8),
            listOf(ByteArray(4)), listOf(ByteArray(4))
        )
        val graphic = book.pages.first { it.kind == StripPageKind.GRAPHIC }
        assertEquals(s.bands[0].bytes.toList(), graphic.bytes.toList())
        assertEquals(1, graphic.bandIndex)
        assertEquals(s.totalBands, graphic.totalBands)
    }

    @Test
    fun coverBackPagesAreNotGraphic() {
        val s = slice(4)
        val book = TactileStripBookBuilder.build(
            s, ImageStripEncoder.Spec.forDisplay(4, 1, 8),
            listOf(ByteArray(4)), listOf(ByteArray(4))
        )
        book.pages.filter { it.kind != StripPageKind.GRAPHIC }.forEach {
            assertEquals(0, it.bandIndex)
        }
    }

    // ------------------------------------------------- 全空图形带剔除

    private fun inkArray(cols: Int, rows: Int, inkAt: (x: Int, y: Int) -> Boolean): BooleanArray =
        BooleanArray(cols * rows) { i -> inkAt(i % cols, i / cols) }

    @Test
    fun emptyBandsDroppedAndNoEmptyGraphicPage() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        // 0–3 行有墨，4–8 行全空，9–11 行有墨 → 中间产生全空带。
        val ink = inkArray(8, 12) { _, y -> y < 4 || y >= 9 }
        val raw = TactileStripSlicer.slice(ink, 8, 12, spec)
        val prepared = TactileStripBookBuilder.stripEmpty(raw)

        assertTrue("剔除前 ${raw.totalBands} 带，剔除后 ${prepared.totalBands} 带",
            prepared.totalBands < raw.totalBands)

        val book = TactileStripBookBuilder.build(
            raw, spec, listOf(ByteArray(4)), listOf(ByteArray(4))
        )
        val graphics = book.pages.filter { it.kind == StripPageKind.GRAPHIC }
        assertEquals(prepared.totalBands, graphics.size)
        // 不留全空图形页。
        graphics.forEach { page ->
            assertTrue("存在全空图形页", page.bytes.any { it.toInt() != 0 })
        }
        assertEquals(prepared.totalBands, book.totalBands)

        // 封面「共 N 带」与剔除后总数一致。
        val cover = TactileStripCoverText.coverLines(
            4, prepared.bandRows, book.totalBands, 8, false, maxLines = 1,
        )
        assertTrue(cover.joinToString("|").contains("共 ${book.totalBands} 带"))
    }

    @Test
    fun whiteImageKeepsOneGraphicBand() {
        val spec = ImageStripEncoder.Spec.forDisplay(20, 1, 8)
        val ink = BooleanArray(8 * 4) { false }
        val raw = TactileStripSlicer.slice(ink, 8, 4, spec)
        val book = TactileStripBookBuilder.build(
            raw, spec, listOf(ByteArray(20)), listOf(ByteArray(20))
        )
        val graphics = book.pages.filter { it.kind == StripPageKind.GRAPHIC }
        assertEquals(1, graphics.size)
        // 全白图图形页仍全 0。
        graphics.forEach { page ->
            assertTrue(page.bytes.all { it.toInt() == 0 })
        }
    }

    @Test
    fun inkRowUnionUnchangedAfterFilter() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val ink = inkArray(8, 12) { _, y -> y < 4 || y >= 9 }
        val raw = TactileStripSlicer.slice(ink, 8, 12, spec)
        val prepared = TactileStripBookBuilder.stripEmpty(raw)

        val inkRows = BooleanArray(12)
        for (y in 0 until 12) {
            for (x in 0 until 8) if (ink[y * 8 + x]) inkRows[y] = true
        }
        val covered = BooleanArray(12)
        prepared.bands.forEach { band ->
            for (r in band.y0 until band.y0 + band.height) covered[r] = true
        }
        inkRows.forEachIndexed { row, hasInk ->
            if (hasInk) assertTrue("含墨行 $row 剔除后丢失", covered[row])
        }
    }
}
