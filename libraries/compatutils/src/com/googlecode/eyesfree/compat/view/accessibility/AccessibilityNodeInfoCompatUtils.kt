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

package com.googlecode.eyesfree.compat.view.accessibility

import android.util.Log
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method

object AccessibilityNodeInfoCompatUtils {
    private val TAG: String = AccessibilityNodeInfoCompatUtils::class.java.simpleName

    private val CLASS_AccessibilityNodeInfo: Class<*>? =
        CompatUtils.getClass("android.view.accessibility.AccessibilityNodeInfo")
    private val METHOD_getLabelFor: Method? =
        CompatUtils.getMethod(CLASS_AccessibilityNodeInfo, "getLabelFor")
    private val METHOD_getLabeledBy: Method? =
        CompatUtils.getMethod(CLASS_AccessibilityNodeInfo, "getLabeledBy")

    @JvmStatic
    fun getLabelFor(node: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? {
        val info = node.info ?: run {
            Log.e(TAG, "Compat node was missing internal node")
            return null
        }
        val resultInfo = CompatUtils.invoke(info, null, METHOD_getLabelFor) ?: return null
        return AccessibilityNodeInfoCompat(resultInfo)
    }

    @JvmStatic
    fun getLabeledBy(node: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? {
        val info = node.info ?: run {
            Log.e(TAG, "Compat node was missing internal node")
            return null
        }
        val resultInfo = CompatUtils.invoke(info, null, METHOD_getLabeledBy) ?: return null
        return AccessibilityNodeInfoCompat(resultInfo)
    }
}
