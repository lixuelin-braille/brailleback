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

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import com.googlecode.eyesfree.utils.SettingsStore
import android.text.Spanned
import android.util.Log
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.braille.translate.TranslationResult
import com.linxi.brailleback.keymap.CustomKeyMapStore
import com.linxi.brailleback.wrapping.HeadingLayout
import com.linxi.brailleback.wrapping.ParagraphIndent
import com.linxi.brailleback.wrapping.SimpleWrapStrategy
import com.linxi.brailleback.wrapping.WordWrapStrategy
import com.linxi.brailleback.wrapping.WrapStrategy
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.SharedPreferencesUtils
import java.lang.ref.WeakReference
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    // 光标点/焦点点叠加（第 7、8 点）的实际值见 DisplayRuntimeState.cursorDots：
    // 八点显示器上这些点会真的凸起来，所以默认关闭；光标位置仍参与自动平移。

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


    internal val panOverflowListener: OnPanOverflowListener = panOverflowListenerArg
    internal val connectionStateChangeListener: Display.OnConnectionStateChangeListener =
            connectionStateChangeListenerArg
    internal val mappedInputEventListener: OnMappedInputEventListener =
            mappedInputEventListenerArg
    // Not final, because it is initialized in the handler thread.
    private var display: Display? = null
    private val displayHandler: DisplayHandler
    private val callbackHandler: DisplayCallbackHandler
    private val handlerThread: HandlerThread
    private val wakeLock: PowerManager.WakeLock
    private val sharedPreferences: SharedPreferences

    // Read and written across threads: written on the callback thread
    /**
     * 跨线程共享状态的唯一来源（连接/模拟/暂停/运动模式/光标叠加）：
     * 原来是五个分散的 `@Volatile`，裁决时连读三个必然可能撕裂，现在一次取快照。
     */
    private val runtimeState = MutableStateFlow(DisplayRuntimeState())

    private val connected: Boolean get() = runtimeState.value.connected
    private val simulatedDisplay: Boolean get() = runtimeState.value.simulated
    private val frozen: Boolean get() = runtimeState.value.frozen
    private val motionMode: Boolean get() = runtimeState.value.motionMode
    private val cursorDotsEnabled: Boolean get() = runtimeState.value.cursorDots
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
    /** 点位回显的独立小闭环；构造在 [displayScope] 之后，跑同一条 display 线程。 */
    private val pressEcho: DisplayPressEcho
    private lateinit var wrapStrategy: WrapStrategy
    private val editingWrapStrategy: WrapStrategy = SimpleWrapStrategy()
    private var preferredWrapStrategy: WrapStrategy = SimpleWrapStrategy()
    internal var currentContent: Content = Content("")

    // Displayed content, already trimmed based on the display position.
    // Updated in updateDisplayedContent() and used in refresh().
    private var displayedBraille: ByteArray = ByteArray(0)
    private var displayedOverlaidBraille: ByteArray = ByteArray(0)
    private var displayedText: CharSequence = ""
    private var displayedBrailleToTextPositions: IntArray = IntArray(0)
    private var blinkNeeded = false
    /**
     * 刷新链路心跳：每 5 秒一行，冻结时用来判断上层是否还在产生新的点阵。
     * 同时记录被 connected/frozen/motion 三个条件拦下的刷新次数。
     *
     * 计数与格式见 [DisplayRefreshStats]；上报线程仍在这里，且必须独立于刷新调度器。
     */
    private val TELEMETRY_INTERVAL_MS = 5000L
    private val stats = DisplayRefreshStats()
    /**
     * 刷新闸门：指纹去重 + 最小间隔限速。三个字段本来是一组（待发指纹、
     * 已送达指纹、已送达时刻），合成一个对象才不会读到互相矛盾的快照。
     */
    private val refreshGate = RefreshGate()
    /**
     * 独立守护线程上报：即使 DisplayManager 的 handler 线程卡死，这里仍会输出，
     * 日志里表现为某项计数不再增长，从而区分「上层不刷新」和「线程被卡住」。
     */
    private val telemetryRunning = AtomicBoolean(true)
    private val telemetryThread = Thread {
        while (telemetryRunning.get()) {
            try {
                Thread.sleep(TELEMETRY_INTERVAL_MS)
            } catch (e: InterruptedException) {
                break
            }
            // 心跳行也取同一份快照，避免日志把两次状态拼在一起。
            val snapshot = runtimeState.value
            LogUtils.i(
                this@DisplayManager,
                "%s",
                stats.describe(snapshot.connected, snapshot.frozen, snapshot.motionMode)
            )
        }
    }.apply {
        isDaemon = true
        name = "DisplayStats"
        start()
    }
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

    /**
     * 三个定时器都跑在 display HandlerThread 的 looper 上（拿 `handlerThread.looper`
     * 当协程调度器），取消即 `cancel()`，不再依赖消息队列里的自续 `Runnable`。
     */
    private val displayScope: CoroutineScope
    /** 空闲清屏：安静满 mIdleClearTimeoutMs 后清屏，之后按同一间隔继续清。 */
    private val idleClearTimer: RepeatingTimer
    /** 运动模式去抖：滚动停下 MOTION_MODE_DEBOUNCE_MS 后补一次刷新。 */
    private val motionDebouncer: Debouncer
    /** 光标闪烁：延迟一次后执行，动作内部（doRefresh）再排下一次。 */
    private val pulseTimer: OneShotTimer

    /**
     * 用户自定义的「原始命令码 → 目标命令码」映射，默认空表（全部保持出厂行为）。
     * 设置页写入偏好后由 [onSharedPreferenceChanged] 原子替换，读写分属不同线程，
     * 因此用 AtomicReference 保证可见性；表本身是不可变 Map，构造完不再修改。
     */
    private val customKeyMap = AtomicReference<Map<Int, Int>>(emptyMap())

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
        val displayLooper = handlerThread.looper
        // 协程调度器直接绑在 display HandlerThread 上：delay、取消、消息处理
        // 共享同一个 looper，先后顺序与原来的 postDelayed 完全一致。
        displayScope = CoroutineScope(
            SupervisorJob() + Handler(displayLooper).asCoroutineDispatcher()
        )
        idleClearTimer = RepeatingTimer(displayScope)
        motionDebouncer = Debouncer(displayScope)
        pulseTimer = OneShotTimer(displayScope)
        pressEcho = DisplayPressEcho(displayScope, { display }, { connected && !frozen }, { refresh() })
        displayHandler = DisplayHandler(displayLooper)
        callbackHandler = DisplayCallbackHandler(this)

        sharedPreferences = SettingsStore.get(context)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        updateWrapStrategyFromPreferences()
        reloadCustomKeyMap()
    }

    fun shutdown() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        telemetryRunning.set(false)
        telemetryThread.interrupt()
        // 先停定时器，避免 looper 退出后再有 delay 唤醒。
        displayScope.cancel()
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

    override fun onConnectionStateChanged(state: Int) {
        if (state == Display.STATE_CONNECTED) {
            runtimeState.update { it.copy(connected = true) }
            sendEmptyCommand()
            displayHandler.retranslate()
            resetIdleTimer()
        } else {
            sendEmptyCommand()
            runtimeState.update { it.copy(connected = false) }
            idleClearTimer.cancel()
        }
        runtimeState.update { it.copy(simulated = display?.isSimulated ?: false) }
        callbackHandler.onConnectionStateChanged(state)
    }

    override fun onInputEvent(event: BrailleInputEvent) {
        keepAwake()
        resetIdleTimer()
        LogUtils.log(this, Log.VERBOSE, "InputEvent: %s", event)
        // 自定义按键映射必须发生在所有分发之前：panLeft/panRight 在本类内被本地拦截，
        // sendMappedEvent 还会对带位置参数的命令做坐标换算，晚一步都会走错分支。
        // 这里是 brltty/JNI 主路径与 BLE 旁路（WujieGattConnection）的唯一汇聚点。
        val effectiveEvent = applyCustomKeyMap(event) ?: return
        // We're called from within the handler thread, so we forward
        // the call only if we are going to invoke the user's callback.
        when (effectiveEvent.command) {
            BrailleInputEvent.CMD_NAV_PAN_LEFT -> panLeft()
            BrailleInputEvent.CMD_NAV_PAN_RIGHT -> panRight()
            else -> sendMappedEvent(effectiveEvent)
        }
    }

    /** 单层查表替换命令码；白名单保证源命令不带位置/点位参数，argument 原样保留。 */
    private fun applyCustomKeyMap(event: BrailleInputEvent): BrailleInputEvent? {
        val target = customKeyMap.get()[event.command] ?: return event
        if (target == BrailleInputEvent.CMD_NONE) {
            // 用户把该按键设为「无动作」：整个事件在这里被吞掉，不反馈、不分发。
            // 静默是刻意的——抱怨「这个键太难按」的用户要的正是「按了当没按」。
            LogUtils.log(this, Log.VERBOSE, "CustomKeyMap: %d -> no action", event.command)
            return null
        }
        if (target == event.command) {
            return event
        }
        LogUtils.log(this, Log.VERBOSE, "CustomKeyMap: %d -> %d", event.command, target)
        return BrailleInputEvent(target, event.argument, event.eventTime)
    }

    /** 从 SharedPreferences 重建运行时表；坏数据由纯逻辑层静默过滤。 */
    private fun reloadCustomKeyMap() {
        customKeyMap.set(CustomKeyMapStore.runtimeMap(context))
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
        /**
         * 待处理的内容。setContent 由调用方线程进入，消息在 display handler
         * 线程消费，所以用原子引用而不是普通字段。窗口内只保留最后一帧。
         */
        private val pendingContent = AtomicReference<Content?>(null)
        /** 是否已有一个 MSG_SET_CONTENT 在队列里等待消费。 */
        private val coalescePending = AtomicBoolean(false)

        fun setContent(content: Content) {
            val superseded = pendingContent.getAndSet(content)
            if (superseded != null) {
                // 定时器已在跑，只换内容、不重置定时器：事件洪流下首帧
                // 延迟上限恒为 CONTENT_MIN_INTERVAL_MILLIS，不会永远轮不到。
                stats.onCoalesced()
                // 被替换掉的那帧不会再显示，它的节点引用必须归还。
                callbackHandler.recycleContent(superseded)
                return
            }
            if (!coalescePending.compareAndSet(false, true)) {
                return
            }
            // 距上次真正刷屏已够久就立即显示，密集时顺延到最小间隔。
            val elapsed = SystemClock.uptimeMillis() - refreshGate.lastDeliveredAt()
            val delay = (CONTENT_MIN_INTERVAL_MILLIS - elapsed).coerceAtLeast(0)
            sendEmptyMessageDelayed(MSG_SET_CONTENT, delay)
        }

        fun retranslate() {
            sendEmptyMessage(MSG_RETRANSLATE)
        }

        fun schedulePulse() {
            // 一次只排一次：闪一下 → 重画 → doRefresh 再决定要不要排下一次。
            pulseTimer.schedule(if (overlaysOn) BLINK_ON_MILLIS else BLINK_OFF_MILLIS) {
                overlaysOn = !overlaysOn
                refresh()
            }
        }

        fun cancelPulse() {
            pulseTimer.cancel()
            overlaysOn = true
        }

        fun stop() {
            sendEmptyMessage(MSG_STOP)
        }

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_SET_CONTENT -> {
                    coalescePending.set(false)
                    val content = pendingContent.getAndSet(null)
                    if (content != null) {
                        handleSetContent(content)
                    }
                }
                MSG_RETRANSLATE -> handleRetranslate()
                MSG_STOP -> handleStop()
                else -> {
                    // Fall out.
                }
            }
        }

        private fun handleSetContent(content: Content) {
            stats.onContent()
            val fingerprint = contentFingerprint(content)
            if (refreshGate.isDuplicateOfLast(fingerprint)) {
                // 文本、选区、焦点、平移策略全都没变：这只是窗口重绘触发的
                // 重复下发，重新翻译并整屏重推会让点显器白白闪一次。
                stats.onIdentical()
                callbackHandler.recycleContent(content)
                return
            }
            refreshGate.markPending(fingerprint)
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
            stats.onRetranslate()
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

        private fun handleStop() {
            // 合并窗口里还没处理的那一帧也要归还，不能随 handler 一起丢弃。
            val pending = pendingContent.getAndSet(null)
            if (pending != null) {
                callbackHandler.recycleContent(pending)
            }
            display?.shutdown()
            handlerThread.quit()
        }
    }


    private fun translateCurrentContent() {
        // Use the current translator, whether contracted or uncontracted, for
        // editing text, but instruct contracted translaters to uncontract
        // the braille for the word under the cursor.
        val translator = translatorManager.getTranslator()
        var translation =
            currentContent.translateWithVerbatimBraille(
                translator, cursorPositionToTranslate,
                uncontractBrailleAtCursor(currentContent))

        val numTextCells = getNumTextCells()
        if (currentContent.isParagraphIndent()
            && numTextCells > ParagraphIndent.INDENT_CELLS
        ) {
            // 段首空两方。缩进必须加在翻译之后：一个汉字最终占几方由盲文
            // 表决定（现行盲文一字一方，双拼两方），在原文里插空格不能
            // 保证正好是两方空白。
            translation = ParagraphIndent.apply(
                currentContent.getText(),
                translation,
                currentContent.isFirstParagraphIndent())
        }
        if (currentContent.isHeading()) {
            // 标题居中：按显示区的实际方数在左侧补空白。
            translation = HeadingLayout.center(translation, numTextCells)
        }

        currentTranslationResult = translation
        wrapStrategy.setContent(currentContent, currentTranslationResult, numTextCells)
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
        // 内容可能已被清空：没有文本就不做这次位置换算，直接放弃这一帧，
        // 以前这里用非空断言，会让刷新线程整个抛出去。
        val contentText = currentContent.getText() ?: return
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
        // 每次显示活动都重置：安静满 mIdleClearTimeoutMs 才清屏，之后按同一间隔继续清。
        idleClearTimer.restart(mIdleClearTimeoutMs) { doSendEmptyCommand() }
    }

    /** Sets the idle clear timeout; 0 disables idle clearing. */
    fun setIdleClearTimeoutMs(timeoutMs: Long) {
        displayHandler.post {
            mIdleClearTimeoutMs = timeoutMs
            // restart 内部对 <= 0 直接跳过，等于关闭空闲清屏。
            resetIdleTimer()
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

    /** 点位回显的唯一入口；叠加与超时还原的细节都在 [DisplayPressEcho]。 */
    fun showPressedDots(dots: Int) = pressEcho.show(dots)

    /**
     * Pauses or resumes the display output. While paused, the current content
     * stays on the display and updates are suppressed until resumed.
     */
    fun setFrozen(frozen: Boolean, clear: Boolean = mPauseClear) {
        displayHandler.post {
            runtimeState.update { it.copy(frozen = frozen) }
            if (frozen) {
                if (clear) {
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
        val nowFrozen = !frozen
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
            if (this.motionMode == motionMode) {
                return@post
            }
            runtimeState.update { it.copy(motionMode = motionMode) }
            if (!this.motionMode) {
                // Apply any deferred content immediately.
                motionDebouncer.cancel()
                refresh()
            }
        }
    }

    /**
     * 设置是否在盲文格上叠加光标点（第 7、8 点）。
     *
     * 关闭后不再加任何额外点位，也不闪烁；光标位置仍用于自动平移，
     * 编辑长文本时视野照样跟着走。
     */
    fun setCursorDotsEnabled(enabled: Boolean) {
        displayHandler.post {
            if (cursorDotsEnabled == enabled) {
                return@post
            }
            runtimeState.update { it.copy(cursorDots = enabled) }
            displayHandler.retranslate()
        }
    }

    private fun refresh() {
        // 一次取快照：三个条件必须来自同一时刻，否则「刚连上但读到旧的冻结标记」
        // 会算出一个当次并不成立的决定。
        when (val decision = runtimeState.value.refreshDecision()) {
            RefreshDecision.Send -> doRefresh()
            is RefreshDecision.Blocked -> {
                stats.onBlocked(decision.reason)
                if (decision.reason == RefreshBlock.MOTION) {
                    // Coalesce rapid updates: refresh once after scrolling settles.
                    motionDebouncer.schedule(MOTION_MODE_DEBOUNCE_MS) {
                        // 滚动停下后再补一次：与原来的去抖 Runnable 同条件。
                        val snapshot = runtimeState.value
                        if (snapshot.connected && !snapshot.frozen) {
                            doRefresh()
                        }
                    }
                }
            }
        }
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
        if (toDisplay.isEmpty() && stats.lastCells() > 0) {
            // 空数组会被编码成一整屏空白，点显器先清屏再画，看起来就是闪一下。
            // 焦点在两个节点之间切换时会短暂下发空内容，此时保持原屏更稳。
            stats.onEmpty()
            return
        }
        stats.onRefreshOk(toDisplay.size, toDisplay.contentHashCode())
        display?.displayDots(toDisplay, displayedText, displayedBrailleToTextPositions)
        // 时刻与指纹一起转正：只有真的送达点显器，重复下发才允许被丢弃。
        refreshGate.onDelivered(SystemClock.uptimeMillis())
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
        cursorDotsEnabled && BlinkPolicy.shouldBlink(
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
        if (key != null && key == CustomKeyMapStore.preferenceKey(context)) {
            // 设置页保存后立即生效，无需重启无障碍服务或重连点显器。
            reloadCustomKeyMap()
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
        val imeMode = context.imeNavigationMode
        val imeOpen = imeMode != null && imeMode.isImeOpen()
        val editing = contentEditable && imeOpen
        wrapStrategy = if (editing) editingWrapStrategy else preferredWrapStrategy
    }

    companion object {
        private const val BLINK_OFF_MILLIS: Long = 800
        private const val BLINK_ON_MILLIS: Long = 600

        private const val MOTION_MODE_DEBOUNCE_MS: Long = 350

        /**
         * 两次整屏刷新之间的最小间隔。
         *
         * 无障碍框架在桌面重绘、快速滚动时会连续下发内容，实测峰值约
         * 5.6 帧/秒，逐帧整屏重推会让点显器持续闪烁。这里只对「密集」
         * 情况生效：距上次刷新已超过该间隔的内容立即显示（单次焦点变化
         * 零延迟），密集时把上限压到约 4 帧/秒。因为定时器只在没有待
         * 处理内容时启动，事件洪流下延迟也不会超过该间隔。
         */
        private const val CONTENT_MIN_INTERVAL_MILLIS: Long = 250

        private const val MSG_SET_CONTENT = 1
        private const val MSG_RETRANSLATE = 2
        private const val MSG_STOP = 3
    }
}