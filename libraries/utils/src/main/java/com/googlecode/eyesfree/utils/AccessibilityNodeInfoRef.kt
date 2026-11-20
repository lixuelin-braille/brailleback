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

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * A class that simplifies traversal of node trees.
 *
 * This class keeps track of an [AccessibilityNodeInfoCompat] object and can
 * traverse to other nodes in the tree, or be reset to other nodes.  The node
 * can be owned, in which case it will be recycled when traversed away from or
 * when a new node is assigned to an object of this class.
 *
 * Any node can be assigned to objects of this class, including nodes that are
 * not visible to the user.  The traversal methods, however, will only traverse
 * to visible nodes.
 *
 * @see AccessibilityNodeInfoUtils.isVisibleOrLegacy
 */
class AccessibilityNodeInfoRef private constructor(
    private var mNode: AccessibilityNodeInfoCompat?,
    private var mOwned: Boolean
) {
    constructor() : this(null, false)

    /** Returns the current node. */
    fun get(): AccessibilityNodeInfoCompat? = mNode

    /**
     * Clears this object, recycling the underlying node if owned. This object
     * should not be used after this method is called.
     */
    // TODO: Add a pool if proven necessary.
    fun recycle() {
        clear()
    }

    /** Clears this object, recycling the underlying node if owned. */
    fun clear() {
        reset(null as AccessibilityNodeInfoCompat?)
    }

    /**
     * Resets this object to contain a new node, taking ownership of the new
     * node.
     */
    fun reset(newNode: AccessibilityNodeInfoCompat?) {
        if (mNode !== newNode && mNode != null && mOwned) {
            mNode?.recycle()
        }
        mNode = newNode
        mOwned = true
    }

    /**
     * Resets this object with the node held by [newNode]. If [newNode] was
     * owning the node, ownership is transferred to this object.
     */
    fun reset(newNode: AccessibilityNodeInfoRef?) {
        reset(newNode?.get())
        mOwned = newNode?.mOwned ?: false
        newNode?.mOwned = false
    }

    /** Creates a new instance of this class containing a new copy of [node]. */
    companion object {
        @JvmStatic
        fun obtain(node: AccessibilityNodeInfoCompat?): AccessibilityNodeInfoRef {
            return AccessibilityNodeInfoRef(AccessibilityNodeInfoCompat.obtain(node), true)
        }

        /** Creates a new instance of this class without assuming ownership of [node]. */
        @JvmStatic
        fun unOwned(node: AccessibilityNodeInfoCompat?): AccessibilityNodeInfoRef? {
            return if (node != null) AccessibilityNodeInfoRef(node, false) else null
        }

        /** Creates a new instance of this class taking ownership of [node]. */
        @JvmStatic
        fun owned(node: AccessibilityNodeInfoCompat?): AccessibilityNodeInfoRef? {
            return if (node != null) AccessibilityNodeInfoRef(node, true) else null
        }

        /**
         * Creates an [AccessibilityNodeInfoRef] with a refreshed copy of [node],
         * taking ownership of the copy. If [node] is `null`, `null` is returned.
         */
        @JvmStatic
        fun refreshed(node: AccessibilityNodeInfoCompat?): AccessibilityNodeInfoRef? {
            return owned(AccessibilityNodeInfoUtils.refreshNode(node))
        }

        @JvmStatic
        fun isNull(ref: AccessibilityNodeInfoRef?): Boolean {
            return ref == null || ref.get() == null
        }
    }

    /**
     * Makes sure that this object owns its own copy of the node it holds by
     * creating a new copy of the node if not already owned or doing nothing
     * otherwise.
     */
    fun makeOwned(): AccessibilityNodeInfoRef {
        if (mNode != null && !mOwned) {
            reset(AccessibilityNodeInfoCompat.obtain(mNode))
        }
        return this
    }

    /**
     * Releases the ownership of the underlying node if it was owned, returning
     * the underlying node.  This is typically chained with [makeOwned] to have
     * a copy that can be put in another container or [AccessibilityNodeInfoRef].
     * After this call, this object still refers to the underlying node so that
     * any of the traversal methods can be used afterwards.
     */
    fun release(): AccessibilityNodeInfoCompat? {
        mOwned = false
        return mNode
    }

    /**
     * Traverses to the last child of this node, returning `true` on success.
     */
    fun lastChild(): Boolean {
        val node = mNode ?: return false
        if (node.childCount < 1) {
            return false
        }
        for (i in node.childCount - 1 downTo 0) {
            val newNode = node.getChild(i)
            if (newNode == null) {
                return false
            }
            if (AccessibilityNodeInfoUtils.isVisibleOrLegacy(newNode)) {
                reset(newNode)
                return true
            }
            newNode.recycle()
        }
        return false
    }

    /**
     * Traverses to the previous sibling of this node within its parent,
     * returning `true` on success.
     */
    fun previousSibling(): Boolean {
        val node = mNode ?: return false
        val parent = node.parent ?: return false
        return try {
            val childNumber = getChildNumber(parent)
            for (i in childNumber - 1 downTo 0) {
                val newNode = parent.getChild(i)
                if (newNode == null) {
                    return false
                }
                if (AccessibilityNodeInfoUtils.isVisibleOrLegacy(newNode)) {
                    reset(newNode)
                    return true
                }
                newNode.recycle()
            }
            false
        } finally {
            parent.recycle()
        }
    }

    /**
     * Traverses to the first child of this node if any, returning `true` on
     * success.
     */
    fun firstChild(): Boolean {
        val node = mNode ?: return false
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val newNode = node.getChild(i)
            if (newNode == null) {
                return false
            }
            if (AccessibilityNodeInfoUtils.isVisibleOrLegacy(newNode)) {
                reset(newNode)
                return true
            }
            newNode.recycle()
        }
        return false
    }

    /**
     * Traverses to the next sibling of this node within its parent, returning
     * `true` on success.
     */
    fun nextSibling(): Boolean {
        val node = mNode ?: return false
        val parent = node.parent ?: return false
        return try {
            val childCount = parent.childCount
            val childNumber = getChildNumber(parent)
            if (childNumber < 0) {
                return false
            }
            for (i in childNumber + 1 until childCount) {
                val newNode = parent.getChild(i)
                if (newNode == null) {
                    return false
                }
                if (AccessibilityNodeInfoUtils.isVisibleOrLegacy(newNode)) {
                    reset(newNode)
                    return true
                }
                newNode.recycle()
            }
            false
        } finally {
            parent.recycle()
        }
    }

    /**
     * Traverses to the parent of this node, returning `true` on success. On
     * failure, returns `false` and does not move.
     */
    fun parent(): Boolean {
        val node = mNode ?: return false
        var parentNode = node.parent
        while (parentNode != null) {
            if (AccessibilityNodeInfoUtils.isVisibleOrLegacy(parentNode)) {
                reset(parentNode)
                return true
            }
            val tmp = parentNode.parent
            parentNode.recycle()
            parentNode = tmp
        }
        return false
    }

    private fun getChildNumber(parent: AccessibilityNodeInfoCompat): Int {
        var ret = -1
        val childCount = parent.childCount
        var i = 0
        while (i < childCount && ret < 0) {
            val child = parent.getChild(i)
            if (mNode?.equals(child) == true) {
                ret = i
            }
            if (child != null) {
                child.recycle()
            }
            i++
        }
        return ret
    }

    /**
     * Traverses to the next node in depth-first order, returning `true` on
     * success.
     */
    fun nextInOrder(): Boolean {
        if (mNode == null) {
            return false
        }
        if (firstChild()) {
            return true
        }
        if (nextSibling()) {
            return true
        }
        val tmp = unOwned(mNode) ?: return false
        while (tmp.parent()) {
            if (tmp.nextSibling()) {
                reset(tmp)
                return true
            }
        }
        tmp.clear()
        return false
    }

    /**
     * Traverses to the previous node in depth-first order, returning `true` on
     * success.
     */
    fun previousInOrder(): Boolean {
        if (mNode == null) {
            return false
        }
        if (previousSibling()) {
            lastDescendant()
            return true
        }
        return parent()
    }

    /**
     * Traverses to the last descendant of this node, returning `true` on
     * success.
     */
    fun lastDescendant(): Boolean {
        if (!lastChild()) {
            return false
        }
        while (lastChild()) {
            // Nothing to do.
        }
        return true
    }
}
