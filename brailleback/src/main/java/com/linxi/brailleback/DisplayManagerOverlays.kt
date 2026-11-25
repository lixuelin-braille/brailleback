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

/**
 * 选区 / 焦点覆盖点叠加与闪烁判定：从 [DisplayManager] 拆出的覆盖层。
 *
 * 覆盖点只改写叠加帧的点位字节，不参与翻译、位置换算与整屏下发；写成同包
 * internal 扩展函数，方法体与拆分前逐字一致。被扩展方需要访问的点位数组、
 * 选区格集合与光标点开关在 [DisplayManager] 里由 private 放宽为 internal。
 */

/** Dot pattern used to overlay characters under a selection. */
// TODO: Make customizable.
private const val SELECTION_DOTS = 0xC0
/** Dot pattern used to overlay characters in a focused element. */
// TODO: Make customizable.
private const val FOCUS_DOTS = 0xC0

/**
 * Marks selection spans in the overlaid braille, and returns the position
 * in braille where the first selection begins. If there are no selection
 * spans, returns -1.
 */
internal fun DisplayManager.markSelection(spanned: Spanned): Int {
    val spans =
            spanned.getSpans(0, spanned.length,
                    DisplaySpans.SelectionSpan::class.java)
    var selectionStart = -1
    for (span in spans) {
        val start =
            textToDisplayPosition(
                currentTranslationResult, cursorPositionToTranslate, spanned.getSpanStart(span))
        var end =
            textToDisplayPosition(
                currentTranslationResult, cursorPositionToTranslate, spanned.getSpanEnd(span))
        if (start == -1 || end == -1) {
            return -1
        }
        if (start == end) {
            end = start + 1
        }
        if (end > brailleContent.size) {
            extendContentForCursor()
        }
        if (cursorDotsEnabled) {
            copyOverlaidContent()
            var i = start
            while (i < end && i < overlaidBrailleContent.size) {
                overlaidBrailleContent[i] =
                    (overlaidBrailleContent[i].toInt() or SELECTION_DOTS).toByte()
                selectionCells.add(i)
                ++i
            }
        }
        if (selectionStart == -1) {
            selectionStart = start
        }
    }
    return selectionStart
}

/**
 * Makes sure that the overlaid content has its own copy.  Call before
 * adding overlay dots.
 */
internal fun DisplayManager.copyOverlaidContent() {
    if (overlaidBrailleContent === brailleContent) {
        overlaidBrailleContent = brailleContent.clone()
    }
}

internal fun DisplayManager.extendContentForCursor() {
    brailleContent = brailleContent.copyOf(brailleContent.size + 1)
    // Always create a new copy of the overlaid content because there will
    // be a cursor, so we will need a copy anyway.
    overlaidBrailleContent =
        overlaidBrailleContent.copyOf(overlaidBrailleContent.size + 1)
}

/**
 * Marks focus spans in the overlaid braille, and returns the position in
 * braille where the first focus begins. If there are no focus spans,
 * returns -1.
 */
internal fun DisplayManager.markFocus(spanned: Spanned): Int {
    val spans =
            spanned.getSpans(0, spanned.length,
                    DisplaySpans.FocusSpan::class.java)
    var focusStart = -1
    for (span in spans) {
        val start =
            textToDisplayPosition(
                currentTranslationResult, cursorPositionToTranslate, spanned.getSpanStart(span))
        if (start >= 0 && start < overlaidBrailleContent.size) {
            if (cursorDotsEnabled) {
                copyOverlaidContent()
                overlaidBrailleContent[start] =
                    (overlaidBrailleContent[start].toInt() or FOCUS_DOTS).toByte()
            }
            if (focusStart == -1) {
                focusStart = start
            }
        }
    }
    return focusStart
}

/**
 * Marks the selection or focus cursor (in that priority), and returns the
 * position in braille of the selection or focus cursor if one exists. If no
 * selection or focus cursor exists, then returns -1.
 */
internal fun DisplayManager.markCursor(): Int {
    // 每次重算覆盖点前先清空：选区被取消后残留的格会让屏上白白多闪
    selectionCells.clear()
    val spanned = currentContent.getSpanned() ?: return -1
    val selectionPosition = markSelection(spanned)
    if (selectionPosition != -1) {
        return selectionPosition
    }

    val focusPosition = markFocus(spanned)
    if (focusPosition != -1) {
        return focusPosition
    }

    return -1
}

/**
 * 显示区里是否有需要明灭的点（选区/光标）。
 *
 * 判定只看选区，不看焦点：焦点标记同样会改写叠加内容，按「字节有没有差异」
 * 判断的话，只要屏上有焦点就会无限闪（详见 [BlinkPolicy]）。
 */
internal fun DisplayManager.blinkNeeded(): Boolean =
    cursorDotsEnabled && BlinkPolicy.shouldBlink(
        selectionCells, wrapStrategy.getDisplayStart(), wrapStrategy.getDisplayEnd())
