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

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.TreeDebug

/**
 * A debugging navigation mode that allows navigating through the currently
 * active window's node tree.
 */
class TreeDebugNavigationMode(
    private val displayManager: DisplayManager,
    private val feedbackManager: FeedbackManager,
    private val service: BrailleBackService
) : NavigationMode {

    // TODO: Could keep current nodes in inactive Windows in an LRU cache to
    // give a better user experience when switching between windows.
    /**
     * The node that is currently shown on the display and which navigation
     * commands start at.
     */
    private var currentNode: AccessibilityNodeInfo? = null

    /**
     * The node of the accessibility event that was last observed. This may
     * become the current node under certain circumstances.
     */
    private var pendingNode: AccessibilityNodeInfo? = null

    override fun onPanLeftOverflow(content: DisplayManager.Content): Boolean {
        return movePrevious()
    }

    override fun onPanRightOverflow(content: DisplayManager.Content): Boolean {
        return moveNext()
    }

    override fun onMappedInputEvent(
        event: BrailleInputEvent,
        content: DisplayManager.Content
    ): Boolean {
        when (event.command) {
            BrailleInputEvent.CMD_NAV_LINE_PREVIOUS -> return movePreviousSibling()
            BrailleInputEvent.CMD_NAV_LINE_NEXT -> return moveNextSibling()
            BrailleInputEvent.CMD_NAV_ITEM_PREVIOUS -> return moveParent()
            BrailleInputEvent.CMD_NAV_ITEM_NEXT -> return moveFirstChild()
            BrailleInputEvent.CMD_ROUTE -> return activateCurrent()
            BrailleInputEvent.CMD_BRAILLE_KEY -> {
                if (event.argument == (BrailleBackService.DOT1 or BrailleBackService.DOT2)) {
                    // letter b
                    showRect()
                    return true
                }
                if (event.argument == (BrailleBackService.DOT1 or BrailleBackService.DOT4)) {
                    // letter c
                    // Undocumented way of clearing the node cache.
                    service.setServiceInfo(service.serviceInfo)
                    return true
                }
                if (event.argument ==
                    (BrailleBackService.DOT1 or BrailleBackService.DOT2 or
                        BrailleBackService.DOT3 or BrailleBackService.DOT5)
                ) {
                    // letter r
                    setPendingNode(service.rootInActiveWindow)
                    if (pendingNode == null) {
                        return false
                    }
                    makePendingNodeCurrent()
                    displayCurrentNode()
                    return true
                }
                if (event.argument ==
                    (BrailleBackService.DOT1 or BrailleBackService.DOT2 or
                        BrailleBackService.DOT3 or BrailleBackService.DOT4)
                ) {
                    // letter p
                    printNodes()
                    return true
                }
            }
        }
        return false
    }

    override fun onActivate() {
        if (pendingNode != null) {
            makePendingNodeCurrent()
        }
        displayCurrentNode()
    }

    override fun onDeactivate() {
        setCurrentNode(null)
    }

    override fun onObserveAccessibilityEvent(event: AccessibilityEvent) {
        val source = event.source ?: return
        val isNewWindow = currentNode == null ||
            currentNode!!.windowId != source.windowId

        val t = event.eventType
        var isInterestingEventType = false
        if (t == AccessibilityEvent.TYPE_VIEW_SELECTED ||
            t == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            t == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            t != AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
        ) {
            isInterestingEventType = true
        }
        if (isNewWindow || isInterestingEventType) {
            setPendingNode(source)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
        if (pendingNode == null) {
            return false
        }
        if (currentNode == null ||
            currentNode!!.windowId != pendingNode!!.windowId
        ) {
            makePendingNodeCurrent()
            displayCurrentNode()
            return true
        }
        return false
    }

    override fun onInvalidateAccessibilityNode(node: AccessibilityNodeInfoCompat) {
        // Nothing to do.
    }

    private fun setPendingNode(newNode: AccessibilityNodeInfo?) {
        if (pendingNode != null && pendingNode !== newNode) {
            pendingNode!!.recycle()
        }
        pendingNode = newNode
    }

    private fun setCurrentNode(newNode: AccessibilityNodeInfo?) {
        if (currentNode != null && currentNode !== newNode) {
            currentNode!!.recycle()
        }
        currentNode = newNode
    }

    private fun makePendingNodeCurrent() {
        setCurrentNode(pendingNode)
        pendingNode = null
    }

    private fun displayCurrentNode() {
        if (currentNode == null) {
            displayManager.setContent(DisplayManager.Content("No Node"))
        } else {
            displayManager.setContent(
                DisplayManager.Content(
                    TreeDebug.nodeDebugDescription(AccessibilityNodeInfoCompat(currentNode!!))))
        }
    }

    private fun movePreviousSibling(): Boolean {
        val node = currentNode ?: return false
        return moveTo(getPreviousSibling(node))
    }

    private fun getPreviousSibling(from: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val parent = from.parent ?: return null
        var ret: AccessibilityNodeInfo? = null
        var prev: AccessibilityNodeInfo? = null
        var cur: AccessibilityNodeInfo? = null
        try {
            val childCount = parent.childCount
            for (i in 0 until childCount) {
                cur = parent.getChild(i)
                if (cur == null) {
                    return null
                }
                if (cur.equals(from)) {
                    ret = prev
                    prev = null
                    return ret
                }
                if (prev != null) {
                    prev!!.recycle()
                }
                prev = cur
                cur = null
            }
        } finally {
            parent.recycle()
            if (prev != null) {
                prev!!.recycle()
            }
            if (cur != null) {
                cur!!.recycle()
            }
        }
        return ret
    }

    private fun moveNextSibling(): Boolean {
        val node = currentNode ?: return false
        return moveTo(getNextSibling(node))
    }

    private fun getNextSibling(from: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val parent = from.parent ?: return null
        var cur: AccessibilityNodeInfo? = null
        try {
            val childCount = parent.childCount
            for (i in 0 until childCount - 1) {
                cur = parent.getChild(i)
                if (cur == null) {
                    return null
                }
                if (cur.equals(from)) {
                    return parent.getChild(i + 1)
                }
                if (cur != null) {
                    cur!!.recycle()
                    cur = null
                }
            }
        } finally {
            parent.recycle()
            if (cur != null) {
                cur!!.recycle()
            }
        }
        return null
    }

    private fun moveParent(): Boolean {
        val node = currentNode ?: return false
        val parent = node.parent
        return moveTo(parent, FeedbackManager.TYPE_NAVIGATE_OUT_OF_HIERARCHY)
    }

    private fun moveFirstChild(): Boolean {
        val node = currentNode ?: return false
        return moveTo(getFirstChild(node), FeedbackManager.TYPE_NAVIGATE_OUT_OF_HIERARCHY)
    }

    private fun getFirstChild(from: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (from.childCount < 1) {
            return null
        }
        return from.getChild(0)
    }

    private fun getLastChild(from: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (from.childCount < 1) {
            return null
        }
        return from.getChild(from.childCount - 1)
    }

    private fun movePrevious(): Boolean {
        val node = currentNode ?: return false
        var target: AccessibilityNodeInfo? = null
        var feedbackType = FeedbackManager.TYPE_NONE
        var prevSibling = getPreviousSibling(node)
        if (prevSibling != null) {
            target = getLastDescendantDfs(prevSibling)
            if (target != null) {
                feedbackType = FeedbackManager.TYPE_NAVIGATE_INTO_HIERARCHY
                prevSibling.recycle()
                prevSibling = null
            } else {
                target = prevSibling
            }
        }
        if (target == null) {
            target = node.parent
            if (target != null) {
                feedbackType = FeedbackManager.TYPE_NAVIGATE_OUT_OF_HIERARCHY
            }
        }
        return moveTo(target, feedbackType)
    }

    private fun getLastDescendantDfs(from: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var lastChild = getLastChild(from) ?: return null
        while (true) {
            val lastGrandChild = getLastChild(lastChild)
            if (lastGrandChild != null) {
                lastChild.recycle()
                lastChild = lastGrandChild
            } else {
                break
            }
        }
        return lastChild
    }

    private fun moveNext(): Boolean {
        val node = currentNode ?: return false
        var feedbackType = FeedbackManager.TYPE_NONE
        var target = getFirstChild(node)
        if (target != null) {
            feedbackType = FeedbackManager.TYPE_NAVIGATE_INTO_HIERARCHY
        } else {
            target = getNextSibling(node)
        }
        if (target == null) {
            var ancestor = node.parent
            while (target == null && ancestor != null) {
                target = getNextSibling(ancestor)
                if (target == null) {
                    val temp = ancestor.parent
                    ancestor.recycle()
                    ancestor = temp
                } else {
                    ancestor.recycle()
                    ancestor = null
                }
            }
            if (target != null) {
                feedbackType = FeedbackManager.TYPE_NAVIGATE_OUT_OF_HIERARCHY
            }
        }
        return moveTo(target, feedbackType)
    }

    private fun moveTo(node: AccessibilityNodeInfo?, feedbackType: Int): Boolean {
        if (moveTo(node)) {
            feedbackManager.emitFeedback(feedbackType)
            return true
        }
        return false
    }

    private fun moveTo(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) {
            return false
        }
        setCurrentNode(node)
        displayCurrentNode()
        return true
    }

    private fun activateCurrent(): Boolean {
        val node = currentNode ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun showRect() {
        val node = currentNode ?: return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        displayManager.setContent(DisplayManager.Content("b: " + rect))
    }

    /**
     * Outputs the node tree from the current node using dfs preorder traversal.
     */
    private fun printNodes() {
        val node = currentNode
        if (node == null) {
            LogUtils.log(this, Log.VERBOSE, "No current node")
            return
        }
        LogUtils.log(this, Log.VERBOSE, "Printing nodes")
        TreeDebug.logNodeTree(AccessibilityNodeInfoCompat(node))
    }
}
