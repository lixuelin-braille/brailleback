package com.googlecode.eyesfree.brailleback.wrapping

import com.googlecode.eyesfree.braille.translate.TranslationResult
import com.googlecode.eyesfree.brailleback.DisplayManager.Content
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍段落排版测试：段首空两方、强制行首、映射与光标平移。
 */
class ParagraphIndentTest {

    /** “第一段。\n\n第二段。”：汉字各占 1 方，换行占 0 方。 */
    private fun twoParagraphs(cursor: Int = -1): TranslationResult = TranslationResult(
        cells = ByteArray(8) { i -> if (i < 4) 0x01 else 0x02 },
        textToBraillePositions = intArrayOf(0, 1, 2, 3, 4, 4, 4, 5, 6, 7),
        brailleToTextPositions = intArrayOf(0, 1, 2, 3, 6, 7, 8, 9),
        cursorPosition = cursor
    )

    @Test
    fun `blank line is a paragraph break but single newline is not`() {
        val text = "第一段。\n\n第二段。"
        assertArrayEquals(intArrayOf(6), ParagraphIndent.findStarts(text))
        assertTrue(ParagraphIndent.findStarts("甲乙\n丙丁").isEmpty())
        // 换行之间夹空格（微信读书实测是“空格 + 两个换行”）也算空行。
        assertArrayEquals(intArrayOf(5), ParagraphIndent.findStarts("甲乙\n \n丙丁"))
        // 结尾空行不产生段首。
        assertTrue(ParagraphIndent.findStarts("甲乙\n\n").isEmpty())
    }

    @Test
    fun `only long reading text enables paragraph layout`() {
        assertFalse(ParagraphIndent.shouldFormat("短\n\n文本"))
        assertFalse(ParagraphIndent.shouldFormat("长".repeat(120)))
        assertTrue(ParagraphIndent.shouldFormat("长".repeat(120) + "\n\n第二段"))
    }

    @Test
    fun `indent inserts two blank cells at paragraph start`() {
        val result = ParagraphIndent.apply("第一段。\n\n第二段。", twoParagraphs())
        assertEquals(10, result.cells.size)
        assertArrayEquals(
            byteArrayOf(1, 1, 1, 1, 0, 0, 2, 2, 2, 2),
            result.cells
        )
        // 段首字符（下标 6）从第 4 格移到第 6 格，前面的换行不动。
        assertArrayEquals(
            intArrayOf(0, 1, 2, 3, 4, 4, 6, 7, 8, 9),
            result.textToBraillePositions
        )
        // 插入的两方反向映射到第二段段首。
        assertArrayEquals(
            intArrayOf(0, 1, 2, 3, 6, 6, 6, 7, 8, 9),
            result.brailleToTextPositions
        )
        assertEquals(-1, result.cursorPosition)
    }

    @Test
    fun `cursor after paragraph start shifts with indent`() {
        assertEquals(7, ParagraphIndent.apply("第一段。\n\n第二段。", twoParagraphs(5)).cursorPosition)
        assertEquals(3, ParagraphIndent.apply("第一段。\n\n第二段。", twoParagraphs(3)).cursorPosition)
    }

    @Test
    fun `wrap strategy starts each paragraph on its own line after the indent`() {
        val text = "第一段。\n\n第二段。"
        val translation = ParagraphIndent.apply(text, twoParagraphs())
        val strategy = WordWrapStrategy()
        strategy.setContent(
            Content().setText(text).setParagraphIndent(true),
            translation,
            10
        )

        strategy.panTo(0, false)
        assertEquals(0, strategy.getDisplayStart())
        assertEquals(4, strategy.getDisplayEnd())

        assertTrue(strategy.panRight())
        assertEquals(4, strategy.getDisplayStart())
        assertEquals(10, strategy.getDisplayEnd())
        assertEquals(0.toByte(), translation.cells[4])
        assertEquals(0.toByte(), translation.cells[5])
        assertEquals(2.toByte(), translation.cells[6])
    }

    @Test
    fun `text without blank lines keeps original translation`() {
        val original = twoParagraphs()
        val result = ParagraphIndent.apply("甲乙\n丙丁", original)
        assertEquals(original, result)
    }

    @Test
    fun `indentFirst treats the whole block as one paragraph`() {
        val translation = TranslationResult(
            cells = byteArrayOf(1, 2, 3),
            textToBraillePositions = intArrayOf(0, 1, 2),
            brailleToTextPositions = intArrayOf(0, 1, 2),
            cursorPosition = 2
        )
        val result = ParagraphIndent.apply("甲乙丙", translation, indentFirst = true)

        assertArrayEquals(byteArrayOf(0, 0, 1, 2, 3), result.cells)
        // 所有字符整体后移两方。
        assertArrayEquals(intArrayOf(2, 3, 4), result.textToBraillePositions)
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 2), result.brailleToTextPositions)
        assertEquals(4, result.cursorPosition)
    }

    @Test
    fun `indentFirst keeps paragraph starts after the first`() {
        val result = ParagraphIndent.apply("第一段。\n\n第二段。", twoParagraphs(), indentFirst = true)
        // 段首两方 + 第二段两方：第一段 4 格，第二段 4 格，共 10 + 2 格。
        assertEquals(12, result.cells.size)
        assertArrayEquals(
            byteArrayOf(0, 0, 1, 1, 1, 1, 0, 0, 2, 2, 2, 2),
            result.cells
        )
    }
}
