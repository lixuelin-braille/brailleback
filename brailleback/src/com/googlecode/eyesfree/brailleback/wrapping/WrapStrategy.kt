/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文显示换行/平移策略（Kotlin 版）。
 * 包含国标排版规则：避头点 / 避尾点（行首不得留标点、行尾不得留开括号）。
 */

package com.googlecode.eyesfree.brailleback.wrapping

import android.util.Log
import android.util.SparseIntArray
import com.googlecode.eyesfree.braille.translate.TranslationResult
import com.googlecode.eyesfree.brailleback.DisplayManager.Content

/**
 * Handles the presentation of braille content that doesn't completely fit on
 * the braille display.
 *
 * Indicates a position in braille that marks an acceptable place to start a
 * new line, while indicating that the cell at the given position should not be
 * removed. For example, you can mark the first braille position after a hyphen
 * as an unremovable break point, since you wouldn't want it to be truncated.
 *
 * Note: in the current line-breaking algorithm, there is an implicit
 * unremovable break point placed on the first braille position after a
 * contiguous string of removable break points. It is acceptable, but not
 * necessary, to explicitly mark the first braille position after a string of
 * removable break points as an unremovable break point.
 */
abstract class WrapStrategy {

    protected var mContent: Content? = null
    protected var mTranslation: TranslationResult? = null

    /**
     * An array where the keys are translated positions that should always
     * correspond to the left-most position on the braille display if at all
     * included.  This is used to split the output at line breaks.  The values
     * are not used and currently set to [SPLIT_POINT].
     */
    protected val mSplitPoints = SparseIntArray()

    /**
     * An array where the keys are positions in braille at which we prefer new
     * lines to begin. The values are either [UNREMOVABLE_BREAK_POINT] or
     * [REMOVABLE_BREAK_POINT].
     */
    protected val mBreakPoints = SparseIntArray()

    /**
     * The precomputed line breaks that are used during panning. Positions
     * marked with [LINE_BREAK] mark the beginning of a line; thus the line
     * break occurs right before such positions. The offset from one line break
     * to another is not necessarily less than or equal to the width of the
     * display; the line breaking algorithm may arrange the line breaks such
     * that removable break points are truncated at the end of a line.
     */
    private val mLineBreaks = SparseIntArray()

    private var mDisplayStart: Int = 0
    private var mDisplayEnd: Int = 0
    private var mDisplayWidth: Int = 0
    private var mIsValid: Boolean = false

    companion object {
        /**
         * Indicates a position in braille that marks an acceptable place to
         * start a new line, while indicating that the cell at the given
         * position should not be removed.
         */
        const val UNREMOVABLE_BREAK_POINT = 1

        /**
         * Indicates a position in braille that marks an acceptable place to
         * start a new line, while indicating that the cell at the given
         * position can be removed if it occurs at the beginning or end of a
         * line. For example, you can mark whitespace as a removable break
         * point because it is acceptable to elide whitespace in most contexts.
         */
        const val REMOVABLE_BREAK_POINT = 2

        /** Used in [mSplitPoints] to indicate a forced line break position. */
        const val SPLIT_POINT = 1

        /** Used in the computed array [mLineBreaks] to indicate line starts. */
        const val LINE_BREAK = 1
    }

    /**
     * Sets the current content context for the wrap strategy to calculate
     * panning positions. If any of these parameters change, you must call
     * [setContent] again; otherwise, the panning calculations done in methods
     * like [panTo] or [panLeft] will be incorrect.
     *
     * @param content The untranslated text content.
     * @param translation The translated braille content.
     * @param displayWidth The number of cells available on the display.
     */
    fun setContent(content: Content?, translation: TranslationResult?, displayWidth: Int) {
        if (content == null || translation == null || displayWidth <= 0
            || content.getText().isNullOrEmpty()) {
            mContent = null
            mTranslation = null
            mDisplayStart = 0
            mDisplayEnd = 0
            mDisplayWidth = 0
            mSplitPoints.clear()
            mBreakPoints.clear()
            mLineBreaks.clear()
            mIsValid = false
            return
        }

        mContent = content
        mTranslation = translation
        mDisplayStart = 0
        mDisplayEnd = 0
        mDisplayWidth = displayWidth

        mSplitPoints.clear()
        calculateSplitPoints()

        mBreakPoints.clear()
        calculateBreakPoints()

        mLineBreaks.clear()
        calculateLineBreaks(0)

        mIsValid = true
    }

