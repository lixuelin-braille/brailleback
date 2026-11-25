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

/**
 * 紧凑的逐格源区间表：只保存每格对应的**源字符下标**（-1 表示无映射），
 * 需要 [CellSource] 时才现拼。
 *
 * 为什么不用 List<CellSource> 直接攒：一本 300 万字级的 txt 能翻出近 200 万格，
 * 逐格一个 CellSource + 一个 SourceSpan + 一个单字 String，加起来要 190MB 左右，
 * 远超 256MB 堆上限，翻译到一半就 java.lang.OutOfMemoryError（真机已复现）。
 * 这里 200 万格只占 8MB，且对外仍是 List<CellSource>，调用方不用改。
 */
class CellSourceTable(
    val sourceText: String,
    private val charPositions: IntArray,
) : AbstractList<CellSource>() {

    override val size: Int get() = charPositions.size

    /** 第 [cell] 格对应的源字符下标；-1 或越界都表示无映射。 */
    fun charPosAt(cell: Int): Int = charPositions[cell]

    override fun get(index: Int): CellSource {
        val pos = charPositions[index]
        if (pos !in sourceText.indices) return CellSource.NONE
        val ch = sourceText[pos].toString()
        return CellSource(SourceSpan(pos, pos + 1, ch), ch)
    }

    companion object {
        val EMPTY = CellSourceTable("", IntArray(0))
    }
}

/** 增长型 int 缓冲：逐格记源下标，避免 ArrayList<Integer> 的装箱与倍增拷贝。 */
class IntPositionBuffer(initialCapacity: Int = 1024) {

    private var data = IntArray(if (initialCapacity > 0) initialCapacity else 16)

    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == data.size) {
            data = data.copyOf(if (size >= 1 shl 20) size + (size shr 1) else maxOf(16, size * 2))
        }
        data[size++] = value
    }

    fun toIntArray(): IntArray = data.copyOf(size)
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
