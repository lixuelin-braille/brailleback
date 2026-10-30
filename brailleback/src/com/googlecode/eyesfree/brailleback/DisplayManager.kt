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

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.preference.PreferenceManager
import android.text.Spanned
import android.util.Log
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.braille.translate.TranslationResult
import com.googlecode.eyesfree.brailleback.wrapping.SimpleWrapStrategy
import com.googlecode.eyesfree.brailleback.wrapping.WordWrapStrategy
import com.googlecode.eyesfree.brailleback.wrapping.WrapStrategy
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.SharedPreferencesUtils
import java.lang.ref.WeakReference
import java.util.Arrays
import java.util.Comparator

/**
 * Keeps track of the current display content and handles panning.
 */
@SuppressLint("InvalidWakeLockTag")
class DisplayManager(
    private val translatorManager: TranslatorManager,
    private val context: BrailleBackService,
    panOverflowListenerArg: OnPanOverflowListener,
    connectionStateChangeListenerArg: Display.OnConnectionStateChangeListener,
    mappedInputEventListenerArg: OnMappedInputEventListener
) : Display.OnConnectionStateChangeListener,
        Display.OnInputEventListener,
        TranslatorManager.OnTablesChangedListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    /** Dot pattern used to overlay characters under a selection. */
    // TODO: Make customizable.
    private val SELECTION_DOTS = 0xC0
    /** Dot pattern used to overlay characters in a focused element. */
    // TODO: Make customizable.
    private val FOCUS_DOTS = 0xC0

    /**
     * Callback interface for notifying interested callers when the display is
     * panned out of the available content.  A typical reaction to such an
     * event would be to move focus to a different area of the screen and
     * display it.
     */
    interface OnPanOverflowListener {
        fun onPanLeftOverflow(content: Content)
        fun onPanRightOverflow(content: Content)
    }

    /**
     * Listener for input events that also get information about the current
     * display content and position mapping for commands with a positional
     * argument.
     */
    interface OnMappedInputEventListener {
        /**
         * Handles an input [event] that was received when
         * [content] was present on the display.
         *
         * If the input event has a positional argument, it is mapped
         * according to the display pan position in the content so that
         * it corresponds to the character that the user touched.
         *
         * [event] and [content] are owned by the caller and may
         * not be referenced after this method returns.
         *
         * NOTE: Since the display is updated asynchronously, there is a chance
         * that the actual content on the display when the user invoked
         * the command is different from [content].
         */
        fun onMappedInputEvent(event: BrailleInputEvent, content: Content)
    }

    /**
     * Builder-like class used to construct the content to put on the display.
     *
     * This object contains a [CharSequence] that represents what
     * characters to put on the display.  This sequence can be a
     * Spannable so that the characters can be annotated with
     * information about cursors and focus which will affect how the content
     * is presented on the display.  Arbitrary java objects may also be
     * included in the Spannable which can be used to determine what
     * action to take when the user invokes key commands related to a
     * particular position on the display (i.e. involving a cursor routing
     * key).  In particular, [AccessibilityNodeInfoCompat]s may be
     * included, in which case they will be recycled by the
     * [Content.recycle] method.  To facilitate movement outside the
     * bounds of the current [Content],
     * [AccessibilityNodeInfoCompat]s that represent the extent of the
     * content can also be added, but in that case, they are not included in
     * the Spannable.
     */
    class Content {
        companion object {
            /**
             * Pan strategy that moves the display to the leftmost position.
             * This is the default panning strategy.
             */
            const val PAN_RESET = 0

            /**
             * Pan strategy that positions the display so that it overlaps the
             * start of a selection or focus mark.  Falls back on [PAN_RESET]
             * if there is no selection or focus.
             */
            const val PAN_CURSOR = 1

            /**
             * Pan strategy that tries to position the display close to the
             * position that corresponds to the panning position in the previously
             * displayed content.  Spans of type
             * [AccessibilityNodeInfoCompat] are used to identify the
             * corresponding content in the old and new display content.
             * Falls back on [PAN_CURSOR] if a corresponding position can't
             * be found.
             */
            const val PAN_KEEP = 2

            /**
             * Default contraction behaviour, allow contractions unless there is a
             * selection span in the content.
             */
            const val CONTRACT_DEFAULT = 0

            /**
             * Allow contraction, regardless of the presence of a selection
             * span.
             */
            const val CONTRACT_ALWAYS_ALLOW = 1
        }

        private var text: CharSequence? = null
        private var firstNode: AccessibilityNodeInfoCompat? = null
        private var lastNode: AccessibilityNodeInfoCompat? = null
        private var panStrategy = PAN_RESET
        private var contractionMode = CONTRACT_DEFAULT
        private var splitParagraphs = false
        private var editable = false

        constructor()

        /** Shortcut to just set text for a one-off use. */
        constructor(textArg: CharSequence?) {
            text = textArg
        }

        fun setText(textArg: CharSequence?): Content {
            text = textArg
            return this
        }

        fun getText(): CharSequence? {
            return text
        }

        fun getSpanned(): Spanned? {
            return text as? Spanned
        }

        fun setFirstNode(node: AccessibilityNodeInfoCompat): Content {
            AccessibilityNodeInfoUtils.recycleNodes(firstNode)
            firstNode = AccessibilityNodeInfoCompat.obtain(node)
            return this
        }

        fun getFirstNode(): AccessibilityNodeInfoCompat? {
            return firstNode
        }

        fun setLastNode(node: AccessibilityNodeInfoCompat): Content {
            AccessibilityNodeInfoUtils.recycleNodes(lastNode)
            lastNode = AccessibilityNodeInfoCompat.obtain(node)
            return this
        }

        fun getLastNode(): AccessibilityNodeInfoCompat? {
            return lastNode
        }

        fun setPanStrategy(strategy: Int): Content {
            panStrategy = strategy
            return this
        }

        fun getPanStrategy(): Int {
            return panStrategy
        }

        fun setContractionMode(mode: Int): Content {
            contractionMode = mode
            return this
        }

        fun getContractionMode(): Int {
            return contractionMode
        }

        fun setSplitParagraphs(value: Boolean): Content {
            splitParagraphs = value
            return this
        }

        fun isSplitParagraphs(): Boolean {
            return splitParagraphs
        }

        fun setEditable(value: Boolean): Content {
            editable = value
            return this
        }

        fun isEditable(): Boolean {
            return editable
        }

        /**
         * Translates the text content, preserving any verbatim braille that is embedded in a
         * BrailleSpan. The current implementation of this method only handles the first
         * BrailleSpan; all subsequent BrailleSpans are ignored.
         *
         * @param translator The translator used for translating the subparts of the text without
         *     embedded BrailleSpans.
         * @param cursorPosition The position of the cursor; if it occurs in a section of the text
         *     without BrailleSpans, then the final cursor position in the output braille by the
         *     translator. Otherwise, if the cursor occurs within a BrailleSpan section, the final
         *     cursor position in the output braille is set to the first braille cell of the
         *     BrailleSpan.
         * @param computerBrailleAtCursor This parameter is passed through to the translator; if
         *     true,then contracted translators are instructed to translate the word under the
         *     cursor using computer braille (instead of contracted braille) to make editing
         *     easier.
         * @return The result of translation, possibly empty, not null.
         */
        fun translateWithVerbatimBraille(
            translator: BrailleTranslator?,
            cursorPosition: Int,
            computerBrailleAtCursor: Boolean
        ): TranslationResult {
            if (translator == null) {
                return createEmptyTranslation(text)
            }
            // Matches the original Java behavior, which assumes non-null text
            // whenever a translator is available.
            val text = this.text!!

            // Assume that we have at most one BrailleSpan since we currently
            // never add more than one BrailleSpan.
            // Also ignore BrailleSpans with zero-length span or no braille for
            // now because we don't currently add such BrailleSpans.
            var brailleSpan: DisplaySpans.BrailleSpan? = null
            var start = -1
            var end = -1
            val spanned = text as? Spanned
            if (spanned != null) {
                val spans =
                    spanned.getSpans(0, spanned.length, DisplaySpans.BrailleSpan::class.java)
                if (spans.size > 1) {
                    LogUtils.log(this, Log.WARN,
                            "More than one BrailleSpan, handling first only")
                }
                if (spans.isNotEmpty()) {
                    val spanStart = spanned.getSpanStart(spans[0])
                    val spanEnd = spanned.getSpanEnd(spans[0])
                    if (spans[0].braille.isNotEmpty() && spanStart < spanEnd) {
                        brailleSpan = spans[0]
                        start = spanStart
                        end = spanEnd
                    }
                }
            }

            if (brailleSpan != null) {
                // Chunk the text into three sections:
                // left: [0, start) - needs translation
                // mid: [start, end) - use the literal braille provided
                // right: [end, length) - needs translation
                val left = text.subSequence(0, start)
                val leftTrans = translator.translate(
                        left.toString(),
                        if (cursorPosition < start) cursorPosition else -1,
                        cursorPosition < start && computerBrailleAtCursor)

                val right = text.subSequence(end, text.length)
                val rightTrans = translator.translate(
                        right.toString(),
                        if (cursorPosition >= end) cursorPosition - end else -1,
                        cursorPosition >= end && computerBrailleAtCursor)

                // If one of the left or right translations is not valid, then
                // we will fall back by ignoring the BrailleSpan and
                // translating everything normally. (Chances are that
                // translating the whole text will fail also, but it wouldn't
                // hurt to try.)
                if (leftTrans == null || rightTrans == null) {
                    LogUtils.log(this, Log.ERROR,
                            "Could not translate left or right subtranslation, "
                            + "falling back on default translation")
                    return translateOrDefault(translator, cursorPosition,
                            computerBrailleAtCursor)
                }

                val startBraille = leftTrans.cells.size
                val endBraille = startBraille + brailleSpan.braille.size
                val totalBraille = endBraille + rightTrans.cells.size

                // Copy braille cells.
                val cells = ByteArray(totalBraille)
                System.arraycopy(leftTrans.cells, 0,
                        cells, 0, leftTrans.cells.size)
                System.arraycopy(brailleSpan.braille, 0,
                        cells, startBraille, brailleSpan.braille.size)
                System.arraycopy(rightTrans.cells, 0,
                        cells, endBraille, rightTrans.cells.size)

                // Copy text-to-braille indices.
                val leftTtb = leftTrans.textToBraillePositions
                val rightTtb = rightTrans.textToBraillePositions
                val textToBraille = IntArray(text.length)

                System.arraycopy(leftTtb, 0, textToBraille, 0, start)
                for (i in start until end) {
                    textToBraille[i] = startBraille
                }
                for (i in end until textToBraille.size) {
                    textToBraille[i] = endBraille + rightTtb[i - end]
                }

                // Copy braille-to-text indices.
                val leftBtt = leftTrans.brailleToTextPositions
                val rightBtt = rightTrans.brailleToTextPositions
                val brailleToText = IntArray(cells.size)

                System.arraycopy(leftBtt, 0, brailleToText, 0, startBraille)
                for (i in startBraille until endBraille) {
                    brailleToText[i] = start
                }
                for (i in endBraille until totalBraille) {
                    brailleToText[i] = end + rightBtt[i - endBraille]
                }

                // Get cursor.
                val cursor: Int = when {
                    cursorPosition < 0 -> -1
                    cursorPosition < start -> leftTrans.cursorPosition
                    cursorPosition < end -> startBraille
                    else -> endBraille + rightTrans.cursorPosition
                }

                return TranslationResult(cells, textToBraille,
                        brailleToText, cursor)
            }

            return translateOrDefault(translator, cursorPosition,
                    computerBrailleAtCursor)
        }

        private fun translateOrDefault(
            translator: BrailleTranslator,
            cursorPosition: Int,
            computerBrailleAtCursor: Boolean
        ): TranslationResult {
            val translation =
                translator.translate(text!!.toString(), cursorPosition, computerBrailleAtCursor)
            if (translation != null) {
                return translation
            }

            return createEmptyTranslation(text)
        }

        fun recycle() {
            AccessibilityNodeInfoUtils.recycleNodes(firstNode, lastNode)
            firstNode = null
            lastNode = null
            text?.let { DisplaySpans.recycleSpans(it) }
            text = null
        }

        override fun toString(): String {
            return String.format("DisplayManager.Content {text=%s}", getText())
        }
    }

    private val panOverflowListener: OnPanOverflowListener = panOverflowListenerArg
    private val connectionStateChangeListener: Display.OnConnectionStateChangeListener =
            connectionStateChangeListenerArg
    private val mappedInputEventListener: OnMappedInputEventListener =
            mappedInputEventListenerArg
    // Not final, because it is initialized in the handler thread.
    private var display: Display? = null
    private val displayHandler: DisplayHandler
    private val callbackHandler: CallbackHandler
    private val handlerThread: HandlerThread
    private val wakeLock: PowerManager.WakeLock
    private val sharedPreferences: SharedPreferences

    // Read and written across threads: written on the callback thread
    // (onConnectionStateChanged) and read on the display handler thread
    // (toggleBluetoothConnection) and the main thread (isDisplayConnected).

    @Volatile
    private var connected = false
    @Volatile
    private var simulatedDisplay = false

    /**
     * Cursor position last passed to the translate method of the translator. We use this because
     * it is more reliable than the position maps inside contracted words. In the common case
     * where there is just one selection/focus on the display at the same time, this gives better
     * results. Otherwise, we fall back on the position map, whic is also used for keeping the pan
     * position.
     */
    private var cursorPositionToTranslate = 0

    private var currentTranslationResult: TranslationResult = createEmptyTranslation(null)
    /** Display content without overlays for cursors, focus etc. */
    private var brailleContent: ByteArray = ByteArray(0)
    /** Braille content, potentially with dots overlaid for cursors and focus. */
    private var overlaidBrailleContent: ByteArray = brailleContent

    private var overlaysOn = false
    /** Whether display output is paused; content stays on the display. */
    @Volatile
    private var mFrozen = false
    /** When set, display refreshes are coalesced while scrolling rapidly. */
    @Volatile
    private var mMotionMode = false
    private lateinit var wrapStrategy: WrapStrategy
    private val editingWrapStrategy: WrapStrategy = SimpleWrapStrategy()
    private var preferredWrapStrategy: WrapStrategy = SimpleWrapStrategy()
    private var currentContent: Content = Content("")

    // Displayed content, already trimmed based on the display position.
    // Updated in updateDisplayedContent() and used in refresh().
    private var displayedBraille: ByteArray = ByteArray(0)
    private var displayedOverlaidBraille: ByteArray = ByteArray(0)
    private var displayedText: CharSequence = ""
    private var displayedBrailleToTextPositions: IntArray = IntArray(0)
    private var blinkNeeded = false
    /**
     * 选区/光标覆盖到的盲文格下标，每次重算覆盖点前清空。
     *
     * 只有它们参与闪烁判定：焦点标记不该闪（详见 [BlinkPolicy]）。
     */
    private val selectionCells = HashSet<Int>()

    /** Idle time after which the display is cleared; 0 disables idle clearing. */
    @Volatile
    private var mIdleClearTimeoutMs = 3 * 60 * 1000L
    /** Whether pausing the display also clears it. */
    @Volatile
    private var mPauseClear = true

    private val mIdleClearRunnable: Runnable = object : Runnable {
        override fun run() {
            doSendEmptyCommand()
            // Keep the display cleared during continued idle.
            if (mIdleClearTimeoutMs > 0) {
                displayHandler.postDelayed(this, mIdleClearTimeoutMs)
            }
        }
    }

    private val mMotionModeRefreshRunnable: Runnable = Runnable {
        if (!connected || mFrozen) {
            return@Runnable
        }
        doRefresh()
    }

    init {
        translatorManager.addOnTablesChangedListener(this)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "BrailleBack")
        handlerThread =
            object : HandlerThread("DisplayManager") {
                override fun onLooperPrepared() {
                    display = OverlayDisplay(context, DisplayClient(context))
                    display?.setOnConnectionStateChangeListener(this@DisplayManager)
                    display?.setOnInputEventListener(this@DisplayManager)
                }
            }
        handlerThread.start()
        displayHandler = DisplayHandler(handlerThread.looper)
        callbackHandler = CallbackHandler(this)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        updateWrapStrategyFromPreferences()
    }

    fun shutdown() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        displayHandler.stop()
        // Discard callbacks that were queued before shutdown.
        callbackHandler.removeCallbacksAndMessages(null)
        // Block on display shutdown. We need to make sure this finishes before
        // we can consider DisplayManager to be shut down.
        try {
            handlerThread.join(1000 /* milis */)
        } catch (e: InterruptedException) {
            LogUtils.log(this, Log.WARN,
                    "Display handler shutdown interrupted")
        }
        translatorManager.removeOnTablesChangedListener(this)
    }

    /**
     * Asynchronously updates the display to reflect [content].
     * [content] must not be modified after this function is called, and
     * will eventually be recycled by the display manager.
     */
    fun setContent(content: Content?) {
        if (content == null) {
            throw NullPointerException("content can't be null")
        }
        if (content.getText() == null) {
            throw NullPointerException("content text is null")
        }
        displayHandler.setContent(content)
    }

    /** Returns true if the current display is simulated. */
    fun isSimulatedDisplay(): Boolean {
        return simulatedDisplay
    }

    /**
     * Marks selection spans in the overlaid braille, and returns the position
     * in braille where the first selection begins. If there are no selection
     * spans, returns -1.
     */
    private fun markSelection(spanned: Spanned): Int {
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
            copyOverlaidContent()
            var i = start
            while (i < end && i < overlaidBrailleContent.size) {
                overlaidBrailleContent[i] = (overlaidBrailleContent[i].toInt() or SELECTION_DOTS).toByte()
                selectionCells.add(i)
                ++i
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
    private fun copyOverlaidContent() {
        if (overlaidBrailleContent === brailleContent) {
            overlaidBrailleContent = brailleContent.clone()
        }
    }

    private fun extendContentForCursor() {
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
    private fun markFocus(spanned: Spanned): Int {
        val spans =
                spanned.getSpans(0, spanned.length,
                        DisplaySpans.FocusSpan::class.java)
        var focusStart = -1
        for (span in spans) {
            val start =
                textToDisplayPosition(
                    currentTranslationResult, cursorPositionToTranslate, spanned.getSpanStart(span))
            if (start >= 0 && start < overlaidBrailleContent.size) {
                copyOverlaidContent()
                overlaidBrailleContent[start] = (overlaidBrailleContent[start].toInt() or FOCUS_DOTS).toByte()
                if (focusStart == -1) {
                    focusStart = start
                }
            }
        }
        return focusStart
    }

    override fun onConnectionStateChanged(state: Int) {
        if (state == Display.STATE_CONNECTED) {
            connected = true
            sendEmptyCommand()
            displayHandler.retranslate()
            resetIdleTimer()
        } else {
            sendEmptyCommand()
            connected = false
            displayHandler.removeCallbacks(mIdleClearRunnable)
        }
        simulatedDisplay = display?.isSimulated ?: false
        callbackHandler.onConnectionStateChanged(state)
    }

    override fun onInputEvent(event: BrailleInputEvent) {
        keepAwake()
        resetIdleTimer()
        LogUtils.log(this, Log.VERBOSE, "InputEvent: %s", event)
        // We're called from within the handler thread, so we forward
        // the call only if we are going to invoke the user's callback.
        when (event.command) {
            BrailleInputEvent.CMD_NAV_PAN_LEFT -> panLeft()
            BrailleInputEvent.CMD_NAV_PAN_RIGHT -> panRight()
            else -> sendMappedEvent(event)
        }
    }

    override fun onTablesChanged() {
        displayHandler.retranslate()
    }

    private fun sendMappedEvent(event: BrailleInputEvent) {
        var mappedEvent = event
        if (BrailleInputEvent.argumentType(mappedEvent.command)
                == BrailleInputEvent.ARGUMENT_POSITION) {
            val oldArgument = mappedEvent.argument
            // Offset argument by pan position and make sure it is less than
            // the next split position.
            val offsetArgument = oldArgument + wrapStrategy.getDisplayStart()
            if (offsetArgument >= wrapStrategy.getDisplayEnd()) {
                // The event is outisde the currently displayed
                // content, drop the event.
                return
            }
            // The mapped event argument is the translated offset argument.
            val newArgument =
                displayToTextPosition(
                    currentTranslationResult, cursorPositionToTranslate, offsetArgument)
            // Create a new event if the argument actually differs.
            if (newArgument != oldArgument) {
                mappedEvent = BrailleInputEvent(mappedEvent.command,
                        newArgument, mappedEvent.eventTime)
            }
        }
        callbackHandler.onMappedInputEvent(mappedEvent)
    }

    private fun panLeft() {
        if (wrapStrategy.panLeft()) {
            updateDisplayedContent()
        } else {
            callbackHandler.onPanLeftOverflow()
        }
    }

    private fun panRight() {
        if (wrapStrategy.panRight()) {
            updateDisplayedContent()
        } else {
            callbackHandler.onPanRightOverflow()
        }
    }

    private inner class DisplayHandler(looper: Looper) : Handler(looper) {
        fun setContent(content: Content) {
            obtainMessage(MSG_SET_CONTENT, content).sendToTarget()
        }

        fun retranslate() {
            sendEmptyMessage(MSG_RETRANSLATE)
        }

        fun schedulePulse() {
            if (hasMessages(MSG_PULSE)) {
                return
            }
            sendEmptyMessageDelayed(MSG_PULSE,
                if (overlaysOn) BLINK_ON_MILLIS else BLINK_OFF_MILLIS)
        }

        fun cancelPulse() {
            removeMessages(MSG_PULSE)
            overlaysOn = true
        }

        fun stop() {
            sendEmptyMessage(MSG_STOP)
        }

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_SET_CONTENT -> handleSetContent(msg.obj as Content)
                MSG_RETRANSLATE -> handleRetranslate()
                MSG_PULSE -> handlePulse()
                MSG_STOP -> handleStop()
                else -> {
                    // Fall out.
                }
            }
        }

        private fun handleSetContent(content: Content) {
            val oldContent = currentContent
            currentContent = content
            updateWrapStrategy()

            cursorPositionToTranslate = findCursorPosition(content)
            val oldTranslationResult = currentTranslationResult
            val oldDisplayStart = wrapStrategy.getDisplayStart()
            translateCurrentContent()
            cancelPulse()
            // Adjust the pan position according to the panning strategy.
            // Setting the position to -1 below means that the cursor position
            // returned by markCursor() will be used instead; if the pan
            // position is >= 0, then the cursor position will be ignored.
            // If the pan position is -1 and the cursor position is also -1
            // (no cursor), then the wrap strategy will reset the display to the
            // beginning of the line.
            var panPosition = -1
            when (content.getPanStrategy()) {
                Content.PAN_RESET -> panPosition = 0
                Content.PAN_KEEP -> {
                    // We don't align the display position to the size of
                    // the display in this case so that content doesn't
                    // jump around on the dipslay if content before the
                    // current display position changes size.
                    panPosition =
                        findMatchingPanPosition(
                            oldContent,
                            content,
                            oldTranslationResult,
                            currentTranslationResult,
                            oldDisplayStart)
                }
                Content.PAN_CURSOR -> {
                }
                else -> LogUtils.log(this@DisplayHandler, Log.ERROR,
                    "Unknown pan strategy: %d", content.getPanStrategy())
            }
            val cursorPosition = markCursor()
            if (panPosition >= 0) {
                wrapStrategy.panTo(panPosition, false)
            } else {
                wrapStrategy.panTo(cursorPosition, true)
            }
            updateDisplayedContent()
            // Have the callback handler recycle the old content so that
            // the thread in which the callbck handler is running is the
            // only thread modifying it.  It is safe for the callback
            // thread to recycle the event when it receives this message
            // because the display handler thread will not send any more
            // input event containing this content and the events that
            // have already been sent will be processed by trhe callback
            // thread before the recycle message arrives because of the
            // guaranteed ordering of message handling.
            callbackHandler.recycleContent(oldContent)
        }

        private fun handleRetranslate() {
            val oldTextPosition =
                displayToTextPosition(
                    currentTranslationResult, cursorPositionToTranslate,
                    wrapStrategy.getDisplayStart())
            translateCurrentContent()
            val panPosition =
                textToDisplayPosition(
                    currentTranslationResult, cursorPositionToTranslate, oldTextPosition)
            val cursorPosition = markCursor()
            if (panPosition >= 0) {
                wrapStrategy.panTo(panPosition, false)
            } else {
                wrapStrategy.panTo(cursorPosition, true)
            }
            cancelPulse()
            updateDisplayedContent()
        }

        private fun handlePulse() {
            overlaysOn = !overlaysOn
            refresh()
        }

        private fun handleStop() {
            display?.shutdown()
            handlerThread.quit()
        }
    }

    private class OnMappedInputEventArgs(
        val event: BrailleInputEvent,
        val content: Content
    )

    /**
     * Static so queued callbacks do not retain the DisplayManager (and its
     * accessibility service) through the main Looper.
     */
    private class CallbackHandler(manager: DisplayManager) : Handler(Looper.getMainLooper()) {
        private val managerRef = WeakReference(manager)

        fun onConnectionStateChanged(state: Int) {
            obtainMessage(MSG_ON_CONNECTION_STATE_CHANGED, state, 0)
                    .sendToTarget()
        }

        fun onMappedInputEvent(event: BrailleInputEvent) {
            val manager = managerRef.get() ?: return
            val args = OnMappedInputEventArgs(event, manager.currentContent)
            obtainMessage(MSG_ON_MAPPED_INPUT_EVENT, args).sendToTarget()
        }

        fun onPanLeftOverflow() {
            val manager = managerRef.get() ?: return
            obtainMessage(MSG_ON_PAN_LEFT_OVERFLOW, manager.currentContent).sendToTarget()
        }

        fun onPanRightOverflow() {
            val manager = managerRef.get() ?: return
            obtainMessage(MSG_ON_PAN_RIGHT_OVERFLOW, manager.currentContent).sendToTarget()
        }

        fun recycleContent(content: Content) {
            obtainMessage(MSG_RECYCLE_CONTENT, content).sendToTarget()
        }

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ON_CONNECTION_STATE_CHANGED ->
                    handleOnConnectionStateChanged(msg.arg1)
                MSG_ON_MAPPED_INPUT_EVENT -> {
                    val args = msg.obj as OnMappedInputEventArgs
                    handleOnMappedInputEvent(args.event, args.content)
                }
                MSG_ON_PAN_LEFT_OVERFLOW ->
                    handleOnPanLeftOverflow(msg.obj as Content)
                MSG_ON_PAN_RIGHT_OVERFLOW ->
                    handleOnPanRightOverflow(msg.obj as Content)
                MSG_RECYCLE_CONTENT ->
                    handleRecycleContent(msg.obj as Content)
                else -> {
                    // Fall out.
                }
            }
        }

        private fun handleOnConnectionStateChanged(state: Int) {
            managerRef.get()?.connectionStateChangeListener?.onConnectionStateChanged(state)
        }

        private fun handleOnMappedInputEvent(event: BrailleInputEvent,
                                             content: Content) {
            managerRef.get()?.mappedInputEventListener?.onMappedInputEvent(event, content)
        }

        private fun handleOnPanLeftOverflow(content: Content) {
            managerRef.get()?.panOverflowListener?.onPanLeftOverflow(content)
        }

        private fun handleOnPanRightOverflow(content: Content) {
            managerRef.get()?.panOverflowListener?.onPanRightOverflow(content)
        }

        private fun handleRecycleContent(content: Content) {
            content.recycle()
        }
    }

    private fun translateCurrentContent() {
        // Use the current translator, whether contracted or uncontracted, for
        // editing text, but instruct contracted translaters to uncontract
        // the braille for the word under the cursor.
        val translator = translatorManager.getTranslator()
        currentTranslationResult =
            currentContent.translateWithVerbatimBraille(
                translator, cursorPositionToTranslate,
                uncontractBrailleAtCursor(currentContent))

        wrapStrategy.setContent(currentContent, currentTranslationResult, getNumTextCells())
        brailleContent = currentTranslationResult.cells
        overlaidBrailleContent = brailleContent
    }

    /**
     * Marks the selection or focus cursor (in that priority), and returns the
     * position in braille of the selection or focus cursor if one exists. If no
     * selection or focus cursor exists, then returns -1.
     */
    private fun markCursor(): Int {
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

    private fun updateDisplayedContent() {
        if (!connected) {
            return
        }

        val displayStart = wrapStrategy.getDisplayStart()
        val displayEnd = wrapStrategy.getDisplayEnd()
        if (displayEnd < displayStart) {
            return
        }

        // Compute equivalent text and mapping.
        val brailleToTextPositions = currentTranslationResult.brailleToTextPositions
        val textLeft = if (displayStart >= brailleToTextPositions.size) {
            0
        } else {
            brailleToTextPositions[displayStart]
        }
        val contentText = currentContent.getText()!!
        var textRight =
            if (displayEnd >= brailleToTextPositions.size) {
                contentText.length
            } else {
                brailleToTextPositions[displayEnd]
            }
        // TODO: Prevent out of order brailleToTextPositions.
        if (textRight < textLeft) {
            textRight = textLeft
        }
        val newText = StringBuilder(contentText.subSequence(textLeft, textRight))
        val trimmedBrailleToTextPositions =
                IntArray(displayEnd - displayStart)
        for (i in trimmedBrailleToTextPositions.indices) {
            if (displayStart + i < brailleToTextPositions.size) {
                trimmedBrailleToTextPositions[i] =
                        brailleToTextPositions[displayStart + i] - textLeft
            } else {
                trimmedBrailleToTextPositions[i] = newText.length
                newText.append(' ')
            }
        }

        // Store all data needed by refresh().
        displayedBraille = brailleContent.copyOfRange(displayStart, displayEnd)
        displayedOverlaidBraille =
            if (brailleContent !== overlaidBrailleContent) {
                overlaidBrailleContent.copyOfRange(displayStart, displayEnd)
            } else {
                displayedBraille
            }
        displayedText = newText.toString()
        displayedBrailleToTextPositions = trimmedBrailleToTextPositions
        blinkNeeded = blinkNeeded()

        refresh()
    }

    /** Resets the idle-clearing timer; call on any display activity. */
    private fun resetIdleTimer() {
        displayHandler.removeCallbacks(mIdleClearRunnable)
        if (mIdleClearTimeoutMs > 0) {
            displayHandler.postDelayed(mIdleClearRunnable, mIdleClearTimeoutMs)
        }
    }

    /** Sets the idle clear timeout; 0 disables idle clearing. */
    fun setIdleClearTimeoutMs(timeoutMs: Long) {
        displayHandler.post {
            mIdleClearTimeoutMs = timeoutMs
            if (timeoutMs <= 0) {
                displayHandler.removeCallbacks(mIdleClearRunnable)
            } else {
                resetIdleTimer()
            }
        }
    }

    /** Sets whether pausing the display also clears it. */
    fun setPauseClear(pauseClear: Boolean) {
        displayHandler.post {
            mPauseClear = pauseClear
        }
    }

    /** Posts an empty (blank) command to clear the display. */
    fun sendEmptyCommand() {
        displayHandler.post {
            doSendEmptyCommand()
        }
    }

    /** Sends an all-blank dot pattern. Runs on the display handler thread. */
    private fun doSendEmptyCommand() {
        val currentDisplay = display ?: return
        if (!connected) {
            return
        }
        val props = currentDisplay.displayProperties
        val cells = props?.numTextCells ?: 0
        if (cells <= 0) {
            return
        }
        currentDisplay.displayDots(ByteArray(cells), "", null)
    }

    /**
     * Pauses or resumes the display output. While paused, the current content
     * stays on the display and updates are suppressed until resumed.
     */
    fun setFrozen(frozen: Boolean) {
        displayHandler.post {
            mFrozen = frozen
            if (mFrozen) {
                if (mPauseClear) {
                    // Paused: clear the display.
                    doSendEmptyCommand()
                }
            } else {
                refresh()
            }
        }
    }

    /**
     * Toggles the paused state. Returns `true` if the display is now
     * paused.
     */
    fun toggleFrozen(): Boolean {
        val nowFrozen = !mFrozen
        setFrozen(nowFrozen)
        return nowFrozen
    }

    /**
     * Toggles the braille display's Bluetooth connection. If a display is
     * currently connected, requests a disconnect; otherwise requests a
     * (re)connect. Intended to be bound to a customizable volume-key gesture.
     * The request is posted to the display handler thread, where the current
     * connection state is read.
     */
    fun toggleBluetoothConnection() {
        displayHandler.post {
            if (connected) {
                display?.disconnect()
            } else {
                display?.poll()
            }
        }
    }

    /** Returns `true` if a braille display is currently connected. */
    fun isDisplayConnected(): Boolean {
        return connected
    }

    /**
     * Sets whether motion mode is enabled. When enabled, display refreshes
     * are coalesced while the user scrolls rapidly: updates are deferred and
     * the display is refreshed once shortly after the scrolling stops.
     */
    fun setMotionMode(motionMode: Boolean) {
        displayHandler.post {
            mMotionMode = motionMode
            if (!mMotionMode) {
                // Apply any deferred content immediately.
                refresh()
            }
        }
    }

    private fun refresh() {
        if (!connected) {
            return
        }
        if (mFrozen) {
            // Output paused: keep the last content on the display.
            return
        }
        if (mMotionMode) {
            // Coalesce rapid updates: refresh once after scrolling settles.
            displayHandler.removeCallbacks(mMotionModeRefreshRunnable)
            displayHandler.postDelayed(
                    mMotionModeRefreshRunnable, MOTION_MODE_DEBOUNCE_MS)
            return
        }
        doRefresh()
    }

    private fun doRefresh() {
        resetIdleTimer()
        // 不闪的时候必须发叠加版：pulse 可能正停在 overlaysOn=false 那一刻，
        // 取消闪烁后沿用 false，焦点/选区点会凭空消失到下次内容变化
        val toDisplay =
            if (BlinkPolicy.showOverlays(blinkNeeded, overlaysOn)) {
                displayedOverlaidBraille
            } else {
                displayedBraille
            }
        display?.displayDots(toDisplay, displayedText, displayedBrailleToTextPositions)
        if (blinkNeeded) {
            displayHandler.schedulePulse()
        } else {
            displayHandler.cancelPulse()
        }
    }

    /**
     * 显示区里是否有需要明灭的点（选区/光标）。
     *
     * 判定只看选区，不看焦点：焦点标记同样会改写叠加内容，按「字节有没有差异」
     * 判断的话，只要屏上有焦点就会无限闪（详见 [BlinkPolicy]）。
     */
    private fun blinkNeeded(): Boolean =
        BlinkPolicy.shouldBlink(
            selectionCells, wrapStrategy.getDisplayStart(), wrapStrategy.getDisplayEnd())

    /**
     * Keeps the phone awake as if there was a 'user activity' registered
     * by the system.
     */
    @SuppressLint("WakelockTimeout")
    private fun keepAwake() {
        // Acquiring the lock and immediately releasing it keesp the phone
        // awake.  We don't use aqcuire() with a timeout because it just
        // adds an unnecessary context switch.
        wakeLock.acquire()
        wakeLock.release()
    }

    /**
     * Returns the size of the connected display, or `1` if
     * no display is connected.
     */
    private fun getNumTextCells(): Int {
        if (!connected) {
            return 1
        }
        return display?.displayProperties?.numTextCells ?: 1
    }

    private fun findMatchingPanPosition(
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

    private class ByDistanceComparator(
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

    /** Returns braille character index of a text character index. May return -1. */
    private fun textToDisplayPosition(
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

    private fun displayToTextPosition(
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

    private fun findCursorPosition(content: Content): Int {
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

    private fun uncontractBrailleAtCursor(content: Content): Boolean {
        if (content.getContractionMode() == Content.CONTRACT_ALWAYS_ALLOW) {
            return false
        }
        val spanned = content.getSpanned() ?: return false
        val selectionSpans =
                spanned.getSpans(0, spanned.length,
                        DisplaySpans.SelectionSpan::class.java)
        return selectionSpans.isNotEmpty()
    }

    override fun onSharedPreferenceChanged(sharedPreferencesArg: SharedPreferences?, key: String?) {
        val wordWrapPrefKey = context.getString(R.string.pref_braille_word_wrap_key)
        if (key != null && key == wordWrapPrefKey) {
            updateWrapStrategyFromPreferences()
        }
    }

    private fun updateWrapStrategyFromPreferences() {
        val wrap =
            SharedPreferencesUtils.getBooleanPref(
                sharedPreferences,
                context.resources,
                R.string.pref_braille_word_wrap_key,
                R.bool.pref_braille_word_wrap_default)

        preferredWrapStrategy = if (wrap) WordWrapStrategy() else SimpleWrapStrategy()
        updateWrapStrategy()
        displayHandler.retranslate()
    }

    private fun updateWrapStrategy() {
        val contentEditable = currentContent.isEditable()
        val imeOpen =
            context.imeNavigationMode != null
                && context.imeNavigationMode.isImeOpen()
        val editing = contentEditable && imeOpen
        wrapStrategy = if (editing) editingWrapStrategy else preferredWrapStrategy
    }

    companion object {
        private const val BLINK_OFF_MILLIS: Long = 800
        private const val BLINK_ON_MILLIS: Long = 600

        private const val MOTION_MODE_DEBOUNCE_MS: Long = 350

        private const val MSG_SET_CONTENT = 1
        private const val MSG_RETRANSLATE = 2
        private const val MSG_PULSE = 3
        private const val MSG_STOP = 4

        private const val MSG_ON_CONNECTION_STATE_CHANGED = 1
        private const val MSG_ON_MAPPED_INPUT_EVENT = 2
        private const val MSG_ON_PAN_LEFT_OVERFLOW = 3
        private const val MSG_ON_PAN_RIGHT_OVERFLOW = 4
        private const val MSG_RECYCLE_CONTENT = 5

        private fun createEmptyTranslation(text: CharSequence?): TranslationResult {
            val textLength = text?.length ?: 0
            return TranslationResult(ByteArray(0), IntArray(textLength), IntArray(0), 0)
        }
    }
}