    private fun calculateSplitPoints() {
        val content = mContent ?: return
        val translation = mTranslation ?: return
        if (!content.isSplitParagraphs()) {
            return
        }
        val text = content.getText() ?: return
        val textToCell = translation.textToBraillePositions
        val numCells = translation.cells.size
        for (i in 0 until text.length - 1) {
            if (text[i] == '\n') {
                val cell = if (i + 1 < textToCell.size) textToCell[i + 1] else numCells
                mSplitPoints.append(cell, SPLIT_POINT)
            }
        }
    }

    protected abstract fun calculateBreakPoints()

    private fun findPointIndex(points: SparseIntArray, displayPosition: Int): Int {
        var index = points.indexOfKey(displayPosition)
        if (index >= 0) {
            // Exact match.
            return index
        }
        // One's complement gives index where the element would be inserted
        // in sorted order.
        index = index.inv()
        return if (index > 0) index - 1 else -1
    }

    private fun findLeftLimit(points: SparseIntArray, end: Int): Int {
        val index = findPointIndex(points, end)
        if (index >= 0) {
            val limit = points.keyAt(index)
            if (limit < end) {
                return limit
            }
            if (index > 0) {
                return points.keyAt(index - 1)
            }
        }
        return 0
    }

    private fun findRightLimit(points: SparseIntArray, start: Int): Int {
        val index = findPointIndex(points, start) + 1
        if (index >= points.size()) {
            return mTranslation?.cells?.size ?: 0
        }
        return points.keyAt(index)
    }

    fun getDisplayStart(): Int {
        return Math.max(0, mDisplayStart)
    }

    fun getDisplayEnd(): Int {
        return Math.min(getDisplayStart() + mDisplayWidth, mDisplayEnd)
    }

    /**
     * Calculates the line breaks for the current content using the current
     * pivot point. The line breaks will occur such that the pivot point is
     * guaranteed to be at the beginning of some line.
     */
    private fun calculateLineBreaks(pivot: Int) {
        val translation = mTranslation ?: return
        // Add pivot as line break.
        mLineBreaks.append(pivot, LINE_BREAK)

        // Add line breaks after pivot.  Guard against a non-progressing line
        // end: the 避尾点/避头点 punctuation-avoidance can retreat the end back
        // to (or before) the start, which would spin this loop forever and
        // starve the display of updates.
        var current = pivot
        while (current < translation.cells.size) {
            val next = calculateDisplayEnd(current)
            current = if (next > current) next else current + 1
            if (current > translation.cells.size) {
                current = translation.cells.size
            }
            mLineBreaks.append(current, LINE_BREAK)
        }

        // Add line breaks before pivot.  Same progress guarantee backwards.
        current = pivot
        while (current > 0) {
            val next = calculateDisplayStart(current)
            current = if (next < current) next else current - 1
            if (current < 0) {
                current = 0
            }
            mLineBreaks.append(current, LINE_BREAK)
        }
    }

    /** Clamps the given position to the interval [0, length). */
    private fun clampPosition(position: Int): Int {
        val translation = mTranslation ?: return 0
        if (position <= 0) {
            return 0
        } else if (position >= translation.cells.size - 1) {
            return translation.cells.size - 1
        } else {
            return position
        }
    }

    /**
     * Pans the display to the indicated position.
     *
     * @param position The position (in braille cells) to which to pan.
     * @param fix If `true`, then the display will pan to `position` as if the
     *            user had manually panned there. If `false`, then the display
     *            will pan such that `position` becomes the leftmost cell.
     */
    fun panTo(position: Int, fix: Boolean) {
        if (!mIsValid) {
            return
        }
        val translation = mTranslation ?: return
        val pos = clampPosition(position)

        // If the position isn't one of the cached line breaks and we can't fix
        // the position, then we will need to recalculate all the line breaks
        // with the position as pivot.
        if (!fix && mLineBreaks.indexOfKey(pos) < 0) {
            mLineBreaks.clear()
            calculateLineBreaks(pos)
        }

        val index = findPointIndex(mLineBreaks, pos)
        if (index >= mLineBreaks.size() - 1) {
            // We need index, index + 1 to be valid.
            return
        }

        mDisplayStart = mLineBreaks.keyAt(index)
        mDisplayEnd = mLineBreaks.keyAt(index + 1)
    }

    /**
     * Moves the display starting and ending positions to the left of the
     * current content.
     *
     * @return `true` if the display was panned, or `false` if it's at the left
     *         edge.
     */
    fun panLeft(): Boolean {
        if (!mIsValid) {
            return false
        }
        val index = mLineBreaks.indexOfKey(mDisplayStart)
        if (index <= 0 || index >= mLineBreaks.size()) {
            return false
        }
        mDisplayStart = mLineBreaks.keyAt(index - 1)
        mDisplayEnd = mLineBreaks.keyAt(index)
        return true
    }

