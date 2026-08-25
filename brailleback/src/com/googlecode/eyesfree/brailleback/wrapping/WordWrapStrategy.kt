/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.wrapping

/**
 * A wrapping strategy where lines can break wherever there is an empty braille
 * cell. Empty braille cells are marked as "removable" break points, so they
 * may be elided if they occur at the end of the display line.
 */
class WordWrapStrategy : WrapStrategy() {

    override fun calculateBreakPoints() {
        val translation = mTranslation ?: return
        val cells = translation.getCells()
        var lastCellEmpty = false
        for (i in cells.indices) {
            val currentCellEmpty = cells[i] == 0.toByte()
            if (currentCellEmpty) {
                mBreakPoints.append(i, REMOVABLE_BREAK_POINT)
            } else if (lastCellEmpty) {
                mBreakPoints.append(i, UNREMOVABLE_BREAK_POINT)
            }
            lastCellEmpty = currentCellEmpty
        }
    }
}
