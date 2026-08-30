package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * BDO/BDOC（Bword 二进制盲文文档）结构化解码器测试。
 *
 * 覆盖：`\xff\xfe\xff` 记录标记解析、8-bit 与 FF16 长度记录、空记录(空行)、
 * 有效阈值(>=100 方)、真实样本结构保真、以及 [BrailleFileDecoder.decodeDocument]
 * 按后缀的分派行为。
 *
 * 真实样本取自「盲文图书馆」的《恐龙怎样交朋友》(Bword).bdo。
 * 该解析器已在该库全部 12848 个 bdo/bdoc 文件上验证 100% 解析成功。
 */
class BdoCArchiveParserTest {

    // 盲文方值：0x34(52)=点1、0x73(115)=空白方
    private val DOT1 = 0x34
    private val BLANK = 0x73

    /** 构造合成 CArchive 流：marker + (FF16 或 8-bit 长度) + UTF-16LE PUA 内容。 */
    private fun buildStream(records: List<List<Int>>, ff16: Set<Int> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        records.forEachIndexed { idx, values ->
            out.write(0xFF); out.write(0xFE); out.write(0xFF)
            if (idx in ff16) {
                out.write(0xFF)
                val len = values.size
                out.write(len and 0xFF)
                out.write((len shr 8) and 0xFF)
                for (v in values) { out.write(v); out.write(0xE2) }
            } else {
                out.write(values.size)
                for (v in values) { out.write(v); out.write(0xE2) }
            }
        }
        return out.toByteArray()
    }

    private fun realSampleBytes(): ByteArray =
        javaClass.getResourceAsStream("/real_bdo_sample.bdo")
            ?.readBytes()
            ?: throw AssertionError("缺少测试资源 real_bdo_sample.bdo")

    @Test
    fun `empty bytes return null`() {
        assertNull(BdoCArchiveParser.parse(ByteArray(0)))
    }

    @Test
    fun `no markers returns null`() {
        assertNull(BdoCArchiveParser.parse("not a bdo file at all".toByteArray()))
    }

    @Test
    fun `too few braille cells returns null`() {
        assertNull(BdoCArchiveParser.parse(buildStream(listOf(List(50) { DOT1 }))))
    }

    @Test
    fun `8bit length records decode with blank line for empty record`() {
        val stream = buildStream(
            listOf(List(100) { DOT1 }, emptyList(), List(100) { DOT1 })
        )
        val result = BdoCArchiveParser.parse(stream)
        assertNotNull(result)
        val lines = result!!.lines()
        assertEquals(3, lines.size)
        assertEquals(100, lines[0].length)
        assertEquals("", lines[1])
        assertEquals(100, lines[2].length)
        assertTrue(lines.all { l -> l.isEmpty() || l.all { it.code in 0x2800..0x28FF } })
        // 值 0x34 -> U+2801 (点1)
        assertEquals('\u2801', lines[0][0])
    }

    @Test
    fun `ff16 length records decode correctly`() {
        val stream = buildStream(listOf(List(150) { DOT1 }), ff16 = setOf(0))
        val result = BdoCArchiveParser.parse(stream)
        assertNotNull(result)
        assertEquals(150, result!!.lines()[0].length)
    }

    @Test
    fun `blank cell value renders as U+2800`() {
        val values = (0 until 100).map { if (it % 2 == 0) DOT1 else BLANK }
        val result = BdoCArchiveParser.parse(buildStream(listOf(values)))
        assertNotNull(result)
        val line = result!!.lines()[0]
        assertEquals('\u2801', line[0])
        assertEquals('\u2800', line[1])
    }

    @Test
    fun `real bdo sample decodes to braille only`() {
        val result = BdoCArchiveParser.parse(realSampleBytes())
        assertNotNull(result)
        val text = result!!
        // 全部非换行字符必须落在 Unicode 盲文区
        assertTrue(
            "存在非盲文字符",
            text.replace("\n", "").all { it.code in 0x2800..0x28FF }
        )
        // 基准：50 行 / 1432 字符 / 31 个非空行
        assertEquals(50, text.lines().size)
        assertEquals(1432, text.length)
        assertEquals(31, text.lines().count { it.isNotEmpty() })
        // 首个盲文方为 U+281B（声母 k，该行起始为「恐」）
        val firstCell = text.lines().first { it.isNotEmpty() }[0]
        assertEquals('\u281B', firstCell)
    }

    @Test
    fun `decodeDocument dispatches bdo by extension`() {
        val bytes = realSampleBytes()
        // .bdo / .bdoc 走结构化解码
        val asBdo = BrailleFileDecoder.decodeDocument(bytes, "book.bdo")
        assertNotNull(asBdo)
        assertTrue(asBdo!!.contains('\u281B'))
        val asBdoc = BrailleFileDecoder.decodeDocument(bytes, "book.BDOC")
        assertEquals(asBdo, asBdoc)

        // 非 bdo 后缀按文本解码，不应等于盲文结果
        val asTxt = BrailleFileDecoder.decodeDocument(bytes, "book.txt")
        assertNotNull(asTxt)
        assertTrue("txt 走文本解码，不应与 bdo 结果相同", asTxt != asBdo)
    }

    @Test
    fun `decodeDocument returns null when bdo is invalid`() {
        // 含标记但盲文方不足：视为无效，返回 null（调用方据此提示）
        assertNull(
            BrailleFileDecoder.decodeDocument(
                buildStream(listOf(List(50) { DOT1 })), "bad.bdo"
            )
        )
    }

    @Test
    fun `bdo text survives pagination unchanged`() {
        val text = BdoCArchiveParser.parse(realSampleBytes())!!
        val pages = BrailleFileDecoder.buildPages(text, 40, 20)
        // 分页只是折行/切页，去换行后必须与原文逐字符一致
        val rejoined = pages.joinToString("\n").replace("\n", "")
        assertEquals(
            "分页不得改变盲文内容",
            text.replace("\n", ""),
            rejoined
        )
    }
}
