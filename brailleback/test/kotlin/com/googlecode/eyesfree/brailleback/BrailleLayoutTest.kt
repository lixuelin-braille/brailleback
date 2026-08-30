package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版（分页）行为测试。
 *
 * 针对真实 bdo 文档实测发现的问题（见 BdoCArchiveParserTest）：
 * 封面区存在连续 10 个空行、文档首尾有空行、段落开头会被孤零零甩在页尾。
 * 本测试固化空行折叠、首尾空行去除、widow 控制与页面预览的行为。
 */
class BrailleLayoutTest {

    private val A = "\u2801"   // 点1，用作可见内容
    private val B = "\u2803"   // 点1-2

    /** 用 [A] 填充指定长度的一行。 */
    private fun line(n: Int, ch: String = A) = ch.repeat(n)

    @Test
    fun `consecutive blank lines collapse to one`() {
        val text = listOf(line(5), "", "", "", "", line(5)).joinToString("\n")
        val pages = BrailleFileDecoder.buildPages(text, 40, 20)
        val lines = pages.joinToString("\n").lines()
        val blankRuns = lines.fold(listOf(0)) { acc, l ->
            if (l.isEmpty()) acc.dropLast(1) + (acc.last() + 1) else acc + 0
        }
        assertTrue("连续空行应折叠为 1 个，实际: $lines", blankRuns.all { it <= 1 })
    }

    @Test
    fun `leading and trailing blank lines are removed`() {
        val text = listOf("", "", "", line(5), "", "", "").joinToString("\n")
        val joined = BrailleFileDecoder.buildPages(text, 40, 20).joinToString("\n")
        val lines = joined.lines()
        assertTrue("开头不应有空行", lines.first().isNotEmpty())
        assertTrue("结尾不应有空行", lines.last().isNotEmpty())
    }

    @Test
    fun `widow paragraph start is pushed to next page`() {
        // 19 行填充 + 1 个长段落（折成 2 行）。
        // 若不做 widow 控制，第 20 行会是该段落的首行，段落被劈在两页上。
        val filler = List(19) { line(3) }
        val longPara = line(70, B)   // 70 方 -> 折成 40 + 30 两行
        val text = (filler + longPara).joinToString("\n")
        val pages = BrailleFileDecoder.buildPages(text, 40, 20)
        assertTrue("应分出多页", pages.size >= 2)
        val firstPageLines = pages[0].lines()
        assertEquals("首页应只放 19 行填充，段落整体推到次页", 19, firstPageLines.size)
        assertTrue("被推下去的段落首行应在次页开头", pages[1].lines()[0].startsWith(B))
    }

    @Test
    fun `no widow control when paragraph fits in one row`() {
        // 段落只有 1 行时，paraStart && paraEnd 同时成立，不应被推走
        val text = (List(19) { line(3) } + line(5, B)).joinToString("\n")
        val pages = BrailleFileDecoder.buildPages(text, 40, 20)
        assertEquals("单行段落不算孤行，应留在首页", 20, pages[0].lines().size)
    }

    @Test
    fun `pagePreview returns first non blank line`() {
        val pages = listOf(
            "\u2800\u2800\n${line(30)}",
            "\n\n${line(10, B)}"
        )
        assertEquals(line(30), BrailleFileDecoder.pagePreview(pages, 0, 40))
        assertEquals(line(10, B), BrailleFileDecoder.pagePreview(pages, 1, 40))
    }

    @Test
    fun `pagePreview trims to maxLen and skips blank pages`() {
        val pages = listOf("", line(50))
        assertEquals("", BrailleFileDecoder.pagePreview(pages, 0, 10))
        assertEquals(line(10), BrailleFileDecoder.pagePreview(pages, 1, 10))
    }

    @Test
    fun `pagePreview is safe for out of range`() {
        val pages = listOf(line(5))
        assertEquals("", BrailleFileDecoder.pagePreview(pages, -1, 10))
        assertEquals("", BrailleFileDecoder.pagePreview(pages, 99, 10))
        assertEquals("", BrailleFileDecoder.pagePreview(emptyList(), 0, 10))
    }

    @Test
    fun `pagination never drops or duplicates content`() {
        // 分页只应增加换行，不得改变正文字符
        val text = (List(37) { line(3 + it % 30) } ).joinToString("\n")
        val joined = BrailleFileDecoder.buildPages(text, 40, 7).joinToString("\n")
        assertEquals(
            "分页后去换行须与原文一致",
            text.replace("\n", ""),
            joined.replace("\n", "")
        )
    }

    @Test
    fun `real bdo first page is no longer mostly blank`() {
        val bytes = javaClass.getResourceAsStream("/real_bdo_sample.bdo")?.readBytes()
            ?: throw AssertionError("缺少测试资源 real_bdo_sample.bdo")
        val text = BdoCArchiveParser.parse(bytes)!!
        val cells = 40
        val lpp = 20
        val pages = BrailleFileDecoder.buildPages(text, cells, lpp)
        val first = pages[0]
        val filled = first.count { it != '\n' && it != '\u2800' }
        val ratio = filled.toDouble() / (cells * lpp)
        // 基线（同口径实测）：优化前首页有效内容占 25.4%，折叠空行后 29.5%。
        // 批量 12 个真实文件的首页占比由平均 6.4% 提升到 36.0%（12/12 全部改善）。
        assertTrue(
            "首页利用率过低: ${"%.1f".format(ratio * 100)}%（优化前基线 25.4%）",
            ratio > 0.27
        )
        assertTrue("首页不应以空行开头", first.lines().first().isNotEmpty())
    }
}
