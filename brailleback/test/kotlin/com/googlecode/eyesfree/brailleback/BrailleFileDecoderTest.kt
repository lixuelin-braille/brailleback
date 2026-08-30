package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * BrailleFileDecoder 单元测试（纯 JVM，无 Android 依赖）。
 *
 * 运行：`gradle :brailleback:testDebugUnitTest`
 */
class BrailleFileDecoderTest {

    private fun locate(rel: String): File? {
        // JUnit 工作目录为 brailleback 模块根，兼容从项目根运行
        for (c in listOf(rel, "../$rel", "brailleback/$rel")) {
            val f = File(c)
            if (f.isFile) return f
        }
        return null
    }

    /** 由 dot 编号串算点位值（测试侧独立实现，避免复用被测代码）。 */
    private fun dotsValue(dots: String): Int {
        var v = 0
        for (d in dots) {
            if (d in '1'..'6') v = v or (1 shl (d - '1'))
        }
        return v
    }

    // ------------------------------------------------------------------
    // NABCC 映射：以 liblouis 权威表 en-us-brf.dis 为黄金基准
    // ------------------------------------------------------------------

    @Test
    fun testNabccMatchesLiblouisTable() {
        val f = locate("service/jni/liblouiswrapper/liblouis/tables/en-us-brf.dis")
        if (f == null) {
            println("[skip] en-us-brf.dis 未找到，跳过表比对")
            return
        }
        var checked = 0
        for (line in f.readLines()) {
            if (!line.startsWith("display ")) continue
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 3) continue
            val ch = when (parts[1]) {
                "\\s" -> ' '
                "\\\\" -> '\\'
                else -> if (parts[1].length == 1) parts[1].single() else continue
            }
            val expected = dotsValue(parts[2])
            val actual = BrailleFileDecoder.toDots(ch)
            assertEquals("字符 '$ch'(0x${ch.code.toString(16)}) 点位不符", expected, actual)
            checked++
        }
        println("[NABCC] 比对 ${checked} 个映射，全部一致")
        assertEquals("Braille ASCII 共 64 个组合，应全部校验到", 64, checked)
    }

    // ------------------------------------------------------------------
    // 关键映射抽查（防回归：这些若用线性公式就会错）
    // ------------------------------------------------------------------

    @Test
    fun testKeyMappingsNotLinear() {
        // 线性公式 U+2800 + (c - 0x20) 会给出错误结果，以下断言可捕获回归
        assertEquals("空格 -> 空方", 0x2800.toChar().code - 0x2800,
            BrailleFileDecoder.toDots(' '))
        assertEquals("A -> 点1(U+2801)", 0x2801,
            BrailleFileDecoder.toUnicodeBraille("A").first().code)
        assertFalse("线性公式会算出 U+2821，必须不是它",
            BrailleFileDecoder.toUnicodeBraille("A").first().code == 0x2821)
        assertEquals("1 -> 点2(U+2802)", 0x2802,
            BrailleFileDecoder.toUnicodeBraille("1").first().code)
        assertEquals("# -> 点3456(U+283C)", 0x283C,
            BrailleFileDecoder.toUnicodeBraille("#").first().code)
        assertEquals("Z -> 点1356(U+2835)", 0x2835,
            BrailleFileDecoder.toUnicodeBraille("Z").first().code)
    }

    @Test
    fun testUnicodeBraillePassThrough() {
        val braille = "\u2801\u2803\u2809"
        assertEquals("已是 Unicode 盲文应原样保留", braille,
            BrailleFileDecoder.toUnicodeBraille(braille))
    }

    @Test
    fun testChinesePreserved() {
        // 中文不在 Braille ASCII 范围，应原样保留（不会转成乱码点位）
        assertEquals("中文盲文", BrailleFileDecoder.toUnicodeBraille("中文盲文"))
    }

    // ------------------------------------------------------------------
    // 编码识别
    // ------------------------------------------------------------------

    @Test
    fun testUtf8WithoutBom() {
        val text = "中文盲文电子书"
        assertEquals(text, BrailleFileDecoder.decode(
            text.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun testUtf8WithBom() {
        val text = "hello braille"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("BOM 应被剥离", text,
            BrailleFileDecoder.decode(bom + text.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun testUtf16LeBom() {
        val text = "盲文"
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertEquals(text, BrailleFileDecoder.decode(
            bom + text.toByteArray(StandardCharsets.UTF_16LE)))
    }

    @Test
    fun testUtf16BeBom() {
        val text = "盲文"
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        assertEquals(text, BrailleFileDecoder.decode(
            bom + text.toByteArray(StandardCharsets.UTF_16BE)))
    }

    @Test
    fun testGbkFallback() {
        // 中文 BRF 多为 GBK：这些字节不是合法 UTF-8，必须回退到 GBK 而非乱码
        val text = "中文盲文电子书"
        val gbk = text.toByteArray(charset("GBK"))
        // 前提：这段 GBK 字节确实不是合法 UTF-8（否则走不到回退分支）
        val utf8Strict = try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(gbk))
            true
        } catch (e: Exception) {
            false
        }
        println("[GBK] 该字节序列按严格 UTF-8 可解码=$utf8Strict")
        assertEquals("GBK 内容应正确还原", text, BrailleFileDecoder.decode(gbk))
        assertFalse("解码结果不应含替换字符 U+FFFD",
            BrailleFileDecoder.decode(gbk).contains('\uFFFD'))
    }

    @Test
    fun testPlainAscii() {
        val text = "THE QUICK BROWN FOX"
        assertEquals(text, BrailleFileDecoder.decode(
            text.toByteArray(StandardCharsets.US_ASCII)))
    }

    // ------------------------------------------------------------------
    // 分页
    // ------------------------------------------------------------------

    @Test
    fun testLongLineWrappedByCells() {
        val long = "A".repeat(100)
        val pages = BrailleFileDecoder.buildPages(long, cellsPerLine = 40, linesPerPage = 10)
        val firstPageLines = pages[0].split("\n")
        assertEquals("100 字符按 40 方应折成 3 行", 3, firstPageLines.size)
        assertTrue("每行不超过 40", firstPageLines.all { it.length <= 40 })
    }

    @Test
    fun testFormFeedForcesPageBreak() {
        // BRF 换页符应强制分段，而不是被当作普通字符
        val text = "AAA\nBBB\u000C CCC"
        val pages = BrailleFileDecoder.buildPages(text, 40, 10)
        assertEquals("换页符应把内容分为 2 页", 2, pages.size)
        // 注意：内容已转为 Unicode 盲文，比较需用转换后的串（A->U+2801、C->U+2809）
        val ccc = BrailleFileDecoder.toUnicodeBraille("CCC")
        assertTrue("第 1 页不应含换页符后的 C", !pages[0].contains(ccc))
        assertTrue("第 2 页应含换页符后的 C", pages[1].contains(ccc))
    }

    @Test
    fun testLinesPerPageChunking() {
        val text = (1..25).joinToString("\n") { "L$it" }
        val pages = BrailleFileDecoder.buildPages(text, 40, 10)
        assertEquals("25 行按每页 10 行应为 3 页", 3, pages.size)
        assertEquals("末页 5 行", 5, pages[2].split("\n").size)
    }

    @Test
    fun testPagesNeverLoseCharacters() {
        // 分页只允许插入换行，不得丢字符
        val text = "THE QUICK BROWN FOX JUMPS OVER\n\u000C 中文盲文混合 SECOND"
        val pages = BrailleFileDecoder.buildPages(text, 20, 5)
        val stripped = pages.joinToString("\n").filter { it != '\n' }
        // 换页符被分页消费掉，不参与字符守恒比较
        val expected = BrailleFileDecoder.toUnicodeBraille(text)
            .filter { it != '\n' && it != '\u000C' }
        assertEquals("分页后字符应与原文一致（仅增加换行）", expected, stripped)
    }

    // ------------------------------------------------------------------
    // 发送点显的一屏内容
    // ------------------------------------------------------------------

    @Test
    fun testPageToCellsTruncated() {
        val pages = BrailleFileDecoder.buildPages("A".repeat(100), 40, 10)
        val cells = BrailleFileDecoder.pageToCells(pages, 0, 20)
        assertEquals("一屏应不超过点显器方数", 20, cells.length)
    }

    @Test
    fun testPageToCellsFlattensNewline() {
        val pages = BrailleFileDecoder.buildPages("AAA\nBBB", 40, 10)
        val cells = BrailleFileDecoder.pageToCells(pages, 0, 40)
        assertFalse("换行应被展平为空格，点显器无换行概念", cells.contains('\n'))
        // 期望值同样要经过盲文转换（空格 -> U+2800）
        assertEquals(BrailleFileDecoder.toUnicodeBraille("AAA BBB"), cells)
    }

    @Test
    fun testPageToCellsOutOfRange() {
        val pages = BrailleFileDecoder.buildPages("AAA", 40, 10)
        assertEquals("", BrailleFileDecoder.pageToCells(pages, 5, 40))
        assertEquals("", BrailleFileDecoder.pageToCells(emptyList(), 0, 40))
    }
}
