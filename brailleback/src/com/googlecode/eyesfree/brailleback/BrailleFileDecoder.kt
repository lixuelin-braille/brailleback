/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文文件解析：编码识别、ASCII 盲文转 Unicode、按点显器方数分页。
 * 纯 Kotlin 实现，不依赖 Android，便于 JVM 单元测试。
 */

package com.googlecode.eyesfree.brailleback

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.min

object BrailleFileDecoder {

    /** BRF 标准换页符（Form Feed）。 */
    private const val FORM_FEED = '\u000C'

    /**
     * North American Braille ASCII Code (NABCC) 点位表。
     *
     * 索引 = ASCII 码 - 0x20，值为 dot 编号串（"145" = 点 1、4、5）。
     * 取自 liblouis 权威表 service/jni/liblouiswrapper/liblouis/tables/en-us-brf.dis。
     *
     * 注意：该映射**非线性**，不能用 U+2800 + (c - 0x20) 计算。
     * 例如 'A' 是点 1（U+2801），线性公式会算成 U+2821（点 1、6），是错的。
     */
    private val NABCC_DOTS = arrayOf(
        "",       // 0x20 空格
        "2346",   // 0x21 !
        "5",      // 0x22 "
        "3456",   // 0x23 #
        "1246",   // 0x24 $
        "146",    // 0x25 %
        "12346",  // 0x26 &
        "3",      // 0x27 '
        "12356",  // 0x28 (
        "23456",  // 0x29 )
        "16",     // 0x2A *
        "346",    // 0x2B +
        "6",      // 0x2C ,
        "36",     // 0x2D -
        "46",     // 0x2E .
        "34",     // 0x2F /
        "356",    // 0x30 0
        "2",      // 0x31 1
        "23",     // 0x32 2
        "25",     // 0x33 3
        "256",    // 0x34 4
        "26",     // 0x35 5
        "235",    // 0x36 6
        "2356",   // 0x37 7
        "236",    // 0x38 8
        "35",     // 0x39 9
        "156",    // 0x3A :
        "56",     // 0x3B ;
        "126",    // 0x3C <
        "123456", // 0x3D =
        "345",    // 0x3E >
        "1456",   // 0x3F ?
        "4",      // 0x40 @
        "1",      // 0x41 A
        "12",     // 0x42 B
        "14",     // 0x43 C
        "145",    // 0x44 D
        "15",     // 0x45 E
        "124",    // 0x46 F
        "1245",   // 0x47 G
        "125",    // 0x48 H
        "24",     // 0x49 I
        "245",    // 0x4A J
        "13",     // 0x4B K
        "123",    // 0x4C L
        "134",    // 0x4D M
        "1345",   // 0x4E N
        "135",    // 0x4F O
        "1234",   // 0x50 P
        "12345",  // 0x51 Q
        "1235",   // 0x52 R
        "234",    // 0x53 S
        "2345",   // 0x54 T
        "136",    // 0x55 U
        "1236",   // 0x56 V
        "2456",   // 0x57 W
        "1346",   // 0x58 X
        "13456",  // 0x59 Y
        "1356",   // 0x5A Z
        "246",    // 0x5B [
        "1256",   // 0x5C backslash
        "12456",  // 0x5D ]
        "45",     // 0x5E ^
        "456"     // 0x5F _
    )

    /** 由 dot 编号串算点位值：dot1=bit0(1) ... dot6=bit5(32)，与 Unicode 盲文位序一致。 */
    private fun dotsValue(dots: String): Int {
        var v = 0
        for (d in dots) {
            if (d in '1'..'6') {
                v = v or (1 shl (d - '1'))
            }
        }
        return v
    }

    /** ASCII(0x20-0x5F) -> 点位值(0-63)；范围内必命中，故无需 -1 哨兵。 */
    private fun asciiToDots(c: Char): Int? {
        val idx = c.code - 0x20
        if (idx !in NABCC_DOTS.indices) return null
        return dotsValue(NABCC_DOTS[idx])
    }

