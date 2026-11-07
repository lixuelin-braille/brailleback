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
import com.google.android.accessibility.brailleime.BrailleIme
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.brailleback.ime.BrailleInputPolicy
import com.googlecode.eyesfree.brailleback.ime.SingleImeViewPolicy
import com.googlecode.eyesfree.brailleback.ime.UnifiedBrailleInput
import com.googlecode.eyesfree.utils.LogUtils
import java.lang.ref.WeakReference

/**
 * 唯一注册的盲文输入法。
 *
 * 合并前项目里有两个 InputMethodService：本类（点显器硬件输入）与移植过来的触屏
 * 盲文键盘 [BrailleIme]，在系统输入法列表里各占一项。现在只注册本类，由它继承
 * [BrailleIme] 复用触屏点字实现，并按 [resolveTouchKeyboardMode] 在两种形态间
 * 选择 input view：
 *   - 硬件模式（接点显器时默认）：沿用原点显器 IME 的占位视图，输入走 UnifiedBrailleInput；
 *   - 触屏模式（未接点显器时默认）：加载移植键盘的 InputView，走它自己的 EditBuffer。
 *
 * 硬件模式刻意不调用 BrailleIme 的 onStartInputView()：那会挂载 overlay 键盘、
 * 弹首启教程并替换全局异常处理器，对依赖读屏的点显器用户是干扰。
 * 硬件输入不依赖触屏键盘是否激活，因此两条链路不会互相影响。
 */
class BrailleIME : BrailleIme {

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
     * 唯一的盲文输入引擎（与触屏盲文键盘同源的 EditBuffer）。
     * 旧的 mComposingBraille + backTranslate 输入路径已整体移除：
     * 点位不再在本类里自行累积和回译，全部交给 EditBuffer。
     */
    private var unifiedBraille: UnifiedBrailleInput? = null

    /** Constructor for general use. */
    constructor() : super() {
        mHost = null
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
        // 复制在 BrailleInputPolicy 里的命令常量必须与 AIDL 事件定义保持一致。
        UnifiedBrailleInput.assertCommandContract()
        LogUtils.log(this, Log.VERBOSE, "Created Braille IME")

        getHost()?.onCreateIME()
    }

    override fun onDestroy() {
        super.onDestroy()
        // onFinishInput is not called when switching away from this IME
        // to another one, so clear the state here as well.
        mExtractedText = null
        updateCurrentText()
        unifiedBraille?.reset()
        unifiedBraille = null
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

        getHost()?.onStartInput(attribute, restarting)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        LogUtils.log(this, Log.VERBOSE, "onFinishInput")
        // 统一引擎里可能还挂着 composing 的音节，切走输入框前先落定，避免丢字。
        unifiedBraille?.finish()
        mExtractedText = null
        updateCurrentText()

        getHost()?.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        // 只有触屏模式才让父类激活移植键盘（overlay / 教程 / 手势控制器）。
        // InputMethodService 对这两个 View 生命周期回调默认是空实现，硬件模式跳过
        // 父类不会破坏 input view 的显示状态。
        if (touchKeyboardEnabled()) {
            super.onStartInputView(info, restarting)
        }
        LogUtils.log(this, Log.VERBOSE, "onStartInputView: touchKeyboard=$touchKeyboard")

        getHost()?.onStartInputView(info, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (touchKeyboardEnabled()) {
            super.onFinishInputView(finishingInput)
        }
        LogUtils.log(this, Log.VERBOSE, "onFinishInputView")

        getHost()?.onFinishInputView(finishingInput)
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onCreateInputView(): View {
        // touchKeyboard 在这里定格：onCreateInputView 每个 IME 会话只调用一次，
        // 同一会话里切模式必须保持一致，否则 onStart/FinishInputView 的判断会错位。
        touchKeyboard = resolveTouchKeyboardMode(readViewMode(),
                BrailleBackService.isBrailleDisplayConnected())
        if (touchKeyboard) {
            return super.onCreateInputView()
        }
        val inflater = getLayoutInflater()
        val inputView = inflater.inflate(R.layout.braille_ime, null)
        inputView.setOnClickListener { switchAwayFromThisIme() }
        inputView.setOnTouchListener { _: View, _: MotionEvent ->
            switchAwayFromThisIme()
            true
        }
        return inputView
    }

    /** 本会话是否处于触屏点字形态；在 onCreateInputView 时确定，之后不再变化。 */
    private var touchKeyboard = false

    private fun touchKeyboardEnabled(): Boolean = touchKeyboard

    private fun readViewMode(): String =
        getSharedPreferences(BRAILLE_KEYBOARD_PREFS, Context.MODE_PRIVATE)
            .getString(PREF_SINGLE_IME_VIEW_MODE, MODE_AUTO) ?: MODE_AUTO

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
            // 统一引擎下用 holdings（未上屏点位）代替旧的 mComposingBraille。
            val pendingCells = unifiedBraille?.holdings()?.position() ?: 0
            if ((candidatesEnd != selStart || selStart != selEnd) && pendingCells > 0) {
                cancelComposingText()
            }

            updateDisplay()
        }
    }

    /* Exposed for use by the host. */

