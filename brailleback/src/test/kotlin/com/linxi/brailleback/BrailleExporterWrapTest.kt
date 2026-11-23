/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导出层折行方式的单测：默认按词断、CELL 保留旧硬切、两种模式都不丢内容。 */
class BrailleExporterWrapTest {

    private fun cell(dots: Int): Char = (0x2800 + dots).toChar()

    private val blank = cell(0)

    /** 30 方实字符 + 空方 + 30 方实字符：40 方列宽下按词断应停在 31 方处。 */
    private fun spacedStream(): String =
        (1..30).joinToString("") { cell(1).toString() } + blank +
            (1..30).joinToString("") { cell(2).toString() }

    @Test
    fun brfDefaultsToWordWrap() {
        val result = BrailleExporter.export(spacedStream(), BrailleExportFormat.BRF)
        val lines = result.content.split("\r\n")
        assertEquals(2, lines.size)
        assertEquals("BRF 应在空方处断行（30 实方 + 1 空格）", 31, lines[0].length)
        assertTrue("行尾是空格", lines[0].endsWith(" "))
        assertTrue("每行不超过 40 方", lines.all { it.length <= 40 })
    }

    @Test
    fun brlDefaultsToWordWrapAtBlankCell() {
        val result = BrailleExporter.export(spacedStream(), BrailleExportFormat.BRL)
        val lines = result.content.split("\r\n")
        assertEquals(2, lines.size)
        assertEquals(31, lines[0].length)
        assertEquals("行尾是空方", blank, lines[0].last())
    }

    @Test
    fun cellModeStaysAtLegacyFixedColumns() {
        val result = BrailleExporter.export(
            spacedStream(), BrailleExportFormat.BRF, wrapMode = BrailleWrapMode.CELL)
        val lines = result.content.split("\r\n")
        assertEquals("硬切应填满 40 方", 40, lines[0].length)
        assertEquals(2, lines.size)
    }

    @Test
    fun explicitWordModeEqualsDefault() {
        val stream = spacedStream()
        assertEquals(
            BrailleExporter.export(stream, BrailleExportFormat.BRF, wrapMode = BrailleWrapMode.WORD)
                .content,
            BrailleExporter.export(stream, BrailleExportFormat.BRF).content)
    }

    @Test
    fun wrapModesKeepAllCellsExceptLineBreaks() {
        // 内容不丢：把折行后的行拼回去，应与原文一致（空格/空方都保留）。
        val stream = spacedStream()
        val word = BrailleExporter.export(stream, BrailleExportFormat.BRF)
            .content.split("\r\n").joinToString("")
        assertEquals(60 + 1, word.length)
                // 空方回写为 ASCII 空格，所以拼回来是 61 个字符，其中 1 个是空格
        val cellMode = BrailleExporter.export(
            stream, BrailleExportFormat.BRF, wrapMode = BrailleWrapMode.CELL)
            .content.split("\r\n").joinToString("")
        assertEquals(word, cellMode)
    }

    @Test
    fun leadingBlanksDoNotMakeATinyLine() {
        // 段落以连续空方开头（缩进）时，空方不能被当成断点切出一行几乎空白的行；
        // 窗口内没有别的空方，就按列宽硬切，与改造前一致。
        val sb = StringBuilder()
        repeat(60) { sb.append(cell(1)) }
        sb.append('\n')
        sb.append(cell(0)).append(cell(0))
        (1..63).forEach { sb.append(cell(it)) }
        val word = BrailleExporter.export(sb.toString(), BrailleExportFormat.BRF)
            .content.split("\r\n")
        val cellMode = BrailleExporter.export(
            sb.toString(), BrailleExportFormat.BRF, wrapMode = BrailleWrapMode.CELL)
            .content.split("\r\n")
        assertEquals("行数与硬切一致", cellMode.size, word.size)
        assertEquals("缩进段不得被切成空白短行",
            cellMode.map { it.length }, word.map { it.length })
        assertEquals(listOf(40, 20, 40, 25), word.map { it.length })
    }

    @Test
    fun longRunWithoutBlankStillFillsFullLines() {
        // 整段没有空方（超长词/英文串）：两种模式都必须按 40 方填满。
        val stream = (1..90).joinToString("") { cell(3).toString() }
        val word = BrailleExporter.export(stream, BrailleExportFormat.BRF)
            .content.split("\r\n").map { it.length }
        val cellMode = BrailleExporter.export(
            stream, BrailleExportFormat.BRF, wrapMode = BrailleWrapMode.CELL)
            .content.split("\r\n").map { it.length }
        assertEquals(listOf(40, 40, 10), word)
        assertEquals(listOf(40, 40, 10), cellMode)
    }
}
