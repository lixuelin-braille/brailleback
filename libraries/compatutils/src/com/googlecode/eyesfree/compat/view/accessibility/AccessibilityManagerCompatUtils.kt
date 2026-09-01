/*
 * Copyright (C) Google Inc.
 * Licensed under the Apache License, Version 2.0
 */

package com.googlecode.eyesfree.compat.view.accessibility

import android.view.accessibility.AccessibilityManager
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method

object AccessibilityManagerCompatUtils {
    private val CLASS_AccessibilityManager: Class<*> = AccessibilityManager::class.java
    private val METHOD_isTouchExplorationEnabled: Method? =
        CompatUtils.getMethod(CLASS_AccessibilityManager, "isTouchExplorationEnabled")

    /**
     * Returns if the touch exploration in the system is enabled.
     *
     * **Note:** Added in API level 14, always returns false in earlier versions.
     *
     * @return True if touch exploration is enabled, false otherwise.
     */
    @JvmStatic
    fun isTouchExplorationEnabled(receiver: AccessibilityManager): Boolean {
        return CompatUtils.invoke(receiver, false, METHOD_isTouchExplorationEnabled) as Boolean
    }
}
