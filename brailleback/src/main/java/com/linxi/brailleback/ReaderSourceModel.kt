/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器屏幕正文的纯 JVM 视图模型：按源文件类型分流——TEXT 源回显原文汉字，
 * BRF/BDO 等盲文源仍显示点字；页码、百分比、原文段位置、按行/段拆焦点都在这里
 * 算，不碰 mOffset/mPageOffsets，不影响点显器发送与导出内容。
 */

package com.linxi.brailleback

import kotlin.math.min
import kotlin.math.roundToInt

/** 屏幕正文来源类型。 */
enum class ReaderSourceKind { TEXT, BRAILLE, ASCII }

/** 一屏的屏幕视图数据；文案组装留给界面层。 */
data class ReaderPageView(
    val displayText: String,
    val focusSegments: List<String>,
    val currentUnit: Int,
    val totalUnits: Int,
    val percent: Int,
    val paragraphIndex: Int,
    val paragraphCount: Int,
)

/**
 * 正文列表里的一项 = 一段可聚焦文本 + 它属于第几页（0 基）。
 *
 * 正文现在是一条可以往下滚的焦点流：屏幕里摆的不止本页的段，
 * 还接着后面几页的段，焦点滑到页末会自然进到下一页的段。
 * 页号不必等翻页动作，看「当前第一段落在哪一页」就知道了。
 */
data class ReaderSegment(val text: String, val pageIndex: Int)

object ReaderSourceModel {

    /**
     * 构建一屏视图。
     *
     * @param kind 源类型
     * @param sourceText TEXT 源的原始明文（翻译前文本）；盲文源可为空
     * @param brailleScreenText 现有盲文屏显文本（screenAt 输出），盲文源与退回点字时用
     * @param spans 逐格源区间（翻译流）；无则 null。按**盲文格序号**索引，
     *   不含段间换行格（与点位 JSON 导出共用同一份，口径不能改）
     * @param stream 当前阅读流；给出时把 [offset]（流内字符位置）换算成盲文格序号，
     *   并在段间落换行，屏显才与点显器读到的那一格对得上。无则按 [offset] 直接当格序号
     * @param offset 当前屏起点在阅读流的偏移
     * @param count 当前屏可见格数
     * @param currentUnit 0 基当前屏序号
     * @param totalUnits 总屏数
     */
    fun build(
        kind: ReaderSourceKind,
        sourceText: String,
        brailleScreenText: String,
        spans: List<CellSource>?,
        offset: Int,
        count: Int,
        currentUnit: Int,
        totalUnits: Int,
        stream: String? = null,
    ): ReaderPageView {
        val displayText = when (kind) {
            ReaderSourceKind.TEXT -> reconstructSource(spans, offset, count, stream)
                ?: proportionalSlice(sourceText, offset, count)
                .ifEmpty { brailleScreenText }
            ReaderSourceKind.BRAILLE,
            ReaderSourceKind.ASCII -> brailleScreenText
        }

        val length = when {
            sourceText.isNotEmpty() -> sourceText.length
            else -> brailleScreenText.length
        }
        val percent = percent(offset, length, currentUnit, totalUnits)
        val (paragraphIndex, paragraphCount) = paragraphAt(sourceText, offset, length)

        val segments = displayText.split('\n')
            .filter { it.isNotBlank() }
        return ReaderPageView(
            displayText = displayText,
            focusSegments = segments.ifEmpty { listOf(displayText) },
            currentUnit = currentUnit,
            totalUnits = totalUnits,
            percent = percent,
            paragraphIndex = paragraphIndex,
            paragraphCount = paragraphCount,
        )
    }

    /**
     * 由逐格跨度还原当前屏可见的原文；无跨度或还原为空返回 null。
     *
     * 一个汉字通常占多格（声母、韵母、调号各一格），这些格的跨度指向同一个源字。
     * 早先逐格 `append`，一个字被重复它占用的格数那么多次（“国国国国国国”）；
     * 这里改为**同一个源片段只在连续格的第一格出现一次**。
     */
    private fun reconstructSource(
        spans: List<CellSource>?,
        offset: Int,
        count: Int,
        stream: String?,
    ): String? {
        if (spans == null) return null
        if (spans is CellSourceTable) {
            // 紧凑表：直接按源字符下标走，跳过的相邻字按原文补齐（见 reconstructFromTable）。
            return reconstructFromTable(
                spans, offset, count,
                if (stream != null && countBrailleCells(stream) == spans.size) stream else null)
        }
        if (stream != null && countBrailleCells(stream) == spans.size) {
            return reconstructFromStream(spans, offset, count, stream)
        }
        return reconstructByCellIndex(spans, offset, count)
    }

    /** 相邻源字被跳过的最大补齐长度；超过视为跳转（换段/换章），不硬补。 */
    private const val MAX_GAP_CHARS = 200

