/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 逐格源区间契约：位置有效时逐字映射；无效位置回挂段落兜底；段间不跨段引用。
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSpanMapperTest {

    @Test
    fun mapsEachCellToSourceCharacter() {
        val paragraph = "你好世界"
        val positions = intArrayOf(0, 1, 2, 3)
        val spans = SourceSpanMapper.buildParagraphSpans(paragraph, positions)

        assertEquals(4, spans.size)
        val expected = listOf("你", "好", "世", "界")
        spans.forEachIndexed { i, cell ->
            assertEquals(expected[i], cell.sourceText)
            assertEquals(i, cell.span?.start)
            assertEquals(i + 1, cell.span?.end)
        }
    }

    @Test
    fun invalidPositionFallsBackToParagraphSpan() {
        val paragraph = "你好世界"
        // -1 与越界 99 都无效，回挂整段。
        val spans = SourceSpanMapper.buildParagraphSpans(paragraph, intArrayOf(-1, 99))

        assertEquals(2, spans.size)
        spans.forEach { cell ->
            assertEquals(0, cell.span?.start)
            assertEquals(paragraph.length, cell.span?.end)
            assertEquals(paragraph, cell.sourceText)
        }
    }

    @Test
    fun spansDoNotCrossParagraphs() {
        val first = "第一段文字"
        val second = "第二段"
        val firstSpans = SourceSpanMapper.buildParagraphSpans(first, intArrayOf(0, 1, 2, 3, 4))
        val secondSpans = SourceSpanMapper.buildParagraphSpans(second, intArrayOf(0, 1, 2))

        firstSpans.forEach { cell ->
            assertTrue("首段跨度不越出本段", cell.span!!.end <= first.length)
        }
        secondSpans.forEach { cell ->
            assertTrue("次段跨度不越出本段", cell.span!!.end <= second.length)
        }
        val all = SourceSpanMapper.concatenate(listOf(firstSpans, secondSpans))
        assertEquals(firstSpans.size + secondSpans.size, all.size)
    }

}
