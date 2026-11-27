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

/**
 * 滚动后的焦点冷却窗口（毫秒）：滚动/翻页会让系统把无障碍焦点重置到新一屏
 * 的第一行，这些自动重置都落在 settle 期间和 settle 之后的一小段时间里。
 * settle 最坏要三次尝试（80 + 120 + 180），留到 500 能全覆盖；用户翻页后
 * 再用手去滑读屏，通常早过了这个窗口，所以正常触摸浏览不受影响。
 */
internal const val SCROLL_FOCUS_COOLDOWN_MILLIS = 500L

/**
 * 系统的无障碍焦点事件该不该跟。
 *
 * 只挡「滚动引起的自动焦点重置」，不挡用户自己的操作：翻页/滚屏时系统会把焦点
 * 重置到新一屏第一行，跟着刷会先闪错帧、settle 后再闪对帧，摸起来就是跳两下，
 * 有时直接停在第一行。用户用手指滑读屏、或读屏自己移动焦点时，距离上次滚动
 * 早已超出冷却窗口，照常跟随。
 *
 * @param scrollPending 滚动更新是否还没落定（settle 未完成）。
 * @param sinceScrollMillis 距上次滚动动作开始过了多少毫秒；从未滚动时传一个很大的值。
 */
internal fun shouldFollowFocusEvent(
        scrollPending: Boolean,
        sinceScrollMillis: Long): Boolean {
    if (scrollPending) {
        return false
    }
    return sinceScrollMillis >= SCROLL_FOCUS_COOLDOWN_MILLIS
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

    /** 最近一次滚动动作的开始时刻（uptime）；0 表示这次会话还没滚动过。 */
    internal var mLastScrollActionAt = 0L

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
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // 读屏自己的手势滚动和惯性滚动不经过 attemptScrollAction，
                // 但系统滚完同样会把焦点重置到新一屏首行，这里统一记冷却起点。
                mLastScrollActionAt = SystemClock.uptimeMillis()
                return true
            }
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                if (!shouldFollowFocusEvent(mScrollUpdatePending, sinceScrollMillis())) {
                    scheduleCooldownRefresh()
                    return true
                }
                // 事件节点此刻还活着，首帧可以直接用它；被合并的那一帧等到期时
                // 事件对象已回收，改取「当前焦点」，避免读到失效节点。
                scheduleFocusBraille(fallback = true) { brailleNodeFromEvent(event) }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!shouldFollowFocusEvent(mScrollUpdatePending, sinceScrollMillis())) {
                    scheduleCooldownRefresh()
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
    private fun scheduleCooldownRefresh() {
        mScrollGuardRunnable?.let { mMainHandler.removeCallbacks(it) }
        val at = mLastScrollActionAt
        val elapsed = if (at == 0L) {
            SCROLL_FOCUS_COOLDOWN_MILLIS
        } else {
            SystemClock.uptimeMillis() - at
        }
        val delay = (SCROLL_FOCUS_COOLDOWN_MILLIS - elapsed).coerceAtLeast(0L)
        val runnable = Runnable {
            mScrollGuardRunnable = null
            // 滚动 settle 还没落定：让它自己刷正确的那一帧，这里不抢，
            // 否则会把 settle 选好的行覆盖成系统焦点所在的首行。
            if (mScrollUpdatePending) {
                return@Runnable
            }
            performSettledBraille(fallback = false)
        }
        mScrollGuardRunnable = runnable
        mMainHandler.postDelayed(runnable, delay)
    }

    /** 距上次滚动动作过了多少毫秒；从未滚动过时返回最大值，表示不设防。 */
    private fun sinceScrollMillis(): Long = if (mLastScrollActionAt == 0L) {
        Long.MAX_VALUE
    } else {
        SystemClock.uptimeMillis() - mLastScrollActionAt
    }

    /** settle 已经上屏了正确的一帧，冷却补帧没有存在的必要，撤掉。 */
    internal fun cancelCooldownRefresh() {
        mScrollGuardRunnable?.let { mMainHandler.removeCallbacks(it) }
        mScrollGuardRunnable = null
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
