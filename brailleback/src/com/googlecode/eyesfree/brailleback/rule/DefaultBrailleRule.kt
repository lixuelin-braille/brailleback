/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.rule

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.TextUtils
import android.widget.AbsSeekBar
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.QuickContactBadge
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.brailleback.BrailleBackService
import com.googlecode.eyesfree.brailleback.FocusFinder
import com.googlecode.eyesfree.brailleback.R
import com.googlecode.eyesfree.brailleback.utils.LabelingUtils
import com.googlecode.eyesfree.brailleback.utils.StringUtils
import com.googlecode.eyesfree.labeling.CustomLabelManager
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils

/**
 * Default rule that adds the text of the node and a check mark if the node is
 * checkable.
 */
internal class DefaultBrailleRule : BrailleRule {

    private var mLabelManager: CustomLabelManager? = null

    override fun accept(context: Context, node: AccessibilityNodeInfoCompat): Boolean {
        return true
    }

    override fun format(result: Editable, context: Context, node: AccessibilityNodeInfoCompat) {
        val oldLength = result.length

        var text: CharSequence? = null

        val labeledBy = node.labeledBy
        if (labeledBy != null) {
            text = LabelingUtils.getNodeText(labeledBy, getLabelManager())
            labeledBy.recycle()
        }
        if (text == null) {
            text = LabelingUtils.getNodeText(node, getLabelManager())
        }
        if (text == null) {
            text = ""
        }

        result.append(text)

        if (node.isCheckable || node.isChecked) {
            val mark: CharSequence = if (node.isChecked) {
                context.getString(R.string.checkmark_checked)
            } else {
                context.getString(R.string.checkmark_not_checked)
            }
            StringUtils.appendWithSpaces(result, mark)
        }

        if (oldLength == result.length
            && AccessibilityNodeInfoUtils.isActionableForAccessibility(node)) {
            result.append(getFallbackText(context, node))
        }
    }

    override fun includeChildren(node: AccessibilityNodeInfoCompat, context: Context): Boolean {
        // This is intended to deal with containers that have content
        // descriptions (such as in the launcher).  This might need to be
        // refined if it takes away text that won't get accessibility focus by
        // itself.
        if (!TextUtils.isEmpty(node.contentDescription)) {
            val firstDescendant = FocusFinder.findFirstFocusableDescendant(node, context)

            AccessibilityNodeInfoUtils.recycleNodes(firstDescendant)
            return firstDescendant == null
        }
        return true
    }

    private fun getFallbackText(context: Context, node: AccessibilityNodeInfoCompat): CharSequence {
        // Order is important below because of class inheritance.
        if (matchesAny(context, node, Button::class.java, ImageButton::class.java)) {
            return context.getString(R.string.type_button)
        }
        if (matchesAny(context, node, QuickContactBadge::class.java)) {
            return context.getString(R.string.type_quickcontact)
        }
        if (matchesAny(context, node, ImageView::class.java)) {
            return context.getString(R.string.type_image)
        }
        if (matchesAny(context, node, EditText::class.java)) {
            return context.getString(R.string.type_edittext)
        }
        if (matchesAny(context, node, AbsSeekBar::class.java)) {
            return context.getString(R.string.type_seekbar)
        }
        return ""
    }

    private fun matchesAny(
        context: Context, node: AccessibilityNodeInfoCompat, vararg classes: Class<*>
    ): Boolean {
        for (clazz in classes) {
            if (AccessibilityNodeInfoUtils.nodeMatchesClassByType(context, node, clazz)) {
                return true
            }
        }
        return false
    }

    /** Retrieves a handle to the correct [CustomLabelManager]. */
    private fun getLabelManager(): CustomLabelManager? {
        if (Build.VERSION.SDK_INT >= CustomLabelManager.MIN_API_LEVEL) {
            val service = BrailleBackService.getActiveInstance()
            if (service != null) {
                return service.getLabelManager()
            }
        }
        return null
    }
}
