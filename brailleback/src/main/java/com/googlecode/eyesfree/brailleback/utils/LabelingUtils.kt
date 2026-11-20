/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.utils

import android.text.TextUtils
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.labeling.CustomLabelManager
import com.googlecode.eyesfree.labeling.Label
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils

/**
 * Utility class for getting node text, falling back to label manager (Kotlin 版).
 *
 * TODO: Put this into AccessibilityNodeInfoUtils when support libs fixed so that
 *       we don't need to unwrap the node to get view id resource name.
 */
object LabelingUtils {

    /**
     * Gets the text of a `node` by returning the content description
     * (if available) or by returning the text. Will use the specified
     * [CustomLabelManager] as a fall back if both are null.
     * If the label manager is null, does the same function as
     * [AccessibilityNodeInfoUtils.getNodeText].
     *
     * @param node The node.
     * @param labelManager The label manager.
     * @return The node text.
     */
    @JvmStatic
    fun getNodeText(node: AccessibilityNodeInfoCompat, labelManager: CustomLabelManager?): CharSequence? {
        val text = AccessibilityNodeInfoUtils.getNodeText(node)
        if (!TextUtils.isEmpty(text)) {
            return text
        }

        if (labelManager != null && labelManager.isInitialized) {
            // TODO: Don't need to do this when support libs fixed.
            val unwrapped = node.info as? android.view.accessibility.AccessibilityNodeInfo
            val label: Label? = labelManager.getLabelForViewIdFromCache(
                unwrapped?.viewIdResourceName)
            if (label != null) {
                return label.text
            }
        }
        return null
    }
}
