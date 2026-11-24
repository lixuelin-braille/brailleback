package com.linxi.brailleback.tactile

/**
 * 封面 / 封底排版：把已经翻成 Unicode 盲文（U+2800..U+28FF）的若干行排进一屏。
 *
 * 纯 Kotlin，不依赖 android.*：
 * - 一方一个盲文字符；8 点取模式值低 8 位，6 点只取低 6 位（第 7/8 点清零）；
 * - 不足 cellsPerLine 的行右侧补 0x00 空方；
 * - 输出长度恒为 linesPerScreen × cellsPerLine；
 * - 行数超一屏或某行超一方宽，直接 [IllegalArgumentException]，不悄悄截断。
 */
object TactileStripCaption {

    /** 盲文图案起始码位。 */
    private const val BRAILLE_BASE = 0x2800

    /**
     * @param brailleLines 每行一个字符串，字符均为 U+2800..U+28FF 盲文图案。
     * @param cellsPerLine 一行方数。
     * @param linesPerScreen 一屏行数。
     * @param dotsPerCell 6 或 8。
     */
    fun page(
        brailleLines: List<String>,
        cellsPerLine: Int,
        linesPerScreen: Int,
        dotsPerCell: Int,
    ): ByteArray {
        require(cellsPerLine > 0) { "cellsPerLine 必须为正数，实际为 $cellsPerLine" }
        require(linesPerScreen > 0) { "linesPerScreen 必须为正数，实际为 $linesPerScreen" }
        require(dotsPerCell == 6 || dotsPerCell == 8) { "dotsPerCell 只能是 6 或 8" }
        require(brailleLines.size <= linesPerScreen) {
            "封面/封底行数 ${brailleLines.size} 超过一屏上限 $linesPerScreen"
        }
        val out = ByteArray(linesPerScreen * cellsPerLine)
        brailleLines.forEachIndexed { lineIndex, line ->
            require(line.length <= cellsPerLine) {
                "第 ${lineIndex + 1} 行字符数 ${line.length} 超过一行上限 $cellsPerLine"
            }
            line.forEachIndexed { cellIndex, ch ->
                var pattern = (ch.code - BRAILLE_BASE).coerceIn(0, 0xFF)
                pattern = if (dotsPerCell == 6) pattern and 0x3F else pattern and 0xFF
                out[lineIndex * cellsPerLine + cellIndex] = pattern.toByte()
            }
        }
        return out
    }
}
