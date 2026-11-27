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

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoRef
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.SettingsStore
import com.linxi.brailleback.utils.AccessibilityEventUtils

/**
 * 焦点解析与上显反馈：从 DefaultNavigationMode 拆出来的第三半。
 *
 * 当前焦点取数（含纯盲文模式下的逻辑焦点）、节点上显、事件内容格式化与
 * 首可聚焦节点兜底。焦点回退口径、Pan 策略与逻辑焦点刷新原样保留；
 * 写成同包扩展函数（internal）。
 */

internal fun DefaultNavigationMode.getFocusedNode(
        fallbackOnRoot: Boolean): AccessibilityNodeInfoCompat? {
    return FocusFinder.getFocusedNode(mAccessibilityService,
            fallbackOnRoot)
}

/**
 * Returns the node that braille navigation should move from.
 *
 * In braille-only mode this is the mode's own logical focus, which is kept
 * separately from system accessibility focus so TalkBack does not announce
 * every braille movement.  Outside that mode it is the system focus, as in
 * the original BrailleBack behaviour.
 */
internal fun DefaultNavigationMode.currentFocusedNode(
        fallbackOnRoot: Boolean): AccessibilityNodeInfoCompat? {
    if (isQuietNavigationEnabled()) {
        val current = mLastFocusedNode.get()
        if (current != null) {
            val refreshed = AccessibilityNodeInfoUtils.refreshNode(current)
            if (refreshed != null && refreshed.isVisibleToUser) {
                return refreshed
            }
            AccessibilityNodeInfoUtils.recycleNodes(refreshed)
            // In braille-only mode the system focus often belongs to
            // another screen reader or to a control such as an edit text
            // at the bottom of the screen.  Never let that focus hijack
            // braille navigation when the logical focus was scrolled out
            // of view.  Stay inside the last scrollable container, or
            // keep the current display while a scroll update is pending.
            if (!mScrollUpdatePending) {
                val fallback = findFocusableInLastScrollContainer()
                if (fallback != null) {
                    return fallback
                }
            }
            return null
        }
    }
    return getFocusedNode(fallbackOnRoot)
}

/**
 * Moves the system accessibility focus, or in braille-only mode updates
 * only the braille display and the mode's logical focus.
 */
internal fun DefaultNavigationMode.focusOrBraille(node: AccessibilityNodeInfoCompat): Boolean {
    if (!isQuietNavigationEnabled()) {
        return node.performAction(
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    }
    // 用点显器移动即进入独占：之后系统重置焦点的那些事件不再改写盲文，
    // 直到用户真的用手指碰屏幕，或换了窗口。
    mIgnoreFocusUntilTouch = true
    rememberScrollContainer(node)
    val content = mNodeBrailler.brailleNode(node, node) ?: return false
    content.setPanStrategy(Content.PAN_CURSOR)
    mLastFocusedNode.reset(AccessibilityNodeInfoCompat.obtain(node))
    mDisplayManager.setContent(content)
    return true
}

internal fun DefaultNavigationMode.isQuietNavigationEnabled(): Boolean {
    return SettingsStore.get(mAccessibilityService)
            .getBoolean(
                    mAccessibilityService.getString(R.string.pref_quiet_navigation_key),
                    mAccessibilityService.resources.getBoolean(
                            R.bool.pref_quiet_navigation_default))
}

/**
 * Formats some braille content from an [AccessibilityEvent].
 *
 * @param event The event from which to format an utterance.
 * @return The formatted utterance.
 */
internal fun DefaultNavigationMode.formatEventToBraille(
        event: AccessibilityEvent): Content {
    val eventNode = getNodeFromEvent(event)
    if (eventNode != null) {
        val ret = mNodeBrailler.brailleNode(eventNode)
        if (ret != null) {
            ret.setPanStrategy(Content.PAN_CURSOR)
            mLastFocusedNode.reset(eventNode)
            return ret
        }
    }

    // Fall back on putting the event text on the display.
    // TODO: This can interfere with what's on the display and should be
    // done in a more disciplined manner.
    LogUtils.log(this, Log.VERBOSE,
            "No node on event, falling back on event text")
    mLastFocusedNode.clear()
    return Content(
            AccessibilityEventUtils.getEventText(event))
}

internal fun DefaultNavigationMode.brailleNodeFromEvent(event: AccessibilityEvent) {
    mDisplayManager.setContent(
            formatEventToBraille(event))
}

internal fun DefaultNavigationMode.brailleFocusedNode(): Boolean {
    val focused = currentFocusedNode(false) ?: return false
    try {
        val quiet = isQuietNavigationEnabled()
        val content = mNodeBrailler.brailleNode(
                focused, if (quiet) focused else null) ?: return false
        if (focused == mLastFocusedNode.get()
                && content.getPanStrategy()
                == Content.PAN_RESET) {
            content.setPanStrategy(Content.PAN_KEEP)
        }
        mDisplayManager.setContent(content)
        mLastFocusedNode.reset(AccessibilityNodeInfoCompat.obtain(focused))
        return true
    } finally {
        AccessibilityNodeInfoUtils.recycleNodes(focused)
    }
}

internal fun DefaultNavigationMode.brailleFirstFocusableNode() {
    val unwrappedRoot = mAccessibilityService.getRootInActiveWindow()
            ?: return
    var root: AccessibilityNodeInfoCompat? =
            AccessibilityNodeInfoCompat(unwrappedRoot)
    var toBraille: AccessibilityNodeInfoCompat? = null
    if (AccessibilityNodeInfoUtils.shouldFocusNode(
                    mAccessibilityService, root)) {
        toBraille = root
        root = null
    } else {
        toBraille = mFocusFinder.linear(root,
                FocusFinder.SEARCH_FORWARD)
        if (toBraille == null) {
            // Fall back on root as a last resort.
            toBraille = root
            root = null
        }
    }
    val quiet = isQuietNavigationEnabled()
    // 走到这里 toBraille 必非空，但跨 if-else 的赋值不在智能转换范围内，显式收窄。
    val brailleTarget = toBraille ?: return
    val content = mNodeBrailler.brailleNode(
            brailleTarget, if (quiet) brailleTarget else null)
    if (content != null) {
        if (AccessibilityNodeInfoRef.isNull(mLastFocusedNode)
                && content.getPanStrategy()
                == Content.PAN_RESET) {
            content.setPanStrategy(Content.PAN_KEEP)
        }
        mDisplayManager.setContent(content)
        if (quiet) {
            mLastFocusedNode.reset(AccessibilityNodeInfoCompat.obtain(brailleTarget))
        }
    }
    if (!quiet) {
        mLastFocusedNode.clear()
    }
    AccessibilityNodeInfoUtils.recycleNodes(root, toBraille)
}

internal fun DefaultNavigationMode.getNodeFromEvent(
        event: AccessibilityEvent): AccessibilityNodeInfoCompat? {
    val node = event.source ?: return null
    return AccessibilityNodeInfoCompat(node)
}
