/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.text.TextUtils
import android.util.Log
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.util.HashSet

/**
 * Util class to help debug Node trees.
 */
object TreeDebug {
    /**
     * Logs the tree using the input node as the root.
     */
    @JvmStatic
    fun logNodeTree(node: AccessibilityNodeInfoCompat?) {
        if (node == null) {
            return
        }
        val seen = HashSet<AccessibilityNodeInfoCompat>()
        logNodeTree(AccessibilityNodeInfoCompat.obtain(node), "", seen)
        for (n in seen) {
            n.recycle()
        }
    }

    private fun logNodeTree(
        node: AccessibilityNodeInfoCompat,
        indent: String,
        seen: HashSet<AccessibilityNodeInfoCompat>
    ) {
        if (!seen.add(node)) {
            LogUtils.log(TreeDebug::class.java, Log.VERBOSE, "Cycle: %d", node.hashCode())
            return
        }

        // Include the hash code as a "poor man's" id, knowing that it might not
        // always be unique.
        LogUtils.log(
            TreeDebug::class.java, Log.VERBOSE, "%s(%d)%s",
            indent, node.hashCode(), nodeDebugDescription(node))

        val childIndent = indent + "  "
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child == null) {
                LogUtils.log(
                    TreeDebug::class.java, Log.VERBOSE,
                    "%sCouldn't get child %d", childIndent, i)
                continue
            }
            logNodeTree(child, childIndent, seen)
        }
    }

    private fun appendSimpleName(sb: StringBuilder, fullName: CharSequence) {
        var dotIndex = TextUtils.lastIndexOf(fullName, '.')
        if (dotIndex < 0) {
            dotIndex = 0
        }
        sb.append(fullName, dotIndex, fullName.length)
    }

    /**
     * Gets a description of the properties of a node.
     */
    @JvmStatic
    fun nodeDebugDescription(node: AccessibilityNodeInfoCompat): CharSequence {
        val sb = StringBuilder()
        sb.append(node.windowId)

        if (node.className != null) {
            appendSimpleName(sb, node.className)
        } else {
            sb.append("??")
        }

        if (!node.isVisibleToUser) {
            sb.append(":invisible")
        }

        if (node.text != null) {
            sb.append(":")
            sb.append(node.text.toString().trim())
        }

        if (node.contentDescription != null) {
            sb.append(":")
            sb.append(node.contentDescription.toString().trim())
        }

        val actions = node.actions
        if (actions != 0) {
            sb.append(":")
            if (actions and AccessibilityNodeInfoCompat.ACTION_FOCUS != 0) {
                sb.append("F")
            }
            if (actions and AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS != 0) {
                sb.append("A")
            }
            if (actions and AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS != 0) {
                sb.append("a")
            }
            if (actions and AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD != 0) {
                sb.append("-")
            }
            if (actions and AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD != 0) {
                sb.append("+")
            }
        }

        if (node.isCheckable) {
            sb.append(":")
            if (node.isChecked) {
                sb.append("(X)")
            } else {
                sb.append("( )")
            }
        }

        if (node.isFocusable) {
            sb.append(":focusable")
        }
        if (node.isFocused) {
            sb.append(":focused")
        }
        if (node.isSelected) {
            sb.append(":selected")
        }
        if (node.isClickable) {
            sb.append(":clickable")
        }
        if (node.isLongClickable) {
            sb.append(":longClickable")
        }
        if (node.isAccessibilityFocused) {
            sb.append(":accessibilityFocused")
        }
        if (!node.isEnabled) {
            sb.append(":disabled")
        }

        return sb.toString()
    }
}
