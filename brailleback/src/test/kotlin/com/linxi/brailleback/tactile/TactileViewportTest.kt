/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块模型视口规则：
 * - 总览恰好一屏并带「总览」标记，与细节页不混淆；
 * - 一块固定 4 屏（末块 1–3），80×80 八点 → 5 块；块内翻到末屏返回 BLOCK_END，首屏返回 BLOCK_START；
 * - 块间移动到第一 / 最后一块返回 FIRST_BLOCK / LAST_BLOCK；
 * - 任意细节位置可一键返回总览；
 * - 横向边界左右到头；纵 / 横相邻屏重叠 1 点且与全局点阵逐位一致；
 * - 不丢墨：所有屏覆盖的含墨行并集 == 全局含墨行；
 * - 6 / 8 点行高正确，6 点高位字节清零；
 * - 既有三段式成书 / 分页规则不受影响。
 */
class TactileViewportTest {

    private fun inkArray(
        cols: Int,
        rows: Int,
        inkAt: (x: Int, y: Int) -> Boolean,
    ): BooleanArray = BooleanArray(cols * rows) { i -> inkAt(i % cols, i / cols) }

    // ------------------------------------------------------------------ 总览

    @Test
    fun overviewIsSingleScreenAndMarked() {
        val spec = ImageStripEncoder.Spec.forDisplay(40, 1, 8)
        val vp = TactileViewport(inkArray(80, 80) { _, _ -> true }, 80, 80, spec)

        assertTrue(vp.isOverview)
        assertEquals(TactileViewport.OVERVIEW_LABEL, vp.overviewLabel)
        val bytes = vp.overviewBytes()
        // 一屏 = linesPerScreen × cellsPerLine。
        assertEquals(spec.linesPerScreen * spec.cellsPerLine, bytes.size)
        // 总览不带块号 / 屏号。
        assertEquals(0, vp.currentBandIndex())
    }

    @Test
    fun legendPagePackedAsOwnScreen() {
        val spec = ImageStripEncoder.Spec.forDisplay(20, 1, 8)
        val bytes = TactileLegendPage.build(listOf("⠁⠃"), spec)
        assertEquals(spec.linesPerScreen * spec.cellsPerLine, bytes.size)
        assertEquals(TactileLegendPage.LEGEND_LABEL, "图例")
    }

    // ------------------------------------------------------------------ 块结构（80×80）

    @Test
    fun eightyByEightySplitsIntoFiveBlocksOfFourScreens() {
        val spec = ImageStripEncoder.Spec.forDisplay(40, 1, 8)
        val vp = TactileViewport(inkArray(80, 80) { _, _ -> true }, 80, 80, spec)

        // 8 点 blockRows = 4×4 = 16；ceil(80/16) = 5 块。
        assertEquals(16, vp.blockRows)
        assertEquals(5, vp.blockCount)

        vp.enterDetail()
        assertEquals(0, vp.blockIndex)
        assertEquals(0, vp.blockPageIndex)
        // 首块固定 4 屏，落在验收 2–4 区间。
        assertEquals(4, vp.blockPageCount())

        // 块内翻 3 屏成功，第 4 次到块末。
        repeat(3) { assertTrue(vp.nextPage().moved) }
        val blockEnd = vp.nextPage()
        assertFalse(blockEnd.moved)
        assertEquals(Boundary.BLOCK_END, blockEnd.boundary)
        assertEquals(3, vp.blockPageIndex)

        // 末块屏数也在 1..4。
        vp.nextBlock()
        repeat(3) { vp.nextBlock() }
        assertEquals(4, vp.blockIndex)
        assertTrue("末块屏数 ${vp.blockPageCount()} 应在 1..4", vp.blockPageCount() in 1..4)
    }

