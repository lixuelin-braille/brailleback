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

package com.googlecode.eyesfree.utils

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityEventCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.core.view.accessibility.AccessibilityRecordCompat
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import java.util.LinkedList

/**
 * Implements a simplified version of an accessibility node provider.
 *
 * This should be applied to the parent view using
 * [ViewCompat.setAccessibilityDelegate]:
 *
 * ```
 * mHelper = ExploreByTouchHelper(host)
 * ViewCompat.setAccessibilityDelegate(host, mHelper)
 * ```
 */
@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
abstract class ExploreByTouchHelper(host: View) : AccessibilityDelegateCompat() {

    /** Virtual node identifier value for invalid nodes. */
    companion object {
        const val INVALID_ID = Int.MIN_VALUE

        /** Virtual node identifier value for the fake root node. */
        const val ROOT_ID = Int.MIN_VALUE + 1

        private const val DEFAULT_CLASS_NAME = "\$VirtualView"
    }

    // Temporary, reusable data structures.
    private val mTempScreenRect = Rect()
    private val mTempParentRect = Rect()
    private val mTempVisibleRect = Rect()
    private val mTempGlobalRect = IntArray(2)

    /** The accessibility manager, used to check state and send events. */
    private val mManager: AccessibilityManager

    /** The view whose internal structure is exposed through this helper. */
    private val mHost: View

    /** The virtual view id for the currently focused item. */
    private var mFocusedVirtualViewId = INVALID_ID

    /** The virtual view id for the currently hovered item. */
    private var mHoveredVirtualViewId = INVALID_ID

    init {
        mHost = host
        mManager = host.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }

    /**
     * Returns the [AccessibilityNodeProviderCompat] for this helper.
     *
     * @return The accessibility node provider for this helper.
     */
    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat {
        return mNodeProvider
    }

    /**
     * Dispatches hover [MotionEvent]s to the virtual view hierarchy when
     * the Explore by Touch feature is enabled.
     *
     * @param event The hover event to dispatch to the virtual view hierarchy.
     * @return Whether the hover event was handled.
     */
    fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (!mManager.isTouchExplorationEnabled) {
            return false
        }

        var virtualViewId = getVirtualViewIdAt(event.x, event.y)
        if (virtualViewId == INVALID_ID) {
            virtualViewId = ROOT_ID
        }

        when (event.action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> setHoveredVirtualViewId(virtualViewId)
            MotionEvent.ACTION_HOVER_EXIT -> setHoveredVirtualViewId(virtualViewId)
        }

