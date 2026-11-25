/*
 * Copyright (C) 2012 Google Inc.
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

import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.utils.LogUtils

/**
 * 编辑器连接与内容回显：从 BrailleIME 拆出来的第二半。
 *
 * 点位路由、默认动作提交、光标与段落移动、系统按键转发、当前文本/选区/composing
 * 回显到点显器，以及失败反馈。光标钳制口径、选区与 holdings 回显范围原样保留；
 * 写成同包 internal 扩展函数。
 */

internal fun BrailleIME.route(position: Int, content: Content): Boolean {
    val ic = getCurrentInputConnection()
    val text: Spanned? = content.getSpanned()
    if (ic != null && text != null) {
        val spans = text.getSpans(position, position, BrailleIME.MarkingSpan::class.java)
        if (spans.size == 1) {
            if (spans[0] === BrailleIME.ACTION_LABEL_SPAN) {
                return emitFeedbackOnFailure(
                        sendDefaultAction(),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            } else if (spans[0] === BrailleIME.EDIT_TEXT_SPAN) {
                return emitFeedbackOnFailure(
                        setCursor(ic,
                                position - text.getSpanStart(BrailleIME.EDIT_TEXT_SPAN)),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            }
        } else if (spans.isEmpty()) {
            // Most likely, the user clicked on the label/hint part of the
            // content.
            emitFeedback(FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
            return true
        } else if (spans.size > 1) {
            LogUtils.log(this, Log.ERROR,
                    "Conflicting spans in Braille IME")
        }
    }
    emitFeedback(FeedbackManager.TYPE_COMMAND_FAILED)
    return true
}

internal fun BrailleIME.sendDefaultAction(): Boolean {
    if (!allowsDefaultAction()) {
        return false
    }
    val ei = getCurrentInputEditorInfo() ?: return false
    val ic = getCurrentInputConnection() ?: return false

    cancelComposingText()
    val actionId = ei.actionId
    return if (actionId != 0) {
        ic.performEditorAction(actionId)
    } else {
        sendDefaultEditorAction(false)
    }
}

internal fun BrailleIME.moveCursor(direction: Int, granularity: Int): Boolean {
    when (granularity) {
        AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER -> {
            val keyCode = if (direction == BrailleIME.DIRECTION_BACKWARD)
                KeyEvent.KEYCODE_DPAD_LEFT
            else
                KeyEvent.KEYCODE_DPAD_RIGHT
            return sendAndroidKey(keyCode)
        }
        AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH -> {
            if (!isMultiLineField()) {
                return false
            }
            val newPos = if (direction == BrailleIME.DIRECTION_BACKWARD)
                findParagraphBreakBackward()
            else
                findParagraphBreakForward()
            // newPos == the length means having the insertion point after
            // the last character, so the below comparison is correct.
            if (newPos < 0 || newPos > mCurrentText.length) {
                return false
            }
            return setCursor(getCurrentInputConnection(), newPos)
        }
    }
    return false
}

/**
 * Attempts to send down and up key events for a raw `keyCode`
 * through an input connection.
 */
internal fun BrailleIME.sendAndroidKey(keyCode: Int): Boolean {
    // DEL / ENTER 交给统一引擎（EditBuffer 按格删 / 上屏并回车）；
    // 其它系统按键前先落定 composing，与屏幕键盘 performTextAction 同语义。
    val command = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> BrailleInputEvent.CMD_KEY_DEL
        KeyEvent.KEYCODE_ENTER -> BrailleInputEvent.CMD_KEY_ENTER
        else -> BrailleInputEvent.CMD_NONE
    }
    if (command != BrailleInputEvent.CMD_NONE) {
        val routed = onHardwareInputEvent(command, 0)
        if (routed != null) {
            return routed
        }
    }
    cancelComposingText()
    return emitFeedbackOnFailure(
            sendAndroidKeyInternal(keyCode),
            FeedbackManager.TYPE_COMMAND_FAILED)
}

private fun BrailleIME.setCursor(ic: InputConnection, pos: Int): Boolean {
    val textLen = mCurrentText.length
    val newPos = if (pos < 0) 0 else if (pos <= textLen) pos else textLen
    val off = mExtractedText?.startOffset ?: 0
    val cursor = off + newPos
    return ic.setSelection(cursor, cursor)
}

private fun BrailleIME.sendAndroidKeyInternal(keyCode: Int): Boolean {
    LogUtils.log(this, Log.VERBOSE, "sendAndroidKey: %d", keyCode)
    val ic = getCurrentInputConnection() ?: return false
    val eventTime = SystemClock.uptimeMillis()
    if (!ic.sendKeyEvent(KeyEvent(eventTime, eventTime,
                    KeyEvent.ACTION_DOWN, keyCode, 0 /*repeat*/))) {
        return false
    }
    return ic.sendKeyEvent(
            KeyEvent(eventTime, eventTime,
                    KeyEvent.ACTION_UP, keyCode, 0 /*repeat*/))
}

private fun BrailleIME.findParagraphBreakBackward(): Int {
    if (mSelectionStart <= 0) {
        return -1
    }
    return mCurrentText.lastIndexOf("\n", mSelectionStart - 2) + 1
}

private fun BrailleIME.findParagraphBreakForward(): Int {
    if (mSelectionEnd >= mCurrentText.length) {
        return -1
    }
    val index = mCurrentText.indexOf("\n", mSelectionEnd)
    return if (index >= 0 && index < mCurrentText.length) {
        index + 1
    } else {
        mCurrentText.length
    }
}

internal fun BrailleIME.updateDisplay() {
    if (mExtractedText == null) {
        return
    }
    val displayManager = getCurrentDisplayManager() ?: return
    val ei = getCurrentInputEditorInfo()
    if (ei == null) {
        LogUtils.log(this, Log.WARN, "No input editor info")
        return
    }
    var label: CharSequence? = ei.label
    var hint: CharSequence? = ei.hintText
    if (TextUtils.isEmpty(label)) {
        label = hint
        hint = null
    }
    val text = SpannableStringBuilder()
    if (!TextUtils.isEmpty(label)) {
        text.append(label)
        text.append(getString(R.string.ime_label_separator))
    }
    val editStart = text.length
    text.append(mCurrentText)
    addMarkingSpan(text, BrailleIME.EDIT_TEXT_SPAN, editStart)
    val actionLabel = getActionLabel()
    if (actionLabel != null) {
        text.append(" [")
        text.append(actionLabel)
        text.append("]")
        addMarkingSpan(text, BrailleIME.ACTION_LABEL_SPAN,
                text.length - (actionLabel.length + 2))
    }
    DisplaySpans.addSelection(text,
            editStart + mSelectionStart, editStart + mSelectionEnd)
    if (0 <= mComposingStart && mComposingStart < mComposingEnd
            && mComposingEnd <= mCurrentText.length) {
        // 回显数据源从旧的 mComposingBraille 改成统一引擎的 holdings，
        // 否则点显器上看不到正在输入的盲文格。
        unifiedBraille?.holdings()?.let { cells ->
            if (cells.position() > 0) {
                DisplaySpans.addBraille(text,
                        editStart + mComposingStart, editStart + mComposingEnd,
                        cells)
            }
        }
    }
    displayManager.setContent(
            Content(text)
                    .setPanStrategy(Content.PAN_CURSOR)
                    .setEditable(true)
                    .setSplitParagraphs(isMultiLineField()))
}

internal fun BrailleIME.updateCurrentText() {
    val extracted = mExtractedText
    if (extracted == null) {
        mCurrentText.setLength(0)
        mSelectionStart = 0
        mSelectionEnd = 0
        return
    }
    if (extracted.text != null) {
        val len = mCurrentText.length
        if (extracted.partialStartOffset < 0) {
            // Complete update.
            mCurrentText.replace(0, len, extracted.text.toString())
        } else {
            val start = Math.min(extracted.partialStartOffset, len)
            val end = Math.min(extracted.partialEndOffset, len)
            mCurrentText.replace(start, end,
                    extracted.text.toString())
        }
    }

    // Update selection, keeping it within the text range even if the
    // client messed up.
    val len = mCurrentText.length
    var start = extracted.selectionStart
    start = if (start < 0) 0 else if (start > len) len else start
    var end = extracted.selectionEnd
    end = if (end < 0) 0 else if (end > len) len else end
    mSelectionStart = start
    mSelectionEnd = end
}

private fun BrailleIME.getActionLabel(): CharSequence? {
    val ei = getCurrentInputEditorInfo() ?: return null
    if (!allowsDefaultAction()) {
        // The edit field asks us to not show this inline in the
        // editor.
        return null
    }
    if (ei.actionLabel != null) {
        return ei.actionLabel
    }
    return getTextForImeAction(ei.imeOptions)
}

private fun BrailleIME.allowsDefaultAction(): Boolean {
    val ei = getCurrentInputEditorInfo()
    return ei != null &&
            (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
}

private fun BrailleIME.isMultiLineField(): Boolean {
    val ei = getCurrentInputEditorInfo() ?: return false
    val type = ei.inputType
    val mask = EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
            EditorInfo.TYPE_TEXT_FLAG_IME_MULTI_LINE
    // Consider this a multiline field if it is multiline in the main
    // text field, and not multiline only in the ime fullscreen mode.
    return (type and mask) == EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
}

private fun BrailleIME.addMarkingSpan(spannable: Spannable, span: BrailleIME.MarkingSpan,
                               position: Int) {
    if (position < spannable.length) {
        spannable.setSpan(span, position, spannable.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

internal fun BrailleIME.emitFeedback(type: Int) {
    getCurrentFeedbackManager()?.emitFeedback(type)
}

internal fun BrailleIME.emitFeedbackOnFailure(result: Boolean, type: Int): Boolean {
    if (!result) {
        emitFeedback(type)
    }
    return true
}