    @Test
    fun blockMovementBoundsReportFirstAndLast() {
        val spec = ImageStripEncoder.Spec.forDisplay(40, 1, 8)
        val vp = TactileViewport(inkArray(80, 80) { _, _ -> true }, 80, 80, spec)
        vp.enterDetail()

        // 第一块再往前 → FIRST_BLOCK。
        val first = vp.prevBlock()
        assertFalse(first.moved)
        assertEquals(Boundary.FIRST_BLOCK, first.boundary)

        // 走到最后一块再往后 → LAST_BLOCK。
        repeat(4) { vp.nextBlock() }
        val last = vp.nextBlock()
        assertFalse(last.moved)
        assertEquals(Boundary.LAST_BLOCK, last.boundary)
        assertEquals(4, vp.blockIndex)
    }

    @Test
    fun shortImageBlockPageCountWithinOneToFour() {
        val spec = ImageStripEncoder.Spec.forDisplay(40, 1, 8)
        val vp = TactileViewport(inkArray(80, 12) { _, _ -> true }, 80, 12, spec)
        vp.enterDetail()
        assertEquals(1, vp.blockCount)
        assertTrue("短图块屏数 ${vp.blockPageCount()} 应在 1..4", vp.blockPageCount() in 1..4)
    }

    @Test
    fun blockStartBoundaryKeepsPage() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val vp = TactileViewport(inkArray(8, 8) { _, _ -> true }, 8, 8, spec)
        vp.enterDetail()

        val start = vp.prevPage()
        assertFalse(start.moved)
        assertEquals(Boundary.BLOCK_START, start.boundary)
        assertEquals(0, vp.blockPageIndex)

