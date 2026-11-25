/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 紧凑逐格源表契约：只存 int 下标、按需现拼 CellSource、跨段用全书坐标；
 * 大书两千格仍按 List<CellSource> 口径对外，屏显还原结果与逐格对象一致。
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellSourceTableTest {

    @Test
    fun mapsCellsToGlobalCharacterIndexAcrossParagraphs() {
        // 两段："第一段" 占 0..2，换行在 3，次段 "第二段文字" 从全书下标 4 起。
        val text = "第一段\n第二段文字"
        val buffer = IntPositionBuffer(2)
        // 首段 3 格指向 0、1、2
        buffer.add(0)
        buffer.add(1)
        buffer.add(2)
        // 次段 5 格：段起点 4，本地 0..4 -> 全局 4..8
        for (local in 0..4) buffer.add(4 + local)
        val table = CellSourceTable(text, buffer.toIntArray())

        assertEquals(8, table.size)
        assertEquals(2, table.charPosAt(2))
        assertEquals(4, table.charPosAt(3))
        assertEquals("第", table[0].sourceText)
        assertEquals("一", table[1].sourceText)
        assertEquals(listOf(1, 2), table[1].span?.let { listOf(it.start, it.end) })
        // 次段第一格是全书下标 4 的「第」，不是段内 0 的「第」——
        // 坐标不能退回段内相对位置，否则整段都错位。
        assertEquals("第", table[3].sourceText)
        assertEquals(listOf(4, 5), table[3].span?.let { listOf(it.start, it.end) })
        assertEquals("字", table[7].sourceText)
    }

    @Test
    fun invalidPositionBecomesNoMapping() {
        val text = "你好"
        val table = CellSourceTable(text, intArrayOf(0, -1, 99))
        assertEquals(3, table.size)
        assertEquals(0, table.charPosAt(0))
        assertEquals(-1, table.charPosAt(1))
        assertEquals(99, table.charPosAt(2))
        assertNull(table[1].sourceText)
        assertNull(table[1].span)
        assertNull(table[2].sourceText)
    }

    @Test
    fun bufferGrowsWithoutLosingOrder() {
        val buffer = IntPositionBuffer(4)
        val n = 5000
        for (i in 0 until n) buffer.add(i * 3)
        val array = buffer.toIntArray()
        assertEquals(n, buffer.size)
        assertEquals(n, array.size)
        for (i in 0 until n) assertEquals(i * 3, array[i])
    }

    @Test
    fun readerModelRestoresSourceFromTableLikeCellObjects() {        // 同一份映射：一段 4 字、每字 1 格的盲文流
        val paragraph = "你好世界"
        val text = paragraph
        val stream = (0 until 4).joinToString("") { (0x2801).toChar().toString() }
        val objects = SourceSpanMapper.buildParagraphSpans(paragraph, intArrayOf(0, 1, 2, 3))
        val table = CellSourceTable(text, intArrayOf(0, 1, 2, 3))

        val fromObjects = ReaderSourceModel.build(
            kind = ReaderSourceKind.TEXT, sourceText = paragraph,
            brailleScreenText = "盲", spans = objects, offset = 0, count = 4,
            currentUnit = 0, totalUnits = 1, stream = stream)
        val fromTable = ReaderSourceModel.build(
            kind = ReaderSourceKind.TEXT, sourceText = paragraph,
            brailleScreenText = "盲", spans = table, offset = 0, count = 4,
            currentUnit = 0, totalUnits = 1, stream = stream)

        assertEquals(fromObjects.displayText, fromTable.displayText)
        assertEquals("你好世界", fromTable.displayText)
    }

    @Test
    fun skippedAdjacentCharactersAreRestoredFromSource() {
        // 词式分割会给出 0,0,1,1,3 … 这种位置：下标 2 的字被整字跳过。
        val text = "身份摆在这"
        val positions = intArrayOf(0, 0, 1, 1, 3, 3, 4, 4)
        val table = CellSourceTable(text, positions)
        val stream = (0 until positions.size)
            .joinToString("") { (0x2801).toChar().toString() }

        val view = ReaderSourceModel.build(
            kind = ReaderSourceKind.TEXT, sourceText = text,
            brailleScreenText = "盲", spans = table, offset = 0, count = positions.size,
            currentUnit = 0, totalUnits = 1, stream = stream)

        // 早先只跳过重复格：得到「身摆在这」（缺「份」）；现在中间被跳的字按原文补齐。
        assertEquals("身份摆在", view.displayText.take(4))
    }

    @Test
    fun tableWithoutStreamUsesCellIndexOffset() {
        val text = "你好世界"
        val table = CellSourceTable(text, intArrayOf(0, 1, 2, 3))
        val view = ReaderSourceModel.build(
            kind = ReaderSourceKind.TEXT, sourceText = text,
            brailleScreenText = "盲", spans = table, offset = 2, count = 2,
            currentUnit = 0, totalUnits = 2)
        assertEquals("世界", view.displayText)
    }
}