    /**
     * Moves the display starting and ending positions to the right of the
     * current content.
     *
     * @return `true` if the display was panned, or `false` if it's at the
     *         right edge.
     */
    fun panRight(): Boolean {
        if (!mIsValid) {
            return false
        }
        val index = mLineBreaks.indexOfKey(mDisplayEnd)
        if (index < 0 || index >= mLineBreaks.size() - 1) {
            return false
        }
        mDisplayStart = mLineBreaks.keyAt(index)
        mDisplayEnd = mLineBreaks.keyAt(index + 1)
        return true
    }

    private fun calculateDisplayEnd(start: Int): Int {
        val translation = mTranslation ?: return start
        val displayLimit = start + mDisplayWidth

        val splitLimit = findRightLimit(mSplitPoints, start)
        if (splitLimit <= displayLimit) {
            return splitLimit
        }

        val breakLimit = findLeftLimit(mBreakPoints, displayLimit + 1)
        if (breakLimit > start) {
            // Extend the breakLimit until it reaches a character that is not a
            // removable break (whitespace padding is OK at end).
            var limit = breakLimit
            while (limit < translation.cells.size
                && mBreakPoints.get(limit) == REMOVABLE_BREAK_POINT) {
                limit++
            }
            return clampLineEndToWidth(
                start,
                avoidOpeningPunctuationAtLineEnd(avoidPunctuationAtLineStart(limit)))
        }

        return clampLineEndToWidth(
            start,
            avoidOpeningPunctuationAtLineEnd(avoidPunctuationAtLineStart(displayLimit)))
    }

    /**
     * Keeps a computed line end inside the display width unless the extra
     * cells are elidable whitespace.
     *
     * 避头点（行首不得留标点）的做法是把标点收进上一行的行尾，这会让行尾
     * 越过显示宽度；而 [getDisplayEnd] 会按宽度截断、[panRight] 却跳到未
     * 截断的行尾，于是被推出宽度的标点永远不会被显示（内容丢失）。
     *
     * 行已排满时改用「回退断点」：让标点连同它前面的字符一起落到下一行。
     * 这样既不会丢内容，也保证下一行行首不是标点（国标排版：标点与前一
     * 字符一起下移），代价是该行少显示若干方。
     */
    private fun clampLineEndToWidth(start: Int, lineEnd: Int): Int {
        val translation = mTranslation ?: return lineEnd
        val numCells = translation.cells.size
        val displayLimit = Math.min(start + mDisplayWidth, numCells)
        if (lineEnd <= displayLimit) {
            return lineEnd
        }
        // 找出被推出显示范围的第一个闭标点；若只有空白被推出，
        // 属于可省略的尾随空白（removable break point），保持原值。
        var punct = displayLimit
        while (punct < lineEnd && !isCellClosingPunctuation(punct)) {
            punct++
        }
        if (punct >= lineEnd) {
            return lineEnd
        }
        // 回退到标点之前最近的非标点位置，使下一行行首是真实字符。
        var candidate = punct - 1
        while (candidate > start && isCellClosingPunctuation(candidate)) {
            candidate--
        }
        // 吸收回退后落在行尾的可省略空白。
        while (candidate > start
            && mBreakPoints.get(candidate - 1) == REMOVABLE_BREAK_POINT) {
            candidate--
        }
        if (candidate <= start) {
            // 极端情况（整行几乎全是标点）：保证算法推进，宁可让下一行
            // 以标点开头，也不能不前进或吞掉内容。
            candidate = Math.min(start + 1, numCells)
        }
        return candidate
    }

    private fun calculateDisplayStart(end: Int): Int {
        val translation = mTranslation ?: return end
        // Move end backwards until the character immediately preceding it is
        // not a removable break. In effect, we're "cancelling out" any
        // whitespace padding that occurred at the end.
        var e = end
        while (e > 0 && mBreakPoints.get(e - 1) == REMOVABLE_BREAK_POINT) {
            e--
        }
        val displayLimit = e - mDisplayWidth

        val splitLimit = findLeftLimit(mSplitPoints, e)
        if (splitLimit >= displayLimit) {
            return splitLimit
        }

        val breakLimit = findRightLimit(mBreakPoints, displayLimit - 1)
        if (breakLimit < e) {
            // Extend the breakLimit until it reaches a character that is not a
            // removable break (whitespace padding is not OK at the start).
            var limit = breakLimit
            while (limit < e && mBreakPoints.get(limit) == REMOVABLE_BREAK_POINT) {
                limit++
            }
            return avoidOpeningPunctuationAtLineEnd(
                avoidPunctuationAtLineStart(limit))
        }

        return avoidOpeningPunctuationAtLineEnd(
            avoidPunctuationAtLineStart(displayLimit))
    }

