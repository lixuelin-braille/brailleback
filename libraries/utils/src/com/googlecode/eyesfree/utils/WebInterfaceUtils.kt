/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.utils

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils

object WebInterfaceUtils {
    private const val ACCESSIBILITY_SCRIPT_INJECTION = "accessibility_script_injection"

    const val DIRECTION_FORWARD: Int = 1
    const val DIRECTION_BACKWARD: Int = -1
    const val ACTION_READ_CURRENT_HTML_ELEMENT: Int = -1
    const val ACTION_READ_PAGE_TITLE_ELEMENT: Int = -2
    const val ACTION_STOP_SPEECH: Int = -3
    private const val ACTION_TOGGLE_SPECIAL_CONTENT: Int = -4
    const val ACTION_TOGGLE_INCREMENTAL_SEARCH: Int = -5
    const val HTML_ELEMENT_MOVE_BY_SECTION: String = "SECTION"
    const val HTML_ELEMENT_MOVE_BY_LIST: String = "LIST"
    const val HTML_ELEMENT_MOVE_BY_CONTROL: String = "CONTROL"

    @JvmStatic
    fun performNavigationToHtmlElementAction(
        node: AccessibilityNodeInfoCompat, direction: Int, htmlElement: String
    ): Boolean {
        val action = if (direction == DIRECTION_FORWARD)
            AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT
        else
            AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT
        val args = Bundle()
        args.putString(
            AccessibilityNodeInfoCompat.ACTION_ARGUMENT_HTML_ELEMENT_STRING, htmlElement
        )
        return node.performAction(action, args)
    }

    @JvmStatic
    fun performNavigationByDOMObject(node: AccessibilityNodeInfoCompat, direction: Int): Boolean {
        val action = if (direction == DIRECTION_FORWARD)
            AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT
        else
            AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT
        return node.performAction(action)
    }

    @JvmStatic
    fun performNavigationAtGranularityAction(
        node: AccessibilityNodeInfoCompat, direction: Int, granularity: Int
    ): Boolean {
        val action = if (direction == DIRECTION_FORWARD)
            AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        else
            AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
        val args = Bundle()
        args.putInt(
            AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity
        )
        return node.performAction(action, args)
    }

    @JvmStatic
    fun performSpecialAction(node: AccessibilityNodeInfoCompat, action: Int): Boolean =
        performSpecialAction(node, action, DIRECTION_FORWARD)

    @JvmStatic
    fun performSpecialAction(node: AccessibilityNodeInfoCompat, action: Int, direction: Int): Boolean =
        performNavigationAtGranularityAction(node, direction, action)

    @JvmStatic
    fun setSpecialContentModeEnabled(node: AccessibilityNodeInfoCompat, enabled: Boolean): Boolean {
        val direction = if (enabled) DIRECTION_FORWARD else DIRECTION_BACKWARD
        return performSpecialAction(node, ACTION_TOGGLE_SPECIAL_CONTENT, direction)
    }

    @JvmStatic
    fun supportsWebActions(node: AccessibilityNodeInfoCompat?): Boolean {
        if (node == null) {
            return false
        }
        return AccessibilityNodeInfoUtils.supportsAnyAction(
            node,
            AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT,
            AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT
        )
    }

    @JvmStatic
    fun hasNativeWebContent(node: AccessibilityNodeInfoCompat?): Boolean {
        if (node == null) {
            return false
        }
        if (!supportsWebActions(node)) {
            return false
        }
        val parent = node.parent
        if (supportsWebActions(parent)) {
            parent?.recycle()
            return true
        }
        parent?.recycle()
        return node.childCount > 0
    }

    @JvmStatic
    fun hasLegacyWebContent(node: AccessibilityNodeInfoCompat?): Boolean {
        if (node == null) {
            return false
        }
        if (!supportsWebActions(node)) {
            return false
        }
        val parent = node.parent
        if (supportsWebActions(parent)) {
            parent?.recycle()
            return false
        }
        parent?.recycle()
        return node.childCount == 0
    }

    @JvmStatic
    fun isScriptInjectionEnabled(context: Context): Boolean {
        val injectionSetting = Settings.Secure.getInt(
            context.contentResolver, ACCESSIBILITY_SCRIPT_INJECTION, 0
        )
        return injectionSetting == 1
    }

    @JvmStatic
    fun hasNavigableWebContent(context: Context, node: AccessibilityNodeInfoCompat?): Boolean =
        (supportsWebActions(node) && isScriptInjectionEnabled(context)) || hasNativeWebContent(node)
}
