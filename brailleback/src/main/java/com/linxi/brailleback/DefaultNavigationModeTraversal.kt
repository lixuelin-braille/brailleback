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

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.compat.accessibilityservice.AccessibilityServiceCompatUtils
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoRef
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.WebInterfaceUtils

/**
 * 节点遍历与定位：从 DefaultNavigationMode 拆出来的第一半。
 *
 * 平移溢出、上一行/下一行、上一项/下一项、首/末节点跳转，以及线性焦点查找。
 * 遍历顺序、边界判定与既有行为一字未改；写成同包扩展函数（internal），
 * 避免把每个 mXxx 都改写成导航器.xxx。
 */

internal fun DefaultNavigationMode.onPanLeftOverflowInternal(content: Content): Boolean {
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

internal fun DefaultNavigationMode.onPanRightOverflowInternal(
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
internal fun DefaultNavigationMode.findNodeForPanRight(
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
internal fun DefaultNavigationMode.nextUpwardsOrClear(node: AccessibilityNodeInfoRef) {
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

internal fun DefaultNavigationMode.linePrevious(content: Content): Boolean {
    return mFeedbackManager.emitOnFailure(
            linePreviousInternal(content),
            FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
}

/**
 * Moves accessibility focus to the first focusable node of the previous
 * 'line'.
 */
internal fun DefaultNavigationMode.linePreviousInternal(content: Content): Boolean {
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

internal fun DefaultNavigationMode.lineNext(content: Content): Boolean {
    return onPanRightOverflow(content)
}

internal fun DefaultNavigationMode.itemPrevious(): Boolean {
    return mFeedbackManager.emitOnFailure(
            navigateItem(DIRECTION_BACKWARD),
            FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
}

internal fun DefaultNavigationMode.navigateItem(direction: Int): Boolean {
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

internal fun DefaultNavigationMode.itemNext(): Boolean {
    return mFeedbackManager.emitOnFailure(
            navigateItem(DIRECTION_FORWARD),
            FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
}

internal fun DefaultNavigationMode.moveFocus(from: AccessibilityNodeInfoCompat?,
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

internal fun DefaultNavigationMode.linearSearch(direction: Int): AccessibilityNodeInfoCompat? {
    val source = currentFocusedNode(true)
    val result = mFocusFinder.linear(source, direction)
    AccessibilityNodeInfoUtils.recycleNodes(source)
    return result
}

/**
 * Attempts to navigate to the top-most focusable node in the tree.
 */
internal fun DefaultNavigationMode.attemptNavigateTop(): Boolean {
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
internal fun DefaultNavigationMode.attemptNavigateBottom(): Boolean {
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