    /**
     * Avoids placing an opening punctuation at the END of a braille line
     * ("tail punctuation" / 避尾点 rule). If the cell just before `lineEnd`
     * maps to an opening bracket/quote, that character is pushed to the
     * beginning of the next line so a line never ends with an opening bracket.
     * (国标排版：行尾不得留开括号)
     */
    private fun avoidOpeningPunctuationAtLineEnd(lineEnd: Int): Int {
        val translation = mTranslation ?: return lineEnd
        if (lineEnd <= 1 || lineEnd > translation.cells.size) {
            return lineEnd
        }
        val btt = translation.brailleToTextPositions
        if (btt == null) {
            return lineEnd
        }
        val content = mContent ?: return lineEnd
        val text = content.getText()
        if (text == null) {
            return lineEnd
        }
        var pos = lineEnd - 1
        var retreat = 0
        val maxRetreat = mDisplayWidth
        while (pos >= 0 && retreat < maxRetreat) {
            val textPos = btt[pos]
            if (textPos < 0 || textPos >= text.length) {
                break
            }
            val c = text[textPos]
            if (!isOpeningPunctuation(c)) {
                break
            }
            val start = pos
            while (pos >= 0 && btt[pos] == textPos) {
                pos--
            }
            retreat += (start - pos)
        }
        if (retreat == 0) {
            return lineEnd
        }
        val result = pos + 1
        // 保护：若开括号已在行首（整行只有一个开括号），不再回退，避免死循环
        if (result <= 0) {
            return lineEnd
        }
        Log.d("TailPunct", "avoided line-end punct: cell "
            + lineEnd + " -> " + result + " retreatedCells=" + retreat)
        return result
    }

    /** Returns `true` if the character is an opening bracket/quote that should
     * not appear at the END of a braille line. */
    private fun isOpeningPunctuation(c: Char): Boolean {
        return when (c) {
            '（', '【', '［', '《', '〈', '〔',
            '{', '[', '(', '<',
            '“', '‘', '「', '『' -> true
            else -> false
        }
    }

    /**
     * Avoids placing closing punctuation at the start of a braille line
     * ("head punctuation" / 避头点 rule). If the cell at `lineStart` maps to a
     * closing punctuation character, that character (and any following closing
     * punctuation) is pushed to the end of the previous line so that a line
     * never begins with a punctuation mark.
     */
    private fun avoidPunctuationAtLineStart(lineStart: Int): Int {
        val translation = mTranslation ?: return lineStart
        if (lineStart <= 0 || lineStart >= translation.cells.size) {
            return lineStart
        }
        val btt = translation.brailleToTextPositions
        if (btt == null) {
            return lineStart
        }
        val content = mContent ?: return lineStart
        val text = content.getText()
        if (text == null) {
            return lineStart
        }
        var pos = lineStart
        var skipped = 0
        val maxSkip = mDisplayWidth
        while (pos < translation.cells.size && skipped < maxSkip) {
            val textPos = btt[pos]
            if (textPos < 0 || textPos >= text.length) {
                break
            }
            val c = text[textPos]
            if (!isClosingPunctuation(c)) {
                break
            }
            // Skip all cells that belong to this punctuation character.
            var next = pos
            while (next < translation.cells.size && btt[next] == textPos) {
                next++
            }
            skipped += (next - pos)
            pos = next
        }
        if (skipped == 0) {
            return lineStart
        }
        // Also skip any removable whitespace after the punctuation so the
        // new line does not begin with a blank cell.
        while (pos < translation.cells.size
            && mBreakPoints.get(pos) == REMOVABLE_BREAK_POINT) {
            pos++
        }
        Log.d("HeadPunct", "avoided line-start punct: cell "
            + lineStart + " -> " + pos + " skippedCells=" + skipped)
        return pos
    }

    /** Returns `true` if the given braille cell maps to a closing punctuation. */
    private fun isCellClosingPunctuation(cell: Int): Boolean {
        val translation = mTranslation ?: return false
        val btt = translation.brailleToTextPositions ?: return false
        val text = mContent?.getText() ?: return false
        if (cell < 0 || cell >= translation.cells.size) {
            return false
        }
        val textPos = btt[cell]
        if (textPos < 0 || textPos >= text.length) {
            return false
        }
        return isClosingPunctuation(text[textPos])
    }

    /** Returns `true` if the character is a closing punctuation mark that
     * should not appear at the start of a braille line. */
    private fun isClosingPunctuation(c: Char): Boolean {
        return when (c) {
            '，', '。', '！', '？', '；', '：',
            '、', '）', '】', '》', '〉', '”',
            '’', '…', '·',
            ',', '.', '!', '?', ';', ':',
            ')', ']', '}', '>', '"', '\'' -> true
            else -> false
        }
    }
}
