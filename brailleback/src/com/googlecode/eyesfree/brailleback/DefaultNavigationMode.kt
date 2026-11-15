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

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.brailleback.rule.BrailleRuleRepository
import com.googlecode.eyesfree.brailleback.utils.AccessibilityEventUtils
import com.googlecode.eyesfree.compat.accessibilityservice.AccessibilityServiceCompatUtils
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoRef
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.WebInterfaceUtils

/**
 * Navigation mode that is based on traversing the node tree using
 * accessibility focus.
 */
// TODO: Consolidate parts of this class with similar code in TalkBack.
internal class DefaultNavigationMode(
    private val mDisplayManager: DisplayManager,
    internal val mAccessibilityService: AccessibilityService,
    private val mFeedbackManager: FeedbackManager,
    private val mSelfBrailleManager: SelfBrailleManager,
    private val mNodeBrailler: NodeBrailler,
    private val mRuleRepository: BrailleRuleRepository
) : NavigationMode {

    internal val mFocusFinder = FocusFinder(mAccessibilityService)

    private val mLastFocusedNode = AccessibilityNodeInfoRef()

    internal val mLastScrollableNode = AccessibilityNodeInfoRef()

    internal val mMainHandler = Handler(Looper.getMainLooper())

    internal var mScrollSequence = 0

    internal var mScrollUpdatePending = false

    override fun onPanLeftOverflow(content: Content): Boolean {
        return mFeedbackManager.emitOnFailure(
                onPanLeftOverflowInternal(content),
                FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }

    private fun onPanLeftOverflowInternal(content: Content): Boolean {
        var currentNode: AccessibilityNodeInfoCompat? = null
        var firstNode: AccessibilityNodeInfoCompat? = null
        try {
            // If the currently focused node is a web view, we attempt
            // to delegate navigation to the web view first.
            currentNode = currentFocusedNode(true)
            if (currentNode != null
                    && WebInterfaceUtils.hasLegacyWebContent(currentNode)
                    && WebInterfaceUtils.performNavigationAtGranularityAction(
                            currentNode, DIRECTION_BACKWARD,
                            AccessibilityNodeInfoCompat
                                    .MOVEMENT_GRANULARITY_LINE)) {
                return true
            }

            // Check if we need to scroll.
            if (autoScrollItem(currentNode,
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                return true
            }

            firstNode = AccessibilityNodeInfoUtils.refreshNode(
                    content.getFirstNode())
            // If the content doesn't have a first node, fall back on the
            // currently focused node.
            if (firstNode == null || !firstNode.isVisibleToUser) {
                AccessibilityNodeInfoUtils.recycleNodes(firstNode)
                firstNode = currentNode
                currentNode = null
            }
            val firstNodeToFocus = firstNode ?: return false
            return moveFocus(firstNodeToFocus, DIRECTION_BACKWARD)
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(currentNode, firstNode)
        }
    }

    override fun onPanRightOverflow(content: Content): Boolean {
        return mFeedbackManager.emitOnFailure(
                onPanRightOverflowInternal(content),
                FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }

    private fun onPanRightOverflowInternal(
            content: Content): Boolean {
        val currentNode = currentFocusedNode(true)
        try {
            if (currentNode != null
                    && WebInterfaceUtils.hasLegacyWebContent(currentNode)
                    && WebInterfaceUtils.performNavigationAtGranularityAction(
                            currentNode, DIRECTION_FORWARD,
                            AccessibilityNodeInfoCompat
                                    .MOVEMENT_GRANULARITY_LINE)) {
                return true
            }

            // Check if we need to scroll.
            if (autoScrollItem(currentNode,
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return true
            }
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(currentNode)
        }
        val target = findNodeForPanRight(content)
        if (AccessibilityNodeInfoRef.isNull(target)) {
            return false
        }
        try {
            // 空引用与已回收是同一回事：取不到节点就当作跳转失败。
            return target.get()?.let { focusOrBraille(it) } ?: false
        } finally {
            target.recycle()
        }
    }

    /**
     * Find the first node to focus when panning right, taking into account
     * that some nodes have their children included on the braille display,
     * while some (such as containers) don't.
     */
    private fun findNodeForPanRight(
            content: Content): AccessibilityNodeInfoRef {
        var current = AccessibilityNodeInfoRef.refreshed(
                content.getFirstNode()) ?: AccessibilityNodeInfoRef()
        // Simplify the algorithm by finding the node to stop our traversal
        // at, which is null if the display is at the end of the tree
        // (in pre-order traversal order).
        val sentinel = AccessibilityNodeInfoRef.refreshed(
                content.getLastNode())
        try {
            val currentInfo = current.get()
            if (currentInfo == null
                    || !currentInfo.isVisibleToUser
                    || AccessibilityNodeInfoRef.isNull(sentinel)) {
                current.reset(linearSearch(FocusFinder.SEARCH_FORWARD))
                return current
            }
            // sentinel 在上面已判过非空；收窄成局部后，下一行的 sentinel.get() 才合法（智能转换只对 val 且只对本函数生效）。
            val sentinelRef = requireNotNull(sentinel) {
                "sentinel 在 isNull 判断后被清空"
            }
            nextUpwardsOrClear(sentinelRef)
            while (!AccessibilityNodeInfoRef.isNull(current)
                    && current.get() != sentinelRef.get()) {
                // 每轮重新取：current 会被 reset，不能沿用上一轮的快照。
                val node = current.get()
                val rule = node?.let { mRuleRepository.find(it) }
                // If children of this node are not included on the display,
                // look for focusable descendants.
                if (node == null
                        || rule?.includeChildren(node, mAccessibilityService) != true) {
                    val focusableDescendant = node?.let {
                        FocusFinder.findFirstFocusableDescendant(it, mAccessibilityService)
                    }
                    if (focusableDescendant != null) {
                        current.reset(focusableDescendant)
                        return current
                    }
                    nextUpwardsOrClear(current)
                } else {
                    if (!current.nextInOrder()) {
                        current.clear()
                    }
                }
            }
            if (AccessibilityNodeInfoRef.isNull(current)) {
                if (!AccessibilityNodeInfoRef.isNull(sentinel)) {
                    // We missed the sentinel, which would happen if there is
                    // some node tree change under our feet.  Fall back on a
                    // normal linear forward search so the user doesn't get
                    // stuck.
                    current.reset(linearSearch(FocusFinder.SEARCH_FORWARD))
                } else {
                    // Reached the end of the tree.
                }
            } else {
                // We reached the sentinel, now focus that node or one that we
                // find by linear forward search.
                if (!AccessibilityNodeInfoUtils.shouldFocusNode(
                                mAccessibilityService, current.get())) {
                    current.reset(mFocusFinder.linear(current.get(),
                            FocusFinder.SEARCH_FORWARD))
                }
            }
            return current
        } finally {
            sentinel?.recycle()
        }
    }

    /**
     * Moves `node` to the next sibling, or, if that doesn't succeed,
     * to the next sibling of the parent, recursively, until a next
     * node is found or the root is reached.  In the latter case, `node`
     * is cleared.
     */
    private fun nextUpwardsOrClear(node: AccessibilityNodeInfoRef) {
        if (node.nextSibling()) {
            return
        }
        while (node.parent()) {
            if (node.nextSibling()) {
                return
            }
        }
        node.clear()
    }

    private fun linePrevious(content: Content): Boolean {
        return mFeedbackManager.emitOnFailure(
                linePreviousInternal(content),
                FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }

    /**
     * Moves accessibility focus to the first focusable node of the previous
     * 'line'.
     */
    private fun linePreviousInternal(content: Content): Boolean {
        val left = AccessibilityNodeInfoRef()
        val right = AccessibilityNodeInfoRef()
        var target: AccessibilityNodeInfoCompat? = null
        var currentNode = currentFocusedNode(true)
        try {
            if (currentNode != null
                    && WebInterfaceUtils.hasLegacyWebContent(currentNode)
                    && WebInterfaceUtils.performNavigationAtGranularityAction(
                            currentNode, DIRECTION_BACKWARD,
                            AccessibilityNodeInfoCompat
                                    .MOVEMENT_GRANULARITY_LINE)) {
                return true
            }

            // Check if we need to scroll.
            if (autoScrollItem(currentNode,
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                return true
            }

            var firstNode = AccessibilityNodeInfoRef.unOwned(
                    content.getFirstNode())
            // If the content doesn't have a first node, fall back on the
            // currently focused node.
            if (AccessibilityNodeInfoRef.isNull(firstNode)) {
                firstNode = AccessibilityNodeInfoRef.owned(currentNode)
                currentNode = null
            }
            if (AccessibilityNodeInfoRef.isNull(firstNode)) {
                return false
            }
            // Move backwards one step from the first node that is currently
            // displayed.
            // 上面已判过 isNull，这里再取一次快照；取不到就当作没有可回退的节点。
            val firstNodeInfo = firstNode?.get() ?: return false
            target = mFocusFinder.linear(firstNodeInfo,
                    FocusFinder.SEARCH_BACKWARD)
            firstNode.recycle()
            if (target == null) {
                return false
            }
            // Find what would be covered by the display if target
            // would have accessibility focus.
            mNodeBrailler.findDisplayExtentFromNode(target, left, right)
            // Find the first focusable nodes moving forward from left,
            // i fleft is not focusable itself.
            if (!AccessibilityNodeInfoUtils.shouldFocusNode(
                            mAccessibilityService,
                            left.get())) {
                left.reset(mFocusFinder.linear(left.get(),
                        FocusFinder.SEARCH_FORWARD))
            }
            // If we didn't find a focusable node at the beginning of the
            // line we are trying to move to, just move to target as
            // a fallback.
            if (AccessibilityNodeInfoRef.isNull(left)) {
                left.reset(target)
                target = null
            }
            return left.get()?.let { focusOrBraille(it) } ?: false
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(target, currentNode)
            left.recycle()
            right.recycle()
        }
    }

    private fun lineNext(content: Content): Boolean {
        return onPanRightOverflow(content)
    }

    private fun itemPrevious(): Boolean {
        return mFeedbackManager.emitOnFailure(
                navigateItem(DIRECTION_BACKWARD),
                FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }

    private fun navigateItem(direction: Int): Boolean {
        val currentNode = currentFocusedNode(true)
        try {
            if (currentNode != null
                    && WebInterfaceUtils.hasLegacyWebContent(currentNode)
                    && WebInterfaceUtils.performNavigationByDOMObject(
                            currentNode, direction)) {
                return true
            }
            // Check if we need to scroll.
            val scrollDirection = if (direction == DIRECTION_FORWARD)
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (autoScrollItem(currentNode, scrollDirection)) {
                return true
            }
            return moveFocus(currentNode, direction)
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(currentNode)
        }
    }

    private fun itemNext(): Boolean {
        return mFeedbackManager.emitOnFailure(
                navigateItem(DIRECTION_FORWARD),
                FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }

    private fun navigateHTMLElementAction(direction: Int, element: String): Boolean {
        val currentNode = currentFocusedNode(true)
        try {
            if (currentNode != null
                    && WebInterfaceUtils.supportsWebActions(currentNode)
                    && WebInterfaceUtils.performNavigationToHtmlElementAction(
                            currentNode, direction, element)) {
                return true
            }
            return false
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(currentNode)
        }
    }

    private fun handleIncrementalSearchAction(): Boolean {
        val currentNode = currentFocusedNode(true)
        try {
            if (currentNode != null
                    && WebInterfaceUtils.hasLegacyWebContent(currentNode)
                    && mFeedbackManager.emitOnFailure(
                            WebInterfaceUtils.performSpecialAction(currentNode,
                                    ACTION_TOGGLE_INCREMENTAL_SEARCH),
                            FeedbackManager.TYPE_COMMAND_FAILED)) {
                return true
            }
            return false
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(currentNode)
        }
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

    private fun moveFocus(from: AccessibilityNodeInfoCompat?,
                          direction: Int): Boolean {
        val searchDirection = if (direction == DIRECTION_BACKWARD)
            FocusFinder.SEARCH_BACKWARD
        else
            FocusFinder.SEARCH_FORWARD
        val next = mFocusFinder.linear(from, searchDirection)
        try {
            if (next != null) {
                return focusOrBraille(next)
            }
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(next)
        }
        return false
    }

    private fun getFocusedNode(
            fallbackOnRoot: Boolean): AccessibilityNodeInfoCompat? {
        return FocusFinder.getFocusedNode(mAccessibilityService,
                fallbackOnRoot)
    }

    /**
     * Returns the node that braille navigation should move from.
     *
     * In braille-only mode this is the mode's own logical focus, which is kept
     * separately from system accessibility focus so TalkBack does not announce
     * every braille movement.  Outside that mode it is the system focus, as in
     * the original BrailleBack behaviour.
     */
    private fun currentFocusedNode(
            fallbackOnRoot: Boolean): AccessibilityNodeInfoCompat? {
        if (isQuietNavigationEnabled()) {
            val current = mLastFocusedNode.get()
            if (current != null) {
                val refreshed = AccessibilityNodeInfoUtils.refreshNode(current)
                if (refreshed != null && refreshed.isVisibleToUser) {
                    return refreshed
                }
                AccessibilityNodeInfoUtils.recycleNodes(refreshed)
                // In braille-only mode the system focus often belongs to
                // another screen reader or to a control such as an edit text
                // at the bottom of the screen.  Never let that focus hijack
                // braille navigation when the logical focus was scrolled out
                // of view.  Stay inside the last scrollable container, or
                // keep the current display while a scroll update is pending.
                if (!mScrollUpdatePending) {
                    val fallback = findFocusableInLastScrollContainer()
                    if (fallback != null) {
                        return fallback
                    }
                }
                return null
            }
        }
        return getFocusedNode(fallbackOnRoot)
    }

    /**
     * Moves the system accessibility focus, or in braille-only mode updates
     * only the braille display and the mode's logical focus.
     */
    internal fun focusOrBraille(node: AccessibilityNodeInfoCompat): Boolean {
        if (!isQuietNavigationEnabled()) {
            return node.performAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        }
        rememberScrollContainer(node)
        val content = mNodeBrailler.brailleNode(node, node) ?: return false
        content.setPanStrategy(Content.PAN_CURSOR)
        mLastFocusedNode.reset(AccessibilityNodeInfoCompat.obtain(node))
        mDisplayManager.setContent(content)
        return true
    }

    internal fun isQuietNavigationEnabled(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(mAccessibilityService)
                .getBoolean(
                        mAccessibilityService.getString(R.string.pref_quiet_navigation_key),
                        mAccessibilityService.resources.getBoolean(
                                R.bool.pref_quiet_navigation_default))
    }

    private fun activateNode(node: AccessibilityNodeInfoCompat?): Boolean {
        return activateNode(node, -1)
    }

    private fun activateNode(node: AccessibilityNodeInfoCompat?,
                             position: Int): Boolean {
        if (node == null) {
            return false
        }
        val current = AccessibilityNodeInfoRef.unOwned(node)
                ?: return false
        try {
            do {
                val cur = current.get() ?: break
                LogUtils.log(this, Log.VERBOSE,
                        "Considering to click: %s",
                        cur.getInfo())
                val supportedActions = cur.actions
                var action = 0
                // For edit texts, the click action doesn't currently focus
                // the view, so we special case it here.
                // TODO: Revise when that changes.
                if (AccessibilityNodeInfoUtils.nodeMatchesClassByType(
                                mAccessibilityService, cur,
                                EditText::class.java)) {
                    action = if ((supportedActions and
                                    AccessibilityNodeInfo.ACTION_FOCUS) != 0) {
                        AccessibilityNodeInfo.ACTION_FOCUS
                    } else {
                        // Put accessibility focus on the field.  If it is
                        // already focused and the IME is selected, that will
                        // activate the editing.
                        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                    }
                } else if (position >= 0 && isSelfBrailled(cur)
                        && (supportedActions
                                and AccessibilityNodeInfo.ACTION_CLICK) != 0) {
                    // Generate a fake "action". For instance, a click at
                    // position 33 maps to -275000033.
                    // TODO: Remove this hack when a better way to pass this
                    // information exists.
                    var fakeAction = ACTION_BRAILLE_CLICK_MAX - position
                    if (fakeAction < ACTION_BRAILLE_CLICK_MIN) {
                        LogUtils.log(this, Log.WARN,
                                "underflow activating node %s at position %d",
                                cur, position)
                        fakeAction = ACTION_BRAILLE_CLICK_MIN
                    } else if (fakeAction > ACTION_BRAILLE_CLICK_MAX) {
                        LogUtils.log(this, Log.WARN,
                                "overflow activating node %s at position %d",
                                cur, position)
                        fakeAction = ACTION_BRAILLE_CLICK_MAX
                    }
                    if (WebInterfaceUtils.performSpecialAction(cur,
                                    fakeAction)) {
                        return true
                    }
                } else if ((supportedActions
                                and AccessibilityNodeInfo.ACTION_CLICK) != 0) {
                    action = AccessibilityNodeInfo.ACTION_CLICK
                }
                if (action != 0 && cur.performAction(action)) {
                    return true
                } else {
                    LogUtils.log(this, Log.VERBOSE, "Action %d failed",
                            action)
                }
            } while (current.parent())
        } finally {
            current.recycle()
        }
        LogUtils.log(this, Log.VERBOSE, "Click action failed")
        return false
    }

    private fun longClickNode(node: AccessibilityNodeInfoCompat?): Boolean {
        if (node == null) {
            return false
        }
        val current = AccessibilityNodeInfoRef.unOwned(node)
                ?: return false
        try {
            do {
                val cur = current.get() ?: break
                LogUtils.log(this, Log.VERBOSE,
                        "Considering to long click: %s",
                        cur.getInfo())
                if (cur.performAction(
                                AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    return true
                }
            } while (current.parent())
        } finally {
            current.recycle()
        }
        LogUtils.log(this, Log.VERBOSE, "Long click action failed")
        return false
    }

    /**
     * Formats some braille content from an [AccessibilityEvent].
     *
     * @param event The event from which to format an utterance.
     * @return The formatted utterance.
     */
    private fun formatEventToBraille(
            event: AccessibilityEvent): Content {
        val eventNode = getNodeFromEvent(event)
        if (eventNode != null) {
            val ret = mNodeBrailler.brailleNode(eventNode)
            if (ret != null) {
                ret.setPanStrategy(Content.PAN_CURSOR)
                mLastFocusedNode.reset(eventNode)
                return ret
            }
        }

        // Fall back on putting the event text on the display.
        // TODO: This can interfere with what's on the display and should be
        // done in a more disciplined manner.
        LogUtils.log(this, Log.VERBOSE,
                "No node on event, falling back on event text")
        mLastFocusedNode.clear()
        return Content(
                AccessibilityEventUtils.getEventText(event))
    }

    private fun brailleNodeFromEvent(event: AccessibilityEvent) {
        mDisplayManager.setContent(
                formatEventToBraille(event))
    }

    private fun brailleFocusedNode(): Boolean {
        val focused = currentFocusedNode(false) ?: return false
        try {
            val quiet = isQuietNavigationEnabled()
            val content = mNodeBrailler.brailleNode(
                    focused, if (quiet) focused else null) ?: return false
            if (focused == mLastFocusedNode.get()
                    && content.getPanStrategy()
                    == Content.PAN_RESET) {
                content.setPanStrategy(Content.PAN_KEEP)
            }
            mDisplayManager.setContent(content)
            mLastFocusedNode.reset(AccessibilityNodeInfoCompat.obtain(focused))
            return true
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(focused)
        }
    }

    private fun brailleFirstFocusableNode() {
        val unwrappedRoot = mAccessibilityService.getRootInActiveWindow()
                ?: return
        var root: AccessibilityNodeInfoCompat? =
                AccessibilityNodeInfoCompat(unwrappedRoot)
        var toBraille: AccessibilityNodeInfoCompat? = null
        if (AccessibilityNodeInfoUtils.shouldFocusNode(
                        mAccessibilityService, root)) {
            toBraille = root
            root = null
        } else {
            toBraille = mFocusFinder.linear(root,
                    FocusFinder.SEARCH_FORWARD)
            if (toBraille == null) {
                // Fall back on root as a last resort.
                toBraille = root
                root = null
            }
        }
        val quiet = isQuietNavigationEnabled()
        // 走到这里 toBraille 必非空，但跨 if-else 的赋值不在智能转换范围内，显式收窄。
        val brailleTarget = toBraille ?: return
        val content = mNodeBrailler.brailleNode(
                brailleTarget, if (quiet) brailleTarget else null)
        if (content != null) {
            if (AccessibilityNodeInfoRef.isNull(mLastFocusedNode)
                    && content.getPanStrategy()
                    == Content.PAN_RESET) {
                content.setPanStrategy(Content.PAN_KEEP)
            }
            mDisplayManager.setContent(content)
            if (quiet) {
                mLastFocusedNode.reset(AccessibilityNodeInfoCompat.obtain(brailleTarget))
            }
        }
        if (!quiet) {
            mLastFocusedNode.clear()
        }
        AccessibilityNodeInfoUtils.recycleNodes(root, toBraille)
    }

    private fun getNodeFromEvent(
            event: AccessibilityEvent): AccessibilityNodeInfoCompat? {
        val node = event.source ?: return null
        return AccessibilityNodeInfoCompat(node)
    }

    private fun linearSearch(direction: Int): AccessibilityNodeInfoCompat? {
        val source = currentFocusedNode(true)
        val result = mFocusFinder.linear(source, direction)
        AccessibilityNodeInfoUtils.recycleNodes(source)
        return result
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

    private fun isSelfBrailled(node: AccessibilityNodeInfoCompat): Boolean {
        return mSelfBrailleManager.hasContentForNode(node)
    }

    /**
     * Attempts to navigate to the top-most focusable node in the tree.
     */
    private fun attemptNavigateTop(): Boolean {
        var root: AccessibilityNodeInfoCompat? =
                AccessibilityServiceCompatUtils.getRootInActiveWindow(
                        mAccessibilityService)
        var toFocus: AccessibilityNodeInfoCompat? = null
        if (AccessibilityNodeInfoUtils.shouldFocusNode(
                        mAccessibilityService, root)) {
            toFocus = root
            root = null
        } else {
            toFocus = mFocusFinder.linear(root, FocusFinder.SEARCH_FORWARD)
            if (toFocus == null) {
                // Fall back on root as a last resort.
                toFocus = root
                root = null
            }
        }

        try {
            val target = toFocus ?: return false
            if (!isQuietNavigationEnabled() && target.isAccessibilityFocused) {
                brailleFocusedNode()
                return true
            }

            return focusOrBraille(target)
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(root, toFocus)
        }
    }

    /**
     * Attempts to navigate to the bottom-most focusable node in the tree.
     */
    private fun attemptNavigateBottom(): Boolean {
        var root: AccessibilityNodeInfoCompat? =
                AccessibilityServiceCompatUtils.getRootInActiveWindow(
                        mAccessibilityService)
        var toFocus: AccessibilityNodeInfoCompat? =
                FocusFinder.findLastFocusableDescendant(root,
                        mAccessibilityService)

        try {
            if (toFocus == null) {
                if (AccessibilityNodeInfoUtils.shouldFocusNode(
                                mAccessibilityService, root)) {
                    root = null
                    toFocus = root
                } else {
                    return false
                }
            }

            val target = toFocus ?: return false
            if (!isQuietNavigationEnabled() && target.isAccessibilityFocused) {
                brailleFocusedNode()
                return true
            }

            return focusOrBraille(target)
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(root, toFocus)
        }
    }

    /**
     * Checks if the specified node is suitable for autoscrolling and will
     * try to scroll it if it is.
     * Note we might have an issue here when more than one node is on the line
     * or not the whole list item is visible.
     */

    private companion object {
        private const val DIRECTION_BACKWARD =
                WebInterfaceUtils.DIRECTION_BACKWARD
        private const val DIRECTION_FORWARD =
                WebInterfaceUtils.DIRECTION_FORWARD

        private const val HTML_ELEMENT_MOVE_BY_SECTION =
                WebInterfaceUtils.HTML_ELEMENT_MOVE_BY_SECTION
        private const val HTML_ELEMENT_MOVE_BY_CONTROL =
                WebInterfaceUtils.HTML_ELEMENT_MOVE_BY_CONTROL
        private const val HTML_ELEMENT_MOVE_BY_LIST =
                WebInterfaceUtils.HTML_ELEMENT_MOVE_BY_LIST

        private const val ACTION_TOGGLE_INCREMENTAL_SEARCH =
                WebInterfaceUtils.ACTION_TOGGLE_INCREMENTAL_SEARCH

        // Actions in this range are reserved for Braille use.
        // TODO: use event arguments instead of this hack.
        private const val ACTION_BRAILLE_CLICK_MAX = -275000000
        private const val ACTION_BRAILLE_CLICK_MIN = -275999999
    }
}
