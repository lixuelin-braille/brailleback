/*
 * Copyright (C) 2013 Google Inc.
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

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.compat.accessibilityservice.AccessibilityServiceCompatUtils
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils

/**
 * Implements a navigation mode which interacts with the input method service.
 * Forwards calls not handled by the hosted IME to another NavigationMode.
 */
class IMENavigationMode @JvmOverloads constructor(
    private val mNext: NavigationMode,
    private val mAccessibilityService: AccessibilityService,
    private val mDisplayManager: DisplayManager,
    private val mFeedbackManager: FeedbackManager,
    private val mSelfBrailleManager: SelfBrailleManager,
    private val mTranslatorManager: TranslatorManager,
    // for testing
    private val mIME: BrailleIME? = null
) : NavigationMode, BrailleIME.Host {

    private enum class State {
        // Disregard all input.
        INACTIVE,
        // Accept text input, but leave navigation alone.
        TEXT_ONLY,
        // Accept text and override navigation controls.
        TEXT_AND_NAVIGATION,
        // Deactivate the underlying mode and present an editor.
        MODAL_EDITOR;

        fun acceptsText(): Boolean = this != INACTIVE

        fun navigatesViaIME(): Boolean = this == MODAL_EDITOR

        fun navigatesByTextGranularity(): Boolean = this == TEXT_AND_NAVIGATION

        fun controlsDisplay(): Boolean = this == MODAL_EDITOR
    }

    private var mState = State.INACTIVE
    private var mActive = false
    private var mNextActive = false
    private var mInputBound = false
    private var mInputStarted = false
    private var mInputViewStarted = false

    /* NavigationMode implementation */

    override fun onActivate() {
        mActive = true
        updateState()
        val ime = getIME()
        if (ime != null && mState.controlsDisplay()) {
            ime.updateDisplay()
            return
        }
        activateNext()
    }

    override fun onDeactivate() {
        mActive = false
        mSelfBrailleManager.setImeOpen(false)
        val ime = getIME()
        if (ime != null && mState.controlsDisplay()) {
            return
        }
        deactivateNext()
    }

    override fun onObserveAccessibilityEvent(event: AccessibilityEvent) {
        if ((event.eventType and UPDATE_STATE_EVENT_MASK) != 0) {
            updateState()
        }
        mNext.onObserveAccessibilityEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
        val ime = getIME()
        if (ime != null && mState.controlsDisplay()) {
            return true
        }
        return mNext.onAccessibilityEvent(event)
    }

    override fun onInvalidateAccessibilityNode(node: AccessibilityNodeInfoCompat) {
        val ime = getIME()
        if (ime != null && mState.controlsDisplay()) {
            return
        }
        mNext.onInvalidateAccessibilityNode(node)
    }

    override fun onPanLeftOverflow(content: DisplayManager.Content): Boolean {
        return mNext.onPanLeftOverflow(content)
    }

    override fun onPanRightOverflow(content: DisplayManager.Content): Boolean {
        return mNext.onPanRightOverflow(content)
    }

    override fun onMappedInputEvent(event: BrailleInputEvent,
            content: DisplayManager.Content): Boolean {
        val ime = getIME()

        // These commands are handled by the IME whenever it is accepting
        // input, even if it has not taken control of the display.
        if (ime != null && mState.acceptsText()) {
            // 唯一输入入口：所有码表下把点字键、上屏、退格、回车、候选切换交给
            // 与屏幕盲文键盘同源的 EditBuffer（经 BrailleInputPolicy 统一按键语义）。
            // 返回 null 表示该按键不归引擎处理（前删、越界点位、导航等），继续原链路；
            // 没有候选时候选键也会返回 null，按键退回原来的光标导航语义。
            val unified = ime.onHardwareInputEvent(event.command, event.argument)
            if (unified != null) {
                return unified
            }
            when (event.command) {
                BrailleInputEvent.CMD_KEY_DEL -> return ime.sendAndroidKey(KeyEvent.KEYCODE_DEL)
                BrailleInputEvent.CMD_KEY_ENTER -> return ime.sendAndroidKey(KeyEvent.KEYCODE_ENTER)
                BrailleInputEvent.CMD_KEY_FORWARD_DEL ->
                    return ime.sendAndroidKey(KeyEvent.KEYCODE_FORWARD_DEL)
                BrailleInputEvent.CMD_BRAILLE_KEY -> return ime.handleBrailleKey(event.argument)
            }
        }

        // If navigation commands are handled by the IME in this state, then
        // move the cursor by the appropriate granularity.
        if (ime != null && mState.navigatesViaIME()) {
            when (event.command) {
                BrailleInputEvent.CMD_NAV_ITEM_PREVIOUS -> return ime.moveCursor(
                        BrailleIME.DIRECTION_BACKWARD,
                        AccessibilityNodeInfoCompat
                                .MOVEMENT_GRANULARITY_CHARACTER)
                BrailleInputEvent.CMD_NAV_ITEM_NEXT -> return ime.moveCursor(
                        BrailleIME.DIRECTION_FORWARD,
                        AccessibilityNodeInfoCompat
                                .MOVEMENT_GRANULARITY_CHARACTER)
                BrailleInputEvent.CMD_NAV_LINE_PREVIOUS ->
                    // Line navigation moves by paragraph since there's no way
                    // of knowing the line extents in the edit text.
                    return ime.moveCursor(
                            BrailleIME.DIRECTION_BACKWARD,
                            AccessibilityNodeInfoCompat
                                    .MOVEMENT_GRANULARITY_PARAGRAPH)
                BrailleInputEvent.CMD_NAV_LINE_NEXT -> return ime.moveCursor(
                        BrailleIME.DIRECTION_FORWARD,
                        AccessibilityNodeInfoCompat
                                .MOVEMENT_GRANULARITY_PARAGRAPH)
            }
        }

        // Alternatively, we may handle these navigation commands by sending
        // "move by granularity" through the accessibility system.
        if (ime != null && mState.navigatesByTextGranularity()) {
            when (event.command) {
                BrailleInputEvent.CMD_NAV_ITEM_PREVIOUS -> return previousAtMovementGranularity(
                        AccessibilityNodeInfoCompat
                                .MOVEMENT_GRANULARITY_CHARACTER)
                BrailleInputEvent.CMD_NAV_ITEM_NEXT -> return nextAtMovementGranularity(
                        AccessibilityNodeInfoCompat
                                .MOVEMENT_GRANULARITY_CHARACTER)
                BrailleInputEvent.CMD_NAV_LINE_PREVIOUS -> return previousAtMovementGranularity(
                        AccessibilityNodeInfoCompat
                                .MOVEMENT_GRANULARITY_LINE)
                BrailleInputEvent.CMD_NAV_LINE_NEXT -> return nextAtMovementGranularity(
                        AccessibilityNodeInfoCompat
                                .MOVEMENT_GRANULARITY_LINE)
            }
        }

        // These commands are handled by the IME only when it has taken
        // control of the display. Otherwise, they are delegated.
        if (ime != null && mState.controlsDisplay()) {
            when (event.command) {
                BrailleInputEvent.CMD_ACTIVATE_CURRENT -> return ime.sendDefaultAction()
                BrailleInputEvent.CMD_ROUTE -> return ime.route(event.argument, content)
            }
        }

        // If all else fails, delegate the event.
        return mNext.onMappedInputEvent(event, content)
    }

    /* BrailleIME.Host implementation */

    // Java 的 Host 接口声明为非空 platform type，但原始实现在翻译器不可用时
    // 会返回 null，这里保持同样的可空语义。
    override fun getBrailleTranslator(): BrailleTranslator? {
        return mTranslatorManager.getTranslator()
    }

    override fun getDisplayManager(): DisplayManager? {
        // Only allow the IME to reach the display manager if the display
        // is controlled by the IME in the current state.
        return if (mState.controlsDisplay()) mDisplayManager else null
    }

    override fun getFeedbackManager(): FeedbackManager {
        return mFeedbackManager
    }

    override fun onCreateIME() {}

    override fun onDestroyIME() {
        mInputBound = false
        mInputStarted = false
        updateState()
    }

    override fun onBindInput() {
        mInputBound = true
        updateState()
    }

    override fun onUnbindInput() {
        mInputBound = false
        updateState()
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        mInputStarted = true
        updateState()
    }

    override fun onFinishInput() {
        mInputStarted = false
        updateState()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        mInputViewStarted = true
        updateState()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        mInputViewStarted = false
        updateState()
    }

    /* Private */

    /** Returns the hosted IME, if any.  */
    private fun getIME(): BrailleIME? {
        return mIME ?: BrailleIME.getActiveInstance()
    }

    /** Changes state, possibly notifying the next navigation mode.  */
    private fun setState(newState: State) {
        if (mState == newState) {
            return
        }

        val oldState = mState
        mState = newState
        if (oldState.controlsDisplay() && !newState.controlsDisplay()) {
            activateNext()
        } else if (!oldState.controlsDisplay() && newState.controlsDisplay()) {
            deactivateNext()
            val ime = getIME()
            ime?.updateDisplay()
        }

        LogUtils.log(this, Log.VERBOSE, "state change: %s -> %s",
                oldState, newState)
    }

    /** Changes states based on new information.  */
    private fun updateState() {
        val imeOpen = isImeOpen()
        if (mActive) {
            mSelfBrailleManager.setImeOpen(imeOpen)
        }
        if (!mInputBound) {
            setState(State.INACTIVE)
        } else if (isModalFieldFocused()) {
            setState(State.MODAL_EDITOR)
        } else if (!isImeOpen()) {
            setState(State.TEXT_ONLY)
        } else if (isSelfBrailledFieldFocused()) {
            setState(State.TEXT_AND_NAVIGATION)
        } else {
            setState(State.TEXT_ONLY)
        }
    }

    // 注意：必须声明为 public。Kotlin 的 internal 函数在 JVM 字节码中会被名字改写
    // （name mangling），DisplayManager.java 将无法按 isImeOpen() 调用。
    fun isImeOpen(): Boolean {
        return mInputStarted && mInputViewStarted
    }

    /** Activates the next navigation mode, suppressing spurious calls.  */
    private fun activateNext() {
        if (!mNextActive) {
            mNextActive = true
            mNext.onActivate()
        }
    }

    /** Deactivates the next navigation mode, suppressing spurious calls.  */
    private fun deactivateNext() {
        if (mNextActive) {
            mNextActive = false
            mNext.onDeactivate()
        }
    }

    /** Returns true if a field suitable for modal editing is focused.  */
    private fun isModalFieldFocused(): Boolean {
        // Only instances of EditText with both input and accessibility focus
        // should be edited modally.
        var root: AccessibilityNodeInfoCompat? = null
        var accessibilityFocused: AccessibilityNodeInfoCompat? = null
        return try {
            root = AccessibilityServiceCompatUtils.getRootInActiveWindow(
                    mAccessibilityService)
            if (root == null) {
                return false
            }
            accessibilityFocused = root.findFocus(
                    AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY)
            (accessibilityFocused != null &&
                    accessibilityFocused.isFocused &&
                    AccessibilityNodeInfoUtils.nodeMatchesClassByType(
                            mAccessibilityService, accessibilityFocused,
                            EditText::class.java))
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(
                    root, accessibilityFocused)
        }
    }

    /** Returns true if a self-brailled node is focused.  */
    private fun isSelfBrailledFieldFocused(): Boolean {
        var root: AccessibilityNodeInfoCompat? = null
        var inputFocused: AccessibilityNodeInfoCompat? = null
        var accessibilityFocused: AccessibilityNodeInfoCompat? = null
        return try {
            root = AccessibilityServiceCompatUtils.getRootInActiveWindow(
                    mAccessibilityService)
            if (root == null) {
                return false
            }
            inputFocused = root.findFocus(
                    AccessibilityNodeInfoCompat.FOCUS_INPUT)
            if (inputFocused == null ||
                    !mSelfBrailleManager.hasContentForNode(inputFocused)) {
                return false
            }
            accessibilityFocused = root.findFocus(
                    AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY)
            inputFocused == accessibilityFocused
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(
                    root, inputFocused, accessibilityFocused)
        }
    }

    /** Returns the node with both accessibility and input focus, if any.  */
    private fun getFocusedNode(): AccessibilityNodeInfoCompat? {
        var root: AccessibilityNodeInfoCompat? = null
        var inputFocused: AccessibilityNodeInfoCompat? = null
        var accessibilityFocused: AccessibilityNodeInfoCompat? = null
        return try {
            root = AccessibilityServiceCompatUtils.getRootInActiveWindow(
                    mAccessibilityService)
            if (root == null) {
                return null
            }
            inputFocused = root.findFocus(
                    AccessibilityNodeInfoCompat.FOCUS_INPUT)
            if (inputFocused == null) {
                return null
            }
            accessibilityFocused = root.findFocus(
                    AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY)
            if (inputFocused == accessibilityFocused) inputFocused else null
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(
                    root, accessibilityFocused)
        }
    }

    /** Asks the focused node to navigate at the requested granularity.  */
    private fun navigateAtMovementGranularity(action: Int,
            granularity: Int): Boolean {
        val arguments = Bundle()
        arguments.putInt(AccessibilityNodeInfoCompat
                .ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
        val node = getFocusedNode() ?: return false
        return try {
            node.performAction(action, arguments)
        } finally {
            node.recycle()
        }
    }

    /** Asks the focused node to navigate at the requested granularity.  */
    private fun nextAtMovementGranularity(granularity: Int): Boolean {
        return navigateAtMovementGranularity(
                AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
                granularity)
    }

    /** Asks the focused node to navigate at the requested granularity.  */
    private fun previousAtMovementGranularity(granularity: Int): Boolean {
        return navigateAtMovementGranularity(
                AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
                granularity)
    }

    private companion object {
        /** Accessibility event types that warrant rechecking the current state.  */
        private const val UPDATE_STATE_EVENT_MASK = (AccessibilityEvent.TYPE_VIEW_FOCUSED
                or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
    }
}
