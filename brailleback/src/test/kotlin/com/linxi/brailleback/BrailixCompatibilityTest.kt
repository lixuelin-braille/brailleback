/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 与 Brailix brf.py 的逐字节兼容核对（参考只读，未复制其代码）：
 * - NABCC 64 个掩码与 Brailix 索引表逐字符一致；
 * - BRF 输出 = NABCC 字符 + CRLF，满 25 行插 FF，末页不挂尾 FF；
 * - BRF ASCII 经解码器往返，逐格掩码与原文一致。
 * 注：原点位 JSON 与 cells.py 的字段级对齐随 JSON 格式一并移除，见 docs/142。
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrailixCompatibilityTest {

    /** Brailix renderer/brf.py 的 NABCC 串：掩码 0..63 -> 字符。 */
    private val brailixNabcc =
        " A1B'K2L@CIF/MSP\"E3H9O6R^DJG>NTQ,*5<-U8V.%[\$+X!&;:4\\0Z7(_?W]#Y)="

    private fun cell(mask: Int): Char = (0x2800 + mask).toChar()

    @Test
    fun nabccMatchesBrailixTableForAllMasks() {
        assertEquals(64, brailixNabcc.length)
        for (mask in 0..63) {
            val expected = brailixNabcc[mask]
            assertEquals("mask $mask 应映射 '$expected'",
                expected, BrailleFileDecoder.dotsToAscii(mask))
        }
    }

    @Test
    fun brfBytesAreNabccCrlfWithoutTrailingFeed() {
        // 掩码 1,2,3 一行；默认 40 方 × 25 行，不折行、不插 FF。
        val stream = listOf(1, 2, 3).joinToString("") { cell(it).toString() }
        val result = BrailleExporter.export(stream, BrailleExportFormat.BRF)

        val expectedAscii = listOf(1, 2, 3).joinToString("") {
            brailixNabcc[it].toString()
        }
        assertEquals(expectedAscii, result.content)
        assertTrue("BRF 用 CRLF", result.content.all { it != '\n' })
        assertEquals(0, result.content.count { it == '\u000C' })
    }

    @Test
    fun brfInsertsFormFeedAfterTwentyFiveLines() {
        // 26 行单点：满 25 行且后面还有内容 → 1 个 FF；末页不挂尾 FF。
        val stream = (1..26).joinToString("\n") { cell(1).toString() }
        val result = BrailleExporter.export(
            stream, BrailleExportFormat.BRF, PageGeometry(40, 25))

        assertEquals(1, result.content.count { it == '\u000C' })
        val beforeFeed = result.content.substringBefore('\u000C')
        val afterFeed = result.content.substringAfter('\u000C').trim()
        assertEquals(25, beforeFeed.split("\r\n").size)
        assertEquals(1, afterFeed.split("\r\n").size)
    }

    @Test
    fun brfAsciiRoundTripsPerCellMask() {
        val masks = listOf(0, 1, 2, 3, 5, 10, 21, 32, 63)
        val stream = masks.joinToString("") { cell(it).toString() }
        val brf = BrailleExporter.export(stream, BrailleExportFormat.BRF).content

        val unicode = BrailleFileDecoder.toUnicodeBraille(brf)
        assertEquals(masks.size, unicode.length)
        masks.forEachIndexed { i, expected ->
            val actual = unicode[i].code - 0x2800
            assertEquals("第 $i 格掩码不一致", expected, actual)
        }
    }
}
