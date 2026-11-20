/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.rule

import android.content.Context
import android.text.Editable
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Decides how to format a single node for output on a braille display.
 */
interface BrailleRule {

    /** Returns whether this rule should be used for this node. */
    fun accept(context: Context, node: AccessibilityNodeInfoCompat): Boolean

    /** Formats `node` for braille output, adding the result to `result`. */
    fun format(result: Editable, context: Context, node: AccessibilityNodeInfoCompat)

    /**
     * Returns `true` if the children of this node should be appended after
     * formatting with this rule.
     */
    fun includeChildren(node: AccessibilityNodeInfoCompat, context: Context): Boolean
}
