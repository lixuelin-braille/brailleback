/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阳光盲文 docx 解码、PUA 判定与 30→40 方重排单测（纯 JVM）。
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

    private fun reflow(vararg lines: String): String =
        BrailleFileDecoder.reflowSunshine(lines.joinToString("\n"), width = 40)

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

    // ------------------------------------------------------------------
    // 30→40 方重排
    // ------------------------------------------------------------------

    @Test
    fun `reflow packs words to 40 cells`() {
        // 20 个「2 方词」之间用空方分隔，原是一行；重排后每行不超过 40 方。
        val space = '\uE273'
        val words = (1..20).map { pua(0x01, 0x02) }
        val line = words.joinToString(space.toString())
        val out = BrailleFileDecoder.reflowSunshine(line, width = 40)
        val lines = out.split('\n').filter { it.isNotEmpty() }
        assertTrue("应重排为多行", lines.size >= 2)
        for (l in lines) assertTrue("每行 <=40 方，实际 ${l.length}", l.length <= 40)
        // 内容无损：去掉重排引入的换行后应与原词序列一致
        assertEquals(words, out.split('\n').flatMap { it.split(space).filter { w -> w.isNotEmpty() } })
    }

    @Test
    fun `reflow merges tiny tail instead of leaving a one-cell orphan`() {
        // 词长组合使重排后原本只剩 1 方尾巴；code89 应把上一词连同空方并回来。
        val space = '\uE273'
        val lengths = listOf(1, 3, 2, 3, 1, 5, 2, 3, 3, 1, 2, 2, 3, 5, 2, 3, 3, 4, 3, 4, 4, 1)
        val body = lengths.joinToString(space.toString()) { len ->
            buildString { repeat(len) { append(pua(0x01)) } }
        }
        val lines = reflow(body).split('\n').filter { it.isNotEmpty() }
        assertTrue("段尾不应只剩 1～2 方", lines.last().count { it != space } > 2)
        assertTrue("不应出现单方孤行", lines.none { it.count { c -> c != space } == 1 })
        assertEquals("一个方都不能丢", body, lines.joinToString(""))
        assertTrue("每行仍不超过 40 方", lines.all { it.length <= 40 })
    }

    @Test
    fun `reflow inserts blank at non-continuation seams and keeps existing blanks`() {
        val space = '\uE273'
        val link = '\uE257'
        // 用 15 方的行，避免误命中 isPageNumLine（短行会被分流）。
        val next = pua(0x02).repeat(15)
        // 上行末方含 4/5 点：词边界，补空方。
        val prevDot4 = pua(0x01).repeat(14) + pua(0x0F)
        assertEquals(prevDot4 + space + next, reflow(prevDot4, next))
        val prevDot5 = pua(0x01).repeat(14) + pua(0x10)
        assertEquals(prevDot5 + space + next, reflow(prevDot5, next))
        // code71 实验规则：上行末方只有 1/2/3 点也补一个空方。
        val prevNo45 = pua(0x01).repeat(14) + pua(0x07)
        assertEquals(prevNo45 + space + next, reflow(prevNo45, next))
        // 36 点续行：删标记后直接接，不加空方。
        assertEquals(prevDot4 + next, reflow(prevDot4, link + next))
        // 下一行原文已以空方开头、或上行结尾已有空方：不重复插。
        assertEquals(prevDot4 + space + next, reflow(prevDot4, space + next))
        assertEquals(prevDot4 + space + next, reflow(prevDot4 + space, next))
        // 原文一行内部的词间空方保持原样。
        val withWordSpace = prevDot4 + space + next
        assertEquals(withWordSpace, reflow(withWordSpace))
    }

    @Test
    fun `reflow treats short 36-point line as continuation not page`() {
        val link = '\uE257'
        val prev = pua(0x01).repeat(15)
        val tail = pua(0x02, 0x03)          // 很短，旧逻辑会误判成页码/标题
        assertEquals(prev + tail, reflow(prev, link + tail))
        // 上一行末方含 4/5 点也不能插空方：这是同一个词的续行。
        val prevDot4 = pua(0x01).repeat(14) + pua(0x0F)
        assertEquals(prevDot4 + tail, reflow(prevDot4, link + tail))
    }

    @Test
    fun `reflow starts new paragraph at two-cell indented line`() {
        val space = '\uE273'
        val prev = pua(0x01).repeat(15)
        val next = space.toString().repeat(2) + pua(0x02).repeat(15)
        val lines = reflow(prev, next).split('\n')
        assertEquals(3, lines.size)
        assertEquals(prev, lines[0])
        assertEquals("", lines[1])
        assertEquals(next, lines[2])
    }

    @Test
    fun `reflow keeps toc untouched and inlines page number with four blanks`() {
        val space = '\uE273'
        val guide = '\uE243'
        val toc = guide.toString().repeat(6)              // 目录行：>=3 个引导点
        val page = pua(0x31, 0x32)                        // 页码行：短行
        val body = (1..30).map { pua(0x01, 0x02) }.joinToString(space.toString())
        val out = reflow(toc, body, page)
        val lines = out.split('\n')
        assertEquals(toc, lines[0])
        assertTrue("页码不应再单独占一行", lines.none { it == page })
        val pad = space.toString().repeat(2)
        assertTrue("页码应内联在正文末尾，前后各两个空方",
            lines.last().endsWith(pad + page + pad))
    }

    @Test
    fun `reflow keeps short line after blank as standalone title`() {
        val title = pua(0x01, 0x02, 0x03)
        val body = (1..30).map { pua(0x01, 0x02) }.joinToString('\uE273'.toString())
        val lines = reflow(title, "", body).split('\n')
        assertEquals(title, lines[0])
        assertEquals("", lines[1])
    }

    // ------------------------------------------------------------------
    // decodeDocument 分派
    // ------------------------------------------------------------------

    @Test
    fun `decodeDocument routes docx through reflow but txt unchanged`() {
        val space = '\uE273'
        val longLine = (1..30).map { pua(0x01, 0x02) }.joinToString(space.toString())
        val decoded = BrailleFileDecoder.decodeDocument(docxOf(listOf(longLine)), "book.docx")
        val text = (decoded as? BrailleFileDecoder.DecodedDocument.Decoded)?.text
        assertNotNull(text)
        assertTrue("docx 正文应被重排为多行", text!!.contains('\n'))

        // 同名 txt 内容保持原样，不重排。
        val txt = BrailleFileDecoder.decodeDocument(longLine.toByteArray(), "book.txt")
        assertEquals(BrailleFileDecoder.DecodedDocument.Decoded(longLine), txt)
    }
}
