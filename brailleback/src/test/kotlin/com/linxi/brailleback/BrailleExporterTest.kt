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

class BrailleExporterTest {

    private fun cell(dots: Int): Char = (0x2800 + dots).toChar()

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
    fun eightDotIsQuestionMarkAndCounted() {
        // U+2840 = 点位 64（八点），BRF 回写 ? 并计入 unmappable。
        val result = BrailleExporter.export(cell(64).toString(), BrailleExportFormat.BRF)
        assertEquals("八点写成 ?", "?", result.content)
        assertEquals("八点计入 unmappableCells", 1, result.unmappableCells)
        assertEquals("明眼字符计数为 0", 0, result.droppedChars)
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
}
