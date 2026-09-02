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

package com.googlecode.eyesfree.brailleback

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.braille.translate.TableInfo
import com.googlecode.eyesfree.brailleback.BrailleBackService.DOT7
import com.googlecode.eyesfree.brailleback.BrailleBackService.DOT8
import com.googlecode.eyesfree.utils.LogUtils
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

/**
 * Input method service for keys from the connected braille display.
 */
class BrailleIME : InputMethodService {

    /**
     * Interface between the IME and a "host" in the accessibility service.
     */
    interface Host {
        /**
         * Returns a translator for input, if available.
         * The IME will ignore text input if no translator is available.
         */
        fun getBrailleTranslator(): BrailleTranslator?

        /**
         * Returns a display manager for output, if available.
         * The IME will not generate output if no display manager is available.
         */
        fun getDisplayManager(): DisplayManager?

        /**
         * Returns a feedback manager, if available.
         * No feedback will be emitted if no feedback manager is available.
         */
        fun getFeedbackManager(): FeedbackManager

        /**
         * Called when the IME has been created by the system.
         * @see InputMethodService.onCreate
         */
        fun onCreateIME()

        /**
         * Called when the IME is being destroyed by the system.
         * @see InputMethodService.onDestroy
         */
        fun onDestroyIME()

        /**
         * Called when the IME has associated with an input connection.
         * @see InputMethodService.onBindInput
         */
        fun onBindInput()

        /**
         * Called when the IME has disassociated from an input connection.
         * @see InputMethodService.onUnbindInput
         */
        fun onUnbindInput()

        /**
         * Called when the IME has started an input session.
         */
        fun onStartInput(attribute: EditorInfo, restarting: Boolean)

        /**
         * Called when the IME has finished an input session.
         * @see InputMethodService.onFinishInput
         */
        fun onFinishInput()

        /**
         * Called when the IME opens the input view.
         */
        fun onStartInputView(info: EditorInfo, restarting: Boolean)

        /**
         * Called when the IME closes the input view.
         */
        fun onFinishInputView(finishingInput: Boolean)
    }

    private val mHost: Host? // for testing
    private lateinit var mInputMethodManager: InputMethodManager

    private var mExtractedText: ExtractedText? = null
    private var mExtractedTextToken = 0
    /** Start of current selection, relative to the start of the extracted text. */
    private var mSelectionStart = 0
    /** End (inclusive) of current selection, relative to the start of the extracted text. */
    private var mSelectionEnd = 0
    /**
     * Start of the currently-composing braille word when typing using a
     * literary braille table.
     */
    private var mComposingStart = 0
    /**
     * End (exclusive) of the currently-composing braille word when typing
     * using a literary braille table. Should correspond to the current cursor
     * position.
     */
    private var mComposingEnd = 0
    /**
     * The text that is shown on the display. Might be only part of the full
     * edit field if it is larger than `MAX_REQUEST_CHARS`.
     */
    private val mCurrentText = StringBuilder()
    /**
     * Buffer storing the currently-composing braille dots for contracted
     * braille.
     */
    private val mComposingBraille = ByteBuffer.allocate(MAX_COMPOSE_DOTS)

    /** Constructor for general use. */
    constructor() : super() {
        mHost = null
        mComposingBraille.clear()
    }

    /** Constructor for testing. Allows using a non-global host object. */
    internal constructor(host: Host, baseContext: Context) : super() {
        mHost = host
        attachBaseContext(baseContext)
    }

    /* InputMethodService implementation */

    override fun onCreate() {
        super.onCreate()
        sInstance = WeakReference(this)
        mInputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        LogUtils.log(this, Log.VERBOSE, "Created Braille IME")

        getHost()?.onCreateIME()
    }

    override fun onDestroy() {
        super.onDestroy()
        // onFinishInput is not called when switching away from this IME
        // to another one, so clear the state here as well.
        mExtractedText = null
        updateCurrentText()
        mComposingBraille.clear()
        sInstance = null

        getHost()?.onDestroyIME()
    }

    override fun onBindInput() {
        super.onBindInput()

        getHost()?.onBindInput()
    }

