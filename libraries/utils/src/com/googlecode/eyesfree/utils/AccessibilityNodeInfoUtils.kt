/*
 * Copyright (C) 2014 Google Inc.
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

package com.googlecode.eyesfree.utils

import android.content.Context
import android.graphics.Rect
import android.os.Build
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.googlecode.eyesfree.compat.CompatUtils
import java.util.ArrayList
import java.util.Comparator
import java.util.HashSet
import java.util.LinkedList

/**
 * Provides a series of utilities for interacting with AccessibilityNodeInfo
 * objects. NOTE: This class only recycles unused nodes that were collected
 * internally. Any node passed into or returned from a public method is retained
 * and TalkBack should recycle it when appropriate.
 *
 * @author caseyburkhardt@google.com (Casey Burkhardt)
 */
class AccessibilityNodeInfoUtils private constructor() {
    companion object {
        /** Whether isVisibleToUser() is supported by the current SDK. */
        private val SUPPORTS_VISIBILITY = Build.VERSION.SDK_INT >= 16

        /**
         * Class for Samsung's TouchWiz implementation of AdapterView. May be
         * `null` on non-Samsung devices.
         */
        private val CLASS_TOUCHWIZ_TWADAPTERVIEW: Class<*>? = CompatUtils.getClass(
            "com.sec.android.touchwiz.widget.TwAdapterView"
        )

        /**
         * Class for Samsung's TouchWiz implementation of AbsListView. May be
         * `null` on non-Samsung devices.
         */
        private val CLASS_TOUCHWIZ_TWABSLISTVIEW: Class<*>? = CompatUtils.getClass(
            "com.sec.android.touchwiz.widget.TwAbsListView"
        )

        /**
         * Gets the text of a `node` by returning the content description
         * (if available) or by returning the text.
         *
         * @param node The node.
         * @return The node text.
         */
        @JvmStatic
        fun getNodeText(node: AccessibilityNodeInfoCompat?): CharSequence? {
            if (node == null) {
                return null
            }

            // Prefer content description over text.
            val contentDescription = node.contentDescription
            if (!TextUtils.isEmpty(contentDescription)
                && TextUtils.getTrimmedLength(contentDescription) > 0
            ) {
                return contentDescription
            }

            val text = node.text
            if (!TextUtils.isEmpty(text) && TextUtils.getTrimmedLength(text) > 0) {
                return text
            }

            return null
        }

        /**
         * Returns the root node of the tree containing `node`.
         */
        @JvmStatic
        fun getRoot(node: AccessibilityNodeInfoCompat?): AccessibilityNodeInfoCompat? {
            if (node == null) {
                return null
            }

            var current: AccessibilityNodeInfoCompat? = null
            var parent: AccessibilityNodeInfoCompat? = AccessibilityNodeInfoCompat.obtain(node)

            do {
                current = parent
                parent = current?.parent
            } while (parent != null)

            return current
        }

        /**
         * Returns whether a node should receive focus from focus traversal or touch
         * exploration.
         *
         * @return `true` if the node is accessibility focusable.
         */
        @JvmStatic
        fun isAccessibilityFocusable(
            context: Context?, node: AccessibilityNodeInfoCompat?
        ): Boolean {
            if (node == null) {
                return false
            }

            // Never focus invisible nodes.
            if (!isVisibleOrLegacy(node)) {
                return false
            }

            // Always focus "actionable" nodes.
            if (isActionableForAccessibility(node)) {
                return true
            }

            if (Build.VERSION.SDK_INT < 16) {
                // In pre-JellyBean, always focus ALL top-level list items and items
                // that should have independently focusable children.
                if (isTopLevelScrollItem(context, node)) {
                    return true
                }
            } else {
                // In post-JellyBean, only focus top-level list items with
                // non-actionable speaking children.
                if (isTopLevelScrollItem(context, node)
                    && (isSpeakingNode(context, node)
                        || hasNonActionableSpeakingChildren(context, node))
                ) {
                    return true
                }
            }

            return false
        }

        /**
         * Returns whether a node should receive accessibility focus from
         * navigation. This method should never be called recursively, since it
         * traverses up the parent hierarchy on every call.
         */
        @JvmStatic
        fun shouldFocusNode(context: Context?, node: AccessibilityNodeInfoCompat?): Boolean {
            if (node == null) {
                return false
            }

            if (!isVisibleOrLegacy(node)) {
                LogUtils.log(
                    AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                    "Don't focus, node is not visible"
                )
                return false
            }

            if (FILTER_ACCESSIBILITY_FOCUSABLE.accept(context, node)) {
                // TODO: This may still result in focusing non-speaking nodes, but it
                // won't prevent unlabeled buttons from receiving focus.
                if (node.childCount <= 0) {
                    LogUtils.log(
                        AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                        "Focus, node is focusable and has no children"
                    )
                    return true
                } else if (isSpeakingNode(context, node)) {
                    LogUtils.log(
                        AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                        "Focus, node is focusable and has something to speak"
                    )
                    return true
                } else {
                    LogUtils.log(
                        AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                        "Don't focus, node is focusable but has nothing to speak"
                    )
                    return false
                }
            }

            // If this node has no focusable ancestors, but it still has text,
            // then it should receive focus from navigation and be read aloud.
            if (!hasMatchingAncestor(context, node, FILTER_ACCESSIBILITY_FOCUSABLE)
                && hasText(node)
            ) {
                LogUtils.log(
                    AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                    "Focus, node has text and no focusable ancestors"
                )
                return true
            }

            LogUtils.log(
                AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                "Don't focus, failed all focusability tests"
            )
            return false
        }

        /**
         * Returns the node that should receive focus from hover by starting from
         * the touched node and calling [shouldFocusNode] at each level.
         */
        @JvmStatic
        fun findFocusFromHover(
            context: Context, touched: AccessibilityNodeInfoCompat?
        ): AccessibilityNodeInfoCompat? {
            return getSelfOrMatchingAncestor(context, touched, FILTER_SHOULD_FOCUS)
        }

        private fun isSpeakingNode(
            context: Context?, node: AccessibilityNodeInfoCompat?
        ): Boolean {
            if (hasText(node)) {
                LogUtils.log(
                    AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                    "Speaking, has text"
                )
                return true
            }

            // Special case for check boxes.
            if (node?.isCheckable == true) {
                LogUtils.log(
                    AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                    "Speaking, is checkable"
                )
                return true
            }

            // Special case for web content.
            if (WebInterfaceUtils.supportsWebActions(node)) {
                LogUtils.log(
                    AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                    "Speaking, has web content"
                )
                return true
            }

            // Special case for containers with non-focusable content.
            if (hasNonActionableSpeakingChildren(context, node)) {
                LogUtils.log(
                    AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                    "Speaking, has non-actionable speaking children"
                )
                return true
            }

            return false
        }

        private fun hasNonActionableSpeakingChildren(
            context: Context?, node: AccessibilityNodeInfoCompat?
        ): Boolean {
            val childCount = node?.childCount ?: 0

            // Has non-actionable, speaking children?
            for (i in 0 until childCount) {
                val child = node?.getChild(i)
                try {
                    if (child == null) {
                        LogUtils.log(
                            AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                            "Child %d is null, skipping it", i
                        )
                        continue
                    }

                    // Ignore invisible nodes.
                    if (!isVisibleOrLegacy(child)) {
                        LogUtils.log(
                            AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                            "Child %d is invisible, skipping it", i
                        )
                        continue
                    }

                    // Ignore focusable nodes.
                    if (FILTER_ACCESSIBILITY_FOCUSABLE.accept(context, child)) {
                        LogUtils.log(
                            AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                            "Child %d is focusable, skipping it", i
                        )
                        continue
                    }

                    // Recursively check non-focusable child nodes.
                    // TODO: Mutual recursion is probably not a good idea.
                    if (isSpeakingNode(context, child)) {
                        LogUtils.log(
                            AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                            "Does have actionable speaking children (child %d)", i
                        )
                        return true
                    }
                } finally {
                    recycleNodes(child)
                }
            }

            LogUtils.log(
                AccessibilityNodeInfoUtils::class.java, Log.VERBOSE,
                "Does not have non-actionable speaking children"
            )
            return false
        }

        /**
         * Returns whether a node is actionable.
         *
         * @return `true` if node is actionable.
         */
        @JvmStatic
        fun isActionableForAccessibility(node: AccessibilityNodeInfoCompat?): Boolean {
            if (node == null) {
                return false
            }

            // Nodes that are clickable are always actionable.
            if (isClickable(node) || isLongClickable(node)) {
                return true
            }

            if (node.isFocusable) {
                return true
            }

            return supportsAnyAction(
                node, AccessibilityNodeInfoCompat.ACTION_FOCUS,
                AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT,
                AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT
            )
        }

        @JvmStatic
        fun isSelfOrAncestorFocused(
            context: Context, node: AccessibilityNodeInfoCompat?
        ): Boolean {
            if (node == null) {
                return false
            }

            if (node.isAccessibilityFocused) {
                return true
            } else {
                return hasMatchingAncestor(context, node, object : NodeFilter() {
                    override fun accept(
                        context: Context?,
                        node: AccessibilityNodeInfoCompat?
                    ): Boolean {
                        return node?.isAccessibilityFocused == true
                    }
                })
            }
        }

        /**
         * Returns whether a node is clickable.
         *
         * @return `true` if node is clickable.
         */
        @JvmStatic
        fun isClickable(node: AccessibilityNodeInfoCompat?): Boolean {
            if (node == null) {
                return false
            }

            if (node.isClickable) {
                return true
            }

            return supportsAnyAction(node, AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        /**
         * Returns whether a node is long clickable.
         *
         * @return `true` if node is long clickable.
         */
        @JvmStatic
        fun isLongClickable(node: AccessibilityNodeInfoCompat?): Boolean {
            if (node == null) {
                return false
            }

            if (node.isLongClickable) {
                return true
            }

            return supportsAnyAction(node, AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
        }

        /**
         * Check whether a given node has a scrollable ancestor.
         *
         * @return `true` if one of the node's ancestors is scrollable.
         */
        private fun hasMatchingAncestor(
            context: Context?, node: AccessibilityNodeInfoCompat?, filter: NodeFilter
        ): Boolean {
            if (node == null) {
                return false
            }

            val result = getMatchingAncestor(context, node, filter) ?: return false

            result.recycle()
            return true
        }

        /**
         * Returns the `node` if it matches the `filter`, or the first
         * matching ancestor. Returns `null` if no nodes match.
         */
        @JvmStatic
        fun getSelfOrMatchingAncestor(
            context: Context, node: AccessibilityNodeInfoCompat?, filter: NodeFilter
        ): AccessibilityNodeInfoCompat? {
            if (node == null) {
                return null
            }

            if (filter.accept(context, node)) {
                return AccessibilityNodeInfoCompat.obtain(node)
            }

            return getMatchingAncestor(context, node, filter)
        }

        /**
         * Returns the first ancestor of `node` that matches the
         * `filter`. Returns `null` if no nodes match.
         */
        private fun getMatchingAncestor(
            context: Context?, nodeParam: AccessibilityNodeInfoCompat?, filter: NodeFilter
        ): AccessibilityNodeInfoCompat? {
            if (nodeParam == null) {
                return null
            }

            val ancestors = HashSet<AccessibilityNodeInfoCompat>()

            try {
                ancestors.add(AccessibilityNodeInfoCompat.obtain(nodeParam))
                var node: AccessibilityNodeInfoCompat? = nodeParam.parent

                while (node != null) {
                    if (!ancestors.add(node)) {
                        // Already seen this node, so abort!
                        node.recycle()
                        return null
                    }

                    if (filter.accept(context, node)) {
                        // Send a copy since node gets recycled.
                        return AccessibilityNodeInfoCompat.obtain(node)
                    }

                    node = node.parent
                }
            } finally {
                recycleNodes(*ancestors.toTypedArray())
            }

            return null
        }

        /**
         * Check whether a given node is scrollable.
         *
         * @return `true` if the node is scrollable.
         */
        private fun isScrollable(node: AccessibilityNodeInfoCompat?): Boolean {
            if (node?.isScrollable == true) {
                return true
            }

            return supportsAnyAction(
                node,
                AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,
                AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD
            )
        }

        /**
         * Returns whether the specified node has text.
         *
         * @return `true` if the node has text.
         */
        private fun hasText(node: AccessibilityNodeInfoCompat?): Boolean {
            if (node == null) {
                return false
            }

            return (!TextUtils.isEmpty(node.text)
                || !TextUtils.isEmpty(node.contentDescription))
        }

        /**
         * Determines whether a node is a top-level item in a scrollable container.
         *
         * @return `true` if `node` is a top-level item in a scrollable
         * container.
         */
        @JvmStatic
        fun isTopLevelScrollItem(
            context: Context?, node: AccessibilityNodeInfoCompat?
        ): Boolean {
            if (node == null) {
                return false
            }

            var parent: AccessibilityNodeInfoCompat? = null
            try {
                parent = node.parent ?: return false

                if (isScrollable(node)) {
                    return true
                }

                // AdapterView, ScrollView, and HorizontalScrollView are focusable
                // containers, but Spinner is a special case.
                if (nodeMatchesAnyClassByType(
                        context, parent,
                        android.widget.AdapterView::class.java,
                        android.widget.ScrollView::class.java,
                        android.widget.HorizontalScrollView::class.java,
                        CLASS_TOUCHWIZ_TWADAPTERVIEW
                    )
                    && !nodeMatchesAnyClassByType(context, parent, android.widget.Spinner::class.java)
                ) {
                    return true
                }

                return false
            } finally {
                recycleNodes(parent)
            }
        }

        /**
         * Determines if the current item is at the edge of a list.
         *
         * @return true if the current item is at the edge of a list.
         */
        @JvmStatic
        fun isEdgeListItem(context: Context, node: AccessibilityNodeInfoCompat?): Boolean {
            return isEdgeListItem(context, node, 0, null)
        }

        /**
         * Determines if the current item is at the edge of a list.
         *
         * @param direction The direction in which to check.
         * @param filter (Optional) Filter used to validate list-type ancestors.
         * @return true if the current item is at the edge of a list.
         */
        @JvmStatic
        fun isEdgeListItem(
            context: Context,
            node: AccessibilityNodeInfoCompat?,
            direction: Int,
            filter: NodeFilter?
        ): Boolean {
            if (node == null) {
                return false
            }

            if (direction <= 0 && isMatchingEdgeListItem(
                    context, node,
                    NodeFocusFinder.SEARCH_BACKWARD, FILTER_SCROLL_BACKWARD.and(filter)
                )
            ) {
                return true
            }

            if (direction >= 0 && isMatchingEdgeListItem(
                    context, node,
                    NodeFocusFinder.SEARCH_FORWARD, FILTER_SCROLL_FORWARD.and(filter)
                )
            ) {
                return true
            }

            return false
        }

        /**
         * Convenience method for [isEdgeListItem] with [FILTER_AUTO_SCROLL].
         */
        @JvmStatic
        fun isAutoScrollEdgeListItem(
            context: Context, node: AccessibilityNodeInfoCompat?, direction: Int
        ): Boolean {
            return isEdgeListItem(context, node, direction, FILTER_AUTO_SCROLL)
        }

        /**
         * Utility method for determining if a searching past a particular node will
         * fall off the edge of a scrollable container.
         *
         * @return `true` if focusing search in the specified direction will
         * fall off the edge of the container.
         */
        private fun isMatchingEdgeListItem(
            context: Context,
            cursor: AccessibilityNodeInfoCompat?,
            direction: Int,
            filter: NodeFilter
        ): Boolean {
            var ancestor: AccessibilityNodeInfoCompat? = null
            var searched: AccessibilityNodeInfoCompat? = null
            var searchedAncestor: AccessibilityNodeInfoCompat? = null

            try {
                ancestor = getMatchingAncestor(null, cursor, filter)
                if (ancestor == null) {
                    // Not contained in a scrollable list.
                    return false
                }

                // Search in the specified direction until we find a focusable node.
                searched = NodeFocusFinder.focusSearch(cursor, direction)
                while (searched != null
                    && !shouldFocusNode(context, searched)
                ) {
                    val temp = searched
                    searched = NodeFocusFinder.focusSearch(temp, direction)
                    temp.recycle()
                }

                if (searched == null || searched == ancestor) {
                    // Can't move from this position.
                    return true
                }

                searchedAncestor = getMatchingAncestor(null, searched, filter)
                if (ancestor != searchedAncestor) {
                    // Moves outside of the scrollable list.
                    return true
                }
            } finally {
                recycleNodes(ancestor, searched, searchedAncestor)
            }

            return false
        }

        /**
         * Determines if the generating class of an
         * [AccessibilityNodeInfoCompat] matches a given [Class] by type.
         */
        @JvmStatic
        fun nodeMatchesClassByType(
            context: Context?, node: AccessibilityNodeInfoCompat?, referenceClass: Class<*>?
        ): Boolean {
            if (node == null || referenceClass == null) {
                return false
            }

            // Attempt to take a shortcut.
            val nodeClassName = node.className
            if (TextUtils.equals(nodeClassName, referenceClass.name)) {
                return true
            }

            val loader = ClassLoadingManager.getInstance()
            val appPackage = node.packageName
            return loader.checkInstanceOf(context, nodeClassName, appPackage, referenceClass)
        }

        /**
         * Determines if the generating class of an
         * [AccessibilityNodeInfoCompat] matches any of the given
         * [Class]es by type.
         */
        @JvmStatic
        fun nodeMatchesAnyClassByType(
            context: Context?,
            node: AccessibilityNodeInfoCompat?,
            vararg referenceClasses: Class<*>?
        ): Boolean {
            for (referenceClass in referenceClasses) {
                if (nodeMatchesClassByType(context, node, referenceClass)) {
                    return true
                }
            }
            return false
        }

        /**
         * Determines if the class of an [AccessibilityNodeInfoCompat] matches
         * a given [Class] by package and name.
         */
        @JvmStatic
        fun nodeMatchesClassByName(
            context: Context,
            node: AccessibilityNodeInfoCompat?,
            referenceClassName: CharSequence?
        ): Boolean {
            if (node == null || referenceClassName == null) {
                return false
            }

            // Attempt to take a shortcut.
            val nodeClassName = node.className
            if (TextUtils.equals(nodeClassName, referenceClassName)) {
                return true
            }

            val loader = ClassLoadingManager.getInstance()
            val appPackage = node.packageName
            return loader.checkInstanceOf(context, nodeClassName, appPackage, referenceClassName)
        }

        /**
         * Recycles the given nodes.
         *
         * @param nodes The nodes to recycle.
         */
        @JvmStatic
        fun recycleNodes(vararg nodes: AccessibilityNodeInfoCompat?) {
            for (node in nodes) {
                node?.recycle()
            }
        }

        /**
         * Returns `true` if the node supports at least one of the specified
         * actions.
         */
        @JvmStatic
        fun supportsAnyAction(node: AccessibilityNodeInfoCompat?, vararg actions: Int): Boolean {
            if (node != null) {
                val supportedActions = node.actions

                for (action in actions) {
                    if (supportedActions and action == action) {
                        return true
                    }
                }
            }

            return false
        }

        /**
         * Returns `true` if the node supports at least one of the specified actions.
         */
        @JvmStatic
        fun supportsAnyAction(
            node: AccessibilityNodeInfoCompat?, vararg actions: AccessibilityAction?
        ): Boolean {
            if (node != null) {
                // Unwrap the node and compare AccessibilityActions because AccessibilityActions,
                // unlike AccessibilityActionCompats, are static (so checks for equality work correctly).
                val supportedActions = node.unwrap().actionList

                for (action in actions) {
                    if (supportedActions.contains(action)) {
                        return true
                    }
                }
            }

            return false
        }

        /**
         * Returns the result of applying a filter using breadth-first traversal.
         */
        @JvmStatic
        fun searchFromBfs(
            context: Context, node: AccessibilityNodeInfoCompat?, filter: NodeFilter
        ): AccessibilityNodeInfoCompat? {
            if (node == null) {
                return null
            }

            val queue = LinkedList<AccessibilityNodeInfoCompat>()

            queue.add(AccessibilityNodeInfoCompat.obtain(node))

            while (!queue.isEmpty()) {
                val item = queue.removeFirst() ?: continue

                if (filter.accept(context, item)) {
                    return AccessibilityNodeInfoCompat.obtain(item)
                }

                val childCount = item.childCount

                for (i in 0 until childCount) {
                    val child = item.getChild(i)

                    if (child != null) {
                        queue.addLast(child)
                    }
                }
            }

            return null
        }

        /**
         * Returns the result of applying a filter using breadth-first traversal.
         */
        @JvmStatic
        fun searchAllFromBfs(
            context: Context,
            node: AccessibilityNodeInfoCompat?,
            filter: NodeFilter
        ): List<AccessibilityNodeInfoCompat>? {
            if (node == null) {
                return null
            }

            val toReturn = ArrayList<AccessibilityNodeInfoCompat>()
            val queue = LinkedList<AccessibilityNodeInfoCompat>()

            queue.add(AccessibilityNodeInfoCompat.obtain(node))

            while (!queue.isEmpty()) {
                val item = queue.removeFirst() ?: continue

                if (filter.accept(context, item)) {
                    toReturn.add(AccessibilityNodeInfoCompat.obtain(item))
                }

                val childCount = item.childCount

                for (i in 0 until childCount) {
                    val child = item.getChild(i)

                    if (child != null) {
                        queue.addLast(child)
                    }
                }
            }

            return toReturn
        }

        /**
         * Performs in-order traversal from a given node in a particular direction
         * until a node matching the specified filter is reached.
         */
        @JvmStatic
        fun searchFromInOrderTraversal(
            context: Context,
            root: AccessibilityNodeInfoCompat?,
            filter: NodeFilter,
            direction: Int
        ): AccessibilityNodeInfoCompat? {
            var currentNode: AccessibilityNodeInfoCompat? = NodeFocusFinder.focusSearch(root, direction)

            val seenNodes = HashSet<AccessibilityNodeInfoCompat>()

            while (currentNode != null
                && !seenNodes.contains(currentNode)
                && !filter.accept(context, currentNode)
            ) {
                seenNodes.add(currentNode)
                currentNode = NodeFocusFinder.focusSearch(currentNode, direction)
            }

            // Recycle all the seen nodes.
            recycleNodes(*seenNodes.toTypedArray())

            return currentNode
        }

        /**
         * Returns a fresh copy of `node` with properties that are
         * less likely to be stale. Returns `null` if the node can't be
         * found anymore.
         */
        @JvmStatic
        fun refreshNode(node: AccessibilityNodeInfoCompat?): AccessibilityNodeInfoCompat? {
            if (node == null) {
                return null
            }
            var result = refreshFromChild(node)
            if (result == null) {
                result = refreshFromParent(node)
            }
            return result
        }

        private fun refreshFromChild(node: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? {
            if (node.childCount > 0) {
                val firstChild = node.getChild(0)
                if (firstChild != null) {
                    val `parent` = firstChild.parent
                    firstChild.recycle()
                    if (node == `parent`) {
                        return `parent`
                    } else {
                        recycleNodes(`parent`)
                    }
                }
            }
            return null
        }

        private fun refreshFromParent(node: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? {
            val `parent` = node.parent
            if (`parent` != null) {
                try {
                    val childCount = `parent`.childCount
                    for (i in 0 until childCount) {
                        val child = `parent`.getChild(i)
                        if (node == child) {
                            return child
                        }
                        recycleNodes(child)
                    }
                } finally {
                    `parent`.recycle()
                }
            }
            return null
        }

        /**
         * Helper method that returns `true` if the specified node is visible
         * to the user or if the current SDK doesn't support checking visibility.
         */
        @JvmStatic
        fun isVisibleOrLegacy(node: AccessibilityNodeInfoCompat): Boolean {
            return !SUPPORTS_VISIBILITY || node.isVisibleToUser
        }

        /**
         * Filter for scrollable items.
         */
        @JvmField
        val FILTER_SCROLLABLE: NodeFilter = object : NodeFilter() {
            override fun accept(context: Context?, node: AccessibilityNodeInfoCompat?): Boolean {
                return isScrollable(node)
            }
        }

        private val FILTER_ACCESSIBILITY_FOCUSABLE: NodeFilter = object : NodeFilter() {
            override fun accept(context: Context?, node: AccessibilityNodeInfoCompat?): Boolean {
                return isAccessibilityFocusable(context, node)
            }
        }

        /**
         * Filter for items that should receive accessibility focus.
         */
        @JvmField
        val FILTER_SHOULD_FOCUS: NodeFilter = object : NodeFilter() {
            override fun accept(context: Context?, node: AccessibilityNodeInfoCompat?): Boolean {
                return shouldFocusNode(context, node)
            }
        }

        /**
         * Filter that defines which types of views should be auto-scrolled.
         */
        private val FILTER_AUTO_SCROLL = object : NodeFilter() {
            override fun accept(context: Context?, node: AccessibilityNodeInfoCompat?): Boolean {
                return nodeMatchesAnyClassByType(
                    context,
                    node,
                    android.widget.AbsListView::class.java,
                    android.widget.AbsSpinner::class.java,
                    android.widget.ScrollView::class.java,
                    android.widget.HorizontalScrollView::class.java,
                    CLASS_TOUCHWIZ_TWABSLISTVIEW
                )
            }
        }

        private val FILTER_SCROLL_FORWARD = NodeActionFilter(
            AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
        )

        private val FILTER_SCROLL_BACKWARD = NodeActionFilter(
            AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD
        )

        /**
         * Convenience class for a [NodeFilter] that checks whether nodes
         * support a specific action or set of actions.
         */
        private class NodeActionFilter(actionMask: Int) : NodeFilter() {
            private val mAction: Int = actionMask

            override fun accept(context: Context?, node: AccessibilityNodeInfoCompat?): Boolean {
                return node != null && (node.actions and mAction) == mAction
            }
        }
    }

    /**
     * Compares two AccessibilityNodeInfos in left-to-right and top-to-bottom fashion.
     */
    class TopToBottomLeftToRightComparator : Comparator<AccessibilityNodeInfoCompat> {
        private val mFirstBounds = Rect()
        private val mSecondBounds = Rect()

        private companion object {
            const val BEFORE = -1
            const val AFTER = 1
        }

        override fun compare(
            first: AccessibilityNodeInfoCompat, second: AccessibilityNodeInfoCompat
        ): Int {
            val firstBounds = mFirstBounds
            first.getBoundsInScreen(firstBounds)

            val secondBounds = mSecondBounds
            second.getBoundsInScreen(secondBounds)

            // First is entirely above second.
            if (firstBounds.bottom <= secondBounds.top) {
                return BEFORE
            }

            // First is entirely below second.
            if (firstBounds.top >= secondBounds.bottom) {
                return AFTER
            }

            // Smaller left-bound.
            val leftDifference = firstBounds.left - secondBounds.left
            if (leftDifference != 0) {
                return leftDifference
            }

            // Smaller top-bound.
            val topDifference = firstBounds.top - secondBounds.top
            if (topDifference != 0) {
                return topDifference
            }

            // Smaller bottom-bound.
            val bottomDifference = firstBounds.bottom - secondBounds.bottom
            if (bottomDifference != 0) {
                return bottomDifference
            }

            // Smaller right-bound.
            val rightDifference = firstBounds.right - secondBounds.right
            if (rightDifference != 0) {
                return rightDifference
            }

            // Just break the tie somehow. Deterministic tie breaking.
            return first.hashCode() - second.hashCode()
        }
    }
}