        vp.nextPage()
        val back = vp.prevPage()
        assertTrue(back.moved)
        assertEquals(0, vp.blockPageIndex)
    }

    @Test
    fun backToOverviewFromAnyBlockPosition() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val vp = TactileViewport(inkArray(16, 32) { _, _ -> true }, 16, 32, spec)
        vp.enterDetail()
        vp.nextBlock()
        vp.nextPage()
        assertEquals(ViewportMode.DETAIL, vp.mode)

        vp.backToOverview()
        assertEquals(ViewportMode.OVERVIEW, vp.mode)
        // 选择框回到当前块起点行（不随块内屏偏移）。
        assertEquals(16, vp.boxRow)
    }

    @Test
    fun overviewBoxMovesBlockAndEntersAligned() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val vp = TactileViewport(inkArray(16, 32) { _, _ -> true }, 16, 32, spec)

        val down = vp.moveBox(0, 1)
        assertTrue(down.moved)
        assertEquals(vp.blockRows, vp.boxRow)

        vp.enterDetail()
        assertEquals(1, vp.blockIndex)
        assertEquals(0, vp.blockPageIndex)
    }

    // ------------------------------------------------------------------ 横向边界

    @Test
    fun leftRightBoundaryKeepsOrigin() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val vp = TactileViewport(inkArray(16, 8) { _, _ -> true }, 16, 8, spec)
        vp.enterDetail()

        val atLeft = vp.originCol
        val left = vp.panLeft()
        assertFalse(left.moved)
        assertEquals(Boundary.LEFT, left.boundary)
        assertEquals(atLeft, vp.originCol)

        vp.panRight()
        vp.panRight()
        val atRight = vp.originCol
        val right = vp.panRight()
        assertFalse(right.moved)
        assertEquals(Boundary.RIGHT, right.boundary)
        assertEquals(atRight, vp.originCol)
    }

    // ------------------------------------------------------------------ 重叠与不丢墨

    @Test
    fun verticalOverlapOneRowCopiedFromSource() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val cols = 8
        val rows = 5
        val ink = inkArray(cols, rows) { _, _ -> true }
        val vp = TactileViewport(ink, cols, rows, spec)
        vp.enterDetail()
        val pages = vp.detailPages()
        assertEquals(2, pages.size)
        val first = pages[0]
        val second = pages[1]

        // 块内相邻屏重叠 1 行；第二屏起点 = 第一屏末行。
        assertEquals(first.y0 + first.height - 1, second.y0)
        val sharedRow = second.y0

        val firstInk = vp.windowInk()
        for (c in 0 until cols) {
            assertEquals(
                ink[sharedRow * cols + c],
                firstInk[(first.height - 1) * vp.windowCols + c],
            )
        }
        vp.nextPage()
        val secondInk = vp.windowInk()
        for (c in 0 until cols) {
            assertEquals(ink[sharedRow * cols + c], secondInk[c])
        }
        val ref = TactileStripSlicer.toBytes(ink, cols, second.x0, second.y0, second.height, spec)
        assertEquals(ref.toList(), vp.windowBytes().toList())
    }

    @Test
    fun horizontalOverlapOneColumnCopiedFromSource() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val cols = 15
        val rows = 3
        val ink = inkArray(cols, rows) { _, _ -> true }
        val vp = TactileViewport(ink, cols, rows, spec)
        vp.enterDetail()
        val pages = vp.detailPages()
        assertEquals(2, pages.size)
        val first = pages[0]
        val second = pages[1]

        assertEquals(first.x0 + first.width - 1, second.x0)
        val sharedCol = second.x0

        vp.panRight()
        val secondInk = vp.windowInk()
        for (r in 0 until rows) {
            assertEquals(ink[r * cols + sharedCol], secondInk[r * vp.windowCols])
        }
        val ref = TactileStripSlicer.toBytes(ink, cols, second.x0, second.y0, second.height, spec)
        assertEquals(ref.toList(), vp.windowBytes().toList())
    }

    @Test
    fun noInkRowLost() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val cols = 8
        val rows = 12
        // 第 4、8 行整行无墨。
        val ink = inkArray(cols, rows) { _, y -> y != 4 && y != 8 }
        val vp = TactileViewport(ink, cols, rows, spec)
        val pages = vp.detailPages()

        val covered = BooleanArray(rows)
        pages.forEach { page ->
            for (r in page.y0 until page.y0 + page.height) covered[r] = true
        }
        for (r in 0 until rows) {
            val hasInk = (0 until cols).any { ink[r * cols + it] }
            if (hasInk) assertTrue("含墨行 $r 丢失", covered[r])
        }
    }

    // ------------------------------------------------------------------ 6 / 8 点

    @Test
    fun bandRowsAndByteLegalityByDotCount() {
        val eight = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val vp8 = TactileViewport(inkArray(8, 4) { _, _ -> true }, 8, 4, eight)
        assertEquals(4, vp8.bandRows)
        assertEquals(16, vp8.blockRows)
        vp8.enterDetail()
        assertEquals(4, vp8.windowBytes().size)

        val six = ImageStripEncoder.Spec.forDisplay(4, 1, 6)
        val vp6 = TactileViewport(inkArray(8, 3) { _, _ -> true }, 8, 3, six)
        assertEquals(3, vp6.bandRows)
        assertEquals(12, vp6.blockRows)
        vp6.enterDetail()
        vp6.windowBytes().forEach { b ->
            assertEquals("6 点高位（7/8 点）必须清零", 0, b.toInt() and 0xC0)
        }
    }

    // ------------------------------------------------------------------ 三段式回归

    @Test
    fun existingStripBookAndPagerStillHold() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val ink = inkArray(8, 10) { _, _ -> true }
        val slice = TactileStripSlicer.slice(ink, 8, 10, spec)
        val book = TactileStripBookBuilder.build(
            slice, spec, listOf(ByteArray(4)), listOf(ByteArray(4)),
        )
        val pager = TactileStripPager(book)

        assertEquals(StripPageKind.COVER, pager.current.kind)
        assertTrue(pager.next())
        assertEquals(StripPageKind.GRAPHIC, pager.current.kind)
        while (pager.canNext()) assertTrue(pager.next())
        assertEquals(StripPageKind.BACK, pager.current.kind)
    }
}
