/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.eyesfree.utils

import android.content.Context
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.util.LinkedList

/**
 * Filters [AccessibilityNodeInfoCompat]s.
 */
abstract class NodeFilter {
    /**
     * Returns whether the specified node matches the filter.
     *
     * @param context The parent context.
     * @param node The node info to filter.
     * @return `true` if the node is accepted.
     */
    abstract fun accept(context: Context?, node: AccessibilityNodeInfoCompat?): Boolean

    /**
     * Returns the logical AND of this and the specified filter.
     *
     * @param filter The filter to AND this filter with.
     * @return A filter where calling `accept()` returns the result of
     *         `(this.accept() && filter.accept())`.
     */
    open fun and(filter: NodeFilter?): NodeFilter {
        if (filter == null) {
            return this
        }
        return NodeFilterAnd(this, filter)
    }

    /**
     * Returns the logical OR of this and the specified filter.
     *
     * @param filter The filter to OR this filter with.
     * @return A filter where calling `accept()` returns the result of
     *         `(this.accept() || filter.accept())`.
     */
    open fun or(filter: NodeFilter?): NodeFilter {
        if (filter == null) {
            return this
        }
        return NodeFilterOr(this, filter)
    }

    private class NodeFilterAnd(lhs: NodeFilter, rhs: NodeFilter) : NodeFilter() {
        private val filters: LinkedList<NodeFilter> =
            LinkedList<NodeFilter>().apply {
                add(lhs)
                add(rhs)
            }

        override fun accept(context: Context?, node: AccessibilityNodeInfoCompat?): Boolean {
            for (filter in filters) {
                if (!filter.accept(context, node)) {
                    return false
                }
            }
            return true
        }

        override fun and(filter: NodeFilter?): NodeFilter {
            filters.add(filter ?: return this)
            return this
        }
    }

    private class NodeFilterOr(lhs: NodeFilter, rhs: NodeFilter) : NodeFilter() {
        private val filters: LinkedList<NodeFilter> =
            LinkedList<NodeFilter>().apply {
                add(lhs)
                add(rhs)
            }

        override fun accept(context: Context?, node: AccessibilityNodeInfoCompat?): Boolean {
            for (filter in filters) {
                if (filter.accept(context, node)) {
                    return true
                }
            }
            return false
        }

        override fun or(filter: NodeFilter?): NodeFilter {
            filters.add(filter ?: return this)
            return this
        }
    }
}
