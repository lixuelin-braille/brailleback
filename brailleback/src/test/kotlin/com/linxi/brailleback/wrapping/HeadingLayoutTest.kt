package com.linxi.brailleback.wrapping

import com.googlecode.eyesfree.braille.translate.TranslationResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 标题居中：左侧补空白方，映射与光标同步平移。
 */
class HeadingLayoutTest {

    private fun translation(cells: ByteArray, cursor: Int = -1): TranslationResult =
        TranslationResult(
            cells = cells,
            textToBraillePositions = IntArray(cells.size) { it },
            brailleToTextPositions = IntArray(cells.size) { it },
            cursorPosition = cursor
        )

    @Test
    fun `centers short heading on display`() {
        val result = HeadingLayout.center(translation(byteArrayOf(1, 2, 3, 4)), 40)

        // (40 - 4) / 2 = 18 方空白，标题从第 18 格开始。
        assertEquals(22, result.cells.size)
        assertEquals(0.toByte(), result.cells[17])
        assertEquals(1.toByte(), result.cells[18])
        assertEquals(4.toByte(), result.cells[21])
        assertArrayEquals(intArrayOf(18, 19, 20, 21), result.textToBraillePositions)
        assertEquals(0, result.brailleToTextPositions[17])
        assertEquals(0, result.brailleToTextPositions[18])
        assertEquals(1, result.brailleToTextPositions[19])
        assertEquals(-1, result.cursorPosition)
    }

    @Test
    fun `cursor shifts with centering`() {
        assertEquals(
            20,
            HeadingLayout.center(translation(byteArrayOf(1, 2, 3, 4), 2), 40).cursorPosition
        )
    }

    @Test
    fun `heading that fills display is unchanged`() {
        val result = HeadingLayout.center(translation(ByteArray(40) { 1 }), 40)
        assertEquals(40, result.cells.size)
        assertEquals(1.toByte(), result.cells[0])
    }

    @Test
    fun `oversized heading is not padded`() {
        val result = HeadingLayout.center(translation(ByteArray(41) { 1 }), 40)
        assertEquals(41, result.cells.size)
    }

    @Test
    fun `empty content and invalid width are unchanged`() {
        val empty = translation(ByteArray(0))
        assertSame(empty, HeadingLayout.center(empty, 40))
        val one = translation(byteArrayOf(1))
        assertSame(one, HeadingLayout.center(one, 0))
    }
}