    override fun onUnbindInput() {
        super.onUnbindInput()

        getHost()?.onUnbindInput()
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        LogUtils.log(this, Log.VERBOSE,
                "onStartInput: inputType: %x, imeOption: %x, " +
                        ", label: %s, hint: %s, package: %s, ",
                attribute.inputType, attribute.imeOptions, attribute.label,
                attribute.hintText, attribute.packageName)
        val ic = getCurrentInputConnection()
        if (ic != null) {
            val req = ExtractedTextRequest()
            req.token = ++mExtractedTextToken
            req.hintMaxChars = MAX_REQUEST_CHARS
            mExtractedText = ic.getExtractedText(req,
                    InputConnection.GET_EXTRACTED_TEXT_MONITOR)
        } else {
            mExtractedText = null
        }
        updateCurrentText()
        updateDisplay()
        mComposingBraille.clear()

        getHost()?.onStartInput(attribute, restarting)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        LogUtils.log(this, Log.VERBOSE, "onFinishInput")
        mExtractedText = null
        updateCurrentText()
        mComposingBraille.clear()

        getHost()?.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        LogUtils.log(this, Log.VERBOSE, "onStartInputView")

        getHost()?.onStartInputView(info, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        LogUtils.log(this, Log.VERBOSE, "onFinishInputView")

        getHost()?.onFinishInputView(finishingInput)
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onCreateInputView(): View {
        val inflater = getLayoutInflater()
        val inputView = inflater.inflate(R.layout.braille_ime, null)
        inputView.setOnClickListener { switchAwayFromThisIme() }
        inputView.setOnTouchListener { _: View, _: MotionEvent ->
            switchAwayFromThisIme()
            true
        }
        return inputView
    }

    override fun onUpdateExtractedText(token: Int, text: ExtractedText) {
        // The superclass only deals with fullscreen support, which we've
        // disabled, so don't call it here.
        if (mExtractedText == null || token != mExtractedTextToken) {
            return
        }
        mExtractedText = text
        updateCurrentText()
        updateDisplay()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int,
                                   newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        if (mExtractedText != null) {
            val off = mExtractedText!!.startOffset
            val len = mCurrentText.length
            var selStart = newSelStart - off
            var selEnd = newSelEnd - off
            selStart = if (selStart < 0) 0 else if (selStart > len) len else selStart
            selEnd = if (selEnd < 0) 0 else if (selEnd > len) len else selEnd
            mSelectionStart = selStart
            mSelectionEnd = selEnd
            mComposingStart = candidatesStart
            mComposingEnd = candidatesEnd

            // If we're composing but the selection isn't a single cursor at
            // the end of the composing text, then cancel composing.
            if ((candidatesEnd != selStart || selStart != selEnd)
                    && mComposingBraille.position() != 0) {
                cancelComposingText()
            }

            updateDisplay()
        }
    }

    /* Exposed for use by the host. */

    fun route(position: Int, content: DisplayManager.Content): Boolean {
        val ic = getCurrentInputConnection()
        val text: Spanned? = content.spanned
        if (ic != null && text != null) {
            val spans = text.getSpans(position, position, MarkingSpan::class.java)
            if (spans.size == 1) {
                if (spans[0] === ACTION_LABEL_SPAN) {
                    return emitFeedbackOnFailure(
                            sendDefaultAction(),
                            FeedbackManager.TYPE_COMMAND_FAILED)
                } else if (spans[0] === EDIT_TEXT_SPAN) {
                    return emitFeedbackOnFailure(
                            setCursor(ic,
                                    position - text.getSpanStart(EDIT_TEXT_SPAN)),
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

    fun sendDefaultAction(): Boolean {
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

    fun moveCursor(direction: Int, granularity: Int): Boolean {
        when (granularity) {
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER -> {
                val keyCode = if (direction == DIRECTION_BACKWARD)
                    KeyEvent.KEYCODE_DPAD_LEFT
                else
                    KeyEvent.KEYCODE_DPAD_RIGHT
                return sendAndroidKey(keyCode)
            }
            AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH -> {
                if (!isMultiLineField()) {
                    return false
                }
                val newPos = if (direction == DIRECTION_BACKWARD)
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
    fun sendAndroidKey(keyCode: Int): Boolean {
        if (mComposingBraille.position() > 0 && keyCode == KeyEvent.KEYCODE_DEL) {
            // Delete the last composing dot and update composing text.
            mComposingBraille.position(mComposingBraille.position() - 1)

            val translator = getCurrentBrailleTranslator()
            val ic = getCurrentInputConnection()
            if (translator == null || ic == null) {
                LogUtils.log(this, Log.WARN, "missing translator %s or IC %s",
                        translator, ic)
                return false
            }

            updateComposingText(translator, ic)
            return true
        }

        cancelComposingText()
        return emitFeedbackOnFailure(
                sendAndroidKeyInternal(keyCode),
                FeedbackManager.TYPE_COMMAND_FAILED)
    }

    fun handleBrailleKey(dots: Int): Boolean {
        val translator = getCurrentBrailleTranslator()
        val sixDot = translator != null && translator.tableInfo?.isEightDot == false
        return if (sixDot && dots == DOT7) {
            sendAndroidKey(KeyEvent.KEYCODE_DEL)
        } else if (sixDot && dots == DOT8) {
            sendAndroidKey(KeyEvent.KEYCODE_ENTER)
        } else {
            emitFeedbackOnFailure(
                    handleBrailleKeyInternal(dots),
                    FeedbackManager.TYPE_COMMAND_FAILED)
        }
    }

    fun updateDisplay() {
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
            text.append(": ") // TODO: Put in a resource.
        }
        val editStart = text.length
        text.append(mCurrentText)
        addMarkingSpan(text, EDIT_TEXT_SPAN, editStart)
        val actionLabel = getActionLabel()
        if (actionLabel != null) {
            text.append(" [")
            text.append(actionLabel)
            text.append("]")
            addMarkingSpan(text, ACTION_LABEL_SPAN,
                    text.length - (actionLabel.length + 2))
        }
        DisplaySpans.addSelection(text,
                editStart + mSelectionStart, editStart + mSelectionEnd)
        if (0 <= mComposingStart && mComposingStart < mComposingEnd
                && mComposingEnd <= mCurrentText.length) {
            DisplaySpans.addBraille(text,
                    editStart + mComposingStart, editStart + mComposingEnd,
                    mComposingBraille)
        }
        displayManager.setContent(
                DisplayManager.Content(text)
                        .setPanStrategy(DisplayManager.Content.PAN_CURSOR)
                        .setEditable(true)
                        .setSplitParagraphs(isMultiLineField()))
    }

    /* Private */

    private fun updateCurrentText() {
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

    private fun findParagraphBreakBackward(): Int {
        if (mSelectionStart <= 0) {
            return -1
        }
        return mCurrentText.lastIndexOf("\n", mSelectionStart - 2) + 1
    }

    private fun findParagraphBreakForward(): Int {
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

    private fun setCursor(ic: InputConnection, pos: Int): Boolean {
        val textLen = mCurrentText.length
        val newPos = if (pos < 0) 0 else if (pos <= textLen) pos else textLen
        val off = mExtractedText?.startOffset ?: 0
        val cursor = off + newPos
        return ic.setSelection(cursor, cursor)
    }

    private fun sendAndroidKeyInternal(keyCode: Int): Boolean {
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

    private fun handleBrailleKeyInternal(dots: Int): Boolean {
        // TODO: Support more than computer braille.  This means that
        // there's not a 1:1 correspondence between a cell and a character,
        // so requires more book-keeping.
        val translator = getCurrentBrailleTranslator()
        val ic = getCurrentInputConnection()
        if (translator == null || ic == null) {
            LogUtils.log(this, Log.WARN, "missing translator %s or IC %s",
                    translator, ic)
            return false
        }

        // TODO: Handle unknown dots (e.g. liblouis gives "\4568/").
        val tableInfo = translator.tableInfo ?: return false
        return if (doesTableNeedComposition(tableInfo)) {
            mComposingBraille.put(dots.toByte())
            // TODO: Handle word breaks properly for non-English,
            // where there might not be spaces (empty cell) between words.
            if (dots == 0 || mComposingBraille.position() == MAX_COMPOSE_DOTS) {
                // Finish composition (error if cannot translate).
                finishComposingText(translator, ic)
            } else {
                // Continue composing (OK if cannot translate).
                updateComposingText(translator, ic)
                true
            }
        } else {
            handleBrailleKeySimple(dots, translator, ic)
        }
    }

    private fun handleBrailleKeySimple(dots: Int,
                                       translator: BrailleTranslator,
                                       ic: InputConnection): Boolean {
        val text = translator.backTranslate(
                byteArrayOf(dots.toByte()))
        if (!TextUtils.isEmpty(text)) {
            return ic.commitText(text, 1)
        }
        return true
    }

    /**
     * Updates the composing text based on the braille dots composed thus far,
     * and maintains the composing state of the editor.
     * Returns `true` if the current string of braille dots could be
     * translated into text, otherwise `false`.
     */
    private fun updateComposingText(translator: BrailleTranslator,
                                    ic: InputConnection): Boolean {
        if (mComposingBraille.position() == 0) {
            return ic.commitText("", 1)
        }

        val text = translator.backTranslate(getComposingBrailleArray())
        return if (TextUtils.isEmpty(text)) {
            ic.setComposingText("\u00A0", 1)
        } else {
            ic.setComposingText(text, 1)
        }
    }

    /**
     * Updates the composing text and commits it to the editor. Returns
     * `true` if the pending braille dots could be translated into text,
     * otherwise `false`.
     */
    private fun finishComposingText(translator: BrailleTranslator,
                                    ic: InputConnection): Boolean {
        if (mComposingBraille.position() == 0) {
            return true
        }

        val text = translator.backTranslate(getComposingBrailleArray())
        mComposingBraille.clear()

        // Commit the final text if we could translate; otherwise, clear the
        // composing text.
        return if (TextUtils.isEmpty(text)) {
            ic.commitText("", 1)
            false
        } else {
            ic.commitText(text, 1)
        }
    }

    /**
     * Cancels the text composition by leaving the already-composed text there
     * and clearing the composition state. Use this when the user tries to
     * interact with the edit field before composition has finished.
     */
    private fun cancelComposingText(): Boolean {
        val ic = getCurrentInputConnection()
        if (ic == null) {
            LogUtils.log(this, Log.WARN, "missing IC %s", ic)
            return false
        }

        mComposingBraille.clear()
        return ic.finishComposingText()
    }

    private fun getComposingBrailleArray(): ByteArray {
        val len = mComposingBraille.position()
        val dotsArray = ByteArray(len)
        mComposingBraille.position(0)
        mComposingBraille.get(dotsArray, 0, len)
        mComposingBraille.position(len)
        return dotsArray
    }

    private fun getHost(): Host? {
        return mHost ?: sHost?.get()
    }

    private fun getCurrentBrailleTranslator(): BrailleTranslator? {
        return getHost()?.getBrailleTranslator()
    }

    private fun getCurrentDisplayManager(): DisplayManager? {
        return getHost()?.getDisplayManager()
    }

    private fun getCurrentFeedbackManager(): FeedbackManager? {
        return getHost()?.getFeedbackManager()
    }

    private fun getActionLabel(): CharSequence? {
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

    private fun allowsDefaultAction(): Boolean {
        val ei = getCurrentInputEditorInfo()
        return ei != null &&
                (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
    }

    private fun isMultiLineField(): Boolean {
        val ei = getCurrentInputEditorInfo() ?: return false
        val type = ei.inputType
        val mask = EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                EditorInfo.TYPE_TEXT_FLAG_IME_MULTI_LINE
        // Consider this a multiline field if it is multiline in the main
        // text field, and not multiline only in the ime fullscreen mode.
        return (type and mask) == EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
    }

    private fun addMarkingSpan(spannable: Spannable, span: MarkingSpan,
                               position: Int) {
        if (position < spannable.length) {
            spannable.setSpan(span, position, spannable.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // Visible for testing.
    internal fun switchAwayFromThisIme() {
        LogUtils.log(this, Log.DEBUG, "Switching to last IME")
        val binder = getWindow()?.window?.attributes?.token
        if (binder == null || !mInputMethodManager.switchToNextInputMethod(binder, false)) {
            LogUtils.log(this, Log.DEBUG,
                    "Failed to switch to next IME, show IME picker instead.")
            mInputMethodManager.showInputMethodPicker()
        }
    }

    private fun emitFeedback(type: Int) {
        getCurrentFeedbackManager()?.emitFeedback(type)
    }

    private fun emitFeedbackOnFailure(result: Boolean, type: Int): Boolean {
        if (!result) {
            emitFeedback(type)
        }
        return true
    }

    private class MarkingSpan

    companion object {
        const val DIRECTION_FORWARD = 1
        const val DIRECTION_BACKWARD = -1
        private const val MAX_REQUEST_CHARS = 1000
        private const val MAX_COMPOSE_DOTS = 1000
        /** Marks the extent of the editable text.  */
        private val EDIT_TEXT_SPAN = MarkingSpan()
        /** Marks the extent of the action button.  */
        private val ACTION_LABEL_SPAN = MarkingSpan()

        private var sInstance: WeakReference<BrailleIME>? = null
        private var sHost: WeakReference<Host>? = null

        @JvmStatic
        fun getActiveInstance(): BrailleIME? {
            return sInstance?.get()
        }

        @JvmStatic
        fun setSingletonHost(host: Host?) {
            sHost = if (host != null) WeakReference(host) else null
        }

        private fun doesTableNeedComposition(info: TableInfo): Boolean {
            // Assume that six-dot (literary) braille needs composition but
            // eight-dot (computer) braille is immediate.
            return !info.isEightDot
        }
    }
}
