package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * BrailleFileDecoder 解码/分页/编码检测单元测试（纯 JVM）。
 *
 * 运行：`gradle :brailleback:testDebugUnitTest`
 */
class BrailleReaderLogicTest {

    /** 由 dot 编号串算点位值（测试侧独立实现）。 */
    private fun dotsValue(dots: String): Int {
        var v = 0
        for (d in dots) {
            if (d in '1'..'6') v = v or (1 shl (d - '1'))
        }
        return v
    }

    // ------------------------------------------------------------------
    // toDots：NABCC 标准映射
    // ------------------------------------------------------------------

    @Test
    fun testToDotsAsciiLetters() {
        assertEquals(dotsValue("1"), BrailleFileDecoder.toDots('A'))
        assertEquals(dotsValue("12"), BrailleFileDecoder.toDots('B'))
        assertEquals(dotsValue("14"), BrailleFileDecoder.toDots('C'))
        assertEquals(dotsValue("145"), BrailleFileDecoder.toDots('D'))
        assertEquals(dotsValue("15"), BrailleFileDecoder.toDots('E'))
        assertEquals(dotsValue("1245"), BrailleFileDecoder.toDots('G'))
        assertEquals(dotsValue("123"), BrailleFileDecoder.toDots('L'))
        assertEquals(dotsValue("1356"), BrailleFileDecoder.toDots('Z'))
    }

    @Test
    fun testToDotsDigitsAndPunct() {
        assertEquals(dotsValue("2"), BrailleFileDecoder.toDots('1'))
        assertEquals(dotsValue("23"), BrailleFileDecoder.toDots('2'))
        assertEquals(dotsValue("356"), BrailleFileDecoder.toDots('0'))
        assertEquals(dotsValue("2346"), BrailleFileDecoder.toDots('!'))
        assertEquals(dotsValue("6"), BrailleFileDecoder.toDots(','))
        assertEquals(dotsValue("46"), BrailleFileDecoder.toDots('.'))
        assertEquals(dotsValue("1456"), BrailleFileDecoder.toDots('?'))
        assertEquals(dotsValue("345"), BrailleFileDecoder.toDots('>'))
    }

    // ------------------------------------------------------------------
    // decodeDocument：txt 直通 / bdo 非 bdo 分派
    // ------------------------------------------------------------------

    @Test
    fun testDecodeDocumentTxtPassthrough() {
        val content = "这是一本盲文书\n第二行测试"
        val decoded = BrailleFileDecoder.decodeDocument(
            content.toByteArray(StandardCharsets.UTF_8), "book.txt")
        assertNotNull(decoded)
        assertEquals(content, decoded)
    }

    @Test
    fun testDecodeDocumentGbkChinese() {
        // 中文 BRF 多为 GBK 编码，需正确回退解码而非误判 UTF-8
        val content = "中文盲文点字"
        val cs = Charset.forName("GBK")
        val gbkBytes = content.toByteArray(cs)
        val decoded = BrailleFileDecoder.decodeDocument(gbkBytes, "book.brf")
        assertNotNull(decoded)
        assertEquals(content, decoded)
    }

    @Test
    fun testDecodeUtf8BomStripped() {
        val content = "UTF-8 with BOM"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val decoded = BrailleFileDecoder.decode(bom + content.toByteArray())
        assertEquals(content, decoded)
    }

    // ------------------------------------------------------------------
    // cellsAt：点显器分页（换行展平为盲文空格）
    // ------------------------------------------------------------------

    @Test
    fun testCellsAtPaging() {
        val stream = "abcdefghij"
        assertEquals("abcd", BrailleFileDecoder.cellsAt(stream, 0, 4))
        assertEquals("efgh", BrailleFileDecoder.cellsAt(stream, 4, 4))
        assertEquals("ij", BrailleFileDecoder.cellsAt(stream, 8, 4)) // 越界截断
        assertEquals("", BrailleFileDecoder.cellsAt(stream, 10, 4)) // offset 到底
        assertEquals("", BrailleFileDecoder.cellsAt(stream, -1, 4)) // 非法 offset
        assertEquals("", BrailleFileDecoder.cellsAt(stream, 0, 0)) // 非法 count
    }

    @Test
    fun testCellsAtFlattensNewline() {
        // 点显器单行：换行应展平为盲文空格 U+2800
        val stream = "ab\ncd"
        val cells = BrailleFileDecoder.cellsAt(stream, 0, 5)
        assertEquals("ab\u2800cd", cells)
    }

    // ------------------------------------------------------------------
    // detectKind：TEXT / BRAILLE 识别
    // ------------------------------------------------------------------

    @Test
    fun testDetectKindText() {
        assertEquals(BrailleFileDecoder.ContentKind.TEXT,
            BrailleFileDecoder.detectKind("hello world"))
    }

    @Test
    fun testDetectKindBraille() {
        // U+2801 = 点 1，纯盲文
        val braille = "\u2801\u2803\u2809\u2819"
        assertEquals(BrailleFileDecoder.ContentKind.BRAILLE,
            BrailleFileDecoder.detectKind(braille))
    }

    // ------------------------------------------------------------------
    // toReadingStream：保留盲文正文
    // ------------------------------------------------------------------

    @Test
    fun testToReadingStream() {
        val text = "\u2801\u2803\n\u2809"
        val stream = BrailleFileDecoder.toReadingStream(text)
        assertNotNull(stream)
        // 流应保留盲文字符（非空）
        assertEquals(true, stream.isNotEmpty())
    }
}
