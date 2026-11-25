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

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoRef
import com.linxi.brailleback.rule.BrailleRuleRepository
import com.googlecode.eyesfree.utils.WebInterfaceUtils

internal const val DIRECTION_BACKWARD =
        WebInterfaceUtils.DIRECTION_BACKWARD
internal const val DIRECTION_FORWARD =
        WebInterfaceUtils.DIRECTION_FORWARD

internal const val HTML_ELEMENT_MOVE_BY_SECTION =
        WebInterfaceUtils.HTML_ELEMENT_MOVE_BY_SECTION
internal const val HTML_ELEMENT_MOVE_BY_CONTROL =
        WebInterfaceUtils.HTML_ELEMENT_MOVE_BY_CONTROL
internal const val HTML_ELEMENT_MOVE_BY_LIST =
        WebInterfaceUtils.HTML_ELEMENT_MOVE_BY_LIST

/**
 * Navigation mode that is based on traversing the node tree using
 * accessibility focus.
 */
// TODO: Consolidate parts of this class with similar code in TalkBack.
internal class DefaultNavigationMode(
    internal val mDisplayManager: DisplayManager,
    internal val mAccessibilityService: AccessibilityService,
    internal val mFeedbackManager: FeedbackManager,
    internal val mSelfBrailleManager: SelfBrailleManager,
    internal val mNodeBrailler: NodeBrailler,
    internal val mRuleRepository: BrailleRuleRepository
) : NavigationMode {

    internal val mFocusFinder = FocusFinder(mAccessibilityService)

    internal val mLastFocusedNode = AccessibilityNodeInfoRef()

    internal val mLastScrollableNode = AccessibilityNodeInfoRef()

    internal val mMainHandler = Handler(Looper.getMainLooper())

    internal var mScrollSequence = 0

    internal var mScrollUpdatePending = false

    override fun onPanLeftOverflow(content: Content): Boolean {
        return mFeedbackManager.emitOnFailure(
                onPanLeftOverflowInternal(content),
                FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }

    override fun onPanRightOverflow(content: Content): Boolean {
        return mFeedbackManager.emitOnFailure(
                onPanRightOverflowInternal(content),
                FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }

    override fun onMappedInputEvent(event: BrailleInputEvent,
                                    content: Content): Boolean {
        when (event.command) {
            BrailleInputEvent.CMD_NAV_ITEM_PREVIOUS ->
                return itemPrevious()
            BrailleInputEvent.CMD_NAV_ITEM_NEXT ->
                return itemNext()
            BrailleInputEvent.CMD_NAV_LINE_PREVIOUS ->
                return linePrevious(content)
            BrailleInputEvent.CMD_NAV_LINE_NEXT ->
                return lineNext(content)
            BrailleInputEvent.CMD_ACTIVATE_CURRENT -> {
                // Activate the current node, but don't fall back on the
                // root if focus is cleared.
                return mFeedbackManager.emitOnFailure(
                        activateNode(currentFocusedNode(false)),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            }
            BrailleInputEvent.CMD_LONG_PRESS_CURRENT -> {
                // Long click the current node, but don't fall back on the
                // root if focus is cleared.
                return mFeedbackManager.emitOnFailure(
                        longClickNode(currentFocusedNode(false)),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            }
            BrailleInputEvent.CMD_ROUTE -> {
                val node = DisplaySpans.getAccessibilityNodeFromPosition(
                        event.argument, content.getText() ?: "")
                return mFeedbackManager.emitOnFailure(
                        activateNode(node, event.argument),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            }
            BrailleInputEvent.CMD_LONG_PRESS_ROUTE -> {
                val node = DisplaySpans.getAccessibilityNodeFromPosition(
                        event.argument, content.getText() ?: "")
                return mFeedbackManager.emitOnFailure(
                        longClickNode(node),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            }
            BrailleInputEvent.CMD_SCROLL_FORWARD ->
                return mFeedbackManager.emitOnFailure(
                        attemptScrollAction(
                                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            BrailleInputEvent.CMD_SCROLL_BACKWARD ->
                return mFeedbackManager.emitOnFailure(
                        attemptScrollAction(
                                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            BrailleInputEvent.CMD_NAV_TOP ->
                return mFeedbackManager.emitOnFailure(
                        attemptNavigateTop(), FeedbackManager.TYPE_COMMAND_FAILED)
            BrailleInputEvent.CMD_NAV_BOTTOM ->
                return mFeedbackManager.emitOnFailure(
                        attemptNavigateBottom(),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            BrailleInputEvent.CMD_SECTION_NEXT ->
                return mFeedbackManager.emitOnFailure(
                        navigateHTMLElementAction(DIRECTION_FORWARD,
                                HTML_ELEMENT_MOVE_BY_SECTION),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            BrailleInputEvent.CMD_SECTION_PREVIOUS ->
                return mFeedbackManager.emitOnFailure(
                        navigateHTMLElementAction(DIRECTION_BACKWARD,
                                HTML_ELEMENT_MOVE_BY_SECTION),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            BrailleInputEvent.CMD_CONTROL_NEXT ->
                return mFeedbackManager.emitOnFailure(
                        navigateHTMLElementAction(DIRECTION_FORWARD,
                                HTML_ELEMENT_MOVE_BY_CONTROL),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            BrailleInputEvent.CMD_CONTROL_PREVIOUS ->
                return mFeedbackManager.emitOnFailure(
                        navigateHTMLElementAction(DIRECTION_BACKWARD,
                                HTML_ELEMENT_MOVE_BY_CONTROL),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            BrailleInputEvent.CMD_LIST_NEXT ->
                return mFeedbackManager.emitOnFailure(
                        navigateHTMLElementAction(DIRECTION_FORWARD,
                                HTML_ELEMENT_MOVE_BY_LIST),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            BrailleInputEvent.CMD_LIST_PREVIOUS ->
                return mFeedbackManager.emitOnFailure(
                        navigateHTMLElementAction(DIRECTION_BACKWARD,
                                HTML_ELEMENT_MOVE_BY_LIST),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            BrailleInputEvent.CMD_TOGGLE_INCREMENTAL_SEARCH ->
                return handleIncrementalSearchAction()
        }
        return false
    }

    override fun onActivate() {
        mLastFocusedNode.clear()
        // Braille the focused node, or if that fails, braille
        // the first focusable node.
        if (!brailleFocusedNode()) {
            brailleFirstFocusableNode()
        }
    }

    override fun onDeactivate() {
        mScrollSequence++
        mScrollUpdatePending = false
        mLastScrollableNode.clear()
    }

    override fun onObserveAccessibilityEvent(event: AccessibilityEvent) {
        // Nothing to do.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ->
                brailleNodeFromEvent(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                brailleFocusedNode()
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!brailleFocusedNode()) {
                    // Since focus is typically not set in a newly opened
                    // window, so braille the window as-if the first focusable
                    // node had focus.  We don't update the focus because that
                    // will make other services (e.g. talkback) reflect this
                    // change, which is not desired.
                    brailleFirstFocusableNode()
                }
            }
        }
        return true
    }

    override fun onInvalidateAccessibilityNode(
            node: AccessibilityNodeInfoCompat) {
        brailleFocusedNode()
    }

    /**
     * Attempts to scroll using the specified action.
     */
    internal fun attemptScrollAction(action: Int): Boolean {
        var focusedNode: AccessibilityNodeInfoCompat? = null
        var scrollableNode: AccessibilityNodeInfoCompat? = null

        try {
            focusedNode = currentFocusedNode(false) ?: return false

            scrollableNode =
                    AccessibilityNodeInfoUtils.getSelfOrMatchingAncestor(
                            mAccessibilityService, focusedNode,
                            AccessibilityNodeInfoUtils.FILTER_SCROLLABLE)
            scrollableNode ?: return false

            return scrollableNode.performAction(action)
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(focusedNode,
                    scrollableNode)
        }
    }
}