    /**
     * 紧凑逐格表版还原：[stream] 非 null 时 [offset] 是流内字符位置，否则按格序号。
     *
     * 词式分割下常出现相邻两个字的多格被压到同一个源位置（位置序列 0,0,1,1,2,2…），
     * 早先只按「与上一格相同就跳过」会把后一个字整字吞掉，屏显和播报就成了
     * 「身地」「举手之劳」这种缺字的句子。这里按源字符下标单调推进：
     * 位置前进超过 1 就把中间被跳过的原字按原文补齐，位置不前进则同一字的多格只出一次。
     */
    private fun reconstructFromTable(
        table: CellSourceTable,
        offset: Int,
        count: Int,
        stream: String?,
    ): String? {
        val sourceText = table.sourceText
        var index = 0
        var streamPos = 0
        if (stream != null) {
            val start = offset.coerceIn(0, stream.length)
            for (i in 0 until start) {
                if (isBrailleCell(stream[i])) index++
            }
            streamPos = start
        } else {
            index = offset.coerceIn(0, table.size)
        }
        if (index >= table.size) return null
        val sb = StringBuilder()
        var remaining = count
        var lastPos = -1
        while (remaining > 0 && index < table.size) {
            if (stream != null) {
                if (streamPos >= stream.length) break
                if (stream[streamPos] == '\n') {
                    if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
                    lastPos = -1
                    streamPos++
                    continue
                }
            }
            val charPos = table.charPosAt(index)
            if (charPos >= 0 && charPos < sourceText.length) {
                when {
                    lastPos < 0 -> sb.append(sourceText[charPos])
                    charPos == lastPos -> Unit // 同一源字的多格，只出一次
                    charPos > lastPos -> {
                        val gapFrom = lastPos + 1
                        val gapTo = charPos
                        val gap = gapTo - gapFrom + 1
                        if (gap <= MAX_GAP_CHARS) {
                            sb.append(sourceText, gapFrom, gapTo + 1)
                        } else {
                            sb.append(sourceText[charPos])
                        }
                    }
                    else -> Unit // 位置回退（极少见）按同一字处理
                }
                lastPos = charPos
            }
            remaining--
            index++
            if (stream != null) streamPos++
        }
        return sb.toString().ifEmpty { null }
    }

    /**
     * 跨度与阅读流逐格对齐（全流盲文格数 == 跨度数）时，沿阅读流走 [count] 格：
     * 段间换行落成屏显换行（正文才逐段可读），其余每格取对应跨度的源片段。
     */
    private fun reconstructFromStream(
        spans: List<CellSource>,
        offset: Int,
        count: Int,
        stream: String,
    ): String? {
        var index = 0
        for (i in 0 until offset.coerceIn(0, stream.length)) {
            if (isBrailleCell(stream[i])) index++
        }
        if (index >= spans.size) return null
        val sb = StringBuilder()
        var remaining = count
        var pos = offset
        var previous: SourceSpan? = null
        while (remaining > 0 && pos < stream.length && index < spans.size) {
            val c = stream[pos]
            if (c == '\n') {
                if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
                previous = null
            } else {
                val cell = spans[index]
                val text = cell.sourceText
                if (text != null && (previous == null || cell.span != previous)) {
                    sb.append(text)
                    previous = cell.span
                }
                remaining--
                index++
            }
            pos++
        }
        return sb.toString().ifEmpty { null }
    }

    /** 跨度与阅读流对不上时的兜底：按格序号直接取，连续同一源片段只取一次。 */
    private fun reconstructByCellIndex(
        spans: List<CellSource>,
        offset: Int,
        count: Int,
    ): String? {
        if (offset >= spans.size) return null
        val end = min(offset + count, spans.size)
        val sb = StringBuilder()
        var previous: Any? = null
        for (i in offset until end) {
            val cell = spans[i]
            val text = cell.sourceText ?: continue
            val key = cell.span ?: text
            if (sb.isNotEmpty() && key == previous) continue
            sb.append(text)
            previous = key
        }
        return sb.toString().ifEmpty { null }
    }

    private fun isBrailleCell(c: Char): Boolean = c.code in 0x2800..0x28FF

    private fun countBrailleCells(stream: String): Int {
        var n = 0
        for (c in stream) if (isBrailleCell(c)) n++
        return n
    }

    /** 跨度不可用时按偏移比例从原文取一段兜底。 */
    private fun proportionalSlice(text: String, offset: Int, count: Int): String {
        if (text.isEmpty()) return ""
        val ratio = if (text.length == 0) 0.0 else offset.toDouble() / text.length
        val start = (ratio * text.length).toInt().coerceIn(0, text.length)
        val end = min(start + count, text.length)
        return text.substring(start, end)
    }

    private fun percent(offset: Int, length: Int, currentUnit: Int, totalUnits: Int): Int {
        if (totalUnits <= 1) return 100
        if (currentUnit == totalUnits - 1) return 100
        if (length <= 0) return 0
        return (offset.toDouble() / length * 100).roundToInt().coerceIn(0, 99)
    }

    private fun paragraphAt(
        sourceText: String,
        offset: Int,
        length: Int,
    ): Pair<Int, Int> {
        if (sourceText.isEmpty()) return -1 to 0
        val paras = sourceText.split('\n')
        val ratio = if (length <= 0) 0.0 else offset.toDouble() / length
        val idx = (ratio * paras.size).toInt().coerceIn(0, paras.size - 1)
        return idx to paras.size
    }

    /**
     * 按中文句读切分句群：保留句末标点（。！？；!?;），不在半句处断行。
     * 供排版与焦点分段共用。
     */
    fun clauses(paragraph: String): List<String> {
        val result = ArrayList<String>()
        var buf = StringBuilder()
        for (c in paragraph) {
            buf.append(c)
            if (c in "。！？；!?;") {
                result.add(buf.toString())
                buf = StringBuilder()
            }
        }
        if (buf.isNotEmpty()) result.add(buf.toString())
        return result.ifEmpty { listOf(paragraph) }
    }
}