    fun route(position: Int, content: DisplayManager.Content): Boolean {
        val ic = getCurrentInputConnection()
        val text: Spanned? = content.getSpanned()
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

    /**
     * 取得唯一输入引擎。所有码表（中文现行 / 英文 / 八点电脑盲文）一律走这里，
     * 由 BrailleLanguages.createEditBuffer 按码表创建对应的 EditBuffer 实现；
     * 不再按 locale 或点数在本类里分流到第二套引擎。
     */
    private fun unifiedEngine(): UnifiedBrailleInput? {
        if (unifiedBraille == null) {
            unifiedBraille = UnifiedBrailleInput(this, ::getCurrentBrailleTranslator)
        }
        return unifiedBraille?.takeIf { it.isAvailable() }
    }

    /**
     * 软件 / 硬件统一的输入入口。
     *
     * @return null 表示本次事件不归输入引擎处理（越界点位、前删、导航等），
     *         调用方应继续原来的导航链路；非 null 表示已消费。
     *         没有候选时候选键也返回 null，按键退回原来的光标导航语义。
     */
    fun onHardwareInputEvent(command: Int, argument: Int): Boolean? {
        val engine = unifiedEngine() ?: return null
        val route = BrailleInputPolicy.route(command, argument, /* unified= */ true, engine.isEightDot())
        return when (route.action) {
            BrailleInputPolicy.Action.LEGACY -> null
            BrailleInputPolicy.Action.APPEND_CELL ->
                emitFeedbackOnFailure(
                    engine.appendCell(route.cell),
                    FeedbackManager.TYPE_COMMAND_FAILED,
                )
            BrailleInputPolicy.Action.COMMIT ->
                emitFeedbackOnFailure(
                    engine.commit(),
                    FeedbackManager.TYPE_COMMAND_FAILED,
                )
            BrailleInputPolicy.Action.DELETE ->
                emitFeedbackOnFailure(
                    engine.delete(),
                    FeedbackManager.TYPE_COMMAND_FAILED,
                )
            BrailleInputPolicy.Action.ENTER ->
                emitFeedbackOnFailure(
                    engine.enter(),
                    FeedbackManager.TYPE_COMMAND_FAILED,
                )
            BrailleInputPolicy.Action.NEXT_CANDIDATE ->
                if (engine.nextCandidate()) true else null
            BrailleInputPolicy.Action.PREVIOUS_CANDIDATE ->
                if (engine.previousCandidate()) true else null
        }
    }

    fun handleBrailleKey(dots: Int): Boolean {
        // 兜底：即使有调用点绕过 onHardwareInputEvent，也只会进同一条链路。
        // 返回 null 的点位（越界、八点表空点位）统一忽略，不再有第二条输入引擎。
        return onHardwareInputEvent(BrailleInputEvent.CMD_BRAILLE_KEY, dots) ?: true
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
            text.append(getString(R.string.ime_label_separator))
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

    /*
     * 旧的第二套输入引擎已删除。
     *
     * 这里原本有 handleBrailleKeyInternal / isCjkTable / isPunctuationCell /
     * handleBrailleKeySimple / updateComposingText / finishComposingText，
     * 它们自行用 mComposingBraille 累积点位并调 host 的 liblouis backTranslate，
     * 与触屏键盘的 EditBuffer 各解释一遍按键，是「两套引擎」的根源。
     * 现在所有码表、所有输入源统一走 UnifiedBrailleInput → EditBuffer。
     * 应急降级也实现同一个 EditBuffer 接口（ime/LegacyEditBuffer.kt），不在此分叉。
     */

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

        // 统一引擎的 holdings 与输入框 composing 是同一份状态：任何会结束 composing
        // 的动作（前删、方向键、切焦点等）都必须先让缓冲落定，否则输入框的
        // 下划线没了、holdings 还在，下一次输入会重冒出旧字。
        // 与屏幕键盘 BrailleImeActor.performTextAction「动作前先提交」保持同一语义。
        val unified = unifiedEngine()
        if (unified != null && unified.finish()) {
            return true
        }
        return ic.finishComposingText()
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

        const val BRAILLE_KEYBOARD_PREFS = SingleImeViewPolicy.BRAILLE_KEYBOARD_PREFS
        const val PREF_SINGLE_IME_VIEW_MODE = SingleImeViewPolicy.PREF_SINGLE_IME_VIEW_MODE
        const val MODE_AUTO = SingleImeViewPolicy.MODE_AUTO
        const val MODE_ON = SingleImeViewPolicy.MODE_ON
        const val MODE_OFF = SingleImeViewPolicy.MODE_OFF

        /** 形态选择委托给纯 Kotlin 策略，便于 JVM 单测与变异测试锁死。 */
        @JvmStatic
        fun resolveTouchKeyboardMode(mode: String?, displayConnected: Boolean): Boolean =
            SingleImeViewPolicy.useTouchKeyboard(mode, displayConnected)

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
    }
}

/**
 * liblouis 对未定义点位的回译输出点号标记（utils.c _lou_unknownDots），
 * 形如 "\4568/"；绝不能当正文提交给编辑器或搜索框。
 */
private val UNKNOWN_DOTS_PATTERN = Regex("\\\\[0-8]+/")

internal fun hasUnknownDots(text: CharSequence?): Boolean =
    text != null && UNKNOWN_DOTS_PATTERN.containsMatchIn(text)
