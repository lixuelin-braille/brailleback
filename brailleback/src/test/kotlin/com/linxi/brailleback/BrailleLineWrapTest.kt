/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 折行内核的纯逻辑单测：按词断不劈字、超宽才硬切、内容一字不丢。 */
class BrailleLineWrapTest {

    private fun cell(dots: Int): Char = (0x2800 + dots).toChar()

    private val blank = cell(0)
    private val a = cell(1)
    private val b = cell(2)
    private val c = cell(3)

    /** 与改造前完全一致的硬切，用于证明 CELL 模式没有漂移。 */
    private fun legacySlice(raw: String, columns: Int): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < raw.length) {
            out.add(raw.substring(i, minOf(i + columns, raw.length)))
            i += columns
        }
        return out
    }

    @Test
    fun cellModeMatchesLegacySlicing() {
        val samples = listOf(
            "",
            a.toString(),
            (1..39).joinToString("") { a.toString() } + blank,
            (1..80).joinToString("") { b.toString() },
            "$a$blank$b$blank$c$blank$a$blank$b$blank$c$blank",
        )
        for (sample in samples) {
            assertEquals("CELL 模式必须与旧硬切逐字符一致",
                legacySlice(sample, 20),
                BrailleLineWrap.wrap(sample, 20, BrailleWrapMode.CELL, blank))
        }
    }

    @Test
    fun wordModeBreaksAtBlankAndKeepsWidth() {
        // 30 个实方 + 空方 + 30 个实方，列宽 40：应在空方处断，而不是第 40 方硬切。
        val raw = (1..30).joinToString("") { a.toString() } + blank +
            (1..30).joinToString("") { b.toString() }
        val lines = BrailleLineWrap.wrap(raw, 40, BrailleWrapMode.WORD, blank)
        assertEquals(2, lines.size)
        assertEquals("第 1 行应停在空方（含空方共 31 方）", 31, lines[0].length)
        assertTrue("第 1 行以空方结尾", lines[0].last() == blank)
        assertTrue("每行不超列宽", lines.all { it.length <= 40 })
        assertEquals("内容必须一字不丢", raw, lines.joinToString(""))
    }

    @Test
    fun wordModeNeverSplitsWordWithRepeatedGaps() {
        // 每 10 方一个空方，列宽 25：每次都该断在空方上，行尾不会停在实方中间。
        val word = (1..10).joinToString("") { a.toString() }
        val raw = (1..6).joinToString(blank.toString()) { word } + blank
        val lines = BrailleLineWrap.wrap(raw, 25, BrailleWrapMode.WORD, blank)
        lines.dropLast(1).forEach { line ->
            assertTrue("整行必须有空方可断，实际行: ${line.length} 方", line.last() == blank)
            assertTrue("不超列宽", line.length <= 25)
        }
        assertEquals(raw, lines.joinToString(""))
    }

    @Test
    fun wordModeFallsBackToHardCutWhenNoBlank() {
        // 整窗都是实方（超长词）：没有断点，只能按列宽硬切。
        val raw = (1..90).joinToString("") { c.toString() }
        val lines = BrailleLineWrap.wrap(raw, 40, BrailleWrapMode.WORD, blank)
        assertEquals(listOf(40, 40, 10), lines.map { it.length })
        assertEquals(raw, lines.joinToString(""))
    }

    @Test
    fun wordModeDoesNotStartLineWithBlank() {
        // 连续两个空方：它们一起留在上一行行尾，下一行从实方开始。
        val raw = (1..20).joinToString("") { a.toString() } + "$blank$blank" +
            (1..20).joinToString("") { b.toString() }
        val lines = BrailleLineWrap.wrap(raw, 40, BrailleWrapMode.WORD, blank)
        assertTrue("下一行不得以空方开头", lines[1].first() != blank)
        assertEquals(raw, lines.joinToString(""))
    }

    @Test
    fun trailingShortLineIsKeptAsIs() {
        val raw = (1..50).joinToString("") { a.toString() }
        val lines = BrailleLineWrap.wrap(raw, 40, BrailleWrapMode.WORD, blank)
        assertEquals(listOf(40, 10), lines.map { it.length })
    }

    @Test
    fun emptyRawProducesNoLine() {
        assertEquals(emptyList<String>(),
            BrailleLineWrap.wrap("", 40, BrailleWrapMode.WORD, blank))
        assertEquals(emptyList<String>(),
            BrailleLineWrap.wrap("", 40, BrailleWrapMode.CELL, blank))
    }
}
