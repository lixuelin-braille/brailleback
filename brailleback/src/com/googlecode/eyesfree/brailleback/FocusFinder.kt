/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.NodeFocusFinder
import java.util.HashSet

/**
 * Functions to find focus.
 *
 * NOTE: To give a consistent behaviour, this code should be kept in sync with
 * the relevant subset of code in the `CursorController` class in TalkBack.
 */
class FocusFinder(context: Context) {

    private val mContext: Context = context

    private val mTmpNodeHash = HashSet<AccessibilityNodeInfoCompat>()

    fun linear(source: AccessibilityNodeInfoCompat?, direction: Int): AccessibilityNodeInfoCompat? {
        if (source == null) {
            return null
        }
        var next: AccessibilityNodeInfoCompat? = NodeFocusFinder.focusSearch(source, direction)

        val seenNodes = mTmpNodeHash
        seenNodes.clear()

        while (next != null && !AccessibilityNodeInfoUtils.shouldFocusNode(mContext, next)) {
            if (seenNodes.contains(next)) {
                LogUtils.log(this, Log.ERROR, "Found duplicate node during traversal: %s", next)
                break
            }

            LogUtils.log(this, Log.VERBOSE, "Search strategy rejected node: %s", next.info)
            seenNodes.add(next)
            next = NodeFocusFinder.focusSearch(next, direction)
        }

        // Clear the list of seen nodes.
        AccessibilityNodeInfoUtils.recycleNodes(*seenNodes.toTypedArray())

        if (next == null) {
            LogUtils.log(this, Log.VERBOSE, "Failed to find the next node")
        }
        return next
    }

    companion object {
        const val SEARCH_FORWARD = NodeFocusFinder.SEARCH_FORWARD
        const val SEARCH_BACKWARD = NodeFocusFinder.SEARCH_BACKWARD

        @JvmStatic
        fun findFirstFocusableDescendant(
            root: AccessibilityNodeInfoCompat?, context: Context
        ): AccessibilityNodeInfoCompat? {
            // null guard and shortcut for leaf nodes.
            if (root == null || root.childCount <= 0) {
                return null
            }
            val seenNodes = HashSet<AccessibilityNodeInfoCompat>()
            seenNodes.add(root)
            try {
                return findFirstFocusableDescendantInternal(root, context, seenNodes)
            } finally {
                seenNodes.remove(root) // Not owned by us.
                AccessibilityNodeInfoUtils.recycleNodes(*seenNodes.toTypedArray())
            }
        }

        private fun findFirstFocusableDescendantInternal(
            root: AccessibilityNodeInfoCompat, context: Context,
            seenNodes: HashSet<AccessibilityNodeInfoCompat>
        ): AccessibilityNodeInfoCompat? {
            for (i in 0 until root.childCount) {
                val child = root.getChild(i)
                if (child == null) {
                    continue
                }
                if (AccessibilityNodeInfoUtils.shouldFocusNode(context, child)) {
                    return child
                }
                if (!seenNodes.add(child)) {
                    LogUtils.log(FocusFinder::class.java, Log.ERROR, "Cycle in node tree")
                    child.recycle()
                    return null
                }
                val n = findFirstFocusableDescendantInternal(child, context, seenNodes)
                if (n != null) {
                    return n
                }
            }
            return null
        }

        @JvmStatic
        fun findLastFocusableDescendant(
            root: AccessibilityNodeInfoCompat?, context: Context
        ): AccessibilityNodeInfoCompat? {
            // null guard and shortcut for leaf nodes.
            if (root == null || root.childCount <= 0) {
                return null
            }
            val seenNodes = HashSet<AccessibilityNodeInfoCompat>()
            seenNodes.add(root)
            try {
                return findLastFocusableDescendantInternal(root, context, seenNodes)
            } finally {
                seenNodes.remove(root) // Not owned by us.
                AccessibilityNodeInfoUtils.recycleNodes(*seenNodes.toTypedArray())
            }
        }

        private fun findLastFocusableDescendantInternal(
            root: AccessibilityNodeInfoCompat, context: Context,
            seenNodes: HashSet<AccessibilityNodeInfoCompat>
        ): AccessibilityNodeInfoCompat? {
            var i = root.childCount - 1
            while (i >= 0) {
                val child = root.getChild(i)
                if (child == null) {
                    i--
                    continue
                }

                val n = findLastFocusableDescendantInternal(child, context, seenNodes)
                if (n != null) {
                    return n
                }

                if (AccessibilityNodeInfoUtils.shouldFocusNode(context, child)) {
                    return child
                }
                if (!seenNodes.add(child)) {
                    LogUtils.log(FocusFinder::class.java, Log.ERROR, "Cycle in node tree")
                    child.recycle()
                    return null
                }
                i--
            }
            return null
        }

        @JvmStatic
        fun getFocusedNode(service: AccessibilityService, fallbackOnRoot: Boolean): AccessibilityNodeInfoCompat? {
            var root: AccessibilityNodeInfo? = service.getRootInActiveWindow()
            var focused: AccessibilityNodeInfo? = null
            try {
                var ret: AccessibilityNodeInfo? = null
                if (root != null) {
                    focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    if (focused != null && focused.isVisibleToUser) {
                        ret = focused
                        focused = null
                    } else if (fallbackOnRoot) {
                        ret = root
                        root = null
                    }
                } else {
                    LogUtils.log(service, Log.ERROR, "No current window root")
                }
                if (ret != null) {
                    return AccessibilityNodeInfoCompat(ret)
                }
            } finally {
                root?.recycle()
                focused?.recycle()
            }
            return null
        }
    }
}
