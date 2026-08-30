package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单行点显器排版适配测试。
 *
 * 背景：点显器是**单行**的，常见 20 / 30 / 40 方不等。旧实现按「屏幕页」
 * 组织阅读并只把当前页的前 N 方发给点显器，导致 20 方点显器每页只能读到
 * 前 20 方（一页可达数百方），其余内容永远读不到，且无法在页内前进。
 *
 * 现改为以字符偏移为阅读位置、以点显器一屏为步进，本测试验证不同方数的
 * 点显器都能连续读完全文，且屏幕显示与点显器起点一致。
 */
class BrailleDisplayPagingTest {

    private fun realStream(): String {
        val bytes = javaClass.getResourceAsStream("/real_bdo_sample.bdo")?.readBytes()
            ?: throw AssertionError("缺少测试资源 real_bdo_sample.bdo")
        val text = BdoCArchiveParser.parse(bytes)!!
        return BrailleFileDecoder.toReadingStream(text)
    }

    /** 合成流：三段正文 + 一个空段落。 */
    private fun sampleStream(): String {
        val a = "\u2801".repeat(50)
        val b = "\u2803".repeat(35)
        val c = "\u2807".repeat(10)
        return BrailleFileDecoder.toReadingStream("$a\n\n$b\n$c\n")
    }

    @Test
    fun `reading stream keeps paragraph separation and drops edge blanks`() {
        val stream = sampleStream()
        // 首段 50 方、空段、次段 35 方、末段 10 方
        val parts = stream.split('\n')
        assertEquals(4, parts.size)
        assertEquals(50, parts[0].length)
        assertEquals("", parts[1])
        assertEquals(35, parts[2].length)
        assertEquals(10, parts[3].length)
    }

    @Test
    fun `cellsAt returns at most count cells and flattens newlines`() {
        val stream = sampleStream()
        val first = BrailleFileDecoder.cellsAt(stream, 0, 20)
        assertEquals(20, first.length)
        assertTrue(first.all { it.code in 0x2800..0x28FF })

        // 跨越段落边界时，换行应被展平为盲文空格
        val crossing = BrailleFileDecoder.cellsAt(stream, 45, 20)
        assertEquals(20, crossing.length)
        assertTrue("换行应展平为 U+2800，点显器无换行概念", crossing.contains('\u2800'))
        assertTrue(crossing.none { it == '\n' })
    }

    @Test
    fun `cellsAt is clamped at end of stream`() {
        val stream = sampleStream()
        val total = stream.length
        val tail = BrailleFileDecoder.cellsAt(stream, total - 3, 40)
        assertEquals("到达末尾不应越界", 3, tail.length)
        assertEquals("", BrailleFileDecoder.cellsAt(stream, total, 20))
        assertEquals("", BrailleFileDecoder.cellsAt(stream, total + 100, 20))
        assertEquals("", BrailleFileDecoder.cellsAt(stream, -1, 20))
    }

    @Test
    fun `all display widths cover the whole document`() {
        val stream = realStream()
        val expected = stream.replace('\n', '\u2800')
        for (cells in listOf(20, 30, 40)) {
            val read = StringBuilder()
            var offset = 0
            var screens = 0
            while (offset < stream.length) {
                read.append(BrailleFileDecoder.cellsAt(stream, offset, cells))
                offset += cells
                screens++
            }
            assertEquals(
                "${cells}方点显器按屏步进应能读到全文（共 $screens 屏）",
                expected, read.toString()
            )
        }
    }

    @Test
    fun `screen starts at the same offset as the display`() {
        val stream = realStream()
        for (offset in listOf(0, 137, 900)) {
            val screen = BrailleFileDecoder.screenAt(stream, offset, 40, 20)
            // 屏幕里的 '\n' 既有段落分隔符也有折行产生的换行，
            // 折行不是内容，故去掉全部换行后应与源流的连续内容一致。
            val flat = screen.replace("\n", "")
            val fromSource = stream.substring(offset).replace("\n", "")
            assertEquals(
                "offset=$offset 屏幕内容应就是从该偏移起的连续正文",
                fromSource.substring(0, minOf(flat.length, fromSource.length)),
                flat
            )
            // 同时确认屏幕首屏与点显器首屏来自同一位置
            val firstLine = screen.lines().first()
            assertEquals(
                "offset=$offset 屏幕与点显器起点须一致",
                BrailleFileDecoder.cellsAt(stream, offset, firstLine.length),
                firstLine
            )
        }
    }

    @Test
    fun `screen respects cells per line and line count`() {
        val stream = realStream()
        val screen = BrailleFileDecoder.screenAt(stream, 0, 25, 8)
        val lines = screen.lines()
        assertTrue("行数不得超过限制: ${lines.size}", lines.size <= 8)
        assertTrue(
            "每行宽度不得超过限制: ${lines.map { it.length }}",
            lines.all { it.length <= 25 }
        )
    }

    @Test
    fun `screen handles blank paragraph without eating extra lines`() {
        // 首段 50 方、空段、次段 35 方：40 方宽下应为
        // [40方][10方][空行][35方]
        val stream = sampleStream()
        val screen = BrailleFileDecoder.screenAt(stream, 0, 40, 8)
        val lines = screen.lines()
        assertEquals(40, lines[0].length)
        assertEquals(10, lines[1].length)
        assertEquals("", lines[2])
        assertEquals(35, lines[3].length)
    }

    @Test
    fun `unitCount matches stepping to the end`() {
        val stream = realStream()
        for (unit in listOf(20, 30, 40)) {
            val count = BrailleFileDecoder.unitCount(stream.length, unit)
            // 按页数换算，最后一屏的起点必须仍在流内
            val lastStart = (count - 1) * unit
            assertTrue(
                "unit=$unit 末屏起点($lastStart)应落在流内(${stream.length})",
                lastStart < stream.length
            )
            assertTrue(
                "unit=$unit 页数应覆盖全文",
                count * unit >= stream.length
            )
        }
    }

    @Test
    fun `old pageToCells limitation is what motivated offset paging`() {
        // 说明旧模型的问题：按屏幕页组织时，一页可达数百方，
        // 而 pageToCells 只取前 N 方，20 方点显器只能读到极小一部分。
        val text = BdoCArchiveParser.parse(
            javaClass.getResourceAsStream("/real_bdo_sample.bdo")!!.readBytes())!!
        val pages = BrailleFileDecoder.buildPages(text, 40, 20)
        val firstPageCells = pages[0].replace("\n", "").length
        val reachable = BrailleFileDecoder.pageToCells(pages, 0, 20).length
        assertTrue(
            "旧模型下一页有 $firstPageCells 方，20 方点显器只能读到 $reachable 方",
            firstPageCells > reachable
        )
    }
}
