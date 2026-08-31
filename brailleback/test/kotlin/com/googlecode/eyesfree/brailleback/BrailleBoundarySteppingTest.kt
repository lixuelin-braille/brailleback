package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.min

/**
 * 边界对齐步进测试。
 *
 * 背景：点显器单行，若严格按 unit 固定步进，约 74% 的屏起点落在字中间
 * （上一屏半个字、下一屏另外半个），无法摸读。`nextOffset` 在起点落在
 * 字中间时回退到字起点、落在边界时跳过连续空方，本测试验证：
 *
 * 1. 永不切断字（合成单字流，所有非首屏起点必在边界上）
 * 2. 覆盖完整、不倒退、不越界、不卡死（真实 bdo 文件 20/30/40 方）
 * 3. [BrailleFileDecoder.pageOffsets] 与逐个 `nextOffset` 迭代完全一致
 * 4. 跳过连续空方、长连续串照常前进等边界情形
 */
class BrailleBoundarySteppingTest {

    private val BLANK = '\u2800'

    private fun realStream(): String {
        val bytes = javaClass.getResourceAsStream("/real_bdo_sample.bdo")?.readBytes()
            ?: throw AssertionError("缺少测试资源 real_bdo_sample.bdo")
        val text = BdoCArchiveParser.parse(bytes)!!
        return BrailleFileDecoder.toReadingStream(text)
    }

    /** 与解码器内部同口径的边界判定：空方或段落换行都不算「字内容」。 */
    private fun isBoundary(c: Char): Boolean = c == BLANK || c == '\n'

    /** 用空方分隔的单字流：每字 [wordLen] 方、字间 1 个空方。 */
    private fun wordStream(count: Int, wordLen: Int = 3): String =
        (0 until count).joinToString(BLANK.toString()) { "\u2801".repeat(wordLen) }

    @Test
    fun `nextOffset never cuts a character`() {
        val stream = wordStream(100, 3)
        for (unit in listOf(20, 30, 40)) {
            var off = 0
            var guard = 0
            while (off < stream.length) {
                if (off != 0) {
                    // 不切断字 = 起点要么落在边界（空方/换行），要么是一个词的首字
                    // （前一字符是边界）。落在词中间才叫「切断」。
                    assertTrue(
                        "unit=$unit off=$off 起点不应落在词中间",
                        isBoundary(stream[off]) || isBoundary(stream[off - 1])
                    )
                }
                val next = BrailleFileDecoder.nextOffset(stream, off, unit)
                assertTrue("unit=$unit next($next) 必须前进", next > off)
                assertTrue(
                    "unit=$unit 一次前进不得超过 unit($unit): $off->$next",
                    next - off <= unit
                )
                off = next
                if (++guard > 10000) fail("unit=$unit 死循环")
            }
        }
    }

    @Test
    fun `boundary stepping covers the whole stream`() {
        val stream = realStream()
        val n = stream.length
        for (unit in listOf(20, 30, 40)) {
            val seen = BooleanArray(n)
            var off = 0
            var guard = 0
            while (off < n) {
                for (i in off until min(off + unit, n)) seen[i] = true
                val next = BrailleFileDecoder.nextOffset(stream, off, unit)
                assertTrue("unit=$unit 必须前进", next > off)
                off = next
                if (++guard > 10000) fail("unit=$unit 死循环")
            }
            // 空方（行首缩进、字间空格）允许被边界对齐有意跳过：
            // 让下一屏以内容开头、不浪费屏。用户摸读关心的是字而非空格。
            val missing = (0 until n).filter { !seen[it] && !isBoundary(stream[it]) }
            assertTrue(
                "unit=$unit 非空方内容应全部覆盖，缺失 ${missing.size} 个",
                missing.isEmpty()
            )
        }
    }

