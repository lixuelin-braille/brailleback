/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 独立验收测试（验收方自写，不沿用既有用例的断言）：
 * 从三段式会话需求反推，覆盖页序、带数一致性、整屏字节长度、6/8 点位掩码、
 * 纯白图不入图形页、末带边界、切片元数据单调可重建、空白行优先切缝。
 * 纯 Kotlin / JVM，不依赖 android.*，可离线跑。
 */
package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeStageAcceptanceTest {

    // ------------------------------------------------------------ 基础构造

    private fun inkArray(cols: Int, rows: Int, inkAt: (x: Int, y: Int) -> Boolean): BooleanArray =
        BooleanArray(cols * rows) { i -> inkAt(i % cols, i / cols) }

    /** 全部有墨。 */
    private fun fullInk(cols: Int, rows: Int): BooleanArray =
        BooleanArray(cols * rows) { true }

    private fun buildBook(
        ink: BooleanArray,
        cols: Int,
        rows: Int,
        spec: ImageStripEncoder.Spec,
        coverScreens: List<ByteArray>,
        backScreens: List<ByteArray>,
    ): Pair<SliceResult, StripBook> {
        val slice = TactileStripSlicer.slice(ink, cols, rows, spec)
        val book = TactileStripBookBuilder.build(slice, spec, coverScreens, backScreens)
        return slice to book
    }

    // ------------------------------------------------------------ 1. 页序

    @Test
    fun pageOrderCoverThenGraphicThenBack() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        // 12 行，第 4、8 行空白 → 3 个图形带。
        val ink = inkArray(8, 12) { _, y -> y != 4 && y != 8 }
        val (slice, book) = buildBook(
            ink, 8, 12, spec,
            coverScreens = listOf(ByteArray(4)),
            backScreens = listOf(ByteArray(4)),
        )

        assertEquals(slice.totalBands, book.totalBands)
        assertEquals(1 + slice.totalBands + 1, book.pages.size)

        // 首页必须是封面，末页必须是封底。
        assertEquals(StripPageKind.COVER, book.pages.first().kind)
        assertEquals(StripPageKind.BACK, book.pages.last().kind)

        // 中间连续 GRAPHIC，bandIndex 从 1 递增到 totalBands。
        val graphics = book.pages.filter { it.kind == StripPageKind.GRAPHIC }
        assertEquals(slice.totalBands, graphics.size)
        graphics.forEachIndexed { i, page ->
            assertEquals("第 ${i + 1} 个图形带 bandIndex", i + 1, page.bandIndex)
            assertEquals(slice.totalBands, page.totalBands)
            assertEquals(StripPageKind.GRAPHIC, page.kind)
        }

        // 封面 / 封底不带图形带号。
        book.pages.filter { it.kind != StripPageKind.GRAPHIC }.forEach {
            assertEquals(0, it.bandIndex)
            assertEquals(0, it.x0)
            assertEquals(0, it.y0)
        }
    }

    // ------------------------------------------------------------ 2. 封面 N 来自切片

    @Test
    fun coverTextNTracksSliceTotalBandsNotConstant() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)

        // A：12 行，空白切缝 4、8 → 3 带。
        val sliceA = TactileStripSlicer.slice(
            inkArray(8, 12) { _, y -> y != 4 && y != 8 }, 8, 12, spec
        )
        // B：14 行，空白切缝 4、8、12 → 4 带。
        val sliceB = TactileStripSlicer.slice(
            inkArray(8, 14) { _, y -> y != 4 && y != 8 && y != 12 }, 8, 14, spec
        )
        assertEquals(3, sliceA.totalBands)
        assertEquals(4, sliceB.totalBands)
        assertNotEquals(sliceA.totalBands, sliceB.totalBands)

        val coverA = TactileStripCoverText.coverLines(
            widthCells = 4, bandHeightDots = sliceA.bandRows,
            totalBands = sliceA.totalBands, dotsPerCell = 8, rotated = false,
        )
        val coverB = TactileStripCoverText.coverLines(
            widthCells = 4, bandHeightDots = sliceB.bandRows,
            totalBands = sliceB.totalBands, dotsPerCell = 8, rotated = false,
        )

        fun joined(lines: List<String>) = lines.joinToString("|")
        assertTrue(joined(coverA).contains("共 3 带"))
        assertTrue(joined(coverA).contains("高 3 带"))
        assertTrue(joined(coverB).contains("共 4 带"))
        assertTrue(joined(coverB).contains("高 4 带"))
        // N 不是写死常量：两份文案里的带号随切片结果变化。
        assertTrue(joined(coverB).contains("共 4 带") && !joined(coverA).contains("共 4 带"))

        // 要求 2 的六项字段齐全。
        val coverJoined = joined(coverA)
        assertTrue(coverJoined.contains("图形开始"))
        assertTrue(coverJoined.contains("宽 4 方"))
        assertTrue(coverJoined.contains("每带"))
        assertTrue(coverJoined.contains("方向：原图上方在前"))
        assertTrue(coverJoined.contains("按下一页开始"))

        // 旋转标记文案随 rotated 切换。
        val rotated = TactileStripCoverText.coverLines(
            4, sliceA.bandRows, sliceA.totalBands, 8, rotated = true,
        )
        assertTrue(joined(rotated).contains("方向：已旋转 90°"))

        // 封底四项字段齐全。
        val backA = TactileStripCoverText.backLines(4, sliceA.totalBands, 8)
        val backJoined = joined(backA)
        assertTrue(backJoined.contains("图形结束"))
        assertTrue(backJoined.contains("共 3 带"))
        assertTrue(backJoined.contains("宽 4 方"))
        assertTrue(backJoined.contains("返回或换图"))
    }

    // --------------------------------------------- 3. 整屏字节长度与 6/8 点位掩码

    @Test
    fun coverBackByteLengthEqualsFullScreenAndDotMasks() {
        val cells = 6
        val lines = 2

        // 8 点：长度恒为 lines×cells；U+28FF 的 dot7/8（bit6/7）必须保留。
        val eight = TactileStripCaption.page(
            listOf(0x28FF.toChar().toString(), ""), cells, lines, 8
        )
        assertEquals(lines * cells, eight.size)
        assertEquals(0xFF, eight[0].toInt() and 0xFF)
        assertTrue((eight[0].toInt() and 0xC0) != 0)

        // 6 点：长度相同；高位 dot7/8 必须清零。
        val six = TactileStripCaption.page(
            listOf(0x28FF.toChar().toString(), ""), cells, lines, 6
        )
        assertEquals(lines * cells, six.size)
        six.forEach { b -> assertEquals(0, b.toInt() and 0xC0) }
    }

    // --------------------------------------------- 4. 纯白图图形页全 0，封面非 0

    @Test
    fun whiteImageYieldsZeroGraphicBytesButCoverNonZero() {
        val spec = ImageStripEncoder.Spec.forDisplay(20, 1, 8)
        val white = GrayImage.white(40, 40)
        val cols = spec.dotColumns
        val rows = maxOf(1, Math.round(cols.toFloat() * white.height.toFloat() / white.width))
        val ink = ImageStripEncoder.dotGrid(white, cols, rows, 128)

        val slice = TactileStripSlicer.slice(ink, cols, rows, spec)
        assertTrue(slice.bands.isNotEmpty())
        // 图形页字节只由点阵产生：全无墨 → 每个图形带全 0。
        slice.bands.forEach { band ->
            band.bytes.forEach { b -> assertEquals(0, b.toInt() and 0xFF) }
        }

        // 对照：封面文字屏只要有一个盲文字形就必须非 0（说明文字不会混进图形页）。
        val coverPage = TactileStripCaption.page(listOf("⠁"), 20, 1, 8)
        assertTrue(coverPage.any { it.toInt() and 0xFF != 0 })

        val book = TactileStripBookBuilder.build(
            slice, spec, listOf(coverPage), listOf(ByteArray(20))
        )
        book.pages.filter { it.kind == StripPageKind.GRAPHIC }.forEach { page ->
            assertTrue(page.bytes.all { it.toInt() and 0xFF == 0 })
        }
        val cover = book.pages.first { it.kind == StripPageKind.COVER }
        assertTrue(cover.bytes.any { it.toInt() and 0xFF != 0 })
    }

    // --------------------------------------------- 5. 末带边界：不丢带、不多带

    @Test
    fun lastBandBoundaryGraphicCountEqualsTotalBands() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val bandRows = spec.linesPerScreen * 4
        // 10 行，切缝 4、8；末带 2 行，不足一屏。
        val ink = inkArray(8, 10) { _, y -> y != 4 && y != 8 }
        val (slice, book) = buildBook(
            ink, 8, 10, spec,
            coverScreens = listOf(ByteArray(4)),
            backScreens = listOf(ByteArray(4)),
        )

        assertEquals(slice.bands.size, slice.totalBands)
        val last = slice.bands.last()
        assertTrue("末带高度 ${last.height} 应 ≤ bandRows $bandRows", last.height <= bandRows)
        assertTrue(last.height < bandRows)

        val graphics = book.pages.filter { it.kind == StripPageKind.GRAPHIC }
        assertEquals(slice.totalBands, graphics.size)
        // 最后一个图形带之后紧接封底。
        val lastGraphicIdx = book.pages.indexOfLast { it.kind == StripPageKind.GRAPHIC }
        assertEquals(StripPageKind.BACK, book.pages[lastGraphicIdx + 1].kind)

        // 末带字节长度仍是整屏，缺行补空。
        assertEquals(spec.linesPerScreen * spec.cellsPerLine, last.bytes.size)
    }

    // --------------------------------------------- 6. 元数据单调、可重建、重叠行一致

    @Test
    fun metadataMonotonicReconstructableAndOverlapRowsMatch() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val cols = 16
        val rows = 10
        // 斜向墨迹，避免整行空白，触发重叠；同时列方向也有墨。
        val ink = inkArray(cols, rows) { x, y -> (x + y) % 2 == 0 }
        val slice = TactileStripSlicer.slice(ink, cols, rows, spec)
        assertTrue(slice.bands.size >= 2)

        // y0 / x0 非递减；overlap 量在合理区间。
        var prevY = -1
        slice.bands.forEach { band ->
            assertTrue(band.y0 >= prevY)
            prevY = band.y0
            assertTrue(band.x0 >= 0)
            assertTrue(band.overlapTop >= 0)
            assertTrue(band.overlapBottom >= 0)
            assertTrue(band.height in 1..slice.bandRows)
            assertEquals(slice.totalBands, band.total)
        }

        // 独立从全局点阵重算每方字节，与带内字节逐位比对（不调 slicer 内部打包函数）。
        slice.bands.forEach { band ->
            val expected = repackFromInk(ink, cols, band, spec)
            assertEquals(expected.toList(), band.bytes.toList())
        }

        // 纵向相邻带：重叠行的字节必须完全一致（重叠行原样复制）。
        for (i in 1 until slice.bands.size) {
            val prev = slice.bands[i - 1]
            val cur = slice.bands[i]
            if (cur.x0 != prev.x0) continue // 横向邻带，跳过纵向比对
            val ov = cur.overlapTop
            if (ov == 0) continue
            val specLines = spec.linesPerScreen
            for (o in 0 until ov) {
                val prevLine = specLines - 1 - (ov - 1 - o) // 上一带尾部第 o 重叠行
                val curLine = o // 本一带头部第 o 重叠行
                for (cell in 0 until spec.cellsPerLine) {
                    val pi = prevLine * spec.cellsPerLine + cell
                    val ci = curLine * spec.cellsPerLine + cell
                    assertEquals(
                        "重叠行 o=$o cell=$cell 字节不一致",
                        prev.bytes[pi].toInt() and 0xFF,
                        cur.bytes[ci].toInt() and 0xFF,
                    )
                }
            }
        }
    }

    /** 验收方独立实现的打包：从全局点阵重算一方一字节，不调用 Slicer.toBytes。 */
    private fun repackFromInk(
        ink: BooleanArray,
        cols: Int,
        band: Band,
        spec: ImageStripEncoder.Spec,
    ): ByteArray {
        val rowsPerCell = if (spec.dotsPerCell == 8) 4 else 3
        val out = ByteArray(spec.linesPerScreen * spec.cellsPerLine)
        var index = 0
        for (line in 0 until spec.linesPerScreen) {
            for (cell in 0 until spec.cellsPerLine) {
                var bits = 0
                for (dr in 0 until rowsPerCell) {
                    val gy = band.y0 + line * rowsPerCell + dr
                    if (gy >= band.y0 + band.height) break
                    for (dc in 0..1) {
                        val gx = band.x0 + cell * 2 + dc
                        if (gx >= cols) continue
                        if (ink[gy * cols + gx]) bits = bits or (1 shl dotBit(dr, dc, spec.dotsPerCell))
                    }
                }
                out[index++] = bits.toByte()
            }
        }
        return out
    }

    private fun dotBit(row: Int, col: Int, dots: Int): Int = when (dots) {
        6 -> if (col == 0) row else row + 3
        else -> if (col == 0) {
            when (row) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 6 }
        } else {
            when (row) { 0 -> 3; 1 -> 4; 2 -> 5; else -> 7 }
        }
    }

    // --------------------------------------------- 7. 空白行优先切：切缝落在空白行

    @Test
    fun blankRowIsChosenAsCutWithZeroOverlap() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val cols = 8
        val rows = 12
        // 第 4、8 行整行无墨，其余行有墨。
        val ink = inkArray(cols, rows) { _, y -> y != 4 && y != 8 }
        val slice = TactileStripSlicer.slice(ink, cols, rows, spec)

        val first = slice.bands[0]
        val second = slice.bands[1]
        assertEquals(0, first.overlapBottom)
        assertEquals(0, second.overlapTop)
        // 切缝位置就是空白行 4：第一带到第 4 行前结束，第二带从第 4 行起。
        assertEquals(4, first.y0 + first.height)
        assertEquals(4, second.y0)
        // 相邻带纵向区间不相交；空白行本身无墨，不会被切缝劈断。
        for (i in 1 until slice.bands.size) {
            val prev = slice.bands[i - 1]
            val cur = slice.bands[i]
            if (cur.x0 == prev.x0) {
                assertTrue(prev.y0 + prev.height <= cur.y0)
            }
        }
        listOf(4, 8).forEach { blankRow ->
            for (x in 0 until cols) assertTrue("空白行 $blankRow 第 $x 列不应有墨", !ink[blankRow * cols + x])
        }
    }

    // --------------------------------------------- 8. 8 点 dot7/8 可出现；6 点不出现

    @Test
    fun eightDotCanRaiseDot7AndEightSixDotCannot() {
        // 一格 2 点列 × 4 点行，只有第 4 点行（dr=3）有墨。
        val ink8 = inkArray(2, 4) { _, y -> y == 3 }
        val spec8 = ImageStripEncoder.Spec.forDisplay(1, 1, 8)
        val slice8 = TactileStripSlicer.slice(ink8, 2, 4, spec8)
        val cell8 = slice8.bands.first().bytes.first().toInt() and 0xFF
        assertTrue("8 点下 dot7/8 位应被置位，实际 0x${cell8.toString(16)}", cell8 and 0xC0 != 0)

        // 6 点：第 3 点行（dr=2）有墨，高位必须保持 0。
        val ink6 = inkArray(2, 3) { _, y -> y == 2 }
        val spec6 = ImageStripEncoder.Spec.forDisplay(1, 1, 6)
        val slice6 = TactileStripSlicer.slice(ink6, 2, 3, spec6)
        slice6.bands.forEach { band ->
            band.bytes.forEach { b -> assertEquals(0, b.toInt() and 0xC0) }
        }
    }
}
