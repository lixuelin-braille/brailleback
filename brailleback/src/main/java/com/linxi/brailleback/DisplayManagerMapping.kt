/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linxi.brailleback

import android.text.Spanned
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.translate.TranslationResult
import java.util.Arrays

/**
 * 文本 ↔ 点位位置映射、平移定位匹配与光标位查找：从 [DisplayManager] 拆出的映射层。
 *
 * 这些函数只读写翻译结果的位置表与内容里的节点 Span，不碰写入通道、定时器与
 * 刷新闸门；写成同包 internal 扩展函数，方法体与拆分前逐字一致。被扩展方需要访问的
 * 字段在 [DisplayManager] 里由 private 放宽为 internal（同模块可见）。
 */

/** Returns braille character index of a text character index. May return -1. */
internal fun DisplayManager.textToDisplayPosition(
    translationResult: TranslationResult, cursorPosition: Int, textPosition: Int
): Int {
    if (textPosition == cursorPosition) {
        return translationResult.cursorPosition // May return -1?
    }
    val posMap = translationResult.textToBraillePositions // May include -1?
    // Any position past-the-end of the position map maps to the
    // corresponding past-the-end position in the braille.
    if (textPosition >= posMap.size) {
        return translationResult.brailleToTextPositions.size
    }
    return posMap[textPosition]
}

internal fun DisplayManager.displayToTextPosition(
    translationResult: TranslationResult,
    cursorPosition: Int,
    displayPosition: Int
): Int {
    if (displayPosition == translationResult.cursorPosition) {
        return cursorPosition
    }
    val posMap = translationResult.brailleToTextPositions
    // Any position past-the-end of the position map maps to the
    // corresponding past-the-end position in the braille.
    if (displayPosition >= posMap.size) {
        return translationResult.textToBraillePositions.size
    }
    return posMap[displayPosition]
}

internal fun DisplayManager.findMatchingPanPosition(
    oldContent: Content, newContent: Content,
    oldTranslationResult: TranslationResult,
    newTranslationResult: TranslationResult,
    oldDisplayPosition: Int
): Int {
    val oldSpanned = oldContent.getSpanned()
    val newSpanned = newContent.getSpanned()
    if (oldSpanned == null || newSpanned == null) {
        return -1
    }
    // Map the current display start and past-the-end positions
    // to the corresponding input positions.
    val oldTextStart = displayToTextPosition(oldTranslationResult,
            -1 /* cursorPosition */, oldDisplayPosition)
    val oldTextEnd = displayToTextPosition(oldTranslationResult,
            -1 /* cursorPosition */, oldDisplayPosition + getNumTextCells())
    // displayToTextPosition() can legitimately return -1 (it returns the
    // passed-in cursorPosition, which is -1 here, and the position maps
    // themselves may contain -1).  Feeding -1 into getSpans() would query a
    // bogus range, so bail out instead.
    if (oldTextStart < 0 || oldTextEnd < 0) {
        return -1
    }
    // Find the nodes that overlap with the display.
    val displayedNodes: Array<AccessibilityNodeInfoCompat> =
            oldSpanned.getSpans(oldTextStart, oldTextEnd,
                    AccessibilityNodeInfoCompat::class.java)
    Arrays.sort(displayedNodes,
            ByDistanceComparator(oldSpanned, oldTextStart))
    // Find corresponding node in new content.
    for (oldNode in displayedNodes) {
        val newNode = DisplaySpans.getEqualSpan(newSpanned, oldNode)
                as? AccessibilityNodeInfoCompat ?: continue
        val oldDisplayStart = textToDisplayPosition(oldTranslationResult,
                -1 /* cursorPosition */, oldSpanned.getSpanStart(oldNode))
        val newDisplayStart = textToDisplayPosition(newTranslationResult,
                -1 /* cursorPosition */, newSpanned.getSpanStart(newNode))
        // textToDisplayPosition() may return -1 (unmappable position: it
        // returns the cursor position, which is -1 here, and the position
        // maps may contain -1).  Subtracting those would yield a bogus --
        // possibly negative -- display position that later indexes into the
        // cell array.  Skip this node and try the next one; if every node
        // is unmappable we fall through to "return -1" below.
        if (oldDisplayStart < 0 || newDisplayStart < 0) {
            continue
        }
        // Offset position according to diff in node position.
        val newDisplayPosition =
                oldDisplayPosition + (newDisplayStart - oldDisplayStart)
        return newDisplayPosition
    }
    return -1
}

internal class ByDistanceComparator(
    private val spanned: Spanned,
    private val start: Int
) : Comparator<AccessibilityNodeInfoCompat> {
    override fun compare(
        a: AccessibilityNodeInfoCompat,
        b: AccessibilityNodeInfoCompat
    ): Int {
        val aStart = spanned.getSpanStart(a)
        val bStart = spanned.getSpanStart(b)
        val aDist = Math.abs(start - aStart)
        val bDist = Math.abs(start - bStart)
        if (aDist != bDist) {
            return aDist - bDist
        }
        // They are on the same distance, compare by length.
        val aLength = aStart + spanned.getSpanEnd(a)
        val bLength = bStart + spanned.getSpanEnd(b)
        return aLength - bLength
    }
}

internal fun DisplayManager.findCursorPosition(content: Content): Int {
    val spanned = content.getSpanned() ?: return -1
    val selectionSpans =
            spanned.getSpans(0, spanned.length,
                    DisplaySpans.SelectionSpan::class.java)
    if (selectionSpans.isNotEmpty()) {
        return spanned.getSpanStart(selectionSpans[0])
    }
    val focusSpans =
            spanned.getSpans(0, spanned.length,
                    DisplaySpans.FocusSpan::class.java)
    if (focusSpans.isNotEmpty()) {
        return spanned.getSpanStart(focusSpans[0])
    }
    return -1
}

internal fun DisplayManager.uncontractBrailleAtCursor(content: Content): Boolean {
    if (content.getContractionMode() == Content.CONTRACT_ALWAYS_ALLOW) {
        return false
    }
    val spanned = content.getSpanned() ?: return false
    val selectionSpans =
            spanned.getSpans(0, spanned.length,
                    DisplaySpans.SelectionSpan::class.java)
    return selectionSpans.isNotEmpty()
}

/**
 * Returns the size of the connected display, or `1` if
 * no display is connected.
 */
internal fun DisplayManager.getNumTextCells(): Int {
    if (!connected) {
        return 1
    }
    return display?.displayProperties?.numTextCells ?: 1
}
