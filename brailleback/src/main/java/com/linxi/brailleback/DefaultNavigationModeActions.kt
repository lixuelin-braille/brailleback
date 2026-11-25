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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoRef
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.WebInterfaceUtils

/**
 * 节点动作：从 DefaultNavigationMode 拆出来的第二半。
 *
 * 激活/长按、按 HTML 元素跳转、增量搜索切换，以及自盲文节点的点位点击伪动作。
 * 动作选择顺序与伪动作编号区间原样保留；写成同包扩展函数（internal）。
 */

private const val ACTION_TOGGLE_INCREMENTAL_SEARCH =
        WebInterfaceUtils.ACTION_TOGGLE_INCREMENTAL_SEARCH

// Actions in this range are reserved for Braille use.
// TODO: use event arguments instead of this hack.
private const val ACTION_BRAILLE_CLICK_MAX = -275000000
private const val ACTION_BRAILLE_CLICK_MIN = -275999999

internal fun DefaultNavigationMode.navigateHTMLElementAction(direction: Int, element: String): Boolean {
    val currentNode = currentFocusedNode(true)
    try {
        if (currentNode != null
                && WebInterfaceUtils.supportsWebActions(currentNode)
                && WebInterfaceUtils.performNavigationToHtmlElementAction(
                        currentNode, direction, element)) {
            return true
        }
        return false
    } finally {
        AccessibilityNodeInfoUtils.recycleNodes(currentNode)
    }
}

internal fun DefaultNavigationMode.handleIncrementalSearchAction(): Boolean {
    val currentNode = currentFocusedNode(true)
    try {
        if (currentNode != null
                && WebInterfaceUtils.hasLegacyWebContent(currentNode)
                && mFeedbackManager.emitOnFailure(
                        WebInterfaceUtils.performSpecialAction(currentNode,
                                ACTION_TOGGLE_INCREMENTAL_SEARCH),
                        FeedbackManager.TYPE_COMMAND_FAILED)) {
            return true
        }
        return false
    } finally {
        AccessibilityNodeInfoUtils.recycleNodes(currentNode)
    }
}

internal fun DefaultNavigationMode.activateNode(node: AccessibilityNodeInfoCompat?): Boolean {
    return activateNode(node, -1)
}

internal fun DefaultNavigationMode.activateNode(node: AccessibilityNodeInfoCompat?,
                                 position: Int): Boolean {
    if (node == null) {
        return false
    }
    val current = AccessibilityNodeInfoRef.unOwned(node)
            ?: return false
    try {
        do {
            val cur = current.get() ?: break
            LogUtils.log(this, Log.VERBOSE,
                    "Considering to click: %s",
                    cur.getInfo())
            val supportedActions = cur.actions
            var action = 0
            // For edit texts, the click action doesn't currently focus
            // the view, so we special case it here.
            // TODO: Revise when that changes.
            if (AccessibilityNodeInfoUtils.nodeMatchesClassByType(
                            mAccessibilityService, cur,
                            EditText::class.java)) {
                action = if ((supportedActions and
                                AccessibilityNodeInfo.ACTION_FOCUS) != 0) {
                    AccessibilityNodeInfo.ACTION_FOCUS
                } else {
                    // Put accessibility focus on the field.  If it is
                    // already focused and the IME is selected, that will
                    // activate the editing.
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                }
            } else if (position >= 0 && isSelfBrailled(cur)
                    && (supportedActions
                            and AccessibilityNodeInfo.ACTION_CLICK) != 0) {
                // Generate a fake "action". For instance, a click at
                // position 33 maps to -275000033.
                // TODO: Remove this hack when a better way to pass this
                // information exists.
                var fakeAction = ACTION_BRAILLE_CLICK_MAX - position
                if (fakeAction < ACTION_BRAILLE_CLICK_MIN) {
                    LogUtils.log(this, Log.WARN,
                            "underflow activating node %s at position %d",
                            cur, position)
                    fakeAction = ACTION_BRAILLE_CLICK_MIN
                } else if (fakeAction > ACTION_BRAILLE_CLICK_MAX) {
                    LogUtils.log(this, Log.WARN,
                            "overflow activating node %s at position %d",
                            cur, position)
                    fakeAction = ACTION_BRAILLE_CLICK_MAX
                }
                if (WebInterfaceUtils.performSpecialAction(cur,
                                fakeAction)) {
                    return true
                }
            } else if ((supportedActions
                            and AccessibilityNodeInfo.ACTION_CLICK) != 0) {
                action = AccessibilityNodeInfo.ACTION_CLICK
            }
            if (action != 0 && cur.performAction(action)) {
                return true
            } else {
                LogUtils.log(this, Log.VERBOSE, "Action %d failed",
                        action)
            }
        } while (current.parent())
    } finally {
        current.recycle()
    }
    LogUtils.log(this, Log.VERBOSE, "Click action failed")
    return false
}

internal fun DefaultNavigationMode.longClickNode(node: AccessibilityNodeInfoCompat?): Boolean {
    if (node == null) {
        return false
    }
    val current = AccessibilityNodeInfoRef.unOwned(node)
            ?: return false
    try {
        do {
            val cur = current.get() ?: break
            LogUtils.log(this, Log.VERBOSE,
                    "Considering to long click: %s",
                    cur.getInfo())
            if (cur.performAction(
                            AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                return true
            }
        } while (current.parent())
    } finally {
        current.recycle()
    }
    LogUtils.log(this, Log.VERBOSE, "Long click action failed")
    return false
}

internal fun DefaultNavigationMode.isSelfBrailled(node: AccessibilityNodeInfoCompat): Boolean {
    return mSelfBrailleManager.hasContentForNode(node)
}
