/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * EbookTextExtractor 单元测试（纯 JVM，无 Android 依赖）。
 */

package com.googlecode.eyesfree.brailleback

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookTextExtractorTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun container(opfPath: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0"
            xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="$opfPath"
                media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private fun opf(vararg items: Pair<String, String>): String {
        val manifest = items.joinToString("\n") { (id, href) ->
            """    <item id="$id" href="$href" media-type="application/xhtml+xml"/>"""
        }
        val spine = items.joinToString("") { (id, _) ->
            """<itemref idref="$id"/>"""
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <manifest>
            $manifest
              </manifest>
              <spine>$spine</spine>
            </package>
        """.trimIndent()
    }

    @Test
    fun `epub 按 spine 顺序取文字并去标签`() {
        val book = zip(
            "META-INF/container.xml" to container("OEBPS/content.opf"),
            "OEBPS/content.opf" to opf(
                "c1" to "text/ch1.xhtml",
                "c2" to "text/ch2.xhtml",
            ),
            "OEBPS/text/ch1.xhtml" to
                "<html><head><style>.x{color:red}</style></head><body>" +
                "<h1>第一章</h1><p>你好&amp;世界&#20320;！</p>" +
                "<script>bad()</script></body></html>",
            "OEBPS/text/ch2.xhtml" to "<html><body><p>第二章</p></body></html>",
        )

        val result = EbookTextExtractor.extract(book, "book.epub")

        assertTrue("应当是 Success，实际 $result", result is EbookTextExtractor.Result.Success)
        val text = (result as EbookTextExtractor.Result.Success).text
        assertTrue("缺第一章", text.contains("第一章"))
        assertTrue("实体未解码（期望 你好&世界你！）", text.contains("你好&世界你！"))
        assertTrue("缺第二章", text.contains("第二章"))
        assertTrue("章序反了", text.indexOf("第一章") < text.indexOf("第二章"))
        assertFalse("script 未剔除", text.contains("bad()"))
        assertFalse("style 未剔除", text.contains("color:red"))
        assertFalse("仍有标签残留", text.contains("<"))
    }

    @Test
    fun `html 去标签并保留断行与实体`() {
        val html = "<html><body><p>第一行<br>第二行&nbsp;尾</p></body></html>"

        val result = EbookTextExtractor.extract(
            html.toByteArray(StandardCharsets.UTF_8), "page.HTML")

        assertTrue(result is EbookTextExtractor.Result.Success)
        val text = (result as EbookTextExtractor.Result.Success).text
        assertTrue("br 未断行: $text", text.contains("第一行\n第二行"))
        assertTrue("nbsp 未解码: $text", text.contains("\u00A0尾"))
    }

    @Test
    fun `缺少 container 的 zip 判定为无效 epub`() {
        val notEpub = zip("OEBPS/content.opf" to opf("c1" to "ch1.xhtml"))

        val result = EbookTextExtractor.extract(notEpub, "broken.epub")

        assertEquals(EbookTextExtractor.Result.Failure.NotEpub, result)
    }

    @Test
    fun `只有图片的 epub 报告没有文字`() {
        val opfText = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <manifest>
                <item id="img" href="images/a.png" media-type="image/png"/>
              </manifest>
              <spine><itemref idref="img"/></spine>
            </package>
        """.trimIndent()
        val book = zip(
            "META-INF/container.xml" to container("OEBPS/content.opf"),
            "OEBPS/content.opf" to opfText,
            "OEBPS/images/a.png" to "not-really-a-png",
        )

        val result = EbookTextExtractor.extract(book, "images.epub")

        assertEquals(EbookTextExtractor.Result.Failure.NoText, result)
    }

    @Test
    fun `markdown 原样返回且保留空行`() {
        val source = "第一段\n\n第二段"

        val result = EbookTextExtractor.extract(
            source.toByteArray(StandardCharsets.UTF_8), "notes.md")

        assertTrue(result is EbookTextExtractor.Result.Success)
        assertEquals(source, (result as EbookTextExtractor.Result.Success).text)
    }

    private fun wordXml(body: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <w:document
            xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body>$body<w:sectPr/></w:body>
        </w:document>
    """.trimIndent()

    @Test
    fun `docx 按段落取正文并拼接 run`() {
        val body = """
            <w:p><w:r><w:t>第一段</w:t></w:r></w:p>
            <w:p><w:r><w:t>第二</w:t></w:r><w:r><w:t>段</w:t></w:r>
              <w:r><w:tab/><w:t>尾</w:t></w:r>
              <w:r><w:br/><w:t>换行</w:t></w:r></w:p>
            <w:p><w:r><w:t>第三段&amp;尾</w:t></w:r></w:p>
        """.trimIndent()
        val docx = zip("word/document.xml" to wordXml(body))

        val result = EbookTextExtractor.extract(docx, "report.docx")

        assertTrue("应当是 Success，实际 $result", result is EbookTextExtractor.Result.Success)
        val text = (result as EbookTextExtractor.Result.Success).text
        assertTrue("缺第一段: $text", text.contains("第一段"))
        assertTrue("run 未拼接: $text", text.contains("第二段"))
        assertTrue("tab 未变空格: $text", text.contains("段 尾"))
        assertTrue("br 未断行: $text", text.contains("尾\n换行"))
        assertTrue("实体未解码: $text", text.contains("第三段&尾"))
        assertTrue("段间未断行: $text", text.contains("第一段\n第二段"))
    }

    @Test
    fun `没有 document_xml 的 docx 判定无效 Word`() {
        val broken = zip("word/styles.xml" to "<styles/>")

        assertEquals(
            EbookTextExtractor.Result.Failure.NotWord,
            EbookTextExtractor.extract(broken, "broken.docx"),
        )
        assertEquals(
            EbookTextExtractor.Result.Failure.NotWord,
            EbookTextExtractor.extract("not a zip".toByteArray(), "fake.docx"),
        )
    }

    @Test
    fun `只有图片的 docx 报告没有文字`() {
        val body = """<w:p><w:r><w:drawing/></w:r></w:p>"""
        val docx = zip("word/document.xml" to wordXml(body))

        assertEquals(
            EbookTextExtractor.Result.Failure.NoText,
            EbookTextExtractor.extract(docx, "images.docx"),
        )
    }

    @Test
    fun `支持的后缀`() {
        assertTrue(EbookTextExtractor.supports("book.EPUB"))
        assertTrue(EbookTextExtractor.supports("page.html"))
        assertTrue(EbookTextExtractor.supports("report.docx"))
        assertTrue(EbookTextExtractor.supports("report.docm"))
        assertFalse(EbookTextExtractor.supports("book.pdf"))
        assertFalse(EbookTextExtractor.supports("legacy.doc"))
        assertFalse(EbookTextExtractor.supports("book.brf"))
        assertEquals(
            EbookTextExtractor.Result.Failure.NotEpub,
            EbookTextExtractor.extract("x".toByteArray(), "book.pdf"),
        )
    }
}
