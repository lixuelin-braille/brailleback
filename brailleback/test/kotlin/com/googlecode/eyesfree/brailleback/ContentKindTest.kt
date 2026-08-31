package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BrailleFileDecoder 内容类型检测与点位转换测试。
 *
 * 覆盖：明眼中文/英文 -> TEXT、Unicode 盲文 -> BRAILLE、
 * Braille ASCII（BRF）-> ASCII，以及 cellsToUnicodeText 还原。
 */
class ContentKindTest {

    private fun locate(rel: String): File? {
        for (c in listOf(rel, "../$rel", "brailleback/$rel")) {
            val f = File(c)
            if (f.isFile) return f
        }
        return null
    }

    /** Braille ASCII：按 NABCC 把字母转成其盲文码字符（如 'A' -> '\u2801'）。 */
    private fun brfOf(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            val dots = BrailleFileDecoder.toDots(c)
            sb.append((0x2800 + dots).toChar())
        }
        return sb.toString()
    }

    @Test
    fun `Chinese text is TEXT`() {
        val text = "这本书讲述了很多人们存在误解的科普知识，向人们普及了科普常识。\n" +
            "作者：陈天昌。"
        assertEquals(BrailleFileDecoder.ContentKind.TEXT,
            BrailleFileDecoder.detectKind(text))
    }

    @Test
    fun `English prose is TEXT`() {
        val text = "This is a simple English sentence used to test detection. " +
            "It has words and spaces only, with a few punctuation marks."
        assertEquals(BrailleFileDecoder.ContentKind.TEXT,
            BrailleFileDecoder.detectKind(text))
    }

    @Test
    fun `unicode braille is BRAILLE`() {
        // 真实 bdo 样本：解析后全是 U+2800 系列
        val f = locate("test/resources/real_bdo_sample.bdo") ?: return
        val text = BdoCArchiveParser.parse(f.readBytes())
        assertTrue("样本解码不应为空", !text.isNullOrEmpty())
        assertEquals(BrailleFileDecoder.ContentKind.BRAILLE,
            BrailleFileDecoder.detectKind(text!!))
    }

    @Test
    fun `braille ascii with digits and symbols is ASCII`() {
        // Braille ASCII 只含 0x20-0x5F：大写字母、数字、标点、符号，无小写字母。
        // 盲文点数字符号（#1. $5 25% " & '）密集出现，与明眼英文正文（小写为主）区分。
        val text = "PAGE 1\n" +
            "#1. THIS COSTS $5, 25% OFF - A BRAILLE TEXT.\n" +
            "(2) \"HELLO\" & 'WORLD'\n"
        assertEquals(BrailleFileDecoder.ContentKind.ASCII,
            BrailleFileDecoder.detectKind(text))
    }

    @Test
    fun `mixed braille unicode and text falls to BRAILLE when dominant`() {
        val braille = brfOf("Braille")
        val text = braille + " " + braille + " " + braille +
            " 这是一段中文说明文字。"
        assertEquals(BrailleFileDecoder.ContentKind.BRAILLE,
            BrailleFileDecoder.detectKind(text))
    }

    @Test
    fun `cells to unicode text roundtrip`() {
        val cells = byteArrayOf(0x01, 0x19, 0x3F, 0x00, 0x20.toByte())
        val text = BrailleFileDecoder.cellsToUnicodeText(cells)
        assertEquals(5, text.length)
        assertEquals('\u2801', text[0])
        assertEquals('\u2819', text[1])
        assertEquals('\u283F', text[2])
        assertEquals('\u2800', text[3])
        assertEquals('\u2820', text[4])
    }

    @Test
    fun `cells to unicode preserves high bit dots`() {
        // 8 点盲文：点位 7/8 编码在高位
        val cells = byteArrayOf((-128).toByte()) // 0x80 -> 点 7
        val text = BrailleFileDecoder.cellsToUnicodeText(cells)
        assertEquals('\u2880', text[0])
    }
}
