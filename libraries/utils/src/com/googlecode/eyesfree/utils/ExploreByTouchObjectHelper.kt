package com.googlecode.eyesfree.utils

import android.annotation.TargetApi
import android.os.Build
import android.os.Bundle
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.view.View
import android.view.accessibility.AccessibilityEvent
import java.util.ArrayList

/**
 * Extension of [ExploreByTouchHelper] for custom views that rely on a single
 * class for logical units.
 */
@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
abstract class ExploreByTouchObjectHelper<T>(parentView: View) : ExploreByTouchHelper(parentView) {

    fun sendEventForItem(item: T, eventType: Int): Boolean {
        val virtualViewId = getVirtualViewIdForItem(item)
        return sendEventForVirtualViewId(virtualViewId, eventType)
    }

    fun invalidateItem(item: T) {
        sendEventForItem(item, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    override fun performActionForVirtualViewId(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        val item = getItemForVirtualViewId(virtualViewId) ?: return false
        return performActionForItem(item, action, arguments)
    }

    override fun populateEventForVirtualViewId(virtualViewId: Int, event: AccessibilityEvent) {
        val item = getItemForVirtualViewId(virtualViewId) ?: return
        populateEventForItem(item, event)
        event.setClassName(item.javaClass.name)
    }

    override fun populateNodeForVirtualViewId(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        val item = getItemForVirtualViewId(virtualViewId) ?: return
        populateNodeForItem(item, node)
        node.setClassName(item.javaClass.name)
    }

    override fun getVisibleVirtualViewIds(virtualViewIds: MutableList<Int>) {
        val items = ArrayList<T>()
        getVisibleItems(items)
        for (item in items) {
            virtualViewIds.add(getVirtualViewIdForItem(item))
        }
    }

    override fun getVirtualViewIdAt(x: Float, y: Float): Int {
        val item = getItemAt(x, y) ?: return ExploreByTouchHelper.INVALID_ID
        return getVirtualViewIdForItem(item)
    }

    protected abstract fun performActionForItem(item: T, action: Int, arguments: Bundle?): Boolean

    protected abstract fun populateEventForItem(item: T, event: AccessibilityEvent)

    protected abstract fun populateNodeForItem(item: T, node: AccessibilityNodeInfoCompat)

    protected abstract fun getVisibleItems(items: List<T>)

    protected abstract fun getItemAt(x: Float, y: Float): T?

    protected abstract fun getVirtualViewIdForItem(item: T): Int

    protected abstract fun getItemForVirtualViewId(id: Int): T?
}
