/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 逐格源区间映射：翻译结果的 brailleToTextPositions 把每个盲文格映射回
 * 原文位置；这里落成纯 JVM 的格级跨度记录，供阅读器屏幕取字与点位 JSON 导出
 * 共用。段落级映射作为兜底：位置无效时整格回挂到所在段落区间。
 */

package com.linxi.brailleback

/** 源区间 [start, end)，含对应源文字。 */
data class SourceSpan(
    val start: Int,
    val end: Int,
    val text: String,
)

/** 单个盲文格对应的源信息；无映射时为 null（兜底由调用方按段落补）。 */
data class CellSource(
    val span: SourceSpan?,
    val sourceText: String?,
) {
    companion object {
        val NONE = CellSource(null, null)
    }
}

object SourceSpanMapper {

    /**
     * 按一段原文与翻译结果的格→文位置数组，逐格生成源跨度。
     *
     * @param paragraph 本段原文
     * @param brailleToTextPositions 每个盲文格对应的原文下标；越界/负值视为无效
     * @return 与盲文格一一对应的格级跨度列表
     */
    fun buildParagraphSpans(
        paragraph: String,
        brailleToTextPositions: IntArray,
    ): List<CellSource> {
        if (paragraph.isEmpty()) {
            return brailleToTextPositions.map { CellSource.NONE }
        }
        val paragraphSpan = SourceSpan(0, paragraph.length, paragraph)
        return brailleToTextPositions.map { pos ->
            if (pos in paragraph.indices) {
                val ch = paragraph[pos].toString()
                CellSource(SourceSpan(pos, pos + 1, ch), ch)
            } else {
                CellSource(paragraphSpan, paragraph)
            }
        }
    }

    /**
     * 多段拼接：把各段格级跨度按顺序接成整书列表；空段占位补齐。
     * 段与段之间不跨段引用，兜底跨度的 start/end 始终落在本段区间内。
     */
    fun concatenate(paragraphSpans: List<List<CellSource>>): List<CellSource> =
        paragraphSpans.flatten()
}
