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
import android.os.SystemClock
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
 * 焦点稳定窗口（毫秒）：系统让无障碍焦点在两个节点间来回跳时，窗口内的
 * 连续焦点事件只补刷最后一帧。首帧不受影响，所以单步移动的响应不变。
 * 取 120 是因为它低于人能察觉的「迟滞」门限，又足够吞掉一次自绘控件抖动；
 * 再大的话，连续换焦点会明显发木。
 */
internal const val FOCUS_SETTLE_MILLIS = 120L

/**
 * 滚动兜底窗口（毫秒）：滚动更新期间压下的焦点事件，若 settle 回调因为序列
 * 变化提前退出、`mScrollUpdatePending` 没被清，至少要有一帧能补上来。
 * 补上来的帧与已上屏内容相同时会被内容指纹挡掉，不会多闪一次。
 */
internal const val SCROLL_GUARD_MILLIS = 400L

/**
 * 系统的无障碍焦点事件该不该跟。
 *
 * 两种不跟的情况：
 *
 * 1. [scrollPending]：滚动更新还没落定。系统在这一刻会把焦点重置到新一屏的
 *    第一行，跟着它刷会先闪一帧错的，settle 完成后再刷一帧对的，摸起来就是
 *    「跳两下」，有时直接停在第一行。
 * 2. [quiet] 且 [hasLogicalFocus]：盲文导航自己维护逻辑焦点，系统焦点可能属于
 *    别的读屏，或是页面自动重置的结果，抢过来会把盲文拽回第一行。
 */
internal fun shouldFollowFocusEvent(
        scrollPending: Boolean,
        quiet: Boolean,
        hasLogicalFocus: Boolean): Boolean {
    if (scrollPending) {
        return false
    }
    return !(quiet && hasLogicalFocus)
}

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

    /**
     * 焦点稳定闸门：空闲后的第一帧立刻上屏，同一窗口内的后续焦点事件合并成一帧。
     * 只在「系统让焦点来回跳」时起作用，正常单步浏览不会变慢。
     */
    private val mFocusSettleGate = FocusSettleGate(FOCUS_SETTLE_MILLIS)

    /** 是否已有一个补刷帧排在主线程队列里（队列里有就不再重复排）。 */
    private var mSettlePending = false

    /** 补刷时若取不到焦点节点，是否退到「第一个可聚焦节点」。 */
    private var mSettleFallback = false

    /** 滚动期间压下焦点事件时排的兜底帧；没有在排就为 null。 */
    private var mScrollGuardRunnable: Runnable? = null

    private val mSettleRunnable = Runnable {
        mSettlePending = false
        val fallback = mSettleFallback
        mSettleFallback = false
        mFocusSettleGate.onFlushed()
        performSettledBraille(fallback)
    }

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
        cancelSettle()
        mFocusSettleGate.reset()
        mScrollSequence++
        mScrollUpdatePending = false
        mLastScrollableNode.clear()
    }

    override fun onObserveAccessibilityEvent(event: AccessibilityEvent) {
        // Nothing to do.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                if (!shouldFollowFocusEvent(
                                mScrollUpdatePending,
                                isQuietNavigationEnabled(),
                                !AccessibilityNodeInfoRef.isNull(mLastFocusedNode))) {
                    scheduleScrollGuard(fallback = true)
                    return true
                }
                // 事件节点此刻还活着，首帧可以直接用它；被合并的那一帧等到期时
                // 事件对象已回收，改取「当前焦点」，避免读到失效节点。
                scheduleFocusBraille(fallback = true) { brailleNodeFromEvent(event) }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (mScrollUpdatePending) {
                    scheduleScrollGuard(fallback = false)
                    return true
                }
                scheduleFocusBraille(fallback = false) { brailleFocusedNode() }
            }
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

    /**
     * 焦点相关事件的统一入口：第一帧立刻执行 [immediate]，同一窗口内的后续事件
     * 合并成一次补刷。补刷取「当前焦点」而不是事件节点，见
     * [AccessibilityEvent] 的回收规则。
     */
    private fun scheduleFocusBraille(fallback: Boolean, immediate: () -> Unit) {
        mSettleFallback = mSettleFallback || fallback
        val delay = mFocusSettleGate.onEvent(SystemClock.uptimeMillis())
        if (delay <= 0L) {
            // 首帧放行：队列里若有更早的补刷，它的内容会被这一帧取代。
            cancelSettle()
            immediate()
            return
        }
        if (mSettlePending) {
            return
        }
        mSettlePending = true
        mMainHandler.postDelayed(mSettleRunnable, delay)
    }

    private fun cancelSettle() {
        mSettlePending = false
        mSettleFallback = false
        mMainHandler.removeCallbacks(mSettleRunnable)
        mScrollGuardRunnable?.let { mMainHandler.removeCallbacks(it) }
        mScrollGuardRunnable = null
    }

    /** 补刷：优先当前焦点节点，取不到时按调用场景决定是否退到第一个可聚焦节点。 */
    private fun performSettledBraille(fallback: Boolean) {
        if (!brailleFocusedNode() && fallback) {
            brailleFirstFocusableNode()
        }
    }

    /**
     * 滚动期间压下的焦点事件留一帧兜底，避免 settle 回调提前退出后屏上再也不刷新。
     * 是否真的补，取决于到期时滚动是否已落定。
     */
    private fun scheduleScrollGuard(fallback: Boolean) {
        mScrollGuardRunnable?.let { mMainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            mScrollGuardRunnable = null
            if (!mScrollUpdatePending) {
                performSettledBraille(fallback)
            }
        }
        mScrollGuardRunnable = runnable
        mMainHandler.postDelayed(runnable, SCROLL_GUARD_MILLIS)
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