    /**
     * 编码识别：BOM(UTF-8/UTF-16LE/UTF-16BE) -> 严格 UTF-8 -> GBK 回退。
     *
     * 中文 BRF 多为 GBK。若不做严格 UTF-8 校验而直接按 UTF-8 解码，
     * GBK 字节会被静默替换为 U+FFFD，整篇乱码，故必须回退。
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte()
            && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte()
            && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte()
            && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        // 严格 UTF-8：非法序列直接报错，避免把 GBK 误判成 UTF-8
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            // 落到 GBK
        }
        return try {
            String(bytes, charset("GBK"))
        } catch (e: Exception) {
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    /**
     * 单行 ASCII 盲文(0x20-0x5F) 转 Unicode 盲文(U+2800-)。
     * 已是 Unicode 盲文或其他字符（中文、标点）原样保留。
     */
    fun toUnicodeBraille(line: String): String {
        val sb = StringBuilder(line.length)
        for (c in line) {
            sb.append(brailleChar(c))
        }
        return sb.toString()
    }

    /**
     * 单字符转 Unicode 盲文。
     * 已是 Unicode 盲文则原样返回；Braille ASCII 按 NABCC 表转换；
     * 其余字符（中文等）原样保留。
     */
    private fun brailleChar(c: Char): Char {
        if (c.code in 0x2800..0x28FF) return c
        val dots = asciiToDots(c)
        return if (dots != null) (0x2800 + dots).toChar() else c
    }

    /**
     * 取字符对应的点位值（0-63）。非盲文字符返回 0（空方）。
     * 供发送到点显器时构造 patterns 数组。
     */
    fun toDots(c: Char): Int {
        if (c.code in 0x2800..0x28FF) return c.code - 0x2800
        return asciiToDots(c) ?: 0
    }

    /**
     * 构建分页。
     *
     * - 先按 BRF 换页符 [FORM_FEED] 分段（尊重文件自带分页）
     * - 段内每行按 [cellsPerLine]（点显器方数）折行，长行不再溢出屏幕
     * - 再按 [linesPerPage] 行切页
     */
    fun buildPages(
        text: String,
        cellsPerLine: Int,
        linesPerPage: Int
    ): List<String> {
        require(cellsPerLine > 0) { "cellsPerLine must be > 0" }
        require(linesPerPage > 0) { "linesPerPage must be > 0" }
        val pages = ArrayList<String>()
        for (section in text.split(FORM_FEED)) {
            val lines = ArrayList<String>()
            for (rawLine in section.split('\n')) {
                val line = toUnicodeBraille(rawLine.trimEnd('\r'))
                if (line.length <= cellsPerLine) {
                    lines.add(line)
                } else {
                    var i = 0
                    while (i < line.length) {
                        val end = min(i + cellsPerLine, line.length)
                        lines.add(line.substring(i, end))
                        i = end
                    }
                }
            }
            if (lines.isEmpty()) {
                lines.add("")
            }
            for (chunk in lines.chunked(linesPerPage)) {
                pages.add(chunk.joinToString("\n"))
            }
        }
        return pages
    }

    /**
     * 取第 [page] 页展平后、用于发送点显器的一屏内容（长度不超过 [cellsPerLine]）。
     * 换行按空格处理：点显器一屏是连续的方，不含换行概念。
     */
    fun pageToCells(pages: List<String>, page: Int, cellsPerLine: Int): String {
        if (pages.isEmpty() || page < 0 || page >= pages.size) return ""
        // 换行按盲文空格(U+2800)展平：点显器一屏是连续的方，无换行概念，
        // 且需与正文中已转换的空格保持一致（同为 U+2800，而非 ASCII 0x20）。
        val flat = pages[page].replace('\n', '\u2800')
        return if (flat.length <= cellsPerLine) flat else flat.substring(0, cellsPerLine)
    }
}
