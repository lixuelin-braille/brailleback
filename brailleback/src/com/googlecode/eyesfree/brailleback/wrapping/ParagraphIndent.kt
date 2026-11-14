/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.wrapping

import com.googlecode.eyesfree.braille.translate.TranslationResult

/**
 * 无障碍长文本的段落排版。
 *
 * 点显器没有“行”的概念：整段文字是一串连续的格，换行符在盲文表里也被
 * 定义成 0 方。这里把原文中的空行当作段边界：
 *
 * - 每个段首插入 [INDENT_CELLS] 个空白方（国标正文抬头空两方）；
 * - 段首由 [WrapStrategy] 记为强制换行点，下一段从行首开始显示。
 *
 * 只处理“至少一个空行分隔”的文本。微信读书等阅读器把整页正文放在一个
 * content-desc 里，段间用 `空格 + \n\n` 分隔；单个换行可能是排版软换行，
 * 不当成段落，避免把每个视觉行都缩进两方。
 */
object ParagraphIndent {
    /** 正文抬头空两方（GB/T 15720-2008 7.2）。 */
    const val INDENT_CELLS = 2

    /** 低于此长度不启用段落排版，避免影响按钮、菜单等短节点。 */
    const val MIN_READING_LENGTH = 80

    /** 判断这段文字是否值得按段落排版（长文本 + 至少一个空行）。 */
    fun shouldFormat(text: CharSequence?): Boolean =
        text != null && text.length >= MIN_READING_LENGTH && findStarts(text).isNotEmpty()

    /**
     * 返回每个段首在原文中的字符下标。
     *
     * 段落分隔必须是两个及以上换行（中间允许夹空格/制表符），即空行；
     * 单个换行按软换行处理。第一段不返回：分页阅读的页首可能是上一段
     * 的续行，贸然缩进会伪造段首。
     */
    fun findStarts(text: CharSequence): IntArray {
        val starts = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                var j = i + 1
                var newlines = 1
                while (j < text.length) {
                    val c = text[j]
                    if (c == '\n') {
                        newlines++
                        j++
                    } else if (c == ' ' || c == '\t' || c == '\u00A0' || c == '\u3000') {
                        j++
                    } else {
                        break
                    }
                }
                if (newlines >= 2 && j < text.length) {
                    starts.add(j)
                }
                i = j
            } else {
                i++
            }
        }
        return starts.toIntArray()
    }

    /**
     * 在每个段首插入 [INDENT_CELLS] 个空白方，并同步平移双向映射与光标。
     *
     * 段首字符的 `textToBraillePositions` 正好指向插入点之后，所以按
     * “字符下标 >= 段首”整体后移。[indentFirst] 为 true 时把下标 0 也
     * 当作段首（公众号这类“每段一个节点”的正文整块就是一段）。
     */
    fun apply(
        text: CharSequence?,
        translation: TranslationResult,
        indentFirst: Boolean = false
    ): TranslationResult {
        val starts = when {
            text == null -> IntArray(0)
            !indentFirst -> findStarts(text)
            // 整块内容就是一段正文（公众号每段一个节点）时，下标 0 也是段首。
            else -> intArrayOf(0) + findStarts(text)
        }
        if (starts.isEmpty()) {
            return translation
        }
        val cells = translation.cells
        val textToCell = translation.textToBraillePositions
        val cellToText = translation.brailleToTextPositions

        // 段首 -> 插入点（格坐标）。多个段首落在同一格时只插一次。
        val insertionCells = ArrayList<Int>(starts.size)
        val insertionTextStarts = ArrayList<Int>(starts.size)
        for (start in starts) {
            val cell = if (start < textToCell.size) textToCell[start] else cells.size
            if (insertionCells.isEmpty() || insertionCells[insertionCells.size - 1] != cell) {
                insertionCells.add(cell)
                insertionTextStarts.add(start)
            }
        }
        if (insertionCells.isEmpty()) {
            return translation
        }

        val newCells = ByteArray(cells.size + insertionCells.size * INDENT_CELLS)
        val newCellToText = IntArray(newCells.size)

        var out = 0
        var insertionIndex = 0
        for (pos in 0..cells.size) {
            while (insertionIndex < insertionCells.size
                && insertionCells[insertionIndex] == pos
            ) {
                repeat(INDENT_CELLS) {
                    newCells[out] = 0
                    newCellToText[out] = insertionTextStarts[insertionIndex]
                    out++
                }
                insertionIndex++
            }
            if (pos == cells.size) {
                break
            }
            newCells[out] = cells[pos]
            newCellToText[out] = if (pos < cellToText.size) cellToText[pos] else 0
            out++
        }

        val newTextToCell = IntArray(textToCell.size)
        var shift = 0
        insertionIndex = 0
        for (i in textToCell.indices) {
            while (insertionIndex < insertionTextStarts.size
                && insertionTextStarts[insertionIndex] <= i
            ) {
                shift += INDENT_CELLS
                insertionIndex++
            }
            newTextToCell[i] = textToCell[i] + shift
        }

        var newCursor = translation.cursorPosition
        if (newCursor >= 0) {
            for (cell in insertionCells) {
                if (newCursor >= cell) {
                    newCursor += INDENT_CELLS
                }
            }
        }

        return TranslationResult(newCells, newTextToCell, newCellToText, newCursor)
    }
}
