/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阳光盲文 docx 解码、PUA 判定单测（纯 JVM）。
 * 重排（30→40 方）相关断言见本文件后半部分。
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SunshineDocxTest {

    /** 合成一个最小 docx（zip 内含 word/document.xml），正文来自给定段落。 */
    private fun docxOf(paragraphs: List<String>): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.append("<w:document xmlns:w=\"http://x\"><w:body>")
        for (p in paragraphs) {
            sb.append("<w:p><w:pPr><w:rPr/></w:pPr><w:r><w:t>")
            sb.append(p)
            sb.append("</w:t></w:r></w:p>")
        }
        sb.append("</w:body></w:document>")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(sb.toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return out.toByteArray()
    }

    /** PUA 码位：传入 1～63 的点位数，映射到 U+E234～U+E272；64 留空方 U+E273。 */
    private fun pua(vararg low: Int): String =
        buildString { for (v in low) append((0xE233 + v).toChar()) }

    // ------------------------------------------------------------------
    // docx 解码
    // ------------------------------------------------------------------

    @Test
    fun `docx extracts PUA text from w t nodes`() {
        // E234->值1, E240->值13, E273->空方
        val body = pua(0x01, 0x0D, 0x40) + "\uE273" + pua(0x0F)
        val bytes = docxOf(listOf(body))
        val text = BrailleFileDecoder.decodeSunshineDocx(bytes)
        assertNotNull(text)
        assertEquals(body, text)
    }

    @Test
    fun `docx skips style tags and unescapes entities`() {
        val bytes = docxOf(listOf(pua(0x01) + "&amp;" + pua(0x02)))
        val text = BrailleFileDecoder.decodeSunshineDocx(bytes)
        assertEquals(pua(0x01) + "&" + pua(0x02), text)
    }

    @Test
    fun `non docx bytes return null`() {
        assertEquals(null, BrailleFileDecoder.decodeSunshineDocx("plain text".toByteArray()))
    }

    // ------------------------------------------------------------------
    // PUA 判定与内容类型识别
    // ------------------------------------------------------------------

    @Test
    fun `PUA text is recognized as sunshine PUA`() {
        assertTrue(BrailleFileDecoder.isSunshinePua(pua(0x01, 0x02, 0x40, 0x03)))
    }

    @Test
    fun `Chinese text is not sunshine PUA`() {
        assertFalse(BrailleFileDecoder.isSunshinePua("这是一段普通汉字正文，不是盲文。"))
    }

    @Test
    fun `PUA text detects as BRAILLE and is not sent to translation`() {
        // PUA 占比 >50% 直接判已是盲文；若落进 TEXT 会被送翻译服务变成乱码。
        assertEquals(
            BrailleFileDecoder.ContentKind.BRAILLE,
            BrailleFileDecoder.detectKind(pua(0x01, 0x02, 0x40))
        )
    }

    @Test
    fun `decodeDocument routes docx through sunshine decoder and txt unchanged`() {
        val body = pua(0x01, 0x0D, 0x40) + "\uE273" + pua(0x0F)
        val decoded = BrailleFileDecoder.decodeDocument(docxOf(listOf(body)), "book.docx")
        val text = (decoded as? BrailleFileDecoder.DecodedDocument.Decoded)?.text
        assertNotNull(text)
        assertEquals(body, text)

        // 同名 txt 内容保持原样，不走 docx 分派。
        val txt = BrailleFileDecoder.decodeDocument(body.toByteArray(), "book.txt")
        assertEquals(BrailleFileDecoder.DecodedDocument.Decoded(body), txt)
    }
}
