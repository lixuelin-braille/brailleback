/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * BrailleExporter 纯 JVM 契约：折行、换行符、分页、六点往返、计数、码位区间、
 * 文件名清洗。反向点位复用 BrailleFileDecoder.dotsToAscii，不在导出侧另立表。
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class BrailleExporterTest {

    private fun cell(dots: Int): Char = (0x2800 + dots).toChar()

    /** 与基线捕获时一致的固定样本：60 点1 + 空方全点位 + 26 行点2 + 明眼字。 */
    private fun baselineSample(): String {
        val sb = StringBuilder()
        repeat(60) { sb.append(cell(1)) }
        sb.append('\n')
        sb.append(cell(0))
        (0..63).forEach { sb.append(cell(it)) }
        sb.append('\n')
        repeat(26) { sb.append(cell(2)); sb.append('\n') }
        sb.append('中')
        return sb.toString()
    }

    // -------------------------------------------------------------- 折行

    @Test
    fun brfWrapsAtFortyCells() {
        val stream = (1..80).joinToString("") { cell(1).toString() }
        val result = BrailleExporter.export(stream, BrailleExportFormat.BRF)
        val lines = result.content.split("\r\n")
        assertEquals("80 方应折成 2 行", 2, lines.size)
        assertTrue("每行不超过 40 方", lines.all { it.length <= 40 })
    }

    @Test
    fun brfUsesCrlfAndNoBareLf() {
        val stream = (1..3).joinToString("\n") { cell(1).toString() }
        val result = BrailleExporter.export(stream, BrailleExportFormat.BRF)
        assertTrue("必须用 CRLF 换行", result.content.contains("\r\n"))
        assertFalse("不得出现裸 LF",
            Regex("(?<!\r)\n").containsMatchIn(result.content))
    }

    // -------------------------------------------------------------- 分页

    @Test
    fun brfInsertsFormFeedEveryTwentyFiveLinesNoTrailing() {
        // 50 行：第 25 行后插一个 FF，末页不挂尾 FF。
        val stream = (1..50).joinToString("\n") { cell(1).toString() }
        val result = BrailleExporter.export(stream, BrailleExportFormat.BRF)
        assertEquals("满 25 行且后面还有内容才插 FF", 1,
            result.content.count { it == '\u000C' })
        assertFalse("末行不得是尾 FF", result.content.last() == '\u000C')
    }

    @Test
    fun brfNoFormFeedWhenUnderTwentyFiveLines() {
        val stream = (1..24).joinToString("\n") { cell(1).toString() }
        val result = BrailleExporter.export(stream, BrailleExportFormat.BRF)
        assertEquals("不足一页不插 FF", 0,
            result.content.count { it == '\u000C' })
    }

    @Test
    fun brlDoesNotInsertFormFeed() {
        val stream = (1..50).joinToString("\n") { cell(1).toString() }
        val result = BrailleExporter.export(stream, BrailleExportFormat.BRL)
        assertEquals("BRL 不插 FF", 0, result.content.count { it == '\u000C' })
    }

    // -------------------------------------------------------------- 六点往返

    @Test
    fun sixDotZeroToSixtyThreeRoundTrip() {
        val stream = (0..63).joinToString("") { cell(it).toString() }
        val brf = BrailleExporter.export(stream, BrailleExportFormat.BRF).content
        // 折行产生的 CRLF 不是盲方；解码前剥离，只比对点位往返。
        val back = BrailleFileDecoder.toUnicodeBraille(brf.replace("\r\n", ""))
        (0..63).forEach { dots ->
            assertEquals("点位 $dots 往返不一致", cell(dots), back[dots])
        }
    }

    @Test
    fun blankCellRoundTripsToSpaceAndBack() {
        val brf = BrailleExporter.export(cell(0).toString(), BrailleExportFormat.BRF)
        assertEquals("空方回写为空格", " ", brf.content)
        assertEquals("空格还原为空方", cell(0),
            BrailleFileDecoder.toUnicodeBraille(brf.content)[0])
    }

    // -------------------------------------------------------------- 计数

    @Test
    fun eightDotStripsDots7And8AndCounts() {
        // U+2840 = 点位 64（仅 dot7）：剥到六点掩码 0 → 空格，计入 eightDot，不写 ?。
        val result = BrailleExporter.export(cell(64).toString(), BrailleExportFormat.BRF)
        assertEquals("剥 7/8 后六点为空 → 空格", " ", result.content)
        assertEquals("八点计入 eightDotCells", 1, result.eightDotCells)
        assertEquals("不再计入 unmappable", 0, result.unmappableCells)
        assertEquals("BRF 不出现 ?", false, result.content.contains('?'))
        assertEquals("明眼字符计数为 0", 0, result.droppedChars)
    }

    @Test
    fun eightDotKeepsSixDotRemainderInBrf() {
        // 点位 65 = dot1 + dot7；剥 dot7 后掩码 1 → NABCC "A"。
        val result = BrailleExporter.export(cell(65).toString(), BrailleExportFormat.BRF)
        assertEquals("保留六点剩余点位", "A", result.content)
        assertEquals(1, result.eightDotCells)
    }

    @Test
    fun eightDotPreservedInBrlAndTxt() {
        val brl = BrailleExporter.export(cell(64).toString(), BrailleExportFormat.BRL)
        assertEquals("BRL 保留 U+2840", cell(64), brl.content[0])
        assertEquals(1, brl.eightDotCells)

        val txt = BrailleExporter.export(cell(64).toString(), BrailleExportFormat.UNICODE_TXT)
        assertEquals("文本保留 U+2840", cell(64), txt.content[0])
        assertEquals(1, txt.eightDotCells)
    }

    @Test
    fun printCharactersAreDroppedAndCounted() {
        val result = BrailleExporter.export("中" + cell(1), BrailleExportFormat.BRF)
        assertEquals("明眼字符丢弃计数", 1, result.droppedChars)
        assertEquals("盲方保留", "A", result.content)
    }

    // -------------------------------------------------------------- BRL 码位

    @Test
    fun brlKeepsUnicodeBrailleCodepointsNotAscii() {
        val result = BrailleExporter.export(cell(1).toString(), BrailleExportFormat.BRL)
        assertEquals("BRL 保留 U+2801", cell(1), result.content[0])
        assertTrue("码位不得退化到 ASCII 区间",
            result.content.all { it.code in 0x2800..0x28FF || it == '\n' || it == '\r' })
    }

    @Test
    fun unicodeTxtDoesNotWrap() {
        val line = (1..60).joinToString("") { cell(1).toString() }
        val result = BrailleExporter.export(line, BrailleExportFormat.UNICODE_TXT)
        assertEquals("不折行：整段保留", line, result.content)
    }

    @Test
    fun emptyInputProducesEmptyResult() {
        val result = BrailleExporter.export("", BrailleExportFormat.BRF)
        assertEquals("", result.content)
        assertEquals(0, result.unmappableCells)
        assertEquals(0, result.droppedChars)
    }

    // -------------------------------------------------------------- 文件名

    @Test
    fun sanitizeFileNameStripsExtensionAndIllegalChars() {
        assertEquals("book_b_k",
            BrailleExporter.sanitizeFileName("book<b:k>.txt"))
    }

    @Test
    fun sanitizeFileNameFallsBackWhenBlank() {
        assertEquals("braille_export", BrailleExporter.sanitizeFileName("   "))
        assertEquals("braille_export", BrailleExporter.sanitizeFileName("///"))
    }

    // -------------------------------------------------------------- F1 版面几何

    @Test
    fun defaultGeometryMatchesBaselineByteForByte() {
        val stream = baselineSample()
        val brf = BrailleExporter.export(stream, BrailleExportFormat.BRF)
        val brl = BrailleExporter.export(stream, BrailleExportFormat.BRL)
        val txt = BrailleExporter.export(stream, BrailleExportFormat.UNICODE_TXT)

        assertBytesEquals(readBaselineBytes("sample.brf"),
            brf.content.toByteArray(StandardCharsets.US_ASCII))
        assertEquals(String(readBaselineBytes("sample.brl"), StandardCharsets.UTF_8), brl.content)
        assertEquals(String(readBaselineBytes("sample.txt"), StandardCharsets.UTF_8), txt.content)
    }

    @Test
    fun geometryMatrixSixGroupsWrapAndPage() {
        val columnsChoices = listOf(20, 30, 40)
        val linesChoices = listOf(20, 25)
        // 50 行单点：用于按 linesPerPage 核算 FF 数量。
        val fiftyLines = (1..50).joinToString("\n") { cell(2).toString() }
        val hundredCells = (1..100).joinToString("") { cell(1).toString() }

        for (columns in columnsChoices) {
            for (linesPerPage in linesChoices) {
                val geometry = PageGeometry(columns, linesPerPage)

                val brf = BrailleExporter.export(fiftyLines, BrailleExportFormat.BRF, geometry)
                val brfLines = brf.content.split("\r\n")
                assertTrue("BRF 每行 ≤ $columns 方", brfLines.all { it.length <= columns })
                val expectedFf = (50 - 1) / linesPerPage
                assertEquals("FF 数量应为 $expectedFf（$columns×$linesPerPage）",
                    expectedFf, brf.content.count { it == '\u000C' })

                val brl = BrailleExporter.export(hundredCells, BrailleExportFormat.BRL, geometry)
                val brlLines = brl.content.split("\r\n")
                assertTrue("BRL 每行 ≤ $columns 方", brlLines.all { it.length <= columns })
                assertEquals("BRL 不插 FF", 0, brl.content.count { it == '\u000C' })
            }
        }
    }

    @Test
    fun geometryRejectsNonPositiveDimensions() {
        try {
            PageGeometry(0, 25)
            assertTrue("columns=0 应抛异常", false)
        } catch (e: IllegalArgumentException) { }
        try {
            PageGeometry(40, 0)
            assertTrue("linesPerPage=0 应抛异常", false)
        } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun presetColumnsAndLinesAreExposed() {
        assertEquals(listOf(20, 30, 32, 40), PageGeometry.PRESET_COLUMNS)
        assertEquals(listOf(20, 25, 28), PageGeometry.PRESET_LINES)
        assertEquals(40, PageGeometry.Default.columns)
        assertEquals(25, PageGeometry.Default.linesPerPage)
    }

    private fun readBaselineBytes(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/export_baseline/$name")
            ?: throw AssertionError("基线缺失: $name")
        return stream.use { it.readBytes() }
    }

    private fun readBaseline(name: String): String =
        String(readBaselineBytes(name), StandardCharsets.US_ASCII)

    private fun assertBytesEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue("字节长度不一致: ${expected.size} vs ${actual.size}",
            expected.contentEquals(actual))
    }
}
