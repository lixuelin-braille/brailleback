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

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import com.google.android.accessibility.brailleime.BrailleIme
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.utils.LogUtils
import com.linxi.brailleback.ime.ImeDisplayBridge
import com.linxi.brailleback.ime.ImeSpeech
import com.linxi.brailleback.ime.SingleImeViewPolicy
import com.linxi.brailleback.ime.UnifiedBrailleInput
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

    internal var mExtractedText: ExtractedText? = null
    private var mExtractedTextToken = 0
    /** Start of current selection, relative to the start of the extracted text. */
    internal var mSelectionStart = 0
    /** End (inclusive) of current selection, relative to the start of the extracted text. */
    internal var mSelectionEnd = 0
    /**
     * Start of the currently-composing braille word when typing using a
     * literary braille table.
     */
    internal var mComposingStart = 0
    /**
     * End (exclusive) of the currently-composing braille word when typing
     * using a literary braille table. Should correspond to the current cursor
     * position.
     */
    internal var mComposingEnd = 0
    /**
     * The text that is shown on the display. Might be only part of the full
     * edit field if it is larger than `MAX_REQUEST_CHARS`.
     */
    internal val mCurrentText = StringBuilder()
    /**
     * 唯一的盲文输入引擎（与触屏盲文键盘同源的 EditBuffer）。
     * 旧的 mComposingBraille + backTranslate 输入路径已整体移除：
     * 点位不再在本类里自行累积和回译，全部交给 EditBuffer。
     */
    internal var unifiedBraille: UnifiedBrailleInput? = null
    /**
     * 点位累积器：单点事件先攒着，攒到第二个点、或者按空格时，才作为一格提交。
     *
     * 刻意不引入任何计时器（不做「停顿多少毫秒算一格」）：节奏完全由用户按键决定，
     * 手慢手快都不影响结果，行为与普通输入法一致。一次事件就带两个以上点（真正同时
     * 按下的 chord）时立刻上屏，不受累积影响。
     */
    internal var pendingDots = 0

    /**
     * 没有无障碍宿主时，输入法自己连点显器。
     *
     * 点显器连接本身不依赖无障碍服务（`DisplayService` 是独立的前台服务），所以输入法可以
     * 单独把点显器接过来打字。宿主在跑时必须为 null：`DisplayService` 把按键广播给所有
     * 客户端，两套同时活着会一个按键做两遍。
     */
    internal var imeDisplay: ImeDisplayBridge? = null

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
        // 输入法可以脱离无障碍服务单独跑：那种情形下读屏桥是空的，候选播报交给系统 TTS 兜底。
        // 读屏在跑时桥会被注入，兜底不会被用到，所以不会重复播报。
        BrailleCommonTalkBackSpeaker.setFallbackSpeaker(ImeSpeech)
        ImeSpeech.warmUp(this)
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
        pendingDots = 0
        imeDisplay?.stop()
        imeDisplay = null
        sInstance = null

        getHost()?.onDestroyIME()

        // 兜底播报随输入法一起退场，别让已销毁的实例占着 TTS 引擎。
        ImeSpeech.shutdown()
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
        pendingDots = 0
        mExtractedText = null
        updateCurrentText()

        getHost()?.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        // 满屏输入区挂出来时，父类的 onStartInputView 是它生效的必经一步：BrailleInputView
        // 的触摸区、候选、手势控制器都在父类里初始化，跳过就不会工作。
        //
        // 硬件模式也走这里——两种键盘同时可用是明确要求：点显器照旧通过 onHardwareInputEvent
        // 送字，屏幕上的触摸键盘走自己的触摸区，两者共用同一个 UnifiedBrailleInput 与
        // EditBuffer，候选是同一份。
        if (fullInputView) {
            super.onStartInputView(info, restarting)
        }
        LogUtils.log(this, Log.VERBOSE, "onStartInputView: touchKeyboard=$touchKeyboard full=$fullInputView")

        reconcileImeDisplay()
        getHost()?.onStartInputView(info, restarting)
    }

    /**
     * 按「有没有无障碍宿主」决定输入法是否自己连点显器。
     *
     * 宿主在跑：立刻断开自己的连接并让出。宿主启动/关闭可能发生在本输入法已经活着的时候，
     * 所以每次弹出输入区都重新对齐一次，不靠一次性判断。
     */
    private fun reconcileImeDisplay() {
        val hostPresent = getHost() != null
        if (hostPresent) {
            imeDisplay?.let {
                it.stop()
                imeDisplay = null
            }
            return
        }
        if (imeDisplay == null) {
            imeDisplay = ImeDisplayBridge(
                applicationContext,
                { getActiveInstance() },
                { getHost() != null },
            ).also { it.start() }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (fullInputView) {
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
        val mode = readViewMode()
        touchKeyboard = resolveTouchKeyboardMode(mode,
                BrailleBackService.isBrailleDisplayConnected())
        // 只有用户显式选了 off（"只用点显器、不要屏幕键盘"）时，才退回原来自带的提示条。
        // 其余情况——包括点显器已连接的 auto——都挂 TalkBack 那颗满屏输入区，屏幕和点显器并行。
        fullInputView = mode != MODE_OFF
        if (fullInputView) {
            // 满屏输入区来自移植的 TalkBack 实现，依赖宿主桥接齐全；万一某台机器上初始化
            // 失败（缺失手势控制器等），退回提示条也比整个输入法崩掉强——崩了用户连字都打不了。
            try {
                return super.onCreateInputView()
            } catch (e: RuntimeException) {
                LogUtils.log(this, Log.ERROR, "super.onCreateInputView failed", e)
                fullInputView = false
            }
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

    /** 本会话是否挂了满屏输入区（触屏键盘与硬件点显器可以并行）。 */
    private var fullInputView = false

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
        val extracted = mExtractedText
        if (extracted != null) {
            val off = extracted.startOffset
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

    internal fun getHost(): Host? {
        return mHost ?: sHost?.get()
    }

    internal fun getCurrentBrailleTranslator(): BrailleTranslator? {
        return getHost()?.getBrailleTranslator()
    }

    internal fun getCurrentDisplayManager(): DisplayManager? {
        return getHost()?.getDisplayManager()
    }

    internal fun getCurrentFeedbackManager(): FeedbackManager? {
        return getHost()?.getFeedbackManager()
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

    internal class MarkingSpan

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
        internal val EDIT_TEXT_SPAN = MarkingSpan()
        /** Marks the extent of the action button.  */
        internal val ACTION_LABEL_SPAN = MarkingSpan()

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
