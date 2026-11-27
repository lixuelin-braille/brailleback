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
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.linxi.brailleback.rule.BrailleRuleRepository
import com.linxi.brailleback.utils.AccessibilityEventUtils
import com.googlecode.eyesfree.compat.accessibilityservice.AccessibilityServiceCompatUtils
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoRef
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.WebInterfaceUtils

/**
 * 滚动与可见性辅助：从 DefaultNavigationMode 拆出来的第二半。
 *
 * 这些函数要读 mFocusFinder / mMainHandler / mLastScrollableNode 与滚动序号，
 * 所以写成同包扩展函数（internal），避免把每个 mXxx 都改写成导航器.xxx。
 */

internal class NodeSnapshot(
    val node: AccessibilityNodeInfoCompat,
    val bounds: Rect,
    val focusable: Boolean
)

private const val MAX_VISIBLE_NODE_DEPTH = 40
private const val MAX_VISIBLE_NODE_COUNT = 400
private val SCROLL_UPDATE_DELAYS_MS = longArrayOf(80L, 120L, 180L)

internal fun DefaultNavigationMode.autoScrollItem(node: AccessibilityNodeInfoCompat?,
                           scrollDirection: Int): Boolean {
    if (scrollAtEdge(node, scrollDirection)) {
        return true
    }
    val edgeDirection =
            if (scrollDirection == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                1
            else
                -1
    return AccessibilityNodeInfoUtils.isAutoScrollEdgeListItem(
            mAccessibilityService, node, edgeDirection)
            && attemptScrollAction(scrollDirection)
}

/**
 * Scrolls the nearest scrollable ancestor when there is no reachable
 * focusable node left in the requested direction inside it.
 *
 * [AccessibilityNodeInfoUtils.isAutoScrollEdgeListItem] only recognizes
 * the legacy list and scroll view classes.  Modern containers
 * (RecyclerView, Compose scrollables, WebView and vendor list widgets)
 * therefore fell through to [findNodeForPanRight] and moved the braille
 * focus to a control outside the list, such as an edit text or a button
 * below the content.  This check is class agnostic and keeps the focus
 * inside the scrollable area whenever it can still scroll.
 */
internal fun DefaultNavigationMode.scrollAtEdge(
        currentNode: AccessibilityNodeInfoCompat?,
        scrollDirection: Int): Boolean {
    if (currentNode == null) {
        return false
    }
    val forward =
            scrollDirection == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    val container = AccessibilityNodeInfoUtils.getSelfOrMatchingAncestor(
            mAccessibilityService, currentNode,
            AccessibilityNodeInfoUtils.FILTER_SCROLLABLE) ?: return false
    var before: List<NodeSnapshot> = emptyList()
    try {
        if (currentNode != container
                && hasVisibleFocusableAhead(currentNode, container,
                        forward)) {
            return false
        }
        before = collectVisibleNodes(container)
        val beforeBounds = before.map { Rect(it.bounds) }
        val refreshed = AccessibilityNodeInfoUtils.refreshNode(container)
        val scrollable = refreshed ?: container
        try {
            if (!scrollable.performAction(scrollDirection)) {
                return false
            }
            mLastScrollableNode.reset(
                    AccessibilityNodeInfoCompat.obtain(scrollable))
            scheduleBrailleAfterScroll(
                    scrollable, currentNode, forward, beforeBounds)
            LogUtils.log(this, Log.VERBOSE,
                    "Auto-scroll started: forward=%s className=%s",
                    forward, scrollable.className)
            return true
        } finally {
            if (refreshed != null && refreshed !== container) {
                refreshed.recycle()
            }
        }
    } finally {
        recycleSnapshots(before)
        container.recycle()
    }
}

internal fun DefaultNavigationMode.hasVisibleFocusableAhead(
        currentNode: AccessibilityNodeInfoCompat,
        container: AccessibilityNodeInfoCompat,
        forward: Boolean): Boolean {
    if (currentNode == container) {
        // All descendants are part of the container's displayed content.
        return false
    }
    val searchDirection = if (forward) {
        FocusFinder.SEARCH_FORWARD
    } else {
        FocusFinder.SEARCH_BACKWARD
    }
    val next = mFocusFinder.linear(currentNode, searchDirection)
            ?: return false
    try {
        return isDescendantOf(next, container)
                && isVisibleInContainer(next, container)
    } finally {
        next.recycle()
    }
}

internal fun DefaultNavigationMode.isDescendantOf(
        node: AccessibilityNodeInfoCompat,
        ancestor: AccessibilityNodeInfoCompat): Boolean {
    if (node == ancestor) {
        return true
    }
    var parent = node.parent
    while (parent != null) {
        if (parent == ancestor) {
            parent.recycle()
            return true
        }
        val next = parent.parent
        parent.recycle()
        parent = next
    }
    return false
}

internal fun DefaultNavigationMode.isVisibleInContainer(
        node: AccessibilityNodeInfoCompat,
        container: AccessibilityNodeInfoCompat): Boolean {
    if (!node.isVisibleToUser) {
        return false
    }
    val nodeBounds = Rect()
    node.getBoundsInScreen(nodeBounds)
    val containerBounds = Rect()
    container.getBoundsInScreen(containerBounds)
    if (nodeBounds.width() <= 0 || nodeBounds.height() <= 0) {
        return false
    }
    val visibleHeight =
        minOf(nodeBounds.bottom, containerBounds.bottom) -
            maxOf(nodeBounds.top, containerBounds.top)
    // A node that only peeks in at the very bottom is not a good
    // navigation target; scroll first so the user gets a usable line.
    return visibleHeight > 0 && visibleHeight * 3 >= nodeBounds.height()
}

/**
 * Collects the visible nodes inside [container] in tree order.  The
 * snapshots are used both to detect whether a scroll action actually
 * moved the content and to choose the next node to braille.
 */
internal fun DefaultNavigationMode.collectVisibleNodes(
        container: AccessibilityNodeInfoCompat): List<NodeSnapshot> {
    val result = ArrayList<NodeSnapshot>()
    val containerBounds = Rect()
    container.getBoundsInScreen(containerBounds)
    collectVisibleNodesInternal(
            container, containerBounds, 0, result)
    return result
}

internal fun DefaultNavigationMode.collectVisibleNodesInternal(
        node: AccessibilityNodeInfoCompat,
        containerBounds: Rect,
        depth: Int,
        result: MutableList<NodeSnapshot>) {
    if (depth >= MAX_VISIBLE_NODE_DEPTH
            || result.size >= MAX_VISIBLE_NODE_COUNT) {
        return
    }
    val childCount = node.childCount
    for (i in 0 until childCount) {
        if (result.size >= MAX_VISIBLE_NODE_COUNT) {
            return
        }
        val child = node.getChild(i) ?: continue
        try {
            acceptVisibleChild(child, containerBounds, depth, result)
        } finally {
            child.recycle()
        }
    }
}

private fun DefaultNavigationMode.acceptVisibleChild(
        child: AccessibilityNodeInfoCompat,
        containerBounds: Rect,
        depth: Int,
        result: MutableList<NodeSnapshot>) {
    if (!child.isVisibleToUser) {
        return
    }
    val bounds = Rect()
    child.getBoundsInScreen(bounds)
    if (bounds.width() <= 0 || bounds.height() <= 0
            || !Rect.intersects(bounds, containerBounds)) {
        return
    }
    result.add(NodeSnapshot(
            AccessibilityNodeInfoCompat.obtain(child),
            Rect(bounds),
            AccessibilityNodeInfoUtils.shouldFocusNode(
                    mAccessibilityService, child)))
    collectVisibleNodesInternal(
            child, containerBounds, depth + 1, result)
}

internal fun DefaultNavigationMode.recycleSnapshots(nodes: List<NodeSnapshot>) {
    for (snapshot in nodes) {
        snapshot.node.recycle()
    }
}

internal fun DefaultNavigationMode.boundsOf(nodes: List<NodeSnapshot>): List<Rect> {
    return nodes.map { Rect(it.bounds) }
}

internal fun DefaultNavigationMode.scheduleBrailleAfterScroll(
        container: AccessibilityNodeInfoCompat,
        previous: AccessibilityNodeInfoCompat,
        forward: Boolean,
        beforeBounds: List<Rect>) {
    val sequence = ++mScrollSequence
    mScrollUpdatePending = true
    // 冷却窗口从滚动开始算：系统在这之后的重置都属于「跟着滚动走」的自动焦点，
    // 不能让它改写盲文；用户自己的触摸一般早过了窗口。
    mLastScrollActionAt = SystemClock.uptimeMillis()
    val containerCopy = AccessibilityNodeInfoCompat.obtain(container)
    val previousCopy = AccessibilityNodeInfoCompat.obtain(previous)
    updateAfterScroll(sequence, containerCopy, previousCopy, forward,
            beforeBounds, 0)
}

internal fun DefaultNavigationMode.updateAfterScroll(
        sequence: Int,
        containerCopy: AccessibilityNodeInfoCompat,
        previousCopy: AccessibilityNodeInfoCompat,
        forward: Boolean,
        beforeBounds: List<Rect>,
        attempt: Int) {
    val delay = SCROLL_UPDATE_DELAYS_MS[attempt]
    mMainHandler.postDelayed({
        if (sequence != mScrollSequence) {
            containerCopy.recycle()
            previousCopy.recycle()
            return@postDelayed
        }
        val refreshed = AccessibilityNodeInfoUtils.refreshNode(
                containerCopy)
        val scrollable = refreshed ?: containerCopy
        val visible = collectVisibleNodes(scrollable)
        val moved = boundsOf(visible) != beforeBounds
        if (!moved && attempt < SCROLL_UPDATE_DELAYS_MS.size - 1) {
            recycleSnapshots(visible)
            if (refreshed != null && refreshed !== containerCopy) {
                refreshed.recycle()
            }
            updateAfterScroll(sequence, containerCopy, previousCopy,
                    forward, beforeBounds, attempt + 1)
            return@postDelayed
        }

        mScrollUpdatePending = false
        LogUtils.log(this, Log.VERBOSE,
                "Auto-scroll settled: moved=%s attempt=%d",
                moved, attempt)
        try {
            if (moved) {
                if (isQuietNavigationEnabled()) {
                    val target = chooseVisibleTarget(
                            visible, previousCopy, forward)
                    if (target != null) {
                        try {
                            focusOrBraille(target)
                        } finally {
                            target.recycle()
                        }
                    } else if (visible.isNotEmpty()) {
                        // Nothing focusable is visible; show the newly
                        // revealed content instead of jumping away.
                        focusOrBraille(scrollable)
                    }
                }
            } else {
                // The scroll action claimed success but nothing moved:
                // we are at the real end of the scrollable area.
                focusNextAfterNoScroll(previousCopy, forward)
            }
        } finally {
            recycleSnapshots(visible)
            if (refreshed != null && refreshed !== containerCopy) {
                refreshed.recycle()
            }
            containerCopy.recycle()
            previousCopy.recycle()
        }
    }, delay)
}

internal fun DefaultNavigationMode.chooseVisibleTarget(
        nodes: List<NodeSnapshot>,
        previous: AccessibilityNodeInfoCompat?,
        forward: Boolean): AccessibilityNodeInfoCompat? {
    val focusable = nodes.filter { it.focusable }
    if (focusable.isEmpty()) {
        return null
    }
    val sorted = if (forward) {
        focusable.sortedBy { it.bounds.top }
    } else {
        focusable.sortedByDescending { it.bounds.bottom }
    }
    val refreshedPrevious = if (previous != null) {
        AccessibilityNodeInfoUtils.refreshNode(previous)
    } else {
        null
    }
    try {
        // Prefer new content over the node that was already on screen.
        val preferred = sorted.firstOrNull {
            refreshedPrevious == null || it.node != refreshedPrevious
        } ?: sorted.first()
        LogUtils.log(this, Log.VERBOSE,
                "Auto-scroll target: className=%s bounds=%s",
                preferred.node.className, preferred.bounds)
        return AccessibilityNodeInfoCompat.obtain(preferred.node)
    } finally {
        AccessibilityNodeInfoUtils.recycleNodes(refreshedPrevious)
    }
}

internal fun DefaultNavigationMode.focusNextAfterNoScroll(
        previous: AccessibilityNodeInfoCompat,
        forward: Boolean) {
    val refreshed = AccessibilityNodeInfoUtils.refreshNode(previous)
            ?: return
    try {
        val next = mFocusFinder.linear(refreshed,
                if (forward) FocusFinder.SEARCH_FORWARD
                else FocusFinder.SEARCH_BACKWARD) ?: return
        try {
            focusOrBraille(next)
        } finally {
            next.recycle()
        }
    } finally {
        refreshed.recycle()
    }
}

internal fun DefaultNavigationMode.rememberScrollContainer(node: AccessibilityNodeInfoCompat) {
    val container = AccessibilityNodeInfoUtils.getSelfOrMatchingAncestor(
            mAccessibilityService, node,
            AccessibilityNodeInfoUtils.FILTER_SCROLLABLE) ?: return
    mLastScrollableNode.reset(container)
}

internal fun DefaultNavigationMode.findFocusableInLastScrollContainer():
        AccessibilityNodeInfoCompat? {
    val stored = mLastScrollableNode.get() ?: return null
    val container = AccessibilityNodeInfoUtils.refreshNode(stored)
            ?: return null
    try {
        if (!container.isVisibleToUser) {
            return null
        }
        val visible = collectVisibleNodes(container)
        try {
            return chooseVisibleTarget(visible, null, true)
        } finally {
            recycleSnapshots(visible)
        }
    } finally {
        container.recycle()
    }
}

