/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.text.TextUtils
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Field
import java.util.Collections

/**
 * Contains static UI automation helper methods.
 */
object AutomationUtils {
    /**
     * Returns the internal Android string corresponding to a specific resource
     * name.
     *
     * @param context The parent context.
     * @param resName The short name of the resource, e.g. "ok".
     * @return An internal string, or `null`.
     */
    @JvmStatic
    fun getInternalString(context: Context, resName: String): String? {
        val internalRes = CompatUtils.getClass("com.android.internal.R\$string")
        val resField = CompatUtils.getField(internalRes, resName)
        val resId = CompatUtils.getFieldValue(null, 0, resField) as? Int ?: 0
        if (resId <= 0) {
            return null
        }
        return context.getString(resId)
    }

    /**
     * Returns the string corresponding to a resource name in a specific package.
     *
     * @param context The parent context.
     * @param packageName The application's package name, e.g. "com.android.settings".
     * @param resName The short name of the resource, e.g. "ok".
     * @return A package-specific string, or `null`.
     */
    @JvmStatic
    fun getPackageString(context: Context, packageName: String, resName: String): String? {
        val pm = context.packageManager

        val packageRes = try {
            pm.getResourcesForApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            return null
        }

        val resId = packageRes.getIdentifier(resName, "string", packageName)
        if (resId <= 0) {
            return null
        }

        return try {
            packageRes.getString(resId)
        } catch (e: Resources.NotFoundException) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Attempts to perform an accessibility action on a view that is an instance
     * of the class [className], the text or content description [text], and
     * exists under a parent view [root].
     *
     * @param context The parent context.
     * @param root The parent view's node.
     * @param className The class name to match.
     * @param text The text or content description to match.
     * @param action The action to perform.
     * @param arguments The action arguments.
     * @return `true` if the action was performed.
     */
    @JvmStatic
    fun performActionOnView(
        context: Context,
        root: AccessibilityNodeInfoCompat,
        className: CharSequence?,
        text: String?,
        action: Int,
        arguments: Bundle?
    ): Boolean {
        val matches = findViewsWithText(context, root, className, text)
        if (matches.size != 1) {
            return false
        }
        val node = matches[0]
        return node.performAction(action, arguments)
    }

    /**
     * Returns a list of nodes under the [root] node that match the class
     * specified by [className] and exactly match the text or content description
     * specified by [text].
     */
    private fun findViewsWithText(
        context: Context,
        root: AccessibilityNodeInfoCompat,
        className: CharSequence?,
        text: String?
    ): List<AccessibilityNodeInfoCompat> {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val results = ArrayList<AccessibilityNodeInfoCompat>()
        for (node in nodes) {
            if (nodeMatchesFilter(context, node, className, text)) {
                results.add(node)
            }
        }
        return Collections.unmodifiableList(results)
    }

    /**
     * Returns whether a node matches the class specified by [referenceClassName]
     * and exactly match the text or content description specified by [findText].
     */
    private fun nodeMatchesFilter(
        context: Context,
        node: AccessibilityNodeInfoCompat,
        referenceClassName: CharSequence?,
        findText: String?
    ): Boolean {
        val loader = ClassLoadingManager.getInstance()
        val nodeClass = node.className
        val nodePackage = node.packageName

        if (!loader.checkInstanceOf(context, nodeClass, nodePackage, referenceClassName)) {
            return false
        }

        val nodeText = node.text
        if (TextUtils.equals(findText, nodeText)) {
            return true
        }

        val nodeDesc = node.contentDescription
        return TextUtils.equals(findText, nodeDesc)
    }
}