        return true
    }

    /**
     * Populates an event of the specified type with information about an item
     * and attempts to send it up through the view hierarchy.
     *
     * @param virtualViewId The virtual view id for which to send an event.
     * @param eventType The type of event to send.
     * @return `true` if the event was sent successfully.
     */
    fun sendEventForVirtualViewId(virtualViewId: Int, eventType: Int): Boolean {
        if (virtualViewId == INVALID_ID || !mManager.isEnabled) {
            return false
        }

        val group = mHost.parent as ViewGroup? ?: return false

        val event: AccessibilityEvent
        event = if (virtualViewId == ROOT_ID) {
            getEventForRoot(eventType)
        } else {
            getEventForVirtualViewId(virtualViewId, eventType)
        }

        return group.requestSendAccessibilityEvent(mHost, event)
    }

    /**
     * Notifies the accessibility framework that the properties of the parent
     * view have changed.
     */
    fun invalidateRoot() {
        invalidateVirtualViewId(ROOT_ID)
    }

    /**
     * Notifies the accessibility framework that the properties of a particular
     * item have changed.
     *
     * @param virtualViewId The virtual view id to invalidate.
     */
    fun invalidateVirtualViewId(virtualViewId: Int) {
        sendEventForVirtualViewId(virtualViewId, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    /**
     * Sets the currently hovered item, sending hover accessibility events as
     * necessary to maintain the correct state.
     *
     * @param virtualViewId The virtual view id for the item currently being
     * hovered, or [INVALID_ID] if no item is hovered within the parent view.
     */
    private fun setHoveredVirtualViewId(virtualViewId: Int) {
        if (mHoveredVirtualViewId == virtualViewId) {
            return
        }

        val previousVirtualViewId = mHoveredVirtualViewId
        mHoveredVirtualViewId = virtualViewId

        // Stay consistent with framework behavior by sending ENTER/EXIT pairs
        // in reverse order. This is accurate as of API 18.
        sendEventForVirtualViewId(virtualViewId, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
        sendEventForVirtualViewId(previousVirtualViewId, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
    }

    private fun getEventForRoot(eventType: Int): AccessibilityEvent {
        val event = AccessibilityEvent.obtain(eventType)
        mHost.onInitializeAccessibilityEvent(event)

        val record = AccessibilityRecordCompat(event)
        record.setSource(mHost, ROOT_ID)

        return event
    }

    /**
     * Constructs and returns an [AccessibilityEvent] populated with
     * information about the specified item.
     *
     * @param virtualViewId The virtual view id for the item for which to
     * construct an event.
     * @param eventType The type of event to construct.
     * @return An [AccessibilityEvent] populated with information about the
     * specified item.
     */
    private fun getEventForVirtualViewId(virtualViewId: Int, eventType: Int): AccessibilityEvent {
        val event = AccessibilityEvent.obtain(eventType)

        // Ensure the client has good defaults.
        event.isEnabled = true
        event.className = mHost.javaClass.name + DEFAULT_CLASS_NAME

        // Allow the client to populate the event.
        populateEventForVirtualViewId(virtualViewId, event)

        if (event.text.isEmpty() && TextUtils.isEmpty(event.contentDescription)) {
            throw RuntimeException(
                "You must add text or a content description in populateEventForItem()"
            )
        }

        // Don't allow the client to override these properties.
        event.packageName = mHost.context.packageName

        // Virtual view hierarchies are only supported in API 16+.
        val record = AccessibilityRecordCompat(event)
        record.setSource(mHost, virtualViewId)

        return event
    }

    /**
     * Constructs and returns an [AccessibilityNodeInfoCompat] for the
     * parent view populated with its virtual descendants.
     *
     * @return An [AccessibilityNodeInfoCompat] for the parent view.
     */
    private fun getNodeForHost(): AccessibilityNodeInfoCompat {
        // Since we don't want the parent to be focusable, but we can't remove
        // actions from a node, copy over the necessary fields.
        val result = AccessibilityNodeInfoCompat.obtain(mHost)
        val source = AccessibilityNodeInfoCompat.obtain(mHost)
        ViewCompat.onInitializeAccessibilityNodeInfo(mHost, source)

        // Copy over parent and screen bounds.
        source.getBoundsInParent(mTempParentRect)
        source.getBoundsInScreen(mTempScreenRect)
        result.setBoundsInParent(mTempParentRect)
        result.setBoundsInScreen(mTempScreenRect)

        // Set up the parent view, if applicable.
        val parent = ViewCompat.getParentForAccessibility(mHost)
        if (parent is View) {
            result.setParent(parent)
        }

        // Populate the minimum required fields.
        result.isVisibleToUser = source.isVisibleToUser
        result.packageName = source.packageName
        result.className = source.className

        // Add the fake root node.
        result.addChild(mHost, ROOT_ID)

        return result
    }

    /**
     * Constructs and returns an [AccessibilityNodeInfoCompat] for the
     * parent view populated with its virtual descendants.
     *
     * @return An [AccessibilityNodeInfoCompat] for the parent view.
     */
    private fun getNodeForRoot(): AccessibilityNodeInfoCompat {
        // The root node is identical to the parent node, except that it is a
        // child of the parent view and is populated with virtual descendants.
        val node = AccessibilityNodeInfoCompat.obtain(mHost)
        ViewCompat.onInitializeAccessibilityNodeInfo(mHost, node)

        // Add the virtual descendants.
        val virtualViewIds = LinkedList<Int>()
        getVisibleVirtualViewIds(virtualViewIds)

        for (virtualViewId in virtualViewIds) {
            node.addChild(mHost, virtualViewId)
        }

        // Set up the node as a child of the parent.
        node.setParent(mHost)
        node.setSource(mHost, ROOT_ID)

        return node
    }

    /**
     * Constructs and returns an [AccessibilityNodeInfoCompat] for the
     * specified item. Automatically manages accessibility focus actions.
     *
     * @param virtualViewId The virtual view id for item for which to construct
     * a node.
     * @return An [AccessibilityNodeInfoCompat] for the specified item.
     */
    private fun getNodeForVirtualViewId(virtualViewId: Int): AccessibilityNodeInfoCompat {
        val node = AccessibilityNodeInfoCompat.obtain()

        // Ensure the client has good defaults.
        node.isEnabled = true
        node.className = mHost.javaClass.name + DEFAULT_CLASS_NAME

        // Allow the client to populate the node.
        populateNodeForVirtualViewId(virtualViewId, node)

        if (TextUtils.isEmpty(node.text) && TextUtils.isEmpty(node.contentDescription)) {
            throw RuntimeException(
                "You must add text or a content description in populateNodeForVirtualViewId()"
            )
        }

        // Don't allow the client to override these properties.
        node.packageName = mHost.context.packageName
        node.setParent(mHost, ROOT_ID)
        node.setSource(mHost, virtualViewId)

        // Manage internal accessibility focus state.
        if (mFocusedVirtualViewId == virtualViewId) {
            node.isAccessibilityFocused = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        } else {
            node.isAccessibilityFocused = false
            node.addAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS)
        }

        node.getBoundsInParent(mTempParentRect)
        if (mTempParentRect.isEmpty) {
            throw RuntimeException(
                "You must set parent bounds in populateNodeForVirtualViewId()"
            )
        }

        // Set the visibility based on the parent bound.
        if (intersectVisibleToUser(mTempParentRect)) {
            node.isVisibleToUser = true
            node.setBoundsInParent(mTempParentRect)
        }

        // Calculate screen-relative bound.
        mHost.getLocationOnScreen(mTempGlobalRect)
        val offsetX = mTempGlobalRect[0]
        val offsetY = mTempGlobalRect[1]
        mTempScreenRect.set(mTempParentRect)
        mTempScreenRect.offset(offsetX, offsetY)
        node.setBoundsInScreen(mTempScreenRect)

        return node
    }

    /**
     * Computes whether the specified [Rect] intersects with the visible
     * portion of its parent [View]. Modifies `localRect` to contain
     * only the visible portion.
     *
     * @param localRect A rectangle in local (parent) coordinates.
     * @return Whether the specified [Rect] is visible on the screen.
     */
    private fun intersectVisibleToUser(localRect: Rect?): Boolean {
        // Missing or empty bounds mean this view is not visible.
        if (localRect == null || localRect.isEmpty) {
            return false
        }

        // Attached to invisible window means this view is not visible.
        if (mHost.windowVisibility != View.VISIBLE) {
            return false
        }

        // An invisible predecessor or one with alpha zero means
        // that this view is not visible to the user.
        var current: Any? = this
        while (current is View) {
            val view = current
            // We have attach info so this view is attached and there is no
            // need to check whether we reach to ViewRootImpl on the way up.
            if (view.alpha <= 0 || view.visibility != View.VISIBLE) {
                return false
            }
            current = view.parent
        }

        // If no portion of the parent is visible, this view is not visible.
        if (!mHost.getLocalVisibleRect(mTempVisibleRect)) {
            return false
        }

        // Check if the view intersects the visible portion of the parent.
        return localRect.intersect(mTempVisibleRect)
    }

    /**
     * Exposes a virtual view hierarchy to the accessibility framework. Only
     * supported in API 16+.
     */
    private val mNodeProvider = object : AccessibilityNodeProviderCompat() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfoCompat {
            if (virtualViewId == View.NO_ID) {
                return getNodeForHost()
            } else if (virtualViewId == ROOT_ID) {
                return getNodeForRoot()
            }

            return getNodeForVirtualViewId(virtualViewId)
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle): Boolean {
            var handled = false

            when (action) {
                AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS -> {
                    // Only handle the FOCUS action if it's placing focus on
                    // a different view that was previously focused.
                    if (mFocusedVirtualViewId != virtualViewId) {
                        mFocusedVirtualViewId = virtualViewId
                        mHost.invalidate()
                        sendEventForVirtualViewId(
                            virtualViewId,
                            AccessibilityEventCompat.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                        )
                        handled = true
                    }
                }
                AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (mFocusedVirtualViewId == virtualViewId) {
                        mFocusedVirtualViewId = INVALID_ID
                    }
                    // Since we're managing focus at the parent level, we are
                    // likely to receive a FOCUS action before a CLEAR_FOCUS
                    // action. We'll give the benefit of the doubt to the
                    // framework and always handle FOCUS_CLEARED.
                    mHost.invalidate()
                    sendEventForVirtualViewId(
                        virtualViewId,
                        AccessibilityEventCompat.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
                    )
                    handled = true
                }
                else -> {
                    // Let the node provider handle focus for the root node, but
                    // the root node should handle everything else by itself.
                    if (virtualViewId == View.NO_ID) {
                        return ViewCompat.performAccessibilityAction(mHost, action, arguments)
                    }
                }
            }

            // Since the client implementation may want to do something special
            // when a FOCUS event occurs, let them handle all events.
            handled = handled or performActionForVirtualViewId(virtualViewId, action, arguments)

            return handled
        }
    }

    /**
     * Returns the virtual view id for the item under the specified
     * parent-relative coordinates.
     *
     * @param x The parent-relative x coordinate.
     * @param y The parent-relative y coordinate.
     * @return The item under coordinates (x,y).
     */
    protected abstract fun getVirtualViewIdAt(x: Float, y: Float): Int

    /**
     * Populates a list with the parent view's visible items. The ordering of
     * items within `virtualViewIds` specifies order of accessibility
     * focus traversal.
     *
     * @param virtualViewIds The list to populate with visible items.
     */
    protected abstract fun getVisibleVirtualViewIds(virtualViewIds: MutableList<Int>)

    /**
     * Populates an event with information about the specified item.
     *
     * @param virtualViewId The virtual view id for the item for which to
     * populate the event.
     * @param event The event to populate.
     */
    protected abstract fun populateEventForVirtualViewId(virtualViewId: Int, event: AccessibilityEvent)

    /**
     * Populates a node with information about the specified item.
     *
     * @param virtualViewId The virtual view id for the item for which to
     * populate the node.
     * @param node The node to populate.
     */
    protected abstract fun populateNodeForVirtualViewId(virtualViewId: Int, node: AccessibilityNodeInfoCompat)

    /**
     * Performs an accessibility action on the specified item.
     *
     * @param virtualViewId The virtual view id for the item on which to perform
     * the action.
     * @param action The accessibility action to perform.
     * @param arguments Arguments for the action, or optionally `null`.
     * @return `true` if the action was performed successfully.
     */
    protected abstract fun performActionForVirtualViewId(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?
    ): Boolean
}
