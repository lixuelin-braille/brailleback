/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.rule

import android.content.Context
import android.text.Editable
import android.widget.AbsListView
import android.widget.GridView
import android.widget.ScrollView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.brailleback.R
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils

/**
 * Rule for formatting certain large vertical containers (such as lists and
 * grids).  A generic description of the view is added and child nodes are not
 * included.
 */
internal class VerticalContainerBrailleRule : BrailleRule {

    override fun accept(context: Context, node: AccessibilityNodeInfoCompat): Boolean {
        return AccessibilityNodeInfoUtils.nodeMatchesAnyClassByType(
            context, node,
            AbsListView::class.java,
            ScrollView::class.java)
    }

    override fun format(result: Editable, context: Context, node: AccessibilityNodeInfoCompat) {
        val empty = node.childCount == 0
        val res: Int
        if (AccessibilityNodeInfoUtils.nodeMatchesClassByType(
                context, node, GridView::class.java)) {
            res = if (empty) R.string.type_emptygridview else R.string.type_gridview
        } else if (AccessibilityNodeInfoUtils.nodeMatchesClassByType(
                context, node, ScrollView::class.java)) {
            res = if (empty) R.string.type_emptyscrollview else R.string.type_scrollview
        } else {
            res = if (empty) R.string.type_emptylistview else R.string.type_listview
        }
        result.append(context.getString(res))
    }

    override fun includeChildren(node: AccessibilityNodeInfoCompat, context: Context): Boolean {
        return false
    }
}