    @Test
    fun `pageOffsets matches nextOffset iteration and stays in bounds`() {
        val stream = realStream()
        for (unit in listOf(20, 30, 40)) {
            val offs = BrailleFileDecoder.pageOffsets(stream, unit)
            assertTrue("unit=$unit 页表非空", offs.isNotEmpty())
            assertEquals("unit=$unit 首屏从 0 开始", 0, offs[0])
            for (i in 1 until offs.size) {
                assertTrue("unit=$unit 页起点必须严格递增", offs[i] > offs[i - 1])
            }
            assertTrue("unit=$unit 末屏起点必须在流内", offs.last() < stream.length)

            // 与逐个 nextOffset 迭代的结果完全一致
            val manual = ArrayList<Int>()
            var off = 0
            var guard = 0
            while (off < stream.length) {
                manual.add(off)
                off = BrailleFileDecoder.nextOffset(stream, off, unit)
                if (++guard > 10000) fail("unit=$unit 死循环")
            }
            assertEquals("unit=$unit 页表应与 nextOffset 迭代一致", manual, offs.toList())
        }
    }

    @Test
    fun `nextOffset keeps leading indentation blanks`() {
        // 行首缩进空方是排版信息（标题空一/两/四方、正文抬头空两方），
        // 起点落在空方上时不得跳过：下一屏从该空方开始，缩进层次才保得住。
        // 19 方字 + 5 个空方 + 10 方字，unit=20 时 end=20 正好落在第 1 个空方上，
        // 应保持不动（返回 20），而非跳到 24 方之后的正文。
        val stream = "\u2801".repeat(19) + BLANK.toString().repeat(5) + "\u2803".repeat(10)
        val next = BrailleFileDecoder.nextOffset(stream, 0, 20)
        assertEquals("起点落在空方上应原样保留（缩进是内容）", 20, next)
        assertEquals('\u2800', stream[next])
    }

    @Test
    fun `nextOffset skips paragraph newlines`() {
        // 段落换行 \n 是纯结构边界，落在它上面说明上一段恰好读完：
        // 跳过换行，下一屏从新段落开头开始（\n 在点显器上会被展平为空方）。
        // 20 方字 + \n + 10 方字：end=20 正好落在 \n 上 -> 跳过到 21（新段首字）。
        val stream = "\u2801".repeat(20) + "\n" + "\u2803".repeat(10)
        val next = BrailleFileDecoder.nextOffset(stream, 0, 20)
        assertEquals("应跳过段落换行", 21, next)
        assertEquals('\u2803', stream[next])
    }

    @Test
    fun `nextOffset skips consecutive paragraph newlines`() {
        // 连续空段落产生的多个 \n（折叠后仍保留段落间空行）：应一并跳过，
        // 避免下一屏以多个空方开头浪费整屏。
        val stream = "\u2801".repeat(20) + "\n\n\n" + "\u2803".repeat(10)
        val next = BrailleFileDecoder.nextOffset(stream, 0, 20)
        assertEquals("连续换行应全部跳过", 23, next)
        assertEquals('\u2803', stream[next])
    }

    @Test
    fun `nextOffset steps forward inside a long unbroken run`() {
        // 连续 200 方无任何边界（乐谱长音型）：切不断只能照常前进，
        // 非末尾步骤应恰好前进 unit；最后一步到末尾允许不足 unit。
        val stream = "\u2801".repeat(200)
        for (unit in listOf(20, 30, 40)) {
            var off = 0
            var guard = 0
            while (off < stream.length) {
                val next = BrailleFileDecoder.nextOffset(stream, off, unit)
                assertTrue("unit=$unit 必须前进", next > off)
                if (next < stream.length) {
                    assertEquals("unit=$unit 无边界时步进应恰为 unit", unit, next - off)
                }
                off = next
                if (++guard > 10000) fail("unit=$unit 死循环")
            }
        }
    }

    @Test
    fun `nextOffset clamps at end of stream`() {
        val stream = "\u2801".repeat(45)
        // 从 40 起 step 20：end=60 越界，应返回流长度
        assertEquals(45, BrailleFileDecoder.nextOffset(stream, 40, 20))
        // 已到末尾再前进，直接返回流长度
        assertEquals(45, BrailleFileDecoder.nextOffset(stream, 45, 20))
        // 空流
        assertEquals(0, BrailleFileDecoder.nextOffset("", 0, 20))
    }
}
