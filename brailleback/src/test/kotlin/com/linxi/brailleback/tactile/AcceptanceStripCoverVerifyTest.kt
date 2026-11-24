/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 验收方独立复核（第二轮收口）：
 * 1) 多行设备封面/封底合并成整屏：40/20 方 × 6/8 点、4 行屏下封面/封底各 1 屏，
 *    六项/四项字段一项不缺；单行设备保持多屏且字段齐全、不截断。
 * 2) stripEmpty 剔空带：含墨行并集前后一致、被剔带全 0、带号重排、totalBands 一致；
 *    全白图保留 1 个图形带；封面「共 N 带」与剔除后总数一致。
 *
 * 纯 Kotlin/JVM，不依赖 android.*；翻译用 1 字符 = 1 方的假映射（不二次折行），
 * 打包走真实 TactileStripCaption.page。
 */
package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceStripCoverVerifyTest {

    // ---------------------------------------------------------- 假翻译打包

    /** 仿 Session.wrapIntoScreens：假翻译 1 字符→1 盲文方，再按 linesPerScreen 切屏。 */
    private fun packScreens(
        lines: List<String>,
        cells: Int,
        linesPerScreen: Int,
        dots: Int,
    ): List<ByteArray> {
        val brailleRows = ArrayList<String>()
        for (line in lines) {
            // 假映射：每个源字符占一方，内容统一 U+2801（仅用于测屏数与长度，不测字形）。
            val fake = line.map { "\u2801" }.joinToString("")
            var rest = fake
            while (rest.isNotEmpty()) {
                brailleRows.add(rest.take(cells))
                rest = rest.drop(cells)
            }
        }
        val screens = ArrayList<ByteArray>()
        var i = 0
        while (i < brailleRows.size) {
            val chunk = brailleRows.subList(i, minOf(i + linesPerScreen, brailleRows.size))
            screens.add(TactileStripCaption.page(chunk, cells, linesPerScreen, dots))
            i += linesPerScreen
        }
        return screens
    }

    private fun inkArray(cols: Int, rows: Int, inkAt: (x: Int, y: Int) -> Boolean): BooleanArray =
        BooleanArray(cols * rows) { i -> inkAt(i % cols, i / cols) }

    // ---------------------------------------------------------- A. 多行整屏

    @Test
    fun multiLineCoverBackOneScreenAllFieldsPresent() {
        for (cells in listOf(40, 20)) {
            for (dots in listOf(8, 6)) {
                val bandHeight = if (dots == 8) 16 else 12
                val cover = TactileStripCoverText.coverLines(
                    widthCells = cells,
                    bandHeightDots = bandHeight,
                    totalBands = 10,
                    dotsPerCell = dots,
                    rotated = false,
                    maxLines = 4,
                )
                val coverScreens = packScreens(cover, cells, 4, dots)
                assertEquals("${cells}方${dots}点封面应 1 屏", 1, coverScreens.size)
                coverScreens.forEach { s ->
                    assertEquals(4 * cells, s.size)
                }
                val cj = cover.joinToString("|")
                assertTrue(cj.contains("图形开始"))
                assertTrue(cj.contains("宽 $cells 方"))
                assertTrue(cj.contains("高 10 带"))
                assertTrue(cj.contains("每带"))
                assertTrue(cj.contains("方向：原图上方在前"))
                assertTrue(cj.contains("共 10 带"))
                assertTrue(cj.contains("按下一页开始"))

                val back = TactileStripCoverText.backLines(
                    widthCells = cells,
                    totalBands = 10,
                    dotsPerCell = dots,
                    maxLines = 4,
                )
                val backScreens = packScreens(back, cells, 4, dots)
                assertEquals("${cells}方${dots}点封底应 1 屏", 1, backScreens.size)
                backScreens.forEach { s -> assertEquals(4 * cells, s.size) }
                val bj = back.joinToString("|")
                assertTrue(bj.contains("图形结束"))
                assertTrue(bj.contains("共 10 带"))
                assertTrue(bj.contains("宽 $cells 方"))
                assertTrue(bj.contains("返回或换图"))
            }
        }
    }

    @Test
    fun threeLineScreenCoverBackFitWithoutTruncation() {
        // 3 行屏（maxLines=3）：字段齐全不截断；合并行估计超宽时按设计回退一字段一行（多屏）。
        for (cells in listOf(40, 20)) {
            val cover = TactileStripCoverText.coverLines(
                widthCells = cells, bandHeightDots = 16, totalBands = 7,
                dotsPerCell = 8, rotated = false, maxLines = 3,
            )
            val coverScreens = packScreens(cover, cells, 3, 8)
            assertEquals(ceilDiv(cover.size, 3), coverScreens.size)
            coverScreens.forEach { assertEquals(3 * cells, it.size) }
            val cj = cover.joinToString("|")
            listOf("图形开始", "宽 $cells 方", "高 7 带", "方向：原图上方在前", "共 7 带", "按下一页开始")
                .forEach { assertTrue("封面缺字段：$it", cj.contains(it)) }

            val back = TactileStripCoverText.backLines(
                widthCells = cells, totalBands = 7, dotsPerCell = 8, maxLines = 3,
            )
            val backScreens = packScreens(back, cells, 3, 8)
            assertEquals(ceilDiv(back.size, 3), backScreens.size)
            val bj = back.joinToString("|")
            listOf("图形结束", "共 7 带", "宽 $cells 方", "返回或换图")
                .forEach { assertTrue("封底缺字段：$it", bj.contains(it)) }
        }
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    @Test
    fun singleLineDeviceMultiScreenFieldsIntact() {
        val cover = TactileStripCoverText.coverLines(
            widthCells = 40, bandHeightDots = 4, totalBands = 10,
            dotsPerCell = 8, rotated = false, maxLines = 1,
        )
        val coverScreens = packScreens(cover, 40, 1, 8)
        assertTrue("单行封面应多屏", coverScreens.size > 1)
        assertEquals(6, cover.size)
        val cj = cover.joinToString("|")
        listOf("图形开始", "宽 40 方", "高 10 带", "方向：原图上方在前", "共 10 带", "按下一页开始")
            .forEach { assertTrue("单行封面缺字段：$it", cj.contains(it)) }

        val back = TactileStripCoverText.backLines(40, 10, 8, maxLines = 1)
        val backScreens = packScreens(back, 40, 1, 8)
        assertTrue("单行封底应多屏", backScreens.size > 1)
        assertEquals(4, back.size)
        val bj = back.joinToString("|")
        listOf("图形结束", "共 10 带", "宽 40 方", "返回或换图")
            .forEach { assertTrue("单行封底缺字段：$it", bj.contains(it)) }
    }

    @Test
    fun narrowWidthFallsBackToOneFieldPerLineNoTruncation() {
        // 合并后估计超宽 → 回退一字段一行；字段原文必须完整保留。
        val cover = TactileStripCoverText.coverLines(
            widthCells = 4, bandHeightDots = 4, totalBands = 10,
            dotsPerCell = 8, rotated = false, maxLines = 4,
        )
        assertEquals(6, cover.size)
        val cj = cover.joinToString("|")
        listOf("图形开始", "宽 4 方", "高 10 带", "方向：原图上方在前", "共 10 带", "按下一页开始")
            .forEach { assertTrue("窄宽回退缺字段：$it", cj.contains(it)) }
    }

    // ---------------------------------------------------------- B. 剔空带

    private fun syntheticBand(
        bytes: ByteArray,
        y0: Int,
        height: Int,
        index: Int,
        total: Int,
    ): Band = Band(
        bytes = bytes, x0 = 0, y0 = y0, height = height,
        overlapTop = 0, overlapBottom = 0, bandIndex = index, total = total,
    )

    @Test
    fun stripEmptyKeepsOnlyInkBandsReindexesAndPreservesBytes() {
        val nonEmpty = ByteArray(4) { 1 }
        val empty = ByteArray(4) { 0 }
        val raw = SliceResult(
            bands = listOf(
                syntheticBand(nonEmpty, 0, 4, 1, 3),
                syntheticBand(empty, 4, 4, 2, 3),
                syntheticBand(nonEmpty, 8, 4, 3, 3),
            ),
            totalBands = 3, cols = 8, rows = 12, pageCols = 8, bandRows = 4,
        )
        val prepared = TactileStripBookBuilder.stripEmpty(raw)

        assertEquals(2, prepared.totalBands)
        assertEquals(2, prepared.bands.size)
        // 被剔的必须是全 0 带；保留带字节原样。
        assertTrue(raw.bands[1].bytes.all { it.toInt() == 0 })
        assertEquals(nonEmpty.toList(), prepared.bands[0].bytes.toList())
        assertEquals(nonEmpty.toList(), prepared.bands[1].bytes.toList())
        // 带号重排 1..N，total 一致。
        prepared.bands.forEachIndexed { i, band ->
            assertEquals(i + 1, band.bandIndex)
            assertEquals(2, band.total)
        }
    }

    @Test
    fun stripEmptyWhiteImageKeepsOneBand() {
        val spec = ImageStripEncoder.Spec.forDisplay(20, 1, 8)
        val white = GrayImage.white(40, 40)
        val cols = spec.dotColumns
        val rows = maxOf(1, Math.round(cols.toFloat() * white.height.toFloat() / white.width))
        val ink = ImageStripEncoder.dotGrid(white, cols, rows, 128)
        val raw = TactileStripSlicer.slice(ink, cols, rows, spec)
        val prepared = TactileStripBookBuilder.stripEmpty(raw)
        assertEquals(1, prepared.totalBands)
        prepared.bands.forEach { band ->
            assertTrue(band.bytes.all { it.toInt() == 0 })
        }
    }

    @Test
    fun inkRowUnionPreservedAfterStrip() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        // 0–3 行有墨，4–8 行全空，9–11 行有墨。
        val ink = inkArray(8, 12) { _, y -> y < 4 || y >= 9 }
        val raw = TactileStripSlicer.slice(ink, 8, 12, spec)
        val prepared = TactileStripBookBuilder.stripEmpty(raw)

        val inkRows = BooleanArray(12)
        for (y in 0 until 12) for (x in 0 until 8) if (ink[y * 8 + x]) inkRows[y] = true
        val covered = BooleanArray(12)
        prepared.bands.forEach { band ->
            for (r in band.y0 until band.y0 + band.height) covered[r] = true
        }
        inkRows.forEachIndexed { row, hasInk ->
            if (hasInk) assertTrue("含墨行 $row 剔空带后丢失", covered[row])
        }
        // 剔除后不允许出现全 0 图形带。
        prepared.bands.forEach { band ->
            assertTrue("保留带中存在全空带", band.bytes.any { it.toInt() != 0 })
        }
    }

    @Test
    fun bookTotalBandsMatchesStrippedAndCoverTextN() {
        val spec = ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        val ink = inkArray(8, 12) { _, y -> y < 4 || y >= 9 }
        val raw = TactileStripSlicer.slice(ink, 8, 12, spec)
        val prepared = TactileStripBookBuilder.stripEmpty(raw)

        val book = TactileStripBookBuilder.build(
            raw, spec, listOf(ByteArray(4)), listOf(ByteArray(4))
        )
        assertEquals(prepared.totalBands, book.totalBands)

        val graphics = book.pages.filter { it.kind == StripPageKind.GRAPHIC }
        assertEquals(prepared.totalBands, graphics.size)
        graphics.forEach { page ->
            assertEquals(prepared.totalBands, page.totalBands)
            assertTrue("图形页出现全空页", page.bytes.any { it.toInt() != 0 })
        }

        val cover = TactileStripCoverText.coverLines(
            4, prepared.bandRows, book.totalBands, 8, false, maxLines = 1,
        )
        val back = TactileStripCoverText.backLines(4, book.totalBands, 8, maxLines = 1)
        assertTrue(cover.joinToString("|").contains("共 ${book.totalBands} 带"))
        assertTrue(cover.joinToString("|").contains("高 ${book.totalBands} 带"))
        assertTrue(back.joinToString("|").contains("共 ${book.totalBands} 带"))
    }

    @Test
    fun whiteBookStructureCoverOneGraphicBack() {
        val spec = ImageStripEncoder.Spec.forDisplay(20, 1, 8)
        val white = GrayImage.white(40, 40)
        val cols = spec.dotColumns
        val rows = maxOf(1, Math.round(cols.toFloat() * white.height.toFloat() / white.width))
        val ink = ImageStripEncoder.dotGrid(white, cols, rows, 128)
        val raw = TactileStripSlicer.slice(ink, cols, rows, spec)
        val coverPage = TactileStripCaption.page(listOf("\u2801"), 20, 1, 8)
        val book = TactileStripBookBuilder.build(
            raw, spec, listOf(coverPage), listOf(ByteArray(20))
        )

        val kinds = book.pages.map { it.kind }
        assertEquals(StripPageKind.COVER, kinds.first())
        assertEquals(StripPageKind.BACK, kinds.last())
        val graphics = book.pages.filter { it.kind == StripPageKind.GRAPHIC }
        assertEquals(1, graphics.size)
        assertTrue(graphics.first().bytes.all { it.toInt() == 0 })
        assertEquals(1, book.totalBands)
    }
}
