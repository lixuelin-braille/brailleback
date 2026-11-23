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

object ReaderSourceModel {

    /**
     * 构建一屏视图。
     *
     * @param kind 源类型
     * @param sourceText TEXT 源的原始明文（翻译前文本）；盲文源可为空
     * @param brailleScreenText 现有盲文屏显文本（screenAt 输出），盲文源与退回点字时用
     * @param spans 逐格源区间（翻译流）；无则 null
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
    ): ReaderPageView {
        val displayText = when (kind) {
            ReaderSourceKind.TEXT -> reconstructSource(spans, offset, count)
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

    /** 由逐格跨度还原当前屏可见的原文；无跨度或还原为空返回 null。 */
    private fun reconstructSource(
        spans: List<CellSource>?,
        offset: Int,
        count: Int,
    ): String? {
        if (spans == null || offset >= spans.size) return null
        val end = min(offset + count, spans.size)
        val sb = StringBuilder()
        for (i in offset until end) {
            spans[i].sourceText?.let(sb::append)
        }
        return sb.toString().ifEmpty { null }
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
