/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.wrapping

import com.googlecode.eyesfree.braille.translate.TranslationResult

/**
 * 标题排版：按点显器宽度居中。
 *
 * 点显器没有排版引擎，居中只能靠左侧补空白方。标题比显示区窄时补
 * `(显示方数 - 标题方数) / 2` 方；标题放不下时保持原样，交给
 * [WrapStrategy] 正常换行/平移。
 */
object HeadingLayout {

    fun center(translation: TranslationResult, displayWidth: Int): TranslationResult {
        if (displayWidth <= 0) {
            return translation
        }
        val cells = translation.cells
        if (cells.isEmpty()) {
            return translation
        }
        val pad = (displayWidth - cells.size) / 2
        if (pad <= 0) {
            return translation
        }

        val textToCell = translation.textToBraillePositions
        val cellToText = translation.brailleToTextPositions

        val newCells = ByteArray(cells.size + pad)
        cells.copyInto(newCells, destinationOffset = pad)

        val newTextToCell = IntArray(textToCell.size) { textToCell[it] + pad }
        val newCellToText = IntArray(newCells.size)
        for (i in 0 until pad) {
            newCellToText[i] = 0
        }
        for (i in cells.indices) {
            newCellToText[pad + i] = if (i < cellToText.size) cellToText[i] else 0
        }

        val newCursor = if (translation.cursorPosition >= 0) {
            translation.cursorPosition + pad
        } else {
            -1
        }

        return TranslationResult(newCells, newTextToCell, newCellToText, newCursor)
    }
}
